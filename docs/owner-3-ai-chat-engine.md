# 3번 담당자 AI Chat Engine 핸드오프

이 문서는 쇼핑몰 챗봇 UI가 호출할 백엔드 AI 엔진의 1차 계약이다. UI 화면 구현은 포함하지 않는다.

정량 평가 기준은 `docs/owner-3-ai-chat-engine-evaluation.md`를 따른다.

## 목적

`AiChatEngine`은 자연어 상담 메시지를 받아 견적/부품 추천 의도를 판단하고, 화면 또는 기존 기능 API가 실행할 수 있는 `actions`를 반환한다. 엔진은 장바구니, 견적초안, 가격알림을 직접 저장하지 않는다.

## Java 경계

- Interface: `com.buildgraph.prototype.agent.AiChatEngine`
- 기본 구현: `com.buildgraph.prototype.agent.DefaultAiChatEngine`
- 요청 DTO: `AiChatEngineRequest`
- 응답 DTO: `AiChatEngineResponse`
- 요구사항 분석 연결 DTO: `QuoteRequirementAnalysisRequest`, `QuoteRequirementAnalysisResult`

실서비스 홈 챗봇 경로인 `POST /api/ai/build-chat`은 `AiChatEngine.respondLlmRequired()`를 호출한다. 이 경로는 LLM/RAG 필수이며 `OPENAI_API_KEY`가 없으면 `428 PRECONDITION_REQUIRED`를 반환한다. `respond()`는 엔진 단위 평가와 내부 deterministic baseline을 위한 메서드로 남겨 둔다.

## 입력

`AiChatEngineRequest`

| 필드 | 설명 |
| --- | --- |
| `message` | 사용자 자연어 메시지. 필수 |
| `surface` | 호출 화면. 예: `HOME`, `SELF_QUOTE`, `PART_DETAIL` |
| `selectedCategory` | 현재 선택된 부품 카테고리. 예: `GPU`, `RAM` |
| `buildId` | 현재 보고 있는 추천 Build public id |
| `draftId` | 현재 견적초안 public id |
| `context` | 화면이 가진 추가 문맥. 기존 선택 부품, 예산, 필터 등 |
| `userInternalId` | 인증 사용자의 내부 id. 없으면 추적 목적에서만 null 허용 |

## 출력

`AiChatEngineResponse`

| 필드 | 설명 |
| --- | --- |
| `assistantMessage` | 사용자에게 보여줄 한국어 응답 |
| `intent` | 엔진이 판단한 의도 |
| `actions` | UI/API가 실행할 수 있는 후속 작업 |
| `recommendations` | PC 조합 후보 |
| `partRecommendations` | 단일 부품 후보 |
| `parsedContext` | 예산, 용도, 해상도 등 구조화 문맥 |
| `evidenceIds` | RAG 근거 public id 목록 |
| `toolResults` | Tool 검증 결과. 직접 Tool check 저장은 하지 않음 |
| `agentSessionId` | Agent trace public id |

## 지원 Intent

| Intent | 의미 |
| --- | --- |
| `FULL_BUILD_RECOMMEND` | 자연어로 PC 전체 견적 후보를 요청 |
| `PART_RECOMMEND` | 특정 카테고리 부품 추천 요청 |
| `BUILD_MODIFY` | 기존 견적의 특정 부품 변경 제안 |
| `PRICE_ALERT_HELP` | 목표가 알림 생성을 위한 조건 추출 |
| `EXPLAIN` | 추천/부품 선택 이유 설명 |
| `ASK_FOLLOW_UP` | 정보 부족으로 추가 질문 필요 |

## 지원 Action과 실행 책임

