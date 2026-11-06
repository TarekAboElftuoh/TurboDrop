"""
TurboDrop Zero-Latency Pipelined Stream Engine
Maximum wire-speed continuous streaming with sub-millisecond inter-file delay,
nested folder preservation, hot failover, and persistent logging.
"""

import os
import sys
import time
import json
import socket
import struct
import threading
from typing import Optional, Callable

from logger import log_info, log_warn, log_error, log_debug

MAGIC = b"TURB"
HEADER_MAX_LEN = 65536
BUFFER_SIZE = 512 * 1024  # 512 KB read buffer for max wire throughput


class FastReceiverEngine:
    def __init__(self, save_dir: str, event_callback: Optional[Callable] = None):
        self.save_dir = os.path.abspath(save_dir)
        os.makedirs(self.save_dir, exist_ok=True)
        self.event_callback = event_callback
        self.running = False

        self.wifi_sock = None
        self.bt_sock = None
        self._dir_cache = set()
        self.last_client_ip = ""
        self.pending_batch_provider = None
        self.request_callback = None
        self.auto_accept = False
        self.security_pin = ""

        # Speed metrics
        self.bytes_wifi_interval = 0
        self.bytes_bt_interval = 0
        self.wifi_speed = 0.0
        self.bt_speed = 0.0
        self.last_metric_time = time.time()
        self.lock = threading.RLock()

        # Current Transfer State
        self.active_file_info = None
        self.active_file_bytes_written = 0
        self.active_file_total_bytes = 0
        self.batch_files_completed = 0
        self.batch_total_files = 1
        self.batch_bytes_completed = 0
        self.batch_total_bytes = 0

        self.wifi_active = False
        self.bt_active = False

    def start(self, wifi_port: int = 5050, bt_channel: int = 5):
        self.running = True
        log_info(f"Starting Zero-Latency Engine (Save Dir: {self.save_dir})")
        self._start_wifi_server(wifi_port)
        self._start_bt_server(bt_channel)
        threading.Thread(target=self._metric_loop, daemon=True, name="MetricLoop").start()

    def stop(self):
        log_info("Stopping Zero-Latency Engine...")
        self.running = False
        if self.wifi_sock:
            try:
                self.wifi_sock.close()
            except Exception as e:
                log_debug(f"Error closing Wi-Fi socket: {e}")
        if self.bt_sock:
            try:
                self.bt_sock.close()
            except Exception as e:
                log_debug(f"Error closing Bluetooth socket: {e}")

    def _metric_loop(self):
        while self.running:
            time.sleep(0.25)
            with self.lock:
                now = time.time()
                elapsed = now - self.last_metric_time
                if elapsed >= 0.25:
                    self.wifi_speed = self.bytes_wifi_interval / elapsed
                    self.bt_speed = self.bytes_bt_interval / elapsed
                    self.bytes_wifi_interval = 0
                    self.bytes_bt_interval = 0
                    self.last_metric_time = now

                    if self.event_callback and self.active_file_info:
                        self._emit_progress()

    def _start_wifi_server(self, port: int):
        def loop():
            try:
                self.wifi_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                self.wifi_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                try:
                    self.wifi_sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
                except Exception:
                    pass
                self.wifi_sock.bind(("0.0.0.0", port))
                self.wifi_sock.listen(10)
                log_info(f"📶 Wi-Fi Server listening on 0.0.0.0:{port}")
                self._emit_event("LOG", f"📶 Wi-Fi Server listening on port {port}")
            except Exception as bind_err:
                log_error(f"Wi-Fi bind error on port {port}", exc=bind_err)
                self._emit_event("LOG", f"⚠️ Wi-Fi bind error on port {port}: {bind_err}")
                return

            while self.running:
                try:
                    client, addr = self.wifi_sock.accept()
                except Exception as accept_err:
                    if not self.running:
                        break
                    log_debug(f"Transient Wi-Fi accept notice: {accept_err}")
                    time.sleep(0.05)
                    continue

                try:
                    client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    client.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
                    client.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
                except Exception as opt_err:
                    log_debug(f"Socket opt error: {opt_err}")

                self.wifi_active = True
                self.last_client_ip = addr[0]
                log_info(f"⚡ Inbound Wi-Fi stream connected from {addr[0]}")
                self._emit_event("CLIENT_IP", addr[0])
                self._emit_event("LOG", f"⚡ Inbound Wi-Fi connected from {addr[0]}")
                threading.Thread(target=self._handle_stream, args=(client, "WIFI"), daemon=True, name=f"WifiStream-{addr[1]}").start()

        threading.Thread(target=loop, daemon=True, name="WifiAcceptThread").start()

    def _start_bt_server(self, channel: int):
        def loop():
            try:
                if not hasattr(socket, "AF_BLUETOOTH"):
                    return
                for ch in [channel, 4, 3, 2, 1]:
                    try:
                        self.bt_sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
                        self.bt_sock.bind(("0:0:0:0:0:0", ch))
                        self.bt_sock.listen(1)
                        log_info(f"ᛒ Bluetooth RFCOMM listening on Channel {ch}")
                        self._emit_event("LOG", f"ᛒ Bluetooth RFCOMM listening on Channel {ch}")
                        break
                    except Exception:
                        if self.bt_sock:
                            self.bt_sock.close()
                            self.bt_sock = None

                if not self.bt_sock:
                    return

                while self.running:
                    try:
                        client, addr = self.bt_sock.accept()
                    except Exception as bt_accept_err:
                        if not self.running:
                            break
                        log_debug(f"Transient BT accept notice: {bt_accept_err}")
                        time.sleep(0.1)
                        continue

                    self.bt_active = True
                    log_info("ᛒ Inbound Bluetooth connection established")
                    self._emit_event("LOG", "ᛒ Inbound Bluetooth connected")
                    threading.Thread(target=self._handle_stream, args=(client, "BLUETOOTH"), daemon=True, name="BtStream").start()
            except Exception as e:
                if self.running:
                    log_error("Bluetooth listener notice", exc=e)

        threading.Thread(target=loop, daemon=True, name="BtAcceptThread").start()

    def _handle_stream(self, sock: socket.socket, transport_name: str):
        try:
            read_buffer = bytearray(BUFFER_SIZE)
            read_view = memoryview(read_buffer)

            while self.running:
                # 1. Read 4-byte Magic
                magic = self._read_exact(sock, 4)
                if not magic:
                    log_debug(f"[{transport_name}] Stream ended (EOF)")
                    break
                if magic != MAGIC:
                    log_warn(f"[{transport_name}] Unexpected magic: {magic!r}")
                    break

                # 2. Read 4-byte Header Length
                len_bytes = self._read_exact(sock, 4)
                if not len_bytes:
                    break
                header_len = struct.unpack("!I", len_bytes)[0]
                if header_len > HEADER_MAX_LEN:
                    log_error(f"[{transport_name}] Header length exceeds maximum: {header_len}")
                    break

                # 3. Read Header JSON
                raw_header = self._read_exact(sock, header_len)
                if not raw_header:
                    break
                meta = json.loads(raw_header.decode("utf-8"))

                # Check for Live Clipboard Sync packet
                if meta.get("type") == "CLIPBOARD_SYNC":
                    text = meta.get("text", "")
                    log_info(f"[{transport_name}] Received clipboard sync ({len(text)} chars)")
                    self._emit_event("CLIPBOARD", text)
                    continue

                # Check for Transfer Permission Handshake Request
                if meta.get("type") == "TRANSFER_REQUEST":
                    sender_name = meta.get("sender_name", "Remote Device")
                    total_files = meta.get("total_files", 1)
                    total_bytes = meta.get("total_bytes", 0)
                    client_pin = str(meta.get("pin", "")).strip()

                    # PIN Security verification
                    if self.security_pin and client_pin != self.security_pin:
                        log_warn(f"[{transport_name}] Transfer rejected: Invalid PIN ({client_pin!r} vs {self.security_pin!r})")
                        resp = {"type": "TRANSFER_DECLINE", "reason": "INVALID_PIN"}
                        raw_resp = json.dumps(resp).encode("utf-8")
                        sock.sendall(MAGIC + struct.pack("!I", len(raw_resp)) + raw_resp)
                        self._emit_event("LOG", f"⚠️ Rejected transfer from {sender_name}: Invalid PIN")
                        break

                    log_info(f"[{transport_name}] Incoming transfer request from '{sender_name}': {total_files} files ({total_bytes} bytes)")
                    
                    accepted = self._prompt_transfer_request(sender_name, total_files, total_bytes)
                    resp_type = "TRANSFER_ACCEPT" if accepted else "TRANSFER_DECLINE"
                    resp = {"type": resp_type}
                    raw_resp = json.dumps(resp).encode("utf-8")
                    sock.sendall(MAGIC + struct.pack("!I", len(raw_resp)) + raw_resp)
                    if not accepted:
                        log_info(f"[{transport_name}] Transfer declined by user.")
                        self._emit_event("LOG", f"⚠️ Declined transfer from {sender_name}")
                        break
                    else:
                        log_info(f"[{transport_name}] Transfer accepted. Waiting for continuous stream...")
                        self._emit_event("LOG", f"✅ Accepted transfer from {sender_name} ({total_files} files)")
                    continue

                # Check if phone is requesting queued batch from PC (Pull mode)
                if meta.get("action") == "REQUEST_BATCH":
                    log_info(f"[{transport_name}] Phone requested outbound batch from PC")
                    if self.pending_batch_provider:
                        self.pending_batch_provider(sock)
                    else:
                        empty_obj = {"status": "EMPTY", "message": "No pending batch queued on PC"}
                        raw = json.dumps(empty_obj).encode("utf-8")
                        sock.sendall(MAGIC + struct.pack("!I", len(raw)) + raw)
                    break

                # Check if BATCH DONE packet
                if meta.get("type") == "BATCH_DONE":
                    log_info(f"[{transport_name}] Received BATCH_DONE packet")
                    sock.sendall(b"BATCH_OK\r\n")
                    self._emit_event("BATCH_COMPLETE", meta)
                    continue

                # Process File
                file_idx = meta.get("file_index", 1)
                total_files = meta.get("total_files", 1)
                rel_path = meta.get("relative_path", "file.dat")
                filesize = meta.get("filesize", 0)
                offset = meta.get("offset", 0)
                batch_total_bytes = meta.get("batch_total_bytes", filesize)

                # Path sanitation
                clean_rel = rel_path.replace("\\", "/").strip("/")
                parts = [p for p in clean_rel.split("/") if p and p not in (".", "..")]
                safe_rel = os.path.join(*parts) if parts else "file.dat"
                full_dest = os.path.join(self.save_dir, safe_rel)
                dest_dir = os.path.dirname(full_dest)
                if dest_dir not in self._dir_cache:
                    os.makedirs(dest_dir, exist_ok=True)
                    self._dir_cache.add(dest_dir)

                with self.lock:
                    self.active_file_info = meta
                    self.active_file_info["safe_rel"] = safe_rel
                    self.active_file_bytes_written = offset
                    self.active_file_total_bytes = filesize
                    self.batch_total_files = total_files
                    self.batch_total_bytes = batch_total_bytes

                start_file_time = time.time()
                self._emit_event("FILE_START", {
                    "file_index": file_idx,
                    "total_files": total_files,
                    "relative_path": safe_rel,
                    "filesize": filesize,
                    "offset": offset,
                    "transport": transport_name
                })

                # Open destination file with 1 MB write buffer for maximum disk throughput
                mode = "r+b" if (offset > 0 and os.path.exists(full_dest)) else "wb"
                f = open(full_dest, mode, buffering=1024 * 1024)
                if offset > 0:
                    f.seek(offset)

                bytes_remaining = filesize - offset

                # Zero-Latency Stream Ingestion (Direct from TCP Wire into Disk!)
                while bytes_remaining > 0 and self.running:
                    to_read = min(bytes_remaining, BUFFER_SIZE)
                    n = sock.recv_into(read_view[:to_read])
                    if n <= 0:
                        raise ConnectionResetError(f"Connection dropped mid-stream at offset {filesize - bytes_remaining}")

                    f.write(read_view[:n])
                    bytes_remaining -= n

                    self.active_file_bytes_written += n
                    if transport_name == "WIFI":
                        self.bytes_wifi_interval += n
                    else:
                        self.bytes_bt_interval += n

                f.flush()
                f.close()

                # File complete - ZERO INTER-FILE WAIT!
                # Immediately ready for the next file header on the same socket!
                duration = time.time() - start_file_time
                avg_speed_mb = (filesize / (1024 * 1024)) / max(0.001, duration)
                log_info(f"[{transport_name}] ✅ Saved: {safe_rel} ({filesize} bytes, {duration:.3f}s, {avg_speed_mb:.1f} MB/s)")

                with self.lock:
                    self.batch_files_completed += 1
                    self.batch_bytes_completed += filesize

                self._emit_event("FILE_DONE", {
                    "file_index": file_idx,
                    "total_files": total_files,
                    "relative_path": safe_rel,
                    "filesize": filesize,
                    "duration": duration,
                    "transport": transport_name
                })

        except ConnectionResetError as cre:
            log_warn(f"[{transport_name}] Client connection reset: {cre}")
            self._emit_event("FAILOVER", f"⚠️ {transport_name} disconnected mid-stream")
        except Exception as e:
            log_error(f"[{transport_name}] Stream handler exception", exc=e)
            self._emit_event("FAILOVER", f"⚠️ {transport_name} error: {e}")
        finally:
            try:
                sock.close()
            except Exception:
                pass
            if transport_name == "WIFI":
                self.wifi_active = False
            else:
                self.bt_active = False

    def _read_exact(self, sock: socket.socket, num_bytes: int) -> Optional[bytes]:
        data = bytearray()
        while len(data) < num_bytes and self.running:
            try:
                packet = sock.recv(min(num_bytes - len(data), 65536))
                if not packet:
                    return None
                data.extend(packet)
            except Exception:
                return None
        return bytes(data) if len(data) == num_bytes else None

    def _prompt_transfer_request(self, sender_name: str, total_files: int, total_bytes: int) -> bool:
        if self.auto_accept:
            return True
        if not self.request_callback:
            return True
        decision_event = threading.Event()
        decision_box = [False]

        def on_decision(accepted: bool):
            decision_box[0] = accepted
            decision_event.set()

        try:
            self.request_callback(sender_name, total_files, total_bytes, on_decision)
            # Wait up to 60s for user response
            if decision_event.wait(timeout=60.0):
                return decision_box[0]
            return False
        except Exception as e:
            log_error(f"Error prompting transfer request: {e}", exc=e)
            return True

    def send_clipboard_to_mobile(self, text: str, target_ip: str, target_port: int = 5052) -> bool:
        """Sends clipboard text from PC to mobile device over TCP."""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(2.5)
            s.connect((target_ip, target_port))
            obj = {"type": "CLIPBOARD_SYNC", "text": text}
            raw = json.dumps(obj).encode("utf-8")
            s.sendall(MAGIC + struct.pack("!I", len(raw)) + raw)
            s.close()
            log_info(f"Sent clipboard text to mobile ({target_ip})")
            self._emit_event("LOG", f"📋 Synced clipboard to mobile ({target_ip})")
            return True
        except Exception as e:
            log_error(f"Failed to send clipboard to mobile: {e}")
            self._emit_event("LOG", f"⚠️ Failed to send clipboard to mobile: {e}")
            return False

    def _emit_progress(self):
        cur = self.active_file_info
        if not cur:
            return

        f_total = max(1, self.active_file_total_bytes)
        f_sent = self.active_file_bytes_written
        f_pct = (f_sent / f_total) * 100.0

        b_total = max(1, self.batch_total_bytes)
        b_sent = self.batch_bytes_completed + f_sent
        b_pct = min(100.0, (b_sent / b_total) * 100.0)

        total_speed = self.wifi_speed + self.bt_speed
        rem_bytes = max(0, b_total - b_sent)
        eta_sec = int(rem_bytes / total_speed) if total_speed > 0 else 0

        self._emit_event("PROGRESS", {
            "file_index": cur.get("file_index", 1),
            "total_files": cur.get("total_files", 1),
            "relative_path": cur.get("safe_rel", "file"),
            "file_percent": f_pct,
            "file_bytes": f_sent,
            "file_total": f_total,
            "batch_percent": b_pct,
            "batch_bytes": b_sent,
            "batch_total": b_total,
            "wifi_speed_mb": self.wifi_speed / (1024 * 1024),
            "bt_speed_kb": self.bt_speed / 1024,
            "total_speed_mb": total_speed / (1024 * 1024),
            "eta_sec": eta_sec,
            "wifi_active": self.wifi_active,
            "bt_active": self.bt_active
        })

    def _emit_event(self, ev_type: str, details):
        if self.event_callback:
            try:
                self.event_callback(ev_type, details)
            except Exception as cb_err:
                log_debug(f"Event callback exception: {cb_err}")
