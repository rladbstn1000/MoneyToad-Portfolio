# 로컬 gateway·서버 준비 대기·시스템 폰트

기준일 2026-10-01. 미커밋 Render runtime·공급자 준비 작업을 출발점으로 사용했다. 이 보고서는 이전 `01`·`02` 실행 결과를 덮어쓰지 않는다. 공급자 연결과 실제 Cloudflare/Render 배포는 로컬 결과와 별도다. 최종 실행 판정은 [STATUS](STATUS.md)에 기록한다.

## 제품 변경

### Cloudflare Pages 프록시

Pages root는 `fe`, build는 `npm ci --ignore-scripts --no-audit --no-fund && npm run build`, 출력은 `dist`다. `VITE_AUTH_MODE=demo`, `VITE_BACK_URL`에는 승인된 **브라우저 Pages HTTPS origin**을 명시한다(Render 주소가 아님). 현재 demo interceptor가 절대 origin을 검증하므로 빈 값/상대 주소를 넣지 않는다. `functions/api/[[path]].ts`와 `functions/api/index.ts`는 root 아래 Functions이며 `public/_routes.json`은 Vite가 `dist`로 복사한다. 실행 경로는 `/api`와 `/api/*`뿐이다.

서버 binding `BACKEND_ORIGIN`에는 별도 승인된 고정 HTTPS origin만 넣는다. 값이 없거나 userinfo/path/query가 있으면 503으로 닫는다. 요청 path/query로 목적지를 바꿀 수 없다. production/preview binding은 별도로 관리하며 preview에 production 값을 자동 제공하지 않는다. 아직 실제 URL이나 서비스를 만들지 않았다. 예제는 `fe/pages.env.example`이다.

method, 경로, query, body, Content-Type, Authorization, Cookie, X-MoneyToad-Demo, 실제 Origin을 전달한다. Origin이 없는 요청에 값을 만들지 않는다. 외부에서 제공한 Forwarded/X-Forwarded-*와 IP 관련 헤더는 제거하며 Host는 고정 upstream URL에 맡긴다. Cookie Domain/Path와 Set-Cookie는 변경하지 않고 각각 전달한다. API 캐시와 POST 자동 재시도는 없으며 redirect는 따라가지 않는다. upstream 실패는 502 JSON이며 401 또는 SPA HTML로 바꾸지 않는다.

