# Counterless demo capacity — 구현 저장, 권한 계약 미완료

2026-10-01. `resident_count`를 제품에서 제거하고 **singleton 행 잠금 + 실제 `COUNT(demo_visit)`**로 변경했다. 새로운 원격 연결은 하지 않았다. 현재 SELECT-only 요구와 MySQL 8.4의 `FOR UPDATE` 권한 요구가 충돌하므로 전체 완료로 표시하지 않는다. 권한 확대나 테스트 기대값 완화는 적용하지 않았다.

```text
LOCAL_DEMO_CAPACITY_READY=false
LOCAL_DEMO_CLEANUP_READY=false
REMOTE_DEMO_CAPACITY_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```

## 변경 이유와 확인 범위

[11단계 실제 TiDB 실행](11-demo-capacity-tidb.md)은 schema/TLS 및 NOWAIT 3572/HY000을 확인했지만 첫 `claimSlot()`의 권한 검사 8121/HY000에서 중단됐다. 과거 근거는 실패 SQL 태그를 보존하지 않아 counter UPDATE 호환성을 유력 원인으로 좁혔으며, 이번 사용자 지시의 UPDATE 실패 설명과 과거 직접 관측의 확신도를 구분한다. [이전 실제 공급자 검증](09-managed-provider-recovery.md)에 기록된 서버는 TiDB 8.5.3 serverless다. [TiDB 열 단위 권한 문서](https://docs.pingcap.com/tidb/stable/column-privilege-management/)는 지원 시작을 8.5.6으로 명시한다. 이 작업은 권한 우회 대신 mutable counter 자체를 없앤다.

과거 NOWAIT 성공은 당시 역할에 열 UPDATE 권한도 주어진 상태의 결과다. 따라서 **과거 잠금 성공이 새 SELECT-only 역할의 잠금 성공을 증명하지는 않는다.** TiDB의 새 권한 계약은 미검증이다. 이번에는 실제 provider 입력·원장에 접근하지 않았고 TiDB/Upstash 연결·예약·원격 자원 생성 모두 0이다. 공식 문서의 읽기와 공급자 데이터 연결을 구분한다.

## 제품 delta

- 현재 배포용 `V001__demo_admission.sql`의 capacity 표는 `id TINYINT PRIMARY KEY`, `max_visitors INT NOT NULL` 두 열뿐이다. 초기행은 id=1/max=1000이다. `demo_visit`의 열·PK·RESTRICT FK·DATETIME(6)·index는 그대로다. 재적용을 숨기는 `IF NOT EXISTS`, 운영 DB 자동 변경, marker backfill은 없다.
- `DemoAdmissionStore.claimSlot()`은 writable READ_COMMITTED 트랜잭션에서 NOWAIT 잠금 → singleton/config 검증 → 실제 marker COUNT를 읽고 초기 수를 반환한다. 정상 count==max면 FULL, count>max인 손상 상태와 일치하지 않는 schema/config/SQL은 UNAVAILABLE이다. 별도의 DB 값·cache·JVM lock을 만들지 않았다.
- `DemoAuthService.login()`은 기존 cookie 검사 후 같은 SQL 트랜잭션에서 초기 COUNT → User → V1 seed → Redis session → marker INSERT → 정확히 초기 COUNT+1 확인 → SQL commit → token 반환 순서를 유지한다. 초기 COUNT는 해당 트랜잭션의 비교값일 뿐 새로운 점유량 저장소가 아니다.
- startup은 capacity 정확히 한 행/id1/양수 max/설정 일치/marker COUNT≤max를 확인한다. 초과 기록을 삭제하거나 자동 복구하지 않는다.
- cleanup APPLY는 같은 capacity 잠금 뒤 초기 COUNT를 읽고 전체 batch 재선택·잠금·검증 후 Transaction→Budget→Card→marker→User를 정확한 PK로 삭제한다. commit 직전 COUNT=초기 COUNT−삭제 방문 수를 검사한다. INSERT/UPDATE/decrement가 없다.
- 실제 SQL 오류·affected-row 불일치는 전체 rollback, commit 응답 불명확은 UNKNOWN/자동 재시도 금지를 유지한다. SQL/Redis 보상, commit 후 ACK 유실 시 SQL dataset/marker 보존도 유지한다.

정상 제품 marker INSERT의 호출자는 `DemoAuthService.login()` 하나이며 `claimSlot()` 뒤에 위치한다. marker DELETE는 독립 `DemoCleanupService` APPLY에서 capacity 잠금 뒤에만 실행된다. 테스트 준비·장애 주입과 관리자의 전용 자원 teardown은 제품 경로와 구분했다. `recordVisit()`를 향후 다른 내부 경로에 연결할 때도 이 순서를 지켜야 한다.

7-table baseline DDL, `DemoDatasetValidator`, scenario, OAuth/JWT/Redis/FE 제품 코드·환경 설정은 변경하지 않았다. category/Budget/profile의 정상 수정은 cleanup 가능한 상태로 유지한다.

## 요청한 최소 권한과 발견된 충돌

|대상|runtime|cleanup|
|---|---|---|
|demo_capacity|SELECT만|SELECT만|
|demo_visit|SELECT/INSERT|SELECT/DELETE|
|User/Card/Transaction/Budget|필요 SELECT/INSERT; 기존 수정에 필요한 User/Transaction/Budget UPDATE|SELECT/DELETE|
|Job/Peer/Dummy|검증에 필요한 SELECT, write 없음|검증에 필요한 SELECT, write 없음|
|DDL·capacity UPDATE|없음|없음|

max 변경은 migration 관리자 책임이다. cleanup에는 어떤 UPDATE도 부여하지 않았다.

최종 실제 MySQL 8.4 검사는 runtime·cleanup을 별도 사례로 실행했다. 둘 다 일반 capacity SELECT 및 기존 UPDATE/DML/DDL 거절 assertion을 통과한 뒤 `SELECT id,max_visitors ... FOR UPDATE NOWAIT`에서 **1142/42000**으로 실패했다. 테스트의 `DEMO_TABLE_PRIVILEGE_DENIED`는 이 vendor/state 조합에서만 생성된다. 정제된 근거에는 고정 단계/오류 코드만 저장하며 실제 계정·SQL 오류 원문은 싣지 않는다.

|제한 역할|일반 SELECT|capacity UPDATE 거절|금지 권한 검사|배타 NOWAIT 잠금|후속 제한 역할 기능|
|---|---|---|---|---|---|
|runtime|PASS|PASS|PASS|FAIL: 1142/42000|admission 성공으로 판정하지 않음|
|cleanup|PASS|PASS|PASS|FAIL: 1142/42000|VERIFY/APPLY BLOCKED: 잠금 단계 실패로 미도달|

근거: [최종 BE 결과](evidence/COUNTERLESS_DEMO_CAPACITY/be-full-01/summary.json)의 `DemoCleanupMySqlTest` 두 실패 사례, [분류 소스](../../be/src/test/java/com/potg/don/maintenance/DemoCleanupMySqlTest.java). 관리자 역할의 기능 테스트와 실제 제한 계정 테스트를 혼동하지 않는다.

[MySQL 8.4 공식 잠금 읽기 계약](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)은 `FOR UPDATE`에 SELECT와 추가 DELETE/LOCK TABLES/UPDATE 중 하나를 요구한다. 이는 제품 COUNT 알고리즘의 rollback 실패가 아니라 **SELECT-only 역할과 배타 잠금의 권한 계약 충돌**이다. 일반 SELECT나 UPDATE 거절의 성공만으로 배타 잠금/제한 계정 cleanup APPLY를 PASS로 만들지 않는다.

이번에는 LOCK TABLES·UPDATE·DELETE 권한을 추가하거나 FOR SHARE/JVM lock으로 바꾸지 않았다. 다음 결정은 DB별 잠금 권한 계약이다. MySQL의 `LOCK TABLES`는 [공식 권한 표](https://dev.mysql.com/doc/refman/8.4/en/privileges-provided.html)상 **database 범위**이므로 capacity 한 표에만 부여하는 대안으로 제시하지 않는다. 전용 schema의 비-DML 잠금 권한을 허용할지, TiDB 전용 권한 검증과 로컬 MySQL 역할 검증을 어떻게 구분할지는 별도 승인·위험 검토가 필요하다. 현재 승인된 SELECT-only 계약 아래서는 로컬 준비 완료가 아니다.

## 실행 검증

최종 source의 전체 JUnit은 **547개 = 일반/관리자/검증도구 456 + Render 설정·TLS 91**이다. 총 **545 PASS / 2 FAIL / error0 / skip0**이며, 두 실패는 위 제한 역할 잠금 계약이다. 이전 BE275만 고른 결과가 아니며 현재 발견한 44개 테스트 클래스를 두 실행으로 모두 검증했다. 컴파일·패키징은 PASS다.

|검증|실제 결과|근거|
|---|---|---|
|최초 focus|컴파일 준비 실패, JUnit0; 기능 RED 아님|[be-focus-01](evidence/COUNTERLESS_DEMO_CAPACITY/be-focus-01/summary.json)|
|교정 뒤 focus|109 중108 PASS/1 FAIL, error/skip0|[be-focus-02](evidence/COUNTERLESS_DEMO_CAPACITY/be-focus-02/summary.json)|
|최종 BE 41클래스|456 중454 PASS/2 FAIL, error/skip0|[be-full-01](evidence/COUNTERLESS_DEMO_CAPACITY/be-full-01/summary.json)|
|Render 3클래스·실제 ConfigData/TLS|91 PASS, error/skip0; compile/package PASS|[render-full-01](evidence/COUNTERLESS_DEMO_CAPACITY/render-full-01/summary.json)|
|추가 512MiB/0.1CPU 패키지 기동|**FAIL**, `limited-packaged-app` / `limited-app-exited`|동일 Render 근거|
|로컬 Python 도구·공개 정제 검사|38 PASS, failure/error/skip0|[local-tools-01](evidence/COUNTERLESS_DEMO_CAPACITY/local-tools-01/summary.json)|
|전체 FE|252 PASS = OAuth151 + demo101, failure/skip/todo0|[fe-full-01](evidence/COUNTERLESS_DEMO_CAPACITY/fe-full-01/summary.json)|
|제품/test/E2E/Functions 타입·OAuth/demo build|전부 PASS; invalid mode의 의도한 거절 PASS|동일 FE 근거|
|ESLint|0 errors / 0 warnings|동일 FE 근거|
|실제 Chromium 독립2회|각4 PASS, 외부 앱 요청0, API mock0, retries0|[browser-full-01](evidence/COUNTERLESS_DEMO_CAPACITY/browser-full-01/summary.json)|
|모든 실행의 소유 자원 정리|PASS, 원격 연결0|각 실행의 `cleanup_complete=true`|

Render 추가 자원 제한 시험은 준비 완료 전에 앱이 종료됐다. 기존 runner는 이 지점의 container exit code/OOM 여부/내부 원인을 보존하지 않아 **원인 UNKNOWN**이다. readiness 이후 제한 환경 로그인·동작·시간 측정은 **BLOCKED(앱 종료로 미도달)**다. 이것을 권한 실패와 같은 원인으로 단정하거나 Render 서비스의 장애라고 표현하지 않는다. 자동 재시도·제품 timeout 확대는 하지 않았다. 컨테이너/전용 포트/프로세스 등 소유 자원 정리는 완료됐다.

FE252 중 이전176 이후의 76개는 이전 배포 준비 단계에서 이미 추가된 테스트다. 이번 task에서 FE 테스트/제품 변경은0이다. 제품 타입 검사는 두 모드 build의 `tsc -b`를 포함한다. 실제 브라우저 핵심 조회/수정/복원/logout과 cookie/CORS는 통과했지만 제한 DB 계정의 권한 계약을 대신 검증하지 않는다.

첫 focus 실행은 기존 오류 분류 테스트의 메서드 참조 한 곳이 새 인자를 반영하지 못해 JUnit 0개로 중단됐다. 이는 기능 RED가 아니다. `verifyIntegrity(0L)`로 호출부만 교정해 unbound transaction 거절/JDBC 0 assertion을 유지했다. 두 번째 focus는 109개 중 108 PASS/권한 사례 1 FAIL이었다. 실패한 두 역할이 서로의 관측을 막지 않도록 같은 권한 테스트를 role별로 분리했고, 일반 SELECT·기존 권한 거절 뒤 NOWAIT의 고정 오류 분류만 추가했다. 기대값은 여전히 잠금 성공이며 실패를 성공으로 바꾸지 않았다.

변경된 테스트 의미는 다음과 같다.

|계약|검증|
|---|---|
|max2/세 번째 FULL/마지막 자리 경합|실제 HTTP·MySQL·Redis; 정확히 한 승자, NOWAIT loser BUSY, SQL/Redis 추가 생성 0|
|startup/runtime integrity|누락/복수/잘못된 id/0 max/max 불일치/실제 marker 초과; schema precision/FK 거절 유지|
|login 원자성|seed/Redis/marker 실패 rollback, 실제 marker INSERT 후 제거 주입에서 최종 COUNT 검사와 Redis revoke|
|commit 경계|commit 실패 rollback/revoke; 실제 commit 후 ACK 유실은 SQL·marker 유지, 자동 환급 없음|
|기존 세션 동작|reissue/session/logout은 SQL marker 수 불변|
|cleanup 안전|verify/dry DML0, 24h·절대 만료·batch 순서, 정확한 삭제/재실행 추가 삭제0, 정상 mutable 수정 허용|
|cleanup 실패|unsafe batch 삭제0, SQL 실패/affected-row 불일치/추가 marker 삭제 주입은 전체 rollback, commit UNKNOWN 유지|
|다른 데이터 보존|marker 없는 User 및 dataset 전 컬럼 불변, Job/금융 source/추가 FK 거절|
|권한|SELECT-only/UPDATE 거절은 유지; 실제 잠금과 제한 cleanup의 판정은 위 충돌 참조|

순수 오류 분류 테스트는 8121/HY000도 BUSY가 아님을 확인한다. 오직 3572/HY000만 BUSY다. 테스트 삭제/skip, scanner 규칙 완화, 설정 완화, validator 기준 완화는 없다.

재현 진입점은 `scripts/verification/counterless_demo_capacity_checks.py`다. 기존 격리 runner에 새 출력 namespace만 전달한다. BE는 현재 모든 JUnit 클래스를 발견하고 별도 Render 3클래스는 TLS phase에서 실행한다. focus에는 기존 commit/ACK transaction 테스트도 포함한다.

```sh
python3 -B scripts/verification/counterless_demo_capacity_checks.py --phase be --focus --run-label <fresh-label> --cache-seed <owned-cache>
python3 -B scripts/verification/counterless_demo_capacity_checks.py --phase be --run-label <fresh-label> --cache-seed <owned-cache>
python3 -B scripts/verification/counterless_demo_capacity_checks.py --phase render --run-label <fresh-label> --cache-seed <owned-cache>
python3 -B scripts/verification/counterless_demo_capacity_checks.py --phase fe --run-label <fresh-label> --dependencies <exact-lockfile-installation>
python3 -B scripts/verification/counterless_demo_capacity_checks.py --phase browser --run-label <fresh-label> --cache-seed <owned-cache> --dependencies <exact-lockfile-installation> --browser-path <owned-chromium>
```

실행은 모두 전용 로컬 자원·합성 설정이다. Render 설정/TLS 단계는 실제 제품 ConfigData와 사전 준비한 schema에 대한 validate를 사용하며, 일반 회귀의 기존 전용 테스트 schema 초기화 방식은 그대로 유지한다. 최종 FE와 Chromium PASS는 제한 계정의 JDBC 잠금 권한 또는 실제 TiDB PASS가 아니다. 로컬 성능을 Render 성능으로 환산하지 않는다.

## 기존 probe와 다음 원격 범위

실패 task `moneytoad-deploy-capacity-tidb-20261001-state`는 열거나 수정하지 않았다. 기존 marker/2000 선예약/소유 원장/실패 결과를 그대로 둔다. 과거 provider/Upstash 전체 검증 재실행도 0이다.

기존 `CapacityTiDbProbe`는 11단계의 counter 기반 SQL·원래 migration digest를 보존한다. 현재 API 이름/인자만 컴파일 호환되게 바꿨으며 **현재 migration은 기존 digest 검사에서 연결 전에 거절**된다. 이것은 새 counterless probe가 아니며 이전 task나 실행기를 재사용하면 안 된다. 과거 보고서/evidence는 편집하지 않았다.

현재 로컬 권한 검증이 FAIL이므로 새 원격 probe의 실행 조건은 충족되지 않았다. 로컬 권한 계약 결정 뒤 별도 승인할 최소 설계는 다음과 같다.

1. 새 private task·빈 소유 schema1·분리 runtime/cleanup 계정2·JDBC 연결 최대4. 기존 state/input 재작성 없음.
2. TLS identity 후 `SELECT VERSION()`의 제한된 version 문자열만 기록. column privilege는 사용하지 않는다.
3. dataset 쓰기 **전에** SELECT-only capacity의 일반 SELECT, `FOR UPDATE NOWAIT`, 다른 연결의 NOWAIT/3572 분류, capacity UPDATE 거절을 각각 고정 단계로 확인한다. 실패하면 후속 쓰기 없이 정리한다.
4. 작은 max=1, rollback용 User/marker 한 묶음 후 부재 확인, 정상 V1 dataset 한 개 설치, 실제 COUNT1/FULL/초과0 확인. HTTP login·JWT·Redis·Upstash·AI 없음.
5. 소유 marker만 테스트 목적으로 aging, 제품 cleanup verify/dry/apply와 COUNT1→0·정확한 삭제 수·UPDATE 거절을 확인한다. fixture 전체 재생성과 provider 성능 측정은 하지 않는다.
6. 소유 데이터/계정/schema 부재와 client 종료를 확인한다. 실패/불명확 결과는 자동 재시도하지 않는다.

잠정 명령 예약은 **검증1000 + 정리500 = 총1500**이다. 과거 예약을 전용/환급하는 값이 아니며 새 승인 전 실제 예약은 0이다.

|보수적 command-equivalent 설계|상한|
|---|---:|
|연결4×32|128|
|메타데이터4회×7호출×8|224|
|DDL/GRANT/부재 확인|90|
|V1 dataset1 + marker INSERT|315|
|rollback/COUNT/lock/트랜잭션 상태|100|
|제품 cleanup SQL|84|
|권한 거절·결과 관측|59|
|검증 합계|1000|
|별도 소유 자원 정리|500|

이는 실행 코드 작성 전 설계 상한이다. Connector/J/메타데이터의 실제 호출 그래프가 이 안에 들어가는지 구현 시 다시 산정하고, 넘으면 임의 실행하지 않는다. TiDB RU·공급자 청구량 측정이 아니다. 최소 권한이 확인되기 전에 315행을 먼저 생성하지 않는 순서를 우선한다.

## 보존·공개 감사와 남은 항목

작업 시작의 559개 파일을 기준으로 비교했다. 기존 파일 삭제/권한·symlink 변경0, 과거 보고서·evidence154개 원문 보존, 기존 Git HEAD/index 불변이다. STATUS는 이번 결과를 앞에 추가하고 이전 본문을 그대로 보존했다. 7-table baseline DDL·validator·scanner 규칙·분류 파일도 불변이다.

공개 scanner는 **FAIL35**를 그대로 기록한다. 기존과 파일/범주별 후보 수가 같고, 실제 secret 강한 패턴 탐지0·evidence 금지값/이미지 메타데이터 탐지0·생성물 후보0이다. 고정 singleton INSERT·소스의 필드명/환경변수 참조·예약 도메인의 합성 테스트 데이터 등을 수동 분류했지만 이를 자동 scanner PASS로 바꾸지 않았다. 규칙/allowlist 변경0이다. 상세 보존·호출 경로 감사는 [final-audit](evidence/COUNTERLESS_DEMO_CAPACITY/final-audit/summary.json)에 있다.

이번 파일 delta:

|범위|파일|
|---|---|
|제품5|`DemoAdmissionSchema.java`, `DemoAdmissionStore.java`, `DemoAuthService.java`, `DemoCleanupService.java`, `V001__demo_admission.sql`|
|기존 테스트6|`DemoAdmissionIntegrationTest.java`, `DemoAuthHttpIntegrationTest.java`, `DemoAuthLoginTransactionTest.java`, `DemoAuthServiceTest.java`, `DemoAdmissionSqlErrorTest.java`, `DemoCleanupMySqlTest.java`|
|과거 probe 컴파일 호환1|`CapacityTiDbProbe.java`: 원격 실행/digest/과거 SQL 의미는 보존|
|검증 도구|기존 `demo_capacity_checks.py`의 출력 namespace 분리; 신규 `counterless_demo_capacity_checks.py`, `test_counterless_demo_capacity_checks.py`|
|문서·근거|이 보고서, STATUS 최신 절, 새 `COUNTERLESS_DEMO_CAPACITY` summary만 추가|

가장 먼저 해결할 한 건은 **SELECT-only와 DB별 배타 잠금 권한의 계약 충돌 결정**이다. 현재 실패를 유지한 채 추가 권한 부여/원격 실행은 진행하지 않는다. 그 뒤 별도로 제한 기동 종료의 관측을 보완해야 하며, 이번 종료 원인은 미확인으로 남긴다.

기능 추가, 제품 timeout/TLS/auth 정책 변경, 원격 연결, stage/commit/push/서비스 생성/배포는 하지 않았다. 별도 잔여 항목인 공개 scanner 정리, cold-start 대기 예산, abuse 방지, 모바일 Chart, 실제 배포는 해결됐다고 표시하지 않는다.
