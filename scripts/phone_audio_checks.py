#!/usr/bin/env python3
"""Qualify foreground phone RX/output/recovery using physical PC speakers and microphone.

Install matching app/test/probe APKs and grant microphone permission first. Requires
an unlocked phone, NTP-synchronized Linux host, PipeWire and libpulse-simple.
No radio is used. Recordings may contain ambient audio; keep output private.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--ntp-host', default='time.cloudflare.com')
    parser.add_argument('--ntp-port', type=int, default=123)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    root = Path(__file__).resolve().parents[1]
    assert subprocess.check_output(['timedatectl', 'show', '-p', 'NTPSynchronized', '--value'], text=True, timeout=5).strip() == 'yes'
    source = subprocess.check_output(['wpctl', 'inspect', '@DEFAULT_AUDIO_SOURCE@'], text=True, timeout=5)
    source_name = re.search(r'node.name = "([^"]+)"', source).group(1)
    assert source_name.startswith('alsa_input.'), 'Select a physical microphone, not an output monitor.'
    manifest = json.loads((root/'reference/wsjtcb/recordings/recordings.json').read_text())
    case = next(c for c in manifest['cases'] if c['file'] == '../channel/cq.wav')
    fixture = root/'reference/wsjtcb/channel/cq.wav'
    assert hashlib.sha256(fixture.read_bytes()).hexdigest() == case['sha256']
    env = dict(os.environ, ADB_LIBUSB='1'); package = 'net.vash.ft8cbdroid'; run = uuid.uuid4().hex

    def adb(*command, check=True):
        return subprocess.run(['adb', *command], env=env, capture_output=True, check=check, timeout=10).stdout

    def read(name):
        try:
            return json.loads(adb('exec-out', 'run-as', package, 'cat', 'cache/'+name, check=False))
        except ValueError:
            return {}

    process = None; recorder = None; player = None; scheduled = None
    stages = []; captures = []; desktop_plays = []

    def stop_recorder():
        nonlocal recorder
        if recorder is not None:
            recorder.terminate()
            try:
                recorder.wait(timeout=4)
            except subprocess.TimeoutExpired:
                recorder.kill(); recorder.wait(timeout=2)
                raise RuntimeError('Capture did not finalize cleanly.')
            assert recorder.returncode == 0, 'Physical capture failed; inspect recorder.log.'
            recorder = None

    try:
        with (args.output/'instrumentation.log').open('w') as log, (args.output/'recorder.log').open('w') as audio_log:
            process = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '--user', '0', '-w', '-r',
                '-e', 'scenario', 'phone-audio', '-e', 'preview_run', run, '-e', 'ntp_host', args.ntp_host,
                '-e', 'ntp_port', str(args.ntp_port), package+'.test/'+package+'.validation.WaveformPhoneChecks'],
                env=env, stdout=log, stderr=subprocess.STDOUT)
            until = time.monotonic()+420
            while process.poll() is None:
                assert time.monotonic() < until, 'Phone audio check exceeded 420 seconds.'
                if scheduled is not None and time.time() >= scheduled:
                    assert time.time()-scheduled < .2, 'Missed desktop slot deadline.'
                    player = subprocess.Popen(['pw-play', '--volume', '0.5', str(fixture)], stdout=audio_log, stderr=subprocess.STDOUT)
                    desktop_plays.append(dict(stage=stages[-1], target_utc=scheduled, launched_utc=time.time()))
                    scheduled = None
                    print('Playing pinned desktop CQ acoustically.', flush=True)
                if player is not None and player.poll() is not None:
                    assert player.returncode == 0, 'PC speaker playback failed.'
                    player = None
                if scheduled is None or scheduled-time.time() > 1:
                    ready = read('r17a-ready.json'); stage = ready.get('stage')
                    if ready.get('run') == run and stage not in stages:
                        stop_recorder()
                        # The preceding desktop fixture may still be draining its silent tail.
                        if player is not None:
                            player.wait(timeout=5); assert player.returncode == 0; player = None
                        if stage.startswith('receive-'):
                            scheduled = (int((time.time()+4)//15)+1)*15
                        elif stage.startswith('capture-'):
                            directory = args.output/stage; directory.mkdir(mode=0o700); captures.append(directory)
                            recorder = subprocess.Popen([sys.executable, str(root/'scripts/audio_timing_capture.py'),
                                '--wav', str(directory/'scheduled.wav'), '--seconds', '65'], stdout=audio_log, stderr=subprocess.STDOUT)
                            limit = time.monotonic()+5
                            while not (directory/'scheduled.ready.json').exists():
                                assert recorder.poll() is None and time.monotonic() < limit, 'Recorder startup failed.'
                                time.sleep(.1)
                        else:
                            assert stage == 'captures-complete', stage
                        stages.append(stage)
                        adb('shell', 'run-as', package, 'touch', f'cache/r17a-go-{run}-{stage}')
                        print('Phone stage:', stage, flush=True)
                time.sleep(.03 if scheduled is not None and scheduled-time.time() < 1 else .2)
            stop_recorder()
        log = (args.output/'instrumentation.log').read_text()
        assert process.returncode == 0 and 'phone_audio_result=PASS' in log and 'INSTRUMENTATION_CODE: -1' in log, log
        report = read('r17a-report.json'); assert report['run'] == run
        line = next(line.split('phone_audio_report=', 1)[1] for line in log.splitlines() if 'INSTRUMENTATION_RESULT: phone_audio_report=' in line)
        assert json.loads(line) == report and len(desktop_plays) == 3 and len(captures) == 2
        for directory, slot in zip(captures, report['slots']):
            assert directory.name == 'capture-'+slot['period'].lower()
            (directory/'report.json').write_text(json.dumps(dict(run=run, slots=[slot]), indent=2))
        report['desktop_plays'] = desktop_plays
        report['physical_source'] = source_name
        (args.output/'report.json').write_text(json.dumps(report, indent=2))
        print('Physical RX/output/recovery and device scenarios passed. Independently decode and measure both captures.', flush=True)
    finally:
        stop_recorder()
        if player is not None and player.poll() is None:
            player.terminate(); player.wait(timeout=3)
        if process is not None and process.poll() is None:
            adb('shell', 'am', 'force-stop', '--user', '0', package, check=False)
            process.terminate(); process.wait(timeout=4)
        for path in args.output.rglob('*'):
            if path.is_file():
                path.chmod(0o600)


if __name__ == '__main__':
    main()
