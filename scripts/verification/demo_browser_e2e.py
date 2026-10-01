#!/usr/bin/env python3
"""Real browser/product HTTP E2E. Creates and removes only run-owned local resources.

No image pulls, ambient env loading, HTTP payload logs or shared services. Browser
provisioning is a separate explicit step. A failed browser scenario stops the run.
"""
from public_evidence import browser as project_browser, browser_series, save
import argparse
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
FE = ROOT / 'fe'
OUT = Path(tempfile.mkdtemp(prefix='moneytoad-browser-artifacts-'))
LABEL = 'moneytoad.verification.browser'


class Blocked(Exception):
    pass


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def poll(check, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.25)
    raise Blocked('readiness deadline exceeded')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=int, default=2, choices=(1, 2))
    parser.add_argument('--cache-seed', type=Path, required=True)
    parser.add_argument('--browser-path', type=Path, required=True)
    args = parser.parse_args()
    if not args.cache_seed.is_dir():
        raise SystemExit('Explicit existing verification Gradle cache required')
    runs = []
    # Replace an older series immediately; an interrupted run cannot leave a stale PASS.
    save('demo-browser-e2e-summary.json', browser_series(runs, args.runs, expected_cases=6))
    try:
        for _ in range(args.runs):
            result = run_once(args, runs)
            if result != 0:
                return result
        return 0 if browser_series(runs, args.runs, expected_cases=6)['status'] == 'PASS' else 1
    finally:
        save('demo-browser-e2e-summary.json', browser_series(runs, args.runs, expected_cases=6))


