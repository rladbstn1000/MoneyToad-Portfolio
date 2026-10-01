# 16. TiDB-compatible cleanup read-only transaction 경계

기준: 2026-10-01, 현재 공개 저장소의 미커밋 배포 준비 변경 위에서 수행했다. 이번 범위는 maintenance cleanup JDBC 입력·읽기 guard·로컬 회귀다. 실제 TiDB/Upstash 연결, provider 입력 파일/private task state 접근, 원격 예약, stage/commit/push/배포는 모두 0이다.

## 결론과 과거 실패의 경계

**로컬 구현·전체 회귀 PASS**다. 최종 BE633(일반44클래스541 + Render3클래스92), FE252, 타입·OAuth/demo build·lint0/0, Chromium 독립2회가 통과했다. 최종 필수 suite failure/error/skip0, 소유 자원 정리 PASS다. 최초 검증 실패2건은 아래에서 원인·교정을 별도로 기록했다.

```text
LOCAL_DEMO_CAPACITY_READY=true
LOCAL_DEMO_CLEANUP_READY=true
REMOTE_DEMO_CAPACITY_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```

15단계 실제 TiDB `8.0.11-TiDB-v8.5.3`에서는 schema·제한 권한·NOWAIT·admission rollback·V1 315행·COUNT/FULL이 PASS였으나 cleanup VERIFY/DRY_RUN 진입이 `CLEANUP_SQL_FAILURE`, vendor1235/SQLState42000으로 실패했다. APPLY는 미도달이었다. 그 실행은 정확한 실패 statement와 서버 noop 설정을 기록하지 않았으므로 **과거 실제 실패의 원인 판정 NARROWED는 그대로 유지**한다. [과거 보고서](15-demo-admission-lock-tidb-retry.md)와 evidence를 덮어쓰지 않았다.

이번에는 설치된 실제 Connector/J9.4.0의 read-only 전달 동작과 동일 실패 분기를 로컬에서 확인했다. 합성1235를 실제 공급자 검증으로 승격하지 않는다. 수정 후 실제 TiDB cleanup VERIFY/DRY_RUN/APPLY는 **NOT_RUN**이다.

## 제품 delta

|파일|변경|
|---|---|
|`be/src/maintenance/java/com/potg/don/maintenance/CleanupCredentials.java`|cleanup 전용 URL에 정확한 `readOnlyPropagatesToServer=false` 필수. 누락/true/다른 표기/encoding/중복/unknown/빈 option 거절. 기존 private file·schema·TLS·timeout 검사 보존.|
|`be/src/maintenance/java/com/potg/don/maintenance/CleanupReadOnlyGuard.java`|VERIFY/DRY_RUN에서 사용하는 고정 SELECT 12종과 정확한 metadata 4종만 허용하는 JDBC 경계.|
|`be/src/maintenance/java/com/potg/don/maintenance/DemoCleanupService.java`|기존 transaction 설정 직후 읽기 모드만 guard로 감싼다.|
|`be/src/maintenance/java/com/potg/don/maintenance/CleanupFailure.java`|고정 오류 코드 `READ_ONLY_GUARD_REJECTED` 추가. SQL/예외 원문은 출력하지 않음.|
|`be/cleanup.env.example`|maintenance 전용 비밀값 없는 예제. 실제 파일은 저장소 밖 private directory700/file600에서 관리.|

runtime DB_URL과 제품 application YAML, runtime TLS/인증/timeout/권한, migration/GRANT, validator, admission, FE는 변경하지 않았다. runtime 설정 테스트에는 cleanup 옵션을 거절하는 음성 사례 1개를 추가했다. `scripts/verification/render_runtime.py`의 해당 클래스 예상 수만 79→80으로 갱신했다.

검증 delta: 신규 guard 단위, 실제 Connector/J/MySQL 통합 테스트, 기존 cleanup 입력 테스트 12사례 추가, 새 `tidb_cleanup_readonly_checks.py`와 3개 Python 경계 검사. 기존 격리 runner의 새 evidence namespace만 추가했다. 과거 selector·assertion·scanner 규칙/분류 allowlist는 유지했다.

## readOnly와 transaction 계약

|모드|JDBC/transaction|서버/응용 경계|종료|
|---|---|---|---|
|VERIFY/DRY_RUN|REPEATABLE_READ → setReadOnly(true) → autoCommit(false)|cleanup URL false로 unsupported READ ONLY 전파 없음. 응용 guard SELECT/metadata only.|rollback|
|APPLY|READ_COMMITTED → setReadOnly(false) → autoCommit(false)|동일 admission lock, 재선택/validator, 정확한 DELETE, COUNT postcondition.|commit; 불명확 결과는 기존 UNKNOWN 유지|

