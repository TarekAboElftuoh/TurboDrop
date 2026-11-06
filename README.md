# ⚡ TurboDrop

[![Platform - Windows](https://img.shields.io/badge/Platform-Windows-0078D6?logo=windows&logoColor=white)](https://github.com/TarekAboElftuoh/TurboDrop)
[![Platform - Android](https://img.shields.io/badge/Platform-Android%207.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/TarekAboElftuoh/TurboDrop)
[![Transfer Speed](https://img.shields.io/badge/Speed-Up%20to%20800%2B%20MB%2Fs-FFB000?logo=speedtest&logoColor=white)](https://github.com/TarekAboElftuoh/TurboDrop)
[![License - MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**TurboDrop** is an ultra-fast, zero-configuration bidirectional file and folder streaming system connecting Windows PCs and Android devices over local Wi-Fi and Bluetooth. Engineered with high-throughput zero-copy streams, dual-bond failover, and automatic network discovery.

---

## ✨ Key Features

- ⚡ **Ultra-Fast Stream Engine:** Achieves raw network wire speeds (up to 800+ MB/s on loopback/high-speed LAN) using 4 MB socket buffers and zero-copy `memoryview` slicing.
- 📁 **Entire Directory & Batch Streaming:** Drag and drop full folder trees. TurboDrop preserves recursive directory structures without requiring intermediate zip compression.
- 🔄 **Bidirectional Transfer:**
  - **PC ➔ Mobile:** Send files, folders, or clipboard text directly to your Android device with staging queue support.
  - **Mobile ➔ PC:** Send via Android system Share Sheet (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) or in-app picker.
- 🛡️ **Dual-Bond Seamless Failover:** If Wi-Fi disconnects mid-transfer, TurboDrop automatically fails over to Bluetooth RFCOMM with byte-offset resuming, and accelerates back to Wi-Fi once restored.
- 🔍 **Zero-Config Beacon Discovery:** Automatic UDP discovery beacon and hotspot gateway detection (`192.168.137.1` / `192.168.43.1`). No manual IP typing required.
- 🔒 **Transfer Permission Handshake:** Incoming transfers show an interactive prompt with file count, size, and sender info to **Accept** or **Decline**.
- 🖱️ **Windows Shell Integration:**
  - One-click Explorer right-click context menu ("Send with TurboDrop").
  - Native Drag & Drop powered by `windnd`.
- 📱 **Unified Storage & Instant Gallery Indexing:**
  - All incoming Android files land directly in `/storage/emulated/0/Download/TurboDrop/`.
  - Automated `MediaScannerConnection` indexing so photos and videos appear in the Gallery app immediately.
  - Multi-tier SAF folder launcher (`DocumentsContract` / `Samsung My Files` / `Files by Google`).
- 📦 **Standalone Portable Executable:** Pre-built `TurboDrop.exe` with embedded icon and APK server—no Python runtime required.

---

## 🚀 Quick Start

### 1. Windows PC
1. Run **`TurboDrop.exe`** (or execute `RUN_PC_SERVER.bat`).
2. The server initializes on Wi-Fi port `5050` and Bluetooth RFCOMM Channel `5`.
3. Drag & drop any files/folders onto the window, or right-click files in Windows Explorer and choose **"Send with TurboDrop"**.

### 2. Android Device
1. Install **`TurboDrop.apk`** on your Android device (Android 7.0 to 14+).
2. Open TurboDrop. The app automatically detects your PC via UDP beacon.
3. Tap **"📁 Send Entire Folder"** or **"📄 Send Individual Files"** to transmit at maximum speed.
4. When receiving files from PC, tap **"Open Folder"** on completion to view the received files directly in your Android file manager.

---

## 🏗️ Architecture & Protocol

TurboDrop uses a continuous framing protocol over non-blocking TCP streams:

```
+---------------+---------------------+--------------------+--------------------+
| MAGIC (4B)    | Header Length (4B)  | Header JSON (Var)  | File Payload Bytes |
| 0x54 0x44 0x52| Big-Endian uint32   | UTF-8 Metadata     | Raw binary stream  |
+---------------+---------------------+--------------------+--------------------+
```

### Handshake & Streaming Lifecycle:
1. **TRANSFER_REQ:** Sender transmits batch metadata (file count, total bytes, sender name).
2. **HANDSHAKE_RESP:** Receiver inspects details and responds with `TRANSFER_ACCEPT` or `TRANSFER_DECLINE`.
3. **FILE_STREAM:** Stream frames each file with relative path, offset, and size, writing directly to disk with zero intermediate buffering.
4. **BATCH_DONE:** Receiver acknowledges with `BATCH_OK` and triggers the OS media scanner.

---

## 🛠️ Building from Source

### PC Application
```powershell
# Install build dependencies
python -m pip install -r requirements.txt

# Run directly
python pc_app/turbo_pc.py

# Build standalone Windows .exe
python build_exe.py
```

### Android APK
The Android client uses native Android SDK tools without Gradle overhead:
```powershell
# Builds and signs TurboDrop.apk using Android SDK 34
powershell.exe -ExecutionPolicy Bypass -File build_apk.ps1
```

### Automated Test Suite
Run the comprehensive self-test suite covering batch transfers, abrupt disconnect failovers, and streaming integrity:
```powershell
python tests/test_suite.py
```

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
