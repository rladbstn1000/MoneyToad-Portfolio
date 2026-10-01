# 18. Public-demo gateway와 bounded abuse guard

기준: 2026-10-01. 최신 17단계 이후 미커밋 작업 트리를 보존하고 public-demo 요청 경계만 추가했다. 실제 Cloudflare/Render/TiDB/Upstash 연결·private provider 입력/state 접근·서비스 생성·배포는 0이다. capacity/cleanup/DDL/권한·JWT/Redis 세션·seed 제품 코드는 변경하지 않았다.

## 결과와 검증 범위

```text
LOCAL_DEMO_ABUSE_GUARD_READY=true
PUBLIC_DEPLOYMENT_READY=false
```

|최종 고정 source 검증|결과|
|---|---|
|전체 BE|721 PASS = 일반52클래스629 + Render 전용3클래스92; failure/error/skip0|
|이번 신규 BE|76 PASS; 위721에 포함|
|전체 FE|294 PASS = OAuth175 + demo119; 기존252 유지 + 신규42|
|제품/test/E2E/Functions 타입|PASS|
|OAuth/demo build·잘못된 auth mode 거절|PASS|
|ESLint|0 errors / 0 warnings|
|Chromium 독립2회|각6 PASS, workers1/retries0, 실제 bundle/Spring/JWT/Guard/MySQL/Redis|
|외부 애플리케이션 요청|두 실행 모두0|
|검증 집계 Python|11 PASS = 신규3 + 기존8|
|모든 최종 실행 소유 자원 정리|PASS|

과거633 이후17단계에서 추가한 원격 검증기의 로컬 안전 테스트12도 이번 전체 BE discovery에 포함했다. 따라서 이번 직전 기존645 + 신규76 =721이다. 원격 기능을 이번에 다시 실행한 수가 아니다. FE evidence의 기존176 표기는 공개 snapshot 원기준이며 이번 직전252 대비 신규42를 구분한다.

추가 로컬512MiB/0.1CPU 패키지 검증: HTTP 준비334.474초, 제품 readiness까지357.782초, 최대 관측434.8MiB, login33.519/14.195초, OOM=false, 소유 컨테이너·포트 정리 PASS. **357.782초는 기존 FE240초보다 길다.** 이 실행은 실제 Render 성능이나 cold-start UX 충족의 근거가 아니며 timeout을 변경하지 않았다.

실제 Cloudflare edge 실행은 NOT_RUN이다. 로컬 Function Fetch 계약과 서버 측 HTTPS proxy fixture가 실제 Spring/JWT/Guard/MySQL/Redis를 연결하는 Chromium 검증을 분리한다. 공급자 기능과 장애전환 보장, 실제 무료 배포 성능을 이번 결과로 확대하지 않는다.

## 서버 gateway 경계

Pages Function은 고정 HTTPS BACKEND_ORIGIN과 서버 secret binding DEMO_GATEWAY_SECRET을 사용한다. 브라우저의 X-MoneyToad-Gateway/X-MoneyToad-Client-IP 및 기존 forwarded 헤더를 제거한 뒤 서버 값으로 설정한다. canonical unpadded Base64URL 32-byte 형식이며 실제 운영 값은 생성하거나 등록하지 않았다. Function 설정이 없거나 잘못되면 upstream 호출 없이 고정503이다. response의 내부 두 헤더도 제거한다.

public-demo의 설정은 profile guard에서 일반 singleton/DB 초기화 전에 fail-closed 검증한다. Render profile 여부와 무관하게 public-demo에 적용한다. OAuth/local-demo는 새 검사와 limiter가 비활성이다. GatewayFilter는 Security chain에서 CorsFilter 앞에 한 번만 구성하며 Servlet filter bean으로 자동 등록하지 않는다. 값 비교는 형식 검증 뒤 constant-time 비교다. 누락/위조/중복 헤더는403 DEMO_GATEWAY_REJECTED, no-store, Set-Cookie0으로 거절한다. 실제 JWT를 함께 보내도 Guard/User DB/Redis/seed 호출0을 검증했다.

