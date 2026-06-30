# 4번 AS 담당자용 AS AI Chat 인계 문서

## 목적

이 문서는 3번 AI 담당자가 구현한 AS AI Chat 기능을 4번 AS 화면과 연결하기 위한 인계 문서다.

AS AI Chat은 AS 티켓을 새로 만들지 않는다. 이미 생성된 `as_tickets`를 기준으로 사용자와 AI가 대화하고, 답변 생성 과정에서 RAG 근거, Tool 결과, LLM 호출 기록을 남긴다.

## 현재 PR 포함 상태

현재 `hyunjin/main`은 `prototype/main`의 PR #14, #15가 반영된 상태를 기준으로 한다.

| 항목 | 기준 |
|---|---|
| PR #14 | `POST /api/auth/logout`은 `Authorization: Bearer ...`와 `refreshToken` body를 받아 refresh token을 폐기한다. |
| PR #15 | request body/query/path 검증 오류는 `400 VALIDATION_ERROR`로 통일한다. |
| AS Chat Auth | `USER` 로그인 토큰이 필요하다. 토큰이 없으면 프론트 guard가 `/login?redirect=...`로 보낸다. |
| AS Chat 소유권 | 화면/API/LLM/RAG/Tool trace는 3번 owner, AS 티켓 상태와 원인 후보 반영은 4번 owner다. |

## 접속 방법

Docker가 실행 중이면 아래 주소로 확인한다.

```text
http://localhost:5173/support/ai-chat
```

로그인 계정:

```text
email: user@example.com
password: passw0rd!
```

기본 seed AS 티켓:

```text
00000000-0000-4000-8000-000000006001
```

AS 티켓 상세 화면에서 바로 연결하려면 다음 형식으로 이동시키면 된다.

```text
/support/ai-chat?asTicketId={asTicketPublicId}
```

예:

```text
/support/ai-chat?asTicketId=00000000-0000-4000-8000-000000006001
```

## 4번 화면에서 붙일 버튼

`/support/:ticketId` 또는 `/admin/as-tickets/:ticketId`에 버튼을 하나 추가하면 된다.

권장 버튼명:

```text
AI 1차 상담 열기
```

권장 이동:

```ts
navigate(`/support/ai-chat?asTicketId=${ticketId}`);
```

현재 `/support/ai-chat` 화면은 query string의 `asTicketId`를 읽고, 없으면 seed 티켓 ID를 사용한다.

## API

### 대화 이력 조회

```http
GET /api/ai/as-chat?asTicketId={ticketId}
Authorization: Bearer {accessToken}
```

역할:

- 해당 AS 티켓의 챗봇 세션과 메시지 이력을 조회한다.
- 세션이 없으면 빈 메시지 목록을 반환한다.
- AS 티켓 증상 요약과 현재 모델명도 반환한다.

### 메시지 전송

```http
POST /api/ai/as-chat/stream
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "asTicketId": "00000000-0000-4000-8000-000000006001",
  "message": "게임을 20분 정도 하면 프레임이 급락하고 GPU 온도가 95도까지 올라갑니다."
}
```

프론트는 SSE endpoint를 우선 사용한다.

SSE 이벤트:

| 이벤트 | 의미 |
|---|---|
| `STARTED` | 사용자/티켓/세션 확인 시작 |
| `RAG_READY` | RAG 근거 검색 완료 |
| `TOOLS_READY` | Tool 결과 준비 완료 |
| `LLM_RUNNING` | LLM 답변 생성 중 |
| `DONE` | 최종 `AsChatResponse` 반환 |
| `ERROR` | 처리 실패 |

SSE 연결 시작 전에 실패하면 프론트는 기존 `POST /api/ai/as-chat`으로 fallback한다.

## 응답에서 4번이 사용할 수 있는 필드

`DONE` 또는 일반 POST 응답의 핵심 필드는 다음과 같다.

| 필드 | 용도 |
|---|---|
| `assistantMessage` | 사용자에게 보여줄 AI 상담 답변 |
| `causeCandidates` | 원인 후보. 화면 표시 또는 4번 API에서 `as_tickets.cause_candidates` 반영 후보로 사용 |
| `nextActions` | 사용자가 따라 할 1차 조치 |
| `escalation.required` | 상담원/원격지원/기사 연결 필요 여부 |
| `ticketDraft.symptomSummary` | 티켓 증상 요약 초안 |
| `ticketDraft.recommendedLogRequest` | 추가 로그 요청 문구 |
| `evidence` | RAG 근거 목록 |
| `toolResults` | Tool 검증 결과 목록 |
| `agentSessionId` | 관리자 Agent trace 상세로 연결할 ID |

