# 교정된 입력으로 실제 managed provider 기능 검증 재개

기준일: 2026-10-01. **실제 TiDB + Upstash 전체 기능 경로 1회 PASS, 소유 자원 정리 PASS**다. [08 포트 교정](08-redis-port-correction.md)의 입력 revision을 그대로 사용했다. 접속정보·비밀번호·TLS·제품 timeout을 변경하거나 Chrome/native 연결 진단을 반복하지 않았다.

근거: [실제 공급자 결과](evidence/AUTONOMOUS_CONTINUATION/provider-e26ae7243edf4034/summary.json), [관련 로컬 검사](evidence/MANAGED_PROVIDER_RECOVERY/20261001-recovery/local-checks.json), [공개·보존 감사](evidence/MANAGED_PROVIDER_RECOVERY/20261001-recovery/final-audit.json).

## 승인과 실행 경계

사용자가 기존 누적 예약9,472를 보존하면서 총상한15,568을 승인했다. 기존 완주 경로는 추가6,096이므로 실행 전에 상한 내임을 확인했다. 이번 예상 login4회와 누적5/8도 사전 확인했다. 예약은 공급자 청구량·요금 한도가 아니다.

기존 private state가 반드시 존재하고 budget가 정확히9,472/login1이며 과거 원장5개가 모두 정리 완료인지 확인했다. 교정된 입력 bytes와 승인 revision의 일치를 값 비노출 방식으로 확인하고 컴파일 뒤 재확인했다. 새 영구 1회 marker를 먼저 기록한 뒤 Java의 기존 증분 예약을 사용했다. 6,096을 먼저 더하고 중복 가산하지 않았다. 과거 marker·원장·revision 삭제, 예약 환급·초기화·축소는 없다.

예전 일반 실행의8,000 사전 차단/10,000 상한을 전역 완화하지 않았다. `--resume-port-corrected`라는 이번 승인 전용 경계만15,568을 허용한다. 시작값이 달라지거나 marker가 이미 있으면 중단한다. 최종 상태에서 같은 실행을 다시 시작할 수 없다.

## 실제 관측 결과

|계약|결과와 관측|
|---|---|
|TiDB 연결|실제 TiDB 8.5.3 serverless, Connector/J9.4.0, JDBC `VERIFY_IDENTITY` 유지. setup/runtime 모두 TLS 확인|
|전용 SQL 범위|write-ahead 원장에 기록한 고유 schema1과 제한 runtime account1 생성. runtime에는 해당 schema의 SELECT/INSERT/UPDATE/DELETE만 부여|
|스키마|검토된7개 table DDL 적용 후 실제 application.yml/demo/render ConfigData로 시작. `ddl-auto=validate`, SQL init never, 시작 전후 schema snapshot 동일|
|보안 체인|실제 JWT filter를 포함한 단일 chain, DemoSessionGuard 및 demo 인증 서비스 사용. demo AnalysisJobScheduler 없음|
|JDBC 기본 계약|Asia/Seoul session timezone, 연말/연초·윤년 DATE/DATETIME, 한국어, collation, FK/unique, null 금융정보, generated key, enum/boolean, SUM와 rollback PASS|
|login 복구|새 login4회 모두201, 토큰 검증 및 인증된 session 조회 성공. 새 User·seed·Redis·SQL commit을 실제 제품 코드로 수행|
|seed|앞의 두 방문자를 각각 실제 SQL로 검사: User1/Card1/Transaction240/Budget72, cardNo/cvc null, AnalysisJob/Peer/Dummy0|
|Chart 조회|실제 API 처리 계층에서12개월9,990,000원, 기준월908,000원, 초기 누수18,000원|
|실제 category 수정|카페 → 마트 / 편의점 PATCH 성공. DB category 변경, 총액908,000원 유지, 누수0, annual leaked=false|
|방문자 격리|다른 방문자의 User/Card/Transaction/Budget 전체 컬럼 snapshot 불변|
|제품 Redis/Lua|실제 DemoSessionStore/StringRedisTemplate/제품 Lua 원문 사용. create/findActive/회전/폐기, HASH3필드·RT 원문 미저장·SHA-256 일치·절대 만료·유한 PTTL 확인|
|Lua 실행|EVALSHA와 EVAL 실행 관측. 실제 RedisNoScriptException3회 관측 후 제품 script executor의 EVAL fallback으로 성공. script cache를 비우지 않음|
|RT 회전/reuse|새 RT로 교환하고 absolute expiry 불변. 이전 RT reuse401 → 세션 부재 → 새 AT도401|
|동시 refresh|동일 RT2건 중 정확히1건200/1건401, 최종 세션 부재, winner AT401. grace window나 기대값 완화 없음|
|재연결|별도 실제 connection과 명시적 reset 후 조회·rotate·revoke·부재 확인 PASS|
|실제 만료|소유 키의 TTL만 짧게 설정해 실제 만료 관측. 암호학적으로 아직 유효한 AT도401|
|외부 애플리케이션|OAuth/팀 AI/외부 AI 호출0. 실제 제품 데이터/API/session 응답 대역 없음|

