# 3번 Agent/RAG/Tool 담당 2주 계획

## 사전 확인 문서

개발 시작 전 아래 문서를 먼저 읽는다.

1. `docs/API_CONTRACT.md`
2. `docs/DB_SCHEMA.md`
3. `docs/ROUTE_OWNERSHIP.md`
4. `docs/openapi.yaml`
5. `docs/architecture.md`
6. `docs/sprint-1-start-checklist.md`
7. `docs/owner-3-agent-rag-collaboration-guide.md`

## 담당 화면과 기능 범위

| 구분 | 담당 범위 |
|---|---|
| 관리자 화면 | `/admin/agent-sessions/:id`, `/admin/tool-invocations/:id`, `/admin/rag-evidence/:id` |
| 백엔드 패키지 | `apps/api/src/main/java/com/buildgraph/prototype/agent`, `apps/api/src/main/java/com/buildgraph/prototype/rag` |
| DB 테이블 | `agent_sessions`, `tool_invocations`, `rag_evidence` |
| API | `POST /api/ai/agent-sessions`, `POST /api/ai/agent-sessions/{id}/run`, `GET /api/ai/agent-sessions/{id}`, `GET /api/rag/search`, `GET /api/rag/evidence/{id}`, admin Agent/RAG/Tool 상세 API |
| 협업 지점 | 1번 추천 API, 2번 Tool 계산 결과, 4번 AS 분석 트리거, 5번 AdminShell/Auth |

3번의 핵심 책임은 추천이나 AS 결과 자체를 대신 만드는 것이 아니라, Agent 실행 과정에서 어떤 RAG 근거와 Tool 결과를 사용했는지 추적 가능하게 저장하고 관리자 화면에서 확인할 수 있게 만드는 것이다.

## 와이어프레임 기준 작업 화면

3번이 직접 구현 책임을 가지는 메인 와이어프레임은 `shop_spec_real_screens_v3_no_handoff.png` 기준 **3행 4번째 화면**이다. 화면 상단 path는 `/admin/agent-sessions/:id`이고, 왼쪽 사이드바에서 **Agent 세션** 메뉴가 선택된 관리자 화면이다.

| 와이어프레임 위치 | 화면에서 보이는 영역 | route | 구현 파일 | 3번 작업 내용 | 연결 API |
|---|---|---|---|---|---|
| 3행 4번째 관리자 Agent 세션 상세 화면 | 왼쪽 큰 패널 `Agent 실행 Trace` | `/admin/agent-sessions/:id` | `apps/web/src/features/admin/pages/AgentSessionAdminPage.tsx` | Agent 실행 단계, 단계별 상태, 비용, 결과를 실제 API 데이터로 표시 | `GET /api/admin/agent-sessions/{id}` |
| 3행 4번째 관리자 Agent 세션 상세 화면 | 오른쪽 위 패널 `RAG Evidence` | `/admin/agent-sessions/:id` 안의 근거 목록 또는 `/admin/rag-evidence/:id` 상세 | `apps/web/src/features/admin/pages/RagEvidenceAdminPage.tsx` | source_id, summary, confidence/score, chunkText, metadata를 표시 | `GET /api/admin/rag-evidence/{id}` |
| 3행 4번째 관리자 Agent 세션 상세 화면 | 오른쪽 아래 검은 코드 박스 `Tool 응답 JSON` | `/admin/agent-sessions/:id` 안의 Tool 결과 또는 `/admin/tool-invocations/:id` 상세 | `apps/web/src/features/admin/pages/ToolInvocationAdminPage.tsx` | Tool 이름, status, confidence, requestPayload, resultPayload, latency를 표시 | `GET /api/admin/tool-invocations/{id}` |
| 3행 4번째 관리자 Agent 세션 상세 화면 | 왼쪽 사이드바 `Tool 이력`, `RAG 근거` 메뉴 | `/admin/tool-invocations/:id`, `/admin/rag-evidence/:id` | 위 상세 page 파일 | 와이어프레임의 상세 패널을 별도 drill-down 화면으로 구현 | 각 admin 상세 API |

현재 와이어프레임 이미지에는 `Tool Invocation 상세`과 `RAG Evidence 상세`가 완전히 독립된 큰 프레임으로 따로 그려진 것이 아니라, **3행 4번째 `/admin/agent-sessions/:id` 화면의 오른쪽 패널과 drill-down 메뉴로 표현되어 있다.** 따라서 구현 우선순위도 독립 화면부터 새로 꾸미는 것이 아니라, 먼저 `Agent 실행 Trace`, `RAG Evidence`, `Tool 응답 JSON` 세 패널이 실제 API 데이터로 채워지게 만드는 것이다.

3번이 직접 만들지는 않지만 협업해야 하는 와이어프레임 지점은 아래와 같다.

