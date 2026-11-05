#!/usr/bin/env python3
"""Export existing independent desktop fixtures for the JVM text-codec tests."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REF = ROOT / 'reference/wsjtcb'
TARGET = ROOT / 'core/src/test/resources/wsjtcb/messages.tsv'


def generate():
    packing = json.loads((REF / 'packing.json').read_text())
    qso = json.loads((REF / 'qso.json').read_text())
    assert packing['revision'] == qso['revision']
    lines = ['# WSJT-CB ' + packing['revision']]
    for name in ('packing.json', 'qso.json'):
        lines.append('# ' + name + ' sha256: ' + hashlib.sha256((REF / name).read_bytes()).hexdigest())
    lines.append('# id\tinput-ASCII-hex\tpayload77\tcold-success\tcold-decoded-ASCII-hex')
    cold = {s['id']: s['steps'][0] for s in packing['scenarios'] if s['id'].startswith('cold-')}
    for case in packing['packing']:
        decoded = cold['cold-' + case['id']]
        lines.append('\t'.join([case['id'], case['input'].encode('ascii').hex(),
                     case['pack77']['payload77'], str(int(decoded['success'])),
                     decoded['decoded'].encode('ascii').hex()]))
    for i, case in enumerate(qso['message_payloads']):
        lines.append('\t'.join(['qso-' + str(i), case['message'].encode('ascii').hex(),
                     case['payload77'], str(int(case['cold_success'])),
                     case['cold_decoded'].encode('ascii').hex()]))
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    result = generate()
    packing = json.loads((REF / 'packing.json').read_text())
    payloads = {case['id']: case['pack77']['payload77'] for case in packing['packing']}
    session_lines = ['# WSJT-CB ' + packing['revision'],
                     '# packing.json sha256: ' + hashlib.sha256((REF / 'packing.json').read_bytes()).hexdigest(),
                     '# scenario\tstep\top\targument-ASCII-hex\twidth\tsuccess\tdesktop-text-ASCII-hex']
    for scenario in packing['scenarios']:
        for i, step in enumerate(scenario['steps']):
            op = step['op']
            argument = payloads[step['case']] if op == 'decode' else step.get('payload77', step.get('call', ''))
            session_lines.append('\t'.join([scenario['id'], str(i), op, argument.encode('ascii').hex(),
                                 str(step.get('width', 0)), str(int(step.get('success', True))),
                                 step.get('decoded', step.get('resolved', '')).encode('ascii').hex()]))
    session_target = TARGET.with_name('hash-sessions.tsv')
    session_result = '\n'.join(session_lines) + '\n'
    if args.check:
        if not session_target.exists() or session_target.read_text() != session_result:
            raise SystemExit('JVM hash-session fixture differs')
    else:
        session_target.write_text(session_result)
    raw_lines = ['# Cold single-step binary RX cases from packing.json',
                 '# sha256: ' + hashlib.sha256((REF / 'packing.json').read_bytes()).hexdigest()]
    for scenario in packing['scenarios']:
        if len(scenario['steps']) != 1:
            continue
        step = scenario['steps'][0]
        if step['op'] == 'raw' and set(step['payload77']) <= {'0', '1'}:
            raw_lines.append('\t'.join([scenario['id'], step['payload77'],
                             str(int(step['success'])), step['decoded'].encode('ascii').hex()]))
    raw_result = '\n'.join(raw_lines) + '\n'
    raw_target = TARGET.with_name('messages-rx.tsv')
    if args.check:
        if not raw_target.exists() or raw_target.read_text() != raw_result:
            raise SystemExit('JVM raw RX fixture differs')
    else:
        raw_target.write_text(raw_result)
    if args.check:
        if not TARGET.exists() or TARGET.read_text() != result:
            raise SystemExit('JVM codec fixture differs; regenerate from verified desktop JSON')
    else:
        TARGET.write_text(result)
    print('JVM codec fixture verified:', len(result.splitlines()) - 4, 'desktop messages')
    print('Hash-session fixture verified:', len(packing['scenarios']), 'desktop scenarios')


if __name__ == '__main__':
    main()
