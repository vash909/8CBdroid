#!/usr/bin/env python3
"""Reproduce the R09 finite profile corpus and report per-profile observations (not decoder parity)."""
import argparse
import array
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import wave

import wsjtcb_recordings as recorded

ROOT = Path(__file__).resolve().parents[1]
REF = ROOT / 'reference/wsjtcb'


def stimulus(clean, snr, seed, tone=0):
    source = recorded.samples(clean) if clean else None
    gain = .02
    power = sum((x*gain)**2 for x in source[9600:161280])/151680 if source else None
    sigma = math.sqrt(power*6000/(2500*10**(snr/10))) if snr is not None else .06
    state = seed
    result = []
    for i in range(180000):
        total = 0
        for _ in range(12):
            state = (state ^ (state << 13)) & 0xffffffff
            state ^= state >> 17
            state = (state ^ (state << 5)) & 0xffffffff
            total += (state >> 8)/16777216
        value = (source[i]*gain if source else 0) + (total-6)*sigma
        if tone:
            value += .05*math.sin(2*math.pi*tone*i/12000)
        assert abs(value) < 1, 'Benchmark stimulus clipped'
        result.append(math.floor(value*32768+.5)/32768)
    return recorded.wav(result)


def generate():
    original = json.loads((REF/'channel/channel.json').read_text())
    rows = []
    for case in json.loads((REF/'recordings/recordings.json').read_text())['cases']:
        name = Path(case['file']).name
        rows.append(dict(id='recorded-'+name, file=name, sha256_pcm=hashlib.sha256((REF/'recordings'/case['file']).read_bytes()[44:]).hexdigest(),
                         payload77=case['payload77'], kind='negative' if case['payload77'] is None else 'recorded'))
    for source in original['waves'][:3]:
        for snr in [-8,-12,-16,-20]:
            for seed in [901,902]:
                rows.append(dict(id=f"sweep-{source['file']}-{snr}-{seed}",file=source['file'],snr_db=snr,seed=seed,
                                 payload77=source['payload77'],kind='sweep'))
    for seed, tone in [(903,0),(904,0),(905,300),(906,1500)]:
        rows.append(dict(id=f'negative-{seed}-{tone}',seed=seed,tone_hz=tone,payload77=None,kind='negative-synthetic'))
    for case in rows:
        if case['kind'] in ('sweep','negative-synthetic'):
            data=stimulus((REF/'channel'/case['file']).read_bytes() if case['kind']=='sweep' else None,
                          case.get('snr_db'),case['seed'],case.get('tone_hz',0))
            case['sha256_pcm']=hashlib.sha256(data[44:]).hexdigest()
    return dict(schema=1,revision=original['revision'],baseline_phone='SM-A515F / Android 13 / arm64-v8a',
                noise='XorShift32; 12 summed U[0,1) draws minus 6; approximate Gaussian, not exact AWGN',
                gain=.02,profiles=[dict(name=n,candidates=c,iterations=i,deadline_ms=d) for n,c,i,d in
                                   [('Fast',24,12,2000),('Standard',60,25,4000)]],cases=rows)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build',type=Path)
    parser.add_argument('--generate',action='store_true')
    parser.add_argument('--report',type=Path)
    args=parser.parse_args()
    manifest=generate()
    path=REF/'recordings/benchmark.json'
    if args.generate:path.write_text(json.dumps(manifest,indent=2)+'\n')
    else:assert json.loads(path.read_text())==manifest,'Benchmark corpus/profiles differ'
    probe=args.build.resolve()/'engine_probe'
    results=[]
    with tempfile.TemporaryDirectory(prefix='ft8-benchmark-') as temp:
        for case in manifest['cases']:
            if case['kind'] in ('recorded','negative'):
                directory=REF/'channel' if (REF/'channel'/case['file']).exists() else REF/'recordings'
                data=(directory/case['file']).read_bytes()
            else:
                data=stimulus((REF/'channel'/case['file']).read_bytes() if case['kind']=='sweep' else None,
                              case.get('snr_db'),case['seed'],case.get('tone_hz',0))
            assert hashlib.sha256(data[44:]).hexdigest()==case['sha256_pcm']
            wav=Path(temp)/'slot.wav';wav.write_bytes(data)
            for profile, settings in enumerate(manifest['profiles']):
                start=time.perf_counter()
                decoded=json.loads(subprocess.run([str(probe),'decode',str(wav),str(profile)],
                                                   capture_output=True,text=True,check=True,timeout=12).stdout)
                elapsed=(time.perf_counter()-start)*1000
                frames=[x['payload77'] for x in decoded['frames']]
                assert decoded['candidates']<=settings['candidates'] and decoded['processed']<=settings['candidates']
                wrong=[x for x in frames if x!=case['payload77']]
                assert not wrong,(case['id'],settings['name'],wrong)
                if case['kind']=='recorded' and (case['file'] in ('cq.wav','leading-hash.wav','trailing-hash.wav','roger-report.wav') or profile==1):
                    assert frames==[case['payload77']],(case['id'],settings['name'])
                results.append(dict(case=case['id'],profile=settings['name'],detected=case['payload77'] in frames,
                                    false_frames=len(wrong),elapsed_ms=round(elapsed,3)))
    report=dict(scope='Host CLI wall time includes process startup; no phone/desktop parity claim.',results=results)
    if args.report:args.report.write_text(json.dumps(report,indent=2)+'\n')
    for settings in manifest['profiles']:
        rows=[r for r in results if r['profile']==settings['name']]
        print(settings['name'],f"{len(rows)} cases; {sum(r['detected'] for r in rows)} detected; no unexpected payloads; max {max(r['elapsed_ms'] for r in rows):.1f} ms",flush=True)


if __name__=='__main__':main()
