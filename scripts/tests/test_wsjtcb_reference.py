"""Integration checks for reference provenance and stale-fixture detection."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ReferenceIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='wsjtcb-reference-test-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        shutil.copytree(ROOT / 'reference', self.root / 'reference')
        (self.root / 'scripts').mkdir()
        shutil.copy2(ROOT / 'scripts/wsjtcb_reference.py', self.root / 'scripts/wsjtcb_reference.py')
        target = self.root / 'core/src/test/resources/wsjtcb'
        target.mkdir(parents=True)
        self.fixture = target / 'predicates.tsv'
        shutil.copy2(ROOT / 'core/src/test/resources/wsjtcb/predicates.tsv', self.fixture)

    def check(self, **environment):
        return subprocess.run([sys.executable, str(self.root / 'scripts/wsjtcb_reference.py'), '--check'],
                              env={**os.environ, **environment}, text=True, capture_output=True, timeout=30)

    def test_modified_source_is_rejected_before_compilation(self):
        source = self.root / 'reference/wsjtcb/source/cb_callsigns.f90'
        source.write_bytes(source.read_bytes() + b'\n! changed\n')
        result = self.check(FC='missing-compiler')
        self.assertEqual(1, result.returncode)
        self.assertIn('source checksum mismatch', result.stderr)

    def test_stale_expected_result_is_rejected(self):
        self.fixture.write_text(self.fixture.read_text().replace('C\tc-001\t\t0', 'C\tc-001\t\t1'))
        result = self.check()
        self.assertEqual(1, result.returncode)
        self.assertIn('Desktop fixture differs', result.stderr)

    def test_changed_case_set_requires_regeneration(self):
        path = self.root / 'reference/wsjtcb/cases.json'
        document = json.loads(path.read_text())
        document['cases'][0]['input'] = 'INVALID'
        path.write_text(json.dumps(document))
        result = self.check()
        self.assertEqual(1, result.returncode)
        self.assertIn('Desktop fixture differs', result.stderr)

    def test_missing_compiler_is_reported_without_a_traceback(self):
        result = self.check(FC='missing-compiler')
        self.assertEqual(1, result.returncode)
        self.assertIn('A Fortran compiler is required', result.stderr)
        self.assertNotIn('Traceback', result.stderr)


if __name__ == '__main__':
    unittest.main()
