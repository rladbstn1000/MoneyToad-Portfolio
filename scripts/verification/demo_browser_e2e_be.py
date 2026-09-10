#!/usr/bin/env python3
"""Unchanged BE275 selectors and isolation, with explicit allowlist projection at the shared output boundary."""
import json
import sys
sys.dont_write_bytecode = True
import demo_chart_seed as seed

shared = seed.boundary.session.profile.csv.a21.shared


if __name__ == '__main__':
    sys.exit(shared.main(verification={
        'stage': 'DEMO_BROWSER_E2E', 'tests': {'green': seed.TESTS['green']},
        'expected_counts': seed.EXPECTED_COUNTS, 'separate_classes': True,
        'csv_client_isolation_selectors': seed.boundary.session.profile.csv.a21.ISOLATED_ORIGINAL_CONTEXTS,
        'evidence_parser': seed.safe_evidence,
    }))
