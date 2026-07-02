# Agent AS 구현 기준 문서

## 서비스 요약

경량 PC Agent가 사용자 PC 상태를 로컬에서 수집하고, 사용자 동의 후 최근 로그를 서버로 업로드해 웹 AS 페이지와 관리자 검토로 이어지는 AS 진단 서비스다.

`/api/agent/**` 는 실제 PC Agent 전용 API prefix다. 웹 JWT와 Agent token은 혼용하지 않는다. Agent는 로그 수집과 트리거만 담당하고, 진단 결과 확인과 AS 진행 상태 확인은 웹 AS 페이지 또는 웹 조회 API에서 한다.

## 전체 AS 흐름

```text
Mock 구매
→ Agent 설치/등록
→ Agent가 로컬에서 5초 단위 로그 저장
→ 이상 감지 또는 사용자 문제 신고
→ 사용자 동의 후 최근 30분 로그 업로드
→ 서버가 AS 티켓 생성
→ 웹 AS 페이지 오픈
→ 서버 AI가 로그 분석
→ 사용자에게 자가 조치 안내
→ 관리자 검토 후 원격/방문지원 확정
```

## Goal 목록과 현재 상태

| Goal | 이름 | 상태 |
|---:|---|---|
| 1 | DB migration + Entity/Repository | 완료 |
| 2 | Agent 인증 체계 + Security Chain | 완료 |
| 3 | Idempotency-Key 인프라 | 완료 |
| 4 | Agent Register + Consent | 다음 구현 |
| 5 | Agent Heartbeat | Goal 4 이후 |
| 6 | Agent Log Upload + AS Diagnosis Start | Goal 5 이후 |
| 7 | 원격지원/방문지원 API | Goal 6 이후 |
| 8 | 최종 E2E/Demo 검증 | 마지막 통합 검증 |

## 다음 구현 순서

1. Goal 4: Agent 등록과 사용자 동의 저장을 구현한다.
2. Goal 5: 등록된 Agent의 heartbeat를 구현한다.
3. Goal 6: Agent 로그 업로드와 AS Diagnosis 시작 흐름을 구현한다.
4. Goal 7: 관리자 원격지원/방문지원 결정을 구현한다.
5. Goal 8: 전체 happy path와 데모 안정성을 검증한다.

Goal 4~8 담당자는 자기 Goal 외 기능을 미리 구현하지 않는다.

## 반드시 읽을 문서 순서

1. [README.md](README.md)
2. [SECURITY.md](SECURITY.md)
3. [IDEMPOTENCY.md](IDEMPOTENCY.md)
4. [GOAL_BOUNDARIES.md](GOAL_BOUNDARIES.md)
5. [IMPLEMENTATION_GUIDE.md](IMPLEMENTATION_GUIDE.md)
6. [E2E_HAPPY_PATH.md](E2E_HAPPY_PATH.md)
7. [TEAM_HANDOFF.md](TEAM_HANDOFF.md)
8. [AGENT_IMPLEMENTATION_SKILL.md](AGENT_IMPLEMENTATION_SKILL.md)

기존 계약 문서도 함께 확인한다.

- [API_CONTRACT.md](../API_CONTRACT.md)
- [DB_SCHEMA.md](../DB_SCHEMA.md)
- [ROUTE_OWNERSHIP.md](../ROUTE_OWNERSHIP.md)
- [openapi.yaml](../openapi.yaml)

## 실제 핵심 파일 경로

현재 repo에서 확인한 기준 파일은 다음과 같다.

| 구분 | 실제 경로 |
|---|---|
| AgentDevice Entity | `apps/api/src/main/java/com/buildgraph/prototype/agent/persistence/AgentDeviceEntity.java` |
| AgentDeviceRepository | `apps/api/src/main/java/com/buildgraph/prototype/agent/persistence/AgentDeviceRepository.java` |
| AgentPrincipal | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentPrincipal.java` |
| AgentAuthenticationToken | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentAuthenticationToken.java` |
| AgentSecurityConfig | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentSecurityConfig.java` |
| AgentTokenAuthenticationService | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentTokenAuthenticationService.java` |
| Agent access token filter | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentAccessTokenFilter.java` |
| Agent token hasher | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentTokenHasher.java` |
| Idempotency Entity | `apps/api/src/main/java/com/buildgraph/prototype/agent/persistence/AgentIdempotencyRecordEntity.java` |
| Idempotency Repository | `apps/api/src/main/java/com/buildgraph/prototype/agent/persistence/AgentIdempotencyRecordRepository.java` |
| Idempotency Service | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentIdempotencyService.java` |
| Idempotency Filter | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentIdempotencyFilter.java` |
| Idempotency Key Extractor | `apps/api/src/main/java/com/buildgraph/prototype/config/security/AgentIdempotencyKeyExtractor.java` |
| Idempotency request wrapper | `apps/api/src/main/java/com/buildgraph/prototype/config/security/CachedBodyHttpServletRequest.java` |
| 공통 예외 처리 | `apps/api/src/main/java/com/buildgraph/prototype/common/ApiExceptionHandler.java` |
| 공통 실패 응답 | `apps/api/src/main/java/com/buildgraph/prototype/common/ApiErrorResponse.java` |
| Agent/AS DB migration | `apps/api/src/main/resources/db/migration/V56__pc_agent_gold_mode_contract.sql` |
| Idempotency DB migration | `apps/api/src/main/resources/db/migration/V57__agent_idempotency_records.sql` |
| Agent 보안 테스트 | `apps/api/src/test/java/com/buildgraph/prototype/config/security/AgentSecurityChainTest.java` |
| Idempotency 서비스 테스트 | `apps/api/src/test/java/com/buildgraph/prototype/config/security/AgentIdempotencyServiceTest.java` |
| Idempotency migration 테스트 | `apps/api/src/test/java/com/buildgraph/prototype/agent/persistence/AgentIdempotencyMigrationContractTest.java` |
| JPA 매핑 테스트 | `apps/api/src/test/java/com/buildgraph/prototype/agent/persistence/AgentAsJpaMappingTest.java` |
| 진행 상태 문서 | `C:\나만무\status.md` |

## 확인 필요

- AI Agent/RAG session API는 웹 JWT 영역인 `/api/ai/agent-sessions`로 분리했다. `/api/agent/**`는 PC Agent token 전용 prefix로 유지한다.
- `POST /api/agent/devices/register`는 Agent token 발급 전 단계일 가능성이 높다. 현재 Agent Security Chain은 `/api/agent/**` 전체를 인증 요구로 처리하므로, Goal 4에서 등록 API의 인증 전 단계 처리 방식을 명확히 결정해야 한다.
- `POST /api/agent-logs/upload`는 현재 계약상 웹 JWT 사용자 API다. PC Agent 직접 업로드 API를 `/api/agent/**` 로 새로 둘지, 기존 경로를 유지할지 Goal 6 전에 계약 확인이 필요하다.
