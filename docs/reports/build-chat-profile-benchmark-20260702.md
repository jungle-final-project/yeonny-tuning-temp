# Build Chat AI Profile Benchmark

- generatedAt: 2026-07-02T09:07:37
- totalCases: 5

## Summary

- slowThresholdMs: 10000

| variant | profile | successRate | avgLatencyMs | p95LatencyMs | maxLatencyMs | slowCases | slowOkRate | schemaValidRate | directionOkRate | categoryOkRate | actionPayloadOkRate |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| gpu-more-repeat5 | BUILD_CHAT_54_MINI_FAST | 100.0% | 4315 | 6021 | 6021 | 0 | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% |

## Worst Latency Cases

| variant | profile | case | repeat | latencyMs | ok | slow |
|---|---|---|---:|---:|---:|---:|
| gpu-more-repeat5 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 5 | 6021 | yes | no |
| gpu-more-repeat5 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 1 | 4905 | yes | no |
| gpu-more-repeat5 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 4 | 4161 | yes | no |
| gpu-more-repeat5 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 2 | 3478 | yes | no |
| gpu-more-repeat5 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 3 | 3009 | yes | no |

## Cases

| variant | profile | case | repeat | ok | latencyMs | answerType | builds | actions | hardConstraint | categoryOk | directionOk | forbiddenOk | actionPayloadOk | warningOk | error |
|---|---|---|---:|---:|---:|---|---:|---|---:|---:|---:|---:|---:|---:|---|
| gpu-more-repeat5 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 1 | yes | 4905 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| gpu-more-repeat5 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 2 | yes | 3478 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| gpu-more-repeat5 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 3 | yes | 3009 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| gpu-more-repeat5 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 4 | yes | 4161 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| gpu-more-repeat5 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 5 | yes | 6021 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |

## Notes

- 이 벤치마크는 UI를 변경하지 않고 `/api/ai/build-chat`의 optional profile header만 바꿔 실행한다.
- 기본 서비스 profile은 현재 `BUILD_CHAT_54_MINI_FAST`이며, 모델 비교가 필요하면 `--profiles`로 후보를 명시한다.
- 5090 같은 명시 부품 조건은 추천 build의 GPU item에 보존되어야 한다.
- 장바구니 교체 케이스는 반환 partId를 `/api/parts/{id}`로 다시 조회해 현재 부품 대비 상향/하향/유사 가격 방향을 검증한다.
