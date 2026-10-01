# Demo admission 전용 잠금 테이블 — 로컬 검증

2026-10-01. `COUNT(demo_visit)`를 점유량의 유일한 기준으로 유지하고, 배타 잠금에 필요한 권한을 업무/설정 테이블에서 분리했다. 기존 V001·7-table baseline 및 과거 보고서/evidence를 보존했다. 실제 TiDB/Upstash 연결은 하지 않았다.

```text
LOCAL_DEMO_CAPACITY_READY=true
LOCAL_DEMO_CLEANUP_READY=true
REMOTE_DEMO_CAPACITY_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```

이번 로컬 admission/cleanup 계약은 완료다. 실제 TiDB 새 권한·잠금 계약, 공개 scanner debt와 cold-start UX 등 공개 준비 전체의 완료를 뜻하지 않는다.

## 변경 이유와 권한 경계

[12단계](12-counterless-demo-capacity.md)에서 MySQL8.4의 SELECT-only 역할 두 개는 일반 SELECT와 금지 권한 검사를 통과했지만 `FOR UPDATE NOWAIT`에 1142/42000으로 실패했다. [MySQL 공식 잠금 읽기 계약](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)은 SELECT에 더해 UPDATE/DELETE/LOCK TABLES 중 하나를 요구한다. 점유량·상한 설정 테이블에 UPDATE 권한을 넓히지 않고 별도 lock-only 표에 필요한 권한을 한정했다.

새 `V002__demo_admission_lock.sql`은 `demo_admission_lock(id TINYINT NOT NULL PRIMARY KEY)`와 id=1 한 행만 추가한다. counter/업무 상태/timestamp/owner 값, CHECK 의존, IF NOT EXISTS, 자동 repair는 없다. 신규 설치 순서는 **관리자 baseline7 → V001 capacity/visit → V002 lock → 제한 계정 grant → 앱 validate**다. 기존 데이터베이스에 자동 적용하거나 기존 migration을 덮어쓰지 않는다.

|표/기능|runtime|cleanup|
|---|---|---|
|demo_capacity|SELECT만|SELECT만|
|demo_admission_lock|SELECT/UPDATE|SELECT/UPDATE|
|demo_visit|SELECT/INSERT|SELECT/DELETE|
|User/Card/Transaction/Budget|기존 필요한 SELECT/INSERT 및 User/Transaction/Budget UPDATE|SELECT/DELETE|
|Job/Peer/Dummy|기존 검증용 SELECT, write0|기존 검증용 SELECT, write0|
|DDL/lock INSERT·DELETE/capacity UPDATE|금지|금지|

`demo_admission_lock`의 UPDATE 권한은 **FOR UPDATE authorization에만 필요한 한 테이블의 제한된 권한**이다. 제품에서 이 표의 실제 UPDATE statement는 실행하지 않는다. schema 전체 UPDATE, LOCK TABLES, capacity UPDATE를 부여하지 않았다.

DB 계정이 침해되면 lock id 변경이나 장시간 잠금으로 서비스 거부를 유발할 수 있다. 이 권한을 데이터 변경이 전혀 불가능한 권한이라고 표현하지 않는다. lock row 누락/추가/예상 밖 id는 startup/runtime/cleanup에서 fail-closed로 거절하며 자동 복구하지 않는다. 실제 점유량/상한 변경 권한을 이 표가 제공하지는 않는다. 기존 업무 표의 허용 권한이 가진 위험까지 제거한 설계도 아니다.

## 제품 동작

