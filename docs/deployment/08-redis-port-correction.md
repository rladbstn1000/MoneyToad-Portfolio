# Redis 포트 교정과 native TLS/PING 확인

기준일: 2026-10-01. **REDIS_PORT 한 항목 교정 후 native 연결이 복구됐다.** 같은 MoneyToad Upstash 자원에 신규 연결1회로 TCP·검증된 TLS·인증/활성화·명시적 PING이 성공했다. 두 번째 연결은 실행하지 않았다. 과거 네트워크 상태의 모든 원인을 소급해 규명한 결과는 아니다.

실행 근거: [단일 연결 정제 summary](evidence/AUTONOMOUS_CONTINUATION/redis-port-correction-af4d1b6244c34ba4/summary.json).

## 콘솔과 입력 교정

기존 승인 Chrome의 동일 자원 Details / Connect TCP를 다시 읽었다. 자원·hostname이 이전 대상과 같고, 화면 Port와 마스킹된 TCP 연결 예시의 port가 일치하며 TLS Enabled임을 확인했다. 플랜·자원·비밀번호 표시·네트워크 정책은 변경하지 않았다. 포트를 관행이나 문서 예시로 추측하지 않았다.

|검사|결과|
|---|---|
|교정 전 콘솔 port ↔ 저장 port|불일치|
|교정 후 콘솔 대상 ↔ 저장 대상|일치 true|
|REDIS_PORT_CORRECTED|true|
|실제 변경 키|REDIS_PORT 하나|
|나머지8개 값|불변 true|
|원본 형식|포트 숫자 부분 외 byte 불변; 공백·따옴표·개행 보존|
|디렉터리/파일 안전|owner·700/600·regular file·single-link·no-follow 검사 PASS|
|임시 비밀 복사본|동일 전용 디렉터리의600 임시 파일로 원자 교체, 잔존0|
|기존 preflight|input PASS; exit2는 REMOTE_EXECUTION_NOT_PERFORMED를 뜻하며 입력 실패가 아님|
|실제 ConfigData/URI 전달|host/port/username/password/TLS/database 비노출 비교6개 모두 true|

변경 전후 값을 메모리에서 비교했다. 공개 문서에는 실제 hostname·port·username·password·URL·checksum을 기록하지 않는다. 비밀번호 재설정·재입력·Chrome 재로그인은 없었다.

기존 private 원장에 승인된 `REDIS_PORT_CORRECTION` 입력 revision을 추가했다. 변경 전/후 checksum과 변경 키·나머지 값 불변 여부는 private 기록에만 남겼다. 과거 checksum 기록·원장·실행 marker는 보존했다. 새 실행기는 동일 inode에서 안전하게 읽은 입력 bytes를 새 revision과 대조하고, 컴파일 뒤 연결 직전에도 다시 대조한다. 공개 실행 결과는 revision reference와 일치 boolean만 갖는다.

## 실제 연결과 관측 경계

Java21.0.11, Lettuce6.6.0.RELEASE, Netty4.1.124.Final의 기존 설치 버전을 사용했다. 실제 제품 application/demo/render ConfigData를 로딩하고 기존 최소 Lettuce `nativeControl`을 한 번 호출했다. 별도 JDK TCP/TLS control A와 관측기 비교 C는 실행0이다.

|단계|결과|실제 근거|
|---|---|---|
|DNS|PASS|JDK 조회1회, 첫 IPv4 선택, 원래 hostname 보존|
|TCP|PASS|CONNECTED_EVENT 관측 후 TLS 성공. socket syscall 타이밍 자체를 측정한 것은 아님|
|TLS|PASS|TLS_FUTURE_SUCCESS, FULL 검증·기본 JDK trust·원래 peer hostname/HTTPS endpoint identification 유지|
|인증/handshake|PASS|기존 credential을 포함한 HELLO 완료와 HANDSHAKE_FUTURE_SUCCESS·연결 활성화. 별도 AUTH 명령은0|
|활성화|PASS|ACTIVATED_EVENT 및 CONNECT_FUTURE_SUCCESS|
|명시 PING|PASS,1회|실제 응답이 기대 PONG인지 코드에서 비교. 응답 원문은 저장하지 않음|
|채널/client/resources|PASS|channel_closed true, CLIENT_SHUTDOWN_DONE, RESOURCES_SHUTDOWN_DONE|
|child/DNS worker|PASS|유한 종료 확인, child_stopped true, cleanup_complete true, 강제 종료 false|

