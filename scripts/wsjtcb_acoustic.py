#!/usr/bin/env python3
"""Derive acoustic exchange payloads and session text from pinned WSJT-CB sources."""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile

import wsjtcb_packing as packing

ROOT = Path(__file__).resolve().parents[1]
CASES = ROOT / 'reference/wsjtcb/acoustic-cases.json'
FIXTURE = ROOT / 'app/src/androidTest/assets/acoustic.json'


def generate():
    manifest, _ = packing.load_inputs()
    cases = json.loads(CASES.read_text())
    assert cases['schema'] == 1 and cases['station'] == '1AT106'
    steps = cases['steps']
    assert len({s['id'] for s in steps}) == len(steps)
    with tempfile.TemporaryDirectory(prefix='wsjtcb-acoustic-') as temp:
        executable = packing.compile_reference(temp, manifest, 'O0')
        commands = ['S|' + cases['station']]
        result = []
        for step in steps:
            row = dict(step)
            if row['action'] == 'reset':
                commands = ['S|' + cases['station']]
            else:
                assert row['action'] in ('send', 'receive')
                assert packing.ascii_text(row['message'], 37)
                bits = packing.run(executable, ['P|' + row['message']])[0].split('|')[2]
                assert len(bits) == 77 and set(bits) <= {'0', '1'}
                row['payload77'] = bits
                if row['action'] == 'send':
                    # A real output closes input; the reopened receiver has a new hash table.
                    commands = ['S|' + cases['station']]
                    success, text = packing.run(executable, ['D|' + bits])[0].split('|', 1)
                    assert success == '1', row['id']
                    row['desktop_cold_text'] = text.strip()
                else:
                    commands += ['D|' + bits, 'C|']
                    success, text = packing.run(executable, commands)[-1].split('|', 1)
                    assert success == '1', row['id']
                    row['expected_text'] = text.strip()
            result.append(row)
    return dict(schema=1, revision=manifest['revision'], station=cases['station'],
                cases_sha256=hashlib.sha256(CASES.read_bytes()).hexdigest(),
                packing_sources_sha256=hashlib.sha256(packing.SOURCES.read_bytes()).hexdigest(), steps=result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    expected = json.dumps(generate(), indent=2) + '\n'
    if args.check:
        assert FIXTURE.read_text() == expected, 'Acoustic desktop expectations changed; inspect and regenerate.'
    else:
        FIXTURE.parent.mkdir(parents=True, exist_ok=True)
        FIXTURE.write_text(expected)
    print('Pinned acoustic scenarios verified: independent payloads and cold/warm session text.')


if __name__ == '__main__':
    main()
