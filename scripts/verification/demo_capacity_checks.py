#!/usr/bin/env python3
"""Local capacity/cleanup regression in owned current-tree copies; never providers.

Existing local MySQL/Redis/TLS/browser lifecycle policies remain the resource
owners. Only their sanitized summaries are projected to the new evidence folder.
"""
import argparse
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
from render_regression import ROOT, digest, inputs

RENDER_CLASSES = frozenset((
    'com.potg.don.auth.RenderRuntimeIntegrationTest',
    'com.potg.don.auth.demo.RenderDemoBoundaryTest',
    'com.potg.don.global.config.RenderRuntimeConfigurationTest',
))
FOCUS_PACKAGES = ('com.potg.don.demo.admission.', 'com.potg.don.maintenance.')
FOCUS_CLASSES = frozenset(('com.potg.don.demo.seed.DemoDatasetValidatorTest',
                           'com.potg.don.auth.demo.DemoAuthServiceTest',
                           'com.potg.don.auth.demo.DemoAuthLoginTransactionTest'))
JUNIT_ANNOTATION = re.compile(r'@(?:org\.junit\.[\w.]+\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b')


def discover_classes(root):
    """Select complete test classes, never individual methods or method filters."""
    found = []
    for path in sorted((Path(root) / 'be/src/test/java').rglob('*.java')):
        source = path.read_text()
        if not JUNIT_ANNOTATION.search(source):
            continue
        package = re.search(r'^\s*package\s+([\w.]+)\s*;', source, re.MULTILINE)
        if not package or not re.search(r'\bclass\s+' + re.escape(path.stem) + r'\b', source):
            raise ValueError('TEST_CLASS_DISCOVERY_FAILED')
        test = package.group(1) + '.' + path.stem
        if test not in RENDER_CLASSES:
            found.append(test)
    if not found or len(found) != len(set(found)):
        raise ValueError('TEST_CLASS_DISCOVERY_EMPTY_OR_DUPLICATE')
    return found


def selected_classes(root, focus=False):
    tests = discover_classes(root)
    if focus:
        tests = [name for name in tests if name.startswith(FOCUS_PACKAGES) or name in FOCUS_CLASSES
                 or name.rsplit('.', 1)[-1].startswith(('DemoAdmission', 'DemoCapacity', 'DemoCleanup'))]
        if not tests:
            raise ValueError('FOCUSED_TEST_CLASS_DISCOVERY_EMPTY')
    return tests


def validate_backend(report, selected, expected_counts):
    suites = report.get('suites', [])
    present = {row.get('test'): row for row in suites}
    if len(present) != len(suites) or set(present) != set(selected):
        return False
    for test, row in present.items():
        count = row.get('tests')
        expected = expected_counts.get(test.rsplit('.', 1)[-1])
        if type(count) is not int or count < 1 or expected is not None and count != expected:
            return False
        if any(row.get(key) != 0 for key in ('failures', 'errors', 'skipped')):
            return False
    return (report.get('status') == 'PASS' and report.get('cleanup_complete') is True
            and report.get('tests') == sum(row['tests'] for row in suites)
            and all(report.get(key) == 0 for key in ('failures', 'errors', 'skipped')))


def backend_expected_counts():
    import demo_chart_seed as baseline
    # Existing 21 service tests are preserved; three admission delegation/error
    # regressions were added in this change. No observed-result auto-adjustment.
    return {**baseline.EXPECTED_COUNTS, 'DemoAuthServiceTest': 24}


def backend_worker(args):
    # The worker is run only inside the owned copy; A1 emits to that copy, never
    # the source portfolio evidence. Its actual Docker/Redis ownership checks
    # and original CSV-client isolation selectors are reused without patches.
    import a1_budget_ownership as shared
    import demo_chart_seed as baseline
    tests = selected_classes(ROOT, args.focus)
    selectors = ['*' + test.rsplit('.', 1)[-1] for test in tests]
    if len(selectors) != len(set(selectors)):
        raise ValueError('AMBIGUOUS_SIMPLE_TEST_CLASS')
    sys.argv = [sys.argv[0], '--phase', 'green', '--cache-seed', str(args.cache_seed.resolve())]
    return shared.main(verification={
        'stage': 'DEMO_CAPACITY_CLEANUP', 'tests': {'green': selectors},
        'expected_counts': backend_expected_counts(), 'separate_classes': True, 'privilege_accounts': True,
        'csv_client_isolation_selectors': baseline.boundary.session.profile.csv.a21.ISOLATED_ORIGINAL_CONTEXTS,
        'evidence_parser': baseline.safe_evidence,
    })


