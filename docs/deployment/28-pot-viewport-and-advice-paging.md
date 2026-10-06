# 28. 장독대 한 화면 배치·슬라이더 전 구간 누수·조언 구간 전환

2026-10-06. 25~27단계 미커밋 구현과 기존 근거를 보존한 상태에서 프런트엔드 표현과 관련 검증만 변경했다. 시작 HEAD는 `f97bb5f5ba5e04d1f80666791f8986b3f8c236ac`이며 HEAD/index를 변경하지 않는다. 실제 공개 제품은 그대로이고, 이 문서는 frontend-only local 수정본의 검증이다.

## 재현과 한 화면 배치

27단계 미리보기의 실제 Chromium computed geometry를 먼저 측정했다. 장독대 root의 scrollHeight는 390×844에서1613px, 768×1024에서1655px, 1440×1000에서1037px, 1920폭에서1305px, 2560×1440에서1788px였다. 3840×2160만 당시 viewport 안이었다. 가로 폭을 기준으로 SVG 비율과 장면 최소 높이를 계산하던 구조가 작은 높이의 viewport를 넘겼다. [수정 전 측정](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/before-geometry.json).

`local-pot-page`에만 viewport 높이와 `auto auto auto minmax(0,1fr)` grid를 적용했다. 실제 Header·안내·월 navigation 다음의 남은 행을 장면과 종이가 나눈다. 장면은 CSS size container의 높이와 너비를 모두 사용하고 폭1600px 상한으로 큰 화면의 항아리 비중을 제한한다. SVG500×700은 내부 좌표계로 유지하며 700 CSS px을 요구하지 않는다. 새 ResizeObserver나 전역 body 스크롤 차단은 장독대에 추가하지 않았다.

900px 이하는 작은 캐릭터/항아리 행과 두루마리 행으로 나눈다. 월 버튼은 최소44px, 슬라이더는 기존44px 터치 영역·금액14px 이상·제목20px 이상을 유지한다. 종이 장식은 가용 사각형을 채우지만 그 안의 텍스트나 컨트롤을 transform으로 축소하지 않는다. 제목/기준월/안내와 합계는 고정하고 예산 목록만 내부 `overflow-y:auto`로 읽는다. 누락 예산의 표시·비활성·금액 계산은 그대로다.

아래는 CSS pixel 기준 표시 프레임 높이이며 투명 여백을 제거한 원본 그림 높이로 오인하지 않는다. 그림 전체 프레임이 viewport 안에 있어 실제 alpha 가시 영역도 포함된다. 물의 실제 가시 영역은 별도 관측했다.

| viewport | 가용 main | 항아리 | 콩쥐 | 두꺼비 | 두루마리 | 목록 | 외부 초과 X/Y |
|---|---:|---:|---:|---:|---:|---:|---|
| 390×844 | 647.0 | 106.2 | 106.1 | 41.3 | 402.6 | 178.2 | 0 / 0 |
| 768×1024 | 827.0 | 138.3 | 142.8 | 55.6 | 517.8 | 292.9 | 0 / 0 |
| 1440×1000 | 805.0 | 379.0 | 417.8 | 162.5 | 561.6 | 263.0 | 0 / 0 |
| 1920×1080 | 885.0 | 422.8 | 467.9 | 182.0 | 748.8 | 440.7 | 0 / 0 |
| 1920×900 | 705.0 | 333.6 | 365.9 | 142.3 | 685.0 | 391.6 | 0 / 0 |
| 2560×1440 | 1245.0 | 601.3 | 576.0 | 224.0 | 900.0 | 557.1 | 0 / 0 |
| 3840×2160 | 1965.0 | 604.2 | 576.0 | 224.0 | 900.0 | 557.1 | 0 / 0 |

html/body/root/장독대/main/stage의 scrollExtent와 scrollTop, 각 그림·종이 제목·목록·합계의 실제 rectangle을 함께 확인했다. root는 overflow:visible 상태이며 밖의 내용을 잘라내서 통과시키지 않는다. 목록 밖 wheel/PageDown과 슬라이더 포커스 이동에서 외부 스크롤0, 목록 끝까지 wheel 이동 시 마지막 항목 접근·제목/합계/그림 위치 불변을 검증했다. 최대6개 실제 예산 누수와 합성12개 전체 카테고리 효과도 확인했다. [최종 기하](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/after-geometry.json), [390 상세 검사](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/layout-390-844.json).

[1920×900 수정 전](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/before-1920-900-pot.png) · [수정 후](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/after-1920-900-pot.png) · [390 최대 누수](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/after-390-844-pot-max.png) · [768 수정 후](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/after-768-1024-pot.png).

## 슬라이더 전체 범위의 local 시각 매핑

