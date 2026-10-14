# 백엔드 안전 보강 — 부분 복구 및 누락분 재구성 후 재검증

작성일: 2026-10-14. 기준 커밋: `40cf4bab33c9407659be8e48493103e24a6c2ebd`.

**최종 고정 후보의 필수 로컬 회귀는 PASS다. 아래 새 실행 결과만 근거로 사용한다. 최종 보관 묶음은 별도 추출·재구성 대조 기록으로 확인한다.**

이 문서는 사라진 과거 보고서를 되찾은 것이 아니다. 남아 있던 중간 BE 9개와 FE 검증 사본 4개를 보존한 뒤, 누락된 CVC 백엔드·후행 JSON 경계를 새로 구현하고 새 실행으로 검증하는 기록이다. 과거 BE 732·FE 581은 새 후보의 PASS 근거로 사용하지 않는다. 삭제 주체·시점·과거 최종 후행 JSON 구현은 여전히 미확인이다.

## 보존과 출처

원본 RECOVERY-STATUS와 13개 소스는 고정 프로젝트의 preserved-original에 원문 그대로 보존했다. 상대 경로·크기·SHA-256·mode를 기록했고, Documents에 독립 원본 ZIP을 만든 뒤 빈 폴더에 풀어 파일 수와 checksum 일치를 확인했다. 기존 사용자 저장소 대신 기준 커밋의 독립 사본에서 작업한다.

최종 후보에서 보존본 그대로인 것은 BE 7개와 FE 4개다. 거래 분류 통합 테스트는 합성 fixture와 실제 HTTP 검사를 보강했다. DemoChartSeedHttpIntegrationTest는 새 CVC 응답 계약을 반영했다. CVC BE·신규 JSON 설정·신규 테스트·runner는 새 재구성이다. 원본 보고서·최종 BE 원문·과거 전체 결과 원문을 복구했다고 주장하지 않는다.

## 거래 분류

- 실제 FE의 13개 선택지를 허용하며 보험/세금의 기존 누수 제외 의미는 유지한다.
- 소유한 정상 입력 200, 타인/미존재 유효 입력의 동일한 일반 404, 미인증 401, null/누락/빈값/공백/미지원 및 잘못된 JSON 400을 검사한다.
- null/빈 legacy 분류를 기타로 합산한다. 금액을 버리거나 DB를 수정하지 않는다. 월 거래·카테고리·예산·연간 합계와 다른 방문자의 모든 열을 비교한다.
- 후행 JSON 거절은 UpdateCategoryRequest 전용 mapper에 적용한다. 전체 RuntimeException/IllegalArgumentException을 광역 400/404로 처리하지 않는다.
- 소유권보다 입력 검증이 먼저다. 잘못된 입력은 거래 존재 여부와 무관하게 400이며, 404 동일성은 유효한 본문으로 검증했다.

## CVC

- 복구 FE 원문의 OAuth 입력·state 제거와 cardNo-only 요청 투영을 재사용한다. demo 함수 본문은 기준 버전과 같다.
- CardRequest는 cvc 필드명을 발견하면 값 token을 읽기 전에 거절한다. null도 허용하지 않는다. 본문 오류는 고정 문구이며 원문/cause를 반환하거나 기록하지 않는다.
- legacy 컬럼은 내부 read-only JPA 매핑으로만 남긴다. ORM INSERT/UPDATE에서 제외하고 공개 getter·응답 속성을 제거한다. 빈 문자열도 존재하는 legacy 값으로 판단한다.
- seed에는 실제 엔티티 존재 여부를 전달한다. 정리 도구는 `(cvc IS NOT NULL)`만 조회하고, 읽기 전용 경계의 정확한 SQL 한 항목을 교체했다. 허용 메서드/쓰기 권한은 늘리지 않았다. 합성 JDBC도 같은 의미를 구현한다.
- 실제 OAuth 설정·JWT·MVC·JPA·CardService·CsvService를 사용한다. DummyService/CsvClient/AnalysisJobService의 후속 외부 작업만 경계 대역이다. 거절 후 신선한 JDBC 조회와 호출 관측으로 저장·후속 작업 0을 확인한다.
- 신규 CVC HTTP 응답·엔티티 직렬화·정상 로그 비노출, legacy 값이 카드 수정 후 그대로 남는지, seed/cleanup의 legacy 거절을 검증한다.

**신규 수집·쓰기·반환 중단과 과거 값 삭제는 다르다. 기존 실제 DB 값/컬럼 삭제는 미실행이다.** 전체 카드번호 원문 취급과 기존 OAuth 구조는 이번 범위 밖에 남는다. 모든 금융정보 처리가 안전해졌다고 확대 해석하지 않는다.

