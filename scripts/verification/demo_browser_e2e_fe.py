#!/usr/bin/env python3
"""Browser-stage existing FE regression checks: no install, env-file loading, external API, rewrite or deployment.

Existing node_modules are required. Vite builds use the actual config with an
owned empty envDir and output directory. Vitest configs already pin loopback MSW.
"""
from public_evidence import frontend, save
import hashlib
import json
from collections import Counter
from pathlib import Path
import re
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[2]
FE = ROOT / 'fe'


def lint_audit(findings):
    baseline = json.loads((ROOT / 'scripts/verification/fixtures/fe-lint-baseline.json').read_text())['files']
    old = Counter((p['file'], m['ruleId'], m['severity'], m['message'])
                  for p in baseline for m in p['messages'])
    current = Counter((str(Path(p['filePath']).relative_to(ROOT)), m['ruleId'], m['severity'], m['message'])
                      for p in findings for m in p['messages'])
    return {'errors': sum(p['errorCount'] for p in findings),
            'warnings': sum(p['warningCount'] for p in findings),
            'added_findings': list((current - old).elements()),
            'same_baseline_findings': current == old}


def main():
    run_id = uuid.uuid4().hex[:12]
    out = Path(tempfile.mkdtemp(prefix='moneytoad-fe-evidence-'))
    out.mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='moneytoad-lint-fe-checks-'))
    env = {'PATH': '/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin',
           'TMPDIR': str(work), 'LANG': 'en_US.UTF-8', 'NO_COLOR': '1'}
    results = {'run_id': run_id, 'work': str(work), 'checks': [], 'install': False,
               'env_loading': False, 'external_api': False, 'browser_e2e': 'SEPARATE_RUNNER',
               'baseline_lint_errors': 0, 'baseline_lint_warnings': 0}
    config = work / 'vite.config.mjs'
    config.write_text('import base from ' + json.dumps(str(FE / 'vite.config.ts')) + ';\n'
                      'export default {...base, envDir: false, build: {...base.build, outDir: '
                      + json.dumps(str(work / 'dist')) + ', emptyOutDir: true}};\n')
    checks = [
        ('node-version', ['node', '--version'], {}),
        ('npm-version', ['npm', '--version'], {}),
        ('oauth-existing-tests', ['npm', 'run', 'test:run', '--', '--reporter=default', '--reporter=json', '--outputFile=' + str(work / 'oauth-tests.json')], {}),
        ('demo-tests', ['npm', 'run', 'test:demo', '--', '--reporter=default', '--reporter=json', '--outputFile=' + str(work / 'demo-tests.json')], {}),
        ('test-types', ['npm', 'run', 'test:check'], {}),
        ('e2e-types', ['npm', 'run', 'test:e2e:check'], {}),
        ('oauth-default-build', ['npm', 'run', 'build', '--', '--config', str(config)], {'VITE_BACK_URL': 'http://127.0.0.1:18080'}),
        ('demo-build', ['npm', 'run', 'build', '--', '--config', str(config)], {'VITE_AUTH_MODE': 'demo', 'VITE_BACK_URL': 'http://127.0.0.1:18080'}),
        ('invalid-mode-build', ['./node_modules/.bin/vite', 'build', '--config', str(config)], {'VITE_AUTH_MODE': 'invalid', 'VITE_BACK_URL': 'http://127.0.0.1:18080'}),
        ('lint', ['npm', 'run', 'lint', '--', '--max-warnings', '0', '--format', 'json'], {}),
    ]
    for name, command, extra in checks:
        row = {'name': name, 'command': command, 'env_overrides': extra}
        try:
            result = subprocess.run(command, cwd=FE, env={**env, **extra}, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240)
            text = re.sub(r'\x1b\[[0-9;]*m', '', result.stdout)
            text = re.sub(r'\beyJ[\w-]+\.[\w-]+\.[\w-]+\b', '<redacted JWT>', text)
            text = re.sub(r'synthetic-access-\d+', '<redacted synthetic AT>', text)
            suffix = 'json' if name == 'lint' else 'log'
            path = out / f'fe-{run_id}-{name}.{suffix}'
            path.write_text(text)
            row.update(exit_code=result.returncode, evidence=path.name)
            if name == 'invalid-mode-build':
                row['status'] = 'PASS' if result.returncode != 0 and 'VITE_AUTH_MODE must be oauth or demo' in text else 'FAIL'
            elif name == 'lint':
                findings = json.loads(text[text.index('[{'):])
                row.update(lint_audit(findings))
                row['status'] = 'PASS' if result.returncode == 0 and row['errors'] == 0 and row['warnings'] == 0 else 'FAIL'
            else:
                row['status'] = 'PASS' if result.returncode == 0 else 'FAIL'
            if name in ('oauth-existing-tests', 'demo-tests'):
                mode = 'oauth' if name == 'oauth-existing-tests' else 'demo'
                report = json.loads((work / (mode + '-tests.json')).read_text())
                row['tests'] = {key: report[key] for key in ('numTotalTests', 'numPassedTests', 'numFailedTests', 'numPendingTests', 'numTodoTests')}
                row['test_files'] = [{'file': str(Path(suite['name']).relative_to(ROOT)),
                    'cases': [{'name': case['fullName'], 'status': case['status']} for case in suite['assertionResults']]}
                    for suite in report['testResults']]
                if (report['numTotalTests'] != (125 if mode == 'oauth' else 51) or
                        report['numTotalTests'] != report['numPassedTests'] or
                        report['numPendingTests'] or report['numTodoTests']):
                    row['status'] = 'FAIL'
        except (OSError, subprocess.TimeoutExpired, ValueError) as error:
            row.update(status='BLOCKED', reason=type(error).__name__)
        results['checks'].append(row)
        print(name + ': ' + row['status'], flush=True)
    results['source_hashes'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
        for directory in [FE / 'src', FE / 'tests'] for p in directory.rglob('*') if p.is_file()}
    results['status'] = 'PASS' if all(p['status'] == 'PASS' for p in results['checks']) else 'INCOMPLETE'
    save('fe-regression-summary.json', frontend(results))
    print(json.dumps({'status': results['status'], 'run_id': run_id}))
    return 0 if results['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
