# 배포 준비 02 — 관리형 공급자 계약 확인

**실제 공급자 검증은 BLOCKED다.** 지정한 `~/.config/moneytoad/provider-check.env` 파일이 없어 TiDB·Upstash에 연결하지 않았다. 설정 원문을 다른 파일에서 찾거나 기존 환경변수를 대신 사용하지 않았다. 원격 연결·쓰기·로그인·생성 자원은 모두 0이다.

```text
TIDB_CONTRACTS_VERIFIED=false
UPSTASH_FUNCTIONAL_CONTRACTS_VERIFIED=false
UPSTASH_FAILOVER_REVOCATION_GUARANTEE=NOT_ESTABLISHED
PROVIDER_SELECTION=HOLD
MANAGED_PROVIDER_CONTRACTS_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```

현재 작업은 접속정보가 없을 때 지시서에서 허용한 안전한 사전 점검·DDL 준비·로컬 검증과 문서 조사까지 수행한다. 로컬 MySQL/Redis 성공을 TiDB/Upstash 성공으로 표시하지 않는다. 공급자에 쓰는 원격 실행기는 아직 구현·검증하지 않았으며, 이 문서의 원격 시나리오는 다음 실행의 계약이다.

## 기준과 이번 변경 범위

기준 HEAD는 `e8f14575b2387e130ec7ace5b4caba36144cad2c`, branch는 `main`이다. 시작 시 기존 추적·미추적 파일 387개와 Git 상태를 비공개 해시 원장으로 확보했다. 미커밋 Render runtime 변경과 과거 보고서/evidence를 포함한다.

기존 [Render runtime 보고서](01-render-runtime.md), 공개 README, AGENTS, 공개 검증 진입점 및 아키텍처를 읽었다. 기존 제품 YAML·JPA·JWT·Lettuce·Lua·로그인 보상·소유권·seed와 FE는 변경하지 않는다. 이번 신규 파일은 다음과 같다.

- `scripts/verification/managed_provider_preflight.py`: 값 노출·연결·쓰기 없는 입력 안전 점검. 유효한 입력이어도 원격 검증 완료를 반환하지 않는다.
- `scripts/verification/test_managed_provider_preflight.py`: 파일·경로·권한·파싱·공개 출력의 합성 단위 검증.
- `scripts/verification/fixtures/managed-provider-schema.sql`: 현재 7개 JPA entity의 검토 가능한 초기 DDL.
- `be/src/test/java/com/potg/don/auth/ManagedSchemaPreparation.java`: 전용 로컬 TLS MySQL에서 명시적 DDL 적용 후 실제 ConfigData와 validate를 확인하는 테스트 전용 도구.
- `scripts/verification/managed_schema_local.py`: 기존 격리 기반을 private snapshot에서 재사용하는 로컬 DDL 검증 진입점. 원격 credential 파일을 읽지 않는다.
- `scripts/verification/test_managed_schema_local.py`: 정확한 private 변환 범위와 반복·잘못된 변환 거절 검증.
- 이 문서와 `evidence/MANAGED_PROVIDER_CONTRACTS/`의 새 정제 요약.

기존 `scan-classifications.json`에는 신규 helper의 합성 schema 입력 한 줄만 위치·원문 줄 digest에 한정해 추가했다. 기존 분류는 전부 보존했다. 실제 비밀값이나 실행 identity를 예외 처리하지 않는다. 그 외 기존 386개 파일은 바꾸지 않았으며 제품·과거 evidence는 그대로다.

## 입력 사전 점검

필요한 키는 `TIDB_HOST`, `TIDB_PORT`, `TIDB_SETUP_USERNAME`, `TIDB_SETUP_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED`뿐이다. 파일은 덮어쓰지 않는다. 이번에는 파일 부재로 실제 owner·mode 검증까지 진행할 수 없었다.

준비한 검사기는 각 경로 구성요소를 symlink를 따라가지 않고 열고, 열린 inode 기준으로 부모 디렉터리 owner/700 및 regular file owner/600을 검사한다. 다중 hard link, FIFO, 과대 파일, 잘못된 인코딩, 중복·누락·placeholder·보간 입력을 거절한다. 값은 allowlist로만 파싱하고 shell source/eval, 환경변수 확장, dotenv 자동 로딩을 사용하지 않는다. DNS hostname을 유지하고 REST URL·IP 대체·평문 Redis 설정을 거절한다. 파일 내용이나 host도 공개 결과에 출력하지 않는다.

