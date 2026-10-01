# Demo admission / cleanup — 실제 TiDB 최소 검증

2026-10-01. **전체 결과 FAIL / 원인 NARROWED**다. 새 독립 원장으로 실제 TiDB 연결 4회와 전용 schema 1개·제한 계정 2개를 사용했다. TLS·스키마·NOWAIT는 통과했지만 첫 admission에서 권한 검사 오류가 발생했다. 후속 기능을 실행하거나 기대값을 완화하지 않았으며 소유 원격 자원과 client/process 정리는 PASS다.

## 최종 판정

```text
REMOTE_DEMO_SCHEMA_VERIFIED=true
REMOTE_DEMO_ADMISSION_VERIFIED=false
REMOTE_DEMO_CLEANUP_VERIFIED=false
REMOTE_DEMO_PRIVILEGES_VERIFIED=false
REMOTE_DEMO_CAPACITY_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```

실제 결과: [단일 원격 실행 요약](evidence/DEMO_CAPACITY_TIDB/probe-4cfe0cfafc494941/summary.json).

|계약|결과|실제 관측과 한계|
|---|---|---|
|입력 안전·불변|PASS|지정 파일 owner/700·600/regular/link 검사, 실행 전후 내용 불변. 기존 입력을 재작성하지 않음|
|JDBC identity TLS|PASS|동일 대상에 setup 1·runtime 2·cleanup 1 연결. VERIFY_IDENTITY 유지, 네 연결 모두 실제 TLS cipher 확인|
|명시적 DDL|PASS|검토된 baseline 7개 CREATE와 신규 migration 2개 CREATE+초기행을 정확한 bytes 확인 후 적용. IF NOT EXISTS 없음|
|신규 schema 계약|PASS|9개 테이블, demo_visit PK/RESTRICT FK/DATETIME(6)/created_at,user_id index, singleton count=0/max=2 검사. 제품 기본1000 불변|
|트랜잭션·NOWAIT|PASS|실제 READ_COMMITTED·pessimistic 확인. A가 lock 보유, B의 실제 오류3572/HY000, 제품 BUSY 분류 일치|
|admission 원자성|FAIL|rollback 시나리오의 첫 claimSlot에서 DEMO_ADMISSION_UNAVAILABLE, SQL8121/HY000. User INSERT 전에 중단|
|count+User+marker rollback|BLOCKED|해당 쓰기 묶음을 완료하지 못했으므로 의도적 rollback 보장으로 표시하지 않음|
|V1 dataset·수정 허용|BLOCKED|User/Card/Transaction/Budget/marker 설치에 도달하지 않음|
|runtime/cleanup 권한 matrix|BLOCKED|계정 생성·명시 GRANT·읽기 관측만으로 쓰기/거절 계약을 PASS 처리하지 않음. 실제 admission 권한 실패는 위 FAIL로 기록|
|제품 cleanup verify/dry-run/apply|BLOCKED|호출 전 admission 실패. 관리자의 이번 자원 teardown과 구분|
|unsafe batch 전체 거절|BLOCKED|unsafe fixture 생성0; 제품 cleanup 미실행|
|메타데이터 가시성|PASS, 범위 제한|cleanup 계정에서 자기 schema의 9표·4FK 확인. admin의 해당 schema 참조 외부 FK 조회0. 숨겨진 타 schema까지 보인다는 보장 아님|
|소유 원격 자원·client 정리|PASS|소유 표의 잔존 데이터 제거/빈 상태 확인, 두 계정과 schema 삭제 후 실제 부재 확인, client/process/group 종료|
|원격 commit ambiguity|REMOTE_NOT_EXERCISED|실제 장애·응답 유실을 유발하지 않음. 기존 LOCAL PASS를 원격 PASS로 바꾸지 않음|

Upstash 연결·Redis 명령·demo HTTP login·FE/E2E·전체 BE 실행은 모두0이다. 이번 probe는 제품 `DemoAdmissionStore`를 실제 JDBC/Spring DataSourceTransactionManager에 바인딩하여 같은 물리 connection에서 호출한다. 전체 JPA 로그인·Redis·HTTP 앱 기동 검증이 아니다. cleanup은 배포 JAR과 같은 `DemoCleanupService` 클래스를 호출하도록 구현했지만 원격 실행은 해당 단계에 도달하지 않았다.

