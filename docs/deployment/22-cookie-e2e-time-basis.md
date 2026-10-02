# Cookie E2E time basis — 검증 책임 분리

기준은 승인된 공개 HEAD `be513cd0938d4cc37cd77ea04035b97ae41c83f2`의 clean 작업 트리다. 이번 변경은 테스트·검증 도구·문서에 한정한다. 공개 URL이나 실제 공급자에는 요청하지 않았고 배포·설정·Git 이력을 변경하지 않았다.

## 변경 이유와 과거 실패의 한계

`D`는 서버의 절대 세션 만료, `C`는 서버의 cookie 생성 시각, `M=floor(D-C)`는 응답의 Max-Age, `Q=(D-C)-M`는 초 미만 절삭량이다. 정상 세션의 3600초 cap이 활성화되지 않는 구간에서, 저장까지의 경과 `L`과 browser/server wall-clock 차이 `K`를 두면 `E-D ≈ L+K-Q`다. `K=0`이어도 `L>Q`이면 브라우저에 기록된 절대 만료 `E`가 `D`보다 늦어진다. 예를 들어 초 단위 상대 모델에서 C=1, D=3600, M=3599, L=0.001이면 E=3600.001이다. 이는 결정적인 구조적 반례이며 실제 과거 지연·시계차 측정값이 아니다.

따라서 기존 browser absolute expiry와 server absolute expiry의 직접 상한 비교를 교체했다. 과거 공개 실행의 실패를 재현하거나 원인을 확정한 결과가 아니다.

```text
PRODUCT_COOKIE_CONTRACT_ANALYSIS=PASS
OLD_UPPER_BOUND_STABILITY=STRUCTURALLY_SENSITIVE
OLD_LOWER_BOUND_STABILITY=STABLE_FOR_NORMAL_FLOW
HISTORICAL_FAILURE_CAUSE=UNKNOWN
E2E_ASSERTION_CHANGE_JUSTIFIED=true
```

## A/D/E와 브라우저 저장의 책임

| 경계 | 실제 검사 | 의미 |
|---|---|---|
| A: 응답 | 실제 login201/reissue200의 headerValues(Set-Cookie), 정확히1개, 정수 Max-Age1..3600, HttpOnly/Lax/정확한 Path/Domain 없음, public Secure | 서버가 지시한 상대 보관 수명과 보안 속성 |
| 브라우저 저장 | 실제 cookie jar의 기존 속성, `stored.expires*1000 > await page.evaluate(Date.now)` | 현재 브라우저 clock에서 유효하게 저장됨 |
| D: 서버 | 실제 DemoRefreshCookie + 고정 Clock, `min(Duration.between(C,D).getSeconds(),3600)` | 서버 계산 시점의 남은 수명을 초 단위로 내림·상한 적용 |
| E: 세션 | login의 session deadline D1과 reissue 후 D2의 정확한 동일성 | rotation이 절대 인증 수명을 연장하지 않음 |
| 삭제 | 실제 logout204의 빈 cookie/Max-Age0/동일 속성과 jar 제거 | 보관 credential 제거 및 기존 Redis/AT 거절 계약 |

local-demo의 명시적 HTTP Secure=false 검증도 유지했다. helper는 raw 헤더나 RT를 반환하지 않고 숫자·boolean·고정 속성만 반환한다. 네트워크/파서 오류도 고정 코드만 반환한다. Expires는 함께 있을 수 있으나 Max-Age와 함께 주 수명 assertion으로 사용하지 않는다. 새로운 임의 tolerance는 없다. 실제 저장 시점 bracket·정밀도를 별도로 증명하지 않았으므로 optional B는 추가하지 않았다.

최종 인증 authority는 기존 JWT/Redis/session 절대 만료다. 브라우저의 credential 보관 시각은 서버 인증 허용 시각과 다르다. 기존 실제 cookie 전송, document.cookie 비노출, session 폐기 후 AT401, JWT/Redis deadline/rotation 검증을 보존했다.

## 범위와 보존

제품의 cookie 계산·JWT·Redis·Guard·login/reissue/logout·FE 인증·gateway/rate limiter는 변경하지 않았다. BE 테스트의 기존8개 방법을 유지하고 고정 Clock 경계3개를 추가했다. exact/fraction1·10·999ms, 정확히3600·그 이상, 1초·0·만료, 생성 phase0..999ms를 검증한다. helper45개는 속성·중복·정수 범위·삭제·오류 비노출을 검증한다.

새 `cookie_time_basis_checks.py`는 기존 소유 자원/정리 실행기를 재사용한다. BE의 전체 discovery를 유지하고 cookie11개만 명시적으로 갱신했다. FE 기존328 + helper45 = 373을 고정한다. 기존 Chromium6-case×2, mobile3, cold-start1을 유지한다. 새 evidence namespace 외에 과거 결과를 덮어쓰지 않는다.

scanner의 검사 규칙·semantic construct·기존 분류 개수는 그대로다. 요청된 코드 수정 때문에 stale이 되는 기존 source 표현6개는 내용 digest·출현 수·construct 불변을 먼저 검증하고 파일 digest와 행 위치만 재연결했다. 새 allowlist 항목·범위 확대는 없다. 새 helper/단위 파일의 credential-like candidate는0이다.

## 실행 결과

