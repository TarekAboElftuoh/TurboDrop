package com.turbodrop.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity implements FastStreamSender.BatchListener, AutoDiscoveryClient.DiscoveryListener, FastStreamReceiver.ReceiverListener {

    private static final int REQ_PICK_FILES = 1001;
    private static final int REQ_PICK_FOLDER = 1002;
    private static final int REQ_PERMISSIONS = 1003;
    private static final String PREF_NAME = "turbodrop_prefs";
    private static final String KEY_WIFI_IP = "saved_wifi_ip";
    private static final String KEY_BT_MAC = "saved_bt_mac";
    private static final String KEY_MODE = "saved_mode";
    private static final String KEY_PIN = "saved_pin";

    // Mode Selector
    private RadioGroup rgMode;
    private RadioButton rbHybrid;
    private RadioButton rbWifiOnly;
    private RadioButton rbBtOnly;

    // Connection Boxes
    private LinearLayout boxWifiSettings;
    private View dividerSettings;
    private LinearLayout boxBtSettings;

    private EditText etWifiIp;
    private Button btnTestWifi;
    private TextView tvWifiStatus;
    private EditText etPin;
    private Button btnDetectHotspot;

    private Spinner spinnerBtDevices;
    private TextView tvBtStatus;
    private Button btnRefreshBt;

    // Actions
    private Button btnSendFolder;
    private Button btnSendFiles;
    private Button btnReceivePc;
    private Button btnSendClipboard;

    // Progress UI
    private LinearLayout layoutProgress;
    private TextView tvActiveFile;
    private ProgressBar pbFileProgress;
    private TextView tvBatchStat;
    private TextView tvSpeedTotal;
    private ProgressBar pbBatchProgress;
    private TextView tvWifiStat;
    private TextView tvBtStat;
    private TextView tvFailoverBanner;
    private TextView tvLog;

    private SharedPreferences prefs;
    private AutoDiscoveryClient discoveryClient;
    private FastStreamSender activeSender;
    private FastStreamReceiver receiver;

    private final List<BluetoothDevice> pairedDeviceList = new ArrayList<>();
    private String selectedBtMac = "";

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private long lastNotifUpdateTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        AppLogger.init(this);
        AppLogger.i("MainActivity onCreate started");

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        initViews();
        loadSavedSettings();
        requestNecessaryPermissions();
        populateBluetoothDevices();

        receiver = new FastStreamReceiver(this, this);
        receiver.startListening(5052);

        try {
            discoveryClient = new AutoDiscoveryClient(this, this);
            discoveryClient.start();
        } catch (Exception ignored) {}

        // Handle incoming Android system share intents (Photos, Gallery, Files -> TurboDrop)
        handleIncomingShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingShareIntent(intent);
    }

    private void handleIncomingShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                List<FileItem> items = new ArrayList<>();
                items.add(extractSingleFile(uri));
                startBatchTransfer(items);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null && !uris.isEmpty()) {
                List<FileItem> items = new ArrayList<>();
                for (Uri u : uris) {
                    if (u != null) items.add(extractSingleFile(u));
                }
                if (!items.isEmpty()) {
                    startBatchTransfer(items);
                }
            }
        }
    }

    private void initViews() {
        rgMode = findViewById(R.id.rg_mode);
        rbHybrid = findViewById(R.id.rb_hybrid);
        rbWifiOnly = findViewById(R.id.rb_wifi_only);
        rbBtOnly = findViewById(R.id.rb_bt_only);

        boxWifiSettings = findViewById(R.id.box_wifi_settings);
        dividerSettings = findViewById(R.id.divider_settings);
        boxBtSettings = findViewById(R.id.box_bt_settings);

        etWifiIp = findViewById(R.id.et_wifi_ip);
        btnTestWifi = findViewById(R.id.btn_test_wifi);
        tvWifiStatus = findViewById(R.id.tv_wifi_status);
        etPin = findViewById(R.id.et_pin);
        btnDetectHotspot = findViewById(R.id.btn_detect_hotspot);

        spinnerBtDevices = findViewById(R.id.spinner_bt_devices);
        tvBtStatus = findViewById(R.id.tv_bt_status);
        btnRefreshBt = findViewById(R.id.btn_refresh_bt);

        btnSendFolder = findViewById(R.id.btn_send_folder);
        btnSendFiles = findViewById(R.id.btn_send_files);
        btnReceivePc = findViewById(R.id.btn_receive_pc);
        btnSendClipboard = findViewById(R.id.btn_send_clipboard);

        layoutProgress = findViewById(R.id.layout_progress);
        tvActiveFile = findViewById(R.id.tv_active_file);
        pbFileProgress = findViewById(R.id.pb_file_progress);
        tvBatchStat = findViewById(R.id.tv_batch_stat);
        tvSpeedTotal = findViewById(R.id.tv_speed_total);
        pbBatchProgress = findViewById(R.id.pb_batch_progress);
        tvWifiStat = findViewById(R.id.tv_wifi_stat);
        tvBtStat = findViewById(R.id.tv_bt_stat);
        tvFailoverBanner = findViewById(R.id.tv_failover_banner);
        tvLog = findViewById(R.id.tv_log);

        rgMode.setOnCheckedChangeListener((group, checkedId) -> updateModeVisibility());
        btnTestWifi.setOnClickListener(v -> testWifiConnection());
        btnRefreshBt.setOnClickListener(v -> populateBluetoothDevices());
        if (btnDetectHotspot != null) btnDetectHotspot.setOnClickListener(v -> detectHotspotGateway());
        if (btnSendClipboard != null) btnSendClipboard.setOnClickListener(v -> sendClipboardToPc());

        btnSendFolder.setOnClickListener(v -> {
            saveCurrentSettings();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, REQ_PICK_FOLDER);
        });

        btnSendFiles.setOnClickListener(v -> {
            saveCurrentSettings();
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "Select Files to Send"), REQ_PICK_FILES);
        });

        if (btnReceivePc != null) {
            btnReceivePc.setOnClickListener(v -> {
                String ip = etWifiIp.getText().toString().trim();
                if (ip.isEmpty()) {
                    Toast.makeText(this, "Please enter the PC IP address first!", Toast.LENGTH_SHORT).show();
                    return;
                }
                saveCurrentSettings();
                acquireHighPerfLocks();
                layoutProgress.setVisibility(View.VISIBLE);
                tvActiveFile.setText("Connecting to PC to pull staged files...");
                pbFileProgress.setProgress(0);
                pbBatchProgress.setProgress(0);
                tvSpeedTotal.setText("Connecting...");
                receiver.pullFromPc(ip, 5050);
            });
        }

        Button btnCopyLog = findViewById(R.id.btn_copy_log);
        if (btnCopyLog != null) {
            btnCopyLog.setOnClickListener(v -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    String fullLog = "Log File: " + AppLogger.getLogFilePath() + "\n\n" + tvLog.getText().toString();
                    cm.setPrimaryClip(ClipData.newPlainText("TurboDropLog", fullLog));
                    Toast.makeText(this, "Log copied to clipboard!", Toast.LENGTH_SHORT).show();
                }
            });
        }

        TextView tvSaveLoc = findViewById(R.id.tv_save_location);
        if (tvSaveLoc != null) {
            tvSaveLoc.setOnClickListener(v -> openReceivedFolder(null));
        }
    }

    private void updateModeVisibility() {
        int mode = getSelectedMode();
        if (mode == FastStreamSender.MODE_WIFI_ONLY) {
            boxWifiSettings.setVisibility(View.VISIBLE);
            dividerSettings.setVisibility(View.GONE);
            boxBtSettings.setVisibility(View.GONE);
        } else if (mode == FastStreamSender.MODE_BT_ONLY) {
            boxWifiSettings.setVisibility(View.GONE);
            dividerSettings.setVisibility(View.GONE);
            boxBtSettings.setVisibility(View.VISIBLE);
        } else {
            boxWifiSettings.setVisibility(View.VISIBLE);
            dividerSettings.setVisibility(View.VISIBLE);
            boxBtSettings.setVisibility(View.VISIBLE);
        }
    }

    private int getSelectedMode() {
        if (rbWifiOnly.isChecked()) return FastStreamSender.MODE_WIFI_ONLY;
        if (rbBtOnly.isChecked()) return FastStreamSender.MODE_BT_ONLY;
        return FastStreamSender.MODE_HYBRID;
    }

    private void loadSavedSettings() {
        String savedIp = prefs.getString(KEY_WIFI_IP, "192.168.1.100");
        etWifiIp.setText(savedIp);
        if (etPin != null) {
            etPin.setText(prefs.getString(KEY_PIN, ""));
        }

        int savedMode = prefs.getInt(KEY_MODE, FastStreamSender.MODE_WIFI_ONLY);
        if (savedMode == FastStreamSender.MODE_WIFI_ONLY) rbWifiOnly.setChecked(true);
        else if (savedMode == FastStreamSender.MODE_BT_ONLY) rbBtOnly.setChecked(true);
        else rbHybrid.setChecked(true);

        updateModeVisibility();
    }

    private void saveCurrentSettings() {
        prefs.edit()
                .putString(KEY_WIFI_IP, etWifiIp.getText().toString().trim())
                .putString(KEY_BT_MAC, selectedBtMac)
                .putString(KEY_PIN, etPin != null ? etPin.getText().toString().trim() : "")
                .putInt(KEY_MODE, getSelectedMode())
                .apply();
    }

    private void detectHotspotGateway() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                android.net.DhcpInfo dhcp = wm.getDhcpInfo();
                if (dhcp != null && dhcp.gateway != 0) {
                    int g = dhcp.gateway;
                    String gatewayIp = (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "." + ((g >> 16) & 0xFF) + "." + ((g >> 24) & 0xFF);
                    if (!gatewayIp.equals("0.0.0.0")) {
                        etWifiIp.setText(gatewayIp);
                        saveCurrentSettings();
                        Toast.makeText(this, "⚡ Hotspot Gateway Detected: " + gatewayIp, Toast.LENGTH_SHORT).show();
                        appendLog("⚡ Detected Hotspot Gateway IP: " + gatewayIp);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {}
        Toast.makeText(this, "Could not detect hotspot gateway automatically.", Toast.LENGTH_SHORT).show();
    }

    private void sendClipboardToPc() {
        String ip = etWifiIp.getText().toString().trim();
        if (ip.isEmpty()) {
            Toast.makeText(this, "Please enter PC IP address first!", Toast.LENGTH_SHORT).show();
            return;
        }
        saveCurrentSettings();
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
            if (text != null && text.length() > 0) {
                receiver.sendClipboardToPc(text.toString(), ip, 5050);
                Toast.makeText(this, "📋 Sending clipboard text to PC...", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Toast.makeText(this, "Clipboard is empty!", Toast.LENGTH_SHORT).show();
    }

    private void populateBluetoothDevices() {
        pairedDeviceList.clear();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    tvBtStatus.setText("Please grant Bluetooth permissions...");
                    return;
                }
            }

            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                tvBtStatus.setText("❌ Bluetooth is not supported on this device.");
                return;
            }

            Set<BluetoothDevice> paired = adapter.getBondedDevices();
            List<String> names = new ArrayList<>();

            int selectedIdx = 0;
            String savedMac = prefs.getString(KEY_BT_MAC, "");

            if (paired != null && !paired.isEmpty()) {
                int i = 0;
                for (BluetoothDevice d : paired) {
                    pairedDeviceList.add(d);
                    String name = "Paired Device";
                    try {
                        if (d.getName() != null) name = d.getName();
                    } catch (SecurityException ignored) {}

                    names.add(name + " (" + d.getAddress() + ")");
                    if (!savedMac.isEmpty() && d.getAddress().equalsIgnoreCase(savedMac)) {
                        selectedIdx = i;
                    }
                    i++;
                }
            }

            if (names.isEmpty()) {
                names.add("No paired devices (pair PC in phone settings)");
                tvBtStatus.setText("⚠️ Please pair your PC in Bluetooth settings.");
            } else {
                tvBtStatus.setText("✅ Paired devices loaded.");
            }

            ArrayAdapter<String> adapterSpinner = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    TextView tv = (TextView) super.getView(position, convertView, parent);
                    tv.setTextColor(Color.parseColor("#F0F6FC"));
                    tv.setTextSize(13);
                    return tv;
                }

                @Override
                public View getDropDownView(int position, View convertView, ViewGroup parent) {
                    TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
                    tv.setTextColor(Color.parseColor("#F0F6FC"));
                    tv.setBackgroundColor(Color.parseColor("#161B22"));
                    tv.setPadding(16, 20, 16, 20);
                    return tv;
                }
            };

            adapterSpinner.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerBtDevices.setAdapter(adapterSpinner);

            if (!pairedDeviceList.isEmpty() && selectedIdx < pairedDeviceList.size()) {
                spinnerBtDevices.setSelection(selectedIdx);
                selectedBtMac = pairedDeviceList.get(selectedIdx).getAddress();
            }

            spinnerBtDevices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position < pairedDeviceList.size()) {
                        selectedBtMac = pairedDeviceList.get(position).getAddress();
                        String devName = "PC";
                        try {
                            if (pairedDeviceList.get(position).getName() != null) {
                                devName = pairedDeviceList.get(position).getName();
                            }
                        } catch (SecurityException ignored) {}
                        tvBtStatus.setText("Selected: " + devName);
                        saveCurrentSettings();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });

        } catch (SecurityException se) {
            tvBtStatus.setText("Please grant Bluetooth permissions.");
        } catch (Exception e) {
            tvBtStatus.setText("Bluetooth error: " + e.getMessage());
        }
    }

    private void testWifiConnection() {
        String ip = etWifiIp.getText().toString().trim();
        if (ip.isEmpty()) {
            Toast.makeText(this, "Please enter the PC IP address first!", Toast.LENGTH_SHORT).show();
            return;
        }

        tvWifiStatus.setText("Testing connection to " + ip + "...");
        new Thread(() -> {
            boolean success = false;
            try {
                URL url = new URL("http://" + ip + ":8080/");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                int code = conn.getResponseCode();
                success = (code == 200);
                conn.disconnect();
            } catch (Exception ignored) {}

            boolean finalSuccess = success;
            runOnUiThread(() -> {
                if (finalSuccess) {
                    tvWifiStatus.setText("🟢 Connected to PC successfully! Ready for wire speed.");
                    Toast.makeText(MainActivity.this, "🟢 Connection to PC verified!", Toast.LENGTH_SHORT).show();
                    saveCurrentSettings();
                } else {
                    tvWifiStatus.setText("❌ Could not reach " + ip + " - ensure PC app is running on the same network.");
                }
            });
        }).start();
    }

    @Override
    public void onPcDiscovered(String name, String wifiIp, int wifiPort, String btMac) {
        runOnUiThread(() -> {
            if (etWifiIp.getText().toString().trim().isEmpty() || etWifiIp.getText().toString().equals("192.168.1.100") || etWifiIp.getText().toString().equals("192.168.100.2")) {
                etWifiIp.setText(wifiIp);
            }
            tvWifiStatus.setText("🟢 Auto-discovered PC: " + name);
            appendLog("🎯 Auto-Discovered: " + name + " (" + wifiIp + ")");

            if (btMac != null && !btMac.isEmpty()) {
                for (int i = 0; i < pairedDeviceList.size(); i++) {
                    if (pairedDeviceList.get(i).getAddress().equalsIgnoreCase(btMac)) {
                        spinnerBtDevices.setSelection(i);
                        selectedBtMac = btMac;
                        break;
                    }
                }
            }
        });
    }

    @Override
    public void onDiscoveryLog(String msg) {
        runOnUiThread(() -> appendLog(msg));
    }

    private void requestNecessaryPermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[0]), REQ_PERMISSIONS);
        }

        requestBatteryOptimizationExemption();
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            populateBluetoothDevices();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_PICK_FOLDER) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                appendLog("📂 Scanning selected folder structure...");
                FolderScanner.scanTreeAsync(getContentResolver(), treeUri, new FolderScanner.ScanCallback() {
                    @Override
                    public void onProgress(int filesFound, long totalBytes) {
                        runOnUiThread(() -> appendLog("Found " + filesFound + " files..."));
                    }

                    @Override
                    public void onComplete(List<FileItem> items, long totalBytes) {
                        runOnUiThread(() -> {
                            if (items.isEmpty()) {
                                Toast.makeText(MainActivity.this, "Selected folder is empty!", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            startBatchTransfer(items);
                        });
                    }
                });
            }
        } else if (requestCode == REQ_PICK_FILES) {
            List<FileItem> items = new ArrayList<>();
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    Uri uri = clipData.getItemAt(i).getUri();
                    if (uri != null) items.add(extractSingleFile(uri));
                }
            } else if (data.getData() != null) {
                items.add(extractSingleFile(data.getData()));
            }

            if (!items.isEmpty()) {
                startBatchTransfer(items);
            }
        }
    }

    private FileItem extractSingleFile(Uri uri) {
        String name = "file.dat";
        long size = 0;
        Cursor c = getContentResolver().query(uri, null, null, null, null);
        if (c != null) {
            int nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int sizeIdx = c.getColumnIndex(OpenableColumns.SIZE);
            if (c.moveToFirst()) {
                if (nameIdx >= 0) name = c.getString(nameIdx);
                if (sizeIdx >= 0) size = c.getLong(sizeIdx);
            }
            c.close();
        }
        return new FileItem(uri, name, name, size);
    }

    private void startBatchTransfer(List<FileItem> items) {
        saveCurrentSettings();

        String ip = etWifiIp.getText().toString().trim();
        int mode = getSelectedMode();

        if ((mode == FastStreamSender.MODE_HYBRID || mode == FastStreamSender.MODE_WIFI_ONLY) && ip.isEmpty()) {
            Toast.makeText(this, "Please enter the PC Wi-Fi IP address!", Toast.LENGTH_LONG).show();
            return;
        }

        if ((mode == FastStreamSender.MODE_HYBRID || mode == FastStreamSender.MODE_BT_ONLY) && selectedBtMac.isEmpty()) {
            Toast.makeText(this, "Please select the paired PC Bluetooth device!", Toast.LENGTH_LONG).show();
            return;
        }

        layoutProgress.setVisibility(View.VISIBLE);
        pbFileProgress.setProgress(0);
        pbBatchProgress.setProgress(0);
        tvSpeedTotal.setText("Connecting...");

        acquireHighPerfLocks();

        activeSender = new FastStreamSender(
                getContentResolver(),
                items,
                ip,
                5050,
                selectedBtMac,
                mode,
                this
        );
        if (etPin != null && etPin.getText().length() > 0) {
            activeSender.setSecurityPin(etPin.getText().toString());
        }
        activeSender.start();
    }

    private void acquireHighPerfLocks() {
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TurboDrop::TransferWakeLock");
                wakeLock.acquire(120 * 60 * 1000L); // 2 hours max
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                // Use WIFI_MODE_FULL_HIGH_PERF (mode 3) to keep 802.11 A-MPDU aggregation active for 50-80+ MB/s
                int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "TurboDrop::HighPerfWifiLock");
                wifiLock.acquire();
            }

            // Start Foreground Service with Ongoing Notification
            Intent serviceIntent = new Intent(this, TransferService.class);
            serviceIntent.setAction(TransferService.ACTION_START);
            serviceIntent.putExtra(TransferService.EXTRA_TITLE, "🚀 TurboDrop: High-Speed Transfer Active");
            serviceIntent.putExtra(TransferService.EXTRA_MSG, "Transfer running in background with screen off");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception ignored) {}
    }

    private void releaseHighPerfLocks() {
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }

            Intent stopIntent = new Intent(this, TransferService.class);
            stopIntent.setAction(TransferService.ACTION_STOP);
            startService(stopIntent);
        } catch (Exception ignored) {}
    }

    @Override
    public void onProgress(int fileIndex, int totalFiles, String filename,
                           long fileSent, long fileTotal,
                           long batchSent, long batchTotal,
                           double wifiSpeedMb, double btSpeedKb, int etaSec) {
        runOnUiThread(() -> {
            if (filename != null && !filename.isEmpty()) {
                tvActiveFile.setText("[" + fileIndex + "/" + totalFiles + "] " + filename);
            }

            int bPct = batchTotal > 0 ? (int) ((batchSent * 100) / batchTotal) : 0;
            pbBatchProgress.setProgress(bPct);

            tvBatchStat.setText("Batch: " + formatBytes(batchSent) + " / " + formatBytes(batchTotal) + " (" + bPct + "%)");

            double totalSpeedMb = wifiSpeedMb + (btSpeedKb / 1024.0);
            tvSpeedTotal.setText(String.format("%.1f MB/s", totalSpeedMb));

            tvWifiStat.setText(String.format("📶 Wi-Fi: %.1f MB/s", wifiSpeedMb));
            tvBtStat.setText(String.format("ᛒ BT: %.1f KB/s", btSpeedKb));

            // Throttle notification updates
            long now = System.currentTimeMillis();
            if (now - lastNotifUpdateTime > 1000) {
                lastNotifUpdateTime = now;
                try {
                    Intent updateIntent = new Intent(this, TransferService.class);
                    updateIntent.setAction(TransferService.ACTION_START);
                    updateIntent.putExtra(TransferService.EXTRA_TITLE, String.format("🚀 TurboDrop: %.1f MB/s", totalSpeedMb));
                    updateIntent.putExtra(TransferService.EXTRA_MSG, String.format("[%d/%d] %s (%d%%)", fileIndex, totalFiles, filename != null ? filename : "", bPct));
                    updateIntent.putExtra(TransferService.EXTRA_PROGRESS, bPct);
                    startService(updateIntent);
                } catch (Exception ignored) {}
            }
        });
    }

    @Override
    public void onFailover(String alert) {
        runOnUiThread(() -> {
            tvFailoverBanner.setText(alert);
            appendLog(alert);
        });
    }

    @Override
    public void onLog(String message) {
        runOnUiThread(() -> appendLog(message));
    }

    @Override
    public void onFileCompleted(int fileIndex, int totalFiles, String fileName) {
        runOnUiThread(() -> appendLog("✅ [" + fileIndex + "/" + totalFiles + "] Sent: " + fileName));
    }

    @Override
    public void onBatchCompleted(int totalFiles, long totalBytes, long durationMs) {
        releaseHighPerfLocks();
        runOnUiThread(() -> {
            pbBatchProgress.setProgress(100);
            pbFileProgress.setProgress(100);
            tvSpeedTotal.setText("Done! 🎉");
            tvFailoverBanner.setText("✅ Transfer completed for all " + totalFiles + " files!");

            double sec = durationMs / 1000.0;
            double avgMb = (totalBytes / (1024.0 * 1024.0)) / Math.max(0.1, sec);
            appendLog(String.format("🎉 Batch completed: %d files (%s) in %.1fs (Avg: %.1f MB/s)",
                    totalFiles, formatBytes(totalBytes), sec, avgMb));

            new AlertDialog.Builder(this)
                    .setTitle("Transfer Complete! 🎉")
                    .setMessage("Successfully transferred " + totalFiles + " files (" + formatBytes(totalBytes) + ") in " + String.format("%.1f seconds", sec))
                    .setPositiveButton("OK", null)
                    .show();
        });
    }

    @Override
    public void onError(String error) {
        releaseHighPerfLocks();
        runOnUiThread(() -> {
            appendLog("❌ Error: " + error);
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        });
    }

    private void appendLog(String line) {
        tvLog.append(line + "\n");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    @Override
    public void onReceiverLog(String msg) {
        runOnUiThread(() -> appendLog(msg));
    }

    @Override
    public void onClipboardReceived(String text) {
        runOnUiThread(() -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("TurboDropSync", text));
                String preview = text.length() > 35 ? text.substring(0, 35) + "..." : text;
                Toast.makeText(this, "📋 Copied from PC: " + preview, Toast.LENGTH_SHORT).show();
                appendLog("📋 Clipboard text synced from PC: " + text);
            }
        });
    }

    @Override
    public void onTransferRequested(String senderName, int totalFiles, long totalBytes, FastStreamReceiver.TransferDecisionCallback callback) {
        runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                    .setTitle("📥 Incoming Transfer Request")
                    .setMessage("Sender: " + senderName + "\nFiles: " + totalFiles + "\nTotal Size: " + formatBytes(totalBytes) + "\n\nDo you want to accept this transfer?")
                    .setCancelable(false)
                    .setPositiveButton("Accept", (dialog, which) -> {
                        callback.onDecision(true);
                        acquireHighPerfLocks();
                        layoutProgress.setVisibility(View.VISIBLE);
                        tvActiveFile.setText("Transfer accepted. Preparing stream...");
                        tvFailoverBanner.setText("⚡ Receiving files from " + senderName);
                    })
                    .setNegativeButton("Decline", (dialog, which) -> {
                        callback.onDecision(false);
                        appendLog("⚠️ Declined transfer from " + senderName);
                    })
                    .show();
        });
    }

    @Override
    public void onReceiveFileStart(int fileIndex, int totalFiles, String filename, long filesize) {
        runOnUiThread(() -> {
            layoutProgress.setVisibility(View.VISIBLE);
            tvActiveFile.setText("📥 [" + fileIndex + "/" + totalFiles + "] " + filename);
            tvFailoverBanner.setText("📥 Receiving high-speed wire stream...");
        });
    }

    @Override
    public void onReceiveProgress(int fileIndex, int totalFiles, String filename,
                                  long fileReceived, long fileTotal,
                                  long batchReceived, long batchTotal,
                                  double speedMb, int etaSec) {
        runOnUiThread(() -> {
            int bPct = batchTotal > 0 ? (int) ((batchReceived * 100) / batchTotal) : 0;
            int fPct = fileTotal > 0 ? (int) ((fileReceived * 100) / fileTotal) : 0;
            pbFileProgress.setProgress(fPct);
            pbBatchProgress.setProgress(bPct);
            tvBatchStat.setText("Receiving: " + formatBytes(batchReceived) + " / " + formatBytes(batchTotal) + " (" + bPct + "%)");
            tvSpeedTotal.setText(String.format("%.1f MB/s", speedMb));
            tvWifiStat.setText(String.format("📶 Wi-Fi: %.1f MB/s", speedMb));

            long now = System.currentTimeMillis();
            if (now - lastNotifUpdateTime > 1000) {
                lastNotifUpdateTime = now;
                try {
                    Intent updateIntent = new Intent(this, TransferService.class);
                    updateIntent.setAction(TransferService.ACTION_START);
                    updateIntent.putExtra(TransferService.EXTRA_TITLE, String.format("📥 TurboDrop: %.1f MB/s", speedMb));
                    updateIntent.putExtra(TransferService.EXTRA_MSG, String.format("[%d/%d] %s (%d%%)", fileIndex, totalFiles, filename != null ? filename : "", bPct));
                    updateIntent.putExtra(TransferService.EXTRA_PROGRESS, bPct);
                    startService(updateIntent);
                } catch (Exception ignored) {}
            }
        });
    }

    @Override
    public void onReceiveFileCompleted(int fileIndex, int totalFiles, String filename, long filesize) {
        runOnUiThread(() -> appendLog("✅ Saved: " + filename + " (" + formatBytes(filesize) + ")"));
    }

    @Override
    public void onReceiveBatchCompleted(int totalFiles, long totalBytes, long durationMs, String saveDir) {
        releaseHighPerfLocks();
        runOnUiThread(() -> {
            pbBatchProgress.setProgress(100);
            pbFileProgress.setProgress(100);
            tvSpeedTotal.setText("Done! 🎉");
            tvFailoverBanner.setText("✅ Completed receiving " + totalFiles + " files!");

            double sec = durationMs / 1000.0;
            double avgMb = (totalBytes / (1024.0 * 1024.0)) / Math.max(0.1, sec);
            appendLog(String.format("🎉 Batch received: %d files (%s) in %.1fs (Avg: %.1f MB/s)",
                    totalFiles, formatBytes(totalBytes), sec, avgMb));
            appendLog("📁 Saved to: " + saveDir);

            new AlertDialog.Builder(this)
                    .setTitle("Receive Complete! 🎉")
                    .setMessage("Successfully received " + totalFiles + " files (" + formatBytes(totalBytes) + ") from PC!\n\nSaved to: Download/TurboDrop")
                    .setPositiveButton("Open Folder", (d, w) -> openReceivedFolder(saveDir))
                    .setNegativeButton("Close", null)
                    .show();
        });
    }

    private void openReceivedFolder(String folderPath) {
        try {
            File folder = (folderPath != null && !folderPath.isEmpty())
                    ? new File(folderPath)
                    : new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TurboDrop");
            if (!folder.exists()) {
                folder.mkdirs();
            }

            // Determine document ID for Android ExternalStorageProvider (primary:Download/TurboDrop)
            String externalRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
            String docId = "primary:Download/TurboDrop";
            if (folder.getAbsolutePath().startsWith(externalRoot)) {
                String rel = folder.getAbsolutePath().substring(externalRoot.length());
                while (rel.startsWith("/") || rel.startsWith("\\")) {
                    rel = rel.substring(1);
                }
                if (!rel.isEmpty()) {
                    docId = "primary:" + rel.replace('\\', '/');
                }
            }

            // Strategy 1: Modern Android DocumentsUI / System Files app (Android 7.0 - 14.0+)
            try {
                Uri docUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Exception e1) {
                AppLogger.w("Folder open strategy 1 (DocumentsUI Document URI) failed: " + e1.getMessage());
            }

            // Strategy 2: DocumentsUI Tree URI
            try {
                Uri treeUri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", docId);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Exception e2) {
                AppLogger.w("Folder open strategy 2 (DocumentsUI Tree URI) failed: " + e2.getMessage());
            }

            // Strategy 3: Samsung My Files direct directory launcher
            try {
                Intent samsungIntent = new Intent("com.sec.android.app.myfiles.VIEW_DIRECTORY");
                samsungIntent.putExtra("DIRECTORY", folder.getAbsolutePath());
                samsungIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(samsungIntent);
                return;
            } catch (Exception ignored) {}

            // Strategy 4: Third-party File Managers with resource/folder MIME
            try {
                Uri docUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(docUri, "resource/folder");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Exception ignored) {}

            // Strategy 5: System Downloads manager
            try {
                Intent dmIntent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
                dmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(dmIntent);
                return;
            } catch (Exception ignored) {}

            // Strategy 6: Fallback notification to user
            Toast.makeText(this, "Files saved in: " + folder.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppLogger.e("Failed to open folder", e);
            Toast.makeText(this, "Files saved in: Download/TurboDrop", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onReceiverError(String error) {
        releaseHighPerfLocks();
        runOnUiThread(() -> {
            appendLog("❌ Receiver: " + error);
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onDestroy() {
        releaseHighPerfLocks();
        super.onDestroy();
        if (discoveryClient != null) {
            discoveryClient.stop();
        }
        if (activeSender != null) {
            activeSender.stop();
        }
        if (receiver != null) {
            receiver.stopListening();
        }
    }
}
