import secrets
"""Synthetic recovery boundaries only; no credentials, providers, or real state."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import signal
import tempfile
import unittest
from unittest import mock

import managed_provider_check as runner
from managed_provider_preflight import REQUIRED


class ProviderRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='moneytoad-recovery-test-')
        self.root = Path(self.temporary.name).resolve()
        self.state = self.root / 'state'; self.state.mkdir(mode=0o700)
        self.input = self.root / '.config/moneytoad/provider-check.env'
        self.input.parent.mkdir(parents=True, mode=0o700); self.input.parent.chmod(0o700)
        values = {key: 'synthetic-only' for key in REQUIRED}
        values.update(TIDB_HOST='db.example.com', REDIS_HOST='cache.example.com',
                      TIDB_PORT='4000', REDIS_PORT='6379', REDIS_SSL_ENABLED='true')
        self.raw = ''.join(key + '=' + values[key] + '\n' for key in sorted(values)).encode()
        self.input.write_bytes(self.raw); self.input.chmod(0o600)
        self.revision = {'sha256Before': hashlib.sha256(self.raw.replace(b'6379', b'6380')).hexdigest(),
                         'sha256After': hashlib.sha256(self.raw).hexdigest(),
                         'changedKeys': ['REDIS_PORT'], 'otherFieldsUnchanged': True}
        self.save(runner.REVISION_FILE, self.revision)
        self.save('budget.json', dict(runner.RESUME_BASELINE))
        self.prior = self.save('ledger-port-correction-prior.json', {'cleanupComplete': True})

    def tearDown(self):
        self.temporary.cleanup()

    def save(self, name, value):
        path = self.state / name
        path.write_text(json.dumps(value)); path.chmod(0o600)
        return path

    @staticmethod
    def observation(status='PASS', clean=True, tidb=True, redis=True):
        return {'status': status, 'cleanup_complete': clean,
                'failover_revocation_guarantee': 'NOT_ESTABLISHED', 'public_deployment_ready': False,
                'tidb_contracts_verified': tidb, 'upstash_functional_contracts_verified': redis,
                'login_recovery_verified': status == 'PASS'}

    def test_resume_requires_existing_exact_budget_without_initializing_or_reserving(self):
        before = (self.state / 'budget.json').read_bytes()
        self.assertEqual(runner.resume_budget(self.state), self.state / 'budget.json')
        self.assertEqual((self.state / 'budget.json').read_bytes(), before)
        self.assertEqual(9472 + runner.RESUME_EXPECTED_COMMANDS, runner.RESUME_BUDGET_LIMIT)
        (self.state / 'budget.json').unlink()
        with self.assertRaisesRegex(ValueError, 'EXISTING_BUDGET_REQUIRED'):
            runner.resume_budget(self.state)
        self.assertFalse((self.state / 'budget.json').exists())
        missing = self.root / 'missing-state'
        with self.assertRaisesRegex(ValueError, 'EXISTING_TASK_STATE_REQUIRED'):
            runner.resume_budget(missing)
        self.assertFalse(missing.exists())

    def test_changed_exhausted_and_unclean_states_are_rejected(self):
        for value, reason in (({'commandEquivalents': 9471, 'loginAttempts': 1}, 'AUTHORIZED_BASELINE_CHANGED'),
                              ({'commandEquivalents': 9473, 'loginAttempts': 1}, 'WORKFLOW_BUDGET_EXHAUSTED'),
                              ({'commandEquivalents': 9472, 'loginAttempts': 5}, 'WORKFLOW_BUDGET_EXHAUSTED'),
                              ({'commandEquivalents': 9472, 'loginAttempts': True}, 'BUDGET_FORMAT')):
            self.save('budget.json', value)
            with self.assertRaisesRegex(ValueError, reason):
                runner.resume_budget(self.state)
        self.save('budget.json', dict(runner.RESUME_BASELINE))
        self.save(self.prior.name, {'cleanupComplete': False})
        with self.assertRaisesRegex(ValueError, 'PRIOR_CLEANUP_UNCONFIRMED'):
            runner.resume_budget(self.state)

    def test_permanent_exclusive_marker_does_not_charge_or_touch_old_ledgers(self):
        prior = self.prior.read_bytes(); budget = (self.state / 'budget.json').read_bytes()
        runner.mark_resume_once(self.state)
        with self.assertRaises(FileExistsError):
            runner.mark_resume_once(self.state)
        with self.assertRaisesRegex(ValueError, 'PROVIDER_RECOVERY_ALREADY_ATTEMPTED'):
            runner.resume_budget(self.state)
        self.assertEqual(self.prior.read_bytes(), prior)
        self.assertEqual((self.state / 'budget.json').read_bytes(), budget)
        self.assertEqual((self.state / runner.RESUME_MARKER).stat().st_mode & 0o777, 0o600)

    def test_revision_keeps_all_nine_fields_and_rejects_input_changes(self):
        revision = runner.load_port_revision(self.state)
        selected = runner.read_revision_inputs(self.input, revision)
        self.assertEqual(set(selected), REQUIRED)
        self.assertIn('TIDB_SETUP_PASSWORD', selected)
        self.input.write_bytes(self.raw.replace(b'6379', b'6380'))
        with self.assertRaisesRegex(ValueError, 'INPUT_REVISION_MISMATCH'):
            runner.read_revision_inputs(self.input, revision)

    def test_compile_time_input_change_blocks_marker_launch_and_budget_mutation(self):
        def changed():
            self.input.write_bytes(self.raw.replace(b'6379', b'6380'))
        result, calls, _ = self.run_main(compile_action=changed)
        self.assertEqual(calls, 0)
        self.assertEqual(result['reason'], 'INPUT_REVISION_MISMATCH')
        self.assertFalse((self.state / runner.RESUME_MARKER).exists())
        self.assertEqual(json.loads((self.state / 'budget.json').read_text()), runner.RESUME_BASELINE)

    def test_launch_failure_keeps_marker_without_charging_and_cleans_delivery(self):
        result, calls, _ = self.run_main(launch_failure=True)
        self.assertEqual(calls, 1)
        self.assertEqual(result['reason'], 'OS_ERROR')
        self.assertFalse(result['provider_process_launched'])
        self.assertTrue((self.state / runner.RESUME_MARKER).exists())
        self.assertEqual(json.loads((self.state / 'budget.json').read_text()), runner.RESUME_BASELINE)
        self.assertFalse(list(self.state.glob('config-*.json')))
        self.assertTrue(result['cleanup_complete'])

    def test_success_passes_exact_limit_and_accounts_only_child_increments(self):
        result, calls, delivered = self.run_main(increment=True)
        self.assertEqual(calls, 1)
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual(result['reserved_command_equivalents'], 15568)
        self.assertEqual(result['reserved_command_equivalents_this_run'], 6096)
        self.assertEqual(result['remaining_command_equivalents'], 0)
        self.assertEqual(result['reserved_login_attempts'], 5)
        self.assertEqual(result['new_login_attempts'], 4)
        self.assertEqual(delivered['verificationBudgetLimit'], 15568)
        self.assertTrue(REQUIRED.issubset(delivered))
        self.assertTrue(result['managed_provider_contracts_verified'])
        self.assertFalse(result['public_deployment_ready'])
        self.assertEqual(result['upstash_failover_revocation_guarantee'], 'NOT_ESTABLISHED')

    def test_partial_failure_keeps_independent_flags_and_requires_ledger_cleanup(self):
        result, _, _ = self.run_main(observed=self.observation('FAIL', tidb=True, redis=False), ledger_clean=False)
        self.assertEqual(result['status'], 'FAIL')
        self.assertTrue(result['tidb_contracts_verified'])
        self.assertFalse(result['upstash_functional_contracts_verified'])
        self.assertFalse(result['managed_provider_contracts_verified'])
        self.assertFalse(result['cleanup_complete'])
        self.assertTrue(list(self.state.glob('config-*.json')))

    def test_interrupt_during_launch_tracks_child_before_cleanup_and_preserves_marker(self):
        result, calls, _ = self.run_main(interrupt=True)
        self.assertEqual(calls, 1)
        self.assertEqual(result['status'], 'INTERRUPTED')
        self.assertTrue(result['provider_process_launched'])
        self.assertTrue(result['cleanup_complete'])
        self.assertTrue(result['child_stopped'])
        self.assertFalse(result['forced_child_termination'])
        self.assertFalse(result['managed_provider_contracts_verified'])
        self.assertTrue((self.state / runner.RESUME_MARKER).exists())

    def test_failover_or_forbidden_result_claim_is_rejected(self):
        for extra in ({'public_deployment_ready': True}, {'failover_revocation_guarantee': 'PASS'},
                      {'password': secrets.token_hex(12)}):
            with self.assertRaisesRegex(ValueError, 'PROVIDER_RESULT_REJECTED'):
                runner.project_observation({**self.observation(), **extra})

    def run_main(self, compile_action=None, launch_failure=False, increment=False,
                 observed=None, ledger_clean=True, interrupt=False):
        work_seen = []; delivered = {}; launched = []
        observed = observed if observed is not None else self.observation()

        def compiler(work, cache):
            work_seen.append(work)
            if compile_action:
                compile_action()
            return work / 'jdk', {}, 'synthetic-classpath', work

        def launch(command, **kwargs):
            launched.append(command)
            self.assertEqual(command[-1], runner.MAIN_CLASS)
            self.assertNotIn('synthetic-only', str(command))
            self.assertEqual(kwargs['stdout'], runner.subprocess.DEVNULL)
            self.assertEqual(kwargs['stderr'], runner.subprocess.DEVNULL)
            env = kwargs['env']
            delivered.update(json.loads(Path(env['MANAGED_CHECK_CONFIG']).read_text()))
            self.assertEqual(json.loads(Path(env['MANAGED_CHECK_BUDGET']).read_text()), runner.RESUME_BASELINE)
            self.assertTrue((self.state / runner.RESUME_MARKER).exists())
            if launch_failure:
                raise OSError('SYNTHETIC_PRIVATE_CANARY')

            def write_result():
                self.save(Path(env['MANAGED_CHECK_RESULT']).name, observed)
                self.save(Path(env['MANAGED_CHECK_LEDGER']).name, {'cleanupComplete': ledger_clean})
                if increment:
                    self.save('budget.json', {'loginAttempts': 5, 'commandEquivalents': 15568})

            child = mock.Mock()
            child.returncode = None if interrupt else (0 if observed['status'] == 'PASS' else 1)
            child.poll.side_effect = lambda: child.returncode
            def wait(timeout):
                if child.returncode is None:
                    child.returncode = -15
                return child.returncode
            child.wait.side_effect = wait
            child.terminate.side_effect = write_result
            if interrupt:
                signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None)
            else:
                write_result()
            return child

        with contextlib.ExitStack() as patches:
            patches.enter_context(mock.patch.object(runner, 'OUTPUT', self.root / 'public-output'))
            patches.enter_context(mock.patch.object(runner.Path, 'home', return_value=self.root))
            patches.enter_context(mock.patch.object(runner, 'compile_probe', side_effect=compiler))
            patches.enter_context(mock.patch.object(runner, 'free_port', return_value=12345))
            child = patches.enter_context(mock.patch.object(runner.subprocess, 'Popen', side_effect=launch))
            patches.enter_context(mock.patch('sys.argv', ['managed_provider_check.py', '--execute',
                '--resume-port-corrected', '--cache-seed', str(self.root / 'cache'), '--state-dir', str(self.state)]))
            output = io.StringIO(); patches.enter_context(contextlib.redirect_stdout(output))
            code = runner.main()
        summaries = list((self.root / 'public-output').rglob('summary.json'))
        self.assertEqual(len(summaries), 1)
        result = json.loads(summaries[0].read_text())
        self.assertEqual(code, 0 if result['status'] == 'PASS' else 2)
        public = summaries[0].read_text() + output.getvalue()
        for hidden in ('SYNTHETIC_PRIVATE_CANARY', 'synthetic-only', self.revision['sha256Before'], self.revision['sha256After']):
            self.assertNotIn(hidden, public)
        if result['cleanup_complete']:
            self.assertFalse(any(path.exists() for path in work_seen))
        else:
            # Only this synthetic test's retained recovery workspace is removed.
            import shutil
            for path in work_seen:
                shutil.rmtree(path)
        return result, child.call_count, delivered


if __name__ == '__main__':
    unittest.main()
