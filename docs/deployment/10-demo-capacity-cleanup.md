# Demo 수용 상한·방문 출처·관리자 SQL 정리

2026-10-01. 승인된 LOCAL IMPLEMENTATION이다. 기존 미커밋 배포 준비 변경 위에서 작업했으며 실제 TiDB/Upstash 접속, 접속정보/private provider state 읽기, 원격 예약 변경, 클라우드 생성, stage/commit/push/배포는 하지 않았다. 과거 공급자 기능 PASS는 새 admission/cleanup의 원격 PASS를 뜻하지 않는다.

## 구현과 적용 경계

- `DemoAuthService`의 기존 REQUIRES_NEW 트랜잭션에 demo 전용 `DemoAdmissionStore`를 연결했다. JDBC는 기존 JpaTransactionManager/DataSource에 바인딩된 같은 연결을 사용한다.
- `DemoAdmissionConfiguration`은 demo에서만 스키마·counter를 확인한다. 일반 OAuth는 새 두 테이블이나 bean을 요구하지 않는다.
- `DemoDatasetValidator`는 seed의 순수 비교 로직을 추출했다. 재설치·DB 변경 없이 seed/정리에서 같은 V1 불변조건을 사용한다.
- 관리 도구는 `be/src/maintenance`의 별도 source set과 `demoCleanupJar`다. 일반 runtime JAR에는 maintenance 클래스가 없고, CLI는 Spring/JPA/Redis/server를 시작하지 않는다.
- FE는 **login token POST의 정확한 503 코드** FULL/BUSY만 구분한다. session/reissue/ready에서 같은 문자열을 받아도 login 거절로 추측하지 않는다. 자동 POST retry·자동 새 login은 없다.

## 스키마와 신규 설치

기존 `scripts/verification/fixtures/managed-provider-schema.sql`의 7-table DDL은 보존했다. 새 `be/src/main/resources/db/demo/V001__demo_admission.sql`만 추가한다.

|테이블|구조|
|---|---|
|demo_capacity|id TINYINT PK, resident_count BIGINT, max_visitors INT; 모두 NOT NULL; 초기 한 행 (1,0,1000)|
|demo_visit|user_id BIGINT PK/FK users(id), created_at DATETIME(6), scenario_version VARCHAR(16), session_expires_at DATETIME(6); 모두 NOT NULL; (created_at,user_id) 인덱스; FK UPDATE/DELETE RESTRICT|

관리자가 **비어 있는 전용 demo schema**에 ① 기존 7-table baseline ② 새 migration ③ 분리 계정 grant를 적용한 뒤 runtime을 `ddl-auto=validate`로 시작한다. migration은 자동 기동 작업이 아니며 IF NOT EXISTS로 불완전 설치를 숨기지 않는다. 기존 데이터에 marker를 생성하거나 count를 자동 수정하지 않는다. 미표시 legacy User를 정리 대상으로 편입하지 않는다.

`DEMO_MAX_VISITORS` 기본 1000은 DB 값의 기대값이다. DB max를 설정값으로 덮어쓰지 않는다. 상한 변경은 트래픽/정리를 중단하고 관리자가 별도 검토해야 한다. OAuth 권한·기능은 바꾸지 않는다.

시작 검사는 InnoDB, 정확한 열/type/nullability/PK/FK/non-cascade/index, DATETIME(6), singleton id=1, max 일치, 0≤count≤max, count=COUNT(demo_visit)를 확인한다. 정상 marker의 created_at은 DB UTC_TIMESTAMP(6), 만료 시각은 실제 발급한 JWT 절대 만료의 UTC 값이다. 무결성 불일치는 fail-closed이고 자동 복구는 없다.

## 로그인 트랜잭션·오류

cookie 사전 검사 → writable 바인딩 트랜잭션/READ_COMMITTED 확인 → capacity id=1 FOR UPDATE NOWAIT → singleton/max/count/marker 확인 → 조건부 count+1 → User → seed → Redis session → V1 marker → 최종 count=marker → SQL commit → token/cookie 반환 순서다. TiDB 대상에서는 pessimistic transaction mode도 읽어 확인하며 설정을 자동 변경하지 않는다.

