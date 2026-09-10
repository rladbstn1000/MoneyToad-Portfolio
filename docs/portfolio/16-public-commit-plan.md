# 공개 allowlist와 6개 누적 commit snapshot 감사

**파일 구성·기술 검증 PASS. `READY_TO_INIT_PUBLIC_REPO=false`.** 과거 자격증명 폐기·회전과 팀 코드/이미지 재게시 범위의 외부 확인이 아직 없다. 이는 기능 테스트 실패와 별개의 P0 공개 승인 조건이다. 실제 Git 저장소 생성·stage·commit·remote 등록·push·PR·merge·배포는 하지 않았다.

## 산출물과 원본 보존

- [최종 파일 manifest](final-manifest.md)는 포함 파일마다 상대 경로, A~F 범주, 이유, 출처, 후속 수정 여부, 모든 commit 전환을 기록한다. 원본 후보와 이전 공개 후보에서 제외한 파일은 X로 별도 나열한다.
- 원본의 tracked/nonignored untracked 실파일 1,269개와 branch·HEAD·index·status가 시작/종료 시 동일하다. [보존 결과](evidence/source-preservation.json).
- 원본 HEAD는 `c35d37e82d7273733d45100492b87f12d73fd92b`다. 원본 작업 트리를 되돌리지 않고 Git object를 읽어 검토된 경로의 바이트만 복사했다. 기존 Git 디렉터리/이력은 어느 snapshot에도 없다.
- 6개 디렉터리는 각각 완전한 누적 파일 tree다. patch나 다른 snapshot을 실행 중 참조하는 구조가 아니다. 임시 검증 산출물·의존성·cache·자원 소유권 기록은 이 tree들 밖에 두었다.
- 1~5단계 검증이 생성한 문서는 최종 tree 밖으로 옮기고, 정제된 결과만 6단계에 포함했다. 제품 코드·기존 테스트·E2E TypeScript 시나리오·최종 package/lockfile의 바이트는 기존 공개 후보와 동일하다.

## 6개 경계와 권장 메시지

번호를 1~6으로 정규화했다. 경로는 `<snapshot-parent>` 아래의 sibling directory다. 정확한 개별 파일 목록과 중복 전환은 manifest를 기준으로 한다.

| 단계 / 디렉터리 | 목적과 포함 범위 | 제외 및 의존 관계 | 권장 commit message |
|---|---|---|---|
| 1 / `01-baseline` | 검토한 팀 HEAD 코드·자산·build 입력 202개 + 공개 root ignore 1개 | demo·후속 테스트·AI subtree·CSV·내부 문서·기존 env 예제·Git 이력 제외. 독립 baseline | `chore(repo): import reviewed MoneyToad baseline` |
| 2 / `02-backend` | 예산 소유권, 카드 소유권을 확인하는 CSV 경계, 테스트 controller 삭제, AI base URL 설정, 일반 404, BE87 테스트와 runner | 1에 의존. demo auth/seed 없음. 안전한 결과 저장을 위해 공통 evidence projector를 이 단계로 앞당김 | `fix(backend): enforce ownership and external API boundaries` |
| 3 / `03-demo-backend` | OAuth profile 분리, JWT/Redis/HTTP 경계, 합성 User·결정적 seed, 카드 변경 제한, Chart 날짜 resolver, 관련 BE 테스트·runner·환경 예제 | 2에 의존. FE demo·Playwright 없음. 일반 OAuth/기존 RT 정책을 새 개인 기능으로 소개하지 않음 | `feat(demo): add isolated auth and deterministic Chart data` |
| 4 / `04-frontend` | Chart fallback 차단, 메모리 인증·복원·single-flight, API 기준월·peer 제한, lint 정리, FE176, FE용 package/lock와 runner | 3에 의존. Playwright package·script·config·E2E 코드 없음 | `feat(frontend): add safe demo Chart flow and resolve lint debt` |
| 5 / `05-e2e` | Playwright 의존성, 실제 browser 시나리오·launcher, 격리 runner, safe reporter, 공개 entrypoint/scanner/검증 단위 테스트 | 4에 의존. raw trace/HAR/video/storage state 없음. 공통 projector에 현재 E2E invocation 집계만 추가 | `test(e2e): verify demo flow with isolated Spring MySQL and Redis` |
| 6 / `06-portfolio` | 최소 공개 문서·기여 경계·최종 manifest·새 정제 결과·선별 screenshot | 5에 의존. A1~14 raw 보고서/원문 evidence 제외. 이전 공개 후보의 중복 실행 요약도 새 결과로 대체 | `docs(portfolio): publish audit and reproducible verification evidence` |

