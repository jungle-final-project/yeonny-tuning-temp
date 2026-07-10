# BuildGraph AI 프로토타입

정글 최종 프로젝트 `나만의 무기 만들기`용 프로토타입 저장소입니다. 5명이 각자 담당 기능을 바로 구현할 수 있도록 공통 화면, API 계약, Docker 인프라, CI 출발점을 제공합니다.

## 먼저 읽을 문서

| 순서 | 문서 | 목적 |
| --- | --- | --- |
| 1 | [docs/API_CONTRACT.md](docs/API_CONTRACT.md) | API 요청/응답, 인증, pagination, 오류, public_id 계약 확인 |
| 2 | [docs/DB_SCHEMA.md](docs/DB_SCHEMA.md) | 공통 DB 테이블, 상태 전이, JSONB, Flyway 기준 확인 |
| 3 | [docs/ROUTE_OWNERSHIP.md](docs/ROUTE_OWNERSHIP.md) | 담당자별 route/API/DB/file owner와 공유 지점 확인 |
| 4 | [docs/openapi.yaml](docs/openapi.yaml) | API 계약의 기계 검증용 OpenAPI 확인 |
| 5 | [docs/role-workspaces.md](docs/role-workspaces.md) | 저장소 기존 작업공간 요약 확인 |
| 6 | [docs/sprint-1-start-checklist.md](docs/sprint-1-start-checklist.md) | 첫 PR에서 무엇을 할지 확인 |
| 7 | [docs/architecture.md](docs/architecture.md) | 전체 구조와 런타임 흐름 확인 |
| 8 | [docs/scaffold-decisions.md](docs/scaffold-decisions.md) | 이번 Sprint에서 고정한 결정사항과 이후 작업 확인 |

사람이 읽고 합의할 기준 문서는 `API_CONTRACT.md`, `DB_SCHEMA.md`, `ROUTE_OWNERSHIP.md`입니다. `docs/openapi.yaml`은 이 계약을 기계적으로 검증하고 프론트/백엔드 타입을 맞추기 위한 보조 파일입니다.

4번 담당자는 [apps/pc-agent/README.md](apps/pc-agent/README.md)도 함께 확인합니다.

## 기술 스택

- 웹: React, TypeScript, Vite, Tailwind, React Router, TanStack Query
- API: Spring Boot, Gradle, Java 21
- 인프라: PostgreSQL + pgvector, Redis, RabbitMQ, Mailpit, Docker Compose
- PC 에이전트: Python 3.11 CLI 골격

## 클린 풀 첫 세팅

새 PC나 새 폴더에서 처음 받는 팀원은 아래 순서로 시작합니다. Docker Desktop이 켜져 있으면 로컬 Java/Node/PostgreSQL 설치 없이 웹, API, DB, Redis, RabbitMQ, Mailpit을 함께 실행할 수 있습니다.

```powershell
git clone https://github.com/jungle-final-project/prototype.git
cd prototype
Copy-Item .env.example .env
docker compose up --build
```

macOS/Linux에서는 `.env` 복사를 아래처럼 합니다.

```bash
cp .env.example .env
docker compose up --build
```

`.env`는 저장소에 커밋하지 않습니다. 처음 실행만 확인할 때는 `.env.example` 기본값만으로도 seed DB, 로그인, 부품 목록, 수동 견적, AS Chat 화면 진입이 동작합니다. 실제 외부 연동까지 확인할 팀원만 아래 값을 채웁니다.

