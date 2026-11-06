"""
TurboDrop PC - Ultra-Fast Bidirectional Stream Transfer GUI
Modern international English interface with transfer permission handshake,
live speed gauges, folder preservation, and persistent logging.
"""

import os
import sys
import time
import socket
import threading
import queue
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from logger import log_info, log_warn, log_error, LOG_FILE
from fast_engine import FastReceiverEngine
from discovery_beacon import DiscoveryBeacon
from web_server import WebCompanionServer
from fast_sender import FastSender

# Modern GitHub-Inspired Dark Theme Palette
BG_DARK = "#0D1117"
CARD_BG = "#161B22"
CARD_BORDER = "#30363D"
ACCENT_CYAN = "#58A6FF"
ACCENT_GREEN = "#3FB950"
ACCENT_BLUE = "#1F6FEB"
ACCENT_AMBER = "#D29922"
ACCENT_RED = "#F85149"
TEXT_PRIMARY = "#F0F6FC"
TEXT_MUTED = "#8B949E"

APP_VERSION = "1.0.0"


def get_local_ips():
    ips = []
    try:
        hostname = socket.gethostname()
        for ip in socket.gethostbyname_ex(hostname)[2]:
            if not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    return ips if ips else ["127.0.0.1"]


class TurboDropGUI:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title(f"TurboDrop v{APP_VERSION} - High-Speed PC Companion")
        self.root.geometry("880x740")
        self.root.minsize(820, 680)
        self.root.configure(bg=BG_DARK)

        # Set Window Icon
        try:
            if getattr(sys, 'frozen', False):
                ico_path = os.path.join(getattr(sys, '_MEIPASS', os.path.dirname(sys.executable)), "app_icon.ico")
                if not os.path.exists(ico_path):
                    ico_path = os.path.join(os.path.dirname(sys.executable), "app_icon.ico")
            else:
                ico_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app_icon.ico")
            if os.path.exists(ico_path):
                self.root.iconbitmap(ico_path)
        except Exception:
            pass

        # Storage directory
        if getattr(sys, 'frozen', False):
            base_dir = os.path.dirname(sys.executable)
        else:
            base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

        self.save_dir = os.path.abspath(os.path.join(base_dir, "Received_Files"))
        os.makedirs(self.save_dir, exist_ok=True)

        self.gui_queue = queue.Queue()
        self.engine = FastReceiverEngine(self.save_dir, self._handle_engine_event)
        self.engine.request_callback = self._on_inbound_transfer_request
        self.engine.security_pin = "1337"
        self.sender = FastSender(self._handle_sender_event)

        self.outbound_items = []
        self.outbound_total_bytes = 0
        self.beacon = None
        self.web_server = None
        self.security_pin_var = tk.StringVar(value="1337")

        self._build_ui()
        self._setup_drag_and_drop()
        self._check_cli_arguments()
        self._start_servers()
        self.root.after(20, self._process_gui_queue)

    def _process_gui_queue(self):
        try:
            for _ in range(60):
                if self.gui_queue.empty():
                    break
                fn, args = self.gui_queue.get_nowait()
                try:
                    fn(*args)
                except Exception:
                    pass
        except Exception:
            pass
        finally:
            try:
                if self.root:
                    self.root.after(20, self._process_gui_queue)
            except Exception:
                pass

    def _build_ui(self):
        # 1. Header Bar
        header_frame = tk.Frame(self.root, bg=CARD_BG, height=65, padx=20, pady=10)
        header_frame.pack(fill=tk.X, side=tk.TOP)

        title_lbl = tk.Label(header_frame, text="⚡ TurboDrop", font=("Segoe UI", 20, "bold"), fg=ACCENT_CYAN, bg=CARD_BG)
        title_lbl.pack(side=tk.LEFT)

        sub_lbl = tk.Label(header_frame, text=f" v{APP_VERSION} • High-Speed Transfer", font=("Segoe UI", 11), fg=TEXT_MUTED, bg=CARD_BG)
        sub_lbl.pack(side=tk.LEFT, pady=(5, 0))

        # Action buttons in header
        btn_box = tk.Frame(header_frame, bg=CARD_BG)
        btn_box.pack(side=tk.RIGHT)

        open_folder_btn = tk.Button(btn_box, text="📂 Open Folder", font=("Segoe UI", 9, "bold"), bg="#238636", fg="#FFF",
                                    activebackground="#2EA043", relief=tk.FLAT, padx=10, pady=4, cursor="hand2", command=self._open_save_folder)
        open_folder_btn.pack(side=tk.LEFT, padx=(0, 6))

        menu_btn = tk.Button(btn_box, text="➕ Right-Click Menu", font=("Segoe UI", 9, "bold"), bg="#21262D", fg=TEXT_MUTED,
                             activebackground=ACCENT_BLUE, activeforeground="#FFF", relief=tk.FLAT, padx=8, pady=4, cursor="hand2", command=self._toggle_context_menu)
        menu_btn.pack(side=tk.LEFT, padx=(0, 6))

        self.folder_lbl = tk.Label(btn_box, text=f"📁 {os.path.basename(self.save_dir)}", font=("Segoe UI", 10), fg=TEXT_PRIMARY, bg=CARD_BG)
        self.folder_lbl.pack(side=tk.LEFT, padx=6)

        change_btn = tk.Button(btn_box, text="Change Folder", font=("Segoe UI", 9, "bold"), bg=CARD_BORDER, fg=TEXT_PRIMARY,
                               activebackground=ACCENT_BLUE, activeforeground="#FFF", relief=tk.FLAT, padx=10, pady=4, cursor="hand2", command=self._choose_folder)
        change_btn.pack(side=tk.LEFT)

        log_btn = tk.Button(btn_box, text="Logs", font=("Segoe UI", 9, "bold"), bg="#21262D", fg=ACCENT_CYAN,
                            activebackground=ACCENT_BLUE, activeforeground="#FFF", relief=tk.FLAT, padx=10, pady=4, cursor="hand2", command=self._open_log_file)
        log_btn.pack(side=tk.LEFT, padx=(6, 0))

        # 2. Server Status Badges
        badges_card = tk.Frame(self.root, bg=CARD_BG, padx=16, pady=12, highlightbackground=CARD_BORDER, highlightthickness=1)
        badges_card.pack(fill=tk.X, padx=20, pady=12)

        # Wi-Fi badge
        wifi_box = tk.Frame(badges_card, bg=CARD_BG)
        wifi_box.pack(side=tk.LEFT, expand=True, fill=tk.X)
        tk.Label(wifi_box, text="📶 Wi-Fi Stream Server (Port 5050)", font=("Segoe UI", 10, "bold"), fg=TEXT_MUTED, bg=CARD_BG).pack(anchor="w")
        ips_str = ", ".join(get_local_ips()[:2])
        self.wifi_info_lbl = tk.Label(wifi_box, text=f"IP: {ips_str}", font=("Consolas", 12, "bold"), fg=ACCENT_GREEN, bg=CARD_BG)
        self.wifi_info_lbl.pack(anchor="w", pady=(2, 0))
        self.wifi_stat_lbl = tk.Label(wifi_box, text="Speed: 0.0 MB/s • Status: Ready", font=("Segoe UI", 9), fg=TEXT_MUTED, bg=CARD_BG)
        self.wifi_stat_lbl.pack(anchor="w")

        # Separator
        tk.Frame(badges_card, bg=CARD_BORDER, width=1, height=50).pack(side=tk.LEFT, fill=tk.Y, padx=15)

        # Bluetooth badge
        bt_box = tk.Frame(badges_card, bg=CARD_BG)
        bt_box.pack(side=tk.LEFT, expand=True, fill=tk.X)
        tk.Label(bt_box, text="ᛒ Bluetooth RFCOMM (Channel 5)", font=("Segoe UI", 10, "bold"), fg=TEXT_MUTED, bg=CARD_BG).pack(anchor="w")
        self.bt_info_lbl = tk.Label(bt_box, text="Ready on Channel 5", font=("Consolas", 12, "bold"), fg=ACCENT_CYAN, bg=CARD_BG)
        self.bt_info_lbl.pack(anchor="w", pady=(2, 0))
        self.bt_stat_lbl = tk.Label(bt_box, text="Speed: 0.0 KB/s • Status: Ready", font=("Segoe UI", 9), fg=TEXT_MUTED, bg=CARD_BG)
        self.bt_stat_lbl.pack(anchor="w")

        # 3. Send to Mobile Device Card
        send_card = tk.Frame(self.root, bg=CARD_BG, padx=16, pady=12, highlightbackground=CARD_BORDER, highlightthickness=1)
        send_card.pack(fill=tk.X, padx=20, pady=(0, 10))

        s_top = tk.Frame(send_card, bg=CARD_BG)
        s_top.pack(fill=tk.X)

        tk.Label(s_top, text="📤 Send to Mobile Device", font=("Segoe UI", 11, "bold"), fg=ACCENT_CYAN, bg=CARD_BG).pack(side=tk.LEFT)

        self.send_status_lbl = tk.Label(s_top, text="No items selected", font=("Segoe UI", 9), fg=TEXT_MUTED, bg=CARD_BG)
        self.send_status_lbl.pack(side=tk.LEFT, padx=12)

        # Buttons on right
        btn_box2 = tk.Frame(s_top, bg=CARD_BG)
        btn_box2.pack(side=tk.RIGHT)

        tk.Button(btn_box2, text="📄 Pick Files", font=("Segoe UI", 9, "bold"), bg=CARD_BORDER, fg=TEXT_PRIMARY,
                  activebackground=ACCENT_BLUE, relief=tk.FLAT, padx=10, pady=4, cursor="hand2", command=self._pick_send_files).pack(side=tk.LEFT, padx=2)
        tk.Button(btn_box2, text="📁 Pick Folder", font=("Segoe UI", 9, "bold"), bg=CARD_BORDER, fg=TEXT_PRIMARY,
                  activebackground=ACCENT_BLUE, relief=tk.FLAT, padx=10, pady=4, cursor="hand2", command=self._pick_send_folder).pack(side=tk.LEFT, padx=2)

        s_bot = tk.Frame(send_card, bg=CARD_BG)
        s_bot.pack(fill=tk.X, pady=(10, 0))

        tk.Label(s_bot, text="Target Phone IP:", font=("Segoe UI", 9, "bold"), fg=TEXT_MUTED, bg=CARD_BG).pack(side=tk.LEFT)
        self.phone_ip_var = tk.StringVar(value="192.168.100.5")
        self.phone_ip_entry = tk.Entry(s_bot, textvariable=self.phone_ip_var, width=15, font=("Consolas", 10), bg=BG_DARK, fg=TEXT_PRIMARY, relief=tk.FLAT, insertbackground=TEXT_PRIMARY)
        self.phone_ip_entry.pack(side=tk.LEFT, padx=(6, 10))

        self.btn_send_now = tk.Button(s_bot, text="🚀 Send to Mobile", font=("Segoe UI", 9, "bold"),
                                      bg="#238636", fg="#FFFFFF", activebackground="#2EA043", activeforeground="#FFF",
                                      relief=tk.FLAT, padx=12, pady=4, cursor="hand2", command=self._send_to_phone)
        self.btn_send_now.pack(side=tk.LEFT)

        tk.Button(s_bot, text="📋 Send Clipboard", font=("Segoe UI", 9, "bold"),
                  bg="#21262D", fg=TEXT_PRIMARY, activebackground=ACCENT_BLUE,
                  relief=tk.FLAT, padx=10, pady=4, cursor="hand2", command=self._send_clipboard_to_phone).pack(side=tk.LEFT, padx=(8, 0))

        tk.Label(s_bot, text="PIN:", font=("Segoe UI", 9, "bold"), fg=TEXT_MUTED, bg=CARD_BG).pack(side=tk.LEFT, padx=(10, 2))
        self.pin_entry = tk.Entry(s_bot, textvariable=self.security_pin_var, width=6, font=("Consolas", 10, "bold"), bg=BG_DARK, fg=ACCENT_CYAN, justify="center", relief=tk.FLAT, insertbackground=TEXT_PRIMARY)
        self.pin_entry.pack(side=tk.LEFT)
        self.security_pin_var.trace_add("write", lambda *_: setattr(self.engine, "security_pin", self.security_pin_var.get().strip()))

        # 4. Live Transfer Dashboard Card
        transfer_card = tk.Frame(self.root, bg=CARD_BG, padx=16, pady=12, highlightbackground=CARD_BORDER, highlightthickness=1)
        transfer_card.pack(fill=tk.X, padx=20, pady=(0, 10))

        t_top = tk.Frame(transfer_card, bg=CARD_BG)
        t_top.pack(fill=tk.X)
        self.filename_lbl = tk.Label(t_top, text="Ready for incoming/outgoing transfers...", font=("Segoe UI", 11, "bold"), fg=TEXT_PRIMARY, bg=CARD_BG)
        self.filename_lbl.pack(side=tk.LEFT)

        self.speed_gauge_lbl = tk.Label(t_top, text="0.0 MB/s", font=("Segoe UI", 16, "bold"), fg=ACCENT_CYAN, bg=CARD_BG)
        self.speed_gauge_lbl.pack(side=tk.RIGHT)

        # File Progress bar
        tk.Label(transfer_card, text="Current File Progress:", font=("Segoe UI", 9), fg=TEXT_MUTED, bg=CARD_BG).pack(anchor="w", pady=(6, 2))
        self.file_progress_var = tk.DoubleVar(value=0.0)
        style = ttk.Style()
        style.theme_use('default')
        style.configure("File.Horizontal.TProgressbar", thickness=8, troughcolor=BG_DARK, background=ACCENT_CYAN)
        self.file_prog_bar = ttk.Progressbar(transfer_card, style="File.Horizontal.TProgressbar", variable=self.file_progress_var, maximum=100)
        self.file_prog_bar.pack(fill=tk.X)

        # Batch Progress bar
        tk.Label(transfer_card, text="Overall Batch Progress:", font=("Segoe UI", 9), fg=TEXT_MUTED, bg=CARD_BG).pack(anchor="w", pady=(6, 2))
        self.batch_progress_var = tk.DoubleVar(value=0.0)
        style.configure("Batch.Horizontal.TProgressbar", thickness=10, troughcolor=BG_DARK, background=ACCENT_GREEN)
        self.batch_prog_bar = ttk.Progressbar(transfer_card, style="Batch.Horizontal.TProgressbar", variable=self.batch_progress_var, maximum=100)
        self.batch_prog_bar.pack(fill=tk.X)

        # Progress details
        t_bot = tk.Frame(transfer_card, bg=CARD_BG)
        t_bot.pack(fill=tk.X, pady=(6, 0))
        self.prog_details_lbl = tk.Label(t_bot, text="0 / 0 MB (0%)", font=("Segoe UI", 9), fg=TEXT_MUTED, bg=CARD_BG)
        self.prog_details_lbl.pack(side=tk.LEFT)

        self.eta_lbl = tk.Label(t_bot, text="ETA: --:--", font=("Segoe UI", 9), fg=TEXT_MUTED, bg=CARD_BG)
        self.eta_lbl.pack(side=tk.RIGHT)

        # 5. Activity Log
        log_card = tk.Frame(self.root, bg=CARD_BG, padx=16, pady=10, highlightbackground=CARD_BORDER, highlightthickness=1)
        log_card.pack(fill=tk.BOTH, expand=True, padx=20, pady=(0, 15))

        tk.Label(log_card, text="📋 Activity Log (Saved to logs/pc_server.log)", font=("Segoe UI", 9, "bold"), fg=TEXT_MUTED, bg=CARD_BG).pack(anchor="w", pady=(0, 4))

        self.log_text = tk.Text(log_card, bg=BG_DARK, fg=TEXT_PRIMARY, font=("Consolas", 9), relief=tk.FLAT, padx=10, pady=8, state=tk.DISABLED)
        self.log_text.pack(fill=tk.BOTH, expand=True, side=tk.LEFT)

        scrollbar = ttk.Scrollbar(log_card, command=self.log_text.yview)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.log_text.config(yscrollcommand=scrollbar.set)

    def _open_save_folder(self):
        try:
            if not os.path.exists(self.save_dir):
                os.makedirs(self.save_dir, exist_ok=True)
            os.startfile(self.save_dir)
        except Exception as e:
            messagebox.showerror("TurboDrop", f"Could not open save directory: {e}")

    def _choose_folder(self):
        chosen = filedialog.askdirectory(initialdir=self.save_dir)
        if chosen:
            self.save_dir = chosen
            self.engine.save_dir = chosen
            self.folder_lbl.config(text=f"📁 {os.path.basename(self.save_dir)}")
            self._log(f"Save directory changed to: {self.save_dir}", TEXT_MUTED)

    def _open_log_file(self):
        if os.path.exists(LOG_FILE):
            os.system(f'notepad.exe "{LOG_FILE}"')

    def _pick_send_files(self):
        files = filedialog.askopenfilenames(title="Select Files to Send to Mobile")
        if files:
            self.outbound_items, self.outbound_total_bytes = FastSender.scan_paths(list(files))
            self._update_outbound_queue()

    def _pick_send_folder(self):
        folder = filedialog.askdirectory(title="Select Folder to Send to Mobile")
        if folder:
            self.outbound_items, self.outbound_total_bytes = FastSender.scan_paths([folder])
            self._update_outbound_queue()

    def _update_outbound_queue(self):
        cnt = len(self.outbound_items)
        size_str = self._format_bytes(self.outbound_total_bytes)
        self.send_status_lbl.config(text=f"Ready to send {cnt} items ({size_str})", fg=ACCENT_GREEN)
        self._log(f"Queued {cnt} items ({size_str}) for mobile receiver", ACCENT_CYAN)

        # Register provider so phone can also pull directly on demand
        self.engine.pending_batch_provider = lambda sock: self.sender.send_stream(
            sock, self.outbound_items, self.outbound_total_bytes
        )

    def _send_to_phone(self):
        if not self.outbound_items:
            messagebox.showinfo("TurboDrop", "Please select files or a folder to send first.")
            return

        phone_ip = self.phone_ip_var.get().strip()
        if not phone_ip:
            messagebox.showwarning("TurboDrop", "Please enter the target phone IP address.")
            return

        self._log(f"Initiating high-speed wire stream to phone at {phone_ip}:5052...", ACCENT_CYAN)
        self.sender.start_send_async(phone_ip, 5052, self.outbound_items, self.outbound_total_bytes)

    def _send_clipboard_to_phone(self):
        try:
            text = self.root.clipboard_get()
            if not text:
                messagebox.showinfo("TurboDrop", "Clipboard is empty.")
                return
            phone_ip = self.phone_ip_var.get().strip()
            if not phone_ip:
                messagebox.showwarning("TurboDrop", "Please enter target phone IP.")
                return
            self.engine.send_clipboard_to_mobile(text, phone_ip, 5052)
        except Exception as e:
            messagebox.showwarning("TurboDrop", f"Could not read clipboard: {e}")

    def _toggle_context_menu(self):
        import winreg
        try:
            installed = False
            try:
                with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Classes\*\shell\TurboDrop"):
                    installed = True
            except FileNotFoundError:
                installed = False

            if installed:
                winreg.DeleteKey(winreg.HKEY_CURRENT_USER, r"Software\Classes\*\shell\TurboDrop\command")
                winreg.DeleteKey(winreg.HKEY_CURRENT_USER, r"Software\Classes\*\shell\TurboDrop")
                winreg.DeleteKey(winreg.HKEY_CURRENT_USER, r"Software\Classes\Directory\shell\TurboDrop\command")
                winreg.DeleteKey(winreg.HKEY_CURRENT_USER, r"Software\Classes\Directory\shell\TurboDrop")
                self._log("Removed TurboDrop from Windows Explorer context menu.", TEXT_MUTED)
                messagebox.showinfo("TurboDrop", "Removed TurboDrop from Windows Right-Click Menu.")
            else:
                if getattr(sys, 'frozen', False):
                    cmd = f'"{sys.executable}" "%1"'
                    icon_path = sys.executable
                else:
                    py_exe = sys.executable
                    script_path = os.path.abspath(__file__)
                    cmd = f'"{py_exe}" "{script_path}" "%1"'
                    icon_path = "shell32.dll,238"

                for key_base in [r"Software\Classes\*\shell\TurboDrop", r"Software\Classes\Directory\shell\TurboDrop"]:
                    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, key_base) as k:
                        winreg.SetValueEx(k, "", 0, winreg.REG_SZ, "Send with TurboDrop")
                        winreg.SetValueEx(k, "Icon", 0, winreg.REG_SZ, icon_path)
                        with winreg.CreateKey(k, "command") as ck:
                            winreg.SetValueEx(ck, "", 0, winreg.REG_SZ, cmd)

                self._log("Added TurboDrop to Windows Explorer context menu!", ACCENT_GREEN)
                messagebox.showinfo("TurboDrop", "Added TurboDrop to Windows Right-Click Menu!\n\nYou can now right-click any file or folder in Windows Explorer and select 'Send with TurboDrop'.")
        except Exception as e:
            messagebox.showerror("TurboDrop", f"Error updating Windows context menu: {e}")

    def _setup_drag_and_drop(self):
        try:
            import windnd
            def on_drop(files):
                paths = []
                for item in files:
                    if isinstance(item, bytes):
                        item = item.decode("utf-8", errors="replace")
                    paths.append(item)
                if paths:
                    self.outbound_items, self.outbound_total_bytes = FastSender.scan_paths(paths)
                    self._update_outbound_queue()
                    self._log(f"📥 Drag & Drop: Staged {len(paths)} items", ACCENT_GREEN)
            windnd.hook_dropfiles(self.root, func=on_drop)
            log_info("Native Windows Drag & Drop initialized successfully.")
        except Exception as e:
            log_warn(f"Drag and drop hook notice: {e}")

    def _check_cli_arguments(self):
        if len(sys.argv) > 1:
            cli_paths = [p for p in sys.argv[1:] if os.path.exists(p)]
            if cli_paths:
                self.outbound_items, self.outbound_total_bytes = FastSender.scan_paths(cli_paths)
                self._update_outbound_queue()
                self._log(f"Launched with {len(cli_paths)} files/folders from Windows Explorer", ACCENT_CYAN)

    def _on_inbound_transfer_request(self, sender_name: str, total_files: int, total_bytes: int, decision_callback):
        """Called by receiver engine when an incoming batch request arrives from a mobile phone."""
        self.gui_queue.put((self._show_accept_dialog, (sender_name, total_files, total_bytes, decision_callback)))

    def _show_accept_dialog(self, sender_name: str, total_files: int, total_bytes: int, decision_callback):
        """Displays modal Accept / Decline permission dialog on PC."""
        dialog = tk.Toplevel(self.root)
        dialog.title("Incoming Transfer Request")
        dialog.geometry("440x230")
        dialog.configure(bg=CARD_BG)
        dialog.resizable(False, False)
        dialog.transient(self.root)
        dialog.grab_set()

        try:
            x = self.root.winfo_x() + (self.root.winfo_width() - 440) // 2
            y = self.root.winfo_y() + (self.root.winfo_height() - 230) // 2
            dialog.geometry(f"+{x}+{y}")
        except Exception:
            pass

        answered = [False]

        def accept():
            answered[0] = True
            decision_callback(True)
            dialog.destroy()

        def decline():
            answered[0] = True
            decision_callback(False)
            dialog.destroy()

        dialog.protocol("WM_DELETE_WINDOW", decline)

        tk.Label(dialog, text="📥 Incoming Transfer Request", font=("Segoe UI", 13, "bold"), fg=ACCENT_CYAN, bg=CARD_BG).pack(pady=(18, 6))

        info_txt = f"From: {sender_name}\nFiles: {total_files} items ({self._format_bytes(total_bytes)})\n\nDo you want to accept this transfer?"
        tk.Label(dialog, text=info_txt, font=("Segoe UI", 10), fg=TEXT_PRIMARY, bg=CARD_BG, justify=tk.CENTER).pack(pady=6)

        btn_box = tk.Frame(dialog, bg=CARD_BG)
        btn_box.pack(pady=15)

        tk.Button(btn_box, text="  ✅ Accept  ", font=("Segoe UI", 10, "bold"), bg="#238636", fg="#FFF",
                  activebackground="#2EA043", relief=tk.FLAT, padx=14, pady=6, cursor="hand2", command=accept).pack(side=tk.LEFT, padx=10)

        tk.Button(btn_box, text="  ❌ Decline  ", font=("Segoe UI", 10, "bold"), bg="#DA3633", fg="#FFF",
                  activebackground="#F85149", relief=tk.FLAT, padx=14, pady=6, cursor="hand2", command=decline).pack(side=tk.LEFT, padx=10)

    def _handle_sender_event(self, ev_type: str, details):
        try:
            self.gui_queue.put((self._dispatch_sender_event, (ev_type, details)))
        except Exception:
            pass

    def _dispatch_sender_event(self, ev_type: str, details):
        if ev_type == "LOG":
            self._append_log(details, ACCENT_CYAN)
        elif ev_type == "FILE_START":
            self.filename_lbl.config(text=f"📤 [{details['file_index']}/{details['total_files']}] {details['relative_path']}")
        elif ev_type == "FILE_DONE":
            self._append_log(f"📤 [{details['file_index']}/{details['total_files']}] Sent: {details['relative_path']} ({details['speed_mb']:.1f} MB/s)", ACCENT_GREEN)
        elif ev_type == "PROGRESS":
            f_pct = (details['file_sent'] * 100.0) / max(1, details['file_total'])
            b_pct = (details['batch_sent'] * 100.0) / max(1, details['batch_total'])
            self.file_progress_var.set(f_pct)
            self.batch_progress_var.set(b_pct)
            self.speed_gauge_lbl.config(text=f"{details['speed_mb']:.1f} MB/s")
            self.prog_details_lbl.config(
                text=f"Sending: {self._format_bytes(details['batch_sent'])} / {self._format_bytes(details['batch_total'])} ({b_pct:.1f}%)"
            )
            eta_m = details["eta_sec"] // 60
            eta_s = details["eta_sec"] % 60
            self.eta_lbl.config(text=f"ETA: {eta_m:02d}:{eta_s:02d}")
        elif ev_type == "BATCH_DONE":
            self._append_log(f"🎉 Batch sent to mobile: {details['total_files']} files ({self._format_bytes(details['batch_sent'])}) in {details['duration_sec']:.2f}s (Avg: {details['avg_speed_mb']:.1f} MB/s)", ACCENT_GREEN)
            self.filename_lbl.config(text="✅ Finished sending to mobile!")
            self.send_status_lbl.config(text="Transfer complete! 🎉", fg=ACCENT_GREEN)
        elif ev_type == "DECLINED":
            self._append_log(f"⚠️ {details}", ACCENT_AMBER)
            self.filename_lbl.config(text="⚠️ Transfer was declined by receiver")
            messagebox.showinfo("TurboDrop", "The mobile device declined the transfer request.")
        elif ev_type == "ERROR":
            self._append_log(f"❌ Sender error: {details}", ACCENT_RED)

    def _log(self, message: str, color: str = TEXT_PRIMARY):
        try:
            self.gui_queue.put((self._append_log, (message, color)))
        except Exception:
            pass

    def _append_log(self, message: str, color: str):
        try:
            self.log_text.config(state=tk.NORMAL)
            timestamp = time.strftime("[%H:%M:%S] ")
            self.log_text.insert(tk.END, timestamp, ("time",))
            self.log_text.insert(tk.END, message + "\n", (color,))
            self.log_text.tag_config("time", foreground=TEXT_MUTED)
            self.log_text.tag_config(color, foreground=color)

            line_count = int(self.log_text.index('end-1c').split('.')[0])
            if line_count > 1000:
                self.log_text.delete("1.0", "150.0")

            self.log_text.see(tk.END)
            self.log_text.config(state=tk.DISABLED)
        except Exception:
            pass

    def _start_servers(self):
        self.engine.start(wifi_port=5050, bt_channel=5)

        main_ip = get_local_ips()[0]
        self.beacon = DiscoveryBeacon(socket.gethostname(), main_ip, 5050, "2C:33:58:60:72:36")
        self.beacon.start()

        if getattr(sys, 'frozen', False):
            base_dir = os.path.dirname(sys.executable)
            bundled_apk = os.path.join(getattr(sys, '_MEIPASS', base_dir), "TurboDrop.apk")
            apk_loc = bundled_apk if os.path.exists(bundled_apk) else os.path.join(base_dir, "TurboDrop.apk")
        else:
            apk_loc = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "TurboDrop.apk"))
        self.web_server = WebCompanionServer(
            host="0.0.0.0", port=8080, pc_ip=main_ip, pc_bt="2C:33:58:60:72:36",
            save_dir=self.save_dir, apk_path=apk_loc,
            log_callback=lambda msg: self._log(msg, "#64B5F6")
        )
        self.web_server.start()

    def _handle_engine_event(self, ev_type: str, details):
        try:
            self.gui_queue.put((self._dispatch_engine_event, (ev_type, details)))
        except Exception as e:
            log_error(f"Error enqueuing engine event {ev_type}", exc=e)

    def _dispatch_engine_event(self, ev_type: str, details):
        try:
            if ev_type == "LOG":
                self._append_log(details, TEXT_PRIMARY)
            elif ev_type == "CLIENT_IP":
                if details and not details.startswith("127."):
                    self.phone_ip_var.set(details)
                    self.send_status_lbl.config(text=f"Phone connected ({details})", fg=ACCENT_GREEN)
            elif ev_type == "FAILOVER":
                self._append_log(details, ACCENT_AMBER)
            elif ev_type == "FILE_START":
                txt = f"[{details['file_index']}/{details['total_files']}] {details['relative_path']}"
                self.filename_lbl.config(text=txt)
            elif ev_type == "FILE_DONE":
                f_idx = details['file_index']
                t_files = details['total_files']
                rel = details['relative_path']
                sec = details['duration']
                self._append_log(f"✅ [{f_idx}/{t_files}] Saved: {rel} ({sec:.2f}s)", ACCENT_GREEN)
            elif ev_type == "BATCH_COMPLETE":
                self._append_log("🎉 Entire Batch Completed successfully!", ACCENT_CYAN)
            elif ev_type == "CLIPBOARD":
                try:
                    self.root.clipboard_clear()
                    self.root.clipboard_append(details)
                    preview = details[:35] + ("..." if len(details) > 35 else "")
                    self._append_log(f"📋 Clipboard synced from phone: '{preview}'", ACCENT_CYAN)
                except Exception as clip_err:
                    log_warn(f"Clipboard sync notice: {clip_err}")
            elif ev_type == "PROGRESS":
                self._update_ui_progress(details)
        except Exception as e:
            log_error(f"Error dispatching engine event {ev_type}", exc=e)

    def _update_ui_progress(self, prog: dict):
        try:
            self.file_progress_var.set(prog['file_percent'])
            self.batch_progress_var.set(prog['batch_percent'])

            speed_txt = f"{prog['total_speed_mb']:.1f} MB/s"
            self.speed_gauge_lbl.config(text=speed_txt)

            self.prog_details_lbl.config(
                text=f"[{prog['file_index']}/{prog['total_files']}] {self._format_bytes(prog['batch_bytes'])} / {self._format_bytes(prog['batch_total'])} ({prog['batch_percent']:.1f}%)"
            )
            eta_m = prog["eta_sec"] // 60
            eta_s = prog["eta_sec"] % 60
            self.eta_lbl.config(text=f"ETA: {eta_m:02d}:{eta_s:02d}")

            wifi_stat = "Active ⚡" if prog["wifi_active"] else "Standby"
            self.wifi_stat_lbl.config(text=f"Speed: {prog['wifi_speed_mb']:.1f} MB/s • Status: {wifi_stat}")

            bt_stat = "Active ᛒ" if prog["bt_active"] else "Standby"
            self.bt_stat_lbl.config(text=f"Speed: {prog['bt_speed_kb']:.1f} KB/s • Status: {bt_stat}")
        except Exception:
            pass

    @staticmethod
    def _format_bytes(num_bytes: int) -> str:
        for unit in ["B", "KB", "MB", "GB"]:
            if num_bytes < 1024.0:
                return f"{num_bytes:.1f} {unit}"
            num_bytes /= 1024.0
        return f"{num_bytes:.2f} TB"


def main():
    root = tk.Tk()
    app = TurboDropGUI(root)
    root.mainloop()


if __name__ == "__main__":
    main()
