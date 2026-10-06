# 26. 원래 구도를 살리는 랜딩·장독대 배치와 조언 탐색 개선

2026-10-06. 25단계의 **현재 미커밋 작업 트리**를 출발점으로 삼아 별도 작업 사본에서 수정했다. HEAD로 되돌리거나 기존 구현을 대체하지 않았다. 변경은 랜딩의 그림 위 문구, 장독대의 캐릭터·항아리 배치와 구멍 위치, 조언 상세 탐색에 한정한다. 공통 샘플 거래·예산·집계·초기화 정책과 기존 서버 연동 계약은 유지했다.

최종 FE 548개와 정적 Chromium 11개 시나리오가 모두 PASS다. API 시도·외부 요청·페이지 오류는 0이며 소유 검증 자원 정리도 PASS다. 최종 공개 scanner·문서 링크 검사와 사용자용 미리보기 FE 파일 checksum 대조도 PASS다. 사용자의 실제 모니터 시각 승인은 별도로 남긴다.

```text
VISUAL_REFINEMENT_LOCAL_READY=true
USER_VISUAL_APPROVAL=PENDING
FRONTEND_ONLY_DEMO_PUBLIC_READY=false
CURRENT_PUBLIC_DEPLOYMENT_UNCHANGED=true
```

## 변경 범위와 보존한 계약

제품 변경은 `ScrollLandingPage.tsx/.css`, `LeakPotPage.tsx/.css`, `potLeakAnchors.ts`, `ToadAdvice.tsx/.css`다. 관련 FE 회귀 테스트와 로컬 시각 검증을 추가·갱신했다. 새 이미지·폰트·패키지·외부 API는 추가하지 않았고 원본 자산 파일도 편집하지 않았다.

- 명시적 demo/local, demo/remote, OAuth의 분리와 기존 서버 인증·저장 계약을 보존했다.
- local 시작에는 서버 연결이 필요하지 않다. 예산·분류 변경은 동일 메모리 상태에서 장독대·Chart·조언에 즉시 반영한다.
- 내부 이동 중 상태 유지, reload/reset/end의 초기화, 금융정보 없는 샘플 profile을 유지했다.
- 12개월·거래 240개·예산 72개의 기존 제품 시나리오를 수정하지 않았다. 화면에서 사용자가 변경한 누수 금액을 초기 데이터 오류로 취급하지 않는다.
- 두루마리 safe area와 Chart 안내/연못 분리, 기존 6개 페이지를 보존했다.
- BE·DB·Redis·공급자 설정·README·과거 보고서/evidence·Git 이력은 수정 대상이 아니다. stage·commit·push·공개 접속·재배포를 수행하지 않았다.

## 랜딩: 실제 그림 영역 안의 텍스트

기존의 그림/별도 설명 열을 제거했다. `.dk-story-scene`은 각 원본 이미지의 실제 가로·세로 비율을 사용하고 사용 가능한 너비와 높이 중 작은 쪽에 맞춘다. 이미지와 텍스트 overlay가 같은 scene의 좌표계를 공유하므로 글이 contain 바깥의 letterbox 영역으로 빠지지 않는다. 전경 이미지는 전체가 표시되며 `object-fit: contain`을 유지한다. 남은 viewport는 기존 배경색과 흐린 동일 그림으로 처리한다.

| 장면 | 원본 크기 | 기본 문구 위치 | 390px 배치 |
|---|---|---|---|
| 콩쥐의 꿈 | 1549×1033 | 오른쪽 빈 바닥 | 오른쪽 여백에 제목·본문 |
| 두꺼비를 만나다 | 1493×995 | 제목은 왼쪽 위, 본문은 아래 테이블 | 얼굴 아래의 하단 본문 |
| 장독대의 비밀 | 2048×1365 | 왼쪽 벽 | 위쪽 제목·아래쪽 본문으로 얼굴과 물 붓는 동작 확보 |
| 콩쥐의 장독대 | 1280×853 | 오른쪽 위 빈 벽 | 오른쪽 위 제목·왼쪽 아래 본문으로 항아리 누수와 두꺼비 확보 |

