# Render public-demo 런타임 준비 01

**실제 로컬 TLS·컨테이너 runtime, 별도 SIGTERM 정리, BE275·FE176 회귀와 공개 검사는 PASS다.** 이번 로컬 실행·연결 설정 단계는 완료했다. 실제 Render, TiDB, Upstash, Cloudflare 서비스의 동작을 검증했다는 의미가 아니다.

```text
LOCAL_RENDER_RUNTIME_READY=true
MANAGED_PROVIDER_CONTRACTS_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```

## 기준과 범위

작업 기준은 공개 저장소의 `e8f14575b2387e130ec7ace5b4caba36144cad2c`다. 기존 소스와 미커밋 변경, 과거 portfolio evidence를 보존한다. 원 팀 저장소의 Git history를 가져오지 않으며 stage, commit, remote 변경, push, 배포를 수행하지 않는다.

이번 제품 변경은 render 설정/early guard, demo에서 분석 scheduler 미등록, demo 인증 로그의 식별값 제거, Docker 실행 경계다. 기존 일반 OAuth, demo JWT/Redis Lua/RT 재사용 정책, 소유권 검증, Chart seed와 FE 동작은 유지한다.

설정·로그·scheduler 단위 검증과 실제 TLS/packaged application/자원 제한 시험을 구분한다. 컴파일 또는 설정 테스트의 성공을 실제 TLS 연결 성공으로 확대하지 않는다.

## 실제 유효 설정 계약

`SPRING_PROFILES_ACTIVE=demo,render`, `APP_DEMO_ENABLED=true`, `APP_DEPLOYMENT_KIND=public-demo`를 함께 지정한다. `render` 단독, local-demo와 render 조합, demo와 prod/production 조합은 시작 실패다. 기존 render 없는 일반·local-demo·public-demo 설정은 새 render 검사 대상이 아니다.

새 `application-render.yml`을 실제 Spring ConfigData로 로딩하며 기존 `AuthProfileGuardConfiguration`이 일반 singleton과 DB 초기화 전에 `RenderRuntimeSettings`를 호출한다. 설정 이름만 검사하는 대신 Boot Binder로 최종 유효 값을 확인한다. 명령행·환경변수의 높은 우선순위로 안전 설정을 덮어도 검사 대상이 된다.

|항목|render 계약|
|---|---|
|PORT|플랫폼 `PORT` 필수. 빈 값·비정수·0·범위 밖 거절. 최종 `server.port`와 일치해야 함|
|bind|최종 `server.address=0.0.0.0`. 로컬 검증의 host publish는 별도로 loopback에 제한|
|Origin|기존 정확한 HTTPS browser origin. 임의 origin 추가·CORS 확대 없음|
|JPA|`ddl-auto=validate`, `generate-ddl=false`, `org.hibernate.dialect.MySQLDialect`|
|SQL 초기화|`spring.sql.init.mode=never`. Hibernate schema-generation/connection override도 검사|
|Hikari|maximumPoolSize 3, minimumIdle 0, maxLifetime 300000ms, connectionTimeout 5000ms, validationTimeout 2000ms, keepaliveTime 0|
|JDBC driver|connectTimeout 5000ms, socketTimeout 10000ms, connectionTimeZone Asia/Seoul, forceConnectionTimeZoneToSession true|
|Redis|인증 username/password 및 TLS 필수. connect timeout 3초, command timeout 2초|

Hikari 크기와 timeout은 이번 단계의 **초기 가설값**이다. 충분한 처리량이나 최적값으로 확인된 것이 아니다. keepalive query를 추가하지 않는다. 실제 런타임 dependency는 Spring Boot 3.5.5, Hibernate 6.6.26.Final, Connector/J 9.4.0, HikariCP 6.3.2를 사용한다.

### JDBC 연결

DB URL은 단일 호스트의 `jdbc:mysql://<host>:<port>/<database>?sslMode=VERIFY_IDENTITY` 형태다. DB username/password는 별도 설정으로 받는다. URL에 사용자 정보나 credential을 포함하지 않는다.

