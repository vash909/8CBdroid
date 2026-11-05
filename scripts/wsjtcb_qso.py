#!/usr/bin/env python3
"""Generate desktop parser, message generation and AutoSeq traces (host only)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile

import wsjtcb_packing as packing

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT / 'reference/wsjtcb'
SOURCES = REFERENCE / 'qso-sources.json'
CASES = REFERENCE / 'qso-cases.json'
FIXTURE = REFERENCE / 'qso.json'
HARNESS = REFERENCE / 'harness'
PROGRESS = ('CALLING', 'REPLYING', 'REPORT', 'ROGER_REPORT', 'ROGERS', 'SIGNOFF')
STATE_TEXT = {'local', 'peer', 'peer_grid', 'grid', 'report', 'progress'}
STATE_BOOL = {'calling_cq', 'auto', 'sent_first_73', 'auto_reply', 'tx1_enabled',
              'auto_seq', 'auto_seq_enabled', 'auto_seq_visible'}
STATE_INT = {'tx', 'rx_df', 'tx_df'}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def extract_methods(manifest):
    source = (REFERENCE / 'source/qso/widgets/mainwindow.cpp').read_bytes()
    fragments = []
    for method in manifest['methods']:
        anchor = method['anchor'].encode('ascii')
        if source.count(anchor) != 1:
            raise ValueError('Non-unique desktop method anchor')
        start = source.index(anchor)
        # Pinned methods end at the next unindented closing brace. Each exact
        # fragment is independently checksummed; this is not a general C++ parser.
        end = source.index(b'\n}', start) + 2
        fragment = source[start:end]
        if hashlib.sha256(fragment).hexdigest() != method['sha256']:
            raise ValueError('Desktop method extraction checksum mismatch')
        fragments.append(fragment)
    grid = [line for line in source.splitlines() if b'QRegularExpression grid_regexp ' in line]
    if len(grid) != 1 or hashlib.sha256(grid[0]).hexdigest() != manifest['grid_regexp_sha256']:
        raise ValueError('Desktop grid expression checksum mismatch')
    return grid[0] + b'\n' + b'\n'.join(fragments) + b'\n'


def validate_state(state, context=False):
    allowed = {'sent_first_73', 'peer'} if context else STATE_TEXT | STATE_BOOL | STATE_INT
    if not isinstance(state, dict) or set(state) - allowed:
        raise ValueError('Unsupported QSO state key')
    for key, value in state.items():
        if key in STATE_TEXT and not packing.ascii_text(value, 37):
            raise ValueError('Invalid ASCII QSO state')
        if key in STATE_BOOL and type(value) is not bool:
            raise ValueError('Invalid boolean QSO state')
        if key in STATE_INT and (type(value) is not int or not 0 <= value <= 5000):
            raise ValueError('Invalid integer QSO state')
    if 'progress' in state and state['progress'] not in PROGRESS:
        raise ValueError('Unknown QSO progress')
    if 'tx' in state and not 1 <= state['tx'] <= 6:
        raise ValueError('Invalid TX selection')


def load_inputs():
    manifest = json.loads(SOURCES.read_text())
    inputs = json.loads(CASES.read_text())
    packing_manifest, _ = packing.load_inputs()
    if (manifest['schema'] != 1 or inputs['schema'] != 1
            or manifest['revision'] != packing_manifest['revision']
            or manifest['repository'] != packing_manifest['repository']):
        raise ValueError('Inconsistent QSO reference revision/schema')
    for file in manifest['files']:
        path = Path(file['local'])
        if path.is_absolute() or '..' in path.parts or digest(REFERENCE / path) != file['sha256']:
            raise ValueError(f'QSO source checksum mismatch: {path}')
    extract_methods(manifest)
    scenarios = inputs['scenarios']
    if not scenarios or len({s['id'] for s in scenarios}) != len(scenarios):
        raise ValueError('Empty QSO scenarios or duplicate IDs')
    for scenario in scenarios:
        validate_state(scenario.get('state', {}))
        if 'remote_state' in scenario:
            validate_state(scenario['remote_state'])
        for step in scenario['steps']:
            if step['op'] in ('parse', 'process', 'auto'):
                if not packing.ascii_text(step['line'], 128):
                    raise ValueError('Invalid ASCII decoder line')
                for key in ('start_tolerance', 'stop_tolerance'):
                    if key in step and (type(step[key]) is not int or not 0 <= step[key] <= 5000):
                        raise ValueError('Invalid AutoSeq tolerance')
            elif step['op'] == 'context':
                validate_state(step['state'], context=True)
                if step.get('side', 'local') not in ('local', 'remote') or (
                        step.get('side') == 'remote' and 'remote_state' not in scenario):
                    raise ValueError('Invalid context station')
            elif step['op'] == 'relay':
                if ('remote_state' not in scenario or step['from'] not in ('local', 'remote')
                        or step['via'] not in ('auto', 'process')
                        or ('tx' in step and (type(step['tx']) is not int or not 1 <= step['tx'] <= 6))):
                    raise ValueError('Invalid relay operation')
            else:
                raise ValueError('Unknown QSO operation')
    return manifest, packing_manifest, scenarios


def command(variable, default):
    args = shlex.split(os.environ.get(variable, default))
    if not args or not shutil.which(args[0]):
        raise ValueError(f'{variable} compiler/tool is required ({default})')
    args[0] = str(Path(shutil.which(args[0])).resolve())
    return args


def compile_reference(directory, manifest, packing_manifest, optimization):
    fc = command('FC', 'gfortran') + shlex.split(os.environ.get('FFLAGS', ''))
    cxx = command('CXX', 'g++') + shlex.split(os.environ.get('CXXFLAGS', ''))
    pkg = command('PKG_CONFIG', 'pkg-config')
    result = subprocess.run(pkg + ['--cflags', '--libs', 'Qt5Core', 'Qt5Network'],
                            capture_output=True, text=True, check=False, timeout=20)
    if result.returncode:
        raise ValueError('Qt5Core/Qt5Network development packages are required: ' + result.stderr.strip())
    (Path(directory) / 'qso_methods.inc').write_bytes(extract_methods(manifest))
    sources = [REFERENCE / file['local'] for file in packing_manifest['files']
               if file['local'] != 'source/pfx.f90']
    sources += [REFERENCE / 'source/qso/lib/stdmsg.f90', REFERENCE / 'source/qso/lib/save_dxbase.f90']
    subprocess.run(fc + ['-' + optimization, '-fwrapv', '-fcheck=all', '-ffunction-sections',
                        '-fdata-sections', '-I', str(REFERENCE / 'source'), '-c']
                   + [str(path) for path in sources], cwd=directory, check=True, timeout=60)
    runtime = None
    for name in ('libgfortran.so', 'libgfortran.so.5'):
        path = subprocess.check_output(fc + ['-print-file-name=' + name], text=True).strip()
        if Path(path).is_absolute() and Path(path).is_file():
            runtime = path
            break
    if not runtime:
        raise ValueError('Cannot locate GNU Fortran runtime for C++ linking')
    source = REFERENCE / 'source/qso'
    executable = Path(directory) / 'qso'
    subprocess.run(cxx + ['-std=c++17', '-' + optimization, '-fPIC', '-ffunction-sections',
                         '-fdata-sections', '-Wl,--gc-sections', '-include', str(HARNESS / 'qso_config.hpp'),
                         '-I', str(HARNESS), '-I', str(source), '-I', str(directory),
                         str(HARNESS / 'qso.cpp'), str(source / 'Radio.cpp'),
                         str(source / 'Decoder/decodedtext.cpp')]
                   + [str(Path(directory) / (path.stem + '.o')) for path in sources]
                   + [runtime] + shlex.split(result.stdout) + shlex.split(os.environ.get('LDFLAGS', ''))
                   + ['-o', str(executable)], cwd=directory, check=True, timeout=60)
    return executable


def generate(manifest, packing_manifest, scenarios, optimization='O0'):
    with tempfile.TemporaryDirectory(prefix='wsjtcb-qso-') as directory:
        executable = compile_reference(directory, manifest, packing_manifest, optimization)
        traces = []
        for scenario in scenarios:
            result = subprocess.run([str(executable)], input=json.dumps(scenario) + '\n',
                                    text=True, capture_output=True, timeout=20)
            if result.returncode:
                raise ValueError(f'QSO scenario {scenario["id"]} failed: {result.stderr.strip()}')
            traces.append(json.loads(result.stdout))
        encoder = packing.compile_reference(directory, packing_manifest, optimization)
        states = []
        for trace in traces:
            states += [trace[key] for key in ('initial', 'remote_initial') if key in trace]
            states += [step[key] for step in trace['steps']
                       for key in ('result', 'remote_result') if key in step]
        messages = sorted({message for state in states for message in state['messages'] if message})
        payloads = []
        for message in messages:
            row, = packing.run(encoder, ['P|' + message])
            i3, n3, bits = row.split('|')
            decoded, = packing.run(encoder, ['D|' + bits])
            ok, text = decoded.split('|', 1)
            payloads.append({'message': message, 'i3': int(i3), 'n3': int(n3), 'payload77': bits,
                             'cold_success': ok == '1', 'cold_decoded': text})
    document = {'schema': 1, 'repository': manifest['repository'], 'revision': manifest['revision'],
                'sources_sha256': digest(SOURCES), 'packing_sources_sha256': digest(packing.SOURCES),
                'cases_sha256': digest(CASES),
                'harness_sha256': {name: digest(HARNESS / name) for name in
                                   ('qso.cpp', 'qso_config.hpp', 'udp_export.h', 'packing.f90')},
                'traces': traces, 'message_payloads': payloads}
    return json.dumps(document, indent=2, ensure_ascii=True) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--optimization', choices=('O0', 'O2'), default='O0')
    args = parser.parse_args()
    manifest, packing_manifest, scenarios = load_inputs()
    generated = generate(manifest, packing_manifest, scenarios, args.optimization)
    if args.check:
        if not FIXTURE.exists() or FIXTURE.read_text() != generated:
            raise ValueError('QSO fixture differs; inspect changes and regenerate without --check')
        print(f'QSO fixture verified: {len(scenarios)} scenarios ({args.optimization})')
    else:
        FIXTURE.write_text(generated)
        print(f'QSO fixture generated: {FIXTURE}')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f'QSO reference check failed: {error}', file=sys.stderr)
        sys.exit(1)
