# PC Agent AS 최종 구현 계약서

기준일: 2026-07-03

이 문서는 PC Agent AS 파트의 최종 시나리오 계약서다. 지원할 AS 종류, 수집 데이터, `IncidentWindow`, `LogSummary`, AI 전달 계약, 원격/방문지원 상태, 관리자 승인 기준은 오늘 최신화한 기획 내용을 기준으로 맞춘다.

기준 관계는 다음과 같이 고정한다.

- 기획 내용과 AS 시나리오는 오늘 회의에서 최신화한 최종 결정을 우선한다.
- `C:\Users\whqja\Downloads\경량_Agent_AI_AS_진단_서비스_구현_기획서_최종.md`는 초기 prototype 방향과 서비스 원칙의 출발점이다.
- API 경로, DB 테이블, 컬럼, enum, DTO 이름은 현재 prototype 기존 작업인 `docs/API_CONTRACT.md`, `docs/DB_SCHEMA.md`, `docs/openapi.yaml`, Flyway migration, Java 코드/테스트의 계약명을 따른다.
- 오늘 확정 시나리오에는 필요하지만 기존 작업에 아직 없는 API/DB는 이 문서에서 "신규 구현 대상" 또는 "운영 고도화 후보"로 표시하고, 기존 명세인 것처럼 쓰지 않는다.

핵심 결정은 피드백 기준을 따른다. `supportDecision`은 확장 enum으로 키우지 않고 coarse routing 4개만 유지한다. 교체 가능성, 미지원 사유, 방문 상세 사유, 원격 조치 권고는 `reasonCodes`, `remoteActions`, `visitReasons`, `blockingFactors`, `nextActions`로 분리한다.

## 0. 추가 확정 결정

이 회의에서 한 번 더 검토해 확정한 결정은 다음과 같다.

| 항목 | 최종 결정 |
|---|---|
| 사용자 원격지원 요청 | 최종 시나리오에는 필요하다. 단, 현재 prototype 기존 계약에는 별도 사용자 원격지원 요청 API가 없으므로 API 경로는 신규 구현 대상이다. 기존 작업 정합화에서는 `PATCH /api/admin/as-tickets/{id}`, `remoteSupportLink`, `remote_support_sessions` 흐름을 기준으로 한다. |
| 방문지원 처리 | 사용자 직접 방문신청 API를 만들지 않는다. 기존 작업 기준으로는 관리자가 `PATCH /api/admin/as-tickets/{id}`에서 `visitSupportRequired`, `visitPreferredDate`, `visitTimeSlot`을 저장하고 `visit_support_reservations`를 생성한다. |
| 미지원 기본 처리 | 미지원 항목의 기본 `supportDecision`은 항상 `NEEDS_MORE_INFO`로 저장한다. `UNSUPPORTED` enum은 만들지 않는다. |
| 위험 신호 안내 | SMART critical, thermal shutdown, 전원 꺼짐 반복 같은 위험 신호는 사용자에게 자동 사용 중지 또는 사용 자제 안내를 표시한다. 최종 지원 결정은 관리자 승인으로 확정한다. |
| 최종 처리 결과 | 현재 prototype은 ticket 상태와 관리자 메모/audit 중심으로 처리한다. 별도 `support_outcomes`는 운영 고도화 후보로 둔다. |
| 사용자 동의 | 로그 업로드, 원격 연결, 전체 제어, 고위험 원격 조치를 단계별 `consentType`으로 분리한다. |
| 계약명 정합화 | 시나리오 문장은 새로 확정하되, API/DB 이름은 기존 작업명을 따른다. `support_service_requests`, `support_outcomes`는 현재 계약명이 아니므로 즉시 구현 대상으로 쓰지 않는다. |

### 0.1 초기 기획명세서와 오늘 최종 시나리오의 관계

| 항목 | 초기 기획명세서 기준 | 오늘 최종 시나리오 적용 방식 |
|---|---|---|
| 서비스 형태 | 경량 Agent 트리거 기반 웹 AS 진단 서비스 | Agent가 직접 진단 답변을 만들거나 문제를 수정하지 않는다. |
| Agent 역할 | 로컬 수집, 이상 감지, 사용자 동의, 서버 전송, 웹 AS 페이지 오픈 | Agent 내부 AI 답변, 자체 원격제어, 직접 수정 기능은 구현하지 않는다. |
| 기본 업로드 범위 | 문제 신고 시 최근 30분 로그 gzip 업로드 | 오늘 최종 시나리오는 `IncidentWindow` 중심이다. PC Agent 직접 업로드는 `symptomType`, `detectedAt`, `incidentStartedAt`, `incidentEndedAt`, `lastNormalBootAt` 기준으로 계산하고, 특정 `rangeMinutes` 고정값으로 단정하지 않는다. |
| 1차 AS 유형 | `SLOW_PERFORMANCE`, `GAME_CRASH`, `TEMPERATURE_HIGH`, `UNKNOWN` | 이 4개는 초기 접수/호환용 symptom type으로 유지한다. 최종 지원 카탈로그는 원격 6종, 방문 5종, 미지원 항목까지 세분화한다. |
| 후순위 감지 | `BLUE_SCREEN`, `DRIVER_ERROR`, `BOOT_FAILURE` | 자동 감지 대상으로 단정하지 않고, 재부팅 후 기록/관리자 판단/방문 시나리오로만 다룬다. |
| 원격지원 | 직접 원격제어 구현 없음, 외부 링크 제공 | Quick Assist/외부 원격툴은 링크/코드 전달과 감사 기록까지만 다룬다. 자체 원격제어 엔진은 만들지 않는다. |
| 방문지원 | 기사 매칭이 아니라 상태값과 관리자 메모 수준에서 시작 | 기존 작업의 `visit_support_reservations`까지 사용한다. 실시간 기사 매칭, 비용 결제, 부품 현장 배정은 운영 고도화다. |
| AI 역할 | Rule 기반 1차 분류 후 LLM 설명 보강 | LLM은 원본 로그 전체를 받지 않고 요약/근거를 받는다. 최종 원격/방문/교체 판단은 관리자 승인이다. |

## 1. 최종 원칙

| 항목 | 최종 계약 |
|---|---|
| 지원 범위 | 오늘 확정한 원격 6종, 방문 5종, 기본 미지원 항목을 최종 시나리오로 둔다. 초기 4개 유형(`SLOW_PERFORMANCE`, `GAME_CRASH`, `TEMPERATURE_HIGH`, `UNKNOWN`)은 접수/호환용 상위 symptom type이다. |
| 지원 대상 | activation token으로 등록된 PC Agent 기기만 기본 지원 대상이다. |
| Agent API | 설치형 PC Agent는 `/api/agent/**`를 사용하고 Agent token 인증을 받는다. |
| 웹 fallback API | 브라우저 수동 업로드는 `/api/agent-logs/**`를 사용하고 USER JWT 인증을 받는다. |
| 업로드 원칙 | 서버 상시 전송 금지. 사용자 동의 후 선택 구간 또는 Agent 감지 시점 주변 로그를 gzip 업로드한다. PC Agent 직접 업로드는 특정 `rangeMinutes` 고정값으로 단정하지 않는다. |
| 분석 window | 서버는 `symptomType`, `detectedAt`, `incidentStartedAt`, `incidentEndedAt`, `lastNormalBootAt` 기준으로 문제 발생 시점 주변 `IncidentWindow`를 산정해 요약/분석한다. |
| 로그 처리 | raw log 전체를 LLM에 전달하지 않는다. 서버가 마스킹/요약 후 구조화된 요청만 전달한다. |
| 지원 결정 | 시스템은 추천과 근거를 만들고, 최종 `supportDecision`은 관리자 승인으로 확정한다. |
| 원격지원 | Quick Assist 우선. 자체 원격제어 엔진은 구현 범위 밖이다. |
| 사용자 신청 | 최종 시나리오에는 사용자 원격지원 요청이 필요하다. 방문지원은 사용자 직접 신청이 아니라 관리자 `VISIT_REQUIRED` 판단과 예약 생성으로 처리한다. |
| 위험 안내 | 위험 신호가 감지되면 사용자에게 자동 안전 안내를 표시한다. |
| 고급 조치 | 승인된 playbook, 사용자 위험 재확인, 복원/백업 확인, audit log가 있을 때만 허용한다. |
| 비용/보증 | 이 문서는 기술 판정까지만 다룬다. 무상/유상/출장비/보증 여부는 별도 운영 정책이다. |
| UI 언어 | DB/API enum은 영어, 사용자 UI 라벨은 한국어로 표시한다. |