```sh
# 네트워크 요청 없이 점검. 원격 검증 미실행이므로 exit 2/BLOCKED가 정상이다.
python3 -B scripts/verification/managed_provider_preflight.py

# 공개 파일 저장도 하지 않는 점검
python3 -B scripts/verification/managed_provider_preflight.py --check-only

# 합성 단위 검증
python3 -B -m unittest discover -s scripts/verification -p 'test_managed_provider_preflight.py'
```

파일이 준비돼도 이 명령은 자동으로 공급자에 연결하지 않는다. 필요한 값은 로컬 편집기로만 준비하며 채팅·문서에 원문을 전달하지 않는다. 원격 실행 재개 시 사용자가 제공한 전용 endpoint/Free 상태/region을 먼저 확인하고 아래 쓰기 범위를 실행 전에 안내해야 한다.

## 초기 DDL과 호환성 경계

현재 JPA에는 7개 테이블이 있다. `dummy`도 무조건 등록되는 entity이므로 validate를 위해 빈 테이블이 필요하다. legacy CSV나 dummy data를 사용한다는 뜻이 아니다.

|테이블|주요 계약|
|---|---|
|users|BIGINT identity, email unique/non-null, name non-null, created_at DATETIME(6)|
|cards|BIGINT identity, unique/non-null user FK, card_no/cvc NULL 허용|
|transactions|card FK, DATETIME(6), INT 금액, 가맹점·category 문자열|
|budgets|user FK, DATE, INT 금액, BIT(1), 예측/수정 시각|
|analysis_job|enum 상태·DATETIME(6), user 값은 기존 매핑대로 scalar이며 새 FK를 만들지 않음|
|peer_transaction_stats|기존 age/gender/date/amount 매핑. 데이터 생성 없음|
|dummy|기존 길이·필수 칼럼·category index. 데이터 생성 없음|

DDL에는 7개 CREATE TABLE만 있으며 database/account 생성·선택·삭제, 기존 테이블 수정, 데이터 입력은 없다. IF NOT EXISTS로 기존 schema에 덮어 적용하지 않는다. DDL 파일의 고정 checksum과 예상 테이블 집합을 로컬 helper가 확인한 뒤 비어 있는 소유 schema에서만 적용한다. DDL 자체는 트랜잭션 rollback 대상이라고 가정하지 않고, 준비 중 실패하면 해당 실행이 소유한 schema 전체의 정리를 별도로 확인한다.

