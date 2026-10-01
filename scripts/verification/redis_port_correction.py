#!/usr/bin/env python3
"""One native TLS/PING run bound to the approved REDIS_PORT-only input revision.

Reuses the existing cumulative state. Reserves 64 commands once, creates no data
resources, and does not reset/refund any past reservation or mutate the input.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import tempfile
import uuid

from managed_provider_check import acquire_lock, checked_private_file, compile_probe, private_state, ROOT
from managed_provider_preflight import InputRejected, MAX_BYTES, parse_values
from redis_transport_comparison import (private_json, redis_inputs, safe_reason, finish_child,
    project_result as project_transport_result, OPERATION as TRANSPORT_OPERATION)
from public_evidence import forbidden

MAIN_CLASS = 'com.potg.verification.managed.RedisPortCorrectionProbe'
OPERATION = 'REDIS_PORT_CORRECTION_NATIVE_TLS_PING'
BASELINE = {'loginAttempts': 1, 'commandEquivalents': 9408}
RESERVATION = COMMAND_RESERVATION = 64
CLEANUP_RESERVATION = 0
MAX_TOTAL = 10000
CHILD_DEADLINE_SECONDS = 60
REVISION_FILE = 'input-revision-port-correction.json'
REVISION_KEYS = frozenset(('sha256Before', 'sha256After', 'changedKeys', 'otherFieldsUnchanged'))
LEDGER_PATTERN = re.compile(r'ledger-port-correction-[a-f0-9]{16}\.json')
REASONS = frozenset(('PORT_CORRECTION_ALREADY_ATTEMPTED', 'INPUT_REVISION_REQUIRED', 'INPUT_REVISION_CONTRACT',
                    'INPUT_REVISION_CHANGED', 'INPUT_REVISION_MISMATCH', 'INPUT_UNSAFE_OR_UNREADABLE'))


def safe_failure(failure):
    if isinstance(failure, ValueError) and str(failure) in REASONS:
        return str(failure)
    return safe_reason(failure)


def existing_budget(state):
    if not state.is_dir():
        raise ValueError('EXISTING_TASK_STATE_REQUIRED')
    if any(state.glob('ledger-port-correction-*.json')):
        raise ValueError('PORT_CORRECTION_ALREADY_ATTEMPTED')
    path = state / 'budget.json'
    if not path.exists():
        raise ValueError('EXISTING_BUDGET_REQUIRED')
    checked_private_file(path)
    try:
        budget = json.loads(path.read_text())
    except (ValueError, UnicodeError):
        raise ValueError('BUDGET_FORMAT') from None
    if (not isinstance(budget, dict) or set(budget) != set(BASELINE)
            or any(type(value) is not int or value < 0 for value in budget.values())):
        raise ValueError('BUDGET_FORMAT')
    if budget['commandEquivalents'] + RESERVATION > MAX_TOTAL or budget['loginAttempts'] >= 8:
        raise ValueError('WORKFLOW_BUDGET_EXHAUSTED')
    if budget != BASELINE:
        raise ValueError('AUTHORIZED_BASELINE_CHANGED')
    for ledger in state.glob('ledger-*.json'):
        checked_private_file(ledger)
        try:
            prior = json.loads(ledger.read_text())
        except (ValueError, UnicodeError):
            raise ValueError('PRIOR_CLEANUP_UNCONFIRMED') from None
        if not isinstance(prior, dict) or prior.get('cleanupComplete') is not True:
            raise ValueError('PRIOR_CLEANUP_UNCONFIRMED')
    # The legacy loader initializes absent budgets and rejects >=8000. Neither
    # behavior fits this explicit continuation, so reuse its file/ledger checks.
    return path, budget


def reserve_once(state, ledger):
    if ledger.parent != state or not LEDGER_PATTERN.fullmatch(ledger.name):
        raise ValueError('LEDGER_PATH_CONTRACT')
    path, budget = existing_budget(state)
    private_json(ledger, {'cleanupComplete': False, 'owned_remote_resources_created': False,
                          'operation': OPERATION, 'commands_reserved': RESERVATION})
    budget['commandEquivalents'] += RESERVATION
    with path.open('r+') as output:
        output.seek(0)
        json.dump(budget, output)
        output.truncate()
        output.flush()
        os.fsync(output.fileno())
    return budget


def load_revision(state):
    path = state / REVISION_FILE
    if not path.exists():
        raise ValueError('INPUT_REVISION_REQUIRED')
    checked_private_file(path)
    if path.stat().st_size > 2048:
        raise ValueError('INPUT_REVISION_CONTRACT')
    try:
        revision = json.loads(path.read_text())
    except (ValueError, UnicodeError):
        raise ValueError('INPUT_REVISION_CONTRACT') from None
    if (not isinstance(revision, dict) or set(revision) != REVISION_KEYS
            or revision.get('changedKeys') != ['REDIS_PORT'] or revision.get('otherFieldsUnchanged') is not True
            or any(not isinstance(revision[key], str) or not re.fullmatch(r'[a-f0-9]{64}', revision[key])
                   for key in ('sha256Before', 'sha256After'))
            or revision['sha256Before'] == revision['sha256After']):
        raise ValueError('INPUT_REVISION_CONTRACT')
    return revision


def read_bound_inputs(path, revision):
    """Use the existing parser on the very bytes whose private revision matches."""
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
        try:
            return redis_inputs(parse_values(raw.decode('utf-8')).values)
        except UnicodeError:
            raise ValueError('INPUT_UNSAFE_OR_UNREADABLE') from None
    except OSError:
        raise ValueError('INPUT_UNSAFE_OR_UNREADABLE') from None
    finally:
        if opened is not None:
            os.close(opened)
        if directory is not None:
            os.close(directory)


def project_result(observed):
    if (not isinstance(observed, dict) or observed.get('read_only_operation') != OPERATION
            or type(observed.get('command_reservation')) is not int or observed['command_reservation'] != RESERVATION
            or type(observed.get('connection_limit')) is not int or observed['connection_limit'] != 1
            or type(observed.get('target_connections_started')) is not int or not 0 <= observed['target_connections_started'] <= 1
            or observed.get('address_selection') != 'FIRST_IPV4' or observed.get('jdk_tcp_control_executed') is not False
            or observed.get('status') not in ('PASS', 'FAIL') or not isinstance(observed.get('controls'), dict)
            or observed['controls'].get('A') != {'status': 'NOT_RUN'} or observed['controls'].get('C') != {'status': 'NOT_RUN'}):
        raise ValueError('RESULT_CONTRACT')
    # Reuse the established nested projection validator. These compatibility
    # values exist only in this validation view and are never returned or saved.
    view = {**observed, 'read_only_operation': TRANSPORT_OPERATION, 'command_reservation': 256,
            'connection_limit': 3, 'status': 'FAIL', 'conditional_c_reason': 'NOT_RUN_NO_SUPPORTED_BASIS'}
    view.pop('address_selection'); view.pop('jdk_tcp_control_executed')
    project_transport_result(view)
    native = observed['controls']['B']
    if sum(native.get('completion_counts', {}).values()) > RESERVATION:
        raise ValueError('RESULT_CONTRACT')
    if observed['status'] == 'PASS' and (native.get('status') != 'PASS' or observed['target_connections_started'] != 1
            or native.get('explicit_ping_count') != 1 or observed.get('cleanup_complete') is not True
            or native.get('activation') != 'PASS' or native.get('ping') != 'PASS' or native.get('channel_closed') is not True
            or not observed.get('setting_matches') or not all(observed['setting_matches'].values())):
        raise ValueError('RESULT_CONTRACT')
    if forbidden(observed):
        raise ValueError('RESULT_REJECTED')
    return observed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true', required=True)
    parser.add_argument('--cache-seed', type=Path, required=True)
    parser.add_argument('--state-dir', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o077)
    result = {'status': 'BLOCKED', 'input_status': 'NOT_CHECKED', 'provider_process_launched': False,
              'input_revision_reference': 'REDIS_PORT_CORRECTION',
              'read_only_operation': OPERATION, 'automatic_retry': False, 'remote_key_writes': 0,
              'database_operations': 0, 'login_attempts': 0, 'provider_selection': 'HOLD',
              'upstash_failover_revocation_guarantee': 'NOT_ESTABLISHED', 'public_deployment_ready': False}
    work = process = lock = config = summary = ledger = None
    previous_signals = {}
    run = uuid.uuid4().hex[:16]
    clean = True
    child_stopped = True
    launching = False
    pending_interrupt = False
    try:
        if not args.state_dir.is_dir():
            raise ValueError('EXISTING_TASK_STATE_REQUIRED')
        state = private_state(args.state_dir)
        lock = acquire_lock(state)
        existing_budget(state)
        revision = load_revision(state)
        approved_input = Path.home() / '.config/moneytoad/provider-check.env'
        values = read_bound_inputs(approved_input, revision)
        result.update(input_status='PASS', input_revision_verified=True)
        def interrupted(signum, frame):
            nonlocal pending_interrupt
            if launching:
                pending_interrupt = True
                return
            raise KeyboardInterrupt()
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous_signals[signum] = signal.signal(signum, interrupted)
        work = Path(tempfile.mkdtemp(prefix='moneytoad-port-correction-')).resolve()
        java, env, classpath, source = compile_probe(work, args.cache_seed.resolve())
        result['probe_compilation'] = 'PASS'
        if load_revision(state) != revision:
            raise ValueError('INPUT_REVISION_CHANGED')
        values = read_bound_inputs(approved_input, revision)
        ledger = state / ('ledger-port-correction-' + run + '.json')
        budget = reserve_once(state, ledger)
        result.update(reserved_command_equivalents=budget['commandEquivalents'],
                      remaining_command_equivalents=MAX_TOTAL - budget['commandEquivalents'],
                      reserved_login_attempts=budget['loginAttempts'],
                      diagnostic_command_equivalents_reserved_this_run=COMMAND_RESERVATION,
                      cleanup_command_equivalents_reserved=CLEANUP_RESERVATION)
        config = state / ('config-port-correction-' + run + '.json')
        summary = state / ('result-port-correction-' + run + '.json')
        private_json(config, values)
        values.clear()
        private_json(summary, {})
        env.update(MANAGED_REDIS_CONFIG=str(config), MANAGED_REDIS_RESULT=str(summary), MANAGED_REDIS_LEDGER=str(ledger))
        # Defer our Python handlers only across launch/tracking. Blocking the OS
        # signals would pass a blocked mask into the Java child at exec time.
        launching = True
        try:
            process = subprocess.Popen([str(java / 'bin/java'), '-cp', classpath, MAIN_CLASS],
                                       cwd=source / 'be', env=env, stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL, start_new_session=True)
            result['provider_process_launched'] = True
            clean = False
            child_stopped = False
        finally:
            launching = False
        if pending_interrupt:
            raise KeyboardInterrupt()
        process.wait(timeout=CHILD_DEADLINE_SECONDS)
    except InputRejected:
        result.update(status='INPUT_REQUIRED', input_status='BLOCKED', reason='INPUT_CHECK_FAILED')
    except KeyboardInterrupt:
        result.update(status='INTERRUPTED', reason='INTERRUPTED')
    except (ValueError, OSError, subprocess.SubprocessError) as failure:
        result['reason'] = safe_failure(failure)
    finally:
        for signum in previous_signals:
            signal.signal(signum, signal.SIG_IGN)
        try:
            child_stopped, forced = finish_child(process, result['status'] == 'INTERRUPTED' or result.get('reason') == 'CHILD_DEADLINE')
            result['forced_child_termination'] = forced
        except (OSError, subprocess.SubprocessError):
            child_stopped = False
            result['forced_child_termination'] = True
        if result['provider_process_launched']:
            clean = False
            try:
                checked_private_file(summary)
                if summary.stat().st_size > 131072:
                    raise ValueError('RESULT_CONTRACT')
                observed = project_result(json.loads(summary.read_text()))
                result['observation'] = observed
                checked_private_file(ledger)
                ledger_state = json.loads(ledger.read_text())
                confirmed = (isinstance(ledger_state, dict) and ledger_state.get('cleanupComplete') is True
                             and ledger_state.get('owned_remote_resources_created') is False)
                clean = child_stopped and not result['forced_child_termination'] and observed['cleanup_complete'] and confirmed
                if result['status'] != 'INTERRUPTED':
                    result['status'] = 'PASS' if not result.get('reason') and process.returncode == 0 and observed['status'] == 'PASS' and clean else 'FAIL'
            except (OSError, ValueError):
                if result['status'] != 'INTERRUPTED':
                    result['status'] = 'FAIL'
                result['reason'] = 'RESULT_OR_CLEANUP_UNCONFIRMED'
        elif ledger is not None and ledger.exists():
            # No child was launched: close this attempt honestly without erasing its marker or reservation.
            try:
                checked_private_file(ledger)
                with ledger.open('w') as output:
                    json.dump({'cleanupComplete': True, 'owned_remote_resources_created': False, 'operation': OPERATION}, output)
                    output.flush()
                    os.fsync(output.fileno())
            except (OSError, ValueError):
                clean = False
        # Read-only controls create no remote recovery resources. Remove only this
        # invocation's plaintext delivery file after its only child has stopped.
        if child_stopped:
            try:
                if config is not None:
                    config.unlink(missing_ok=True)
                if work is not None:
                    shutil.rmtree(work)
            except OSError:
                clean = False
        else:
            clean = False
        if lock is not None:
            lock.close()
        result['cleanup_complete'] = clean
        result['child_stopped'] = child_stopped
        if forbidden(result):
            raise ValueError('PUBLIC_PROJECTION_REJECTED')
        destination = ROOT / 'docs/deployment/evidence/AUTONOMOUS_CONTINUATION' / ('redis-port-correction-' + run)
        destination.mkdir(parents=True, exist_ok=False)
        (destination / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({key: result[key] for key in ('status', 'input_status', 'provider_process_launched', 'cleanup_complete')}))
        for signum, handler in previous_signals.items():
            signal.signal(signum, handler)
    return 0 if result['status'] == 'PASS' else 2


if __name__ == '__main__':
    raise SystemExit(main())
