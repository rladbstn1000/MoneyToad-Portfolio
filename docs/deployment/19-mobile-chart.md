# 19. 모바일 Chart 상세 영역 가독성

2026-10-01. 이번 범위는 Chart의 responsive 표시와 관련 검증이다. 시작 작업 트리의 미커밋 변경을 기준으로 구현했으며 백엔드·인증·gateway·rate limit·capacity·cleanup·seed·provider 설정은 변경하지 않았다.

## 변경 전 실제 재현

동일한 기존 제품을 실제 Chromium, demo bundle, Spring Boot/JWT/Guard, 전용 로컬 MySQL/Redis에 연결했다. 제품 API 응답 대역을 사용하지 않았다. `before-04`는 세 viewport의 관측을 끝냈다는 의미의 PASS이며 **기존 mobile layout은 FAIL**이다.

|viewport|변경 전 관측|영향|
|---|---|---|
|390×844|고정 비율 card 높이211px, 두 열 각각164px. 표311px에 내부 overflow171px, 상호 셀36px, Select높이36px|상세 영역이 잘리고 거래명 줄바꿈이 과도했다. 해당 폭에서 월 선택도 성공하지 못했다.|
|768×1024|두 열 각각344px, 상호 셀42px/높이201px, Select36px|거래명이 지나치게 좁은 열에 쌓였다.|
|1440×1000|두 열 각각654px, 상호197px, Select174×36px|기존 desktop 배치를 기준선으로 보존했다.|

390px 변경 전 이미지는 **desktop에서 실제 월 선택 후390px로 줄인 진단**이다. 390px 월 선택 성공으로 표시하지 않았다. 좌표 강제 클릭·DOM event 강제 발송으로 실패를 숨기지 않았다. 변경 후에는 각 폭에서 일반 버튼/combobox/option으로 조작했다. Desktop 그래프는 실제 dot의 위치로 포인터를 이동한 후 나타난 activeDot을 정상 locator click했다.

## 제품 변경

- `fe/src/pages/ChartPage.css`:900px 이하에만 상세 영역을 한 열로 쌓고 전체 내용을 세로 스크롤한다. 표의 각 거래는 상호, 날짜·금액, 카테고리를 명시한 카드 형태로 표시한다. 기존 desktop CSS 본문은 그대로 보존했다.
- `fe/src/pages/ChartPage.tsx`: table/row/cell/header 의미와 거래별 category label을 유지한다. 작은 화면의 월 버튼은 기존 API의12개월과 기존 월 선택 함수를 사용한다. 첫 화면의 고정 비율/절대 배치는 작은 화면에서만 일반 흐름으로 바꿔 월 진입 경로를 확보했다. 모바일 원형 차트 표시는 폭에 맞추되 동일한 `pieData`를 사용한다.
- `fe/src/components/JPSelect.tsx`: 선택적 접근성 label과 Chart 전용 portal class만 추가했다. 다른 화면의 기본 label과 스타일은 유지한다. Chart의 모바일 trigger/option/스크롤 버튼은44px 이상이며 실제 Radix combobox/option이다.

거래명/금액/Select 글자는16px다. 본문 가로 overflow, 데이터 숨기기, 글꼴 과도 축소, scale을 사용하지 않았다. 화면 읽기 도구용 표 헤더만 시각적으로 숨기고 각 셀에 같은 뜻의 label을 표시한다. 거래 값은 숨기지 않는다. 요약과 아래 거래/차트는 세로로 이동해 읽는 구조이며 한 화면에 억지로 압축하지 않는다.

API, transaction ID, category PATCH, 합계·누수 계산, React Query 재조회, auth restore/generation 및 실제 seed 값은 불변이다. 샘플 fallback을 추가하지 않았다. Desktop 두 열·기존 파이 반경200px·라벨 계산을 보존했다.

## 실제 최종 viewport 검증

|viewport|실측 가독성/overflow|실제 업무 흐름|
|---|---|---|
|390×844|상호312px, 금액150px, Select312×44px, 한 열342px, body overflow0|월 선택·Select PATCH·재집계·복원·logout PASS|
|768×1024|상호690px, 금액339px, Select690×44px, 한 열720px, body overflow0|동일 전체 흐름 PASS|
|1440×1000|상호197px, Select174×36px, 기존 두 열654px씩, body overflow0|기존 배치/동작 PASS|

최종 source 고정·세 viewport3 PASS·외부 요청0·소유 자원 정리 PASS: [mobile-final 결과](evidence/MOBILE_CHART/mobile-final/summary.json).

선별 비식별 화면: [390px 변경 전](evidence/MOBILE_CHART/before-04/mobile-390-before.png), [390px 변경 후](evidence/MOBILE_CHART/mobile-final/mobile-390-after.png), [768px 변경 후](evidence/MOBILE_CHART/mobile-final/mobile-768-after.png). 중간 실행의 수치/실패 기록은 보존하고 중복 screenshot은 공개 근거에서 제외했다.

