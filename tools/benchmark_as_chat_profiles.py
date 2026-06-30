#!/usr/bin/env python3
"""Run AS Chat AI profiles against fixed quality cases and write a markdown report."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


DEFAULT_OPENAI_PROFILES = ["AS_CHAT_FAST", "AS_CHAT_NANO_FAST", "AS_CHAT_BALANCED", "AS_CHAT_HIGH_QUALITY"]


def main() -> int:
    parser = argparse.ArgumentParser(description="Benchmark AS Chat AI profiles")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--cases", default="tools/ai_quality_cases.json")
    parser.add_argument("--output-dir", default="docs/reports")
    parser.add_argument("--user-email", default="user@example.com")
    parser.add_argument("--user-password", default="passw0rd!")
    parser.add_argument("--admin-email", default="admin@example.com")
    parser.add_argument("--admin-password", default="passw0rd!")
    parser.add_argument("--profiles", nargs="+", default=None)
    parser.add_argument("--strict", action="store_true", help="모든 케이스가 통과하지 않으면 non-zero로 종료")
    args = parser.parse_args()

    cases = json.loads(Path(args.cases).read_text(encoding="utf-8"))
    profiles = selected_profiles(args.profiles)
    user_token = login(args.base_url, args.user_email, args.user_password)
    admin_token = login(args.base_url, args.admin_email, args.admin_password)

    results = []
    for profile in profiles:
        for case in cases:
            results.append(run_case(args.base_url, user_token, admin_token, profile, case))

    output = write_report(Path(args.output_dir), results)
    print(output)
    return 0 if not args.strict or all(result["success"] for result in results) else 1


def run_case(base_url: str, user_token: str, admin_token: str, profile: str, case: dict) -> dict:
    ticket = request_json(
        base_url,
        "POST",
        "/api/as-tickets",
        user_token,
        {"symptom": case["symptom"]},
    )
    ticket_id = ticket["id"]
    started = time.perf_counter()
    error = None
    response = None
    time_to_first_event_ms = None
    try:
        response, time_to_first_event_ms = request_sse_json(
            base_url,
            user_token,
            {"asTicketId": ticket_id, "message": case["message"]},
            {"X-BuildGraph-AI-Profile": profile},
            timeout=180,
        )
    except RuntimeError as exc:
        error = str(exc)
    wall_latency_ms = round((time.perf_counter() - started) * 1000)

    admin_detail = {}
    if response and response.get("agentSessionId"):
        try:
            admin_detail = request_json(
                base_url,
                "GET",
                f"/api/admin/agent-sessions/{response['agentSessionId']}",
                admin_token,
                None,
                timeout=30,
            )
        except RuntimeError:
            admin_detail = {}

    llm_generation = first((admin_detail.get("llmGenerations") or []), {})
    assistant_message = response.get("assistantMessage", "") if response else ""
    expected_keywords = case.get("expectedKeywords", [])
    keyword_hits = sum(1 for keyword in expected_keywords if keyword.lower() in assistant_message.lower())
    evidence_count = len(response.get("evidence", [])) if response else 0
    tool_count = len(response.get("toolResults", [])) if response else 0
    next_action_count = len(response.get("nextActions", [])) if response else 0
    grounded_rate = grounded_evidence_rate(response)
    unsupported_count = unsupported_claim_count(response, case)
    failure_type = provider_failure_type(error)
    schema_valid = bool(
        response
        and response.get("assistantMessage")
        and isinstance(response.get("causeCandidates"), list)
        and isinstance(response.get("nextActions"), list)
        and isinstance(response.get("escalation"), dict)
        and isinstance(response.get("ticketDraft"), dict)
    )
    success = bool(
        schema_valid
        and evidence_count >= 1
        and tool_count >= 1
        and next_action_count >= 2
        and keyword_hits >= max(1, math.ceil(len(expected_keywords) / 2))
        and unsupported_count == 0
        and grounded_rate >= 0.5
    )
    return {
        "profile": profile,
        "caseId": case["id"],
        "risk": case.get("risk", "-"),
        "success": success,
        "schemaValid": schema_valid,
        "timeToFirstEventMs": time_to_first_event_ms,
        "wallLatencyMs": wall_latency_ms,
        "recordedLatencyMs": llm_generation.get("latencyMs"),
        "inputTokens": llm_generation.get("inputTokens"),
        "outputTokens": llm_generation.get("outputTokens"),
        "totalTokens": llm_generation.get("totalTokens"),
        "provider": llm_generation.get("provider") or provider_from_profile(profile),
        "model": llm_generation.get("model") or response.get("model") if response else "-",
        "reasoningEffort": llm_generation.get("reasoningEffort"),
        "evidenceCount": evidence_count,
        "toolCount": tool_count,
        "nextActionCount": next_action_count,
        "keywordHits": keyword_hits,
        "keywordTotal": len(expected_keywords),
        "groundedEvidenceRate": grounded_rate,
        "unsupportedClaimCount": unsupported_count,
        "providerFailureType": failure_type,
        "error": error,
    }


def write_report(output_dir: Path, results: list[dict]) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    date = dt.datetime.now().strftime("%Y%m%d")
    output_path = output_dir / f"as-chat-profile-benchmark-{date}.md"
    grouped = {}
    for result in results:
        grouped.setdefault(result["profile"], []).append(result)

    lines = [
        "# AS Chat AI Profile Benchmark",
        "",
        f"- generatedAt: {dt.datetime.now().isoformat(timespec='seconds')}",
        f"- totalCases: {len(results)}",
        "",
        "## Summary",
        "",
        "| profile | provider | successRate | avgFirstEventMs | avgFinalLatencyMs | p95FinalLatencyMs | avgInputTokens | avgOutputTokens | avgTokens | schemaValidRate | avgGroundedEvidenceRate | avgUnsupportedClaims |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for profile, rows in grouped.items():
        success_rate = ratio(row["success"] for row in rows)
        schema_rate = ratio(row["schemaValid"] for row in rows)
        first_events = [row["timeToFirstEventMs"] for row in rows if row["timeToFirstEventMs"] is not None]
        latencies = [row["wallLatencyMs"] for row in rows]
        input_tokens = [row["inputTokens"] for row in rows if row["inputTokens"] is not None]
        output_tokens = [row["outputTokens"] for row in rows if row["outputTokens"] is not None]
        tokens = [row["totalTokens"] for row in rows if row["totalTokens"] is not None]
        grounded_rates = [row["groundedEvidenceRate"] for row in rows]
        unsupported_counts = [row["unsupportedClaimCount"] for row in rows]
        lines.append(
            f"| {profile} | {rows[0].get('provider') or provider_from_profile(profile)} | {success_rate:.1%} | {mean(first_events):.0f} | {mean(latencies):.0f} | {percentile(latencies, 95):.0f} | "
            f"{mean(input_tokens):.0f} | {mean(output_tokens):.0f} | {mean(tokens):.0f} | {schema_rate:.1%} | {mean(grounded_rates):.1%} | {mean(unsupported_counts):.1f} |"
        )

    provider_notes = availability_notes(results)
    if provider_notes:
        lines.extend(["", "## Provider Availability", ""])
        lines.extend(f"- {note}" for note in provider_notes)

    lines.extend([
        "",
        "## Cases",
        "",
        "| profile | provider | case | risk | ok | firstEventMs | finalLatencyMs | model | inTok | outTok | tokens | evidence | tools | actions | keywords | grounded | unsupported | failureType | error |",
        "|---|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|",
    ])
    for row in results:
        error = (row["error"] or "").replace("|", "/")
        lines.append(
            f"| {row['profile']} | {row['provider']} | {row['caseId']} | {row['risk']} | {yes(row['success'])} | "
            f"{value(row['timeToFirstEventMs'])} | {row['wallLatencyMs']} | {row['model']} | "
            f"{value(row['inputTokens'])} | {value(row['outputTokens'])} | {value(row['totalTokens'])} | "
            f"{row['evidenceCount']} | {row['toolCount']} | {row['nextActionCount']} | "
            f"{row['keywordHits']}/{row['keywordTotal']} | {row['groundedEvidenceRate']:.0%} | "
            f"{row['unsupportedClaimCount']} | {row['providerFailureType']} | {error} |"
        )

    lines.extend([
        "",
        "## Selection Notes",
        "",
        "- 기본 profile 후보는 schema valid 100%, 성공률 95% 이상을 먼저 만족해야 한다.",
        "- 근거 없는 단정 카운트가 0인 profile을 우선한다.",
        "- cause candidate가 RAG evidence 또는 Tool invocation을 참조하는 비율을 grounded evidence rate로 본다.",
        "- 첫 진행 이벤트 평균이 1초 이하인 profile을 우선한다.",
        "- 평균 응답 시간이 10초 이하인 profile을 우선한다.",
        "- p95 응답 시간이 20초를 넘으면 사용자 체감상 감점한다.",
        "- 품질 차이가 작으면 더 빠른 profile을 선택한다.",
        "- benchmark 명령은 기본적으로 보고서 생성을 성공으로 본다. 전체 통과를 CI gate로 강제하려면 `--strict`를 사용한다.",
    ])
    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return output_path


def login(base_url: str, email: str, password: str) -> str:
    response = request_json(base_url, "POST", "/api/auth/login", None, {"email": email, "password": password})
    token = response.get("accessToken")
    if not token:
        raise RuntimeError(f"login failed for {email}: accessToken missing")
    return token


def selected_profiles(explicit_profiles: list[str] | None) -> list[str]:
    if explicit_profiles:
        return explicit_profiles
    return list(DEFAULT_OPENAI_PROFILES)


def grounded_evidence_rate(response: dict | None) -> float:
    if not response:
        return 0.0
    candidates = response.get("causeCandidates")
    if not isinstance(candidates, list) or not candidates:
        return 0.0
    grounded = sum(1 for item in candidates if referenced_ids(item))
    return grounded / len(candidates)


def unsupported_claim_count(response: dict | None, case: dict) -> int:
    if not response:
        return 0
    candidates = response.get("causeCandidates")
    unsupported_candidates = 0
    if isinstance(candidates, list):
        unsupported_candidates = sum(1 for item in candidates if not referenced_ids(item))
    allowed_text = json.dumps(
        {
            "symptom": case.get("symptom"),
            "message": case.get("message"),
            "evidence": response.get("evidence"),
            "toolResults": response.get("toolResults"),
        },
        ensure_ascii=False,
    ).lower()
    assistant_text = json.dumps(
        {
            "assistantMessage": response.get("assistantMessage"),
            "causeCandidates": response.get("causeCandidates"),
            "nextActions": response.get("nextActions"),
            "ticketDraft": response.get("ticketDraft"),
        },
        ensure_ascii=False,
    ).lower()
    unsupported_numbers = sum(1 for claim in numeric_claims(assistant_text) if claim not in allowed_text)
    return unsupported_candidates + unsupported_numbers


def referenced_ids(item) -> bool:
    if not isinstance(item, dict):
        return False
    evidence_ids = item.get("evidenceIds")
    tool_ids = item.get("toolInvocationIds")
    return bool(evidence_ids) or bool(tool_ids)


def numeric_claims(text: str) -> set[str]:
    return set(re.findall(r"\d+(?:\.\d+)?\s?(?:fps|프레임|%|도|w|gb|만원|원)", text, flags=re.IGNORECASE))


def provider_failure_type(error: str | None) -> str:
    if not error:
        return "-"
    normalized = error.lower()
    if "resource_exhausted" in normalized or "429" in normalized or "quota" in normalized or "rate" in normalized:
        return "quota"
    if "401" in normalized or "403" in normalized or "api_key" in normalized:
        return "auth"
    if "428" in normalized or "precondition" in normalized:
        return "missing_key"
    if "json" in normalized or "schema" in normalized:
        return "schema"
    if "502" in normalized or "http" in normalized:
        return "upstream"
    return "runtime"


def provider_from_profile(profile: str) -> str:
    return "openai"


def availability_notes(results: list[dict]) -> list[str]:
    notes = []
    for provider in sorted({row.get("provider") for row in results if row.get("provider")}):
        quota_failures = [
            row for row in results
            if row.get("provider") == provider and row.get("providerFailureType") == "quota"
        ]
        if quota_failures:
            notes.append(f"{provider} profile은 API까지 도달했지만 quota/rate-limit 실패가 있어 품질 실패와 분리 기록했다.")
    return notes


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


def request_sse_json(
    base_url: str,
    token: str,
    body: dict,
    extra_headers: dict | None = None,
    timeout: int = 180,
) -> tuple[dict, int | None]:
    started = time.perf_counter()
    data = json.dumps(body).encode("utf-8")
    headers = {
        "Accept": "text/event-stream",
        "Content-Type": "application/json",
        "Authorization": f"Bearer {token}",
    }
    if extra_headers:
        headers.update(extra_headers)
    request = urllib.request.Request(
        f"{base_url}/api/ai/as-chat/stream",
        data=data,
        headers=headers,
        method="POST",
    )
    first_event_ms = None
    event_name = "message"
    data_lines = []
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            while True:
                raw_line = response.readline()
                if raw_line == b"":
                    break
                line = raw_line.decode("utf-8").rstrip("\r\n")
                if line == "":
                    if not data_lines:
                        event_name = "message"
                        continue
                    if first_event_ms is None:
                        first_event_ms = round((time.perf_counter() - started) * 1000)
                    payload = json.loads("\n".join(data_lines))
                    if event_name == "DONE":
                        return payload, first_event_ms
                    if event_name == "ERROR":
                        raise RuntimeError(f"POST /api/ai/as-chat/stream failed: {payload}")
                    event_name = "message"
                    data_lines = []
                    continue
                if line.startswith("event:"):
                    event_name = line[len("event:"):].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[len("data:"):].lstrip())
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"POST /api/ai/as-chat/stream failed: HTTP {exc.code} {raw}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"POST /api/ai/as-chat/stream failed: {exc}") from exc
    raise RuntimeError("POST /api/ai/as-chat/stream completed without DONE event")


def first(items: list, default):
    return items[0] if items else default


def yes(value_: bool) -> str:
    return "yes" if value_ else "no"


def value(value_):
    return "-" if value_ is None else str(value_)


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
