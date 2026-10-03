## 최신 전체 화면 공개 여정 — PASS

2026-10-03. 실제 공개 제품 SHA는 `d020d837f2830125696d2038eb4a4c969864432e`, 검증기는 `public-harness-v2`다. 신규 방문자 1명·Chromium context 1개·실행 1회·자동 retry 0회로 마당·장독대·씀씀이·조언·곳간·정보 입력 전체 여정과 최종 outcome PASS를 확인했다.

예산 PATCH 2회·category PATCH 1회와 PATCH 응답 뒤 시작된 실제 GET 재집계, 총 소비 908,000 유지·누수 0·annual leaked=false, 390/768/1440의 6개 화면, 곳간 P01~P14, reload/reissue/session 복원·체험 종료를 검증했다. 폐기 AT 401·직접 backend 403, 외부/금지/읽기 예산 초과/변경 상한 초과 요청 0, 최종 오류 0·exit 0·소유 자원 정리 PASS다.

Cookie A/E는 실제 공개 응답 PASS이며 D는 기존 fixed-clock 근거를 유지한다. 이번 공개 실행의 결과를 전체 BE/FE 재실행으로 표기하지 않는다. 자연 cold-start는 **NOT_OBSERVED**, 공급자 장애전환 이후 폐기 보장은 기존 미확인 상태다. 과거 public Mypage 실패 원인은 이번 PASS와 별개로 **UNKNOWN**을 유지한다.

```text
FULL_DEMO_EXPERIENCE_PUBLIC_READY=true
PUBLIC_DEPLOYMENT_READY=true
HISTORICAL_PUBLIC_MYPAGE_FAILURE_CAUSE=UNKNOWN
```

[공개 전체 여정 검증 요약](public-full-experience-verified.md)에 검증기 manifest checksum과 결과 범위를 기록했다. 이번 마무리는 문서만 갱신하며 공개 여정 재실행·제품 변경·재배포는 없다. 문서 커밋 이후 GitHub HEAD와 배포 제품 SHA가 달라도 위 제품 배포는 유지한다. 자동 배포 OFF 설정은 변경하지 않는다. 아래 FAIL/UNKNOWN/로컬 결과와 readiness 기록은 당시 상태 그대로 보존한다.

---

## 최신 PATCH 이후 응답 선택 경합 교정 — 로컬 PASS

2026-10-02. main `9e52a4ca90838b420fddb750b4c311ea869c06bf`의 제품 bytes를 유지하고 검증기만 교정했다. 실제 공개 이전 실행에서 stale annual 응답을 선택한 경합은 확인됐으나 제품 재집계 결과는 미관측이었다. 요청 시작/응답 순서를 함께 기록하고, PATCH200 뒤에 시작된 실제 연간·월별·카테고리 응답만 선택한다. 이미 도착한 응답과 이후 응답 모두 지원하며 explicit fetch·sleep·mock·기대값 완화는 없다.

합성 RED13개 중3FAIL → GREEN14PASS. 최종 FE473(기존459+신규14), 제품/test/E2E/Functions 타입·OAuth/demo build·invalid mode·lint0/0 PASS. 실제 로컬 전체체험390/768/1440의3개, core독립2회각6개, mobile3개, cold 수동복구1개 PASS. 외부 요청0·소유자원정리PASS. 최초 새 테스트 문법의 타입 준비 실패는 보존했고 설정 완화 없이 테스트 선언만 교정했다.

이번 BE724 전체와 Cookie D fixed-clock11개는 제품/BE 테스트 불변으로 기존 근거를 유지한다. cookie A/E는 core에서 재확인했다. 자연 cold-start와 공급자 장애전환 보장은 새로 관측하지 않았다. README·제품·provider·scanner 규칙 변경0이다. strict public scanner PASS·미해결0이다.

아래 이전 STATUS 본문은 당시 로컬 결과를 보존한다. 현재 실제 production은 위 기준 SHA로 전체 UI가 배포된 상태이나 이전 public full E2E는 Chart 응답 선택에서 실패했다. 이번 로컬 성공을 공개 전체 여정 성공으로 대신하지 않는다. 승인된 다음 절차는 검증 commit/push → 같은 SHA 수동 배포(auto OFF) → 신규 방문자 최대1명의 독립 public full E2E다. 그 공개 실행 결과는 별도 정제 기록과 최종 응답으로 보고한다.

```text
POST_PATCH_RESPONSE_CORRELATION_LOCAL_READY=true
PUBLIC_DEPLOYMENT_READY=false
```

상세: [24-post-patch-response-correlation.md](24-post-patch-response-correlation.md).

---

## 최신 public demo 전체 사용자 경험 — 로컬 PASS

2026-10-02. clean main `f569d9cd0054ce5bd3661ac2fe957b6c3eba225a`에서 마당·장독대·Chart·조언·곳간·정보 입력 6개 페이지를 복원했다. 로그인 뒤 API 기준월 장독대로 이동하며, 실제 예산/거래 저장과 교차 재조회·방문별 메모리 프로필·경로별 자산 로딩을 연결했다. 예산 없는 항목은 기준 없음으로 표시하고 fake ID·소비 fallback·실시간 AI를 사용하지 않는다.

**BE724, FE459(기존373+신규86), Python66 PASS**. 제품/test/E2E/Functions 타입, OAuth/demo build, invalid mode 거절, lint0/0 PASS. 실제 로컬 Chromium 전체 체험390/768/1440의3개, 기존 core독립2회각6개, mobile3개, cold-start1개 PASS. 외부앱/금지업무요청0·소유자원정리PASS·최종필수failure/error/skip/todo0이다. strict scanner PASS, 새 규칙/허용범위확대0·미분류/stale0이다.

실제 예산40,000→60,000→40,000과 누수18,000→0→18,000, Chart분류변경 후 총908,000·누수0·annual leaked=false를 확인했다. 조언 갱신·곳간/정보입력·reload에서 실제 저장 유지와 메모리 프로필 초기화·자동login0·logout을 검증했다. 기존보호/API/쿠키A·D·E/제한 계약은 유지한다.

BE·seed·schema·인증 엔진·gateway·provider·README·package/lockfile·과거evidence는 보존했다. 이번 원격provider/공개URL요청·stage/commit/push/배포0이다. 실제production은 기존 제품 `1d3479e39defe5777fa122846bf7988ec7e12c94`의 이전 UI이며 이번 로컬 구현이 배포됐다는 의미가 아니다. 기존 공급자 장애전환 미확인 보장도 바꾸지 않았다.

```text
FULL_DEMO_EXPERIENCE_LOCAL_READY=true
PUBLIC_DEPLOYMENT_READY=false
```

상세: [23-full-demo-experience.md](23-full-demo-experience.md), [새 evidence](evidence/FULL_DEMO_EXPERIENCE/). 다음 별도 단계는 변경 검토와 승인된 commit/push/공개 배포 절차다. 이번에는 실행하지 않았다.

---

## 최신 cookie E2E 시각 기준 분리 — 로컬 PASS

2026-10-02. clean HEAD `be513cd0938d4cc37cd77ea04035b97ae41c83f2`에서 제품 변경 없이 검증 책임을 분리했다. browser cookie absolute expiry와 server session deadline의 직접 상한 비교는 `E-D ≈ L+K-Q`의 구조적 민감성 때문에 교체했다. **HISTORICAL_FAILURE_CAUSE=UNKNOWN**이며 과거 공개 실패 원인을 확정한 결과가 아니다.

A: 실제 Set-Cookie의 정수 Max-Age·보안 속성, D: 실제 DemoRefreshCookie fixed Clock, E: reissue 전후 API absolute deadline 동일성으로 검증한다. 브라우저에 저장된 유효성은 browser Date.now만 사용한다. 기존 RT 전송·HttpOnly·삭제·Redis/JWT 폐기·소유권·gateway/rate 제한은 그대로다. 임의 tolerance/optional storage interval은 추가하지 않았다.

**BE724(기존721+신규3), FE373(기존328+신규45), Python64 PASS**. 제품/test/E2E/Functions 타입·BE compile·OAuth/demo build·invalid mode 거절·lint0/0 PASS. Chromium core독립2회각6, mobile3, cold-start1 PASS. 외부앱요청0·소유자원정리PASS·최종필수failure/error/skip0이다. strict scanner PASS, 규칙/허용범위확대0·미분류0. 기존6개 source 분류는 동일 표현/횟수/construct의 파일digest·행 위치만 갱신했다.

