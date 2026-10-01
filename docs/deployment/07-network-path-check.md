# 네트워크 접근 경로 확인

기준일: 2026-10-01. Deploy06 지시서의 읽기 전용 콘솔 확인과 무명령 TCP 대조 범위를 적용했다. **새 TCP 연결은 0회다. 콘솔 TCP 포트와 지정 입력 파일의 포트가 달라 원격 실행 전에 중단했다.** 제품·접속정보·기존 예산·원장·marker를 변경하지 않았다.

## 결론과 원인 구분

- **CONFIRMED — 대상 포트 불일치:** 승인된 MoneyToad Upstash 콘솔의 TCP hostname은 저장 파일과 일치하지만 port는 불일치한다. 문자열 비교뿐 아니라 정수 비교에서도 false다. TLS Enabled와 저장된 TLS true는 일치한다.
- **NARROWED — 이전 TCP timeout 원인:** 입력 파일 checksum은 06 대조 실행 전 private 기록과 동일하다. 따라서 당시 입력과 제품 ConfigData가 서로 일치했다는 사실만으로 공급자 콘솔의 실제 TCP endpoint와 일치했다고 볼 수 없다. 이번 불일치는 우선 교정해야 할 구체적 후보지만, 바른 포트로 연결을 시도하지 않았으므로 이전 timeout의 단독 원인이라고 확정하지 않는다.
- **UNKNOWN — 실행 환경/네트워크 차단 여부:** OS·관리형 정책·중간망·공급자의 실제 TCP 수신 상태는 미관측이다. Chrome HTTPS 접속 성공을 Java native TCP 성공 또는 방화벽 허용으로 간주하지 않는다.

## 원격 연결 없는 확인

기존 Chrome 확장 연결로 사용자가 승인한 자원의 Details / Connect TCP 화면만 읽었다. 별도 로그인·비밀값 표시·복사·계정 변경은 없었다. 화면 새로고침 직후 로딩 중의 빈 관측은 결과로 채택하지 않았고, 로딩 완료 뒤 기존 내용과 동일한 메타데이터를 확인했다.

|관측|결과|한계|
|---|---|---|
|MoneyToad 대상 자원|확인|다른 프로젝트·자원 접근0|
|현재 표시 플랜/지역|Free Tier / AWS Singapore|새 자원·유료 변경0|
|선택한 연결 방식|TCP|REST endpoint를 TCP로 대체하지 않음|
|hostname ↔ 지정 파일|일치 true|실제 값은 공개하지 않음|
|port ↔ 지정 파일|**일치 false**|문자열·정수 비교 모두 불일치|
|화면 Port ↔ TCP 명령 예시의 port|일치 true|명령 실행·비밀 표시0|
|TLS Enabled ↔ 지정 파일|일치 true|TLS handshake/인증서 성공 검증이 아님|
|토큰 표시|마스킹 확인|credential 원문·clipboard·screenshot 수집0|
|서비스 operational status / 접근 정책|**NOT_OBSERVED**|Details가 열린다는 이유로 Active/정상 수신을 추정하지 않음|
|입력 안전 parser|PASS|기존 owner·700/600·regular file·single-link 조건 유지; 공급자 연결 성공을 뜻하지 않음|
|입력 파일 ↔ 06 실행 전 checksum|일치 true|재작성·비밀번호 재설정0|

콘솔의 UI 조회에 사용하는 HTTPS 요청과 진단 대상의 native TCP 접속 수를 구분한다. 콘솔의 관리 API 내부 요청 수는 측정하지 않았다.

## 실행 권한 확인과 미확인 범위

현재 대화에 제공된 실행 정책은 workspace-write, 제한된 기본 network, auto-review 승인 방식이다. 작업 대상 공개 저장소는 기본 쓰기 workspace 밖이므로 이번 문서 저장에도 범위가 명시된 도구 승인을 사용한다. 이것을 대상 TCP 경로의 승인이나 성공으로 해석하지 않는다.

직전06의 실제 실행 호출에는 `sandbox_permissions=require_escalated`가 지정돼 있었고 해당 명령은 실행 결과를 반환했다. 도구 계약은 이 옵션을 승인된 sandbox 밖 실행으로 정의한다. **직전 검사를 단순한 기본 sandbox 내부 실패로 다시 분류할 근거는 없다.** 그러나 실행 응답만으로 OS/MDM/VPN/방화벽·중간망까지 무제한이었다고 증명할 수는 없다. 이번에 그와 다른 네트워크 권한 환경을 확보했다고 주장하지 않는다.

관련 사용자 설정의 권한 키만 제한적으로 조회했다. 이 조회에서 별도 적용값을 확인하지 못했으며, 설정 전체·다른 credential·관리형 정책 내부를 탐색하지 않았다. 설치 앱의 버전 번호는 확인하지 못했다. 실제 현재 정책은 대화에 제공된 런타임 정책과 도구 계약을 근거로 구분한다. 이전 보고서의 proxy/JVM 환경변수 부재도 OS 제한 부재의 증거로 쓰지 않는다.

