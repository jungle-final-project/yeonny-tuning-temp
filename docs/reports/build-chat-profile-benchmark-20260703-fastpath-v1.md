# Build Chat AI 프로필 벤치마크

- 생성 시각: 2026-07-03T05:47:31
- 총 테스트 수: 24

## 요약

- 느린 응답 기준: 10000ms 이상
- successRate는 schema, 기대 answerType, action payload, 하드 조건, 방향성 검증을 모두 통과한 비율이다.
- fastPathUsedRate는 LLM/RAG trace 없이 서버 fast path로 처리된 응답 비율이다.
- llmExpectedCases는 fast path가 아니라 기존 LLM/RAG 판단이 필요한 케이스 수다.

| 실험 라벨 | 프로필 | 성공률 | 평균 지연(ms) | p95 지연(ms) | 최대 지연(ms) | 느린 케이스 | 속도 기준 통과율 | fast path 사용률 | fast path 기대 충족률 | LLM 필요 케이스 | schema 통과율 | 방향성 통과율 | 카테고리 통과율 | action payload 통과율 | 필수 문구 통과율 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | 100.0% | 16 | 32 | 35 | 0 | 100.0% | 100.0% | 100.0% | 0 | 100.0% | 100.0% | 100.0% | 100.0% | 100.0% |

## 가장 느린 케이스

| 실험 라벨 | 프로필 | 케이스 | 반복 | 지연(ms) | 성공 | 느림 |
|---|---|---|---:|---:|---:|---:|
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-cheap | 1 | 35 | yes | no |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-more | 1 | 32 | yes | no |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-cheap | 1 | 28 | yes | no |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-case-similar | 1 | 28 | yes | no |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 1 | 27 | yes | no |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-more | 1 | 27 | yes | no |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-cheap | 1 | 24 | yes | no |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-more | 1 | 20 | yes | no |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-cheap | 1 | 20 | yes | no |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-similar | 1 | 18 | yes | no |

## 케이스별 결과

| 실험 라벨 | 프로필 | 케이스 | 반복 | 성공 | 지연(ms) | answerType | 추천 빌드 수 | actions | fast path 기대 | fast path 사용 | 하드 조건 | 카테고리 | 방향성 | 금지 후보 제외 | action payload | 필수 문구 | warning 조건 | 오류 |
|---|---|---|---:|---:|---:|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-more | 1 | yes | 27 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-cheap | 1 | yes | 35 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-gpu-similar | 1 | yes | 11 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-more | 1 | yes | 20 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-cheap | 1 | yes | 28 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-cpu-similar | 1 | yes | 18 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-more | 1 | yes | 27 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-cheap | 1 | yes | 20 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-motherboard-similar | 1 | yes | 17 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-more | 1 | yes | 18 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-cheap | 1 | yes | 8 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-ram-similar | 1 | yes | 6 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-more | 1 | yes | 7 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-cheap | 1 | yes | 6 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-storage-similar | 1 | yes | 6 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-more | 1 | yes | 32 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-cheap | 1 | yes | 17 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-psu-similar | 1 | yes | 6 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-case-more | 1 | yes | 5 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-case-cheap | 1 | yes | 4 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-case-similar | 1 | yes | 28 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-more | 1 | yes | 17 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-cheap | 1 | yes | 24 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |
| fastpath-v1 | BUILD_CHAT_54_MINI_FAST | live-direction-cooler-similar | 1 | yes | 6 | PART | 0 | REPLACE_DRAFT_PART | yes | yes | yes | yes | yes | yes | yes | yes | yes |  |

## 해석 기준

- 이 벤치마크는 UI를 변경하지 않고 `/api/ai/build-chat` API를 직접 호출해 측정한다.
- 기본 서비스 profile은 현재 `BUILD_CHAT_54_MINI_FAST`이며, 모델 비교가 필요하면 `--profiles`로 후보를 명시한다.
- `5090` 같은 명시 부품 조건은 추천 build의 GPU item에 보존되어야 한다.
- 장바구니 교체 케이스는 반환 partId를 `/api/parts/{id}`로 다시 조회해 현재 부품 대비 상향/하향/유사 가격 방향을 검증한다.
- fast path 케이스는 고확신 장바구니 조작 요청을 LLM 호출 없이 처리하는지 확인하기 위한 속도 회귀 테스트다.
