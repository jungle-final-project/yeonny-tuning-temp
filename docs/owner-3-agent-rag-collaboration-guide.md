# 3번 Agent/RAG/Tool 협업 인터페이스 가이드

이 문서는 1번 추천, 2번 부품 Tool, 4번 AS/로그 분석, 5번 관리자 화면 담당자가 3번 Agent/RAG/Tool trace를 어떻게 연결할지 정리한 기준이다.

3번의 책임은 추천 결과나 AS 결과를 대신 계산하는 것이 아니라, 결과가 만들어지는 과정에서 사용된 입력, RAG 근거, Tool 호출 결과, 상태 전이를 추적 가능하게 남기는 것이다.

## 담당 경계

| 담당 | 직접 구현 책임 | 3번과 연결되는 지점 |
|---|---|---|
| 1번 추천/견적 | 요구사항 입력, 추천 Build 생성, 추천 결과 화면 | `requirementId` 또는 `buildId`로 Agent session 생성, 추천 근거와 Tool 결과 id를 화면에 표시 |
| 2번 부품/가격/Tool | 호환성, 전력, 규격, 성능, 가격 Tool 계산 | Tool 결과를 `AgentToolInvocationDraft` 형태로 넘겨 `tool_invocations`에 기록 |
| 3번 Agent/RAG | Agent session, 상태 전이, RAG evidence, Tool invocation trace | `AgentTraceService`와 Agent/Admin/RAG API 제공 |
| 4번 AS/로그 | PC Agent 로그 업로드, AS 티켓, 원인 후보, 업그레이드 후보 | `asTicketId`로 `AS_ANALYZE` Agent session 생성, 원인/업그레이드 근거를 evidence/tool trace에 연결 |
| 5번 사용자/관리자 | Auth, AdminShell, 권한, 운영 대시보드 | 관리자 화면에서 Agent/Tool/RAG 상세 API를 보호 라우트로 노출 |

## 외부 API 기준

프론트나 다른 모듈이 HTTP로 연결할 때는 아래 API를 사용한다.

| 목적 | Method | Path | 요청 핵심 | 응답 핵심 |
|---|---|---|---|---|
| Agent 세션 생성 | `POST` | `/api/ai/agent-sessions` | `requirementId`, `buildId`, `asTicketId` 중 정확히 1개 | `id`, `status=QUEUED`, `stateTimeline` |
| Agent 실행 시작 | `POST` | `/api/ai/agent-sessions/{id}/run` | body `{}` | 실행 시작 시점의 `status=RUNNING` |
| Agent 결과 조회 | `GET` | `/api/ai/agent-sessions/{id}` | path id | 최종 `status`, `summary`, `toolInvocationIds`, `evidenceIds` |
| RAG 검색 | `GET` | `/api/rag/search?q=...` | 검색어 | `items[]` |
| RAG 근거 조회 | `GET` | `/api/rag/evidence/{id}` | evidence id | 공개용 `sourceId`, `summary`, `score`, `metadata` |
| 관리자 Agent 상세 | `GET` | `/api/admin/agent-sessions/{id}` | admin token | `stateTimeline`, `purpose`, `toolInvocations`, `evidenceIds` |
| 관리자 Tool 상세 | `GET` | `/api/admin/tool-invocations/{id}` | admin token | `requestPayload`, `resultPayload`, `latencyMs` |
| 관리자 RAG 상세 | `GET` | `/api/admin/rag-evidence/{id}` | admin token | `chunkText`, `metadata`, `score` |

`POST /api/ai/agent-sessions` 요청은 아래 세 필드 중 하나만 보내야 한다.

```json
{
  "requirementId": "00000000-0000-4000-8000-000000020001",
  "buildId": null,
  "asTicketId": null
}
```

root id에 따라 목적은 자동 결정된다.

| root | Agent 목적 | 사용 화면/흐름 |
|---|---|---|
| `requirementId` | `BUILD_RECOMMEND` | AI 견적 입력 후 추천 Build 생성 |
| `buildId` | `BUILD_EXPLAIN` | 부품 변경, 추천 결과 설명, 비교 설명 |
| `asTicketId` | `AS_ANALYZE` | AS 접수, 로그 분석, 원인/업그레이드 후보 설명 |

## 내부 Service 기준

같은 Spring API 서버 안에서 직접 연결할 때는 `AgentTraceService`를 사용한다.

