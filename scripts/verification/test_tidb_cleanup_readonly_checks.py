"""Local-only entrypoint and exact namespace safety; no provider inputs."""
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import demo_capacity_checks as shared
import tidb_cleanup_readonly_checks as current


class CleanupReadOnlyRunnerTest(unittest.TestCase):
    def test_entrypoint_reuses_owned_regression_with_distinct_output(self):
        with mock.patch.object(shared, 'main', return_value=2) as run:
            self.assertEqual(current.main(), 2)
            run.assert_called_once_with(evidence_directory='TIDB_CLEANUP_READONLY')

    def test_output_cannot_overwrite_any_prior_namespace(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            previous = shared.evidence_output(root, 'DEMO_ADMISSION_LOCK', 'same')
            previous.mkdir(parents=True)
            current = shared.evidence_output(root, 'TIDB_CLEANUP_READONLY', 'same')
            self.assertNotEqual(current, previous)
            current.mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, 'EVIDENCE_ALREADY_EXISTS'):
                shared.evidence_output(root, 'TIDB_CLEANUP_READONLY', 'same')

    def test_rejects_arbitrary_namespace_and_traversal(self):
        with self.assertRaisesRegex(ValueError, 'EVIDENCE_DIRECTORY_REJECTED'):
            shared.evidence_output(Path('.'), 'UNREVIEWED', 'new')
        for label in ('../old', '/tmp', 'new/child', ''):
            with self.assertRaisesRegex(ValueError, 'EVIDENCE_LABEL_REJECTED'):
                shared.evidence_output(Path('.'), 'TIDB_CLEANUP_READONLY', label)


if __name__ == '__main__':
    unittest.main()
