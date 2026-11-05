"""Native desktop behavior and integrity checks for the R03c reference corpus."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import wsjtcb_qso as reference


class DesktopQsoTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        manifest, packing_manifest, scenarios = reference.load_inputs()
        cls.fixture = json.loads(reference.generate(manifest, packing_manifest, scenarios))
        cls.traces = {trace['id']: trace for trace in cls.fixture['traces']}

    def result(self, name, step=0):
        return self.traces[name]['steps'][step]['result']

    def test_generated_cb_messages_use_destination_only_reports_and_finals(self):
        self.assertEqual(['<26AT101> 1AT106 ', '26AT101 -07', '26AT101 R-07',
                          '26AT101 RR73', '26AT101 73', 'CQ 1AT106'],
                         self.traces['generate-1']['initial']['messages'])
        self.assertEqual(['<1AT106> 26AT101 ', '1AT106 +05', '1AT106 R+05',
                          '1AT106 RR73', '1AT106 73', 'CQ 26AT101'],
                         self.traces['generate-2']['initial']['messages'])
        self.assertEqual('161XZ085 R+00', self.traces['generate-3']['initial']['messages'][2])
        self.assertEqual('999ZZ/ZZ R-50', self.traces['generate-4']['initial']['messages'][2])
        self.assertEqual('001AB123 R+49', self.traces['generate-5']['initial']['messages'][2])

    def test_generated_one_call_reports_and_finals_survive_the_real_packer(self):
        payloads = {p['message']: p for p in self.fixture['message_payloads']}
        for message in ['26AT101 -07', '26AT101 R-07', '26AT101 RR73', '26AT101 73',
                        '161XZ085 R+00', '999ZZ/ZZ R-50', '001AB123 R+49']:
            with self.subTest(message=message):
                self.assertTrue(payloads[message]['cold_success'])
                self.assertEqual(message, payloads[message]['cold_decoded'])
                self.assertEqual(77, len(payloads[message]['payload77']))

    def test_parser_flags_are_not_round_trip_or_peer_admission_guarantees(self):
        unresolved = self.traces['parse-unresolved']['steps'][0]['parsed']
        self.assertTrue(unresolved['standard'])
        self.assertFalse(unresolved['complete_peer'])
        self.assertFalse(self.traces['parse-partial']['steps'][0]['parsed']['complete_peer'])
        report = self.traces['parse-report']['steps'][0]['parsed']
        self.assertFalse(report['standard'])
        self.assertEqual(['1AT106 -07', '', '1AT106', '-07'], report['words'])
        self.assertEqual('', report['report'])
        self.assertEqual('-12', report['snr_report'])
        self.assertEqual(1500, report['df'])
        self.assertEqual(43200, report['time'])
        self.assertTrue(self.traces['parse-cq-grid-loss']['steps'][0]['parsed']['standard'])

    def test_cq_admission_and_local_identity_edge_cases(self):
        for name in ['hashed-peer', 'free-peer', 'other-complete-peer', 'slash']:
            self.assertEqual(1, self.result('cq-answer-' + name)['processed'], name)
        for name in ['unresolved', 'partial', 'amateur']:
            self.assertEqual(0, self.result('cq-answer-' + name)['processed'], name)
        for name in ['trailing-local-hash', 'leading-peer-hash']:
            self.assertEqual('1AT106', self.result('cq-answer-' + name)['peer'], name)
        self.assertEqual('26AT101', self.result('cq-answer-both-hashed')['peer'])
        self.assertEqual(0, self.result('auto-trailing-local-hash-final')['processed'])

    def test_auto_seq_controls_and_finished_flag_block_progression(self):
        for name in ['auto-off', 'seq-off', 'seq-disabled', 'seq-hidden', 'finished']:
            with self.subTest(name=name):
                state = self.result(name)
                self.assertEqual('REPORT', state['progress'])
                self.assertEqual(0, state['processed'])

    def test_report_duplicate_and_final_sequence_preserves_selected_peer(self):
        trace = self.traces['long-call-exchange']['steps']
        self.assertEqual(['REPORT', 'REPORT', 'ROGER_REPORT', 'ROGER_REPORT', 'ROGERS',
                          'SIGNOFF', 'SIGNOFF', 'SIGNOFF', 'SIGNOFF'],
                         [step['result']['progress'] for step in trace])
        self.assertEqual([2, 2, 3, 3, 4, 5, 5, 5, 5], [step['result']['tx'] for step in trace])
        self.assertTrue(all(step['result']['peer'] == '26AT101' for step in trace))
        self.assertEqual('-07', trace[2]['result']['received_report'])
        self.assertEqual(trace[6]['result'], trace[7]['result'])
        self.assertEqual(trace[7]['result'], trace[8]['result'])

    def test_two_station_exchange_relays_actual_generated_messages(self):
        steps = self.traces['two-station-generated-exchange']['steps']
        self.assertEqual(['CQ 1AT106', '<1AT106> 26AT101', '26AT101 -12', '1AT106 R-12',
                          '26AT101 RR73', '1AT106 73'], [s['line'][24:] for s in steps[:6]])
        self.assertEqual('SIGNOFF', steps[5]['result']['progress'])
        self.assertEqual('SIGNOFF', steps[5]['remote_result']['progress'])
        self.assertEqual('-12', steps[5]['result']['received_report'])
        self.assertEqual('-12', steps[5]['remote_result']['received_report'])
        for key in ['result', 'remote_result']:
            self.assertEqual(steps[7][key], steps[8][key])
            self.assertEqual(steps[8][key], steps[9][key])

    def test_selected_peer_supplies_context_missing_from_one_call_report(self):
        steps = self.traces['out-of-order-and-selected-peer']['steps']
        self.assertEqual('ROGERS', steps[0]['result']['progress'])
        self.assertEqual('ROGER_REPORT', steps[1]['result']['progress'])
        self.assertEqual('161XZ085', steps[3]['result']['peer'])
        self.assertEqual('-07', steps[3]['result']['received_report'])

    def test_manual_malformed_report_differs_from_automatic_admission(self):
        self.assertEqual('REPORT', self.result('auto-one-RminusXX')['progress'])
        self.assertEqual(0, self.result('auto-one-RminusXX')['processed'])
        direct = self.result('process-one-RminusXX')
        self.assertEqual('ROGERS', direct['progress'])
        self.assertEqual('', direct['received_report'])
        self.assertEqual('+99', self.result('auto-one-plus99')['received_report'])
        self.assertEqual('ROGER_REPORT', self.result('auto-one-plus99')['progress'])

    def test_desktop_unrelated_final_behavior_is_recorded_as_a_limitation(self):
        self.assertEqual('SIGNOFF', self.result('unrelated-73')['progress'])
        self.assertEqual('SIGNOFF', self.result('unrelated-RR73')['progress'])
        self.assertEqual('RR73', self.result('unrelated-RR73')['peer'])
        self.assertEqual(0, self.result('unrelated-sender-final')['processed'])

    def test_frequency_tolerances_include_the_boundary(self):
        for df, accepted in [(1475, True), (1474, False), (1525, True), (1526, False)]:
            self.assertEqual(int(accepted), self.result(f'free-final-frequency-{df}')['processed'])
        for df, stopped in [(1450, True), (1449, False), (1550, True), (1551, False)]:
            self.assertEqual(stopped, self.result(f'partner-other-station-{df}')['stop_requested'])


class QsoIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='wsjtcb-qso-test-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        shutil.copytree(ROOT / 'reference', self.root / 'reference')
        (self.root / 'scripts').mkdir()
        for name in ['wsjtcb_qso.py', 'wsjtcb_packing.py']:
            shutil.copy2(ROOT / 'scripts' / name, self.root / 'scripts' / name)

    def check(self, **environment):
        return subprocess.run([sys.executable, str(self.root / 'scripts/wsjtcb_qso.py'), '--check'],
                              env={**os.environ, **environment}, text=True, capture_output=True, timeout=90)

    def test_each_modified_qso_source_fails_before_compilation(self):
        manifest = json.loads((self.root / 'reference/wsjtcb/qso-sources.json').read_text())
        for file in manifest['files']:
            with self.subTest(source=file['path']):
                path = self.root / 'reference/wsjtcb' / file['local']
                original = path.read_bytes()
                try:
                    path.write_bytes(original + b'\n// modified\n')
                    result = self.check(CXX='missing-compiler')
                    self.assertEqual(1, result.returncode)
                    self.assertIn('QSO source checksum mismatch', result.stderr)
                finally:
                    path.write_bytes(original)

    def test_changed_method_extraction_pin_fails_before_compilation(self):
        path = self.root / 'reference/wsjtcb/qso-sources.json'
        document = json.loads(path.read_text())
        document['methods'][0]['sha256'] = '0' * 64
        path.write_text(json.dumps(document))
        result = self.check(CXX='missing-compiler')
        self.assertEqual(1, result.returncode)
        self.assertIn('method extraction checksum mismatch', result.stderr)

    def test_changed_decision_requires_regeneration(self):
        path = self.root / 'reference/wsjtcb/qso.json'
        document = json.loads(path.read_text())
        document['traces'][0]['initial']['tx'] = 6
        path.write_text(json.dumps(document, indent=2) + '\n')
        result = self.check()
        self.assertEqual(1, result.returncode)
        self.assertIn('QSO fixture differs', result.stderr)

    def test_changed_inputs_require_regeneration(self):
        path = self.root / 'reference/wsjtcb/qso-cases.json'
        document = json.loads(path.read_text())
        document['scenarios'][0]['state']['report'] = '-15'
        path.write_text(json.dumps(document, indent=2) + '\n')
        result = self.check()
        self.assertEqual(1, result.returncode)
        self.assertIn('QSO fixture differs', result.stderr)

    def test_unsupported_state_is_rejected_before_compilation(self):
        path = self.root / 'reference/wsjtcb/qso-cases.json'
        document = json.loads(path.read_text())
        document['scenarios'][0]['state']['mode'] = 'MSK144'
        path.write_text(json.dumps(document))
        result = self.check(CXX='missing-compiler')
        self.assertEqual(1, result.returncode)
        self.assertIn('Unsupported QSO state key', result.stderr)

    def test_missing_cpp_compiler_has_a_clear_diagnostic(self):
        result = self.check(CXX='missing-compiler')
        self.assertEqual(1, result.returncode)
        self.assertIn('CXX compiler/tool is required', result.stderr)
        self.assertNotIn('Traceback', result.stderr)


if __name__ == '__main__':
    unittest.main()
