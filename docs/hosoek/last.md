# BuildGraph AI 남은 작업 최종 정리

#### 작성일: 2026-07-01  
#### 최신커밋: 6a055824fa1fc88c4d71e9927d8b41f2367593a1

기준 문서: 
```
docs/API_CONTRACT.md 
docs/DB_SCHEMA.md
docs/ROUTE_OWNERSHIP.md
docs/openapi.yaml
docs/hosoek/owner5-work-analysis-checklist.md
```

## 완료 기준

프로젝트 완료는 화면 렌더링이 아니라 실제 사용자 흐름이 서버, DB, 권한, 테스트까지 닫히는 상태를 뜻한다.

MVP 완료 기준:

```text
로그인
-> 자연어 구매 상담
-> 요구사항 저장
-> RAG/Tool 근거 기반 추천
-> 추천 견적 적용
-> 내 견적함 확인
-> 가격 알림 등록
-> PC Agent 로그 업로드
-> AS 티켓 생성
-> 관리자 화면에서 Agent 근거와 AS 티켓 확인
```

공통 검증 기준:

- `apps/web`: `npm run build`, `npm run test`
- `apps/api`: `./gradlew test --no-daemon`, `./gradlew bootJar --no-daemon`
- OpenAPI: `python tools/validate_openapi.py`
- Docker: `docker compose config`, 필요 시 `docker compose up --build`
- Runtime E2E: 실제 서버와 DB 기준으로 MVP 전체 흐름 1회 이상 성공

## MVP를 끝내기 위한 작업

