# 5번 작업 분석 체크리스트

작성일: 2026-06-29

## 결론

5번 담당 범위는 **Auth 공통 연동/AdminShell/AdminDashboard/공통 UI/Infra/CI**다.

로그인/회원가입의 프론트와 백엔드 구현은 1번에게 이관했다. 5번은 Auth API 구현 자체가 아니라 `api.ts`의 token 전달, `RequireAdmin`, `/admin/*` 권한 분기, AdminShell, AdminDashboard, Health, Infra/CI를 담당한다.

Figma 기준으로 5번이 직접 맡아야 할 화면은 `153:1880 STATE-15 ADMIN-01 운영 대시보드 / degraded`다. `153:2011 STATE-16 ADMIN-04 AS 티켓 관리자 / assigned success`는 4번 담당 화면이지만, 같은 AdminShell을 쓰므로 5번은 shell/guard 관점에서 참고해야 한다.

5번 기능 단위 상태는 1번 이관 작업을 제외하고 이렇게 정리한다.

| 기능 단위 | 상태 | 5번에게 남은 작업 |
| --- | --- | --- |
| Auth 공통 연동 | 진행중 | 1번 Auth/User 백엔드 구현은 `main`에 반영됨. `Authorization` header와 access token helper는 완료. refresh token 저장/재시도, logout API 호출, `ApiError`의 `ErrorResponse` 보존이 남음 |
| JWT/Token 연동 | 진행중 | JWT access token 발급/검증과 admin role 확인은 반영됨. `RequireUser`의 만료 token 처리와 Spring Security filter 전환 여부 검토가 남음 |
| Auth Error 연동 | 진행중 | admin 401/403 분기는 완료. 백엔드 `ErrorResponse`는 있으나 프론트 `ApiError`가 `code/message/details`를 버리고 있어 공통 오류 표시 고도화가 필요 |
| RequireAdmin | 완료 | `/admin/*` guard, `auth/me` role 확인, 401/403 화면 분기와 테스트 완료 |
| AdminShell | 진행중 | 8개 nav 메뉴와 route 연결 완료. topbar search 제외와 disabled action frame 완료. nav label/order owner 공유 미완료 |
| AdminDashboard | 완료 | DTO 정합성, metric, loading/error/success, degraded alert, 운영 작업, 관리자 할 일 link frame 완료 |
| Admin Audit Logs | 완료 | 백엔드 endpoint, 권한 테스트, seed 조회, 프론트 `최근 관리자 작업` 표시 연결 완료 |
| Common API Client | 진행중 | token header 첨부 완료. refresh/logout API가 들어왔으므로 refresh retry 1회, refresh 실패 시 token 정리, logout API 호출 후 clear, error normalization 구현을 시작할 수 있음 |
| Common UI | 완료 | 공통 UI barrel/layout/display/feedback 구조 유지 완료 |
| Health | 완료 | `/api/health` DB probe, 503 DOWN, 테스트/OpenAPI 완료. 2026-06-29 runtime 응답 `{"status":"UP","database":"UP"}` 확인 완료 |
| Docker Compose | 완료 | `docker compose config` 완료. Postgres init SQL 주입 제거 완료. `docker compose up --build` 기준 web/api/postgres/redis/rabbitmq/mailpit 기동과 health 응답 확인 완료 |
| Redis | 완료 | Sprint 1 smoke 완료: container `Up`, `redis-cli ping`=`PONG`. 실제 기능 사용은 1번 OAuth one-time code 또는 3번/공통 cache·quota 구현 후 연동 |
| RabbitMQ | 완료 | Sprint 1 smoke 완료: container `Up`, management API 200, queue 0개 확인. 실제 작업 등록/처리는 3번 AI 견적 추천 실행 작업 또는 2번 부품 가격 수집 작업 구현 후 연동 |
| Mailpit | 완료 | Sprint 1 smoke 완료: container `Up`, UI/API 200, SMTP 1025 연결 확인. 실제 가격 알림 메일은 2번 가격 알림 메일 구현 후 연동 |
| CI/GitHub Actions | 완료 | frontend build/test, OpenAPI, backend bootJar, compose config, health smoke 구성 완료 |
| k6/부하 테스트 | 진행중 | `infra/k6/smoke.js` skeleton과 `docs/reports/k6-smoke-report-template.md` 있음. 300명/1000명 부하 시나리오 확장은 별도 작업 |
| 테스트/검증 | 완료 | `npm build/test`, `gradlew test/bootJar`, OpenAPI validation, compose config, Docker Compose runtime health 검증 완료 |

## 분류별 최신 상태

### 프론트

| 기능 단위 | 상태 | 최신 판단 |
| --- | --- | --- |
| Auth 공통 연동 | 진행중 | `Authorization` header, `saveToken`, `getToken`, `clearToken` helper는 완료. 1번 Auth 구현이 `main`에 들어왔으므로 refresh token 저장/재시도와 logout API 연동 검토가 필요 |
| JWT/Token 연동 | 진행중 | 현재 프론트는 저장된 access token을 `Bearer`로 전달한다. 실제 JWT role 확인은 동작하므로 남은 것은 만료 token 처리와 Spring Security filter 전환 여부 검토 |
| Auth Error 연동 | 진행중 | `RequireAdmin`의 401/403 화면 분기는 완료. 공통 error normalization과 회원가입/로그인 validation 오류 표시 정합성 검토 필요 |
| RequireAdmin | 완료 | `/admin/*` guard, `auth/me` role 확인, token 없음/401/403/USER role 분기와 테스트 완료 |
| AdminShell | 진행중 | 8개 nav, topbar search 제외, export/action disabled frame 완료. nav label/order owner 공유만 남음 |
| AdminDashboard | 완료 | DTO 정합성, metric, loading/error/success, degraded alert, 운영 작업, 관리자 할 일 frame 완료 |
| Admin Audit Logs | 완료 | `getRecentAdminAuditLogs()` wrapper와 `/admin` 최근 관리자 작업 표시, 실패 패널 분리 완료 |
| Common API Client | 진행중 | token header 첨부는 완료. refresh/logout API가 있으므로 token 만료 시 `clearToken()`, refresh retry 1회, logout 후 token 정리, error normalization 구현을 진행할 수 있음 |
| Common UI | 완료 | `components/ui.tsx` barrel 유지, 공통 layout/display/feedback 구조 유지 완료 |

### 백엔드

| 기능 단위 | 상태 | 최신 판단 |
| --- | --- | --- |
| JWT/Token 연동 | 진행중 | 현재 admin/user API는 seed 사용자 비밀번호 검증과 JWT access token을 사용한다. Spring Security filter 전환 여부는 후속 작업에서 검토한다. |
| Auth Error 연동 | 진행중 | `ApiExceptionHandler`의 401/403 `ErrorResponse`는 완료. 400 validation, 409 duplicate, refresh/logout 오류 shape가 프론트 공통 `ApiError`에 보존되는지 검토 필요 |
| AdminDashboard | 완료 | `GET /api/admin/dashboard`가 OpenAPI DTO 기준 필드와 일치한다. 계약 변경 시 계속 동시 검토 필요 |
| Admin Audit Logs | 완료 | `GET /api/admin/audit-logs/recent` 응답 shape, seed 조회, 권한 테스트, 프론트 표시 연결 완료 |
| Health | 완료 | `/api/health` DB probe, DB 실패 503, OpenAPI, 테스트, runtime smoke 확인 완료 |
| Common API/Error | 진행중 | 공통 `ApiExceptionHandler` skeleton은 있음. validation/detail field/error code 확장은 1번 Auth와 각 domain 구현 후 검토 필요 |

### 인프라·문서·검증

| 기능 단위 | 상태 | 최신 판단 |
| --- | --- | --- |
| Docker Compose | 완료 | `docker compose config`, `docker compose up --build`, web/API/postgres/redis/rabbitmq/mailpit 기동, health 응답 확인 완료 |
| Redis | 완료 | Sprint 1 smoke 완료. 실제 사용 후보는 OAuth one-time code, LLM/RAG cache, quota, job state |
| RabbitMQ | 완료 | Sprint 1 smoke 완료. 작업 대기열 이름 초안: `agent.jobs`, `price.jobs`, `mail.jobs` |
| Mailpit | 완료 | Sprint 1 smoke 완료. 실제 사용 후보는 가격 알림 메일 |
| CI/GitHub Actions | 완료 | web build/test, OpenAPI 검증, API test/bootJar, compose config, Docker build, API runtime smoke 구성 완료 |
| k6/부하 테스트 | 진행중 | smoke script와 smoke report template 완료. 300명/1000명 부하 시나리오 확장은 별도 작업 |
| 테스트/검증 | 완료 | frontend/backend/OpenAPI/compose/runtime health 검증 기록 완료 |
| API 계약 문서 | 완료 | `API_CONTRACT.md`의 Auth/User owner를 1번으로 정리했다. Auth error/refresh/logout 세부 계약이 바뀌면 `openapi.yaml`과 같이 검토 |
| Route Ownership 문서 | 완료 | `ROUTE_OWNERSHIP.md`, `role-workspaces.md`, `README.md`, Sprint 1 체크리스트에 Auth/User 구현 owner 1번, Auth 공통/token/guard owner 5번 기준을 반영했다 |

### 분류 요약

| 분류 | 완료 | 진행중 | 시작안함 |
| --- | --- | --- | --- |
| 프론트 | `RequireAdmin`, `AdminDashboard`, `Admin Audit Logs`, `Common UI` | `Auth 공통 연동`, `JWT/Token 연동`, `Auth Error 연동`, `AdminShell`, `Common API Client` | 없음 |
| 백엔드 | `AdminDashboard`, `Admin Audit Logs`, `Health` | `JWT/Token 연동`, `Auth Error 연동`, `Common API/Error` | 없음 |
| 인프라·문서·검증 | `Docker Compose`, `Redis`, `RabbitMQ`, `Mailpit`, `CI/GitHub Actions`, `테스트/검증`, `API 계약 문서`, `Route Ownership 문서` | `k6/부하 테스트` | 없음 |

## 2026-06-30 main 반영 후 1~4번 작업 기반 5번 후속 작업

현재 `main`에는 1번 Auth/JWT/refresh/logout, 2번 parts/price 일부, 3번 Agent/RAG/AS Chat, 4번 AS ticket 흐름이 들어와 있다. 이 변경을 기준으로 5번에게 남은 일은 새 도메인 기능 구현이 아니라 **공통 API client, 관리자 shell/guard, 인프라 smoke, API 계약 위반 감시, PR 전 검증**이다.

## 2026-06-30 1~4번 작업 완료 여부 감사

결론: **1~4번 모두 프로젝트 계약 기준으로 완전 완료는 아니다.** 현재 상태는 "핵심 골격과 일부 주요 흐름 구현 완료, 계약/소유권/관리자 화면/테스트 보강 필요"다.

완료 판단 기준은 `docs/ROUTE_OWNERSHIP.md`, `docs/API_CONTRACT.md`, 실제 frontend route, backend controller/service, DB migration, 테스트 존재 여부를 함께 봤다.

| 담당 | 완료 판정 | 완료된 것 | 완료 아님/남은 것 |
| --- | --- | --- | --- |
| 1번 Quote/Auth | 부분완료 | Auth 백엔드 로그인/회원가입/JWT/refresh/logout, 기본 로그인/회원가입 화면, 요구사항 분석/추천/Build 상세/부품 변경 API 호출 흐름 | Google OAuth start/callback/exchange 미구현, `BuildQueryService`가 현재 사용자 대신 `user@example.com`에 저장, build history/detail/change 소유권 404 미흡, `/my/quotes` mock/정적 가격 알림, refresh token 프론트 저장/재시도 미연결 |
| 2번 Parts/Price/Tool | 부분완료 | 부품 목록/상세/가격 이력, 셀프 견적초안, Tool check, 네이버 외부 가격 후보/cache, catalog/external-offer refresh 골격 | 가격 알림이 현재 사용자 기준이 아니고 중복 409 없음, `/admin/price-jobs` 화면 정적/disabled, `POST /api/admin/price-jobs/run` active job 409 계약 불일치, 실제 queue/worker 가격 수집 미연결, price/domain contract test 부족 |
| 3번 Agent/RAG/AS Chat | 부분완료 | RAG 검색/근거 조회, Agent trace 저장, AS Chat SSE/LLM 구조화 응답, admin Agent/RAG/Tool 상세 API 연결 | 일반 Agent session API가 본인 소유 404를 보장하지 않음, Agent queue/RabbitMQ worker 미구현, AdminShell 메뉴가 list가 아니라 seed id 상세로 직접 이동, AgentController/AsChatController contract test 부족 |
| 4번 PC Agent/AS | 부분완료 | 사용자 AS 접수 화면, 로그 업로드 API, AS 티켓 생성/조회 API, 사용자 티켓 상세 화면 | 로그 업로드가 현재 사용자 대신 `user@example.com`에 저장, JSONL/MIME/크기 검증 미흡, 티켓 생성/조회 소유권 404 미흡, 관리자 AS 티켓 화면 mock/no-op, `PATCH /api/admin/as-tickets/{id}` 상태 전이 409/audit log/assignedAdminId 정책 미구현, backend test 없음 |

### 1번 상세 감사

