# Agent AS 팀 핸드오프

## 공통 인계 원칙

- Goal 1~3 인프라는 재설계하지 않는다.
- `/api/agent/**` 는 PC Agent 전용 API다.
- 웹 JWT와 Agent token은 섞지 않는다.
- mutation Agent API는 `Idempotency-Key`를 요구한다.
- 범위 밖 기능은 선구현하지 않는다.
- 계약이 불명확하면 확인 필요로 남기고 멈춘다.
- `stash@{0}`는 pop/apply/drop 하지 않는다.

## 팀원 A: Goal 4/5 보강

### 맡을 범위

- Goal 4: Agent Register + Consent
- Goal 5: Agent Heartbeat

### 건드리면 안 되는 범위

- Log Upload
- AS Ticket 생성
- Diagnosis Start
- AI Analysis
- 원격지원/방문지원 API
- 웹 화면
- Agent exe 구현

### 반드시 읽을 문서

1. [README.md](README.md)
2. [SECURITY.md](SECURITY.md)
3. [IDEMPOTENCY.md](IDEMPOTENCY.md)
4. [GOAL_BOUNDARIES.md](GOAL_BOUNDARIES.md)
5. [IMPLEMENTATION_GUIDE.md](IMPLEMENTATION_GUIDE.md)

### 테스트 추가 기준

- Register는 token 발급 전 단계 보안 정책을 테스트로 고정한다.
- Agent token 원문이 DB에 저장되지 않는지 검증한다.
- Consent mutation은 `Idempotency-Key`를 검증한다.
- Heartbeat mutation은 Agent token과 `Idempotency-Key`를 모두 검증한다.
- inactive/blocked/revoked device 처리 기준을 검증한다.

### 완료 보고 형식

```text
## Goal 4/5 결과

* 목표:
* 완료 여부:
* 수정한 파일:
* 추가한 테스트:
* Security Chain 충돌 해결:
* Idempotency-Key 적용:
* 실행한 검증 명령:
* 검증 결과:
* 확인 필요:
* 제안 커밋 메시지:
```

## 팀원 B: Goal 6/7 보강

### 맡을 범위

- Goal 6: Agent Log Upload + AS Diagnosis Start
- Goal 7: 원격지원/방문지원 API

### 건드리면 안 되는 범위

- Agent Register 재설계
- Agent Security Chain 재설계
- Idempotency 로직 재설계
- Agent exe 구현
- 웹 JWT와 Agent token 혼용
- AI 분석 품질 고도화 선구현

### 반드시 읽을 문서

1. [README.md](README.md)
2. [SECURITY.md](SECURITY.md)
3. [IDEMPOTENCY.md](IDEMPOTENCY.md)
4. [GOAL_BOUNDARIES.md](GOAL_BOUNDARIES.md)
5. [E2E_HAPPY_PATH.md](E2E_HAPPY_PATH.md)
6. [IMPLEMENTATION_GUIDE.md](IMPLEMENTATION_GUIDE.md)

### 테스트 추가 기준

- 로그 업로드는 Agent token을 요구한다.
- 로그 업로드 mutation은 `Idempotency-Key`를 요구한다.
- gzip/multipart validation 실패를 검증한다.
- 동의 없는 upload 실패를 검증한다.
- upload와 ticket/diagnosis DB 연결을 검증한다.
- 관리자 supportDecision은 ADMIN JWT를 요구한다.
- 잘못된 ticket 상태 전이는 `409 CONFLICT_STATE`를 반환한다.

### 완료 보고 형식

```text
## Goal 6/7 결과

* 목표:
* 완료 여부:
* 수정한 파일:
* 추가한 테스트:
* 업로드 저장/검증 방식:
* Diagnosis 시작 방식:
* 원격/방문지원 결정 방식:
* 실행한 검증 명령:
* 검증 결과:
* 확인 필요:
* 제안 커밋 메시지:
```

## 나: Goal 8 통합 검증/데모 안정화

### 맡을 범위

- Goal 4~7 전체 happy path 검증
- demo seed/fixture 확인
- smoke/E2E 명령 정리
- 실패 재현과 최소 수정 요청 정리

### 건드리면 안 되는 범위

- Goal 4~7 기능 확장
- 새 API 추가
- Security Chain 재설계
- Idempotency 로직 재설계
- 계약 문서 임의 변경

### 반드시 읽을 문서

1. 전체 `docs/agent-as/*.md`
2. [API_CONTRACT.md](../API_CONTRACT.md)
3. [DB_SCHEMA.md](../DB_SCHEMA.md)
4. [ROUTE_OWNERSHIP.md](../ROUTE_OWNERSHIP.md)
5. [openapi.yaml](../openapi.yaml)

### 테스트 추가 기준

- Agent 등록부터 관리자 supportDecision까지 최소 E2E를 검증한다.
- 웹 JWT와 Agent token 분리 테스트를 유지한다.
- Idempotency replay/conflict 테스트를 유지한다.
- 기존 웹 API smoke가 깨지지 않는지 확인한다.

### 완료 보고 형식

```text
## Goal 8 결과

* 목표:
* 완료 여부:
* 검증한 시나리오:
* 실행한 명령:
* 성공 결과:
* 실패/불안정 지점:
* 데모 전 확인 필요:
* 제안 커밋 메시지:
```

### 데모 전 체크리스트

- 브랜치가 `integration/agent-as-e2e` 또는 그 위 Goal 8 검증 브랜치인지 확인한다.
- `git status --short`가 clean인지 확인한다.
- `python tools/validate_openapi.py`가 통과하는지 확인한다.
- `apps/api`에서 `./gradlew.bat test --no-daemon`과 `./gradlew.bat bootJar --no-daemon`이 통과하는지 확인한다.
- 가능하면 `docker compose config`, API/Web Docker image build, API runtime smoke(`/api/health`)까지 확인한다.
- Agent 등록은 `POST /api/agent/devices/register`에서 `Authorization` header 없이 `demo-agent-activation-token`으로 시작한다.
- Register 응답의 raw `agentToken`은 이후 Agent 요청에만 사용하고, 웹 JWT API에는 사용하지 않는다.
- Agent mutation 요청에는 `Idempotency-Key`를 반드시 넣는다.
- gzip upload는 `POST /api/agent/log-uploads`에 `file=@agent-log.jsonl.gz`, `rangeMinutes=30`으로 보낸다.
- 같은 upload `Idempotency-Key` 재시도 시 새 AS ticket이 생성되지 않는지 확인한다.
- 관리자 결정은 `PATCH /api/admin/as-tickets/{ticketId}`에서 ADMIN JWT로 수행한다.
- 최종 상태는 `GET /api/as-tickets/{ticketId}`에서 USER JWT로 확인한다.

## 확인 필요

- Goal 3 변경이 현재 브랜치에서 commit된 상태인지, 또는 working tree 변경으로 남아 있는지 작업 시작 전에 확인한다.
- 기존 계약 문서의 `/api/agent/sessions`와 `/api/agent-logs/upload`는 PC Agent 전용 정책과 충돌할 수 있다. 각 담당자는 자기 Goal 착수 전에 path와 auth 정책을 확인해야 한다.
- 현재 integration 데모는 PC Agent 직접 API로 `/api/agent/devices/register`, `/api/agent/consents`, `/api/agent/heartbeat`, `/api/agent/log-uploads`를 사용한다. 기존 `API_CONTRACT.md`와 `openapi.yaml`의 웹 JWT 기반 `/api/agent-logs/upload`, `/api/as-tickets` 계약과 완전히 같지 않으므로 최종 계약 정리가 필요하다.
