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

잘못된 형식 파라미터 공통 처리:

- UUID 형식이 아닌 `{id}` path parameter는 존재하지 않는 리소스와 동일하게 `404 NOT_FOUND`를 반환한다. 잘못된 형식이 `500`으로 새지 않는다.
- 숫자 파라미터/본문 값이 숫자로 파싱되지 않으면 `400 VALIDATION_ERROR`와 `details.reason="INVALID_NUMBER"`를 반환한다.

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
| `GET` | `/api/auth/google/start` | no | 1번 | `?redirect=/my/quotes` | `302 Google OAuth redirect` | runtime |
| `GET` | `/api/auth/google/callback` | no | 1번 | Google callback query | `302 /auth/callback?code=one-time-code&redirect=/my/quotes` | `users`, `user_auth_providers`, runtime |
| `POST` | `/api/auth/exchange` | no | 1번 | `{ "code": "one-time-code", "termsAccepted": true, "marketingAccepted": false }` | `{ "accessToken": "jwt-access-token", "refreshToken": "opaque-refresh-token", "user": { "id": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "email": "user@example.com", "name": "홍길동", "role": "USER" } }` | `users`, `user_auth_providers`, `refresh_tokens`, runtime |

Auth/User 구현 owner는 1번이다. 5번은 `Authorization` header 전달, token 저장 helper, `RequireAdmin`, admin guard, security allowlist, 공통 `ErrorResponse` 정합성을 검토한다.

Google OAuth 정책:

- V1 provider는 `GOOGLE` 1개다.
- `/api/auth/google/start`의 `redirect`는 `/`로 시작하고 `//`로 시작하지 않는 내부 경로만 허용한다.
- state와 one-time code는 Redis에 짧은 TTL로 저장하고, exchange 성공 시 one-time code는 1회만 소비한다.
- Google scope는 `openid email profile`만 요청한다.
- Google `email_verified=true`만 허용한다.
- 신규 Google 사용자는 `/api/auth/exchange`에서 `termsAccepted=true`를 보내기 전까지 `400 VALIDATION_ERROR`, `details.reason="TERMS_REQUIRED"`를 받으며 `users` row가 생성되지 않는다.
- 공개 이메일 회원가입과 신규 Google 가입은 항상 `role=USER`만 생성한다. request body의 `role` 값은 받거나 신뢰하지 않는다.
- verified email이 기존 local 계정과 같으면 같은 `users` row에 연결하고 기존 role을 유지한다. 기존 `ADMIN` 이메일이면 `ADMIN`으로 로그인되지만, Google 신규 가입으로 `ADMIN`이 생성되지는 않는다.
- Google access/refresh token은 저장하지 않는다.
- 관리자 계정은 `/admin/login`에서 로그인하며 공개 `/admin/signup`은 만들지 않는다.

### Quote/Build

| Method | Path | Auth | Owner | Request 예시 | Response 예시 | 관련 DB table |
|---|---|---|---|---|---|---|
| `POST` | `/api/requirements/parse` | USER | 1번 | `{ "message": "150만원 게임용 PC", "budget": 1500000, "usageTags": ["GAMING"], "resolution": "QHD", "preferredVendors": ["NVIDIA"], "priority": "성능" }` | `{ "id": "2e0f8c9c-8e1c-4d75-94a2-5d6a4977de11", "rawMessage": "150만원 게임용 PC", "budget": 1500000, "usageTags": ["GAMING"], "parsedContext": { "usageTags": ["GAMING"], "budget": 1500000, "resolution": "QHD", "parseMode": "AGENT_RAG_LLM", "parser": "requirement-parse-agent-v1" }, "questions": [{ "key": "noisePreference", "label": "소음 민감도", "options": ["상관없음", "조용한 편"], "required": false }], "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "agentSummary": "요구사항을 구조화했습니다.", "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"] }` | `requirements`, `agent_sessions`, `rag_evidence` |
| `POST` | `/api/builds/recommend` | USER | 1번 | `{ "requirementId": "2e0f8c9c-8e1c-4d75-94a2-5d6a4977de11", "answers": { "noisePreference": "조용한 편" } }` | `{ "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "recommendations": [{ "id": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd", "name": "균형형 추천 Build", "recommendedFor": "균형 우선", "summary": "내부 자산과 저장된 현재가를 조합했습니다.", "totalPrice": 1450000, "confidence": "HIGH", "items": [], "warnings": [], "toolResults": [{ "tool": "compatibility", "status": "PASS", "confidence": "HIGH", "summary": "CPU, 메인보드, RAM 호환성이 맞습니다." }], "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"], "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "agentSummary": "추천 근거 요약", "changeableCategories": ["GPU", "RAM"] }], "warnings": [], "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"], "toolResults": [] }` | `requirements`, `builds`, `build_items`, `agent_sessions`, `tool_invocations`, `rag_evidence` |
| `POST` | `/api/builds/from-chat` | USER | 1번 | `{ "sourceBuildId": "ai-engine-2-qhd-balanced", "lastUserMessage": "200만원 PC 추천", "build": { "id": "ai-engine-2-qhd-balanced", "tier": "balanced", "title": "QHD 균형 추천 조합", "summary": "AI 챗봇 추천", "totalPrice": 1980000, "budgetWon": 2000000, "confidence": "HIGH", "items": [{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "quantity": 1, "price": 890000 }] } }` | `{ "id": "3ff6d7a2-1c51-4c9d-9720-94b7ef1d62bd" }` | `requirements`, `builds`, `build_items` |
| `POST` | `/api/ai/build-chat` | USER | 3번 | `{ "message": "GPU 빼줘", "currentBuilds": [], "appliedPartPreferences": [], "currentQuoteDraft": { "items": [{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "quantity": 1, "price": 890000 }] } }` | `{ "answerType": "PART", "message": "현재 견적(4개 부품, 2,350,000원) 기준입니다. 제거: RTX 5070. 변경을 반영하면 총액이 2,350,000원 → 1,460,000원 (-890,000원)입니다. 아래 미리보기 카드에서 적용할 수 있습니다.", "builds": [{ "id": "ai-draft-edit-preview-1", "tier": "draft-edit", "label": "변경 미리보기", "title": "변경 적용 미리보기", "summary": "요청하신 변경을 현재 견적에 반영한 구성입니다. 카드의 적용 버튼을 눌러야 실제 견적에 반영됩니다.", "badges": ["DRAFT_EDIT_PREVIEW"], "appliedPartCategories": ["GPU"], "totalPrice": 1460000, "budgetLabel": "146만원", "items": [{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "CPU", "quantity": 1, "price": 550000 }], "toolResults": [{ "tool": "price", "status": "PASS", "confidence": "HIGH", "summary": "저장된 현재가 기준 예산 안에 들어옵니다." }], "warnings": [], "confidence": "HIGH", "evidenceIds": [] }], "warnings": [], "evidenceIds": [], "agentSessionId": null }` | `parts`, `compatibility_rules`, `benchmark_summaries`, `price_snapshots`, `rag_evidence` |
| `GET` | `/api/recommendations/home-parts` | USER | 3번 | `?limit=8` | `{ "items": [{ "recommendationId": "home-part-0e9f3b8b", "rankPosition": 0, "part": { "id": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "name": "RTX 5070", "price": 850000 }, "scoreSource": "FALLBACK", "modelVersion": null, "reasonTags": ["benchmark", "image"] }], "generatedAt": "2026-07-03T10:00:00Z", "fallbackUsed": true }` | `parts`, `benchmark_summaries`, `part_external_offers`, `price_snapshots`, `recommendation_shadow_scores` |
| `POST` | `/api/recommendation-events` | USER | 3번 | `{ "eventType": "SAVE", "sourceSurface": "BUILD_CHAT", "recommendationId": "ai-engine-1", "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "rankPosition": 0, "idempotencyKey": "ui-save-1" }` | `{ "id": "9b2f2f18-3b35-45da-8f65-79e4dc101a10", "eventType": "SAVE", "labelScore": 3.0, "sourceSurface": "BUILD_CHAT", "recommendationId": "ai-engine-1", "category": "GPU", "rankPosition": 0, "createdAt": "2026-07-03T10:00:00Z" }` | `recommendation_events` |
| `POST` | `/api/admin/recommendation-feedback/as-tickets/{id}` | ADMIN | 3번/4번 협업 | `{ "failureCategory": "PART_SELECTION", "severity": "HIGH", "relatedPartId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "logSummaryId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "useForRecommendationTraining": true, "note": "추천 GPU 발열 문제로 확정" }` | 학습 이벤트 생성 시 이벤트 필드가 최상위에 온다: `{ "id": "9b2f2f18-3b35-45da-8f65-79e4dc101a10", "eventType": "AS_CONFIRMED_NEGATIVE", "labelScore": -2.0, "sourceSurface": "ADMIN_AS_FEEDBACK", "recommendationId": null, "category": "GPU", "rankPosition": null, "createdAt": "2026-07-03T10:00:00Z", "label": { "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "failureCategory": "PART_SELECTION", "severity": "HIGH", "useForRecommendationTraining": true }, "trainingEventCreated": true }`. 학습 링크 없음 또는 `useForRecommendationTraining=false`면 라벨 필드 최상위 + `"event": null, "trainingEventCreated": false` | `as_ticket_labels`, `agent_log_summaries`, `recommendation_events`, `as_tickets` 읽기 |
| `POST` | `/api/admin/recommendation-feedback/home-parts` | ADMIN | 3번 | `{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "label": "PROMOTE", "reason": "홈 추천 강화" }` | `{ "id": "9b2f2f18-3b35-45da-8f65-79e4dc101a10", "eventType": "ADMIN_PROMOTE", "labelScore": 4.0, "sourceSurface": "ADMIN_HOME_PART_FEEDBACK", "category": "GPU", "createdAt": "2026-07-03T10:00:00Z" }` | `recommendation_events`, `parts` 읽기 |
| `GET` | `/api/admin/recommendation-models` | ADMIN | 3번 | - | `{ "items": [{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "modelName": "xgboost-reranker", "modelVersion": "xgb-20260703100000", "algorithm": "XGBOOST", "status": "SHADOW", "metrics": { "trainMae": 0.31, "trainRmse": 0.44, "holdout": { "rows": 24, "mae": 0.42, "rmse": 0.55, "spearman": 0.61, "ndcgAt4Global": 0.87, "split": "TIME_LAST_20PCT" } }, "featureSchema": { "features": ["rank_position", "part_price"] }, "createdAt": "2026-07-03T10:00:00Z" }], "page": 0, "size": 50, "total": 1 }` | `recommendation_model_versions`, `recommendation_shadow_scores` |
| `GET` | `/api/admin/recommendation-models/summary` | ADMIN | 3번 | - | `{ "latestModel": { "modelVersion": "xgb-20260703100000", "status": "SHADOW" }, "homeParts": { "windowDays": 7, "impressions": 120, "clicks": 18, "ctr": 0.15, "recentShadowScores": 480, "scoreSources": [{ "scoreSource": "XGBOOST", "count": 120, "share": 1.0 }], "recentCandidates": [{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "name": "RTX 5070", "score": 8.7 }] } }` | `recommendation_model_versions`, `recommendation_events`, `recommendation_shadow_scores` |
| `GET` | `/api/admin/recommendation-shadow/summary?days=7` | ADMIN | 3번 | - | `{ "windowDays": 7, "totalGroups": 42, "scoredGroups": 30, "scoredCandidates": 118, "avgInversionRate": 0.18, "avgTop4ReplacementRate": 0.12, "generatedAt": "..." }` (M4: baseline 순위 대비 실모델 순위 역전율·top4 교체율; baseline-shadow·BUILD_CHAT·중복 회차 제외) | `recommendation_shadow_scores`, `recommendation_model_versions` |
| `GET` | `/api/admin/recommendation-drift?days=14` | ADMIN | 3번 | - | `{ "items": [{ "snapshotDate": "2026-07-06", "metrics": { "catalogFeaturePsi": { "part_price": { "psi": 0.34, "refN": 800, "curN": 640 } }, "predictionDriftPsi": { "psi": 0.05, "modelChanged": false }, "operational": { "fallbackRatio": 0.12, "scoreErrorsDelta": 3, "trainingJobFailureRate": 0.0 } }, "alerts": [{ "series": "catalog.part_price", "level": "SEVERE", "value": 0.34 }] }], "total": 1 }` (M3: 일일 drift 스냅샷) | `recommendation_drift_snapshots` |
| `GET` | `/api/admin/recommendation-training/overview` | ADMIN | 3번 | - | `{ "eligibleEvents": 120, "trainedDistinctEvents": 80, "untrainedEligibleEvents": 40, "excludedDatasetItems": 7, "recentSevenDayEvents": 24, "activeModel": null, "latestJob": { "status": "SUCCEEDED" } }` | `recommendation_events`, `recommendation_training_*`, `recommendation_model_versions` |
| `GET` | `/api/admin/recommendation-training-datasets` | ADMIN | 3번 | - | `{ "items": [{ "id": "7dfb...", "name": "2026-07 홈 추천 데이터셋", "status": "DRAFT", "eligibleCount": 120, "includedCount": 118, "excludedCount": 2 }] }` | `recommendation_training_datasets` |
| `POST` | `/api/admin/recommendation-training-datasets` | ADMIN | 3번 | `{ "name": "2026-07 홈 추천 데이터셋", "eventTypes": ["CLICK", "DETAIL_VIEW", "ADMIN_PROMOTE"], "categories": ["GPU"] }` | `RecommendationTrainingDatasetDto` | `recommendation_events`, `recommendation_training_datasets`, `recommendation_training_dataset_items` |
| `PATCH` | `/api/admin/recommendation-training-datasets/{id}` | ADMIN | 3번 | `{ "name": "데모 학습 데이터셋" }` | `RecommendationTrainingDatasetDto` | `recommendation_training_datasets` |
| `POST` | `/api/admin/recommendation-training-datasets/{id}/items/bulk-include` | ADMIN | 3번 | `{ "eventType": "CLICK", "category": "GPU" }` | `RecommendationTrainingDatasetDto` | `recommendation_training_dataset_items` |
| `POST` | `/api/admin/recommendation-training-datasets/{id}/items/bulk-exclude` | ADMIN | 3번 | `{ "eventType": "IMPRESSION", "reason": "노출만 있는 약한 신호 제외" }` | `RecommendationTrainingDatasetDto` | `recommendation_training_dataset_items` |
| `POST` | `/api/admin/recommendation-training-datasets/{id}/lock` | ADMIN | 3번 | - | `RecommendationTrainingDatasetDto`, `status=LOCKED` | `recommendation_training_datasets` |
| `POST` | `/api/admin/recommendation-training-datasets/{id}/archive` | ADMIN | 3번 | - | `RecommendationTrainingDatasetDto`, `status=ARCHIVED` | `recommendation_training_datasets` |
| `GET` | `/api/admin/recommendation-training-datasets/{id}/items` | ADMIN | 3번 | - | `{ "items": [{ "eventType": "CLICK", "included": true, "labelScoreSnapshot": 1.0, "featuresSnapshot": { "part_price": 880000 } }] }` | `recommendation_training_dataset_items`, `recommendation_events` |
| `GET` | `/api/admin/recommendation-training-jobs` | ADMIN | 3번 | - | `{ "items": [{ "id": "7dfb...", "datasetId": "9b2f...", "status": "QUEUED", "logSummary": "관리자 요청으로 학습 대기열에 등록되었습니다." }] }` | `recommendation_training_jobs` |
| `POST` | `/api/admin/recommendation-training-jobs` | ADMIN | 3번 | `{ "datasetId": "9b2f2f18-3b35-45da-8f65-79e4dc101a10" }` | `RecommendationTrainingJobDto`, `status=QUEUED` | `recommendation_training_jobs` |
| `POST` | `/api/admin/recommendation-models/{id}/activate` | ADMIN | 3번 | - | `RecommendationModelVersionDto`, `status=ACTIVE` | `recommendation_model_versions`, `xgb-reranker /reload` |
| `POST` | `/api/admin/recommendation-models/{id}/retire` | ADMIN | 3번 | - | `RecommendationModelVersionDto`, `status=RETIRED` | `recommendation_model_versions`, `xgb-reranker /reload` |
| `POST` | `/api/build-graphs/resolve` | USER | 1번 | `{ "source": "AI_BUILD", "view": "FOCUSED", "budgetWon": 2000000, "items": [{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "quantity": 1 }], "focus": { "mode": "PART_IMPACT", "category": "GPU", "tool": "power" } }` | `{ "mode": "PART_IMPACT", "summary": "GPU 선택으로 영향을 받는 부품과 제약을 확인했습니다.", "nodes": [{ "id": "part-GPU", "type": "PART", "category": "GPU", "label": "GeForce RTX 5070", "status": "PASS", "position": { "x": 300, "y": 270 } }], "edges": [{ "id": "edge-gpu-psu-power", "source": "part-GPU", "target": "part-PSU", "type": "AFFECTS", "status": "WARN", "label": "전력 여유", "summary": "권장 750W / 현재 650W 기준으로 여유를 판단합니다." }], "focusNodeIds": ["part-GPU", "part-PSU"], "insights": [{ "id": "insight-power", "status": "WARN", "title": "파워 여유 확인", "description": "PSU 정격 출력 여유가 낮습니다.", "relatedNodeIds": ["part-GPU", "part-PSU"] }], "compositeScore": { "score": 854, "maxScore": 1000, "grade": "A", "label": "고성능", "components": [{ "key": "performance", "label": "성능", "score": 356, "maxScore": 430, "percent": 83, "summary": "CPU/GPU 벤치마크와 RAM/저장장치/쿨링 스펙 기반" }], "requestFit": { "status": "PASS", "score": 100, "budgetWon": 2000000, "totalPrice": 1990000, "priceDiff": -10000, "summary": "요청 예산 안에 들어옵니다." } }, "toolResults": [] }` | `parts`, `quote_drafts`, `quote_draft_items`, `benchmark_summaries`, `compatibility_rules`, `build_graph_layouts` |
| `GET` | `/api/admin/build-graph-layouts/default` | ADMIN | 1번/5번 협업 | - | `{ "layoutKey": "DEFAULT", "source": "SAVED", "positions": { "CPU": { "x": 20, "y": 170 }, "GPU": { "x": 300, "y": 270 } }, "updatedAt": "2026-07-03T00:00:00Z" }` | `build_graph_layouts` |
| `PUT` | `/api/admin/build-graph-layouts/default` | ADMIN | 1번/5번 협업 | `{ "positions": { "CPU": { "x": 20, "y": 170 }, "GPU": { "x": 300, "y": 270 } } }` | `{ "layoutKey": "DEFAULT", "source": "SAVED", "positions": { "CPU": { "x": 20, "y": 170 }, "GPU": { "x": 300, "y": 270 } }, "updatedAt": null }` | `build_graph_layouts` |
| `DELETE` | `/api/admin/build-graph-layouts/default` | ADMIN | 1번/5번 협업 | - | `{ "layoutKey": "DEFAULT", "source": "DEFAULT", "positions": { "CPU": { "x": 20, "y": 170 }, "GPU": { "x": 300, "y": 270 } }, "updatedAt": null }` | `build_graph_layouts` |
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

