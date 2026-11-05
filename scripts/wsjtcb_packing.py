#!/usr/bin/env python3
"""Generate/check payload and hash fixtures with unmodified WSJT-CB routines."""
import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT / 'reference/wsjtcb'
FIXTURE = REFERENCE / 'packing.json'
SOURCES = REFERENCE / 'packing-sources.json'
CASES = REFERENCE / 'packing-cases.json'
HARNESS = REFERENCE / 'harness/packing.f90'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ascii_text(value, maximum):
    return (isinstance(value, str) and len(value) <= maximum and value.isascii()
            and all(32 <= ord(char) < 127 and char != '|' for char in value))


def load_inputs():
    manifest = json.loads(SOURCES.read_text())
    cases = json.loads(CASES.read_text())
    predicate_manifest = json.loads((REFERENCE / 'sources.json').read_text())
    if (manifest['schema'] != 1 or cases['schema'] != 1
            or manifest['revision'] != predicate_manifest['references']['cb']['revision']
            or manifest['repository'] != predicate_manifest['references']['cb']['repository']):
        raise ValueError('Inconsistent packing reference revision/schema')
    names = [file['local'] for file in manifest['files']]
    if not names or len(names) != len(set(names)):
        raise ValueError('Empty or duplicate packing sources')
    for file in manifest['files']:
        local = Path(file['local'])
        if local.is_absolute() or '..' in local.parts:
            raise ValueError('Invalid packing source path')
        if digest(REFERENCE / local) != file['sha256']:
            raise ValueError(f'Packing source checksum mismatch: {local}')
    packing = cases['packing']
    ids = {case['id'] for case in packing}
    if not packing or len(ids) != len(packing):
        raise ValueError('Empty packing cases or duplicate IDs')
    if any(not ascii_text(case['input'], 37) for case in packing):
        raise ValueError('Packing inputs must be printable ASCII, at most 37 characters')
    if any('<...>' in case['input'] for case in packing):
        raise ValueError('Unresolved hash placeholders have no defined TX payload; use a raw RX scenario')
    calls = cases['hash_calls']
    if not calls or len(calls) != len(set(calls)) or any(not ascii_text(c, 11) or not c for c in calls):
        raise ValueError('Invalid or duplicate hash calls')
    scenarios = cases['scenarios']
    if not scenarios or len({s['id'] for s in scenarios}) != len(scenarios):
        raise ValueError('Empty scenarios or duplicate IDs')
    for scenario in scenarios:
        if not scenario['steps']:
            raise ValueError('Empty scenario')
        for step in scenario['steps']:
            op = step['op']
            valid = False
            if op == 'decode':
                valid = step['case'] in ids
            elif op == 'raw':
                valid = len(step['payload77']) == 77 and set(step['payload77']) <= {'0', '1', 'X'}
            elif op in ('learn', 'station'):
                valid = step['call'] in calls
            elif op == 'lookup':
                valid = step['call'] in calls and step['width'] in (10, 12, 22)
            elif op == 'commit':
                valid = True
            if not valid:
                raise ValueError(f'Invalid packing scenario step: {scenario["id"]}')
    return manifest, cases


def compile_reference(directory, manifest, optimization):
    compiler = shlex.split(os.environ.get('FC', 'gfortran'))
    if not compiler or not shutil.which(compiler[0]):
        raise ValueError('A Fortran compiler is required; install gfortran or set FC')
    compiler[0] = str(Path(shutil.which(compiler[0])).resolve())
    executable = Path(directory) / 'packing'
    # Overflow wraps in the production 64-bit hash. Unused legacy packjt routines
    # are discarded by the linker; no algorithm or external routine is stubbed.
    flags = ['-' + optimization, '-fwrapv', '-fcheck=all', '-ffunction-sections',
             '-fdata-sections', '-Wl,--gc-sections', '-I', str(REFERENCE / 'source')]
    sources = [str(REFERENCE / file['local']) for file in manifest['files']
               if file['local'].endswith('.f90') and file['local'] != 'source/pfx.f90']
    subprocess.run(compiler + shlex.split(os.environ.get('FFLAGS', '')) + flags
                   + sources + [str(HARNESS), '-o', str(executable)],
                   cwd=directory, check=True, timeout=60)
    return executable


