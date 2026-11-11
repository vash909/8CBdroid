#!/usr/bin/env python3
"""Regenerate original desktop admission traces and independent wire payloads; Android policy stays explicit."""
import argparse
import json
import re
from pathlib import Path
import wsjtcb_qso as qso

ROOT = Path(__file__).resolve().parents[1]
CASES = ROOT/'reference/wsjtcb/admission-cases.json'
FIXTURE = ROOT/'reference/wsjtcb/admission.json'


def generate(optimization):
    manifest, packing_manifest, _ = qso.load_inputs()
    scenarios = json.loads(CASES.read_text())['scenarios']
    messages = {'1AT106 -07', '1AT106 R-07', '1AT106 RR73'}
    for case in scenarios:
        qso.validate_state(case['state'])
        for step in case['steps']:
            assert step['op'] == 'auto' and qso.packing.ascii_text(step['line'], 128)
            text = step['line'].split('~', 1)[1].strip()
            assert '<...>' not in text
            messages.add(text)
    generated = qso.generate(manifest, packing_manifest, scenarios, optimization, CASES, messages)
    return generated


def adapter(document):
    inputs = json.loads(CASES.read_text())['scenarios']
    traces = {t['id']: t for t in document['traces']}
    payloads = {p['message'].strip(): p for p in document['message_payloads']}
    lines = [f"# WSJT-CB {document['revision']}; admission.json SHA-256 {qso.digest(FIXTURE)}",
             '# Desktop outcomes are observations; Android expectations are explicit policy.',
             '# id\tstep\tstage\tpeer\twarm peer\tinput hex\tpayload77\tfrequency\tAndroid admission\tAndroid next\tdesktop next\tdesktop peer\tdesktop stop\tpolicy hex']
    for case in inputs:
        for index, step in enumerate(case['steps']):
            text = step['line'].split('~', 1)[1].strip()
            packet = payloads[text]
            # All decision inputs in this matrix must be lossless wire messages.
            assert packet['cold_success'] and packet['cold_decoded'] == re.sub(r'<[^>]+>', '<...>', text), 'Decision input is lossy or not a supported wire message.'
            state = traces[case['id']]['steps'][index]['result']
            expected = case['expect'] if index == 0 else 'ACCEPTED'
            lines.append('\t'.join([case['id'], str(index), case['stage'], case['peer'], str(int(case['warm_peer'])),
                text.encode().hex(), packet['payload77'], str(traces[case['id']]['steps'][index]['parsed']['df']),
                expected, case['next'], str(state['tx']), state['peer'] or '-', str(int(state['stop_requested'])), case['policy'].encode().hex()]))
    # Seed messages are independently encoded by the original packer, never by the Kotlin model under test.
    for text in ['1AT106 -07', '1AT106 R-07', '1AT106 RR73']:
        lines.append('# seed\t'+text.encode().hex()+'\t'+payloads[text]['payload77'])
    return '\n'.join(lines)+'\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--optimization', choices=['O0', 'O2'], default='O0')
    parser.add_argument('--adapter-only', action='store_true')
    args = parser.parse_args()
    if not args.adapter_only:
        generated = generate(args.optimization)
        if args.check:
            assert FIXTURE.read_text() == generated, 'Desktop admission corpus differs.'
        else:
            FIXTURE.write_text(generated)
    document = json.loads(FIXTURE.read_text())
    manifest, packing_manifest, _ = qso.load_inputs()
    assert document['revision'] == manifest['revision']
    assert document['sources_sha256'] == qso.digest(qso.SOURCES)
    assert document['packing_sources_sha256'] == qso.digest(qso.packing.SOURCES)
    assert document['harness_sha256'] == {name: qso.digest(qso.HARNESS/name) for name in document['harness_sha256']}
    assert document['cases_sha256'] == qso.digest(CASES)
    expected = adapter(document)
    destination = ROOT/'core/src/test/resources/wsjtcb/qso-admission.tsv'
    if args.check:
        assert destination.read_text() == expected, 'Regenerate admission TSV.'
    else:
        destination.write_text(expected)
    print(f"{len(document['traces'])} desktop admission scenarios verified ({args.optimization}).")


if __name__ == '__main__':
    main()