Baseline의 root ignore는 공개 준비용 명시적 추가이며 팀 HEAD 코드라고 표시하지 않는다. 원본 env 예제는 baseline에서 제외하고 BE demo 예제는 3, FE 예제는 4에 도입했다. 원 팀 코드/시각 자산과 개인 후속 작업은 [기여 경계](contribution-boundary.md)에 구분했다. 재게시 권한을 새 라이선스 선언으로 대체하지 않았다.

### 같은 파일이 여러 단계에서 바뀌는 경우

- `BudgetRepository.java`: 1의 HEAD → 2의 소유권 조회 메서드 → 3의 seed 조회 메서드. 2에 seed 전용 메서드를 섞지 않았다.
- `application.yml`: 1의 HEAD → 2의 AI base URL 한 항목 → 3의 demo/OAuth profile 분리. 2에는 demo 기본 설정이 없다.
- `fe/package.json`, `fe/package-lock.json`: 1의 HEAD → 4의 FE 테스트 의존성 → 5의 Playwright. 4의 lock은 별도 `npm ci --ignore-scripts`로 설치해 검증했다. 최종 5/6의 package/lock은 검토된 후보와 동일하다.
- `public_evidence.py`: BE runner의 필수 import라서 2에 도입하고 5에서 E2E 종합 요약 기능만 추가했다. 기존 BE/FE projector 함수는 AST 비교로 동일함을 확인했다.
- `CsvTestController.java`는 1에 실제 HEAD 상태로 존재하고 2에서 삭제된다. `json.d.ts`는 1에 존재하고 4에서 삭제된다. 최종 manifest에서는 X이며 삭제 전환을 명시한다.

따라서 파일별 주된 범주와 최초 도입 단계만으로 변경 이력을 추측하면 안 된다. manifest의 commit 전환 열을 함께 사용한다. 어떤 단계에도 이후 제품 파일 전체를 끌어와 컴파일만 맞추지 않았다.

## 실제 검증 결과와 적용 범위

| Snapshot | 이번에 실제 실행한 검증 | 결과와 한계 |
|---|---|---|
| 1 | BE compileJava/compileTestJava, 원본 FE lock 독립 설치, OAuth typecheck/build, lint | compile/build PASS. 원본 lint 34 errors/3 warnings를 그대로 재현. 원본 OAuth/AI에 연결하는 앱 context는 기동하지 않음. demo 검증은 아직 해당 없음. [결과](evidence/commit-01-summary.json) |
| 2 | 전체 BE 테스트 소스 컴파일 + 실제 전용 MySQL/Redis의 소유권·404·CsvClient 87개 | 87 PASS, failure/error/skip 0, cleanup PASS. FE는 1과 바이트 동일하여 1의 build 근거를 연결하고 별도 재실행으로 표시하지 않음. [결과](evidence/commit-02-summary.json) |
| 3 | 전체 BE 테스트 소스 컴파일 + 실제 login/seed/조회/PATCH·결정성 집중20개 | 20 PASS, failure/error/skip 0, cleanup PASS. 여기서 BE275 전체를 실행했다고 주장하지 않음. 전체 회귀는 6에서 수행. [결과](evidence/commit-03-summary.json) |
| 4 | Playwright 없는 lock 독립 설치, FE176, 제품/테스트 타입, OAuth/demo build, invalid mode 거절, lint | 전체 PASS, lint 0/0. BE 제품은 3과 동일. [결과](evidence/commit-04-summary.json) |
| 5 | FE176, 제품/테스트/E2E 타입, 두 모드 build, lint, 공개 검증14개 | 전체 PASS. 공개 검증은 기존11+종합 요약 회귀3. 실제 browser와 전체 BE는 실행 코드가 동일한 6에서 검증. [결과](evidence/commit-05-summary.json) |
| 6 | BE275/compile, FE176, 제품/테스트/E2E 타입, OAuth/demo build, invalid mode, lint0/0, 공개 검증14개, 최종 Chromium 독립2회 | 모두 PASS. E2E 각4개, external attempts0, cleanup PASS. 최종 assertion/대상/skip 완화 없음. [결과](evidence/commit-06-summary.json) |

