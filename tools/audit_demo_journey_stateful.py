#!/usr/bin/env python3
"""Run the 100-case four-minute demo journey audit against a live API.

The runner uses dedicated QA users, keeps every Build Chat call read-only, and
restores quote drafts after each case. Assembly requests and support tickets
are created through public APIs and moved to terminal states during cleanup.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import os
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from itertools import count
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

from audit_build_chat_stateful import (
    DraftManager,
    StatefulApiClient,
    blocking_graph,
    worker_email,
)
from benchmark_build_chat_scenario_qa import (
    PROFILE,
    draft_fingerprint,
    draft_from_verified_build,
    preview_builds,
    response_schema_valid,
    verified_virtual_draft,
)


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CASES = ROOT / "tools" / "demo_journey_stateful_audit_cases.json"
KST = ZoneInfo("Asia/Seoul")
HARD_FAILURES = {
    "CROSS_USER_DATA",
    "DRAFT_MUTATED_BY_CHAT",
    "DRAFT_RESTORE_FAILED",
    "TOOL_FAIL_RECOMMENDED",
    "APPLIED_BUILD_TOOL_FAIL",
    "ASSEMBLY_STATE_INVALID",
    "SUPPORT_PRIVATE_STATE_LEAK",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Four-minute demo stateful audit")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--cases", default=str(DEFAULT_CASES))
    parser.add_argument("--output-dir", default=str(ROOT / "docs" / "reports"))
    parser.add_argument("--results-dir", default=str(ROOT / ".qa-results" / "stateful"))
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--case-id", nargs="+")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--no-rerun", action="store_true")
    parser.add_argument("--strict", action="store_true")
    parser.add_argument("--user-email", default=os.environ.get("STATEFUL_QA_DEMO_USER_EMAIL", "stateful-demo-qa@example.com"))
    parser.add_argument("--user-password", default=os.environ.get("STATEFUL_QA_USER_PASSWORD", "passw0rd!"))
    parser.add_argument("--admin-email", default=os.environ.get("STATEFUL_QA_ADMIN_EMAIL", "admin@example.com"))
    return parser.parse_args()


def load_cases(path: str | Path) -> list[dict[str, Any]]:
    rows = json.loads(Path(path).read_text(encoding="utf-8"))
    expected = {
        "DEMO_REQUIREMENT_RECOMMEND": 20,
        "DEMO_GPU_DOWNGRADE_RESTORE": 20,
        "DEMO_ASSEMBLY_MATCH": 20,
        "DEMO_DIAGNOSIS_CONSENT": 20,
        "DEMO_REMOTE_SUPPORT": 20,
    }
    if not isinstance(rows, list) or len(rows) != 100:
        raise RuntimeError(f"phase-2 corpus must contain 100 cases, found {len(rows) if isinstance(rows, list) else 'invalid'}")
    if dict(Counter(str(row.get("group")) for row in rows)) != expected:
        raise RuntimeError("phase-2 group distribution is invalid")
    if len({str(row.get("id")) for row in rows}) != 100:
        raise RuntimeError("phase-2 case ids must be unique")
    if sum(bool(row.get("webReplay")) for row in rows) != 20:
        raise RuntimeError("phase-2 corpus must mark exactly 20 web replays")
    for row in rows:
        if row.get("profile") != PROFILE:
            raise RuntimeError(f"{row.get('id')}: profile must be {PROFILE}")
        if not isinstance(row.get("steps"), list) or len(row["steps"]) < 4:
            raise RuntimeError(f"{row.get('id')}: at least four state steps are required")
    return rows


def source_commit() -> str | None:
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def percentile(values: list[int], pct: int) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    return float(ordered[max(0, math.ceil(len(ordered) * pct / 100) - 1)])


def multipart_upload(client: StatefulApiClient, content: bytes, filename: str) -> tuple[int, dict[str, Any], int]:
    boundary = f"----BuildGraphStateful{uuid.uuid4().hex}"
    chunks = []
    for name, value in {"rangeMinutes": "30", "consentAccepted": "true"}.items():
        chunks.append(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode()
        )
    chunks.append(
        (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
            "Content-Type: application/x-ndjson\r\n\r\n"
        ).encode()
    )
    chunks.extend([content, f"\r\n--{boundary}--\r\n".encode()])
    request = urllib.request.Request(
        client.base_url + "/api/agent-logs/upload",
        data=b"".join(chunks),
        method="POST",
        headers={
            "Accept": "application/json",
            "Authorization": f"Bearer {client.token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            raw = response.read().decode("utf-8", "replace")
            return response.status, json.loads(raw) if raw else {}, round((time.perf_counter() - started) * 1000)
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", "replace")
        try:
            payload = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            payload = {"message": raw}
        return error.code, payload, round((time.perf_counter() - started) * 1000)


def graph_has_fail(graph: dict[str, Any] | None) -> bool:
    return bool(graph) and any(str(row.get("status") or "").upper() == "FAIL" for row in graph.get("toolResults") or [])


def build_has_tool_fail(build: dict[str, Any]) -> bool:
    return any(str(row.get("status") or "").upper() == "FAIL" for row in build.get("toolResults") or [])


def tomorrow(days: int) -> str:
    return (dt.date.today() + dt.timedelta(days=days)).isoformat()


def qa_log(case_id: str) -> bytes:
    rows = [
        {
            "timestamp": dt.datetime.now(dt.timezone.utc).isoformat(),
            "eventId": "Display-4101",
            "source": "Display",
            "message": "Display driver nvlddmkm stopped responding and recovered",
            "symptom": "game black screen",
            "caseId": case_id,
        },
        {
            "timestamp": dt.datetime.now(dt.timezone.utc).isoformat(),
            "eventId": "GPU-METRIC",
            "gpuTempC": 83,
            "gpuUsagePercent": 99,
            "fps": 0,
            "caseId": case_id,
        },
    ]
    return ("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n").encode("utf-8")


@dataclass
class WorkerContext:
    index: int
    client: StatefulApiClient
    admin: StatefulApiClient
    other_user: StatefulApiClient
    manager: DraftManager


def chat(
    context: WorkerContext,
    message: str,
    draft: dict[str, Any],
    current_builds: list[dict[str, Any]] | None = None,
    previous_response: dict[str, Any] | None = None,
) -> tuple[int, dict[str, Any], int]:
    body: dict[str, Any] = {"message": message, "currentQuoteDraft": draft}
    if current_builds:
        body["currentBuilds"] = current_builds[:3]
    clarification = (previous_response or {}).get("clarification") or {}
    if isinstance(clarification, dict) and clarification.get("originalMessage"):
        body["clarificationContext"] = {"originalMessage": clarification["originalMessage"]}
    status, response, latency, _ = context.client.request(
        "POST",
        "/api/ai/build-chat",
        body,
        headers={"X-BuildGraph-AI-Profile": PROFILE},
    )
    return status, response, latency


def build_chat_failure_basics(status: int, response: dict[str, Any]) -> list[str]:
    failures = []
    if status != 200:
        failures.append(f"HTTP_{status}")
    if not response_schema_valid(response):
        failures.append("SCHEMA_INVALID")
        return failures
    if any(build_has_tool_fail(build) for build in response.get("builds") or []):
        failures.append("TOOL_FAIL_RECOMMENDED")
    return failures


def target_budget(message: str) -> int:
    for value in (180, 200, 220, 250):
        if f"{value}만원" in message:
            return value * 10_000
    return 2_000_000


def run_recommendation(context: WorkerContext, case: dict[str, Any], trace: list[dict[str, Any]]) -> list[str]:
    failures: list[str] = []
    context.manager.replace_persisted([])
    before = context.manager.current_snapshot()
    message = str(case["steps"][0]["message"])
    status, response, latency = chat(context, message, before)
    failures.extend(build_chat_failure_basics(status, response))
    builds = response.get("builds") or []
    if not 1 <= len(builds) <= 3:
        failures.append("RECOMMENDATION_COUNT_INVALID")
    budget = target_budget(message)
    for build in builds:
        price = int(build.get("totalPrice") or 0)
        if not budget * 0.875 <= price <= budget * 1.125:
            failures.append("TARGET_BUDGET_BAND_VIOLATION")
        if "GPU" not in {str(item.get("category")) for item in build.get("items") or []}:
            failures.append("GAMING_GPU_MISSING")
        graph = context.client.resolve_graph(build)
        if graph_has_fail(graph):
            failures.append("TOOL_FAIL_RECOMMENDED")
    after_chat = context.manager.current_snapshot()
    if draft_fingerprint(before) != draft_fingerprint(after_chat):
        failures.append("DRAFT_MUTATED_BY_CHAT")
    applied = None
    if builds:
        applied = draft_from_verified_build(context.client, builds[0])
        if not applied:
            failures.append("BUILD_APPLICATION_DATA_MISSING")
        else:
            persisted = context.manager.replace_persisted(applied.get("items") or [])
            if not persisted.get("items"):
                failures.append("BUILD_APPLICATION_EMPTY")
            if blocking_graph(context.client.resolve_graph(persisted)):
                failures.append("APPLIED_BUILD_TOOL_FAIL")
    trace.append({
        "step": "recommend-and-apply", "message": message, "status": status,
        "latencyMs": latency, "response": response, "draftBefore": draft_fingerprint(before),
        "draftAfterChat": draft_fingerprint(after_chat),
        "draftAfterApply": draft_fingerprint(context.manager.current_snapshot()) if applied else None,
    })
    return failures


def run_gpu_downgrade(context: WorkerContext, case: dict[str, Any], trace: list[dict[str, Any]]) -> list[str]:
    failures: list[str] = []
    items, _ = context.manager.resolve("COMPLETE_VERIFIED")
    original = context.manager.replace_persisted(items)
    status, response, latency = chat(context, str(case["steps"][1]["message"]), original)
    failures.extend(build_chat_failure_basics(status, response))
    previews = preview_builds(response)
    if not previews:
        failures.append("GPU_PREVIEW_MISSING")
    if draft_fingerprint(original) != draft_fingerprint(context.manager.current_snapshot()):
        failures.append("DRAFT_MUTATED_BY_CHAT")
    preview = previews[0] if previews else None
    if preview:
        current_gpu = next((row for row in original.get("items") or [] if row.get("category") == "GPU"), None)
        next_gpu = next((row for row in preview.get("items") or [] if row.get("category") == "GPU"), None)
        if not current_gpu or not next_gpu or str(current_gpu.get("partId")) == str(next_gpu.get("partId")):
            failures.append("GPU_NOT_CHANGED")
        elif int(next_gpu.get("currentPrice") or next_gpu.get("price") or 0) >= int(current_gpu.get("currentPrice") or current_gpu.get("price") or 0):
            failures.append("GPU_NOT_CHEAPER")
        simulation_status, simulation, simulation_latency = chat(
            context,
            str(case["steps"][3]["message"]),
            original,
            [preview],
            response,
        )
        failures.extend(build_chat_failure_basics(simulation_status, simulation))
        if not simulation.get("simulation"):
            failures.append("SIMULATION_MISSING")
        if preview_builds(simulation):
            failures.append("SIMULATION_CREATED_MUTATION")
        candidate = draft_from_verified_build(context.client, preview)
        if candidate:
            applied = context.manager.replace_persisted(candidate.get("items") or [])
            if blocking_graph(context.client.resolve_graph(applied)):
                failures.append("APPLIED_BUILD_TOOL_FAIL")
        else:
            failures.append("GPU_PREVIEW_APPLICATION_DATA_MISSING")
        trace.append({
            "step": "simulate", "status": simulation_status, "latencyMs": simulation_latency,
            "response": simulation,
        })
    trace.append({
        "step": "gpu-downgrade-preview", "status": status, "latencyMs": latency,
        "response": response, "draftBefore": draft_fingerprint(original),
        "draftAfterChat": draft_fingerprint(context.manager.current_snapshot()),
    })
    context.manager.restore(original)
    if draft_fingerprint(context.manager.current_snapshot()) != draft_fingerprint(original):
        failures.append("GPU_RESTORE_FAILED")
    return failures


def run_assembly(context: WorkerContext, case: dict[str, Any], trace: list[dict[str, Any]]) -> list[str]:
    failures: list[str] = []
    items, _ = context.manager.resolve("COMPLETE_VERIFIED")
    context.manager.replace_persisted(items)
    request_body = {
        "serviceType": "FULL_SERVICE",
        "region": "서울",
        "preferredDate": tomorrow(3 + context.index),
        "deliveryMethod": "DELIVERY",
        "note": f"상태형 데모 감사 {case['id']}",
        "asPolicyAccepted": True,
    }
    headers = {"Idempotency-Key": f"stateful-{case['id']}-{uuid.uuid4().hex}"}
    status, created, latency, _ = context.client.request("POST", "/api/assembly-requests", request_body, headers=headers)
    if status not in {200, 201}:
        failures.append(f"ASSEMBLY_CREATE_HTTP_{status}")
        trace.append({"step": "assembly-create", "status": status, "response": created, "latencyMs": latency})
        return failures
    request_id = str(created.get("id") or "")
    offers = [row for row in created.get("offers") or [] if row.get("status") == "AVAILABLE"]
    internal_offers = [row for row in offers if row.get("providerType") == "INTERNAL"]
    if not request_id or not 1 <= len(internal_offers) <= 2:
        failures.append("INTERNAL_OFFER_COUNT_INVALID")
    other_status, _, _, _ = context.other_user.request("GET", f"/api/assembly-requests/{request_id}")
    if other_status != 404:
        failures.append("CROSS_USER_DATA")
    refresh_status, refreshed, refresh_ms, _ = context.client.request("GET", f"/api/assembly-requests/{request_id}")
    if refresh_status != 200 or str(refreshed.get("id") or "") != request_id:
        failures.append("ASSEMBLY_REFRESH_FAILED")
    if offers:
        offer_id = str(offers[0].get("id") or "")
        select_status, selected, select_ms, _ = context.client.request(
            "POST", f"/api/assembly-requests/{request_id}/offers/{offer_id}/select"
        )
        if select_status != 200 or selected.get("status") != "MATCHED":
            failures.append("ASSEMBLY_MATCH_STATE_INVALID")
        pay_status, paid, pay_ms, _ = context.client.request(
            "POST", f"/api/assembly-requests/{request_id}/payments/confirm-virtual"
        )
        if pay_status != 200 or (paid.get("payment") or {}).get("status") != "PAID":
            failures.append("ASSEMBLY_PAYMENT_STATE_INVALID")
        trace.extend([
            {"step": "select-offer", "status": select_status, "latencyMs": select_ms, "response": selected},
            {"step": "confirm-virtual-payment", "status": pay_status, "latencyMs": pay_ms, "response": paid},
        ])
    trace.extend([
        {"step": "assembly-create", "status": status, "latencyMs": latency, "response": created},
        {"step": "assembly-refresh", "status": refresh_status, "latencyMs": refresh_ms, "response": refreshed},
    ])
    cancel_status, cancelled, cancel_ms, _ = context.client.request(
        "POST", f"/api/assembly-requests/{request_id}/cancel", {"reason": "상태형 QA 정리"}
    )
    if cancel_status != 200 or cancelled.get("status") != "CANCELLED":
        failures.append("ASSEMBLY_CLEANUP_FAILED")
    payment = cancelled.get("payment") or {}
    if payment and payment.get("status") not in {"REFUNDED", "CANCELLED"}:
        failures.append("ASSEMBLY_REFUND_STATE_INVALID")
    trace.append({"step": "assembly-cancel", "status": cancel_status, "latencyMs": cancel_ms, "response": cancelled})
    return failures


def run_diagnosis_consent(context: WorkerContext, case: dict[str, Any], trace: list[dict[str, Any]]) -> list[str]:
    failures: list[str] = []
    before = context.manager.current_snapshot()
    message = str(case["steps"][0]["message"])
    status, response, latency = chat(context, message, before)
    failures.extend(build_chat_failure_basics(status, response))
    guidance = response.get("supportGuidance") or {}
    text = " ".join([
        str(response.get("message") or ""),
        json.dumps(guidance, ensure_ascii=False),
        " ".join(response.get("quickReplies") or []),
    ]).lower()
    if not guidance:
        failures.append("SUPPORT_GUIDANCE_MISSING")
    if not any(term in text for term in ["가능", "예상", "원인", "드라이버", "그래픽", "진단"]):
        failures.append("PROBABILISTIC_GUIDANCE_MISSING")
    if not any(term in text for term in ["동의", "agent", "에이전트", "진단"]):
        failures.append("DIAGNOSIS_CONSENT_PATH_MISSING")
    if not any(term in text for term in ["다운로드", "support/new", "as 접수", "접수"]):
        failures.append("AGENT_OR_SUPPORT_ENTRY_MISSING")
    token_status, token, token_ms, _ = context.client.request("POST", "/api/users/me/agent-activation-token", {})
    if token_status not in {200, 201} or not token.get("activationToken"):
        failures.append("AGENT_ACTIVATION_TOKEN_FAILED")
    if draft_fingerprint(before) != draft_fingerprint(context.manager.current_snapshot()):
        failures.append("DRAFT_MUTATED_BY_CHAT")
    trace.extend([
        {"step": "shopping-ai-symptom", "status": status, "latencyMs": latency, "response": response},
        {"step": "agent-activation-token", "status": token_status, "latencyMs": token_ms,
         "response": {key: value for key, value in token.items() if key != "activationToken"}},
    ])
    return failures


def close_ticket(context: WorkerContext, ticket_id: str) -> tuple[int, dict[str, Any], int]:
    total_latency = 0
    status, payload, latency, _ = context.admin.request("GET", f"/api/admin/as-tickets/{ticket_id}")
    total_latency += latency
    if status != 200:
        return status, payload, total_latency
    current = str(payload.get("status") or "")
    if current in {"CLOSED", "CANCELLED"}:
        return 200, payload, total_latency
    if current != "RESOLVED":
        status, payload, latency, _ = context.admin.request(
            "PATCH", f"/api/admin/as-tickets/{ticket_id}",
            {"status": "RESOLVED", "adminNote": "상태형 QA 원격지원 완료"},
        )
        total_latency += latency
        if status != 200:
            return status, payload, total_latency
    status, payload, latency, _ = context.admin.request(
        "PATCH", f"/api/admin/as-tickets/{ticket_id}",
        {"status": "CLOSED", "adminNote": "상태형 QA 종료"},
    )
    return status, payload, total_latency + latency


def reset_active_support_ticket(context: WorkerContext, trace: list[dict[str, Any]]) -> None:
    status, payload, latency, _ = context.client.request("GET", "/api/support/chat-sessions/current")
    if status != 200:
        raise RuntimeError(f"support precondition lookup failed: status={status}")
    contact = payload.get("contact") if isinstance(payload, dict) else None
    ticket_id = str((contact or {}).get("asTicketId") or "")
    if not ticket_id:
        return
    close_status, closed, close_ms = close_ticket(context, ticket_id)
    trace.append({
        "step": "support-precondition-reset",
        "status": close_status,
        "latencyMs": latency + close_ms,
        "response": {"ticketId": ticket_id, "closedStatus": closed.get("status")},
    })
    if close_status != 200 or closed.get("status") != "CLOSED":
        raise RuntimeError(f"support precondition reset failed: status={close_status}")


def run_remote_support(context: WorkerContext, case: dict[str, Any], trace: list[dict[str, Any]]) -> list[str]:
    failures: list[str] = []
    reset_active_support_ticket(context, trace)
    upload_status, uploaded, upload_ms = multipart_upload(context.client, qa_log(case["id"]), f"{case['id']}.jsonl")
    log_id = str(uploaded.get("id") or "")
    if upload_status not in {200, 201} or not log_id:
        failures.append("LOG_UPLOAD_FAILED")
        trace.append({"step": "log-upload", "status": upload_status, "latencyMs": upload_ms, "response": uploaded})
        return failures
    create_status, ticket, create_ms, _ = context.client.request(
        "POST", "/api/as-tickets",
        {"symptom": "게임 실행 중 검은 화면과 그래픽 드라이버 중단", "logUploadId": log_id},
    )
    ticket_id = str(ticket.get("id") or "")
    if create_status not in {200, 201} or not ticket_id:
        failures.append("SUPPORT_TICKET_CREATE_FAILED")
        trace.append({"step": "ticket-create", "status": create_status, "latencyMs": create_ms, "response": ticket})
        return failures
    evidence_text = json.dumps({
        "causeCandidates": ticket.get("causeCandidates"),
        "supportDecision": ticket.get("supportDecision"),
        "analysisStatus": ticket.get("analysisStatus"),
        "adminNote": ticket.get("adminNote"),
    }, ensure_ascii=False).lower()
    if not any(term in evidence_text for term in ["display", "driver", "드라이버", "gpu", "그래픽"]):
        failures.append("LOG_EVIDENCE_MISSING")
    other_status, _, _, _ = context.other_user.request("GET", f"/api/as-tickets/{ticket_id}")
    if other_status != 404:
        failures.append("SUPPORT_PRIVATE_STATE_LEAK")
    remote_status, remote, remote_ms, _ = context.client.request(
        "POST", f"/api/as-tickets/{ticket_id}/remote-support-requests",
        {"reason": "개인정보를 제외한 진단 자료 전달에 동의하고 원격지원을 요청합니다."},
    )
    if remote_status not in {200, 201} or remote.get("remoteSupportStatus") != "REQUESTED":
        failures.append("REMOTE_SUPPORT_REQUEST_FAILED")
    link = f"https://support.example.test/session/{ticket_id}"
    patch_status, patched, patch_ms, _ = context.admin.request(
        "PATCH", f"/api/admin/as-tickets/{ticket_id}",
        {
            "reviewStatus": "APPROVED",
            "supportDecision": "REMOTE_POSSIBLE",
            "riskLevel": "MEDIUM",
            "diagnosticAccuracy": "ACCURATE",
            "remoteSupportLink": link,
            "adminNote": "그래픽 드라이버 재설치 원격지원 준비",
        },
    )
    if patch_status != 200 or patched.get("remoteSupportLink") != link:
        failures.append("REMOTE_SUPPORT_ADMIN_LINK_FAILED")
    get_status, visible, get_ms, _ = context.client.request("GET", f"/api/as-tickets/{ticket_id}")
    if get_status != 200 or visible.get("remoteSupportLink") != link:
        failures.append("REMOTE_SUPPORT_USER_STATUS_MISSING")
    trace.extend([
        {"step": "log-upload", "status": upload_status, "latencyMs": upload_ms, "response": uploaded},
        {"step": "ticket-create", "status": create_status, "latencyMs": create_ms, "response": ticket},
        {"step": "remote-request", "status": remote_status, "latencyMs": remote_ms, "response": remote},
        {"step": "admin-link", "status": patch_status, "latencyMs": patch_ms, "response": patched},
        {"step": "user-status", "status": get_status, "latencyMs": get_ms, "response": visible},
    ])
    close_status, closed, close_ms = close_ticket(context, ticket_id)
    if close_status != 200 or closed.get("status") != "CLOSED":
        failures.append("SUPPORT_TICKET_CLEANUP_FAILED")
    trace.append({"step": "ticket-close", "status": close_status, "latencyMs": close_ms, "response": closed})
    return failures


RUNNERS = {
    "DEMO_REQUIREMENT_RECOMMEND": run_recommendation,
    "DEMO_GPU_DOWNGRADE_RESTORE": run_gpu_downgrade,
    "DEMO_ASSEMBLY_MATCH": run_assembly,
    "DEMO_DIAGNOSIS_CONSENT": run_diagnosis_consent,
    "DEMO_REMOTE_SUPPORT": run_remote_support,
}


def run_attempt(context: WorkerContext, case: dict[str, Any], attempt: int) -> dict[str, Any]:
    original = context.manager.current_snapshot()
    complete_items, _ = context.manager.resolve("COMPLETE_VERIFIED")
    trace: list[dict[str, Any]] = []
    failures: list[str] = []
    harness_errors: list[str] = []
    try:
        failures.extend(RUNNERS[case["group"]](context, case, trace))
    except Exception as error:
        harness_errors.append(f"{type(error).__name__}: {error}")
    finally:
        try:
            context.manager.restore(original)
            restored = True
        except Exception as error:
            restored = False
            failures.append("DRAFT_RESTORE_FAILED")
            harness_errors.append(f"restore: {type(error).__name__}: {error}")
    failures = sorted(set(failures))
    return {
        "attempt": attempt,
        "failures": failures,
        "harnessErrors": harness_errors,
        "restored": restored,
        "resolvedCompleteItems": complete_items,
        "trace": trace,
    }


def execute_case(context: WorkerContext, case: dict[str, Any], rerun: bool) -> dict[str, Any]:
    first = run_attempt(context, case, 1)
    second = None
    if rerun and first["failures"] and not first["harnessErrors"]:
        second = run_attempt(context, case, 2)
    if first["harnessErrors"]:
        verdict = "HARNESS_GAP"
        reasons = first["harnessErrors"]
    elif not first["failures"]:
        verdict = "PASS"
        reasons = []
    elif set(first["failures"]) & HARD_FAILURES:
        verdict = "CONFIRMED_BUG"
        reasons = first["failures"]
    elif second is None:
        verdict = "SUSPECTED"
        reasons = first["failures"]
    else:
        repeated = sorted(set(first["failures"]) & set(second["failures"]))
        verdict = "CONFIRMED_BUG" if repeated else "SUSPECTED"
        reasons = repeated or first["failures"]
    return {
        "caseId": case["id"],
        "group": case["group"],
        "journeyVariant": case.get("journeyVariant"),
        "webReplay": bool(case.get("webReplay")),
        "verdict": verdict,
        "confirmedReasons": reasons,
        "attempts": [row for row in (first, second) if row],
    }


def summarize(results: list[dict[str, Any]], duration_ms: int) -> dict[str, Any]:
    verdicts = Counter(row["verdict"] for row in results)
    groups: dict[str, Counter[str]] = defaultdict(Counter)
    latencies = []
    for row in results:
        groups[row["group"]][row["verdict"]] += 1
        first = (row.get("attempts") or [{}])[0]
        for step in first.get("trace") or []:
            if step.get("latencyMs") is not None:
                latencies.append(int(step["latencyMs"]))
    return {
        "caseCount": len(results),
        "verdicts": dict(verdicts),
        "groupVerdicts": {group: dict(counts) for group, counts in groups.items()},
        "draftRestored": sum(bool((row.get("attempts") or [{}])[0].get("restored")) for row in results),
        "webReplayExpected": sum(bool(row.get("webReplay")) for row in results),
        "durationMs": duration_ms,
        "latencyDiagnostic": {
            "averageMs": round(statistics.mean(latencies), 1) if latencies else 0,
            "p95Ms": percentile(latencies, 95),
            "maxMs": max(latencies, default=0),
        },
    }


def write_outputs(results: list[dict[str, Any]], summary: dict[str, Any], cases: list[dict[str, Any]], args: argparse.Namespace) -> tuple[Path, Path, Path]:
    now = dt.datetime.now(KST)
    date = now.strftime("%Y%m%d")
    output_dir = Path(args.output_dir)
    results_dir = Path(args.results_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    results_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / f"demo-journey-stateful-audit-{date}-phase2.json"
    md_path = output_dir / f"demo-journey-stateful-audit-{date}-phase2.md"
    replay_path = results_dir / "demo-journey-stateful-web-replay.json"
    payload = {
        "generatedAt": now.isoformat(),
        "sourceCommit": source_commit(),
        "profile": PROFILE,
        "summary": summary,
        "results": results,
    }
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    result_by_id = {row["caseId"]: row for row in results}
    selected = []
    for case in cases:
        if not case.get("webReplay"):
            continue
        result = result_by_id.get(case["id"]) or {}
        attempt = (result.get("attempts") or [{}])[0]
        selected.append({**case, "setupItems": attempt.get("resolvedCompleteItems") or []})
    replay_path.write_text(json.dumps(selected, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = [
        "# 4분 데모 상세 상태 전이 2차 감사",
        "",
        f"- 생성 시각: `{now.isoformat()}`",
        f"- 기준 commit: `{payload['sourceCommit'] or 'unknown'}`",
        f"- Build Chat profile: `{PROFILE}`",
        "- 추천부터 원격지원까지 실제 API 상태 전이를 실행하고 생성 데이터는 terminal 상태로 정리했다.",
        "",
        "## 요약",
        "",
        f"- 실행 case: **{summary['caseCount']}/100**",
        f"- PASS: **{summary['verdicts'].get('PASS', 0)}**",
        f"- 확정 버그: **{summary['verdicts'].get('CONFIRMED_BUG', 0)}**",
        f"- 의심 사례: **{summary['verdicts'].get('SUSPECTED', 0)}**",
        f"- harness gap: **{summary['verdicts'].get('HARNESS_GAP', 0)}**",
        f"- draft 원복: **{summary['draftRestored']}/{summary['caseCount']}**",
        "",
        "## 그룹별 결과",
        "",
        "| 그룹 | PASS | 확정 | 의심 | harness gap |",
        "|---|---:|---:|---:|---:|",
    ]
    for group, counts in summary["groupVerdicts"].items():
        lines.append(
            f"| {group} | {counts.get('PASS', 0)} | {counts.get('CONFIRMED_BUG', 0)} | "
            f"{counts.get('SUSPECTED', 0)} | {counts.get('HARNESS_GAP', 0)} |"
        )
    lines.extend(["", "## 실패 사례", "", "| case | 그룹 | 판정 | 반복 위반 |", "|---|---|---|---|"])
    for row in results:
        if row["verdict"] != "PASS":
            lines.append(f"| {row['caseId']} | {row['group']} | {row['verdict']} | {', '.join(row['confirmedReasons'])} |")
    lines.extend([
        "",
        "## 지연 진단",
        "",
        f"- 평균: **{summary['latencyDiagnostic']['averageMs'] / 1000:.3f}초**",
        f"- p95: **{summary['latencyDiagnostic']['p95Ms'] / 1000:.3f}초**",
        f"- 최대: **{summary['latencyDiagnostic']['maxMs'] / 1000:.3f}초**",
        "",
        "지연은 진단 자료로만 기록했으며 timeout 또는 5xx가 아닌 경우 기능 실패로 계산하지 않았다.",
        "",
        "## 원본 증거",
        "",
        f"전체 상태 전이 응답과 2회 재현 기록은 `{json_path.name}`에 있다.",
        f"브라우저 대표 재현 20개는 `{replay_path.relative_to(ROOT).as_posix()}`에 생성했다.",
    ])
    md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return md_path, json_path, replay_path


def main() -> int:
    args = parse_args()
    all_cases = load_cases(args.cases)
    cases = list(all_cases)
    if args.case_id:
        wanted = set(args.case_id)
        cases = [row for row in cases if row["id"] in wanted]
    if args.limit is not None:
        cases = cases[:max(0, args.limit)]
    if args.validate_only:
        print(f"Validated phase-2 cases: total=100 selected={len(cases)} webReplay={sum(bool(row.get('webReplay')) for row in cases)}")
        return 0
    if not cases:
        raise RuntimeError("no phase-2 cases selected")

    bootstrap = StatefulApiClient(
        args.base_url,
        worker_email(args.user_email, 0),
        args.user_password,
        name="Demo QA Bootstrap",
        provision=True,
    )
    active_parts = bootstrap.active_parts()
    base_draft = verified_virtual_draft(bootstrap, active_parts)
    workers = max(1, int(args.workers))
    local = threading.local()
    context_counter = count(1)
    context_lock = threading.Lock()

    def context() -> WorkerContext:
        if not hasattr(local, "value"):
            with context_lock:
                index = next(context_counter)
            client = StatefulApiClient(
                args.base_url,
                worker_email(args.user_email, index),
                args.user_password,
                name=f"Demo QA Worker {index}",
                provision=True,
            )
            other = bootstrap if index != 0 else client
            admin = StatefulApiClient(
                args.base_url,
                args.admin_email,
                args.user_password,
                name="Demo QA Admin",
                provision=False,
            )
            client.part_cache.update({str(row["id"]): row for row in active_parts if row.get("id")})
            manager = DraftManager(client, active_parts, base_draft)
            local.value = WorkerContext(index, client, admin, other, manager)
        return local.value

    started = time.perf_counter()
    ordered: dict[int, dict[str, Any]] = {}
    completed = 0
    progress_path = Path(args.results_dir) / "demo-journey-stateful-progress.json"
    progress_path.parent.mkdir(parents=True, exist_ok=True)

    def update_progress() -> None:
        progress_path.write_text(json.dumps({
            "completed": completed,
            "total": len(cases),
            "workers": workers,
            "updatedAt": dt.datetime.now(KST).isoformat(),
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    update_progress()
    def run_job(case: dict[str, Any]) -> dict[str, Any]:
        return execute_case(context(), case, not args.no_rerun)

    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="demo-stateful-qa") as executor:
        future_map = {
            executor.submit(run_job, case): (index, case["id"])
            for index, case in enumerate(cases, 1)
        }
        for future in as_completed(future_map):
            ordinal, case_id = future_map[future]
            ordered[ordinal] = future.result()
            completed += 1
            print(f"[{completed}/{len(cases)}] {case_id} -> {ordered[ordinal]['verdict']}", flush=True)
            update_progress()

    results = [ordered[index] for index in sorted(ordered)]
    summary = summarize(results, round((time.perf_counter() - started) * 1000))
    paths = write_outputs(results, summary, all_cases, args)
    print(f"Markdown: {paths[0]}")
    print(f"JSON: {paths[1]}")
    print(f"Web replay: {paths[2]}")
    if args.strict and any(row["verdict"] != "PASS" for row in results):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