```java
String sessionId = agentTraceService.createQueuedSession(root, "SYSTEM");
agentTraceService.advanceStatus(sessionId, AgentStatus.RUNNING, "SYSTEM", "agent run requested");
agentTraceService.recordRagEvidence(sessionId, ragDraft);
agentTraceService.advanceStatus(sessionId, AgentStatus.RAG_SEARCHED, "SYSTEM", "evidence retrieved");
agentTraceService.recordToolInvocation(sessionId, toolDraft);
agentTraceService.advanceStatus(sessionId, AgentStatus.TOOLS_CALLED, "SYSTEM", "tools completed");
agentTraceService.updateSummary(sessionId, summary);
agentTraceService.advanceStatus(sessionId, AgentStatus.SUMMARY_READY, "SYSTEM", "summary generated");
agentTraceService.advanceStatus(sessionId, AgentStatus.SUCCEEDED, "SYSTEM", "agent completed");
```

상태 전이는 아래 순서만 허용된다.

```text
QUEUED
  -> RUNNING
  -> RAG_SEARCHED
  -> TOOLS_CALLED
  -> SUMMARY_READY
  -> SUCCEEDED
```

오류/취소 흐름은 `FAILED`, `CANCELLED`, LLM fallback 흐름은 `FALLBACK_READY`를 사용한다.

## 2번 Tool 결과 기록 형식

2번 담당자의 Tool 계산 결과는 아래 draft 형태로 3번에 전달한다.

```java
new AgentToolInvocationDraft(
    "compatibility",
    ToolStatus.PASS,
    ConfidenceLevel.HIGH,
    "CPU, motherboard, RAM, GPU, case compatibility passed.",
    requestPayload,
    resultPayload,
    42
);
```

허용 Tool 이름은 다음 5개다.

| toolName | 의미 |
|---|---|
| `compatibility` | CPU/메인보드/RAM 등 기본 호환성 |
| `power` | PSU 용량과 전력 여유율 |
| `size` | 케이스, GPU 길이, 보드 규격 |
| `performance` | 병목, 사용 목적별 성능 추정 |
| `price` | 예산 초과, 가격 추정, 가격 변동 |

`status`는 `PASS`, `WARN`, `FAIL` 중 하나다. `confidence`는 `LOW`, `MEDIUM`, `HIGH` 중 하나다.

Tool payload는 자유 JSON이지만 관리자 화면에서 바로 읽히도록 아래 키를 유지한다.

```json
{
  "requestPayload": {
    "toolName": "power",
    "buildId": "00000000-0000-4000-8000-000000002001",
    "partIds": ["00000000-0000-4000-8000-000000010004"],
    "context": {
      "budget": 1800000,
      "usageTags": ["GAMING", "DEV"]
    }
  },
  "resultPayload": {
    "status": "WARN",
    "confidence": "MEDIUM",
    "summary": "PSU headroom is acceptable but close to the warning threshold.",
    "details": {
      "estimatedWattage": 520,
      "psuCapacityW": 750,
      "headroomPercent": 30
    },
    "evidenceIds": ["00000000-0000-4000-8000-000000004001"]
  }
}
```

## RAG evidence 기록 형식

RAG 검색 결과나 규칙 근거는 아래 draft로 기록한다.

```java
new AgentRagEvidenceDraft(
    "internal-rule-qhd-gaming-001",
    "QHD gaming builds should prioritize GPU class and power margin.",
    "QHD gaming recommendation rule used for build recommendation.",
    BigDecimal.valueOf(0.92),
    metadata
);
```

필수 필드는 `sourceId`, `chunkText`, `summary`다. `score`는 있으면 `0` 이상 `1` 이하로 넣는다.

`metadata`에는 아래 키를 우선 사용한다.

| key | 예시 | 이유 |
|---|---|---|
| `sourceType` 또는 `sourceTypes` | `INTERNAL_RULE`, `PART_SPEC`, `BENCHMARK`, `TROUBLESHOOTING` | 어떤 종류의 근거인지 구분 |
| `purpose` | `BUILD_RECOMMEND`, `BUILD_EXPLAIN`, `AS_ANALYZE` | 추천/설명/AS 분석 흐름 구분 |
| `rootType` | `REQUIREMENT`, `BUILD`, `AS_TICKET` | 어떤 도메인 객체에서 시작됐는지 추적 |
| `rootId` | public id | 원본 객체로 되돌아가기 위한 연결점 |
| `retrievedAt` | ISO timestamp | 검색/선택 시점 |

