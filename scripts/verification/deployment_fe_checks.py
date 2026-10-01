#!/usr/bin/env python3
"""Current FE regression in a private copy and mutable-cache-safe dependency overlay."""
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

from dependency_overlay import DependencyOverlay
from public_evidence import forbidden
from render_regression import ROOT, digest, inputs

EXPECTED = {'oauth': 151, 'demo': 101}


def source_inputs(private_manifest=None):
    if private_manifest is None:
        return inputs()
    path = Path(private_manifest).resolve()
    if path != ROOT / '.capacity-copy-inputs.json' or not (ROOT / '.capacity-copy-owner').is_file():
        raise ValueError('PRIVATE_COPY_INPUT_BOUNDARY_REQUIRED')
    value = json.loads(path.read_text())
    if set(value) != {'owner', 'files'} or value['owner'] != (ROOT / '.capacity-copy-owner').read_text():
        raise ValueError('PRIVATE_COPY_INPUT_OWNER_MISMATCH')
    result = []
    for name, checksum in value['files'].items():
        relative = Path(name)
        source = ROOT / relative
        if (relative.is_absolute() or '..' in relative.parts or not relative.parts
                or relative.parts[0] != 'fe' or source.is_symlink() or not source.is_file()
                or relative.name.startswith('.env') and relative.name != '.env.example'
                or digest(source) != checksum):
            raise ValueError('PRIVATE_COPY_INPUT_INVALID')
        result.append(relative)
    if not result:
        raise ValueError('PRIVATE_COPY_INPUT_EMPTY')
    return sorted(result)


def preserved(manifest):
    return all((ROOT / name).is_file() and digest(ROOT / name) == checksum for name, checksum in manifest.items())


