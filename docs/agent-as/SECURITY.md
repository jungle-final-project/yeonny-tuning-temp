# Agent Security 기준

## 핵심 원칙

- `/api/agent/**` 는 실제 PC Agent 전용 API다.
- `/api/agent/**` 요청은 Agent token만 허용한다.
- 웹 JWT를 `/api/agent/**` 에 섞는 방식은 금지한다.
- Agent token으로 웹 JWT 보호 API에 접근할 수 없어야 한다.
- Agent 인증 성공 시 웹 사용자 Principal이 아니라 `AgentPrincipal`을 사용한다.

## 실제 구현 파일

| 구분 | 실제 경로 |
|---|---|
| Agent 전용 Security Chain | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentSecurityConfig.java` |
| Agent token filter | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentAccessTokenFilter.java` |
| Agent token 검증 서비스 | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentTokenAuthenticationService.java` |
| Agent token hash | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentTokenHasher.java` |
| Agent 인증 결과 | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentTokenAuthenticationResult.java` |
| Agent Principal | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentPrincipal.java` |
| Agent Authentication | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentAuthenticationToken.java` |
| Security 실패 응답 writer | `apps/api/src/main/java/com/buildgraph/prototype/config/security/SecurityErrorResponseWriter.java` |
| 웹 JWT 사용자 서비스 | `apps/api/src/main/java/com/buildgraph/prototype/user/CurrentUserService.java` |
| 웹 JWT token 서비스 | `apps/api/src/main/java/com/buildgraph/prototype/user/JwtTokenService.java` |
| 보안 테스트 | `apps/api/src/test/java/com/buildgraph/prototype/config/security/AgentSecurityChainTest.java` |

## `/api/agent/**` Security Chain

`AgentSecurityConfig`는 `@Order(1)` SecurityFilterChain을 제공한다.

- `securityMatcher("/api/agent/**")`
- `csrf` 비활성화
- `httpBasic`, `formLogin`, `logout` 비활성화
- session policy는 `STATELESS`
- 모든 `/api/agent/**` 요청은 `authenticated()`
- `AgentAccessTokenFilter`를 `UsernamePasswordAuthenticationFilter` 앞에 추가
- `AgentIdempotencyFilter`를 `AgentAccessTokenFilter` 뒤에 추가

이 체인은 Agent token 인증을 먼저 수행하고, 인증 성공 후 mutation 요청에만 Idempotency-Key 검사를 수행한다.

## 웹 JWT 체인과의 분리 방식

현재 웹 API는 컨트롤러나 서비스에서 `CurrentUserService.requireUser(authorization)` 또는 `requireAdmin(authorization)`로 JWT를 검증하는 방식이다.

분리 기준은 다음과 같다.

| 영역 | 인증 방식 | Principal |
|---|---|---|
| `/api/agent/**` | `agent_devices.agent_token_hash` 기반 Agent token | `AgentPrincipal` |
| 웹 USER/ADMIN API | JWT access token | `CurrentUserService.CurrentUser` |

새 Agent API는 `CurrentUserService`를 사용하지 않는다. 새 웹 API는 `AgentPrincipal`이나 `AgentAuthenticationToken`을 사용하지 않는다.

## Agent token 인증 흐름

1. 요청 header에서 `Authorization: Bearer <raw-agent-token>`을 읽는다.
2. token이 없거나 비어 있으면 `401 UNAUTHORIZED`를 반환한다.
3. `AgentTokenHasher.sha256Hex(rawToken)`으로 SHA-256 hash를 계산한다.
4. `agent_devices.agent_token_hash`와 같은 row를 조회한다.
5. `status`가 `ACTIVE` 또는 `UPDATE_REQUIRED`이면 인증 성공으로 본다.
6. 그 외 status는 `403 FORBIDDEN`으로 처리한다.
7. 인증 성공 시 `AgentAuthenticationToken`을 만들어 SecurityContext에 저장한다.

현재 DB 기준 필드는 `V56__pc_agent_gold_mode_contract.sql`의 `agent_devices` 테이블을 따른다.

## AgentPrincipal 필드

`AgentPrincipal`은 다음 필드만 가진다.

| 필드 | 의미 |
|---|---|
| `deviceInternalId` | `agent_devices.id` 내부 PK. 서버 내부 scope와 idempotency scope에 사용한다. |
| `deviceId` | `agent_devices.public_id` 문자열. API 응답이나 로그 식별에 사용할 수 있다. |
| `userInternalId` | `agent_devices.user_id`. 소유 사용자 내부 PK다. |
| `status` | 현재 Agent device 상태 문자열이다. |

외부 API 응답에서 내부 PK를 노출하면 안 된다. `deviceInternalId`, `userInternalId`는 서버 내부 검증에만 사용한다.

## Authorization 헤더 형식

```http
Authorization: Bearer <raw-agent-token>
```

raw token 원문은 DB에 저장하지 않는다. 저장 기준은 `agent_devices.agent_token_hash`다.

## 인증 실패 기준

| 상황 | 결과 |
|---|---|
| `Authorization` header 없음 | `401 UNAUTHORIZED` |
| Bearer prefix 없음 | `401 UNAUTHORIZED` |
| Bearer token 값이 blank | `401 UNAUTHORIZED` |
| hash와 매칭되는 Agent device 없음 | `401 UNAUTHORIZED` |
| 웹 JWT를 `/api/agent/**` 에 전달 | Agent token으로 해석되며 매칭 실패 시 `401 UNAUTHORIZED` |
| Agent device status가 `ACTIVE`, `UPDATE_REQUIRED`가 아님 | `403 FORBIDDEN` |
| Agent token으로 웹 JWT 보호 API 접근 | 웹 JWT 검증 실패로 `401 UNAUTHORIZED` |

## 새 Agent API 구현 체크리스트

- endpoint path가 `/api/agent/**` 인가?
- 웹 JWT 인증을 섞지 않았는가?
- `CurrentUserService`를 사용하지 않았는가?
- controller에서 `@AuthenticationPrincipal AgentPrincipal` 또는 SecurityContext의 `AgentPrincipal`을 사용했는가?
- 내부 소유권 검증은 `principal.deviceInternalId()`와 `principal.userInternalId()` 기준으로 했는가?
- mutation API라면 [IDEMPOTENCY.md](IDEMPOTENCY.md) 기준을 따르는가?
- 응답에 내부 `BIGINT id`를 노출하지 않았는가?
- 실패 응답은 `ApiErrorResponse(code, message)` 형태와 맞는가?

## Register API 처리 원칙

Register API는 Agent token을 발급하기 전 단계일 수 있다. 이 경우에도 웹 JWT를 `/api/agent/**` 에 섞는 방식은 금지한다.

가능한 방향은 Goal 4에서 계약과 테스트로 먼저 확정한다.

- activation token 전용 pre-auth filter를 `/api/agent/devices/register`에만 적용한다.
- register endpoint만 별도 security rule로 열되, body/header의 activation token 검증을 service에서 강제한다.
- register path를 `/api/agent/**` 밖으로 옮긴다.

## 확인 필요

- 현재 `AgentSecurityConfig`는 `/api/agent/**` 전체에 `authenticated()`를 요구한다. `POST /api/agent/devices/register`가 token 발급 전 API라면 현 구조와 충돌할 수 있다.
- 웹 JWT 기반 AI Agent/RAG session API는 `POST /api/ai/agent-sessions`, `POST /api/ai/agent-sessions/{id}/run`, `GET /api/ai/agent-sessions/{id}`로 분리했다. `/api/agent/**`에는 웹 JWT 기반 API를 두지 않는다.