위치는 이미지 비율에 대한 백분율로 지정했다. 제목/본문 뒤에만 부드러운 국소 scrim과 그림자를 두고 그림 전체를 어둡게 하지 않는다. 모바일 본문은 16px, 제목은 25px이며 줄바꿈과 배치를 조정한다. 제목·설명의 원문, 4개 이야기, wheel/touch/키보드, 700ms 이동 잠금, 페이지 점·표시·체험 버튼은 그대로다.

`landing1.webp`의 상상 장면 윗부분은 **원본 파일 경계에서 이미 잘려 있다**. 이번 변경은 원본에 없는 내용을 복원하거나 추가로 crop한 것이 아니다. 원본 비율 유지와 CSS 추가 crop 0을 별도로 검사한다.

이전 테스트의 “그림과 글이 겹치지 않는다”는 계약은 이번 사용자 요구와 반대이므로 명시적으로 교체했다. 새 검사는 overlay의 scene 내부 배치, 제목·본문의 이미지/viewport 내 포함, 실제 이미지 비율과 전체 표시를 확인한다. 얼굴과 주요 장면의 가시성은 실제 스크린샷을 함께 검토한다.

## 장독대: 캐릭터와 항아리의 공간 분리

demo stage는 캐릭터 열과 항아리 열을 별도로 확보한다. 캐릭터는 일반 레이아웃 흐름의 flex 영역에서 간격을 갖고, 항아리는 독립된 SVG 영역에 놓인다. 음수 캐릭터 margin을 제거해 콩쥐·두꺼비가 서로 또는 항아리 앞에 겹치지 않게 했다. 데스크톱에서는 왼쪽 캐릭터, 가운데 항아리, 오른쪽 두루마리 순서다.

1199px 이하에서는 두루마리를 다음 행에 두고, 600px 이하에서는 항아리와 캐릭터도 서로 다른 행을 쓴다. 좁은 화면에 모든 그림을 한 줄로 축소하지 않는다. 항아리 원본 280:319 비율과 SVG 바닥선 480/520을 기준으로 캐릭터 발과 항아리 바닥을 맞췄다. 물웅덩이와 물줄기는 항아리 SVG 내부 효과로 유지한다.

정상/누수 상태의 서로 다른 캐릭터 이미지 모두 레이아웃 영역을 확보한다. 기존 종이의 비율·safe area·목록 스크롤·slider·제목·합계 배치는 보존했다. 투명 여백이 있는 이미지이므로 rectangle 검사만으로 시각 완료를 판단하지 않고 실제 화면을 함께 확인한다.

새 열 배치에서 드러난 tooltip 좌표 차이도 교정했다. 구멍 진입과 포인터 이동 모두 전체 시각화 컨테이너 기준 좌표를 사용하고 SVG 내부 hit-test는 그대로 유지한다. 1920×1080에서 같은 구멍 위를 1px 움직이는 실제 재확인으로 종전 약 399px의 좌측 이동이 사라지고 포인터 옆 8px 위치를 유지함을 확인했다.

## 장독대: 카테고리별 고정 비대칭 구멍

`potLeakAnchors.ts`의 작은 고정 목록이 지원하는 12개 카테고리의 위치를 소유한다. 필터링된 누수 배열의 index나 월별 예산 ID를 좌표로 사용하지 않는다. 원형 각도·반지름 계산과 render마다 실행하는 난수 배치는 없다.

| 카테고리 | u | v |
|---|---:|---:|
| 식비 | 0.34 | 0.34 |
| 카페 | 0.65 | 0.48 |
| 마트 / 편의점 | 0.24 | 0.63 |
| 문화생활 | 0.57 | 0.78 |
| 교통 / 차량 | 0.75 | 0.31 |
| 패션 / 미용 | 0.44 | 0.58 |
| 생활용품 | 0.78 | 0.66 |
| 주거 / 통신 | 0.53 | 0.30 |
| 건강 / 병원 | 0.31 | 0.81 |
| 교육 | 0.22 | 0.45 |
| 경조사 / 회비 | 0.68 | 0.86 |
| 기타 | 0.52 | 0.44 |

좌표는 항아리 원본 안의 normalized coordinate다. 서로 다른 높이와 불균일한 거리를 사용하고 목·뚜껑·바닥 경계와 둥근 몸통 바깥을 피한다. 금액은 제한된 구멍 크기에만 반영하고 중심은 이동하지 않는다. 구멍 그림에서 실제 어두운 구멍 위치를 기준으로 물줄기 출발점을 맞추고 좌우 뒤집기의 기준점도 같은 anchor로 둔다.