URL query allowlist는 `sslMode`와 위 네 driver 설정뿐이다. `sslMode=VERIFY_IDENTITY`가 필수이며 driver 설정을 URL에도 지정한다면 YAML과 같은 값이어야 한다. 중복 query key, legacy SSL flag, hostname 검증 완화, 임의 truststore/connection factory, URL credential은 거절한다. 다른 Connector/J 옵션이 필요하면 검토와 테스트를 거쳐 계약을 수정해야 한다.

Hikari의 별도 JDBC URL, JNDI/DataSource 경로, credential override, 임의 connection-init/test SQL과 data-source-properties 우회도 거절한다. `spring.jpa.properties`의 DDL·schema-generation·JDBC 연결 우회 역시 차단한다. 환경변수만 안전하게 보이고 최종 datasource는 다른 값을 사용하는 구성을 허용하지 않는다.

[정확한 설치 버전의 driver 정적 계약](evidence/RENDER_RUNTIME/static-driver-contract/summary.json)에서 Connector/J의 연결·socket timeout은 millisecond 정수이고, timezone 두 속성은 서버에 `SET SESSION time_zone`을 보내는 조합임을 확인했다. `Asia/Seoul` 같은 지역명은 서버 timezone 데이터가 필요하다. Hikari의 connectionTimeout은 pool 획득 대기 시간이며 query deadline이 아니다. maxLifetime은 연결별 negative variance가 있는 soft eviction으로, 사용 중인 연결을 정확히 5분에 강제 종료하지 않는다. keepaliveTime0은 예약 keepalive 없음이다.

이 근거는 dependency의 속성 정의·파싱과 bytecode를 확인한 정적 검사다. 지연 주입·pool 고갈·5분 수명 실험이나 `SELECT @@session.time_zone` 조회는 수행하지 않았다. 실제 TLS 연결 PASS와 이러한 미측정 동작은 구분한다.

이 검사는 TiDB의 모든 MySQL 호환성을 증명하지 않는다. 실제 TiDB의 schema/FK/unique/ID 생성/rollback/집계 결과와 운영 timeout은 후속 검증 사항이다.

### Redis 연결

`REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED`를 기존 Boot/Lettuce 설정에 연결한다. render에서 TLS 비활성화, credential 누락, Redis URL 대체 설정, sentinel/cluster, 다른 database index/client 또는 replica read override는 거절한다.

기존 `StringRedisTemplate`과 Lua를 그대로 사용한다. TLS에는 정상 인증서 체인과 서버 hostname 검증이 필요하다. trust-all, peer 검증 해제, 평문 fallback은 추가하지 않는다. 테스트용 CA는 전용 임시 truststore와 해당 JVM에만 적용하며 OS trust store를 바꾸지 않는다.

연결 성공과 Upstash 전체 계약 검증은 다르다. Upstash의 실제 EVAL/EVALSHA/TIME/TTL/CAS·재연결·quota·장애 전환과 폐기 일관성은 아직 검증하지 않았다. 결과가 불명확한 session 생성/rotation에 재시도 또는 성공 추정을 추가하지 않으며 provider 오류를 포괄적으로 503으로 덮는 catch-all도 추가하지 않는다.

## scheduler·인증·로그 경계

`AnalysisJobScheduler`에 `!demo` 조건을 적용한다. 전체 `@EnableScheduling`은 유지하며 일반 모드의 기존 task는 보존한다. demo에서는 scheduler bean과 scheduled task가 없어야 하고, 빈 조회 결과가 아니라 **polling 호출 자체가 0**이어야 한다.

기존 실제 JWT filter와 DemoSessionGuard, HttpOnly/Secure/Lax cookie, 정확한 Origin/header/body 계약을 유지한다. demo 성공 인증 로그는 고정 event `DEMO_AUTHENTICATED`만 출력한다. 거절 로그에도 사용자 이름·email·식별값·토큰·cookie·hash를 남기지 않는다. logger 전체를 끄는 방식으로 검증하지 않는다.

## Docker build와 실행

Docker build context는 `be`이며 그 디렉터리의 `.dockerignore`가 적용된다. 현재 실제 context의 **100개 파일**은 wrapper/build 정의, 제품 Java, application YAML과 Redis Lua의 허용 집합과 정확히 일치한다. 실제 env, key/certificate, cache, build 결과, 테스트·raw evidence 제외를 canary로 검증했다. wrapper는 저장소 실행 비트에 의존하지 않고 `bash`로 실행한다.

