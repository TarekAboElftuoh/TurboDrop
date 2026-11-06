package com.turbodrop.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class AutoDiscoveryClient {

    public interface DiscoveryListener {
        void onPcDiscovered(String name, String wifiIp, int wifiPort, String btMac);
        void onDiscoveryLog(String msg);
    }

    private static final int DISCOVERY_PORT = 5051;
    private final Context context;
    private final DiscoveryListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private DatagramSocket socket;
    private WifiManager.MulticastLock multicastLock;

    public AutoDiscoveryClient(Context context, DiscoveryListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (isRunning.getAndSet(true)) return;

        // Acquire multicast/broadcast lock on Android Wi-Fi
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("TurboDropDiscoveryLock");
                multicastLock.setReferenceCounted(true);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {}

        // 1. Inspect bonded Bluetooth devices safely
        checkPairedBluetoothDevices();

        // 2. Start UDP background listener
        Thread thread = new Thread(this::listenLoop, "DiscoveryThread");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        isRunning.set(false);
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            try {
                multicastLock.release();
            } catch (Exception ignored) {}
        }
    }

    private void checkPairedBluetoothDevices() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
            }
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isEnabled()) {
                Set<BluetoothDevice> paired = adapter.getBondedDevices();
                if (paired != null && !paired.isEmpty()) {
                    for (BluetoothDevice d : paired) {
                        String name = d.getName() != null ? d.getName() : "Bluetooth PC";
                        mainHandler.post(() -> listener.onDiscoveryLog("Found paired device: " + name + " (" + d.getAddress() + ")"));
                    }
                }
            }
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {}
    }

    private void listenLoop() {
        byte[] buffer = new byte[2048];
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(DISCOVERY_PORT));

            while (isRunning.get()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String jsonStr = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                try {
                    JSONObject obj = new JSONObject(jsonStr);
                    if ("TURBODROP_BEACON".equals(obj.optString("magic"))) {
                        String pcName = obj.optString("name", "PC");
                        String wifiIp = obj.optString("wifi_ip", packet.getAddress().getHostAddress());
                        int wifiPort = obj.optInt("wifi_port", 5050);
                        String btMac = obj.optString("bt_mac", "");

                        mainHandler.post(() -> listener.onPcDiscovered(pcName, wifiIp, wifiPort, btMac));
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            if (isRunning.get()) {
                mainHandler.post(() -> listener.onDiscoveryLog("Beacon listener retry..."));
            }
        }
    }
}
