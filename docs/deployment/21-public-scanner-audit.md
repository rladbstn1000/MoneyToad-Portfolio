# 21. 공개 scanner 전체 감사·정제

이번 감사는 deployment 17~20의 결과와 전체 미커밋 공개 후보를 출발점으로 한다. 제품 기능·보안·timeout·provider 계약은 변경하지 않는다. 실제 공급자·Cloudflare·Render 연결, 자격증명/private state 접근, stage·commit·push·배포는 실행하지 않는다.

## 시작 상태와 검토 범위

HEAD와 index를 고정하고 tracked 357 + untracked 392 = **749개**의 bytes·mode·symlink 여부를 기록했다. 시작 staged 변경은 0이다. 기존 scanner를 수정하지 않은 채 실행해 **FAIL79**를 재현했다. 시작 후보와 원본 분류·과거 evidence는 새 실행 결과로 덮어쓰지 않았다.

| 시작 rule | 후보 |
|---|---:|
| credential-assignment | 38 |
| identity-field | 13 |
| email-marker | 13 |
| cookie-assignment | 6 |
| bearer-marker | 4 |
| authorization-marker | 2 |
| data-dump-candidate | 2 |
| cookie-header | 1 |
| 합계 | 79 |

`candidate-triage.json`은 79개 각각의 위치·rule·source 종류·검토 근거·기존 검토 여부·이동 여부·공개 필요성을 기록한다. 실제 matched value는 싣지 않는다.

| 초기 분류 | 후보 |
|---|---:|
| NON_SECRET_SOURCE_PATTERN | 44 |
| SYNTHETIC_TEST_DATA | 8 |
| LINE_MOVE / STALE_CLASSIFICATION | 27 |
| REAL_SECRET_OR_PII / PUBLIC_EVIDENCE_PROBLEM / UNKNOWN | 각 0 |

기존에 분류돼 있던 현재 79개 source site도 재검토한다. 합성 credential의 고정 literal을 단순히 B로 다시 승인하지 않고 가능한 곳은 runtime canary로 교체한다. 필수 DDL 두 파일의 INSERT는 데이터 dump가 아니라 capacity/lock 고정 singleton 초기화다. DDL과 제품 데이터 계약을 바꾸지 않는다.

## source 정제와 분류 모델

이번 단계의 비문서 변경은 **26파일**이다. source 정제17파일(예제 주석1, BE/FE 테스트9, Python 테스트6, 기존 RED 표시명 연동1), scanner/분류3파일, 새 감사 진입점·그 테스트·evidence 목적지 연결6파일로 구분한다. 삭제/공개 제외 파일은 **0개**다. 기존 누적 변경을 되돌리거나 별도 디렉터리를 광범위하게 제외하지 않았다.

고정 canary는 실행 중 난수로 만들되 JWT 알고리즘·키 길이, TLS keytool/클라이언트 양쪽 일치, gateway canonical encoding, 잘못된 입력의 거절과 원문 비노출 assertion을 유지했다. 제품 **BE107·FE64·Functions2파일과 기존 E2E 소스의 bytes 변경0**, package/lockfile/build 설정 변경0이다.

기존 v1 메타데이터124행을 현재 내용에 대한 strict v2 **148행/149곳**으로 재구성했다. 한 파일의 같은 표현2곳은 출현 횟수2로 묶었다. 현재 통과하던79곳도 재검토했고, 같은 site62곳 유지·나머지17곳은 fixture 등의 정제 후 재검증했다. 이전124행의 오래된/중복 항목45행을 그대로 자동 승인하지 않는다. 개별 최소 construct와 실제 값이 아닌 이유는 [classification-review.json](evidence/PUBLIC_SCANNER_AUDIT/classification-review.json)에 있다.

SQL 두 singleton에 대한 분류는 정확한 경로·statement·전체 파일을 고정한다. 추가 INSERT는 거절하며 SQL의 credential/email/cookie 인접값도 검사한다. 자기 검사용 regex는 전체 grammar fingerprint에 한정한다. 빈 cookie 이름·런타임 변수·reserved invalid-domain fixture 등은 각 의미 predicate를 통과해야 하며 임의 리터럴이나 fallback을 허용하지 않는다.

분류는 정확한 파일·rule·semantic category·파일 전체 digest·해당 표현 digest·최소 구조·출현 횟수에 묶는다. line은 진단 위치이며 승인 키가 아니다. 파일의 다른 내용이 바뀌어도 자동 무효화한다. 누락·중복·stale·잘못된 category는 실패다. scanner가 메타데이터를 자동 갱신하지 않는다.

HARD/SITES/BAD 검사와 evidence 금지 필드/값 규칙은 삭제하거나 완화하지 않는다. evidence 자체는 어느 형식이든 source 분류를 적용할 수 없다. classification은 파일 전체 허용이나 테스트 디렉터리 예외가 아니다. 정확한 construct를 벗어난 실제 값은 여전히 실패해야 한다.

## 문서·evidence·이력

공개 후보의 문서와 예제 설정, 테스트, 검증 도구를 실제 내용으로 확인했다. 예제는 비어 있는 설정 또는 설명용 값이며 실제 provider credential은 없다. PNG 34개는 메타데이터와 중복을 제외한 20개 화면을 직접 검사했다. synthetic 거래명/금액 외에 인증값·사용자 identity·개인 경로가 보이지 않는다. 기존 binary와 과거 실패 자료는 보존했다.

기존 public 7개 commit, reachable blob 399개, 현재 후보를 검사했다. root commit 1개이며 원 팀 repository와 공통 commit 0개다. 공개 repo의 history를 다시 쓰거나 원본 history를 연결하지 않았다. OAuth의 기존 외부 주소는 이미 검토된 별도 경로로 보존하며 이번 demo/provider 연결 근거와 혼동하지 않는다.