`.dockerignore`만으로 미래의 모든 파일을 차단한다고 주장하지 않는다. Java package 디렉터리를 다시 여는 규칙 아래에 확장자 없는 미검토 파일이 추가되면 context에 들어갈 수 있다. runner가 실제 Docker context 전체와 허용 집합을 비교하므로 그런 추가는 검증 FAIL이 된다. 새 제품 파일·resource를 추가할 때 이 검사와 내용 검토를 함께 유지한다.

Build stage는 Java 21 JDK로 `bootJar`를 만든다. runtime stage는 Java 21 JRE, 비root 사용자, 실행용 jar를 사용한다. 외부 DB/Redis가 필요한 테스트는 image build에서 실행하지 않고 별도 격리 runner가 수행한다. 실행 entrypoint는 JVM에 종료 신호가 전달되는 exec 형식이다.

```sh
# 저장소 루트에서, 검토된 공개 의존성/이미지 준비 후 수행하는 로컬 명령 예시
# 이 명령은 실제 Render 서비스 생성 또는 배포가 아니다.
docker build -f be/Dockerfile -t moneytoad-render-runtime-local be
```

Render의 후속 설정은 Docker runtime, root `be`, Dockerfile `Dockerfile`이다. start command override 없이 이미지의 `java -jar` entrypoint를 사용한다. 플랫폼 PORT 및 비밀값은 runtime에만 주입하고 Docker ARG/ENV/COPY에 넣지 않는다.

초기 `JAVA_TOOL_OPTIONS`:

```text
-Xms64m -Xmx256m -XX:+UseSerialGC -XX:ActiveProcessorCount=1
-XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Seoul
```

이 옵션은 실제 앱 runtime에만 적용한다. Gradle/test JVM을 같은 크기로 강제하지 않는다. 512MiB/0.1CPU 시험에서도 heap 이외의 메모리가 필요하므로 `Xmx256m` 자체가 OOM 방지를 보장하지 않는다. 최고 관측 메모리는 샘플링 결과이며 정확한 절대 peak라고 주장하지 않는다. cold startup·seed 시간은 로컬 관측치이고 Render SLO나 원격 DB 성능 수치가 아니다.

## 환경변수 분류

|이름|분류|값의 역할|
|---|---|---|
|SPRING_PROFILES_ACTIVE|비밀 아님|`demo,render`|
|APP_DEMO_ENABLED / APP_DEPLOYMENT_KIND|비밀 아님|`true` / `public-demo`|
|APP_DEMO_BROWSER_ORIGIN|비밀 아님|최종 브라우저의 정확한 HTTPS origin|
|PORT|플랫폼 제공|정수 TCP port. 임의 fallback 없음|
|DB_URL|연결 메타데이터|검토된 단일-host JDBC URL, VERIFY_IDENTITY, credential 없음|
|DB_USERNAME / DB_PASSWORD|비밀 취급|전용 demo DB credential|
|REDIS_HOST / REDIS_PORT|연결 메타데이터|전용 Redis TLS endpoint|
|REDIS_USERNAME / REDIS_PASSWORD|비밀 취급|Redis 인증 credential|
|REDIS_SSL_ENABLED|비밀 아님|`true`|
|JWT_SECRET|비밀|별도로 생성·관리하는 서명키. 문서에 원문 없음|
|JWT_ACCESS_SECONDS / JWT_REFRESH_SECONDS / JWT_ISSUER|비밀 아님|기존 JWT 설정 필요. demo token의 5분/1시간 상한은 기존 코드 계약|
|AI_BASE_URL|비밀 아님|CsvClient 생성에 필요한 소유 loopback placeholder. 실제 AI 연결은 활성화하지 않음|

