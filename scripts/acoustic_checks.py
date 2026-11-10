#!/usr/bin/env python3
"""Run physical two-way phone/WSJT-CB scenarios; keep captures and reports private.

Requires matching CI app/test APKs, an unlocked phone with station 1AT106 and a
Phone profile, NTP, physical PipeWire speakers/microphone, desktop compilers,
NumPy/SciPy and an installed WSJT-CB jt9 executable. No radio is controlled.
"""
import argparse
import array
import hashlib
import io
import json
import math
import os
from pathlib import Path
import random
import re
import subprocess
import sys
import time
import uuid
import wave

import wsjtcb_channel as channel
import wsjtcb_pcm as pcm_reference

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / 'app/src/androidTest/assets/acoustic.json'
PACKAGE = 'net.vash.ft8cbdroid'


def degraded_wave(data, step):
    """Deterministic input degradation, not a claim of measured over-air SNR."""
    with wave.open(io.BytesIO(data)) as wav:
        assert (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) == (12000, 1, 2)
        samples = array.array('h', wav.readframes(wav.getnframes()))
    if sys.byteorder != 'little':
        samples.byteswap()
    values = [x / 32768 for x in samples]
    if 'noise_snr_db' in step:
        rng = random.Random(step['noise_seed'])
        active = values[9600:161280]
        rms = math.sqrt(sum(x*x for x in active) / len(active))
        sigma = rms * 10 ** (-step['noise_snr_db'] / 20)
        values = [x + rng.gauss(0, sigma) for x in values]
        scale = min(1, .85 / max(abs(x) for x in values))
        values = [x * scale for x in values]
    gain = 10 ** (-step.get('attenuation_db', 0) / 20)
    output = array.array('h', (round(x * gain * 32767) for x in values))
    if sys.byteorder != 'little':
        output.byteswap()
    stream = io.BytesIO()
    with wave.open(stream, 'wb') as wav:
        wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(12000); wav.writeframes(output.tobytes())
    return stream.getvalue()


