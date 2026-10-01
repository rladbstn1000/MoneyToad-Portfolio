"""No network: added browser cases remain an explicit completeness contract."""
import copy
import unittest
from public_evidence import browser_series

class AbuseVerificationTest(unittest.TestCase):
    def summary(self, cases=3):
        return {'status': 'PASS', 'cleanup_complete': True, 'modes': [
            {'cases': [{'status': 'passed'} for _ in range(cases)],
             'networks': [{'external_attempts': 0, 'backend_attempts': 0}]} for _ in range(2)]}

    def test_new_six_cases_pass_only_when_explicitly_selected(self):
        runs = [('browser-0123456789ab-summary.json', self.summary())]
        self.assertEqual('FAIL', browser_series(runs, 1)['status'])
        self.assertEqual('PASS', browser_series(runs, 1, expected_cases=6)['status'])

    def test_historical_four_case_contract_is_preserved(self):
        runs = [('browser-0123456789ab-summary.json', self.summary(2))]
        self.assertEqual('PASS', browser_series(runs, 1)['status'])
        self.assertEqual('FAIL', browser_series(runs, 1, expected_cases=6)['status'])

    def test_fail_skip_missing_run_egress_or_cleanup_never_passes(self):
        baseline = self.summary()
        variants = []
        for status in ('failed', 'skipped'):
            changed = copy.deepcopy(baseline); changed['modes'][0]['cases'][0]['status'] = status; variants.append(changed)
        changed = copy.deepcopy(baseline); changed['cleanup_complete'] = False; variants.append(changed)
        changed = copy.deepcopy(baseline); changed['modes'][0]['networks'][0]['external_attempts'] = 1; variants.append(changed)
        for item in variants:
            self.assertEqual('FAIL', browser_series([('browser-0123456789ab-summary.json', item)], 1, expected_cases=6)['status'])
        self.assertEqual('FAIL', browser_series([], 2, expected_cases=6)['status'])

if __name__ == '__main__': unittest.main()
