# API_CONTRACT.md

## 목적

이 문서는 BuildGraph 3주 MVP의 API 계약을 정의한다. `docs/DB_SCHEMA.md`의 테이블과 `docs/ROUTE_OWNERSHIP.md`의 owner 기준을 따른다.

## Base URL

```text
/api
```

환경별 host는 배포 설정에서 분리한다. 이 문서의 모든 path는 `/api` 기준이다.

## 인증 방식

- 기본 인증 방식은 `Authorization: Bearer <accessToken>`이다.
- access token은 JWT다.
- refresh token은 `refresh_tokens` 테이블에 hash로 저장한다.
- Google OAuth one-time code는 DB에 저장하지 않고 Redis/runtime에서 짧은 TTL로 관리한다.
- 관리자 API는 `ADMIN` 권한이 필요하다.
- V1은 전체 로그인 정책이다. 회원가입, 로그인, OAuth 시작/콜백/교환, health를 제외한 API는 인증이 필요하다.

## 공통 성공 응답 형식

성공 응답은 별도 envelope 없이 DTO를 직접 반환한다.

예:

```json
{
  "id": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd",
  "email": "user@example.com",
  "name": "홍길동",
  "role": "USER"
}
```

API DTO의 `id`는 내부 PK가 아니라 `public_id` 문자열이다.

## 공통 실패 응답 형식

```json
{
  "code": "VALIDATION_ERROR",
  "message": "요청 값이 올바르지 않습니다.",
  "details": {
    "field": "email"
  }
}
```

공통 실패 code:

| code | HTTP status | 사용 기준 |
|---|---:|---|
| `VALIDATION_ERROR` | `400` | request body, query, path param 형식 오류 |
| `UNAUTHORIZED` | `401` | access token 없음, 만료, 검증 실패 |
| `FORBIDDEN` | `403` | 인증은 되었지만 `ADMIN` 권한이 필요한 API 접근 |
| `NOT_FOUND` | `404` | public_id 리소스 없음, soft delete 리소스, 본인 소유가 아닌 사용자 리소스 |
| `CONFLICT_STATE` | `409` | 허용되지 않은 상태 전이, 이미 실행 중인 job/session |
| `DUPLICATE_RESOURCE` | `409` | 이메일 중복, 동일 active price alert 중복 |
| `FILE_VALIDATION_ERROR` | `400` | JSONL 업로드 파일 크기/MIME/확장자/line 검증 실패 |
| `PRECONDITION_REQUIRED` | `428` | LLM 필수 기능에서 `OPENAI_API_KEY` 등 서버 선행 설정 누락 |
| `UPSTREAM_ERROR` | `502` | LLM 응답 JSON 계약 위반 또는 외부 LLM 호출 처리 실패 |
| `INTERNAL_ERROR` | `500` | 서버 내부 오류 |

## status code 규칙

| Status | 사용 기준 |
|---|---|
| `200` | 조회, 수정, 동기 처리 성공 |
| `201` | 리소스 생성 성공 |
| `204` | 본문 없는 성공 |
| `302` | OAuth redirect |
| `400` | validation 실패, `consentAccepted=false`, 파일 검증 실패 |
| `401` | 인증 없음 또는 token 만료 |
| `403` | 인증은 되었지만 필요한 role 부족 |
| `404` | public_id에 해당하는 리소스 없음, soft delete, 본인 소유 아님 |
| `409` | 중복 리소스, 상태 전이 충돌, 중복 실행 |
| `428` | 서버 선행 설정이 필요한 기능에서 환경변수나 외부 키 누락 |
| `502` | 외부 LLM 응답을 계약 DTO로 해석할 수 없음 |
| `500` | 서버 오류 |

## pagination 규칙

목록 API는 query parameter `page`, `size`를 받는다.

MVP 기준 결정값:

- `page` 기본값은 `0`이다.
- `size` 기본값은 `20`이다.
- `size` 최대값은 `100`이다.
- `page < 0`, `size < 1`, `size > 100`이면 `400 VALIDATION_ERROR`를 반환한다.
- 정렬 기본값은 각 API의 최신 생성순 또는 최신 수집순이다. public contract에는 `sort` parameter를 열지 않는다.

