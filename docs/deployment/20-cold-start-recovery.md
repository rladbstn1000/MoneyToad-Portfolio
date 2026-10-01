# 20. public-demo cold-start 이후 수동 복구

## 범위와 판정

2026-10-02. 기존240초를 늘리지 않고, 자동 준비 확인이 끝난 뒤 사용자가 GET 한 번으로 서버 상태를 확인하도록 연결했다. 이전 로컬512MiB/0.1CPU 관측은357.782초와215.738초로 달랐다. 두 표본만으로240초를 안정적인 시작 완료 시간이나 적정 timeout으로 간주하지 않는다.

이번 제품 변경은 FE의 readiness/coordinator/store/상태 UI 네 파일뿐이다. BE·gateway·rate limiter·JDBC/Redis·capacity/cleanup·seed·Chart·HTTP 인증 계약·제품 timeout·heap·CPU 설정은 보존했다. 실제 Render/Cloudflare와 TiDB/Upstash 연결, provider 파일/private state/예산 접근은 **NOT_RUN / 0**이다.

```text
COLD_START_RECOVERY_READY=true
PUBLIC_DEPLOYMENT_READY=false
```

## 상태와 요청 계약

|상황|상태·후속 요청|
|---|---|
|최초 준비 확인|기존240000ms·최대80회·각 요청10000ms·기본3000ms 간격. 로그인 POST 자동 생성0. 정상 시작의 reissue→session 복원은 유지.|
|deadline 또는80회 소진|`startupSlow`, operation 해제. 자동 polling timer와 진행 요청 정리. 시간만 지나도 GET/POST 추가0. 빠른 false 응답80회는237초에 소진될 수 있으며240초 deadline과 별개다.|
|수동 버튼 클릭|`manualReadiness`, 버튼 disabled, 공통 Promise로 중복 클릭 합류. 별도 transport의 GET ready 한 번만 실행. 새240초 cycle 없음.|
|수동 ready=true|anonymous의 명시적 체험 버튼 활성화. 자동 login/reissue/session0. 기존 RT cookie를 JS가 읽거나 삭제하지 않음.|
|별도 체험 클릭|기존 login 흐름으로 진행. 활성 cookie에 대한409는 기존 reissue→session 복원을 이용. 요청을 자동 재전송하지 않음.|
|수동 false·일반503·network·timeout|startupSlow로 돌아가며 자동 재시도0.|
|정확한429/DEMO_READINESS_RATE_LIMITED|기존 Retry-After1~3600초 검증·잘못된 값60초 fallback. `readinessRetryAt` 동안 버튼 비활성. 만료는 버튼만 활성화하며 GET0.|
|확정 오류|기존401은 anonymous,403/미인식429 및 Function의 명시적 BACKEND_NOT_CONFIGURED/GATEWAY_NOT_CONFIGURED는 unavailable. raw 오류 표시 없음.|
|logout 복구가 준비 한도 소진|기존 unavailable/recovery=logout 유지. 종료를 확인하지 못한 상황에서 새 로그인 버튼을 제시하지 않음.|

자동 polling 중 받은 긴 Retry-After가 deadline보다 뒤에 있으면 남은 cooldown을 수동 상태로 전달한다. `loginRetryAt`과 섞지 않는다. UI용 cooldown clock은 만료 후 해제되고 네트워크 요청을 시작하지 않는다. “예약 timer0”은 자동 준비 polling의 종료를 뜻하며, 명시적인 활성 cooldown의 UI timer와 구분한다.

수동 응답은 generation뿐 아니라 revision/status/operation을 모두 확인한 뒤 상태를 변경한다. endDemo/logout은 준비 요청을 abort하며, 이전 Promise의 늦은 결과가 새 체험을 복구하지 못한다. StrictMode 초기 restore와 기존 session/JWT/refresh/logout 구조는 재작성하지 않았다.

UI는 “서버를 시작하고 있습니다”, 담백한 설명, “서버 다시 확인”을 표시한다. 진행 중에는 “서버 확인 중…”과 disabled를 사용하며 상태 안내에 live region을 둔다. 별도의 spinner나 인프라 상세는 추가하지 않았다.

## 변경 전 재현과 중간 실패

