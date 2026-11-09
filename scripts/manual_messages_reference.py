#!/usr/bin/env python3
"""Extract manual message/template expectations from the verified pinned desktop QSO corpus."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    source = ROOT/'reference/wsjtcb/qso.json'
    cases = ROOT/'reference/wsjtcb/qso-cases.json'
    reference = json.loads(source.read_text())
    inputs = json.loads(cases.read_text())
    assert reference['cases_sha256'] == hashlib.sha256(cases.read_bytes()).hexdigest()
    traces = {t['id']: t for t in reference['traces']}
    payloads = {m['message'].strip(): m['payload77'] for m in reference['message_payloads']}
    lines = [f"# WSJT-CB {reference['revision']}; qso.json SHA-256 {hashlib.sha256(source.read_bytes()).hexdigest()}",
             '# id\tlocal\tpeer\treport\tkind\tdesktop text (ASCII hex)\tpayload77']
    kinds = ['REPLY', 'REPORT', 'ROGER', 'RR73', 'SIGNOFF', 'CQ']
    for case in inputs['scenarios']:
        if not case['id'].startswith('generate-'):
            continue
        state = case['state']
        for kind, message in zip(kinds, traces[case['id']]['initial']['messages']):
            text = message.strip()
            if text:
                lines.append('\t'.join([case['id'], state['local'], state['peer'], str(int(state['report'])),
                                        kind, text.encode('ascii').hex(), payloads[text]]))
    expected = '\n'.join(lines)+'\n'
    destination = ROOT/'core/src/test/resources/wsjtcb/manual-messages.tsv'
    if args.check:
        assert destination.read_text() == expected, 'Regenerate manual message fixtures.'
    else:
        destination.write_text(expected)
    print(f'{len(lines)-2} independently generated desktop messages verified.')


if __name__ == '__main__':
    main()
