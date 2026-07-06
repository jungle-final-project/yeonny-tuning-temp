# Agent AS E2E Happy Path

## 최소 데모 시나리오

Goal 4~8의 최소 데모는 다음 흐름을 성공시키는 것이다.

```text
Agent 등록
→ Agent token 발급/저장
→ 사용자 동의 저장
→ Heartbeat 성공
→ gzip/multipart 로그 업로드
→ AS Ticket 생성
→ Diagnosis 상태 생성
→ 관리자 supportDecision 확정
→ 웹 AS 페이지 또는 조회 API에서 상태 확인
```

## API 순서 초안

아래 API path는 구현 전 계약 확인이 필요하다. 현재 repo의 [API_CONTRACT.md](../API_CONTRACT.md)와 [openapi.yaml](../openapi.yaml)에는 기존 웹 JWT 기반 path가 일부 남아 있기 때문이다.

| 순서 | 단계 | 예상 API | 인증 | 핵심 필드 |
|---:|---|---|---|---|
| 1 | Agent 등록 | `POST /api/agent/devices/register` | 확인 필요 | activation token, device fingerprint, osVersion, agentVersion |
| 2 | Agent token 저장 | register response | activation/register 정책 | raw agent token은 응답 후 Agent 로컬 저장, 서버는 hash만 저장 |
| 3 | 사용자 동의 저장 | `POST /api/agent/consents` | Agent token | consentType, policyVersion, accepted |
| 4 | Heartbeat | `POST /api/agent/heartbeat` | Agent token | agentVersion, serviceStatus, policyVersion |
| 5 | 로그 업로드 | `POST /api/agent/log-uploads` 또는 계약 확정 path | Agent token | gzip/multipart file, rangeStartedAt, rangeEndedAt, schemaVersion |
| 6 | AS Ticket 생성 | 로그 업로드 처리 내부 또는 `POST /api/agent/as-tickets` | Agent token | logUploadId, symptomSummary |
| 7 | Diagnosis 상태 생성 | 내부 service/worker | 서버 내부 | asTicketId, analysisStatus |
| 8 | 관리자 결정 | 관리자 AS ticket update API | ADMIN JWT | supportDecision, reviewStatus, adminNote |
| 9 | 사용자 상태 확인 | `GET /api/as-tickets/{id}` 또는 웹 AS 페이지 | USER JWT | status, analysisStatus, supportDecision, nextActions |

## 현재 integration 데모 실행 순서

`integration/agent-as-e2e` 기준 최소 데모는 다음 순서로 검증한다. 이 절차는 Goal 4~7의 전체 기능 완성이 아니라 Goal 8 happy path 안정화용이다.

1. Agent 등록
   - `POST /api/agent/devices/register`
   - `Authorization` header를 보내지 않는다.
   - request body에 `activationToken: "demo-agent-activation-token"`을 포함한다.
   - response의 `agentToken`은 Agent가 최초 1회 저장하고, 서버 DB에는 hash만 저장한다.
2. 사용자 동의 저장
   - `POST /api/agent/consents`
   - headers:
     - `Authorization: Bearer <agentToken>`
     - `Idempotency-Key: consent-demo-1`
   - body: `consentType=SERVER_UPLOAD`, `accepted=true`
3. Heartbeat
   - `POST /api/agent/heartbeat`
   - headers:
     - `Authorization: Bearer <agentToken>`
     - `Idempotency-Key: heartbeat-demo-1`
   - 성공 시 device `lastSeenAt`과 heartbeat row가 갱신된다.
4. gzip multipart 로그 업로드
   - `POST /api/agent/log-uploads`
   - headers:
     - `Authorization: Bearer <agentToken>`
     - `Idempotency-Key: upload-demo-1`
   - multipart fields:
     - `file=@agent-log.jsonl.gz`
     - `rangeMinutes=30`
     - `symptom=GPU temperature spike`
   - 성공 시 `uploadJobId`, `logUploadId`, `ticketId`, `analysisStatus=RULE_READY`를 받는다.
5. 같은 upload 재시도
   - 같은 Agent, 같은 path, 같은 body, 같은 `Idempotency-Key: upload-demo-1`로 재전송한다.
   - 저장된 응답이 replay되어야 하며 새 ticket이 생성되면 안 된다.
6. 관리자 supportDecision 확정
   - `PATCH /api/admin/as-tickets/{ticketId}`
   - `Authorization: Bearer <adminJwt>`
   - body 예시:

```json
{
  "supportDecision": "REMOTE_POSSIBLE",
  "reviewStatus": "APPROVED",
  "adminNote": "Remote support link sent."
}
```

7. 사용자/웹 AS 상태 조회
   - `GET /api/as-tickets/{ticketId}`
   - `Authorization: Bearer <userJwt>`
   - `analysisStatus`, `reviewStatus`, `supportDecision`이 노출되는지 확인한다.

### gzip multipart 업로드 예시