누수가 0이면 해당 구멍과 물줄기만 사라진다. 다른 카테고리의 좌표와 누수 계산·금액은 유지한다. 최대 크기에서 구멍끼리 겹치지 않고 실제 몸통 안에 들어가는지를 단위 테스트로 확인한다. 실제 브라우저의 0/1/3/5/12개 누수 및 첫 항목 제거 검사는 **제품 seed를 바꾸지 않는 별도 렌더링 fixture**를 사용한다. 최종 Chromium 검사에서 0/1/3/5/12개 구멍 수·물줄기 출발점·구멍 비겹침과 첫 항목 제거 후 나머지 좌표 보존이 PASS다. [구멍 근거](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/anchors.json)와 [최대 12개 화면](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/after-pot-12-anchors.png)에 저장한다.

## 조언: 첫 화면의 실제 상세 이동 버튼

요약 액자 바로 아래에 52px 이상 높이의 “카테고리별 소비 조언 보기 ↓” 버튼과 보조 문구를 둔다. fixed 요소가 아니므로 내용을 덮지 않는다. 좁은 화면에서는 요약·이동 버튼을 월 선택보다 먼저 배치해 첫 화면에서 동작을 발견할 수 있게 했다.

클릭하면 현재 페이지의 상세 제목에 focus를 옮기고 해당 위치로 스크롤한다. 새 route·reload·API 호출은 없다. 상세 제목에는 `scroll-margin-top: 112px`를 지정했다. mandatory snap과 viewport 고정 section 높이를 제거해 일반 wheel/touch로도 자연스럽게 아래 내용을 탐색할 수 있다. reduced-motion에서는 부드러운 이동과 불필요한 애니메이션을 줄인다.

분석할 항목이 없는 달은 “이번 달 소비 결과 보기 ↓”로 바뀌며 실제 빈 결과 영역으로 이동한다. 없는 카드 수를 안내하거나 dead button을 남기지 않는다. 월 선택·기존 집계·카드·modal·Escape·포커스 복귀를 유지했다. 7개 viewport에서 클릭·제목 노출·modal 회귀를 확인했고 키보드/wheel/reduced-motion/빈 결과의 추가 브라우저 시나리오도 PASS다. [조언 탐색 근거](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/advice-navigation.json)에 저장한다.

## 검증 결과와 화면 조건

| 검증 | 결과 |
|---|---|
| FE 전체 | **548 PASS**: OAuth 251 + remote demo 243 + local demo 54, 실패/skip/todo 0 |
| 제품·test·E2E·Functions 타입 | 4종 PASS |
| lint | 0 errors / 0 warnings, `--max-warnings 0` |
| OAuth·remote demo·local demo build | 3종 PASS |
| 잘못된 실행 모드 거절 | 5종 PASS |
| FE runner 격리·소유 자원 정리·입력 복사본 보존 | PASS |
| 기존 정적 Chromium 시나리오 | 9 PASS: 전체 여정 1 + viewport 7 + 직접 경로/복귀/자산 보류 1 |
| 새 구멍 fixture·조언 탐색 Chromium | 2 PASS, 기존 9개와 합쳐 최종 **11/11 PASS**, global errors 0 |
| 최종 API/외부/page error 집계 | 모두 **0**, 소유 서버/브라우저 정리 PASS |
| 공개 scanner·문서 링크 | PASS — 미해결 0, 기존 scanner 규칙/분류 변경 0 |
| BE·DB·Redis·공급자 전체 검증 | 미실행 — 해당 제품/설정 변경 없음, 기존 근거 유지 |

| CSS viewport | 기존 시각·회귀 시나리오 |
|---|---|
| 390×844 | PASS |
| 768×1024 | PASS |
| 1440×1000 | PASS |
| 1920×1080 | PASS |
| 2560×1440 | PASS |
| 3840×2160 | PASS |
| 1920×900 | PASS |

7개 화면에서 랜딩 4장면의 overlay·원본 비율·텍스트 포함, 정상/누수 캐릭터 배치, 종이 safe area, Chart 안내/연못 분리, 조언 버튼의 첫 화면 노출과 상세 접근, body 가로 overflow 0을 확인했다. 전체 여정에서는 6개 페이지와 즉시 집계·초기화 동작을 유지한다. 실제 화면 검토와 geometry 검사는 별도 근거다.

