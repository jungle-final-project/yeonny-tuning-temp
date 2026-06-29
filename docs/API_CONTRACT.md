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
| `POST` | `/api/users` | no | 5번 | `{ "email": "user@example.com", "password": "passw0rd!", "name": "홍길동", "termsAccepted": true, "marketingAccepted": false }` | `{ "id": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "email": "user@example.com", "name": "홍길동", "role": "USER" }` | `users` |
| `POST` | `/api/auth/login` | no | 5번 | `{ "email": "user@example.com", "password": "passw0rd!" }` | `{ "accessToken": "jwt-access-token", "refreshToken": "opaque-refresh-token", "user": { "id": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "email": "user@example.com", "name": "홍길동", "role": "USER" } }` | `users`, `refresh_tokens` |
| `POST` | `/api/auth/refresh` | no | 5번 | `{ "refreshToken": "opaque-refresh-token" }` | `{ "accessToken": "new-jwt-access-token", "refreshToken": "new-opaque-refresh-token" }` | `refresh_tokens` |
| `POST` | `/api/auth/logout` | USER | 5번 | `{ "refreshToken": "opaque-refresh-token" }` | `204 No Content` | `refresh_tokens` |
| `GET` | `/api/auth/me` | USER | 5번 | - | `{ "id": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "email": "user@example.com", "name": "홍길동", "role": "USER" }` | `users` |
| `GET` | `/api/auth/google/start` | no | 5번 | - | `302 Redirect` | runtime |
| `GET` | `/api/auth/google/callback` | no | 5번 | Google callback query | `302 /auth/callback?code=one-time-code` | `users`, `user_auth_providers`, runtime |
| `POST` | `/api/auth/exchange` | no | 5번 | `{ "code": "one-time-code" }` | `{ "accessToken": "jwt-access-token", "refreshToken": "opaque-refresh-token", "user": { "id": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "email": "user@example.com", "name": "홍길동", "role": "USER" } }` | `users`, `user_auth_providers`, `refresh_tokens`, runtime |

Google OAuth 정책:

- V1 provider는 `GOOGLE` 1개다.
- verified email이 기존 local 계정과 같으면 같은 `users` row에 연결한다.
- Google access/refresh token은 저장하지 않는다.

### Quote/Build

| Method | Path | Auth | Owner | Request 예시 | Response 예시 | 관련 DB table |
|---|---|---|---|---|---|---|
| `POST` | `/api/requirements/parse` | USER | 1번 | `{ "message": "150만원 게임용 PC", "budget": 1500000, "usageTags": ["GAMING"] }` | `{ "id": "2e0f8c9c-8e1c-4d75-94a2-5d6a4977de11", "rawMessage": "150만원 게임용 PC", "budget": 1500000, "usageTags": ["GAMING"], "parsedContext": { "usageTags": ["GAMING"], "budget": 1500000 } }` | `requirements` |
| `POST` | `/api/builds/recommend` | USER | 1번 | `{ "requirementId": "2e0f8c9c-8e1c-4d75-94a2-5d6a4977de11" }` | `{ "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "recommendations": [{ "id": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd", "name": "QHD Gaming Build", "totalPrice": 1450000, "confidence": "HIGH", "items": [], "warnings": [], "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"], "changeableCategories": ["GPU", "RAM"] }], "warnings": [], "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"] }` | `requirements`, `builds`, `build_items`, `agent_sessions`, `tool_invocations`, `rag_evidence` |
| `GET` | `/api/builds/{id}` | USER | 1번 | - | `{ "id": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd", "name": "QHD Gaming Build", "totalPrice": 1450000, "confidence": "HIGH", "items": [{ "category": "GPU", "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "name": "RTX 4070", "price": 850000 }], "warnings": [], "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"], "changeableCategories": ["GPU", "RAM"], "createdAt": "2026-06-29T10:20:00Z" }` | `builds`, `build_items`, `parts` |
| `GET` | `/api/builds/history` | USER | 1번 | `?page=0&size=20` | `{ "items": [{ "id": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd", "name": "QHD Gaming Build", "totalPrice": 1450000, "confidence": "HIGH", "createdAt": "2026-06-29T10:20:00Z" }], "page": 0, "size": 20, "total": 1 }` | `requirements`, `builds` |
| `POST` | `/api/builds/{id}/change-part` | USER | 1번 | `{ "category": "GPU", "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11" }` | `{ "buildId": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd", "category": "GPU", "previousPartId": "0bb1f994-5e1f-4dc4-b55c-c615130e1bb4", "selectedPartId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "totalPrice": 1500000, "diff": { "price": 50000 }, "warnings": [] }` | `builds`, `build_items`, `parts` |

