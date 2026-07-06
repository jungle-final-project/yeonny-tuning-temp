# Agent AS Demo Runbook

이 문서는 Agent AS 데모와 QA를 `main` 통합 직후 같은 방식으로 반복하기 위한 실행 기준이다. API 계약은 `docs/API_CONTRACT.md`와 `docs/openapi.yaml`을 기준으로 하고, PC Agent token 흐름은 `docs/agent-as/SECURITY.md`, `docs/agent-as/IDEMPOTENCY.md`, `docs/agent-as/E2E_HAPPY_PATH.md`를 따른다.

## 데모 계정

기본 seed 계정은 `README.md` 기준이다.

| role | email | password | 사용처 |
|---|---|---|---|
| USER | `user@example.com` | `passw0rd!` | `/support/new`, `/support/{ticketId}` |
| ADMIN | `admin@example.com` | `passw0rd!` | `/admin/as-tickets`, `/admin/as-tickets/{ticketId}` |

로그인 API:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8080/api/auth/login" `
  -ContentType "application/json" `
  -Body (@{ email = "admin@example.com"; password = "passw0rd!" } | ConvertTo-Json)
```

## API 경계

`/api/agent/**` 전체를 Agent token 전용으로 열지 않는다. PC Agent token 보안 체인은 아래 네 경로에만 적용한다.

| path | auth | idempotency |
|---|---|---|
| `POST /api/agent/devices/register` | activation token, no bearer auth | no |
| `POST /api/agent/consents` | Agent bearer token | required |
| `POST /api/agent/heartbeat` | Agent bearer token | required |
| `POST /api/agent/log-uploads` | Agent bearer token | required |

웹 JWT 기반 AI Agent/RAG 세션인 `/api/agent/sessions*`는 기존 웹 JWT 영역으로 유지한다. `AgentAccessTokenFilter`나 `AgentIdempotencyFilter`가 `/api/agent/sessions*`를 가로채면 안 된다.

## 데모 데이터 준비

1. DB seed가 적용된 API 서버를 실행한다.
2. USER 계정으로 `/support/new`에서 PC Agent를 다운로드하거나 샘플 JSONL fallback으로 AS 티켓을 만든다.
3. ADMIN 계정으로 `/admin/as-tickets/{ticketId}`에 진입한다.
4. `reviewStatus=APPROVED`, `supportDecision=REMOTE_POSSIBLE`, `riskLevel=MEDIUM`, `adminNote`, `remoteSupportLink`를 저장한다.
5. USER 계정으로 `/support/{ticketId}`를 새로 열어 `승인됨`, `원격 지원 가능`, 관리자 메모, 원격 지원 링크가 보이는지 확인한다.

Playwright QA는 이 흐름을 mock API로 반복 검증한다.

```powershell
cd apps\web
npm run test -- --reporter=dot
```

생성되는 시각 증거:

```text
artifacts/qa/agent-as/01-support-new.png
artifacts/qa/agent-as/02-support-ticket-before-decision.png
artifacts/qa/agent-as/03-admin-ticket-decision-fields.png
artifacts/qa/agent-as/04-support-ticket-after-decision.png
artifacts/qa/agent-as/05-mobile-ticket.png
```

## 다운로드 링크 확인

최신 main 기준 웹 `/support/new`에는 PC Agent demo exe 다운로드 링크가 있다.

| 항목 | 기준 |
|---|---|
| 웹 다운로드 URL | `/downloads/pc-agent/agent.exe` |
| repo 파일 경로 | `apps/web/public/downloads/pc-agent/agent.exe` |
| 다운로드 안내 | `apps/web/public/downloads/pc-agent/README.txt` |
| 로컬 CLI/빌드 소스 | `apps/pc-agent/buildgraph_agent.py`, `apps/pc-agent/build-agent-exe.cmd`, `apps/pc-agent/build-agent-exe.ps1` |

샘플 JSONL 다운로드는 PC Agent runtime이 없는 환경에서 수동 업로드 QA를 반복하기 위한 fallback으로 유지한다. 데모에서 사용자에게 안내할 표현은 다음처럼 고정한다.

- 현재 가능: 웹에서 demo `agent.exe` 다운로드
- 현재 가능: 샘플 JSONL 다운로드 후 웹 수동 업로드
- 현재 가능: `apps/pc-agent/buildgraph_agent.py` CLI로 register/status/doctor/upload 실행
- 아직 아님: signed installer, Windows Service, auto-update, signed release channel

`agent.exe` 파일이 바뀌면 아래를 다시 확인한다.

```powershell
Get-FileHash apps\web\public\downloads\pc-agent\agent.exe -Algorithm SHA256
python -m pytest apps\pc-agent
cd apps\web
npm run build
npm run test -- --reporter=dot
```

## OpenAPI 자동 검증

`tools/validate_openapi.py`는 다음을 필수로 검증한다.

- PC Agent lifecycle path 네 개 존재
- register는 bearer auth 없음
- consent/heartbeat/log upload는 `agentBearerAuth`와 `Idempotency-Key` 필요
- `/api/agent/sessions*`가 PC Agent token 계약으로 섞이지 않음
- `AsTicketDto`가 관리자 결정 표시 필드를 포함
- `AdminAsTicketUpdateRequest`가 관리자 결정 저장 필드를 포함

