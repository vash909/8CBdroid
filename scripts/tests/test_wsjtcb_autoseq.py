"""Reject incomplete physical traces rather than accepting model-only success claims."""
import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import autoseq_audio_checks as checks


def trace():
    def role(name, first, texts, qso):
        return dict(role=name, qso=qso, sent=[dict(text=text, utc_millis=first+i*30000) for i, text in enumerate(texts)], received=[])
    calling = role('calling', 30000, ['CQ 1AT106', 'CQ 1AT106', '26AT101 +10', '26AT101 +10', '26AT101 RR73'], 'COMPLETE')
    answering = role('answering', 300000, ['<26AT101> 1AT106', '26AT101 R-07', '26AT101 73'], 'COMPLETE')
    quiet = role('no-response', 600000, ['CQ 1AT106']*3, 'ABORTED')
    plays = []
    for item, expected in [(calling, [(1, 'reply'), (2, 'reply'), (3, 'roger'), (4, 'signoff')]),
                           (answering, [(0, 'report'), (1, 'final')])]:
        for index, name in expected:
            utc = item['sent'][index]['utc_millis']+15000
            text = checks.TEXTS[name]
            item['received'].append(dict(utc_millis=utc, text=text, captured_samples=162000))
            plays.append(dict(role=item['role'], utc_millis=utc, text=text))
    return dict(roles=[calling, answering, quiet]), plays


class AutomaticPhysicalTraceTest(unittest.TestCase):
    def test_complete_immediate_loss_repeat_and_no_response_trace(self):
        report, plays = trace()
        checks.validate_roles(report, plays)

    def test_whole_cycle_delay_is_not_immediate_recovery(self):
        report, plays = trace()
        report['roles'][0]['sent'][2]['utc_millis'] += 30000
        with self.assertRaises(AssertionError): checks.validate_roles(report, plays)

    def test_full_or_missing_window_cannot_claim_early_reception(self):
        report, plays = trace()
        report['roles'][1]['received'][0]['captured_samples'] = 180000
        with self.assertRaises(AssertionError): checks.validate_roles(report, plays)

    def test_own_output_and_foreign_final_cannot_validate_a_trace(self):
        report, plays = trace()
        original = copy.deepcopy(report)
        report['roles'][0]['received'].append(dict(text='CQ 1AT106'))
        with self.assertRaises(AssertionError): checks.validate_roles(report, plays)
        original['roles'][0]['received'][-1]['text'] = '161XZ085 73'
        with self.assertRaises(AssertionError): checks.validate_roles(original, plays)

    def test_silent_peer_is_not_a_completed_contact(self):
        report, plays = trace(); report['roles'][2]['qso'] = 'COMPLETE'
        with self.assertRaises(AssertionError): checks.validate_roles(report, plays)


if __name__ == '__main__':
    unittest.main()