## 2. 역할 분리

### PC Agent/AS 파트

- Agent 설치/등록
- 5초 단위 로컬 JSONL 수집
- 로컬 이상 감지
- 사용자 AS 접수와 동의 기록
- 증상별 `IncidentWindow` 산정
- gzip 업로드
- 서버 raw log 검증과 PII 마스킹
- `LogSummary` 생성
- `supportRouting` 추천 생성
- AS ticket 생성/조회
- 원격지원 링크/상태 저장과 `remote_support_sessions` 기록
- 방문 필요 판단, `visit_support_reservations` 예약 생성, 관리자 메모 기록
- Quick Assist 또는 외부 원격툴 연결 정보와 감사 metadata 기록
- 사용자 피드백과 관리자 처리 결과 기록

### LLM/AS Chat 파트

- `AiDiagnosisRequest`를 입력으로 받는다.
- Structured Outputs 기반 `AiDiagnosisResult`를 반환한다.
- 사용자 1차 상담 문구, 원인 후보, 안전한 자가 조치, 추가 질문, 관리자 메모 초안을 생성한다.
- 원본 gzip, 전체 JSONL, 전체 프로세스 목록, 전체 파일 경로는 입력으로 받지 않는다.

## 3. 전체 흐름

```text
Agent 설치/등록
→ 로컬 5초 JSONL 수집
→ 사용자 AS 요청 또는 Agent 이상 감지
→ 사용자 선택 구간 또는 Agent 감지 시점 주변 IncidentWindow 산정
→ `SERVER_UPLOAD` 동의 및 필요 단계별 재확인
→ Agent token 기반 /api/agent/log-uploads gzip 업로드
→ 서버 raw log 검증/PII 마스킹
→ LogSummary 생성
→ supportRouting 생성
→ AiDiagnosisRequest 생성
→ LLM 팀이 AiDiagnosisResult 반환
→ 사용자 1차 안내 표시
→ 위험 신호가 있으면 사용자에게 자동 사용 중지/사용 자제 안내 표시
→ 사용자가 원격지원을 요청하거나 관리자가 원격/방문 필요 여부를 검토
→ 관리자가 진단 결과와 근거 확인
→ 관리자가 supportDecision 최종 승인
→ `PATCH /api/admin/as-tickets/{id}`로 원격 링크 또는 방문 예약 정보 저장
→ Quick Assist 연결 또는 방문 진행
→ ticket 상태, remote/visit 상태, 관리자 audit 기록
→ 사용자 피드백과 관리자 처리 결과 저장
```

## 4. 지원 카탈로그

지원 카탈로그는 초기 접수/호환용 상위 유형과 오늘 확정한 완성형 세부 유형으로 나눈다.

### 4.0 초기 접수/호환용 상위 유형

| 코드 | 기준 | 기본 `supportDecision` | 처리 |
|---|---|---|---|
| `SLOW_PERFORMANCE` | CPU/RAM/DISK 사용률 과다, 사용자가 느림을 신고 | `SELF_SOLVABLE` 또는 `REMOTE_POSSIBLE` | 로그 요약 후 백그라운드 앱, 메모리, 디스크 사용률 중심으로 자가 조치/원격 검토 |
| `GAME_CRASH` | 데모 이벤트 또는 프로세스 비정상 종료 시뮬레이션 | `REMOTE_POSSIBLE` | 드라이버/런처/런타임 충돌 가능성으로 원격 검토 |
| `TEMPERATURE_HIGH` | 온도 수집 가능 환경의 과열 또는 데모 이벤트 | `REMOTE_POSSIBLE` 또는 위험 신호 시 `VISIT_REQUIRED` | 쿨링/부하/팬 상태 안내, 위험 시 관리자 검토 |
| `UNKNOWN` | 분류 불가 | `NEEDS_MORE_INFO` | 추가 증상 설명 또는 추가 로그 요청 |

아래 원격 6종과 방문 5종은 오늘 확정한 완성형 세부 분류다. 상위 유형으로 접수된 ticket도 서버 요약/라우팅 과정에서 가능한 한 세부 분류로 매핑한다. API/DB 이름은 현재 prototype 기존 작업명을 따르고, 새 리소스가 필요한 부분은 별도 신규 구현 대상으로 분리한다.

### 4.1 원격지원 우선 6종

| 코드 | 지원 항목 | `supportDecision` | 세부 사유/조치 |
|---|---|---|---|
| `REMOTE_AGENT` | Agent 설치/등록/업로드/권한 오류 | `REMOTE_POSSIBLE` | `AGENT_INSTALL_OR_UPLOAD_FAILURE`, `CHECK_AGENT_CONFIG` |
| `REMOTE_DRIVER_OS` | 드라이버/OS 업데이트/롤백/장치 관리자 오류 | `REMOTE_POSSIBLE` | `DRIVER_CRASH_LOG`, `REINSTALL_GRAPHICS_DRIVER` |
| `REMOTE_APP_LAUNCHER` | 앱/런처 실행 오류 | `REMOTE_POSSIBLE` | `APP_SPECIFIC_FAILURE`, `CHECK_RUNTIME_OR_PERMISSION` |
| `REMOTE_STORAGE_MEMORY` | 저장공간 부족/메모리 압박 | `REMOTE_POSSIBLE` | `MEMORY_PRESSURE`, `CHECK_STORAGE_HEALTH` |
| `REMOTE_STARTUP_SERVICE` | 시작프로그램/백그라운드 서비스 부하 | `REMOTE_POSSIBLE` | `BACKGROUND_SERVICE_PRESSURE`, `CHECK_STARTUP_APPS` |
| `REMOTE_LOCAL_NETWORK` | 로컬 네트워크 설정/DNS/어댑터 문제 | `REMOTE_POSSIBLE` | `LOCAL_NETWORK_CONFIG`, `CHECK_ADAPTER_DRIVER` |

### 4.2 방문 판정 5종

| 코드 | 지원 항목 | `supportDecision` | 세부 방문 사유 |
|---|---|---|---|
| `VISIT_BOOT_REMOTE_BLOCKED` | 부팅 불가 또는 원격 연결 불가 | `VISIT_REQUIRED` | `DEVICE_OFFLINE`, `REMOTE_HELP_NOT_AVAILABLE` |
| `VISIT_DISK_FAILURE` | SMART critical, 디스크 I/O 오류 반복 | `VISIT_REQUIRED` | `STORAGE_REPLACEMENT_SUSPECTED` |
| `VISIT_WHEA_BSOD` | WHEA/BSOD 반복 | `VISIT_REQUIRED` | `SUSPECTED_HARDWARE_FAILURE`, `BSOD_SIGNATURE` |
| `VISIT_POWER_SHUTDOWN` | 부하 시 전원 꺼짐, Kernel-Power 반복 | `VISIT_REQUIRED` | `PSU_OR_POWER_PATH_RISK` |
| `VISIT_FAN_THERMAL` | 팬 미동작, thermal shutdown | `VISIT_REQUIRED` | `THERMAL_SERVICE_REQUIRED` |

방문/교체 의심은 `REPAIR_OR_REPLACE` 같은 별도 `supportDecision`으로 만들지 않는다. 공식 결정값은 `VISIT_REQUIRED`로 유지하고, 교체 가능성은 `visitReasons`, `reasonCodes`, `nextActions`, 관리자 메모로 표현한다.

### 4.3 기본 미지원 항목

| 항목 | 처리 |
|---|---|
| 게임별 FPS 튜닝 | `blockingFactors=["UNSUPPORTED_SCOPE"]` |
| 오버클럭 안정화 | `reasonCodes=["UNSUPPORTED_CATEGORY"]` |
| ISP/공유기 장애 | `blockingFactors=["OUT_OF_PC_SCOPE"]` |
| 주변기기/프린터 | `blockingFactors=["UNSUPPORTED_SCOPE"]` |
| 데이터 복구 | `blockingFactors=["DATA_RECOVERY_REQUIRED"]` |
| 불법 소프트웨어 | `blockingFactors=["UNSUPPORTED_SOFTWARE"]` |
| 물리 파손 | `blockingFactors=["PHYSICAL_DAMAGE_POLICY_REQUIRED"]` |

