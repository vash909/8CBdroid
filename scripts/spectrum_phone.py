#!/usr/bin/env python3
"""Bounded, physical two-tone spectrum/rendering check; no injected microphone samples."""
import json
import math
import os
from pathlib import Path
import signal
import struct
import subprocess
import time
import wave

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.tooling'


def main():
    OUT.mkdir(exist_ok=True)
    for name in ['spectrum-phone-report.json', 'spectrum-gfxinfo.txt']:
        (OUT / name).unlink(missing_ok=True)
    env = dict(os.environ, ADB_LIBUSB='1')

    def adb(*args, check=True):
        return subprocess.run(['adb', *args], env=env, capture_output=True, check=check, timeout=10).stdout

    def interrupted(*_):
        raise InterruptedError('Spectrum check interrupted')

    signal.signal(signal.SIGTERM, interrupted)
    # Independent host frequencies, generated without using the Android FFT.
    tone = OUT / 'spectrum-reference.wav'
    with wave.open(str(tone), 'wb') as output:
        output.setparams((1, 2, 12000, 0, 'NONE', 'not compressed'))
        for second in range(150):
            samples = bytearray()
            for sample in range(12000):
                index = second * 12000 + sample
                ramp = min(1, index / 120, (150 * 12000 - index) / 120)
                value = .18 * ramp * (math.sin(2 * math.pi * 700 * index / 12000) + math.sin(2 * math.pi * 1900 * index / 12000))
                samples.extend(struct.pack('<h', round(value * 32767)))
            output.writeframes(samples)
    original = adb('shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in').decode().strip()
    instrument = None
    player = None
    try:
        adb('shell', 'svc', 'power', 'stayon', 'true')
        adb('shell', 'run-as', 'net.vash.ft8cbdroid', 'rm', '-f', 'cache/r13-spectrum-ready.json')
        log = OUT / 'spectrum-phone-output.txt'
        with log.open('w') as output:
            instrument = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '--user', '0', '-w', '-r',
                '-e', 'scenario', 'spectrum', 'net.vash.ft8cbdroid.test/net.vash.ft8cbdroid.validation.WaveformPhoneChecks'],
                env=env, stdout=output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 210
            while instrument.poll() is None:
                assert time.monotonic() < deadline, 'Spectrum phone check exceeded 210 seconds'
                if player is None:
                    exists = adb('exec-out', 'run-as', 'net.vash.ft8cbdroid', 'cat',
                                 'cache/r13-spectrum-ready.json', check=False).strip() == b'{}'
                    if exists:
                        player = subprocess.Popen(['pw-play', '--volume', '0.5', str(tone)],
                                                  stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
                        print('Playing independent 700/1900 Hz tones; measuring 120 seconds of visible rendering.', flush=True)
                if player is not None and player.poll() is not None:
                    assert player.returncode == 0, 'Desktop tone playback failed'
                time.sleep(.5)
        text = log.read_text()
        print(text)
        assert 'spectrum_result=PASS' in text and 'INSTRUMENTATION_CODE: -1' in text, 'Spectrum phone checks did not pass'
        report = json.loads(adb('exec-out', 'run-as', 'net.vash.ft8cbdroid', 'cat', 'cache/r13-spectrum-report.json'))
        line = next(line for line in text.splitlines() if line.startswith('INSTRUMENTATION_RESULT: spectrum_report='))
        assert json.loads(line.split('=', 1)[1]) == report and player is not None
        (OUT / 'spectrum-phone-report.json').write_text(json.dumps(report, indent=2))
        (OUT / 'spectrum-gfxinfo.txt').write_bytes(adb('exec-out', 'run-as', 'net.vash.ft8cbdroid', 'cat', 'cache/r13-gfxinfo.txt'))
        print('Verified physical frequency axis, bounded waterfall and sustained visible rendering.')
    finally:
        if player is not None and player.poll() is None:
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