서버의 실제 인증서 체인과 hostname 검증을 해제하지 않았다. connect3초, activation/command2초, TLS handshake10초와 기본 protocol 협상은 그대로다. 실제 preconnect protocol은 RESP3였고 임의 RESP2 강제는 없다. 자동 재접속·애플리케이션 retry도 없다.

**제품 설정 일치와 전체 제품 실행 경로는 구분한다.** 이 검사는 대상 접속 수를 제한하는 기존 공유 JDK DNS resolver와 첫 IPv4 한 주소를 사용한다. Spring 애플리케이션·LettuceConnectionFactory 전체를 시작한 것은 아니며, 원래 Netty DNS 경로를 완전히 동일하게 재현했다고 주장하지 않는다. summary의 `product_condition_exact=false`는 이 진단 경로 차이를 명시한다. 기존 필드 `same_dns_target_as_a`는 이번에도 현재 선택한 DNS 주소와 같다는 뜻이며, A 연결을 실행했다는 뜻이 아니다. hostname/SNI·credential·TLS/timeout 정책 자체는 동일하다.

### 상대시간

|probe 내부 계측 시작 후|관측|
|---:|---|
|278→318ms|DNS 시작→완료|
|385ms|native connect 호출 시작|
|632ms|연결 event 수신|
|710ms|TLS future 성공|
|1,057→1,058ms|활성화·connect/handshake future 성공|
|1,058→1,174ms|명시 PING 시작→성공|
|1,177ms|채널·연결 close 완료|
|1,183→1,184ms|client/resources shutdown 완료|
|1,185ms|전체 cleanup 완료|

native control 총865ms, probe 내부 전체 계측 구간1,185ms다. OS 프로세스의 시작·종료 전체 시간을 측정한 값은 아니며, 실제 child 종료는 별도 확인했다. event와 future callback의 수신 시각은 비동기 관측이며 TLS wire/서버 내부 처리 시간을 뜻하지 않는다. Mac의 이번 한 번 관측을 Render 성능·최적 timeout·공급자 장애전환 보장으로 표현하지 않는다.

## 유한 명령·예산·정리

기존 실행기의2,256 예약을 줄여 재실행하지 않고, **사용자가 새로 승인한 연결 전용64 예약 경계**를 추가했다. 기존 parser·private 파일 검사·lock·컴파일·nativeControl·정제/종료 함수를 재사용한다. 기존 원장의9408/login1 및 cleanup을 확인하고 영구 새 marker를 먼저 기록한 뒤64를 선예약한다. 기존 marker 삭제·예산 환급·state 초기화는 없다. 10,000 총한도를 유지한다.

설치된 handshake 코드의 정적 상한은 HELLO1 + 필요할 때만 같은 channel의 AUTH/PING fallback1 + CLIENT metadata2 + 명시 PING1 = **최대5명령**이다. 데이터베이스0·clientName 없음·readOnly false로 SELECT/SETNAME/READONLY는 없고 close에서 QUIT를 보내지 않는다. 연결은 호출1회·channel1·autoReconnect false다. 64는 정적 상한을 포함한 보수적 내부 예약이며 동적 명령 차단기나 공급자 청구량으로 주장하지 않는다.

이번 완료 관측은 **HELLO1, CLIENT2, PING1**, AUTH/SELECT/예상 밖 명령0이다. 명령 인자·응답 원문을 수집하지 않았다. completion 관측4와 exact wire 시작 횟수·공급자 청구량은 구분하며, 후자는 NOT_OBSERVED다. OS 내부 DNS 패킷/접속 수 역시 미관측이다.

