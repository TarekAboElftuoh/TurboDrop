"""
TurboDrop Zero-Config Auto-Discovery Beacon
Broadcasts UDP discovery packets across the local Wi-Fi subnet so the Android phone
automatically detects PC name, Wi-Fi IP, and Bluetooth MAC with ZERO manual input.
"""

import time
import socket
import json
import threading

DISCOVERY_PORT = 5051

class DiscoveryBeacon:
    def __init__(self, pc_name: str, wifi_ip: str, wifi_port: int, bt_mac: str, web_port: int = 8080):
        self.pc_name = pc_name
        self.wifi_ip = wifi_ip
        self.wifi_port = wifi_port
        self.bt_mac = bt_mac
        self.web_port = web_port
        self.running = False
        self.sock = None

    def start(self):
        self.running = True
        t = threading.Thread(target=self._broadcast_loop, daemon=True, name="BeaconThread")
        t.start()

    def stop(self):
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass

    def _broadcast_loop(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self.sock.settimeout(0.2)

        # Build payload
        data = {
            "magic": "TURBODROP_BEACON",
            "name": self.pc_name,
            "wifi_ip": self.wifi_ip,
            "wifi_port": self.wifi_port,
            "bt_mac": self.bt_mac,
            "web_port": self.web_port
        }
        raw_msg = json.dumps(data).encode("utf-8")

        # Determine broadcast targets
        targets = ["255.255.255.255", "<broadcast>"]
        # If IP is e.g. 192.168.100.2 -> subnet broadcast is 192.168.100.255
        try:
            parts = self.wifi_ip.split(".")
            if len(parts) == 4:
                subnet_bcast = f"{parts[0]}.{parts[1]}.{parts[2]}.255"
                targets.append(subnet_bcast)
        except Exception:
            pass

        while self.running:
            for target in targets:
                try:
                    self.sock.sendto(raw_msg, (target, DISCOVERY_PORT))
                except Exception:
                    pass
            time.sleep(1.0)
