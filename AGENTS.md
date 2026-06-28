# AGENTS.md

이 문서는 BuildGraph AI 프로토타입 저장소에서 작업하는 에이전트와 개발자가 반드시 따를 기준이다. 기획 명세서의 확정 사항을 우선하며, 확정되지 않은 사항은 임의로 정하지 않는다.

## 최우선 원칙

- 애매하거나 모호한 사항이 있으면 구현 전에 반드시 사용자에게 질문한다.
- 사용자 또는 다른 개발자가 만든 변경사항을 임의로 되돌리지 않는다.
- 기능 범위, API 계약, DB 컬럼, 상태값, 인증/권한 정책이 불명확하면 추정 구현하지 않는다.
- 새 구현은 담당 feature/domain 안에 둔다. 공통 파일에 임시 구현을 쌓지 않는다.
- API 요청/응답 구조를 바꾸면 같은 변경에서 `docs/openapi.yaml`을 함께 수정한다.
- MVP 범위를 벗어난 결제, 배송, 거래, 원격제어, 최저가 보장, 정확한 FPS 보장, 자동 모델 학습은 구현하지 않는다.

## 프로젝트 목표

BuildGraph AI는 사용자가 예산, 용도, 우선순위, 브랜드 선호를 자연어로 입력하면 LLM, RAG, 제한된 Agent Orchestrator, Tool Calling을 통해 PC 부품 조합을 추천하고 검증하는 하드웨어 컨설팅 플랫폼이다.

핵심은 단순 챗봇이 아니라 다음 근거를 저장하고 검증 가능한 추천을 만드는 것이다.

- 자연어 요구사항 파싱
- RAG 근거 검색
- 호환성, 전력, 규격, 성능 범위, 가격 Tool 검증
- 추천 Build 2~3개와 경고 생성
- 부품 변경 영향 비교
- PC Agent 로그 기반 AS 티켓과 관리자 원인 후보 제공

## 먼저 확인할 문서

작업을 시작하기 전에 변경 범위에 맞춰 아래 문서를 확인한다.

| 문서 | 목적 |
| --- | --- |
| `README.md` | 실행 방법, 기술 스택, PR 전 확인 |
| `docs/role-workspaces.md` | 담당자별 파일 소유권과 PR 범위 |
| `docs/sprint-1-start-checklist.md` | 첫 PR 기준과 완료 조건 |
| `docs/architecture.md` | 런타임 흐름, 도메인 구조 |
| `docs/scaffold-decisions.md` | 현재 스캐폴드 결정사항 |
| `docs/openapi.yaml` | API 요청/응답 계약 |
| `apps/pc-agent/README.md` | PC Agent/AS 로그 작업 기준 |

## 기술 스택과 구조

- Frontend: React, TypeScript, Vite, Tailwind, React Router, TanStack Query
- Backend: Spring Boot, Gradle, Java 21
- DB/Infra: PostgreSQL + pgvector, Redis, RabbitMQ, Mailpit, Docker Compose
- PC Agent: Python 3.11 CLI, JSON Lines 로그
- 부하 테스트: k6

주요 경로:

- `apps/web`: React 프론트엔드
- `apps/api`: Spring Boot API
- `apps/pc-agent`: PC Agent CLI
- `infra`: Docker/인프라 설정
- `docs`: OpenAPI, 아키텍처, 역할 문서
- `seed`: 샘플 부품 데이터와 Agent 로그
- `tools`: 검증 스크립트

## 담당 영역

각 담당자는 자기 기능의 화면, API, DB, 테스트까지 책임진다.

| 담당 | 주요 경로 | 책임 |
| --- | --- | --- |
| 1번 | `apps/web/src/features/quote`, `apps/web/src/features/auth` | 사용자 견적 흐름, 추천 UI, 로그인/회원가입 화면 |
| 2번 | `apps/api/.../part`, `apps/api/.../price`, `apps/web/src/features/parts` | 부품 DB, Tool 검증, 룰 기반 성능, 가격 알림 |
| 3번 | `apps/api/.../agent`, `apps/api/.../rag`, 관리자 Agent/RAG/Tool 화면 | LLM/RAG/Agent, Tool 호출 이력, 비용 제어 |
| 4번 | `apps/pc-agent`, `apps/api/.../log`, `apps/api/.../ticket`, `apps/web/src/features/support` | PC Agent, 로그 업로드, AS 티켓 |
| 5번 | `infra`, `.github/workflows`, `tools`, `apps/api/.../user`, `apps/api/.../admin`, 공통 컴포넌트 | 공통 인프라, 인증 공통, 관리자 shell, CI |