제품253파일 raw bytes/mode 불변을 digest로 기록하고 전체 변경을 test/verification/docs로 제한했다. 초기 test lint 준비 오류와 교정·최종PASS를 분리해 보존했다. 과거evidence·HEAD/index 유지, 공개URL/provider요청·stage/commit/push/redeploy0이다. public full E2E 완료를 주장하지 않으며 기존 공급자 장애전환 보장도 승격하지 않는다.

```text
COOKIE_E2E_TIME_BASIS_READY=true
PUBLIC_DEPLOYMENT_READY=false
```

상세: [22-cookie-e2e-time-basis.md](22-cookie-e2e-time-basis.md), [새 evidence](evidence/COOKIE_E2E_TIME_BASIS/). 다음 별도 단계는 commit/push와 필요 시 동일 제품 provenance 재배포 후 actual public full E2E다. 이번에는 수행하지 않았다.

---

## 최신 공개 scanner 전체 감사·정제 — PASS

2026-10-02. 전체 공개 후보749파일·HEAD/index를 고정하고 기존FAIL79를 재현했다. 79개 전부 검토 결과 source 표현44·합성fixture8·오래된 위치분류27, 실제 secret/PII·UNKNOWN0이다. 테스트용 고정 값을 runtime canary로 정제하고, 정확한 파일/전체 내용/site/최소 construct/출현 횟수에 묶인 strict 분류148행(149곳)을 적용했다. 파일 변경·stale·중복·실제값 혼입은 실패한다.

HARD/SITES/BAD와 기존 evidence 금지 필드/값 검사 규칙을 보존했다. SQL·손상된 텍스트 evidence 검사는 강화했으며, wildcard/전체 파일·디렉터리 허용0이다. 최종 scanner **PASS**, HARD/PII/금지evidence/미분류/stale/예상밖artifact0. 기존7commit·399blob에서 실제 비밀값0, 원본 팀 history 교집합0이다. 기존PNG34개 및 신규3개를 metadata와 시각으로 확인했다.

**BE721·FE328·Python138 PASS**, 제품/test/E2E/Functions 타입·OAuth/demo build·invalid mode 거절·lint0/0 PASS. 실제Chromium core독립2회각6·mobile3·cold-start1 PASS, 외부앱요청0·소유자원정리PASS다. 제품 BE/FE/Functions/E2E bytes·package/lockfile·보안/timeout/provider 계약은 유지했다. 최초 fixture 준비 실패와 scanner 합성 음성 검토 실패는 최종PASS와 구분해 보존했다.

HEAD/index·기존 미커밋 자료·과거evidence를 보존했고 stage/commit/push/PR/merge/배포0이다. 실제 공급자·Render/Cloudflare·private state 접근/신규예약은 NOT_RUN/0. 기존 실제provider PASS는17단계까지의 근거이며, 장애전환 이후 폐기 미확인 보장은 이번에 승격하지 않는다.

```text
PUBLIC_SCANNER_AUDIT_READY=true
PUBLIC_DEPLOYMENT_READY=false
```

상세: [21-public-scanner-audit.md](21-public-scanner-audit.md), [새 evidence](evidence/PUBLIC_SCANNER_AUDIT/). 다음 작업은 별도 승인 범위의 commit/push이며 이번에는 실행하지 않았다.

---

## 최신 cold-start 수동 복구 — 로컬 PASS

2026-10-02. 기존240초·최대80회·10초 request timeout을 유지하고 자동 준비 확인이 끝나면 `startupSlow`로 전환한다. 자동 polling을 중단하고 “서버 다시 확인” 클릭만 readiness GET1을 실행한다. 성공 뒤에도 자동 login/reissue0이며 별도 체험 클릭이 필요하다. 정확한readiness429의 Retry-After/cooldown은 loginRetryAt과 분리했고, 만료는 버튼만 활성화한다. generation/revision·기존 restore/logout·오류 taxonomy를 유지했다.

**BE721·FE328(기존302+신규26) PASS**, 제품/test/E2E/Functions 타입·OAuth/demo build·invalid mode 거절·lint0/0 PASS. 기존 Chromium **독립2회 각6 PASS**, 모바일390/768/desktop3PASS, 신규 cold-start Chromium1PASS다. 실제 deadline 뒤 자동GET/POST0→수동GET1/POST0→명시적login1→실제seed/Chart/logout을 확인했다.390px overflow0·안내/버튼 가독성PASS, 외부앱요청0·모든 최종 필수failure/error/skip0·소유자원정리PASS다.

로컬512MiB/CPU0.1 표본은 HTTP bind249.005초·ready263.701초·최대관측415.8MiB·login15.839/16.094초·OOM=false였다. 과거357.782/215.738초와 함께 변동을 기록하며 실제 Render 적합성/콜드스타트 완료시간으로 일반화하지 않는다. **실제 Render/Cloudflare·공급자 연결 NOT_RUN**, private state 접근/신규 예약0이다.

FE 네 제품 파일만 변경했고 BE/gateway/rate/DB/Redis/capacity/cleanup/seed/Chart/timeout/heap은 보존했다. 과거자료·STATUS본문·HEAD/index 보존. scanner **기존FAIL79 유지·새후보0**, 규칙/allowlist 변경0·이번evidence 금지값0이다. 중간 기능RED, UI cooldown 리뷰RED, 초기Docker prerequisite 및 browser setup/소스보존 실패를 최종PASS와 구분해 보존했다.

```text
COLD_START_RECOVERY_READY=true
MOBILE_CHART_LAYOUT_READY=true
LOCAL_DEMO_ABUSE_GUARD_READY=true
REMOTE_DEMO_CAPACITY_VERIFIED=true
PUBLIC_DEPLOYMENT_READY=false
```

remote 상태는 기존17단계 근거이며 이번 재검증 결과가 아니다. 공급자 장애전환 미확인 보장과 공개 scanner 부채는 그대로다. 상세: [20-cold-start-recovery.md](20-cold-start-recovery.md), [새 evidence](evidence/COLD_START_RECOVERY/). stage/commit/push/PR/merge/배포0.

---

## 최신 모바일 Chart 상세 가독성 — 로컬 PASS

2026-10-01 실제 Chromium의390×844/768×1024/1440×1000에서 변경 전 압축·잘림을 재현하고900px 이하만 세로 stack으로 교정했다. 거래명·날짜·금액·category를 읽을 수 있는 거래 카드와44px Select, API 기준월 버튼을 제공한다. 모바일 파이는 기존 비율과 같은 데이터의 범례를 사용하고 desktop 두 열/라벨/계산은 보존했다.

최종 세 viewport 모두 body 가로 overflow0, 상호/금액/category/Select 가독성·조작 PASS다. 실제 PATCH 후 총소비908,000원 유지·누수18,000→0·연간 leaked=false, reload/reissue/session 복원·수정 유지·logout·자동 login0을 확인했다.390px 상호312px/Select44px,768px 상호690px/Select44px,desktop 상호197px와 기존 두 열을 관측했다. 모바일 파이 라벨 겹침0·6개 범례 가독성도 확인했다.

**BE721 PASS**(일반629+Render92), **FE302 PASS**(기존294+신규8), 제품/test/E2E/Functions 타입·OAuth/demo build·lint0/0 PASS. 기존 Chromium **독립2회 각6 PASS**, 별도 모바일3viewport PASS, 외부 앱 요청0, 최종 필수 failure/error/skip0·소유 자원 정리 PASS다. 초기 관측 도구 오류·소스 보존 실패·파이 촬영/관측 가정 오류와 중간 라벨 겹침은 숨기지 않고 최종 재검증과 구분해 기록했다.

백엔드·인증·gateway·rate limit·capacity·cleanup·DB schema·seed·provider 계약 변경0, 실제 공급자 연결/private state 접근/신규 예약0이다. 과거 evidence와 기존 미커밋 자료·HEAD/index를 보존했다. scanner는 **기존78 + 검토된 source 표현의 행 이동1 = FAIL79**이며 규칙/allowlist 변경0·실제 secret/PII 확인0이다. 공개 정제 부채를 해결한 것으로 표시하지 않는다.

```text
MOBILE_CHART_LAYOUT_READY=true
LOCAL_DEMO_ABUSE_GUARD_READY=true
REMOTE_DEMO_CAPACITY_VERIFIED=true
PUBLIC_DEPLOYMENT_READY=false
```

실제 모바일 기기/Safari·Cloudflare/Render·공개 URL 배포 검증은 NOT_RUN이다. 기존 공급자 장애전환 미확인 보장과 cold-start 정책은 이번 작업으로 바뀌지 않는다. 상세: [19-mobile-chart.md](19-mobile-chart.md), [새 evidence](evidence/MOBILE_CHART/). stage/commit/push/PR/merge/배포0.

---