미지원 여부는 `supportDecision` enum으로 표현하지 않는다. 미지원 항목의 기본 `supportDecision`은 항상 `NEEDS_MORE_INFO`로 저장하고, 세부 미지원 사유는 `blockingFactors`, `reasonCodes`, 관리자 메모, 예외 승인 metadata로 표현한다.

관리자가 예외 승인하면 `REMOTE_POSSIBLE` 또는 `VISIT_REQUIRED`로 최종 결정할 수 있다. 예외 승인에는 사유, 책임 범위, 사용자 안내 문구, audit log가 필요하다.

## 5. 공식 supportDecision 계약

`supportDecision`은 coarse routing만 담당한다.

| enum | UI 라벨 | 의미 |
|---|---|---|
| `SELF_SOLVABLE` | 자가 조치 가능 | 원격/방문 없이 사용자 안내로 처리 가능 |
| `REMOTE_POSSIBLE` | 원격지원 가능 | 원격 세션으로 해결 가능성이 높음 |
| `VISIT_REQUIRED` | 방문지원 필요 | 물리 점검 또는 현장 확인 필요 |
| `NEEDS_MORE_INFO` | 추가 정보 필요 | 증거 부족, 미지원 가능성, 사용자 추가 입력 필요 |

`supportDecision`에 넣지 않는 값:

- `REPAIR_OR_REPLACE`
- `MONITOR_ONLY`
- `UNSUPPORTED`

이 값들은 각각 다음 필드로 표현한다.

| 의미 | 표현 위치 |
|---|---|
| 수리/교체 가능성 | `visitReasons`, `reasonCodes`, `nextActions`, 관리자 메모 |
| 관찰 필요 | `nextActions`, `riskLevel=LOW`, follow-up schedule |
| 지원 범위 밖 | `blockingFactors`, `reasonCodes`, 예외 승인 metadata |

## 6. 세부 사유 enum 계약

### reasonCodes

- `GPU_THERMAL_THROTTLE`
- `DRIVER_CRASH_LOG`
- `MEMORY_PRESSURE`
- `STORAGE_IO_BOTTLENECK`
- `PSU_POWER_EVENT`
- `UNEXPECTED_REBOOT_PATTERN`
- `DEVICE_NOT_DETECTED`
- `BSOD_SIGNATURE`
- `APP_SPECIFIC_FAILURE`
- `INSUFFICIENT_EVIDENCE`
- `UNSUPPORTED_CATEGORY`
- `AGENT_INSTALL_OR_UPLOAD_FAILURE`
- `BACKGROUND_SERVICE_PRESSURE`
- `LOCAL_NETWORK_CONFIG`

### remoteActions

- `CHECK_EVENT_VIEWER`
- `REINSTALL_GRAPHICS_DRIVER`
- `DISABLE_OVERCLOCK`
- `RUN_MEMORY_DIAGNOSTIC`
- `CHECK_STORAGE_HEALTH`
- `MONITOR_TEMPERATURE`
- `CHECK_AGENT_CONFIG`
- `CHECK_ADAPTER_DRIVER`
- `CHECK_RUNTIME_OR_PERMISSION`
- `CHECK_STARTUP_APPS`

### visitReasons

- `SUSPECTED_HARDWARE_FAILURE`
- `PSU_OR_POWER_PATH_RISK`
- `THERMAL_SERVICE_REQUIRED`
- `STORAGE_REPLACEMENT_SUSPECTED`
- `NEEDS_BENCH_REPRODUCTION`
- `DEVICE_OFFLINE`
- `REMOTE_HELP_NOT_AVAILABLE`

### blockingFactors

- `NO_UPLOAD_CONSENT`
- `INSUFFICIENT_LOG_RANGE`
- `DEVICE_OFFLINE`
- `REMOTE_HELP_NOT_AVAILABLE`
- `ADMIN_APPROVAL_PENDING`
- `HIGH_RISK_HARDWARE_DAMAGE`
- `CUSTOMER_UNAVAILABLE`
- `UNSUPPORTED_SCOPE`
- `OUT_OF_PC_SCOPE`
- `DATA_RECOVERY_REQUIRED`
- `UNSUPPORTED_SOFTWARE`
- `PHYSICAL_DAMAGE_POLICY_REQUIRED`

## 7. API 경로와 인증 계약

이 섹션은 오늘 시나리오를 API/DB 이름으로 표현할 때 현재 prototype 기존 작업명을 기준으로 쓴다. 아래 표에 없는 경로는 최종 시나리오에 필요하더라도 아직 기존 계약명이 아니므로 별도 신규 구현 대상으로 다룬다.

| 경로 | 인증 | 목적 |
|---|---|---|
| `POST /api/agent/devices/register` | pre-auth activation token | Agent 최초 등록 |
| `POST /api/agent/consents` | Agent token + `Idempotency-Key` | Agent 동의 저장 |
| `POST /api/agent/heartbeat` | Agent token + `Idempotency-Key` | Agent 생존/상태 보고 |
| `POST /api/agent/log-uploads` | Agent token + `Idempotency-Key` | 설치형 PC Agent 직접 gzip 업로드 |
| `POST /api/agent-logs/upload` | USER JWT | 브라우저 수동 업로드/fallback |
| `GET /api/agent-logs/{id}` | USER JWT | 브라우저 수동 업로드 상세 조회 |
| `POST /api/as-tickets` | USER JWT | 브라우저 fallback AS ticket 생성 |
| `GET /api/as-tickets/{id}` | USER JWT | 사용자 ticket 상세/진단/지원 상태 조회 |
| `GET /api/admin/as-tickets` | ADMIN JWT | 관리자 ticket 목록 조회 |
| `GET /api/admin/as-tickets/{id}` | ADMIN JWT | 관리자 ticket 상세 조회 |
| `PATCH /api/admin/as-tickets/{id}` | ADMIN JWT | ticket 상태, `reviewStatus`, `supportDecision`, `riskLevel`, `adminNote`, 원격 링크, 방문 예약 필드 저장 |

기존 작업 기준 관련 DB 테이블은 `agent_devices`, `agent_consents`, `agent_heartbeats`, `agent_upload_jobs`, `agent_log_uploads`, `agent_idempotency_records`, `as_tickets`, `remote_support_sessions`, `visit_support_reservations`, `admin_audit_logs`다.

현재 기존 작업에 없는 경로/리소스:

- 사용자 원격지원 요청 전용 endpoint. 경로는 아직 미확정이다.
- 관리자 `supportDecision` 승인/응답/원격지원/방문지원 분리 endpoint. 경로는 아직 미확정이다.
- `support_service_requests`
- `support_outcomes`

위 항목은 오늘 최종 시나리오상 필요하거나 운영 고도화에서 유용할 수 있지만, 현재 API/DB 명세서에 맞춘 계약명으로 확정된 것은 아니다. 신규 구현 시에는 기존 `PATCH /api/admin/as-tickets/{id}`와 `remote_support_sessions`, `visit_support_reservations`를 유지할지, 별도 endpoint/resource로 분리할지 먼저 결정해야 한다.

### 7.1 사용자 원격지원 요청 시나리오

최종 시나리오에는 사용자가 ticket 상세 화면에서 원격지원을 요청하는 흐름이 필요하다. 다만 현재 prototype 기존 작업에는 사용자 원격지원 요청 전용 endpoint가 없다.

