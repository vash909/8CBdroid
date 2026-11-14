#!/usr/bin/env python3
"""Qualify automatic exchanges with physical phone/PC audio and original desktop decoders.

Requires matching CI app/test/probe APKs, station 1AT106, a Phone profile, an
unlocked phone, working selected NTP server, synchronized host, physical PC
speakers/microphone, reference compilers and NumPy/SciPy. Keep all output private.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import uuid
import wave

import wsjtcb_channel as channel
import wsjtcb_packing as packing
import wsjtcb_pcm as pcm_reference

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'net.vash.ft8cbdroid'
LIVE_WINDOW_LEAD_MILLIS = 1600
TEXTS = {'cq': 'CQ 26AT101', 'reply': '<1AT106> 26AT101', 'report': '1AT106 -07',
         'roger': '1AT106 R-07', 'final': '1AT106 RR73', 'signoff': '1AT106 73'}


def original_payload(executable, text):
    row, = packing.run(executable, ['P|' + text])
    bits = row.split('|')[2]
    decoded, = packing.run(executable, ['C|', 'S|1AT106', 'L|26AT101', 'D|' + bits])
    ok, message = decoded.split('|', 1)
    message = ' '.join(message.split())
    if ok == '1' and message != text:
        # The pinned receive session cannot recover this outgoing peer hash.
        # Validate against its original packed fixture, without inventing a resolution.
        corpus = json.loads((ROOT/'reference/wsjtcb/qso.json').read_text())['message_payloads']
        known = next((m for m in corpus if m['message'].strip() == text), None)
        assert known is not None and known['payload77'] == bits and known['cold_decoded'] == message
    else:
        assert ok == '1' and message == text, 'Lossy original desktop input: ' + text
    return bits


def analyze(output, report, encoder, packer, decoder, jt9):
    import numpy as np
    from scipy.signal import resample_poly
    captures = []
    for role in report['roles']:
        directory = output / role['role']
        with wave.open(str(directory/'scheduled.wav')) as wav:
            assert (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) == (48000, 1, 2)
            raw = np.frombuffer(wav.readframes(wav.getnframes()), '<i2').astype(float)/32768
        pcm = resample_poly(raw, 1, 4)
        rows = [json.loads(line) for line in (directory/'scheduled.timestamps.jsonl').read_text().splitlines()]
        for index, slot in enumerate(role['sent']):
            target = slot['utc_millis'] * 1_000_000
            bits = original_payload(packer, slot['text'])
            assert slot['payload77'] == bits and slot['frames'] == 161280
            encoded = subprocess.check_output([str(encoder)], input=bits+'\n', text=True, timeout=15).strip()
            expected_tones = encoded.split('|')[2]
            anchor = min(rows, key=lambda r: abs(r['end_utc_ns']-(target-300_000_000)))
            offset = round((anchor['end_frame']+(target-300_000_000-anchor['end_utc_ns'])*48000/1e9)/4)
            assert 0 <= offset and offset+180000 <= len(pcm), 'Physical recording missed the complete observation window'
            path = directory/f'output-{index+1}.wav'
            pcm_reference.write_wave(path, pcm[offset:offset+180000])
            desktop = subprocess.run([str(jt9), '-8', '-p', '15', '-m', '1', '-w', '0', '-d', '2',
                '-a', str(directory), '-t', str(directory), '-c', '26AT101', '-x', '1AT106', str(path)],
                check=True, capture_output=True, text=True, timeout=30)
            path.with_suffix('.jt9.txt').write_text(desktop.stdout+desktop.stderr)
            texts = [' '.join(line.split('~', 1)[1].split()) for line in desktop.stdout.splitlines() if '~' in line]
            assert slot['text'] in texts, desktop.stdout
            syncidx = np.array(list(range(7))+list(range(36, 43))+list(range(72, 79)))
            sync = np.tile([3, 1, 4, 0, 6, 5, 2], 3)
            frequency = slot['frequency_hz']; base = round(frequency/6.25)
            rotation = np.exp(-2j*np.pi*(frequency-base*6.25)*np.arange(1920)/12000)
            ranks = []
            for shift in range(-1800, 1801, 12):
                indices = offset+9600+shift+np.arange(79)[:, None]*1920+np.arange(1920)[None, :]
                assert indices.min() >= 0 and indices.max() < len(pcm)
                powers = abs(np.fft.fft(pcm[indices]*rotation, axis=1))[:, base:base+8]**2
                score = np.mean(powers[syncidx, sync]/np.maximum(powers[syncidx].sum(axis=1), 1e-20))
                ranks.append((float(score), shift))
            score, shift = max(ranks); assert abs(shift) < 1800
            actual = offset+shift
            assert actual+180000 <= len(pcm)
            first = (actual+9600)*4
            anchor = min(rows, key=lambda r: abs(r['end_frame']-first))
            onset = anchor['end_utc_ns']+(first-anchor['end_frame'])*1e9/48000
            error = (onset-target-500_000_000)/1e6
            nearby = [r['end_utc_ns']-r['end_frame']*1e9/48000 for r in rows if abs(r['end_frame']-first) < 48000]
            spread = (max(nearby)-min(nearby))/1e6
            assert abs(error) <= 150 and spread < 30, (error, spread)
            peak = float(max(abs(raw[(actual+9600)*4:(actual+161280)*4])))
            assert peak < .99, 'Physical microphone clipped'
            normalized = pcm[actual:actual+180000]
            normalized = normalized/max(abs(normalized))*.7
            aligned = directory/f'output-{index+1}-aligned.wav'
            pcm_reference.write_wave(aligned, normalized)
            original = subprocess.check_output([str(decoder), str(aligned), str(frequency), ".8", "acoustic"], text=True, timeout=20).strip()
            decoded_bits, tones, _, _ = original.split('|')
            # Acoustic noise may change hard decisions that the original LDPC/CRC
            # decoder corrects. Require the exact recovered payload and retain
            # every observed symbol error as evidence.
            # The independent full desktop candidate search above supplies sync.
            assert decoded_bits == bits
            assert len(tones) == len(expected_tones) == 79
            tone_errors = [i for i, (a, b) in enumerate(zip(tones, expected_tones)) if a != b]
            captures.append(dict(role=role['role'], index=index+1, text=slot['text'], payload77=bits,
                utc_millis=slot['utc_millis'], frequency_hz=frequency, actual_played_frames=slot['frames'],
                acoustic_error_ms=error, capture_anchor_spread_ms=spread, peak=peak, sync_score=score,
                desktop_texts=texts, original_tones79=tones, expected_tones79=expected_tones,
                corrected_hard_tone_indices=tone_errors))
            print('Independent physical decode:', role['role'], index+1, slot['text'], flush=True)
    return captures


def validate_roles(report, plays):
    assert sorted(role['role'] for role in report['roles']) == ['answering', 'calling', 'no-response']
    for role in report['roles']:
        sent = role['sent']; texts = [s['text'] for s in sent]
        assert all(row['text'] != 'CQ 1AT106' for row in role['received']), 'Own output leaked into a receive window'
        assert all(b['utc_millis']-a['utc_millis'] == 30000 for a, b in zip(sent, sent[1:])), 'Automatic exchanges skipped an immediate reply opportunity'
        if role['role'] == 'calling':
            assert texts[:2] == ['CQ 1AT106']*2 and texts[2] == texts[3]
            assert re.fullmatch(r'26AT101 [+-]\d{2}', texts[2]) and texts[4] == '26AT101 RR73'
            expected = [(1, TEXTS['reply']), (2, TEXTS['reply']), (3, TEXTS['roger']), (4, TEXTS['signoff'])]
        elif role['role'] == 'answering':
            assert texts[0] == '<26AT101> 1AT106' and re.fullmatch(r'26AT101 R[+-]\d{2}', texts[1]) and texts[2] == '26AT101 73'
            expected = [(0, TEXTS['report']), (1, TEXTS['final'])]
        else:
            assert texts == ['CQ 1AT106']*3 and role['qso'] != 'COMPLETE'
            continue
        assert role['qso'] == 'COMPLETE'
        for index, text in expected:
            utc = sent[index]['utc_millis']+15000
            assert any(p['role'] == role['role'] and p['utc_millis'] == utc and p['text'] == text for p in plays)
            assert any(r['utc_millis'] == utc and r['text'] == text and r['captured_samples'] == 162000 for r in role['received']), (role['role'], text, utc)


def time_recovery(output):
    """Repeat only real NTP failure/UI recovery; this does not qualify audio."""
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    env = dict(os.environ, ADB_LIBUSB='1'); run = uuid.uuid4().hex
    def adb(*command):
        return subprocess.check_output(['adb', *command], env=env, timeout=12)
    def read(name):
        try: return json.loads(adb('exec-out', 'run-as', PACKAGE, 'cat', 'cache/'+name))
        except (ValueError, subprocess.CalledProcessError): return {}
    original = None; restored = False; process = None; stages = []
    def restore():
        nonlocal restored
        if original is not None and not restored:
            for service, key in [('wifi', 'wifi_on'), ('data', 'mobile_data')]:
                adb('shell', 'svc', service, 'enable' if original[key] == '1' else 'disable')
            for key, value in original.items():
                assert adb('shell', 'settings', 'get', 'global', key).decode().strip() == value
            restored = True
            (output/'network-restored.json').write_text(json.dumps(dict(restored=True, original=original)))
    def terminate(*_): raise KeyboardInterrupt('NTP qualification interrupted')
    signal.signal(signal.SIGTERM, terminate)
    try:
        with (output/'instrumentation.log').open('w') as log:
            process = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '--user', '0', '-w', '-r',
                '-e', 'scenario', 'automatic-time', '-e', 'preview_run', run,
                PACKAGE+'.test/'+PACKAGE+'.validation.WaveformPhoneChecks'], env=env, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic()+800
            while process.poll() is None:
                assert time.monotonic() < deadline, 'NTP recovery exceeded 800 seconds'
                ready = read('autoseq-ready.json'); stage = ready.get('stage')
                if ready.get('run') == run and stage not in stages:
                    if stage == 'ntp-offline':
                        original = {key: adb('shell', 'settings', 'get', 'global', key).decode().strip() for key in ['wifi_on', 'mobile_data']}
                        assert all(value in ['0', '1'] for value in original.values())
                        (output/'network-original.json').write_text(json.dumps(original))
                        adb('shell', 'svc', 'wifi', 'disable'); adb('shell', 'svc', 'data', 'disable')
                    else:
                        assert stage == 'ntp-online' and stages == ['ntp-offline']
                        restore()
                    stages.append(stage)
                    adb('shell', 'run-as', PACKAGE, 'touch', f'cache/autoseq-go-{run}-{stage}')
                    print('Real NTP phase:', stage, flush=True)
                time.sleep(.3)
        text = (output/'instrumentation.log').read_text()
        assert process.returncode == 0 and 'autoseq_result=PASS' in text and 'INSTRUMENTATION_CODE: -1' in text, text
        report = read('autoseq-report.json')
        assert report['run'] == run and report['scope'] == 'time-recovery' and report['result'] == 'PASS'
        assert report['real_ntp_failure_warning_and_manual_recovery'] and report['failed_refresh_preserved_expiry']
        assert report['roles'] == [] and report['guards'] == [] and restored
        (output/'report.json').write_text(json.dumps(report, indent=2))
        print('Real periodic NTP failure, original expiry, warning navigation and explicit recovery PASS.', flush=True)
    finally:
        restore()
        if process is not None and process.poll() is None:
            adb('shell', 'am', 'force-stop', '--user', '0', PACKAGE)
            process.terminate(); process.wait(timeout=4)
        for path in output.glob('*'):
            if path.is_file(): path.chmod(0o600)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--jt9', required=True, type=Path)
    parser.add_argument('--continuous', action='store_true', help='Qualify navigation, Activity destruction, background and a complete screen-off QSO; omit the separate NTP outage/UI-recovery scenario.')
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--analyze-only', action='store_true')
    modes.add_argument('--time-only', action='store_true', help='Repeat only actual NTP failure/recovery; no audio qualification')
    args = parser.parse_args(); output = args.output.resolve(); jt9 = args.jt9.resolve()
    if args.time_only:
        time_recovery(output); return
    import numpy as np
    from scipy.signal import resample_poly
    assert jt9.is_file() and os.access(jt9, os.X_OK)
    if args.analyze_only:
        report = json.loads((output/'phone-report.json').read_text())
        assert report['result'] == 'PASS' and len(report['live_desktop_decodes']) == 11
        assert report['desktop_binary_sha256'] == hashlib.sha256(jt9.read_bytes()).hexdigest()
        validate_roles(report, report['desktop_plays'])
        reference = output/'reference'
        report['independent_captures'] = analyze(output, report, reference/'channel', reference/'packing', reference/'pcm_decode', jt9)
        (output/'report.json').write_text(json.dumps(report, indent=2)); return
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    assert subprocess.check_output(['timedatectl', 'show', '-p', 'NTPSynchronized', '--value'], text=True, timeout=5).strip() == 'yes'
    source = subprocess.check_output(['wpctl', 'inspect', '@DEFAULT_AUDIO_SOURCE@'], text=True, timeout=5)
    source_name = re.search(r'node.name = "([^"]+)"', source).group(1)
    assert source_name.startswith('alsa_input.'), 'Use a physical PC microphone, not an output monitor.'
    reference = output/'reference'; reference.mkdir()
    encoder, manifest = channel.compile_reference(reference)
    packer = packing.compile_reference(reference, manifest, 'O0')
    decoder = pcm_reference.compile_decoder(reference)
    incoming = {}
    for key, text in TEXTS.items():
        path = reference/(key+'.wav'); path.write_bytes(channel.make_wave(encoder, original_payload(packer, text), reference)); incoming[key] = path
    env = dict(os.environ, ADB_LIBUSB='1'); run = uuid.uuid4().hex
    def adb(*command, check=True):
        return subprocess.run(['adb', *command], env=env, check=check, capture_output=True, timeout=10).stdout
    def read(name):
        try: return json.loads(adb('exec-out', 'run-as', PACKAGE, 'cat', 'cache/'+name, check=False))
        except ValueError: return {}
    process = recorder = player = None; stages = []; plays = []; scheduled = None; handled = set(); role = None
    pending_online = []; online = []; network_original = None; network_restored = False; service_verified = False
    def restore_network():
        nonlocal network_restored
        if network_original is not None and not network_restored:
            for service, key in [('wifi', 'wifi_on'), ('data', 'mobile_data')]:
                adb('shell', 'svc', service, 'enable' if network_original[key] == '1' else 'disable')
            network_restored = True
            (output/'network-restored.json').write_text(json.dumps(dict(restored=True, original=network_original)))
    def online_decode(item):
        directory = output/item['role']; slot = item['slot']
        if time.time() < slot['utc_millis']/1000+15-LIVE_WINDOW_LEAD_MILLIS/1000: return False
        rows = [json.loads(line) for line in (directory/'scheduled.timestamps.jsonl').read_text().splitlines()]
        target = (slot['utc_millis']-LIVE_WINDOW_LEAD_MILLIS)*1_000_000
        anchor = min(rows, key=lambda r: abs(r['end_utc_ns']-target))
        first = round(anchor['end_frame']+(target-anchor['end_utc_ns'])*48000/1e9)
        path = directory/'scheduled.wav'
        if path.stat().st_size < 44+(first+720000)*2: return False
        assert first >= 0
        with path.open('rb') as source:
            source.seek(44+first*2); data = source.read(720000*2)
        assert len(data) == 720000*2
        raw = np.frombuffer(data, '<i2').astype(float)/32768
        captured = directory/f'online-{item["index"]+1}.wav'
        pcm_reference.write_wave(captured, resample_poly(raw, 1, 4))
        started = time.time()
        decoded = subprocess.run([str(jt9), '-8', '-p', '15', '-m', '1', '-w', '0', '-d', '1',
            '-a', str(directory), '-t', str(directory), '-c', '26AT101', '-x', '1AT106', str(captured)],
            capture_output=True, text=True, timeout=10, check=True)
        texts = [' '.join(line.split('~', 1)[1].split()) for line in decoded.stdout.splitlines() if '~' in line]
        depth = 1
        if slot['text'] not in texts:
            # Ordinary ambient noise can defeat the fast desktop pass. Reuse
            # the same real samples at full depth, within the fixed reply time.
            remaining = slot['utc_millis']/1000+14.7-time.time()
            assert remaining > .1, 'No time left for a full desktop decode'
            decoded = subprocess.run([str(jt9), '-8', '-p', '15', '-m', '1', '-w', '0', '-d', '2',
                '-a', str(directory), '-t', str(directory), '-c', '26AT101', '-x', '1AT106', str(captured)],
                capture_output=True, text=True, timeout=min(1., remaining), check=True)
            texts = [' '.join(line.split('~', 1)[1].split()) for line in decoded.stdout.splitlines() if '~' in line]
            depth = 2
        captured.with_suffix('.jt9.txt').write_text(decoded.stdout+decoded.stderr)
        assert slot['text'] in texts, decoded.stdout
        online.append(dict(role=item['role'], index=item['index']+1, text=slot['text'],
            started_utc=started, completed_utc=time.time(), desktop_depth=depth, actual_window_start_utc_millis=slot['utc_millis']-LIVE_WINDOW_LEAD_MILLIS))
        if item['response'] is not None: schedule(item['response'], slot['utc_millis']/1000+15)
        print('Live desktop decode:', item['role'], item['index']+1, slot['text'], flush=True)
        return True
    def stop_recorder():
        nonlocal recorder
        if recorder is not None:
            recorder.terminate(); recorder.wait(timeout=5)
            assert recorder.returncode == 0; recorder = None
    def schedule(name, target):
        nonlocal scheduled
        assert scheduled is None and target-time.time() > .25
        scheduled = (name, target)
    def terminate(*_): raise KeyboardInterrupt('Physical qualification interrupted')
    signal.signal(signal.SIGTERM, terminate)
    try:
        with (output/'instrumentation.log').open('w') as log, (output/'audio.log').open('w') as audio:
            process = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '--user', '0', '-w', '-r',
                '-e', 'scenario', 'continuous-operating' if args.continuous else 'automatic-audio', '-e', 'preview_run', run,
                PACKAGE+'.test/'+PACKAGE+'.validation.WaveformPhoneChecks'], env=env, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic()+1400
            while process.poll() is None:
                assert time.monotonic() < deadline, 'Automatic phone checks exceeded 1400 seconds'
                if recorder is not None: assert recorder.poll() is None, 'Physical recording ended prematurely'
                if scheduled is not None and time.time() >= scheduled[1]:
                    name, target = scheduled; assert time.time()-target < .2
                    assert player is None
                    player = subprocess.Popen(['pw-play', '--volume', '0.5', str(incoming[name])], stdout=audio, stderr=subprocess.STDOUT)
                    plays.append(dict(role=role, utc_millis=round(target*1000), text=TEXTS[name], launched_utc=time.time()))
                    print('Immediate desktop response:', role, name, flush=True); scheduled = None
                if player is not None and player.poll() is not None:
                    assert player.returncode == 0; player = None
                if pending_online and online_decode(pending_online[0]): pending_online.pop(0)
                if scheduled is None or scheduled[1]-time.time() > .6:
                    ready = read('autoseq-ready.json'); stage = ready.get('stage')
                    if ready.get('run') == run and stage not in stages:
                        if stage in ['calling', 'answering', 'no-response']:
                            role = stage; directory = output/role; directory.mkdir(mode=0o700)
                            recorder = subprocess.Popen([sys.executable, str(ROOT/'scripts/audio_timing_capture.py'), '--wav', str(directory/'scheduled.wav'), '--seconds', '240'], stdout=audio, stderr=subprocess.STDOUT)
                            limit = time.monotonic()+5
                            while not (directory/'scheduled.ready.json').exists():
                                assert recorder.poll() is None and time.monotonic() < limit; time.sleep(.05)
                            if role == 'answering': schedule('cq', (int((time.time()+4)//15)+1)*15)
                        elif stage.startswith('guard-'):
                            assert role is None and recorder is None
                        elif stage == 'ntp-offline':
                            assert role is None and recorder is None
                            network_original = {key: adb('shell', 'settings', 'get', 'global', key).decode().strip() for key in ['wifi_on', 'mobile_data']}
                            assert all(value in ['0', '1'] for value in network_original.values())
                            (output/'network-original.json').write_text(json.dumps(network_original))
                            adb('shell', 'svc', 'wifi', 'disable'); adb('shell', 'svc', 'data', 'disable')
                        elif stage == 'ntp-online':
                            assert network_original is not None
                            restore_network()
                        else:
                            assert stage == 'done-'+role
                            progress = read('autoseq-progress.json')
                            last = max(s['utc_millis'] for s in progress['sent'])/1000
                            while pending_online:
                                if online_decode(pending_online[0]): pending_online.pop(0)
                                else: time.sleep(.05)
                            while time.time() < last+16: time.sleep(.05)
                            stop_recorder(); role = None
                        stages.append(stage); adb('shell', 'run-as', PACKAGE, 'touch', f'cache/autoseq-go-{run}-{stage}')
                        print('Phone stage:', stage, flush=True)
                    progress = read('autoseq-progress.json')
                    if role is not None and progress.get('run') == run and progress.get('role') == role:
                        if args.continuous and progress.get('active') and not service_verified:
                            services = adb('shell', 'dumpsys', 'activity', 'services', PACKAGE).decode()
                            (output/'foreground-service.txt').write_text(services)
                            # Vendor dumps may omit types. The device probe reads Service.getForegroundServiceType().
                            api = progress['android_api']
                            types = progress['foreground_service_types']
                            required = 0x82 if api >= 30 else 0x2 if api >= 29 else 0
                            assert type(types) is int and types & required == required, 'Microphone/mediaPlayback foreground service not observed'
                            assert 'isForeground=true foregroundId=2901' in services, 'Operating service is not in the foreground'
                            (output/'foreground-service-types.json').write_text(json.dumps(dict(android_api=api, actual=types, required=required)))
                            service_verified = True
                        for index, sent in enumerate(progress['sent']):
                            key = (role, index)
                            if key in handled: continue
                            handled.add(key); print('Phone complete waveform:', role, index+1, sent['text'], flush=True)
                            name = ({1: 'reply', 2: 'reply', 3: 'roger', 4: 'signoff'}.get(index) if role == 'calling'
                                else {0: 'report', 1: 'final'}.get(index) if role == 'answering' else None)
                            pending_online.append(dict(role=role, index=index, slot=sent, response=name))
                time.sleep(.02 if scheduled is not None and scheduled[1]-time.time() < .6 else .1)
            stop_recorder()
        text = (output/'instrumentation.log').read_text()
        assert process.returncode == 0 and 'autoseq_result=PASS' in text and 'INSTRUMENTATION_CODE: -1' in text, text
        report = read('autoseq-report.json'); assert report['result'] == 'PASS'
        report['desktop_plays'] = plays; report['physical_source'] = source_name
        assert len(online) == 11 and (args.continuous or network_restored) and not pending_online
        report['live_desktop_decodes'] = online
        if args.continuous:
            assert report['scope'] == 'continuous' and service_verified
            assert report['automatic_navigation_activity_destruction_and_background_continue'] and report['automatic_complete_qso_with_screen_off']
            assert report['application_owned_completions_staged_once_and_fixtures_removed'] == 2
            assert set(report['guards']) == {'queued-stop', 'playing-stop', 'edit', 'clock-reset', 'focus-loss', 'navigation', 'recreation', 'service-stop', 'permission-loss', 'profile-browse', 'profile'}
            report['microphone_media_playback_foreground_service_verified'] = service_verified
        else:
            assert report['real_ntp_failure_warning_and_manual_recovery'] is True
        report['desktop_binary_sha256'] = hashlib.sha256(jt9.read_bytes()).hexdigest()
        validate_roles(report, plays)
        (output/'phone-report.json').write_text(json.dumps(report, indent=2))
        report['independent_captures'] = analyze(output, report, encoder, packer, decoder, jt9)
        assert len(report['independent_captures']) == 11
        (output/'report.json').write_text(json.dumps(report, indent=2))
        print('Automatic physical exchanges, loss/repeat limits, interruptions and independent decodes PASS.', flush=True)
    finally:
        restore_network()
        stop_recorder()
        partial = read('autoseq-report.json')
        if partial.get('run') == run:
            partial.update(desktop_plays=plays, live_desktop_decodes=online, physical_source=source_name)
            (output/'phone-checkpoint.json').write_text(json.dumps(partial, indent=2))
        if player is not None and player.poll() is None: player.terminate(); player.wait(timeout=3)
        if process is not None and process.poll() is None:
            adb('shell', 'am', 'force-stop', '--user', '0', PACKAGE, check=False); process.terminate(); process.wait(timeout=4)
        for path in output.rglob('*'):
            if path.is_file() and not os.access(path, os.X_OK): path.chmod(0o600)


if __name__ == '__main__':
    main()