- admission은 READ_COMMITTED의 같은 SQL 트랜잭션에서 lock id1 NOWAIT → capacity/max 및 실제 marker COUNT 확인 → User/seed/Redis → marker INSERT → 최종 COUNT=초기+1 → SQL commit 순서다. token/cookie는 commit 뒤에만 반환한다.
- 정상 count==max는 FULL, count>max 또는 구조/설정 오류는 UNAVAILABLE이다. lock NOWAIT의 정확한3572/HY000만 BUSY이며1142/8121/deadlock/연결 오류를 BUSY로 바꾸지 않는다.
- startup은 capacity singleton/id1/양수 max/설정 일치/count≤max와 lock singleton/id1·정확한 컬럼/PK/FK 구조를 확인한다. 일반 OAuth 경로에 demo 표를 요구하지 않는다.
- `recordVisit()`와 `verifyIntegrity()`는 성공한 `claimSlot()`의 현재 Spring transaction synchronization·동일 ConnectionHolder/Connection을 요구한다. REQUIRES_NEW는 바깥 claim을 사용할 수 없고 종료된 claim을 재사용할 수 없다. marker 중복·잘못된 초기COUNT·최종 검증 누락도 거절한다.
- 이 transaction claim은 잠금 획득 경로를 확인하는 내부 증명이며 점유량 cache/JVM lock이 아니다. 실제 점유량은 항상 SQL COUNT다. marker를 기록한 채 최종 검증을 생략하면 beforeCommit에서 실패한다. 직접 JDBC로 transaction을 임의 commit/rollback하는 비정상 내부 코드를 지원하는 계약은 아니며 정상 제품 경로는 Spring이 transaction을 소유한다.
- cleanup APPLY는 private lock 획득 뒤 COUNT/candidate 선택·잠금·전체 검증 후 정확한 PK의 Transaction→Budget→Card→visit→User 순서로 삭제한다. 최종COUNT=초기−삭제 수가 아니면 rollback한다. marker DELETE로 가는 우회 공개 메서드는 없다.
- 24h·absolute expiry·V1 provenance·mutable category/Budget/profile 허용·unsafe batch 전체 거절·affected rows·COMMIT_UNKNOWN/자동 retry 금지·legacy 데이터 보존을 유지한다. validator는 바꾸지 않았다.

## 로컬 검증

최종 source에서 **BE569 PASS = 일반41클래스478 + Render3클래스91**, failure/error/skip0이다. 이전547을 유지하면서 admission17·cleanup5 사례를 추가했다. 12단계의 제한 계정 실패2개도 실제 새 잠금 경로로 PASS했다.

|실행|결과|새 근거|
|---|---|---|
|집중 BE|132 PASS, failure/error/skip0|[be-focus-01](evidence/DEMO_ADMISSION_LOCK/be-focus-01/summary.json)|
|전체 BE 일반/관리자/도구|478 PASS, failure/error/skip0|[be-full-01](evidence/DEMO_ADMISSION_LOCK/be-full-01/summary.json)|
|Render 설정·실제 ConfigData/TLS|91 PASS, compile/package PASS; validate schema 불변|[render-full-01](evidence/DEMO_ADMISSION_LOCK/render-full-01/summary.json)|
|검증 도구/진단 비노출 단위|48 PASS, failure/error/skip0|[local-tools-01](evidence/DEMO_ADMISSION_LOCK/local-tools-01/summary.json)|
|전체 FE|252 PASS = OAuth151 + demo101, failure/skip/todo0|[fe-full-01](evidence/DEMO_ADMISSION_LOCK/fe-full-01/summary.json)|
|제품/test/E2E/Functions 타입|모두 PASS; 제품 타입은 각 build의 tsc -b 포함|동일 FE 근거|
|OAuth/demo build·잘못된 mode 거절|PASS|동일 FE 근거|
|ESLint|0 errors / 0 warnings|동일 FE 근거|
|실제 Chromium 독립2회|각4 PASS, 외부 앱 요청0, retry0, API mock0|[browser-full-01](evidence/DEMO_ADMISSION_LOCK/browser-full-01/summary.json)|
|모든 소유 자원 정리|PASS|각 summary의 cleanup_complete|

현재 단계에서 제품/검증 준비 실패로 재시도한 실행은 없으며 집중 검증 뒤 최종 전체 source를 검증했다. 과거 12단계 실패는 변경 전 근거로 보존했으며 같은 실패를 기대하도록 바꾸지 않았다.

