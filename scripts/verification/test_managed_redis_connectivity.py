"""Offline persistent-budget contracts; no approved input or provider is read."""
import json
from pathlib import Path
import tempfile
import unittest
from managed_redis_connectivity import existing_budget, reserve_existing_budget, activation_attempt_boundary


class DiagnosticBudgetTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='moneytoad-redis-budget-test-')
        self.state = Path(self.temp.name).resolve()
        self.state.chmod(0o700)

    def tearDown(self):
        self.temp.cleanup()

    def save(self, value, name='budget.json'):
        path = self.state / name
        path.write_text(json.dumps(value))
        path.chmod(0o600)
        return path

    def test_missing_budget_is_not_initialized(self):
        with self.assertRaisesRegex(ValueError, 'EXISTING_BUDGET_REQUIRED'):
            reserve_existing_budget(self.state)
        self.assertFalse((self.state / 'budget.json').exists())

    def test_reserves_cleanup_before_work_and_preserves_login_count(self):
        self.save({'loginAttempts': 1, 'commandEquivalents': 2640})
        self.assertEqual(reserve_existing_budget(self.state), {'loginAttempts': 1, 'commandEquivalents': 4896})
        self.assertEqual(json.loads((self.state / 'budget.json').read_text()), {'loginAttempts': 1, 'commandEquivalents': 4896})

    def test_corrupt_budget_is_not_reset(self):
        path = self.save({})
        for raw in ('broken-json', '{}', '{"loginAttempts":true,"commandEquivalents":0}'):
            path.write_text(raw)
            with self.assertRaisesRegex(ValueError, 'BUDGET_FORMAT'):
                reserve_existing_budget(self.state)
            self.assertEqual(path.read_text(), raw)

    def test_exhausted_budget_is_unchanged(self):
        value = {'loginAttempts': 1, 'commandEquivalents': 7745}
        path = self.save(value)
        with self.assertRaisesRegex(ValueError, 'WORKFLOW_BUDGET_EXHAUSTED'):
            reserve_existing_budget(self.state)
        self.assertEqual(json.loads(path.read_text()), value)

    def test_incomplete_prior_cleanup_blocks_diagnostic(self):
        self.save({'loginAttempts': 1, 'commandEquivalents': 2640})
        self.save({'cleanupComplete': False}, 'ledger-prior.json')
        with self.assertRaisesRegex(ValueError, 'PRIOR_CLEANUP_UNCONFIRMED'):
            existing_budget(self.state)

    def test_symlink_budget_is_rejected(self):
        self.save({'loginAttempts': 1, 'commandEquivalents': 2640}, 'other.json')
        (self.state / 'budget.json').symlink_to(self.state / 'other.json')
        with self.assertRaisesRegex(ValueError, 'PRIVATE_FILE_OWNER_OR_MODE'):
            existing_budget(self.state)


    def test_first_activation_uses_product_settings_without_comparison(self):
        activation_attempt_boundary(self.state)
        with self.assertRaisesRegex(ValueError, 'FIRST_ATTEMPT_MUST_USE_PRODUCT_SETTINGS'):
            activation_attempt_boundary(self.state, 'protocol')

    def test_second_activation_requires_observed_cause_and_cleanup(self):
        self.save({'cleanupComplete': True}, 'ledger-activation-prior.json')
        with self.assertRaisesRegex(ValueError, 'OBSERVED_COMPARISON_BASIS_REQUIRED'):
            activation_attempt_boundary(self.state)
        self.save({'cleanup_complete': True, 'diagnostics': {'failure_category': 'AUTH_REJECTED'}}, 'result-activation-prior.json')
        with self.assertRaisesRegex(ValueError, 'COMPARISON_NOT_SUPPORTED'):
            activation_attempt_boundary(self.state, 'protocol')
        self.save({'cleanup_complete': True, 'diagnostics': {'failure_category': 'LOCAL_PROBE_REJECTED'}}, 'result-activation-prior.json')
        activation_attempt_boundary(self.state, 'probe-boundary')

    def test_third_activation_exceeds_512_diagnostic_reservation(self):
        self.save({'cleanupComplete': True}, 'ledger-activation-first.json')
        self.save({'cleanupComplete': True}, 'ledger-activation-second.json')
        with self.assertRaisesRegex(ValueError, 'ACTIVATION_DIAGNOSTIC_BUDGET_EXHAUSTED'):
            activation_attempt_boundary(self.state, 'timeout')

    def test_new_reserved_amount_never_refunds_previous_reservation(self):
        self.save({'loginAttempts': 1, 'commandEquivalents': 4896})
        self.assertEqual(reserve_existing_budget(self.state), {'loginAttempts': 1, 'commandEquivalents': 7152})
        self.assertEqual(reserve_existing_budget(self.state), {'loginAttempts': 1, 'commandEquivalents': 9408})
        with self.assertRaisesRegex(ValueError, 'WORKFLOW_BUDGET_EXHAUSTED'):
            reserve_existing_budget(self.state)


if __name__ == '__main__':
    unittest.main()
