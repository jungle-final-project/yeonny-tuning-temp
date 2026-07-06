# Agent AS UI QA Skill

## 목적

이 문서는 Agent AS 데모 전 웹/프론트 관점의 시각적 QA를 반복 가능하게 수행하기 위한 runbook이다.

```text
이 QA Skill은 새 기능 구현용이 아니다.
시각적 확인, 데모 안정화, 테스트 증거 수집을 위한 절차다.
```

## 적용 시점

- `main` 또는 `main` 기준 QA 브랜치에서 Agent AS 데모를 준비할 때
- Agent AS happy path가 API 테스트로 통과한 뒤, 웹 화면에서 보여줄 증거가 필요한 때
- 데모 직전에 스크린샷, 주요 API 호출, 콘솔 에러 여부를 확인할 때

## 반드시 읽을 문서

1. `docs/agent-as/README.md`
2. `docs/agent-as/SECURITY.md`
3. `docs/agent-as/IDEMPOTENCY.md`
4. `docs/agent-as/E2E_HAPPY_PATH.md`
5. `docs/agent-as/TEAM_HANDOFF.md`
6. `docs/agent-as/AGENT_IMPLEMENTATION_SKILL.md`

## 실제 확인 파일

| 구분 | 경로 |
|---|---|
| Web package | `apps/web/package.json` |
| Web route | `apps/web/src/App.tsx` |
| Web API helper | `apps/web/src/lib/api.ts` |
| 사용자 AS API client | `apps/web/src/features/support/supportApi.ts` |
| 사용자 AS DTO | `apps/web/src/features/support/types.ts` |
| 사용자 AS 화면 | `apps/web/src/features/support/SupportPages.tsx` |
| 관리자 AS API client | `apps/web/src/features/admin/adminApi.ts` |
| 관리자 AS 목록 | `apps/web/src/features/admin/pages/AdminTicketsPage.tsx` |
| 관리자 AS 상세 | `apps/web/src/features/admin/pages/AdminTicketDetailPage.tsx` |
| 기존 Playwright 테스트 | `apps/web/tests/*.spec.ts` |
| Agent AS 시각 QA 테스트 | `apps/web/tests/agent-as-visual.spec.ts` |

## QA 대상 화면

- `/support/new`
- `/support/{ticketId}`
- `/admin/as-tickets`
- `/admin/as-tickets/{ticketId}`
- 모바일 폭의 `/support/{ticketId}`

## QA 대상 API-only 흐름

| 단계 | 분류 | 확인 방식 |
|---|---|---|
| Agent 등록 | B. API 호출로만 확인 가능 | `POST /api/agent/devices/register`, 기존 API 테스트 |
| Agent token 획득 | B. API 호출로만 확인 가능 | register response, DB에는 hash 저장 테스트 |
| Consent 저장 | B. API 호출로만 확인 가능 | `POST /api/agent/consents`, 기존 API 테스트 |
| Heartbeat | B. API 호출로만 확인 가능 | `POST /api/agent/heartbeat`, 기존 API 테스트 |
| gzip multipart Log Upload | B. API 호출로만 확인 가능 | `POST /api/agent/log-uploads`, 기존 API 테스트 |
| AS Ticket 생성 | A. 웹 UI에서 직접 확인 가능 | `/support/{ticketId}` |
| Diagnosis `RULE_READY` | A. 웹 UI에서 직접 확인 가능 | 사용자/관리자 티켓 상세 |
| Admin `supportDecision` 확정 | C. 관리자 UI가 없어서 API/테스트로만 확인 가능 | `PATCH /api/admin/as-tickets/{ticketId}` |
| User ticket status 조회 | A. 웹 UI에서 직접 확인 가능 | `/support/{ticketId}` |

## 체크리스트

- 앱이 `npm run build`로 빌드되는가?
- `/support/new`가 흰 화면 없이 열린다.
- `/support/{ticketId}`가 `analysisStatus`, `reviewStatus`, `supportDecision`을 표시한다.
- `/admin/as-tickets/{ticketId}`가 `analysisStatus`, `reviewStatus`, `supportDecision`을 표시한다.
- 모바일 폭에서 사용자 티켓 핵심 정보가 가려지지 않는다.
- 콘솔 에러가 없다.
- 네트워크 요청이 예상 API path를 호출한다.
- Agent 등록/Consent/Heartbeat/Upload는 UI가 아니라 API 테스트로 검증됐는지 확인한다.
- 같은 upload `Idempotency-Key` 재시도 시 ticket 중복 생성이 없는지 기존 테스트 근거를 확인한다.

## 스크린샷 저장 위치

```text
artifacts/qa/agent-as/
  01-support-new.png
  02-support-ticket-before-decision.png
  03-admin-ticket-decision-fields.png
  04-support-ticket-after-decision.png
  05-mobile-ticket.png
```

## 실패 판단 기준

- 흰 화면 또는 React runtime error가 보인다.
- Playwright 콘솔 에러가 발생한다.
- `analysisStatus`, `reviewStatus`, `supportDecision` 중 데모 핵심 필드가 화면에 없다.
- 사용자가 보는 티켓 상세에서 관리자 결정 후 상태를 확인할 수 없다.
- 모바일 폭에서 핵심 티켓 정보가 읽을 수 없을 정도로 잘린다.
- 실제 API-only 흐름을 검증하는 테스트 근거가 없다.

## 수정 허용 범위

- 텍스트 라벨 개선
- 응답 필드 표시 누락 수정
- 로딩/에러 상태 표시 보강
- 타입 오류 수정
- API 응답 필드 매핑 오류 수정
- 테스트/스크린샷 스크립트 추가
- QA 문서 작성

## 수정 금지 범위

- 새 기능 구현
- 백엔드 Security Chain 수정
- Idempotency 인프라 수정
- DB schema/migration 수정
- Agent AS API path 변경
- API 계약 변경
- 테스트 삭제 또는 기대값 완화
- UI 전체 리디자인
- 원격제어, LLM, Quick Assist 구현