검증 명령:

```powershell
C:\Users\82103\anaconda3\python.exe tools\validate_openapi.py
```

로컬 `python`에 PyYAML이 없으면 위 Anaconda Python을 사용한다.

## OpenAPI 클라이언트 생성 검토

현재 프론트는 feature별 API wrapper(`supportApi.ts`, `adminApi.ts`)를 사용한다. 이번 작업에서는 generated client를 도입하지 않는다. 이유는 다음과 같다.

- 데모 직전 모든 API 호출부를 생성 클라이언트로 교체하면 충돌 범위가 커진다.
- 현재 필요한 계약 누락은 `validate_openapi.py`와 Playwright DTO 표시 테스트로 잡는다.
- generated client는 OpenAPI가 더 안정화된 뒤 별도 PR로 도입하는 편이 안전하다.

도입 검토 시 후보:

```powershell
cd apps\web
npx openapi-typescript ..\..\docs\openapi.yaml -o src\api\openapi-types.ts
```

도입 조건:

- generated type이 `supportApi.ts`, `adminApi.ts`의 DTO와 충돌하지 않아야 한다.
- `npm run build`, `npm run test -- --reporter=dot`, `python tools\validate_openapi.py`가 모두 통과해야 한다.
- 대규모 wrapper 교체는 기능 동결 기간 이후 별도 작업으로 분리한다.

## main merge 후 검증 명령

```powershell
git diff --check
C:\Users\82103\anaconda3\python.exe tools\validate_openapi.py

cd apps\api
.\gradlew.bat test --tests com.buildgraph.prototype.config.security.AgentSecurityChainTest --no-daemon
.\gradlew.bat test --tests com.buildgraph.prototype.config.security.PcAgentControllerSecurityTest --no-daemon
.\gradlew.bat test --tests com.buildgraph.prototype.agent.PcAgentAsServiceTest --no-daemon
.\gradlew.bat test --tests com.buildgraph.prototype.ticket.TicketQueryServiceTest --no-daemon
cd ..\..

cd apps\web
npm run build
npm run test -- --reporter=dot
cd ..\..
```

시간이 허용되면 `apps/api` 전체 테스트도 추가로 실행한다.

## 장애 대응

| 증상 | 우선 확인 | 조치 |
|---|---|---|
| `/api/agent/sessions`가 401 Agent token 오류 | `AgentSecurityConfig`, `AgentAccessTokenFilter` matcher | PC Agent exact path 네 개만 matcher인지 확인 |
| consent/heartbeat/log upload가 idempotency 없이 성공 | `AgentIdempotencyFilter` exact path | 세 mutation path에만 `Idempotency-Key` 필수인지 확인 |
| register가 bearer auth를 요구 | OpenAPI와 security chain | `POST /api/agent/devices/register`는 no bearer auth로 유지 |
| 관리자 결정 저장 후 사용자 화면 미반영 | `PATCH /api/admin/as-tickets/{id}` 응답, `GET /api/as-tickets/{id}` 응답 | `supportDecision`, `reviewStatus`, `riskLevel`, `adminNote`, `remoteSupportLink`가 응답에 포함되는지 확인 |
| 웹 화면에 raw enum 노출 | `StatusBadge`, `SupportPages`, `AdminTicketDetailPage` | `StatusBadge` 라벨 매핑에 enum 추가 |
| OpenAPI 검증 실패 | 실패 메시지의 path/schema | `docs/openapi.yaml`, `docs/API_CONTRACT.md`, wrapper type을 같은 변경에서 수정 |
| 샘플 업로드 실패 | 파일 확장자/JSONL/동의 체크 | `.jsonl` 또는 `.ndjson`, 최근 30분 범위, `consentAccepted=true` 확인 |

## 발표 시나리오

1. USER로 로그인한다.
2. `/support/new`에서 PC Agent demo exe 다운로드 링크와 샘플 JSONL fallback을 확인하고 AS 티켓을 만든다.
3. `/support/{ticketId}`에서 `규칙 진단 완료`, `검토 필요`, `추가 정보 필요` 상태를 보여준다.
4. ADMIN으로 로그인해 `/admin/as-tickets/{ticketId}`에 들어간다.
5. `APPROVED`, `REMOTE_POSSIBLE`, `MEDIUM`, 관리자 메모, 원격 지원 링크를 저장한다.
6. USER 화면으로 돌아와 `승인됨`, `원격 지원 가능`, 관리자 메모와 링크가 반영된 것을 보여준다.
7. PC Agent 직접 API는 테스트와 CLI/runtime으로 검증된 backend path임을 설명하고, demo exe는 제공되지만 signed installer, Windows Service, auto-update는 후속 범위라고 말한다.

## 범위 밖

- Quick Assist 직접 실행
- Windows Service, signed installer, auto-update
- signed installer, auto-update, release channel 운영
- diagnosis backend 로직 확장
- Agent token 발급 정책 변경