| 영역 | 상태 | 근거 |
| --- | --- | --- |
| Auth/User 백엔드 | 완료에 가까움 | `UserController`, `UserQueryService`, `PasswordService`, `JwtTokenService`, `RefreshTokenService`가 있고 관련 테스트가 있다. |
| Auth 프론트 | 부분완료 | 로그인/회원가입 API 호출은 하지만 refresh token은 저장하지 않고, 실패 시 backend validation 메시지를 보여주지 않는다. |
| Google OAuth | 미완료 | 계약에는 `GET /api/auth/google/start`, `GET /api/auth/google/callback`, `POST /api/auth/exchange`가 있으나 controller 구현이 없다. |
| Quote/Build API | 부분완료 | `parse`, `recommend`, `build detail`, `history`, `change-part`는 있으나 현재 사용자 소유권 기준이 빠져 있다. |
| 내 견적함 | 미완료 | `/my/quotes`는 `quoteMock`을 렌더링하고 가격 알림 등록도 정적 form이다. |
| 테스트 | 부족 | Auth 테스트는 많지만 Build/Quote controller/service contract test가 없다. |

핵심 근거:

- `BuildQueryService.parse()`는 인증 사용자가 아니라 `(SELECT id FROM users WHERE email = 'user@example.com')`로 requirement를 저장한다.
- `BuildQueryService.builds()`는 모든 build를 최신순으로 반환하고 사용자별 filtering이 없다.
- `BuildQueryService.buildRow()`는 `public_id`만 보고 조회하며 소유자 조건이 없다.
- `MyQuotesPage.tsx`는 `../mocks/quoteMock`의 `builds`를 그대로 사용한다.

### 2번 상세 감사

| 영역 | 상태 | 근거 |
| --- | --- | --- |
| Parts 조회 | 완료에 가까움 | 목록, 상세, 가격 이력, 필터, pagination, 저장된 외부 가격 cache 표시가 있다. |
| Quote Draft | 완료에 가까움 | 현재 사용자 기준의 견적초안 생성/담기/수량 변경/삭제가 구현되어 있다. |
| Tool check | 부분완료 | 5개 Tool API와 rule 기반 판정은 있으나 일부 fallback은 seed 결과 중심이다. |
| Price Alert | 부분완료 | API는 있으나 현재 사용자 대신 `user@example.com`에 저장하고, 중복 active alert는 409가 아니라 기존 row 반환이다. |
| Price Job | 미완료에 가까움 | backend skeleton은 있으나 active job 중복 409 계약과 다르고, frontend `/admin/price-jobs`는 정적/disabled다. |
| 테스트 | 부족 | `PartControllerTest`는 Tool auth 중심이고 parts/quote draft/price alert/price job contract test가 없다. |

핵심 근거:

- `PriceQueryService.createAlert()`는 `(SELECT id FROM users WHERE email = 'user@example.com')`로 저장한다.
- `PriceQueryService.createAlert()`는 중복 alert가 있으면 `409 DUPLICATE_RESOURCE`가 아니라 기존 row를 반환한다.
- `PriceQueryService.runPriceJob()`은 active job이 있으면 `409 CONFLICT_STATE`가 아니라 기존 job을 반환한다.
- `AdminPriceJobsPage.tsx`는 `GET /api/admin/price-jobs`를 호출하지 않고 정적 row와 disabled 버튼만 보여준다.

### 3번 상세 감사

| 영역 | 상태 | 근거 |
| --- | --- | --- |
| RAG 검색/상세 | 부분완료 | `RagController`, `RagQueryService`, admin evidence 조회가 있고 테스트도 일부 있다. |
| Agent 세션 | 부분완료 | create/run/get과 trace 저장은 있으나 사용자 소유권 조건이 없다. |
| AS Chat | 완료에 가까움 | 사용자 티켓 소유권 확인, SSE, RAG/Tool/LLM 구조화 응답, `llm_generations` 저장 흐름이 있다. |
| Admin Agent/RAG/Tool 화면 | 부분완료 | 상세 화면은 API 연결됐지만 메뉴가 목록이 아니라 seed 상세 id로 바로 이동한다. |
| Queue/Worker | 미완료 | RabbitMQ 기반 Agent job queue/ack/retry는 아직 없다. |
| 테스트 | 부족 | RAG retrieval/profile/OpenAI request 테스트는 있으나 AgentController/AsChatController contract test가 부족하다. |

핵심 근거:

- `AgentQueryService.createSession()`은 `currentUser`를 받지 않고 `AgentTraceService.createQueuedSession(root, "USER")`만 호출한다.
- `AgentQueryService.agentSessionRow()`는 `s.public_id = ?`만 조건으로 보고 사용자 소유자 조건이 없다.
- `AdminShell`의 Agent/Tool/RAG 메뉴는 seed public_id 상세 route로 직접 이동한다.

### 4번 상세 감사

| 영역 | 상태 | 근거 |
| --- | --- | --- |
| 사용자 AS 접수 화면 | 부분완료 | 파일 선택, 동의, 로그 업로드, 티켓 생성 API 호출, 상세 화면 이동 흐름은 있다. |
| 로그 업로드 API | 미완료에 가까움 | `MultipartFile`을 받지만 JSONL 파싱/확장자/MIME/크기 검증이 없고, 현재 사용자 대신 seed user에 저장한다. |
| AS 티켓 API | 부분완료 | 생성/조회/update skeleton은 있으나 소유권, 상태 전이, audit log가 빠져 있다. |
| 관리자 AS 화면 | 미완료 | `/admin/as-tickets`, `/admin/as-tickets/:ticketId`가 mock data와 no-op 버튼이다. |
| 테스트 | 미완료 | `log`, `ticket` package에 backend controller/service test가 없다. |

핵심 근거:

- `AgentLogQueryService.upload()`는 file이 없어도 기본 파일명으로 저장하고 `user@example.com`에 연결한다.
- `AgentLogQueryService.detail()`은 `public_id`만 보고 조회하며 현재 사용자 소유 조건이 없다.
- `TicketQueryService.create()`는 `user@example.com`으로 티켓을 생성한다.
- `TicketQueryService.ticket()`은 `public_id`만 보고 조회하며 현재 사용자 소유 조건이 없다.
- `TicketQueryService.update()`는 임의 status update만 하고 허용 상태 전이, 409, `assignedAdminId`, audit log를 처리하지 않는다.
- `AdminTicketsPage.tsx`와 `AdminTicketDetailPage.tsx`는 mock data와 정적 버튼을 사용한다.

### 검증 결과

| 검증 | 결과 | 해석 |
| --- | --- | --- |
| OpenAPI validation | 통과, 49 paths | 문법 검증 통과다. 실제 구현이 계약을 모두 만족한다는 뜻은 아니다. |
| Backend test | 통과 | 현재 존재하는 테스트는 통과. 단, Build/Price/Agent ownership/Log/Ticket contract test가 부족하다. |
| Frontend test | 통과, 61개 | 현재 UI smoke/mock 테스트는 통과. mock/static 화면도 테스트를 통과할 수 있어 완료 근거로는 부족하다. |

### 완료라고 볼 수 없는 결정적 이유

- [ ] 사용자별 소유권이 여러 핵심 API에서 빠져 있다. 계약상 본인 소유가 아니면 `404 NOT_FOUND`여야 한다.
- [ ] 1번 `/my/quotes`, 2번 `/admin/price-jobs`, 4번 `/admin/as-tickets`가 실제 데이터 연결이 아니라 mock/static이다.
- [ ] 가격 alert/job의 409 계약이 구현과 다르다.
- [ ] Google OAuth 계약 endpoint가 구현되지 않았다.
- [ ] PC Agent 로그 업로드의 JSONL/MIME/크기 validation이 부족하다.
- [ ] AS 티켓 관리자 상태 전이/audit log 정책이 구현되지 않았다.
- [ ] Agent job/price job의 RabbitMQ queue/worker 처리는 아직 없다.
- [ ] 통과한 테스트가 현재 미완료 계약을 잡지 못한다.

## 2026-06-30 1~4번에게 공유할 말

아래 내용은 5번이 다른 owner 기능을 대신 구현하겠다는 뜻이 아니다. 각 owner가 자기 담당 기능을 마무리할 때 **계약 문서, 공통 인증, 관리자 shell, 인프라 검증과 충돌하지 않게 맞춰야 하는 지점**을 공유하는 것이다.

### 공통 공유 메시지

현재 1~4번 작업은 전체적으로 골격은 들어왔지만, `docs/API_CONTRACT.md`와 `docs/ROUTE_OWNERSHIP.md` 기준으로는 아직 완전 완료가 아닙니다.

특히 **현재 로그인 사용자 기준 소유권 처리, 409 상태 충돌, mock/static 관리자 화면, 부족한 contract test**가 남아 있습니다.

각자 담당 API는 다음 기준을 맞춰 주세요.

- 로그인 사용자 자원은 JWT의 현재 사용자 기준으로 저장/조회해야 합니다.
- 본인 소유가 아닌 build, ticket, log, agent session은 `403`이 아니라 `404 NOT_FOUND`여야 합니다.
- active job/session/alert 중복이나 금지 상태 전이는 `409 CONFLICT_STATE` 또는 `409 DUPLICATE_RESOURCE`로 맞춰야 합니다.
- public response에는 내부 DB `BIGINT id`가 나가면 안 되고 `public_id` 문자열만 나가야 합니다.
- mock/static 화면은 실제 API 연결 전이면 PR 설명과 화면 문구에 명확히 표시해 주세요.
- API path/request/response/error code를 바꾸면 `docs/API_CONTRACT.md`와 `docs/openapi.yaml`을 같이 수정해 주세요.
- 새 기능은 controller/service 테스트나 Playwright 테스트까지 같이 넣어 주세요.

### 1번에게 전달할 말

1번은 Quote/Auth owner라서 Auth는 많이 진행됐지만, Quote/Build 쪽 계약이 아직 남아 있습니다.

- Auth 백엔드의 로그인/회원가입/JWT/refresh/logout은 들어왔습니다.
- Google OAuth 계약 endpoint인 `GET /api/auth/google/start`, `GET /api/auth/google/callback`, `POST /api/auth/exchange`는 구현 여부를 확정해 주세요. MVP에서 제외할 거면 계약 문서도 같이 바꿔야 합니다.
- `BuildQueryService`에서 requirement/build 저장이 현재 로그인 사용자 기준이 아니라 `user@example.com` 기준으로 들어가는 부분을 현재 사용자 기준으로 바꿔 주세요.
- `GET /api/builds/history`, `GET /api/builds/{id}`, `POST /api/builds/{id}/change-part`는 현재 로그인 사용자 소유권을 확인하고, 남의 자원이면 `404 NOT_FOUND`를 반환해야 합니다.
- `/my/quotes`는 현재 `quoteMock` 기반입니다. 실제 `GET /api/builds/history`와 2번의 `GET/POST /api/price-alerts` 연동 범위를 정리해 주세요.
- 로그인/회원가입 실패 시 backend `ErrorResponse`의 validation/duplicate 메시지를 화면에 보여줄 수 있게 5번 `api.ts` 공통 오류 처리와 맞춰 주세요.
- Build/Quote controller/service contract test를 추가해 주세요. 특히 소유권 404, validation 400, public_id 노출을 확인해야 합니다.

5번 협업 지점:

- refresh token 저장/재시도/logout API 호출은 5번 `api.ts` 공통 client와 같이 맞추겠습니다.
- Auth 오류 응답 shape가 바뀌면 5번이 `ErrorResponse` 보존 로직을 같이 맞추겠습니다.

### 2번에게 전달할 말

2번은 Parts/Price/Tool owner라서 parts와 quote draft는 많이 진행됐지만, price alert와 price job 계약이 남아 있습니다.

- `GET /api/parts`, `GET /api/parts/{id}`, `GET /api/parts/{id}/price-history`, quote draft, Tool check는 현재 구현 수준이 높습니다.
- `GET /api/price-alerts`와 `POST /api/price-alerts`는 현재 로그인 사용자 기준으로 조회/생성되게 바꿔 주세요.
- 같은 사용자, 같은 `partId`, 같은 `targetPrice`의 active alert 중복은 기존 row 반환이 아니라 `409 DUPLICATE_RESOURCE`여야 합니다.
- `POST /api/admin/price-jobs/run`은 active job이 있으면 기존 job 반환이 아니라 `409 CONFLICT_STATE`여야 합니다.
- `/admin/price-jobs`는 현재 정적 안내와 disabled 버튼입니다. 2번 owner 화면으로 실제 `GET /api/admin/price-jobs`, `POST /api/admin/price-jobs/run`을 연결할지 확정해 주세요.
- 네이버 쇼핑 API/다나와 제한 크롤링 key나 설정은 프론트에 노출하지 말고 API 서버 env에서만 관리해야 합니다.
- price alert, price job, catalog refresh, external offer refresh에 대한 controller/service contract test를 추가해 주세요.

5번 협업 지점:

- 가격 수집 작업을 RabbitMQ worker로 연결하는 시점이 오면 `price.jobs` queue, 중복 실행, 실패 기록, Docker/RabbitMQ smoke를 같이 검토하겠습니다.
- Mailpit으로 가격 알림 메일을 검증해야 하면 5번이 SMTP/Mailpit runtime smoke를 맡겠습니다.

### 3번에게 전달할 말

3번은 Agent/RAG/AS Chat owner라서 AS Chat과 RAG 근거 흐름은 많이 들어왔지만, 일반 Agent session 계약과 queue 정책이 남아 있습니다.