| 와이어프레임 위치 | 협업 화면 | 주 담당 | 3번이 제공할 것 |
|---|---|---|---|
| 1행 2번째 `/requirements/new` | AI 견적 입력 / 추가 질문 | 1번 | Agent session 생성과 실행 API 계약, 진행 상태 조회 방식 |
| 1행 3번째 `/builds/:id` | 추천 Build 결과의 `RAG evidence 4개`, `Tool 검증 완료` 배지와 우측 `견적 합계 / 검증` 패널 | 1번 | `evidenceIds`, `toolInvocationIds`, RAG 근거 상세 링크/조회 API |
| 2행 1번째 `/builds/:id/compare` | 부품 변경 제안의 오른쪽 `근거/경고 패널` | 1번/2번 | 변경 전후 설명에 사용할 RAG/Tool trace 저장 방식 |
| 2행 3번째 `/support/new` | `PC Agent 로그 미리보기`, `도움말/상태` | 4번 | `asTicketId` 기반 `AS_ANALYZE` Agent session 생성 방식 |
| 2행 4번째 `/support/tickets/:id` | 오른쪽 `로그 요약 / 추천 조치` 패널 | 4번 | 원인 후보와 업그레이드 후보가 참조할 `rag_evidence` id 제공 방식 |
| 3행 3번째 `/admin` | `최근 Agent 세션`, `관리자 할 일` 표 | 5번 | Agent session 상태와 RAG/Tool 요약 데이터 |

이번 2주 안에 3번 화면은 완성형 UI가 아니라, 와이어프레임에서 약속한 정보 구조가 실제 API 데이터로 채워지는 수준을 목표로 한다. 색상, 여백, 세부 시각 개선은 AdminShell과 공통 컴포넌트 기준을 따른다.

## 기능 단위 진행 현황

바이브/AI 보조 개발을 전제로 하므로 하루에 하나씩 나누지 않는다. 아래 순서대로 기능 단위를 작게 커밋하고, 통과한 단위는 바로 다음 단위로 넘어간다.

| 우선순위 | 기능 단위 | 상태 | 현재 산출물 | 남은 작업 |
|---:|---|---|---|---|
| 1 | 공통 계약 문서 확인 | 완료 | `API_CONTRACT`, `DB_SCHEMA`, `ROUTE_OWNERSHIP` 기준 확인 | 계약 변경 시 문서 먼저 갱신 |
| 2 | 담당 와이어프레임 범위 확정 | 완료 | 관리자 Agent/RAG/Tool 상세 화면 3개와 협업 화면 분리 | Notion 공유 후 팀 피드백 반영 |
| 3 | Agent session 기본 흐름 | 완료 | `POST /api/ai/agent-sessions`, root 구분, 목적 타입, `QUEUED -> RUNNING` | 인증/소유권 검증은 5번 공통 정책과 맞춘 뒤 보강 |
| 4 | RAG 근거 기록 기반 | 완료 | `AgentTraceService.recordRagEvidence`, `AgentRagEvidenceDraft` | 실제 runner에서 호출해 세션별 evidence 생성 |
| 5 | Tool 호출 기록 기반 | 완료 | `AgentTraceService.recordToolInvocation`, `AgentToolInvocationDraft` | 2번 Tool 결과 DTO와 payload shape 최종 합의 |
| 6 | Agent 상태 전이 공통화 | 완료 | `AgentTraceService.advanceStatus`, 허용 전이/금지 전이 검증 | runner와 화면에서 상태 전이 결과 사용 |
| 7 | 목적별 mock Agent runner | 완료 | `AgentMockRunService`, 목적별 RAG/Tool 기록, `SUCCEEDED` 완료 흐름 | 실제 LLM/RAG 연동 시 교체 가능한 경계 유지 |
| 8 | 관리자 Agent 상세 화면 API 연결 | 완료 | `AgentSessionAdminPage`가 Agent session/RAG evidence API 데이터로 패널 표시 | Tool/RAG drill-down 상세 화면과 UX 맞추기 |
| 9 | Tool/RAG 상세 화면 API 연결 | 완료 | `ToolInvocationAdminPage`, `RagEvidenceAdminPage`가 관리자 상세 API 데이터 표시 | 테스트/계약 검증에서 route smoke 유지 |
| 10 | 테스트와 계약 검증 | 완료 | OpenAPI, backend bootJar, frontend build/test, 정식 Docker compose smoke 통과 | PR 전 같은 명령 재실행 |
| 11 | 협업 인터페이스 문서화 | 완료 | `docs/owner-3-agent-rag-collaboration-guide.md` 추가 | 팀 피드백 반영 |
| 12 | LLM summary 생성 연결 | 완료 | `AgentRunner`, `LlmAgentRunner`, `OpenAiResponsesClient`, LLM 실행 환경변수 추가, 실제 LLM smoke 통과 | 2번 Tool/4번 AS 실제 결과와 prompt context 연결 |

## 2026-06-29 검증 기록

