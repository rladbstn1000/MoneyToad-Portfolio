# 30. 장독대 하단 구도·상단 여백 재조정과 원래 글꼴 조사

2026-10-06. 25~29단계 미커밋 작업을 보존했다. 시작 HEAD는 `f97bb5f5ba5e04d1f80666791f8986b3f8c236ac`이며 이번 제품 변경은 **demo/local 장독대 CSS**에 한정된다. 하단 구도·상단 여백은 구현/검증 PASS, 원본 글꼴은 공식 원본과 이용조건 확인까지 완료했으나 **현재 scanner의 모든 폰트 금지 규칙과 충돌하여 적용 보류**다. 기존 공개 서비스는 변경하지 않았다.

## 하단 구도와 실제 가시 바닥

29단계는 500×700 SVG 프레임 전체를 화면 안에 놓았다. 항아리 바닥은 내부 y=480이므로, wrapper 하단 정렬만으로는 대부분 화면에서12~17px만 내려갔다. 이번에는 같은 viewport에서 원본 이미지 alpha>0 픽셀을 직접 읽어 콩쥐 발끝·두꺼비 바닥·항아리 바닥을 비교했다. screenshot 해상도에서 viewport를 추정하지 않았다.

공통 stage는 하단 정렬을 유지하고, local 프레임의 마지막25개 투명 좌표 단위만 제외했다. SVG 자체500×700, 구멍 anchor·물 시작점·local 초과비율 매핑·자산 bytes는 그대로다. 캐릭터 바닥 여백을 동일한 y=480 기준으로 맞췄다. 고정 translateY나 큰 margin으로 그림을 화면 밖으로 밀지 않았다.

상단 여백 확대와 최대 물 효과 영역을 함께 확보하기 위해 장면의 높이 기반 폭 제한을130cqh→120cqh, 큰 화면 상한을1600→1440px로 조정했다. 캐릭터 상한도 같은10% 비율로 맞췄다. 좁은 화면은 기존130cqh 제한을 유지한다. CSS gap16px와 좌우 순서는 보존하지만 공통 축소/중앙 정렬에 따른 가로 위치 변화는 있다. 요청하지 않은 크기 변화가 없다고 주장하지 않는다.

| CSS viewport, DPR1 | 실제 항아리 바닥 하강 | 최종 그림 배율(이전 대비) | 실제 메뉴→안내 / 안내→월 / 아이콘→stage |
|---|---:|---:|---|
| 390×844 | 29.72px | 94.94% | 10 / 8 / 12px |
| 768×1024 | 32.01px | 96.13% | 10 / 8 / 12px |
| 1440×1000 | 60.00px | 84.50% | 30 / 20 / 36px |
| 1920×1080 | 67.16px | 84.40% | 30 / 20 / 36px |
| 1920×900 | 56.97px | 82.29% | 30 / 20 / 36px |
| 2560×1440 | 87.55px | 86.75% | 30 / 20 / 36px |
| 3840×2160 | 77.23px | 89.90% | 30 / 20 / 36px |

콩쥐·두꺼비의 실제 alpha 바닥도 desktop56.85~87.41px 내려간다. 이미지별 투명 테두리로1px 안팎 차이가 있으며 전체 비교에는 각 x/y/크기/배율을 보존했다. 1440~2560에서는 약6vh 하강이고, 3840에서는 약3.6vh다. 4K에서6~10vh를 강제하려면 더 큰 축소가 필요하므로 현재10% 상한 축소와77px 하강을 택했다. 사용자 시각 승인을 대신하는 수치는 아니다.

두루마리는 그림과 묶어 이동하지 않았다. 기존 독립된 가용 높이/중앙 정렬 규칙을 보존했으며 상단 공간 증가로 desktop y가36px 달라진다. 제목·합계·slider safe area·목록 내부 스크롤·마지막 항목 접근은 유지했다.

[실제 alpha 위치 비교](evidence/POT_GROUNDING_AND_SPACING/geometry-comparison.json) · [수정 전1920](evidence/POT_GROUNDING_AND_SPACING/before-1920-1080-pot.png) · [수정 후1920](evidence/POT_GROUNDING_AND_SPACING/after-1920-1080-pot.png) · [390](evidence/POT_GROUNDING_AND_SPACING/after-390-844-pot.png).

## 투명 여백과 최대 물 효과

기존5/12/20 대표 프레임보다19번 프레임이 더 아래까지 그려졌다. 설치된 실제 Lottie를 모든29개 정수 프레임에서 rasterize하고 alpha>0 픽셀을 실제 CTM으로 변환했다. 현재 local에서 편집 가능한6개 예산의 최대 물 효과는 y=665.94다.675 프레임은9 SVG unit 이상 여유를 남긴다. 나머지 id=null 카테고리는 예산을 생성하거나 편집하지 않는다.