|외부 요청|Gateway|추가 정책|
|---|---|---|
|POST /api/auth/demo/login|필수|유효 IP, 기존 HTTP 계약 뒤 login limiter|
|POST /api/auth/demo/reissue|필수|login bucket 미사용, 기존 RT 정책|
|GET /api/auth/demo/session|필수|기존 JWT/Guard 유지|
|POST /api/auth/demo/logout|필수|기존 JWT/Guard 유지|
|GET /api/auth/demo/ready|필수|별도 readiness limiter|
|OPTIONS /api 및 /api/**|필수|gateway 이후 기존 CORS|
|GET /api/test|유일한 예외|기존 상수 liveness, DB/Redis/seed0|
|HEAD/POST /api/test 및 나머지 /api/**|필수|기존 인증/경로/소유권 계약 유지|

method/path/query/body/Content-Type/Authorization/Cookie/Origin/각 Set-Cookie/status 전달을 보존한다. upstream fetch는 한 번, redirect는 manual, API cache와 POST retry는 없다. 3xx/Location을 브라우저에 반환하는 기존 계약은 유지하므로 브라우저 전체의 redirect 차단이라고 표현하지 않는다. 내부 값은 브라우저가 보유하지 않으며 다른 upstream으로 서버가 redirect-follow하는 경로가 없다.

서버 shared secret은 gateway 경로 인증이며 사용자 JWT/세션/Origin/소유권 검사를 대체하지 않는다. 실제 Render 직결은 NOT_RUN이고, 로컬 동일 제품에서 gateway 없는 직접 접근 거절을 검증했다.

## Client IP와 개인정보 경계

지원 경로는 브라우저에서 Pages로 직접 들어오는 요청이다. Function은 Cloudflare edge CF-Connecting-IP literal을 검사한다. Worker subrequest나 Pseudo IPv4 overwrite처럼 실제 주소 의미를 확정할 수 없는 입력은 login에서 거절한다. BE는 gateway 인증 뒤 내부 client-IP 헤더만 사용하고 X-Forwarded-For/직접 CF 헤더/remoteAddr를 fallback으로 쓰지 않는다.

IPv4/IPv6 literal만 DNS 없이 파싱하고 IPv4-mapped IPv6는 IPv4로 통합한다. BE IPv6 key는 /64, IPv4는 전체 주소다. 누락/복수/comma/hostname/port/CIDR/zone/잘못된 형식은403 DEMO_CLIENT_ADDRESS_REJECTED다. IP를 사람이나 계정 identity로 취급하지 않는다. 공유 NAT·VPN·IPv6 prefix 공유에는 오탐 제한 가능성이 있다.

주소 key는 메모리에만 존재한다. 원문/prefix/hash를 제품 로그·DB·Redis·FE 상태·공개 evidence에 저장하지 않는다. 물리적 메모리 제거는 다음 요청의 만료 정리 또는 process 종료 시이며 엄격한 실시간 삭제 보장을 주장하지 않는다.

공식 의미: [Cloudflare request headers](https://developers.cloudflare.com/fundamentals/reference/http-headers/), [Pages secret bindings](https://developers.cloudflare.com/pages/functions/bindings/). 실제 계정의 edge/Pseudo IPv4 설정은 이번에 접속·변경하지 않았다.

## 생성 제한과 메모리 상한

단일 JVM의 monotonic clock, 고정 timestamp ring과 최대1024 IP entry로 exact rolling window를 구현했다. 초기 운영 가정이며 측정된 최적값이 아니다.

|대상|초기 제한|
|---|---|
|IP별 login|10회 / 30분|
|전체 login short|5회 / 1분|
|전체 login long|30회 / 1시간|
|IP entry|최대1024|
|전체 readiness|60회 / 1분|

한 synchronized critical section에서 만료 timestamp/entry 제거, 모든 한도 검사, 허용 시 전체 bucket 기록을 처리한다. 그 안에 DB/Redis/HTTP/file I/O는 없다. 요청별 thread, scheduler, unbounded queue/map을 만들지 않았다. IP entry는 마지막 허용 login 후30분이 지난 것만 제거한다. 활성 entry를 eviction하지 않으며 포화된 새 IP는429로 거절한다. 거절은 새 timestamp/entry나 수명 연장을 만들지 않는다. 만료 정리는 전체 상한1024개 안에서 유한하다.

기존 MVC body/header/Origin 검사는 order0, 새 interceptor는 order1이다. body를 다시 읽지 않는다. login은 gateway/IP/HTTP 계약을 통과하고 서비스에 진입하도록 허용된 시점에 계산한다. 이후201/409/401/FULL/BUSY/DB·Redis503이어도 환급하지 않는다. 잘못된 입력이나 이미 제한된 요청은 사용량을 늘리지 않는다.

429 code는 login의 DEMO_LOGIN_RATE_LIMITED와 readiness의 DEMO_READINESS_RATE_LIMITED로 분리한다. Retry-After는 필요한 모든 window/공간의 최대 대기시간을 올림한 양의 정수 초다. count/IP/timestamp를 반환하지 않으며 no-store/Set-Cookie0이다. readiness limiter는 실제 ready handler의 GET/HEAD에 적용하고, 기존 worker1/queue0/timeout을 보존한다. 인증되지 않은 HEAD는 기존 Security의401이며 실제 probe가 실행되지 않는다.

## FE 처리

직접 login 응답의 정확한429/code만 loginRetryAt 메모리 상태로 처리한다. Retry-After 1~3600 정수만 인정하고 잘못된 값은60초 안내로 처리한다. 현재 방문/쿠키를 revoke하거나 지우지 않으며 이전 generation 응답을 거절한다. cooldown은 UI와 loginDemo 진입에서 readiness보다 앞에 검사한다. 만료 후 버튼만 활성화하고 자동 login/POST retry는0이다. reissue/session/logout은 login bucket과 cooldown을 사용하지 않는다.

readiness의 정확한429/code만 GET polling의 대기를 조정한다. 기존240초 deadline·80회·요청 timeout은 바꾸지 않았고 deadline 뒤 새 요청을 예약하지 않는다. 같은 origin은 그대로이며 기존 별도 CORS에서는 Retry-After만 expose한다. 내부 gateway/IP 헤더는 browser allowed/exposed header에 추가하지 않았다.

기존503 DEMO_CAPACITY_FULL/DEMO_ADMISSION_BUSY와 기타401/503/network/불명확 결과는 별도 계약이다. 예상치 못한429를 확정적인 생성 전 거절로 확대하지 않는다.

## 실행 이력과 회귀

- 변경 전 BE: 기존 제품과 실제 JWT/로컬MySQL/Redis에서 gateway 없는 session 요청의 새403 계약을 실패시키는1건 RED. compile failure를 RED로 사용하지 않았다.
- 변경 전 FE: Function19건/demo 신규15건의 기능 RED. 제품 변경 전 source를 별도 보존해 실행했다.
- 준비 오류: 새 MockHttpServletRequest 중복 header 호출의 compile 오류, Vite ProxyServer 타입 오류를 교정했다. 기존 assertion이나 config 엄격도를 변경하지 않았다. 최초 집중/FE 결과를 보존한다.
- 최종 집중: 신규76 PASS. 실제 gateway→CORS→JWT 순서와 MVC order, invalid input quota0, readiness SQL/PING0, login429 전체 DB snapshot 불변, 기존 visitor reissue/read/logout, 실제 NOWAIT BUSY/FULL을 포함한다.
- 최초 browser 전체 각6건은 실제로 통과했으나 과거4건 집계 기준으로 series FAIL이었다. 기존4건 기본 계약을 유지하고 이번 실행에서6건을 명시하도록 교정했다. 실패/skip/외부 요청/정리 실패를 PASS로 바꾸지 않는 Python3건과 기존 runner8건을 통과했다. 원래 결과는 보존한다.

새 근거는 [DEMO_ABUSE_GUARD](evidence/DEMO_ABUSE_GUARD/)에만 저장했다.

- [변경 전 BE RED](evidence/DEMO_ABUSE_GUARD/red-be/summary.json), [변경 전 FE RED](evidence/DEMO_ABUSE_GUARD/fe-red-summary.json)
- [신규 BE76](evidence/DEMO_ABUSE_GUARD/focus-02/summary.json)
- [최종 일반 BE629](evidence/DEMO_ABUSE_GUARD/be-final/summary.json), [Render92·TLS·패키지](evidence/DEMO_ABUSE_GUARD/render-final/summary.json)
- [FE294·타입·build·lint·bundle 비노출](evidence/DEMO_ABUSE_GUARD/fe-final/summary.json)
- [교정 전 browser 집계 FAIL](evidence/DEMO_ABUSE_GUARD/browser-final/summary.json), [최종 독립2회](evidence/DEMO_ABUSE_GUARD/browser-confirmed/summary.json)
- [Python 집계 계약11](evidence/DEMO_ABUSE_GUARD/python-runner-summary.json)

재현은 저장소 루트에서 다음 진입점을 사용한다. 실제 `.env`를 로딩하지 않으며 기존 안전 runner의 소유 자원·오프라인 dependency/cache 조건을 따른다. 각 실행의 run-label은 새 값을 사용해 과거 결과를 덮어쓰지 않는다.

```text
python3 -B scripts/verification/demo_abuse_guard_checks.py --phase be --cache-seed <owned-gradle-cache> --run-label <new-label>
python3 -B scripts/verification/demo_abuse_guard_checks.py --phase render --cache-seed <owned-gradle-cache> --run-label <new-label>
python3 -B scripts/verification/demo_abuse_guard_checks.py --phase fe --dependencies <exact-lockfile-dependencies> --run-label <new-label>
python3 -B scripts/verification/demo_abuse_guard_checks.py --phase browser --cache-seed <owned-gradle-cache> --dependencies <exact-lockfile-dependencies> --browser-path <owned-chromium> --run-label <new-label>
```

## 이번 파일 범위

- 신규 BE: `auth/demo/DemoGatewaySettings`, `DemoGatewayFilter`, `DemoClientAddress`, `DemoAbuseLimiter`, `DemoAbuseRequestInterceptor`.
- 기존 BE: `DemoAuthHttpConfiguration`, `AuthProfileGuardConfiguration`, `SecurityConfig`, `application-demo.yml`, `render.env.example`의 경계/설정만 추가.
- Function/FE: `functions/api/[[path]].ts`, 서버 helper `server/demoClientAddress.ts`, `pages.env.example`, `demoRateLimit.ts`, `demoCoordinator.ts`, `demoReadiness.ts`, `authStore.ts`, `DemoAuthStatus.tsx`.
- 테스트: 신규 BE7개 test class와 합성 gateway 지원 helper, 기존 BE8파일의 public-demo 준비 header/설정, FE Function/demo 테스트, 실제 E2E proxy·시나리오.
- 검증: 새 `demo_abuse_guard_checks.py`와 집계 test; 기존 local runner의 합성 gateway 전달/이번6-case 선택/FE build canary 검사만 확장. `public_evidence.py`의 browser 집계는 기본4를 유지하고 이번에 명시6을 전달한다. scanner 규칙·정제 금지 패턴은 불변이다.
- 문서: 이 보고서와 STATUS prepend 및 새 evidence. package/lockfile, DB/Redis 구조, capacity/cleanup 제품·DDL, 기존 인증/seed 서비스는 변경하지 않았다.

## 보존·정제 및 한계

시작627개 파일 중 제품/검증 변경27개와 STATUS prepend1개를 제외한599개는 content/mode 그대로다. 삭제0, 과거 보고서/evidence198개·제품 capacity/cleanup/DDL/JWT/seed 핵심18개·provider 도구50개·Git HEAD/index 보존을 별도로 확인했다. 기존 BE 테스트8파일의 assert/verify/assume와 test/parameter/skip annotation은 그대로이며 public-demo 설정·헤더 준비만 추가했다.

scanner는 기존 **FAIL60 → FAIL78**로 남는다. 추가 위치 후보20개와 기존 줄 이동으로 사라진 위치2개를 구분해 검토했으며, 변수/합성 fixture/헤더 계약과 로컬 runtime credential 생성 코드 후보였다. 실제 secret/PII 유출 확인0이다. 이는 자동 PASS가 아니며 공개 정제 부채를 해결했다고 표시하지 않는다. `public_scan.py`, classification allowlist와 evidence 금지 패턴은 변경하지 않았다.

새 evidence는 정제 JSON만 사용한다. JWT/RT/cookie 값·gateway 값·IP 원문/prefix/hash·개인 식별값·로컬 경로·process/container identity·raw HTTP/log/trace/HAR/video/storageState를 저장하지 않았다. known synthetic canary의 FE bundle·응답·제품 로그 비노출, 새 JSON의 금지 필드/값0을 확인했다. 실제 배포 secret은 생성하지 않았다. 새 최종 [보존·정제 감사](evidence/DEMO_ABUSE_GUARD/final-audit-summary.json)에 수치와 범위만 기록한다.

단일 instance·재시작 시 기록 초기화이며 영속 상한/분산 rate limit/DDoS 완전 방어가 아니다. 공격자가 전체 bucket을 먼저 사용하면 정상 방문자도 기다린다. readiness 제한도 복구 가용성을 보장하지 않는다. 잘못된 요청의 parsing/느린 전송, 기존 visitor 업무 조회·reissue·logout·OPTIONS·정적 자산의 전체 요청률은 이번 제한 밖이다. 기존 capacity/수동 cleanup이 SQL 점유 상한을 별도로 담당한다.

실제 Cloudflare/Render/공개 URL E2E, provider 재검증, 장애전환 보장, cold-start timeout 변경, Turnstile, 모바일 수정, 신규 DB/Redis 구조, stage/commit/push/배포는 수행하지 않았다. 기존 scanner 부채와 공급자 장애전환 미확인 상태는 분리한다.