공식 문서상 sandbox 명령, 승인된 escalation, Chrome/Computer Use는 각각 별도 통제 범위다. macOS 명령 sandbox는 Seatbelt를 사용하며 브라우저 성공만으로 명령 네트워크 정책을 판정할 수 없다. [OpenAI Permissions — scope and enforcement](https://learn.chatgpt.com/docs/permissions#scope-and-enforcement)

Full Access 상시 설정, config 변경, 방화벽/VPN/DNS/hosts 변경, 별도 Terminal 자동 실행은 하지 않았다. 대상 불일치가 먼저 확인됐으므로 신규 native 연결 승인 요청도 하지 않았다. **권한 비교 완료 또는 실행 권한 차단 확정으로 표시하지 않는다.**

## 대조별 실행 여부

|대조|상태|이유|신규 대상 연결|
|---|---|---|---:|
|A 다른 승인 환경 / 3초|NOT_RUN|이전도 승인 실행이었고 다른 권한 환경의 증거가 없음. 같은 조건 반복 제외. 추가로 대상 포트 불일치 확인.|0|
|B 진단 전용 / 10초|BLOCKED_TARGET_MISMATCH|지시서의 추가 접속 전 목표 확인 조건 미충족. 승인된 기존 port를 임의로 바꾸거나 잘못된 대상에 반복 연결하지 않음.|0|
|DNS 조회|NOT_RUN|기존 DNS 결과 재사용으로 현재 주소를 추정하지 않음. 주소 변경 여부 NOT_ESTABLISHED.|0|
|TLS / Lettuce / AUTH / PING|NOT_RUN|이번 승인 범위 밖.|0|

새 연결의 timeout·소요 시간은 **측정값 없음**이다. 이전06의 3초 timeout은 과거 결과로 남기고 이번 관측으로 재사용하지 않는다. 무명령 진단용 새 Java/Python 진입점도 만들지 않았다. 대상 확정 이전에는 실행 준비 코드의 필요성이 없어 기존 입력 parser만 재사용했다.

## 예산·정리·보존

- 이번 신규 Upstash TCP 연결 **0/2**, Redis 명령 **0**, login/schema/User/key/원격 자원 생성 **0**.
- 기존 예약 **9,408/10,000**, 잔여 **592**, login 시도 **1/8** 보존. 예약 환급·초기화·증액·새 2,000 정리 예약0. 이번 무명령 예외를 기존 probe 실행에 사용하지 않았다.
- 기존 private 파일 전체 checksum과 예산·원장4개의 cleanup 확인값 보존. 현재 owner/mode 안전 조건도 확인. 접속정보 checksum·안전 조건 보존.
- 이번 socket/client/Java child/DNS worker 생성0. 원격 정리 **NOT_NEEDED_NO_REMOTE_RESOURCES_CREATED**, 소유 연결/프로세스 잔존0. 연결 실패 또는 TTL을 cleanup PASS로 대신하지 않았다. 사용자의 기존 Chrome 탭은 닫거나 변경하지 않았다(읽기용 새로고침만 수행).
- 제품·검증 코드·기존 테스트 변경0. 전체 BE/FE/E2E와 과거 JDK/Lettuce/managed probe 재실행0. 보조 코드 추가가 없어 새 합성 테스트 묶음도 만들지 않았다.
- 새07 보고서와 최신 STATUS만 저장한다. 과거 보고서·evidence·Git HEAD/index·기존 미커밋 파일을 보존한다. 공개 스캔은 과거 evidence를 덮어쓰지 않는 private 복사본에서 수행한다.

## 저장 전후 공개·보존 검사

공개 후보498개 파일의 실제 내용을 private 복사본에서 검사해 공개 스캔 PASS, 미분류 후보0을 확인했다. 지정 입력의 실제 민감값 일치 검사도0건이다. 시작497개 파일 중 변경 허용 대상은 STATUS 하나이고 새 파일은 이07 보고서 하나다. 기존 코드·과거 evidence·HEAD·index는 보존하며 Git 공백 검사도 수행한다. 실제 민감값·콘솔 hostname/port·IP·개인 절대 경로·예외 원문은 보고서에 저장하지 않는다.

## 다음 조치 한 건

**지정 입력 파일의 `REDIS_PORT`만 현재 승인 자원의 콘솔 TCP 포트와 일치시키는 별도 승인**이 필요하다. 비밀번호 재설정·파일 전체 재작성·재로그인은 필요 없다. 이번 지시서는 credential/접속정보 변경을 금지하므로 자동 교정하지 않았다. 포트 수정과 해당 목표에 대한 제한된 후속 연결이 명시적으로 승인되기 전에는 원격 대조를 재개하지 않는다.

전체 기능 probe에 필요한 최소 추가 예약5,504(총상한15,504)는 이전 계산을 유지하며 이번에 승인되거나 실행되지 않았다. TCP 성공만으로 login·seed·Lua·TTL·회전·폐기·재연결 또는 failover 보장을 PASS로 바꾸지 않는다.

```text
NETWORK_PATH_CAUSE=NARROWED
CONSOLE_PORT_MISMATCH=CONFIRMED
EXECUTION_PERMISSION_COMPARISON=NOT_ESTABLISHED
NETWORK_PATH_TCP_STATUS=BLOCKED_TARGET_MISMATCH
NEW_TARGET_TCP_CONNECTIONS=0
NEW_REDIS_COMMANDS=0
COMMAND_RESERVATION_TOTAL=9408
COMMAND_RESERVATION_REMAINING=592
UPSTASH_NATIVE_CONNECT_VERIFIED=false
LOGIN_RECOVERY_VERIFIED=false
MANAGED_PROVIDER_CONTRACTS_VERIFIED=false
UPSTASH_FAILOVER_REVOCATION_GUARANTEE=NOT_ESTABLISHED
PUBLIC_DEPLOYMENT_READY=false
```

stage·commit·push·서비스 생성·배포는 하지 않았다.