local에서만 지출 S·현재 한도 B·초과 L=max(S−B,0)을 사용한다. S>0/L>0이면 r=clamp(L/S,0,1), crackScale=.4+1.1r, waterScale=.3+1.7r이다. L=0이면 효과를 렌더링하지 않는다. 예산 ID가 없는 카테고리는 B=0으로 간주하지 않는다.

local에서 중간 금액에 먼저 걸리는 cap을 제거했다. remote/OAuth는 기존 절대 금액 곡선과 88,000원/136,000원 포화 계약을 그대로 유지한다. 실제 거래·한도·누수·조언 금액, 즉시 메모리 갱신·초기화는 변경하지 않았다. 상대 초과율의 표현이므로 서로 다른 카테고리의 절대 누수 금액이 같다고 그림 크기도 같다는 뜻은 아니다. 절대 금액은 기존 텍스트가 전달한다.

지출500,000과58,000 두 규모에서 native range key 입력으로 r=.2/.4/.6/.8/1을 왕복했다. 카테고리 중심·물 출발점은 고정이고 양수 변경 중 Lottie SVG 인스턴스는 유지됐다. 0에서 제거한 뒤 다시 낮추면 같은 anchor에 나타난다. 일반 재생 진행도 확인했다. 실제 제품 주거/통신 slider 역시 Home과5,000원씩 ArrowRight로0~500,000 전 구간을 검증했다. 자동 이벤트 주입이나 제품 상태 주입을 사용하지 않았다.

다음은 같은1920×1080 검증 fixture의 실제 컴포넌트, 주거/통신 anchor, Lottie 프레임12다. 균열은 원본565×442의 alpha>16 가시 폭335를 반영한 **균열선 포함 가시 폭**이며 검은 구멍 중심만의 폭은 아니다. 물은 실제 mask를 그린 픽셀을 SVG 변환으로 화면에 투영했다.

| 한도 | r | 가시 균열 폭(px) | 물 가시 폭×높이(px) |
|---:|---:|---:|---:|
| 500,000 | 0 | 효과 없음 | 효과 없음 |
| 400,000 | 0.2 | 27.86 | 45.69×77.61 |
| 300,000 | 0.4 | 37.74 | 69.96×118.84 |
| 200,000 | 0.6 | 47.62 | 94.23×160.08 |
| 100,000 | 0.8 | 57.51 | 118.50×201.31 |
| 0 | 1.0 | 67.39 | 142.78×242.55 |

프레임5/12/20 모두 다섯 양수 구간에서 실제 표시 크기가 엄격하게 증가했고 역방향으로 원래 크기에 돌아왔다. 최대12개 효과가 SVG 경계 안에 있고, 실제 local 페이지7viewport의 최대6개 예산 누수도 viewport 안이다. 합계 기반 물웅덩이 곡선과 기존 fixed anchor는 보존했다. 모든 애니메이션 프레임 전수 분석은 하지 않았다.

[관측 수치](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/severity.json) · [실제 제품 slider](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/product-slider.json) · [20%](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/after-slider-20-percent.png) · [60%](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/after-slider-60-percent.png) · [100%](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/after-slider-100-percent.png).

## 조언 구간 전환과 정상 읽기

첫 요약과 다음 상세는 계속 인접한 DOM section이다. 조언 scroller에만 wheel listener를 설치한다. 초기값은24 CSS px 누적과200ms 무입력 간격이며, 특정 물리 trackpad에서 측정한 최적값이 아니다. pixel/line/page 단위를 정규화하고 한 제스처의 전환 후 관성 입력을 소비한다. 상세 시작에서 새 위 입력을 받으면 요약으로 돌아간다. 가로 우세 입력·ctrl wheel/pinch·입력/선택·팝업·중첩 스크롤은 가로채지 않는다.

긴 모바일 요약과 긴 상세는 먼저 일반 스크롤로 읽는다. 경계에 도달한 제스처의 남은 관성으로 즉시 다음 화면에 넘어가지 않고 새 입력을 받는다. CTA와 PageDown/PageUp도 같은 이동 함수를 사용한다. 목표는 실제 heading과 기존 scroll-margin112px로 매번 계산하고 resize 때 현재 정렬을 갱신한다. 해제 시 listener·관측 frame·ResizeObserver를 정리한다. CSS/데이터/API 경로는 바꾸지 않았다.

reduced-motion에서는 긴 애니메이션 없이 이동한다. 첫 최종 검사에서 위치는 도착했지만 RAF가 아직 상태를 정리하지 않은 순간 다음 PageUp을 무시하는 경합을 발견했다. 다음 입력 시 실제 도착 위치를 확인해 완료 상태를 정리하는 최소 교정과 frame flush 없는 회귀를 추가했다. 테스트에 임의 대기시간을 넣어 숨기지 않았다.

