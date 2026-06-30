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
| `OPENAI_API_KEY` | AS Chat 실제 답변 테스트 시 필요 | `/support/ai-chat`, Agent LLM mode, AS Chat benchmark | 없으면 AS Chat 답변 생성은 `428 PRECONDITION_REQUIRED`로 실패하며, 화면은 키 필요 안내를 보여줍니다. |
| `NAVER_SEARCH_CLIENT_ID` | 가격/상품 갱신 시 필요 | 관리자 부품 offer 갱신, 내부 자산 후보 수집 | 없으면 seed에 저장된 내부 자산과 가격만 표시됩니다. |
| `NAVER_SEARCH_CLIENT_SECRET` | 가격/상품 갱신 시 필요 | 관리자 부품 offer 갱신, 내부 자산 후보 수집 | 네이버 검색 API secret입니다. 커밋 금지입니다. |
| `AGENT_RUNNER_MODE` | 선택 | Agent 실행 방식 | 기본값은 `deterministic`입니다. 실제 LLM summary를 보려면 `llm`으로 바꿉니다. |
| `AS_CHAT_DEFAULT_PROFILE` | 선택 | AS Chat 기본 profile | 기본값은 `AS_CHAT_FAST`입니다. |

팀 공통 테스트 계정:

| 권한 | 이메일 | 비밀번호 | 용도 |
| --- | --- | --- | --- |
| USER | `user@example.com` | `passw0rd!` | 쇼핑몰, 수동 견적, AS Chat |
| ADMIN | `admin@example.com` | `passw0rd!` | 관리자 화면, 가격 갱신 API |

서비스 주소:

| 서비스 | 주소 |
| --- | --- |
| 웹 | http://localhost:5173 |
| API health | http://localhost:8080/api/health |
| RabbitMQ 관리 화면 | http://localhost:15672 |
| Mailpit | http://localhost:8025 |

클린 풀 후 바로 확인할 화면:

| 화면 | 주소 | 확인할 내용 |
| --- | --- | --- |
| 홈 | http://localhost:5173 | 로그인 진입, 주요 쇼핑몰 메뉴 |
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
AS_CHAT_DEFAULT_PROFILE=AS_CHAT_FAST
AS_CHAT_FAST_MODEL=gpt-5.5
AS_CHAT_FAST_REASONING_EFFORT=low
AS_CHAT_FAST_RAG_TOP_K=2
AS_CHAT_FAST_MAX_OUTPUT_TOKENS=900
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

결과는 `docs/reports/as-chat-profile-benchmark-YYYYMMDD.md`에 생성됩니다. 일반 사용자 요청은 `AS_CHAT_DEFAULT_PROFILE` 하나만 실행하며, benchmark 스크립트만 내부 header로 profile들을 순차 비교합니다. 기본 비교군에는 `AS_CHAT_FAST`, `AS_CHAT_NANO_FAST`, `AS_CHAT_BALANCED`, `AS_CHAT_HIGH_QUALITY`가 포함됩니다. `/support/ai-chat` 화면은 `POST /api/ai/as-chat/stream`을 우선 사용해 첫 진행 상태와 최종 응답 시간을 따로 검증합니다.

## 네이버 쇼핑 검색 연동

셀프 견적 화면은 항상 DB에 저장된 내부 자산 `parts`를 읽습니다. 사용자 부품 조회 API는 네이버 쇼핑 검색 API를 직접 호출하지 않습니다. 외부 API는 관리자 갱신 작업에서만 호출하고, 갱신 결과는 `part_catalog_candidates` 후보로 쌓은 뒤 검증 또는 자동 게시 옵션을 통해 `parts`에 반영합니다.

상품 사진과 공급업체 표시는 `part_external_offers` 캐시를 읽습니다. 저장소 루트 `.env`에 아래 값을 넣고 API를 재빌드한 뒤, 관리자 갱신 API를 호출해 내부 자산과 외부 상품 캐시를 채웁니다. 갱신 작업이 `part_external_offers.low_price`를 저장하면 같은 값을 `parts.price`와 `price_snapshots`에도 반영하므로, 사용자 화면의 가격/정렬/필터는 마지막으로 저장된 네이버 검색 가격 기준으로 동작합니다. 상품별 가격변동 추이는 `GET /api/parts/{id}/price-history`가 `price_snapshots`를 읽어서 반환합니다.

API 서버가 실행 중이고 네이버 키가 설정되어 있으면 `part.price-refresh.cron`에 따라 한국시간 매일 04:00에 `part_external_offers`를 자동 갱신합니다. 기본 수동 갱신도 `force=true`가 없으면 최근 1일 안에 갱신된 상품을 건너뜁니다.

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
