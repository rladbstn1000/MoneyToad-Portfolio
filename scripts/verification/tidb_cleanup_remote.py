#!/usr/bin/env python3
"""One independently budgeted TiDB cleanup-only probe; no admission or Redis replay.

No existing provider state is opened. A new private task reserves its complete
finite work/cleanup allowance before launch and can never be replayed. The child
uses the current packaged maintenance service, not the full test suite.
"""
import argparse
import json
import hashlib
import ipaddress
import zipfile
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

from demo_capacity_tidb import (private_json, private_document, account_name, stop)
from public_evidence import forbidden
from render_regression import ROOT, inputs

MAIN_CLASS = 'com.potg.verification.cleanupremote.CleanupTiDbProbe'
REQUIRED_BUDGET_PROOFS = frozenset((
    'actualNormalWorkflowFitsFiniteBudgetAndUsesTwoClients',
    'everyWorkCommandEarlyFailureStillRunsBoundedOwnedCleanup',
    'cancellationFromEveryWorkBoundaryStillClosesOwnedResources',
    'cleanupFailureCannotBorrowWorkOrOpenThirdClient',
))
OUTPUT = ROOT / 'docs/deployment/evidence/TIDB_CLEANUP_REMOTE'
TOTAL_LIMIT = 1500
WORK_LIMIT = 1200
CLEANUP_LIMIT = 300
CONNECTION_LIMIT = 2
TIDB_KEYS = frozenset(('TIDB_HOST', 'TIDB_PORT', 'TIDB_SETUP_USERNAME', 'TIDB_SETUP_PASSWORD'))
OLD_STATE_NAMES = frozenset(('moneytoad-deploy03-provider-state',
    'moneytoad-deploy-capacity-tidb-20261001-state'))
# Conservative finite call envelope, cross-checked by focused Java safety tests.
# Unused margin is reserved permanently; it is not permission for retries.
STATIC_WORK_BOUNDS = {
    'driver_connection_initialization': 64,
    'schema_and_grants': 80,
    'reviewed_metadata_calls': 320,
    'single_v1_fixture_inserts_and_assertions': 340,
    'cleanup_sql_contract_calls': 300,
    'scope_and_state_observations': 80,
    'reserved_unused_margin': 16,
}
SAFE_REASONS = frozenset((
    'NEW_PRIVATE_STATE_REQUIRED', 'PRIVATE_STATE_PATH', 'PRIVATE_STATE_MODE',
    'PRIVATE_FILE_CONTRACT', 'INPUT_UNSAFE_OR_UNREADABLE', 'INPUT_CHANGED',
    'EXPLICIT_CACHE_REQUIRED', 'COMPILE_FAILED', 'STATIC_BUDGET_REJECTED',
    'ACCOUNT_PREFIX_REJECTED', 'RESULT_CONTRACT', 'RESOURCE_CLEANUP_UNCONFIRMED',
    'CHILD_DEADLINE', 'INTERRUPTED', 'OS_ERROR', 'EXECUTION_REJECTED',
    'SOURCE_CHANGED', 'PACKAGE_CONTRACT',
))
RESULT_BOOLEANS = frozenset(('cleanupComplete', 'schemaVerified', 'privilegesVerified',
    'verifyVerified', 'dryRunVerified', 'applyVerified', 'packagedServiceVerified',
    'clientsClosed', 'ownedDataRemoved', 'ownedAccountsAbsent', 'ownedSchemaAbsent'))
RESULT_INTEGERS = frozenset(('connectionAttempts', 'workCommands', 'cleanupCommands'))
RESULT_OPTIONAL = frozenset(('failedPhase', 'failureCode', 'ledgerFailure', 'cleanupFailure',
    'failureVendor', 'failureSqlState', 'databaseVersion', 'modeObservations', 'confirmedReuseResets'))
CHECK_NAMES = frozenset(('APPLY_RC_EXACT_DELETES_COMMIT',
 'DRY_RUN_READ_ONLY_RR_ROLLBACK_DML_ZERO',
 'EXACT_CLEANUP_GRANTS',
 'ONE_MUTABLE_V1_DATASET_315',
 'PACKAGED_PRODUCT_AND_CLEANUP_CONFIG',
 'SCHEMA_METADATA',
 'SINGLETONS_UNRELATED_UNCHANGED',
 'TWO_TLS_CONNECTIONS_PROPAGATION_FALSE',
 'VERIFY_READ_ONLY_RR_ROLLBACK_DML_ZERO'))