## 최신 public-demo gateway / abuse guard — 로컬 PASS

2026-10-01 공개 demo의 서버 gateway 검증과 bounded in-memory login/readiness 제한을 추가했다. Pages Function은 브라우저 내부 헤더를 제거하고 서버 binding으로 설정하며, BE는 public-demo에서 gateway→CORS→JWT/Guard→MVC 순서로 검증한다. 유일한 gateway 예외는 기존 상수 GET /api/test다. OAuth/local-demo는 새 검사를 활성화하지 않는다.

login은 IP10/30분·전체5/분 및30/시간, 메모리 IP key 최대1024, readiness는 별도60/분이다. monotonic rolling window를 원자적으로 검사하며 잘못된 요청·429는 timestamp/수명을 늘리지 않는다. 429는 기존 FULL/BUSY503과 분리하고 Retry-After/no-store/Set-Cookie0을 유지한다. FE는 정확한 login429만 메모리 cooldown·수동 재시도로 처리하며 자동 login/POST 재전송0, readiness240초/80회 한도 불변이다.

**최종 BE721 PASS**(일반629 + 별도 Render92), FE294(기존252+신규42), 제품/test/E2E/Functions 타입·OAuth/demo build·lint0/0 PASS. Chromium 독립2회 각6 PASS, 외부 앱 요청0, 모든 최종 필수 suite failure/error/skip0, 소유 자원 정리 PASS다. 신규 BE76은721에 포함한다. 기존633 이후17단계의 로컬 안전 테스트12도 전체 discovery에 포함했다. Python 집계11 PASS. 변경 전 기능 RED와 새 테스트 준비 오류·browser 집계 오류 및 교정 결과를 보존했다.

로컬512MiB/0.1CPU 준비357.782초·최대 관측434.8MiB·login33.519/14.195초·OOM=false다. **준비시간은 기존 FE240초보다 길며**, 실제 Render 성능/콜드스타트 UX 충족을 주장하지 않는다. 이 정책과 timeout은 바꾸지 않았다.

capacity/cleanup/schema/provider/JWT·Redis/seed 제품 계약과 과거 문서/evidence·HEAD/index를 보존했다. 실제 Cloudflare/Render/TiDB/Upstash 접속·provider private state 접근·신규 예약0, 실제 secret 생성/등록0이다. scanner는 기존FAIL60에서 새 소스 후보를 포함해 **FAIL78**이며 실제 secret/PII 확인0·새 evidence 금지 필드/값0·규칙/allowlist 변경0이다. 자동 공개 정제 PASS로 표시하지 않는다.

```text
LOCAL_DEMO_ABUSE_GUARD_READY=true
REMOTE_DEMO_CAPACITY_VERIFIED=true
PUBLIC_DEPLOYMENT_READY=false
```

remote capacity=true는17단계까지의 기존 검증 상태이며 이번 재검증 결과가 아니다. 실제 Cloudflare edge/Render 직결·공개 URL E2E는 NOT_RUN이다. 단일 instance·재시작 시 초기화·NAT 공유 오탐·다중 instance 한계가 있으며 영속 상한이나 DDoS 완전 방어가 아니다. 공급자 장애전환 미확인 보장도 별도 유지한다.

상세: [18-demo-abuse-guard.md](18-demo-abuse-guard.md), [새 로컬 evidence](evidence/DEMO_ABUSE_GUARD/). 다음 작업은 별도 범위의 공개 scanner 후보 검토/정제이며 이번에 scanner 규칙을 변경하지 않았다. 모바일 Chart·cold-start UX·실제 배포는 남는다. stage/commit/push/PR/merge/배포0.

---

## 최신 실제 TiDB cleanup-only — VERIFY / DRY_RUN / APPLY PASS

2026-10-01 새 독립 task에 work1200 + cleanup300 = **1500**을 선예약하고, 실제 TiDB `8.0.11-TiDB-v8.5.3`에서 현재 packaged cleanup service의 세 모드만 **한 번** 실행했다. 기존 task·marker·예산·원장은 접근·변경·환급·재사용하지 않았다. 제품/DDL/권한/runtime/FE 변경0이다.

VERIFY/DRY_RUN은 RR·client readOnly=true·autoCommit=false, 서버 READ ONLY 전달 관측0·DML/DDL0·1235/42000 0·guard위반0·rollback1씩 PASS다. 전체 fixture 불변, DRY candidate1을 확인하고 실제 rollback 완료 뒤 연결 상태 초기화2회를 확인했다. APPLY는 기존 **READ_COMMITTED**·readOnly=false에서 NOWAIT/재선택/전체 validator·정확한 DELETE5·COUNT1→0·COMMIT1 PASS다. 서버-level read-only 보장은 주장하지 않는다.

admin fixture는 V1 315행·금융/source null·Job/Peer/Dummy0, category1건 수정·DB UTC24h 이상 적격화다. 제품 세 모드는 제한 cleanup 계정만 사용했다. baseline/V001/V002 schema10·최소 SHOW GRANTS·실제 driver false/TLS·패키지 CodeSource도 PASS다. singleton/무관 데이터는 불변이다.

원격 전 Python27/Java12 PASS. 같은 actual workflow의 normal912/35, early fail·interrupt570지점 각각max912/40, finally35지점max912/35로 예산을 증명했다. 실제 **JDBC2/2**, charged work912+cleanup35=947, 선예약1500/1500·재사용잔여0·미사용553 환급0이다. 공급자 과금량이 아니다. finally 소유data0·계정1/schema1 실제 부재·client/process 종료·전달/build 임시자원 제거 모두 PASS다.

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

capacity=true는 이전15단계 실제 admission/schema/권한 PASS와 이번cleanup을 합친 판정이다. admission경합/FULL, Upstash, HTTP login, FE/Chromium 재실행0이다. 기존 BE633/FE252/Chromium2회는16단계 근거이며 이번 새 전체회귀로 표시하지 않는다. 과거1235의 정확한 원인을 소급 확정하지 않는다.

기존616개 파일 중 STATUS prepend만 변경했고 이전본문·과거evidence·기존미커밋자료·HEAD/index를 보존했다. 새공개evidence 금지필드/값0. scanner는 기존51+새source후보9=**FAIL60**, 실제secret/PII확인0·규칙/allowlist변경0이다. abuse guard·mobile Chart·cold-start UX·scanner부채·commit/push·실제 Render/Cloudflare·공개URL E2E는 남는다. 다음 한 건은 공개 demo abuse guard의 최소 설계·검증이다.

상세: [17-tidb-cleanup-remote.md](17-tidb-cleanup-remote.md), [실제 원격 결과](evidence/TIDB_CLEANUP_REMOTE/probe-83c0f479b0034f71/summary.json). stage/commit/push/PR/merge/서비스생성/배포0.

---

## 최신 cleanup read-only 경계 — 로컬 PASS, 실제 TiDB 재검증 NOT_RUN

2026-10-01 cleanup CLI 전용 URL에 정확한 `readOnlyPropagatesToServer=false`를 필수화했다. VERIFY/DRY_RUN은 REPEATABLE_READ·client setReadOnly(true)·autoCommit false·rollback을 보존하고, 고정 SELECT/metadata guard가 쓰기·batch·DDL·원본 JDBC 객체 접근을 delegate 전에 거절한다. 서버 read-only 보장은 주장하지 않는다. 사용자 확인에 따라 APPLY의 기존 READ_COMMITTED·lock/validator/정확한 DELETE/COUNT/COMMIT을 유지했다. runtime DB_URL/권한/TLS/timeout/validator/FE는 불변이다.

실제 로컬 MySQL8.4/ConnectorJ9.4.0에서 기본 true의 서버 read-only 전달과 false의 client-only 상태를 확인했다. 실제 드라이버의 READ ONLY 경계에만1235/42000을 합성했을 때 true 제품 VERIFY/DRY는 실패하고 false는 조회·rollback·DML0으로 통과했다. 실제 APPLY도 정상 삭제했다. TiDB noop 설정·오류 무시·권한 확대는 사용하지 않았다. 과거 실제 TiDB1235의 정확한 실패 statement는 여전히 미관측이므로 원인 NARROWED를 소급해 확정하지 않는다.

**최종 BE633 PASS**(일반44클래스541+Render3클래스92), 집중175·Python25·FE252·제품/test/E2E/Functions 타입·OAuth/demo build·lint0/0·Chromium 독립2회 각4 PASS/외부 앱 요청0이다. 최종 필수 suite failure/error/skip0, 모든 소유 자원 정리 PASS다. 최초 집중4 FAIL은 새 테스트 Long/Integer 비교 교정, 최초 패키지 검증 FAIL은 maintenance fixture 옵션 추가로 각각 해결했으며 원래 근거는 보존했다.