| 항목 | 계약 |
|---|---|
| 신청 주체 | ticket 소유 사용자 |
| 현재 기존 작업 | 별도 사용자 요청 API 없음. 관리자가 `PATCH /api/admin/as-tickets/{id}`로 `remoteSupportLink`를 저장하면 `remote_support_sessions`가 생성된다. |
| 신규 구현 필요 | 사용자 원격지원 요청을 받을 API와 중복 요청 409 정책 |
| 효과 | 티켓을 원격지원 요청 상태로 표시하고 관리자 화면에 노출해야 한다. |
| 중복 요청 | 같은 ticket에 진행 중인 원격 요청이 있으면 새 요청은 `409 CONFLICT_STATE`로 거절한다. |
| 방문지원 | 사용자 직접 방문 신청 API는 만들지 않는다. 관리자가 `VISIT_REQUIRED` 판단 후 `visitSupportRequired`, `visitPreferredDate`, `visitTimeSlot`을 저장한다. |

사용자 원격지원 요청 최소 필드:

```json
{
  "reason": "드라이버 오류로 원격지원을 받고 싶습니다.",
  "contactPhone": "010-****-1234"
}
```

`support_service_requests`처럼 신청을 별도 업무 리소스로 분리하는 구조는 운영 고도화 후보이며, 현재 prototype 정합화의 선구현 대상이 아니다.

### 7.2 단계별 동의 계약

통합 동의 1개로 모든 원격 AS를 처리하지 않는다. 각 단계는 별도 `consentType`과 `policyVersion`을 남긴다.

| consentType | 필요한 시점 | 필수 연결 정보 |
|---|---|---|
| `SERVER_UPLOAD` | gzip 로그 업로드 전 | `asTicketId`, `logUploadId` 또는 upload job id |
| `REMOTE_CONNECTION` | Quick Assist 코드 입력/원격 연결 전 | `asTicketId`, `remoteSessionId` |
| `REMOTE_FULL_CONTROL` | 전체 제어 허용 전 | `asTicketId`, `remoteSessionId` |
| `HIGH_RISK_REMOTE_ACTION` | registry 수정, driver rollback, firmware update, BIOS/UEFI 변경, 강제 재부팅 전 | `asTicketId`, `remoteSessionId`, `actionCode`, `playbookId`, `riskNoticeVersion` |

`accepted=false` 또는 동의 철회 상태에서는 해당 단계로 진행하지 않는다. 고위험 조치 동의는 조치 단위로 새로 받아야 하며 이전 동의 재사용을 금지한다.

### 7.3 위험 신호 자동 안내 계약

위험 신호 자동 안내는 사용자 보호용 안내이며, 최종 AS 판정을 자동 승인하지 않는다.

| 조건 | 자동 안내 | 기본 라우팅 |
|---|---|---|
| SMART critical, bad block 반복, filesystem write failure 반복 | 데이터 손실 위험이 있으므로 사용을 중지하고 추가 쓰기 작업을 피하라고 안내 | `VISIT_REQUIRED` 추천 |
| thermal shutdown, fan rpm 0, vendor critical thermal event | 과열 위험이 있으므로 고부하 작업을 중지하고 전원을 끄거나 사용을 자제하라고 안내 | `VISIT_REQUIRED` 추천 |
| Kernel-Power 반복, 부하 직전 unexpected shutdown 반복 | 전원 경로 위험이 있으므로 반복 부하 테스트를 중지하라고 안내 | `VISIT_REQUIRED` 추천 |
| 사용자 증상에 탄 냄새, 연기, 파손, 누수, 스파크가 포함됨 | 즉시 전원을 끄고 원격 조치를 시도하지 말라고 안내 | `VISIT_REQUIRED` 추천 |
| WHEA/BSOD 반복과 hardware error signature 동시 발생 | 작업 중 데이터 손실 가능성을 안내하고 추가 로그/방문 검토로 유도 | `VISIT_REQUIRED` 또는 `NEEDS_MORE_INFO` |

자동 안내 결과는 `supportRouting.safetyAdviceLevel`과 `safetyNotices[]`에 저장한다. 가능한 값은 `NONE`, `CAUTION`, `STOP_USE_UNTIL_REVIEW`다.

## 8. IncidentWindow 계약

| 증상 그룹 | 기본 window | 저장 기준 |
|---|---|---|
| 원격 6종/일반 장애 | `detectedAt` 또는 사용자 선택 시점 기준 전 15분, 후 5분 | `agent_upload_jobs` + `agent_log_uploads.incident_window`, `range_started_at`, `range_ended_at` |
| BSOD, 디스크, 전원, thermal shutdown, 방문 5종 | `detectedAt` 기준 전 30분, 후 10분 | `agent_upload_jobs` + `agent_log_uploads.incident_window`, `range_started_at`, `range_ended_at` |
| 부팅 불가/원격 연결 불가 | Agent 자동 감지 대상으로 단정하지 않는다. 마지막 정상 heartbeat/boot event 또는 `lastNormalBootAt`이 있을 때만 참고 요약한다. | 마지막 heartbeat/boot event 요약. 기존 DB_SCHEMA fallback은 서버 감지 시각 기준 최근 24시간이다. |

`IncidentWindow` 최소 필드:

```json
{
  "rangeStartedAt": "2026-07-03T09:45:00+09:00",
  "rangeEndedAt": "2026-07-03T10:05:00+09:00",
  "timezone": "Asia/Seoul",
  "triggerType": "USER_REQUEST",
  "eventCount": 12,
  "primarySignal": "DRIVER_CRASH_LOG"
}
```

## 9. 데이터 수집 원칙

| 원칙 | 계약 |
|---|---|
| 로컬 수집 | Agent는 기본 5초 단위로 로컬 JSONL 로그를 저장한다. |
| 서버 전송 | 서버로 상시 전송하지 않는다. |
| 업로드 트리거 | 사용자 AS 요청 또는 Agent 이상 감지 후 사용자 동의가 있을 때만 전송한다. |
| 업로드 형식 | gzip 형식이다. |
| 업로드 범위 | PC Agent 직접 업로드는 `symptomType`, `detectedAt`, `incidentStartedAt`, `incidentEndedAt`, `lastNormalBootAt` 기준의 `IncidentWindow`를 사용한다. 웹 fallback/manual 업로드의 `rangeMinutes`는 legacy 입력으로 유지한다. |
| AI 입력 | 원본 전체 로그를 AI에 넘기지 않는다. |
| raw sample | evidenceRefs에 연결된 JSONL 일부만 최대 20개까지 제공한다. |

## 10. 개인정보와 민감정보 제한

수집 금지 또는 기본 제외 항목:

- 키 입력
- 스크린샷
- 클립보드
- 문서 내용
- 브라우저 방문 기록
- 전체 파일 경로
- 창 제목
- 전체 프로세스 목록
- 사용자가 입력한 비밀번호/토큰/개인 메시지

프로세스 정보는 allowlist 이름, category, hash 중심으로 제한한다. Windows Event Log message에 사용자명, 경로, 계정 정보가 있으면 마스킹한다.

## 11. 보관 정책

| 데이터 | 보관 정책 |
|---|---|
| 로컬 raw JSONL | 7일 |
| 로컬 incident metadata | 30일 |
| 서버 업로드 raw gzip | 30일 |
| `LogSummary` | AS 정책 기간 보관 |
| ticket, `supportDecision`, 예약/승인 기록 | AS 정책 기간 보관 |
| audit log | 운영/보안 정책 기간 보관 |

보관 만료 후 raw log 삭제는 `LogSummary`, ticket, audit 이력 삭제와 별개로 처리한다.

## 12. 데이터 계약

### RawLog

```json
{
  "schemaVersion": "1",
  "collectedAt": "2026-07-03T10:00:00+09:00",
  "agentId": "agent-public-id",
  "deviceIdHash": "hash",
  "sequence": 12345,
  "kind": "SYSTEM_METRIC",
  "payload": {},
  "privacyFlags": {
    "masked": true,
    "containsRawPath": false
  }
}
```

### LogSummary

```json
{
  "contractVersion": "1",
  "systemInfo": {},
  "keySymptoms": [],
  "redFlags": [],
  "extractedEvents": [],
  "timeline": [],
  "anomalies": [],
  "correlations": [],
  "safetyNotices": [],
  "dataQuality": {
    "level": "ENOUGH",
    "missingSignals": []
  },
  "piiMaskingApplied": true,
  "evidenceRefs": [],
  "rawSamples": []
}
```

### supportRouting

