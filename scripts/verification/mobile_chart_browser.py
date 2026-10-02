#!/usr/bin/env python3
"""Three real Chromium viewports, using the existing owned MySQL/Redis gateway harness."""
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

from public_evidence import forbidden
from render_regression import EXCLUDED_PARTS, EXCLUDED_SUFFIXES

ROOT = Path(__file__).resolve().parents[2]

def source_files():
    for directory, folders, files in os.walk(ROOT, followlinks=False):
        folders[:] = sorted(folder for folder in folders if folder not in EXCLUDED_PARTS
            and str((Path(directory) / folder).relative_to(ROOT)) not in
            ('docs/portfolio/evidence', 'docs/deployment/evidence'))
        for folder in folders:
            if (Path(directory) / folder).is_symlink():
                raise ValueError('SOURCE_SYMLINK_REJECTED')
        for filename in sorted(files):
            path = Path(directory) / filename
            if (path.suffix in EXCLUDED_SUFFIXES or path.name.startswith('.env') and path.name != '.env.example'
                    or path.name == '.DS_Store'):
                continue
            if path.is_symlink():
                raise ValueError('SOURCE_SYMLINK_REJECTED')
            if path.is_file():
                yield path.relative_to(ROOT)

def worker(args):
    import demo_browser_e2e as harness
    runs = []
    code = harness.run_once(args, runs, modes=('public-demo',), browser_config='playwright.mobile.config.ts',
                            expected_cases=3, browser_extra={'E2E_MOBILE_PHASE': args.phase}, copy_screenshots=False)
    summaries = [summary for _, summary in runs]
    folders = list(harness.OUT.glob('*/public-demo'))
    result = {'status': 'FAIL', 'phase': args.phase, 'cleanup_complete': bool(summaries) and all(row['cleanup_complete'] for row in summaries),
              'managed_provider_used': False, 'product_api_mocks': False, 'workers': 1, 'retries': 0,
              'viewport_results': [], 'external_attempts': 0, 'cases': 0}
    for summary in summaries:
        for mode in summary['modes']:
            if mode['mode'] == 'public-demo':
                result['cases'] += len(mode.get('cases', []))
                result['tests'] = mode.get('cases', [])
                result['external_attempts'] += sum(network['external_attempts'] + network['backend_attempts'] for network in mode['networks'])
    output = ROOT / 'mobile-result'
    output.mkdir()
    for folder in folders:
        diagnostic = folder / 'pie-observation.json'
        if diagnostic.is_file():
            value = json.loads(diagnostic.read_text())
            if forbidden(value): raise ValueError('PIE_DIAGNOSTIC_REJECTED')
            result['pie_observation'] = value
        for path in folder.glob('layout-*.json'):
            value = json.loads(path.read_text())
            if forbidden(value): raise ValueError('PUBLIC_LAYOUT_REJECTED')
            result['viewport_results'].append(value)
        for path in folder.glob('mobile-*.png'):
            contents = path.read_bytes()
            if contents[:8] != b'\x89PNG\r\n\x1a\n': raise ValueError('SCREENSHOT_FORMAT_REJECTED')
            offset = 8
            while offset < len(contents):
                length = int.from_bytes(contents[offset:offset + 4], 'big')
                kind = contents[offset + 4:offset + 8]
                if kind in (b'tEXt', b'zTXt', b'iTXt', b'eXIf'): raise ValueError('SCREENSHOT_METADATA_REJECTED')
                offset += 12 + length
            shutil.copy2(path, output / path.name)
        for path in folder.glob('*-network.json'):
            value = json.loads(path.read_text())
            if forbidden(value): raise ValueError('PUBLIC_NETWORK_REJECTED')
            (output / path.name).write_text(json.dumps(value, ensure_ascii=False, indent=2))
    passed = (code == 0 and len(result['viewport_results']) == 3 and result['cases'] == 3 and result['external_attempts'] == 0
              and result['cleanup_complete'] and all(test['status'] == 'passed' for test in result.get('tests', [])))
    result['status'] = 'PASS' if passed else 'FAIL'
    result['layout_ready'] = passed and all(row['selectedAtViewport'] and row['originalLayout']['readable']
        and row['originalLayout']['bodyOverflow'] <= 1 and (row['viewport']['width'] > 768 or row['originalLayout']['touchTarget'])
        for row in result['viewport_results'])
    result['observation_only'] = args.phase == 'before'
    if forbidden(result): raise ValueError('PUBLIC_SUMMARY_REJECTED')
    (output / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if passed else 1

def main(*, evidence_directory='MOBILE_CHART', entrypoint='scripts/verification/mobile_chart_browser.py',
         worker_function=None, phase_choices=('before', 'after')):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase', choices=phase_choices, required=True)
    parser.add_argument('--cache-seed', type=Path, required=True)
    parser.add_argument('--browser-path', type=Path, required=True)
    parser.add_argument('--dependencies', type=Path, required=True)
    parser.add_argument('--run-label', required=True)
    parser.add_argument('--worker', action='store_true')
    parser.add_argument('--evidence-directory', choices=('MOBILE_CHART', 'COLD_START_RECOVERY', 'PUBLIC_SCANNER_AUDIT', 'COOKIE_E2E_TIME_BASIS', 'FULL_DEMO_EXPERIENCE'), default=evidence_directory)
    args = parser.parse_args()
    if args.worker:
        if not (ROOT / '.mobile-copy-owner').is_file(): parser.error('OWNED_COPY_REQUIRED')
        return (worker_function or worker)(args)
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', args.run_label): parser.error('INVALID_LABEL')
    output = ROOT / 'docs/deployment/evidence' / args.evidence_directory / args.run_label
    if output.exists(): parser.error('EVIDENCE_EXISTS')
    os.umask(0o077)
    work = Path(tempfile.mkdtemp(prefix='moneytoad-mobile-browser-'))
    copy = work / 'source'
    manifests = {}
    for relative in source_files():
        path = ROOT / relative
        manifests[str(relative)] = hashlib.sha256(path.read_bytes()).hexdigest()
        destination = copy / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, destination)
    (copy / '.mobile-copy-owner').write_text(args.run_label)
    from dependency_overlay import DependencyOverlay
    overlay = DependencyOverlay(copy / 'fe', args.dependencies.resolve()).create()
    command = [sys.executable, '-B', entrypoint, '--worker', '--phase', args.phase,
               '--cache-seed', str(args.cache_seed.resolve()), '--browser-path', str(args.browser_path.resolve()),
               '--dependencies', str(args.dependencies.resolve()), '--run-label', args.run_label,
               '--evidence-directory', args.evidence_directory]
    env = {'PATH': '/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin', 'HOME': str(Path.home()),
           'LANG': 'en_US.UTF-8', 'PYTHONDONTWRITEBYTECODE': '1'}
    process = None
    previous = {}
    def interrupt(signum, frame): raise KeyboardInterrupt()
    try:
        for signum in (signal.SIGINT, signal.SIGTERM): previous[signum] = signal.signal(signum, interrupt)
        process = subprocess.Popen(command, cwd=copy, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        process.wait(timeout=900)
    finally:
        for signum in previous: signal.signal(signum, signal.SIG_IGN)
        if process and process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=90)
        overlay.close()
        changed_source_paths = [relative for relative, checksum in manifests.items()
            if not (ROOT / relative).is_file() or hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() != checksum]
        preserved = not changed_source_paths
        observed = copy / 'mobile-result'
        if observed.is_dir():
            shutil.copytree(observed, output)
            result = json.loads((output / 'summary.json').read_text())
        else:
            output.mkdir(parents=True)
            result = {'status': 'BLOCKED', 'cleanup_complete': False, 'diagnostic': 'NO_CURRENT_WORKER_RESULT'}
        result['source_preserved'] = preserved
        result['changed_source_paths'] = changed_source_paths
        result['dependencies_preserved'] = all(overlay.report.values())
        if not preserved or not result['dependencies_preserved']: result['status'] = 'FAIL'
        if result['cleanup_complete']:
            shutil.rmtree(copy)
        result['owned_source_copy_removed'] = not copy.exists()
        result['cleanup_complete'] = result['cleanup_complete'] and result['owned_source_copy_removed'] and all(overlay.report.values())
        if not result['cleanup_complete']: result['status'] = 'FAIL'
        if forbidden(result): raise ValueError('FINAL_PUBLIC_SUMMARY_REJECTED')
        (output / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        for signum, handler in previous.items(): signal.signal(signum, handler)
        print(json.dumps({key: result[key] for key in ('status', 'cleanup_complete', 'source_preserved', 'dependencies_preserved')}))
    return 0 if result['status'] == 'PASS' else 1

if __name__ == '__main__':
    raise SystemExit(main())