- 기존 callable bootstrap/restore로 자동 예산 소진 테스트6개를 실행: **5 FAIL / 1 PASS**. 새 manual API가 없어서 생긴 compile 오류를 RED로 사용하지 않았다. 당시 다른18개는 미선택이며 최종 skip이 아니다. [RED](evidence/COLD_START_RECOVERY/fe-red-summary.json)
- 리뷰에서 오래 머문 startupSlow 화면의 시계가 멈춘 뒤 이미 끝난 login cooldown으로 복귀하면 버튼이 계속 잠길 수 있음을 발견했다. 회귀1개 RED 후 남은 시간 계산에 현재 시각을 반영했다. 새 테스트의 기대값은 유지했다. [리뷰 RED](evidence/COLD_START_RECOVERY/fe-review-red-summary.json), [리뷰 GREEN](evidence/COLD_START_RECOVERY/fe-review-green-summary.json)
- 첫 cold browser 집중 실행은 setup 단계 cases0, 제품 결함 여부를 판정하지 못했다. 당시 동시 테스트 파일 변경도 source 보존 실패로 기록됐다. 초기 요약에 setup 분류가 빠진 도구의 관측 누락만 보강하고, 동결 소스로 별도 실행했다. 첫 결과를 덮어쓰거나 PASS로 변경하지 않았다. [초기 결과](evidence/COLD_START_RECOVERY/cold-browser-focus/summary.json)
- 첫 Render regression은 prerequisites의 Docker 명령 exit1로 중단됐고 실제 제품 테스트/자원 제한 관측은 실행하지 못했다. 원문 오류를 공개하지 않은 기존 요약만으로 세부 원인을 소급 확정하지 않는다. 같은 clean environment·명시 Docker socket에서 필요한 기존 이미지5개와 Docker info가 모두 통과함을 확인한 뒤 새 label로 실행했다. 초기 자원 정리 PASS. [첫 결과](evidence/COLD_START_RECOVERY/render-final/summary.json)

최종 감사 파일을 처음 쓰는 순서에서 아직 생성 전인 자기 문서 링크2개를 실패로 판정한 기록도 보존했다. 파일 저장 뒤 링크 재검사와 감사는 PASS이며 제품/테스트 결함이 아니다. [작성 순서 기록](evidence/COLD_START_RECOVERY/audit-publication-order-summary.json)

## 최종 로컬 검증

|검증|결과|근거|
|---|---|---|
|BE 전체|629 + 별도 Render92 = **721 PASS**, failure/error/skip0|[BE](evidence/COLD_START_RECOVERY/be-final/summary.json), [Render](evidence/COLD_START_RECOVERY/render-confirmed/summary.json)|
|FE 전체|OAuth180 + demo148 = **328 PASS**(기존302+신규26), failure/skip/todo0|[FE](evidence/COLD_START_RECOVERY/fe-final/summary.json)|
|제품/test/E2E/Functions 타입|PASS; 제품 타입은 두 build의 tsc -b에도 포함|같은 FE 근거|
|OAuth/demo build·invalid mode|PASS / PASS / 의도한 fail-fast 거절 PASS|같은 FE 근거|
|ESLint|0 errors / 0 warnings|같은 FE 근거|
|기존 Chromium|**독립2회 각각6 PASS**, workers1/retries0, 외부 요청0·정리PASS|[core](evidence/COLD_START_RECOVERY/core-browser-final/summary.json)|
|모바일 Chart|390×844 / 768×1024 / 1440×1000 **3 PASS**, overflow0·수정/재집계/복원/logout 유지|[mobile](evidence/COLD_START_RECOVERY/mobile-final/summary.json)|
|신규 cold-start Chromium|**1 PASS**, 외부 요청0·정리PASS·실행 소스 보존PASS|[cold](evidence/COLD_START_RECOVERY/cold-browser-focus-02/summary.json)|
|검증 집계 Python|11 PASS, skip0|[집계](evidence/COLD_START_RECOVERY/final-audit-summary.json)|

최종 필수 검증 FAIL/BLOCKED/skip0이며 중간 RED·환경/도구 실패는 위 이력에 별도 보존했다.

기존 테스트의 assertion·대상·skip·6-case 계약을 완화하지 않았다. FE wrapper의 expected count만 실제 발견된180+148에 맞췄다. 공용 FE summary의 `existing_tests=176`은 공개 baseline, `new_tests=152`는 그 이후 누적이다. **이번 작업의 증가분은302→328, 26개**다.

신규 시간 기반26개는 정확한 deadline 전/도달/직후, 독립80회 상한, timer0·이후 GET0, manual1회/중복 합류, true후POST0/별도 클릭POST1, false/503/network/timeout,429 cooldown/fallback/독립성, generation/revision stale, abort, login409 복원, 기존logout 복구, 만료된 login cooldown 복귀를 검증한다.

## 실제 Chromium 경계

초기 **정확한 GET /api/auth/demo/ready의503**만 로컬 서버 측 opt-in fixture다. 브라우저 route는 외부 요청 차단/continue만 하며 응답 대체·route.fulfill·제품 API mock은 사용하지 않는다. Playwright clock으로 브라우저의240초와 이후 유휴시간을 전진시키고 실제 Spring/MySQL/Redis의 시계는 바꾸지 않는다. 이 검증은 서버가 실제240초 늦게 시작했음을 측정한 테스트가 아니다.

실행 소유 loopback control이 fixture를 해제한 뒤 수동 준비 확인은 실제 Spring으로 전달된다. 제품 gateway·Origin/Cookie/Set-Cookie 계약과6-case 기존 테스트는 그대로다.

- 초기 준비 GET1 관측 후240초 deadline → startupSlow. 이후10분 시간 전진에도 자동GET/POST0.
- 수동 클릭 → 실제 readiness GET1, 자동POST0. 추가1분 대기에도 새 요청0.
- 별도 체험 클릭 → 실제 login1·User1/Card1/Transaction240/Budget72·Redis session1·Chart 진입.
- 실제 logout → cookie 제거·Redis session0·보호 화면 차단·자동 새 login0.
-390×844: body 가로 overflow0, 안내문/버튼 화면 안, 버튼 높이48.78px. 스크린샷은 인증정보 없는 안내 영역만 저장했다.
- 외부 앱 요청0, managed provider 사용0, 모든 소유 client/process/컨테이너/임시 copy·dependency overlay 정리PASS.

