# ROUTE_OWNERSHIP.md

## 목적

이 문서는 BuildGraph 3주 MVP에서 frontend route, frontend file, backend package, DB table, API endpoint의 주 owner를 고정한다.
한 route나 API는 반드시 한 명의 주 owner만 가진다. 협업이 필요한 경우 협업자를 별도 컬럼에 적는다.

## 공통 개발 원칙

- `products` 도메인은 사용하지 않고 `parts` 도메인을 사용한다.
- API path의 `{id}`와 frontend route param은 모두 `public_id`다.
- 내부 DB `BIGINT id`는 frontend에 노출하지 않는다.
- Auth 기능 owner와 Auth 공통/관리자 권한 owner는 분리한다.
- AdminShell owner와 각 admin page 내부 owner는 분리한다.
- 주문, 결제, 배송, 재고 차감, 타임세일은 V1 route/API에 포함하지 않는다.
- 직접 Tool check는 `tool_invocations`에 저장하지 않는다. Agent/recommend 내부 Tool 호출만 저장한다.
- 관리자 API는 `ADMIN` 권한 필요 여부를 명확히 유지한다.

## 병렬 개발 동결 규칙

MVP 기준 결정값:

- 세 문서의 API path, enum/status, owner, DB table, `public_id` 규칙은 기능 구현 전에 동결한다.
- path의 `{id}`와 route param의 `:buildId`, `:ticketId`, `:agentSessionId`는 모두 `public_id` 문자열이다.
- 내부 DB `BIGINT id`는 public API, route, frontend DTO, audit log `target_id`에 노출하지 않는다.
- `products`, 주문, 결제, 배송, 재고 차감, 타임세일 도메인은 V1에서 추가하지 않는다.
- owner 변경, API path 변경, enum/status 변경, table 추가/삭제는 이 문서, `DB_SCHEMA.md`, `API_CONTRACT.md`를 같은 PR에서 동시에 수정해야 한다.
- 병렬 개발 중 임시 필드가 필요하면 public DTO에 노출하지 말고 feature 내부 mock이나 테스트 fixture로만 둔다.

## frontend 폴더 규칙

| 영역 | 폴더 규칙 |
|---|---|
| Quote/Build | `apps/web/src/features/quote/**` |
| Parts/Price/Tool | `apps/web/src/features/parts/**` |
| Agent/RAG/Admin evidence | `apps/web/src/features/admin/agent/**` 또는 `apps/web/src/features/admin/evidence/**` |
| Support/AS | `apps/web/src/features/support/**` |
| Auth 화면 | `apps/web/src/features/auth/**` |
| Admin shell/common | `apps/web/src/features/admin/shell/**`, `apps/web/src/components/**` |
| API client | 각 feature 내부 `*Api.ts` 또는 공통 `apps/web/src/lib/api.ts` |

공통 UI를 변경할 때는 아래 공유 지점 수정 권한 표를 따른다. 기능별 화면 owner는 자기 feature 폴더 내부 컴포넌트를 우선 사용하고, 공통 컴포넌트는 직접 수정 가능 범위를 넘기지 않는다.

## backend package 규칙

| 영역 | package 규칙 |
|---|---|
| Auth/User | `com.buildgraph.prototype.user`, `com.buildgraph.prototype.auth` |
| Quote/Build | `com.buildgraph.prototype.build`, `com.buildgraph.prototype.requirement` |
| Parts/Price/Tool | `com.buildgraph.prototype.part`, `com.buildgraph.prototype.price`, `com.buildgraph.prototype.tool` |
| Agent/RAG | `com.buildgraph.prototype.agent`, `com.buildgraph.prototype.rag` |
| Support/AS | `com.buildgraph.prototype.log`, `com.buildgraph.prototype.ticket` |
| Admin | `com.buildgraph.prototype.admin` |
| Common/Infra | `com.buildgraph.prototype.common`, config, security, migration |

## 공유 지점 수정 권한

5번 owner가 병목이 되지 않도록 공통 파일은 "승인만 필요한 변경"과 "5번만 직접 수정 가능한 변경"을 분리한다. "PR reviewer 필수"가 `예`인 항목은 주 owner가 직접 구현하지 않았더라도 해당 reviewer 승인 없이는 merge하지 않는다.

