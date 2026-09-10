import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import public_evidence as pe


class PublicProjectionTest(unittest.TestCase):
    def test_backend_constructs_allowlist_and_preserves_failures(self):
        raw = {'status': 'FAIL', 'exit_code': 1, 'cleanup_complete': False,
               'userId': 999, 'sid': 'not-a-real-session', 'resources': {'pid': 999},
               'suites': [{'name': 'SyntheticSuite', 'tests': 2, 'failures': 1, 'errors': 0,
                           'skipped': 0, 'cases': [{'name': 'contract', 'status': 'FAIL',
                                                   'email': 'ignored'}]}]}
        clean = pe.backend(raw)
        self.assertEqual((clean['status'], clean['tests'], clean['failures']), ('FAIL', 2, 1))
        self.assertFalse(clean['cleanup_complete'])
        self.assertEqual(pe.forbidden(clean), [])
        self.assertNotIn('resources', clean)

    def test_parameterized_labels_use_ordinal_without_relaxing_scanner(self):
        raw = {'status': 'PASS', 'exit_code': 0, 'cleanup_complete': True,
               'suites': [{'name': 'HeaderContract', 'tests': 1, 'failures': 0, 'errors': 0,
                           'skipped': 0, 'cases': [{'name': 'header Bearer ' + 'never-issued', 'status': 'PASS'}]}]}
        clean = pe.backend(raw)
        self.assertEqual(clean['suites'][0]['cases'][0]['test'], 'case-1')
        self.assertEqual(clean['tests'], 1)
        self.assertEqual(pe.forbidden(clean), [])

    def test_nested_fields_and_values_fail_closed(self):
        for key in pe.FORBIDDEN_KEYS:
            with self.subTest(key=key):
                self.assertTrue(pe.forbidden({'safe': [{key: 'omitted'}]}))
        for value in ('Bearer ' + 'never-issued', 'someone' + '@' + 'example.invalid',
                      '/' + 'Users/' + 'example/project', 'demoRefreshToken=' + 'never-issued',
                      '-----BEGIN ' + 'PRIVATE KEY-----'):
            self.assertTrue(pe.forbidden({'test': value}))

    def test_frontend_drops_runtime_paths_and_test_payloads(self):
        raw = {'status': 'PASS', 'work': 'omitted', 'checks': [{'name': 'types', 'status': 'PASS',
                'exit_code': 0, 'command': ['not-for-publication'], 'env_overrides': {'secret': 'omitted'}}]}
        self.assertEqual(pe.frontend(raw), {'status': 'PASS', 'checks': [
            {'check': 'types', 'status': 'PASS', 'exit_code': 0}]})

    def test_write_refuses_forbidden_payload(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(pe, 'OUT', Path(directory)):
            with self.assertRaises(ValueError):
                pe.save('rejected.json', {'userId': 999})
            self.assertEqual(list(Path(directory).iterdir()), [])
            pe.save('accepted.json', {'tests': 275, 'status': 'PASS'})
            self.assertEqual(json.loads((Path(directory) / 'accepted.json').read_text())['tests'], 275)

    def test_shared_runner_uses_actual_output_boundary(self):
        source = (Path(__file__).parent / 'a1_budget_ownership.py').read_text()
        self.assertIn('save("be-regression-summary.json", backend(result))', source)
        wrapper = (Path(__file__).parent / 'demo_browser_e2e_be.py').read_text()
        self.assertNotIn('shared.redact =', wrapper)


if __name__ == '__main__':
    unittest.main()
