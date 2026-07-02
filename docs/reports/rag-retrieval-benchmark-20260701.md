# RAG Retrieval Benchmark

- generatedAt: 2026-07-01T21:21:18
- distinctCases: 190
- variants: 5
- totalRows: 950
- endpoint: `GET /api/rag/search`

## Summary

| variant | purpose | cases | top1HitRate | topKHitRate | avgLatencyMs | p95LatencyMs | avgResults |
|---|---|---:|---:|---:|---:|---:|---:|
| vector-on | REQUIREMENT_PARSE | 90 | 43.3% | 71.1% | 320 | 549 | 10.0 |
| vector-on | BUILD_RECOMMEND | 20 | 65.0% | 80.0% | 311 | 395 | 9.0 |
| vector-on | BUILD_EXPLAIN | 10 | 100.0% | 100.0% | 260 | 368 | 3.0 |
| vector-on | AS_ANALYZE | 50 | 68.0% | 92.0% | 281 | 417 | 6.0 |
| vector-on | PUBLIC_SEARCH | 20 | 80.0% | 90.0% | 359 | 468 | 10.0 |
| requirement-vector-off | REQUIREMENT_PARSE | 90 | 0.0% | 0.0% | 16 | 32 | 0.0 |
| requirement-vector-off | BUILD_RECOMMEND | 20 | 65.0% | 80.0% | 332 | 425 | 9.0 |
| requirement-vector-off | BUILD_EXPLAIN | 10 | 100.0% | 100.0% | 308 | 451 | 3.0 |
| requirement-vector-off | AS_ANALYZE | 50 | 68.0% | 92.0% | 295 | 408 | 6.0 |
| requirement-vector-off | PUBLIC_SEARCH | 20 | 80.0% | 90.0% | 282 | 366 | 10.0 |
| as-vector-off | REQUIREMENT_PARSE | 90 | 43.3% | 71.1% | 290 | 425 | 10.0 |
| as-vector-off | BUILD_RECOMMEND | 20 | 65.0% | 80.0% | 321 | 405 | 9.0 |
| as-vector-off | BUILD_EXPLAIN | 10 | 100.0% | 100.0% | 295 | 410 | 3.0 |
| as-vector-off | AS_ANALYZE | 50 | 2.0% | 2.0% | 14 | 29 | 0.2 |
| as-vector-off | PUBLIC_SEARCH | 20 | 80.0% | 90.0% | 278 | 310 | 10.0 |
| public-vector-off | REQUIREMENT_PARSE | 90 | 0.0% | 0.0% | 13 | 31 | 0.0 |
| public-vector-off | BUILD_RECOMMEND | 20 | 0.0% | 0.0% | 12 | 28 | 0.0 |
| public-vector-off | BUILD_EXPLAIN | 10 | 0.0% | 0.0% | 10 | 29 | 0.0 |
| public-vector-off | AS_ANALYZE | 50 | 2.0% | 2.0% | 13 | 27 | 0.2 |
| public-vector-off | PUBLIC_SEARCH | 20 | 0.0% | 0.0% | 13 | 26 | 0.2 |
| vector-off | REQUIREMENT_PARSE | 90 | 0.0% | 0.0% | 15 | 31 | 0.0 |
| vector-off | BUILD_RECOMMEND | 20 | 0.0% | 0.0% | 11 | 23 | 0.0 |
| vector-off | BUILD_EXPLAIN | 10 | 0.0% | 0.0% | 6 | 15 | 0.0 |
| vector-off | AS_ANALYZE | 50 | 2.0% | 2.0% | 13 | 27 | 0.2 |
| vector-off | PUBLIC_SEARCH | 20 | 0.0% | 0.0% | 13 | 31 | 0.2 |

## Cases

