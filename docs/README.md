# 돈꺼비 문서 안내

## 먼저 읽을 문서

| 관심사 | 문서 |
| --- | --- |
| 본인이 맡은 기능과 팀 결과의 구분 | [기여 범위](portfolio/contribution-boundary.md) |
| 소유권 검사·원자적 토큰 회전·DB 집계 | [백엔드 사례](portfolio/backend-cases.md) |
| 현재 구조와 서버 연동 | [서버 아키텍처 노트](portfolio/architecture-notes.md) |
| 정적 체험을 선택한 이유 | [전환 결정](portfolio/demo-decision.md) |
| 테스트 수의 시점·검증 범위·실행 방법 | [검증 요약](portfolio/verification-index.md) |

## 상세 결과와 과거 기록

[현재 정적 공개 결과](deployment/frontend-only-public-verified.md)와 [과거 서버 연동 공개 결과](deployment/public-full-experience-verified.md)는 서로 다른 제품 동작의 검증이다.

[배포 STATUS](deployment/STATUS.md), [배포 단계 문서](deployment), [초기 스냅샷 검증](portfolio/verification-summary.md)은 재현과 문제 추적을 위한 기록으로 유지한다. 당시 FAIL·UNKNOWN·테스트 수를 현재 결과로 읽지 않는다.

## 자료 정리 범위

이번 정리는 읽는 순서와 역할 설명을 개선하는 문서 작업이다. 기존 단계 문서·이미지·JSON·소스·테스트는 삭제하거나 보관 브랜치로 일괄 이동하지 않았다.

추가 용량 정리는 반복 PNG와 중간 출력부터 검토한다. 제거 후보의 내용 중복, Markdown 링크, 스크립트·fixture 참조를 확인하고 대표 근거를 남겨야 한다. 필요한 fixture를 과거 evidence로 오인해 삭제하지 않는다. 기존 Git 이력을 다시 쓰는 작업과도 구분한다.

[프로젝트 README](../README.md)
