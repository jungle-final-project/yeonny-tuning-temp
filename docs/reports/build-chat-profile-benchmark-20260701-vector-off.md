# Build Chat AI Profile Benchmark

- generatedAt: 2026-07-01T19:55:21
- totalCases: 15

## Summary

| variant | profile | successRate | avgLatencyMs | p95LatencyMs | schemaValidRate |
|---|---|---:|---:|---:|---:|
| vector-off | BUILD_CHAT_FAST | 100.0% | 4160 | 5467 | 100.0% |
| vector-off | BUILD_CHAT_54_FAST | 100.0% | 3813 | 4585 | 100.0% |
| vector-off | BUILD_CHAT_54_MINI_FAST | 100.0% | 2669 | 3027 | 100.0% |

## Cases

| variant | profile | case | ok | latencyMs | answerType | builds | actions | hardConstraint | warningOk | error |
|---|---|---|---:|---:|---|---:|---|---:|---:|---|
| vector-off | BUILD_CHAT_FAST | qhd-gaming-budget | yes | 2830 | BUDGET | 3 | - | yes | yes |  |
| vector-off | BUILD_CHAT_FAST | rtx-5090-hard-constraint | yes | 3396 | BUDGET | 3 | - | yes | yes |  |
| vector-off | BUILD_CHAT_FAST | rtx-5090-under-budget | yes | 3774 | BUDGET | 3 | - | yes | yes |  |
| vector-off | BUILD_CHAT_FAST | cheaper-gpu-draft-edit | yes | 5467 | PART | 0 | REPLACE_DRAFT_PART | yes | yes |  |
| vector-off | BUILD_CHAT_FAST | ram-64gb-draft-edit | yes | 5332 | PART | 0 | UPDATE_DRAFT_QUANTITY | yes | yes |  |
| vector-off | BUILD_CHAT_54_FAST | qhd-gaming-budget | yes | 2617 | BUDGET | 3 | - | yes | yes |  |
| vector-off | BUILD_CHAT_54_FAST | rtx-5090-hard-constraint | yes | 3985 | BUDGET | 3 | - | yes | yes |  |
| vector-off | BUILD_CHAT_54_FAST | rtx-5090-under-budget | yes | 3789 | BUDGET | 3 | - | yes | yes |  |
| vector-off | BUILD_CHAT_54_FAST | cheaper-gpu-draft-edit | yes | 4087 | PART | 0 | REPLACE_DRAFT_PART | yes | yes |  |
| vector-off | BUILD_CHAT_54_FAST | ram-64gb-draft-edit | yes | 4585 | PART | 0 | UPDATE_DRAFT_QUANTITY | yes | yes |  |
| vector-off | BUILD_CHAT_54_MINI_FAST | qhd-gaming-budget | yes | 2276 | BUDGET | 3 | - | yes | yes |  |
| vector-off | BUILD_CHAT_54_MINI_FAST | rtx-5090-hard-constraint | yes | 2495 | BUDGET | 3 | - | yes | yes |  |
| vector-off | BUILD_CHAT_54_MINI_FAST | rtx-5090-under-budget | yes | 2834 | BUDGET | 3 | - | yes | yes |  |
| vector-off | BUILD_CHAT_54_MINI_FAST | cheaper-gpu-draft-edit | yes | 3027 | PART | 0 | REPLACE_DRAFT_PART | yes | yes |  |
| vector-off | BUILD_CHAT_54_MINI_FAST | ram-64gb-draft-edit | yes | 2712 | PART | 0 | UPDATE_DRAFT_QUANTITY | yes | yes |  |

## Notes

- 이 벤치마크는 UI를 변경하지 않고 `/api/ai/build-chat`의 optional profile header만 바꿔 실행한다.
- 기본 서비스 profile은 별도 gate를 통과하기 전까지 `BUILD_CHAT_FAST`로 유지한다.
- 5090 같은 명시 부품 조건은 추천 build의 GPU item에 보존되어야 한다.