로컬512MiB/0.1CPU 준비240.037초·최대 관측423.0MiB·login21.630/8.999초·OOM=false였다. **ready240.037>FE240초**이므로 cold-start UX 보장은 여전히 별도다. 실제 Render 성능 보장이 아니다.

원격 TiDB/Upstash 연결0, provider 입력/private task state 접근·변경0, 신규 원격 예약0이다. 기존2500/2500 task·marker·실패/정리 결과·원장은 보존했다. actual TiDB cleanup VERIFY/DRY/APPLY는 **NOT_RUN**이다. 과거 보고서/evidence183개·이전 STATUS 본문·HEAD/index 보존. scanner는 기존49+새 합성/변수 source2=**FAIL51**이며 실제 secret/금지 evidence 탐지0, rule/allowlist 변경0이다.

```text
LOCAL_DEMO_CAPACITY_READY=true
LOCAL_DEMO_CLEANUP_READY=true
REMOTE_DEMO_CAPACITY_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```

다음 한 건은 별도 승인한 새 **cleanup-only TiDB probe**다. 최소 service 검증안은 새 schema1/제한 cleanup 계정1, admin 준비·정리1+cleanup1=최대2연결, fixture315행, VERIFY→DRY→APPLY다. 잠정 work1200+cleanup300=1500 내부 command-equivalents는 후속 실제 경로의 유한 예산 합성 증명 후 확정해야 하며 아직 예약/실행하지 않았다. admission 경합/FULL·로그인/Redis/전체 provider 재실행은 제외한다. CLI 각 mode를 별도 process로 검증하면4연결이므로2연결 service안과 구분한다.

상세: [16-tidb-cleanup-readonly.md](16-tidb-cleanup-readonly.md), [새 검증 근거](evidence/TIDB_CLEANUP_READONLY/). stage/commit/push/원격 접속/배포0. abuse guard·mobile Chart·cold-start·scanner 부채·실제 배포/공개 URL E2E는 남는다.

---

## 최신 TiDB admission-lock 재실행 — admission PASS, 제품 cleanup FAIL

2026-10-01 보완된 음성 권한 classifier를 사용해 **새 독립 task/예산2500(검증2000+정리500)/JDBC4**로 한 번 실행했다. 기존 모든 provider/capacity task·marker·budget·ledger는 접근·변경·환급·재사용하지 않았다. 제품/DDL/GRANT/기존 검증 본체 수정0이며 새 진입점은 evidence 출력 경로만 분리한다.

실제 TiDB `8.0.11-TiDB-v8.5.3`: identity TLS4연결·10-table schema·제한 권한 전체 matrix·READ_COMMITTED/pessimistic·NOWAIT3572/HY000→BUSY·commit/rollback 잠금 해제·제품 admission rollback·V1 315행·COUNT1/max1 FULL은 **PASS**다. 다음 admission의 추가 data0, category1건 수정과 소유 marker 적격화 준비도 실행했다. 제품 기본 max1000/24h 조건은 불변이다.

그러나 제품 **CLEANUP_VERIFY_DRY_RUN에서 CLEANUP_SQL_FAILURE1235/42000으로 FAIL**했다. VERIFY/DRY_RUN 각각의 완료나 mutable validator 성공은 확인되지 않았고 APPLY는 BLOCKED다. 원인은 **NARROWED**: 제품 setReadOnly(true)와 실제 Connector/J9.4.0의 READ ONLY 전달, TiDB 문서의 관련 미지원 계약을 확인했지만 정확한 실패 statement/대상 noop 값은 미관측이다. read-only 제거·noop ON·권한 확대·예외 무시는 적용하지 않았다. 같은 task 재실행0.

원격 전 **Python20·출력분리 합성2·Java20 PASS**이고 실행 경계에서도 Java20/유한 예산 proof를 다시 통과했다. 전체 BE569/FE252/Chromium은 과거 로컬 결과이며 이번 재실행0, Upstash/Redis/HTTP login/JWT/FE/E2E/AI0이다.

연결4/4, 선예약2500/2500, 재사용 잔여0. 내부 command-equivalents는 검증836+정리45=881이며 미사용1619를 환급하지 않는다. 과금량이 아니다. finally에서 소유 data 잔존0·계정2/schema1의 실제 부재, client/process 종료·private 전달 파일 제거·소유 임시 build 정리 **PASS**다. 제품 cleanup 계약 실패와 finally 자원 정리 성공을 구분한다.

기존594개 파일 중 변경은 STATUS 최신 추가뿐이며 이전 본문·과거 보고서/evidence·제품/테스트·HEAD/index를 보존했다. 공개 scanner는 기존 **FAIL49** 유지, 새 finding0, 실제 private 값/금지 evidence 탐지0이며 규칙/allowlist를 바꾸지 않았다.

```text
LOCAL_DEMO_CAPACITY_READY=true
LOCAL_DEMO_CLEANUP_READY=true
REMOTE_DEMO_SCHEMA_VERIFIED=true
REMOTE_DEMO_LOCK_PRIVILEGES_VERIFIED=true
REMOTE_DEMO_ADMISSION_VERIFIED=true
REMOTE_DEMO_CLEANUP_VERIFIED=false
REMOTE_DEMO_CAPACITY_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```

다음 한 건은 **TiDB cleanup 읽기 전용 transaction 호환성의 최소 진단·설계**다. 실패한 정확한 JDBC 호출 확인과 DML0/조회 일관성/제한 권한을 유지하는 대안을 결정해야 한다. 새 원격 연결은 별도 승인/예산이 필요하며 접속정보 재입력·비밀번호 재설정은 필요 없다. abuse guard·mobile Chart·cold-start242.337>240초·scanner49·commit/push·실제 Render/Cloudflare 배포·공개 URL E2E도 남는다. stage/commit/push/PR/merge/배포0.

상세: [15-demo-admission-lock-tidb-retry.md](15-demo-admission-lock-tidb-retry.md), [실제 재실행](evidence/DEMO_ADMISSION_LOCK_TIDB_RETRY/probe-856e527496d04451/summary.json), [로컬 검사·정리](evidence/DEMO_ADMISSION_LOCK_TIDB_RETRY/local-checks-01/summary.json).

## 최신 실제 TiDB admission lock — 권한 검증기에서 FAIL, 소유 자원 정리 PASS

2026-10-01 새 독립 task에 검증2000/정리500/총2500을 선예약하고 실제 TiDB 최소 probe를 한 번 실행했다. 과거 provider/capacity state·marker·budget·ledger는 접근·수정·환급·재사용하지 않았다.

실제 TiDB `8.0.11-TiDB-v8.5.3`의 identity TLS4연결, baseline7→V001→V002 10-table 제품 schema 검사, count0/max1, cleanup 역할의 table10/FK4 가시성과 소유 schema 참조 예상 밖 외부FK0은 PASS다. 그러나 금지 작업 검사 중 **8121/HY000을 기존 검증기가 권한 거절로 분류하지 못해 FAIL**했다. 역할/개별 SQL 태그는 당시 미기록이므로 소급 단정하지 않는다. READ_COMMITTED/pessimistic·NOWAIT·제품 admission·dataset·제품 cleanup은 미도달/BLOCKED다. 기록된 fixture User/seed/marker0이다.

검증 도구에만 명시적인 음성 권한 검사에서 TiDB8121/HY000을 인정하도록 최소 교정했다. 허용 작업의 실패·금지 작업 성공·미지 코드/잘못된 SQLState는 여전히 FAIL, 제품 BUSY3572/HY000은 불변이다. 이후 진단은 고정 역할/연산 태그만 허용한다. 최종 로컬 **Java20/Python20 PASS**, failure/error/skip0이며 원격 보완 재실행0이다. 원래 FAIL evidence는 보존했다.

**실제 연결4/4, 선예약2500/2500, 재사용 잔여0.** 내부 command-equivalent 관측319=검증279+정리40이며 미사용2181은 환급하지 않는다. 새 schema1/계정2 실제 부재, data잔존0, client/process 종료·전달 파일 제거·소유 임시 build 정리 PASS다. 제품 cleanup APPLY 통과와 자원 finally 정리 PASS를 구분한다. Upstash/Redis/login/FE/E2E/AI0, 기존 BE569/FE252/Chromium2회는 이번에 반복하지 않았다.

제품·DDL·기존 테스트·과거 보고서/evidence·이전 STATUS 본문·HEAD/index 보존. 공개 scanner는 기존38+새 합성/변수 source11=FAIL49이며 실제 secret/금지 evidence 탐지0, 규칙/allowlist 변경0이다.

