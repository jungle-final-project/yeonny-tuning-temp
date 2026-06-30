# 5번 작업 분석 체크리스트

작성일: 2026-06-29

## 결론

5번 담당 범위는 **Auth 공통 연동/AdminShell/AdminDashboard/공통 UI/Infra/CI**다.

로그인/회원가입의 프론트와 백엔드 구현은 1번에게 이관했다. 5번은 Auth API 구현 자체가 아니라 `api.ts`의 token 전달, `RequireAdmin`, `/admin/*` 권한 분기, AdminShell, AdminDashboard, Health, Infra/CI를 담당한다.

Figma 기준으로 5번이 직접 맡아야 할 화면은 `153:1880 STATE-15 ADMIN-01 운영 대시보드 / degraded`다. `153:2011 STATE-16 ADMIN-04 AS 티켓 관리자 / assigned success`는 4번 담당 화면이지만, 같은 AdminShell을 쓰므로 5번은 shell/guard 관점에서 참고해야 한다.

5번 기능 단위 상태는 1번 이관 작업을 제외하고 이렇게 정리한다.

| 기능 단위 | 상태 | 5번에게 남은 작업 |
| --- | --- | --- |
| Auth 공통 연동 | 진행중 | `Authorization` header와 token helper는 완료. Common API Client 정책 결정 완료. 1번 Auth 구현 후 실제 응답과 `RequireAdmin` 연동 검토 필요 |
| JWT/Token 연동 | 진행중 | 프론트 header 전달은 완료. 1번의 실제 JWT 구현 이후 admin guard/security 전환 검토 필요 |
| Auth Error 연동 | 진행중 | admin 401/403 분기는 완료. 1번 Auth 오류 응답이 공통 `ErrorResponse`와 충돌 없는지 검토 필요 |
| RequireAdmin | 완료 | `/admin/*` guard, `auth/me` role 확인, 401/403 화면 분기와 테스트 완료 |
| AdminShell | 진행중 | 8개 nav 메뉴와 route 연결 완료. topbar search 제외와 disabled action frame 완료. nav label/order owner 공유 미완료 |
| AdminDashboard | 완료 | DTO 정합성, metric, loading/error/success, degraded alert, 운영 작업, 관리자 할 일 link frame 완료 |
| Admin Audit Logs | 완료 | 백엔드 endpoint, 권한 테스트, seed 조회, 프론트 `최근 관리자 작업` 표시 연결 완료 |
| Common API Client | 진행중 | token header 첨부 완료. refresh retry, token 만료 시 clear, logout 후 clear, error normalization 정책 결정 완료. 구현 고도화는 1번 Auth/JWT PR 이후 |
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
| Auth 공통 연동 | 진행중 | `Authorization` header, `saveToken`, `getToken`, `clearToken` helper는 완료. Common API Client 정책 결정 완료. 1번 Auth 구현 후 실제 token/refresh 응답과 `RequireAdmin` 연동 검토 필요 |
| JWT/Token 연동 | 진행중 | 현재 프론트는 저장된 token을 `Bearer`로 전달한다. 실제 JWT role/expiry 연동은 1번 JWT 구현 후 검토 |
| Auth Error 연동 | 진행중 | `RequireAdmin`의 401/403 화면 분기는 완료. 공통 error normalization과 1번 Auth 오류 응답 정합성 검토 필요 |
| RequireAdmin | 완료 | `/admin/*` guard, `auth/me` role 확인, token 없음/401/403/USER role 분기와 테스트 완료 |
| AdminShell | 진행중 | 8개 nav, topbar search 제외, export/action disabled frame 완료. nav label/order owner 공유만 남음 |
| AdminDashboard | 완료 | DTO 정합성, metric, loading/error/success, degraded alert, 운영 작업, 관리자 할 일 frame 완료 |
| Admin Audit Logs | 완료 | `getRecentAdminAuditLogs()` wrapper와 `/admin` 최근 관리자 작업 표시, 실패 패널 분리 완료 |
| Common API Client | 진행중 | token header 첨부는 완료. token 만료 시 `clearToken()`, refresh retry 위치, logout 후 token 정리, error normalization 정책 결정 완료. 구현은 1번 Auth/JWT PR 이후 |
| Common UI | 완료 | `components/ui.tsx` barrel 유지, 공통 layout/display/feedback 구조 유지 완료 |

### 백엔드

| 기능 단위 | 상태 | 최신 판단 |
| --- | --- | --- |
| JWT/Token 연동 | 진행중 | 현재 admin/user API는 seed 사용자 비밀번호 검증과 JWT access token을 사용한다. Spring Security filter 전환 여부는 후속 작업에서 검토한다. |
| Auth Error 연동 | 진행중 | `ApiExceptionHandler`의 401/403 `ErrorResponse`는 완료. 400 validation, 409 duplicate, refresh/logout 오류 shape는 1번 Auth 구현 후 검토 필요 |
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
| API 계약 문서 | 완료 | `API_CONTRACT.md`의 Auth/User owner를 1번으로 정리했다. 1번 Auth 구현 PR에서 error/refresh 세부 계약 변경이 있으면 `openapi.yaml`과 같이 검토 |
| Route Ownership 문서 | 완료 | `ROUTE_OWNERSHIP.md`, `role-workspaces.md`, `README.md`, Sprint 1 체크리스트에 Auth/User 구현 owner 1번, Auth 공통/token/guard owner 5번 기준을 반영했다 |

