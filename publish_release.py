"""
TurboDrop Automated Release Engine
Seamlessly bumps version across PC and Android, builds packages, runs self-tests,
commits, tags, and pushes to GitHub to trigger the automated Release CI/CD workflow.
"""

import os
import sys
import json
import re
import subprocess
import argparse

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
VERSION_FILE = os.path.join(ROOT_DIR, "version.json")
MANIFEST_FILE = os.path.join(ROOT_DIR, "android_app", "AndroidManifest.xml")
TURBO_PC_FILE = os.path.join(ROOT_DIR, "pc_app", "turbo_pc.py")


def load_version():
    with open(VERSION_FILE, "r", encoding="utf-8") as f:
        return json.load(f)


def save_version(data):
    with open(VERSION_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)


def bump_semver(current: str, part: str = "patch") -> str:
    parts = [int(p) for p in current.split(".")]
    while len(parts) < 3:
        parts.append(0)
    if part == "major":
        parts[0] += 1
        parts[1] = 0
        parts[2] = 0
    elif part == "minor":
        parts[1] += 1
        parts[2] = 0
    else:  # patch
        parts[2] += 1
    return ".".join(str(p) for p in parts)


def update_android_manifest(new_version: str, new_code: int):
    with open(MANIFEST_FILE, "r", encoding="utf-8") as f:
        content = f.read()

    content = re.sub(r'android:versionCode="[0-9]+"', f'android:versionCode="{new_code}"', content)
    content = re.sub(r'android:versionName="[^"]+"', f'android:versionName="{new_version}"', content)

    with open(MANIFEST_FILE, "w", encoding="utf-8") as f:
        f.write(content)


def update_turbo_pc(new_version: str):
    with open(TURBO_PC_FILE, "r", encoding="utf-8") as f:
        content = f.read()

    content = re.sub(r'APP_VERSION = "[^"]+"', f'APP_VERSION = "{new_version}"', content)

    with open(TURBO_PC_FILE, "w", encoding="utf-8") as f:
        f.write(content)


def run_command(cmd, cwd=ROOT_DIR):
    print(f"==> Running: {cmd}")
    res = subprocess.run(cmd, shell=True, cwd=cwd)
    if res.returncode != 0:
        print(f"❌ Command failed with code {res.returncode}: {cmd}")
        sys.exit(res.returncode)


def main():
    parser = argparse.ArgumentParser(description="TurboDrop Automated Release Engine")
    parser.add_argument("message", nargs="?", default="Routine enhancements and performance optimizations",
                        help="Release description / changelog message")
    parser.add_argument("--bump", choices=["patch", "minor", "major"], default="patch",
                        help="Semver bump type (default: patch)")
    parser.add_argument("--set-version", default=None, help="Explicitly specify the new version string (e.g. 1.1.0)")
    args = parser.parse_args()

    v_data = load_version()
    old_version = v_data["version"]
    new_version = args.set_version if args.set_version else bump_semver(old_version, args.bump)
    new_build = v_data.get("build", 1) + 1

    print("=" * 60)
    print(f" 🚀 PREPARING TURBODROP RELEASE v{new_version} (Build {new_build})")
    print(f" Changelog: {args.message}")
    print("=" * 60)

    # 1. Update Version Metadata
    print("\n[Step 1/5] Updating version metadata across PC and Android...")
    v_data["version"] = new_version
    v_data["build"] = new_build
    save_version(v_data)
    update_android_manifest(new_version, new_build)
    update_turbo_pc(new_version)
    print(f"  -> Version bumped from v{old_version} to v{new_version}")

    # 2. Build Android APK
    print("\n[Step 2/5] Building Android APK (TurboDrop.apk)...")
    run_command("powershell.exe -ExecutionPolicy Bypass -File build_apk.ps1")

    # 3. Build Windows Executable
    print("\n[Step 3/5] Building Standalone PC Executable (TurboDrop.exe)...")
    run_command(f'"{sys.executable}" build_exe.py')

    # 4. Run Self-Test Suite
    print("\n[Step 4/5] Running Automated Comprehensive Self-Test Suite...")
    run_command(f'"{sys.executable}" tests/test_suite.py')

    # 5. Git Commit, Tag, and Push
    print("\n[Step 5/5] Committing changes, creating tag, and pushing to GitHub...")
    run_command("git add -A")
    commit_msg = f"Release v{new_version}: {args.message}"
    run_command(f'git commit -m "{commit_msg}"')
    run_command(f'git tag -a v{new_version} -m "TurboDrop v{new_version}: {args.message}"')
    run_command("git push origin main --tags")

    print("\n" + "=" * 60)
    print(f" 🎉 SUCCESS! TurboDrop v{new_version} released successfully!")
    print(f" GitHub Release: https://github.com/TarekAboElftuoh/TurboDrop/releases/tag/v{new_version}")
    print(f" GitHub Actions: https://github.com/TarekAboElftuoh/TurboDrop/actions")
    print("=" * 60)


if __name__ == "__main__":
    main()
