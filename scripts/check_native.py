#!/usr/bin/env python3
"""Check native channel coding against independent WSJT-CB fixtures."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def run(executable, *args):
    return subprocess.run([str(executable), *args], check=True, text=True,
                          capture_output=True, timeout=15).stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build', type=Path)
    args = parser.parse_args()
    probe = args.build.resolve() / 'engine_probe'
    codec = args.build.resolve() / 'codec_probe'
    directory = ROOT / 'reference/wsjtcb/channel'
    corpus = json.loads((directory / 'channel.json').read_text())
    vendor = ROOT / 'native/third_party/ft8_lib'
    manifest = json.loads((vendor / 'manifest.json').read_text())
    for file in manifest['files']:
        assert hashlib.sha256((vendor / file['path']).read_bytes()).hexdigest() == file['sha256'], file['path']
    # Invert Gray coding to check all 174 encoded bits, not only the 79 tones.
    gray = [0, 1, 3, 2, 5, 6, 4, 7]
    for vector in corpus['vectors']:
        tones = run(probe, 'encode', vector['payload77'])
        assert tones == vector['tones79'], vector
        data = tones[7:36] + tones[43:72]
        word = ''.join(f'{gray.index(int(t)):03b}' for t in data)
        assert word == vector['codeword174'], vector
    for fixture in corpus['waves']:
        path = directory / fixture['file']
        assert hashlib.sha256(path.read_bytes()).hexdigest() == fixture['sha256']
        result = json.loads(run(probe, 'decode', str(path)))
        assert len(result['frames']) == 1, result
        frame = result['frames'][0]
        assert frame['payload77'] == fixture['payload77'], frame
        assert abs(frame['frequency'] - fixture['frequency_hz']) <= 6.25, frame
        assert abs(frame['time'] - fixture['lead_seconds']) <= .16, frame
    bits = corpus['waves'][0]['payload77']
    assert [f['payload77'] for f in json.loads(run(probe, 'roundtrip', bits))['frames']] == [bits]
    assert json.loads(run(probe, 'silence'))['frames'] == []
    invalid = subprocess.run([str(probe), 'encode', 'invalid'], capture_output=True, timeout=5)
    assert invalid.returncode != 0
    packing = json.loads((ROOT / 'reference/wsjtcb/packing.json').read_text())
    mismatches = []
    for case in packing['packing']:
        status, actual = run(codec, case['input']).split('|')
        if status != '0' or actual != case['pack77']['payload77']:
            mismatches.append(case['id'])
    print(f"Native channel: {len(corpus['vectors'])} desktop payload/codeword/tone matches; "
          f"{len(corpus['waves'])} independent WAV decodes; roundtrip and silence passed")
    print(f"Upstream amateur text codec: {len(mismatches)}/{len(packing['packing'])} CB mismatches (excluded from Android)")
    print('Mismatched cases: ' + ', '.join(mismatches))


if __name__ == '__main__':
    main()
