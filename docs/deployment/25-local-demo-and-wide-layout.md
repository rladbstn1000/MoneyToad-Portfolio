# 25. 프런트엔드 전용 체험과 큰 화면 레이아웃 — 로컬 PASS

2026-10-03. 시작 기준은 clean main `f97bb5f5ba5e04d1f80666791f8986b3f8c236ac`이다. 별도 작업 사본에서 구현·검증 후 변경 파일만 반영했다. 기존 서버 연동 체험을 보존하고 브라우저 메모리에서 즉시 시작하는 명시적 local 모드를 추가했다. README와 실제 공개 서비스는 변경하지 않았다. 현재 배포 제품은 계속 `d020d837f2830125696d2038eb4a4c969864432e`다.

```text
FRONTEND_ONLY_DEMO_LOCAL_READY=true
WIDE_SCREEN_LAYOUT_READY=true
FRONTEND_ONLY_DEMO_PUBLIC_READY=false
CURRENT_PUBLIC_DEPLOYMENT_UNCHANGED=true
```

## 실행 모드와 로컬 실행

| AUTH MODE | DATA MODE | 동작 |
|---|---|---|
| 생략 또는 oauth | 생략 또는 remote | 기존 OAuth·저장·서버 API |
| demo | 생략 또는 remote | 기존 서버 인증·readiness·세션·실제 저장 |
| demo | local | 인증이 아닌 브라우저 메모리 체험 |
| oauth/생략 | local | 시작/build 실패 |
| 모든 모드 | 빈 문자열·공백·미등록 값 | 시작/build 실패 |

FE 디렉터리에서 기존 lockfile의 의존성을 설치한 뒤 다음 명령을 사용한다. 이번 검증은 이미 설치된 동일 lockfile 의존성을 읽기 전용으로 재사용했으며 새 패키지 설치·버전 변경은 없다.

```sh
npm run dev:demo-local
npm run build:demo-local
```

개발 서버는 loopback에 bind한다. 정적 결과는 `fe/dist-local/`이며 Git 제외 대상이다. 전용 Vite config가 demo/local을 명시하고 환경파일 로딩과 ambient VITE 노출을 차단한다. backend 주소나 공급자 접속정보 없이 build 및 실제 브라우저 실행이 통과했다. 기존 `npm run build`와 remote/OAuth 의미는 유지한다.

local은 서버 bootstrap·readiness·사용자 확인·인증 interceptor를 실행하지 않는다. demo 인증 transport도 생성하지 않는다. 공용 API 모듈을 잘못 호출하면 HTTP 이전에 거절한다. 가짜 HTTP 응답·가짜 인증·운영 MSW·Service Worker·브라우저 SQL은 없다. 자동 local fallback도 없다.

## 하나의 시나리오와 공급 경계

`localDemoScenario` → `localDemoStore` → 순수 집계 → 기존 query/mutation 형태의 경계로 연결한다. 기존 6개 화면과 QueryClient를 재사용한다. 실제 서버 서비스 호출은 remote/OAuth 분기에만 남는다.

- 직접 작성한 V1을 코드로 재현한다. 고정 metadata `anchorYearMonth=2026-10`, 샘플 날짜는 28일까지다. 브라우저 오늘 날짜를 사용하지 않는다.
- 정확히 12개월·거래240·예산72, 초기 합계9,990,000원. 원본 DB에서 export한 자료가 아니다.
- 기준월 총908,000원 / 카페58,000원 / 예산40,000원 / 누수18,000원.
- 예산60,000원으로 조절하면 누수0,40,000원으로 되돌리면18,000원.
- 분류 연습30,000원을 마트로 이동하면 카페28,000원·마트120,000원·총908,000원·누수0·연간 leaked=false.
- M-5 문화생활180,000원·기준60,000원·누수120,000원.
- 보험/세금의 기존 집계 제외 규칙과 상점별 계산을 유지한다. 없는 예산 레코드는 만들지 않는다. 화면 DTO의 `id:null`은 실제0원 예산과 구별되고 편집되지 않는다.