새 admission37은 lock row missing/extra/id 변경, startup missing table/extra column/type 오류, 현재 transaction claim 필수, 종료 후 claim 재사용 금지, REQUIRES_NEW 분리/복원, connection 교체 거절, marker 중복/잘못된 초기COUNT/최종 검증 생략을 포함한다. cleanup36은 기존31에 lock 손상3 및 commit/rollback 자동 해제2를 더한다.

실제 NOWAIT 경합은 정확한3572/HY000을 확인하고 rollback/commit 뒤 새 transaction이 lock을 얻는 것을 확인한다. max2/login2/세 번째FULL·마지막 slot 한 승자/loserBUSY·SQL/Redis 부작용0·seed/Redis/marker/commit rollback·commit ACK 유실·session/reissue/logout marker 불변·safe/unsafe cleanup·legacy 보존을 유지한다. 공개BUSY 분류에는1142/8121/deadlock/connection 오류가 포함되지 않는다.

권한 검증은 생성한 제한 계정으로 직접 실행한다. runtime은 lock 획득 뒤 실제 User/Card/Transaction/Budget/marker INSERT 및 기존 허용 UPDATE를 수행하고 rollback한다. cleanup은 실제 제품 APPLY로 정확한 dataset을 삭제한다. 일반 SELECT 또는 금지 권한 거절만으로 성공을 판정하지 않는다. 12단계의 실패 두 사례는 새 잠금 대상에서 성공을 계속 요구하며 skip/기대값 완화는 없다.

BE 기능은 전용 MySQL8.4/Redis, Render 설정 검증은 제품 ConfigData + 관리자 사전 schema + validate로 실행한다. 기타 기존 회귀의 격리 fixture 방식을 그대로 유지한다. FE는 외부 .env 없이 정확한 lockfile 설치를 사용하고 API 대역 테스트와 실제 Chromium 검증을 분리한다. Chromium은 실제 번들·Spring/JWT/Guard·전용 MySQL/Redis를 사용하며 API mock0/retries0, 새 context로 두 번 실행한다.

새 진입점은 `scripts/verification/demo_admission_lock_checks.py`다. 기존 runner를 재사용하고 새 `DEMO_ADMISSION_LOCK` 경로만 사용한다.

```sh
python3 -B scripts/verification/demo_admission_lock_checks.py --phase be --focus --run-label <fresh-label> --cache-seed <owned-cache>
python3 -B scripts/verification/demo_admission_lock_checks.py --phase be --run-label <fresh-label> --cache-seed <owned-cache>
python3 -B scripts/verification/demo_admission_lock_checks.py --phase render --run-label <fresh-label> --cache-seed <owned-cache>
python3 -B scripts/verification/demo_admission_lock_checks.py --phase fe --run-label <fresh-label> --dependencies <exact-lockfile-installation>
python3 -B scripts/verification/demo_admission_lock_checks.py --phase browser --run-label <fresh-label> --cache-seed <owned-cache> --dependencies <exact-lockfile-installation> --browser-path <owned-chromium>
```

## 별도 제한 컨테이너 결과

기존 조건으로 **한 번 재실행해 PASS**했다. 준비 완료 뒤 login2·session/보호 API/logout·폐기된 AT401, 정확한 seed row delta와 식별값 비노출, 정상 종료/자원 정리를 확인했다. POST retry0이다. 이번 실행은 조기 종료를 재현하지 않았으므로 12단계 실패 원인을 소급 확정하지 않는다. 과거 결과는 FAIL/원인 UNKNOWN으로 그대로 보존한다.

|실측 항목|이번 로컬 관측|
|---|---:|
|제한|512MiB / CPU0.1|
|HTTP bind까지|237.341초|
|HTTP bind 뒤 ready까지|4.996초|
|기동부터 ready까지|242.337초|
|첫/후속 login|17.145초 / 9.697초|
|최대 관측 memory|422.8MiB(샘플 관측값)|
|OOMKilled|false|
|정상 정리 시 exit code|143|
|소유 자원 잔존/포트|0 / 해제 PASS|

