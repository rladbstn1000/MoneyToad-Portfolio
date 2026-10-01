#!/usr/bin/env python3
"""One read-only native TLS/AUTH/PING diagnostic after a failed provider run.

Reuses an EXISTING private task budget/lock; never initializes or refunds budget.
No SQL, login, key operations, credential alternatives or automatic retry.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import uuid

from managed_provider_check import acquire_lock, checked_private_file, compile_probe, private_state, ROOT
from managed_provider_preflight import InputRejected, read_inputs
from public_evidence import forbidden

MAIN_CLASS = 'com.potg.verification.managed.ManagedRedisConnectivityProbe'
RESERVATION = 2256  # 2,000 cleanup + 256 connection/handshake/PING upper bound.
DIAGNOSTIC_COMMAND_RESERVATION = 256
MAX_DIAGNOSTIC_COMMAND_RESERVATION = 512
FIELDS = frozenset(('REDIS_HOST', 'REDIS_PORT', 'REDIS_USERNAME', 'REDIS_PASSWORD', 'REDIS_SSL_ENABLED'))


def existing_budget(state):
    if not state.is_dir():
        raise ValueError('EXISTING_TASK_STATE_REQUIRED')
    path = state / 'budget.json'
    if not path.exists():
        raise ValueError('EXISTING_BUDGET_REQUIRED')
    checked_private_file(path)
    try:
        budget = json.loads(path.read_text())
    except (ValueError, UnicodeError):
        raise ValueError('BUDGET_FORMAT') from None
    if (not isinstance(budget, dict) or set(budget) != {'loginAttempts', 'commandEquivalents'}
            or any(type(value) is not int or value < 0 for value in budget.values())
            or budget['loginAttempts'] > 8):
        raise ValueError('BUDGET_FORMAT')
    if budget['commandEquivalents'] + RESERVATION > 10000:
        raise ValueError('WORKFLOW_BUDGET_EXHAUSTED')
    for ledger in state.glob('ledger-*.json'):
        checked_private_file(ledger)
        if json.loads(ledger.read_text()).get('cleanupComplete') is not True:
            raise ValueError('PRIOR_CLEANUP_UNCONFIRMED')
    return path, budget



def activation_attempt_boundary(state, comparison_reason=None):
    """Two runs at most under existing per-run cleanup reservations; no refund."""
    previous = sorted(state.glob('ledger-activation-*.json'))
    if (len(previous) + 1) * DIAGNOSTIC_COMMAND_RESERVATION > MAX_DIAGNOSTIC_COMMAND_RESERVATION:
        raise ValueError('ACTIVATION_DIAGNOSTIC_BUDGET_EXHAUSTED')
    if not previous:
        if comparison_reason is not None:
            raise ValueError('FIRST_ATTEMPT_MUST_USE_PRODUCT_SETTINGS')
        return
    if comparison_reason is None:
        raise ValueError('OBSERVED_COMPARISON_BASIS_REQUIRED')
    prior_result = state / previous[0].name.replace('ledger-activation-', 'result-activation-')
    checked_private_file(prior_result)
    observation = json.loads(prior_result.read_text())
    if observation.get('cleanup_complete') is not True:
        raise ValueError('PRIOR_CLEANUP_UNCONFIRMED')
    category = observation.get('diagnostics', {}).get('failure_category')
    permitted = {
        'probe-boundary': {'LOCAL_PROBE_REJECTED'},
        'mapping': {'LOCAL_PROBE_REJECTED'},
        'protocol': {'PROTOCOL_UNSUPPORTED', 'COMMAND_UNSUPPORTED'},
        'timeout': {'HANDSHAKE_TIMEOUT', 'COMMAND_TIMEOUT'},
    }
    if category not in permitted.get(comparison_reason, set()):
        raise ValueError('COMPARISON_NOT_SUPPORTED_BY_FIRST_OBSERVATION')


def reserve_existing_budget(state):
    path, budget = existing_budget(state)
    budget['commandEquivalents'] += RESERVATION
    with path.open('r+') as output:
        output.seek(0)
        json.dump(budget, output)
        output.truncate()
        output.flush()
        os.fsync(output.fileno())
    return budget


def private_json(path, value):
    with path.open('x') as output:
        json.dump(value, output)
        output.flush()
        os.fsync(output.fileno())
    path.chmod(0o600)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true', required=True)
    parser.add_argument('--cache-seed', type=Path, required=True)
    parser.add_argument('--state-dir', type=Path, required=True)
    parser.add_argument('--comparison-reason', choices=('probe-boundary', 'mapping', 'protocol', 'timeout'))
    args = parser.parse_args()
    os.umask(0o077)
    result = {'status': 'BLOCKED', 'input_status': 'NOT_CHECKED', 'provider_process_launched': False,
              'remote_key_writes': 0, 'database_operations': 0, 'login_attempts': 0,
              'read_only_operation': 'NATIVE_TLS_AUTH_SINGLE_PING', 'automatic_retry': False,
              'provider_selection': 'HOLD', 'upstash_failover_revocation_guarantee': 'NOT_ESTABLISHED'}
    work = process = lock = config = summary = ledger = None
    old_signals = {}
    run = uuid.uuid4().hex[:16]
    clean = True
    try:
        values = read_inputs(Path.home() / '.config/moneytoad/provider-check.env').values
        result['input_status'] = 'PASS'
        if not args.state_dir.is_dir():
            raise ValueError('EXISTING_TASK_STATE_REQUIRED')
        state = private_state(args.state_dir)
        lock = acquire_lock(state)
        existing_budget(state)
        activation_attempt_boundary(state, args.comparison_reason)
        def interrupted(signum, frame):
            raise KeyboardInterrupt()
        for signum in (signal.SIGINT, signal.SIGTERM):
            old_signals[signum] = signal.signal(signum, interrupted)
        work = Path(tempfile.mkdtemp(prefix='moneytoad-redis-readonly-')).resolve()
        java, env, classpath, source = compile_probe(work, args.cache_seed.resolve())
        result['probe_compilation'] = 'PASS'
        # Reserve before any provider process. Compilation never consumes remote budget.
        activation_attempt_boundary(state, args.comparison_reason)
        budget = reserve_existing_budget(state)
        result['reserved_command_equivalents'] = budget['commandEquivalents']
        result['diagnostic_command_equivalents_reserved_this_run'] = DIAGNOSTIC_COMMAND_RESERVATION
        result['comparison_basis'] = args.comparison_reason or 'FIRST_PRODUCT_SETTINGS'
        result['diagnostic_contract'] = 'ACTIVATION_OBSERVATION_V2'
        result['reserved_login_attempts'] = budget['loginAttempts']
        result['cleanup_command_equivalents_reserved'] = 2000
        config = state / ('config-activation-' + run + '.json')
        summary = state / ('result-activation-' + run + '.json')
        ledger = state / ('ledger-activation-' + run + '.json')
        private_json(config, {key: values[key] for key in FIELDS})
        private_json(summary, {})
        private_json(ledger, {'cleanupComplete': False, 'owned_remote_resources_created': False,
                              'operation': 'NATIVE_TLS_AUTH_SINGLE_PING'})
        env.update(MANAGED_REDIS_CONFIG=str(config), MANAGED_REDIS_RESULT=str(summary), MANAGED_REDIS_LEDGER=str(ledger))
        process = subprocess.Popen([str(java / 'bin/java'), '-cp', classpath, MAIN_CLASS], cwd=source / 'be',
                                   env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        result['provider_process_launched'] = True
        clean = False
        process.wait(timeout=60)
    except InputRejected as failure:
        result.update(status='INPUT_REQUIRED', input_status='BLOCKED', reason=str(failure))
    except KeyboardInterrupt:
        result.update(status='INTERRUPTED', reason='INTERRUPTED')
    except (ValueError, OSError, subprocess.SubprocessError) as failure:
        message = str(failure)
        result['reason'] = message if re.fullmatch(r'[A-Z_]+', message) else type(failure).__name__
    finally:
        for signum in old_signals:
            signal.signal(signum, signal.SIG_IGN)
        if process is not None and process.poll() is None:
            # Interrupt stops the parent workflow, not the finite child's cleanup.
            try:
                process.wait(timeout=45)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=15)
        if result['provider_process_launched'] and summary is not None:
            try:
                checked_private_file(summary)
                observed = json.loads(summary.read_text())
                if forbidden(observed) or not isinstance(observed, dict):
                    raise ValueError('RESULT_REJECTED')
                if observed.get('read_only_operation') != 'NATIVE_TLS_AUTH_SINGLE_PING':
                    raise ValueError('RESULT_CONTRACT')
                result['observation'] = observed
                clean = observed.get('cleanup_complete') is True
                if result['status'] != 'INTERRUPTED':
                    result['status'] = 'PASS' if process.returncode == 0 and observed.get('status') == 'PASS' and clean else 'FAIL'
            except (OSError, ValueError):
                clean = False
                result.update(status='FAIL', reason='RESULT_OR_CLEANUP_UNCONFIRMED')
        if clean and config is not None:
            config.unlink(missing_ok=True)
        if clean and work is not None:
            shutil.rmtree(work)
        if lock is not None:
            lock.close()
        result['cleanup_complete'] = clean
        if forbidden(result):
            raise ValueError('PUBLIC_PROJECTION_REJECTED')
        destination = ROOT / 'docs/deployment/evidence/AUTONOMOUS_CONTINUATION' / ('redis-activation-' + run)
        destination.mkdir(parents=True, exist_ok=False)
        (destination / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({key: result[key] for key in ('status', 'input_status', 'provider_process_launched', 'cleanup_complete')}))
        for signum, handler in old_signals.items():
            signal.signal(signum, handler)
    return 0 if result['status'] == 'PASS' else 2


if __name__ == '__main__':
    raise SystemExit(main())