[Render runtime 환경 예시](../../be/render.env.example)는 문서이며 Spring Boot가 자동으로 읽는 파일이 아니다. 빈 비밀값과 placeholder는 실제 환경에서 별도로 제공해야 한다. 실제 credential/env 파일을 저장소 또는 image context에 넣지 않는다. `SERVER_PORT`, `SPRING_*` 등 우선순위 높은 설정을 추가할 때도 최종 계약과 일치해야 한다. 일반 설정의 `JPA_DDL_AUTO` 문서 값은 validate로 유지하고, render의 최종 validate 검사를 우회하지 않는다.

## 재현 방법과 격리

Java 21, Node 22, Python 3, 로컬 Docker, 검토된 MySQL 8.4/Redis 7.4/Temurin 21 JDK·JRE/Node 22 relay 이미지와 explicit dependency cache를 준비한다. 현재 runner의 Java discovery와 Docker socket 처리는 macOS 로컬 환경을 대상으로 한다. 명령의 placeholder는 사용자가 소유한 경로로 대체하며 실제 경로를 공개 evidence에 복사하지 않는다.

```sh
python3 -B scripts/verification/render_runtime.py \
  --cache-seed "<owned-gradle-cache>"

# 별도 invocation으로 SIGTERM 중단 정리 확인
python3 -B scripts/verification/render_runtime.py \
  --cache-seed "<owned-gradle-cache>" --interrupt-check

python3 -B scripts/verification/render_regression.py --phase be \
  --cache-seed "<owned-gradle-cache>"

python3 -B scripts/verification/render_regression.py --phase fe \
  --dependencies "<exact-lockfile-dependency-directory>"
```

- 제품 YAML을 ConfigData로 읽고, 전용 빈 MySQL은 **테스트 준비 단계에서만** 먼저 초기화한다. 검증 대상 render runtime은 계속 validate다. 빈 schema로 validate 실패와 자동 생성 0도 별도로 검증한다.
- 전용 TLS Redis에서 trusted CA+정상 hostname+정상 credential과 세 가지 실패(CA/hostname/credential)를 분리한다. JDBC 설정 강제와 실제 JDBC TLS 인증서 시험의 결과도 별도로 기록한다.
- 전용 내부 network와 loopback host publish를 사용한다. Docker Desktop의 internal network publish 제한 때문에 소유 relay가 정해진 MySQL/Redis/app 목적지로 TCP bytes만 양방향 전달한다. TLS를 종료하거나 payload/header를 수정하지 않으며 임의 목적지 proxy가 아니다.
- 제한 시험의 실제 앱 컨테이너는 internal network에만 연결한다. host JVM을 사용하는 준비·통합·negative 시험은 소유 loopback 설정을 사용하지만 OS 수준의 전체 외부 통신 차단까지 적용한 것은 아니다. 두 격리 수준을 구분하며 host 실행을 구조적 egress 차단 검증으로 표현하지 않는다.
- 새 자원의 ownership을 확인하고 finally에서 소유 container/process/network/image만 정리한다. 정상·실패·timeout·interrupt 정리 결과를 각각 판정한다.
- runtime 이미지에서 env/private key/raw evidence/build tool 부재를 확인한다. 제한 대상은 앱 컨테이너 512MiB/0.1CPU이며 MySQL/Redis 제한과 혼동하지 않는다.
- host/container/image architecture와 에뮬레이션 여부, startup·login seed·두 방문자의 요청, 메모리 관측, OOM/종료 상태를 기록한다. runner의 고정 안전 deadline은 서비스 성능 목표가 아니다.
- 기존 BE275 및 FE176 selector와 assertion을 그대로 사용한다. 회귀는 현재 tree의 임시 복사본에서 수행하고 기존 portfolio evidence를 덮어쓰지 않는다.

신규 공개 결과는 [RENDER_RUNTIME evidence](evidence/RENDER_RUNTIME/)에 allowlist 요약으로 저장한다. 실제 인증값·식별값·절대 로컬 경로·container/process identity·raw log·certificate는 포함하지 않는다. 로컬 원문 진단과 공개 요약을 구분한다.

## 실행 결과 — 최종 PASS

