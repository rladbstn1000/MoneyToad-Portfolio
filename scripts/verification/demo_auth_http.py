#!/usr/bin/env python3
"""Verify demo HTTP auth using inspected, disposable loopback resources."""
import json
import re
import sys
sys.dont_write_bytecode = True
import demo_session as session

NEW_TESTS = ["*DemoAuthHttpIntegrationTest", "*DemoAuthServiceTest", "*DemoRefreshCookieTest", "*DemoAuthControllerTest", "*DemoAuthLoginTransactionTest", "*DemoAuthFilterAvailabilityTest", "*DemoAuthHttpRequestContractTest"]
TESTS = {"red": [NEW_TESTS[0]], "green": [*NEW_TESTS, *session.TESTS["green"]], "focus": NEW_TESTS}
EXPECTED_COUNTS = {**session.EXPECTED_COUNTS, "DemoAuthHttpIntegrationTest": 15, "DemoAuthServiceTest": 18, "DemoRefreshCookieTest": 8, "DemoAuthControllerTest": 12, "DemoAuthLoginTransactionTest": 8, "DemoAuthFilterAvailabilityTest": 16, "DemoAuthHttpRequestContractTest": 9}

def safe_evidence(line):
    if not line.startswith("DEMO_AUTH_HTTP_EVIDENCE "):
        return session.safe_evidence(line)
    try:
        value = json.loads(line.split(" ", 1)[1])
        scenario = value.get("scenario")
        if not isinstance(scenario, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", scenario):
            return None
        result = {"kind": "DEMO_AUTH_HTTP_EVIDENCE", "scenario": scenario}
        for key, item in value.items():
            if re.fullmatch(r"[a-zA-Z][a-zA-Z0-9]{0,60}", key) and (type(item) is bool or type(item) is int and 0 <= item <= 100000):
                result[key] = item
        return result
    except (AttributeError, TypeError, ValueError):
        return None

def expected_red(result):
    suites = result["suites"]
    return (len(suites) == 1 and suites[0]["tests"] == 4 and suites[0]["failures"] == 1
        and suites[0]["errors"] == 0 and suites[0]["skipped"] == 0
        and any(row.get("scenario") == "login_http_contract" and row.get("httpStatus") == 401
            and row.get("demoEndpointCount") == 0 and row.get("databaseUnchanged")
            and row.get("stubRequests") == 0 for row in result.get("boundary_evidence", [])))

if __name__ == "__main__":
    sys.exit(session.profile.csv.a21.shared.main(verification={
        "stage": "DEMO_AUTH_HTTP", "tests": TESTS, "expected_counts": EXPECTED_COUNTS,
        "separate_classes": True,
        "csv_client_isolation_selectors": session.profile.csv.a21.ISOLATED_ORIGINAL_CONTEXTS,
        "evidence_parser": safe_evidence, "red_classifier": expected_red,
    }))
