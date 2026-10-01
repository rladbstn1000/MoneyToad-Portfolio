# 보완된 TiDB admission-lock 최소 검증 재실행

2026-10-01. **이번 독립 원격 실행은 FAIL이다. schema·제한 권한·NOWAIT·제품 admission은 PASS했지만 제품 cleanup의 VERIFY/DRY_RUN 단계에서 SQL1235/42000으로 중단됐다.** 같은 task를 반복하지 않았고 제품/DDL/GRANT/timeout을 변경하지 않았다. 실패 뒤 소유 데이터·두 계정·스키마의 실제 제거/부재 및 client/process 정리는 PASS다.

```text
REMOTE_DEMO_SCHEMA_VERIFIED=true
REMOTE_DEMO_LOCK_PRIVILEGES_VERIFIED=true
REMOTE_DEMO_ADMISSION_VERIFIED=true
REMOTE_DEMO_CLEANUP_VERIFIED=false
REMOTE_DEMO_CAPACITY_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```

근거: [이번 원격 실행](evidence/DEMO_ADMISSION_LOCK_TIDB_RETRY/probe-856e527496d04451/summary.json). 이전 [14단계](14-demo-admission-lock-tidb.md)의 원격 FAIL·로컬 교정 근거는 그대로 보존한다. 이번 PASS 항목은 이번 실행에서 새로 관측한 결과이며 과거 결과를 재분류한 것이 아니다.

## 실행 전 경계

기존 검증기를 재사용했다. 신규 `scripts/verification/demo_admission_lock_tidb_retry.py`는 **공개 evidence 출력 폴더만** 이번 경로로 지정하는 진입점이다. 입력·예산·연결·SQL·권한·원장·정리·결과 assertion은 바꾸지 않았다. 제품과 검증 본체, 기존 테스트 수정0이다.

- 음성 권한 검사에서만 MySQL1142/1143+42000 및 TiDB8121/HY000을 expected denial로 인정한다.
- 허용 작업의8121, unknown vendor/state, 잘못된 SQLState, 성공한 금지 SQL은 FAIL을 유지한다.
- 제품 BUSY는 오직3572/HY000이다.8121을 BUSY로 바꾸지 않는다.
- 역할/연산 진단은 검토된 고정 태그만 허용한다. SQL 원문·계정·credential·예외 메시지를 공개 결과에 저장하지 않는다.
- 새 독립 private state/marker/ownership ledger/budget을 사용했다. 기존 모든 task는 열거나 수정·초기화·환급·재사용하지 않았다.

원격 전에 Python20 및 출력 분리 합성2개 PASS, Java20/compile/budget proof PASS를 확인했다. `--execute`도 새 복사본에서 Java20을 다시 통과한 뒤에만 연결했다. 모두 failure/error/skip0이다. [별도 offline 준비](evidence/DEMO_ADMISSION_LOCK_TIDB_RETRY/probe-d68461679e9344b2/summary.json), [로컬 검사·정리](evidence/DEMO_ADMISSION_LOCK_TIDB_RETRY/local-checks-01/summary.json)

기존 safety test는 실제 probe의 정상 흐름과776개 work 실패 위치·776개 interrupt 위치·40개 cleanup 실패 위치를 검사한다. 알려진 합성 정상계수 work1180/cleanup40/연결4, 실패 최대 work1180/cleanup47/연결4를 유지하는 동일 소스로 재실행했다. 실제 코드 hard cap은 work2000/cleanup500/연결4이며 초기화·metadata에 보수적 계수를 포함한다. dispatch 전 합성 실패 검사이지 원격 ACK-loss·commit ambiguity·provider 장애 보장은 아니다.

## 실제 TiDB 결과