| variant | purpose | case | top1 | topK | latencyMs | k | count | modes | topSources | error |
|---|---|---|---:|---:|---:|---:|---:|---|---|---|
| vector-on | REQUIREMENT_PARSE | req-001 | no | yes | 736 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-002 | yes | yes | 281 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-example-noise-upgrade-brand, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-003 | no | no | 314 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-004 | yes | yes | 294 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-005 | yes | yes | 289 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-006 | no | yes | 399 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-007 | yes | yes | 281 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-008 | no | yes | 284 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-009 | no | no | 138 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-010 | no | no | 276 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-premium-open-budget, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-011 | no | no | 302 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-012 | no | yes | 290 | 3 | 10 | VECTOR | requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-013 | yes | yes | 256 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-014 | no | no | 306 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-015 | no | yes | 290 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-016 | yes | yes | 254 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-017 | yes | yes | 298 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-018 | yes | yes | 256 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-019 | yes | yes | 263 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-020 | yes | yes | 231 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-021 | yes | yes | 291 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-022 | yes | yes | 295 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-023 | yes | yes | 328 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-024 | no | yes | 259 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-025 | yes | yes | 240 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-026 | yes | yes | 300 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-027 | yes | yes | 609 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-028 | yes | yes | 357 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-029 | no | yes | 443 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-030 | no | yes | 270 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-031 | no | yes | 278 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-032 | yes | yes | 264 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, internal-rule-requirement-parse-premium-open-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-033 | yes | yes | 310 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-noise-upgrade-brand, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-034 | yes | yes | 258 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-035 | no | yes | 253 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, internal-rule-requirement-parse-premium-open-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-036 | yes | yes | 255 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-noise-upgrade |  |
| vector-on | REQUIREMENT_PARSE | req-037 | no | no | 531 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-038 | yes | yes | 279 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-039 | yes | yes | 258 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-040 | yes | yes | 264 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-041 | yes | yes | 261 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-042 | no | yes | 253 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-noise-upgrade-brand, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-043 | no | no | 267 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-044 | no | no | 253 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-045 | no | no | 343 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-noise-upgrade-brand, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-046 | no | no | 273 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-047 | no | yes | 267 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-048 | no | yes | 264 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-049 | no | no | 380 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-050 | no | no | 304 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, guide-requirement-parse-budget-resolution-workload, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-051 | no | yes | 549 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-052 | no | no | 330 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-053 | no | yes | 278 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-example-workload-mixed-creator-ai, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-054 | no | yes | 327 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-noise-upgrade |  |
| vector-on | REQUIREMENT_PARSE | req-055 | yes | yes | 261 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-rule-explicit-gpu-class-hard-constraint, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-056 | yes | yes | 302 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-057 | no | no | 296 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-058 | yes | yes | 295 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-059 | yes | yes | 296 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-060 | yes | yes | 246 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-061 | no | no | 292 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-062 | no | yes | 288 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-063 | yes | yes | 493 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-064 | no | yes | 296 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-065 | no | no | 241 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-066 | no | yes | 372 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-067 | yes | yes | 301 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-068 | no | yes | 414 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-noise-upgrade-brand, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-069 | no | no | 381 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-070 | no | no | 324 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-071 | no | no | 325 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-072 | no | yes | 415 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-073 | yes | yes | 301 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-074 | yes | yes | 248 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-075 | yes | yes | 280 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-076 | no | yes | 299 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-077 | no | no | 299 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-078 | no | no | 322 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-079 | no | yes | 355 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-080 | yes | yes | 284 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-081 | yes | yes | 577 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-082 | no | yes | 1036 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-083 | no | no | 289 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-084 | yes | yes | 281 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-085 | yes | yes | 308 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-086 | no | no | 294 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-087 | no | no | 355 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-example-gaming-resolution-refresh, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-088 | no | yes | 139 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-089 | no | no | 142 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget, internal-rule-requirement-parse-noise-upgrade |  |
| vector-on | REQUIREMENT_PARSE | req-090 | no | no | 411 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-gaming-resolution-refresh |  |
| vector-on | BUILD_RECOMMEND | build-001 | yes | yes | 269 | 3 | 9 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-002 | yes | yes | 305 | 3 | 9 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-003 | yes | yes | 311 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-004 | yes | yes | 366 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, part-spec-rtx-5090-class, internal-rule-psu-atx31-power-margin |  |
| vector-on | BUILD_RECOMMEND | build-005 | yes | yes | 314 | 3 | 9 | VECTOR | internal-rule-psu-atx31-power-margin, part-catalog-rtx50-tool-ready-dimensions, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-006 | no | yes | 379 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-007 | yes | yes | 395 | 3 | 9 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-008 | yes | yes | 345 | 3 | 9 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, build-rule-hard-gpu-class-selection, internal-rule-build-qhd-gaming-gpu-priority |  |
| vector-on | BUILD_RECOMMEND | build-009 | yes | yes | 259 | 3 | 9 | VECTOR | build-rule-memory-storage-workload-floor, part-catalog-rtx50-tool-ready-dimensions, build-rule-cpu-gpu-balance-and-bottleneck |  |
| vector-on | BUILD_RECOMMEND | build-010 | yes | yes | 247 | 3 | 9 | VECTOR | build-rule-memory-storage-workload-floor, internal-rule-build-qhd-gaming-gpu-priority, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-011 | no | no | 393 | 3 | 9 | VECTOR | part-spec-rtx-5090-class, part-catalog-rtx50-tool-ready-dimensions, build-rule-saved-price-and-psu-headroom |  |
| vector-on | BUILD_RECOMMEND | build-012 | no | no | 300 | 3 | 9 | VECTOR | part-spec-rtx-5090-class, part-catalog-rtx50-tool-ready-dimensions, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-013 | no | no | 311 | 3 | 9 | VECTOR | build-rule-hard-gpu-class-selection, build-rule-saved-price-and-psu-headroom, build-rule-cpu-gpu-balance-and-bottleneck |  |
| vector-on | BUILD_RECOMMEND | build-014 | no | no | 147 | 3 | 9 | VECTOR | build-rule-saved-price-and-psu-headroom, part-catalog-rtx50-tool-ready-dimensions, build-rule-memory-storage-workload-floor |  |
| vector-on | BUILD_RECOMMEND | build-015 | yes | yes | 267 | 3 | 9 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, build-rule-saved-price-and-psu-headroom, part-catalog-rtx50-tool-ready-dimensions |  |
| vector-on | BUILD_RECOMMEND | build-016 | yes | yes | 347 | 3 | 9 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-017 | yes | yes | 265 | 3 | 9 | VECTOR | build-rule-memory-storage-workload-floor, internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck |  |
| vector-on | BUILD_RECOMMEND | build-018 | no | yes | 301 | 3 | 9 | VECTOR | internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-019 | no | yes | 292 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, part-spec-rtx-5090-class, build-rule-airflow-cooler-case-fit |  |
| vector-on | BUILD_RECOMMEND | build-020 | yes | yes | 408 | 3 | 9 | VECTOR | build-rule-saved-price-and-psu-headroom, build-rule-hard-gpu-class-selection, part-spec-rtx-5090-class |  |
| vector-on | BUILD_EXPLAIN | explain-001 | yes | yes | 233 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-002 | yes | yes | 289 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first |  |
| vector-on | BUILD_EXPLAIN | explain-003 | yes | yes | 297 | 3 | 3 | VECTOR | benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance |  |
| vector-on | BUILD_EXPLAIN | explain-004 | yes | yes | 285 | 3 | 3 | VECTOR | benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance |  |
| vector-on | BUILD_EXPLAIN | explain-005 | yes | yes | 142 | 3 | 3 | VECTOR | price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor, internal-rule-case-gpu-clearance |  |
| vector-on | BUILD_EXPLAIN | explain-006 | yes | yes | 368 | 3 | 3 | VECTOR | price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-007 | yes | yes | 261 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-008 | yes | yes | 146 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first |  |
| vector-on | BUILD_EXPLAIN | explain-009 | yes | yes | 300 | 3 | 3 | VECTOR | benchmark-guide-ram-video-dev-floor, internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first |  |
| vector-on | BUILD_EXPLAIN | explain-010 | yes | yes | 281 | 3 | 3 | VECTOR | price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor |  |
| vector-on | AS_ANALYZE | as-001 | yes | yes | 285 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu |  |
| vector-on | AS_ANALYZE | as-002 | yes | yes | 147 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-003 | yes | yes | 290 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-004 | yes | yes | 131 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-005 | yes | yes | 128 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-006 | no | yes | 267 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-007 | no | no | 286 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-power-instability, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-008 | yes | yes | 278 | 3 | 6 | VECTOR | support-guide-airflow-upgrade-before-gpu, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-009 | no | yes | 286 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-010 | yes | yes | 252 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-011 | yes | yes | 232 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-012 | yes | yes | 267 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-013 | yes | yes | 486 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-014 | yes | yes | 158 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-015 | yes | yes | 417 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-016 | yes | yes | 347 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-017 | yes | yes | 224 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-018 | yes | yes | 301 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-019 | yes | yes | 312 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-020 | yes | yes | 262 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-021 | yes | yes | 307 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-022 | yes | yes | 393 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-023 | yes | yes | 251 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-024 | no | yes | 326 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-025 | no | yes | 261 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-026 | no | yes | 386 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-027 | yes | yes | 149 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-028 | yes | yes | 248 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-029 | yes | yes | 310 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-power-instability, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-030 | no | no | 244 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-031 | no | no | 246 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-032 | no | yes | 257 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-033 | no | yes | 454 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-memory-storage-pressure, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-034 | no | yes | 289 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-035 | yes | yes | 273 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-036 | no | yes | 345 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-037 | yes | yes | 281 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-038 | yes | yes | 275 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-039 | yes | yes | 245 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-040 | yes | yes | 280 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-041 | yes | yes | 402 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-042 | yes | yes | 300 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-043 | no | no | 257 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-044 | yes | yes | 293 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-045 | yes | yes | 121 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-046 | no | yes | 262 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-047 | yes | yes | 317 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-048 | no | yes | 273 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-049 | no | yes | 388 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-050 | yes | yes | 283 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | PUBLIC_SEARCH | public-001 | yes | yes | 319 | 3 | 10 | VECTOR | part-spec-rtx-5090-class, build-rule-hard-gpu-class-selection, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | PUBLIC_SEARCH | public-002 | yes | yes | 234 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | PUBLIC_SEARCH | public-003 | yes | yes | 318 | 3 | 10 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, support-guide-airflow-upgrade-before-gpu, build-rule-hard-gpu-class-selection |  |
| vector-on | PUBLIC_SEARCH | public-004 | yes | yes | 302 | 3 | 10 | VECTOR | internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom, internal-rule-case-gpu-clearance |  |
| vector-on | PUBLIC_SEARCH | public-005 | yes | yes | 299 | 3 | 10 | VECTOR | internal-rule-case-gpu-clearance, part-catalog-rtx50-tool-ready-dimensions, build-rule-hard-gpu-class-selection |  |
| vector-on | PUBLIC_SEARCH | public-006 | yes | yes | 286 | 3 | 10 | VECTOR | benchmark-guide-ram-video-dev-floor, build-rule-memory-storage-workload-floor, as-guide-memory-storage-pressure |  |
| vector-on | PUBLIC_SEARCH | public-007 | yes | yes | 136 | 3 | 10 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu |  |
| vector-on | PUBLIC_SEARCH | public-008 | yes | yes | 310 | 3 | 10 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure |  |
| vector-on | PUBLIC_SEARCH | public-009 | yes | yes | 446 | 3 | 10 | VECTOR | as-guide-memory-storage-pressure, benchmark-guide-ram-video-dev-floor, build-rule-memory-storage-workload-floor |  |
| vector-on | PUBLIC_SEARCH | public-010 | yes | yes | 289 | 3 | 10 | VECTOR | as-guide-power-instability, internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom |  |
| vector-on | PUBLIC_SEARCH | public-011 | no | no | 279 | 3 | 10 | VECTOR | build-rule-hard-gpu-class-selection, internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck |  |
| vector-on | PUBLIC_SEARCH | public-012 | no | yes | 284 | 3 | 10 | VECTOR | build-rule-memory-storage-workload-floor, benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai |  |
| vector-on | PUBLIC_SEARCH | public-013 | no | yes | 468 | 3 | 10 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, requirement-example-gaming-resolution-refresh, support-guide-gpu-thermal-frame-drop |  |
| vector-on | PUBLIC_SEARCH | public-014 | yes | yes | 122 | 3 | 10 | VECTOR | price-guide-saved-snapshot-first, build-rule-saved-price-and-psu-headroom, as-guide-memory-storage-pressure |  |
| vector-on | PUBLIC_SEARCH | public-015 | yes | yes | 1568 | 3 | 10 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin, part-spec-rtx-5090-class |  |
| vector-on | PUBLIC_SEARCH | public-016 | yes | yes | 421 | 3 | 10 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, support-guide-airflow-upgrade-before-gpu |  |
| vector-on | PUBLIC_SEARCH | public-017 | yes | yes | 324 | 3 | 10 | VECTOR | support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop, as-guide-gpu-thermal-frame-drop |  |
| vector-on | PUBLIC_SEARCH | public-018 | no | no | 246 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | PUBLIC_SEARCH | public-019 | yes | yes | 268 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, support-guide-airflow-upgrade-before-gpu |  |
| vector-on | PUBLIC_SEARCH | public-020 | yes | yes | 253 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai, internal-rule-build-qhd-gaming-gpu-priority |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-001 | no | no | 38 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-002 | no | no | 32 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-003 | no | no | 32 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-004 | no | no | 31 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-005 | no | no | 7 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-006 | no | no | 21 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-007 | no | no | 17 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-008 | no | no | 7 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-009 | no | no | 24 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-010 | no | no | 32 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-011 | no | no | 30 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-012 | no | no | 7 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-013 | no | no | 6 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-014 | no | no | 18 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-015 | no | no | 32 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-016 | no | no | 31 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-017 | no | no | 6 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-018 | no | no | 24 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-019 | no | no | 16 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-020 | no | no | 15 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-021 | no | no | 14 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-022 | no | no | 16 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-023 | no | no | 15 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-024 | no | no | 17 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-025 | no | no | 4 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-026 | no | no | 27 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-027 | no | no | 5 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-028 | no | no | 6 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-029 | no | no | 21 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-030 | no | no | 32 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-031 | no | no | 31 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-032 | no | no | 7 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-033 | no | no | 23 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-034 | no | no | 6 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-035 | no | no | 6 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-036 | no | no | 19 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-037 | no | no | 30 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-038 | no | no | 15 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-039 | no | no | 15 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-040 | no | no | 5 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-041 | no | no | 28 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-042 | no | no | 5 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-043 | no | no | 5 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-044 | no | no | 20 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-045 | no | no | 5 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-046 | no | no | 27 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-047 | no | no | 15 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-048 | no | no | 5 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-049 | no | no | 27 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-050 | no | no | 16 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-051 | no | no | 15 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-052 | no | no | 16 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-053 | no | no | 5 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-054 | no | no | 26 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-055 | no | no | 15 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-056 | no | no | 15 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-057 | no | no | 4 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-058 | no | no | 4 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-059 | no | no | 22 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-060 | no | no | 16 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-061 | no | no | 17 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-062 | no | no | 19 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-063 | no | no | 29 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-064 | no | no | 6 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-065 | no | no | 4 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-066 | no | no | 4 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-067 | no | no | 4 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-068 | no | no | 27 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-069 | no | no | 15 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-070 | no | no | 16 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-071 | no | no | 21 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-072 | no | no | 26 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-073 | no | no | 16 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-074 | no | no | 15 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-075 | no | no | 16 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-076 | no | no | 15 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-077 | no | no | 16 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-078 | no | no | 16 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-079 | no | no | 5 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-080 | no | no | 4 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-081 | no | no | 4 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-082 | no | no | 5 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-083 | no | no | 4 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-084 | no | no | 6 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-085 | no | no | 7 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-086 | no | no | 28 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-087 | no | no | 14 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-088 | no | no | 15 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-089 | no | no | 16 | 3 | 0 | - | - |  |
| requirement-vector-off | REQUIREMENT_PARSE | req-090 | no | no | 4 | 3 | 0 | - | - |  |
| requirement-vector-off | BUILD_RECOMMEND | build-001 | yes | yes | 743 | 3 | 9 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck, build-rule-hard-gpu-class-selection |  |
| requirement-vector-off | BUILD_RECOMMEND | build-002 | yes | yes | 266 | 3 | 9 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck, build-rule-hard-gpu-class-selection |  |
| requirement-vector-off | BUILD_RECOMMEND | build-003 | yes | yes | 271 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin, part-spec-rtx-5090-class |  |
| requirement-vector-off | BUILD_RECOMMEND | build-004 | yes | yes | 267 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, part-spec-rtx-5090-class, internal-rule-psu-atx31-power-margin |  |
| requirement-vector-off | BUILD_RECOMMEND | build-005 | yes | yes | 313 | 3 | 9 | VECTOR | internal-rule-psu-atx31-power-margin, part-catalog-rtx50-tool-ready-dimensions, part-spec-rtx-5090-class |  |
| requirement-vector-off | BUILD_RECOMMEND | build-006 | no | yes | 394 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin, part-spec-rtx-5090-class |  |
| requirement-vector-off | BUILD_RECOMMEND | build-007 | yes | yes | 245 | 3 | 9 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, build-rule-hard-gpu-class-selection |  |
| requirement-vector-off | BUILD_RECOMMEND | build-008 | yes | yes | 269 | 3 | 9 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, build-rule-hard-gpu-class-selection, internal-rule-build-qhd-gaming-gpu-priority |  |
| requirement-vector-off | BUILD_RECOMMEND | build-009 | yes | yes | 327 | 3 | 9 | VECTOR | build-rule-memory-storage-workload-floor, part-catalog-rtx50-tool-ready-dimensions, build-rule-cpu-gpu-balance-and-bottleneck |  |
| requirement-vector-off | BUILD_RECOMMEND | build-010 | yes | yes | 294 | 3 | 9 | VECTOR | build-rule-memory-storage-workload-floor, internal-rule-build-qhd-gaming-gpu-priority, part-spec-rtx-5090-class |  |
| requirement-vector-off | BUILD_RECOMMEND | build-011 | no | no | 400 | 3 | 9 | VECTOR | part-spec-rtx-5090-class, part-catalog-rtx50-tool-ready-dimensions, build-rule-saved-price-and-psu-headroom |  |
| requirement-vector-off | BUILD_RECOMMEND | build-012 | no | no | 270 | 3 | 9 | VECTOR | part-spec-rtx-5090-class, part-catalog-rtx50-tool-ready-dimensions, build-rule-hard-gpu-class-selection |  |
| requirement-vector-off | BUILD_RECOMMEND | build-013 | no | no | 425 | 3 | 9 | VECTOR | build-rule-hard-gpu-class-selection, build-rule-saved-price-and-psu-headroom, build-rule-cpu-gpu-balance-and-bottleneck |  |
| requirement-vector-off | BUILD_RECOMMEND | build-014 | no | no | 294 | 3 | 9 | VECTOR | build-rule-saved-price-and-psu-headroom, part-catalog-rtx50-tool-ready-dimensions, build-rule-memory-storage-workload-floor |  |
| requirement-vector-off | BUILD_RECOMMEND | build-015 | yes | yes | 321 | 3 | 9 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, build-rule-saved-price-and-psu-headroom, part-catalog-rtx50-tool-ready-dimensions |  |
| requirement-vector-off | BUILD_RECOMMEND | build-016 | yes | yes | 324 | 3 | 9 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, build-rule-hard-gpu-class-selection |  |
| requirement-vector-off | BUILD_RECOMMEND | build-017 | yes | yes | 245 | 3 | 9 | VECTOR | build-rule-memory-storage-workload-floor, internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck |  |
| requirement-vector-off | BUILD_RECOMMEND | build-018 | no | yes | 273 | 3 | 9 | VECTOR | internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom, part-spec-rtx-5090-class |  |
| requirement-vector-off | BUILD_RECOMMEND | build-019 | no | yes | 416 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, part-spec-rtx-5090-class, build-rule-airflow-cooler-case-fit |  |
| requirement-vector-off | BUILD_RECOMMEND | build-020 | yes | yes | 278 | 3 | 9 | VECTOR | build-rule-saved-price-and-psu-headroom, build-rule-hard-gpu-class-selection, part-spec-rtx-5090-class |  |
| requirement-vector-off | BUILD_EXPLAIN | explain-001 | yes | yes | 230 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| requirement-vector-off | BUILD_EXPLAIN | explain-002 | yes | yes | 282 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first |  |
| requirement-vector-off | BUILD_EXPLAIN | explain-003 | yes | yes | 255 | 3 | 3 | VECTOR | benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance |  |
| requirement-vector-off | BUILD_EXPLAIN | explain-004 | yes | yes | 296 | 3 | 3 | VECTOR | benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance |  |
| requirement-vector-off | BUILD_EXPLAIN | explain-005 | yes | yes | 451 | 3 | 3 | VECTOR | price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor, internal-rule-case-gpu-clearance |  |
| requirement-vector-off | BUILD_EXPLAIN | explain-006 | yes | yes | 308 | 3 | 3 | VECTOR | price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor |  |
| requirement-vector-off | BUILD_EXPLAIN | explain-007 | yes | yes | 289 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| requirement-vector-off | BUILD_EXPLAIN | explain-008 | yes | yes | 413 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first |  |
| requirement-vector-off | BUILD_EXPLAIN | explain-009 | yes | yes | 279 | 3 | 3 | VECTOR | benchmark-guide-ram-video-dev-floor, internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first |  |
| requirement-vector-off | BUILD_EXPLAIN | explain-010 | yes | yes | 277 | 3 | 3 | VECTOR | price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor |  |
| requirement-vector-off | AS_ANALYZE | as-001 | yes | yes | 147 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu |  |
| requirement-vector-off | AS_ANALYZE | as-002 | yes | yes | 413 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop |  |
| requirement-vector-off | AS_ANALYZE | as-003 | yes | yes | 307 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-004 | yes | yes | 308 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-005 | yes | yes | 354 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-006 | no | yes | 292 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop |  |
| requirement-vector-off | AS_ANALYZE | as-007 | no | no | 305 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-power-instability, support-guide-gpu-thermal-frame-drop |  |
| requirement-vector-off | AS_ANALYZE | as-008 | yes | yes | 284 | 3 | 6 | VECTOR | support-guide-airflow-upgrade-before-gpu, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| requirement-vector-off | AS_ANALYZE | as-009 | no | yes | 408 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| requirement-vector-off | AS_ANALYZE | as-010 | yes | yes | 256 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-011 | yes | yes | 251 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-012 | yes | yes | 260 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-013 | yes | yes | 297 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-014 | yes | yes | 306 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop |  |
| requirement-vector-off | AS_ANALYZE | as-015 | yes | yes | 295 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| requirement-vector-off | AS_ANALYZE | as-016 | yes | yes | 243 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop |  |
| requirement-vector-off | AS_ANALYZE | as-017 | yes | yes | 259 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| requirement-vector-off | AS_ANALYZE | as-018 | yes | yes | 262 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-019 | yes | yes | 307 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-020 | yes | yes | 299 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-021 | yes | yes | 285 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| requirement-vector-off | AS_ANALYZE | as-022 | yes | yes | 294 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure |  |
| requirement-vector-off | AS_ANALYZE | as-023 | yes | yes | 403 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| requirement-vector-off | AS_ANALYZE | as-024 | no | yes | 259 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| requirement-vector-off | AS_ANALYZE | as-025 | no | yes | 309 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| requirement-vector-off | AS_ANALYZE | as-026 | no | yes | 266 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| requirement-vector-off | AS_ANALYZE | as-027 | yes | yes | 392 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-driver-crash-event-log |  |
| requirement-vector-off | AS_ANALYZE | as-028 | yes | yes | 233 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-029 | yes | yes | 281 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-power-instability, as-guide-memory-storage-pressure |  |
| requirement-vector-off | AS_ANALYZE | as-030 | no | no | 286 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, support-guide-gpu-thermal-frame-drop |  |
| requirement-vector-off | AS_ANALYZE | as-031 | no | no | 246 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| requirement-vector-off | AS_ANALYZE | as-032 | no | yes | 412 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-033 | no | yes | 151 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-memory-storage-pressure, as-guide-driver-crash-event-log |  |
| requirement-vector-off | AS_ANALYZE | as-034 | no | yes | 324 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| requirement-vector-off | AS_ANALYZE | as-035 | yes | yes | 247 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| requirement-vector-off | AS_ANALYZE | as-036 | no | yes | 307 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-037 | yes | yes | 271 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| requirement-vector-off | AS_ANALYZE | as-038 | yes | yes | 306 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-039 | yes | yes | 324 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop |  |
| requirement-vector-off | AS_ANALYZE | as-040 | yes | yes | 298 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| requirement-vector-off | AS_ANALYZE | as-041 | yes | yes | 277 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-042 | yes | yes | 294 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| requirement-vector-off | AS_ANALYZE | as-043 | no | no | 275 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| requirement-vector-off | AS_ANALYZE | as-044 | yes | yes | 320 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-045 | yes | yes | 238 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| requirement-vector-off | AS_ANALYZE | as-046 | no | yes | 294 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure |  |
| requirement-vector-off | AS_ANALYZE | as-047 | yes | yes | 387 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| requirement-vector-off | AS_ANALYZE | as-048 | no | yes | 287 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| requirement-vector-off | AS_ANALYZE | as-049 | no | yes | 408 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| requirement-vector-off | AS_ANALYZE | as-050 | yes | yes | 228 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| requirement-vector-off | PUBLIC_SEARCH | public-001 | yes | yes | 123 | 3 | 10 | VECTOR | part-spec-rtx-5090-class, build-rule-hard-gpu-class-selection, requirement-rule-explicit-gpu-class-hard-constraint |  |
| requirement-vector-off | PUBLIC_SEARCH | public-002 | yes | yes | 267 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| requirement-vector-off | PUBLIC_SEARCH | public-003 | yes | yes | 307 | 3 | 10 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, support-guide-airflow-upgrade-before-gpu, build-rule-hard-gpu-class-selection |  |
| requirement-vector-off | PUBLIC_SEARCH | public-004 | yes | yes | 284 | 3 | 10 | VECTOR | internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom, internal-rule-case-gpu-clearance |  |
| requirement-vector-off | PUBLIC_SEARCH | public-005 | yes | yes | 366 | 3 | 10 | VECTOR | internal-rule-case-gpu-clearance, part-catalog-rtx50-tool-ready-dimensions, build-rule-hard-gpu-class-selection |  |
| requirement-vector-off | PUBLIC_SEARCH | public-006 | yes | yes | 309 | 3 | 10 | VECTOR | benchmark-guide-ram-video-dev-floor, build-rule-memory-storage-workload-floor, as-guide-memory-storage-pressure |  |
| requirement-vector-off | PUBLIC_SEARCH | public-007 | yes | yes | 275 | 3 | 10 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu |  |
| requirement-vector-off | PUBLIC_SEARCH | public-008 | yes | yes | 127 | 3 | 10 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure |  |
| requirement-vector-off | PUBLIC_SEARCH | public-009 | yes | yes | 264 | 3 | 10 | VECTOR | as-guide-memory-storage-pressure, benchmark-guide-ram-video-dev-floor, build-rule-memory-storage-workload-floor |  |
| requirement-vector-off | PUBLIC_SEARCH | public-010 | yes | yes | 242 | 3 | 10 | VECTOR | as-guide-power-instability, internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom |  |
| requirement-vector-off | PUBLIC_SEARCH | public-011 | no | no | 300 | 3 | 10 | VECTOR | build-rule-hard-gpu-class-selection, internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck |  |
| requirement-vector-off | PUBLIC_SEARCH | public-012 | no | yes | 397 | 3 | 10 | VECTOR | build-rule-memory-storage-workload-floor, benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai |  |
| requirement-vector-off | PUBLIC_SEARCH | public-013 | no | yes | 360 | 3 | 10 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, requirement-example-gaming-resolution-refresh, support-guide-gpu-thermal-frame-drop |  |
| requirement-vector-off | PUBLIC_SEARCH | public-014 | yes | yes | 296 | 3 | 10 | VECTOR | price-guide-saved-snapshot-first, build-rule-saved-price-and-psu-headroom, as-guide-memory-storage-pressure |  |
| requirement-vector-off | PUBLIC_SEARCH | public-015 | yes | yes | 299 | 3 | 10 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin, part-spec-rtx-5090-class |  |
| requirement-vector-off | PUBLIC_SEARCH | public-016 | yes | yes | 241 | 3 | 10 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, support-guide-airflow-upgrade-before-gpu |  |
| requirement-vector-off | PUBLIC_SEARCH | public-017 | yes | yes | 303 | 3 | 10 | VECTOR | support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop, as-guide-gpu-thermal-frame-drop |  |
| requirement-vector-off | PUBLIC_SEARCH | public-018 | no | no | 305 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| requirement-vector-off | PUBLIC_SEARCH | public-019 | yes | yes | 306 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, support-guide-airflow-upgrade-before-gpu |  |
| requirement-vector-off | PUBLIC_SEARCH | public-020 | yes | yes | 269 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai, internal-rule-build-qhd-gaming-gpu-priority |  |
| as-vector-off | REQUIREMENT_PARSE | req-001 | no | yes | 557 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-002 | yes | yes | 296 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-example-noise-upgrade-brand, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-003 | no | no | 284 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-rule-explicit-gpu-class-hard-constraint |  |
| as-vector-off | REQUIREMENT_PARSE | req-004 | yes | yes | 355 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| as-vector-off | REQUIREMENT_PARSE | req-005 | yes | yes | 280 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| as-vector-off | REQUIREMENT_PARSE | req-006 | no | yes | 255 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| as-vector-off | REQUIREMENT_PARSE | req-007 | yes | yes | 308 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-008 | no | yes | 153 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-009 | no | no | 263 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| as-vector-off | REQUIREMENT_PARSE | req-010 | no | no | 149 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-premium-open-budget, benchmark-requirement-parse-gaming-development |  |
| as-vector-off | REQUIREMENT_PARSE | req-011 | no | no | 244 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| as-vector-off | REQUIREMENT_PARSE | req-012 | no | yes | 306 | 3 | 10 | VECTOR | requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-premium-open-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-013 | yes | yes | 294 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, guide-requirement-parse-budget-resolution-workload |  |
| as-vector-off | REQUIREMENT_PARSE | req-014 | no | no | 398 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint, internal-rule-requirement-parse-premium-open-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-015 | no | yes | 261 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-premium-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-016 | yes | yes | 260 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-017 | yes | yes | 314 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-018 | yes | yes | 228 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, benchmark-requirement-parse-gaming-development |  |
| as-vector-off | REQUIREMENT_PARSE | req-019 | yes | yes | 276 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| as-vector-off | REQUIREMENT_PARSE | req-020 | yes | yes | 243 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, benchmark-requirement-parse-gaming-development |  |
| as-vector-off | REQUIREMENT_PARSE | req-021 | yes | yes | 255 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, requirement-counterexample-premium-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-022 | yes | yes | 143 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-023 | yes | yes | 282 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-024 | no | yes | 274 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-025 | yes | yes | 317 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-rule-explicit-gpu-class-hard-constraint |  |
| as-vector-off | REQUIREMENT_PARSE | req-026 | yes | yes | 271 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, requirement-example-noise-upgrade-brand |  |
| as-vector-off | REQUIREMENT_PARSE | req-027 | yes | yes | 277 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| as-vector-off | REQUIREMENT_PARSE | req-028 | yes | yes | 223 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| as-vector-off | REQUIREMENT_PARSE | req-029 | no | yes | 240 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh |  |
| as-vector-off | REQUIREMENT_PARSE | req-030 | no | yes | 299 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai |  |
| as-vector-off | REQUIREMENT_PARSE | req-031 | no | yes | 294 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai |  |
| as-vector-off | REQUIREMENT_PARSE | req-032 | yes | yes | 399 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, internal-rule-requirement-parse-premium-open-budget, requirement-example-noise-upgrade-brand |  |
| as-vector-off | REQUIREMENT_PARSE | req-033 | yes | yes | 248 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-noise-upgrade-brand, benchmark-requirement-parse-gaming-development |  |
| as-vector-off | REQUIREMENT_PARSE | req-034 | yes | yes | 296 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-035 | no | yes | 288 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, internal-rule-requirement-parse-premium-open-budget, requirement-example-noise-upgrade-brand |  |
| as-vector-off | REQUIREMENT_PARSE | req-036 | yes | yes | 281 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-noise-upgrade |  |
| as-vector-off | REQUIREMENT_PARSE | req-037 | no | no | 309 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-038 | yes | yes | 267 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-gaming-resolution-refresh |  |
| as-vector-off | REQUIREMENT_PARSE | req-039 | yes | yes | 315 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development |  |
| as-vector-off | REQUIREMENT_PARSE | req-040 | yes | yes | 305 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-041 | yes | yes | 122 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-gaming-resolution-refresh |  |
| as-vector-off | REQUIREMENT_PARSE | req-042 | no | yes | 288 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-noise-upgrade-brand, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-043 | no | no | 299 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai |  |
| as-vector-off | REQUIREMENT_PARSE | req-044 | no | no | 338 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-045 | no | no | 306 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-noise-upgrade-brand, requirement-counterexample-premium-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-046 | no | no | 329 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget, requirement-example-gaming-resolution-refresh |  |
| as-vector-off | REQUIREMENT_PARSE | req-047 | no | yes | 270 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-048 | no | yes | 153 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-premium-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-049 | no | no | 242 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-example-noise-upgrade-brand |  |
| as-vector-off | REQUIREMENT_PARSE | req-050 | no | no | 270 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, guide-requirement-parse-budget-resolution-workload, requirement-example-noise-upgrade-brand |  |
| as-vector-off | REQUIREMENT_PARSE | req-051 | no | yes | 378 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development |  |
| as-vector-off | REQUIREMENT_PARSE | req-052 | no | no | 150 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint, requirement-example-gaming-resolution-refresh |  |
| as-vector-off | REQUIREMENT_PARSE | req-053 | no | yes | 304 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-example-workload-mixed-creator-ai, requirement-example-noise-upgrade-brand |  |
| as-vector-off | REQUIREMENT_PARSE | req-054 | no | yes | 261 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-noise-upgrade |  |
| as-vector-off | REQUIREMENT_PARSE | req-055 | yes | yes | 258 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-rule-explicit-gpu-class-hard-constraint, requirement-example-workload-mixed-creator-ai |  |
| as-vector-off | REQUIREMENT_PARSE | req-056 | yes | yes | 322 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-057 | no | no | 329 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-058 | yes | yes | 308 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh |  |
| as-vector-off | REQUIREMENT_PARSE | req-059 | yes | yes | 265 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| as-vector-off | REQUIREMENT_PARSE | req-060 | yes | yes | 288 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-061 | no | no | 232 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint, internal-rule-requirement-parse-premium-open-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-062 | no | yes | 237 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint, internal-rule-requirement-parse-premium-open-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-063 | yes | yes | 291 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| as-vector-off | REQUIREMENT_PARSE | req-064 | no | yes | 396 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-counterexample-premium-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-065 | no | no | 300 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-premium-open-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-066 | no | yes | 295 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-067 | yes | yes | 407 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-068 | no | yes | 376 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-noise-upgrade-brand, benchmark-requirement-parse-gaming-development |  |
| as-vector-off | REQUIREMENT_PARSE | req-069 | no | no | 258 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| as-vector-off | REQUIREMENT_PARSE | req-070 | no | no | 425 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-071 | no | no | 244 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, requirement-example-gaming-resolution-refresh |  |
| as-vector-off | REQUIREMENT_PARSE | req-072 | no | yes | 290 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, internal-rule-requirement-parse-premium-open-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-073 | yes | yes | 258 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh |  |
| as-vector-off | REQUIREMENT_PARSE | req-074 | yes | yes | 281 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai |  |
| as-vector-off | REQUIREMENT_PARSE | req-075 | yes | yes | 274 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, requirement-example-gaming-resolution-refresh |  |
| as-vector-off | REQUIREMENT_PARSE | req-076 | no | yes | 426 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, internal-rule-requirement-parse-premium-open-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-077 | no | no | 318 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| as-vector-off | REQUIREMENT_PARSE | req-078 | no | no | 382 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-079 | no | yes | 299 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-080 | yes | yes | 449 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| as-vector-off | REQUIREMENT_PARSE | req-081 | yes | yes | 152 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-premium-open-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-082 | no | yes | 247 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-083 | no | no | 247 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-084 | yes | yes | 290 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-085 | yes | yes | 401 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| as-vector-off | REQUIREMENT_PARSE | req-086 | no | no | 256 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | REQUIREMENT_PARSE | req-087 | no | no | 284 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-example-gaming-resolution-refresh, requirement-rule-explicit-gpu-class-hard-constraint |  |
| as-vector-off | REQUIREMENT_PARSE | req-088 | no | yes | 438 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| as-vector-off | REQUIREMENT_PARSE | req-089 | no | no | 239 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget, internal-rule-requirement-parse-noise-upgrade |  |
| as-vector-off | REQUIREMENT_PARSE | req-090 | no | no | 314 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-gaming-resolution-refresh |  |
| as-vector-off | BUILD_RECOMMEND | build-001 | yes | yes | 321 | 3 | 9 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck, build-rule-hard-gpu-class-selection |  |
| as-vector-off | BUILD_RECOMMEND | build-002 | yes | yes | 342 | 3 | 9 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck, build-rule-hard-gpu-class-selection |  |
| as-vector-off | BUILD_RECOMMEND | build-003 | yes | yes | 274 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin, part-spec-rtx-5090-class |  |
| as-vector-off | BUILD_RECOMMEND | build-004 | yes | yes | 233 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, part-spec-rtx-5090-class, internal-rule-psu-atx31-power-margin |  |
| as-vector-off | BUILD_RECOMMEND | build-005 | yes | yes | 869 | 3 | 9 | VECTOR | internal-rule-psu-atx31-power-margin, part-catalog-rtx50-tool-ready-dimensions, part-spec-rtx-5090-class |  |
| as-vector-off | BUILD_RECOMMEND | build-006 | no | yes | 124 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin, part-spec-rtx-5090-class |  |
| as-vector-off | BUILD_RECOMMEND | build-007 | yes | yes | 396 | 3 | 9 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, build-rule-hard-gpu-class-selection |  |
| as-vector-off | BUILD_RECOMMEND | build-008 | yes | yes | 296 | 3 | 9 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, build-rule-hard-gpu-class-selection, internal-rule-build-qhd-gaming-gpu-priority |  |
| as-vector-off | BUILD_RECOMMEND | build-009 | yes | yes | 268 | 3 | 9 | VECTOR | build-rule-memory-storage-workload-floor, part-catalog-rtx50-tool-ready-dimensions, build-rule-cpu-gpu-balance-and-bottleneck |  |
| as-vector-off | BUILD_RECOMMEND | build-010 | yes | yes | 253 | 3 | 9 | VECTOR | build-rule-memory-storage-workload-floor, internal-rule-build-qhd-gaming-gpu-priority, part-spec-rtx-5090-class |  |
| as-vector-off | BUILD_RECOMMEND | build-011 | no | no | 279 | 3 | 9 | VECTOR | part-spec-rtx-5090-class, part-catalog-rtx50-tool-ready-dimensions, build-rule-saved-price-and-psu-headroom |  |
| as-vector-off | BUILD_RECOMMEND | build-012 | no | no | 279 | 3 | 9 | VECTOR | part-spec-rtx-5090-class, part-catalog-rtx50-tool-ready-dimensions, build-rule-hard-gpu-class-selection |  |
| as-vector-off | BUILD_RECOMMEND | build-013 | no | no | 291 | 3 | 9 | VECTOR | build-rule-hard-gpu-class-selection, build-rule-saved-price-and-psu-headroom, build-rule-cpu-gpu-balance-and-bottleneck |  |
| as-vector-off | BUILD_RECOMMEND | build-014 | no | no | 354 | 3 | 9 | VECTOR | build-rule-saved-price-and-psu-headroom, part-catalog-rtx50-tool-ready-dimensions, build-rule-memory-storage-workload-floor |  |
| as-vector-off | BUILD_RECOMMEND | build-015 | yes | yes | 305 | 3 | 9 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, build-rule-saved-price-and-psu-headroom, part-catalog-rtx50-tool-ready-dimensions |  |
| as-vector-off | BUILD_RECOMMEND | build-016 | yes | yes | 284 | 3 | 9 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, build-rule-hard-gpu-class-selection |  |
| as-vector-off | BUILD_RECOMMEND | build-017 | yes | yes | 292 | 3 | 9 | VECTOR | build-rule-memory-storage-workload-floor, internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck |  |
| as-vector-off | BUILD_RECOMMEND | build-018 | no | yes | 283 | 3 | 9 | VECTOR | internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom, part-spec-rtx-5090-class |  |
| as-vector-off | BUILD_RECOMMEND | build-019 | no | yes | 268 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, part-spec-rtx-5090-class, build-rule-airflow-cooler-case-fit |  |
| as-vector-off | BUILD_RECOMMEND | build-020 | yes | yes | 405 | 3 | 9 | VECTOR | build-rule-saved-price-and-psu-headroom, build-rule-hard-gpu-class-selection, part-spec-rtx-5090-class |  |
| as-vector-off | BUILD_EXPLAIN | explain-001 | yes | yes | 243 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| as-vector-off | BUILD_EXPLAIN | explain-002 | yes | yes | 285 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first |  |
| as-vector-off | BUILD_EXPLAIN | explain-003 | yes | yes | 298 | 3 | 3 | VECTOR | benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance |  |
| as-vector-off | BUILD_EXPLAIN | explain-004 | yes | yes | 326 | 3 | 3 | VECTOR | benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance |  |
| as-vector-off | BUILD_EXPLAIN | explain-005 | yes | yes | 276 | 3 | 3 | VECTOR | price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor, internal-rule-case-gpu-clearance |  |
| as-vector-off | BUILD_EXPLAIN | explain-006 | yes | yes | 275 | 3 | 3 | VECTOR | price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor |  |
| as-vector-off | BUILD_EXPLAIN | explain-007 | yes | yes | 258 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| as-vector-off | BUILD_EXPLAIN | explain-008 | yes | yes | 410 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first |  |
| as-vector-off | BUILD_EXPLAIN | explain-009 | yes | yes | 287 | 3 | 3 | VECTOR | benchmark-guide-ram-video-dev-floor, internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first |  |
| as-vector-off | BUILD_EXPLAIN | explain-010 | yes | yes | 295 | 3 | 3 | VECTOR | price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor |  |
| as-vector-off | AS_ANALYZE | as-001 | no | no | 27 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-002 | no | no | 30 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-003 | no | no | 15 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-004 | no | no | 5 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-005 | no | no | 6 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-006 | no | no | 10 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-007 | no | no | 29 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-008 | no | no | 31 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-009 | no | no | 28 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-010 | no | no | 6 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-011 | no | no | 5 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-012 | no | no | 19 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-013 | no | no | 15 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-014 | no | no | 6 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-015 | no | no | 6 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-016 | no | no | 5 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-017 | no | no | 14 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-018 | no | no | 16 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-019 | no | no | 5 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-020 | no | no | 6 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-021 | yes | yes | 10 | 3 | 10 | KEYWORD_FALLBACK | as-guide-power-instability, as-guide-power-instability, as-guide-power-instability |  |
| as-vector-off | AS_ANALYZE | as-022 | no | no | 5 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-023 | no | no | 6 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-024 | no | no | 14 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-025 | no | no | 5 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-026 | no | no | 27 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-027 | no | no | 6 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-028 | no | no | 25 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-029 | no | no | 6 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-030 | no | no | 25 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-031 | no | no | 16 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-032 | no | no | 15 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-033 | no | no | 16 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-034 | no | no | 15 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-035 | no | no | 5 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-036 | no | no | 28 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-037 | no | no | 5 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-038 | no | no | 26 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-039 | no | no | 15 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-040 | no | no | 16 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-041 | no | no | 5 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-042 | no | no | 27 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-043 | no | no | 16 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-044 | no | no | 16 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-045 | no | no | 6 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-046 | no | no | 5 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-047 | no | no | 5 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-048 | no | no | 5 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-049 | no | no | 26 | 3 | 0 | - | - |  |
| as-vector-off | AS_ANALYZE | as-050 | no | no | 5 | 3 | 0 | - | - |  |
| as-vector-off | PUBLIC_SEARCH | public-001 | yes | yes | 257 | 3 | 10 | VECTOR | part-spec-rtx-5090-class, build-rule-hard-gpu-class-selection, requirement-rule-explicit-gpu-class-hard-constraint |  |
| as-vector-off | PUBLIC_SEARCH | public-002 | yes | yes | 269 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | PUBLIC_SEARCH | public-003 | yes | yes | 274 | 3 | 10 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, support-guide-airflow-upgrade-before-gpu, build-rule-hard-gpu-class-selection |  |
| as-vector-off | PUBLIC_SEARCH | public-004 | yes | yes | 304 | 3 | 10 | VECTOR | internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom, internal-rule-case-gpu-clearance |  |
| as-vector-off | PUBLIC_SEARCH | public-005 | yes | yes | 238 | 3 | 10 | VECTOR | internal-rule-case-gpu-clearance, part-catalog-rtx50-tool-ready-dimensions, build-rule-hard-gpu-class-selection |  |
| as-vector-off | PUBLIC_SEARCH | public-006 | yes | yes | 286 | 3 | 10 | VECTOR | benchmark-guide-ram-video-dev-floor, build-rule-memory-storage-workload-floor, as-guide-memory-storage-pressure |  |
| as-vector-off | PUBLIC_SEARCH | public-007 | yes | yes | 282 | 3 | 10 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu |  |
| as-vector-off | PUBLIC_SEARCH | public-008 | yes | yes | 158 | 3 | 10 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure |  |
| as-vector-off | PUBLIC_SEARCH | public-009 | yes | yes | 310 | 3 | 10 | VECTOR | as-guide-memory-storage-pressure, benchmark-guide-ram-video-dev-floor, build-rule-memory-storage-workload-floor |  |
| as-vector-off | PUBLIC_SEARCH | public-010 | yes | yes | 251 | 3 | 10 | VECTOR | as-guide-power-instability, internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom |  |
| as-vector-off | PUBLIC_SEARCH | public-011 | no | no | 460 | 3 | 10 | VECTOR | build-rule-hard-gpu-class-selection, internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck |  |
| as-vector-off | PUBLIC_SEARCH | public-012 | no | yes | 232 | 3 | 10 | VECTOR | build-rule-memory-storage-workload-floor, benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai |  |
| as-vector-off | PUBLIC_SEARCH | public-013 | no | yes | 303 | 3 | 10 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, requirement-example-gaming-resolution-refresh, support-guide-gpu-thermal-frame-drop |  |
| as-vector-off | PUBLIC_SEARCH | public-014 | yes | yes | 262 | 3 | 10 | VECTOR | price-guide-saved-snapshot-first, build-rule-saved-price-and-psu-headroom, as-guide-memory-storage-pressure |  |
| as-vector-off | PUBLIC_SEARCH | public-015 | yes | yes | 292 | 3 | 10 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin, part-spec-rtx-5090-class |  |
| as-vector-off | PUBLIC_SEARCH | public-016 | yes | yes | 308 | 3 | 10 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, support-guide-airflow-upgrade-before-gpu |  |
| as-vector-off | PUBLIC_SEARCH | public-017 | yes | yes | 287 | 3 | 10 | VECTOR | support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop, as-guide-gpu-thermal-frame-drop |  |
| as-vector-off | PUBLIC_SEARCH | public-018 | no | no | 259 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| as-vector-off | PUBLIC_SEARCH | public-019 | yes | yes | 246 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, support-guide-airflow-upgrade-before-gpu |  |
| as-vector-off | PUBLIC_SEARCH | public-020 | yes | yes | 289 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai, internal-rule-build-qhd-gaming-gpu-priority |  |
| public-vector-off | REQUIREMENT_PARSE | req-001 | no | no | 37 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-002 | no | no | 33 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-003 | no | no | 29 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-004 | no | no | 31 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-005 | no | no | 7 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-006 | no | no | 6 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-007 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-008 | no | no | 6 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-009 | no | no | 23 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-010 | no | no | 31 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-011 | no | no | 31 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-012 | no | no | 30 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-013 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-014 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-015 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-016 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-017 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-018 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-019 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-020 | no | no | 24 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-021 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-022 | no | no | 26 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-023 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-024 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-025 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-026 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-027 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-028 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-029 | no | no | 18 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-030 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-031 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-032 | no | no | 26 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-033 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-034 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-035 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-036 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-037 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-038 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-039 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-040 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-041 | no | no | 6 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-042 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-043 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-044 | no | no | 20 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-045 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-046 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-047 | no | no | 22 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-048 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-049 | no | no | 27 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-050 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-051 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-052 | no | no | 26 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-053 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-054 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-055 | no | no | 7 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-056 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-057 | no | no | 14 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-058 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-059 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-060 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-061 | no | no | 21 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-062 | no | no | 31 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-063 | no | no | 14 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-064 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-065 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-066 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-067 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-068 | no | no | 17 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-069 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-070 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-071 | no | no | 27 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-072 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-073 | no | no | 27 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-074 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-075 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-076 | no | no | 24 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-077 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-078 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-079 | no | no | 20 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-080 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-081 | no | no | 28 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-082 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-083 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-084 | no | no | 28 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-085 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-086 | no | no | 26 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-087 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-088 | no | no | 26 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-089 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | REQUIREMENT_PARSE | req-090 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-001 | no | no | 28 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-002 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-003 | no | no | 26 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-004 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-005 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-006 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-007 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-008 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-009 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-010 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-011 | no | no | 19 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-012 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-013 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-014 | no | no | 27 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-015 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-016 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-017 | no | no | 23 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-018 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-019 | no | no | 28 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_RECOMMEND | build-020 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_EXPLAIN | explain-001 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_EXPLAIN | explain-002 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_EXPLAIN | explain-003 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_EXPLAIN | explain-004 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_EXPLAIN | explain-005 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_EXPLAIN | explain-006 | no | no | 29 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_EXPLAIN | explain-007 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_EXPLAIN | explain-008 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_EXPLAIN | explain-009 | no | no | 27 | 3 | 0 | - | - |  |
| public-vector-off | BUILD_EXPLAIN | explain-010 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-001 | no | no | 27 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-002 | no | no | 6 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-003 | no | no | 6 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-004 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-005 | no | no | 6 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-006 | no | no | 9 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-007 | no | no | 8 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-008 | no | no | 6 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-009 | no | no | 10 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-010 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-011 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-012 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-013 | no | no | 20 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-014 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-015 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-016 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-017 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-018 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-019 | no | no | 4 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-020 | no | no | 28 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-021 | yes | yes | 28 | 3 | 10 | KEYWORD_FALLBACK | as-guide-power-instability, as-guide-power-instability, as-guide-power-instability |  |
| public-vector-off | AS_ANALYZE | as-022 | no | no | 6 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-023 | no | no | 27 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-024 | no | no | 6 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-025 | no | no | 26 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-026 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-027 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-028 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-029 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-030 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-031 | no | no | 21 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-032 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-033 | no | no | 26 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-034 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-035 | no | no | 6 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-036 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-037 | no | no | 21 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-038 | no | no | 6 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-039 | no | no | 25 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-040 | no | no | 6 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-041 | no | no | 24 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-042 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-043 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-044 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-045 | no | no | 27 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-046 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-047 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-048 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-049 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | AS_ANALYZE | as-050 | no | no | 27 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-001 | no | no | 32 | 3 | 5 | - | requirement-rule-explicit-gpu-class-hard-constraint, build-rule-hard-gpu-class-selection, requirement-counterexample-explicit-gpu-with-user-budget |  |
| public-vector-off | PUBLIC_SEARCH | public-002 | no | no | 6 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-003 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-004 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-005 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-006 | no | no | 22 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-007 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-008 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-009 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-010 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-011 | no | no | 17 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-012 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-013 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-014 | no | no | 26 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-015 | no | no | 15 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-016 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-017 | no | no | 5 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-018 | no | no | 26 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-019 | no | no | 16 | 3 | 0 | - | - |  |
| public-vector-off | PUBLIC_SEARCH | public-020 | no | no | 17 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-001 | no | no | 23 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-002 | no | no | 32 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-003 | no | no | 8 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-004 | no | no | 22 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-005 | no | no | 7 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-006 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-007 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-008 | no | no | 7 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-009 | no | no | 22 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-010 | no | no | 32 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-011 | no | no | 31 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-012 | no | no | 7 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-013 | no | no | 7 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-014 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-015 | no | no | 33 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-016 | no | no | 31 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-017 | no | no | 30 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-018 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-019 | no | no | 25 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-020 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-021 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-022 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-023 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-024 | no | no | 17 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-025 | no | no | 31 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-026 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-027 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-028 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-029 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-030 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-031 | no | no | 31 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-032 | no | no | 17 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-033 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-034 | no | no | 25 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-035 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-036 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-037 | no | no | 30 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-038 | no | no | 14 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-039 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-040 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-041 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-042 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-043 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-044 | no | no | 23 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-045 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-046 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-047 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-048 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-049 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-050 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-051 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-052 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-053 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-054 | no | no | 28 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-055 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-056 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-057 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-058 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-059 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-060 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-061 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-062 | no | no | 7 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-063 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-064 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-065 | no | no | 14 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-066 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-067 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-068 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-069 | no | no | 19 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-070 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-071 | no | no | 34 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-072 | no | no | 25 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-073 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-074 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-075 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-076 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-077 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-078 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-079 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-080 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-081 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-082 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-083 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-084 | no | no | 7 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-085 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-086 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-087 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-088 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-089 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-090 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-001 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-002 | no | no | 19 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-003 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-004 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-005 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-006 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-007 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-008 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-009 | no | no | 23 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-010 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-011 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-012 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-013 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-014 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-015 | no | no | 22 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-016 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-017 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-018 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-019 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-020 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-001 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-002 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-003 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-004 | no | no | 7 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-005 | no | no | 14 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-006 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-007 | no | no | 3 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-008 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-009 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-010 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-001 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-002 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-003 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-004 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-005 | no | no | 17 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-006 | no | no | 12 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-007 | no | no | 7 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-008 | no | no | 7 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-009 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-010 | no | no | 29 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-011 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-012 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-013 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-014 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-015 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-016 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-017 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-018 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-019 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-020 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-021 | yes | yes | 18 | 3 | 10 | KEYWORD_FALLBACK | as-guide-power-instability, as-guide-power-instability, as-guide-power-instability |  |
| vector-off | AS_ANALYZE | as-022 | no | no | 29 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-023 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-024 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-025 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-026 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-027 | no | no | 19 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-028 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-029 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-030 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-031 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-032 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-033 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-034 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-035 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-036 | no | no | 25 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-037 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-038 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-039 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-040 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-041 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-042 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-043 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-044 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-045 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-046 | no | no | 25 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-047 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-048 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-049 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-050 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-001 | no | no | 8 | 3 | 5 | - | requirement-rule-explicit-gpu-class-hard-constraint, build-rule-hard-gpu-class-selection, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-off | PUBLIC_SEARCH | public-002 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-003 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-004 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-005 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-006 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-007 | no | no | 24 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-008 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-009 | no | no | 25 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-010 | no | no | 31 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-011 | no | no | 31 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-012 | no | no | 31 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-013 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-014 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-015 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-016 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-017 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-018 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-019 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-020 | no | no | 5 | 3 | 0 | - | - |  |

## Policy Reading Guide

- `topKHitRate`가 vector-off와 2%p 미만 차이면 해당 경로는 latency를 보고 끌 후보가 된다.
- `REQUIREMENT_PARSE`와 `BUILD_RECOMMEND`는 5090, 끝판왕, 예산 표현 같은 의미 검색 실패 감소를 우선한다.
- `AS_ANALYZE`는 thermal, driver, memory, storage, power 증상 근거가 top3 안에 들어오는지를 우선한다.
- 이 보고서는 기본 env를 바꾸지 않고 다음 PR의 정책 판단 근거로만 사용한다.
