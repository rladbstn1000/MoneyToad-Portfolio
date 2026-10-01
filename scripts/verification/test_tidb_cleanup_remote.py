"""Synthetic launcher safety contracts; no provider connection or product suite."""
import contextlib
import hashlib
import io
import json
import re
import secrets
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import tidb_cleanup_remote as probe
from managed_provider_preflight import REQUIRED


class CleanupRemoteLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='moneytoad-cleanup-launcher-test-')
        self.root = Path(self.temp.name).resolve()
        self.path = self.root / '.config/moneytoad/provider-check.env'
        self.path.parent.mkdir(parents=True, mode=0o700)
        self.path.parent.chmod(0o700)
        values = {key: 'synthetic-private-canary' for key in REQUIRED}
        values.update(TIDB_HOST='db.example.com', TIDB_PORT='4000',
                      TIDB_SETUP_USERNAME='fixture.setup', REDIS_HOST='cache.example.com',
                      REDIS_PORT='6379', REDIS_SSL_ENABLED='true')
        self.raw = ''.join(key + '=' + values[key] + '\n' for key in sorted(values)).encode()
        self.path.write_bytes(self.raw)
        self.path.chmod(0o600)
        self.state = self.root / 'moneytoad-deploy-cleanup-tidb-test-state'

    def tearDown(self):
        self.temp.cleanup()

    @staticmethod
    def mode_fixture(mode):
        return {'stateObserved':True, 'completed':True, 'readOnly':mode!='APPLY', 'autoCommit':False,
                'isolation':'READ_COMMITTED' if mode=='APPLY' else 'REPEATABLE_READ',
                'serverReadOnlyStatements':0, 'delegateDml':5 if mode=='APPLY' else 0, 'delegateDdl':0,
                'driverDml':5 if mode=='APPLY' else 0, 'commits':1 if mode=='APPLY' else 0,
                'rollbacks':0 if mode=='APPLY' else 1, 'unsupported1235':0, 'guardViolations':0}

    @staticmethod
    def proof_fixture():
        values = [{'kind':'NORMAL','work':900,'cleanup':40,'work_positions':600,'cleanup_positions':30,'connections':2,'metadata_calls':40},
                  {'kind':'EARLY_FAILURE','positions':600,'max_work':900,'max_cleanup':45,'max_connections':2},
                  {'kind':'INTERRUPT','positions':600,'max_work':900,'max_cleanup':45,'max_connections':2},
                  {'kind':'FINALLY','positions':30,'max_work':900,'max_cleanup':40,'max_connections':2}]
        return ''.join('CLEANUP_SYNTHETIC_PROOF '+json.dumps(value)+'\n' for value in values)

    @staticmethod
    def result_fixture():
        return {'status': 'PASS', **{key: True for key in probe.RESULT_BOOLEANS},
                'connectionAttempts': 2, 'workCommands': 900, 'cleanupCommands': 40,
                'checks': {key: True for key in probe.CHECK_NAMES},
                'databaseVersion': '8.0.11-TiDB-v8.5.4', 'confirmedReuseResets':2,
                'modeObservations':{mode: CleanupRemoteLauncherTest.mode_fixture(mode) for mode in ('VERIFY','DRY_RUN','APPLY')}}

    def test_input_only_propagates_four_tidb_values(self):
        values, digest = probe.read_tidb_inputs(self.path)
        self.assertEqual(set(values), probe.TIDB_KEYS)
        self.assertFalse(any(key.startswith('REDIS') for key in values))
        self.assertEqual(digest, hashlib.sha256(self.raw).hexdigest())
        self.assertEqual(self.path.read_bytes(), self.raw)

    def test_input_rejects_symlink_hardlink_and_permissions(self):
        link = self.path.parent / 'symlink'; link.symlink_to(self.path)
        with self.assertRaisesRegex(ValueError, 'INPUT_UNSAFE_OR_UNREADABLE'):
            probe.read_tidb_inputs(link)
        hard = self.path.parent / 'hardlink'
        import os
        os.link(self.path, hard)
        with self.assertRaisesRegex(ValueError, 'INPUT_UNSAFE_OR_UNREADABLE'):
            probe.read_tidb_inputs(self.path)
        hard.unlink()
        self.path.chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'INPUT_UNSAFE_OR_UNREADABLE'):
            probe.read_tidb_inputs(self.path)

    def test_input_rejects_parent_symlink_and_nonprivate_directory(self):
        link = self.root / 'linked'; link.symlink_to(self.path.parent, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'INPUT_UNSAFE_OR_UNREADABLE'):
            probe.read_tidb_inputs(link / self.path.name)
        self.path.parent.chmod(0o755)
        with self.assertRaisesRegex(ValueError, 'INPUT_UNSAFE_OR_UNREADABLE'):
            probe.read_tidb_inputs(self.path)

    def test_new_state_never_reuses_existing_or_old_state(self):
        created = probe.new_state(self.state)
        self.assertEqual(created.stat().st_mode & 0o777, 0o700)
        with self.assertRaisesRegex(ValueError, 'NEW_PRIVATE_STATE_REQUIRED'):
            probe.new_state(self.state)
        old = self.root / 'moneytoad-deploy03-provider-state'; old.mkdir(mode=0o700)
        prior = old / 'budget.json'; prior.write_text('DO_NOT_READ_OR_CHANGE')
        with self.assertRaisesRegex(ValueError, 'PRIVATE_STATE_PATH'):
            probe.new_state(old)
        self.assertEqual(prior.read_text(), 'DO_NOT_READ_OR_CHANGE')
        self.assertEqual(list(old.iterdir()), [prior])

    def test_reservation_is_permanent_and_cleanup_reserved_before_launch(self):
        state = probe.new_state(self.state)
        probe.reserve_once(state, 'private-digest', {'schema': 'owned-only'}, {}, 'private-package-checksum')
        budget = probe.private_document(state / 'budget.json')
        self.assertEqual(budget['reservedCommands'], 1500)
        self.assertEqual(budget['cleanupReserved'], 300)
        self.assertEqual(budget['workReserved'], 1200)
        before = (state / 'budget.json').read_bytes()
        with self.assertRaises(FileExistsError):
            probe.reserve_once(state, 'private-digest', {'schema': 'owned-only'}, {}, 'private-package-checksum')
        self.assertEqual((state / 'budget.json').read_bytes(), before)

    def test_budget_limit_cannot_be_silently_increased(self):
        state = probe.new_state(self.state)
        with mock.patch.object(probe, 'WORK_LIMIT', 1201):
            with self.assertRaisesRegex(ValueError, 'STATIC_BUDGET_REJECTED'):
                probe.reserve_once(state, 'private-digest', {}, {}, 'private-package-checksum')
        self.assertEqual(list(state.iterdir()), [])

    def test_static_components_equal_full_reserved_upper_bound(self):
        plan = probe.static_plan()
        self.assertEqual(sum(plan['work_components'].values()), 1200)
        self.assertEqual(plan['total_reserved_upper_bound'], 1500)
        with mock.patch.dict(probe.STATIC_WORK_BOUNDS, {'single_v1_fixture_inserts_and_assertions': 341}):
            with self.assertRaisesRegex(ValueError, 'STATIC_BUDGET_REJECTED'):
                probe.static_plan()

    def test_account_prefix_matches_tidb_and_has_safe_fixed_entropy(self):
        self.assertEqual(probe.account_name('fixture.setup', 'a' * 14), 'fixture.m' + 'a' * 14)
        for name in ('setup', "fixture.bad'", 'x' * 17 + '.setup'):
            with self.assertRaisesRegex(ValueError, 'ACCOUNT_PREFIX_REJECTED'):
                probe.account_name(name, 'a' * 14)

    def test_projection_rejects_unknown_fields_bad_types_and_limit_overruns(self):
        accepted = self.result_fixture()
        self.assertEqual(probe.project_result(accepted), accepted)
        for update in ({'password': secrets.token_hex(12)}, {'connectionAttempts': 3},
                       {'workCommands': 1201}, {'cleanupCommands': 301},
                       {'connectionAttempts': True}, {'clientsClosed': False},
                       {'cleanupComplete': 'true'}, {'checks': {'SCHEMA_METADATA': 'arbitrary-value'}},
                       {'failureCode': 'SYNTHETIC_PRIVATE_CANARY'},
                       {'failureSqlState': 'SYNTHETIC_PRIVATE_CANARY'},
                       {'failureVendor': True},
                       {'failedPhase': 'SYNTHETIC_PRIVATE_CANARY'},
                       {'checks': {'SYNTHETIC_PRIVATE_CANARY': True}},
                       {'redisCommands': 1}, {'upstashConnections': 1}, {'httpLogins': 1}):
            with self.assertRaisesRegex(ValueError, 'RESULT_CONTRACT'):
                probe.project_result({**accepted, **update})

    def test_owned_process_group_is_terminated_without_other_process_targets(self):
        process = mock.Mock()
        process.pid = 321
        process.poll.return_value = None
        process.returncode = 0
        with mock.patch('demo_capacity_tidb.group_alive', return_value=False), \
                mock.patch.object(probe.os, 'killpg') as kill:
            self.assertTrue(probe.stop(process))
        kill.assert_called_once_with(321, probe.signal.SIGTERM)

    def test_forced_termination_is_never_reported_as_graceful_cleanup(self):
        process = mock.Mock()
        process.pid = 321
        process.poll.return_value = None
        with mock.patch('demo_capacity_tidb.group_alive', return_value=True), \
                mock.patch.object(probe.os, 'killpg') as kill:
            self.assertFalse(probe.stop(process, timeout=0))
        self.assertEqual(kill.call_args_list, [mock.call(321, probe.signal.SIGTERM),
                                             mock.call(321, probe.signal.SIGKILL)])
        process.wait.assert_called_once_with(timeout=15)

    def test_java_public_projection_contract_matches_fixed_python_allowlist(self):
        source_root = Path(probe.__file__).resolve().parents[2] / 'be/src/test/java/com/potg/verification/cleanupremote'
        main = (source_root / 'CleanupTiDbProbe.java').read_text()
        budget = (source_root / 'CleanupSqlBudget.java').read_text()
        self.assertEqual(set(re.findall(r'pass\("([A-Z][A-Z0-9_]+)"\)', main)), probe.CHECK_NAMES)
        self.assertEqual(set(re.findall(r'phase\s*=\s*"([A-Z][A-Z0-9_]+)"', main)), probe.PHASES)
        codes = set(re.findall(r'require\([^;]*?,\s*"([A-Z][A-Z0-9_]+)"\)', main, re.DOTALL))
        codes.update(re.findall(r'(?:rejected|Rejected)\("([A-Z][A-Z0-9_]+)"\)', main + budget))
        self.assertFalse(codes - probe.FAILURE_CODES, 'Java fixed failure code missing from projection')

    def test_budget_proof_xml_requires_all_four_actual_paths_without_skips(self):
        import xml.etree.ElementTree as ET
        directory = self.root / 'reports'; directory.mkdir()
        report = directory / 'TEST-proof.xml'
        def write(names, skipped=None):
            suite = ET.Element('testsuite', name='com.potg.verification.cleanupremote.CleanupTiDbProbeSafetyTest',
                               tests=str(len(names)), failures='0', errors='0', skipped='0')
            for name in names:
                case = ET.SubElement(suite, 'testcase', name=name + '()')
                if name == skipped:
                    ET.SubElement(case, 'skipped')
            ET.SubElement(suite, 'system-out').text = self.proof_fixture()
            ET.ElementTree(suite).write(report)
        names = sorted(probe.REQUIRED_BUDGET_PROOFS)
        write(names)
        self.assertTrue(probe.local_test_results(directory)['budget_proofs_passed'])
        write(names[:-1])
        with self.assertRaisesRegex(ValueError, 'COMPILE_FAILED'):
            probe.local_test_results(directory)
        write(names, skipped=names[0])
        with self.assertRaisesRegex(ValueError, 'COMPILE_FAILED'):
            probe.local_test_results(directory)
        report.write_text('<malformed')
        with self.assertRaisesRegex(ValueError, 'COMPILE_FAILED'):
            probe.local_test_results(directory)

    def test_version_projection_accepts_only_sanitized_numeric_tidb_version(self):
        result = self.result_fixture()
        missing = dict(result); missing.pop('databaseVersion')
        with self.assertRaisesRegex(ValueError, 'RESULT_CONTRACT'):
            probe.project_result(missing)
        self.assertEqual(probe.project_result({**result, 'databaseVersion': '8.0.11-TiDB-v8.5.4'})['databaseVersion'],
                         '8.0.11-TiDB-v8.5.4')
        for unsafe in ('8.0.11-TiDB-v8.5.4-private-identity', 'synthetic-private-canary', 123):
            with self.assertRaisesRegex(ValueError, 'RESULT_CONTRACT'):
                probe.project_result({**result, 'databaseVersion': unsafe})

    def test_both_historical_states_and_nested_paths_are_rejected_without_opening(self):
        for name in probe.OLD_STATE_NAMES:
            protected = self.root / name
            with self.assertRaisesRegex(ValueError, 'PRIVATE_STATE_PATH'):
                probe.new_state(protected)
            with self.assertRaisesRegex(ValueError, 'PRIVATE_STATE_PATH'):
                probe.new_state(protected / 'moneytoad-deploy-cleanup-tidb-nested')
            self.assertFalse(protected.exists())

    def test_compiler_cleanup_masks_repeated_interrupt_and_restores_handlers(self):
        work = self.root / 'compile'; work.mkdir()
        cache = self.root / 'cache'; (cache / 'wrapper').mkdir(parents=True); (cache / 'caches').mkdir()
        (work / 'classpath').write_text('synthetic-classpath')
        child = mock.Mock(); child.wait.return_value = 0
        old_handler = mock.Mock()
        with mock.patch.object(probe, 'inputs', return_value=[]), \
                mock.patch.object(probe, 'source_manifest', return_value={}), \
                mock.patch.object(probe, 'package_classpath', return_value='synthetic-classpath'), \
                mock.patch.object(probe.subprocess, 'run', return_value=mock.Mock(stdout='/synthetic/jdk')), \
                mock.patch.object(probe.subprocess, 'Popen', return_value=child), \
                mock.patch.object(probe, 'stop', return_value=True) as stop, \
                mock.patch.object(probe.signal, 'signal', return_value=old_handler) as handlers, \
                mock.patch.object(probe, 'local_test_results', return_value={'budget_proofs_passed': True}):
            probe.compile_probe(work, cache)
        stop.assert_called_once_with(child)
        self.assertEqual(handlers.call_args_list, [
            mock.call(probe.signal.SIGINT, probe.signal.SIG_IGN),
            mock.call(probe.signal.SIGTERM, probe.signal.SIG_IGN),
            mock.call(probe.signal.SIGINT, old_handler),
            mock.call(probe.signal.SIGTERM, old_handler)])

    def test_other_provider_lines_are_not_interpreted_or_required(self):
        only = ''.join(line + '\n' for line in self.raw.decode().splitlines() if line.startswith('TIDB_'))
        values = probe.parse_tidb_only(only + 'REDIS_PASSWORD=' + secrets.token_hex(16) + '\nREDIS_HOST=\nREDIS_SSL_ENABLED=false\n')
        self.assertEqual(set(values), probe.TIDB_KEYS)
        for invalid in (only + 'TIDB_PORT=4000\n', only.replace('4000','0'),
                        only.replace('db.example.com','127.0.0.1'), only.replace('db.example.com','db.invalid')):
            with self.assertRaisesRegex(ValueError, 'INPUT_UNSAFE_OR_UNREADABLE'):
                probe.parse_tidb_only(invalid)

    def test_source_manifest_pins_content_paths_and_modes_and_rejects_symlinks(self):
        root = self.root / 'source'; (root / 'be').mkdir(parents=True)
        file = root / 'be/task.java'; file.write_text('original')
        with mock.patch.object(probe, 'inputs', return_value=[Path('be/task.java')]):
            baseline = probe.source_manifest(root)
            file.write_text('changed')
            self.assertNotEqual(probe.source_manifest(root), baseline)
            file.write_text('original'); file.chmod(0o700)
            self.assertNotEqual(probe.source_manifest(root), baseline)
            file.unlink(); file.symlink_to(self.path)
            with self.assertRaisesRegex(ValueError, 'SOURCE_CHANGED'):
                probe.source_manifest(root)

    def test_packaged_product_classes_exactly_match_compiled_bytes_and_lead_classpath(self):
        import zipfile
        work = self.root / 'packaging'; libs = work / 'build/libs'; libs.mkdir(parents=True)
        compiled = work / 'build/classes/java/main/com/example/Product.class'
        compiled.parent.mkdir(parents=True); compiled.write_bytes(b'synthetic-bytecode')
        test = work / 'build/classes/java/test'; test.mkdir(parents=True)
        with zipfile.ZipFile(libs / 'owned-demo-cleanup.jar','w') as jar:
            jar.writestr('BOOT-INF/classes/com/example/Product.class',b'synthetic-bytecode')
        classpath = probe.package_classpath(work, str(test) + probe.os.pathsep + str(compiled.parents[2]))
        self.assertEqual(classpath.split(probe.os.pathsep)[0], str(work / 'packaged/classes'))
        self.assertNotIn(str(compiled.parents[2]), classpath.split(probe.os.pathsep))
        self.assertEqual(probe.private_document(work / 'packaging-proof.json')['classCount'],1)

    def test_packaged_product_mismatch_or_traversal_is_rejected(self):
        import zipfile
        for suffix, entry, content in (('mismatch','com/example/Product.class',b'changed'),
                                       ('traversal','../../escaped.class',b'synthetic-bytecode')):
            work = self.root / suffix; libs = work / 'build/libs'; libs.mkdir(parents=True)
            compiled = work / 'build/classes/java/main/com/example/Product.class'
            compiled.parent.mkdir(parents=True); compiled.write_bytes(b'synthetic-bytecode')
            with zipfile.ZipFile(libs / 'owned-demo-cleanup.jar','w') as jar:
                jar.writestr('BOOT-INF/classes/'+entry,content)
            with self.assertRaisesRegex(ValueError, 'PACKAGE_CONTRACT'):
                probe.package_classpath(work,'unused')
            self.assertFalse((work / 'escaped.class').exists())

    def test_cleanup_delivery_is_private_false_tls_config_without_admin_or_redis_values(self):
        settings = {'TIDB_HOST':'db.example.com','TIDB_PORT':'4000','schema':'mtcleanup'+'a'*16,
                    'cleanupAccount':'fixture.m'+'b'*14,'cleanupPassword':secrets.token_hex(32)}
        target = self.root / 'cleanup.properties'
        probe.write_cleanup_config(target,settings)
        self.assertEqual(target.stat().st_mode & 0o777,0o600)
        text = target.read_text()
        self.assertIn('readOnlyPropagatesToServer=false',text)
        self.assertIn('sslMode=VERIFY_IDENTITY',text)
        self.assertIn('connectTimeout=5000&socketTimeout=10000',text)
        self.assertNotIn('TIDB_SETUP_',text); self.assertNotIn('REDIS_',text)
        with self.assertRaises(FileExistsError):
            probe.write_cleanup_config(target,settings)

    def test_proof_counts_must_cover_actual_positions_and_cannot_exceed_limits(self):
        accepted = self.proof_fixture()
        self.assertEqual(probe.synthetic_budget_proof(accepted)['normal']['connections'],2)
        for invalid in (accepted.replace('"work": 900','"work": 1201'), accepted.replace('"cleanup": 40','"cleanup": 301'),
                        accepted.replace('"connections": 2','"connections": 3'), accepted.replace('"positions": 600','"positions": 599'),
                        accepted + accepted.splitlines()[0] + '\n', accepted.replace('"kind": "NORMAL"','"kind": "UNOBSERVED"')):
            with self.assertRaisesRegex(ValueError,'COMPILE_FAILED'):
                probe.synthetic_budget_proof(invalid)

    def test_mode_observations_reject_leaks_fake_completion_and_wrong_transaction_semantics(self):
        for mode in ('VERIFY','DRY_RUN','APPLY'):
            accepted = self.mode_fixture(mode)
            self.assertEqual(probe.project_mode(mode,accepted),accepted)
            for update in ({'sql':'synthetic-private-canary'}, {'serverReadOnlyStatements':1},
                           {'guardViolations':1}, {'unsupported1235':1}, {'delegateDdl':1},
                           {'stateObserved':False}, {'autoCommit':True}, {'delegateDml':True},
                           {'isolation':'SERIALIZABLE'}):
                with self.assertRaisesRegex(ValueError,'RESULT_CONTRACT'):
                    probe.project_mode(mode,{**accepted,**update})
        failed = {'stateObserved':False,'completed':False,'serverReadOnlyStatements':1,
                  'delegateDml':0,'delegateDdl':0,'unsupported1235':1,'guardViolations':0}
        self.assertEqual(probe.project_mode('VERIFY',failed),failed)
        complete = self.result_fixture()
        for update in ({'confirmedReuseResets':1}, {'confirmedReuseResets':True}, {'modeObservations':{}},
                       {'modeObservations':{'UNREVIEWED':self.mode_fixture('VERIFY')}}):
            with self.assertRaisesRegex(ValueError,'RESULT_CONTRACT'):
                probe.project_result({**complete,**update})

    def test_early_failure_without_mode_or_version_does_not_fabricate_observations(self):
        early = {'status':'FAIL', **{key:False for key in probe.RESULT_BOOLEANS},
                 'connectionAttempts':1,'workCommands':32,'cleanupCommands':1,'checks':{}}
        self.assertEqual(probe.project_result(early),early)
        self.assertNotIn('modeObservations',early)
        self.assertNotIn('databaseVersion',early)

    def compiler(self, work, cache):
        source = work / 'snapshot'; (source / 'be').mkdir(parents=True)
        (work / 'local-tests.json').write_text(json.dumps({'tests': 4, 'failures': 0, 'errors': 0, 'skipped': 0}))
        probe.private_json(work / 'source-manifest.json', {})
        probe.private_json(work / 'packaging-proof.json', {'jarChecksum': 'synthetic-package-checksum'})
        return work / 'java', {}, 'synthetic-classpath', source

    def run_main(self, compile_only=False, compiler=None):
        work_seen = []
        def compile_record(work, cache):
            work_seen.append(work)
            return (compiler or self.compiler)(work, cache)
        args = ['tidb_cleanup_remote.py', '--compile-only' if compile_only else '--execute',
                '--cache-seed', str(self.root / 'cache'), '--state-dir', str(self.state)]
        output = io.StringIO()
        with contextlib.ExitStack() as patches:
            patches.enter_context(mock.patch.object(probe, 'OUTPUT', self.root / 'evidence'))
            patches.enter_context(mock.patch.object(probe, 'source_manifest', return_value={}))
            patches.enter_context(mock.patch.object(probe.Path, 'home', return_value=self.root))
            patches.enter_context(mock.patch.object(probe, 'compile_probe', side_effect=compile_record))
            child = patches.enter_context(mock.patch.object(probe.subprocess, 'Popen',
                side_effect=OSError('SYNTHETIC_PRIVATE_CANARY')))
            patches.enter_context(mock.patch('sys.argv', args))
            patches.enter_context(contextlib.redirect_stdout(output))
            code = probe.main()
        report = next((self.root / 'evidence').rglob('summary.json'))
        public = report.read_text() + output.getvalue()
        self.assertNotIn('synthetic-private-canary', public)
        self.assertNotIn('SYNTHETIC_PRIVATE_CANARY', public)
        self.assertNotIn(hashlib.sha256(self.raw).hexdigest(), public)
        self.assertNotIn(str(self.root), public)
        self.assertFalse(any(work.exists() for work in work_seen))
        self.assertEqual(self.path.read_bytes(), self.raw)
        return code, json.loads(report.read_text()), child

    def test_compile_only_reads_no_credentials_and_reserves_nothing(self):
        with mock.patch.object(probe, 'read_tidb_inputs', side_effect=AssertionError('No input read permitted')):
            code, report, child = self.run_main(compile_only=True)
        self.assertEqual(code, 0)
        self.assertEqual(report['reserved_command_equivalents'], 0)
        self.assertFalse(self.state.exists())
        child.assert_not_called()

    def test_failed_launch_does_not_refund_reservation_or_keep_delivery_secret(self):
        code, report, child = self.run_main()
        self.assertEqual(code, 2)
        self.assertEqual(child.call_count, 1)
        self.assertEqual(report['reserved_command_equivalents'], 1500)
        self.assertEqual(report['remaining_command_equivalents'], 0)
        self.assertFalse(report['provider_process_launched'])
        self.assertTrue(report['cleanup_complete'])
        self.assertFalse((self.state / 'config.json').exists())
        self.assertTrue((self.state / 'execution-marker.json').exists())
        args, kwargs = child.call_args
        self.assertEqual(args[0][-1], probe.MAIN_CLASS)
        self.assertNotIn('synthetic-private-canary', str(args) + str(kwargs))
        self.assertEqual(set(kwargs['env']), {'CLEANUP_CHECK_CONFIG', 'CLEANUP_CHECK_RESULT', 'CLEANUP_CHECK_LEDGER'})

    def test_input_changed_during_compile_never_reserves_or_launches(self):
        def change(work, cache):
            self.path.write_bytes(self.raw.replace(b'4000', b'4001'))
            return self.compiler(work, cache)
        # The test's final preservation assertion refers to the deliberately changed fixture.
        old = self.raw
        def changed(work, cache):
            result = change(work, cache)
            self.raw = self.path.read_bytes()
            return result
        code, report, child = self.run_main(compiler=changed)
        self.assertEqual(code, 2)
        self.assertEqual(report['reason'], 'INPUT_CHANGED')
        self.assertFalse(self.state.exists())
        child.assert_not_called()
        self.assertNotEqual(self.raw, old)


if __name__ == '__main__':
    unittest.main()
