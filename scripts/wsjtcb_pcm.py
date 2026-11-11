#!/usr/bin/env python3
"""Qualify generated clean PCM using pinned desktop LDPC/CRC and known-time demodulation."""
import argparse
import array
import hashlib
import json
import math
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import wave

import wsjtcb_channel as channel

ROOT = Path(__file__).resolve().parents[1]
REF = ROOT / 'reference/wsjtcb'


def compile_decoder(directory):
    base = channel.load_sources()
    manifest = json.loads((REF / 'pcm-sources.json').read_text())
    assert manifest['schema'] == 1 and manifest['revision'] == base['revision'] and manifest['repository'] == base['repository']
    for file in manifest['files']:
        assert hashlib.sha256((REF / file['local']).read_bytes()).hexdigest() == file['sha256'], file['path']
    cpp = REF / 'source/channel/lib/crc14.cpp'
    boost = ['-I', os.environ['BOOST_INCLUDEDIR']] if 'BOOST_INCLUDEDIR' in os.environ else []
    subprocess.run(shlex.split(os.environ.get('CXX', 'g++')) + boost +
                   ['-O1', '-c', str(cpp), '-o', str(directory / 'crc14.o')], check=True, timeout=45)
    sources = [str(REF / f['local']) for f in base['files'] if f['local'] != 'source/pfx.f90']
    sources += [str(REF / 'source/channel/lib/crc.f90')]
    sources += [str(REF / f['local']) for f in manifest['files'] if not f['path'].endswith('parity.f90')]
    executable = directory / 'pcm_decode'
    subprocess.run(shlex.split(os.environ.get('FC', 'gfortran')) + shlex.split(os.environ.get('FFLAGS', '')) +
                   ['-O1', '-fwrapv', '-fcheck=all', '-ffunction-sections', '-fdata-sections', '-Wl,--gc-sections',
                    '-I', str(REF / 'source'), '-I', str(REF / 'source/pcm/lib/ft8var')] + sources +
                   [str(REF / 'harness/pcm_decode.f90'), str(directory / 'crc14.o'), '-lstdc++', '-o', str(executable)],
                   cwd=directory, check=True, timeout=60)
    return executable


def write_wave(path, samples):
    # Match the Kotlin writer: symmetric PCM16 quantization (32767 peak).
    pcm = array.array('h', (math.floor(float(x) * 32767 + .5) for x in samples))
    if sys.byteorder != 'little':
        pcm.byteswap()
    with wave.open(str(path), 'wb') as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(12000)
        output.writeframes(pcm.tobytes())