- AS Chat은 사용자 티켓 소유권 확인, SSE 진행 이벤트, RAG/Tool/LLM 구조화 응답까지 잘 들어와 있습니다.
- 일반 `POST /api/agent/sessions`, `POST /api/agent/sessions/{id}/run`, `GET /api/agent/sessions/{id}`는 현재 로그인 사용자 소유권을 확인해야 합니다.
- 본인 소유가 아닌 Agent session은 계약대로 `404_NOT_FOUND`를 반환해야 합니다.
- AdminShell의 `Agent 세션`, `Tool 이력`, `RAG 근거` 메뉴가 현재 seed 상세 id로 바로 이동합니다. list route를 만들지, seed/sample link로 둘지 결정해 주세요.
- Agent job을 실제 비동기 queue로 처리할 계획이면 상태 전이 `QUEUED -> RUNNING -> RAG_SEARCHED -> TOOLS_CALLED -> SUMMARY_READY -> SUCCEEDED`와 실패/취소 정책을 문서와 테스트에 맞춰 주세요.
- AgentController/AsChatController contract test를 보강해 주세요. 특히 소유권 404, 상태 전이 409, SSE fallback/error, LLM JSON 실패 502를 확인해야 합니다.

5번 협업 지점:

- Agent job을 RabbitMQ로 연결하는 시점이 오면 `agent.jobs` queue, ack/retry, 최종 실패 기록, Docker/RabbitMQ smoke를 같이 검토하겠습니다.
- AS Chat SSE는 `api.ts` 공통 wrapper를 우회하므로, token header/401/ErrorResponse 처리 정책은 5번과 맞춰 주세요.

### 4번에게 전달할 말

4번은 PC Agent/AS owner라서 사용자 AS 접수 흐름은 들어왔지만, 로그 검증과 관리자 AS 화면은 아직 남아 있습니다.

- `/support/new`에서 로그 업로드 후 AS 티켓 생성, `/support/:ticketId` 상세 조회 흐름은 들어왔습니다.
- `POST /api/agent-logs/upload`는 현재 file이 없어도 저장될 수 있고 JSONL/확장자/MIME/크기 validation이 부족합니다. 계약대로 실패 시 `400 FILE_VALIDATION_ERROR`를 반환하고 DB row를 만들지 않아야 합니다.
- 로그 업로드와 티켓 생성은 현재 로그인 사용자 기준으로 저장해야 합니다. 현재처럼 `user@example.com` seed user에 묶이면 안 됩니다.
- `GET /api/agent-logs/{id}`, `GET /api/as-tickets/{id}`는 현재 로그인 사용자 소유권을 확인하고, 남의 자원이면 `404_NOT_FOUND`여야 합니다.
- `/admin/as-tickets`, `/admin/as-tickets/:ticketId`는 현재 mock/static 화면입니다. 실제 `GET /api/admin/as-tickets`, `GET/PATCH /api/admin/as-tickets/{id}`와 연결해 주세요.
- `PATCH /api/admin/as-tickets/{id}`는 계약의 허용 상태 전이만 허용하고, 금지 전이는 `409 CONFLICT_STATE`를 반환해야 합니다.
- 관리자 상태 변경, 담당자 배정, 상태 전이 거절은 `admin_audit_logs` 기록 정책과 맞춰 주세요.
- log/ticket controller/service test를 추가해 주세요. 특히 파일 validation, 소유권 404, 상태 전이 409, soft delete 제외를 확인해야 합니다.

5번 협업 지점:

- 관리자 AS 화면은 AdminShell/RequireAdmin 안에서 동작해야 하므로 route guard와 401/403은 5번이 같이 검토하겠습니다.
- 로그 업로드/AS ticket runtime smoke가 필요하면 Docker/API/파일 업로드 검증을 같이 보겠습니다.

### 5번이 팀에 약속할 것

- 5번은 1~4번 도메인 비즈니스 로직을 대신 구현하지 않는다.
- 대신 공통 API client, `RequireUser`/`RequireAdmin`, AdminShell, 인프라, CI, OpenAPI/계약 검증을 책임진다.
- 각 owner PR에서 auth/error/pagination/public_id/admin 권한/인프라 영향이 있으면 5번이 reviewer로 확인한다.
- 계약 위반이 보이면 기능 완성 여부와 별개로 먼저 문서와 테스트 기준을 맞추도록 요청한다.

### 1번 Auth/User 작업 반영 후

| 상태 | 5번 후속 작업 | 근거 |
| --- | --- | --- |
| 진행 필요 | `api.ts`가 backend `ErrorResponse`의 `code`, `message`, `details`를 보존하도록 `ApiError`를 확장한다. | 현재 `ApiError`는 `status/path`만 보존해서 회원가입 400, 중복 이메일 409, refresh 실패 원인을 화면에서 구분하기 어렵다. |
| 진행 필요 | refresh token 저장 위치와 `api.ts` refresh retry 1회 정책을 실제 `POST /api/auth/refresh` 응답에 맞춘다. | 1번이 refresh/logout 백엔드를 구현했지만 프론트 공통 client는 access token만 저장한다. |
| 진행 필요 | logout 버튼이 단순 `clearToken()`이 아니라 `POST /api/auth/logout` 호출 후 token을 정리할지 1번과 맞춘다. | 현재 header logout은 로컬 access token만 삭제한다. 서버 refresh token 폐기와 연결되어 있지 않다. |
| 진행 필요 | `RequireUser`가 token 존재만 보는 현재 방식으로 충분한지, 만료 token이면 `/api/auth/me` 확인 또는 API 401 처리로 정리할지 결정한다. | 현재 일반 사용자 route는 localStorage token만 있으면 진입하고, 만료/폐기 token은 내부 API 호출 때 실패한다. |
| 진행 필요 | 회원가입 400/409 응답이 화면에서 실제 validation 메시지로 보이도록 Auth UI owner 1번과 오류 표시 계약을 맞춘다. | 1번 화면 owner, 5번 공통 `ApiError` owner가 겹치는 지점이다. |

### 2번 Parts/Price 작업 반영 후

| 상태 | 5번 후속 작업 | 근거 |
| --- | --- | --- |
| 진행 필요 | `POST /api/admin/price-jobs/run`의 중복 실행 정책을 2번과 확인한다. 계약은 active job 존재 시 `409 CONFLICT_STATE`인데 현재 구현은 기존 job을 그대로 반환한다. | `docs/API_CONTRACT.md`와 `PriceQueryService.runPriceJob()` 동작이 다르다. |
| 진행 필요 | `/admin/price-jobs` 화면이 실제 `GET /api/admin/price-jobs`/`POST /api/admin/price-jobs/run`과 연결될지, 2번 owner 화면으로 남길지 명확히 공유한다. | 현재 `AdminPriceJobsPage`는 정적 안내와 disabled 버튼이다. |
| 대기 | 2번이 실제 부품 가격 수집 작업을 queue/worker로 연결하면 `price.jobs` 작업 등록, 중복 실행, 실패 기록, RabbitMQ smoke를 같이 검토한다. | 5번은 가격 비즈니스 로직이 아니라 작업 대기열/인프라 협업자다. |
| 대기 | 네이버 쇼핑 API key, 다나와 제한 크롤링 설정, 가격 알림 메일 설정이 프론트에 노출되지 않고 API 서버 env로만 관리되는지 확인한다. | 외부 API/메일 설정은 인프라·보안 검토 대상이다. |

### 3번 Agent/RAG/AS Chat 작업 반영 후

| 상태 | 5번 후속 작업 | 근거 |
| --- | --- | --- |
| 진행 필요 | AdminShell의 `Agent 세션`, `Tool 이력`, `RAG 근거` 메뉴가 고정 sample id로 이동하는 현재 방식을 3번과 확인한다. list route를 만들지, 최신 항목으로 이동할지 결정해야 한다. | 현재 sidebar는 seed public_id에 직접 연결되어 있다. |
| 진행 필요 | `streamAsChat()`이 공통 `api()`를 우회하는 fetch/SSE 흐름이라 401, token header, `ApiError` body 보존 정책이 공통 client와 어긋나지 않는지 검토한다. | SSE는 일반 JSON API wrapper와 처리 방식이 다르다. |
| 대기 | 3번이 AI 견적 추천 실행 작업을 RabbitMQ로 연결하면 `agent.jobs` 작업 등록, 성공 확인, 재시도, 최종 실패 기록 정책을 같이 검토한다. | 5번은 Agent 내부 로직이 아니라 queue 운영 정책 협업자다. |
| 진행 필요 | LLM profile/benchmark 설정값이 `.env.example`, compose, 문서에 안전하게 정리되어 있고 실제 secret이 커밋되지 않는지 확인한다. | AS Chat profile benchmark가 추가되어 환경변수 관리 범위가 늘었다. |

### 4번 PC Agent/AS 작업 반영 후

| 상태 | 5번 후속 작업 | 근거 |
| --- | --- | --- |
| 진행 필요 | `/admin/as-tickets`와 `/admin/as-tickets/:ticketId`가 mock/static 화면으로 남아 있는지 4번과 공유한다. 실제 API 연결은 4번 owner, 5번은 shell/guard/route slot을 유지한다. | 현재 관리자 AS 티켓 목록/상세는 mock data와 no-op 버튼을 사용한다. |
| 진행 필요 | `PATCH /api/admin/as-tickets/{id}`의 상태 전이 409, 401/403, audit log 정책이 계약과 맞는지 4번 PR에서 reviewer로 확인한다. | AS 티켓 내부는 4번이지만 admin 권한/오류/감사 로그는 5번 공통 기준과 맞아야 한다. |
| 대기 | PC Agent 로그 업로드/AS 티켓 생성이 실제 파일 validation과 연결되면 Docker/runtime smoke와 public_id 비노출 검증을 다시 실행한다. | 4번 기능이 운영 데이터와 파일 업로드를 포함하므로 공통 검증 영향이 있다. |

### 5번 단독으로 바로 할 수 있는 일

- [ ] `api.ts`의 `ApiError`가 backend `ErrorResponse`를 보존하도록 테스트를 먼저 추가하고 구현한다.
- [ ] refresh token 저장/조회/삭제 helper와 refresh retry 1회 정책을 1번 Auth 계약 기준으로 구현할지 최종 확인한다.
- [ ] `RequireUser`의 만료 token 처리 정책을 문서화하고, 필요하면 테스트를 먼저 작성한다.
- [ ] AdminShell sample id 메뉴를 3번과 공유하고, list route가 없으면 seed/sample link임을 화면 또는 문서에 명확히 둔다.
- [ ] `/admin/price-jobs`와 `/admin/as-tickets`가 현재 static/mock 상태임을 2번/4번과 공유한다.
- [ ] `POST /api/admin/price-jobs/run` 중복 실행 409 계약 위반 여부를 2번과 확인한다.
- [ ] AS Chat SSE fetch가 공통 auth/error 정책과 맞는지 확인한다.
- [ ] 300명/1000명 k6 시나리오를 Auth, Parts, AS Chat, Admin smoke로 분리해 설계한다.
- [ ] 새로 들어온 1~4번 작업 기준으로 `npm --prefix apps/web run build`, `npm --prefix apps/web run test`, `cd apps/api && ./gradlew test --no-daemon`, `cd apps/api && ./gradlew bootJar --no-daemon`, OpenAPI validation, `docker compose config`를 PR 전 다시 기록한다.

5번이 하면 안 되는 것도 명확합니다.

| 기능 | 담당 |
| --- | --- |
| 로그인/회원가입 화면 UI, form state, validation 문구 | 1번 |
| 로그인/회원가입 백엔드 Auth/User 구현 | 1번 |
| `POST /api/users`, `POST /api/auth/login`, refresh/logout/OAuth 구현 | 1번 |
| 부품/가격 비즈니스 로직 | 2번 |
| LLM/RAG/Agent 내부 로직 | 3번 |
| PC Agent, AS 티켓 내부 로직 | 4번 |
| `/admin/as-tickets` 화면 내부 기능 | 4번 |
| `/admin/parts` 화면 내부 기능 | 2번 |

## 운영 규칙

- 커밋 메시지 작성을 요청받으면 먼저 이 체크리스트를 최신 구현/검증 상태로 갱신한다.
- 새로 완료한 route, API, 테스트, 검증 명령, Docker/runtime 확인, owner 결정 사항이 있으면 커밋 메시지 제안 전에 이 문서에 반영한다.
- 체크리스트에 반영하지 않는 변경이 있으면 커밋 메시지 응답의 주의할 점에 제외 이유를 함께 적는다.

## 5번 담당 범위

| 구분 | 5번 책임 | 주요 파일/API |
| --- | --- | --- |
| 관리자 첫 화면 | `/admin` 운영 대시보드, dashboard frame, 운영 요약 | `apps/web/src/features/admin/pages/AdminDashboardPage.tsx`, `GET /api/admin/dashboard`, `GET /api/admin/audit-logs/recent` |
| 관리자 공통 shell | sidebar, topbar, route title, navigation, layout slot | `apps/web/src/components/layout/AdminShell.tsx`, `features/admin/shell/**` |
| 부하 테스트 화면 | k6 smoke/load report frame, 성능 검증 계획 표시 | `apps/web/src/features/admin/pages/AdminLoadTestsPage.tsx`, `infra/k6/smoke.js` |
| 관리자 권한 | `/admin/*` guard, `ADMIN` role 확인, 401/403 분기 | `apps/web/src/features/auth/RequireAdmin.tsx`, `GET /api/auth/me` 연동 |
| Auth 공통 연동 | token 저장/전달, Authorization header, token 만료 시 공통 처리 정책 협업 | `apps/web/src/lib/api.ts`, `RequireAdmin`, Auth API 계약 리뷰 |
| 공통 UI | layout/display/feedback component contract 유지 | `apps/web/src/components/**`, `components/ui.tsx` barrel |
| 백엔드 공통 | admin/common/health, admin 권한 분기, security 연동 협업 | `apps/api/src/main/java/com/buildgraph/prototype/admin`, `common`, config/security |
| 인프라/CI | Docker Compose, OpenAPI 검증, k6 smoke, GitHub Actions | `infra`, `tools`, `.github/workflows`, `GET /api/health` |

