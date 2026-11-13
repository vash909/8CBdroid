#!/usr/bin/env python3
"""Bounded real-phone continuous audio/LAN reception check. Never starts TX."""
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
TEXTS = {'local-navigation': 'CQ 26AT101', 'local-background': 'CQ 13AT101', 'local-screen-off': 'CQ 14AT101'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, mode=0o700)
    sink = subprocess.check_output(['wpctl', 'inspect', '@DEFAULT_AUDIO_SINK@'], text=True, timeout=5)
    assert 'alsa_output.' in sink, 'Select physical PC speakers.'
    reference = output/'reference'; reference.mkdir()
    encoder, manifest = channel.compile_reference(reference)
    packer = packing.compile_reference(reference, manifest, 'O0')
    waves = {}
    for stage, text in TEXTS.items():
        packed, = packing.run(packer, ['P|'+text])
        bits = packed.split('|')[2]
        unpacked, = packing.run(packer, ['C|', 'D|'+bits])
        ok, decoded = unpacked.split('|', 1)
        assert ok == '1' and ' '.join(decoded.split()) == text
        path = reference/(stage+'.wav')
        path.write_bytes(channel.make_wave(encoder, bits, reference))
        waves[stage] = path
    env = dict(os.environ, ADB_LIBUSB='1'); run = uuid.uuid4().hex
    def adb(*args, check=True):
        return subprocess.run(['adb', *args], env=env, check=check, capture_output=True, timeout=12).stdout
    def read(name):
        try: return json.loads(adb('exec-out', 'run-as', PACKAGE, 'cat', 'cache/'+name, check=False))
        except ValueError: return {}
    def write(name, data):
        # stdin is data, never shell-interpolated text.
        subprocess.run(['adb', 'shell', 'run-as', PACKAGE, 'sh', '-c', '"cat > cache/'+name+'"'],
                       input=json.dumps(data).encode(), env=env, capture_output=True, check=True, timeout=10)
    def interrupt(*_): raise KeyboardInterrupt('Receive check interrupted')
    signal.signal(signal.SIGTERM, interrupt)
    address = adb('shell', 'ip', '-4', 'addr', 'show', 'wlan0').decode()
    phone_ip = re.search(r'inet ([0-9.]+)/', address).group(1)
    desktop = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    desktop.bind(('0.0.0.0', 0)); desktop.settimeout(.2)
    process = player = None; plans = {}; plays = []; stages = set(); last_lan = 0; permission_changed = False
    op = adb('shell', 'cmd', 'appops', 'get', PACKAGE, 'RECORD_AUDIO').decode()
    original_mode = re.search(r'RECORD_AUDIO: (allow|default|foreground)', op)
    assert original_mode, 'Expected an authorized debug microphone; no app-op was changed'
    original_mode = original_mode.group(1)
    uid_op = ['--uid'] if re.search(r'Uid mode:.*RECORD_AUDIO', op) else []
    try:
        with (output/'instrumentation.log').open('w') as log, (output/'audio.log').open('w') as audio:
            process = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '--user', '0', '-w', '-r',
                '-e', 'scenario', 'receive-session', '-e', 'preview_run', run,
                PACKAGE+'.test/'+PACKAGE+'.validation.WaveformPhoneChecks'], env=env, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic()+900
            while process.poll() is None:
                assert time.monotonic() < deadline, 'Phone checks exceeded 900 seconds'
                ready = read('receive-ready.json')
                stage = ready.get('stage') if ready.get('run') == run else None
                if stage and stage not in stages:
                    stages.add(stage); print('Receive check stage:', stage, flush=True)
                    if stage == 'unlock': print('OWNER_ACTION_REQUIRED: unlock the debug phone; host test waits up to 180 seconds.', flush=True)
                if stage == 'microphone-interruption' and not permission_changed:
                    adb('shell', 'cmd', 'appops', 'set', *uid_op, PACKAGE, 'RECORD_AUDIO', 'ignore'); permission_changed = True
                if stage == 'microphone-restore' and permission_changed:
                    adb('shell', 'cmd', 'appops', 'set', *uid_op, PACKAGE, 'RECORD_AUDIO', original_mode); permission_changed = False
                    write('receive-input.json', dict(run=run, stage=stage, utc_millis=round(time.time()*1000)))
                if stage in waves and stage not in plans:
                    plans[stage] = (int((time.time()+4)//15)+1)*15
                    write('receive-input.json', dict(run=run, stage=stage, utc_millis=plans[stage]*1000))
                if stage in waves and stage not in {p['stage'] for p in plays} and time.time() >= plans[stage]:
                    assert player is None and time.time()-plans[stage] < .25, 'Missed/overlapping playback'
                    player = subprocess.Popen(['pw-play', '--volume', '0.5', str(waves[stage])], stdout=audio, stderr=subprocess.STDOUT)
                    plays.append(dict(stage=stage, text=TEXTS[stage], utc_millis=plans[stage]*1000, launched_utc=time.time()))
                    (output/'desktop-plays.json').write_text(json.dumps(plays, indent=2))
                    print('Actual desktop speaker input:', TEXTS[stage], flush=True)
                if player is not None and player.poll() is not None:
                    assert player.returncode == 0; player = None
                if stage in ('lan-navigation', 'lan-background', 'lan-screen-off') and time.monotonic()-last_lan >= 1:
                    last_lan = time.monotonic()
                    destination = (phone_ip, ready['port']); instance = '8CBdroid-receive-check'
                    desktop.sendto(lan.header(2, 0, instance)+struct.pack('>I', 3)+lan.utf8('receive-test')+lan.utf8('1'), destination)
                    desktop.sendto(lan.status(2, instance), destination)
                    index = ('lan-navigation', 'lan-background', 'lan-screen-off').index(stage)
                    sample = (1000+index*15000, -12, .2, 1000, ['CQ 26AT101', 'CQ 13AT101', 'CQ 14AT101'][index], False, False)
                    desktop.sendto(lan.decode(2, instance, sample, True), destination)
                    try:
                        reply, source = desktop.recvfrom(65507)
                        assert source == destination and struct.unpack('>I', reply[:4])[0] == lan.MAGIC
                    except socket.timeout: pass
                next_time = plans.get(stage)
                time.sleep(.02 if next_time and 0 < next_time-time.time() < .8 else .1)
        text = (output/'instrumentation.log').read_text()
        report = read('receive-report.json'); report['desktop_plays'] = plays
        (output/'report.json').write_text(json.dumps(report, indent=2))
        assert process.returncode == 0 and 'receive_result=PASS' in text and 'INSTRUMENTATION_CODE: -1' in text, text
        assert report['run'] == run and report['result'] == 'PASS' and len(plays) == 3
        print('Real continuous microphone/LAN reception and Stop PASS.', flush=True)
    finally:
        desktop.close()
        if permission_changed: adb('shell', 'cmd', 'appops', 'set', *uid_op, PACKAGE, 'RECORD_AUDIO', original_mode, check=False)
        if player is not None and player.poll() is None: player.terminate(); player.wait(timeout=3)
        if process is not None and process.poll() is None:
            adb('shell', 'am', 'force-stop', '--user', '0', PACKAGE, check=False)
            process.terminate(); process.wait(timeout=4)
        for path in output.rglob('*'):
            if path.is_file() and not os.access(path, os.X_OK): path.chmod(0o600)


if __name__ == '__main__': main()