| 영역 | 현재 상태 | 남은 작업 | 완료 기준 | 담당/협업 |
| --- | --- | --- | --- | --- |
| Auth/User | 부분완료 | 이메일 회원가입, 로그인, refresh/logout, `/auth/me`는 있으나 OAuth 계약과 공통 client 최종 정리가 남음 | 로그인/회원가입/refresh/logout/me가 실제 JWT와 refresh token 기준으로 통과하고, 401/403/ErrorResponse 테스트가 있음 | 1번, 5번 |
| OAuth 계약 | 미확정 | Google OAuth를 MVP에 넣을지 결정. 넣으면 start/callback/exchange, Redis one-time code, account linking 구현. 빼면 OpenAPI/API_CONTRACT에서 MVP 범위 조정 | OAuth 포함 또는 제외가 문서와 코드에서 일치함 | 1번, 5번 |
| 공통 API Client | 부분완료 | `api.ts`가 `ErrorResponse.code/message/details`를 보존하고, access token 만료 시 refresh retry 1회, 실패 시 `clearToken()`, logout API 호출 흐름을 고정 | Playwright에서 refresh 성공/실패/logout/error normalization 테스트 통과 | 5번 |
| Quote/Build 소유권 | 부분완료 | `requirements`, `builds`, `build_items` 저장/조회/변경을 현재 로그인 사용자 기준으로 수정 | 타인 requirement/build/history/detail/change-part 접근 시 404 contract test 통과 | 1번 |
| 구매 상담/추천 | 부분완료 | 자연어 입력, 요구사항 저장, RAG/Tool 근거, 추천 2~3개, 추천 적용까지 실제 DB 기준 E2E 연결 | 로그인 사용자 기준으로 build-chat 또는 requirement flow가 추천 결과를 만들고 견적초안/Build로 이어짐 | 1번, 2번, 3번 |
| 내 견적함 | 미완료 | `/my/quotes`의 `quoteMock` 제거, `GET /api/builds/history` 실제 API 연결, loading/error/empty/success 처리 | 로그인 사용자별 저장 Build 목록이 화면에 표시되고 테스트가 있음 | 1번, 2번 |
| 부품/가격/Tool | 부분완료 | 내부 자산 `parts`, 가격 스냅샷, 5개 Tool check, 견적초안 적용/수정/삭제를 계약 DTO와 맞춤 | 각 API가 OpenAPI DTO와 일치하고 controller/service test 통과 | 2번 |
| 가격 알림 | 부분완료 | 현재 사용자 기준 등록/조회, active 중복 409, 목표가 이하 최초 확인 시 이메일 1회 발송 정책 구현 | 가격 알림 등록/조회/중복/메일 발송 smoke 테스트 통과 | 2번, 5번 |
| 가격 Job | 부분완료 | `POST /api/admin/price-jobs/run` 중복 실행 409, 상태 전이, 실패 이력, 관리자 화면 실제 연결 | `/admin/price-jobs`에서 실행/상태/실패 이력이 실제 API로 동작 | 2번, 5번 |
| Agent/RAG/Tool 근거 | 부분완료 | Agent session create/run/get의 본인 소유권 404, 관리자 list/detail 정책, Tool/RAG 근거 조회 계약 정리 | 사용자/관리자 권한별 Agent/RAG/Tool 조회 테스트 통과 | 3번, 5번 |
| Agent 실행 방식 | 미확정 | MVP에서 동기 실행으로 둘지 RabbitMQ queue worker까지 할지 결정 | MVP 방식이 문서와 코드에서 일치하고 상태 전이가 테스트됨 | 3번, 5번 |
| AS Chat | 부분완료 | AS 티켓 기준 LLM/RAG/Tool 답변 저장, OPENAI_API_KEY 없음, stream fallback, 실패 응답을 테스트로 고정 | `/support/ai-chat`가 티켓 기준으로 동작하고 실패 상태가 사용자에게 안전하게 표시됨 | 3번, 4번 |
| PC Agent CLI | 부분완료 | sample/export 유지, JSONL schema validation 후보 확정, 실제 업로드 파일과 같은 field 기준 유지 | `sample`과 `export` 명령이 계속 동작하고 샘플 로그로 AS 접수 가능 | 4번 |
| 로그 업로드 | 부분완료 | 현재 로그인 사용자 기준 저장, 파일 필수, MIME/확장자, 10 MiB, 20000 line, JSONL line validation, PII masking, `FILE_VALIDATION_ERROR` 구현 | 검증 실패 시 row/file 미생성, 성공 시 `delete_after=30일` 저장 | 4번 |
| AS 티켓 사용자 API | 부분완료 | 티켓 생성/조회에 현재 사용자 소유권, 타인 티켓 404, soft delete 제외 적용 | `POST/GET /api/as-tickets` contract test 통과 | 4번 |
| 관리자 AS | 미완료 | `/admin/as-tickets`, `/admin/as-tickets/:ticketId` mock/static 제거, 실제 API 연결, 담당자 배정, 상태 저장 구현 | 목록/상세/update가 실제 API로 동작하고 no-op 버튼이 없음 | 4번, 5번 |
| AS 상태 전이/audit | 미완료 | `PATCH /api/admin/as-tickets/{id}` 허용 전이, 금지 전이 409, `resolvedAt`, `assignedAdminId`, `admin_audit_logs` 기록 구현 | 상태 전이 성공/실패/audit log 테스트 통과 | 4번, 5번 |
| AdminShell | 부분완료 | 8개 메뉴와 route는 있으나 owner별 list/detail 정책 공유 필요 | nav label/order/route가 2/3/4번 담당 화면과 충돌 없음 | 5번, 2/3/4번 |
| AdminDashboard | 부분완료 | dashboard 지표가 실제 도메인 데이터 변화와 맞는지 최종 smoke | `agentRunning`, `openTickets`, `priceJobsRunning`, `degraded`가 실제 DB 기준 표시됨 | 5번 |
| Redis/RabbitMQ/Mailpit | smoke 완료 | 실제 기능이 붙으면 connection smoke에서 기능 smoke로 승격 | OAuth code, queue job, email 중 실제 사용 기능 기준 smoke 통과 | 5번, 관련 owner |
| CI/OpenAPI | 부분완료 | 새 API가 생길 때 `tools/validate_openapi.py` 필수 path/schema에 즉시 반영 | OpenAPI 검증이 누락 API를 잡고 CI에서 실패시킴 | 5번 |
| 최종 E2E | 미완료 | 실제 서버+DB로 MVP 전체 흐름을 한 번에 검증 | 로그인부터 관리자 확인까지 1회 이상 성공하고 결과 문서화 | 전체, 5번 검증 |

## MVP 이후 기능 구현을 끝내기 위한 작업