## DB 저장 기준

AS Chat은 다음 테이블을 사용한다.

| 테이블 | owner | 설명 |
|---|---|---|
| `as_chat_sessions` | 3번 | 사용자 + AS 티켓당 active 챗봇 세션 |
| `as_chat_messages` | 3번 | USER/ASSISTANT 대화 이력 |
| `agent_sessions` | 3번 | assistant 답변 1회당 Agent 실행 trace |
| `rag_evidence` | 3번 | 실제 사용한 RAG 근거 |
| `tool_invocations` | 3번 | Agent 내부 Tool 호출 결과 |
| `llm_generations` | 3번 | provider/profile/model/latency/token/schema 결과 |
| `as_tickets` | 4번 | 읽기만 한다. AS Chat은 status/cause/upgrade를 직접 수정하지 않는다. |

중요:

- AS Chat은 `as_tickets.status`를 변경하지 않는다.
- AS Chat은 `as_tickets.cause_candidates`를 직접 저장하지 않는다.
- AS Chat은 `as_tickets.upgrade_candidates`를 직접 저장하지 않는다.
- 4번이 필요하면 AS Chat 응답을 읽어 별도 PATCH/API에서 티켓에 반영한다.

## 에러 처리

| 상황 | 응답/화면 |
|---|---|
| 로그인 토큰 없음 | 프론트 guard가 로그인 페이지로 이동 |
| 권한 없는 티켓 또는 없는 티켓 | `404`, 화면에 “현재 로그인 사용자에게 연결된 AS 티켓을 찾을 수 없습니다.” |
| `OPENAI_API_KEY` 없음 | `428 PRECONDITION_REQUIRED`, 화면에 키 설정 안내 |
| LLM JSON 계약 실패 | `502`, 화면에 실패 메시지 |
| 입력값 누락 | PR #15 정책에 따라 `400 VALIDATION_ERROR` |

## 현재 기본 AI profile

사용자 요청은 기본적으로 profile 1개만 실행한다.

```text
AS_CHAT_DEFAULT_PROFILE=AS_CHAT_FAST
```

`AS_CHAT_NANO_FAST`는 실험 후보로 남겨두었다. 평균 응답은 빠르지만 현재 benchmark에서 schema valid가 낮아 기본값으로 전환하지 않았다.

## 4번이 바로 하면 되는 작업

1. `/support/:ticketId`에 `AI 1차 상담 열기` 버튼 추가
2. 버튼 클릭 시 `/support/ai-chat?asTicketId=${ticketId}`로 이동
3. AS 티켓 상세 화면에 AI 결과를 반영하고 싶다면, `causeCandidates`, `nextActions`, `ticketDraft`를 읽어 화면에 표시
4. `as_tickets.cause_candidates`, `upgrade_candidates`, `status`에 저장하려면 4번 owner API에서 명시적으로 처리
5. 원격지원/기사 연결 판단은 `escalation.required`와 `escalation.reason`을 참고하되, 최종 상태 전이는 4번 AS 정책으로 결정

## PR 전 감사 결과

현재 3번 변경은 공동계약서 기준으로 다음 조건을 만족한다.

| 감사 항목 | 결과 |
|---|---|
| `/support/ai-chat` route owner | 3번 owner, 4번 협업자로 문서화됨 |
| `as_tickets` 쓰기 침범 | 없음. 읽기만 수행 |
| `cause_candidates`, `upgrade_candidates` 자동 수정 | 없음 |
| Auth 정책 | PR #14/#15 반영 후 테스트 통과 |
| OpenAPI AS Chat profile enum | 코드 enum과 일치 |
| Flyway migration 목록 | V31~V35 문서 반영 |
| 테스트 | OpenAPI 검증, 프론트 route/AS Chat test, 백엔드 test, Docker API health 통과 |

따라서 이 변경은 4번 AS 담당자가 화면 연결 작업을 시작할 수 있는 상태다.