`POST /api/builds/recommend` transaction 경계:

1. request 검증과 requirement 소유권 확인은 transaction 밖에서 수행한다. 소유자가 아니면 `404 NOT_FOUND`다.
2. Agent/Tool/RAG 계산은 DB transaction 밖에서 수행한다. 이 단계에서 외부 호출이 실패해도 fallback 추천이 가능하면 `warnings`를 포함해 계속 진행한다.
3. 최종 저장은 하나의 DB transaction으로 묶는다. 포함 범위는 `agent_sessions` 생성, `builds` 저장, `build_items` 저장, `rag_evidence` 저장, `tool_invocations` 저장, `agent_sessions.status` 최종 갱신이다.
4. 위 최종 저장 transaction에서 하나라도 실패하면 전체 rollback하고 `500 INTERNAL_ERROR`를 반환한다. 이 경우 build는 유지하지 않고 evidence만 실패 처리하지도 않는다.
5. Tool/RAG 계산 결과가 없어 fallback build를 저장하는 경우에는 `agent_sessions.state_timeline`에 `FALLBACK_READY -> SUCCEEDED`를 남기고, response의 `warnings`에 fallback 사유를 포함한다.
6. response에 포함된 `agentSessionId`, `recommendations[].id`, `evidenceIds`는 모두 같은 최종 저장 transaction에서 commit된 `public_id`다.
7. 1번 추천 API는 `agent_sessions`, `tool_invocations`, `rag_evidence`를 직접 조작하지 않고, 3번이 제공하는 내부 Agent trace service를 호출해 추적 데이터를 기록한다.

### Parts/Price

| Method | Path | Auth | Owner | Request 예시 | Response 예시 | 관련 DB table |
|---|---|---|---|---|---|---|
| `GET` | `/api/parts` | USER | 2번 | `?category=GPU&q=4070&manufacturer=NVIDIA&status=ACTIVE&minPrice=500000&maxPrice=1300000&page=0&size=20&sort=price_desc` | `{ "items": [{ "id": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "name": "RTX 4070", "manufacturer": "NVIDIA", "price": 850000, "status": "ACTIVE", "attributes": { "wattage": 200 }, "latestPriceSource": "DANAWA_BACKUP" }], "page": 0, "size": 20, "total": 1 }` | `parts`, `price_snapshots`, `benchmark_summaries` |
| `GET` | `/api/parts/{id}` | USER | 2번 | - | `{ "id": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "name": "RTX 4070", "manufacturer": "NVIDIA", "price": 850000, "status": "ACTIVE", "attributes": { "wattage": 200, "lengthMm": 304 }, "benchmarkSummary": { "summary": "QHD high preset 기준", "score": 92.5 }, "latestPriceSource": "DANAWA_BACKUP" }` | `parts`, `price_snapshots`, `benchmark_summaries` |
| `GET` | `/api/price-alerts` | USER | 2번 | `?page=0&size=20` | `{ "items": [{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "partName": "RTX 4070", "targetPrice": 700000, "currentPrice": 850000, "status": "ACTIVE", "createdAt": "2026-06-29T10:25:00Z" }], "page": 0, "size": 20, "total": 1 }` | `price_alerts`, `parts`, `users` |
| `POST` | `/api/price-alerts` | USER | 2번 | `{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "targetPrice": 700000 }` | `{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "partName": "RTX 4070", "targetPrice": 700000, "currentPrice": 850000, "status": "ACTIVE", "createdAt": "2026-06-29T10:25:00Z" }` | `price_alerts`, `parts`, `users` |
| `GET` | `/api/admin/price-jobs` | ADMIN | 2번 | `?page=0&size=20` | `{ "items": [{ "id": "8d4b2d5b-7d39-4f8a-8195-bf32b9c5f61e", "status": "SUCCEEDED", "requestedBy": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "startedAt": "2026-06-29T10:00:00Z", "finishedAt": "2026-06-29T10:01:00Z", "errorSummary": null }], "page": 0, "size": 20, "total": 1 }` | `price_jobs` |
| `POST` | `/api/admin/price-jobs/run` | ADMIN | 2번 | `{ "source": "manual" }` | `{ "id": "8d4b2d5b-7d39-4f8a-8195-bf32b9c5f61e", "status": "QUEUED", "requestedBy": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "createdAt": "2026-06-29T10:30:00Z" }` | `price_jobs`, `price_snapshots` |