실제 Chromium에서32px wheel,8px×4입력, 역방향 복귀, 관성 no-bounce, resize, CTA/키보드, reduced-motion, 빈 결과, 긴 본문·modal 내부 스크롤과 Escape/focus 복귀가 PASS다. deltaMode·정확한 경계·noise·cleanup 등15개 합성 검사는 실제 입력 결과와 별개다. 물리 trackpad/OS 배율/브라우저 zoom은 측정하지 않았다.

[요약 화면](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/after-1920-1080-advice.png) · [상세 화면](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/after-1920-1080-advice-detail.png) · [일반 이동](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/paging-no-preference.json) · [줄인 동작](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/paging-reduce.json) · [긴 본문/팝업](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/paging-content.json).

## 최종 검증과 실패 이력

| 검사 | 결과 |
|---|---|
| OAuth FE | 267 PASS |
| remote demo FE | 245 PASS |
| local demo FE | 61 PASS |
| 합계 | 573 PASS; 기존552 + 신규21, 실패/skip/todo0 |
| 제품/test/E2E/Functions TypeScript | 4종 PASS |
| OAuth/remote demo/local demo build | 3종 PASS |
| invalid mode | 5종 PASS |
| lint | 0 errors / 0 warnings |
| 실제 Chromium 전체 | 16/16 PASS; 실패/skip/global error0·exit0 |
| 6페이지·즉시 재집계·reload/reset/end | PASS |
| API·외부·page error | 모두0 |
| 소유 검증 자원 정리 | PASS |
| strict scanner·링크·파일 보존 | PASS — 미해결0·새 링크28개 정상·기존 파일 보존 |

[FE](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/fe-summary.json) · [브라우저](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/browser-summary.json) · [여정](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/journey.json) · [요청 경계](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/network-summary.json). 전체 BE·실제 공급자·공개 사이트는 실행하지 않았다. 이번 변경과 무관한 기존 서버 검증을 새 PASS로 표시하지 않는다. 기존 테스트의 원본 픽셀 최소치/종이 고정2:3비율/절대 금액 조기 포화 assertion만 이번 요구의 viewport fit/상대 비율로 교체했다. 데이터·인증·초기화 assertion을 약화하지 않았다.

첫 신규 타입 준비 실패, 빠른 키 입력 관측 실패, compositor 완료 전 modal 관측 실패, 실제 reduced-motion 경합과 교정을 [실행 이력](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/iterations.json)에 분리해 보존했다. 실패 실행의 global error도0으로 다시 쓰지 않았다. 조언 집중3개 PASS 후 최종 고정 source 전체16개를 다시 통과했다.

재현은 기존 `local_demo_checks.py --phase fe`와 `--phase browser`에 검토한 lockfile 의존성·별도 output·설치된 Chromium을 명시한다. 실제 env 로딩·의존성 설치 없이 임시 사본과 소유 loopback만 사용한다. 원본 이미지·패키지/lockfile·scanner 규칙/분류는 변경하지 않았다.

## 저장 범위·미리보기·판정

제품은 LeakPotPage 표현/CSS·potLeakAnchors·ToadAdvice 표현과 새 adviceSectionPaging이다. 관련 단위·브라우저 검증과 해당 runner만 추가/수정했다. 랜딩·Chart·local 시나리오/집계·인증·BE·provider·README·25~27보고서/evidence는 보존한다. [변경 범위](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/implementation-delta.json), [보존](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/preservation.json), [공개 검사](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/scanner.json), [링크](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/links.json).

새 미리보기: [http://127.0.0.1:50164/](http://127.0.0.1:50164/). FE의 `npm run dev:demo-local`을 별도 strict loopback 포트로 실행했다. 최종220개 FE파일과 제공 사본의 내용·mode를 대조하며 사용자용 서버만 의도적으로 유지한다. 검증용 개발 서버와 브라우저는 종료했고 기존 다른 미리보기는 건드리지 않았다. 종료를 요청하면 이번 소유 서버만 정리한다. [미리보기 확인](evidence/POT_VIEWPORT_AND_ADVICE_PAGING/preview.json).

```text
POT_SINGLE_VIEWPORT_LOCAL_READY=true
LEAK_SLIDER_RESPONSE_LOCAL_READY=true
ADVICE_SECTION_PAGING_LOCAL_READY=true
USER_VISUAL_APPROVAL=PENDING
FRONTEND_ONLY_DEMO_PUBLIC_READY=false
CURRENT_PUBLIC_DEPLOYMENT_UNCHANGED=true
```

7개 viewport와 screenshot의 로컬 검증은 사용자32인치 실제 시각 승인을 대신하지 않는다. 공개 URL·Render·TiDB·Upstash 접속0, stage/commit/push/배포0이며 현재 production은 이전 제품 그대로다.
