#!/usr/bin/env python3
"""Full demo local regression; existing isolated resource ownership and assertions."""
import sys
from unittest.mock import patch
import demo_capacity_checks as base
from mobile_chart_browser import source_files

FRONTEND_COUNTS = (225, 234)  # Existing 373 + 86 full-experience contracts.
ENTRYPOINT = 'scripts/verification/full_demo_checks.py'
EVIDENCE = 'FULL_DEMO_EXPERIENCE'


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
    original_counts = base.backend_expected_counts
    def counts():
        return {**original_counts(), 'DemoRefreshCookieTest': 11}
    with patch.object(base, 'inputs', lambda: list(source_files())), patch.object(base, 'backend_expected_counts', counts):
        return base.main(evidence_directory=EVIDENCE, frontend_counts=FRONTEND_COUNTS,
                         entrypoint=ENTRYPOINT, frontend_entrypoint=ENTRYPOINT, browser_cases=6)


if __name__ == '__main__':
    raise SystemExit(main())