## 5번이 맡으면 안 되는 내부 화면

| 화면 | 주 owner | 5번이 맡는 부분 |
| --- | --- | --- |
| `/admin/parts` | 2번 | AdminShell, guard, 공통 component contract |
| `/admin/price-jobs` | 2번 | AdminShell, guard, 가격 Job frame |
| `/admin/agent-sessions/:id` | 3번 | AdminShell, guard, route slot |
| `/admin/tool-invocations/:id` | 3번 | AdminShell, guard, route slot |
| `/admin/rag-evidence/:id` | 3번 | AdminShell, guard, route slot |
| `/admin/as-tickets` | 4번 | AdminShell, guard, route slot |
| `/admin/as-tickets/:ticketId` | 4번 | AdminShell, guard, route slot |
| `/login`, `/signup`, `/auth/callback`, Auth/User 백엔드 | 1번 | token 공통 전달, `RequireAdmin`, admin guard 연동만 협업 |

## 한 일

### Figma/문서 분석

- [x] Figma URL의 섹션 노드 `138:2`를 분석했다.
- [x] 5번 직접 담당 화면을 `153:1880 STATE-15 ADMIN-01 운영 대시보드 / degraded`로 판정했다.
- [x] 4번 AS 티켓 관리자 화면 `153:2011`은 AdminShell 참고 화면으로 분리했다.
- [x] 전체 섹션 캡처를 저장했다.
- [x] 5번 직접 담당 대시보드 캡처를 저장했다.
- [x] AdminShell 참고 AS 티켓 화면 캡처를 저장했다.
- [x] `docs/ROUTE_OWNERSHIP.md`에서 5번의 route/API/DB/file owner를 확인했다.
- [x] `docs/role-workspaces.md`에서 `AdminDashboardPage.tsx`와 `AdminShell`이 5번 담당임을 확인했다.
- [x] 5번 전용 2주 계획 문서를 만들었다.

### 관리자 route/guard

- [x] `apps/web/src/App.tsx`에서 `/admin` route가 `RequireAdmin`으로 감싸져 있음을 확인했다.
- [x] `/admin/agent-sessions/:id` route가 `RequireAdmin`으로 감싸져 있음을 확인했다.
- [x] `/admin/tool-invocations/:id` route가 `RequireAdmin`으로 감싸져 있음을 확인했다.
- [x] `/admin/rag-evidence/:id` route가 `RequireAdmin`으로 감싸져 있음을 확인했다.
- [x] `/admin/parts` route가 `RequireAdmin`으로 감싸져 있음을 확인했다.
- [x] `/admin/price-jobs` route가 `RequireAdmin`으로 감싸져 있음을 확인했다.
- [x] `/admin/load-tests` route가 `RequireAdmin`으로 감싸져 있음을 확인했다.
- [x] `/admin/as-tickets` route가 `RequireAdmin`으로 감싸져 있음을 확인했다.
- [x] `/admin/as-tickets/:ticketId` route가 `RequireAdmin`으로 감싸져 있음을 확인했다.
- [x] `RequireAdmin`이 localStorage의 `buildgraph.token` 존재 여부를 먼저 확인한다.
- [x] token이 있으면 `GET /api/auth/me`로 role을 확인한다.
- [x] `role === "ADMIN"`일 때만 관리자 화면을 렌더링한다.
- [x] token 없음, 401, USER role 상태에서 관리자 화면 본문을 숨긴다.
- [x] 권한 확인 중 loading 화면이 있다.
- [x] 권한 실패 시 로그인 이동/홈 이동 버튼이 있다.

### AdminShell

- [x] `AdminShell`이 sidebar, topbar, main layout을 담당한다.
- [x] `AdminShell`에 auth/permission 판단 코드가 들어가 있지 않다.
- [x] 도메인별 관리자 page 데이터를 `AdminShell`로 끌어올리지 않았다.
- [x] 현재 shell 공통 버튼은 frame 수준이며, 도메인 동작을 직접 실행하지 않는다.
- [x] AdminShell sidebar를 8개 메뉴로 세분화했다.
- [x] `Agent/RAG` 단일 메뉴를 `Agent 세션`, `Tool 이력`, `RAG 근거`로 분리했다.
- [x] `가격 Job` 메뉴와 `/admin/price-jobs` route를 추가했다.
- [x] `부하 테스트` 메뉴와 `/admin/load-tests` route를 추가했다.

### AdminDashboard/API

- [x] `AdminDashboardPage.tsx`가 5번 소유 화면임을 확인했다.
- [x] `AdminDashboardPage.tsx`가 `getAdminDashboard()` wrapper를 사용한다.
- [x] page component에서 `api()`를 직접 호출하지 않는다.
- [x] loading 상태가 있다.
- [x] error 상태가 있다.
- [x] `adminApi.ts`에 `AdminDashboard` 타입이 있다.
- [x] 백엔드 `GET /api/admin/dashboard` endpoint가 있다.
- [x] 백엔드 `GET /api/admin/audit-logs/recent` endpoint가 있다.
- [x] 백엔드 admin endpoint에서 `CurrentUserService.requireAdmin()`으로 JWT와 `ADMIN` role을 검사한다.
- [x] `AdminDashboard` DTO 정합성은 백엔드/OpenAPI 계약 기준으로 프론트를 맞추기로 결정했다.
- [x] `adminApi.ts`의 `AdminDashboard` 타입을 `agentRunning`, `openTickets`, `priceJobsRunning`, `degraded`, `generatedAt` 기준으로 수정했다.
- [x] `AdminDashboardPage.tsx` metric 표시를 실제 API 응답 필드 기준으로 수정했다.
- [x] `/admin` success 상태에서 `undefined` 값이 화면에 나오지 않도록 fallback과 테스트를 추가했다.
- [x] degraded 상태일 때 `/admin` 상단에 운영 상태 주의 alert frame을 표시하도록 수정했다.
- [x] 최근 Agent 세션은 3번 owner 상세 로직을 만들지 않고 summary/link frame으로만 표시했다.
- [x] 운영 작업 영역은 가격 Job, Mailpit, Mock Worker, k6 Smoke 요약으로 정리했다.
- [x] 관리자 할 일 table은 2번/3번/4번/5번 owner별 이동 link frame으로 정리했다.
- [x] `adminApi.ts`에 `getRecentAdminAuditLogs()` wrapper를 추가했다.
- [x] `/admin`에서 `GET /api/admin/audit-logs/recent`를 호출해 `최근 관리자 작업` 패널에 `action`, `targetType`, `targetId`, `createdAt`를 표시한다.
- [x] audit logs 조회 실패 시 전체 대시보드는 유지하고 해당 패널만 warning 상태를 표시하도록 테스트와 구현을 추가했다.

### Auth/API 공통

- [x] `apps/web/src/lib/api.ts`가 `buildgraph.token`을 읽어 `Authorization: Bearer <token>` header를 붙인다.
- [x] `saveToken`, `getToken`, `clearToken` helper가 있다.
- [x] Auth 화면 UI 세부 구현은 1번 담당으로 분리했다.
- [x] 5번은 Auth API 구현 자체가 아니라 token 저장/전달, `RequireAdmin`, admin guard, security 연동 검토를 담당한다고 정리했다.
- [x] 현재 구현된 `UserController` API skeleton 테스트를 작성했다. 테스트 파일: `apps/api/src/test/java/com/buildgraph/prototype/user/UserControllerTest.java`
- [x] `POST /api/auth/login` skeleton response shape를 테스트했다.
- [x] `POST /api/users` 신규/기존 사용자 response shape를 테스트했다.
- [x] `GET /api/auth/me` JWT 응답과 token 없음 401 `ErrorResponse`를 테스트했다.
- [x] 로그인/회원가입 점검 결과, 현재 구현은 seed 사용자 비밀번호 검증과 JWT access token 발급/검증 흐름을 사용한다.
- [x] `POST /api/auth/login`은 password hash를 검증하고 JWT access token을 반환한다.
- [x] `POST /api/users`는 입력 password를 hash로 저장한다.
- [x] `GET /api/auth/me`는 JWT를 검증하고 subject의 `users.public_id`로 현재 사용자를 조회한다.
- [x] `/login`, `/signup` 화면은 실제 입력값을 읽어 Auth API를 호출한다.
- [x] password hashing, login password verification, JWT 발급/검증, refresh token hash 저장/회전, logout revoke, validation error shape, duplicate email 409 처리는 1번 Auth/User 백엔드 작업으로 이관했다.
- [x] OpenAPI에 있는 `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/auth/google/start`, `GET /api/auth/google/callback`, `POST /api/auth/exchange` 구현은 1번에게 이관했다.
- [x] 5번은 1번 Auth 구현 후 `api.ts`, `RequireAdmin`, admin guard, API 계약 충돌 여부를 검토한다.
- [x] `AdminController` 권한/응답 계약 테스트를 작성했다. 테스트 파일: `apps/api/src/test/java/com/buildgraph/prototype/admin/AdminControllerTest.java`
- [x] `GET /api/admin/dashboard`의 token 없음 401, USER token 403, ADMIN token 200 응답을 테스트했다.
- [x] `GET /api/admin/audit-logs/recent`의 token 없음 401, USER token 403, ADMIN token 200 응답을 테스트했다.
- [x] admin API 401/403 실패 응답이 공통 `ErrorResponse` shape와 맞는지 테스트했다.
- [x] 프론트 `RequireAdmin`과 백엔드 admin API의 401/403 권한 분기 테스트를 다시 실행해 통과를 확인했다.
- [x] `HealthController` 성공 응답 테스트를 작성했다. 테스트 파일: `apps/api/src/test/java/com/buildgraph/prototype/common/HealthControllerTest.java`
- [x] `GET /api/health`가 DB probe 성공 시 200, `status: "UP"`, `database: "UP"`를 반환하는지 테스트했다.
- [x] DB 연결 실패 시 `/api/health`가 503, `{ "status": "DOWN" }`를 반환하는 정책을 반영했다.
- [x] DB 연결 실패 health 정책을 `docs/API_CONTRACT.md`와 `docs/openapi.yaml`에 반영했다.

### 인프라/검증 문서화

- [x] `compose.yaml`에 postgres, redis, rabbitmq, mailpit, api, web 서비스가 있음을 기존 체크리스트에서 확인했다.
- [x] `docker compose config`가 기존 확인에서 정상 출력되었음을 문서화했다.
- [x] OpenAPI 검증은 `PYTHONPATH=.venv/lib/python3.11/site-packages python3 tools/validate_openapi.py` 방식이 필요할 수 있음을 문서화했다.
- [x] k6 smoke skeleton이 `/api/health`, `/api/builds/recommend`, `/api/parts`를 확인하는 수준임을 정리했다.
- [x] Postgres Docker init SQL과 Flyway `V1__extensions.sql`이 모두 `vector`, `pgcrypto` extension을 생성하던 중복 원인을 확인했다.
- [x] `infra/docker/postgres/01-init.sql`을 제거하고 extension 생성 책임을 Flyway `V1__extensions.sql`로 단일화했다.
- [x] `docker compose config`로 Postgres Dockerfile 변경 후 compose 설정이 유효함을 다시 확인했다.
- [x] `docker compose up --build` 실행 후 `web`, `api`, `postgres`, `redis`, `rabbitmq`, `mailpit` 컨테이너가 모두 `Up` 상태임을 확인했다.
- [x] `buildgraph-web`에서 Vite dev server가 `0.0.0.0:5173`으로 기동되는 것을 확인했다.
- [x] `buildgraph-api`에서 Spring Boot, DB 연결, Flyway migration validation이 정상 완료되는 것을 확인했다.
- [x] `http://localhost:8080/api/health`와 `http://localhost:5173/api/health`가 모두 `{"status":"UP","database":"UP"}`를 반환함을 확인했다.
- [x] 관리자 화면의 브라우저 로딩 원인이 Docker/API hang이 아니라 브라우저 localStorage의 오래된 token 불일치일 수 있음을 확인했다.
- [x] 백엔드 admin 인증 기준은 `admin@example.com` 로그인으로 발급받은 JWT access token이며, legacy 문자열 token은 401을 반환함을 확인했다.
- [x] 관리자 화면 수동 확인 시 로그인 화면에서 `admin@example.com / passw0rd!`로 로그인한 뒤 `/admin`에 접근해야 함을 정리했다.

## 해야 할 일

### 1. 테스트 먼저 작성

#### Frontend guard 테스트