|계약|판정|실제 관측|
|---|---|---|
|지정 입력 안전·불변|PASS|외부 지정 파일의 owner/regular file/링크/권한 조건 확인, 필요한 TiDB4필드만 전달|
|TLS VERIFY_IDENTITY|PASS|4개 실제 JDBC 연결의 인증서·hostname 검증과 cipher 존재 확인|
|실제 version|PASS|정제된 `8.0.11-TiDB-v8.5.3`|
|사전 부재·소유 생성·DDL|PASS|빈 schema1, runtime/cleanup 각1; baseline7→V001→V002→제한 GRANT|
|schema|PASS|총10개 표, capacity/lock singleton, visit 구조·FK/RESTRICT/index/DATETIME(6), 초기COUNT0/max1|
|metadata 가시성|PASS|cleanup에 보이는 소유 table10/FK4, admin의 소유 schema 참조 예상 밖 외부FK0|
|runtime/cleanup 권한 matrix|PASS|dataset 생성 전에 모든 허용 작업 성공·금지 작업 거절. 예상 밖 권한 성공0|
|READ_COMMITTED·pessimistic|PASS|제품 transaction-mode 검사로 확인. provider 전역/session 정책 자동 변경0|
|NOWAIT 경합|PASS|A 보유 중 B vendor3572/HY000, 제품 DEMO_ADMISSION_BUSY 일치|
|잠금 자동 해제|PASS|A rollback 뒤, 별도 commit 뒤 새 transaction에서 lock 획득|
|제품 admission rollback|PASS|같은 Spring JDBC transaction의 User/marker fixture rollback 뒤 잔존0/COUNT0|
|정상 V1 dataset|PASS|User1/Card1/Transaction240/Budget72/demo_visit1=315행, 금융정보·AI 예측/출처 null, Job/Peer/Dummy0|
|COUNT/FULL|PASS|COUNT1/max1에서 다음 제품 claim은 DEMO_CAPACITY_FULL, 추가 User/marker/data0|
|category 변경·적격화 준비|실행 완료|허용 category1건 UPDATE affected1, 정확한 소유 marker만 DB 시각 기준 생성25h 전/만료1h 전으로 준비|
|변경 dataset의 cleanup-safe 판정|BLOCKED|validator 완료 근거에 도달하지 못함. category UPDATE 성공만으로 판정하지 않음|
|제품 cleanup VERIFY/DRY_RUN|FAIL / 완료 미확인|통합 phase에서 CLEANUP_SQL_FAILURE, vendor1235/SQLState42000. 두 mode의 개별 완료 여부는 미기록|
|제품 cleanup APPLY|BLOCKED|후속 phase에 도달하지 않아 삭제·COUNT1→0·commit 계약 미검증|
|finally 소유 자원 정리|PASS|admin 경계로 정확한 소유 데이터 정리, 계정2/schema 부재, client/process 종료|

NOWAIT의 클라이언트 경합 검사 구간은489ms였다. 제품 claim의 관련 조회와 네트워크 왕복을 포함한 단일 관측이며, lock SQL 자체의 순수 실행 시간이나 지연 상한 보장이 아니다. vendor3572/HY000로 NOWAIT 실패를 확인했다.

`max_visitors=1`은 이번 전용 probe fixture다. 제품 기본1000은 변경하지 않았다. 점유량은 COUNT(demo_visit)만 사용하고 resident_count는 도입하지 않았다. 정상 V1 dataset은 현재 DemoSeedScenario의 직접 작성 시나리오를 사용한 SQL fixture이며, demo HTTP login/JWT/Redis 또는 JPA seed 전체 흐름을 재실행한 것이 아니다.

metadata 검사 범위는 제한 역할에 실제 보이는 소유 schema다. 서버 전체 숨겨진 schema/FK의 부재를 주장하지 않는다. 기존 다른 프로젝트의 행을 읽거나 비교하지 않았다. 정상 seed/FULL 경계의 unrelated 표·singleton 불변은 확인했지만 제품 cleanup 이후 불변은 APPLY 미실행으로 미검증이다.

## 권한 PASS의 정확한 의미

