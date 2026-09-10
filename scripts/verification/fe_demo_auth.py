#!/usr/bin/env python3
"""Run unchanged BE251 with owned loopback resources; write only new FE stage evidence.

Usage: python3 scripts/verification/fe_demo_auth.py --phase green [existing runner options]
FE-only checks are documented in 11-fe-demo-auth.md; this runner never starts the FE or AI.
"""
import sys
sys.dont_write_bytecode = True
import demo_auth_http as boundary

if __name__ == '__main__':
    sys.exit(boundary.session.profile.csv.a21.shared.main(verification={
        'stage': 'FE_DEMO_AUTH', 'tests': {'green': boundary.TESTS['green']},
        'expected_counts': boundary.EXPECTED_COUNTS, 'separate_classes': True,
        'csv_client_isolation_selectors': boundary.session.profile.csv.a21.ISOLATED_ORIGINAL_CONTEXTS,
        'evidence_parser': boundary.safe_evidence,
    }))
