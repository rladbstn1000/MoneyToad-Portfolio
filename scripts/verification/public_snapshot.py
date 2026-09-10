#!/usr/bin/env python3
"""Git-free verification using explicit dependencies and disposable service resources."""
import argparse
import json
import os
import re
from pathlib import Path
import signal
import subprocess
import sys
from public_evidence import save

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase', choices=('unit', 'be', 'fe', 'browser', 'all'), default='all')
    parser.add_argument('--cache-seed', type=Path)
    parser.add_argument('--browser-path', type=Path)
    parser.add_argument('--dependencies', type=Path,
                        help='External directory containing npm-ci-installed node_modules')
    args = parser.parse_args()
    # Verification derives all paths from this file; Git metadata is never consulted.
    node = ROOT / 'fe/node_modules'
    created_link = False
    if args.phase in ('fe', 'browser', 'all'):
        if node.exists() or node.is_symlink():
            raise SystemExit('Refusing to reuse ambient node_modules')
        if not args.dependencies or not (args.dependencies / 'node_modules').is_dir():
            raise SystemExit('Provide a separately installed dependency directory')
        node.symlink_to((args.dependencies / 'node_modules').resolve(), target_is_directory=True)
        created_link = True
    def interrupted(signum, frame):
        raise KeyboardInterrupt()
    previous = signal.signal(signal.SIGTERM, interrupted)
    checks = []
    phases = ('unit', 'be', 'fe', 'browser') if args.phase == 'all' else (args.phase,)
    try:
        for phase in phases:
            common = [sys.executable, '-B']
            if phase == 'unit':
                command = common + ['-m', 'unittest', 'discover', '-s', 'scripts/verification', '-p', 'test_public_*.py']
            elif phase == 'be':
                if not args.cache_seed:
                    raise ValueError('Explicit Gradle dependency cache required')
                command = common + ['scripts/verification/demo_browser_e2e_be.py', '--phase', 'green',
                                    '--cache-seed', str(args.cache_seed)]
            elif phase == 'fe':
                command = common + ['scripts/verification/demo_browser_e2e_fe.py']
            else:
                if not args.cache_seed or not args.browser_path:
                    raise ValueError('Explicit Gradle cache and Chromium installation required')
                command = common + ['scripts/verification/demo_browser_e2e.py', '--runs', '2',
                                    '--cache-seed', str(args.cache_seed), '--browser-path', str(args.browser_path)]
            env = {**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'}
            # Child runners emit only phase/status messages; never forward arbitrary error payloads.
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=subprocess.DEVNULL,
                                       stderr=subprocess.PIPE, text=True)
            try:
                _, diagnostic = process.communicate(timeout=2400)
                code = process.returncode
            except (KeyboardInterrupt, subprocess.TimeoutExpired):
                process.terminate()
                process.wait(timeout=60)
                raise
            checks.append({'check': phase, 'exit_code': code, 'status': 'PASS' if code == 0 else 'FAIL'})
            if phase == 'unit':
                total = re.search(r'Ran (\d+) tests?', diagnostic)
                checks[-1]['tests'] = int(total.group(1)) if total else 0
                if checks[-1]['tests'] < 14 or 'skipped=' in diagnostic:
                    checks[-1]['status'] = 'FAIL'
                    code = 1
            print(phase + ': ' + checks[-1]['status'], flush=True)
            if code:
                break
    finally:
        signal.signal(signal.SIGTERM, previous)
        if created_link:
            node.unlink()
        save('public-entrypoint-' + args.phase + '.json', {'checks': checks,
             'status': 'PASS' if len(checks) == len(phases) and all(c['status'] == 'PASS' for c in checks) else 'FAIL'})
    return 0 if len(checks) == len(phases) and all(c['status'] == 'PASS' for c in checks) else 1


if __name__ == '__main__':
    raise SystemExit(main())
