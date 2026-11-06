package com.turbodrop.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ContentResolver;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class FastStreamSender {

    public static final int MODE_HYBRID = 0;
    public static final int MODE_WIFI_ONLY = 1;
    public static final int MODE_BT_ONLY = 2;

    public interface BatchListener {
        void onProgress(int fileIndex, int totalFiles, String filename,
                        long fileSent, long fileTotal,
                        long batchSent, long batchTotal,
                        double wifiSpeedMb, double btSpeedKb, int etaSec);
        void onLog(String msg);
        void onFailover(String alert);
        void onFileCompleted(int fileIndex, int totalFiles, String filename);
        void onBatchCompleted(int totalFiles, long totalBytes, long durationMs);
        void onError(String error);
    }

    private static final byte[] MAGIC = new byte[]{'T', 'U', 'R', 'B'};
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int BUFFER_SIZE = 512 * 1024; // 512 KB buffer optimal for TCP wire throughput

    private final ContentResolver contentResolver;
    private final List<FileItem> fileItems;
    private final String wifiIp;
    private final int wifiPort;
    private final String btMac;
    private final int mode;
    private final BatchListener listener;
    private String securityPin = "";

    public void setSecurityPin(String pin) {
        this.securityPin = pin != null ? pin.trim() : "";
    }

    private final long totalBatchBytes;
    private final AtomicLong batchBytesCompleted = new AtomicLong(0);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Speed metrics
    private final AtomicLong wifiBytesInterval = new AtomicLong(0);
    private final AtomicLong btBytesInterval = new AtomicLong(0);
    private volatile double currentWifiSpeedMb = 0.0;
    private volatile double currentBtSpeedKb = 0.0;

    // Active sockets
    private volatile Socket wifiSocket = null;
    private volatile OutputStream wifiOut = null;
    private volatile InputStream wifiIn = null;

    private volatile BluetoothSocket btSocket = null;
    private volatile OutputStream btOut = null;
    private volatile InputStream btIn = null;

    // Background Wi-Fi probe for seamless Hybrid re-escalation
    private final AtomicReference<Socket> restoredWifiSocket = new AtomicReference<>(null);
    private final AtomicBoolean isProbingWifi = new AtomicBoolean(false);

    // Prefetch queue for zero inter-file latency
    private final BlockingQueue<PreparedFile> prefetchQueue = new ArrayBlockingQueue<>(3);
    private final AtomicBoolean prefetchDone = new AtomicBoolean(false);

    private static class PreparedFile {
        final FileItem item;
        final int fileIndex;
        final int totalFiles;
        final ParcelFileDescriptor pfd;
        final boolean isValid;

        PreparedFile(FileItem item, int fileIndex, int totalFiles, ParcelFileDescriptor pfd, boolean isValid) {
            this.item = item;
            this.fileIndex = fileIndex;
            this.totalFiles = totalFiles;
            this.pfd = pfd;
            this.isValid = isValid;
        }

        void close() {
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
        }
    }

    public FastStreamSender(ContentResolver contentResolver, List<FileItem> fileItems,
                            String wifiIp, int wifiPort, String btMac, int mode,
                            BatchListener listener) {
        this.contentResolver = contentResolver;
        this.fileItems = fileItems;
        this.wifiIp = wifiIp;
        this.wifiPort = wifiPort;
        this.btMac = btMac;
        this.mode = mode;
        this.listener = listener;

        long total = 0;
        for (FileItem item : fileItems) {
            total += item.filesize;
        }
        this.totalBatchBytes = total;
    }

    public void start() {
        isRunning.set(true);
        long startTime = System.currentTimeMillis();

        String modeName = (mode == MODE_WIFI_ONLY) ? "📶 Wi-Fi Only (Turbo)" :
                          (mode == MODE_BT_ONLY) ? "ᛒ Bluetooth Only" : "⚡ Hybrid (Wi-Fi + Auto BT Failover)";

        String startMsg = "Starting " + modeName + " | Files: " + fileItems.size() + " (" + formatBytes(totalBatchBytes) + ")";
        AppLogger.i(startMsg);
        listener.onLog("🚀 " + startMsg);

        // 1. Metric reporter
        new Thread(this::metricLoop, "MetricThread").start();

        // 2. Prefetch worker (Pre-opens files in background so network pipe NEVER starves!)
        new Thread(this::prefetchWorker, "PrefetchWorkerThread").start();

        // 3. Main Network Streaming Worker
        new Thread(() -> {
            try {
                // Elevate Linux thread priority to FOREGROUND to prevent CPU core throttling
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND);
                } catch (Exception ignored) {}

                // Stop any Bluetooth discovery scan to prevent 2.4 GHz RF coexistence interference with Wi-Fi
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null && adapter.isDiscovering()) {
                        adapter.cancelDiscovery();
                    }
                } catch (SecurityException ignored) {}

                // Connect primary socket
                if (mode == MODE_WIFI_ONLY || mode == MODE_HYBRID) {
                    connectWifiPersistent(false);
                } else if (mode == MODE_BT_ONLY) {
                    connectBluetoothImmediate();
                }

                if (wifiSocket == null && btSocket == null) {
                    listener.onError("Could not establish connection to receiver.");
                    isRunning.set(false);
                    return;
                }

                // Transfer Permission Handshake
                boolean accepted = performTransferRequestHandshake();
                if (!accepted) {
                    listener.onLog("⚠️ Transfer declined by receiver.");
                    listener.onError("Transfer declined by receiver.");
                    isRunning.set(false);
                    return;
                }

                listener.onLog("✅ Transfer accepted! Wire streaming at maximum speed...");

                byte[] streamBuffer = new byte[BUFFER_SIZE];

                for (int i = 0; i < fileItems.size(); i++) {
                    if (!isRunning.get()) break;

                    PreparedFile prep = null;
                    while (isRunning.get() && prep == null) {
                        if (prefetchDone.get() && prefetchQueue.isEmpty()) {
                            break;
                        }
                        try {
                            prep = prefetchQueue.poll(200, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }

                    if (prep == null) break;

                    if (!prep.isValid) {
                        listener.onLog("⚠️ Skipping unreadable: " + prep.item.relativePath);
                        prep.close();
                        continue;
                    }

                    // Stream file with persistent reconnect and zero inter-file delay
                    boolean success = streamPreparedFile(prep, streamBuffer);
                    prep.close();

                    if (success) {
                        batchBytesCompleted.addAndGet(prep.item.filesize);
                        listener.onFileCompleted(prep.fileIndex, prep.totalFiles, prep.item.relativePath);
                    } else if (isRunning.get()) {
                        listener.onError("Error streaming: " + prep.item.relativePath);
                        break;
                    }
                }

                // Send BATCH_DONE at the end of the entire batch
                if (isRunning.get()) {
                    sendBatchDone();
                }

                if (isRunning.getAndSet(false)) {
                    long duration = System.currentTimeMillis() - startTime;
                    double sec = duration / 1000.0;
                    double avgMb = (totalBatchBytes / (1024.0 * 1024.0)) / Math.max(0.05, sec);
                    String compMsg = "Completed: " + fileItems.size() + " files (" + formatBytes(totalBatchBytes) + ") in " + String.format("%.2fs", sec) + " (Avg: " + String.format("%.1f MB/s", avgMb) + ")";
                    AppLogger.i(compMsg);
                    closeSockets();
                    listener.onBatchCompleted(fileItems.size(), totalBatchBytes, duration);
                }

            } catch (Exception e) {
                AppLogger.e("Pipelined stream fatal exception", e);
                if (isRunning.get()) {
                    listener.onError("Transfer error: " + e.getMessage());
                }
            } finally {
                closeSockets();
            }
        }, "ZeroLatencySenderThread").start();
    }

    public void stop() {
        AppLogger.i("Transfer cancelled by user");
        isRunning.set(false);
        closeSockets();
    }

    private boolean performTransferRequestHandshake() {
        try {
            JSONObject req = new JSONObject();
            req.put("type", "TRANSFER_REQUEST");
            req.put("sender_name", Build.MANUFACTURER + " " + Build.MODEL);
            req.put("total_files", fileItems.size());
            req.put("total_bytes", totalBatchBytes);
            req.put("pin", securityPin);

            byte[] jsonBytes = req.toString().getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(8 + jsonBytes.length);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.put(MAGIC);
            buf.putInt(jsonBytes.length);
            buf.put(jsonBytes);

            OutputStream out = (wifiOut != null) ? wifiOut : btOut;
            InputStream in = (wifiIn != null) ? wifiIn : btIn;
            if (out == null || in == null) return false;

            listener.onLog("Waiting for receiver to accept transfer (" + fileItems.size() + " files)...");
            out.write(buf.array());
            out.flush();

            // Read response
            byte[] respMagic = readExact(in, 4);
            if (respMagic == null || respMagic[0] != MAGIC[0] || respMagic[1] != MAGIC[1] ||
                respMagic[2] != MAGIC[2] || respMagic[3] != MAGIC[3]) {
                AppLogger.w("Invalid response magic from receiver");
                return false;
            }

            byte[] lenBuf = readExact(in, 4);
            if (lenBuf == null) return false;
            int respLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).getInt();
            if (respLen <= 0 || respLen > 65536) return false;

            byte[] respData = readExact(in, respLen);
            if (respData == null) return false;

            JSONObject respJson = new JSONObject(new String(respData, StandardCharsets.UTF_8));
            return "TRANSFER_ACCEPT".equals(respJson.optString("type"));

        } catch (Exception e) {
            AppLogger.e("Transfer request handshake failed", e);
            return false;
        }
    }

    private void prefetchWorker() {
        try {
            for (int i = 0; i < fileItems.size() && isRunning.get(); i++) {
                FileItem item = fileItems.get(i);
                int fileIdx = i + 1;
                PreparedFile prep;

                try {
                    ParcelFileDescriptor pfd = contentResolver.openFileDescriptor(item.uri, "r");
                    prep = new PreparedFile(item, fileIdx, fileItems.size(), pfd, pfd != null);
                } catch (Throwable t) {
                    AppLogger.w("Prefetch error for " + item.relativePath + ": " + t.getMessage());
                    prep = new PreparedFile(item, fileIdx, fileItems.size(), null, false);
                }

                try {
                    while (isRunning.get()) {
                        if (prefetchQueue.offer(prep, 200, TimeUnit.MILLISECONDS)) {
                            break;
                        }
                    }
                } catch (InterruptedException ie) {
                    prep.close();
                    break;
                }
            }
        } catch (Throwable t) {
            AppLogger.e("Prefetch thread caught error", t);
        } finally {
            prefetchDone.set(true);
        }
    }

    private boolean streamPreparedFile(PreparedFile prep, byte[] buffer) {
        FileItem item = prep.item;
        long offset = 0;

        while (offset < item.filesize && isRunning.get()) {
            // Check if connection is active
            boolean useWifi = (mode != MODE_BT_ONLY && wifiSocket != null && !wifiSocket.isClosed());
            boolean useBt = (!useWifi && btSocket != null && btSocket.isConnected());

            if (!useWifi && !useBt) {
                // Persistent background auto-reconnect
                boolean reconnected = persistentReconnect(offset);
                if (!reconnected) {
                    return false;
                }
                useWifi = (mode != MODE_BT_ONLY && wifiSocket != null && !wifiSocket.isClosed());
            }

            // Close standby Bluetooth if Wi-Fi is active to eliminate 2.4GHz RF coexistence degradation
            if (useWifi && btSocket != null) {
                try { btSocket.close(); } catch (Exception ignored) {}
                btSocket = null;
            }

            // In Hybrid mode on Bluetooth, start background Wi-Fi probe
            if (!useWifi && mode == MODE_HYBRID) {
                startWifiProbeWorker();
            }

            OutputStream out = useWifi ? wifiOut : btOut;
            String transport = useWifi ? "WIFI" : "BLUETOOTH";

            ParcelFileDescriptor pfd = prep.pfd;
            if (pfd == null) {
                try {
                    pfd = contentResolver.openFileDescriptor(item.uri, "r");
                } catch (Exception ignored) {}
            }
            if (pfd == null) return false;

            FileInputStream fis = null;
            try {
                // 1. Send File Header directly into wire
                byte[] header = buildFileHeader(item, prep.fileIndex, prep.totalFiles, offset);
                out.write(header);
                out.flush();

                // 2. Stream File Data directly from FD
                fis = new FileInputStream(pfd.getFileDescriptor());
                if (offset > 0) {
                    long toSkip = offset;
                    while (toSkip > 0) {
                        long skipped = fis.skip(toSkip);
                        if (skipped <= 0) break;
                        toSkip -= skipped;
                    }
                }

                long remaining = item.filesize - offset;
                while (remaining > 0 && isRunning.get()) {
                    // Hybrid mode Wi-Fi re-escalation
                    if (!useWifi && mode == MODE_HYBRID) {
                        Socket newWifi = restoredWifiSocket.getAndSet(null);
                        if (newWifi != null) {
                            try {
                                wifiSocket = newWifi;
                                wifiOut = newWifi.getOutputStream();
                                wifiIn = newWifi.getInputStream();

                                if (btSocket != null) {
                                    try { btSocket.close(); } catch (Exception ignored) {}
                                    btSocket = null;
                                }

                                AppLogger.i("⚡ Wi-Fi Restored! Switching seamlessly back to Wi-Fi at offset " + formatBytes(offset));
                                listener.onLog("⚡ Wi-Fi Restored! Auto-resuming high-speed transfer at " + formatBytes(offset));
                                listener.onFailover("⚡ Wi-Fi Restored: Running at full speed");

                                out.flush();
                                break; // Switch to Wi-Fi transmission loop
                            } catch (Exception err) {
                                AppLogger.e("Error switching to restored Wi-Fi socket", err);
                            }
                        }
                    }

                    int toRead = (int) Math.min(buffer.length, remaining);
                    int r = fis.read(buffer, 0, toRead);
                    if (r < 0) break;

                    out.write(buffer, 0, r);
                    offset += r;
                    remaining -= r;

                    if (useWifi) {
                        wifiBytesInterval.addAndGet(r);
                    } else {
                        btBytesInterval.addAndGet(r);
                    }
                }

                out.flush();
                return offset >= item.filesize;

            } catch (Exception e) {
                AppLogger.e("[" + transport + "] Stream exception at offset " + offset, e);
                if (useWifi) {
                    if (mode == MODE_HYBRID) {
                        listener.onLog("⚠️ Wi-Fi disconnected at " + formatBytes(offset) + "! Falling back to Bluetooth...");
                        listener.onFailover("⚠️ Wi-Fi lost: Transferring over Bluetooth fallback...");
                    } else {
                        listener.onLog("⚠️ Wi-Fi disconnected at " + formatBytes(offset) + ". Auto-reconnecting in background...");
                        listener.onFailover("🔄 Wi-Fi connection lost: Auto-reconnecting...");
                    }
                    try { if (wifiSocket != null) wifiSocket.close(); } catch (Exception ignored) {}
                    wifiSocket = null;
                } else {
                    listener.onLog("⚠️ Bluetooth disconnected at " + formatBytes(offset) + ". Reconnecting...");
                    try { if (btSocket != null) btSocket.close(); } catch (Exception ignored) {}
                    btSocket = null;
                }
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            } finally {
                if (fis != null) {
                    try { fis.close(); } catch (Exception ignored) {}
                }
            }
        }

        return offset >= item.filesize;
    }

    private boolean persistentReconnect(long currentOffset) {
        int attempt = 0;
        int maxAttempts = 60; // Retry for ~90 seconds

        while (isRunning.get() && attempt < maxAttempts) {
            attempt++;
            listener.onFailover(String.format("🔄 Reconnecting in background (attempt %d/%d)...", attempt, maxAttempts));
            listener.onLog(String.format("🔄 Connection dropped at %s. Auto-reconnecting... (attempt %d)", formatBytes(currentOffset), attempt));

            // Try Wi-Fi
            if (mode != MODE_BT_ONLY) {
                connectWifiImmediate();
                if (wifiSocket != null && !wifiSocket.isClosed()) {
                    AppLogger.i("Wi-Fi reconnected successfully on attempt " + attempt);
                    listener.onLog("⚡ Wi-Fi reconnected! Wire stream resumed seamlessly.");
                    listener.onFailover("⚡ Connected: Transferring at full speed");
                    return true;
                }
            }

            // Try Bluetooth
            if (mode == MODE_BT_ONLY || mode == MODE_HYBRID) {
                connectBluetoothImmediate();
                if (btSocket != null && btSocket.isConnected()) {
                    AppLogger.i("Bluetooth reconnected successfully on attempt " + attempt);
                    listener.onLog("ᛒ Bluetooth reconnected! Wire stream resumed.");
                    listener.onFailover("ᛒ Connected: Transferring over Bluetooth");
                    return true;
                }
            }

            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
                break;
            }
        }

        return (wifiSocket != null && !wifiSocket.isClosed()) || (btSocket != null && btSocket.isConnected());
    }

    private void startWifiProbeWorker() {
        if (mode != MODE_HYBRID || !isRunning.get() || isProbingWifi.getAndSet(true)) {
            return;
        }

        new Thread(() -> {
            AppLogger.i("📶 WifiProbeThread started: continuously probing Wi-Fi recovery...");
            while (isRunning.get() && mode == MODE_HYBRID && wifiSocket == null) {
                try {
                    Socket s = new Socket();
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    try { s.setTrafficClass(0x08); } catch (Exception ignored) {}
                    s.connect(new InetSocketAddress(wifiIp, wifiPort), 1000);
                    try {
                        s.setSendBufferSize(4 * 1024 * 1024);
                        s.setReceiveBufferSize(2 * 1024 * 1024);
                    } catch (Exception ignored) {}

                    AppLogger.i("📶 Wi-Fi probe connected successfully! Handing socket to streaming loop.");
                    restoredWifiSocket.set(s);
                    break;
                } catch (Exception e) {
                    try {
                        Thread.sleep(1200);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
            isProbingWifi.set(false);
        }, "WifiProbeThread").start();
    }

    private byte[] buildFileHeader(FileItem item, int fileIdx, int totalFiles, long offset) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("file_index", fileIdx);
            obj.put("total_files", totalFiles);
            obj.put("relative_path", item.relativePath);
            obj.put("filesize", item.filesize);
            obj.put("offset", offset);
            obj.put("batch_total_bytes", totalBatchBytes);

            byte[] jsonBytes = obj.toString().getBytes(StandardCharsets.UTF_8);

            ByteBuffer buf = ByteBuffer.allocate(8 + jsonBytes.length);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.put(MAGIC);
            buf.putInt(jsonBytes.length);
            buf.put(jsonBytes);
            return buf.array();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sendBatchDone() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "BATCH_DONE");
            byte[] jsonBytes = obj.toString().getBytes(StandardCharsets.UTF_8);

            ByteBuffer buf = ByteBuffer.allocate(8 + jsonBytes.length);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.put(MAGIC);
            buf.putInt(jsonBytes.length);
            buf.put(jsonBytes);

            if (wifiOut != null) {
                wifiOut.write(buf.array());
                wifiOut.flush();
                byte[] ackBuf = new byte[10];
                try { wifiIn.read(ackBuf); } catch (Exception ignored) {}
            } else if (btOut != null) {
                btOut.write(buf.array());
                btOut.flush();
                byte[] ackBuf = new byte[10];
                try { btIn.read(ackBuf); } catch (Exception ignored) {}
            }
            AppLogger.i("Pipelined BATCH_DONE complete");
        } catch (Exception ignored) {}
    }

    private void connectWifiPersistent(boolean isResume) {
        if (wifiIp == null || wifiIp.isEmpty()) return;
        connectWifiImmediate();
        if (wifiSocket == null && mode == MODE_HYBRID) {
            connectBluetoothImmediate();
        }
    }

    private void connectWifiImmediate() {
        if (wifiIp == null || wifiIp.isEmpty()) return;
        try {
            Socket s = new Socket();
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            try { s.setTrafficClass(0x08); } catch (Exception ignored) {} // IPTOS_THROUGHPUT QoS
            s.connect(new InetSocketAddress(wifiIp, wifiPort), 2500);
            try {
                s.setSendBufferSize(4 * 1024 * 1024); // 4 MB send buffer for high TCP window scaling
                s.setReceiveBufferSize(2 * 1024 * 1024);
            } catch (Exception ignored) {}
            wifiSocket = s;
            wifiOut = s.getOutputStream(); // Direct raw stream without BufferedOutputStream locking
            wifiIn = s.getInputStream();
            AppLogger.i("Wi-Fi Connected immediately to " + wifiIp + ":" + wifiPort);
            listener.onLog("⚡ Wi-Fi Connected to " + wifiIp);
            listener.onFailover("⚡ Wi-Fi Active: High-Speed Wire Stream Active");
        } catch (Exception e) {
            AppLogger.w("Wi-Fi connect notice: " + e.getMessage());
            wifiSocket = null;
        }
    }

    private void connectBluetoothImmediate() {
        if (btMac == null || btMac.isEmpty()) return;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return;
            BluetoothDevice dev = adapter.getRemoteDevice(btMac);
            try { adapter.cancelDiscovery(); } catch (SecurityException ignored) {}

            BluetoothSocket s = null;
            int[] channels = new int[]{5, 4, 3, 2, 1};
            for (int ch : channels) {
                try {
                    Method m = dev.getClass().getMethod("createInsecureRfcommSocket", int.class);
                    s = (BluetoothSocket) m.invoke(dev, ch);
                    if (s != null) {
                        s.connect();
                        break;
                    }
                } catch (Exception ignored) {}
            }

            if (s == null || !s.isConnected()) {
                s = dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                s.connect();
            }

            btSocket = s;
            btOut = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            btIn = s.getInputStream();
            AppLogger.i("Bluetooth connected to " + btMac);
            listener.onLog("ᛒ Bluetooth Connected to " + btMac);
        } catch (Exception e) {
            AppLogger.w("Bluetooth connect failed: " + e.getMessage());
            btSocket = null;
        }
    }

    private byte[] readExact(InputStream in, int length) {
        byte[] data = new byte[length];
        int total = 0;
        try {
            while (total < length) {
                int r = in.read(data, total, length - total);
                if (r < 0) return null;
                total += r;
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private void metricLoop() {
        long lastTime = System.currentTimeMillis();
        while (isRunning.get()) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {}

            long now = System.currentTimeMillis();
            double elapsedSec = (now - lastTime) / 1000.0;
            if (elapsedSec > 0) {
                long wBytes = wifiBytesInterval.getAndSet(0);
                long bBytes = btBytesInterval.getAndSet(0);

                currentWifiSpeedMb = (wBytes / (1024.0 * 1024.0)) / elapsedSec;
                currentBtSpeedKb = (bBytes / 1024.0) / elapsedSec;
                lastTime = now;

                long sent = batchBytesCompleted.get();
                long total = totalBatchBytes;

                double totalSpeedBytesPerSec = (wBytes + bBytes) / elapsedSec;
                long remainingBytes = Math.max(0, total - sent);
                int etaSec = (totalSpeedBytesPerSec > 0) ? (int) (remainingBytes / totalSpeedBytesPerSec) : 0;

                listener.onProgress(0, fileItems.size(), "",
                        0, 0, sent, total,
                        currentWifiSpeedMb, currentBtSpeedKb, etaSec);
            }
        }
    }

    private void closeSockets() {
        try { if (wifiSocket != null) wifiSocket.close(); } catch (Exception ignored) {}
        try { if (btSocket != null) btSocket.close(); } catch (Exception ignored) {}
        Socket restored = restoredWifiSocket.getAndSet(null);
        if (restored != null) {
            try { restored.close(); } catch (Exception ignored) {}
        }
        wifiSocket = null;
        btSocket = null;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
