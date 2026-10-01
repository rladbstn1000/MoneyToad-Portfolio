# Demo admission lock — 실제 TiDB 최소 검증

2026-10-01. **원격 실행은 FAIL, 후속 admission/cleanup 기능 검증은 BLOCKED다.** 실제 TiDB의 인증서 검증 연결과 10-table schema는 통과했지만, 금지 작업의 권한 거절을 분류하는 검증 도구가 TiDB 오류를 인식하지 못해 중단됐다. 이번 task의 원격 연결은 한 번의 실행에서 4회이며 재실행하지 않았다. 만든 스키마·두 계정의 실제 부재와 client/process 정리는 PASS다.

```text
REMOTE_DEMO_SCHEMA_VERIFIED=true
REMOTE_DEMO_LOCK_PRIVILEGES_VERIFIED=false
REMOTE_DEMO_ADMISSION_VERIFIED=false
REMOTE_DEMO_CLEANUP_VERIFIED=false
REMOTE_DEMO_CAPACITY_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```

제품·DDL·권한 정책은 변경하지 않았다. [13단계](13-demo-admission-lock.md)의 LOCAL_DEMO_CAPACITY_READY/LOCAL_DEMO_CLEANUP_READY=true 및 BE569·FE252·Chromium 독립2회 PASS는 기존 로컬 근거다. 이번에 전체 suite를 다시 실행한 결과가 아니다. 이전 일반 TiDB/Upstash 기능 PASS도 새 lock-table 계약의 PASS로 바꾸지 않는다.

## 실제 관측과 중단 지점

근거: [실제 단일 실행 summary](evidence/DEMO_ADMISSION_LOCK_TIDB/probe-0310925ef2b14aa2/summary.json). 아래 결과는 실행 당시 소스로 생성한 원본 summary이며, 실행 뒤 도구를 보완해도 덮어쓰지 않았다.

|계약|결과|관측 범위와 한계|
|---|---|---|
|지정 입력 파일 안전 조건·입력 불변|PASS|소유자/regular file/링크/권한 검사, TiDB 4필드만 전달. 값·접속주소 비공개|
|TLS VERIFY_IDENTITY|PASS|admin/runtime A/runtime B/cleanup 네 연결에서 검증된 TLS와 실제 cipher 존재 확인|
|실제 TiDB version|PASS|정제된 `8.0.11-TiDB-v8.5.3`만 저장|
|사전 부재·소유 원장·DDL/grants|PASS|새 빈 schema1·제한 계정2. baseline7 → V001 → V002 → 제한 grant 순서|
|현재 제품 schema 검사|PASS|10개 테이블, lock singleton, capacity 초기 count0/max1. 제품 기본 max1000은 불변|
|metadata 가시성|PASS|cleanup 역할이 소유 schema의 table10/FK4를 볼 수 있음. admin의 소유 schema 참조 외부 FK 검사0|
|제한 권한 전체 matrix|FAIL / 미완료|`PRIVILEGES`에서 `EXPECTED_PRIVILEGE_DENIAL`, vendor8121/SQLState HY000으로 중단. 각 역할의 허용/거절 전체를 PASS로 판단할 수 없음|
|READ_COMMITTED·pessimistic 실제 확인|BLOCKED|이번 실행은 해당 확인 단계 이전에 중단. 이전 task 결과를 이번 관측으로 사용하지 않음|
|NOWAIT 경합·3572/HY000·commit/rollback 잠금 해제|BLOCKED|`nowaitSqlState=NOT_OBSERVED`. 숫자0은 오류 코드 관측이 아니라 미관측 초기값|
|제품 DemoAdmissionStore rollback/COUNT/FULL|BLOCKED|권한 단계에서 중단해 실제 제품 transaction 경계에 도달하지 못함|
|V1 dataset315행·mutable category·validator|BLOCKED|기록된 fixture User0, seed/marker 생성0, aging 미실행|
|제품 cleanup VERIFY/DRY_RUN/APPLY|BLOCKED|실행하지 않음. 아래 finally 자원 정리와 다른 계약|
|이번 task의 finally 자원 정리|PASS|소유 data 잔존0, schema 부재, 두 계정 부재, client 종료를 실제 확인|

metadata 결과는 **해당 역할에 보이는 현재 소유 schema**의 범위다. 숨겨진 서버 전체 schema/FK까지 검사했다고 주장하지 않는다. 권한은 dataset 생성 전에 검사했고, 중단 뒤 업무 데이터를 추가하지 않았다. 제품 public-demo 로그인·JWT·JPA seed 흐름을 실행한 것이 아니다.

## 원인과 최소 도구 교정

