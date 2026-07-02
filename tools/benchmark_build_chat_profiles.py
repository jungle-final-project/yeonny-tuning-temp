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
    parser.add_argument("--user-email", default="user@example.com")
    parser.add_argument("--user-password", default="passw0rd!")
    parser.add_argument("--profiles", nargs="+", default=None)
    parser.add_argument("--variant-label", default="default")
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()

    cases = json.loads(Path(args.cases).read_text(encoding="utf-8"))
    profiles = args.profiles or DEFAULT_PROFILES
    token = login(args.base_url, args.user_email, args.user_password)
    results = [
        run_case(args.base_url, token, args.variant_label, profile, case)
        for profile in profiles
        for case in cases
    ]
    output = write_report(Path(args.output_dir), results)
    print(output)
    return 0 if not args.strict or all(result["success"] for result in results) else 1


def run_case(base_url: str, token: str, variant_label: str, profile: str, case: dict) -> dict:
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
    answer_type_ok = response and response.get("answerType") == case.get("expectedAnswerType")
    min_builds_ok = build_count >= case.get("expectedMinBuilds", 0)
    warning_ok = not case.get("expectWarning") or bool(response and response.get("warnings"))
    success = bool(
        schema_valid
        and answer_type_ok
        and min_builds_ok
        and hard_constraint_ok
        and action_ok
        and warning_ok
    )
    return {
        "variant": variant_label,
        "profile": profile,
        "caseId": case["id"],
        "success": success,
        "schemaValid": schema_valid,
        "latencyMs": latency_ms,
        "answerType": response.get("answerType") if response else "-",
        "buildCount": build_count,
        "actionTypes": ",".join(sorted(action_types)) if action_types else "-",
        "hardConstraintOk": hard_constraint_ok,
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


def write_report(output_dir: Path, results: list[dict]) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / f"build-chat-profile-benchmark-{dt.datetime.now().strftime('%Y%m%d')}.md"
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
        "| variant | profile | successRate | avgLatencyMs | p95LatencyMs | schemaValidRate |",
        "|---|---|---:|---:|---:|---:|",
    ]
    for (variant, profile), rows in grouped.items():
        latencies = [row["latencyMs"] for row in rows]
        lines.append(
            f"| {variant} | {profile} | {ratio(row['success'] for row in rows):.1%} | "
            f"{mean(latencies):.0f} | {percentile(latencies, 95):.0f} | "
            f"{ratio(row['schemaValid'] for row in rows):.1%} |"
        )

    lines.extend([
        "",
        "## Cases",
        "",
        "| variant | profile | case | ok | latencyMs | answerType | builds | actions | hardConstraint | warningOk | error |",
        "|---|---|---|---:|---:|---|---:|---|---:|---:|---|",
    ])
    for row in results:
        error = (row["error"] or "").replace("|", "/")
        lines.append(
            f"| {row['variant']} | {row['profile']} | {row['caseId']} | {yes(row['success'])} | "
            f"{row['latencyMs']} | {row['answerType']} | {row['buildCount']} | {row['actionTypes']} | "
            f"{yes(row['hardConstraintOk'])} | {yes(row['warningOk'])} | {error} |"
        )

    lines.extend([
        "",
        "## Notes",
        "",
        "- 이 벤치마크는 UI를 변경하지 않고 `/api/ai/build-chat`의 optional profile header만 바꿔 실행한다.",
        "- 기본 서비스 profile은 현재 `BUILD_CHAT_54_MINI_FAST`이며, 모델 비교가 필요하면 `--profiles`로 후보를 명시한다.",
        "- 5090 같은 명시 부품 조건은 추천 build의 GPU item에 보존되어야 한다.",
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