| 공유 지점 | 주 owner | 협업자 | 직접 수정 가능 범위 | PR reviewer 필수 |
|---|---|---|---|---|
| `/my/quotes` route | 1번 | 2번 | 1번은 build history 화면, 2번은 가격 알림 widget과 `GET/POST /api/price-alerts` 연결만 수정 가능 | 2번 변경 포함 시 1번, build history 변경 포함 시 2번 |
| `/builds/:buildId` route | 1번 | 2번, 3번 | 1번은 화면 composition과 `GET /api/builds/{id}`, 2번은 part 표시 DTO, 3번은 evidence link 표시만 수정 가능 | 2번 또는 3번 데이터 필드 변경 시 해당 owner |
| `/builds/:buildId/change-part` route | 1번 | 2번 | 1번은 교체 flow와 route state, 2번은 parts 검색/필터와 category별 후보 DTO만 수정 가능 | 예, 1번과 2번 상호 review |
| `/admin` route | 5번 | 2번, 3번, 4번 | 5번은 shell, guard, dashboard frame을 수정한다. 각 도메인 owner는 자기 admin summary card의 데이터 mapping만 수정 가능 | 도메인 card 변경 시 5번 |
| AdminShell | 5번 | 2번, 3번, 4번 | 승인만 필요한 변경: nav item label/order, 각 owner의 admin route link 추가. 5번만 직접 수정 가능한 변경: guard, layout slot contract, admin 권한 분기, dashboard 공통 query | 예, 5번 |
| Auth 화면 | 1번 | 5번 | 1번은 `/login`, `/signup`, `/auth/callback` 화면 state, form validation, Auth API 연결을 수정한다. token 저장 helper와 admin route guard 변경은 5번과 합의한다 | token/guard 변경 시 5번 |
| Auth API/User | 1번 | 5번 | 1번은 auth endpoint, users, JWT, refresh, OAuth 구현을 수정한다. 5번은 공통 API client, `RequireAdmin`, admin guard, security allowlist를 검토한다 | auth/token/security 변경 시 5번 |
| `components/**` | 5번 | 모든 화면 owner | 승인만 필요한 변경: 기존 prop을 깨지 않는 style variant, 접근성 label, 빈 상태 문구. 5번만 직접 수정 가능한 변경: prop rename/remove, layout primitive, table/form control contract, global theme token | 예, 5번 |
| `apps/web/src/lib/api.ts` | 5번 | 모든 API owner | 승인만 필요한 변경: owner별 endpoint wrapper 추가, typed DTO import 추가. 5번만 직접 수정 가능한 변경: auth header, refresh retry, base URL, error normalization, pagination 공통 처리 | error/auth/pagination 변경 시 5번 |
| `config/security` | 5번 | 모든 API owner | 5번만 직접 수정 가능: Spring Security chain, CORS, role mapping, JWT filter, public path allowlist. 각 owner는 필요한 path 권한을 문서와 PR 설명에 적는다 | 예, 5번 |
| Flyway migration | 5번 | 모든 DB table owner | 승인만 필요한 변경: 자기 도메인 table/index 초안 SQL. 5번만 직접 수정 가능한 변경: migration 번호, 실행 순서, extension, 공통 enum/check constraint, FK 순서 조정 | 예, 5번 |

## 담당자별 소유 범위

### 1번: Quote/Auth

| 항목 | 내용 |
|---|---|
| 담당 화면 route | `/`, `/requirements/new`, `/builds/:buildId`, `/builds/:buildId/change-part`, `/my/quotes`, `/login`, `/signup`, `/auth/callback` |
| frontend files | `features/quote/**`, `features/auth/pages/**` |
| backend packages | `build`, `requirement`, `user`, `auth` |
| DB tables | `requirements`, `builds`, `build_items`, `users`, `user_auth_providers`, `refresh_tokens` |
| API endpoints | `POST /api/requirements/parse`, `POST /api/builds/recommend`, `GET /api/builds/{id}`, `GET /api/builds/history`, `POST /api/builds/{id}/change-part`, `POST /api/users`, `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/auth/me`, `GET /api/auth/google/start`, `GET /api/auth/google/callback`, `POST /api/auth/exchange` |
| 협업자 | Auth 공통/token/guard/security는 5번, Tool/RAG 근거는 3번, parts 데이터는 2번 |

