# MLOps 구체화 설계 — 추천 파이프라인 성숙도 L1 → L2

> 배경: 현행 추천 학습 파이프라인은 "MLOps 따라하기 수준"이라는 평가를 받았다. 이 문서는 그 평가를 수용하고,
> **현재 코드베이스 위에서** 실제 MLOps로 올라가는 구체 설계를 정의한다. 일반론이 아니라 이 저장소의
> 테이블·서비스·워커 단위로 쓴다. (현재 상태 근거: docs/pipeline-audit-and-redesign.md, 운영 규칙: docs/RECOMMENDATION_OPERATIONS.md)
>
> **리뷰 이력**: 초안을 3렌즈(실현가능성·ML 방법론·운영 안전) 적대적 리뷰에 통과시켜 BLOCKER 5건·MAJOR 11건을
> 본 개정판에 반영했다. 핵심 정정: rank_position은 원 순위가 아님(M4), holdout이 early stopping에 소비됨(M1),
> 자동 LOCK의 B5 재개방(M2), 워커의 freeze 미인지(M2), featureSchema 노출 경로 부재(M6).

## 0. 성숙도 진단 — 무엇이 있고 무엇이 없는가

Google MLOps 성숙도 모델 기준으로 현재 위치를 정직하게 매핑한다.

| MLOps 요소 | 현재 상태 | 근거 |
|---|---|---|
| 데이터 수집·라벨 | ✅ 있음 (이벤트 화이트리스트·AS 라벨 브리지) | `recommendation_events`, `as_ticket_labels` |
| 데이터셋 재현성 | ✅ 있음 (스냅샷) | `features_snapshot`/`event_snapshot`/`label_score_snapshot` |
| 훈련 자동화 | ⚠️ 반자동 (관리자 잡 생성 → 워커 소비) | `reranker_service.py` 워커, `FOR UPDATE SKIP LOCKED` |
| 훈련 평가 | ⚠️ **holdout이 early stopping의 eval_set으로 소비됨** — 모델 선택과 평가가 미분리 (reranker_service.py:459). M1에서 분리 필요 | metrics.holdout{mae,rmse,spearman,ndcgAt4Global} |
| 승급 게이트 | ⚠️ 최소 (holdout **존재** 여부만) | activateModel 409 — "기존 모델 대비 우위" 비교 없음 |
| 서빙 안전 | ✅ 있음 (tri-state, FALLBACK 정합, 롤백=retire) | `HomePartRecommendationService` |
| 모델 레지스트리 | ✅ 경량 (상태 기계 + 아티팩트 경로) | `recommendation_model_versions` |
| **자동 재훈련** | ❌ 없음 | — |
| **Drift 감지** | ❌ 없음 | — |
| **Champion–Challenger 비교** | ❌ 없음 | — |
| **온라인 실험(A/B)** | ❌ 없음 | — |
| **학습-서빙 스큐 자동 게이트** | ⚠️ 수동 (코드 컨벤션 + 문서) | feature_schema는 기록만 하고 검증에 미사용 |
| 관측/알림 | ⚠️ 부분 (잡 이력·카운터는 있고, 임계 알림 없음) | `pipeline_job_runs`, scorer `/health` counters |

**결론**: L1(파이프라인 자동화)의 뼈대는 갖췄고, L2(CT — Continuous Training + 검증 자동화)로 가는 데 필요한 것은
**5개의 구체 기능**이다. 아래 M1~M6이 그것이고, 전부 기존 스택(Postgres + Spring 스케줄러 + Python 워커) 위에 얹는다.

## 1. 설계 원칙

