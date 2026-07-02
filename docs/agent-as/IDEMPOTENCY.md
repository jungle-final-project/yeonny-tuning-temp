# Agent Idempotency-Key 기준

## 필요한 이유

PC Agent는 네트워크 장애, 재시도, 프로세스 재시작으로 같은 mutation 요청을 다시 보낼 수 있다. `Idempotency-Key`는 같은 Agent가 같은 요청을 재시도했을 때 중복 처리를 막고, 같은 key로 다른 본문을 보내는 실수를 충돌로 막기 위한 인프라다.

## 실제 구현 파일

| 구분 | 실제 경로 |
|---|---|
| migration | `apps/api/src/main/resources/db/migration/V57__agent_idempotency_records.sql` |
| Entity | `apps/api/src/main/java/com/buildgraph/prototype/agent/persistence/AgentIdempotencyRecordEntity.java` |
| Repository | `apps/api/src/main/java/com/buildgraph/prototype/agent/persistence/AgentIdempotencyRecordRepository.java` |
| Service | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentIdempotencyService.java` |
| Filter | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentIdempotencyFilter.java` |
| Key extractor | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentIdempotencyKeyExtractor.java` |
| Decision record | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentIdempotencyDecision.java` |
| Request body wrapper | `apps/api/src/main/java/com/buildgraph/prototype/config/security/CachedBodyHttpServletRequest.java` |
| Security Chain 연결 | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentSecurityConfig.java` |
| migration 테스트 | `apps/api/src/test/java/com/buildgraph/prototype/agent/persistence/AgentIdempotencyMigrationContractTest.java` |
| service 테스트 | `apps/api/src/test/java/com/buildgraph/prototype/config/security/AgentIdempotencyServiceTest.java` |
| filter/security 테스트 | `apps/api/src/test/java/com/buildgraph/prototype/config/security/AgentSecurityChainTest.java` |

## 적용 대상

`/api/agent/**` 중 다음 HTTP method에 적용한다.

- `POST`
- `PUT`
- `PATCH`
- `DELETE`

다음 method에는 요구하지 않는다.

- `GET`
- `HEAD`
- `OPTIONS`

웹 API에는 적용하지 않는다.

## 헤더 형식

```http
Idempotency-Key: <client-generated-key>
```

현재 extractor 기준:

- 최대 길이: 160
- 허용 문자: `A-Z`, `a-z`, `0-9`, `.`, `_`, `:`, `-`
- 누락 또는 blank: `400 VALIDATION_ERROR`
- 허용 문자/길이 위반: `400 VALIDATION_ERROR`

## 반드시 지킬 정책

```text
인증 실패는 401이 먼저 나와야 한다.
인증 성공 후 Idempotency-Key 누락은 400이다.
같은 key로 다른 request body를 보내면 409다.
서로 다른 Agent가 같은 key를 쓰는 것은 충돌이 아니다.
```

## scope 기준

Idempotency scope는 다음 조합이다.

```text
AgentPrincipal.deviceInternalId
request method
request path
Idempotency-Key
```

DB unique constraint는 `agent_idempotency_records`의 `uq_agent_idempotency_scope`다.

```text
UNIQUE (agent_device_id, request_method, request_path, idempotency_key)
```

따라서 서로 다른 Agent가 같은 key를 사용해도 충돌하지 않는다.

## request hash 기준

`AgentIdempotencyFilter`는 `CachedBodyHttpServletRequest`로 요청 body를 byte 배열로 읽고 SHA-256 hex hash를 계산한다.

- body가 같은 요청은 같은 hash가 된다.
- body가 없는 mutation 요청도 빈 byte 배열 기준 hash를 가진다.
- 같은 scope에 기존 record가 있고 request hash가 다르면 `409 CONFLICT_STATE`다.

## 중복 요청 처리 방식

1. 인증이 먼저 성공해야 한다.
2. mutation method이면 `Idempotency-Key`를 검사한다.
3. body hash를 계산한다.
4. `AgentIdempotencyService.reserve(...)`가 record를 조회하거나 생성한다.
5. 새 record이면 controller로 진행한다.
6. controller 응답 status, content type, body를 저장한다.
7. 같은 scope와 같은 hash로 다시 들어오고 record가 `COMPLETED`이면 저장된 응답을 replay한다.
8. 같은 scope와 같은 hash지만 record가 `IN_PROGRESS`이면 `409 CONFLICT_STATE`다.

## 충돌 요청 처리 방식

같은 Agent, 같은 method, 같은 path, 같은 `Idempotency-Key`인데 request hash가 다르면 `409 CONFLICT_STATE`를 반환한다.

이는 같은 key로 다른 의미의 mutation을 보내는 것을 금지하기 위한 정책이다.

## 새 mutation API에서 적용하는 방법

일반적인 controller마다 별도 idempotency 코드를 작성하지 않는다. `/api/agent/**` Security Chain에 연결된 `AgentIdempotencyFilter`가 공통으로 처리한다.

새 Agent mutation API 작성자는 다음만 지킨다.

- path를 `/api/agent/**` 아래에 둔다.
- 인증 principal은 `AgentPrincipal`을 사용한다.
- 요청이 실제로 상태를 변경한다면 `POST`, `PUT`, `PATCH`, `DELETE` 중 적절한 method를 사용한다.
- 중복 요청 시 replay해도 안전한 응답 DTO를 반환한다.
- response body가 너무 크거나 파일 스트림이면 Goal 담당자가 별도 replay 정책을 문서화한다.

## 테스트 기준

필수 테스트 기준은 다음과 같다.

- Agent token 없음 + POST `/api/agent/**` -> `401`
- 유효한 Agent token + `Idempotency-Key` 없음 -> `400`
- 잘못된 `Idempotency-Key` 형식 -> `400`
- 같은 Agent + 같은 method + 같은 path + 같은 key + 같은 request hash -> 중복 처리 또는 replay
- 같은 Agent + 같은 key + 다른 request hash -> `409`
- 서로 다른 Agent가 같은 `Idempotency-Key` 사용 -> 충돌하지 않음
- GET `/api/agent/**` 는 `Idempotency-Key` 없이 통과
- 웹 JWT 보호 API에는 `Idempotency-Key` 정책이 적용되지 않음
- 기존 Agent Security 테스트가 유지됨

## 확인 필요

- Goal 6의 gzip/multipart 로그 업로드는 요청 body가 커질 수 있다. 현재 `CachedBodyHttpServletRequest`는 body 전체를 메모리에 올리므로, 대용량 업로드 API는 별도 hash 계산 방식이나 size 제한을 검토해야 한다.
- 현재 replay는 response body를 text로 저장한다. 바이너리, stream, multipart 응답이 필요한 Agent API가 생기면 replay 정책을 별도로 정해야 한다.
