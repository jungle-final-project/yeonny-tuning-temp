#!/usr/bin/env python3
"""BuildGraph recommendation scorer and optional training worker.

HTTP API:
  GET  /health
  POST /score
  POST /reload

The training worker polls recommendation_training_jobs when enabled. It is kept
in this process so Docker Compose can run a single xgb-reranker service for the
prototype.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import shutil
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


# 모델 피처 계약. Java의 훈련 스냅샷(RecommendationTrainingService.featureSnapshot)과
# 서빙 요청(HomePartRecommendationService.features) 모두 이 이름·의미와 일치해야 한다.
# rank_position은 제외한다: 훈련(카드 노출 위치 0~3)과 서빙(전체 후보 인덱스 0~N)의 의미가
# 달라 학습-서빙 스큐를 만들고, 이전 랭킹이 정한 위치를 입력으로 쓰는 포지션 누수이기도 하다.
FEATURES = [
    "part_price",
    "build_total_price",
    "part_benchmark_score",
    "part_tool_ready",
    "part_has_image",
    "part_has_offer",
    "part_price_age_days",
    "part_has_fps_coverage",
    "category_CPU",
    "category_GPU",
    "category_RAM",
    "category_MOTHERBOARD",
    "category_STORAGE",
    "category_PSU",
    "category_CASE",
    "category_COOLER",
]


class Scorer:
    def __init__(self, model_path: str | None):
        self.model_path = model_path
        self.model = None
        self.load_error = None
        self._model_version = None
        if model_path:
            try:
                from xgboost import XGBRegressor

                self.model = XGBRegressor()
                self.model.load_model(model_path)
                self._model_version = read_model_version(model_path) or Path(model_path).stem
            except ImportError as exc:
                raise SystemExit("Install xgboost to serve a trained reranker model.") from exc
            except Exception as exc:  # Keep the service available with baseline scoring.
                self.load_error = str(exc)
                self.model = None
                self.model_path = None

    @property
    def model_version(self) -> str:
        if not self.model_path:
            return "baseline-shadow"
        return self._model_version or Path(self.model_path).stem

    @property
    def artifact_path(self) -> str | None:
        return self.model_path or None

    def score(self, candidate: dict[str, Any]) -> float:
        features = candidate.get("features") or {}
        if self.model is None:
            price = number(features.get("part_price")) or number(features.get("totalPrice")) or 0
            rank_position = number(features.get("rank_position")) or candidate.get("rankPosition") or 0
            benchmark = number(features.get("part_benchmark_score")) or number(features.get("benchmark_score")) or 0
            has_image = 1 if truthy(features.get("part_has_image")) or truthy(features.get("has_image")) else 0
            has_offer = 1 if truthy(features.get("part_has_offer")) or truthy(features.get("has_offer")) else 0
            return float(max(0, 10 - rank_position) + benchmark / 20 + has_image + has_offer - (price / 10_000_000))
        row = [[feature_value(features, name) for name in FEATURES]]
        return float(self.model.predict(row)[0])


class ScorerState:
    def __init__(self, model_path: str | None):
        self._lock = threading.RLock()
        self._scorer = Scorer(model_path)

    def current(self) -> Scorer:
        with self._lock:
            return self._scorer

    def reload(self, model_path: str | None) -> Scorer:
        with self._lock:
            self._scorer = Scorer(model_path)
            return self._scorer


def number(value: Any) -> float | None:
    try:
        if value is None or value == "":
            return None
        return float(value)
    except (TypeError, ValueError):
        return None


def truthy(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if value is None:
        return False
    return str(value).strip().lower() in {"1", "true", "yes", "y"}


def feature_value(features: dict[str, Any], name: str) -> float:
    if name.startswith("category_") and features.get(name) is None:
        return 1.0 if str(features.get("category") or "").upper() == name.removeprefix("category_") else 0.0
    return number(features.get(name)) or 0.0


def default_model_path() -> str | None:
    configured = os.getenv("RECOMMENDATION_RERANKER_MODEL_PATH")
    if configured:
        return configured
    docker_active = Path("/models/home-parts-active.json")
    if docker_active.exists():
        return str(docker_active)
    return None


def read_model_version(model_path: str) -> str | None:
    path = Path(model_path)
    candidates = [
        path.with_name(f"{path.stem}.metrics.json"),
        path.with_name("metrics.json"),
    ]
    for metrics_path in candidates:
        if not metrics_path.exists():
            continue
        try:
            metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
            value = metrics.get("modelVersion")
            return str(value) if value else None
        except Exception:
            continue
    return None


# 최소 관측성: 요청/오류 카운터. 예전에는 액세스 로그 억제 + 지표 부재로 실패율 집계가 불가능했다(감사 B11).
COUNTERS = {"scoreRequests": 0, "scoreErrors": 0, "reloadRequests": 0}
COUNTERS_LOCK = threading.Lock()


def bump(counter: str):
    with COUNTERS_LOCK:
        COUNTERS[counter] = COUNTERS.get(counter, 0) + 1


def make_handler(state: ScorerState):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802 - stdlib callback name
            if self.path != "/health":
                self.send_error(404)
                return
            scorer = state.current()
            with COUNTERS_LOCK:
                counters = dict(COUNTERS)
            self.write_json({
                "status": "UP",
                "modelVersion": scorer.model_version,
                "modelLoaded": scorer.model is not None,
                "modelLoadError": scorer.load_error,
                "counters": counters,
                # 활성화 게이트(M6)가 모델의 feature_schema와 대조할 현재 서빙 피처 계약.
                "featureSchema": {"features": FEATURES},
            })

        def do_POST(self):  # noqa: N802 - stdlib callback name
            if self.path == "/score":
                self.handle_score()
                return
            if self.path == "/reload":
                self.handle_reload()
                return
            self.send_error(404)

        def handle_score(self):
            # malformed 요청이 커넥션 리셋으로만 끝나 원인 파악이 불가능하던 것(감사 B11) →
            # 400 JSON으로 응답하고 오류 카운터에 집계한다.
            bump("scoreRequests")
            try:
                payload = self.read_json()
                candidates = payload.get("candidates") or []
                if not isinstance(candidates, list):
                    raise ValueError("candidates must be a list")
                scorer = state.current()
                scores = []
                for candidate in candidates:
                    if not isinstance(candidate, dict):
                        raise ValueError("each candidate must be an object")
                    scores.append({
                        "candidateId": candidate.get("candidateId"),
                        "partId": candidate.get("partId"),
                        "score": scorer.score(candidate),
                    })
            except (ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
                bump("scoreErrors")
                self.write_json({"error": "INVALID_REQUEST", "message": str(exc)}, status=400)
                return
            except Exception as exc:  # 예기치 못한 스코어링 실패도 JSON 500으로 관측 가능하게.
                bump("scoreErrors")
                self.write_json({"error": "SCORING_FAILED", "message": str(exc)}, status=500)
                return
            self.write_json({
                "modelName": "xgboost-reranker" if scorer.model is not None else "baseline-shadow-reranker",
                "modelVersion": scorer.model_version,
                "artifactPath": scorer.artifact_path,
                "scores": scores,
                "metrics": {},
                "featureSchema": {"features": FEATURES},
            })

        def handle_reload(self):
            bump("reloadRequests")
            payload = self.read_json()
            model_path = payload.get("modelPath")
            scorer = state.reload(str(model_path) if model_path else None)
            if scorer.model is not None and model_path:
                persist_active_model(str(model_path))
            elif not model_path:
                clear_active_model()
            self.write_json({
                "status": "UP",
                "modelVersion": scorer.model_version,
                "modelLoaded": scorer.model is not None,
                "modelLoadError": scorer.load_error,
                "featureSchema": {"features": FEATURES},
            })

        def read_json(self) -> dict[str, Any]:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0:
                return {}
            return json.loads(self.rfile.read(length).decode("utf-8") or "{}")

        def write_json(self, payload: dict[str, Any], status: int = 200):
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):  # noqa: A003,N802 - stdlib callback name
            return

    return Handler


def db_configured() -> bool:
    return bool(os.getenv("RECOMMENDATION_DB_HOST"))


def db_connection():
    import psycopg2
    import psycopg2.extras

    return psycopg2.connect(
        host=os.getenv("RECOMMENDATION_DB_HOST", "localhost"),
        port=int(os.getenv("RECOMMENDATION_DB_PORT", "5432")),
        dbname=os.getenv("RECOMMENDATION_DB_NAME", "buildgraph"),
        user=os.getenv("RECOMMENDATION_DB_USER", "buildgraph"),
        password=os.getenv("RECOMMENDATION_DB_PASSWORD", "buildgraph"),
        cursor_factory=psycopg2.extras.RealDictCursor,
    )


def training_worker_loop(stop_event: threading.Event):
    if not truthy(os.getenv("RECOMMENDATION_TRAINING_WORKER_ENABLED", "true")):
        print("recommendation training worker disabled")
        return
    if not db_configured():
        print("recommendation training worker disabled: DB env is not configured")
        return
    poll_seconds = int(os.getenv("RECOMMENDATION_TRAINING_POLL_SECONDS", "5"))
    min_rows = int(os.getenv("RECOMMENDATION_TRAINING_MIN_ROWS", "50"))
    worker_id = f"xgb-reranker-{os.getpid()}"
    print(f"recommendation training worker started workerId={worker_id} minRows={min_rows}")
    while not stop_event.is_set():
        try:
            processed = process_one_training_job(worker_id, min_rows)
            if not processed:
                stop_event.wait(poll_seconds)
        except Exception as exc:  # Keep the scorer process alive.
            print(f"recommendation training worker error: {exc}")
            stop_event.wait(poll_seconds)


def process_one_training_job(worker_id: str, min_rows: int) -> bool:
    with db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE recommendation_training_jobs
                SET status = 'RUNNING',
                    worker_id = %s,
                    started_at = now(),
                    updated_at = now(),
                    log_summary = '학습 worker가 Job을 시작했습니다.'
                WHERE id = (
                  SELECT id
                  FROM recommendation_training_jobs
                  WHERE status = 'QUEUED'
                    AND deleted_at IS NULL
                  ORDER BY created_at ASC, id ASC
                  LIMIT 1
                  FOR UPDATE SKIP LOCKED
                )
                RETURNING id, public_id::text AS public_id, dataset_id
                """,
                (worker_id,),
            )
            job = cur.fetchone()
            if not job:
                return False
    run_training_job(job["id"], job["dataset_id"], worker_id, min_rows)
    return True