- LLM/RAG 필수 API다. 견적 생성(`BUILD_RECOMMEND`) 요청은 `OPENAI_API_KEY`가 없으면 `428 PRECONDITION_REQUIRED`를 반환하고 가짜 견적 fallback을 만들지 않는다. 단, 라우터가 `UNSUPPORTED`로 강등해 LLM으로 흘린 문장은 LLM이 불가해도 `warnings=["UNSUPPORTED_INTENT"]`와 기능 안내 `quickReplies`를 담은 200 거절 응답으로 마무리한다.
- **모호 요청 되묻기(clarification)**: 예산·용도·구체 모델이 모두 없는 견적 요청("피시 맞춰줘", "해상도 좋은 컴퓨터")은 견적을 생성하지 않고 `GENERAL` 응답에 `quickReplies`(선택지 칩, 각 항목이 용도+예산을 포함한 완전한 프롬프트)와 `clarification.originalMessage`를 담아 되묻는다. 프론트는 다음 요청에 `clarificationContext.originalMessage`를 에코하고, 서버는 이를 message와 합성해 한 문장처럼 라우팅한다(무상태 왕복). clarification 에코는 라우터 즉답 되묻기 외에 LLM이 스스로 되물은 턴(`ASK_FOLLOW_UP`, 또는 카드·칩 없는 부품 추천/변경/설명 응답)과 simulation 교체 대상 미해상 되묻기 턴에도 부착되며, 이 경우 `clarification.missingSlots`는 빈 배열이고 `originalMessage`만 담는다. 에코 부착도 최대 1회 — 이미 되묻기 후속 턴이면 재부착하지 않는다. 되묻기는 최대 1회 — 합성 후에도 정보가 부족하면 기본 예산을 가정하지 않고, 사용자의 원문을 인용해 무엇을 읽지 못했는지 밝히며 한 번 더 정확히 묻는다(`warnings=["LOW_INFORMATION"]`, `quickReplies` 4칩(예: `사무용 100만원`), `clarification.missingSlots=["budget","useCase"]` 포함). 기본 예산 300만원 가정과 `ASSUMED_DEFAULT_BUDGET` warning은 폐지됐다. 이 경로는 LLM을 호출하지 않는 즉답이다.
- `quickReplies`는 되묻기 전용이 아니다. 무예산 용도-only 요청에는 예산대 방향 칩 4종(`100만원대로 추천해줘`, `200만원대로 추천해줘`, `300만원대로 추천해줘`, `예산 무관 고성능으로 추천해줘`), 단일 부품 역제안에는 담기 칩(`{부품명} 견적에 담아줘`), 다중부품 감액 안내에는 카테고리 교체 칩(`{카테고리} 더 저렴한 걸로 바꿔줘`)을 붙인다. 카드·칩·되묻기·simulation이 모두 빈 응답에는 서버가 기능 안내 칩 3종(`200만원 게이밍 PC 추천해줘`, `지금 견적 나머지 채워줘`, `CPU를 9700X로 바꾸면?`) 또는 예산 재시도 칩을 항상 채워 dead-end를 막는다.
- 내부 live benchmark는 optional `X-BuildGraph-AI-Profile` header를 사용할 수 있다. 지원값은 `BUILD_CHAT_FAST`, `BUILD_CHAT_54_FAST`, `BUILD_CHAT_54_MINI_FAST`이며, 사용자 화면은 이 header를 보내지 않는다.
- 기본 Build Chat profile은 `BUILD_CHAT_DEFAULT_PROFILE`이며 실측 결과 기준 기본값은 `BUILD_CHAT_54_MINI_FAST`다. rollback이 필요하면 env에서 `BUILD_CHAT_DEFAULT_PROFILE=BUILD_CHAT_FAST`로 되돌린다. `gpt-5.4`, `gpt-5.4-mini` 후보는 같은 응답 shape를 유지한다.
- Build Chat 의도 분기는 내부 `BuildChatIntentRouter` 결정값을 기준으로 한다. intent는 `SIMULATE_REPLACEMENT`, `BUILD_RECOMMEND`, `ASK_CLARIFICATION`, `UNSUPPORTED` 4종이다. 서버는 decision의 `confidence`, `sideEffectRisk`, `targetCategory`, `partQuery`, `preferredPath`, `cachePolicy`, `semanticConstraintSignature`, `ambiguityReasons`를 내부 로그와 테스트에서만 사용하고 공개 응답 shape에는 노출하지 않는다.
- 라우터가 `UNSUPPORTED`로 판정한 문장은 즉답으로 거절하지 않고 LLM 경로로 강등해 실제 의도(부품 제약·견적 변경·설명)를 처리한다. LLM 호출이 실패한 경우에만 `warnings=["UNSUPPORTED_INTENT"]`와 기능 안내 `quickReplies`를 담아 우아하게 거절한다. 예산 티어 스냅샷·견적 완성·결정적 fast path는 `BUILD_RECOMMEND` intent일 때만 시도하며, 강등된 문장이 예산 즉답을 가로채지 않는다.
- Build Chat 응답 캐시는 Redis exact/shared cache와 Postgres pgvector semantic cache 두 층이다. Redis cache는 `BUILD_CHAT_CACHE_ENABLED=true`, `BUILD_CHAT_CACHE_TTL_SECONDS=600`이 기본이며, 성공한 `POST /api/ai/build-chat` 응답만 저장한다. cache key는 profile, 정규화된 message, selectedCategory, currentQuoteDraft fingerprint, parts/benchmark/FPS/RAG/alias version으로 만든다. 문맥 없는 견적 추천과 문맥 없는 부품 추천은 사용자 id 대신 `shared` key를 사용하고, 장바구니/최근 추천/교체/삭제/담기 문맥이 있는 요청은 사용자별 key를 사용한다. `BUILD_CHAT_CACHE_PREWARM_ENABLED=true`, `BUILD_CHAT_CACHE_PREWARM_TTL_SECONDS=3600`이면 서버 준비 후 `800만원으로 최고급 PC 추천해줘`, `고성능 GPU 추천해줘`를 비동기로 캐시에 올린다. Redis 장애, OpenAI key 없음, prewarm 실패는 서버 시작을 막지 않고 기존 LLM/RAG 흐름으로 우회한다. cache hit 응답은 이전 실행의 `agentSessionId`, `evidenceIds`, `toolInvocationIds`를 재사용하지 않는다.
- Build Chat의 자연어 부품 변경은 항상 변경 미리보기와 명시 적용을 거친다. 단, `quickReplyCommands.type=ADD_MULTI_ITEM_TO_DRAFT`가 붙은 구체 RAM/SSD 추천 칩은 사용자가 상품을 직접 골랐다는 뜻이므로 기존 quote draft item API로 즉시 1개를 추가한다. 동일 상품은 기존 수량에 `quantityDelta`를 더하고, 다른 RAM/SSD는 별도 line item으로 담는다. 이 직접 추가는 전체 호환성 FAIL로 저장을 막지 않지만, 클라이언트는 후속 그래프 검사에서 문제를 안내하고 구매 단계는 기존 호환성 정책으로 차단한다.
- Semantic cache는 `BUILD_CHAT_SEMANTIC_CACHE_ENABLED=true`, threshold `0.94`, TTL `600`초가 기본이다. 적용 대상은 `sideEffectRisk=NONE`, 장바구니/currentBuilds/selectedCategory 문맥이 없는 `BUILD_RECOMMEND`와 문맥 없는 `PART_RECOMMEND`뿐이다. 예산, 부품 class, 카테고리 같은 `semanticConstraintSignature`가 일치하고 embedding similarity가 threshold 이상일 때만 hit로 인정한다. `300만원`과 `800만원`, `5090`과 `5080`, `바꾸면`과 `바꿔줘`는 semantic cache를 공유하지 않는다. embedding 또는 DB 오류가 있으면 기존 Redis/LLM/RAG 흐름으로 우회한다.
- 명시 예산 Build Chat 추천은 예산 모드를 구분한다. `800만원으로`, `800만원짜리`, `800만원 정도`는 target budget으로 보고 **반환하는 모든 추천 카드의 `totalPrice`가** 예산의 87.5%~112.5% 밴드 안에 들어야 한다(일부 카드만이 아니다). 추천 3안의 다양성은 가격대가 아니라 **구성**으로 만든다 — 밴드 밖 조합은 카드 수가 3장 미만이 되더라도 반환하지 않으며(밴드 밖 3장보다 밴드 안 2장), 밴드 안 조합이 전무하면 '가능한 최소 구성' 카드 1장으로 폴백한다. 예산 티어 스냅샷 즉답도 서빙 시점에 요청 예산 기준으로 이 규칙을 재검증하고, 어긋나면 일반 경로로 폴백한다(스냅샷 허용 오차 15% > 밴드 12.5%). `이하`, `안으로`, `안에서`, `안에`, `예산 내`, `범위 내`, `넘지 않게`는 max budget으로 예산 이하를 우선하며, 이때는 예산 이하 범위에서 가성비~예산 근접까지 가격 다양화를 허용한다. `이상`, `최소`, `부터`는 min budget으로 기존 하한 예산 동작을 유지한다. 명시 부품 하드 조건이 있는 경우만 예산 초과를 허용하며 `HARD_CONSTRAINT_OVER_BUDGET` warning을 포함한다.
- Build Chat XGBoost reranker는 실제 견적 추천 순서를 바꾸지 않는다. `RECOMMENDATION_RERANKER_ENABLED=false`, `RECOMMENDATION_RERANKER_SHADOW_ENABLED=true`가 기본이며, 외부 scorer가 응답한 경우에만 `recommendation_shadow_scores`에 점수를 저장한다. Tool `FAIL` 또는 하드 조건 위반으로 제외된 후보는 shadow scoring 대상에도 들어가지 않는다.
- 홈 하단 `인기 부품 랭킹`은 `GET /api/recommendations/home-parts`가 제공하는 내부 자산 추천부품을 사용한다. 현재 홈 화면은 `limit=8`로 2줄 랭킹을 표시하고, API는 limit을 생략하면 4개를 기본 반환한다. 이 API는 견적 생성기가 아니라 쇼핑 발견용 부품 랭킹이다. trained model scorer가 정상 응답하면 XGBoost score를 우선 정렬에 반영하고, 모델 파일 없음, baseline-shadow, scorer 실패, 응답 오류인 경우에는 benchmark, 이미지/offer, 가격 갱신 신선도, toolReady, FPS coverage 기반 deterministic fallback을 반환한다. `scoreSource`는 실제 정렬에 쓰인 점수 출처와 항상 일치한다. scorer가 baseline임이 확인되면 홈 응답은 scorer 호출을 기다리지 않고 비동기 shadow 기록만 수행하며(같은 후보 집합은 `recommendation.reranker.shadow-throttle-ms` 기본 5분 스로틀), `ACTIVE` 모델 승급/은퇴 시 다음 요청부터 즉시 동기 XGBoost 정렬로 전환/복귀한다.
- XGBoost scorer는 Docker의 `xgb-reranker` 서비스로 운영한다. Docker 내부 API 기본 endpoint는 `http://xgb-reranker:8091/score`이고, 로컬 jar 단독 실행 시에는 `RECOMMENDATION_RERANKER_ENDPOINT=http://localhost:8091/score`로 바꿔 실행한다.
- XGBoost 학습 운영은 관리자 수동 흐름이다. dataset 생성은 eligible event를 snapshot으로 고정하고, `DRAFT` 상태에서만 include/exclude를 수정할 수 있다. `LOCKED` dataset만 training job을 만들 수 있으며, `xgb-reranker` worker가 queued job을 polling해 학습한다.
- dataset item bulk include/exclude는 `eventType`/`category` 필터 방식과 함께 `itemIds`(dataset item public_id 배열)를 받는다. `itemIds`가 있으면 필터보다 우선하며, 응답 `RecommendationTrainingDatasetDto`에는 갱신된 행 수 `updatedItems`가 포함된다.
- 50건 미만 dataset은 기본적으로 `SKIPPED_LOW_DATASET`으로 끝나며 모델 버전을 만들지 않는다. 학습 성공 모델은 `SHADOW`로만 저장된다. `POST /api/admin/recommendation-models/{id}/activate`는 (1) `SHADOW` 상태, (2) `artifactPath` 존재, (3) 학습 워커가 기록한 `metrics.holdout` 평가 지표 존재, (4) scorer reload 성공을 모두 요구한다. holdout 지표가 없는 구버전 모델은 `409 CONFLICT_STATE`로 거부되며 최신 학습 워커로 재훈련해야 한다. 활성화 성공 시 기존 `ACTIVE` 모델은 자동으로 `RETIRED` 처리된다. 품질 판단과 activate 게이트는 `metrics.holdout` 기준이며 `trainMae`/`trainRmse`는 과적합 참고용이다. 활성 모델 파일은 `/models/home-parts-active.json`로 유지한다.
- 추천 학습 이벤트는 `POST /api/recommendation-events`로 수집한다. 홈 추천부품은 노출 `IMPRESSION`, 클릭 `CLICK`, 상세 진입 `DETAIL_VIEW`, 견적 담기 `ADD_PART_TO_DRAFT`를 기록한다. Public 이벤트 API의 `sourceSurface`는 `BUILD_CHAT`(생략 시 기본값), `HOME_RECOMMENDED_PARTS`만 허용하며 그 외 값(예: `ADMIN_AS_FEEDBACK`)은 `400 VALIDATION_ERROR`로 거부한다. 관리자 전용 이벤트인 `AS_CONFIRMED_NEGATIVE`, `ADMIN_PROMOTE`, `ADMIN_DEMOTE`는 `403 FORBIDDEN`으로 거부한다. 관리자는 `/api/admin/recommendation-feedback/home-parts`로 `ADMIN_PROMOTE` 또는 `ADMIN_DEMOTE` 라벨을 추가할 수 있다.
- AS 접수는 자동 negative label이 아니다. 관리자가 `/api/admin/recommendation-feedback/as-tickets/{id}`로 `as_ticket_labels`를 확정하고, `useForRecommendationTraining=true`이며 관련 `part/build/recommendationId`가 있는 경우에만 `AS_CONFIRMED_NEGATIVE`, `sourceSurface=ADMIN_AS_FEEDBACK` 이벤트가 생성된다. optional `logSummaryId`는 해당 티켓의 `agent_log_summaries` public_id여야 하며 다른 티켓 소속이면 `400 VALIDATION_ERROR`, 생략하면 최신 요약을 자동 연결한다. 같은 티켓을 재확정하면 새 이벤트를 만들지 않고 기존 `AS_CONFIRMED_NEGATIVE` 이벤트의 part/build/recommendation 링크를 갱신하며 `trainingEventCreated=false`를 반환한다. 이 API는 `as_tickets.status`, `cause_candidates`, `upgrade_candidates`를 수정하지 않는다.
- 추천 학습 dataset은 `sourceSurfaces` 필터로 `HOME_RECOMMENDED_PARTS`, `ADMIN_HOME_PART_FEEDBACK`, `ADMIN_AS_FEEDBACK`를 포함/제외할 수 있다. AS row의 `featuresSnapshot`에는 `agent_log_summaries.feature_payload`, `risk_flags`, `as_ticket_labels`의 확정 라벨만 포함하고 raw gzip/전체 JSONL은 포함하지 않는다.
- LLM은 intent, 예산, 용도, 해상도, 부품 카테고리, 하드 제약만 구조화한다. 부품 ID, 가격, FPS, 상품명은 서버가 내부 DB에서만 선택한다.
- 전체 견적 요청은 `parts.status=ACTIVE`인 실제 부품만 사용해 AI build 후보 1~3개(기본 3개)를 반환한다. 명시 예산 target 모드에서 밴드(±12.5%) 안 조합이 3개 미만이면 그 수만큼만 반환한다. 예산 없이 용도만 있는 요청은 즉시 3안을 만들지 않고 LLM이 예산대를 되물으며 `quickReplies`에 예산대 방향 칩 4종을 붙인다(예산 무관·최고급 명시 요청은 즉시 추천 유지). 예산 근접 조합을 구성하지 못하면 빈 `builds` 대신 '가능한 최소 구성' 카드 1장과 경고 문구를 반환한다. GAMING/VIDEO_EDIT/AI_DEV처럼 GPU가 필요한 용도는 GPU 포함 최소 구성가(AI 용도는 VRAM 8GB 이상) 기준으로 예산 미달을 판정·고지하고(`BUDGET_BELOW_USAGE_MINIMUM` warning), GPU 포함 '가능한 최소 구성' 카드와 예산 상향/용도 하향 칩을 제공한다.
- 부품 질문은 LLM이 판단한 카테고리에서 후보 3개를 실제 `parts.price`와 내부 자산 기준으로 반환하고, `currentBuilds`의 해당 카테고리를 서버가 다시 조회한 partId 가격으로 교체한다. 문맥 없는 단일 부품 추천에 GPU class(`RTX 5080` 등)나 제조사(`MSI 메인보드` 등)가 명시되면 서버가 ACTIVE 내부 자산을 다시 필터링해 해당 class/제조사와 일치하는 후보만 반환한다. 일치 후보가 없을 때는 무관한 TOP3로 대체하지 않는다.
- 부품 질문에 `currentBuilds`가 없거나 복원할 수 없으면 기본 예산 build를 새로 만들지 않고 `builds=[]`를 유지한다. 부품 제약(스펙·예산) 요청은 서버가 내부 DB를 대조해 역제안으로 답한다: 예산만 있으면 예산 내 최상 스펙 TOP3와 담기 칩, 요구 스펙 미보유면 보유 최고 스펙 근접 대안(`PART_CONSTRAINT_NOT_FOUND` warning), 스펙은 있으나 예산 부족이면 최저가·부족액·예산 내 대안(`PART_BUDGET_SHORTFALL` warning), 충족이면 TOP3 나열과 담기 칩을 반환한다. 현재 메인보드 소켓과 맞지 않는 CPU 요청은 빈 후보 대신 장착 불가 사유와 현재 보드 호환 CPU 대안을 제시하고, 상향 요청에서 더 높은 후보가 없으면 '현재 부품이 내부 자산 기준 이미 최상위 구성' 사유를 명시한다.
- 명시한 부품·제조사·용량·출력 조건을 모두 만족하면서 자동 검증을 통과한 조합이 없으면 무관한 일반 견적으로 대체하지 않는다. `builds=[]`, `PART_CONSTRAINT_NOT_FOUND`, 사용자 원문을 인용한 실패 사유와 `조건 유지+예산 상향`/`일부 조건 완화` 칩으로 응답한다.
- 서버는 client가 보낸 part 이름/가격을 신뢰하지 않는다. `currentBuilds[].items[].partId`를 기준으로 DB에서 현재 `parts.price`와 attributes를 다시 읽는다.
- 각 AI build에는 기존 Tool 검증 결과를 `toolResults`로 포함한다. 호환·전력·장착 등 blocking Tool `FAIL` 조합은 추천 카드에서 제외한다. 견적 변경 미리보기 역시 변경 후 전체 구성을 재검증하고, `FAIL`이면 적용 가능한 `draft-edit` 카드를 만들지 않은 채 실패 사유와 재요청 `quickReplies`만 반환한다. Tool `WARN` 조합은 경고를 포함해 유지한다.
- AI build는 대화용 DTO이며 `POST /api/ai/build-chat` 응답 시점에는 `builds/build_items`에 저장하지 않는다. 대화 이력 저장은 프론트 `sessionStorage` 범위다. 사용자가 저장 버튼을 누르면 프론트가 `POST /api/builds/from-chat`을 호출해 현재 로그인 사용자 기준 `requirements`, `builds`, `build_items`로 저장한다.
- `POST /api/builds/from-chat`은 AI 재호출이나 RAG 재검색을 하지 않는다. 요청의 `build.items[].partId`와 `category`를 서버 DB의 ACTIVE 부품과 대조하고, 사용자가 본 `price * quantity`를 `build_items.price` 라인 가격으로 저장한다. 저장 시 Tool 검증은 서버에서 다시 실행해 `warnings`에 반영한다.
- `POST /api/builds/from-chat`은 V1에서 category별 1개 `build_items` row만 저장한다. `build_items`에는 `quantity` 컬럼을 추가하지 않으며, 요청의 `quantity`는 표시 line total 계산 입력으로만 사용한다. RAM/STORAGE 다중 수량을 정확히 영속화하는 사용자 장바구니 흐름은 `quote_drafts`, `quote_draft_items`가 담당한다.
- `sourceBuildId`는 `sessionStorage` 기반 임시 추천 식별자다. 서버는 같은 `sourceBuildId`의 중복 저장을 DB unique key로 막지 않는다. 중복 클릭 방지는 프론트 성공 상태/버튼 상태로 처리하고, 저장 API idempotency는 V1 범위에서 제외한다.
- 순수 화면 이동 명령은 사용자 체감 속도를 위해 프론트가 먼저 처리한다. 서버 공개 응답은 `OPEN_ROUTE` action을 반환하지 않으며, 서버 주도 화면 이동 자동 실행 계약은 폐지됐다.
- `최상급 CPU와 GPU로 추천`처럼 예산 무관 표현과 둘 이상의 부품 카테고리가 함께 명시된 요청은 첫 번째 카테고리의 단일 부품 추천으로 축소하지 않고 전체 견적 추천으로 처리한다.
- 명시 예산은 LLM `parsedContext`와 별개로 서버가 raw message에서 다시 판정한다. 하드 부품 조건이 없으면 target budget은 `±12.5%`, max budget은 상한 이하, min budget은 하한 이상으로 최종 추천을 거른다.
- `바꾸면/교체하면/넣으면/달면/차이/FPS/성능` 계열은 견적 변경보다 우선해 읽기 전용 `simulation`으로 분리한다. 'A에서 B로' 문형은 도착지(B) 구간에서 target을 해상하고 모델 토큰의 조사(에서/으로/로/를 등)를 제거해 카탈로그와 대조한다. target 부품이 명확하지 않아도 임의 후보를 고르지 않는다 — 쿨러/SSD/케이스처럼 닫힌 속성 어휘(수랭·공랭, PCIe 세대, 통풍)가 있고 드래프트에 같은 카테고리 부품이 있으면 LLM 속성 경로로 흘려 '현재 부품 vs 속성 충족 최저가 후보'의 1:1 스펙비교 simulation 카드(answerType=`GENERAL`, quickReplies 없음)로 답하고, 그 외에는 `clarification` 원문 에코를 붙인 되묻기로 내려간다(최대 1회). 시뮬레이션으로 판정된 턴은 LLM 응답이 변경 의도로 흔들려도 `draft-edit` 미리보기나 장바구니 mutation을 생성하지 않는다. `SIMULATION_TARGET_NOT_FOUND` warning은 드래프트가 비었거나 카테고리를 못 잡은 시뮬 안내 즉답에만 남는다.
- `currentQuoteDraft`가 전달된 **자연어** 변경 요청(교체/제거/추가/수량)은 `builds[]`에 '변경 미리보기' 카드 1장(tier=`draft-edit`, badges=[`DRAFT_EDIT_PREVIEW`], `appliedPartCategories`=[변경 카테고리], answerType=`PART`)으로 변경안을 반환하고, 메시지에 현재 견적 기준 총액 before→after 델타와 교체/제거/추가 요약을 붙인다. AI API는 `quote_drafts`, `quote_draft_items`를 직접 쓰지 않으며, 사용자가 카드의 적용 버튼을 눌러야 프론트가 `PUT /api/quote-drafts/current/apply-ai-build`로 반영한다 — 즉시 자동 반영은 없다. 단, 구체 RAM/SSD 추천 칩의 `quickReplyCommands` 직접 추가 정책은 이 자연어 변경 규칙의 예외이며, 상세 동작은 위 직접 추가 정책을 따른다. 프론트는 변경 명령 어휘(바꿔/교체/싼/저렴/올려/빼/제거/삭제/넣어/담아/추가/수량/늘려/줄여)가 보이면 최신 드래프트를 `currentQuoteDraft`로 실어 보낸다.
- 공개 `/api/ai/build-chat` 응답은 `actions` 필드를 반환하지 않는다. 내부 `AiChatEngine`의 action(`OPEN_SELF_QUOTE`, `ADD_BUILD_TO_DRAFT`, `CREATE_PRICE_ALERT` 등)은 서버 내부용이며 공개 응답 shape에 노출하지 않는다. 견적 변경은 '변경 미리보기' 카드와 적용 버튼(`PUT /api/quote-drafts/current/apply-ai-build`)으로 수행한다.
- AI build의 사용자 표시 `totalPrice`는 항상 `items[].price * items[].quantity` 합계다. LLM/엔진의 `estimatedTotalPrice`는 내부 참고값이며 홈 추천 카드와 견적초안 장바구니 총액의 기준으로 사용하지 않는다.
- LLM JSON 계약 위반이나 외부 LLM 호출 실패는 견적 생성(`BUILD_RECOMMEND`) 흐름에서 `502 BAD_GATEWAY`로 반환한다. 라우터가 `UNSUPPORTED`로 강등해 LLM으로 흘린 문장은 LLM 실패 시 502 대신 `warnings=["UNSUPPORTED_INTENT"]`와 기능 안내 `quickReplies`를 담은 200 거절 응답을 반환한다.
- “RTX 5090 글카 들어간 PC” 같은 명시 부품 조건은 `requiredGpuClasses`로 보존하되 하드 제약 승격은 조건부다. `말고/빼고/대신/제외/아닌/없는/없이` 같은 부정 문맥의 모델명은 하드 제약으로 승격하지 않고 예산 완화·대체 조합을 허용한다. 이 원문 부정 판정은 LLM이 잘못 만든 하드 제약보다 우선한다. LLM이 `hardConstraintPolicy=NONE`(소프트 선호)으로 명시하면 서버가 `MUST_INCLUDE`로 되강제하지 않고 예산 맞춤 대체·역제안을 허용하며, LLM이 값을 주지 않은 경우에만 `MUST_INCLUDE`로 승격한다. `MUST_INCLUDE`가 유지된 경우에는 예산이 부족해도 부품을 낮추지 않고 예산 초과 warning을 반환한다.
- Build Chat `warnings[]` 주요 코드: 부품 제약을 만족하는 스펙 자체가 없으면 `PART_CONSTRAINT_NOT_FOUND`, 스펙은 있으나 예산이 모자라면 `PART_BUDGET_SHORTFALL`, GPU 필수 용도의 최소 구성가 미달이면 `BUDGET_BELOW_USAGE_MINIMUM`(용도를 모르면 기존 `BUDGET_BELOW_MINIMUM`), 되묻기 턴은 `LOW_INFORMATION`, LLM 불가 거절은 `UNSUPPORTED_INTENT`다. `ASSUMED_DEFAULT_BUDGET`은 폐지됐다.

