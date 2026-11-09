#!/usr/bin/env python3
"""Qualify one even and one odd bench slot on an unlocked Android phone.

Install matching CI app/test/probe APKs first. Linux needs libpulse-simple,
PipeWire wpctl, a physical microphone near the phone and an NTP-synchronized host.
The phone measures real NTP without editing saved time settings. No radio is used.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();args.output.mkdir(mode=0o700,parents=True,exist_ok=False)
    status=subprocess.check_output(['timedatectl','show','-p','NTPSynchronized','--value'],text=True,timeout=5).strip()
    if status!='yes': raise RuntimeError('Synchronize the host clock before timing qualification.')
    source=subprocess.check_output(['wpctl','inspect','@DEFAULT_AUDIO_SOURCE@'],text=True,timeout=5)
    source_name=re.search(r'node.name = "([^"]+)"',source).group(1)
    if not source_name.startswith('alsa_input.'): raise RuntimeError('Select a physical ALSA microphone as the default source.')
    env=dict(os.environ,ADB_LIBUSB='1');package='net.vash.ft8cbdroid';run=uuid.uuid4().hex
    def adb(*command,check=True):return subprocess.run(['adb',*command],env=env,capture_output=True,timeout=12,check=check).stdout
    def read(name):
        try:return json.loads(adb('shell','run-as',package,'cat','cache/'+name,check=False))
        except ValueError:return {}
    process=None;recorder=None;captured=False
    def stop_recorder():
        nonlocal recorder
        if recorder is not None:
            recorder.terminate()
            try:recorder.wait(timeout=4)
            except subprocess.TimeoutExpired:recorder.kill();recorder.wait(timeout=2);raise RuntimeError('Capture did not stop cleanly.')
            if recorder.returncode!=0:raise RuntimeError('Capture failed; inspect recorder.log.')
            recorder=None
    try:
        with (args.output/'instrumentation.log').open('w') as log,(args.output/'recorder.log').open('w') as audio_log:
            process=subprocess.Popen(['adb','shell','am','instrument','--user','0','-w','-r','-e','scenario','scheduled',
                '-e','preview_run',run,package+'.test/'+package+'.validation.WaveformPhoneChecks'],env=env,stdout=log,stderr=subprocess.STDOUT)
            end=time.monotonic()+240
            while process.poll() is None:
                if time.monotonic()>end:raise TimeoutError('Scheduled checks exceeded 240 seconds.')
                if not captured and read('r15-ready.json').get('run')==run:
                    recorder=subprocess.Popen([sys.executable,str(Path(__file__).with_name('audio_timing_capture.py')),
                        '--wav',str(args.output/'scheduled.wav'),'--seconds','180'],stdout=audio_log,stderr=subprocess.STDOUT)
                    until=time.monotonic()+5
                    while not (args.output/'scheduled.ready.json').exists():
                        if recorder.poll() is not None or time.monotonic()>until:raise RuntimeError('Microphone recorder failed to start.')
                        time.sleep(.1)
                    adb('shell','run-as',package,'touch','cache/r15-go-'+run);captured=True
                    print('Physical timestamped microphone capture started.',flush=True)
                if recorder is not None and read('r15-captures-complete.json').get('run')==run:
                    time.sleep(.5);stop_recorder();print('Even/odd captures complete; checking interruption paths.',flush=True)
                time.sleep(.2)
            stop_recorder()
        log=(args.output/'instrumentation.log').read_text()
        assert process.returncode==0 and 'INSTRUMENTATION_RESULT: scheduled_result=PASS' in log and 'INSTRUMENTATION_CODE: -1' in log,log
        report=read('r15-report.json');assert report['run']==run and captured
        line=next(line.split('scheduled_report=',1)[1] for line in log.splitlines() if 'INSTRUMENTATION_RESULT: scheduled_report=' in line)
        assert json.loads(line)==report
        report['physical_source']=source_name;report['host_ntp_synchronized']=True
        (args.output/'report.json').write_text(json.dumps(report,indent=2))
        print('Phone scenarios passed; independently analyze the recorded onset against UTC.',flush=True)
    finally:
        stop_recorder()
        if process is not None and process.poll() is None:
            adb('shell','am','force-stop','--user','0',package,check=False);process.terminate();process.wait(timeout=4)
        for path in args.output.iterdir():
            if path.is_file():path.chmod(0o600)


if __name__=='__main__':main()
