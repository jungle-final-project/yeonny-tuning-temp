# Goal 범위 기준

## 공통 규칙

- 현재 Goal 범위 밖 기능은 선구현하지 않는다.
- production code를 수정하기 전 실제 계약 문서와 구현 파일을 확인한다.
- `/api/agent/**` 는 PC Agent token 전용 API다.
- 웹 JWT와 Agent token은 섞지 않는다.
- mutation Agent API는 [IDEMPOTENCY.md](IDEMPOTENCY.md)를 따른다.
- 계약이 불명확하면 추측 구현하지 않고 확인 필요로 보고한다.

## Goal 1. DB migration + Entity/Repository

| 항목 | 기준 |
|---|---|
| 구현할 것 | Agent/AS 관련 Flyway migration, Entity, Enum, Repository |
| 구현하지 말 것 | API, Controller, Service, Security, UI |
| 의존하는 이전 Goal | 없음 |
| 반드시 읽을 문서 | [README.md](README.md), [DB_SCHEMA.md](../DB_SCHEMA.md) |
| 필수 테스트 | migration 계약 테스트, JPA Entity/Enum/Repository 매핑 테스트 |
| 완료 보고 기준 | migration 파일, Entity/Repository 목록, 테스트 결과, `git diff --check` |

## Goal 2. Agent 인증 체계 + Security Chain

| 항목 | 기준 |
|---|---|
| 구현할 것 | `AgentPrincipal`, `AgentAuthenticationToken`, Agent token 검증, `/api/agent/**` Security Chain |
| 구현하지 말 것 | Register, Consent, Heartbeat, Upload, AS API |
| 의존하는 이전 Goal | Goal 1 |
| 반드시 읽을 문서 | [SECURITY.md](SECURITY.md), [DB_SCHEMA.md](../DB_SCHEMA.md) |
| 필수 테스트 | token 없음 401, 잘못된 token 401, 유효 token 성공, 웹 JWT와 Agent token 분리 |
| 완료 보고 기준 | Security Chain 분리 방식, 테스트 결과, 기존 웹 JWT 테스트 영향 없음 |

## Goal 3. Idempotency-Key 인프라

| 항목 | 기준 |
|---|---|
| 구현할 것 | Agent idempotency 저장 구조, Entity/Repository/Service/Filter, request hash, replay/conflict 처리 |
| 구현하지 말 것 | 실제 Register/Heartbeat/Upload 등 production API |
| 의존하는 이전 Goal | Goal 1, Goal 2 |
| 반드시 읽을 문서 | [SECURITY.md](SECURITY.md), [IDEMPOTENCY.md](IDEMPOTENCY.md) |
| 필수 테스트 | mutation key 누락 400, invalid key 400, duplicate replay, hash conflict 409, Agent별 scope 분리 |
| 완료 보고 기준 | migration/Entity/Service/Filter 목록, request hash 방식, 테스트 결과 |

## Goal 4. Agent Register + Consent

| 항목 | 기준 |
|---|---|
| 구현할 것 | Agent 등록, Agent token 발급/저장, activation token 검증, 사용자 동의 저장 |
| 구현하지 말 것 | Heartbeat, Log Upload, AS Ticket 생성, AI Diagnosis, 원격/방문지원 |
| 의존하는 이전 Goal | Goal 1, Goal 2, Goal 3 |
| 반드시 읽을 문서 | [SECURITY.md](SECURITY.md), [IDEMPOTENCY.md](IDEMPOTENCY.md), [IMPLEMENTATION_GUIDE.md](IMPLEMENTATION_GUIDE.md) |
| 필수 테스트 | 등록 성공, 중복 등록 idempotency, token 원문 미저장, consent 저장, register pre-auth 정책 |
| 완료 보고 기준 | Register/Consent API 범위, Security 충돌 해결 방식, 테스트 결과 |

확인 필요: Register는 Agent token 발급 전 단계라 현재 `/api/agent/**` 인증 요구와 충돌할 수 있다. Goal 4는 구현 전에 Register 인증 방식을 먼저 테스트로 고정해야 한다.

