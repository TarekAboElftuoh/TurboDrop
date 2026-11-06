package com.turbodrop.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class TransferService extends Service {
    private static final String CHANNEL_ID = "turbodrop_transfer_channel";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_START = "com.turbodrop.app.START_TRANSFER";
    public static final String ACTION_STOP = "com.turbodrop.app.STOP_TRANSFER";

    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_MSG = "extra_msg";
    public static final String EXTRA_PROGRESS = "extra_progress";

    private PowerManager.WakeLock wakeLock = null;
    private WifiManager.WifiLock wifiLock = null;
    private NotificationManager notifManager = null;

    @Override
    public void onCreate() {
        super.onCreate();
        notifManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        acquireLocks();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "TurboDrop High-Speed Transfer",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps Wi-Fi and CPU active at maximum throughput even with screen off");
            channel.setShowBadge(false);
            if (notifManager != null) {
                notifManager.createNotificationChannel(channel);
            }
        }
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TurboDrop::TransferServiceWakeLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(120 * 60 * 1000L); // 2 hours max
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                // Always use WIFI_MODE_FULL_HIGH_PERF (mode 3) to enable maximum 802.11 A-MPDU/A-MSDU frame aggregation!
                // Mode 4 (LOW_LATENCY) suppresses packet aggregation which cuts throughput in half.
                int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "TurboDrop::TransferServiceWifiLock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }

            String title = intent.getStringExtra(EXTRA_TITLE);
            String msg = intent.getStringExtra(EXTRA_MSG);
            int progress = intent.getIntExtra(EXTRA_PROGRESS, -1);

            if (title == null) title = "🚀 TurboDrop: High-Speed Transfer Active";
            if (msg == null) msg = "Transfer running in background with screen off";

            Notification notification = buildNotification(title, msg, progress);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String title, String msg, int progress) {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle(title)
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pendingIntent)
                .setOngoing(true);

        if (progress >= 0 && progress <= 100) {
            builder.setProgress(100, progress, false);
        }

        return builder.build();
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
