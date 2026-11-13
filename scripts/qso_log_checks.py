#!/usr/bin/env python3
"""Qualify one real acoustic completion, durable log/UI recovery and Worked reception.

Requires matching CI app/test APKs, an unlocked authorized phone with station
1AT106/Phone profile and working NTP, synchronized host, physical PC speakers
and microphone, original reference compilers, NumPy/SciPy and installed WSJT-CB
jt9. Recordings and reports are private. Run-owned log fixtures are removed by ID.
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

import wsjtcb_channel as channel
import wsjtcb_packing as packing
import wsjtcb_pcm as pcm_reference
from autoseq_audio_checks import analyze, original_payload

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'net.vash.ft8cbdroid'
TEXTS = {'cq': 'CQ 26AT101', 'report': '1AT106 -07', 'final': '1AT106 RR73', 'unknown': 'CQ 999ZZ999'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--jt9', required=True, type=Path)
    args = parser.parse_args(); output = args.output.resolve(); jt9 = args.jt9.resolve()
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    assert jt9.is_file() and os.access(jt9, os.X_OK)
    assert subprocess.check_output(['timedatectl', 'show', '-p', 'NTPSynchronized', '--value'], text=True, timeout=5).strip() == 'yes'
    source = subprocess.check_output(['wpctl', 'inspect', '@DEFAULT_AUDIO_SOURCE@'], text=True, timeout=5)
    source_name = re.search(r'node.name = "([^"]+)"', source).group(1)
    assert source_name.startswith('alsa_input.'), 'Use the physical PC microphone, not a monitor.'
    reference = output/'reference'; reference.mkdir()
    encoder, manifest = channel.compile_reference(reference)
    packer = packing.compile_reference(reference, manifest, 'O0'); decoder = pcm_reference.compile_decoder(reference)
    waves = {}
    for key, text in TEXTS.items():
        path = reference/(key+'.wav'); path.write_bytes(channel.make_wave(encoder, original_payload(packer, text), reference)); waves[key] = path
    env = dict(os.environ, ADB_LIBUSB='1'); run = uuid.uuid4().hex
    def adb(*command, check=True):
        return subprocess.run(['adb', *command], env=env, check=check, capture_output=True, timeout=15).stdout
    def read(name):
        try: return json.loads(adb('exec-out', 'run-as', PACKAGE, 'cat', 'cache/'+name+'.json', check=False))
        except ValueError: return {}
    def command(scenario):
        return ['adb', 'shell', 'am', 'instrument', '--user', '0', '-w', '-r', '-e', 'scenario', scenario,
                '-e', 'preview_run', run, PACKAGE+'.test/'+PACKAGE+'.validation.WaveformPhoneChecks']
    process = recorder = player = None; schedule = []; stages = set(); handled = set(); plays = []; sent = []
    cleaned = False
    def stop_recording():
        nonlocal recorder
        if recorder is not None:
            recorder.terminate(); recorder.wait(timeout=5); assert recorder.returncode == 0; recorder = None
    def interrupt(*_): raise KeyboardInterrupt('Log qualification interrupted')
    signal.signal(signal.SIGTERM, interrupt)
    try:
        with (output/'instrumentation.log').open('w') as log, (output/'audio.log').open('w') as audio:
            process = subprocess.Popen(command('logbook'), env=env, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic()+780
            while process.poll() is None:
                assert time.monotonic() < deadline, 'Log/UI qualification exceeded 780 seconds'
                if recorder is not None: assert recorder.poll() is None, 'Recording ended before the complete observation window'
                if player is not None and player.poll() is not None:
                    assert player.returncode == 0; player = None
                if schedule and time.time() >= schedule[0][1]:
                    key, target, stage = schedule.pop(0)
                    assert time.time()-target < .2 and player is None
                    player = subprocess.Popen(['pw-play', '--volume', '0.5', str(waves[key])], stdout=audio, stderr=subprocess.STDOUT)
                    plays.append(dict(text=TEXTS[key], utc_millis=round(target*1000), launched_utc=time.time(), stage=stage))
                    print('Actual desktop input:', stage, TEXTS[key], flush=True)
                critical = bool(schedule and schedule[0][1]-time.time() < .6)
                if not critical:
                    ready = read('logbook-ready'); stage = ready.get('stage')
                    if ready.get('run') == run and stage not in stages:
                        stages.add(stage); target = (int((time.time()+4)//15)+1)*15
                        if stage == 'answering':
                            directory = output/'answering'; directory.mkdir()
                            recorder = subprocess.Popen([sys.executable, str(ROOT/'scripts/audio_timing_capture.py'), '--wav', str(directory/'scheduled.wav'), '--seconds', '240'], stdout=audio, stderr=subprocess.STDOUT)
                            until = time.monotonic()+5
                            while not (directory/'scheduled.ready.json').exists():
                                assert recorder.poll() is None and time.monotonic() < until; time.sleep(.05)
                            schedule.append(('cq', target, stage))
                        else:
                            assert stage == 'worked' and not schedule
                            TEXTS['unknown'] = 'CQ '+ready['unworked_call']
                            waves['unknown'].write_bytes(channel.make_wave(encoder, original_payload(packer, TEXTS['unknown']), reference))
                            schedule += [('cq', target, stage), ('unknown', target+30, stage)]
                        print('Phone stage:', stage, flush=True)
                    progress = read('logbook-progress')
                    if progress.get('run') == run:
                        sent = progress['sent']
                        for index, item in enumerate(sent):
                            if index in handled: continue
                            handled.add(index)
                            assert item['frames'] == 161280
                            print('Confirmed phone output:', item['text'], flush=True)
                            key = {0: 'report', 1: 'final'}.get(index)
                            if key is not None:
                                target = item['utc_millis']/1000+15
                                assert target-time.time() > .25, 'Host missed the next peer response deadline'
                                schedule.append((key, target, 'answering')); schedule.sort(key=lambda s: s[1])
                    if len(sent) == 3 and recorder is not None and time.time() >= sent[-1]['utc_millis']/1000+16: stop_recording()
                time.sleep(.02 if critical else .08)
            stop_recording()
        log = (output/'instrumentation.log').read_text(); report = read('logbook-report')
        (output/'phone-report.json').write_text(json.dumps(report, indent=2))
        assert process.returncode == 0 and 'logbook_result=RESTART_READY' in log and report.get('run') == run and report['result'] == 'RESTART_READY', log+'\n'+str(report)
        assert len(sent) == 3 and stages == {'answering', 'worked'} and len(plays) == 5 and not schedule
        adb('shell', 'am', 'force-stop', '--user', '0', PACKAGE)
        reopened = subprocess.run(command('logbook-reopen'), env=env, capture_output=True, text=True, timeout=120)
        (output/'reopen.log').write_text(reopened.stdout+reopened.stderr)
        reopen = read('logbook-reopen-report'); (output/'reopen-report.json').write_text(json.dumps(reopen, indent=2))
        assert reopened.returncode == 0 and 'logbook_result=PASS' in reopened.stdout and reopen.get('run') == run and reopen['result'] == 'PASS', reopened.stdout+'\n'+str(reopen)
        cleaned = True
        report['independent_captures'] = analyze(output, report, encoder, packer, decoder, jt9)
        assert len(report['independent_captures']) == 3
        report.update(result='PASS', desktop_plays=plays, reopen=reopen, physical_source=source_name,
                      desktop_binary_sha256=hashlib.sha256(jt9.read_bytes()).hexdigest())
        (output/'report.json').write_text(json.dumps(report, indent=2))
        print('Actual acoustic completion, independent desktop decodes, SQLite/UI recovery, Worked and cold-process persistence PASS.', flush=True)
    finally:
        stop_recording()
        if player is not None and player.poll() is None: player.terminate(); player.wait(timeout=3)
        if process is not None and process.poll() is None:
            adb('shell', 'am', 'force-stop', '--user', '0', PACKAGE, check=False); process.terminate(); process.wait(timeout=4)
        if not cleaned:
            partial = read('logbook-report')
            if partial.get('run') == run: (output/'partial-report.json').write_text(json.dumps(partial, indent=2))
            cleanup = subprocess.run(command('logbook-cleanup'), env=env, capture_output=True, text=True, timeout=60)
            (output/'cleanup.log').write_text(cleanup.stdout+cleanup.stderr)
            if cleanup.returncode != 0 or 'logbook_result=PASS' not in cleanup.stdout:
                print('Fixture cleanup needs review; inspect the private cleanup log.', flush=True)
        for path in output.rglob('*'):
            if path.is_file() and not os.access(path, os.X_OK): path.chmod(0o600)


if __name__ == '__main__': main()
