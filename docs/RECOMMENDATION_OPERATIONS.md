# 추천 파이프라인 운영 계약

> 홈 추천부품 XGBoost 파이프라인의 **운영 규칙**(승급·롤백·서빙 모드·데이터 정책)을 명문화한다.
> 코드가 강제하는 규칙과 사람이 지켜야 할 규칙을 구분해 표기한다. (근거: docs/pipeline-audit-and-redesign.md)

## 1. 모델 수명주기

```
이벤트 수집 → dataset 생성(DRAFT) → 관리자 검토/제외 → LOCK → 훈련 Job(QUEUED)
→ 워커 훈련 → SHADOW 모델 → [승급 게이트] → ACTIVE → (retire) → RETIRED
```

| 단계 | 강제 주체 | 규칙 |
|---|---|---|
| 훈련 최소 데이터 | 워커(코드) | included 50행 미만 → `SKIPPED_LOW_DATASET` |
| 훈련 평가 | 워커(코드) | 이벤트 시각 기준 **과거 80% 학습 / 최근 20% holdout**, early stopping(15). metrics에 `holdout{mae,rmse,spearman,ndcgAt4Global}` 기록. `trainMae/trainRmse`는 참고용 in-sample — **품질 판단에 쓰지 말 것** |
| ACTIVE 승급 | 서버(코드) | `metrics.holdout` 없는 모델은 activate **409 거절**. artifactPath 없는 모델도 거절 |
| 승급 판단 | **사람** | holdout 지표를 보고 결정한다. 참고 기준: `spearman > 0`(순위 상관 양수), `ndcgAt4Global`은 전역 근사치(요청 그룹 정보 부재)임을 감안. 데이터가 노출(IMPRESSION) 위주면 모델이 상수 예측을 학습할 수 있음 — spearman null이면 활성화 보류 |
| 롤백 | 사람+코드 | 관리자 retire → scorer가 baseline으로 reload → 홈은 즉시 FALLBACK 복귀 |

## 2. 서빙 모드 (tri-state)

홈 추천의 스코어러 사용은 `HomePartRecommendationService`가 자동 관리한다:

| 상태 | 홈 응답 | shadow 수집 |
|---|---|---|
| 미확인(부팅 직후) | 동기 1회 탐지 | 동기 기록 |
| **baseline**(실모델 없음) | **스코어러 대기 없이 즉시 응답**(deterministic 순위, scoreSource=FALLBACK) | 백그라운드 스레드, request_hash당 5분 스로틀 |
| **실모델 ACTIVE** | 동기 스코어링(scoreSource=XGBOOST, 순위 반영) | 동기 기록 |

- activate/retire 시 훈련 서비스가 서빙 모드를 즉시 전환한다(훅). 미호출 경로로 모델이 바뀌어도 다음 응답 관측으로 자동 보정.
- `scoreSource=FALLBACK`이면 **순위도 Java deterministic 점수**가 결정한다(표시-실제 일치 보장).

## 3. 학습 데이터 정책

| 항목 | 규칙 | 강제 |
|---|---|---|
| 사용자 이벤트 surface | `BUILD_CHAT`, `HOME_RECOMMENDED_PARTS`만 허용(그 외 400) — ADMIN_* surface 위조 차단 | 코드 |
| 사용자 이벤트 type | LABEL_SCORES 화이트리스트, `AS_CONFIRMED_NEGATIVE`/`ADMIN_*`은 사용자 API에서 403 | 코드 |
| AS 확정 negative | 관리자 API만. training link(part/build/recommendation) 없으면 이벤트 미생성 | 코드 |
| AS 라벨 재확정 | 라벨 정정 시 기존 학습 이벤트의 part/링크/payload도 갱신됨 | 코드 |
| 로그 요약 연결 | `logSummaryId` 명시 지정 가능(티켓 소속 검증, 오지정 400). 미지정 시 **최신 요약** | 코드 |
| 모델 피처 | `features_snapshot` = 모델이 소비하는 피처만(reranker_service.py `FEATURES`와 1:1). AS 컨텍스트는 `event_snapshot.asContext`에 보존(모델 미사용) | 코드 |
| 피처 결측 기본값 | `part_price_age_days` 결측 = **999**(훈련·서빙 동일). `rank_position`은 모델 피처 아님(학습-서빙 의미 불일치·포지션 누수) | 코드 |
| 가격 신선도 기준시각 | **학습** `price_age_days`는 이벤트(라벨) 발생 시점(`event.created_at`) 기준으로 계산(M2) — dataset 생성 시점(now())이 아니라. 같은 이벤트가 매주 재훈련마다 값이 달라지는 미래정보 주입을 막아 M1 비교 재현성을 지킨다. **서빙**은 요청 시점(now()) 기준(서빙 신선도가 맞음) | 코드 |
| shadow 보존 | `recommendation_shadow_scores` 30일 후 매일 03:40 KST 삭제 | 코드 |
| 자동 재훈련(M2) | 주 1회(기본 off). 미학습 이벤트≥100 **그중 양성 라벨≥10** + 마지막 성공 훈련 후 7일 경과 시 dataset·job 자동 생성. **오염 가드**: 단일 user가 양성 30% 초과 시 제외(`AUTO_SUSPECT_CONCENTRATION`), 고가중(≥3.0) 20% 초과분 제외(`AUTO_HIGH_WEIGHT_CAP`). 승급은 안 함(SHADOW까지만). 연속 2회 실패 시 중단 | 코드/env |