def old_evidence():
    return {str(path.relative_to(ROOT)): digest(path)
            for folder in ('docs/portfolio/evidence', 'docs/deployment/evidence')
            for path in (ROOT / folder).rglob('*') if path.is_file()}


def unchanged(manifest):
    return all((ROOT / path).is_file() and digest(ROOT / path) == checksum
               for path, checksum in manifest.items())


def one_report(paths):
    paths = list(paths)
    if len(paths) != 1:
        raise ValueError('CURRENT_RUN_SUMMARY_MISSING_OR_AMBIGUOUS')
    value = json.loads(paths[0].read_text())
    if forbidden(value):
        raise ValueError('EVIDENCE_PROJECTION_REJECTED')
    return value


def evidence_output(root, directory, label):
    """Only distinct reviewed evidence namespaces; never arbitrary output paths."""
    if directory not in ('DEMO_CAPACITY_CLEANUP', 'COUNTERLESS_DEMO_CAPACITY', 'DEMO_ADMISSION_LOCK', 'TIDB_CLEANUP_READONLY', 'DEMO_ABUSE_GUARD', 'MOBILE_CHART', 'COLD_START_RECOVERY', 'PUBLIC_SCANNER_AUDIT', 'COOKIE_E2E_TIME_BASIS', 'FULL_DEMO_EXPERIENCE'):
        raise ValueError('EVIDENCE_DIRECTORY_REJECTED')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', label):
        raise ValueError('EVIDENCE_LABEL_REJECTED')
    output = Path(root) / 'docs/deployment/evidence' / directory / label
    if output.exists():
        raise ValueError('EVIDENCE_ALREADY_EXISTS')
    return output