JDBC 제약조건 probe에서 임시 enum 행을 포함한 합성 행을 만들고 rollback·전체 빈 테이블을 확인한 뒤 login을 시작했다. seed가 AnalysisJob을 생성했다는 뜻이 아니다. 후속 두 login도 동일 실제 제품 경로를 통과했으며, 추가 전체 행 수 조회를 했다고 주장하지 않는다.

이번 HTTP 검증은 실제 Spring MVC/security/JWT/서비스/SQL/Redis를 연결한 **MockMvc 요청**이다. Render 실행이나 공개 URL/실제 브라우저 E2E 결과가 아니다. 종료 계약은 실제 제품 Store의 revoke로 확인했다. 별도 HTTP logout 호출은 이번 기존 시나리오에 포함하지 않았다.

## 실패 주입과 보장 범위

실제 Redis rotate가 완료된 뒤 검증기에서 결과 전달을 끊는 제한된 주입에서503·토큰 미반환·CAS 재시도0을 확인했다. 별도의 알려진 unavailable 주입에서503·업무 SQL0도 확인했다. 이는 **로컬 서비스 결과 경계의 주입**이며 실제 공급자 장애/timeout을 유발한 결과가 아니다.

검증기 소유 Lettuce에는 자동 재접속false와 disconnected commands 거절을 적용했다. 연결3초·명령2초, 인증서/hostname 검증, credential, 기본 protocol과 실제 Spring factory/제품 Lua는 유지했다. 이 한 번의 유한 실행에서 불명확한 변경 명령이 자동 재전송되지 않도록 한 검증 설정이다. 제품 기본 자동 재접속 정책이나 모든 네트워크 장애 시 명령 처리 보장까지 검증했다고 표현하지 않는다. 제품 파일은 변경0이다.

새 application command gate는 제품 Lua 내용/SHA와 정확한 단일 소유 키만 허용한다. 최초 생성 전에는 해당 후보 키의 EXISTS 확인만 예외로 허용한다. 전역 SCAN/KEYS/FLUSHDB/FLUSHALL/SCRIPT FLUSH와 임의 Lua는 전송 전 차단한다. installed Lettuce의 단일/배치 writer에서 실제 delegate 이전 차단을 로컬 테스트로 확인했다. handshake는 별도 유한 예약 범위이며 application command 관측 수와 구분한다.

NOSCRIPT 발생은 이번 실제 관측이다. direct EVAL이 별도로 있기 때문에 EVAL 종류가 보였다는 사실만으로 fallback을 주장하지 않았고, 이번에는 EVALSHA 실패 타입3개와 제품 호출 성공을 함께 확인했다. TIME/PEXPIREAT 및 내부 HASH/TTL 명령은 실제 실행된 제품 Lua의 내용과 반환/만료 결과로 검증했으며, 각각을 독립 wire 명령으로 계수한 것은 아니다.

일반 reconnect PASS와 공급자 장애전환 후 acknowledged revoke가 되살아나지 않는다는 보장은 다르다. 장애전환을 유발하지 않았고 공급자 보장을 새로 확정하지 않았다. **UPSTASH_FAILOVER_REVOCATION_GUARANTEE=NOT_ESTABLISHED**를 유지한다.

## 실제 시간과 예약

|이번 Mac → 원격 공급자 계측|시간|
|---|---:|
|애플리케이션 context 시작|3,758ms|
|첫 login|18,590ms|
|후속 login|17,022ms|
|동시성 fixture login|17,002ms|
|결과 유실 fixture login|17,044ms|
|첫 session 조회|507ms|
|연간 조회|1,361ms|
|첫 후속 조회|818ms|
|첫/후속 refresh|525ms / 419ms|

context 시간은 schema 준비/컴파일을 포함하지 않는다. login은 실제 seed/세션/SQL commit HTTP 처리를 포함한다. 위 값은 이번 Mac에서의 관측이며 Render cold start·512MB/0.1CPU 성능·상시 응답시간 보장이 아니다.

