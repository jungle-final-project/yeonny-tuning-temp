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

## 빠른 실행

Docker Desktop이 켜져 있으면 아래 명령만으로 웹, API, DB, Redis, RabbitMQ, Mailpit을 함께 실행할 수 있습니다.

```powershell
git clone https://github.com/jungle-final-project/prototype.git
cd prototype
docker compose up --build
```

서비스 주소:

| 서비스 | 주소 |
| --- | --- |
| 웹 | http://localhost:5173 |
| API health | http://localhost:8080/api/health |
| RabbitMQ 관리 화면 | http://localhost:15672 |
| Mailpit | http://localhost:8025 |

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

공통 seed inventory:

| 도메인 | seed 내용 | 용도 |
| --- | --- | --- |
| User/Auth | USER 1명, ADMIN 1명, Google provider 1건, refresh token 2건 | 로그인, 관리자 guard, owner별 사용자 FK 확인 |
| Parts | CPU, MOTHERBOARD, RAM, GPU, STORAGE, PSU, CASE, COOLER 각 1건 | 부품 목록, Build item, Tool skeleton, 업그레이드 후보 |
| Price | price snapshot 3건, active price alert 1건, completed price job 1건 | 가격 알림, 관리자 가격 작업 화면 |
| Quote/Build | requirement 1건, build 2건, build_items 8건 | AI 견적 결과, build 상세, 내 견적함 |
| Agent/RAG/Tool | agent session 2건, tool invocation 3건, rag evidence 3건 | Agent 실행 흐름, 관리자 근거/Tool 상세 |
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
OPENAI_MODEL=gpt-4.1-mini
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
| 1번 Quote/Auth 화면 | 견적 입력, 추천 Build 화면, 내 견적함, 로그인/회원가입 화면 | `requirements`, `builds`, `build_items` | 1번 route와 quote feature에서 견적 API 응답을 화면에 연결 | Auth token 저장 방식, Build 생성 과정에서 Agent 추적 데이터 쓰기 |
| 2번 Parts/Price/Tool | 부품 목록, 부품 상세, 가격 알림, 가격 작업, 호환성/전력/규격/성능/가격 Tool | `parts`, `price_snapshots`, `price_alerts`, `price_jobs`, `compatibility_rules`, `benchmark_summaries` | 부품/가격 API를 DB 조회로 전환, Tool 계산 로직 구현 | Agent 내부 Tool 호출 이력 저장 방식, `price_jobs` 실행 권한/중복 실행 정책 |
| 3번 Agent/RAG/Tool 근거 | Agent session, Tool invocation, RAG evidence 관리자 상세 | `agent_sessions`, `tool_invocations`, `rag_evidence` | Agent/RAG/Tool 추적 API를 DB 조회/저장으로 전환 | 1번 추천 생성 로직, 2번 Tool 계산 결과 구조, 4번 AS 분석 트리거 방식 |
| 4번 Support/PC Agent | 로그 업로드, AS 접수, AS 티켓 상세, PC Agent CLI | `agent_log_uploads`, `as_tickets` | 로그 업로드/AS 티켓 API를 DB 저장으로 전환 | 로그 분석 Agent session 생성, 로그 보관/삭제 스케줄러 |
| 5번 Auth/Admin/Infra | 인증 API, 관리자 shell, 공통 admin dashboard, audit, security, migration | `users`, `user_auth_providers`, `refresh_tokens`, `admin_audit_logs` | 인증/권한/관리자 공통 API와 Flyway 순서 관리 | 각 도메인 관리자 상세의 내부 데이터 구조 변경 |

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