[전체 runtime 검증 요약](evidence/RENDER_RUNTIME/e73529b7bb1c/runtime-summary.json)은 실패·오류·skip 없이 PASS다. 별도 [의도적 SIGTERM 요약](evidence/RENDER_RUNTIME/972320abc6ab/runtime-summary.json)은 `INTERRUPTED`와 `interruption_exercised=true`로 중단을 기록하고 정리 계약을 통과했다. 의도적 중단을 일반 runtime 완료로 세지 않는다. [BE 회귀 요약](evidence/RENDER_RUNTIME/be-final-stable/be-summary.json)과 [FE 회귀 요약](evidence/RENDER_RUNTIME/fe-final/fe-summary.json)은 이번 최종 소스에서 재실행한 결과다.

|검증|이번 실행 결과|해석 범위|
|---|---|---|
|BE compile·bootJar|PASS|현재 제품·신규 테스트 컴파일과 실제 jar 생성|
|ConfigData/guard·scheduler·로그 신규 단위 테스트|85 PASS: 설정79 + scheduler/로그6, 실패·오류·skip0|실제 제품 YAML, effective override 거절, 일반/demo 경계|
|실제 ConfigData/TLS 통합|6 PASS, 실패·오류·skip0|전용 TLS MySQL/Redis와 실제 Spring context|
|전용 MySQL 준비 후 validate 기동·schema 불변|PASS|DDL 초기화는 별도 테스트 준비에서만 수행|
|schema 없음 → validate 실패·자동 table 생성0|PASS|제품 runtime에서 빈 schema 자동 복구 없음|
|JDBC CA 불신·hostname 불일치·credential 오류|3가지 실제 실패 계약 PASS|실제 TiDB 검증은 아님|
|Redis CA/hostname/credential 오류|3가지 실제 client 원인·보호 API 업무 SQL0·login503·token/cookie0·SQL rollback 모두 PASS|원문 예외 노출 없이 원인 관측과 HTTP 계약을 별도로 검증|
|Docker 실제 context·runtime image·packaged resources|PASS|허용 파일 집합 일치, 테스트·build tool·소스·인증 자료 runtime 부재|
|512MiB/0.1CPU 실제 앱·두 방문자 auth/seed|PASS|login/session/보호 API/logout/폐기 후401, 합계 User2/Card2/Transaction480/Budget144|
|정상 종료·소유 자원 정리|PASS|잔존 소유 Docker 자원0, 소유 port 해제, OOM=false|
|별도 SIGTERM 중단 정리|PASS|TLS 서비스 준비 직후 runner 중단, 잔존 소유 Docker 자원0·소유 port 해제. 앱의 JVM 종료 신호는 별도 정상 종료 시험에서 확인|
|기존 FE176|PASS: OAuth125 + demo51, 실패·pending·todo0|기존 selector/assertion 유지|
|FE 제품·테스트/E2E 타입, OAuth/demo build|PASS|잘못된 auth mode build는 예상한 exit1로 거절 PASS|
|전체 FE lint|PASS: errors0/warnings0|규칙·대상 완화 없음|
|기존 BE275 최종 회귀|275 PASS, 실패·오류·skip0|selector/assertion 유지, 소유 자원 정리·소스/과거 근거 보존·임시 복사본 제거 PASS|
|공개 scan/projection 기존 단위 계약|14 PASS|[정제 정책 회귀](evidence/RENDER_RUNTIME/public-policy-tests/summary.json). 전체 tree 최종 검사를 대신하지 않음|
|최종 diff/public evidence scan·과거 근거 보존|PASS|[공개 검사](evidence/RENDER_RUNTIME/review-final/scan-summary.json): 후보0, 규칙 완화0. 기존 portfolio38개와 FE157개 파일 보존|

runtime·회귀·정리·공개 검사를 모두 확인해 `LOCAL_RENDER_RUNTIME_READY=true`로 판정한다. [전체 완료 요약](evidence/RENDER_RUNTIME/completion-summary.json)에 이번 결과와 보존 범위를 함께 기록했다. 과거 실패 실행은 최종 PASS로 덮어쓰지 않았다.

### 관측 환경과 자원 한계

