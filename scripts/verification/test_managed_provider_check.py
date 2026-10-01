"""Offline launcher safety; no connection file or provider is contacted."""
import json
import os
from pathlib import Path
import tempfile
import unittest

from managed_provider_check import acquire_lock, checked_private_file, load_budget, private_state


class PrivateStateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='moneytoad-launcher-test-')
        self.state = Path(self.temp.name).resolve()
        self.state.chmod(0o700)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, name, value):
        path = self.state / name
        path.write_text(json.dumps(value))
        path.chmod(0o600)
        return path

    def test_private_state_requires_exact_mode(self):
        self.assertEqual(private_state(self.state), self.state)
        self.state.chmod(0o755)
        with self.assertRaisesRegex(ValueError, 'STATE_OWNER_OR_MODE'):
            private_state(self.state)

    def test_budget_is_persistent_not_reset(self):
        path = load_budget(self.state)
        value = {'loginAttempts': 7, 'commandEquivalents': 7900}
        path.write_text(json.dumps(value))
        self.assertEqual(json.loads(load_budget(self.state).read_text()), value)

    def test_budget_exhaustion_prevents_work(self):
        for value in ({'loginAttempts': 8, 'commandEquivalents': 0},
                      {'loginAttempts': 0, 'commandEquivalents': 8000}):
            self.write('budget.json', value)
            with self.assertRaisesRegex(ValueError, 'WORKFLOW_BUDGET_EXHAUSTED'):
                load_budget(self.state)

    def test_incomplete_cleanup_blocks_retry(self):
        path = self.write('ledger-test.json', {'cleanupComplete': False})
        with self.assertRaisesRegex(ValueError, 'PRIOR_CLEANUP_UNCONFIRMED'):
            load_budget(self.state)
        path.write_text(json.dumps({'cleanupComplete': True}))
        self.assertTrue(load_budget(self.state).is_file())

    def test_symlink_and_hardlink_files_rejected(self):
        target = self.write('target', {})
        linked = self.state / 'linked'
        linked.symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'PRIVATE_FILE_OWNER_OR_MODE'):
            checked_private_file(linked)
        linked.unlink()
        os.link(target, linked)
        with self.assertRaisesRegex(ValueError, 'PRIVATE_FILE_OWNER_OR_MODE'):
            checked_private_file(linked)

    def test_lock_is_exclusive(self):
        lock = acquire_lock(self.state)
        try:
            with self.assertRaises(BlockingIOError):
                acquire_lock(self.state)
        finally:
            lock.close()

    def test_lock_cannot_follow_symlink(self):
        target = self.write('target', {})
        (self.state / 'run.lock').symlink_to(target)
        with self.assertRaises(OSError):
            acquire_lock(self.state)


if __name__ == '__main__':
    unittest.main()