```bash
printf '{"at":"2026-07-02T00:00:00Z","level":"WARN","message":"GPU temperature spike"}\n' | gzip > agent-log.jsonl.gz

curl -X POST http://localhost:8080/api/agent/log-uploads \
  -H "Authorization: Bearer ${AGENT_TOKEN}" \
  -H "Idempotency-Key: upload-demo-1" \
  -F "file=@agent-log.jsonl.gz;type=application/gzip" \
  -F "rangeMinutes=30" \
  -F "symptom=GPU temperature spike"
```

### 데모 실패 시 우선 확인 로그

- API boot 실패: `api-smoke.log` 또는 `docker compose logs api`
- DB migration 실패: Flyway error와 `flyway_schema_history`
- Agent 인증 실패: `Authorization: Bearer <agentToken>` 형식과 `agent_devices.agent_token_hash`
- mutation 400: `Idempotency-Key` 누락 또는 형식 오류
- upload 400: gzip 파일 여부, `rangeMinutes=30`, `SERVER_UPLOAD` consent 존재 여부
- supportDecision 400/409: 허용 enum과 ticket 상태 전이

## 요청/응답 핵심 필드

### Agent 등록

최소 구현 필드:

- request: activation token, device fingerprint hash, hostname hash, osVersion, agentVersion, policyVersion
- response: device public id, raw agent token, status

보강 대상:

- activation token 만료/폐기 처리
- registration idempotency
- 기기 재등록 정책
- token rotation 정책

### 사용자 동의

최소 구현 필드:

- request: consentType, policyVersion, accepted
- response: consent public id, acceptedAt 또는 revokedAt

보강 대상:

- consent source
- 동의 철회 이력
- 품질 개선 동의 분리

### Heartbeat

최소 구현 필드:

- request: agentVersion, serviceStatus, trayStatus, policyVersion
- response: server time, update policy, pending commands

보강 대상:

- delete request command
- update rollout command
- blocked/revoked device 처리

### 로그 업로드

최소 구현 필드:

- request: gzip/multipart file, schemaVersion, rangeStartedAt, rangeEndedAt, checksum
- response: upload job id, log upload id, status

보강 대상:

- 대용량 upload streaming
- virus/mime validation
- storage path 정책
- server retention/deleteAfter

### AS Ticket와 Diagnosis

최소 구현 필드:

- ticket: public id, status, logUploadId, symptomSummary
- diagnosis: analysisStatus, reviewStatus, supportDecision, riskLevel

보강 대상:

- AI 분석 세부 결과
- causeCandidates
- nextActions
- audit log

### 관리자 supportDecision

최소 구현 필드:

- supportDecision: `SELF_SOLVABLE`, `REMOTE_POSSIBLE`, `VISIT_REQUIRED`, `REPAIR_OR_REPLACE`, `NEEDS_MORE_INFO`, `MONITOR_ONLY`, `UNSUPPORTED`
- reviewStatus
- adminNote

보강 대상:

- 원격지원 외부 링크 생성
- 방문 예약 가능 시간대
- SLA 대시보드

## 최소 구현과 보강 대상 구분

| 영역 | 최소 구현 | 팀원 보강 대상 |
|---|---|---|
| Register | token 발급과 device 저장 | activation token UX, 재등록 정책 |
| Consent | 필수 동의 저장 | 철회/동의 이력 상세 |
| Heartbeat | 수신과 last seen 갱신 | command/update/delete 정책 |
| Upload | gzip/multipart 저장과 DB 연결 | streaming, storage hardening |
| Ticket | 로그 기반 ticket 생성 | 증상 분류, 상태 전이 고도화 |
| Diagnosis | 상태 생성 | AI 분석 품질, RAG 근거 |
| Support decision | 관리자 결정 저장 | remote/visit 운영 상세 |
| Web status | 사용자 조회 가능 | UI polish와 realtime update |

## 데모 성공 기준

- Agent 등록 후 token으로 `/api/agent/**` 인증이 성공한다.
- 웹 JWT로 `/api/agent/**` 접근하면 실패한다.
- Agent token으로 웹 JWT 보호 API 접근이 실패한다.
- Agent mutation 요청은 `Idempotency-Key` 없으면 실패한다.
- 같은 mutation 재시도는 중복 처리되지 않는다.
- 로그 업로드 후 AS ticket과 diagnosis 상태가 생성된다.
- 관리자가 supportDecision을 확정할 수 있다.
- 사용자가 웹 AS 페이지 또는 조회 API에서 상태를 확인할 수 있다.

## 확인 필요

- 현재 계약 문서에는 `POST /api/agent-logs/upload`, `POST /api/as-tickets`가 웹 JWT USER API로 정의되어 있다. PC Agent 직접 업로드와 AS ticket 생성 path는 Goal 6 전에 확정해야 한다.
- 현재 계약 문서에는 `/api/agent/sessions`가 웹 JWT USER API로 남아 있다. PC Agent 전용 `/api/agent/**` 원칙과 충돌 가능성이 있다.
