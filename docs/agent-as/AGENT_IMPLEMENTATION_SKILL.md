# Agent AS 구현 AI 규칙

이 문서는 Codex 또는 다른 AI Agent에게 매번 붙여서 사용할 구현 규칙이다.

```text
이 문서를 읽은 AI Agent는 절대 Goal 범위 밖 기능을 선구현하지 않는다.
계약이 불명확하면 추측 구현하지 않고 확인 필요 항목으로 보고한다.
```

## 작업 시작 전 필수 확인

1. `git status --short`를 확인한다.
2. 현재 Goal과 무관한 변경이 있으면 임의로 수정하지 않는다.
3. `stash@{0}`는 pop/apply/drop 하지 않는다.
4. 현재 Goal 문서를 읽는다.
5. 실제 파일 경로를 확인하고 추측으로 문서나 코드를 작성하지 않는다.

## 반드시 읽을 문서

1. `docs/agent-as/README.md`
2. `docs/agent-as/SECURITY.md`
3. `docs/agent-as/IDEMPOTENCY.md`
4. `docs/agent-as/GOAL_BOUNDARIES.md`
5. `docs/agent-as/IMPLEMENTATION_GUIDE.md`
6. `docs/API_CONTRACT.md`
7. `docs/DB_SCHEMA.md`
8. `docs/ROUTE_OWNERSHIP.md`
9. `docs/openapi.yaml`

## 불변 원칙

- `/api/agent/**` 는 실제 PC Agent 전용 API다.
- `/api/agent/**` 는 Agent token만 허용한다.
- 웹 JWT와 Agent token은 혼용하지 않는다.
- Agent token은 `agent_devices.agent_token_hash` 기반으로 검증한다.
- Agent 인증 성공 시 `AgentPrincipal`을 사용한다.
- 웹 사용자 인증 객체와 Agent 인증 객체를 섞지 않는다.
- mutation Agent API는 `Idempotency-Key`를 요구한다.
- 인증 실패는 idempotency 검사보다 먼저 `401`이어야 한다.

## 구현 금지

현재 Goal에 포함되지 않은 기능은 구현하지 않는다.

- Agent Register API 선구현 금지
- Consent API 선구현 금지
- Heartbeat API 선구현 금지
- Log Upload API 선구현 금지
- AS Ticket 생성 선구현 금지
- Diagnosis 선구현 금지
- 원격지원/방문지원 API 선구현 금지
- 웹 화면 선구현 금지
- Agent exe 로직 선구현 금지
- Security Chain 재설계 금지
- Idempotency 로직 임의 변경 금지
- DB migration 임의 추가 금지
- 테스트 삭제 또는 기대값 완화 금지

## TDD 순서

1. 현재 Goal의 필수 테스트를 먼저 작성한다.
2. 실패를 확인한다.
3. 최소 구현을 한다.
4. 테스트를 통과시킨다.
5. 필요한 경우에만 작은 리팩터링을 한다.
6. 기존 Agent Security 테스트와 Idempotency 테스트를 다시 확인한다.

## 중단 조건

다음 중 하나라도 발생하면 코드를 고치지 말고 중단 보고를 남긴다.

- 현재 Goal과 직접 관련 없는 파일 수정이 필요하다.
- 계약 문서와 구현 방향이 충돌한다.
- `/api/agent/**`에 웹 JWT를 섞어야만 구현할 수 있다.
- 기존 테스트를 삭제하거나 약화해야 통과한다.
- DB_SCHEMA에 없는 table/column/constraint가 필요하다.
- openapi.yaml과 구현 방향이 충돌한다.
- 외부 API, secret, 배포 설정 변경이 필요하다.
- 같은 실패 원인에 대한 수정 시도가 5회를 넘었다.

## 완료 보고 기준

완료 보고에는 다음을 포함한다.

- 변경 파일 목록
- 추가한 테스트 목록
- 인증/Idempotency 적용 방식
- 실행한 검증 명령과 결과
- Goal 범위 밖이라 구현하지 않은 항목
- 확인 필요 항목
- 커밋 메시지 제안

## 현재 기준 핵심 파일

- `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentSecurityConfig.java`
- `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentAccessTokenFilter.java`
- `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentTokenAuthenticationService.java`
- `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentPrincipal.java`
- `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentAuthenticationToken.java`
- `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentIdempotencyFilter.java`
- `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentIdempotencyService.java`
- `apps/api/src/main/java/com/buildgraph/prototype/agent/persistence/AgentDeviceEntity.java`
- `apps/api/src/main/java/com/buildgraph/prototype/agent/persistence/AgentIdempotencyRecordEntity.java`
- `apps/api/src/main/resources/db/migration/V56__pc_agent_gold_mode_contract.sql`
- `apps/api/src/main/resources/db/migration/V57__agent_idempotency_records.sql`
