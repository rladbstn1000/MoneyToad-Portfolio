"""Offline evidence-boundary contracts; no product or provider connections."""
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import counterless_demo_capacity_checks as current
import demo_capacity_checks as shared


class CounterlessVerificationBoundaryTest(unittest.TestCase):
    def test_entrypoint_reuses_exact_existing_runner_with_distinct_output(self):
        with mock.patch.object(shared, 'main', return_value=2) as run:
            self.assertEqual(current.main(), 2)
            run.assert_called_once_with(evidence_directory='COUNTERLESS_DEMO_CAPACITY')

    def test_focus_retains_commit_failure_and_ack_loss_transaction_regressions(self):
        with mock.patch.object(shared, 'discover_classes', return_value=[
                'com.potg.don.auth.demo.DemoAuthServiceTest',
                'com.potg.don.auth.demo.DemoAuthLoginTransactionTest',
                'com.potg.don.DonApplicationTests']):
            self.assertEqual(shared.selected_classes(Path('.'), focus=True), [
                'com.potg.don.auth.demo.DemoAuthServiceTest',
                'com.potg.don.auth.demo.DemoAuthLoginTransactionTest'])

    def test_new_output_cannot_overwrite_current_or_historical_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            historical = shared.evidence_output(root, 'DEMO_CAPACITY_CLEANUP', 'same-label')
            historical.mkdir(parents=True)
            (historical / 'summary.json').write_text('historical-preservation-canary')
            output = shared.evidence_output(root, 'COUNTERLESS_DEMO_CAPACITY', 'same-label')
            self.assertNotEqual(output, historical)
            output.mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, 'EVIDENCE_ALREADY_EXISTS'):
                shared.evidence_output(root, 'COUNTERLESS_DEMO_CAPACITY', 'same-label')
            self.assertEqual((historical / 'summary.json').read_text(), 'historical-preservation-canary')

    def test_arbitrary_directory_and_path_traversal_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            for invalid in ('../DEMO_CAPACITY_CLEANUP', '/tmp', 'not-reviewed', ''):
                with self.assertRaisesRegex(ValueError, 'EVIDENCE_DIRECTORY_REJECTED'):
                    shared.evidence_output(directory, invalid, 'safe-label')
            for invalid in ('../old', '/tmp', '.', 'label/child', ''):
                with self.assertRaisesRegex(ValueError, 'EVIDENCE_LABEL_REJECTED'):
                    shared.evidence_output(directory, 'COUNTERLESS_DEMO_CAPACITY', invalid)


if __name__ == '__main__':
    unittest.main()