## 새 실행 기록

- 거래 분류 첫 focus: 53개 중 1실패. 요청 처리 불가능한 Spring Data projection converter까지 strict mapper를 요구한 관측 오류였다. 실행 도중 테스트 서버 finally 보완이 들어가 소스 불변성도 실패했다. 이 결과를 보존했다.
- 거래 분류 재실행: 53 PASS, 실패/오류/skip 0, 소스 불변·소유 자원 정리 PASS. 이후 CVC 적용 전 별도 소스 체크포인트를 보관했다.
- CVC 첫 focus: 59개 중 1실패. 신규 CVC 6개는 PASS였으나 DRY_RUN의 exact SQL guard와 바뀐 존재 여부 조회가 불일치했다. 실패를 보존하고 guard/fixture 및 회귀 2개를 보완했다.
- CVC 및 관련 회귀 재실행: 104 PASS, 실패/오류/skip 0, 소스 불변·소유 자원 정리 PASS.
- 첫 전체 FE 실행: 581개·타입·lint는 통과했으나 의존성 메타데이터 보존 확인에서 실패했다. 전체 PASS가 아니다. 프로세스 원인은 미확인이고 보존 검사를 완화하지 않는다.
- FE 재실행은 파일 내용·권한·링크가 같은 별도 의존성 사본을 사용했다. 기존 보존 검사를 유지했고 최종 PASS다. 첫 의존성 위치에서는 실행 종료 후에도 폴더 수정 시각 변화가 관측됐으나 작성 주체는 UNKNOWN이다.

| 최종 후보 검증 | 실제 결과 | 새 근거 |
| --- | --- | --- |
| BE 전체 일반 회귀·test compile | 645 PASS, failure/error/skip 0 | [BE](evidence/BACKEND_SAFETY_RECOVERY/be-final-01/summary.json) |
| Render 설정 및 실제 로컬 TLS | 86 + 6 PASS, failure/error/skip 0 | [Render/TLS](evidence/BACKEND_SAFETY_RECOVERY/render-final-01/summary.json) |
| FE OAuth / remote / local | 275 / 245 / 61 PASS, 합계581, failed/skip/todo 0 | [FE](evidence/BACKEND_SAFETY_RECOVERY/frontend-final-02/summary.json) |
| 제품·테스트·E2E·Functions 타입 | 모두 PASS | 위 FE 결과 |
| OAuth / remote / local build·invalid mode | 모두 PASS | 위 FE 결과 |
| lint | 0 errors / 0 warnings | 위 FE 결과 |
| 정적 Chromium | 16 PASS, API/외부 요청/페이지 오류 0 | [브라우저](evidence/BACKEND_SAFETY_RECOVERY/static-browser-final-01/summary.json), [요청 경계](evidence/BACKEND_SAFETY_RECOVERY/static-browser-final-01/network-summary.json) |
| 서버 Chromium | 독립2회 각각6 PASS, retries0, 외부 요청0 | [서버 브라우저](evidence/BACKEND_SAFETY_RECOVERY/server-browser-final-01/summary.json) |
| 소유 자원 정리·고정 소스 불변 | 최종 모든 실행 PASS | 각 결과의 cleanup 및 source manifest |
| strict scanner 회귀 | 기존55 PASS | [최종 공개·링크 검사](evidence/BACKEND_SAFETY_RECOVERY/final-audit.json) |

이번 BE의 서로 분리된 일반645·설정86·TLS6을 합한 전체는 **737**이다. 신규 JUnit 사례는 거래분류5·CVC6·정리경계2다. FE는 복구 테스트8개를 포함해581이다. 표적104개나 과거 결과를 더해 총수를 부풀리지 않는다.

[최종 결과 요약](evidence/BACKEND_SAFETY_RECOVERY/final-summary.json)과 [검증 소스 manifest](evidence/BACKEND_SAFETY_RECOVERY/final-verified-source-manifest.json)를 연결했다. 검증 대상 BE/FE/scripts 515파일의 manifest digest는 `d34be787a22a523f3e73298009620faa9027ac2d55eeed80a8ca8903ca7f1ae8`이다. 테스트 뒤 제품/테스트/실행기 변경은 없으며 후속 문서·정제 결과만 추가했다.

일반 상태/부작용 검사는 실제 JWT·JPA·MySQL을 사용하는 MockMvc 경계다. 후행 JSON은 실제 loopback HTTP로 거래분류14거절+정상1, 카드28거절+정상2를 별도 검사했다. CVC 필드 null/스칼라/구조 입력14건은 실제 제품 MVC 역직렬화와 DB/외부 경계 호출 수로 확인했다. 제품 API 응답 mock으로 브라우저를 통과시키지 않았다.

