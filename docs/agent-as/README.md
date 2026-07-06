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
| 4 | Agent Register + Consent | 최소 데모 구현/QA 완료 |
| 5 | Agent Heartbeat | 최소 데모 구현/QA 완료 |
| 6 | Agent Log Upload + AS Diagnosis Start | gzip upload + ticket 생성 QA 완료 |
| 7 | 원격지원/방문지원 API | `supportDecision` 저장/노출 QA 완료, 운영 고도화 남음 |
| 8 | 최종 E2E/Demo 검증 | runtime happy path QA 완료 |

## 현재 main 기준 구현/검증 상태

`50065bac5b26f62aaf68986dbe9039171fe3c547` 기준으로 다음 흐름은 QA에서 확인됐다.

1. 서버 실행과 웹 실행
2. Agent config 준비
3. Agent `status`
4. Agent `register`
5. Agent `status` 재확인
6. consent accepted 처리
7. heartbeat 성공
8. demo log 생성
9. gzip upload
10. `ticketId` 반환
11. 사용자 `/support/{ticketId}` 조회
12. 관리자 `supportDecision` 저장
13. 사용자 화면에서 `supportDecision` 반영 확인
14. PC Agent 개발용 exe 다운로드 링크 노출

## 다음 보강 순서

1. 실제 activation token 발급/만료/재발급 정책을 구매/설치 코드와 연결한다.
2. Windows Service, tray app, installer, auto-update를 개발용 exe 이후 단계로 구현한다.
3. 원격지원/방문지원 운영 UI와 예약/세션 상세를 고도화한다.
4. 대용량 로그 streaming, storage hardening, 보관 만료 삭제 자동화를 구현한다.
5. 기존 웹 JWT 기반 `/api/agent/sessions` 계약과 PC Agent 전용 `/api/agent/**` prefix 충돌을 정리한다.

## 반드시 읽을 문서 순서

1. [README.md](README.md)
2. [SECURITY.md](SECURITY.md)
3. [IDEMPOTENCY.md](IDEMPOTENCY.md)
4. [GOAL_BOUNDARIES.md](GOAL_BOUNDARIES.md)
5. [IMPLEMENTATION_GUIDE.md](IMPLEMENTATION_GUIDE.md)
6. [E2E_HAPPY_PATH.md](E2E_HAPPY_PATH.md)
7. [TEAM_HANDOFF.md](TEAM_HANDOFF.md)
8. [DEPLOYMENT_RISKS.md](DEPLOYMENT_RISKS.md)
9. [AGENT_IMPLEMENTATION_SKILL.md](AGENT_IMPLEMENTATION_SKILL.md)

기존 계약 문서도 함께 확인한다.

- [API_CONTRACT.md](../API_CONTRACT.md)
- [DB_SCHEMA.md](../DB_SCHEMA.md)
- [ROUTE_OWNERSHIP.md](../ROUTE_OWNERSHIP.md)
- [openapi.yaml](../openapi.yaml)

## 데모/QA runbook

- [DEMO_RUNBOOK.md](DEMO_RUNBOOK.md): 데모 계정, 테스트 데이터 준비, 관리자 `supportDecision` 저장 후 사용자 화면 반영 QA, 다운로드 링크 상태, OpenAPI 검증, 운영 장애 대응, 발표 시나리오를 정리한다.

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
| Agent/AS DB migration | `apps/api/src/main/resources/db/migration/V53__pc_agent_gold_mode_contract.sql` |
| Idempotency DB migration | `apps/api/src/main/resources/db/migration/V54__agent_idempotency_records.sql` |
| Agent 보안 테스트 | `apps/api/src/test/java/com/buildgraph/prototype/config/security/AgentSecurityChainTest.java` |
| Idempotency 서비스 테스트 | `apps/api/src/test/java/com/buildgraph/prototype/config/security/AgentIdempotencyServiceTest.java` |
| Idempotency migration 테스트 | `apps/api/src/test/java/com/buildgraph/prototype/agent/persistence/AgentIdempotencyMigrationContractTest.java` |
| JPA 매핑 테스트 | `apps/api/src/test/java/com/buildgraph/prototype/agent/persistence/AgentAsJpaMappingTest.java` |
| 진행 상태 문서 | `C:\나만무\status.md` |

## 확인 필요

- 현재 [API_CONTRACT.md](../API_CONTRACT.md)와 [openapi.yaml](../openapi.yaml)에는 기존 웹 JWT 기반 `/api/agent/sessions` 계약이 남아 있다. 현재 구현 원칙은 `/api/agent/**` 를 PC Agent token 전용으로 처리하므로, 기존 AI Agent/RAG session API를 `/api/ai/**` 등 웹 JWT 영역으로 분리할지 계약 정리가 필요하다.
- `POST /api/agent/devices/register`는 Agent token 발급 전 단계라 현재 구현에서 `permitAll` pre-auth 등록 API로 고정했다. 운영 전에는 activation token 발급 주체와 재발급 UX를 연결해야 한다.
- PC Agent 직접 업로드는 현재 `/api/agent/log-uploads`를 사용한다. `POST /api/agent-logs/upload`는 웹 fallback AS 접수 흐름으로 남아 있으므로 두 경로의 역할을 계속 분리해야 한다.
