#!/usr/bin/env python3
"""Run the 100-chain Build Chat state-transition audit.

This runner deliberately mutates only dedicated QA users while preparing a
case.  Every chain snapshots and restores its quote draft.  Build Chat calls
must remain read-only; any unexpected draft mutation or cross-user signal is a
P0 and stops the run.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import os
import re
import statistics
import subprocess
import threading
import time
from collections import Counter, defaultdict
from concurrent.futures import CancelledError, ThreadPoolExecutor, as_completed
from copy import deepcopy
from dataclasses import dataclass
from itertools import count
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

from benchmark_build_chat_scenario_qa import (
    ApiClient,
    CATEGORIES,
    PROFILE,
    build_fact_failures,
    draft_fingerprint,
    has_next_action,
    outcome_failures,
    preview_builds,
    response_fingerprint,
    response_schema_valid,
    verified_virtual_draft,
    visible_language_failures,
)


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CASES = ROOT / "tools" / "build_chat_stateful_audit_cases.json"
KST = ZoneInfo("Asia/Seoul")
SINGLE_SLOT_CATEGORIES = {"CPU", "MOTHERBOARD", "GPU", "PSU", "CASE", "COOLER"}
P0_FAILURES = {"DRAFT_MUTATED", "CROSS_USER_DATA", "DRAFT_RESTORE_FAILED"}
HARD_INVARIANT_FAILURES = {
    "UNKNOWN_PART", "PART_NAME_MISMATCH", "PART_PRICE_MISMATCH", "BUILD_TOTAL_MISMATCH",
    "TOOL_FAIL_RECOMMENDED", "GRAPH_FAIL_RECOMMENDED", "PUBLIC_ACTIONS_EXPOSED",
    "SIMULATION_MUTATION", "BOARD_FOCUS_MUTATION", "SINGLE_SLOT_DUPLICATED",
}


class StatefulApiClient(ApiClient):
    """ApiClient with the current user-registration contract."""

    def _authenticate(self, email: str, password: str, name: str, provision: bool) -> str:
        try:
            return self._login(email, password)
        except RuntimeError:
            if not provision:
                raise
        suffix = abs(hash(email)) % 10_000_000
        phone = f"010-{suffix // 10_000:04d}-{suffix % 10_000:04d}"
        status, payload, _, _ = self.request("POST", "/api/users", {
            "email": email,
            "password": password,
            "name": name,
            "phoneNumber": phone,
            "postalCode": "06236",
            "addressLine1": "서울특별시 강남구 테헤란로 1",
            "addressLine2": f"QA {suffix}",
            "termsAccepted": True,
            "marketingAccepted": False,
        }, auth=False)
        if status >= 400 and status != 409:
            raise RuntimeError(f"QA user provisioning failed: status={status} payload={payload}")
        return self._login(email, password)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Stateful Build Chat audit")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--cases", default=str(DEFAULT_CASES))
    parser.add_argument("--output-dir", default=str(ROOT / "docs" / "reports"))
    parser.add_argument("--results-dir", default=str(ROOT / ".qa-results" / "stateful"))
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--case-id", nargs="+")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--strict", action="store_true")
    parser.add_argument("--no-rerun", action="store_true")
    parser.add_argument("--user-email", default=os.environ.get("STATEFUL_QA_USER_EMAIL", "stateful-qa@example.com"))
    parser.add_argument("--user-password", default=os.environ.get("STATEFUL_QA_USER_PASSWORD", "passw0rd!"))
    parser.add_argument("--report-suffix", default="")
    parser.add_argument("--merge-json", help="Merge selected reruns into an existing full result JSON")
    parser.add_argument("--duration-ms", type=int, help="Preserve the wall-clock duration of a prior full run")
    return parser.parse_args()


def load_cases(path: str | Path) -> list[dict[str, Any]]:
    rows = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(rows, list):
        raise RuntimeError("stateful corpus must be a JSON array")
    validate_cases(rows)
    return rows


def validate_cases(cases: list[dict[str, Any]]) -> None:
    if len(cases) != 100:
        raise RuntimeError(f"expected exactly 100 phase-1 cases, found {len(cases)}")
    expected_groups = {
        "COMPATIBILITY_BACKFILL": 14, "CURRENT_PART_QUANTITY": 12,
        "CLARIFICATION_CONTEXT": 12, "EXACT_ALIAS_AMBIGUITY": 10,
        "DIRECTION_IMPROVEMENT": 12, "BUDGET_HARD_COUNTER": 12,
        "SIMULATION_EXPLANATION": 10, "CACHE_CONTEXT_ISOLATION": 8,
        "EXHAUSTION_PARTIAL_SATURATION": 6, "ROBUSTNESS_AS_HANDOFF": 4,
    }
    counts = Counter(str(row.get("group")) for row in cases)
    if dict(counts) != expected_groups:
        raise RuntimeError(f"unexpected group distribution: {dict(counts)}")
    ids = [str(row.get("id") or "") for row in cases]
    if any(not value for value in ids) or len(ids) != len(set(ids)):
        raise RuntimeError("case ids must be non-empty and unique")
    if sum(bool(row.get("webReplay")) for row in cases) != 20:
        raise RuntimeError("phase-1 corpus must mark exactly 20 web replays")
    for row in cases:
        if row.get("profile") != PROFILE:
            raise RuntimeError(f"{row['id']}: profile must be {PROFILE}")
        if not isinstance(row.get("setupDraft"), dict) or not row["setupDraft"].get("template"):
            raise RuntimeError(f"{row['id']}: setupDraft.template is required")
        turns = row.get("turns")
        if not isinstance(turns, list) or not 3 <= len(turns) <= 5:
            raise RuntimeError(f"{row['id']}: turns must contain 3 to 5 entries")
        if not isinstance(row.get("expectedInvariants"), list) or not row["expectedInvariants"]:
            raise RuntimeError(f"{row['id']}: expectedInvariants are required")
        for turn_index, turn in enumerate(turns, 1):
            if not isinstance(turn, dict) or not isinstance(turn.get("expect"), dict):
                raise RuntimeError(f"{row['id']} turn {turn_index}: expect is required")
            if not turn.get("message") and "quickReplyIndex" not in turn:
                raise RuntimeError(f"{row['id']} turn {turn_index}: message or quickReplyIndex is required")


def worker_email(base: str, worker_index: int) -> str:
    local, separator, domain = base.partition("@")
    if not separator:
        raise ValueError("QA email must contain @")
    return f"{local}-worker-{worker_index}@{domain}"


def source_commit() -> str | None:
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def item_from_part(part: dict[str, Any], quantity: int = 1) -> dict[str, Any]:
    price = int(part.get("price") or part.get("currentPrice") or 0)
    return {
        "partId": str(part.get("id") or part.get("partId") or ""),
        "category": str(part.get("category") or ""),
        "name": str(part.get("name") or ""),
        "manufacturer": str(part.get("manufacturer") or ""),
        "quantity": quantity,
        "currentPrice": price,
        "price": price,
        "lineTotal": price * quantity,
        "attributes": part.get("attributes") or {},
    }


def draft_from_items(items: list[dict[str, Any]], name: str = "Stateful QA") -> dict[str, Any]:
    normalized = [deepcopy(item) for item in items]
    for item in normalized:
        price = int(item.get("currentPrice") or item.get("price") or 0)
        quantity = int(item.get("quantity") or 1)
        item["currentPrice"] = price
        item["price"] = price
        item["lineTotal"] = price * quantity
    return {
        "id": None, "status": "STATEFUL_QA", "name": name,
        "items": normalized,
        "itemCount": sum(int(item.get("quantity") or 1) for item in normalized),
        "totalPrice": sum(int(item.get("lineTotal") or 0) for item in normalized),
    }


def blocking_graph(graph: dict[str, Any] | None) -> bool:
    if not graph:
        return True
    return any(
        str(row.get("status") or "").upper() == "FAIL"
        and str(row.get("tool") or "").lower() in {"compatibility", "power", "size"}
        for row in graph.get("toolResults") or []
    )


def graph_status(graph: dict[str, Any] | None) -> str:
    if not graph:
        return "UNKNOWN"
    statuses = {str(row.get("status") or "").upper() for row in graph.get("toolResults") or []}
    if "FAIL" in statuses:
        return "FAIL"
    if "WARN" in statuses:
        return "WARN"
    return "PASS"


def attribute_number(part: dict[str, Any], keys: list[str]) -> float:
    attributes = part.get("attributes") or {}
    for key in keys:
        value = attributes.get(key)
        if isinstance(value, (int, float)):
            return float(value)
        if isinstance(value, str):
            match = re.search(r"\d+(?:\.\d+)?", value.replace(",", ""))
            if match:
                return float(match.group())
    return 0.0


class DraftManager:
    def __init__(self, client: ApiClient, active_parts: list[dict[str, Any]], base_draft: dict[str, Any]):
        self.client = client
        self.active_parts = active_parts
        self.base_draft = deepcopy(base_draft)
        self.by_category: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for part in active_parts:
            category = str(part.get("category") or "")
            if category in CATEGORIES and int(part.get("price") or 0) > 0:
                self.by_category[category].append(part)
        for rows in self.by_category.values():
            rows.sort(key=lambda row: (int(row.get("price") or 0), str(row.get("id") or "")))
        self._template_cache: dict[str, list[dict[str, Any]]] = {}

    def current_snapshot(self) -> dict[str, Any]:
        return deepcopy(self.client.current_draft())

    def replace_persisted(self, items: list[dict[str, Any]]) -> dict[str, Any]:
        current = self.client.current_draft()
        for item in current.get("items") or []:
            part_id = str(item.get("partId") or "")
            if part_id:
                status, payload, _, _ = self.client.request("DELETE", f"/api/quote-drafts/current/items/{part_id}")
                if status >= 400:
                    raise RuntimeError(f"draft clear failed for {part_id}: {status} {payload}")
        for item in items:
            part_id = str(item.get("partId") or "")
            quantity = int(item.get("quantity") or 1)
            status, payload, _, _ = self.client.request(
                "PUT", f"/api/quote-drafts/current/items/{part_id}", {"quantity": quantity}
            )
            if status >= 400:
                raise RuntimeError(f"draft setup failed for {part_id}: {status} {payload}")
        return self.client.current_draft()

    def restore(self, snapshot: dict[str, Any]) -> dict[str, Any]:
        restored = self.replace_persisted(snapshot.get("items") or [])
        if draft_fingerprint(restored) != draft_fingerprint(snapshot):
            raise RuntimeError("DRAFT_RESTORE_FAILED")
        return restored

    def resolve(self, template: str) -> tuple[list[dict[str, Any]], dict[str, Any]]:
        if template in self._template_cache:
            items = deepcopy(self._template_cache[template])
            return items, self._facts(items, template)
        base_items = deepcopy(self.base_draft.get("items") or [])
        if template == "EMPTY":
            items: list[dict[str, Any]] = []
        elif template == "PARTIAL_CPU_GPU":
            items = [row for row in base_items if row.get("category") in {"CPU", "GPU"}]
        elif template in {"COMPLETE_VERIFIED", "RAM_PRESENT", "STORAGE_PRESENT"}:
            items = base_items
        elif template in {"RAM_MULTI", "RAM_SATURATED"}:
            items = base_items
            ram = next((row for row in items if row.get("category") == "RAM"), None)
            if not ram:
                raise RuntimeError("no RAM in verified draft")
            ram["quantity"] = 4 if template == "RAM_SATURATED" else 2
        elif template == "STORAGE_MULTI":
            items = base_items
            current_ids = {str(row.get("partId")) for row in items}
            alternative = next((part for part in self.by_category["STORAGE"] if str(part.get("id")) not in current_ids), None)
            if not alternative:
                raise RuntimeError("no alternate STORAGE part")
            items.append(item_from_part(alternative))
        elif template in {"B860_COMPLETE", "AM5_COMPLETE"}:
            items = self._platform_variant(base_items, template)
        elif template == "HIGH_CPU_LOW_GPU":
            items = self._tier_variant(base_items, high_category="CPU", low_category="GPU")
        elif template == "LOW_CPU_HIGH_GPU":
            items = self._tier_variant(base_items, high_category="GPU", low_category="CPU")
        elif template == "TIGHT_PSU":
            items = self._single_category_variant(base_items, "PSU", ascending=True, require_non_blocking=True)
        elif template == "TIGHT_CASE":
            items = self._single_category_variant(base_items, "CASE", ascending=True, require_non_blocking=True)
        elif template == "FAIL_FORM_FACTOR":
            items = self._failure_variant(base_items)
        else:
            raise RuntimeError(f"unknown setup template: {template}")
        items = draft_from_items(items, template)["items"]
        self._template_cache[template] = deepcopy(items)
        return items, self._facts(items, template)

    def _replace_category(self, items: list[dict[str, Any]], category: str, part: dict[str, Any]) -> list[dict[str, Any]]:
        result = [row for row in items if row.get("category") != category]
        result.append(item_from_part(part, 1))
        return result

    def _platform_variant(self, base: list[dict[str, Any]], template: str) -> list[dict[str, Any]]:
        board_terms = ["B860"] if template == "B860_COMPLETE" else ["B850", "X870", "AM5"]
        cpu_terms = ["INTEL", "285", "265", "245"] if template == "B860_COMPLETE" else ["RYZEN", "라이젠", "9950", "9900", "9700"]
        boards = [row for row in self.by_category["MOTHERBOARD"] if any(term in str(row.get("name") or "").upper() for term in board_terms)]
        cpus = [row for row in self.by_category["CPU"] if any(term in str(row.get("name") or "").upper() for term in cpu_terms)]
        for board in boards[:20]:
            board_items = self._replace_category(base, "MOTHERBOARD", board)
            for cpu in cpus[:30]:
                candidate = self._replace_category(board_items, "CPU", cpu)
                graph = self.client.resolve_graph(draft_from_items(candidate))
                if not blocking_graph(graph):
                    return candidate
        raise RuntimeError(f"could not resolve {template} from active assets")

    def _tier_variant(self, base: list[dict[str, Any]], *, high_category: str, low_category: str) -> list[dict[str, Any]]:
        high_rows = sorted(self.by_category[high_category], key=lambda row: (attribute_number(row, ["benchmarkScore", "score", "capacityW"]), int(row.get("price") or 0)), reverse=True)
        low_rows = sorted(self.by_category[low_category], key=lambda row: (attribute_number(row, ["benchmarkScore", "score", "capacityW"]), int(row.get("price") or 0)))
        for high in high_rows[:20]:
            high_items = self._replace_category(base, high_category, high)
            for low in low_rows[:20]:
                candidate = self._replace_category(high_items, low_category, low)
                if not blocking_graph(self.client.resolve_graph(draft_from_items(candidate))):
                    return candidate
        return base

    def _single_category_variant(self, base: list[dict[str, Any]], category: str, *, ascending: bool, require_non_blocking: bool) -> list[dict[str, Any]]:
        keys = {
            "PSU": ["capacityW", "wattage"],
            "CASE": ["maxGpuLengthMm", "maxCpuCoolerHeightMm"],
        }.get(category, ["benchmarkScore"])
        rows = sorted(self.by_category[category], key=lambda row: (attribute_number(row, keys), int(row.get("price") or 0)), reverse=not ascending)
        for part in rows:
            candidate = self._replace_category(base, category, part)
            graph = self.client.resolve_graph(draft_from_items(candidate))
            if not require_non_blocking or not blocking_graph(graph):
                return candidate
        return base

    def _failure_variant(self, base: list[dict[str, Any]]) -> list[dict[str, Any]]:
        boards = sorted(self.by_category["MOTHERBOARD"], key=lambda row: attribute_number(row, ["formFactorRank", "widthMm", "heightMm"]), reverse=True)
        cases = sorted(self.by_category["CASE"], key=lambda row: attribute_number(row, ["maxMotherboardRank", "maxGpuLengthMm"]))
        for board in boards[:20]:
            board_items = self._replace_category(base, "MOTHERBOARD", board)
            for case in cases[:20]:
                candidate = self._replace_category(board_items, "CASE", case)
                graph = self.client.resolve_graph(draft_from_items(candidate))
                if graph_status(graph) == "FAIL":
                    return candidate
        raise RuntimeError("could not construct a form-factor failure fixture")

    def _facts(self, items: list[dict[str, Any]], template: str) -> dict[str, Any]:
        draft = draft_from_items(items, template)
        graph = self.client.resolve_graph(draft) if items else None
        return {
            "template": template,
            "itemCount": len(items),
            "draftFingerprint": draft_fingerprint(draft),
            "graphStatus": graph_status(graph),
            "toolResults": (graph or {}).get("toolResults") or [],
            "compositeScore": (graph or {}).get("compositeScore"),
        }

    def variables(self, items: list[dict[str, Any]]) -> dict[str, str]:
        result: dict[str, str] = {}
        current = {str(row.get("category")): row for row in items}
        current_ids = {str(row.get("partId")) for row in items}
        for category in CATEGORIES:
            current_row = current.get(category)
            if current_row:
                result[f"current.{category}.name"] = str(current_row.get("name") or "")
            alternatives = [row for row in self.by_category[category] if str(row.get("id")) not in current_ids]
            if alternatives:
                result[f"alternative.{category}.name"] = str(alternatives[0].get("name") or "")
            exact = next((row for row in alternatives if category == "CASE" and "FRAME" in str(row.get("name") or "").upper()), None)
            exact = exact or (alternatives[len(alternatives) // 2] if alternatives else current_row)
            if exact:
                result[f"exact.{category}.name"] = str(exact.get("name") or "")
        return result

    def compatible_pool(self, draft: dict[str, Any], category: str) -> list[dict[str, Any]]:
        current_ids = {str(row.get("partId")) for row in draft.get("items") or []}
        result = []
        for part in self.by_category[category][:60]:
            if str(part.get("id")) in current_ids:
                continue
            items = [deepcopy(row) for row in draft.get("items") or []]
            if category in SINGLE_SLOT_CATEGORIES:
                items = [row for row in items if row.get("category") != category]
            items.append(item_from_part(part))
            graph = self.client.resolve_graph(draft_from_items(items)) if items else None
            status = graph_status(graph)
            if status != "FAIL":
                result.append({"partId": str(part.get("id")), "name": part.get("name"), "status": status})
        return result


def render_message(template: str, variables: dict[str, str]) -> str:
    def replace(match: re.Match[str]) -> str:
        key = match.group(1)
        return variables.get(key, match.group(0))
    return re.sub(r"\{\{([^{}]+)}}", replace, template)


def response_candidates(response: dict[str, Any], active_parts: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_id = {str(row.get("id")): row for row in active_parts}
    found: dict[str, dict[str, Any]] = {}
    for build in response.get("builds") or []:
        for item in build.get("items") or []:
            part_id = str(item.get("partId") or "")
            if part_id and part_id in by_id:
                found[part_id] = by_id[part_id]
    texts = [str(value) for value in response.get("quickReplies") or []]
    texts.append(str(response.get("message") or ""))
    candidates = sorted(active_parts, key=lambda row: len(str(row.get("name") or "")), reverse=True)
    for text in texts:
        normalized = text.lower().replace(" ", "")
        for part in candidates:
            name = str(part.get("name") or "")
            if name and name.lower().replace(" ", "") in normalized:
                found[str(part.get("id"))] = part
    return list(found.values())


def custom_failures(
    case: dict[str, Any], turn_spec: dict[str, Any], response: dict[str, Any],
    draft: dict[str, Any], manager: DraftManager, candidate_pool_cache: dict[str, list[dict[str, Any]]],
) -> tuple[list[str], dict[str, Any]]:
    failures: list[str] = []
    invariants = set(case.get("expectedInvariants") or [])
    candidates = response_candidates(response, manager.active_parts)
    candidate_ids = {str(row.get("id")) for row in candidates}
    current_items = draft.get("items") or []
    current_ids = {str(row.get("partId")) for row in current_items}
    category = turn_spec.get("expect", {}).get("expectedCategory")
    candidate_facts: dict[str, Any] = {"responseCandidateIds": sorted(candidate_ids), "responseCandidateCount": len(candidates)}

    for build in preview_builds(response):
        counts = Counter(str(item.get("category")) for item in build.get("items") or [])
        if any(counts[category_name] > 1 for category_name in SINGLE_SLOT_CATEGORIES):
            failures.append("SINGLE_SLOT_DUPLICATED")

    expected = turn_spec.get("expect", {})
    audited_candidates = [row for row in candidates if not category or row.get("category") == category]
    audited_candidate_ids = {str(row.get("id")) for row in audited_candidates}
    current_category_ids = {
        str(row.get("partId")) for row in current_items if not category or row.get("category") == category
    }

    if expected.get("candidateAudit") and "CURRENT_PART_EXCLUDED" in invariants and audited_candidate_ids & current_category_ids:
        failures.append("CURRENT_PART_RECOMMENDED")

    needs_pool = bool(
        expected.get("candidateAudit") and category
        and invariants & {"COMPATIBLE_TOP3", "NO_BLOCKING_CANDIDATE", "PASS_BEFORE_WARN"}
    )
    if needs_pool:
        cache_key = f"{category}:{json.dumps(draft_fingerprint(draft), sort_keys=True)}"
        pool = candidate_pool_cache.get(cache_key)
        if pool is None:
            pool = manager.compatible_pool(draft, str(category))
            candidate_pool_cache[cache_key] = pool
        candidate_facts["compatiblePoolCount"] = len(pool)
        candidate_facts["compatiblePoolSample"] = pool[:10]
        response_in_category = [row for row in candidates if row.get("category") == category]
        if "COMPATIBLE_TOP3" in invariants and len(pool) >= 3 and len(response_in_category) < 3:
            failures.append("TOP3_NOT_BACKFILLED")
        if "NO_BLOCKING_CANDIDATE" in invariants:
            allowed = {str(row["partId"]) for row in pool}
            if any(str(row.get("id")) not in allowed for row in response_in_category):
                failures.append("BLOCKING_CANDIDATE_RECOMMENDED")
        if "PASS_BEFORE_WARN" in invariants and sum(row["status"] == "PASS" for row in pool) >= 3:
            status_by_id = {str(row["partId"]): row["status"] for row in pool}
            if any(status_by_id.get(str(row.get("id"))) == "WARN" for row in response_in_category[:3]):
                failures.append("WARN_SELECTED_BEFORE_PASS")

    direction = next((value.removeprefix("DIRECTION_") for value in invariants if value.startswith("DIRECTION_")), None)
    if direction and category:
        current = next((row for row in current_items if row.get("category") == category), None)
        category_candidates = [row for row in candidates if row.get("category") == category and str(row.get("id")) not in current_ids]
        if current and category_candidates and direction in {"CHEAPER", "MORE_EXPENSIVE"}:
            current_price = int(current.get("currentPrice") or current.get("price") or 0)
            prices = [int(row.get("price") or 0) for row in category_candidates]
            if direction == "CHEAPER" and any(price >= current_price for price in prices):
                failures.append("DIRECTION_PRICE_REVERSED")
            if direction == "MORE_EXPENSIVE" and any(price <= current_price for price in prices):
                failures.append("DIRECTION_PRICE_REVERSED")

    if "READ_ONLY" in invariants and preview_builds(response):
        failures.append("READ_ONLY_PREVIEW_CREATED")
    if "MUTATION_PREVIEW" in invariants and not preview_builds(response) and not response.get("clarification"):
        failures.append("EXPECTED_PREVIEW_MISSING")
    if "AMBIGUOUS_NOT_DETAIL" in invariants:
        text = json.dumps(response, ensure_ascii=False)
        if re.search(r'"route"\s*:\s*"/parts/', text):
            failures.append("AMBIGUOUS_DETAIL_ROUTE")
    if "AS_GUIDANCE" in invariants:
        text = (
            str(response.get("message") or "") + " "
            + " ".join(response.get("quickReplies") or []) + " "
            + json.dumps(response.get("supportGuidance") or {}, ensure_ascii=False)
        ).lower()
        if not any(term in text for term in ["가능", "예상", "원인", "진단", "agent", "에이전트", "as"]):
            failures.append("AS_GUIDANCE_MISSING")
    if not has_next_action(response) and not response.get("supportGuidance"):
        failures.append("DEAD_END")
    return sorted(set(failures)), candidate_facts


@dataclass
class WorkerContext:
    index: int
    client: ApiClient
    manager: DraftManager
    initial_snapshot: dict[str, Any]


def run_attempt(context: WorkerContext, case: dict[str, Any], attempt: int, stop_event: threading.Event) -> dict[str, Any]:
    manager = context.manager
    original = manager.current_snapshot()
    result: dict[str, Any] = {
        "caseId": case["id"], "group": case["group"], "workerIndex": context.index,
        "attempt": attempt, "risk": case.get("risk"), "webReplay": bool(case.get("webReplay")),
        "setupTemplate": case["setupDraft"]["template"], "turns": [], "failures": [],
        "harnessErrors": [], "infraErrors": [], "p0": False,
    }
    candidate_pool_cache: dict[str, list[dict[str, Any]]] = {}
    try:
        items, setup_facts = manager.resolve(case["setupDraft"]["template"])
        persisted = manager.replace_persisted(items)
        result["resolvedSetupItems"] = persisted.get("items") or []
        result["setupFacts"] = setup_facts
        result["setupDraftFingerprint"] = draft_fingerprint(persisted)
        variables = manager.variables(persisted.get("items") or [])
        previous_response: dict[str, Any] | None = None
        recent_builds: list[dict[str, Any]] = []
        for turn_index, turn_spec in enumerate(case["turns"], 1):
            if stop_event.is_set():
                result["infraErrors"].append("RUN_STOPPED_AFTER_P0")
                break
            message = None
            if "quickReplyIndex" in turn_spec:
                replies = (previous_response or {}).get("quickReplies") or []
                reply_index = int(turn_spec.get("quickReplyIndex") or 0)
                if reply_index < len(replies):
                    message = str(replies[reply_index])
                else:
                    result["failures"].append("QUICK_REPLY_MISSING")
                    break
            else:
                message = render_message(str(turn_spec.get("message") or ""), variables)
                if "{{" in message:
                    result["harnessErrors"].append(f"UNRESOLVED_TEMPLATE:{message}")
                    break
            draft_before = manager.current_snapshot()
            request_body: dict[str, Any] = {"message": message, "currentQuoteDraft": draft_before}
            ui_context = turn_spec.get("uiContext") or case.get("uiContext")
            if ui_context:
                request_body["uiContext"] = ui_context
            if recent_builds:
                request_body["currentBuilds"] = recent_builds[:3]
            clarification = (previous_response or {}).get("clarification") or {}
            if isinstance(clarification, dict) and clarification.get("originalMessage"):
                request_body["clarificationContext"] = {"originalMessage": clarification["originalMessage"]}
            status, response, latency_ms, transport = context.client.request(
                "POST", "/api/ai/build-chat", request_body,
                headers={"X-BuildGraph-AI-Profile": PROFILE},
            )
            failures: list[str] = []
            metrics: dict[str, Any] = {}
            candidate_facts: dict[str, Any] = {}
            if status != 200:
                if status == 0 or status == 429 or status >= 500:
                    result["infraErrors"].append(f"HTTP_{status or 'TRANSPORT'}")
                else:
                    failures.append(f"HTTP_{status}")
            if not response_schema_valid(response):
                failures.append("SCHEMA_INVALID")
            else:
                outcome_errors, metrics = outcome_failures(response, turn_spec.get("expect") or {})
                failures.extend(outcome_errors)
                failures.extend(build_fact_failures(context.client, response, turn_spec.get("expect") or {}, True))
                failures.extend(visible_language_failures(response))
                custom, candidate_facts = custom_failures(case, turn_spec, response, draft_before, manager, candidate_pool_cache)
                failures.extend(custom)
                if response.get("supportGuidance"):
                    failures = [failure for failure in failures if failure != "DEAD_END"]
            draft_after = manager.current_snapshot()
            if draft_fingerprint(draft_before) != draft_fingerprint(draft_after):
                failures.append("DRAFT_MUTATED")
                stop_event.set()
            row = {
                "turn": turn_index, "message": message, "requestBody": request_body,
                "status": status, "latencyMs": latency_ms, "transport": transport,
                "response": response, "responseFingerprint": response_fingerprint(response) if response_schema_valid(response) else None,
                "expected": turn_spec.get("expect") or {}, "metrics": metrics,
                "candidateFacts": candidate_facts,
                "draftBefore": draft_fingerprint(draft_before), "draftAfter": draft_fingerprint(draft_after),
                "failures": sorted(set(failures)),
            }
            result["turns"].append(row)
            result["failures"].extend(row["failures"])
            previous_response = response if isinstance(response, dict) else None
            if previous_response and previous_response.get("builds"):
                recent_builds = previous_response["builds"][:3]
    except Exception as error:  # keep the remaining corpus running unless it is a P0
        result["harnessErrors"].append(f"{type(error).__name__}: {error}")
    finally:
        try:
            manager.restore(original)
            result["restored"] = True
        except Exception as error:
            result["restored"] = False
            result["failures"].append("DRAFT_RESTORE_FAILED")
            result["harnessErrors"].append(f"restore: {type(error).__name__}: {error}")
            stop_event.set()
    result["failures"] = sorted(set(result["failures"]))
    result["p0"] = bool(set(result["failures"]) & P0_FAILURES)
    result["success"] = not result["failures"] and not result["harnessErrors"] and not result["infraErrors"]
    return result


def classify(first: dict[str, Any], second: dict[str, Any] | None) -> tuple[str, list[str]]:
    if first.get("p0"):
        return "CONFIRMED_BUG", first.get("failures") or []
    if first.get("harnessErrors"):
        return "HARNESS_GAP", first.get("harnessErrors") or []
    if first.get("infraErrors") and not first.get("failures"):
        return "INFRA", first.get("infraErrors") or []
    failures = set(first.get("failures") or [])
    if not failures:
        return "PASS", []
    if failures & HARD_INVARIANT_FAILURES:
        return "CONFIRMED_BUG", sorted(failures)
    if second is None:
        return "SUSPECTED", sorted(failures)
    repeated = failures & set(second.get("failures") or [])
    if repeated:
        return "CONFIRMED_BUG", sorted(repeated)
    return "SUSPECTED", sorted(failures)


def execute_case(context: WorkerContext, case: dict[str, Any], stop_event: threading.Event, rerun: bool) -> dict[str, Any]:
    first = run_attempt(context, case, 1, stop_event)
    second = None
    if rerun and first.get("failures") and not first.get("p0") and not first.get("harnessErrors") and not stop_event.is_set():
        second = run_attempt(context, case, 2, stop_event)
    verdict, reasons = classify(first, second)
    return {
        "caseId": case["id"], "group": case["group"], "risk": case.get("risk"),
        "webReplay": bool(case.get("webReplay")), "expectedInvariants": case.get("expectedInvariants"),
        "verdict": verdict, "confirmedReasons": reasons, "attempts": [row for row in [first, second] if row],
    }


def apply_comparison_oracles(results: list[dict[str, Any]], cases_by_id: dict[str, dict[str, Any]]) -> None:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for result in results:
        case = cases_by_id[result["caseId"]]
        if case.get("comparisonGroup"):
            grouped[str(case["comparisonGroup"])].append(result)
    for pair_name, rows in grouped.items():
        if len(rows) != 2:
            continue
        expectation = cases_by_id[rows[0]["caseId"]].get("comparisonExpectation")
        fingerprints = []
        for row in rows:
            attempts = row.get("attempts") or []
            turns = attempts[0].get("turns") if attempts else []
            fingerprints.append(turns[0].get("responseFingerprint") if turns else None)
        if expectation == "DIFFERENT_REQUIRED" and fingerprints[0] and fingerprints[0] == fingerprints[1]:
            for row in rows:
                row["verdict"] = "CONFIRMED_BUG"
                row["confirmedReasons"] = sorted(set((row.get("confirmedReasons") or []) + ["CACHE_INTENT_COLLISION"]))
        elif expectation == "SAME_ALLOWED" and fingerprints[0] and fingerprints[0] != fingerprints[1]:
            for row in rows:
                row.setdefault("diagnostics", []).append(f"{pair_name}: semantic equivalent responses differed")


def percentile(values: list[int], pct: int) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * pct / 100) - 1)
    return float(ordered[index])


def summarize(results: list[dict[str, Any]], duration_ms: int) -> dict[str, Any]:
    verdicts = Counter(row["verdict"] for row in results)
    groups: dict[str, Counter[str]] = defaultdict(Counter)
    latencies: list[int] = []
    total_turns = 0
    restored = 0
    for row in results:
        groups[row["group"]][row["verdict"]] += 1
        first = (row.get("attempts") or [{}])[0]
        if first.get("restored"):
            restored += 1
        for turn in first.get("turns") or []:
            total_turns += 1
            latencies.append(int(turn.get("latencyMs") or 0))
    return {
        "caseCount": len(results), "turnCount": total_turns,
        "verdicts": dict(verdicts), "groupVerdicts": {key: dict(value) for key, value in groups.items()},
        "draftRestored": restored, "webReplayExpected": sum(row.get("webReplay", False) for row in results),
        "durationMs": duration_ms,
        "latencyDiagnostic": {
            "averageMs": round(statistics.mean(latencies), 1) if latencies else 0,
            "p95Ms": percentile(latencies, 95), "maxMs": max(latencies, default=0),
        },
    }


def root_cause_ids(row: dict[str, Any]) -> list[str]:
    """Collapse repeated natural-language variants into independent defect roots."""
    case_id = str(row.get("caseId") or "")
    attempts = row.get("attempts") or []
    turns = attempts[0].get("turns") if attempts else []
    causes: set[str] = set()
    if case_id in {
        "state-compat-01-b860-cpu-top3",
        "state-compat-02-am5-cpu-top3",
        "state-compat-09-ram-platform",
        "state-compat-11-mb-size-fit",
    }:
        causes.add("BG-STATE-01")
    if any(
        int(turn.get("turn") or 0) > 1
        and set(turn.get("failures") or []) & {"CATEGORY_MISMATCH", "TOP3_NOT_BACKFILLED", "AS_GUIDANCE_MISSING"}
        for turn in turns
    ):
        causes.add("BG-STATE-02")
    if case_id == "state-alias-09-storage-m2-alias":
        causes.add("BG-STATE-03")
    if case_id in {"state-compat-05-gpu-backfill", "state-direction-12-score-chip-improves"}:
        causes.add("BG-STATE-04")
    if case_id in {
        "state-budget-01-target-200",
        "state-budget-10-numeric-comma",
        "state-budget-11-korean-number",
    }:
        causes.add("BG-STATE-05")
    return sorted(causes)


def write_outputs(results: list[dict[str, Any]], summary: dict[str, Any], args: argparse.Namespace) -> tuple[Path, Path, Path]:
    now = dt.datetime.now(KST)
    date = now.strftime("%Y%m%d")
    suffix = f"-{args.report_suffix.strip('-')}" if args.report_suffix else ""
    output_dir = Path(args.output_dir)
    results_dir = Path(args.results_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    results_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / f"build-chat-stateful-audit-{date}{suffix}.json"
    md_path = output_dir / f"build-chat-stateful-audit-{date}{suffix}.md"
    replay_path = results_dir / "build-chat-stateful-web-replay.json"
    payload = {
        "generatedAt": now.isoformat(), "sourceCommit": source_commit(), "profile": PROFILE,
        "summary": summary, "results": results,
    }
    for row in results:
        row["rootCauseIds"] = root_cause_ids(row) if row.get("verdict") == "CONFIRMED_BUG" else []
    root_counts = Counter(
        root_id
        for row in results
        for root_id in row.get("rootCauseIds") or []
    )
    summary["independentRootCauseCount"] = len(root_counts)
    summary["rootCauseCases"] = dict(root_counts)
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    replay_rows = []
    for row in results:
        if not row.get("webReplay") or not row.get("attempts"):
            continue
        first = row["attempts"][0]
        replay_rows.append({
            "caseId": row["caseId"], "group": row["group"],
            "setupItems": first.get("resolvedSetupItems") or [],
            "turns": [
                {
                    "message": turn.get("message"), "expected": turn.get("expected") or {},
                    "uiContext": (turn.get("requestBody") or {}).get("uiContext"),
                }
                for turn in first.get("turns") or []
            ],
        })
    replay_path.write_text(json.dumps(replay_rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# Build Chat 상세 상태 전이 1차 감사", "",
        f"- 생성 시각: `{now.isoformat()}`", f"- 기준 commit: `{payload['sourceCommit'] or 'unknown'}`",
        f"- 모델 profile: `{PROFILE}`", "- 지연 시간은 진단 자료이며 timeout/5xx 외에는 기능 실패로 판정하지 않았다.", "",
        "## 요약", "",
        f"- 실행 case: **{summary['caseCount']}/100**", f"- 실행 turn: **{summary['turnCount']}**",
        f"- PASS: **{summary['verdicts'].get('PASS', 0)}**",
        f"- 확정 버그: **{summary['verdicts'].get('CONFIRMED_BUG', 0)}**",
        f"- 독립 원인: **{summary['independentRootCauseCount']}**",
        f"- 의심 사례: **{summary['verdicts'].get('SUSPECTED', 0)}**",
        f"- harness gap: **{summary['verdicts'].get('HARNESS_GAP', 0)}**",
        f"- 인프라 징후: **{summary['verdicts'].get('INFRA', 0)}**",
        f"- draft 원복 확인: **{summary['draftRestored']}/{summary['caseCount']}**", "",
        "## 그룹별 결과", "", "| 그룹 | PASS | 확정 버그 | 의심 | harness gap | 인프라 |", "|---|---:|---:|---:|---:|---:|",
    ]
    for group, counts in summary["groupVerdicts"].items():
        lines.append(
            f"| {group} | {counts.get('PASS', 0)} | {counts.get('CONFIRMED_BUG', 0)} | "
            f"{counts.get('SUSPECTED', 0)} | {counts.get('HARNESS_GAP', 0)} | {counts.get('INFRA', 0)} |"
        )
    if root_counts:
        lines.extend([
            "", "## 독립 원인 트리아지", "",
            "| ID | 독립 원인 | 연결 사례 수 | 판단 근거 |", "|---|---|---:|---|",
            f"| BG-STATE-01 | 관계 문장에서 기준 부품을 추천 대상으로 뒤집음 | {root_counts.get('BG-STATE-01', 0)} | `현재 보드에 맞는 CPU`, `현재 케이스에 들어가는 메인보드`가 기준 부품 추천으로 역전됨 |",
            f"| BG-STATE-02 | 직전 후보 카테고리와 AS 증상 문맥이 후속 턴에서 소실됨 | {root_counts.get('BG-STATE-02', 0)} | `다시 선택지`, `다른 후보`, `원인과 다음 행동`이 직전 턴의 대상을 이어받지 못함 |",
            f"| BG-STATE-03 | `M.2` 저장장치 alias가 부품 추천 의도로 연결되지 않음 | {root_counts.get('BG-STATE-03', 0)} | `M.2 슬롯에 넣을 2테라`가 저장장치 후보 대신 전체 견적 되묻기로 전환됨 |",
            f"| BG-STATE-04 | 현재 견적 평가에서 개선 후보 요청으로 넘어가는 의도 전이가 막힘 | {root_counts.get('BG-STATE-04', 0)} | 호환 GPU·점수 개선 부품 요청이 평가 설명 또는 저정보 되묻기로 종료됨 |",
            f"| BG-STATE-05 | deterministic 예산 snapshot의 Tool FAIL 카드가 최종 서빙 게이트를 통과함 | {root_counts.get('BG-STATE-05', 0)} | 같은 200만원 QHD 의도의 숫자 표현 3종에서 동일 FAIL 조합이 노출됨 |",
        ])
    lines.extend(["", "## 확정 버그", ""])
    confirmed = [row for row in results if row["verdict"] == "CONFIRMED_BUG"]
    if not confirmed:
        lines.append("확정 버그가 발견되지 않았다.")
    else:
        lines.extend(["| case | 그룹 | 재현된 위반 | 시작 상태 |", "|---|---|---|---|"])
        for row in confirmed:
            first = row["attempts"][0]
            lines.append(
                f"| {row['caseId']} | {row['group']} | {', '.join(row['confirmedReasons'])} | "
                f"{first.get('setupTemplate', '-')} |"
            )
    lines.extend(["", "## 의심·환경 사례", "", "| case | 판정 | 사유 |", "|---|---|---|"])
    for row in results:
        if row["verdict"] in {"SUSPECTED", "HARNESS_GAP", "INFRA"}:
            first = row["attempts"][0] if row.get("attempts") else {}
            reasons = row.get("confirmedReasons") or first.get("failures") or first.get("harnessErrors") or first.get("infraErrors") or []
            lines.append(f"| {row['caseId']} | {row['verdict']} | {', '.join(reasons)} |")
    lines.extend([
        "", "## 지연 진단", "",
        f"- 평균: **{summary['latencyDiagnostic']['averageMs'] / 1000:.3f}초**",
        f"- p95: **{summary['latencyDiagnostic']['p95Ms'] / 1000:.3f}초**",
        f"- 최대: **{summary['latencyDiagnostic']['maxMs'] / 1000:.3f}초**", "",
        "## 원본 증거", "",
        f"전체 request/response, draft 전후 fingerprint, Tool 결과, 2회 재현 기록은 `{json_path.name}`에 있다.",
        f"웹 재현 20개 입력은 `{replay_path.relative_to(ROOT).as_posix()}`에 생성했다.",
    ])
    md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return md_path, json_path, replay_path


def main() -> int:
    args = parse_args()
    all_cases = load_cases(args.cases)
    cases = list(all_cases)
    if args.case_id:
        selected_ids = set(args.case_id)
        cases = [row for row in cases if row["id"] in selected_ids]
    if args.limit is not None:
        cases = cases[:max(0, args.limit)]
    if args.validate_only:
        print(f"Validated 100 stateful Build Chat cases; selected={len(cases)}; webReplay={sum(bool(row.get('webReplay')) for row in cases)}")
        return 0
    if not cases:
        raise RuntimeError("no cases selected")

    bootstrap = StatefulApiClient(args.base_url, worker_email(args.user_email, 0), args.user_password, name="Stateful QA Bootstrap", provision=True)
    active_parts = bootstrap.active_parts()
    base_draft = verified_virtual_draft(bootstrap, active_parts)
    workers = max(1, int(args.workers))
    stop_event = threading.Event()
    local = threading.local()
    worker_counter = count(1)
    worker_lock = threading.Lock()
    worker_contexts: list[WorkerContext] = []

    def context() -> WorkerContext:
        if not hasattr(local, "context"):
            with worker_lock:
                index = next(worker_counter)
            client = StatefulApiClient(
                args.base_url, worker_email(args.user_email, index), args.user_password,
                name=f"Stateful QA Worker {index}", provision=True,
            )
            client.part_cache.update({str(row["id"]): row for row in active_parts if row.get("id")})
            manager = DraftManager(client, active_parts, base_draft)
            worker_context = WorkerContext(index, client, manager, manager.current_snapshot())
            local.context = worker_context
            with worker_lock:
                worker_contexts.append(worker_context)
        return local.context

    results_dir = Path(args.results_dir)
    results_dir.mkdir(parents=True, exist_ok=True)
    progress_path = results_dir / "build-chat-stateful-progress.json"
    started = time.perf_counter()
    ordered: dict[int, dict[str, Any]] = {}
    completed = 0

    def update_progress() -> None:
        progress_path.write_text(json.dumps({
            "completed": completed, "total": len(cases), "workers": workers,
            "stoppedAfterP0": stop_event.is_set(), "updatedAt": dt.datetime.now(KST).isoformat(),
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    update_progress()
    def run_job(case: dict[str, Any]) -> dict[str, Any]:
        return execute_case(context(), case, stop_event, not args.no_rerun)

    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="stateful-qa") as executor:
        future_map = {
            executor.submit(run_job, case): (ordinal, case["id"])
            for ordinal, case in enumerate(cases, 1)
        }
        for future in as_completed(future_map):
            ordinal, case_id = future_map[future]
            try:
                ordered[ordinal] = future.result()
            except CancelledError:
                continue
            completed += 1
            print(f"[{completed}/{len(cases)}] {case_id} -> {ordered[ordinal]['verdict']}", flush=True)
            update_progress()
            if stop_event.is_set():
                for pending in future_map:
                    pending.cancel()

    results = [ordered[index] for index in sorted(ordered)]
    if args.merge_json:
        previous = json.loads(Path(args.merge_json).read_text(encoding="utf-8"))
        merged = {str(row["caseId"]): row for row in previous.get("results") or []}
        merged.update({str(row["caseId"]): row for row in results})
        results = [merged[row["id"]] for row in all_cases if row["id"] in merged]
    apply_comparison_oracles(results, {row["id"]: row for row in all_cases})
    duration_ms = args.duration_ms if args.duration_ms is not None else round((time.perf_counter() - started) * 1000)
    summary = summarize(results, duration_ms)
    md_path, json_path, replay_path = write_outputs(results, summary, args)
    print(f"Markdown: {md_path}")
    print(f"JSON: {json_path}")
    print(f"Web replay: {replay_path}")
    if args.strict and (summary["verdicts"].get("CONFIRMED_BUG", 0) or summary["verdicts"].get("HARNESS_GAP", 0) or stop_event.is_set()):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
