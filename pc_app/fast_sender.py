"""
TurboDrop PC Fast Sender Engine
Pipelined zero-latency sender to stream files and folder hierarchies
from Windows PC to Android mobile device over Wi-Fi with permission handshake
and automatic background reconnection.
"""

import os
import sys
import time
import json
import socket
import struct
import threading
from typing import List, Dict, Callable, Optional, Tuple

from logger import log_info, log_warn, log_error, log_debug

MAGIC = b"TURB"
BUFFER_SIZE = 512 * 1024  # 512 KB chunk size for maximum wire throughput


class FastSender:
    def __init__(self, event_callback: Optional[Callable] = None):
        self.event_callback = event_callback
        self.is_running = threading.Event()
        self.active_socket = None

    @staticmethod
    def scan_paths(paths: List[str]) -> Tuple[List[Dict], int]:
        """
        Scans a list of file or folder paths and builds a flat list of items:
        [{'full_path': str, 'relative_path': str, 'filesize': int}]
        """
        items = []
        total_bytes = 0

        for p in paths:
            p = os.path.abspath(p)
            if not os.path.exists(p):
                continue

            if os.path.isfile(p):
                name = os.path.basename(p)
                size = os.path.getsize(p)
                items.append({
                    "full_path": p,
                    "relative_path": name,
                    "filesize": size
                })
                total_bytes += size
            elif os.path.isdir(p):
                base_dir_name = os.path.basename(p.rstrip("\\/"))
                for root, _, files in os.walk(p):
                    for f in files:
                        full = os.path.join(root, f)
                        rel_from_root = os.path.relpath(full, p)
                        rel_path = os.path.join(base_dir_name, rel_from_root).replace("\\", "/")
                        size = os.path.getsize(full)
                        items.append({
                            "full_path": full,
                            "relative_path": rel_path,
                            "filesize": size
                        })
                        total_bytes += size

        return items, total_bytes

    def send_stream(self, sock: socket.socket, items: List[Dict], total_bytes: int,
                    start_file_idx: int = 0, start_offset: int = 0,
                    is_resume: bool = False) -> Tuple[bool, int, int]:
        """
        Streams items over an already connected TCP socket with ZERO inter-file latency.
        Returns: (success: bool, last_file_index: int, last_file_offset: int)
        """
        self.is_running.set()
        self.active_socket = sock
        total_files = len(items)
        batch_sent = sum(items[j]["filesize"] for j in range(start_file_idx)) + start_offset
        start_time = time.time()
        last_metric_time = start_time
        interval_bytes = 0

        curr_idx = start_file_idx
        curr_offset = start_offset

        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4 * 1024 * 1024)
            except Exception:
                pass

            # 1. Handshake Permission: Send TRANSFER_REQUEST if not resuming
            if not is_resume and start_file_idx == 0 and start_offset == 0:
                req_obj = {
                    "type": "TRANSFER_REQUEST",
                    "sender_name": socket.gethostname(),
                    "total_files": total_files,
                    "total_bytes": total_bytes
                }
                raw_req = json.dumps(req_obj).encode("utf-8")
                sock.sendall(MAGIC + struct.pack("!I", len(raw_req)) + raw_req)
                self._emit("LOG", f"Waiting for mobile receiver to accept transfer ({total_files} files)...")

                resp_magic = self._read_exact(sock, 4)
                if not resp_magic or resp_magic != MAGIC:
                    self._emit("ERROR", "Invalid response from mobile device")
                    return False, curr_idx, curr_offset

                len_b = self._read_exact(sock, 4)
                if not len_b:
                    return False, curr_idx, curr_offset
                resp_len = struct.unpack("!I", len_b)[0]
                resp_bytes = self._read_exact(sock, resp_len)
                if not resp_bytes:
                    return False, curr_idx, curr_offset
                resp_json = json.loads(resp_bytes.decode("utf-8"))

                if resp_json.get("type") != "TRANSFER_ACCEPT":
                    self._emit("LOG", "⚠️ Transfer declined by mobile receiver.")
                    self._emit("DECLINED", "Transfer declined by mobile receiver.")
                    return False, curr_idx, curr_offset

                self._emit("LOG", "✅ Transfer accepted! Wire streaming at maximum speed...")

            read_buf = bytearray(BUFFER_SIZE)

            for i in range(start_file_idx, total_files):
                if not self.is_running.is_set():
                    break

                curr_idx = i
                item = items[i]
                file_idx = i + 1
                filesize = item["filesize"]
                rel_path = item["relative_path"]
                full_path = item["full_path"]
                offset = start_offset if i == start_file_idx else 0
                curr_offset = offset

                # 2. Build and send Header
                header_obj = {
                    "file_index": file_idx,
                    "total_files": total_files,
                    "relative_path": rel_path,
                    "filesize": filesize,
                    "offset": offset,
                    "batch_total_bytes": total_bytes
                }
                header_bytes = json.dumps(header_obj).encode("utf-8")
                header_pkt = MAGIC + struct.pack("!I", len(header_bytes)) + header_bytes
                sock.sendall(header_pkt)

                self._emit("FILE_START", {
                    "file_index": file_idx,
                    "total_files": total_files,
                    "relative_path": rel_path,
                    "filesize": filesize,
                    "offset": offset
                })

                file_sent = offset
                file_start_time = time.time()

                # 3. Stream File Data directly from disk to socket
                with open(full_path, "rb") as f:
                    if offset > 0:
                        f.seek(offset)

                    while file_sent < filesize and self.is_running.is_set():
                        to_read = min(BUFFER_SIZE, filesize - file_sent)
                        n = f.readinto(memoryview(read_buf)[:to_read])
                        if not n:
                            break

                        sock.sendall(memoryview(read_buf)[:n])
                        file_sent += n
                        curr_offset = file_sent
                        batch_sent += n
                        interval_bytes += n

                        now = time.time()
                        elapsed = now - last_metric_time
                        if elapsed >= 0.25:
                            speed_mb = (interval_bytes / (1024.0 * 1024.0)) / elapsed
                            interval_bytes = 0
                            last_metric_time = now

                            rem_bytes = max(0, total_bytes - batch_sent)
                            rem_speed_bytes = speed_mb * 1024 * 1024
                            eta = int(rem_bytes / rem_speed_bytes) if rem_speed_bytes > 0 else 0

                            self._emit("PROGRESS", {
                                "file_index": file_idx,
                                "total_files": total_files,
                                "filename": rel_path,
                                "file_sent": file_sent,
                                "file_total": filesize,
                                "batch_sent": batch_sent,
                                "batch_total": total_bytes,
                                "speed_mb": speed_mb,
                                "eta_sec": eta
                            })

                file_dur = max(0.001, time.time() - file_start_time)
                file_speed = ((filesize - offset) / (1024 * 1024)) / file_dur
                log_info(f"[SENDER] ✅ Sent: {rel_path} ({filesize} bytes, {file_dur:.2f}s, {file_speed:.1f} MB/s)")
                self._emit("FILE_DONE", {
                    "file_index": file_idx,
                    "total_files": total_files,
                    "relative_path": rel_path,
                    "filesize": filesize,
                    "speed_mb": file_speed
                })

                # Completed this file cleanly; next file starts at offset 0
                start_offset = 0
                curr_offset = filesize

            # 4. Send BATCH_DONE
            done_obj = {"type": "BATCH_DONE"}
            done_bytes = json.dumps(done_obj).encode("utf-8")
            done_pkt = MAGIC + struct.pack("!I", len(done_bytes)) + done_bytes
            sock.sendall(done_pkt)

            try:
                sock.settimeout(4.0)
                ack = sock.recv(16)
                log_debug(f"[SENDER] Received batch ack: {ack!r}")
            except Exception as e:
                log_debug(f"[SENDER] Batch ACK notice: {e}")

            total_dur = max(0.01, time.time() - start_time)
            avg_speed = (batch_sent / (1024 * 1024)) / total_dur
            log_info(f"[SENDER] 🎉 Batch Complete: {total_files} files ({batch_sent / (1024*1024):.1f} MB) in {total_dur:.2f}s (Avg: {avg_speed:.1f} MB/s)")

            self._emit("BATCH_DONE", {
                "total_files": total_files,
                "batch_sent": batch_sent,
                "duration_sec": total_dur,
                "avg_speed_mb": avg_speed
            })
            return True, total_files, 0

        except Exception as e:
            log_error(f"[SENDER] Connection interrupted at file {curr_idx+1} offset {curr_offset}: {e}")
            return False, curr_idx, curr_offset
        finally:
            if self.active_socket:
                try:
                    self.active_socket.close()
                except Exception:
                    pass
                self.active_socket = None

    def start_send_async(self, target_ip: str, target_port: int, items: List[Dict], total_bytes: int):
        """
        Connects to target phone and starts sending in background thread.
        Features PERSISTENT AUTO-RECONNECT: If interrupted, automatically reconnects and resumes!
        """
        def run():
            self.is_running.set()
            current_idx = 0
            current_offset = 0
            retry_count = 0
            max_retries = 40  # Keep retrying for over 60 seconds
            is_resumed = False

            while self.is_running.is_set() and current_idx < len(items):
                try:
                    conn_msg = f"Connecting to mobile phone at {target_ip}:{target_port}..."
                    if is_resumed:
                        conn_msg = f"Auto-reconnecting to mobile phone (Attempt #{retry_count})..."
                    log_info(f"[SENDER] {conn_msg}")
                    self._emit("LOG", f"⚡ {conn_msg}")

                    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    s.settimeout(3.5)
                    s.connect((target_ip, target_port))
                    s.settimeout(None)

                    # Reset retry counter upon successful connection
                    retry_count = 0

                    success, next_idx, next_offset = self.send_stream(
                        s, items, total_bytes,
                        start_file_idx=current_idx,
                        start_offset=current_offset,
                        is_resume=is_resumed
                    )

                    if success:
                        break  # Batch complete!

                    current_idx = next_idx
                    current_offset = next_offset
                    is_resumed = True

                except Exception as e:
                    if not self.is_running.is_set():
                        break

                    retry_count += 1
                    if retry_count > max_retries:
                        err_msg = f"Failed to reconnect to phone after {max_retries} attempts: {e}"
                        log_error(f"[SENDER] {err_msg}")
                        self._emit("ERROR", err_msg)
                        break

                    self._emit("LOG", f"⚠️ Connection lost. Auto-reconnecting in background (Attempt #{retry_count})...")
                    time.sleep(1.5)

            self.is_running.clear()

        threading.Thread(target=run, daemon=True, name="PcFastSenderThread").start()

    def cancel(self):
        self.is_running.clear()
        if self.active_socket:
            try:
                self.active_socket.close()
            except Exception:
                pass
            self.active_socket = None

    def _read_exact(self, sock: socket.socket, num_bytes: int) -> Optional[bytes]:
        data = bytearray()
        while len(data) < num_bytes and self.is_running.is_set():
            try:
                packet = sock.recv(min(num_bytes - len(data), 65536))
                if not packet:
                    return None
                data.extend(packet)
            except Exception:
                return None
        return bytes(data) if len(data) == num_bytes else None

    def _emit(self, ev_type: str, details):
        if self.event_callback:
            try:
                self.event_callback(ev_type, details)
            except Exception:
                pass
