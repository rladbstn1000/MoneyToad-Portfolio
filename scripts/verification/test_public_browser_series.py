import unittest
from public_evidence import browser_series, forbidden


class BrowserSeriesTest(unittest.TestCase):
    def summary(self):
        return {'status': 'PASS', 'cleanup_complete': True, 'modes': [
            {'cases': [{'status': 'passed'}, {'status': 'passed'}],
             'networks': [{'external_attempts': 0, 'backend_attempts': 0}]}
            for _ in range(2)]}

    def test_only_current_invocation_entries_are_published(self):
        rows = [('browser-' + marker * 12 + '-summary.json', self.summary()) for marker in ('a', 'b')]
        result = browser_series(rows, 2)
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual([row['evidence_file'] for row in result['runs']], [row[0] for row in rows])
        self.assertEqual(forbidden(result), [])
        self.assertEqual(browser_series(rows[:1], 2)['status'], 'FAIL')
        self.assertEqual(browser_series([], 2)['status'], 'FAIL')

    def test_failures_egress_and_cleanup_are_not_hidden(self):
        for failure in ('status', 'cleanup', 'egress', 'case'):
            summary = self.summary()
            if failure == 'status': summary['status'] = 'FAIL'
            if failure == 'cleanup': summary['cleanup_complete'] = False
            if failure == 'egress': summary['modes'][0]['networks'][0]['external_attempts'] = 1
            if failure == 'case': summary['modes'][0]['cases'][0]['status'] = 'skipped'
            result = browser_series([('browser-' + 'a' * 12 + '-summary.json', summary)], 1)
            self.assertEqual(result['status'], 'FAIL')
            self.assertEqual(result['runs'][0]['status'], 'FAIL')

    def test_invalid_evidence_path_is_rejected(self):
        with self.assertRaises(ValueError):
            browser_series([('../outside.json', self.summary())], 1)


if __name__ == '__main__':
    unittest.main()