CSS viewport와 DPR은 브라우저에서 관측한다. 현재 검증은 DPR 1의 Chromium이며 브라우저 zoom·OS 디스플레이 배율·사용자의 실제 모니터 조건을 같은 것으로 간주하지 않는다. 사용자의 실제 화면에 대한 시각 승인은 자동 검증으로 대신하지 않는다.

초기 실패와 교정 이력은 [iteration 기록](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/iteration-results.json)에 보존했다. 신규 fixture 준비의 외부 자산 경로·과도한 external 지정·Lottie UMD/ESM 선택 실패와 첫 장면 중복 클릭에 따른 700ms 이동 잠금 관측 실패를 별도 iteration JSON에 보존했다. 제품 자료나 API 차단 assertion을 완화하지 않았다. 최종 실행은 자동 retry 0의 11/11 PASS다.

## 전후 화면과 저장 근거

새 근거는 `docs/deployment/evidence/LOCAL_DEMO_VISUAL_REFINEMENT/`에만 저장한다. 저장한 근거와 링크 검사 결과는 아래와 같다. 25단계 보고서와 기존 evidence는 보존한다.

| 화면 | 수정 전 | 수정 후 |
|---|---|---|
| 랜딩 | [기존 화면](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/before-landing.png) | [1920px 1장면](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/after-1920-1080-landing-1.png) · [390px 3장면](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/after-390-844-landing-3.png) |
| 장독대 | [기존 화면](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/before-pot.png) | [1920px](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/after-1920-1080-pot.png) · [390px](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/after-390-pot-detail.png) |
| 조언 | [기존 화면](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/before-advice.png) | [1920px](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/after-1920-1080-advice.png) · [390px](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/after-390-844-advice.png) |

[FE 결과](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/fe-results.json), [Chromium 요약](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/browser-summary.json), [개별 시나리오](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/tests.json), [네트워크 합계](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/network-summary.json), [공개 검사](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/scanner.json), [링크 검사](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/links.json)를 같은 새 evidence에 저장한다. 공개 검사·링크 검사 모두 PASS이며 원문 실행 로그는 공개 자료에 포함하지 않았다.

## 재실행과 실제 미리보기

기존 `scripts/verification/local_demo_checks.py`는 정확한 lockfile의 설치된 의존성, 새 결과 디렉터리, browser 단계의 설치된 Chromium 경로를 명시적으로 받는다. 원본에 설치하거나 실제 `.env`를 로딩하지 않고 별도 복사본·의존성 overlay·소유 loopback 정적 서버를 사용한다. `--phase fe`와 `--phase browser`를 각각 실행한다. browser phase의 별도 누수 fixture는 검증용 build에만 추가하며 제품 초기 자료나 일반 local build에 포함하지 않는다.

```sh
python3 -B scripts/verification/local_demo_checks.py --phase fe \
  --dependencies <exact-lockfile-dependency-directory> --output <new-fe-result-directory>
python3 -B scripts/verification/local_demo_checks.py --phase browser \
  --dependencies <exact-lockfile-dependency-directory> --browser-path <installed-chromium-directory> \
  --output <new-browser-result-directory>
```

새 사용자용 미리보기는 [http://127.0.0.1:58170/](http://127.0.0.1:58170/)다. 이번 최종 FE 복사본을 제공하는 소유 loopback 서버로 시작했다. 개발 중 사용한 기존 포트와 구분하며 다른 사용자의 서버를 종료하지 않았다. 최종 FE 파일 217개를 제공 복사본과 SHA-256으로 대조했다. [미리보기 일치 근거](evidence/LOCAL_DEMO_VISUAL_REFINEMENT/preview.json)를 함께 저장했다. 실행 명령은 `npm run dev:demo-local`이며 종료를 요청하면 이 작업 소유 서버만 종료한다.

공개 검사는 기존 `public_scan.scan()`을 최종 공개 파일 사본에 적용하고 결과를 새 evidence에만 저장했다. 과거 기본 출력 경로를 덮어쓰는 실행 방식은 사용하지 않았다.

공개 서비스는 이전 배포 그대로다. Render·TiDB·Upstash·Cloudflare 설정 변경이나 공개 접속을 수행하지 않았으며, 이 보고서는 공개 배포 완료를 의미하지 않는다.