runtime은 capacity SELECT 및 lock SELECT/NOWAIT, 필요한 업무 INSERT/허용 UPDATE를 통과했다. capacity UPDATE, lock INSERT/DELETE, 업무 DELETE, DDL, Job/Peer/Dummy 쓰기는 거절됐다. cleanup은 capacity SELECT, lock SELECT/NOWAIT, cleanup 대상 SELECT/DELETE를 통과하고 capacity UPDATE, lock INSERT/DELETE, 업무 INSERT/UPDATE, DDL 및 Job/Peer/Dummy 쓰기가 거절됐다.

dataset 생성 전 허용 DML 권한은 **영향 행0의 SQL**로 확인했고, 이후 runtime의 실제 V1 INSERT·category UPDATE를 통과했다. cleanup의 실제 행 DELETE는 제품 APPLY에서 확인해야 하는 계약이므로 이번 권한 matrix PASS가 제품 cleanup PASS를 대신하지 않는다.

두 역할 모두 lock table의 제한된 UPDATE 권한을 갖는다. 제품에서 해당 표의 실제 UPDATE는 실행하지 않는다. cleanup에 업무/capacity UPDATE 권한이 없다는 계약과 lock의 FOR UPDATE authorization용 UPDATE 권한을 구분한다. grant 확대·schema 전체 UPDATE·LOCK TABLES·advisory lock·GET_LOCK/RELEASE는 추가하지 않았다.

## cleanup 실패와 원인 범위

원격에서 확정된 사실은 `CLEANUP_VERIFY_DRY_RUN / CLEANUP_SQL_FAILURE / 1235 / 42000`이다. 정확한 JDBC 호출·SQL 원문·원격 예외 메시지는 수집하지 않았으며 공개하려고 재연결하지 않았다. 따라서 두 mode 중 정확한 실패 지점이나 특정 SQL을 관측 사실로 단정하지 않는다.

**원인 판정은 NARROWED다.** 현재 제품 `DemoCleanupService.execute()`는 VERIFY/DRY_RUN 진입 시 REPEATABLE_READ → `connection.setReadOnly(true)` → autoCommit(false) 순서로 설정한다. 승인된 cache의 실제 Connector/J9.4.0 bytecode를 읽어, 기본 `readOnlyPropagatesToServer=true`/`useLocalSessionState=false`와 서버 버전 조건에서 이 호출이 `SET SESSION TRANSACTION READ ONLY`로 전달되는 것을 확인했다. 현재 probe는 이 옵션을 override하지 않는다. [Connector/J 공식 속성 문서](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-performance-extensions.html)