```text
LOCAL_DEMO_CAPACITY_READY=true
LOCAL_DEMO_CLEANUP_READY=true
REMOTE_DEMO_SCHEMA_VERIFIED=true
REMOTE_DEMO_LOCK_PRIVILEGES_VERIFIED=false
REMOTE_DEMO_ADMISSION_VERIFIED=false
REMOTE_DEMO_CLEANUP_VERIFIED=false
REMOTE_DEMO_CAPACITY_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```

다음 한 건은 **보완된 음성 권한 검사를 사용한 새 독립 TiDB 최소 probe**다. 별도 승인한 새 task/예산·연결 상한이 필요하며 이번 원장을 초기화하지 않는다. 접속정보 재입력·비밀번호 재설정·권한 확대는 필요하지 않다. abuse guard·mobile Chart·cold-start242.337>240초·scanner debt49·commit/push·실제 배포/공개 URL E2E도 남는다. stage/commit/push/PR/merge/배포0.

상세: [14-demo-admission-lock-tidb.md](14-demo-admission-lock-tidb.md), [실제 FAIL](evidence/DEMO_ADMISSION_LOCK_TIDB/probe-0310925ef2b14aa2/summary.json), [로컬 교정](evidence/DEMO_ADMISSION_LOCK_TIDB/post-remote-local-correction-01/summary.json).

# 배포 준비 현재 상태

기준일: 2026-10-01. 공개 저장소 `main`, HEAD `e8f14575b2387e130ec7ace5b4caba36144cad2c`의 미커밋 Render runtime·공급자 준비 변경까지 포함한다. 과거 보고서와 evidence를 보존한다.

## 최신 전용 admission lock — 로컬 계약 PASS

2026-10-01 승인된 lock-only table 분리를 구현했다. 기존 V001은 보존하고 V002에 `demo_admission_lock(id)` singleton만 추가했다. capacity는 SELECT-only, lock table만 SELECT/UPDATE이며 UPDATE 권한은 FOR UPDATE authorization 용도다. 제품의 실제 lock/capacity UPDATE0, resident_count0, 점유량은 COUNT(demo_visit)만 사용한다.

동일 lock으로 login/cleanup을 직렬화하고, 현재 Spring transaction/Connection에 결합한 claim 없이는 marker INSERT/최종 검증을 할 수 없게 했다. startup·runtime·cleanup에서 lock 변조/누락/추가를 fail-closed로 거절한다. bounded UPDATE가 침해 시 lock id 변조/장시간 잠금에 의한 DoS를 허용할 수 있다는 한계는 유지한다. 자동 repair·JVM/advisory lock·LOCK TABLES 권한은 없다.

실제 MySQL8.4 제한 runtime/cleanup 두 역할의 SELECT·NOWAIT 성공과 capacity UPDATE/lock INSERT·DELETE/DDL·금지 업무 write 거절을 확인했다. runtime 허용 업무 INSERT/UPDATE와 cleanup 실제 APPLY도 PASS다. 정확한3572/HY000 경합 및 commit/rollback 자동 해제, 기존 FULL·rollback/ACK·unsafe/legacy 보존을 유지했다.

**최종 BE569 PASS**(일반41클래스478 + Render3클래스91), 집중132·Python48·FE252·제품/test/E2E/Functions 타입·OAuth/demo build·lint0/0 PASS다. Chromium 독립2회 각4 PASS/외부 앱 요청0. 필수 suite failure/error/skip0, 모든 소유 자원 정리 PASS다.

별도512MiB/0.1CPU 시험도 이번 한 번은 기동/login2/session/logout/정리 PASS, OOM=false/정상 정리exit143이다. 준비242.337초·최대 관측422.8MiB·login17.145/9.697초를 기록했다. **ready242.337초>FE한도240초**이므로 cold-start UX 보장은 별도 미완료다. 과거 조기 종료 원인은 여전히 UNKNOWN이며 이번 성공으로 소급 규명하지 않는다. 제품timeout/heap/CPU 변경0, Render 실제 성능 보장 아님.

원격 TiDB/Upstash 연결·input/state 접근·신규 예약0. 과거 실패 task/marker/2000예약은 보존했다. 후속 새 최소 TiDB probe 범위와 잠정상한2500(검증2000+정리500)은 별도 승인 전 계획이며 실제 예약하지 않았다.

과거 보고서/evidence163개·이전 STATUS 본문·HEAD/index·V001/7-table DDL/validator 보존 PASS. 자동 공개 scanner는 기존35+새 고정migration/합성source3=FAIL38로 유지한다. 실제 secret/금지 evidence 탐지0, 규칙/allowlist 변경0이다.

```text
LOCAL_DEMO_CAPACITY_READY=true
LOCAL_DEMO_CLEANUP_READY=true
REMOTE_DEMO_CAPACITY_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```

다음 한 건은 별도 승인/새 task의 **TiDB lock-table 제한 권한·counterless admission/cleanup 최소 probe**다. stage/commit/push/서비스 생성/배포는 하지 않았다.

상세: [13-demo-admission-lock.md](13-demo-admission-lock.md), [새 검증 근거](evidence/DEMO_ADMISSION_LOCK/).

## 최신 counterless demo capacity — 구현 저장, 로컬 준비 미완료

2026-10-01 `resident_count`를 제거하고 singleton NOWAIT 잠금 + 실제 `COUNT(demo_visit)`를 점유량 기준으로 바꿨다. login은 초기COUNT+1, cleanup은 초기COUNT−삭제 방문 수를 commit 직전에 검사한다. 7-table baseline·visit 구조·validator·SQL/Redis 보상·기존 인증 정책은 보존했다. 원격 연결0, 실제 provider 입력/기존 private state 접근·변경0, 새 예약0이다.

최종 BE547 중 **545 PASS/2 FAIL**, error/skip0이다(일반41클래스454/456 + Render3클래스91/91). runtime·cleanup의 SELECT-only 역할은 일반 SELECT와 금지 권한 검사를 통과했지만 NOWAIT 잠금에서 **1142/42000**으로 실패했다. MySQL8.4의 FOR UPDATE는 SELECT 외 추가 권한을 요구한다. 권한을 늘리거나 기대값을 완화하지 않았으며 제한 역할의 후속 APPLY는 BLOCKED다. 과거 TiDB 잠금 성공은 기존 열 UPDATE 권한을 가진 역할의 결과이므로 새 SELECT-only 계약의 증거가 아니다.

FE252·제품/test/E2E/Functions 타입·OAuth/demo build·lint0/0 PASS, Chromium 독립2회 각4 PASS·외부 앱 요청0, 관련 Python38 PASS다. 별도512MiB/0.1CPU 패키지 시험은 준비 완료 전 앱 종료로 FAIL(`limited-app-exited`); 종료 코드/OOM/내부 원인은 미관측이며 이후 제한 환경 기능 검증은 BLOCKED다. 모든 실행의 소유 자원 정리는 PASS다.

과거 보고서/evidence154개·이전 STATUS 본문·Git HEAD/index 보존 PASS. 자동 공개 scanner FAIL35는 별도 부채로 유지하며 규칙/allowlist 변경0, 실제 secret/금지 evidence 탐지0이다. 기존 실패 TiDB task의 marker/2000 예약/원장을 열거나 수정하지 않았다. 후속 최소 TiDB probe는 조건부 범위만 기록했으며 로컬 PASS 전 실행/예약하지 않는다.

```text
LOCAL_DEMO_CAPACITY_READY=false
LOCAL_DEMO_CLEANUP_READY=false
REMOTE_DEMO_CAPACITY_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```

다음 한 건: **SELECT-only와 DB별 배타 잠금 권한 계약 충돌 결정**. MySQL LOCK TABLES는 table 한정이 아닌 database 범위여서 자동 추가하지 않는다. 제품·테스트·검증 근거는 저장했으며 stage/commit/push/배포0이다.

상세: [12-counterless-demo-capacity.md](12-counterless-demo-capacity.md), [새 검증 근거](evidence/COUNTERLESS_DEMO_CAPACITY/).

## 최신 실제 TiDB admission / cleanup — FAIL, 소유 자원 정리 PASS

2026-10-01 새 독립 private task와 총2000(정리500 포함) 선예약으로 한정 실행했다. 기존 소진 provider state/marker/예산은 접근·수정하지 않았다. Upstash/Redis/demo HTTP login/FE/E2E0이다.

