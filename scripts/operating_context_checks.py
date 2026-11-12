#!/usr/bin/env python3
"""Check country/filter UI with three real desktop-to-phone acoustic receptions.

Requires matching app/device-check APKs, an unlocked authorized phone with
station 1AT106 and the Phone profile, working saved NTP, synchronized Linux time,
physical PC speakers and the desktop reference compiler environment. Output is
private test evidence; this does not transmit on a radio or establish RF results.
"""
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

import wsjtcb_channel as channel
import wsjtcb_packing as packing
import wsjtcb_lan_probe as lan

PACKAGE = 'net.vash.ft8cbdroid'
TEXTS = ['CQ 999ZZ999', '1AT106 -07', 'CQ 26AT101']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args(); output = args.output.resolve()
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    assert subprocess.check_output(['timedatectl', 'show', '-p', 'NTPSynchronized', '--value'],
                                   text=True, timeout=5).strip() == 'yes'
    sink = subprocess.check_output(['wpctl', 'inspect', '@DEFAULT_AUDIO_SINK@'], text=True, timeout=5)
    assert 'alsa_output.' in sink, 'Select physical PC speakers.'
    reference = output/'reference'; reference.mkdir()
    encoder, manifest = channel.compile_reference(reference)
    packer = packing.compile_reference(reference, manifest, 'O0')
    waves = []
    for index, text in enumerate(TEXTS):
        packed, = packing.run(packer, ['P|'+text])
        bits = packed.split('|')[2]
        unpacked, = packing.run(packer, ['C|', 'D|'+bits])
        ok, decoded = unpacked.split('|', 1)
        assert ok == '1' and ' '.join(decoded.split()) == text, 'Lossy desktop input'
        path = reference/f'input-{index}.wav'
        path.write_bytes(channel.make_wave(encoder, bits, reference))
        waves.append(path)
    env = dict(os.environ, ADB_LIBUSB='1'); run = uuid.uuid4().hex
    def adb(*args, check=True):
        return subprocess.run(['adb', *args], env=env, check=check, capture_output=True, timeout=12).stdout
    def read(name):
        try: return json.loads(adb('exec-out', 'run-as', PACKAGE, 'cat', 'cache/'+name, check=False))
        except ValueError: return {}
    def interrupt(*_): raise KeyboardInterrupt('Acoustic context check interrupted')
    signal.signal(signal.SIGTERM, interrupt)
    address = adb('shell', 'ip', '-4', 'addr', 'show', 'wlan0').decode()
    phone_ip = re.search(r'inet ([0-9.]+)/', address).group(1)
    desktop = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    desktop.bind(('0.0.0.0', 0)); desktop.settimeout(3)
    process = player = None; plays = []; next_slot = None; lan_sent = False
    try:
        with (output/'instrumentation.log').open('w') as log, (output/'audio.log').open('w') as audio:
            process = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '--user', '0', '-w', '-r',
                '-e', 'scenario', 'operating-context', '-e', 'preview_run', run,
                PACKAGE+'.test/'+PACKAGE+'.validation.WaveformPhoneChecks'], env=env,
                stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic()+240
            while process.poll() is None:
                assert time.monotonic() < deadline, 'Phone check exceeded 240 seconds'
                if next_slot is None or len(plays) == len(waves):
                    ready = read('context-ready.json')
                    if next_slot is None and ready.get('run') == run and ready.get('stage') == 'audio':
                        next_slot = (int((time.time()+4)//15)+1)*15
                    if ready.get('run') == run and ready.get('stage') == 'lan' and not lan_sent:
                        destination = (phone_ip, ready['port']); instance = '8CBdroid-context-check'
                        desktop.sendto(lan.header(2, 0, instance)+struct.pack('>I', 3)+lan.utf8('context-test')+lan.utf8('1'), destination)
                        reply, source = desktop.recvfrom(65507)
                        magic, schema, kind = struct.unpack('>III', reply[:12])
                        assert source == destination and magic == lan.MAGIC and schema in (2, 3) and kind == 0
                        desktop.sendto(lan.status(schema, instance), destination)
                        for index, text in enumerate(['CQ 26AT101', '1AT106 -07', 'CQ 999ZZ999']):
                            sample = (1000+index*15000, -12, .2, 1000, text, index == 1, index == 1)
                            desktop.sendto(lan.decode(schema, instance, sample, index != 1), destination)
                        lan_sent = True
                        print('Actual UDP simulation: three live/replay rows sent.', flush=True)
                if player is not None and player.poll() is not None:
                    assert player.returncode == 0; player = None
                if next_slot is not None and len(plays) < len(waves) and time.time() >= next_slot:
                    assert time.time()-next_slot < .2 and player is None
                    index = len(plays)
                    player = subprocess.Popen(['pw-play', '--volume', '0.5', str(waves[index])],
                                              stdout=audio, stderr=subprocess.STDOUT)
                    plays.append(dict(text=TEXTS[index], utc_millis=round(next_slot*1000), launched_utc=time.time()))
                    next_slot += 15
                    print('Desktop acoustic input:', TEXTS[index], flush=True)
                time.sleep(.02 if next_slot is not None and next_slot-time.time() < .6 else .15)
        text = (output/'instrumentation.log').read_text()
        report = read('context-report.json')
        report['desktop_plays'] = plays
        (output/'report.json').write_text(json.dumps(report, indent=2))
        assert process.returncode == 0 and 'context_result=PASS' in text and 'INSTRUMENTATION_CODE: -1' in text, text
        assert report['run'] == run and report['result'] == 'PASS' and len(plays) == 3 and lan_sent
        print('Real acoustic country/filter UI, peer selection, recreation and recovery PASS.', flush=True)
    finally:
        desktop.close()
        if player is not None and player.poll() is None: player.terminate(); player.wait(timeout=3)
        if process is not None and process.poll() is None:
            adb('shell', 'am', 'force-stop', '--user', '0', PACKAGE, check=False)
            process.terminate(); process.wait(timeout=4)
        for path in output.rglob('*'):
            if path.is_file() and not os.access(path, os.X_OK): path.chmod(0o600)


if __name__ == '__main__': main()
