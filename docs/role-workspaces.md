# 역할별 작업 범위

이 문서는 팀원이 자기 담당 파일과 PR 범위를 확인하기 위한 기준입니다. 기능 구현은 각 담당자가 자기 영역 안에서 시작하고, 공통 계약이 바뀌면 같은 PR에서 문서와 테스트를 함께 수정합니다.

## 담당자별 범위

| 담당 | 주요 경로 | 책임 |
| --- | --- | --- |
| 1번 | `apps/web/src/features/quote`, `apps/web/src/features/auth` | 소비자 견적 흐름, 추천 UI, 로그인/회원가입 화면 |
| 2번 | `apps/api/src/main/java/com/buildgraph/prototype/part`, `apps/api/src/main/java/com/buildgraph/prototype/price`, `apps/web/src/features/parts` | 부품 DB, Tool check, 가격 알림 |
| 3번 | `apps/api/src/main/java/com/buildgraph/prototype/agent`, `apps/api/src/main/java/com/buildgraph/prototype/rag`, `apps/web/src/features/admin/pages/*Agent*`, `*Tool*`, `*Rag*` | Agent/RAG/Tool 근거와 관리자 검토 화면 |
| 4번 | `apps/pc-agent`, `apps/api/src/main/java/com/buildgraph/prototype/log`, `apps/api/src/main/java/com/buildgraph/prototype/ticket`, `apps/web/src/features/support` | PC Agent, 로그 업로드, AS 티켓 |
| 5번 | `infra`, `.github/workflows`, `tools`, `apps/api/src/main/java/com/buildgraph/prototype/user`, `apps/api/src/main/java/com/buildgraph/prototype/admin`, `apps/web/src/components`, `apps/web/src/features/admin/pages/AdminDashboardPage.tsx` | 인프라, 인증 공통, 관리자 shell, CI |

## 첫 PR 목표

| 담당 | 첫 PR에서 할 일 | 같이 확인할 것 |
| --- | --- | --- |
| 1번 | 로그인/회원가입 form state 연결, 요구사항 입력과 추천 API 연결 | `quoteApi.ts`, `authApi.ts`, route smoke test |
| 2번 | 부품/가격 DTO/service skeleton, Tool별 입력 구조 초안 | `partsApi.ts`, `docs/openapi.yaml` |
| 3번 | Agent 상태 전이 skeleton, RAG 근거 조회 경계 | 관리자 Agent/RAG/Tool 화면, OpenAPI schema |
| 4번 | JSONL export와 AS 업로드/티켓 생성 흐름 연결 | `supportApi.ts`, log/ticket controller |
| 5번 | admin shell/auth guard 위치 정리, CI/Docker 유지 | GitHub Actions, `/api/health`, Docker Compose |

## 프론트엔드 소유권

| 경로 | 담당 | 규칙 |
| --- | --- | --- |
| `apps/web/src/features/quote/pages` | 1번 | 홈, 요구사항 입력, 추천 결과, 부품 변경, 내 견적 |
| `apps/web/src/features/quote/components` | 1번 | 견적 카드 등 quote 전용 컴포넌트 |
| `apps/web/src/features/auth` | 1번, 5번 | 1번은 화면 state, 5번은 인증 공통 정책 |
| `apps/web/src/features/parts` | 2번 | 셀프 견적, 부품 표, Tool check |
| `apps/web/src/features/support` | 4번 | AS 접수, 티켓 상세, 로그 업로드 정책 |
| `apps/web/src/components` | 5번 | 공통 layout/display/feedback만 배치 |

## 관리자 화면 소유권

`apps/web/src/features/admin`은 여러 담당자가 같이 사용하므로 아래 기준을 따릅니다.

| 파일 | 담당 | 책임 |
| --- | --- | --- |
| `AdminDashboardPage.tsx` | 5번 | 관리자 첫 화면, 운영 요약 |
| `AdminPartsPage.tsx` | 2번 | 부품, 가격, 가격 작업 상태 |
| `AgentSessionAdminPage.tsx` | 3번 | Agent 세션 상태와 근거 |
| `ToolInvocationAdminPage.tsx` | 3번 | Tool 호출 상세 |
| `RagEvidenceAdminPage.tsx` | 3번 | RAG 근거 상세 |
| `AdminTicketsPage.tsx` | 4번 | AS 티켓 목록 |
| `AdminTicketDetailPage.tsx` | 4번 | AS 티켓 상세, 로그 정책, 원인 후보 |
| `AdminShell` | 5번 | 관리자 layout과 navigation |

## 시드와 Mock 데이터

| 데이터 | 위치 | 규칙 |
| --- | --- | --- |
| 백엔드 seed | 담당 domain의 `*Seed.java` | domain 데이터는 `common/MockData.java`에 넣지 않습니다. |
| 프론트 mock | 담당 feature의 `mocks` 디렉터리 | `src/data/prototypeData.ts`는 호환용 barrel로만 유지합니다. |
| 공통 유틸 | `common/MockData.java` | `map()`, `now()` 같은 작은 helper만 유지합니다. |

## API 계약 규칙

- API 요청/응답 구조를 바꾸면 [openapi.yaml](openapi.yaml)을 같은 PR에서 수정합니다.
- 기능 API 호출은 page component에서 `api()`를 직접 호출하지 말고 담당 `*Api.ts`에 추가합니다.
- 오류 응답 세부 정책은 각 domain DTO/service가 구체화될 때 함께 확정합니다.
- 성공 요청 body, 인증 필요 여부, 핵심 response 필드명처럼 담당자 간 해석이 갈릴 수 있는 계약은 먼저 맞춥니다.
- 현재 Tool API는 `/api/tools/{tool}/check` 축약 방식을 사용합니다.

## CI 책임

| 검사 | 담당 | 목적 |
| --- | --- | --- |
| `npm run build`, `npm run test` | 전체, 5번 유지 | 프론트 build와 17개 route smoke |
| `python tools/validate_openapi.py` | API 담당, 5번 유지 | OpenAPI YAML과 핵심 requestBody 확인 |
| `./gradlew bootJar --no-daemon` | 백엔드 담당, 5번 유지 | API compile/package 확인 |
| `docker compose config` | 5번 | Compose 구조 확인 |
| `/api/health` runtime smoke | 5번 | API jar 실행, DB 연결, health 응답 확인 |

CI는 배포, 브랜치 보호 설정, 300명/1000명 부하 테스트 완성본을 담당하지 않습니다. 해당 항목은 Sprint 중 별도 작업으로 확장합니다.