**242.337초는 현재 FE cold 대기 한도240초보다 길다.** 실제 FE의 cold-start UX PASS가 아니며 대기 계약은 별도 잔여 항목이다. 이번에 FE/제품 timeout·heap·CPU 값을 늘리지 않았다. 로컬 Mac의 다른 회귀도 병행한 단일 시험이며, managed network latency와 실제 Render 환경을 측정한 값이 아니다. 종료 code143은 runner가 검증을 마친 뒤 정상 정리한 결과로, 이전 조기 종료 코드가 아니다.

근거: [Render 실제 설정·TLS·제한 시험](evidence/DEMO_ADMISSION_LOCK/render-full-01/summary.json). 실패 시 관측 경로는 합성 테스트로 검증했지만 이번 실제 실행에서는 성공하여 failure projection이 생성되지 않았다.

관측기는 실패 시 소유 label을 확인한 뒤, 정리 전에 Docker state와 최근 최대160줄 기동 로그를 메모리에서 읽어 고정 범주만 남긴다. exit code/OOMKilled/container state/reason/마지막 관측 phase를 구분한다. exit137만으로 OOM을 추론하지 않으며 미관측 단계는 NOT_OBSERVED다. raw 로그/실제 계정/토큰/PID/경로/컨테이너 식별값은 공개하지 않는다. CPU0.1·512MiB·heap·제품 timeout·기존 runner의 대기 한도는 바꾸지 않았다. 이 결과를 실제 Render 성능으로 환산하지 않는다.

## 다음 최소 TiDB probe의 조건부 설계

로컬 계약 PASS를 확인한 뒤 별도 승인할 후보이며, 이번 실행 승인을 의미하지 않는다. 이전 원장의 잔여를 전용하거나 과거 probe를 재실행하지 않는다.

1. 새 task·빈 소유 schema1·runtime/cleanup 계정2·JDBC 최대4연결(admin/runtimeA/runtimeB/cleanup). TLS identity·READ_COMMITTED·TiDB pessimistic 조건을 유지한다.
2. 실제 `SELECT VERSION()`의 제한된 version 문자열을 먼저 기록하고 baseline7→V001→V002 순서의10-table 구조·lock singleton을 확인한다. column-level 권한은 사용하지 않는다.
3. dataset 생성 **전에** 두 역할의 capacity SELECT/UPDATE 거절, lock SELECT/NOWAIT 성공/INSERT·DELETE 거절을 확인한다. 권한 오류는 여기서 중단하며 기대값을 낮추지 않는다.
4. A가 lock을 보유할 때 B의 정확한3572/HY000과 제품 BUSY를 확인한다. A rollback 후, 별도 commit 후 각각 자동 해제를 확인한다. advisory/session lock이나 RELEASE는 없다.
5. Spring JDBC transaction 경계에서 제품 `DemoAdmissionStore`를 사용한다. rollback용 User/marker 한 묶음의 부재를 확인한 다음, 관리자 준비 max=1에서 직접 작성 V1 fixture 한 방문(총315행)과 marker COUNT0→1/FULL/초과0을 검증한다. HTTP 인증/Redis/JPA seed 전체 재검증으로 표현하지 않는다.
6. 관리자 계정으로 정확한 소유 marker만 cleanup 적격 시각으로 준비하고 제품 cleanup VERIFY/DRY_RUN/APPLY의 COUNT1→0/정확한 삭제/금지 권한 유지와 잔존0을 확인한다. Upstash·두 번째 완전 seed·Job/unsafe 추가 fixture·FE는 제외한다.
7. 정확한 소유 데이터/계정/schema 부재 및 client 종료를 확인한다. 불명확 결과는 자동 재시도하지 않는다.

새 probe 구현 전 **잠정 명령 상한2500 = 검증2000 + 별도 정리500**을 제안한다. 이는 승인/예약/공급자 과금 관측이 아니다. 기존2000 예약은 그대로 보존한다. 현 호출 구조에 대한 보수적 envelope는 다음과 같으며, 새 실행 코드의 모든 정상/early-fail/finally 경로를 합성 JDBC로 계수한 뒤 확정해야 한다.