```json
{
  "supportDecision": "REMOTE_POSSIBLE",
  "riskLevel": "MEDIUM",
  "confidence": "HIGH",
  "reasonCodes": ["DRIVER_CRASH_LOG"],
  "remoteActions": ["REINSTALL_GRAPHICS_DRIVER"],
  "visitReasons": [],
  "blockingFactors": [],
  "safetyAdviceLevel": "NONE",
  "safetyNotices": [],
  "adminApprovalRequired": true,
  "allowAutoResponse": false
}
```

### AiDiagnosisRequest

```json
{
  "contractVersion": "1",
  "asTicketId": "ticket-public-id",
  "logUploadId": "log-upload-public-id",
  "agentDeviceId": "device-public-id",
  "incidentWindow": {},
  "logSummary": {},
  "userReportedSymptom": {},
  "supportRouting": {},
  "policy": {
    "includeRawLog": false,
    "maxEvidenceCount": 20,
    "safetyMode": "STRICT"
  },
  "traceRefs": {
    "agentSessionId": "agent-session-public-id",
    "llmGenerationId": null
  }
}
```

### AiDiagnosisResult

```json
{
  "contractVersion": "1",
  "supportDecision": "REMOTE_POSSIBLE",
  "riskLevel": "MEDIUM",
  "confidence": "MEDIUM",
  "reasonCodes": [],
  "causeCandidates": [],
  "nextActions": [],
  "remoteActions": [],
  "visitReasons": [],
  "blockingFactors": [],
  "requiredAdditionalLogs": [],
  "evidenceRefs": [],
  "toolRefs": [],
  "unsafeActionsExcluded": [],
  "adminReviewRequired": true,
  "proposedTicketPatch": {}
}
```

`proposedTicketPatch`는 자동 DB 반영용이 아니다. 관리자 승인 화면에서 “이 판단을 반영하면 어떤 필드가 바뀌는가”를 보여주는 제안 객체다.

### RawLog kind별 payload 최소 계약

`payload`는 `kind`별로 다음 최소 필드를 갖는다. 수집 실패 값은 `null`과 `unavailableReason`으로 표현하고, 임의 문자열 원문을 그대로 넣지 않는다.

| kind | payload 최소 필드 | 금지/주의 |
|---|---|---|
| `SYSTEM_METRIC` | `cpuUsagePercent`, `memoryUsedPercent`, `diskActivePercent`, `gpuUsagePercent`, `sampleIntervalSeconds` | 프로세스 전체 목록 금지 |
| `EVENT_LOG` | `source`, `eventId`, `level`, `messageMasked`, `eventTime` | 원본 경로, 사용자명, token/password 문자열 금지 |
| `STORAGE_HEALTH` | `diskIdHash`, `smartStatus`, `criticalWarnings`, `ioLatencyMs`, `freeSpacePercent` | 디스크 시리얼 원문 금지 |
| `THERMAL_SENSOR` | `cpuTemperatureCelsius`, `gpuTemperatureCelsius`, `fanRpm`, `thermalThrottle`, `unavailableReason` | 센서 미지원 시 임의 추정값 금지 |
| `BOOT_SHUTDOWN` | `bootTime`, `shutdownTime`, `shutdownType`, `unexpected`, `kernelPowerDetected` | 사용자 파일/앱 이름 원문 금지 |
| `PROCESS_CATEGORY` | `category`, `cpuUsagePercent`, `memoryUsedMb`, `processNameHash`, `allowlistedName` | 전체 프로세스 목록, 창 제목 금지 |
| `NETWORK_DIAGNOSTIC` | `adapterStatus`, `gatewayReachable`, `dnsLookupOk`, `packetLossPercent`, `latencyMs` | 방문 기록, 접속 URL 전체 금지 |
| `AGENT_HEALTH` | `agentVersion`, `serviceStatus`, `trayStatus`, `lastUploadErrorCode`, `configSchemaVersion` | access token 원문 금지 |

## 13. DB 계약

### ai_diagnoses

진단 업무 결과는 장기적으로 ticket 본문이나 `llm_generations`에 직접 섞지 않고 별도 업무 결과 테이블로 저장하는 것이 좋다. 다만 현재 prototype 기존 작업에는 `ai_diagnoses` 테이블이 없다. 현재 계약명 기준으로는 `as_tickets.log_summary`, `as_tickets.support_routing`, `as_tickets.ai_diagnosis_request` JSONB가 우선이다.

신규 구현 시 후보 필드:

- `id`
- `public_id`
- `as_ticket_id`
- `agent_session_id`
- `llm_generation_id`
- `source_log_upload_id`
- `contract_version`
- `incident_window jsonb`
- `log_summary jsonb`
- `support_decision`
- `risk_level`
- `confidence`
- `reason_codes text[]`
- `remote_actions jsonb`
- `visit_reasons text[]`
- `blocking_factors text[]`
- `next_actions jsonb`
- `required_additional_logs jsonb`
- `admin_approval_required boolean`
- `approved_by_admin_id`
- `approved_at`
- `created_at`

`llm_generations`는 모델 실행 trace로 재사용하고, `use_case=PC_AGENT_DIAGNOSIS`를 추가한다.

### remote_support_sessions

현재 기존 DB 필드:

- `id`
- `public_id`
- `as_ticket_id`
- `device_id`
- `provider`
- `session_url`
- `status`
- `requested_by_admin_id`
- `started_at`
- `ended_at`
- `created_at`

현재 provider enum은 `EXTERNAL_LINK`, `ANYDESK`, `TEAMVIEWER`, `ZOOM`, `GOOGLE_MEET`이고, status enum은 `REQUESTED`, `LINK_SENT`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`다. Quick Assist 코드 만료, full control 허용/철회, 종료 사유 같은 세부 감사 필드는 오늘 시나리오상 필요하지만 현재 기존 DB에는 없으므로 신규 migration 대상이다.

### visit_support_reservations

현재 기존 DB 필드:

- `id`
- `public_id`
- `as_ticket_id`
- `user_id`
- `preferred_date`
- `time_slot`
- `status`
- `address_snapshot`
- `technician_note`
- `created_at`
- `updated_at`

현재 time slot enum은 `MORNING`, `AFTERNOON`, `EVENING`이고, status enum은 `REQUESTED`, `SCHEDULED`, `RESCHEDULE_REQUESTED`, `VISIT_IN_PROGRESS`, `COMPLETED`, `CANCELLED`다. 방문 사유 코드, 차단 요인, 승인자, 취소 사유, 연락처 snapshot은 운영 고도화 후보 필드다.

### support_service_requests

운영 고도화 후보 테이블이다. 오늘 최종 시나리오에는 사용자 원격지원 요청 흐름이 필요하지만, 현재 prototype 기존 계약명은 ticket, `remote_support_sessions`, `visit_support_reservations` 중심이다. 따라서 이 테이블은 prototype 정합화 작업에서 선구현하지 않는다.

후보 필드:

- `id`
- `public_id`
- `as_ticket_id`
- `user_id`
- `device_id`
- `request_type`
- `status`
- `reason`
- `preferred_time_slots jsonb`
- `contact_phone_snapshot`
- `requested_at`
- `cancelled_at`
- `reschedule_reason`
- `linked_remote_session_id`
- `linked_visit_reservation_id`
- `created_at`
- `updated_at`

### support_outcomes

운영 고도화 후보 테이블이다. 현재 prototype 정합화 기준은 ticket 상태, 관리자 메모, `admin_audit_logs`, 원격지원 세션 상태로 처리 결과를 추적하는 것이다. 별도 outcome 테이블은 운영 통계/AI 개선 단계에서 도입한다.

후보 필드:

- `id`
- `public_id`
- `as_ticket_id`
- `support_request_id`
- `remote_session_id`
- `visit_reservation_id`
- `outcome_type`
- `resolved`
- `resolution_summary`
- `actual_actions jsonb`
- `parts_replaced jsonb`
- `follow_up_required boolean`
- `follow_up_reason`
- `user_rating`
- `user_feedback`
- `admin_id`
- `created_at`

`outcome_type` 값:

- `SELF_RESOLVED`
- `REMOTE_RESOLVED`
- `REMOTE_TO_VISIT`
- `VISIT_RESOLVED`
- `UNSUPPORTED_CLOSED`
- `UNREPRODUCED`
- `USER_CANCELLED`
- `NEEDS_ADDITIONAL_LOGS`

## 14. 원격/방문 상태 계약

DB/API는 영문 enum, UI는 한국어 라벨을 사용한다. 상태 필드는 현재 prototype 기존 작업에 맞춰 리소스별로 나눈다.

| 리소스/필드 | 기존 enum | 역할 |
|---|---|---|
| `as_tickets.status` | `OPEN`, `ASSIGNED`, `IN_PROGRESS`, `RESOLVED`, `CLOSED`, `CANCELLED` | AS ticket 전체 생명주기 |
| `as_tickets.review_status` | `NOT_REQUIRED`, `REQUIRED`, `IN_REVIEW`, `APPROVED`, `REJECTED` | 관리자 검토 상태 |
| `as_tickets.support_decision` | `SELF_SOLVABLE`, `REMOTE_POSSIBLE`, `VISIT_REQUIRED`, `NEEDS_MORE_INFO` | 최종 라우팅 결정. 확장 enum은 쓰지 않는다. |
| `remote_support_sessions.status` | `REQUESTED`, `LINK_SENT`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED` | 외부 원격지원 링크/세션 상태 |
| `visit_support_reservations.status` | `REQUESTED`, `SCHEDULED`, `RESCHEDULE_REQUESTED`, `VISIT_IN_PROGRESS`, `COMPLETED`, `CANCELLED` | 방문 예약/진행 상태 |