생성 결과와 행은 immutable이며 초기화마다 새 객체를 만든다. local 수정은 메모리에 즉시 반영하고 열린 query의 연간·월별·카테고리·예산 결과를 같은 상태에서 갱신한다. 서버 저장을 흉내 내는500ms debounce가 없다. remote의 기존500ms·저장 실패·재조회 계약은 보존했다. 늦게 실행되는 거래 mutation은 dispatch 당시 generation을 검사한다.

장독대·Chart·조언의 수치가 함께 갱신된다. 조언 문구는 준비된 샘플 표현이고 실시간 AI가 아니다. 곳간과 정보 입력은 기존 방문별 샘플 profile만 사용한다. 실제 개인·금융정보 입력 기능을 추가하지 않았다.

## 수명과 자산

| 경계 | local 결과 |
|---|---|
| 내부 메뉴·SPA 뒤/앞 이동·일반 탭 전환 | 거래·예산·profile 유지 |
| reload·새 탭 | 새 초기 샘플 |
| 처음부터 다시하기 | 현재 유효 경로를 유지하고 전체 상태·cache 초기화 |
| 체험 종료 | 초기화 후 마당; 서버 logout 없음 |
| 외부 페이지에서 재탐색 복귀 | 새 초기 샘플 |
| persisted pageshow | generation 교체·cache/profile 초기화 |

localStorage/sessionStorage/IndexedDB/cookie에 체험 데이터를 저장하지 않는다. 기존 OAuth storage나 서버 세션은 지우지 않는다. 실제 Chromium에서 외부 페이지 복귀는 재탐색으로 관측됐다. **bfcache는 NOT_OBSERVED**이며 persisted event에 대한 합성 lifecycle 테스트 PASS와 구분한다. StrictMode·rerender·일반 pageshow/visibility에서는 수정값을 보존한다.

local App은 전체 이미지 Promise.all을 기다리는 overlay를 사용하지 않는다. 첫 이야기 이미지는 eager/high, 나머지는 lazy, 활성 이야기의 배경만 준비한다. 페이지별 기존 이미지/Lottie를 재사용하고 공간을 예약한다. 실제 이미지 요청을 잠시 보류해도 체험 시작·장독대 조작이 가능함을 확인했다. 정적 다운로드와 이야기 애니메이션은 여전히 존재한다.

## 세 화면의 수정과 시각 확인

| 화면 | 수정 | 실제 확인 |
|---|---|---|
| 랜딩 | 어울리는 배경 위에 원본 비율 contain 전경·별도 글 영역 | CSS에 의한 추가 crop0·그림/글 겹침0 |
| 장독대 | 최대1440px grid, native500:750 종이 장식과 safe area 분리 | 실제 종이 x13–87% 안에 더 좁은16% 좌우 inset, 제목·목록·합계 분리 |
| Chart | 제목/안내를 연못 앞 문서 흐름으로 이동, 충돌 높이 제거 | 제목이 stage/plot 위, 그래프가 무대 안, 자연스러운 세로 스크롤 |

장독대 목록에만 내부 스크롤·안내·스크롤바를 둔다. slider thumb 양끝 여유,44px 조작 높이,20px 이상 패널 제목을 확인했다. 첫 행 일부가 보이는 스크린샷은 카페 행으로 이동한 정상 목록 스크롤 상태다. 제목·합계는 그 스크롤에 포함되지 않는다. 모바일 항아리 stage 최소390px를 보존했다.

랜딩4개 이야기·wheel/touch/키보드·페이지 점을 보존한다. 원본 landing1 이미지의 상상 장면 윗부분은 파일 자체 경계에서 이미 잘려 있다. 원본에 없는 내용을 복원한 것이 아니다. Chart 모바일은 장식 연못 아래에 읽을 수 있는320px 그래프를 둔다. 내용 숨김이나 화면 전체 축소로 통과시키지 않았다.

| CSS viewport | 실제 Chromium / overflow·종이·분리 |
|---|---|
| 390×844 | PASS |
| 768×1024 | PASS |
| 1440×1000 | PASS |
| 1920×1080 | PASS |
| 2560×1440 | PASS |
| 3840×2160 | PASS |
| 1920×900 | PASS |

7개 모두 body 가로 overflow0이고 그림·종이 안쪽·Chart rectangle을 별도로 검사했다. 실제 스크린샷을 직접 확인했다. DPR1이며 브라우저 zoom/OS 배율은 측정하지 않았다. 짧은 높이에서 Chart 아래쪽은 정상 세로 스크롤 대상이다.

