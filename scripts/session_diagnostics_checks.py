#!/usr/bin/env python3
"""Bounded real-device diagnostic UI, sustained microphone or LAN checks; never transmits."""
import argparse
import json
import os
from pathlib import Path
import re
import signal
import socket
import struct
import subprocess
import time
import uuid

PACKAGE = 'net.vash.ft8cbdroid'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mode', choices=['smoke', 'sustained', 'lan', 'interrupted'], default='smoke')
    parser.add_argument('--radio', action='store_true', help='Listen to actual radio-speaker FT8 instead of playing PC reference audio')
    args = parser.parse_args()
    if args.radio and args.mode != 'sustained':
        parser.error('--radio requires --mode sustained')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    env = dict(os.environ, ADB_LIBUSB='1')
    # Resolve once; never silently switch devices when another phone is connected.
    serial = subprocess.check_output(['adb', 'get-serialno'], env=env, timeout=10).decode().strip()
    assert serial and serial != 'unknown', 'Select one authorized phone with ANDROID_SERIAL'
    env['ANDROID_SERIAL'] = serial
    run = uuid.uuid4().hex

    def adb(*argv, check=True):
        return subprocess.run(['adb', *argv], env=env, capture_output=True, check=check, timeout=15).stdout

    def read(name):
        try:
            return json.loads(adb('exec-out', 'run-as', PACKAGE, 'cat', 'cache/'+name, check=False))
        except ValueError:
            return {}

    wave = None
    if args.mode == 'sustained' and not args.radio:
        import wsjtcb_channel as channel
        import wsjtcb_packing as packing
        sink = subprocess.check_output(['wpctl', 'inspect', '@DEFAULT_AUDIO_SINK@'], text=True, timeout=5)
        assert 'alsa_output.' in sink, 'Select physical speakers, not a virtual sink'
        reference = output/'reference'; reference.mkdir()
        encoder, manifest = channel.compile_reference(reference)
        packer = packing.compile_reference(reference, manifest, 'O0')
        packed, = packing.run(packer, ['P|CQ 26AT101'])
        wave = reference/'input.wav'; wave.write_bytes(channel.make_wave(encoder, packed.split('|')[2], reference))

    process = player = None
    desktop = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    desktop.bind(('0.0.0.0', 0))
    plays = []; last_lan = 0; next_slot = None; cached_ready = None; last_ready_read = 0; last_progress = 0
    phone_ip = None
    if args.mode == 'lan':
        address = adb('shell', 'ip', '-4', 'addr', 'show', 'wlan0').decode()
        match = re.search(r'inet ([0-9.]+)/', address)
        assert match, 'Phone needs Wi-Fi on the host LAN'
        phone_ip = match.group(1)
        import wsjtcb_lan_probe as lan

    def interrupt(*_):
        raise KeyboardInterrupt('Diagnostic checks interrupted')
    signal.signal(signal.SIGTERM, interrupt)
    try:
        scenarios = ['diagnostics-interrupt', 'diagnostics-cold'] if args.mode == 'interrupted' else ['diagnostics-'+args.mode]
        for scenario in scenarios:
            if scenario == 'diagnostics-cold':
                adb('shell', 'am', 'force-stop', '--user', '0', PACKAGE)
            log_path = output/(scenario+'.log')
            with log_path.open('w') as log, (output/'playback.log').open('a') as audio:
                process = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '--user', '0', '-w', '-r',
                    '-e', 'scenario', scenario, '-e', 'preview_run', run,
                    '-e', 'radio_input', str(args.radio).lower(),
                    PACKAGE+'.test/'+PACKAGE+'.validation.WaveformPhoneChecks'], env=env, stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + (1050 if args.mode == 'sustained' else 180)
                while process.poll() is None:
                    assert time.monotonic() < deadline, 'Finite phone-check deadline exceeded'
                    if cached_ready is None and time.monotonic()-last_ready_read >= 1:
                        last_ready_read = time.monotonic()
                        ready = read('diagnostics-ready.json')
                        if ready.get('run') == run:
                            cached_ready = ready
                    ready = cached_ready or {}
                    stage = ready.get('stage')
                    if time.monotonic()-last_progress >= 30:
                        last_progress = time.monotonic()
                        print('Diagnostic check active:', scenario, 'stage:', stage or 'setup', 'physical plays:', len(plays), flush=True)
                    if stage == 'microphone' and wave is not None:
                        if next_slot is None:
                            next_slot = (int((time.time()+4)//15)+1)*15
                        if time.time() >= next_slot:
                            assert player is None and time.time()-next_slot < .25, 'Missed or overlapping physical playback'
                            player = subprocess.Popen(['pw-play', '--volume', '0.5', str(wave)], stdout=audio, stderr=subprocess.STDOUT)
                            plays.append(dict(utc_millis=next_slot*1000, launched_utc=time.time()))
                            next_slot += 30
                    if player is not None and player.poll() is not None:
                        assert player.returncode == 0, 'Physical audio playback failed'
                        player = None
                    if stage == 'lan' and time.monotonic()-last_lan >= 1:
                        last_lan = time.monotonic(); destination = (phone_ip, ready['port']); instance = 'diagnostics-bench'
                        desktop.sendto(lan.header(2, 0, instance)+struct.pack('>I', 3)+lan.utf8('diagnostics-test')+lan.utf8('1'), destination)
                        desktop.sendto(lan.status(2, instance), destination)
                        desktop.sendto(lan.decode(2, instance, (1000, -12, .2, 1000, 'CQ 26AT101', False, False), True), destination)
                    time.sleep(.03 if next_slot and 0 < next_slot-time.time() < .8 else .2)
            report = read('diagnostics-phone-report.json')
            (output/(scenario+'-report.json')).write_text(json.dumps(report, indent=2))
            text = log_path.read_text()
            assert process.returncode == 0 and 'diagnostics_result=PASS' in text and 'INSTRUMENTATION_CODE: -1' in text, text
            assert report.get('run') == run and report.get('result') == 'PASS', report
            print(scenario, 'PASS', flush=True)
        exported = read('diagnostics-export.json')
        if args.mode != 'interrupted' or exported:
            assert exported.get('schema') == 1
            (output/'exported-report.json').write_text(json.dumps(exported, indent=2))
        (output/'desktop-plays.json').write_text(json.dumps(plays, indent=2))
        (output/'result.json').write_text(json.dumps(dict(result='PASS',mode=args.mode,radio=args.radio,run=run),indent=2))
    finally:
        desktop.close()
        if player is not None and player.poll() is None:
            player.terminate(); player.wait(timeout=4)
        if process is not None and process.poll() is None:
            adb('shell', 'am', 'force-stop', '--user', '0', PACKAGE, check=False)
            process.terminate(); process.wait(timeout=4)
        for path in output.rglob('*'):
            if path.is_file() and not os.access(path, os.X_OK):
                path.chmod(0o600)


if __name__ == '__main__':
    main()