TiDB 문서는 READ ONLY 관련 구문의 미구현 동작과 `tidb_enable_noop_functions`의 기본 OFF를 설명하며, ON으로 구문을 받아도 실제 read-write 상태가 유지될 수 있다고 명시한다. 이는 읽기 전용 세션 설정 호환성을 강한 후보로 지지한다. 그러나 **대상의 실제 noop 변수값이나 실패한 statement는 관측하지 않았으므로 문서 기본값을 원격 관측값으로 사용하지 않는다.** [TiDB 공식 시스템 변수 문서](https://docs.pingcap.com/tidb/stable/system-variables/#tidb_enable_noop_functions-new-in-v40)

제품 SQL/격리 수준/read-only/권한/DDL/driver는 변경하지 않았다. read-only 호출 제거, driver 전파 차단, noop ON은 실제 DML 보호 의미를 바꿀 수 있으므로 이번에 적용하지 않는다.

1235를 expected privilege denial이나 BUSY로 추가하지 않았고, 오류를 무시하거나 VERIFY/DRY_RUN을 skip하지 않았다. 새 검증기 결함으로 확인된 사항이 없으므로 실패를 숨기는 도구 교정도 하지 않았다. 원격 결과는 FAIL 그대로이며 후속 cleanup 기능은 BLOCKED다.

## 예산과 정리

|항목|이번 새 task|
|---|---:|
|검증 선예약 / 정리 선예약|2000 / 500|
|총예약 / 승인 상한|2500 / 2500|
|재사용 가능한 잔여 예약|0|
|관측 work / cleanup command-equivalents|836 / 45|
|관측 합계|881|
|미사용 예약|1619 — 환급/재사용0|
|JDBC 연결|4/4|
|원격 실행 / 자동 retry / 결과 뒤 추가 연결|1 / 0 / 0|
|생성한 schema / 제한 계정|1 / 2|
|정상 complete V1 dataset|1|
|별도 rollback fixture|User/marker 한 묶음, rollback 확인|

내부 안전 계수·예약이며 TiDB RU/청구량/결제 측정이 아니다. 이번 task만의2500이며 과거 원장의 소진분은 합치거나 재사용하지 않는다. 과거 state/marker/budget/ledger는 접근·수정하지 않았다. 이번 marker/budget은 영구 보존하며 재실행에 사용할 수 없다.

실패 뒤 finally는 기존 네 연결의 범위에서 정확한 소유 fixture를 정리했다. `ownedDataRemoved`, `ownedAccountsAbsent`, `ownedSchemaAbsent`, `clientsClosed`, `cleanupComplete` 모두 true다. 생성 계정2/schema1의 실제 부재 확인, child 종료, 강제 종료0, private 전달 파일 제거, 소유 임시 build/cache 복사본 정리 PASS다. 기존 dependency cache나 다른 사용자의 자원은 제거하지 않았다.

독립 로컬 확인에서도 해당 검증 Java/runner 프로세스 잔존0이다. 두 번의 로컬 compile와 원격 child의 finally 완료 및 진단 임시 디렉터리 미보존을 확인했다. `owned_ephemeral_local_resources=0`과 별개로 private 감사 자료 및 재실행 방지 원장은 의도적으로 보존한다. 연결 종료 예정이나 TTL을 정리 PASS로 사용하지 않았다.

## 범위·보존·재현

이번 Upstash 연결/Redis command/HTTP login/JWT-session API/FE/E2E/AI는 모두0이다. Redis Lua/TTL/rotation, 두 번째 V1 dataset, unsafe Job/financial fixture, concurrent HTTP login, Redis failure, ACK-loss, commit ambiguity, provider failover는 REMOTE_NOT_EXERCISED다. 기존 BE569·FE252·Chromium2회는 이전 로컬 검증 근거이며 이번 전체 재실행0이다.

시작 기준594개 파일과 미커밋 작업을 보존했다. 기존 변경은 STATUS 최신 항목 추가뿐이며 과거 STATUS 본문·보고서/evidence·제품·기존 테스트·HEAD/index는 그대로다. 출력 분리 진입점1개와 이번 보고서·evidence만 추가한다. 공개 scanner 규칙/allowlist 변경0, 기존49개 경고는 별도 부채로 유지한다. [최종 보존·비노출 감사](evidence/DEMO_ADMISSION_LOCK_TIDB_RETRY/final-audit/summary.json)

사용한 진입점은 다음과 같다. 실제 `--execute`는 이번 task에서 이미 한 번 사용했으므로 재현 명령을 그대로 반복할 수 없다. 이후 원격 확인은 별도 승인/새 task/새 예산이 필요하다.

```sh
# 원격 연결 없음
python3 -B scripts/verification/demo_admission_lock_tidb_retry.py --compile-only --cache-seed <owned-cache>
# 별도 승인 시에만: 이번 task는 재사용 금지
python3 -B scripts/verification/demo_admission_lock_tidb_retry.py --execute --cache-seed <owned-cache> --state-dir <new-private-task>
```

다음 한 건은 **TiDB에서 cleanup의 읽기 전용 transaction 호환성 원인을 확정하고, DML0·일관된 조회·기존 권한을 보존하는 최소 대응을 결정하는 것**이다. 현재1235 오류를 우회하거나 제품 정책을 즉시 완화하지 않는다. 접속정보 재입력·비밀번호 재설정·권한 확대는 필요하지 않다.

공개 abuse guard·mobile Chart·cold-start242.337초>FE240초·scanner debt49·commit/push·실제 Render/Cloudflare 배포·공개 URL E2E는 계속 남는다. 이번에 stage/commit/push/PR/merge/서비스 생성/배포는 하지 않았다.