실제 identity TLS4연결, 새9-table 스키마·RESTRICT FK·DATETIME(6)·index·초기count0/max2, READ_COMMITTED/pessimistic 및 NOWAIT3572/HY000→제품BUSY는 PASS다. 그러나 의도적 rollback 준비의 첫 `DemoAdmissionStore.claimSlot()`이 **DEMO_ADMISSION_UNAVAILABLE / SQL8121 HY000**으로 실패했다. User INSERT 전 중단이며 기록된User0, seed·marker·unsafe fixture0이다. 후속 role matrix·제품 cleanup verify/dry-run/apply/unsafe batch는 BLOCKED다.

원인은 **NARROWED**: 권한 검사 실패는 확인했고 column UPDATE/resident_count 조건부 SQL 호환성이 유력하지만, 실제 서버 버전·정확한 실패 SQL 태그를 저장하지 않아 확정하지 않는다. 권한 확대·제품 SQL 변경·기대값 완화·재실행은 하지 않았다. 다음 한 건은 별도 승인/한도의 열 단위 UPDATE 권한 확인이다.

이번 command-equivalents 관측317(검증280+정리37), 연결4/8. 선예약2000/2000·재사용 잔여0이며 미사용 예약을 환급하지 않는다. 생성 schema1/제한 계정2의 실제 제거·부재, 모든 client/process/group 종료, 전달 secret 파일 제거 PASS. 강제 종료0, 입력 불변. 내부 예약은 과금량이 아니다.

관련 Python15·Java11·컴파일 PASS. 기존 BE530/FE252/Chromium2는 과거 로컬 결과이며 이번 재실행0이다. commit ambiguity는 REMOTE_NOT_EXERCISED다. 과거 문서/evidence149개·기존 STATUS 본문·HEAD/index 보존 PASS. 공개 scanner는 기존27+새 합성/변수 source8=FAIL35를 유지하며 실제 secret/금지 evidence 탐지0, 규칙 변경0이다.

```text
REMOTE_DEMO_SCHEMA_VERIFIED=true
REMOTE_DEMO_ADMISSION_VERIFIED=false
REMOTE_DEMO_CLEANUP_VERIFIED=false
REMOTE_DEMO_PRIVILEGES_VERIFIED=false
REMOTE_DEMO_CAPACITY_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```

상세: [11-demo-capacity-tidb.md](11-demo-capacity-tidb.md), [실제 단일 실행](evidence/DEMO_CAPACITY_TIDB/probe-4cfe0cfafc494941/summary.json). abuse guard·mobile Chart·scanner·cold readiness354>240초·실제 배포/공개 URL E2E도 남아 있다.

## 최신 LOCAL demo 수용 상한·관리자 정리

2026-10-01 승인된 로컬 구현을 저장했다. demo 전용 JDBC 2-table migration, 같은 로그인 트랜잭션의 NOWAIT capacity/방문 marker, 순수 V1 validator, 독립 관리자 cleanup CLI, FE FULL/BUSY 안내를 추가했다. 일반 OAuth 정책·기존 미커밋 배포 변경·과거 보고서/evidence를 보존했다.

최종 BE 전체530 PASS(일반40클래스439 + 별도 Render3클래스91), FE252·모든 타입·OAuth/demo build·lint0/0 PASS, Chromium 독립2회 각각4 PASS·외부앱 요청0·정리 PASS다. 초기 fixture/메타데이터 문제와 수정 이력은 새 보고서에 분리했다. 제한 컨테이너의 raw POST 로그인은503/JDBC 통신 오류·SQL 증가0이었다. 같은 제품/자원에서 현재 FE의 readiness 관문을 거친 대조는 로그인2회/세션/logout/정리 PASS, POST 재시도0이다. 다만 cold 준비354초는 FE 한도240초를 넘었으므로 제한 환경의 cold-start UX PASS로 표시하지 않는다. 내부 원인은 NARROWED이며 공개 전 별도 기동/복구 평가가 필요하다.

새 admission/cleanup의 실제 TiDB는 NOT_RUN, Upstash 재검증0, 원격 schema/key/login/예약0이다. 기존 provider 원장15,568/15,568·marker·접속정보에는 접근/수정하지 않았다. 과거 provider 기능 PASS를 신규 JDBC 계약 PASS로 바꾸지 않는다.

공개 검사 규칙은 유지했다. 실제 비밀값/금지 evidence 탐지는0이지만 기존 자동 scanner의 source marker·고정 migration INSERT 경고는 별도 수동검토로 구분하며 자동 PASS로 쓰지 않는다. 허용 목록 수정은 자동 승인 심사 거절로 적용하지 않았다.

상세: [10-demo-capacity-cleanup.md](10-demo-capacity-cleanup.md), [새 근거](evidence/DEMO_CAPACITY_CLEANUP/).

```text
LOCAL_DEMO_CAPACITY_READY=true
LOCAL_DEMO_CLEANUP_READY=true
REMOTE_DEMO_CAPACITY_VERIFIED=false
PUBLIC_DEPLOYMENT_READY=false
```

다음 한 건은 별도 승인/독립 예산의 TiDB admission/cleanup 전용 probe다. 공개 배포 전 cold-start 대기 한도와 자동 scanner 경고 정리도 남는다. 기존 소진 원장은 재사용하지 않는다. 정리는 자동 실행되지 않으며 전용 schema/분리 계정/관리자 동시 DDL 금지가 전제다.

## 이전 실제 공급자 기능 복구 — 전체 경로 1회 PASS

2026-10-01 사용자 승인으로 총 내부 예약 상한을15,568로 적용해 기존 원장을 보존하고 한 번 실행했다. 교정된 입력 revision/접속정보는 불변이며 Chrome·native 연결 진단을 반복하지 않았다.

**실제 TiDB + Upstash의 login → seed → API → 재분류/집계 → Lua/TTL/회전/폐기/재연결 PASS**다. 새 login4회 모두201, 총소비908,000원 유지·누수18,000원→0·annual leaked=false·다른 방문자 전체 컬럼 불변을 확인했다. 실제 ConfigData validate·JDBC identity TLS·제한 runtime 계정·시간대/제약조건/rollback도 PASS다. 같은 RT 동시 refresh는1건200/1건401, 최종 세션 부재와 winner AT401로 기존 계약을 유지했다.

실제 NOSCRIPT3회 뒤 제품 fallback 성공을 관측했다. 재연결은 별도 connection/reset에 한정되며 provider failover 보장은 NOT_ESTABLISHED다. 결과 유실/known unavailable503 검사는 실제 Redis 연산에 연결한 로컬 주입이며 공급자 장애를 유발한 것이 아니다. HTTP는 실제 Spring 처리 계층의 MockMvc이고 이번 Store revoke PASS를 별도 HTTP logout 실행으로 표현하지 않는다.

새 예약6,096(정리2,000 포함)으로 **15,568/15,568, 잔여0**, 신규 login4/누적5/8이다. schema1·제한 계정1·세션 키5(방문자4+TTL시험1)의 실제 제거/부재 확인과 client/resources/child 종료 PASS. 강제 종료0·credential 전달 파일 잔존0, 과거5개+신규1개 원장 모두 정리 완료다. 예약은 청구 사용량이 아니다.

검증기만 승인된 1회 경계·자동 replay 차단·제품 Lua/정확한 소유 키 검사·종료 확인을 보강했다. 관련 Python25·Java41 및 컴파일 PASS, failure/error/skip0. 제품 변경0으로 전체 BE/FE/E2E는 재실행하지 않았다. 기존28 Java/7 Python 및 이전 입력revision8 검사는 유지했다.

실제 Mac→공급자 단일 관측은 context3.758초, 첫login18.590초, 후속17.022초, 연간조회1.361초다. Render/cold start 성능 보장이 아니다. 검증기의 무재전송 설정은 제품 기본 자동재연결 정책 보장과 구분한다.

상세: [09-managed-provider-recovery.md](09-managed-provider-recovery.md), [실제 결과](evidence/AUTONOMOUS_CONTINUATION/provider-e26ae7243edf4034/summary.json), [로컬 검사](evidence/MANAGED_PROVIDER_RECOVERY/20261001-recovery/local-checks.json), [보존·공개 감사](evidence/MANAGED_PROVIDER_RECOVERY/20261001-recovery/final-audit.json).

## 직전 포트 교정 — native TLS·인증·PING 복구 PASS

2026-10-01 사용자 승인으로 지정 파일의 **REDIS_PORT 하나만** 현재 동일 MoneyToad Upstash 콘솔 TCP 값으로 원자 교정했다. 다른8개 값·형식·owner·700/600과 기존 credential/TLS·제품 timeout은 보존했다. 과거 checksum 기록을 보존하면서 새 private 입력revision에 현재 실행을 연결했다. preflight input PASS, 실제 제품 ConfigData→URI의 비노출 설정 비교6개 true다.

