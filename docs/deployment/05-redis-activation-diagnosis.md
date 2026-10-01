# 배포 준비 05 — Redis 연결 활성화 진단

기준일: 2026-10-01. **원인은 NARROWED다.** 제품과 같은 연결 입력·TLS·timeout·기본 protocol을 전달했지만, 주소 해석 후 Netty TCP 연결 promise가 `ClosedChannelException`으로 실패했다. TLS·인증·handshake·PING까지 도달했다는 근거는 없다. 물리적 네트워크·공급자·로컬 transport 중 근본 원인을 확정하지 않는다.

```text
CAUSE=NARROWED
UPSTASH_NATIVE_CONNECT_VERIFIED=false
LOGIN_RECOVERY_VERIFIED=false
MANAGED_PROVIDER_CONTRACTS_VERIFIED=false
UPSTASH_FAILOVER_REVOCATION_GUARANTEE=NOT_ESTABLISHED
PUBLIC_DEPLOYMENT_READY=false
```

## 범위와 보존

최신 STATUS와 04 보고서·이전 managed/연결 결과·실제 private state를 확인했다. 지시서 파일은 첫 확인 시 없었고 사용자의 후속 안내 뒤 제공된 본문을 읽었다. 그 전에는 원격 실행을 하지 않았다. 지정 접속 파일의 preflight 입력 PASS를 확인했고 값 재입력·재작성·비밀번호 재설정·Chrome 접근은 하지 않았다.

시작 시 공개 예정480파일의 checksum/mode와 Git 상태를 비공개 원장에 기록했다. 기존 HEAD·index와 미커밋 제품 변경을 보존했다. 이번은 진단 helper·runner·합성 테스트·문서 변경이며 제품 Java/YAML·FE·JWT·Lua·seed·권한 정책은 변경하지 않았다. TiDB와 전체 managed probe는 재실행하지 않았다.

## 확인한 도구 문제와 최소 교정

|확인된 사실|수정·한계|
|---|---|
|기존 분류기는 ClosedChannelException을 놓치고 모든 미분류 실패를 OTHER로 축약|고정 class allowlist와 cause/suppressed 관계, 단계별 상태를 보존한다. 이번 결과는 TCP_FAILURE로 구분된다.|
|기존 catch는 연결 전 로컬 오류도 CONNECT_ACTIVATE로 덮어쓸 수 있음|INPUT/ConfigData/client/factory/연결/PING 단계의 실제 위치를 유지한다.|
|기존 일반 CommandListener는 초기 HELLO/AUTH를 관측하지 않음|Netty pipeline에서 명령 이름·완료 상태만 관측한다. 명령 객체·인자·응답·주소를 직렬화하지 않는다.|
|기존 진단기만 requestQueueSize1 사용|제품 기본 대기열을 사용한다. 로컬에서는 queue1이 CLIENT metadata2건을 거절해도 PING은 성공했으므로 원격 실패 원인으로 단정하지 않는다.|
|기존 진단기의 ClientOptions에는 Boot의 TimeoutOptions.enabled가 누락|설치된 Boot의 설정과 동일하게 활성화했다. connect3초/command2초를 늘리지 않았다.|
|입력과 최종 client 설정의 일치 관측 없음|parser→ConfigData/Binder→StandaloneConfiguration→실제 RedisURI의 host/port/username/password/TLS/database를 메모리에서 비교하고 boolean만 기록한다.|
|일반 listener의 command0만으로 handshake 위치를 추론하기 어려움|명령 카운터와 TCP/TLS 관측을 분리했다. 현재 command0은 별도 pipeline 관측에서도 확인되지만 청구량0을 뜻하지 않는다.|

기존 managed probe에는 진단기의 queue1 설정이 없고, command listener는 관측 command875 상한만 검사한다. 따라서 진단기의 충실도 교정이 과거 login 실패를 해결했다고 주장하지 않는다. 과거 HTTP 오류 상태는 기록되지 않았고 이번에도 login을 실행하지 않았다.

초기화 명령과 설정은 설치된 JAR의 API/bytecode를 확인했다. Lettuce6.6은 기본 RESP3 협상을 시도하고 HELLO에 인증을 포함할 수 있다. CLIENT SETINFO metadata를 별도 전송하고 실패를 선택적으로 처리한다. 명시적 clientName·database0 이외 선택이 없으므로 SETNAME/SELECT 발생을 가정하지 않는다. 공개 관측에는 CLIENT의 인자나 metadata 값을 넣지 않고 명령명만 남긴다.