| 환경변수 | 필수 여부 | 쓰는 곳 | 비고 |
| --- | --- | --- | --- |
| `OPENAI_API_KEY` | AS Chat/PC Agent AI 진단 실제 LLM 답변 테스트 시 필요 | `/support/ai-chat`, PCAgent AI 진단, Agent LLM mode, AS Chat benchmark | 없으면 AS Chat 답변 생성은 `428 PRECONDITION_REQUIRED`로 실패합니다. PCAgent AI 진단은 rule fallback 답변으로 계속 동작합니다. |
| `NAVER_SEARCH_CLIENT_ID` | 가격/상품 갱신 시 필요 | 관리자 부품 offer 갱신, 내부 자산 후보 수집 | 없으면 seed에 저장된 내부 자산과 가격만 표시됩니다. |
| `NAVER_SEARCH_CLIENT_SECRET` | 가격/상품 갱신 시 필요 | 관리자 부품 offer 갱신, 내부 자산 후보 수집 | 네이버 검색 API secret입니다. 커밋 금지입니다. |
| `AGENT_RUNNER_MODE` | 선택 | Agent 실행 방식 | 기본값은 `deterministic`입니다. 실제 LLM summary를 보려면 `llm`으로 바꿉니다. |
| `PC_AGENT_DIAGNOSIS_CHAT_MODEL` | 선택 | PCAgent AI 진단 탭 | 기본값은 `gpt-5.4-mini`입니다. `OPENAI_API_KEY`가 있을 때만 사용하고, 실패 시 rule fallback으로 우회합니다. |
| `PC_AGENT_DIAGNOSIS_CHAT_REASONING_EFFORT` | 선택 | PCAgent AI 진단 탭 | 기본값은 `low`입니다. |
| `PC_AGENT_DIAGNOSIS_CHAT_MAX_OUTPUT_TOKENS` | 선택 | PCAgent AI 진단 탭 | 기본값은 `750`입니다. |
| `AS_CHAT_DEFAULT_PROFILE` | 선택 | AS Chat 기본 profile | 기본값은 실측 benchmark 기준 `AS_CHAT_54_MINI_FAST`입니다. |
| `BUILD_CHAT_DEFAULT_PROFILE` | 선택 | Build Chat 기본 profile | 기본값은 실측 benchmark 기준 `BUILD_CHAT_54_MINI_FAST`입니다. |
| `BUILD_CHAT_CACHE_ENABLED` | 선택 | Build Chat Redis cache | 기본값은 `true`입니다. Redis 장애 시 자동 우회하며 응답 body에는 cache 상태를 노출하지 않습니다. |
| `BUILD_CHAT_CACHE_TTL_SECONDS` | 선택 | Build Chat Redis cache | 기본값은 `600`초입니다. 문맥 없는 견적/부품 추천은 shared key를 사용하고, 장바구니 문맥이 있으면 사용자/profile/draft와 parts/benchmark/FPS/RAG/alias version이 바뀔 때 key도 달라집니다. cache hit 응답은 이전 실행의 agent trace id를 재사용하지 않습니다. |
| `BUILD_CHAT_CACHE_PREWARM_ENABLED` | 선택 | Build Chat Redis cache prewarm | 기본값은 `true`입니다. 서버 준비 후 + 주기적으로 데모 대표 프롬프트(예산·용도 견적)를 비동기로 캐시에 올립니다. OpenAI key 없음/Redis 장애는 서버 시작을 막지 않습니다. |
| `BUILD_CHAT_CACHE_PREWARM_TTL_SECONDS` | 선택 | Build Chat Redis cache prewarm | 기본값은 `3600`초입니다. 프리웜한 응답의 캐시 유지 시간입니다. |
| `BUILD_CHAT_CACHE_PREWARM_REFRESH_DELAY_MS` | 선택 | Build Chat 프리웜 재실행 주기 | 기본값은 `2700000`(45분)입니다. 프리웜 TTL이 만료돼 캐시가 식기 전에 서버가 스스로 재프리웜해 항상 캐시를 유지하므로, 배포 환경에서 별도 워밍 스크립트를 수동 실행할 필요가 없습니다. |
| `BUILD_CHAT_SEMANTIC_CACHE_ENABLED` | 선택 | Build Chat pgvector semantic cache | 기본값은 `true`입니다. 문맥 없는 읽기/추천 요청만 embedding similarity로 재사용하고, 장바구니 변경/시뮬레이션/라우팅 요청은 제외합니다. |
| `BUILD_CHAT_SEMANTIC_CACHE_THRESHOLD` | 선택 | Build Chat semantic cache hit 기준 | 기본값은 `0.94`입니다. constraint signature가 다른 요청은 similarity가 높아도 cache hit로 보지 않습니다. |
| `BUILD_CHAT_SEMANTIC_CACHE_TTL_SECONDS` | 선택 | Build Chat semantic cache TTL | 기본값은 `600`초입니다. OpenAI embedding 또는 DB 오류 시 기존 LLM/RAG 흐름으로 우회합니다. |
| `BUILD_CHAT_TIER_SNAPSHOT_ENABLED` | 선택 | Build Chat 예산 티어 스냅샷 | 기본값은 `true`입니다. 200만~1300만원(100만원 간격) 추천 조합을 백그라운드에서 미리 계산해 두고, 예산 요청이 티어와 허용 오차(기본 15%) 안이면 LLM/조합 탐색 없이 즉시 응답합니다. 명시적 부품 제약이나 견적 드래프트 문맥이 있으면 기존 경로로 폴백합니다. |
| `BUILD_CHAT_TIER_SNAPSHOT_REFRESH_DELAY_MS` | 선택 | 티어 스냅샷 재계산 주기 | 기본값은 `3600000`(1시간)입니다. 범위/간격/허용오차는 `BUILD_CHAT_TIER_SNAPSHOT_MIN_BUDGET_WON`/`MAX_BUDGET_WON`/`STEP_WON`/`TOLERANCE_PCT`로 조절합니다. |
| `BUILDGRAPH_CORS_ALLOWED_ORIGINS` | 선택 | REST/WS 허용 origin | 기본값은 `http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174`입니다. 배포 환경에서는 실제 web/admin origin을 명시해야 합니다. |
| `GOOGLE_OAUTH_CLIENT_ID` | Google 로그인 테스트 시 필요 | `/login`, `/signup`, `/admin/login` Google 버튼 | 없으면 Google OAuth 시작 API가 `428 PRECONDITION_REQUIRED`를 반환합니다. |
| `GOOGLE_OAUTH_CLIENT_SECRET` | Google 로그인 테스트 시 필요 | API Google code exchange | 커밋 금지입니다. Google access/refresh token은 DB에 저장하지 않습니다. |
| `GOOGLE_OAUTH_REDIRECT_URI` | Google 로그인 테스트 시 필요 | Google OAuth callback | 기본 로컬 값은 `http://localhost:8080/api/auth/google/callback`입니다. Google Console 승인 redirect URI와 일치해야 합니다. |
| `GOOGLE_OAUTH_WEB_CALLBACK_URL` | Google 로그인 테스트 시 필요 | 프론트 callback route | 기본 로컬 값은 `http://localhost:5173/auth/callback`입니다. |
| `SUPPORT_CHAT_WS_TICKET_TTL_SECONDS` | 선택 | Support Chat WebSocket ticket TTL | 기본값은 `60`초입니다. WS URL에는 JWT를 싣지 않고 REST로 발급한 1회용 ticket을 첫 `AUTH` frame으로 전송합니다. |
| `RECOMMENDATION_RERANKER_ENDPOINT` | 선택 | 홈 추천부품 XGBoost scorer | Docker 기본값은 `http://xgb-reranker:8091/score`입니다. 로컬 jar 단독 실행 시에는 `http://localhost:8091/score`로 바꿉니다. |
| `RECOMMENDATION_RERANKER_MODEL_PATH` | 선택 | XGBoost scorer 모델 파일 | Docker에서는 `/models/<model-file>.json` 형식입니다. 비어 있으면 baseline scorer로 동작합니다. |
| `RECOMMENDATION_RERANKER_MODEL_VOLUME` | 선택 | XGBoost scorer 모델 저장소 | Docker Compose 기본값은 named volume `recommendation-models`입니다. 로컬 파일을 직접 연결할 때만 공유 가능한 host 경로로 바꿉니다. |
| `RECOMMENDATION_RERANKER_ENABLED` | 선택 | XGBoost rerank 순위 반영 | 기본값은 `false`입니다. `true`면 rerank 점수를 실제 후보 순위에 반영하고, `false`면 shadow 기록만 남깁니다. |
| `RECOMMENDATION_RERANKER_SHADOW_ENABLED` | 선택 | XGBoost shadow 수집 | 기본값은 `true`입니다. 홈 추천 shadow 점수 수집과 scorer 사용 전체 스위치입니다. 운영 규칙 상세는 [docs/RECOMMENDATION_OPERATIONS.md](docs/RECOMMENDATION_OPERATIONS.md)를 참고합니다. |
| `RECOMMENDATION_RERANKER_TIMEOUT_MS` | 선택 | XGBoost scorer 호출 타임아웃 | 기본값은 `1200`ms입니다. |
| `RECOMMENDATION_TRAINING_WORKER_ENABLED` | 선택 | xgb-reranker 학습 워커 | 기본값은 `true`입니다. 컨테이너 안에서 `QUEUED` 학습 Job을 polling해 처리합니다. |
| `RECOMMENDATION_TRAINING_MIN_ROWS` | 선택 | 학습 최소 행 수 | 기본값은 `50`입니다. 미만이면 학습 Job이 `SKIPPED_LOW_DATASET`으로 끝납니다. |
| `RECOMMENDATION_TRAINING_POLL_SECONDS` | 선택 | 학습 Job polling 주기 | 기본값은 `5`초입니다. |
| `SCHEDULING_POOL_SIZE` | 선택 | API `@Scheduled` 잡 스레드풀 크기 | 기본값은 `4`입니다. 파이프라인 잡(가격/다나와/릴리스 스캔/프리웜/티어 스냅샷)이 한 스레드를 공유하며 서로 막지 않게 격리합니다. compose.yaml에는 env 매핑이 없어 Docker에서 바꾸려면 compose에 매핑을 추가해야 합니다. |
| `DEMO_FREEZE_MUTATIONS` | 선택 | 데모 동결 단일 스위치 | 기본값은 `false`입니다. `true`면 가격/다나와/추이/제조사 스캔 스케줄러 4종이 `SKIPPED_FROZEN`으로 건너뛰고, 관리자 가격 Job 실행(`POST /api/admin/price-jobs/run`)은 `409`로 거절됩니다. 읽기 API에는 영향이 없습니다. |
| `AGENT_DEMO_ACTIVATION_TOKEN` | 선택 | PC Agent 데모 등록 토큰 | 기본값(빈값)이면 데모 등록 경로가 비활성화되어 `401`을 반환합니다. Docker Compose 로컬 스택은 `demo-agent-activation-token`으로 켜 둡니다. 프로덕션 배포에서는 반드시 비우거나 DB 발급 토큰 방식으로 대체합니다. |
| `PART_MANUFACTURER_RELEASE_DEMO_FEED_ENABLED` | 선택 | 인증 없는 데모 RSS 피드 노출 | 기본값은 `false`이며 이때 `/api/demo/manufacturer-release-feed.xml` 라우트 자체가 비활성화됩니다. Docker Compose 로컬 스택만 `true`입니다. |
| `OPENAI_EMBEDDING_MODEL` | 선택 | RAG vector 검색 | 기본값은 `text-embedding-3-small`입니다. |
| `OPENAI_EMBEDDING_DIMENSIONS` | 선택 | RAG vector 검색 | 기본값은 `1536`입니다. |
| `RAG_VECTOR_ENABLED` | 선택 | RAG 검색 방식 | 기본값은 `true`입니다. 키/embedding이 없으면 keyword fallback을 사용합니다. |
| `RAG_VECTOR_REQUIREMENT_PARSE_ENABLED` | 선택 | 견적 자연어 파싱 RAG | 기본값은 `RAG_VECTOR_ENABLED`를 상속합니다. vector 정책 실험 때만 개별 override합니다. |
| `RAG_VECTOR_BUILD_RECOMMEND_ENABLED` | 선택 | 추천/부품변경 근거 RAG | 기본값은 `RAG_VECTOR_ENABLED`를 상속합니다. |
| `RAG_VECTOR_AS_ANALYZE_ENABLED` | 선택 | AS Chat 근거 RAG | 기본값은 `RAG_VECTOR_ENABLED`를 상속합니다. |
| `RAG_VECTOR_PUBLIC_SEARCH_ENABLED` | 선택 | `/api/rag/search` 검증 검색 | 기본값은 `RAG_VECTOR_ENABLED`를 상속합니다. 관리자/검증 검색 품질 비교에 사용합니다. |
| `RAG_EMBEDDING_BACKFILL_ON_STARTUP` | 선택 | RAG embedding 백필 | 기본값은 `false`입니다. 비용 통제를 위해 수동 실행을 권장합니다. |