`POST /api/price-snapshots/collect`는 공개 API가 아니다. 가격 스냅샷 생성은 `/api/admin/price-jobs/run` 뒤의 내부 service 처리다.

`POST /api/price-alerts`는 같은 사용자, 같은 `partId`, 같은 `targetPrice`의 `ACTIVE` 알림이 이미 있으면 `409 DUPLICATE_RESOURCE`를 반환한다.

`POST /api/admin/price-jobs/run`은 `price_jobs.status IN ('QUEUED', 'RUNNING')`인 row가 하나라도 있으면 새 job을 만들지 않고 `409 CONFLICT_STATE`를 반환한다.

부품 검색 정렬은 `category`, `price_asc`, `price_desc`, `name`만 허용한다. `q`는 `parts.name`, `parts.manufacturer`, `parts.attributes`를 대상으로 검색한다.

외부 가격 수집 백업은 별도 public API를 만들지 않는다. 현재 단계에서는 `price_snapshots.source = "DANAWA_BACKUP"`와 `price_snapshots.raw_payload`, `parts.attributes.externalSources`에 키워드와 source metadata를 저장한다. 실제 크롤러/수집기는 관리자 가격 Job 내부 처리로만 붙인다.

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
| `POST` | `/api/tools/performance/check` | USER | 2번 | 공통 wrapper | 공통 Tool response | `parts`, `benchmark_summaries` |
| `POST` | `/api/tools/price/check` | USER | 2번 | 공통 wrapper | 공통 Tool response | `parts`, `price_snapshots` |

### Agent/RAG

| Method | Path | Auth | Owner | Request 예시 | Response 예시 | 관련 DB table |
|---|---|---|---|---|---|---|
| `POST` | `/api/agent/sessions` | USER | 3번 | `{ "requirementId": "2e0f8c9c-8e1c-4d75-94a2-5d6a4977de11", "buildId": null, "asTicketId": null }` | `{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "status": "QUEUED", "summary": null, "stateTimeline": [{ "from": null, "to": "QUEUED", "actor": "USER", "at": "2026-06-29T10:35:00Z" }] }` | `agent_sessions` |
| `POST` | `/api/agent/sessions/{id}/run` | USER | 3번 | `{}` | `{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "status": "RUNNING", "summary": null, "stateTimeline": [{ "from": "QUEUED", "to": "RUNNING", "actor": "SYSTEM", "at": "2026-06-29T10:36:00Z" }] }` | `agent_sessions` |
| `GET` | `/api/agent/sessions/{id}` | USER | 3번 | - | `{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "status": "SUCCEEDED", "summary": "추천이 완료되었습니다.", "stateTimeline": [], "toolInvocationIds": ["4cf44761-e25b-4d5b-bd31-52c13dd9975c"], "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"] }` | `agent_sessions`, `tool_invocations`, `rag_evidence` |
| `GET` | `/api/rag/search` | USER | 3번 | `?q=gpu&page=0&size=20` | `{ "items": [{ "id": "9ebf5278-68aa-42a5-96f4-8ec0f90f0f77", "summary": "RTX 4070 QHD 성능 근거", "sourceId": "spec-rtx4070", "score": 0.92 }], "page": 0, "size": 20, "total": 1 }` | `rag_evidence` |
| `GET` | `/api/rag/evidence/{id}` | USER | 3번 | - | `{ "id": "9ebf5278-68aa-42a5-96f4-8ec0f90f0f77", "summary": "RTX 4070 QHD 성능 근거", "sourceId": "spec-rtx4070", "metadata": { "sourceType": "PART_SPEC", "title": "RTX 4070 Spec" } }` | `rag_evidence` |
| `GET` | `/api/admin/agent-sessions` | ADMIN | 3번 | `?page=0&size=20` | `{ "items": [{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "status": "RUNNING", "userId": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "createdAt": "2026-06-29T10:35:00Z" }], "page": 0, "size": 20, "total": 1 }` | `agent_sessions` |
| `GET` | `/api/admin/agent-sessions/{id}` | ADMIN | 3번 | - | `{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "status": "SUCCEEDED", "summary": "추천이 완료되었습니다.", "stateTimeline": [], "toolInvocations": [], "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"] }` | `agent_sessions`, `tool_invocations`, `rag_evidence` |
| `GET` | `/api/admin/tool-invocations` | ADMIN | 3번 | `?page=0&size=20` | `{ "items": [{ "id": "4cf44761-e25b-4d5b-bd31-52c13dd9975c", "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "toolName": "compatibility", "status": "PASS", "confidence": "HIGH", "createdAt": "2026-06-29T10:36:10Z" }], "page": 0, "size": 20, "total": 1 }` | `tool_invocations` |
| `GET` | `/api/admin/tool-invocations/{id}` | ADMIN | 3번 | - | `{ "id": "4cf44761-e25b-4d5b-bd31-52c13dd9975c", "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "toolName": "compatibility", "status": "PASS", "confidence": "HIGH", "summary": "호환됩니다.", "requestPayload": {}, "resultPayload": {}, "latencyMs": 120 }` | `tool_invocations` |
| `GET` | `/api/admin/rag-evidence/{id}` | ADMIN | 3번 | - | `{ "id": "9ebf5278-68aa-42a5-96f4-8ec0f90f0f77", "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "sourceId": "spec-rtx4070", "chunkText": "근거 chunk", "summary": "RTX 4070 QHD 성능 근거", "metadata": { "sourceType": "PART_SPEC" }, "score": 0.92 }` | `rag_evidence` |