|분류|잠정 상한|
|---|---:|
|연결4×32|128|
|schema/account/10-table DDL/grant/TLS/version|96|
|제품 schema 검증4회 및 관계 메타데이터|400|
|NOWAIT·rollback/commit 해제 대조|160|
|독립 rollback fixture|64|
|V1 INSERT315 및 TX/집계/assertion|400|
|권한 허용·거절|96|
|cleanup 후보/정확한 삭제/COUNT/transaction|128|
|범위·정리 전 소유 확인|128|
|분류 합계|1600|
|driver·안전 확인 차이의 여유(재시도용 아님)|400|
|별도 자원 정리|500|

원격 실행 전에 이 상한을 보장할 수 없다면 중단한다. 재연결/실패 반복 또는 전체 공급자 회귀를 이 여유분으로 수행하지 않는다.

이번에는 원격 input/state/budget/marker를 열거나 수정하지 않았고 신규 연결/예약/원격 데이터0이다. 과거 실패 task와2000 예약은 재사용·환급하지 않는다. 이전 provider/Upstash 기능 검증도 반복하지 않는다. 기존 counter 기반 probe는 옛 migration digest로 현재 배포 DDL을 거절하는 역사적 검증기이며 다음 probe로 사용하지 않는다.

## 파일 범위와 보존 감사

시작570개 파일을 기준으로 비교했다. 기존 파일 삭제/권한·symlink 변경0, 과거 보고서/evidence163개 원문 보존, Git HEAD/index 불변이다. STATUS는 이번 최신 절만 앞에 추가하고 이전 본문을 보존했다. V001·7-table baseline·validator·제품 인증/FE·timeout/heap/CPU 설정도 불변이다.

공개 scanner는 **FAIL38**이다. 기존35 후보를 유지하고 신규 V002의 고정singleton INSERT1 및 작성한 예약 도메인 테스트 fixture2개 참조가 추가됐다. 실제 secret 강한 패턴0, 금지 evidence/이미지 메타데이터 탐지0, 생성물 후보0이다. 후보별 수동 검토를 기록했지만 자동 scanner PASS나 allowlist 승인으로 바꾸지 않았으며 규칙/분류 파일을 수정하지 않았다.

|범위|이번 파일|
|---|---|
|제품 수정3|`DemoAdmissionSchema.java`, `DemoAdmissionStore.java`, `DemoCleanupService.java`|
|신규 migration1|`be/src/main/resources/db/demo/V002__demo_admission_lock.sql`|
|기존 테스트·fixture6|`DemoAdmissionIntegrationTest.java`, `DemoAdmissionSqlErrorTest.java`, `DemoCleanupMySqlTest.java`, `DemoAuthHttpTestSupport.java`, `OwnedDemoSchemaPreparation.java`, `ManagedSchemaPreparation.java`|
|기존 검증 도구3|`demo_capacity_checks.py`의 새 namespace, `demo_browser_e2e.py`의 V002 설치, `render_runtime.py`의 안전한 실패 관측|
|신규 검증 도구2|`demo_admission_lock_checks.py`, `test_demo_admission_lock_checks.py`|
|문서·근거|이 보고서, STATUS 최신 절, 새 DEMO_ADMISSION_LOCK summary|

[최종 보존·공개 감사](evidence/DEMO_ADMISSION_LOCK/final-audit/summary.json)에 실제 변경 파일과 정적 marker 호출 경로를 기록했다. 정상 제품 INSERT는 `DemoAuthService.login`→동일 transaction claim 뒤 `recordVisit`, DELETE는 cleanup APPLY의 private 경로뿐이다. 제품의 resident_count 참조, capacity/lock UPDATE statement는0이다.

stage/commit/push/서비스 생성/공개 배포는 하지 않았다. 공개 scanner, 제한 환경 cold-start, abuse 방지·모바일 UI 등 별도 과제를 완료로 바꾸지 않는다.
