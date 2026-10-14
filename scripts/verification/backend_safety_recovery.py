#!/usr/bin/env python3
"""Reconstructed adapter to existing owned local runners; persistent source is never cleanup input."""
import hashlib
import json
from pathlib import Path
import stat
import sys
from unittest.mock import patch
import demo_capacity_checks as base
from render_regression import inputs, ROOT

ENTRYPOINT = 'scripts/verification/backend_safety_recovery.py'
FOCUS = frozenset((
    'TransactionCategoryContractIntegrationTest', 'DemoChartSeedHttpIntegrationTest',
    'CardCvcContractIntegrationTest', 'CardControllerCsvContractTest',
    'DemoDatasetValidatorTest', 'DemoCleanupMySqlTest',
    'CleanupReadOnlyGuardTest', 'DemoCleanupConnectorReadOnlyMySqlTest', 'CleanupTiDbProbeSafetyTest',
))


def source_manifest():
    return [{'path': str(path), 'sha256': hashlib.sha256((ROOT / path).read_bytes()).hexdigest(),
             'mode': oct(stat.S_IMODE((ROOT / path).stat().st_mode))}
            for path in inputs() if path.parts[0] in ('be', 'fe', 'scripts')]


def manifest_digest(rows):
    return hashlib.sha256(json.dumps(rows, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def main():
    if '--phase' in sys.argv and sys.argv[sys.argv.index('--phase') + 1:][:1] == ['fe']:
        raise SystemExit('Use local_demo_checks.py for the current three-mode frontend suite.')
    original_selected = base.selected_classes
    original_output = base.evidence_output
    original_counts = base.backend_expected_counts
    destination = []

    def selected(root, focus=False):
        tests = original_selected(root, False)
        if focus:
            tests = [name for name in tests if name.rsplit('.', 1)[-1] in FOCUS]
            if not any(name.endswith('TransactionCategoryContractIntegrationTest') for name in tests):
                raise ValueError('CATEGORY_CONTRACT_TEST_MISSING')
        return tests

    def output(root, directory, label):
        original_output(root, 'COOKIE_E2E_TIME_BASIS', label)
        target = Path(root) / 'docs/portfolio/evidence/BACKEND_SAFETY_RECOVERY' / label
        if target.exists():
            raise ValueError('EVIDENCE_ALREADY_EXISTS')
        destination.append(target)
        return target

    def counts():
        # Current baseline has eleven fixed-clock cookie tests; do not infer counts from results.
        return {**original_counts(), 'DemoRefreshCookieTest': 11}

    worker = '--be-worker' in sys.argv
    before = None if worker else source_manifest()
    with patch.object(base, 'selected_classes', selected), patch.object(base, 'evidence_output', output), patch.object(base, 'backend_expected_counts', counts):
        code = base.main(entrypoint=ENTRYPOINT, browser_cases=6)
    if not worker and destination:
        path = destination[0] / 'summary.json'
        result = json.loads(path.read_text())
        result['verification_source_manifest_sha256'] = manifest_digest(before)
        result['verification_sources_unchanged'] = before == source_manifest()
        if not result['verification_sources_unchanged']:
            result['status'] = 'FAIL'
            code = 2
        path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        (destination[0] / 'source-manifest.json').write_text(json.dumps(before, ensure_ascii=False, indent=2) + '\n')
    return code


if __name__ == '__main__':
    raise SystemExit(main())
