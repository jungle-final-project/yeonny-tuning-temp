# Build Chat AI Profile Benchmark

- generatedAt: 2026-07-02T09:12:56
- totalCases: 72

## Summary

- slowThresholdMs: 10000

| variant | profile | successRate | avgLatencyMs | p95LatencyMs | maxLatencyMs | slowCases | slowOkRate | schemaValidRate | directionOkRate | categoryOkRate | actionPayloadOkRate |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | 100.0% | 3669 | 4763 | 9702 | 0 | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% |

## Worst Latency Cases

| variant | profile | case | repeat | latencyMs | ok | slow |
|---|---|---|---:|---:|---:|---:|
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-more | 2 | 9702 | yes | no |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-similar | 1 | 5655 | yes | no |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 1 | 5224 | yes | no |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-similar | 2 | 4763 | yes | no |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-more | 3 | 4695 | yes | no |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-more | 1 | 4609 | yes | no |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-similar | 3 | 4579 | yes | no |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-more | 1 | 4321 | yes | no |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-similar | 3 | 4268 | yes | no |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-similar | 1 | 4260 | yes | no |

## Cases

| variant | profile | case | repeat | ok | latencyMs | answerType | builds | actions | hardConstraint | categoryOk | directionOk | forbiddenOk | actionPayloadOk | warningOk | error |
|---|---|---|---:|---:|---:|---|---:|---|---:|---:|---:|---:|---:|---:|---|
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 1 | yes | 5224 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 2 | yes | 3182 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 3 | yes | 3586 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-cheap | 1 | yes | 3295 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-cheap | 2 | yes | 3209 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-cheap | 3 | yes | 2981 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-similar | 1 | yes | 5655 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-similar | 2 | yes | 3573 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-similar | 3 | yes | 4063 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-more | 1 | yes | 4321 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-more | 2 | yes | 9702 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-more | 3 | yes | 3229 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-cheap | 1 | yes | 3577 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-cheap | 2 | yes | 3086 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-cheap | 3 | yes | 3342 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-similar | 1 | yes | 3364 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-similar | 2 | yes | 4763 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-similar | 3 | yes | 4226 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-more | 1 | yes | 3503 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-more | 2 | yes | 3833 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-more | 3 | yes | 3094 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-cheap | 1 | yes | 3171 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-cheap | 2 | yes | 3132 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-cheap | 3 | yes | 2993 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-similar | 1 | yes | 3772 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-similar | 2 | yes | 3398 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-similar | 3 | yes | 3787 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-more | 1 | yes | 4609 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-more | 2 | yes | 3447 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-more | 3 | yes | 4695 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-cheap | 1 | yes | 2963 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-cheap | 2 | yes | 3070 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-cheap | 3 | yes | 3183 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-similar | 1 | yes | 3283 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-similar | 2 | yes | 3477 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-similar | 3 | yes | 4579 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-more | 1 | yes | 3235 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-more | 2 | yes | 3393 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-more | 3 | yes | 3816 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-cheap | 1 | yes | 3474 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-cheap | 2 | yes | 2983 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-cheap | 3 | yes | 3123 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-similar | 1 | yes | 4260 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-similar | 2 | yes | 3662 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-similar | 3 | yes | 4268 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-more | 1 | yes | 3688 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-more | 2 | yes | 4140 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-more | 3 | yes | 3430 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-cheap | 1 | yes | 3891 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-cheap | 2 | yes | 3340 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-cheap | 3 | yes | 3160 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-similar | 1 | yes | 3568 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-similar | 2 | yes | 3450 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-similar | 3 | yes | 3730 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-case-more | 1 | yes | 3143 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-case-more | 2 | yes | 3437 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-case-more | 3 | yes | 3143 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-case-cheap | 1 | yes | 3264 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-case-cheap | 2 | yes | 3650 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-case-cheap | 3 | yes | 2928 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-case-similar | 1 | yes | 3177 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-case-similar | 2 | yes | 3740 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-case-similar | 3 | yes | 3142 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-more | 1 | yes | 3242 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-more | 2 | yes | 3241 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-more | 3 | yes | 3185 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-cheap | 1 | yes | 3688 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-cheap | 2 | yes | 3182 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-cheap | 3 | yes | 3102 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-similar | 1 | yes | 3893 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-similar | 2 | yes | 3796 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |
| direction-repeat3 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-similar | 3 | yes | 4229 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes |  |

## Notes

- 이 벤치마크는 UI를 변경하지 않고 `/api/ai/build-chat`의 optional profile header만 바꿔 실행한다.
- 기본 서비스 profile은 현재 `BUILD_CHAT_54_MINI_FAST`이며, 모델 비교가 필요하면 `--profiles`로 후보를 명시한다.
- 5090 같은 명시 부품 조건은 추천 build의 GPU item에 보존되어야 한다.
- 장바구니 교체 케이스는 반환 partId를 `/api/parts/{id}`로 다시 조회해 현재 부품 대비 상향/하향/유사 가격 방향을 검증한다.