1. **경량 우선** — MLflow/Kubeflow/피처 스토어 같은 신규 스택 도입 없이 Postgres-native로 구현한다(팀 규모·데모 단계·"신규 의존성 금지" 관례). 도구 도입은 이 설계가 한계에 닿을 때 재논의.
2. **auto-train, manual-promote** — 재훈련은 자동화하되 ACTIVE 승급은 항상 사람이 결정한다. 자동화는 SHADOW까지만.
3. **데이터 현실 인정** — 현재 이벤트는 노출(IMPRESSION, label 0) 위주 ~152건. CTR 기반 지표는 당분간 통계적으로 무의미하므로, 초기 비교는 **라벨 상관(순위 일치)** 기반으로 시작하고 온라인 지표는 트래픽 축적 후 단계 도입.
4. **실험 표면 고정** — 홈 4-card 한정. Build Chat/견적 본체 확장 금지(기존 안전선 유지).
5. **관측 우선** — 모든 자동 동작(재훈련·드리프트 체크)은 `pipeline_job_runs`에 기록되고 관리자 화면에서 보인다. 침묵 자동화 금지.
6. **통계적 겸손** — 소표본 단계에서 지표가 판단 불능이면 판단 불능이라고 표기한다(INSUFFICIENT_DATA). 노이즈 위에 하드 게이트를 얹지 않는다.

## 2. M1 — Champion–Challenger 승급 게이트 v2

**문제**: 현재 게이트는 "holdout 지표가 존재하는가"만 본다. 새 모델이 **기존보다 나은지**는 사람이 숫자를 눈으로 비교해야 한다.

### 2a. 전제: holdout 순수성 확보 (비교 이전의 선결 과제)

현행 훈련은 holdout(시간 기준 마지막 20%)을 `early_stopping_rounds`의 eval_set으로 쓴다(reranker_service.py:459).
즉 challenger는 그 holdout에서 최적 iteration이 **선택된** 모델이라, 같은 holdout으로 champion과 비교하면 challenger가 체계적으로 유리하다.
→ **early stopping용 검증셋은 train 80% 내부의 마지막 15%로 다시 쪼개고, holdout은 비교 전용 순수 평가셋으로 유지한다.** (워커 수정)

### 2b. champion 예측 계산

훈련 워커가 SHADOW 모델 생성 직후, **같은 holdout 집합**(단, 2a에 의해 challenger의 early stopping에 사용되지 않은 집합)에 대해 champion의 예측을 계산해 `metrics.comparison`에 기록한다.

- **champion = 실제 XGB 모델(ACTIVE)일 때**: `recommendation_model_versions`에서 artifact_path와 feature_schema를 읽어 로드(워커=스코어러 동일 프로세스, /models 동일 볼륨이라 현실적).
  **단 champion의 feature_schema가 현재 FEATURES와 불일치하면 예측을 생략하고 verdict=INCONCLUSIVE(사유: featureSchemaMismatch)** — 다른 스키마로 학습된 모델에 현재 피처 벡터를 넣으면 조용히 왜곡되기 때문. 아티팩트 파일 부재/로드 실패도 동일 처리(사유 기록).
- **champion = baseline 휴리스틱일 때**: Scorer.score()의 model-None 분기 공식을 재사용하되 **rank_position 항은 0으로 고정**한다.
  이유: 훈련 스냅샷의 rank_position(홈 노출 위치)은 position bias로 라벨과 상관되어 baseline spearman을 인위적으로 부풀리는데, challenger XGB는 FEATURES에서 rank_position이 의도적으로 제외돼 이 신호를 못 쓴다 — 불공정 비교가 된다.
  이때 이 비교의 의미는 "**노출-순서 항을 제거한 콘텐츠 휴리스틱 대비**"임을 관리자 UI에 정직하게 표기한다.
- **holdout 경계**: challenger holdout이 champion의 학습 구간(trained_from~trained_to)과 겹치면 champion이 in-sample 이점을 갖는다(자동 dataset은 전체 기간을 누적 포함하므로 데이터가 쌓일수록 겹침이 커진다). → **비교용 holdout은 champion.trained_to 이후 이벤트로 한정**하고, 겹침 비율을 comparison에 기록한다.