## 담당자별 연결 예시

### 1번 추천 담당

AI 견적 입력 후 추천 Build를 만들 때:

1. `requirementId`로 `POST /api/ai/agent-sessions` 호출
2. 생성된 `sessionId`로 `POST /api/ai/agent-sessions/{id}/run` 호출
3. `GET /api/ai/agent-sessions/{id}`로 `toolInvocationIds`, `evidenceIds` 조회
4. 추천 결과 화면에서 `Tool 검증 완료`, `RAG evidence` 링크 표시

부품 변경 비교 설명에서는 `buildId`로 `BUILD_EXPLAIN` 세션을 만들면 된다.

### 2번 Tool 담당

Tool 계산기는 결과를 직접 `tool_invocations` 테이블에 넣지 않는다. 3번의 `recordToolInvocation`을 통해 저장한다.

Tool 응답에는 최소한 `status`, `confidence`, `summary`, `details`를 넣는다. 관리자 화면은 `requestPayload`, `resultPayload`를 그대로 보여주므로, 사람이 읽을 수 있는 키 이름을 써야 한다.

### 4번 AS/로그 담당

AS 티켓이 만들어진 뒤 로그 분석이 필요하면:

1. `asTicketId`로 `POST /api/ai/agent-sessions` 호출
2. `POST /api/ai/agent-sessions/{id}/run` 실행
3. 최종 Agent detail의 `evidenceIds`, `toolInvocationIds`를 AS 티켓 상세 화면의 원인 후보/업그레이드 후보 근거로 연결

AS 흐름의 목적은 `AS_ANALYZE`이며, 현재 mock runner는 `performance`, `compatibility`, `price` Tool 기록을 생성한다.

### 5번 관리자 담당

관리자 화면은 아래 route와 API를 맞춘다.

| route | API | 표시할 핵심 |
|---|---|---|
| `/admin/agent-sessions/:id` | `GET /api/admin/agent-sessions/{id}` | 상태 timeline, purpose, summary, Tool 목록, RAG id |
| `/admin/tool-invocations/:id` | `GET /api/admin/tool-invocations/{id}` | Tool status, confidence, request/result payload |
| `/admin/rag-evidence/:id` | `GET /api/admin/rag-evidence/{id}` | sourceId, score, chunkText, metadata |

## Agent runner 모드

Agent 실행 방식은 `AGENT_RUNNER_MODE` 환경변수로 고른다.

| mode | 동작 | 필요한 환경변수 | 용도 |
|---|---|---|---|
| `deterministic` | seed 기반 RAG/Tool trace와 고정 summary 생성 | 없음 | 키가 없는 팀원 개발, 기본 Docker 실행 |
| `llm` | seed 기반 RAG/Tool trace를 저장한 뒤 OpenAI API로 summary 생성 | `OPENAI_API_KEY` | 실제 LLM 생성 결과가 관리자 화면까지 표시되는 검증 |

LLM 실행에 필요한 `.env` 예시는 아래와 같다.

```env
AGENT_RUNNER_MODE=llm
OPENAI_API_KEY=sk-...
OPENAI_MODEL=gpt-5.5
OPENAI_REASONING_EFFORT=medium
RAG_VECTOR_ENABLED=true
RAG_VECTOR_REQUIREMENT_PARSE_ENABLED=true
RAG_VECTOR_BUILD_RECOMMEND_ENABLED=true
RAG_VECTOR_AS_ANALYZE_ENABLED=true
RAG_VECTOR_PUBLIC_SEARCH_ENABLED=true
AS_CHAT_DEFAULT_PROFILE=AS_CHAT_54_MINI_FAST
AS_CHAT_FAST_MODEL=gpt-5.5
AS_CHAT_FAST_REASONING_EFFORT=low
AS_CHAT_FAST_RAG_TOP_K=2
AS_CHAT_FAST_MAX_OUTPUT_TOKENS=900
AS_CHAT_54_MINI_FAST_MODEL=gpt-5.4-mini
AS_CHAT_54_MINI_FAST_REASONING_EFFORT=low
AS_CHAT_54_MINI_FAST_RAG_TOP_K=2
AS_CHAT_54_MINI_FAST_MAX_OUTPUT_TOKENS=850
BUILD_CHAT_DEFAULT_PROFILE=BUILD_CHAT_54_MINI_FAST
BUILD_CHAT_54_MINI_FAST_MODEL=gpt-5.4-mini
BUILD_CHAT_54_MINI_FAST_REASONING_EFFORT=low
BUILD_CHAT_54_MINI_FAST_RAG_TOP_K=3
BUILD_CHAT_54_MINI_FAST_MAX_OUTPUT_TOKENS=850
AS_CHAT_NANO_FAST_MODEL=gpt-5.4-nano
AS_CHAT_NANO_FAST_REASONING_EFFORT=low
AS_CHAT_NANO_FAST_RAG_TOP_K=2
AS_CHAT_NANO_FAST_MAX_OUTPUT_TOKENS=700
AS_CHAT_NANO_FAST_RECENT_MESSAGE_LIMIT=2
AS_CHAT_BALANCED_MODEL=gpt-5.5
AS_CHAT_BALANCED_REASONING_EFFORT=low
AS_CHAT_BALANCED_RAG_TOP_K=3
AS_CHAT_BALANCED_MAX_OUTPUT_TOKENS=1100
AS_CHAT_HIGH_QUALITY_MODEL=gpt-5.5
AS_CHAT_HIGH_QUALITY_REASONING_EFFORT=medium
AS_CHAT_HIGH_QUALITY_RAG_TOP_K=5
AS_CHAT_HIGH_QUALITY_MAX_OUTPUT_TOKENS=2600
OPENAI_BASE_URL=https://api.openai.com/v1
```