팀 공통 테스트 계정:

| 권한 | 이메일 | 비밀번호 | 용도 |
| --- | --- | --- | --- |
| USER | `user@example.com` | `passw0rd!` | 쇼핑몰, 수동 견적, AS Chat |
| ADMIN | `admin@example.com` | `passw0rd!` | 관리자 화면, 가격 갱신 API |

관리자는 공개 회원가입으로 만들지 않습니다. seed 관리자 또는 운영자가 DB/관리 명령으로 사전 부여한 `ADMIN` 계정만 `/admin/login`에서 로그인할 수 있고, Google 인증도 기존 `ADMIN` 이메일과 연결될 때만 관리자 화면에 접근합니다.

서비스 주소:

| 서비스 | 주소 |
| --- | --- |
| 웹 | http://localhost:5173 |
| API health | http://localhost:8080/api/health |
| XGBoost scorer health | http://localhost:8091/health |
| RabbitMQ 관리 화면 | http://localhost:15672 |
| Mailpit | http://localhost:8025 |

클린 풀 후 바로 확인할 화면:

| 화면 | 주소 | 확인할 내용 |
| --- | --- | --- |
| 홈 | http://localhost:5173 | fullPage 배너, 주요 이동 버튼, 추천상품, 인기 부품 랭킹 |
| 셀프 견적 | http://localhost:5173/self-quote | 내부 자산 부품 목록, 카테고리 선택, 견적 담기 |
| AS Chat | http://localhost:5173/support/ai-chat | 로그인 후 AS AI 챗봇 화면. 실제 답변 생성은 `OPENAI_API_KEY` 필요 |
| API health | http://localhost:8080/api/health | API와 DB 연결 상태 |

중지와 초기화:

```powershell
docker compose down
docker compose down -v
```

`docker compose down -v`는 PostgreSQL volume까지 삭제하므로 seed 상태로 다시 시작할 때만 사용합니다.

DB schema와 seed 데이터는 Spring SQL init이 아니라 Flyway migration으로 관리합니다.

## RAG 임베딩 백필

`rag_evidence` 재사용 지식 청크는 Flyway에서 생성하지만, 외부 API 호출이 필요한 embedding 값은 Flyway에서 만들지 않습니다. 실제 pgvector RAG 검색을 쓰려면 Docker 기동 후 한 번 백필합니다.

Windows:

```powershell
.\scripts\backfill-rag-embeddings.ps1
```

macOS/Linux:

```bash
bash scripts/backfill-rag-embeddings.sh
```

기본 seed 관리자 계정(`admin@example.com` / `passw0rd!`)으로 로그인해 `POST /api/admin/rag-embeddings/backfill`을 호출합니다. 다른 환경에서는 아래처럼 바꿀 수 있습니다.

```powershell
.\scripts\backfill-rag-embeddings.ps1 -ApiBaseUrl "http://localhost:8080" -AdminEmail "admin@example.com" -AdminPassword "passw0rd!" -Limit 200
```

```bash
API_BASE_URL=http://localhost:8080 ADMIN_EMAIL=admin@example.com ADMIN_PASSWORD='passw0rd!' LIMIT=200 bash scripts/backfill-rag-embeddings.sh
```

성공 예시:

```json
{
  "scanned": 28,
  "updated": 28,
  "skipped": 0,
  "reusableTotal": 28,
  "embeddedTotal": 28,
  "embeddingModel": "text-embedding-3-small",
  "embeddingDimensions": 1536
}
```

이미 최신 embedding이 채워진 상태면 `updated`는 `0`, `skipped`는 기존 row 수로 표시됩니다.

