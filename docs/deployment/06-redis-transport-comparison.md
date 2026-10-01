# Redis transport 독립 대조

기준일: 2026-10-01. 기존 미커밋 배포 준비 코드와 05 보고서·원장을 보존한 연결 진단이다. 제품 설정·인증·timeout·transport 정책을 변경하지 않았다. TiDB·login·schema·User·Redis 데이터 key 작업은 모두 0이다.


## 결과 — NARROWED, 연결 성공 미확인

**기본 JDK Socket도 connect3초에서 timeout으로 실패했다.** Netty/Lettuce/기존 관측 pipeline을 거치지 않은 실패다. 원인은 TCP 연결 완료 이전으로 좁혔지만, 현재 Mac의 egress·중간 네트워크·공급자 접근 정책/서비스 상태·3초보다 긴 연결 지연 중 무엇인지는 UNKNOWN이다. 비밀번호·인증서·프로토콜 오류로 판단할 근거는 없다.

|검증|실제 결과|
|---|---|
|승인 입력 → 제품 ConfigData → native URI 구성|일치6개 true; 실제 연결 인증 성공을 뜻하지 않는다.|
|JDK DNS|PASS, IPv4 선택, 원래 hostname 보존 true. 과거05 실행과 동일 IP인지는 NOT_ESTABLISHED.|
|A JDK TCP|**FAIL / TCP_TIMEOUT / java.net.SocketTimeoutException**. control 관측3,008ms.|
|A JDK 검증 TLS|**NOT_RUN** — TCP 연결 실패로 handshake 시작0.|
|B 최소 Lettuce 활성화/PING|**NOT_RUN** — A TCP/TLS 성공 조건 미충족.|
|C 관측기 한 변수 대조|**NOT_RUN** — 사전에 제한한 B 성공 조건 미충족.|
|제품과 같은 조건의 연결·PING|성공 미확인. 제품 timeout·credential·TLS·transport 변경0.|
|자원 정리|PASS. socket close 확인, child 종료, DNS executor 종료, 임시 전달 파일 제거. 강제 종료0.|

이번 실행은 A 실패에서 중단했다. 관측된3초 timeout만으로 timeout 값이 원인이라고 확정하지 않았고, 같은 실패를 반복하거나 제한을 확대하지 않았다. B/C의 준비·로컬 PASS와 원격 NOT_RUN을 구분한다. 성공한 단계로 간주하거나 전체 공급자 계약을 PASS로 바꾸지 않는다.

원격 근거: [단일 합동 대조 summary](evidence/AUTONOMOUS_CONTINUATION/redis-transport-comparison-ea5cd91f4a084b97/summary.json).

### 관측된 단조시계 순서

|프로세스 시작 후 상대 시간|사건|
|---|---|
|266ms|JDK DNS 시작|
|343ms|DNS 완료|
|391ms|A TCP connect 시작|
|3,397ms|SocketTimeoutException 분류, 그 뒤 cleanup 진입|
|3,398ms|소유 socket close 확인|
|3,399ms|전체 cleanup 완료|

외부 child deadline120초, A 전체14초/TLS 전체10초 watchdog은 발동하지 않았다. TCP 실패가 cleanup보다 먼저다. 이번 실행에는 Lettuce 활성화 타이머·Netty channel cancel 사건 자체가 없다. 따라서 이전 ClosedChannelException의 정확한 close 주체가 확인됐다고 주장하지 않는다. 현재 증거는 **Lettuce 관측기만이 실패의 유일한 원인이라는 설명으로 충분하지 않다**는 점을 보여준다.

## 실행 환경과 비교 범위

macOS arm64, Java 21.0.11, Spring Boot 3.5.5, Spring Data Redis 3.5.3, Lettuce 6.6.0.RELEASE, Netty 4.1.124.Final의 설치된 JAR를 기준으로 조사했다. 기존 Java 21과 전용 offline cache를 사용하고 실제 접속정보는 승인된 저장소 밖 파일에서만 읽었다. 원문 출력·재입력·재설정은 없다.

실행기에서 proxy/JVM 주입 환경변수를 물려주지 않는다. 현재 부모 환경에서도 관련 proxy/JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS 설정 존재는 모두 false였다. 원격 실행은 승인된 이 작업의 native 연결에 한정한다. 네트워크 정책·VPN·방화벽·proxy 변경은 하지 않는다.

