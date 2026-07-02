# Build Chat route runtime verification - 2026-07-03

## Scope

- Base: `upstream/main` `b117f17` (`Merge pull request #39 from jungle-final-project/feat/adminPage`)
- Branch: `codex/build-chat-cache-rag-quality`
- Target area: `/api/ai/build-chat`, home/self-quote chatbot navigation, route action handling, RAG embedding operational smoke.
- UI scope: no layout redesign. This verification only checks that the existing chatbot surface can consume the new route/action behavior.

## API scenario results

Executed against Docker API at `http://localhost:8080` with `user@example.com / passw0rd!`.

| Scenario | Expected | Result | Latency |
| --- | --- | --- | ---: |
| `GPU 보여줘` | Fast `OPEN_ROUTE` to GPU category without LLM path | `OPEN_ROUTE` `/self-quote?category=GPU` | 12 ms |
| `GPU 추천해줘` | Recommendation path, not route false positive | `answerType=PART`, GPU options returned | 10 ms cached |
| `ASUS Astral 5090 상세 보여줘` | Do not fast-route to category before resolver | Routed to GPU category because ACTIVE candidates were ambiguous | 8 ms cached |
| exact `ASUS ROG MATRIX 지포스 RTX 5090 OC 32GB PLATINUM 상세 보여줘` | Single high-confidence part detail route | `OPEN_ROUTE` `/parts/a2f9d478-b368-4c16-9048-4a064828c5c1` | 4.5 s |
| `5090 글카가 들어간 PC 추천해줘` | Hard GPU constraint preserved | 3 builds, all with RTX 5090 GPU | 8 ms cached |
| `300만원 이하 RTX 5090 PC 추천해줘` | RTX 5090 preserved, over-budget warning allowed | 1 build with RTX 5090, over-budget and Tool exclusion warnings | 8 ms cached |

Quality notes:

- Fast route is intentionally narrow. Clear movement commands are handled locally/fast.
- Ambiguous product names do not auto-open a random product. `Astral 5090` had multiple ACTIVE candidates, so the route degraded to GPU category.
- Exact product names can route to `/parts/{partId}`.
- Cached repeat calls are below 20 ms; first uncached LLM fallback for ambiguous part detail was observed around 21.9 s before cache, which is acceptable for fallback but not for high-frequency navigation.

## Browser scenario results

Executed with Playwright against Docker web at `http://localhost:5173`.

| Scenario | Expected | Result | Latency |
| --- | --- | --- | ---: |
| Login -> home chat -> `GPU 보여줘` | Browser navigates to GPU self-quote category | PASS, URL became `/self-quote?category=GPU` | 119 ms |
| Login -> home chat -> `GPU 추천해줘` | Existing chat surface shows recommendation answer | PASS, answer rendered in chat panel | 3.861 s |

No browser console errors were observed in this run.

## RAG embedding operational check

Admin endpoint and database smoke were executed against Docker.

Backfill endpoint:

```json
{
  "scanned": 28,
  "updated": 0,
  "skipped": 28,
  "reusableTotal": 28,
  "embeddedTotal": 28,
  "embeddingModel": "text-embedding-3-small",
  "embeddingDimensions": 1536
}
```

Database count:

| reusable_total | reusable_embedded | session_evidence |
| ---: | ---: | ---: |
| 28 | 28 | 6 |

`/api/rag/search?q=5090&purpose=REQUIREMENT_PARSE` top results:

1. `requirement-rule-explicit-gpu-class-hard-constraint`
2. `internal-rule-requirement-parse-premium-open-budget`
3. `requirement-counterexample-explicit-gpu-with-user-budget`

Conclusion: reusable RAG chunks are fully embedded in the current Docker database, and explicit GPU class retrieval is aligned with the hard-constraint policy.

## Remaining risk

- Product detail route fallback depends on exact or single high-confidence ACTIVE part matching. Ambiguous model names intentionally route to category/search instead of selecting an arbitrary product.
- First uncached LLM fallback for route resolution can still be slow. Clear route commands remain the fast path; broader natural-language route understanding stays on the Build Chat LLM path.