초기 baseline debt는 원본 상태의 관측값이며 최종 lint 통과와 혼동하지 않는다. 후속 기능이 아직 없는 단계의 해당 검증은 적용 대상이 아님을 표시했다. 필수 최종 검증에는 미실행/FAIL/BLOCKED/skip이 없다.

### 최종 browser 근거

[현재 invocation의 2회 종합 결과](evidence/demo-browser-e2e-summary.json)에 포함된 개별 요약만 이번 최종 결과다. 실제 demo bundle·Spring Boot·JWT filter/Guard·MySQL·Redis를 사용했다. 제품 API mock은 없다. 각 실행마다 새 자원을 사용하고 workers=1, retries=0이다.

- login → session → Chart → 실제 category PATCH → 후속 GET → 새로고침 reissue/session → logout 검증.
- 실제 소비 908,000원 유지, 누수 18,000원 → 0원, 연간 leaked true → false.
- 전용 User/Card/Transaction/Budget 수 1/1/240/72, 카드 금융정보 없음, AnalysisJob 없음.
- public-demo HTTPS 동일 origin, 별도 local-demo HTTP cookie와 CORS 검사. cookie 속성과 무효화만 공개한다.
- 외부 애플리케이션 요청0, 소유 컨테이너·프로세스·브라우저·포트 cleanup PASS. [최종 자원 확인](evidence/final-resource-check.json).
- screenshot은 최신 두 실행의 마스킹된 화면만 선별했다. 원문 토큰·HTTP dump·실행 자원 식별값을 포함하지 않는다.

### 해결한 검증 기반 문제

첫 BE 시도는 sandbox의 Docker socket 접근 거절로 테스트0에서 BLOCKED였다. 권한 검토 후 동일한 전용 자원 runner로 실행하여 BE275 PASS를 확인했다. 제품 오류로 분류하지 않는다.

첫 browser 재실행은 개별 두 실행이 PASS였지만 기존 종합 요약 파일이 이전 invocation을 계속 참조했다. 이를 publication FAIL로 기록하고 runner가 현재 호출에서 반환한 명시적 목록으로만 집계하도록 고쳤다. 시작 시 기존 종합 PASS를 초기화하고 finally에서 현재 결과를 기록한다. 미완료 실행, egress, cleanup 실패, skipped case를 성공으로 숨기지 않는 회귀3개를 추가했다. 수정 후 실제 독립2회를 다시 실행해 개별 파일과 종합 요약의 참조 일치까지 확인했다. [준비 이력](evidence/commit-preparation-history.json), [수정 범위](evidence/verification-repair-scope.json).

이 보완은 5단계 검증 코드에만 적용했다. BE/FE 제품, 기존275/176 assertion, E2E 사용자 시나리오는 변경하지 않았다. 자동 재시도로 실패를 숨기지 않았고 앞선 정제된 시도 근거는 공개 tree 밖에 보존했다.

## 공개 검사·제외 정책

