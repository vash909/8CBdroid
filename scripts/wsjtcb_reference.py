#!/usr/bin/env python3
"""Generate/check desktop predicate fixtures; optionally audit pinned upstream source files."""
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
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT / 'reference/wsjtcb'
FIXTURE = ROOT / 'core/src/test/resources/wsjtcb/predicates.tsv'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load_inputs():
    manifest = json.loads((REFERENCE / 'sources.json').read_text())
    document = json.loads((REFERENCE / 'cases.json').read_text())
    if manifest['schema'] != 1 or document['schema'] != 1:
        raise ValueError('Unsupported reference schema')
    source = manifest['vendored']
    expected = next(file['sha256'] for file in manifest['references'][source['reference']]['files']
                    if file['path'] == source['path'])
    local = ROOT / source['local']
    if digest(local.read_bytes()) != expected:
        raise ValueError('Vendored WSJT-CB source checksum mismatch')
    cases = document['cases']
    if not cases or len({case['id'] for case in cases}) != len(cases):
        raise ValueError('Empty case set or duplicate case IDs')
    for case in cases:
        value = case['input']
        if (case['kind'] not in ('C', 'T') or len(value) > 128 or not value.isascii()
                or any(ord(char) < 32 or ord(char) == 127 for char in value)):
            raise ValueError(f'Invalid ASCII reference case: {case["id"]}')
    return manifest, local, cases


def generate(manifest, source, cases):
    compiler = shlex.split(os.environ.get('FC', 'gfortran'))
    if not compiler or not shutil.which(compiler[0]):
        raise ValueError('A Fortran compiler is required; install gfortran or set FC')
    compiler[0] = str(Path(shutil.which(compiler[0])).resolve())
    flags = shlex.split(os.environ.get('FFLAGS', ''))
    harness = REFERENCE / 'harness/predicates.f90'
    with tempfile.TemporaryDirectory(prefix='wsjtcb-predicates-') as directory:
        executable = Path(directory) / 'predicates'
        subprocess.run(compiler + flags + ['-std=f2008', '-O0', '-fcheck=all', str(source),
                       str(harness), '-o', str(executable)], cwd=directory, check=True)
        payload = ''.join(f'{case["kind"]}|{case["input"]}\n' for case in cases)
        result = subprocess.run([str(executable)], input=payload, text=True,
                                capture_output=True, check=True, timeout=20)
    answers = result.stdout.splitlines()
    if len(answers) != len(cases) or any(answer.strip() not in ('0', '1') for answer in answers):
        raise ValueError('Unexpected response from desktop reference harness')
    revision = manifest['references']['cb']['revision']
    lines = [f'# WSJT-CB {revision}; desktop predicates, generator schema 1',
             '# cases-sha256: ' + digest((REFERENCE / 'cases.json').read_bytes()),
             '# kind\tid\tinput-ASCII-hex\taccepted']
    for case, answer in zip(cases, answers):
        lines.append(f'{case["kind"]}\t{case["id"]}\t{case["input"].encode("ascii").hex()}\t{answer.strip()}')
    return '\n'.join(lines) + '\n'


def audit_sources(manifest):
    """Network access is explicit; comparison releases are not asserted to be ancestors."""
    destination = ROOT / '.tooling/wsjt-reference-audit'
    packing = json.loads((REFERENCE / 'packing-sources.json').read_text())
    qso = json.loads((REFERENCE / 'qso-sources.json').read_text())
    cb = manifest['references']['cb']
    for extra in (packing, qso):
        if extra['revision'] != cb['revision'] or extra['repository'] != cb['repository']:
            raise ValueError('Inconsistent source audit revision')
    files = {file['path']: file for file in cb['files']}
    for file in packing['files'] + qso['files']:
        if file['path'] in files and files[file['path']]['sha256'] != file['sha256']:
            raise ValueError('Conflicting reference source checksum')
        files[file['path']] = {'path': file['path'], 'sha256': file['sha256']}
    cb['files'] = list(files.values())
    for name, ref in manifest['references'].items():
        for file in ref['files']:
            path = Path(file['path'])
            if path.is_absolute() or '..' in path.parts:
                raise ValueError('Invalid source path')
            url = f'https://raw.githubusercontent.com/{ref["repository"]}/{ref["revision"]}/{file["path"]}'
            with urlopen(url, timeout=30) as response:
                data = response.read()
            if digest(data) != file['sha256']:
                raise ValueError(f'Upstream checksum mismatch: {name}/{path}')
            target = destination / name / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    for baseline in ('x3', 'x32'):
        for path in ('Radio.cpp', 'lib/ft8var/chkfalse8var.f90', 'widgets/mainwindow.cpp'):
            before = (destination / baseline / path).read_text().splitlines(keepends=True)
            after = (destination / 'cb' / path).read_text().splitlines(keepends=True)
            patch = ''.join(difflib.unified_diff(before, after, f'{baseline}/{path}', f'cb/{path}'))
            target = destination / 'diffs' / baseline / (path + '.diff')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(patch)
    print(f'Upstream source hashes and normalized-text diffs verified: {destination}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Fail if the committed fixture differs')
    parser.add_argument('--audit-sources', action='store_true', help='Download and hash-check the pinned comparison sources')
    args = parser.parse_args()
    manifest, source, cases = load_inputs()
    generated = generate(manifest, source, cases)
    if args.check:
        if not FIXTURE.exists() or FIXTURE.read_text() != generated:
            raise ValueError('Desktop fixture differs; inspect changes and regenerate without --check')
        print(f'Desktop fixture verified: {len(cases)} cases')
    else:
        FIXTURE.write_text(generated)
        print(f'Desktop fixture generated: {len(cases)} cases')
    if args.audit_sources:
        audit_sources(manifest)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f'Reference check failed: {error}', file=sys.stderr)
        sys.exit(1)