|내부 원장|값|
|---|---:|
|시작 누적 예약|9,472|
|이번 추가 예약|6,096|
|그중 정리 선예약|2,000|
|최종 누적/승인 상한|15,568 / 15,568|
|잔여|0|
|신규 login 시도/성공|4 / 4|
|누적 login 시도/상한|5 / 8|
|관측된 application Redis command 시작 수|66|

기존 경로의6,096은 정리2,000 + 연결/기동 등256단위4개 +64단위48개다. 66은 listener에서 확인한 application 명령 수이며 native handshake·Lua 내부 호출·전체 JDBC 쿼리·공급자 청구량을 모두 합친 수가 아니다. 콘솔 청구 사용량/서비스 지역/플랜은 이번에 재확인하지 않았다.

## 실제 정리와 보존

- 확정 생성 schema1, 제한 runtime account1, 세션 키5를 기록했다. 이번 원장의 정확한 대상만 삭제했다.
- Redis 키5개의 실제 부재, schema의 정보 스키마 부재, 삭제한 정확한 account의 SHOW GRANTS 부재 응답을 확인했다. TTL 만료 예정이나 연결 실패를 성공 근거로 쓰지 않았다.
- Spring context·보조 client close, Netty resources 종료 future, Java child 종료 모두 확인했다. 강제 종료0, credential 전달 파일 잔존0이다.
- 이전5개와 이번1개를 합친 원장6개 모두 cleanup 완료다. 기존 private 파일은 승인된 budget 증가 외 불변이고 접속정보 bytes/권한/owner/링크 조건도 불변이다.
- 영구 재개 marker는 보존했다. 예약이 소진되었으므로 임의 반복·다음 원격 실행은 하지 않는다.

## 이번 검증 도구 변경과 로컬 검사

제품 변경 없이 검증기만 수정했다.

- `managed_provider_check.py`: 승인된 baseline/전체9키 revision/1회 marker/상한, launch 중단 경계, summary+원장 cleanup 교차 확인, 독립 결과 판정.
- `ManagedProviderProbe.java`: 명시 상한의 증분 예약, 제한 runtime 계정 실패 시 중단, 숫자 HTTP 상태/부분 결과 기록, 정확한 account 부재와 client/resources 종료 확인.
- `ManagedRedisCommandGate.java`: 제품 Lua/소유 키/허용 명령 검사와 검증기 자동 replay 차단.
- 신규 관련 테스트3파일. 기존 source 분류3지점의 이동과 합성 음성 테스트1지점만 scanner에 정확한 행/내용 해시로 추가했다. 비밀 패턴·evidence 규칙은 완화하지 않았다.

|로컬 검증|결과|
|---|---|
|기존 launcher7 + 신규 recovery10 + 기존 port revision8|Python25 PASS|
|기존 probe safety28 + 신규 recovery7 + 신규 gate6|Java41 PASS, failure/error/skip0|
|Java 현재 제품·테스트 컴파일|PASS|
|직접 StringRedisTemplate/Lettuce 직렬화와 NOSCRIPT fallback|신규6 중 loopback fixture1 PASS. 실제 공급자 Lua 검증과 별도|
|BE/FE/E2E 전체|NOT_RUN — 제품 변경 없고 이번 요청 범위에서 제외|

실행 진입점은 `python3 -B scripts/verification/managed_provider_check.py --execute --resume-port-corrected --cache-seed <approved-offline-cache> --state-dir <existing-private-state>`다. 이 명령은 완료된 marker와 소진된 원장에서 재실행할 수 없다. marker 삭제/상태 초기화로 반복하지 않는다.

## 최종 판정과 다음 한 건

```text
TIDB_CONTRACTS_VERIFIED=true
UPSTASH_FUNCTIONAL_CONTRACTS_VERIFIED=true
LOGIN_RECOVERY_VERIFIED=true
MANAGED_PROVIDER_CONTRACTS_VERIFIED=true
UPSTASH_FAILOVER_REVOCATION_GUARANTEE=NOT_ESTABLISHED
PUBLIC_DEPLOYMENT_READY=false
```

이번 승인된 기능 경로에는 FAIL/BLOCKED/skip이 없다. 공개 전 방문 수용 상한·SQL 데이터 잔존 정리·남용 방지, 모바일 Chart 가독성, 실제 Render/Cloudflare 배포와 공개 URL E2E는 별도 미완료다. 다음 한 건은 **공개 demo의 방문 수용 상한과 안전한 잔존 SQL 정리 정책 설계**다. 이 보고서가 해당 구현·배포를 승인하거나 완료로 표시하는 것은 아니다.

stage·commit·push·서비스 생성·유료 변경·배포는 수행하지 않았다. 과거 보고서/evidence와 기존 미커밋 제품·테스트 변경은 보존했다.