Auth 화면과 Auth/User API 구현 주 owner는 1번이다. 5번은 `apps/web/src/lib/api.ts`, `RequireAdmin`, admin guard, security allowlist, migration 순서 관점에서 협업한다.

### 2번: Parts/Price/Tool

| 항목 | 내용 |
|---|---|
| 담당 화면 route | `/self-quote`, `/admin/parts`, `/admin/price-jobs`, `/my/quotes`의 가격 알림 영역 |
| frontend files | `features/parts/**`, `features/admin/pages/AdminPartsPage.tsx`, `features/admin/pages/AdminPriceJobsPage.tsx` |
| backend packages | `part`, `price`, `tool`, `quote` |
| DB tables | `parts`, `price_snapshots`, `part_external_offers`, `part_catalog_refresh_jobs`, `part_catalog_candidates`, `quote_drafts`, `quote_draft_items`, `price_alerts`, `price_jobs`, `compatibility_rules`, `benchmark_summaries` |
| API endpoints | `GET /api/parts`, `GET /api/parts/{id}`, `GET /api/parts/{id}/price-history`, `GET /api/quote-drafts/current`, `PUT/PATCH/DELETE /api/quote-drafts/current/items/{partId}`, `GET /api/price-alerts`, `POST /api/price-alerts`, `GET /api/admin/price-jobs`, `POST /api/admin/price-jobs/run`, `POST /api/admin/parts/catalog/refresh`, `POST /api/admin/parts/external-offers/refresh`, 5개 Tool API |
| 협업자 | `price_jobs`, catalog refresh, external offer refresh infra 실행 환경은 5번, build 연동은 1번, Agent Tool 이력은 3번 |

`price_jobs`의 주 owner는 2번이고 협업자는 5번이다.

### 3번: Agent/RAG/Tool Evidence

| 항목 | 내용 |
|---|---|
| 담당 화면 route | `/support/ai-chat`, `/admin/agent-sessions/:id`, `/admin/tool-invocations/:id`, `/admin/rag-evidence/:id` |
| frontend files | `features/support/**` 중 AS AI Chat 화면/API, `features/admin/agent/**`, `features/admin/evidence/**` |
| backend packages | `agent`, `rag` |
| DB tables | `agent_sessions`, `tool_invocations`, `rag_evidence`, `as_chat_sessions`, `as_chat_messages`, `llm_generations` |
| API endpoints | `GET /api/ai/as-chat`, `POST /api/ai/as-chat`, `POST /api/ai/as-chat/stream`, `POST /api/agent/sessions`, `POST /api/agent/sessions/{id}/run`, `GET /api/agent/sessions/{id}`, `GET /api/rag/search`, `GET /api/rag/evidence/{id}`, `GET /api/admin/agent-sessions`, `GET /api/admin/agent-sessions/{id}`, `GET /api/admin/tool-invocations`, `GET /api/admin/tool-invocations/{id}`, `GET /api/admin/rag-evidence/{id}` |
| 협업자 | 추천 결과 UI는 1번, Tool 판정 로직은 2번, AS 원인 후보는 4번 |

### 4번: PC Agent/AS

| 항목 | 내용 |
|---|---|
| 담당 화면 route | `/support/new`, `/support/:ticketId`, `/admin/as-tickets`, `/admin/as-tickets/:ticketId` |
| frontend files | `features/support/**`, `features/admin/as-tickets/**` |
| backend packages | `log`, `ticket` |
| DB tables | `agent_log_uploads`, `as_tickets` |
| API endpoints | `POST /api/agent-logs/upload`, `GET /api/agent-logs/{id}`, `POST /api/as-tickets`, `GET /api/as-tickets/{id}`, `GET /api/admin/as-tickets`, `GET /api/admin/as-tickets/{id}`, `PATCH /api/admin/as-tickets/{id}` |
| 협업자 | Auth/guard는 5번, AS 원인 후보 Agent는 3번 |

### 5번: AdminShell/Auth Common/Infra