```json
"comparison": {
  "champion": "home-parts-20260705...-job1" | "baseline-heuristic",
  "holdoutRows": 30, "holdoutPositives": 7, "holdoutOverlapWithChampion": 0.0,
  "challengerSpearman": 0.61, "championSpearman": 0.44, "spearmanCi95": [0.02, 0.31],
  "challengerNdcgAt4": 0.87, "championNdcgAt4": 0.71,
  "verdict": "CHALLENGER_BETTER" | "CHAMPION_BETTER" | "INCONCLUSIVE" | "INSUFFICIENT_DATA",
  "verdictReason": "..."
}
```

### 2c. verdict 3단 규칙 (소표본 통계력 반영)

holdout은 ~30행(min_rows=50이면 10행까지) 수준이고 양성 라벨이 0~3개일 수 있다 — spearman(분산 0)·ndcg(전부 gain 0)가 None이 되는 경로가 실재한다. 규칙:

1. **INSUFFICIENT_DATA**: holdout 양성 라벨 수 < 10, 또는 spearman/ndcg 어느 한쪽이라도 None → 비교 보류. 게이트 비활성, UI에 "신호 부족" 뱃지만.
2. **1차 지표는 spearman 단독** + 행 단위 paired bootstrap 95% CI(1000회, 순수 Python으로 충분). CI가 0을 제외할 때만 CHALLENGER_BETTER / CHAMPION_BETTER. 그 외 INCONCLUSIVE. (bootstrap 구현이 밀리면 최소 규칙: |Δspearman| < 0.1 → INCONCLUSIVE)
3. ndcgAt4는 참고 지표로만 기록(양성 1개면 값이 양자화되어 동률 빈발 — 판정에 쓰지 않음).

### 2d. activateModel 게이트

- **409 거절 조건(전부 충족 시에만)**: verdict=CHAMPION_BETTER **이고** comparison.champion이 **현재 ACTIVE 모델과 일치**(stale verdict 방지) **이고** holdoutOverlapWithChampion=0(공정 비교였음). 그 외 CHAMPION_BETTER는 경고로 강등(승급 허용 — 사람 판단 존중).
- INCONCLUSIVE/INSUFFICIENT_DATA는 승급 허용 + 응답에 경고 필드. verdict 필드 부재(구모델)도 동일(null-safe).
- 관리자 UI 모델 행에 verdict 뱃지 + 사유.

**변경 지점**: `tools/reranker_service.py`(2a eval_set 분리 + champion 예측 + bootstrap — **~100–150줄**), `RecommendationTrainingService.activateModel`(게이트), `AdminDashboardPage`(뱃지). 스키마 변경 없음(metrics jsonb).

**AC**: (1) 공정 조건을 갖춘 열위 모델 activate → 409, 그 외 CHAMPION_BETTER는 경고로 통과. (2) 양성 라벨 부족 데이터셋에서 verdict=INSUFFICIENT_DATA가 기록되고 게이트가 발동하지 않는다. (3) 관리자 화면에서 verdict·사유가 보인다.

## 3. M2 — 자동 재훈련 트리거 (Continuous Training)

**문제**: 데이터가 쌓여도 사람이 dataset 생성→lock→job 생성을 안 하면 모델이 낡는다.

### 3a. 스케줄러

신규 `RecommendationRetrainScheduler`(주 1회, 일요일 03:00 KST, `DemoFreezeGuard` 존중, `PipelineJobRunRecorder.run` 래핑):

