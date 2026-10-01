#!/usr/bin/env python3
"""Run only A1 tests with disposable local MySQL/Redis and synthetic credentials.

Requires Python 3.9+, Java 21, a local Docker Engine with mysql:8.4 already
available, and redis-server. Nothing pulls images, loads .env, or uses an existing
database. Red/green both return the actual Gradle exit code; inspect the JSON
evidence to distinguish an expected ownership assertion from an environment error.
"""

from public_evidence import backend, save
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
LABEL = "moneytoad.verification.a1"
TESTS = {
    "red": ["*BudgetOwnershipIntegrationTest"],
    "green": ["*BudgetServiceOwnershipTest", "*BudgetOwnershipIntegrationTest",
              "*DonApplicationTests"],
}
EXPECTED_COUNTS = {"BudgetServiceOwnershipTest": 4, "BudgetOwnershipIntegrationTest": 5,
                   "DonApplicationTests": 1}
SAFE_COLUMNS = {
    "id", "user_id", "amount", "initial_amount", "initial_file_id", "predicted_at",
    "is_overridden", "overridden_at", "budget_date", "category",
}


class Blocked(Exception):
    """A checked prerequisite failed; the message must contain no command output."""


def safe_db_evidence(line):
    """Copy only the agreed synthetic DB evidence schema, never arbitrary stdout."""
    try:
        value = json.loads(line.split("A1_DB_EVIDENCE ", 1)[1])
        result = {}
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", value["scenario"]):
            return None
        result["scenario"] = value["scenario"]
        for key in ("httpStatus", "changedRows", "rowsBefore", "rowsAfter"):
            if type(value[key]) is not int:
                return None
            result[key] = value[key]
        result["changes"] = []
        for change in value["changes"]:
            if type(change["budgetId"]) is not int:
                return None
            if not set(change["columns"]).issubset(SAFE_COLUMNS):
                return None
            item = {"budgetId": change["budgetId"], "columns": change["columns"]}
            for key in ("amountBefore", "amountAfter"):
                if change.get(key) is not None and type(change[key]) not in (int, float):
                    return None
                item[key] = change.get(key)
            result["changes"].append(item)
        return result
    except (KeyError, ValueError, TypeError, IndexError):
        return None


def summarize_xml(directory, extra_parser=None, extra_evidence=None):
    suites, db_evidence = [], []
    for path in sorted(directory.glob("TEST-*.xml")):
        tree = ET.parse(path).getroot()
        suite = {"name": tree.get("name"), "tests": int(tree.get("tests", "0")),
                 "failures": int(tree.get("failures", "0")),
                 "errors": int(tree.get("errors", "0")),
                 "skipped": int(tree.get("skipped", "0")), "cases": []}
        for case in tree.findall("testcase"):
            failure = case.find("failure")
            if failure is None:
                failure = case.find("error")
            item = {"name": case.get("name"), "seconds": float(case.get("time", "0")),
                    "status": "FAIL" if failure is not None else
                    "SKIPPED" if case.find("skipped") is not None else "PASS"}
            if failure is not None:
                # Full exception messages/stacks can contain Authorization headers.
                kind = failure.get("type", "")
                item["failure_type"] = kind if re.fullmatch(r"[A-Za-z0-9_.$]+", kind) else "omitted"
                # Gradle may wrap JUnit assertAll in DefaultMultiCauseException.
                item["assertion_failure"] = bool(re.search(
                    r"org\.opentest4j\.(?:AssertionFailedError|MultipleFailuresError)|java\.lang\.AssertionError",
                    kind + "\n" + (failure.text or "")))
                # Diagnose local failures without retaining XML, actual assertion
                # values, SQL, request bodies or credential-bearing messages.
                diagnostic = (failure.text or "") + "\n" + failure.get("message", "")
                item["failure_sources"] = [
                    {"source": filename, "line": int(line)}
                    for filename, line in dict.fromkeys(re.findall(
                        r"\bat com\.potg\.[A-Za-z0-9_.$]+\(([A-Za-z0-9_$]+\.java):([0-9]+)\)", diagnostic))
                ][:16]
                item["cause_classes"] = sorted(set(re.findall(
                    r"\b(?:[a-z][A-Za-z0-9_$]*\.)+[A-Z][A-Za-z0-9_$]*(?:Exception|Error)\b", diagnostic)))[:20]
                item["failure_codes"] = sorted(set(re.findall(
                    r"\b(?:DEMO_[A-Z_]+|CREDENTIAL_FILE_REJECTED|SCHEMA_REJECTED|INPUT_REJECTED|INTEGRITY_REJECTED|UNSAFE_BATCH|ROW_COUNT_MISMATCH|SQL_FAILURE|COMMIT_UNKNOWN|ROLLBACK_UNKNOWN|LOCK_BUSY)\b", diagnostic)))[:12]
                match = re.search(r"Status expected:<([0-9]{3})> but was:<([0-9]{3})>",
                                  failure.get("message", ""))
                if match:
                    item["http_status_comparison"] = {
                        "expected": int(match[1]), "actual": int(match[2])}
            suite["cases"].append(item)
        for output in tree.iter("system-out"):
            for line in (output.text or "").splitlines():
                if line.startswith("A1_DB_EVIDENCE "):
                    entry = safe_db_evidence(line)
                    if entry is not None:
                        db_evidence.append(entry)
                elif extra_parser is not None:
                    entry = extra_parser(line)
                    if entry is not None:
                        extra_evidence.append(entry)
        suites.append(suite)
    return suites, db_evidence