| 파일 | 역할 |
| --- | --- |
| `apps/api/src/main/resources/db/migration/V1__extensions.sql` | `pgvector`, `pgcrypto` extension |
| `apps/api/src/main/resources/db/migration/V2__users_auth.sql` | 사용자, OAuth provider, refresh token |
| `apps/api/src/main/resources/db/migration/V3__parts_price.sql` | 부품, 가격, 호환성, 벤치마크 |
| `apps/api/src/main/resources/db/migration/V4__quote_build.sql` | 요구사항, 추천 build, build item |
| `apps/api/src/main/resources/db/migration/V5__support_ticket.sql` | PC Agent 로그 업로드, AS ticket |
| `apps/api/src/main/resources/db/migration/V6__agent_rag_tool.sql` | Agent session, Tool invocation, RAG evidence |
| `apps/api/src/main/resources/db/migration/V7__admin_audit_seed.sql` | 관리자 audit log와 팀 공통 seed 데이터 |
| `apps/api/src/main/resources/db/migration/V8__parts_catalog_seed.sql` | 내부 쇼핑몰 부품 카탈로그 확장 seed와 DANAWA_BACKUP 가격 스냅샷 |
| `apps/api/src/main/resources/db/migration/V9__current_lineup_parts_seed.sql` | 최신 라인업 기준 ACTIVE 내부 자산과 MANUAL_CURRENT_LINEUP 가격 스냅샷 |
| `apps/api/src/main/resources/db/migration/V10__part_external_offers_cache.sql` | 네이버 쇼핑 검색 결과를 사용자 조회와 분리해서 저장하는 외부 상품 캐시 |
| `apps/api/src/main/resources/db/migration/V11__part_catalog_refresh_pipeline.sql` | 외부 검색 API로 내부 자산 후보를 대량 수집하고 `parts`에 게시하기 위한 갱신 작업/후보 테이블 |
| `apps/api/src/main/resources/db/migration/V12__part_tool_spec_enrichment.sql` | Tool 계산에 필요한 소켓, 규격, 치수, 케이스 장착 제약, 쿨러 지원 스펙 보정 |
| `apps/api/src/main/resources/db/migration/V13__part_psu_capacity_enrichment.sql` | `RM850x`, `A850GS`, `GX-1000`처럼 W 표기가 없는 PSU 상품명의 정격 출력 보정 |
| `apps/api/src/main/resources/db/migration/V14__part_spec_confidence_normalization.sql` | 외부 수집 상품과 seed 상품의 스펙 신뢰도 표기 분리 |
| `apps/api/src/main/resources/db/migration/V15__part_manual_verified_specs.sql` | 제조사/공식 스펙 근거가 있는 케이스, 쿨러, PSU, GPU 고정 규격을 수동 검증값으로 보강하고 렌탈/비상품 GPU 비활성화 |
| `apps/api/src/main/resources/db/migration/V16__sync_parts_price_from_external_offers.sql` | 저장된 네이버 검색 offer 가격을 `parts.price`와 `price_snapshots`에 동기화 |
| `apps/api/src/main/resources/db/migration/V17__current_cooler_lineup_seed.sql` | 최신 공랭/수랭 쿨러 ACTIVE 내부 자산과 MANUAL_CURRENT_LINEUP 가격 스냅샷 |
| `apps/api/src/main/resources/db/migration/V18__curated_cooler_external_offers.sql` | 쿨러 seed 자산의 대표 상품 이미지, 공급업체, 현재가 offer 캐시 |
| `apps/api/src/main/resources/db/migration/V19__collected_parts_catalog_snapshot.sql` | 네이버 검색으로 수집해 검토한 CPU/GPU/메인보드/RAM/SSD/파워/케이스/쿨러 내부 자산 스냅샷 |
| `apps/api/src/main/resources/db/migration/V20__budget_psu_wattage_catalog_seed.sql` | 300W~850W 보급형/중급형 PSU 구간 보강 seed와 offer 캐시 |
| `apps/api/src/main/resources/db/migration/V21__official_spec_option_gap_seed.sql` | 공식 스펙 기반 옵션 구간 보강 seed. mATX/ITX 보드, RAM/SSD 용량·속도, SFX/ATX PSU, 소형 케이스, 공랭/수랭 쿨러를 추가하고 Tool-ready 기준을 분리 |
| `apps/api/src/main/resources/db/migration/V22__gpu_tool_dimension_seed.sql` | 수집된 RTX 50 시리즈 GPU 자산의 길이, 높이, 두께, 슬롯 폭, 전원 커넥터를 보강해 `size`, `power`, `performance`, `price` Tool 후보로 사용 가능하게 함 |
| `apps/api/src/main/resources/db/migration/V23__team_shared_naver_offer_price_seed.sql` | 네이버 검색 API로 갱신한 대표 상품 이미지, 공급업체, 현재가, 가격 이력 1건을 팀 공통 seed로 고정 |
| `apps/api/src/main/resources/db/migration/V24__agent_rag_asset_knowledge_seed.sql` | 내부 자산/가격/AS 기준을 Agent가 검색할 수 있는 재사용 RAG 지식 청크로 고정 |
| `apps/api/src/main/resources/db/migration/V25__requirement_parse_rag_seed.sql` | 요구사항 파싱용 RAG seed |
| `apps/api/src/main/resources/db/migration/V26__premium_intent_requirement_parse_rag.sql` | 최고급/예산무관 의도 해석용 RAG 보강 |
| `apps/api/src/main/resources/db/migration/V27__rag_v2_policy_example_seed.sql` | RAG v2 정책, 예시, 반례 seed |
| `apps/api/src/main/resources/db/migration/V28__quote_drafts.sql` | 로그인 사용자별 수동 견적초안 저장 테이블 |
| `apps/api/src/main/resources/db/migration/V29__quote_draft_category_policy.sql` | 수동 견적 카테고리별 수량/중복 정책 |
| `apps/api/src/main/resources/db/migration/V30__auth_seed_password_hashes.sql` | 팀 공통 USER/ADMIN seed 계정 비밀번호 hash |
| `apps/api/src/main/resources/db/migration/V31__as_chat_sessions.sql` | AS Chat 세션과 메시지 저장 테이블 |
| `apps/api/src/main/resources/db/migration/V32__llm_generations.sql` | AS Chat LLM 호출 profile, latency, token, schema 검증 기록 |
| `apps/api/src/main/resources/db/migration/V34__llm_generations_nano_profile.sql` | AS Chat nano profile과 OpenAI profile constraint 보강 |
| `apps/api/src/main/resources/db/migration/V35__correct_corsair_ram_offer_seed.sql` | Corsair RAM 자산에 잘못 매칭된 방열판 offer를 실제 48GB DDR5 kit offer와 가격으로 교정 |

공통 seed inventory:

| 도메인 | seed 내용 | 용도 |
| --- | --- | --- |
| User/Auth | USER 1명, ADMIN 1명, Google provider 1건, refresh token 2건 | 로그인, 관리자 guard, owner별 사용자 FK 확인 |
| Parts | CPU, MOTHERBOARD, RAM, GPU, STORAGE, PSU, CASE, COOLER 최신 라인업 ACTIVE 내부 자산. 주요 자산은 대표 상품 이미지/공급업체 offer 포함. Tool 계산은 `attributes.toolReady=true`이고 공식 스펙 필수 필드가 채워진 자산을 기준으로 함 | 부품 목록, Build item, Tool skeleton, 업그레이드 후보 |
| Price | price snapshot 3건, active price alert 1건, completed price job 1건 | 가격 알림, 관리자 가격 작업 화면 |
| Quote/Build | requirement 1건, build 2건, build_items 8건 | AI 견적 결과, build 상세, 내 견적함 |
| Agent/RAG/Tool | agent session 2건, tool invocation 3건, 세션별 rag evidence 3건, 재사용 RAG 지식 청크 8건 | Agent 실행 흐름, 관리자 근거/Tool 상세, Agent 실행 시 검색할 내부 자산/가격/AS 기준 |
| Support/AS | agent log upload 1건, AS ticket 2건 | AS 접수/상세, 관리자 AS 목록 |
| Admin | audit log 3건 | 관리자 최근 이력 |