| 구분 | 명령 / 방식 | 결과 |
|---|---|---|
| OpenAPI 계약 | `python tools/validate_openapi.py` | `OpenAPI validation passed: 42 paths` |
| Compose 설정 | `docker compose config` | compose 설정 출력 성공 |
| 백엔드 빌드 | `apps/api/gradlew.bat bootJar --no-daemon` | `BUILD SUCCESSFUL` |
| 프론트 빌드 | `npm --prefix apps/web run build` | TypeScript/Vite build 통과 |
| 프론트 라우트 테스트 | `npm --prefix apps/web run test` | `31 passed` |
| Docker 기동 | 기존 `prototype` compose 스택을 정상 종료한 뒤 `hyunjin` 폴더에서 `docker compose down -v --remove-orphans; docker compose up --build -d` 실행 | 기본 포트 `8080`, `5173`, `5432`, `6379`, `5672`, `15672`, `1025`, `8025`로 Postgres, Redis, RabbitMQ, Mailpit, API, Web 시작 성공 |
| API health | `GET /api/health` | `status=UP`, `database=UP` |
| Web smoke | `GET http://localhost:5173` | HTTP `200` |
| Agent smoke | 세션 생성 -> 실행 -> 상세 조회 | `RUNNING` 응답 후 최종 `SUCCEEDED`, Tool 5개, RAG evidence 1개 확인 |
| Admin smoke | 관리자 Agent/Tool/RAG 상세 API 조회 | 첫 Tool `compatibility / PASS`, RAG source `internal-rule-qhd-gaming-mock` 확인 |

## 2026-06-29 LLM 연결 진행 기록

| 구분 | 결과 |
|---|---|
| 실행 경계 | `AgentRunner` 인터페이스로 분리 완료 |
| 기본 runner | `DeterministicAgentRunner`로 유지, 키 없이 Docker 기본 실행 가능 |
| LLM runner | `AGENT_RUNNER_MODE=llm`일 때 `LlmAgentRunner`가 OpenAI Responses API로 summary 생성 |
| OpenAI client | `OpenAiResponsesClient` 추가, `OPENAI_API_KEY`, `OPENAI_MODEL`, `OPENAI_BASE_URL` 환경변수 사용 |
| 검증 완료 | 키 없이 기본 `deterministic` mode에서 Docker compose, API health, Agent smoke, Admin smoke 통과 |
| 실제 LLM smoke | 로컬 `.env`에 `OPENAI_API_KEY`와 `AGENT_RUNNER_MODE=llm`을 넣고 Docker API 재빌드 후 Agent 실행 성공 |
| LLM 결과 | `RUNNING -> RAG_SEARCHED -> TOOLS_CALLED -> SUMMARY_READY -> SUCCEEDED`, Tool 5개, RAG evidence 1개, 한국어 summary 저장 확인 |
| 남은 연결 | 실제 embedding 검색과 2번 Tool 계산 결과를 prompt context에 연결 |

## 빠른 완성 순서

남은 구현은 아래 순서로 진행한다.

1. `AgentTraceService`에 상태 전이 공통 메서드를 추가한다.
2. `runSession`이 직접 DB update하지 않고 상태 전이 공통 메서드를 사용하게 정리한다.
3. 목적별 mock runner를 만들어 `RAG_SEARCHED`, `TOOLS_CALLED`, `SUMMARY_READY`, `SUCCEEDED`까지 한 번에 흐르게 한다.
4. mock runner에서 `recordRagEvidence`, `recordToolInvocation`을 호출해 세션 상세에 evidence/tool id가 실제로 생기게 한다.
5. 관리자 Agent 상세 화면을 API 데이터로 연결한다.
6. Tool Invocation 상세와 RAG Evidence 상세 화면을 API 데이터로 연결한다.
7. 1번 추천 API, 2번 Tool 결과, 4번 AS ticket 분석 트리거가 3번 trace를 어떻게 호출할지 예시 코드를 문서화한다.
8. `bootJar`, 프론트 빌드, route smoke, OpenAPI 검증을 통과시킨다.

## 협업 확인 포인트

| 상대 담당 | 확인할 내용 |
|---|---|
| 1번 | `POST /api/builds/recommend` 내부에서 Agent trace service를 어떤 순서로 호출할지 |
| 2번 | Tool 결과 DTO의 `status`, `confidence`, `summary`, `requestPayload`, `resultPayload` shape |
| 4번 | AS ticket 생성 후 `AS_ANALYZE` Agent session을 언제 만들고 실행할지 |
| 5번 | 관리자 route guard, AdminShell, 권한 오류 처리, 공통 API client 사용 방식 |

## 이번 2주 목표

2주 종료 시점에는 실제 LLM 품질보다 다음을 우선 완료한다.

- Agent 실행 세션이 생성되고 상태가 추적된다.
- RAG 근거와 Tool 호출 이력이 세션에 연결되어 저장된다.
- 관리자 화면에서 Agent timeline, Tool payload, RAG 근거 chunk를 확인할 수 있다.
- 1번 추천 흐름과 4번 AS 흐름이 같은 Agent/RAG/Tool trace 구조를 사용할 수 있다.