### 14.1 원격/방문 상태 전이

ticket 자체의 상태 전이는 기존 `PATCH /api/admin/as-tickets/{id}` 계약을 따른다.

| 현재 상태 | 다음 상태 | 주체 | 허용 여부 | 조건 |
|---|---|---|---|---|
| `OPEN` | `ASSIGNED` | ADMIN | 허용 | 담당자 배정 |
| `OPEN`, `ASSIGNED` | `IN_PROGRESS` | ADMIN | 허용 | 처리 시작 |
| `ASSIGNED`, `IN_PROGRESS` | `ASSIGNED` | ADMIN | 허용 | 담당자 재배정 |
| `IN_PROGRESS` | `RESOLVED` | ADMIN | 허용 | 해결 시각 저장 |
| `RESOLVED` | `CLOSED` | ADMIN | 허용 | 종료 확정 |
| `OPEN`, `ASSIGNED`, `IN_PROGRESS` | `CANCELLED` | ADMIN | 허용 | 취소 사유 기록 |
| `CLOSED`, `CANCELLED` | any | any | 금지 | `409 CONFLICT_STATE` |

원격지원은 현재 별도 사용자 요청 API가 아니라 관리자 `PATCH /api/admin/as-tickets/{id}` 요청의 `remoteSupportLink` 입력으로 생성된다. 서버는 `remote_support_sessions`에 `provider=EXTERNAL_LINK`, `status=LINK_SENT`를 저장한다.

방문지원은 관리자 `PATCH /api/admin/as-tickets/{id}` 요청의 `visitSupportRequired=true`, `visitPreferredDate`, `visitTimeSlot` 입력으로 생성된다. 서버는 `visit_support_reservations`에 `status=REQUESTED`를 저장한다.

### 14.2 원격에서 방문으로 전환하는 기준

다음 조건 중 하나라도 있으면 원격지원 해결로 닫지 않고 관리자가 `supportDecision=VISIT_REQUIRED` 또는 방문 예약 필드를 저장한다. 현재 prototype 기준 처리 결과는 `as_tickets.admin_note`, `admin_audit_logs`, `remote_support_sessions`, `visit_support_reservations`에 남긴다. `support_outcomes`는 운영 고도화 후보이며 현 계약명으로 단정하지 않는다.

| 조건 | 전환 기준 |
|---|---|
| Agent offline 또는 heartbeat 장기 누락 | 원격 연결 자체가 불가능하므로 방문 검토 |
| 사용자가 원격 연결 또는 전체 제어를 거부 | 원격 조치 중단, 사용자 요청에 따라 방문 또는 종료 |
| Quick Assist 코드 만료/연결 실패 반복 | 2회 이상 실패 시 방문 또는 추가 정보 요청 |
| 위험 신호 `STOP_USE_UNTIL_REVIEW` | 원격 고위험 조치 금지, 방문 우선 |
| SMART critical, thermal shutdown, Kernel-Power 반복 | 물리 점검 가능성이 높으므로 방문 전환 |
| 원격 조치 후 재발 | 관리자 메모/audit에 재발 기록 후 방문 또는 추가 로그 요청 |

## 15. Quick Assist 흐름

1. 사용자가 AS 접수와 로그 업로드를 완료한다.
2. 시스템이 `REMOTE_POSSIBLE`을 추천하거나 사용자가 화면에서 원격지원 필요를 표시한다.
3. 현재 기존 계약에서는 사용자 원격지원 요청 전용 API가 없으므로, 관리자가 ticket 상세에서 원격지원 필요 여부를 확인한다.
4. 관리자가 `LogSummary`, `rawSamples`, `supportRouting`, `AiDiagnosisResult`를 확인한다.
5. 관리자가 `PATCH /api/admin/as-tickets/{id}`에 `reviewStatus=APPROVED`, `supportDecision=REMOTE_POSSIBLE`, `remoteSupportLink`를 저장한다.
6. 서버는 `remote_support_sessions`에 `provider=EXTERNAL_LINK`, `status=LINK_SENT`를 기록한다.
7. 사용자는 웹 또는 Agent UI에서 외부 원격지원 링크/코드를 확인한다.
8. 사용자가 Quick Assist 또는 허용된 외부 원격툴에 직접 코드를 입력한다.
9. `REMOTE_CONNECTION` 동의 후 연결한다.
10. 전체 제어가 필요하면 `REMOTE_FULL_CONTROL` 동의를 별도로 받는다.
11. 연결 허용, full control 허용, full control 철회, 종료 사유를 audit metadata로 남긴다.
12. 세션 종료 후 ticket 상태, 관리자 메모, `remote_support_sessions.status`, `admin_audit_logs`에 조치 결과와 다음 단계를 저장한다.

## 16. 고급 원격 조치 guardrail

허용 가능한 고급 조치:

- registry 수정
- BIOS/UEFI 기본값 복원 안내
- firmware update
- vendor 공식 cleanup tool 사용
- driver clean install
- 오버클럭 해제 또는 기본값 복원 안내

오버클럭 안정화 자체는 지원하지 않는다.

허용 조건:

- 승인된 playbook이 있어야 한다.
- 사용자가 `HIGH_RISK_REMOTE_ACTION` 동의로 위험을 재확인해야 한다.
- 복원 지점, 백업, rollback 가능성을 확인해야 한다.
- 관리자 audit log에 조치 전/후와 사유를 남겨야 한다.

### 16.1 피드백 루프 계약

피드백은 사용자 만족도만 저장하는 기능이 아니다. 실제 해결 여부와 진단 정확도를 후속 rule/AI 개선 데이터로 쓰기 위한 업무 결과다.

| 항목 | 계약 |
|---|---|
| 해결 여부 | 현재 기존 작업 기준으로는 `as_tickets.status`, `resolved_at`, `adminNote`, `admin_audit_logs`에 저장한다. |
| 실제 처리 결과 | 원격은 `remote_support_sessions.status`, 방문은 `visit_support_reservations.status`, ticket은 `adminNote`와 audit에 남긴다. |
| 진단 적중 여부 | 현재 기존 DB 필드가 없으므로 신규 구현 대상이다. 도입 시 raw log 전체가 아니라 `LogSummary`, `supportRouting`, `AiDiagnosisResult`와 연결한다. |
| 사용자 피드백 | 현재 기존 계약에 별도 표준 필드가 없으므로 신규 구현 대상이다. |
| 재발 여부 | 재발 시 기존 ticket에 관리자 메모/audit를 남기고, 필요하면 새 ticket과 연결하는 정책을 별도 구현한다. |
| AI 개선 입력 | raw log 전체가 아니라 `LogSummary`, `supportRouting`, `AiDiagnosisResult`, 관리자 처리 결과 요약만 평가 데이터로 사용한다. `support_outcomes`는 운영 고도화 후보일 때만 사용한다. |

