"""
TurboDrop Embedded Companion Web Server
Serves TurboDrop.apk to mobile phones via browser download and provides
an instant browser-based Wi-Fi file transfer portal.
"""

import os
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse

HTML_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>TurboDrop - Mobile Companion &amp; Transfer Portal</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
        body { background: #0D1117; color: #F0F6FC; padding: 28px 16px; display: flex; flex-direction: column; align-items: center; min-height: 100vh; }
        .card { background: #161B22; border: 1px solid #30363D; border-radius: 16px; padding: 24px; width: 100%; max-width: 480px; box-shadow: 0 8px 30px rgba(0,0,0,0.5); margin-bottom: 20px; }
        .logo { font-size: 28px; font-weight: bold; color: #58A6FF; text-align: center; margin-bottom: 8px; }
        .sub { font-size: 14px; color: #8B949E; text-align: center; margin-bottom: 24px; line-height: 1.5; }
        .btn-apk { display: block; background: linear-gradient(135deg, #238636, #2EA043); color: #FFFFFF; text-align: center; padding: 16px; border-radius: 12px; font-weight: bold; font-size: 17px; text-decoration: none; margin-bottom: 16px; transition: transform 0.2s; box-shadow: 0 4px 15px rgba(35,134,54,0.3); }
        .btn-apk:active { transform: scale(0.98); }
        .badge { background: #21262D; border-radius: 10px; padding: 12px 14px; margin-bottom: 12px; border: 1px solid #30363D; }
        .badge-title { font-size: 12px; color: #8B949E; margin-bottom: 4px; font-weight: bold; text-transform: uppercase; letter-spacing: 0.5px; }
        .badge-val { font-family: monospace; font-size: 16px; color: #3FB950; font-weight: bold; }
        .section-title { font-size: 15px; font-weight: bold; color: #58A6FF; margin: 16px 0 10px; }
        .dropzone { border: 2px dashed #30363D; border-radius: 12px; padding: 30px 16px; text-align: center; cursor: pointer; transition: border-color 0.2s; background: #0D1117; }
        .dropzone:hover { border-color: #58A6FF; }
        .progress-bar { height: 8px; background: #21262D; border-radius: 4px; overflow: hidden; margin-top: 12px; display: none; }
        .progress-fill { width: 0%; height: 100%; background: #58A6FF; transition: width 0.1s; }
        #status { margin-top: 10px; font-size: 14px; color: #8B949E; text-align: center; }
    </style>
</head>
<body>

    <div class="card">
        <div class="logo">⚡ TurboDrop</div>
        <div class="sub">Ultra-Fast Android &amp; PC File Transfer System<br>(Wi-Fi High-Speed Wire Stream &amp; Bluetooth Safety Failover)</div>

        <a href="/download-apk" class="btn-apk">📥 Download Android APK</a>

        <div class="badge">
            <div class="badge-title">📶 PC Wi-Fi Stream Address:</div>
            <div class="badge-val" id="pc-ip">__PC_IP__:5050</div>
        </div>

        <div class="badge">
            <div class="badge-title">ᛒ PC Bluetooth MAC Address:</div>
            <div class="badge-val" id="pc-bt" style="color: #58A6FF;">__PC_BT__</div>
        </div>
    </div>

    <!-- Direct Web Upload Fallback -->
    <div class="card">
        <div class="section-title">🚀 Direct Browser Upload (Instant Wi-Fi):</div>
        <div class="dropzone" onclick="document.getElementById('file-input').click()">
            <div style="font-size: 32px; margin-bottom: 8px;">📁</div>
            <div style="font-weight: bold; color: #F0F6FC;">Click to pick any file to send to PC</div>
            <div style="font-size: 12px; color: #8B949E; margin-top: 6px;">Supports 4K videos, photos, archives &amp; documents</div>
            <input type="file" id="file-input" style="display: none;" onchange="uploadFile(this.files[0])">
        </div>
        <div class="progress-bar" id="p-bar">
            <div class="progress-fill" id="p-fill"></div>
        </div>
        <div id="status"></div>
    </div>

    <script>
        function uploadFile(file) {
            if (!file) return;
            const pBar = document.getElementById('p-bar');
            const pFill = document.getElementById('p-fill');
            const status = document.getElementById('status');

            pBar.style.display = 'block';
            status.innerText = 'Streaming at wire speed...';

            const startTime = Date.now();
            const xhr = new XMLHttpRequest();
            xhr.open('POST', '/upload?filename=' + encodeURIComponent(file.name), true);

            xhr.upload.onprogress = (e) => {
                if (e.lengthComputable) {
                    const pct = ((e.loaded / e.total) * 100).toFixed(1);
                    pFill.style.width = pct + '%';
                    const elapsed = (Date.now() - startTime) / 1000;
                    const speedMb = ((e.loaded / (1024 * 1024)) / Math.max(0.1, elapsed)).toFixed(1);
                    status.innerText = pct + '% (' + speedMb + ' MB/s)';
                }
            };

            xhr.onload = () => {
                if (xhr.status === 200) {
                    pFill.style.width = '100%';
                    status.style.color = '#3FB950';
                    status.innerText = '✅ File sent successfully and saved on PC!';
                } else {
                    status.style.color = '#F85149';
                    status.innerText = '❌ Upload failed: ' + xhr.statusText;
                }
            };

            xhr.onerror = () => {
                status.style.color = '#F85149';
                status.innerText = '❌ Connection error to PC server.';
            };

            xhr.send(file);
        }
    </script>
</body>
</html>
"""

class WebCompanionServer:
    def __init__(self, host: str = "0.0.0.0", port: int = 8080, pc_ip: str = "192.168.100.2", pc_bt: str = "2C:33:58:60:72:36", save_dir: str = "", apk_path: str = "", log_callback = None):
        self.host = host
        self.port = port
        self.pc_ip = pc_ip
        self.pc_bt = pc_bt
        self.save_dir = save_dir
        self.apk_path = apk_path
        self.log_callback = log_callback
        self.httpd = None
        self.running = False

    def start(self):
        handler_cls = self._create_handler()
        try:
            self.httpd = HTTPServer((self.host, self.port), handler_cls)
            self.running = True
            t = threading.Thread(target=self.httpd.serve_forever, daemon=True, name="WebCompanionThread")
            t.start()
            if self.log_callback:
                self.log_callback(f"🌐 Companion Web Portal active at: http://{self.pc_ip}:{self.port}")
        except Exception as e:
            if self.log_callback:
                self.log_callback(f"⚠️ Web companion server port {self.port} in use: {e}")

    def stop(self):
        if self.httpd:
            self.httpd.shutdown()
            self.httpd.server_close()

    def _create_handler(self):
        parent = self

        class CompanionHandler(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass

            def do_GET(self):
                parsed = urllib.parse.urlparse(self.path)
                if parsed.path == "/" or parsed.path == "/index.html":
                    page = HTML_PAGE.replace("__PC_IP__", parent.pc_ip).replace("__PC_BT__", parent.pc_bt)
                    data = page.encode("utf-8")
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html; charset=utf-8")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)

                elif parsed.path == "/download-apk":
                    if os.path.exists(parent.apk_path):
                        filesize = os.path.getsize(parent.apk_path)
                        self.send_response(200)
                        self.send_header("Content-Type", "application/vnd.android.package-archive")
                        self.send_header("Content-Disposition", 'attachment; filename="TurboDrop.apk"')
                        self.send_header("Content-Length", str(filesize))
                        self.end_headers()
                        with open(parent.apk_path, "rb") as f:
                            while chunk := f.read(64 * 1024):
                                self.wfile.write(chunk)
                        if parent.log_callback:
                            parent.log_callback("📱 TurboDrop.apk downloaded by mobile phone!")
                    else:
                        self.send_error(404, "APK not found")
                else:
                    self.send_error(404, "Not Found")

            def do_POST(self):
                parsed = urllib.parse.urlparse(self.path)
                if parsed.path == "/upload":
                    query = urllib.parse.parse_qs(parsed.query)
                    filename = query.get("filename", ["uploaded_file.dat"])[0]
                    clean_name = os.path.basename(filename)
                    if not os.path.exists(parent.save_dir):
                        os.makedirs(parent.save_dir, exist_ok=True)
                    out_path = os.path.join(parent.save_dir, clean_name)

                    content_length = int(self.headers.get("Content-Length", 0))
                    read_bytes = 0
                    with open(out_path, "wb") as f:
                        while read_bytes < content_length:
                            chunk = self.rfile.read(min(content_length - read_bytes, 64 * 1024))
                            if not chunk:
                                break
                            f.write(chunk)
                            read_bytes += len(chunk)

                    if parent.log_callback:
                        parent.log_callback(f"📥 Received file via Web Portal: '{clean_name}' ({read_bytes / (1024*1024):.1f} MB)")

                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(b'{"status":"ok"}')
                else:
                    self.send_error(404, "Not Found")

        return CompanionHandler