PHASES = frozenset(('ADMIN_FIXTURE',
 'APPLY',
 'DRY_RUN',
 'INPUT',
 'REVIEWED_INPUT',
 'SCHEMA_AND_GRANTS',
 'SETUP',
 'VERIFY'))
FAILURE_CODES = frozenset(('NO_UNSUPPORTED_READ_ONLY_FAILURE', 'ACCOUNT_FORMAT',
 'ACTUAL_TIDB_REQUIRED',
 'ADMIN_NO_EXTERNAL_FK',
 'AFFECTED_ROWS',
 'APPLY_COMMIT_CONFIRMED',
 'APPLY_EXACT_FIVE_DELETES',
 'APPLY_RESULT',
 'ARGUMENTS_REJECTED',
 'BATCH_DISABLED',
 'BUDGET_INVALID',
 'CLEANUP_BUDGET_OR_DEADLINE',
 'CLEANUP_COMMIT_UNKNOWN',
 'CLEANUP_CONNECTION_CLOSE_UNKNOWN',
 'CLEANUP_CREDENTIAL_FILE_REJECTED',
 'CLEANUP_CREDENTIAL_MATCH',
 'CLEANUP_FOUR_FKS_VISIBLE',
 'CLEANUP_INPUT_REJECTED',
 'CLEANUP_INTEGRITY_REJECTED',
 'CLEANUP_LOCK_BUSY',
 'CLEANUP_READ_ONLY_GUARD_REJECTED',
 'CLEANUP_ROLLBACK_UNKNOWN',
 'CLEANUP_ROW_COUNT_MISMATCH',
 'CLEANUP_SCHEMA_REJECTED',
 'CLEANUP_SQL_FAILURE',
 'CLEANUP_TEN_TABLES_VISIBLE',
 'CLEANUP_UNEXPECTED_FAILURE',
 'CLEANUP_UNSAFE_BATCH',
 'CLEANUP_URL_MATCH',
 'CONFIRMED_REUSE_STATE',
 'CONNECTION_LIMIT',
 'DATASET_ABSENT',
 'DDL_COUNT',
 'DDL_DIGEST',
 'DDL_FILE',
 'DRY_RUN_RESULT',
 'DRY_RUN_ROWS_UNCHANGED',
 'EFFECTIVE_PROPAGATION_FALSE',
 'EXACT_GRANTS_REQUIRED',
 'FINAL_COUNT_ZERO',
 'FINITE_DEADLINE',
 'FIXED_BUDGET',
 'FIXTURE_IDENTITIES',
 'FIXTURE_SQL_FAILURE',
 'FRESH_ACCOUNT_REQUIRED',
 'FRESH_SCHEMA_REQUIRED',
 'GENERATED_KEY',
 'GENERATED_KEY_ONE',
 'GENERATED_PASSWORD_FORMAT',
 'HOST_FORMAT',
 'INITIAL_COUNT_ZERO',
 'INPUT_KEYS',
 'INPUT_OBJECT',
 'INSERT_ONE',
 'MODE_CONNECTION_STATE',
 'MODE_ISOLATION',
 'NO_SERVER_READ_ONLY_STATEMENT',
 'NO_UNSUPPORTED_READ_ONLY_FAILURE',
 'OWNED_CLEANUP_ROW_BOUND',
 'OWNED_SCHEMA_EXISTENCE',
 'OWNED_TABLE_ALLOWLIST',
 'OWNED_TABLE_EMPTY',
 'PACKAGED_PRODUCT_ORIGIN',
 'PORT_RANGE',
 'PRIVATE_DIRECTORY_MISMATCH',
 'PRIVATE_DIRECTORY_MODE',
 'PRIVATE_FILE_SAFETY',
 'PRIVATE_PATH_REQUIRED',
 'READ_ONLY_DML_DDL_ZERO',
 'READ_ONLY_ROLLBACK_CONFIRMED',
 'SCALAR_NUMBER',
 'SCHEMA_FORMAT',
 'SETUP_PASSWORD',
 'SETUP_PREFIX',
 'SQL_EXCEPTION',
 'TEN_TABLES_CREATED',
 'TLS_CIPHER_REQUIRED',
 'UNEXPECTED_FAILURE',
 'UNRELATED_UNCHANGED',
 'UNREVIEWED_EXECUTION',
 'UNREVIEWED_METADATA',
 'V1_COUNTS',
 'V1_NULL_INVARIANTS',
 'VERIFY_RESULT',
 'VERIFY_ROWS_UNCHANGED',
 'WORK_BUDGET_OR_DEADLINE'))