기존 MySQL 8.4 검증의 collation은 `utf8mb4_0900_ai_ci`다. TiDB 기본 collation을 그대로 사용해 unique/문자열 비교 의미가 바뀌지 않도록 이 값을 명시했다. TiDB는 버전별 지원 차이가 있으므로 실제 버전·지원 여부·대소문자 및 trailing-space 비교를 확인해야 한다. 미지원이면 실패로 남기고 bin 등으로 자동 대체하지 않는다. [TiDB 문자 집합·collation](https://docs.pingcap.com/tidbcloud/character-set-and-collation/)

실제 제품은 `demo,render`, `ddl-auto=validate`, `generate-ddl=false`, SQL init never다. JDBC allowlist와 `sslMode=VERIFY_IDENTITY`, Connector/J의 Asia/Seoul·session timezone 강제 설정을 유지한다. TiDB Starter는 TLS를 요구하지만 문서의 일반 예제만으로 현재 Hikari/driver/schema 조합이 통과했다고 판단하지 않는다. [공식 JDBC 연결 안내](https://docs.pingcap.com/developer/dev-guide-sample-application-java-jdbc/)

TiDB의 FK·unique·ENUM/BIT·generated keys·rollback·집계 타입은 실제 연결 검증 대상이다. auto-increment 값의 연속성은 요구하지 않는다. TiDB를 MySQL과 동일하게 가정하지 않으며, 기존 소유권/집계 assertion을 provider에 맞춰 느슨하게 변경하지 않는다.

## 재개할 원격 쓰기·정리 계약

원격 runner는 아직 실행하지 않았다. 아래 절차를 만족하는 제한된 probe를 먼저 구현·검토한 뒤 연결한다. 기존 BE 전체 runner를 원격 환경에 연결하지 않는다.

1. 제공된 전용 host/port 외에는 연결하지 않는다. 실제 DNS hostname과 시스템 신뢰 체인으로 TLS를 검증하며 IP 치환·trust-all·평문 fallback은 없다. provider HTTP 관리 API나 REST Redis로 바꾸지 않는다.
2. 이번 실행에서 고유한 `moneytoad_contract_<run-id>` schema 하나를 생성한다. 동명 schema가 있으면 즉시 중단한다. 생성 사실을 private 원장에 기록하고 명시 DDL을 적용한다.
3. 가능하면 setup과 별도로 해당 schema에만 권한이 있는 runtime 계정을 생성한다. DDL setup 이후 앱은 validate로만 실행한다. 계정 분리가 불가능하면 실제 권한을 기록하고 운영 최소 권한 PASS로 표시하지 않는다.
4. 제품 DemoSessionStore를 호출하는 테스트 전용 경계에서 새 키를 **쓰기 시도 전에** private 원장에 기록한다. 성공 HTTP 응답에서만 키를 수집하면 create 결과 유실 때 정리 대상을 잃을 수 있다. 원문 키·사용자 값·토큰·hash는 공개하지 않는다.
5. KEYS/전역 SCAN/FLUSHDB/FLUSHALL/SCRIPT FLUSH/Redis CONFIG, table 삭제, create-drop, provider instance 삭제는 금지한다. 소유한 정확한 session key만 사용한다.
6. 세션 폐기→앱 종료→남은 정확한 키 삭제 확인→이번 생성 사실이 있는 schema/runtime 계정 정리 순서로 종료한다. 정상·실패·timeout·interrupt 모두 finally 경로로 시도하며 연결 실패를 cleanup PASS로 기록하지 않는다. TTL 만료 예정과 실제 삭제 확인은 별개다.
7. 소유권 또는 삭제 결과가 불명확하면 삭제 범위를 넓히지 않고 비공개 정리 원장을 남긴다. 공개 결과에는 잔존 개수·범주만 기록한다.

## 유한 probe와 예상 비용

일반 자원에서 A/B 두 방문자의 전체 조회·변경·격리를 검사하고, C는 logout, D는 짧은 테스트용 만료를 확인한다. 제한 자원에서 E/F 두 방문자의 시작·login·최소 조회를 관측한다. 성공 login 계획은 **6회**, 절대 상한은 **8회**, 동시 방문은 **2명**이다. 각 login은 314행이므로 6회 1,884행, 8회 상한 2,512행이다. 이는 row 수이며 실제 저장 bytes·RU 추정으로 바꾸지 않는다. 실패 시나리오는 별도 유한 attempt cap을 두고 알려진 실패를 반복하지 않는다.

|Redis 연산|횟수 예산|Lua 내부+외부 호출 합계(복제 추가분 제외)|
|---|---:|---:|
|create|16|16 × (4 + EVALSHA/EVAL 2) = 96|
|findActive|100|100 × (5 + 2) = 700|
|rotate/reuse|20|20 × (6 + 2) = 160|
|개별 관측·삭제|120|120|
|새 연결 handshake 예비량|12 × 16|192|
|합계|—|1,268 command-equivalents|

운영 예산 2,000, 절대 한도 10,000 안에서 정리 여유를 따로 확보한다. 이 값은 실행 전 계산이며 공급자 과금 계측치가 아니다. 실제 Lettuce listener에서는 command 이름·개수만 세고 인자·응답을 저장하지 않는다. 자동 재연결의 반복 때문에 wall-clock deadline만으로 명령 상한을 보장할 수 없으므로, 수량 제한과 종료·정리 예산을 함께 적용해야 한다. Lua 내부 명령도 비용에 포함될 수 있다. [Upstash Lua 비용 설명](https://upstash.com/docs/redis/sdks/ratelimit-ts/costs)

위 1,268은 read-region 복제 추가분을 제외한 값이다. 원격 실행 전에 실제 read region 구성을 확인하고 공식 설명의 `(1 + readRegionCount) × writeCommandCount + readCommandCount`를 반영하거나 전체 합계에 보수적 복제 배수를 적용해 예산을 다시 산정한다. 구성 미확인 상태에서 이 숫자를 공급자 과금 상한으로 확정하지 않는다. 재산정 값이 10,000 이하인지 확인한 뒤에만 진행한다.

실제 계정의 Free 플랜·region·잔여 quota·TiDB RU/storage·Upstash command 전후 값은 **미확인**이다. 이번에는 0회 연결·0회 원격 command이므로 관측 비용도 없다. 무료 한도 수치를 현재 계정의 잔여량으로 해석하거나 1 SQL=1 RU, 1 HTTP=1 Redis command로 계산하지 않는다.

문서상 TiDB Starter Free는 대상 인스턴스마다 row 5GiB, columnar 5GiB, 월 50M RU를 설명한다. row 저장 예산을 10GiB로 합치지 않는다. 한도 도달 시 신규 연결 거절·기존 연결 throttle이 가능하다. Upstash Free 문서는 256MB, 월 500K commands, bandwidth 10GB를 설명하지만 현재 계정의 잔여량이나 월 quota 도달 시 정확한 오류 응답은 확인하지 못했다. 한도 소진 시험은 하지 않는다. [TiDB tier·한도](https://docs.pingcap.com/tidbcloud/select-cluster-tier/) · [Upstash pricing](https://upstash.com/pricing/redis)

## 실제 공급자에서 확인할 항목 — 모두 BLOCKED

|필수 계약|실행 방법·판정|
|---|---|
|TiDB 접속·버전·권한|actual endpoint/plan/region 및 JDBC/DB 버전, 제한 runtime 계정, TLS identity 인증. 비밀값 비공개|
|validate|명시 준비한 빈 schema에서 실제 제품 ConfigData로 시작, 전후 schema 구조 불변|
|timezone·날짜|앱 DataSource가 얻은 connection에서 session timezone 조회, 윤일/경계일 DATE·DATETIME 왕복. 별도 CLI 결과로 대체하지 않음|
|제약·타입·rollback|unique/FK 위반, null financial fields, IDENTITY generated key, 문자열/enum/boolean/집계 타입, SQL 실패 후 rollback|
|seed·HTTP·격리|실제 JWT/Guard/API 두 방문자, 각각 1/1/240/72, Job0, 금융정보 NULL, B 전 컬럼 불변|
|집계·수정|연간 9,990,000, 기준월 908,000/누수18,000, 실제 PATCH 뒤 908,000/누수0, annual leaked=false|
|Upstash native 계약|Lettuce/StringRedisTemplate·제품 Lua 원문, EVAL/EVALSHA/TIME/HASH/PTTL/PEXPIREAT/반환 타입|
|세션 수명|create/findActive/rotate/revoke, raw RT 미저장, 절대 만료 유지, 폐기·만료 후 이전 AT401|
|동시 RT|동일 RT 최대1교환 성공, reuse 후 최종 session 없음, 승자 토큰도 접근 거절|
|재연결|서로 다른 실제 연결 및 명시 reconnect 후 조회·회전·폐기. 장애전환 시험 아님|
|결과 유실·불가용|로컬 수송 경계 실패 주입, 성공 추정·자동 업무 재시도·재생성0. 실제 공급자 장애 유발 아님|
|자원 제한·지연|충분한 로컬 자원 후 같은 코드 512MiB/0.1CPU. Mac→provider 관측이며 Render 측정 아님|
|정리|이번 실행의 정확한 키/schema/계정/local resources 삭제 확인. TTL만으로 PASS하지 않음|

지금의 BLOCKED 원인은 접속정보 파일 부재이며, 실제 호환성 FAIL이 발견된 것은 아니다. 파일이 준비된 뒤 원격 runner와 write-ahead 소유 원장을 구현·검토하고 실행해야 한다. 실패를 발견하면 고정 분류와 최소 수정 후보만 기록하고 현재 제품 정책은 유지한다.

## Upstash 문서상 보장과 미검증 범위

|항목|문서상 범위|이번 실제 관측|
|---|---|---|
|Lua|EVAL/EVALSHA의 원자 실행. NOSCRIPT일 때 EVAL fallback 가능|미실행|
|연결 내 순서|TCP 단일 연결의 causal consistency / read-your-writes 설명|미실행|
|복제|eventual consistency, 비동기 복제 및 last-write-wins. strong consistency는 deprecated|문서 확인만|
|acknowledged revoke/CAS의 장애전환 내구성|현재 요구하는 무손실 보장을 확립하지 못함|장애전환 유발·시험 없음|

공식 문서: [native compatibility](https://upstash.com/docs/redis/overall/compatibility), [EVAL](https://upstash.com/docs/redis/commands/scripting/eval), [EVALSHA](https://upstash.com/docs/redis/commands/scripting/evalsha), [consistency](https://upstash.com/docs/redis/features/consistency), [global database](https://upstash.com/docs/redis/features/globaldatabase), [eviction](https://upstash.com/docs/redis/features/eviction).

일반 Lua를 primary로 보낸다는 사실이나 정상 재연결 성공은 복제·장애전환의 strong consistency 증거가 아니다. CAS 결과 수신 실패 뒤 무조건 재시도하면 제품의 reuse 폐기 정책과 충돌한다. 정당한 NOSCRIPT fallback과 불명확한 결과 뒤 업무 재실행을 구별한다. eviction으로 세션이 없어지면 인증이 거절되는 fail-closed 동작과, 이미 폐기된 세션이 장애전환 뒤 되살아나지 않는다는 보장은 다른 문제다.

따라서 기능 호환성을 향후 모두 통과해도 `UPSTASH_FAILOVER_REVOCATION_GUARANTEE=NOT_ESTABLISHED`는 별도로 남을 수 있다. 해당 보장이 필요한 공급자 채택은 **HOLD**다. 정책 완화나 공급자 교체를 이번에 수행하지 않는다.

## 로컬 DDL 검증과 이번 실행 결과

로컬 DDL helper는 리뷰한 SQL만 적용하고 실제 `application.yml`+`application-demo.yml`+`application-render.yml`을 통해 validate한다. 기존 생성 helper의 create 경로를 사용하지 않는다. 연결은 소유 loopback TLS MySQL에만 허용하며 실제 공급자 입력은 읽지 않는다. DDL 검증 후 새 probe 행을 모두 rollback하고 기존 Render 실제 TLS·인증 회귀를 수행한다.

```sh
python3 -B scripts/verification/managed_schema_local.py \
  --cache-seed "<owned-gradle-cache>"
```

|이번 실행|결과·범위|근거|
|---|---|---|
|실제 입력 사전 점검|BLOCKED, 파일 부재, 원격 연결/쓰기0|[사전 점검](evidence/MANAGED_PROVIDER_CONTRACTS/8b2d7d388ea1/preflight-summary.json)|
|신규 준비 단위 계약|26 PASS, failure/error/skip0. 입력 안전21 + private 변환5|[단위 요약](evidence/MANAGED_PROVIDER_CONTRACTS/preparation-tests/summary.json)|
|기존 공개 보호 단위|14 PASS, failure/error/skip0|동일 단위 요약|
|신규 Python 구문|4개 AST PASS. Python lint 설정이 없어 새 lint 도구는 설치하지 않음|동일 단위 요약|
|DDL·helper 컴파일/기동|7테이블 명시 DDL 적용, actual ConfigData validate, schema 구조 불변 PASS|[로컬 DDL 전체 결과](evidence/MANAGED_PROVIDER_CONTRACTS/local-ddl-01/local-ddl-summary.json)|
|실제 앱 JDBC 로컬 probe|session Asia/Seoul, 윤일·연말/연초·월 경계 DATE/DATETIME(6), 한국어·BIT, unique·FK·generated key·금융정보NULL·SUM·rollback PASS|동일 로컬 결과. fixed helper 성공은 모든 내부 assertion의 성공을 요구|
|관련 기존 Render 회귀|85 설정/경계 + 6 실제 TLS 통합 = 91 PASS, failure/error/skip0. 기존 selector/assertion 유지|동일 로컬 결과|
|로컬 TLS 실패 경계|JDBC/Redis CA·hostname·credential 거절, Redis 실패503·SQL 불변·보호 업무 실행0 PASS|동일 로컬 결과|
|제한된 패키지 실행|512MiB/0.1CPU, 두 login과 2/2/480/144행 증가, logout·폐기 후401 PASS|동일 로컬 결과|
|정리|소유 Docker 자원0, 포트 해제, child group 해제, private 사본 제거 PASS|동일 로컬 결과|
|실제 TiDB·Upstash 기능/원격 지연|전부 BLOCKED. 실행0, 공급자 환경/region/권한 미확인|사전 점검 및 이 문서의 필수 계약 표|

초기 새 adapter 단위 테스트 1개는 변환을 되돌리는 테스트 코드에서 진단 정규식 조각을 잘못 제거해 실패했다. 역변환 테스트만 교정한 후 26개가 통과했으며 기존 runner·제품·assertion은 바꾸지 않았다. [초기 준비 오류](evidence/MANAGED_PROVIDER_CONTRACTS/preparation-tests/initial-fixture-failure.json)를 보존했다. 새 클래스 컴파일 실패를 기능 RED로 기록한 것이 아니다.

로컬 adapter는 현재 소스의 private copy에서 준비 helper 클래스·검토 DDL 경로·고정 실패 진단 marker 3곳만 exact-count로 변경한다. 원본 runner와 제품/기존 테스트에는 이 변환을 저장하지 않는다. 누락·중복·재적용이면 거절한다. 기존 create helper를 호출하지 않고 명시 DDL 적용을 사용하는 차이다. 기존 회귀 assertion을 생략하거나 완화하지 않았다.

실제 공급자 단계의 미실행은 위 로컬 PASS로 해제하지 않는다. 최종 공개 검사·기존 분류 보존·원본 파일 해시는 별도 [검토 요약](evidence/MANAGED_PROVIDER_CONTRACTS/final-review/summary.json)에 기록한다.

## 지연·cold start와 남은 작업

이번 TiDB/Upstash startup·첫 login·다음 login·session·refresh·주요 GET 표본 수는 각각 **0**이며 수치를 만들지 않는다. 실제 provider 버전·region·계정 권한·RU·command 사용량도 확인하지 못했다.

이전 [로컬 Render runtime 근거](evidence/RENDER_RUNTIME/e73529b7bb1c/runtime-summary.json)의 startup169.967초, login12.800/8.794초, 최고 샘플 메모리396.9MiB는 로컬 TLS 서비스 결과다. TiDB/Upstash 또는 Render Singapore 측정이 아니다. 첫 login은 현재 FE10초보다 길고 startup은 기존 readiness90초 가정보다 길다. 향후 runner의 startup600초/login60초 관찰 한도는 제품 FE timeout 또는 UX 완료 기준과 다르다.

이번 DDL 로컬 재실행도 표본1회이며 startup145.206초, 첫/후속 login17.396초/4.802초, 최고 샘플 메모리400.2MiB, OOM=false, 정상 SIGTERM exit143이었다. Java host21.0.11/container21.0.12.1, arm64 native, Connector/J9.4.0/Hikari6.3.2/Lettuce6.6.0으로 관측했다. 이 값 역시 소유 로컬 DB/Redis에 대한 결과이며 원격 지연을 모사하지 않았다. 첫 login10초·readiness90초 부족 가능성이 남지만 이번에 FE를 수정하지 않았다. session·refresh·주요 GET의 원격 표본은 계속0이며 p95나 수용 인원은 계산하지 않는다.

기존 BE366/FE176/typecheck/build/lint PASS는 이전 근거이며, 이번에 전체를 다시 실행했다고 표기하지 않는다. Cloudflare·Render 생성, 유료 전환, 실제 secret 등록, 공개 배포, FE timeout/readiness/font, cleanup·수용 상한·AI 구현과 Git stage/commit/push는 수행하지 않았다.

다음 최소 작업은 **지정한 전용 연결정보로 소유 원장을 갖춘 제한된 provider probe를 구현·실행**하는 것이다. 초기 schema·runtime 최소 권한, 실제 ConfigData validate, 두 방문자 수정·격리, 제품 Lua와 reconnect, 별도의 로컬 실패 주입·제한 자원 관측, 정확한 자원 정리가 모두 확인돼야 기능 계약을 PASS로 표시한다. 장애전환 폐기 보장은 별도 검토를 유지한다.