1. **조건**: ① 미학습 eligible 이벤트 ≥ `auto-retrain-min-new-events`(기본 100) **② 그중 양성 라벨(label > 0) ≥ `auto-retrain-min-new-positives`(기본 10)** ③ 마지막 SUCCEEDED 훈련 후 `auto-retrain-min-interval-days`(기본 7) 경과. — ②가 없으면 홈 방문 IMPRESSION(1회 방문당 최대 4건 자동 기록)만으로 조건이 충족되어 라벨 없는 재훈련이 반복된다.
2. 충족 시: dataset 자동 생성(이름 `auto-{date}`, 직전 auto dataset은 ARCHIVED 처리) → **오염 가드(3b) 적용** → LOCK → 훈련 job 생성. 워커가 소비(기존 흐름) → SHADOW + M1 comparison까지 자동.
3. 미충족 시: **status=SUCCEEDED + result_summary `{"skipped":true,"reason":"newEvents=37 < 100"}`** 로 기록. (pipeline_job_runs의 CHECK 제약이 SUCCEEDED/FAILED/SKIPPED_FROZEN/SKIPPED_LOCKED만 허용하므로 범용 'SKIPPED' 상태는 쓸 수 없다 — 스키마 불변 유지를 위해 요약 필드로 표현. 기존 recordSkippedFrozen 관례와 구분됨을 관리자 UI 라벨로 명시.)
4. **실패 백오프**: 직전 자동 재훈련 job이 FAILED **또는 SKIPPED_LOW_DATASET**이면 관리자 확인 전 재시도 억제(연속 2회 시 중단 + 대시보드 경고). 또한 오염 가드 적용 후 included가 워커 `min_rows`(기본 50) 미만이면 **job을 만들지 않고 skip**(doomed job이 SKIPPED_LOW_DATASET로 끝나 같은 데이터로 매주 무한 재생성되는 것을 원천 차단). 없으면 같은 데이터로 dataset·job이 매주 무한 생성된다.

### 3b. 오염 가드 — 자동 LOCK은 B5 방어선을 우회한다 (필수)

감사 B5(학습 이벤트 사용자 위조)의 완화책 중 하나가 "관리자가 LOCK 전 datasetItems 검토·제외"였는데, 자동 LOCK은 그 검토를 건너뛴다. sourceSurface 화이트리스트는 surface만 제한할 뿐이고, 아무 로그인 사용자나 ORDER_INTENT(+5.0)·SAVE(+3.0)를 임의 partId에 기록할 수 있으며 rate limit도 없다 — 가드 없는 자동 LOCK은 "위조 이벤트 → 무인 훈련 → 오염 SHADOW" 경로를 완성하고, M1 verdict도 오염된 holdout 위에서 계산되므로 승급 판단 근거까지 오염된다. **다음 가드를 자동 dataset에 강제한다**:

- **사용자 편중 컷**: 단일 user_id가 dataset 양성 라벨의 30% 초과 시 해당 사용자의 이벤트를 자동 제외(`excluded_reason='AUTO_SUSPECT_CONCENTRATION'`) + 대시보드 경고.
- **고가중 비율 상한**: 가중치 ≥3.0 이벤트(ORDER_INTENT/SAVE)가 dataset의 20%를 초과하면 초과분을 제외(`excluded_reason='AUTO_HIGH_WEIGHT_CAP'`). **최신순 = 원 이벤트 발생 시각(`event.created_at`) 기준**(dataset item의 created_at은 단일 트랜잭션이라 전부 동일 — 위조 버스트는 최신 이벤트다). 남길 개수 = `floor(0.25 × 비고가중)`으로 **제외 후 고가중 비율 ≤20% 보장**.
- 제외 내역은 datasetItems에 남아 관리자가 사후 검토·복원 가능(수동 승급 전 확인 항목).
- *(대안으로 "DRAFT 생성 → 24h 유예 후 자동 LOCK(그 사이 관리자 제외 가능)"도 가능 — 가드보다 사람 검토를 선호하면 이 옵션. 기본안은 가드+즉시 LOCK.)*

### 3c. 워커 측 데모 동결 (필수)

