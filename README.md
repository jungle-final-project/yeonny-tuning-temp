# BuildGraph AI 프로토타입

정글 최종 프로젝트 `나만의 무기 만들기`용 프로토타입 저장소입니다. 5명이 각자 담당 기능을 바로 구현할 수 있도록 공통 화면, API 계약, Docker 인프라, CI 출발점을 제공합니다.

## 먼저 읽을 문서

| 순서 | 문서 | 목적 |
| --- | --- | --- |
| 1 | [docs/role-workspaces.md](docs/role-workspaces.md) | 자기 담당 범위, 파일 소유권, PR 규칙 확인 |
| 2 | [docs/sprint-1-start-checklist.md](docs/sprint-1-start-checklist.md) | 첫 PR에서 무엇을 할지 확인 |
| 3 | [docs/architecture.md](docs/architecture.md) | 전체 구조와 런타임 흐름 확인 |
| 4 | [docs/scaffold-decisions.md](docs/scaffold-decisions.md) | 이번 Sprint에서 고정한 결정사항과 이후 작업 확인 |
| 5 | [docs/openapi.yaml](docs/openapi.yaml) | API 요청/응답 계약 확인 |

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

```powershell
cd apps/web
npm run build
npm run test

cd ../..
python tools/validate_openapi.py
docker compose config
```

백엔드 코드를 수정했다면 Java 21 또는 Docker 환경에서 API 빌드도 확인합니다.

```powershell
cd apps/api
.\gradlew.bat bootJar --no-daemon
```

## 협업 규칙

- 자기 담당 feature/domain 안에서 먼저 작업합니다.
- API 요청/응답 구조를 바꾸면 같은 PR에서 [docs/openapi.yaml](docs/openapi.yaml)을 함께 수정합니다.
- mock 데이터는 담당 feature의 `mocks` 디렉터리에 둡니다.
- seed 데이터는 담당 백엔드 domain의 `*Seed.java`에 둡니다.
- `components/ui.tsx`, `prototypeData.ts`, `QuotePages.tsx`, `AdminPages.tsx`는 barrel 용도입니다. 새 구현을 쌓지 않습니다.
- 결제/배송/원격제어/최저가/FPS 보장은 이후 Sprint에서 별도 기능으로 다룹니다.

## CI

Pull Request와 `main`, `dev` push에서 GitHub Actions가 다음을 확인합니다.

- 웹 의존성 설치, build, 17개 route smoke test
- OpenAPI YAML 및 핵심 POST requestBody 검증
- API test
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
