#!/usr/bin/env python3
"""Check clean/noisy recorded slots and invalid codewords against pinned desktop LDPC/CRC.

The desktop adapter has supplied time/frequency; it is not the full WSJT-CB search.
"""
import argparse
import array
import hashlib
import io
import json
import math
from pathlib import Path
import random
import subprocess
import sys
import tempfile
import wave

import wsjtcb_pcm

ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / 'reference/wsjtcb/recordings'


def samples(data):
    with wave.open(io.BytesIO(data), 'rb') as source:
        pcm = array.array('h', source.readframes(source.getnframes()))
    if sys.byteorder != 'little':
        pcm.byteswap()
    return [x / 32768 for x in pcm]


def wav(values):
    assert len(values) == 180000 and max(abs(x) for x in values) < 1
    pcm = array.array('h', (round(x * 32768) for x in values))
    if sys.byteorder != 'little':
        pcm.byteswap()
    buffer = io.BytesIO()
    with wave.open(buffer, 'wb') as output:
        output.setnchannels(1); output.setsampwidth(2); output.setframerate(12000)
        output.writeframes(pcm.tobytes())
    return buffer.getvalue()


def fixtures():
    original = ROOT / 'reference/wsjtcb/channel'
    corpus = json.loads((original / 'channel.json').read_text())
    packing = json.loads((ROOT / 'reference/wsjtcb/packing.json').read_text())
    payload_by_id = {row['id']: row['pack77']['payload77'] for row in packing['packing']}
    text_by_payload = {payload_by_id[step['case']]: step['decoded'] for case in packing['scenarios']
                       if case['id'].startswith('cold-') for step in case['steps'] if step['op'] == 'decode'}
    rows, files = [], {}
    for index, source in enumerate(corpus['waves']):
        data = (original / source['file']).read_bytes()
        assert hashlib.sha256(data).hexdigest() == source['sha256']
        clean = samples(data)
        rows.append(dict(file='../channel/' + source['file'], sha256=source['sha256'],
                         payload77=source['payload77'], text=text_by_payload[source['payload77']],
                         kind='clean', frequency_hz=1000, onset_seconds=.8))
        # Fixed moderate AWGN, not a sensitivity threshold. Keep headroom without clipping.
        signal = [x * .1 for x in clean]
        power = sum(x*x for x in signal[9600:161280]) / 151680
        snr = [-4, -8, -12, -8][index]
        sigma = math.sqrt(power * 6000 / (2500 * 10**(snr/10)))
        rng = random.Random(80700 + index)
        noisy = [x + rng.gauss(0, sigma) for x in signal]
        name = 'noisy-' + source['file']
        files[name] = wav(noisy)
        rows.append(dict(file=name, sha256=hashlib.sha256(files[name]).hexdigest(),
                         payload77=source['payload77'], text=text_by_payload[source['payload77']],
                         kind='awgn', frequency_hz=1000, onset_seconds=.8, expected_snr_db=snr,
                         seed=80700+index, noise_sigma=sigma))
    # Shift an independent desktop waveform to verify FFT-delay correction.
    base = samples((original / 'cq.wav').read_bytes())
    for onset in [.32, 1.28]:
        shifted = [0.] * 180000
        start = round(onset * 12000)
        shifted[start:start+151680] = base[9600:161280]
        name = 'onset-' + str(round(onset * 1000)) + '.wav'
        files[name] = wav(shifted)
        rows.append(dict(file=name, sha256=hashlib.sha256(files[name]).hexdigest(),
                         payload77=corpus['waves'][0]['payload77'], text='CQ 1AT106',
                         kind='shifted-clean', frequency_hz=1000, onset_seconds=onset))
    # Intact sync; splice half the data symbols from a different desktop codeword.
    corrupt = samples((original / 'cq.wav').read_bytes())
    other = samples((original / 'roger-report.wav').read_bytes())
    for symbol in range(7, 36):
        start = 9600 + symbol * 1920
        corrupt[start:start+1920] = other[start:start+1920]
    rng = random.Random(80999)
    for name, values, kind in [('corrupt-data.wav', corrupt, 'corrupt-data-with-intact-sync'),
                               ('noise-only.wav', [rng.gauss(0, .06) for _ in range(180000)], 'noise-only')]:
        files[name] = wav(values)
        rows.append(dict(file=name, sha256=hashlib.sha256(files[name]).hexdigest(), kind=kind,
                         payload77=None, frequency_hz=1000, onset_seconds=.8))
    manifest = dict(schema=1, revision=corpus['revision'], slot_start_utc='2026-11-06T12:00:00Z',
                    provenance='Desktop-generated source WAVs; deterministic AWGN or data-symbol splicing. '
                               'Reference LDPC/CRC uses known onset/frequency, not the full desktop search.',
                    cases=rows)
    return manifest, files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    parser.add_argument('--generate', action='store_true')
    args = parser.parse_args()
    manifest, files = fixtures()
    path = DIRECTORY / 'recordings.json'
    if args.generate:
        DIRECTORY.mkdir(parents=True, exist_ok=True)
        for name, data in files.items():
            (DIRECTORY / name).write_bytes(data)
        path.write_text(json.dumps(manifest, indent=2) + '\n')
    else:
        assert json.loads(path.read_text()) == manifest, 'Recording manifest differs'
        for name, data in files.items():
            assert (DIRECTORY / name).read_bytes() == data, name
    probe = args.build.resolve() / 'engine_probe'
    with tempfile.TemporaryDirectory(prefix='wsjtcb-recordings-') as temp:
        decoder = wsjtcb_pcm.compile_decoder(Path(temp))
        for case in manifest['cases']:
            source = DIRECTORY / case['file']
            desktop = subprocess.run([str(decoder), str(source), '1000', str(case['onset_seconds'])], capture_output=True, text=True, timeout=15)
            native = json.loads(subprocess.run([str(probe), 'decode', str(source)], check=True,
                                              capture_output=True, text=True, timeout=15).stdout)
            if case['payload77'] is None:
                assert desktop.returncode != 0, (case, desktop.stdout)
                assert native['frames'] == [], (case, native)
            else:
                assert desktop.returncode == 0, (case, desktop.stderr)
                fields = desktop.stdout.strip().split('|')
                assert fields[0] == case['payload77'] and fields[2] == case['text'], (case, fields)
                assert len(native['frames']) == 1, (case, native)
                frame = native['frames'][0]
                assert frame['payload77'] == case['payload77']
                assert abs(frame['time'] - case['onset_seconds']) <= .08001 and abs(frame['frequency'] - 1000) <= 6.25
                if case['kind'] == 'awgn':
                    assert frame['snr'] is not None and abs(frame['snr'] - case['expected_snr_db']) <= 3, (case, frame)
            print(case['file'] + ': PASS', flush=True)
    print('Recorded slots: 4 clean, 4 AWGN, 2 shifted onsets, intact-sync corrupted data and noise-only passed; AWGN SNR within 3 dB')


if __name__ == '__main__':
    main()
