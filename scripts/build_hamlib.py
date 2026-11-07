#!/usr/bin/env python3
"""Build the pinned Hamlib C library from verified source, without host USB access."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PIN = ROOT / 'radio/hamlib/source.json'


def build(output, abi, ndk, jobs):
    pin = json.loads(PIN.read_text())
    for patch in pin['patches']:
        path = PIN.parent / patch['file']
        if hashlib.sha256(path.read_bytes()).hexdigest() != patch['sha256']:
            raise ValueError('Hamlib patch checksum mismatch')
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, LC_ALL='C', TZ='UTC', SOURCE_DATE_EPOCH='1782000000')
    cflags = '-O2 -g0 -fPIC'
    options = ['--disable-shared', '--enable-static', '--without-libusb',
               '--without-readline', '--without-indi', '--without-xml-support',
               '--without-cxx-binding', '--without-perl-binding', '--without-python-binding',
               '--without-tcl-binding', '--without-lua-binding', '--disable-winradio']
    # Android sensor rotators are unrelated to the radio catalog and require
    # host-provided sensor callbacks. They are excluded, not stubbed as working.
    env['ac_cv_header_android_sensor_h'] = 'no'
    if abi != 'host':
        # Android USB permission/routing replaces desktop /dev glob discovery.
        # glob() is also unavailable before API 28; keep upstream's no-glob path.
        env['ac_cv_header_glob_h'] = 'no'
        if not ndk:
            raise ValueError('--ndk is required for an Android build')
        hosts = {'Linux': 'linux-x86_64', 'Darwin': 'darwin-x86_64'}
        toolchain = ndk.resolve() / 'toolchains/llvm/prebuilt' / hosts[platform.system()]
        target = {'arm64-v8a': 'aarch64-linux-android', 'x86_64': 'x86_64-linux-android'}[abi]
        env.update(CC=str(toolchain / 'bin' / (target + '26-clang')),
                   CXX=str(toolchain / 'bin' / (target + '26-clang++')),
                   AR=str(toolchain / 'bin/llvm-ar'), RANLIB=str(toolchain / 'bin/llvm-ranlib'),
                   STRIP=str(toolchain / 'bin/llvm-strip'))
        options += ['--host=' + target]
        compiler = subprocess.check_output([env['CC'], '--version'], text=True, timeout=10)
    else:
        env.update(CC='cc', CXX='c++')
        compiler = subprocess.check_output(['cc', '--version'], text=True, timeout=10)
    env.update(CFLAGS=cflags, CXXFLAGS=cflags)
    fingerprint = hashlib.sha256(json.dumps({
        'pin': pin, 'abi': abi, 'compiler': compiler, 'options': options,
        'script': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    }, sort_keys=True).encode()).hexdigest()
    stamp = output / 'build-stamp.json'
    library = output / 'lib/libhamlib.a'
    headers_present = all((output / 'include/hamlib' / name).is_file() for name in ['rig.h', 'config.h'])
    if stamp.exists() and library.exists() and headers_present:
        record = json.loads(stamp.read_text())
        if record.get('fingerprint') == fingerprint and record.get('library_sha256') == hashlib.sha256(library.read_bytes()).hexdigest():
            print('Hamlib', pin['version'], abi, 'is current.', flush=True)
            return
    archive = output / ('hamlib-' + pin['version'] + '.tar.gz')
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != pin['sha256']:
        request = urllib.request.Request(pin['url'], headers={'User-Agent': '8CBdroid-source-build'})
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read(16 * 1024 * 1024 + 1)
        if len(data) > 16 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != pin['sha256']:
            raise ValueError('Hamlib source archive checksum/size mismatch')
        archive.write_bytes(data)
    source = output / 'source'
    work = output / 'work'
    # Only regenerate this build's disposable directories; no user data lives here.
    for folder in (source, work):
        if folder.exists():
            shutil.rmtree(folder)
        folder.mkdir()
    with tarfile.open(archive, 'r:gz') as tar:
        for member in tar.getmembers():
            name = Path(member.name)
            if name.is_absolute() or '..' in name.parts or not (member.isfile() or member.isdir()):
                raise ValueError('Unexpected Hamlib archive member')
        tar.extractall(source, filter='data')
    source = source / ('hamlib-' + pin['version'])
    for patch in pin['patches']:
        path = PIN.parent / patch['file']
        if hashlib.sha256(path.read_bytes()).hexdigest() != patch['sha256']:
            raise ValueError('Hamlib patch checksum mismatch')
        subprocess.run(['patch', '--batch', '-p1', '-i', str(path)], cwd=source,
                       check=True, timeout=10)
    print('Configure Hamlib', pin['version'], abi, flush=True)
    with (output / 'configure.log').open('w') as log:
        subprocess.run([str(source / 'configure'), *options], cwd=work, env=env,
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
    print('Compile Hamlib', abi, 'with', jobs, 'jobs', flush=True)
    with (output / 'make.log').open('w') as log:
        subprocess.run(['make', '-C', 'src', '-j' + str(jobs), 'libhamlib.la'], cwd=work,
                       env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=600)
    library.parent.mkdir(exist_ok=True)
    shutil.copy2(work / 'src/.libs/libhamlib.a', library)
    include = output / 'include/hamlib'
    include.parent.mkdir(exist_ok=True)
    shutil.copytree(source / 'include/hamlib', include, dirs_exist_ok=True)
    shutil.copy2(work / 'include/hamlib/config.h', include / 'config.h')
    stamp.write_text(json.dumps({'fingerprint': fingerprint, 'version': pin['version'], 'abi': abi,
                               'library_sha256': hashlib.sha256(library.read_bytes()).hexdigest()}, indent=2))
    print('Built pinned Hamlib', pin['version'], abi, flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--abi', choices=['host', 'arm64-v8a', 'x86_64'], required=True)
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--jobs', type=int, default=2)
    args = parser.parse_args()
    if not 1 <= args.jobs <= 4:
        parser.error('--jobs must be between 1 and 4')
    build(args.output, args.abi, args.ndk, args.jobs)