## 17. AS별 상세 시나리오

### 17.1 REMOTE_AGENT - Agent 설치/등록/업로드/권한 오류

| 항목 | 내용 |
|---|---|
| 사용자 증상 | Agent 설치 실패, 등록 실패, 로그 업로드 실패, 권한 오류 |
| 감지 이벤트 | heartbeat 누락, upload 실패, auth 401/409, config parse 실패 |
| 수집 데이터 | Agent version, service/tray status, config schema, last heartbeat, last upload error, token status |
| IncidentWindow | 전 15분/후 5분 |
| LogSummary | 실패 단계, error code, 마지막 성공 시점, 재시도 횟수 |
| supportDecision | `REMOTE_POSSIBLE` |
| reason/action | `AGENT_INSTALL_OR_UPLOAD_FAILURE`, `CHECK_AGENT_CONFIG` |

### 17.2 REMOTE_DRIVER_OS - 드라이버/OS 오류

| 항목 | 내용 |
|---|---|
| 사용자 증상 | 장치 오류, 그래픽 드라이버 오류, Windows Update 후 문제 |
| 감지 이벤트 | display driver reset, device manager error, Windows Update failure |
| 수집 데이터 | driver version, device class, Windows build, update history summary, allowlist eventId |
| IncidentWindow | 전 15분/후 5분 |
| LogSummary | 드라이버 변경 시점, 오류 반복 빈도, 업데이트와 증상 시간 상관관계 |
| supportDecision | `REMOTE_POSSIBLE` 또는 하드웨어 반복 신호 시 `VISIT_REQUIRED` |
| reason/action | `DRIVER_CRASH_LOG`, `REINSTALL_GRAPHICS_DRIVER` |

### 17.3 REMOTE_APP_LAUNCHER - 앱/런처 실행 오류

| 항목 | 내용 |
|---|---|
| 사용자 증상 | 앱 실행 안 됨, 런처 오류, runtime missing, 권한 오류 |
| 감지 이벤트 | app crash, installer error, permission denied, runtime error |
| 수집 데이터 | app category, app hash/name allowlist, crash code, runtime package 여부 |
| IncidentWindow | 전 15분/후 5분 |
| LogSummary | 실패 앱, crash signature, runtime/권한/저장공간 조건 |
| supportDecision | `REMOTE_POSSIBLE` |
| reason/action | `APP_SPECIFIC_FAILURE`, `CHECK_RUNTIME_OR_PERMISSION` |

### 17.4 REMOTE_STORAGE_MEMORY - 저장공간/메모리 압박

| 항목 | 내용 |
|---|---|
| 사용자 증상 | PC 느림, 저장공간 부족, 앱 실행 지연, 메모리 부족 메시지 |
| 감지 이벤트 | free space low, high memory pressure, pagefile spike, disk active time high |
| 수집 데이터 | disk free, disk active time, RAM used, commit charge, pagefile usage, process category |
| IncidentWindow | 전 15분/후 5분 |
| LogSummary | 압박 구간, 원인 category, baseline 대비 변화 |
| supportDecision | `REMOTE_POSSIBLE` 또는 디스크 장애 신호 시 `VISIT_REQUIRED` |
| reason/action | `MEMORY_PRESSURE`, `STORAGE_IO_BOTTLENECK`, `CHECK_STORAGE_HEALTH` |

### 17.5 REMOTE_STARTUP_SERVICE - 시작프로그램/백그라운드 서비스 부하

| 항목 | 내용 |
|---|---|
| 사용자 증상 | 부팅 후 느림, 특정 작업 없이 CPU/RAM 사용률 높음 |
| 감지 이벤트 | idle high cpu, startup app spike, service crash loop |
| 수집 데이터 | CPU/RAM baseline, process category, startup impact, service restart count |
| IncidentWindow | 전 15분/후 5분 |
| LogSummary | 유휴 상태 기준 부하, 반복 서비스, 사용자 작업과 무관한 리소스 점유 |
| supportDecision | `REMOTE_POSSIBLE` |
| reason/action | `BACKGROUND_SERVICE_PRESSURE`, `CHECK_STARTUP_APPS` |

### 17.6 REMOTE_LOCAL_NETWORK - 로컬 네트워크 설정/DNS/어댑터 문제

| 항목 | 내용 |
|---|---|
| 사용자 증상 | 인터넷 안 됨, 특정 앱 접속 실패, DNS 오류 |
| 감지 이벤트 | DNS failure, adapter disabled, gateway unreachable, NIC driver error |
| 수집 데이터 | adapter status, gateway presence, DNS result, diagnostic endpoint latency/loss, NIC driver version |
| IncidentWindow | 전 15분/후 5분 |
| LogSummary | DNS/어댑터/드라이버/외부망 후보 분리 |
| supportDecision | `REMOTE_POSSIBLE` 또는 외부망 문제 시 `NEEDS_MORE_INFO` |
| blockingFactors | 외부 ISP/공유기 문제면 `OUT_OF_PC_SCOPE` |

### 17.7 VISIT_BOOT_REMOTE_BLOCKED - 부팅 불가/원격 연결 불가

| 항목 | 내용 |
|---|---|
| 사용자 증상 | 부팅 안 됨, 화면 진입 불가, 원격지원 연결 불가 |
| 감지 이벤트 | heartbeat 장기 누락, 마지막 shutdown abnormal, boot failure event |
| 수집 데이터 | 마지막 정상 heartbeat, 마지막 정상 boot, 마지막 critical event |
| IncidentWindow | 마지막 정상 부팅 이후 critical event 요약 |
| LogSummary | 마지막 정상 상태, 장애 직전 이벤트, 원격 불가 사유 |
| supportDecision | `VISIT_REQUIRED` |
| visitReasons | `DEVICE_OFFLINE`, `REMOTE_HELP_NOT_AVAILABLE` |

### 17.8 VISIT_DISK_FAILURE - SMART critical/디스크 I/O 오류 반복

| 항목 | 내용 |
|---|---|
| 사용자 증상 | 파일 열기 느림, 저장 실패, 부팅 지연, 디스크 오류 |
| 감지 이벤트 | SMART critical, bad block, disk I/O error, filesystem error 반복 |
| 수집 데이터 | SMART summary, disk model hash, free space, I/O latency, eventId allowlist |
| IncidentWindow | 전 30분/후 10분 |
| LogSummary | 디스크 오류 signature, 반복 횟수, 데이터 손실 위험 |
| supportDecision | `VISIT_REQUIRED` |
| visitReasons | `STORAGE_REPLACEMENT_SUSPECTED` |

### 17.9 VISIT_WHEA_BSOD - WHEA/BSOD 반복

| 항목 | 내용 |
|---|---|
| 사용자 증상 | 블루스크린 반복, 갑자기 재부팅, 특정 부하에서 crash |
| 감지 이벤트 | WHEA event, bugcheck, memory hardware error, repeated BSOD |
| 수집 데이터 | bugcheck code, WHEA source, crash count, driver version, temperature, memory pressure |
| IncidentWindow | 전 30분/후 10분 |
| LogSummary | BSOD signature, 반복성, driver/thermal/power와의 상관관계 |
| supportDecision | `VISIT_REQUIRED` |
| visitReasons | `SUSPECTED_HARDWARE_FAILURE`, `BSOD_SIGNATURE` |

### 17.10 VISIT_POWER_SHUTDOWN - 부하 시 전원 꺼짐/Kernel-Power 반복

| 항목 | 내용 |
|---|---|
| 사용자 증상 | 게임/작업 중 PC가 꺼짐, 재부팅됨 |
| 감지 이벤트 | Kernel-Power, unexpected shutdown, high load before shutdown |
| 수집 데이터 | CPU/GPU utilization, temperature, power event, shutdown time, boot time |
| IncidentWindow | 전 30분/후 10분 |
| LogSummary | 꺼짐 직전 부하, 온도, 반복성, driver crash와의 차이 |
| supportDecision | `VISIT_REQUIRED` |
| visitReasons | `PSU_OR_POWER_PATH_RISK` |

### 17.11 VISIT_FAN_THERMAL - 팬 미동작/thermal shutdown