- [x] `RequireAdmin` 테스트를 먼저 작성한다. 기존 테스트 파일: `apps/web/tests/admin-guard.spec.ts`
- [x] token이 없으면 `auth/me`를 호출하지 않고 로그인 필요 상태를 보여주는지 테스트한다.
- [x] `auth/me`가 `role: "ADMIN"`을 반환하면 children을 렌더링하는지 테스트한다.
- [x] `auth/me`가 `role: "USER"`를 반환하면 관리자 권한 없음 상태를 보여주는지 테스트한다.
- [x] `auth/me`가 401을 반환하면 로그인 필요 상태를 보여주는지 테스트한다.
- [x] `auth/me`가 403을 반환하면 관리자 권한 없음 상태를 보여주는지 테스트한다.
- [x] `/admin/*` 주요 route가 token 없는 상태에서 guard 화면을 보여주는지 테스트한다.
- [x] `AdminDashboardPage` loading/error/success 상태 테스트를 작성한다.
- [x] `/admin`이 ADMIN role일 때 `/api/admin/dashboard` 응답을 화면에 표시하는지 테스트한다.
- [x] `getAdminDashboard()` 경로가 `/api/admin/dashboard`를 호출하는지 Playwright 통합 테스트에서 검증한다.
- [x] `api.ts`가 token이 있을 때 Authorization header를 붙이는지 Playwright 통합 테스트에서 검증한다.

#### Backend 테스트

`RequireAdmin`은 React 컴포넌트라 `admin-guard.spec.ts`가 맞다. 로그인/회원가입 화면 UI와 form state는 1번 작업이다.

로그인/회원가입 프론트/백엔드는 1번에게 이관했다. 아래 `UserControllerTest` 관련 항목은 이관 전 skeleton 현황 확인 기록이며, 완성 Auth 테스트는 1번 작업이다.

- [x] `apps/api/src/test/java/com/buildgraph/prototype/admin/AdminControllerTest.java`를 만든다.
- [x] admin token이 없으면 `GET /api/admin/dashboard`가 401을 반환하는지 테스트한다.
- [x] USER token이면 `GET /api/admin/dashboard`가 403을 반환하는지 테스트한다.
- [x] ADMIN token이면 `GET /api/admin/dashboard`가 200과 `AdminDashboardDto` 필드를 반환하는지 테스트한다.
- [x] `GET /api/admin/audit-logs/recent`도 ADMIN 권한에서만 조회되는지 테스트한다.
- [x] admin API 실패 응답이 공통 `ErrorResponse`와 맞는지 테스트한다.
- [x] `apps/api/src/test/java/com/buildgraph/prototype/common/HealthControllerTest.java`를 만든다.
- [x] `GET /api/health`가 200과 `{ "status": "UP" }` 형태를 반환하는지 테스트한다.
- [x] DB 연결 실패 시 health 응답 정책을 확인한다. 정책: `503 Service Unavailable` + `{ "status": "DOWN" }`
- [x] 이관 전 현재 구현된 Auth API skeleton 확인용으로 `apps/api/src/test/java/com/buildgraph/prototype/user/UserControllerTest.java`를 만들었다.
- [x] 현재 API skeleton 기준으로만 `POST /api/users`, `POST /api/auth/login`, `GET /api/auth/me`의 status와 response shape를 테스트한다.
- [x] 비밀번호 검증, refresh token 회전, logout invalidation, OAuth 실제 교환 테스트는 1번 Auth/User 완성 작업으로 이관했다.
- [x] `POST /api/auth/refresh`, `POST /api/auth/logout`, Google OAuth start/callback/exchange는 1번 구현 PR에서 테스트해야 한다.
- [x] 로그인/회원가입 화면 UI, form state, validation 문구 테스트는 1번 작업으로 분리한다.

### 2. AdminDashboard DTO 정합성 해결

- [x] 현재 프론트 타입 `llmQueueP95`, `apiP95`, `asOpen`, `recommendationSuccess`와 OpenAPI/백엔드 타입 `agentRunning`, `openTickets`, `priceJobsRunning`, `degraded`, `generatedAt` 중 어느 쪽으로 맞출지 결정한다. 결정: 백엔드/OpenAPI 계약 기준으로 프론트를 맞춘다.
- [x] 결정 전에 API 계약 변경 여부를 확인한다.
- [x] API 계약을 바꾸면 `docs/API_CONTRACT.md`와 `docs/openapi.yaml`을 같은 변경에 포함한다. 이번 작업은 API 계약 변경 없이 프론트만 계약에 맞췄다.
- [x] 프론트를 맞추는 경우 `adminApi.ts`의 `AdminDashboard` 타입을 `AdminDashboardDto`에 맞춘다.
- [x] `AdminDashboardPage.tsx`의 metric 표시를 실제 응답 필드 기준으로 고친다.
- [x] success 상태에서 `undefined` 값이 화면에 나오지 않게 한다.

### 3. Figma 기준 AdminDashboard 보강

- [x] `153:1880` 기준으로 degraded alert frame을 추가한다.
- [x] metric card 4개를 실제 API/계약에 맞게 다시 배치한다.
- [x] `최근 Agent 세션` 영역은 3번 데이터와 경계를 확인한 뒤 summary 수준으로만 둔다.
- [x] `운영 작업` 영역은 부품 가격 수집 작업, 메일 발송 확인, Mock Worker, k6 Smoke 리포트 중 어떤 값을 5번이 표시할지 결정한다.
- [x] `관리자 할 일` table은 각 도메인 owner 데이터가 필요한지 확인한다.
- [x] 5번 단독으로 만들 수 없는 도메인 데이터는 mock summary 또는 link frame으로만 둔다.

### 4. AdminShell navigation 정리

- [x] 현재 4개 nav 항목을 owner 1/2/3/4 작업 목록과 실제 `/admin/*` route 기준으로 분석했다.
- [x] 최초 분석 결론은 6개 active nav + 2개 보류였으나, 사용자 요청에 따라 8개 항목을 모두 active nav로 만들기로 변경했다.
- [x] `대시보드`는 `/admin`으로 연결한다.
- [x] `Agent 세션`은 3번 owner route인 `/admin/agent-sessions/:id`로 연결한다.
- [x] `Tool 이력`은 3번 owner route인 `/admin/tool-invocations/:id`로 연결한다.
- [x] `RAG 근거`는 3번 owner route인 `/admin/rag-evidence/:id`로 연결한다.
- [x] `부품/가격`은 2번 owner route인 `/admin/parts`로 연결한다.
- [x] `AS 티켓`은 4번 owner route인 `/admin/as-tickets`로 연결한다.
- [x] `가격 Job`은 2번 owner의 별도 route `/admin/price-jobs`로 연결한다.
- [x] `부하 테스트`는 5번 인프라 route `/admin/load-tests`로 연결한다.
- [ ] nav label/order 변경은 각 owner와 공유한다.

AdminShell nav 분석 결과:

| 메뉴 후보 | route | 주 owner | 판단 |
| --- | --- | --- | --- |
| 대시보드 | `/admin` | 5번 | active nav |
| Agent 세션 | `/admin/agent-sessions/:id` | 3번 | active nav |
| Tool 이력 | `/admin/tool-invocations/:id` | 3번 | active nav |
| RAG 근거 | `/admin/rag-evidence/:id` | 3번 | active nav |
| 부품/가격 | `/admin/parts` | 2번 | active nav |
| AS 티켓 | `/admin/as-tickets` | 4번 | active nav |
| 가격 Job | `/admin/price-jobs` | 2번 | active nav |
| 부하 테스트 | `/admin/load-tests` | 5번/팀 공통 | active nav |

1번 작업인 로그인/회원가입/견적 흐름은 관리자 내부 메뉴로 넣지 않는다. 1번 Auth 구현은 `RequireAdmin`과 token 정책에만 영향을 준다.

### 5. AdminShell topbar 정리

- [x] Figma처럼 admin search input을 둘지 결정한다. 결정: Sprint 1에서는 검색 input을 두지 않는다.
- [x] 검색 범위가 세션/티켓/부품 전체라면 각 owner API와 충돌하는지 확인한다. 판단: Agent/RAG는 3번, 티켓은 4번, 부품/가격은 2번 owner API가 필요하므로 5번이 전역 검색을 임의 구현하지 않는다.
- [x] 검색 동작이 미확정이면 placeholder UI만 둔다. 결정: placeholder input도 두지 않고 topbar action frame만 유지한다.
- [x] `내보내기` 버튼의 실제 export 범위를 결정한다. 결정: Sprint 1에서는 export 범위 미확정이므로 실제 동작을 연결하지 않는다.
- [x] `작업 실행` 버튼이 어떤 job을 실행하는지 결정한다. 결정: Sprint 1에서는 실행 job 미확정이므로 실제 동작을 연결하지 않는다.
- [x] 동작이 미확정이면 버튼을 disabled 또는 no-op frame으로 둔다. 구현: `AdminShell`의 `내보내기`, `작업 실행` 버튼을 disabled placeholder로 둔다.

### 6. Auth 협업/관리자 권한 고도화

- [x] `POST /api/users`, `POST /api/auth/login`, `GET /api/auth/me`, refresh/logout/OAuth 구현은 1번에게 이관한다.
- [x] password hashing, login password verification, JWT 발급/검증, refresh token hash 저장/회전/폐기, duplicate email 409, Auth validation error 구현은 1번에게 이관한다.
- [x] 1번 Auth API 구현 후 `apps/web/src/lib/api.ts`의 Authorization header가 실제 JWT access token 전달과 충돌 없는지 확인했다.
- [x] 1번 Auth API 구현 후 `RequireAdmin`이 실제 JWT/role 기반 `/api/auth/me` 응답과 맞는지 확인했다.
- [ ] refresh token 저장/재발급/로그아웃 호출을 `api.ts`와 header logout 흐름에 연결한다.
- [ ] `ApiError`가 Auth validation/duplicate/refresh 실패 응답의 `code`, `message`, `details`를 보존하게 한다.
- [ ] Spring Security filter 도입 시 admin API 권한 분기를 현재 `CurrentUserService` 기반 JWT 검사에서 filter 기반 정책으로 전환할지 결정한다.
- [ ] Auth API 계약 변경 시 `docs/API_CONTRACT.md`와 `docs/openapi.yaml`의 오류 응답/예시를 1번 변경과 같이 검토한다.
- [x] 401과 403 메시지를 더 명확히 분리한다. 프론트는 로그인 필요/관리자 권한 없음 메시지를 분리하고, 백엔드는 `UNAUTHORIZED`/`FORBIDDEN` 응답을 분리한다.
- [x] token이 만료되었을 때 `clearToken()`을 호출할지 결정한다. 결정: protected API에서 refresh 재시도까지 실패하거나 refresh가 없는 상태의 401이면 access token을 정리한다.
- [x] refresh retry를 `api.ts` 공통에 둘지, 1번 Auth flow 내부에 둘지 결정한다. 결정: refresh endpoint/응답 정책은 1번이 구현하고, 공통 재시도 wrapper는 `api.ts`에 최대 1회만 둔다.
- [x] `/api/auth/logout` 호출 후 프론트 token 정리 흐름을 정한다. 결정: 1번이 logout API를 호출하고, 성공 또는 강제 로그아웃 시 5번 helper인 `clearToken()`으로 로컬 token을 정리한다.
- [x] OAuth callback/exchange는 1번 구현 범위이며, 5번은 token 저장/전달 연동만 검토한다.
- [x] AdminController 인증 검사 방식에서 Spring Security JWT filter로 넘어갈 위치를 문서화한다. 전환 위치는 `config/security`/JWT filter, `GET /api/auth/me`, `RequireAdmin`, `apps/web/src/lib/api.ts` 연동 지점이다.

#### Common API Client 정책 결정

| 항목 | 결정 | 후속 조건 |
| --- | --- | --- |
| token 저장 key | 현재 `buildgraph.token` 유지 | 1번이 refresh token 저장 위치를 별도 제안하면 XSS/보안 정책 검토 |
| Authorization header | `api.ts`가 access token을 `Authorization: Bearer <token>`으로 자동 첨부 | owner별 API wrapper는 page에서 직접 `api()`를 호출하지 않는 규칙 유지 |
| 401 처리 | refresh 불가 또는 refresh 실패 시 `clearToken()` 호출 후 로그인 필요 상태로 전환 | refresh endpoint가 구현되었으므로 `api.ts` 구현 고도화 대상 |
| refresh retry | `api.ts` 공통 wrapper에서 요청당 최대 1회만 허용 | `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`, `/api/auth/exchange`는 retry 제외 |
| logout | 1번 Auth flow가 logout API를 호출하고, 프론트 token 정리는 `clearToken()` 사용 | 현재 header logout은 로컬 token만 삭제하므로 서버 refresh token 폐기 호출 연결 필요 |
| error normalization | `ApiError`가 backend `ErrorResponse`의 `code`, `message`, `details`, `status`를 보존하는 방향 | 공통 `ErrorResponse` 세부 field가 변경되면 `API_CONTRACT.md`/`openapi.yaml` 동시 수정 |
| admin guard | `RequireAdmin`은 `getToken()` 확인 후 `/api/auth/me`의 `role`을 기준으로 판단 | security chain 전환 검토 |

### 7. Backend/Admin/Health

- [x] `GET /api/admin/dashboard` 응답을 OpenAPI와 정확히 맞춘다.
- [x] `GET /api/admin/audit-logs/recent` 응답 shape를 화면에서 쓸 수 있게 정리한다. 백엔드 응답 shape, 권한 테스트, 프론트 `최근 관리자 작업` 표시 연결을 완료했다.
- [x] `GET /api/health`가 DB 연결까지 확인하는지 runtime smoke로 검증한다. 확인: 2026-06-29 로컬 `/api/health` 요청에서 `{"status":"UP","database":"UP"}` 응답을 확인했다.
- [x] `admin_audit_logs` seed와 화면 표시 항목을 연결할지 결정한다. 결정: 백엔드 `GET /api/admin/audit-logs/recent` shape와 seed 연결은 완료, 프론트 표시 연결은 P1로 분리한다.
- [x] admin endpoint 401/403 response가 공통 `ErrorResponse`와 일치하는지 확인한다.

