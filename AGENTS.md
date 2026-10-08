# MoneyToad 작업 지침

## 현재 구조

먼저 [프로젝트 README](README.md), [기여 범위](docs/portfolio/contribution-boundary.md), [검증 진입점](docs/portfolio/verification-index.md)을 읽는다. [배포 STATUS](docs/deployment/STATUS.md)의 과거 결과를 현재 작업의 실행 결과로 간주하지 않는다.

- 공개 체험은 `demo/local`의 브라우저 메모리 모드다. 서버 준비·인증·업무 API를 호출하지 않는다.
- `demo/remote`와 OAuth는 별도의 서버 연동 계약이다. local의 초기화와 remote의 저장·복원을 섞지 않는다.
- 원래 팀의 화면·AI 결과와 개인 담당 백엔드·후속 개선을 구분한다.

## 검증과 환경

1. 현재 FE 검증은 `scripts/verification/local_demo_checks.py`의 `--help`와 검증 진입점 문서를 따른다. 정확한 lockfile 의존성, 설치된 Chromium, 새 결과 경로를 사용한다.
2. 서버 검증은 명시적인 전용 로컬 MySQL·Redis와 합성 설정을 사용한다. 현재 코드에 초기 스냅샷의 고정 테스트 개수를 강제하지 않는다.
3. 초기 `public_snapshot.py` 등은 해당 기준 스냅샷의 재현용이다. 저장소가 Git-free라고 가정하지 않는다.
4. 실제 환경파일·공유 DB·팀 OAuth·AI·managed provider를 자동으로 연결하지 않는다. 로컬 검증을 실제 클라우드 검증이라고 쓰지 않는다.
5. 요청된 범위에 맞는 타입·lint·회귀와 기존 공개 검사를 수행한다. 문서만 바뀌면 전체 제품 검증을 불필요하게 반복하지 않는다. 실행하지 못한 검사는 명확히 남긴다.

## 안전 경계와 보존

- 기존 미커밋 변경·과거 evidence·실패·UNKNOWN을 보존한다. 새 결과는 새 출력 경로를 사용한다.
- 원문 credential, token, cookie, 사용자 식별값, private 경로, 로그·HAR·trace·저장 상태·키·미확인 CSV·폰트는 공개 파일에 넣지 않는다. 현재 폰트는 시스템 fallback이다.
- `public_scan.py`와 기존 source review를 유지한다. 검사 규칙 완화·확장자 변경 등으로 우회하지 않는다. 값 없는 안전한 오류 코드와 측정값까지 지워 진단 근거를 잃지 않는다.
- 자원은 생성한 소유권을 확인하고 정리한다. cleanup 미확인은 후속 쓰기의 차단 조건이다.
- provider 원장·단일 실행 marker·예약을 초기화하거나 재사용하지 않는다. 원격 실행, 배포, 계정 폐기, 이력 재작성은 각각 명시된 요청 범위에서만 수행한다.
- 기존 render 모드의 명시적 profile·PORT/bind·SQL validate·JDBC/Redis TLS와 분리 권한을 유지한다. maintenance는 기본 dry-run이며 임의 공유 DB 정리를 허용하지 않는다.
- 결과가 불명확한 인증 POST를 자동 재시도하거나 성공으로 추정하지 않는다. gateway·readiness·rate limit·세션·cleanup 정책은 관련 제품 변경 없이 바꾸지 않는다.

## 읽는 사람을 위한 문서

README에는 본인 역할과 핵심 사례, 대표 검증 결과만 둔다. 반복 실행 수치·원문 작업 지시는 상세 기록과 구분한다. AI 도구 사용을 숨기거나 수행하지 않은 설계·검토·실행을 본인 완료로 쓰지 않는다.
