# 23. Public demo 전체 사용자 경험 — 로컬 복원

시작 기준: clean main `f569d9cd0054ce5bd3661ac2fe957b6c3eba225a`. 이번 결과는 로컬 구현·검증이며 실제 공개 서비스는 기존 UI다. 공개 서비스의 기존 검증 제품 SHA `1d3479e39defe5777fa122846bf7988ec7e12c94`를 재배포하지 않았다. README·BE·seed·DB schema·인증 엔진·gateway·rate limiter·provider 설정은 변경하지 않았다.

## 구현 범위

- 마당, 장독대, 씀씀이, 두꺼비의 조언, 곳간, 정보 입력의 실제 페이지를 demo 라우트에 등록했다. 공통 RouteGuard는 유지하며 복원 중·비인증·장애 상태에서 업무 화면과 요청을 차단한다. callback은 홈으로 이동하고 알 수 없는 경로는 NotFound다.
- 명시적 로그인 또는 체험 이어가기는 `/pot`에서 기존 연간 API의 마지막 기준월을 확인한 뒤 해당 장독대로 이동한다. 기간 조회 실패는 세션을 유지한 수동 조회 재시도이며 추가 로그인하지 않는다.
- 기존 메뉴를 복원하고 900px 이하에는 키보드·Escape·경로 이동으로 조작하는 44px 이상 메뉴 버튼을 제공한다. 페이지별 기존 자산만 preload하며 이전 경로의 완료 응답은 현재 로딩 상태를 바꾸지 못한다.
- 장독대의 기존 항아리·균열·Lottie 물·웅덩이·캐릭터와 실제 예산 조회/저장을 연결했다. 실제 ID가 있는 6개만 수정하며 나머지 항목은 ‘기준 예산 없음’과 읽기 전용 slider로 표시한다. 성공 대신 샘플 fallback을 표시하지 않는다.
- 예산은 즉시 pending 값으로 표시하고 500ms 뒤 저장한다. 방문·선택 월·실제 응답 소속·양수 ID·행 revision을 확인한다. 미전송 timer는 월 이동·unmount·logout·방문 교체 시 취소하고 전송 중인 행만 잠근다. 실패한 행만 pending을 제거하고 재조회하며 자동 PATCH retry는 없다.
- 예산과 거래 분류 수정은 현재 방문의 `monthlyBudgets`·`transactions`를 함께 무효화한다. 이전 방문의 늦은 callback은 새 방문 cache를 변경하지 않는다.
- 조언의 지출·누수·상점·날짜·방문 빈도는 실제 API에서 계산한다. 같은 상점만 묶고 준비된 문구·V1 참고 평균은 별도 모듈에 둔다. 예산 없는 항목은 실제 소비와 ‘비교 기준 없음’을 표시하며 과소비나 0원 예산으로 주장하지 않는다. demo 컨테이너는 AI query를 호출하지 않고 샘플 분석임을 명시한다. OAuth의 기존 AI 경로는 유지한다.
- 곳간의 문→콩쥐→종이와 정보 입력의 대화·4단계를 복원했다. 성별·나이·카드 A/B 선택만 방문별 메모리에 저장한다. 저장/취소·경로 이동/토큰 회전 중 유지, reload/방문 교체/logout 초기화를 검증한다. 페이지 자체 user/card 요청과 금융정보 입력은 없으며 공통 Guard의 기존 `/users` GET만 허용한다.

공통 모듈은 `fe/src/demo/`의 profile/provider, API 기간, 예산 표현/저장, 조언, 자산 선택으로 나눴다. 새 외부 패키지·이미지·폰트·API endpoint는 추가하지 않았다.

## 실제 Chromium 결과

|검증|결과|근거|
|---|---|---|
|전체 체험 390×844·768×1024·1440×1000|3 PASS, 여섯 페이지·실제 저장·reload·종료|[실행 근거](evidence/FULL_DEMO_EXPERIENCE/full-browser-final-06/summary.json)|
|기존 core 독립 두 실행|각 6 PASS, gateway/제한/CORS/cookie A·E/폐기 유지|[실행 근거](evidence/FULL_DEMO_EXPERIENCE/core-final-03/summary.json)|
|기존 Chart 모바일·태블릿·desktop|3 PASS, 가독성·실제 Select/PATCH·복원|[실행 근거](evidence/FULL_DEMO_EXPERIENCE/mobile-final-02/summary.json)|
|기존 cold-start 수동 복구|1 PASS, 자동 POST0·수동 확인 후 명시적 체험|[실행 근거](evidence/FULL_DEMO_EXPERIENCE/cold-final-02/summary.json)|