### 8. Infra/CI/k6

- [x] Postgres Docker init SQL 주입을 제거해 Flyway schema history 충돌 원인을 없앤다.
- [x] Redis를 Sprint 1에서 연결 확인만 할지 실제 기능에 쓸지 결정한다. 결정: Sprint 1은 연결 확인과 사용 정책 문서화까지만 진행하고, 실제 사용은 OAuth/cache/quota/job state 구현 PR에서 연동한다.
- [x] RabbitMQ를 AI 견적 추천 실행 작업, 부품 가격 수집 작업, 메일 발송 작업 중 어디까지 검증할지 결정한다. 결정: Sprint 1은 기동/관리 화면/connection smoke와 작업 대기열 이름 초안까지만 진행하고, 실제 작업 등록/처리는 3번 AI 견적 추천 실행 작업 또는 2번 부품 가격 수집 작업 구현 PR에서 연동한다.
- [x] Mailpit은 실행 확인만 할지 실제 메일 발송까지 할지 결정한다. 결정: Sprint 1은 UI/SMTP 확인과 test mail smoke 방식 정리까지 진행하고, 실제 가격 알림 메일은 2번 가격 알림 메일 구현 PR에서 연동한다.

#### Redis Sprint 1 작업

- [x] `compose.yaml`에 `redis:7-alpine` 서비스와 `6379:6379` 포트가 있음을 확인했다.
- [x] `apps/api/src/main/resources/application.yml`에서 `spring.data.redis.host`, `port` 설정이 있음을 확인했다.
- [x] `apps/api/build.gradle`에 `spring-boot-starter-data-redis` 의존성이 있음을 확인했다.
- [x] `docker compose up --build` 기준 `buildgraph-redis` 컨테이너가 `Up` 상태임을 확인했다.
- [x] Redis runtime smoke 명령을 실행하고 결과를 기록한다. 결과: `docker exec buildgraph-redis redis-cli ping`이 `PONG`을 반환했다.
- [x] Redis 사용 후보를 문서화한다: OAuth one-time code, LLM/RAG cache, quota counter, 임시 job state.
- [x] Sprint 1에서는 Redis key schema를 임의 확정하지 않는다고 기록한다.
- [ ] 1번이 OAuth one-time code를 구현하면 TTL, key prefix, single-use 삭제 정책을 같이 검토한다.
- [ ] 3번이 LLM/RAG cache 또는 quota를 구현하면 TTL, eviction, 비용 제한 기준을 같이 검토한다.

#### RabbitMQ Sprint 1 작업

- [x] `compose.yaml`에 `rabbitmq:3-management` 서비스와 `5672`, `15672` 포트가 있음을 확인했다.
- [x] `apps/api/src/main/resources/application.yml`에서 `spring.rabbitmq.host`, `username`, `password` 설정이 있음을 확인했다.
- [x] `apps/api/build.gradle`에 `spring-boot-starter-amqp` 의존성이 있음을 확인했다.
- [x] `docker compose up --build` 기준 `buildgraph-rabbitmq` 컨테이너가 `Up` 상태임을 확인했다.
- [x] RabbitMQ 관리 화면 접속을 확인한다. 주소: `http://localhost:15672`, 계정: `buildgraph` / `buildgraph`. 확인: management API `/api/overview`가 200으로 응답했다.
- [x] RabbitMQ connection smoke 방식을 정한다. 결정: Sprint 1은 management API 확인과 `/api/queues` 조회로 끝내고 테스트용 작업 대기열 선언은 하지 않는다.
- [x] 작업 대기열 이름 초안을 문서화한다. 초안: `agent.jobs`, `price.jobs`, `mail.jobs`.
- [x] Sprint 1에서는 실제 작업 처리 worker를 임의 구현하지 않는다고 기록한다.
- [x] 현재 RabbitMQ queue가 없는 상태를 확인했다. 결과: `/api/queues` 응답 `[]`.
- [ ] 3번이 AI 견적 추천 실행 작업을 구현하면 추천 상태 전이 `QUEUED(대기) -> RUNNING(처리 중) -> RAG_SEARCHED(근거 검색 완료) -> TOOLS_CALLED(검증 완료) -> SUMMARY_READY(요약 완료) -> SUCCEEDED(완료)`와 `FALLBACK_READY`, `FAILED`, `CANCELLED` 처리 기준을 같이 검토한다.
- [ ] 3번이 AI 견적 추천 실행 작업을 RabbitMQ에 연결하면 `agent.jobs` 작업 등록, 작업 처리기 성공 확인(ack), 일시 실패 재시도, 최종 실패 기록 정책을 같이 검토한다.
- [ ] 2번이 부품 가격 수집 작업을 구현하면 `POST /api/admin/price-jobs/run` 이후 `price_jobs` 생성, `price.jobs` 작업 등록, 이미 실행 중인 가격 수집 중복 요청 409, 작업 처리기 실패 시 `FAILED`와 `error_summary` 기록 정책을 같이 검토한다.
- [ ] mail job이 필요해지면 가격 알림 메일과 연결할지, RabbitMQ 없이 동기 smoke로 둘지 결정한다.

#### Mailpit Sprint 1 작업

- [x] `compose.yaml`에 `mailpit` 서비스와 SMTP `1025`, UI `8025` 포트가 있음을 확인했다.
- [x] `apps/api/src/main/resources/application.yml`에서 `spring.mail.host`, `port` 설정이 있음을 확인했다.
- [x] `apps/api/build.gradle`에 `spring-boot-starter-mail` 의존성이 있음을 확인했다.
- [x] `docker compose up --build` 기준 `buildgraph-mailpit` 컨테이너가 `Up` 상태임을 확인했다.
- [x] Mailpit UI 접속을 확인한다. 주소: `http://localhost:8025`. 확인: HTML title `Mailpit` 응답.
- [x] Mailpit API 접속을 확인했다. 결과: `/api/v1/messages`가 200으로 응답하고 현재 message count는 0이다.
- [x] SMTP smoke 방식을 정한다. 결정: Sprint 1은 SMTP 포트 연결 확인까지만 수행하고, 실제 test mail 발송은 가격 알림 메일 구현 전까지 보류한다.
- [x] SMTP 포트 연결을 확인했다. 결과: `nc -z localhost 1025` 성공.
- [x] Sprint 1에서는 실제 사용자 메일 발송 로직을 5번이 임의 구현하지 않는다고 기록한다.
- [ ] 2번이 가격 알림 메일을 구현하면 Mailpit으로 목표가 알림 메일 수신 여부를 확인한다.
- [ ] 1번이 회원가입 인증 메일을 요구하면 Auth owner와 API 계약을 먼저 확정한다.
- [x] `docker compose up --build`로 전체 실행을 확인한다. 확인 범위: 컨테이너 기동, web/API 포트 응답, `/api/health`, Vite proxy health, 관리자 JWT 응답
- [x] k6 smoke와 실제 부하 테스트 시나리오를 분리한다. 결정: Sprint 1 k6는 `infra/k6/smoke.js`와 `docs/reports/k6-smoke-report-template.md` 기준의 smoke 결과 기록으로 두고, 300명/1000명 부하는 별도 확장 작업으로 분리한다.
- [x] 300명/1000명 목표가 이번 Sprint인지 이후 Sprint인지 확정한다. 결정: 300명은 2주차, 1,000명은 4주차 목표로 둔다.
- [x] 성능 리포트 템플릿을 만든다. 파일: `docs/reports/k6-smoke-report-template.md`

### 9. PR 전 검증

- [x] `npm --prefix apps/web run build` 통과. 2026-06-29 재검증 완료.
- [x] `npm --prefix apps/web run test` 통과. 2026-06-29 재검증: Playwright 41개 통과.
- [ ] `python tools/validate_openapi.py`. 현재 로컬 shell에서 `python` 명령이 없어 실패하므로 `python3` 또는 `.venv` 방식 사용 필요.
- [x] 필요 시 `PYTHONPATH=.venv/lib/python3.11/site-packages python3 tools/validate_openapi.py` 통과. 2026-06-29 재검증: 42 paths.
- [x] `docker compose config` 통과. 2026-06-29 재검증 완료.
- [x] 백엔드 테스트 변경 검증으로 `cd apps/api && ./gradlew test --no-daemon`을 실행한다. 2026-06-29 재검증: `BUILD SUCCESSFUL`.
- [x] 백엔드 운영 빌드 변경 시 `cd apps/api && ./gradlew bootJar --no-daemon`. 2026-06-29 재검증: `BUILD SUCCESSFUL`.
- [x] 인프라 변경 시 `docker compose up --build`. 2026-06-29 확인: compose 서비스 전체 `Up`, API health 200, Vite proxy health 200
- [x] 검증 결과를 PR 설명에 붙인다. 아래 `PR 설명용 검증 결과 요약` 블록을 사용한다.

#### PR 설명용 검증 결과 요약

```text
검증일: 2026-06-29

- Frontend build: npm --prefix apps/web run build 통과
- Frontend test: npm --prefix apps/web run test 통과, Playwright 41개 통과
- Backend test: cd apps/api && ./gradlew test --no-daemon 통과, BUILD SUCCESSFUL
- Backend bootJar: cd apps/api && ./gradlew bootJar --no-daemon 통과, BUILD SUCCESSFUL
- OpenAPI: PYTHONPATH=.venv/lib/python3.11/site-packages python3 tools/validate_openapi.py 통과, 42 paths
- Docker Compose config: docker compose config 통과
- Docker runtime smoke: docker compose up --build 기준 web/api/postgres/redis/rabbitmq/mailpit Up
- Health smoke: API /api/health 200, Vite proxy health 200
- Redis smoke: buildgraph-redis redis-cli ping -> PONG
- RabbitMQ smoke: management API /api/overview 200, /api/queues -> []
- Mailpit smoke: UI/API 200, SMTP 1025 연결 성공

주의:
- 로컬 shell에 python 명령이 없어 python tools/validate_openapi.py는 실패한다.
- OpenAPI 검증은 python3 또는 위 PYTHONPATH 명령을 사용한다.
```

#### origin/main 병합 충돌 처리 기록

- [x] `origin/main`을 `juhoseok` 브랜치에 merge하는 과정에서 `README.md`, `apps/web/src/features/admin/adminApi.ts`, `apps/web/src/features/admin/pages/AdminPartsPage.tsx`, `docs/ROUTE_OWNERSHIP.md` 충돌을 해결했다.
- [x] `adminApi.ts`는 5번의 `AdminAuditLog`/`getRecentAdminAuditLogs()`와 3번의 Agent/RAG/Tool 상세 타입을 모두 유지했다.
- [x] `AdminPartsPage.tsx`는 부품 DB 조회와 가격 데이터 기준 안내만 유지하고, 가격 Job 실행은 별도 `/admin/price-jobs` 화면으로 분리하는 기준을 유지했다.
- [x] `README.md`와 `ROUTE_OWNERSHIP.md`는 Auth/User 구현 owner 1번 기준과 2번의 parts/catalog refresh 확장 기준을 함께 반영했다.
- [x] 충돌 marker 제거 후 `npm --prefix apps/web run build`와 `git diff --check`가 통과했다.

#### Home UI 커밋 메시지 요청 기록

- [x] 2026-06-30 기준 홈 화면 command center 개편, 공통 header/nav/screen 반응형 조정, body 최소 폭 제거, 홈 Playwright 테스트 추가 범위를 확인했다.
- [x] 커밋 메시지에는 사용자 홈 화면 경험과 모바일 overflow 방지 검증을 함께 반영한다.
- [x] 2026-06-30 추가 변경 기준 홈 화면 자연어 입력, 추천 모드 감지, 세션 저장, follow-up 상담, draggable assistant bar, 로컬 추천 카드 갱신 테스트 범위를 확인했다.
- [x] 커밋 메시지에는 백엔드 추천 완성이 아니라 프론트 로컬 상담 시뮬레이션 흐름임을 명확히 반영한다.
- [x] 2026-06-30 홈 챗봇 UX 개선 기준 assistant 답변 영역, 용도/해상도 칩 위저드, 추천 컴퓨터 카드 동시 갱신, 직접 입력 보조 흐름을 확인했다.
- [x] 검증 결과: `./node_modules/.bin/tsc -b`, `npm --prefix apps/web run test -- home.spec.ts`, `npm --prefix apps/web run build` 통과.
- [x] 2026-06-30 추가 UI 변경 기준 홈 첫 화면 추천 견적/인기 부품 랭킹, 셀프 견적 쇼핑 workspace, 공통 commerce 색상/패널/테이블/헤더 스타일, 모바일 셀프 견적 테스트 범위를 확인했다.
- [x] 2026-06-30 추가 변경 기준 홈 화면의 빠른 쇼핑/상담 요약 보조 섹션 숨김과 Vite 개발 서버 API 프록시 대상 환경변수 분리 범위를 확인했다.
- [x] 2026-06-30 `origin/main` 최신 변경을 `feat/improve-home-ui`에 병합하고, 숨긴 홈 보조 섹션 기준으로 Playwright 기대값을 정리했다. 검증: web build, web test 61개, OpenAPI 49 paths, backend test, backend bootJar, docker compose config 통과.
- [x] 2026-06-30 구매 컨설팅 개선 기준 `POST /api/ai/build-chat`, `PUT /api/quote-drafts/current/apply-ai-build`, 홈 AI 추천상품 탭, 챗봇 대화 세션, 셀프 견적 batch 적용, 구매 컨설팅 아이콘 자산, 관련 backend/frontend 테스트와 OpenAPI 계약 변경 범위를 확인했다.
- [x] 2026-06-30 구매 상담 추천의 toolReady 부품 조회 SQL에서 `trueORDER BY`가 붙지 않도록 공백을 보정하고, 예산 추천 호출 시 SQL 조합 공백 회귀 테스트가 추가된 범위를 확인했다.