def main(*, verification=None):
    # A2 supplies only its narrow test/evidence policy; A1 defaults stay unchanged.
    verification = verification or {}
    stage = verification.get("stage", "A1")
    tests = verification.get("tests", TESTS)
    expected_counts = verification.get("expected_counts", EXPECTED_COUNTS)
    separate_classes = verification.get("separate_classes", False)
    resource_label = "moneytoad.verification." + stage.lower()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", required=True, choices=tests)
    parser.add_argument("--java-home", type=Path,
                        help="Java 21 home; macOS otherwise resolves /usr/libexec/java_home -v 21")
    parser.add_argument("--docker-socket", type=Path,
                        help="Existing local Unix socket (remote Docker contexts are never used)")
    parser.add_argument("--redis-server", type=Path, help="Local redis-server executable")
    parser.add_argument("--mysql-image", help="Optional existing local MySQL 8.4 image ID (no pull)")
    parser.add_argument("--cache-seed", type=Path,
                        help="Optional trusted isolated Gradle cache to copy; caches/wrapper only")
    args = parser.parse_args()
    os.umask(0o077)
    run_id = uuid.uuid4().hex[:12]
    work = Path(tempfile.mkdtemp(prefix="moneytoad-" + stage.lower() + "-"))
    name = "moneytoad-" + stage.lower() + "-" + run_id
    network = "moneytoad-" + stage.lower() + "-net-" + run_id
    # Existing A1 safety assertions require this prefix, including A2 regressions.
    schema = "moneytoad_a1_" + (stage.lower() + "_" if stage != "A1" else "") + run_id
    result = {"phase": args.phase, "run_id": run_id, "status": "BLOCKED",
              "expected_red_observed": False, "work_directory": str(work),
              "commands": [], "suites": [], "db_evidence": [], "cleanup": []}
    result["resources"] = {"mysql_container": name, "network": network,
                           "label": resource_label + "=" + run_id, "schema": schema}
    if verification:
        result.update({"verification": stage, "class_runs": [], "boundary_evidence": []})
    generated = [secrets.token_urlsafe(36) for _ in range(4)]
    db_password, root_password, jwt_secret, oauth_secret = generated
    env = {"PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
           "TMPDIR": str(work), "LANG": "en_US.UTF-8"}
    docker, redis = None, None
    network_attempted, container_attempted = False, False
    exit_code, started = 2, time.monotonic()

    def redact(text):
        # Diagnostics never persist payloads or database identities in the public runner.
        return '[Runtime output omitted; see projected suite results.]\n'

    def run(command, label, timeout=30, check=True):
        entry = {"step": label, "command": redact(" ".join(map(str, command)))}
        result["commands"].append(entry)
        process = subprocess.Popen(list(map(str, command)), cwd=ROOT / "be", env=env,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, start_new_session=True)
        try:
            output, _ = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                output, _ = process.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                output, _ = process.communicate(timeout=5)
            (work / (str(len(result["commands"])) + "-" + label + ".log")).write_text(redact(output))
            entry["status"] = "TIMEOUT"
            raise Blocked(label + " timed out")
        except KeyboardInterrupt:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate(timeout=5)
            raise
        completed = subprocess.CompletedProcess(command, process.returncode, output)
        entry["exit_code"] = completed.returncode
        (work / (str(len(result["commands"])) + "-" + label + ".log")).write_text(
            redact(completed.stdout), encoding="utf-8")
        if check and completed.returncode:
            raise Blocked(label + " failed; inspect the sanitized temporary log")
        return completed

    def ownership(kind, resource):
        inspected = run(docker + [kind, "inspect", "--format",
                        '{{index .Labels "' + resource_label + '"}}' if kind == "network" else
                        '{{index .Config.Labels "' + resource_label + '"}}', resource],
                        "ownership-" + kind, check=False)
        if inspected.returncode == 0:
            return "owned" if inspected.stdout.strip() == run_id else "mismatch"
        if re.search(r"(?i)no such (?:container|object|network)|network .+ not found", inspected.stdout):
            return "absent"
        return "unknown"

    def interrupted(signum, frame):
        raise KeyboardInterrupt()

    signal.signal(signal.SIGTERM, interrupted)
    try:
        if not (ROOT / "be/gradlew").is_file():
            raise Blocked("Run this checked-in script inside the MoneyToad repository")
        java_home = args.java_home
        if java_home is None and Path("/usr/libexec/java_home").is_file():
            java_home = Path(run(["/usr/libexec/java_home", "-v", "21"], "resolve-java").stdout.strip())
        if java_home is None or not (java_home / "bin/java").is_file():
            raise Blocked("Java 21 is required; provide --java-home")
        env.update({"JAVA_HOME": str(java_home), "PATH": str(java_home / "bin") + ":" + env["PATH"],
                    "GRADLE_USER_HOME": str(work / "gradle-cache")})
        version = run([java_home / "bin/java", "-version"], "java-version").stdout
        if not re.search(r'version "21[.\"]', version):
            raise Blocked("Selected JVM is not Java 21")
        result["java_version"] = version.splitlines()[0]
        if args.cache_seed:
            if not args.cache_seed.is_dir():
                raise Blocked("Explicit Gradle cache seed directory does not exist")
            for part in ("caches", "wrapper"):
                source = args.cache_seed / part
                if source.is_dir():
                    shutil.copytree(source, work / "gradle-cache" / part,
                                    ignore=shutil.ignore_patterns("*.lock", "*.lck"))
        docker_bin = shutil.which("docker", path=env["PATH"])
        redis_bin = str(args.redis_server) if args.redis_server else shutil.which("redis-server", path=env["PATH"])
        if not docker_bin or not redis_bin:
            raise Blocked("Local docker and redis-server executables are required")
        candidates = [args.docker_socket] if args.docker_socket else [
            Path.home() / ".docker/run/docker.sock", Path("/var/run/docker.sock")]
        docker_socket = next((p for p in candidates if p and p.is_socket()), None)
        if docker_socket is None:
            raise Blocked("An accessible local Docker Unix socket is required")
        docker_config = work / "docker-config"
        docker_config.mkdir()
        docker = [docker_bin, "--config", str(docker_config), "--host", "unix://" + str(docker_socket)]
        result["docker_socket"] = str(docker_socket)
        run(docker + ["info", "--format", "{{.ServerVersion}} {{.OSType}}"], "docker-local-info")
        image_selector = args.mysql_image
        if image_selector is None:
            image_ids = run(docker + ["image", "ls", "--filter", "reference=mysql:8.4",
                            "--format", "{{.ID}}"], "find-local-mysql").stdout.splitlines()
            if len(image_ids) != 1:
                raise Blocked("Exactly one existing local mysql:8.4 image is required; no images are pulled")
            image_selector = image_ids[0]
        if not re.fullmatch(r"(?:sha256:)?[a-f0-9]{12,64}", image_selector):
            raise Blocked("--mysql-image must identify an existing local image ID")
        image = run(docker + ["image", "inspect", image_selector, "--format", "{{.Id}}"],
                    "local-mysql-image").stdout.strip()
        result["mysql_image_id"] = image
        result["redis_version"] = run([redis_bin, "--version"], "redis-version").stdout.strip()
        mysql_env = work / "mysql.env"
        mysql_env.write_text("MYSQL_ROOT_PASSWORD=" + root_password + "\nMYSQL_DATABASE=" + schema +
                             "\nMYSQL_USER=a1_user\nMYSQL_PASSWORD=" + db_password + "\n")
        mysql_client = work / "mysql-client.cnf"
        mysql_client.write_text("[client]\nuser=a1_user\npassword=" + db_password + "\n")
        extra_mounts = []
        if separate_classes:
            mysql_root = work / "mysql-root.cnf"
            mysql_root.write_text("[client]\nuser=root\npassword=" + root_password + "\n")
            extra_mounts = ["--mount", "type=bind,src=" + str(mysql_root) +
                            ",dst=/run/a1/root.cnf,readonly"]
        network_attempted = True
        run(docker + ["network", "create", "--driver", "bridge", "--label", resource_label + "=" + run_id,
                      network], "create-owned-network")
        # Choose a free loopback port explicitly; a racing bind makes docker run fail safely.
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            db_port = reservation.getsockname()[1]
        container_attempted = True
        launched = run(docker + ["run", "--detach", "--name", name, "--label", resource_label + "=" + run_id,
                      "--network", network, "--publish", "127.0.0.1:" + str(db_port) + ":3306", "--memory", "768m",
                      "--cpus", "2", "--tmpfs", "/var/lib/mysql:rw,size=512m", "--env-file", str(mysql_env),
                      "--mount", "type=bind,src=" + str(mysql_client) + ",dst=/run/a1/client.cnf,readonly",
                      ] + extra_mounts + [image, "--skip-log-bin"], "create-owned-mysql", check=False)
        # Even a failed docker run can leave an owned stopped container behind.
        if launched.returncode or ownership("container", name) != "owned":
            raise Blocked("Dedicated MySQL container did not start")
        bindings = json.loads(run(docker + ["container", "inspect", "--format",
                            "{{json .HostConfig.PortBindings}}", name], "mysql-loopback-port").stdout)
        if bindings.get("3306/tcp") != [{"HostIp": "127.0.0.1", "HostPort": str(db_port)}]:
            raise Blocked("Dedicated MySQL port was not exclusively loopback")
        result["resources"]["mysql_port"] = db_port
        for attempt in range(5):
            with socket.socket() as reservation:
                reservation.bind(("127.0.0.1", 0))
                redis_port = reservation.getsockname()[1]
            if redis_port == 6379:
                continue
            redis = subprocess.Popen([redis_bin, "--bind", "127.0.0.1", "--port", str(redis_port),
                       "--save", "", "--appendonly", "no", "--dir", str(work),
                       "--pidfile", str(work / "redis.pid")], env=env,
                       stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
            time.sleep(0.2)
            if redis.poll() is None:
                break
        if redis is None or redis.poll() is not None:
            raise Blocked("Could not reserve a dedicated Redis port after five attempts")
        result["resources"].update({"redis_port": redis_port, "redis_pid": redis.pid})
        mysql = docker + ["exec", name, "mysql", "--defaults-extra-file=/run/a1/client.cnf",
                          "--protocol=TCP", "-h127.0.0.1", "-D" + schema,
                          "--batch", "--skip-column-names", "-e"]
        ready = False
        for _ in range(60):
            checked = run(mysql + ["SELECT 1;"], "mysql-readiness", timeout=10, check=False)
            if checked.returncode == 0 and redis.poll() is None:
                try:
                    with socket.create_connection(("127.0.0.1", db_port), timeout=1):
                        pass
                    with socket.create_connection(("127.0.0.1", redis_port), timeout=1) as ping:
                        ping.settimeout(1)
                        ping.sendall(b"*1\r\n$4\r\nPING\r\n")
                        ready = ping.recv(32) == b"+PONG\r\n"
                    if ready:
                        break
                except OSError:
                    pass
            time.sleep(1)
        if not ready:
            raise Blocked("Dedicated MySQL TCP/Redis readiness failed")
        if run(mysql + ["SHOW TABLES;"], "verify-empty-schema").stdout.strip():
            raise Blocked("Dedicated schema is not empty")
        result["mysql_server_version"] = run(mysql + ["SELECT VERSION();"], "mysql-version").stdout.strip()
        env.update({"A1_DB_URL": "jdbc:mysql://127.0.0.1:" + str(db_port) + "/" + schema +
                    "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul",
                    "A1_DB_USERNAME": "a1_user", "A1_DB_PASSWORD": db_password,
                    "A1_REDIS_PORT": str(redis_port), "A1_JWT_SECRET": jwt_secret,
                    "A1_OAUTH_SECRET": oauth_secret})
        init = work / "a1.init.gradle"
        init.write_text("""allprojects {
  if (System.getenv('A1_ISOLATED_BUILD_DIR')) {
    layout.buildDirectory.set(file(System.getenv('A1_ISOLATED_BUILD_DIR')))
  }
  tasks.withType(Test).configureEach {
    systemProperty 'spring.config.location', 'classpath:application-a1.yml'
    systemProperty 'spring.profiles.active', 'a1'
    if (System.getenv('A2_1_CSV_CLIENT_ISOLATION')) {
      systemProperty 'a2_1.csv-client-isolation', System.getenv('A2_1_CSV_CLIENT_ISOLATION')
    }
    reports.junitXml.outputLocation = file(System.getenv('A1_REPORT_DIR'))
    reports.html.required = false
    testLogging.showStandardStreams = false
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
  }
}
""")
        if verification.get("privilege_accounts"):
            env["A1_ADMIN_DB_USERNAME"] = "root"
            env["A1_ADMIN_DB_PASSWORD"] = root_password
        if separate_classes:
            # Fresh per invocation: deleted product .class files cannot survive RED/GREEN.
            env["A1_ISOLATED_BUILD_DIR"] = str(work / "isolated-build")
        groups = [[selector] for selector in tests[args.phase]] if separate_classes else [tests[args.phase]]
        exit_code = 0
        for index, selectors in enumerate(groups, 1):
            env.pop("A2_1_CSV_CLIENT_ISOLATION", None)
            if any(selector in verification.get("csv_client_isolation_selectors", ()) for selector in selectors):
                env["A2_1_CSV_CLIENT_ISOLATION"] = "true"
            group_schema = schema
            if separate_classes:
                group_schema = schema + "_" + str(index)
                # Names are generated identifiers; grant only this owned schema to the disposable user.
                # MySQL database-level GRANT treats an unescaped underscore as a wildcard.
                grant_schema = group_schema.replace("_", "\\_")
                run(docker + ["exec", name, "mysql", "--defaults-extra-file=/run/a1/root.cnf",
                              "--batch", "--skip-column-names", "-e",
                              "CREATE DATABASE `" + group_schema + "`; GRANT ALL PRIVILEGES ON `" +
                              grant_schema + "`.* TO 'a1_user'@'%';"], "create-owned-schema-" + str(index))
                env["A1_DB_URL"] = ("jdbc:mysql://127.0.0.1:" + str(db_port) + "/" + group_schema +
                                   "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul")
            reports = work / ("test-xml-" + str(index) if separate_classes else "test-xml")
            reports.mkdir()
            env["A1_REPORT_DIR"] = str(reports)
            command = ["bash", "./gradlew"]
            if args.phase == "green":
                command += ["compileJava", "compileTestJava"]
            command += ["test", "--init-script", str(init), "--project-cache-dir", str(work / "project-cache"),
                        "--no-daemon", "--console=plain", "--max-workers=2"]
            for selector in selectors:
                command += ["--tests", selector]
            print(stage + " " + args.phase + ": running " + ", ".join(selectors) + " in owned schema.", flush=True)
            tested = run(command, "gradle-" + args.phase + "-" + str(index), timeout=900, check=False)
            exit_code = exit_code or tested.returncode
            suites, db_evidence = summarize_xml(reports, verification.get("evidence_parser"),
                                                 result.get("boundary_evidence"))
            result["suites"].extend(suites)
            result["db_evidence"].extend(db_evidence)
            if verification:
                class_result = {"selectors": selectors, "schema": group_schema,
                                "exit_code": tested.returncode, "reports": str(reports),
                                "separate_gradle_execution": separate_classes,
                                "status": "PASS" if tested.returncode == 0 and suites and all(
                                    not (suite["failures"] or suite["errors"] or suite["skipped"])
                                    for suite in suites) else "FAIL"}
                if not suites:
                    class_result["status"] = "BLOCKED"
                    class_result["reason"] = ("Compilation failed before JUnit execution" if re.search(
                        r":compile(?:Test)?Java FAILED", tested.stdout) else
                        "No JUnit XML produced; this is not a regression RED")
                    result["blocked_reason"] = class_result["reason"]
                    result["unexecuted_selectors"] = [selector for group in groups[index:] for selector in group]
                result["class_runs"].append(class_result)
            for report in reports.glob("*.xml"):
                report.write_text(redact(report.read_text()), encoding="utf-8")
            if verification and not suites:
                exit_code = 2
                break
        foreign_assertion_failed = any(
            suite["name"].endswith("BudgetOwnershipIntegrationTest") and
            case["name"].startswith("foreignBudgetReturnsSameGeneric404AndPreservesEveryRow") and
            case["status"] == "FAIL" and case.get("assertion_failure", False)
            for suite in result["suites"] for case in suite["cases"])
        owner_reached_handler = any(row["scenario"] == "owner_a" and row["httpStatus"] == 200
                                    for row in result["db_evidence"])
        foreign_write_observed = any(
            row["scenario"] == "foreign_budget" and row["httpStatus"] == 200 and
            any(change["budgetId"] == 2002 and set(change["columns"]) ==
                {"amount", "is_overridden", "overridden_at"} for change in row["changes"])
            for row in result["db_evidence"])
        result["expected_red_observed"] = (args.phase == "red" and foreign_assertion_failed
                                            and owner_reached_handler and foreign_write_observed)
        if verification.get("red_classifier") is not None:
            result["expected_red_observed"] = args.phase == "red" and verification["red_classifier"](result)
        complete = all(any(suite["name"].endswith(selector[1:]) and
                          suite["tests"] > 0 and
                          (selector[1:] not in expected_counts or suite["tests"] == expected_counts[selector[1:]])
                          for suite in result["suites"]) for selector in tests[args.phase])
        no_skips_or_failures = all(not (suite["failures"] or suite["errors"] or suite["skipped"])
                                  for suite in result["suites"])
        result["status"] = "PASS" if exit_code == 0 and complete and no_skips_or_failures else "FAIL"
        if result.get("blocked_reason"):
            result["status"] = "BLOCKED"
        if exit_code == 0 and result["status"] != "PASS":
            exit_code = 1
    except (Blocked, OSError, ET.ParseError) as error:
        result["blocked_reason"] = str(error) if isinstance(error, Blocked) else type(error).__name__
        exit_code = 2
    except KeyboardInterrupt:
        result["blocked_reason"] = "Interrupted; owned resource cleanup attempted"
        exit_code = 130
    finally:
        if redis is not None:
            if redis.poll() is None:
                redis.terminate()
                try:
                    redis.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    redis.kill()
                    redis.wait(timeout=5)
            result["cleanup"].append({"resource": "owned-redis", "pid": redis.pid, "removed": redis.poll() is not None})
        for attempted, kind, resource in [(container_attempted, "container", name), (network_attempted, "network", network)]:
            if not attempted:
                continue
            try:
                resource_state = ownership(kind, resource)
                if resource_state == "owned":
                    if kind == "container":
                        run(docker + ["stop", "--time", "10", resource], "stop-owned-mysql", check=False)
                    removed = run(docker + [kind, "rm", resource], "remove-owned-" + kind, check=False).returncode == 0
                else:
                    removed = resource_state == "absent"
                result["cleanup"].append({"resource": resource, "ownership": resource_state, "removed": removed})
            except (Blocked, OSError):
                result["cleanup"].append({"resource": resource, "removed": False})
        for secret_file in (work / "mysql.env", work / "mysql-client.cnf", work / "mysql-root.cnf"):
            secret_file.unlink(missing_ok=True)
        result["elapsed_seconds"] = round(time.monotonic() - started, 2)
        result["cleanup_complete"] = all(item["removed"] for item in result["cleanup"])
        if not result["cleanup_complete"]:
            result["status"] = "BLOCKED"
            exit_code = 2
        result["exit_code"] = exit_code
        # Private run-owned recovery metadata is kept outside the publishable tree.
        (work / "ownership.json").write_text(json.dumps({"resources": result["resources"],
            "cleanup": result["cleanup"], "snapshot": str(ROOT)}, indent=2))
        # Project the result object at the actual output boundary. No module monkey patch.
        save("be-regression-summary.json", backend(result))
        print(json.dumps({"phase": args.phase, "status": result["status"],
                          "cleanup_complete": result["cleanup_complete"]}), flush=True)

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
