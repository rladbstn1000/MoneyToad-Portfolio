"""Offline lock-runner boundaries and bounded container diagnostic projections."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import demo_admission_lock_checks as current
import demo_capacity_checks as shared
from public_evidence import forbidden
from render_runtime import limited_runtime_projection


class AdmissionLockVerificationBoundaryTest(unittest.TestCase):
    def test_entrypoint_keeps_owned_existing_regression_and_distinct_namespace(self):
        with mock.patch.object(shared, 'main', return_value=2) as run:
            self.assertEqual(current.main(), 2)
            run.assert_called_once_with(evidence_directory='DEMO_ADMISSION_LOCK')

    def test_historical_and_current_evidence_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            historical = [shared.evidence_output(root, namespace, 'same-label') for namespace in
                          ('DEMO_CAPACITY_CLEANUP', 'COUNTERLESS_DEMO_CAPACITY')]
            for folder in historical:
                folder.mkdir(parents=True)
                (folder / 'summary.json').write_text('previous-evidence')
            output = shared.evidence_output(root, 'DEMO_ADMISSION_LOCK', 'same-label')
            self.assertNotIn(output, historical)
            output.mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, 'EVIDENCE_ALREADY_EXISTS'):
                shared.evidence_output(root, 'DEMO_ADMISSION_LOCK', 'same-label')
            for folder in historical:
                self.assertEqual((folder / 'summary.json').read_text(), 'previous-evidence')

    def test_new_namespace_rejects_path_traversal(self):
        for label in ('../old', '/tmp', 'new/child', ''):
            with self.assertRaisesRegex(ValueError, 'EVIDENCE_LABEL_REJECTED'):
                shared.evidence_output(Path('.'), 'DEMO_ADMISSION_LOCK', label)


class LimitedRuntimeDiagnosticTest(unittest.TestCase):
    def test_oom_observation_preserves_exit_but_never_runtime_material(self):
        canary = 'sensitive-canary-value'
        value = limited_runtime_projection({
            'Status': 'exited', 'ExitCode': 137, 'OOMKilled': True, 'Running': False,
            'Error': canary, 'Pid': 999, 'StartedAt': canary,
        }, 'Starting DonApplication using Java\n' + canary + '\njava.lang.OutOfMemoryError: ' + canary)
        self.assertEqual(value['exit_code'], 137)
        self.assertIs(value['oom_killed'], True)
        self.assertEqual(value['container_state'], 'exited')
        self.assertEqual(value['reason'], 'OOM_KILLED')
        self.assertEqual(value['last_safe_boot_phase'], 'SPRING_BOOT_STARTING')
        self.assertEqual(value['known_failure_classes'], ['OutOfMemoryError'])
        self.assertNotIn(canary, json.dumps(value))
        self.assertNotIn('Pid', json.dumps(value))
        self.assertEqual(forbidden(value), [])

    def test_exit_137_alone_does_not_establish_oom(self):
        value = limited_runtime_projection({'Status': 'exited', 'ExitCode': 137, 'OOMKilled': False}, '')
        self.assertIs(value['oom_killed'], False)
        self.assertEqual(value['reason'], 'PROCESS_EXIT_NONZERO')
        self.assertEqual(value['last_safe_boot_phase'], 'NOT_OBSERVED')

    def test_last_observed_boot_phase_uses_log_order_not_inferred_readiness(self):
        value = limited_runtime_projection({'Status': 'exited', 'ExitCode': 1, 'OOMKilled': False},
            'Starting DonApplication\nHHH000204: Processing PersistenceUnitInfo\n'
            'HikariPool-1 - Starting...\nHikariPool-1 - Start completed.\n'
            'Initialized JPA EntityManagerFactory\nTomcat started on port 9999\n')
        self.assertEqual(value['last_safe_boot_phase'], 'HTTP_BOUND')
        self.assertEqual(value['reason'], 'PROCESS_EXIT_NONZERO')
        self.assertNotEqual(value['last_safe_boot_phase'], 'APPLICATION_STARTED')

    def test_unknown_and_malformed_fields_are_not_success_or_serialized(self):
        value = limited_runtime_projection({'Status': 'private-engine-value', 'ExitCode': True,
                                             'OOMKilled': 'true', 'Running': 'true'}, 'unrecognized raw details')
        self.assertEqual(value['container_state'], 'UNKNOWN')
        self.assertIsNone(value['exit_code'])
        self.assertIsNone(value['oom_killed'])
        self.assertEqual(value['reason'], 'UNKNOWN')
        self.assertEqual(value['last_safe_boot_phase'], 'NOT_OBSERVED')
        self.assertEqual(forbidden(value), [])

    def test_engine_error_becomes_only_fixed_category(self):
        value = limited_runtime_projection({'Status': 'dead', 'ExitCode': 1, 'OOMKilled': False,
                                             'Error': 'private socket or mount details'}, '')
        self.assertEqual(value['reason'], 'ENGINE_REPORTED_ERROR')
        self.assertNotIn('private socket', json.dumps(value))

    def test_missing_inspection_does_not_claim_observation(self):
        value = limited_runtime_projection({}, '')
        self.assertIs(value['state_observed'], False)
        self.assertIsNone(value['exit_code'])
        self.assertIsNone(value['oom_killed'])
        self.assertEqual(value['container_state'], 'UNKNOWN')
        self.assertEqual(value['last_safe_boot_phase'], 'NOT_OBSERVED')

    def test_running_container_at_timeout_is_distinct_from_process_exit(self):
        value = limited_runtime_projection({'Status': 'running', 'ExitCode': 0, 'OOMKilled': False,
                                             'Running': True}, 'Started DonApplication in 12 seconds')
        self.assertEqual(value['reason'], 'RUNNING_AT_FAILURE')
        self.assertEqual(value['last_safe_boot_phase'], 'APPLICATION_STARTED')
        self.assertEqual(forbidden(value), [])


if __name__ == '__main__':
    unittest.main()