DB 계약이나 seed 기준을 바꾼 뒤 깨끗한 DB로 다시 확인하려면 아래처럼 실행합니다.

```powershell
docker compose down -v
docker compose up --build
```

## VS Code 컨테이너로 열기

로컬 Node/Java/Python 버전을 맞추기 어렵다면 VS Code Dev Containers로 작업합니다.

준비:

- Docker Desktop 실행
- VS Code 확장 `Dev Containers` 설치

실행:

1. VS Code에서 이 저장소 폴더를 엽니다.
2. `Ctrl + Shift + P`를 누릅니다.
3. `Dev Containers: Reopen in Container`를 실행합니다.
4. 컨테이너가 열린 뒤 필요한 서비스는 터미널에서 실행합니다.

```powershell
docker compose up --build
```

`.devcontainer/devcontainer.json`은 Node 22, Java 21, Python 3.11, Docker CLI를 포함합니다. 컨테이너가 처음 열릴 때 `scripts/setup-dev.sh`가 실행되어 웹 의존성과 Python 도구 의존성을 설치합니다.

## Agent LLM 실행

기본 실행은 OpenAI API 키가 없어도 동작하는 `deterministic` runner입니다. 3번 Agent/RAG 담당 기능에서 실제 LLM summary가 관리자 화면까지 표시되는 흐름을 확인하려면 저장소 루트에 `.env`를 만들고 아래 값을 넣습니다.

