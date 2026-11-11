#!/usr/bin/env python3
"""Extract the pinned desktop relay; Android phases are explicit domain policy, not desktop TX evidence."""
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
    reference = json.loads(source.read_text())
    cases = ROOT/'reference/wsjtcb/qso-cases.json'
    assert reference['cases_sha256'] == hashlib.sha256(cases.read_bytes()).hexdigest()
    trace = next(t for t in reference['traces'] if t['id'] == 'two-station-generated-exchange')
    payloads = {m['message'].strip(): m['payload77'] for m in reference['message_payloads']}
    # Completion events below are synthesized at the domain boundary. The desktop
    # harness models message selection, not actual audio completion or RF reception.
    policy = [
        ('CQ', 'CALLING', 'SELECTED'),
        ('REPLY', 'REPLYING', 'REPLYING'),
        ('REPORT', 'REPORT_EXCHANGE', 'REPORT_EXCHANGE'),
        ('ROGER', 'ACKNOWLEDGED', 'ACKNOWLEDGED'),
        ('RR73', 'SIGNOFF', 'SIGNOFF'),
        ('SIGNOFF', 'COMPLETE', 'COMPLETE'),
    ]
    lines = [f"# WSJT-CB {reference['revision']}; qso.json SHA-256 {hashlib.sha256(source.read_bytes()).hexdigest()}",
             '# Wire text/parser results/payloads: desktop. Sent callbacks and phase expectations: Android policy.',
             '# step\tsender\tkind\ttext ASCII hex\tpayload77\tlocal phase\tremote phase\tparser destination\tparser sender\tparser report']
    for index, (kind, local_phase, remote_phase) in enumerate(policy):
        step = trace['steps'][index]
        assert step['op'] == 'relay'
        text = step['line'].split('~', 1)[1].strip()
        parsed = step['parsed']
        lines.append('\t'.join([str(index), step['from'], kind, text.encode('ascii').hex(), payloads[text],
                                local_phase, remote_phase, parsed['call'], parsed['de_call'], parsed['report']]))
    expected = '\n'.join(lines)+'\n'
    destination = ROOT/'core/src/test/resources/wsjtcb/qso-progression.tsv'
    if args.check:
        assert destination.read_text() == expected, 'Regenerate QSO progression fixtures.'
    else:
        destination.write_text(expected)
    print(f'{len(policy)} desktop relay messages and Android policy expectations verified.')


if __name__ == '__main__':
    main()