| Action | 엔진 payload | 실제 실행 주체 |
| --- | --- | --- |
| `OPEN_SELF_QUOTE` | `{ route: "/self-quote" }` | UI 라우터 |
| `OPEN_ROUTE` | `{ route, source, reason? }` | UI 라우터. 공개 Build Chat 응답에서는 사용자 화면 allowlist만 허용 |
| `ADD_PART_TO_DRAFT` | `{ partId, category, quantity, source }` | `PUT /api/quote-drafts/current/items/{partId}` |
| `REPLACE_DRAFT_PART` | `{ category, partId?, quantity, source }` | 견적초안 API. 카테고리 중복/교체 규칙은 2번 API가 처리 |
| `REMOVE_DRAFT_PART` | `{ partId, category, source }` | `DELETE /api/quote-drafts/current/items/{partId}` |
| `UPDATE_DRAFT_QUANTITY` | `{ partId, category, quantity, source }` | `PATCH /api/quote-drafts/current/items/{partId}`. RAM/SSD 수량 조정에 우선 사용 |
| `ADD_BUILD_TO_DRAFT` | `{ items: [{ partId, category, quantity }], source }` | 현재는 UI가 품목별로 견적초안 API 호출. 추후 bulk API가 생기면 2번 계약에 맞춰 변경 |
| `CREATE_PRICE_ALERT` | `{ partId?, category?, targetPrice, source }` | `POST /api/price-alerts` |
| `ASK_FOLLOW_UP` | `{ missing, message }` | UI 채팅창에 후속 질문 표시 |

`AiChatEngine` 내부 action 범위는 위 표처럼 넓게 둔다. 공개 `POST /api/ai/build-chat` 응답에서 현재 프론트가 자동 실행하는 action은 `OPEN_ROUTE`, `ADD_BUILD_TO_DRAFT`, `ADD_PART_TO_DRAFT`, `REPLACE_DRAFT_PART`, `REMOVE_DRAFT_PART`, `UPDATE_DRAFT_QUANTITY`, `ASK_FOLLOW_UP` subset이다. `OPEN_SELF_QUOTE`, `CREATE_PRICE_ALERT`는 엔진 공통화와 후속 UI 연결을 위한 내부 계약으로 남긴다.

## 자연어 화면 이동 정책

- 프론트 fast route는 `GPU 보여줘`, `내 견적함 열어줘`처럼 명확한 이동 표현만 0초대 shortcut으로 처리한다.
- `GPU 추천해줘`, `GPU 더 싼 걸로`, `RAM 64GB로`, `이 견적 담아줘` 같은 추천/교체/삭제/담기 명령은 화면 이동으로 오탐하면 안 된다.
- fast route가 잡지 못한 이동 표현은 Build Chat LLM structured output의 `routeIntent`가 담당한다.
- 내부 `routeIntent` shape는 `{ shouldNavigate, routeType, category, partQuery, confidence, reason }`다.
- 서버는 `shouldNavigate=true`이고 `confidence=HIGH`일 때만 `OPEN_ROUTE` action으로 변환한다.
- route는 사용자 화면 allowlist만 허용한다. 관리자 화면, 임의 URL, 결제 확정/주문 확정 route는 자동 실행하지 않는다.
- 상품 상세 이동은 `PART_DETAIL` routeIntent가 오더라도 서버 `PartRouteResolver`가 단일 고확신 `ACTIVE` 부품을 찾은 경우에만 `/parts/{partId}`로 변환한다.
- `5090 보여줘`, `MSI 보드 보여줘`처럼 후보가 여러 개인 표현은 `/parts/{partId}`로 자동 이동하지 않는다. 카테고리가 명확하면 `/self-quote?category=...`로 낮추거나 일반 답변으로 처리한다.
- UI는 서버가 내려준 `OPEN_ROUTE`도 반드시 프론트 allowlist로 재검증한 뒤 `navigate()`해야 한다.

## 소유권 원칙