def qualify(path, frequency, level, decoder, expected_payload, expected_tones):
    with wave.open(str(path), 'rb') as source:
        assert (source.getnchannels(), source.getsampwidth(), source.getframerate(), source.getnframes()) == (1, 2, 12000, 180000)
        pcm = array.array('h', source.readframes(180000))
    if sys.byteorder != 'little':
        pcm.byteswap()
    assert all(x == 0 for x in pcm[:9600]) and all(x == 0 for x in pcm[161280:])
    peak = max(abs(x) for x in pcm) / 32767
    assert abs(peak - level) < .0001, (peak, level)
    rms = math.sqrt(sum((x / 32767) ** 2 for x in pcm[9600:161280]) / 151680)
    assert abs(rms / level - 1 / math.sqrt(2)) < .005, rms
    for i in range(240):
        bound = level * (1 - math.cos(math.pi * i / 240)) / 2 + 2 / 32767
        assert abs(pcm[9600 + i] / 32767) <= bound
        assert abs(pcm[161279 - i] / 32767) <= bound
    result = subprocess.run([str(decoder), str(path), str(frequency)], check=True,
                            capture_output=True, text=True, timeout=15).stdout.strip()
    bits, tones, text, leakage = result.split('|')
    assert bits == expected_payload and tones == expected_tones, result
    assert float(leakage) < .02, leakage
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path, help='Native host build directory')
    parser.add_argument('--wave', type=Path, help='Also qualify a phone-exported WAV')
    parser.add_argument('--payload', help='Expected 77 bits for --wave')
    parser.add_argument('--frequency', type=float, default=1000)
    parser.add_argument('--level', type=float, default=.4)
    args = parser.parse_args()
    probe = args.build.resolve() / 'engine_probe'
    corpus = json.loads((REF / 'channel/channel.json').read_text())
    tones_by_payload = {x['payload77']: x['tones79'] for x in corpus['vectors']}
    packing = json.loads((REF / 'packing.json').read_text())
    ids = {'cq-1AT106', 'to-26AT101', 'from-26AT101', 'one-26AT101-Rminus10',
           'free-text', 'cq-1A1', 'standard-final'}
    cases = [x for x in packing['packing'] if x['id'] in ids]
    assert {x['id'] for x in cases} == ids
    cold = {s['id'].removeprefix('cold-'): s['steps'][0]['decoded']
            for s in packing['scenarios'] if s['id'].startswith('cold-')}
    with tempfile.TemporaryDirectory(prefix='wsjtcb-pcm-') as temp:
        directory = Path(temp)
        decoder = compile_decoder(directory)
        count = 0
        for case in cases:
            bits = case['pack77']['payload77']
            for frequency, level in [(200, .01), (1000, .4), (2900, 1)]:
                raw, wav = directory / 'generated.f32', directory / 'generated.wav'
                subprocess.run([str(probe), 'wave', bits, str(frequency), str(level), str(raw)], check=True, timeout=15)
                samples = array.array('f', raw.read_bytes())
                if sys.byteorder != 'little':
                    samples.byteswap()
                assert len(samples) == 180000 and all(math.isfinite(x) and abs(x) <= 1 for x in samples)
                write_wave(wav, samples)
                assert qualify(wav, frequency, level, decoder, bits, tones_by_payload[bits]) == cold[case['id']]
                native_result = subprocess.run([str(probe), 'decode', str(wav)], check=True,
                                               text=True, capture_output=True, timeout=15)
                assert [f['payload77'] for f in json.loads(native_result.stdout)['frames']] == [bits]
                count += 1
        for frequency, level in [('nan', '.4'), ('199', '.4'), ('2901', '.4'), ('1000', 'nan'), ('1000', '0'), ('1000', '1.01')]:
            invalid = subprocess.run([str(probe), 'wave', cases[0]['pack77']['payload77'], frequency, level,
                                      str(directory / 'invalid.f32')], capture_output=True, timeout=5)
            assert invalid.returncode != 0
        # A physical-channel check must permit corrected hard-decision errors,
        # while clean waveform qualification keeps its strict sync/tone checks.
        clean = array.array('h')
        with wave.open(str(wav), 'rb') as source:
            clean.frombytes(source.readframes(180000))
        if sys.byteorder != 'little': clean.byteswap()
        damaged = [x / 32768 for x in clean]
        for symbol in [0, 12]:
            start = 9600 + symbol * 1920
            wrong = (int(tones_by_payload[bits][symbol]) + 1) % 8
            for j in range(1920):
                damaged[start+j] = .4 * math.sin(2*math.pi*(2900+wrong*6.25)*j/12000)
        noisy = directory / 'correctable-symbol-errors.wav'
        write_wave(noisy, damaged)
        assert subprocess.run([str(decoder), str(noisy), '2900'], capture_output=True, timeout=15).returncode != 0
        recovered = subprocess.check_output([str(decoder), str(noisy), '2900', '.8', 'acoustic'], text=True, timeout=15).strip().split('|')
        assert recovered[0] == bits and recovered[1] != tones_by_payload[bits]
        silent = directory / 'silence.wav'
        write_wave(silent, [0] * 180000)
        assert subprocess.run([str(decoder), str(silent), '1000'], capture_output=True, timeout=15).returncode != 0
        assert subprocess.run([str(decoder), str(wav), '1800'], capture_output=True, timeout=15).returncode != 0
        if args.wave:
            assert args.payload in tones_by_payload, 'Expected payload must be in the pinned corpus'
            text = qualify(args.wave, args.frequency, args.level, decoder, args.payload, tones_by_payload[args.payload])
            print('Exported WAV passed desktop channel checks:', text)
    print(f'Generated PCM: {count} cases passed pinned desktop LDPC/CRC, tones, envelope, duration, peak/RMS and spectral probes')


if __name__ == '__main__':
    main()
