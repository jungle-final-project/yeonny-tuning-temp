# Build Chat AI 프로필 벤치마크

- 생성 시각: 2026-07-03T04:38:30
- 총 테스트 수: 66

## 요약

- 느린 응답 기준: 10000ms 이상
- 이 보고서는 fast path 적용 전 `rag-quality-v2` 기준선이다.
- successRate는 schema, 기대 answerType, action payload, 하드 조건, 방향성 검증을 모두 통과한 비율이다.

| 실험 라벨 | 프로필 | 성공률 | 평균 지연(ms) | p95 지연(ms) | 최대 지연(ms) | 느린 케이스 | 속도 기준 통과율 | schema 통과율 | 방향성 통과율 | 카테고리 통과율 | action payload 통과율 | 필수 문구 통과율 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | 100.0% | 3786 | 5002 | 15169 | 1 | 98.5% | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% |

## 가장 느린 케이스

| 실험 라벨 | 프로필 | 케이스 | 반복 | 지연(ms) | 성공 | 느림 |
|---|---|---|---:|---:|---:|---:|
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 1 | 15169 | yes | yes |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | explain-build | 1 | 7422 | yes | no |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-case-similar | 1 | 5772 | yes | no |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | cpu-part-dev | 1 | 5002 | yes | no |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | lower-budget-draft | 1 | 4565 | yes | no |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | power-safe-build | 1 | 4477 | yes | no |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | creator-ram-heavy | 1 | 4447 | yes | no |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | game-dev-mixed | 1 | 4380 | yes | no |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | cheap-gpu-no-draft | 1 | 4279 | yes | no |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | blender-3d | 1 | 4209 | yes | no |

## 케이스별 결과

| 실험 라벨 | 프로필 | 케이스 | 반복 | 성공 | 지연(ms) | answerType | 추천 빌드 수 | actions | 하드 조건 | 카테고리 | 방향성 | 금지 후보 제외 | action payload | 필수 문구 | warning 조건 | 오류 |
|---|---|---|---:|---:|---:|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---|
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | qhd-gaming-budget | 1 | yes | 3284 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | rtx-5090-hard-constraint | 1 | yes | 3060 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | rtx-5090-under-budget | 1 | yes | 3581 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | open-budget-enthusiast | 1 | yes | 3071 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | premium-with-budget | 1 | yes | 3309 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | fhd-valorant | 1 | yes | 3422 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | qhd-pubg-144 | 1 | yes | 3164 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | 4k-cyberpunk | 1 | yes | 3277 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | lostark-cpu-focus | 1 | yes | 3523 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | game-dev-mixed | 1 | yes | 4380 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | video-edit | 1 | yes | 3713 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | local-ai-cuda | 1 | yes | 3150 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | blender-3d | 1 | yes | 4209 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | quiet-night | 1 | yes | 3390 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | upgrade-headroom | 1 | yes | 3488 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | nvidia-preference | 1 | yes | 4039 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | intel-preference | 1 | yes | 3338 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | white-build | 1 | yes | 3525 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | compact-case | 1 | yes | 3597 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | storage-heavy | 1 | yes | 3493 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | rtx-5070-part | 1 | yes | 3028 | PART | 0 | ADD_PART_TO_DRAFT | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | cpu-part-dev | 1 | yes | 5002 | PART | 0 | ADD_PART_TO_DRAFT | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | psu-part-5090 | 1 | yes | 3884 | PART | 0 | ADD_PART_TO_DRAFT | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | case-part-airflow | 1 | yes | 3351 | PART | 0 | ADD_PART_TO_DRAFT | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | cooler-part-quiet | 1 | yes | 2776 | PART | 0 | ADD_PART_TO_DRAFT | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | ssd-part-fast | 1 | yes | 3368 | PART | 0 | ADD_PART_TO_DRAFT | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | ram-part-64 | 1 | yes | 3445 | PART | 0 | ADD_PART_TO_DRAFT | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | motherboard-part-am5 | 1 | yes | 3328 | PART | 0 | ADD_PART_TO_DRAFT | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | cheaper-gpu-draft-edit | 1 | yes | 2892 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | ram-64gb-draft-edit | 1 | yes | 3398 | PART | 0 | UPDATE_DRAFT_QUANTITY | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | remove-gpu-draft | 1 | yes | 2908 | PART | 0 | REMOVE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | lower-budget-draft | 1 | yes | 4565 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | replace-cooler-draft | 1 | yes | 3636 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | add-storage-draft | 1 | yes | 3430 | PART | 0 | ADD_PART_TO_DRAFT | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | price-alert-gpu | 1 | yes | 3052 | GENERAL | 0 | CREATE_PRICE_ALERT,REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | explain-build | 1 | yes | 7422 | GENERAL | 0 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | ask-followup-vague | 1 | yes | 3285 | GENERAL | 0 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | ask-followup-monitor | 1 | yes | 3468 | GENERAL | 0 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | budget-office | 1 | yes | 3508 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | creator-ram-heavy | 1 | yes | 4447 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | power-safe-build | 1 | yes | 4477 | BUDGET | 3 | - | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | cheap-gpu-no-draft | 1 | yes | 4279 | PART | 0 | ADD_PART_TO_DRAFT | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 1 | yes | 15169 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-cheap | 1 | yes | 3198 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-similar | 1 | yes | 3852 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-more | 1 | yes | 3076 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-cheap | 1 | yes | 4083 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-similar | 1 | yes | 3399 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-more | 1 | yes | 3308 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-cheap | 1 | yes | 2972 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-similar | 1 | yes | 3730 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-more | 1 | yes | 3524 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-cheap | 1 | yes | 3116 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-similar | 1 | yes | 3570 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-more | 1 | yes | 3596 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-cheap | 1 | yes | 3280 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-similar | 1 | yes | 3602 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-more | 1 | yes | 3158 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-cheap | 1 | yes | 3144 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-similar | 1 | yes | 3716 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-case-more | 1 | yes | 3593 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-case-cheap | 1 | yes | 2962 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-case-similar | 1 | yes | 5772 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-more | 1 | yes | 3481 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-cheap | 1 | yes | 3970 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |
| rag-quality-v2 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-similar | 1 | yes | 3616 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes |  |

## 해석 기준

- 이 벤치마크는 UI를 변경하지 않고 `/api/ai/build-chat` API를 직접 호출해 측정한다.
- 기본 서비스 profile은 현재 `BUILD_CHAT_54_MINI_FAST`이며, 모델 비교가 필요하면 `--profiles`로 후보를 명시한다.
- `5090` 같은 명시 부품 조건은 추천 build의 GPU item에 보존되어야 한다.
- 장바구니 교체 케이스는 반환 partId를 `/api/parts/{id}`로 다시 조회해 현재 부품 대비 상향/하향/유사 가격 방향을 검증한다.