```env
AGENT_RUNNER_MODE=llm
OPENAI_API_KEY=sk-...
OPENAI_MODEL=gpt-5.5
OPENAI_REASONING_EFFORT=medium
PC_AGENT_DIAGNOSIS_CHAT_MODEL=gpt-5.4-mini
PC_AGENT_DIAGNOSIS_CHAT_REASONING_EFFORT=low
PC_AGENT_DIAGNOSIS_CHAT_MAX_OUTPUT_TOKENS=750
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

그 다음 Docker를 다시 올립니다.

```powershell
docker compose down -v
docker compose up --build
```

검증 흐름:

1. `POST /api/agent/sessions`로 Agent 세션을 생성합니다.
2. `POST /api/agent/sessions/{id}/run`을 호출합니다.
3. `GET /api/admin/agent-sessions/{id}`에서 `summary`가 LLM 생성 문장으로 표시되는지 확인합니다.

`OPENAI_API_KEY`는 절대 커밋하지 않습니다. 저장소에는 `.env.example`만 유지합니다.

AS Chat profile별 품질/속도 비교는 API가 실행 중일 때 아래 명령으로 수행합니다.

```powershell
python tools/benchmark_as_chat_profiles.py
```

결과는 `docs/reports/as-chat-profile-benchmark-YYYYMMDD.md`에 생성됩니다. 일반 사용자 요청은 `AS_CHAT_DEFAULT_PROFILE` 하나만 실행하며, benchmark 스크립트는 기본적으로 현재 사용자 기본값인 `AS_CHAT_54_MINI_FAST`만 실행합니다. 모델 후보를 다시 비교할 때만 `--profiles AS_CHAT_FAST AS_CHAT_54_FAST AS_CHAT_54_MINI_FAST AS_CHAT_NANO_FAST AS_CHAT_BALANCED`처럼 명시합니다. 기본값은 실측 결과 기준 `AS_CHAT_54_MINI_FAST`와 `BUILD_CHAT_54_MINI_FAST`입니다. `AS_CHAT_FAST`와 `BUILD_CHAT_FAST`는 rollback 후보이고, `AS_CHAT_NANO_FAST`는 schema 실패 추적용 실험 profile입니다. 되돌릴 때는 `.env`에서 `AS_CHAT_DEFAULT_PROFILE=AS_CHAT_FAST`, `BUILD_CHAT_DEFAULT_PROFILE=BUILD_CHAT_FAST`로 지정합니다. `/support/ai-chat` 화면은 `POST /api/ai/as-chat/stream`을 우선 사용해 첫 진행 상태와 최종 응답 시간을 따로 검증합니다.

RAG vector 정책 비교는 public 검색 API 기준 retrieval benchmark와 live AI benchmark를 분리해서 봅니다.

```powershell
python tools/benchmark_rag_retrieval.py --variant-label vector-on
python tools/benchmark_as_chat_profiles.py --variant-label vector-on
python tools/benchmark_build_chat_profiles.py --variant-label vector-on
```

경로별로 vector를 꺼서 비교할 때는 API를 재기동합니다. 예를 들어 AS Chat만 끄려면 `RAG_VECTOR_AS_ANALYZE_ENABLED=false`를 설정하고 `docker compose up --build -d api` 후 같은 명령을 `--variant-label as-vector-off`로 다시 실행합니다. 이번 단계에서는 `RAG_VECTOR_ENABLED=true` 기본값을 유지하고, 보고서에서 경로별 권장 정책만 남깁니다.

## 홈 추천부품 XGBoost scorer

홈 인기 부품 랭킹은 `GET /api/recommendations/home-parts`가 반환합니다. 현재 홈 화면은 `limit=8`로 2줄 랭킹을 표시하며, API의 기본값은 파라미터를 생략했을 때 4개입니다. Docker Compose는 `xgb-reranker` 서비스를 함께 실행합니다. `ACTIVE` 모델이 있으면 API가 scorer를 동기 호출해 XGBoost score를 홈 추천부품 순서에 반영하고(`scoreSource=XGBOOST`), scorer가 baseline임이 확인되면 홈 응답을 scorer 호출로 블로킹하지 않고 deterministic 순위로 즉시 응답하면서(`scoreSource=FALLBACK`) 백그라운드로 shadow 점수만 기록합니다. 같은 후보 집합의 shadow 기록은 기본 5분 스로틀이 적용되고(`recommendation.reranker.shadow-throttle-ms`), 쌓인 shadow 점수는 매일 03:40 KST에 30일 초과분이 삭제됩니다(`recommendation.shadow.retention-days`/`retention-cron`, Docker에서 바꾸려면 compose.yaml에 env 매핑 추가 필요). 모델을 활성화/은퇴하면 서빙 모드가 즉시 전환됩니다. 승급 게이트, 서빙 모드, 학습 데이터 정책 등 추천 운영 상세는 [docs/RECOMMENDATION_OPERATIONS.md](docs/RECOMMENDATION_OPERATIONS.md)를 참고합니다.

권장 운영 순서:

```powershell
docker compose up --build -d api web xgb-reranker
```

1. 관리자 계정으로 `/admin`에 접속합니다.
2. `AI 추천 모델 상태`의 `XGBoost 학습 운영`에서 dataset을 생성합니다.
3. dataset이 `DRAFT`인 동안 노출만 있는 약한 이벤트 등을 include/exclude로 정리합니다.
4. dataset을 `LOCKED`로 잠급니다.
5. 학습 Job을 생성합니다. API는 DB에 `QUEUED` Job만 만들고, `xgb-reranker` worker가 학습을 처리합니다.
6. 성공한 모델은 `SHADOW`로만 저장되며, 최근 20% 시간 기반 holdout 지표(NDCG@4, Spearman, MAE/RMSE)가 `metrics.holdout`으로 함께 기록됩니다. holdout 지표를 확인한 뒤 `활성화`를 누르면 scorer reload 성공 후 `ACTIVE`로 전환됩니다. holdout 지표가 없는 구버전 모델은 활성화가 `409`로 거절되므로 최신 워커로 재훈련합니다. 활성화 성공 시 기존 `ACTIVE` 모델은 자동으로 `RETIRED` 처리됩니다.

기본 학습은 50행 미만이면 `SKIPPED_LOW_DATASET`으로 끝나며 모델 버전을 만들지 않습니다. CLI export/train은 로컬 분석 또는 데모 보조용으로 남겨두며, 운영 기본 흐름은 관리자 dataset/job UI입니다. 주의: CLI 학습 도구(`tools/train_xgb_reranker.py`)는 현재 서빙 피처 계약과 다릅니다(서빙 `FEATURES`는 `rank_position` 제외, CLI 학습/추출은 `rank_position` 포함). CLI로 만든 모델을 Docker scorer에 직접 연결하면 scoring이 실패해 홈이 항상 FALLBACK으로 떨어지므로, 운영 모델은 반드시 관리자 dataset/job UI(워커 학습)로 만듭니다.

```powershell
python tools/export_recommendation_training_data.py --home-parts --output artifacts/recommendation/training-home-parts.csv
python tools/train_xgb_reranker.py --input artifacts/recommendation/training-home-parts.csv --output-dir artifacts/recommendation/model --allow-small-dataset
```

Docker scorer의 기본 active 모델 경로는 `/models/home-parts-active.json`입니다. `/models`는 기본적으로 Docker named volume `recommendation-models`에 저장되므로 macOS Docker Desktop의 File Sharing 설정 없이 `docker compose up --build`가 동작합니다. 이 파일은 관리자 activate API가 scorer reload에 성공했을 때 갱신됩니다. 특정 모델 파일을 고정 테스트하려면 `.env`에 모델 경로를 넣고 scorer/API를 재기동합니다.

```env
RECOMMENDATION_RERANKER_MODEL_PATH=/models/home-parts-active.json
```

CLI로 만든 `artifacts/recommendation/model` 파일을 Docker scorer에 직접 연결해야 할 때만 아래처럼 bind mount로 바꿉니다(위 피처 계약 주의 — 로컬 실험 용도로만 사용). macOS Docker Desktop에서는 이 host 경로가 File Sharing에 포함되어 있어야 합니다.

```env
RECOMMENDATION_RERANKER_MODEL_VOLUME=./artifacts/recommendation/model
```

```powershell
docker compose up --build -d xgb-reranker api
```

운영 상태는 관리자 계정으로 `/admin`에 접속해 `AI 추천 모델 상태` 카드에서 확인합니다.

## 네이버 쇼핑 검색 연동

셀프 견적 화면은 항상 DB에 저장된 내부 자산 `parts`를 읽습니다. 사용자 부품 조회 API는 네이버 쇼핑 검색 API를 직접 호출하지 않습니다. 외부 API는 관리자 갱신 작업에서만 호출하고, 갱신 결과는 `part_catalog_candidates` 후보로 쌓은 뒤 검증 또는 자동 게시 옵션을 통해 `parts`에 반영합니다.

상품 사진과 공급업체 표시는 `part_external_offers` 캐시를 읽습니다. 저장소 루트 `.env`에 아래 값을 넣고 API를 재빌드한 뒤, 관리자 갱신 API를 호출해 내부 자산과 외부 상품 캐시를 채웁니다. 갱신 작업이 `part_external_offers.low_price`를 저장하면 같은 값을 `parts.price`와 `price_snapshots`에도 반영하므로, 사용자 화면의 가격/정렬/필터는 마지막으로 저장된 네이버 검색 가격 기준으로 동작합니다. 상품별 가격변동 추이는 `GET /api/parts/{id}/price-history`가 `price_snapshots`를 읽어서 반환합니다.

API 서버가 실행 중이고 네이버 키가 설정되어 있으면 `part.price-refresh.cron`에 따라 한국시간 매일 04:00에 `part_external_offers`를 자동 갱신합니다. 기본 수동 갱신도 `force=true`가 없으면 최근 1일 안에 갱신된 상품을 건너뜁니다.

네이버 API 호출에는 connect 5초/read 10초 타임아웃과 요청 간 150ms 지연이 적용됩니다(`naver.search.connect-timeout-ms`/`read-timeout-ms`/`request-delay-ms` 프로퍼티, 연속 오류 시 비례 백오프 후 조기 중단. Docker에서 바꾸려면 compose.yaml에 env 매핑 추가 필요). API 오류(429/5xx/타임아웃)는 상품 미매칭(`skipped`)과 구분된 `errors`로 집계됩니다. 자동 갱신을 포함한 스케줄 잡 실행 결과는 `pipeline_job_runs`에 기록되어 관리자 가격 작업 화면의 `스케줄 실행 이력`과 `GET /api/admin/pipeline-job-runs`로 확인할 수 있으며, `DEMO_FREEZE_MUTATIONS=true`면 자동/수동 갱신이 모두 동결됩니다.

```env
NAVER_SEARCH_CLIENT_ID=...
NAVER_SEARCH_CLIENT_SECRET=...
NAVER_SEARCH_BASE_URL=https://openapi.naver.com
PART_PRICE_REFRESH_ENABLED=true
PART_PRICE_REFRESH_CRON="0 0 4 * * *"
PART_PRICE_REFRESH_ZONE=Asia/Seoul
```

캐시 갱신:

```powershell
$login = Invoke-RestMethod -Method Post "http://localhost:8080/api/auth/login" `
  -ContentType "application/json" `
  -Body (@{ email = "admin@example.com"; password = "passw0rd!" } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

Invoke-RestMethod -Method Post `
  -Headers $headers `
  "http://localhost:8080/api/admin/parts/external-offers/refresh?category=GPU&limit=20"
```

내부 자산 후보 대량 수집:

```powershell
Invoke-RestMethod -Method Post `
  -Headers $headers `
  "http://localhost:8080/api/admin/parts/catalog/refresh?category=GPU&limitPerQuery=3"
```

후보를 바로 내부 자산으로 게시하면서 수집:

```powershell
Invoke-RestMethod -Method Post `
  -Headers $headers `
  "http://localhost:8080/api/admin/parts/catalog/refresh?category=GPU&limitPerQuery=3&publish=true"
```

`catalog/refresh`는 category별 query pack을 사용합니다. GPU는 RTX 5090/5080/5070 Ti/5070/5060 Ti/5060을 ASUS, MSI, GIGABYTE, ZOTAC, PNY 등 제조사 검색어로 나누어 수십 개 후보를 모읍니다. MOTHERBOARD, PSU도 주요 제조사와 최신 규격 검색어 묶음을 사용합니다.

키가 없거나 캐시가 없으면 `/api/parts`는 정상 동작하지만 `externalOffer`는 `null`이고, 웹은 기본 썸네일과 공급업체 `-`로 표시합니다. 네이버 키는 절대 커밋하지 않습니다.

## 제조사 릴리스 인테이크

신제품 자산 후보 수집 파이프라인입니다. 공식 제조사 피드를 읽어 `manufacturer_posts`로 저장하고, 제품 후보로 판정된 게시글을 `part_catalog_candidates`로 만듭니다. AI 초안(`ai-asset-draft`)은 게시글 구조화와 후보 생성/동기화까지만 수행하며, `INACTIVE` 자산 초안은 관리자가 후보를 검수한 뒤 approve로만 만듭니다.

- 기본 OFF입니다. `PART_MANUFACTURER_RELEASE_INTAKE_ENABLED=true`면 한국시간 매일 06:00(`PART_MANUFACTURER_RELEASE_INTAKE_CRON`)에 전체 scan이 실행됩니다.
- 전체 scan은 source별 `poll_interval_minutes` 주기가 도래한 활성 source만 대상으로 하며, 저장된 ETag/Last-Modified로 조건부 GET을 보내 304면 본문을 다시 받지 않습니다. 외부 호출에는 connect 10초/read 20초 타임아웃이 적용됩니다(`part.manufacturer-release-intake.connect-timeout-ms`/`read-timeout-ms`).
- 차단성 응답(403/429)이 연속 3회 누적되면 해당 source는 `ERROR`가 아니라 `PAUSED`로 자동 전환되어 scan 대상에서 빠집니다. 관리자 화면에서 원인을 확인한 뒤 수동으로 재개합니다. UA 위장으로 차단을 우회하지 않습니다.
- scan 결과는 `pipeline_job_runs`에 기록되며 `DEMO_FREEZE_MUTATIONS=true`면 동결 대상입니다.
- 데모 RSS 피드 `/api/demo/manufacturer-release-feed.xml`은 `PART_MANUFACTURER_RELEASE_DEMO_FEED_ENABLED=true`인 로컬/데모 환경에서만 노출됩니다(기본 false, Docker Compose 로컬 스택은 true).

## 데모 동결과 스케줄 잡 실행 이력

데모 시연 직전에는 `.env`에 `DEMO_FREEZE_MUTATIONS=true`를 넣고 API를 재기동합니다. 가격/다나와/추이/제조사 스캔 스케줄러 4종이 일괄 동결되어 실행 이력에 `SKIPPED_FROZEN`으로 남고, 관리자 가격 Job 실행(`POST /api/admin/price-jobs/run`)은 `409`로 거절됩니다. 읽기 API에는 영향이 없습니다. 데모가 끝나면 `false`로 되돌리고 다시 재기동합니다.

스케줄 파이프라인 잡(네이버 가격, 다나와 스냅샷/추이, 제조사 릴리스 스캔, 추천 shadow 보존 정리)의 실행 이력은 `pipeline_job_runs`에 기록됩니다. 관리자 가격 작업 화면의 `스케줄 실행 이력` 패널 또는 `GET /api/admin/pipeline-job-runs`로 확인합니다.

| status | 의미 |
| --- | --- |
| `SUCCEEDED` / `FAILED` | 정상 완료 / 실패(`errorSummary` 기록) |
| `SKIPPED_FROZEN` | `DEMO_FREEZE_MUTATIONS=true` 동결로 건너뜀 |
| `SKIPPED_LOCKED` | 다른 인스턴스가 같은 잡을 실행 중이어서 건너뜀 |

API 인스턴스를 여러 개 띄워도 스케줄 잡은 잡 이름 단위 Postgres advisory lock으로 상호배제되며, 락을 못 잡은 인스턴스는 실행하지 않고 `SKIPPED_LOCKED` 이력만 남깁니다(잡 실행 중 DB 커넥션 1개 점유). 홈 추천 파이프라인의 승급/롤백/서빙 모드 등 운영 규칙은 [docs/RECOMMENDATION_OPERATIONS.md](docs/RECOMMENDATION_OPERATIONS.md)에 있습니다.

## Java 21 로컬 설치

API는 Java 21을 기준으로 빌드합니다. 로컬 Java 버전이 다르면 `./gradlew bootJar --no-daemon`이 실패할 수 있으므로 백엔드 코드를 수정하는 팀원은 Java 21을 설치합니다.

현재 Java 버전 확인:

```bash
java -version
```

macOS Homebrew 예시:

```bash
brew install --cask temurin@21
/usr/libexec/java_home -V
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
java -version
```

Windows에서는 Eclipse Temurin 21 또는 Microsoft Build of OpenJDK 21을 설치한 뒤 새 터미널에서 `java -version`을 확인합니다. 로컬 버전 맞추기가 어렵다면 Dev Container를 기준 환경으로 사용합니다.

## 로컬 의존성 한 번에 설치

Docker만 사용할 때는 이 단계가 필요 없습니다. 로컬에서 프론트엔드, OpenAPI 검증, PC Agent를 직접 실행할 팀원은 아래 스크립트를 한 번 실행합니다.

Windows:

```powershell
.\scripts\setup-dev.ps1
.\.venv\Scripts\Activate.ps1
```

macOS/Linux:

```bash
bash scripts/setup-dev.sh
source .venv/bin/activate
```

설치 스크립트는 `apps/web`의 npm 의존성, Playwright Chromium 브라우저, `tools/requirements.txt`, `apps/pc-agent/requirements.txt`를 설치합니다. 백엔드 Gradle 의존성은 `bootRun` 또는 `bootJar` 실행 시 Gradle Wrapper가 받습니다.

## 개발 명령어

프론트엔드:

```powershell
cd apps/web
npm ci
npm run dev
npm run test
```

백엔드:

```powershell
cd apps/api
.\gradlew.bat bootRun
```

macOS/Linux에서는 `./gradlew bootRun`을 사용합니다. 로컬 Java 버전이 맞지 않으면 Docker 기준으로 실행합니다.

```powershell
docker compose up --build api
```

OpenAPI 검증:

```powershell
python tools/validate_openapi.py
```

macOS/Linux에서 `python` 명령이 없다면 `python3 tools/validate_openapi.py`를 사용합니다.

PC Agent 샘플 로그:

```powershell
cd apps/pc-agent
pip install -r requirements.txt
python buildgraph_agent.py sample --out ../../seed/sample-agent-log.jsonl
python buildgraph_agent.py export --source ../../seed/sample-agent-log.jsonl --out recent-30m.jsonl --minutes 30
```

macOS/Linux에서 `pip` 또는 `python` 명령이 없다면 `pip3`, `python3`를 사용합니다.

## PR 전 확인

저장소 루트에서 아래 명령을 실행합니다.

```bash
npm --prefix apps/web run build
npm --prefix apps/web run test
python tools/validate_openapi.py
docker compose config
```

백엔드 코드를 수정했다면 Java 21 또는 Docker 환경에서 API 빌드도 확인합니다.

Windows:

```powershell
cd apps/api
.\gradlew.bat bootJar --no-daemon
```

macOS/Linux:

```bash
cd apps/api
./gradlew bootJar --no-daemon
```

## 협업 규칙

- 자기 담당 feature/domain 안에서 먼저 작업합니다.
- API 요청/응답 구조를 바꾸면 같은 PR에서 [docs/API_CONTRACT.md](docs/API_CONTRACT.md)와 [docs/openapi.yaml](docs/openapi.yaml)을 함께 수정합니다.
- DB 테이블, 컬럼, enum, 상태 전이를 바꾸면 같은 PR에서 [docs/DB_SCHEMA.md](docs/DB_SCHEMA.md)를 함께 수정합니다.
- route, owner, 공유 파일 경계를 바꾸면 같은 PR에서 [docs/ROUTE_OWNERSHIP.md](docs/ROUTE_OWNERSHIP.md)를 함께 수정합니다.
- mock 데이터는 담당 feature의 `mocks` 디렉터리에 둡니다.
- 팀 공통 DB seed는 Flyway migration에 둡니다. `*Seed.java`는 DB 연결 전 임시 응답이나 단위 테스트용으로만 사용합니다.
- `components/ui.tsx`, `prototypeData.ts`, `QuotePages.tsx`, `AdminPages.tsx`는 barrel 용도입니다. 새 구현을 쌓지 않습니다.
- 결제/배송/원격제어/최저가/FPS 보장은 이후 Sprint에서 별도 기능으로 다룹니다.

## API와 DB 연결 책임

각 담당자는 자기 API를 자기 DB 테이블에 연결합니다. 다른 담당자의 API를 대신 DB 연결하거나, 다른 담당자 테이블의 쓰기 로직을 임의로 구현하지 않습니다.

| 담당 | API 연결 책임 | 주로 연결할 DB 테이블 | 직접 해도 되는 작업 | 먼저 상의할 작업 |
| --- | --- | --- | --- | --- |
| 1번 Quote/Auth | 견적 입력, 추천 Build 화면, 내 견적함, 로그인/회원가입 화면/API | `requirements`, `builds`, `build_items`, `users`, `user_auth_providers`, `refresh_tokens` | 1번 route와 quote/auth feature에서 견적 API와 Auth/User API 응답을 연결 | Auth token 저장 방식, Build 생성 과정에서 Agent 추적 데이터 쓰기 |
| 2번 Parts/Price/Tool | 부품 목록, 부품 상세, 내부 자산 갱신, 가격 알림, 가격 작업, 호환성/전력/규격/성능/가격 Tool | `parts`, `part_external_offers`, `part_catalog_refresh_jobs`, `part_catalog_candidates`, `price_snapshots`, `price_alerts`, `price_jobs`, `compatibility_rules`, `benchmark_summaries` | 부품/가격 API를 DB 조회로 전환, Tool 계산 로직 구현 | Agent 내부 Tool 호출 이력 저장 방식, `price_jobs`/내부 자산 갱신 실행 권한과 중복 실행 정책 |
| 3번 Agent/RAG/Tool 근거 | Agent session, Tool invocation, RAG evidence 관리자 상세 | `agent_sessions`, `tool_invocations`, `rag_evidence` | Agent/RAG/Tool 추적 API를 DB 조회/저장으로 전환 | 1번 추천 생성 로직, 2번 Tool 계산 결과 구조, 4번 AS 분석 트리거 방식 |
| 4번 Support/PC Agent | 로그 업로드, AS 접수, AS 티켓 상세, PC Agent CLI | `agent_log_uploads`, `as_tickets` | 로그 업로드/AS 티켓 API를 DB 저장으로 전환 | 로그 분석 Agent session 생성, 로그 보관/삭제 스케줄러 |
| 5번 Admin/Auth Common/Infra | 관리자 shell, 공통 admin dashboard, audit, auth 공통 연동, security review, migration | `admin_audit_logs` | `api.ts` token 전달, `RequireAdmin`, admin 401/403, Health, Flyway 순서 관리 | Auth/User 구현 세부, 각 도메인 관리자 상세의 내부 데이터 구조 변경 |

공통 기준:

- API path와 response의 `id`는 내부 `BIGINT id`가 아니라 `public_id`입니다.
- 다른 담당자 테이블은 기본적으로 읽기만 하고, 쓰기가 필요하면 해당 owner와 API/DTO를 먼저 맞춥니다.
- enum/status, table/column, shared DTO, 권한 정책을 바꾸면 관련 문서를 같은 PR에서 함께 수정합니다.
- Flyway migration 번호, 실행 순서, extension, 공통 enum/check constraint, FK 순서 조정은 5번 owner가 관리합니다.
- 공통 seed는 기능 구현을 시작하기 위한 기준 데이터입니다. 실제 크롤링 데이터나 운영 데이터로 취급하지 않습니다.

## CI

Pull Request와 `main`, `dev` push에서 GitHub Actions가 다음을 확인합니다.

- 웹 의존성 설치, build, 17개 route smoke test
- OpenAPI YAML 및 핵심 POST requestBody 검증
- API `bootJar` 빌드
- Docker Compose config 검증
- API jar 실행 후 `/api/health` runtime smoke

CI 실행 조건:

| 상황 | CI 실행 여부 |
| --- | --- |
| 개인 브랜치에 그냥 push | 실행 안 됨 |
| 개인 브랜치에서 PR 생성 | 실행됨 |
| PR에 새 commit push | 실행됨 |
| PR을 dev로 merge | 실행됨 |
| PR을 main으로 merge | 실행됨 |
| main에 직접 push | 실행됨 |
| dev에 직접 push | 실행됨 |

브랜치 보호 권장 설정:

| 브랜치 | 설정 |
| --- | --- |
| `main` | PR 필수, `Build and smoke test` required check, force push 금지, 삭제 금지 |
| `dev` | PR 필수, `Build and smoke test` required check, force push 금지, 삭제 금지 |

CI 실패 시 먼저 확인할 GitHub Actions step:

| 실패 위치 | 확인할 내용 |
| --- | --- |
| `Build web` | TypeScript 또는 Vite build 오류 |
| `Run web route smoke tests` | Playwright route, admin guard, 화면 렌더링 오류 |
| `Validate OpenAPI YAML` | `docs/openapi.yaml` 경로, schema, requestBody 누락 |
| `Build API` | Java 21, Gradle, Spring compile/package 오류 |
| `Validate Docker Compose` | `compose.yaml` 문법, service, volume, port 설정 오류 |
| `Run API runtime smoke test` | PostgreSQL health, API jar 실행, `/api/health` DB 연결 오류 |

CI가 안정적으로 통과하면 다음 단계로 Docker image build 검증을 추가합니다. 이 단계는 이미지를 registry에 push하지 않고 `docker build`만 실행해 Dockerfile이 깨졌는지 확인합니다. CD와 AWS 배포 자동화는 CI가 안정화된 뒤 별도 workflow로 설계합니다.