응답 형식:

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "total": 125
}
```

pagination 적용 API:

- `GET /api/builds/history`
- `GET /api/parts`
- `GET /api/price-alerts`
- `GET /api/rag/search`
- `GET /api/admin/price-jobs`
- `GET /api/admin/agent-sessions`
- `GET /api/admin/tool-invocations`
- `GET /api/admin/as-tickets`

## 공통 ID 규칙

- path의 `{id}`는 모두 해당 리소스의 `public_id`다.
- `buildId`, `partId`, `ticketId`, `agentSessionId`, `assignedAdminId`도 모두 `public_id`다.
- 내부 `BIGINT id`는 API로 노출하지 않는다.
- `targetId`, `evidenceIds`, `partIds`도 모두 `public_id` 문자열이다.

## 사용자 본인 자원 접근 규칙

MVP 기준 결정값:

- USER API에서 build, AS ticket, agent log upload, agent session을 조회할 때 소유자가 아니면 `404 NOT_FOUND`를 반환한다.
- 존재 여부 추정을 막기 위해 본인 소유가 아닌 public_id에 `403`을 사용하지 않는다.
- ADMIN API는 role이 `ADMIN`이 아니면 `403 FORBIDDEN`을 반환한다.
- 인증이 없거나 token이 유효하지 않으면 소유권 확인 전 `401 UNAUTHORIZED`를 반환한다.
- soft delete된 리소스는 소유자여도 `404 NOT_FOUND`다.

## API별 계약

### Auth/User

| Method | Path | Auth | Owner | Request 예시 | Response 예시 | 관련 DB table |
|---|---|---|---|---|---|---|
| `POST` | `/api/users` | no | 1번 | `{ "email": "user@example.com", "password": "passw0rd!", "name": "홍길동", "termsAccepted": true, "marketingAccepted": false }` | `{ "id": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "email": "user@example.com", "name": "홍길동", "role": "USER" }` | `users` |
| `POST` | `/api/auth/login` | no | 1번 | `{ "email": "user@example.com", "password": "passw0rd!" }` | `{ "accessToken": "jwt-access-token", "refreshToken": "opaque-refresh-token", "user": { "id": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "email": "user@example.com", "name": "홍길동", "role": "USER" } }` | `users`, `refresh_tokens` |
| `POST` | `/api/auth/refresh` | no | 1번 | `{ "refreshToken": "opaque-refresh-token" }` | `{ "accessToken": "new-jwt-access-token", "refreshToken": "new-opaque-refresh-token" }` | `refresh_tokens` |
| `POST` | `/api/auth/logout` | USER | 1번 | `{ "refreshToken": "opaque-refresh-token" }` | `204 No Content` | `refresh_tokens` |
| `GET` | `/api/auth/me` | USER | 1번 | - | `{ "id": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "email": "user@example.com", "name": "홍길동", "role": "USER" }` | `users` |
| `GET` | `/api/auth/google/start` | no | 1번 | - | `302 Redirect` | runtime |
| `GET` | `/api/auth/google/callback` | no | 1번 | Google callback query | `302 /auth/callback?code=one-time-code` | `users`, `user_auth_providers`, runtime |
| `POST` | `/api/auth/exchange` | no | 1번 | `{ "code": "one-time-code" }` | `{ "accessToken": "jwt-access-token", "refreshToken": "opaque-refresh-token", "user": { "id": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "email": "user@example.com", "name": "홍길동", "role": "USER" } }` | `users`, `user_auth_providers`, `refresh_tokens`, runtime |

Auth/User 구현 owner는 1번이다. 5번은 `Authorization` header 전달, token 저장 helper, `RequireAdmin`, admin guard, security allowlist, 공통 `ErrorResponse` 정합성을 검토한다.

Google OAuth 정책:

- V1 provider는 `GOOGLE` 1개다.
- verified email이 기존 local 계정과 같으면 같은 `users` row에 연결한다.
- Google access/refresh token은 저장하지 않는다.

### Quote/Build

| Method | Path | Auth | Owner | Request 예시 | Response 예시 | 관련 DB table |
|---|---|---|---|---|---|---|
| `POST` | `/api/requirements/parse` | USER | 1번 | `{ "message": "150만원 게임용 PC", "budget": 1500000, "usageTags": ["GAMING"], "resolution": "QHD", "preferredVendors": ["NVIDIA"], "priority": "성능" }` | `{ "id": "2e0f8c9c-8e1c-4d75-94a2-5d6a4977de11", "rawMessage": "150만원 게임용 PC", "budget": 1500000, "usageTags": ["GAMING"], "parsedContext": { "usageTags": ["GAMING"], "budget": 1500000, "resolution": "QHD", "parseMode": "AGENT_RAG_LLM", "parser": "requirement-parse-agent-v1" }, "questions": [{ "key": "noisePreference", "label": "소음 민감도", "options": ["상관없음", "조용한 편"], "required": false }], "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "agentSummary": "요구사항을 구조화했습니다.", "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"] }` | `requirements`, `agent_sessions`, `rag_evidence` |
| `POST` | `/api/builds/recommend` | USER | 1번 | `{ "requirementId": "2e0f8c9c-8e1c-4d75-94a2-5d6a4977de11", "answers": { "noisePreference": "조용한 편" } }` | `{ "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "recommendations": [{ "id": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd", "name": "균형형 추천 Build", "recommendedFor": "균형 우선", "summary": "내부 자산과 저장된 현재가를 조합했습니다.", "totalPrice": 1450000, "confidence": "HIGH", "items": [], "warnings": [], "toolResults": [{ "tool": "compatibility", "status": "PASS", "confidence": "HIGH", "summary": "CPU, 메인보드, RAM 호환성이 맞습니다." }], "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"], "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "agentSummary": "추천 근거 요약", "changeableCategories": ["GPU", "RAM"] }], "warnings": [], "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"], "toolResults": [] }` | `requirements`, `builds`, `build_items`, `agent_sessions`, `tool_invocations`, `rag_evidence` |
| `POST` | `/api/ai/build-chat` | USER | 3번 | `{ "message": "GPU 빼줘", "currentBuilds": [], "appliedPartPreferences": [], "currentQuoteDraft": { "items": [] } }` | `{ "answerType": "BUDGET", "message": "LLM/RAG로 조건을 분석했습니다.", "builds": [{ "id": "ai-engine-2-qhd-균형-추천-조합", "tier": "balanced", "title": "QHD 균형 추천 조합", "totalPrice": 1980000, "items": [{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "quantity": 1, "price": 890000 }], "toolResults": [{ "tool": "price", "status": "PASS", "confidence": "HIGH", "summary": "저장된 현재가 기준 예산 안에 들어옵니다." }], "warnings": [], "confidence": "HIGH", "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"] }], "partRecommendation": null, "actions": [{ "id": "draft-action-remove-draft-part-gpu", "type": "REMOVE_DRAFT_PART", "label": "GPU 빼기", "description": "견적에서 GPU를 제거합니다.", "payload": { "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU" }, "requiresConfirmation": true }], "warnings": [], "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"], "agentSessionId": null }` | `parts`, `compatibility_rules`, `benchmark_summaries`, `price_snapshots`, `rag_evidence` |
| `GET` | `/api/builds/{id}` | USER | 1번 | - | `{ "id": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd", "name": "QHD Gaming Build", "summary": "내부 자산 기반 추천", "totalPrice": 1450000, "confidence": "HIGH", "items": [{ "category": "GPU", "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "name": "RTX 5070", "manufacturer": "NVIDIA", "price": 850000, "attributes": {} }], "warnings": [], "toolResults": [], "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"], "agentSummary": "추천 근거 요약", "changeableCategories": ["GPU", "RAM"], "createdAt": "2026-06-29T10:20:00Z" }` | `builds`, `build_items`, `parts` |
| `GET` | `/api/builds/history` | USER | 1번 | `?page=0&size=20` | `{ "items": [{ "id": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd", "name": "QHD Gaming Build", "totalPrice": 1450000, "confidence": "HIGH", "createdAt": "2026-06-29T10:20:00Z" }], "page": 0, "size": 20, "total": 1 }` | `requirements`, `builds` |
| `POST` | `/api/builds/{id}/change-part` | USER | 1번 | `{ "category": "GPU", "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11" }` | `{ "buildId": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd", "category": "GPU", "previousPartId": "0bb1f994-5e1f-4dc4-b55c-c615130e1bb4", "selectedPartId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "totalPrice": 1500000, "diff": { "price": 50000 }, "beforeBuild": {}, "afterBuild": { "id": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd", "name": "QHD Gaming Build", "totalPrice": 1500000, "items": [] }, "diffRows": [{ "label": "GPU", "before": "RTX 5060", "after": "RTX 5070", "diff": "+50,000원", "status": "PASS" }], "toolResults": [], "agentSummary": "변경 비교 요약", "warnings": [] }` | `builds`, `build_items`, `parts` |

`RequirementDto.parsedContext`는 추천 품질을 위해 내부 해석 필드를 포함할 수 있다. `performanceTier`는 `ENTHUSIAST | PERFORMANCE | STANDARD`, `budgetPolicy`는 `USER_BUDGET | OPEN_BUDGET | UNSPECIFIED` 중 하나다. `ragSourceIds`와 `parseEvidenceSummary`는 요구사항 파싱에 사용된 RAG 근거 묶음을 추적하기 위한 내부 필드다. 예산 없는 최고급 의도는 RAG 정책 근거를 사용해 `performanceTier=ENTHUSIAST`, `budgetPolicy=OPEN_BUDGET`으로 저장할 수 있고, 추천 결과는 첫 카드부터 끝판왕/하이엔드 체급으로 정렬한다. 사용자가 예산을 명시한 경우에는 예산을 우선한다. 예산이 없는 일반/성능 목표 요청은 기본 예산으로 저장하지 않고 `budget=null`, `budgetPolicy=UNSPECIFIED`로 둔다.

명시 부품 조건은 별도 하드 제약으로 보존한다. 예를 들어 “RTX 5090 글카 들어간 PC”는 `parsedContext.requiredGpuClasses=["RTX_5090"]`, `parsedContext.hardConstraintPolicy="MUST_INCLUDE"`로 저장한다. 예산이 없으면 `OPEN_BUDGET` 추천으로 처리하고, 예산이 있으면 `USER_BUDGET`을 유지하되 추천기는 RTX 5090을 임의로 RTX 5080/5070으로 낮추지 않는다. 이 경우 총액이 예산을 넘으면 `warnings[].code="HARD_CONSTRAINT_OVER_BUDGET"`로 표시한다.

`POST /api/builds/recommend` transaction 경계:

1. request 검증과 requirement 소유권 확인은 transaction 밖에서 수행한다. 소유자가 아니면 `404 NOT_FOUND`다.
2. Agent/Tool/RAG 계산은 DB transaction 밖에서 수행한다. 이 단계에서 외부 호출이 실패해도 fallback 추천이 가능하면 `warnings`를 포함해 계속 진행한다.
3. 최종 저장은 하나의 DB transaction으로 묶는다. 포함 범위는 `agent_sessions` 생성, `builds` 저장, `build_items` 저장, `rag_evidence` 저장, `tool_invocations` 저장, `agent_sessions.status` 최종 갱신이다.
4. 위 최종 저장 transaction에서 하나라도 실패하면 전체 rollback하고 `500 INTERNAL_ERROR`를 반환한다. 이 경우 build는 유지하지 않고 evidence만 실패 처리하지도 않는다.
5. Tool/RAG 계산 결과가 없어 fallback build를 저장하는 경우에는 `agent_sessions.state_timeline`에 `FALLBACK_READY -> SUCCEEDED`를 남기고, response의 `warnings`에 fallback 사유를 포함한다.
6. response에 포함된 `agentSessionId`, `recommendations[].id`, `evidenceIds`는 모두 같은 최종 저장 transaction에서 commit된 `public_id`다.
7. 1번 추천 API는 `agent_sessions`, `tool_invocations`, `rag_evidence`를 직접 조작하지 않고, 3번이 제공하는 내부 Agent trace service를 호출해 추적 데이터를 기록한다.

`POST /api/ai/build-chat` v1 정책:

- LLM/RAG 필수 API다. `OPENAI_API_KEY`가 없으면 `428 PRECONDITION_REQUIRED`를 반환하고 deterministic fallback을 만들지 않는다.
- 내부 live benchmark는 optional `X-BuildGraph-AI-Profile` header를 사용할 수 있다. 지원값은 `BUILD_CHAT_FAST`, `BUILD_CHAT_54_FAST`, `BUILD_CHAT_54_MINI_FAST`이며, 사용자 화면은 이 header를 보내지 않는다.
- 기본 Build Chat profile은 `BUILD_CHAT_DEFAULT_PROFILE`이며 실측 결과 기준 기본값은 `BUILD_CHAT_54_MINI_FAST`다. rollback이 필요하면 env에서 `BUILD_CHAT_DEFAULT_PROFILE=BUILD_CHAT_FAST`로 되돌린다. `gpt-5.4`, `gpt-5.4-mini` 후보는 같은 응답 shape를 유지한다.
- LLM은 intent, 예산, 용도, 해상도, 부품 카테고리, 하드 제약만 구조화한다. 부품 ID, 가격, FPS, 상품명은 서버가 내부 DB에서만 선택한다.
- 전체 견적 요청은 `parts.status=ACTIVE`인 실제 부품만 사용해 AI build 후보 3개를 반환한다.
- 부품 질문은 LLM이 판단한 카테고리에서 후보 3개를 실제 `parts.price`와 내부 자산 기준으로 반환하고, `currentBuilds`의 해당 카테고리를 서버가 다시 조회한 partId 가격으로 교체한다.
- 부품 질문에 `currentBuilds`가 없거나 복원할 수 없으면 기본 예산 build를 새로 만들지 않는다. 이 경우 `builds=[]`와 `partRecommendation`만 반환하고, 프론트는 기존 세션 build를 유지한다.
- 서버는 client가 보낸 part 이름/가격을 신뢰하지 않는다. `currentBuilds[].items[].partId`를 기준으로 DB에서 현재 `parts.price`와 attributes를 다시 읽는다.
- 각 AI build에는 기존 Tool 검증 결과를 `toolResults`로 포함한다. Tool 실패 시 build 자체는 반환하되 `warnings`에 실패 사유를 넣는다.
- AI build는 대화용 DTO이며 `builds/build_items`에 저장하지 않는다. 대화 이력 저장은 프론트 `sessionStorage` 범위다.
- `currentQuoteDraft`가 전달된 `/self-quote` 챗봇 요청은 `actions`로 변경안만 반환한다. AI API는 `quote_drafts`, `quote_draft_items`를 직접 쓰지 않고, 프론트가 사용자 확인 후 기존 quote draft API를 호출한다.
- action 종류는 `ADD_PART_TO_DRAFT`, `REPLACE_DRAFT_PART`, `REMOVE_DRAFT_PART`, `UPDATE_DRAFT_QUANTITY`, `ASK_FOLLOW_UP`이다. 모든 저장 action은 `{ partId, category, quantity?, source }`를 payload에 담고 `requiresConfirmation=true`로 내려간다.
- 위 action enum은 공개 `/api/ai/build-chat` 응답 기준이다. 내부 `AiChatEngine`은 `OPEN_SELF_QUOTE`, `ADD_BUILD_TO_DRAFT`, `CREATE_PRICE_ALERT` 같은 더 넓은 action을 가질 수 있지만, 현재 프론트 Build Chat 응답에는 견적초안 변경 확인용 action subset만 내려간다.
- AI build의 사용자 표시 `totalPrice`는 항상 `items[].price * items[].quantity` 합계다. LLM/엔진의 `estimatedTotalPrice`는 내부 참고값이며 홈 추천 카드와 견적초안 장바구니 총액의 기준으로 사용하지 않는다.
- LLM JSON 계약 위반이나 외부 LLM 호출 실패는 `502 BAD_GATEWAY`로 반환한다.
- “RTX 5090 글카 들어간 PC” 같은 명시 부품 조건은 `requiredGpuClasses`와 `hardConstraintPolicy=MUST_INCLUDE`로 보존한다. 예산이 부족하면 부품을 낮추지 않고 예산 초과 warning을 반환한다.

### Quote Draft

수동 견적초안은 상품 상세/셀프 견적 화면에서 사용자가 직접 담은 부품 목록이다. AI 추천 결과인 `builds/build_items`와 분리한다.

| Method | Path | Auth | Owner | Request 예시 | Response 예시 | 관련 DB table |
|---|---|---|---|---|---|---|
| `GET` | `/api/quote-drafts/current` | USER | 2번 | - | `{ "id": null, "status": "EMPTY", "name": "셀프 견적", "items": [], "totalPrice": 0, "itemCount": 0 }` | `quote_drafts`, `quote_draft_items`, `parts` |
| `PUT` | `/api/quote-drafts/current/apply-ai-build` | USER | 2번 | `{ "buildId": "ai-2000000-balanced", "conflictPolicy": "REPLACE", "items": [{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "quantity": 1 }] }` | `QuoteDraftDto` | `quote_drafts`, `quote_draft_items`, `parts` |
| `PUT` | `/api/quote-drafts/current/items/{partId}` | USER | 2번 | `{ "quantity": 2 }` | `{ "id": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd", "status": "ACTIVE", "items": [{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "RAM", "name": "DDR5 32GB", "quantity": 2, "currentPrice": 120000, "lineTotal": 240000 }], "totalPrice": 240000, "itemCount": 2 }` | `quote_drafts`, `quote_draft_items`, `parts` |
| `PATCH` | `/api/quote-drafts/current/items/{partId}` | USER | 2번 | `{ "quantity": 1 }` | `QuoteDraftDto` | `quote_draft_items`, `parts` |
| `DELETE` | `/api/quote-drafts/current/items/{partId}` | USER | 2번 | - | `QuoteDraftDto` | `quote_draft_items`, `parts` |

수동 견적초안 규칙:

- `GET /api/quote-drafts/current`는 active draft가 없어도 DB row를 만들지 않고 `status=EMPTY` DTO를 반환한다.
- `PUT item`은 active draft가 없으면 생성한 뒤 item을 저장한다.
- `PUT /api/quote-drafts/current/apply-ai-build`는 `conflictPolicy=REPLACE`만 허용하고 하나의 transaction으로 현재 active 견적초안 항목 전체를 AI 추천 조합으로 교체한다.
- AI batch 적용은 요청에 포함된 카테고리의 기존 active item을 먼저 제거한 뒤 새 AI item을 삽입한다. `RAM`, `STORAGE`도 이 API에서는 append가 아니라 AI 조합 기준으로 교체한다.
- AI batch 적용은 모든 `partId`를 먼저 검증한다. 하나라도 존재하지 않거나 ACTIVE가 아니면 전체 적용을 실패시키고 기존 draft를 변경하지 않는다.
- `CPU`, `GPU`, `MOTHERBOARD`, `PSU`, `CASE`, `COOLER`는 같은 category의 다른 부품을 담으면 기존 active item을 교체한다.
- `RAM`, `STORAGE`는 서로 다른 상품을 여러 개 담을 수 있다. 같은 상품을 다시 담으면 row를 추가하지 않고 `quantity`를 갱신한다.
- 단일 구성 카테고리의 `quantity`는 1만 허용한다. `RAM`, `STORAGE`의 `quantity`는 1~9만 허용한다.
- `totalPrice`와 `lineTotal`은 현재 `parts.price` 기준으로 계산한다. `unitPriceAtAdd`는 담은 시점 추적용이다.

### Parts/Price

| Method | Path | Auth | Owner | Request 예시 | Response 예시 | 관련 DB table |
|---|---|---|---|---|---|---|
| `GET` | `/api/parts` | USER | 2번 | `?category=GPU&q=5070&manufacturer=NVIDIA&status=ACTIVE&minPrice=500000&maxPrice=1300000&page=0&size=20&sort=price_desc` | `{ "items": [{ "id": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "name": "GeForce RTX 5070", "manufacturer": "NVIDIA", "price": 960000, "status": "ACTIVE", "attributes": { "wattage": 250 }, "latestPriceSource": "MANUAL_CURRENT_LINEUP", "externalOffer": { "imageUrl": "https://...", "supplierName": "Naver Store", "offerUrl": "https://...", "lowPrice": 950000, "source": "NAVER_SHOPPING_SEARCH", "refreshedAt": "2026-06-29T10:25:00Z" } }], "page": 0, "size": 20, "total": 1 }` | `parts`, `price_snapshots`, `benchmark_summaries`, `part_external_offers` |
| `GET` | `/api/parts/{id}` | USER | 2번 | - | `{ "id": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "name": "GeForce RTX 5070", "manufacturer": "NVIDIA", "price": 960000, "status": "ACTIVE", "attributes": { "wattage": 250, "lengthMm": 304 }, "benchmarkSummary": { "summary": "GPU category-local normalized score 78.0 for gaming_ai_creator. Use as recommendation evidence, not exact FPS or render-time guarantee.", "score": 78.0 }, "latestPriceSource": "MANUAL_CURRENT_LINEUP", "externalOffer": null }` | `parts`, `price_snapshots`, `benchmark_summaries`, `part_external_offers` |
| `GET` | `/api/parts/{id}/price-history` | USER | 2번 | `?days=3650&limit=120` 또는 `?source=DANAWA_PRICE_TREND` | `{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "partName": "GeForce RTX 5070", "currentPrice": 960000, "days": 3650, "source": null, "items": [{ "price": 950000, "source": "NAVER_SHOPPING_SEARCH", "collectedAt": "2026-06-29T10:25:00Z" }, { "price": 948000, "source": "DANAWA_BACKUP", "collectedAt": "2026-06-30T04:30:00Z" }, { "price": 910000, "source": "DANAWA_PRICE_TREND", "collectedAt": "2026-01-01T00:00:00+09:00" }], "summary": { "sampleCount": 3, "currentPrice": 960000, "minPrice": 910000, "maxPrice": 950000, "changeAmount": 40000, "changeRatePercent": 4.4 } }` | `parts`, `price_snapshots` |
| `GET` | `/api/price-alerts` | USER | 2번 | `?page=0&size=20` | `{ "items": [{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "partName": "RTX 4070", "targetPrice": 700000, "currentPrice": 850000, "status": "ACTIVE", "createdAt": "2026-06-29T10:25:00Z" }], "page": 0, "size": 20, "total": 1 }` | `price_alerts`, `parts`, `users` |
| `POST` | `/api/price-alerts` | USER | 2번 | `{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "targetPrice": 700000 }` | `{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "partName": "RTX 4070", "targetPrice": 700000, "currentPrice": 850000, "status": "ACTIVE", "createdAt": "2026-06-29T10:25:00Z" }` | `price_alerts`, `parts`, `users` |
| `GET` | `/api/admin/price-jobs` | ADMIN | 2번 | `?page=0&size=20` | `{ "items": [{ "id": "8d4b2d5b-7d39-4f8a-8195-bf32b9c5f61e", "status": "SUCCEEDED", "requestedBy": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "startedAt": "2026-06-29T10:00:00Z", "finishedAt": "2026-06-29T10:01:00Z", "errorSummary": null }], "page": 0, "size": 20, "total": 1 }` | `price_jobs` |
| `POST` | `/api/admin/price-jobs/run` | ADMIN | 2번 | `{ "source": "manual" }` | `{ "id": "8d4b2d5b-7d39-4f8a-8195-bf32b9c5f61e", "status": "QUEUED", "requestedBy": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "createdAt": "2026-06-29T10:30:00Z" }` | `price_jobs`, `price_snapshots` |
| `GET` | `/api/admin/parts` | ADMIN | 2번 | `?category=GPU&status=ACTIVE&q=5090&includeDeleted=false&page=0&size=20&sort=updated_desc` | `{ "items": [{ "id": "0e9...", "category": "GPU", "name": "RTX 5090", "manufacturer": "ASUS", "price": 3980000, "status": "ACTIVE", "attributes": { "toolReady": true }, "toolReady": true, "missingRequiredFields": [], "externalOffer": { "source": "NAVER_SHOPPING_SEARCH" } }], "page": 0, "size": 20, "total": 1 }` | `parts`, `part_external_offers` |
| `POST` | `/api/admin/parts` | ADMIN | 2번 | `{ "category": "GPU", "name": "RTX 5090 신규 후보", "manufacturer": "ASUS", "price": 3980000, "attributes": { "gpuClass": "RTX_5090" } }` | `AdminPartDto`, 기본 `status=INACTIVE` | `parts`, `admin_audit_logs` |
| `GET` | `/api/admin/parts/{id}` | ADMIN | 2번 | - | `AdminPartDto` | `parts`, `part_external_offers` |
| `PATCH` | `/api/admin/parts/{id}` | ADMIN | 2번 | `{ "status": "ACTIVE", "attributes": { "vramGb": 32, "lengthMm": 357, "requiredSystemPowerW": 1000 } }` | `AdminPartDto` | `parts`, `admin_audit_logs` |
| `DELETE` | `/api/admin/parts/{id}` | ADMIN | 2번 | - | `{ "id": "0e9...", "deleted": true }` | `parts`, `admin_audit_logs` |
| `POST` | `/api/admin/parts/{id}/restore` | ADMIN | 2번 | - | `AdminPartDto`, 복구 후 `status=INACTIVE` | `parts`, `admin_audit_logs` |
| `POST` | `/api/admin/parts/{id}/manual-price` | ADMIN | 2번 | `{ "price": 3970000, "reason": "관리자 대표가 보정" }` | `AdminPartDto` | `parts`, `price_snapshots`, `admin_audit_logs` |
| `PATCH` | `/api/admin/parts/{id}/external-offer` | ADMIN | 2번 | `{ "supplierName": "수동 공급처", "offerUrl": "https://...", "lowPrice": 3970000, "imageUrl": "https://..." }` | `AdminPartDto` | `part_external_offers`, `admin_audit_logs` |
| `POST` | `/api/admin/parts/catalog/refresh` | ADMIN | 2번 | `?category=GPU&limitPerQuery=3&publish=true` | `{ "configured": true, "jobId": "8d4b2d5b-7d39-4f8a-8195-bf32b9c5f61e", "category": "GPU", "queryCount": 24, "limitPerQuery": 3, "publish": true, "attempted": 72, "discovered": 70, "published": 45, "failed": 2 }` | `part_catalog_refresh_jobs`, `part_catalog_candidates`, `parts`, `part_external_offers` |
| `POST` | `/api/admin/parts/external-offers/refresh` | ADMIN | 2번 | `?category=GPU&limit=20&force=false` | `{ "configured": true, "category": "GPU", "limit": 20, "force": false, "attempted": 7, "updated": 7, "skipped": 0, "failed": 0 }` | `parts`, `part_external_offers`, `price_snapshots` |
| `POST` | `/api/admin/parts/danawa-price-snapshots/refresh` | ADMIN | 2번 | `?category=GPU&limit=20&force=false` | `{ "configured": true, "category": "GPU", "limit": 20, "force": false, "attempted": 7, "collected": 6, "skipped": 1, "failed": 0 }` | `parts`, `part_external_offers`, `price_snapshots` |
| `POST` | `/api/admin/parts/danawa-price-trends/refresh` | ADMIN | 2번 | `?category=GPU&limit=20&months=6&force=false` | `{ "configured": true, "category": "GPU", "months": 6, "limit": 20, "force": false, "attempted": 7, "collectedParts": 5, "collectedPoints": 35, "skipped": 1, "missing": 1, "failed": 0 }` | `parts`, `part_external_offers`, `price_snapshots` |
| `GET` | `/api/admin/manufacturer-sources` | ADMIN | 2번 | `?enabled=true&status=ACTIVE&category=GPU&includeDeleted=true` | `{ "items": [{ "id": "2f9d...", "manufacturer": "ASUS", "categoryScope": "GPU", "sourceType": "NEWS", "sourceUrl": "https://...", "enabled": true, "pollIntervalMinutes": 1440, "status": "ACTIVE", "deletedAt": null }] }` | `manufacturer_sources` |
| `POST` | `/api/admin/manufacturer-sources` | ADMIN | 2번 | `{ "manufacturer": "ASUS", "categoryScope": "GPU", "sourceType": "NEWS", "sourceUrl": "https://www.asus.com/news/", "enabled": true, "pollIntervalMinutes": 1440, "parserConfig": {} }` | `{ "id": "2f9d...", "manufacturer": "ASUS", "categoryScope": "GPU", "sourceType": "NEWS", "sourceUrl": "https://www.asus.com/news/", "enabled": true, "status": "ACTIVE" }` | `manufacturer_sources`, `admin_audit_logs` |
| `GET` | `/api/admin/manufacturer-sources/{id}` | ADMIN | 2번 | `?includeDeleted=true` | `ManufacturerSourceDto` | `manufacturer_sources` |
| `PATCH` | `/api/admin/manufacturer-sources/{id}` | ADMIN | 2번 | `{ "enabled": false, "status": "PAUSED", "parserConfig": { "itemSelector": ".news-card" } }` | `{ "id": "2f9d...", "enabled": false, "status": "PAUSED", "updatedAt": "2026-07-01T09:00:00Z" }` | `manufacturer_sources`, `admin_audit_logs` |
| `DELETE` | `/api/admin/manufacturer-sources/{id}` | ADMIN | 2번 | - | `{ "id": "2f9d...", "deleted": true }` | `manufacturer_sources`, `admin_audit_logs` |
| `POST` | `/api/admin/manufacturer-sources/{id}/restore` | ADMIN | 2번 | - | `ManufacturerSourceDto`, 복구 후 `enabled=false`, `status=PAUSED` | `manufacturer_sources`, `admin_audit_logs` |
| `POST` | `/api/admin/manufacturer-sources/{id}/scan` | ADMIN | 2번 | `?limit=20&createCandidates=true` | `{ "sourceId": "2f9d...", "failed": false, "unchanged": false, "parsedPosts": 5, "newPosts": 2, "productPosts": 1, "createdCandidates": 1 }` | `manufacturer_sources`, `manufacturer_posts`, `part_catalog_refresh_jobs`, `part_catalog_candidates` |
| `POST` | `/api/admin/manufacturer-sources/scan` | ADMIN | 2번 | `?limitPerSource=20&createCandidates=true` | `{ "scannedSources": 8, "newPosts": 4, "createdCandidates": 2, "failedSources": 1, "results": [] }` | `manufacturer_sources`, `manufacturer_posts`, `part_catalog_refresh_jobs`, `part_catalog_candidates` |
| `GET` | `/api/admin/manufacturer-posts` | ADMIN | 2번 | `?status=PRODUCT_CANDIDATE&category=GPU&includeDeleted=true&page=0&size=20` | `{ "items": [{ "id": "8f3...", "manufacturer": "ASUS", "externalUrl": "https://...", "title": "ASUS launches ...", "classificationStatus": "PRODUCT_CANDIDATE", "detectedCategory": "GPU", "catalogCandidateId": "9a1...", "deletedAt": null }], "page": 0, "size": 20, "total": 1 }` | `manufacturer_posts`, `manufacturer_sources`, `part_catalog_candidates` |
| `POST` | `/api/admin/manufacturer-posts` | ADMIN | 2번 | `{ "sourceId": "2f9d...", "externalUrl": "https://www.asus.com/news/new-gpu", "title": "ASUS launches RTX 5090", "classificationStatus": "PRODUCT_CANDIDATE", "detectedCategory": "GPU", "detectedProductName": "RTX 5090" }` | `ManufacturerPostDto` | `manufacturer_posts`, `admin_audit_logs` |
| `GET` | `/api/admin/manufacturer-posts/{id}` | ADMIN | 2번 | `?includeDeleted=true` | `ManufacturerPostDto` | `manufacturer_posts`, `manufacturer_sources`, `part_catalog_candidates` |
| `PATCH` | `/api/admin/manufacturer-posts/{id}` | ADMIN | 2번 | `{ "classificationStatus": "IGNORED", "detectedCategory": null, "detectedProductName": null }` | `ManufacturerPostDto` | `manufacturer_posts`, `admin_audit_logs` |
| `DELETE` | `/api/admin/manufacturer-posts/{id}` | ADMIN | 2번 | - | `{ "id": "8f3...", "deleted": true }` | `manufacturer_posts`, `admin_audit_logs` |
| `POST` | `/api/admin/manufacturer-posts/{id}/restore` | ADMIN | 2번 | - | `ManufacturerPostDto` | `manufacturer_posts`, `admin_audit_logs` |
| `POST` | `/api/admin/manufacturer-posts/{id}/create-candidate` | ADMIN | 2번 | - | `{ "configured": true, "created": true, "candidateId": "9a1...", "title": "ASUS RTX 5090 ...", "lowPrice": 3980000 }` | `manufacturer_posts`, `part_catalog_refresh_jobs`, `part_catalog_candidates` |
| `POST` | `/api/admin/manufacturer-posts/{id}/ai-asset-draft` | ADMIN | 2번 | - | `{ "postId": "8f3...", "classificationStatus": "PRODUCT_CANDIDATE", "detectedCategory": "GPU", "detectedProductName": "RTX 5090", "candidateId": "9a1...", "partId": "0e9...", "partStatus": "INACTIVE", "messages": ["AI 분류", "INACTIVE 초안 생성"] }` | `manufacturer_posts`, `part_catalog_refresh_jobs`, `part_catalog_candidates`, `parts`, `part_external_offers`, `price_snapshots`, `admin_audit_logs` |
| `GET` | `/api/admin/part-catalog-candidates` | ADMIN | 2번 | `?status=DISCOVERED&source=MANUFACTURER_RELEASE_NAVER_SEARCH&includeDeleted=true&page=0&size=20` | `{ "items": [{ "id": "9a1...", "source": "MANUFACTURER_RELEASE_NAVER_SEARCH", "category": "GPU", "title": "ASUS RTX 5090 ...", "candidateStatus": "DISCOVERED", "lowPrice": 3980000, "deletedAt": null }], "page": 0, "size": 20, "total": 1 }` | `part_catalog_candidates`, `parts` |
| `GET` | `/api/admin/part-catalog-candidates/{id}` | ADMIN | 2번 | `?includeDeleted=true` | `PartCatalogCandidateDto` | `part_catalog_candidates`, `parts` |
| `PATCH` | `/api/admin/part-catalog-candidates/{id}` | ADMIN | 2번 | `{ "title": "ASUS RTX 5090", "manufacturerGuess": "ASUS", "lowPrice": 3980000, "offerUrl": "https://..." }` | `PartCatalogCandidateDto` | `part_catalog_candidates`, `admin_audit_logs` |
| `DELETE` | `/api/admin/part-catalog-candidates/{id}` | ADMIN | 2번 | - | `{ "id": "9a1...", "deleted": true }` | `part_catalog_candidates`, `admin_audit_logs` |
| `POST` | `/api/admin/part-catalog-candidates/{id}/restore` | ADMIN | 2번 | - | `PartCatalogCandidateDto` | `part_catalog_candidates`, `admin_audit_logs` |
| `POST` | `/api/admin/part-catalog-candidates/{id}/approve` | ADMIN | 2번 | - | `{ "candidateId": "9a1...", "publishedPartId": "0e9...", "created": true, "partStatus": "INACTIVE", "status": "PUBLISHED" }` | `part_catalog_candidates`, `parts`, `part_external_offers`, `price_snapshots`, `admin_audit_logs` |
| `POST` | `/api/admin/part-catalog-candidates/{id}/reject` | ADMIN | 2번 | `{ "reason": "공식 신제품이 아닌 이벤트 게시글" }` | `{ "candidateId": "9a1...", "status": "REJECTED", "reason": "공식 신제품이 아닌 이벤트 게시글" }` | `part_catalog_candidates`, `admin_audit_logs` |
| `POST` | `/api/admin/part-catalog-candidates/{id}/refresh-offers` | ADMIN | 2번 | - | `{ "configured": true, "candidateId": "9a1...", "updated": true, "attempted": 1, "title": "ASUS RTX 5090 ...", "lowPrice": 3980000 }` | `part_catalog_candidates` |

`POST /api/price-snapshots/collect`는 공개 API가 아니다. 가격 스냅샷 생성은 `/api/admin/price-jobs/run` 뒤의 내부 service 처리다.

`POST /api/price-alerts`는 같은 사용자, 같은 `partId`, 같은 `targetPrice`의 `ACTIVE` 알림이 이미 있으면 `409 DUPLICATE_RESOURCE`를 반환한다.

`POST /api/admin/price-jobs/run`은 `price_jobs.status IN ('QUEUED', 'RUNNING')`인 row가 하나라도 있으면 새 job을 만들지 않고 `409 CONFLICT_STATE`를 반환한다.

부품 검색 정렬은 `category`, `price_asc`, `price_desc`, `name`만 허용한다. `q`는 `parts.name`, `parts.manufacturer`, `parts.attributes`를 대상으로 검색한다.

`GET /api/parts`에서 `status`를 생략하면 쇼핑몰 기본 노출 기준인 `ACTIVE`만 반환한다. 구형 seed나 교체 후보 보관용 자산은 `status=INACTIVE` 또는 `status=DISCONTINUED`를 명시해 조회한다.

관리자 부품 CRUD는 `/api/admin/parts`를 사용한다. 수동 생성은 항상 `INACTIVE` 초안으로 시작하고, `ACTIVE` 전환은 카테고리별 Tool 필수 스펙이 모두 있을 때만 허용한다. 서버는 저장 시 `attributes.toolReady`를 validator 결과로 계산하며 관리자가 직접 토글하지 않는다. 누락이 있으면 `400 VALIDATION_ERROR`와 누락 필드 목록을 반환한다.

삭제는 soft delete다. `DELETE /api/admin/parts/{id}`는 `parts.deleted_at`만 채우며 사용자 `/api/parts`, 추천, Tool 대상에서 제외된다. `POST /api/admin/parts/{id}/restore`는 삭제를 해제하고 `status=INACTIVE`로 복구한다.

대표 가격 수동 보정은 `POST /api/admin/parts/{id}/manual-price`로만 수행한다. 이 API는 하나의 transaction에서 `parts.price`를 갱신하고 `price_snapshots.source=ADMIN_MANUAL` 이력을 남긴다. `PATCH /api/admin/parts/{id}/external-offer`는 대표 offer 후보만 수정하며 `parts.price`를 직접 바꾸지 않는다. 생성, 수정, 상태 변경, 가격 보정, offer 보정, 삭제, 복구는 `admin_audit_logs`에 기록한다.

외부 가격 수집 백업은 별도 public 사용자 API를 만들지 않는다. 현재 단계에서는 `price_snapshots.source = "DANAWA_BACKUP"` 또는 최신 라인업 수동 seed용 `MANUAL_CURRENT_LINEUP`, `price_snapshots.raw_payload`, `parts.attributes.externalSources`에 키워드와 source metadata를 저장한다. 다나와 백업 수집은 관리자 전용 `POST /api/admin/parts/danawa-price-snapshots/refresh`와 비활성 기본 스케줄러로만 실행한다.

네이버 쇼핑 검색 API 키가 설정된 환경에서도 `/api/parts`와 `/api/parts/{id}`는 외부 API를 직접 호출하지 않는다. 내부 자산 최신화는 `POST /api/admin/parts/catalog/refresh`가 담당한다. 이 API는 카테고리별 query pack을 돌려 `part_catalog_candidates`에 후보를 저장하고, `publish=true`일 때 검증 가능한 후보를 `parts`에 게시한다. 사용자 화면은 저장된 `parts`만 읽는다.

상품 사진/공급업체/현재가 보강은 `POST /api/admin/parts/external-offers/refresh`가 담당한다. 검색 결과는 `part_external_offers`에 저장하고, `low_price`가 있으면 `parts.price`와 `price_snapshots`에도 같은 가격을 반영한다. 캐시가 없거나 갱신 실패 시 `externalOffer`는 `null`이다. 네이버 API 키는 프론트로 전달하지 않고 API 서버 환경변수로만 관리한다. `force=true`가 없으면 최근 1일 안에 갱신된 상품은 재호출하지 않는다. 서버 자동 갱신은 기본값 기준 한국시간 매일 04:00에 같은 1일 기준으로 실행된다.

다나와 백업 갱신은 `POST /api/admin/parts/danawa-price-snapshots/refresh`가 담당한다. 이 작업은 robots 정책을 우회하지 않는 공개 검색 페이지 요청만 사용하고, 로그인/캡차/비공개 페이지 우회는 하지 않는다. 수집 성공 시 `price_snapshots.source = DANAWA_BACKUP` 이력과 `part_external_offers.source = DANAWA_BACKUP` 백업 row를 저장하지만, 쇼핑몰 대표 현재가인 `parts.price`는 변경하지 않는다. 기본 스케줄러는 `PART_DANAWA_REFRESH_ENABLED=false`로 꺼져 있으며, 데모/관리자 판단에 따라 1일 단위로 실행한다.

다나와 월별 가격 추이는 `POST /api/admin/parts/danawa-price-trends/refresh`가 담당한다. 이 작업은 `part_external_offers.source = DANAWA_BACKUP`의 상품 상세 URL을 우선 사용하고, 상세 URL이 없거나 검색 URL이면 다나와 공개 검색 결과에서 `prod.danawa.com/info/?pcode=...` 상세 URL을 찾는다. 상세 페이지가 사용하는 공개 `getProductPriceList.ajax.php` 응답의 6/12/24개월 그래프 포인트만 `price_snapshots.source = DANAWA_PRICE_TREND`로 저장한다. 월별 포인트는 해당 월 1일, 현재가는 현재일 12:00 KST 기준으로 저장하며, `parts.price`는 변경하지 않는다. 기본 스케줄러는 `PART_DANAWA_TREND_REFRESH_ENABLED=false`, 기본 cron은 매월 1일 05:30 KST다.

상품별 가격변동 추이는 `GET /api/parts/{id}/price-history`로 조회한다. 이 API는 저장된 `price_snapshots`만 읽고 외부 검색 API를 실시간 호출하지 않는다. 사용자 화면의 가격변동 그래프는 source를 생략해 네이버 현재가 이력, 다나와 백업 이력, 다나와 월별 추이, 수동 seed 이력을 함께 보여준다. 출처별 비교가 필요하면 `source=NAVER_SHOPPING_SEARCH`, `source=DANAWA_BACKUP`, `source=DANAWA_PRICE_TREND`처럼 필터링한다. 추출 실패 상품은 사용자에게 내부 실패 문구를 노출하지 않고 저장된 이력만 표시한다.

`POST /api/admin/parts/catalog/refresh`의 기본 query pack은 카테고리별로 수십 개 후보를 확보하도록 설계한다. GPU는 RTX 5090/5080/5070 Ti/5070/5060 Ti/5060을 ASUS, MSI, GIGABYTE, ZOTAC, PNY 등 제조사 검색어로 나누고, MOTHERBOARD와 PSU도 최신 소켓/칩셋/ATX 3.1 기준 제조사별 검색어를 사용한다. COOLER는 주요 공랭 듀얼타워와 360mm AIO 제품군을 별도 query pack으로 관리한다.

제조사 신제품 감지 파이프라인은 `manufacturer_sources`를 주기적으로 scan해 새 공식 게시글을 `manufacturer_posts`에 저장한다. scan은 `parts`를 직접 수정하지 않고, 제품 후보로 판정된 게시글만 네이버 쇼핑 검색을 거쳐 `part_catalog_candidates.source = "MANUFACTURER_RELEASE_NAVER_SEARCH"` 상태로 저장한다. source 생성/수정은 공식 제조사 도메인의 `https` URL만 허용한다. 단, Flyway seed로 들어가는 `BuildGraph Demo` source는 로컬 시연용 예외이며 `parserConfig.demo=true`와 localhost URL만 허용한다. 관리자 승인 전 후보는 사용자 화면, 추천, Tool 대상에 노출하지 않는다. `POST /api/admin/part-catalog-candidates/{id}/approve`와 `POST /api/admin/manufacturer-posts/{id}/ai-asset-draft`는 후보를 `parts.status = "INACTIVE"` 초안으로만 생성하며, 최종 `ACTIVE` 전환은 관리자 스펙 검수 후 별도 작업으로 처리한다. 신제품 판정 AI는 공식 게시글 본문을 읽어 게시글 분류, 검색어 생성, 후보 생성 사유 요약, 카테고리별 스펙 초안 추출, INACTIVE 초안화 연결까지만 담당한다. AI가 추출한 스펙은 `part_catalog_candidates.raw_payload.manufacturerRelease.aiSpecAttributes`를 거쳐 연결된 `parts.attributes`에 저장되며, 특정 제품명 하드코딩으로 채우지 않는다. `OPENAI_API_KEY`가 없으면 AI 초안화 API는 `428 PRECONDITION_REQUIRED`를 반환하고 가짜 후보를 만들지 않는다. AI JSON 계약 위반은 `502 UPSTREAM_ERROR`로 처리한다.

`part_catalog_candidates.source_product_key`는 외부 후보 중복 방지를 위한 서버 생성 내부키다. 관리자 후보 보정 화면과 `PATCH /api/admin/part-catalog-candidates/{id}` request는 이 값을 받지 않으며, 서버는 기존 값을 유지하거나 legacy 빈 값이 발견될 때 source/category/title/offerUrl/searchQuery 기반 안정 키로 보정한다.

로컬 시연용 source는 Flyway seed로 `manufacturer = "BuildGraph Demo"`, `enabled = false`, `sourceType = "RSS"` 상태로 1개 제공한다. 이 source는 `/api/demo/manufacturer-release-feed.xml`을 읽는 데모 전용 feed이며 운영 제조사 감시 source가 아니다. `/admin/parts`에서 수동 scan을 실행하면 실제 파이프라인과 동일하게 `manufacturer_posts`를 기록하고, 네이버 쇼핑 API 설정이 있으면 `parserConfig.searchQuery` 기반으로 후보 생성을 시도한다. 네이버 API가 없거나 검색 결과가 부적합하면 게시글만 남기고 후보는 생성하지 않는다.

공식 제조사 감시 source는 Flyway seed로 ASUS, MSI, GIGABYTE, ASRock, ZOTAC, CORSAIR, Cooler Master, LIAN LI, Fractal Design의 공식 뉴스/제품 발표 페이지를 `enabled = true` 상태로 등록한다. 관리자는 `/admin/parts`에서 개별 source scan, `enabled = true`이고 `PAUSED`가 아닌 source 전체 scan, 감지 게시글 확인, 후보 offer 재검색, 후보 승인/거절을 수행한다. 전체 scan도 승인 전에는 `parts`를 직접 만들지 않으며, 후보 승인 후에도 `INACTIVE` 초안으로만 생성한다.

외부 제조사 페이지가 403, timeout, 빈 응답 등으로 실패하면 해당 source는 `manufacturer_sources.status = "ERROR"`와 `errorSummary`로 기록한다. 개별 scan과 전체 scan은 실패를 `failed = true` 또는 `failedSources`로 반환하며, 전체 scan은 나머지 source 처리를 계속한다. robots/캡차/차단 우회는 하지 않는다.

### Tool

Tool API는 직접 check 호출 자체를 `tool_invocations`에 저장하지 않는다. `tool_invocations`에는 Agent/recommend 내부에서 실행된 Tool 호출만 저장한다.

공통 request wrapper:

```json
{
  "buildId": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd",
  "partIds": ["0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11"],
  "context": {
    "category": "GPU"
  }
}
```

공통 response:

```json
{
  "status": "PASS",
  "confidence": "HIGH",
  "summary": "호환됩니다.",
  "details": {
    "checkedPartIds": ["0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11"]
  }
}
```

Tool별 `context`와 `details` shape:

| tool | context JSON shape | details JSON shape |
|---|---|---|
| `compatibility` | `{ "category": "GPU", "existingPartIds": ["0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11"] }` | `{ "checkedPartIds": ["0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11"], "failedRules": [{ "ruleKey": "socket-am5", "message": "..." }] }` |
| `power` | `{ "psuPartId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "estimatedWattage": 520 }` | `{ "estimatedWattage": 520, "recommendedWattage": 650, "headroomPercent": 20 }` |
| `size` | `{ "casePartId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU" }` | `{ "requiredLengthMm": 304, "availableLengthMm": 315, "constraints": ["GPU_LENGTH"] }` |
| `performance` | `{ "usageTags": ["GAMING"], "resolution": "QHD" }` | `{ "score": 92.5, "bottlenecks": ["CPU"], "benchmarkKeys": ["qhd-gaming-rtx4070"] }` |
| `price` | `{ "budget": 1500000, "currentTotalPrice": 1450000 }` | `{ "totalPrice": 1450000, "budget": 1500000, "overBudget": false, "priceDiff": -50000 }` |

| Method | Path | Auth | Owner | Request 예시 | Response 예시 | 관련 DB table |
|---|---|---|---|---|---|---|
| `POST` | `/api/tools/compatibility/check` | USER | 2번 | 공통 wrapper | 공통 Tool response | `parts`, `compatibility_rules` |
| `POST` | `/api/tools/power/check` | USER | 2번 | 공통 wrapper | 공통 Tool response | `parts`, `compatibility_rules` |
| `POST` | `/api/tools/size/check` | USER | 2번 | 공통 wrapper | 공통 Tool response | `parts`, `compatibility_rules` |
| `POST` | `/api/tools/performance/check` | USER | 2번 | 공통 wrapper | 공통 Tool response. `details.cpuBenchmarkScore`, `details.gpuBenchmarkScore`, `details.gameFpsEvidence[]`, `details.benchmarkSource`는 저장된 근거가 있을 때 포함된다. `details.gameFpsEvidenceStatus`는 FPS evidence가 있을 때만 포함한다. `gameFpsEvidence[].match`에는 `gameMatched`, `resolutionMatched`, `gpuClassMatched`, `cpuClassMatched`, `exactGpuPartMatched`, `exactCpuPartMatched`, `evidenceExactness`가 포함된다. FPS 근거는 공개 데이터 기반 참고값이며 정확 FPS 보장이 아니다. 내부 seed 누락 대상은 API 응답이 아니라 `game_fps_coverage_gaps` view로 관리한다. | `parts`, `benchmark_summaries`, `game_fps_benchmarks`, `game_fps_coverage_targets` |
| `POST` | `/api/tools/price/check` | USER | 2번 | 공통 wrapper | 공통 Tool response | `parts`, `price_snapshots` |

### Agent/RAG

| Method | Path | Auth | Owner | Request 예시 | Response 예시 | 관련 DB table |
|---|---|---|---|---|---|---|
| `POST` | `/api/agent/sessions` | USER | 3번 | `{ "requirementId": "2e0f8c9c-8e1c-4d75-94a2-5d6a4977de11", "buildId": null, "asTicketId": null }` | `{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "status": "QUEUED", "summary": null, "stateTimeline": [{ "from": null, "to": "QUEUED", "actor": "USER", "at": "2026-06-29T10:35:00Z" }] }` | `agent_sessions` |
| `POST` | `/api/agent/sessions/{id}/run` | USER | 3번 | `{}` | `{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "status": "RUNNING", "summary": null, "stateTimeline": [{ "from": "QUEUED", "to": "RUNNING", "actor": "SYSTEM", "at": "2026-06-29T10:36:00Z" }] }` | `agent_sessions` |
| `GET` | `/api/agent/sessions/{id}` | USER | 3번 | - | `{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "status": "SUCCEEDED", "summary": "추천이 완료되었습니다.", "stateTimeline": [], "toolInvocationIds": ["4cf44761-e25b-4d5b-bd31-52c13dd9975c"], "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"] }` | `agent_sessions`, `tool_invocations`, `rag_evidence` |
| `GET` | `/api/rag/search` | USER | 3번 | `?q=5090&purpose=REQUIREMENT_PARSE&sourceType=INTERNAL_RULE&limit=3` | `{ "items": [{ "id": "9ebf5278-68aa-42a5-96f4-8ec0f90f0f77", "summary": "명시 GPU 조건은 하드 제약입니다.", "sourceId": "requirement-rule-explicit-gpu-class-hard-constraint", "score": 0.92 }], "page": 0, "size": 3, "total": 1 }` | `rag_evidence` |
| `GET` | `/api/rag/evidence/{id}` | USER | 3번 | - | `{ "id": "9ebf5278-68aa-42a5-96f4-8ec0f90f0f77", "summary": "RTX 4070 QHD 성능 근거", "sourceId": "spec-rtx4070", "metadata": { "sourceType": "PART_SPEC", "title": "RTX 4070 Spec" } }` | `rag_evidence` |
| `GET` | `/api/admin/agent-sessions` | ADMIN | 3번 | `?page=0&size=20` | `{ "items": [{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "status": "RUNNING", "userId": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "createdAt": "2026-06-29T10:35:00Z" }], "page": 0, "size": 20, "total": 1 }` | `agent_sessions` |
| `GET` | `/api/admin/agent-sessions/{id}` | ADMIN | 3번 | - | `{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "status": "SUCCEEDED", "summary": "추천이 완료되었습니다.", "stateTimeline": [], "toolInvocations": [], "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"] }` | `agent_sessions`, `tool_invocations`, `rag_evidence` |
| `GET` | `/api/admin/tool-invocations` | ADMIN | 3번 | `?page=0&size=20` | `{ "items": [{ "id": "4cf44761-e25b-4d5b-bd31-52c13dd9975c", "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "toolName": "compatibility", "status": "PASS", "confidence": "HIGH", "createdAt": "2026-06-29T10:36:10Z" }], "page": 0, "size": 20, "total": 1 }` | `tool_invocations` |
| `GET` | `/api/admin/tool-invocations/{id}` | ADMIN | 3번 | - | `{ "id": "4cf44761-e25b-4d5b-bd31-52c13dd9975c", "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "toolName": "compatibility", "status": "PASS", "confidence": "HIGH", "summary": "호환됩니다.", "requestPayload": {}, "resultPayload": {}, "latencyMs": 120 }` | `tool_invocations` |
| `GET` | `/api/admin/rag-evidence/{id}` | ADMIN | 3번 | - | `{ "id": "9ebf5278-68aa-42a5-96f4-8ec0f90f0f77", "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "sourceId": "spec-rtx4070", "chunkText": "근거 chunk", "summary": "RTX 4070 QHD 성능 근거", "metadata": { "sourceType": "PART_SPEC" }, "score": 0.92 }` | `rag_evidence` |
| `POST` | `/api/admin/rag-embeddings/backfill` | ADMIN | 3번 | `{ "limit": 200 }` | `{ "scanned": 28, "updated": 28, "skipped": 0, "reusableTotal": 28, "embeddedTotal": 28, "embeddingModel": "text-embedding-3-small", "embeddingDimensions": 1536 }` | `rag_evidence` |

RAG 공개 범위:

- 일반 RAG API는 요약 중심이다.
- admin RAG API는 `chunkText`, `metadata`, `score`를 포함할 수 있다.
- `GET /api/rag/search`는 `page=0`, `size=20`, `size<=100` pagination 기준을 따른다. `limit`은 `size` 별칭이며 둘 다 있으면 `size`가 우선한다.
- `GET /api/rag/search`는 `purpose=REQUIREMENT_PARSE|BUILD_RECOMMEND|BUILD_EXPLAIN|AS_ANALYZE`, `sourceType=GUIDE|INTERNAL_RULE|BENCHMARK|PART_SPEC|TROUBLESHOOTING` 필터를 지원한다.
- `agent_session_id`가 없는 `rag_evidence` row는 재사용 지식 청크로 검색 대상에 포함된다.
- Agent 실행 중 선택된 RAG 청크는 세션별 `rag_evidence` row로 복사되어 `evidenceIds`에 노출된다.
- `rag_evidence.embedding`이 있고 `OPENAI_API_KEY`가 설정된 환경에서는 `GET /api/rag/search`와 Agent 내부 검색이 pgvector semantic search를 우선 사용한다. 검색 실패, 키 미설정, embedding 미백필 상태에서는 기존 keyword fallback을 사용한다.
- vector 사용 여부는 전역 `RAG_VECTOR_ENABLED`와 경로별 override로 결정한다. 경로별 값은 `RAG_VECTOR_REQUIREMENT_PARSE_ENABLED`, `RAG_VECTOR_BUILD_RECOMMEND_ENABLED`, `RAG_VECTOR_AS_ANALYZE_ENABLED`, `RAG_VECTOR_PUBLIC_SEARCH_ENABLED`이며 기본값은 전역 값을 상속한다.
- `REQUIREMENT_PARSE`는 요구사항 파싱과 Build Chat 의도 해석, `BUILD_RECOMMEND`/`BUILD_EXPLAIN`은 추천과 변경 근거, `AS_ANALYZE`는 AS Chat 근거, `PUBLIC_SEARCH`는 `/api/rag/search` 관리자/검증 검색에 해당한다.
- 이번 계약은 기본값을 바꾸지 않고 경로별 policy benchmark를 가능하게 하는 목적이다. 운영 기본값 변경은 `docs/reports/rag-retrieval-benchmark-YYYYMMDD.md`와 live benchmark 결과를 근거로 별도 PR에서 판단한다.
- embedding 생성은 Flyway에서 수행하지 않는다. 데모 전 관리자가 `POST /api/admin/rag-embeddings/backfill`을 실행해 reusable RAG chunk를 백필한다.

`POST /api/agent/sessions/{id}/run` 409 조건:

- 해당 세션이 요청 사용자 소유가 아니면 `404 NOT_FOUND`다.
- status가 `QUEUED`가 아니면 실행을 시작하지 않고 `409 CONFLICT_STATE`를 반환한다.
- 특히 `RUNNING`, `RAG_SEARCHED`, `TOOLS_CALLED`, `SUMMARY_READY`, `FALLBACK_READY`는 이미 실행 중인 상태로 본다.
- `SUCCEEDED`, `FAILED`, `CANCELLED`는 완료된 상태이므로 재실행하지 않고 `409 CONFLICT_STATE`를 반환한다.

`POST /api/agent/sessions`는 `requirementId`, `buildId`, `asTicketId` 중 정확히 하나만 non-null이어야 한다. 0개 또는 2개 이상이면 `400 VALIDATION_ERROR`다.

Agent 실행 방식:

- 계약상 `POST /api/agent/sessions/{id}/run`은 실행 시작 API이며 `QUEUED -> RUNNING` 전이를 반환한다.
- 클라이언트는 `POST /run` 응답만으로 완료를 판단하지 않고 `GET /api/agent/sessions/{id}`로 최종 상태와 근거 ID를 조회한다.
- Sprint 1 mock/dev 구현이 내부에서 즉시 완료 상태를 만들어도 public contract의 시작 응답 기준은 `RUNNING`이다.

### AS AI Chat

AS AI Chat은 AS 접수 이후 티켓을 기준으로 동작하는 3번 AI 담당 기능이다. 4번의 `as_tickets`를 읽지만 `cause_candidates`, `upgrade_candidates`, ticket status를 수정하지 않는다. 대화 결과는 `as_chat_sessions`, `as_chat_messages`에 저장하고, 근거 추적은 기존 `agent_sessions`, `rag_evidence`, `tool_invocations`를 사용한다.

| Method | Path | Auth | Owner | Request 예시 | Response 예시 | 관련 DB table |
|---|---|---|---|---|---|---|
| `GET` | `/api/ai/as-chat` | USER | 3번 | `?asTicketId=4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a` | `{ "sessionId": null, "asTicketId": "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a", "ticket": { "id": "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a", "status": "OPEN", "symptom": "게임 중 프레임 급락" }, "model": "gpt-5.4-mini", "messages": [], "evidence": [], "toolResults": [] }` | `as_chat_sessions`, `as_chat_messages`, `as_tickets` |
| `POST` | `/api/ai/as-chat` | USER | 3번 | `{ "asTicketId": "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a", "message": "게임 20분 뒤 프레임이 급락하고 GPU 온도가 95도까지 올라가요" }` | `{ "sessionId": "7c2f8f17-8f18-4d10-bcd1-9d20d1c71a01", "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "assistantMessage": "GPU 온도 상승과 프레임 급락이 함께 나타나므로 냉각/드라이버를 우선 확인해야 합니다.", "messages": [], "causeCandidates": [{ "label": "GPU 과열 가능성", "confidence": "MEDIUM", "reason": "티켓 증상과 thermal RAG 근거가 일치", "evidenceIds": ["4cf44761-e25b-4d5b-bd31-52c13dd9975c"], "toolInvocationIds": ["7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a"] }], "nextActions": [{ "label": "팬과 먼지 확인", "priority": "HIGH", "instruction": "전원을 끄고 흡기/배기 팬과 먼지를 확인하세요.", "evidenceIds": [], "toolInvocationIds": [] }], "escalation": { "required": false, "reason": "추가 로그 확인 전 원격지원 필수는 아님" }, "ticketDraft": { "symptomSummary": "게임 20분 후 프레임 급락", "recommendedLogRequest": "GPU 온도와 frame time 로그" }, "evidence": [], "toolResults": [] }` | `as_chat_sessions`, `as_chat_messages`, `agent_sessions`, `rag_evidence`, `tool_invocations`, `llm_generations`, `as_tickets` |
| `POST` | `/api/ai/as-chat/stream` | USER | 3번 | `{ "asTicketId": "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a", "message": "게임 20분 뒤 프레임이 급락하고 GPU 온도가 95도까지 올라가요" }` | `text/event-stream`: `STARTED`, `RAG_READY`, `TOOLS_READY`, `LLM_RUNNING`, `DONE`, `ERROR`. `DONE` data는 `AsChatResponse` | `as_chat_sessions`, `as_chat_messages`, `agent_sessions`, `rag_evidence`, `tool_invocations`, `llm_generations`, `as_tickets` |

AS AI Chat 규칙:

- `asTicketId`는 로그인 사용자의 티켓이어야 하며, 본인 소유가 아니면 `404 NOT_FOUND`다.
- 한 사용자와 한 AS 티켓에는 `ACTIVE` chat session 1개만 유지한다.
- `GET /api/ai/as-chat`은 세션이 없으면 DB row를 만들지 않고 빈 `messages`를 반환한다.
- `POST /api/ai/as-chat`은 세션이 없으면 생성하고, 사용자 메시지와 AI 메시지를 `as_chat_messages`에 저장한다.
- `POST /api/ai/as-chat`은 LLM 필수 기능이다. `OPENAI_API_KEY`가 없으면 대화 저장 전에 `428 PRECONDITION_REQUIRED`를 반환한다.
- 사용자 화면은 `POST /api/ai/as-chat/stream`을 우선 사용해 `STARTED`, `RAG_READY`, `TOOLS_READY`, `LLM_RUNNING` 진행 상태를 표시한다. 기존 `POST /api/ai/as-chat`은 호환과 fallback을 위해 유지한다.
- AS Chat LLM 호출은 OpenAI Responses API structured output 기능을 사용하며, 단순 prompt 지시만으로 JSON을 생성하지 않는다.
- 기본 사용자 profile은 실측 결과 기준 `AS_CHAT_54_MINI_FAST`다. rollback이 필요하면 env에서 `AS_CHAT_DEFAULT_PROFILE=AS_CHAT_FAST`로 되돌린다. `AS_CHAT_NANO_FAST`는 schema 실패 추적용 실험 profile이며 기본 후보가 아니다.
- AS Chat은 `AS_CHAT_DEFAULT_PROFILE` 기준 profile 1개만 실행한다. 내부 검증용 `X-BuildGraph-AI-Profile` header가 있으면 해당 profile 1개만 실행한다.
- 일반 요청에서 전원 꺼짐, 재부팅, 과열 같은 고위험 문맥이면 서버가 balanced profile로 승격할 수 있다.
- 지원 profile은 `AS_CHAT_FAST`, `AS_CHAT_54_FAST`, `AS_CHAT_54_MINI_FAST`, `AS_CHAT_NANO_FAST`, `AS_CHAT_BALANCED`, `AS_CHAT_HIGH_QUALITY`다.
- 각 profile은 model, reasoning effort, RAG topK, prompt version, max output tokens, 최근 대화 개수, RAG 원문/Tool payload 포함 여부를 가진다.
- LLM 필수 필드는 `assistantMessage`, `causeCandidates`, `nextActions`, `escalation`, `ticketDraft`다.
- `causeCandidates[]`, `nextActions[]`는 `evidenceIds`, `toolInvocationIds`를 포함하며, 현재 응답에서 제공된 RAG/Tool id만 참조한다.
- LLM JSON 계약을 지키지 못하면 assistant message를 저장하지 않고 `502 UPSTREAM_ERROR`를 반환한다.
- `as_chat_messages.agent_session_id`는 해당 assistant 답변 턴의 `agent_sessions` 추적을 위한 연결점이다.
- LLM 호출 성능과 실패 기록은 `llm_generations`에 저장한다. prompt 원문과 API key는 저장하지 않는다.
- AS Chat stage latency는 `llm_generations.request_metadata.stageTimings`에 저장한다. 주요 키는 `firstEventMs`, `ragReadyMs`, `toolsReadyMs`, `llmRunningMs`, `llmOnlyLatencyMs`, `doneMs`다.
- 원본 RAG/Tool 근거는 `agentSessionId`로 `rag_evidence`, `tool_invocations`에서 조회한다.
- AS 티켓의 `cause_candidates`, `upgrade_candidates` 저장은 4번 담당 API가 결정한다. 이 API는 챗봇 결과를 반환만 한다.

### PC Agent/AS

| Method | Path | Auth | Owner | Request 예시 | Response 예시 | 관련 DB table |
|---|---|---|---|---|---|---|
| `POST` | `/api/agent-logs/upload` | USER | 4번 | `multipart/form-data` | `{ "id": "1b363bcb-42be-4428-b625-54a6b267d66f", "status": "UPLOADED", "fileName": "agent-log.jsonl", "fileSize": 12000, "rangeMinutes": 30, "deleteAfter": "2026-07-29T10:40:00Z" }` | `agent_log_uploads` |
| `GET` | `/api/agent-logs/{id}` | USER | 4번 | - | `{ "id": "1b363bcb-42be-4428-b625-54a6b267d66f", "status": "UPLOADED", "fileName": "agent-log.jsonl", "rangeMinutes": 30, "summary": "GPU driver error 반복", "createdAt": "2026-06-29T10:40:00Z", "deleteAfter": "2026-07-29T10:40:00Z" }` | `agent_log_uploads` |
| `POST` | `/api/as-tickets` | USER | 4번 | `{ "logUploadId": "1b363bcb-42be-4428-b625-54a6b267d66f", "symptom": "화면이 멈춤" }` | `{ "id": "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a", "status": "OPEN", "symptom": "화면이 멈춤", "logUploadId": "1b363bcb-42be-4428-b625-54a6b267d66f", "causeCandidates": [], "upgradeCandidates": [], "createdAt": "2026-06-29T10:42:00Z" }` | `as_tickets`, `agent_log_uploads` |
| `GET` | `/api/as-tickets/{id}` | USER | 4번 | - | `{ "id": "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a", "status": "OPEN", "symptom": "화면이 멈춤", "logUploadId": "1b363bcb-42be-4428-b625-54a6b267d66f", "causeCandidates": [], "upgradeCandidates": [], "adminNote": null, "createdAt": "2026-06-29T10:42:00Z" }` | `as_tickets` |
| `GET` | `/api/admin/as-tickets` | ADMIN | 4번 | `?page=0&size=20` | `{ "items": [{ "id": "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a", "status": "OPEN", "symptom": "화면이 멈춤", "userId": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "assignedAdminId": null, "createdAt": "2026-06-29T10:42:00Z" }], "page": 0, "size": 20, "total": 1 }` | `as_tickets` |
| `GET` | `/api/admin/as-tickets/{id}` | ADMIN | 4번 | - | `{ "id": "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a", "status": "OPEN", "symptom": "화면이 멈춤", "logUploadId": "1b363bcb-42be-4428-b625-54a6b267d66f", "assignedAdminId": null, "causeCandidates": [], "upgradeCandidates": [], "adminNote": null }` | `as_tickets`, `agent_log_uploads` |
| `PATCH` | `/api/admin/as-tickets/{id}` | ADMIN | 4번 | `{ "status": "IN_PROGRESS", "assignedAdminId": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "adminNote": "확인 중" }` | `{ "id": "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a", "status": "IN_PROGRESS", "assignedAdminId": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "adminNote": "확인 중", "resolvedAt": null, "updatedAt": "2026-06-29T10:45:00Z" }` | `as_tickets`, `users`, `admin_audit_logs` |

`POST /api/agent-logs/upload` multipart fields:

| field | type | required | 설명 |
|---|---|---:|---|
| `file` | file | yes | JSONL 로그 파일 |
| `rangeMinutes` | number | yes | 최근 N분 로그 범위 |
| `consentAccepted` | boolean | yes | 로그 업로드 동의 여부 |

`consentAccepted=false`이면 `400`을 반환한다. `true`일 때 서버가 `consent_accepted_at`을 저장한다.

파일 검증 실패는 `400 FILE_VALIDATION_ERROR`를 반환하고 `agent_log_uploads` row를 만들지 않는다. 기준은 `DB_SCHEMA.md`의 로그 업로드 보안 정책을 따른다.

`PATCH /api/admin/as-tickets/{id}` 허용 상태 전이:

| from | to | 결과 |
|---|---|---|
| `OPEN` | `ASSIGNED` | `200` |
| `OPEN`, `ASSIGNED` | `IN_PROGRESS` | `200` |
| `ASSIGNED`, `IN_PROGRESS` | `ASSIGNED` | `200`, 담당자 재배정 |
| `IN_PROGRESS` | `RESOLVED` | `200`, `resolvedAt` 저장 |
| `RESOLVED` | `CLOSED` | `200` |
| `OPEN`, `ASSIGNED`, `IN_PROGRESS` | `CANCELLED` | `200` |

`PATCH /api/admin/as-tickets/{id}` 409 조건:

- 현재 status가 `CLOSED` 또는 `CANCELLED`이면 어떤 status로도 변경하지 않고 `409 CONFLICT_STATE`를 반환한다.
- `RESOLVED`에서 `CLOSED` 외 상태로 변경하면 `409 CONFLICT_STATE`다.
- 위 허용 전이표에 없는 전이는 `409 CONFLICT_STATE`다.
- 409가 발생해도 DB status는 변경하지 않는다. admin 요청이므로 거절 이력은 `admin_audit_logs`에 기록한다.

### Admin/Health

| Method | Path | Auth | Owner | Request 예시 | Response 예시 | 관련 DB table |
|---|---|---|---|---|---|---|
| `GET` | `/api/admin/dashboard` | ADMIN | 5번 | - | `{ "agentRunning": 1, "openTickets": 3, "priceJobsRunning": 0, "degraded": false, "generatedAt": "2026-06-29T10:50:00Z" }` | `agent_sessions`, `as_tickets`, `price_jobs` |
| `GET` | `/api/admin/audit-logs/recent` | ADMIN | 5번 | - | `{ "items": [{ "action": "AS_TICKET_UPDATED", "targetType": "as_tickets", "targetId": "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a", "metadata": { "beforeStatus": "OPEN", "afterStatus": "IN_PROGRESS" }, "createdAt": "2026-06-29T10:45:00Z" }] }` | `admin_audit_logs` |
| `GET` | `/api/health` | no | 5번 | - | `200 { "status": "UP", "database": "UP" }`, DB 연결 실패 시 `503 { "status": "DOWN" }` | runtime |

- `/api/health`는 DB probe를 포함한다. DB 연결 또는 query 실패 시 `503 Service Unavailable`과 `status: "DOWN"`을 반환한다.

## Schema Appendix

모든 `id`와 `*Id`는 `public_id` 문자열이다. 내부 `BIGINT id`는 어떤 DTO에도 포함하지 않는다.

### 공통 DTO

| schema | field name | type | nullable | example |
|---|---|---|---:|---|
| `ErrorResponse` | `code` | `string` | no | `VALIDATION_ERROR` |
| `ErrorResponse` | `message` | `string` | no | `요청 값이 올바르지 않습니다.` |
| `ErrorResponse` | `details` | `object` | yes | `{ "field": "email" }` |
| `PageDto<T>` | `items` | `T[]` | no | `[]` |
| `PageDto<T>` | `page` | `number` | no | `0` |
| `PageDto<T>` | `size` | `number` | no | `20` |
| `PageDto<T>` | `total` | `number` | no | `125` |
| `WarningDto` | `code` | `string` | no | `OVER_BUDGET` |
| `WarningDto` | `message` | `string` | no | `예산보다 50000원 높습니다.` |
| `WarningDto` | `severity` | `string` | no | `WARN` |
| `WarningDto` | `relatedPartIds` | `string[]` | yes | `["0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11"]` |

### Auth/User DTO

| schema | field name | type | nullable | example |
|---|---|---|---:|---|
| `UserDto` | `id` | `string` | no | `c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd` |
| `UserDto` | `email` | `string` | no | `user@example.com` |
| `UserDto` | `name` | `string` | no | `홍길동` |
| `UserDto` | `role` | `string` | no | `USER` |
| `SignupRequest` | `email` | `string` | no | `user@example.com` |
| `SignupRequest` | `password` | `string` | no | `passw0rd!` |
| `SignupRequest` | `name` | `string` | no | `홍길동` |
| `SignupRequest` | `termsAccepted` | `boolean` | no | `true` |
| `SignupRequest` | `marketingAccepted` | `boolean` | no | `false` |
| `AuthResponse` | `accessToken` | `string` | no | `jwt-access-token` |
| `AuthResponse` | `refreshToken` | `string` | no | `opaque-refresh-token` |
| `AuthResponse` | `user` | `UserDto` | no | `{ "id": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd" }` |

### Quote/Build DTO

| schema | field name | type | nullable | example |
|---|---|---|---:|---|
| `RequirementParseRequest` | `message` | `string` | no | `150만원 게임용 PC` |
| `RequirementParseRequest` | `budget` | `number` | yes | `1500000` |
| `RequirementParseRequest` | `usageTags` | `string[]` | yes | `["GAMING"]` |
| `RequirementParseRequest` | `resolution` | `string` | yes | `QHD` |
| `RequirementParseRequest` | `preferredVendors` | `string[]` | yes | `["NVIDIA"]` |
| `RequirementParseRequest` | `priority` | `string` | yes | `성능` |
| `RequirementDto` | `id` | `string` | no | `2e0f8c9c-8e1c-4d75-94a2-5d6a4977de11` |
| `RequirementDto` | `rawMessage` | `string` | no | `150만원 게임용 PC` |
| `RequirementDto` | `budget` | `number` | yes | `1500000` |
| `RequirementDto` | `usageTags` | `string[]` | yes | `["GAMING"]` |
| `RequirementDto` | `parsedContext` | `object` | yes | `{ "usageTags": ["GAMING"] }` |
| `RequirementDto` | `questions` | `RequirementQuestionDto[]` | no | `[{ "key": "noisePreference" }]` |
| `RequirementDto` | `agentSessionId` | `string` | yes | `7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a` |
| `RequirementDto` | `agentSummary` | `string` | yes | `요구사항을 구조화했습니다.` |
| `RequirementDto` | `evidenceIds` | `string[]` | yes | `["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"]` |
| `BuildRecommendRequest` | `requirementId` | `string` | no | `2e0f8c9c-8e1c-4d75-94a2-5d6a4977de11` |
| `BuildRecommendRequest` | `answers` | `object` | yes | `{ "noisePreference": "조용한 편" }` |
| `BuildRecommendResponse` | `agentSessionId` | `string` | no | `7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a` |
| `BuildRecommendResponse` | `recommendations` | `BuildDto[]` | no | `[{ "id": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd" }]` |
| `BuildRecommendResponse` | `warnings` | `WarningDto[]` | no | `[]` |
| `BuildRecommendResponse` | `evidenceIds` | `string[]` | no | `["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"]` |
| `BuildRecommendResponse` | `toolResults` | `ToolResultDto[]` | yes | `[]` |
| `BuildDto` | `id` | `string` | no | `3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd` |
| `BuildDto` | `name` | `string` | no | `QHD Gaming Build` |
| `BuildDto` | `recommendedFor` | `string` | yes | `균형 우선` |
| `BuildDto` | `summary` | `string` | yes | `내부 자산 기반 추천` |
| `BuildDto` | `totalPrice` | `number` | no | `1450000` |
| `BuildDto` | `confidence` | `string` | no | `HIGH` |
| `BuildDto` | `items` | `BuildItemDto[]` | no | `[]` |
| `BuildDto` | `warnings` | `WarningDto[]` | no | `[]` |
| `BuildDto` | `toolResults` | `ToolResultDto[]` | yes | `[{ "tool": "compatibility", "status": "PASS" }]` |
| `BuildDto` | `evidenceIds` | `string[]` | no | `["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"]` |
| `BuildDto` | `agentSessionId` | `string` | yes | `7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a` |
| `BuildDto` | `agentSummary` | `string` | yes | `추천 근거 요약` |
| `BuildDto` | `changeableCategories` | `string[]` | no | `["GPU", "RAM"]` |
| `BuildDto` | `createdAt` | `string` | yes | `2026-06-29T10:20:00Z` |
| `BuildItemDto` | `category` | `string` | no | `GPU` |
| `BuildItemDto` | `partId` | `string` | no | `0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11` |
| `BuildItemDto` | `name` | `string` | no | `RTX 4070` |
| `BuildItemDto` | `manufacturer` | `string` | yes | `NVIDIA` |
| `BuildItemDto` | `price` | `number` | no | `850000` |
| `BuildItemDto` | `attributes` | `object` | yes | `{ "lengthMm": 304 }` |
| `ChangePartRequest` | `category` | `string` | no | `GPU` |
| `ChangePartRequest` | `partId` | `string` | no | `0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11` |
| `ChangePartResponse` | `beforeBuild` | `BuildDto` | no | `{ "id": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd" }` |
| `ChangePartResponse` | `afterBuild` | `object` | no | `{ "totalPrice": 1500000, "items": [] }` |
| `ChangePartResponse` | `diffRows` | `ChangePartDiffRowDto[]` | no | `[{ "label": "GPU", "diff": "+50,000원" }]` |
| `ChangePartResponse` | `toolResults` | `ToolResultDto[]` | no | `[{ "tool": "power", "status": "PASS" }]` |
| `ChangePartResponse` | `agentSummary` | `string` | yes | `변경 비교 요약` |
| `AiBuildChatRequest` | `message` | `string` | no | `200만원 PC 추천` |
| `AiBuildChatRequest` | `currentBuilds` | `AiBuildRecommendationDto[]` | yes | `[]` |
| `AiBuildChatRequest` | `appliedPartPreferences` | `AiAppliedPartPreferenceDto[]` | yes | `[]` |
| `AiBuildChatRequest` | `currentQuoteDraft` | `QuoteDraftDto` | yes | `{ "items": [] }` |
| `AiBuildChatResponse` | `answerType` | `string` | no | `BUDGET` |
| `AiBuildChatResponse` | `message` | `string` | no | `200만원 예산 기준 추천입니다.` |
| `AiBuildChatResponse` | `builds` | `AiBuildRecommendationDto[]` | no | `[{ "tier": "balanced" }]` |
| `AiBuildChatResponse` | `partRecommendation` | `AiPartRecommendationDto` | yes | `{ "category": "GPU", "options": [] }` |
| `AiBuildChatResponse` | `actions` | `AiDraftActionDto[]` | yes | `[{ "type": "REMOVE_DRAFT_PART" }]` |
| `AiBuildChatResponse` | `warnings` | `string[]` | no | `[]` |
| `AiBuildRecommendationDto` | `id` | `string` | no | `ai-2000000-balanced` |
| `AiBuildRecommendationDto` | `tier` | `string` | no | `balanced` |
| `AiBuildRecommendationDto` | `items` | `AiBuildItemDto[]` | no | `[{ "category": "GPU" }]` |
| `AiBuildRecommendationDto` | `toolResults` | `ToolResultDto[]` | no | `[{ "tool": "price", "status": "PASS" }]` |
| `AiBuildApplyRequest` | `conflictPolicy` | `string` | no | `REPLACE` |
| `AiBuildApplyRequest` | `items` | `AiBuildApplyItem[]` | no | `[{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "quantity": 1 }]` |

### Parts/Price/Tool DTO

| schema | field name | type | nullable | example |
|---|---|---|---:|---|
| `PartDto` | `id` | `string` | no | `0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11` |
| `PartDto` | `category` | `string` | no | `GPU` |
| `PartDto` | `name` | `string` | no | `RTX 4070` |
| `PartDto` | `manufacturer` | `string` | yes | `NVIDIA` |
| `PartDto` | `price` | `number` | no | `850000` |
| `PartDto` | `status` | `string` | no | `ACTIVE` |
| `PartDto` | `attributes` | `object` | no | `{ "wattage": 200 }` |
| `PartDto` | `externalOffer` | `object` | yes | `{ "imageUrl": "https://...", "supplierName": "Naver Store", "offerUrl": "https://...", "lowPrice": 950000, "source": "NAVER_SHOPPING_SEARCH", "refreshedAt": "2026-06-29T10:25:00Z" }` |
| `PriceAlertDto` | `partId` | `string` | no | `0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11` |
| `PriceAlertDto` | `partName` | `string` | no | `RTX 4070` |
| `PriceAlertDto` | `targetPrice` | `number` | no | `700000` |
| `PriceAlertDto` | `currentPrice` | `number` | no | `850000` |
| `PriceAlertDto` | `status` | `string` | no | `ACTIVE` |
| `PriceAlertDto` | `createdAt` | `string` | yes | `2026-06-29T10:25:00Z` |
| `PriceJobDto` | `id` | `string` | no | `8d4b2d5b-7d39-4f8a-8195-bf32b9c5f61e` |
| `PriceJobDto` | `status` | `string` | no | `QUEUED` |
| `PriceJobDto` | `requestedBy` | `string` | no | `c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd` |
| `PriceJobDto` | `startedAt` | `string` | yes | `2026-06-29T10:00:00Z` |
| `PriceJobDto` | `finishedAt` | `string` | yes | `2026-06-29T10:01:00Z` |
| `PriceJobDto` | `errorSummary` | `string` | yes | `null` |
| `ToolCheckRequest` | `buildId` | `string` | yes | `3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd` |
| `ToolCheckRequest` | `partIds` | `string[]` | no | `["0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11"]` |
| `ToolCheckRequest` | `context` | `object` | no | `{ "category": "GPU" }` |
| `ToolCheckResponse` | `status` | `string` | no | `PASS` |
| `ToolCheckResponse` | `confidence` | `string` | no | `HIGH` |
| `ToolCheckResponse` | `summary` | `string` | no | `호환됩니다.` |
| `ToolCheckResponse` | `details` | `object` | no | `{ "checkedPartIds": ["0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11"] }` |

### Agent/RAG DTO

| schema | field name | type | nullable | example |
|---|---|---|---:|---|
| `AgentSessionCreateRequest` | `requirementId` | `string` | yes | `2e0f8c9c-8e1c-4d75-94a2-5d6a4977de11` |
| `AgentSessionCreateRequest` | `buildId` | `string` | yes | `3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd` |
| `AgentSessionCreateRequest` | `asTicketId` | `string` | yes | `4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a` |
| `AgentSessionDto` | `id` | `string` | no | `7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a` |
| `AgentSessionDto` | `status` | `string` | no | `SUCCEEDED` |
| `AgentSessionDto` | `summary` | `string` | yes | `추천이 완료되었습니다.` |
| `AgentSessionDto` | `stateTimeline` | `StateTimelineItemDto[]` | no | `[]` |
| `AgentSessionDto` | `toolInvocationIds` | `string[]` | yes | `["4cf44761-e25b-4d5b-bd31-52c13dd9975c"]` |
| `AgentSessionDto` | `evidenceIds` | `string[]` | yes | `["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"]` |
| `StateTimelineItemDto` | `from` | `string` | yes | `QUEUED` |
| `StateTimelineItemDto` | `to` | `string` | no | `RUNNING` |
| `StateTimelineItemDto` | `actor` | `string` | no | `SYSTEM` |
| `StateTimelineItemDto` | `at` | `string` | no | `2026-06-29T10:36:00Z` |
| `RagEvidenceDto` | `id` | `string` | no | `9ebf5278-68aa-42a5-96f4-8ec0f90f0f77` |
| `RagEvidenceDto` | `sourceId` | `string` | no | `spec-rtx4070` |
| `RagEvidenceDto` | `summary` | `string` | no | `RTX 4070 QHD 성능 근거` |
| `RagEvidenceDto` | `score` | `number` | yes | `0.92` |
| `RagEvidenceDto` | `metadata` | `object` | yes | `{ "sourceType": "PART_SPEC" }` |
| `ToolInvocationDto` | `id` | `string` | no | `4cf44761-e25b-4d5b-bd31-52c13dd9975c` |
| `ToolInvocationDto` | `agentSessionId` | `string` | no | `7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a` |
| `ToolInvocationDto` | `toolName` | `string` | no | `compatibility` |
| `ToolInvocationDto` | `status` | `string` | no | `PASS` |
| `ToolInvocationDto` | `confidence` | `string` | no | `HIGH` |
| `ToolInvocationDto` | `summary` | `string` | no | `호환됩니다.` |
| `ToolInvocationDto` | `requestPayload` | `object` | yes | `{}` |
| `ToolInvocationDto` | `resultPayload` | `object` | yes | `{}` |
| `ToolInvocationDto` | `latencyMs` | `number` | yes | `120` |
| `ToolInvocationDto` | `createdAt` | `string` | yes | `2026-06-29T10:36:10Z` |
| `AsChatRequest` | `asTicketId` | `string` | no | `4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a` |
| `AsChatRequest` | `message` | `string` | no | `게임 20분 뒤 프레임이 급락하고 GPU 온도가 95도까지 올라가요` |
| `AsChatResponse` | `sessionId` | `string` | yes | `7c2f8f17-8f18-4d10-bcd1-9d20d1c71a01` |
| `AsChatResponse` | `asTicketId` | `string` | no | `4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a` |
| `AsChatResponse` | `ticket` | `AsTicketDto` | no | `{ "id": "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a" }` |
| `AsChatResponse` | `model` | `string` | no | `gpt-5.4-mini` |
| `AsChatResponse` | `agentSessionId` | `string` | yes | `7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a` |
| `AsChatResponse` | `messages` | `AsChatMessageDto[]` | no | `[]` |
| `AsChatResponse` | `assistantMessage` | `string` | yes | `GPU 과열 가능성을 먼저 확인하세요.` |
| `AsChatResponse` | `causeCandidates` | `object[]` | yes | `[{ "label": "GPU 과열", "confidence": "MEDIUM" }]` |
| `AsChatResponse` | `nextActions` | `object[]` | yes | `[{ "label": "팬 확인", "priority": "HIGH" }]` |
| `AsChatResponse` | `escalation` | `object` | yes | `{ "required": false, "reason": "원격지원 전 확인 가능" }` |
| `AsChatResponse` | `ticketDraft` | `object` | yes | `{ "symptomSummary": "게임 중 프레임 급락" }` |
| `AsChatResponse` | `evidence` | `RagEvidenceDto[]` | no | `[]` |
| `AsChatResponse` | `toolResults` | `ToolInvocationDto[]` | no | `[]` |
| `AsChatMessageDto` | `id` | `string` | no | `7c2f8f17-8f18-4d10-bcd1-9d20d1c71a01` |
| `AsChatMessageDto` | `role` | `string` | no | `USER` |
| `AsChatMessageDto` | `content` | `string` | no | `게임 중 프레임이 급락합니다.` |
| `AsChatMessageDto` | `structuredPayload` | `object` | yes | `{ "causeCandidates": [] }` |
| `AsChatMessageDto` | `agentSessionId` | `string` | yes | `7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a` |
| `AsChatMessageDto` | `createdAt` | `string` | yes | `2026-06-29T10:36:10Z` |

### PC Agent/AS/Admin DTO

| schema | field name | type | nullable | example |
|---|---|---|---:|---|
| `AgentLogUploadDto` | `id` | `string` | no | `1b363bcb-42be-4428-b625-54a6b267d66f` |
| `AgentLogUploadDto` | `status` | `string` | no | `UPLOADED` |
| `AgentLogUploadDto` | `fileName` | `string` | no | `agent-log.jsonl` |
| `AgentLogUploadDto` | `fileSize` | `number` | yes | `12000` |
| `AgentLogUploadDto` | `rangeMinutes` | `number` | no | `30` |
| `AgentLogUploadDto` | `summary` | `string` | yes | `GPU driver error 반복` |
| `AgentLogUploadDto` | `deleteAfter` | `string` | no | `2026-07-29T10:40:00Z` |
| `AsTicketDto` | `id` | `string` | no | `4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a` |
| `AsTicketDto` | `status` | `string` | no | `OPEN` |
| `AsTicketDto` | `symptom` | `string` | no | `화면이 멈춤` |
| `AsTicketDto` | `logUploadId` | `string` | yes | `1b363bcb-42be-4428-b625-54a6b267d66f` |
| `AsTicketDto` | `assignedAdminId` | `string` | yes | `c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd` |
| `AsTicketDto` | `causeCandidates` | `object[]` | no | `[]` |
| `AsTicketDto` | `upgradeCandidates` | `object[]` | no | `[]` |
| `AsTicketDto` | `adminNote` | `string` | yes | `확인 중` |
| `AsTicketDto` | `resolvedAt` | `string` | yes | `null` |
| `AdminDashboardDto` | `agentRunning` | `number` | no | `1` |
| `AdminDashboardDto` | `openTickets` | `number` | no | `3` |
| `AdminDashboardDto` | `priceJobsRunning` | `number` | no | `0` |
| `AdminDashboardDto` | `degraded` | `boolean` | no | `false` |
| `AdminDashboardDto` | `generatedAt` | `string` | yes | `2026-06-29T10:50:00Z` |
| `AuditLogDto` | `action` | `string` | no | `AS_TICKET_UPDATED` |
| `AuditLogDto` | `targetType` | `string` | no | `as_tickets` |
| `AuditLogDto` | `targetId` | `string` | yes | `4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a` |
| `AuditLogDto` | `metadata` | `object` | yes | `{ "beforeStatus": "OPEN", "afterStatus": "IN_PROGRESS" }` |
| `AuditLogDto` | `createdAt` | `string` | no | `2026-06-29T10:45:00Z` |

## V1 제외 API

| API | 제외 이유 |
|---|---|
| `POST /api/price-snapshots/collect` | 공개 API가 아니라 내부 service 처리 |
| `PATCH /api/price-alerts/{id}` | V1 새 기능 추가 금지에 따라 제외 |
| 주문/결제/배송/재고/타임세일 API | MVP 범위 아님 |
| Google 외 OAuth provider API | V1은 Google 1개 provider만 구현 |
| 관리자 룰/벤치마크 편집 API | V1은 seed read-only |
| 전체 audit 검색/필터 API | V1은 최근 이력 조회만 |
| Quick Assist 연동 API | V2 확장 |

## API 변경 절차

1. API path, request, response 변경 전 `DB_SCHEMA.md`의 저장 테이블과 충돌이 없는지 확인한다.
2. 변경 API의 주 owner를 `ROUTE_OWNERSHIP.md`에서 확인한다.
3. path `{id}`는 public_id 원칙을 유지한다.
4. status/enum은 이 문서와 `DB_SCHEMA.md`의 값을 동시에 갱신한다.
5. V1 제외 항목을 API에 다시 포함하지 않는다.
6. 관리자 API는 반드시 `ADMIN` 권한 필요 여부를 명시한다.
7. API field name, nullable, type 변경은 이 문서의 Schema Appendix와 예시 response를 함께 수정한다.
8. API path, owner, DB table, enum/status, error code, pagination, 권한 정책 중 하나라도 변경하면 `ROUTE_OWNERSHIP.md`, `DB_SCHEMA.md`, 이 문서의 관련 섹션을 같은 PR에서 동시에 수정한다.
9. 병렬 개발 동결 이후 P0 계약 항목은 미정 상태로 남기지 않고 MVP 기준 결정값을 적는다.

## 병렬 개발 contract test 최소 세트

| 테스트 | API 기준 |
|---|---|
| public API BIGINT id 비노출 | 모든 response의 `id`, `*Id`, `targetId`가 UUID 문자열 또는 stable key인지 확인한다. 숫자 PK는 실패다. |
| 401/403 권한 분기 | 인증 없음은 `401 UNAUTHORIZED`, USER가 admin API 접근 시 `403 FORBIDDEN`이다. |
| 본인 자원 접근 | build, ticket, agent log, agent session이 본인 소유가 아니면 `404 NOT_FOUND`다. |
| 금지된 상태 전이 | 상태 전이표에 없는 변경은 `409 CONFLICT_STATE`다. |
| 중복 실행 | `POST /api/admin/price-jobs/run`은 active job이 있으면 `409`, `POST /api/agent/sessions/{id}/run`은 `QUEUED`가 아니면 `409`다. |
| soft delete 조회 제외 | soft delete 리소스 상세는 `404 NOT_FOUND`, 목록에는 포함하지 않는다. |
| Flyway migration 순서 검증 | API contract에 필요한 table/column이 migration 순서상 먼저 생성되는지 확인한다. |
| JSONL 업로드 validation | 크기, MIME/확장자, JSONL line 검증 실패는 `400 FILE_VALIDATION_ERROR`다. |
| pagination 기본값/최대값 | 목록 API는 `page=0`, `size=20` 기본값과 `size<=100`을 지킨다. |