## Goal 5. Agent Heartbeat

| 항목 | 기준 |
|---|---|
| 구현할 것 | 등록된 Agent heartbeat 수신, `last_seen_at` 또는 heartbeat table 저장, update/delete command 응답 |
| 구현하지 말 것 | Log Upload, AS Ticket 생성, Diagnosis, 원격/방문지원 |
| 의존하는 이전 Goal | Goal 4 |
| 반드시 읽을 문서 | [SECURITY.md](SECURITY.md), [IDEMPOTENCY.md](IDEMPOTENCY.md), [IMPLEMENTATION_GUIDE.md](IMPLEMENTATION_GUIDE.md) |
| 필수 테스트 | Agent token 필요, Idempotency-Key 필요, device scope 저장, inactive device 실패 |
| 완료 보고 기준 | heartbeat 저장/응답 필드, 테스트 결과, Goal 6 기능 미구현 확인 |

## Goal 6. Agent Log Upload + AS Diagnosis Start

| 항목 | 기준 |
|---|---|
| 구현할 것 | 사용자 동의 후 최근 30분 로그 업로드, upload job/log bundle 저장, AS Ticket 생성 또는 연결, Diagnosis 상태 시작 |
| 구현하지 말 것 | 실제 AI 분석 세부 로직 완성, 원격/방문지원 확정, Agent exe |
| 의존하는 이전 Goal | Goal 4, Goal 5 |
| 반드시 읽을 문서 | [IDEMPOTENCY.md](IDEMPOTENCY.md), [E2E_HAPPY_PATH.md](E2E_HAPPY_PATH.md), [DB_SCHEMA.md](../DB_SCHEMA.md) |
| 필수 테스트 | gzip/multipart validation, 동의 없을 때 실패, idempotent upload, ticket 생성, diagnosis 상태 생성 |
| 완료 보고 기준 | upload 저장 경로/DB 연결, ticket/diagnosis 생성 기준, 대용량 body hash 검토 결과 |

확인 필요: 현재 `/api/agent-logs/upload`는 웹 JWT 사용자 API 계약으로 남아 있다. PC Agent 직접 업로드 API path를 Goal 6 전에 확정해야 한다.

## Goal 7. 원격지원/방문지원 API

| 항목 | 기준 |
|---|---|
| 구현할 것 | 관리자 supportDecision 확정, 원격지원 세션 생성, 방문지원 예약 생성 |
| 구현하지 말 것 | 자체 원격제어 구현, 외부 원격툴 깊은 연동, Agent exe 기능 |
| 의존하는 이전 Goal | Goal 6 |
| 반드시 읽을 문서 | [E2E_HAPPY_PATH.md](E2E_HAPPY_PATH.md), [IMPLEMENTATION_GUIDE.md](IMPLEMENTATION_GUIDE.md), [API_CONTRACT.md](../API_CONTRACT.md) |
| 필수 테스트 | 관리자 권한, ticket 상태 전이, remote/visit 생성, audit log, 잘못된 상태 409 |
| 완료 보고 기준 | 관리자 결정 흐름, 원격/방문지원 DB 연결, 테스트 결과 |

## Goal 8. 최종 E2E/Demo 검증

| 항목 | 기준 |
|---|---|
| 구현할 것 | Goal 4~7 통합 happy path 검증, 데모 seed/fixture 안정화, smoke test 문서화 |
| 구현하지 말 것 | 새 기능 추가, Goal 4~7 범위 확장 |
| 의존하는 이전 Goal | Goal 4, Goal 5, Goal 6, Goal 7 |
| 반드시 읽을 문서 | 전체 `docs/agent-as/*.md`, [API_CONTRACT.md](../API_CONTRACT.md), [openapi.yaml](../openapi.yaml) |
| 필수 테스트 | 등록부터 관리자 supportDecision까지 E2E, 웹 JWT/Agent token 분리, idempotency replay, 기존 웹 API smoke |
| 완료 보고 기준 | 실행 명령, 성공 로그, 데모 시나리오, 남은 위험 |
