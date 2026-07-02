# Home Shopping Flow Audit - 2026-07-03

## Scope

- Target branch: `codex/build-chat-cache-rag-quality`
- Base checked before audit: `upstream/main` at `b117f17`
- User surfaces covered: `/`, `/self-quote`, `/parts/:id`, `/checkout`, `/checkout/complete`, login redirect, home/self-quote AI assistant actions
- Admin screens were intentionally excluded except when user shopping behavior depended on seeded/admin-managed data.

## Evidence

Screenshots are stored under:

`docs/reports/home-shopping-audit-20260703/screenshots/`

Key captures:

- `01-home-after-login-viewport.png`: logged-in home
- `02-fast-route-gpu.png`: natural-language route to GPU category
- `04-home-ai-5090-result.png`: home AI recommendation for RTX 5090 request
- `05-self-quote-after-ai-apply.png`: AI build applied to self quote with matching total
- `06-exact-cpu-detail-route.png`: exact CPU natural-language route to product detail
- `07-detail-after-add.png`: product detail add-to-quote success
- `09-checkout.png`: checkout view from current quote
- `10-checkout-complete.png`: checkout complete view
- `11-chat-cpu-better.png` to `18-chat-cooler-better.png`: category assistant matrix before fixes
- `19-recheck-cpu-better-no-downgrade.png`: CPU upgrade recheck after rank fix
- `20-recheck-gpu-cheaper-single-action.png`: GPU cheaper recheck after action limiting
- `21-fresh-self-quote-after-fixes.png`: fresh self-quote page after fixes

Raw browser evidence:

- `self-quote-chat-matrix.json`
- `recheck-chat-results.json`

Figma board attempt:

- Target file key: `bu2jnMDo64u6pebmRGgqps`
- Result: blocked by Figma edit permission: `You do not have permission to edit this file`
- Debug UUID: `7170f719-7323-4723-8d38-421d3e31afbe`

## Scenario Results

| Area | Scenario | Result |
| --- | --- | --- |
| Login | `/login` with `user@example.com` | Pass |
| Home | Logged-in home renders recommendation tabs and assistant | Pass |
| Fast route | `GPU 보여줘` | Pass. Navigates to `/self-quote?category=GPU` without LLM route ambiguity. |
| Home AI recommend | `5090 글카가 들어간 PC 추천해줘` | Pass. 3 RTX 5090 builds returned. |
| Apply AI build | Apply first AI build to quote draft | Pass. Home build total and self-quote total matched at `10,716,940원`. |
| Exact product route | `AMD 라이젠9-6세대 9950X3D 그래니트 릿지 정품(멀티팩) 상세페이지로 이동해` | Pass. Routed to exact `9950X3D` detail page, not the similarly named `9950X3D2`. |
| Product detail | Add current product to quote draft | Pass. Detail page saved to server draft. |
| Checkout | Current quote -> checkout -> complete | Pass. Checkout and complete totals matched at `10,122,330원` in the captured run. |
| Tool FAIL exclusion | Self-quote graph showed power/size warnings for an intentionally partial draft | Pass for display. Recommendations were not blindly applied from FAIL graph state. |

## Bugs Found And Fixed

### P1 - Multiple same-category draft actions auto-executed

Problem:

- For a request like `그래픽카드 더 싼데 성능 너무 떨어지지 않게`, the assistant could return multiple GPU replacement actions and the frontend attempted to execute several same-category draft changes in sequence.

Fix:

- Backend `BuildChatService` now emits only one replacement action for draft mutation flows.
- Frontend `AiBuildAssistant` deduplicates auto-executable draft actions by category before execution.

Regression coverage:

- Existing home/self-quote Playwright tests pass.
- Targeted backend tests for Build Chat continue to pass.

### P1 - "Better CPU" could downgrade X3D to non-X3D same-tier fallback

Problem:

- A current `9950X3D` draft could receive a non-X3D same-class CPU suggestion when the user asked for a better CPU.
- The ranker gave positive weight to higher TDP and allowed same-tier fallback by coarse bucket, so a worse practical gaming CPU could be considered a fallback.

Fix:

- `PartReplacementRanker` removes TDP as a positive CPU rank signal.
- CPU model/tier parsing now recognizes model numbers and `X3D` as rank signals.
- MORE_EXPENSIVE fallback now refuses lower rank candidates instead of silently falling through.

Regression coverage:

- Added `betterCpuDoesNotDowngradeX3dToNonX3dSameTierFallback`.

### P2 - ReactFlow minimap duplicate key warning

Problem:

- Repeated graph nodes such as `part-STORAGE` caused React duplicate key warnings in the ReactFlow minimap.

Fix:

- Graph node ids are normalized to unique ReactFlow ids while preserving original ids for selection.
- The auxiliary `MiniMap` was removed from the main graph because ReactFlow minimap internally reuses duplicate source ids. The main graph, controls, node click, edge click, and candidate panel remain intact.

Regression coverage:

- Docker web rebuild served the updated bundle.
- Fresh `/self-quote` browser check after rebuild: `recentErrorCount=0`, `duplicateKeyWarnings=0`, `miniMapWarnings=0`.

## Remaining Notes

- The browser audit found no blank page, fatal runtime crash, wrong checkout total, or wrong exact-product route after fixes.
- Some assistant responses for a deliberately minimal CPU+GPU-only draft produced "candidate 부족" style messages. This is acceptable for a partial draft, but future quality work can improve suggestions by asking for or inferring a full draft context.
- The fast route path is instant from intent classification, but full page readiness still includes data loading. The captured page-load user experience was acceptable but not sub-second.

## Validation Log

- `.\gradlew.bat test --tests "com.buildgraph.prototype.agent.PartReplacementRankerTest" --tests "com.buildgraph.prototype.build.BuildChatServiceTest" --no-daemon`: passed
- `npm --prefix apps/web run test -- tests/home.spec.ts tests/self-quote.spec.ts`: passed
- `python tools/validate_openapi.py docs/openapi.yaml`: passed, 85 paths
- `npm --prefix apps/web run test`: passed, 107 tests
- `npm --prefix apps/web run build`: passed
- `.\gradlew.bat test --no-daemon`: passed
- `.\gradlew.bat bootJar --no-daemon`: passed
- `docker compose up --build -d api web`: passed
- `GET http://localhost:8080/api/health`: `{"database":"UP","status":"UP"}`
- Rebuilt browser smoke: fresh `/self-quote` console errors 0
