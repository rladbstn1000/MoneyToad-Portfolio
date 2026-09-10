#!/usr/bin/env python3
"""A2.1 servlet missing-resource checks with the existing disposable A1/A2 runner.

Prior runners/evidence retain their defaults. Every selected class has its own
schema and Gradle/test JVM; each invocation gets a fresh temporary build/cache.
"""

import json
import re
import sys

sys.dont_write_bytecode = True
import a2_csv_boundary as a2


shared = a2.shared
TESTS = {
    "red": ["*NotFoundContractIntegrationTest", "*CsvBoundaryIntegrationTest",
            "*GlobalExceptionHandlerContractTest"],
    "green": [*a2.TESTS["green"], "*NotFoundContractIntegrationTest",
              "*GlobalExceptionHandlerContractTest"],
}
ISOLATED_ORIGINAL_CONTEXTS = {"*BudgetOwnershipIntegrationTest", "*DonApplicationTests"}
MISSING_PATH_CASES = {
    "deleted_csv_get": "GET", "deleted_csv_patch": "PATCH",
    "deleted_csv_trigger_get": "GET", "deleted_csv_analysis_get": "GET",
    "unique_missing_get": "GET", "unique_missing_patch": "PATCH",
    "missing_static_get": "GET",
}
ENUM_FIELDS = {
    "scenario": {"authenticated_not_found", "unauthenticated_not_found",
                 "unexpected_server_error", "openapi_success", "mvc_no_resource",
                 "mvc_no_handler", "entity_not_found", "security_exception",
                 "illegal_state", "runtime_error", "servlet_error", "csv_client_guard"},
    "layer": {"http", "direct_resolver", "context_initializer"},
    "method": {"GET", "PATCH"},
    "pathCase": {*MISSING_PATH_CASES, "test_only_runtime_failure", "local_openapi_json"},
    "handler": {"ResourceHttpRequestHandler", "HandlerMethod", "NONE"},
    "exception": {"NoResourceFoundException", "NoHandlerFoundException", "RuntimeException",
                  "ServletException", "EntityNotFoundException", "SecurityException",
                  "IllegalStateException", "NONE"},
}
INT_FIELDS = {"httpStatus", "expectedStatus", "clientCalls", "csvBusinessHandlers", "csvControllerBeans"}
BOOL_FIELDS = {"authenticated", "dbUnchanged", "exactContract", "genericServerErrorContract", "clientMock", "clientSpy"}


def safe_contract_evidence(line):
    """Persist enumerated metadata only; delegate unchanged A2 evidence schemas."""
    prefix, _, payload = line.partition(" ")
    if prefix != "A2_1_EVIDENCE":
        return a2.safe_boundary_evidence(line)
    try:
        value = json.loads(payload)
        if not isinstance(value, dict):
            return None
        result = {"kind": prefix}
        for key, allowed in ENUM_FIELDS.items():
            if isinstance(value.get(key), str) and value[key] in allowed:
                result[key] = value[key]
        for key in INT_FIELDS:
            if type(value.get(key)) is int and 0 <= value[key] <= 10000:
                result[key] = value[key]
        for key in BOOL_FIELDS:
            if type(value.get(key)) is bool:
                result[key] = value[key]
        for key in ("springBootVersion", "springFrameworkVersion"):
            if isinstance(value.get(key), str) and re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,3}", value[key]):
                result[key] = value[key]
        return result if "scenario" in result and "layer" in result else None
    except (TypeError, ValueError):
        return None