## 4. 운영 플래그 (env)

| env | 기본 | 의미 |
|---|---|---|
| `RECOMMENDATION_RERANKER_SHADOW_ENABLED` | true | 홈 shadow 수집·스코어러 사용 전체 스위치 |
| `RECOMMENDATION_RERANKER_ENDPOINT` | http://xgb-reranker:8091/score | 스코어러 주소 |
| `RECOMMENDATION_RERANKER_TIMEOUT_MS` | 1200 | 스코어러 호출 타임아웃 |
| `RECOMMENDATION_RERANKER_SHADOW_THROTTLE_MS` (yml: shadow-throttle-ms) | 300000 | 같은 후보 집합 shadow 재기록 스로틀 |
| `RECOMMENDATION_TRAINING_WORKER_ENABLED` | true | 컨테이너 내 훈련 워커. **데모 시 false로 재기동해 워커 동결**(freeze는 Java 잡 생성만 막고 워커는 별개 프로세스 — 섹션 6) |
| `RECOMMENDATION_TRAINING_MIN_ROWS` | 50 | 훈련 최소 행 |
| `RECOMMENDATION_AUTO_RETRAIN_ENABLED` | false | **M2 자동 재훈련** 스케줄러 on/off |
| `RECOMMENDATION_AUTO_RETRAIN_CRON` | `0 0 3 * * SUN` | 자동 재훈련 주기(일요일 03:00 KST) |
| `RECOMMENDATION_AUTO_RETRAIN_MIN_NEW_EVENTS` | 100 | 자동 재훈련 트리거 최소 미학습 이벤트 |
| `RECOMMENDATION_AUTO_RETRAIN_MIN_NEW_POSITIVES` | 10 | 그중 최소 양성 라벨(라벨 없는 재훈련 방지) |
| `RECOMMENDATION_AUTO_RETRAIN_MIN_INTERVAL_DAYS` | 7 | 마지막 성공 훈련 후 최소 간격 |
| `recommendation.shadow.retention-days` | 30 | shadow 보존일 |
| `DEMO_FREEZE_MUTATIONS` | false | **데모 동결**: 수집 스케줄러 5종 skip + 관리자 가격 Job 409 (섹션 6) |

## 5. 관측

- 스케줄 잡 이력: `pipeline_job_runs` (관리자 UI: 가격 작업 관리자 → 스케줄 실행 이력). 상태 `SUCCEEDED/FAILED/SKIPPED_FROZEN/SKIPPED_LOCKED`
- 스코어러: `GET :8091/health` — `modelLoaded`, `counters{scoreRequests,scoreErrors,reloadRequests}`. **`modelLoaded:false`인데 ACTIVE 모델이 있어야 하는 상황이면 즉시 조사**
- 홈 응답의 `scoreSource` 분포: 관리자 `recommendation-models/summary`의 `scoreSources`

## 6. 데모 체크리스트

1. 데모 전: `DEMO_FREEZE_MUTATIONS=true`로 재기동 → 가격/자산 수집 일괄 동결(잡 이력에 SKIPPED_FROZEN 기록됨)
2. 데모 전: **훈련 워커 동결** — `RECOMMENDATION_TRAINING_WORKER_ENABLED=false`로 `xgb-reranker` 재기동. `DEMO_FREEZE_MUTATIONS`는 Java 잡 생성만 막고 Python 워커는 별개 프로세스라, 금요일에 QUEUED된 잡을 데모 중 소비해 모델 행이 생길 수 있다. 잔여 QUEUED 잡이 있으면 `UPDATE recommendation_training_jobs SET status='CANCELLED' WHERE status='QUEUED'`로 정리
3. 저품질 SHADOW 모델을 실수로 activate하지 않았는지 확인(홈 scoreSource가 의도와 일치하는지)
4. 데모 후: freeze 해제 + `RECOMMENDATION_TRAINING_WORKER_ENABLED` 원복(워커 재활성화)

## 7. 하지 말 것 (안전선)

- 홈 4-card 외 추천 본체(Build Chat/견적 순서)에 XGBoost 직접 연결
- AS 접수만으로 자동 negative 라벨 생성 (training link 필수 유지)
- raw gzip/전체 JSONL을 학습 피처로 투입 (AI_DIAGNOSIS_CONTRACT 준수)
- holdout 지표 없는(또는 spearman null인) 모델의 ACTIVE 승급
- 차단(403/429)당한 제조사 소스의 UA 위장 우회 — 자동 PAUSED가 정책이다