`POST /api/build-graphs/resolve` v1 정책:

- 이 API는 UI 설명용 관계 그래프를 반환하며 DB를 쓰지 않는다.
- `source=AI_BUILD`는 request의 `items[].partId`만 신뢰하고, 가격/이름/spec은 현재 `parts` DB에서 다시 조회한다.
- `source=QUOTE_DRAFT_CURRENT`는 request의 `items`를 무시하고 로그인 사용자의 active quote draft를 서버에서 조회한다.
- 그래프의 최종 상태는 기존 `ToolCheckService`의 `compatibility`, `power`, `size`, `performance`, `price` 결과를 기준으로 한다.
- 기본 edge는 `CPU -> MOTHERBOARD`, `MOTHERBOARD -> RAM`, `CPU -> COOLER`, `GPU -> PSU`, `GPU -> CASE`, `COOLER -> CASE`, `CPU -> GPU`, `budget -> totalPrice`다.
- `nodes[].position`은 관리자 `/api/admin/build-graph-layouts/default`에서 저장한 category 기준 좌표다. 프론트는 이 좌표를 우선 사용하고, 같은 category 노드가 여러 개면 겹침 방지를 위해 주변으로 보정할 수 있다.
- `view=FOCUSED`는 프론트의 기본 표시 모드이며, v1 서버 응답은 핵심 관계 전체를 반환하고 프론트가 focus node와 warning insight를 우선 노출한다.
- `compositeScore`는 완성 PC 조합의 사용자 표시용 성능/완성도 `0~1000점`이다. 가격 효율은 섞지 않고 `requestFit`으로 분리한다. `compatibility`, `power`, `size` 중 하나라도 `FAIL`이면 `score=0`이며, 세부 정책은 `docs/build-composite-score-policy.md`를 따른다.
- 활성 부품이 없거나 `partId`와 `category`가 불일치하면 graph를 만들지 않고 400/404 오류를 반환한다.

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
| `GET` | `/api/parts` | USER | 2번 | `?category=GPU&q=5070&manufacturer=NVIDIA&status=ACTIVE&minPrice=500000&maxPrice=1300000&page=0&size=20&sort=compatibility&compatibilitySource=QUOTE_DRAFT_CURRENT` | `{ "items": [{ "id": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "name": "GeForce RTX 5070", "manufacturer": "NVIDIA", "price": 960000, "status": "ACTIVE", "attributes": { "wattage": 250 }, "compatibility": { "status": "PASS", "statusLabel": "호환 가능", "summary": "현재 조합 기준 호환 가능합니다.", "checkedTools": ["power", "size", "performance"] }, "latestPriceSource": "MANUAL_CURRENT_LINEUP", "externalOffer": { "imageUrl": "https://...", "supplierName": "Naver Store", "offerUrl": "https://...", "lowPrice": 950000, "source": "NAVER_SHOPPING_SEARCH", "refreshedAt": "2026-06-29T10:25:00Z" } }], "page": 0, "size": 20, "total": 1 }` | `parts`, `quote_drafts`, `quote_draft_items`, `price_snapshots`, `benchmark_summaries`, `part_external_offers` |
| `POST` | `/api/parts/compatible-candidates` | USER | 2번 | `{ "source": "AI_BUILD", "category": "GPU", "items": [{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "quantity": 1 }], "limit": 5 }` | `{ "category": "GPU", "items": [{ "part": { "id": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "name": "GeForce RTX 5070 Ti", "price": 990000, "status": "ACTIVE", "attributes": {} }, "status": "PASS", "statusLabel": "여유 있음", "summary": "현재 조합 기준 호환 가능합니다.", "checkedTools": ["power", "size", "performance"] }], "rejectedCount": 1, "warnings": [] }` | `parts`, `quote_drafts`, `quote_draft_items`, `benchmark_summaries` |
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
| `POST` | `/api/admin/parts/external-offers/refresh` | ADMIN | 2번 | `?category=GPU&limit=20&force=false` | `{ "configured": true, "category": "GPU", "limit": 20, "force": false, "attempted": 7, "updated": 7, "skipped": 0, "failed": 0, "errors": 0 }` | `parts`, `part_external_offers`, `price_snapshots` |
| `POST` | `/api/admin/parts/danawa-price-snapshots/refresh` | ADMIN | 2번 | `?category=GPU&limit=20&force=false` | `{ "configured": true, "category": "GPU", "limit": 20, "force": false, "attempted": 7, "collected": 6, "skipped": 1, "failed": 0 }` | `parts`, `part_external_offers`, `price_snapshots` |
| `POST` | `/api/admin/parts/danawa-price-trends/refresh` | ADMIN | 2번 | `?category=GPU&limit=20&months=6&force=false` | `{ "configured": true, "category": "GPU", "months": 6, "limit": 20, "force": false, "attempted": 7, "collectedParts": 5, "collectedPoints": 35, "skipped": 1, "missing": 1, "failed": 0 }` | `parts`, `part_external_offers`, `price_snapshots` |
| `GET` | `/api/admin/parts/quality-report` | ADMIN | 2번 | - | `{ "summary": { "activeParts": 286, "toolReadyMissing": 3, "requiredSpecMissing": 4, "benchmarkMissing": 0, "fpsCoverageGap": 2, "aliasReviewOpen": 1 }, "categories": [], "actionItems": [] }` | `parts`, `benchmark_summaries`, `game_fps_coverage_gaps`, `part_alias_review_items` |
| `GET` | `/api/admin/manufacturer-sources` | ADMIN | 2번 | `?enabled=true&status=ACTIVE&category=GPU&includeDeleted=true` | `{ "items": [{ "id": "2f9d...", "manufacturer": "ASUS", "categoryScope": "GPU", "sourceType": "NEWS", "sourceUrl": "https://...", "enabled": true, "pollIntervalMinutes": 1440, "status": "ACTIVE", "deletedAt": null }] }` | `manufacturer_sources` |
| `POST` | `/api/admin/manufacturer-sources` | ADMIN | 2번 | `{ "manufacturer": "ASUS", "categoryScope": "GPU", "sourceType": "NEWS", "sourceUrl": "https://www.asus.com/news/", "enabled": true, "pollIntervalMinutes": 1440, "parserConfig": {} }` | `{ "id": "2f9d...", "manufacturer": "ASUS", "categoryScope": "GPU", "sourceType": "NEWS", "sourceUrl": "https://www.asus.com/news/", "enabled": true, "status": "ACTIVE" }` | `manufacturer_sources`, `admin_audit_logs` |
| `GET` | `/api/admin/manufacturer-sources/{id}` | ADMIN | 2번 | `?includeDeleted=true` | `ManufacturerSourceDto` | `manufacturer_sources` |
| `PATCH` | `/api/admin/manufacturer-sources/{id}` | ADMIN | 2번 | `{ "enabled": false, "status": "PAUSED", "parserConfig": { "itemSelector": ".news-card" } }` | `{ "id": "2f9d...", "enabled": false, "status": "PAUSED", "updatedAt": "2026-07-01T09:00:00Z" }` | `manufacturer_sources`, `admin_audit_logs` |
| `DELETE` | `/api/admin/manufacturer-sources/{id}` | ADMIN | 2번 | - | `{ "id": "2f9d...", "deleted": true }` | `manufacturer_sources`, `admin_audit_logs` |
| `POST` | `/api/admin/manufacturer-sources/{id}/restore` | ADMIN | 2번 | - | `ManufacturerSourceDto`, 복구 후 `enabled=false`, `status=PAUSED` | `manufacturer_sources`, `admin_audit_logs` |
| `POST` | `/api/admin/manufacturer-sources/{id}/scan` | ADMIN | 2번 | `?limit=20&createCandidates=true` | `{ "sourceId": "2f9d...", "manufacturer": "ASUS", "failed": false, "unchanged": false, "parsedPosts": 5, "newPosts": 2, "updatedPosts": 1, "ignoredPosts": 1, "productPosts": 1, "createdCandidates": 1, "posts": [] }` | `manufacturer_sources`, `manufacturer_posts`, `part_catalog_refresh_jobs`, `part_catalog_candidates` |
| `POST` | `/api/admin/manufacturer-sources/scan` | ADMIN | 2번 | `?limitPerSource=20&createCandidates=true` | `{ "scannedSources": 8, "newPosts": 4, "createdCandidates": 2, "failedSources": 1, "candidateBackfill": { "attempted": 3, "created": 1 }, "results": [] }` | `manufacturer_sources`, `manufacturer_posts`, `part_catalog_refresh_jobs`, `part_catalog_candidates` |
| `GET` | `/api/admin/manufacturer-posts` | ADMIN | 2번 | `?status=PRODUCT_CANDIDATE&category=GPU&includeDeleted=true&page=0&size=20` | `{ "items": [{ "id": "8f3...", "manufacturer": "ASUS", "externalUrl": "https://...", "title": "ASUS launches ...", "classificationStatus": "PRODUCT_CANDIDATE", "detectedCategory": "GPU", "catalogCandidateId": "9a1...", "deletedAt": null }], "page": 0, "size": 20, "total": 1 }` | `manufacturer_posts`, `manufacturer_sources`, `part_catalog_candidates` |
| `POST` | `/api/admin/manufacturer-posts` | ADMIN | 2번 | `{ "sourceId": "2f9d...", "externalUrl": "https://www.asus.com/news/new-gpu", "title": "ASUS launches RTX 5090", "classificationStatus": "PRODUCT_CANDIDATE", "detectedCategory": "GPU", "detectedProductName": "RTX 5090" }` | `ManufacturerPostDto` | `manufacturer_posts`, `admin_audit_logs` |
| `GET` | `/api/admin/manufacturer-posts/{id}` | ADMIN | 2번 | `?includeDeleted=true` | `ManufacturerPostDto` | `manufacturer_posts`, `manufacturer_sources`, `part_catalog_candidates` |
| `PATCH` | `/api/admin/manufacturer-posts/{id}` | ADMIN | 2번 | `{ "classificationStatus": "IGNORED", "detectedCategory": null, "detectedProductName": null }` | `ManufacturerPostDto` | `manufacturer_posts`, `admin_audit_logs` |
| `DELETE` | `/api/admin/manufacturer-posts/{id}` | ADMIN | 2번 | - | `{ "id": "8f3...", "deleted": true }` | `manufacturer_posts`, `admin_audit_logs` |
| `POST` | `/api/admin/manufacturer-posts/{id}/restore` | ADMIN | 2번 | - | `ManufacturerPostDto` | `manufacturer_posts`, `admin_audit_logs` |
| `POST` | `/api/admin/manufacturer-posts/{id}/create-candidate` | ADMIN | 2번 | - | `{ "configured": true, "created": true, "candidateId": "9a1...", "title": "ASUS RTX 5090 ...", "lowPrice": 3980000 }` | `manufacturer_posts`, `part_catalog_refresh_jobs`, `part_catalog_candidates` |
| `POST` | `/api/admin/manufacturer-posts/{id}/ai-asset-draft` | ADMIN | 2번 | - | `{ "postId": "8f3...", "aiUsed": true, "classificationStatus": "PRODUCT_CANDIDATE", "detectedCategory": "GPU", "detectedProductName": "RTX 5090", "confidence": "HIGH", "candidateId": "9a1...", "candidateStatus": "PENDING_REVIEW", "partId": null, "partStatus": null, "messages": ["AI가 제조사 게시글을 신제품 후보와 스펙 초안으로 구조화했습니다."] }`. `partId`/`partStatus`는 항상 `null`이다 | `manufacturer_posts`, `part_catalog_refresh_jobs`, `part_catalog_candidates`, `admin_audit_logs` |
| `GET` | `/api/admin/part-catalog-candidates` | ADMIN | 2번 | `?status=DISCOVERED&source=MANUFACTURER_RELEASE_NAVER_SEARCH&includeDeleted=true&page=0&size=20` | `{ "items": [{ "id": "9a1...", "source": "MANUFACTURER_RELEASE_NAVER_SEARCH", "category": "GPU", "title": "ASUS RTX 5090 ...", "candidateStatus": "DISCOVERED", "lowPrice": 3980000, "deletedAt": null }], "page": 0, "size": 20, "total": 1 }` | `part_catalog_candidates`, `parts` |
| `GET` | `/api/admin/part-catalog-candidates/{id}` | ADMIN | 2번 | `?includeDeleted=true` | `PartCatalogCandidateDto` | `part_catalog_candidates`, `parts` |
| `PATCH` | `/api/admin/part-catalog-candidates/{id}` | ADMIN | 2번 | `{ "title": "ASUS RTX 5090", "manufacturerGuess": "ASUS", "lowPrice": 3980000, "offerUrl": "https://..." }` | `PartCatalogCandidateDto` | `part_catalog_candidates`, `admin_audit_logs` |
| `DELETE` | `/api/admin/part-catalog-candidates/{id}` | ADMIN | 2번 | - | `{ "id": "9a1...", "deleted": true }` | `part_catalog_candidates`, `admin_audit_logs` |
| `POST` | `/api/admin/part-catalog-candidates/{id}/restore` | ADMIN | 2번 | - | `PartCatalogCandidateDto` | `part_catalog_candidates`, `admin_audit_logs` |
| `POST` | `/api/admin/part-catalog-candidates/{id}/approve` | ADMIN | 2번 | - | `{ "candidateId": "9a1...", "publishedPartId": "0e9...", "created": true, "partStatus": "INACTIVE", "status": "PUBLISHED" }` | `part_catalog_candidates`, `parts`, `part_external_offers`, `price_snapshots`, `admin_audit_logs` |
| `POST` | `/api/admin/part-catalog-candidates/{id}/reject` | ADMIN | 2번 | `{ "reason": "공식 신제품이 아닌 이벤트 게시글" }` | `{ "candidateId": "9a1...", "status": "REJECTED", "reason": "공식 신제품이 아닌 이벤트 게시글" }` | `part_catalog_candidates`, `admin_audit_logs` |
| `POST` | `/api/admin/part-catalog-candidates/{id}/refresh-offers` | ADMIN | 2번 | - | `{ "configured": true, "candidateId": "9a1...", "updated": true, "attempted": 1, "title": "ASUS RTX 5090 ...", "lowPrice": 3980000 }` | `part_catalog_candidates` |
| `GET` | `/api/admin/part-alias-review-items` | ADMIN | 2번 | `?status=OPEN&category=GPU&targetField=gpuClass&sourceType=AI_BUILD_CHAT&page=0&size=20` | `{ "items": [{ "id": "4d2...", "sourceType": "AI_BUILD_CHAT", "category": "GPU", "targetField": "rank", "aliasText": "5070티아이", "status": "OPEN" }], "page": 0, "size": 20, "total": 1 }` | `part_alias_review_items`, `parts`, `part_alias_rules` |
| `GET` | `/api/admin/part-alias-review-items/summary` | ADMIN | 2번 | - | `{ "items": [{ "category": "GPU", "targetField": "gpuClass", "sourceType": "AI_BUILD_CHAT", "count": 1 }] }` | `part_alias_review_items` |
| `POST` | `/api/admin/part-alias-review-items/{id}/resolve` | ADMIN | 2번 | `{ "aliasText": "5070티아이", "category": "GPU", "targetField": "gpuClass", "canonicalValue": "RTX_5070_TI", "note": "한국어 표기 alias" }` | `PartAliasReviewItemDto` | `part_alias_review_items`, `part_alias_rules`, `admin_audit_logs` |
| `POST` | `/api/admin/part-alias-review-items/{id}/ignore` | ADMIN | 2번 | `{ "note": "처리 불필요" }` | `PartAliasReviewItemDto` | `part_alias_review_items`, `admin_audit_logs` |
| `GET` | `/api/admin/part-alias-rules` | ADMIN | 2번 | `?category=GPU&targetField=gpuClass&page=0&size=50` | `{ "items": [{ "id": "f21...", "category": "GPU", "targetField": "gpuClass", "aliasText": "5070티아이", "canonicalValue": "RTX_5070_TI" }], "page": 0, "size": 50, "total": 1 }` | `part_alias_rules` |
| `POST` | `/api/admin/part-alias-rules` | ADMIN | 2번 | `{ "aliasText": "골드 파워", "category": "PSU", "targetField": "efficiency", "canonicalValue": "GOLD" }` | `PartAliasRuleDto` | `part_alias_rules`, `admin_audit_logs` |

