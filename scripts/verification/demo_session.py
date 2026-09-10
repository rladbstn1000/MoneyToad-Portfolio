#!/usr/bin/env python3
"""Verify demo session/JWT/Redis contracts using only disposable owned resources."""
import json
import re
import sys
sys.dont_write_bytecode = True
import demo_auth_profile as profile

NEW_TESTS = ["*DemoSessionBoundaryIntegrationTest", "*DemoJwtContractTest", "*DemoSessionServiceTest",
             "*DemoSessionStoreRedisTest", "*DemoSessionFilterContractTest"]
TESTS = {"red": [NEW_TESTS[0]], "green": [*NEW_TESTS, *profile.TESTS["green"]], "focus": NEW_TESTS}
EXPECTED_COUNTS = {**profile.EXPECTED_COUNTS, "DemoSessionBoundaryIntegrationTest": 13,
                   "DemoJwtContractTest": 12, "DemoSessionServiceTest": 14,
                   "DemoSessionStoreRedisTest": 16, "DemoSessionFilterContractTest": 14}

def safe_evidence(line):
    if not line.startswith("DEMO_SESSION_EVIDENCE "):
        return profile.safe_evidence(line)
    try:
        value = json.loads(line.split(" ", 1)[1])
        scenario = value.get("scenario")
        if not isinstance(scenario, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", scenario):
            return None
        result = {"kind": "DEMO_SESSION_EVIDENCE", "scenario": scenario}
        for key, item in value.items():
            if re.fullmatch(r"[a-zA-Z][a-zA-Z0-9]{0,60}", key) and (type(item) is bool or type(item) is int and 0 <= item <= 100000):
                result[key] = item
        return result
    except (AttributeError, TypeError, ValueError):
        return None

def expected_red(result):
    suites = result["suites"]
    cases = [c for s in suites for c in s["cases"]]
    rows = result.get("boundary_evidence", [])
    return (len(suites) == 1 and suites[0]["tests"] in (2, EXPECTED_COUNTS["DemoSessionBoundaryIntegrationTest"])
        and suites[0]["failures"] == 1 and suites[0]["errors"] == 0 and suites[0]["skipped"] == 0
        and any(c["name"].startswith("originalAccessStillWorks") and c["status"] == "PASS" for c in cases)
        and any(c["name"].startswith("demoRejectsGeneralAccess") and c.get("assertion_failure") for c in cases)
        and any(r.get("scenario") == "ordinary_at_rejected_in_demo" and r.get("httpStatus") == 200
            and r.get("databaseUnchanged") and r.get("stubRequests") == 0 for r in rows))

if __name__ == "__main__":
    sys.exit(profile.csv.a21.shared.main(verification={
        "stage": "DEMO_SESSION", "tests": TESTS, "expected_counts": EXPECTED_COUNTS,
        "separate_classes": True,
        "csv_client_isolation_selectors": profile.csv.a21.ISOLATED_ORIGINAL_CONTEXTS,
        "evidence_parser": safe_evidence, "red_classifier": expected_red,
    }))
