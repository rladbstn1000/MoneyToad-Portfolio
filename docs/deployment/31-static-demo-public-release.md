# 31. 승인된 정적 체험판 배포 — 공개 검증 중단

2026-10-06. 25~30단계의 시스템 글꼴 디자인을 사용자가 승인했다. 누적 변경을 감사하고 커밋·일반 fast-forward push 및 기존 Cloudflare Pages 정적 배포까지 수행했다. **배포 자체는 성공했으나, 첫 공개 정적 파일 대조가 실패해 공개 준비 완료로 표시하지 않는다.** README의 현재 체험 안내는 아직 바꾸지 않았다.

## 확인된 상태

- 제품 소스 및 GitHub main: `050b65b9517dfedbb0c231cfd979eb75936a2697`.
- 마지막으로 확인한 production: `b9d82297-67c3-4d44-94c2-79bcdf6e2075`, Cloudflare 상태 success, source SHA 일치.
- 직전 정상 production 복귀 대상: `93044e65-2456-4356-9112-04d0af2f65d1`, 제품 `d020d837f2830125696d2038eb4a4c969864432e`.
- 공개 주소: [돈꺼비](https://moneytoad-portfolio.pages.dev/). 현재 새 정적 배포가 production이며 이전 배포로 돌아갔다고 표시하지 않는다.
- 기존 프로젝트·main·production/preview 자동 배포 OFF를 확인했고 정책을 변경하지 않았다. 새 서비스 생성이나 Render·TiDB·Upstash 접속/변경은 없다.

## 소스·빌드·배포 경계

승인된 30단계 사본과 저장소 1,262개 파일의 bytes·mode·경로, 사용자 미리보기 FE220파일이 일치했다. 누적 변경281개만 명시적으로 stage했다. stage canonical 검사와 working/committed source strict scanner가 PASS였으며 규칙·허용 범위를 변경하지 않았다.

커밋된 소스에서 `npm run build:demo-local`을 실행했다. demo/local이 명시된 설정의 envDir:false로 실제 환경파일과 서버 주소 주입을 차단했다. 정적58파일/6,389,613bytes, SPA fallback을 포함하고 Functions·Worker·API proxy·검증 fixture·원본 폰트는 포함하지 않았다. 산출물 manifest SHA-256은 `d8e7bb7e6198c8ca6cf4f754a85298d233ea9c81990781358698eb71171c5bcc`다.

설치된 Wrangler4.146.0의 실제 옵션과 [공식 Git 연동 수동 배포 안내](https://developers.cloudflare.com/pages/configuration/git-integration/)를 확인했다. 별도 정적 폴더와 설정이 없는 상위 경로에서 source SHA·project·main을 명시해 배포1회만 실행했다. CLI는 정적파일3개 업로드/기존54개 재사용 및 redirect 설정 업로드·배포 완료를 보고했고 exit0이었다. Function/Worker compile/upload 메시지는 없었으며, 새 deployment API의 `uses_functions=false`를 함께 확인했다. 브라우저 API0만으로 미포함을 추정한 것이 아니다.

## 실패와 미실행

실제 서빙 파일57개를 checksum 대조하려는 첫 GET `/404.webp`에서 Python `HTTPError`가 발생했다. 공개 파일 bytes/size 일치는 **미확인**이다. 이 관측기는 예외 class만 저장했으므로 상태 코드·응답 본문·응답 header는 근거에 남아 있지 않다. 특정 HTTP 코드나 서버 원인으로 추정하지 않는다.

지시서의 불명확한 검증 실패 중단 규칙에 따라 추가 공개 요청·브라우저 전체 여정·제품/기대값 수정·재배포를 수행하지 않았다. 주요 화면 손상이 확인된 상태가 아니므로 rollback도 실행하지 않았다. 배포1회, rollback0, 공개 브라우저0, 새 서버 로그인0, 공급자 접속0이다. 공개 브라우저 실행 marker는 소비되지 않았으나 이후 실행은 별도 진단/재개 판단이 필요하다.

CLI 요약의 최초 `asset_upload_completed` 감지는 설치 버전과 다른 완료 문자열을 비교하여 false였다. 원문 업로드 성공 행·exit0·deployment success로 실제 업로드 결과를 구분했으며, 이 요약 문자열 문제를 공개 GET 실패 원인으로 설명하지 않는다. 원문은 저장소 밖에 보존하고 공개 근거에는 값 없는 상태만 선별했다.

## 기존 검증과 정리

- 동일 제품의 [30단계](30-pot-grounding-spacing-and-original-font.md) FE573·타입4종·build3종·invalid mode5종·lint0/0·로컬 Chromium16 결과는 당시 근거이며 이번에 재실행하지 않았다.
- 공개용 정적 검증기 준비: 실제 최종58개 artifact와 일치하는 loopback 사본에서8/8 PASS, 네 화면 크기390/768/1440/1920·6화면·초기화·직접 경로 확인. 정적 GET364, API/외부/금지/오류0, 소유 브라우저/서버 정리PASS. 이는 공개 여정 PASS가 아니다.
- 고정 검증기13개 파일의 manifest SHA-256은 `6440719c6e8b0753ad6065bbd08091e3ff4a70ced44087867f1462b027cd1f91`이다. 공개 실행 전후 기대값을 바꾸지 않았다.
- 빌드 의존성 보존 및 overlay 제거PASS. 공개 Chromium은 시작하지 않았다. 배포/조회 프로세스는 종료됐으며 이번 임시 관리 도구 인증 파일은 정리한다. 기존 사용자 미리보기와 backend 자원은 종료하지 않는다.

[정제된 실행 결과](evidence/STATIC_DEMO_PUBLIC_RELEASE/result.json). README와 과거 보고서/evidence는 보존한다. 과거 서버 연동 결과·자연 cold-start 및 공급자 장애전환 미확인 보장은 이번 작업으로 승격하지 않는다. 원래 글꼴 적용은 계속 보류다.

```text
USER_VISUAL_APPROVAL=APPROVED
ORIGINAL_FONT_LOCAL_READY=false
FRONTEND_ONLY_DEMO_PUBLIC_READY=false
PUBLIC_DEPLOYMENT_READY=false
```

다음 한 건은 현재 정적 배포의 첫 파일 HTTP 실패를 대상으로, 상태 코드와 redirect/edge 응답을 비파괴적으로 구분하는 최소 진단이다. 이번 실행에서는 진단을 추가 수행하거나 검증 결과를 PASS로 바꾸지 않는다.
