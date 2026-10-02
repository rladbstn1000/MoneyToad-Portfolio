# 24. PATCH 이후 재조회 응답의 요청 시작 순서 검증

기준 main은 `9e52a4ca90838b420fddb750b4c311ea869c06bf`다. 이번 변경은 검증기·테스트·실행 진입점·문서뿐이다. FE 제품, BE, Functions, 정적 자산, 환경·provider 설정, package/lockfile, README는 바꾸지 않았다.

## 원인과 교정

이전 공개 실행에서 Chart에 들어갈 때 시작된 연간 GET이 category PATCH 완료보다 먼저 응답했다. 기존 `waitForResponse`는 요청 시작 시각을 구분하지 않아 이 응답을 수정 후 결과로 검사했다. PATCH 이후 GET도 시작됐지만 완료를 확인하지 못했으므로 당시 제품 재집계 성공·실패는 미확인이다.

`fe/e2e/responseTimeline.ts`는 브라우저 context의 request/response 이벤트에 단조 증가 순서를 부여한다. Request와 Response 객체의 순서는 WeakMap에 기록하고, 공개 근거에는 method·정규화 path·status·phase·requestStartOrder·responseOrder만 남긴다. 실제 객체는 메모리에만 두며 헤더·body·인증값·대상 호스트를 직렬화하지 않는다.

수정 후 응답은 GET·정확한 정규화 경로·200과 `requestStartOrder > patchResponseOrder`를 모두 요구한다. collector는 UI 조작 전에 붙인다. PATCH를 기다리는 동안 이미 완료된 빠른 재조회는 저장된 record에서 찾고, 아직 없으면 유한 timeout으로 후속 event를 기다린다. 이 두 단계 사이에는 비동기 공백이 없다. 조회를 직접 만들거나 응답을 대체하지 않는다. 관측하지 못한 request start를 늦은 response로 추정하지 않는다.

실제 제품의 `transactions` invalidation에 대응하는 연간·선택 월·카테고리 조회를 각각 확인한다. 현재 화면에서 비활성인 예산 조회까지 즉시 발생하도록 강제하지 않는다. 선택된 실제 연간 응답의 총908,000·leaked=false와 별도로 UI 총액·누수0·마트/편의점 분류를 확인한다. 기존 SQL 변경·budget·profile·restore·logout·요청 상한 assertion도 유지한다.

## 합성 재현

지시된 순서인 old GET start → PATCH start → PATCH200 → old GET200 → new GET start → new GET200을 고정했다. old payload는 leaked=true, new payload는 false이므로 잘못 고르면 실패한다. 기존 response-arrival 선택은13개 중3개 FAIL을 재현했다. 교정 뒤14개 모두 PASS이며, 실제 이전 공개 실행처럼 old response가 PATCH200 전에 도착한 경우도 추가했다.

빠른 cached response, 이후 도착 response, 미관측 start 거절, method/path/status 구분, 세 query의 독립 갱신, timeout·dispose·유한 관측량·정제도 검증한다. 관측 record 최대4096, 대기자32, wait 최대60초이며 초과는 실패다. 의도적 순서 재배열은 합성 객체에서 수행했고 실제 Chromium에서는 제품 응답을 지연·mock하지 않고 같은 helper로 관측했다.

[합성 RED/GREEN](evidence/FULL_DEMO_EXPERIENCE/correlation-synthetic-01/summary.json)

## 로컬 결과

| 검증 | 최종 결과 |
|---|---|
| 전체 FE | OAuth239 + demo234 =473 PASS; failure/error/skip/todo0 |
| 타입·build | 제품/test/E2E/Functions 타입, OAuth/demo build, invalid mode 거절 PASS |
| ESLint | 0 errors / 0 warnings |
| 실제 전체 체험 | 390/768/1440 각각1, 총3 PASS |
| 기존 핵심 계약 | 독립2회 각각6 PASS |
| 모바일 | 390/768/desktop 총3 PASS |
| cold-start 수동 복구 | 로컬 준비 fixture1 PASS; 자연 cold 관측 아님 |
| 외부 요청·정리 | 외부 요청0, 자동 재실행0, 소유 자원 정리 PASS |