모든 최종 실행은 외부 앱 요청0·정리 PASS이며 workers=1, retries=0이다. cookie D의 fixed-clock 제품 검증은 아래 BE에 포함된다.

각 전체 여정은 전용 로컬 Spring Boot/JWT/Guard/MySQL/Redis와 실제 HTTPS gateway·demo bundle·Chromium을 사용했다. 제품 API response mock, 강제 DOM event, 강제 좌표 클릭은 없다. 서버의 고정 clock 또는 준비 fixture를 사용하는 기존 테스트는 그 책임을 그대로 구분한다.

전체 여정의 각 방문에서 User1/Card1/거래240/예산72를 관측했다. 실제 카페 한도 40,000→60,000→40,000 저장과 SQL 재조회, 누수 18,000→0→18,000을 확인했다. 이후 Chart 분류 연습 거래를 카페→마트/편의점으로 수정해 총 908,000 유지·누수0·annual leaked=false를 확인했다. 현재월의 오래된 카페 조언은 사라지고 M-5 문화생활 180,000/한도60,000/누수120,000 상세가 표시된다.

곳간 취소/저장·정보 입력·장독대 복귀 후 reload에서 로그인 추가0, reissue/session으로 복원했다. 예산·분류는 유지되고 메모리 프로필은 초기화됐다. logout의 쿠키 제거·세션 폐기·보호 화면 차단을 검증했다. 폐기 전 AT의 401은 기존 core 계약에서 유지한다. 외부 앱/SSAFY/AI/peer/card 요청과 사용자정보 PATCH는0이다.

모바일 장독대는 월 선택→항아리/캐릭터→예산 패널 순서와 실제 크기·가림·비겹침을 검사했다. 조언은 390px 1열/768px 2열, viewport 안의 스크롤 modal·Escape·포커스 복귀를 확인했다. 곳간과 정보 입력은 실제 버튼·선택·스크롤로 완료했고 모든 페이지의 body 가로 overflow가 없다. 비식별 screenshot은 새 evidence에만 저장했다.

## 전체 회귀

|검증|결과|근거|
|---|---|---|
|BE 기존 전체|724 PASS, failure/error/skip0, compile PASS|[실행 근거](evidence/FULL_DEMO_EXPERIENCE/be-final-authorized/summary.json) · [실행 근거](evidence/FULL_DEMO_EXPERIENCE/render-final/summary.json)|
|FE 전체|459 PASS = 기존373 + 신규86; OAuth225/demo234, failure/skip/todo0|[실행 근거](evidence/FULL_DEMO_EXPERIENCE/fe-final-04/summary.json)|
|제품·test·E2E·Functions TypeScript|PASS|[실행 근거](evidence/FULL_DEMO_EXPERIENCE/fe-final-04/summary.json)|
|OAuth/demo build·invalid mode fail-fast|PASS|[실행 근거](evidence/FULL_DEMO_EXPERIENCE/fe-final-04/summary.json)|
|ESLint|0 errors / 0 warnings|[실행 근거](evidence/FULL_DEMO_EXPERIENCE/fe-final-04/summary.json)|
|scanner/보존 도구 합성 검사|66 PASS|[실행 요약](evidence/FULL_DEMO_EXPERIENCE/final-review.json)|
|strict public scanner|PASS, HARD/PII/금지 evidence/미분류/stale/예상 밖 artifact0|[실행 근거](evidence/FULL_DEMO_EXPERIENCE/strict-final-02/summary.json)|

FE 공용 runner의 기존 `existing_tests=176/new_tests=283` 필드는 최초 브라우저 단계 기준이다. 이번 단계의 비교 기준은 사용자 지정373개이며, 최종 discovered459개에서 신규86개로 계산했다. 원래 실행 요약을 덮어쓰지 않았다.

BE 전체는 일반 632개와 별도 Render 설정86/TLS6의 합계724개다. 로컬 제한 컨테이너 관측을 실제 Render 성능 또는 managed provider 재검증으로 표현하지 않는다. TiDB/Upstash·공개 URL·서비스 생성·배포 요청0이다. 기존 공급자 장애전환 미확인 보장은 변경하지 않았다.

## 중간 실패와 교정