- 신규 native 대상 연결 **1/2**, 자동 retry0, 명시 PING **1**. 성공이 명확하므로 추가 연결0.
- 신규 예약 **64**, 원격 데이터 정리 예약 **0**. 누적 **9,472/10,000**, 잔여 **528**, login 시도 **1/8** 불변.
- login/User/seed/TiDB/schema/Redis data key/Lua 작업 **0**.
- 기존 private 파일은 승인된 budget 증가 외 checksum·mode 보존. 과거 원장4개·marker 보존, 신규 원장1개를 포함한 **5개 모두 cleanup 확인**.
- 실제 channel·client·Netty resources·DNS worker·child 종료와 임시 비밀 전달 파일 제거 확인. 강제 child 종료0, 전달 파일 잔존0. 생성한 원격 데이터가 없으며 TTL을 cleanup 성공 근거로 쓰지 않았다.

## 변경과 관련 로컬 검증

신규 검증 파일4개만 추가했다: `RedisPortCorrectionProbe.java`, `RedisPortCorrectionProbeTest.java`, `redis_port_correction.py`, `test_redis_port_correction.py`. 기존 제품 Java/YAML/timeout/보안 정책·기존 검증 코드·과거 테스트는 변경하지 않았다.

|검증|결과|
|---|---|
|포트 숫자만 치환하는 private 보조 코드|합성4사례 PASS: 공백·따옴표·개행 보존, 중복 거절|
|Java ConfigData/URI/주소 선택|신규2 PASS|
|기존 최소 native loopback TLS/PING|1 PASS, 기존 assertion 유지|
|Java 컴파일|PASS|
|Python 입력revision/예산/marker/정제/실패 정리|신규8 PASS, 실패/skip0|
|실제 Java 결과→Python 정제|연결0인 입력 거절 결과와 합성 loopback 성공 payload PASS; 거짓 성공 조건3개 거절|
|실행기 도움말|PASS|
|초기 Python 검사 실행 위치|루트 모듈 경로로 실행해 ImportError1 발생. 코드/expectation 변경 없이 verification 디렉터리에서 재실행해8 PASS. 기능 실패나 원격 실패로 분류하지 않음|
|BE/FE/E2E 전체|NOT_RUN — 제품 변경 없고 이번 요청에서 제외|

Python 재현은 verification 디렉터리에서 `python3 -B -m unittest test_redis_port_correction.py`다. 원격 진입점은 `python3 -B scripts/verification/redis_port_correction.py --execute --cache-seed <approved-offline-cache> --state-dir <existing-private-state>`다. 이미 실행 marker가 있어 같은 실행을 반복할 수 없다. marker 제거·state 초기화로 재실행하지 않는다.

## 남은 기능 검증

이번 결과는 **포트 교정 후 native 연결·인증·PING 복구 확인**이다. 과거의 모든 네트워크 조건까지 규명한 것은 아니다. login/seed/API·방문자 격리, Lua/TTL/회전/폐기/재연결은 여전히 미검증이다. 장애전환 이후 세션 폐기 보장은 NOT_ESTABLISHED다.

기존 전체 기능 검증의6,096 예약을 그대로 적용하면 현재 잔여528 대비 추가 **5,568**, 총상한 **15,568**의 별도 승인이 필요하다. 과거 추가5,504 계산은 잔여592 당시의 값이며 이번64 사용을 반영해 갱신했다. 이번 요청은 그 증액이나 전체 probe 실행 승인이 아니다.

```text
REDIS_PORT_CORRECTED=true
OTHER_EIGHT_INPUT_FIELDS_UNCHANGED=true
INPUT_REVISION_VERIFIED=true
UPSTASH_NATIVE_CONNECT_VERIFIED=true
MINIMAL_LETTUCE_REMOTE_STATUS=PASS
LOGIN_RECOVERY_VERIFIED=false
UPSTASH_FUNCTIONAL_CONTRACTS_VERIFIED=false
MANAGED_PROVIDER_CONTRACTS_VERIFIED=false
UPSTASH_FAILOVER_REVOCATION_GUARANTEE=NOT_ESTABLISHED
PUBLIC_DEPLOYMENT_READY=false
```

최종 공개 후보504개 파일 검사 PASS, 미분류 후보0·실제 민감값 일치0·Git 공백 검사 PASS다. 시작 파일 중 STATUS만 갱신했고 신규6파일을 추가했다. 기존 제품·과거 보고서/evidence·Git HEAD/index·나머지 미커밋 자료 보존을 checksum으로 확인했다. stage·commit·push·서비스 생성·유료 전환·배포는 하지 않았다.