A는 원래 hostname을 JDK로 한 번 해석하고 기본 Socket의 TCP 성공과 같은 socket을 감싼 SSLSocket의 TLS 성공을 구별한다. 인증서 기본 trust, hostname 검증, 원래 이름의 SNI를 유지한다. Redis 인증·명령은 전송하지 않는다.

B는 Spring context/DB 없이 최소 Lettuce를 사용한다. 원래 hostname/port/username/password, FULL TLS, database0, 기본 protocol, connect3초·command/activation2초·handshake10초를 유지한다. 사용자 정의 Netty pipeline 관측기는 없다. 자동 재접속·애플리케이션 retry는 없고 활성화 후 PING은 최대1회다.

**진단 전용 차이:** 설치된 기본 Netty DNS resolver는 UDP 응답 절단 시 TCP fallback을 열 수 있고 글로벌 physical connection 상한을 Redis channel 수만으로 증명할 수 없다. 이번 최소 대조는 A에서 얻은 JDK hostname 해석 결과를 메모리에서 공유하는 지원 DnsResolver를 사용한다. 주소를 하드코딩하거나 hosts/네트워크 경로를 바꾸지 않는다. 원래 hostname과 SNI 이름 일치는 값 없이 확인한다. 이 resolver 차이 때문에 성공하더라도 전체 제품 transport 경로와 완전히 동일한 조건의 성공으로 표시하지 않는다. OS가 내부적으로 수행한 DNS 패킷/물리 접속 수는 NOT_OBSERVED이며 0으로 기록하지 않는다.

## 설치 버전에서 확인한 종료·timeout 경로

|경계|설치 코드에서 확인한 의미|
|---|---|
|TCP connect3초|Netty socket connect timer. TCP promise가 먼저 실패하면 초기화 future를 기다리지 않고 연결 실패가 전달될 수 있다.|
|RedisURI activation2초|`RedisHandshakeHandler.channelRegistered`에서 시작한다. TCP 성공 전에도 만료될 수 있다. timeout 실패 처리에서 channel을 먼저 닫고 close listener에서 handshake future를 실패 완료한다.|
|pending connect 종료|`AbstractNioChannel.doClose`는 아직 진행 중인 connect promise를 ClosedChannelException으로 실패시키고 connect timer를 취소한다. 따라서 내부 활성화 timeout의 원래 예외가 바깥에서 가려지는 코드 경로가 존재한다.|
|SSL handshake10초|`SslHandler.startHandshakeProcessing` 이후 적용된다. TCP 이전부터 시작하는 총괄 deadline이 아니다.|
|일반 command2초|명령 만료로 command를 예외 완료하는 별도 경계다. 활성화/channel close와 같지 않다.|
|기존 외부 Future30초/parent60초|기존 진단의 finally 종료는 이보다 앞선 관측 실패를 받은 뒤 실행된다. 2.62초만으로 이 외부 deadline이 먼저 종료했다고 해석할 수 없다.|

위는 bytecode로 확인한 **가능한 실행 경로**다. 이전 실제 원격 실패의 timer 사건을 관측한 것은 아니므로 timeout 원인 확정으로 쓰지 않는다. `ConnectionFuture`의 중간 단계 취소가 원래 channel까지 전파된다고 가정하지 않으며, 이번 도구는 소유 connection/client/resources를 유한하게 종료하고 결과에 완료 여부를 남긴다. callback 안에서 blocking 대기를 하지 않는다.

`Transports`는 사용 가능한 native transport를 우선하고 없으면 NIO를 선택한다. 현재 macOS arm64 cache에는 kqueue가 없다. 정적 예상과 이번 런타임 관측을 따로 기록한다. 기본 SSL provider는 JDK다. 이번 런타임 transport selector는 NioSocketChannel을 반환했지만 B/C 미실행이므로 실제 Netty channel 생성은0이다. TLS 역시 설정만 확인했고 원격 handshake 성공은 미확인이다.

## 예산과 미실행 범위

