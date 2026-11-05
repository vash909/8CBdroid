#!/usr/bin/env python3
"""Generate/check independent desktop FT8 channel vectors and clean audio slots."""
import argparse
import array
import hashlib
import io
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import wave

import wsjtcb_packing as packing

ROOT = Path(__file__).resolve().parents[1]
REF = ROOT / 'reference/wsjtcb'
FIXTURES = REF / 'channel'
MANIFEST = REF / 'channel-sources.json'
HARNESS = REF / 'harness/channel.f90'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load_sources():
    base, _ = packing.load_inputs()
    extra = json.loads(MANIFEST.read_text())
    if extra['schema'] != 1 or extra['repository'] != base['repository'] or extra['revision'] != base['revision']:
        raise ValueError('Channel reference revision mismatch')
    for file in extra['files']:
        if digest((REF / file['local']).read_bytes()) != file['sha256']:
            raise ValueError('Channel source checksum mismatch: ' + file['path'])
    return base


def compile_reference(directory):
    base = load_sources()
    fc = shlex.split(os.environ.get('FC', 'gfortran')) + shlex.split(os.environ.get('FFLAGS', ''))
    cxx = shlex.split(os.environ.get('CXX', 'g++'))
    boost = ['-I', os.environ['BOOST_INCLUDEDIR']] if 'BOOST_INCLUDEDIR' in os.environ else []
    cpp = REF / 'source/channel/lib/crc14.cpp'
    subprocess.run(cxx + boost + ['-O0', '-c', str(cpp), '-o', str(Path(directory)/'crc14.o')],
                   cwd=directory, check=True, timeout=45)
    sources = [str(REF/f['local']) for f in base['files'] if f['local'] != 'source/pfx.f90']
    sources += [str(REF/'source/channel'/p) for p in ['lib/crc.f90', 'lib/ft8/encode174_91.f90',
                'lib/ft8/genft8.f90', 'lib/timer_module.f90', 'lib/ft2/gfsk_pulse.f90', 'lib/ft8/gen_ft8wave.f90']]
    executable = Path(directory)/'channel'
    subprocess.run(fc + ['-O0', '-fwrapv', '-fcheck=all', '-ffunction-sections',
                        '-fdata-sections', '-Wl,--gc-sections', '-I', str(REF/'source'), '-I',
                        str(REF/'source/channel/lib/ft8')] + sources + [str(HARNESS),
                        str(Path(directory)/'crc14.o'), '-lstdc++', '-o', str(executable)],
                   cwd=directory, check=True, timeout=60)
    return executable, base


def make_wave(executable, bits, directory):
    raw = Path(directory)/'wave.f32'
    subprocess.run([str(executable), str(raw)], input=bits+'\n', text=True,
                   capture_output=True, check=True, timeout=15)
    samples = array.array('f', raw.read_bytes())
    if sys.byteorder != 'little':
        samples.byteswap()
    pcm = array.array('h', [0]*9600)
    pcm.extend(round(value*13107.2) for value in samples)
    pcm.extend([0]*(180000-len(pcm)))
    if sys.byteorder != 'little':
        pcm.byteswap()
    stream = io.BytesIO()
    with wave.open(stream, 'wb') as wav:
        wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(12000); wav.writeframes(pcm.tobytes())
    return stream.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    inputs = [REF/'packing.json', REF/'qso.json']
    p, q = [json.loads(path.read_text()) for path in inputs]
    bits = sorted({row['pack77']['payload77'] for row in p['packing']}
                  | {row['payload77'] for row in q['message_payloads']})
    with tempfile.TemporaryDirectory(prefix='wsjtcb-channel-') as directory:
        executable, base = compile_reference(directory)
        result = subprocess.run([str(executable)], input='\n'.join(bits)+'\n', text=True,
                                capture_output=True, check=True, timeout=20)
        vectors = []
        for line in result.stdout.splitlines():
            payload, codeword, tones = line.split('|')
            if len(codeword)!=174 or len(tones)!=79:
                raise ValueError('Invalid desktop channel output')
            vectors.append({'payload77': payload, 'codeword174': codeword, 'tones79': tones})
        if len(vectors)!=len(bits):
            raise ValueError('Incomplete channel corpus')
        by_id = {row['id']: row['pack77']['payload77'] for row in p['packing']}
        waves = {name+'.wav': make_wave(executable, by_id[case], directory) for name, case in [
                 ('cq', 'cq-1AT106'), ('leading-hash', 'to-26AT101'),
                 ('trailing-hash', 'from-26AT101'), ('roger-report', 'one-26AT101-Rminus10')]}
    metadata = {'schema': 1, 'revision': base['revision'], 'sources_sha256': digest(MANIFEST.read_bytes()),
                'harness_sha256': digest(HARNESS.read_bytes()),
                'inputs_sha256': {path.name: digest(path.read_bytes()) for path in inputs},
                'vectors': vectors,
                'waves': [{'file': name, 'payload77': by_id[case], 'sha256': digest(waves[name]),
                           'sample_rate_hz': 12000, 'samples': 180000, 'frequency_hz': 1000,
                           'lead_seconds': 0.8} for name, case in [
                           ('cq.wav','cq-1AT106'),('leading-hash.wav','to-26AT101'),
                           ('trailing-hash.wav','from-26AT101'),('roger-report.wav','one-26AT101-Rminus10')]]}
    FIXTURES.mkdir(parents=True, exist_ok=True)
    path = FIXTURES/'channel.json'
    if args.check:
        expected = json.loads(path.read_text())
        for actual, old in zip(metadata['waves'], expected['waves']):
            committed = (FIXTURES/old['file']).read_bytes()
            if digest(committed) != old['sha256']:
                raise ValueError('Committed desktop WAV checksum mismatch')
            # libm/Fortran versions may differ by a few PCM quantization units.
            a = array.array('h', waves[actual['file']][44:])
            b = array.array('h', committed[44:])
            if len(a)!=len(b) or max(abs(x-y) for x,y in zip(a,b))>8:
                raise ValueError('Regenerated desktop waveform differs')
            actual['sha256'] = old['sha256']
        if metadata != expected:
            raise ValueError('Desktop channel fixture differs')
        print(f'Desktop channel verified: {len(vectors)} payloads and {len(waves)} WAVs')
    else:
        for name,data in waves.items():(FIXTURES/name).write_bytes(data)
        path.write_text(json.dumps(metadata,indent=2)+'\n')
        print(f'Desktop channel generated: {len(vectors)} payloads and {len(waves)} WAVs')


if __name__ == '__main__':
    try:main()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f'Channel reference failed: {error}', file=sys.stderr); sys.exit(1)
