#!/usr/bin/env python3
"""Verify the product OAuth/demo profile boundary with disposable local resources.

The existing runner supplies only synthetic A1_* infrastructure credentials.
The new tests use those values in a fresh Spring environment and load the actual
product ConfigData, never application-a1.yml. Existing 87 regressions retain their
original configuration and assertions. No source restoration is performed.
"""

import json
import re
import sys

sys.dont_write_bytecode = True
import csv_client_config as csv


NEW_TEST = "*DemoAuthProfileBoundaryTest"
TESTS = {"red": [NEW_TEST], "green": [NEW_TEST, *csv.TESTS["green"]]}
EXPECTED_COUNTS = {
    "DemoAuthProfileBoundaryTest": 9,
    "CsvClientConfigTest": 21,
    "CsvServiceOwnershipTest": 11,
    "CsvBoundaryIntegrationTest": 14,
    "CsvControllerRegistrationTest": 3,
    "CardControllerCsvContractTest": 5,
    "BudgetServiceOwnershipTest": 4,
    "BudgetOwnershipIntegrationTest": 5,
    "DonApplicationTests": 1,
    "NotFoundContractIntegrationTest": 16,
    "GlobalExceptionHandlerContractTest": 7,
}


def safe_evidence(line):
    if not line.startswith("DEMO_AUTH_PROFILE_EVIDENCE "):
        return csv.safe_evidence(line)
    try:
        value = json.loads(line.split(" ", 1)[1])
        if not isinstance(value, dict):
            return None
        scenario = value.get("scenario")
        if not isinstance(scenario, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", scenario):
            return None
        result = {"kind": "DEMO_AUTH_PROFILE_EVIDENCE", "scenario": scenario}
        failure_type = value.get("failureRootType")
        if isinstance(failure_type, str) and re.fullmatch(r"[A-Za-z0-9_.$]{1,160}", failure_type):
            result["failureRootType"] = failure_type
        # Only fixed boolean/count observations survive; no exception messages,
        # JWTs, environment values, response bodies, or arbitrary bean output.
        for key, item in value.items():
            if not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9]{0,60}", key):
                continue
            if type(item) is bool or type(item) is int and 0 <= item <= 100000:
                result[key] = item
        return result
    except (TypeError, ValueError):
        return None


def expected_red(result):
    suites = result["suites"]
    cases = [case for suite in suites for case in suite["cases"]]
    failed = [case for case in cases if case["status"] == "FAIL"]
    result["red_observations"] = {
        "new_contract_class_executed": len(suites) == 1 and suites[0]["name"].endswith("DemoAuthProfileBoundaryTest"),
        "no_junit_errors_or_skips": bool(suites) and all(s["errors"] == 0 and s["skipped"] == 0 for s in suites),
        "actual_assertion_failures": bool(failed) and all(c.get("assertion_failure") for c in failed),
        "unchanged_original_control_passed": any(c["status"] == "PASS" and "original" in c["name"].lower() for c in cases),
    }
    return all(result["red_observations"].values())


if __name__ == "__main__":
    sys.exit(csv.a21.shared.main(verification={
        "stage": "DEMO_AUTH_PROFILE",
        "tests": TESTS,
        "expected_counts": EXPECTED_COUNTS,
        "separate_classes": True,
        "csv_client_isolation_selectors": csv.a21.ISOLATED_ORIGINAL_CONTEXTS,
        "evidence_parser": safe_evidence,
        "red_classifier": expected_red,
    }))