12개 전체 카테고리 합성 fixture의 최대는 y=688.89이므로 그 fixture와 remote/OAuth는 기존700 프레임을 유지한다. **675는 현재 local6개 편집 계약에 근거한 표현값**이며 향후 편집 대상/애니메이션 변경 시 재검증해야 한다. 원본 SVG viewBox나 물 크기를 바꿔 맞춘 값이 아니다. 정수 프레임 사이 보간의 모든 실수 시점을 전수 검사했다고 주장하지 않는다.

외부 html/body/root/page/main/stage 초과0을 유지한다. 기존의 전체SVG rectangle 포함 assertion만 local675 프레임 포함으로 교체하고, 물의 실제 alpha 경계가 viewport·CSS clip 안에 있는지, 캐릭터/패널과 겹치지 않는지 추가했다. 물웅덩이 ellipse의120% filter 영역도 검사한다. 기본→정상→최대 누수 전환에서 캐릭터 공통 바닥/중심과 항아리 위치가 변하지 않는지 확인한다. 투명영역을 제외하는 `overflow:clip`을 실제 물 잘림의 근거로 대신 사용하지 않았다.

[29프레임 범위](evidence/POT_GROUNDING_AND_SPACING/water-envelope-29-frames.json) · [낮은 화면의 최대 누수](evidence/POT_GROUNDING_AND_SPACING/after-1920-900-pot-max.png). 재현 가능한 기존 fixture 버튼과 비교 회귀에19번 프레임을 추가했고 alpha 기준도16 초과에서0 초과로 강화했다.

## 상단 세 영역

메뉴/안내/월은 기존 auto grid 행을 사용한다. 실제 header 높이 뒤24px, 안내 뒤20px, 월 뒤28px+main padding8px를 둔다. 메뉴 버튼 하단과 header 하단의6px를 포함하면 실제 메뉴→안내는30px다. desktop main 시작은267px로 바뀌며 이 값 자체를 하드코딩하지 않는다. 모바일은 header→안내6px, 실제 메뉴→안내10px, 이후8/12px로 줄여 목록 가용 높이와 한 화면 배치를 우선한다.

갈색·상아색·금빛 안내띠와 기존 글자16px/mobile15px, 월 아이콘/label 크기는29단계 그대로다. desktop12개월 한 줄/mobile6개씩 두 줄, 최소44px 조작 영역, hover/focus/선택 시 행 높이 불변을 확인했다. 큰 카드나 추가 이미지/아이콘/폰트 라이브러리는 없다. [실제 메뉴 하단 관측](evidence/POT_GROUNDING_AND_SPACING/actual-menu-spacing.json)에서 header 박스와 구분했다.

## 원래 글꼴 — 원본 확보 PASS, 제품 적용 보류

원본 공개 기준선 `ade12429242ff3653f4050bec14ce373ac666832`의 index.css는 `Joseon100Years` 별칭으로 ChosunCentennial 제3자 WOFF2를 global selector에 적용한다. Landing/LeakPot에도 명시하고, Mypage에는 시스템/monospace 별도 선언이 있었다. 이번에는 과거 CDN을 앱에 다시 연결하지 않았다.

