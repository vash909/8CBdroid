#!/usr/bin/env python3
"""Verify the official RC1 source archive against the initial CB import and V1.0."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / 'reference/wsjtcb/initial-snapshot.json'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def fingerprints(data, prefix=''):
    files = {}
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive:
            if not member.isfile():
                continue
            if not member.name.startswith(prefix):
                raise ValueError('Unexpected source archive prefix')
            path = member.name.removeprefix(prefix)
            if path in files:
                raise ValueError('Duplicate source archive path')
            content = archive.extractfile(member).read()
            normalized = content
            if b'\0' not in content:
                try:
                    content.decode('utf-8')
                    normalized = content.replace(b'\r\n', b'\n')
                except UnicodeError:
                    pass
            files[path] = {'sha256': digest(content), 'normalized_sha256': digest(normalized)}
    return files


def compare(upstream, fork, revision):
    common = sorted(upstream.keys() & fork.keys())
    identical = [path for path in common if upstream[path]['sha256'] == fork[path]['sha256']]
    normalized = [path for path in common if upstream[path]['normalized_sha256'] == fork[path]['normalized_sha256']]
    return {
        'repository': 'vash909/WSJT-CB', 'revision': revision, 'common_files': len(common),
        'byte_identical_files': len(identical), 'normalized_identical_files': len(normalized),
        'different_files': [path for path in common if path not in normalized],
        'upstream_only': sorted(upstream.keys() - fork.keys()),
        'cb_only': sorted(fork.keys() - upstream.keys()),
    }


def verify(archive_path, repository, expected):
    data = archive_path.read_bytes()
    source = expected['source_archive']
    if len(data) != source['size_bytes'] or digest(data) != source['sha256']:
        raise ValueError('Official RC1 archive size/checksum mismatch')
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        inner = archive.extractfile(source['source_member']).read()
    if digest(inner) != source['source_sha256']:
        raise ValueError('Nested RC1 source checksum mismatch')
    upstream = fingerprints(inner, 'wsjtx/')
    if len(upstream) != source['source_file_count']:
        raise ValueError('Unexpected RC1 source file count')
    for comparison in expected['comparisons']:
        result = subprocess.run(['git', '-C', str(repository), 'archive', comparison['revision']],
                                check=True, capture_output=True, timeout=60)
        actual = compare(upstream, fingerprints(result.stdout), comparison['revision'])
        if actual != comparison:
            raise ValueError(f'CB source comparison differs: {comparison["revision"]}')
        print(f'{comparison["revision"]}: {actual["byte_identical_files"]} / '
              f'{actual["common_files"]} common files byte-identical')
    print('Official RC1 source hashes and CB import comparisons verified; no upstream Git commit is inferred.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True, help='Local official wsjtx-3.0.0-rc1.tgz')
    parser.add_argument('--repository', type=Path, required=True, help='Local WSJT-CB Git repository containing the pinned early commits')
    args = parser.parse_args()
    expected = json.loads(REPORT.read_text())
    if expected['schema'] != 1:
        raise ValueError('Unsupported snapshot report schema')
    verify(args.archive, args.repository, expected)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, tarfile.TarError, subprocess.SubprocessError) as error:
        print(f'Snapshot check failed: {error}', file=sys.stderr)
        sys.exit(1)