`DemoFreezeGuard`는 Java 스케줄러의 잡 **생성**만 막는다. 훈련 워커는 별도 Python 프로세스로 5초마다 QUEUED를 소비하며 freeze를 모른다 — 금요일에 QUEUED된 잡을 데모 중 소비해 모델 행 삽입·dataset 전이가 일어날 수 있다.
→ **데모 절차(docs/RECOMMENDATION_OPERATIONS.md 체크리스트)에 추가**: 데모 전 `RECOMMENDATION_TRAINING_WORKER_ENABLED=false`로 xgb-reranker 재기동(이 스위치는 reranker_service.py:289에 이미 존재 — 재사용) + 잔여 QUEUED 잡 확인/취소. 이 문서 갱신이 M2의 AC에 포함된다.

### 3d. 구현 지점

- 신규 스케줄러 1클래스. `RecommendationTrainingService`에 **createDatasetInternal + createJobInternal**(created_by/queued_by NULL — 기존 createJob은 admin.internalId()를 무조건 호출해 NPE, 컬럼은 nullable이라 DB는 문제없음. lockDataset은 publicId 기반 그대로 재사용). filters에 `{"trigger":"AUTO_RETRAIN"}` 마킹.
- **전제 항목**: featureSnapshot의 `price_age_days`가 dataset 생성 시점 now() 기준이라 같은 이벤트도 매주 값이 달라진다(라벨 시점 대비 미래 정보 주입). → event.created_at 기준 계산으로 수정. (M1의 "같은 holdout 비교" 전제이기도 함)
- **승급은 하지 않는다**(원칙 2). 결과는 SHADOW 목록 + verdict 뱃지로 노출 — 사람은 "승급 판단"만 하면 됨.

**AC**: 조건 충족 주에 dataset·job이 자동 생성되고 SHADOW+comparison이 생긴다. 미충족 주에는 skipped 요약 이력이 남는다. 편중/고가중 이벤트가 자동 제외되고 사유가 보인다. 데모 체크리스트에 워커 동결 단계가 문서화된다. ACTIVE는 변하지 않는다.

## 4. M3 — Drift·품질 모니터링

**문제**: 서빙 분포가 학습 시점과 달라져도(부품 가격 변동, 카탈로그 교체, 사용자 행동 변화) 아무도 모른다.

**설계** — 신규 테이블 1개 + 일일 스케줄러 1개:
```sql
CREATE TABLE recommendation_drift_snapshots (
  id BIGSERIAL PRIMARY KEY,
  snapshot_date DATE NOT NULL UNIQUE,
  metrics JSONB NOT NULL,
  alerts JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 기록은 INSERT ... ON CONFLICT (snapshot_date) DO UPDATE (수동 재실행·재기동 멱등)
```

**일일 계산 3계열** (`RecommendationDriftScheduler`, 04:50 KST, recorder 래핑):

1. **카탈로그 피처 drift (PSI — 재훈련 연계 대상)**: ACTIVE 부품 카탈로그의 서빙 피처 분포(part_price, part_benchmark_score, part_price_age_days — 부품 수백 개 전수, 표본 노이즈 없음) vs ACTIVE(없으면 최신 SHADOW) 모델의 trained_from~to 구간 features_snapshot 분포.
   *초안의 "이벤트 라벨/eventType 믹스 PSI"는 폐기* — 주간 이벤트 수십 건 규모에서 PSI 기대 노이즈가 (B−1)(1/N₁+1/N₂) ≈ 0.2를 넘어 무드리프트에도 상시 경보가 되고, 라벨은 eventType의 결정적 함수라 중복 신호다. **이벤트 믹스는 주간 카운트 + 이항 신뢰구간으로 표시만** 한다.
   PSI 구현 규정: 연속 변수 10분위 구간, 0-빈도 스무딩 ε=1e-4, 창당 최소 표본 500(미달 시 계산 생략+사유).
