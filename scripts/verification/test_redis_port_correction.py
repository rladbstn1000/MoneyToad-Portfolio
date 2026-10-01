"""Minimal synthetic revision, reservation, projection and launch boundaries."""
import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import redis_port_correction as correction
from managed_provider_preflight import REQUIRED
import test_redis_transport_comparison as previous_tests


class PortCorrectionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='moneytoad-port-test-')
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
        self.save(self.revision, correction.REVISION_FILE)
        self.save(dict(correction.BASELINE), 'budget.json')
        self.ledger = self.state / ('ledger-port-correction-' + 'a' * 16 + '.json')

    def tearDown(self):
        self.temporary.cleanup()

    def save(self, value, name):
        path = self.state / name; path.write_text(json.dumps(value)); path.chmod(0o600)
        return path

    def test_exact_reservation_preserves_old_markers_and_cannot_repeat(self):
        prior = self.save({'cleanupComplete': True}, 'ledger-transport-comparison-prior.json')
        before = prior.read_bytes()
        self.assertEqual(correction.reserve_once(self.state, self.ledger),
                         {'loginAttempts': 1, 'commandEquivalents': 9472})
        self.assertEqual(prior.read_bytes(), before)
        self.assertEqual(correction.CLEANUP_RESERVATION, 0)
        self.assertEqual(10000 - 9472, 528)
        with self.assertRaisesRegex(ValueError, 'PORT_CORRECTION_ALREADY_ATTEMPTED'):
            correction.reserve_once(self.state, self.ledger)

    def test_missing_changed_or_unclean_budget_is_never_initialized_or_refunded(self):
        budget = self.state / 'budget.json'; budget.unlink()
        with self.assertRaisesRegex(ValueError, 'EXISTING_BUDGET_REQUIRED'):
            correction.existing_budget(self.state)
        self.assertFalse(budget.exists())
        self.save({'loginAttempts': 1, 'commandEquivalents': 9472}, 'budget.json')
        with self.assertRaisesRegex(ValueError, 'AUTHORIZED_BASELINE_CHANGED'):
            correction.existing_budget(self.state)
        self.save(dict(correction.BASELINE), 'budget.json')
        self.save({'cleanupComplete': False}, 'ledger-prior.json')
        with self.assertRaisesRegex(ValueError, 'PRIOR_CLEANUP_UNCONFIRMED'):
            correction.existing_budget(self.state)
        self.assertFalse(self.ledger.exists())

    def test_revision_requires_only_port_change_and_true_boolean(self):
        for change in ({'changedKeys': ['REDIS_HOST']}, {'otherFieldsUnchanged': 1},
                       {'sha256Before': self.revision['sha256After']}, {'extra': 'synthetic-canary'}):
            self.save({**self.revision, **change}, correction.REVISION_FILE)
            with self.assertRaisesRegex(ValueError, 'INPUT_REVISION_CONTRACT'):
                correction.load_revision(self.state)

    def test_exact_bytes_are_bound_to_revision_and_changed_input_is_rejected(self):
        revision = correction.load_revision(self.state)
        selected = correction.read_bound_inputs(self.input, revision)
        self.assertEqual(len(selected), 5)
        self.assertEqual(selected['REDIS_PORT'], '6379')
        self.input.write_bytes(self.raw.replace(b'6379', b'6380'))
        with self.assertRaisesRegex(ValueError, 'INPUT_REVISION_MISMATCH'):
            correction.read_bound_inputs(self.input, revision)

    def test_input_symlink_and_insecure_mode_are_rejected(self):
        self.input.chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'INPUT_UNSAFE_OR_UNREADABLE'):
            correction.read_bound_inputs(self.input, self.revision)
        self.input.chmod(0o600)
        link = self.input.parent / 'link.env'; link.symlink_to(self.input)
        with self.assertRaisesRegex(ValueError, 'INPUT_UNSAFE_OR_UNREADABLE'):
            correction.read_bound_inputs(link, self.revision)

    @staticmethod
    def result_fixture():
        result = previous_tests.TransportComparisonTest.projected_failure()
        result.update(read_only_operation=correction.OPERATION, command_reservation=64, connection_limit=1,
                      address_selection='FIRST_IPV4', jdk_tcp_control_executed=False, target_connections_started=0)
        result.pop('conditional_c_reason')
        result['controls'] = {key: {'status': 'NOT_RUN'} for key in ('A', 'B', 'C')}
        return result

    def test_projection_preserves_real_single_run_values_and_hides_unknown_fields(self):
        result = self.result_fixture(); before = copy.deepcopy(result)
        self.assertEqual(correction.project_result(result), before)
        self.assertEqual(result, before)
        for change in ({'command_reservation': 256}, {'target_connections_started': 2},
                       {'extra': 'SYNTHETIC_PRIVATE_CANARY'}, {'jdk_tcp_control_executed': True}):
            with self.assertRaisesRegex(ValueError, 'RESULT_CONTRACT'):
                correction.project_result({**result, **change})

    def test_changed_input_during_compile_blocks_launch_and_reservation(self):
        def compile_change(work, cache):
            self.input.write_bytes(self.raw.replace(b'6379', b'6380'))
            return work / 'jdk', {}, 'synthetic-classpath', work
        result = self.run_main(compile_change)
        self.assertFalse(result['provider_process_launched'])
        self.assertEqual(result['reason'], 'INPUT_REVISION_MISMATCH')
        self.assertEqual(json.loads((self.state / 'budget.json').read_text()), correction.BASELINE)
        self.assertFalse(list(self.state.glob('ledger-port-correction-*.json')))

    def test_launch_failure_keeps64_reservation_and_removes_delivery_file(self):
        result = self.run_main(lambda work, cache: (work / 'jdk', {}, 'synthetic-classpath', work), launch=True)
        self.assertFalse(result['provider_process_launched'])
        self.assertEqual(json.loads((self.state / 'budget.json').read_text())['commandEquivalents'], 9472)
        markers = list(self.state.glob('ledger-port-correction-*.json')); self.assertEqual(len(markers), 1)
        self.assertTrue(json.loads(markers[0].read_text())['cleanupComplete'])
        self.assertFalse(list(self.state.glob('config-port-correction-*.json')))
        self.assertEqual(self.input.read_bytes(), self.raw)

    def run_main(self, compiler, launch=False):
        work_seen = []
        def compile_record(work, cache):
            work_seen.append(work)
            return compiler(work, cache)
        with contextlib.ExitStack() as patches:
            patches.enter_context(mock.patch.object(correction, 'ROOT', self.root / 'public-output'))
            patches.enter_context(mock.patch.object(correction.Path, 'home', return_value=self.root))
            patches.enter_context(mock.patch.object(correction, 'compile_probe', side_effect=compile_record))
            child = patches.enter_context(mock.patch.object(correction.subprocess, 'Popen', side_effect=OSError('SYNTHETIC_PRIVATE_CANARY')))
            patches.enter_context(mock.patch('sys.argv', ['redis_port_correction.py', '--execute', '--cache-seed', str(self.root / 'cache'), '--state-dir', str(self.state)]))
            output = io.StringIO(); patches.enter_context(contextlib.redirect_stdout(output))
            self.assertEqual(correction.main(), 2)
            self.assertEqual(child.call_count, int(launch))
            if launch:
                args, kwargs = child.call_args
                self.assertEqual(args[0][-1], correction.MAIN_CLASS)
                self.assertNotIn('synthetic-only', str(args))
                self.assertEqual(set(kwargs['env']), {'MANAGED_REDIS_CONFIG', 'MANAGED_REDIS_RESULT', 'MANAGED_REDIS_LEDGER'})
        self.assertFalse(any(path.exists() for path in work_seen))
        summaries = list((self.root / 'public-output').rglob('summary.json')); self.assertEqual(len(summaries), 1)
        public = summaries[0].read_text() + output.getvalue()
        for hidden in ('SYNTHETIC_PRIVATE_CANARY', 'synthetic-only', self.revision['sha256Before'], self.revision['sha256After']):
            self.assertNotIn(hidden, public)
        return json.loads(summaries[0].read_text())


if __name__ == '__main__':
    unittest.main()