기존 예약7,152와 login 시도1을 보존한다. 단일 대조 실행 전에256명령+2,000정리 예약을 한 번 추가하며 환급·초기화하지 않는다. 기존 원장 정리 확인과 배타 lock, 영구적인 이번 실행 marker로 자동 반복을 막는다. JDK는 Redis 명령0, Lettuce 각 연결의 자동 초기화·명시적 PING을 보수적 상한에 포함한다. 원격 자원을 만들지 않아 DEL 등 원격 삭제 명령도 없다. 예약은 실제 Upstash 청구량 측정이 아니다.

B는 A의 TCP/TLS가 모두 성공해야 실행한다. 이번 도구의 C는 B 성공 후 기존 관측기만 켜는 한 변수 비교로 제한했다. timeout 확대 대조는 구현·실행하지 않았다. 동일 실패의 이유 없는 반복은 하지 않는다. 연결 control은 로그인·seed·Lua·TTL·rotation·revoke·failover 보장을 검증하지 않는다.

설치된 `RedisHandshake`의 이번 옵션에서 Redis 명령의 정적 상한은 연결당5개다. 정상 RESP3 경로는 HELLO1(인증 포함)+CLIENT metadata최대2+명시적 PING1=최대4다. HELLO가 unsupported일 때만 RESP2 fallback AUTH1이 추가될 수 있다. database0이어서 SELECT0, readOnly=false이어서 READONLY0, clientName 없음으로 SETNAME0, close는 channel close이며 QUIT0, autoReconnect=false이어서 Watchdog 재접속0이다. 두 Lettuce control 합계는 최대10이다. 진단기의 연결당64, 전체256 예약은 이 상한을 넉넉히 포함하며 exact sent/청구량으로 주장하지 않는다. 최소 B의 latency metric은 **완료 관측**이고 명령 시작 횟수는 NOT_OBSERVED다.


## 진단 변경과 로컬 검증

제품 파일 변경0. 신규 Python launcher/관련 단위 검사, 신규 Java JDK/Lettuce 비교 probe/loopback 검사만 추가했다. 기존 `RedisActivationObservation`은 명시적 TCP 단계의 SocketTimeoutException을 `TCP_TIMEOUT`으로 구분하는 최소 변경이다. 다른 기존 관측기/runner와 과거 evidence는 보존했다.

- 실제 제품 ConfigData·설정 일치, 안전 입력 parser·원장 lock을 재사용했다. 이 probe는 Spring 애플리케이션·DB를 기동하지 않는다.
- 원장 부재·손상·예산 변경·미확인 cleanup은 차단한다. 영구 이번 실행 marker를 예약 전에 기록하므로 실행 실패 후 자동 반복할 수 없다. 선예약을 환급하지 않는다.
- 초기 연결 A에 전체14초/실제 TLS 단계10초 deadline, 원래 hostname/SNI 검증을 적용했다. B는 handler를 추가하지 않고 기존 TLS/activation future와 channel close를 읽기 전용 관측한다. C만 기존 pipeline 관측기를 추가한다. 외부 future cancel을 정리 성공으로 간주하지 않고 channel/client/resources 종료를 별도 확인한다.
- Python 실행/추적 사이 중단 경쟁을 막고, 중단 시 즉시 자식에 종료를 전달한다. Java stop 상태에서는 다음 연결·명시적 PING을 시작하지 않는다. 정리 완료 결과와 private ledger가 모두 맞아야 cleanup PASS다.
- stdout/stderr·wire DEBUG 원문을 공개하지 않는다. 고정 class/단계/시간/boolean projection을 검증한다. EventBus 시간은 이벤트 수신 시점이고, channel/future listener 시간과 구별해야 한다.

|실행|결과·범위|
|---|---|
|첫 Java 컴파일·관련 검사|32 PASS: 기존23+신규9, failure/error/skip0.|
|추가 검사 준비|신규 테스트의 wildcard Map assertion 타입 컴파일 오류1건. 필드값 비교로 교정, 기대값 보존. 기능 RED로 분류하지 않는다.|
|확장 관련 Java 검사|35 PASS: 기존23+신규12, compile PASS, failure/error/skip0.|
|최종 중단/Boolean cleanup 보강 후 영향 검사|신규12 재실행 PASS, compile PASS. 다른 기존23의 소스·assertion 변경 없음.|
|Python launcher 안전 검사|신규19+기존10=29 PASS. 원장/링크/예산/단일실행/중단/정제 경계를 합성 자원으로 검사.|
|Java → Python 정제|실제 Java의 입력 거절·JVM override 거절·loopback 활성화/PING 결과 모두 PASS. 버전 metadata의 제한된 slash suffix 수용 오류를 원격 실행 전에 교정했다.|
|TLS/수명 로컬 검사|JDK 정상·CA 거절·hostname 거절·TLS 전체 deadline, Lettuce 정상·AUTH 거절·실제 activation timeout, PING 최대1·중단 후 새 연결0·소유 자원 정리 PASS.|
|BE/FE/build/E2E 전체|**NOT_RUN: 제품 변경이 없고 이번 요청에서 제외.** 과거 전체 로컬 PASS와 이번 공급자 결과를 혼동하지 않는다.|

