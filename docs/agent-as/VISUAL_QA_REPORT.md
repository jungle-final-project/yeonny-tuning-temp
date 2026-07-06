# Agent AS Visual QA Report

## QA 수행 일시

- 2026-07-02 15:56:01 +09:00

## 기준 브랜치/커밋

- 기준 원격 브랜치: `pcagent/main`
- 기준 커밋: `f076045 Merge branch 'integration/agent-as-e2e' into merge/agent-as-e2e-to-main`
- QA 브랜치: `qa/agent-as-visual-demo`

## 실행 환경

- OS: Windows, PowerShell
- Web: `apps/web`, Vite + React + Playwright
- API: `apps/api`, Gradle wrapper
- 스크린샷 도구: Playwright desktop Chromium

## 실행 명령

```powershell
git fetch pcagent
git switch -c qa/agent-as-visual-demo pcagent/main
npx playwright test tests/agent-as-visual.spec.ts --project=desktop-chromium
```

전체 검증 명령은 최종 검증 단계에서 다시 실행한다.

## 확인한 실제 프론트 파일/라우트

| 구분 | 실제 경로 |
|---|---|
| route 정의 | `apps/web/src/App.tsx` |
| API helper | `apps/web/src/lib/api.ts` |
| 사용자 AS API client | `apps/web/src/features/support/supportApi.ts` |
| 사용자 AS DTO | `apps/web/src/features/support/types.ts` |
| 사용자 AS 화면 | `apps/web/src/features/support/SupportPages.tsx` |
| 관리자 AS API client | `apps/web/src/features/admin/adminApi.ts` |
| 관리자 AS 목록 | `apps/web/src/features/admin/pages/AdminTicketsPage.tsx` |
| 관리자 AS 상세 | `apps/web/src/features/admin/pages/AdminTicketDetailPage.tsx` |
| Playwright 설정 | `apps/web/playwright.config.ts` |
| 기존 route smoke | `apps/web/tests/routes.spec.ts` |
| 기존 admin guard 테스트 | `apps/web/tests/admin-guard.spec.ts` |
| Agent AS visual QA | `apps/web/tests/agent-as-visual.spec.ts` |

## Agent AS 흐름의 UI/API 분류

| 단계 | 분류 | QA 결과 |
|---|---|---|
| Agent 등록 | B. API 호출로만 확인 가능 | `PcAgentControllerSecurityTest`, `PcAgentAsServiceTest`로 검증됨 |
| Agent token 획득 | B. API 호출로만 확인 가능 | register 응답과 raw token 미저장 테스트로 검증됨 |
| Consent 저장 | B. API 호출로만 확인 가능 | Agent token + `Idempotency-Key` 테스트로 검증됨 |
| Heartbeat | B. API 호출로만 확인 가능 | Agent token + `Idempotency-Key` 테스트로 검증됨 |
| gzip multipart Log Upload | B. API 호출로만 확인 가능 | multipart/gzip, `RULE_READY`, idempotent retry 테스트로 검증됨 |
| AS Ticket 생성 | A. 웹 UI에서 직접 확인 가능 | `/support/{ticketId}` 스크린샷으로 확인 |
| Diagnosis `RULE_READY` | A. 웹 UI에서 직접 확인 가능 | 사용자/관리자 상세 화면에 표시 확인 |
| Admin `supportDecision` 확정 | C. 관리자 UI가 없어서 API/테스트로만 확인 가능 | `PATCH /api/admin/as-tickets/{ticketId}` 테스트로 검증됨 |
| User ticket status 조회 | A. 웹 UI에서 직접 확인 가능 | `/support/{ticketId}` 스크린샷으로 확인 |

## 확인한 화면 목록

- `/support/new`
- `/support/qa-ticket-before`
- `/admin/as-tickets/qa-ticket-after`
- `/support/qa-ticket-after`
- 모바일 폭의 `/support/qa-ticket-after`

## 확인한 API 흐름

Playwright visual QA에서 mock으로 확인한 path:

- `GET /api/as-tickets/qa-ticket-before`
- `GET /api/auth/me`
- `GET /api/admin/as-tickets/qa-ticket-after`
- `GET /api/as-tickets/qa-ticket-after`

기존 백엔드 테스트로 확인된 path:

