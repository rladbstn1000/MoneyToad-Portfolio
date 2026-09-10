#!/usr/bin/env python3
"""A2 CSV boundary checks using the A1 disposable-resource runner.

Each class gets its own schema and separate Gradle/test JVM lifetime. A fresh
temporary build directory per invocation prevents a deleted controller's old
.class file from surviving. A1 assertions/config/evidence remain unchanged.
"""

import json
import re
import sys

sys.dont_write_bytecode = True
import a1_budget_ownership as shared


TESTS = {
    "red": ["*CsvBoundaryIntegrationTest", "*CsvControllerRegistrationTest"],
    "green": ["*CsvServiceOwnershipTest", "*CsvBoundaryIntegrationTest",
              "*CsvControllerRegistrationTest", "*CardControllerCsvContractTest",
              "*BudgetServiceOwnershipTest", "*BudgetOwnershipIntegrationTest",
              "*DonApplicationTests"],
}
TABLES = {"users", "cards", "transactions", "budgets", "analysis_job"}
PREFIXES = {"A2_EVIDENCE", "A2_DB_EVIDENCE", "A2_CARD_CONTRACT"}
INT_FIELDS = {"businessHandlerCount", "controllerBeanCount", "httpRequestsExecuted",
              "clientCalls", "httpStatus", "userId", "cardId", "csvRows", "transactionCount",
              "candidateCount", "controllerCandidateCount", "budgetUserId", "jobUserId"}
BOOL_FIELDS = {"dbUnchanged", "databaseVerified", "csvHeaderValid", "csvSorted",
               "expectedClientArguments", "principalMatches", "filtersEnabled",
               "authenticated", "applicationStarted"}
TEXT_FIELDS = {"scenario", "exception", "handler", "handlerMethod", "method", "profile", "csvBranch"}
CSV_MAPPINGS = {"GET /csv", "PATCH /csv", "GET /csv/trigger", "GET /csv/analysis"}


def safe_boundary_evidence(line):
    """Allow only synthetic relationship/count metadata, never CSV/card/token values."""
    prefix, _, payload = line.partition(" ")
    if prefix not in PREFIXES:
        return None
    try:
        value = json.loads(payload)
        if not isinstance(value, dict):
            return None
        result = {"kind": prefix}
        for key in INT_FIELDS:
            if key in value and type(value[key]) is int:
                result[key] = value[key]
        for key in BOOL_FIELDS:
            if key in value and type(value[key]) is bool:
                result[key] = value[key]
        for key in TEXT_FIELDS:
            if key in value and isinstance(value[key], str) and re.fullmatch(
                    r"[A-Za-z0-9_.$#()/-]{1,160}", value[key]):
                result[key] = value[key]
        if value.get("path") in {"/api/csv", "/api/csv/trigger", "/api/csv/analysis", "/api/cards"}:
            result["path"] = value["path"]
        if value.get("mapping") in CSV_MAPPINGS:
            result["mapping"] = value["mapping"]
        if isinstance(value.get("registeredMappings"), list) and all(
                isinstance(item, str) and item in CSV_MAPPINGS for item in value["registeredMappings"]):
            result["registeredMappings"] = value["registeredMappings"]
        if isinstance(value.get("changedTables"), list) and all(
                isinstance(item, str) and item in TABLES for item in value["changedTables"]):
            result["changedTables"] = value["changedTables"]
        if isinstance(value.get("rowCounts"), dict) and all(
                key in TABLES and type(count) is int for key, count in value["rowCounts"].items()):
            result["rowCounts"] = value["rowCounts"]
        if isinstance(value.get("profiles"), list) and all(
                item in {"default", "demo", "prod", "production", "a1"} for item in value["profiles"]):
            result["profiles"] = value["profiles"]
        return result if len(result) > 1 else None
    except (TypeError, ValueError):
        return None


def expected_red(result):
    def failed_assertion(class_name, method_prefix):
        return any(suite["name"].endswith(class_name) and case["status"] == "FAIL" and
                   case["name"].startswith(method_prefix) and case.get("assertion_failure", False)
                   for suite in result["suites"] for case in suite["cases"])

    evidence = result["boundary_evidence"]
    mapping = any(row.get("scenario") == "csv_handlers" and row.get("businessHandlerCount") == 4 and
                  row.get("controllerBeanCount") == 1 and row.get("httpRequestsExecuted") == 0
                  for row in evidence)
    foreign = {}
    for operation in ("upload", "change"):
        foreign[operation] = any(
            row.get("scenario") == "denied_" + operation + "_foreign" and
            row.get("clientCalls", 0) > 0 and row.get("dbUnchanged") is False and
            "users" in row.get("changedTables", []) and row.get("exception") == "NONE"
            for row in evidence)
    result["red_observations"] = {
        "four_csv_handlers_registered_without_requests": mapping,
        "foreign_upload_export_and_database_change": foreign["upload"],
        "foreign_change_export_and_database_change": foreign["change"],
        "mapping_assertion_failed": failed_assertion(
            "CsvBoundaryIntegrationTest", "targetControllersAreAbsentAndAuthenticatedRequestsHaveNoEffects"),
        "ownership_assertion_failed": all(
            failed_assertion("CsvBoundaryIntegrationTest", operation + " rejects foreign before export")
            for operation in ("UPLOAD", "CHANGE")),
    }
    return all(result["red_observations"].values())


if __name__ == "__main__":
    sys.exit(shared.main(verification={
        "stage": "A2",
        "tests": TESTS,
        # A2 counts are discovered from XML; only the original A1 counts are fixed.
        "expected_counts": shared.EXPECTED_COUNTS,
        "separate_classes": True,
        "evidence_parser": safe_boundary_evidence,
        "red_classifier": expected_red,
    }))
