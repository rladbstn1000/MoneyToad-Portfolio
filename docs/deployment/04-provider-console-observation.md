# 배포 준비 04 — 콘솔 입력과 실제 공급자 관측

기준일: 2026-10-01. 실제 공급자 검증의 현재 결과는 **FAIL**이다. 지정한 접속정보 파일의 입력 검사는 PASS이며 실제 TiDB 연결과 일부 DB 계약은 통과했다. 첫 demo login이 기대한 성공 응답을 내지 않아 방문자별 seed/API·Upstash 세션 계약 전체의 완료는 보류한다. 과거 입력 부재 보고서는 당시 근거로 보존한다.

## 콘솔 확인과 입력 준비

승인된 Chrome에서 기존 MoneyToad 전용 두 자원을 확인했다. TiDB는 Starter Free/AWS Tokyo, Upstash는 Free/Singapore였다. 실행 전 콘솔에 TiDB RU·storage와 Upstash commands·storage가 0으로 표시됐다. 이는 실행 후 공급자 청구 사용량 확인이 아니다. 자원·플랜·지역·ACL·네트워크 정책은 변경하지 않았다.

TiDB 최초 비밀번호 생성은 사용자가 직접 완료했다. 브라우저 도구의 값 가림과 가상 클립보드 때문에 자동 전달로 파일을 만들지 않았으며, 사용자가 콘솔의 연결 문자열을 비공개 TTY 입력에 붙여넣어 저장을 완료했다. 웹 입력기의 실패한 제출과 placeholder는 완성 파일로 저장하지 않았다. 이번에 만든 입력 서버와 입력 탭은 종료했고 콘솔의 비밀 표시도 다시 숨겼다.

지정한 저장소 밖 파일만 기존 preflight로 검사했다. 정확한 9키, owner·디렉터리700·regular file600·링크 조건, TLS true, placeholder 거절, raw/파싱 값 보존을 확인했다. 파일을 source/eval하거나 원문 출력하지 않았고 다른 비밀정보를 탐색하지 않았다. preflight exit2는 **입력 PASS + 원격 미실행**을 의미하며 접속 실패가 아니다.

## 검증 도구의 최소 수정

TiDB Starter의 전체 runtime username에는 setup 계정과 같은 접두사가 필요하다. 기존 도구는 임시 계정에 이를 누락했으므로 managed 모드에서만 접두사와 난수 suffix를 조합하도록 수정했다. CREATE/GRANT/JDBC/DROP/소유 원장에 동일한 전체 이름을 쓰고 32자 상한을 유지한다. local rehearsal의 계정 생성 방식·권한 범위·제품 설정·JWT/Lua·기존 assertion은 보존했다.