- `POST /api/agent/devices/register`
- `POST /api/agent/consents`
- `POST /api/agent/heartbeat`
- `POST /api/agent/log-uploads`
- `PATCH /api/admin/as-tickets/{ticketId}`
- `GET /api/as-tickets/{ticketId}`

## 스크린샷 경로

```text
artifacts/qa/agent-as/01-support-new.png
artifacts/qa/agent-as/02-support-ticket-before-decision.png
artifacts/qa/agent-as/03-admin-ticket-decision-fields.png
artifacts/qa/agent-as/04-support-ticket-after-decision.png
artifacts/qa/agent-as/05-mobile-ticket.png
```

## 발견한 문제

1. 사용자 AS 티켓 상세 화면이 백엔드 응답의 `analysisStatus`, `reviewStatus`, `supportDecision`을 표시하지 않았다.
2. 관리자 AS 티켓 상세 화면이 백엔드 응답의 `analysisStatus`, `reviewStatus`, `supportDecision`, `riskLevel`을 표시하지 않았다.
3. 사용자 AS 티켓 상세 화면이 모바일 폭에서 2열 고정 grid 때문에 핵심 정보가 화면 밖으로 밀렸다.
4. 전역 헤더/내비게이션은 모바일 폭에서 여전히 가로 폭이 넓다. 이번 QA는 Agent AS 데모 화면 안정화가 목적이므로 전체 헤더 리디자인은 하지 않았다.

## 수정한 문제

- `apps/web/src/features/support/types.ts`
  - 사용자 티켓 DTO에 `analysisStatus`, `reviewStatus`, `supportDecision`, `riskLevel`, remote/visit support 표시 필드를 추가했다.
- `apps/web/src/features/support/SupportPages.tsx`
  - 사용자 티켓 상세에 진단 상태, 검토 상태, 지원 결정, 위험도, 관리자 메모, 원격/방문지원 정보를 표시했다.
  - AS 화면의 2열 grid를 작은 화면에서 1열로 바꿨다.
- `apps/web/src/features/admin/adminApi.ts`
  - 관리자 티켓 DTO에 Agent AS decision 관련 필드를 추가했다.
- `apps/web/src/features/admin/pages/AdminTicketDetailPage.tsx`
  - 관리자 티켓 상세에 진단 상태, 검토 상태, 지원 결정, 위험도, 원격/방문지원 정보를 표시했다.
  - 관리자 상세 grid를 작은 화면에서 1열로 바꿨다.
- `apps/web/src/components/display/DataTable.tsx`
  - 긴 URL과 decision 값이 표 셀 안에서 줄바꿈되도록 했다.
- `apps/web/tests/agent-as-visual.spec.ts`
  - 스크린샷 기반 QA와 콘솔 에러/예상 API path 검증을 추가했다.

## 남은 위험

- 관리자 `supportDecision` 확정은 현재 웹 UI 폼이 아니라 API/테스트로만 검증된다. 새 기능 추가를 피하기 위해 이번 QA에서는 관리자 결정 UI를 만들지 않았다.
- 전역 헤더/내비게이션은 모바일에서 완전히 최적화되어 있지 않다. Agent AS 본문 핵심 정보는 확인 가능하지만, 전체 모바일 리디자인은 별도 작업으로 분리하는 것이 안전하다.
- `/support/new`는 기존 웹 사용자 로그 업로드 흐름이고, PC Agent 직접 API인 `/api/agent/log-uploads`를 호출하지 않는다. Agent 등록부터 업로드까지는 API/test-only 구간으로 분류했다.

## 데모 전 체크리스트

- `git status --short`가 clean인지 확인한다.
- `python tools/validate_openapi.py`가 통과하는지 확인한다.
- `apps/api`에서 `.\gradlew.bat test --no-daemon`이 통과하는지 확인한다.
- `apps/api`에서 `.\gradlew.bat bootJar --no-daemon`이 통과하는지 확인한다.
- `apps/web`에서 `npm run build`가 통과하는지 확인한다.
- `apps/web`에서 `npm run test`가 통과하는지 확인한다.
- `npx playwright test tests/agent-as-visual.spec.ts --project=desktop-chromium`이 통과하는지 확인한다.
- 스크린샷 5개가 `artifacts/qa/agent-as/` 아래 생성됐는지 확인한다.
- main 반영 전 GitHub Actions CI가 success인지 확인한다.