#### 2026-06-30 공동계약서 위반 감사 기록

- [x] 현재 브랜치 `feat/aiChatImprove`가 `main` 대비 21개 파일을 변경하는 것을 확인했다. 주요 변경은 `POST /api/ai/build-chat`, `PUT /api/quote-drafts/current/apply-ai-build`, 홈 AI 추천 UI, 셀프 견적 batch 적용, 구매 컨설팅 아이콘 자산이다.
- [x] 계약 문서 동기화 여부를 확인했다. `docs/API_CONTRACT.md`, `docs/ROUTE_OWNERSHIP.md`, `docs/openapi.yaml`에는 새 API와 route owner 변경이 반영되어 있다.
- [x] 공동계약서 위반 가능성이 있는 항목을 구분했다.
  - [ ] 5번 단독 PR이라면 owner 범위 위반 가능성이 있다. 이 브랜치는 1번 owner 영역인 `build`, `features/quote`, 홈 화면과 2번 owner 영역인 `quote`, `features/parts`, 셀프 견적 화면을 직접 수정한다. 1번/2번 작업 또는 명시적 리뷰가 있으면 허용 가능하고, 5번 단독 작업이면 계약 위반이다.
  - [ ] 새 API 2개가 `docs/openapi.yaml`에는 들어갔지만 `tools/validate_openapi.py`의 `REQUIRED_PATHS`와 request schema 검사 목록에는 포함되지 않았다. CI의 OpenAPI 검증이 새 API 누락을 잡지 못하므로 검증 계약 보강이 필요하다.
  - [ ] `HomePage.tsx` 안에 `featuredBuilds`, `popularPartDeals` 같은 domain mock/static 데이터가 직접 들어 있다. 유지하려면 "홈 마케팅용 정적 데이터"로 합의해야 하고, mock 데이터라면 `features/quote/mocks`로 옮기는 것이 계약에 맞다.
- [x] 위반으로 보지 않는 항목을 확인했다.
  - [x] 페이지 컴포넌트가 공통 `api()`를 직접 호출하지 않고 `quoteApi.ts`, `partsApi.ts` wrapper를 사용한다.
  - [x] `POST /api/ai/build-chat`는 이후 3번 owner의 LLM/RAG 필수 AI 엔진 API로 변경되었다. 홈 UI는 1번과 협업하고, Tool 계산은 2번 구현을 호출/참조한다.
  - [x] `PUT /api/quote-drafts/current/apply-ai-build`는 `conflictPolicy=REPLACE`와 transaction 적용 흐름을 갖고 있어 새 계약 방향과 대체로 맞다.
  - [x] 구매 컨설팅 아이콘 자산은 `docs/hosoek/assets` 문서 자산이므로 route/API/DB owner 계약 위반은 아니다.
- [x] 검증 결과를 기록했다.
  - [x] `git diff --check main...HEAD` 통과.
  - [x] `cd apps/api && ./gradlew test --no-daemon` 통과.
  - [x] `cd apps/web && npm run test` 통과. Playwright 60개 통과.
  - [x] Ruby YAML 기반 OpenAPI 보조 검증 통과. 51 paths 확인.
  - [ ] 공식 `python tools/validate_openapi.py`는 로컬 `python` 명령 없음, `python3` PyYAML 없음, system pip 외부 관리 환경 제한 때문에 직접 실행하지 못했다. CI 또는 `.venv`에서 공식 스크립트 재확인이 필요하다.

#### 2026-07-01 커밋 메시지 요청 전 점검 기록

- [x] `origin/main` 병합 후 현재 브랜치가 `feat/aiChatImprove`이고, 마지막 커밋이 `fix: 로그인 후 헤더 사용자 표시를 유지`임을 확인했다.
- [x] 현재 unstaged 변경은 `docs/hosoek/owner5-work-analysis-checklist.md` 1개뿐이며, 코드/API/OpenAPI 추가 변경은 없다.
- [x] 이번 커밋 메시지는 구매 상담 기능 구현이 아니라 공동계약서 감사와 커밋 메시지 요청 전 점검 기록을 남기는 문서 커밋으로 분리한다.

#### 2026-07-01 1번/4번 완료도 재감사 기록

결론: **1번과 4번은 모두 부분완료다.** 현재 테스트와 OpenAPI 검증은 통과하지만, 계약서 기준의 소유권, OAuth, 파일 검증, 관리자 화면/API 상태 전이까지 완전히 닫히지는 않았다.

- [x] 검증 실행 결과
  - [x] `cd apps/web && npm run test` 통과. Playwright 70개 통과.
  - [x] `cd apps/api && ./gradlew test --no-daemon` 통과. `BUILD SUCCESSFUL`.
  - [x] `/private/tmp/buildgraph-openapi-check/bin/python tools/validate_openapi.py` 통과. 52 paths.
- [x] 1번 완료된 부분 확인
  - [x] `POST /api/users`, `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `GET /api/auth/me` 구현과 테스트가 있다.
  - [x] 로그인/회원가입 화면은 API payload, 오류 표시, token 저장, logout 흐름 테스트가 있다.
  - [x] 요구사항 분석, 추천, build 상세, 부품 변경 API 골격은 있다.
- [ ] 1번 미완료/확인 필요
  - [ ] `GET /api/auth/google/start`, `GET /api/auth/google/callback`, `POST /api/auth/exchange`는 OpenAPI/계약에는 있으나 현재 `UserController`에 구현되어 있지 않다.
  - [ ] `BuildQueryService.parse()`가 현재 로그인 사용자 id가 아니라 `user@example.com` 고정 user_id로 requirement를 저장한다.
  - [ ] `GET /api/builds/history`, `GET /api/builds/{id}`, `POST /api/builds/{id}/change-part`는 현재 사용자 소유권 404를 보장하지 않는다.
  - [ ] `/my/quotes`는 `quoteMock` 기반 정적 화면이고 `getBuildHistory()`를 호출하지 않는다.
- [x] 4번 완료된 부분 확인
  - [x] PC Agent CLI sample/export가 있다.
  - [x] `/support/new`는 파일 선택, 동의, `POST /api/agent-logs/upload`, `POST /api/as-tickets`, 상세 이동 흐름을 갖고 있다.
  - [x] `POST /api/agent-logs/upload`, `GET /api/agent-logs/{id}`, `POST /api/as-tickets`, `GET /api/as-tickets/{id}` API 골격이 있다.
  - [x] `GET/PATCH /api/admin/as-tickets` 백엔드 route는 `AdminController`에 있다.
- [ ] 4번 미완료/확인 필요
  - [ ] `AgentLogQueryService.upload()`가 현재 사용자 id가 아니라 `user@example.com` 고정 user_id로 저장한다.
  - [ ] 로그 업로드 서버 검증이 계약 수준에 부족하다. 파일 필수, JSONL line 검증, MIME/확장자, 10 MiB, 20000 line, PII masking, `FILE_VALIDATION_ERROR`가 필요하다.
  - [ ] `AgentLogQueryService.detail()`과 `TicketQueryService.ticket()`는 본인 소유 404를 보장하지 않는다.
  - [ ] `TicketQueryService.create()`가 현재 사용자 id가 아니라 `user@example.com` 고정 user_id로 티켓을 만든다.
  - [ ] `PATCH /api/admin/as-tickets/{id}`는 허용 상태 전이표, `409 CONFLICT_STATE`, `assignedAdminId`, `resolvedAt`, `admin_audit_logs` 기록을 구현하지 않는다.
  - [ ] `/admin/as-tickets`, `/admin/as-tickets/:ticketId` 프론트는 mock/static/no-op 화면으로 실제 admin AS API를 호출하지 않는다.
  - [ ] `AgentLogControllerTest`, `TicketControllerTest`, `TicketQueryServiceTest`가 없어 4번 계약을 막아주는 백엔드 테스트가 부족하다.

#### 2026-07-01 프로젝트 종료까지 남은 작업

프로젝트를 끝내려면 “화면이 렌더링된다”가 아니라 **계약서의 실제 사용자 흐름이 서버+DB+테스트로 닫히는 상태**가 되어야 한다.

- [ ] 1번 Quote/Auth 마무리
  - [ ] Google OAuth start/callback/exchange 구현 여부를 결정하고, 구현한다면 Redis/runtime one-time code와 token 저장 흐름을 완성한다.
  - [ ] `requirements`, `builds`, `build_items` 저장/조회/변경을 현재 로그인 사용자 소유권 기준으로 바꾼다.
  - [ ] 타인 build/history/detail/change-part 접근 시 404를 반환하도록 contract test를 추가한다.
  - [ ] `/my/quotes`를 mock에서 `GET /api/builds/history` 실제 API 연결로 전환한다.
- [ ] 2번 Parts/Price/Tool 마무리
  - [ ] 가격 알림을 현재 사용자 기준으로 저장/조회하고 active 중복 등록은 409로 정리한다.
  - [ ] `POST /api/admin/price-jobs/run`의 중복 실행 409, 실패 처리, 상태 전이 정책을 계약과 맞춘다.
  - [ ] `/admin/price-jobs`를 disabled/static 화면이 아니라 실제 API 실행/상태/실패 이력 화면으로 연결한다.
  - [ ] 가격 수집이 네이버 API/다나와 보완 결과를 내부 후보/게시 데이터로 저장하는 흐름을 최종 검증한다.
- [ ] 3번 Agent/RAG/AS Chat 마무리
  - [ ] 일반 Agent session create/run/get API의 본인 소유권 404를 보장한다.
  - [ ] Agent 실행 상태 전이와 queue/worker 사용 여부를 결정하고, RabbitMQ를 실제로 쓰는 경우 ack/retry 정책을 문서화/테스트한다.
  - [ ] 관리자 Agent/Tool/RAG 메뉴가 seed 상세 링크인지 list route인지 최종 결정한다.
  - [ ] AS Chat LLM/RAG/Tool 응답이 저장되고 실패/OPENAI_API_KEY 없음/stream fallback이 테스트로 막히는지 확인한다.
- [ ] 4번 PC Agent/AS 마무리
  - [ ] 로그 업로드를 현재 로그인 사용자 기준으로 저장하고 본인 소유 404를 적용한다.
  - [ ] JSONL 파일 필수, MIME/확장자, 10 MiB, 20000 line, line JSON validation, PII masking, `FILE_VALIDATION_ERROR`를 구현한다.
  - [ ] AS 티켓 생성/조회/update에 현재 사용자 소유권, soft delete 제외, 상태 전이 409, `assignedAdminId`, `resolvedAt`, audit log를 적용한다.
  - [ ] `/admin/as-tickets`, `/admin/as-tickets/:ticketId`를 mock/static에서 실제 admin AS API 화면으로 전환한다.
  - [ ] `AgentLogControllerTest`, `TicketControllerTest`, `TicketQueryServiceTest`를 추가한다.
- [ ] 5번 Common/Admin/Infra 마무리
  - [ ] `api.ts`의 `ErrorResponse` 보존, refresh retry 1회, refresh 실패 시 `clearToken()`, logout API 호출 흐름을 고정한다.
  - [ ] `RequireUser`, `RequireAdmin`, admin shell route guard가 실제 JWT/role 정책과 충돌 없는지 최종 확인한다.
  - [ ] AdminShell nav label/order와 각 owner route 정책을 2/3/4번과 공유해 확정한다.
  - [ ] k6 300명/1000명 부하 테스트 시나리오와 리포트 템플릿을 실제 endpoint 기준으로 확장한다.
  - [ ] Redis/RabbitMQ/Mailpit은 실제 기능이 붙은 뒤 connection smoke에서 기능 smoke로 올린다.
- [ ] 공통 종료 검증
  - [ ] `npm run build`, `npm run test`.
  - [ ] `./gradlew test --no-daemon`, `./gradlew bootJar --no-daemon`.
  - [ ] `python tools/validate_openapi.py`.
  - [ ] `docker compose config`, 필요 시 `docker compose up --build` runtime smoke.
  - [ ] 실제 E2E: 로그인 -> 자연어 구매 상담 -> 추천 적용 -> 내 견적함 -> 가격 알림 -> PC Agent 로그 업로드 -> AS 티켓 생성 -> 관리자 화면 확인.

#### 2026-07-01 MVP와 MVP 이후 작업 구분

| 구분 | 기능/영역 | 끝내기 위한 작업 | 담당/협업 |
| --- | --- | --- | --- |
| MVP | Auth/User | 이메일 회원가입, 로그인, refresh/logout, `/auth/me`, token 저장/삭제, 401/403 분기, 공통 `ErrorResponse`를 테스트까지 닫는다. OAuth는 구현 여부를 별도 확정한다. | 1번, 5번 |
| MVP | 구매 상담/추천 | 자연어 입력 -> 요구사항 저장 -> RAG/Tool 근거 기반 추천 2~3개 -> 추천 적용까지 실제 DB와 현재 사용자 기준으로 연결한다. | 1번, 2번, 3번 |
| MVP | 내 견적함 | `/my/quotes` mock을 제거하고 `GET /api/builds/history` 실제 사용자 build 목록으로 바꾼다. | 1번, 2번 협업 |
| MVP | 부품/가격/Tool | 내부 자산 `parts`, 가격 스냅샷, Tool check, 견적초안 적용/수정/삭제를 계약 DTO와 맞추고 테스트한다. | 2번 |
| MVP | 가격 알림 | 목표가 알림 등록/조회, 현재 사용자 기준 저장, active 중복 409, 1회 이메일 발송 정책을 구현한다. | 2번, 5번 Mailpit |
| MVP | Agent/RAG/근거 | Agent session, Tool invocation, RAG evidence 저장/조회, 관리자 상세 확인, 본인 소유권/관리자 권한을 테스트한다. | 3번, 5번 |
| MVP | PC Agent/AS | JSONL sample/export, 로그 업로드, 파일 검증, AS 티켓 생성/조회, 본인 소유권 404를 구현한다. | 4번 |
| MVP | 관리자 AS | `/admin/as-tickets`, `/admin/as-tickets/:ticketId`를 mock에서 실제 API로 바꾸고 상태 전이 409, 담당자 배정, audit log를 구현한다. | 4번, 5번 |
| MVP | AdminShell/공통 관리자 | 8개 메뉴, guard, dashboard, audit logs, owner route 연결, 권한 분기를 최종 고정한다. | 5번, 2/3/4번 협업 |
| MVP | 인프라/검증 | Docker compose, Redis/RabbitMQ/Mailpit smoke, CI, OpenAPI 검증, Gradle/npm test/build, 실제 E2E를 닫는다. | 5번 |
| MVP 이후 | Google OAuth 확장 | Google start/callback/exchange, Redis one-time code TTL, 계정 연결 정책, OAuth 오류/보안 테스트를 구현한다. MVP에 포함할지는 팀 결정 필요. | 1번, 5번 |
| MVP 이후 | 실제 queue worker | Agent job, price job, mail job을 RabbitMQ publish/consume/ack/retry/dead-letter 정책으로 확장한다. | 2번, 3번, 5번 |
| MVP 이후 | 가격 수집 고도화 | 가격 동향 그래프, 배송비/쿠폰/품절 반영, 수집 대상 확대, 다나와 크롤링 안정화, 가격 이력 분석을 추가한다. | 2번 |
| MVP 이후 | 부하 테스트 확장 | k6 300명/1000명 시나리오, 병목 분석, p95/에러율 리포트, mock worker 부하 검증을 실행한다. | 5번, 전체 |
| MVP 이후 | PC Agent 고도화 | 실제 센서 수집, Windows Event Log, NVML, 문제 상황 profile, 자동 로그 보관 삭제 스케줄러를 붙인다. | 4번 |
| MVP 이후 | AS 고도화 | 상담원 답변 등록, 티켓 종료 요청, 업그레이드 후보 반영, AS Chat 결과를 티켓 후보로 승인 반영하는 workflow를 만든다. | 4번, 3번 |
| MVP 이후 | 관리자 운영 고도화 | 전역 검색, export/action button, 운영 작업 패널 실시간화, dashboard degraded 원인 드릴다운을 구현한다. | 5번, 2/3/4번 |
| MVP 이후 | 메일/알림 확장 | 회원가입 인증 메일, 가격 알림 템플릿, 재발송/수신거부, Mailpit 이후 실제 SMTP 연동을 구현한다. | 1번, 2번, 5번 |
| MVP 이후 | 제외 범위 유지 | 결제/배송/거래, 커뮤니티, 원격제어, 정확한 FPS 보장, 최저가 보장, 자동 모델 학습은 MVP 이후에도 별도 기획 없이는 구현하지 않는다. | 팀 결정 필요 |

#### 2026-07-01 `last.md` 최종 남은 작업 문서 생성 기록

- [x] 저장소 루트에 `last.md`를 새로 만들었다.
- [x] MVP를 끝내기 위한 작업과 MVP 이후 기능 구현을 끝내기 위한 작업을 분리했다.
- [x] 각 작업에 현재 상태, 남은 작업, 완료 기준, 담당/협업자를 정리했다.
- [x] MVP 이후에도 별도 기획 없이는 구현하지 않을 제외 범위를 따로 정리했다.

#### 2026-07-01 구매 상담 AI 챗봇 사용자 격리 및 로그인 오류 UX 수정

- [x] Playwright 회귀 테스트를 먼저 추가했다.
  - [x] A 사용자 구매 상담 세션이 있어도 B 사용자 로그인 상태에서는 A 메시지가 보이지 않는다.
  - [x] legacy global key `buildgraph.ai.assistantSession`에 남은 이전 대화가 현재 사용자에게 표시되지 않는다.
  - [x] 챗봇 화면이 열린 뒤 token이 사라진 상태에서 질문하면 API 호출 없이 로그인 필요 문구를 보여준다.
  - [x] `/api/ai/build-chat`가 401을 반환하면 일반 실패 문구 대신 로그인 필요 문구를 보여준다.
- [x] `buildgraph.ai.assistantSession:{userIdOrEmail}` 방식으로 구매 상담 챗봇 대화 저장소를 사용자별로 분리했다.
- [x] `buildgraph.ai.selectedBuild:{userIdOrEmail}` 방식으로 AI 선택 Build 저장소도 사용자별로 분리했다.
- [x] 기존 unscoped `buildgraph.ai.assistantSession`, `buildgraph.ai.selectedBuild`는 현재 로그인 사용자에게 fallback하지 않고 정리 대상으로 처리했다.
- [x] token 없음 또는 401 응답에서는 optimistic user message를 남기지 않고 `로그인이 필요합니다. 다시 로그인해 주세요.`를 표시하도록 수정했다.
- [x] 검증: `cd apps/web && npm run test -- home.spec.ts` 통과.
- [x] 추가 검증: `cd apps/web && npm run test`, `cd apps/web && npm run build`, `git diff --check` 통과.

#### 2026-07-01 커밋 메시지 요청 전 점검 기록

- [x] 현재 브랜치가 `main`이고, 마지막 커밋이 `Merge pull request #26 from jungle-final-project/feat/popular-part-detail-links`임을 확인했다.
- [x] 현재 변경은 구매 상담 AI 세션 사용자별 저장소 분리, 로그인 만료 오류 UX, 관련 Playwright 테스트, 남은 작업 최종 정리 문서, 오래된 hoseok/hosoek 문서 자산 삭제로 구분된다.
- [x] 코드/테스트 변경과 문서 정리 변경은 성격이 달라 별도 커밋으로 나누는 것이 적절하다.