API 키는 저장소에 커밋하지 않는다. 각자 로컬 `.env`에만 넣는다.

AS Chat은 기본 요청에서 `AS_CHAT_DEFAULT_PROFILE` 하나만 실행한다. OpenAI profile 비교는 `tools/benchmark_as_chat_profiles.py`가 내부 검증용 header `X-BuildGraph-AI-Profile`을 사용해 순차 실행한다. 기본 benchmark 실행은 현재 사용자 기본값인 `AS_CHAT_54_MINI_FAST` 하나만 측정하고, 후보 전체 비교가 필요할 때만 `--profiles`로 명시한다. 사용자 화면은 `POST /api/ai/as-chat/stream`을 우선 사용해 `STARTED -> RAG_READY -> TOOLS_READY -> LLM_RUNNING -> DONE` 진행 상태를 표시한다.

| profile | provider | 목적 | 기본 모델 | reasoning | RAG topK | max output |
|---|---|---|---|---|---:|---:|
| `AS_CHAT_54_MINI_FAST` | OpenAI | 기본 사용자 profile | `gpt-5.4-mini` | `low` | 2 | 850 |
| `AS_CHAT_FAST` | OpenAI | 이전 기본값 / rollback 후보 | `gpt-5.5` | `low` | 2 | 900 |
| `AS_CHAT_NANO_FAST` | OpenAI | 실험/실패 추적 profile | `gpt-5.4-nano` | `low` | 2 | 700 |
| `AS_CHAT_BALANCED` | OpenAI | 고위험/품질 보강 후보 | `gpt-5.5` | `low` | 3 | 1100 |
| `AS_CHAT_HIGH_QUALITY` | OpenAI | 관리자 검증/고품질 후보 | `gpt-5.5` | `medium` | 5 | 2600 |

LLM 호출 결과는 `llm_generations`에 저장한다. 저장 대상은 provider, profile, model, reasoning, latency, token usage, schema validity, error 요약이다. prompt 원문, API key, 원본 로그 전문은 저장하지 않는다.

Build Chat은 반복 상담 속도 개선을 위해 Redis exact/shared cache와 Postgres pgvector semantic cache를 함께 사용할 수 있다. Redis 기본 TTL은 `BUILD_CHAT_CACHE_TTL_SECONDS=600`초이며, 문맥 없는 견적/부품 추천은 `shared + profile + normalized message + selectedCategory + parts/benchmark/FPS/RAG/alias version` key를 사용한다. 장바구니, 최근 추천, 선택 부품, 교체/삭제/담기 문맥이 있는 요청은 `user internal id + currentQuoteDraft partId/quantity fingerprint`를 포함해 사용자별 key를 사용한다. `BUILD_CHAT_CACHE_PREWARM_ENABLED=true`이면 서버 준비 후 자동입력칩용 LLM 요청을 비동기로 prewarm하고, prewarm TTL 기본값은 `BUILD_CHAT_CACHE_PREWARM_TTL_SECONDS=3600`초다. Redis 장애, 미설정, 직렬화 실패, OpenAI key 없음이 있어도 사용자 응답 shape는 바뀌지 않고 기존 LLM/RAG 실행으로 우회한다. cache hit/miss는 내부 로그와 benchmark 보고서에서만 확인하고 프론트 계약에는 노출하지 않는다. cache payload는 이전 실행의 `agentSessionId`, `evidenceIds`, `toolInvocationIds`를 제거해 trace 식별자를 재사용하지 않는다.