def run_training_job(job_id: int, dataset_id: int, worker_id: str, min_rows: int):
    try:
        rows = load_training_rows(dataset_id)
        if len(rows) < min_rows:
            complete_job_without_model(job_id, dataset_id, "SKIPPED_LOW_DATASET", {
                "status": "SKIPPED_LOW_DATASET",
                "rowCount": len(rows),
                "minRows": min_rows,
                "createdAt": datetime.now(timezone.utc).isoformat(),
            }, f"학습 데이터가 {len(rows)}건으로 최소 {min_rows}건보다 적어 모델을 만들지 않았습니다.")
            return
        train_model(job_id, dataset_id, rows, worker_id)
    except Exception as exc:
        mark_job_failed(job_id, str(exc))


def load_training_rows(dataset_id: int) -> list[dict[str, Any]]:
    # 이벤트 발생 시각 오름차순으로 정렬해 시간 기반 holdout 분리(과거로 학습, 최근으로 평가)가 가능하게 한다.
    with db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT item.features_snapshot,
                       item.label_score_snapshot,
                       event.created_at AS event_created_at
                FROM recommendation_training_dataset_items item
                JOIN recommendation_events event ON event.id = item.event_id
                WHERE item.dataset_id = %s
                  AND item.included = true
                ORDER BY event.created_at ASC, item.id ASC
                """,
                (dataset_id,),
            )
            return list(cur.fetchall())


def mean_absolute_error(predictions: list[float], actuals: list[float]) -> float:
    return sum(abs(pred - actual) for pred, actual in zip(predictions, actuals)) / len(actuals)


def root_mean_squared_error(predictions: list[float], actuals: list[float]) -> float:
    return math.sqrt(sum((pred - actual) ** 2 for pred, actual in zip(predictions, actuals)) / len(actuals))


def midranks(values: list[float]) -> list[float]:
    # 동순위는 평균 순위(midrank)로 처리한 순위 배열.
    order = sorted(range(len(values)), key=lambda index: values[index])
    ranks = [0.0] * len(values)
    position = 0
    while position < len(order):
        tail = position
        while tail + 1 < len(order) and values[order[tail + 1]] == values[order[position]]:
            tail += 1
        average_rank = (position + tail) / 2.0 + 1.0
        for index in range(position, tail + 1):
            ranks[order[index]] = average_rank
        position = tail + 1
    return ranks


def spearman_correlation(predictions: list[float], actuals: list[float]) -> float | None:
    # 예측과 실제 라벨의 순위 일치도(-1~1). 랭킹 문제에서 MAE보다 직접적인 신호다.
    if len(predictions) < 2:
        return None
    pred_ranks = midranks(predictions)
    actual_ranks = midranks(actuals)
    mean_pred = sum(pred_ranks) / len(pred_ranks)
    mean_actual = sum(actual_ranks) / len(actual_ranks)
    covariance = sum((p - mean_pred) * (a - mean_actual) for p, a in zip(pred_ranks, actual_ranks))
    pred_variance = math.sqrt(sum((p - mean_pred) ** 2 for p in pred_ranks))
    actual_variance = math.sqrt(sum((a - mean_actual) ** 2 for a in actual_ranks))
    if pred_variance == 0 or actual_variance == 0:
        return None
    return covariance / (pred_variance * actual_variance)


def ndcg_at_k(predictions: list[float], actuals: list[float], k: int = 4) -> float | None:
    # holdout 전체를 하나의 랭킹 그룹으로 본 NDCG@k. 음수 라벨은 gain 0으로 클램프한다.
    # (요청 단위 그룹 정보가 학습 행에 없어 그룹별 NDCG는 불가 — 전역 근사치임을 이름으로 명시)
    if not predictions:
        return None
    gains = [max(actual, 0.0) for actual in actuals]
    if all(gain == 0.0 for gain in gains):
        return None
    predicted_order = sorted(range(len(predictions)), key=lambda index: predictions[index], reverse=True)
    ideal_order = sorted(range(len(gains)), key=lambda index: gains[index], reverse=True)
    dcg = sum(gains[index] / math.log2(position + 2) for position, index in enumerate(predicted_order[:k]))
    ideal_dcg = sum(gains[index] / math.log2(position + 2) for position, index in enumerate(ideal_order[:k]))
    if ideal_dcg == 0:
        return None
    return dcg / ideal_dcg


# ---------------------------------------------------------------------------
# M1 Champion-Challenger 승급 비교 (설계 docs/mlops-maturity-design.md §2)
# 모두 순수 함수/국소 I/O로, 실패해도 학습 잡을 터뜨리지 않고 verdict 사유로만 남긴다.
# ---------------------------------------------------------------------------

VERDICT_MIN_POSITIVES = int(os.getenv("RECOMMENDATION_COMPARE_MIN_POSITIVES", "10"))


def baseline_comparison_score(features: dict[str, Any]) -> float:
    # champion=baseline일 때의 예측 점수. Scorer.score의 model-None 휴리스틱과 동일하되 rank_position
    # 항을 0으로 고정한다(→ 상수항 10): 훈련 스냅샷의 rank_position(노출 위치)은 라벨과 상관돼 baseline
    # spearman을 부풀리는데 challenger XGB는 rank_position을 안 쓰므로 불공정 비교가 되기 때문(설계 2b).
    price = number(features.get("part_price")) or number(features.get("totalPrice")) or 0
    benchmark = number(features.get("part_benchmark_score")) or number(features.get("benchmark_score")) or 0
    has_image = 1 if truthy(features.get("part_has_image")) or truthy(features.get("has_image")) else 0
    has_offer = 1 if truthy(features.get("part_has_offer")) or truthy(features.get("has_offer")) else 0
    return float(10 + benchmark / 20 + has_image + has_offer - (price / 10_000_000))


def paired_spearman_diff_ci(
    challenger_preds: list[float],
    champion_preds: list[float],
    actuals: list[float],
    iterations: int = 1000,
    seed: int = 42,
) -> tuple[float, float] | None:
    # (challenger spearman - champion spearman)의 paired bootstrap 95% CI. holdout 행을 복원추출로
    # 리샘플해 두 모델의 순위 상관 차를 재계산한다. degenerate(분산 0) 리샘플은 건너뛰고, 유효 표본이
    # 절반 미만이면 판정 불가(None)로 본다. 소표본 노이즈 위에 하드 게이트를 얹지 않기 위함(설계 2c).
    n = len(actuals)
    if n < 2:
        return None
    rng = random.Random(seed)
    diffs: list[float] = []
    for _ in range(iterations):
        idx = [rng.randrange(n) for _ in range(n)]
        resampled_actuals = [actuals[i] for i in idx]
        challenger = spearman_correlation([challenger_preds[i] for i in idx], resampled_actuals)
        champion = spearman_correlation([champion_preds[i] for i in idx], resampled_actuals)
        if challenger is None or champion is None:
            continue
        diffs.append(challenger - champion)
    if len(diffs) < iterations * 0.5:
        return None
    diffs.sort()
    low = diffs[int(0.025 * len(diffs))]
    high = diffs[min(len(diffs) - 1, int(0.975 * len(diffs)))]
    return (low, high)


def decide_verdict(
    challenger_spearman: float | None,
    champion_spearman: float | None,
    ci: tuple[float, float] | None,
    holdout_positives: int,
    challenger_ndcg: float | None,
    min_positives: int = VERDICT_MIN_POSITIVES,
) -> tuple[str, str]:
    # 설계 2c 3단 규칙. 소표본/지표 불능이면 INSUFFICIENT_DATA(게이트 비활성), 그 외에는 spearman 차
    # 95% CI가 0을 배제할 때만 우열을 가린다.
    if holdout_positives < min_positives:
        return "INSUFFICIENT_DATA", f"holdout 양성 라벨 {holdout_positives}건 < {min_positives}건"
    if challenger_spearman is None or champion_spearman is None or challenger_ndcg is None:
        return "INSUFFICIENT_DATA", "지표 계산 불능(순위/gain 분산 부족)"
    if ci is None:
        return "INCONCLUSIVE", "bootstrap CI 계산 불능"
    low, high = ci
    if low > 0:
        return "CHALLENGER_BETTER", f"spearman 차 95% CI [{low:.3f}, {high:.3f}] > 0"
    if high < 0:
        return "CHAMPION_BETTER", f"spearman 차 95% CI [{low:.3f}, {high:.3f}] < 0"
    return "INCONCLUSIVE", f"spearman 차 95% CI [{low:.3f}, {high:.3f}]가 0을 포함"


def load_active_champion() -> dict[str, Any] | None:
    # 현재 ACTIVE 챔피언 모델의 비교 메타. 없으면(=baseline 서빙 중) None.
    with db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT model_version, artifact_path, feature_schema, trained_to
                FROM recommendation_model_versions
                WHERE status = 'ACTIVE'
                  AND deleted_at IS NULL
                ORDER BY activated_at DESC NULLS LAST, created_at DESC
                LIMIT 1
                """
            )
            return cur.fetchone()


