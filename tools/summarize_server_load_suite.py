#!/usr/bin/env python3
"""Summarize BuildGraph server load profiles into append-only Korean research artifacts."""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import re
import sys
from pathlib import Path
from typing import Any


# breakpoint는 구 capacity의 개칭이다. 별칭으로 실행된 이전 산출물도 함께 인식한다.
PROFILES = ["smoke", "load", "stress", "spike", "soak", "breakpoint", "capacity"]
ENDPOINTS = [
    "auth",
    "auth_refresh",
    "health",
    "parts",
    "home_recommendations",
    "quote_draft",
    "build_history",
    "price_alerts",
    "assembly_requests",
    "ai_fast",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir")
    parser.add_argument("--reports-dir", default="infra/k6/reports")
    parser.add_argument("--suffix", default="20260711")
    parser.add_argument("--output", default="docs/reports/server-load-suite-20260711.md")
    parser.add_argument("--json-output", default="docs/reports/server-load-suite-20260711.json")
    return parser.parse_args()


def metric(data: dict[str, Any], name: str, key: str, default: float = 0.0) -> float:
    return float(((data.get("metrics") or {}).get(name) or {}).get("values", {}).get(key, default))


def report_path(args: argparse.Namespace, profile: str) -> Path:
    if args.run_dir:
        return Path(args.run_dir) / f"server-{profile}.json"
    return Path(args.reports_dir) / f"server-{profile}-{args.suffix}.json"


def load_memory_samples(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    with path.open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    if not rows:
        return None
    working_sets = [float(row["workingSetMb"]) for row in rows]
    private_sets = [float(row["privateMb"]) for row in rows]
    return {
        "samples": len(rows),
        "workingSetStartMb": working_sets[0],
        "workingSetEndMb": working_sets[-1],
        "workingSetDeltaMb": working_sets[-1] - working_sets[0],
        "workingSetPeakMb": max(working_sets),
        "privateStartMb": private_sets[0],
        "privateEndMb": private_sets[-1],
        "privateDeltaMb": private_sets[-1] - private_sets[0],
        "privatePeakMb": max(private_sets),
    }


def main() -> int:
    args = parse_args()
    rows: list[dict[str, Any]] = []
    raw_by_profile: dict[str, dict[str, Any]] = {}
    # run-dir에 실제로 존재하는 report만 집계한다. 없는 프로필은 경고 후 건너뛴다
    # (예: smoke만 실행한 run, breakpoint에서 knee abort로 일부만 실행된 run).
    for profile in PROFILES:
        path = report_path(args, profile)
        if not path.exists():
            print(f"warn: k6 report 없음, 건너뜀: {path}", file=sys.stderr)
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        raw_by_profile[profile] = data
        metrics = data.get("metrics") or {}
        threshold_failures = sum(
            1
            for value in metrics.values()
            for result in (value.get("thresholds") or {}).values()
            if not result.get("ok", False)
        )
        endpoint_p95 = {
            endpoint: metric(data, f"http_req_duration{{endpoint:{endpoint}}}", "p(95)")
            for endpoint in ENDPOINTS
        }
        rows.append({
            "profile": profile,
            "requests": int(metric(data, "http_reqs", "count")),
            "rps": metric(data, "http_reqs", "rate"),
            "avgMs": metric(data, "http_req_duration", "avg"),
            "p95Ms": metric(data, "http_req_duration", "p(95)"),
            "maxMs": metric(data, "http_req_duration", "max"),
            "failedRate": metric(data, "http_req_failed", "rate"),
            "failedCount": int(metric(data, "http_req_failed", "passes")),
            "checkRate": metric(data, "checks", "rate"),
            "droppedIterations": int(metric(data, "dropped_iterations", "count")),
            "thresholdFailures": threshold_failures,
            "endpointP95Ms": endpoint_p95,
        })

    if not rows:
        raise SystemExit("no k6 reports found (run-dir/reports-dir에서 server-*.json을 찾지 못함)")

    soak_data = raw_by_profile.get("soak") or {}
    soak_windows: list[dict[str, Any]] = []
    for name in sorted(soak_data.get("metrics") or {}):
        match = re.fullmatch(r"soak_window_(\d+)_duration", name)
        if not match:
            continue
        window = match.group(1)
        soak_windows.append({
            "window": int(window),
            "avgMs": metric(soak_data, name, "avg"),
            "p95Ms": metric(soak_data, name, "p(95)"),
            "maxMs": metric(soak_data, name, "max"),
            "failedRate": metric(soak_data, f"soak_window_{window}_failures", "rate"),
        })

    run_dir = Path(args.run_dir) if args.run_dir else Path(args.reports_dir)
    manifest_path = run_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig")) if manifest_path.exists() else {}
    memory = load_memory_samples(run_dir / "server-soak-resources.csv")
    result = {
        "generatedAt": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        "runId": manifest.get("runId", args.suffix),
        "commit": manifest.get("commit"),
        "branch": manifest.get("branch"),
        "baseUrl": manifest.get("baseUrl"),
        "soakDuration": manifest.get("soakDuration", "5m-short-baseline"),
        "profiles": rows,
        "soakWindows": soak_windows,
        "soakMemory": memory,
        # 각 summary의 buildgraph 키(k6 handleSummary가 기록한 실행 구성: offered mix,
        # SLO 프로필, LOGIN_RATIO 등)를 그대로 보존해 재현성을 남긴다.
        "profileConfigs": {
            profile: data.get("buildgraph")
            for profile, data in raw_by_profile.items()
        },
    }

    lines = [
        "# BuildGraph 전체 서버 부하·내구 연구 보고서",
        "",
        f"- 실행 ID: `{result['runId']}`",
        f"- 생성 시각: {result['generatedAt']}",
        f"- Git commit: `{result['commit'] or 'legacy-run'}`",
        f"- Soak 지속시간: `{result['soakDuration']}`",
        "- 범위: 인증, 부품 조회, 홈 추천부품, 견적초안, 견적 이력, 가격 알림, deterministic Build Chat",
        "- 외부 OpenAI LLM 호출은 고부하 혼합에서 제외하고 별도 Build Chat 보고서로 관리한다.",
        "",
        "| 종류 | 요청 수 | RPS | 평균(ms) | p95(ms) | 최대(ms) | 오류 건수 | 오류율 | 체크 성공률 | 누락 iteration | 실패 threshold |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in rows:
        lines.append(
            f"| {row['profile']} | {row['requests']:,} | {row['rps']:.2f} | {row['avgMs']:.2f} | "
            f"{row['p95Ms']:.2f} | {row['maxMs']:.2f} | {row['failedCount']} | {row['failedRate']:.3%} | "
            f"{row['checkRate']:.3%} | {row['droppedIterations']} | {row['thresholdFailures']} |"
        )

    lines.extend([
        "",
        "## 엔드포인트별 p95",
        "",
        "| 종류 | 로그인 | 토큰 갱신 | 헬스 | 부품 | 홈 추천 | 견적 초안 | 견적 이력 | 가격 알림 | 조립 요청 | AI fast |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ])
    for row in rows:
        endpoint = row["endpointP95Ms"]
        lines.append(
            f"| {row['profile']} | {endpoint['auth']:.2f} | {endpoint['auth_refresh']:.2f} | {endpoint['health']:.2f} | "
            f"{endpoint['parts']:.2f} | {endpoint['home_recommendations']:.2f} | "
            f"{endpoint['quote_draft']:.2f} | {endpoint['build_history']:.2f} | "
            f"{endpoint['price_alerts']:.2f} | {endpoint['assembly_requests']:.2f} | "
            f"{endpoint['ai_fast']:.2f} |"
        )

    if soak_windows:
        lines.extend([
            "",
            "## 60분 Soak 5분 구간 추세",
            "",
            "| 구간 | 평균(ms) | p95(ms) | 최대(ms) | 오류율 |",
            "|---:|---:|---:|---:|---:|",
        ])
        for window in soak_windows:
            lines.append(
                f"| {window['window']} | {window['avgMs']:.2f} | {window['p95Ms']:.2f} | "
                f"{window['maxMs']:.2f} | {window['failedRate']:.3%} |"
            )

    if memory:
        lines.extend([
            "",
            "## Soak JVM 메모리",
            "",
            f"- 표본: {memory['samples']}개",
            f"- Working set: {memory['workingSetStartMb']:.2f}MB → {memory['workingSetEndMb']:.2f}MB "
            f"(변화 {memory['workingSetDeltaMb']:+.2f}MB, 최대 {memory['workingSetPeakMb']:.2f}MB)",
            f"- Private memory: {memory['privateStartMb']:.2f}MB → {memory['privateEndMb']:.2f}MB "
            f"(변화 {memory['privateDeltaMb']:+.2f}MB, 최대 {memory['privatePeakMb']:.2f}MB)",
        ])

    lines.extend([
        "",
        "## 판정 기준",
        "",
        "- 전체 오류율 1% 미만, 체크 성공률 99% 초과",
        "- 전체 p95 1,500ms 미만, health p95 300ms 미만",
        "- parts/AI fast p95 800ms 미만",
        "- Breakpoint(구 Capacity)는 threshold 통과 자체보다 오류·누락 iteration이 시작되는 도착률(knee)을 한계로 본다.",
        "- 60분 Soak는 로컬 내구 근거이며, 운영 인증에는 동일 사양에서 2시간 이상 재실행을 권장한다.",
    ])

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    json_output = Path(args.json_output)
    json_output.parent.mkdir(parents=True, exist_ok=True)
    json_output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(output)
    print(json_output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
