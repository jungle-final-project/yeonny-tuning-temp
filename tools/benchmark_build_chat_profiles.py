#!/usr/bin/env python3
"""Run Build Chat AI profiles against live API cases and write a markdown report."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import statistics
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


DEFAULT_PROFILES = ["BUILD_CHAT_54_MINI_FAST"]


def main() -> int:
    parser = argparse.ArgumentParser(description="Benchmark Build Chat AI profiles")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--cases", default="tools/build_chat_live_cases.json")
    parser.add_argument("--output-dir", default="docs/reports")
    parser.add_argument("--report-suffix", default=None, help="Optional suffix appended to the markdown report filename")
    parser.add_argument("--user-email", default="user@example.com")
    parser.add_argument("--user-password", default="passw0rd!")
    parser.add_argument("--profiles", nargs="+", default=None)
    parser.add_argument("--variant-label", default="default")
    parser.add_argument("--case-group", default=None, help="Run only cases whose benchmarkGroup matches this value")
    parser.add_argument("--case-id", nargs="+", default=None, help="Run only the selected case id(s)")
    parser.add_argument("--repeat", type=int, default=1, help="Repeat each selected case N times")
    parser.add_argument("--slow-threshold-ms", type=int, default=10_000, help="Latency threshold for slow-case reporting")
    parser.add_argument("--fail-on-slow", action="store_true", help="Return non-zero when any case reaches slow-threshold-ms")
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()

    cases = json.loads(Path(args.cases).read_text(encoding="utf-8"))
    if args.case_group:
        cases = [case for case in cases if case.get("benchmarkGroup") == args.case_group]
        if not cases:
            raise RuntimeError(f"no cases matched benchmarkGroup={args.case_group}")
    if args.case_id:
        selected_ids = set(args.case_id)
        cases = [case for case in cases if case.get("id") in selected_ids]
        if not cases:
            raise RuntimeError(f"no cases matched case id(s)={sorted(selected_ids)}")
    repeat_count = max(1, args.repeat)
    profiles = args.profiles or DEFAULT_PROFILES
    token = login(args.base_url, args.user_email, args.user_password)
    results = [
        run_case(args.base_url, token, args.variant_label, profile, case, repeat_index, repeat_count, args.slow_threshold_ms)
        for profile in profiles
        for case in cases
        for repeat_index in range(1, repeat_count + 1)
    ]
    output = write_report(Path(args.output_dir), results, args.slow_threshold_ms, args.report_suffix)
    print(output)
    all_success = all(result["success"] for result in results)
    no_slow_cases = all(result["latencyMs"] < args.slow_threshold_ms for result in results)
    return 0 if not args.strict or (all_success and (no_slow_cases or not args.fail_on_slow)) else 1


def run_case(
    base_url: str,
    token: str,
    variant_label: str,
    profile: str,
    case: dict,
    repeat_index: int = 1,
    repeat_count: int = 1,
    slow_threshold_ms: int = 10_000,
) -> dict:
    body = {
        "message": case["message"],
        "currentBuilds": case.get("currentBuilds", []),
        "appliedPartPreferences": case.get("appliedPartPreferences", []),
    }
    if case.get("currentQuoteDraft"):
        body["currentQuoteDraft"] = case["currentQuoteDraft"]
    started = time.perf_counter()
    response = None
    error = None
    try:
        response = request_json(
            base_url,
            "POST",
            "/api/ai/build-chat",
            token,
            body,
            {"X-BuildGraph-AI-Profile": profile},
            timeout=180,
        )
    except RuntimeError as exc:
        error = str(exc)
    latency_ms = round((time.perf_counter() - started) * 1000)
    schema_valid = build_chat_schema_valid(response)
    build_count = len(response.get("builds", [])) if response else 0
    action_types = action_type_set(response)
    hard_constraint_ok = hard_constraint_preserved(response, case)
    expected_action = case.get("expectedActionType")
    action_ok = expected_action is None or expected_action in action_types
    category_ok = category_preserved(response, case)
    direction_ok = direction_preserved(base_url, token, response, case)
    forbidden_candidate_ok = forbidden_candidates_absent(response, case)
    action_payload_ok = action_payload_valid(response, case)
    answer_type_ok = response and response.get("answerType") == case.get("expectedAnswerType")
    min_builds_ok = build_count >= case.get("expectedMinBuilds", 0)
    warning_ok = not case.get("expectWarning") or bool(response and response.get("warnings"))
    success = bool(
        schema_valid
        and answer_type_ok
        and min_builds_ok
        and hard_constraint_ok
        and action_ok
        and category_ok
        and direction_ok
        and forbidden_candidate_ok
        and action_payload_ok
        and warning_ok
    )
    return {
        "variant": variant_label,
        "profile": profile,
        "caseId": case["id"],
        "repeat": repeat_index,
        "repeatCount": repeat_count,
        "success": success,
        "schemaValid": schema_valid,
        "latencyMs": latency_ms,
        "answerType": response.get("answerType") if response else "-",
        "buildCount": build_count,
        "actionTypes": ",".join(sorted(action_types)) if action_types else "-",
        "hardConstraintOk": hard_constraint_ok,
        "categoryOk": category_ok,
        "directionOk": direction_ok,
        "forbiddenCandidateOk": forbidden_candidate_ok,
        "actionPayloadOk": action_payload_ok,
        "slowOk": latency_ms < slow_threshold_ms,
        "warningOk": warning_ok,
        "error": error,
    }


def build_chat_schema_valid(response: dict | None) -> bool:
    if not isinstance(response, dict):
        return False
    return (
        isinstance(response.get("answerType"), str)
        and isinstance(response.get("message"), str)
        and isinstance(response.get("builds"), list)
        and "partRecommendation" in response
        and isinstance(response.get("actions"), list)
        and isinstance(response.get("warnings"), list)
        and isinstance(response.get("evidenceIds"), list)
    )


def action_type_set(response: dict | None) -> set[str]:
    if not response or not isinstance(response.get("actions"), list):
        return set()
    result = set()
    for action in response["actions"]:
        if isinstance(action, dict) and isinstance(action.get("type"), str):
            result.add(action["type"])
    return result


def hard_constraint_preserved(response: dict | None, case: dict) -> bool:
    required_gpu_class = case.get("requiredGpuClass")
    if not required_gpu_class:
        return True
    if not response:
        return False
    needle = str(required_gpu_class).lower()
    builds = response.get("builds") or []
    if not builds:
        return False
    for build in builds:
        items = build.get("items") if isinstance(build, dict) else []
        gpu_items = [
            item for item in items
            if isinstance(item, dict) and item.get("category") == "GPU"
        ]
        if not gpu_items:
            return False
        text = json.dumps(gpu_items, ensure_ascii=False).lower()
        if needle not in text:
            return False
    return True


def category_preserved(response: dict | None, case: dict) -> bool:
    expected = case.get("expectedCategory")
    if not expected:
        return True
    if not response:
        return False
    recommendation = response.get("partRecommendation") or {}
    if recommendation.get("category") == expected:
        return True
    for action in response.get("actions") or []:
        payload = action.get("payload") if isinstance(action, dict) else {}
        if isinstance(payload, dict) and payload.get("category") == expected:
            return True
    return False


def direction_preserved(base_url: str, token: str, response: dict | None, case: dict) -> bool:
    direction = case.get("expectedDirection")
    if not direction:
        return True
    if not response:
        return False
    current = current_item(case)
    category = case.get("expectedCategory")
    if not current or not category:
        return False
    current_price = int(current.get("currentPrice") or current.get("price") or current.get("lineTotal") or 0)
    current_tier = tier(category, current.get("attributes") or {}, current.get("name") or "")
    candidates = hydrated_candidates(base_url, token, response)
    candidates = [candidate for candidate in candidates if candidate.get("category") == category]
    if not candidates:
        return False
    if direction == "CHEAPER":
        return all(int(candidate.get("price") or 0) < current_price for candidate in candidates)
    if direction == "MORE_EXPENSIVE":
        return all(tier(category, candidate.get("attributes") or {}, candidate.get("name") or "") > current_tier for candidate in candidates)
    if direction == "SIMILAR_PRICE":
        return all(tier(category, candidate.get("attributes") or {}, candidate.get("name") or "") >= current_tier for candidate in candidates)
    return True


def forbidden_candidates_absent(response: dict | None, case: dict) -> bool:
    if not response:
        return not case.get("forbiddenPartIds") and not case.get("forbiddenClasses")
    text = json.dumps(candidate_items(response), ensure_ascii=False).upper().replace("-", "_")
    forbidden_ids = set(case.get("forbiddenPartIds") or [])
    if forbidden_ids:
        actual_ids = {str(candidate.get("partId")) for candidate in candidate_items(response) if candidate.get("partId")}
        if forbidden_ids & actual_ids:
            return False
    for forbidden_class in case.get("forbiddenClasses") or []:
        if str(forbidden_class).upper().replace("-", "_") in text:
            return False
    return True


def action_payload_valid(response: dict | None, case: dict) -> bool:
    expected_action = case.get("expectedActionType")
    if not expected_action:
        return True
    if not response:
        return False
    for action in response.get("actions") or []:
        if not isinstance(action, dict) or action.get("type") != expected_action:
            continue
        payload = action.get("payload")
        if not isinstance(payload, dict):
            return False
        if expected_action in {"ADD_PART_TO_DRAFT", "REPLACE_DRAFT_PART"}:
            return bool(payload.get("partId") and payload.get("category") and payload.get("quantity"))
        if expected_action == "REMOVE_DRAFT_PART":
            return bool(payload.get("partId") and payload.get("category"))
        if expected_action == "UPDATE_DRAFT_QUANTITY":
            return bool(payload.get("partId") and payload.get("category") and payload.get("quantity"))
        if expected_action == "CREATE_PRICE_ALERT":
            return bool(payload.get("targetPrice"))
        return True
    return False


def current_item(case: dict) -> dict:
    expected_category = case.get("expectedCategory")
    draft = case.get("currentQuoteDraft") or {}
    for item in draft.get("items") or []:
        if item.get("category") == expected_category:
            return item
    return {}


def candidate_items(response: dict) -> list[dict]:
    items = []
    recommendation = response.get("partRecommendation") or {}
    for option in recommendation.get("options") or []:
        if isinstance(option, dict):
            items.append(option)
    for action in response.get("actions") or []:
        payload = action.get("payload") if isinstance(action, dict) else {}
        if isinstance(payload, dict) and payload.get("partId"):
            items.append({
                "partId": payload.get("partId"),
                "category": payload.get("category"),
                "quantity": payload.get("quantity"),
                "name": payload.get("name", ""),
            })
    return items


def hydrated_candidates(base_url: str, token: str, response: dict) -> list[dict]:
    result = []
    seen = set()
    for item in candidate_items(response):
        part_id = item.get("partId")
        if not part_id or part_id in seen:
            continue
        seen.add(part_id)
        try:
            detail = request_json(base_url, "GET", f"/api/parts/{part_id}", token, None, timeout=30)
            if isinstance(detail, dict):
                attributes = detail.get("attributes")
                if isinstance(attributes, dict) and isinstance(detail.get("benchmarkSummary"), dict):
                    attributes = dict(attributes)
                    attributes["benchmarkSummary"] = detail["benchmarkSummary"]
                    detail = dict(detail)
                    detail["attributes"] = attributes
            result.append(detail)
        except RuntimeError:
            result.append(item)
    return result


def tier(category: str, attributes: dict, name: str) -> float:
    score = benchmark_score(attributes)
    if category == "GPU":
        text = f"{attributes.get('gpuClass', '')} {name}".upper().replace("-", "_")
        if "5090" in text:
            return 5090 * 1000 + float(attributes.get("vramGb") or 0) * 8 + score
        if "5080" in text:
            return 5080 * 1000 + float(attributes.get("vramGb") or 0) * 8 + score
        if "5070" in text and "TI" in text:
            return 5075 * 1000 + float(attributes.get("vramGb") or 0) * 8 + score
        if "5070" in text:
            return 5070 * 1000 + float(attributes.get("vramGb") or 0) * 8 + score
        if "5060" in text:
            return 5060 * 1000 + float(attributes.get("vramGb") or 0) * 8 + score
    if category == "CPU":
        text = f"{attributes.get('cpuClass', '')} {name}".upper()
        cores = float(attributes.get("coreCount") or 0)
        threads = float(attributes.get("threadCount") or 0)
        tdp = float(attributes.get("tdpW") or 0)
        if "9" in text or "RYZEN_9" in text or "I9" in text:
            return 9000 + score * 10 + cores * 20 + threads * 6 + tdp * 0.2
        if "7" in text or "RYZEN_7" in text or "I7" in text:
            return 7000 + score * 10 + cores * 20 + threads * 6 + tdp * 0.2
        if "5" in text or "RYZEN_5" in text or "I5" in text:
            return 5000 + score * 10 + cores * 20 + threads * 6 + tdp * 0.2
        return score * 10 + cores * 20 + threads * 6 + tdp * 0.2
    if category == "RAM":
        return (
            float(attributes.get("capacityGb") or 0) * 100
            + float(attributes.get("speedMhz") or 0) / 10
            + float(attributes.get("moduleCount") or 0) * 20
            + (300 if str(attributes.get("memoryType") or "").upper() == "DDR5" else 0)
            + (30 if bool(attributes.get("xmp")) else 0)
            + (30 if bool(attributes.get("expo")) else 0)
            + score * 10
        )
    if category == "STORAGE":
        return (
            float(attributes.get("capacityGb") or 0) / 2
            + float(attributes.get("readMbps") or 0) / 10
            + float(attributes.get("writeMbps") or 0) / 15
            + generation_rank(str(attributes.get("generation") or "")) * 250
            + score * 10
        )
    if category == "PSU":
        return (
            float(attributes.get("capacityW") or attributes.get("wattage") or 0)
            + efficiency_rank(str(attributes.get("efficiency") or "")) * 90
            + generation_rank(str(attributes.get("atxSpec") or "")) * 50
            + generation_rank(str(attributes.get("pcieSpec") or "")) * 50
            + (60 if bool(attributes.get("modular")) else 0)
            + score * 10
        )
    if category == "MOTHERBOARD":
        chipset = str(attributes.get("chipset") or "").upper()
        chipset_rank = 4 if chipset.startswith(("X", "Z")) else 3 if chipset.startswith("B") else 2 if chipset.startswith(("H", "A")) else 1
        return (
            chipset_rank * 1000
            + generation_rank(str(attributes.get("pcieGeneration") or "")) * 160
            + (300 if str(attributes.get("memoryType") or "").upper() == "DDR5" else 0)
            + (80 if bool(attributes.get("hasWifi")) else 0)
            + form_factor_rank(str(attributes.get("formFactor") or "")) * 30
            + score * 10
        )
    if category == "CASE":
        return (
            float(attributes.get("maxGpuLengthMm") or 0) * 2
            + float(attributes.get("maxCpuCoolerHeightMm") or 0) * 2.5
            + float(attributes.get("maxPsuLengthMm") or 0)
            + (120 if bool(attributes.get("frontMesh")) else 0)
            + (120 if bool(attributes.get("airflowFocus")) else 0)
            + score * 10
        )
    if category == "COOLER":
        return (
            float(attributes.get("tdpW") or 0) * 4
            + float(attributes.get("radiatorLengthMm") or 0) * 2
            + float(attributes.get("heightMm") or 0) * 0.8
            + (120 if str(attributes.get("coolerType") or "").upper() == "AIO" else 0)
            + score * 10
        )
    return 0.0


def benchmark_score(attributes: dict) -> float:
    for key in ("_benchmarkScore", "benchmarkScore", "score"):
        value = attributes.get(key)
        if value:
            try:
                return float(value)
            except (TypeError, ValueError):
                pass
    summary = attributes.get("benchmarkSummary")
    if isinstance(summary, dict):
        try:
            return float(summary.get("score") or 0)
        except (TypeError, ValueError):
            return 0.0
    return 0.0


def generation_rank(value: str) -> float:
    import re
    match = re.search(r"([0-9]+(?:\\.[0-9]+)?)", value)
    return float(match.group(1)) if match else 0.0


def efficiency_rank(value: str) -> float:
    normalized = value.upper()
    if "TITANIUM" in normalized:
        return 5
    if "PLATINUM" in normalized:
        return 4
    if "GOLD" in normalized:
        return 3
    if "SILVER" in normalized:
        return 2
    if "BRONZE" in normalized:
        return 1
    return 0


def form_factor_rank(value: str) -> float:
    normalized = value.upper()
    if "E-ATX" in normalized:
        return 4
    if "ATX" in normalized:
        return 3
    if "M-ATX" in normalized or "MICRO" in normalized:
        return 2
    if "ITX" in normalized:
        return 1
    return 0


def write_report(
    output_dir: Path,
    results: list[dict],
    slow_threshold_ms: int = 10_000,
    report_suffix: str | None = None,
) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    suffix = "" if not report_suffix else "-" + safe_filename(report_suffix)
    output_path = output_dir / f"build-chat-profile-benchmark-{dt.datetime.now().strftime('%Y%m%d')}{suffix}.md"
    grouped = {}
    for result in results:
        grouped.setdefault((result["variant"], result["profile"]), []).append(result)

    lines = [
        "# Build Chat AI Profile Benchmark",
        "",
        f"- generatedAt: {dt.datetime.now().isoformat(timespec='seconds')}",
        f"- totalCases: {len(results)}",
        "",
        "## Summary",
        "",
        f"- slowThresholdMs: {slow_threshold_ms}",
        "",
        "| variant | profile | successRate | avgLatencyMs | p95LatencyMs | maxLatencyMs | slowCases | slowOkRate | schemaValidRate | directionOkRate | categoryOkRate | actionPayloadOkRate |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for (variant, profile), rows in grouped.items():
        latencies = [row["latencyMs"] for row in rows]
        slow_cases = sum(1 for latency in latencies if latency >= slow_threshold_ms)
        lines.append(
            f"| {variant} | {profile} | {ratio(row['success'] for row in rows):.1%} | "
            f"{mean(latencies):.0f} | {percentile(latencies, 95):.0f} | {max(latencies) if latencies else 0} | {slow_cases} | "
            f"{ratio(row['latencyMs'] < slow_threshold_ms for row in rows):.1%} | "
            f"{ratio(row['schemaValid'] for row in rows):.1%} | "
            f"{ratio(row['directionOk'] for row in rows):.1%} | "
            f"{ratio(row['categoryOk'] for row in rows):.1%} | "
            f"{ratio(row['actionPayloadOk'] for row in rows):.1%} |"
        )

    lines.extend([
        "",
        "## Worst Latency Cases",
        "",
        "| variant | profile | case | repeat | latencyMs | ok | slow |",
        "|---|---|---|---:|---:|---:|---:|",
    ])
    for row in sorted(results, key=lambda item: item["latencyMs"], reverse=True)[:10]:
        lines.append(
            f"| {row['variant']} | {row['profile']} | {row['caseId']} | {row['repeat']} | "
            f"{row['latencyMs']} | {yes(row['success'])} | {yes(row['latencyMs'] >= slow_threshold_ms)} |"
        )

    lines.extend([
        "",
        "## Cases",
        "",
        "| variant | profile | case | repeat | ok | latencyMs | answerType | builds | actions | hardConstraint | categoryOk | directionOk | forbiddenOk | actionPayloadOk | warningOk | error |",
        "|---|---|---|---:|---:|---:|---|---:|---|---:|---:|---:|---:|---:|---:|---|",
    ])
    for row in results:
        error = (row["error"] or "").replace("|", "/")
        lines.append(
            f"| {row['variant']} | {row['profile']} | {row['caseId']} | {row['repeat']} | {yes(row['success'])} | "
            f"{row['latencyMs']} | {row['answerType']} | {row['buildCount']} | {row['actionTypes']} | "
            f"{yes(row['hardConstraintOk'])} | {yes(row['categoryOk'])} | {yes(row['directionOk'])} | "
            f"{yes(row['forbiddenCandidateOk'])} | {yes(row['actionPayloadOk'])} | {yes(row['warningOk'])} | {error} |"
        )

    lines.extend([
        "",
        "## Notes",
        "",
        "- 이 벤치마크는 UI를 변경하지 않고 `/api/ai/build-chat`의 optional profile header만 바꿔 실행한다.",
        "- 기본 서비스 profile은 현재 `BUILD_CHAT_54_MINI_FAST`이며, 모델 비교가 필요하면 `--profiles`로 후보를 명시한다.",
        "- 5090 같은 명시 부품 조건은 추천 build의 GPU item에 보존되어야 한다.",
        "- 장바구니 교체 케이스는 반환 partId를 `/api/parts/{id}`로 다시 조회해 현재 부품 대비 상향/하향/유사 가격 방향을 검증한다.",
    ])
    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return output_path


def login(base_url: str, email: str, password: str) -> str:
    response = request_json(base_url, "POST", "/api/auth/login", None, {"email": email, "password": password})
    token = response.get("accessToken")
    if not token:
        raise RuntimeError(f"login failed for {email}: accessToken missing")
    return token


def request_json(
    base_url: str,
    method: str,
    path: str,
    token: str | None,
    body: dict | None,
    extra_headers: dict | None = None,
    timeout: int = 60,
) -> dict:
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if extra_headers:
        headers.update(extra_headers)
    request = urllib.request.Request(f"{base_url}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {path} failed: HTTP {exc.code} {raw}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"{method} {path} failed: {exc}") from exc


def yes(value: bool) -> str:
    return "yes" if value else "no"


def safe_filename(value: str) -> str:
    return "".join(char if char.isalnum() or char in {"-", "_"} else "-" for char in value.strip()).strip("-")


def ratio(values) -> float:
    items = list(values)
    return sum(1 for item in items if item) / len(items) if items else 0.0


def mean(values) -> float:
    return statistics.mean(values) if values else 0.0


def percentile(values, percent: int) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil((percent / 100) * len(ordered)) - 1))
    return ordered[index]


if __name__ == "__main__":
    sys.exit(main())