RAG 공개 범위:

- 일반 RAG API는 요약 중심이다.
- admin RAG API는 `chunkText`, `metadata`, `score`를 포함할 수 있다.

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
| `GET` | `/api/health` | no | 5번 | - | `{ "status": "UP" }` | runtime |

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
| `RequirementDto` | `id` | `string` | no | `2e0f8c9c-8e1c-4d75-94a2-5d6a4977de11` |
| `RequirementDto` | `rawMessage` | `string` | no | `150만원 게임용 PC` |
| `RequirementDto` | `budget` | `number` | yes | `1500000` |
| `RequirementDto` | `usageTags` | `string[]` | yes | `["GAMING"]` |
| `RequirementDto` | `parsedContext` | `object` | yes | `{ "usageTags": ["GAMING"] }` |
| `BuildRecommendRequest` | `requirementId` | `string` | no | `2e0f8c9c-8e1c-4d75-94a2-5d6a4977de11` |
| `BuildRecommendResponse` | `agentSessionId` | `string` | no | `7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a` |
| `BuildRecommendResponse` | `recommendations` | `BuildDto[]` | no | `[{ "id": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd" }]` |
| `BuildRecommendResponse` | `warnings` | `WarningDto[]` | no | `[]` |
| `BuildRecommendResponse` | `evidenceIds` | `string[]` | no | `["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"]` |
| `BuildDto` | `id` | `string` | no | `3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd` |
| `BuildDto` | `name` | `string` | no | `QHD Gaming Build` |
| `BuildDto` | `totalPrice` | `number` | no | `1450000` |
| `BuildDto` | `confidence` | `string` | no | `HIGH` |
| `BuildDto` | `items` | `BuildItemDto[]` | no | `[]` |
| `BuildDto` | `warnings` | `WarningDto[]` | no | `[]` |
| `BuildDto` | `evidenceIds` | `string[]` | no | `["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"]` |
| `BuildDto` | `changeableCategories` | `string[]` | no | `["GPU", "RAM"]` |
| `BuildDto` | `createdAt` | `string` | yes | `2026-06-29T10:20:00Z` |
| `BuildItemDto` | `category` | `string` | no | `GPU` |
| `BuildItemDto` | `partId` | `string` | no | `0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11` |
| `BuildItemDto` | `name` | `string` | no | `RTX 4070` |
| `BuildItemDto` | `price` | `number` | no | `850000` |
| `ChangePartRequest` | `category` | `string` | no | `GPU` |
| `ChangePartRequest` | `partId` | `string` | no | `0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11` |

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
