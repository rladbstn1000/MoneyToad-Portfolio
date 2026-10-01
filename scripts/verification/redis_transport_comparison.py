#!/usr/bin/env python3
"""One authorized JDK/Lettuce comparison child; no reset, refund or retry.

Only the existing approved input file and private task state are used. Compilation
is offline; a durable one-run marker and the full 2,256 reservation precede launch.
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

from managed_provider_check import acquire_lock, checked_private_file, compile_probe, load_budget, private_state, ROOT
from managed_provider_preflight import InputRejected, REQUIRED, read_inputs
from public_evidence import forbidden

MAIN_CLASS = 'com.potg.verification.managed.RedisTransportComparisonProbe'
OPERATION = 'JDK_TLS_LETTUCE_TRANSPORT_COMPARISON'
FIELDS = frozenset(('REDIS_HOST', 'REDIS_PORT', 'REDIS_USERNAME', 'REDIS_PASSWORD', 'REDIS_SSL_ENABLED'))
RESERVATION = 2256
CLEANUP_RESERVATION = 2000
COMMAND_RESERVATION = 256
BASELINE = {'loginAttempts': 1, 'commandEquivalents': 7152}
MAX_TOTAL = 10000
CHILD_DEADLINE_SECONDS = 120
LEDGER_PATTERN = re.compile(r'ledger-transport-comparison-[a-f0-9]{16}\.json')
FIXED_REASONS = frozenset(('EXISTING_TASK_STATE_REQUIRED', 'EXISTING_BUDGET_REQUIRED',
    'TRANSPORT_COMPARISON_ALREADY_ATTEMPTED', 'BUDGET_FORMAT', 'PRIOR_CLEANUP_UNCONFIRMED',
    'WORKFLOW_BUDGET_EXHAUSTED', 'AUTHORIZED_BASELINE_CHANGED', 'PRIVATE_FILE_OWNER_OR_MODE',
    'BUDGET_OWNER_OR_MODE', 'STATE_MUST_BE_PRIVATE_OUTSIDE_REPOSITORY', 'STATE_SYMLINK_REJECTED',
    'STATE_OWNER_OR_MODE', 'LEDGER_PATH_CONTRACT', 'INPUT_ALLOWLIST', 'RESULT_CONTRACT',
    'RESULT_REJECTED', 'PROBE_COMPILATION_FAILED', 'EXPLICIT_CACHE_MISSING'))

EXCEPTION_CLASSES = frozenset(('java.lang.Exception', 'java.lang.RuntimeException', 'java.lang.IllegalStateException', 'java.lang.IllegalArgumentException', 'java.util.concurrent.ExecutionException', 'java.util.concurrent.CompletionException', 'java.util.concurrent.TimeoutException', 'java.util.concurrent.CancellationException', 'java.io.IOException', 'java.io.EOFException', 'java.nio.channels.ClosedChannelException', 'java.net.UnknownHostException', 'java.net.ConnectException', 'java.net.NoRouteToHostException', 'java.net.SocketException', 'java.net.SocketTimeoutException', 'javax.net.ssl.SSLException', 'javax.net.ssl.SSLHandshakeException', 'javax.net.ssl.SSLPeerUnverifiedException', 'java.security.cert.CertificateException', 'java.security.cert.CertificateExpiredException', 'java.security.cert.CertificateNotYetValidException', 'java.security.cert.CertPathValidatorException', 'sun.security.validator.ValidatorException', 'sun.security.provider.certpath.SunCertPathBuilderException', 'org.springframework.data.redis.RedisConnectionFailureException', 'org.springframework.data.redis.RedisSystemException', 'org.springframework.dao.DataAccessResourceFailureException', 'org.springframework.dao.InvalidDataAccessApiUsageException', 'org.springframework.dao.QueryTimeoutException', 'org.springframework.boot.context.properties.bind.BindException', 'io.lettuce.core.RedisException', 'io.lettuce.core.RedisConnectionException', 'io.lettuce.core.RedisCommandExecutionException', 'io.lettuce.core.RedisCommandTimeoutException', 'io.lettuce.core.RedisCommandInterruptedException', 'io.lettuce.core.RedisProtocolException', 'io.netty.channel.AbstractChannel$AnnotatedConnectException', 'io.netty.channel.ConnectTimeoutException', 'io.netty.resolver.dns.DnsNameResolverException', 'io.netty.resolver.dns.DnsNameResolverTimeoutException', 'io.netty.handler.ssl.SslHandshakeTimeoutException', 'io.netty.handler.ssl.NotSslRecordException', 'io.netty.handler.codec.DecoderException', 'io.netty.handler.codec.CorruptedFrameException', 'io.netty.handler.codec.TooLongFrameException', 'com.potg.verification.managed.ManagedSqlContracts$ContractFailure', 'UNLISTED_EXCEPTION_CLASS'))


def existing_budget(state):
    """Preconditions precede the legacy loader, which may otherwise initialize state."""
    if not state.is_dir():
        raise ValueError('EXISTING_TASK_STATE_REQUIRED')
    if any(state.glob('ledger-transport-comparison-*.json')):
        raise ValueError('TRANSPORT_COMPARISON_ALREADY_ATTEMPTED')
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
    # Validate shape before the inherited loader calls .get on these ledgers.
    for ledger in state.glob('ledger-*.json'):
        checked_private_file(ledger)
        try:
            previous = json.loads(ledger.read_text())
        except (ValueError, UnicodeError):
            raise ValueError('PRIOR_CLEANUP_UNCONFIRMED') from None
        if not isinstance(previous, dict) or previous.get('cleanupComplete') is not True:
            raise ValueError('PRIOR_CLEANUP_UNCONFIRMED')
    if load_budget(state) != path:
        raise ValueError('BUDGET_FORMAT')
    return path, budget


def private_json(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        json.dump(value, output)
        output.flush()
        os.fsync(output.fileno())


def reserve_once(state, ledger):
    if ledger.parent != state or not LEDGER_PATTERN.fullmatch(ledger.name):
        raise ValueError('LEDGER_PATH_CONTRACT')
    path, budget = existing_budget(state)
    # Marker survives even an interrupted reservation or failed child launch.
    # Never delete this marker to make another joint run possible.
    private_json(ledger, {'cleanupComplete': False, 'owned_remote_resources_created': False,
                          'operation': OPERATION})
    budget['commandEquivalents'] += RESERVATION
    with path.open('r+') as output:
        output.seek(0)
        json.dump(budget, output)
        output.truncate()
        output.flush()
        os.fsync(output.fileno())
    return budget


def redis_inputs(values):
    if not isinstance(values, dict) or set(values) != REQUIRED:
        raise ValueError('INPUT_ALLOWLIST')
    if any(not isinstance(value, str) for value in values.values()):
        raise ValueError('INPUT_ALLOWLIST')
    return {key: values[key] for key in sorted(FIELDS)}


def safe_reason(failure):
    if isinstance(failure, ValueError) and str(failure) in FIXED_REASONS:
        return str(failure)
    if isinstance(failure, subprocess.TimeoutExpired):
        return 'CHILD_DEADLINE'
    if isinstance(failure, PermissionError):
        return 'LOCAL_PERMISSION_DENIED'
    if isinstance(failure, FileNotFoundError):
        return 'LOCAL_FILE_MISSING'
    if isinstance(failure, subprocess.SubprocessError):
        return 'CHILD_PROCESS_ERROR'
    return 'LOCAL_OPERATION_FAILED'


def finish_child(process, interrupted=False):
    """Finite cleanup of this invocation's owned process group, never a retry."""
    if process is None or process.poll() is not None:
        return True, False
    if interrupted:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            process.wait(timeout=10)
            return True, False
    try:
        process.wait(timeout=30)
        return True, False
    except subprocess.TimeoutExpired:
        pass
    forced = False
    try:
        if not interrupted:
            os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        forced = True
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=10)
    except ProcessLookupError:
        process.wait(timeout=10)
    return process.poll() is not None, forced