최종 근거: [FE](evidence/FULL_DEMO_EXPERIENCE/correlation-fe-02/summary.json), [전체 체험](evidence/FULL_DEMO_EXPERIENCE/correlation-full-01/summary.json), [핵심](evidence/FULL_DEMO_EXPERIENCE/correlation-core-01/summary.json), [모바일](evidence/FULL_DEMO_EXPERIENCE/correlation-mobile-01/summary.json), [대기 복구](evidence/FULL_DEMO_EXPERIENCE/correlation-cold-01/summary.json).

실제 전체 체험의 세 viewport 모두 category PATCH responseOrder106 뒤 연간 requestStartOrder107/responseOrder112를 관측했다. 월별·카테고리 요청도108/109로 시작했다. 같은 실행의 실제 payload와 UI, SQL 변경을 함께 통과했다. 이 숫자는 해당 실행의 이벤트 순서이며 시간이나 성능 수치가 아니다.

최초 FE 종합 실행은473개 테스트·E2E/Functions 타입·두 build·lint를 통과했지만 새 fake class의 constructor parameter property가 저장소 `erasableSyntaxOnly`에 맞지 않아 test-types5개 진단으로 실패했다. 설정을 느슨하게 하지 않고 테스트의 일반 필드/생성자 대입으로만 교체했다. helper·E2E spec·기대값은 불변이며 최종 FE 검증으로 타입까지 다시 통과했다. [최초 준비 오류](evidence/FULL_DEMO_EXPERIENCE/correlation-fe-01/summary.json)

제품과 BE 테스트가 불변이므로 BE724 및 Cookie D fixed-clock11개는23단계의 기존 검증 근거를 유지한다. 이번에 전체 BE를 재실행했다고 주장하지 않는다. 쿠키 A/E·실제 폐기·gateway·제한·CORS는 기존 core6×독립2회에서 다시 검증한다. 공급자 계정·원격 DB/Redis 직접 접근은 없다.

strict public scanner는 전체 공개 후보1016파일에서 PASS(미해결0)를 확인했다. 규칙·분류·허용 범위 변경0이며 정확한 Git index/tree도 commit 경계에서 다시 검사한다. [스캔 결과](evidence/FULL_DEMO_EXPERIENCE/correlation-scan-01/summary.json)

## 재실행

기존 Java21·정확한 lock 의존성·전용 Gradle cache·설치된 Chromium을 명시한다. 환경파일을 로딩하지 않고 기존 runner의 소유 자원·finally 정리 경계를 그대로 사용한다.

```sh
python3 -B scripts/verification/post_patch_response_checks.py --phase fe --dependencies <owned-dependencies> --run-label <new-label>
python3 -B scripts/verification/full_demo_browser.py --phase verify --cache-seed <owned-cache> --dependencies <owned-dependencies> --browser-path <owned-browser> --run-label <new-label>
python3 -B scripts/verification/full_demo_checks.py --phase browser --cache-seed <owned-cache> --dependencies <owned-dependencies> --browser-path <owned-browser> --run-label <new-label>
python3 -B scripts/verification/mobile_chart_browser.py --phase after --evidence-directory FULL_DEMO_EXPERIENCE --cache-seed <owned-cache> --dependencies <owned-dependencies> --browser-path <owned-browser> --run-label <new-label>
python3 -B scripts/verification/cold_start_browser.py --phase verify --evidence-directory FULL_DEMO_EXPERIENCE --cache-seed <owned-cache> --dependencies <owned-dependencies> --browser-path <owned-browser> --run-label <new-label>
python3 -B scripts/verification/full_demo_checks.py --phase scan --run-label <new-label>
```

과거 evidence와 보고서는 덮어쓰지 않았다. scanner 규칙·허용 범위·metadata 분류는 변경하지 않는다. stage 전 exact diff·제품 bytes/mode·strict scan을 확인하고 검증/문서 commit만 만든다. 공개 재검증은 이 commit 뒤 동일 SHA 수동 배포와 새 context·신규 방문자 최대1명으로 별도 실행한다. 로컬 PASS를 공개 전체 체험 PASS로 대신하지 않는다. 공개 결과는 최종 응답과 정제된 별도 실행 기록으로 구분한다.

```text
POST_PATCH_RESPONSE_CORRELATION_LOCAL_READY=true
PUBLIC_DEPLOYMENT_READY=false
```