def run_once(args, runs, *, modes=('public-demo', 'local-demo'),
             browser_config='playwright.config.ts', expected_cases=3, browser_extra=None,
             copy_screenshots=True, cold_start_fixture=False):
    run_id = uuid.uuid4().hex[:12]
    dest = OUT / run_id
    dest.mkdir(parents=True)
    work = Path(tempfile.mkdtemp(prefix='moneytoad-browser-'))
    work.chmod(0o700)
    result = {'run_id': run_id, 'status': 'BLOCKED', 'resources': [], 'checks': [], 'cleanup': [],
              'asset_debt': 'Product system fonts; no test-only font transform or downloaded font.',
              'product_api_mocks': False, 'retries': 0}
    env = {'PATH': '/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin', 'LANG': 'en_US.UTF-8',
           'TMPDIR': str(work), 'NO_COLOR': '1', 'CI': '1', 'PLAYWRIGHT_NO_COPY_PROMPT': '1'}
    owned = []
    bound_ports = []
    containers = []
    controls = []
    result_code = 2
    docker = []
    phase = 'preflight'

    def command(cmd, *, timeout=120, cwd=ROOT, extra=None):
        return subprocess.run([str(c) for c in cmd], cwd=cwd, env={**env, **(extra or {})},
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)

    def required(cmd, **kwargs):
        completed = command(cmd, **kwargs)
        if completed.returncode:
            # Exception names only: never copy runtime output, SQL rows, headers or credentials.
            classes = sorted(set(re.findall(r'\b[A-Z][A-Za-z]+(?:Exception|Error)\b', completed.stdout)))
            result['failure_classes'] = classes
            labels = {'mount': 'MOUNT', 'address already in use': 'PORT', 'memory': 'MEMORY', 'permission denied': 'PERMISSION', 'no space': 'DISK', 'no such': 'MISSING_RESOURCE', 'container name': 'CONTAINER_NAME', 'connection refused': 'CONNECTION'}
            result['failure_tags'] = [tag for marker, tag in labels.items() if marker in completed.stdout.lower()]
            raise Blocked('command failed in ' + phase)
        return completed.stdout.strip()

    def spawn(cmd, name, extra=None, cwd=ROOT):
        process = subprocess.Popen([str(c) for c in cmd], cwd=cwd, env={**env, **(extra or {})},
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        owned.append((process, name))
        result['resources'].append({'kind': name, 'pid': process.pid, 'process_group': process.pid})
        return process

    def stop(process, name):
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
        # A leader can exit before a child; only its originally owned group is eligible.
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            removed = True
        else:
            os.killpg(process.pid, signal.SIGKILL)
            removed = False
            for _ in range(20):
                try:
                    os.killpg(process.pid, 0)
                except ProcessLookupError:
                    removed = True
                    break
                time.sleep(0.1)
        result['cleanup'].append({'kind': name, 'pid': process.pid, 'removed': removed})

    def interrupted(signum, frame):
        raise KeyboardInterrupt()

    previous = signal.signal(signal.SIGTERM, interrupted)
    try:
        java = Path(required(['/usr/libexec/java_home', '-v', '21']))
        env.update(JAVA_HOME=str(java), PATH=str(java / 'bin') + ':' + env['PATH'], GRADLE_USER_HOME=str(work / 'gradle-cache'))
        for part in ('wrapper', 'caches'):
            shutil.copytree(args.cache_seed / part, work / 'gradle-cache' / part,
                            ignore=shutil.ignore_patterns('*.lock', '*.lck'))
        for name, cmd in [('java', [java / 'bin/java', '-version']), ('node', ['node', '--version']),
                          ('redis', ['redis-server', '--version'])]:
            result[name] = required(cmd).splitlines()[0]
        docker_socket = Path.home() / '.docker/run/docker.sock'
        if not docker_socket.is_socket():
            raise Blocked('local Docker Unix socket absent')
        (work / 'docker-config').mkdir()
        docker = ['docker', '--host', 'unix://' + str(docker_socket), '--config', str(work / 'docker-config')]
        required(docker + ['info', '--format', '{{.ServerVersion}}'])
        image = required(docker + ['image', 'ls', '--filter', 'reference=mysql:8.4', '--format', '{{.ID}}'])
        if not re.fullmatch('[a-f0-9]{12,64}', image):
            raise Blocked('one existing local mysql:8.4 image required; no pull')
        result['mysql_image'] = image
        if not args.browser_path.is_dir():
            raise Blocked('dedicated Playwright Chromium must be provisioned first')
        phase = 'compile'
        init = work / 'browser.init.gradle'
        init.write_text('''allprojects {
  layout.buildDirectory.set(file(System.getenv('E2E_BUILD')))
  afterEvaluate {
    tasks.register('browserClasspath') {
      dependsOn 'testClasses'
      doLast { file(System.getenv('E2E_CLASSPATH')).text = sourceSets.test.runtimeClasspath.asPath }
    }
  }
}
''')
        buildenv = {'E2E_BUILD': str(work / 'build'), 'E2E_CLASSPATH': str(work / 'classpath')}
        required(['bash', './gradlew', 'compileJava', 'compileTestJava', 'browserClasspath', '--offline',
                  '--init-script', init, '--project-cache-dir', work / 'project-cache', '--no-daemon',
                  '--console=plain', '--max-workers=2'], cwd=ROOT / 'be', extra=buildenv, timeout=300)
        result['checks'].append({'phase': phase, 'status': 'PASS'})
        for mode in modes:
            phase = mode + '-setup'
            print(f'{run_id}: {phase}', flush=True)
            directory = work / mode
            directory.mkdir(mode=0o700)
            evidence = dest / mode
            evidence.mkdir()
            name = 'moneytoad-browser-' + run_id + ('-https' if mode == 'public-demo' else '-http')
            schema = 'browser_' + run_id
            password = secrets.token_hex(24)
            config = directory / 'mysql.env'
            config.write_text(f'MYSQL_ROOT_PASSWORD={password}\nMYSQL_DATABASE={schema}\n')
            config.chmod(0o600)
            client = directory / 'client.cnf'
            client.write_text(f'[client]\nuser=root\npassword={password}\n')
            client.chmod(0o600)
            dbport, redisport, feport, probeport = port(), port(), port(), port()
            bound_ports.extend([dbport, redisport, feport, probeport])
            containers.append(name)  # docker run failure can still leave an owned container.
            cid = required(docker + ['run', '--detach', '--name', name, '--label', LABEL + '=' + run_id,
                '--publish', f'127.0.0.1:{dbport}:3306', '--memory', '768m', '--cpus', '2',
                '--tmpfs', '/var/lib/mysql:rw,size=512m', '--env-file', config,
                '--mount', f'type=bind,src={client},dst=/run/browser.cnf,readonly', image, '--skip-log-bin'])
            label = required(docker + ['inspect', '--format', '{{index .Config.Labels "' + LABEL + '"}}', name])
            if label != run_id:
                raise Blocked('MySQL ownership mismatch')
            bindings = json.loads(required(docker + ['inspect', '--format', '{{json .HostConfig.PortBindings}}', name]))
            if bindings != {'3306/tcp': [{'HostIp': '127.0.0.1', 'HostPort': str(dbport)}]}:
                raise Blocked('MySQL loopback binding mismatch')
            result['resources'].append({'kind': 'mysql', 'container_id': cid, 'name': name, 'ownership_label': run_id, 'port': dbport})
            mysql = docker + ['exec', name, 'mysql', '--defaults-extra-file=/run/browser.cnf',
                '--default-character-set=utf8mb4', '--batch', '--skip-column-names', '-D', schema, '-e']
            redis = spawn(['redis-server', '--bind', '127.0.0.1', '--port', redisport, '--save', '',
                           '--appendonly', 'no', '--dir', directory], 'redis-' + mode)
            rediscli = ['redis-cli', '-h', '127.0.0.1', '-p', str(redisport)]
            poll(lambda: command(mysql + ['SELECT 1'], timeout=5).returncode == 0 and redis.poll() is None and
                 command(rediscli + ['PING'], timeout=2).stdout.strip() == 'PONG')
            if required(mysql + ['SHOW TABLES']):
                raise Blocked('owned schema must start empty')
            for ddl in (ROOT / 'scripts/verification/fixtures/managed-provider-schema.sql',
                        ROOT / 'be/src/main/resources/db/demo/V001__demo_admission.sql',
                        ROOT / 'be/src/main/resources/db/demo/V002__demo_admission_lock.sql'):
                required(mysql + [ddl.read_text()])
            origin = ('https' if mode == 'public-demo' else 'http') + f'://127.0.0.1:{feport}'
            gateway_value = secrets.token_urlsafe(32)  # Owned synthetic local fixture, not a deployment secret.
            runtime = {
                'SPRING_CONFIG_LOCATION': (ROOT / 'be/src/main/resources/application.yml').as_uri(),
                'SPRING_PROFILES_ACTIVE': 'demo', 'APP_DEMO_ENABLED': 'true', 'APP_DEPLOYMENT_KIND': mode,
                'DEMO_GATEWAY_SECRET': gateway_value, 'APP_DEMO_BROWSER_ORIGIN': origin, 'DEMO_BIND_ADDRESS': '127.0.0.1', 'SERVER_PORT': '0',
                'DB_URL': f'jdbc:mysql://127.0.0.1:{dbport}/{schema}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul',
                'DB_USERNAME': 'root', 'DB_PASSWORD': password, 'JPA_DDL_AUTO': 'validate',
                'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': str(redisport),
                'JWT_SECRET': secrets.token_hex(48), 'JWT_ACCESS_SECONDS': '300', 'JWT_REFRESH_SECONDS': '3600',
                'JWT_ISSUER': 'moneytoad-browser', 'AI_BASE_URL': f'http://127.0.0.1:{feport}',
                'LOGGING_LEVEL_ROOT': 'OFF', 'SPRING_MAIN_BANNER_MODE': 'off', 'E2E_CONTROL_DIR': str(directory),
            }
            app = spawn([java / 'bin/java', '-cp', (work / 'classpath').read_text(),
                         'com.potg.verification.browser.DemoBrowserApplication'], 'spring-' + mode, runtime)
            def app_ready():
                if app.poll() is not None:
                    raise Blocked('product context exited before readiness')
                return (directory / 'ready').exists()
            poll(app_ready)
            beport = int((directory / 'ready').read_text())
            bound_ports.append(beport)
            def health():
                try:
                    with urllib.request.urlopen(f'http://127.0.0.1:{beport}/api/test', timeout=2) as response:
                        return response.read() == b'Hello World'
                except OSError:
                    return False
            poll(health, 15)
            result['checks'].append({'phase': mode + '-context', 'status': 'PASS', 'security_invariants': True})
            if mode == 'public-demo':
                required(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                    '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1,DNS:localhost',
                    '-keyout', directory / 'key.pem', '-out', directory / 'cert.pem'])
            settings = {'E2E_GATEWAY_VALUE': gateway_value, 'E2E_WORK': str(directory), 'E2E_ORIGIN': origin, 'E2E_PROBE_PORT': str(probeport),
                'E2E_BACKEND': f'http://127.0.0.1:{beport}', 'E2E_RUN_ID': run_id,
                'VITE_AUTH_MODE': 'demo', 'VITE_BACK_URL': origin, 'E2E_ARTIFACTS': str(evidence),
                'PLAYWRIGHT_BROWSERS_PATH': str(args.browser_path)}
            if cold_start_fixture:
                settings['E2E_COLD_START_FIXTURE'] = '1'
            frontend = spawn(['node', '--experimental-strip-types', 'e2e/preview.ts'], 'preview-' + mode, settings, FE)
            def front_ready():
                if frontend.poll() is not None:
                    raise Blocked('E2E build/preview exited before readiness')
                return (directory / 'preview-ready').exists()
            poll(front_ready)
            # Server closure captures this phase's dedicated resources only.
            class Control(http.server.BaseHTTPRequestHandler):
                def log_message(self, *args):
                    pass

                def do_POST(self):
                    # Run-owned loopback control, never a browser or product endpoint.
                    if (not cold_start_fixture or mode != 'public-demo' or self.path != '/cold-ready'
                            or self.headers.get('Origin') is not None or self.headers.get('Content-Length', '0') != '0'):
                        self.send_error(404)
                        return
                    marker = directory / 'cold-ready'
                    marker.write_text('ready')
                    marker.chmod(0o600)
                    self.send_response(204)
                    self.end_headers()

                def do_GET(self):
                    if self.path != '/snapshot':
                        self.send_error(404)
                        return
                    sql = """SELECT
                      (SELECT COUNT(*) FROM users),(SELECT COUNT(*) FROM cards),
                      (SELECT COUNT(*) FROM transactions),(SELECT COUNT(*) FROM budgets),
                      (SELECT COUNT(*) FROM cards WHERE card_no IS NOT NULL OR cvc IS NOT NULL),
                      (SELECT COUNT(*) FROM analysis_job),
                      (SELECT COUNT(*) FROM transactions WHERE merchant_name='합성 장보기(분류 연습)' AND category='마트 / 편의점');"""
                    values = [int(x) for x in required(mysql + [sql]).split()]
                    data = dict(zip(['users', 'cards', 'transactions', 'budgets', 'financial', 'jobs', 'changed'], values))
                    data['sessions'] = int(required(rediscli + ['DBSIZE']))
                    data['outbound'] = int((directory / 'outbound').read_text())
                    gateway = directory / 'gateway.jsonl'
                    entries = [json.loads(line) for line in gateway.read_text().splitlines()] if gateway.exists() else []
                    data['options'] = sum(e['method'] == 'OPTIONS' and 'status' not in e for e in entries)
                    data['optionsRejected'] = sum(e['method'] == 'OPTIONS' and e.get('status') == 403 for e in entries)
                    data['loginPosts'] = sum(e['method'] == 'POST' and e['path'] == '/api/auth/demo/login' and 'status' not in e for e in entries)
                    body = json.dumps(data).encode()
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.end_headers()
                    self.wfile.write(body)
            control = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Control)
            controls.append(control)
            bound_ports.append(control.server_port)
            thread = threading.Thread(target=control.serve_forever, daemon=True)
            thread.start()
            settings['E2E_CONTROL'] = f'http://127.0.0.1:{control.server_port}'
            settings.update(browser_extra or {})
            phase = mode + '-browser'
            print(f'{run_id}: {phase}', flush=True)
            # Own the runner group so timeout/interrupt also reaches Chromium children.
            browser = spawn(['./node_modules/.bin/playwright', 'test', '--config', browser_config],
                            'chromium-' + mode, settings, FE)
            try:
                code = browser.wait(timeout=240)
            except subprocess.TimeoutExpired:
                raise Blocked('browser deadline exceeded') from None
            tests_path = evidence / 'tests.json'
            tests = json.loads(tests_path.read_text()) if tests_path.exists() else {'status': 'missing'}
            passed = code == 0 and tests['status'] == 'passed' and len(tests.get('cases', [])) == expected_cases and all(
                row['status'] == 'passed' for row in tests.get('cases', []))
            result['checks'].append({'phase': phase, 'status': 'PASS' if passed else 'FAIL', 'exit_code': code})
            if not passed:
                result['status'] = 'FAIL'
                result_code = 1
                break
            control.shutdown()
            control.server_close()
            controls.remove(control)
            for process, name_ in [(browser, 'chromium-' + mode), (frontend, 'preview-' + mode), (app, 'spring-' + mode), (redis, 'redis-' + mode)]:
                stop(process, name_)
                owned.remove((process, name_))
        else:
            result['status'] = 'PASS'
            result_code = 0
    except (Blocked, OSError, ValueError, subprocess.TimeoutExpired) as error:
        result['reason'] = str(error) if isinstance(error, Blocked) else type(error).__name__
        result['failed_phase'] = phase
    except KeyboardInterrupt:
        result['reason'] = 'Interrupted'
        result_code = 130
    finally:
        for control in controls:
            control.shutdown()
            control.server_close()
        for process, name in reversed(owned):
            try:
                stop(process, name)
            except (OSError, subprocess.TimeoutExpired):
                result['cleanup'].append({'kind': name, 'removed': False})
        for file in work.rglob('browser-owned.jsonl'):
            for line in file.read_text().splitlines():
                pid = json.loads(line)['pid']
                removed = False
                try:
                    os.killpg(pid, 0)
                except ProcessLookupError:
                    removed = True
                else:
                    # Playwright launchServer owns a detached process group on macOS.
                    os.killpg(pid, signal.SIGTERM)
                    for _ in range(40):
                        try:
                            os.killpg(pid, 0)
                        except ProcessLookupError:
                            removed = True
                            break
                        time.sleep(0.1)
                result['resources'].append({'kind': 'chromium-browser', 'pid': pid, 'process_group': pid})
                result['cleanup'].append({'kind': 'chromium-browser', 'pid': pid, 'removed': removed})
        for name in reversed(containers):
            try:
                found = command(docker + ['inspect', '--format', '{{index .Config.Labels "' + LABEL + '"}}', name])
                if found.returncode == 0 and found.stdout.strip() == run_id:
                    command(docker + ['stop', '--time', '10', name], timeout=30)
                    removed = command(docker + ['rm', name]).returncode == 0
                else:
                    removed = found.returncode != 0 and 'No such' in found.stdout
                result['cleanup'].append({'kind': 'mysql', 'name': name, 'removed': removed})
            except (OSError, subprocess.TimeoutExpired):
                result['cleanup'].append({'kind': 'mysql', 'name': name, 'removed': False})
        for selected_port in sorted(set(bound_ports)):
            with socket.socket() as probe_socket:
                probe_socket.settimeout(0.3)
                released = probe_socket.connect_ex(('127.0.0.1', selected_port)) != 0
            result['cleanup'].append({'kind': 'loopback-port', 'port': selected_port, 'removed': released})
        result['cleanup_complete'] = all(row['removed'] for row in result['cleanup'])
        if not result['cleanup_complete']:
            result['status'] = 'BLOCKED'
            result_code = 2
        result['work_directory'] = str(work)
        result['exit_code'] = result_code
        # Remove only secret material created by this runner; preserve nonsecret diagnostic build files.
        for pattern in ('mysql.env', 'client.cnf', 'key.pem'):
            for file in work.rglob(pattern):
                file.unlink(missing_ok=True)
        (work / 'ownership.json').write_text(json.dumps({'resources': result['resources'], 'cleanup': result['cleanup'], 'snapshot': str(ROOT)}, indent=2))
        projected = project_browser(result, dest)
        filename = 'browser-' + run_id + '-summary.json'
        save(filename, projected)
        runs.append((filename, projected))
        # Only already-masked, final successful images enter the public artifact set.
        if result['status'] == 'PASS' and copy_screenshots:
            for image_name in ('chart-before.png', 'chart-after.png', 'chart-restored.png', 'chart-mobile.png', 'logout.png'):
                image = dest / 'public-demo' / image_name
                if image.is_file():
                    shutil.copy2(image, ROOT / 'docs/portfolio/evidence' / ('selected-' + run_id + '-' + image_name))
        signal.signal(signal.SIGTERM, previous)
        print(json.dumps({'run_id': run_id, 'status': result['status'], 'cleanup': result['cleanup_complete'], 'public_projection': True}), flush=True)
    return result_code


if __name__ == '__main__':
    raise SystemExit(main())