def champion_predictor(champion: dict[str, Any] | None):
    # (predict_fn, champion_label, skip_reason). champion이 XGB인데 스키마 불일치/아티팩트 부재/로드
    # 실패면 predict_fn=None + 사유를 돌려 verdict를 INCONCLUSIVE로 만든다(조용한 왜곡 방지, 설계 2b).
    if champion is None:
        return (lambda features: baseline_comparison_score(features)), "baseline-heuristic", None
    schema = champion.get("feature_schema") or {}
    champion_features = schema.get("features") if isinstance(schema, dict) else None
    if champion_features != FEATURES:
        return None, champion.get("model_version"), "featureSchemaMismatch"
    artifact = champion.get("artifact_path")
    if not artifact or not Path(artifact).exists():
        return None, champion.get("model_version"), "artifactMissing"
    try:
        from xgboost import XGBRegressor

        model = XGBRegressor()
        model.load_model(artifact)
    except Exception as exc:
        return None, champion.get("model_version"), f"loadFailed:{exc}"

    def predict(features: dict[str, Any]) -> float:
        row = [[feature_value(features, name) for name in FEATURES]]
        return float(model.predict(row)[0])

    return predict, champion.get("model_version"), None


def build_champion_comparison(
    holdout_rows: list[dict[str, Any]],
    y_holdout: list[float],
    challenger_predictions: list[float],
) -> dict[str, Any]:
    # M1 2b/2c. 비교용 holdout은 champion.trained_to 이후 이벤트로 한정(챔피언 in-sample 이점 제거)하고,
    # 두 모델을 같은 부분집합에 대해 채점해 paired bootstrap으로 우열을 가린다.
    try:
        champion = load_active_champion()
    except Exception as exc:
        return {"champion": None, "verdict": "INCONCLUSIVE", "verdictReason": f"championLoadFailed:{exc}"}

    predict_fn, champion_label, skip_reason = champion_predictor(champion)

    trained_to = champion.get("trained_to") if champion else None
    full_n = len(holdout_rows)
    if trained_to is not None:
        keep = [
            i for i, row in enumerate(holdout_rows)
            if row.get("event_created_at") is not None and row["event_created_at"] > trained_to
        ]
    else:
        keep = list(range(full_n))
    overlap = 0.0 if full_n == 0 else round((full_n - len(keep)) / full_n, 4)

    comparison: dict[str, Any] = {
        "champion": champion_label,
        "holdoutRows": len(keep),
        "holdoutOverlapWithChampion": overlap,
    }
    if predict_fn is None:
        comparison["verdict"] = "INCONCLUSIVE"
        comparison["verdictReason"] = skip_reason
        return comparison

    # champion 예측(predict-time)·지표 계산도 방어한다. champion 아티팩트가 로드는 됐으나 xgboost 버전
    # 스큐/부분 손상으로 predict에서 던지는 경우, 정상 학습된 challenger를 버리지 않고 verdict를
    # INCONCLUSIVE로 강등한다(설계 2b/2c: 비교 실패는 승급 허용 대상, 학습은 계속).
    try:
        y_keep = [y_holdout[i] for i in keep]
        challenger_keep = [challenger_predictions[i] for i in keep]
        champion_keep = [predict_fn(holdout_rows[i].get("features_snapshot") or {}) for i in keep]
        positives = sum(1 for value in y_keep if value > 0)

        challenger_spearman = spearman_correlation(challenger_keep, y_keep)
        champion_spearman = spearman_correlation(champion_keep, y_keep)
        both_defined = challenger_spearman is not None and champion_spearman is not None
        ci = paired_spearman_diff_ci(challenger_keep, champion_keep, y_keep) if both_defined else None
        challenger_ndcg = ndcg_at_k(challenger_keep, y_keep, 4)
        verdict, reason = decide_verdict(challenger_spearman, champion_spearman, ci, positives, challenger_ndcg)

        comparison.update({
            "holdoutPositives": positives,
            "challengerSpearman": challenger_spearman,
            "championSpearman": champion_spearman,
            "spearmanCi95": list(ci) if ci else None,
            "challengerNdcgAt4": challenger_ndcg,
            "championNdcgAt4": ndcg_at_k(champion_keep, y_keep, 4),
            "verdict": verdict,
            "verdictReason": reason,
        })
    except Exception as exc:
        comparison["verdict"] = "INCONCLUSIVE"
        comparison["verdictReason"] = f"comparisonFailed:{exc}"
    return comparison