5번은 백엔드 전체 담당자가 아니다. User/Auth 공통 모듈을 제외하고 추천 비즈니스 로직, 요구사항 파싱, 부품 검증 Tool, 성능 계산, 가격 매칭, LLM 호출 정책, RAG 검색, PC Agent 로그 수집, AS 티켓 생성 로직은 각 기능 담당자가 구현한다.

## AI, RAG, Tool 책임 경계

LLM이 담당하는 일:

- 요구사항 파싱
- 추가 질문 생성
- 추천 설명
- 부품 변경 영향 설명
- 얕은 AS 원인 후보 생성

LLM이 하지 않는 일:

- 호환성 최종 판정
- 전력 계산
- 규격 검증
- 가격 비교
- 성능 범위 계산 핵심 로직

Tool Calling 순서는 다음 흐름을 기준으로 한다.

```text
RAG 검색
-> Build 후보 생성
-> 호환성/전력/규격 검증
-> 성능 범위/병목 분석
-> 가격 확인
-> Agent 종합 설명
```

Agent는 무제한 자율 Agent가 아니다. 정해진 Tool, 비용 정책, 큐 정책 안에서 동작하는 제한된 오케스트레이터다.

Feedback Loop는 자동 학습이 아니다. 오차를 기록하고 관리자 화면에 개선 후보를 표시하는 방식으로 둔다.

## Tool 응답 규칙

모든 Tool은 공통 응답 형식을 따른다.

```json
{
  "status": "PASS | WARN | FAIL",
  "score": 0.82,
  "confidence": "LOW | MEDIUM | HIGH",
  "summary": "권장 PSU 용량은 750W이며 현재 선택한 650W는 여유율이 낮습니다.",
  "warnings": ["피크 전력 기준 여유율 부족"],
  "evidence": [
    {
      "source_id": "psu-rule-001",
      "summary": "GPU 피크 전력과 CPU TDP를 합산한 뒤 여유율을 적용"
    }
  ]
}
```

- `status` 값은 `PASS`, `WARN`, `FAIL`만 사용한다.
- `confidence` 값은 `LOW`, `MEDIUM`, `HIGH`만 사용한다.
- `evidence`에는 근거 요약과 출처 ID를 넣는다.
- 현재 스캐폴드의 Tool API는 `POST /api/tools/{tool}/check` 축약 경로를 기준으로 한다.
- 사용 가능한 `tool` 값은 `compatibility`, `power`, `size`, `performance`, `price`다.

## API와 데이터 계약

- API 계약은 OpenAPI 수준 상세를 목표로 한다.
- Springdoc/Swagger 자동문서와 `docs/openapi.yaml`의 계약이 어긋나지 않게 유지한다.
- 주요 API마다 request/response 예시를 유지한다.
- page component에서 직접 `api()`를 호출하지 말고 담당 feature의 `*Api.ts`에 API wrapper를 둔다.
- mock 데이터는 담당 feature의 `mocks` 디렉터리에 둔다.
- seed 데이터는 담당 백엔드 domain의 `*Seed.java`에 둔다.
- `common/MockData.java`에는 작은 helper만 둔다.

현재 확정되지 않았으므로 반드시 질문해야 하는 항목:

- 각 엔티티별 실제 컬럼명, 타입, 필수 여부, 기본값, 관계, 인덱스
- status/error/auth/pagination 정책
- 공통 error response schema
- validation error 형식
- rate limit 또는 quota 초과 응답 형식
- 공개 API, 로그인 필요 API, 관리자 API의 권한 경계

## 프론트엔드 작업 규칙

- 기존 React/Vite/Tailwind 구조와 feature 단위 경계를 따른다.
- 공통 layout/display/feedback 컴포넌트는 `apps/web/src/components`에만 둔다.
- feature 전용 컴포넌트와 mock은 각 feature 내부에 둔다.
- `QuotePages.tsx`, `AdminPages.tsx`, `prototypeData.ts`, `components/ui.tsx`는 barrel 용도로만 유지한다.
- 사용자 화면에는 AS 원인 후보 같은 관리자용 진단 정보를 노출하지 않는다.
- 로그인 상태에 따라 내 견적함, 가격 알림, AS 접수 진입 처리를 분리한다.
- 로딩, 에러, 성공 상태를 실제 API 흐름에 맞춰 구현한다.

## 백엔드 작업 규칙