| 항목 | 내용 |
|---|---|
| 담당 화면 route | `/admin` shell/guard/dashboard, `/admin/load-tests` |
| frontend files | `features/admin/shell/**`, `components/**`, `apps/web/src/lib/api.ts`, `features/auth/RequireAdmin.tsx`, `features/admin/pages/AdminLoadTestsPage.tsx` |
| backend packages | `admin`, `common`, config/security |
| DB tables | `admin_audit_logs` |
| API endpoints | `GET /api/admin/dashboard`, `GET /api/admin/audit-logs/recent`, `GET /api/health` |
| 협업자 | Auth/User 구현은 1번, domain admin page 내부는 각 도메인 owner, `price_jobs` infra는 2번과 협업 |

## route ownership

| Route | 주 owner | 협업자 | 연결 API |
|---|---|---|---|
| `/` | 1번 | - | `POST /api/requirements/parse` |
| `/requirements/new` | 1번 | 3번 | `POST /api/requirements/parse`, `POST /api/builds/recommend` |
| `/builds/:buildId` | 1번 | 2번, 3번 | `GET /api/builds/{id}`, `GET /api/rag/evidence/{id}` |
| `/builds/:buildId/change-part` | 1번 | 2번 | `POST /api/builds/{id}/change-part`, `GET /api/parts` |
| `/my/quotes` | 1번 | 2번 | `GET /api/builds/history`, `GET /api/price-alerts`, `POST /api/price-alerts` |
| `/self-quote` | 2번 | 1번, 5번 | `GET /api/parts`, `GET /api/parts/{id}/price-history`, `GET /api/quote-drafts/current`, `PUT/PATCH/DELETE /api/quote-drafts/current/items/{partId}`, 5개 Tool API |
| `/parts/:partId` | 2번 | 5번 | `GET /api/parts/{id}`, `PUT /api/quote-drafts/current/items/{partId}` |
| `/login` | 1번 | 5번 | `POST /api/auth/login`, `GET /api/auth/google/start` |
| `/signup` | 1번 | 5번 | `POST /api/users` |
| `/auth/callback` | 1번 | 5번 | `POST /api/auth/exchange` |
| `/support/new` | 4번 | 5번 | `POST /api/agent-logs/upload`, `POST /api/as-tickets` |
| `/support/ai-chat` | 3번 | 4번, 5번 | `GET /api/ai/as-chat`, `POST /api/ai/as-chat/stream`, `POST /api/ai/as-chat` |
| `/support/:ticketId` | 4번 | - | `GET /api/as-tickets/{id}` |
| `/admin` | 5번 | 2번, 3번, 4번 | `GET /api/admin/dashboard`, `GET /api/admin/audit-logs/recent` |
| `/admin/parts` | 2번 | 5번 | `GET /api/parts`, `GET /api/parts/{id}/price-history`, `POST /api/admin/parts/catalog/refresh`, `POST /api/admin/parts/external-offers/refresh` |
| `/admin/price-jobs` | 2번 | 5번 | `GET /api/admin/price-jobs`, `POST /api/admin/price-jobs/run` |
| `/admin/load-tests` | 5번 | 2번, 3번, 4번 | k6 smoke/load report, `GET /api/health` smoke |
| `/admin/agent-sessions/:id` | 3번 | 5번 | `GET /api/admin/agent-sessions/{id}` |
| `/admin/tool-invocations/:id` | 3번 | 5번 | `GET /api/admin/tool-invocations/{id}` |
| `/admin/rag-evidence/:id` | 3번 | 5번 | `GET /api/admin/rag-evidence/{id}` |
| `/admin/as-tickets` | 4번 | 5번 | `GET /api/admin/as-tickets` |
| `/admin/as-tickets/:ticketId` | 4번 | 5번 | `GET /api/admin/as-tickets/{id}`, `PATCH /api/admin/as-tickets/{id}` |

관리자 상세 route는 AdminShell을 경유하지만 화면 내부의 주 owner는 각 도메인 owner다.

## API ownership

