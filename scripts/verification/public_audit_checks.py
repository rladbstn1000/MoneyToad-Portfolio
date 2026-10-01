#!/usr/bin/env python3
"""Public scanner audit regression using unchanged local resource ownership and six-case browser contracts."""
import sys
import demo_capacity_checks as base

FRONTEND_COUNTS = (180, 148)
ENTRYPOINT = 'scripts/verification/public_audit_checks.py'


def main():
    if '--fe-worker' in sys.argv:
        sys.argv.remove('--fe-worker')
        import deployment_fe_checks as frontend
        frontend.EXPECTED = dict(zip(('oauth', 'demo'), FRONTEND_COUNTS))
        return frontend.main(server_only_canaries=('A' * 43, '192.0.2.123'))
    return base.main(evidence_directory='PUBLIC_SCANNER_AUDIT', frontend_counts=FRONTEND_COUNTS,
                     entrypoint=ENTRYPOINT, frontend_entrypoint=ENTRYPOINT, browser_cases=6)


if __name__ == '__main__':
    raise SystemExit(main())
