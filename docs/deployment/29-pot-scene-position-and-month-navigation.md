# 29. 장독대 그림 하단 배치와 안내·월 표시 가독성

2026-10-06. 28단계까지의 미커밋 코드와 과거 근거를 보존하고 **demo/local 장독대 CSS만** 조정했다. 시작 HEAD는 `f97bb5f5ba5e04d1f80666791f8986b3f8c236ac`다. 사용자 화면의 변경된 예산/누수는 유효한 체험 상태이며 seed 결함으로 취급하지 않았다. 아래 비교는 별도 fresh local context의 동일한 기본 월/예산에서 측정했다.

## 그림 묶음만 하단 정렬

실제28단계 미리보기와 수정본을 같은7개 CSS viewport/DPR1에서 비교했다. 그림과 누수 효과를 담은 공통 `.pot-visualization`을 기존 stage의 남은 공간 안에서 아래 정렬했다. 개별 그림의 좌표·SVG500×700·구멍 anchor·local 누수 배율은 변경하지 않았다. 고정 translateY/margin-top이나 전체 scale을 사용하지 않았다.

상단 메뉴의 기존 세로 padding을 안내띠와 월 label의 실제 행 공간에 재배분했다. root의 `auto auto auto minmax(0,1fr)`을 유지하므로 가용 높이는 실제 레이아웃으로 결정된다. main의 시작은 관측상 desktop195px/mobile197px로 유지됐으며 그 숫자를 CSS에서 빼도록 하드코딩하지 않았다.

| CSS viewport | 그림 공통 하강(px) | 그림 x/폭/높이 | 두루마리 x/y/폭/높이 | 외부 초과 X/Y |
|---|---:|---|---|---|
| 390×844 | 8.00 | 동일 | 동일 | 0 / 0 |
| 768×1024 | 8.56 | 동일 | 동일 | 0 / 0 |
| 1440×1000 | 16.91 | 동일 | 동일 | 0 / 0 |
| 1920×1080 | 13.48 | 동일 | 동일 | 0 / 0 |
| 1920×900 | 11.94 | 동일 | 동일 | 0 / 0 |
| 2560×1440 | 16.59 | 동일 | 동일 | 0 / 0 |
| 3840×2160 | 373.75 | 동일 | 동일 | 0 / 0 |

SVG 부동소수 표시의 최대0.00006px 차이 외에 그림 크기·가로 위치는 동일하다. 세 그림과 SVG 프레임의 세로 이동량이 같고, panel/title/list/summary의 사각형은 보존됐다. 3840화면은 기존1600px 장면 폭 상한 때문에 세로 잔여 공간이 컸다. 작은 높이에서는11.94px 등 가능한 만큼만 이동해 최대 효과 여유를 우선했다.

단순히 wrapper 정렬만 검사하지 않았다. `#pot-body image`·콩쥐·두꺼비의 실제 표시 rectangle과 바닥 좌표를 함께 비교했고, 같은 자산/배율이므로 불투명 영역 역시 같은 양만큼 이동한다. 정상/누수 상태 비겹침과 최대6개 실제 예산 누수, 기존12개 합성 효과의 대표 프레임 검사를 재사용했다. SVG의 투명 여백까지 viewport 안에 두며 물을 숨겨서 맞추지 않는다. 이미지 alpha 윤곽이나 모든 애니메이션 프레임을 새로 전수 분석한 것은 아니다.

[위치·크기 비교](evidence/POT_VISUAL_POLISH/geometry-comparison.json) · [수정 전1920](evidence/POT_VISUAL_POLISH/before-1920-1080-pot.png) · [수정 후1920](evidence/POT_VISUAL_POLISH/after-1920-1080-pot.png) · [낮은 화면의 최대 누수](evidence/POT_VISUAL_POLISH/after-1920-900-pot-max.png).

## 안내띠와 월 표시

문구는 “샘플 소비와 기준 예산으로 누수를 살펴보세요.” 그대로다. 내용 길이에 맞춰 중앙 정렬하고 반투명 짙은 갈색·상아색 글자·1px 은은한 금빛 테두리·6px 모서리로 표현했다. 기존 폰트를 사용하며 desktop16px/line-height24px, mobile15px/21px다. 장면 위 absolute 배너나 이미지/폰트 추가가 아니다.

| 대상 | 28단계 | 이번 수정 |
|---|---:|---:|
| desktop 일반 아이콘 높이 | 51.83px | 59.75px, 약15.3% 확대 |
| desktop 선택 아이콘 높이 | 72px | 83px, 약15.3% 확대 |
| desktop 월 글자 | 14px | 16px |
| mobile 일반 아이콘 높이 | 34.55px | 40px, 약15.8% 확대 |
| mobile 선택 아이콘 높이 | 48px | 48px 유지 |
| mobile 월 글자 | 12px | 13px |

