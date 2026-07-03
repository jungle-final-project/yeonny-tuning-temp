# Build Chat AI 프로필 벤치마크

- 생성 시각: 2026-07-03T06:47:02
- 총 테스트 수: 21

## 요약

- 느린 응답 기준: 1000ms 이상
- successRate는 schema, 기대 answerType, action payload, 하드 조건, 방향성 검증을 모두 통과한 비율이다.
- fastPathUsedRate는 LLM/RAG trace 없이 서버 fast path로 처리된 응답 비율이다.
- llmExpectedCases는 fast path가 아니라 기존 LLM/RAG 판단이 필요한 케이스 수다.

| 실험 라벨 | 프로필 | 성공률 | 평균 지연(ms) | p95 지연(ms) | 최대 지연(ms) | 느린 케이스 | 속도 기준 통과율 | fast path 사용률 | fast path 기대 충족률 | LLM 필요 케이스 | schema 통과율 | 방향성 통과율 | 카테고리 통과율 | 금지 action 제외율 | action payload 통과율 | 필수 문구 통과율 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | 100.0% | 18 | 31 | 31 | 0 | 100.0% | 100.0% | 100.0% | 5 | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% |

## Fast Path 전용 속도

- 이 표는 `expectedFastPath=true` 또는 fast path 기대 케이스만 따로 집계한다. LLM이 필요한 추천/교체 케이스는 제외한다.

| 실험 라벨 | 프로필 | fast path 케이스 | 성공률 | 평균 지연(ms) | p95 지연(ms) | 최대 지연(ms) | fast path 사용률 |
|---|---|---:|---:|---:|---:|---:|---:|
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | 16 | 100.0% | 17 | 30 | 30 | 100.0% |

## 가장 느린 케이스

| 실험 라벨 | 프로필 | 케이스 | 반복 | 지연(ms) | 성공 | 느림 |
|---|---|---|---:|---:|---:|---:|
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-5090-build-not-route | 1 | 31 | yes | no |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-msi-board-replace-not-route | 1 | 31 | yes | no |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-ddr5-ram-ambiguous | 1 | 30 | yes | no |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-liquid-cooler-ambiguous | 1 | 30 | yes | no |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-lianli-replace-not-route | 1 | 30 | yes | no |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-9950x3d-token | 1 | 28 | yes | no |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-5090-ambiguous | 1 | 25 | yes | no |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-corsair-psu-ambiguous | 1 | 25 | yes | no |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-msi-board-ambiguous | 1 | 22 | yes | no |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-nvidia-generic-category | 1 | 21 | yes | no |

## 케이스별 결과

| 실험 라벨 | 프로필 | 케이스 | 반복 | 성공 | 지연(ms) | answerType | 추천 빌드 수 | actions | fast path 기대 | fast path 사용 | 하드 조건 | 카테고리 | 방향성 | 금지 후보 제외 | 금지 action 제외 | action payload | 필수 문구 | warning 조건 | 오류 |
|---|---|---|---:|---:|---:|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-9950x3d-token | 1 | yes | 28 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-9950x3d-full-korean | 1 | yes | 15 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-9700x-token | 1 | yes | 5 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-5090-ambiguous | 1 | yes | 25 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-rtx5080-ambiguous | 1 | yes | 5 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-ryzen9-ambiguous | 1 | yes | 4 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-msi-board-ambiguous | 1 | yes | 22 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-samsung-ssd-ambiguous | 1 | yes | 5 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-corsair-psu-ambiguous | 1 | yes | 25 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-lianli-case-ambiguous | 1 | yes | 4 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-ddr5-ram-ambiguous | 1 | yes | 30 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-liquid-cooler-ambiguous | 1 | yes | 30 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-gpu-recommend-not-route | 1 | yes | 17 | PART | 0 | ADD_PART_TO_DRAFT | no | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-5090-build-not-route | 1 | yes | 31 | BUDGET | 3 | - | no | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-lianli-replace-not-route | 1 | yes | 30 | PART | 0 | - | no | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-msi-board-replace-not-route | 1 | yes | 31 | PART | 0 | ADD_PART_TO_DRAFT | no | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-ram-change-not-route | 1 | yes | 6 | PART | 0 | ADD_PART_TO_DRAFT | no | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-nvidia-generic-category | 1 | yes | 21 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-nvme-generic-category | 1 | yes | 16 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-case-page-category | 1 | yes | 16 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| product-route-fastpath | BUILD_CHAT_54_MINI_FAST | route-detail-psu-page-category | 1 | yes | 15 | GENERAL | 0 | OPEN_ROUTE | yes | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |

## 해석 기준

- 이 벤치마크는 UI를 변경하지 않고 `/api/ai/build-chat` API를 직접 호출해 측정한다.
- 기본 서비스 profile은 현재 `BUILD_CHAT_54_MINI_FAST`이며, 모델 비교가 필요하면 `--profiles`로 후보를 명시한다.
- `5090` 같은 명시 부품 조건은 추천 build의 GPU item에 보존되어야 한다.
- 장바구니 교체 케이스는 반환 partId를 `/api/parts/{id}`로 다시 조회해 현재 부품 대비 상향/하향/유사 가격 방향을 검증한다.
- fast path 케이스는 고확신 장바구니 조작 요청을 LLM 호출 없이 처리하는지 확인하기 위한 속도 회귀 테스트다.
