#!/usr/bin/env python3
"""One independently authorized TiDB-only admission/cleanup probe.

No existing provider state is opened. A new private task reserves its complete
finite work/cleanup allowance before launch and can never be replayed. The child
uses the current product store and maintenance service, not the full test suite.
"""
import argparse
import hashlib
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

from managed_provider_preflight import MAX_BYTES, InputRejected, parse_values
from public_evidence import forbidden
from render_regression import ROOT, inputs

MAIN_CLASS = 'com.potg.verification.capacity.CapacityTiDbProbe'
OUTPUT = ROOT / 'docs/deployment/evidence/DEMO_CAPACITY_TIDB'
TOTAL_LIMIT = 2000
WORK_LIMIT = 1500
CLEANUP_LIMIT = 500
CONNECTION_LIMIT = 8
TIDB_KEYS = frozenset(('TIDB_HOST', 'TIDB_PORT', 'TIDB_SETUP_USERNAME', 'TIDB_SETUP_PASSWORD'))
OLD_STATE_NAME = 'moneytoad-deploy03-provider-state'
# Reviewed finite call graph: two V1 visitors, five schema checks, no reconnect,
# no batch SQL, no metadata discovery loop outside the reviewed fixed nine tables.
# Driver handshake/session initialization reserve32 per physical connection;
# each allowed metadata ResultSet call is charged8 before delegation.
STATIC_WORK_BOUNDS = {
    'driver_connection_initialization': 128,
    'reviewed_metadata_calls': 280,
    'schema_and_grants': 110,
    'two_v1_fixture_inserts': 630,
    'admission_locks_and_transaction_state': 130,
    'cleanup_sql_contract_calls': 150,
    'privilege_denials_and_observations': 72,
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
    'failureVendor', 'failureSqlState'))
CHECK_NAMES = frozenset(('FOUR_IDENTITY_VERIFIED_TLS_CONNECTIONS', 'ADMIN_UNSAFE_DATASET_REMOVED', 'BOUND_ADMISSION_ROLLBACK',
    'COMMIT_FULL_COUNTER_EQUALITY', 'LEAST_PRIVILEGE_DENIALS', 'MUTABLE_CATEGORY_ACCEPTED',
    'NOWAIT_3572_HY000', 'REAL_RESTRICTED_CLEANUP_APPLY', 'SCHEMA_METADATA',
    'UNRELATED_ROW_UNCHANGED', 'UNSAFE_BATCH_ZERO_DML', 'VERIFY_AND_DRY_RUN_ZERO_DML'))
PHASES = frozenset(('ADMIN_UNSAFE_TEARDOWN', 'AGE_OWNED_FIXTURES', 'CAPACITY_FULL',
    'CLEANUP_APPLY', 'CLEANUP_VERIFY_DRY_RUN', 'DATASETS', 'INPUT', 'NOWAIT',
    'PRIVILEGES', 'REVIEWED_DDL', 'ROLLBACK', 'SCHEMA', 'SETUP', 'UNSAFE_BATCH'))
