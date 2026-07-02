# RAG Retrieval Benchmark

- generatedAt: 2026-07-02T11:57:16
- distinctCases: 190
- variants: 2
- totalRows: 380
- endpoint: `GET /api/rag/search`

## Summary

| variant | purpose | cases | top1HitRate | topKHitRate | avgLatencyMs | p95LatencyMs | avgResults |
|---|---|---:|---:|---:|---:|---:|---:|
| vector-off | REQUIREMENT_PARSE | 90 | 0.0% | 0.0% | 13 | 31 | 0.0 |
| vector-off | BUILD_RECOMMEND | 20 | 0.0% | 0.0% | 11 | 27 | 0.0 |
| vector-off | BUILD_EXPLAIN | 10 | 0.0% | 0.0% | 14 | 28 | 0.0 |
| vector-off | AS_ANALYZE | 50 | 2.0% | 2.0% | 13 | 28 | 0.0 |
| vector-off | PUBLIC_SEARCH | 20 | 0.0% | 0.0% | 11 | 27 | 0.3 |
| vector-on | REQUIREMENT_PARSE | 90 | 75.6% | 96.7% | 333 | 501 | 9.9 |
| vector-on | BUILD_RECOMMEND | 20 | 70.0% | 95.0% | 363 | 724 | 10.0 |
| vector-on | BUILD_EXPLAIN | 10 | 100.0% | 100.0% | 320 | 416 | 3.0 |
| vector-on | AS_ANALYZE | 50 | 76.0% | 98.0% | 328 | 547 | 6.0 |
| vector-on | PUBLIC_SEARCH | 20 | 85.0% | 95.0% | 311 | 392 | 10.0 |

## Cases