| 최종 검증 | 결과 |
|---|---|
| Cookie helper | **45 PASS**, 원문/RT 비반환·오류 비노출 |
| 실제 DemoRefreshCookie fixed Clock | **11 PASS** (기존8 + 신규3, 전체 BE에도 포함) |
| 전체 BE | **724 PASS** = 일반632 + 별도 Render86/실제 ConfigData TLS6, failure/error/skip0 |
| 전체 FE | **373 PASS** = OAuth225 + demo148, 기존328 유지 + 신규45 |
| 제품/test/E2E/Functions 타입, E2E 컴파일 | PASS |
| OAuth/demo build, invalid mode 거절, BE compile/package | PASS |
| ESLint | **0 errors / 0 warnings** |
| 검증 도구·strict scanner 관련 로컬 합성 검사 | **64 PASS** |
| Chromium core | **독립2회 각각6 PASS**, workers1/retries0 |
| Chromium mobile | **390×844 / 768×1024 / 1440×1000, 3 PASS**, body overflow0 |
| Chromium cold-start recovery | **1 PASS**, 기존 서버 준비 fixture·수동 복구 계약 |
| 외부 앱/공개 URL/실제 provider 요청 | **각0** |
| 소유 resource/process/overlay 정리 | **모든 실행 PASS** |
| strict public scanner | PASS, 미분류0, 기존 규칙·scope 유지 |

[실행 요약](evidence/COOKIE_E2E_TIME_BASIS/verification-summary.json), [BE](evidence/COOKIE_E2E_TIME_BASIS/be-full/summary.json), [Render](evidence/COOKIE_E2E_TIME_BASIS/render-full/summary.json), [FE](evidence/COOKIE_E2E_TIME_BASIS/fe-full/summary.json), [Chromium 두 실행](evidence/COOKIE_E2E_TIME_BASIS/core-browser-two-runs/summary.json), [mobile](evidence/COOKIE_E2E_TIME_BASIS/mobile-three-viewports/summary.json), [cold-start](evidence/COOKIE_E2E_TIME_BASIS/cold-start-recovery/summary.json).

두 core 실행의 public login Max-Age는 모두3599초, reissue는 모두3597초, logout은0이었다. local-demo도 동일 속성 경계와 Max-Age 범위를 통과했다. 이는 그 로컬 실행에서 관측한 상대 수명이며 과거 공개 실패의 지연/시계차를 설명하는 값이 아니다. 실제 브라우저 저장 시각은 server D와 비교하지 않았다. D1==D2, 실제 RT 전송·HttpOnly·삭제 및 logout 후 AT401은 모두 보존·통과했다.

runtime/maintenance source·assets·Functions·제품 설정/build manifest **253파일**의 raw bytes/mode가 기준과 같다. [개별 manifest](evidence/COOKIE_E2E_TIME_BASIS/product-manifest.json)의 before/after aggregate SHA-256은 모두 `954a58caf1cab5cc35e90dd7330985c87792e90bd7aed2edaf3518868afbcc21`이다. 나머지 수정 목록도 테스트/검증/문서로 제한했다. Windows wrapper의 기존 working CRLF/index LF는 Git attribute에 따른 canonical content로 별도 확인했고 변경하지 않았다. HEAD/index·과거 evidence를 보존했다. 새 PNG3개도 직접 시각 확인했으며 사용자 식별·인증정보는 보이지 않는다.

초기 helper43개·타입은 통과했으나 테스트 코드의 control-character regex에 lint1개가 발생했다. ASCII 범위 비교로 교정하고 제어/non-ASCII 음성2개를 추가한 최종45개·lint0/0과 구분해 evidence에 보존했다. private focused wrapper의 경로 canonicalization 준비 오류도 기록했다. 제품 오류 또는 과거 공개 실패의 원인으로 해석하지 않는다.

## 재현

기존 문서대로 정확한 lockfile의 소유 dependency 설치, Java21의 소유 Gradle cache, Playwright에 맞는 소유 Chromium directory를 명시한다. 설치·실제 환경파일 자동 로딩·공급자 요청은 없다.

```sh
python3 -B scripts/verification/cookie_time_basis_checks.py --phase be --focus --cache-seed <owned-cache> --run-label <new-focus>
python3 -B scripts/verification/cookie_time_basis_checks.py --phase be --cache-seed <owned-cache> --run-label <new-be>
python3 -B scripts/verification/cookie_time_basis_checks.py --phase render --cache-seed <owned-cache> --run-label <new-render>
python3 -B scripts/verification/cookie_time_basis_checks.py --phase fe --dependencies <owned-dependencies> --run-label <new-fe>
python3 -B scripts/verification/cookie_time_basis_checks.py --phase browser --cache-seed <owned-cache> --dependencies <owned-dependencies> --browser-path <owned-browser> --run-label <new-core>
python3 -B scripts/verification/mobile_chart_browser.py --phase after --evidence-directory COOKIE_E2E_TIME_BASIS --cache-seed <owned-cache> --dependencies <owned-dependencies> --browser-path <owned-browser> --run-label <new-mobile>
python3 -B scripts/verification/cold_start_browser.py --phase verify --evidence-directory COOKIE_E2E_TIME_BASIS --cache-seed <owned-cache> --dependencies <owned-dependencies> --browser-path <owned-browser> --run-label <new-cold>
python3 -B scripts/verification/cookie_time_basis_checks.py --phase scan --run-label <new-scan>
```

이번 로컬 PASS가 public full E2E 완료를 뜻하지 않는다. 별도 승인 단계에서 commit/push, 필요 시 동일 제품 provenance 재배포, 실제 public full E2E를 진행할 수 있다. 이번에는 이 작업을 수행하지 않았다.

```text
COOKIE_E2E_TIME_BASIS_READY=true
PUBLIC_DEPLOYMENT_READY=false
```

필수 최종 FAIL/BLOCKED/skip0. public full E2E는 다음 별도 단계이며 이번에 실행하지 않았다. 과거 공급자 장애전환 이후 폐기 보장의 미확인 상태도 바꾸지 않았다.
