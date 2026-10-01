# 17. TiDB cleanup-only 최소 원격 검증

기준: 2026-10-01. 16단계까지 적용된 현재 미커밋 작업 트리를 그대로 사용했다. 이번에는 새 독립 task로 실제 TiDB cleanup VERIFY → DRY_RUN → APPLY만 한 번 실행했다. 기존 task·marker·예산·소유 원장은 읽거나 수정·환급·재사용하지 않았다.

## 결과

**실제 TiDB 세 모드와 최종 소유 자원 정리 모두 PASS**다. 실제 서버 버전은 `8.0.11-TiDB-v8.5.3`이며 Java21/Connector/J9.4.0을 사용했다. 제품 코드·DDL·권한·runtime 설정·FE 변경은 0이다.

```text
LOCAL_DEMO_CAPACITY_READY=true
LOCAL_DEMO_CLEANUP_READY=true
REMOTE_DEMO_CLEANUP_VERIFY_VERIFIED=true
REMOTE_DEMO_CLEANUP_DRY_RUN_VERIFIED=true
REMOTE_DEMO_CLEANUP_APPLY_VERIFIED=true
REMOTE_DEMO_CLEANUP_VERIFIED=true
REMOTE_DEMO_CAPACITY_VERIFIED=true
PUBLIC_DEPLOYMENT_READY=false
```

마지막 capacity 판정은 [15단계의 실제 admission/schema/권한 결과](15-demo-admission-lock-tidb-retry.md)와 이번 cleanup 결과를 합친 것이다. admission 경합·rollback·FULL을 이번에 다시 실행한 뜻이 아니다. 이번 성공으로 과거 실패의 정확한 statement나 원인을 소급 확정하지 않는다.

## 실제 실행 경계

- 전용 schema 1개, 새 제한 cleanup 계정 1개. write-ahead 원장에 이름을 기록하고 정확한 대상의 부재를 확인한 뒤 생성했다.
- setup/admin 1개 + cleanup 1개 = JDBC 연결 **2/2**. 자동 재연결·실패 retry·추가 connection은 없다.
- admin은 DDL/GRANT/fixture/category 1건/적격 시간 설정과 불변성 관측·최종 teardown에만 사용했다. 제품 세 모드는 같은 제한 cleanup 연결로 실행했다.
- baseline 7개 표 + V001 2개 + V002 1개 = 10개 표, capacity/lock singleton, FK·RESTRICT·index·DATETIME(6) metadata를 확인했다.
- SHOW GRANTS의 정확한 집합은 SELECT 10개 표, DELETE 대상 5개 표, admission lock의 UPDATE 1개 표다. 전역 USAGE 외 global/schema wildcard·추가 권한·role·GRANT OPTION은 허용하지 않는다. 이번에는 금지 SQL 전체 matrix를 재실행하지 않았다.
- 실제 `demoCleanupJar`를 만들고 컴파일 결과와 패키지 클래스 bytes가 모두 같은지 검사했다. 추출한 BOOT-INF/classes를 classpath 맨 앞에 두고 제품 service/credential/schema/scenario의 실제 CodeSource까지 확인했다. 별도 세 CLI process 대신 **현재 패키지의 service 경계**를 같은 연결에서 호출했다.
- 실제 private cleanup config를 제품 `CleanupCredentials`로 읽었다. 정확한 `readOnlyPropagatesToServer=false`, VERIFY_IDENTITY TLS, 기존 5초 connect/10초 socket timeout, 대상 schema·제한 credential 일치를 검증했다. 실제 Connector/J property도 false임을 값 비노출 방식으로 확인했다.

TiDB 전역/noop 정책·인증·TLS·제품 timeout을 바꾸지 않았다. 기존 UTC 세션 설정과 표준 JDBC transaction 상태 처리는 유지했다. 입력 파일은 실행 전후 checksum 비교로 불변을 확인했으며 원문과 checksum은 공개하지 않았다.

## fixture와 모드별 관측

현재 `DemoSeedScenario`의 직접 작성 V1을 admin setup으로 설치했다. User1 + Card1 + Transaction240 + Budget72 + demo_visit1 = **315행**이다. 카드 금융정보와 file/prediction source는 null이며 Job/Peer/Dummy는 0이다. 난수 소비/외부 CSV를 사용하지 않았다.

