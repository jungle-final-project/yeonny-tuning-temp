# Build Chat Intent Router 풀 검증 보고서

- 검증일: 2026-07-04
- 대상 브랜치: `codex/build-chat-intent-safety-matrix`
- 최신 흡수 기준: `prototype/main` `6adc481` (`Merge pull request #49`)
- 작업 시작 커밋: `1fa68d2`
- 검증 목적: `BuildChatIntentRouter`, route/action/simulation 분리, semantic cache v1이 실제 사용자 흐름에서 오탐, 자동 실행 오류, 캐시 오염, 성능 회귀를 만들지 않는지 확인

## 결론

이번 구조 변경은 최신 `prototype/main` `6adc481` 흡수 후 검증 기준을 통과했다.

- 정적 계약, OpenAPI, Gradle test, web test/build, bootJar, Docker health 모두 통과했다.
- API live 87개 케이스는 성공률 100%, schema valid 100%, route/action/direction/hard constraint 검증 100%를 기록했다.
- API live 기준 평균 지연은 0.457초, p95는 3.669초, 최대는 4.267초로 단일 요청 5초 초과가 없었다.
- 웹 체감 60개 시나리오는 모든 그룹 p95가 1초 미만이며, 단일 5초 초과가 없었다.
- semantic cache 테이블 생성과 row 저장을 Docker runtime에서 확인했다.

## 정적/계약 검증

| 항목 | 결과 | 비고 |
|---|---:|---|
| `python tools/validate_openapi.py docs/openapi.yaml` | 통과 | OpenAPI 104 paths 검증 |
| `git diff --check` | 통과 | 공백 오류 없음 |
| secret 패턴 검색 | 통과 | placeholder, 문서 예시, package-lock false positive만 확인 |
| `docker compose config` | 통과 | 로컬 env가 출력될 수 있으므로 공유 로그에서는 주의 필요 |

## Backend / Frontend 검증

| 항목 | 결과 |
|---|---:|
| `.\gradlew.bat test --no-daemon` | 통과 |
| `.\gradlew.bat bootJar --no-daemon` | 통과 |
| `npm --prefix apps/web run test` | 통과, 138 tests |
| `npm --prefix apps/web run build` | 통과 |
| `docker compose up --build -d` | 통과 |
| `GET /api/health` | 통과, database `UP`, status `UP` |
| `build_chat_semantic_cache` row count | 240 |

## API Live Benchmark

상세 결과: `docs/reports/build-chat-profile-benchmark-20260703-intent-router-cache-v1-latest-main.md`

| 지표 | 값 |
|---|---:|
| 총 케이스 | 87 |
| 프로필 | `BUILD_CHAT_54_MINI_FAST` |
| 성공률 | 100.0% |
| 평균 지연 | 0.457초 |
| p95 지연 | 3.669초 |
| 최대 지연 | 4.267초 |
| 5초 초과 | 0건 |
| schema 통과율 | 100.0% |
| 방향성 통과율 | 100.0% |
| 카테고리 통과율 | 100.0% |
| 금지 action 제외율 | 100.0% |
| action payload 통과율 | 100.0% |
| fast path 사용률 | 88.5% |
| fast path 기대 충족률 | 100.0% |

Fast path 전용 45개 케이스는 평균 0.012초, p95 0.028초, 최대 0.032초였다.

가장 느린 케이스도 기준 안에 있었다.

| 케이스 | 지연 |
|---|---:|
| `rtx-5090-hard-constraint` | 4.267초 |
| `premium-with-budget` | 4.188초 |
| `route-detail-5090-build-not-route` | 3.949초 |
| `blender-3d` | 3.758초 |
| `power-safe-build` | 3.669초 |

## 웹 체감 Latency

상세 결과: `docs/reports/web-ai-latency-20260703.md`

| 그룹 | 케이스 | 평균 | p95 | 최대 | 기준 |
|---|---:|---:|---:|---:|---:|
| `FAST_LOCAL_ROUTE` | 8 | 0.756초 | 0.888초 | 0.888초 | 1.0초 |
| `FAST_SERVER_ROUTE` | 12 | 0.717초 | 0.776초 | 0.776초 | 1.5초 |
| `DRAFT_ACTION` | 12 | 0.715초 | 0.734초 | 0.734초 | 2.0초 |
| `DETERMINISTIC_RECOMMEND` | 16 | 0.713초 | 0.819초 | 0.819초 | 3.0초 |
| `LLM_FULL_COMPLEX` | 12 | 0.699초 | 0.729초 | 0.729초 | 5.0초 |

웹 체감 기준 60개 중 실패 케이스와 5초 초과 케이스는 없었다.

## 검증 중 발견 및 수정한 잔버그

1. `NVIDIA GPU 상세 보여줘`, `수랭 쿨러 보여줘`, `5090 보여줘` 같은 애매한 상세 이동이 `/parts/{id}`로 갈 수 있는 위험을 막고 `/self-quote?category=...&q=...` 필터 route로 고정했다.
2. `메인보드 MSI 걸로 맞춰줘`, `케이스 리안리 216 모델꺼로 맞춰줘`가 route나 일반 답변으로 빠지지 않고 draft replacement action으로 처리되게 했다.
3. `영상편집 + Docker + IDE 병행용으로 400만원 안쪽`처럼 추천 동사가 없어도 예산+용도 조합이면 build recommend로 분류되게 했다.
4. deterministic 요청이 semantic cache embedding lookup 때문에 느려질 수 있어, deterministic 응답을 semantic cache lookup보다 먼저 처리하도록 조정했다.
5. 명시 예산 fallback 조합 생성에서 예산 하한/상한 pruning을 추가해 불필요한 조합 탐색을 제거했다.
6. semantic cache store는 응답 지연에 영향을 주지 않도록 async best-effort 저장으로 변경했다.
7. Redis exact cache namespace와 semantic data version hash를 올려 구조 변경 전 stale 응답 재사용을 차단했다.

## 핵심 안전성 판정

| 항목 | 판정 |
|---|---|
| route 오탐 | 통과. 애매한 상품명은 상세 자동 이동하지 않고 필터 route로 이동 |
| mutation 오탐 | 통과. simulation/설명/추천 요청은 draft mutation을 만들지 않음 |
| simulation 안전성 | 통과. simulation 응답은 `actions=[]`를 유지 |
| Tool FAIL hard drop | 통과. 장착 불가/FAIL 후보는 추천 및 자동 적용 후보에서 제외 |
| semantic cache 오염 | 통과. 예산, 부품 class, mutation/read-only 의미가 다르면 cache hit 금지 테스트 통과 |
| 5초 기준 | 통과. API live 최대 4.267초, 웹 live 최대 0.888초 |

## 남은 리스크

- 일부 LLM/RAG full path 케이스는 4초대 후반까지 접근한다. 현재 5초 기준은 통과했지만, 네트워크나 OpenAI 응답 지연이 커지면 초과 가능성이 있다.
- semantic cache async store는 best-effort라서 응답 직후 즉시 row가 보장되지는 않는다.
- `docker compose config`는 로컬 환경 변수를 펼쳐 보여줄 수 있으므로 CI 로그나 외부 공유 로그에서는 민감값 노출 여부를 별도로 주의해야 한다.

## 제외 항목

- XGBoost 홈 추천부품, AS runtime/API, 관리자 운영 기능은 이번 구조 검증의 수정 범위가 아니다.
- 로컬 SQL 백업 파일 `buildgraph-before-*.sql`은 검증 대상이 아니며 커밋/스테이징 대상에서 제외한다.