| API | 주 owner | 협업자 |
|---|---|---|
| `POST /api/users` | 1번 | 5번 |
| `POST /api/auth/login` | 1번 | 5번 |
| `POST /api/auth/refresh` | 1번 | 5번 |
| `POST /api/auth/logout` | 1번 | 5번 |
| `GET /api/auth/me` | 1번 | 5번 |
| `GET /api/auth/google/start` | 1번 | 5번 |
| `GET /api/auth/google/callback` | 1번 | 5번 |
| `POST /api/auth/exchange` | 1번 | 5번 |
| `POST /api/requirements/parse` | 1번 | - |
| `POST /api/builds/recommend` | 1번 | 2번, 3번 |
| `GET /api/builds/{id}` | 1번 | 2번, 3번 |
| `GET /api/builds/history` | 1번 | - |
| `POST /api/builds/{id}/change-part` | 1번 | 2번 |
| `GET /api/parts` | 2번 | - |
| `GET /api/parts/{id}` | 2번 | - |
| `GET /api/parts/{id}/price-history` | 2번 | 3번 |
| `GET /api/quote-drafts/current` | 2번 | 5번 |
| `PUT /api/quote-drafts/current/items/{partId}` | 2번 | 5번 |
| `PATCH /api/quote-drafts/current/items/{partId}` | 2번 | 5번 |
| `DELETE /api/quote-drafts/current/items/{partId}` | 2번 | 5번 |
| `GET /api/price-alerts` | 2번 | 1번 |
| `POST /api/price-alerts` | 2번 | 1번 |
| `GET /api/admin/price-jobs` | 2번 | 5번 |
| `POST /api/admin/price-jobs/run` | 2번 | 5번 |
| `POST /api/admin/parts/catalog/refresh` | 2번 | 5번 |
| `POST /api/admin/parts/external-offers/refresh` | 2번 | 5번 |
| `POST /api/tools/compatibility/check` | 2번 | 3번 |
| `POST /api/tools/power/check` | 2번 | 3번 |
| `POST /api/tools/size/check` | 2번 | 3번 |
| `POST /api/tools/performance/check` | 2번 | 3번 |
| `POST /api/tools/price/check` | 2번 | 3번 |
| `GET /api/ai/as-chat` | 3번 | 4번, 5번 |
| `POST /api/ai/as-chat` | 3번 | 4번, 5번 |
| `POST /api/ai/as-chat/stream` | 3번 | 4번, 5번 |
| `POST /api/agent/sessions` | 3번 | - |
| `POST /api/agent/sessions/{id}/run` | 3번 | - |
| `GET /api/agent/sessions/{id}` | 3번 | - |
| `GET /api/rag/search` | 3번 | - |
| `GET /api/rag/evidence/{id}` | 3번 | - |
| `GET /api/admin/agent-sessions` | 3번 | 5번 |
| `GET /api/admin/agent-sessions/{id}` | 3번 | 5번 |
| `GET /api/admin/tool-invocations` | 3번 | 5번 |
| `GET /api/admin/tool-invocations/{id}` | 3번 | 5번 |
| `GET /api/admin/rag-evidence/{id}` | 3번 | 5번 |
| `POST /api/agent-logs/upload` | 4번 | - |
| `GET /api/agent-logs/{id}` | 4번 | - |
| `POST /api/as-tickets` | 4번 | 3번 |
| `GET /api/as-tickets/{id}` | 4번 | - |
| `GET /api/admin/as-tickets` | 4번 | 5번 |
| `GET /api/admin/as-tickets/{id}` | 4번 | 5번 |
| `PATCH /api/admin/as-tickets/{id}` | 4번 | 5번 |
| `GET /api/admin/dashboard` | 5번 | 2번, 3번, 4번 |
| `GET /api/admin/audit-logs/recent` | 5번 | - |
| `GET /api/health` | 5번 | - |

## 내부 service 경계

`POST /api/builds/recommend`는 1번 owner API지만, 추천 과정에서 남기는 Agent/RAG/Tool 추적 데이터는 3번 owner 영역이다.