|항목|실제 관측값|
|---|---|
|host Java / container runtime Java|21.0.11 / 21.0.12.1|
|host / Docker / image architecture|arm64 / aarch64 / arm64, 에뮬레이션 없음|
|Spring Boot / Hibernate|3.5.5 / 6.6.26.Final|
|Connector/J / HikariCP / Lettuce|9.4.0 / 6.3.2 / 6.6.0.RELEASE|
|FE 실행 도구|Node 22.14.0 / npm 10.9.2|
|앱 container 제한|512MiB, 0.1CPU|
|최고 샘플 관측 메모리|396.9MiB|
|cold startup|169.967초|
|방문자별 login·seed HTTP 시간|12.800초 / 8.794초|
|정상 정리 시 app 종료|exit143, OOMKilled=false|

이는 전용 로컬 TLS DB/Redis를 사용한 단일 실행 관측치다. 메모리 절대 peak·부하 한계·장기 안정성이나 Render 성능을 입증하지 않는다. 원격 provider 지연은 모사하지 않았고, 600초 기동 안전 deadline은 서비스 SLO가 아니다. image의 Java21 tag는 후속 rebuild에서 patch 버전이 바뀔 수 있으므로 관측 버전을 결과와 함께 보존한다.

관측 startup은 약 1분 가정을 넘었고 첫 login은 현재 FE 인증 timeout 10초를 넘었다. 이번 시험은 HTTP 검증 runner의 대기 한도로 runtime 계약을 확인했으며 실제 배포의 브라우저 UX 통과를 의미하지 않는다. FE timeout 일괄 확대 없이 readiness/명시적 서버 준비 UI/seed 지연을 별도 단계에서 검토해야 한다. 이번에는 FE를 수정하지 않았다.

### 실패·교정 이력

|근거에 기록된 단계|구분|확인 사실과 교정|
|---|---|---|
|`configuration-tests-command`|제품 guard 및 테스트 fixture|OS relaxed binding 두 사례 FAIL. fixture source 이름이 Boot OS mapper 선택 규칙과 달랐고 guard 일부가 Environment 직접 조회를 사용했다. OS mapper fixture와 Binder 최종 값 검사를 함께 교정했다. 기존 거절 assertion은 유지했다.|
|교정 후 `configuration-tests`|중간 PASS|신규84개 PASS. 이후 Hikari가 임의 driver classname을 로그에 출력하기 전에 scalar로 거절하는 제품 보강과 canary 테스트1개를 추가했으며 최신 전체 실행은85개 PASS다.84개 결과는 당시의 별도 실행 기록이다.|
|`owned-services-command`|준비 fixture|MySQL 초기화용 임시 서버에 단순 ping이 성공해도 대상 DB 준비가 끝난 것은 아니었다. 실제 대상 DB에서 SELECT1이 성공할 때까지 확인하도록 변경했다.|
|`schema-preparation-command` / `owned-services`의 `ConnectionRefusedError`|격리 network fixture|container 내부 준비 성공과 host에서 연결 가능한 상태를 혼동했다. Docker Desktop internal network의 host publish 제한을 확인하고 고정 목적지 TCP relay와 host loopback 연결 확인을 추가했다. TLS bytes는 수정하지 않는다.|
|`redis-untrusted-ca`|실패 원인 관측 fixture|제품이 예외 원문을 안전하게 숨겨 HTTP 로그에서 TLS 원인 문자열을 찾는 방식이 성립하지 않았다. 로그를 더 노출시키는 대신 실제 Spring StringRedisTemplate의 cause probe와 실제 HTTP 실패 검증을 분리했다.|
|교정 후 `redis-negative-contracts`|관측 강화 후 전체 PASS|CA·hostname·credential 원인은 실제 client의 예외 사슬에서 분류하고 공개 결과에는 종류만 남긴다. 별도 실제 login503·인증값 반환0·DB 불변과 실제 JWT/Guard 보호 API503·업무 SQL0을 세 실패 조건 모두에서 확인했다.|
|`dockerignore-actual-context`|제품 build-context 제외 설정|실제 Docker context canary가 src/test 포함을 발견해 해당 실행을 FAIL로 보존했다. 제외 설정을 교정한 후 현재 실제100개 파일의 허용 집합 exact match가 PASS했다. canary/assertion은 삭제하거나 완화하지 않았다.|

