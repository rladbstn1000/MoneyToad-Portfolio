#!/usr/bin/env python3
"""Finite provider probe launcher; credentials only from the approved external file.

Use the SAME private state directory for retries in one task. Budgets are never
reset. Compilation uses a private current-tree copy, offline Gradle and Java21.
No existing full-suite runner is connected to a managed service.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import time
import uuid

from managed_provider_preflight import InputRejected, MAX_BYTES, parse_values, read_inputs
from public_evidence import forbidden
from render_regression import ROOT, inputs

MAIN_CLASS = 'com.potg.verification.managed.ManagedProviderProbe'
OUTPUT = ROOT / 'docs/deployment/evidence/AUTONOMOUS_CONTINUATION'
RESUME_BASELINE = {'loginAttempts': 1, 'commandEquivalents': 9472}
RESUME_EXPECTED_COMMANDS = 6096
RESUME_EXPECTED_LOGINS = 4
RESUME_BUDGET_LIMIT = 15568
RESUME_MARKER = 'managed-port-corrected-resume-once.json'
REVISION_FILE = 'input-revision-port-correction.json'
SAFE_REASONS = frozenset(('STATE_MUST_BE_PRIVATE_OUTSIDE_REPOSITORY', 'STATE_SYMLINK_REJECTED',
    'STATE_OWNER_OR_MODE', 'BUDGET_OWNER_OR_MODE', 'BUDGET_FORMAT', 'WORKFLOW_BUDGET_EXHAUSTED',
    'PRIOR_CLEANUP_UNCONFIRMED', 'PRIVATE_FILE_OWNER_OR_MODE', 'EXPLICIT_CACHE_MISSING',
    'PROBE_COMPILATION_FAILED', 'EXISTING_TASK_STATE_REQUIRED', 'EXISTING_BUDGET_REQUIRED',
    'PROVIDER_RECOVERY_ALREADY_ATTEMPTED', 'AUTHORIZED_BASELINE_CHANGED', 'INPUT_REVISION_REQUIRED',
    'INPUT_REVISION_CONTRACT', 'INPUT_REVISION_CHANGED', 'INPUT_REVISION_MISMATCH',
    'INPUT_UNSAFE_OR_UNREADABLE', 'PROVIDER_RESULT_REJECTED', 'RESULT_OR_CLEANUP_UNCONFIRMED',
    'FINAL_BUDGET_UNCONFIRMED'))


def private_state(path):
    path = path.absolute()
    if '..' in path.parts or ROOT == path or ROOT in path.parents:
        raise ValueError('STATE_MUST_BE_PRIVATE_OUTSIDE_REPOSITORY')
    for part in (path, *path.parents):
        if part.is_symlink():
            raise ValueError('STATE_SYMLINK_REJECTED')
    if not path.exists():
        path.mkdir(mode=0o700)
    info = path.stat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
        raise ValueError('STATE_OWNER_OR_MODE')
    return path


def load_budget(state):
    path = state / 'budget.json'
    if not path.exists():
        path.write_text(json.dumps({'loginAttempts': 0, 'commandEquivalents': 0}))
        path.chmod(0o600)
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
            or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1):
        raise ValueError('BUDGET_OWNER_OR_MODE')
    budget = json.loads(path.read_text())
    if set(budget) != {'loginAttempts', 'commandEquivalents'}:
        raise ValueError('BUDGET_FORMAT')
    if any(type(budget[key]) is not int or budget[key] < 0 for key in budget):
        raise ValueError('BUDGET_FORMAT')
    if budget['loginAttempts'] >= 8 or budget['commandEquivalents'] >= 8000:
        raise ValueError('WORKFLOW_BUDGET_EXHAUSTED')
    # Incomplete ownership ledgers block additional writes, even with valid inputs.
    for ledger in state.glob('ledger-*.json'):
        checked_private_file(ledger)
        if json.loads(ledger.read_text()).get('cleanupComplete') is not True:
            raise ValueError('PRIOR_CLEANUP_UNCONFIRMED')
    return path


def checked_private_file(path):
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
            or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1):
        raise ValueError('PRIVATE_FILE_OWNER_OR_MODE')


def private_document(path, maximum=131072):
    checked_private_file(path)
    if path.stat().st_size > maximum:
        raise ValueError('PRIVATE_FILE_OWNER_OR_MODE')
    return json.loads(path.read_text())


def read_existing_budget(state):
    path = state / 'budget.json'
    if not path.exists():
        raise ValueError('EXISTING_BUDGET_REQUIRED')
    value = private_document(path, 2048)
    if (not isinstance(value, dict) or set(value) != set(RESUME_BASELINE)
            or any(type(item) is not int or item < 0 for item in value.values())):
        raise ValueError('BUDGET_FORMAT')
    return path, value


def resume_budget(state):
    """This one authorization never initializes, reserves, refunds, or resets."""
    if not state.is_dir():
        raise ValueError('EXISTING_TASK_STATE_REQUIRED')
    marker = state / RESUME_MARKER
    if marker.exists() or marker.is_symlink():
        raise ValueError('PROVIDER_RECOVERY_ALREADY_ATTEMPTED')
    path, value = read_existing_budget(state)
    if (value['commandEquivalents'] + RESUME_EXPECTED_COMMANDS > RESUME_BUDGET_LIMIT
            or value['loginAttempts'] + RESUME_EXPECTED_LOGINS > 8):
        raise ValueError('WORKFLOW_BUDGET_EXHAUSTED')
    if value != RESUME_BASELINE:
        raise ValueError('AUTHORIZED_BASELINE_CHANGED')
    for ledger in state.glob('ledger-*.json'):
        previous = private_document(ledger)
        if not isinstance(previous, dict) or previous.get('cleanupComplete') is not True:
            raise ValueError('PRIOR_CLEANUP_UNCONFIRMED')
    return path


def load_port_revision(state):
    # Import after this module is initialized: the existing Redis helper imports
    # its private-file/lock helpers from here. Its five-field return is not used.
    from redis_port_correction import load_revision
    return load_revision(state)


def read_revision_inputs(path, revision):
    """Parse all nine fields from the same opened bytes matched to the revision."""
    path = Path(path).absolute()
    directory = opened = None
    try:
        if '..' in path.parts:
            raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
        directory = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        for part in path.parts[1:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory)
            directory = child
        parent = os.fstat(directory)
        if parent.st_uid != os.getuid() or stat.S_IMODE(parent.st_mode) != 0o700:
            raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
        opened = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        before = os.fstat(opened)
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid() or before.st_nlink != 1
                or stat.S_IMODE(before.st_mode) != 0o600 or before.st_size > MAX_BYTES):
            raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
        with os.fdopen(opened, 'rb', closefd=False) as stream:
            raw = stream.read(MAX_BYTES + 1)
        after = os.fstat(opened)
        current = os.stat(path.name, dir_fd=directory, follow_symlinks=False)
        if (len(raw) > MAX_BYTES or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
                != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
                or (current.st_dev, current.st_ino) != (after.st_dev, after.st_ino)):
            raise ValueError('INPUT_UNSAFE_OR_UNREADABLE')
        if hashlib.sha256(raw).hexdigest() != revision['sha256After']:
            raise ValueError('INPUT_REVISION_MISMATCH')
        return parse_values(raw.decode('utf-8')).values
    except (OSError, UnicodeError):
        raise ValueError('INPUT_UNSAFE_OR_UNREADABLE') from None
    finally:
        if opened is not None:
            os.close(opened)
        if directory is not None:
            os.close(directory)


def private_json(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        json.dump(value, output)
        output.flush()
        os.fsync(output.fileno())


def mark_resume_once(state):
    # The caller holds run.lock. This permanent launch marker is separate from
    # resource ownership; even a failed launch cannot reuse this authorization.
    private_json(state / RESUME_MARKER, {
        'operation': 'MANAGED_PORT_CORRECTED_RECOVERY',
        'input_revision_reference': 'REDIS_PORT_CORRECTION',
        'baseline': RESUME_BASELINE, 'expected_command_equivalents': RESUME_EXPECTED_COMMANDS,
        'expected_login_attempts': RESUME_EXPECTED_LOGINS, 'verification_budget_limit': RESUME_BUDGET_LIMIT})
    directory = os.open(state, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def safe_failure(failure):
    if isinstance(failure, ValueError) and str(failure) in SAFE_REASONS:
        return str(failure)
    if isinstance(failure, subprocess.TimeoutExpired):
        return 'CHILD_DEADLINE'
    return 'OS_ERROR' if isinstance(failure, OSError) else 'PROBE_EXECUTION_REJECTED'


def project_observation(observed):
    if (not isinstance(observed, dict) or observed.get('status') not in ('PASS', 'FAIL', 'BLOCKED', 'INTERRUPTED')
            or type(observed.get('cleanup_complete')) is not bool
            or observed.get('failover_revocation_guarantee') != 'NOT_ESTABLISHED'
            or observed.get('public_deployment_ready') is not False or forbidden(observed)):
        raise ValueError('PROVIDER_RESULT_REJECTED')
    return observed


def acquire_lock(state):
    descriptor = os.open(state / 'run.lock', os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    lock = os.fdopen(descriptor, 'a')
    try:
        checked_private_file(state / 'run.lock')
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return lock
    except BaseException:
        lock.close()
        raise


def free_port():
    with socket.socket() as server:
        server.bind(('127.0.0.1', 0))
        return server.getsockname()[1]


def compile_probe(work, cache):
    source = work / 'snapshot'
    for relative in inputs():
        target = source / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / relative, target)
    for part in ('wrapper', 'caches'):
        if not (cache / part).is_dir():
            raise ValueError('EXPLICIT_CACHE_MISSING')
        shutil.copytree(cache / part, work / 'cache' / part, ignore=shutil.ignore_patterns('*.lock', '*.lck'))
    java = subprocess.run(['/usr/libexec/java_home', '-v', '21'], capture_output=True, text=True, check=True).stdout.strip()
    env = {'PATH': java + '/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin',
           'JAVA_HOME': java, 'GRADLE_USER_HOME': str(work / 'cache'), 'LANG': 'en_US.UTF-8',
           'TMPDIR': str(work), 'MANAGED_BUILD': str(work / 'build'),
           'MANAGED_CLASSPATH': str(work / 'classpath')}
    init = work / 'init.gradle'
    init.write_text("""allprojects {
 layout.buildDirectory.set(file(System.getenv('MANAGED_BUILD')))
 afterEvaluate {
  tasks.register('managedProbeClasspath') {
   dependsOn 'testClasses'
   doLast { file(System.getenv('MANAGED_CLASSPATH')).text = sourceSets.test.runtimeClasspath.asPath }
  }
 }
}
""")
    command = ['bash', './gradlew', '--offline', '--no-daemon', '--console=plain', '--max-workers=2',
               '--project-cache-dir', str(work / 'project-cache'), '--init-script', str(init),
               'compileJava', 'compileTestJava', 'managedProbeClasspath']
    with (work / 'compile-private.txt').open('w') as log:
        process = subprocess.Popen(command, cwd=source / 'be', env=env, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout=420)
        except BaseException:
            stop(process)
            raise
    if code:
        raise ValueError('PROBE_COMPILATION_FAILED')
    return Path(java), env, (work / 'classpath').read_text(), source


def stop(process):
    if process is None or process.poll() is not None:
        return True
    process.terminate()
    try:
        process.wait(timeout=240)
        return True
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=15)
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--execute', action='store_true')
    mode.add_argument('--compile-only', action='store_true')
    parser.add_argument('--resume-port-corrected', action='store_true',
                        help='One approved full recovery from 9472 commands/login 1, corrected input revision, ceiling 15568')
    parser.add_argument('--cache-seed', type=Path, required=True)
    parser.add_argument('--state-dir', type=Path, required=True,
                        help='Owned 700 directory outside the repository; reuse for this task, never reset budget')
    args = parser.parse_args()
    if args.resume_port_corrected and not args.execute:
        parser.error('--resume-port-corrected requires --execute')
    os.umask(0o077)
    run = uuid.uuid4().hex[:16]
    result = {'status': 'BLOCKED', 'input_status': 'NOT_CHECKED',
              'provider_input_ready': False, 'provider_process_launched': False,
              'tidb_contracts_verified': False, 'upstash_functional_contracts_verified': False,
              'login_recovery_verified': False,
              'upstash_failover_revocation_guarantee': 'NOT_ESTABLISHED', 'provider_selection': 'HOLD',
              'managed_provider_contracts_verified': False, 'public_deployment_ready': False}
    work = None
    process = None
    lock = None
    config = ledger = summary = state = budget = None
    safe_cleanup = True
    child_stopped = True
    forced_termination = False
    launching = False
    pending_interrupt = False
    previous = {}
    try:
        if args.resume_port_corrected and not args.state_dir.is_dir():
            raise ValueError('EXISTING_TASK_STATE_REQUIRED')
        state = private_state(args.state_dir)
        lock = acquire_lock(state)
        approved_input = Path.home() / '.config/moneytoad/provider-check.env'
        if args.resume_port_corrected:
            budget = resume_budget(state)
            revision = load_port_revision(state)
            values = read_revision_inputs(approved_input, revision)
            result.update(input_revision_reference='REDIS_PORT_CORRECTION', input_revision_verified=True,
                          expected_command_equivalents_this_run=RESUME_EXPECTED_COMMANDS,
                          expected_login_attempts_this_run=RESUME_EXPECTED_LOGINS,
                          verification_budget_limit=RESUME_BUDGET_LIMIT)
        else:
            budget = load_budget(state)
            values = read_inputs(approved_input).values if args.execute else None
        if args.execute:
            result.update(input_status='PASS', provider_input_ready=True)
        def interrupted(signum, frame):
            nonlocal pending_interrupt
            if launching:
                pending_interrupt = True
                return
            raise KeyboardInterrupt()
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous[signum] = signal.signal(signum, interrupted)
        work = Path(tempfile.mkdtemp(prefix='moneytoad-provider-execution-')).resolve()
        java, env, classpath, source = compile_probe(work, args.cache_seed.resolve())
        result['probe_compilation'] = 'PASS'
        if args.compile_only:
            result.update(status='PREPARED', remote_execution='NOT_PERFORMED')
        else:
            if args.resume_port_corrected:
                if load_port_revision(state) != revision:
                    raise ValueError('INPUT_REVISION_CHANGED')
                values = read_revision_inputs(approved_input, revision)
                budget = resume_budget(state)
            config = state / ('config-' + run + '.json')
            ledger = state / ('ledger-' + run + '.json')
            summary = state / ('result-' + run + '.json')
            for path, initial in ((ledger, {'cleanupComplete': False}), (summary, {})):
                private_json(path, initial)
            settings = {**values, 'mode': 'managed', 'schema': 'moneytoad_contract_' + run,
                        'ddlPath': str(source / 'scripts/verification/fixtures/managed-provider-schema.sql'),
                        'appPort': free_port(), 'deadlineEpochMillis': int((time.time() + 1200) * 1000),
                        'verificationBudgetLimit': RESUME_BUDGET_LIMIT if args.resume_port_corrected else 10000}
            private_json(config, settings)
            values.clear()
            settings.clear()
            env.update(MANAGED_CHECK_CONFIG=str(config), MANAGED_CHECK_RESULT=str(summary),
                       MANAGED_CHECK_LEDGER=str(ledger), MANAGED_CHECK_BUDGET=str(budget))
            if args.resume_port_corrected:
                mark_resume_once(state)
            # No raw output is retained or forwarded: Java publishes only allowlisted results.
            # Defer Python handlers through tracking; an OS-blocked mask would be
            # inherited by the Java child and prevent its shutdown cleanup.
            launching = True
            try:
                process = subprocess.Popen([str(java / 'bin/java'), '-cp', classpath, MAIN_CLASS],
                                           cwd=source / 'be', env=env, stdout=subprocess.DEVNULL,
                                           stderr=subprocess.DEVNULL, start_new_session=True)
                result['provider_process_launched'] = True
                safe_cleanup = False
                child_stopped = False
            finally:
                launching = False
            if pending_interrupt:
                raise KeyboardInterrupt()
            process.wait(timeout=1440)
    except InputRejected:
        result.update(status='INPUT_REQUIRED', input_status='BLOCKED', reason='INPUT_CHECK_FAILED')
    except KeyboardInterrupt:
        result.update(status='INTERRUPTED', reason='INTERRUPTED')
    except (ValueError, OSError, subprocess.SubprocessError) as failure:
        result.update(status='BLOCKED', reason=safe_failure(failure))
    finally:
        for signum in previous:
            signal.signal(signum, signal.SIG_IGN)
        try:
            forced_termination = not stop(process)
            child_stopped = process is None or process.poll() is not None
        except (OSError, subprocess.SubprocessError):
            child_stopped = False
        if result['provider_process_launched']:
            safe_cleanup = False
            try:
                observed = project_observation(private_document(summary))
                owned = private_document(ledger)
                result['observation'] = observed
                safe_cleanup = (child_stopped and not forced_termination and observed['cleanup_complete'] is True
                                and isinstance(owned, dict) and owned.get('cleanupComplete') is True)
                if result['status'] != 'INTERRUPTED':
                    result['status'] = ('PASS' if not result.get('reason') and process.returncode == 0
                                        and observed['status'] == 'PASS' and safe_cleanup else 'FAIL')
                for key in ('tidb_contracts_verified', 'upstash_functional_contracts_verified', 'login_recovery_verified'):
                    result[key] = observed.get(key) is True
                result['managed_provider_contracts_verified'] = (result['status'] == 'PASS' and safe_cleanup
                    and result['tidb_contracts_verified'] and result['upstash_functional_contracts_verified'])
            except (OSError, ValueError):
                if result['status'] != 'INTERRUPTED':
                    result['status'] = 'FAIL'
                result['reason'] = 'RESULT_OR_CLEANUP_UNCONFIRMED'
        elif ledger is not None and ledger.exists():
            # No provider child exists, so close only this unused ownership file.
            # A recovery marker, if created, remains permanent even on launch failure.
            try:
                checked_private_file(ledger)
                with ledger.open('w') as output:
                    json.dump({'cleanupComplete': True}, output)
                    output.flush()
                    os.fsync(output.fileno())
            except (OSError, ValueError):
                safe_cleanup = False
        if args.resume_port_corrected and budget is not None:
            try:
                _, final_budget = read_existing_budget(state)
                if (not RESUME_BASELINE['commandEquivalents'] <= final_budget['commandEquivalents'] <= RESUME_BUDGET_LIMIT
                        or not RESUME_BASELINE['loginAttempts'] <= final_budget['loginAttempts'] <= 8):
                    raise ValueError('FINAL_BUDGET_UNCONFIRMED')
                result.update(reserved_command_equivalents=final_budget['commandEquivalents'],
                              remaining_command_equivalents=RESUME_BUDGET_LIMIT - final_budget['commandEquivalents'],
                              reserved_command_equivalents_this_run=final_budget['commandEquivalents'] - RESUME_BASELINE['commandEquivalents'],
                              reserved_login_attempts=final_budget['loginAttempts'],
                              new_login_attempts=final_budget['loginAttempts'] - RESUME_BASELINE['loginAttempts'])
            except (OSError, ValueError):
                result['managed_provider_contracts_verified'] = False
                if result['status'] != 'INTERRUPTED':
                    result['status'] = 'FAIL'
                result['reason'] = 'FINAL_BUDGET_UNCONFIRMED'
        # Unknown remote cleanup retains private config/ledger; never delete the recovery map.
        try:
            if safe_cleanup and child_stopped:
                if config is not None:
                    config.unlink(missing_ok=True)
                if work is not None:
                    shutil.rmtree(work)
        except OSError:
            safe_cleanup = False
        result['cleanup_complete'] = safe_cleanup and child_stopped
        result['child_stopped'] = child_stopped
        result['forced_child_termination'] = forced_termination
        if not result['cleanup_complete']:
            result['managed_provider_contracts_verified'] = False
            if result['status'] == 'PASS':
                result['status'] = 'FAIL'
        result['remote_cleanup'] = ('NOT_NEEDED_NO_PROVIDER_PROCESS' if not result['provider_process_launched']
                                    else 'CONFIRMED' if safe_cleanup else 'UNCONFIRMED_PRIVATE_LEDGER_RETAINED')
        if lock is not None:
            lock.close()
        if forbidden(result):
            raise ValueError('Public projection rejected')
        destination = OUTPUT / ('provider-' + run)
        destination.mkdir(parents=True, exist_ok=False)
        (destination / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({key: result[key] for key in ('status', 'input_status', 'provider_process_launched',
              'tidb_contracts_verified', 'upstash_functional_contracts_verified', 'cleanup_complete')}))
        for signum, handler in previous.items():
            signal.signal(signum, handler)
    return 0 if result['status'] in ('PASS', 'PREPARED') else 2


if __name__ == '__main__':
    raise SystemExit(main())