- 1번은 추천 API request/response, build 저장 transaction orchestration, build/build_items 저장을 담당한다.
- 2번은 Tool 판정 로직과 parts/price 데이터 조회 contract를 담당한다.
- 3번은 `agent_sessions`, `tool_invocations`, `rag_evidence` 저장 방식과 관리자 상세 조회 contract를 담당한다.
- 1번은 3번 table을 직접 insert/update하지 않고, 3번이 제공하는 내부 Agent trace service를 호출한다.
- 3번은 2번의 Tool 직접 check API 호출을 `tool_invocations`에 저장하지 않는다. `tool_invocations`에는 Agent/recommend 내부 Tool 호출 이력만 저장한다.
- `/support/ai-chat`은 3번 owner API/화면이지만 AS 티켓을 기준으로 동작하므로 4번과 협업한다.
- 3번은 AS Chat에서 `as_tickets`를 읽기만 하고 `cause_candidates`, `upgrade_candidates`, `status`를 수정하지 않는다.
- AS Chat 대화 이력은 `as_chat_sessions`, `as_chat_messages`에 저장하고, 원인 후보를 티켓에 반영하는 작업은 4번 API가 별도로 결정한다.
- AS Chat profile 비교와 `llm_generations` 기록은 3번 owner 범위다. 기본 사용자 요청은 profile 1개만 실행하고, OpenAI profile 비교는 benchmark 명령에서만 수행한다.

## 1주차 완료 기준

| 담당자 | 완료 기준 |
|---|---|
| 1번 | `/requirements/new`에서 `POST /api/requirements/parse`와 `POST /api/builds/recommend` mock 또는 dev API를 호출하고, `/builds/:buildId`가 `BuildDto`의 `items`, `totalPrice`, `confidence`, `warnings`, `evidenceIds`, `changeableCategories`를 렌더링한다. `/builds/:buildId/change-part`는 `POST /api/builds/{id}/change-part`의 성공/409 응답을 화면에서 구분한다. `/login`, `/signup`, `/auth/callback`과 Auth/User API는 `API_CONTRACT.md`/`openapi.yaml` 계약과 충돌하지 않게 연결한다. |
| 2번 | `GET /api/parts`, `GET /api/parts/{id}`, `GET /api/parts/{id}/price-history`, 5개 Tool API, `GET/POST /api/price-alerts`, `GET /api/admin/price-jobs`, `POST /api/admin/price-jobs/run`, `POST /api/admin/parts/catalog/refresh`, `POST /api/admin/parts/external-offers/refresh`가 계약 DTO로 응답한다. pagination 기본값/최대값, `price_jobs` 중복 실행 409, seed 기준 `parts` 조회 제외 조건, 내부 자산 갱신이 사용자 조회 중 외부 API를 호출하지 않는 조건, 가격 이력 조회가 `price_snapshots`만 읽는 조건을 contract test로 확인한다. |
| 3번 | `POST /api/agent/sessions`, `POST /api/agent/sessions/{id}/run`, `GET /api/agent/sessions/{id}`, admin Agent/RAG/Tool 상세 API가 public_id만 반환한다. 실행 중 세션 재실행 409, 금지 상태 전이 409, `stateTimeline`/`requestPayload`/`resultPayload` DTO shape를 contract test로 확인한다. |
| 4번 | `POST /api/agent-logs/upload`가 JSONL 파일 크기/MIME/확장자/라인 validation을 수행하고 `FILE_VALIDATION_ERROR`를 반환할 수 있다. `GET /api/agent-logs/{id}`, `POST/GET /api/as-tickets`, `PATCH /api/admin/as-tickets/{id}`가 본인 소유 404, 금지 상태 전이 409, soft delete 조회 제외를 contract test로 확인한다. |
| 5번 | AdminShell, `RequireAdmin`, `apps/web/src/lib/api.ts`, `config/security` review, Flyway 순서 검증, `GET /api/health`, `GET /api/admin/dashboard`, `GET /api/admin/audit-logs/recent`가 최소 DTO로 연결된다. admin 401/403 분기, public API BIGINT 비노출, migration 순서 검증을 contract test로 확인한다. |

## contract test 최소 세트

