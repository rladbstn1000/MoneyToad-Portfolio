# 프런트엔드 전용 돈꺼비 — 공개 전체 검증 완료

2026-10-07. [현재 공개 체험](https://moneytoad-portfolio.pages.dev/)은 Cloudflare 정적 파일과 브라우저 메모리·계산으로 동작한다. 별도 로그인·백엔드 준비·데이터 API 대기 없이 직접 작성한 샘플 거래와 예산으로 6개 화면을 체험한다. 파일 다운로드와 표시 시간은 접속 환경에 따라 달라진다.

```text
USER_VISUAL_APPROVAL=APPROVED
FRONTEND_ONLY_DEMO_PUBLIC_READY=true
PUBLIC_DEPLOYMENT_READY=true
ORIGINAL_FONT_LOCAL_READY=false
NON_BROWSER_403_CAUSE=UNKNOWN
HISTORICAL_HTTP_ERROR_CAUSE=UNKNOWN
```

## 고정 기준

| 구분 | 값 |
| --- | --- |
| 배포 제품 SHA | `050b65b9517dfedbb0c231cfd979eb75936a2697` |
| 최종 검증기 revision | `mypage38-readiness-full-eight` |
| 검증기 source manifest SHA-256 | `3b8de3b1f08ea255495d0f4a3b51a41659d2a6dbb2fe63435d2bb7a3f104636f` |
| 로컬 실행 manifest SHA-256 | `8038dd61f49baf38b777fd8327aeb4904541d2aec81bf2ac97fe31ad4fc19849` |
| 공개 실행 manifest SHA-256 | `a5d1e1a3dec85deb1f6c6c1096604078d526ce832e99886be01e409d0bf774b2` |
| 배포 artifact manifest SHA-256 | `d8e7bb7e6198c8ca6cf4f754a85298d233ea9c81990781358698eb71171c5bcc` |

문서 커밋 SHA와 위 배포 제품 SHA는 다를 수 있다. 이번 문서 마무리에서 제품·배포 설정을 수정하거나 재빌드·공개 요청·재배포를 하지 않았다. 원래 시스템 글꼴 제품을 유지하며, 원래 글꼴 적용은 보류다.

## 현재 체험과 검증 범위

마당 → 장독대 누수·예산 조절 → 씀씀이 거래 재분류 → 두꺼비의 조언 → 곳간·정보 입력 → 초기화/종료를 확인했다. 예산·거래 변경과 재집계는 현재 탭의 메모리에서 처리한다. 화면 이동에는 유지되고, 새로고침·처음부터 다시하기·체험 종료에는 초기화된다. 새 탭은 독립적이다.

조언은 현재 샘플 수치와 준비된 분석 문구이며 실시간 AI 호출이 아니다. 곳간·정보 입력은 샘플 인물·카드 선택만 제공하며 실제 개인정보·카드번호·CVC를 입력하거나 저장하지 않는다. 현재 공개 체험은 서버 API를 사용하지 않는다. Spring Boot·MySQL·Redis 기반 인증·실제 DB 수정·세션 복원은 [별도 서버 연동 모드의 과거 공개 검증](public-full-experience-verified.md)으로 보존한다.

최종 검증기는 소유 loopback에서 원래 배포 58파일로 전체 8개를 한 번 PASS한 뒤, 같은 고정 소스로 공개 전체 8개를 **한 번** 실행했다. 공개 자동 retry·추가 집중 진단·실패 후 재시작은 0이다. 사례 구성은 전체 체험, 네 화면 크기의 geometry, 일반/줄인모션 조언 전환, 직접 경로/reload다. 화면 크기는 **390×844·768×1024·1440×1000·1920×1080**이다.

| 이번 공개 실행 항목 | 결과 |
| --- | --- |
| 전체 suite | **8/8 PASS** |
| 서빙 파일 | **57/57** 완료된 body·MIME·크기·SHA 일치 |
| 랜딩 준비 | **20/20 PASS** |
| 장독대 준비·geometry | **28/28**, **12회 × 기존 105조건 PASS** |
| 곳간 단계 준비 | **37/37 PASS** — CLOSED9·OPEN7·SITTING14·PAPER7 |
| 곳간 이미지 확인 | **44건 PASS** — 현재 요소 decode·소스 동일성·표시 확인 |
| 정적 GET | 자연 **355** + 마지막 미관측 파일 감사 **18** = **373** |
| 요청 실패·필수 오류·미관측·pending | **0** |
| API·외부·금지·WebSocket·예산 초과 | **0** |
| 실행 종료 | onEnd **passed**, 실제 process exit **0**, cleanup **PASS** |

업로드 58개 중 `_redirects`는 배포 설정이므로 HTTP 서빙57파일과 구분한다. 자연 응답을 재사용하고 미관측 파일 감사는 마지막 사례에서만 각1회 수행했다. HTTP200만으로 수신 성공을 대신하지 않았으며, 공개 open.webp 7개 요청도 모두 완료했다. 곳간의 문 열림·앉은 장면은 스크린샷으로 별도 확인했다. PAPER에서는 sitting 배경과 별도 종이 이미지를 검사했다.

## 과거 근거와 한계

- [31단계 보고서](31-static-demo-public-release.md)와 [당시 정제 결과](evidence/STATIC_DEMO_PUBLIC_RELEASE/result.json)는 배포 성공·최초 정적 HTTP 검증 중단 당시의 기록으로 원문 그대로 보존한다. 그 문서의 main·production·FAIL·미실행·다음 작업 설명은 당시 시점이며, 최신 공개 결과는 이 요약을 따른다.
- FE573·타입4종·build3종·lint0/0·로컬 Chromium16은 [30단계의 이전 근거](30-pot-grounding-spacing-and-original-font.md)다. 최종 공개 실행이나 이번 문서 작업에서 다시 실행한 결과가 아니다. 서로 다른 시점의 테스트 수를 합산하지 않는다.
- 최종 공개 결과의 비노출 검사는 정제 결과 파일·산출물 이름·선별 화면 범위였으며, 저장소 전체 strict scanner와 구분한다. 이번 문서 공개 검사도 사용자 여정을 다시 실행하는 검증이 아니다.
- 이번 PASS는 각 중간 장면의 준비·표시를 확인하며 진행한 정상 여정에 한정한다. 모든 빠른 연속 클릭이나 이미지 취소의 안전성을 보장하지 않는다. 과거 ERR_ABORTED는 화면 전환 중 취소 가능성으로 좁혀졌으나 직접 원인·무해성은 미확정이다. 과거 FAIL을 재판정하지 않는다.
- `NON_BROWSER_403_CAUSE=UNKNOWN`, `HISTORICAL_HTTP_ERROR_CAUSE=UNKNOWN`을 유지한다. 과거 서버 cold-start·공급자 장애전환의 미확인 보장도 승격하지 않는다.

private 실행기·승인 파일·실행 marker·원문 요청/응답·로그·HAR·trace·storageState는 이 공개 요약에 포함하지 않았다. 관련 원본은 별도로 보존하며, 함께 공개하지 않은 파일로 링크하지 않는다.