| 영역 | 구현할 기능 | 끝내기 위한 작업 | 완료 기준 | 담당/협업 |
| --- | --- | --- | --- | --- |
| Google OAuth 확장 | 소셜 로그인 | Google start/callback/exchange, Redis one-time code TTL, single-use 삭제, local 계정 연결, OAuth 오류 처리 | Google 로그인으로 JWT/refresh token 발급 성공, token 저장/권한 분기 통과 | 1번, 5번 |
| 실제 queue worker | 비동기 작업 처리 | Agent job, price job, mail job을 RabbitMQ publish/consume/ack/retry/dead-letter 정책으로 구현 | worker 재시도/실패/중복 방지/상태 전이가 테스트됨 | 2번, 3번, 5번 |
| Agent 고도화 | 장시간 LLM/RAG 실행 | 비용 제한, queue 상태, 취소, 실패 fallback, 실행 로그를 관리자에서 확인 | 대량 요청에서도 queue 안정성과 상태 전이가 유지됨 | 3번, 5번 |
| 가격 수집 고도화 | 네이버 API/다나와 보완 강화 | 수집 대상 확대, 후보 검수, 자동 게시 정책, 실패 시 이전 가격 fallback 정교화 | 새 가격 후보가 검수/게시 흐름을 거쳐 사용자 API에 반영됨 | 2번 |
| 가격 동향 | 그래프/분석 | 가격 이력 그래프, 기간 필터, 최저/평균가, 품절/쿠폰/배송비 반영 여부 결정 | `/parts/:id`에서 가격 동향이 실제 `price_snapshots` 기준 표시됨 | 2번 |
| 가격 알림 확장 | 알림 정책 고도화 | 재알림, 수신거부, 알림 조건 다양화, 메일 템플릿, 실제 SMTP 연동 | Mailpit 이후 실제 SMTP에서도 알림 발송 추적 가능 | 2번, 5번 |
| 부하 테스트 확장 | 300명/1000명 검증 | k6 시나리오 분리, 로그인/추천/부품/AS/admin endpoint별 p95와 에러율 리포트 | 목표 p95/에러율 기준으로 리포트 생성 | 5번, 전체 |
| PC Agent 실제 수집 | 운영체제 지표 수집 | psutil, NVML, Windows Event Log, 문제 기록 모드, schema validation 강화 | 실제 PC에서 최근 30분 로그를 export하고 업로드 가능 | 4번 |
| 로그 보관 자동화 | 보관 만료 처리 | `delete_after` 기준 자동 삭제 scheduler, 삭제 로그, 실패 재시도 | 30일 만료 로그가 자동 삭제되고 audit 가능 | 4번, 5번 |
| AS workflow | 상담 처리 고도화 | 상담원 답변, 티켓 종료 요청, 재오픈, 업그레이드 후보 등록, AS Chat 결과 승인 반영 | 사용자/관리자 양쪽에서 AS 처리 흐름이 완결됨 | 4번, 3번 |
| 관리자 운영 고도화 | 운영 생산성 | 전역 검색, export/action button, 운영 작업 패널 실시간화, degraded 원인 drill-down | 관리자가 티켓/부품/job/agent 상태를 한 화면에서 추적 가능 | 5번, 2/3/4번 |
| 회원가입 메일 | 이메일 인증 | 인증 메일, 인증 토큰 TTL, 재발송, 가입 완료 정책, Mailpit/SMTP 검증 | 인증 전/후 로그인 정책이 명확하고 테스트됨 | 1번, 5번 |
| 보안 강화 | 인증/권한 정리 | Spring Security filter 전환 여부 결정, JWT secret/env 운영화, refresh token rotation 탐지 | 권한 우회 테스트와 token 만료 테스트 통과 | 1번, 5번 |
| 관측성 | 운영 로그/지표 | API latency, error rate, queue depth, LLM cost, mail send result 기록 | dashboard나 report에서 핵심 운영 지표 확인 가능 | 5번, 전체 |
| 배포 준비 | 실행 안정화 | `.env` 운영 분리, Docker image build, seed 분리, README 실행 절차 보강 | 새 환경에서 문서대로 실행하면 MVP E2E 재현 가능 | 5번 |

## MVP 이후에도 별도 기획 없이는 구현하지 않을 범위

| 제외 기능 | 이유 |
| --- | --- |
| 결제/배송/거래 | BuildGraph AI MVP는 구매 컨설팅과 검증 플랫폼이지 쇼핑몰 거래 플랫폼이 아님 |
| 커뮤니티/장터 | MVP 핵심 E2E와 무관하고 운영 정책이 별도로 필요함 |
| 원격제어/Quick Assist 실제 연결 | 보안, 권한, 책임 범위가 커서 P1 이후 별도 기획 필요 |
| 정확한 FPS 보장 | 현재 목표는 성능 범위/병목 가능성 설명이며 정확한 FPS 보장이 아님 |
| 최저가 보장 | 가격 수집은 참고/알림 목적이며 가격 보장 정책이 아님 |
| 자동 모델 학습 | Feedback loop는 개선 후보 기록이며 자동 학습이 아님 |

## 우선순위 실행 순서

| 순서 | 작업 묶음 | 이유 |
| --- | --- | --- |
| 1 | 현재 사용자 소유권 정리 | 여러 사용자가 쓰는 서비스에서 가장 치명적인 계약 위반을 먼저 막아야 함 |
| 2 | mock/static 화면 제거 | MVP 시연에서 실제 데이터 흐름을 보여주기 위한 필수 조건 |
| 3 | 상태 전이/409/ErrorResponse 정리 | 실패 상황이 계약대로 동작해야 PR 이후 회귀를 막을 수 있음 |
| 4 | 가격 알림/AS/관리자 실제 API 연결 | MVP E2E의 후반부를 닫는 작업 |
| 5 | 최종 E2E와 검증 명령 고정 | 프로젝트 완료 판정을 객관적으로 만들기 위한 마지막 단계 |

