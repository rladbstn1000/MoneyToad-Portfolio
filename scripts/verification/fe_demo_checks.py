#!/usr/bin/env python3
"""FE checks only: no install, env-file loading, external API, rewrite or deployment.

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
    work = Path(tempfile.mkdtemp(prefix='moneytoad-fe-demo-checks-'))
    env = {'PATH': '/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin',
           'TMPDIR': str(work), 'LANG': 'en_US.UTF-8', 'NO_COLOR': '1'}
    results = {'run_id': run_id, 'work': str(work), 'checks': [], 'install': False,
               'env_loading': False, 'external_api': False, 'browser_e2e': 'OUT_OF_SCOPE',
               'baseline_lint_errors': 33, 'baseline_lint_warnings': 2}
    config = work / 'vite.config.mjs'
    config.write_text('import base from ' + json.dumps(str(FE / 'vite.config.ts')) + ';\n'
                      'export default {...base, envDir: false, build: {...base.build, outDir: '
                      + json.dumps(str(work / 'dist')) + ', emptyOutDir: true}};\n')
    checks = [
        ('node-version', ['node', '--version'], {}),
        ('npm-version', ['npm', '--version'], {}),
        ('oauth-existing-tests', ['npm', 'run', 'test:run'], {}),
        ('demo-tests', ['npm', 'run', 'test:demo'], {}),
        ('test-types', ['npm', 'run', 'test:check'], {}),
        ('oauth-default-build', ['npm', 'run', 'build', '--', '--config', str(config)], {'VITE_BACK_URL': 'http://127.0.0.1:18080'}),
        ('demo-build', ['npm', 'run', 'build', '--', '--config', str(config)], {'VITE_AUTH_MODE': 'demo', 'VITE_BACK_URL': 'http://127.0.0.1:18080'}),
        ('invalid-mode-build', ['./node_modules/.bin/vite', 'build', '--config', str(config)], {'VITE_AUTH_MODE': 'invalid', 'VITE_BACK_URL': 'http://127.0.0.1:18080'}),
        ('lint', ['./node_modules/.bin/eslint', '.', '--format', 'json'], {}),
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
                findings = json.loads(text)
                row.update(lint_audit(findings))
                row['status'] = 'FAIL' if row['added_findings'] else 'QUALITY_DEBT' if result.returncode else 'PASS'
            else:
                row['status'] = 'PASS' if result.returncode == 0 else 'FAIL'
        except (OSError, subprocess.TimeoutExpired, ValueError) as error:
            row.update(status='BLOCKED', reason=type(error).__name__)
        results['checks'].append(row)
        print(name + ': ' + row['status'], flush=True)
    results['source_hashes'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
        for directory in [FE / 'src', FE / 'tests'] for p in directory.rglob('*') if p.is_file()}
    results['status'] = 'PASS' if all(p['status'] in ('PASS', 'QUALITY_DEBT') for p in results['checks']) else 'INCOMPLETE'
    save('fe-regression-summary.json', frontend(results))
    print(json.dumps({'status': results['status'], 'run_id': run_id}))
    return 0 if results['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
