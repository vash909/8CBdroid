#!/usr/bin/env python3
"""Capture the real phone speaker while running the bounded preview phone checks.

Requires installed matching app/test/probe APKs, adb, PipeWire pw-record/wpctl,
an authorized unlocked phone, and a physical PC microphone near its speaker.
Recordings may contain ambient sound; output is private by default.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
import uuid
import wave


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--source', help='Physical PipeWire input node; defaults to the default source')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    env = dict(os.environ, ADB_LIBUSB='1')
    run_id = uuid.uuid4().hex
    package = 'net.vash.ft8cbdroid'

    def adb(*command, check=True):
        return subprocess.run(['adb', *command], env=env, check=check,
                              capture_output=True, timeout=12).stdout

    source = args.source
    if source is None:
        inspection = subprocess.run(['wpctl', 'inspect', '@DEFAULT_AUDIO_SOURCE@'],
                                    check=True, capture_output=True, text=True, timeout=5).stdout
        source = re.search(r'node.name = "([^"]+)"', inspection).group(1)
    if 'monitor' in source.lower():
        raise ValueError('A physical microphone is required, not an output monitor.')
    original_power = adb('shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in').decode().strip()
    recorder = None
    instrument = None
    captures = []

    def stop_recording():
        nonlocal recorder
        if recorder is not None:
            recorder.send_signal(signal.SIGINT)
            try:
                recorder.wait(timeout=4)
            except subprocess.TimeoutExpired:
                recorder.kill()
                recorder.wait(timeout=2)
                raise RuntimeError('PC recorder failed to finalize its WAV.')
            recorder = None

    try:
        adb('shell', 'svc', 'power', 'stayon', 'true')
        with (args.output/'instrumentation.log').open('w') as log, (args.output/'recorder.log').open('w') as record_log:
            instrument = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '--user', '0', '-w', '-r',
                '-e', 'scenario', 'preview', '-e', 'preview_run', run_id,
                package+'.test/'+package+'.validation.WaveformPhoneChecks'], env=env,
                stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic()+240
            stages = []
            while instrument.poll() is None:
                if time.monotonic()>deadline:
                    raise TimeoutError('Phone preview checks exceeded 240 seconds.')
                try:
                    ready = json.loads(adb('shell', 'run-as', package, 'cat', 'cache/r14-ready.json', check=False))
                except (ValueError, subprocess.CalledProcessError):
                    ready = {}
                stage = ready.get('stage')
                if ready.get('run') == run_id and stage not in stages:
                    stop_recording()
                    if stage in ('gain-100', 'gain-50'):
                        path = args.output/(stage+'.wav')
                        recorder = subprocess.Popen(['pw-record', '--target', source, '--rate', '48000',
                            '--channels', '1', '--format', 's16', '--latency', '100ms', str(path)],
                            stdout=record_log, stderr=subprocess.STDOUT)
                        time.sleep(.4)
                        if recorder.poll() is not None:
                            raise RuntimeError('Physical PC microphone recorder did not start.')
                        captures.append(path)
                        adb('shell', 'run-as', package, 'touch', f'cache/r14-go-{run_id}-{stage}')
                    elif stage != 'captures-complete':
                        raise ValueError('Unexpected capture stage: '+str(stage))
                    stages.append(stage)
                    print('Phone stage:', stage, flush=True)
                time.sleep(.2)
            stop_recording()
        log_text = (args.output/'instrumentation.log').read_text()
        if instrument.returncode != 0 or 'INSTRUMENTATION_RESULT: preview_result=PASS' not in log_text or 'INSTRUMENTATION_CODE: -1' not in log_text:
            raise RuntimeError('Phone checks failed; inspect instrumentation.log.')
        assert stages == ['gain-100', 'gain-50', 'captures-complete'], stages
        report = json.loads(adb('shell', 'run-as', package, 'cat', 'cache/r14-report.json'))
        line = next(line.split('preview_report=', 1)[1] for line in log_text.splitlines() if 'INSTRUMENTATION_RESULT: preview_report=' in line)
        assert json.loads(line) == report and report['run'] == run_id
        report['recordings'] = []
        for path in captures:
            with wave.open(str(path)) as wav:
                assert wav.getframerate() == 48000 and wav.getnchannels() == 1 and wav.getsampwidth() == 2
                report['recordings'].append({'file': path.name, 'frames': wav.getnframes(),
                    'rate_hz': 48000, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
        (args.output/'report.json').write_text(json.dumps(report, indent=2))
        print('Phone checks passed; analyze the physical recordings independently before qualification.')
    finally:
        stop_recording()
        if instrument is not None and instrument.poll() is None:
            adb('shell', 'am', 'force-stop', '--user', '0', package, check=False)
            instrument.terminate()
            instrument.wait(timeout=4)
        adb('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', original_power)
        for path in args.output.iterdir():
            if path.is_file():
                path.chmod(0o600)


if __name__ == '__main__':
    main()
