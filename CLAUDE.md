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
- 보드는 **실장도(placement)**: 추상 메인보드 평면도(BoardPlanArt, viewBox 160×100)의 실장 지점(CPU 소켓·DIMM·PCIe·M.2·명판)에 부품이 꽂히고, 보드에 안 꽂히는 부품(파워·쿨러·케이스)은 우측 도킹 베이. **배치는 고정** — 아트와 핫스팟 좌표(SLOT_CONFIGS)는 같은 상수 계보라 함께만 움직인다(구 관리자 드래그 배치는 미사용). 장착 시 꽂힘 모션(slot-plug-in)+선 draw-in+포트 점등, reduced-motion 시 전부 off.
- 부품 표현은 **카테고리 통일 SVG 에셋**(`/slot-board/parts/*.svg`, 단색 기하 추상 — 기판 #f4f7fb/부품 #e2e8f0/선 #cbd5e1) — 개별 상품 사진을 보드에 쓰지 않는다. 상품명 전문·가격은 체크리스트와 hover 툴팁(title)이 담당한다.
- 실장 관계(CPU-보드, RAM-보드, GPU-보드)는 선을 그리지 않는다 — 꽂혀 있는 그림이 관계다(상태 점/문제 라벨만 실장 지점 옆에). 도킹 부품의 관계(24핀·쿨러 장착·전력 등)만 연결선으로 그린다.
- 관계선 라벨은 **문제일 때만 텍스트**(WARN/FAIL — 서버 사유 그대로), 정상/미장착은 상태 점(dot)만. "PCIe x16/소켓" 같은 전문용어를 평상시에 노출하지 않는다(상세는 title 툴팁).
- Do not draw complex CPU/RAM/GPU/SSD/PSU/case/cooler shapes with CSS.
- SVG files are glyphs only. Product name, category, price, selected ring, status badge, and empty state must be rendered by React/CSS.
- Do not use remote images or bitmap images inside the empty-slot glyph layer.
- 호환 상태 색 체계(전 화면 공통): 정상 = 초록, 주의 = 노랑, 불가 = 빨강, 미검증 = 회색. 정상 연결선을 검정/회색으로 그리지 않는다.
- 구성 관계도 보드 표면은 **짙은 그래파이트 트레이**(`.slot-board-tray`, #171d27 계열 + 도트 텍스처)다 — 흰 페이지에 묻히지 않게 대비를 준다. PCB 평면도는 어두운 기판(#232c3a)에 밝은 소켓/트레이스, 장착 슬롯은 흰 카드로 트레이 위에 떠오른다. 빈 실장 슬롯 라벨은 데스크톱에서 밝게 반전(lg:text-slate-100). 상태색(초록/노랑/빨강)은 트레이 위에서 그대로 유지.
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
- **검색·정렬은 기존 `GET /api/parts` 파라미터를 재사용한다(백엔드 무변경)**: 이름·제조사 검색은 `q`(입력 디바운스 300ms), 정렬은 `sort=compatibility`(호환 가능 우선 — 서버가 PASS→WARN→FAIL 후 가격순)·`price_asc`·`price_desc`·`name`. 기본 정렬은 `price_asc`(패널 오픈 속도).
- **Show ALL candidates**: PASS/WARN/FAIL 전부 담을 수 있다. FAIL(장착 불가) 후보는 숨기지 말고 빨강 경고 스타일(빨간 테두리 버튼 + '장착 불가' 뱃지 + 사유)로 표시하되 담기 자체는 허용한다 — 담으면 보드에서 빨강으로 보고 교체하는 UX다(구매는 여전히 호환 FAIL이면 차단). "왜 안 되는지"를 눈으로 확인할 수 있어야 함.
- WARN candidates are selectable and keep `간섭 주의`.
- 버튼 문구: 빈 슬롯 = `담기`, 채워진 단일 슬롯 = `교체`, 교체 대상 지정 시 = `이걸로 교체`.
- **장착된 슬롯 박스는 상태색으로 칠한다**: 정상=초록(bg-emerald-100), 간섭 주의=주황(bg-amber-100), 장착 불가=빨강(bg-red-100) 진한 틴트 + 상태색 굵은 테두리(border-2) + 상시 상태색 링/글로우 펄스(slot-*-pulse — 쉴 때도 링이 보이게). 어두운 트레이 위에서도 색이 확실히 읽히도록 시인성을 우선한다. 상품 이미지는 중앙을 덮어 그대로 보인다.

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