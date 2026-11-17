#!/usr/bin/env python3
"""Compile pinned desktop ADIF methods; check file fixtures and exported phone logs.

DXCC lookup is stubbed. Contest activity, desktop GUI and LAN logs are outside
this harness. The writer, loader and field extractor remain upstream code.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT / 'reference/wsjtcb'
FIXTURE = ROOT / 'core/src/test/resources/wsjtcb/adif/desktop.adi'


def extract(text, marker):
    start = text.index(marker)
    brace = text.index('{', start)
    # These exact pinned methods have balanced braces in strings/comments too.
    depth = 1
    end = brace + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


def compile_reference(directory):
    manifest = json.loads((REFERENCE / 'adif-sources.json').read_text())
    for entry in manifest['files']:
        if hashlib.sha256((REFERENCE / entry['local']).read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError('ADIF source checksum mismatch: ' + entry['path'])
    source = REFERENCE / 'source/adif'
    writer = extract((source / 'logbook.cpp').read_text(), 'QByteArray LogBook::QSOToADIF').replace('LogBook::QSOToADIF', 'desktop_QSOToADIF', 1)
    reader = (source / 'WorkedBefore.cpp').read_text()
    body = (REFERENCE / 'harness/adif/prefix.cpp').read_text() + '\n' + writer + '\n'
    body += extract(reader, 'QString extractField') + '\n' + extract(reader, 'worked_before_database_type loader') + '\n'
    body += (REFERENCE / 'harness/adif/main.cpp').read_text()
    cpp = Path(directory) / 'adif.cpp'; cpp.write_text(body)
    executable = Path(directory) / 'adif'
    flags = subprocess.check_output(shlex.split(os.environ.get('PKG_CONFIG', 'pkg-config')) + ['--cflags', '--libs', 'Qt5Core'], text=True, timeout=20)
    subprocess.run(shlex.split(os.environ.get('CXX', 'g++')) + ['-std=c++17', '-O0', '-fPIC', str(cpp), '-o', str(executable)] + shlex.split(flags) + shlex.split(os.environ.get('LDFLAGS', '')), check=True, timeout=60)
    return executable


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--read', type=Path, help='Read an actual exported ADI file with desktop methods')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='wsjtcb-adif-') as directory:
        executable = compile_reference(directory)
        data = subprocess.check_output([executable, 'write', REFERENCE / 'adif-cases.json'], timeout=10)
        if args.check:
            if FIXTURE.read_bytes() != data: raise ValueError('Desktop ADIF fixture differs; regenerate and review')
        else:
            FIXTURE.parent.mkdir(parents=True, exist_ok=True); FIXTURE.write_bytes(data)
        result = json.loads(subprocess.check_output([executable, 'read', args.read or FIXTURE], timeout=10))
        if not args.read:
            app_file = FIXTURE.with_name('app-export.adi')
            exported = json.loads(subprocess.check_output([executable, 'read', app_file], timeout=10))
            assert len(exported['worked']) == len(exported['fields']) == 1
            assert exported['worked'][0]['call'] == '26AT101' and exported['worked'][0]['band'] == '11M'
            assert exported['fields'][0]['STATION_CALLSIGN'] == '1AT/AA'
            assert exported['fields'][0]['FREQ'] == '27.265123' and exported['fields'][0]['QSO_DATE_OFF'] == '20261118'
            cases = json.loads((REFERENCE / 'adif-cases.json').read_text())
            assert len(result['worked']) == len(cases) == len(result['fields'])
            for case, worked, fields in zip(cases, result['worked'], result['fields']):
                assert worked['call'] == case['call'] and worked['band'] == case['band'].upper() and worked['mode'] == 'FT8'
                assert fields['CALL'] == case['call'] and fields['STATION_CALLSIGN'] == case['station'] and fields['FREQ'] == case['frequency']
            print('Pinned desktop ADIF writer/loader/field fixtures: PASS')
        else: print(json.dumps(result, indent=2))


if __name__ == '__main__': main()
