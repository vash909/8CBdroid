#!/usr/bin/env python3
"""Install an isolated Linux x86_64 JDK 17 + Android SDK in .tooling/."""
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / ".tooling"
CACHE = TOOLS / "downloads"


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "8CBdroid-bootstrap"})
    return urllib.request.urlopen(request, timeout=120)


def download(url, name, checksum, algorithm="sha256"):
    target = CACHE / name
    if not target.exists():
        print(f"Download: {name}", flush=True)
        with fetch(url) as source, target.open("wb") as dest:
            shutil.copyfileobj(source, dest)
    digest = hashlib.new(algorithm, target.read_bytes()).hexdigest()
    if digest.lower() != checksum.lower():
        target.unlink()
        raise RuntimeError(f"Checksum mismatch: {name}; retry bootstrap")
    return target


def main():
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "AMD64"):
        raise SystemExit("Automatic bootstrap supports Linux x86_64. Other systems: docs/DEVELOPMENT.md")
    CACHE.mkdir(parents=True, exist_ok=True)
    java = TOOLS / "jdk-17"
    if not (java / "bin/java").exists():
        assets = json.load(fetch(
            "https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=linux"
        ))
        package = assets[0]["binary"]["package"]
        archive = download(package["link"], package["name"], package["checksum"])
        staging = TOOLS / "jdk-extract"
        staging.mkdir(exist_ok=True)
        with tarfile.open(archive) as bundle:
            bundle.extractall(staging, filter="data")
        shutil.move(str(next(staging.iterdir())), java)
        staging.rmdir()
        (TOOLS / "jdk-source.json").write_text(json.dumps(assets[0], indent=2) + "\n")

    sdk = TOOLS / "android-sdk"
    manager = sdk / "cmdline-tools/latest/bin/sdkmanager"
    if not manager.exists():
        xml = ET.parse(fetch("https://dl.google.com/android/repository/repository2-1.xml"))
        package = next(p for p in xml.getroot() if p.get("path") == "cmdline-tools;latest")
        archive = next(a for a in package.findall("./archives/archive") if a.findtext("host-os") == "linux")
        complete = archive.find("complete")
        checksum = complete.find("checksum")
        url = "https://dl.google.com/android/repository/" + complete.findtext("url")
        bundle = download(url, "commandline-tools-linux.zip", checksum.text, checksum.get("type", "sha1"))
        staging = sdk / "cmdline-tools/extract"
        staging.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(bundle) as zipped:
            zipped.extractall(staging)
        shutil.move(str(staging / "cmdline-tools"), sdk / "cmdline-tools/latest")
        staging.rmdir()
        for executable in manager.parent.iterdir():
            executable.chmod(executable.stat().st_mode | 0o111)
        (TOOLS / "sdk-source.json").write_text(json.dumps({"url": url, "checksum": checksum.text}) + "\n")

    env = dict(os.environ, JAVA_HOME=str(java), ANDROID_HOME=str(sdk))
    print("Android SDK licenses: read and accept when prompted.", flush=True)
    subprocess.run([str(manager), f"--sdk_root={sdk}", "--licenses"], env=env, check=True)
    subprocess.run([str(manager), f"--sdk_root={sdk}", "platform-tools", "platforms;android-36", "build-tools;35.0.0"], env=env, check=True)
    (ROOT / "local.properties").write_text(f"sdk.dir={sdk}\n")
    print("Ready. Run: source scripts/env.sh && ./gradlew assembleDebug", flush=True)


if __name__ == "__main__":
    main()