Semantic cache는 `build_chat_semantic_cache` 테이블과 `text-embedding-3-small` embedding을 사용한다. 기본값은 `BUILD_CHAT_SEMANTIC_CACHE_ENABLED=true`, threshold `0.94`, TTL `600`초다. 적용 대상은 `BuildChatIntentRouter` decision 기준 `sideEffectRisk=NONE`이고 장바구니/currentBuilds/selectedCategory 문맥이 없는 `BUILD_RECOMMEND`와 `PART_RECOMMEND`뿐이다. 예산, 부품 class, 카테고리 등이 들어간 `semanticConstraintSignature`가 일치해야 하며, `300만원`과 `800만원`, `5090`과 `5080`, `바꾸면`과 `바꿔줘`는 cache hit 대상이 아니다. embedding/DB 오류는 사용자 실패로 전파하지 않고 기존 LLM/RAG 흐름으로 우회한다.

Build Chat의 route/action/simulation/LLM 분기는 내부 `BuildChatIntentRouter` decision으로 통제한다. 프론트 즉시 route는 정적 화면 이동과 명확한 category-only 이동으로 제한하고, 상품 상세/필터/시뮬레이션/장바구니 변경 판단은 서버 decision을 따른다. draft mutation 자동 실행은 `requiresConfirmation=false`, `payload.intentConfidence=HIGH`, `payload.sideEffectRisk=LOW`일 때만 허용하며, metadata가 없거나 risk가 `MEDIUM` 이상인 action은 변경안 카드로 남긴다.

Build Chat 명시 예산은 `budgetMode`로 관리한다. 서버는 LLM `parsedContext`와 별개로 raw message에서 금액과 `target/max/min` 의도를 다시 파싱하고, 이 raw budget guard를 최종 추천 필터에 우선 적용한다. target budget(`800만원으로`, `800만원짜리`, `800만원 정도`)은 총액을 87.5%~112.5% 안에 맞추고, max budget(`이하`, `안으로`, `넘지 않게`)은 예산 이하를 우선하며, min budget(`이상`, `최소`, `부터`)은 기존 하한 예산 동작을 유지한다. 하드 부품 조건이 있으면 예산 초과를 허용하되 `HARD_CONSTRAINT_OVER_BUDGET` warning을 남긴다.

Build Chat 추천 품질 개선을 위한 XGBoost reranker는 shadow scoring만 수행한다. 홈 하단 추천부품 랭킹은 같은 scorer를 보이는 랭킹에 사용하며, scorer 장애나 모델 부재 시 deterministic fallback을 반환해 사용자 화면을 유지한다. Docker 기본 endpoint는 `RECOMMENDATION_RERANKER_ENDPOINT=http://xgb-reranker:8091/score`이고, 로컬 jar 단독 실행 시에는 `http://localhost:8091/score`를 사용한다. Java API는 기존 Tool/규칙 필터를 통과한 후보만 external scorer에 보내고, scorer 장애나 미기동은 사용자 응답 실패로 전파하지 않는다. Build Chat의 반환 score는 `recommendation_shadow_scores`에 저장하지만 추천 순서는 바꾸지 않는다.

학습 데이터는 `POST /api/recommendation-events`, 관리자 홈 추천부품 라벨 API, 관리자 AS feedback API로 수집한다. 홈 추천부품의 노출/클릭/상세 이동/견적 담기는 `sourceSurface=HOME_RECOMMENDED_PARTS`로 저장한다. 관리자는 `POST /api/admin/recommendation-feedback/home-parts`로 `ADMIN_PROMOTE` 또는 `ADMIN_DEMOTE` 라벨을 추가할 수 있다. 이 라벨은 학습 신호일 뿐 `parts.status`나 사용자 노출 여부를 바꾸지 않는다. AS 접수 자체는 자동 negative label이 아니며, 관리자가 `POST /api/admin/recommendation-feedback/as-tickets/{id}`로 추천 조합/부품 문제를 확정한 경우에만 `AS_CONFIRMED_NEGATIVE` 이벤트를 저장한다. 이 과정에서 `as_tickets.status`, `cause_candidates`, `upgrade_candidates`는 수정하지 않는다.

