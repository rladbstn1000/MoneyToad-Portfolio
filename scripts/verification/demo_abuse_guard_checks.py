#!/usr/bin/env python3
"""Public-demo abuse guard checks using the existing isolated local resource owners."""
import sys
import demo_capacity_checks as base

# Explicit counts include all existing cases; update only for reviewed test additions.
FRONTEND_COUNTS = (175, 119)
ENTRYPOINT = 'scripts/verification/demo_abuse_guard_checks.py'


def main():
    if '--fe-worker' in sys.argv:
        sys.argv.remove('--fe-worker')
        import deployment_fe_checks as frontend
        frontend.EXPECTED = dict(zip(('oauth', 'demo'), FRONTEND_COUNTS))
        return frontend.main(server_only_canaries=('A' * 43, '192.0.2.123'))
    original = base.selected_classes
    def selected(root, focus=False):
        if not focus:
            return original(root, False)
        return [name for name in base.discover_classes(root) if name.rsplit('.', 1)[-1].startswith(
            ('DemoGateway', 'DemoClientAddress', 'DemoAbuse'))]
    base.selected_classes = selected
    return base.main(evidence_directory='DEMO_ABUSE_GUARD', frontend_counts=FRONTEND_COUNTS,
                     entrypoint=ENTRYPOINT, frontend_entrypoint=ENTRYPOINT, browser_cases=6)


if __name__ == '__main__':
    raise SystemExit(main())