def train_model(job_id: int, dataset_id: int, rows: list[dict[str, Any]], worker_id: str):
    from psycopg2.extras import Json
    from xgboost import XGBRegressor

    x = []
    y = []
    for row in rows:
        features = row["features_snapshot"] or {}
        x.append([feature_value(features, name) for name in FEATURES])
        y.append(float(row["label_score_snapshot"]))

    # 시간 기반 holdout: 과거 80%로 학습하고 최근 20%로 평가한다(rows는 event 시각 오름차순).
    # 기존 train-on-train MAE/RMSE는 일반화 성능을 전혀 반영하지 못해 SHADOW 품질을 오판하게 했다.
    holdout_size = max(len(rows) // 5, 5)
    train_size = len(rows) - holdout_size
    x_train, y_train = x[:train_size], y[:train_size]
    x_holdout, y_holdout = x[train_size:], y[train_size:]
    holdout_rows = rows[train_size:]

    # M1 2a: early stopping 검증셋은 train 내부의 마지막 ~15%로 분리한다. holdout을 early stopping에
    # 소비하면 challenger가 그 위에서 iteration을 '선택'해 champion 비교가 부풀려지므로(모델 선택과
    # 평가의 분리), holdout은 순수 평가/비교 전용으로 남긴다. train이 너무 작아(< 10) fit 세트가 붕괴하면
    # early stopping 없이 x_train 전체로 학습한다(검증셋 1행짜리 무의미 학습 방지).
    if len(x_train) >= 10:
        earlyval_size = max(2, len(x_train) // 7)
        x_fit, y_fit = x_train[:-earlyval_size], y_train[:-earlyval_size]
        x_earlyval, y_earlyval = x_train[-earlyval_size:], y_train[-earlyval_size:]
        model = XGBRegressor(
            n_estimators=200,
            max_depth=3,
            learning_rate=0.08,
            objective="reg:squarederror",
            random_state=42,
            early_stopping_rounds=15,
        )
        model.fit(x_fit, y_fit, eval_set=[(x_earlyval, y_earlyval)], verbose=False)
    else:
        # 너무 작아 검증셋을 못 떼면 early stopping 없이 학습한다.
        model = XGBRegressor(
            n_estimators=200,
            max_depth=3,
            learning_rate=0.08,
            objective="reg:squarederror",
            random_state=42,
        )
        model.fit(x_train, y_train, verbose=False)

    train_predictions = [float(value) for value in model.predict(x_train)]
    holdout_predictions = [float(value) for value in model.predict(x_holdout)]
    holdout_metrics = {
        "rows": len(y_holdout),
        "mae": mean_absolute_error(holdout_predictions, y_holdout),
        "rmse": root_mean_squared_error(holdout_predictions, y_holdout),
        "spearman": spearman_correlation(holdout_predictions, y_holdout),
        "ndcgAt4Global": ndcg_at_k(holdout_predictions, y_holdout, 4),
        "split": "TIME_LAST_20PCT",
    }
    # M1 2b/2c: 현재 ACTIVE 챔피언과의 순위 비교(verdict). 실패해도 학습을 막지 않는다.
    comparison = build_champion_comparison(holdout_rows, y_holdout, holdout_predictions)

    model_version = f"home-parts-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}-job{job_id}"
    artifact_path = f"/models/{model_version}.json"
    Path("/models").mkdir(parents=True, exist_ok=True)
    model.save_model(artifact_path)
    metrics = {
        "modelVersion": model_version,
        "status": "SUCCEEDED",
        "rowCount": len(rows),
        "trainRows": len(y_train),
        # 참고용 in-sample 지표(과적합 정도 파악용). 품질 판단은 holdout을 봐야 한다.
        "trainMae": mean_absolute_error(train_predictions, y_train),
        "trainRmse": root_mean_squared_error(train_predictions, y_train),
        "holdout": holdout_metrics,
        "comparison": comparison,
        "bestIteration": int(getattr(model, "best_iteration", 0) or 0),
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "workerId": worker_id,
    }
    Path(f"/models/{model_version}.metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    feature_schema = {"features": FEATURES, "target": "label_score_snapshot"}
    with db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT min(event.created_at) AS trained_from,
                       max(event.created_at) AS trained_to
                FROM recommendation_training_dataset_items item
                JOIN recommendation_events event ON event.id = item.event_id
                WHERE item.dataset_id = %s
                  AND item.included = true
                """,
                (dataset_id,),
            )
            window = cur.fetchone() or {}
            cur.execute(
                """
                INSERT INTO recommendation_model_versions (
                  model_name,
                  model_version,
                  algorithm,
                  artifact_path,
                  status,
                  trained_from,
                  trained_to,
                  metrics,
                  feature_schema
                )
                VALUES ('xgboost-reranker', %s, 'XGBOOST', %s, 'SHADOW', %s, %s, %s::jsonb, %s::jsonb)
                RETURNING id
                """,
                (
                    model_version,
                    artifact_path,
                    window.get("trained_from"),
                    window.get("trained_to"),
                    Json(metrics),
                    Json(feature_schema),
                ),
            )
            model_version_id = cur.fetchone()["id"]
            cur.execute(
                """
                UPDATE recommendation_training_jobs
                SET status = 'SUCCEEDED',
                    model_version_id = %s,
                    model_version = %s,
                    artifact_path = %s,
                    metrics = %s::jsonb,
                    log_summary = '학습이 완료되어 SHADOW 모델을 생성했습니다.',
                    finished_at = now(),
                    updated_at = now()
                WHERE id = %s
                """,
                (model_version_id, model_version, artifact_path, Json(metrics), job_id),
            )
            cur.execute(
                """
                UPDATE recommendation_training_datasets
                SET status = 'TRAINED',
                    updated_at = now()
                WHERE id = %s
                """,
                (dataset_id,),
            )


def persist_active_model(model_path: str):
    active_path = Path("/models/home-parts-active.json")
    active_path.parent.mkdir(parents=True, exist_ok=True)
    source_path = Path(model_path)
    if source_path.resolve() != active_path.resolve():
        shutil.copyfile(model_path, active_path)
    source_metrics_path = source_path.with_name(f"{source_path.stem}.metrics.json")
    if source_metrics_path.exists():
        shutil.copyfile(source_metrics_path, active_path.with_name(f"{active_path.stem}.metrics.json"))
    active_pointer = {
        "activeModelPath": str(active_path),
        "sourceModelPath": model_path,
        "activatedAt": datetime.now(timezone.utc).isoformat(),
    }
    Path("/models/home-parts-active.pointer.json").write_text(
        json.dumps(active_pointer, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def clear_active_model():
    for path in (Path("/models/home-parts-active.json"), Path("/models/home-parts-active.pointer.json")):
        try:
            path.unlink()
        except FileNotFoundError:
            pass


def complete_job_without_model(job_id: int, dataset_id: int, status: str, metrics: dict[str, Any], summary: str):
    from psycopg2.extras import Json

    with db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE recommendation_training_jobs
                SET status = %s,
                    metrics = %s::jsonb,
                    log_summary = %s,
                    finished_at = now(),
                    updated_at = now()
                WHERE id = %s
                """,
                (status, Json(metrics), summary, job_id),
            )
            cur.execute(
                """
                UPDATE recommendation_training_datasets
                SET status = 'TRAINED',
                    updated_at = now()
                WHERE id = %s
                """,
                (dataset_id,),
            )


def mark_job_failed(job_id: int, summary: str):
    with db_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE recommendation_training_jobs
                SET status = 'FAILED',
                    log_summary = %s,
                    finished_at = now(),
                    updated_at = now()
                WHERE id = %s
                """,
                (summary[:2000], job_id),
            )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8091)
    parser.add_argument("--model", default=default_model_path())
    args = parser.parse_args()

    state = ScorerState(args.model)
    stop_event = threading.Event()
    worker = threading.Thread(target=training_worker_loop, args=(stop_event,), daemon=True)
    worker.start()
    server = ThreadingHTTPServer((args.host, args.port), make_handler(state))
    print(f"reranker_service listening on http://{args.host}:{args.port}/score modelVersion={state.current().model_version}")
    try:
        server.serve_forever()
    finally:
        stop_event.set()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
