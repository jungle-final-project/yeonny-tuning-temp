# Agent AS 구현 가이드

## 새 Agent API 추가 순서

1. [README.md](README.md), [SECURITY.md](SECURITY.md), [IDEMPOTENCY.md](IDEMPOTENCY.md), [GOAL_BOUNDARIES.md](GOAL_BOUNDARIES.md)를 읽는다.
2. 본인 Goal에 포함된 API만 고른다.
3. [API_CONTRACT.md](../API_CONTRACT.md), [DB_SCHEMA.md](../DB_SCHEMA.md), [ROUTE_OWNERSHIP.md](../ROUTE_OWNERSHIP.md), [openapi.yaml](../openapi.yaml)을 확인한다.
4. 계약과 현재 구현이 충돌하면 코드를 추측 수정하지 않고 확인 필요로 보고한다.
5. 테스트를 먼저 작성한다.
6. 필요한 최소 controller/service/repository만 구현한다.
7. `/api/agent/**` mutation이면 `Idempotency-Key` 정책을 통과하는지 확인한다.
8. 관련 테스트, 기존 Agent Security 테스트, `git diff --check`를 실행한다.
9. 완료 보고에 구현 범위와 구현하지 않은 범위를 함께 적는다.

## Controller 작성 기준

- PC Agent 전용 endpoint는 `/api/agent/**` 아래에 둔다.
- 인증된 Agent 정보는 `AgentPrincipal`을 사용한다.
- 웹 사용자 인증을 위해 쓰는 `CurrentUserService`를 Agent controller에 넣지 않는다.
- 내부 `BIGINT id`는 response에 노출하지 않는다.
- 실제 production endpoint가 아직 Goal 범위가 아니면 test-only controller만 사용한다.
- Register처럼 token 발급 전 단계가 필요한 API는 구현 전에 [SECURITY.md](SECURITY.md)의 확인 필요 항목을 먼저 해결한다.

예상 형태:

```java
@PostMapping("/api/agent/...")
ResponseDto handle(@AuthenticationPrincipal AgentPrincipal principal, @RequestBody RequestDto request) {
    ...
}
```

## Service 작성 기준

- 소유권과 scope는 `AgentPrincipal.deviceInternalId()`와 `AgentPrincipal.userInternalId()`로 잡는다.
- 웹 user public id만으로 Agent 소유권을 판단하지 않는다.
- 상태 변경은 transaction 경계를 명확히 둔다.
- 다른 Goal의 상태 전이를 미리 구현하지 않는다.
- token 원문, activation token 원문, 민감 로그 원문은 DB에 저장하지 않는다.

## Repository 사용 기준

- 기존 `agent.persistence` Entity/Repository 패턴을 따른다.
- schema에 없는 table/column/constraint를 임의로 추가하지 않는다.
- migration이 필요한 경우 기존 Flyway naming을 따른다.

현재 naming 예:

```text
V56__pc_agent_gold_mode_contract.sql
V57__agent_idempotency_records.sql
```

## AgentPrincipal 사용 방식

`AgentPrincipal` 필드 의미는 [SECURITY.md](SECURITY.md)를 따른다.

- `deviceInternalId`: 내부 DB scope용
- `deviceId`: 외부에 노출 가능한 public id 문자열
- `userInternalId`: 사용자 소유권 연결용
- `status`: Agent device 상태

Agent API에서 웹 `CurrentUserService.CurrentUser`와 섞지 않는다.

## Idempotency-Key 적용 방식

Controller가 직접 `Idempotency-Key` 저장 로직을 구현하지 않는다. 현재 공통 filter가 `/api/agent/**` mutation에 적용된다.

새 mutation API는 다음 정책을 만족해야 한다.

- 인증 실패는 idempotency 검사보다 먼저 `401`
- 인증 성공 후 key 누락은 `400`
- 같은 key와 같은 request body는 중복 처리 방지 또는 replay
- 같은 key와 다른 request body는 `409`
- 서로 다른 Agent는 같은 key를 사용할 수 있음

