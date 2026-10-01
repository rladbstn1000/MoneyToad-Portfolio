#!/usr/bin/env python3
"""Rehearse the managed probe with private TLS MySQL/Redis only, never cloud inputs.

The explicit cache is copied read-only; source/DDL/credentials/PKI stay in a
private directory. Resources are label-owned and cleaned in every exit path.
Output contains only the sanitized probe projection and exact JUnit counts.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
from managed_provider_check import compile_probe
from public_evidence import forbidden

def main():
    os.umask(63)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache-seed', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    work = Path(tempfile.mkdtemp(prefix='moneytoad-provider-rehearsal-')).resolve()
    run = uuid.uuid4().hex[:16]
    label = 'moneytoad.managed-probe.rehearsal'
    containers = []
    procs = []
    networks = []
    ports = []
    base = ['docker', '--host', 'unix://' + str(Path.home() / '.docker/run/docker.sock'), '--config', str(work / 'docker-config')]
    (work / 'docker-config').mkdir(mode=448)
    base_env = {'PATH': '/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin', 'LANG': 'en_US.UTF-8', 'TMPDIR': str(work), 'PYTHONDONTWRITEBYTECODE': '1'}
    previous = {}
    summary = {'status': 'FAIL', 'provider_connections': 0}
    phase = 'prerequisites'

    def command(parts, data=None, allow=False, env=None, timeout=60, cwd=None):
        p = subprocess.Popen(list(map(str, parts)), cwd=cwd or work, stdin=subprocess.PIPE if data else subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env={**base_env, **(env or {})}, start_new_session=True)
        procs.append(p)
        try:
            (o, _) = p.communicate(data, timeout=timeout)
        except BaseException:
            os.killpg(p.pid, signal.SIGTERM)
            try:
                p.communicate(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(p.pid, signal.SIGKILL)
                p.communicate()
            raise
        if p.returncode and (not allow):
            raise RuntimeError(phase)
        return (p.returncode, o)

    def docker(*args, **kw):
        return command(base + list(map(str, args)), **kw)

    def owned(name, *args):
        containers.append(name)
        return docker('run', '-d', '--name', name, '--label', label + '=' + run, '--pull', 'never', *args)

    def free():
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0))
            return s.getsockname()[1]

    def poll(fn, seconds=120):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if fn():
                return
            time.sleep(0.3)
        raise RuntimeError(phase + '-readiness')
    try:

        def interrupted(signum, frame):
            raise KeyboardInterrupt()
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous[signum] = signal.signal(signum, interrupted)
        phase = 'compile'
        (java_home, gradle_env, classpath, source) = compile_probe(work, args.cache_seed.resolve())
        base_env.update(JAVA_HOME=str(java_home), GRADLE_USER_HOME=str(work / 'cache'))
        phase = 'safety-tests'
        command(['bash', './gradlew', '--offline', '--no-daemon', '--console=plain', '--max-workers=2', '--project-cache-dir', str(work / 'project-cache'), '--init-script', str(work / 'init.gradle'), 'test', '--tests', 'com.potg.verification.managed.ManagedProviderProbeSafetyTest'], env=gradle_env, timeout=180, cwd=source / 'be')
        xml = ET.parse(work / 'build/test-results/test/TEST-com.potg.verification.managed.ManagedProviderProbeSafetyTest.xml').getroot()
        unit = {k: int(xml.attrib[k]) for k in ('tests', 'failures', 'errors', 'skipped')}
        if unit != {'tests': 28, 'failures': 0, 'errors': 0, 'skipped': 0}:
            raise RuntimeError('unit-contract')
        summary['safety_tests'] = unit
        phase = 'tls'
        tls = work / 'tls'
        tls.mkdir(mode=493)
        command(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=Owned Local Check CA', '-keyout', tls / 'ca.key', '-out', tls / 'ca.crt'])
        command(['openssl', 'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=localhost', '-keyout', tls / 'server.key', '-out', tls / 'server.csr'])
        (tls / 'extensions').write_text('subjectAltName=DNS:localhost,DNS:mysql,DNS:redis\nextendedKeyUsage=serverAuth\n')
        command(['openssl', 'x509', '-req', '-in', tls / 'server.csr', '-CA', tls / 'ca.crt', '-CAkey', tls / 'ca.key', '-CAcreateserial', '-days', '1', '-extfile', tls / 'extensions', '-out', tls / 'server.crt'])
        java = java_home / 'bin'
        trust = secrets.token_hex(24)
        command([java / 'keytool', '-importcert', '-noprompt', '-alias', 'owned', '-file', tls / 'ca.crt', '-keystore', tls / 'trust.p12', '-storetype', 'PKCS12', '-storepass', trust])
        for f in tls.iterdir():
            f.chmod(420)
        java_args = work / 'java.args'
        java_args.write_text('-Djavax.net.ssl.trustStore=' + str(tls / 'trust.p12') + '\n-Djavax.net.ssl.trustStorePassword=' + trust + '\n')
        phase = 'owned-services'
        network = 'moneytoad-managed-' + run
        ingress = network + '-ingress'
        for (name, internal) in [(network, True), (ingress, False)]:
            networks.append(name)
            docker('network', 'create', *(['--internal'] if internal else []), '--label', label + '=' + run, name)
        dbpass = secrets.token_hex(24)
        redispass = secrets.token_hex(24)
        (work / 'mysql.env').write_text('MYSQL_ROOT_PASSWORD=' + dbpass + '\nMYSQL_ROOT_HOST=%\n')
        (work / 'client.cnf').write_text('[client]\nuser=root\npassword=' + dbpass + '\n')
        mysql = 'moneytoad-managed-db-' + run
        redis = 'moneytoad-managed-redis-' + run
        owned(mysql, '--network', network, '--network-alias', 'mysql', '--tmpfs', '/var/lib/mysql:rw,size=512m', '--mount', f'type=bind,src={tls},dst=/tls,readonly', '--mount', f"type=bind,src={work / 'client.cnf'},dst=/run/client.cnf,readonly", '--env-file', work / 'mysql.env', 'mysql:8.4', '--skip-log-bin', '--require-secure-transport=ON', '--ssl-ca=/tls/ca.crt', '--ssl-cert=/tls/server.crt', '--ssl-key=/tls/server.key')
        poll(lambda : docker('exec', mysql, 'mysql', '--defaults-extra-file=/run/client.cnf', '--protocol=TCP', '-h', '127.0.0.1', '--ssl-mode=REQUIRED', '--batch', '--skip-column-names', '-e', 'SELECT 1', allow=True)[1].strip() == '1')
        (work / 'redis.conf').write_text('bind 0.0.0.0\nport 0\ntls-port 6379\ntls-auth-clients no\ntls-cert-file /tls/server.crt\ntls-key-file /tls/server.key\ntls-ca-cert-file /tls/ca.crt\nsave ""\nappendonly no\nuser default off\nuser runtime on >' + redispass + ' ~* +@all\n')
        owned(redis, '--network', network, '--network-alias', 'redis', '--tmpfs', '/data:rw,size=32m', '--user', '0:0', '--mount', f'type=bind,src={tls},dst=/tls,readonly', '--mount', f"type=bind,src={work / 'redis.conf'},dst=/run/redis.conf,readonly", '--entrypoint', 'redis-server', 'redis:7.4-alpine', '/run/redis.conf')
        poll(lambda : docker('exec', '-e', 'REDISCLI_AUTH', redis, 'redis-cli', '--tls', '--cacert', '/tls/ca.crt', '-h', 'redis', '--user', 'runtime', 'PING', env={'REDISCLI_AUTH': redispass}, allow=True)[1].strip() == 'PONG')
        (dbport, redisport) = (free(), free())
        ports.extend((dbport, redisport))
        relay = work / 'relay.mjs'
        relay.write_text("import net from 'node:net';for(const [port,host] of [[3306,'mysql'],[6379,'redis']])net.createServer(c=>{const u=net.connect({host,port});c.on('error',()=>u.destroy());u.on('error',()=>c.destroy());c.on('close',()=>u.destroy());u.on('close',()=>c.destroy());c.pipe(u);u.pipe(c)}).listen(port,'0.0.0.0');")
        relay.chmod(420)
        relayname = 'moneytoad-managed-relay-' + run
        owned(relayname, '--network', ingress, '-p', f'127.0.0.1:{dbport}:3306', '-p', f'127.0.0.1:{redisport}:6379', '--user', '1000:1000', '--mount', f'type=bind,src={relay},dst=/run/relay.mjs,readonly', '--entrypoint', 'node', 'node:22-bookworm-slim', '/run/relay.mjs')
        docker('network', 'connect', network, relayname)

        def canconnect(port):
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=2):
                    return True
            except OSError:
                return False
        for p in [dbport, redisport]:
            poll(lambda : canconnect(p), 15)
        config = {'TIDB_HOST': 'localhost', 'TIDB_PORT': str(dbport), 'TIDB_SETUP_USERNAME': 'root', 'TIDB_SETUP_PASSWORD': dbpass, 'REDIS_HOST': 'localhost', 'REDIS_PORT': str(redisport), 'REDIS_USERNAME': 'runtime', 'REDIS_PASSWORD': redispass, 'REDIS_SSL_ENABLED': 'true', 'mode': 'local-rehearsal', 'schema': 'moneytoad_contract_' + run, 'ddlPath': str(source / 'scripts/verification/fixtures/managed-provider-schema.sql'), 'appPort': str(free()), 'deadlineEpochMillis': str(int((time.time() + 1200) * 1000))}
        for (name, doc) in [('input', config), ('result', {}), ('ledger', {}), ('budget', {'loginAttempts': 0, 'commandEquivalents': 0})]:
            p = work / (name + '.json')
            p.write_text(json.dumps(doc))
            p.chmod(384)
        phase = 'actual-product-probe'
        env = {'MANAGED_CHECK_' + key: str(work / (name + '.json')) for (key, name) in [('CONFIG', 'input'), ('RESULT', 'result'), ('LEDGER', 'ledger'), ('BUDGET', 'budget')]}
        (code, _) = command([java / 'java', '@' + str(java_args), '-cp', classpath, 'com.potg.verification.managed.ManagedProviderProbe'], env=env, timeout=600, allow=True)
        data = json.loads((work / 'result.json').read_text())
        summary['probe'] = data
        summary['process_exit'] = code
        if code == 0 and data.get('status') == 'PASS' and data.get('cleanup_complete'):
            summary['status'] = 'PASS'
    except BaseException as e:
        summary['failed_phase'] = phase
        summary['failure_type'] = type(e).__name__
    finally:
        for signum in previous:
            signal.signal(signum, signal.SIG_IGN)
        cleanup = True
        for p in procs:
            if p.poll() is None:
                try:
                    os.killpg(p.pid, signal.SIGTERM)
                    p.wait(20)
                except BaseException:
                    cleanup = False
        for name in reversed(containers):
            try:
                (code, out) = docker('inspect', '--format', '{{index .Config.Labels "' + label + '"}}', name, allow=True)
                if code == 0:
                    if out.strip() != run:
                        cleanup = False
                        continue
                    docker('rm', '-f', name)
                    if docker('inspect', name, allow=True)[0] == 0:
                        cleanup = False
            except BaseException:
                cleanup = False
        for name in reversed(networks):
            try:
                (code, out) = docker('network', 'inspect', '--format', '{{index .Labels "' + label + '"}}', name, allow=True)
                if code == 0:
                    if out.strip() != run:
                        cleanup = False
                        continue
                    docker('network', 'rm', name)
                    if docker('network', 'inspect', name, allow=True)[0] == 0:
                        cleanup = False
            except BaseException:
                cleanup = False
        for port in ports:
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=1):
                    cleanup = False
            except OSError:
                pass
        summary['local_owned_cleanup'] = cleanup
        if not cleanup:
            summary['status'] = 'FAIL'
        if forbidden(summary):
            summary = {'status': 'FAIL', 'reason': 'PUBLIC_PROJECTION_REJECTED', 'local_owned_cleanup': cleanup, 'provider_connections': 0}
        output = root / 'docs/deployment/evidence/AUTONOMOUS_CONTINUATION' / ('provider-local-' + uuid.uuid4().hex[:12])
        output.mkdir(parents=True, exist_ok=False)
        (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        if cleanup:
            shutil.rmtree(work)
        for (signum, handler) in previous.items():
            signal.signal(signum, handler)
        print(json.dumps(summary, indent=2))
        return 0 if summary['status'] == 'PASS' else 1
if __name__ == '__main__':
    raise SystemExit(main())