capacity 잠금은 seed/Redis 처리 동안 유지한다. 작은 demo의 확실한 저장 상한을 위해 로그인 직렬화와 즉시 BUSY를 선택했다. 별도 connection에서 counter를 예약/환급하지 않는다. reissue/session/logout/Redis TTL은 SQL count를 줄이지 않는다. `/ready`는 공간이 가득 차도 기존 방문자의 복원·조회·logout을 막지 않는다.

|상황|HTTP|효과|
|---|---|---|
|정상 상한 도달|503 DEMO_CAPACITY_FULL|새 SQL/Redis/토큰 0, FE 공간 부족 안내|
|명시적 NOWAIT 획득 실패|503 DEMO_ADMISSION_BUSY|새 SQL/Redis/토큰 0, FE 다른 체험 준비 안내|
|admission schema/config/integrity/SQL 문제|503 DEMO_ADMISSION_UNAVAILABLE|인증 성공 금지, 일반 unavailable/복원 유지|
|기타 기존 인증/구현 오류|기존 401/503/500 계약|기존 분류 유지|

BUSY는 lock query에서 실제 vendor 3572 + SQLState HY000가 확인될 때만 사용한다. 원인/next-exception chain의 deadlock·connection·다른 오류는 BUSY로 숨기지 않는다. 메시지 문자열로 판단하지 않는다. 응답은 no-store이며 SQL/식별값/credential을 포함하지 않는다.

|실패 지점|SQL/counter|Redis/응답|
|---|---|---|
|User save 또는 seed|전체 rollback|session 생성 0|
|Redis create 실패|전체 rollback|토큰 0; 불명확 sid 추측 금지|
|marker 또는 최종 무결성|전체 rollback|알려진 sid best-effort revoke, 토큰 0|
|확정 commit 실패|전체 rollback|알려진 sid revoke, 토큰 0|
|commit 성공 뒤 응답 유실|dataset/marker/count가 남을 수 있음|알려진 sid revoke, 토큰 0; count 별도 환급/재시도 금지|
|revoke 자체 실패|원래 SQL 결과 보존|실패 유지, 성공으로 추정하지 않음|

## 정리 안전 계약

기본 dry-run과 `--verify`는 DML 0이다. apply는 새 READ_COMMITTED 트랜잭션에서 capacity NOWAIT 잠금 후 무결성을 다시 확인한다. DB UTC를 한 번 고정하고 `created_at <= now-24h AND session_expires_at < now`를 만족하는 marker만 오래된 순(created_at,user_id)으로 다시 선택한다. 이전 dry-run 결과를 재사용하지 않는다.

선택한 marker/User/Card/Transaction/Budget 행을 NOWAIT로 잠근 뒤 **전체 batch 검증이 끝나야** 삭제한다. 필수 조건은 다음과 같다.

- marker와 User 존재, V1, Card 정확히 1 및 cardNo/cvc null.
- 거래 240개의 immutable 시간·금액·상호 형태가 V1과 일치.
- Budget 72개의 날짜/category 슬롯 완전·중복 0, prediction/source 필드 null.
- User fileId null, AnalysisJob 0, 예상 밖 테이블/관계·금융 필드 거절.
- email/name prefix, age/gender, 거래 category, Budget amount/override/overriddenAt은 원본 일치를 요구하지 않는다.

unsafe 한 건이면 batch 전체 삭제/count 변경 0이다. skip/force/이메일 추측/임의 User 지정은 없다. 확보한 정확한 PK로 Transaction→Budget→Card→demo_visit→User 순서로 삭제하고 테이블별 affected rows를 검사한다. 기존 count 일치 및 count≥삭제수 조건으로 차감하고, commit 전 count=marker를 재확인한다. 중간 오류·row count 불일치·underflow·post-condition 오류는 rollback한다.

commit 응답 유실은 UNKNOWN이며 자동 재시도하지 않는다. 별도 `--verify`로 count/marker를 확인한 뒤 운영자가 다음 행동을 판단한다. verify는 전체 dataset을 복구하거나 과거 commit 결과를 단독으로 확정하는 명령이 아니다.

## 관리자 CLI 사용