## 로컬 검증 — 원격 전에 완료

|검증|결과|
|---|---|
|Java 설정·관측·실제 loopback TLS|23 PASS = 설정5 + 관측 정제12 + TLS6; 실패/오류/skip0|
|Python 원장·예산·비교 실행 제한|10 PASS; 누적 환급/초기화 금지, 근거 없는 두 번째 실행 거절, 진단512 상한|
|compile·Python 구문|PASS|
|정상 local TLS|HELLO1 → CLIENT2 → PING1, 각 시작·성공 및 순서 확인|
|관측기 on/off 비교|정상·잘못된 CA·hostname 불일치·인증 거절 모두 동일한 성공/실패|
|비노출·한도|합성 canary·예외 cycle·suppressed·unknown class·명령 입력, 64event/12node/24relation 상한 PASS|
|old queue1 비교|CLIENT2 로컬 거절을 관측했지만 PING 성공. 원격 원인의 증거 아님|

초기 새 테스트 한 곳에서 Integer와 Long wrapper를 비교해 실패했다. 기대 수치2는 유지하고 타입을2L로 교정한 뒤 최종23개가 통과했다. 제품 실패를 숨기거나 assertion을 완화한 것이 아니다. 로컬 테스트용 인증서·키와 원문 실행 산출물은 private 임시 영역만 사용했고 public evidence에는 넣지 않았다.

## 실제 원격 1회 결과

[전체 정제 관측](evidence/AUTONOMOUS_CONTINUATION/redis-activation-96a9699ad683409e/summary.json)을 그대로 보존한다. 원문 예외·credential·host·IP·URI는 기록하지 않는다.

|관측|결과|
|---|---|
|실제 설정 전달 비교6개|전부 true. 공급자의 인증 승인 여부를 증명하는 것은 아님|
|DNS|PASS: Netty connect에 해석된 주소가 전달됨. endpoint 소유권 검증은 아님|
|TCP|FAIL: connect promise 실패|
|TLS / HANDSHAKE / ACTIVE / EXPLICIT_PING|모두 NOT_OBSERVED|
|명령 시작/성공/실패 카운터|HELLO/AUTH/CLIENT/SELECT/PING 모두0, 예상 밖 명령0|
|고정 분류|TCP_FAILURE|
|소요 시간|2,620ms; Mac에서 원격 연결 시도1표본, timeout 증거·Render 성능 아님|
|native client/resources 정리|PASS|

관측된 예외 사슬은 `ExecutionException → RedisConnectionFailureException → RedisConnectionException → ClosedChannelException`이다. 이 사슬과 연결 promise 실패가 직접 근거다. TCP 패킷이나 OS/공급자 내부 사유를 관측한 것은 아니므로 방화벽·provider outage·잘못된 비밀번호·인증서 오류·RESP3 비호환을 단정하지 않는다. TLS 핸드셰이크·인증 거절 오류도 관측되지 않았다.

실제 설정: TLS FULL(peer/hostname 검증), database0, connect3,000ms, command/activation2,000ms, SSL handshake 상한10,000ms, command timeout 활성, ping-before-activation 활성, 기본 protocol 협상(연결 전 preferred RESP3), 기본 대기열2,147,483,647. 안전 조건상 autoReconnect=false다. 실제 협상 완료 protocol은 미관측이다. 자원 thread 수와 재연결 제한은 유한 진단용이며 제품 인증/전송 의미를 바꾸지 않는다.

실제 classpath/JDK: Java21.0.11+10-LTS, Spring Data Redis3.5.3, Lettuce6.6.0.RELEASE, Netty4.1.124.Final, Boot3.5.5. 새로운 라이브러리를 설치·업그레이드하지 않았다.

이번 실패를 근거로 바꿀 수 있는 protocol/timeout/credential 변수가 확인되지 않아 두 번째 연결을 사용하지 않았다. IP 치환·TLS 해제·신뢰 체인 우회·네트워크 정책 변경도 하지 않았다. 실패 경로는 좁혔으나 연결 성공은 확인하지 못했다.

## 예산과 정리