def project_result(observed):
    """Validate the Java projection, including failures, without projecting new data."""
    def require(condition):
        if not condition:
            raise ValueError('RESULT_CONTRACT')
    def shape(value, required, optional=()):
        require(isinstance(value, dict) and set(required) <= set(value) <= set(required) | set(optional))
    def enum(value, choices):
        require(type(value) is str and value in choices)
    def number(value, maximum=2147483647):
        require(type(value) is int and 0 <= value <= maximum)
    def boolean(value):
        require(type(value) is bool)
    commands = frozenset(('HELLO', 'AUTH', 'CLIENT', 'PING', 'SELECT', 'UNEXPECTED_COMMAND'))
    phases = frozenset(('NOT_OBSERVED', 'INPUT', 'CONFIGDATA', 'CLIENT_RESOURCES', 'CLIENT_OPTIONS',
        'FACTORY_START', 'CONNECT_ACTIVATE', 'PING', 'CLEANUP', 'COMPLETE', 'DNS', 'TCP', 'TLS',
        'HANDSHAKE', 'ACTIVE', 'EXPLICIT_PING', 'UNEXPECTED_PHASE'))
    stages = frozenset(('DNS', 'TCP', 'TLS', 'HANDSHAKE', 'ACTIVE', 'EXPLICIT_PING'))
    states = frozenset(('NOT_OBSERVED', 'STARTED', 'PASS', 'FAIL', 'UNEXPECTED_STATE'))
    categories = frozenset(('NOT_OBSERVED', 'DNS_FAILURE', 'TCP_FAILURE', 'TCP_TIMEOUT', 'TLS_TRUST_FAILURE',
        'TLS_HOSTNAME_FAILURE', 'TLS_FAILURE', 'AUTH_REQUIRED', 'AUTH_REJECTED', 'ACL_DENIED',
        'PROTOCOL_UNSUPPORTED', 'COMMAND_UNSUPPORTED', 'CONNECTION_LIMIT', 'QUOTA_REJECTED',
        'HANDSHAKE_TIMEOUT', 'COMMAND_TIMEOUT', 'DECODE_FAILURE', 'LOCAL_PROBE_REJECTED', 'UNKNOWN'))
    def diagnostics(value):
        shape(value, ('phase', 'stages', 'commands', 'events', 'events_total', 'events_dropped',
                      'failure_category', 'exception_nodes', 'exception_relations', 'exceptions_truncated'))
        enum(value['phase'], phases); enum(value['failure_category'], categories)
        shape(value['stages'], stages)
        for state in value['stages'].values():
            enum(state, states)
        shape(value['commands'], commands)
        for counts in value['commands'].values():
            shape(counts, ('STARTED', 'PASS', 'FAIL', 'UNEXPECTED_STATE'))
            for count in counts.values():
                number(count, COMMAND_RESERVATION)
        require(isinstance(value['events'], list) and len(value['events']) <= 64)
        for sequence, event in enumerate(value['events'], 1):
            shape(event, ('sequence', 'kind', 'code', 'state'))
            number(event['sequence']); require(event['sequence'] == sequence)
            enum(event['kind'], ('PHASE', 'STAGE', 'COMMAND', 'FAILURE'))
            enum(event['code'], phases | stages | commands | categories | {'UNEXPECTED_STAGE'})
            enum(event['state'], states)
        number(value['events_total'], 1024); number(value['events_dropped'], 1024)
        require(value['events_total'] == len(value['events']) + value['events_dropped'])
        nodes = value['exception_nodes']; relations = value['exception_relations']
        require(isinstance(nodes, list) and len(nodes) <= 12)
        require(isinstance(relations, list) and len(relations) <= 24)
        for position, node in enumerate(nodes):
            shape(node, ('position', 'exception_class', 'depth'))
            number(node['position'], 11); require(node['position'] == position)
            number(node['depth'], 11); enum(node['exception_class'], EXCEPTION_CLASSES)
        for relation in relations:
            shape(relation, ('from', 'to', 'relation'))
            number(relation['from'], 11); number(relation['to'], 11)
            require(relation['from'] < len(nodes) and relation['to'] < len(nodes))
            enum(relation['relation'], ('CAUSE', 'SUPPRESSED'))
        boolean(value['exceptions_truncated'])
    required = ('status', 'read_only_operation', 'owned_remote_resources_created', 'remote_key_writes',
        'database_operations', 'login_attempts', 'automatic_retry', 'product_condition_exact', 'resolver',
        'os_dns_physical_connections', 'probe_owned_dns_sockets', 'command_reservation', 'connection_limit',
        'conditional_c_reason', 'controls', 'lifecycle', 'target_connections_started', 'elapsed_millis',
        'cleanup_complete', 'diagnostics')
    optional = ('product_configdata', 'setting_matches', 'effective_settings', 'versions', 'native_transport',
                'environment_flags', 'jdk_selected_address_family', 'prior_run_dns_target_comparison', 'dns_hostname_preserved', 'ssl_provider')
    shape(observed, required, optional)
    enum(observed['status'], ('PASS', 'FAIL'))
    require(observed['read_only_operation'] == OPERATION)
    for field in ('owned_remote_resources_created', 'automatic_retry'):
        require(observed[field] is False)
    for field in ('remote_key_writes', 'database_operations', 'login_attempts', 'probe_owned_dns_sockets'):
        number(observed[field], 0)
    require(type(observed['command_reservation']) is int and observed['command_reservation'] == COMMAND_RESERVATION)
    require(type(observed['connection_limit']) is int and observed['connection_limit'] == 3)
    boolean(observed['product_condition_exact']); boolean(observed['cleanup_complete'])
    enum(observed['resolver'], ('SHARED_JDK_HOSTNAME_LOOKUP',))
    if 'ssl_provider' in observed:
        enum(observed['ssl_provider'], ('JDK_DEFAULT_TRUST',))
    enum(observed['os_dns_physical_connections'], ('NOT_OBSERVED',))
    enum(observed['conditional_c_reason'], ('NOT_RUN_NO_SUPPORTED_BASIS', 'B_SUCCESS_OBSERVER_ONLY_COMPARISON'))
    number(observed['target_connections_started'], 3); number(observed['elapsed_millis'], 180000)
    diagnostics(observed['diagnostics'])
    if 'product_configdata' in observed:
        enum(observed['product_configdata'], ('PASS',))
    if 'setting_matches' in observed:
        shape(observed['setting_matches'], ('host_matches', 'port_matches', 'username_matches', 'password_matches', 'tls_matches', 'database_matches'))
        for match in observed['setting_matches'].values():
            boolean(match)
    if 'effective_settings' in observed:
        settings = observed['effective_settings']
        shape(settings, ('connect_timeout_millis', 'activation_timeout_millis', 'command_timeout_millis',
            'ssl_handshake_timeout_millis', 'tls_verify_mode', 'protocol_configured', 'protocol_effective_preconnect',
            'command_timeout_enabled', 'auto_reconnect', 'request_queue_limit', 'jdk_dns_deadline_millis', 'native_control_deadline_millis'))
        for field in ('connect_timeout_millis', 'activation_timeout_millis', 'command_timeout_millis',
                      'ssl_handshake_timeout_millis', 'jdk_dns_deadline_millis', 'native_control_deadline_millis'):
            number(settings[field], 30000)
        number(settings['request_queue_limit'])
        enum(settings['tls_verify_mode'], ('FULL',)); enum(settings['protocol_configured'], ('DEFAULT_NEGOTIATION',))
        enum(settings['protocol_effective_preconnect'], ('RESP2', 'RESP3'))
        boolean(settings['command_timeout_enabled']); require(settings['auto_reconnect'] is False)
    if 'versions' in observed:
        shape(observed['versions'], ('java', 'lettuce', 'netty'))
        for version in observed['versions'].values():
            require(type(version) is str and re.fullmatch(r'[0-9]+\.[0-9A-Za-z.+_-]{1,63}(?:/[a-f0-9]{7,40})?', version) is not None)
    if 'native_transport' in observed:
        enum(observed['native_transport'], ('NioSocketChannel', 'EpollSocketChannel', 'KQueueSocketChannel', 'UNLISTED_TRANSPORT'))
    if 'environment_flags' in observed:
        shape(observed['environment_flags'], ('http_proxyHost_configured', 'https_proxyHost_configured',
            'socksProxyHost_configured', 'javax_net_ssl_trustStore_configured', 'java_net_preferIPv4Stack_configured',
            'java_net_preferIPv6Addresses_configured', 'io_netty_transport_noNative_configured', 'system_proxies_enabled'))
        for flag in observed['environment_flags'].values():
            boolean(flag)
    if 'jdk_selected_address_family' in observed:
        enum(observed['jdk_selected_address_family'], ('IPV4', 'IPV6'))
    if 'dns_hostname_preserved' in observed:
        boolean(observed['dns_hostname_preserved'])
    if 'prior_run_dns_target_comparison' in observed:
        enum(observed['prior_run_dns_target_comparison'], ('NOT_ESTABLISHED',))
    shape(observed['controls'], ('A', 'B', 'C'))
    total_completions = total_pings = 0
    for label, control in observed['controls'].items():
        require(isinstance(control, dict))
        if control.get('status') == 'NOT_RUN':
            shape(control, ('status',)); continue
        enum(control.get('status'), ('PASS', 'FAIL'))
        common = ('status', 'cleanup_complete', 'elapsed_millis', 'diagnostics')
        if label == 'A':
            shape(control, common + ('tcp', 'tls', 'redis_commands_sent'))
            enum(control['tcp'], ('PASS', 'FAIL', 'NOT_RUN')); enum(control['tls'], ('PASS', 'FAIL', 'NOT_RUN'))
            number(control['redis_commands_sent'], 0)
        else:
            shape(control, common + ('activation', 'ping', 'custom_pipeline_observer', 'command_start_count',
                'automatic_and_explicit_command_upper_bound', 'connect_future_cancelled', 'completion_counts',
                'explicit_ping_count', 'same_dns_target_as_a', 'selected_address_family', 'conservative_command_allowance',
                'read_only_channel_lifetime_observer', 'channel_closed', 'tls_peer_hostname_matches',
                'tls_future_diagnostics', 'handshake_future_diagnostics'))
            enum(control['activation'], ('PASS', 'FAIL', 'NOT_RUN')); enum(control['ping'], ('PASS', 'FAIL', 'NOT_RUN'))
            require(control['custom_pipeline_observer'] is (label == 'C'))
            boolean(control['connect_future_cancelled'])
            enum(control['same_dns_target_as_a'], ('MATCH', 'MISMATCH', 'NOT_OBSERVED'))
            require(control['read_only_channel_lifetime_observer'] is True)
            boolean(control['channel_closed']); boolean(control['tls_peer_hostname_matches'])
            diagnostics(control['tls_future_diagnostics']); diagnostics(control['handshake_future_diagnostics'])
            enum(control['selected_address_family'], ('IPV4', 'IPV6', 'NOT_OBSERVED'))
            require(type(control['automatic_and_explicit_command_upper_bound']) is int and control['automatic_and_explicit_command_upper_bound'] == 5)
            require(type(control['conservative_command_allowance']) is int and control['conservative_command_allowance'] == 64)
            if control['command_start_count'] != 'NOT_OBSERVED':
                number(control['command_start_count'], 64)
            shape(control['completion_counts'], commands)
            for count in control['completion_counts'].values():
                number(count, 64); total_completions += count
            number(control['explicit_ping_count'], 1); total_pings += control['explicit_ping_count']
        boolean(control['cleanup_complete']); number(control['elapsed_millis'], 120000)
        diagnostics(control['diagnostics'])
    require(total_completions <= COMMAND_RESERVATION and total_pings <= 2)
    events = observed['lifecycle']
    require(isinstance(events, list) and len(events) <= 128)
    event_names = frozenset(('INPUT_READY', 'DNS_BEGIN', 'DNS_PASS', 'DNS_FAIL', 'TCP_BEGIN', 'TCP_PASS',
        'TLS_BEGIN', 'TLS_PASS', 'CONTROL_FAIL', 'CONTROL_CLEANUP_BEGIN', 'SOCKET_CLOSE_BEGIN', 'SOCKET_CLOSED',
        'NATIVE_CONNECT_BEGIN', 'CONNECTION_CREATED', 'CONNECT_EVENT', 'CONNECTED_EVENT', 'ACTIVATED_EVENT',
        'DEACTIVATED_EVENT', 'DISCONNECTED_EVENT', 'CONNECT_FUTURE_SUCCESS', 'CONNECT_FUTURE_FAILURE',
        'CONNECT_FUTURE_CANCEL_REQUEST', 'PING_BEGIN', 'PING_PASS', 'CONNECTION_CLOSE_BEGIN', 'CONNECTION_CLOSED',
        'CLIENT_SHUTDOWN_BEGIN', 'CLIENT_SHUTDOWN_DONE', 'RESOURCES_SHUTDOWN_BEGIN', 'RESOURCES_SHUTDOWN_DONE',
        'RUN_CLEANUP_BEGIN', 'RUN_CLEANUP_DONE', 'CHANNEL_CREATED', 'CHANNEL_CLOSED', 'CHANNEL_CLOSE_BEGIN',
        'TLS_FUTURE_SUCCESS', 'TLS_FUTURE_FAILURE', 'HANDSHAKE_FUTURE_SUCCESS', 'HANDSHAKE_FUTURE_FAILURE',
        'A_CONTROL_DEADLINE', 'TLS_CONTROL_DEADLINE'))
    previous = 0
    for event in events:
        shape(event, ('control', 'event', 'elapsed_millis'))
        enum(event['control'], ('RUN', 'A', 'B', 'C')); enum(event['event'], event_names)
        number(event['elapsed_millis'], 180000); require(event['elapsed_millis'] >= previous)
        previous = event['elapsed_millis']
    if observed['status'] == 'PASS':
        require(observed['cleanup_complete'] and all(control['status'] == 'PASS' for control in observed['controls'].values()))
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
        values = redis_inputs(read_inputs(Path.home() / '.config/moneytoad/provider-check.env').values)
        result['input_status'] = 'PASS'
        if not args.state_dir.is_dir():
            raise ValueError('EXISTING_TASK_STATE_REQUIRED')
        state = private_state(args.state_dir)
        lock = acquire_lock(state)
        existing_budget(state)
        def interrupted(signum, frame):
            nonlocal pending_interrupt
            if launching:
                pending_interrupt = True
                return
            raise KeyboardInterrupt()
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous_signals[signum] = signal.signal(signum, interrupted)
        work = Path(tempfile.mkdtemp(prefix='moneytoad-redis-transport-')).resolve()
        java, env, classpath, source = compile_probe(work, args.cache_seed.resolve())
        result['probe_compilation'] = 'PASS'
        ledger = state / ('ledger-transport-comparison-' + run + '.json')
        budget = reserve_once(state, ledger)
        result.update(reserved_command_equivalents=budget['commandEquivalents'],
                      remaining_command_equivalents=MAX_TOTAL - budget['commandEquivalents'],
                      reserved_login_attempts=budget['loginAttempts'],
                      diagnostic_command_equivalents_reserved_this_run=COMMAND_RESERVATION,
                      cleanup_command_equivalents_reserved=CLEANUP_RESERVATION)
        config = state / ('config-transport-comparison-' + run + '.json')
        summary = state / ('result-transport-comparison-' + run + '.json')
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
        result['reason'] = safe_reason(failure)
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
        destination = ROOT / 'docs/deployment/evidence/AUTONOMOUS_CONTINUATION' / ('redis-transport-comparison-' + run)
        destination.mkdir(parents=True, exist_ok=False)
        (destination / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({key: result[key] for key in ('status', 'input_status', 'provider_process_launched', 'cleanup_complete')}))
        for signum, handler in previous_signals.items():
            signal.signal(signum, handler)
    return 0 if result['status'] == 'PASS' else 2


if __name__ == '__main__':
    raise SystemExit(main())