시작 전체 미커밋 변경 432개는 intended product 53, verification 123, docs/evidence 256이며 unexpected 0이었다. package/lockfile·mode·symlink·실제 env·private key·font·dump의 예상 밖 추가는 0이다. 로컬 ignored cache는 공개 후보에서 구분했으며 삭제하지 않았다.

## 회귀·최종 판정

| 최종 검증 | 결과 |
|---|---|
| 전체 BE | **721 PASS**: 일반629 + 실제 제품 ConfigData/TLS Render92 |
| 전체 FE | **328 PASS**: OAuth180 + demo148 |
| 제품/test/E2E/Functions 타입 | PASS |
| OAuth/demo build·invalid mode fail-fast | PASS |
| ESLint | **0 errors / 0 warnings** |
| Python 영향 범위 및 scanner/진입점 | **138 PASS**, failure/error/skip0 |
| 독립 scanner 검토 | 단위55 PASS, 별도 조작 입력23개 모두 거절(위 테스트와 중복 합산하지 않음) |
| 실제 Chromium core | **독립2회 각6 PASS** |
| mobile Chromium | **390/768/desktop 3 PASS** |
| cold-start 복구 Chromium | **1 PASS** |
| 외부 앱 요청·소유 자원 정리 | 요청0·cleanup PASS |
| 최종 공개 scanner | **PASS**, HARD/PII/금지 evidence/미분류/stale/예상 밖 artifact0 |

회귀는 이번 작업에서 실제 재실행했다. 최종 BE/FE/Functions/E2E bytes는 테스트한 고정 소스와 같다. scanner와 Python 도구는 마지막 수정 뒤 영향 범위 전체를 다시 실행했다. 로컬 MySQL/Redis/TLS 결과를 실제 TiDB/Upstash 또는 Render/Cloudflare 검증으로 바꾸지 않는다.

독립 검토는 새 분류 구현의 인접 리터럴·임의 함수 인자·fallback·손상 인코딩 evidence 처리 공백을 합성 입력으로 재현했다. 모두 좁은 조건과 음성 테스트로 교정했다. 손상된 텍스트 evidence는 실패하며, 서명·chunk 정책을 확인한 PNG만 압축 픽셀을 텍스트로 해석하지 않는다. PNG의 HARD 전체 bytes·메타데이터·직접 시각 검사는 유지했다. **실제 후보에서 비밀값이 발견된 결과와 이 합성 검사 실패는 별개**다. 최초 준비 경로/권한 오류도 보존했다.

새 browser screenshot3개도 직접 보았다. 이번 전체 공개 후보의 evidence PNG는 기존34 + 신규3이며, 인증값/개인 identity 노출0이다. [시작 PNG 검토](evidence/PUBLIC_SCANNER_AUDIT/binary-visual-review.json)와 [새 PNG 검토](evidence/PUBLIC_SCANNER_AUDIT/new-image-review.json)를 분리한다.

최종 전체 미커밋 **487파일**은 제품53·검증135·문서/evidence299이며 예상 밖0이다. 이번 감사 자체 delta는70파일이고 그중 비문서26파일이다. 최종 전체 미커밋 파일 목록과 이번 단계 delta는 [final-changed-file-inventory.json](evidence/PUBLIC_SCANNER_AUDIT/final-changed-file-inventory.json)에 있다. HEAD/index 보존, staged0, unexpected0, mode/symlink/실제 env/credential 파일 추가0, 과거 evidence bytes 보존을 확인한다. manifest 자신의 digest는 자기참조를 만들지 않도록 포함하지 않으며, 그 파일 내용도 마지막 scanner가 검사한다.

### 재현 진입점

명시적인 기존 로컬 dependency/cache/browser 디렉터리만 사용한다. 설치·환경파일 자동 로딩·공급자 연결은 없다.

- `public_audit_checks.py --phase be|render|fe|browser`에 기존 runner와 같은 명시적 자원 인자를 준다. browser는6-case×2회다.
- `mobile_chart_browser.py --phase after --evidence-directory PUBLIC_SCANNER_AUDIT`와 `cold_start_browser.py --phase verify --evidence-directory PUBLIC_SCANNER_AUDIT`는 각각 별도 실행한다.
- `public_audit_scan.py --run-label <새-label>`은 tracked와 nonignored untracked 후보 **전부**를 정확히 복사해 검사한다. 링크/빈 목록/복사 불일치/누락/동시 변경은 실패하며 기존 결과를 덮어쓰지 않는다.

각 실행 결과는 [새 evidence](evidence/PUBLIC_SCANNER_AUDIT/)에만 저장했다. 시작 FAIL79와 중간 검토 실패는 그대로 남아 있다.

준비 실패와 최종 성공을 분리한다. runtime JWT canary의 초기 키 길이가 기존 HS384 negative fixture의 최소 길이에 못 미쳐 한 번 준비 실패했다. 제품의 알고리즘 거절 assertion에 도달하지 못한 테스트 준비 문제였으며 충분한 runtime key material로 교정하고 동일 테스트12개 PASS를 확인했다. 실패 자료를 보존하고 assertion은 바꾸지 않는다.

공개 scanner PASS는 실제 클라우드 배포 승인이나 미확인 공급자 장애전환 보장을 뜻하지 않는다. 기존 remote 계약 PASS는 17단계까지의 과거 근거이며 이번에 재검증하지 않는다.

```text
PUBLIC_SCANNER_AUDIT_READY=true
PUBLIC_DEPLOYMENT_READY=false
```