## 오류 해석과 최소 후속 후보

확인된 사실은 `ROLLBACK` 단계에서 첫 `DemoAdmissionStore.claimSlot()`이 SQL vendor8121 / SQLState HY000으로 실패했다는 것이다. private 소유 원장의 User 기록0과 제어 흐름을 대조했다. User INSERT 실패였다면 별도의 fixture 오류가 되므로 이 결과를 User 생성 실패로 해석하지 않는다. 비밀값·SQL 원문·전체 예외 메시지는 저장하지 않았다.

[TiDB 오류 문서](https://docs.pingcap.com/tidb/stable/error-codes/)는8121을 권한 검사 실패로 설명한다. 현재 probe의 runtime은 필요한 표 SELECT와 `demo_capacity`의 `UPDATE(resident_count)`만 갖는다. 같은 읽기/lock 경로가 통과한 점에서 **조건부 counter UPDATE와 열 단위 UPDATE 권한의 호환성**이 유력하다. 그러나 실패 SQL을 식별하는 고정 단계 태그와 실제 서버 버전을 저장하지 않았으므로 그 SQL이나 서버 버전 원인을 확정하지 않는다.

[TiDB 열 단위 권한 문서](https://docs.pingcap.com/tidb/stable/column-privilege-management/)는 지원 시작을8.5.6으로 명시한다. 문서 설명은 현재 관리형 인스턴스의 지원 관측을 대신하지 않는다. 이번에는 VERSION의 TiDB 여부만 확인했으므로 구버전 또는 Starter 미지원이라고 단정하지 않는다.

다음 한 건은 **별도 승인·새 한도의 열 단위 UPDATE 권한 확인**이다. 정제한 서버 버전, 정확한 소유 계정의 GRANT/열 권한 존재 여부, resident_count UPDATE 성공, max_visitors UPDATE 거절, 제품 조건부 UPDATE를 각각 고정 태그로 확인해야 한다. 테이블 전체 UPDATE 부여, setup credential로 runtime 대체, 오류를 BUSY/PASS로 변경하는 방법은 채택하지 않는다. 열 단위 권한 미지원이 확정된다면 변경 불가 max 설정과 쓰기 가능한 counter를 물리적으로 분리하는 후속 설계를 검토한다. 이번에는 제품 SQL·migration·grant 의미를 변경하지 않았다.

## 예산과 자원 소유

기존 `moneytoad-deploy03-provider-state`의15,568/15,568 원장·marker에는 접근하거나 수정하지 않았다. 새 private task `moneytoad-deploy-capacity-tidb-20261001-state`에 별도 immutable 실행 marker/예산과 write-ahead 소유 원장을 만들었다. 실제 대상 이름·계정·입력 checksum은 공개 문서에 넣지 않는다.

원격 시작 전 정적 상한:

|분류|command-equivalent 상한|
|---|---:|
|연결 초기화4×32|128|
|스키마 검사5회×메타데이터7회×8|280|
|DDL·GRANT|110|
|V1 두 방문자 INSERT|630|
|admission·lock·트랜잭션 상태|130|
|제품 cleanup SQL|150|
|권한 거절·관측|72|
|검증 합계|1500|
|별도 정리 선예약|500|
|총 선예약|2000|

설치된 Connector/J9.4.0의 기본 metadata 경로를 읽기 전용으로 확인했다. 정확한 catalog/table에 대한 메타데이터 호출에8, 단일 연결 초기화에32를 보수적으로 계상한다. 준비된 INSERT도 행별 실행이며 batch·multi-query·자동 reconnect를 사용하지 않는다. JDBC 실행/트랜잭션 상태/메타데이터별 차감은 호출 전에 이루어지고, 검증1500과 정리500을 서로 전용하지 않는다. 이것은 내부 안전 예약이며 TiDB RU·wire 명령 수·결제량 측정이 아니다.

이번 관측은 **검증280 + 정리37 = 317 command-equivalents**, 실제 JDBC 연결4/허용8이다. 전체2000을 선예약한 marker를 보존하므로 잔여 재실행 예약은0이다. 미사용 예약1683을 환급하거나 다음 실행 한도로 재사용하지 않았다.

schema1·runtime account1·cleanup account1은 생성 전에 정확한 이름을 private 원장에 기록하고 부재를 확인했다. 최초 검증 실패 후 추가 연결·재실행0. User/seed/unsafe fixture0이며 마지막 정리에서는 소유 표의 정확한 PK만 대상으로 잔존 초기 capacity 행 등을 제거했다. 계정·schema 부재를 실제 조회했다. 강제 종료0, child/group 종료, 비밀 전달 파일 삭제, 전용 빌드 복사본 제거 PASS다. 원본 접속정보의 내용·owner·mode·link 조건도 보존했다.

## 검증 도구와 로컬 검사

추가 파일은 다음 다섯 개다. 제품과 기존 테스트·runner를 변경하지 않았다.

- `scripts/verification/demo_capacity_tidb.py`: 새 독립 원장, 비노출 TiDB 입력 전달, 유한 실행, 정리·공개 결과 검증.
- `scripts/verification/test_demo_capacity_tidb.py`: 안전 입력/원장 재사용 거절/상한/공개 allowlist/소유 process group 검사.
- `be/src/test/java/com/potg/verification/capacity/CapacityTiDbProbe.java`: 제품 store/cleanup/scenario를 사용하는 한정 probe.
- `be/src/test/java/com/potg/verification/capacity/CapacitySqlBudget.java`: 연결·SQL·메타데이터 한도와 정제 관측.
- `be/src/test/java/com/potg/verification/capacity/CapacityTiDbProbeSafetyTest.java`: budget·cancellation·metadata/unwrap·client 종료·불명확 transaction·비노출 검사.

최종 Python15 PASS, 실제 원격 실행 전 같은 사전 단계의 Java11 PASS/compile PASS, 실패·오류·skip0이다. 앞선 오프라인 [Java7 준비](evidence/DEMO_CAPACITY_TIDB/probe-8c2431102ba5468f/summary.json), [Java10 준비](evidence/DEMO_CAPACITY_TIDB/probe-43169617591746fb/summary.json)도 보존했다. 이 두 준비 실행의 입력 읽기·원격 연결·예약은0이다.

교차 검토 중 검증기만 보강했다: TLS 후 검사 실패로 아직 변수에 할당되지 않은 client도 추적하여 종료, rollback 실패에도 finally close, 불명확 cleanup 결과에서 autoCommit=true 금지, SQL 메시지 대신 제한된 vendor/state 전달, resource teardown 후 client 종료. 실제 원격 실패 후 기대값이나 제품을 수정하지 않았다.

안전한 로컬 재확인은 `python3 -B -m unittest discover -s scripts/verification -p test_demo_capacity_tidb.py`와 `demo_capacity_tidb.py --compile-only --cache-seed <owned-cache>`다. 원격 `--execute`는 별도 새 승인/예산을 전제로 하며 이번 state를 다시 실행할 수 없다. 전체 BE530/FE252/Chromium2는10보고서의 과거 로컬 결과이며 이번 실행에서 다시 수행한 결과가 아니다.

## 남은 공개 준비

이번 원격 admission/권한 실패와 미실행 cleanup 계약이 우선 남는다. 별도로 abuse guard, mobile Chart, scanner 공개 감사, cold readiness354초 대비 FE240초 한도, 실제 Render/Cloudflare 배포와 공개 URL E2E도 남아 있다. 제품·TLS·timeout·보안 정책, 과거 evidence와 Git HEAD/index를 보존했다. stage·commit·push·PR·merge·배포는 수행하지 않았다.

## 최종 보존·공개 내용 검사

[최종 감사](evidence/DEMO_CAPACITY_TIDB/final-audit/summary.json): 시작549파일 중 기존 STATUS만 승인된 최신 항목을 추가했다. 과거 문서/evidence149개 및 STATUS의 기존 본문은 checksum 일치, HEAD/index 불변, 제품 변경0이다. 실제 입력 내용·owner·mode·link와 기존 scanner 규칙도 보존했다.

자동 공개 scanner는 **FAIL35**를 유지한다(기존27 + 새 source 후보8). 새8개는 검토한 합성 fixture 주소·SQL column 이름·난수 credential 생성 표현·비노출 canary 테스트이며 실제 접속값/런타임 identity가 아니다. HARD secret 탐지0, 새 evidence 금지 필드/값0이다. 규칙/허용 목록을 바꾸거나 자동 PASS로 표시하지 않았다. 공개 scanner 부채는 여전히 별도 정리 대상이다.
