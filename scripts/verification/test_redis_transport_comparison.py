"""Synthetic launcher/budget boundaries only: no approved input, child or network."""
import contextlib
import copy
import io
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import tempfile
import unittest
from unittest import mock

import redis_transport_comparison as comparison


class TransportComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='moneytoad-transport-boundary-test-')
        self.root = Path(self.temporary.name).resolve()
        self.state = self.root / 'state'
        self.state.mkdir(mode=0o700)
        self.ledger = self.state / ('ledger-transport-comparison-' + 'a' * 16 + '.json')

    def tearDown(self):
        self.temporary.cleanup()

    def save(self, value, name='budget.json'):
        path = self.state / name
        path.write_text(json.dumps(value))
        path.chmod(0o600)
        return path

    def baseline(self):
        return self.save(dict(comparison.BASELINE))

    def test_missing_state_and_budget_never_reach_initializing_loader(self):
        with mock.patch.object(comparison, 'load_budget') as loader:
            for state, reason in ((self.root / 'absent', 'EXISTING_TASK_STATE_REQUIRED'),
                                  (self.state, 'EXISTING_BUDGET_REQUIRED')):
                with self.assertRaisesRegex(ValueError, reason):
                    comparison.existing_budget(state)
            loader.assert_not_called()
        self.assertFalse((self.root / 'absent').exists())
        self.assertFalse((self.state / 'budget.json').exists())

    def test_one_joint_reservation_preserves_login_and_creates_durable_marker(self):
        budget_path = self.baseline()
        self.save({'cleanupComplete': True}, 'ledger-prior.json')
        budget = comparison.reserve_once(self.state, self.ledger)
        self.assertEqual(budget, {'loginAttempts': 1, 'commandEquivalents': 9408})
        self.assertEqual(json.loads(budget_path.read_text()), budget)
        self.assertFalse(json.loads(self.ledger.read_text())['cleanupComplete'])
        self.assertEqual(stat.S_IMODE(self.ledger.stat().st_mode), 0o600)
        self.assertEqual(comparison.MAX_TOTAL - budget['commandEquivalents'], 592)

    def test_any_prior_joint_marker_blocks_repeat_without_refund(self):
        budget_path = self.baseline()
        for complete in (False, True):
            self.save({'cleanupComplete': complete}, self.ledger.name)
            before = budget_path.read_bytes()
            with self.assertRaisesRegex(ValueError, 'TRANSPORT_COMPARISON_ALREADY_ATTEMPTED'):
                comparison.reserve_once(self.state, self.ledger)
            self.assertEqual(budget_path.read_bytes(), before)

    def test_reservation_cannot_be_repeated_after_successful_reserve(self):
        budget_path = self.baseline()
        comparison.reserve_once(self.state, self.ledger)
        other = self.state / ('ledger-transport-comparison-' + 'b' * 16 + '.json')
        with self.assertRaisesRegex(ValueError, 'TRANSPORT_COMPARISON_ALREADY_ATTEMPTED'):
            comparison.reserve_once(self.state, other)
        self.assertEqual(json.loads(budget_path.read_text())['commandEquivalents'], 9408)

    def test_changed_or_exhausted_baseline_stops_before_any_marker_or_mutation(self):
        for commands, login, reason in ((4896, 1, 'AUTHORIZED_BASELINE_CHANGED'),
                                       (7152, 2, 'AUTHORIZED_BASELINE_CHANGED'),
                                       (7745, 1, 'WORKFLOW_BUDGET_EXHAUSTED'),
                                       (7152, 8, 'WORKFLOW_BUDGET_EXHAUSTED')):
            path = self.save({'loginAttempts': login, 'commandEquivalents': commands})
            before = path.read_bytes()
            with self.assertRaisesRegex(ValueError, reason):
                comparison.reserve_once(self.state, self.ledger)
            self.assertEqual(path.read_bytes(), before)
            self.assertFalse(self.ledger.exists())

    def test_corrupt_budget_is_preserved(self):
        path = self.baseline()
        for raw in ('not-json', '[]', '{}', '{"loginAttempts":true,"commandEquivalents":7152}'):
            path.write_text(raw)
            with self.assertRaisesRegex(ValueError, 'BUDGET_FORMAT'):
                comparison.existing_budget(self.state)
            self.assertEqual(path.read_text(), raw)

    def test_unknown_prior_cleanup_or_malformed_ledger_blocks(self):
        self.baseline()
        for previous in ({'cleanupComplete': False}, {}, [], {'cleanupComplete': 1}):
            self.save(previous, 'ledger-prior.json')
            with self.assertRaisesRegex(ValueError, 'PRIOR_CLEANUP_UNCONFIRMED'):
                comparison.existing_budget(self.state)
        (self.state / 'ledger-prior.json').write_text('not-json')
        with self.assertRaisesRegex(ValueError, 'PRIOR_CLEANUP_UNCONFIRMED'):
            comparison.existing_budget(self.state)

    def test_budget_symlink_and_insecure_mode_are_rejected(self):
        self.save(dict(comparison.BASELINE), 'other.json')
        path = self.state / 'budget.json'
        path.symlink_to(self.state / 'other.json')
        with self.assertRaisesRegex(ValueError, 'PRIVATE_FILE_OWNER_OR_MODE'):
            comparison.existing_budget(self.state)
        path.unlink()
        self.baseline().chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'PRIVATE_FILE_OWNER_OR_MODE'):
            comparison.existing_budget(self.state)

    def test_ledger_path_is_exact_and_private_writer_never_overwrites(self):
        self.baseline()
        for path in (self.root / self.ledger.name, self.state / 'ledger-arbitrary.json'):
            with self.assertRaisesRegex(ValueError, 'LEDGER_PATH_CONTRACT'):
                comparison.reserve_once(self.state, path)
        comparison.private_json(self.ledger, {'synthetic': True})
        with self.assertRaises(FileExistsError):
            comparison.private_json(self.ledger, {'synthetic': False})
        self.assertEqual(json.loads(self.ledger.read_text()), {'synthetic': True})

    def test_existing_lock_excludes_a_second_launcher(self):
        first = comparison.acquire_lock(self.state)
        try:
            with self.assertRaises(BlockingIOError):
                comparison.acquire_lock(self.state)
        finally:
            first.close()

    def test_only_exact_five_redis_values_are_propagated(self):
        values = {key: 'synthetic-only' for key in comparison.REQUIRED}
        selected = comparison.redis_inputs(values)
        self.assertEqual(set(selected), comparison.FIELDS)
        self.assertEqual(len(selected), 5)
        self.assertTrue(all(selected[key] == values[key] for key in selected))
        with self.assertRaisesRegex(ValueError, 'INPUT_ALLOWLIST'):
            comparison.redis_inputs({**values, 'EXTRA': 'synthetic-only'})

    def test_arbitrary_exception_messages_are_never_projected(self):
        canary = 'SYNTHETIC_PRIVATE_CANARY_NEVER_PROJECT'
        for error in (ValueError(canary), OSError(canary), RuntimeError(canary)):
            self.assertNotIn(canary, comparison.safe_reason(error))
        self.assertEqual(comparison.safe_reason(ValueError('AUTHORIZED_BASELINE_CHANGED')), 'AUTHORIZED_BASELINE_CHANGED')

    def test_child_cleanup_is_bounded_and_never_relaunches(self):
        process = mock.Mock(pid=12345)
        process.poll.side_effect = [None, 0]
        process.wait.side_effect = [subprocess.TimeoutExpired('synthetic', 30),
                                    subprocess.TimeoutExpired('synthetic', 10), 0]
        with mock.patch.object(comparison.os, 'killpg') as kill:
            self.assertEqual(comparison.finish_child(process), (True, True))
        self.assertEqual(process.wait.call_args_list, [mock.call(timeout=30), mock.call(timeout=10), mock.call(timeout=10)])
        self.assertEqual(kill.call_args_list, [mock.call(12345, signal.SIGTERM), mock.call(12345, signal.SIGKILL)])

    def test_compile_failure_does_not_reserve_or_launch(self):
        self.baseline()
        result = self.run_main(compile_failure=ValueError('PROBE_COMPILATION_FAILED'))
        self.assertEqual(result['provider_process_launched'], False)
        self.assertEqual(json.loads((self.state / 'budget.json').read_text()), comparison.BASELINE)
        self.assertFalse(list(self.state.glob('ledger-transport-comparison-*.json')))
        self.assertTrue(result['cleanup_complete'])

    def test_launch_failure_keeps_reservation_marker_and_removes_delivery_file(self):
        self.baseline()
        result = self.run_main(launch_failure=OSError('SYNTHETIC_PRIVATE_CANARY_NEVER_PROJECT'))
        self.assertFalse(result['provider_process_launched'])
        self.assertEqual(json.loads((self.state / 'budget.json').read_text())['commandEquivalents'], 9408)
        markers = list(self.state.glob('ledger-transport-comparison-*.json'))
        self.assertEqual(len(markers), 1)
        self.assertTrue(json.loads(markers[0].read_text())['cleanupComplete'])
        self.assertFalse(list(self.state.glob('config-transport-comparison-*.json')))
        self.assertTrue(result['cleanup_complete'])
        with self.assertRaisesRegex(ValueError, 'TRANSPORT_COMPARISON_ALREADY_ATTEMPTED'):
            comparison.existing_budget(self.state)

    @staticmethod
    def projected_failure():
        diagnostic = {'phase': 'NOT_OBSERVED', 'stages': {key: 'NOT_OBSERVED' for key in
            ('DNS', 'TCP', 'TLS', 'HANDSHAKE', 'ACTIVE', 'EXPLICIT_PING')},
            'commands': {key: {state: 0 for state in ('STARTED', 'PASS', 'FAIL', 'UNEXPECTED_STATE')}
                         for key in ('HELLO', 'AUTH', 'CLIENT', 'PING', 'SELECT', 'UNEXPECTED_COMMAND')},
            'events': [], 'events_total': 0, 'events_dropped': 0, 'failure_category': 'NOT_OBSERVED',
            'exception_nodes': [], 'exception_relations': [], 'exceptions_truncated': False}
        return {'status': 'FAIL', 'read_only_operation': comparison.OPERATION,
            'owned_remote_resources_created': False, 'remote_key_writes': 0, 'database_operations': 0,
            'login_attempts': 0, 'automatic_retry': False, 'product_condition_exact': False,
            'resolver': 'SHARED_JDK_HOSTNAME_LOOKUP', 'ssl_provider': 'JDK_DEFAULT_TRUST', 'os_dns_physical_connections': 'NOT_OBSERVED',
            'probe_owned_dns_sockets': 0, 'command_reservation': 256, 'connection_limit': 3,
            'conditional_c_reason': 'NOT_RUN_NO_SUPPORTED_BASIS',
            'controls': {'A': {'status': 'FAIL', 'tcp': 'FAIL', 'tls': 'NOT_RUN', 'cleanup_complete': True,
                'redis_commands_sent': 0, 'elapsed_millis': 4, 'diagnostics': copy.deepcopy(diagnostic)},
                'B': {'status': 'NOT_RUN'}, 'C': {'status': 'NOT_RUN'}},
            'lifecycle': [], 'target_connections_started': 1, 'elapsed_millis': 5, 'cleanup_complete': True,
            'diagnostics': diagnostic}

    def test_failure_summary_and_false_mapping_observations_are_valid_without_claiming_success(self):
        result = self.projected_failure()
        result['versions'] = {'java': '21.0.11+10-LTS', 'lettuce': '6.6.0.RELEASE/643bd47', 'netty': '4.1.124.Final'}
        result['setting_matches'] = {key: False for key in ('host_matches', 'port_matches', 'username_matches',
                                                         'password_matches', 'tls_matches', 'database_matches')}
        self.assertEqual(comparison.project_result(result), result)

    def test_public_projection_rejects_unknown_fields_bad_types_and_overlimits(self):
        changes = ({'extra': 'SYNTHETIC_PRIVATE_CANARY_NEVER_PROJECT'}, {'target_connections_started': 4},
                   {'cleanup_complete': 1}, {'automatic_retry': True}, {'remote_key_writes': 1})
        for change in changes:
            result = {**self.projected_failure(), **change}
            with self.assertRaisesRegex(ValueError, 'RESULT_CONTRACT'):
                comparison.project_result(result)
        result = self.projected_failure()
        result['diagnostics']['exception_nodes'] = [{'position': 0, 'depth': 0,
                                                    'exception_class': 'SYNTHETIC_PRIVATE_CANARY_NEVER_PROJECT'}]
        with self.assertRaisesRegex(ValueError, 'RESULT_CONTRACT'):
            comparison.project_result(result)

    def test_native_failure_summary_keeps_unobserved_dns_tls_and_timeout_classification(self):
        result = self.projected_failure()
        diagnostic = copy.deepcopy(result['diagnostics'])
        diagnostic['failure_category'] = 'TCP_TIMEOUT'
        native = {'status': 'FAIL', 'activation': 'FAIL', 'ping': 'NOT_RUN', 'custom_pipeline_observer': False,
            'command_start_count': 'NOT_OBSERVED', 'automatic_and_explicit_command_upper_bound': 5,
            'conservative_command_allowance': 64, 'read_only_channel_lifetime_observer': True,
            'connect_future_cancelled': False, 'channel_closed': True, 'tls_peer_hostname_matches': False,
            'same_dns_target_as_a': 'NOT_OBSERVED', 'selected_address_family': 'NOT_OBSERVED',
            'completion_counts': {key: 0 for key in diagnostic['commands']}, 'explicit_ping_count': 0,
            'cleanup_complete': True, 'elapsed_millis': 10, 'diagnostics': diagnostic,
            'tls_future_diagnostics': copy.deepcopy(result['diagnostics']),
            'handshake_future_diagnostics': copy.deepcopy(result['diagnostics'])}
        result['controls']['B'] = native
        result['lifecycle'] = [{'control': 'A', 'event': 'TLS_CONTROL_DEADLINE', 'elapsed_millis': 1}]
        self.assertEqual(comparison.project_result(result), result)
        for field in ('tls_peer_hostname_matches', 'channel_closed'):
            invalid = copy.deepcopy(result)
            invalid['controls']['B'][field] = 'true'
            with self.assertRaisesRegex(ValueError, 'RESULT_CONTRACT'):
                comparison.project_result(invalid)
        native['same_dns_target_as_a'] = True
        with self.assertRaisesRegex(ValueError, 'RESULT_CONTRACT'):
            comparison.project_result(result)

    def test_signal_during_launch_is_deferred_until_tracked_then_stops_child(self):
        self.baseline()
        result = self.run_main(launch_interrupt=True)
        self.assertEqual(result['status'], 'INTERRUPTED')
        self.assertTrue(result['provider_process_launched'])
        self.assertTrue(result['child_stopped'])
        self.assertTrue(result['cleanup_complete'])
        self.assertFalse(list(self.state.glob('config-transport-comparison-*.json')))
        self.assertEqual(json.loads((self.state / 'budget.json').read_text())['commandEquivalents'], 9408)

    def run_main(self, compile_failure=None, launch_failure=None, launch_interrupt=False):
        synthetic = {key: 'synthetic-only' for key in comparison.REQUIRED}
        captured = {}
        def fake_compile(work, cache):
            captured['work'] = work
            if compile_failure is not None:
                raise compile_failure
            source = work / 'snapshot'
            (source / 'be').mkdir(parents=True)
            return work / 'jdk', {}, 'synthetic-classpath', source
        def fake_launch(command, **kwargs):
            captured['launch'] = (command, kwargs)
            self.assertEqual(command[-1], comparison.MAIN_CLASS)
            self.assertEqual(set(kwargs['env']), {'MANAGED_REDIS_CONFIG', 'MANAGED_REDIS_RESULT', 'MANAGED_REDIS_LEDGER'})
            self.assertNotIn('synthetic-only', str(command))
            self.assertTrue(kwargs['start_new_session'])
            self.assertEqual(set(json.loads(Path(kwargs['env']['MANAGED_REDIS_CONFIG']).read_text())), comparison.FIELDS)
            self.assertEqual(json.loads((self.state / 'budget.json').read_text())['commandEquivalents'], 9408)
            if launch_failure is not None:
                raise launch_failure
            if launch_interrupt:
                signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None)
                # Simulate only this child's already-sanitized orderly shutdown.
                Path(kwargs['env']['MANAGED_REDIS_RESULT']).write_text(json.dumps(self.projected_failure()))
                Path(kwargs['env']['MANAGED_REDIS_LEDGER']).write_text(json.dumps(
                    {'cleanupComplete': True, 'owned_remote_resources_created': False}))
                process = mock.Mock(pid=12345, returncode=1)
                process.poll.return_value = None
                process.wait.return_value = 1
                return process
            raise AssertionError('SYNTHETIC_LAUNCH_CONFIGURATION_REQUIRED')
        output_root = self.root / 'public-output'
        with contextlib.ExitStack() as patches:
            patches.enter_context(mock.patch.object(comparison, 'ROOT', output_root))
            patches.enter_context(mock.patch.object(comparison.Path, 'home', return_value=self.root / 'synthetic-home'))
            inputs = patches.enter_context(mock.patch.object(comparison, 'read_inputs', return_value=mock.Mock(values=synthetic)))
            patches.enter_context(mock.patch.object(comparison, 'compile_probe', side_effect=fake_compile))
            launch = patches.enter_context(mock.patch.object(comparison.subprocess, 'Popen', side_effect=fake_launch))
            kill = patches.enter_context(mock.patch.object(comparison.os, 'killpg'))
            patches.enter_context(mock.patch('sys.argv', ['redis_transport_comparison.py', '--execute', '--cache-seed', str(self.root / 'cache'), '--state-dir', str(self.state)]))
            stdout = io.StringIO()
            patches.enter_context(contextlib.redirect_stdout(stdout))
            self.assertEqual(comparison.main(), 2)
            inputs.assert_called_once_with(self.root / 'synthetic-home/.config/moneytoad/provider-check.env')
            self.assertEqual(launch.call_count, 0 if compile_failure else 1)
            if launch_interrupt:
                kill.assert_called_once_with(12345, signal.SIGTERM)
            else:
                kill.assert_not_called()
        self.assertNotIn('SYNTHETIC_PRIVATE_CANARY_NEVER_PROJECT', stdout.getvalue())
        self.assertFalse(captured['work'].exists())
        summaries = list(output_root.rglob('summary.json'))
        self.assertEqual(len(summaries), 1)
        text = summaries[0].read_text()
        self.assertNotIn('synthetic-only', text)
        self.assertNotIn('SYNTHETIC_PRIVATE_CANARY_NEVER_PROJECT', text)
        return json.loads(text)


if __name__ == '__main__':
    unittest.main()