- Spring Boot 도메인 패키지 경계를 유지한다.
- 도메인별 DTO/service/repository 분리는 담당 기능 구현 중 단계적으로 확장한다.
- LLM 관련 요청은 RabbitMQ 큐와 Worker 제한으로 분리하는 방향을 유지한다.
- 비LLM API는 서버에서 직접 처리한다.
- Redis는 캐시와 작업 상태 확장에 사용한다.
- `/api/health`는 프로젝트용 health endpoint이며 DB 연결까지 확인해야 한다.
- 룰 기반 성능 범위/병목 가능성 계산은 MVP에서 정교한 실측 FPS 예측으로 확장하지 않는다.

## 가격 알림 정책

P0 범위:

- 네이버 쇼핑 API를 1차 가격 수집원으로 사용한다.
- 다나와 제한 크롤링으로 PC 부품 가격을 보완한다.
- 하루 1회 가격을 확인한다.
- 상품가는 배송비, 쿠폰, 카드할인을 제외한 표시 가격 기준이다.
- 목표가 이하가 처음 확인되면 이메일을 1회 발송한다.
- 수집 실패 시 이전 가격을 임시 사용한다.
- 마지막 갱신 시각을 저장한다.

P1 범위인 가격 동향 그래프, 수집 대상 확대, 배송비/품절/쿠폰 반영, 알림 조건 고도화는 MVP에서 구현하지 않는다.

## PC Agent와 AS 정책

- PC Agent는 Python CLI로 시작한다.
- 로그 포맷은 JSON Lines이며 한 줄은 하나의 timestamp 관측치로 유지한다.
- 평상시 로그는 5초 간격, 문제 기록 모드는 1초 간격을 기준으로 한다.
- 업로드 범위는 최근 30분으로 제한한다.
- 서버 업로드는 AS 요청 시 명시 동의 후 수행한다.
- 동의 화면에는 업로드 항목, 기간, 보관 기간을 표시한다.
- 업로드 로그는 30일 후 자동 삭제한다.
- 사용자 화면에는 AS 티켓 번호만 제공한다.
- 관리자 화면에는 사용자 증상, 로그 파일, 최근 30분 로그 요약, Agent 원인 후보 1~3개, 간단 업그레이드 후보를 제공한다.
- Quick Assist 실제 연결과 FPS/Frame Time 선택 수집은 P1 이후 범위다.

## 협업과 일정 기준

- 기능 브랜치 기반으로 개발한다.
- 1주차 1~3일에는 OpenAPI, DB, Tool 응답 형식, 화면 흐름을 먼저 고정한다.
- 완성된 기능은 매일 dev 브랜치에 통합한다.
- 3주차 종료 시 기능 동결한다.
- 4주차에는 버그 수정, 성능 개선, 문서화만 허용한다.
- 4주차에 처음 통합하지 않는다.
- 부하 검증은 마지막에 전원이 함께 수행한다.

3주차 전체 E2E 성공 기준:

```text
자연어 입력
-> 요구사항 파싱
-> RAG 근거 검색
-> Tool 검증
-> 추천 결과 표시
-> 부품 변경 비교
-> 가격 알림 등록
-> PC Agent 로그 업로드
-> AS 티켓 생성
-> 관리자 화면에서 Agent 근거 확인
```

## 검증 명령

프론트엔드:

```bash
cd apps/web
npm run build
npm run test
```

백엔드:

```bash
cd apps/api
./gradlew bootJar --no-daemon
```

OpenAPI:

```bash
python tools/validate_openapi.py
```

Docker Compose:

```bash
docker compose config
```

PC Agent 샘플:

```bash
cd apps/pc-agent
python buildgraph_agent.py sample --out ../../seed/sample-agent-log.jsonl
python buildgraph_agent.py export --source ../../seed/sample-agent-log.jsonl --out recent-30m.jsonl --minutes 30
```

macOS/Linux에서 `python` 명령이 없으면 `python3`를 사용한다.

## 성능과 품질 기준

- 비LLM API p95 500ms 이하를 목표로 한다.
- 에러율 1% 이하를 목표로 한다.
- LLM Mock Queue p95 대기 30초 이하를 목표로 한다.
- LLM 비용은 1만 요청 환산 100달러 이하를 목표로 한다.
- 2주차에는 동시 300명, 4주차에는 동시 1,000명 검증을 목표로 한다.
- LLM 부하는 Mock Worker로 큐 안정성을 검증한다.
- 실제 LLM 호출은 100~300건으로 제한해 비용과 지연시간을 측정한다.

## 제외 범위

MVP에서 구현하지 않는다.

- 쇼핑몰/장터 거래
- 결제/배송
- 커뮤니티
- 조립자 매칭
- 역경매
- 장착 주의사항 카드
- 가격 동향 그래프
- Quick Assist 실제 연결
- 자체 원격제어
- 정확한 FPS 보장
- FPS/Frame Time 선택 수집
- 최저가 보장
- 자동 모델 학습