Render 결과는 소유 로컬 컨테이너의 설정/TLS 검사이며 실제 Render/TiDB/Upstash 성능 또는 이번 후보의 공급자 PASS가 아니다. 필수 로컬 검사 미실행은 없고, 실제 공급자·공개사이트·운영 값 삭제·배포는 승인 범위 밖이므로 미실행이다.

재실행은 새 evidence label과 기존 전용 cache/dependencies/browser 경로를 명시한다. 실제 비밀값·환경 파일을 로딩하지 않는다.

```sh
python3 -B scripts/verification/backend_safety_recovery.py --phase be --cache-seed <owned-cache> --run-label <new-label>
python3 -B scripts/verification/backend_safety_recovery.py --phase render --cache-seed <owned-cache> --run-label <new-label>
python3 -B scripts/verification/backend_safety_recovery.py --phase browser --cache-seed <owned-cache> --dependencies <exact-lockfile-install> --browser-path <installed-chromium> --run-label <new-label>
python3 -B scripts/verification/local_demo_checks.py --phase fe --dependencies <exact-lockfile-install> --output <new-output>
python3 -B scripts/verification/local_demo_checks.py --phase browser --dependencies <exact-lockfile-install> --browser-path <installed-chromium> --output <new-output>
```

Java21.0.11·Gradle8.14.3·Node22.14.0·MySQL8.4와 현재 lockfile을 사용했다. Docker runtime Java는 실행 결과의21.0.12.1로 구분한다. 의존성 offline 설치는 준비 실패했고, 공식 registry의 고정 lockfile 설치 후 실행했다. 이는 애플리케이션 외부 API 접속과 구분한다.

## 파일별 출처

새 실행 결과와 이 보고서는 새로 작성한 자료다. 기존 분류 메타데이터는 내용이 같은 이미 검토된 구문3개의 파일 해시·줄 위치만 갱신했다. scanner 규칙·분류 구문·허용 범위 확대는 없다.