지시서의 APPLY “기존 REPEATABLE_READ” 표기는 현재 코드와 달랐다. 사용자 확인에 따라 **현재 READ_COMMITTED를 유지**했다. APPLY에 읽기 guard를 적용하거나 cleanup DELETE 권한을 제거하지 않았다.

`readOnlyPropagatesToServer=false`는 **서버가 read-only라는 보장이 아니다.** Connector/J의 client read-only 상태와 검사만 남고 서버 transaction READ ONLY 전달은 중단된다. MySQL의 서버 read-only 최적화 이점이 없어지는 maintenance CLI의 성능 trade-off를 수용한다. 최적화 손실의 처리량/시간은 측정하지 않았다. [Connector/J 공식 속성 설명](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-performance-extensions.html)

TiDB의 noop 기능을 켜면 구문을 수용하더라도 쓰기 금지 보장이 생기지 않는다. 그러므로 `tidb_enable_noop_functions=ON/WARN`, 서버 설정 변경,1235 무시/성공 매핑은 사용하지 않았다. [TiDB 공식 noop 설명](https://docs.pingcap.com/tidb/stable/system-variables/#tidb_enable_noop_functions-new-in-v40)

## DML0 경계

Guard는 SQL 동사를 넓게 허용하는 parser가 아니다. 현재 제품의 검토된 고정 SELECT 12종만 공백/대소문자 정규화 후 exact match하며 원 SQL은 변경하지 않는다. unknown SELECT, multi-statement, CALL, locking SELECT, INSERT/UPDATE/DELETE/DDL은 prepared statement 생성 전에 거절한다. statement의 update/execute/batch, connection의 transaction 변경/commit/nativeSQL/unwrap, result의 update/원본 객체 접근도 막는다.

metadata는 현재 schema의 demo 표 3개에 대한 getColumns/getPrimaryKeys/getImportedKeys/getIndexInfo만 허용한다. 반환 rows·statement·connection 접근도 guard 밖 원본을 노출하지 않는다. Connector/J 내부 session/protocol/metadata SQL은 driver 내부에서 실행하므로 application SQL allowlist로 재분류하지 않는다. 회귀에서 실제 metadata 조회와 rollback을 확인했다.

범용 DB 방화벽/관리자 악성 코드 방어를 주장하지 않는다. 이 경계는 제품 VERIFY/DRY_RUN에서 의도치 않은 쓰기나 신규 미검토 query가 실제 JDBC delegate로 나가는 것을 막는다. 미래 cleanup 조회 변경 시 allowlist와 테스트를 함께 검토해야 한다. 제한 cleanup 역할의 DELETE/lock 권한은 APPLY를 위해 그대로 유지한다.

## 실행 결과

|검증|결과|새 근거|
|---|---|---|
|관련 Python 경계|25 PASS, failure/error/skip0|`local-audit-01/summary.json`|
|집중 BE|175 PASS, failure/error/skip0|`focused-02/summary.json`|
|전체 일반 BE|44클래스541 PASS, failure/error/skip0|`be-final-01/summary.json`|
|Render 설정·TLS|3클래스92 PASS; compile/runtime·maintenance package·TLS CLI PASS|`render-final-02/summary.json`|
|전체 BE 합계|**633 PASS**|일반541+Render92; 중복 집중175는 더하지 않음|
|FE|OAuth151+demo101=**252 PASS**, 실패/skip/todo0|`fe-final-01/summary.json`|
|TypeScript/build/lint|제품/test/E2E/Functions 타입·OAuth/demo build·invalid mode 거절·lint0/0 PASS|같은 FE 근거|
|실제 Chromium|독립2회, 각각4 PASS, workers1/retries0, 외부 앱 요청0, API mock0|`browser-final-01/summary.json`|
|소유 자원 정리|최초 실패 포함 모든 실행 PASS|각 summary의 cleanup/source/evidence 확인|

기존569개에 이전 단계에서 추가된 원격 안전 경계 단위20개와 이번44개를 포함한 수다. 이번 추가44는 guard22 + actual-driver9 + URL 음성12 + runtime URL 음성1이며 기존 assertion을 약화하지 않았다. Java 호스트21.0.11, packaged runtime21.0.12.1, 실제 MySQL8.4/ConnectorJ9.4.0을 사용했다.

### 실제 MySQL8.4 + Connector/J9.4.0

- 기본 propagation=true: client isReadOnly=true, session transaction_read_only=1, 드라이버 READ ONLY 전달 1회, RR/autocommit false, rollback 확인.
- false: client isReadOnly=true이지만 session transaction_read_only=0. SELECT 성공, READ ONLY 전달0, RR/autocommit false·rollback 확인. **서버 read-only PASS로 표현하지 않는다.**
- 실제 JDBC 앞 guard: INSERT/UPDATE/DELETE/DDL 준비 delegate0, createStatement0, batch delegate0. 승인된 SELECT만 실제 실행한다.
- APPLY false: client readOnly=false, READ_COMMITTED, 제품의 정확한 5개 DELETE와 COMMIT1, dataset/marker0, capacity/lock singleton 보존.

### driver-level TiDB 오류 합성

실제 Connector/J public QueryInterceptor 경계에서 정확한 READ ONLY session statement만 synthetic CJException1235/42000으로 실패시켰다. 그 외 연결·schema·SQL·데이터는 실제 소유 MySQL이다. 라이브러리는 runtime dependency 그대로이며 교체/업그레이드하지 않았다.

기본 true의 setReadOnly는 해당 SQLException을 노출하고, 동일 제품 VERIFY/DRY_RUN은 기존 SQL_FAILURE로 실패한다. false에서는 해당 statement0·합성 오류0으로 같은 두 제품 mode가 조회/rollback을 완료한다. 실제 TiDB나 프로토콜 전체 에뮬레이션은 아니다.

### 기존 cleanup 계약

기존 `DemoCleanupMySqlTest` 36사례를 유지했다. VERIFY/DRY DML0,24h cutoff/세션 만료, V1 immutable validator, 사용자 category/Budget/profile 수정 허용, unsafe batch 전체 거절, COUNT source of truth, NOWAIT, 정확한 affected rows, 중간 SQL 실패 rollback, COMMIT_UNKNOWN, 자동 retry0, 제한 역할 금지 write/DDL을 재검증했다.

### 최초 검증 실패 보존

`focused-01`은175 중171 PASS/4 FAIL, error/skip0, 소유 자원 정리 PASS였다. 기존 cleanup36·guard22·입력29는 모두 PASS였고 신규 driver 9사례의 4개 parameterized 사례만 공통 행 수 비교에서 실패했다.

원인은 새 테스트의 `long` 결과와 `Integer` 기대값이 AssertJ `isEqualTo(Object)`를 선택한 것이다. 실제 설치 AssertJ3.27.4/JDK21의 작은 합성 재현과 bytecode에서 확인했다. 동일한 숫자를 `.longValue()`로 넘겨 `isEqualTo(long)`을 선택하도록 **신규 테스트 한 줄만 교정**했다. 기대 수치·SQL·제품·기존 assertion은 바꾸지 않았다. 최초 FAIL을 기능 RED나 TiDB FAIL로 재분류하지 않는다.

`render-final-01`은 configuration86개 PASS 뒤 packaged cleanup CLI에서 중단됐다. 기존 로컬 fixture가 새 필수 옵션 없는 URL을 만들었기 때문이다. 검증용 maintenance 입력에만 false를 추가하고 runtime URL·성공 기대값은 보존했다. 이 실행의 자원 정리도 PASS이며 이후 새 label로 재실행했다.

별도 로컬512MiB/0.1CPU 패키지 시험도 PASS다. 준비240.037초, 최대 관측423.0MiB, login21.630/8.999초, OOM=false, 정상 정리exit143이었다. **240.037초>FE240초**이므로 cold-start 대기 상한 문제는 여전히 미완료다. 실제 Render/TiDB/Upstash 성능 또는 장애전환 보장을 뜻하지 않으며 timeout/heap/CPU 정책을 바꾸지 않았다.

## 재현과 evidence

저장소 루트에서 실행하며 placeholder는 검증한 소유 경로로 대체한다. 원격 provider input/state를 읽지 않는 로컬 runner다. 기존 Java21·MySQL8.4 이미지·loopback Redis·정확한 FE lockfile·Chromium 설치를 사용하고 raw 로그/DB identity는 공개하지 않는다.

```sh
python3 -B scripts/verification/tidb_cleanup_readonly_checks.py --phase be --focus --run-label <new-focus> --cache-seed <owned-cache>
python3 -B scripts/verification/tidb_cleanup_readonly_checks.py --phase be --run-label <new-be> --cache-seed <owned-cache>
python3 -B scripts/verification/tidb_cleanup_readonly_checks.py --phase render --run-label <new-render> --cache-seed <owned-cache>
python3 -B scripts/verification/tidb_cleanup_readonly_checks.py --phase fe --run-label <new-fe> --dependencies <exact-lockfile-dependencies>
python3 -B scripts/verification/tidb_cleanup_readonly_checks.py --phase browser --run-label <new-browser> --cache-seed <owned-cache> --dependencies <exact-lockfile-dependencies> --browser-path <owned-browser>
```

새 실행 결과는 [TIDB_CLEANUP_READONLY](evidence/TIDB_CLEANUP_READONLY/)에만 저장했다. 각 runner가 소유 자원 정리를 실제 확인하고 이전 evidence/source 보존 여부를 기록한다. 기존 과거 보고서/evidence와 STATUS 이전 본문, HEAD/index를 보존했다.

공개 scanner는 **FAIL51**을 그대로 기록한다. 기존49개 source 분류 부채에 새 actual-driver test의 환경변수 대입1·reserved invalid domain 합성 fixture1 후보가 추가됐다. 수동 내용 검토에서 실제 credential/개인정보나 금지 evidence는 발견하지 못했지만 자동 PASS로 바꾸지 않았다. scanner rule/분류 allowlist 변경0이다.

시작600개 파일 중 기존 수정은 maintenance3·입력1·runtime 음성 테스트1·runner2·STATUS1로 제한했다. 과거 보고서/evidence183개와 이전 STATUS 본문, 기존 미커밋 파일의 나머지 내용·모드, Git HEAD/index를 보존했다. 새 public evidence는 비밀값 없는 JSON 요약뿐이며 raw SQL/URL·credential·실제 사용자 식별자·local path·PID·컨테이너 identity는 저장하지 않았다. 최종 audit는 [local-audit-01](evidence/TIDB_CLEANUP_READONLY/local-audit-01/summary.json)에 있다.

## 다음 한 건: cleanup-only 실제 TiDB 최소 probe 설계

**아직 승인·예약·실행하지 않은 제안**이다. 지난 task2500/2500, 실패 결과/marker/ledger/미사용 예약은 접근·수정·환급·재사용하지 않았다.

- 새 task, 새 전용 schema1, 제한 cleanup 계정1.
- setup·finally 정리 admin 연결1 + 제한 cleanup 연결1, 최대2연결. admin은 fixture/DDL/정리만 수행하고 제품 cleanup에 사용하지 않는다.
- V1 fixture315행을 코드로 설치하고 적격24h/세션만료 조건을 준비한다. 원래 fixture 기준값, COUNT, 무관 표 불변을 관찰한다.
- cleanup 전용 false/TLS URL로 실제 제품 VERIFY→DRY_RUN→APPLY. 모드별 SELECT/DML 수, rollback/commit, exact delete·COUNT 확인. 기존 product validator·schema·최소 권한 검사를 생략하지 않는다.
- 동일 cleanup 연결을 재사용하려면 각 모드 rollback/commit과 상태 초기화 확인이 필요하다. 불명확 결과에 새 연결/retry하지 않는다. 세 mode를 CLI subprocess로 따로 실행하는 검증은 setup1+cleanup3=4연결이므로 위2연결 service probe와 구분한다.
- admission 경합/FULL·로그인·Redis·FE·전체 provider suite는 반복하지 않는다. TLS/대상/제한 역할 확인은 이번 cleanup 실행 자체의 필수 경계다.

잠정 상한은 **work1200 + cleanup300 =1500 내부 command-equivalents**다. work는 연결·초기설정64, DDL/GRANT80, fixture340, metadata240, transaction/cleanup SQL100, 관측100, 여유276으로 계획했다. 공급자 과금량이 아니다. 현재 미구현 후속 진입점의 정상/실패/중단/finally 경로를 합성으로 계산해 유한 상한이 맞는지 증명한 다음 별도 사용자 승인을 받아야 한다. 그 전에는 확정된 실행 예산이나 원격 PASS로 표현하지 않는다.

현재 공개 준비의 나머지 abuse guard·mobile Chart·cold-start/FE 대기 상한·scanner 분류 부채·실제 Render/Cloudflare 연결·공개 URL E2E도 별도다.