`/api/admin/parts/quality-report`의 `requiredSpecMissing`은 ToolCheckService가 실제 판정에 쓰는 필드 기준이다. 예를 들어 CPU는 `socket`과 `wattage|tdpW`, GPU는 `gpuClass`, `lengthMm`, `wattage`, `requiredSystemPowerW`, `vramGb`, CASE는 `maxGpuLengthMm`, `maxCpuCoolerHeightMm`, COOLER는 `socketSupport`, `heightMm|coolerHeightMm`를 본다. ToolCheckService가 직접 쓰지 않는 표시용 스펙은 이 누락 수에 포함하지 않는다.

`POST /api/price-snapshots/collect`는 공개 API가 아니다. 가격 스냅샷 생성은 `/api/admin/price-jobs/run` 뒤의 내부 service 처리다.

`POST /api/price-alerts`는 같은 사용자, 같은 `partId`, 같은 `targetPrice`의 `ACTIVE` 알림이 이미 있으면 `409 DUPLICATE_RESOURCE`를 반환한다.

`POST /api/admin/price-jobs/run`은 `price_jobs.status IN ('QUEUED', 'RUNNING')`인 row가 하나라도 있으면 새 job을 만들지 않고 `409 CONFLICT_STATE`를 반환한다. 또한 서버가 `DEMO_FREEZE_MUTATIONS=true`(데모 동결)로 기동된 경우에는 active job 확인 전에 즉시 `409 CONFLICT_STATE`를 반환한다. job을 받은 worker는 전 카테고리 네이버 offer 일일 갱신 경로를 실행하며, `PART_DANAWA_REFRESH_ENABLED=true`인 환경에서는 다나와 일일 스냅샷 수집도 함께 실행한다.