|항목|값|
|---|---:|
|이번 신규 native 연결 시도|1 (지시서 최대3; 현재 실행기 예산으로 최대2)|
|이번 명령 예약|256 / 진단 상한512|
|이번 정리 선예약|2,000|
|이전 누적 예약|4,896|
|현재 누적 예약|7,152 / 10,000|
|잔여 예약 가능량|2,848|
|누적 login 시도|기존1/8 그대로; 이번0|
|이번 schema·User·Redis 데이터 key 생성/쓰기|모두0|

기존 원장을 재사용했고 환급·초기화는 없다. 연결 전 입력·소유권·이전 cleanup·예산을 검사하며 두 번째 실행에는 첫 관측과 일치하는 비교 근거를 요구한다. 전송 관측기는 연결1·명령64의 안전 상한을 두고, per-run256은 handshake·PING·종료를 포함한 보수적 예약이다. 실제 Upstash 과금 수를 측정한 값이 아니다.

이번 원장까지3개 모두 cleanupComplete=true를 확인한다. 이번에 원격 key/schema를 만들지 않았으므로 원격 삭제 작업도 필요하지 않았다. native client/resources·실행 child 종료와 임시 전달 파일 제거를 확인했다. 승인된 접속정보 파일은 그대로 보존한다. TTL 예정이나 연결 실패로 정리 PASS를 대신하지 않는다.

## 미완료 기능과 다음 실행의 예산

TiDB 기본 계약은 04 당시 PASS 근거를 유지한다. 남은 login/seed 수량·Chart 조회/수정/격리, Redis Lua·TTL·절대 수명·CAS/동시 회전·재사용 폐기·재연결은 **BLOCKED**다. login 복구는 false, 장애전환 뒤 폐기 상태의 무손실 보장은 NOT_ESTABLISHED다.

현재 검증된 전체 runner 경로는 정리2,000을 포함해6,096 예약과 login4회를 사용했다. 잔여2,848로는 실행할 수 없으며, 같은 경로 재검증에는 최소 **3,248의 추가 예약 승인**(총상한13,248)이 필요하다. 이는 사전 계산이며 새로운 예산 승인이나 실행이 아니다. 미완료 기능만 분리했을 때 더 적은 예약으로 완주한다는 근거는 없으므로 임의 축소하지 않는다.

그 경로는 소유 schema1·제한 계정1에 합성 User/Card 각4, Transaction960, Budget288, 세션 key 최대5를 만들고 정확한 소유 원장으로 정리한다. 동시에 활성 방문자는 최대2이며 누적 login 시도는5/8이 된다. 이번 요청에서는 그 쓰기를 전혀 수행하지 않았다.

먼저 native TCP 연결이 닫히는 경로를 확인해야 한다. 현재 credential 재입력·reset·Chrome 로그인·서비스 생성 같은 사용자 조치는 필요하지 않다. 추가 원격 조사나 전체 기능 검증은 원인에 맞는 비교 변수와 필요한 추가 예약 범위를 정한 뒤 진행해야 한다.

## 변경 파일

- `ManagedRedisConnectivityProbe.java`, `ManagedRedisConnectivityProbeTest.java`: 설정 일치 비교·제품 옵션 정합성·단계별 결과.
- `RedisActivationObservation.java`, `RedisActivationObservationTest.java`: bounded 비노출 투영.
- `RedisActivationTransport.java`, `ManagedRedisActivationLocalTest.java`: handshake 관측과 owned loopback TLS 검증.
- `managed_redis_connectivity.py`, `test_managed_redis_connectivity.py`: 기존 누적 예산·조건부 비교·512 제한.
- `scan-classifications.json`: 로컬 합성 fixture 한 소스 지점만 정확한 행/digest로 추가. 규칙 완화 없음.
- 이 보고서·현재 STATUS·새 정제 근거. 과거 evidence는 변경하지 않았다.

제품 변경이 없어 BE/FE 전체 회귀·build·E2E를 다시 실행하지 않았다. 기존 PASS는 이전 근거이며 이번 진단 성공의 근거로 대체 사용하지 않는다. stage·commit·push·클라우드 생성·배포는 없다.

[최종 공개·보존 감사](evidence/REDIS_ACTIVATION_DIAGNOSIS/20261001-native-check/final-audit.json): 공개 예정488파일 검사 PASS, 실제 접속값 일치0, 과거 보호115파일 불변, 기존 삭제/mode 변경0, 제품 변경0, HEAD 동일·staged0. 원장3개 모두 cleanup 확인, 임시 비밀 전달 파일0.
