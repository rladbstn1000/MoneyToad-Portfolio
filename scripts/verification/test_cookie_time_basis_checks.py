"""No network: cookie evidence contains only approved retention metadata."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import cookie_time_basis_checks as runner
import demo_capacity_checks as base
from public_evidence import browser, forbidden


class CookieVerificationTest(unittest.TestCase):
    def project(self, cookie):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory) / 'public-demo'
            folder.mkdir()
            (folder / 'cookie-contract.json').write_text(json.dumps(cookie))
            return browser({'status': 'PASS', 'exit_code': 0, 'cleanup_complete': True}, directory)

    def test_only_numeric_retention_and_existing_boolean_contracts_are_projected(self):
        cookie = {'browserClockValidity': True, 'absoluteExpiryUnchanged': True,
                  'loginCookie': {'maxAge': 3599, 'unreviewed': 'discard-this-data'},
                  'rotatedCookie': {'maxAge': 3588}, 'deletedCookie': {'maxAge': 0}}
        projected = self.project(cookie)
        self.assertEqual(projected['modes'][0]['cookie_attributes'], {
            'browserClockValidity': True, 'absoluteExpiryUnchanged': True,
            'login_max_age': 3599, 'reissue_max_age': 3588, 'logout_max_age': 0})
        self.assertNotIn('discard-this-data', json.dumps(projected))
        self.assertFalse(forbidden(projected))

    def test_invalid_retention_projection_fails_without_serializing_input(self):
        for age in (True, -1, 3601, 1.5, 'discard-this-data', None):
            with self.subTest(kind=type(age).__name__):
                with self.assertRaisesRegex(ValueError, '^COOKIE_METADATA_PROJECTION_REJECTED$'):
                    self.project({'loginCookie': {'maxAge': age}})

    def test_historical_browser_evidence_does_not_invent_new_observations(self):
        projected = self.project({'absoluteExpiryUnchanged': True})
        self.assertEqual(projected['modes'][0]['cookie_attributes'], {'absoluteExpiryUnchanged': True})

    def test_full_discovery_and_reviewed_counts_are_preserved(self):
        original = base.backend_expected_counts()
        selected = base.selected_classes(base.ROOT, False)
        def verify(**kwargs):
            self.assertEqual(base.selected_classes(base.ROOT, False), selected)
            self.assertEqual(base.selected_classes(base.ROOT, True), [runner.COOKIE_CLASS])
            self.assertEqual(base.backend_expected_counts(), {**original, 'DemoRefreshCookieTest': 11})
            self.assertEqual(kwargs['browser_cases'], 6)
            self.assertEqual(kwargs['frontend_counts'], (225, 148))
            return 0
        with patch('sys.argv', ['cookie_time_basis_checks.py']), patch.object(base, 'main', verify):
            self.assertEqual(runner.main(), 0)
        self.assertEqual(base.backend_expected_counts(), original)


if __name__ == '__main__':
    unittest.main()