## 예외 처리 방식

공통 실패 응답은 `ApiErrorResponse(code, message)` 형태다.

실제 파일:

- `apps/api/src/main/java/com/buildgraph/prototype/common/ApiExceptionHandler.java`
- `apps/api/src/main/java/com/buildgraph/prototype/common/ApiErrorResponse.java`
- `apps/api/src/main/java/com/buildgraph/prototype/config/security/SecurityErrorResponseWriter.java`

현재 주요 code:

| HTTP | code |
|---:|---|
| 400 | `VALIDATION_ERROR` |
| 401 | `UNAUTHORIZED` |
| 403 | `FORBIDDEN` |
| 404 | `NOT_FOUND` |
| 409 | `CONFLICT_STATE` |
| 428 | `PRECONDITION_REQUIRED` |
| 502 | `UPSTREAM_ERROR` |
| 500 | `INTERNAL_ERROR` |

## 테스트 작성 방식

기존 테스트 스타일은 다음 파일을 따른다.

- `apps/api/src/test/java/com/buildgraph/prototype/config/security/AgentSecurityChainTest.java`
- `apps/api/src/test/java/com/buildgraph/prototype/config/security/AgentIdempotencyServiceTest.java`
- `apps/api/src/test/java/com/buildgraph/prototype/agent/persistence/AgentIdempotencyMigrationContractTest.java`
- `apps/api/src/test/java/com/buildgraph/prototype/agent/persistence/AgentAsJpaMappingTest.java`

테스트 기준:

- Security/Filter는 `@WebMvcTest`와 test-only controller를 사용한다.
- Service는 mock repository와 AssertJ로 decision을 검증한다.
- migration은 SQL 파일 문자열 계약을 검증한다.
- Entity/Repository는 reflection 기반 매핑 테스트를 유지한다.
- production API가 아직 없으면 test-only controller/config로만 인증 통과 여부를 검증한다.

## 금지 사항

- `/api/agent/**` 에 웹 JWT 인증을 섞지 않는다.
- `CurrentUserService`를 Agent controller 인증에 사용하지 않는다.
- Goal 범위 밖 API를 만들지 않는다.
- Security Chain을 재설계하지 않는다.
- Idempotency 인프라를 임의로 우회하지 않는다.
- `stash@{0}`를 pop/apply/drop 하지 않는다.
- 기존 테스트를 삭제하거나 기대값을 약화하지 않는다.
- 계약 문서를 임의로 바꾸지 않는다. 계약 수정이 필요하면 확인 필요로 보고한다.

## 커밋 전 체크리스트

```text
구현 전:
- README.md를 읽었는가?
- SECURITY.md를 읽었는가?
- IDEMPOTENCY.md를 읽었는가?
- GOAL_BOUNDARIES.md를 읽었는가?
- 본인 Goal 범위만 이해했는가?

구현 중:
- /api/agent/** 에 웹 JWT를 섞지 않았는가?
- AgentPrincipal을 사용했는가?
- mutation API에 Idempotency-Key를 적용했는가?
- 범위 밖 API를 만들지 않았는가?
- DB migration이 필요한 경우 기존 naming을 따랐는가?

구현 후:
- 관련 테스트를 추가했는가?
- 기존 테스트가 깨지지 않았는가?
- git diff --check를 통과했는가?
- 완료 보고를 남겼는가?
```

## 확인 필요

- 현재 계약 문서의 일부 Agent/RAG session API path가 `/api/agent/**` 아래에 남아 있다. 이 path는 현재 PC Agent 전용 Security Chain과 충돌할 수 있으므로 다음 구현 전에 계약 조정이 필요하다.
- 대용량 upload API는 현재 request body caching 방식의 메모리 비용을 검토해야 한다.