- 처음 BE 실행은 sandbox의 Docker 접근 제한으로 시작 전 BLOCKED였다. 전용 로컬 자원 접근을 허용한 후 전체 검증을 실행했다.
- 전체 브라우저 초기 실행은 화면 준비 전에 모바일 여부를 판단하고 재조회 중 교체되는 SVG 점을 스크롤하는 테스트 경합으로 중단됐다. viewport와 실제 준비 상태를 기다리고 고정 차트 영역을 스크롤한 뒤 현재 점을 실제 클릭하도록 교정했다. 예상 값·응답·대상은 완화하지 않았다.
- screenshot 검토에서 모바일 장독대의 flex 압축/가운데 정렬로 월 메뉴와 예산 패널이 겹치는 제품 문제를 확인했다. demo 모바일 범위에서 일반 문서 흐름으로 교정하고 실제 위치·크기·가림 검사를 추가했다. desktop 배치는 유지했다.
- collector의 새 contract 파일명 조건과 안전한 assertion 위치 기록을 수정했다. 오류 원문·인증값은 저장하지 않았다.
- 기존 abuse E2E의 재로그인 경로는 장독대로 변경된 목적지를 거쳐 Chart로 이동하도록 맞췄다. 6-case와 기존 제한/쿠키 assertion을 모두 유지했다.
- 최종 검토에서 null-ID 예산을 조언이 실제 0원 한도로 취급하는 경계를 찾아 실제 소비/기준 없음 계약으로 보강했다. ID가 있는 실제 0원 예산의 기존 계산은 유지했다.

중간 결과는 `full-browser-01`부터의 별도 폴더와 `core-final-01/02`에 남겼다. 최종 판정은 아래 최종 실행을 기준으로 하며 과거 실패를 덮어쓰지 않는다.

## 재실행과 보존

저장소 루트에서 기존에 검증한 Java21·전용 Gradle cache·정확한 lockfile dependency·설치된 Chromium을 명시한다. 실제 환경파일을 읽지 않으며 각 실행은 새 소유 자원을 사용한다.

```sh
python3 -B scripts/verification/full_demo_checks.py --phase be --cache-seed <owned-cache> --run-label <new-label>
python3 -B scripts/verification/full_demo_checks.py --phase render --cache-seed <owned-cache> --run-label <new-label>
python3 -B scripts/verification/full_demo_checks.py --phase fe --dependencies <owned-dependencies> --run-label <new-label>
python3 -B scripts/verification/full_demo_checks.py --phase browser --cache-seed <owned-cache> --dependencies <owned-dependencies> --browser-path <owned-browser> --run-label <new-label>
python3 -B scripts/verification/full_demo_browser.py --phase verify --cache-seed <owned-cache> --dependencies <owned-dependencies> --browser-path <owned-browser> --run-label <new-label>
python3 -B scripts/verification/mobile_chart_browser.py --phase after --evidence-directory FULL_DEMO_EXPERIENCE --cache-seed <owned-cache> --dependencies <owned-dependencies> --browser-path <owned-browser> --run-label <new-label>
python3 -B scripts/verification/cold_start_browser.py --phase verify --evidence-directory FULL_DEMO_EXPERIENCE --cache-seed <owned-cache> --dependencies <owned-dependencies> --browser-path <owned-browser> --run-label <new-label>
python3 -B scripts/verification/full_demo_checks.py --phase scan --run-label <new-label>
```

원본과 별도로 고정한 소스에서 검증하고 정확한 bytes/mode의 변경만 작업 트리에 반영했다. BE·README·Functions·public asset·package/lockfile·과거 portfolio/deployment evidence를 보존했다. HEAD/index는 변경하지 않았다.

strict scanner의 규칙·허용 범위는 유지했다. 기존 분류148행/149곳의 내용 digest·construct·횟수·semantic 분류를 그대로 두고, 검토된 기존 표현11행의 전체 파일 digest/행 위치만 갱신했다. 새 허용 항목·wildcard·대상 제외는 없다. 실제 인증값·개인 식별값·로컬 절대 경로·원문 로그는 새 공개 evidence에 저장하지 않는다.

최종 diff 검사에서 수정한 선택자 16줄에 남아 있던 공백을 제거했다. 이 마지막 변경은 CSS 공백뿐이며 설치된 esbuild의 변환 결과가 수정 전후 완전히 같고 경고0임을 별도 확인했다. 기능 회귀를 다시 실행한 것으로 주장하지 않으며, 공백 정리 후 strict scanner와 diff 검사를 다시 통과했다. [공백 검증](evidence/FULL_DEMO_EXPERIENCE/final-format-review.json).

현재 변경은 로컬 미커밋 상태다. 기존 source/evidence 보존과 소유 자원·dependency overlay 정리는 [최종 검토 요약](evidence/FULL_DEMO_EXPERIENCE/final-review.json)에 기록했다.

## 최종 판정

```text
FULL_DEMO_EXPERIENCE_LOCAL_READY=true
PUBLIC_DEPLOYMENT_READY=false
```

실제 production은 이전 UI다. 이번에는 구현·로컬 검증·문서 저장까지만 수행했다. 다중 탭 조정, 실제 AI/금융정보, backend/seed/schema 변경, provider 재검증, Cloudflare/Render 배포, stage/commit/push는 수행하지 않았다.