FAILURE_CODES = frozenset(('OWNED_SCHEMA_EXISTENCE', 'OWNED_TABLE_ALLOWLIST', 'ACTUAL_TIDB_REQUIRED', 'ACCOUNTS_DISTINCT', 'ACCOUNT_FORMAT', 'ADMIN_NO_EXTERNAL_FK',
    'AFFECTED_ROWS', 'ARGUMENTS_REJECTED', 'BATCH_DISABLED', 'BUDGET_INVALID',
    'CLEANUP_BUDGET_OR_DEADLINE', 'CLEANUP_EXACT_ONE_VISIT', 'CLEANUP_FOUR_FKS_VISIBLE',
    'CLEANUP_NINE_TABLES_VISIBLE', 'CONNECTION_LIMIT', 'DATASETS_ABSENT', 'DDL_COUNT',
    'DDL_DIGEST', 'DDL_FILE', 'DRY_RUN_UNCHANGED', 'EXCESS_PRIVILEGE', 'EXPECTED_PRIVILEGE_DENIAL',
    'FINAL_COUNTER_EQUALITY', 'FINITE_DEADLINE', 'FIXED_BUDGET', 'FIXTURE_IDENTITIES',
    'FIXTURE_SQL_FAILURE', 'FRESH_ACCOUNTS_REQUIRED', 'FRESH_SCHEMA_REQUIRED',
    'FULL_CLASSIFICATION', 'FULL_COUNTER_UNCHANGED', 'FULL_NOT_REJECTED', 'GENERATED_KEY',
    'GENERATED_KEY_ONE', 'GENERATED_PASSWORD_FORMAT', 'HOST_FORMAT', 'INITIAL_COUNTER',
    'INPUT_KEYS', 'INPUT_OBJECT', 'INSERT_ONE', 'LEDGER_WRITE_FAILED', 'NINE_TABLES_CREATED',
    'NOWAIT_ACQUIRED', 'NOWAIT_PRODUCT_MAPPING', 'NOWAIT_VENDOR_STATE', 'OWNED_CLEANUP_ROW_BOUND',
    'OWNED_TABLE_EMPTY', 'PORT_RANGE', 'PRIVATE_DIRECTORY_MISMATCH', 'PRIVATE_DIRECTORY_MODE',
    'PRIVATE_FILE_SAFETY', 'PRIVATE_PATH_REQUIRED', 'ROLLBACK_ALL_ABSENT', 'SCALAR_NUMBER',
    'SCHEMA_FORMAT', 'SETUP_PASSWORD', 'SETUP_PREFIX', 'SQL_EXCEPTION', 'TLS_CIPHER_REQUIRED',
    'TWO_COMMITTED_VISITS', 'UNEXPECTED_FAILURE', 'UNRELATED_ALL_COLUMNS_UNCHANGED',
    'UNREVIEWED_EXECUTION', 'UNREVIEWED_METADATA', 'UNSAFE_BATCH_ACCEPTED',
    'UNSAFE_CLASSIFICATION', 'UNSAFE_TEARDOWN_COUNTER', 'UNSAFE_WHOLE_BATCH_UNCHANGED',
    'UNWRAP_REJECTED', 'V1_COUNTS', 'V1_NULL_INVARIANTS', 'VERIFY_DML_ZERO',
    'WORK_BUDGET_OR_DEADLINE', 'DEMO_CAPACITY_FULL', 'DEMO_ADMISSION_BUSY',
    'DEMO_ADMISSION_UNAVAILABLE', 'CLEANUP_INPUT_REJECTED', 'CLEANUP_CREDENTIAL_FILE_REJECTED',
    'CLEANUP_SCHEMA_REJECTED', 'CLEANUP_INTEGRITY_REJECTED', 'CLEANUP_UNSAFE_BATCH',
    'CLEANUP_LOCK_BUSY', 'CLEANUP_SQL_FAILURE', 'CLEANUP_ROW_COUNT_MISMATCH',
    'CLEANUP_ROLLBACK_UNKNOWN', 'CLEANUP_COMMIT_UNKNOWN', 'CLEANUP_CONNECTION_CLOSE_UNKNOWN',
    'CLEANUP_UNEXPECTED_FAILURE'))


def private_json(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        json.dump(value, output)
        output.flush()
        os.fsync(output.fileno())


def private_document(path, maximum=131072):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        details = os.fstat(descriptor)
        if (not stat.S_ISREG(details.st_mode) or details.st_uid != os.getuid()
                or details.st_nlink != 1 or stat.S_IMODE(details.st_mode) != 0o600
                or details.st_size > maximum):
            raise ValueError('PRIVATE_FILE_CONTRACT')
        with os.fdopen(descriptor, 'r', closefd=False) as stream:
            return json.loads(stream.read(maximum + 1))
    finally:
        os.close(descriptor)


def read_tidb_inputs(path):
    """Open approved bytes without links; return only TiDB fields and private digest."""
    path = Path(path).absolute()
    directory = opened = None
    try:
        if '..' in path.parts:
            raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
        directory = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        for part in path.parts[1:-1]:
            next_directory = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory)
            directory = next_directory
        parent = os.fstat(directory)
        if parent.st_uid != os.getuid() or stat.S_IMODE(parent.st_mode) != 0o700:
            raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
        opened = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        before = os.fstat(opened)
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid()
                or stat.S_IMODE(before.st_mode) != 0o600 or before.st_nlink != 1
                or before.st_size > MAX_BYTES):
            raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
        with os.fdopen(opened, 'rb', closefd=False) as stream:
            raw = stream.read(MAX_BYTES + 1)
        after = os.fstat(opened)
        current = os.stat(path.name, dir_fd=directory, follow_symlinks=False)
        fields = ('st_dev', 'st_ino', 'st_size', 'st_mtime_ns', 'st_ctime_ns')
        if (len(raw) > MAX_BYTES or any(getattr(before, f) != getattr(after, f) for f in fields)
                or (after.st_dev, after.st_ino) != (current.st_dev, current.st_ino)):
            raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
        parsed = parse_values(raw.decode('utf-8')).values
        return {key: parsed[key] for key in TIDB_KEYS}, hashlib.sha256(raw).hexdigest()
    except (OSError, UnicodeError, InputRejected):
        raise ValueError('INPUT_UNSAFE_OR_UNREADABLE') from None
    finally:
        if opened is not None:
            os.close(opened)
        if directory is not None:
            os.close(directory)