안전 단위는 기존19+신규9=28 PASS, 실패/오류/skip0, 제품·테스트 compile PASS다. 로컬 runner의 정확한 예상 개수만 19에서28로 갱신했다. 실제 TiDB에서도 제한된 runtime 계정 생성·연결·삭제가 통과했다. [TiDB Starter username prefix](https://docs.pingcap.com/tidbcloud/select-cluster-tier/#user-name-prefix), [username 길이](https://docs.pingcap.com/tidb/stable/tidb-faq/#what-is-the-length-limit-for-the-tidb-user-name).

## 실제 managed 실행

기존 private state의 누적 예산과 소유 원장을 이어 사용해 전체 probe를 한 번 실행했다. `demo,render`의 실제 제품 ConfigData와 validate 설정을 사용하며 전체 BE suite를 원격에 연결하지 않았다. schema 하나·별도 제한 계정만 생성하고 검토한 DDL을 적용했다. 기존 schema나 다른 프로젝트 데이터는 사용하지 않았다.

|계약|실제 결과|
|---|---|
|입력 안전 검사|PASS, 원문·endpoint 비공개|
|TiDB JDBC identity TLS와 검토 DDL|PASS|
|실제 ConfigData + validate context|PASS|
|시간대·날짜 경계·한국어·collation·unique/FK·identity·ENUM/BIT·SUM·rollback|PASS|
|실제 demo login 성공|FAIL: 기대한 201을 충족하지 못함|
|두 방문자 seed 수량·Chart 조회·category 수정·격리|BLOCKED: 첫 login 실패 이후 미실행|
|Upstash 제품 Lua·TTL·RT 회전·재사용 폐기·재연결|BLOCKED: 세션 생성 이전 연결 단계에서 중단|
|장애전환 이후 폐기 상태의 무손실 보장|NOT_ESTABLISHED, 시험·보장 확인 없음|
|소유 자원 정리|PASS: schema·계정 삭제와 부재 확인; 생성된 Redis key0|

관측 버전은 TiDB v8.5.3-serverless와 Connector/J9.4.0이다. [원격 실행 원본 정제 요약](evidence/AUTONOMOUS_CONTINUATION/provider-08f6e79d15b941cf/summary.json)을 보존한다.

이 실행기의 실패 응답 상태/body는 저장하지 않았으므로 실제 HTTP 오류 코드를 추정하지 않는다. 누적 예약값과 제어 흐름을 대조하면 세션 key 소유 기록 직전의 `hasKey` 연결 호출에서 중단됐다. 소유 Redis key는0이고 제품 create Lua는 아직 호출되지 않았다. 이는 source/원장에 따른 원인 범위 추론이며 TLS·AUTH 중 어느 단계인지 자체 증명하지 않는다. command listener의0은 TCP/TLS/AUTH 교환0을 뜻하지 않는다.

|시간 표본|밀리초|해석|
|---|---:|---|
|application context 시작|5,977|Mac에서 실제 원격 공급자에 연결한 표본1|
|첫 login|17,494|실패한 요청의 소요 시간, 성공 latency 아님|
|후속 login·session·refresh·Chart 조회|미측정|첫 login 실패로 진행하지 않음|

Render의 CPU·메모리·네트워크 성능이나 p95·수용 인원으로 해석하지 않는다.

## 후속 읽기 전용 연결 진단

실제 제품 YAML 세 문서에서 Redis 설정을 바인딩하되 제품 context/DB를 시작하지 않는 최소 도구를 추가했다. native Lettuce 연결·AUTH·PING 한 번만 허용하고 auto-reconnect를 껐다. 제품의 connect3초/command2초, 인증서·hostname 검증은 그대로다. DB·key·login 작업은 없고 원문 로그를 버리며 고정 오류 범주만 공개한다. 이전 원장의 배타적 lock 아래 정리2,000+연결256을 먼저 예약한다. state가 없거나 손상되면 초기화하지 않는다.

오프라인 Java4·Python6 PASS, compile PASS, failure/error/skip0 후 원격 진단을 한 번 실행했다. 결과는 **FAIL / CONNECT_ACTIVATE / CONNECTION_ACTIVATION_OTHER**이며 소요2,514ms, 명시적 PING 시도0이다. native client/resources 종료는 PASS다. 이 범주는 비밀번호 오류·TLS 오류·DNS 오류를 확정하지 않으며 TLS/AUTH 성공으로 해석하지 않는다. [읽기 전용 진단 근거](evidence/AUTONOMOUS_CONTINUATION/redis-connectivity-d4bcd19b28e8437f/summary.json).

이번 진단은 예외 원문과 cause chain을 저장하지 않았으므로 더 구체적인 원인은 현재 근거로 판별할 수 없다. 다음 최소 도구 개선 후보는 연결 예외의 **허용된 class 이름 및 고정 activation 오류 분류**만 기록하는 것이다. credential·endpoint·예외 원문을 출력하거나 TLS/제품 timeout을 완화하는 대안은 제외한다. 이 원인 보강 없이 같은 원격 실패를 다시 실행하지 않았다.

|원격 예산/정리|현재 값|
|---|---:|
|누적 명령 equivalent 예약|4,896 / 10,000|
|login 시도 예약|1 / 8; 성공0|
|전체 probe 예약|2,640 (정리2,000 포함)|
|읽기 전용 진단 예약|2,256 (정리2,000 포함)|
|남은 명령 equivalent|5,104|
|확인된 실행 원장|2, 모두 cleanupComplete=true|
|잔존 소유 schema·계정·Redis key|0|
|임시 credential 전달 파일|0; 지정 접속정보 파일은 보존|

이 수치는 보수적 runner 예약치이며 Upstash의 실제 청구 command 수가 아니다. 연결 handshake·서버 측 command 과금 수를 listener0과 동일시하지 않는다. 실행 후 공급자 콘솔의 RU/command 총량은 재측정하지 않았다. 이전 완주한 로컬 시나리오의 예약6,096보다 남은5,104가 작으므로 전체 remote probe를 다시 시작하지 않았다. 예산을 환급·초기화하거나 근거 없이 상한을 높이지 않았다.

## 보존과 재개 경계

제품 Java/YAML·FE·API·seed·JWT/Lua 정책은 이번 작업에서 바꾸지 않았다. secret·token·endpoint·사용자 식별값·로컬 절대 경로를 공개 근거에 넣지 않았다. 이번 실행의 schema·계정·key 정리를 TTL 예정으로 대신하지 않았다. 기존 전체 BE/FE/build/lint/browser 결과는 과거 로컬 근거이며 이번에 전체를 재실행한 결과가 아니다.

전체 공급자 계약과 공개 배포 준비는 false, 공급자 선택은 HOLD다. 이후 재개는 같은 private state와 누적 예산을 유지해야 하며, 실패 원인 교정과 남은 예산으로 완주 가능한지 확인하기 전에는 전체 probe를 반복하지 않는다. 접속정보 재입력·비밀번호 재설정을 요청할 근거는 현재까지 없다. 실제 클라우드 서비스 생성·배포·유료 변경·stage·commit·push는 수행하지 않았다.

[이번 최종 보존·공개 검사](evidence/PROVIDER_CONSOLE_ACCESS/final-observation/final-audit.json): 공개 예정479파일 검사 PASS, 실제 접속값 일치0, 과거 보호 파일107개 불변, 기존 파일 삭제/mode 변경0, 제품 변경0, HEAD 동일·staged0. username 접두사 교정 이후의 private checkpoint 비교이며 해당 test-source 변경은 별도28개 테스트로 검증했다.
