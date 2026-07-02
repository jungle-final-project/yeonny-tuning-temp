#!/usr/bin/env python3
"""Benchmark public RAG retrieval quality through GET /api/rag/search."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


def main() -> int:
    parser = argparse.ArgumentParser(description="Benchmark RAG retrieval variants")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--cases", default="tools/rag_retrieval_cases.json")
    parser.add_argument("--output-dir", default="docs/reports")
    parser.add_argument("--user-email", default="user@example.com")
    parser.add_argument("--user-password", default="passw0rd!")
    parser.add_argument("--variant-label", default="default")
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()

    cases = json.loads(Path(args.cases).read_text(encoding="utf-8"))
    token = login(args.base_url, args.user_email, args.user_password)
    results = [run_case(args.base_url, token, args.variant_label, case) for case in cases]
    output_dir = Path(args.output_dir)
    merged_results = merge_results(output_dir, results)
    output = write_report(output_dir, merged_results)
    print(output)
    return 0 if not args.strict or all(result["topKHit"] for result in results) else 1


def run_case(base_url: str, token: str, variant: str, case: dict[str, Any]) -> dict[str, Any]:
    purpose = None if case["purpose"] == "PUBLIC_SEARCH" else case["purpose"]
    required_top_k = int(case.get("requiredTopK") or 3)
    size = max(required_top_k, 10)
    query = {
        "q": case["query"],
        "limit": str(size),
    }
    if purpose:
        query["purpose"] = purpose
    if case.get("sourceType"):
        query["sourceType"] = case["sourceType"]

    started = time.perf_counter()
    response = None
    error = None
    try:
        response = request_json(base_url, "GET", "/api/rag/search", token, query=query, timeout=45)
    except RuntimeError as exc:
        error = str(exc)
    latency_ms = round((time.perf_counter() - started) * 1000)
    items = response.get("items", []) if response else []
    top_items = items[:required_top_k]
    top_one = items[:1]
    return {
        "variant": variant,
        "caseId": case["id"],
        "purpose": case["purpose"],
        "query": case["query"],
        "latencyMs": latency_ms,
        "top1Hit": hit(top_one, case),
        "topKHit": hit(top_items, case),
        "requiredTopK": required_top_k,
        "resultCount": len(items),
        "topSources": ", ".join(source_id(item) for item in top_items) or "-",
        "retrievalModes": ", ".join(sorted(retrieval_modes(top_items))) or "-",
        "error": error,
    }


def hit(items: list[dict[str, Any]], case: dict[str, Any]) -> bool:
    if not items:
        return False
    expected_ids = {str(item).lower() for item in case.get("expectedSourceIds", [])}
    if expected_ids:
        for item in items:
            if source_id(item).lower() in expected_ids or str(item.get("id", "")).lower() in expected_ids:
                return True
        return False

    expected_types = {str(item).upper() for item in case.get("expectedSourceTypes", [])}
    term_ok = must_terms_hit(items, case)
    if expected_types:
        type_ok = any(source_type(item).upper() in expected_types for item in items)
        return type_ok and term_ok
    return term_ok


def must_terms_hit(items: list[dict[str, Any]], case: dict[str, Any]) -> bool:
    terms = [str(term).lower() for term in case.get("mustContainTerms", []) if str(term).strip()]
    if not terms:
        return True
    haystack = "\n".join(json.dumps(item, ensure_ascii=False).lower() for item in items)
    matched = sum(1 for term in terms if term in haystack)
    return matched >= max(1, math.ceil(len(terms) / 2))


def retrieval_modes(items: list[dict[str, Any]]) -> set[str]:
    modes = set()
    for item in items:
        metadata = item.get("metadata")
        if isinstance(metadata, dict) and metadata.get("retrievalMode"):
            modes.add(str(metadata["retrievalMode"]))
    return modes


def source_id(item: dict[str, Any]) -> str:
    return str(item.get("sourceId") or "")


def source_type(item: dict[str, Any]) -> str:
    metadata = item.get("metadata")
    if isinstance(metadata, dict):
        return str(metadata.get("sourceType") or "")
    return ""


def merge_results(output_dir: Path, current_results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    output_dir.mkdir(parents=True, exist_ok=True)
    cache_path = output_dir / f"rag-retrieval-benchmark-{dt.datetime.now().strftime('%Y%m%d')}.json"
    variants = {row["variant"] for row in current_results}
    existing: list[dict[str, Any]] = []
    if cache_path.exists():
        try:
            existing = json.loads(cache_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            existing = []
    merged = [row for row in existing if row.get("variant") not in variants] + current_results
    cache_path.write_text(json.dumps(merged, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return merged


def write_report(output_dir: Path, results: list[dict[str, Any]]) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / f"rag-retrieval-benchmark-{dt.datetime.now().strftime('%Y%m%d')}.md"
    grouped: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for result in results:
        grouped.setdefault((result["variant"], result["purpose"]), []).append(result)
    distinct_case_count = len({result["caseId"] for result in results})
    variant_count = len({result["variant"] for result in results})

    lines = [
        "# RAG Retrieval Benchmark",
        "",
        f"- generatedAt: {dt.datetime.now().isoformat(timespec='seconds')}",
        f"- distinctCases: {distinct_case_count}",
        f"- variants: {variant_count}",
        f"- totalRows: {len(results)}",
        "- endpoint: `GET /api/rag/search`",
        "",
        "## Summary",
        "",
        "| variant | purpose | cases | top1HitRate | topKHitRate | avgLatencyMs | p95LatencyMs | avgResults |",
        "|---|---|---:|---:|---:|---:|---:|---:|",
    ]
    for (variant, purpose), rows in grouped.items():
        latencies = [row["latencyMs"] for row in rows]
        result_counts = [row["resultCount"] for row in rows]
        lines.append(
            f"| {variant} | {purpose} | {len(rows)} | {ratio(row['top1Hit'] for row in rows):.1%} | "
            f"{ratio(row['topKHit'] for row in rows):.1%} | {mean(latencies):.0f} | "
            f"{percentile(latencies, 95):.0f} | {mean(result_counts):.1f} |"
        )

    lines.extend([
        "",
        "## Cases",
        "",
        "| variant | purpose | case | top1 | topK | latencyMs | k | count | modes | topSources | error |",
        "|---|---|---|---:|---:|---:|---:|---:|---|---|---|",
    ])
    for row in results:
        error = (row["error"] or "").replace("|", "/")
        lines.append(
            f"| {row['variant']} | {row['purpose']} | {row['caseId']} | {yes(row['top1Hit'])} | "
            f"{yes(row['topKHit'])} | {row['latencyMs']} | {row['requiredTopK']} | {row['resultCount']} | "
            f"{row['retrievalModes']} | {row['topSources']} | {error} |"
        )

    lines.extend([
        "",
        "## Policy Reading Guide",
        "",
        "- `topKHitRate`가 vector-off와 2%p 미만 차이면 해당 경로는 latency를 보고 끌 후보가 된다.",
        "- `REQUIREMENT_PARSE`와 `BUILD_RECOMMEND`는 5090, 끝판왕, 예산 표현 같은 의미 검색 실패 감소를 우선한다.",
        "- `AS_ANALYZE`는 thermal, driver, memory, storage, power 증상 근거가 top3 안에 들어오는지를 우선한다.",
        "- 이 보고서는 기본 env를 바꾸지 않고 다음 PR의 정책 판단 근거로만 사용한다.",
    ])
    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return output_path


def login(base_url: str, email: str, password: str) -> str:
    response = request_json(base_url, "POST", "/api/auth/login", None, body={"email": email, "password": password})
    token = response.get("accessToken")
    if not token:
        raise RuntimeError(f"login failed for {email}: accessToken missing")
    return token


def request_json(
    base_url: str,
    method: str,
    path: str,
    token: str | None,
    body: dict[str, Any] | None = None,
    query: dict[str, str] | None = None,
    timeout: int = 60,
) -> dict[str, Any]:
    query_string = f"?{urllib.parse.urlencode(query)}" if query else ""
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(f"{base_url}{path}{query_string}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {path} failed: HTTP {exc.code} {raw}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"{method} {path} failed: {exc}") from exc


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


def yes(value: bool) -> str:
    return "yes" if value else "no"


if __name__ == "__main__":
    sys.exit(main())
