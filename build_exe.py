"""
Build Script for TurboDrop Standalone PC Executable (PyInstaller)
Generates a single, portable TurboDrop.exe with embedded icons and resources.
"""

import os
import sys
import shutil
import subprocess

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

def build():
    root_dir = os.path.dirname(os.path.abspath(__file__))
    pc_app_dir = os.path.join(root_dir, "pc_app")
    entry_point = os.path.join(pc_app_dir, "turbo_pc.py")
    icon_path = os.path.join(pc_app_dir, "app_icon.ico")
    apk_path = os.path.join(root_dir, "TurboDrop.apk")

    print("============================================================")
    print(" 🚀 BUILDING TURBODROP STANDALONE WINDOWS EXECUTABLE (.EXE)")
    print("============================================================")

    cmd = [
        sys.executable, "-m", "PyInstaller",
        "--name", "TurboDrop",
        "--noconsole",
        "--onefile",
        "--clean",
        f"--icon={icon_path}",
        f"--add-data={icon_path};.",
    ]

    if os.path.exists(apk_path):
        cmd.append(f"--add-data={apk_path};.")

    cmd.extend([
        "--hidden-import", "windnd",
        "--hidden-import", "tkinter",
        "--hidden-import", "PIL",
        entry_point
    ])

    print("Running PyInstaller command:")
    print(" ".join(cmd))
    print()

    res = subprocess.run(cmd, cwd=root_dir)
    if res.returncode != 0:
        print("❌ PyInstaller build failed!")
        sys.exit(res.returncode)

    dist_exe = os.path.join(root_dir, "dist", "TurboDrop.exe")
    target_exe = os.path.join(root_dir, "TurboDrop.exe")

    if os.path.exists(dist_exe):
        shutil.copy2(dist_exe, target_exe)
        size_mb = os.path.getsize(target_exe) / (1024 * 1024)
        print("============================================================")
        print(f" ✅ SUCCESS: TurboDrop.exe built successfully!")
        print(f" Location: {target_exe}")
        print(f" Size: {size_mb:.2f} MB")
        print("============================================================")
    else:
        print("❌ Built executable not found in dist/")
        sys.exit(1)

if __name__ == "__main__":
    build()