[공식 안내](https://event.chosun.com/100/100font.html)에서 직접 연결한 [공식 TTF 배포](https://fontdown.chosun.com/100/ChosunCentennial_ttf.zip)를 확인했다. 권리자는 방일영문화재단이다. 개인·기업 무료 사용과 자유로운 재배포가 가능하며 복사/배포 대가 청구와 수정 판매를 금지하고 원형 사용을 요구한다. 별도 LICENSE 파일은 없지만 TTF 내부 nameID13에 같은 조건이 포함되어 있다.

- 원본 파일: `ChosunCentennial_ttf.ttf`, TrueType, 5,120,116 bytes, Regular400/normal, version1.020.
- SHA-256: `d620604fbf9eab138612753c9317162aa7a748f97ca53ea61d2860a9a727ba1f`.
- 변환·서브셋·glyph/내부 이름 수정0. 원본은 저장소 밖에 보존했다.
- 내부OS/2 fsType4도 관측했다. 공식 페이지의 이용/배포 안내와 내부 조건을 기록했으며 이를 별도 권리자의 추가 보장으로 확대하지 않는다.

현재 `public_scan.py`의 BAD_SUFFIXES는 `.ttf/.otf/.woff/.woff2`를 무조건 excluded-artifact로 판정한다. 기존 SourceReviews는 source 문법 검토 기능이며 binary 자산 등록 기능은 없다. 따라서 “폰트 포함”과 “scanner 규칙 변경 금지”를 동시에 만족할 수 없다. 확인된 원본1개만 정확한 경로/checksum으로 등록하는 방안의 승인을 요청했지만, **현재 승인 없이 규칙을 바꾸지 않고 폰트 포함을 보류**했다. 확장자 변경·base64·검사 제외로 우회하지 않았다.

이에 local6화면과 remote/OAuth의 기존 시스템 fallback을 유지했다. 원래 글꼴의 실제 응답/FontFace/렌더링 및 지연·실패 검증은 **NOT_RUN**이며 복원 PASS가 아니다. 이번7viewport 회귀는 현재 fallback 렌더링의 근거다. 새폰트/runtime CDN 요청0이다. 공식 안내/다운로드 준비 요청만 별도로 허용 범위에서 수행했고 앱/공급자/공개서비스에는 연결하지 않았다.

[폰트 출처·조건·checksum](evidence/POT_GROUNDING_AND_SPACING/font-source-review.json) · [적용 보류 상태](evidence/POT_GROUNDING_AND_SPACING/font-integration.json).

## 최종 로컬 검증

| 검사 | 결과 |
|---|---|
| 전체 FE | 기존573 유지: OAuth267 + remote245 + local61, 모두PASS |
| failure/error/skip/todo | 0 |
| 제품/test/E2E/Functions 타입 | 4종PASS |
| OAuth/remote demo/local demo build | 3종PASS |
| invalid mode | 5종PASS |
| lint | 0errors / 0warnings |
| Chromium | 기존16case 모두PASS, retry0/globalerror0/exit0 |
| 7viewport | 외부XY0, 읽기/조작/내부목록·최대누수·6페이지PASS |
| API/외부 runtime/page error | 0 |
| 소유 검증 자원 정리 | PASS |
| strict scanner/링크/보존 | PASS — 미해결0 / 새 링크18개 / 기존파일 보존 |
| 원본 폰트 제품 적용 | BLOCKED — 검사 규칙 충돌, 현재 fallback 유지 |

[FE](evidence/POT_GROUNDING_AND_SPACING/fe-summary.json) · [Chromium](evidence/POT_GROUNDING_AND_SPACING/browser-summary.json) · [case목록](evidence/POT_GROUNDING_AND_SPACING/tests.json) · [요청 경계](evidence/POT_GROUNDING_AND_SPACING/network-summary.json).

기존 `local_demo_checks.py --phase fe`/`--phase browser`와 명시적 lockfile 의존성·설치된 Chromium·새 output을 사용했다. 최대 프레임/가시 범위·고정 바닥·상단 간격 assertion을 보강했다. case 삭제·skip·데이터/초기화/인증 assertion 완화0. 최종 소스 기준 입력 보존과 소유 자원 정리를 확인했다. 초기 실행도PASS였고 추가19프레임 검사를 최종 소스로 다시 실행했다. 첫 브라우저 기동 요청은 승인 검토 서비스의 용량 오류로 실행되지 않았으며 정식 재승인 후 진행했다.

## 미리보기와 보존

새 frontend-only 미리보기: [http://127.0.0.1:55421/](http://127.0.0.1:55421/). 최종 FE source와 회귀/미리보기 사본의 bytes 및mode를 대조한다. 개발용 서버는 소유권을 확인해 종료했고 이 사용자 미리보기만 의도적으로 유지했다. 기존 서버는 종료하지 않았다. 실제 사용자모니터·OS배율·브라우저zoom은 측정하지 않았으며 시각 승인은PENDING이다.

제품 변경은 LeakPotPage.css의 local범위이고 관련 e2e/fixture 검사, 새보고서/evidence/STATUS만 추가한다. data·이미지·font·auth·backend·provider·패키지·README·25~29보고서/evidence·HEAD/index를 보존한다. stage/commit/push/공개접속/배포0.

[보존·사본 일치](evidence/POT_GROUNDING_AND_SPACING/preservation.json) · [미리보기 관측](evidence/POT_GROUNDING_AND_SPACING/preview.json) · [공개 검사](evidence/POT_GROUNDING_AND_SPACING/scanner.json) · [링크 검사](evidence/POT_GROUNDING_AND_SPACING/links.json).

```text
POT_GROUNDING_LOCAL_READY=true
POT_TOP_SPACING_LOCAL_READY=true
ORIGINAL_FONT_LOCAL_READY=false
FONT_RESTORATION_LOCAL_READY=false
USER_VISUAL_APPROVAL=PENDING
FRONTEND_ONLY_DEMO_PUBLIC_READY=false
CURRENT_PUBLIC_DEPLOYMENT_UNCHANGED=true
```

다음 재개 지점은 검증된 원본1개의 엄격한 자산 등록 허용 여부다. 허용되는 경우에만 local6화면 font token과실제 로딩/실패 회귀를 적용한다. 원본 폰트 복원까지 완료했다고 표시하지 않는다.
