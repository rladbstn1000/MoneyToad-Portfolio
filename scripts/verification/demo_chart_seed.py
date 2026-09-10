#!/usr/bin/env python3
"""Demo Chart seed checks using existing owned MySQL/Redis isolation; no downloads or .env."""
import json
import re
import sys
sys.dont_write_bytecode = True
import demo_auth_http as boundary

NEW_TESTS = ["*DemoChartSeedHttpIntegrationTest", "*DemoChartSeedInstallationTest", "*DemoSeedScenarioTest"]
TESTS = {"red": [NEW_TESTS[0]], "focus": NEW_TESTS,
         "core": ["*DemoSeedScenarioTest", "*DemoAuthServiceTest", "*DemoAuthLoginTransactionTest"],
         "regression": boundary.TESTS["green"], "green": [*NEW_TESTS, *boundary.TESTS["green"]]}
EXPECTED_COUNTS = {**boundary.EXPECTED_COUNTS, "DemoAuthServiceTest": 21, "DemoAuthLoginTransactionTest": 9,
    "DemoChartSeedHttpIntegrationTest": 3, "DemoChartSeedInstallationTest": 6, "DemoSeedScenarioTest": 11}


def safe_evidence(line):
    if not line.startswith("DEMO_CHART_SEED_EVIDENCE "):
        return boundary.safe_evidence(line)
    try:
        value = json.loads(line.split(" ", 1)[1])
        scenario = value.get("scenario")
        if not isinstance(scenario, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,100}", scenario):
            return None
        result = {"kind": "DEMO_CHART_SEED_EVIDENCE", "scenario": scenario}
        for key, item in value.items():
            if re.fullmatch(r"[a-zA-Z][a-zA-Z0-9]{0,60}", key) and (
                    type(item) is bool or type(item) is int and 0 <= item <= 1_000_000_000):
                result[key] = item
        return result
    except (AttributeError, TypeError, ValueError):
        return None


def expected_red(result):
    return any(s["name"].endswith("DemoChartSeedHttpIntegrationTest") and
               s["failures"] > 0 and s["errors"] == 0 and s["skipped"] == 0
               for s in result["suites"]) and any(
        e.get("scenario") == "login_chart_ready" and e.get("transactions") == 0
        and e.get("budgets") == 0 and e.get("cards") == 0 and e.get("users") == 1
        for e in result.get("boundary_evidence", []))


if __name__ == "__main__":
    sys.exit(boundary.session.profile.csv.a21.shared.main(verification={
        "stage": "DEMO_CHART_SEED", "tests": TESTS, "expected_counts": EXPECTED_COUNTS,
        "separate_classes": True,
        "csv_client_isolation_selectors": boundary.session.profile.csv.a21.ISOLATED_ORIGINAL_CONTEXTS,
        "evidence_parser": safe_evidence, "red_classifier": expected_red,
    }))
