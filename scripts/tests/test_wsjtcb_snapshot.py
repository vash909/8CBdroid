"""Test snapshot comparison semantics without downloading the full source archive."""
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

from scripts.wsjtcb_snapshot import compare, fingerprints, verify


def archive_of(files):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as archive:
        for name, content in files:
            entry = tarfile.TarInfo(name)
            entry.size = len(content)
            archive.addfile(entry, io.BytesIO(content))
    return output.getvalue()


class SnapshotComparisonTest(unittest.TestCase):
    def test_text_line_endings_are_separate_from_byte_identity(self):
        before = fingerprints(archive_of([('wsjtx/a.f90', b'first\r\nsecond\r\n')]), 'wsjtx/')
        after = fingerprints(archive_of([('a.f90', b'first\nsecond\n')]))
        result = compare(before, after, 'test-revision')
        self.assertEqual(0, result['byte_identical_files'])
        self.assertEqual(1, result['normalized_identical_files'])
        self.assertEqual([], result['different_files'])

    def test_binary_changes_and_added_removed_files_remain_visible(self):
        before = fingerprints(archive_of([('binary', b'\0\r\n'), ('old', b'old')]))
        after = fingerprints(archive_of([('binary', b'\0\n'), ('new', b'new')]))
        result = compare(before, after, 'test-revision')
        self.assertEqual(['binary'], result['different_files'])
        self.assertEqual(['old'], result['upstream_only'])
        self.assertEqual(['new'], result['cb_only'])

    def test_duplicate_paths_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate source archive path'):
            fingerprints(archive_of([('a', b'one'), ('a', b'two')]))

    def test_modified_archive_is_rejected_before_git_or_unpacking(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'source.tgz'
            path.write_bytes(b'bad data')
            expected = {'source_archive': {'size_bytes': 8, 'sha256': '0' * 64}}
            with self.assertRaisesRegex(ValueError, 'archive size/checksum mismatch'):
                verify(path, Path(directory), expected)