- 3번은 자연어 해석, RAG 근거 검색, Agent session 상태 전이, LLM structured output, Tool trace 연결을 담당한다.
- 1번 `BuildQueryService`는 AI 엔진 결과를 사용하되 `builds`, `build_items` 저장 책임을 유지한다.
- 2번 `parts`, `ToolCheckService`, `quote_drafts` 저장 규칙은 3번이 직접 수정하지 않는다.
- AI 엔진은 `quote_drafts`, `quote_draft_items`, `parts`를 직접 쓰지 않는다.
- `/self-quote` 챗봇이 `currentQuoteDraft`를 전달하면 3번 엔진은 장바구니 변경안을 `actions`로만 반환한다. UI는 응답 수신 즉시 기존 quote draft API를 자동 호출하며, 실패 시 재시도 상태만 제공한다.
- AI build의 표시 가격은 `items[].price * items[].quantity` 합계다. `estimatedTotalPrice`는 LLM/엔진 참고값이며 홈 추천 카드나 견적초안 총액의 기준으로 사용하지 않는다.
- 직접 Tool check API 호출 결과는 `tool_invocations`에 저장하지 않는다. Agent 또는 추천 흐름 내부에서 실행된 Tool trace만 저장한다.
- AS Chat은 이번 문서의 직접 범위가 아니다. 다만 향후 `AsChatEngine`으로 분리해도 `assistantMessage`, `actions`, `evidenceIds`, `toolResults`, `agentSessionId` 형태는 재사용한다.

## 호출 예시

### PC 전체 추천

요청:

```json
{
  "message": "200만원 QHD 게임용 PC 추천해줘",
  "surface": "HOME",
  "context": {}
}
```

응답 핵심:

```json
{
  "intent": "FULL_BUILD_RECOMMEND",
  "actions": [
    { "type": "OPEN_SELF_QUOTE", "label": "셀프 견적으로 보기", "payload": { "route": "/self-quote" } },
    { "type": "ADD_BUILD_TO_DRAFT", "label": "추천 조합 담기", "payload": { "items": [] } }
  ],
  "recommendations": [
    { "name": "가성비형 추천 조합", "items": [] },
    { "name": "균형형 추천 조합", "items": [] },
    { "name": "고성능형 추천 조합", "items": [] }
  ]
}
```

### 부품 추천

요청:

```json
{
  "message": "RTX 5070 중에 뭐가 좋아?",
  "surface": "SELF_QUOTE",
  "selectedCategory": "GPU"
}
```

응답 핵심:

```json
{
  "intent": "PART_RECOMMEND",
  "actions": [
    { "type": "ADD_PART_TO_DRAFT", "payload": { "partId": "...", "category": "GPU", "quantity": 1 } }
  ],
  "partRecommendations": []
}
```

## 현재 구현 수준

- `POST /api/ai/build-chat`은 기존 UI 응답 shape를 유지하되, 내부적으로 `AiChatEngine.respondLlmRequired()`를 호출한다.
- `POST /api/ai/build-chat`은 선택 필드 `currentQuoteDraft`를 받을 수 있고, 응답에는 선택 필드 `actions`가 포함될 수 있다. 이 필드는 기존 홈 챗봇 응답을 깨지 않는다.
- `respondLlmRequired()`는 RAG 근거와 LLM structured output으로 intent/조건을 판단하고, 실제 부품 선택은 내부 자산 `parts`에서만 수행한다.
- LLM은 부품 ID, 가격, FPS, 상품명을 만들지 않는다. 명시 조건은 `parsedContext.requiredGpuClasses`, `requiredPartKeywords`, `hardConstraintPolicy`에 보존한다.
- `/self-quote` 변경 요청은 LLM이 내부 `draftEdit`으로 `operation`, `category`, `priceDirection`, `targetMaxPrice`, `targetQuantity`를 구조화한다. 이 값은 공개 응답 필드가 아니라 서버가 더 싼 GPU 교체, 수량 변경, 삭제 같은 action 후보를 만드는 중간 표현이다.
- `AiChatEngine.respond()`는 내부 자산 `parts`를 읽어 action과 추천 후보를 반환하는 deterministic baseline이다.
- `AiChatEngine.analyzeQuoteRequirement()`는 기존 `/api/requirements/parse` 흐름에서 사용되며 RAG evidence와 Agent trace를 3번 테이블에 남긴다.
- Tool 고도화는 2번 구현을 그대로 사용한다. 이 엔진은 Tool 결과를 직접 계산하지 않는다.
