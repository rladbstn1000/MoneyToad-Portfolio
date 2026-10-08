# MoneyToad · 돈꺼비

**예산을 초과한 소비를 장독대에서 새는 물로 보여주는 소비 관리 서비스입니다.**

SSAFY 팀 프로젝트에서 Spring Boot 백엔드를 담당했습니다. 프로젝트 종료 후 접근 통제와 데모 세션을 보강하고, 기존 화면을 서버 대기 없이 둘러볼 수 있는 체험판으로 전환했습니다.

[체험판 열기](https://moneytoad-portfolio.pages.dev/) · [백엔드 사례](docs/portfolio/backend-cases.md) · [기여 범위](docs/portfolio/contribution-boundary.md) · [검증 요약](docs/portfolio/verification-index.md)

## 백엔드 하이라이트

| 문제 | 구현과 검증 | 코드 |
| --- | --- | --- |
| 예산 ID만으로 수정 대상을 조회해 다른 사용자의 예산을 수정할 수 있는 경로 | 로그인 사용자와 예산 소유자를 함께 조회하도록 변경하고, 거절 응답뿐 아니라 DB 불변성도 검사 | [BudgetService](be/src/main/java/com/potg/don/budget/service/BudgetService.java) · [회귀 테스트](be/src/test/java/com/potg/don/budget/BudgetOwnershipIntegrationTest.java) |
| 서버 연동 데모의 동시 갱신과 이전 토큰 재사용 | Redis Lua로 토큰 해시 교체를 원자화하고, 재사용 시 세션 폐기. 회전해도 절대 만료 유지 | [회전 Lua](be/src/main/resources/redis/demo-session-rotate.lua) · [세션 가드](be/src/main/java/com/potg/don/auth/demo/DemoSessionGuard.java) |
| 월별 소비와 카테고리별 누수를 일관되게 조회 | 사용자와 기간을 제한한 DB 집계, 월별·카테고리별 합계와 예산 비교 | [TransactionRepository](be/src/main/java/com/potg/don/transaction/repository/TransactionRepository.java) |

토큰 재발급·교체 자체는 원본에도 있었습니다. 후속 개선은 **demo 경로의 원자적 회전, 재사용 감지, 세션 폐기와 절대 만료**이며, 원본 OAuth 전체를 같은 방식으로 개편한 것은 아닙니다. [문제와 선택의 상세 설명](docs/portfolio/backend-cases.md)

## 담당 역할과 개인 개선

| 구분 | 범위 |
| --- | --- |
| 팀 프로젝트에서 담당 | Spring Boot 인증과 토큰 관리, 소비 내역·예산 API, 월별·카테고리별 집계, AI 분석 결과 연동 |
| 팀 공동 결과 | 서비스 콘셉트, 프런트엔드 화면과 시각 디자인, 백엔드와 AI 분석 서비스를 결합한 사용자 경험 |
| 프로젝트 종료 후 개인 개선 | 소유권 검사와 회귀 테스트, 서버 연동 데모의 인증·합성 데이터·검증, 프런트엔드 전용 공개 체험과 반응형 화면 보강 |

AI 결과를 백엔드에 연결한 역할과 AI 모델·분석 서비스 구현은 구분합니다. 담당 범위와 원본 변경 기록은 [기여 문서](docs/portfolio/contribution-boundary.md)에 정리했습니다. 줄 수를 개인 기여율이나 전체 단독 설계의 근거로 사용하지 않습니다.

## 공개 체험

**마당 → 장독대 → 씀씀이 → 두꺼비의 조언 → 곳간 → 정보 입력**을 둘러볼 수 있습니다.

장독대의 예산을 조절하거나 거래 분류를 바꾸면 같은 메모리 상태에서 모든 화면의 수치를 다시 계산합니다. 샘플 기준월의 연습 거래를 카페에서 마트로 옮기면 총소비 **908,000원은 유지**되고 누수는 **18,000원에서 0원**으로 바뀝니다.

별도 로그인이나 백엔드 준비가 필요하지 않습니다. 변경 내용은 **현재 탭의 메모리에서만 유지**되며 새로고침, 처음부터 다시하기, 체험 종료 시 초기화됩니다. 조언은 샘플 소비 수치와 준비된 분석 문구이며, 실시간 AI 호출이나 실제 개인정보·카드번호·CVC 입력·저장은 없습니다.

## 두 가지 실행 구조

| 구분 | 처리 위치와 목적 |
| --- | --- |
| 현재 공개 체험 | Cloudflare 정적 파일 → React → 브라우저 메모리. 화면과 상호작용을 빠르게 확인 |
| 별도 서버 연동 모드 | React → Spring Boot API → SQL DB·Redis. 인증·소유권·토큰 관리와 실제 저장을 확인 |

기존 백엔드와 서버 연동 검증은 보존합니다. 정적 체험의 성공을 현재 공개 링크에서의 DB 저장이나 Redis 동작 검증으로 설명하지 않습니다. [서버 구조](docs/portfolio/architecture-notes.md) · [정적 체험을 선택한 이유](docs/portfolio/demo-decision.md)

## 기술 스택

| 영역 | 주요 기술 |
| --- | --- |
| Backend | Java 21, Spring Boot 3.5.5, Spring Security, JPA, JWT, WebClient |
| Data | MySQL, Redis |
| Frontend | React, TypeScript, Vite, TanStack Query, Zustand, Recharts |
| Test | JUnit, Vitest, Testing Library, Playwright, ESLint |

정확한 의존성은 [BE build](be/build.gradle), [FE manifest](fe/package.json)와 [lockfile](fe/package-lock.json)을 기준으로 합니다.

## 검증 요약

| 대상 | 저장된 대표 결과 |
| --- | --- |
| 백엔드 회귀 | **724 PASS** — 서버 연동 검증의 22단계 기록 |
| 프런트엔드 | **573 PASS** — OAuth·remote·local을 포함한 30단계 기록 |
| 현재 정적 공개 체험 | **8/8 PASS** — 6개 화면, 4개 viewport, 재집계·초기화, API·외부 요청 0 |

서로 다른 시점과 실행 범위의 결과입니다. 이번 문서 정리에서 테스트를 다시 실행하거나 합산한 수치가 아닙니다. 기준 제품, 환경, 상세 결과와 과거 검증은 [검증 요약](docs/portfolio/verification-index.md)에서 확인할 수 있습니다.

## 로컬 체험 실행

Node 22와 npm이 필요합니다. 저장소를 받은 뒤 실행합니다.

```sh
cd fe
npm ci
npm run dev:demo-local
```

정적 빌드는 `npm run build:demo-local`이며 결과는 `fe/dist-local/`입니다. 이 모드는 백엔드 주소나 실제 접속정보가 필요하지 않습니다. [모드와 초기화 계약](docs/deployment/25-local-demo-and-wide-layout.md)

서버 연동·격리 검증은 별도 환경을 사용합니다. 현재 코드와 초기 스냅샷의 실행기를 섞지 않도록 [검증 진입점](docs/portfolio/verification-index.md)을 확인하세요.

## 문서와 한계

[문서 안내](docs/README.md)에서 핵심 사례, 실행 방법, 과거 단계별 근거를 구분했습니다. 반복 실행 기록은 상세 근거로 보존하며 README의 핵심 설명과 분리합니다.

현재 체험은 시스템 글꼴을 사용합니다. 실제 AI·개인 금융정보·서버 저장은 제공하지 않습니다. 기존 서버 연동의 제약과 원본 코드·자산의 출처는 [기여 범위](docs/portfolio/contribution-boundary.md)에 남깁니다.

후속 개선 과정에서 GPT·Codex를 구현, 테스트, 검증 자동화와 문서 작성의 보조 도구로 사용했습니다. AI 사용 사실과 개인 담당 범위를 구분하며, 원래 팀의 프런트엔드·AI 결과를 개인 단독 성과로 표기하지 않습니다.