| variant | purpose | case | top1 | topK | latencyMs | k | count | modes | topSources | error |
|---|---|---|---:|---:|---:|---:|---:|---|---|---|
| vector-off | REQUIREMENT_PARSE | req-001 | no | no | 33 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-002 | no | no | 17 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-003 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-004 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-005 | no | no | 7 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-006 | no | no | 31 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-007 | no | no | 7 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-008 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-009 | no | no | 32 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-010 | no | no | 31 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-011 | no | no | 29 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-012 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-013 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-014 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-015 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-016 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-017 | no | no | 29 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-018 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-019 | no | no | 9 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-020 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-021 | no | no | 18 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-022 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-023 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-024 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-025 | no | no | 14 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-026 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-027 | no | no | 25 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-028 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-029 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-030 | no | no | 21 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-031 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-032 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-033 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-034 | no | no | 21 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-035 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-036 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-037 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-038 | no | no | 14 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-039 | no | no | 30 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-040 | no | no | 29 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-041 | no | no | 31 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-042 | no | no | 30 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-043 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-044 | no | no | 24 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-045 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-046 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-047 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-048 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-049 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-050 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-051 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-052 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-053 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-054 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-055 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-056 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-057 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-058 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-059 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-060 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-061 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-062 | no | no | 9 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-063 | no | no | 18 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-064 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-065 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-066 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-067 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-068 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-069 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-070 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-071 | no | no | 18 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-072 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-073 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-074 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-075 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-076 | no | no | 23 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-077 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-078 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-079 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-080 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-081 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-082 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-083 | no | no | 19 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-084 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-085 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-086 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-087 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-088 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-089 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-090 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-001 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-002 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-003 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-004 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-005 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-006 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-007 | no | no | 28 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-008 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-009 | no | no | 25 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-010 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-011 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-012 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-013 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-014 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-015 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-016 | no | no | 20 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-017 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-018 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-019 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-020 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-001 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-002 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-003 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-004 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-005 | no | no | 28 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-006 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-007 | no | no | 25 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-008 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-009 | no | no | 28 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-010 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-001 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-002 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-003 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-004 | no | no | 24 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-005 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-006 | no | no | 19 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-007 | no | no | 29 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-008 | no | no | 29 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-009 | no | no | 14 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-010 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-011 | no | no | 28 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-012 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-013 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-014 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-015 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-016 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-017 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-018 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-019 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-020 | no | no | 13 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-021 | yes | yes | 11 | 3 | 1 | - | as-guide-power-instability |  |
| vector-off | AS_ANALYZE | as-022 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-023 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-024 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-025 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-026 | no | no | 28 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-027 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-028 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-029 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-030 | no | no | 20 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-031 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-032 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-033 | no | no | 14 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-034 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-035 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-036 | no | no | 28 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-037 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-038 | no | no | 28 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-039 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-040 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-041 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-042 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-043 | no | no | 14 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-044 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-045 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-046 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-047 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-048 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-049 | no | no | 20 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-050 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-001 | no | no | 22 | 3 | 6 | - | requirement-rule-explicit-gpu-class-hard-constraint, build-rule-hard-gpu-class-selection, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-off | PUBLIC_SEARCH | public-002 | no | no | 25 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-003 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-004 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-005 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-006 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-007 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-008 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-009 | no | no | 24 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-010 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-011 | no | no | 3 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-012 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-013 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-014 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-015 | no | no | 3 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-016 | no | no | 3 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-017 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-018 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-019 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-020 | no | no | 16 | 3 | 0 | - | - |  |
| vector-on | REQUIREMENT_PARSE | req-001 | yes | yes | 1336 | 3 | 10 | - | internal-rule-requirement-parse-premium-open-budget, requirement-example-workload-mixed-creator-ai, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-002 | yes | yes | 658 | 3 | 10 | - | internal-rule-requirement-parse-premium-open-budget, guide-requirement-parse-budget-resolution-workload, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-003 | yes | yes | 320 | 3 | 10 | - | internal-rule-requirement-parse-premium-open-budget, guide-requirement-parse-budget-resolution-workload, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-004 | yes | yes | 269 | 3 | 10 | - | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-005 | yes | yes | 252 | 3 | 10 | - | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-006 | no | no | 113 | 3 | 0 | - | - |  |
| vector-on | REQUIREMENT_PARSE | req-007 | yes | yes | 286 | 3 | 10 | - | requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-008 | yes | yes | 281 | 3 | 10 | - | requirement-counterexample-premium-with-user-budget, guide-requirement-parse-budget-resolution-workload, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-009 | yes | yes | 438 | 3 | 10 | - | requirement-counterexample-premium-with-user-budget, guide-requirement-parse-budget-resolution-workload, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-010 | yes | yes | 250 | 3 | 10 | - | requirement-counterexample-premium-with-user-budget, requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-011 | no | yes | 294 | 3 | 10 | - | requirement-rule-explicit-gpu-class-hard-constraint, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-012 | yes | yes | 275 | 3 | 10 | - | internal-rule-requirement-parse-premium-open-budget, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-013 | yes | yes | 274 | 3 | 10 | - | internal-rule-requirement-parse-premium-open-budget, requirement-rule-explicit-gpu-class-hard-constraint, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-014 | yes | yes | 314 | 3 | 10 | - | requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-015 | no | no | 276 | 3 | 10 | - | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-016 | yes | yes | 296 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-017 | yes | yes | 279 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-018 | yes | yes | 286 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-019 | yes | yes | 248 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-020 | yes | yes | 138 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-021 | yes | yes | 296 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-022 | yes | yes | 303 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-023 | yes | yes | 419 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-024 | no | yes | 333 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, guide-requirement-parse-budget-resolution-workload, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-025 | yes | yes | 262 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-026 | yes | yes | 433 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-027 | yes | yes | 410 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-028 | yes | yes | 309 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-029 | yes | yes | 271 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-030 | no | yes | 284 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-031 | yes | yes | 285 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-032 | yes | yes | 424 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, internal-rule-requirement-parse-premium-open-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-033 | yes | yes | 300 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, requirement-example-noise-upgrade-brand, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-034 | yes | yes | 312 | 3 | 10 | - | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-035 | yes | yes | 237 | 3 | 10 | - | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-036 | yes | yes | 137 | 3 | 10 | - | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-037 | yes | yes | 138 | 3 | 10 | - | requirement-example-noise-upgrade-brand, requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-038 | yes | yes | 309 | 3 | 10 | - | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-039 | no | yes | 147 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, requirement-example-noise-upgrade-brand, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-040 | yes | yes | 427 | 3 | 10 | - | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-041 | yes | yes | 275 | 3 | 10 | - | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-042 | yes | yes | 260 | 3 | 10 | - | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-043 | no | yes | 307 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, guide-requirement-parse-budget-resolution-workload, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-044 | yes | yes | 330 | 3 | 10 | - | guide-requirement-parse-budget-resolution-workload, requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-045 | no | yes | 299 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-046 | yes | yes | 275 | 3 | 10 | - | guide-requirement-parse-budget-resolution-workload, requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-047 | no | yes | 286 | 3 | 10 | - | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-048 | no | yes | 300 | 3 | 10 | - | guide-requirement-parse-budget-resolution-workload, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-049 | no | yes | 407 | 3 | 10 | - | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-050 | no | yes | 422 | 3 | 10 | - | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-051 | yes | yes | 404 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-052 | yes | yes | 392 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-053 | no | yes | 286 | 3 | 10 | - | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-054 | no | no | 153 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-055 | yes | yes | 410 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-056 | yes | yes | 384 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-057 | no | yes | 273 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-058 | yes | yes | 278 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-059 | yes | yes | 315 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-060 | yes | yes | 392 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-061 | yes | yes | 500 | 3 | 10 | - | requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-062 | yes | yes | 273 | 3 | 10 | - | internal-rule-requirement-parse-premium-open-budget, requirement-rule-explicit-gpu-class-hard-constraint, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-063 | yes | yes | 241 | 3 | 10 | - | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-064 | yes | yes | 320 | 3 | 10 | - | requirement-counterexample-premium-with-user-budget, requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-065 | no | yes | 414 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, guide-requirement-parse-budget-resolution-workload, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-066 | yes | yes | 326 | 3 | 10 | - | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-067 | yes | yes | 249 | 3 | 10 | - | requirement-example-noise-upgrade-brand, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-068 | yes | yes | 443 | 3 | 10 | - | requirement-example-noise-upgrade-brand, requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-069 | yes | yes | 263 | 3 | 10 | - | guide-requirement-parse-budget-resolution-workload, requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-070 | yes | yes | 322 | 3 | 10 | - | guide-requirement-parse-budget-resolution-workload, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-071 | yes | yes | 304 | 3 | 10 | - | guide-requirement-parse-budget-resolution-workload, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-072 | no | yes | 416 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-073 | no | yes | 292 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-074 | yes | yes | 259 | 3 | 10 | - | benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-075 | no | yes | 1023 | 3 | 10 | - | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-076 | no | yes | 384 | 3 | 10 | - | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-077 | yes | yes | 290 | 3 | 10 | - | guide-requirement-parse-budget-resolution-workload, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-078 | no | yes | 632 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-079 | yes | yes | 306 | 3 | 10 | - | internal-rule-requirement-parse-premium-open-budget, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-080 | yes | yes | 501 | 3 | 10 | - | internal-rule-requirement-parse-premium-open-budget, requirement-example-noise-upgrade-brand, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-081 | yes | yes | 295 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-082 | yes | yes | 277 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-083 | yes | yes | 316 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-084 | yes | yes | 288 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, guide-requirement-parse-budget-resolution-workload, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-085 | yes | yes | 146 | 3 | 10 | - | requirement-example-workload-mixed-creator-ai, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-086 | no | yes | 162 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-087 | yes | yes | 289 | 3 | 10 | - | requirement-example-noise-upgrade-brand, requirement-counterexample-explicit-gpu-with-user-budget, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-088 | yes | yes | 306 | 3 | 10 | - | requirement-example-noise-upgrade-brand, requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-noise-upgrade |  |
| vector-on | REQUIREMENT_PARSE | req-089 | yes | yes | 433 | 3 | 10 | - | guide-requirement-parse-budget-resolution-workload, requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-090 | yes | yes | 294 | 3 | 10 | - | guide-requirement-parse-budget-resolution-workload, requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development |  |
| vector-on | BUILD_RECOMMEND | build-001 | yes | yes | 1036 | 3 | 10 | - | internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-002 | yes | yes | 513 | 3 | 10 | - | internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-003 | no | yes | 238 | 3 | 10 | - | build-rule-saved-price-and-psu-headroom, part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin |  |
| vector-on | BUILD_RECOMMEND | build-004 | no | yes | 290 | 3 | 10 | - | part-spec-rtx-5090-class, part-catalog-rtx50-tool-ready-dimensions, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-005 | yes | yes | 201 | 3 | 10 | - | internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom, part-catalog-rtx50-tool-ready-dimensions |  |
| vector-on | BUILD_RECOMMEND | build-006 | yes | yes | 428 | 3 | 10 | - | internal-rule-psu-atx31-power-margin, part-catalog-rtx50-tool-ready-dimensions, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-007 | yes | yes | 286 | 3 | 10 | - | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, build-rule-airflow-cooler-case-fit |  |
| vector-on | BUILD_RECOMMEND | build-008 | yes | yes | 356 | 3 | 10 | - | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, build-rule-airflow-cooler-case-fit |  |
| vector-on | BUILD_RECOMMEND | build-009 | yes | yes | 307 | 3 | 10 | - | build-rule-memory-storage-workload-floor, internal-rule-build-qhd-gaming-gpu-priority, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-010 | no | yes | 317 | 3 | 10 | - | price-guide-saved-snapshot-first, build-rule-memory-storage-workload-floor, build-rule-saved-price-and-psu-headroom |  |
| vector-on | BUILD_RECOMMEND | build-011 | yes | yes | 278 | 3 | 10 | - | build-rule-airflow-cooler-case-fit, part-spec-rtx-5090-class, part-catalog-rtx50-tool-ready-dimensions |  |
| vector-on | BUILD_RECOMMEND | build-012 | yes | yes | 319 | 3 | 10 | - | build-rule-airflow-cooler-case-fit, part-spec-rtx-5090-class, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-013 | yes | yes | 269 | 3 | 10 | - | price-guide-saved-snapshot-first, build-rule-saved-price-and-psu-headroom, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-014 | yes | yes | 272 | 3 | 10 | - | price-guide-saved-snapshot-first, build-rule-saved-price-and-psu-headroom, build-rule-memory-storage-workload-floor |  |
| vector-on | BUILD_RECOMMEND | build-015 | no | no | 285 | 3 | 10 | - | price-guide-saved-snapshot-first, internal-rule-build-qhd-gaming-gpu-priority, build-rule-saved-price-and-psu-headroom |  |
| vector-on | BUILD_RECOMMEND | build-016 | yes | yes | 316 | 3 | 10 | - | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, build-rule-airflow-cooler-case-fit |  |
| vector-on | BUILD_RECOMMEND | build-017 | yes | yes | 293 | 3 | 10 | - | build-rule-memory-storage-workload-floor, internal-rule-build-qhd-gaming-gpu-priority, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-018 | no | yes | 273 | 3 | 10 | - | internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-019 | yes | yes | 253 | 3 | 10 | - | build-rule-airflow-cooler-case-fit, internal-rule-build-qhd-gaming-gpu-priority, part-catalog-rtx50-tool-ready-dimensions |  |
| vector-on | BUILD_RECOMMEND | build-020 | no | yes | 724 | 3 | 10 | - | price-guide-saved-snapshot-first, build-rule-saved-price-and-psu-headroom, build-rule-memory-storage-workload-floor |  |
| vector-on | BUILD_EXPLAIN | explain-001 | yes | yes | 334 | 3 | 3 | - | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-002 | yes | yes | 256 | 3 | 3 | - | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-003 | yes | yes | 355 | 3 | 3 | - | benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance |  |
| vector-on | BUILD_EXPLAIN | explain-004 | yes | yes | 280 | 3 | 3 | - | benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance |  |
| vector-on | BUILD_EXPLAIN | explain-005 | yes | yes | 314 | 3 | 3 | - | price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor, internal-rule-case-gpu-clearance |  |
| vector-on | BUILD_EXPLAIN | explain-006 | yes | yes | 300 | 3 | 3 | - | price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-007 | yes | yes | 276 | 3 | 3 | - | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-008 | yes | yes | 321 | 3 | 3 | - | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-009 | yes | yes | 416 | 3 | 3 | - | benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance |  |
| vector-on | BUILD_EXPLAIN | explain-010 | yes | yes | 345 | 3 | 3 | - | price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor |  |
| vector-on | AS_ANALYZE | as-001 | yes | yes | 260 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-002 | yes | yes | 303 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-003 | yes | yes | 585 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-004 | yes | yes | 547 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-005 | yes | yes | 563 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-006 | no | yes | 304 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-007 | no | yes | 492 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-008 | yes | yes | 288 | 3 | 6 | - | support-guide-airflow-upgrade-before-gpu, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-009 | yes | yes | 275 | 3 | 6 | - | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-010 | yes | yes | 335 | 3 | 6 | - | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-011 | yes | yes | 277 | 3 | 6 | - | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-012 | yes | yes | 283 | 3 | 6 | - | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-013 | yes | yes | 323 | 3 | 6 | - | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-014 | yes | yes | 415 | 3 | 6 | - | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-015 | yes | yes | 287 | 3 | 6 | - | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-016 | yes | yes | 517 | 3 | 6 | - | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-017 | yes | yes | 327 | 3 | 6 | - | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-018 | yes | yes | 282 | 3 | 6 | - | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-019 | yes | yes | 267 | 3 | 6 | - | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-020 | yes | yes | 375 | 3 | 6 | - | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-021 | yes | yes | 314 | 3 | 6 | - | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-022 | yes | yes | 386 | 3 | 6 | - | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-023 | no | yes | 313 | 3 | 6 | - | as-guide-driver-crash-event-log, as-guide-power-instability, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-024 | yes | yes | 317 | 3 | 6 | - | as-guide-power-instability, as-guide-memory-storage-pressure, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-025 | no | yes | 309 | 3 | 6 | - | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-026 | yes | yes | 268 | 3 | 6 | - | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-027 | yes | yes | 319 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-028 | yes | yes | 398 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-029 | yes | yes | 288 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-030 | no | yes | 270 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-031 | no | yes | 429 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-032 | no | yes | 295 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-033 | yes | yes | 421 | 3 | 6 | - | as-guide-driver-crash-event-log, as-guide-power-instability, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-034 | no | yes | 252 | 3 | 6 | - | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-035 | yes | yes | 234 | 3 | 6 | - | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-036 | yes | yes | 173 | 3 | 6 | - | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-037 | yes | yes | 259 | 3 | 6 | - | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-038 | yes | yes | 441 | 3 | 6 | - | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-039 | yes | yes | 280 | 3 | 6 | - | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-040 | yes | yes | 304 | 3 | 6 | - | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-041 | yes | yes | 314 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-042 | yes | yes | 247 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-043 | no | yes | 286 | 3 | 6 | - | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-044 | yes | yes | 264 | 3 | 6 | - | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-045 | yes | yes | 284 | 3 | 6 | - | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-046 | no | yes | 316 | 3 | 6 | - | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-047 | yes | yes | 287 | 3 | 6 | - | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-048 | no | yes | 277 | 3 | 6 | - | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-049 | yes | yes | 291 | 3 | 6 | - | as-guide-power-instability, as-guide-memory-storage-pressure, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-050 | no | no | 272 | 3 | 6 | - | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | PUBLIC_SEARCH | public-001 | no | no | 286 | 3 | 10 | - | part-spec-rtx-5090-class, build-rule-hard-gpu-class-selection, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | PUBLIC_SEARCH | public-002 | yes | yes | 312 | 3 | 10 | - | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | PUBLIC_SEARCH | public-003 | yes | yes | 288 | 3 | 10 | - | internal-rule-build-qhd-gaming-gpu-priority, support-guide-gpu-thermal-frame-drop, build-rule-cpu-gpu-balance-and-bottleneck |  |
| vector-on | PUBLIC_SEARCH | public-004 | yes | yes | 307 | 3 | 10 | - | internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom, as-guide-power-instability |  |
| vector-on | PUBLIC_SEARCH | public-005 | yes | yes | 418 | 3 | 10 | - | internal-rule-case-gpu-clearance, build-rule-airflow-cooler-case-fit, part-catalog-rtx50-tool-ready-dimensions |  |
| vector-on | PUBLIC_SEARCH | public-006 | yes | yes | 265 | 3 | 10 | - | benchmark-guide-ram-video-dev-floor, build-rule-memory-storage-workload-floor, as-guide-memory-storage-pressure |  |
| vector-on | PUBLIC_SEARCH | public-007 | yes | yes | 293 | 3 | 10 | - | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu |  |
| vector-on | PUBLIC_SEARCH | public-008 | yes | yes | 287 | 3 | 10 | - | as-guide-driver-crash-event-log, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| vector-on | PUBLIC_SEARCH | public-009 | yes | yes | 326 | 3 | 10 | - | as-guide-memory-storage-pressure, benchmark-guide-ram-video-dev-floor, build-rule-memory-storage-workload-floor |  |
| vector-on | PUBLIC_SEARCH | public-010 | yes | yes | 296 | 3 | 10 | - | as-guide-power-instability, internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom |  |
| vector-on | PUBLIC_SEARCH | public-011 | yes | yes | 380 | 3 | 10 | - | requirement-example-noise-upgrade-brand, build-rule-hard-gpu-class-selection, internal-rule-build-qhd-gaming-gpu-priority |  |
| vector-on | PUBLIC_SEARCH | public-012 | no | yes | 353 | 3 | 10 | - | benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai, build-rule-memory-storage-workload-floor |  |
| vector-on | PUBLIC_SEARCH | public-013 | yes | yes | 255 | 3 | 10 | - | requirement-example-gaming-resolution-refresh, internal-rule-build-qhd-gaming-gpu-priority, support-guide-gpu-thermal-frame-drop |  |
| vector-on | PUBLIC_SEARCH | public-014 | yes | yes | 285 | 3 | 10 | - | price-guide-saved-snapshot-first, build-rule-saved-price-and-psu-headroom, internal-rule-build-qhd-gaming-gpu-priority |  |
| vector-on | PUBLIC_SEARCH | public-015 | yes | yes | 327 | 3 | 10 | - | part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin, part-spec-rtx-5090-class |  |
| vector-on | PUBLIC_SEARCH | public-016 | yes | yes | 392 | 3 | 10 | - | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, support-guide-airflow-upgrade-before-gpu |  |
| vector-on | PUBLIC_SEARCH | public-017 | yes | yes | 305 | 3 | 10 | - | support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop, as-guide-gpu-thermal-frame-drop |  |
| vector-on | PUBLIC_SEARCH | public-018 | no | yes | 273 | 3 | 10 | - | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | PUBLIC_SEARCH | public-019 | yes | yes | 277 | 3 | 10 | - | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, support-guide-airflow-upgrade-before-gpu |  |
| vector-on | PUBLIC_SEARCH | public-020 | yes | yes | 294 | 3 | 10 | - | benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai, internal-rule-build-qhd-gaming-gpu-priority |  |