def analyze(output, fixture, report, channel_exe, decoder, jt9):
    import numpy as np
    from scipy.signal import correlate, hilbert, resample_poly
    expected = {s['id']: s for s in fixture['steps']}
    results = []
    for event in report['events']:
        if event['action'] != 'send':
            continue
        step = expected[event['id']]
        directory = output / event['id']
        with wave.open(str(directory / 'scheduled.wav')) as wav:
            assert (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) == (48000, 1, 2)
            raw = np.frombuffer(wav.readframes(wav.getnframes()), '<i2').astype(float) / 32768
        pcm = resample_poly(raw, 1, 4)
        rows = [json.loads(line) for line in (directory / 'scheduled.timestamps.jsonl').read_text().splitlines()]
        target = event['utc_millis'] * 1_000_000
        anchor = min(rows, key=lambda r: abs(r['end_utc_ns'] - (target - 300_000_000)))
        offset = round((anchor['end_frame'] + (target - 300_000_000 - anchor['end_utc_ns']) * 48000 / 1e9) / 4)
        assert 0 <= offset and offset + 180000 <= len(pcm)
        # Full desktop search gets the UTC window from timestamps, without a
        # synthesized template, known tones or a forced candidate frequency.
        pcm_reference.write_wave(directory / 'utc-slot.wav', pcm[offset:offset+180000])
        desktop = subprocess.run([str(jt9), '-8', '-p', '15', '-m', '1', '-w', '0', '-d', '2',
            '-a', str(directory), '-t', str(directory), '-c', step['peer'], '-x', fixture['station'],
            str(directory / 'utc-slot.wav')], check=True, capture_output=True, text=True, timeout=30)
        (directory / 'jt9.txt').write_text(desktop.stdout + desktop.stderr)
        texts = [line.split('~', 1)[1].strip() for line in desktop.stdout.splitlines() if '~' in line]
        assert step['message'] in texts or step['desktop_cold_text'] in texts, desktop.stdout
        # Separate original LDPC/CRC verification checks the exact payload and
        # all observed tones. Correlation only locates the recorded signal.
        data = channel.make_wave(channel_exe, step['payload77'], output / 'reference')
        with wave.open(io.BytesIO(data)) as wav:
            template = np.frombuffer(wav.readframes(wav.getnframes()), '<i2').astype(float) / 32768
        start = max(0, offset-6000); end = min(len(pcm), offset+186000)
        correlation = correlate(hilbert(pcm[start:end]), hilbert(template), mode='valid', method='fft')
        actual = start + int(np.argmax(abs(correlation)))
        first = (actual + 9600) * 4
        anchor = min(rows, key=lambda r: abs(r['end_frame'] - first))
        onset = anchor['end_utc_ns'] + (first-anchor['end_frame']) * 1e9 / 48000
        error = (onset - (target+500_000_000)) / 1e6
        nearby = [r['end_utc_ns']-r['end_frame']*1e9/48000 for r in rows if abs(r['end_frame']-first) < 48000]
        spread = (max(nearby)-min(nearby)) / 1e6
        assert spread < 30 and abs(error) <= 150, (event['id'], error, spread)
        assert max(abs(raw[(actual+9600)*4:(actual+161280)*4])) < .99, 'Clipped physical capture'
        recorded = pcm[actual:actual+180000]
        assert len(recorded) == 180000
        recorded = recorded / max(abs(recorded)) * .7
        pcm_reference.write_wave(directory / 'aligned-slot.wav', recorded)
        decoded = subprocess.check_output([str(decoder), str(directory / 'aligned-slot.wav'), '1000'], text=True, timeout=20).strip()
        bits, tones, cold, _ = decoded.split('|')
        vector = subprocess.check_output([str(channel_exe)], input=step['payload77']+'\n', text=True, timeout=10).strip().split('|')
        assert bits == step['payload77'] == event['payload77'] and tones == vector[2], decoded
        results.append(dict(id=event['id'], payload77=bits, tones79=tones, desktop_texts=texts,
            cold_reference_text=cold, acoustic_error_ms=error, capture_anchor_spread_ms=spread,
            capture_sha256=hashlib.sha256((directory/'scheduled.wav').read_bytes()).hexdigest()))
        print('Independent desktop capture PASS:', event['id'], texts, flush=True)
    assert len(results) == sum(s['action'] == 'send' for s in fixture['steps'])
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--jt9', required=True, type=Path, help='Installed WSJT-CB jt9, not WSJT-X')
    parser.add_argument('--ntp-host', default='time.cloudflare.com')
    parser.add_argument('--ntp-port', type=int, default=123)
    parser.add_argument('--analyze-only', action='store_true', help='Recheck retained physical captures without playing or recording audio')
    args = parser.parse_args()
    output = args.output.resolve()
    if args.analyze_only:
        report = json.loads((output/'phone-report.json').read_text())
        assert report['fixture_sha256'] == hashlib.sha256(FIXTURE.read_bytes()).hexdigest()
        fixture = json.loads(FIXTURE.read_text())
        report['independent_captures'] = analyze(output, fixture, report,
            output/'reference/channel', output/'reference/pcm_decode', args.jt9.resolve())
        (output/'report.json').write_text(json.dumps(report, indent=2))
        return
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    assert subprocess.check_output(['timedatectl', 'show', '-p', 'NTPSynchronized', '--value'], text=True, timeout=5).strip() == 'yes'
    source = subprocess.check_output(['wpctl', 'inspect', '@DEFAULT_AUDIO_SOURCE@'], text=True, timeout=5)
    source_name = re.search(r'node.name = "([^"]+)"', source).group(1)
    assert source_name.startswith('alsa_input.'), 'A physical PC microphone is required.'
    fixture = json.loads(FIXTURE.read_text())
    assert fixture['cases_sha256'] == hashlib.sha256((ROOT/'reference/wsjtcb/acoustic-cases.json').read_bytes()).hexdigest()
    reference = output / 'reference'; reference.mkdir()
    channel_exe, sources = channel.compile_reference(reference)
    assert fixture['revision'] == sources['revision']
    assert fixture['packing_sources_sha256'] == hashlib.sha256(channel.packing.SOURCES.read_bytes()).hexdigest()
    decoder = pcm_reference.compile_decoder(reference)
    incoming = {}
    for step in fixture['steps']:
        if step['action'] == 'receive':
            path = reference / (step['id']+'.wav')
            path.write_bytes(degraded_wave(channel.make_wave(channel_exe, step['payload77'], reference), step))
            incoming[step['id']] = path
    env = dict(os.environ, ADB_LIBUSB='1')
    run = uuid.uuid4().hex

    def adb(*command, check=True, data=None):
        return subprocess.run(['adb', *command], env=env, input=data, capture_output=True, check=check, timeout=10).stdout

    def read(name):
        try:
            return json.loads(adb('exec-out', 'run-as', PACKAGE, 'cat', 'cache/'+name, check=False))
        except ValueError:
            return {}

    process = player = recorder = None
    stages = []; plays = []; scheduled = None

    def stop_recorder():
        nonlocal recorder
        if recorder is not None:
            recorder.terminate()
            try:
                recorder.wait(timeout=4)
            except subprocess.TimeoutExpired:
                recorder.kill(); recorder.wait(timeout=2)
                raise RuntimeError('Physical capture did not finalize.')
            assert recorder.returncode == 0
            recorder = None

    try:
        with (output/'instrumentation.log').open('w') as log, (output/'audio.log').open('w') as audio_log:
            process = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '--user', '0', '-w', '-r',
                '-e', 'scenario', 'acoustic', '-e', 'preview_run', run, '-e', 'ntp_host', args.ntp_host,
                '-e', 'ntp_port', str(args.ntp_port), PACKAGE+'.test/'+PACKAGE+'.validation.WaveformPhoneChecks'],
                env=env, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 1050
            while process.poll() is None:
                assert time.monotonic() < deadline, 'Acoustic scenarios exceeded 1050 seconds.'
                if scheduled is not None and time.time() >= scheduled:
                    assert time.time()-scheduled < .2, 'Missed physical desktop slot deadline.'
                    player = subprocess.Popen(['pw-play', '--volume', '0.5', str(incoming[stages[-1]])], stdout=audio_log, stderr=subprocess.STDOUT)
                    plays.append(dict(id=stages[-1], utc_millis=scheduled*1000, launched_utc=time.time()))
                    scheduled = None
                    print('Playing desktop audio:', stages[-1], flush=True)
                if player is not None and player.poll() is not None:
                    assert player.returncode == 0, 'PC playback failed.'
                    player = None
                if scheduled is None or scheduled-time.time() > 1:
                    ready = read('acoustic-ready.json'); stage = ready.get('stage')
                    if ready.get('run') == run and stage not in stages:
                        stop_recorder()
                        if player is not None:
                            player.wait(timeout=5); assert player.returncode == 0; player = None
                        step = next((s for s in fixture['steps'] if s['id'] == stage), None)
                        if stage == 'complete':
                            assert len(stages) == sum(s['action'] != 'reset' for s in fixture['steps'])
                        elif step['action'] == 'receive':
                            scheduled = (int((time.time()+4)//15)+1)*15
                        elif step['action'] == 'send':
                            directory = output / stage; directory.mkdir(mode=0o700)
                            recorder = subprocess.Popen([sys.executable, str(ROOT/'scripts/audio_timing_capture.py'),
                                '--wav', str(directory/'scheduled.wav'), '--seconds', '65'], stdout=audio_log, stderr=subprocess.STDOUT)
                            limit = time.monotonic()+5
                            while not (directory/'scheduled.ready.json').exists():
                                assert recorder.poll() is None and time.monotonic() < limit
                                time.sleep(.1)
                        else:
                            raise AssertionError('Unknown phone stage: '+str(stage))
                        stages.append(stage)
                        data = json.dumps(dict(utc_millis=(scheduled or 0)*1000)).encode()
                        adb('exec-in', 'run-as', PACKAGE, 'tee', 'cache/acoustic-go-'+run+'-'+stage+'.tmp', data=data)
                        adb('shell', 'run-as', PACKAGE, 'mv', 'cache/acoustic-go-'+run+'-'+stage+'.tmp', 'cache/acoustic-go-'+run+'-'+stage+'.json')
                        print('Phone stage:', stage, flush=True)
                time.sleep(.03 if scheduled is not None and scheduled-time.time() < 1 else .2)
        stop_recorder()
        log = (output/'instrumentation.log').read_text()
        assert process.returncode == 0 and 'acoustic_result=PASS' in log and 'INSTRUMENTATION_CODE: -1' in log, log
        report = read('acoustic-report.json'); assert report['run'] == run
        line = next(line.split('acoustic_report=', 1)[1] for line in log.splitlines() if 'INSTRUMENTATION_RESULT: acoustic_report=' in line)
        assert json.loads(line) == report
        report.update(desktop_plays=plays, physical_source=source_name, fixture_sha256=hashlib.sha256(FIXTURE.read_bytes()).hexdigest(),
            desktop_binary_sha256=hashlib.sha256(args.jt9.read_bytes()).hexdigest())
        (output/'phone-report.json').write_text(json.dumps(report, indent=2))
        report['independent_captures'] = analyze(output, fixture, report, channel_exe, decoder, args.jt9.resolve())
        (output/'report.json').write_text(json.dumps(report, indent=2))
        print('Physical bidirectional acoustic scenarios and independent desktop decodes PASS. No RF observation.', flush=True)
    finally:
        stop_recorder()
        if player is not None and player.poll() is None:
            player.terminate(); player.wait(timeout=3)
        if process is not None and process.poll() is None:
            adb('shell', 'am', 'force-stop', '--user', '0', PACKAGE, check=False)
            process.terminate(); process.wait(timeout=4)
        for path in output.rglob('*'):
            if path.is_file():
                path.chmod(0o600)


if __name__ == '__main__':
    main()
