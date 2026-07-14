# Build Chat 상태형 웹 재현 감사

- 모델 profile: `BUILD_CHAT_54_MINI_FAST`
- 결과: PASS 20 / FAIL 0

| case | 그룹 | 결과 | 턴 | 오류 |
|---|---|---|---:|---|
| state-compat-01-b860-cpu-top3 | COMPATIBILITY_BACKFILL | PASS | 3 | - |
| state-compat-02-am5-cpu-top3 | COMPATIBILITY_BACKFILL | PASS | 3 | - |
| state-compat-03-b860-reject-am5 | COMPATIBILITY_BACKFILL | PASS | 3 | - |
| state-compat-04-am5-board-top3 | COMPATIBILITY_BACKFILL | PASS | 3 | - |
| state-quantity-02-ram-exact-add | CURRENT_PART_QUANTITY | PASS | 3 | - |
| state-quantity-03-ram-increment | CURRENT_PART_QUANTITY | PASS | 3 | - |
| state-quantity-07-storage-exact-add | CURRENT_PART_QUANTITY | PASS | 3 | - |
| state-context-01-generic-build | CLARIFICATION_CONTEXT | PASS | 3 | - |
| state-context-04-part-generic | CLARIFICATION_CONTEXT | PASS | 3 | - |
| state-alias-01-exact-case-frame | EXACT_ALIAS_AMBIGUITY | PASS | 3 | - |
| state-alias-02-exact-cpu | EXACT_ALIAS_AMBIGUITY | PASS | 3 | - |
| state-alias-04-gpu-series-ambiguous | EXACT_ALIAS_AMBIGUITY | PASS | 3 | - |
| state-direction-01-gpu-up | DIRECTION_IMPROVEMENT | PASS | 3 | - |
| state-direction-02-gpu-down | DIRECTION_IMPROVEMENT | PASS | 3 | - |
| state-direction-09-case-clearance | DIRECTION_IMPROVEMENT | PASS | 3 | - |
| state-budget-01-target-200 | BUDGET_HARD_COUNTER | PASS | 3 | - |
| state-budget-05-tiny-ai | BUDGET_HARD_COUNTER | PASS | 3 | - |
| state-budget-07-hard-5090-max | BUDGET_HARD_COUNTER | PASS | 3 | - |
| state-readonly-01-gpu-fps | SIMULATION_EXPLANATION | PASS | 3 | - |
| state-readonly-07-score-explain | SIMULATION_EXPLANATION | PASS | 3 | - |