## Failure Summary

| variant | purpose | bucket | failed | cases |
|---|---|---|---:|---|
| vector-off | AS_ANALYZE | empty-result | 49 | as-001, as-002, as-003, as-004, as-005, as-006, as-007, as-008, as-009, as-010, as-011, as-012, +37 more |
| vector-off | BUILD_EXPLAIN | empty-result | 10 | explain-001, explain-002, explain-003, explain-004, explain-005, explain-006, explain-007, explain-008, explain-009, explain-010 |
| vector-off | BUILD_RECOMMEND | empty-result | 20 | build-001, build-002, build-003, build-004, build-005, build-006, build-007, build-008, build-009, build-010, build-011, build-012, +8 more |
| vector-off | PUBLIC_SEARCH | empty-result | 19 | public-002, public-003, public-004, public-005, public-006, public-007, public-008, public-009, public-010, public-011, public-012, public-013, +7 more |
| vector-off | PUBLIC_SEARCH | public-search | 1 | public-001 |
| vector-off | REQUIREMENT_PARSE | empty-result | 90 | req-001, req-002, req-003, req-004, req-005, req-006, req-007, req-008, req-009, req-010, req-011, req-012, +78 more |
| vector-on | AS_ANALYZE | as-symptom | 1 | as-050 |
| vector-on | BUILD_RECOMMEND | saved-price-policy | 1 | build-015 |
| vector-on | PUBLIC_SEARCH | public-search | 1 | public-001 |
| vector-on | REQUIREMENT_PARSE | empty-result | 1 | req-006 |
| vector-on | REQUIREMENT_PARSE | game-resolution | 1 | req-054 |
| vector-on | REQUIREMENT_PARSE | workload-followup | 1 | req-015 |