데모 동결은 `DEMO_FREEZE_MUTATIONS=true` 단일 스위치로, 가격/자산 수집 스케줄러 4종(네이버 가격 갱신, 다나와 스냅샷, 다나와 추이, 제조사 릴리스 스캔)과 관리자 가격 Job의 mutating 경로를 일괄 중지한다. 스킵된 스케줄 실행은 `pipeline_job_runs`에 `SKIPPED_FROZEN`으로 기록되며, 읽기 API에는 영향이 없다.

부품 검색 정렬은 `category`, `price_asc`, `price_desc`, `name`, `compatibility`를 허용한다. `compatibility`는 특정 `category`와 `compatibilitySource=QUOTE_DRAFT_CURRENT`가 함께 있을 때만 사용하며, 로그인 사용자의 현재 활성 견적초안 기준으로 `PASS -> WARN -> FAIL`, 가격 낮은순으로 정렬한다. `q`는 `parts.name`, `parts.manufacturer`, `parts.attributes`를 대상으로 검색한다.

`GET /api/parts`에서 `compatibilitySource=QUOTE_DRAFT_CURRENT`와 특정 `category`를 함께 보내면 각 `PartDto`에 선택적 `compatibility` 객체를 포함한다. 전체 카테고리 조회에서는 호환성 컬럼을 붙이지 않는다. 평가 의미론은 `compatibilityMode`로 정한다 — 생략/`REPLACE`(기본)는 같은 카테고리를 후보로 교체한 상태로 평가(기존 동작)하고, `ADD`는 현재 구성을 유지한 채 후보를 더한 상태로 평가한다(RAM처럼 복수 장착 카테고리의 슬롯 합산이 담기 전에 반영됨). `REPLACE`에서 `replaceTargetPartId`를 지정하면 그 행만 제외하고 평가한다. 호환 평가가 켜진 요청(`compatibilitySource`+`category`)에서 `compatibilityMode=ADD`와 `replaceTargetPartId`를 동시 지정하면 `400`이고, 호환 평가 없이 온 모드 파라미터는 (다른 미지 파라미터처럼) 무시한다.

`GET /api/parts`에서 `status`를 생략하면 쇼핑몰 기본 노출 기준인 `ACTIVE`만 반환한다. 구형 seed나 교체 후보 보관용 자산은 `status=INACTIVE` 또는 `status=DISCONTINUED`를 명시해 조회한다.

`POST /api/parts/compatible-candidates`는 그래프 노드 클릭 시 현재 조합과 호환되는 같은 카테고리 후보를 계산한다. `source=AI_BUILD`는 request의 `items[].partId`만 신뢰하고 서버가 DB에서 가격/spec을 다시 조회한다. `source=QUOTE_DRAFT_CURRENT`는 request의 `items`를 무시하고 로그인 사용자의 현재 견적초안을 직접 읽는다. 후보는 현재 조합에서 해당 category만 교체해 `ToolCheckService`를 실행한 뒤 `PASS -> WARN`, 가격 낮은순으로 정렬한다. `FAIL` 후보는 기본 목록에서 제외하고 `rejectedCount`에 반영한다.

관리자 부품 CRUD는 `/api/admin/parts`를 사용한다. 수동 생성은 항상 `INACTIVE` 초안으로 시작하고, `ACTIVE` 전환은 카테고리별 Tool 필수 스펙이 모두 있을 때만 허용한다. 서버는 저장 시 `attributes.toolReady`를 validator 결과로 계산하며 관리자가 직접 토글하지 않는다. 누락이 있으면 `400 VALIDATION_ERROR`와 누락 필드 목록을 반환한다.

삭제는 soft delete다. `DELETE /api/admin/parts/{id}`는 `parts.deleted_at`만 채우며 사용자 `/api/parts`, 추천, Tool 대상에서 제외된다. `POST /api/admin/parts/{id}/restore`는 삭제를 해제하고 `status=INACTIVE`로 복구한다.

대표 가격 수동 보정은 `POST /api/admin/parts/{id}/manual-price`로만 수행한다. 이 API는 하나의 transaction에서 `parts.price`를 갱신하고 `price_snapshots.source=ADMIN_MANUAL` 이력을 남긴다. `PATCH /api/admin/parts/{id}/external-offer`는 대표 offer 후보만 수정하며 `parts.price`를 직접 바꾸지 않는다. 생성, 수정, 상태 변경, 가격 보정, offer 보정, 삭제, 복구는 `admin_audit_logs`에 기록한다.

외부 가격 수집 백업은 별도 public 사용자 API를 만들지 않는다. 현재 단계에서는 `price_snapshots.source = "DANAWA_BACKUP"` 또는 최신 라인업 수동 seed용 `MANUAL_CURRENT_LINEUP`, `price_snapshots.raw_payload`, `parts.attributes.externalSources`에 키워드와 source metadata를 저장한다. 다나와 백업 수집은 관리자 전용 `POST /api/admin/parts/danawa-price-snapshots/refresh`, 비활성 기본 스케줄러, 그리고 `PART_DANAWA_REFRESH_ENABLED=true`인 환경에서 `POST /api/admin/price-jobs/run`이 발행한 가격 잡으로 실행한다.

네이버 쇼핑 검색 API 키가 설정된 환경에서도 `/api/parts`와 `/api/parts/{id}`는 외부 API를 직접 호출하지 않는다. 내부 자산 최신화는 `POST /api/admin/parts/catalog/refresh`가 담당한다. 이 API는 카테고리별 query pack을 돌려 `part_catalog_candidates`에 후보를 저장하고, `publish=true`일 때 검증 가능한 후보를 `parts`에 게시한다. 사용자 화면은 저장된 `parts`만 읽는다.

상품 사진/공급업체/현재가 보강은 `POST /api/admin/parts/external-offers/refresh`가 담당한다. 검색 결과는 `part_external_offers`에 저장하고, `low_price`가 있으면 `parts.price`와 `price_snapshots`에도 같은 가격을 반영한다. 캐시가 없거나 갱신 실패 시 `externalOffer`는 `null`이다. 네이버 API 키는 프론트로 전달하지 않고 API 서버 환경변수로만 관리한다. `force=true`가 없으면 최근 1일 안에 갱신된 상품은 재호출하지 않는다. 서버 자동 갱신은 기본값 기준 한국시간 매일 04:00에 같은 1일 기준으로 실행된다. 응답의 `errors`는 네이버 API 오류(429/5xx/타임아웃) 건수로 상품 미매칭(`skipped`)과 구분되며, 연속 API 오류가 누적되면 남은 대상 갱신을 조기 중단한다.

다나와 백업 갱신은 `POST /api/admin/parts/danawa-price-snapshots/refresh`가 담당한다. 이 작업은 robots 정책을 우회하지 않는 공개 검색 페이지 요청만 사용하고, 로그인/캡차/비공개 페이지 우회는 하지 않는다. 수집 성공 시 `price_snapshots.source = DANAWA_BACKUP` 이력과 `part_external_offers.source = DANAWA_BACKUP` 백업 row를 저장하지만, 쇼핑몰 대표 현재가인 `parts.price`는 변경하지 않는다. 기본 스케줄러는 `PART_DANAWA_REFRESH_ENABLED=false`로 꺼져 있으며, 데모/관리자 판단에 따라 1일 단위로 실행한다. 같은 플래그가 켜진 환경에서는 `POST /api/admin/price-jobs/run` 가격 잡 worker도 다나와 일일 스냅샷 수집을 함께 실행한다.