모바일 선택 아이콘은 한 화면 배치와 큰 상태의 구분을 위해 기존48px을 유지했다. 월 label을 실제 별도 grid 행에 두어 아이콘과 겹치지 않는다. desktop12개월 한 줄/mobile6개씩 두 줄, 최소44px 조작 영역을 유지한다. hover/focus/5월 선택/10월 복귀 때 버튼 사각형·행 높이 불변, label/icon 버튼 내 표시, 작년·누수색상/선택 구분을 확인했다. nav 최대폭은 기존 값을 유지했다.

[390 화면](evidence/POT_VISUAL_POLISH/after-390-844-pot.png) · [390 측정](evidence/POT_VISUAL_POLISH/layout-390-844.json) · [1920 측정](evidence/POT_VISUAL_POLISH/layout-1920-1080.json). 새 배치에서도 메뉴/안내/월이 겹치지 않고, 목록만 내부 스크롤하며 마지막 항목 접근·제목/합계 고정·외부 wheel/PageDown/포커스 이동0을 유지했다.

## 최종 로컬 검증

| 검사 | 이번 결과 |
|---|---|
| 전체 FE | **573 PASS** = OAuth267 + remote245 + local61; 기존573 유지 |
| failure / error / skip / todo | 0 |
| 제품·test·E2E·Functions 타입 검사 | 4종 PASS |
| OAuth·remote demo·local demo build | 3종 PASS |
| invalid mode | 5종 PASS |
| lint | 0 errors / 0 warnings |
| 실제 Chromium | **16/16 PASS**, global error0·exit0·retry0 |
| API·외부·page error | 0 |
| 소유 검증 자원 정리 | PASS |
| strict scanner·링크·보존 검사 | PASS — 미해결0·새 링크16개 정상·기존 파일 보존 |

[FE 결과](evidence/POT_VISUAL_POLISH/fe-summary.json) · [브라우저 결과](evidence/POT_VISUAL_POLISH/browser-summary.json) · [전체 case](evidence/POT_VISUAL_POLISH/tests.json) · [요청 경계](evidence/POT_VISUAL_POLISH/network-summary.json).

기존7viewport browser case에 안내·월 표시 크기/겹침/hover/선택 안정성 검사를 **추가**했고 case 삭제·skip·기존 assertion 약화는 없다. 신규 단위 case를 불필요하게 늘리지 않았으며573이라는 수만으로 새 시각 요구를 증명하지 않는다. 6페이지·즉시 재집계·초기화·슬라이더 전 구간·조언 구간 이동과 긴 본문/팝업 계약은 기존 실제 Chromium 여정으로 재검증했다. 이번 전체 FE·브라우저 실행의 실패0이며 준비 중83px 대신82.8px을 검토했던 중간CSS는 최종 기대값/근거로 사용하지 않았다.

재현은 기존 `local_demo_checks.py --phase fe` 및 `--phase browser`에 명시적 lockfile 의존성·설치된 Chromium·새 output을 지정했다. 의존성 설치·실제 env 로딩·BE 기동·공급자/공개사이트 연결0이다. runner와 패키지/lockfile/scanner 규칙·분류는 변경하지 않았다.

## 저장 범위와 미리보기

제품 변경은 `fe/src/pages/LeakPotPage.css`의 `.local-pot-page` 범위뿐이다. `fe/e2e/local-demo.spec.ts`에 검증을 보강하고 본 보고서/새 근거/STATUS 최신 항목을 추가했다. 나머지 제품·테스트·설정·이미지 자산·README·25~28보고서/evidence·HEAD/index는 보존했다. 두루마리 데이터·금액·누수 매핑·조언 코드·다른 페이지·서버 계약은 변경0이다.

새 미리보기: [http://127.0.0.1:52658/](http://127.0.0.1:52658/). 별도 소유 사본에서 `npm run dev:demo-local`을 strict loopback 포트로 실행했다. 최종FE220파일은 실제 회귀 사본·사용자 미리보기와 내용/mode가 일치한다. 개발용 임시 서버는 종료하고 이 사용자 미리보기만 의도적으로 유지했다. 기존 서버를 종료하지 않았다.

[보존 검사](evidence/POT_VISUAL_POLISH/preservation.json) · [미리보기](evidence/POT_VISUAL_POLISH/preview.json) · [공개 검사](evidence/POT_VISUAL_POLISH/scanner.json) · [링크 검사](evidence/POT_VISUAL_POLISH/links.json).

```text
POT_VISUAL_POLISH_LOCAL_READY=true
USER_VISUAL_APPROVAL=PENDING
FRONTEND_ONLY_DEMO_PUBLIC_READY=false
CURRENT_PUBLIC_DEPLOYMENT_UNCHANGED=true
```

실제 사용자32인치 모니터·OS배율·브라우저zoom은 측정하지 않았으며 screenshot 해상도를 사용자CSS viewport로 가정하지 않는다. 사용자의 시각 승인은 대기 중이다. stage/commit/push/공개접속/배포/provider 변경0.