### Failed Cases

| variant | purpose | case | bucket | query | topSources |
|---|---|---|---|---|---|
| vector-off | REQUIREMENT_PARSE | req-001 | empty-result | 끝판왕 컴퓨터 만들어줘 | - |
| vector-off | REQUIREMENT_PARSE | req-002 | empty-result | 돈 상관없이 제일 좋은 PC로 맞춰줘 | - |
| vector-off | REQUIREMENT_PARSE | req-003 | empty-result | 예산 무관 플래그십 게임용 본체 | - |
| vector-off | REQUIREMENT_PARSE | req-004 | empty-result | 최고사양으로 아무거나 타협 없이 | - |
| vector-off | REQUIREMENT_PARSE | req-005 | empty-result | 하이엔드 감성으로 제일 좋은 구성 | - |
| vector-off | REQUIREMENT_PARSE | req-006 | empty-result | 200만원으로 최고사양 느낌 내줘 | - |
| vector-off | REQUIREMENT_PARSE | req-007 | empty-result | 300만원 안에서 끝판왕처럼 맞춰줘 | - |
| vector-off | REQUIREMENT_PARSE | req-008 | empty-result | 250만원 예산으로 제일 좋은 게임용 PC | - |
| vector-off | REQUIREMENT_PARSE | req-009 | empty-result | 예산 180만원 하이엔드 감성 본체 | - |
| vector-off | REQUIREMENT_PARSE | req-010 | empty-result | 200으로 최고급 게임용 맞춰줘 | - |
| vector-off | REQUIREMENT_PARSE | req-011 | empty-result | 5090 글카 들어간 PC 추천해줘 | - |
| vector-off | REQUIREMENT_PARSE | req-012 | empty-result | RTX 5090급 하이엔드 본체 | - |
| vector-off | REQUIREMENT_PARSE | req-013 | empty-result | 5090 넣고 예산은 나중에 정할게 | - |
| vector-off | REQUIREMENT_PARSE | req-014 | empty-result | RTX 5090 넣고 300만원 이하로 | - |
| vector-off | REQUIREMENT_PARSE | req-015 | empty-result | 최상급 그래픽카드 포함하되 400 안쪽 | - |
| vector-off | REQUIREMENT_PARSE | req-016 | empty-result | QHD 배그 144Hz 옵션 맞춰줘 | - |
| vector-off | REQUIREMENT_PARSE | req-017 | empty-result | 배틀그라운드 qhd 고주사율용 | - |
| vector-off | REQUIREMENT_PARSE | req-018 | empty-result | FHD 240hz FPS 게임 위주 | - |
| vector-off | REQUIREMENT_PARSE | req-019 | empty-result | 4K 로스트아크용 PC | - |
| vector-off | REQUIREMENT_PARSE | req-020 | empty-result | qhd 옵션 타협 없이 게임할 컴퓨터 | - |
| vector-off | REQUIREMENT_PARSE | req-021 | empty-result | 발로란트 FHD 고주사율 세팅 | - |
| vector-off | REQUIREMENT_PARSE | req-022 | empty-result | 오버워치2 QHD 165Hz 목표 | - |
| vector-off | REQUIREMENT_PARSE | req-023 | empty-result | 사이버펑크 4K RT 옵션 욕심 | - |
| vector-off | REQUIREMENT_PARSE | req-024 | empty-result | 게임용인데 모니터 해상도 아직 모름 | - |
| vector-off | REQUIREMENT_PARSE | req-025 | empty-result | 개발 IDE Docker 컴파일용 | - |
| vector-off | REQUIREMENT_PARSE | req-026 | empty-result | 프리미어 영상 편집 PC | - |
| vector-off | REQUIREMENT_PARSE | req-027 | empty-result | 블렌더 3D 작업용 컴퓨터 | - |
| vector-off | REQUIREMENT_PARSE | req-028 | empty-result | 로컬 AI CUDA 실험용 | - |
| vector-off | REQUIREMENT_PARSE | req-029 | empty-result | 게임이랑 개발 같이 할 컴퓨터 | - |
| vector-off | REQUIREMENT_PARSE | req-030 | empty-result | 프리미어랑 게임 둘 다 QHD | - |
| vector-off | REQUIREMENT_PARSE | req-031 | empty-result | 다빈치 리졸브 편집과 게임 겸용 | - |
| vector-off | REQUIREMENT_PARSE | req-032 | empty-result | LLM 실험이랑 쿠다 작업 위주 | - |
| vector-off | REQUIREMENT_PARSE | req-033 | empty-result | 컴파일 빠르고 도커 여러 개 돌릴 개발 PC | - |
| vector-off | REQUIREMENT_PARSE | req-034 | empty-result | 조용한 PC로 맞춰줘 | - |
| vector-off | REQUIREMENT_PARSE | req-035 | empty-result | 저소음으로 밤에 켜둘 본체 | - |
| vector-off | REQUIREMENT_PARSE | req-036 | empty-result | 오래 쓸 업그레이드 여유 있는 구성 | - |
| vector-off | REQUIREMENT_PARSE | req-037 | empty-result | NVIDIA 선호하는 게임용 | - |
| vector-off | REQUIREMENT_PARSE | req-038 | empty-result | 라데온 싫고 엔비디아로 | - |
| vector-off | REQUIREMENT_PARSE | req-039 | empty-result | 인텔 선호 개발용 | - |
| vector-off | REQUIREMENT_PARSE | req-040 | empty-result | 화이트 감성에 조용한 PC | - |
| vector-off | REQUIREMENT_PARSE | req-041 | empty-result | 밤새 켜둘 조용한 개발 PC | - |
| vector-off | REQUIREMENT_PARSE | req-042 | empty-result | 향후 그래픽카드 업그레이드 여유 | - |
| vector-off | REQUIREMENT_PARSE | req-043 | empty-result | 예산은 아직 없고 QHD 게임과 개발 | - |
| vector-off | REQUIREMENT_PARSE | req-044 | empty-result | 용도는 게임인데 예산 미정 | - |
| vector-off | REQUIREMENT_PARSE | req-045 | empty-result | 가격은 나중에, 해상도는 qhd | - |
| vector-off | REQUIREMENT_PARSE | req-046 | empty-result | 브랜드는 상관없고 성능만 | - |
| vector-off | REQUIREMENT_PARSE | req-047 | empty-result | 기존 컴퓨터보다 업그레이드 체감 좋게 | - |
| vector-off | REQUIREMENT_PARSE | req-048 | empty-result | 소음에 민감한데 예산은 아직 없음 | - |
| vector-off | REQUIREMENT_PARSE | req-049 | empty-result | 업그레이드 여유 때문에 예산 올려도 돼? | - |
| vector-off | REQUIREMENT_PARSE | req-050 | empty-result | 조용하고 오래 쓸 사무 개발용 | - |
| vector-off | REQUIREMENT_PARSE | req-051 | empty-result | 메모리 많이 쓰는 영상 편집용 | - |
| vector-off | REQUIREMENT_PARSE | req-052 | empty-result | ai 이미지 생성용 그래픽카드 좋은 PC | - |
| vector-off | REQUIREMENT_PARSE | req-053 | empty-result | 쿠다 되는 엔비디아 위주로 | - |
| vector-off | REQUIREMENT_PARSE | req-054 | empty-result | qhd 배그랑 개발 ide 같이 쓸 조용한 PC | - |
| vector-off | REQUIREMENT_PARSE | req-055 | empty-result | 로스트아크 4K랑 영상 편집 | - |
| vector-off | REQUIREMENT_PARSE | req-056 | empty-result | 피파와 롤은 가볍게 개발은 무겁게 | - |
| vector-off | REQUIREMENT_PARSE | req-057 | empty-result | 게임방송 송출용 PC | - |
| vector-off | REQUIREMENT_PARSE | req-058 | empty-result | 3D 렌더링과 언리얼 개발 | - |
| vector-off | REQUIREMENT_PARSE | req-059 | empty-result | 프레임 방어 잘되는 FPS용 | - |
| vector-off | REQUIREMENT_PARSE | req-060 | empty-result | 144hz 목표인데 예산 안 정함 | - |
| vector-off | REQUIREMENT_PARSE | req-061 | empty-result | 예산 500으로 5090급 하이엔드 | - |
| vector-off | REQUIREMENT_PARSE | req-062 | empty-result | 가격 상관없고 5090과 9950X급 | - |
| vector-off | REQUIREMENT_PARSE | req-063 | empty-result | 가성비 말고 최고급 부품만 | - |
| vector-off | REQUIREMENT_PARSE | req-064 | empty-result | 150만원 안에서 최대한 좋은 게임용 | - |
| vector-off | REQUIREMENT_PARSE | req-065 | empty-result | 예산 120만원 사무 학습 개발 | - |
| vector-off | REQUIREMENT_PARSE | req-066 | empty-result | 예산 없이 저소음 영상편집 | - |
| vector-off | REQUIREMENT_PARSE | req-067 | empty-result | 브랜드는 ASUS 위주였으면 | - |
| vector-off | REQUIREMENT_PARSE | req-068 | empty-result | AMD 싫고 인텔 엔비디아 조합 | - |
| vector-off | REQUIREMENT_PARSE | req-069 | empty-result | 미니타워 작은 케이스로 | - |
| vector-off | REQUIREMENT_PARSE | req-070 | empty-result | 램은 넉넉하고 저장공간도 넉넉하게 | - |
| vector-off | REQUIREMENT_PARSE | req-071 | empty-result | 초보라 질문해서 맞춰가고 싶음 | - |
| vector-off | REQUIREMENT_PARSE | req-072 | empty-result | 해상도 모르겠고 배그 옵션 맞춰줘 | - |
| vector-off | REQUIREMENT_PARSE | req-073 | empty-result | 개발자용인데 가끔 게임 | - |
| vector-off | REQUIREMENT_PARSE | req-074 | empty-result | 프로그래밍 Docker 하고 QHD 게임 | - |
| vector-off | REQUIREMENT_PARSE | req-075 | empty-result | 시끄럽지 않고 업그레이드 쉬운 케이스 | - |
| vector-off | REQUIREMENT_PARSE | req-076 | empty-result | 밤에 녹음도 해서 팬소음 적게 | - |
| vector-off | REQUIREMENT_PARSE | req-077 | empty-result | 가격은 중간이고 성능은 높게 | - |
| vector-off | REQUIREMENT_PARSE | req-078 | empty-result | 300 아래로 4K 게임은 욕심일까 | - |
| vector-off | REQUIREMENT_PARSE | req-079 | empty-result | 돈 상관없지만 조용해야 함 | - |
| vector-off | REQUIREMENT_PARSE | req-080 | empty-result | 최고급인데 라데온은 제외 | - |
| vector-off | REQUIREMENT_PARSE | req-081 | empty-result | 작업용 워크스테이션 느낌으로 | - |
| vector-off | REQUIREMENT_PARSE | req-082 | empty-result | 영상편집하면서 크롬 많이 띄움 | - |
| vector-off | REQUIREMENT_PARSE | req-083 | empty-result | 언리얼 엔진 빌드 빠르게 | - |
| vector-off | REQUIREMENT_PARSE | req-084 | empty-result | AI 학습은 아니고 AI 실험 조금 | - |
| vector-off | REQUIREMENT_PARSE | req-085 | empty-result | 로컬 LLM 돌려보고 싶음 | - |
| vector-off | REQUIREMENT_PARSE | req-086 | empty-result | 게임 성능보다는 렌더링 성능 | - |
| vector-off | REQUIREMENT_PARSE | req-087 | empty-result | GPU는 엔비디아로 꼭 넣어줘 | - |
| vector-off | REQUIREMENT_PARSE | req-088 | empty-result | 라데온 말고 지포스로 부탁 | - |
| vector-off | REQUIREMENT_PARSE | req-089 | empty-result | 소음은 상관없고 성능 우선 | - |
| vector-off | REQUIREMENT_PARSE | req-090 | empty-result | 모니터는 qhd인데 정확한 예산은 없음 | - |
| vector-off | BUILD_RECOMMEND | build-001 | empty-result | QHD 게임 추천에서 GPU를 먼저 봐야 하나 | - |
| vector-off | BUILD_RECOMMEND | build-002 | empty-result | QHD 배그 144Hz용 추천 근거 | - |
| vector-off | BUILD_RECOMMEND | build-003 | empty-result | RTX 50 시리즈 크기와 전력 스펙 저장 여부 | - |
| vector-off | BUILD_RECOMMEND | build-004 | empty-result | 5090 글카는 내부 자산 스펙이 있나 | - |
| vector-off | BUILD_RECOMMEND | build-005 | empty-result | 파워 용량 여유와 ATX 3.1 확인 | - |
| vector-off | BUILD_RECOMMEND | build-006 | empty-result | RTX 50 빌드 파워 헤드룸 검증 | - |
| vector-off | BUILD_RECOMMEND | build-007 | empty-result | CPU GPU 병목 위험 설명 | - |
| vector-off | BUILD_RECOMMEND | build-008 | empty-result | 플래그십 GPU에 약한 CPU 조합 위험 | - |
| vector-off | BUILD_RECOMMEND | build-009 | empty-result | 개발 편집 3D에는 RAM 몇 GB가 바닥인가 | - |
| vector-off | BUILD_RECOMMEND | build-010 | empty-result | AI 작업용 RAM과 NVMe 저장장치 기준 | - |
| vector-off | BUILD_RECOMMEND | build-011 | empty-result | 고성능 빌드는 케이스 쿨링 확인 필요 | - |
| vector-off | BUILD_RECOMMEND | build-012 | empty-result | 컴팩트 케이스에 5090 넣어도 되나 | - |
| vector-off | BUILD_RECOMMEND | build-013 | empty-result | 사용자 요청 중 외부 API를 매번 치면 안 되는 가격 기준 | - |
| vector-off | BUILD_RECOMMEND | build-014 | empty-result | 추천 가격은 저장된 현재가와 스냅샷 우선 | - |
| vector-off | BUILD_RECOMMEND | build-015 | empty-result | QHD 게임과 가격 스냅샷 근거 | - |
| vector-off | BUILD_RECOMMEND | build-016 | empty-result | 고주사율 게임은 CPU GPU 균형 필요 | - |
| vector-off | BUILD_RECOMMEND | build-017 | empty-result | 영상편집은 32GB 이상 RAM 추천 근거 | - |
| vector-off | BUILD_RECOMMEND | build-018 | empty-result | PSU 전력 여유가 좁으면 WARN | - |
| vector-off | BUILD_RECOMMEND | build-019 | empty-result | 케이스 GPU 길이와 쿨러 높이 확인 | - |
| vector-off | BUILD_RECOMMEND | build-020 | empty-result | 저장된 parts.price 기준으로 견적 합산 | - |
| vector-off | BUILD_EXPLAIN | explain-001 | empty-result | 케이스에 GPU가 들어가는지 설명 | - |
| vector-off | BUILD_EXPLAIN | explain-002 | empty-result | GPU 길이와 슬롯 두께 비교 | - |
| vector-off | BUILD_EXPLAIN | explain-003 | empty-result | RAM 64GB 업그레이드 이유 | - |
| vector-off | BUILD_EXPLAIN | explain-004 | empty-result | 영상 편집 개발 AI 작업 RAM 근거 | - |
| vector-off | BUILD_EXPLAIN | explain-005 | empty-result | 현재가와 가격 스냅샷 기준 설명 | - |
| vector-off | BUILD_EXPLAIN | explain-006 | empty-result | 외부 가격 조회는 관리자 스케줄에서 해야 한다 | - |
| vector-off | BUILD_EXPLAIN | explain-007 | empty-result | 조립할 때 케이스 공기 흐름 확인 | - |
| vector-off | BUILD_EXPLAIN | explain-008 | empty-result | 대형 그래픽카드와 CPU 쿨러 간섭 | - |
| vector-off | BUILD_EXPLAIN | explain-009 | empty-result | 메모리 압박이 있으면 RAM 증설 근거 | - |
| vector-off | BUILD_EXPLAIN | explain-010 | empty-result | 가격 근거는 네이버를 요청마다 치지 않음 | - |
| vector-off | AS_ANALYZE | as-001 | empty-result | 게임 20분 뒤 프레임 급락 GPU 온도 높음 | - |
| vector-off | AS_ANALYZE | as-002 | empty-result | GPU 온도 95도 팬 소음 증가 | - |
| vector-off | AS_ANALYZE | as-003 | empty-result | 프레임 타임 튐과 렉이 같이 발생 | - |
| vector-off | AS_ANALYZE | as-004 | empty-result | 먼지 청소 후에도 게임 중 열이 심함 | - |
| vector-off | AS_ANALYZE | as-005 | empty-result | 케이스 팬 소음 증가 후 프레임 드랍 | - |
| vector-off | AS_ANALYZE | as-006 | empty-result | 온도는 높은데 GPU 교체해야 하나 | - |
| vector-off | AS_ANALYZE | as-007 | empty-result | GPU는 충분한데 발열 때문에 업그레이드 고민 | - |
| vector-off | AS_ANALYZE | as-008 | empty-result | 쿨링 확인 전 비싼 GPU 추천하면 안 될 때 | - |
| vector-off | AS_ANALYZE | as-009 | empty-result | 화면 멈춤 블루스크린 발생 | - |
| vector-off | AS_ANALYZE | as-010 | empty-result | nvlddmkm 드라이버 오류 반복 | - |
| vector-off | AS_ANALYZE | as-011 | empty-result | display driver stopped 이벤트 로그 | - |
| vector-off | AS_ANALYZE | as-012 | empty-result | 게임이 자꾸 튕기고 드라이버 오류 | - |
| vector-off | AS_ANALYZE | as-013 | empty-result | 그래픽 드라이버 롤백이 필요한지 | - |
| vector-off | AS_ANALYZE | as-014 | empty-result | 화면이 멈췄다가 복구되고 로그가 남음 | - |
| vector-off | AS_ANALYZE | as-015 | empty-result | 렌더링 느림 RAM 90퍼센트 사용 | - |
| vector-off | AS_ANALYZE | as-016 | empty-result | IDE 멈춤 크롬 탭 많음 | - |
| vector-off | AS_ANALYZE | as-017 | empty-result | 디스크 100퍼센트 게임 로딩 지연 | - |
| vector-off | AS_ANALYZE | as-018 | empty-result | SSD 병목처럼 로딩이 너무 느림 | - |
| vector-off | AS_ANALYZE | as-019 | empty-result | 메모리 부족으로 작업이 멈칫함 | - |
| vector-off | AS_ANALYZE | as-020 | empty-result | 저장장치 큐가 길고 게임 설치 후 느림 | - |
| vector-off | AS_ANALYZE | as-022 | empty-result | 고사양 게임에서만 전원이 꺼짐 파워 부족 의심 | - |
| vector-off | AS_ANALYZE | as-023 | empty-result | 전원 꺼짐과 이벤트 로그 확인 | - |
| vector-off | AS_ANALYZE | as-024 | empty-result | 파워 커넥터 문제일 수 있나요 | - |
| vector-off | AS_ANALYZE | as-025 | empty-result | 고부하 순간에만 PC가 다운됩니다 | - |
| vector-off | AS_ANALYZE | as-026 | empty-result | RTX 5090 사용 중 파워 부족 의심 | - |
| vector-off | AS_ANALYZE | as-027 | empty-result | 온도와 드라이버 오류가 같이 있습니다 | - |
| vector-off | AS_ANALYZE | as-028 | empty-result | 게임 30분 후 프레임이 떨어지고 화면 멈춤 | - |
| vector-off | AS_ANALYZE | as-029 | empty-result | 팬 속도가 올라가고 렉이 발생 | - |
| vector-off | AS_ANALYZE | as-030 | empty-result | 먼지 때문에 흡기가 막힌 것 같음 | - |
| vector-off | AS_ANALYZE | as-031 | empty-result | 쿨러 팬 방향 확인해야 하나 | - |
| vector-off | AS_ANALYZE | as-032 | empty-result | GPU 바꾸기 전에 케이스 쿨링 확인 | - |
| vector-off | AS_ANALYZE | as-033 | empty-result | 드라이버 업데이트 후 게임 튕김 | - |
| vector-off | AS_ANALYZE | as-034 | empty-result | 블루스크린과 전원 문제 구분 | - |
| vector-off | AS_ANALYZE | as-035 | empty-result | 램 사용률이 높은데 SSD도 100% | - |
| vector-off | AS_ANALYZE | as-036 | empty-result | 작업 중 백그라운드 프로세스가 많음 | - |
| vector-off | AS_ANALYZE | as-037 | empty-result | 영상 렌더링이 느려서 메모리 업그레이드 고민 | - |
| vector-off | AS_ANALYZE | as-038 | empty-result | 게임 로딩만 느리고 프레임은 괜찮음 | - |
| vector-off | AS_ANALYZE | as-039 | empty-result | 새 GPU 장착 후 전원이 꺼짐 | - |
| vector-off | AS_ANALYZE | as-040 | empty-result | 12V-2x6 케이블 연결 문제 의심 | - |
| vector-off | AS_ANALYZE | as-041 | empty-result | 프레임 드랍인데 온도는 90도 이상 | - |
| vector-off | AS_ANALYZE | as-042 | empty-result | GPU 팬이 갑자기 최대로 돌고 화면이 끊김 | - |
| vector-off | AS_ANALYZE | as-043 | empty-result | 케이스 바꾸면 발열 해결될까요 | - |
| vector-off | AS_ANALYZE | as-044 | empty-result | 먼저 드라이버를 지워야 하나요 | - |
| vector-off | AS_ANALYZE | as-045 | empty-result | 이벤트 로그에서 display driver stopped | - |
| vector-off | AS_ANALYZE | as-046 | empty-result | 램 부족하면 게임도 끊기나요 | - |
| vector-off | AS_ANALYZE | as-047 | empty-result | SSD 상태와 디스크 큐 확인 | - |
| vector-off | AS_ANALYZE | as-048 | empty-result | 파워가 부족하면 블루스크린보다 재부팅인가 | - |
| vector-off | AS_ANALYZE | as-049 | empty-result | 부하 테스트에서만 꺼짐 | - |
| vector-off | AS_ANALYZE | as-050 | empty-result | 온도 드라이버 전원 중 무엇부터 볼까요 | - |
| vector-off | PUBLIC_SEARCH | public-001 | public-search | 5090 | requirement-rule-explicit-gpu-class-hard-constraint, build-rule-hard-gpu-class-selection, requirement-counterexample-explicit-gpu-with-user-budget |
| vector-off | PUBLIC_SEARCH | public-002 | empty-result | 끝판왕 OPEN_BUDGET | - |
| vector-off | PUBLIC_SEARCH | public-003 | empty-result | QHD gaming GPU priority | - |
| vector-off | PUBLIC_SEARCH | public-004 | empty-result | PSU headroom ATX 3.1 | - |
| vector-off | PUBLIC_SEARCH | public-005 | empty-result | GPU clearance maxGpuLengthMm | - |
| vector-off | PUBLIC_SEARCH | public-006 | empty-result | RAM 64GB video editing | - |
| vector-off | PUBLIC_SEARCH | public-007 | empty-result | GPU thermal frame drop | - |
| vector-off | PUBLIC_SEARCH | public-008 | empty-result | nvlddmkm driver event log | - |
| vector-off | PUBLIC_SEARCH | public-009 | empty-result | disk 100 RAM pressure | - |
| vector-off | PUBLIC_SEARCH | public-010 | empty-result | power reboot PSU connector | - |
| vector-off | PUBLIC_SEARCH | public-011 | empty-result | NVIDIA vendor preference | - |
| vector-off | PUBLIC_SEARCH | public-012 | empty-result | Docker compile development | - |
| vector-off | PUBLIC_SEARCH | public-013 | empty-result | FHD 240hz FPS | - |
| vector-off | PUBLIC_SEARCH | public-014 | empty-result | saved price snapshots | - |
| vector-off | PUBLIC_SEARCH | public-015 | empty-result | RTX 50 dimensions wattage connector | - |
| vector-off | PUBLIC_SEARCH | public-016 | empty-result | CPU GPU balance bottleneck | - |
| vector-off | PUBLIC_SEARCH | public-017 | empty-result | airflow before GPU upgrade | - |
| vector-off | PUBLIC_SEARCH | public-018 | empty-result | missing budget should not infer | - |
| vector-off | PUBLIC_SEARCH | public-019 | empty-result | quiet low-noise upgrade headroom | - |
| vector-off | PUBLIC_SEARCH | public-020 | empty-result | gaming and development workload ratio | - |
| vector-on | REQUIREMENT_PARSE | req-006 | empty-result | 200만원으로 최고사양 느낌 내줘 | - |
| vector-on | REQUIREMENT_PARSE | req-015 | workload-followup | 최상급 그래픽카드 포함하되 400 안쪽 | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |
| vector-on | REQUIREMENT_PARSE | req-054 | game-resolution | qhd 배그랑 개발 ide 같이 쓸 조용한 PC | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-example-noise-upgrade-brand |
| vector-on | BUILD_RECOMMEND | build-015 | saved-price-policy | QHD 게임과 가격 스냅샷 근거 | price-guide-saved-snapshot-first, internal-rule-build-qhd-gaming-gpu-priority, build-rule-saved-price-and-psu-headroom |
| vector-on | AS_ANALYZE | as-050 | as-symptom | 온도 드라이버 전원 중 무엇부터 볼까요 | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |
| vector-on | PUBLIC_SEARCH | public-001 | public-search | 5090 | part-spec-rtx-5090-class, build-rule-hard-gpu-class-selection, requirement-rule-explicit-gpu-class-hard-constraint |

## Policy Reading Guide

- `topKHitRate`가 vector-off와 2%p 미만 차이면 해당 경로는 latency를 보고 끌 후보가 된다.
- `REQUIREMENT_PARSE`와 `BUILD_RECOMMEND`는 5090, 끝판왕, 예산 표현 같은 의미 검색 실패 감소를 우선한다.
- `AS_ANALYZE`는 thermal, driver, memory, storage, power 증상 근거가 top3 안에 들어오는지를 우선한다.
- 이 보고서는 기본 env를 바꾸지 않고 다음 PR의 정책 판단 근거로만 사용한다.