다나와 월별 가격 추이는 `POST /api/admin/parts/danawa-price-trends/refresh`가 담당한다. 이 작업은 `part_external_offers.source = DANAWA_BACKUP`의 상품 상세 URL을 우선 사용하고, 상세 URL이 없거나 검색 URL이면 다나와 공개 검색 결과에서 `prod.danawa.com/info/?pcode=...` 상세 URL을 찾는다. 상세 페이지가 사용하는 공개 `getProductPriceList.ajax.php` 응답의 6/12/24개월 그래프 포인트만 `price_snapshots.source = DANAWA_PRICE_TREND`로 저장한다. 월별 포인트는 해당 월 1일, 현재가는 현재일 12:00 KST 기준으로 저장하며, `parts.price`는 변경하지 않는다. 기본 스케줄러는 `PART_DANAWA_TREND_REFRESH_ENABLED=false`, 기본 cron은 매월 1일 05:30 KST다.

상품별 가격변동 추이는 `GET /api/parts/{id}/price-history`로 조회한다. 이 API는 저장된 `price_snapshots`만 읽고 외부 검색 API를 실시간 호출하지 않는다. 사용자 화면의 가격변동 그래프는 source를 생략해 네이버 현재가 이력, 다나와 백업 이력, 다나와 월별 추이, 수동 seed 이력을 함께 보여준다. 출처별 비교가 필요하면 `source=NAVER_SHOPPING_SEARCH`, `source=DANAWA_BACKUP`, `source=DANAWA_PRICE_TREND`처럼 필터링한다. 추출 실패 상품은 사용자에게 내부 실패 문구를 노출하지 않고 저장된 이력만 표시한다.

`POST /api/admin/parts/catalog/refresh`의 기본 query pack은 카테고리별로 수십 개 후보를 확보하도록 설계한다. GPU는 RTX 5090/5080/5070 Ti/5070/5060 Ti/5060을 ASUS, MSI, GIGABYTE, ZOTAC, PNY 등 제조사 검색어로 나누고, MOTHERBOARD와 PSU도 최신 소켓/칩셋/ATX 3.1 기준 제조사별 검색어를 사용한다. COOLER는 주요 공랭 듀얼타워와 360mm AIO 제품군을 별도 query pack으로 관리한다.

제조사 신제품 감지 파이프라인은 `manufacturer_sources`를 주기적으로 scan해 새 공식 게시글을 `manufacturer_posts`에 저장한다. scan은 `parts`를 직접 수정하지 않고, 제품 후보로 판정된 게시글만 네이버 쇼핑 검색을 거쳐 `part_catalog_candidates.source = "MANUFACTURER_RELEASE_NAVER_SEARCH"` 상태로 저장한다. source 생성/수정은 공식 제조사 도메인의 `https` URL만 허용한다. 단, Flyway seed로 들어가는 `BuildGraph Demo` source는 로컬 시연용 예외이며 `parserConfig.demo=true`와 localhost URL만 허용한다. 관리자 승인 전 후보는 사용자 화면, 추천, Tool 대상에 노출하지 않는다. `POST /api/admin/manufacturer-posts/{id}/ai-asset-draft`는 게시글 구조화, 후보 생성/동기화까지만 수행하고 INACTIVE 자산 초안 연결은 자동으로 하지 않는다(응답의 `partId`/`partStatus`는 항상 `null`). INACTIVE 초안 생성은 관리자가 후보를 검수한 뒤 `POST /api/admin/part-catalog-candidates/{id}/approve`로만 수행하며, 이때도 후보를 `parts.status = "INACTIVE"` 초안으로만 생성한다. 최종 `ACTIVE` 전환은 관리자 스펙 검수 후 별도 작업으로 처리한다. 신제품 판정 AI는 공식 게시글 본문을 읽어 게시글 분류, 검색어 생성, 후보 생성 사유 요약, 카테고리별 스펙 초안 추출까지만 담당한다. AI가 추출한 스펙은 `part_catalog_candidates.raw_payload.manufacturerRelease.aiSpecAttributes`에 저장되고 후보 승인 시 생성되는 `parts.attributes`에 반영되며, 특정 제품명 하드코딩으로 채우지 않는다. 게시글 분류는 `classification_source`(`RULE`/`ADMIN`/`AI`)로 출처를 추적한다. 관리자 `PATCH /api/admin/manufacturer-posts/{id}`(`ADMIN`) 또는 AI 초안(`AI`)으로 확정된 분류는 이후 재스캔의 룰 기반 분류가 덮어쓰지 않으며, 확정 게시글에는 scan이 후보를 재생성하지 않는다. `OPENAI_API_KEY`가 없으면 AI 초안화 API는 `428 PRECONDITION_REQUIRED`를 반환하고 가짜 후보를 만들지 않는다. AI JSON 계약 위반은 `502 UPSTREAM_ERROR`로 처리한다.

`part_catalog_candidates.source_product_key`는 외부 후보 중복 방지를 위한 서버 생성 내부키다. 관리자 후보 보정 화면과 `PATCH /api/admin/part-catalog-candidates/{id}` request는 이 값을 받지 않으며, 서버는 기존 값을 유지하거나 legacy 빈 값이 발견될 때 source/category/title/offerUrl/searchQuery 기반 안정 키로 보정한다.

AI 부품 교체 어시스턴트는 `quote_drafts`를 직접 수정하지 않고, 변경안을 `builds[]`의 '변경 미리보기' 카드(tier=`draft-edit`)로 반환해 사용자가 적용 버튼으로 확정하게 한다. 교체 후보 선정은 가격만 보지 않고 카테고리별 rank를 함께 사용한다. “더 좋은/상위/업그레이드”는 현재 부품보다 높은 rank 후보를 우선하고, 상위 후보가 없으면 범용 실패 문구 대신 “현재 {부품명}이(가) 내부 자산 기준 이미 최상위 구성입니다”로 사유를 명시한다. “더 싼/저렴/예산 낮춰”는 현재보다 낮은 가격 중 성능 하락이 가장 작은 후보를 우선한다. rank 계산에 필요한 alias나 스펙이 부족하면 응답 `warnings[]`에 `ALIAS_REVIEW_REQUIRED`, `RANK_FALLBACK_USED`, `NO_HIGHER_RANK_CANDIDATE` 중 필요한 값을 붙이고 `part_alias_review_items`에 관리자 검수 항목을 남긴다.

로컬 시연용 source는 Flyway seed로 `manufacturer = "BuildGraph Demo"`, `enabled = false`, `sourceType = "RSS"` 상태로 1개 제공한다. 이 source는 `/api/demo/manufacturer-release-feed.xml`을 읽는 데모 전용 feed이며 운영 제조사 감시 source가 아니다. 데모 피드는 `PART_MANUFACTURER_RELEASE_DEMO_FEED_ENABLED=true`인 로컬/데모 환경에서만 노출되며, 기본값(`false`)에서는 라우트 자체가 비활성화된다. `/admin/parts`에서 수동 scan을 실행하면 실제 파이프라인과 동일하게 `manufacturer_posts`를 기록하고, 네이버 쇼핑 API 설정이 있으면 `parserConfig.searchQuery` 기반으로 후보 생성을 시도한다. 네이버 API가 없거나 검색 결과가 부적합하면 게시글만 남기고 후보는 생성하지 않는다.

공식 제조사 감시 source는 Flyway seed로 ASUS, MSI, GIGABYTE, ASRock, ZOTAC, CORSAIR, Cooler Master, LIAN LI, Fractal Design의 공식 뉴스/제품 발표 페이지를 `enabled = true` 상태로 등록한다. 관리자는 `/admin/parts`에서 개별 source scan, `enabled = true`이고 `PAUSED`가 아닌 source 전체 scan, 감지 게시글 확인, 후보 offer 재검색, 후보 승인/거절을 수행한다. 전체 scan도 승인 전에는 `parts`를 직접 만들지 않으며, 후보 승인 후에도 `INACTIVE` 초안으로만 생성한다. 전체 scan은 source별 `pollIntervalMinutes` 주기를 존중해 `last_checked_at` 이후 주기가 지나지 않은 source는 이번 스캔에서 건너뛴다. 또한 후보 연결이 없는 기존 `PRODUCT_CANDIDATE` 게시글(최대 50건)에 후보 생성을 backfill로 재시도하고 결과를 응답 `candidateBackfill`에 담는다.

외부 제조사 페이지가 403, timeout, 빈 응답 등으로 실패하면 해당 source는 `manufacturer_sources.status = "ERROR"`와 `errorSummary`로 기록한다. 단, 차단성 응답(403/429)이 연속 3회에 도달하면 `ERROR` 대신 `PAUSED`로 자동 전환해(차단 사이트 연타 방지) 전체 scan 대상에서 제외하며, 관리자가 원인 확인 후 수동으로 재개해야 한다. 성공 scan은 실패 카운트를 리셋한다. 개별 scan과 전체 scan은 실패를 `failed = true` 또는 `failedSources`로 반환하며, 전체 scan은 나머지 source 처리를 계속한다. scan은 저장된 ETag/Last-Modified 기반 조건부 GET(If-None-Match/If-Modified-Since)을 사용하며, 304 응답은 본문 파싱 없이 `unchanged = true`인 성공으로 기록한다. robots/캡차/차단 우회는 하지 않는다.

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
| `POST` | `/api/ai/agent-sessions` | USER | 3번 | `{ "requirementId": "2e0f8c9c-8e1c-4d75-94a2-5d6a4977de11", "buildId": null, "asTicketId": null }` | `{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "status": "QUEUED", "summary": null, "stateTimeline": [{ "from": null, "to": "QUEUED", "actor": "USER", "at": "2026-06-29T10:35:00Z" }] }` | `agent_sessions` |
| `POST` | `/api/ai/agent-sessions/{id}/run` | USER | 3번 | `{}` | `{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "status": "RUNNING", "summary": null, "stateTimeline": [{ "from": "QUEUED", "to": "RUNNING", "actor": "SYSTEM", "at": "2026-06-29T10:36:00Z" }] }` | `agent_sessions` |
| `GET` | `/api/ai/agent-sessions/{id}` | USER | 3번 | - | `{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "status": "SUCCEEDED", "summary": "추천이 완료되었습니다.", "stateTimeline": [], "toolInvocationIds": ["4cf44761-e25b-4d5b-bd31-52c13dd9975c"], "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"] }` | `agent_sessions`, `tool_invocations`, `rag_evidence` |
| `GET` | `/api/rag/search` | USER | 3번 | `?q=5090&purpose=REQUIREMENT_PARSE&sourceType=INTERNAL_RULE&limit=3` | `{ "items": [{ "id": "9ebf5278-68aa-42a5-96f4-8ec0f90f0f77", "summary": "명시 GPU 조건은 하드 제약입니다.", "sourceId": "requirement-rule-explicit-gpu-class-hard-constraint", "score": 0.92 }], "page": 0, "size": 3, "total": 1 }` | `rag_evidence` |
| `GET` | `/api/rag/evidence/{id}` | USER | 3번 | - | `{ "id": "9ebf5278-68aa-42a5-96f4-8ec0f90f0f77", "summary": "RTX 4070 QHD 성능 근거", "sourceId": "spec-rtx4070", "metadata": { "sourceType": "PART_SPEC", "title": "RTX 4070 Spec" } }` | `rag_evidence` |
| `GET` | `/api/admin/agent-sessions` | ADMIN | 3번 | `?page=0&size=20` | `{ "items": [{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "status": "RUNNING", "userId": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "createdAt": "2026-06-29T10:35:00Z" }], "page": 0, "size": 20, "total": 1 }` | `agent_sessions` |
| `GET` | `/api/admin/agent-sessions/{id}` | ADMIN | 3번 | - | `{ "id": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "status": "SUCCEEDED", "summary": "추천이 완료되었습니다.", "stateTimeline": [], "toolInvocations": [], "evidenceIds": ["9ebf5278-68aa-42a5-96f4-8ec0f90f0f77"] }` | `agent_sessions`, `tool_invocations`, `rag_evidence` |
| `GET` | `/api/admin/tool-invocations` | ADMIN | 3번 | `?page=0&size=20` | `{ "items": [{ "id": "4cf44761-e25b-4d5b-bd31-52c13dd9975c", "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "toolName": "compatibility", "status": "PASS", "confidence": "HIGH", "createdAt": "2026-06-29T10:36:10Z" }], "page": 0, "size": 20, "total": 1 }` | `tool_invocations` |
| `GET` | `/api/admin/tool-invocations/{id}` | ADMIN | 3번 | - | `{ "id": "4cf44761-e25b-4d5b-bd31-52c13dd9975c", "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "toolName": "compatibility", "status": "PASS", "confidence": "HIGH", "summary": "호환됩니다.", "requestPayload": {}, "resultPayload": {}, "latencyMs": 120 }` | `tool_invocations` |
| `GET` | `/api/admin/rag-evidence` | ADMIN | 3번 | `?page=0&size=20` | `{ "items": [{ "id": "9ebf5278-68aa-42a5-96f4-8ec0f90f0f77", "agentSessionId": "7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a", "sourceId": "spec-rtx4070", "summary": "RTX 4070 QHD 성능 근거", "score": 0.92 }], "page": 0, "size": 20, "total": 1 }` | `rag_evidence` |
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

`POST /api/ai/agent-sessions/{id}/run` 409 조건:

- 해당 세션이 요청 사용자 소유가 아니면 `404 NOT_FOUND`다.
- status가 `QUEUED`가 아니면 실행을 시작하지 않고 `409 CONFLICT_STATE`를 반환한다.
- 특히 `RUNNING`, `RAG_SEARCHED`, `TOOLS_CALLED`, `SUMMARY_READY`, `FALLBACK_READY`는 이미 실행 중인 상태로 본다.
- `SUCCEEDED`, `FAILED`, `CANCELLED`는 완료된 상태이므로 재실행하지 않고 `409 CONFLICT_STATE`를 반환한다.

`POST /api/ai/agent-sessions`는 `requirementId`, `buildId`, `asTicketId` 중 정확히 하나만 non-null이어야 한다. 0개 또는 2개 이상이면 `400 VALIDATION_ERROR`다.

Agent 실행 방식:

- 계약상 `POST /api/ai/agent-sessions/{id}/run`은 실행 시작 API이며 `QUEUED -> RUNNING` 전이를 반환한다.
- 클라이언트는 `POST /run` 응답만으로 완료를 판단하지 않고 `GET /api/ai/agent-sessions/{id}`로 최종 상태와 근거 ID를 조회한다.
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

### Support Chat Rooms

Support Chat Rooms는 로그인 사용자와 관리자가 AS 티켓을 기준으로 직접 대화하는 4번 PC Agent/AS 담당 기능이다. 기존 `/support/ai-chat` AS AI Chat은 유지하며, 이 기능은 LLM, RAG, Tool Calling, PC Agent 로그 분석, AS 티켓 생성을 수행하지 않는다. 사용자 전역 위젯은 일반 USER에게만 표시하고, 관리자는 `/admin/support-chat-sessions`에서 여러 사용자 상담방을 관리한다.