Python batch 도구:

- `tools/export_recommendation_training_data.py`: `recommendation_events`를 CSV 학습 데이터로 export한다. 홈 추천부품은 `--home-parts`로 사용자 HOME 이벤트와 관리자 HOME 라벨을 함께 export한다.
- `tools/train_xgb_reranker.py`: CSV에서 XGBoost 모델과 metric 보고서를 생성한다. 기본은 50행 미만이면 `SKIPPED_LOW_DATASET`으로 모델 생성을 건너뛰고, 데모 강제 학습은 `--allow-small-dataset`을 명시한다.
- `tools/reranker_service.py`: optional scorer API와 training worker다. Docker `xgb-reranker` 서비스에서 실행되며 `/models/home-parts-active.json`이 있으면 XGBoost로 점수를 내고, 모델이 없거나 로드 실패하면 연결 smoke용 baseline score를 반환한다. worker는 `recommendation_training_jobs.status=QUEUED` row를 polling해 locked dataset을 학습한다.

관리자 운영 학습/배포 절차:

1. `/admin` 대시보드의 “AI 추천 모델 상태”에서 학습 dataset을 만든다. dataset은 eligible event의 feature/label snapshot이다.
2. `DRAFT` dataset에서 포함/제외를 정리한 뒤 `LOCKED`로 잠근다.
3. 학습 Job을 만든다. API는 `QUEUED` row만 만들고 Python 학습은 `xgb-reranker` worker가 처리한다.
4. 50행 미만이면 `SKIPPED_LOW_DATASET`으로 끝나며 모델을 만들지 않는다.
5. 성공 모델은 `recommendation_model_versions.status=SHADOW`로 저장된다. 관리자 검토 후 activate API가 scorer reload에 성공하면 `/models/home-parts-active.json`이 갱신되고 DB status가 `ACTIVE`로 바뀐다.
6. CLI export/train은 로컬 분석과 데모 보조용으로만 사용한다. 특정 파일을 강제 테스트하려면 `.env`의 `RECOMMENDATION_RERANKER_MODEL_PATH=/models/<model-file>.json`에 지정한다.

LLM mode에서도 외부 담당자가 보는 계약은 바뀌지 않는다.

1. `POST /api/ai/agent-sessions`로 세션 생성
2. `POST /api/ai/agent-sessions/{id}/run`으로 실행
3. `GET /api/ai/agent-sessions/{id}` 또는 `GET /api/admin/agent-sessions/{id}`로 summary, Tool, RAG 근거 조회

## 현재 구현 상태

- DB 저장은 실제 PostgreSQL/Flyway 테이블에 연결되어 있다.
- `agent_session_id IS NULL`인 `rag_evidence` row는 Agent가 검색할 재사용 지식 청크로 사용한다.
- Agent 실행 시 선택된 지식 청크는 세션별 `rag_evidence` row로 복사되어 관리자 화면과 `evidenceIds`에서 추적된다.
- 관리자 Agent/Tool/RAG 상세 화면은 실제 API 응답을 읽는다.
- `deterministic` runner는 키 없이 같은 흐름을 재현한다.
- `llm` runner는 RAG evidence와 Tool invocation을 저장한 뒤 OpenAI structured output API로 생성한 summary를 `agent_sessions.summary`에 저장한다.
- RAG vector 검색은 `RAG_VECTOR_ENABLED` 전역값과 `RAG_VECTOR_REQUIREMENT_PARSE_ENABLED`, `RAG_VECTOR_BUILD_RECOMMEND_ENABLED`, `RAG_VECTOR_AS_ANALYZE_ENABLED`, `RAG_VECTOR_PUBLIC_SEARCH_ENABLED` 경로별 override를 따른다. 경로별 기본값은 전역값을 상속한다.
- 실제 embedding 검색은 OpenAI key와 `rag_evidence.embedding` 백필이 있을 때 동작하고, 실패하거나 꺼져 있으면 keyword fallback을 사용한다.
- 실제 2번 Tool 계산은 기존 Tool owner 구현을 호출/참조한다.
- 외부 담당자는 `sessionId`, `toolInvocationIds`, `evidenceIds` 계약을 유지하면 runner 내부 구현 변경에 영향받지 않는다.