분류 연습 거래 1건의 category만 카페에서 마트 / 편의점으로 바꿨다. 해당 소유 marker만 DB UTC 기준 25시간 전 생성·1시간 전 세션 만료로 적격화했다. 제품의 24시간 cutoff와 validator는 그대로다. 실제 DRY_RUN/APPLY의 전체 V1 validator가 변경된 category를 허용했다.

|관측|VERIFY|DRY_RUN|APPLY|
|---|---|---|---|
|isolation|REPEATABLE_READ|REPEATABLE_READ|READ_COMMITTED|
|client readOnly|true|true|false|
|autoCommit|false|false|false|
|서버 READ ONLY 전달 관측|0|0|0|
|application delegate DML / DDL|0 / 0|0 / 0|5 / 0|
|driver 관측 DML|0|0|5|
|관측 1235/42000|0|0|0|
|guard 위반|0|0|0|
|rollback / commit|1 / 0|1 / 0|0 / 1|
|candidate / 삭제 방문|0 / 0|1 / 0|1 / 1|
|COUNT 전후|1 → 1|1 → 1|1 → 0|
|전체 fixture 변화|0|0|대상 315행 삭제|

VERIFY와 DRY_RUN은 결과 반환·실제 rollback 확인 후에만 autoCommit true/readOnly false/RR로 정상화했고, 다음 모드 전에 상태를 실제로 읽어 확인했다. 확인된 재사용 초기화는 **2회**다. 불명확한 transaction을 새 연결로 우회하지 않았다.

APPLY는 같은 제한 계정으로 NOWAIT lock·capacity 검증·후보 재선택·row lock·전체 validator를 실행했다. DRY_RUN의 candidate ID를 전달하지 않았다. 제품의 정확한 PK DELETE가 Transaction240 → Budget72 → Card1 → demo_visit1 → User1 순서로 실행되고 affected rows와 COUNT1→0을 확인했다. capacity/lock singleton과 Job/Peer/Dummy는 전 컬럼 불변이다.

위 숫자는 성공을 확인한 제품 assertion 및 실제 JDBC/driver 관측에 근거한다. SQL·매개변수·행 identity는 저장하지 않고 고정 check와 count만 남겼다. `QueryInterceptor`의 관측은 해당 제품 호출 구간의 드라이버 경계이며 별도 서버 전체 감사 로그를 수집한 것은 아니다.

**false 옵션은 서버 transaction이 read-only라는 보장이 아니다.** 안전성은 application SELECT/metadata guard + 제한 cleanup 계정 + 읽기 모드 rollback의 조합이다. VERIFY는 schema/관계/integrity를 확인하고, DRY_RUN/APPLY가 전체 V1 dataset을 검증한다.

## 원격 전 유한 예산 증명

새 진입점 `scripts/verification/tidb_cleanup_remote.py`와 Java cleanup-only probe만 추가했다. 새 Python 안전 검사 **27 PASS**, 새 Java 검사 **12 PASS**, failure/error/skip 0이다. 원격 실행 진입점에서도 동일 Java 12개와 패키지 일치를 다시 통과한 뒤 예약·접속했다. 기존 전체 BE/FE/E2E는 반복하지 않았다.

|같은 검증 코드의 합성 경로|검사 지점|최대 work|최대 cleanup|최대 연결|
|---|---:|---:|---:|---:|
|정상 전체 흐름|work 570, finally 35|912|35|2|
|각 work 경계 early failure|570|912|40|2|
|각 work 경계 interrupt|570|912|40|2|
|각 finally 경계 정리 실패|35|912|35|2|

합성 모델은 현재 제품 service/guard/validator 및 실제 probe run/finally를 실행한다. JDBC 네트워크만 메모리 대역으로 바꿨다. 정상·중단 정리와 모든 정리 실패의 유한 종료를 확인했으며, 정리 실패를 PASS로 바꾸지 않는다. network outage 후 무조건 정리 성공하거나 공급자 RU를 예측한다는 증명은 아니다.

설치된 드라이버의 연결 초기화 allowance와 metadata 40회 × 8을 포함한 command-equivalent를 사용한다. 실행 전 work **1,200** + cleanup **300** = **1,500**을 선예약했다. 실제 이번 실행은 work **912** + cleanup **35** = **947**이다. 미사용 예약 **553**은 환급하지 않으며 재사용 가능한 예약 잔여는 **0**이다. 과거 task 예산과 합치거나 초기화하지 않았다. 공급자 과금·RU·시간당 처리량 측정값이 아니다.

