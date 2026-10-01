# Snapshot verification rules

Read docs/portfolio/verification-summary.md and architecture-notes.md before running checks. This snapshot intentionally has no Git metadata and no original raw evidence.

Use the public_snapshot.py entrypoint with explicit dependency/cache/browser arguments and dedicated local MySQL/Redis. Do not use shared services, ambient env files, external OAuth/AI, API mocks or weakened assertions. Runtime credentials and resource identity must never enter public evidence. Run public_scan.py after changes. Keep fonts, unverified CSVs, raw logs and private keys outside this tree.


## Render runtime verification — local contracts verified

Read [the Render runtime contract](docs/deployment/01-render-runtime.md) before using the new deployment checks. `demo,render` is explicit and requires effective PORT/bind, validate-only SQL initialization, JDBC identity TLS, and authenticated Redis TLS settings. Existing non-render modes keep their original contract.

- `python3 -B scripts/verification/render_runtime.py --cache-seed <owned-gradle-cache>` checks the actual product ConfigData and owned TLS/runtime resources. Use a separate invocation with `--interrupt-check` for interruption cleanup.
- `python3 -B scripts/verification/render_regression.py --phase be --cache-seed <owned-gradle-cache>` and `--phase fe --dependencies <exact-lockfile-dependency-directory>` reuse existing regression selectors in private current-tree copies.
- New sanitized summaries go only under `docs/deployment/evidence/RENDER_RUNTIME/`; preserve past portfolio evidence. Never publish runtime credentials, identity, raw logs, certificates or local absolute paths.
- Local verification passed: existing BE275 plus new91, FE176, types, OAuth/demo builds and lint0/0; actual TLS success/failure, validate-only startup, constrained packaged runtime and owned-resource cleanup passed. The first constrained login exceeded the current frontend timeout; see the report for measured limits. Managed-provider contracts and public deployment remain unverified. Do not contact managed services or deploy through these runners.

## Gateway/readiness continuation

Read `docs/deployment/STATUS.md` and `03-gateway-readiness.md` for the current result and remaining input. Existing runtime/preflight summaries are historical and must not be overwritten.

- `deployment_fe_checks.py` and `deployment_local_checks.py` run current code in private copies with explicit dependencies/cache/browser and publish only new deployment evidence. Dependency overlays keep mutable tool caches out of the original installation.
- `managed_provider_check.py --compile-only` is offline preparation. `--execute` is a distinct, explicitly authorized provider probe using only the designated external input file, an owned schema and a durable exact-key ledger. Reuse the same private task state and cumulative budget; unknown cleanup blocks further writes. Never connect the full local suites to providers.
- Demo readiness is a bounded side-effect-free GET. Only login has a 60-second timeout; token POSTs are not automatically retried after an uncertain result. Pages frontend API base must be the actual browser origin, not empty or the backend origin.
- Product CSS uses system fonts. E2E must not reintroduce a test-only font transform. Local proxy tests and loopback browser E2E do not establish actual Cloudflare/Render or managed-provider compatibility.

## 로컬 demo capacity / cleanup 검증

- 현재 작업은 `docs/deployment/10-demo-capacity-cleanup.md`와 최신 deployment STATUS를 따른다. 두 JDBC 표는 demo 전용이며 OAuth JPA에 등록하지 않는다. DB는 관리자 사전 DDL 후 validate로 실행한다.
- `scripts/verification/demo_capacity_checks.py`의 fe/be/render/browser phase는 현재 소스를 소유 임시 복사본에 두고 기존 격리 runner를 사용한다. 전용 Gradle cache·정확한 FE dependencies·Chromium 경로를 명시한다. 새 결과만 DEMO_CAPACITY_CLEANUP에 저장한다. 전체 BE는 현재 모든 JUnit 클래스를 발견하며 Render 3클래스는 별도 TLS phase다.
- 관리자 CLI는 별도 maintenance JAR이며 기본 dry-run, verify/apply, batch1–100만 지원한다. 외부 private credential 파일·정확한 schema·TLS identity 검증·분리 권한을 요구한다. 임의 identity/force/자동 retry/범용 공유 DB 정리는 없다.
- 실제 TiDB/Upstash를 이번 로컬 검증에 연결하지 않는다. 기존 provider 원장은 소진됐으며 초기화·marker 삭제·예약 환급하지 않는다. 새 원격 admission/cleanup 검증은 별도 승인/예산이 필요하다.
- 최종 로컬 검증은 BE530(일반439+Render91), FE252, 타입/build/lint0/0, Chromium 독립2회 PASS다. 제한 컨테이너 준비354초는 FE240초보다 길어 실제 cold-start UX 보장은 별도다. 과거 raw POST 실패와 공개 scanner 수동검토 경고를 10보고서에서 분리한다.
