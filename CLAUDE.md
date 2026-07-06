# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 필수 규칙 (AGENTS.md 요약)

- API 계약(`docs/API_CONTRACT.md`), DB 스키마(`docs/DB_SCHEMA.md`), 담당자(`docs/ROUTE_OWNERSHIP.md`) 확인 후 구현한다.
- API 요청/응답 구조를 바꾸면 반드시 `docs/openapi.yaml`을 함께 수정한다.
- 기능 범위, API 계약, DB 컬럼, 상태값, 인증/권한 정책이 불명확하면 추정 구현하지 않고 질문한다.
- 새 구현은 담당 feature/domain 안에 둔다. 공통 파일에 임시 구현을 쌓지 않는다.

### 테스트
```bash
# 프론트엔드 E2E (앱 실행 상태에서)
cd apps/web && npm run test               # playwright (포트 5174, webServer 자동 실행)
cd apps/web && npm run test:e2e:mvp       # MVP 흐름 E2E (포트 5173)

# API 유닛/통합 테스트
cd apps/api && ./gradlew test
# 단일 테스트 클래스 실행
cd apps/api && ./gradlew test --tests "com.buildgraph.prototype.user.UserControllerTest"
```
## 아키텍처 개요

모노레포 구조. `apps/web`(React), `apps/api`(Spring Boot), `apps/pc-agent`(Python CLI), Docker 인프라가 하나의 저장소에 있다.

### 서비스 포트
| 서비스 | 포트 |
|---|---|
| web (dev) | 5173 |
| api | 8080 |
| postgres | 5432 |
| redis | 6379 |
| rabbitmq | 5672 / 15672 |
| mailpit | 1025 / 8025 |
| xgb-reranker | 8091 |


# Self Quote SlotBoard Rules

## Scope
- `/self-quote` is an **information-first estimate builder**: checklist(품목 지도) + slot board(보조 그래프) + candidate panel.
- 2026-07-06 멘토 피드백 기반 리디자인(docs/self-quote-redesign.md) 이후의 규칙이다. 구 P0~P5 마이그레이션은 완료됨.

## Hard constraints
- Do not change backend APIs.
- Do not change route paths.
- Do not change auth guards.
- Do not change DB schema or migrations.
- Do not introduce new dependencies unless explicitly approved.
- Do not modify Home, My Quotes, Build detail, or other `BuildDependencyGraph` consumers.
- `/self-quote` must not render the old node graph or old list/cart workspace.
- Preserve the old PC category / full parts list / quote cart JSX as `LegacySelfQuoteListSections`, but do not render it.

## Design direction
- Final UX is not a physical PC assembly simulator.
- **Information first, graphics assist**: 견적 체크리스트(순서·상태·가격·총액)와 후보 카드가 주인공이고, 보드/연결선은 호환성 이해 도구다. 장식용 배경 평면도·하드웨어 라벨(PCIe/24핀 등)은 쓰지 않는다.
- 보드는 **메인보드 허브 방사형**: 중앙 허브(메인보드)에서 스포크가 뻗고, 크로스 관계는 인접 배치 + 곡선(bow 설정). 장착 시 카드가 허브 방향으로 밀려 들어오는 "꽂힘" 모션(slot-plug-in, reduced-motion 시 off).
- 장착된 슬롯 카드는 **상품 이미지 + 카테고리 + 짧은 요약(shortSpec)**만 — 상품명 전문·가격은 체크리스트와 hover 툴팁(title)이 담당한다. 빈 슬롯은 local SVG glyph(`/slot-board/parts/*.svg`)로 표시한다.
- 관계선 라벨은 **문제일 때만 텍스트**(WARN/FAIL — 서버 사유 그대로), 정상/미장착은 상태 점(dot)만. "PCIe x16/소켓" 같은 전문용어를 평상시에 노출하지 않는다(상세는 title 툴팁).
- Do not draw complex CPU/RAM/GPU/SSD/PSU/case/cooler shapes with CSS.
- SVG files are glyphs only. Product name, category, price, selected ring, status badge, and empty state must be rendered by React/CSS.
- Do not use remote images or bitmap images inside the empty-slot glyph layer.
- 호환 상태 색 체계(전 화면 공통): 정상 = 초록, 주의 = 노랑, 불가 = 빨강, 미검증 = 회색. 정상 연결선을 검정/회색으로 그리지 않는다.
- 사용자 언어 우선: "호환 가능/간섭 주의/장착 불가/파워 부족"처럼 쓴다. "constraint/dependency/socket mismatch" 같은 원어 노출 금지.

## Slot policy
- 8 logical slots: CPU, motherboard, RAM, GPU, SSD, PSU, case, cooler.
- RAM visual mini slots are fixed at 4.
- SSD visual mini slots are fixed at 2.
- Do not imply RAM/SSD mini slots are real motherboard capacities.
- Empty slots show dashed outline and `+ 부품 선택`.

## Candidate panel
- Reuse existing `GET /api/parts`.
- Include `compatibilitySource=QUOTE_DRAFT_CURRENT`.
- Load candidates in 20 item pages.
- **Show ALL candidates**: PASS/WARN은 선택 가능, FAIL은 숨기지 말고 회색 비활성 + 선택 불가 사유를 함께 표시한다(사용자가 "왜 안 되는지" 알 수 있어야 함).
- WARN candidates are selectable and keep `간섭 주의`.
- 버튼 문구: 빈 슬롯 = `담기`, 채워진 단일 슬롯 = `교체`, 교체 대상 지정 시 = `이걸로 교체`.

## Onboarding / guidance
- 견적이 비어 있으면 AI 시작 CTA(챗봇 열기)와 "직접 고르기" 진입을 함께 노출한다 — 숨은 AI 버튼에 의존하지 않는다.
- 권장 선택 순서는 CPU → 메인보드 → RAM → GPU → SSD → 파워 → 케이스 → 쿨러이며, 다음 채울 슬롯을 안내·강조한다(강제 아님 — 아무 슬롯이나 클릭 가능).
- 진행 상태(N/8)와 총액은 항상 보인다.

## Graph validation
- P1+ may reuse `POST /api/build-graphs/resolve`.
- Graph API must not be a hard dependency for basic SlotBoard rendering.
- If graph API fails, fallback topology must still render.

## Verification
After relevant `/self-quote` changes, run:
- `cd apps/web && npm run test -- self-quote.spec.ts`
- `cd apps/web && npm run test -- home.spec.ts`
- `cd apps/web && npm run build`