## 우선순위

### P0

- [x] `RequireAdmin` 테스트 작성: `apps/web/tests/admin-guard.spec.ts`
- [x] Frontend 테스트 보강: `AdminDashboardPage`, `adminApi`, `api.ts`
- [x] Backend Java 테스트 작성: `AdminControllerTest.java`, `HealthControllerTest.java`
- [x] `AdminDashboard` DTO 불일치 해결
- [x] `/admin` dashboard가 실제 API 응답을 안전하게 표시하도록 수정
- [x] 401/403 권한 분기 확인
- [x] PR 전 기본 검증 명령 실행
- [x] 1번 Auth/User 구현 후 `api.ts`, `RequireAdmin`, admin guard 기본 연동 검토
- [ ] `api.ts` refresh retry, logout API 호출, `ErrorResponse` 보존 구현

### P1

- [x] AdminShell nav를 Figma 기준 8개 메뉴로 세분화
- [x] AdminShell topbar search/action frame 정리
- [x] audit logs recent 표시 연결
- [x] k6 smoke 리포트 템플릿 작성
- [x] Redis/RabbitMQ/Mailpit Sprint 1 smoke 결과 기록
- [x] Auth owner 문서를 1번 Auth/User 구현, 5번 Auth 공통/token/guard 기준으로 정리
- [x] PR 검증 결과 요약 정리
- [x] Common API Client 정책 결정

### P2

- [ ] Redis/RabbitMQ/Mailpit 실제 기능 연동. 조건: OAuth one-time code, AI 견적 추천 실행 작업, 부품 가격 수집 작업, 가격 알림 메일 중 해당 owner 구현 PR 발생
- [ ] 부하 테스트 300명/1000명 시나리오 확장
- [ ] AdminShell sample id 메뉴를 3번과 합의해 list route 또는 seed link 정책으로 정리
- [ ] `/admin/price-jobs` 중복 실행 409 계약 위반 여부를 2번과 정리
- [ ] `/admin/as-tickets` mock/static 상태를 4번과 정리

## 다음 작업 순서

1. **Common API Client 구현 고도화**
   - `apps/web/src/lib/api.ts`에 backend `ErrorResponse` 보존 테스트를 먼저 추가한다.
   - refresh token 저장/조회/삭제 helper와 요청당 최대 1회 refresh retry를 구현한다.
   - `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`, `/api/auth/exchange`는 retry 제외 대상으로 둔다.
   - refresh 실패 또는 refresh 불가 401에서 `clearToken()`과 로그인 필요 상태가 일관되게 동작하는지 확인한다.

2. **Owner 2/3/4 관리자 route 정합성 공유**
   - 2번과 `/admin/price-jobs` 실제 API 연결 범위와 중복 실행 409 정책을 확인한다.
   - 3번과 AdminShell의 Agent/Tool/RAG 메뉴가 sample id link인지 list route인지 확인한다.
   - 4번과 `/admin/as-tickets` mock/static 화면을 실제 API 연결로 넘길 시점을 확인한다.
   - admin API 권한 분기를 현재 JWT service 검사에서 Spring Security/JWT filter 정책으로 전환할지 결정한다.

3. **Redis/RabbitMQ/Mailpit 실제 기능 연동 대기**
   - Sprint 1 smoke는 완료했다. Redis `PONG`, RabbitMQ management API 200, Mailpit UI/API/SMTP 응답 확인.
   - RabbitMQ 작업 대기열 이름 초안은 `agent.jobs`, `price.jobs`, `mail.jobs`로 기록했다.
   - 실제 Redis key schema, RabbitMQ 작업 대기열 선언/작업 처리기, Mailpit 메일 발송 로직은 owner 구현 전까지 만들지 않는다.
   - 1번 OAuth one-time code, 3번 AI 견적 추천 실행 작업, 2번 부품 가격 수집 작업 또는 가격 알림 메일 구현 PR이 나오면 5번이 인프라 연동을 검토한다.

4. **k6 확장 시나리오 분리**
   - 현재 완료된 것은 `infra/k6/smoke.js`와 `docs/reports/k6-smoke-report-template.md` 기준의 smoke 결과 기록이다.
   - 300명/1,000명 부하는 별도 k6 script와 별도 리포트로 확장한다.
   - 사용자 견적 생성, 관리자 조회, AS 티켓 생성 흐름을 포함할지는 각 owner와 합의한다.

## 확인 필요 질문

- [x] AdminDashboard DTO는 OpenAPI 기준으로 프론트를 맞출지, Figma 운영 지표 기준으로 OpenAPI를 확장할지 결정해야 한다. 결정: OpenAPI/백엔드 기준으로 프론트를 맞춘다.
- [x] DB 연결 실패 시 `/api/health`를 500 `INTERNAL_ERROR`, 503 `DOWN`, 또는 200 `status: "DOWN"` 중 어떤 정책으로 반환할지 결정해야 한다. 결정: `503 Service Unavailable` + `{ "status": "DOWN" }`
- [x] AdminShell nav의 `가격 Job`을 2번의 `/admin/parts` 안에 둘지 별도 route로 둘지 결정해야 한다. 결정: `/admin/price-jobs` 별도 route
- [x] `부하 테스트` 관리자 화면을 MVP route로 만들지, 문서/리포트 링크로 둘지 결정해야 한다. 결정: `/admin/load-tests` route
- [x] topbar `작업 실행`이 어떤 작업을 실행하는 버튼인지 결정해야 한다. 결정: Sprint 1에서는 실행 job 미확정으로 disabled placeholder 처리한다.
- [x] admin search가 전역 검색인지, 단순 placeholder인지 결정해야 한다. 결정: Sprint 1에서는 전역 검색과 placeholder input을 모두 제외한다.

| 구분 | 남은 일 | 상태 |
| --- | --- | --- |
| 로그인 토큰 공통 연결 | 1번 로그인/JWT/refresh/logout 백엔드가 들어왔으므로 `api.ts` refresh/logout 연동과 오류 표시를 고도화 | 바로 가능 |
| API 공통 호출 함수 | 로그인 만료 시 토큰 삭제, API 오류 응답 통일, refresh 재시도 1회 처리 | 바로 가능 |
| 관리자 권한 검사 | 현재 JWT service 방식에서 Spring Security filter 방식으로 바꿀지 결정 | 후속 보안 정리 시점 |
| 관리자 메뉴/레이아웃 | 관리자 메뉴 이름과 순서를 2/3/4번 담당자와 공유 | 바로 가능 |
| 동시 접속 부하 테스트 | 300명/1000명 접속 상황 테스트 시나리오 만들기 | 남음 |
| Redis 임시 저장소 | 구글 로그인 임시 코드, AI 결과 캐시, 사용량 제한 정책 검토 | 1번/3번 이후 |
| RabbitMQ 작업 대기열 | AI 추천/분석 작업, 부품 가격 수집 작업을 대기열에 넣고 처리하는 정책 검토 | 2번/3번 이후 |
| Mailpit 개발용 메일함 | 가격 알림 메일이나 회원가입 인증 메일이 잘 오는지 확인 | 1번/2번 이후 |
