#!/usr/bin/env python3
"""Verify the real CsvClient against owned loopback HTTP and preserve BE regressions.

Reuses the existing disposable MySQL/Redis, synthetic environment, per-class
JVM/build/schema and cleanup runner unchanged. RED selects only the new narrow
client context; GREEN also runs the existing 66 tests with their full client
doubles. The narrow context itself does not start the application or use a DB.
"""

import json
import sys

sys.dont_write_bytecode = True
import a2_1_not_found_contract as a21


NEW_TEST = "*CsvClientConfigTest"
TESTS = {"red": [NEW_TEST], "green": [NEW_TEST, *a21.TESTS["green"]]}
PATHS = {"/api/ai/csv/upload", "/api/ai/csv/change", "/api/ai/data",
         "/api/ai/csv/status", "/api/ai/data/baseline"}
ENUMS = {
    "scenario": {"endpoint_contract", "invalid_base", "guard_self_test",
                 "yaml_mapping", "yaml_missing_env"},
    "layer": {"http_loopback", "spring_context", "outbound_guard"},
    "operation": {"upload", "change", "trigger", "status", "baseline", "none"},
    "baseCase": {"root", "prefix", "prefix_trailing", "encoded_prefix", "unicode_prefix", "none"},
    "configCase": {"missing", "empty", "blank", "relative", "unsupported_scheme",
                   "query", "fragment", "userinfo", "zero_port", "invalid_port", "none"},
    "expectedMethod": {"POST", "PUT", "GET"},
    "expectedPath": PATHS | {prefix + p for p in PATHS for prefix in
                             ("/synthetic-prefix", "/synthetic%20prefix", "/%ED%95%A9%EC%84%B1")},
    "expectedHost": {"127.0.0.1"},
}
COUNTS = {"port", "acceptedRequests", "blockedAttempts", "stubRequests",
          "requestBodyBytes", "csvPayloadBytes"}
FLAGS = {"invocationSucceeded", "methodMatches", "pathMatches", "queryMatches",
         "multipartMatches", "filenameMatches", "payloadMatches",
         "partContentTypeMatches", "responseMatches", "loopbackPeer",
         "startupRejected", "placeholderMappingExact", "networkUntouched"}


def safe_evidence(line):
    if not line.startswith("CSV_CONFIG_EVIDENCE "):
        return a21.safe_contract_evidence(line)
    try:
        value = json.loads(line.split(" ", 1)[1])
        if not isinstance(value, dict):
            return None
        result = {"kind": "CSV_CONFIG_EVIDENCE"}
        for key, allowed in ENUMS.items():
            if isinstance(value.get(key), str) and value[key] in allowed:
                result[key] = value[key]
        for key in COUNTS:
            if type(value.get(key)) is int and 0 <= value[key] <= 100000:
                result[key] = value[key]
        for key in FLAGS:
            if type(value.get(key)) is bool:
                result[key] = value[key]
        return result if "scenario" in result and "layer" in result else None
    except (TypeError, ValueError):
        return None


def expected_red(result):
    rows = [row for row in result["boundary_evidence"]
            if row.get("kind") == "CSV_CONFIG_EVIDENCE"
            and row.get("scenario") == "endpoint_contract"]
    complete_cases = {(row.get("operation"), row.get("baseCase")) for row in rows}
    required_cases = {(operation, base) for operation in ("upload", "change", "trigger", "status", "baseline")
                      for base in ("root", "prefix", "prefix_trailing")}
    required_cases |= {("status", base) for base in ("encoded_prefix", "unicode_prefix")}
    blocked_before_network = len(rows) == 17 and all(
        row.get("blockedAttempts") == 1 and row.get("acceptedRequests") == 0
        and row.get("stubRequests") == 0 and row.get("invocationSucceeded") is False
        for row in rows)
    executed = len(result["suites"]) == 1 and result["suites"][0]["tests"] == 21
    assertions = executed and result["suites"][0]["errors"] == 0 and result["suites"][0]["skipped"] == 0 and all(
        case.get("assertion_failure") for case in result["suites"][0]["cases"] if case["status"] == "FAIL")
    result["red_observations"] = {
        "all_17_endpoint_contracts_executed": complete_cases == required_cases,
        "legacy_uri_attempts_blocked_before_network": blocked_before_network,
        "actual_assertions_not_compilation_or_environment_failure": bool(assertions),
        "all_21_invocations_executed": executed,
    }
    return all(result["red_observations"].values())


if __name__ == "__main__":
    sys.exit(a21.shared.main(verification={
        "stage": "CSV_CONFIG",
        "tests": TESTS,
        "expected_counts": {**a21.shared.EXPECTED_COUNTS, "CsvClientConfigTest": 21},
        "separate_classes": True,
        "csv_client_isolation_selectors": a21.ISOLATED_ORIGINAL_CONTEXTS,
        "evidence_parser": safe_evidence,
        "red_classifier": expected_red,
    }))