def expected_red(result):
    """Require real, side-effect-free HTTP 500 plus corresponding 404 assertion failures."""
    def case_observed(class_name, display_prefix, status, assertion=False):
        # Gradle XML uses explicit parameterized display names, not the Java method name.
        return any(suite["name"].endswith(class_name) and case["name"].startswith(display_prefix)
                   and case["status"] == status and (not assertion or case.get("assertion_failure", False))
                   for suite in result["suites"] for case in suite["cases"])

    evidence = result["boundary_evidence"]
    path_observations = {}
    for path_case, method in MISSING_PATH_CASES.items():
        auth = any(row.get("kind") == "A2_1_EVIDENCE" and row.get("layer") == "http"
                   and row.get("scenario") == "authenticated_not_found"
                   and row.get("pathCase") == path_case and row.get("method") == method
                   and row.get("authenticated") is True and row.get("expectedStatus") == 404
                   and row.get("httpStatus") == 500 and row.get("exactContract") is False
                   and row.get("genericServerErrorContract") is True
                   and row.get("handler") == "ResourceHttpRequestHandler"
                   and row.get("exception") == "NoResourceFoundException"
                   and row.get("clientCalls") == 0 and row.get("dbUnchanged") is True
                   and row.get("csvBusinessHandlers") == 0 and row.get("csvControllerBeans") == 0
                   for row in evidence)
        unauth = any(row.get("kind") == "A2_1_EVIDENCE" and row.get("layer") == "http"
                     and row.get("scenario") == "unauthenticated_not_found"
                     and row.get("pathCase") == path_case and row.get("method") == method
                     and row.get("authenticated") is False and row.get("expectedStatus") == 401
                     and row.get("httpStatus") == 401 and row.get("exactContract") is True
                     and row.get("handler") == "NONE" and row.get("exception") == "NONE"
                     and row.get("clientCalls") == 0 and row.get("dbUnchanged") is True
                     for row in evidence)
        path_observations[path_case] = {
            "authenticated_missing_resource_500_expected_404": auth,
            "corresponding_404_assertion_failed": case_observed("NotFoundContractIntegrationTest",
                "authenticated " + path_case + " returns generic 404", "FAIL", True),
            "missing_bearer_401_without_side_effects": unauth,
            "corresponding_authentication_case_passed": case_observed("NotFoundContractIntegrationTest",
                "missing Bearer for " + path_case + " remains 401", "PASS"),
        }
    direct = {}
    for scenario, exception in (("mvc_no_resource", "NoResourceFoundException"),
                                ("mvc_no_handler", "NoHandlerFoundException")):
        direct[scenario] = any(row.get("kind") == "A2_1_EVIDENCE"
            and row.get("layer") == "direct_resolver" and row.get("scenario") == scenario
            and row.get("exception") == exception and row.get("httpStatus") == 500
            and row.get("expectedStatus") == 404 for row in evidence) and case_observed(
                "GlobalExceptionHandlerContractTest", scenario + " has generic missing-resource response",
                "FAIL", True)
    result["red_observations"] = {
        "http_path_cases": path_observations,
        "direct_resolver_missing_types_500_expected_404": direct,
        "a2_updated_response_assertion_failed": case_observed("CsvBoundaryIntegrationTest",
            "targetControllersAreAbsentAndAuthenticatedRequestsHaveNoEffects", "FAIL", True),
        "all_selected_classes_produced_tests_without_errors_or_skips": not result.get("blocked_reason") and all(
            any(suite["name"].endswith(selector[1:]) and suite["tests"] > 0
                and suite["errors"] == 0 and suite["skipped"] == 0 for suite in result["suites"])
            for selector in TESTS["red"]),
    }
    return (all(all(observations.values()) for observations in path_observations.values())
            and all(direct.values())
            and result["red_observations"]["a2_updated_response_assertion_failed"]
            and result["red_observations"]["all_selected_classes_produced_tests_without_errors_or_skips"])


if __name__ == "__main__":
    sys.exit(shared.main(verification={
        "stage": "A2_1",
        "tests": TESTS,
        # Discover new counts from JUnit; preserve the fixed original A1 4/5/1 check.
        "expected_counts": shared.EXPECTED_COUNTS,
        "separate_classes": True,
        "csv_client_isolation_selectors": ISOLATED_ORIGINAL_CONTEXTS,
        "evidence_parser": safe_contract_evidence,
        "red_classifier": expected_red,
    }))
