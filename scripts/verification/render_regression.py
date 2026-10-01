#!/usr/bin/env python3
"""Run existing regression selectors in a private current-tree copy; preserve past evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import uuid

from public_evidence import forbidden

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'docs/deployment/evidence/RENDER_RUNTIME'
EXCLUDED_PARTS = {'.git', 'node_modules', '.gradle', '__pycache__', 'build', 'dist',
                  'test-results', 'playwright-report', 'blob-report'}
EXCLUDED_SUFFIXES = {'.pem', '.key', '.crt', '.cer', '.p12', '.pfx', '.jks', '.rdb',
                     '.dump', '.log', '.har', '.pyc'}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def historical_evidence():
    directory = ROOT / 'docs/portfolio/evidence'
    return {str(path.relative_to(ROOT)): digest(path)
            for path in directory.rglob('*') if path.is_file()}


def inputs():
    env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
    env['GIT_OPTIONAL_LOCKS'] = '0'
    result = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', 'ls-files',
                             '--cached', '--others', '--exclude-standard', '-z'],
                            cwd=ROOT, env=env, capture_output=True, check=True)
    paths = []
    for name in sorted(set(result.stdout.decode().split('\0')) - {''}):
        relative = Path(name)
        if relative.is_absolute() or '..' in relative.parts:
            raise ValueError('Unsafe source path')
        if (name.startswith(('docs/portfolio/evidence/', 'docs/deployment/evidence/'))
                or set(relative.parts) & EXCLUDED_PARTS
                or relative.suffix in EXCLUDED_SUFFIXES
                or relative.name.startswith('.env') and relative.name != '.env.example'
                or relative.name == '.DS_Store'):
            continue
        source = ROOT / relative
        if source.is_symlink():
            raise ValueError('Source symlinks are not copy inputs')
        if source.is_file():
            paths.append(relative)
    return paths


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase', required=True, choices=('fe', 'be'))
    parser.add_argument('--dependencies', type=Path)
    parser.add_argument('--cache-seed', type=Path)
    parser.add_argument('--run-label', help='Optional unique output label; never overwrite an existing run')
    args = parser.parse_args()
    if args.phase == 'fe' and not args.dependencies:
        parser.error('FE requires explicit separately installed dependencies')
    if args.phase == 'be' and not args.cache_seed:
        parser.error('BE requires an explicit isolated Gradle cache')
    label = args.run_label or (args.phase + '-' + uuid.uuid4().hex[:12])
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', label):
        parser.error('Invalid run label')
    output = OUTPUT / label
    if output.exists():
        parser.error('Output already exists; choose a fresh run label')
    os.umask(0o077)
    work = Path(tempfile.mkdtemp(prefix='moneytoad-render-regression-'))
    copy = work / 'snapshot'
    copy.mkdir()
    (work / 'tmp').mkdir()
    before = historical_evidence()
    files = inputs()
    guards = {str(p): digest(ROOT / p) for p in files if p.parts[0] == args.phase}
    result = {'phase': args.phase, 'status': 'BLOCKED', 'existing_selectors_unchanged': True,
              'historical_evidence_preserved': False, 'source_preserved': False,
              'private_copy_removed': False, 'application_external_requests_allowed': False}
    process = None
    previous = signal.signal(signal.SIGTERM, lambda signum, frame: (_ for _ in ()).throw(KeyboardInterrupt()))
    cleanup_safe = True
    try:
        for relative in files:
            target = copy / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        command = [sys.executable, '-B', 'scripts/verification/public_snapshot.py', '--phase', args.phase]
        if args.phase == 'fe':
            dependencies = args.dependencies.resolve()
            if not (dependencies / 'node_modules').is_dir():
                raise ValueError('Dependency installation missing')
            for manifest in ('package.json', 'package-lock.json'):
                if digest(dependencies / manifest) != digest(copy / 'fe' / manifest):
                    raise ValueError('Dependency manifest does not match the current frontend')
            command += ['--dependencies', str(dependencies)]
        else:
            cache = args.cache_seed.resolve()
            if not (cache / 'caches').is_dir() or not (cache / 'wrapper').is_dir():
                raise ValueError('Explicit Gradle cache is incomplete')
            command += ['--cache-seed', str(cache)]
        env = {'PATH': '/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin',
               'HOME': str(Path.home()), 'TMPDIR': str(work / 'tmp'), 'LANG': 'en_US.UTF-8',
               'NO_COLOR': '1', 'PYTHONDONTWRITEBYTECODE': '1'}
        process = subprocess.Popen(command, cwd=copy, env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True, start_new_session=True)
        try:
            process.communicate(timeout=2500)
        except (KeyboardInterrupt, subprocess.TimeoutExpired):
            process.terminate()
            try:
                process.communicate(timeout=75)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate(timeout=10)
                cleanup_safe = False
            raise
        result['exit_code'] = process.returncode
        report = copy / 'docs/portfolio/evidence' / (args.phase + '-regression-summary.json')
        entry = copy / 'docs/portfolio/evidence' / ('public-entrypoint-' + args.phase + '.json')
        if not report.is_file() or not entry.is_file():
            raise ValueError('Current execution did not produce the required summaries')
        summary = json.loads(report.read_text())
        entrypoint = json.loads(entry.read_text())
        if forbidden(summary) or forbidden(entrypoint):
            raise ValueError('Regression summary violates public evidence policy')
        result['regression'] = summary
        result['entrypoint'] = entrypoint
        if args.phase == 'be':
            cleanup_safe = summary.get('cleanup_complete') is True
            passed = (summary.get('status') == 'PASS' and summary.get('tests') == 275
                      and summary.get('failures') == 0 and summary.get('errors') == 0
                      and summary.get('skipped') == 0)
        else:
            checked = {row['check']: row for row in summary.get('checks', [])}
            passed = summary.get('status') == 'PASS' and all(row.get('status') == 'PASS' for row in checked.values())
            for mode, count in (('oauth-existing-tests', 125), ('demo-tests', 51)):
                tests = checked.get(mode, {}).get('tests', {})
                passed = passed and (tests.get('numTotalTests') == count and tests.get('numPassedTests') == count
                         and tests.get('numFailedTests') == 0 and tests.get('numPendingTests') == 0
                         and tests.get('numTodoTests') == 0)
            for check in ('test-types', 'e2e-types', 'oauth-default-build', 'demo-build', 'invalid-mode-build', 'lint'):
                passed = passed and checked.get(check, {}).get('status') == 'PASS'
            passed = passed and checked.get('lint', {}).get('errors') == 0 and checked.get('lint', {}).get('warnings') == 0
            for tool in ('node', 'npm'):
                version = subprocess.run([tool, '--version'], env=env, capture_output=True, text=True, check=True).stdout.strip()
                if not re.fullmatch(r'v?\d+\.\d+\.\d+', version):
                    raise ValueError('Unexpected tool version format')
                result[tool + '_version'] = version
        result['status'] = 'PASS' if process.returncode == 0 and passed and entrypoint.get('status') == 'PASS' else 'FAIL'
    except KeyboardInterrupt:
        result['diagnostic'] = 'Interrupted'
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        result['diagnostic'] = type(error).__name__
        if args.phase == 'be' and process is not None and 'regression' not in result:
            cleanup_safe = False
    finally:
        signal.signal(signal.SIGTERM, previous)
        result['historical_evidence_preserved'] = historical_evidence() == before
        result['source_preserved'] = all((ROOT / p).is_file() and digest(ROOT / p) == value for p, value in guards.items())
        # The existing entrypoint must remove its own dependency link, even on interruption.
        leftover = copy / 'fe/node_modules'
        if leftover.exists() or leftover.is_symlink():
            cleanup_safe = False
        if cleanup_safe:
            shutil.rmtree(work)
            result['private_copy_removed'] = not work.exists()
        if not all(result[key] for key in ('historical_evidence_preserved', 'source_preserved', 'private_copy_removed')):
            result['status'] = 'FAIL'
        if forbidden(result):
            raise ValueError('Public projection rejected; values suppressed')
        output.mkdir(parents=True, exist_ok=False)
        (output / (args.phase + '-summary.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({key: result[key] for key in ('phase', 'status', 'historical_evidence_preserved', 'source_preserved', 'private_copy_removed')}), flush=True)
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
