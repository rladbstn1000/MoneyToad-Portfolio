#!/usr/bin/env python3
"""Local Render contract only: owned TLS services, validate, packaged app and limits.

Runtime material stays in a private temporary directory. Only allowlisted summaries
are published; this runner never connects to a managed provider or shared service.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import signal
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile

from public_evidence import forbidden

ROOT = Path(__file__).resolve().parents[2]
LABEL = 'moneytoad.verification.render-runtime'


class CheckFailure(Exception):
    pass



def limited_runtime_projection(state, logs):
    """Only fixed phases/enums and typed values survive private Docker material."""
    status = state.get('Status')
    if status not in ('created', 'running', 'paused', 'restarting', 'removing', 'exited', 'dead'):
        status = 'UNKNOWN'
    exit_code = state.get('ExitCode')
    if type(exit_code) is not int or not -255 <= exit_code <= 255:
        exit_code = None
    oom = state.get('OOMKilled')
    if type(oom) is not bool:
        oom = None
    if oom is True:
        reason = 'OOM_KILLED'
    elif isinstance(state.get('Error'), str) and state['Error']:
        reason = 'ENGINE_REPORTED_ERROR'
    elif status in ('exited', 'dead') and exit_code is not None:
        reason = 'PROCESS_EXIT_ZERO' if exit_code == 0 else 'PROCESS_EXIT_NONZERO'
    elif state.get('Running') is True:
        reason = 'RUNNING_AT_FAILURE'
    else:
        reason = 'UNKNOWN'
    # Match known boot messages, never emit a line, URI, arbitrary class or value.
    phases = (
        ('SPRING_BOOT_STARTING', r'\bStarting DonApplication\b'),
        ('DATASOURCE_STARTING', r'\bHikariPool-\d+ - Starting\.\.\.'),
        ('DATASOURCE_READY', r'\bHikariPool-\d+ - Start completed\.'),
        ('JPA_INITIALIZING', r'\bHHH000204: Processing PersistenceUnitInfo'),
        ('JPA_READY', r'Initialized JPA EntityManagerFactory'),
        ('HTTP_BOUND', r'Tomcat started on port'),
        ('APPLICATION_STARTED', r'\bStarted DonApplication\b'),
    )
    observed = [(match.start(), phase) for phase, pattern in phases
                for match in re.finditer(pattern, logs)]
    failure_types = ('OutOfMemoryError', 'BeanCreationException', 'ApplicationContextException',
                     'SchemaManagementException', 'SQLSyntaxErrorException',
                     'CommunicationsException', 'RedisConnectionFailureException')
    return {'state_observed': bool(state), 'container_state': status,
            'exit_code': exit_code, 'oom_killed': oom, 'reason': reason,
            'last_safe_boot_phase': max(observed)[1] if observed else 'NOT_OBSERVED',
            'known_failure_classes': [kind for kind in failure_types
                                      if re.search(r'\b' + kind + r'\b', logs)]}


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def copy_source(target):
    def excluded(directory, names):
        return [n for n in names if n in ('.git', 'node_modules', '.gradle', 'build', 'dist', '__pycache__')
                or n == '.env' or n.startswith('.env.') and not n.endswith('.example')]
    shutil.copytree(ROOT / 'be', target, ignore=excluded)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache-seed', type=Path, required=True)
    parser.add_argument('--mysql-image', default='mysql:8.4')
    parser.add_argument('--redis-image', default='redis:7.4-alpine')
    parser.add_argument('--interrupt-check', action='store_true', help='Exercise SIGTERM cleanup after services become ready')
    args = parser.parse_args()
    os.umask(0o077)
    work = Path(tempfile.mkdtemp(prefix='moneytoad-render-runtime-'))
    run = uuid.uuid4().hex[:12]
    output = ROOT / 'docs/deployment/evidence/RENDER_RUNTIME' / run
    result = {'status': 'BLOCKED', 'checks': [], 'cleanup_complete': False,
              'managed_provider_contracts_verified': False, 'public_deployment_ready': False,
              'local_render_runtime_ready': False}
    env = {'PATH': '/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin',
           'LANG': 'en_US.UTF-8', 'PYTHONDONTWRITEBYTECODE': '1', 'TMPDIR': str(work)}
    containers = []
    processes = []
    owned_ports = []
    network = 'moneytoad-render-' + run
    ingress_network = 'moneytoad-render-ingress-' + run
    image = 'moneytoad-render-runtime:' + run
    network_created = False
    ingress_created = False
    image_created = False
    context_image_created = False
    context_image = 'moneytoad-render-context:' + run
    docker = ['docker', '--host', 'unix://' + str(Path.home() / '.docker/run/docker.sock'),
              '--config', str(work / 'docker-config')]
    phase = 'prerequisites'
    diagnostics = []
    interrupted = False

    def record(name, ok, **values):
        result['checks'].append({'check': name, 'status': 'PASS' if ok else 'FAIL', **values})
        print(name + ': ' + ('PASS' if ok else 'FAIL'), flush=True)
        if not ok:
            raise CheckFailure(name)

    def command(command_args, *, extra=None, cwd=None, timeout=60, data=None, required=True):
        process = subprocess.Popen(list(map(str, command_args)), cwd=cwd or work,
                                   env={**env, **(extra or {})}, stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True)
        processes.append(process)
        try:
            text, _ = process.communicate(data, timeout=timeout)
        except BaseException:
            terminate(process)
            raise
        if required and process.returncode:
            diagnostics.append(text)
            result['failed_command_exit_code'] = process.returncode
            result['failed_command_tool'] = Path(str(command_args[0])).name
            raise CheckFailure(phase + '-command')
        return subprocess.CompletedProcess(command_args, process.returncode, text)

    def terminate(process):
        # A Gradle/client parent may exit before a child in its owned session.
        # Every process here was launched with start_new_session=True.
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            process.poll()
            return
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            process.poll()
            try:
                os.killpg(process.pid, 0)
            except ProcessLookupError:
                return
            time.sleep(0.1)
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=10)
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            return
        raise CheckFailure('owned-process-group-survived')

    def poll(predicate, seconds=120):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.25)
        raise CheckFailure(phase + '-readiness-deadline')

    def dc(*parts, **kwargs):
        return command(docker + list(parts), **kwargs)

    def remove_container(name):
        inspect = dc('inspect', '--format', '{{index .Config.Labels "' + LABEL + '"}}', name, required=False)
        if inspect.returncode:
            return 'No such' in inspect.stdout
        if inspect.stdout.strip() != run:
            return False
        return dc('rm', '-f', '-v', name, required=False).returncode == 0

    def new_container(name, *parts):
        containers.append(name)
        dc('run', '-d', '--name', name, '--label', LABEL + '=' + run, *parts)

    def stopped(signum, frame):
        nonlocal interrupted
        interrupted = True
        raise KeyboardInterrupt()

    previous = {s: signal.signal(s, stopped) for s in (signal.SIGTERM, signal.SIGINT)}
    try:
        (work / 'docker-config').mkdir()
        java = Path(command(['/usr/libexec/java_home', '-v', '21']).stdout.strip())
        java_version = command([java / 'bin/java', '-version']).stdout
        version_match = re.search(r'version "([0-9.+_-]+)"', java_version)
        if not version_match:
            raise CheckFailure('java-version-not-observed')
        result['host_java_version'] = version_match[1]
        env.update(JAVA_HOME=str(java), PATH=str(java / 'bin') + ':' + env['PATH'], GRADLE_USER_HOME=str(work / 'cache'))
        for part in ('wrapper', 'caches'):
            if not (args.cache_seed / part).is_dir():
                raise CheckFailure('explicit-dependency-cache-missing')
            shutil.copytree(args.cache_seed / part, work / 'cache' / part,
                            ignore=shutil.ignore_patterns('*.lock', '*.lck'))
        for expected in (args.mysql_image, args.redis_image, 'eclipse-temurin:21-jdk', 'eclipse-temurin:21-jre', 'node:22-bookworm-slim'):
            dc('image', 'inspect', expected)
        result['host_architecture'] = platform.machine()
        result['docker_architecture'] = dc('info', '--format', '{{.Architecture}}').stdout.strip()
        record('prerequisites', True)

        phase = 'compile'
        copy_source(work / 'be')
        baseline = work / 'scripts/verification/fixtures/managed-provider-schema.sql'
        baseline.parent.mkdir(parents=True)
        shutil.copy2(ROOT / 'scripts/verification/fixtures/managed-provider-schema.sql', baseline)
        init = work / 'init.gradle'
        init.write_text("""allprojects {
 layout.buildDirectory.set(file(System.getenv('RUNTIME_BUILD')))
 afterEvaluate {
  tasks.register('runtimeClasspathForVerification') {
   dependsOn 'testClasses'
   doLast { file(System.getenv('RUNTIME_CLASSPATH')).text = sourceSets.test.runtimeClasspath.asPath }
  }
  tasks.withType(Test).configureEach {
   if (System.getenv('RUNTIME_JVM_ARGS')) jvmArgs '@' + System.getenv('RUNTIME_JVM_ARGS')
  }
 }
}
""")
        build_env = {'RUNTIME_BUILD': str(work / 'build'), 'RUNTIME_CLASSPATH': str(work / 'classpath')}
        gradle = ['bash', './gradlew', '--offline', '--init-script', str(init), '--project-cache-dir', str(work / 'project-cache'),
                  '--no-daemon', '--console=plain', '--max-workers=2']
        command(gradle + ['compileJava', 'compileTestJava', 'runtimeClasspathForVerification', 'bootJar', 'demoCleanupJar'],
                cwd=work / 'be', extra=build_env, timeout=360)
        classpath = (work / 'classpath').read_text()
        result['dependency_versions'] = sorted({Path(entry).name for entry in classpath.split(os.pathsep)
            if re.fullmatch(r'(spring-boot|hibernate-core|mysql-connector-j|lettuce-core|HikariCP)-[0-9][A-Za-z0-9.\-]*\.jar', Path(entry).name)})
        jar = work / 'build/libs/be-0.0.1-SNAPSHOT.jar'
        record('compile-and-package', jar.is_file())
        phase = 'configuration-tests'
        command(gradle + ['test', '--tests', '*RenderRuntimeConfigurationTest', '--tests', '*RenderDemoBoundaryTest'],
                cwd=work / 'be', extra=build_env, timeout=360)

        def junit_summary(expected):
            suites = []
            actual = {}
            for xml in (work / 'build/test-results/test').glob('TEST-*.xml'):
                suite = ET.parse(xml).getroot()
                suites.append({k: int(suite.get(k, '0')) for k in ('tests', 'failures', 'errors', 'skipped')})
                actual[suite.get('name')] = int(suite.get('tests', '0'))
            if actual != expected:
                raise CheckFailure('exact-test-suite-count-mismatch')
            return {k: sum(s[k] for s in suites) for k in ('tests', 'failures', 'errors', 'skipped')}

        unit = junit_summary({'com.potg.don.global.config.RenderRuntimeConfigurationTest': 80,
                             'com.potg.don.auth.demo.RenderDemoBoundaryTest': 6})
        record('configuration-tests', unit['tests'] > 0 and not any(unit[k] for k in ('failures', 'errors', 'skipped')), **unit)

        phase = 'tls-fixtures'
        tls = work / 'tls'
        tls.mkdir(mode=0o755)
        tls.chmod(0o755)
        for tag in ('trusted', 'untrusted'):
            command(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2',
                     '-subj', '/CN=MoneyToad Local Verification ' + tag,
                     '-keyout', tls / (tag + '-ca.key'), '-out', tls / (tag + '-ca.crt')])
            command(['openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=localhost',
                     '-keyout', tls / (tag + '.key'), '-out', tls / (tag + '.csr')])
            (tls / 'extensions').write_text('subjectAltName=DNS:localhost,DNS:mysql,DNS:redis\nextendedKeyUsage=serverAuth\n')
            command(['openssl', 'x509', '-req', '-in', tls / (tag + '.csr'), '-CA', tls / (tag + '-ca.crt'),
                     '-CAkey', tls / (tag + '-ca.key'), '-CAcreateserial', '-days', '2',
                     '-extfile', tls / 'extensions', '-out', tls / (tag + '.crt')])
        trustpass = secrets.token_hex(20)
        for tag in ('trusted', 'untrusted'):
            command([java / 'bin/keytool', '-importcert', '-noprompt', '-alias', 'verification-ca',
                     '-file', tls / (tag + '-ca.crt'), '-keystore', tls / (tag + '.p12'),
                     '-storetype', 'PKCS12', '-storepass', trustpass])
        for p in tls.iterdir():
            p.chmod(0o644)
        def jvm_args(path, store):
            path.write_text('-Djavax.net.ssl.trustStore=' + str(store) + '\n-Djavax.net.ssl.trustStorePassword=' + trustpass + '\n')
            path.chmod(0o644)
        jvm_args(work / 'host.args', tls / 'trusted.p12')
        jvm_args(work / 'wrong.args', tls / 'untrusted.p12')

        phase = 'owned-services'
        db_port, redis_port, gateway_app_port = free_port(), free_port(), free_port()
        owned_ports.extend((db_port, redis_port, gateway_app_port))
        schema = 'moneytoad_render_' + run
        password, redis_password = secrets.token_hex(24), secrets.token_hex(24)
        root_password = secrets.token_hex(24)
        (work / 'mysql.env').write_text('MYSQL_ROOT_PASSWORD=' + root_password + '\nMYSQL_DATABASE=' + schema
                                      + '\nMYSQL_USER=runtime\nMYSQL_PASSWORD=' + password + '\n')
        (work / 'client.cnf').write_text('[client]\nuser=root\npassword=' + root_password + '\n')
        network_created = True
        dc('network', 'create', '--internal', '--label', LABEL + '=' + run, network)
        ingress_created = True
        dc('network', 'create', '--label', LABEL + '=' + run, ingress_network)
        mysql_name, redis_name = 'moneytoad-render-db-' + run, 'moneytoad-render-redis-' + run
        new_container(mysql_name, '--network', network, '--network-alias', 'mysql', '--network-alias', 'mysql-mismatch',
            '--tmpfs', '/var/lib/mysql:rw,size=512m',
            '--mount', f'type=bind,src={tls},dst=/tls,readonly',
            '--mount', f'type=bind,src={work / "client.cnf"},dst=/run/client.cnf,readonly',
            '--env-file', str(work / 'mysql.env'), args.mysql_image, '--skip-log-bin',
            '--require-secure-transport=ON', '--ssl-ca=/tls/trusted-ca.crt',
            '--ssl-cert=/tls/trusted.crt', '--ssl-key=/tls/trusted.key')

        def sql(query):
            return dc('exec', '-i', mysql_name, 'mysql', '--defaults-extra-file=/run/client.cnf', '--batch',
                      '--skip-column-names', '--default-character-set=utf8mb4', '-D', schema,
                      data=query).stdout.strip()

        # mysqladmin ping can succeed for an access-denied/initialization server.
        # Wait for the final TCP server and the explicitly created schema instead.
        poll(lambda: dc('exec', mysql_name, 'mysql', '--defaults-extra-file=/run/client.cnf',
            '--protocol=TCP', '-h', '127.0.0.1', '--ssl-mode=REQUIRED', '-D', schema,
            '--batch', '--skip-column-names', '-e', 'SELECT 1', required=False).stdout.strip() == '1')
        sql('CREATE DATABASE ' + schema + '_empty; GRANT ALL ON ' + schema + '_empty.* TO runtime;')
        def redis_start(tag='trusted'):
            (work / 'redis.conf').write_text('bind 0.0.0.0\nport 0\ntls-port 6379\ntls-auth-clients no\n'
                + 'tls-cert-file /tls/' + tag + '.crt\ntls-key-file /tls/' + tag + '.key\ntls-ca-cert-file /tls/'
                + tag + '-ca.crt\nsave ""\nappendonly no\nuser default off\nuser runtime on >' + redis_password + ' ~* +@all\n')
            new_container(redis_name, '--network', network, '--network-alias', 'redis', '--network-alias', 'redis-mismatch',
                '--tmpfs', '/data:rw,size=32m', '--user', '0:0',
                '--mount', f'type=bind,src={tls},dst=/tls,readonly',
                '--mount', f'type=bind,src={work / "redis.conf"},dst=/run/redis.conf,readonly',
                '--entrypoint', 'redis-server', args.redis_image, '/run/redis.conf')
            poll(lambda: dc('exec', '-e', 'REDISCLI_AUTH', redis_name, 'redis-cli', '--tls', '--cacert', '/tls/' + tag + '-ca.crt',
                '-h', 'redis', '--user', 'runtime', 'PING', extra={'REDISCLI_AUTH': redis_password}, required=False).stdout.strip() == 'PONG')
        redis_start()
        record('owned-tls-services', True)
        # Docker Desktop does not publish ports from an internal network. A fixed
        # TCP-only ingress preserves end-to-end TLS while the app cannot egress.
        relay = work / 'relay.mjs'
        relay.write_text("""import net from 'node:net';