def new_state(path):
    path = Path(path).absolute()
    if (ROOT == path or ROOT in path.parents or '..' in path.parts
            or OLD_STATE_NAME in path.parts
            or not re.fullmatch(r'moneytoad-deploy-capacity-tidb-[a-zA-Z0-9_-]{1,64}', path.name)):
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


def account_name(setup_name, entropy):
    if (not re.fullmatch(r'[A-Za-z0-9]{1,16}\.[A-Za-z0-9_]+', setup_name)
            or not re.fullmatch(r'[a-f0-9]{14}', entropy)):
        raise ValueError('ACCOUNT_PREFIX_REJECTED')
    value = setup_name.split('.', 1)[0] + '.m' + entropy
    if len(value) > 32:
        raise ValueError('ACCOUNT_PREFIX_REJECTED')
    return value


def static_plan():
    if (WORK_LIMIT != 1500 or CLEANUP_LIMIT != 500 or TOTAL_LIMIT != 2000
            or WORK_LIMIT + CLEANUP_LIMIT != TOTAL_LIMIT or CONNECTION_LIMIT != 8
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
        'operation': 'TIDB_ADMISSION_CLEANUP_ONCE', 'inputDigest': input_digest,
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
    if 'failedPhase' in result and result['failedPhase'] not in PHASES:
        raise ValueError('RESULT_CONTRACT')
    if 'failureCode' in result and result['failureCode'] not in FAILURE_CODES:
        raise ValueError('RESULT_CONTRACT')
    if any(type(result[key]) is not bool for key in ('ledgerFailure', 'cleanupFailure') if key in result):
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
    if result['status'] == 'PASS' and (not all(result[key] for key in RESULT_BOOLEANS)
            or checks.keys() != CHECK_NAMES or not all(checks.values())
            or any(result.get(key, False) for key in ('ledgerFailure', 'cleanupFailure'))
            or result['nowaitVendor'] != 3572 or state != 'HY000'):
        raise ValueError('RESULT_CONTRACT')
    if forbidden(result):
        raise ValueError('RESULT_CONTRACT')
    return result


def group_alive(process):
    try:
        os.killpg(process.pid, 0)
        return True
    except ProcessLookupError:
        return False


def stop(process, timeout=180):
    if process is None:
        return True
    if process.poll() is not None and not group_alive(process):
        return True
    os.killpg(process.pid, signal.SIGTERM)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        process.poll()
        if process.returncode is not None and not group_alive(process):
            return True
        time.sleep(0.1)
    if group_alive(process):
        os.killpg(process.pid, signal.SIGKILL)
    process.wait(timeout=15)
    return False


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
           'CAPACITY_BUILD': str(work / 'build'), 'CAPACITY_CLASSPATH': str(work / 'classpath')}
    init = work / 'init.gradle'
    init.write_text("""allprojects {
 layout.buildDirectory.set(file(System.getenv('CAPACITY_BUILD')))
 afterEvaluate {
  tasks.register('capacityProbeClasspath') {
   dependsOn 'testClasses', 'maintenanceClasses'
   doLast { file(System.getenv('CAPACITY_CLASSPATH')).text = sourceSets.test.runtimeClasspath.asPath }
  }
 }
}
""")
    command = ['bash', './gradlew', '--offline', '--no-daemon', '--console=plain', '--max-workers=2',
               '--project-cache-dir', str(work / 'project-cache'), '--init-script', str(init),
               'compileJava', 'compileMaintenanceJava', 'compileTestJava', 'test',
               '--tests', 'com.potg.verification.capacity.*Test', 'capacityProbeClasspath']
    with (work / 'compile-private.txt').open('w') as log:
        process = subprocess.Popen(command, cwd=source / 'be', env=env, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout=420)
        finally:
            stopped = stop(process)
    if code or not stopped:
        raise ValueError('COMPILE_FAILED')
    counts = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    suites = sorted((work / 'build/test-results/test').glob('TEST-*.xml'))
    if not suites:
        raise ValueError('COMPILE_FAILED')
    for report in suites:
        suite = ET.parse(report).getroot()
        if not suite.attrib.get('name', '').startswith('com.potg.verification.capacity.'):
            raise ValueError('COMPILE_FAILED')
        for key in counts:
            counts[key] += int(suite.attrib.get(key, '0'))
    if counts['tests'] < 1 or any(counts[key] for key in ('failures', 'errors', 'skipped')):
        raise ValueError('COMPILE_FAILED')
    (work / 'local-tests.json').write_text(json.dumps(counts))
    return Path(java), env, (work / 'classpath').read_text(), source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--compile-only', action='store_true')
    mode.add_argument('--execute', action='store_true')
    parser.add_argument('--cache-seed', type=Path, required=True)
    parser.add_argument('--state-dir', type=Path, help='New private moneytoad-deploy-capacity-tidb-* directory; never reuse')
    args = parser.parse_args()
    if args.execute and args.state_dir is None:
        parser.error('--execute requires --state-dir')
    os.umask(0o077)
    result = {'status': 'BLOCKED', 'phase': 'TIDB_ADMISSION_CLEANUP', 'input_status': 'NOT_CHECKED',
              'provider_process_launched': False, 'old_provider_state_accessed': False,
              'upstash_connections': 0, 'redis_commands': 0, 'http_logins': 0,
              'reserved_command_equivalents': 0, 'budget_limit': TOTAL_LIMIT,
              'work_limit': WORK_LIMIT, 'cleanup_reserved': 0, 'connection_limit': CONNECTION_LIMIT,
              'public_deployment_ready': False, 'remote_demo_capacity_verified': False,
              'remote_commit_ambiguity': 'REMOTE_NOT_EXERCISED'}
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
        work = Path(tempfile.mkdtemp(prefix='moneytoad-capacity-compile-')).resolve()
        java, env, classpath, source = compile_probe(work, args.cache_seed.resolve())
        result['compilation'] = 'PASS'
        result['local_java_tests'] = json.loads((work / 'local-tests.json').read_text())
        if args.compile_only:
            result.update(status='PREPARED', remote_execution='NOT_PERFORMED')
        else:
            values, current_digest = read_tidb_inputs(input_path)
            if baseline_digest != current_digest:
                raise ValueError('INPUT_CHANGED')
            identities = {'schema': 'mtcapacity' + uuid.uuid4().hex[:16],
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
                'deadlineEpochMillis': int((time.time() + 600) * 1000),
                'workLimit': WORK_LIMIT, 'cleanupLimit': CLEANUP_LIMIT, 'connectionLimit': CONNECTION_LIMIT}
            private_json(config, settings)
            reserve_once(state, baseline_digest, identities)
            result.update(reserved_command_equivalents=TOTAL_LIMIT, cleanup_reserved=CLEANUP_LIMIT)
            values.clear(); settings.clear()
            env.update(CAPACITY_CHECK_CONFIG=str(config), CAPACITY_CHECK_RESULT=str(summary),
                       CAPACITY_CHECK_LEDGER=str(ledger))
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
                for short in ('schema', 'admission', 'cleanup', 'privileges'):
                    result['remote_demo_' + short + '_verified'] = observation[short + 'Verified']
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
                    shutil.rmtree(work)
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