### 분류 요약

| 분류 | 완료 | 진행중 | 시작안함 |
| --- | --- | --- | --- |
| 프론트 | `RequireAdmin`, `AdminDashboard`, `Admin Audit Logs`, `Common UI` | `Auth 공통 연동`, `JWT/Token 연동`, `Auth Error 연동`, `AdminShell`, `Common API Client` | 없음 |
| 백엔드 | `AdminDashboard`, `Admin Audit Logs`, `Health` | `JWT/Token 연동`, `Auth Error 연동`, `Common API/Error` | 없음 |
| 인프라·문서·검증 | `Docker Compose`, `Redis`, `RabbitMQ`, `Mailpit`, `CI/GitHub Actions`, `테스트/검증`, `API 계약 문서`, `Route Ownership 문서` | `k6/부하 테스트` | 없음 |

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
- [ ] 1번 Auth API 구현 후 `apps/web/src/lib/api.ts`의 Authorization header/token 저장 정책과 충돌 없는지 확인한다.
- [ ] 1번 Auth API 구현 후 `RequireAdmin`이 실제 JWT/role 기반 `/api/auth/me` 응답과 맞는지 확인한다.
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
| 401 처리 | refresh 불가 또는 refresh 실패 시 `clearToken()` 호출 후 로그인 필요 상태로 전환 | 1번 refresh 구현 전에는 자동 refresh retry를 넣지 않음 |
| refresh retry | `api.ts` 공통 wrapper에서 요청당 최대 1회만 허용 | `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`, `/api/auth/exchange`는 retry 제외 |
| logout | 1번 Auth flow가 logout API를 호출하고, 프론트 token 정리는 `clearToken()` 사용 | logout API 실패 시에도 강제 로컬 로그아웃이 필요한지 1번 PR에서 최종 확인 |
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

## 우선순위

### P0

- [x] `RequireAdmin` 테스트 작성: `apps/web/tests/admin-guard.spec.ts`
- [x] Frontend 테스트 보강: `AdminDashboardPage`, `adminApi`, `api.ts`
- [x] Backend Java 테스트 작성: `AdminControllerTest.java`, `HealthControllerTest.java`
- [x] `AdminDashboard` DTO 불일치 해결
- [x] `/admin` dashboard가 실제 API 응답을 안전하게 표시하도록 수정
- [x] 401/403 권한 분기 확인
- [x] PR 전 기본 검증 명령 실행
- [ ] 1번 Auth/User 구현 후 `api.ts`, `RequireAdmin`, admin guard 연동 검토

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
- [ ] 1번 refresh/JWT 구현 후 5번 공통 API client/admin guard 연동 고도화

## 다음 작업 순서

1. **1번 Auth 구현 후 5번 공통 연동 검토**
   - `apps/web/src/lib/api.ts`의 token 저장/전달 정책이 실제 access token/refresh token 응답과 맞는지 확인한다.
   - `RequireAdmin`이 실제 `/api/auth/me`의 role, 401, 403 응답과 맞는지 확인한다.
   - admin API 권한 분기를 현재 JWT service 검사에서 Spring Security/JWT filter 정책으로 전환할지 결정한다.

2. **Common API Client 구현 고도화**
   - 1번 refresh/JWT 구현 후 `api.ts`에 최대 1회 refresh retry를 구현한다.
   - refresh 실패 또는 refresh 불가 401에서 `clearToken()`과 로그인 필요 상태가 일관되게 동작하는지 확인한다.
   - `ApiError`가 backend `ErrorResponse`의 `code`, `message`, `details`, `status`를 보존하도록 확장한다.

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
| 로그인 토큰 공통 연결 | 1번이 로그인/JWT/로그아웃/구글 로그인을 만들면 `api.ts`, `RequireAdmin`, 관리자 권한 검사와 충돌 없는지 확인 | 대기 |
| API 공통 호출 함수 | 로그인 만료 시 토큰 삭제, API 오류 응답 통일, refresh 재시도 1회 처리 | 1번 Auth 이후 |
| 관리자 권한 검사 | 현재 JWT service 방식에서 Spring Security filter 방식으로 바꿀지 결정 | 후속 보안 정리 시점 |
| 관리자 메뉴/레이아웃 | 관리자 메뉴 이름과 순서를 2/3/4번 담당자와 공유 | 바로 가능 |
| 동시 접속 부하 테스트 | 300명/1000명 접속 상황 테스트 시나리오 만들기 | 남음 |
| Redis 임시 저장소 | 구글 로그인 임시 코드, AI 결과 캐시, 사용량 제한 정책 검토 | 1번/3번 이후 |
| RabbitMQ 작업 대기열 | AI 추천/분석 작업, 부품 가격 수집 작업을 대기열에 넣고 처리하는 정책 검토 | 2번/3번 이후 |
| Mailpit 개발용 메일함 | 가격 알림 메일이나 회원가입 인증 메일이 잘 오는지 확인 | 1번/2번 이후 |