| 파일 | 출처 |
| --- | --- |
| [be/src/main/java/com/potg/don/budget/controller/BudgetController.java](../../be/src/main/java/com/potg/don/budget/controller/BudgetController.java) | 남은 원문 그대로 복구 |
| [be/src/main/java/com/potg/don/card/dto/request/CardRequest.java](../../be/src/main/java/com/potg/don/card/dto/request/CardRequest.java) | 신규 재구성(기준 코드에 새 변경) |
| [be/src/main/java/com/potg/don/card/dto/response/CardResponse.java](../../be/src/main/java/com/potg/don/card/dto/response/CardResponse.java) | 신규 재구성(기준 코드에 새 변경) |
| [be/src/main/java/com/potg/don/card/entity/Card.java](../../be/src/main/java/com/potg/don/card/entity/Card.java) | 신규 재구성(기준 코드에 새 변경) |
| [be/src/main/java/com/potg/don/demo/seed/DemoDatasetValidator.java](../../be/src/main/java/com/potg/don/demo/seed/DemoDatasetValidator.java) | 신규 재구성(기준 코드에 새 변경) |
| [be/src/main/java/com/potg/don/demo/seed/DemoSeedService.java](../../be/src/main/java/com/potg/don/demo/seed/DemoSeedService.java) | 신규 재구성(기준 코드에 새 변경) |
| [be/src/main/java/com/potg/don/exception/GlobalExceptionHandler.java](../../be/src/main/java/com/potg/don/exception/GlobalExceptionHandler.java) | 남은 원문 그대로 복구 |
| [be/src/main/java/com/potg/don/global/config/JsonRequestConfiguration.java](../../be/src/main/java/com/potg/don/global/config/JsonRequestConfiguration.java) | 신규 재구성(기준 코드에 새 변경) |
| [be/src/main/java/com/potg/don/transaction/TransactionCategories.java](../../be/src/main/java/com/potg/don/transaction/TransactionCategories.java) | 남은 원문 그대로 복구 |
| [be/src/main/java/com/potg/don/transaction/dto/request/UpdateCategoryRequest.java](../../be/src/main/java/com/potg/don/transaction/dto/request/UpdateCategoryRequest.java) | 남은 원문 그대로 복구 |
| [be/src/main/java/com/potg/don/transaction/dto/response/TransactionResponse.java](../../be/src/main/java/com/potg/don/transaction/dto/response/TransactionResponse.java) | 남은 원문 그대로 복구 |
| [be/src/main/java/com/potg/don/transaction/exception/InvalidTransactionCategoryException.java](../../be/src/main/java/com/potg/don/transaction/exception/InvalidTransactionCategoryException.java) | 남은 원문 그대로 복구 |
| [be/src/main/java/com/potg/don/transaction/service/TransactionService.java](../../be/src/main/java/com/potg/don/transaction/service/TransactionService.java) | 남은 원문 그대로 복구 |
| [be/src/maintenance/java/com/potg/don/maintenance/CleanupReadOnlyGuard.java](../../be/src/maintenance/java/com/potg/don/maintenance/CleanupReadOnlyGuard.java) | 신규 재구성(기준 코드에 새 변경) |
| [be/src/maintenance/java/com/potg/don/maintenance/DemoCleanupService.java](../../be/src/maintenance/java/com/potg/don/maintenance/DemoCleanupService.java) | 신규 재구성(기준 코드에 새 변경) |
| [be/src/test/java/com/potg/don/auth/CardCvcContractIntegrationTest.java](../../be/src/test/java/com/potg/don/auth/CardCvcContractIntegrationTest.java) | 신규 재구성(기준 코드에 새 변경) |
| [be/src/test/java/com/potg/don/auth/DemoChartSeedHttpIntegrationTest.java](../../be/src/test/java/com/potg/don/auth/DemoChartSeedHttpIntegrationTest.java) | 중간본을 바탕으로 수정 |
| [be/src/test/java/com/potg/don/auth/TransactionCategoryContractIntegrationTest.java](../../be/src/test/java/com/potg/don/auth/TransactionCategoryContractIntegrationTest.java) | 중간본을 바탕으로 수정 |
| [be/src/test/java/com/potg/don/card/controller/CardControllerCsvContractTest.java](../../be/src/test/java/com/potg/don/card/controller/CardControllerCsvContractTest.java) | 신규 재구성(기준 코드에 새 변경) |
| [be/src/test/java/com/potg/don/demo/seed/DemoDatasetValidatorTest.java](../../be/src/test/java/com/potg/don/demo/seed/DemoDatasetValidatorTest.java) | 신규 재구성(기준 코드에 새 변경) |
| [be/src/test/java/com/potg/don/maintenance/CleanupReadOnlyGuardTest.java](../../be/src/test/java/com/potg/don/maintenance/CleanupReadOnlyGuardTest.java) | 신규 재구성(기준 코드에 새 변경) |
| [be/src/test/java/com/potg/verification/cleanupremote/CleanupSyntheticJdbc.java](../../be/src/test/java/com/potg/verification/cleanupremote/CleanupSyntheticJdbc.java) | 신규 재구성(기준 코드에 새 변경) |
| [be/src/test/java/com/potg/verification/cleanupremote/CleanupTiDbProbeSafetyTest.java](../../be/src/test/java/com/potg/verification/cleanupremote/CleanupTiDbProbeSafetyTest.java) | 신규 재구성(기준 코드에 새 변경) |
| [fe/src/api/services/cards.ts](../../fe/src/api/services/cards.ts) | 남은 원문 그대로 복구 |
| [fe/src/pages/Mypage.tsx](../../fe/src/pages/Mypage.tsx) | 남은 원문 그대로 복구 |
| [fe/src/pages/UserInfoInputPage.tsx](../../fe/src/pages/UserInfoInputPage.tsx) | 남은 원문 그대로 복구 |
| [fe/tests/oauthCardSafety.test.tsx](../../fe/tests/oauthCardSafety.test.tsx) | 남은 원문 그대로 복구 |
| [scripts/verification/backend_safety_recovery.py](../../scripts/verification/backend_safety_recovery.py) | 신규 재구성(기준 코드에 새 변경) |
| [scripts/verification/fixtures/scan-classifications.json](../../scripts/verification/fixtures/scan-classifications.json) | 기존 동일 구문의 파일 fingerprint 갱신만 |

## 정리 경계와 한계

제품·보고서·원문·복구 묶음은 finally/cleanup 삭제 대상에서 제외한다. 실행기가 소유한 임시 DB·Redis·브라우저·프로세스·검증 사본만 정리한다. 실제 공급자·운영 DB·공개 사이트에는 접속하지 않는다. 의존성 준비는 공식 registry의 기존 lockfile과 MySQL 이미지에 한정했다. 기존 이력·원본 자격증명·공개 배포·과거 evidence는 보존한다.

commit·push·재배포·실제 legacy 데이터 삭제는 하지 않았다. 새 로컬 후보의 성공을 현재 공개 서비스의 새 배포로 표현하지 않는다.
