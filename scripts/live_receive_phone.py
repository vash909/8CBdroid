#!/usr/bin/env python3
"""Finite physical PC-speaker to Android-microphone qualification of live CB decoding."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.tooling'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--screen', choices=['tools', 'operate'], default='tools')
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True)
    env = dict(os.environ, ADB_LIBUSB='1')

    def adb(*args, check=True):
        return subprocess.run(['adb', *args], env=env, capture_output=True, check=check, timeout=10).stdout

    def interrupted(*_):
        raise InterruptedError('Live phone check interrupted')

    signal.signal(signal.SIGTERM, interrupted)
    manifest = json.loads((ROOT / 'reference/wsjtcb/recordings/recordings.json').read_text())
    waves = {}
    for label, name in [('cq', '../channel/cq.wav'), ('directed', '../channel/leading-hash.wav')]:
        case = next(c for c in manifest['cases'] if c['file'] == name)
        path = (ROOT / 'reference/wsjtcb/recordings' / name).resolve()
        assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
        waves[label] = path
    synced = subprocess.run(['timedatectl', 'show', '-p', 'NTPSynchronized', '--value'],
                            check=True, capture_output=True, text=True, timeout=5).stdout.strip()
    assert synced == 'yes', 'The PC clock must be synchronized before scheduling desktop audio'
    original = adb('shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in').decode().strip()
    instrument = None
    players = []
    schedule = []
    deadline = time.monotonic() + 165
    log = OUT / 'live-phone-output.txt'
    try:
        adb('shell', 'svc', 'power', 'stayon', 'true')
        adb('shell', 'run-as', 'net.vash.ft8cbdroid', 'rm', '-f', 'cache/r12-ready.json')
        with log.open('w') as output:
            instrument = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '--user', '0', '-w', '-r',
                '-e', 'scenario', 'operate-live' if args.screen == 'operate' else 'live', 'net.vash.ft8cbdroid.test/net.vash.ft8cbdroid.validation.WaveformPhoneChecks'],
                env=env, stdout=output, stderr=subprocess.STDOUT)
            stages = set()
            plays = []
            while instrument.poll() is None:
                assert time.monotonic() < deadline, 'Live physical check exceeded its deadline'
                ready = {}
                if not plays or plays[0][0] - time.time() > 1:
                    try:
                        ready = json.loads(adb('exec-out', 'run-as', 'net.vash.ft8cbdroid', 'cat',
                                               'cache/r12-ready.json', check=False))
                    except (ValueError, subprocess.TimeoutExpired):
                        pass
                stage = ready.get('stage')
                if stage and stage not in stages:
                    stages.add(stage)
                    # Leave at least four seconds for timestamp settling and scheduling.
                    start = (int((time.time() + 4) // 15) + 1) * 15
                    if stage == 'first-run':
                        plays.extend([(start, 'cq'), (start + 15, 'directed')])
                    elif stage == 'cold-restart':
                        plays.append((start, 'directed'))
                    else:
                        raise AssertionError('Unknown device check stage')
                    print('Phone ready:', stage, '; scheduled', len(plays), 'desktop slots', flush=True)
                for player in players:
                    if player.poll() is not None:
                        assert player.returncode == 0, 'Desktop audio playback failed'
                if plays and time.time() >= plays[0][0]:
                    target, label = plays.pop(0)
                    assert time.time() - target < .2, 'Missed desktop audio deadline'
                    # At most three players; a preceding file may still be draining its silent tail.
                    players.append(subprocess.Popen(['pw-play', '--volume', '0.5', str(waves[label])],
                                                    stdout=subprocess.DEVNULL, stderr=subprocess.PIPE))
                    schedule.append(dict(fixture=label, target_utc=target, launched_utc=time.time()))
                    print('Playing desktop', label, 'slot', flush=True)
                time.sleep(.05 if plays and plays[0][0] - time.time() < 1 else .3)
            assert instrument.returncode == 0, log.read_text()
        text = log.read_text()
        print(text)
        assert 'live_result=PASS' in text and 'INSTRUMENTATION_CODE: -1' in text, 'Live device checks did not pass'
        report = json.loads(adb('exec-out', 'run-as', 'net.vash.ft8cbdroid', 'cat', 'cache/r12-live-report.json'))
        line = next(line for line in text.splitlines() if line.startswith('INSTRUMENTATION_RESULT: live_report='))
        assert json.loads(line.split('=', 1)[1]) == report
        assert report['result'] == 'PASS' and len(schedule) == 3
        (OUT / 'live-phone-report.json').write_text(json.dumps(dict(device=report, desktop_schedule=schedule), indent=2))
        print('Verified physical desktop-to-phone live reception and cold session restart.')
    finally:
        for player in players:
            if player.poll() is None:
                player.terminate()
                try:
                    player.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    player.kill()
        adb('shell', 'am', 'force-stop', '--user', '0', 'net.vash.ft8cbdroid', check=False)
        if instrument is not None and instrument.poll() is None:
            instrument.terminate()
            try:
                instrument.wait(timeout=3)
            except subprocess.TimeoutExpired:
                instrument.kill()
        if original == 'null':
            adb('shell', 'settings', 'delete', 'global', 'stay_on_while_plugged_in')
        else:
            adb('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', original)


if __name__ == '__main__':
    main()
