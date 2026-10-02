#!/usr/bin/env python3
"""Cookie verification only; unchanged isolated resource owners and core six-case checks."""
import sys
from unittest.mock import patch
import demo_capacity_checks as base

# Original OAuth180/demo148 plus the explicitly reviewed cookie helper cases.
FRONTEND_COUNTS = (225, 148)  # 45 new helper cases; original 328 remain selected.
ENTRYPOINT = 'scripts/verification/cookie_time_basis_checks.py'
EVIDENCE = 'COOKIE_E2E_TIME_BASIS'
COOKIE_CLASS = 'com.potg.don.auth.demo.DemoRefreshCookieTest'


def main():
    if '--fe-worker' in sys.argv:
        sys.argv.remove('--fe-worker')
        import deployment_fe_checks as frontend
        frontend.EXPECTED = dict(zip(('oauth', 'demo'), FRONTEND_COUNTS))
        return frontend.main(server_only_canaries=('A' * 43, '192.0.2.123'))
    if '--phase' in sys.argv and sys.argv[sys.argv.index('--phase') + 1] == 'scan':
        offset = sys.argv.index('--phase')
        del sys.argv[offset:offset + 2]
        import public_audit_scan
        return public_audit_scan.main(evidence_directory=EVIDENCE)
    original_selected = base.selected_classes
    original_counts = base.backend_expected_counts

    def selected(root, focus=False):
        all_classes = original_selected(root, False)
        if not focus:
            return all_classes
        if COOKIE_CLASS not in all_classes:
            raise ValueError('COOKIE_CONTRACT_TEST_MISSING')
        return [COOKIE_CLASS]

    def counts():
        # Eight original methods plus three new fixed-Clock boundary methods.
        return {**original_counts(), 'DemoRefreshCookieTest': 11}

    with patch.object(base, 'selected_classes', selected), patch.object(base, 'backend_expected_counts', counts):
        return base.main(evidence_directory=EVIDENCE, frontend_counts=FRONTEND_COUNTS,
                         entrypoint=ENTRYPOINT, frontend_entrypoint=ENTRYPOINT, browser_cases=6)


if __name__ == '__main__':
    raise SystemExit(main())