def run(executable, commands):
    # A fresh process isolates all upstream module state between scenarios.
    result = subprocess.run([str(executable)], input='\n'.join(commands) + '\n',
                            text=True, capture_output=True, check=True, timeout=20)
    return result.stdout.splitlines()


def generate(manifest, cases, optimization='O0'):
    with tempfile.TemporaryDirectory(prefix='wsjtcb-packing-') as directory:
        executable = compile_reference(directory, manifest, optimization)
        packed = []
        for case in cases['packing']:
            # Separate processes also isolate the two desktop TX hash tables.
            item = dict(case)
            for command, name in [('P', 'pack77'), ('V', 'pack77var')]:
                rows = run(executable, [command + '|' + case['input']])
                if len(rows) != 1:
                    raise ValueError('Unexpected packer response')
                i3, n3, bits = rows[0].split('|')
                if len(bits) != 77 or set(bits) - {'0', '1'}:
                    raise ValueError('Invalid desktop payload')
                item[name] = {'i3': int(i3), 'n3': int(n3), 'payload77': bits}
            packed.append(item)
        rows = run(executable, ['H|' + call for call in cases['hash_calls']])
        if len(rows) != len(cases['hash_calls']):
            raise ValueError('Unexpected hash response')
        hashes = {call: dict(zip(('10', '12', '22'), map(int, row.split('|'))))
                  for call, row in zip(cases['hash_calls'], rows)}
        payloads = {case['id']: case['pack77']['payload77'] for case in packed}
        scenarios = []
        for scenario in cases['scenarios']:
            commands = []
            for step in scenario['steps']:
                op = step['op']
                if op == 'decode':
                    commands.append('D|' + payloads[step['case']])
                elif op == 'raw':
                    commands.append('D|' + step['payload77'])
                elif op == 'lookup':
                    commands.append(f'Q|{step["width"]} {hashes[step["call"]][str(step["width"])]}')
                elif op == 'learn':
                    commands.append('L|' + step['call'])
                elif op == 'station':
                    commands.append('S|' + step['call'])
                elif op == 'commit':
                    commands.append('C|')
            answers = iter(run(executable, commands))
            steps = []
            for step in scenario['steps']:
                item = dict(step)
                if step['op'] in ('decode', 'raw'):
                    ok, decoded = next(answers).split('|', 1)
                    if ok not in ('0', '1'):
                        raise ValueError('Unexpected unpacker response')
                    item.update(success=ok == '1', decoded=decoded)
                elif step['op'] == 'lookup':
                    item['resolved'] = next(answers)
                steps.append(item)
            if next(answers, None) is not None:
                raise ValueError('Unexpected extra scenario response')
            scenarios.append({'id': scenario['id'], 'steps': steps})
    document = {'schema': 1, 'repository': manifest['repository'], 'revision': manifest['revision'],
                'sources_sha256': digest(SOURCES), 'cases_sha256': digest(CASES),
                'harness_sha256': digest(HARNESS), 'packing': packed, 'hashes': hashes,
                'scenarios': scenarios}
    return json.dumps(document, indent=2, ensure_ascii=True) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Fail if committed fixtures differ')
    parser.add_argument('--optimization', choices=('O0', 'O2'), default='O0')
    args = parser.parse_args()
    manifest, cases = load_inputs()
    generated = generate(manifest, cases, args.optimization)
    if args.check:
        if not FIXTURE.exists() or FIXTURE.read_text() != generated:
            expected = FIXTURE.read_text() if FIXTURE.exists() else ''
            difference = list(difflib.unified_diff(expected.splitlines(), generated.splitlines(),
                              fromfile='committed packing fixture', tofile='generated packing fixture'))
            print('\n'.join(difference[:120]), file=sys.stderr)
            raise ValueError('Packing fixture differs; inspect changes and regenerate without --check')
        print(f'Packing fixture verified: {len(cases["packing"])} payloads, '
              f'{len(cases["hash_calls"])} hashes, {len(cases["scenarios"])} scenarios ({args.optimization})')
    else:
        FIXTURE.write_text(generated)
        print(f'Packing fixture generated: {FIXTURE}')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, StopIteration, subprocess.SubprocessError) as error:
        print(f'Packing reference check failed: {error}', file=sys.stderr)
        sys.exit(1)
