#!/usr/bin/env python3
"""축소된 Build Chat(4-intent) 계약용 fast-path latency 벤치마크.

PR #53 이후 응답 계약은 answerType, message, builds, optional simulation, warnings 중심이며
partRecommendation/actions는 제거됐다. 이 스크립트는 그 계약에 맞춰 각 케이스의 응답을
검증하고 pathType별 latency 분포(p50/p95/max)를 출력한다.

기존 benchmark_build_chat_profiles.py는 제거 전 전체기능 계약(actions/route/direction) 전용이라
이 스크립트로 대체한다.

사용:
  python tools/benchmark_build_chat_fastpaths.py --repeat 5
"""

from __future__ import annotations

import argparse
import json
import statistics
import time
import urllib.error
import urllib.request
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description="Benchmark reduced Build Chat fast paths")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--cases", default="tools/build_chat_fastpath_cases.json")
    parser.add_argument("--user-email", default="user@example.com")
    parser.add_argument("--user-password", default="passw0rd!")
    parser.add_argument("--repeat", type=int, default=3, help="각 케이스 반복 횟수")
    parser.add_argument("--warmup", action="store_true", help="측정 전 각 케이스 1회 예열(캐시/티어 워밍업)")
    parser.add_argument("--strict", action="store_true", help="실패/지연 초과 시 비정상 종료코드 반환")
    args = parser.parse_args()

    cases = json.loads(Path(args.cases).read_text(encoding="utf-8"))
    token = login(args.base_url, args.user_email, args.user_password)

    if args.warmup:
        for case in cases:
            call_build_chat(args.base_url, token, case)

    rows = []
    for case in cases:
        latencies = []
        last = None
        for _ in range(max(1, args.repeat)):
            started = time.perf_counter()
            response = call_build_chat(args.base_url, token, case)
            latencies.append(round((time.perf_counter() - started) * 1000))
            last = response
        rows.append(evaluate(case, last, latencies))

    print_report(rows)
    ok = all(r["contractOk"] and r["latencyOk"] for r in rows)
    return 0 if not args.strict or ok else 1


def evaluate(case: dict, response: dict | None, latencies: list[int]) -> dict:
    builds = response.get("builds") if response else None
    warnings = response.get("warnings") if response else None
    simulation = response.get("simulation") if response else None
    build_count = len(builds) if isinstance(builds, list) else 0

    schema_ok = contract_schema_valid(response)
    answer_ok = bool(response) and response.get("answerType") == case.get("expectedAnswerType")
    builds_ok = build_count >= case.get("expectedMinBuilds", 0)
    warning_ok = (not case.get("expectWarning")) or bool(warnings)
    # target 불명확 시뮬레이션은 simulation 객체가 없어야 한다(임의 후보 금지 검증)
    sim_absent_ok = (not case.get("expectSimulationAbsent")) or (simulation is None)
    # 구체적 시뮬레이션은 simulation 객체가 있거나 되묻기(GENERAL)여야 한다
    sim_or_clarify_ok = (not case.get("expectSimulationOrClarify")) or (
        simulation is not None or (response and response.get("answerType") == "GENERAL")
    )
    contract_ok = all([schema_ok, answer_ok, builds_ok, warning_ok, sim_absent_ok, sim_or_clarify_ok])

    p50 = percentile(latencies, 50)
    p95 = percentile(latencies, 95)
    latency_ok = p95 <= case.get("maxLatencyMs", 6000)

    return {
        "id": case["id"],
        "answerType": response.get("answerType") if response else "-",
        "builds": build_count,
        "hasSimulation": simulation is not None,
        "p50": p50,
        "p95": p95,
        "max": max(latencies) if latencies else 0,
        "maxLatencyMs": case.get("maxLatencyMs", 6000),
        "contractOk": contract_ok,
        "latencyOk": latency_ok,
        "fail": _fail_reason(schema_ok, answer_ok, builds_ok, warning_ok, sim_absent_ok, sim_or_clarify_ok, latency_ok),
    }


def _fail_reason(schema_ok, answer_ok, builds_ok, warning_ok, sim_absent_ok, sim_or_clarify_ok, latency_ok) -> str:
    reasons = []
    if not schema_ok:
        reasons.append("schema")
    if not answer_ok:
        reasons.append("answerType")
    if not builds_ok:
        reasons.append("minBuilds")
    if not warning_ok:
        reasons.append("warning")
    if not sim_absent_ok:
        reasons.append("simulation-should-be-absent")
    if not sim_or_clarify_ok:
        reasons.append("simulation-or-clarify")
    if not latency_ok:
        reasons.append("latency")
    return ",".join(reasons) if reasons else "-"


def contract_schema_valid(response: dict | None) -> bool:
    if not isinstance(response, dict):
        return False
    return (
        isinstance(response.get("answerType"), str)
        and isinstance(response.get("message"), str)
        and isinstance(response.get("builds"), list)
        and isinstance(response.get("warnings"), list)
    )


def call_build_chat(base_url: str, token: str, case: dict) -> dict | None:
    body = {"message": case["message"]}
    if case.get("currentQuoteDraft"):
        body["currentQuoteDraft"] = case["currentQuoteDraft"]
    try:
        return request_json(base_url, "POST", "/api/ai/build-chat", token, body, timeout=180)
    except RuntimeError as exc:
        print(f"  [warn] {case['id']} 호출 실패: {exc}")
        return None


def print_report(rows: list[dict]) -> None:
    header = f"{'case':28} {'answerType':11} {'builds':6} {'sim':4} {'p50':>6} {'p95':>6} {'max':>6} {'≤ms':>6} {'ok':>4} {'fail'}"
    print(header)
    print("-" * len(header))
    for r in rows:
        ok = "PASS" if (r["contractOk"] and r["latencyOk"]) else "FAIL"
        print(
            f"{r['id']:28} {r['answerType']:11} {r['builds']:>6} "
            f"{'Y' if r['hasSimulation'] else '-':4} {r['p50']:>6} {r['p95']:>6} {r['max']:>6} "
            f"{r['maxLatencyMs']:>6} {ok:>4} {r['fail']}"
        )
    passed = sum(1 for r in rows if r["contractOk"] and r["latencyOk"])
    print("-" * len(header))
    print(f"통과 {passed}/{len(rows)}  ·  전체 p95={percentile([r['p95'] for r in rows], 95)}ms")


def login(base_url: str, email: str, password: str) -> str:
    response = request_json(base_url, "POST", "/api/auth/login", None, {"email": email, "password": password})
    token = response.get("accessToken")
    if not token:
        raise RuntimeError(f"login failed for {email}: accessToken missing")
    return token


def request_json(base_url, method, path, token, body, extra_headers=None, timeout=60):
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


def percentile(values, percent: int) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    import math
    index = max(0, min(len(ordered) - 1, math.ceil((percent / 100) * len(ordered)) - 1))
    return ordered[index]


if __name__ == "__main__":
    raise SystemExit(main())
