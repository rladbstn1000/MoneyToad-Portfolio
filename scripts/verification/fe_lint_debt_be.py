#!/usr/bin/env python3
"""Re-run the unchanged 275 BE cases in owned resources, with new lint-stage evidence."""
import sys
sys.dont_write_bytecode = True
import demo_chart_seed as seed


if __name__ == "__main__":
    sys.exit(seed.boundary.session.profile.csv.a21.shared.main(verification={
        "stage": "FE_LINT_DEBT", "tests": {"green": seed.TESTS["green"]},
        "expected_counts": seed.EXPECTED_COUNTS, "separate_classes": True,
        "csv_client_isolation_selectors": seed.boundary.session.profile.csv.a21.ISOLATED_ORIGINAL_CONTEXTS,
        "evidence_parser": seed.safe_evidence,
    }))