각 viewport에서 API 기준월 선택→분류 연습 거래의 실제 Select 변경→PATCH200→월 거래/카테고리/연간 GET을 확인했다. SQL 변경1건, 총소비908,000원 유지, 누수18,000→0원, 연간 해당 월 leaked=false다. 재집계 후에도 상호/금액/category가 읽히고 Select가 화면 안에 있다.

이어 reload 시 reissue를 관측 목적으로 잠시 보류하고 restoring UI와 보호 화면/업무 API0을 확인한 뒤 실제 요청을 그대로 계속했다. reissue→session 복원, 수정 category 유지, logout, cookie/세션 정리, 보호 페이지 차단과 자동 login0을 확인했다.

DOM 검증은 단순 `scrollWidth`만 사용하지 않는다. 실제 텍스트 Range, 셀/Select 크기, 화면 가로·세로 포함 여부, 조상 overflow에 의한 clipping, computed visibility를 확인한다. Desktop은 두 열과 table/pie의 가로 배치를 명시적으로 검사한다. CSS assertion만으로 완료를 판정하지 않았다.

## 실행 이력과 교정

- `before-01`의 dot hover 실패와 `before-02/03`의 activeDot 준비 문제는 새 관측 도구의 오류다. `before-04`에서 변경 전 제품의 실제 압축·잘림을 확보했다. 준비 오류를 기능 RED로 계산하지 않았다.
- 신규 FE CSS 읽기 테스트2건은 Vite의 raw import 처리/파일 경로 준비 오류를 고쳤다. 제품 기대값을 완화하지 않았다.
- `after-01`은 실제 세 흐름이 성공했으나 실행 중 검증 소스 변경을 탐지했으므로 최종 근거에서 제외했다. 소스 고정 재실행 결과를 별도로 보존한다.
- 원형 차트의 초기 빈 사진은 재집계 애니메이션 중간에 캡처한 결과였다. 종료 후6개 실제 sector를 확인했다. Recharts는3% 미만 custom label이 null이어도 빈 Text 요소를 남기므로 전체 text 수5를 요구한 신규 assertion은 잘못된 가정이었다. 이 실패도 보존했다.
- 완료 프레임에서는 새 모바일 안쪽 라벨의 겹침을 발견했다. 최종 표시를 교정하고 실제 Chromium 검증을 다시 수행했다. 애니메이션 중간 이미지나 CSS 검사만으로 PASS 처리하지 않았다.

최종 모바일 파이는 기존 비율만 SVG에 표시하고, 동일 `pieData`의 카테고리·색상·금액6개를 범례로 제공한다. 새로운 집계/비율 계산은 없다. 실제390px에서6 sector, 내용 있는 라벨5개, 라벨 겹침0, 범례6개 전부 화면 안에 있음을 관측하고 이미지를 직접 확인했다.3% 미만 문화생활은 기존 SVG label 정책을 유지하면서 범례에20,000원을 표시한다. Desktop은 범례를 추가하지 않고 기존 이름+비율을 유지한다.

## 전체 회귀

|최종 검사|결과|근거|
|---|---|---|
|전체 BE/compile|721 PASS, failure/error/skip0|[일반629](evidence/MOBILE_CHART/be-final/summary.json), [Render92](evidence/MOBILE_CHART/render-final/summary.json)|
|전체 FE|302 PASS = 기존294 + 신규8; OAuth180 + demo122; failure/skip/todo0|[최종 FE](evidence/MOBILE_CHART/fe-layout-final/summary.json)|
|제품/test/E2E/Functions TypeScript|PASS|동일 FE 결과|
|OAuth/demo build, 잘못된 auth mode fail-fast|PASS|동일 FE 결과|
|전체 ESLint|0 errors / 0 warnings|동일 FE 결과|
|기존 Chromium 독립2회|각6 PASS, 외부 앱 요청0, 정리 PASS|[최종 핵심 브라우저](evidence/MOBILE_CHART/browser-layout-final/summary.json)|
|모바일 전용 Chromium|390/768/desktop3 PASS, source/dependencies 보존·정리 PASS|[최종 viewport](evidence/MOBILE_CHART/mobile-final/summary.json)|
|Python 집계 계약|기존11 PASS|[Python 결과](evidence/MOBILE_CHART/python-runner-summary.json)|

신규 FE8개는 Select/스타일4, 실제 페이지/수정 실패/기준월3, compact pie/desktop 복귀1이다. 기존294개의 기대값과 실행 범위는 보존했다. 집중15개 및 최종 pie/Select20개도 별도로 검증했으며 전체302에 중복 합산하지 않는다.

BE 결과는 기존52클래스629 + Render 전용3클래스92를 합친721이다. 일부 클래스만 골라 전체 PASS로 표현하지 않았다. 최종 UI 교정 동안 BE 내용/mode는 동일하므로 BE를 다시 실행하지 않았다. FE evidence의 `existing_tests=176`은 공개 snapshot의 옛 기준이다. 이번 직전294개와 이번 신규 테스트를 별도로 구분한다.