**포트 교정 후 native 연결이 복구됐다.** 기존 JDK/Lettuce와 최소 nativeControl1회에서 TCP·FULL TLS 인증서/hostname·HELLO 인증·활성화·명시 PING1회 PASS. 신규 대상 연결1회, 두 번째 실행0. 제품 connect3초/activation·command2초 유지, 자동 재접속0. 공유 JDK DNS/첫IPv4라는 진단 resolver 차이는 유지하므로 Spring 앱 전체 실행이나 원래 Netty DNS와 완전히 같은 경로라고 주장하지 않는다. 과거 네트워크 상태 전체의 원인 규명도 아니다.

HELLO1/CLIENT2/PING1 완료 관측, 예상 밖 명령0. 64를 선예약해 **9,472/10,000**, 잔여 **528**, login 시도1/8 불변. 이는 내부 예약이며 공급자 청구량이 아니다. login/User/seed/TiDB/schema/Redis data key/Lua0. 채널/client/resources/DNS worker/child 종료 PASS, 강제 종료0, 비밀 전달 파일 잔존0, 원장5개 모두 cleanup 확인.

관련 Java 신규2+기존loopback1=3 PASS·compile PASS, Python8 PASS, private 치환4사례 PASS. Python 최초 호출의 import 경로 오류는 실행 위치만 교정했다. 제품 변경0으로 전체 BE/FE/E2E는 재실행하지 않았다.

상세: [08-redis-port-correction.md](08-redis-port-correction.md), [실제 단일 연결 결과](evidence/AUTONOMOUS_CONTINUATION/redis-port-correction-af4d1b6244c34ba4/summary.json).

## 직전 네트워크 경로 확인 — 07 당시 포트 불일치 / 원격0

2026-10-01 승인된 Chrome의 MoneyToad Upstash Details/Connect TCP 화면을 읽고 새로고침 후 재확인했다. **hostname·TLS는 지정 입력 파일과 일치하지만 port는 문자열·정수 비교 모두 불일치**한다. 화면 Port와 TCP 예시의 port는 서로 같다. 입력 파일 checksum은 직전06 실행 전과 동일하다. 따라서 불일치 사실은 **CONFIRMED**, 과거 timeout 원인은 **NARROWED**이며 바른 포트의 접속 성공·OS/중간망 차단 여부는 아직 UNKNOWN이다.

직전06도 `require_escalated` 승인 실행이었다. Chrome 접근과 native TCP 권한은 별개이며 다른 실행 권한 환경이 확보됐다고 주장하지 않는다. A3초는 같은 조건 반복을 피하고, B10초는 **BLOCKED_TARGET_MISMATCH**로 실행하지 않았다. 현재 서비스 operational status/접근 정책은 화면에서 확인하지 못했다. Free Tier·Singapore·TCP·TLS Enabled와 마스킹 상태만 확인했다.

신규 TCP/Redis 명령/login/schema/User/key0. 기존 예약 **9,408/10,000**, 잔여 **592**, login 시도1/8·원장4개·marker·접속정보를 보존했다. 새 socket/client/child0으로 원격 정리 불필요, 소유 프로세스 잔존0. 제품·도구·테스트 변경0, 전체 BE/FE/E2E 재실행0.

다음 한 건은 **지정 파일의 REDIS_PORT만 현재 승인 자원의 콘솔 TCP 포트에 맞추는 별도 승인**이다. 비밀번호·전체 파일 재작성·재로그인은 필요 없다. 이번에는 접속정보 변경 금지와 대상 확인 조건을 지켜 자동 교정·새 연결을 하지 않았다.

상세: [07-network-path-check.md](07-network-path-check.md).

## 직전 Redis transport 대조 — JDK TCP timeout / NARROWED

2026-10-01 합동 대조에서 **기본 JDK Socket도 제품과 같은 connect3초에서 SocketTimeoutException으로 실패**했다. DNS는77ms에 완료됐고 IPv4 선택·원래 hostname 보존을 확인했다. TCP control3,008ms, 전체3,399ms였다. TLS는 시작되지 않았고, A TCP/TLS 성공 조건 미충족으로 최소 Lettuce(B)·관측기 비교(C)는 **NOT_RUN**이다. 기존 Netty/관측기를 거치지 않은 실패를 확인했지만 Mac egress·경로·공급자 접근 정책/서비스·연결 지연의 구체적 근본 원인은 UNKNOWN이다.

설치 JAR에서 activation2초가 TCP 완료 전 channelRegistered부터 시작하고, timer close가 pending TCP promise를 ClosedChannelException으로 만들 수 있는 경로를 확인했다. 이것은 과거05 원인의 확정이 아니다. 이번 A에서는 실제 TCP 실패가 cleanup보다 먼저였고 외부 deadline/강제 종료는 없었다. 제품 timeout/transport/TLS/인증·접속정보 변경0.

신규 대상 TCP 시도1, Redis 명령/PING0, login/schema/User/data key0. socket/child/전달 파일 정리 PASS, 누적 원장4개 모두 cleanup 확인. 예약 **9,408/10,000**, 잔여 **592**, 누적 login 시도1/8. OS 내부 DNS 물리 접속 수와 공급자 청구량은 미측정이다.

로컬 관련 Java35 PASS, 최종 중단·cleanup 보강 후 영향12 재실행 PASS, Python29 PASS, compile·실제 loopback 결과 정제 PASS. 신규 테스트 컴파일 준비 오류1건은 기대값 보존 교정했다. 제품 변경이 없어 BE/FE/E2E 전체는 재실행하지 않았다.

상세: [06-redis-transport-comparison.md](06-redis-transport-comparison.md), [실제 단일 대조](evidence/AUTONOMOUS_CONTINUATION/redis-transport-comparison-ea5cd91f4a084b97/summary.json), [로컬 검사](evidence/REDIS_TRANSPORT_COMPARISON/20261001-control/local-checks.json).

## 직전 Redis 활성화 진단 — 05 당시 기록


2026-10-01 추가 진단에서 입력→ConfigData→실제 native URI 설정 일치6개 true, DNS 주소 해석 PASS를 관측했다. 그러나 Netty TCP connect promise는 **ClosedChannelException**으로 실패했다. TLS/HANDSHAKE/ACTIVE/PING은 모두 NOT_OBSERVED, 명령 관측0이다. 현재 근거는 비밀번호·인증서·RESP3·방화벽·provider outage 중 근본 원인을 확정하지 못한다. `CONNECTION_ACTIVATION_OTHER` 축약을 벗어나 **TCP_FAILURE / NARROWED**로 범위를 좁혔다.

진단기만 제품 기본 대기열·TimeoutOptions 설정과 일치시켰고, 단계·cause/suppressed·handshake 명령을 비노출 관측하도록 보강했다. 로컬 Java23 + Python10 PASS, compile PASS. 관측기 on/off에서 TLS 정상/CA/hostname/인증 실패 결과가 동일했다. old queue1의 CLIENT metadata 거절은 로컬에서 재현됐지만 PING은 성공해 원격 실패 원인으로 단정하지 않는다. 제품·TLS 검증·3초/2초·기본 protocol·접속파일은 유지했다.

새 연결은1회이며 비교 변수를 뒷받침하는 근거가 없어 두 번째 실행은 하지 않았다. 이번 login/schema/data key 작업0, client/resources 정리 PASS. 기존 원장을 보존해 누적 예약 **7,152/10,000**, 잔여 **2,848**, 누적 login 시도1/8이다. 신규256명령+2,000정리 예약을 포함하며 실제 청구 사용량과 다르다.

상세: [05-redis-activation-diagnosis.md](05-redis-activation-diagnosis.md), [단계별 원격 관측](evidence/AUTONOMOUS_CONTINUATION/redis-activation-96a9699ad683409e/summary.json), [이번 정제 요약](evidence/REDIS_ACTIVATION_DIAGNOSIS/20261001-native-check/summary.json).

## 직전 공급자 실행 기록 — 현재 예산은 위 최신값 사용

승인된 Chrome에서 MoneyToad 전용 TiDB Starter Free(AWS Tokyo)·Upstash Free(Singapore)를 확인했다. 사용자가 최초 비밀번호 생성과 가려진 로컬 입력을 완료했고, 지정 접속정보 파일의 정확한9키·owner·700/600·링크 안전 검사와 preflight 입력 PASS를 확인했다. 입력 파일은 덮어쓰지 않았다. 콘솔의 비밀 표시를 숨겼으며 이번 입력 서버·탭은 종료했다. 이전 입력 부재 근거는 과거 상태로 보존한다.