for (const [port, host] of [[3306, 'mysql'], [6379, 'redis'], [18765, 'app']]) {
  net.createServer(client => {
    const upstream = net.connect({host, port});
    client.on('error', () => upstream.destroy());
    upstream.on('error', () => client.destroy());
    client.on('close', () => upstream.destroy());
    upstream.on('close', () => client.destroy());
    client.pipe(upstream); upstream.pipe(client);
  }).listen(port, '0.0.0.0');
}
""")
        relay.chmod(0o644)
        relay_name = 'moneytoad-render-ingress-' + run
        new_container(relay_name, '--network', ingress_network,
            '-p', f'127.0.0.1:{db_port}:3306', '-p', f'127.0.0.1:{redis_port}:6379',
            '-p', f'127.0.0.1:{gateway_app_port}:18765', '--user', '1000:1000',
            '--mount', f'type=bind,src={relay},dst=/run/relay.mjs,readonly',
            '--entrypoint', 'node', 'node:22-bookworm-slim', '/run/relay.mjs')
        dc('network', 'connect', network, relay_name)
        for service_port in (db_port, redis_port):
            # Host connectivity is distinct from an in-container readiness response.
            def can_connect():
                try:
                    with socket.create_connection(('127.0.0.1', service_port), timeout=2):
                        return True
                except OSError:
                    return False
            poll(can_connect, 15)
        record('host-published-loopback-connectivity', True)
        if args.interrupt_check:
            phase = 'intentional-interrupt'
            os.kill(os.getpid(), signal.SIGTERM)

        url = f'jdbc:mysql://localhost:{db_port}/{schema}?sslMode=VERIFY_IDENTITY&connectionTimeZone=Asia/Seoul'
        runtime = {'SPRING_PROFILES_ACTIVE': 'demo,render', 'APP_DEMO_ENABLED': 'true', 'APP_DEPLOYMENT_KIND': 'public-demo',
            'DEMO_GATEWAY_SECRET': secrets.token_urlsafe(32), 'APP_DEMO_BROWSER_ORIGIN': 'https://localhost', 'PORT': str(free_port()),
            'DB_URL': url, 'DB_USERNAME': 'runtime', 'DB_PASSWORD': password, 'JPA_DDL_AUTO': 'validate',
            'REDIS_HOST': 'localhost', 'REDIS_PORT': str(redis_port), 'REDIS_USERNAME': 'runtime',
            'REDIS_PASSWORD': redis_password, 'REDIS_SSL_ENABLED': 'true',
            'JWT_SECRET': secrets.token_hex(48), 'JWT_ACCESS_SECONDS': '300', 'JWT_REFRESH_SECONDS': '3600',
            'JWT_ISSUER': 'moneytoad-local-render-check', 'AI_BASE_URL': 'http://127.0.0.1:9'}
        test_env = {'RUNNER_RENDER_' + k: runtime[k] for k in ('DB_URL', 'DB_USERNAME', 'DB_PASSWORD',
                    'REDIS_HOST', 'REDIS_PORT', 'REDIS_USERNAME', 'REDIS_PASSWORD', 'JWT_SECRET', 'PORT', 'DEMO_GATEWAY_SECRET')}
        phase = 'schema-preparation'
        command([java / 'bin/java', '@' + str(work / 'host.args'), '-cp', classpath,
                 'com.potg.don.auth.RenderSchemaPreparation'], extra=test_env, timeout=120)
        schema_before = sql('SHOW TABLES;')
        def schema_structure():
            # Ignore only AUTO_INCREMENT's next value; inserts legitimately advance it.
            tables = sql('SHOW TABLES;').splitlines()
            if not all(re.fullmatch(r'[a-z_]+', t) for t in tables):
                raise CheckFailure('unexpected-schema-table-name')
            return [re.sub(r' AUTO_INCREMENT=\d+', '', sql('SHOW CREATE TABLE `' + t + '`;')) for t in sorted(tables)]
        structure_before = schema_structure()
        record('separate-schema-preparation', bool(schema_before))
        # Exercise the independently packaged administrator entrypoint, with
        # an owned private credential file and real identity-verified local TLS.
        phase = 'maintenance-cli'
        cleanup_jar = work / 'build/libs/be-0.0.1-SNAPSHOT-demo-cleanup.jar'
        import zipfile
        with zipfile.ZipFile(jar) as archive:
            record('maintenance-absent-from-runtime-artifact',
                   not any('/com/potg/don/maintenance/' in name for name in archive.namelist()))
        private_cli = work / 'maintenance-input'
        private_cli.mkdir(mode=0o700)
        credential_file = private_cli / 'cleanup.env'
        credential_file.write_text('DB_URL=' + url + '&connectTimeout=5000&socketTimeout=10000&readOnlyPropagatesToServer=false\n'
            + 'DB_USERNAME=runtime\nDB_PASSWORD=' + password + '\nDEMO_MAX_VISITORS=1000\n')
        credential_file.chmod(0o600)
        try:
            for mode, arguments in (('VERIFY', ['--verify']), ('DRY_RUN', [])):
                observed = command([java / 'bin/java', '@' + str(work / 'host.args'), '-jar', cleanup_jar,
                    '--config-file', credential_file, '--schema', schema] + arguments, timeout=30)
                value = json.loads(observed.stdout)
                record('packaged-cleanup-' + mode.lower(), value == {'status': 'PASS', 'mode': mode,
                    'candidates': 0, 'deleted': 0, 'count_before': 0, 'count_after': 0, 'maximum': 1000})
        finally:
            credential_file.unlink(missing_ok=True)
        phase = 'integration-tests'
        command(gradle + ['test', '--tests', '*RenderRuntimeIntegrationTest'], cwd=work / 'be',
                extra={**build_env, **test_env, 'RUNTIME_JVM_ARGS': str(work / 'host.args')}, timeout=360)
        integration = junit_summary({'com.potg.don.auth.RenderRuntimeIntegrationTest': 6})
        record('actual-configdata-tls-integration', integration['tests'] > 0 and not any(integration[k] for k in ('failures', 'errors', 'skipped')), **integration)
        record('validate-did-not-change-schema', schema_structure() == structure_before)

        def counts():
            return [int(x) for x in sql('SELECT (SELECT count(*) FROM users),(SELECT count(*) FROM cards),'
                '(SELECT count(*) FROM transactions),(SELECT count(*) FROM budgets);').split()]

        def http(port, method, path, token=None, timeout=180):
            headers = {'X-MoneyToad-Gateway': runtime['DEMO_GATEWAY_SECRET'], 'X-MoneyToad-Client-IP': '192.0.2.122', 'Origin': 'https://localhost', 'X-MoneyToad-Demo': '1', 'Content-Type': 'application/json'}
            if token:
                headers['Authorization'] = 'Bearer ' + token
            request = urllib.request.Request(f'http://127.0.0.1:{port}/api' + path, method=method,
                data=b'{}' if method == 'POST' else None, headers=headers)
            try:
                response = urllib.request.urlopen(request, timeout=timeout)
            except urllib.error.HTTPError as failure:
                response = failure
            with response:
                return response.status, response.read(), response.headers

        def host_app(overrides=None, wrong_trust=False):
            port = free_port()
            owned_ports.append(port)
            config = {**runtime, 'PORT': str(port), **(overrides or {})}
            log = tempfile.TemporaryFile(mode='w+t')
            process = subprocess.Popen([str(java / 'bin/java'), '@' + str(work / ('wrong.args' if wrong_trust else 'host.args')),
                '-jar', str(jar)], env={**env, **config}, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            processes.append(process)
            return process, log, port

        def host_ready(process, port):
            def ready():
                if process.poll() is not None:
                    raise CheckFailure('packaged-app-premature-exit')
                try:
                    return http(port, 'GET', '/test')[0] == 200
                except OSError:
                    return False
            poll(ready, 120)

        phase = 'jdbc-negative-contracts'
        for case, overrides, wrong_trust, marker in (
            ('jdbc-untrusted-ca', {}, True, r'PKIX|SSLHandshake|trustAnchors'),
            ('jdbc-hostname-mismatch', {'DB_URL': url.replace('localhost', '127.0.0.1')}, False, r'Server identity verification failed|No subject alternative|No name matching|does not match'),
            ('jdbc-wrong-credential', {'DB_PASSWORD': secrets.token_hex(24)}, False, r'Access denied'),
            ('missing-schema-validate', {'DB_URL': url.replace('/' + schema + '?', '/' + schema + '_empty?')}, False, r'Schema-validation.*missing table'),
        ):
            before = counts()
            app, log, port = host_app(overrides, wrong_trust)
            try:
                app.wait(timeout=90)
                log.seek(0)
                text = log.read()
                record(case, app.returncode != 0 and re.search(marker, text, re.I) is not None and counts() == before)
                if case == 'missing-schema-validate':
                    record('missing-schema-remains-empty', not sql('SHOW TABLES FROM ' + schema + '_empty;'))
            finally:
                terminate(app)
                log.close()

        phase = 'redis-negative-contracts'
        for case, overrides, tag in (
            ('redis-untrusted-ca', {}, 'untrusted'),
            ('redis-hostname-mismatch', {'REDIS_HOST': '127.0.0.1'}, 'trusted'),
            ('redis-wrong-credential', {'REDIS_PASSWORD': secrets.token_hex(24)}, 'trusted'),
        ):
            if not remove_container(redis_name):
                raise CheckFailure('redis-replacement-ownership')
            redis_start(tag)
            before = counts()
            kind = 'CA' if tag == 'untrusted' else ('HOSTNAME' if 'hostname' in case else 'CREDENTIAL')
            probe_env = {**test_env, 'RUNNER_REDIS_EXPECTED_FAILURE': kind,
                         **{'RUNNER_RENDER_' + key: value for key, value in overrides.items()}}
            probe = command([java / 'bin/java', '@' + str(work / 'host.args'), '-cp', classpath,
                             'com.potg.don.auth.RenderRedisFailureProbe'], extra=probe_env, timeout=120)
            record(case + '-actual-client-cause', 'RENDER_REDIS_FAILURE: ' + kind in probe.stdout)
            record(case + '-protected-api-no-business',
                   'RENDER_REDIS_PROTECTED: REJECTED_NO_SQL' in probe.stdout)
            app, log, port = host_app(overrides)
            try:
                host_ready(app, port)
                status, body, headers = http(port, 'POST', '/auth/demo/login')
                log.seek(0)
                text = log.read()
                record(case, status == 503 and b'accessToken' not in body and not headers.get('Set-Cookie')
                       and counts() == before, http_status=status, tokens_returned=b'accessToken' in body,
                       database_unchanged=counts() == before)
            finally:
                terminate(app)
                log.close()
        remove_container(redis_name)
        redis_start()

        phase = 'docker-build'
        # Sentinel files are private test fixtures, not product changes or real credentials.
        canaries = ['.env', 'local.key', 'src/main/resources/ignored.key', 'src/main/resources/local.log',
                    'src/main/resources/unreviewed.txt', 'src/main/java/unreviewed.bin',
                    'build/ignored.jar', 'src/test/resources/ignored.txt']
        for name in canaries:
            target = work / 'be' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text('LOCAL_CONTEXT_EXCLUSION_SENTINEL')
        audit_file = work / 'context.Dockerfile'
        audit_file.write_text('FROM eclipse-temurin:21-jre\nCOPY . /context\n')
        context_image_created = True
        dc('build', '--pull=false', '--label', LABEL + '=' + run, '-f', audit_file, '-t', context_image, work / 'be', timeout=120)
        context_name = 'moneytoad-render-context-audit-' + run
        containers.append(context_name)
        context_paths = dc('run', '--name', context_name, '--label', LABEL + '=' + run, '--network', 'none',
            '--entrypoint', 'sh', context_image, '-c', 'find /context -type f').stdout.splitlines()
        approved_context = {'Dockerfile', '.dockerignore', 'gradlew', 'build.gradle', 'settings.gradle',
                            'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties'}
        for pattern in ('src/main/java/**/*.java', 'src/main/resources/application*.yml', 'src/main/resources/redis/*.lua'):
            approved_context.update(str(path.relative_to(work / 'be')) for path in (work / 'be').glob(pattern))
        record('dockerignore-actual-context', bool(context_paths) and all('/context/' + p not in context_paths for p in canaries)
            and set(context_paths) == {'/context/' + path for path in approved_context}
            and '/context/gradlew' in context_paths and '/context/gradle/wrapper/gradle-wrapper.jar' in context_paths
            and '/context/src/main/java/com/potg/don/DonApplication.java' in context_paths
            and '/context/src/main/resources/application-render.yml' in context_paths
            and '/context/src/main/resources/redis/demo-session-create.lua' in context_paths
            and not any('/src/test/' in p for p in context_paths))
        image_created = True
        dc('build', '--pull=false', '--label', LABEL + '=' + run, '--tag', image, str(work / 'be'), timeout=900)
        inspection = json.loads(dc('image', 'inspect', image).stdout)[0]
        result['image_architecture'] = inspection['Architecture']
        result['emulation'] = (result['docker_architecture'].replace('aarch64', 'arm64') != inspection['Architecture'])
        record('runtime-image-contract', inspection['Config']['User'] == '10001:10001'
               and inspection['Config']['Entrypoint'] == ['java', '-jar', '/app/app.jar'])
        audit_name = 'moneytoad-render-image-audit-' + run
        containers.append(audit_name)
        audit = dc('run', '--name', audit_name, '--label', LABEL + '=' + run, '--network', 'none', '--entrypoint', 'sh', image,
            '-c', 'test ! -e /app/src && test ! -e /app/.env && test ! -e /app/gradlew && ! command -v javac && ! command -v gradle && test -f /app/app.jar && java -version', required=False)
        record('runtime-image-no-build-tools-or-source', audit.returncode == 0)
        image_version = re.search(r'version "([0-9.+_-]+)"', audit.stdout)
        record('runtime-java-version-observed', image_version is not None)
        result['runtime_java_version'] = image_version[1]
        dc('cp', audit_name + ':/app/app.jar', str(work / 'runtime.jar'))
        with zipfile.ZipFile(work / 'runtime.jar') as packaged:
            app_entries = [name for name in packaged.namelist() if name.startswith('BOOT-INF/classes/') and not name.endswith('/')]
            unsafe_suffixes = ('.key', '.pem', '.crt', '.p12', '.jks', '.log', '.java', '.csv', '.har', '.zip')
            record('packaged-resources-reviewed', bool(app_entries)
                   and not any(name.endswith(unsafe_suffixes) or '/.env' in name or '/test/' in name for name in app_entries)
                   and packaged.read('BOOT-INF/classes/application-render.yml') == (work / 'be/src/main/resources/application-render.yml').read_bytes())

        phase = 'limited-packaged-app'
        app_name, app_port = 'moneytoad-render-app-' + run, gateway_app_port
        container_env = {**runtime, 'PORT': '18765', 'DB_URL': url.replace(f'localhost:{db_port}', 'mysql:3306'),
                         'REDIS_HOST': 'redis', 'REDIS_PORT': '6379'}
        (work / 'app.env').write_text(''.join(k + '=' + v + '\n' for k, v in container_env.items()))
        # Argument file avoids JVM startup echo of truststore secrets in JAVA_TOOL_OPTIONS.
        jvm_args(tls / 'container.args', '/tls/trusted.p12')
        before = counts()
        start = time.monotonic()
        new_container(app_name, '--network', network, '--network-alias', 'app',
            '--memory', '512m', '--memory-swap', '512m', '--cpus', '0.1', '--env-file', str(work / 'app.env'),
            '--mount', f'type=bind,src={tls / "trusted.p12"},dst=/tls/trusted.p12,readonly',
            '--mount', f'type=bind,src={tls / "container.args"},dst=/tls/container.args,readonly', '--entrypoint', 'java', image,
            '@/tls/container.args', '-jar', '/app/app.jar')
        app_config = json.loads(dc('inspect', app_name).stdout)[0]
        record('actual-container-limits-and-isolation',
               app_config['HostConfig']['Memory'] == 512 * 1024 * 1024
               and app_config['HostConfig']['MemorySwap'] == 512 * 1024 * 1024
               and app_config['HostConfig']['NanoCpus'] == 100_000_000
               and set(app_config['NetworkSettings']['Networks']) == {network}
               and not app_config['HostConfig']['PortBindings']
               and dc('network', 'inspect', '--format', '{{.Internal}}', network).stdout.strip() == 'true')
        observed = []
        monitor_stop = threading.Event()
        def monitor():
            while not monitor_stop.is_set():
                try:
                    stats = subprocess.run(docker + ['stats', '--no-stream', '--format', '{{.MemUsage}}', app_name],
                        env=env, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=5)
                    m = re.match(r'([0-9.]+)([KMG]iB)', stats.stdout)
                    if m:
                        observed.append(float(m[1]) * {'KiB': 1/1024, 'MiB': 1, 'GiB': 1024}[m[2]])
                except (OSError, subprocess.TimeoutExpired):
                    pass
                monitor_stop.wait(0.5)
        observer = threading.Thread(target=monitor, daemon=True)
        observer.start()
        sensitive = [password, root_password, redis_password, runtime['JWT_SECRET'], runtime['DEMO_GATEWAY_SECRET']]
        try:
            def ready():
                state = json.loads(dc('inspect', '--format', '{{json .State}}', app_name).stdout)
                if not state['Running']:
                    raise CheckFailure('limited-app-exited')
                try:
                    return http(app_port, 'GET', '/test')[0] == 200
                except OSError:
                    return False
            # Fixed safety deadline, not a Render performance SLO.
            poll(ready, 600)
            startup = time.monotonic() - start
            # Current FE explicitly gates token POST behind SQL/Redis readiness.
            # Keep the prior raw-/test failure evidence; do not retry any login.
            readiness_start = time.monotonic()
            readiness_checks = 0
            before_readiness = counts()
            backend_ready = False
            while readiness_checks < 80 and time.monotonic() - readiness_start < 240:
                readiness_checks += 1
                try:
                    ready_status, ready_body, ready_headers = http(app_port, 'GET', '/auth/demo/ready', timeout=10)
                    backend_ready = ready_status == 200 and json.loads(ready_body) == {'ready': True}
                    if backend_ready:
                        record('limited-readiness-no-cookie', ready_headers.get('Set-Cookie') is None)
                        break
                except (OSError, ValueError):
                    pass
                threading.Event().wait(min(3, max(0, 240 - (time.monotonic() - readiness_start))))
            result['limited_readiness_observation'] = {
                'attempts': readiness_checks, 'ready': backend_ready,
                'ready_seconds_after_http_bound': round(time.monotonic() - readiness_start, 3),
                'cold_start_to_ready_seconds': round(time.monotonic() - start, 3),
                'same_product_settings': True, 'login_post_retries': 0,
            }
            record('limited-product-readiness', backend_ready and counts() == before_readiness)
            login_seconds = []
            for visitor in range(2):
                then = time.monotonic()
                status, body, headers = http(app_port, 'POST', '/auth/demo/login')
                login_seconds.append(round(time.monotonic() - then, 3))
                token = json.loads(body).get('accessToken') if status == 201 else None
                if status != 201 or not token:
                    payload = json.loads(body)
                    safe_codes = {'DEMO_CAPACITY_FULL', 'DEMO_ADMISSION_BUSY', 'DEMO_ADMISSION_UNAVAILABLE'}
                    logs = dc('logs', app_name).stdout
                    result['limited_login_failure'] = {
                        'http_status': status,
                        'admission_code': payload.get('code') if payload.get('code') in safe_codes else None,
                        'tokens_returned': bool(token),
                        'sql_row_delta': [b-a for a,b in zip(before, counts())],
                        'duration_seconds': login_seconds[-1],
                        'cause_classes': sorted(set(re.findall(r'\b[A-Za-z]+(?:Exception|Error)\b', logs)))[:30],
                    }
                record('limited-login-' + str(visitor + 1), status == 201 and bool(token))
                sensitive.append(token)
                cookie = headers.get('Set-Cookie', '')
                record('limited-secure-cookie-' + str(visitor + 1), 'HttpOnly' in cookie and 'Secure' in cookie
                    and 'SameSite=Lax' in cookie and 'Path=/api/auth/demo' in cookie and 'Domain=' not in cookie)
                if cookie:
                    sensitive.append(cookie.split(';')[0].split('=', 1)[1])
                claims = json.loads(base64.urlsafe_b64decode(token.split('.')[1] + '=='))
                sensitive.append(claims['sid'])
                record('limited-session-' + str(visitor + 1), http(app_port, 'GET', '/auth/demo/session', token)[0] == 200)
                record('limited-protected-' + str(visitor + 1), http(app_port, 'GET', '/users', token)[0] == 200)
                record('limited-logout-' + str(visitor + 1), http(app_port, 'POST', '/auth/demo/logout', token)[0] == 204)
                record('limited-revoked-access-' + str(visitor + 1), http(app_port, 'GET', '/users', token)[0] == 401)
            after = counts()
            record('limited-seed-row-delta', [b-a for a,b in zip(before, after)] == [2, 2, 480, 144])
            sensitive.extend(sql('SELECT email FROM users;').splitlines())
            logs = dc('logs', app_name).stdout
            record('packaged-auth-logs-no-runtime-identifiers', not any(v and v in logs for v in sensitive)
                   and not re.search(r'(?i)(Successfully authenticated user:|refreshHash|Authorization:|Cookie:)', logs))
            dc('stop', '--time', '20', app_name)
            state = json.loads(dc('inspect', '--format', '{{json .State}}', app_name).stdout)
            record('limited-app-normal-shutdown', not state['OOMKilled'] and state['ExitCode'] in (0, 143))
            result['local_resource_observation'] = {'memory_limit_mib': 512, 'cpu_limit': 0.1,
                'maximum_observed_memory_mib': round(max(observed), 2) if observed else None,
                'cold_start_seconds': round(startup, 3), 'login_seconds': login_seconds,
                'oom_killed': state['OOMKilled'], 'exit_code': state['ExitCode'],
                'managed_latency_simulated': False, 'render_equivalence_claimed': False,
                'startup_safety_deadline_seconds': 600}
            record('memory-observation-present', bool(observed))
        except BaseException:
            # Preserve a bounded safe observation BEFORE finally removes the
            # owned container. Inspection/log failures must not hide the cause.
            result['limited_failure_observation'] = limited_runtime_projection({}, '')
            result['limited_failure_observation']['capture_status'] = 'UNAVAILABLE'
            try:
                inspection = dc('inspect', app_name, timeout=10, required=False)
                inspected = json.loads(inspection.stdout)[0] if inspection.returncode == 0 else {}
                if inspected.get('Config', {}).get('Labels', {}).get(LABEL) == run:
                    result['limited_failure_observation'] = limited_runtime_projection(inspected.get('State', {}), '')
                    result['limited_failure_observation']['capture_status'] = 'STATE_ONLY'
                    log_result = dc('logs', '--tail', '160', app_name, timeout=10, required=False)
                    if log_result.returncode == 0:
                        result['limited_failure_observation'] = limited_runtime_projection(
                            inspected.get('State', {}), log_result.stdout)
                        result['limited_failure_observation']['capture_status'] = 'STATE_AND_BOUNDED_LOGS'
            except (OSError, ValueError, KeyError, IndexError, subprocess.SubprocessError):
                pass
            raise
        finally:
            monitor_stop.set()
            observer.join(timeout=10)
        result['status'] = 'PASS'
    except KeyboardInterrupt:
        result['status'] = 'INTERRUPTED'
        result['interruption_exercised'] = phase == 'intentional-interrupt'
    except Exception as failure:
        result['status'] = 'FAIL' if isinstance(failure, CheckFailure) else 'BLOCKED'
        result['failed_phase'] = phase
        result['failure_class'] = type(failure).__name__
        if isinstance(failure, CheckFailure) and re.fullmatch(r'[a-z0-9-]+', str(failure)):
            result['failed_check'] = str(failure)
        # Details remain private and are deleted on cleanup; never print raw command output.
        print('stopped: ' + phase + ' (' + type(failure).__name__ + ')', flush=True)
        if diagnostics:
            # Only compiler source locations and diagnostic categories are reviewable.
            combined = '\n'.join(diagnostics)
            result['diagnostic_categories'] = sorted(set(re.findall(r'RENDER_RUNTIME: [A-Z_]+|ERROR [0-9]+|(?:[A-Za-z]+Exception)|cannot find symbol|incompatible types|permission denied|invalid mount config|Connection refused|Network is unreachable|No route to host', combined)))[:30]
        result['test_failures'] = []
        for path in (work / 'build/test-results/test').glob('TEST-*.xml'):
            tree = ET.parse(path).getroot()
            for case in tree.findall('testcase'):
                error = case.find('failure')
                if error is not None:
                    result['test_failures'].append({'test': re.sub(r'[^A-Za-z0-9_$]', '_', case.get('name', 'unknown')),
                        'failure_class': error.get('type', 'unknown'),
                        'tags': sorted(set(re.findall(r'RENDER_RUNTIME: [A-Z_]+|(?:[A-Za-z]+Exception)', error.text or '')))[:20]})
    finally:
        for s in previous:
            signal.signal(s, signal.SIG_IGN)
        cleaned = True
        for process in reversed(processes):
            try:
                terminate(process)
            except Exception:
                cleaned = False
        for name in reversed(list(dict.fromkeys(containers))):
            try:
                cleaned = remove_container(name) and cleaned
            except Exception:
                cleaned = False
        if network_created:
            try:
                owned = dc('network', 'inspect', '--format', '{{index .Labels "' + LABEL + '"}}', network).stdout.strip() == run
                cleaned = owned and dc('network', 'rm', network, required=False).returncode == 0 and cleaned
            except Exception:
                cleaned = False
        if ingress_created:
            try:
                owned = dc('network', 'inspect', '--format', '{{index .Labels "' + LABEL + '"}}', ingress_network).stdout.strip() == run
                cleaned = owned and dc('network', 'rm', ingress_network, required=False).returncode == 0 and cleaned
            except Exception:
                cleaned = False
        if image_created:
            try:
                owned = dc('image', 'inspect', '--format', '{{index .Config.Labels "' + LABEL + '"}}', image).stdout.strip() == run
                cleaned = owned and dc('image', 'rm', image, required=False).returncode == 0 and cleaned
            except Exception:
                cleaned = False
        if context_image_created:
            try:
                owned = dc('image', 'inspect', '--format', '{{index .Config.Labels "' + LABEL + '"}}', context_image).stdout.strip() == run
                cleaned = owned and dc('image', 'rm', context_image, required=False).returncode == 0 and cleaned
            except Exception:
                cleaned = False
        # Reconcile labels as well as remembered names, including partial creation.
        try:
            residual = [dc('ps', '-aq', '--filter', 'label=' + LABEL + '=' + run).stdout.strip(),
                        dc('network', 'ls', '-q', '--filter', 'label=' + LABEL + '=' + run).stdout.strip(),
                        dc('image', 'ls', '-q', '--filter', 'label=' + LABEL + '=' + run).stdout.strip()]
            result['owned_docker_resources_remaining'] = sum(bool(value) for value in residual)
            cleaned = not any(residual) and cleaned
        except Exception:
            cleaned = False
        released = True
        for owned_port in owned_ports:
            try:
                with socket.create_connection(('127.0.0.1', owned_port), timeout=0.2):
                    released = False
            except OSError:
                pass
        result['owned_ports_released'] = released
        cleaned = released and cleaned
        # Temporary TLS keys, credentials and argument files are owned resources too.
        try:
            shutil.rmtree(work)
            cleaned = not work.exists() and cleaned
        except OSError:
            cleaned = False
        result['cleanup_complete'] = cleaned
        result['local_render_runtime_ready'] = result['status'] == 'PASS' and cleaned
        if not cleaned:
            result['status'] = 'FAIL'
        if forbidden(result):
            result = {'status': 'FAIL', 'failure_class': 'EvidenceProjectionRejected', 'cleanup_complete': cleaned,
                      'local_render_runtime_ready': False, 'managed_provider_contracts_verified': False, 'public_deployment_ready': False}
        output.mkdir(parents=True, exist_ok=False)
        (output / 'runtime-summary.json').write_text(json.dumps(result, indent=2) + '\n')
        # No raw logs, TLS materials, credentials or manifests are public artifacts.
        for s, handler in previous.items():
            signal.signal(s, handler)
    return 0 if result['local_render_runtime_ready'] or args.interrupt_check and interrupted and result['cleanup_complete'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
