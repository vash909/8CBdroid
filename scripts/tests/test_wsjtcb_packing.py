"""Exercise real desktop packing/hash behavior and fixture provenance failures."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('wsjtcb_packing', ROOT / 'scripts/wsjtcb_packing.py')
reference = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reference)


class DesktopPackingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        manifest, cases = reference.load_inputs()
        cls.fixture = json.loads(reference.generate(manifest, cases))
        cls.packing = {p['id']: p for p in cls.fixture['packing']}
        cls.scenarios = {s['id']: s['steps'] for s in cls.fixture['scenarios']}

    def decoded(self, scenario):
        return [(s['success'], s['decoded']) for s in self.scenarios[scenario]
                if s['op'] in ('decode', 'raw')]

    def test_cq_has_exact_desktop_payload_in_both_packers(self):
        expected = '10000111110100000000000000000000000000000010101110100100110110001101010001100'
        for packer in ('pack77', 'pack77var'):
            self.assertEqual(expected, self.packing['cq-1AT106'][packer]['payload77'])
            self.assertEqual(4, self.packing['cq-1AT106'][packer]['i3'])
        for case in self.fixture['packing']:
            with self.subTest(case=case['id']):
                self.assertEqual(case['pack77'], case['pack77var'])

    def test_hash_is_unresolved_until_pending_calls_are_committed(self):
        self.assertEqual([(True, '<...> 26AT101'), (True, '<...> 26AT101'),
                          (True, '<1AT106> 26AT101'), (True, '26AT101 <1AT106>'),
                          (True, '<1AT106> K1ABC -10'), (True, 'K1ABC <1AT106> -10')],
                         self.decoded('pending-and-committed-hash'))
        lookups = [s['resolved'] for s in self.scenarios['pending-and-committed-hash']
                   if s['op'] == 'lookup']
        self.assertEqual(['<...>'] * 3 + ['<1AT106>'] * 3, lookups)

    def test_received_cq_teaches_the_call_after_merge(self):
        self.assertEqual([(True, '<...> 26AT101'), (True, 'CQ 1AT106'),
                          (True, '<...> 26AT101'), (True, '<1AT106> 26AT101'),
                          (True, '26AT101 <1AT106>'), (True, '<1AT106> K1ABC -10')],
                         self.decoded('learn-from-cq'))
        self.assertEqual(['<26AT101>'] * 3,
                         [s['resolved'] for s in self.scenarios['learn-full-call-from-directed']
                          if s['op'] == 'lookup'])

    def test_station_identity_does_not_resolve_both_type4_orientations(self):
        self.assertEqual([(True, '<1AT106> 26AT101'), (True, '26AT101 <...>'),
                          (True, '<1AT106> K1ABC -10'), (True, 'K1ABC <1AT106> -10')],
                         self.decoded('station-call-receive-orientations'))
        self.assertEqual([(True, '<...> 26AT101'), (True, '<...> K1ABC -10')],
                         self.decoded('unrelated-known-call'))

    def test_hash_collisions_replace_the_identity_with_last_learned_call(self):
        hashes = self.fixture['hashes']
        self.assertEqual(1928, hashes['1AT0002']['12'])
        self.assertEqual(hashes['1AT0002']['12'], hashes['1AT0050']['12'])
        self.assertNotEqual(hashes['1AT0002']['22'], hashes['1AT0050']['22'])
        self.assertEqual(2524178, hashes['5AT179']['22'])
        self.assertEqual(hashes['5AT179']['22'], hashes['10AT107']['22'])
        for width, first, second in [(10, '1AT0002', '1AT0050'),
                                     (12, '1AT0002', '1AT0050'),
                                     (22, '5AT179', '10AT107')]:
            with self.subTest(width=width):
                lookups = [s['resolved'] for s in self.scenarios[f'collision-{width}-last-learned']
                           if s['op'] == 'lookup']
                expected = [f'<{first}>', f'<{second}>']
                if width != 10:
                    expected = ['<...>'] + expected + [f'<{first}>']
                self.assertEqual(expected, lookups)
        self.assertEqual([(True, '<1AT0002> 26AT101'), (True, '<1AT0050> 26AT101'),
                          (True, '<1AT0002> 26AT101')], self.decoded('collision-12-last-learned'))

    def test_lossy_messages_are_recorded_without_claiming_round_trip_success(self):
        self.assertEqual([(True, '1AT106 26AT10')], self.decoded('cold-long-calls-report-loss'))
        self.assertEqual([(True, '<...> 26AT101')], self.decoded('cold-type4-report-loss'))
        self.assertEqual([(True, 'CQ 1AT106')], self.decoded('cold-cq-locator-loss'))
        self.assertEqual([(True, '26AT101 R-10')], self.decoded('cold-one-26AT101-Rminus10'))
        self.assertEqual([(True, '26AT101 RR73')], self.decoded('cold-one-26AT101-RR73'))
        warm = self.decoded('warm-finals-and-lossy-reports')
        self.assertIn((True, '<1AT106> 26AT101 RR73'), warm)
        self.assertIn((True, '26AT101 <1AT106> 73'), warm)
        self.assertIn((True, '<1AT106> 26AT101'), warm)

    def test_invalid_type4_payloads_and_reserved_types_fail_unpacking(self):
        self.assertEqual([(False, 'CQ 53HDFKJEASD')], self.decoded('cold-invalid-long-letters'))
        self.assertEqual([(False, 'CQ 1234AT1')], self.decoded('cold-invalid-long-prefix'))
        self.assertEqual([(False, 'failed unpack')], self.decoded('invalid-payload-characters'))
        for kind in (6, 7):
            self.assertEqual([(False, '')], self.decoded(f'reserved-type-{kind}'))


class PackingIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='wsjtcb-packing-test-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        shutil.copytree(ROOT / 'reference', self.root / 'reference')
        (self.root / 'scripts').mkdir()
        shutil.copy2(ROOT / 'scripts/wsjtcb_packing.py', self.root / 'scripts/wsjtcb_packing.py')

    def check(self, **environment):
        return subprocess.run([sys.executable, str(self.root / 'scripts/wsjtcb_packing.py'), '--check'],
                              env={**os.environ, **environment}, text=True, capture_output=True, timeout=60)

    def test_each_modified_production_source_is_rejected_before_compilation(self):
        manifest = json.loads((self.root / 'reference/wsjtcb/packing-sources.json').read_text())
        for file in manifest['files']:
            with self.subTest(source=file['path']):
                path = self.root / 'reference/wsjtcb' / file['local']
                original = path.read_bytes()
                try:
                    path.write_bytes(original + b'\n! modified\n')
                    result = self.check(FC='missing-compiler')
                    self.assertEqual(1, result.returncode)
                    self.assertIn('source checksum mismatch', result.stderr)
                finally:
                    path.write_bytes(original)

    def test_changed_payload_is_rejected(self):
        path = self.root / 'reference/wsjtcb/packing.json'
        document = json.loads(path.read_text())
        bits = document['packing'][0]['pack77']['payload77']
        document['packing'][0]['pack77']['payload77'] = str(1 - int(bits[0])) + bits[1:]
        path.write_text(json.dumps(document, indent=2) + '\n')
        result = self.check()
        self.assertEqual(1, result.returncode)
        self.assertIn('Packing fixture differs', result.stderr)

    def test_changed_inputs_require_regeneration(self):
        path = self.root / 'reference/wsjtcb/packing-cases.json'
        document = json.loads(path.read_text())
        document['packing'][0]['input'] = 'CQ 1AT106'
        path.write_text(json.dumps(document, indent=2) + '\n')
        result = self.check()
        self.assertEqual(1, result.returncode)
        self.assertIn('Packing fixture differs', result.stderr)

    def test_duplicate_ids_are_rejected_before_compilation(self):
        path = self.root / 'reference/wsjtcb/packing-cases.json'
        document = json.loads(path.read_text())
        document['packing'][1]['id'] = document['packing'][0]['id']
        path.write_text(json.dumps(document))
        result = self.check(FC='missing-compiler')
        self.assertEqual(1, result.returncode)
        self.assertIn('duplicate IDs', result.stderr)

    def test_mismatched_reference_revision_is_rejected(self):
        path = self.root / 'reference/wsjtcb/packing-sources.json'
        document = json.loads(path.read_text())
        document['revision'] = '0' * 40
        path.write_text(json.dumps(document))
        result = self.check(FC='missing-compiler')
        self.assertEqual(1, result.returncode)
        self.assertIn('Inconsistent packing reference', result.stderr)

    def test_missing_compiler_has_clear_diagnostic(self):
        result = self.check(FC='missing-compiler')
        self.assertEqual(1, result.returncode)
        self.assertIn('A Fortran compiler is required', result.stderr)
        self.assertNotIn('Traceback', result.stderr)


if __name__ == '__main__':
    unittest.main()