def parse_tidb_only(text):
    """Interpret only the four approved TiDB values; other provider lines are ignored."""
    values = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key = line.split('=', 1)[0].strip()
        if key not in TIDB_KEYS:
            continue
        if '=' not in line or key in values:
            raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
        value = line.split('=', 1)[1].strip()
        if value[:1] in ('"', "'"):
            if len(value) < 2 or value[-1] != value[0]:
                raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
            value = value[1:-1]
        if (not value or any(ord(c) < 32 or ord(c) == 127 for c in value)
                or any(part in value for part in ('$(','`','${','<','>'))):
            raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
        values[key] = value
    if values.keys() != TIDB_KEYS:
        raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
    host = values['TIDB_HOST']
    if (len(host) > 253 or host != host.lower() or '.' not in host
            or not all(re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', part) for part in host.split('.'))
            or host.endswith(('.local', '.localhost', '.invalid'))
            or not re.fullmatch(r'[0-9]{1,5}', values['TIDB_PORT'])
            or not 1 <= int(values['TIDB_PORT']) <= 65535):
        raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
    try:
        ipaddress.ip_address(host)
    except ValueError:
        return values
    raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')


def read_tidb_inputs(path):
    """No links, 700/600 owned input, stable inode; never parse Redis values."""
    path = Path(path).absolute()
    directory = opened = None
    try:
        if '..' in path.parts:
            raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
        directory = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        for part in path.parts[1:-1]:
            next_directory = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory); directory = next_directory
        parent = os.fstat(directory)
        if parent.st_uid != os.getuid() or stat.S_IMODE(parent.st_mode) != 0o700:
            raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
        opened = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        before = os.fstat(opened)
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid() or before.st_nlink != 1
                or stat.S_IMODE(before.st_mode) != 0o600 or before.st_size > 16384):
            raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
        with os.fdopen(opened, 'rb', closefd=False) as stream:
            raw = stream.read(16385)
        after = os.fstat(opened)
        current = os.stat(path.name, dir_fd=directory, follow_symlinks=False)
        fields = ('st_dev', 'st_ino', 'st_size', 'st_mtime_ns', 'st_ctime_ns')
        if (len(raw) > 16384 or any(getattr(before, key) != getattr(after, key) for key in fields)
                or (after.st_dev, after.st_ino) != (current.st_dev, current.st_ino)):
            raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
        return parse_tidb_only(raw.decode('utf-8')), hashlib.sha256(raw).hexdigest()
    except (OSError, UnicodeError):
        raise ValueError('INPUT_UNSAFE_OR_UNREADABLE') from None
    finally:
        if opened is not None:
            os.close(opened)
        if directory is not None:
            os.close(directory)


def source_manifest(directory=None):
    directory = ROOT if directory is None else directory
    result = {}
    for relative in inputs():
        if relative.parts[0] not in ('be', 'scripts'):
            continue
        path = directory / relative
        if path.is_symlink() or not path.is_file():
            raise ValueError('SOURCE_CHANGED')
        result[str(relative)] = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                                 'mode': stat.S_IMODE(path.stat().st_mode)}
    return result


def write_cleanup_config(path, settings):
    # Generated password is hex; every identifier is separately validated before delivery.
    url = ('jdbc:mysql://' + settings['TIDB_HOST'] + ':' + settings['TIDB_PORT'] + '/' + settings['schema']
           + '?sslMode=VERIFY_IDENTITY&connectTimeout=5000&socketTimeout=10000'
           + '&readOnlyPropagatesToServer=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true')
    value = ('DB_URL=' + url + '\nDB_USERNAME=' + settings['cleanupAccount']
             + '\nDB_PASSWORD=' + settings['cleanupPassword'] + '\nDEMO_MAX_VISITORS=1000\n')
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        stream.write(value); stream.flush(); os.fsync(stream.fileno())