Java 21, 기존 lock된 Gradle 의존성을 사용한다. `be`에서 `bash ./gradlew demoCleanupJar`로 `build/libs/be-0.0.1-SNAPSHOT-demo-cleanup.jar`를 만든다.

```sh
java -jar build/libs/be-0.0.1-SNAPSHOT-demo-cleanup.jar --config-file "$CLEANUP_CONFIG" --schema "$DEMO_SCHEMA"
java -jar build/libs/be-0.0.1-SNAPSHOT-demo-cleanup.jar --config-file "$CLEANUP_CONFIG" --schema "$DEMO_SCHEMA" --verify
java -jar build/libs/be-0.0.1-SNAPSHOT-demo-cleanup.jar --config-file "$CLEANUP_CONFIG" --schema "$DEMO_SCHEMA" --apply --batch-size 10
```

위 변수는 **운영자가 선택한 경로/schema 이름**이다. 이번 작업에서는 원격 설정 파일을 만들거나 읽지 않았다. credential 값은 인자/env/로그 대신 저장소 밖 전용 파일에서만 읽는다. 파일에는 정확히 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DEMO_MAX_VISITORS` 네 키를 쓴다. shell source/eval은 사용하지 않는다.

파일의 canonical 절대 경로, 현재 사용자 소유 디렉터리 700/regular file 600, 링크 수 1, 경로 중 symlink 없음, 저장소 밖 조건을 검사한다. URL은 정확한 schema와 `sslMode=VERIFY_IDENTITY`, 명시적 connectTimeout(1–5000ms), socketTimeout(1–10000ms)를 요구한다. credential URL 내장·unknown/duplicate 옵션·TLS 완화는 거절한다. 필요한 신뢰 CA는 JVM trust store로 제공하고 검증을 끄지 않는다.

batch 기본 10, 범위 1–100. dry-run 기본/verify/apply는 배타적이다. --force/임의 User/email/24시간 예외는 없다. 결과는 고정 상태와 count만 출력하며 exit 0=PASS, 2=FAIL, 3=UNKNOWN이다. 실패 원문·대상 식별값은 출력하지 않는다.

## 계정 권한과 운영 전제

|계정|허용|금지|
|---|---|---|
|public-demo runtime|필요한 9테이블 SELECT; users/cards/transactions/budgets/demo_visit INSERT; users/transactions/budgets 업무 UPDATE; demo_capacity UPDATE(resident_count)|DELETE, DDL, max 변경, AnalysisJob/Peer/Dummy 쓰기|
|cleanup|필요한 SELECT; users/cards/transactions/budgets/demo_visit DELETE; demo_capacity UPDATE(resident_count)|INSERT, DDL, max 변경, Job 쓰기|
|migration admin|명시적 DDL/초기행/grant|runtime/FE에 credential 전달|

GRANT는 해당 전용 schema·표의 테이블/열로 한정한다. schema 이름에 와일드카드 문자가 있으면 GRANT의 schema 패턴 escaping도 확인한다. cleanup credential은 Render runtime·FE·repo에 넣지 않는다.

**정리 계정의 메타데이터 가시성은 전체 서버 권한이 아니다.** 도구는 보이는 incoming FK(다른 schema가 현재 테이블을 참조하는 경우 포함)를 거절한다. MySQL 정보 스키마는 권한 없는 child table의 FK를 숨길 수 있다. 따라서 migration admin이 전용 schema에 외부 FK/공유 데이터가 없음을 보장하고 정리 중 DDL·관리자 쓰기를 금지해야 한다. 숨겨진 타 schema까지 검사했다고 주장하지 않는다. AnalysisJob에는 기존 FK가 없어 gap lock으로 보호된다고 가정하지 않으며 runtime/cleanup의 Job 쓰기 금지가 함께 필요하다.

## 변경 파일 범위

이번 제품 delta는 admission Java 4개, 순수 dataset validator 1개, DemoSeedService·DemoAuthService·DemoAuthExceptionHandler, demo max 설정·migration, maintenance Java 5개·별도 Gradle task, FE coordinator의 login 오류 분기에 한정된다. 패키지/lockfile/일반 OAuth 인증 정책은 변경하지 않았다.

검증 delta는 새 admission/validator/SQL 오류/cleanup 테스트, 기존 demo fixture의 명시적 7+2 DDL 사전 준비와 validate 전환, missing-user와 로그인 경합 준비, 전용 관리자 계정 입력, 현재 테스트 자동 발견 runner, Render/Chromium의 사전 DDL 준비·독립 CLI 검사다. 과거 원격 baseline 7-table DDL과 결과를 덮어쓰지 않는다. `ManagedSchemaPreparation` 변경은 로컬 테스트용 additive migration 준비이며 원격 검증 실행·원장 변경이 아니다.

기존 배포 단계의 readiness/gateway/system font/Render 설정/managed provider 도구 변경은 시작 시 이미 존재하던 미커밋 변경이다. 이번 기능으로 새로 구현했다고 집계하지 않는다.

## 검증 결과

최종 현재-tree JUnit 전체는 **530 PASS = 일반 BE 439(40클래스) + 별도 Render 91(3클래스)**다. 한 suite의 수치라고 표현하지 않으며 모든 발견된 클래스가 해당 두 실행에 포함됐고 실패/오류/skip은 0이다. 이전 BE275만 전체 수치로 재사용하지 않았다. 신규/보강 테스트와 기존 assertion을 유지했다.

|검증|최종 결과·근거|
|---|---|
|관련 MySQL/Redis 우선 검증|[focus03](evidence/DEMO_CAPACITY_CLEANUP/be-focus-03/summary.json) 95 PASS; admission16/service24/SQL5/validator4/cleanup input17/cleanup MySQL29|
|일반 BE 전체|[be-full-02](evidence/DEMO_CAPACITY_CLEANUP/be-full-02/summary.json) 439 PASS, compile PASS, failure/error/skip0|
|Render 전체/TLS/독립 CLI|[render-full-05](evidence/DEMO_CAPACITY_CLEANUP/render-full-05/summary.json) 91 PASS; 실제 CA/hostname/credential 성공·거절; 제품 validate; maintenance 제외 runtime JAR; 실제 packaged CLI verify/dry-run PASS|
|FE 전체|[fe-full-02](evidence/DEMO_CAPACITY_CLEANUP/fe-full-02/summary.json) OAuth151 + demo101 = 252 PASS, 신규17 포함|
|타입/build/lint|제품·테스트·E2E·Functions 타입, OAuth/demo build, invalid-mode 거절 PASS; lint0 errors/0 warnings|
|실제 Chromium|[browser-full-01](evidence/DEMO_CAPACITY_CLEANUP/browser-full-01/summary.json) 독립2회 각각4 PASS; 실제 demo/JWT/MySQL/Redis; 외부 앱 요청0; cleanup PASS|
|도구 안전|scanner/projection/runner 관련 합성19 PASS; 실제값 노출 없이 고정 코드·예외 class만 보고|
|보존·공개 검사|[감사](evidence/DEMO_CAPACITY_CLEANUP/final-audit/summary.json); 과거 문서/evidence 보존, 실제 secret/금지값0; 기존 scanner 경고는 아래처럼 분리|

재현 진입점은 `python3 -B scripts/verification/demo_capacity_checks.py --phase fe|be|render|browser`다. 각 phase에 `--run-label`로 새 출력 이름을 주고, BE/render/browser에는 검토한 전용 `--cache-seed`, FE/browser에는 정확한 lockfile의 `--dependencies`, browser에는 `--browser-path`를 명시한다. `--phase be --focus`는 관련 검증만이며 전체 회귀를 대체하지 않는다. 기존 자원 소유/정리 경계를 사용하고 원격 provider 입력을 읽지 않는다. install이나 실제 .env 로딩 없이 실행했다.

### 실패 이력과 교정 범위

- fe-full-01: Git 없는 임시 복사본을 중첩 runner가 읽지 못한 준비 오류. 실제 FE test 미실행; source-manifest 입력으로 교정하고 남은 복사본 정리를 별도 근거로 확인했다.
- be-focus-01/02: JUnit 배열 인자 fixture, NOWAIT 중립 wrapper fixture, DATETIME 정밀도 검사 문제. `DECIMAL_DIGITS` 추론 대신 실제 `information_schema.columns.datetime_precision=6`을 확인한다. DATETIME(3) 거절은 유지했다. focus03에서 모두 PASS.
- render-full-01: 소유 로컬 TLS 스키마 준비 중 CommunicationsException. 다음 실행에서 별도 DDL 준비는 성공했으며 네트워크의 역사적 원인 전체를 단정하지 않는다. render-full-02는 같은 날짜 정밀도 검사의 초기화 실패였다.
- be-full-01: 439개 중 missing-user fixture 1개가 새 FK에 걸렸다. 해당 fixture의 provenance/count 준비만 갱신한 full02는 439 PASS다.
- render-full-03/04: 단순 `/test` 응답 뒤 cold raw POST 첫 로그인 실패. 04에서는 HTTP503/admission code 없음/JDBC 통신 예외 언급/토큰0/SQL 행 증가0을 관측했다. 04는 실행 중 위 무관한 HTTP 테스트 fixture 수정도 관측해 source-preserved=false를 기록했다. 원문 실패 근거를 덮어쓰지 않았다.
- render-full-05: 제품·timeout·자원은 그대로 두고 **현재 FE의 SQL/Redis 준비 관문**을 선행했다. readiness GET3회 후 ready=true, 로그인 POST 재시도0, 두 로그인/세션/보호 API/logout/폐기401/row delta/정리 PASS. 준비 경로 차이는 NARROWED이며 과거 JDBC 내부 원인을 확정했다고 주장하지 않는다. raw POST 실패가 없었던 것으로 바꾸지 않는다.

### 자원 제한 관측과 배포 제한

실제 로컬 컨테이너 512MiB/0.1CPU에서 관측 최대 메모리435.8MiB, HTTP 기동328.445초, 준비 완료354.250초, 준비 관문 이후 로그인27.508/20.500초였다. OOM0, 정상 종료와 소유 container/network/client/process/port 정리 PASS다. Mac/로컬 Docker 관측이며 TiDB/Upstash 지연을 포함하지 않고 Render 성능으로 환산하지 않는다.

**354초는 현재 FE의 cold readiness 한도240초를 초과한다.** runner의 기동 안전상한600초 + 준비 대조240초는 실제 브라우저의 단일240초 대기와 다르다. 따라서 이 제한 환경의 첫 방문 cold-start UX가 통과했다고 표시하지 않는다. 기능은 준비 완료 후 검증됐으며, 공개 배포 전 실제 기동 성능/리소스·실패 후 복구 UX를 별도 평가해야 한다. 이 작업에서 timeout/heap/CPU 정책을 늘리거나 login을 자동 재전송하지 않았다.

### 자동 scanner와 수동 검토

기존 자동 scanner는 FAIL27을 그대로 유지한다: 기존 분류의 행 이동17, 새 합성 fixture/명령 표현9, 고정 capacity 초기 INSERT1. 실제 credential/JWT/identity dump/금지 로컬 경로/예상 밖 산출물은0이며 각 지적을 수동 NON_SECRET_SOURCE로 확인했다. 허용 목록/규칙 갱신은 자동 승인 심사가 검사 우회 위험으로 거절하여 적용하지 않았다. 원래 규칙과 분류 파일은 시작 bytes와 동일하다. 자동 PASS로 이름을 바꾸지 않았고 공개 준비 true를 주장하지 않는다. 이는 로컬 기능 검사와 구분되는 공개 감사 정리 항목이다.

- 실제 원격 TiDB/Upstash: NOT_RUN. 신규 remote schema/key/login/예약 0.
- 기존 provider 원장 15,568/15,568은 접근/수정하지 않았다. 기존 marker/예약을 환급·재사용하지 않는다.

## 필수 계약과 테스트 대응

|요구 번호|실제 검증|
|---|---|
|1–7|DemoAdmissionIntegrationTest: max=2 두 로그인/세 번째 FULL, 마지막 자리 barrier 경합, NOWAIT loser 신규 SQL/Redis 0|
|8–11|기존 DemoAuthLoginTransactionTest/seed 설치 실패 검사: counter·marker 포함 snapshot rollback; 실제 commit 뒤 ACK 유실은 행·marker·count 보존 + 알려진 세션 폐기|
|12|실제 HTTP reissue/session/logout 뒤 SQL count 불변; 기존 Redis TTL 테스트 유지|
|13–15|시작/실행 중 count/max/누락·추가 capacity row 거절; schema DATETIME(3) 거절|
|16–19|실제 connection DML 관측 dry-run/verify 0; 24h 경계·새 방문·미만료 세션 선택 검사|
|20–23,32|안전 dataset 정확한 삭제/차감/반복 무차감; category·Budget·profile 변경 보존 허용|
|24–27|금융정보/User source/Budget prediction 5변형, Job, 미지원 V1, partial 거래/예산/immutable 손상 모두 batch 거절|
|28–31|count 불일치·음수/underflow 거절; 실제 DELETE 중 SQL 실패, 실제 삭제 후 affected-row 값 유실 대역 모두 rollback|
|33–34|batch 기본10/100 허용/101·음수·0 거절, 실제 oldest-first batch1; marker 없는 legacy/normal dataset 전 컬럼 불변|
|35–36|실제 cleanup transaction 대 login, cleanup 대 cleanup NOWAIT 및 loser 쓰기 0|
|37–39|전용 제한 계정 runtime DELETE/DDL/Job·max 쓰기 거절; cleanup INSERT/DDL/Job·max 쓰기 거절; 제한 cleanup 실제 apply 성공|
|추가|visible 외부 schema CASCADE FK 거절/양쪽 데이터 불변; private credential file/링크/명령 인자/정제 출력; packaged CLI TLS verify/dry-run|

기존 missing-user HTTP 회귀는 새 RESTRICT FK 때문에 준비 단계에서 실패했다. 해당 전용 fixture의 marker와 count만 정리하도록 보강했으며, 이후 401·회전 없음·DB 불변 assertion은 그대로 유지했다. 새로운 FK를 제품에서 제거하거나 테스트를 skip하지 않았다.

## 남은 제한과 다음 TiDB 한 건

resident_count는 동시 로그인 수가 아니라 **아직 SQL에 남아 있는 marker 방문 수**다. logout/1시간 session 만료 뒤에도 자리와 데이터가 남고 최소 24시간 후 관리자가 지워야 한다. marker 없는 과거 데이터는 자동 삭제하지 않으므로 새 전용 schema 설치 원칙이 필요하다. 최대 1000명이면 방문 dataset 314,000행 + marker 1000행 + capacity 1행 수준이며 저장 바이트나 처리량을 측정하지 않았다.

rate limiter/Turnstile/자동 cleanup/TTL/scheduler/mobile/AI/실제 배포는 추가하지 않았다. 단일 capacity lock과 동기 seed는 처리량보다 원자성을 우선한 선택이다. 원격 지연에 따른 BUSY 비율·cleanup batch 지연은 아직 측정하지 않았다.

다음 별도 승인 범위는 **TiDB JDBC admission/cleanup만** 검증하는 작은 probe다. 전용 빈 schema 1개, 분리 runtime/cleanup 계정, 명시적 7+2 DDL, 작은 max=2, 고정 데이터로 NOWAIT/error mapping·rollback/commit ambiguity·meta visibility·grants·정확한 PK cleanup을 확인한다. Upstash 재검증·demo HTTP login·FE/E2E를 묶지 않는다. SQL admission 경계의 Redis 역할은 쓰기 없는 내부 테스트 경계로 구분하고 전체 로그인 PASS라고 표현하지 않는다.

새 private task와 독립 명령 예산을 사용자 승인 뒤 만들며, 예시 상한 SQL command 2,000(사전 예약 정리 500 포함), 연결 최대 8, 전용 schema/계정 각각 명시 소유 원장으로 제한한다. 정확한 prepared/batch/metadata 명령 수는 구현 전 산정해 상한 안임을 확인하고 초과 예상이면 실행하지 않는다. 기존 15,568 원장을 초기화·증액하는 계획이 아니다. 실제 원격 실행은 이번에 하지 않는다.

```text
LOCAL_DEMO_CAPACITY_READY=true
LOCAL_DEMO_CLEANUP_READY=true
REMOTE_DEMO_CAPACITY_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```