| 항목 | 내용 |
|---|---|
| 사용자 증상 | 과열, 팬 안 돎, 갑자기 꺼짐, 온도 경고 |
| 감지 이벤트 | thermal shutdown, thermal throttle, fan rpm 0 if available |
| 수집 데이터 | CPU/GPU temperature, fan rpm if available, utilization, throttle flag, shutdown event |
| IncidentWindow | 전 30분/후 10분 |
| LogSummary | 온도 상승 곡선, fan 반응, throttle/shutdown 여부 |
| supportDecision | `VISIT_REQUIRED` |
| visitReasons | `THERMAL_SERVICE_REQUIRED` |

## 18. 테스트/수용 기준

### 보안/인증

- register 성공
- 잘못된 activation token
- blocked/revoked device
- 웹 JWT로 `/api/agent/**` 접근 실패
- Agent token으로 웹 JWT API 접근 실패

### idempotency

- 같은 `Idempotency-Key` + 같은 body replay 성공
- 같은 key + 다른 body 충돌
- key 누락 실패

### 로그 업로드

- gzip 정상 업로드
- gzip 아님 실패
- consent 없음 실패
- 5분/30분/60분 incident window
- `rangeStartedAt > rangeEndedAt` 실패
- oversize 실패
- checksum/summary 부재 fallback

### 진단

- gpu thermal
- driver crash
- memory pressure
- storage bottleneck
- power instability
- mixed symptom
- unsupported scope

### 관리자 승인

- `AiDiagnosisResult` 제안만 있고 승인 전에는 최종 결정으로 보지 않음
- 승인 후 `supportDecision` 확정
- 승인 없는 자동 응답 차단
- 금지 상태 전이 409
- remote session 생성
- user consent denied
- visit reservation 생성/취소
- 미지원 항목 관리자 예외 승인

### 사용자 신청/동의/위험 안내

- 사용자 원격지원 신청 생성은 최종 시나리오 필수지만 현재 기존 API에는 없으므로 신규 구현 대상
- 같은 ticket에 진행 중인 원격지원 요청이 있으면 중복 요청 시 409. 단, 요청 리소스/API 확정 후 테스트한다.
- 방문지원은 사용자 직접 신청이 아니라 관리자 검토 후 상태값/메모로 처리
- `SERVER_UPLOAD`, `REMOTE_CONNECTION`, `REMOTE_FULL_CONTROL`, `HIGH_RISK_REMOTE_ACTION` 동의 분리
- `REMOTE_FULL_CONTROL` 동의 없이 전체 제어 진행 차단
- 고위험 조치 동의 없이 registry/driver rollback/firmware/BIOS 조치 차단
- SMART critical, thermal shutdown, Kernel-Power 반복 시 자동 안전 안내 생성
- 미지원 항목은 기본 `supportDecision=NEEDS_MORE_INFO`

### 처리 결과/피드백

- 원격 해결 후 ticket 상태/관리자 메모/audit log 저장
- 원격 실패 후 방문 필요 판단 시 `VISIT_REQUIRED`와 관리자 메모 저장
- 미지원 종료 시 `NEEDS_MORE_INFO` + `blockingFactors` + 관리자 메모 저장
- 재현 실패 시 `NEEDS_MORE_INFO`와 추가 로그 요청 저장
- 사용자 평점/피드백 저장은 현재 기존 계약에 표준 필드가 없으므로 신규 구현 대상
- AI 개선 데이터에 raw log 전체 미포함
- 별도 `support_outcomes` 테이블은 운영 고도화 후보

## 19. 현재 repo 보정 지시

이 문서를 최종 계약서로 사용하려면 시나리오 내용은 오늘 확정안을 따르고, API/DB/스키마 이름은 기존 prototype 작업명에 맞춘다. 아래는 현재 확인된 충돌과 처리 기준이다.

| 현재 확인 | 판단/보정 |
|---|---|
| Flyway `V53__pc_agent_gold_mode_contract.sql`의 `support_decision` check constraint는 4개 coarse enum이다. | 최종 기준과 일치한다. |
| `docs/API_CONTRACT.md`, `docs/DB_SCHEMA.md`, `docs/openapi.yaml`, 일부 Java 코드/테스트에는 `REPAIR_OR_REPLACE`, `MONITOR_ONLY`, `UNSUPPORTED` 확장 enum이 남아 있다. | 오늘 최종 결정과 충돌한다. 4개 coarse enum으로 보정하고, 교체/미지원/관찰은 `reasonCodes`, `visitReasons`, `blockingFactors`, `nextActions`, 관리자 메모로 분리한다. |
| `PcAgentLogAnalyzer`가 `UNSUPPORTED`, `REPAIR_OR_REPLACE`를 `recommendedDecision`으로 반환하는 흐름이 있다. | `UNSUPPORTED`는 `NEEDS_MORE_INFO` + `blockingFactors`, `REPAIR_OR_REPLACE`는 `VISIT_REQUIRED` + `visitReasons`로 변환한다. |
| 기존 API 계약명은 `POST /api/agent/log-uploads`, `POST /api/agent-logs/upload`, `POST/GET /api/as-tickets`, 관리자 `PATCH /api/admin/as-tickets/{id}`다. | 새 문서/코드도 이 이름을 우선 사용한다. 분리형 admin endpoint는 별도 Goal 전까지 현재 계약처럼 쓰지 않는다. |
| 원격지원은 현재 관리자 `PATCH`의 `remoteSupportLink` 저장과 `remote_support_sessions` 생성 흐름이 있다. | 최종 시나리오의 Quick Assist/외부 링크 흐름은 이 기존 구조에 맞춰 구현한다. |
| 방문지원은 현재 관리자 `PATCH`의 `visitSupportRequired`, `visitPreferredDate`, `visitTimeSlot`과 `visit_support_reservations` 생성 흐름이 있다. | 사용자 직접 방문신청 API는 만들지 않는다. |
| 사용자 원격지원 요청 API는 현재 기존 작업에 없다. | 최종 시나리오상 신규 구현 대상이다. 경로명은 기존 API_CONTRACT 갱신 때 확정한다. |
| `support_service_requests`, `support_outcomes`는 현재 migration/API 기존 계약명이 아니다. | 운영 고도화 후보로만 유지하고 선구현하지 않는다. |
| 위험 신호 사용자 안내와 단계별 고위험 원격 조치 동의는 현재 기존 계약에 부족하다. | 최종 시나리오상 필요하므로 신규 구현 대상이다. 단, 기존 `agent_consents` 구조와 호환되게 확장한다. |

## 20. 팀 공유 문장

PC Agent AS 최종 계약은 “범용 PC 문제 해결”이 아니라 등록 기기의 로그 기반 AS 라우팅이다. 기획 내용은 오늘 최신화한 최종 시나리오를 따른다. API 경로, DB 테이블, 컬럼, DTO 이름은 현재 prototype 기존 작업명에 맞춘다. 공식 `supportDecision`은 `SELF_SOLVABLE`, `REMOTE_POSSIBLE`, `VISIT_REQUIRED`, `NEEDS_MORE_INFO` 네 가지 coarse routing으로만 유지한다. 미지원 기본값은 `NEEDS_MORE_INFO`이며, 교체 가능성, 미지원 사유, 방문 상세 사유, 원격 조치 권고는 `reasonCodes`, `remoteActions`, `visitReasons`, `blockingFactors`, `nextActions`로 분리한다. 설치형 PC Agent는 `/api/agent/log-uploads`, 브라우저 수동 업로드는 `/api/agent-logs/upload`를 사용한다. 업로드는 사용자 동의 후 선택 구간 또는 Agent 감지 시점 주변 JSONL gzip이며, 특정 `rangeMinutes` 고정값으로 단정하지 않는다. 사용자는 원격지원을 요청할 수 있어야 하지만 현재 별도 API는 신규 구현 대상이고, 방문지원은 관리자 `VISIT_REQUIRED` 판단과 `visit_support_reservations`로 처리한다. 위험 신호는 자동 안전 안내를 표시하되 최종 지원 결정은 관리자 승인으로 확정한다. LLM에는 원본 로그 전체가 아니라 `IncidentWindow`, `LogSummary`, `supportRouting`을 포함한 `AiDiagnosisRequest`만 전달한다.