def package_classpath(work, original):
    jars = list((work / 'build/libs').glob('*-demo-cleanup.jar'))
    if len(jars) != 1:
        raise ValueError('PACKAGE_CONTRACT')
    destination = work / 'packaged/classes'
    destination.mkdir(parents=True, exist_ok=False)
    expected = {}
    for source in ('main', 'maintenance'):
        for path in (work / ('build/classes/java/' + source)).rglob('*.class'):
            relative = str(path.relative_to(work / ('build/classes/java/' + source)))
            if relative in expected:
                raise ValueError('PACKAGE_CONTRACT')
            expected[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    actual = {}; total = 0
    try:
        with zipfile.ZipFile(jars[0]) as archive:
            seen = set()
            for entry in archive.infolist():
                if not entry.filename.startswith('BOOT-INF/classes/') or entry.is_dir():
                    continue
                relative = Path(entry.filename[len('BOOT-INF/classes/'):])
                if (relative.is_absolute() or '..' in relative.parts or str(relative) in seen
                        or stat.S_ISLNK(entry.external_attr >> 16) or entry.file_size > 16 * 1024 * 1024):
                    raise ValueError('PACKAGE_CONTRACT')
                total += entry.file_size
                if total > 32 * 1024 * 1024:
                    raise ValueError('PACKAGE_CONTRACT')
                seen.add(str(relative))
                value = archive.read(entry)
                target = destination / relative
                target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(value)
                if target.suffix == '.class':
                    actual[str(relative)] = hashlib.sha256(value).hexdigest()
    except zipfile.BadZipFile:
        raise ValueError('PACKAGE_CONTRACT') from None
    if not expected or actual != expected:
        raise ValueError('PACKAGE_CONTRACT')
    private_json(work / 'packaging-proof.json', {'jarChecksum': hashlib.sha256(jars[0].read_bytes()).hexdigest(),
                 'packagedClasses': actual, 'classCount': len(actual)})
    runtime = []
    for entry in original.split(os.pathsep):
        path = Path(entry)
        if (path.is_file() and path.suffix == '.jar'
                or path == work / 'build/classes/java/test' or path == work / 'build/resources/test'):
            runtime.append(entry)
    if str(work / 'build/classes/java/test') not in runtime:
        raise ValueError('PACKAGE_CONTRACT')
    return os.pathsep.join((str(destination), *runtime))


def new_state(path):
    path = Path(path).absolute()
    if (ROOT == path or ROOT in path.parents or '..' in path.parts
            or any(name in path.parts for name in OLD_STATE_NAMES)
            or any(part.startswith('moneytoad-deploy-') for part in path.parts[:-1])
            or any(part.startswith(('moneytoad-deploy03-', 'moneytoad-deploy-capacity-',
                                   'moneytoad-deploy-admission-lock-')) for part in path.parts)
            or not re.fullmatch(r'moneytoad-deploy-cleanup-tidb-[a-zA-Z0-9_-]{1,64}', path.name)):
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
    if (WORK_LIMIT != 1200 or CLEANUP_LIMIT != 300 or TOTAL_LIMIT != 1500
            or WORK_LIMIT + CLEANUP_LIMIT != TOTAL_LIMIT or CONNECTION_LIMIT != 2
            or any(type(value) is not int or value <= 0 for value in STATIC_WORK_BOUNDS.values())
            or sum(STATIC_WORK_BOUNDS.values()) != WORK_LIMIT):
        raise ValueError('STATIC_BUDGET_REJECTED')
    return {'work_components': dict(STATIC_WORK_BOUNDS), 'work_upper_bound': WORK_LIMIT,
            'cleanup_reserved_before_launch': CLEANUP_LIMIT, 'total_reserved_upper_bound': TOTAL_LIMIT,
            'planned_physical_connections': 2, 'authorized_connection_limit': CONNECTION_LIMIT,
            'provider_billing_measurement': False}


def reserve_once(state, input_digest, identities, source_manifest, package_checksum):
    plan = static_plan()
    # O_EXCL plus a never-reused directory prevents recovery by resetting state.
    private_json(state / 'execution-marker.json', {
        'operation': 'TIDB_CLEANUP_REMOTE_ONCE', 'inputDigest': input_digest,
        'sourceManifest': source_manifest, 'packageChecksum': package_checksum,
        'reservedCommands': TOTAL_LIMIT, 'workLimit': WORK_LIMIT, 'cleanupLimit': CLEANUP_LIMIT,
        'connectionLimit': CONNECTION_LIMIT, 'schemaLimit': 1, 'runtimeAccountLimit': 0,
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


def project_mode(mode, observed):
    booleans = {'stateObserved', 'completed', 'readOnly', 'autoCommit'}
    integers = {'serverReadOnlyStatements', 'delegateDml', 'delegateDdl', 'driverDml',
                'commits', 'rollbacks', 'unsupported1235', 'guardViolations'}
    required = {'stateObserved', 'completed', 'serverReadOnlyStatements', 'delegateDml',
                'delegateDdl', 'unsupported1235', 'guardViolations'}
    if (not isinstance(observed, dict) or not required <= observed.keys()
            or observed.keys() - booleans - integers - {'isolation'}
            or any(type(observed[key]) is not bool for key in booleans & observed.keys())
            or any(type(observed[key]) is not int or observed[key] < 0 for key in integers & observed.keys())
            or 'isolation' in observed and observed['isolation'] not in ('REPEATABLE_READ', 'READ_COMMITTED', 'OTHER')):
        raise ValueError('RESULT_CONTRACT')
    if observed['stateObserved'] and not {'readOnly', 'autoCommit', 'isolation'} <= observed.keys():
        raise ValueError('RESULT_CONTRACT')
    if observed['completed']:
        if (not (booleans | integers | {'isolation'}) <= observed.keys()
                or not observed['stateObserved'] or observed['autoCommit']
                or any(observed[key] != 0 for key in ('serverReadOnlyStatements', 'delegateDdl', 'unsupported1235', 'guardViolations'))):
            raise ValueError('RESULT_CONTRACT')
        expected = ({'readOnly': False, 'isolation': 'READ_COMMITTED', 'delegateDml': 5,
                     'driverDml': 5, 'commits': 1, 'rollbacks': 0} if mode == 'APPLY' else
                    {'readOnly': True, 'isolation': 'REPEATABLE_READ', 'delegateDml': 0,
                     'driverDml': 0, 'commits': 0, 'rollbacks': 1})
        if any(observed[key] != value for key, value in expected.items()):
            raise ValueError('RESULT_CONTRACT')
    return observed


def project_result(result):
    required = {'status', 'checks', *RESULT_INTEGERS, *RESULT_BOOLEANS}
    if (not isinstance(result, dict) or not required <= result.keys()
            or result.keys() - required - RESULT_OPTIONAL
            or result['status'] not in ('PASS', 'FAIL', 'BLOCKED', 'INTERRUPTED')):
        raise ValueError('RESULT_CONTRACT')
    if (any(type(result[key]) is not bool for key in RESULT_BOOLEANS)
            or any(type(result[key]) is not int or result[key] < 0 for key in RESULT_INTEGERS)
            or result['connectionAttempts'] > CONNECTION_LIMIT
            or result['workCommands'] > WORK_LIMIT or result['cleanupCommands'] > CLEANUP_LIMIT):
        raise ValueError('RESULT_CONTRACT')
    if ('failedPhase' in result and result['failedPhase'] not in PHASES
            or 'failureCode' in result and result['failureCode'] not in FAILURE_CODES):
        raise ValueError('RESULT_CONTRACT')
    if any(type(result[key]) is not bool for key in ('ledgerFailure', 'cleanupFailure') if key in result):
        raise ValueError('RESULT_CONTRACT')
    if 'databaseVersion' in result and (not isinstance(result['databaseVersion'], str)
            or not re.fullmatch(r'[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}-TiDB-v[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}', result['databaseVersion'])):
        raise ValueError('RESULT_CONTRACT')
    if 'failureVendor' in result and (type(result['failureVendor']) is not int or result['failureVendor'] < 0):
        raise ValueError('RESULT_CONTRACT')
    if 'failureSqlState' in result and (not isinstance(result['failureSqlState'], str)
            or result['failureSqlState'] != 'NOT_OBSERVED' and not re.fullmatch(r'[A-Z0-9]{5}', result['failureSqlState'])):
        raise ValueError('RESULT_CONTRACT')
    if 'confirmedReuseResets' in result and (type(result['confirmedReuseResets']) is not int
            or not 0 <= result['confirmedReuseResets'] <= 2):
        raise ValueError('RESULT_CONTRACT')
    modes = result.get('modeObservations', {})
    if not isinstance(modes, dict) or modes.keys() - {'VERIFY', 'DRY_RUN', 'APPLY'}:
        raise ValueError('RESULT_CONTRACT')
    for mode, observed in modes.items():
        project_mode(mode, observed)
    for mode, key in (('VERIFY', 'verifyVerified'), ('DRY_RUN', 'dryRunVerified'), ('APPLY', 'applyVerified')):
        if result[key] and not modes.get(mode, {}).get('completed', False):
            raise ValueError('RESULT_CONTRACT')
    checks = result['checks']
    if (not isinstance(checks, dict) or checks.keys() - CHECK_NAMES
            or any(type(value) is not bool for value in checks.values())):
        raise ValueError('RESULT_CONTRACT')
    if result['status'] == 'PASS' and ('databaseVersion' not in result
            or result.get('confirmedReuseResets') != 2
            or not all(result[key] for key in RESULT_BOOLEANS)
            or checks.keys() != CHECK_NAMES or not all(checks.values())
            or any(result.get(key, False) for key in ('ledgerFailure', 'cleanupFailure'))):
        raise ValueError('RESULT_CONTRACT')
    if forbidden(result):
        raise ValueError('RESULT_CONTRACT')
    return result


def synthetic_budget_proof(text):
    proof = {}
    prefix = 'CLEANUP_SYNTHETIC_PROOF '
    for line in text.splitlines():
        if not line.startswith('CLEANUP_SYNTHETIC_'):
            continue
        if not line.startswith(prefix):
            raise ValueError('COMPILE_FAILED')
        try:
            item = json.loads(line[len(prefix):])
        except (ValueError, TypeError):
            raise ValueError('COMPILE_FAILED') from None
        if not isinstance(item, dict) or item.get('kind') not in ('NORMAL','EARLY_FAILURE','INTERRUPT','FINALLY'):
            raise ValueError('COMPILE_FAILED')
        key = item['kind'].lower()
        expected = ({'kind','work','cleanup','work_positions','cleanup_positions','connections','metadata_calls'}
                    if key == 'normal' else {'kind','positions','max_work','max_cleanup','max_connections'})
        if (item.keys() != expected or key in proof
                or any(type(value) is not int or value < 0 for field, value in item.items() if field != 'kind')):
            raise ValueError('COMPILE_FAILED')
        clean = ({field: value for field,value in item.items() if field!='kind'} if key=='normal' else
                 {'positions':item['positions'],'work':item['max_work'],
                  'cleanup':item['max_cleanup'],'connections':item['max_connections']})
        if clean['work'] > WORK_LIMIT or clean['cleanup'] > CLEANUP_LIMIT or clean['connections'] > CONNECTION_LIMIT:
            raise ValueError('COMPILE_FAILED')
        proof[key] = clean
    if proof.keys() != {'normal', 'early_failure', 'interrupt', 'finally'}:
        raise ValueError('COMPILE_FAILED')
    regular = proof['normal']
    if (regular['connections'] != 2 or regular['metadata_calls'] != 40
            or not 1 <= regular['work_positions'] <= regular['work']
            or not 1 <= regular['cleanup_positions'] <= regular['cleanup']
            or any(proof[key]['positions'] != regular['work_positions'] for key in ('early_failure', 'interrupt'))
            or proof['finally']['positions'] != regular['cleanup_positions']):
        raise ValueError('COMPILE_FAILED')
    return proof


def local_test_results(directory):
    counts = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    proofs = set()
    proof_output = []
    suites = sorted(directory.glob('TEST-*.xml'))
    if not suites:
        raise ValueError('COMPILE_FAILED')
    for report in suites:
        try:
            suite = ET.parse(report).getroot()
        except (ET.ParseError, OSError):
            raise ValueError('COMPILE_FAILED') from None
        if not suite.attrib.get('name', '').startswith('com.potg.verification.cleanupremote.'):
            raise ValueError('COMPILE_FAILED')
        if suite.attrib.get('name') == 'com.potg.verification.cleanupremote.CleanupTiDbProbeSafetyTest':
            proof_output.extend(element.text or '' for element in suite.findall('.//system-out'))
            proofs.update(case.attrib.get('name', '').removesuffix('()')
                          for case in suite.findall('testcase')
                          if not any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')))
        for key in counts:
            counts[key] += int(suite.attrib.get(key, '0'))
    if (counts['tests'] < len(REQUIRED_BUDGET_PROOFS)
            or not REQUIRED_BUDGET_PROOFS <= proofs
            or any(counts[key] for key in ('failures', 'errors', 'skipped'))):
        raise ValueError('COMPILE_FAILED')
    counts['budget_proof'] = synthetic_budget_proof('\n'.join(proof_output))
    counts['budget_proofs_passed'] = True
    return counts


def compile_probe(work, cache):
    source = work / 'snapshot'
    before = source_manifest()
    for relative in inputs():
        target = source / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / relative, target)
    if before != source_manifest() or before != source_manifest(source):
        raise ValueError('SOURCE_CHANGED')
    private_json(work / 'source-manifest.json', before)
    for part in ('wrapper', 'caches'):
        if not (cache / part).is_dir():
            raise ValueError('EXPLICIT_CACHE_REQUIRED')
        shutil.copytree(cache / part, work / 'cache' / part, ignore=shutil.ignore_patterns('*.lock', '*.lck'))
    java = subprocess.run(['/usr/libexec/java_home', '-v', '21'], capture_output=True,
                          text=True, check=True).stdout.strip()
    env = {'PATH': java + '/bin:/usr/bin:/bin:/usr/sbin:/sbin', 'JAVA_HOME': java,
           'GRADLE_USER_HOME': str(work / 'cache'), 'LANG': 'en_US.UTF-8', 'TMPDIR': str(work),
           'CLEANUP_BUILD': str(work / 'build'), 'CLEANUP_CLASSPATH': str(work / 'classpath')}
    init = work / 'init.gradle'
    init.write_text("""allprojects {
 layout.buildDirectory.set(file(System.getenv('CLEANUP_BUILD')))
 afterEvaluate {
  tasks.register('cleanupProbeClasspath') {
   dependsOn 'testClasses', 'maintenanceClasses'
   doLast { file(System.getenv('CLEANUP_CLASSPATH')).text = sourceSets.test.runtimeClasspath.asPath }
  }
 }
}
""")
    command = ['bash', './gradlew', '--offline', '--no-daemon', '--console=plain', '--max-workers=2',
               '--project-cache-dir', str(work / 'project-cache'), '--init-script', str(init),
               'compileJava', 'compileMaintenanceJava', 'compileTestJava', 'test',
               '--tests', 'com.potg.verification.cleanupremote.*Test', 'demoCleanupJar', 'cleanupProbeClasspath']
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
    classpath = package_classpath(work, (work / 'classpath').read_text())
    if before != source_manifest():
        raise ValueError('SOURCE_CHANGED')
    return Path(java), env, classpath, source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--compile-only', action='store_true')
    mode.add_argument('--execute', action='store_true')
    parser.add_argument('--cache-seed', type=Path, required=True)
    parser.add_argument('--state-dir', type=Path, help='New private moneytoad-deploy-cleanup-tidb-* directory; never reuse')
    args = parser.parse_args()
    if args.execute and args.state_dir is None:
        parser.error('--execute requires --state-dir')
    os.umask(0o077)
    result = {'status': 'BLOCKED', 'phase': 'TIDB_CLEANUP_REMOTE', 'input_status': 'NOT_CHECKED',
              'provider_process_launched': False, 'old_provider_state_accessed': False,
              'upstash_connections': 0, 'redis_commands': 0, 'http_logins': 0,
              'reserved_command_equivalents': 0, 'budget_limit': TOTAL_LIMIT,
              'work_limit': WORK_LIMIT, 'cleanup_reserved': 0, 'connection_limit': CONNECTION_LIMIT,
              'public_deployment_ready': False, 'remote_demo_capacity_verified': False,
              'remote_demo_cleanup_verify_verified': False, 'remote_demo_cleanup_dry_run_verified': False,
              'remote_demo_cleanup_apply_verified': False, 'remote_demo_cleanup_verified': False,
              'packaged_service_verified': False, 'source_unchanged': False,
              'remote_commit_ambiguity': 'REMOTE_NOT_EXERCISED',
              'remote_http_redis_fe_and_browser': 'REMOTE_NOT_EXERCISED',
              'remote_unsafe_financial_job_fixture': 'REMOTE_NOT_EXERCISED',
              'remote_second_visitor': 'REMOTE_NOT_EXERCISED',
              'local_diagnostics_retained': False}
    work = state = config = cleanup_config = ledger = summary = process = None
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
        work = Path(tempfile.mkdtemp(prefix='moneytoad-cleanup-compile-')).resolve()
        java, env, classpath, source = compile_probe(work, args.cache_seed.resolve())
        result['compilation'] = 'PASS'
        result['packaged_classes_match_build'] = True
        result['local_java_tests'] = json.loads((work / 'local-tests.json').read_text())
        if args.compile_only:
            result.update(status='PREPARED', remote_execution='NOT_PERFORMED')
        else:
            values, current_digest = read_tidb_inputs(input_path)
            if baseline_digest != current_digest:
                raise ValueError('INPUT_CHANGED')
            pinned_sources = private_document(work / 'source-manifest.json', maximum=262144)
            if pinned_sources != source_manifest():
                raise ValueError('SOURCE_CHANGED')
            identities = {'schema': 'mtcleanup' + uuid.uuid4().hex[:16],
                'cleanupAccount': account_name(values['TIDB_SETUP_USERNAME'], secrets.token_hex(7))}
            state = new_state(args.state_dir)
            config, ledger, summary = (state / name for name in ('config.json', 'ownership.json', 'result.json'))
            cleanup_config = state / 'cleanup.properties'
            private_json(ledger, {**identities, 'cleanupComplete': False})
            private_json(summary, {})
            settings = {**values, **identities, 'cleanupPassword': secrets.token_hex(32),
                'cleanupConfigPath': str(cleanup_config),
                'maintenanceClassesPath': str(work / 'packaged/classes'),
                'baselinePath': str(source / 'scripts/verification/fixtures/managed-provider-schema.sql'),
                'migrationPath': str(source / 'be/src/main/resources/db/demo/V001__demo_admission.sql'),
                'lockMigrationPath': str(source / 'be/src/main/resources/db/demo/V002__demo_admission_lock.sql'),
                'deadlineEpochMillis': int((time.time() + 600) * 1000),
                'workLimit': WORK_LIMIT, 'cleanupLimit': CLEANUP_LIMIT, 'connectionLimit': CONNECTION_LIMIT}
            private_json(config, settings)
            write_cleanup_config(cleanup_config, settings)
            package = private_document(work / 'packaging-proof.json')
            result.update(reserved_command_equivalents=TOTAL_LIMIT, cleanup_reserved=CLEANUP_LIMIT)
            reserve_once(state, baseline_digest, identities, pinned_sources, package['jarChecksum'])
            values.clear(); settings.clear()
            env.update(CLEANUP_CHECK_CONFIG=str(config), CLEANUP_CHECK_RESULT=str(summary),
                       CLEANUP_CHECK_LEDGER=str(ledger))
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
                for short, field in (('verify', 'verifyVerified'), ('dry_run', 'dryRunVerified'), ('apply', 'applyVerified')):
                    result['remote_demo_cleanup_' + short + '_verified'] = observation[field]
                result['packaged_service_verified'] = observation['packagedServiceVerified']
                result['remote_demo_cleanup_verified'] = passed
                # Admission/schema/privilege evidence is reviewed separately by the task report.
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
                    result.update(status='FAIL', reason='INPUT_CHANGED', remote_demo_capacity_verified=False, remote_demo_cleanup_verified=False)
            if work is not None and (work / 'source-manifest.json').exists():
                result['source_unchanged'] = private_document(work / 'source-manifest.json', maximum=262144) == source_manifest()
                if not result['source_unchanged']:
                    result.update(status='FAIL', reason='SOURCE_CHANGED', remote_demo_capacity_verified=False, remote_demo_cleanup_verified=False)
            if safe_cleanup and child_stopped:
                if config is not None:
                    config.unlink(missing_ok=True)
                if cleanup_config is not None:
                    cleanup_config.unlink(missing_ok=True)
                if work is not None:
                    if result.get('compilation') == 'PASS':
                        shutil.rmtree(work)
                    else:
                        result['local_diagnostics_retained'] = True
        except (OSError, ValueError):
            safe_cleanup = False
        local_removed = (work is None or not work.exists()) and all(
            path is None or not path.exists() for path in (config, cleanup_config))
        if result['provider_process_launched'] and not local_removed:
            safe_cleanup = False
        result.update(owned_local_resources_removed=local_removed,
                      cleanup_complete=safe_cleanup and child_stopped,
                      child_stopped=child_stopped, forced_child_termination=not graceful,
                      remaining_command_equivalents=TOTAL_LIMIT - result['reserved_command_equivalents'])
        if not result['cleanup_complete']:
            result.update(status='FAIL', remote_demo_capacity_verified=False, remote_demo_cleanup_verified=False)
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