전후 근거:

- 랜딩: [전](evidence/LOCAL_DEMO_WIDE_LAYOUT/before-1920-landing.png) / [후](evidence/LOCAL_DEMO_WIDE_LAYOUT/after-1920-1080-landing.png) / [3840px](evidence/LOCAL_DEMO_WIDE_LAYOUT/after-3840-2160-landing.png)
- 장독대: [전](evidence/LOCAL_DEMO_WIDE_LAYOUT/before-1920-pot.png) / [후](evidence/LOCAL_DEMO_WIDE_LAYOUT/after-1920-1080-pot.png) / [390px](evidence/LOCAL_DEMO_WIDE_LAYOUT/after-390-844-pot.png)
- Chart: [전](evidence/LOCAL_DEMO_WIDE_LAYOUT/before-1920-chart.png) / [후](evidence/LOCAL_DEMO_WIDE_LAYOUT/after-1920-1080-chart.png)

## 실제 브라우저와 회귀 결과

**FE524 PASS = OAuth241 + remote-demo235 + local48**. 기존473개를 유지하고 구조3개·local48개를 추가했다. failure/error/skip/todo0. 제품/test/E2E/Functions 타입, OAuth/remote/local build, 잘못된 mode5가지 거절, lint0errors/0warnings PASS. scanner 관련 합성39개 PASS.

정적 local Chromium9개 PASS: 전체 체험1·7개viewport·직접 경로/외부 복귀/자산 보류1. 정적 서버만 기동했고 BE/MySQL/Redis를 사용하지 않았다. 마당→장독대 예산 변경→Chart 분류 변경→조언→곳간→정보 입력→내부 이동→reload/reset/end를 실제 UI로 수행했다. API 응답 mock·강제 DOM 입력은 없다.

[네트워크 집계](evidence/LOCAL_DEMO_WIDE_LAYOUT/local-network.json): 정적 요청335회, API **시도0**, 외부 시도0, page error0. 차단해서 전달만0으로 만든 결과가 아니다. 외부 이동 경계는 별도의 소유 loopback 문서로 확인했다. 인증 쿠키와 브라우저 두 Storage 모두 비어 있음을 확인했다.

기존 remote 회귀는 별도 전용 로컬 Spring·MySQL·Redis·HTTPS gateway를 사용했다. core6×독립2회=12PASS, 전체6화면390/768/1440=3PASS. 실제 예산PATCH2·categoryPATCH1·reload 후 DB수정 유지·종료·Cookie A/E·JWT 폐기·CORS·gateway/제한 계약을 보존했다. 자동 retry0, 외부/금지 요청0, cleanup PASS. core 이후 마지막 full 실행은 현재 제품 bytes와 일치한다. 별도 mobile/cold-start 시나리오는 추가 반복하지 않았고 관련 화면은 full3에서 검증했다. Cookie D와 BE724 전체는 변경 없는 기존 근거이며 **이번 재실행으로 표기하지 않는다**.

초기 실패도 [별도 기록](evidence/LOCAL_DEMO_WIDE_LAYOUT/iteration-results.json)에 보존했다. 새 local 키보드 검증기가 controlled value 갱신 전에4번 입력한45000 결과는 각 실제 입력의 표시값 관측으로 교정했다. 기존 CSS 정적 검사2개는 새로운 문서 흐름과 동일 system font 표기를 반영했다. remote full의340px stage 실패는 assertion을 유지한 채 제품 CSS 최소390px로 복구했다. 실패를 삭제하거나 automatic retry로 통과시키지 않았다.

## 관측 시간과 한계

Mac 로컬 Node22.14.0 / Chromium153.0.8010.12 / Playwright1.63.0에서 측정했다. 공개 환경·다른 컴퓨터의 성능 보장이 아니다.

- 순수 생성 함수1회: 약0.34ms, 거래240·예산72. **Node 측정**이며 브라우저 다운로드 시간이 아니다.
- 정적 landing 문서 응답 완료5.5ms, DOMContentLoaded45.5ms. 그 시점 관측 JS1건 약6.1ms, 이미지1건 약5.1ms. lazy 자산 전체 완료 시간으로 해석하지 않는다.
- 실제 버튼 클릭→예산 controls 표시 약64ms.
- 실제 키보드4단계→예산/누수 갱신 약113ms, 분류 Select 조작→집계 갱신 약105ms. Playwright 입력·assertion 시간이 포함된다.
- backend 대기·인위적 저장 지연0. 네트워크 다운로드0초라는 의미가 아니다.