def main(*, server_only_canaries=()):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dependencies', type=Path, required=True)
    parser.add_argument('--run-label', default='fe-' + uuid.uuid4().hex[:12])
    parser.add_argument('--owned-input-manifest', type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', args.run_label):
        parser.error('Invalid run label')
    output = ROOT / 'docs/deployment/evidence/AUTONOMOUS_CONTINUATION' / args.run_label
    if output.exists():
        parser.error('Output exists; choose a new run label')
    files = [path for path in source_inputs(args.owned_input_manifest) if path.parts[0] == 'fe']
    source_before = {str(path): digest(ROOT / path) for path in files}
    old_evidence = {str(path.relative_to(ROOT)): digest(path)
                    for location in ('docs/portfolio/evidence', 'docs/deployment/evidence')
                    for path in (ROOT / location).rglob('*') if path.is_file()}
    os.umask(0o077)
    work = Path(tempfile.mkdtemp(prefix='moneytoad-deployment-fe-')).resolve()
    snapshot = work / 'snapshot'
    frontend = snapshot / 'fe'
    result = {'status': 'BLOCKED', 'environment': 'private-jsdom-msw-and-builds', 'checks': [],
              'existing_tests': 176, 'provider_connections_attempted': 0,
              'source_preserved': False, 'historical_evidence_preserved': False,
              'cleanup_complete': False}
    overlay = None
    child = None
    previous = {}

    def interrupted(signum, frame):
        raise KeyboardInterrupt()

    try:
        for signum in (signal.SIGTERM, signal.SIGINT):
            previous[signum] = signal.signal(signum, interrupted)
        for relative in files:
            destination = snapshot / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, destination)
        (work / 'home').mkdir()
        overlay = DependencyOverlay(frontend, args.dependencies).create()
        env = {'PATH': '/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin', 'HOME': str(work / 'home'),
               'TMPDIR': str(work), 'LANG': 'en_US.UTF-8', 'NO_COLOR': '1',
               'NPM_CONFIG_CACHE': str(work / 'npm-cache')}
        if server_only_canaries:
            env['DEMO_GATEWAY_SECRET'] = server_only_canaries[0]
        build_config = work / 'vite.config.mjs'
        build_config.write_text('import base from ' + json.dumps(str(frontend / 'vite.config.ts')) + ';\n'
            + 'export default {...base, envDir:false, cacheDir:' + json.dumps(str(work / 'vite-cache'))
            + ',build:{...base.build,outDir:' + json.dumps(str(work / 'dist')) + ',emptyOutDir:true}};\n')
        checks = [
            ('oauth-tests', ['npm', 'run', 'test:run', '--', '--reporter=json', '--outputFile=' + str(work / 'oauth.json')], {}),
            ('demo-tests', ['npm', 'run', 'test:demo', '--', '--reporter=json', '--outputFile=' + str(work / 'demo.json')], {}),
            ('test-types', ['npm', 'run', 'test:check'], {}),
            ('e2e-types', ['npm', 'run', 'test:e2e:check'], {}),
            ('functions-types', ['./node_modules/.bin/tsc', '-p', 'tsconfig.functions.json', '--noEmit'], {}),
            ('oauth-build', ['npm', 'run', 'build', '--', '--config', str(build_config)], {'VITE_BACK_URL': 'http://127.0.0.1:18080'}),
            ('demo-build', ['npm', 'run', 'build', '--', '--config', str(build_config)], {'VITE_BACK_URL': 'http://127.0.0.1:18080', 'VITE_AUTH_MODE': 'demo'}),
            ('invalid-mode-build', ['./node_modules/.bin/vite', 'build', '--config', str(build_config)], {'VITE_BACK_URL': 'http://127.0.0.1:18080', 'VITE_AUTH_MODE': 'invalid'}),
            ('lint', ['npm', 'run', 'lint', '--', '--max-warnings', '0', '--format', 'json'], {}),
        ]
        for name, command, extra in checks:
            child = subprocess.Popen(command, cwd=frontend, env={**env, **extra}, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, start_new_session=True)
            raw, _ = child.communicate(timeout=240)
            row = {'check': name, 'status': 'PASS' if child.returncode == 0 else 'FAIL', 'exit_code': child.returncode}
            if name.endswith('-tests'):
                mode = name.split('-')[0]
                report = json.loads((work / (mode + '.json')).read_text())
                row.update(tests=report['numTotalTests'], passed=report['numPassedTests'],
                           failed=report['numFailedTests'], skipped=report['numPendingTests'], todo=report['numTodoTests'])
                row['status'] = 'PASS' if (child.returncode == 0 and row['tests'] == EXPECTED[mode]
                    and row['passed'] == row['tests'] and row['failed'] == row['skipped'] == row['todo'] == 0) else 'FAIL'
                row['failed_cases'] = [{'file': str(Path(suite['name']).relative_to(snapshot)), 'test': case['fullName']}
                    for suite in report['testResults'] for case in suite['assertionResults'] if case['status'] != 'passed']
            elif name == 'invalid-mode-build':
                row['status'] = 'PASS' if child.returncode != 0 and 'VITE_AUTH_MODE must be oauth or demo' in raw else 'FAIL'
            elif name == 'lint':
                findings = json.loads(raw[raw.index('[{'):])
                row.update(errors=sum(item['errorCount'] for item in findings), warnings=sum(item['warningCount'] for item in findings))
                row['locations'] = [{'file': str(Path(item['filePath']).relative_to(snapshot)),
                    'line': message['line'], 'rule': message['ruleId']} for item in findings for message in item['messages']]
                row['status'] = 'PASS' if child.returncode == 0 and row['errors'] == row['warnings'] == 0 else 'FAIL'
            elif child.returncode:
                row['type_diagnostics'] = re.findall(r'(?:src|tests|functions)/[^\r\n:]+?\(\d+,\d+\): error TS\d+', raw)
            if name in ('oauth-build', 'demo-build') and row['status'] == 'PASS':
                styles = list((work / 'dist').rglob('*.css'))
                row['external_font_definitions'] = sum(bool(re.search(r'@font-face|ChosunCentennial|gcore\.jsdelivr', path.read_text())) for path in styles)
                if not styles or row['external_font_definitions'] != 0:
                    row['status'] = 'FAIL'
                if server_only_canaries:
                    bundles = [path for path in (work / 'dist').rglob('*') if path.is_file()]
                    row['server_material_absent_from_bundle'] = all(
                        value.encode() not in path.read_bytes() for value in server_only_canaries for path in bundles)
                    if not row['server_material_absent_from_bundle']:
                        row['status'] = 'FAIL'
            result['checks'].append(row)
            print(name + ': ' + row['status'], flush=True)
        result['tests'] = sum(row.get('tests', 0) for row in result['checks'])
        result['new_tests'] = result['tests'] - result['existing_tests']
        result['status'] = 'PASS' if all(row['status'] == 'PASS' for row in result['checks']) else 'FAIL'
    except KeyboardInterrupt:
        result['status'] = 'INTERRUPTED'
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        result['diagnostic'] = type(error).__name__
    finally:
        for signum in previous:
            signal.signal(signum, signal.SIG_IGN)
        cleaned = True
        if child is not None and child.poll() is None:
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.communicate(timeout=30)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.communicate(timeout=10)
                cleaned = False
        if overlay is not None:
            try:
                overlay.close()
            except (OSError, ValueError):
                cleaned = False
            result['dependencies'] = overlay.report
        result['source_preserved'] = preserved(source_before)
        result['historical_evidence_preserved'] = preserved(old_evidence)
        try:
            shutil.rmtree(work)
            cleaned = not work.exists() and cleaned
        except OSError:
            cleaned = False
        result['cleanup_complete'] = cleaned
        if not all(result[key] for key in ('source_preserved', 'historical_evidence_preserved', 'cleanup_complete')):
            result['status'] = 'FAIL'
        if forbidden(result):
            raise ValueError('Public projection rejected; raw values suppressed')
        output.mkdir(parents=True, exist_ok=False)
        (output / 'fe-summary.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({key: result[key] for key in ('status', 'cleanup_complete', 'source_preserved', 'historical_evidence_preserved')}), flush=True)
        for signum, handler in previous.items():
            signal.signal(signum, handler)
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