로컬 세부 수치: [local-checks.json](evidence/REDIS_TRANSPORT_COMPARISON/20261001-control/local-checks.json).

실행 진입점은 `python3 -B scripts/verification/redis_transport_comparison.py --execute --cache-seed <approved-offline-cache> --state-dir <existing-private-state>`다. 이 작업의 이미 기록된 marker가 있으면 다시 실행되지 않는다. marker 삭제·state 초기화로 재실행하지 않는다.

## 실제 소비와 정리

- 신규 **대상 Upstash TCP 연결 시도1회**, 성공0. 자동 retry0, B/C 추가 연결0.
- Redis 명령0, 인증/HELLO/PING0, 데이터 key/login/schema/User 작업0.
- JDK DNS는 한 번 조회했고 probe 소유 DNS socket0이다. OS DNS 물리 패킷·접속 수는 **NOT_OBSERVED**다. 대상 서비스 TCP1을 전체 OS 네트워크 물리 접속1과 동일시하지 않는다.
- 신규 예약256+정리2,000=2,256. 누적 **9,408/10,000**, 잔여 **592**, 누적 login 시도 **1/8** 그대로다. 실제 공급자 청구량은 미측정이다.
- 기존 원장3개에 이번 원장1개가 추가되어 총4개 모두 cleanup 확인. 생성 원격 자원0, 소유 client/child/임시 전달 파일 정리 PASS. 이번에는 Lettuce client를 생성하지 않았다.
- 승인 접속정보 파일 원문·mode·owner 안전 조건 유지. 비밀번호 재설정/재입력/Chrome 작업0. 과거 원장·보고서·evidence 보존.

## 다음 조치 한 건

**현재 Mac에서 승인된 Upstash 대상 native TCP 경로의 허용/차단 상태를 확인하는 네트워크 경로 진단**이 다음 한 건이다. 이번 결과만으로 제품 timeout·TLS·RESP·credential을 변경하지 않는다. 새로운 원격 대조는 별도 승인·선예약 후 진행해야 하며, 남은592로 기존2,256 진단 run을 반복할 수 없다. 기존과 같은 진단 예약을 쓰려면 최소 추가1,664(총상한11,664)가 필요하다.

전체 공급자 기능 경로는6,096 예약이 필요하므로 현재 잔여 기준 **추가5,504(총상한15,504)**가 필요하다. 이는 이번 실행 승인이 아니며, 연결 문제를 해결하지 않은 상태로 전체 probe를 반복하지 않는다. TiDB 기본 계약의 과거 PASS, 성공 login/seed·Lua/TTL/회전/폐기·재연결의 BLOCKED, failover 이후 폐기 보장 NOT_ESTABLISHED를 유지한다.

```text
REDIS_TRANSPORT_CAUSE=NARROWED
JDK_TCP_VERIFIED=false
JDK_TLS_VERIFIED=false
MINIMAL_LETTUCE_REMOTE_STATUS=NOT_RUN
UPSTASH_NATIVE_CONNECT_VERIFIED=false
LOGIN_RECOVERY_VERIFIED=false
MANAGED_PROVIDER_CONTRACTS_VERIFIED=false
UPSTASH_FAILOVER_REVOCATION_GUARANTEE=NOT_ESTABLISHED
PUBLIC_DEPLOYMENT_READY=false
```

[공개 내용·보존 감사](evidence/REDIS_TRANSPORT_COMPARISON/20261001-control/final-audit.json). stage·commit·push·서비스 생성·배포는 하지 않았다.