- 실제 파일 내용 전체에서 key/token/literal credential 후보, 실행 identity/cookie 값, 로컬 경로, 바이너리/생성물·메타데이터를 검사했다. 각 snapshot의 결과는 [scan 요약](evidence/commit-scan-summary.json), 최종 상세는 [최종 scan](evidence/snapshot-scan.json)에 있다.
- 소스의 타입/필드명과 합성 테스트 fixture 표현은 실제 실행 데이터와 구분한다. 이미 검토한 경로·line 내용 digest를 정확히 대조하며, key/JWT 같은 강한 탐지 규칙을 예외로 허용하지 않는다. 원문 candidate는 보고서에 출력하지 않는다.
- 원본 Git/AI subtree/내부 포팅 자료/미확인 CSV/font/raw evidence 및 실제 환경파일은 양의 allowlist 밖이다. 후보 값 제거를 위해 원본을 수정하거나 원본 이력을 복제하지 않았다.
- root ignore는 17개 제외/7개 보존 사례 PASS. JSON/PNG/SQL 전체 제외 없음. [ignore 결과](evidence/ignore-contract.json).
- legacy OAuth 코드의 팀 주소는 1에서 보존되고 최종 일반 OAuth 경로에도 남는다. demo API 의존성으로 소개하지 않는다. CsvClient는 2부터 명시적 AI 설정을 요구하며 팀 서버 fallback이 없다.
- 제품 CSS의 CDN 폰트는 남아 있다. E2E는 기존 테스트용 시스템 fallback을 사용했으며 폰트를 새로 복제·변형·포함하지 않았다. 실제 공개 배포 전 폰트 권리/로컬 제공은 P1 asset debt다. [외부 참조 분류](evidence/external-reference-review.json).
- 이 검사는 알려진 패턴·명시적 provenance 검토에 근거한 공개 감사이며 모든 종류의 비밀값 부재에 대한 수학적 보증은 아니다. 실행 데이터가 포함되는 raw 출력 자체를 allowlist에서 제외한다.

## 재실행과 다음 단계

최종 snapshot의 실행 방법은 [독립 검증 안내](verification-summary.md)를 따른다. Git metadata 없이 실행하며 검증 cache·npm 설치·Chromium은 snapshot 밖의 명시적 디렉터리로 제공한다.

중간 단계는 각 디렉터리에서 실행한다. 2는 `csv_client_config.py --phase green`, 3은 `demo_chart_seed.py --phase focus`, 4는 `fe_lint_debt.py`, 5/6은 `public_snapshot.py`를 사용한다. 2/3은 명시적인 전용 Gradle cache가 필요하다. 4의 npm 설치는 해당 단계 package/lock을 사용해야 한다. 1은 외부 임시 Gradle build/project-cache와 envDir=false·외부 outDir Vite config로 compile/build만 검증하며 원본 실행 설정을 그대로 기동하지 않는다.

다음 Git 초기화 단계 전에 확인할 사항:

1. 과거 MinIO 자격증명 폐기·회전 여부의 소유자 확인.
2. 팀 코드/시각 자산의 포트폴리오 재게시 범위 확인 및 기여 경계 유지.
3. 최종 manifest와 실제 파일 tree 일치, 공개 scan PASS, Git/원문 실행 자료/의존성/인증서 부재 확인.
4. 중간 baseline은 원본 결함과 외부 OAuth 경로를 담은 이력용 상태임을 이해하고 실제 배포는 최종 tree로만 판단.
5. 별도 요청이 있을 때만 새 저장소 초기화와 6개 commit 구성을 진행. 현재 작업에서는 이 동작을 수행하지 않음.

현재 1·2가 확인되지 않았으므로 [공개 준비 상태](evidence/public-readiness.json)의 `READY_TO_INIT_PUBLIC_REPO=false`를 유지한다. 이 파일들은 실제 commit이 아니라 검토 가능한 file snapshot이다.