기존 abuse guard E2E의 세 시나리오(핵심 방문, CORS, gateway/제한 수동 재시도)를 public-demo/local-demo에서 각각 실행하는 **6-case 계약**을 유지했다. 모바일 관측은 별도 config의3viewport이며 기존6개를 대체하지 않는다. workers1/retries0, 외부 앱 요청0, 로컬 소유 자원 정리 PASS다.

로컬 Render 제한 시험도 기존 계약으로 재실행했다:512MiB/0.1CPU, 최대 관측440.0MiB, 준비215.738초, login12.55/6.502초, OOM=false. 실제 Render·TiDB·Upstash 성능이나 cold-start 정책 충족을 일반화하지 않는다. 이번 UI 작업에서 timeout/readiness를 변경하지 않았다.

## 재현 방법

Java21, 소유 Gradle cache, 현재 lockfile과 동일한 FE 의존성, 소유 Chromium 설치와 로컬 Docker/MySQL/Redis가 필요하다. 실제 env 파일·원격 공급자를 사용하지 않는다. runner가 private copy와 합성 설정을 만들고 소유 자원만 정리한다. 실행 label은 기존 evidence와 겹치면 거절한다.

```sh
python3 -B scripts/verification/mobile_chart_checks.py --phase be --cache-seed <owned-gradle-cache> --run-label <new-label>
python3 -B scripts/verification/mobile_chart_checks.py --phase render --cache-seed <owned-gradle-cache> --run-label <new-label>
python3 -B scripts/verification/mobile_chart_checks.py --phase fe --dependencies <exact-lockfile-dependencies> --run-label <new-label>
python3 -B scripts/verification/mobile_chart_checks.py --phase browser --cache-seed <owned-gradle-cache> --dependencies <exact-lockfile-dependencies> --browser-path <owned-chromium> --run-label <new-label>
python3 -B scripts/verification/mobile_chart_browser.py --phase after --cache-seed <owned-gradle-cache> --dependencies <exact-lockfile-dependencies> --browser-path <owned-chromium> --run-label <new-label>
```

`before`는 의도적으로 관측 전용이며 `layout_ready=false`를 기록할 수 있다. 변경 전 재현에는 보존한 변경 전 제품 복사본을 사용했다. 현재 제품으로 `--phase before`를 실행하는 것을 과거 RED 재현으로 주장하지 않는다.

검증 변경은 새 mobile spec/config/runner와 기존 reporter의 안전 위치 허용, 기존 harness의 명시적 config/모드 선택 인자, 새 evidence namespace에 한정한다. 기본 harness 흐름·소유 자원 관리와 기존6개 테스트 본문은 보존했다. E2E TypeScript 설정에 새 config를 포함했다.

## 보존·비노출·남는 범위

시작662개 파일 중 이번 수정 대상 외의 내용/mode와 모든 BE211개 파일을 보존했다. 이전 보고서/evidence, package/lockfile, 인증·gateway·rate limit·capacity·cleanup·provider·scanner 규칙/allowlist는 불변이다. HEAD와 index도 보존했다. 상세 수치는 [최종 보존 검사](evidence/MOBILE_CHART/final-audit-summary.json)에 기록한다.

새 JSON은 기존 금지 필드/값 검사, 선별 screenshot3장은 PNG metadata 검사와 직접 시각 확인을 거쳤다. 공개 근거에는 정규화 요청 경로·상태·순서, 비식별 DOM 크기, 합계만 저장했다. 실제 인증정보·식별값·원문 로그/trace/HAR/storageState/video를 추가하지 않았다.

scanner는 기존 **FAIL78 → FAIL79**다. 추가1건은 기존 검증기의 검토된 난수 생성 표현이189→191행으로 이동해 위치 기반 분류와 일치하지 않는 항목이며 내용 digest는 기존 검토와 같다. 실제 secret/PII 확인0, 새 evidence 금지 값0이다. 분류/규칙을 변경하거나 scanner PASS로 표시하지 않았다. 이 별도 공개 정제 부채는 그대로 남긴다.

실제 provider 접속/입력/private state 접근·신규 예약0이다. backend/gateway/rate limiter/capacity/cleanup과 과거 원격 PASS는 이번 재검증 결과가 아니라 보존된 상태다. 실제 Cloudflare/Render, 실제 모바일 기기/Safari, 공개 URL E2E, 다중 탭은 이번 검증 범위 밖이다. 공개 배포를 완료로 표시하지 않는다.

stage/commit/push/PR/merge/배포0. 새 문서와 새 evidence만 저장했고 이전 STATUS 본문은 그대로 두고 최신 결과를 앞에 추가했다.

```text
MOBILE_CHART_LAYOUT_READY=true
PUBLIC_DEPLOYMENT_READY=false
```
