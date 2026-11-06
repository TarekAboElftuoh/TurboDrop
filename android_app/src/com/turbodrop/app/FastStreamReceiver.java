package com.turbodrop.app;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class FastStreamReceiver {

    public interface TransferDecisionCallback {
        void onDecision(boolean accept);
    }

    public interface ReceiverListener {
        void onReceiverLog(String msg);
        void onClipboardReceived(String text);
        void onTransferRequested(String senderName, int totalFiles, long totalBytes, TransferDecisionCallback callback);
        void onReceiveFileStart(int fileIndex, int totalFiles, String filename, long filesize);
        void onReceiveProgress(int fileIndex, int totalFiles, String filename,
                               long fileReceived, long fileTotal,
                               long batchReceived, long batchTotal,
                               double speedMb, int etaSec);
        void onReceiveFileCompleted(int fileIndex, int totalFiles, String filename, long filesize);
        void onReceiveBatchCompleted(int totalFiles, long totalBytes, long durationMs, String saveDir);
        void onReceiverError(String error);
    }

    private static final byte[] MAGIC = new byte[]{'T', 'U', 'R', 'B'};
    private static final int BUFFER_SIZE = 512 * 1024; // 512 KB stream buffer

    private final Context context;
    private final ReceiverListener listener;
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private final AtomicBoolean isReceiving = new AtomicBoolean(false);

    private ServerSocket serverSocket = null;
    private Socket activeClientSocket = null;
    private File saveDirectory;

    public FastStreamReceiver(Context context, ReceiverListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        initSaveDirectory();
    }

    private void initSaveDirectory() {
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            saveDirectory = new File(downloads, "TurboDrop");
            if (!saveDirectory.exists()) {
                saveDirectory.mkdirs();
            }
        } catch (Exception e) {
            saveDirectory = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "TurboDrop");
            saveDirectory.mkdirs();
        }
    }

    public File getSaveDirectory() {
        if (saveDirectory == null || !saveDirectory.exists()) {
            initSaveDirectory();
        }
        return saveDirectory;
    }

    public File getDestinationFile(String cleanRel) {
        File baseDir = getSaveDirectory();
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
        File dest = new File(baseDir, cleanRel);
        File parentDir = dest.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        return dest;
    }

    public void sendClipboardToPc(String text, String pcIp, int pcPort) {
        new Thread(() -> {
            try (Socket s = new Socket()) {
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(pcIp, pcPort), 2500);
                JSONObject obj = new JSONObject();
                obj.put("type", "CLIPBOARD_SYNC");
                obj.put("text", text);
                byte[] raw = obj.toString().getBytes(StandardCharsets.UTF_8);
                ByteBuffer buf = ByteBuffer.allocate(8 + raw.length);
                buf.order(ByteOrder.BIG_ENDIAN);
                buf.put(MAGIC);
                buf.putInt(raw.length);
                buf.put(raw);
                s.getOutputStream().write(buf.array());
                s.getOutputStream().flush();
                listener.onReceiverLog("📋 Sent clipboard to PC successfully.");
            } catch (Exception e) {
                listener.onReceiverError("Failed to send clipboard to PC: " + e.getMessage());
            }
        }, "SendClipboardThread").start();
    }

    public void startListening(int port) {
        if (isListening.getAndSet(true)) return;

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                try { serverSocket.setReceiveBufferSize(4 * 1024 * 1024); } catch (Exception ignored) {}
                serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
                AppLogger.i("FastStreamReceiver server listening on port " + port);
                listener.onReceiverLog("📥 Ready to receive from PC (Port " + port + ")");

                while (isListening.get()) {
                    try {
                        Socket client = serverSocket.accept();
                        client.setTcpNoDelay(true);
                        try { client.setReceiveBufferSize(4 * 1024 * 1024); } catch (Exception ignored) {}
                        AppLogger.i("Inbound PC connection accepted from " + client.getInetAddress().getHostAddress());
                        listener.onReceiverLog("⚡ Connected remote sender: " + client.getInetAddress().getHostAddress());

                        new Thread(() -> handleIncomingStream(client, "INBOUND"), "ReceiverStreamThread").start();
                    } catch (Exception e) {
                        if (!isListening.get()) break;
                    }
                }
            } catch (Exception e) {
                AppLogger.e("Receiver server listener error", e);
            }
        }, "ReceiverServerThread").start();
    }

    public void stopListening() {
        isListening.set(false);
        isReceiving.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        try {
            if (activeClientSocket != null) activeClientSocket.close();
        } catch (Exception ignored) {}
    }

    public void pullFromPc(String pcIp, int pcPort) {
        if (isReceiving.get()) {
            listener.onReceiverError("Transfer already in progress!");
            return;
        }

        new Thread(() -> {
            Socket s = null;
            try {
                listener.onReceiverLog("📡 Requesting staged batch from PC (" + pcIp + ":" + pcPort + ")...");
                s = new Socket();
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(pcIp, pcPort), 3000);
                try {
                    s.setReceiveBufferSize(4 * 1024 * 1024);
                    s.setSendBufferSize(2 * 1024 * 1024);
                } catch (Exception ignored) {}

                // Send REQUEST_BATCH command to PC
                JSONObject req = new JSONObject();
                req.put("action", "REQUEST_BATCH");
                byte[] jsonBytes = req.toString().getBytes(StandardCharsets.UTF_8);

                ByteBuffer buf = ByteBuffer.allocate(8 + jsonBytes.length);
                buf.order(ByteOrder.BIG_ENDIAN);
                buf.put(MAGIC);
                buf.putInt(jsonBytes.length);
                buf.put(jsonBytes);

                OutputStream out = s.getOutputStream();
                out.write(buf.array());
                out.flush();

                handleIncomingStream(s, "PULL");

            } catch (Exception e) {
                AppLogger.e("Pull from PC failed", e);
                listener.onReceiverError("Failed to connect to PC to pull files: " + e.getMessage());
                if (s != null) {
                    try { s.close(); } catch (Exception ignored) {}
                }
            }
        }, "PullFromPcThread").start();
    }

    private void handleIncomingStream(Socket socket, String modeName) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND);
        } catch (Exception ignored) {}
        isReceiving.set(true);
        activeClientSocket = socket;
        long batchStartTime = System.currentTimeMillis();
        long totalBatchBytes = 0;
        long totalReceivedBytes = 0;
        int totalFilesCount = 0;
        byte[] readBuffer = new byte[BUFFER_SIZE];

        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            while (isReceiving.get()) {
                // 1. Read 4 bytes MAGIC
                byte[] magicBuf = readExact(in, 4);
                if (magicBuf == null) break; // EOF
                if (magicBuf[0] != MAGIC[0] || magicBuf[1] != MAGIC[1] ||
                    magicBuf[2] != MAGIC[2] || magicBuf[3] != MAGIC[3]) {
                    AppLogger.w("Invalid stream magic");
                    break;
                }

                // 2. Read 4 bytes Header Length
                byte[] lenBuf = readExact(in, 4);
                if (lenBuf == null) break;
                int headerLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).getInt();
                if (headerLen <= 0 || headerLen > 65536) break;

                // 3. Read Header JSON
                byte[] jsonBuf = readExact(in, headerLen);
                if (jsonBuf == null) break;
                String jsonStr = new String(jsonBuf, StandardCharsets.UTF_8);
                JSONObject meta = new JSONObject(jsonStr);

                // Check for Transfer Permission Handshake
                if (meta.optString("type").equals("TRANSFER_REQUEST")) {
                    String senderName = meta.optString("sender_name", "Remote PC");
                    int totalFiles = meta.optInt("total_files", 1);
                    long totalBytes = meta.optLong("total_bytes", 0);

                    AppLogger.i("Incoming transfer request from " + senderName + ": " + totalFiles + " files (" + totalBytes + " bytes)");
                    listener.onReceiverLog("🔔 Transfer request from " + senderName + " (" + totalFiles + " files, " + formatBytes(totalBytes) + ")");

                    final CountDownLatch latch = new CountDownLatch(1);
                    final AtomicBoolean userAccepted = new AtomicBoolean(false);

                    listener.onTransferRequested(senderName, totalFiles, totalBytes, accepted -> {
                        userAccepted.set(accepted);
                        latch.countDown();
                    });

                    try {
                        boolean answered = latch.await(60, TimeUnit.SECONDS);
                        if (!answered) {
                            userAccepted.set(false);
                        }
                    } catch (InterruptedException e) {
                        userAccepted.set(false);
                    }

                    boolean accepted = userAccepted.get();
                    JSONObject resp = new JSONObject();
                    resp.put("type", accepted ? "TRANSFER_ACCEPT" : "TRANSFER_DECLINE");
                    byte[] respBytes = resp.toString().getBytes(StandardCharsets.UTF_8);

                    ByteBuffer respBuf = ByteBuffer.allocate(8 + respBytes.length);
                    respBuf.order(ByteOrder.BIG_ENDIAN);
                    respBuf.put(MAGIC);
                    respBuf.putInt(respBytes.length);
                    respBuf.put(respBytes);

                    out.write(respBuf.array());
                    out.flush();

                    if (!accepted) {
                        AppLogger.i("Transfer declined by user.");
                        listener.onReceiverLog("⚠️ Transfer declined.");
                        break;
                    } else {
                        AppLogger.i("Transfer accepted. Ready for continuous stream.");
                        listener.onReceiverLog("✅ Transfer accepted. Wire streaming active...");
                        continue;
                    }
                }

                // Check for Live Clipboard Sync packet
                if (meta.optString("type").equals("CLIPBOARD_SYNC")) {
                    String clipText = meta.optString("text", "");
                    if (!clipText.isEmpty()) {
                        listener.onClipboardReceived(clipText);
                    }
                    continue;
                }

                // Check for Empty queue response from PC
                if (meta.has("status") && meta.getString("status").equals("EMPTY")) {
                    listener.onReceiverLog("ℹ️ No pending files queued on PC.");
                    break;
                }

                // Check for BATCH_DONE
                if (meta.optString("type").equals("BATCH_DONE")) {
                    AppLogger.i("Received BATCH_DONE from sender");
                    try {
                        out.write("BATCH_OK\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (Exception ignored) {}

                    long duration = System.currentTimeMillis() - batchStartTime;
                    listener.onReceiveBatchCompleted(totalFilesCount, totalReceivedBytes, duration, getSaveDirectory().getAbsolutePath());
                    break;
                }

                // Read File Header Info
                int fileIdx = meta.optInt("file_index", 1);
                int totalFiles = meta.optInt("total_files", 1);
                String relPath = meta.optString("relative_path", "received_file.dat");
                long filesize = meta.optLong("filesize", 0);
                long offset = meta.optLong("offset", 0);
                long batchTotal = meta.optLong("batch_total_bytes", filesize);

                totalBatchBytes = batchTotal;
                totalFilesCount = totalFiles;

                // Sanitize Path and smart categorize into media folders (Pictures, Movies, Music, Downloads)
                String cleanRel = relPath.replace("\\", "/").replaceAll("^/+", "");
                File destFile = getDestinationFile(cleanRel);
                File parentDir = destFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }

                listener.onReceiveFileStart(fileIdx, totalFiles, cleanRel, filesize);

                // Stream file data from socket to disk
                FileOutputStream fos = new FileOutputStream(destFile, offset > 0);
                long fileReceived = offset;
                long fileStartTime = System.currentTimeMillis();
                long lastMetricTime = fileStartTime;
                long intervalBytes = 0;

                long remaining = filesize - offset;
                while (remaining > 0 && isReceiving.get()) {
                    int toRead = (int) Math.min(readBuffer.length, remaining);
                    int r = in.read(readBuffer, 0, toRead);
                    if (r < 0) break;

                    fos.write(readBuffer, 0, r);
                    fileReceived += r;
                    totalReceivedBytes += r;
                    remaining -= r;
                    intervalBytes += r;

                    // Progress reporting every 250ms
                    long now = System.currentTimeMillis();
                    long elapsed = now - lastMetricTime;
                    if (elapsed >= 250) {
                        double speedMb = (intervalBytes / (1024.0 * 1024.0)) / (elapsed / 1000.0);
                        intervalBytes = 0;
                        lastMetricTime = now;

                        long remBatch = Math.max(0, totalBatchBytes - totalReceivedBytes);
                        double speedBytesPerSec = speedMb * 1024 * 1024;
                        int eta = (speedBytesPerSec > 0) ? (int) (remBatch / speedBytesPerSec) : 0;

                        listener.onReceiveProgress(fileIdx, totalFiles, cleanRel,
                                fileReceived, filesize, totalReceivedBytes, totalBatchBytes, speedMb, eta);
                    }
                }

                fos.flush();
                fos.close();

                // Scan file into Android MediaStore so Gallery / Photos app indexes it immediately!
                try {
                    MediaScannerConnection.scanFile(context, new String[]{destFile.getAbsolutePath()}, null, null);
                } catch (Exception ignored) {}

                listener.onReceiveFileCompleted(fileIdx, totalFiles, cleanRel, filesize);
                AppLogger.i("Saved incoming file: " + cleanRel + " (" + filesize + " bytes)");
            }

        } catch (Exception e) {
            AppLogger.e("Receiver stream exception", e);
            listener.onReceiverError("Error during file reception: " + e.getMessage());
        } finally {
            isReceiving.set(false);
            try { socket.close(); } catch (Exception ignored) {}
            if (activeClientSocket == socket) activeClientSocket = null;
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

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