사전 리뷰에서 TiDB SHOW GRANTS가 `SELECT,DELETE`처럼 쉼표 뒤 공백 없이 출력할 수 있음을 확인했다. 새 검증기만 쉼표 분리 후 trim하도록 교정했고, 빈/중복 항목·추가 권한 거절은 유지했다. 실제 원격 실패 후 기대값을 완화하거나 재실행한 것이 아니다. [TiDB v8.5.3 권한 출력 원문](https://github.com/pingcap/tidb/blob/v8.5.3/pkg/privilege/privileges/cache.go)

## 정리·보존·공개 점검

finally에서 소유 데이터 잔존 0, 새 계정 부재, 새 schema 부재를 실제 확인했다. client close, child/process-group 종료, private credential 전달 파일 제거, 전용 build/Gradle 임시 복사본 제거가 모두 PASS다. 강제 종료 없이 정상 정리했다. 새 감사 marker/budget/ownership/result는 private 경로에 보존하며 비밀 전달 파일은 남기지 않았다.

시작 시 616개 파일을 기록했다. 기존 파일 변경은 STATUS의 최신 결과 prepend뿐이며 이전 본문·과거 보고서/evidence·제품·기존 테스트·DDL·미커밋 자료·HEAD/index를 보존했다. 새 evidence는 정제 JSON 요약만 포함한다.

공개 scanner는 **기존 FAIL51 → 현재 FAIL60**이다. 추가 9개는 새 검증 코드의 SQL 열 이름/합성 fixture/비노출 음성 테스트/런타임 난수 비밀번호 생성 구문에 대한 source 후보다. 내용 검토에서 실제 secret/PII를 발견하지 않았지만 자동 분류를 우회해 PASS로 바꾸지 않았다. scanner 규칙·분류 allowlist 변경 0, 새 evidence의 금지 필드/값 탐지 0이다. 후속 공개 정제 부채로 유지한다.

## 실행 근거와 재현 경계

- [오프라인 컴파일·합성 예산](evidence/TIDB_CLEANUP_REMOTE/probe-11fa733b686d4414/summary.json)
- [Python 안전 검사27](evidence/TIDB_CLEANUP_REMOTE/python-safety-01/summary.json)
- [실제 TiDB 1회 결과](evidence/TIDB_CLEANUP_REMOTE/probe-83c0f479b0034f71/summary.json)
- [보존·정제 감사](evidence/TIDB_CLEANUP_REMOTE/local-audit-01/summary.json)

```sh
python3 -B scripts/verification/tidb_cleanup_remote.py --compile-only --cache-seed <owned-cache>
python3 -B scripts/verification/tidb_cleanup_remote.py --execute --cache-seed <owned-cache> --state-dir <new-private-cleanup-task>
```

첫 명령은 provider input/state/네트워크를 사용하지 않는다. 두 번째는 새 독립 승인·예산·private task가 필요하며 기존 state를 재사용할 수 없다. 이번에 두 번째 명령은 **단 한 번** 실행했다. 실제 호스트·계정·schema 이름·접속 URL·비밀번호·행 identity·local path·원문 SQL/로그는 공개 evidence에 없다.

BE633/FE252/타입·build·lint0/0/Chromium2회는 [16단계 로컬 실행 근거](16-tidb-cleanup-readonly.md)다. 이번에는 제품 변경이 없어 전체 suite를 재실행하지 않았다. Upstash 연결/명령, HTTP login/JWT/session, runtime account, admission 전체 matrix·concurrency/FULL, FE/Chromium, AI는 모두 0이다. unsafe Job/금융 필드 fixture·두 번째 방문자·ACK-loss·commit ambiguity·장애전환·자원 제한 성능도 새로 검증하지 않았다.

## 남은 작업

이번 cleanup 호환성 한 건은 완료했다. 다음 한 건은 **공개 demo abuse guard의 최소 범위 설계·검증**이다. mobile Chart, cold-start UX(기존 로컬 준비240.037초가 FE240초보다 김), scanner source 분류 부채60, commit/push, 실제 Render/Cloudflare와 공개 URL E2E가 남아 있다. 공개 배포 준비 완료나 공급자 장애전환 무손실 보장은 주장하지 않는다.

stage/commit/push/PR/merge/서비스 생성/배포는 하지 않았다.
