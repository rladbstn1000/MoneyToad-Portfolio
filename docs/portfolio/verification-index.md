# 검증 요약과 실행 진입점

이 페이지는 저장된 결과를 읽기 쉽게 연결한 색인이다. 문서 정리 자체를 새로운 테스트 실행으로 표시하지 않는다. 각 행은 서로 다른 시점·범위이며 합산한 단일 테스트 수가 아니다.

## 대표 결과

| 대상 | 저장된 결과 | 기준과 근거 |
| --- | --- | --- |
| 서버 백엔드 | 724 PASS | [22단계](../deployment/22-cookie-e2e-time-basis.md): 일반 632와 별도 Render/TLS 92를 포함한 당시 전체 회귀 |
| 프런트엔드 | 573 PASS | [30단계](../deployment/30-pot-grounding-spacing-and-original-font.md): OAuth 267, remote 245, local 61. 타입·3개 빌드·lint도 당시 PASS |
| 현재 정적 공개 체험 | 8/8 PASS | 제품 `050b65b9517dfedbb0c231cfd979eb75936a2697`의 [공개 결과](../deployment/frontend-only-public-verified.md) |
| 과거 서버 연동 공개 체험 | 전체 여정 PASS | 제품 `d020d837f2830125696d2038eb4a4c969864432e`의 [서버 공개 결과](../deployment/public-full-experience-verified.md) |

백엔드 724를 최신 문서 커밋에서 다시 실행한 결과로 설명하지 않는다. 최신 FE 및 공개 체험과 동일한 실행 환경·날짜로 묶지도 않는다. 파일 무결성·요청 수·세부 이미지 검사·과거 실패는 각 결과 문서에서 확인한다.

초기 BE 275·FE 176과 이후 AGENTS에 있었던 FE 252는 당시 단계의 값이다. [초기 검증 정책](verification-summary.md)과 [초기 BE](evidence/be-regression-summary.json) / [초기 FE](evidence/fe-regression-summary.json)를 보존한다.

## 무엇을 확인한 결과인가

**서버 연동:** 실제 보안 필터, SQL 소유권·데이터 불변성, 세션 회전·폐기, 설정·TLS, 합성 데이터와 API 재집계를 검사한 기록이다.

**현재 정적 체험:** 6개 화면, 4개 viewport, 메모리 재집계·초기화·새 탭·직접 경로, 조언 화면 이동을 확인했다. 실제 제공 파일 57개 대조와 API·외부 요청 0을 포함한다. 서버 로그인·DB 쓰기·Redis 동작을 실행한 검증은 아니다.

**한계:** 정상적인 중간 장면의 이미지 준비를 확인하며 진행한 여정이다. 모든 빠른 연속 입력이나 모든 브라우저를 보장하지 않는다. 과거 HTTP 오류와 취소 요청의 불확실성을 최신 PASS로 소급 변경하지 않는다. 실제 bfcache 사용 미관측과 합성 lifecycle 검증도 구분한다.

## 현재 프런트엔드 실행

[fe/package.json](../../fe/package.json)의 lockfile 기준 의존성을 준비한 뒤 `fe`에서 실행한다.

```sh
npm ci
npm run dev:demo-local
npm run build:demo-local
```

테스트 명령은 `test:run`(OAuth), `test:demo`(remote), `test:local`(local)로 나뉜다. 통합된 안전 실행 절차는 [25단계](../deployment/25-local-demo-and-wide-layout.md)의 `local_demo_checks.py --phase fe`와 `--phase browser`를 따른다. 먼저 해당 스크립트의 `--help`로 현재 옵션을 확인하고, 정확한 의존성·설치된 Chromium·새 출력 경로를 지정한다.

## 서버와 초기 스냅샷의 재현

현재 서버 연동 검증은 [22단계 재현 안내](../deployment/22-cookie-e2e-time-basis.md)를 기준으로 명시적 로컬 자원을 사용한다. 클라우드 계정이나 팀 서버를 자동 연결하지 않는다. 준비되지 않은 환경에서는 실행하지 않고 환경 미충족을 별도로 기록한다.

초기 `public_snapshot.py`와 일부 이전 runner는 당시 테스트 집합·개수를 고정한다. 초기 결과를 재현할 때는 `a750718033e51041015d5809b60c841d37e5c8ba`의 별도 스냅샷을 사용한다. 현재 main에 그 기대값을 강제하거나 기대 개수를 낮춰 통과시키지 않는다. 초기 전체 명령은 [정리 전 README의 고정 버전](https://github.com/rladbstn1000/MoneyToad-Portfolio/blob/8d5a1a18bc19326af6f8fd31b80cb5ad33987bb3/README.md#로컬-실행과-재현)에 남아 있다.

## 기록 보존

[배포 STATUS](../deployment/STATUS.md)는 최신 상태와 과거 진행 기록의 색인이다. 과거 FAIL·UNKNOWN·PENDING은 당시 관측을 나타낸다. 새 실행은 별도 결과 경로에 저장하고 기존 보고서를 덮어쓰지 않는다.

소스·필수 테스트·fixture·검증 진입점은 유지한다. 중복 PNG와 중간 출력의 정리는 코드·링크·참조를 확인한 뒤 별도로 진행하며, 현재 문서 정리에서는 삭제하거나 이력을 재작성하지 않는다.
