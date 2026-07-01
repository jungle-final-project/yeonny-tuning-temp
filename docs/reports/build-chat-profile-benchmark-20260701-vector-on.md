# Build Chat AI Profile Benchmark

- generatedAt: 2026-07-01T20:01:40
- totalCases: 15

## Summary

| variant | profile | successRate | avgLatencyMs | p95LatencyMs | schemaValidRate |
|---|---|---:|---:|---:|---:|
| vector-on | BUILD_CHAT_FAST | 100.0% | 4318 | 5204 | 100.0% |
| vector-on | BUILD_CHAT_54_FAST | 100.0% | 3833 | 4256 | 100.0% |
| vector-on | BUILD_CHAT_54_MINI_FAST | 100.0% | 3056 | 3484 | 100.0% |

## Cases

| variant | profile | case | ok | latencyMs | answerType | builds | actions | hardConstraint | warningOk | error |
|---|---|---|---:|---:|---|---:|---|---:|---:|---|
| vector-on | BUILD_CHAT_FAST | qhd-gaming-budget | yes | 5204 | BUDGET | 3 | - | yes | yes |  |
| vector-on | BUILD_CHAT_FAST | rtx-5090-hard-constraint | yes | 4190 | BUDGET | 3 | - | yes | yes |  |
| vector-on | BUILD_CHAT_FAST | rtx-5090-under-budget | yes | 4592 | BUDGET | 3 | - | yes | yes |  |
| vector-on | BUILD_CHAT_FAST | cheaper-gpu-draft-edit | yes | 3802 | PART | 0 | REPLACE_DRAFT_PART | yes | yes |  |
| vector-on | BUILD_CHAT_FAST | ram-64gb-draft-edit | yes | 3802 | PART | 0 | UPDATE_DRAFT_QUANTITY | yes | yes |  |
| vector-on | BUILD_CHAT_54_FAST | qhd-gaming-budget | yes | 4256 | BUDGET | 3 | - | yes | yes |  |
| vector-on | BUILD_CHAT_54_FAST | rtx-5090-hard-constraint | yes | 3141 | BUDGET | 3 | - | yes | yes |  |
| vector-on | BUILD_CHAT_54_FAST | rtx-5090-under-budget | yes | 4025 | BUDGET | 3 | - | yes | yes |  |
| vector-on | BUILD_CHAT_54_FAST | cheaper-gpu-draft-edit | yes | 4052 | PART | 0 | REPLACE_DRAFT_PART | yes | yes |  |
| vector-on | BUILD_CHAT_54_FAST | ram-64gb-draft-edit | yes | 3689 | PART | 0 | UPDATE_DRAFT_QUANTITY | yes | yes |  |
| vector-on | BUILD_CHAT_54_MINI_FAST | qhd-gaming-budget | yes | 2357 | BUDGET | 3 | - | yes | yes |  |
| vector-on | BUILD_CHAT_54_MINI_FAST | rtx-5090-hard-constraint | yes | 2785 | BUDGET | 3 | - | yes | yes |  |
| vector-on | BUILD_CHAT_54_MINI_FAST | rtx-5090-under-budget | yes | 3484 | BUDGET | 3 | - | yes | yes |  |
| vector-on | BUILD_CHAT_54_MINI_FAST | cheaper-gpu-draft-edit | yes | 3447 | PART | 0 | REPLACE_DRAFT_PART | yes | yes |  |
| vector-on | BUILD_CHAT_54_MINI_FAST | ram-64gb-draft-edit | yes | 3209 | PART | 0 | UPDATE_DRAFT_QUANTITY | yes | yes |  |

## Notes

- 이 벤치마크는 UI를 변경하지 않고 `/api/ai/build-chat`의 optional profile header만 바꿔 실행한다.
- 기본 서비스 profile은 별도 gate를 통과하기 전까지 `BUILD_CHAT_FAST`로 유지한다.
- 5090 같은 명시 부품 조건은 추천 build의 GPU item에 보존되어야 한다.
