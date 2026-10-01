#!/usr/bin/env python3
"""One separately authorized, counterless admission-lock TiDB probe.

No existing provider state is opened. A new private task reserves its complete
finite work/cleanup allowance before launch and can never be replayed. The child
uses the current product store and maintenance service, not the full test suite.
"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import stat
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

from demo_capacity_tidb import (private_json, private_document, read_tidb_inputs, account_name, stop)
from public_evidence import forbidden
from render_regression import ROOT, inputs

MAIN_CLASS = 'com.potg.verification.admissionlock.AdmissionLockTiDbProbe'
REQUIRED_BUDGET_PROOFS = frozenset((
    'actualNormalWorkflowFitsFiniteBudgetAndUsesFourClients',
    'everyWorkCommandEarlyFailureStillRunsBoundedOwnedCleanup',
    'cleanupFailureCannotBorrowWorkOrOpenFifthClient',
    'cancellationFromEveryWorkBoundaryStillClosesOwnedResources',
))
OUTPUT = ROOT / 'docs/deployment/evidence/DEMO_ADMISSION_LOCK_TIDB'
TOTAL_LIMIT = 2500
WORK_LIMIT = 2000
CLEANUP_LIMIT = 500
CONNECTION_LIMIT = 4
TIDB_KEYS = frozenset(('TIDB_HOST', 'TIDB_PORT', 'TIDB_SETUP_USERNAME', 'TIDB_SETUP_PASSWORD'))
OLD_STATE_NAMES = frozenset(('moneytoad-deploy03-provider-state',
    'moneytoad-deploy-capacity-tidb-20261001-state'))
# Conservative finite call envelope, cross-checked by focused Java safety tests.
# Unused margin is reserved permanently; it is not permission for retries.
STATIC_WORK_BOUNDS = {
    'driver_connection_initialization': 128,
    'schema_and_grants': 96,
    'reviewed_metadata_calls': 400,
    'admission_locks_and_transaction_state': 160,
    'rolled_back_fixture': 64,
    'single_v1_fixture_inserts_and_assertions': 400,
    'privilege_denials_and_observations': 96,
    'cleanup_sql_contract_calls': 128,
    'scope_and_identity_observations': 128,
    'reserved_unused_margin': 400,
}
SAFE_REASONS = frozenset((
    'NEW_PRIVATE_STATE_REQUIRED', 'PRIVATE_STATE_PATH', 'PRIVATE_STATE_MODE',
    'PRIVATE_FILE_CONTRACT', 'INPUT_UNSAFE_OR_UNREADABLE', 'INPUT_CHANGED',
    'EXPLICIT_CACHE_REQUIRED', 'COMPILE_FAILED', 'STATIC_BUDGET_REJECTED',
    'ACCOUNT_PREFIX_REJECTED', 'RESULT_CONTRACT', 'RESOURCE_CLEANUP_UNCONFIRMED',
    'CHILD_DEADLINE', 'INTERRUPTED', 'OS_ERROR', 'EXECUTION_REJECTED',
))
RESULT_BOOLEANS = frozenset(('cleanupComplete', 'schemaVerified', 'admissionVerified',
    'cleanupVerified', 'privilegesVerified', 'clientsClosed', 'ownedDataRemoved',
    'ownedAccountsAbsent', 'ownedSchemaAbsent'))
RESULT_INTEGERS = frozenset(('connectionAttempts', 'workCommands', 'cleanupCommands', 'nowaitVendor'))
RESULT_OPTIONAL = frozenset(('failedPhase', 'failureCode', 'ledgerFailure', 'cleanupFailure',
    'failureVendor', 'failureSqlState', 'databaseVersion', 'nowaitElapsedMillis',
    'failedNegativeRole', 'failedNegativeOperation'))
# Fixed diagnostic tags only; no SQL, account, parameter or exception message.
NEGATIVE_COMMON = frozenset(('DEMO_CAPACITY_UPDATE', 'DEMO_ADMISSION_LOCK_INSERT',
    'DEMO_ADMISSION_LOCK_DELETE', 'USERS_ALTER')) | frozenset(
    table + '_' + verb for table in ('ANALYSIS_JOB', 'PEER_TRANSACTION_STATS', 'DUMMY')
    for verb in ('INSERT', 'UPDATE', 'DELETE'))
NEGATIVE_OPERATIONS = {
    'RUNTIME': NEGATIVE_COMMON | frozenset(table + '_DELETE' for table in
        ('USERS', 'CARDS', 'TRANSACTIONS', 'BUDGETS', 'DEMO_VISIT')) |
        frozenset(('CARDS_UPDATE', 'DEMO_VISIT_UPDATE')),
    'CLEANUP': NEGATIVE_COMMON | frozenset(table + '_' + verb for table in
        ('USERS', 'CARDS', 'TRANSACTIONS', 'BUDGETS', 'DEMO_VISIT') for verb in ('INSERT', 'UPDATE')),
}
CHECK_NAMES = frozenset(('BOUND_ADMISSION_ROLLBACK',
 'FOUR_IDENTITY_VERIFIED_TLS_CONNECTIONS',
 'LEAST_PRIVILEGE_BEFORE_DATA',
 'MUTABLE_CATEGORY_ACCEPTED',
 'NOWAIT_3572_HY000',
 'ONE_V1_DATASET_315_ROWS',
 'PRODUCT_COUNT_AND_FULL',
 'REAL_RESTRICTED_CLEANUP_APPLY',
 'ROLLBACK_COMMIT_RELEASE_LOCK',
 'SCHEMA_METADATA',
 'UNRELATED_TABLES_SINGLETONS_UNCHANGED',
 'VERIFY_AND_DRY_RUN_ZERO_DML'))
PHASES = frozenset(('AGE_OWNED_FIXTURE',
 'CAPACITY_FULL',
 'CLEANUP_APPLY',
 'CLEANUP_VERIFY_DRY_RUN',
 'DATASET',
 'INPUT',
 'MUTABLE_CATEGORY',
 'NOWAIT',
 'PRIVILEGES',
 'REVIEWED_DDL',
 'ROLLBACK',
 'SCHEMA',
 'SETUP'))
FAILURE_CODES = frozenset(('NEGATIVE_PROBE_NOT_REVIEWED', 'ACCOUNTS_DISTINCT',
 'ACCOUNT_FORMAT',
 'ACTUAL_TIDB_REQUIRED',
 'ADMIN_NO_EXTERNAL_FK',
 'AFFECTED_ROWS',
 'ARGUMENTS_REJECTED',
 'A_COMMIT_RELEASE',
 'BATCH_DISABLED',
 'BUDGET_INVALID',
 'CAPACITY_SELECT',
 'CLEANUP_BUDGET_OR_DEADLINE',
 'CLEANUP_COMMIT_UNKNOWN',
 'CLEANUP_CONNECTION_CLOSE_UNKNOWN',
 'CLEANUP_CREDENTIAL_FILE_REJECTED',
 'CLEANUP_EXACT_ONE_VISIT',
 'CLEANUP_FIVE_EXACT_DELETES_NO_UPDATE',
 'CLEANUP_FOUR_FKS_VISIBLE',
 'CLEANUP_INPUT_REJECTED',
 'CLEANUP_INTEGRITY_REJECTED',
 'CLEANUP_LOCK_BUSY',
 'CLEANUP_ROLLBACK_UNKNOWN',
 'CLEANUP_ROW_COUNT_MISMATCH',
 'CLEANUP_SCHEMA_REJECTED',
 'CLEANUP_SELECT_EMPTY',
 'CLEANUP_SQL_FAILURE',
 'CLEANUP_TEN_TABLES_VISIBLE',
 'CLEANUP_UNEXPECTED_FAILURE',
 'CLEANUP_UNSAFE_BATCH',
 'COMMIT_RELEASE',
 'CONNECTION_LIMIT',
 'DATASET_ABSENT',
 'DDL_COUNT',
 'DDL_DIGEST',
 'DDL_FILE',
 'DEMO_ADMISSION_BUSY',
 'DEMO_ADMISSION_UNAVAILABLE',
 'DEMO_CAPACITY_FULL',
 'DRY_RUN_UNCHANGED',
 'EXCESS_PRIVILEGE',
 'EXPECTED_PRIVILEGE_DENIAL',
 'FINAL_COUNT_ZERO',
 'FINITE_DEADLINE',
 'FIXED_BUDGET',
 'FIXTURE_IDENTITIES',
 'FIXTURE_SQL_FAILURE',
 'FRESH_ACCOUNTS_REQUIRED',
 'FRESH_SCHEMA_REQUIRED',
 'FULL_CLASSIFICATION',
 'FULL_NOT_REJECTED',
 'FULL_NO_ADDITIONAL_ROWS',
 'GENERATED_KEY',
 'GENERATED_KEY_ONE',
 'GENERATED_PASSWORD_FORMAT',
 'HOST_FORMAT',
 'INITIAL_COUNT_ZERO',
 'INPUT_KEYS',
 'INPUT_OBJECT',
 'INSERT_ONE',
 'LEDGER_WRITE_FAILED',
 'LOCK_SELECT',
 'NOWAIT_ACQUIRED',
 'NOWAIT_PRODUCT_MAPPING',
 'NOWAIT_VENDOR_STATE',
 'ONE_COMMITTED_VISIT',
 'OWNED_CLEANUP_ROW_BOUND',
 'OWNED_SCHEMA_EXISTENCE',
 'OWNED_TABLE_ALLOWLIST',
 'OWNED_TABLE_EMPTY',
 'PORT_RANGE',
 'PRIVATE_DIRECTORY_MISMATCH',
 'PRIVATE_DIRECTORY_MODE',
 'PRIVATE_FILE_SAFETY',
 'PRIVATE_PATH_REQUIRED',
 'PRIVILEGE_PROBE_ZERO_ROWS',
 'ROLE_LOCK_SUCCESS',
 'ROLLBACK_ALL_ABSENT',
 'ROLLBACK_INITIAL_ZERO',
 'ROLLBACK_RELEASE',
 'SCALAR_NUMBER',
 'SCHEMA_FORMAT',
 'SETUP_PASSWORD',
 'SETUP_PREFIX',
 'SQL_EXCEPTION',
 'TABLE_NOT_REVIEWED',
 'TEN_TABLES_CREATED',
 'TLS_CIPHER_REQUIRED',
 'UNEXPECTED_FAILURE',
 'UNRELATED_ALL_COLUMNS_UNCHANGED',
 'UNRELATED_TABLES_UNCHANGED',
 'UNREVIEWED_EXECUTION',
 'UNREVIEWED_METADATA',
 'V1_COUNTS',
 'V1_NULL_INVARIANTS',
 'VERIFY_DML_ZERO',
 'WORK_BUDGET_OR_DEADLINE'))


def new_state(path):
    path = Path(path).absolute()
    if (ROOT == path or ROOT in path.parents or '..' in path.parts
            or any(name in path.parts for name in OLD_STATE_NAMES)
            or not re.fullmatch(r'moneytoad-deploy-admission-lock-tidb-[a-zA-Z0-9_-]{1,64}', path.name)):
        raise ValueError('PRIVATE_STATE_PATH')
    for ancestor in (path, *path.parents):
        if ancestor.is_symlink():
            raise ValueError('PRIVATE_STATE_PATH')
    if path.exists():
        raise ValueError('NEW_PRIVATE_STATE_REQUIRED')
    path.mkdir(mode=0o700)
    details = path.stat()
    if not stat.S_ISDIR(details.st_mode) or details.st_uid != os.getuid() or stat.S_IMODE(details.st_mode) != 0o700:
        raise ValueError('PRIVATE_STATE_MODE')
    return path


def static_plan():
    if (WORK_LIMIT != 2000 or CLEANUP_LIMIT != 500 or TOTAL_LIMIT != 2500
            or WORK_LIMIT + CLEANUP_LIMIT != TOTAL_LIMIT or CONNECTION_LIMIT != 4
            or any(type(value) is not int or value <= 0 for value in STATIC_WORK_BOUNDS.values())
            or sum(STATIC_WORK_BOUNDS.values()) != WORK_LIMIT):
        raise ValueError('STATIC_BUDGET_REJECTED')
    return {'work_components': dict(STATIC_WORK_BOUNDS), 'work_upper_bound': WORK_LIMIT,
            'cleanup_reserved_before_launch': CLEANUP_LIMIT, 'total_reserved_upper_bound': TOTAL_LIMIT,
            'planned_physical_connections': 4, 'authorized_connection_limit': CONNECTION_LIMIT,
            'provider_billing_measurement': False}


def reserve_once(state, input_digest, identities):
    plan = static_plan()
    # O_EXCL plus a never-reused directory prevents recovery by resetting state.
    private_json(state / 'execution-marker.json', {
        'operation': 'TIDB_ADMISSION_LOCK_ONCE', 'inputDigest': input_digest,
        'reservedCommands': TOTAL_LIMIT, 'workLimit': WORK_LIMIT, 'cleanupLimit': CLEANUP_LIMIT,
        'connectionLimit': CONNECTION_LIMIT, 'schemaLimit': 1, 'runtimeAccountLimit': 1,
        'cleanupAccountLimit': 1, 'ownedResources': identities, 'staticPlan': plan,
    })
    private_json(state / 'budget.json', {'reservedCommands': TOTAL_LIMIT,
        'workReserved': WORK_LIMIT, 'cleanupReserved': CLEANUP_LIMIT, 'totalLimit': TOTAL_LIMIT,
        'loginAttempts': 0, 'upstashConnections': 0})
    directory = os.open(state, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def project_result(result):
    required = {'status', 'checks', 'nowaitSqlState', *RESULT_INTEGERS, *RESULT_BOOLEANS}
    allowed = required | RESULT_OPTIONAL
    if not isinstance(result, dict) or not required <= result.keys() or result.keys() - allowed:
        raise ValueError('RESULT_CONTRACT')
    if result['status'] not in ('PASS', 'FAIL', 'BLOCKED', 'INTERRUPTED'):
        raise ValueError('RESULT_CONTRACT')
    if any(type(result[key]) is not bool for key in RESULT_BOOLEANS):
        raise ValueError('RESULT_CONTRACT')
    if any(type(result[key]) is not int or result[key] < 0 for key in RESULT_INTEGERS):
        raise ValueError('RESULT_CONTRACT')
    if (result['connectionAttempts'] > CONNECTION_LIMIT or result['workCommands'] > WORK_LIMIT
            or result['cleanupCommands'] > CLEANUP_LIMIT):
        raise ValueError('RESULT_CONTRACT')
    if {'failedNegativeRole', 'failedNegativeOperation'} & result.keys():
        role, operation = result.get('failedNegativeRole'), result.get('failedNegativeOperation')
        if (type(role) is not str or type(operation) is not str
                or role not in NEGATIVE_OPERATIONS or operation not in NEGATIVE_OPERATIONS[role]
                or result['status'] == 'PASS' or result.get('failedPhase') != 'PRIVILEGES'):
            raise ValueError('RESULT_CONTRACT')
    if 'failedPhase' in result and result['failedPhase'] not in PHASES:
        raise ValueError('RESULT_CONTRACT')
    if 'failureCode' in result and result['failureCode'] not in FAILURE_CODES:
        raise ValueError('RESULT_CONTRACT')
    if any(type(result[key]) is not bool for key in ('ledgerFailure', 'cleanupFailure') if key in result):
        raise ValueError('RESULT_CONTRACT')
    if 'nowaitElapsedMillis' in result and (type(result['nowaitElapsedMillis']) is not int
            or result['nowaitElapsedMillis'] < 0):
        raise ValueError('RESULT_CONTRACT')
    if 'databaseVersion' in result and (not isinstance(result['databaseVersion'], str)
            or not re.fullmatch(r'[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}-TiDB-v[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}', result['databaseVersion'])):
        raise ValueError('RESULT_CONTRACT')
    if 'failureVendor' in result and (type(result['failureVendor']) is not int or result['failureVendor'] < 0):
        raise ValueError('RESULT_CONTRACT')
    if 'failureSqlState' in result:
        failed_state = result['failureSqlState']
        if not isinstance(failed_state, str) or (failed_state != 'NOT_OBSERVED' and not re.fullmatch(r'[A-Z0-9]{5}', failed_state)):
            raise ValueError('RESULT_CONTRACT')
    state = result['nowaitSqlState']
    if not isinstance(state, str) or (state != 'NOT_OBSERVED' and not re.fullmatch(r'[A-Z0-9]{5}', state)):
        raise ValueError('RESULT_CONTRACT')
    checks = result['checks']
    if (not isinstance(checks, dict) or checks.keys() - CHECK_NAMES
            or any(type(value) is not bool for value in checks.values())):
        raise ValueError('RESULT_CONTRACT')
    if result['status'] == 'PASS' and ('databaseVersion' not in result
            or not all(result[key] for key in RESULT_BOOLEANS)
            or checks.keys() != CHECK_NAMES or not all(checks.values())
            or any(result.get(key, False) for key in ('ledgerFailure', 'cleanupFailure'))
            or result['nowaitVendor'] != 3572 or state != 'HY000'):
        raise ValueError('RESULT_CONTRACT')
    if forbidden(result):
        raise ValueError('RESULT_CONTRACT')
    return result


def local_test_results(directory):
    counts = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    proofs = set()
    suites = sorted(directory.glob('TEST-*.xml'))
    if not suites:
        raise ValueError('COMPILE_FAILED')
    for report in suites:
        try:
            suite = ET.parse(report).getroot()
        except (ET.ParseError, OSError):
            raise ValueError('COMPILE_FAILED') from None
        if not suite.attrib.get('name', '').startswith('com.potg.verification.admissionlock.'):
            raise ValueError('COMPILE_FAILED')
        if suite.attrib.get('name') == 'com.potg.verification.admissionlock.AdmissionLockTiDbProbeSafetyTest':
            proofs.update(case.attrib.get('name', '').removesuffix('()')
                          for case in suite.findall('testcase')
                          if not any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')))
        for key in counts:
            counts[key] += int(suite.attrib.get(key, '0'))
    if (counts['tests'] < len(REQUIRED_BUDGET_PROOFS)
            or not REQUIRED_BUDGET_PROOFS <= proofs
            or any(counts[key] for key in ('failures', 'errors', 'skipped'))):
        raise ValueError('COMPILE_FAILED')
    counts['budget_proofs_passed'] = True
    return counts


def compile_probe(work, cache):
    source = work / 'snapshot'
    for relative in inputs():
        target = source / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / relative, target)
    for part in ('wrapper', 'caches'):
        if not (cache / part).is_dir():
            raise ValueError('EXPLICIT_CACHE_REQUIRED')
        shutil.copytree(cache / part, work / 'cache' / part, ignore=shutil.ignore_patterns('*.lock', '*.lck'))
    java = subprocess.run(['/usr/libexec/java_home', '-v', '21'], capture_output=True,
                          text=True, check=True).stdout.strip()
    env = {'PATH': java + '/bin:/usr/bin:/bin:/usr/sbin:/sbin', 'JAVA_HOME': java,
           'GRADLE_USER_HOME': str(work / 'cache'), 'LANG': 'en_US.UTF-8', 'TMPDIR': str(work),
           'ADMISSION_LOCK_BUILD': str(work / 'build'), 'ADMISSION_LOCK_CLASSPATH': str(work / 'classpath')}
    init = work / 'init.gradle'
    init.write_text("""allprojects {
 layout.buildDirectory.set(file(System.getenv('ADMISSION_LOCK_BUILD')))
 afterEvaluate {
  tasks.register('admissionLockProbeClasspath') {
   dependsOn 'testClasses', 'maintenanceClasses'
   doLast { file(System.getenv('ADMISSION_LOCK_CLASSPATH')).text = sourceSets.test.runtimeClasspath.asPath }
  }
 }
}
""")
    command = ['bash', './gradlew', '--offline', '--no-daemon', '--console=plain', '--max-workers=2',
               '--project-cache-dir', str(work / 'project-cache'), '--init-script', str(init),
               'compileJava', 'compileMaintenanceJava', 'compileTestJava', 'test',
               '--tests', 'com.potg.verification.admissionlock.*Test', 'admissionLockProbeClasspath']
    with (work / 'compile-private.txt').open('w') as log:
        process = subprocess.Popen(command, cwd=source / 'be', env=env, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout=420)
        finally:
            # Repeated interrupt must not interrupt owned compiler termination.
            previous = {signum: signal.signal(signum, signal.SIG_IGN)
                        for signum in (signal.SIGINT, signal.SIGTERM)}
            try:
                stopped = stop(process)
            finally:
                for signum, handler in previous.items():
                    signal.signal(signum, handler)
    if code or not stopped:
        raise ValueError('COMPILE_FAILED')
    counts = local_test_results(work / 'build/test-results/test')
    (work / 'local-tests.json').write_text(json.dumps(counts))
    return Path(java), env, (work / 'classpath').read_text(), source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--compile-only', action='store_true')
    mode.add_argument('--execute', action='store_true')
    parser.add_argument('--cache-seed', type=Path, required=True)
    parser.add_argument('--state-dir', type=Path, help='New private moneytoad-deploy-admission-lock-tidb-* directory; never reuse')
    args = parser.parse_args()
    if args.execute and args.state_dir is None:
        parser.error('--execute requires --state-dir')
    os.umask(0o077)
    result = {'status': 'BLOCKED', 'phase': 'TIDB_ADMISSION_LOCK', 'input_status': 'NOT_CHECKED',
              'provider_process_launched': False, 'old_provider_state_accessed': False,
              'upstash_connections': 0, 'redis_commands': 0, 'http_logins': 0,
              'reserved_command_equivalents': 0, 'budget_limit': TOTAL_LIMIT,
              'work_limit': WORK_LIMIT, 'cleanup_reserved': 0, 'connection_limit': CONNECTION_LIMIT,
              'public_deployment_ready': False, 'remote_demo_capacity_verified': False,
              'remote_demo_schema_verified': False, 'remote_demo_lock_privileges_verified': False,
              'remote_demo_admission_verified': False, 'remote_demo_cleanup_verified': False,
              'remote_commit_ambiguity': 'REMOTE_NOT_EXERCISED',
              'remote_http_redis_fe_and_browser': 'REMOTE_NOT_EXERCISED',
              'remote_unsafe_financial_job_fixture': 'REMOTE_NOT_EXERCISED',
              'remote_second_visitor': 'REMOTE_NOT_EXERCISED',
              'local_diagnostics_retained': False}
    work = state = config = ledger = summary = process = None
    baseline_digest = None
    input_path = Path.home() / '.config/moneytoad/provider-check.env'
    safe_cleanup = True
    child_stopped = True
    launching = pending_interrupt = False
    previous = {}
    try:
        result['static_plan'] = static_plan()
        if args.execute:
            _, baseline_digest = read_tidb_inputs(input_path)
            result['input_status'] = 'PASS'
        def interrupted(signum, frame):
            nonlocal pending_interrupt
            if launching:
                pending_interrupt = True
                return
            raise KeyboardInterrupt()
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous[signum] = signal.signal(signum, interrupted)
        work = Path(tempfile.mkdtemp(prefix='moneytoad-admission-lock-compile-')).resolve()
        java, env, classpath, source = compile_probe(work, args.cache_seed.resolve())
        result['compilation'] = 'PASS'
        result['local_java_tests'] = json.loads((work / 'local-tests.json').read_text())
        if args.compile_only:
            result.update(status='PREPARED', remote_execution='NOT_PERFORMED')
        else:
            values, current_digest = read_tidb_inputs(input_path)
            if baseline_digest != current_digest:
                raise ValueError('INPUT_CHANGED')
            identities = {'schema': 'mtadmissionlock' + uuid.uuid4().hex[:16],
                'runtimeAccount': account_name(values['TIDB_SETUP_USERNAME'], secrets.token_hex(7)),
                'cleanupAccount': account_name(values['TIDB_SETUP_USERNAME'], secrets.token_hex(7))}
            if identities['runtimeAccount'] == identities['cleanupAccount']:
                raise ValueError('ACCOUNT_PREFIX_REJECTED')
            state = new_state(args.state_dir)
            config, ledger, summary = (state / name for name in ('config.json', 'ownership.json', 'result.json'))
            private_json(ledger, {'cleanupComplete': False})
            private_json(summary, {})
            settings = {**values, **identities, 'runtimePassword': secrets.token_hex(32),
                'cleanupPassword': secrets.token_hex(32),
                'baselinePath': str(source / 'scripts/verification/fixtures/managed-provider-schema.sql'),
                'migrationPath': str(source / 'be/src/main/resources/db/demo/V001__demo_admission.sql'),
                'lockMigrationPath': str(source / 'be/src/main/resources/db/demo/V002__demo_admission_lock.sql'),
                'deadlineEpochMillis': int((time.time() + 600) * 1000),
                'workLimit': WORK_LIMIT, 'cleanupLimit': CLEANUP_LIMIT, 'connectionLimit': CONNECTION_LIMIT}
            private_json(config, settings)
            reserve_once(state, baseline_digest, identities)
            result.update(reserved_command_equivalents=TOTAL_LIMIT, cleanup_reserved=CLEANUP_LIMIT)
            values.clear(); settings.clear()
            env.update(ADMISSION_LOCK_CHECK_CONFIG=str(config), ADMISSION_LOCK_CHECK_RESULT=str(summary),
                       ADMISSION_LOCK_CHECK_LEDGER=str(ledger))
            launching = True
            try:
                process = subprocess.Popen([str(java / 'bin/java'), '-cp', classpath, MAIN_CLASS],
                    cwd=source / 'be', env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    start_new_session=True)
                result['provider_process_launched'] = True
                safe_cleanup = child_stopped = False
            finally:
                launching = False
            if pending_interrupt:
                raise KeyboardInterrupt()
            process.wait(timeout=780)
    except KeyboardInterrupt:
        result.update(status='INTERRUPTED', reason='INTERRUPTED')
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        reason = str(error) if isinstance(error, ValueError) and str(error) in SAFE_REASONS else (
            'CHILD_DEADLINE' if isinstance(error, subprocess.TimeoutExpired) else
            'OS_ERROR' if isinstance(error, OSError) else 'EXECUTION_REJECTED')
        result.update(status='BLOCKED', reason=reason)
    finally:
        for signum in previous:
            signal.signal(signum, signal.SIG_IGN)
        graceful = True
        try:
            graceful = stop(process)
            child_stopped = process is None or process.poll() is not None
        except (OSError, subprocess.SubprocessError):
            child_stopped = False
        if result['provider_process_launched']:
            safe_cleanup = False
            try:
                observation = project_result(private_document(summary))
                owned = private_document(ledger)
                safe_cleanup = (graceful and child_stopped and observation['cleanupComplete']
                                and observation['clientsClosed'] and owned.get('cleanupComplete') is True)
                result['observation'] = observation
                passed = (not result.get('reason') and process.returncode == 0
                          and observation['status'] == 'PASS' and safe_cleanup)
                if result['status'] != 'INTERRUPTED':
                    result['status'] = 'PASS' if passed else 'FAIL'
                for short in ('schema', 'admission', 'cleanup'):
                    result['remote_demo_' + short + '_verified'] = observation[short + 'Verified']
                result['remote_demo_lock_privileges_verified'] = observation['privilegesVerified']
                result['remote_demo_capacity_verified'] = passed
            except (OSError, ValueError, TypeError):
                result.update(status='FAIL', reason='RESOURCE_CLEANUP_UNCONFIRMED')
        try:
            if state is not None and result['reserved_command_equivalents']:
                expected_budget = {'reservedCommands': TOTAL_LIMIT, 'workReserved': WORK_LIMIT,
                    'cleanupReserved': CLEANUP_LIMIT, 'totalLimit': TOTAL_LIMIT,
                    'loginAttempts': 0, 'upstashConnections': 0}
                if private_document(state / 'budget.json') != expected_budget:
                    raise ValueError('STATIC_BUDGET_REJECTED')
                result['reservation_unchanged'] = True
            if baseline_digest is not None:
                _, current_digest = read_tidb_inputs(input_path)
                result['input_unchanged'] = current_digest == baseline_digest
                if not result['input_unchanged']:
                    result.update(status='FAIL', reason='INPUT_CHANGED', remote_demo_capacity_verified=False)
            if safe_cleanup and child_stopped:
                if config is not None:
                    config.unlink(missing_ok=True)
                if work is not None:
                    if result.get('compilation') == 'PASS':
                        shutil.rmtree(work)
                    else:
                        result['local_diagnostics_retained'] = True
        except (OSError, ValueError):
            safe_cleanup = False
        result.update(cleanup_complete=safe_cleanup and child_stopped,
                      child_stopped=child_stopped, forced_child_termination=not graceful,
                      remaining_command_equivalents=TOTAL_LIMIT - result['reserved_command_equivalents'])
        if not result['cleanup_complete']:
            result.update(status='FAIL', remote_demo_capacity_verified=False)
        result['remote_cleanup'] = ('NOT_NEEDED_NO_PROVIDER_PROCESS' if not result['provider_process_launched']
                                    else 'CONFIRMED' if safe_cleanup else 'UNCONFIRMED_PRIVATE_LEDGER_RETAINED')
        if forbidden(result):
            raise ValueError('RESULT_CONTRACT')
        destination = OUTPUT / ('probe-' + uuid.uuid4().hex[:16])
        destination.mkdir(parents=True, exist_ok=False)
        (destination / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({key: result[key] for key in ('status', 'provider_process_launched',
              'remote_demo_capacity_verified', 'cleanup_complete', 'reserved_command_equivalents')}))
        for signum, handler in previous.items():
            signal.signal(signum, handler)
    return 0 if result['status'] in ('PASS', 'PREPARED') else 2


if __name__ == '__main__':
    raise SystemExit(main())