[계약 결과](evidence/COLD_START_RECOVERY/cold-browser-focus-02/cold-contract.json), [네트워크 순서](evidence/COLD_START_RECOVERY/cold-browser-focus-02/cold-network.json), [390px 화면](evidence/COLD_START_RECOVERY/cold-browser-focus-02/cold-recovery-390.png)

## 제한 환경 관측

같은 제품 설정·실제 packaged application에서 **HTTP bind249.005초**, 이후 준비 확인14.696초, **총 ready263.701초**를 관측했다. 메모리 최대 관측415.8MiB, OOM=false, 첫/후속 login15.839/16.094초, login POST 재시도0이었다. 정상 종료 요청에 따른 exit143과 남은 소유 Docker 자원0·포트 해제를 확인했다. 이 실행은 이번 단계의 유일한 완료된 제한 패키지 관측이며 앞선 prerequisites 실패는 표본에 포함하지 않는다. [관측값](evidence/COLD_START_RECOVERY/render-confirmed/summary.json)

이는 로컬 Docker512MiB/CPU0.1 표본이며 같은 호스트의 다른 로컬 회귀와 실행 시간이 겹쳤다. 실제 Render 성능/기동 보장으로 표현하지 않는다. readiness240초·80회·request timeout·heap·CPU를 변경하지 않았고, 이번 수동 복구는 성능 최적화가 아니다. 실제 Render의 sleep/cold start와 공개URL 연결은 **NOT_RUN**이다.

## 파일·재현·보존

제품: `fe/src/auth/demoReadiness.ts`, `demoCoordinator.ts`, `fe/src/store/authStore.ts`, `fe/src/components/DemoAuthStatus.tsx`.

신규 회귀: `fe/tests/demo/demoColdStartRecovery.test.tsx`, `fe/e2e/cold-start.spec.ts`, `fe/playwright.cold-start.config.ts`. 기존E2E preview/reporter/타입 설정에는 opt-in readiness fixture와 정제된 실패 위치만 연결했다.

검증: `cold_start_checks.py`, `cold_start_browser.py`; 기존 `demo_capacity_checks.py`는 evidence 경로, `mobile_chart_browser.py`는 출력경로·worker 주입, `demo_browser_e2e.py`는 opt-in 서버fixture 경계만 추가했다. 기본 core/mobile 동작은 보존했다.

로컬에 준비된 Java21·Gradle cache·npm dependency·Chromium과 확인한 Docker 이미지가 필요하다. 설치/이미지 pull/실제 환경파일 로딩 없이 다음을 실행한다. CACHE/DEPS/BROWSERS는 검증자가 소유한 경로를 명시하며 공급자 설정을 넣지 않는다.

```sh
python3 -B scripts/verification/cold_start_checks.py --phase be --cache-seed "$CACHE" --run-label be-new
python3 -B scripts/verification/cold_start_checks.py --phase render --cache-seed "$CACHE" --dependencies "$DEPS" --run-label render-new
python3 -B scripts/verification/cold_start_checks.py --phase fe --dependencies "$DEPS" --run-label fe-new
python3 -B scripts/verification/cold_start_checks.py --phase browser --cache-seed "$CACHE" --dependencies "$DEPS" --browser-path "$BROWSERS" --run-label core-new
python3 -B scripts/verification/mobile_chart_browser.py --phase after --evidence-directory COLD_START_RECOVERY --cache-seed "$CACHE" --dependencies "$DEPS" --browser-path "$BROWSERS" --run-label mobile-new
python3 -B scripts/verification/cold_start_browser.py --phase verify --cache-seed "$CACHE" --dependencies "$DEPS" --browser-path "$BROWSERS" --run-label cold-new
```

작업 시작719개 파일을 체크섬·mode로 비교했고, 기존 파일 변경11개는 위 FE/검증 기반과 STATUS prepend뿐이다. 이전 STATUS 본문, 과거 보고서·evidence243개, BE211개, gateway/인증HTTP/제한/DB 설정, scanner 규칙·분류, HEAD/index를 보존했다. 삭제0·예상 밖 변경0이다. 실행 전 source 및 최종 제품 source를 구분해 보관했고, cold 집중 PASS 소스와 저장소의 FE/브라우저 관련 파일 bytes도 일치했다. [최종 보존/비노출 검사](evidence/COLD_START_RECOVERY/final-audit-summary.json)

기존 scanner의 **FAIL79**는 별도 공개 정제 부채다. 규칙·allowlist를 바꾸지 않았으며 새 위치/후보0, 이번 evidence의 실제 비밀값·PII·금지 필드0을 확인했다. 공급자 장애전환 이후 폐기 보장도 이번 로컬 검증으로 확대하지 않는다. 실제 배포 준비 완료가 아니며 stage/commit/push/PR/merge/배포0이다.
