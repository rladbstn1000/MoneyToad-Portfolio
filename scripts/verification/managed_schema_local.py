#!/usr/bin/env python3
"""Run reviewed DDL through existing local TLS checks without changing original files.

No provider credentials are read. Three exact preparation/diagnostic anchors are adapted
in a private Git-free copy; product sources, selectors and assertions stay intact.
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

OUTPUT = ROOT / 'docs/deployment/evidence/MANAGED_PROVIDER_CONTRACTS'
RUNNER = Path('scripts/verification/render_runtime.py')
DDL = Path('scripts/verification/fixtures/managed-provider-schema.sql')
HELPER = Path('be/src/test/java/com/potg/don/auth/ManagedSchemaPreparation.java')
OLD_CLASS = 'com.potg.don.auth.RenderSchemaPreparation'
NEW_CLASS = 'com.potg.don.auth.ManagedSchemaPreparation'
PREPARATION = "        phase = 'schema-preparation'\n"
DIAGNOSTIC = r'RENDER_RUNTIME: [A-Z_]+|ERROR [0-9]+'
MANAGED_DIAGNOSTIC = r'MANAGED_SCHEMA_LOCAL_PREPARATION_FAIL: [A-Z_]+'


def adapt_runner(text):
    if (text.count(OLD_CLASS) != 1 or text.count(PREPARATION) != 1
            or text.count(DIAGNOSTIC) != 1):
        raise ValueError('PRIVATE_ADAPTATION_ANCHOR_MISMATCH')
    if NEW_CLASS in text or 'RUNNER_MANAGED_SCHEMA_DDL' in text or MANAGED_DIAGNOSTIC in text:
        raise ValueError('PRIVATE_ADAPTATION_ALREADY_PRESENT')
    return text.replace(DIAGNOSTIC, MANAGED_DIAGNOSTIC + '|' + DIAGNOSTIC).replace(
        OLD_CLASS, NEW_CLASS).replace(PREPARATION,
        "        test_env['RUNNER_MANAGED_SCHEMA_DDL'] = str(ROOT / " + repr(DDL.as_posix()) + ")\n"
        + PREPARATION)


def past_evidence():
    return {str(path.relative_to(ROOT)): digest(path)
            for directory in ('docs/portfolio/evidence', 'docs/deployment/evidence')
            for path in (ROOT / directory).rglob('*') if path.is_file()}


def unchanged(manifest):
    return all((ROOT / name).is_file() and digest(ROOT / name) == checksum
               for name, checksum in manifest.items())


def group_gone(process):
    if process is None:
        return True
    try:
        os.killpg(process.pid, 0)
        return False
    except ProcessLookupError:
        return True


def stop_child(process):
    # Allow the existing runner to clean its separately owned process groups/resources.
    if process is None or process.poll() is not None:
        return True
    process.terminate()
    try:
        process.communicate(timeout=240)
        return True
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.communicate(timeout=15)
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache-seed', type=Path, required=True)
    parser.add_argument('--run-label', help='Fresh output label; never overwrite existing evidence')
    args = parser.parse_args()
    label = args.run_label or ('local-ddl-' + uuid.uuid4().hex[:12])
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', label):
        parser.error('Invalid run label')
    output = OUTPUT / label
    if output.exists():
        parser.error('Output exists; choose a fresh run label')
    cache = args.cache_seed.resolve()
    if not all((cache / name).is_dir() for name in ('caches', 'wrapper')):
        parser.error('Explicit complete isolated Gradle cache required')
    files = inputs()
    source_before = {str(path): digest(ROOT / path) for path in files
                     if path.parts[0] in ('be', 'fe', 'scripts')}
    evidence_before = past_evidence()
    os.umask(0o077)
    work = Path(tempfile.mkdtemp(prefix='moneytoad-managed-local-ddl-')).resolve()
    snapshot = work / 'snapshot'
    result = {
        'status': 'BLOCKED', 'environment': 'local-only',
        'preparation': 'reviewed-ddl-instead-of-hibernate-schema-generation',
        'private_adaptations': ['preparation-helper-class', 'reviewed-ddl-path-environment',
                               'fixed-schema-diagnostic-marker'],
        'product_and_test_assertions_changed': False,
        'provider_credentials_read': False, 'provider_connections_attempted': 0,
        'managed_provider_contracts_verified': False, 'public_deployment_ready': False,
        'local_schema_runtime_verified': False,
        'source_preserved': False, 'historical_evidence_preserved': False,
        'child_process_group_released': False, 'cleanup_complete': False,
        'private_copy_removed': False,
    }
    process = None
    finished = False
    previous = {}

    def interrupted(signum, frame):
        raise KeyboardInterrupt()

    try:
        for signum in (signal.SIGTERM, signal.SIGINT):
            previous[signum] = signal.signal(signum, interrupted)
        snapshot.mkdir()
        (work / 'tmp').mkdir()
        if any(path not in files for path in (RUNNER, DDL, HELPER)):
            raise ValueError('REVIEWED_LOCAL_INPUT_MISSING')
        for relative in files:
            target = snapshot / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)
        runner = snapshot / RUNNER
        runner.write_text(adapt_runner(runner.read_text()))
        env = {'PATH': '/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin',
               'HOME': str(Path.home()), 'TMPDIR': str(work / 'tmp'),
               'LANG': 'en_US.UTF-8', 'NO_COLOR': '1', 'PYTHONDONTWRITEBYTECODE': '1'}
        command = [sys.executable, '-B', str(RUNNER), '--cache-seed', str(cache)]
        process = subprocess.Popen(command, cwd=snapshot, env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True, start_new_session=True)
        # Raw child output is discarded, even though the existing runner prints only progress.
        process.communicate(timeout=2400)
        result['exit_code'] = process.returncode
        finished = True
    except KeyboardInterrupt:
        result['status'] = 'INTERRUPTED'
        result['diagnostic'] = 'Interrupted'
    except subprocess.TimeoutExpired:
        result['status'] = 'FAIL'
        result['diagnostic'] = 'OuterObservationDeadlineExceeded'
    except (OSError, ValueError, subprocess.SubprocessError) as failure:
        result['diagnostic'] = type(failure).__name__
    finally:
        for signum in previous:
            signal.signal(signum, signal.SIG_IGN)
        try:
            child_graceful = stop_child(process)
        except (OSError, subprocess.SubprocessError):
            child_graceful = False
        result['child_process_group_released'] = group_gone(process)
        reports = list((snapshot / 'docs/deployment/evidence/RENDER_RUNTIME').glob('*/runtime-summary.json'))
        child_cleaned = process is None
        if len(reports) == 1:
            try:
                runtime = json.loads(reports[0].read_text())
                if forbidden(runtime):
                    raise ValueError('PRIVATE_RESULT_PROJECTION_REJECTED')
                result['runtime'] = runtime
                child_cleaned = (runtime.get('cleanup_complete') is True
                    and runtime.get('owned_docker_resources_remaining') == 0
                    and runtime.get('owned_ports_released') is True)
                checks = {entry['check']: entry for entry in runtime.get('checks', [])}
                counted = all(checks.get(name, {}).get('tests') == expected
                    and checks[name].get('status') == 'PASS'
                    and all(checks[name].get(key) == 0 for key in ('failures', 'errors', 'skipped'))
                    for name, expected in (('configuration-tests', 85), ('actual-configdata-tls-integration', 6)))
                passed = (finished and process.returncode == 0 and runtime.get('status') == 'PASS'
                    and runtime.get('local_render_runtime_ready') is True and counted
                    and all(checks.get(name, {}).get('status') == 'PASS' for name in
                        ('separate-schema-preparation', 'validate-did-not-change-schema',
                         'actual-container-limits-and-isolation', 'limited-seed-row-delta')))
                if finished:
                    result['status'] = 'PASS' if passed else 'FAIL'
            except (OSError, ValueError, TypeError, KeyError):
                result['status'] = 'FAIL'
                result['diagnostic'] = 'LocalResultMissingOrUnsafe'
        elif process is not None:
            result['status'] = 'FAIL'
            result['diagnostic'] = 'LocalResultMissingOrAmbiguous'
        result['source_preserved'] = unchanged(source_before)
        result['historical_evidence_preserved'] = unchanged(evidence_before)
        safe = child_graceful and child_cleaned and result['child_process_group_released']
        if safe:
            try:
                shutil.rmtree(work)
                result['private_copy_removed'] = not work.exists()
            except OSError:
                pass
        result['cleanup_complete'] = safe and result['private_copy_removed']
        if not all(result[key] for key in ('source_preserved', 'historical_evidence_preserved', 'cleanup_complete')):
            result['status'] = 'FAIL'
        result['local_schema_runtime_verified'] = result['status'] == 'PASS'
        if forbidden(result):
            raise ValueError('Public projection rejected; values suppressed')
        output.mkdir(parents=True, exist_ok=False)
        (output / 'local-ddl-summary.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({key: result[key] for key in ('status', 'environment',
            'local_schema_runtime_verified', 'cleanup_complete', 'source_preserved',
            'historical_evidence_preserved', 'managed_provider_contracts_verified')}), flush=True)
        for signum, handler in previous.items():
            signal.signal(signum, handler)
    return 0 if result['local_schema_runtime_verified'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