| 테스트 | 기준 |
|---|---|
| public API BIGINT 비노출 | 모든 public/admin response의 `id`, `*Id`, `targetId`는 UUID 문자열 또는 stable key이며 숫자 PK를 반환하지 않는다. |
| 401/403 권한 분기 | 인증 없음은 401, 인증은 있으나 `ADMIN` 권한이 없는 admin API 접근은 403이다. |
| 사용자 본인 자원 접근 | build, ticket, agent log, agent session이 본인 소유가 아니면 404를 반환한다. |
| 금지된 상태 전이 | `agent_sessions`, `as_tickets`, `price_jobs`, `price_alerts`, `agent_log_uploads`의 허용되지 않은 전이는 409 `CONFLICT_STATE`다. |
| soft delete 조회 제외 | `deleted_at IS NOT NULL`인 업무 리소스는 목록/상세에서 제외하고 상세 조회는 404다. |
| Flyway migration 순서 | extension, parent table, child table, FK 추가 순서가 `DB_SCHEMA.md`의 권장 순서와 충돌하지 않는다. |
| JSONL 업로드 validation | 크기, MIME/확장자, JSONL 파싱 실패는 400 `FILE_VALIDATION_ERROR`다. |
| pagination 기본값/최대값 | `page` 기본값은 0, `size` 기본값은 20, 최대값은 100이며 초과 시 400 `VALIDATION_ERROR`다. |

## owner별 route/API smoke test 출력 형식

각 owner는 1주차 종료 전 아래 형식의 smoke test 결과를 PR에 붙인다.

```text
owner: 1
route: /builds/:buildId
api: GET /api/builds/{id}
auth: USER
status: 200
responseIdKind: public_id
checked:
  - noBigintId
  - dtoFieldsMatch
  - ownerResourceOnly
```

## 공통 UI 수정 규칙

- `components/**`와 AdminShell 변경은 5번 주 owner가 승인한다.
- 각 기능 owner는 자기 feature 폴더 내부 컴포넌트를 우선 사용한다.
- 공통 table, badge, panel, form control을 변경할 때는 영향 route를 PR에 적는다.
- Auth layout과 Auth/User API는 1번 owner가 작업하되 admin guard와 token 공통 전달 정책은 5번 owner가 관리한다.

## PR 규칙

- PR 제목은 영역을 드러낸다. 예: `docs: add MVP API contract`.
- PR 설명에는 변경한 route, API, DB table, owner를 적는다.
- V1 제외 항목을 다시 포함하는 변경은 별도 팀 결정 없이는 금지한다.
- enum/status 추가나 의미 변경은 `DB_SCHEMA.md`와 `API_CONTRACT.md`를 함께 수정한다.
- API 추가/삭제/field 변경은 `API_CONTRACT.md`와 이 문서를 함께 수정한다.
- DB table/column/index/FK 변경은 `DB_SCHEMA.md`, 관련 API DTO가 있으면 `API_CONTRACT.md`, owner 영향이 있으면 이 문서를 함께 수정한다.
- 공통 파일 변경은 공유 지점 수정 권한 표의 reviewer 필수 여부를 PR description에 체크리스트로 남긴다.
- 병렬 개발 동결 이후 P0 계약 항목은 미정 상태로 남기지 않고 MVP 기준 결정값을 문서에 적는다.

## 수정 금지 규칙

- 다른 담당자 feature 폴더를 임의로 리팩터링하지 않는다.
- `products` 도메인 파일/문서를 추가하지 않는다.
- 주문, 결제, 배송, 재고 차감, 타임세일 관련 API/route/table을 V1에 추가하지 않는다.
- public route/API에서 내부 `BIGINT id`를 노출하지 않는다.
- AWS 공용 DB에서 직접 DDL을 수정하지 않는다.

## Codex 작업 규칙

- 작업 시작 전 관련 owner 문서를 확인한다.
- 불명확한 owner는 임의로 정하지 않고 주 owner와 협업자를 분리해 기록한다.
- 문서 변경 시 세 문서의 용어, enum, id 규칙이 같은지 확인한다.
- API path, enum/status, owner, DB table, public_id 규칙, error code, pagination 기본값, 권한 정책 중 하나라도 바꾸면 관련 문서를 같은 작업에서 동시에 수정한다.
- 새 기능을 추가하지 않고 계약 불일치만 고치는 경우에도 변경 이유를 PR에 적는다.
- 대화에서 확정한 결정값을 축소하거나 삭제하지 않는다.