[실제 관측](evidence/LOCAL_DEMO_WIDE_LAYOUT/local-journey.json), [생성 측정](evidence/LOCAL_DEMO_WIDE_LAYOUT/scenario-timing.json), [복귀 범위](evidence/LOCAL_DEMO_WIDE_LAYOUT/local-history.json).

## 변경 범위·공개 검사·재실행

제품은 FE에 한정된다. mode/App/Provider/Guard/entry/header, 중앙local시나리오·계산·상태·lifecycle, 기존query/mutation경계,3개페이지와CSS를 수정했다. 새local단위/컴포넌트/Chromium테스트, 전용Vite/Vitest/Playwright설정과 npm scripts, 검증runner·결과문서가 추가됐다. lockfile/패키지 버전/README/BE/Functions/provider 설정/기존 evidence/HEAD/index는 보존한다.

[시작 manifest](evidence/LOCAL_DEMO_WIDE_LAYOUT/baseline-manifest.json), [구현 delta](evidence/LOCAL_DEMO_WIDE_LAYOUT/implementation-delta.json), [보존 결과](evidence/LOCAL_DEMO_WIDE_LAYOUT/preservation.json)를 함께 기록한다. scanner 규칙과 허용 범위는 바꾸지 않았다. demoAuth의 기존 값 없는 header 표현4곳은 코드 행/구문/횟수 그대로이며 추가한 local 분기 때문에 달라진 행 번호·파일 checksum만 기존 분류에 연결했다. 신규 분류0이다. strict scanner 최종 PASS·미해결0, 공개 evidence 비밀값·개인 경로·식별값0이다.

전용 검증 진입점은 `scripts/verification/local_demo_checks.py`다. `--help`에서 실제 옵션을 확인하고 동일 lockfile의 이미 설치된 의존성 디렉터리, 새 결과 디렉터리, browser 단계의 설치된 Chromium cache를 명시한다. `--phase fe`와 `--phase browser`를 각각 실행한다. 이 runner는 원본에 설치하지 않고 private 복사본·독립 캐시·소유 정적 서버를 사용하며 finally에서 소유 프로세스/브라우저/서버를 정리한다. 다른 사용자의 자원은 건드리지 않는다. 최종 정리 모두 PASS다.

## 정적 배포 경계와 남은 작업

`dist-local`에는 HTML/JS/CSS/기존 정적 자산과 `/* /index.html 200` SPA fallback만 있다. `_routes.json`, `_worker.js`, `functions`는 없다. 기존 `fe/functions`는 보존했다. 브라우저가 API를 안 부른다는 사실만으로 기존 Cloudflare 프로젝트의 Functions가 사라진다고 주장하지 않는다.

후속 공개 전환은 **별도 승인 작업**이다. 정적 결과물만 독립 deployment root로 복사하고, 그 root 및 부모 작업 경로에 기존 `functions`가 없는 상태에서 업로드 대상을 이 결과물로 한정해야 한다. 기존 `fe` 저장소 root를 그대로 Git 배포하면 Functions를 자동 감지할 수 있으므로 그 경로를 재사용하지 않는다. 이 산출물은 API 경로를 제공하지 않으며 BE주소·서버용 비밀값을 FE build 입력으로 넘기지 않는다. 해당 배포 방식의 실제 Pages 설정·직접 경로 fallback·요청0은 전환 때 별도로 확인해야 한다. 이번에는 Cloudflare/Render 설정·공개 사이트·공급자에 접근하지 않았다.

현재 공개 서비스는 검증된 이전 서버 연동 버전 그대로다. local 모드에서 reload가 초기화라는 의도된 차이를 안내한다. 샘플 수치·문구는 실제 금융/AI 결과가 아니며 변경 내용은 보존되지 않는다. 실제 bfcache 활용·브라우저 zoom/OS 배율·클라우드 전환은 미검증이다. stage/commit/push/배포는 수행하지 않았다.