이 구현의 Fetch API 계약 테스트는 플랫폼 호출을 대역으로 관찰한다. 실제 Chromium E2E의 gateway는 기존 로컬 HTTPS Vite proxy다. 따라서 두 검증을 합쳐 Cloudflare edge 실배포 PASS라고 주장하지 않는다. [Pages routing](https://developers.cloudflare.com/pages/functions/routing/) · [Workers Headers](https://developers.cloudflare.com/workers/runtime-apis/headers/).

### 서버 준비와 인증 대기

demo 전용 `GET /api/auth/demo/ready`는 200 `{"ready":true}` 또는 503 `{"ready":false}`와 no-store만 반환한다. SQL `SELECT 1`과 Redis PING만 사용하고 User/seed/session 생성은 없다. 기존 JWT chain은 유지하고 정확한 readiness GET만 익명 허용한다. 일반 profile에는 해당 bean/handler가 없다.

서버 HTTP 대기는 최대 8초, 작업자는 1개, 대기열은 0개다. SQL query timeout은 1초다. 드라이버가 interrupt에 즉시 응하지 않아도 새 probe를 쌓지 않고 503을 반환한다. 그 작업의 최종 자원 해제는 드라이버 연결 제한에 의존하므로 하드 실시간 중단 보장으로 표현하지 않는다.

FE는 초기 restore·명시적 login·명시적 복구에서 readiness Promise 하나를 공유한다. 전체 deadline 240초, GET당 최대 10초, 간격 3초, 시도 최대 80회다. 성공은 실제 application/json 및 정확한 ready boolean으로 확인한다. 기동 안내 HTML 200은 성공이 아니다. 기다리는 동안 보호 화면/업무 query를 실행하지 않는다. 이전 방문 응답은 generation 검사로 차단한다.

login만 timeout 60초다. reissue/session/logout와 기존 일반 API의 10초 설정은 유지한다. 이 값은 이전 로컬 145–170초 기동/17.396초 첫 login 관측을 넘는 **유한 초기 가정**이며 Render 성능이나 managed-provider 측정값이 아니다. login/reissue 결과가 불명확하면 자동 POST 재전송 없이 unavailable와 사용자 복구 버튼을 제공한다. 정상 업무 401 refresh는 기존 single-flight를 유지하고 readiness를 매번 호출하지 않는다. 백그라운드 영구 ping은 없다.

### 시스템 폰트

제품의 전역·랜딩·장독대 CSS에서 미확인 외부 폰트 정의를 제거하고 시스템 한글/기본 sans-serif stack을 사용한다. 폰트 파일 다운로드·복제·변환은 없다. E2E의 임시 CSS 변환도 제거해 실제 제품 CSS를 검증한다. Chart 금액·Select·버튼과 작은 화면은 실제 Chromium에서 확인했다. 운영체제에 따른 글꼴 차이는 허용하며 동일 폰트 픽셀 모양을 보장하지 않는다.

시각 검토에서 390×844 화면의 기존 상세 표가 좁게 압축되고 열 내용이 잘리는 한계를 발견했다. Select 옵션은 화면 안에서 동작하고 금액/수정/복원은 통과하지만, 이것을 모바일 레이아웃 정상으로 확대 해석하지 않는다. Chart 레이아웃 전체 수정은 이번 폰트 교체와 분리한 후속 항목이다. `MOBILE_CHART_LAYOUT_READY=false`이며 상세 스크린샷을 보존한다.

현재 Chart CSS/TSX는 HEAD 대비 변경0이다. 기존 16:9 상세 카드와 2열 grid/overflow 설정이 좁은 표의 구조적 원인이다. 시스템 폰트 전후 동일 모바일 A/B는 실행하지 않아 폰트 영향의 크기는 단정하지 않는다. E2E의 option bounding-box 검증은 선택 메뉴의 위치만 보장하며 전체 표의 가독성을 보장하지 않는다. 후속은 작은 breakpoint의 상세 카드 비율·1열 grid·header wrap·표 독립 scroll만 최소 조정하고 390/768/desktop을 다시 확인하는 작업이다.

## 실제 공급자 검증기

`managed_provider_check.py`는 기존 입력 검사와 별개인 명시적 실행 도구다. 입력은 지정된 외부 파일 하나만 allowlist 파싱하고 shell source/eval하지 않는다. 파일이 없으면 `INPUT_REQUIRED`, 원격 프로세스 시작0이다. `--compile-only`는 원격 입력/연결 없이 Java probe 준비만 검증한다.

실행기는 private 현재-tree 사본과 명시적 Java21/Gradle cache를 사용한다. 실제 probe는 테스트 package에 있고 제품 bean으로 등록되지 않는다. 전용 schema 하나에 검토한 7개 DDL을 적용한 뒤 실제 제품 ConfigData demo,render의 validate로 context를 구성한다. JWT/Guard/MockMvc HTTP/seed/집계 및 실제 JDBC/Lettuce/Lua를 사용한다. 외부 CsvClient/AI 호출은 즉시 실패시킨다.

쓰기 전 소유권 원장, 재실행에도 유지하는 login/command 예산, 유한 deadline과 finally 정리를 사용한다. 같은 private state directory를 재사용해야 하며 예산을 초기화해서 재시도하지 않는다. CREATE 결과나 정리가 불명확하면 private 원장을 보존하고 추가 쓰기를 막는다. 실제 provider 연결이 없는 로컬 TLS 리허설은 TiDB/Upstash PASS가 아니다.

Upstash의 별도 연결/재연결 관측과 장애전환 이후 승인된 revoke/CAS의 무손실 보장은 다르다. 공식 문서는 비동기 복제와 eventual consistency를 설명한다. 따라서 `UPSTASH_FAILOVER_REVOCATION_GUARANTEE=NOT_ESTABLISHED`, 채택 `HOLD`를 유지하며 기능 PASS로 이 보장을 대체하지 않는다. [Upstash consistency](https://upstash.com/docs/redis/features/consistency).

## 재현 진입점

모든 경로 인자는 소유한 전용 자원으로 명시한다. credential 원문·원격 자원명·로그는 evidence에 저장하지 않는다.

```sh
python3 -B scripts/verification/managed_provider_check.py --compile-only --cache-seed <owned-cache> --state-dir <private-task-state>
python3 -B scripts/verification/managed_provider_check.py --execute --cache-seed <owned-cache> --state-dir <same-private-task-state>
python3 -B scripts/verification/managed_provider_local.py --cache-seed <owned-cache>
python3 -B scripts/verification/deployment_fe_checks.py --dependencies <exact-lockfile-dependencies>
python3 -B scripts/verification/deployment_local_checks.py --phase be --cache-seed <owned-cache>
python3 -B scripts/verification/deployment_local_checks.py --phase browser --cache-seed <owned-cache> --dependencies <exact-lockfile-dependencies> --browser-path <owned-chromium>
python3 -B scripts/verification/render_runtime.py --cache-seed <owned-cache>
```

기존 BE275 selectors는 그대로다. Render91과 신규 readiness/probe 준비 테스트는 별도 집계한다. FE 기존176은 유지하며 신규 proxy/readiness/폰트 검사를 별도로 기록한다. dependency overlay는 원본 설치를 읽기 전용으로 재사용하고 도구 cache는 private 사본에만 둔다. 실행 후 소스·과거 evidence·설치 파일 보존과 소유 자원 정리를 확인한다.

실행 중 fixture/도구 문제와 최초 FAIL도 새 evidence에 남긴다. 과거 portfolio evidence는 수정하지 않는다. 새 결과 위치는 `evidence/AUTONOMOUS_CONTINUATION/`, 현 runtime runner 결과는 새 UUID의 `evidence/RENDER_RUNTIME/`다.

## 이번 실행 결과

|검증|실제 결과|새 근거|
|---|---|---|
|원격 입력|INPUT_REQUIRED: 지정 파일 없음. 연결/쓰기0|[입력 결과](evidence/AUTONOMOUS_CONTINUATION/provider-14119788b92e459f/summary.json)|
|새 launcher compile-only|PREPARED, 실제 remote 실행0|[컴파일 준비](evidence/AUTONOMOUS_CONTINUATION/provider-c800fb1668be4710/summary.json)|
|기존 BE 회귀|275 PASS, 실패/오류/skip0, 소스 보존/정리 PASS|[BE](evidence/AUTONOMOUS_CONTINUATION/be-0454eeb94f53/summary.json)|
|readiness 신규 단위|12 PASS|[readiness](evidence/AUTONOMOUS_CONTINUATION/readiness-unit/summary.json)|
|Render 기존 설정·통합|85+6 PASS, 실제 ConfigData/TLS/validate|[runtime](evidence/RENDER_RUNTIME/045c8457a53d/runtime-summary.json)|
|검증기 안전 단위·로컬 TLS 리허설|19 단위 PASS와 7 계약 PASS, 실제 login4·외부0·정리 PASS|[provider-local](evidence/AUTONOMOUS_CONTINUATION/provider-local-4fda85a97fde/summary.json)|
|FE|기존176+신규59=235 PASS; 타입/E2E/Functions 타입, OAuth/demo build, lint0/0|[FE](evidence/AUTONOMOUS_CONTINUATION/fe-final/fe-summary.json)|
|실제 Chromium|독립2회 각각4 PASS, 제품 font transform0, 외부0, 소스/설치/정리 PASS|[browser](evidence/AUTONOMOUS_CONTINUATION/browser-016d954532e4/summary.json)|

준비 도구 Python33, 기존 공개 보호14, dependency overlay7도 별도 PASS이며 제품 테스트 수에 합산하지 않는다. 위 BE 계열은 275+91+12+19=397이지만 한 번의 단일 suite 실행 수가 아니라 별도 검증 합계다.

제한 없는 MacBook의 실제 로컬 TLS 리허설에서 context 3.122초, 첫 login 0.881초, 후속 login 0.512초, session 0.024초, 연간 조회 0.063초를 관측했다. 원격 네트워크 또는 Render 수치가 아니다. 예약 command-equivalents 6,096(정리2,000 포함), 관측 wire command66은 서로 다른 수치이며 공급자 billing 측정이 아니다.

별도 512MiB/0.1CPU 로컬 컨테이너는 기동 219.439초, login 23.504/4.804초, 샘플 최대 메모리 410.6MiB, OOM=false, 정리 PASS였다. FE의 240초 준비 제한/60초 login 제한은 이번 관측보다 길지만 실제 Render·원격 DB 지연에 대한 보장은 아니다. 기동 여유가 약20.6초뿐이라는 한계를 남기며 초과 시 재전송 없이 명시적 복구로 처리한다.

초기 실패도 보존했다. FE 초기5개는 CSS raw import와 body 없는 GET Content-Type 기대 오류였고, 다음4개는 테스트 URL 변환 문제였다. 실제 파일 읽기와 올바른 GET 계약으로 수정한 최종235가 통과했다. 첫 browser 호출은 실행파일 대신 설치 디렉터리를 요구하는 도구 인자 오류였다. 첫 BE/browser 기능 검증은 통과했으나 병렬 작업 중 검증기/환경 예제가 변경돼 보존 gate가 실패했다. 최종 고정 사본으로 다시 실행해 두 gate까지 통과했다. 기존 assertion, skip, 기대 금액, 보안 정책은 완화하지 않았다.

## 제외와 공개 전 조건

실제 서비스 생성·secret 등록·배포·stage/commit/push는 하지 않았다. 데이터 수용 상한, 생성 남용 방지, 잔존 SQL 정리, 다중 탭 인증, managed-provider 채택 결정은 남아 있다. 로컬 gateway/UX 준비 완료가 공개 운영 준비 완료를 뜻하지 않는다.