| Method | Path | Auth | Owner | Request 예시 | Response 예시 | 관련 DB table |
|---|---|---|---|---|---|---|
| `GET` | `/api/support/chat-sessions/current` | USER | 4번 | `?asTicketId=4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a` optional | `{ "contact": { "id": "7c2f8f17-8f18-4d10-bcd1-9d20d1c71a01", "asTicketId": "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a", "ticketStatus": "OPEN", "title": "AS 상담방", "symptom": "게임 중 프레임 급락", "userUnreadCount": 0, "adminUnreadCount": 1, "canSendMessage": true }, "messages": [], "supportNewPath": "/support/new", "pollingIntervalMs": 5000 }` | `support_chat_rooms`, `support_chat_messages`, `as_tickets`, `users` |
| `GET` | `/api/support/chat-sessions/{id}` | USER | 4번 | - | `SupportChatSessionResponse` | `support_chat_rooms`, `support_chat_messages`, `as_tickets`, `users` |
| `POST` | `/api/support/chat-sessions/{id}/messages` | USER | 4번 | `{ "content": "담당자님, 재부팅 후에도 같은 증상이 있습니다." }` | `SupportChatSessionResponse` | `support_chat_messages`, `support_chat_rooms` |
| `PUT` | `/api/support/chat-sessions/{id}/visit-reservation` | USER | 4번 | `{ "scheduledAt": "2026-07-10T14:30:00+09:00", "addressSnapshot": "서울시 강남구" }` | `SupportChatSessionResponse` | `visit_support_reservations`, `support_chat_messages`, `support_chat_rooms` |
| `POST` | `/api/support/chat-sessions/{id}/ws-ticket` | USER | 4번 | - | `{ "ticket": "opaque-token", "expiresAt": "2026-07-06T10:01:00Z", "expiresInSeconds": 60 }` | Redis `support-chat:ws-ticket:*`, `support_chat_rooms` |
| `GET` | `/api/admin/support/chat-sessions` | ADMIN | 4번 | - | `{ "items": [{ "id": "7c2f8f17-8f18-4d10-bcd1-9d20d1c71a01", "asTicketId": "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a", "ticketStatus": "OPEN", "adminUnreadCount": 1, "user": { "id": "c6d75f0c-0f57-4d1c-a8b2-a4079dcd40fd", "email": "user@example.com" } }], "pollingIntervalMs": 5000 }` | `support_chat_rooms`, `as_tickets`, `users` |
| `POST` | `/api/admin/support/chat-sessions/ws-ticket` | ADMIN | 4번 | - | `{ "ticket": "opaque-token", "expiresAt": "2026-07-06T10:01:00Z", "expiresInSeconds": 60 }` | Redis `support-chat:ws-ticket:*` |
| `GET` | `/api/admin/support/chat-sessions/{id}` | ADMIN | 4번 | `?markRead=false` optional | `SupportChatSessionResponse` | `support_chat_rooms`, `support_chat_messages`, `as_tickets`, `users` |
| `DELETE` | `/api/admin/support/chat-sessions/{id}` | ADMIN | 4번 | - | `SupportChatSessionResponse`, `contact.status=ARCHIVED`, `canSendMessage=false` | `support_chat_rooms`, `support_chat_messages`, `as_tickets` |
| `POST` | `/api/admin/support/chat-sessions/{id}/messages` | ADMIN | 4번 | `{ "content": "담당자가 확인 중입니다. 최근 재현 시각을 알려주세요." }` | `SupportChatSessionResponse` | `support_chat_messages`, `support_chat_rooms`, `as_tickets` |
| `PUT` | `/api/admin/support/chat-sessions/{id}/visit-reservation` | ADMIN | 4번 | `{ "scheduledAt": "2026-07-10T14:30:00+09:00", "technicianNote": "방문 전 연락" }` | `SupportChatSessionResponse` | `visit_support_reservations`, `support_chat_messages`, `support_chat_rooms` |
| `DELETE` | `/api/admin/support/chat-sessions/{id}/visit-reservation` | ADMIN | 4번 | - | `SupportChatSessionResponse` | `visit_support_reservations`, `support_chat_messages`, `support_chat_rooms` |
| `POST` | `/api/admin/support/chat-sessions/{id}/ws-ticket` | ADMIN | 4번 | - | `{ "ticket": "opaque-token", "expiresAt": "2026-07-06T10:01:00Z", "expiresInSeconds": 60 }` | Redis `support-chat:ws-ticket:*`, `support_chat_rooms` |
| WebSocket | `/ws/support-chat?mode=user&sessionId={id}` | USER/ADMIN | 4번 | 방 상세 전용. 연결 후 5초 안에 `{ "type": "AUTH", "ticket": "opaque-token" }`. 수신 전용. client `MESSAGE`는 저장하지 않고 `{ "type": "ERROR", "code": "WS_MESSAGE_DISABLED", "retryable": false }` 반환 | `{ "type": "CHAT_UPDATED", "detail": SupportChatSessionResponse }` | `support_chat_rooms` |
| WebSocket | `/ws/admin/support-chat-queue` | ADMIN | 4번 | 관리자 상담방 목록 전용. 연결 후 5초 안에 `{ "type": "AUTH", "ticket": "opaque-token" }`. URL query string에 token/ticket을 넣지 않는다. | `SUPPORT_CHAT_QUEUE_READY`, `SUPPORT_CHAT_QUEUE_UPDATED`, `SUPPORT_CHAT_QUEUE_REMOVED` | `support_chat_rooms` |

Support Chat Rooms 규칙:

- 전역 상담방 UI는 로그인 사용자 화면에 떠 있으며, `/login`, `/signup`, `/admin/**`, `/support/new`에서는 표시하지 않는다.
- 상담 메시지 전송은 AS 티켓과 연결된 active 상담방이 있을 때만 허용한다.
- 사용자당 진행 중 상담방은 1개만 허용한다. 진행 중 상담은 `support_chat_rooms.status='ACTIVE'`이고 연결된 티켓이 `CLOSED`, `CANCELLED`가 아닌 상태다.
- 로그인 사용자가 상담방이 없으면 `GET /api/support/chat-sessions/current`는 DB row를 만들지 않고 `contact: null`, `supportNewPath: "/support/new"`를 반환한다.
- `asTicketId`를 지정한 `GET /api/support/chat-sessions/current`는 로그인 사용자의 AS 티켓인지 확인하고, 티켓이 있으면 active 상담방을 보장한다.
- `POST /api/as-tickets`로 AS 티켓이 생성되면 같은 transaction에서 active 상담방과 최초 `SYSTEM` 메시지를 생성한다.
- `POST /api/as-tickets`는 생성 전 사용자 row를 잠그고 진행 중 상담방을 다시 확인한다. 진행 중 상담방이 있으면 새 티켓을 만들지 않고 `409 CONFLICT_STATE`와 `details.asTicketId`, `details.supportChatRoomId`를 반환한다.
- `POST /api/support/chat-sessions/{id}/messages`는 사용자 본인 티켓 상담방에만 쓸 수 있으며, 본인 소유가 아니면 `404 NOT_FOUND`다.
- `POST /api/admin/support/chat-sessions/{id}/messages`는 관리자 권한이 필요하며, 첫 관리자 응답 시 `as_tickets.assigned_admin_id`가 비어 있으면 현재 관리자로 배정한다.
- `content`는 trim 후 1자 이상 2000자 이하만 허용한다.
- `CLOSED`, `CANCELLED` 티켓 상담방에는 새 메시지를 보낼 수 없고 `409 CONFLICT_STATE`를 반환한다.
- 사용자 메시지는 `adminUnreadCount`를 증가시키고, 관리자 메시지는 `userUnreadCount`를 증가시킨다. REST 상세 조회는 기본적으로 조회자 쪽 unread count를 0으로 초기화한다. 관리자 상세는 `markRead=false`로 읽음 처리 없이 조회할 수 있고, WebSocket push snapshot은 unread count를 초기화하지 않는다.
- 관리자가 `DELETE /api/admin/support/chat-sessions/{id}`를 호출하면 해당 상담방만 `ARCHIVED`로 전환하고 `SYSTEM` 메시지를 남긴다. 연결 티켓은 `OPEN`, `ASSIGNED`, `IN_PROGRESS`, `RESOLVED`이면 `CANCELLED`로 바꾸며, 이미 `CLOSED`/`CANCELLED`이면 티켓 상태를 유지한다.
- 관리자 명시 삭제로 `ARCHIVED`된 상담방은 진행 중 상담 기준에서 제외되며, 사용자는 `/support/new`에서 새 AS 티켓을 다시 접수할 수 있다.
- `GET /api/admin/support/chat-sessions`는 `as_tickets.status`가 `CLOSED`, `CANCELLED`가 아닌 상담방만 최근순 최대 100개까지 반환한다.
- 상세 조회의 `messages`는 최근 100개만 시간순으로 반환한다.
- WebSocket은 실시간 갱신용이며 클라이언트는 연결 실패 시 REST polling(`pollingIntervalMs`)으로 fallback한다. 소켓이 연결된 상태에서도 낮은 빈도의 fallback polling을 유지한다.
- WebSocket 인증은 query string JWT를 쓰지 않는다. REST `ws-ticket` endpoint가 Redis에 60초 TTL, 1회 사용 ticket을 저장하고, WebSocket 연결 후 첫 `AUTH` frame으로 소비한다. Redis 장애 시 ticket 발급은 `503 UPSTREAM_ERROR`이며 클라이언트는 REST polling으로 fallback한다.
- 관리자 상담방 목록은 `/ws/admin/support-chat-queue`로 별도 구독한다. 인증 성공 시 `{ "type": "SUPPORT_CHAT_QUEUE_READY", "pollingIntervalMs": 5000 }`를 보내고, 목록에 포함되는 방 변경은 `{ "type": "SUPPORT_CHAT_QUEUE_UPDATED", "contact": SupportChatContact }`, CLOSED/CANCELLED/삭제 등 목록에서 빠지는 방은 `{ "type": "SUPPORT_CHAT_QUEUE_REMOVED", "id": "..." }`로 보낸다.
- REST CORS와 WebSocket allowed origin은 공통 `buildgraph.cors.allowed-origins` property를 사용한다. 기본값은 `http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174`이고 배포 환경에서는 명시 설정해야 한다.
- 현재 WebSocket session map은 API JVM 메모리 기반이다. P2에서는 단일 API 인스턴스 push만 보장하며, 여러 API 인스턴스에서 다른 인스턴스에 붙은 소켓 push는 누락될 수 있다. 이 경우 `pollingIntervalMs` REST polling이 복구 경로이고, Redis pub/sub fan-out은 다음 단계 과제다.
- 메시지는 REST `POST .../messages` 성공 시에만 저장된다. 저장 후 서버는 해당 상담방에 연결된 모든 WebSocket 세션에 `CHAT_UPDATED`를 push하고, 관리자 queue 세션에는 단일 방 patch를 push한다. 각 방 상세 세션은 자신의 모드(user/admin) 기준 unread-safe `SupportChatSessionResponse` snapshot을 받는다.
- 방문 지원 예약은 `visit_support_reservations.scheduled_at`에 정확한 시작 시각을 저장하고, 기존 `preferred_date`, `time_slot`도 호환용으로 함께 채운다. 사용자는 요청/변경 요청만 가능하고(`REQUESTED`, `RESCHEDULE_REQUESTED`), 관리자는 확정/변경(`SCHEDULED`)과 취소(`CANCELLED`)를 수행한다. 취소는 관리자만 가능하다.
- 한 AS 티켓에는 active 방문 예약 1건만 유지한다. active 상태는 `REQUESTED`, `RESCHEDULE_REQUESTED`, `SCHEDULED`, `VISIT_IN_PROGRESS`다. 예약 생성/변경은 티켓 row를 `FOR UPDATE`로 잠그고, 변경 사실을 `SYSTEM` 메시지로 남긴 뒤 기존 room detail/queue WebSocket으로 push한다.

### PC Agent/AS

