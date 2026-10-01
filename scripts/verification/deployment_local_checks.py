#!/usr/bin/env python3
"""Run unchanged BE selectors / actual browser scenarios in a private current tree.

Only sanitized new deployment evidence is copied back. Explicit local dependencies,
cache and browser are required; this runner never loads managed credentials.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import uuid

from public_evidence import forbidden
from render_regression import ROOT, digest, inputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase', choices=('be', 'browser'), required=True)
    parser.add_argument('--cache-seed', type=Path, required=True)
    parser.add_argument('--dependencies', type=Path)
    parser.add_argument('--browser-path', type=Path, help='Owned PLAYWRIGHT_BROWSERS_PATH directory, not executable')
    args = parser.parse_args()
    if args.phase == 'browser' and not (args.dependencies and args.browser_path):
        parser.error('Browser requires explicit dependencies and browser directory')
    if args.phase == 'browser' and not args.browser_path.is_dir():
        parser.error('Provide the owned browser installation directory')
    os.umask(0o077)
    work = Path(tempfile.mkdtemp(prefix='moneytoad-deployment-check-')).resolve()
    source = work / 'snapshot'
    output = ROOT / 'docs/deployment/evidence/AUTONOMOUS_CONTINUATION' / (args.phase + '-' + uuid.uuid4().hex[:12])
    selected = inputs()
    guarded = ('be',) if args.phase == 'be' else ('be', 'fe')
    before = {str(p): digest(ROOT / p) for p in selected if p.parts[0] in guarded}
    result = {'phase': args.phase, 'status': 'BLOCKED', 'source_preserved': False,
              'cleanup_complete': False, 'managed_provider_used': False}
    process = None
    overlay = None
    cleanup = True
    previous = signal.signal(signal.SIGTERM, lambda signum, frame: (_ for _ in ()).throw(KeyboardInterrupt()))
    try:
        for relative in selected:
            target = source / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        (work / 'tmp').mkdir()
        if args.phase == 'browser':
            # Package contents remain read-only; Vite/tsc caches live in the overlay.
            from dependency_overlay import DependencyOverlay
            overlay = DependencyOverlay(source / 'fe', args.dependencies.resolve()).create()
            command = [sys.executable, '-B', 'scripts/verification/demo_browser_e2e.py', '--runs', '2',
                       '--cache-seed', str(args.cache_seed.resolve()), '--browser-path', str(args.browser_path.resolve())]
        else:
            command = [sys.executable, '-B', 'scripts/verification/demo_browser_e2e_be.py', '--phase', 'green',
                       '--cache-seed', str(args.cache_seed.resolve())]
        env = {'PATH': '/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin',
               'HOME': str(Path.home()), 'TMPDIR': str(work / 'tmp'), 'LANG': 'en_US.UTF-8',
               'NO_COLOR': '1', 'PYTHONDONTWRITEBYTECODE': '1'}
        process = subprocess.Popen(command, cwd=source, env=env, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, start_new_session=True)
        process.wait(timeout=2500)
        result['exit_code'] = process.returncode
        name = 'be-regression-summary.json' if args.phase == 'be' else 'demo-browser-e2e-summary.json'
        evidence = source / 'docs/portfolio/evidence'
        report = json.loads((evidence / name).read_text())
        if forbidden(report):
            raise ValueError('Public projection rejected')
        result['checks'] = report
        cleanup = (report.get('cleanup_complete') is True if args.phase == 'be'
                   else bool(report.get('runs')) and all(r.get('cleanup_complete') is True for r in report['runs']))
        passed = report.get('status') == 'PASS' and cleanup and process.returncode == 0
        if args.phase == 'be':
            passed = passed and report.get('tests') == 275 and all(report.get(k) == 0 for k in ('failures', 'errors', 'skipped'))
        else:
            passed = passed and len(report.get('runs', [])) == 2 and all(r.get('external_attempts') == 0 for r in report['runs'])
        result['status'] = 'PASS' if passed else 'FAIL'
        output.mkdir(parents=True, exist_ok=False)
        # Only the existing explicit projector's JSON and masked screenshots.
        for path in evidence.iterdir():
            if path.suffix == '.json':
                value = json.loads(path.read_text())
                if forbidden(value):
                    raise ValueError('Evidence projection rejected')
                shutil.copy2(path, output / path.name)
            elif args.phase == 'browser' and path.suffix == '.png':
                shutil.copy2(path, output / path.name)
    except KeyboardInterrupt:
        result['diagnostic'] = 'INTERRUPTED'
        cleanup = False
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        result['diagnostic'] = type(error).__name__
        if process is not None:
            cleanup = False
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=90)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
                cleanup = False
        result['source_preserved'] = all((ROOT / p).is_file() and digest(ROOT / p) == value for p, value in before.items())
        if overlay is not None:
            try:
                overlay.close()
            except (OSError, ValueError):
                cleanup = False
            result['dependencies'] = overlay.report
        if cleanup:
            shutil.rmtree(work)
        result['cleanup_complete'] = cleanup and not work.exists()
        if not result['source_preserved'] or not result['cleanup_complete']:
            result['status'] = 'FAIL'
        if forbidden(result):
            raise ValueError('Public projection rejected')
        output.mkdir(parents=True, exist_ok=True)
        (output / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
        signal.signal(signal.SIGTERM, previous)
        print(json.dumps({key: result[key] for key in ('phase', 'status', 'source_preserved', 'cleanup_complete')}))
    return 0 if result['status'] == 'PASS' else 2


if __name__ == '__main__':
    raise SystemExit(main())