2. **예측 drift (PSI — 표시 전용, 재훈련 연계 제외)**: **동일 model_version_id 내** 최근 7일 vs 직전 7일 shadow score 분포. baseline-shadow(model-None) 행 제외 — baseline 점수(0~20+, rank 항 지배)와 XGB 출력(-3~5)은 스케일이 달라 섞으면 버전 전환 주간마다 의미 없는 폭발 경보가 나고, M2 연계 시 "방금 승급한 모델이 즉시 재훈련을 유발"하는 자기 루프가 된다. 버전 전환 주간은 MODEL_CHANGED 플래그로 경보 억제.
3. **운영 지표**: fallback 비율(scoreSource 집계), scorer `/health` counters의 scoreErrors 증분(**현재값 < 직전값이면 컨테이너 리셋으로 간주하고 현재값을 증분으로 사용** — 인메모리 카운터), 훈련 job 실패율.

**알림·연계**:
- 임계(코드 상수): PSI > 0.2 경고, > 0.3 심각. fallback > 30% 경고. 위반 시 alerts 기록 + 관리자 대시보드 "드리프트" 패널(최근 7일 표, 경고 행 강조). 외부 채널(Slack)은 팀 채널 확정 후 후속.
- **재훈련 연계는 계열 1(카탈로그 PSI)에만**: 심각 판정 시 M3 스케줄러가 M2 로직을 즉시 호출(min-interval 무시, min-new-events·positives 조건은 유지). 주 1회 M2를 기다리면 최대 6일 지연되므로 즉시 경로가 필요.
- `PipelineJobRunRecorder`에 trigger_type 파라미터 추가(SCHEDULED/DRIFT_TRIGGERED) — 드리프트발 재훈련을 이력에서 구분(원칙 5).

**AC**: 매일 스냅샷 1행(재실행 시 갱신), 대시보드에서 PSI 추이·경고 확인. 시드 데이터로 카탈로그 가격 분포를 인위 변경 시 PSI 경고 발생. 버전 전환 주간에 예측 drift 경보가 억제된다.

## 5. M4 — Shadow 비교 관측 (기존 B10 잔여)

**문제**: shadow 점수를 쌓기만 하고, baseline 순위 대비 모델 순위가 **얼마나 다른지** 집계가 없다.

**⚠️ 전제 정정 (초안 폐기 사항)**: `recommendation_shadow_scores.rank_position`은 "원 순위"가 아니다 — loadCandidates()의 SQL에 ORDER BY가 없어 **Postgres 실행계획에 따른 임의 페치 순서의 인덱스**이고, deterministic 정렬은 그 뒤에 일어난다. 이걸 기준으로 Kendall-τ를 계산하면 "모델 vs 디스크 힙 순서" 비교가 되어 지표가 무의미하다.

**설계 (정정판)**:
- **baseline 순위 재구성**: shadow 행의 features JSONB에 deterministicScore의 입력이 전부 저장돼 있으므로(part_benchmark_score, part_has_fps_coverage, part_has_image, part_has_offer, part_tool_ready, part_price_age_days, part_price, category), 집계 시 Java deterministicScore를 재계산해 그룹 내 baseline 순위를 복원한 뒤 모델 score 순위와 Kendall-τ를 계산한다. *(선택 최적화: shadow INSERT 시 baseline 점수를 features jsonb에 `baseline_score`로 함께 기록 — jsonb라 스키마 불변)*
- **오염 필터 3종(필수)**: ① `source_surface='HOME_RECOMMENDED_PARTS' AND candidate_type='PART'`(BUILD_CHAT 행은 BUILD/PART별 rank_position이 0부터 재시작해 혼합 τ가 무의미), ② model_version_id 조인으로 **baseline-shadow 행 제외**, ③ **후보별 최신 1건으로 dedup**(`row_number` PARTITION BY request_hash, model_version_id, candidate_id) 후 **(request_hash, model_version_id)로 그룹** — 홈 부품 점수는 사용자·시각 무관(부품 결정적)이라 같은 request_hash는 같은 후보집합이므로, 초 버스트 분리 대신 후보별 최신 점수 dedup이 중복 회차·초 경계 straddle 편향을 모두 없앤다.
- **top-4 교체율**: baseline score 상위 4 vs 모델 score 상위 4의 집합 차이. 단 실제 사용자가 본 top-4는 diverseTop(카테고리 다양성 규칙)까지 거친 결과이므로, 이 지표는 "점수 순위 기준"임을 UI에 명시(실서빙 재현이 필요하면 diverseTop 로직 재적용).
- API `GET /api/admin/recommendation-shadow/summary?days=7` + 대시보드 카드 1행: "shadow가 순위를 평균 N% 바꿈 · top4 교체율 M%" — **승급 전 사람 판단의 근거**(순위를 거의 안 바꾸는 모델은 승급 가치 없음).

