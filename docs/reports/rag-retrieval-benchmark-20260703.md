# RAG Retrieval Benchmark

- generatedAt: 2026-07-03T04:39:34
- distinctCases: 190
- variants: 1
- totalRows: 190
- endpoint: `GET /api/rag/search`

## Summary

| variant | purpose | cases | top1HitRate | topKHitRate | avgLatencyMs | p95LatencyMs | avgResults |
|---|---|---:|---:|---:|---:|---:|---:|
| vector-on | REQUIREMENT_PARSE | 90 | 78.9% | 100.0% | 288 | 427 | 10.0 |
| vector-on | BUILD_RECOMMEND | 20 | 75.0% | 100.0% | 305 | 414 | 10.0 |
| vector-on | BUILD_EXPLAIN | 10 | 100.0% | 100.0% | 280 | 382 | 3.0 |
| vector-on | AS_ANALYZE | 50 | 78.0% | 100.0% | 312 | 413 | 6.0 |
| vector-on | PUBLIC_SEARCH | 20 | 90.0% | 100.0% | 278 | 395 | 10.0 |

## Cases

| variant | purpose | case | top1 | topK | latencyMs | k | count | modes | topSources | error |
|---|---|---|---:|---:|---:|---:|---:|---|---|---|
| vector-on | REQUIREMENT_PARSE | req-001 | yes | yes | 321 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-002 | yes | yes | 263 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, guide-requirement-parse-budget-resolution-workload, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-003 | yes | yes | 256 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, guide-requirement-parse-budget-resolution-workload, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-004 | yes | yes | 282 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-005 | yes | yes | 223 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-006 | yes | yes | 247 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, internal-rule-requirement-parse-premium-open-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-007 | yes | yes | 274 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-008 | yes | yes | 296 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, requirement-example-gaming-resolution-refresh, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-009 | yes | yes | 251 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, guide-requirement-parse-budget-resolution-workload, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-010 | yes | yes | 315 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-011 | no | yes | 264 | 3 | 10 | VECTOR | requirement-rule-explicit-gpu-class-hard-constraint, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-012 | yes | yes | 123 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-013 | yes | yes | 252 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-rule-explicit-gpu-class-hard-constraint, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-014 | yes | yes | 261 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-015 | yes | yes | 310 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-016 | yes | yes | 410 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-017 | yes | yes | 266 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-018 | yes | yes | 285 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-019 | yes | yes | 288 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-020 | yes | yes | 313 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-021 | yes | yes | 262 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-022 | yes | yes | 289 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-023 | yes | yes | 260 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-024 | no | yes | 278 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, guide-requirement-parse-budget-resolution-workload, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-025 | yes | yes | 293 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-026 | yes | yes | 875 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-027 | yes | yes | 496 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-028 | yes | yes | 297 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-029 | yes | yes | 267 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-030 | no | yes | 231 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-031 | yes | yes | 287 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-032 | yes | yes | 328 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-033 | yes | yes | 257 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-034 | yes | yes | 255 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-035 | yes | yes | 245 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-036 | yes | yes | 232 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-037 | yes | yes | 143 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-038 | yes | yes | 242 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-039 | no | yes | 237 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-noise-upgrade-brand, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-040 | yes | yes | 429 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-041 | yes | yes | 297 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-042 | yes | yes | 295 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-043 | no | yes | 278 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-044 | yes | yes | 396 | 3 | 10 | VECTOR | guide-requirement-parse-budget-resolution-workload, requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-045 | no | yes | 320 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-046 | yes | yes | 296 | 3 | 10 | VECTOR | guide-requirement-parse-budget-resolution-workload, requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-047 | no | yes | 233 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-048 | no | yes | 293 | 3 | 10 | VECTOR | guide-requirement-parse-budget-resolution-workload, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-049 | no | yes | 429 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-050 | no | yes | 235 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-051 | yes | yes | 254 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-052 | yes | yes | 308 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-053 | no | yes | 134 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-054 | yes | yes | 283 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-055 | yes | yes | 300 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-056 | yes | yes | 289 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-057 | no | yes | 303 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-058 | yes | yes | 290 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-059 | yes | yes | 364 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-060 | yes | yes | 258 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-061 | yes | yes | 271 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-062 | yes | yes | 309 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-rule-explicit-gpu-class-hard-constraint, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-063 | yes | yes | 260 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-064 | yes | yes | 246 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-065 | no | yes | 401 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, guide-requirement-parse-budget-resolution-workload, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-066 | yes | yes | 144 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-067 | yes | yes | 232 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-068 | yes | yes | 271 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-069 | yes | yes | 132 | 3 | 10 | VECTOR | guide-requirement-parse-budget-resolution-workload, requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-070 | yes | yes | 250 | 3 | 10 | VECTOR | guide-requirement-parse-budget-resolution-workload, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-071 | yes | yes | 306 | 3 | 10 | VECTOR | guide-requirement-parse-budget-resolution-workload, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-072 | no | yes | 257 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-073 | no | yes | 291 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-074 | yes | yes | 275 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-075 | no | yes | 229 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-076 | no | yes | 248 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-077 | yes | yes | 233 | 3 | 10 | VECTOR | guide-requirement-parse-budget-resolution-workload, requirement-counterexample-premium-with-user-budget, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-078 | no | yes | 156 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-079 | yes | yes | 339 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-080 | yes | yes | 326 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-example-noise-upgrade-brand, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-081 | yes | yes | 269 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-082 | yes | yes | 280 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-083 | yes | yes | 267 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-084 | yes | yes | 419 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, guide-requirement-parse-budget-resolution-workload, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-085 | yes | yes | 258 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-086 | no | yes | 402 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-087 | yes | yes | 277 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-counterexample-explicit-gpu-with-user-budget, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-088 | yes | yes | 427 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-089 | yes | yes | 239 | 3 | 10 | VECTOR | guide-requirement-parse-budget-resolution-workload, requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-090 | yes | yes | 370 | 3 | 10 | VECTOR | guide-requirement-parse-budget-resolution-workload, requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development |  |
| vector-on | BUILD_RECOMMEND | build-001 | yes | yes | 285 | 3 | 10 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, build-rule-qhd-price-combined-evidence, build-rule-cpu-gpu-balance-and-bottleneck |  |
| vector-on | BUILD_RECOMMEND | build-002 | yes | yes | 366 | 3 | 10 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, build-rule-qhd-price-combined-evidence, build-rule-cpu-gpu-balance-and-bottleneck |  |
| vector-on | BUILD_RECOMMEND | build-003 | no | yes | 370 | 3 | 10 | VECTOR | build-rule-saved-price-and-psu-headroom, part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin |  |
| vector-on | BUILD_RECOMMEND | build-004 | no | yes | 270 | 3 | 10 | VECTOR | part-spec-rtx-5090-class, part-catalog-rtx50-tool-ready-dimensions, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-005 | yes | yes | 362 | 3 | 10 | VECTOR | internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom, part-catalog-rtx50-tool-ready-dimensions |  |
| vector-on | BUILD_RECOMMEND | build-006 | yes | yes | 264 | 3 | 10 | VECTOR | internal-rule-psu-atx31-power-margin, part-catalog-rtx50-tool-ready-dimensions, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-007 | yes | yes | 432 | 3 | 10 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, build-rule-airflow-cooler-case-fit |  |
| vector-on | BUILD_RECOMMEND | build-008 | yes | yes | 231 | 3 | 10 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, build-rule-airflow-cooler-case-fit |  |
| vector-on | BUILD_RECOMMEND | build-009 | yes | yes | 279 | 3 | 10 | VECTOR | build-rule-memory-storage-workload-floor, internal-rule-build-qhd-gaming-gpu-priority, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-010 | no | yes | 313 | 3 | 10 | VECTOR | price-guide-saved-snapshot-first, build-rule-memory-storage-workload-floor, build-rule-saved-price-and-psu-headroom |  |
| vector-on | BUILD_RECOMMEND | build-011 | yes | yes | 276 | 3 | 10 | VECTOR | build-rule-airflow-cooler-case-fit, part-spec-rtx-5090-class, part-catalog-rtx50-tool-ready-dimensions |  |
| vector-on | BUILD_RECOMMEND | build-012 | yes | yes | 244 | 3 | 10 | VECTOR | build-rule-airflow-cooler-case-fit, part-spec-rtx-5090-class, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-013 | yes | yes | 250 | 3 | 10 | VECTOR | price-guide-saved-snapshot-first, build-rule-saved-price-and-psu-headroom, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-014 | yes | yes | 414 | 3 | 10 | VECTOR | price-guide-saved-snapshot-first, build-rule-saved-price-and-psu-headroom, build-rule-memory-storage-workload-floor |  |
| vector-on | BUILD_RECOMMEND | build-015 | yes | yes | 278 | 3 | 10 | VECTOR | build-rule-qhd-price-combined-evidence, price-guide-saved-snapshot-first, internal-rule-build-qhd-gaming-gpu-priority |  |
| vector-on | BUILD_RECOMMEND | build-016 | yes | yes | 295 | 3 | 10 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, build-rule-qhd-price-combined-evidence |  |
| vector-on | BUILD_RECOMMEND | build-017 | yes | yes | 363 | 3 | 10 | VECTOR | build-rule-memory-storage-workload-floor, internal-rule-build-qhd-gaming-gpu-priority, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-018 | no | yes | 280 | 3 | 10 | VECTOR | internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-019 | yes | yes | 263 | 3 | 10 | VECTOR | build-rule-airflow-cooler-case-fit, internal-rule-build-qhd-gaming-gpu-priority, part-catalog-rtx50-tool-ready-dimensions |  |
| vector-on | BUILD_RECOMMEND | build-020 | no | yes | 272 | 3 | 10 | VECTOR | price-guide-saved-snapshot-first, build-rule-saved-price-and-psu-headroom, build-rule-memory-storage-workload-floor |  |
| vector-on | BUILD_EXPLAIN | explain-001 | yes | yes | 290 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-002 | yes | yes | 382 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-003 | yes | yes | 257 | 3 | 3 | VECTOR | benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance |  |
| vector-on | BUILD_EXPLAIN | explain-004 | yes | yes | 291 | 3 | 3 | VECTOR | benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance |  |
| vector-on | BUILD_EXPLAIN | explain-005 | yes | yes | 158 | 3 | 3 | VECTOR | price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor, internal-rule-case-gpu-clearance |  |
| vector-on | BUILD_EXPLAIN | explain-006 | yes | yes | 289 | 3 | 3 | VECTOR | price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-007 | yes | yes | 303 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-008 | yes | yes | 266 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-009 | yes | yes | 297 | 3 | 3 | VECTOR | benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance |  |
| vector-on | BUILD_EXPLAIN | explain-010 | yes | yes | 268 | 3 | 3 | VECTOR | price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor |  |
| vector-on | AS_ANALYZE | as-001 | yes | yes | 304 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-002 | yes | yes | 403 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-003 | yes | yes | 281 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-004 | yes | yes | 260 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-005 | yes | yes | 258 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-006 | no | yes | 298 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-007 | no | yes | 247 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-008 | yes | yes | 348 | 3 | 6 | VECTOR | support-guide-airflow-upgrade-before-gpu, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-009 | yes | yes | 377 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-010 | yes | yes | 276 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-011 | yes | yes | 292 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-012 | yes | yes | 413 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-013 | yes | yes | 262 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-014 | yes | yes | 296 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-015 | yes | yes | 132 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-016 | yes | yes | 766 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-017 | yes | yes | 413 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-018 | yes | yes | 294 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-019 | yes | yes | 390 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-020 | yes | yes | 229 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-021 | yes | yes | 465 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-022 | yes | yes | 337 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-023 | no | yes | 284 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-power-instability, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-024 | yes | yes | 249 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-memory-storage-pressure, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-025 | no | yes | 397 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-026 | yes | yes | 255 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-027 | yes | yes | 383 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-028 | yes | yes | 298 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-029 | yes | yes | 282 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-030 | no | yes | 280 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-031 | no | yes | 306 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-032 | no | yes | 291 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-033 | yes | yes | 253 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-power-instability, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-034 | no | yes | 392 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-035 | yes | yes | 286 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-036 | yes | yes | 252 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-037 | yes | yes | 288 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-038 | yes | yes | 278 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-039 | yes | yes | 250 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-040 | yes | yes | 290 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-041 | yes | yes | 335 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-042 | yes | yes | 383 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-043 | no | yes | 314 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-044 | yes | yes | 402 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-045 | yes | yes | 267 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-046 | no | yes | 297 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-047 | yes | yes | 306 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-048 | no | yes | 285 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-049 | yes | yes | 226 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-memory-storage-pressure, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-050 | yes | yes | 148 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | PUBLIC_SEARCH | public-001 | yes | yes | 315 | 3 | 10 | VECTOR | part-spec-rtx-5090-class, build-rule-hard-gpu-class-selection, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | PUBLIC_SEARCH | public-002 | yes | yes | 312 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | PUBLIC_SEARCH | public-003 | yes | yes | 276 | 3 | 10 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, build-rule-qhd-price-combined-evidence, support-guide-gpu-thermal-frame-drop |  |
| vector-on | PUBLIC_SEARCH | public-004 | yes | yes | 306 | 3 | 10 | VECTOR | internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom, as-guide-power-instability |  |
| vector-on | PUBLIC_SEARCH | public-005 | yes | yes | 272 | 3 | 10 | VECTOR | internal-rule-case-gpu-clearance, build-rule-airflow-cooler-case-fit, part-catalog-rtx50-tool-ready-dimensions |  |
| vector-on | PUBLIC_SEARCH | public-006 | yes | yes | 299 | 3 | 10 | VECTOR | benchmark-guide-ram-video-dev-floor, build-rule-memory-storage-workload-floor, as-guide-memory-storage-pressure |  |
| vector-on | PUBLIC_SEARCH | public-007 | yes | yes | 176 | 3 | 10 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu |  |
| vector-on | PUBLIC_SEARCH | public-008 | yes | yes | 311 | 3 | 10 | VECTOR | as-guide-driver-crash-event-log, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| vector-on | PUBLIC_SEARCH | public-009 | yes | yes | 282 | 3 | 10 | VECTOR | as-guide-memory-storage-pressure, benchmark-guide-ram-video-dev-floor, build-rule-memory-storage-workload-floor |  |
| vector-on | PUBLIC_SEARCH | public-010 | yes | yes | 395 | 3 | 10 | VECTOR | as-guide-power-instability, internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom |  |
| vector-on | PUBLIC_SEARCH | public-011 | yes | yes | 277 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, build-rule-hard-gpu-class-selection, internal-rule-build-qhd-gaming-gpu-priority |  |
| vector-on | PUBLIC_SEARCH | public-012 | no | yes | 438 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai, build-rule-memory-storage-workload-floor |  |
| vector-on | PUBLIC_SEARCH | public-013 | yes | yes | 252 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, internal-rule-build-qhd-gaming-gpu-priority, build-rule-qhd-price-combined-evidence |  |
| vector-on | PUBLIC_SEARCH | public-014 | yes | yes | 133 | 3 | 10 | VECTOR | price-guide-saved-snapshot-first, build-rule-qhd-price-combined-evidence, build-rule-saved-price-and-psu-headroom |  |
| vector-on | PUBLIC_SEARCH | public-015 | yes | yes | 270 | 3 | 10 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin, part-spec-rtx-5090-class |  |
| vector-on | PUBLIC_SEARCH | public-016 | yes | yes | 286 | 3 | 10 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, support-guide-airflow-upgrade-before-gpu |  |
| vector-on | PUBLIC_SEARCH | public-017 | yes | yes | 230 | 3 | 10 | VECTOR | support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop, as-guide-gpu-thermal-frame-drop |  |
| vector-on | PUBLIC_SEARCH | public-018 | no | yes | 124 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | PUBLIC_SEARCH | public-019 | yes | yes | 323 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, support-guide-airflow-upgrade-before-gpu |  |
| vector-on | PUBLIC_SEARCH | public-020 | yes | yes | 283 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh |  |

## Top1 Miss Summary

| variant | purpose | top1OnlyMiss | cases |
|---|---|---:|---|
| vector-on | AS_ANALYZE | 11 | as-006, as-007, as-023, as-025, as-030, as-031, as-032, as-034, as-043, as-046, as-048 |
| vector-on | BUILD_RECOMMEND | 5 | build-003, build-004, build-010, build-018, build-020 |
| vector-on | PUBLIC_SEARCH | 2 | public-012, public-018 |
| vector-on | REQUIREMENT_PARSE | 19 | req-011, req-024, req-030, req-039, req-043, req-045, req-047, req-048, req-049, req-050, req-053, req-057, +7 more |

## Policy Reading Guide

- `topKHitRate`가 vector-off와 2%p 미만 차이면 해당 경로는 latency를 보고 끌 후보가 된다.
- `REQUIREMENT_PARSE`와 `BUILD_RECOMMEND`는 5090, 끝판왕, 예산 표현 같은 의미 검색 실패 감소를 우선한다.
- `AS_ANALYZE`는 thermal, driver, memory, storage, power 증상 근거가 top3 안에 들어오는지를 우선한다.
- 이 보고서는 기본 env를 바꾸지 않고 다음 PR의 정책 판단 근거로만 사용한다.
