#!/usr/bin/env python3
"""Post-PATCH response correlation checks with unchanged local resource owners."""
from unittest.mock import patch

import full_demo_checks as baseline

# Existing OAuth225/demo234 plus 14 request/response-order regression cases.
FRONTEND_COUNTS = (239, 234)
ENTRYPOINT = 'scripts/verification/post_patch_response_checks.py'


def main():
    # Keep the established evidence boundary and all existing check selectors.
    # Distinct correlation-* run labels separate these results from stage 23.
    with patch.object(baseline, 'FRONTEND_COUNTS', FRONTEND_COUNTS), patch.object(baseline, 'ENTRYPOINT', ENTRYPOINT):
        return baseline.main()


if __name__ == '__main__':
    raise SystemExit(main())