**변경 지점**: 집계 서비스 + AdminController 엔드포인트 1개 + 대시보드 카드 (+선택: XgboostShadowReranker에 baseline_score 기록). 스키마 변경 없음.

**AC**: 대시보드에서 최근 7일 역전율/top-4 교체율이 보이고, 그 수치가 baseline-shadow·BUILD_CHAT·중복 회차에 오염되지 않음을 테스트로 고정.

## 6. M5 — 온라인 실험 최소형 (조건부·후순위)

**전제 조건**: variant당 **주간 활성 사용자 100명 이상**. (초안의 "이벤트 500건" 기준 폐기 — 무작위화 단위는 사용자인데 이벤트는 소수 파워유저에 집중될 수 있어 이벤트 수 기준은 클러스터 효과로 CI를 과신하게 만든다.)

**설계** (조건 충족 시):
- **버킷팅**: `Math.floorMod(hash(user_public_id + experiment_salt), 100) < treatment-percent`(기본 0=off). floorMod 필수(String.hashCode 음수 → % 100 음수 → 전원 control로 새는 조용한 SRM 버그). experiment_salt로 실험별 재배정(램프업 시 저버킷 사용자 고정·이월 편향 방지).
- **variant 태깅 — recommendationId 인코딩안 폐기**: 접미사 인코딩은 openapi scoreSource enum 위반, 홈 IMPRESSION dedup 키(`${session}:impression:${recommendationId}`) 파괴(모델 retire로 전원 control 복귀 시 dedup 리셋 → CTR 오염), E2E 하드코딩 다수 파괴를 일으킨다. **대신 버킷팅이 결정적이므로 서버가 recordEvent 시점에 variant를 재계산해 event_payload에 태깅**한다 — recommendationId 불변, 프론트 무변경이 실제로 성립. 홈 응답에는 nullable `variant` 필드 추가(scoreSource enum 불변, **openapi.yaml 수정 포함**).
- **평가**: 사용자 단위 우선 집계(사용자별 CTR → variant 평균 비교). 주간 리포트에 variant별 **사용자 수 + SRM 카이제곱 p-value**(p<0.001이면 결과 무효 처리) 포함. 유의성은 초기엔 신뢰구간 겹침 수준(엄밀 검정은 표본 확보 후).
- **가드**: treatment는 ACTIVE 모델 존재 시에만 유효. retire 시 전원 control 자동 복귀(tri-state가 보장).

**AC**: treatment-percent=50 설정 시 사용자 절반이 XGBOOST 순위를 받고, 주간 리포트에 variant별 사용자 수·SRM 검정·지표가 분리 집계된다.

## 7. M6 — 학습-서빙 스큐 자동 게이트 (소형, 즉시 가능)

**문제**: 피처 계약(FEATURES ↔ featureSnapshot ↔ 서빙 features)이 코드 컨벤션과 문서로만 유지된다 — 감사에서 실제 스큐 사고(price_age_days 999↔0)가 있었다.