def main(evidence_directory='DEMO_CAPACITY_CLEANUP', *, frontend_counts=(151, 101),
         entrypoint='scripts/verification/demo_capacity_checks.py', frontend_entrypoint=None, browser_cases=4):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase', required=True, choices=('fe', 'be', 'browser', 'render'))
    parser.add_argument('--cache-seed', type=Path)
    parser.add_argument('--dependencies', type=Path)
    parser.add_argument('--browser-path', type=Path)
    parser.add_argument('--focus', action='store_true', help='BE admission/cleanup classes only; not full regression')
    parser.add_argument('--run-label', default=None)
    parser.add_argument('--be-worker', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.focus and args.phase != 'be':
        parser.error('--focus is only valid for BE')
    if args.phase in ('be', 'browser', 'render'):
        if not args.cache_seed or not all((args.cache_seed / name).is_dir() for name in ('caches', 'wrapper')):
            parser.error('An explicit populated owned Gradle cache is required')
    if args.phase in ('fe', 'browser'):
        if not args.dependencies or not (args.dependencies / 'node_modules').is_dir():
            parser.error('An explicit exact-lockfile dependency installation is required')
    if args.phase == 'browser' and (not args.browser_path or not args.browser_path.is_dir()):
        parser.error('An explicit owned Playwright browser directory is required')
    if args.be_worker:
        if args.phase != 'be' or not (ROOT / '.capacity-copy-owner').is_file():
            parser.error('BE worker requires the owned private-copy boundary')
        return backend_worker(args)
    label = args.run_label or args.phase + '-' + uuid.uuid4().hex[:12]
    try:
        output = evidence_output(ROOT, evidence_directory, label)
    except ValueError as error:
        parser.error(str(error))
    os.umask(0o077)
    files = inputs()
    guarded = ('fe',) if args.phase == 'fe' else ('be', 'fe') if args.phase == 'browser' else ('be',)
    source_before = {str(path): digest(ROOT / path) for path in files if path.parts[0] in guarded}
    evidence_before = old_evidence()
    work = Path(tempfile.mkdtemp(prefix='moneytoad-capacity-check-')).resolve()
    copy = work / 'snapshot'
    result = {'phase': args.phase, 'focused': args.focus, 'status': 'BLOCKED',
              'managed_provider_used': False, 'provider_connections_attempted': 0,
              'source_preserved': False, 'historical_evidence_preserved': False,
              'cleanup_complete': False}
    process = None
    overlay = None
    cleanup_safe = True
    resource_cleanup_observed = False
    previous = {}

    def interrupted(signum, frame):
        raise KeyboardInterrupt()

    try:
        for signum in (signal.SIGTERM, signal.SIGINT):
            previous[signum] = signal.signal(signum, interrupted)
        for relative in files:
            target = copy / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        (copy / '.capacity-copy-owner').write_text(label)
        (copy / '.capacity-copy-inputs.json').write_text(json.dumps({'owner': label, 'files': {
            str(path): digest(copy / path) for path in files if path.parts[0] == 'fe'}}))
        (work / 'tmp').mkdir()
        env = {'PATH': '/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin',
               'HOME': str(Path.home()), 'TMPDIR': str(work / 'tmp'), 'LANG': 'en_US.UTF-8',
               'NO_COLOR': '1', 'PYTHONDONTWRITEBYTECODE': '1'}
        command = [sys.executable, '-B']
        if args.phase == 'fe':
            command += ([frontend_entrypoint, '--fe-worker'] if frontend_entrypoint else ['scripts/verification/deployment_fe_checks.py']) + ['--dependencies', str(args.dependencies.resolve()),
                        '--run-label', 'capacity-current', '--owned-input-manifest', str(copy / '.capacity-copy-inputs.json')]
        elif args.phase == 'be':
            selected = selected_classes(copy, args.focus)
            result['selected_test_classes'] = selected
            result['render_test_classes_in_separate_phase'] = sorted(RENDER_CLASSES)
            command += [entrypoint, '--phase', 'be', '--be-worker',
                        '--cache-seed', str(args.cache_seed.resolve())]
            if args.focus:
                command.append('--focus')
        elif args.phase == 'render':
            command += ['scripts/verification/render_runtime.py', '--cache-seed', str(args.cache_seed.resolve())]
        else:
            from dependency_overlay import DependencyOverlay
            overlay = DependencyOverlay(copy / 'fe', args.dependencies.resolve()).create()
            command += ['scripts/verification/demo_browser_e2e.py', '--runs', '2',
                        '--cache-seed', str(args.cache_seed.resolve()), '--browser-path', str(args.browser_path.resolve())]
        # Child raw stdout is deliberately discarded. Safe summaries and compiler
        # category projections come from the already reviewed local runners.
        process = subprocess.Popen(command, cwd=copy, env=env, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, start_new_session=True)
        process.wait(timeout=7200 if args.phase == 'be' else 3600)
        result['exit_code'] = process.returncode
        if args.phase == 'fe':
            report = one_report((copy / 'docs/deployment/evidence/AUTONOMOUS_CONTINUATION').glob('*/fe-summary.json'))
            resource_cleanup_observed = report.get('cleanup_complete') is True
            checks = {row.get('check'): row for row in report.get('checks', [])}
            passed = report.get('status') == 'PASS' and report.get('tests') == sum(frontend_counts)
            for mode, count in zip(('oauth-tests', 'demo-tests'), frontend_counts):
                row = checks.get(mode, {})
                passed = passed and row.get('tests') == row.get('passed') == count and all(row.get(k) == 0 for k in ('failed', 'skipped', 'todo'))
            passed = passed and checks.get('lint', {}).get('errors') == checks.get('lint', {}).get('warnings') == 0
            passed = passed and all(checks.get(check, {}).get('status') == 'PASS' for check in (
                'test-types', 'e2e-types', 'functions-types', 'oauth-build', 'demo-build', 'invalid-mode-build', 'lint'))
        elif args.phase == 'be':
            report = one_report([copy / 'docs/portfolio/evidence/be-regression-summary.json'])
            resource_cleanup_observed = report.get('cleanup_complete') is True
            passed = validate_backend(report, selected, backend_expected_counts())
        elif args.phase == 'render':
            report = one_report((copy / 'docs/deployment/evidence/RENDER_RUNTIME').glob('*/runtime-summary.json'))
            resource_cleanup_observed = report.get('cleanup_complete') is True
            passed = report.get('status') == 'PASS' and report.get('local_render_runtime_ready') is True
        else:
            report = one_report([copy / 'docs/portfolio/evidence/demo-browser-e2e-summary.json'])
            runs = report.get('runs', [])
            resource_cleanup_observed = bool(runs) and all(row.get('cleanup_complete') is True for row in runs)
            passed = (report.get('status') == 'PASS' and len(runs) == 2 and all(row.get('status') == 'PASS'
                      and row.get('external_attempts') == 0 and row.get('cases') == browser_cases for row in runs))
            # Keep the existing explicit browser projection (no raw artifacts).
            result['browser_runs'] = [one_report([copy / 'docs/portfolio/evidence' / row['evidence_file']]) for row in runs]
        result['checks'] = report
        result['status'] = 'PASS' if process.returncode == 0 and passed and resource_cleanup_observed else 'FAIL'
    except KeyboardInterrupt:
        result['diagnostic'] = 'INTERRUPTED'
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        result['diagnostic'] = type(error).__name__
    finally:
        for signum in previous:
            signal.signal(signum, signal.SIG_IGN)
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=90)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
                cleanup_safe = False
        if process is not None and process.poll() is not None and not resource_cleanup_observed:
            # Interrupted children may complete their finally cleanup after the
            # normal result-reading path was left. Read only this fresh copy.
            try:
                if args.phase == 'fe':
                    completed = one_report((copy / 'docs/deployment/evidence/AUTONOMOUS_CONTINUATION').glob('*/fe-summary.json'))
                elif args.phase == 'render':
                    completed = one_report((copy / 'docs/deployment/evidence/RENDER_RUNTIME').glob('*/runtime-summary.json'))
                elif args.phase == 'be':
                    completed = one_report([copy / 'docs/portfolio/evidence/be-regression-summary.json'])
                else:
                    completed = one_report([copy / 'docs/portfolio/evidence/demo-browser-e2e-summary.json'])
                if args.phase == 'browser':
                    completed_runs = completed.get('runs', [])
                    resource_cleanup_observed = bool(completed_runs) and all(row.get('cleanup_complete') is True for row in completed_runs)
                else:
                    resource_cleanup_observed = completed.get('cleanup_complete') is True
            except (OSError, ValueError, KeyError):
                pass
        result['source_preserved'] = unchanged(source_before)
        result['historical_evidence_preserved'] = unchanged(evidence_before)
        if overlay is not None:
            try:
                overlay.close()
            except (OSError, ValueError):
                cleanup_safe = False
            result['dependencies'] = overlay.report
        # An unobserved resource cleanup keeps private recovery material in place;
        # merely terminating the parent runner is never reported as cleanup PASS.
        may_remove = cleanup_safe and (process is None or resource_cleanup_observed)
        if may_remove:
            try:
                shutil.rmtree(work)
            except OSError:
                cleanup_safe = False
        result['cleanup_complete'] = may_remove and cleanup_safe and not work.exists()
        if not all(result[key] for key in ('source_preserved', 'historical_evidence_preserved', 'cleanup_complete')):
            result['status'] = 'FAIL'
        if forbidden(result):
            result = {'phase': args.phase, 'status': 'FAIL', 'diagnostic': 'EvidenceProjectionRejected',
                      'managed_provider_used': False, 'cleanup_complete': result['cleanup_complete']}
        output.mkdir(parents=True, exist_ok=False)
        (output / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        for signum, handler in previous.items():
            signal.signal(signum, handler)
        print(json.dumps({key: result[key] for key in ('phase', 'status', 'cleanup_complete')}), flush=True)
    return 0 if result['status'] == 'PASS' else 2


if __name__ == '__main__':
    raise SystemExit(main())