현재까지의 FAIL/BLOCKED 요약은 덮어쓰지 않는다. 각 교정이 완료된 소스와 그 소스로 수행한 후속 실행 결과를 구분한다. 최신 전체 runtime·이미지·자원 제한·정상/중단 정리·기존 회귀·공개 검사는 PASS이며 미해결 필수 로컬 FAIL/BLOCKED/skip은 없다.

별도 준비 과정에서는 npm의 사용자/전역 설정에 같은 빈 파일을 지정한 실행이 거절되어 서로 다른 전용 빈 설정으로 교정한 뒤 정확한 lockfile 설치가 성공했다. Python bytecode 쓰기 경로 권한 오류는 제품 실패가 아니며, 파일을 쓰지 않는 AST 구문 검사로 runner를 확인했다. 이 준비 오류를 제품 검증 PASS로 세지 않았다.

## 이번 변경 파일 범위

A1부터의 기존 공개 제품 변경과 구분한 이번 delta다. 파일 목록에는 인증값이나 실행별 로컬 경로를 포함하지 않는다.

|분류|파일·변경 목적|
|---|---|
|제품 설정·guard|`be/src/main/resources/application-render.yml` 신규, `global/config/RenderRuntimeSettings.java` 신규, `AuthProfileGuardConfiguration.java`의 early 호출 추가|
|제품 scheduler·로그|`analysisJob/scheduler/AnalysisJobScheduler.java`의 demo 미등록, `auth/jwt/JwtAuthenticationFilter.java`의 demo 인증 로그 식별값 제거|
|image·환경 예시|`be/Dockerfile` 수정, `be/.dockerignore` 및 `be/render.env.example` 신규|
|설정·로그 단위 테스트|`global/config/RenderRuntimeConfigurationTest.java`, `auth/demo/RenderDemoBoundaryTest.java` 신규|
|실제 TLS 통합·준비 fixture|`auth/RenderRuntimeIntegrationTest.java`, `RenderRuntimeTestSupport.java`, `RenderRedisFailureProbe.java`, `RenderSchemaPreparation.java` 신규|
|기존 profile 회귀|`auth/DemoAuthProfileBoundaryTest.java`에 일반 scheduler1/demo scheduler0 assertion과 관측 추가. 기존 OAuth/JWT/401/principal assertion 유지|
|검증 실행·공개 검사|`scripts/verification/render_runtime.py`, `render_regression.py` 신규; `fixtures/scan-classifications.json`의 합성 검증 fixture 분류 갱신|
|문서·근거|`AGENTS.md` 실행 안내, 이 문서, 새 `docs/deployment/evidence/RENDER_RUNTIME/`의 정제된 요약만 추가|

위 Java 경로는 제품이면 `be/src/main/java/com/potg/don/`, 테스트면 `be/src/test/java/com/potg/don/` 기준이다. FE 제품·package/lockfile, 일반 OAuth/RefreshTokenStore, demo JWT/Lua/seed, DB schema와 과거 portfolio 보고서·evidence는 이번 제품 수정 범위가 아니다.

## 후속 P0와 이번에 하지 않은 작업

- 실제 TiDB schema/인덱스/FK/unique/rollback/집계 검증과 초기 DDL 적용.
- 실제 Upstash primary·Lua·TTL·CAS·재연결 및 장애 전환 보장의 한계 확인.
- Cloudflare same-origin proxy·Set-Cookie/Origin/body/query/status 보존, API 캐시·POST 재시도 금지.
- Render cold-start readiness와 FE 대기 UI, 원격 DB seed 지연 측정.
- 방문 데이터 수용 상한과 출처가 확인되는 관리자 cleanup. rate limiter·cleanup scheduler·DB capacity counter는 이번 범위 밖.
- Render 직접 URL에도 인증/생성 제한이 유지되는지 확인. CORS를 서버간 인증으로 보지 않음.
- 시스템 폰트 제품 적용과 레이아웃, 서비스별 quota/과금 전환/중단 정책 확인.

Cloudflare/Render/TiDB/Upstash 생성·실제 secret 등록·실제 접속·배포, AI adapter, FE 기능 변경, JWT/Lua 정책 변경은 수행하지 않는다. 모든 로컬 검증이 통과하더라도 managed-provider 검증과 공개 배포 상태는 별도로 false를 유지한다.
