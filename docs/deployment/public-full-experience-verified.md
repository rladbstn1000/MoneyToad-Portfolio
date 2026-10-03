# 전체 화면 공개 여정 검증 결과

[돈꺼비 공개 데모](https://moneytoad-portfolio.pages.dev/)

2026-10-03, 이미 배포된 제품의 전체 사용자 여정을 실제 Chromium에서 검증했다. 제품·환경·배포를 바꾸지 않고 **실행 1회·신규 방문자 1명·fresh context 1개·자동 retry 0회**로 완료했다. 전체 여정과 검증기의 최종 outcome은 **PASS**다.

## 제품과 검증기 기준

| 구분 | 값 |
| --- | --- |
| 배포 제품 SHA | `d020d837f2830125696d2038eb4a4c969864432e` |
| 검증기 | `public-harness-v2` |
| 원본 source manifest SHA-256 | `5845fdbf57aebdb0e1b3b0491c6782212a9fbd8115c26e51bc45f9a7c2c8358d` |
| 실행 manifest SHA-256 | `988bea24df066c329642d07956eb80973fb13ab6b98400a562029215a718ca63` |
| 브라우저 도구 | Playwright 1.63.0 / Chromium 153.0.8010.12 |

실행본은 원본 v2 정책·assertion을 유지하고 곳간 P01~P14 관측을 추가했다. 이 checkpoint는 전체 여정의 기존 assertion을 기록한 것이며 과거 집중 진단과 검증 범위가 같다는 뜻은 아니다. 실제 실행 중 제품·검증 기대값·환경 변경은 없었다. 위 checksum은 검증기 출처 식별용이며, private 실행기나 원문 실행 자료는 이 문서에 포함하지 않는다.

## 화면별 결과

| 화면 / 흐름 | 실제 결과 |
| --- | --- |
| 마당·체험 시작 | 이야기 4개, readiness·anonymous, 명시적 login 201 → session 200 → API 기준월 장독대 진입 PASS. 자동 login 0 |
| 장독대 | 카페 소비 58,000원·예산 40,000원·누수 18,000원. 예산 40,000→60,000→40,000의 실제 PATCH 2회 모두 200, 누수 0→18,000 복원 PASS. 예산 없는 6개 항목의 PATCH 0 |
| 씀씀이(Chart) | 카페→마트 / 편의점 category PATCH 1회 200. 총 소비 908,000원 유지·누수 0·연간 leaked=false, 월·카테고리 재조회 PASS |
| 장독대 재집계 | 재분류 후 카페 28,000원·마트 120,000원·누수 0, 이전 누수 18,000원 표시 없음 |
| 두꺼비의 조언 | 현재월 오래된 카페 과소비 조언 없음. 기준월 5개월 전 문화생활 소비 180,000원·예산 60,000원·누수 120,000원과 분석 카드·modal·comment·Escape·focus 복귀 PASS. 샘플 분석 안내 유지 |
| 콩쥐의 곳간 | 390px P01~P14 전체 PASS. 문→콩쥐→종이·편집·취소·저장·닫기·재열기·선택값 유지 PASS |
| 정보 입력 과정 | intro→gender→age→sample card 후 장독대 복귀 PASS. 실제 카드번호/CVC input, card API, user PATCH 0 |
| 화면 크기 | 390×844 / 768×1024 / 1440×1000 각각 6페이지 이동·핵심 조작·body 가로 overflow 0 PASS |
| 새로고침 | 추가 login 0, reissue 200→session 200, 실제 예산/category 저장 유지, 메모리 프로필 초기화 PASS |
| 체험 종료 | UI logout 204, cookie 제거·anonymous·보호 화면 차단·폐기 전 접근 토큰으로 보호 API 401 PASS |
| 직접 backend 접근 | gateway 없는 보호 GET 403, 내부 gateway/client IP header 브라우저 노출 0 |

장독대·Chart는 방문자별 합성 데이터를 실제 API로 조회·수정했다. 조언은 실제 소비 수치와 준비된 샘플 분석 문구이며 실시간 AI 호출이 아니다. 곳간·정보 입력의 샘플 인물·카드 선택은 방문별 메모리에서만 유지하며 실제 개인정보·금융정보를 입력하거나 저장하지 않는다.

## PATCH 이후 요청 시작 순서

PATCH 응답보다 뒤에 **시작된 GET**과 그 응답만 후속 재조회로 인정했다. 관측을 위한 추가 fetch는 없었다.

| 조회 | PATCH 응답 순번 | GET 시작 순번 | GET 응답 순번 / 상태 |
| --- | ---: | ---: | --- |
| annual | 138 | 139 | 144 / 200 |
| monthly | 138 | 140 | 142 / 200 |
| categories | 138 | 141 | 143 / 200 |

`CATEGORY_PATCH_HTTP`, `POST_PATCH_ANNUAL_REQUEST_OBSERVED`, `POST_PATCH_ANNUAL_RESPONSE_OBSERVED` 모두 PASS다. 해당 annual payload의 total 908000 / leaked=false와 화면의 총 소비 908,000원 / 누수 0 / 마트 category를 함께 확인했다.

## Cookie A / D / E

- **A — 이번 실제 응답 PASS:** login/reissue/logout의 Max-Age는 각각 3596/3521/0초였다. Secure·HttpOnly·SameSite=Lax·Path=/api/auth/demo·Domain 미지정 유지, JavaScript에서 cookie 접근 불가, 실제 reissue 전송, logout 후 cookie jar 제거를 확인했다.
- **D — 기존 근거 유지:** [고정 시계 검증](22-cookie-e2e-time-basis.md)의 기존 결과다. 이번 공개 실행에서 다시 수행하지 않았다.
- **E — 이번 실제 응답 PASS:** reissue 전후 session 절대 만료 시각이 동일했다. browser/server 절대 expiry를 직접 비교하지 않았다.

## 요청 경계·집계·정리

- login 1 / 예산 PATCH 2 / category PATCH 1 / logout 1. 정상 restore reissue는 초기 무쿠키 401, reload 200, 종료 뒤 보호 경로 재진입 401의 3회다.
- 허용 읽기 79건 / 유한 상한 481건. `externalRequests=0`, `unauthorizedRequests=0`, `readBudgetExceeded=0`, `mutationLimitExceeded=0`.
- SSAFY OAuth / 외부 AI / peer / card API / demo user PATCH 요청 0.
- 본문 assertion·request guard·afterEach·관측·screenshot/result 저장·reporter·worker/global·unknown global·maxFailures 알림·cleanup 오류 모두 0.
- onEnd `passed`, 실제 process exit `0`, 최종 outcome **PASS**. 소유 browser/process 종료와 임시 실행 자원 정리 **PASS**.

## 한계와 최종 상태

이번 공개 실행에서 전체 BE/FE 단위 테스트를 다시 돌리지 않았으며 과거 단계의 테스트 수를 합산하지 않는다. 자연 cold-start는 **NOT_OBSERVED**이고 공급자 장애전환 이후 세션 폐기 보장은 기존 미확인 상태다. 이번 PASS로 이전 public Mypage 실패 원인을 소급 확정하지 않는다. 단일 방문자·단일 탭 검증이며 상시 가용성이나 부하 성능을 보장하지 않는다.

```text
HISTORICAL_PUBLIC_MYPAGE_FAILURE_CAUSE=UNKNOWN
FULL_DEMO_EXPERIENCE_PUBLIC_READY=true
PUBLIC_DEPLOYMENT_READY=true
```

이 문서를 공개하는 작업은 문서 마무리만 수행한다. 제품 배포 SHA는 위 값을 유지하며, 문서 커밋으로 GitHub HEAD가 달라져도 재배포하지 않는다.