**검증 도구의 권한 거절 코드 분류 누락은 CONFIRMED다.** 실행 당시 음성 권한 검사 함수는 MySQL의 1142/1143 + 42000만 인정했다. TiDB는 해당 금지 작업에 8121/HY000을 반환했고 도구가 이를 예상한 거절로 분류하지 못했다. TiDB 공식 문서는 8121을 권한 검사 실패로 설명한다. [TiDB 오류 코드](https://docs.pingcap.com/tidb/stable/error-codes/)

실행 당시 결과에는 역할·개별 SQL 태그가 없었다. 따라서 어느 역할의 어느 SQL이었다고 관측 사실처럼 소급 기재하지 않는다. 이 실패를 제품 잠금 획득이나 admission 자체의 실패로 단정하지도 않는다. 남은 실제 공급자 계약은 UNKNOWN/미검증이다.

원격 실행 뒤 **검증 도구만** 최소 보완했다.

- 명시적인 금지 작업 검사에서만 8121/HY000을 정상 권한 거절로 인식한다.
- 허용돼야 하는 작업에서 발생한 8121, 잘못된 SQLState/미지 코드, 금지 SQL의 성공은 계속 실패한다.
- 제품 BUSY 판정은 정확한 3572/HY000만 유지한다. 제품 오류 매핑·SQL·권한·transaction·timeout은 변경하지 않는다.
- 이후 음성 검사 실패에는 검토된 고정 역할/연산 태그만 남긴다. SQL 원문·계정·매개변수·예외 메시지는 공개하지 않는다. 새 태그는 과거 결과를 보충한 관측이 아니다.

관련 **Java20/Python20 PASS**, failure/error/skip0이다. 기존 actual workflow 합성 예산 검사도 유지했다. 보완한 코드의 원격 실행은 **NOT_PERFORMED**이며 연결0이다. 원래 원격 FAIL은 유지한다. [실행 뒤 로컬 교정](evidence/DEMO_ADMISSION_LOCK_TIDB/post-remote-local-correction-01/summary.json)

## 예산·소유 자원·정리

기존 provider/capacity task·marker·budget·ledger를 열거나 수정하지 않고 별도 private task를 생성했다. 지정 입력은 불변이며 private 전달 파일은 정리 확인 뒤 제거했다. 새 원장은 재실행 차단을 위해 보존한다.

|항목|이번 실행|
|---|---:|
|선예약 검증 / 정리|2000 / 500|
|누적 선예약 / 승인 총상한|2500 / 2500|
|재사용 가능한 예약 잔여|0|
|관측 command-equivalent 검증 / 정리|279 / 40|
|관측 합계|319|
|미사용 예약|2181 — 환급·재사용하지 않음|
|JDBC 연결 / 상한|4 / 4|
|schema 생성 / 제거 확인|1 / 1|
|제한 계정 생성 / 부재 확인|2 / 2|
|fixture User·dataset·marker 생성|0|
|추가 원격 retry / 보완 뒤 원격 실행|0 / 0|

이 수치는 검증기의 내부 안전 예약·계수이며 공급자 RU/과금/청구량 측정이 아니다. 연결4회를 이미 사용했으므로 제5의 복구 연결을 열지 않았고, 남은 내부 사용 여유를 새 실행 승인으로 전용하지 않았다.

finally는 기존 연결을 사용해 정확한 소유 자원만 정리했다. `ownedDataRemoved`, `ownedAccountsAbsent`, `ownedSchemaAbsent`, `clientsClosed`, `cleanupComplete` 모두 true다. child 종료 확인, 강제 종료0, private 전달 파일 잔존0이다. 독립 로컬 확인에서도 이번 Java 프로세스 잔존0을 확인하고 정확한 소유 임시 컴파일/복사 cache 디렉터리만 삭제했다. 기존 dependency cache·다른 프로세스/컨테이너는 정리하지 않았다. [로컬 정리](evidence/DEMO_ADMISSION_LOCK_TIDB/local-cleanup-01/summary.json)

감사 기록·영구 task marker/budget/정리 완료 원장은 의도적으로 남겨 두었다. `owned_ephemeral_local_resources=0`은 실행 프로세스·전달 파일·임시 build 자원을 뜻하며, 재실행 방지 기록을 삭제했다는 의미가 아니다.

## 실행 전 합성 검사와 이력

현재 제품 Store/Schema/Cleanup을 사용하는 전용 Java 진입점과 SQL 계수 경계를 추가했다. 정상/실패/interrupt/finally를 원격 없이 검사하고 Java17/Python19 및 compile PASS 뒤에만 실제 연결했다. 전체 BE 테스트를 공급자에 연결하지 않았다. [실행 전 로컬 검사](evidence/DEMO_ADMISSION_LOCK_TIDB/local-contracts-01/summary.json)

|합성 경로|관측 결과|
|---|---|
|정상 actual probe 흐름|work1180/cleanup40/connection4|
|work failure 주입|776개 위치, 최대 work1180/cleanup47/connection4|
|interrupt 주입|776개 위치, 유한 종료와 소유 정리 경계 확인|
|cleanup failure 주입|40개 위치, 실패를 cleanup PASS로 바꾸지 않음. 다섯 번째 연결·예산 차용0|
|Spring transaction 종료 후 선택적 reset 오류|8개는 설치된 Spring이 복구함. 실제 주입·해당 cleanup stack·전체 제품 assertion/정리/예산 유지일 때만 복구로 분류|

이는 **명령 dispatch 전 합성 실패**에 대한 유한 실행 증거다. 원격 ACK-loss/commit ambiguity/provider 장애 무손실 보장은 아니다. 코드의 hard cap은 work2000/cleanup500/연결4이며 각 JDBC 연결 초기화32를 별도로 보수적으로 계수한다.

초기 도구 준비 실패도 보존했다. Python draft allowlist 불일치1, Java 합성 metadata label/column 해석 오류, Spring의 transaction 종료 reset 복구에 대한 시험 가정 오류를 순차 수정했다. 초기 원격 미실행 [COMPILE_FAILED summary](evidence/DEMO_ADMISSION_LOCK_TIDB/probe-91c293f6c5124312/summary.json)는 연결0/예약0이며 기능 RED나 공급자 실패가 아니다. 원격 뒤 최종 Python 재확인에서 한 번 잘못된 module search path로 import 실패했고, 작업 디렉터리만 바로잡아20개가 통과했다. 기대값·skip·제품 assertion은 약화하지 않았다.

로컬 Python 재현은 `scripts/verification`에서 `python3 -B -m unittest test_demo_admission_lock_tidb`다. Java는 runner의 `--compile-only --cache-seed <owned-cache>`로 실제 제품 소스와 전용 safety test를 임시 디렉터리에 컴파일한다. `--execute`는 별도 원격 승인과 새 private task가 필요한 경계이며, 이번 task는 재사용할 수 없다.

## 파일 변경·보존·공개 검사

신규 검증 파일:

- `scripts/verification/demo_admission_lock_tidb.py`: 기존 안전 입력/프로세스 원시 도구 재사용, 새 상태·예산·실행 경계와 정제된 결과
- `scripts/verification/test_demo_admission_lock_tidb.py`: 입력·원장·예산·실행 전 합성 proof gate·결과 비노출
- `be/src/test/java/com/potg/verification/admissionlock/AdmissionLockTiDbProbe.java`: 현재 제품 Store/Schema/Cleanup과 실제 TiDB 경계
- `be/src/test/java/com/potg/verification/admissionlock/AdmissionLockSqlBudget.java`: SQL/metadata/transaction/연결 유한 계수
- `be/src/test/java/com/potg/verification/admissionlock/AdmissionLockSyntheticJdbc.java`: 네트워크 없는 합성 JDBC 모델
- `be/src/test/java/com/potg/verification/admissionlock/AdmissionLockTiDbProbeSafetyTest.java`: 실제 probe 흐름의 정상/오류/정리·정제 계약

새 보고서/evidence와 STATUS 최신 항목만 추가한다. 제품/DDL/기존 테스트·과거 문서/evidence·기존 STATUS 본문·HEAD/index를 보존한다. 시작 시 기록한581개 파일 중 기존 변경 대상은 STATUS뿐이다. [최종 보존 감사](evidence/DEMO_ADMISSION_LOCK_TIDB/final-audit/summary.json)

공개 scanner는 규칙/allowlist를 바꾸지 않고 **FAIL49 = 기존38 + 새 source11**로 유지한다. 새11개는 합성 fixture의 예약 email2, SQL 컬럼/모델 필드 표현6, 실행 중 생성하는 비밀번호 변수2, 합성 비노출 canary1이다. 실제 secret/실행 identity나 evidence 값의 탐지와 구분하며 자동 PASS로 바꾸지 않는다. 공개 전 source-site 검토 부채로 남긴다.

이번에 Upstash 연결/Redis 명령/demo login/JWT-session API/FE/E2E/AI 호출은 모두0이다. 별도 unsafe Job/financial fixture, 두 번째 complete seed, concurrent HTTP login, Redis failure, ACK-loss/commit ambiguity, provider failover는 REMOTE_NOT_EXERCISED다.

## 다음 한 건

**보완한 음성 권한 분류를 사용한 새 독립 TiDB 최소 probe**가 남았다. 이번 실행의 연결4/4·선예약2500은 소진 상태로 보존하며 환급/초기화하지 않는다. 다음 실행에는 별도 승인한 새 task·유한 예산/연결 상한이 필요하다. 접속정보 재입력·비밀번호 재설정·권한 확대는 필요하지 않다. 그 실행에서 제한 권한 matrix → 실제 NOWAIT → admission rollback/COUNT/FULL → mutable V1 → 제품 cleanup을 차례로 검증해야 한다.

공개 abuse guard·mobile Chart·cold-start242.337초>FE240초·scanner debt49·commit/push·실제 Render/Cloudflare 배포·공개 URL E2E도 남아 있다. 이번 결과로 공개 준비 완료를 선언하지 않는다. stage/commit/push/PR/merge/서비스 생성/배포는 하지 않았다.