실제 managed probe1회에서 **TiDB identity TLS·제한 runtime 계정·DDL·제품 ConfigData validate·시간대/날짜/한국어/제약조건/집계/rollback PASS**. 첫 demo login은 기대한201을 내지 못해 **FAIL**이며 HTTP 실제 오류코드는 당시 저장하지 않아 추정하지 않는다. 원장과 제어 흐름상 Redis key 기록 전 연결 확인 단계에서 중단됐다. 두 방문자 seed/API·Lua/TTL/회전/폐기/재연결은 BLOCKED다.

후속 native Lettuce 읽기 전용 연결/PING 진단1회도 **FAIL / CONNECT_ACTIVATE / CONNECTION_ACTIVATION_OTHER**, 명시적 PING0이었다. 실제 제품 connect3초/command2초와 TLS 인증서·hostname 검증을 유지했고 자동 retry·DB·key·login 쓰기는 없었다. 현재 근거는 비밀번호·TLS·DNS 중 원인을 확정하지 못한다. 접속정보 재입력이나 재설정을 요청하지 않는다.

생성한 schema·제한 계정 삭제/부재 확인과 client 정리 PASS. 생성 Redis key0, 원장2개 모두 정리 확인. 누적 예약은 **4,896/10,000**, login 시도1/8(성공0)이며 state를 초기화하지 않았다. 남은5,104는 기존 완주 시나리오의6,096보다 작아 전체 검증 반복을 멈췄다. 이는 runner 예약량이며 공급자 청구 사용량 측정이 아니다.

검증기의 managed runtime username 접두사 교정만 적용했다. 기존 안전19+신규9=28 PASS, 읽기 전용 진단 Java4+Python6 PASS, compile PASS. 제품 정책·권한·기존 assertion은 유지했다. 아래 BE/FE/E2E 수치는 이전 로컬 검증이며 이번 원격 PASS가 아니다.

상세: [04-provider-console-observation.md](04-provider-console-observation.md), [실제 managed 결과](evidence/AUTONOMOUS_CONTINUATION/provider-08f6e79d15b941cf/summary.json), [읽기 전용 결과](evidence/AUTONOMOUS_CONTINUATION/redis-connectivity-d4bcd19b28e8437f/summary.json), [현재 정제 요약](evidence/PROVIDER_CONSOLE_ACCESS/final-observation/summary.json).

## 이전 로컬 완료 기록 — 원격 완료와 구분

- 시작 파일399개 해시·mode·Git 상태를 private 원장에 확보했다. 공개 저장소 origin이 요청한 프로젝트와 일치한다.
- 당시에는 지정 접속정보 파일이 없어 `CONNECTION_FILE_MISSING`/원격 실행0이었다. 현재 입력 PASS와 실제 원격 관측은 위 최신 상태를 따른다.
- 기존 preflight는 입력 검사와 원격 완료를 구별한다. exit2만 보고 credential이 유효하지 않다고 단정하지 않는다.
- 실제 JDBC/Lettuce/Lua·제품 ConfigData/JWT/Guard/seed/API를 실행하는 유한 공급자 검증기와 로컬 TLS 리허설 도구를 저장했다. 전용 schema/정확한 key 원장/누적 예산/정리 실패 차단을 적용했다.
- Cloudflare Pages API 프록시, demo readiness GET, 공유 준비 대기와 login 전용 timeout, 제품 시스템 폰트를 구현했다. 실제 서비스 생성·배포는 없다.
- 기존 BE275 + Render91 + readiness12 + probe 안전19가 각각 PASS(합계397, 단일 suite 수가 아님). 로컬 TLS7계약·도구 Python33+14+7도 별도 PASS.
- FE235=기존176+신규59 PASS. 제품·테스트·Functions·E2E 타입, OAuth/demo build, lint0 errors/0 warnings PASS.
- 최종 고정 코드에서 Chromium 독립2회 각각4 PASS. 실제 Spring/MySQL/Redis·제품 bundle·실제 보안 체인, 서버 준비/복원 보류 중 보호 요청0, 외부 애플리케이션/폰트 요청0, cleanup PASS.
- 기존 portfolio38파일·기존 deployment evidence22파일·01/02보고서는 시작 checksum과 동일하다. 시작 파일 삭제/mode 변경0, Git HEAD 동일, staged0, package/lockfile 변경0.

상세 구현·중간 실패·최종 근거는 [03-gateway-readiness.md](03-gateway-readiness.md)에 있다. [최종 BE](evidence/AUTONOMOUS_CONTINUATION/be-0454eeb94f53/summary.json), [최종 FE](evidence/AUTONOMOUS_CONTINUATION/fe-final/fe-summary.json), [최종 browser](evidence/AUTONOMOUS_CONTINUATION/browser-016d954532e4/summary.json), [로컬 probe](evidence/AUTONOMOUS_CONTINUATION/provider-local-4fda85a97fde/summary.json).

[최종 공개 내용/보존 감사](evidence/AUTONOMOUS_CONTINUATION/final-audit-161db57d6e9b/summary.json): 468개 파일 실제 내용 검사 PASS, 미해결 secret/PII 후보0. 기존 분류를 보존하고 이동한 기존 소스12지점과 개별 확인한 합성 fixture/변수13지점만 정확한 행·내용 해시로 추가했다. 실제 credential 패턴과 evidence 규칙은 완화하지 않았다. 과거 보호 문서/근거62개 checksum 보존 PASS, git diff 공백 검사 PASS.

## 직전 공급자 단계의 판정과 남은 작업 (이력)

이번 승인된 공급자 기능 경로에는 FAIL/BLOCKED/skip이 없다. 과거 첫 login 실패는 이번4회201·실제 기능 검증으로 복구 확인했다. 이전 native 진단의 역사적 네트워크 조건 전체를 소급해 규명한 것은 아니다.

- 공급자 장애전환 후 acknowledged revoke 무손실 보장: **NOT_ESTABLISHED**. 이번에는 일반 reconnect만 확인했다.
- 공개 전 방문 수용 상한·생성 남용 방지·잔존 SQL 정리: 미완료.
- 모바일 Chart 상세 표 가독성: 기존390×844 검토 실패가 남아 있다. 이번 제품 UI 수정0.
- 실제 Render/Cloudflare 생성·배포·공개 URL E2E: 미실행.
- 과거 로컬 BE/FE/build/browser 결과는 위 역사 기록이며 이번 원격 검증으로 재실행한 결과가 아니다.

```text
PROVIDER_INPUT_READY=true
INPUT_REVISION_VERIFIED=true
REDIS_PORT_CORRECTED=true
OTHER_EIGHT_INPUT_FIELDS_UNCHANGED=true
UPSTASH_NATIVE_CONNECT_VERIFIED=true
LOCAL_RENDER_RUNTIME_READY=true
TIDB_CONTRACTS_VERIFIED=true
UPSTASH_FUNCTIONAL_CONTRACTS_VERIFIED=true
LOGIN_RECOVERY_VERIFIED=true
MANAGED_PROVIDER_CONTRACTS_VERIFIED=true
UPSTASH_FAILOVER_REVOCATION_GUARANTEE=NOT_ESTABLISHED
PROVIDER_SELECTION=HOLD
LOCAL_GATEWAY_AUTH_CONTRACTS_VERIFIED=true
PRODUCT_SYSTEM_FONT_APPLIED=true
MOBILE_CHART_LAYOUT_READY=false
LOCAL_GATEWAY_UX_READY=false
PUBLIC_DEPLOYMENT_READY=false
```

## 직전 공급자 단계의 재개 경계 (이력)

다음 한 건은 **공개 demo의 방문 수용 상한과 안전한 잔존 SQL 정리 정책 설계**다. 공급자 선택/공개 운영 준비 최종 판정은 기능 PASS와 별개다. 접속정보 재입력·비밀번호 재설정·동일 native 진단 반복은 필요 없다.

private state `moneytoad-deploy03-provider-state`는 기존 기록을 유지하며 최종 예약15,568·잔여0·누적login5/8·원장6개 cleanup 완료다. 승인된 이번 경로의 영구 marker가 있으므로 동일 명령을 다시 실행할 수 없다. marker 삭제·예약 환급·state 초기화·임의 상한 증액은 금지한다. 추가 원격 검증은 별도 범위/예약 승인이 있어야 한다.

이번에는 검증 코드·관련 테스트·09보고서·STATUS·새 정제 evidence만 저장했다. 과거 보고서/evidence와 기존 미커밋 제품 변경·Git HEAD/index는 보존했다. stage·commit·push·서비스 생성·유료 변경·배포0.