**설계 (초안 정정: featureSchema는 현재 /score 응답에만 있고 /health·/reload에는 없다 — "워커 무변경" 불가)**:
- 워커: `/reload`(및 `/health`) 응답에 `"featureSchema": {"features": FEATURES}` 추가 — **Python 2~3줄**.
- Java: `activateModel`은 이미 reload를 호출하므로 그 응답의 featureSchema와 모델의 feature_schema를 diff, 불일치면 **409** — "이 모델은 현재 서빙 피처와 다른 스키마로 훈련됨". **~30줄**.
- **게이트 범위 확장(B2 재발 방지의 실질)**: 모델 feature_schema ↔ scorer FEATURES ↔ **Java 서빙 피처 키 목록**(HomePartRecommendationService.features가 생성하는 키를 상수 목록으로 추출) **3자 대조**. scorer 상수만 비교하면 실사고 지점이었던 Java 피처 빌더의 키 드리프트를 못 잡는다.

**AC**: FEATURES 변경 배포 후 구스키마 모델 activate → 409. Java 피처 키 상수와 scorer FEATURES 불일치를 잡는 테스트 1개.

## 8. 하지 않는 것 (명시적 비목표)

- MLflow/Kubeflow/W&B 등 **외부 MLOps 스택 도입** — 현 규모에서 운영 비용 > 효익. M1~M6이 한계에 닿으면 재논의.
- **피처 스토어 풀구현** — features_snapshot이 그 역할의 80%를 이미 함.
- 홈 4-card **외 표면으로 모델 확장** — 기존 안전선.
- AS raw 로그의 피처화 — AI_DIAGNOSIS_CONTRACT 준수.
- 완전 자동 승급(auto-promote) — 데이터 규모상 자동 판단의 오류 비용이 사람 병목 비용보다 큼.

## 9. 단계·순서·규모

| 단계 | 항목 | 예상 규모 | 의존성 |
|---|---|---|---|
| 1 | M6 스큐 게이트 | Python 2~3줄 + Java ~30줄 + 테스트 | 없음 (즉시) |
| 1 | M1 Champion–Challenger | Python ~100–150줄 + Java 게이트 + UI 뱃지 | 없음 |
| 2 | M4 Shadow 비교 관측 | 집계 서비스 + API + 카드 | 없음 |
| 2 | M2 자동 재훈련 | 스케줄러 + Internal 메서드 2개 + 오염 가드 + 운영 문서 | M1(verdict), price_age_days 시점 수정 |
| 3 | M3 Drift 모니터링 | 테이블 1 + 스케줄러 + PSI + 대시보드 패널 | recorder trigger_type 확장 |
| 4 | M5 온라인 A/B | 버킷팅 + 서버 태깅 + 리포트 + openapi | **주간 활성 사용자 조건 충족 후** |

- 단계 1~2는 스키마 변경 없이 가능(M2의 skipped 기록은 result_summary 방식 채택으로 CHECK 제약 회피).
- 단계 3 마이그레이션 1개 — **번호는 예약하지 않는다**: PR #62(V69~V81)가 out-of-order 문제로 V93+ 재번호될 가능성이 높으므로, 머지 직전 저장소 최대 버전+1로 부여한다(Flyway out-of-order는 켜지 않는 정책 유지).

## 10. 발표 프레이밍 (팀장 피드백 대응)

> "현재는 **수명주기·안전 게이트를 갖춘 학습 파이프라인**(이벤트→스냅샷 데이터셋→holdout 훈련→SHADOW→수동 승급→롤백)이고,
> MLOps 성숙도로는 L1입니다. L2(Continuous Training)로 가는 로드맵이 본 문서로 설계돼 있으며 —
> champion-challenger 게이트, 주간 자동 재훈련(오염 가드 포함), PSI 드리프트 감지, A/B — 전부 기존 스택 위 경량 구현으로
> 외부 도구 없이 도달 가능합니다. 자동 승급만은 의도적으로 배제했습니다(소표본 단계에서 사람 게이트가 더 안전).
> 설계는 3렌즈 적대적 리뷰(실현가능성·ML 방법론·운영 안전)를 통과한 상태입니다."