| Method | Path | Auth | Owner | Request 예시 | Response 예시 | 관련 DB table |
|---|---|---|---|---|---|---|
| `POST` | `/api/agent/devices/register` | no (activation token) | 4번 | `{ "activationToken": "demo-agent-activation-token", "userEmail": "user@example.com", "deviceFingerprintHash": "sha256-...", "osVersion": "Windows 11", "agentVersion": "0.1.0", "policyVersion": "demo-policy-v1" }` | `201 { "deviceId": "b7e1...", "status": "ACTIVE", "agentToken": "raw-agent-token", "tokenType": "Bearer" }` | `agent_devices`, `users` 읽기 |
| `POST` | `/api/agent/consents` | AGENT | 4번 | `Idempotency-Key` header + `{ "consentType": "SERVER_UPLOAD", "policyVersion": "demo-policy-v1", "accepted": true }` | `{ "id": "c3ac...", "consentType": "SERVER_UPLOAD", "policyVersion": "demo-policy-v1", "accepted": true, "acceptedAt": "2026-07-03T10:00:00Z", "revokedAt": null }` | `agent_consents` |
| `POST` | `/api/agent/heartbeat` | AGENT | 4번 | `Idempotency-Key` header + `{ "agentVersion": "0.1.0", "serviceStatus": "RUNNING" }` | `{ "id": "d2f1...", "deviceId": "b7e1...", "status": "ACTIVE", "receivedAt": "2026-07-03T10:00:00Z", "pendingCommands": [] }` | `agent_heartbeats`, `agent_devices` |
| `POST` | `/api/agent/diagnosis-chat` | AGENT | 4번 | `{ "message": "AS 접수해야 해?", "diagnosis": { "summaryText": "Driver reset repeated", "recommendedService": "REMOTE_SUPPORT", "recommendedDecision": "REMOTE_POSSIBLE", "confidence": "HIGH" }, "messages": [{ "role": "user", "content": "게임이 꺼져요" }] }` | `{ "assistantMessage": "먼저 그래픽 드라이버를 클린 설치하고 문제가 재현되는지 확인해 보세요.", "causeCandidates": [], "nextActions": [], "escalation": { "required": false, "recommended": true, "reason": "Remote-checkable signals repeat." }, "ticketDraft": { "symptomSummary": "Driver reset repeated" }, "model": "gpt-5.4-mini" }` | none. Stateless agent-token chat; uses the PC Agent diagnosis LLM when `OPENAI_API_KEY` is configured and falls back to `buildgraph-agent-diagnosis-rule-v1`; does not create `as_tickets`, `agent_log_uploads`, `as_chat_sessions`, or `as_chat_messages`. |
| `POST` | `/api/agent-logs/upload` | USER | 4번 | `multipart/form-data` | `{ "id": "1b363bcb-42be-4428-b625-54a6b267d66f", "status": "UPLOADED", "fileName": "agent-log.jsonl", "fileSize": 12000, "rangeMinutes": 30, "deleteAfter": "2026-07-29T10:40:00Z" }` | `agent_log_uploads` |
| `POST` | `/api/agent-logs/as-rag-preview` | USER | 4번 | `multipart/form-data` | `{ "recommendedService": "REMOTE_SUPPORT", "supportDecision": "REMOTE_POSSIBLE", "confidence": "MEDIUM", "summaryText": "..." }` | none |
| `GET` | `/api/agent-logs/{id}` | USER | 4번 | - | `{ "id": "1b363bcb-42be-4428-b625-54a6b267d66f", "status": "UPLOADED", "fileName": "agent-log.jsonl", "rangeMinutes": 30, "summary": "GPU driver error 반복", "createdAt": "2026-06-29T10:40:00Z", "deleteAfter": "2026-07-29T10:40:00Z" }` | `agent_log_uploads` |
| `POST` | `/api/as-tickets` | USER | 4번 | `{ "logUploadId": "1b363bcb-42be-4428-b625-54a6b267d66f", "symptom": "화면이 멈춤" }` | `201 { "id": "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a", "status": "OPEN", "symptom": "화면이 멈춤", "logUploadId": "1b363bcb-42be-4428-b625-54a6b267d66f", "causeCandidates": [], "upgradeCandidates": [], "supportChatRoomId": "7c2f8f17-8f18-4d10-bcd1-9d20d1c71a01", "supportChatUserUnreadCount": 0, "supportChatAdminUnreadCount": 0, "createdAt": "2026-06-29T10:42:00Z" }`, 진행 중 상담방 존재 시 `409 CONFLICT_STATE { "details": { "asTicketId": "...", "supportChatRoomId": "..." } }` | `as_tickets`, `agent_log_uploads`, `support_chat_rooms`, `support_chat_messages` |
| `GET` | `/api/as-tickets/{id}` | USER | 4번 | - | `{ "id": "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a", "status": "OPEN", "symptom": "화면이 멈춤", "logUploadId": "1b363bcb-42be-4428-b625-54a6b267d66f", "causeCandidates": [], "upgradeCandidates": [], "supportChatRoomId": "7c2f8f17-8f18-4d10-bcd1-9d20d1c71a01", "supportChatUserUnreadCount": 0, "supportChatAdminUnreadCount": 0, "supportChatLastMessageAt": "2026-06-29T10:42:00Z", "adminNote": null, "createdAt": "2026-06-29T10:42:00Z" }` | `as_tickets`, `support_chat_rooms` |
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

`POST /api/agent-logs/as-rag-preview`는 AS 접수 화면의 미리보기 전용 API다. `POST /api/agent-logs/upload`와 같은 JSONL/NDJSON 파일 검증과 PII 마스킹을 적용하지만 DB row를 만들지 않는다. 웹 화면은 `agent-metrics.jsonl` 같은 누적 로그를 선택하면 브라우저에서 최신 timestamp 기준 최근 30분 JSONL만 추출해 이 API와 실제 업로드 API에 같은 파일을 보낸다.

PC Agent 등록/인증 규칙:

- `POST /api/agent/devices/register`는 데모 등록 경로다. activation token은 `AGENT_DEMO_ACTIVATION_TOKEN` env로 주입하며, 값이 비어 있으면(프로덕션 기본) 데모 등록 경로 자체가 비활성화되어 `401 UNAUTHORIZED`를 반환한다. 실서비스는 DB 발급 토큰 방식으로 대체한다.
- 등록 성공 시 발급된 `agentToken`이 이후 agent API의 Bearer 토큰이다. `AGENT` 인증 라우트(`/api/agent/consents`, `/api/agent/heartbeat`, `/api/agent/log-uploads`, `/api/agent/diagnosis-chat`)는 사용자 JWT가 아니라 agent token Bearer 인증을 사용한다. 상태를 생성/수정하는 agent API는 `Idempotency-Key` header를 사용하되, `/api/agent/diagnosis-chat`은 서버 DB row를 만들지 않는 stateless 상담 API라서 idempotency header를 요구하지 않는다.
- `/api/agent/diagnosis-chat`의 LLM 호출은 AS Chat의 `as_chat_sessions`/`as_chat_messages` 저장 흐름과 분리한다. OpenAI key가 없거나 upstream/schema 오류가 나면 기존 rule fallback 응답을 반환하며, 이 경우에도 AS 티켓/로그 업로드/채팅 세션 row를 만들지 않는다. PC Agent는 raw JSONL 로그가 아니라 `diagnosis_chat_context()`의 요약 필드와 최근 대화만 보낸다.

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
| `GET` | `/api/admin/pipeline-job-runs` | ADMIN | 5번 | `?limit=30` | `{ "items": [{ "id": "5a3f...", "jobName": "DANAWA_SNAPSHOT_REFRESH", "triggerType": "SCHEDULED", "status": "SUCCEEDED", "resultSummary": { "attempted": 7 }, "errorSummary": null, "startedAt": "2026-07-03T04:00:00Z", "finishedAt": "2026-07-03T04:01:10Z", "durationMs": 70000 }], "total": 1 }` | `pipeline_job_runs` |
| `GET` | `/api/health` | no | 5번 | - | `200 { "status": "UP", "database": "UP" }`, DB 연결 실패 시 `503 { "status": "DOWN" }` | runtime |

- `/api/health`는 DB probe를 포함한다. DB 연결 또는 query 실패 시 `503 Service Unavailable`과 `status: "DOWN"`을 반환한다.
- `GET /api/admin/pipeline-job-runs`는 스케줄 파이프라인 잡(`PART_PRICE_REFRESH`, `DANAWA_SNAPSHOT_REFRESH`, `DANAWA_TREND_REFRESH`, `MANUFACTURER_RELEASE_SCAN`, `SHADOW_SCORE_RETENTION`) 실행 이력을 최신순으로 반환한다. query `limit`은 기본 30, 최대 100이며 page/size pagination은 사용하지 않는다. `status`는 `SUCCEEDED | FAILED | SKIPPED_FROZEN | SKIPPED_LOCKED`다.
- 스케줄 잡은 잡 이름 단위 Postgres advisory lock으로 상호배제한다. API를 다중 인스턴스로 실행해도 같은 잡이 중복 실행되지 않으며, 락을 잡지 못한 인스턴스는 실행하지 않고 `SKIPPED_LOCKED` 이력만 남긴다. 데모 동결(`DEMO_FREEZE_MUTATIONS=true`)로 건너뛴 실행은 `SKIPPED_FROZEN`으로 남는다.

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
| `AiBuildChatResponse` | `quickReplies` | `string[]` | yes | `["게이밍 200만원", "200만원대로 추천해줘"]` |
| `AiBuildChatResponse` | `quickReplyCommands` | `AiQuickReplyCommandDto[]` | yes | `[{ "label": "DDR5 32GB 견적에 담아줘", "type": "ADD_MULTI_ITEM_TO_DRAFT", "partId": "...", "category": "RAM", "quantityDelta": 1 }]` |
| `AiBuildChatResponse` | `clarification` | `object` | yes | `{ "missingSlots": ["budget", "useCase"], "originalMessage": "피시 맞춰줘" }` |
| `AiBuildChatResponse` | `simulation` | `AiPerformanceSimulationDto` | yes | `{ "type": "PERFORMANCE_COMPARISON", "category": "GPU" }` |
| `AiBuildChatResponse` | `warnings` | `string[]` | no | `[]` |
| `AiPerformanceSimulationDto` | `currentPart` | `AiSimulationPartDto` | no | `{ "name": "RTX 5080" }` |
| `AiPerformanceSimulationDto` | `targetPart` | `AiSimulationPartDto` | no | `{ "name": "RTX 5090" }` |
| `AiPerformanceSimulationDto` | `scoreComparison` | `object` | yes | `{ "label": "벤치마크 기반 점수", "currentScore": 95, "targetScore": 100, "delta": 5 }` |
| `AiPerformanceSimulationDto` | `fpsComparisons` | `object[]` | yes | `[{ "gameTitle": "PUBG", "resolution": "QHD", "currentFps": 223, "targetFps": 243 }]` |
| `AiPerformanceSimulationDto` | `specComparisons` | `object[]` | yes | `[{ "label": "VRAM", "currentValue": "16GB", "targetValue": "32GB" }]` |
| `AiBuildRecommendationDto` | `id` | `string` | no | `ai-2000000-balanced` |
| `AiBuildRecommendationDto` | `tier` | `string` | no | `balanced` (변경 미리보기 카드는 `draft-edit`) |
| `AiBuildRecommendationDto` | `label` | `string` | yes | `변경 미리보기` |
| `AiBuildRecommendationDto` | `title` | `string` | yes | `변경 적용 미리보기` |
| `AiBuildRecommendationDto` | `badges` | `string[]` | yes | `["DRAFT_EDIT_PREVIEW"]` |
| `AiBuildRecommendationDto` | `appliedPartCategories` | `string[]` | yes | `["GPU"]` |
| `AiBuildRecommendationDto` | `budgetLabel` | `string` | yes | `146만원` |
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
| `PartDto` | `compatibility` | `PartCompatibilityDto` | yes | `{ "status": "PASS", "statusLabel": "호환 가능", "summary": "현재 조합 기준 호환 가능합니다.", "checkedTools": ["power", "size"] }` |
| `PartDto` | `externalOffer` | `object` | yes | `{ "imageUrl": "https://...", "supplierName": "Naver Store", "offerUrl": "https://...", "lowPrice": 950000, "source": "NAVER_SHOPPING_SEARCH", "refreshedAt": "2026-06-29T10:25:00Z" }` |
| `PartCompatibilityDto` | `status` | `string` | no | `PASS` |
| `PartCompatibilityDto` | `statusLabel` | `string` | no | `호환 가능` |
| `PartCompatibilityDto` | `summary` | `string` | no | `현재 조합 기준 호환 가능합니다.` |
| `PartCompatibilityDto` | `checkedTools` | `string[]` | no | `["compatibility"]` |
| `CompatiblePartCandidateRequest` | `source` | `string` | no | `AI_BUILD` |
| `CompatiblePartCandidateRequest` | `category` | `string` | no | `GPU` |
| `CompatiblePartCandidateRequest` | `items` | `AiBuildItemDto[]` | yes | `[{ "partId": "0e9f3b8b-8c83-4d9a-9f7d-1f2b4dfb8a11", "category": "GPU", "quantity": 1 }]` |
| `CompatiblePartCandidateResponse` | `items` | `CompatiblePartCandidateDto[]` | no | `[{ "statusLabel": "여유 있음" }]` |
| `CompatiblePartCandidateDto` | `part` | `PartDto` | no | `{ "category": "GPU" }` |
| `CompatiblePartCandidateDto` | `status` | `string` | no | `PASS` |
| `CompatiblePartCandidateDto` | `summary` | `string` | no | `현재 조합 기준 호환 가능합니다.` |
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

PC Agent 앱이 직접 호출하는 정식 업로드 경로는 `POST /api/agent/log-uploads`다. 이 API는 일반 사용자 JWT가 아니라 agent token Bearer 인증을 사용하며, multipart `file`에는 gzip JSONL incident window를 담는다. 서버는 gzip, JSONL envelope(`schemaVersion`, `collectedAt`, `agentId`, `sequence`, `kind`, `payload`, `privacyFlags`)와 privacy flag를 검증한다. `privacyFlags.containsRawPath=true`인데 `masked=true`가 아니면 `400 FILE_VALIDATION_ERROR`를 반환하고 DB row와 파일을 남기지 않는다. 성공 시 `agent_log_uploads`, `agent_log_bundles`, `as_tickets`, `support_chat_rooms`, `support_chat_messages`, `agent_log_summaries`를 생성하고 `{ uploadJobId, logUploadId, ticketId, supportChatRoomId, logSummaryId, ... }`를 반환한다.

`agent_log_summaries.feature_payload`에는 XGBoost 학습에 사용할 수 있는 정량 피처만 저장한다. 예: `maxDiskUsage`, `avgMemoryUsage`, `maxGpuTemp`, `gpuMetricAvailable`, `unavailableReasonCounts`, `thermalRisk`, `storagePressureRisk` 등이다. raw gzip과 전체 JSONL line은 재처리/감사용 보존 대상이며 학습 dataset row로 복사하지 않는다.

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
| `AsTicketDto` | `logSummaryId` | `string` | yes | `7dfb98c8-7f35-4fd3-95e0-dfd58cbda77a` |
| `AsTicketDto` | `logSummaryPayload` | `object` | yes | `{ "lineCount": 120, "unknownKindCount": 2 }` |
| `AsTicketDto` | `logFeaturePayload` | `object` | yes | `{ "maxDiskUsage": 93.4, "gpuMetricAvailable": true }` |
| `AsTicketDto` | `logRiskFlags` | `object` | yes | `{ "storagePressureRisk": true }` |
| `AsTicketDto` | `asTrainingLabel` | `object` | yes | `{ "failureCategory": "PART_SELECTION", "severity": "HIGH" }` |
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
| 중복 실행 | `POST /api/admin/price-jobs/run`은 active job이 있으면 `409`, `POST /api/ai/agent-sessions/{id}/run`은 `QUEUED`가 아니면 `409`다. |
| soft delete 조회 제외 | soft delete 리소스 상세는 `404 NOT_FOUND`, 목록에는 포함하지 않는다. |
| Flyway migration 순서 검증 | API contract에 필요한 table/column이 migration 순서상 먼저 생성되는지 확인한다. |
| JSONL 업로드 validation | 크기, MIME/확장자, JSONL line 검증 실패는 `400 FILE_VALIDATION_ERROR`다. |
| pagination 기본값/최대값 | 목록 API는 `page=0`, `size=20` 기본값과 `size<=100`을 지킨다. |
