from __future__ import annotations

import argparse
import calendar
import gzip
import hashlib
import json
import math
import mimetypes
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
import webbrowser
from dataclasses import dataclass, replace as dataclass_replace
from datetime import datetime, timedelta, timezone
from io import TextIOWrapper
from pathlib import Path
from typing import Any, Callable, Sequence
from urllib.parse import quote, urljoin

from demo_sensor_data import DEMO_SENSOR_SAMPLES
from diagnosis_request_agent import (
    AgentDiagnosisWebSocketClient,
    BackgroundViewerController,
    DiagnosisRequest,
    DiagnosisSession,
    DiagnosisSessionReplacement,
    DiagnosisSessionStore,
    DiagnosisRequestProcessor,
    STANDALONE,
    ViewerRequestSignal,
    WEB_REQUEST,
)
from diagnosis_as_request import (
    DiagnosisAsRequest,
    DiagnosisAsRequestClient,
    DiagnosisAsResponse,
    build_diagnosis_as_request,
)
from diagnosis_orchestrator import (
    FINAL_SESSION_STATES,
    GRAPHICS_DIAGNOSIS_TASK_DEFINITIONS,
    GRAPHICS_DIAGNOSIS_TASK_LABELS,
    GRAPHICS_DIAGNOSIS_TASK_WEIGHTS,
    DiagnosisLogStore,
    DiagnosisOrchestrator,
    DiagnosisRunSnapshot,
    DiagnosisSettings,
    DiagnosisTask,
    TaskOutcome,
    diagnosis_component_state,
    diagnosis_current_task_label,
)
from diagnosis_result import (
    DiagnosisResult,
    DiagnosisResultStore,
    DiagnosisRuleEngine,
    actual_device_problem_evidence,
    can_offer_as,
    compact_result_evidence,
    format_diagnosis_result_detail,
    matching_display_driver_evidence,
)
from initial_metrics import (
    ABNORMAL,
    AVAILABLE,
    DEFAULT_METRIC_POLICY,
    ERROR,
    FAILED,
    MODERATE,
    NORMAL,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
    UNSUPPORTED,
    WARNING,
    DemoSensorProvider as InitialDemoSensorProvider,
    HardwareSensorProvider,
    InitialMetricsCoordinator,
    MetricReading,
    MetricsNormalizer,
    MetricsSnapshot,
    MetricsStore,
)
from pc_agent_ui_rendering import (
    AnimationCallbackController,
    DeferredFluidWaveCache,
    FLUID_WAVE_FRAME_COUNT,
    FLUID_WAVE_SIZE,
    FluidWaveDisplayState,
    RetainedAssetCache,
    SPINNER_FRAME_COUNT,
    WindowVisibilityState,
    fluid_wave_amplitude,
    home_hardware_icon_cache_key,
    render_finding_icon,
    render_fluid_wave_frame,
    render_home_hardware_icon as render_pillow_home_hardware_icon,
    render_number_badge,
    render_progress_ring as render_pillow_progress_ring,
    render_result_icon as render_pillow_result_icon,
    render_rounded_surface,
    render_status_dot,
    render_status_icon as render_pillow_status_icon,
    render_step_connector,
    render_step_node,
    render_summary_icon,
)
from pc_agent_demo_scenarios import (
    DEMO_DATA_MODE,
    GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID,
    GRAPHICS_CODE43_REMOTE_SUPPORT_SYMPTOM,
    demo_scenario_id,
)
from windows_graphics_diagnostics import (
    Code43RemoteSupportDemoGraphicsProvider,
    NO_RESULTS as WINDOWS_NO_RESULTS,
    OK as WINDOWS_QUERY_OK,
    PowerShellJsonRunner,
    WindowsGraphicsDiagnosticsProvider,
    WindowsGraphicsDiagnosticsSnapshot,
)

try:
    import tkinter as tk
    from tkinter import font as tkfont
    from tkinter import ttk
except Exception:  # pragma: no cover - optional in minimal packaged runtimes
    tk = None
    tkfont = None
    ttk = None

try:
    import psutil
except Exception:  # pragma: no cover - optional for prototype environments
    psutil = None

try:
    import pystray
except Exception:  # pragma: no cover - optional outside packaged Windows agent
    pystray = None

try:
    from PIL import Image, ImageDraw, ImageTk
except Exception:  # pragma: no cover - Pillow is required by the packaged Windows agent
    Image = None
    ImageDraw = None
    ImageTk = None

KST = timezone(timedelta(hours=9))
DEFAULT_CONFIG_PATH = Path("agent-config.json")
DEFAULT_LOG_DIR = Path("out/logs")
DEFAULT_LOG_FILE = "agent-metrics.jsonl"
ACTIVATION_CONFIG_FILE = "pcagent-activation.json"
LEGACY_ACTIVATION_CONFIG_FILES = ("buildgraph-agent-activation.json",)
DEFAULT_RANGE_MINUTES = 30
DEFAULT_SCHEMA_VERSION = 1
REMOTE_SYMPTOM_TYPES = {
    "REMOTE_AGENT",
    "REMOTE_DRIVER_OS",
    "REMOTE_APP_LAUNCHER",
    "REMOTE_STORAGE_MEMORY",
    "REMOTE_STARTUP_SERVICE",
    "REMOTE_LOCAL_NETWORK",
}
VISIT_SYMPTOM_TYPES = {
    "VISIT_BOOT_REMOTE_BLOCKED",
    "VISIT_DISK_FAILURE",
    "VISIT_WHEA_BSOD",
    "VISIT_POWER_SHUTDOWN",
    "VISIT_FAN_THERMAL",
}
REGISTER_PATH = "/api/agent/devices/register"
CONSENT_PATH = "/api/agent/consents"
LOG_UPLOAD_PATH = "/api/agent/log-uploads"
AS_DRAFT_PATH = "/api/agent/as-drafts"
AS_RAG_PREVIEW_PATH = "/api/agent/log-uploads/as-rag-preview"
DIAGNOSIS_CHAT_PATH = "/api/agent/diagnosis-chat"
UPDATE_MANIFEST_PATH = "/downloads/pc-agent/latest.json"
REGISTERED_STATUS = "REGISTERED"
UNREGISTERED_STATUS = "UNREGISTERED"
DISPLAY_APP_NAME = "PCAgent"
WINDOW_TITLE = "PC Agent"
DATA_APP_NAME = "BuildGraphAgent"
LEGACY_DISPLAY_APP_NAMES = ("BuildGraphAgent", "PC Agent", "BuildGraph PC Agent")
DOWNLOAD_FILE_PREFIX = DISPLAY_APP_NAME
LEGACY_DOWNLOAD_FILE_PREFIXES = ("BuildGraphAgent",)
APP_NAME = DISPLAY_APP_NAME
PC_AGENT_URL_PROTOCOL = "buildgraph-pc-agent"
APP_ASSET_DIR = "assets"
AGENT_ICON_PNG = "specup-agent.png"
AGENT_ICON_ICO = "specup-agent.ico"
SCREEN_LOGO_PNG = "pc-agent-logo.png"
SCREEN_LOGO_DISPLAY_SIZE = (46, 46)
SCREEN_LOGO_POSITION = (76, 86)
BACKGROUND_INSTANCE_MUTEX_NAME = r"Local\SpecUpPcAgentBackground"
VIEWER_INSTANCE_MUTEX_NAME = r"Local\SpecUpPcAgentViewer"
DEFAULT_AGENT_VERSION = "0.1.19"
DEFAULT_POLICY_VERSION = "policy-v1"


STATUS_HOME_SIGNAL_LIMIT = 3
LOG_TABLE_LIMIT = 500
EVENT_PANEL_SIGNAL_LIMIT = 3
STATUS_LOG_SUMMARY_LIMIT = 6
DIAGNOSIS_HISTORY_FILE = "diagnosis-history.jsonl"
DIAGNOSIS_HISTORY_LIMIT = 100
DIAGNOSIS_CHAT_HISTORY_FILE = "diagnosis-chat-history.jsonl"
DIAGNOSIS_CHAT_HISTORY_LIMIT = 100
UPDATE_DIR_NAME = "updates"
UPDATE_APPLY_SCRIPT_FILE = "apply-pcagent-update.cmd"
UPDATE_PENDING_FILE = "pending-update.json"
HOME_MEMORY_WARNING_THRESHOLD = 85.0
PC_AGENT_USAGE_WARNING_THRESHOLD = DEFAULT_METRIC_POLICY.usage_warning
PC_AGENT_USAGE_DANGER_THRESHOLD = DEFAULT_METRIC_POLICY.usage_abnormal
PC_AGENT_CPU_TEMP_WARNING_THRESHOLD = DEFAULT_METRIC_POLICY.cpu_temperature_warning
PC_AGENT_CPU_TEMP_DANGER_THRESHOLD = DEFAULT_METRIC_POLICY.cpu_temperature_abnormal
PC_AGENT_GPU_TEMP_WARNING_THRESHOLD = DEFAULT_METRIC_POLICY.gpu_temperature_warning
PC_AGENT_GPU_TEMP_DANGER_THRESHOLD = DEFAULT_METRIC_POLICY.gpu_temperature_abnormal
DIAGNOSIS_DETAIL_EVENT_LIMIT = 5
DIAGNOSIS_DETAIL_WARNING_THRESHOLD = HOME_MEMORY_WARNING_THRESHOLD
DIAGNOSIS_DETAIL_DANGER_THRESHOLD = 95.0
HOME_USAGE_LOOKBACK_MINUTES = 5
UI_FONT_CANDIDATES = ("Pretendard", "Noto Sans KR", "Segoe UI Variable", "Segoe UI", "Malgun Gothic")
FONT_BODY_PX = 14
FONT_SECONDARY_PX = 12
FONT_BUTTON_PX = 14
FONT_SECTION_TITLE_PX = 16
FONT_PAGE_TITLE_PX = 20
FONT_LOG_PX = 12
CARD_ICON_FILES = {
    "pc": Path("assets/icons/pc-security.png"),
    "server": Path("assets/icons/server-cloud.png"),
    "upload": Path("assets/icons/upload-cloud.png"),
    "startup": Path("assets/icons/startup-windows.png"),
    "version": Path("assets/icons/version-info.png"),
}
PC_AGENT_UI_FLOW = (
    "SYMPTOM_CONFIRM",
    "DIAGNOSING",
    "DIAGNOSIS_RESULT",
)
PC_AGENT_DIAGNOSIS_STEPS = ("증상 확인", "하드웨어 진단", "결과 및 조치")
PC_AGENT_WINDOW_WIDTH = 1000
PC_AGENT_WINDOW_HEIGHT = 740
PC_AGENT_REMOVED_HEADER_HEIGHT = 56
STANDALONE_INITIAL_DESCRIPTION = (
    "CPU, GPU, 메모리, 저장장치의 현재 상태를 자동으로 확인하고 있습니다.\n"
    "준비가 끝나면 아래 버튼으로 정밀 진단을 시작할 수 있습니다."
)
METRIC_WAVE_FRAME_MS = 33
METRIC_WAVE_MAX_AMPLITUDE = 22.0
UI_PAGE_MARGIN = 70
UI_CARD_GAP = 12
UI_CARD_RADIUS = 12
UI_CARD_BORDER_WIDTH = 1
UI_CARD_PADDING = 18
UI_BUTTON_HEIGHT = 54


def usage_wave_target_amplitude(value: float | None, maximum: float = METRIC_WAVE_MAX_AMPLITUDE) -> float:
    return fluid_wave_amplitude(value, maximum)


def smooth_wave_amplitude(current: float, target: float, factor: float = 0.12) -> float:
    bounded_factor = max(0.0, min(1.0, factor))
    return current + (target - current) * bounded_factor


def metric_wave_coordinates(
    x: int,
    baseline_y: int,
    width: int,
    amplitude: float,
    phase: float,
    point_count: int = 30,
) -> tuple[float, ...]:
    bounded_points = max(2, point_count)
    coordinates: list[float] = []
    for index in range(bounded_points):
        ratio = index / (bounded_points - 1)
        point_x = x + width * ratio
        point_y = baseline_y - math.sin(phase + ratio * math.tau * 1.5) * amplitude
        coordinates.extend((point_x, point_y))
    return tuple(coordinates)


@dataclass(frozen=True)
class SymptomDisplayState:
    title: str
    description: str
    helper: str


def initial_status_summary(metrics: MetricsSnapshot | None) -> str:
    if not isinstance(metrics, MetricsSnapshot) or not metrics.diagnosis_id or not metrics.readings:
        return STANDALONE_INITIAL_DESCRIPTION
    if not metrics.initial_complete:
        return "CPU, GPU, 메모리, 저장장치 상태를 확인하고 있습니다."

    abnormal_statuses = {WARNING, ABNORMAL}
    gpu_usage = metrics.latest("gpu", "usage")
    gpu_temperature = metrics.latest("gpu", "temperature")
    if (
        gpu_usage is not None
        and gpu_temperature is not None
        and gpu_usage.status in abnormal_statuses
        and gpu_temperature.status in abnormal_statuses
    ):
        return "GPU 부하와 온도가 높아 냉각 상태 확인이 필요합니다."

    ram_usage = metrics.latest("ram", "usage")
    if ram_usage is not None and ram_usage.status in abnormal_statuses:
        return "메모리 사용량이 높아 메모리 부족 가능성을 확인해야 합니다."

    unavailable_components = {
        component
        for component in ("cpu", "gpu", "ram", "disk")
        if (
            (reading := metrics.latest(component, "usage", "activity")) is not None
            and reading.availability in {UNSUPPORTED, PERMISSION_REQUIRED, FAILED}
        )
    }
    if len(unavailable_components) >= 2:
        return "일부 센서를 사용할 수 없어 초기 상태 확인이 제한됩니다."
    return "초기 측정에서는 뚜렷한 이상이 확인되지 않았습니다. 정밀 진단을 진행해 주세요."


def symptom_display_state(
    source: str,
    symptom: str,
    metrics: MetricsSnapshot | None,
) -> SymptomDisplayState:
    if source == WEB_REQUEST and symptom.strip():
        return SymptomDisplayState(
            "전달받은 증상",
            symptom,
            "웹 상담 정보를 바탕으로 점검 범위를 설정했습니다.",
        )
    helper = (
        "현재 하드웨어 상태를 수집하기 전입니다."
        if not isinstance(metrics, MetricsSnapshot) or not metrics.diagnosis_id
        else "실제 센서 측정값을 바탕으로 작성한 초기 관찰입니다."
    )
    return SymptomDisplayState("초기 상태 요약", initial_status_summary(metrics), helper)


def next_pc_agent_ui_state(current: str) -> str:
    try:
        index = PC_AGENT_UI_FLOW.index(current)
    except ValueError:
        return PC_AGENT_UI_FLOW[0]
    return PC_AGENT_UI_FLOW[min(index + 1, len(PC_AGENT_UI_FLOW) - 1)]


def diagnosis_session_ui_state(
    session: DiagnosisSession | None,
    metrics: MetricsSnapshot | None,
    diagnosis: DiagnosisRunSnapshot | None = None,
    result: DiagnosisResult | None = None,
    diagnosis_started: bool = False,
    result_requested: bool = False,
) -> str:
    result_available = diagnosis_result_available(session, diagnosis, result)
    if result_requested and result_available:
        return "DIAGNOSIS_RESULT"
    if diagnosis_started or (
        isinstance(session, DiagnosisSession)
        and session.agent_state in {"RUNNING", "COMPLETED", "FAILED"}
    ):
        return "DIAGNOSING"
    return "SYMPTOM_CONFIRM"


def should_auto_start_initial_metrics(
    session: DiagnosisSession | None,
    already_requested: bool,
) -> bool:
    return not already_requested and not isinstance(session, DiagnosisSession)


DIAGNOSIS_WARNING_EVIDENCE_STATUSES = {
    WARNING,
    ABNORMAL,
    "DEGRADED",
    "DEVICE_REPORTED_PROBLEM",
    "DRIVER_LOAD_FAILED",
    "DRIVER_BLOCKED",
    "SIGNATURE_PROBLEM",
}


def diagnosis_component_presentation(
    snapshot: DiagnosisRunSnapshot,
    component: str,
) -> tuple[str, str]:
    tasks = snapshot.component_tasks(component)
    if not tasks and component in {"cpu", "ram", "disk"}:
        system_task = snapshot.task("current_system_status")
        if system_task is None or system_task.status == "PENDING":
            return "대기", "neutral"
        if system_task.status == "RUNNING":
            return "초기 상태 확인", "running"
        if system_task.status in {"FAILED", "TIMED_OUT"}:
            return "확인 실패", "error"
        if system_task.status in {"UNSUPPORTED", "CANCELLED"}:
            return "미확인", "neutral"
        return "초기 상태 확인", "neutral"
    if not tasks or all(task.status == "PENDING" for task in tasks):
        return "대기", "neutral"
    statuses = {task.status for task in tasks}
    if "RUNNING" in statuses:
        return "검사 중", "running"
    if statuses & {"FAILED", "TIMED_OUT"}:
        return "오류", "error"
    if statuses and statuses <= {"UNSUPPORTED", "CANCELLED"}:
        return "건너뜀", "neutral"
    evidence_statuses = {
        str(item.get("status") or "").upper()
        for task in tasks
        for item in task.evidence
        if isinstance(item, dict)
    }
    has_evidence = any(
        isinstance(item, dict) and bool(item)
        for task in tasks
        for item in task.evidence
    )
    if evidence_statuses & DIAGNOSIS_WARNING_EVIDENCE_STATUSES:
        return "주의", "warning"
    if statuses and statuses <= {"COMPLETED", "UNSUPPORTED", "CANCELLED"}:
        return ("정상", "success") if has_evidence else ("근거 없음", "neutral")
    return "대기", "neutral"


def diagnosis_task_presentation(status: str) -> tuple[str, str]:
    return {
        "PENDING": ("대기", "neutral"),
        "RUNNING": ("진행 중", "running"),
        "COMPLETED": ("정상 완료", "success"),
        "UNSUPPORTED": ("건너뜀", "neutral"),
        "FAILED": ("오류", "error"),
        "TIMED_OUT": ("시간 초과", "error"),
        "CANCELLED": ("취소", "neutral"),
    }.get(status, (status, "neutral"))


def diagnosis_event_presentation(event_type: str) -> tuple[str, str]:
    return {
        "DIAGNOSIS_STARTED": ("시작", "running"),
        "TASK_STARTED": ("진행 중", "running"),
        "TASK_COMPLETED": ("정상 완료", "success"),
        "TASK_UNSUPPORTED": ("건너뜀", "neutral"),
        "TASK_FAILED": ("오류", "error"),
        "TASK_TIMED_OUT": ("시간 초과", "error"),
        "PROGRESS_UPDATED": ("진행", "running"),
        "DIAGNOSIS_EVALUATION_STARTED": ("분석 중", "running"),
        "DIAGNOSIS_COMPLETED": ("진단 완료", "success"),
        "DIAGNOSIS_FAILED": ("오류", "error"),
        "DIAGNOSIS_CANCELLED": ("취소", "neutral"),
    }.get(event_type, ("정보", "neutral"))


ACTIVE_VIEWER_AGENT_STATES = {"REQUEST_RECEIVED", "RUNNING"}
ACTIVE_VIEWER_DIAGNOSIS_STATES = {"COLLECTING", "DIAGNOSING", "EVALUATING"}
TERMINAL_VIEWER_AGENT_STATES = {"COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"}
TERMINAL_VIEWER_DIAGNOSIS_STATES = {
    "COMPLETED",
    "PARTIALLY_COMPLETED",
    "FAILED",
    "CANCELLED",
    "TIMED_OUT",
}


def active_viewer_session(
    session: DiagnosisSession | None,
    diagnosis: DiagnosisRunSnapshot | None,
) -> DiagnosisSession | None:
    if not isinstance(session, DiagnosisSession):
        return None
    diagnosis_matches = (
        isinstance(diagnosis, DiagnosisRunSnapshot)
        and diagnosis.diagnosis_id == session.request.diagnosis_id
    )
    if session.agent_state in TERMINAL_VIEWER_AGENT_STATES or (
        diagnosis_matches and diagnosis.state in TERMINAL_VIEWER_DIAGNOSIS_STATES
    ):
        return None
    if session.agent_state in ACTIVE_VIEWER_AGENT_STATES or (
        diagnosis_matches and diagnosis.state in ACTIVE_VIEWER_DIAGNOSIS_STATES
    ):
        return session
    return None


def move_terminal_session_to_idle(
    diagnosis_store: DiagnosisSessionStore,
    diagnosis: DiagnosisRunSnapshot | None,
) -> bool:
    session = diagnosis_store.session
    if not isinstance(session, DiagnosisSession):
        return False
    diagnosis_matches = (
        isinstance(diagnosis, DiagnosisRunSnapshot)
        and diagnosis.diagnosis_id == session.request.diagnosis_id
    )
    terminal = session.agent_state in TERMINAL_VIEWER_AGENT_STATES or (
        diagnosis_matches and diagnosis.state in TERMINAL_VIEWER_DIAGNOSIS_STATES
    )
    if not terminal:
        return False
    diagnosis_store.update_state("IDLE")
    return True


def reset_diagnosis_session_state(
    diagnosis_store: DiagnosisSessionStore,
    metrics_store: MetricsStore,
    diagnosis_log_store: DiagnosisLogStore,
    diagnosis_result_store: DiagnosisResultStore,
) -> None:
    diagnosis_store.clear_current()
    metrics_store.clear()
    diagnosis_log_store.replace(DiagnosisRunSnapshot(), reset=True)
    diagnosis_result_store.clear()


def diagnosis_result_available(
    session: DiagnosisSession | None,
    diagnosis: DiagnosisRunSnapshot | None,
    result: DiagnosisResult | None,
) -> bool:
    return (
        isinstance(session, DiagnosisSession)
        and isinstance(diagnosis, DiagnosisRunSnapshot)
        and diagnosis.diagnosis_id == session.request.diagnosis_id
        and diagnosis.transition_allowed
        and diagnosis.state in {"COMPLETED", "PARTIALLY_COMPLETED"}
        and isinstance(result, DiagnosisResult)
        and result.diagnosis_id == session.request.diagnosis_id
    )


def start_diagnosis_once(
    session: DiagnosisSession | None,
    diagnosis_store: DiagnosisSessionStore,
    metrics_store: MetricsStore,
    diagnosis_orchestrator: DiagnosisOrchestrator,
    diagnosis_result_store: DiagnosisResultStore | None = None,
) -> DiagnosisSession | None:
    current_session = diagnosis_store.session
    if (
        not isinstance(session, DiagnosisSession)
        or not isinstance(current_session, DiagnosisSession)
        or current_session.request.diagnosis_id != session.request.diagnosis_id
        or current_session.agent_state != "REQUEST_RECEIVED"
    ):
        return None
    metrics = metrics_store.snapshot
    if metrics.diagnosis_id != current_session.request.diagnosis_id or not metrics.initial_complete:
        return None
    if isinstance(diagnosis_result_store, DiagnosisResultStore):
        diagnosis_result_store.clear()
    diagnosis_orchestrator.prepare(
        current_session.request.diagnosis_id,
        current_session.request.mode,
        current_session.request.requested_checks,
        reset=True,
    )
    diagnosis_store.update_state("RUNNING")
    if not diagnosis_orchestrator.start(
        current_session.request.diagnosis_id,
        current_session.request.mode,
        current_session.request.requested_checks,
    ):
        diagnosis_store.update_state("REQUEST_RECEIVED")
        return None
    return diagnosis_store.session


def start_initial_metrics_session(
    session: DiagnosisSession | None,
    mode: str,
    device_id: str | None,
    diagnosis_store: DiagnosisSessionStore,
    metrics_store: MetricsStore,
    diagnosis_result_store: DiagnosisResultStore,
    diagnosis_orchestrator: DiagnosisOrchestrator,
    initial_metrics_coordinator: InitialMetricsCoordinator,
    now: Callable[[], datetime] | None = None,
) -> DiagnosisSession | None:
    active_session = session
    if not isinstance(active_session, DiagnosisSession):
        normalized_mode = mode.upper()
        if normalized_mode not in {"LIVE", "DEMO"}:
            return None
        requested_at = (now or (lambda: datetime.now(timezone.utc)))()
        active_session = DiagnosisSession(DiagnosisRequest(
            diagnosis_id=f"standalone-{uuid.uuid4()}",
            device_id=device_id or "standalone",
            symptom="",
            requested_checks=("cpu", "gpu", "memory", "disk", "cooling"),
            requested_at=requested_at.isoformat(),
            expires_at=(requested_at + timedelta(hours=1)).isoformat(),
            mode=normalized_mode,
            source=STANDALONE,
        ))
        diagnosis_store.accept(active_session)
    current_session = diagnosis_store.session
    if (
        not isinstance(current_session, DiagnosisSession)
        or current_session.request.diagnosis_id != active_session.request.diagnosis_id
        or current_session.agent_state != "REQUEST_RECEIVED"
    ):
        return None
    existing_metrics = metrics_store.snapshot
    if (
        existing_metrics.diagnosis_id == current_session.request.diagnosis_id
        and existing_metrics.initial_complete
    ):
        return current_session
    diagnosis_result_store.clear()
    diagnosis_orchestrator.prepare(
        current_session.request.diagnosis_id,
        current_session.request.mode,
        current_session.request.requested_checks,
    )
    started = initial_metrics_coordinator.start(
        current_session.request.diagnosis_id,
        current_session.request.mode,
    )
    return current_session if started else None


SUPPORTED_GRAPHICS_SYMPTOM_FRAGMENTS = (
    "검은 화면",
    "화면이 꺼졌다가 복구",
    "화면이 나오지 않",
    "그래픽 출력 중단",
    "화면 복구",
    "화면이 잠깐 꺼졌다가 다시 켜",
)


def is_supported_graphics_symptom(symptom: str) -> bool:
    normalized = " ".join(symptom.strip().split())
    return bool(normalized) and any(fragment in normalized for fragment in SUPPORTED_GRAPHICS_SYMPTOM_FRAGMENTS)


def diagnosis_demo_scenario_id(session: DiagnosisSession | None) -> str | None:
    if not isinstance(session, DiagnosisSession):
        return None
    return demo_scenario_id(session.request.mode, session.request.symptom)


def collect_session_windows_graphics_snapshot(
    session: DiagnosisSession | None,
    live_provider: Callable[[], WindowsGraphicsDiagnosticsSnapshot],
    demo_provider: Callable[[], WindowsGraphicsDiagnosticsSnapshot],
) -> WindowsGraphicsDiagnosticsSnapshot:
    if diagnosis_demo_scenario_id(session) == GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID:
        return demo_provider()
    return live_provider()


def diagnosis_event_sync_detail(
    snapshot: DiagnosisRunSnapshot,
    event: Any,
    session: DiagnosisSession | None,
) -> dict[str, Any]:
    detail = event.to_dict()
    detail.update({
        "sessionState": snapshot.state,
        "progress": snapshot.progress,
        "mode": snapshot.mode,
        "dataMode": snapshot.mode,
    })
    scenario_id = diagnosis_demo_scenario_id(session)
    if scenario_id:
        detail["scenarioId"] = scenario_id
    return detail


def diagnosis_session_replacement_sync_detail(
    replacement: DiagnosisSessionReplacement,
    occurred_at: datetime | None = None,
) -> dict[str, Any]:
    timestamp = (occurred_at or datetime.now(timezone.utc)).astimezone(timezone.utc).isoformat()
    message = (
        "만료된 미시작 진단 요청을 정리했습니다."
        if replacement.reason == "REQUEST_EXPIRED"
        else "새 진단 요청이 접수되어 이전 미시작 요청을 취소했습니다."
    )
    return {
        "diagnosisId": replacement.session.request.diagnosis_id,
        "eventId": str(uuid.uuid4()),
        "taskId": "diagnosis-request-lifecycle",
        "eventType": "DIAGNOSIS_CANCELLED",
        "status": "CANCELLED",
        "sessionState": "CANCELLED",
        "progress": 0,
        "progressPercent": 0,
        "message": message,
        "occurredAt": timestamp,
        "metadata": {"reason": replacement.reason},
    }


def graphics_diagnosis_task_handlers(
    session_provider: Callable[[], DiagnosisSession | None],
    metrics_snapshot_provider: Callable[[], MetricsSnapshot],
    diagnosis_snapshot_provider: Callable[[], DiagnosisRunSnapshot],
    windows_snapshot_provider: Callable[[], WindowsGraphicsDiagnosticsSnapshot],
    observation_timeout_seconds: float = 8.0,
    monotonic: Callable[[], float] | None = None,
    sleeper: Callable[[float], None] | None = None,
) -> dict[str, Callable[[DiagnosisTask, MetricsSnapshot, tuple[DiagnosisTask, ...]], TaskOutcome]]:
    clock = monotonic or time.monotonic
    sleep = sleeper or time.sleep
    windows_cache: dict[str, Any] = {
        "key": None,
        "attempted": False,
        "snapshot": None,
        "error": None,
    }

    def current_run_key() -> tuple[str | None, int]:
        snapshot = diagnosis_snapshot_provider()
        return snapshot.diagnosis_id, snapshot.retry_count

    def windows_snapshot() -> WindowsGraphicsDiagnosticsSnapshot:
        key = current_run_key()
        if windows_cache["key"] != key:
            windows_cache.update({"key": key, "attempted": False, "snapshot": None, "error": None})
        if not windows_cache["attempted"]:
            windows_cache["attempted"] = True
            try:
                windows_cache["snapshot"] = windows_snapshot_provider()
            except Exception as exception:
                windows_cache["error"] = exception
        error = windows_cache["error"]
        if isinstance(error, Exception):
            raise error
        snapshot = windows_cache["snapshot"]
        if not isinstance(snapshot, WindowsGraphicsDiagnosticsSnapshot):
            raise RuntimeError("Windows graphics diagnostics returned no snapshot")
        return snapshot

    def query_outcome(
        query: Any,
        evidence: tuple[dict[str, Any], ...],
        no_results_completed: bool,
    ) -> TaskOutcome:
        if query.status == WINDOWS_QUERY_OK:
            return TaskOutcome("COMPLETED", evidence)
        if query.status == WINDOWS_NO_RESULTS:
            status = "COMPLETED" if no_results_completed else "UNSUPPORTED"
            return TaskOutcome(
                status,
                evidence,
                None if no_results_completed else "NO_RESULTS",
                None if no_results_completed else "조회된 Windows 장치 정보가 없습니다.",
            )
        if query.status == UNSUPPORTED:
            return TaskOutcome("UNSUPPORTED", evidence, "UNSUPPORTED", query.error or "지원되지 않는 Windows 조회입니다.")
        return TaskOutcome("FAILED", evidence, query.status, query.error or "Windows 진단 정보 조회에 실패했습니다.")

    def evidence_for(snapshot: WindowsGraphicsDiagnosticsSnapshot, *task_ids: str) -> tuple[dict[str, Any], ...]:
        allowed = set(task_ids)
        return tuple(item.to_dict() for item in snapshot.to_evidence() if item.task_id in allowed)

    def current_system_status(
        task: DiagnosisTask,
        metrics: MetricsSnapshot,
        tasks: tuple[DiagnosisTask, ...],
    ) -> TaskOutcome:
        run = diagnosis_snapshot_provider()
        started_at = _parse_iso_datetime(run.started_at)
        deadline = clock() + max(0.0, observation_timeout_seconds)
        current = metrics
        sample_timestamps: tuple[str, ...] = ()
        while True:
            current = metrics_snapshot_provider()
            timestamps = {
                reading.sampled_at
                for reading in current.readings
                if reading.component in {"cpu", "gpu", "ram", "disk"}
                and reading.source != "demo-scenario"
                and (
                    started_at is None
                    or (
                        (sampled_at := _parse_iso_datetime(reading.sampled_at)) is not None
                        and sampled_at > started_at
                    )
                )
            }
            sample_timestamps = tuple(sorted(timestamps))
            if len(sample_timestamps) >= 3 or clock() >= deadline:
                break
            sleep(min(0.05, max(0.0, deadline - clock())))

        selected_timestamps = set(sample_timestamps[:3])
        evidence: list[dict[str, Any]] = []
        metric_preferences = {
            "cpu": ("usage",),
            "gpu": ("usage",),
            "ram": ("usage",),
            "disk": ("activity", "usage"),
        }
        for sampled_at in sorted(selected_timestamps):
            for component, metric_types in metric_preferences.items():
                reading = next(
                    (
                        item
                        for metric_type in metric_types
                        for item in current.readings
                        if item.sampled_at == sampled_at
                        and item.component == component
                        and item.metric_type == metric_type
                        and item.availability == AVAILABLE
                    ),
                    None,
                )
                if reading is None:
                    continue
                payload = reading.to_dict()
                payload.update({
                    "category": "PERFORMANCE",
                    "code": f"{component.upper()}_{reading.metric_type.upper()}",
                    "occurredAt": reading.sampled_at,
                    "description": "MetricsStore actual sensor sample",
                })
                evidence.append(payload)

        observed_at = sample_timestamps[min(2, len(sample_timestamps) - 1)] if sample_timestamps else (run.started_at or "")
        evidence.append({
            "component": "system",
            "metricType": "observation_window",
            "value": {
                "sampleCount": min(3, len(sample_timestamps)),
                "sampleTimestamps": list(sample_timestamps[:3]),
                "timeoutSeconds": max(0.0, observation_timeout_seconds),
            },
            "unit": "count",
            "availability": AVAILABLE,
            "status": "OBSERVED" if len(sample_timestamps) >= 3 else "OBSERVATION_WINDOW_COMPLETED",
            "source": "MetricsStore",
            "sampledAt": observed_at,
            "category": "PERFORMANCE",
            "code": "ACTUAL_SAMPLE_TIMESTAMPS",
            "occurredAt": observed_at,
            "description": "Actual samples observed after diagnosis start",
        })
        return TaskOutcome("COMPLETED", tuple(evidence))

    def display_devices(
        task: DiagnosisTask,
        metrics: MetricsSnapshot,
        tasks: tuple[DiagnosisTask, ...],
    ) -> TaskOutcome:
        snapshot = windows_snapshot()
        return query_outcome(
            snapshot.device_query,
            evidence_for(snapshot, "windows_display_devices"),
            no_results_completed=False,
        )

    def display_drivers(
        task: DiagnosisTask,
        metrics: MetricsSnapshot,
        tasks: tuple[DiagnosisTask, ...],
    ) -> TaskOutcome:
        snapshot = windows_snapshot()
        return query_outcome(
            snapshot.driver_query,
            evidence_for(snapshot, "windows_display_drivers"),
            no_results_completed=False,
        )

    def graphics_events(
        task: DiagnosisTask,
        metrics: MetricsSnapshot,
        tasks: tuple[DiagnosisTask, ...],
    ) -> TaskOutcome:
        snapshot = windows_snapshot()
        evidence = evidence_for(snapshot, "windows_graphics_events", "windows_kernel_power_events")
        return query_outcome(snapshot.graphics_event_query, evidence, no_results_completed=True)

    def whea_events(
        task: DiagnosisTask,
        metrics: MetricsSnapshot,
        tasks: tuple[DiagnosisTask, ...],
    ) -> TaskOutcome:
        snapshot = windows_snapshot()
        return query_outcome(
            snapshot.whea_event_query,
            evidence_for(snapshot, "windows_whea_events"),
            no_results_completed=True,
        )

    def symptom_correlation(
        task: DiagnosisTask,
        metrics: MetricsSnapshot,
        tasks: tuple[DiagnosisTask, ...],
    ) -> TaskOutcome:
        session = session_provider()
        run = diagnosis_snapshot_provider()
        if not isinstance(session, DiagnosisSession) or session.request.diagnosis_id != run.diagnosis_id:
            return TaskOutcome("FAILED", error_code="SESSION_MISMATCH", failure_reason="현재 웹 진단 요청을 확인할 수 없습니다.")
        snapshot = windows_snapshot()
        scenario_symptom = (
            GRAPHICS_CODE43_REMOTE_SUPPORT_SYMPTOM
            if snapshot.data_mode == DEMO_DATA_MODE
            and snapshot.scenario_id == GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID
            else None
        )
        supported = is_supported_graphics_symptom(scenario_symptom or session.request.symptom)
        occurred_at = session.request.requested_at
        value = {
            "symptom": session.request.symptom,
            "requestedAt": session.request.requested_at,
            "supported": supported,
            "deviceInstanceIds": [device.instance_id for device in snapshot.devices],
            "graphicsEventOccurredAt": [event.occurred_at for event in snapshot.graphics_events],
            "components": sorted({event.component for event in snapshot.graphics_events} | ({"gpu"} if snapshot.devices else set())),
        }
        if scenario_symptom:
            value.update({
                "scenarioId": snapshot.scenario_id,
                "scenarioSymptom": scenario_symptom,
            })
        evidence = ({
            "component": "system",
            "metricType": "symptom_correlation",
            "value": value,
            "unit": "",
            "availability": AVAILABLE if supported else UNSUPPORTED,
            "status": "MATCHED" if supported else "UNSUPPORTED_SYMPTOM",
            "source": "DiagnosisSession",
            "sampledAt": occurred_at,
            "category": "SYSTEM",
            "code": "SUPPORTED_GRAPHICS_SYMPTOM" if supported else "UNSUPPORTED_GRAPHICS_SYMPTOM",
            "occurredAt": occurred_at,
            "description": "Web symptom and Windows graphics evidence context",
        },)
        return TaskOutcome("COMPLETED" if supported else "UNSUPPORTED", evidence)

    def evidence_finalize(
        task: DiagnosisTask,
        metrics: MetricsSnapshot,
        tasks: tuple[DiagnosisTask, ...],
    ) -> TaskOutcome:
        run = diagnosis_snapshot_provider()
        summary = [
            {"taskId": item.task_id, "status": item.status, "evidenceCount": len(item.evidence)}
            for item in tasks
            if item.task_id not in {"evidence_finalize", "final_classification"}
        ]
        evidence = ({
            "component": "system",
            "metricType": "evidence_summary",
            "value": summary,
            "unit": "count",
            "availability": AVAILABLE,
            "status": "COMPLETED",
            "source": "DiagnosisOrchestrator",
            "sampledAt": run.started_at or "",
            "category": "SYSTEM",
            "code": "EVIDENCE_SUMMARY",
            "occurredAt": run.started_at or "",
            "description": "Terminal task evidence summary",
        },)
        return TaskOutcome("COMPLETED", evidence)

    def final_classification(
        task: DiagnosisTask,
        metrics: MetricsSnapshot,
        tasks: tuple[DiagnosisTask, ...],
    ) -> TaskOutcome:
        run = diagnosis_snapshot_provider()
        symptom_matched = any(
            item.get("metricType") == "symptom_correlation"
            and item.get("status") == "MATCHED"
            for diagnosis_task in tasks
            for item in diagnosis_task.evidence
        )
        problem_devices = [
            item
            for diagnosis_task in tasks
            for item in diagnosis_task.evidence
            if item.get("metricType") == "display_device_status"
            and isinstance(item.get("code"), int)
            and item.get("code") != 0
            and isinstance(item.get("value"), dict)
            and item["value"].get("deviceName")
            and item["value"].get("instanceId")
            and item["value"].get("problemCode") == item.get("code")
            and item["value"].get("problemCodeQueryStatus") == WINDOWS_QUERY_OK
        ]
        diagnosis_type = (
            "DEVICE_DRIVER_CONFIGURATION_ISSUE"
            if symptom_matched and problem_devices
            else "INSUFFICIENT_EVIDENCE"
        )
        value: dict[str, Any] = {
            "diagnosisType": diagnosis_type,
            "symptomMatched": symptom_matched,
            "problemDeviceCount": len(problem_devices),
        }
        if problem_devices:
            value["device"] = problem_devices[0]["value"]
        evidence = ({
            "component": "system",
            "metricType": "diagnosis_type",
            "value": value,
            "unit": "",
            "availability": AVAILABLE,
            "status": "CLASSIFIED" if diagnosis_type == "DEVICE_DRIVER_CONFIGURATION_ISSUE" else "INSUFFICIENT_EVIDENCE",
            "source": "DiagnosisOrchestrator",
            "sampledAt": run.started_at or "",
            "category": "SYSTEM",
            "code": diagnosis_type,
            "occurredAt": run.started_at or "",
            "description": "Evidence-backed graphics diagnosis classification",
        },)
        return TaskOutcome("COMPLETED", evidence)

    return {
        "current_system_status": current_system_status,
        "windows_display_devices": display_devices,
        "windows_display_drivers": display_drivers,
        "windows_graphics_events": graphics_events,
        "windows_whea_events": whea_events,
        "symptom_correlation": symptom_correlation,
        "evidence_finalize": evidence_finalize,
        "final_classification": final_classification,
    }


def _parse_iso_datetime(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def resolve_ui_font_family(root: Any | None = None) -> str:
    if tkfont is None:
        return UI_FONT_CANDIDATES[-1]
    try:
        installed = {str(name).casefold(): str(name) for name in tkfont.families(root)}
    except Exception:
        return UI_FONT_CANDIDATES[-1]
    for candidate in UI_FONT_CANDIDATES:
        found = installed.get(candidate.casefold())
        if found:
            return found
    return UI_FONT_CANDIDATES[-1]


def tk_ui_font(family: str, size_px: int, weight: str = "regular", underline: bool = False) -> tuple[Any, ...]:
    styles: list[str] = []
    if weight in {"semibold", "bold"}:
        styles.append("bold")
    if underline:
        styles.append("underline")
    return (family, -size_px, *styles)


def fit_measured_text(
    value: str,
    max_width: int,
    max_lines: int,
    measure: Callable[[str], int],
) -> str:
    """Wrap text to a fixed number of measured lines and add an ellipsis when needed."""

    normalized = " ".join(str(value).split())
    if not normalized or max_width <= 0 or max_lines <= 0:
        return ""
    lines: list[str] = []
    remaining = normalized
    while remaining and len(lines) < max_lines:
        if measure(remaining) <= max_width:
            lines.append(remaining)
            remaining = ""
            break
        last_fit = 0
        word_break = 0
        for index in range(1, len(remaining) + 1):
            candidate = remaining[:index].rstrip()
            if measure(candidate) <= max_width:
                last_fit = index
                if index == len(remaining) or remaining[index:index + 1].isspace():
                    word_break = index
            else:
                break
        best_break = max(1, word_break or last_fit)
        lines.append(remaining[:best_break].rstrip())
        remaining = remaining[best_break:].lstrip()
    if remaining and lines:
        ellipsis = "…"
        last = lines[-1]
        while last and measure(f"{last}{ellipsis}") > max_width:
            last = last[:-1].rstrip()
        lines[-1] = f"{last}{ellipsis}" if last else ellipsis
    return "\n".join(lines)


def result_action_columns(
    action_count: int,
    left: int = 145,
    right: int = 855,
) -> tuple[tuple[int, int], ...]:
    """Return evenly divided action columns within the result card."""

    count = max(1, min(3, int(action_count)))
    width = (right - left) / count
    return tuple(
        (round(left + index * width), round(left + (index + 1) * width))
        for index in range(count)
    )


def result_action_vertical_layout(
    rendered_text: str,
    line_height: int,
    container_top: int = 568,
    container_height: int = 40,
) -> tuple[int, int]:
    """Center one- and two-line action text and its number badge on one axis."""

    line_count = max(1, min(2, len(str(rendered_text).splitlines())))
    text_height = max(1, int(line_height)) * line_count
    text_top = round(container_top + (container_height - text_height) / 2)
    badge_center = round(text_top + text_height / 2)
    return text_top, badge_center


EVENT_PANEL_SIGNAL_CODES = {
    "REMOTE_DRIVER_OS",
    "REMOTE_APP_LAUNCHER",
    "REMOTE_LOCAL_NETWORK",
    "VISIT_DISK_FAILURE",
    "VISIT_WHEA_BSOD",
    "VISIT_POWER_SHUTDOWN",
    "VISIT_FAN_THERMAL",
}
EVENT_PANEL_SUMMARIES = {
    "REMOTE_DRIVER_OS": "드라이버 또는 OS 관련 오류 신호가 감지되었습니다.",
    "REMOTE_APP_LAUNCHER": "앱 또는 런처 실행 오류가 반복된 기록이 있습니다.",
    "REMOTE_LOCAL_NETWORK": "로컬 네트워크 연결 문제 신호가 감지되었습니다.",
    "VISIT_DISK_FAILURE": "디스크 상태 점검이 필요한 신호가 감지되었습니다.",
    "VISIT_WHEA_BSOD": "블루스크린 또는 하드웨어 오류 로그가 반복 감지되었습니다.",
    "VISIT_POWER_SHUTDOWN": "예상치 못한 전원 종료 기록이 감지되었습니다.",
    "VISIT_FAN_THERMAL": "과열 또는 팬 상태 점검이 필요한 신호가 감지되었습니다.",
}
FINAL_SIGNAL_RULES: tuple[dict[str, Any], ...] = (
    {
        "code": "REMOTE_AGENT",
        "title": "Agent 등록/업로드 오류",
        "level": "주의",
        "keywords": (
            "remote_agent",
            "agent_health",
            "register failed",
            "registration failed",
            "upload failed",
            "auth 401",
            "auth 409",
            "token error",
            "config parse",
            "acl",
            "permission",
            "heartbeat missing",
        ),
    },
    {
        "code": "REMOTE_DRIVER_OS",
        "title": "드라이버/OS 오류",
        "level": "주의",
        "keywords": (
            "remote_driver_os",
            "display_driver_warning",
            "display driver",
            "nvlddmkm",
            "driver reset",
            "windows update",
            "pnp",
            "device manager",
        ),
    },
    {
        "code": "REMOTE_APP_LAUNCHER",
        "title": "앱/런처 실행 오류",
        "level": "주의",
        "keywords": (
            "remote_app_launcher",
            "application error",
            "windows error reporting",
            ".net runtime",
            "sidebyside",
            "launcher crash",
            "app crash",
            "runtime error",
        ),
    },
    {
        "code": "REMOTE_STORAGE_MEMORY",
        "title": "저장공간/메모리 압박",
        "level": "주의",
        "keywords": (
            "remote_storage_memory",
            "memory pressure",
            "out of memory",
            "storage low",
            "disk full",
            "pagefile",
            "free space low",
        ),
    },
    {
        "code": "REMOTE_STARTUP_SERVICE",
        "title": "시작프로그램/서비스 부하",
        "level": "주의",
        "keywords": (
            "remote_startup_service",
            "startup app",
            "startup service",
            "service crash loop",
            "background service",
            "idle high cpu",
        ),
    },
    {
        "code": "REMOTE_LOCAL_NETWORK",
        "title": "로컬 네트워크 문제",
        "level": "주의",
        "keywords": (
            "remote_local_network",
            "dns failure",
            "gateway unreachable",
            "adapter disabled",
            "nic driver",
            "network diagnostic",
        ),
    },
    {
        "code": "VISIT_BOOT_REMOTE_BLOCKED",
        "title": "부팅/원격 연결 불가",
        "level": "검토",
        "keywords": (
            "visit_boot_remote_blocked",
            "device offline",
            "remote help not available",
            "boot failure",
            "heartbeat long missing",
        ),
    },
    {
        "code": "VISIT_DISK_FAILURE",
        "title": "디스크 장애 의심",
        "level": "위험",
        "keywords": (
            "visit_disk_failure",
            "smart critical",
            "bad block",
            "filesystem write failure",
            "disk event 7",
            "disk event 51",
            "disk event 55",
            "disk event 129",
            "disk event 153",
        ),
    },
    {
        "code": "VISIT_WHEA_BSOD",
        "title": "WHEA/블루스크린 반복",
        "level": "위험",
        "keywords": (
            "visit_whea_bsod",
            "whea-logger",
            "bugcheck",
            "bsod",
            "minidump",
        ),
    },
    {
        "code": "VISIT_POWER_SHUTDOWN",
        "title": "전원 꺼짐 반복",
        "level": "위험",
        "keywords": (
            "visit_power_shutdown",
            "kernel-power",
            "eventlog 6008",
            "unexpected shutdown",
            "power event",
        ),
    },
    {
        "code": "VISIT_FAN_THERMAL",
        "title": "과열/팬 이상",
        "level": "위험",
        "keywords": (
            "visit_fan_thermal",
            "thermal shutdown",
            "thermal throttle",
            "fan rpm 0",
            "thermal_service_required",
        ),
    },
)


class ConfigError(ValueError):
    pass


class RegisterError(RuntimeError):
    pass


class AgentError(RuntimeError):
    pass


class UploadError(AgentError):
    pass


class NamedInstanceLock:
    ERROR_ALREADY_EXISTS = 183

    def __init__(self, name: str) -> None:
        self.name = name
        self.handle: int | None = None
        self.acquired = False
        self._kernel32: Any = None

    def acquire(self) -> bool:
        if os.name != "nt":
            self.acquired = True
            return True
        try:
            import ctypes

            self._kernel32 = ctypes.windll.kernel32
            handle = self._kernel32.CreateMutexW(None, True, self.name)
            if not handle:
                self.acquired = True
                return True
            if self._kernel32.GetLastError() == self.ERROR_ALREADY_EXISTS:
                self._kernel32.CloseHandle(handle)
                return False
            self.handle = handle
            self.acquired = True
            return True
        except Exception:
            self.acquired = True
            return True

    def release(self) -> None:
        if not self.acquired:
            return
        if os.name == "nt" and self.handle and self._kernel32 is not None:
            try:
                self._kernel32.ReleaseMutex(self.handle)
            except Exception:
                pass
            try:
                self._kernel32.CloseHandle(self.handle)
            except Exception:
                pass
        self.handle = None
        self.acquired = False


def acquire_named_instance_lock(name: str) -> NamedInstanceLock | None:
    instance_lock = NamedInstanceLock(name)
    if not instance_lock.acquire():
        return None
    return instance_lock


@dataclass(frozen=True)
class IncidentWindow:
    incident_id: str
    trigger_type: str
    symptom_type: str
    detected_at: datetime
    started_at: datetime
    ended_at: datetime
    selected_by_user: bool
    consent_id: str | None = None

    def range_minutes(self) -> int:
        seconds = (self.ended_at - self.started_at).total_seconds()
        return max(1, int((seconds + 59) // 60))

    def metadata(self) -> dict[str, str]:
        fields = {
            "incidentId": self.incident_id,
            "triggerType": self.trigger_type,
            "symptomType": self.symptom_type,
            "detectedAt": self.detected_at.isoformat(),
            "startedAt": self.started_at.isoformat(),
            "endedAt": self.ended_at.isoformat(),
            "rangeStartedAt": self.started_at.isoformat(),
            "rangeEndedAt": self.ended_at.isoformat(),
            "rangeMinutes": str(self.range_minutes()),
            "selectedByUser": str(self.selected_by_user).lower(),
        }
        if self.consent_id:
            fields["consentId"] = self.consent_id
        return fields


@dataclass(frozen=True)
class IssueDraftMacro:
    symptom_type: str
    title: str
    detail: str
    symptom: str
    support_request_kind: str


class AgentRuntime:
    def __init__(self) -> None:
        self.running = True
        self.index = 0
        self.last_event_panel_signature: str | None = None
        self.last_issue_notification_at: float = 0.0

    def stop(self) -> None:
        self.running = False


@dataclass(frozen=True)
class AgentConfig:
    api_base_url: str
    activation_token: str
    device_fingerprint_hash: str
    os_version: str
    agent_version: str
    policy_version: str
    agent_token: str | None = None
    log_dir: Path = DEFAULT_LOG_DIR
    schema_version: int = DEFAULT_SCHEMA_VERSION
    web_base_url: str | None = None
    environment: str = "local"
    symptom: str | None = None
    symptom_type: str | None = None
    device_id: str | None = None

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "AgentConfig":
        return cls(
            api_base_url=required_config_text(data, "apiBaseUrl").rstrip("/"),
            activation_token=required_config_text(data, "activationToken"),
            device_fingerprint_hash=required_config_text(data, "deviceFingerprintHash"),
            os_version=required_config_text(data, "osVersion"),
            agent_version=required_config_text(data, "agentVersion"),
            policy_version=required_config_text(data, "policyVersion"),
            agent_token=optional_config_text(data, "agentToken"),
            log_dir=optional_config_path(data, "logDir", DEFAULT_LOG_DIR),
            schema_version=optional_config_int(data, "schemaVersion", DEFAULT_SCHEMA_VERSION),
            web_base_url=optional_config_text(data, "webBaseUrl"),
            environment=optional_config_text(data, "environment") or "local",
            symptom=optional_config_text(data, "symptom"),
            symptom_type=optional_config_text(data, "symptomType"),
            device_id=optional_config_text(data, "deviceId"),
        )

    def registration_status(self) -> str:
        if self.agent_token:
            return REGISTERED_STATUS
        return UNREGISTERED_STATUS


@dataclass(frozen=True)
class AgentUpdateInfo:
    version: str
    download_url: str
    sha256: str
    file_name: str = f"{APP_NAME}.exe"
    notes: str = ""


def required_config_text(data: dict[str, Any], field: str) -> str:
    if field not in data:
        raise ConfigError(f"Missing required config field: {field}")
    value = data[field]
    if not isinstance(value, str) or not value.strip():
        raise ConfigError(f"Config field must be a non-empty string: {field}")
    return value.strip()


def optional_config_text(data: dict[str, Any], field: str) -> str | None:
    if field not in data or data[field] is None:
        return None
    value = data[field]
    if not isinstance(value, str):
        raise ConfigError(f"Config field must be a string when provided: {field}")
    value = value.strip()
    return value or None


def optional_config_path(data: dict[str, Any], field: str, default: Path) -> Path:
    value = optional_config_text(data, field)
    return Path(value) if value else default


def optional_config_int(data: dict[str, Any], field: str, default: int) -> int:
    if field not in data or data[field] is None:
        return default
    value = data[field]
    if not isinstance(value, int):
        raise ConfigError(f"Config field must be an integer when provided: {field}")
    return value


def read_config_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise ConfigError(f"Config file not found: {path}")
    try:
        with path.open("r", encoding="utf-8-sig") as file:
            data = json.load(file)
    except json.JSONDecodeError as exception:
        raise ConfigError(f"Config file is not valid JSON: {path}: {exception.msg}") from exception
    if not isinstance(data, dict):
        raise ConfigError("Config file root must be a JSON object.")
    return data


def load_config(path: Path) -> AgentConfig:
    data = read_config_json(path)
    return AgentConfig.from_dict(data)


def ensure_runtime_config_version(path: Path) -> None:
    try:
        data = read_config_json(path)
    except ConfigError:
        return
    if data.get("agentVersion") == DEFAULT_AGENT_VERSION:
        return
    data["agentVersion"] = DEFAULT_AGENT_VERSION
    with path.open("w", encoding="utf-8") as file:
        json.dump(data, file, ensure_ascii=False, indent=2)
        file.write("\n")
    restrict_file_to_current_user(path)


def app_data_dir() -> Path:
    root = os.environ.get("LOCALAPPDATA")
    if root:
        return Path(root) / DATA_APP_NAME
    return Path.home() / f".{DATA_APP_NAME.lower()}"


def runtime_asset_path(relative_path: Path) -> Path:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base / relative_path


def default_background_config_path() -> Path:
    return app_data_dir() / "agent-config.json"


def web_base_url(config: AgentConfig) -> str:
    if config.web_base_url:
        return config.web_base_url.rstrip("/")
    base = config.api_base_url.rstrip("/")
    if base.endswith(":8080"):
        return base[:-5] + ":5173"
    return base


def support_new_url(config: AgentConfig) -> str:
    return f"{web_base_url(config)}/support/new"


def support_draft_url(config: AgentConfig, draft_id: str) -> str:
    return f"{support_new_url(config)}?draftId={quote(draft_id)}"


def update_manifest_url(config: AgentConfig) -> str:
    return f"{web_base_url(config)}{UPDATE_MANIFEST_PATH}"


def update_dir() -> Path:
    return app_data_dir() / UPDATE_DIR_NAME


def normalize_version_parts(version: str) -> list[int]:
    parts: list[int] = []
    for token in re.split(r"[^0-9]+", version):
        if token:
            parts.append(int(token))
    return parts or [0]


def compare_versions(left: str, right: str) -> int:
    left_parts = normalize_version_parts(left)
    right_parts = normalize_version_parts(right)
    size = max(len(left_parts), len(right_parts))
    left_parts.extend([0] * (size - len(left_parts)))
    right_parts.extend([0] * (size - len(right_parts)))
    if left_parts > right_parts:
        return 1
    if left_parts < right_parts:
        return -1
    return 0


def update_available(current_version: str, latest_version: str) -> bool:
    return compare_versions(latest_version, current_version) > 0


def parse_agent_update_manifest(payload: Any, manifest_url: str) -> AgentUpdateInfo:
    if not isinstance(payload, dict):
        raise AgentError("update manifest root must be a JSON object.")
    version = str(payload.get("version") or "").strip()
    download_url = str(payload.get("downloadUrl") or "").strip()
    sha256 = str(payload.get("sha256") or "").strip().lower()
    if not version:
        raise AgentError("update manifest is missing version.")
    if not download_url:
        raise AgentError("update manifest is missing downloadUrl.")
    if not re.fullmatch(r"[0-9a-f]{64}", sha256):
        raise AgentError("update manifest sha256 must be a 64-character hex string.")
    file_name = sanitize_filename(str(payload.get("fileName") or f"{APP_NAME}.exe"), f"{APP_NAME}.exe")
    notes = sanitize_display_text(payload.get("notes"), 500)
    return AgentUpdateInfo(
        version=version,
        download_url=urljoin(manifest_url, download_url),
        sha256=sha256,
        file_name=file_name,
        notes="" if notes == "-" else notes,
    )


def fetch_agent_update_manifest(
    config: AgentConfig,
    timeout_seconds: int = 8,
    opener: Any = None,
) -> AgentUpdateInfo:
    manifest_url = update_manifest_url(config)
    request = urllib.request.Request(
        manifest_url,
        method="GET",
        headers={"Accept": "application/json"},
    )
    open_fn = opener or urllib.request.urlopen
    try:
        with open_fn(request, timeout=timeout_seconds) as response:
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise AgentError(f"update check failed: HTTP {exception.code} {detail}") from exception
    except urllib.error.URLError as exception:
        raise AgentError(f"update check failed: {exception.reason}") from exception
    try:
        data = json.loads(payload)
    except json.JSONDecodeError as exception:
        raise AgentError(f"update manifest is not JSON: {payload[:200]}") from exception
    return parse_agent_update_manifest(data, manifest_url)


def sanitize_filename(value: str, fallback: str) -> str:
    name = Path(value).name.strip()
    if not name:
        return fallback
    return re.sub(r"[^A-Za-z0-9._-]", "_", name)


def download_bytes(url: str, timeout_seconds: int = 30, opener: Any = None) -> bytes:
    request = urllib.request.Request(url, method="GET")
    open_fn = opener or urllib.request.urlopen
    try:
        with open_fn(request, timeout=timeout_seconds) as response:
            return response.read()
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise AgentError(f"update download failed: HTTP {exception.code} {detail}") from exception
    except urllib.error.URLError as exception:
        raise AgentError(f"update download failed: {exception.reason}") from exception


def stage_agent_update(
    info: AgentUpdateInfo,
    timeout_seconds: int = 30,
    opener: Any = None,
) -> Path:
    data = download_bytes(info.download_url, timeout_seconds=timeout_seconds, opener=opener)
    digest = hashlib.sha256(data).hexdigest()
    if digest.lower() != info.sha256:
        raise AgentError("downloaded PCAgent update failed sha256 verification.")
    directory = update_dir()
    directory.mkdir(parents=True, exist_ok=True)
    staged_name = sanitize_filename(f"{APP_NAME}-{info.version}.exe", f"{APP_NAME}.exe")
    staged_path = directory / staged_name
    tmp_path = staged_path.with_suffix(staged_path.suffix + ".tmp")
    tmp_path.write_bytes(data)
    tmp_path.replace(staged_path)
    pending_path = directory / UPDATE_PENDING_FILE
    pending_path.write_text(
        json.dumps(
            {
                "version": info.version,
                "downloadUrl": info.download_url,
                "sha256": info.sha256,
                "stagedPath": str(staged_path),
                "createdAt": datetime.now(KST).isoformat(),
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return staged_path


def write_update_apply_script(staged_executable: Path, target_executable: Path, latest_version: str) -> Path:
    script_path = update_dir() / UPDATE_APPLY_SCRIPT_FILE
    script_path.parent.mkdir(parents=True, exist_ok=True)
    pid_path = pid_file()
    config_path = default_background_config_path()
    script = f"""@echo off
setlocal
set "SOURCE={staged_executable}"
set "TARGET={target_executable}"
set "PIDFILE={pid_path}"
set "CONFIG={config_path}"
set "VERSION={latest_version}"
if exist "%PIDFILE%" (
  set /p AGENT_PID=<"%PIDFILE%"
  if not "%AGENT_PID%"=="" taskkill /PID %AGENT_PID% /T /F >nul 2>nul
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$target=$env:TARGET; Get-CimInstance Win32_Process | Where-Object {{ $_.Name -eq 'PCAgent.exe' -and $_.ExecutablePath -and [string]::Equals($_.ExecutablePath, $target, [System.StringComparison]::OrdinalIgnoreCase) }} | ForEach-Object {{ Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }}" >nul 2>nul
timeout /t 2 /nobreak >nul
copy /Y "%SOURCE%" "%TARGET%" >nul
if errorlevel 1 (
  echo Failed to update PCAgent executable.
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p=$env:CONFIG; if (Test-Path -LiteralPath $p) {{ $j=Get-Content -LiteralPath $p -Raw | ConvertFrom-Json; $j.agentVersion=$env:VERSION; $j | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $p -Encoding UTF8 }}" >nul 2>nul
start "" "%TARGET%" run-background
exit /b 0
"""
    script_path.write_text(script, encoding="utf-8")
    return script_path


def prepare_agent_update(config: AgentConfig, opener: Any = None) -> dict[str, Any]:
    info = fetch_agent_update_manifest(config, opener=opener)
    if not update_available(config.agent_version, info.version):
        return {
            "status": "UP_TO_DATE",
            "currentVersion": config.agent_version,
            "latestVersion": info.version,
        }
    if not getattr(sys, "frozen", False):
        return {
            "status": "DEV_MODE",
            "currentVersion": config.agent_version,
            "latestVersion": info.version,
            "downloadUrl": info.download_url,
        }
    staged_path = stage_agent_update(info, opener=opener)
    target = installed_executable_path()
    script_path = write_update_apply_script(staged_path, target, info.version)
    return {
        "status": "READY",
        "currentVersion": config.agent_version,
        "latestVersion": info.version,
        "stagedPath": str(staged_path),
        "scriptPath": str(script_path),
    }


def launch_update_apply_script(script_path: Path) -> None:
    if os.name != "nt":
        raise AgentError("PCAgent self-update is currently supported only on Windows packaged builds.")
    subprocess.Popen(
        ["cmd.exe", "/c", str(script_path)],
        cwd=str(script_path.parent),
        **hidden_subprocess_kwargs(),
    )


def hidden_subprocess_kwargs() -> dict[str, Any]:
    if os.name != "nt":
        return {}
    flag = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    return {"creationflags": flag} if flag else {}


def restrict_file_to_current_user(path: Path) -> None:
    if os.name == "nt":
        user_sid = current_user_sid()
        if not user_sid:
            return
        try:
            subprocess.run(
                [
                    "icacls",
                    str(path),
                    "/inheritance:r",
                    "/grant:r",
                    f"*{user_sid}:F",
                    "*S-1-5-32-544:F",
                    "*S-1-5-18:F",
                ],
                check=False,
                capture_output=True,
                text=True,
                **hidden_subprocess_kwargs(),
            )
        except Exception:
            return
    else:
        try:
            path.chmod(0o600)
        except Exception:
            return


def current_user_sid() -> str | None:
    if os.name != "nt":
        return None
    try:
        result = subprocess.run(
            ["whoami", "/user", "/fo", "csv", "/nh"],
            check=False,
            capture_output=True,
            text=True,
            **hidden_subprocess_kwargs(),
        )
    except Exception:
        return None
    if result.returncode != 0:
        return None
    parts = [part.strip().strip('"') for part in result.stdout.strip().split(",")]
    if len(parts) < 2 or not parts[1].startswith("S-"):
        return None
    return parts[1]


def config_access_summary(path: Path) -> str:
    if os.name == "nt":
        return "restricted to current user when saved on Windows"
    try:
        mode = path.stat().st_mode & 0o777
    except OSError:
        return "unknown"
    return oct(mode)


def device_fingerprint_hash() -> str:
    raw = f"{socket.gethostname()}:{os.environ.get('USERNAME', '')}:{platform.platform()}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def ensure_default_config(path: Path) -> Path:
    if path.exists():
        ensure_runtime_config_version(path)
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    data = {
        "apiBaseUrl": "http://localhost:8080",
        "activationToken": "demo-agent-activation-token",
        "deviceFingerprintHash": device_fingerprint_hash(),
        "osVersion": platform.platform(),
        "agentVersion": DEFAULT_AGENT_VERSION,
        "policyVersion": DEFAULT_POLICY_VERSION,
        "agentToken": None,
        "logDir": str(path.parent / "logs"),
        "schemaVersion": DEFAULT_SCHEMA_VERSION,
        "webBaseUrl": "http://localhost:5173",
        "environment": "local",
    }
    with path.open("w", encoding="utf-8") as file:
        json.dump(data, file, ensure_ascii=False, indent=2)
        file.write("\n")
    restrict_file_to_current_user(path)
    return path


def downloads_dir() -> Path:
    return Path.home() / "Downloads"


def activation_config_candidates() -> list[Path]:
    config_names = (ACTIVATION_CONFIG_FILE, *LEGACY_ACTIVATION_CONFIG_FILES)
    candidates: list[Path] = []
    for directory in (Path.cwd(), downloads_dir(), app_data_dir()):
        candidates.extend(directory / name for name in config_names)
    for directory in (Path.cwd(), downloads_dir(), app_data_dir()):
        if directory.exists():
            candidates.extend(directory.glob("pcagent-activation*.json"))
            candidates.extend(directory.glob("buildgraph-agent-activation*.json"))
    unique: dict[Path, Path] = {}
    for path in candidates:
        if path.exists():
            unique[path.resolve()] = path
    return list(unique.values())


def cleanup_activation_config_files() -> None:
    for path in activation_config_candidates():
        try:
            path.unlink()
        except Exception:
            continue


def latest_activation_config() -> Path | None:
    candidates = activation_config_candidates()
    if not candidates:
        return None
    return max(candidates, key=lambda path: path.stat().st_mtime)


def activation_token_from_executable_name() -> str | None:
    name = Path(sys.executable).name if getattr(sys, "frozen", False) else Path(sys.argv[0]).name
    prefixes = "|".join(re.escape(prefix) for prefix in (DOWNLOAD_FILE_PREFIX, *LEGACY_DOWNLOAD_FILE_PREFIXES))
    match = re.search(rf"(?:{prefixes})-([A-Za-z0-9_-]{{20,}})", name)
    return match.group(1) if match else None


def import_activation_config(config_path: Path, activation_path: Path | None = None) -> bool:
    executable_token = activation_token_from_executable_name()
    if executable_token:
        config_data = read_config_json(config_path)
        if config_data.get("activationToken") != executable_token:
            config_data["activationToken"] = executable_token
            config_data["agentToken"] = None
            with config_path.open("w", encoding="utf-8") as file:
                json.dump(config_data, file, ensure_ascii=False, indent=2)
                file.write("\n")
            restrict_file_to_current_user(config_path)
            return True
        return False

    source = activation_path or latest_activation_config()
    if source is None:
        return False
    activation_data = read_config_json(source)
    activation_token = optional_config_text(activation_data, "activationToken")
    if not activation_token:
        return False

    config_data = read_config_json(config_path)
    changed = False
    for field in ("apiBaseUrl", "webBaseUrl", "environment", "symptom", "symptomType"):
        value = optional_config_text(activation_data, field)
        if value and config_data.get(field) != value:
            config_data[field] = value
            changed = True
    if config_data.get("activationToken") != activation_token:
        config_data["activationToken"] = activation_token
        config_data["agentToken"] = None
        changed = True
    if changed:
        with config_path.open("w", encoding="utf-8") as file:
            json.dump(config_data, file, ensure_ascii=False, indent=2)
            file.write("\n")
        restrict_file_to_current_user(config_path)
    return changed


def log_file(config: AgentConfig) -> Path:
    return config.log_dir / DEFAULT_LOG_FILE


def print_status(config_path: Path) -> None:
    if sys.platform == "win32":
        try:
            import ctypes

            ctypes.windll.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
        except Exception:
            try:
                ctypes.windll.user32.SetProcessDPIAware()
            except Exception:
                pass

    config = load_config(config_path)
    print(config.registration_status())


def print_doctor(config_path: Path) -> None:
    config = load_config(config_path)
    path = log_file(config)
    config.log_dir.mkdir(parents=True, exist_ok=True)
    print("config: ok")
    print(f"apiBaseUrl: {config.api_base_url}")
    print(f"registration: {config.registration_status()}")
    print(f"logDir: {config.log_dir.resolve()}")
    print(f"logFile: {path}")
    print(f"logBytes: {path.stat().st_size if path.exists() else 0}")
    print(f"agentVersion: {config.agent_version}")
    print(f"policyVersion: {config.policy_version}")
    print(f"environment: {config.environment}")
    print(f"webBaseUrl: {web_base_url(config)}")
    print(f"configAccess: {config_access_summary(config_path)}")
    if config.agent_token:
        print("agentToken: present")
    else:
        print("agentToken: missing; run register first or wait for Goal 10 token storage.")


def register_endpoint(api_base_url: str) -> str:
    return api_base_url.rstrip("/") + REGISTER_PATH


def consent_endpoint(api_base_url: str) -> str:
    return api_base_url.rstrip("/") + CONSENT_PATH


def registration_idempotency_key(config: AgentConfig) -> str:
    digest = hashlib.sha256(config.device_fingerprint_hash.encode("utf-8")).hexdigest()
    return f"agent-register-{digest[:32]}"


def register_request_body(config: AgentConfig) -> dict[str, str]:
    return {
        "activationToken": config.activation_token,
        "deviceFingerprintHash": config.device_fingerprint_hash,
        "registrationIdempotencyKey": registration_idempotency_key(config),
        "osVersion": config.os_version,
        "agentVersion": config.agent_version,
        "policyVersion": config.policy_version,
    }


def call_register(config: AgentConfig, timeout_seconds: int = 15) -> str:
    request_body = json.dumps(register_request_body(config)).encode("utf-8")
    request = urllib.request.Request(
        register_endpoint(config.api_base_url),
        data=request_body,
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            response_body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace").strip()
        message = detail or exception.reason
        raise RegisterError(f"Register request failed with HTTP {exception.code}: {message}") from exception
    except urllib.error.URLError as exception:
        raise RegisterError(f"Register request failed: {exception.reason}") from exception

    try:
        payload = json.loads(response_body)
    except json.JSONDecodeError as exception:
        raise RegisterError("Register response is not valid JSON.") from exception
    if not isinstance(payload, dict):
        raise RegisterError("Register response root must be a JSON object.")

    agent_token = payload.get("agentToken")
    if not isinstance(agent_token, str) or not agent_token.strip():
        raise RegisterError("Register response is missing agentToken.")
    return agent_token.strip()


def call_server_upload_consent(config: AgentConfig, timeout_seconds: int = 15) -> None:
    if not config.agent_token:
        raise RegisterError("agentToken is missing; cannot save server upload consent.")
    request_body = json.dumps(
        {
            "consentType": "SERVER_UPLOAD",
            "policyVersion": config.policy_version,
            "accepted": True,
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        consent_endpoint(config.api_base_url),
        data=request_body,
        headers={
            "Accept": "application/json",
            "Authorization": f"Bearer {config.agent_token}",
            "Content-Type": "application/json",
            "Idempotency-Key": "agent-consent-server-upload-" + hashlib.sha256(
                config.device_fingerprint_hash.encode("utf-8")
            ).hexdigest()[:32],
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            response.read()
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace").strip()
        message = detail or exception.reason
        raise RegisterError(f"Consent request failed with HTTP {exception.code}: {message}") from exception
    except urllib.error.URLError as exception:
        raise RegisterError(f"Consent request failed: {exception.reason}") from exception


def save_agent_token(config_path: Path, agent_token: str) -> None:
    if not agent_token.strip():
        raise ConfigError("agentToken must be a non-empty string.")
    data = read_config_json(config_path)
    data["agentToken"] = agent_token.strip()
    with config_path.open("w", encoding="utf-8") as file:
        json.dump(data, file, ensure_ascii=False, indent=2)
        file.write("\n")
    restrict_file_to_current_user(config_path)


def register_agent(config_path: Path) -> None:
    config = load_config(config_path)
    agent_token = call_register(config)
    save_agent_token(config_path, agent_token)
    call_server_upload_consent(load_config(config_path))
    print(REGISTERED_STATUS)
    print(f"agentToken: saved to {config_path}")
    print("serverUploadConsent: accepted")


def auto_register_agent(config_path: Path) -> bool:
    config = load_config(config_path)
    if config.agent_token:
        return False
    agent_token = call_register(config)
    save_agent_token(config_path, agent_token)
    call_server_upload_consent(load_config(config_path))
    return True


DISK_DELTA_FIELDS = (
    "diskReadBytesPerSec",
    "diskWriteBytesPerSec",
    "diskReadCountPerSec",
    "diskWriteCountPerSec",
    "diskBusyEstimatePercent",
)
GPU_FIELDS = (
    "gpuUsage",
    "gpuUsagePercent",
    "vramUsage",
    "vramUsagePercent",
    "gpuTemp",
    "gpuTempCelsius",
    "gpuFanRpm",
    "gpuFanPercent",
)
CPU_TEMP_FIELDS = ("cpuTemp", "cpuTempCelsius", "cpuTemperatureCelsius")
CPU_USAGE_FIELDS = ("cpuUsage", "cpuUsagePercent")
CPU_CLOCK_FIELDS = ("cpuClockMhz",)
MEMORY_USAGE_FIELDS = ("memoryUsage", "ramUsage", "memoryUsedPercent")
MEMORY_USED_FIELDS = ("memoryUsedBytes",)
MEMORY_TOTAL_FIELDS = ("memoryTotalBytes",)
DISK_USAGE_FIELDS = ("diskUsage", "diskUsedPercent")
DISK_USED_BYTES_FIELDS = ("diskUsedBytes",)
DISK_TOTAL_BYTES_FIELDS = ("diskTotalBytes",)
DISK_ACTIVE_FIELDS = ("diskBusyEstimatePercent",)
DISK_HEALTH_FIELDS = ("diskSmartStatus", "diskHealth")
GPU_USAGE_FIELDS = ("gpuUsage", "gpuUsagePercent")
GPU_VRAM_FIELDS = ("vramUsage", "vramUsagePercent")
GPU_TEMP_FIELDS = ("gpuTemp", "gpuTempCelsius")
GPU_FAN_RPM_FIELDS = ("gpuFanRpm",)
GPU_FAN_PERCENT_FIELDS = ("gpuFanPercent",)
OPTIONAL_SENSOR_STATUS_FIELDS = (
    "vramUsagePercent",
    "cpuTempCelsius",
    "gpuTempCelsius",
    "gpuFanRpm",
    "gpuFanPercent",
    "diskSmartStatus",
)
WINDOWS_GPU_ENGINE_COUNTER = r"\GPU Engine(*)\Utilization Percentage"
WINDOWS_DISK_BUSY_COUNTER = r"\PhysicalDisk(_Total)\% Disk Time"
SYSTEM_METRIC_KIND = "SYSTEM_METRIC"
OPTIONAL_SENSOR_UNSUPPORTED_TEXT = "현재 수집기 미지원"


def is_number(value: Any) -> bool:
    return isinstance(value, int | float) and not isinstance(value, bool)


def rounded_or_none(value: Any) -> float | None:
    if not is_number(value):
        return None
    return round(float(value), 1)


def parse_float(value: Any) -> float | None:
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return None


def clamp_percent(value: Any) -> float | None:
    number = rounded_or_none(value)
    if number is None:
        return None
    return max(0.0, min(100.0, number))


def set_metric_aliases(payload: dict[str, Any], fields: Sequence[str], value: Any) -> None:
    for field in fields:
        payload[field] = value


def mark_unavailable(
    payload: dict[str, Any],
    reasons: dict[str, str],
    fields: Sequence[str],
    reason: str,
) -> None:
    for field in fields:
        payload[field] = None
        reasons.setdefault(field, reason)


def sensor_reason_is_failure(reason: str | None) -> bool:
    normalized = (reason or "").casefold()
    return any(token in normalized for token in ("failed", "timed out", "exception"))


def sensor_exception_reason(prefix: str, exception: Exception) -> str:
    if isinstance(exception, PermissionError):
        return f"{prefix} permission required"
    return f"{prefix} query failed"


def safe_process_name(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    text = value.strip().replace("/", "\\")
    if not text:
        return None
    return text.rsplit("\\", 1)[-1] or None


def disk_usage_root() -> str:
    if os.name == "nt":
        drive = os.environ.get("SystemDrive", "C:")
        return drive + "\\"
    return "/"


def counter_delta(current: Any, previous: Any, field: str) -> float | None:
    if not hasattr(current, field) or not hasattr(previous, field):
        return None
    current_value = parse_float(getattr(current, field, None))
    previous_value = parse_float(getattr(previous, field, None))
    if current_value is None or previous_value is None:
        return None
    return max(0.0, current_value - previous_value)


def disk_busy_percent_from_counters(
    current: Any,
    previous: Any,
    elapsed: float,
) -> tuple[float | None, str | None, str | None]:
    if elapsed <= 0:
        return None, "disk delta elapsed time unavailable", None
    busy_delta_ms = counter_delta(current, previous, "busy_time")
    if busy_delta_ms is not None:
        return round(max(0.0, min(100.0, busy_delta_ms / 1000.0 / elapsed * 100.0)), 1), None, "psutil-busy-time"
    read_delta_ms = counter_delta(current, previous, "read_time")
    write_delta_ms = counter_delta(current, previous, "write_time")
    if read_delta_ms is not None and write_delta_ms is not None:
        io_delta_ms = read_delta_ms + write_delta_ms
        return round(max(0.0, min(100.0, io_delta_ms / 1000.0 / elapsed * 100.0)), 1), None, "psutil-read-write-time"
    return None, "disk busy_time/read_time/write_time unavailable", None


def read_windows_disk_busy_percent_powershell(
    runner: Any = subprocess.run,
) -> tuple[float | None, str | None]:
    if os.name != "nt":
        return None, "Windows disk performance counters unavailable on this OS"
    command = (
        "(Get-Counter '\\PhysicalDisk(_Total)\\% Disk Time' -ErrorAction Stop)."
        "CounterSamples | Select-Object -ExpandProperty CookedValue"
    )
    try:
        result = runner(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
            capture_output=True,
            text=True,
            timeout=3,
            check=False,
            **(hidden_subprocess_kwargs() if runner is subprocess.run else {}),
        )
    except FileNotFoundError:
        return None, "PowerShell unavailable"
    except subprocess.TimeoutExpired:
        return None, "PowerShell disk counter timed out"
    except Exception as exception:
        return None, sensor_exception_reason("PowerShell disk counter", exception)
    if getattr(result, "returncode", 1) != 0:
        return None, "PowerShell disk counter query failed"
    lines = [line.strip() for line in str(getattr(result, "stdout", "")).splitlines() if line.strip()]
    if not lines:
        return None, "PowerShell disk counter returned no values"
    value = clamp_percent(parse_float(lines[-1]))
    if value is None:
        return None, "PowerShell disk counter output parse failed"
    return value, None


def read_windows_gpu_usage_percent_win32pdh(win32pdh_module: Any = None) -> tuple[float | None, str | None]:
    if os.name != "nt" and win32pdh_module is None:
        return None, "Windows GPU performance counters unavailable on this OS"
    if win32pdh_module is None:
        try:
            import win32pdh as win32pdh_module  # type: ignore[import-not-found]
        except Exception:
            return None, "pywin32 win32pdh unavailable"
    try:
        counters, instances = win32pdh_module.EnumObjectItems(
            None,
            None,
            "GPU Engine",
            win32pdh_module.PERF_DETAIL_WIZARD,
        )
    except Exception:
        return None, "Windows GPU counter enumeration failed"
    if "Utilization Percentage" not in counters or not instances:
        return None, "Windows GPU counters unavailable"

    query = None
    try:
        query = win32pdh_module.OpenQuery()
        counter_handles = []
        for instance in instances:
            try:
                counter_path = win32pdh_module.MakeCounterPath(
                    (None, "GPU Engine", instance, None, 0, "Utilization Percentage"),
                )
                counter_handles.append(win32pdh_module.AddCounter(query, counter_path))
            except Exception:
                continue
        if not counter_handles:
            return None, "Windows GPU counters could not be opened"
        win32pdh_module.CollectQueryData(query)
        time.sleep(0.1)
        win32pdh_module.CollectQueryData(query)
        values: list[float] = []
        for handle in counter_handles:
            try:
                _, value = win32pdh_module.GetFormattedCounterValue(handle, win32pdh_module.PDH_FMT_DOUBLE)
            except Exception:
                continue
            number = parse_float(value)
            if number is not None:
                values.append(max(0.0, number))
        if not values:
            return None, "Windows GPU counter values unavailable"
        # GPU Engine exposes per-engine utilization. Sum active engines, then clamp
        # to a task-manager-like 0-100 estimate for the single UI percentage column.
        return round(max(0.0, min(100.0, sum(values))), 1), None
    except Exception as exception:
        return None, sensor_exception_reason("Windows GPU performance counter", exception)
    finally:
        if query is not None:
            try:
                win32pdh_module.CloseQuery(query)
            except Exception:
                pass


def read_windows_gpu_usage_percent_powershell(
    runner: Any = subprocess.run,
) -> tuple[float | None, str | None]:
    if os.name != "nt":
        return None, "PowerShell GPU counters unavailable on this OS"
    command = (
        "$samples = (Get-Counter '\\GPU Engine(*)\\Utilization Percentage' -ErrorAction Stop).CounterSamples; "
        "$sum = ($samples | Measure-Object -Property CookedValue -Sum).Sum; "
        "if ($null -eq $sum) { '' } else { [Math]::Min(100, [Math]::Max(0, $sum)) }"
    )
    try:
        result = runner(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
            capture_output=True,
            text=True,
            timeout=3,
            check=False,
            **(hidden_subprocess_kwargs() if runner is subprocess.run else {}),
        )
    except FileNotFoundError:
        return None, "PowerShell unavailable"
    except subprocess.TimeoutExpired:
        return None, "PowerShell GPU counter timed out"
    except Exception as exception:
        return None, sensor_exception_reason("PowerShell GPU counter", exception)
    if getattr(result, "returncode", 1) != 0:
        return None, "PowerShell GPU counter query failed"
    lines = [line.strip() for line in str(getattr(result, "stdout", "")).splitlines() if line.strip()]
    if not lines:
        return None, "PowerShell GPU counter returned no values"
    value = clamp_percent(parse_float(lines[-1]))
    if value is None:
        return None, "PowerShell GPU counter output parse failed"
    return value, None


def should_initialize_log_filter(log_tab_opened: bool, user_touched_filter: bool) -> bool:
    return not log_tab_opened and not user_touched_filter


def default_log_filter_values(now: datetime | None = None) -> tuple[str, int]:
    current = now or datetime.now(KST)
    if current.tzinfo is None:
        current = current.replace(tzinfo=KST)
    else:
        current = current.astimezone(KST)
    return current.strftime("%Y-%m-%d"), current.hour


def build_metric_snapshot(ts: datetime, index: int, event_type: str, payload: dict[str, Any]) -> dict:
    collected_at = ts.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    snapshot = {
        "schemaVersion": str(DEFAULT_SCHEMA_VERSION),
        "collectedAt": collected_at,
        "agentId": socket.gethostname() or "local-agent",
        "sequence": index,
        "kind": event_type,
        "payload": payload,
        "privacyFlags": {
            "containsRawPath": False,
            "masked": True,
            "containsUserContent": False,
        },
        "timestamp": ts.isoformat(),
    }
    snapshot.update(payload)
    return snapshot


class HardwareMetricCollector:
    def __init__(
        self,
        psutil_module: Any = psutil,
        nvidia_smi_runner: Any = None,
        gpu_counter_reader: Any = None,
        powershell_gpu_counter_reader: Any = None,
        disk_busy_reader: Any = None,
        time_fn: Any = time.monotonic,
    ) -> None:
        self.psutil = psutil_module
        self.nvidia_smi_runner = nvidia_smi_runner or self.run_nvidia_smi
        self.gpu_counter_reader = gpu_counter_reader or read_windows_gpu_usage_percent_win32pdh
        self.powershell_gpu_counter_reader = powershell_gpu_counter_reader or read_windows_gpu_usage_percent_powershell
        self.disk_busy_reader = disk_busy_reader or read_windows_disk_busy_percent_powershell
        self.time_fn = time_fn
        self._last_disk_io: Any = None
        self._last_disk_at: float | None = None
        self._last_process_cpu: dict[int, tuple[float, str]] | None = None
        self._last_process_at: float | None = None

    def collect(self, ts: datetime, index: int) -> dict:
        return self._collect(ts, index, include_processes=True)

    def collect_initial(self, ts: datetime, index: int) -> dict:
        return self._collect(ts, index, include_processes=False)

    def _collect(self, ts: datetime, index: int, include_processes: bool) -> dict:
        observed_at = float(self.time_fn())
        payload: dict[str, Any] = {
            "metricKind": "system",
            "eventType": "SYSTEM_METRIC",
            "message": "System metrics collected.",
            "osErrorEvent": None,
        }
        reasons: dict[str, str] = {}

        self.collect_cpu_memory(payload, reasons)
        self.collect_disk_usage(payload, reasons)
        self.collect_disk_io(payload, reasons, observed_at)
        mark_unavailable(
            payload,
            reasons,
            DISK_HEALTH_FIELDS,
            "SMART health unsupported by this collector",
        )
        self.collect_gpu(payload, reasons)
        self.collect_cpu_temperature(payload, reasons)
        if include_processes:
            self.collect_top_processes(payload, reasons, observed_at)
        self.collect_sensor_status(payload, reasons)

        payload["unavailableReason"] = reasons
        return build_metric_snapshot(ts, index, "SYSTEM_METRIC", payload)

    def collect_cpu_memory(self, payload: dict[str, Any], reasons: dict[str, str]) -> None:
        if self.psutil is None:
            mark_unavailable(payload, reasons, CPU_USAGE_FIELDS, "psutil unavailable")
            mark_unavailable(payload, reasons, CPU_CLOCK_FIELDS, "psutil unavailable")
            mark_unavailable(payload, reasons, MEMORY_USAGE_FIELDS, "psutil unavailable")
            mark_unavailable(payload, reasons, MEMORY_USED_FIELDS, "psutil unavailable")
            mark_unavailable(payload, reasons, MEMORY_TOTAL_FIELDS, "psutil unavailable")
            return
        try:
            cpu_usage = clamp_percent(self.psutil.cpu_percent(interval=0.05))
        except Exception as exception:
            cpu_usage = None
            mark_unavailable(payload, reasons, CPU_USAGE_FIELDS, sensor_exception_reason("psutil cpu_percent", exception))
        try:
            frequency = self.psutil.cpu_freq()
            cpu_clock = rounded_or_none(getattr(frequency, "current", None)) if frequency is not None else None
        except Exception as exception:
            cpu_clock = None
            mark_unavailable(payload, reasons, CPU_CLOCK_FIELDS, sensor_exception_reason("psutil cpu_freq", exception))
        try:
            memory = self.psutil.virtual_memory()
            memory_usage = clamp_percent(getattr(memory, "percent", None))
            memory_used = rounded_or_none(getattr(memory, "used", None))
            memory_total = rounded_or_none(getattr(memory, "total", None))
        except Exception as exception:
            memory_usage = None
            memory_used = None
            memory_total = None
            reason = sensor_exception_reason("psutil virtual_memory", exception)
            mark_unavailable(payload, reasons, MEMORY_USAGE_FIELDS, reason)
            mark_unavailable(payload, reasons, MEMORY_USED_FIELDS, reason)
            mark_unavailable(payload, reasons, MEMORY_TOTAL_FIELDS, reason)
        if cpu_usage is None:
            mark_unavailable(payload, reasons, CPU_USAGE_FIELDS, "psutil cpu_percent unavailable")
        else:
            set_metric_aliases(payload, CPU_USAGE_FIELDS, cpu_usage)
        if cpu_clock is None:
            mark_unavailable(payload, reasons, CPU_CLOCK_FIELDS, "psutil cpu_freq unavailable")
        else:
            set_metric_aliases(payload, CPU_CLOCK_FIELDS, cpu_clock)
        if memory_usage is None:
            mark_unavailable(payload, reasons, MEMORY_USAGE_FIELDS, "psutil virtual_memory unavailable")
        else:
            set_metric_aliases(payload, MEMORY_USAGE_FIELDS, memory_usage)
        if memory_used is None:
            mark_unavailable(payload, reasons, MEMORY_USED_FIELDS, "psutil memory used bytes unavailable")
        else:
            set_metric_aliases(payload, MEMORY_USED_FIELDS, memory_used)
        if memory_total is None:
            mark_unavailable(payload, reasons, MEMORY_TOTAL_FIELDS, "psutil memory total bytes unavailable")
        else:
            set_metric_aliases(payload, MEMORY_TOTAL_FIELDS, memory_total)

    def collect_disk_usage(self, payload: dict[str, Any], reasons: dict[str, str]) -> None:
        if self.psutil is None:
            mark_unavailable(payload, reasons, DISK_USAGE_FIELDS, "psutil unavailable")
            mark_unavailable(payload, reasons, DISK_USED_BYTES_FIELDS, "psutil unavailable")
            mark_unavailable(payload, reasons, DISK_TOTAL_BYTES_FIELDS, "psutil unavailable")
            return
        try:
            usage = self.psutil.disk_usage(disk_usage_root())
            disk_total = rounded_or_none(getattr(usage, "total", None))
            disk_free = rounded_or_none(getattr(usage, "free", None))
            if (
                disk_total is None
                or disk_free is None
                or disk_total <= 0
                or disk_free < 0
                or disk_free > disk_total
            ):
                disk_usage = None
                disk_used = None
            else:
                disk_used = round(disk_total - disk_free, 1)
                disk_usage = clamp_percent(disk_used / disk_total * 100.0)
        except Exception as exception:
            disk_usage = None
            disk_used = None
            disk_total = None
            reason = sensor_exception_reason("psutil disk_usage", exception)
            mark_unavailable(payload, reasons, DISK_USAGE_FIELDS, reason)
            mark_unavailable(payload, reasons, DISK_USED_BYTES_FIELDS, reason)
            mark_unavailable(payload, reasons, DISK_TOTAL_BYTES_FIELDS, reason)
        if disk_usage is None:
            mark_unavailable(payload, reasons, DISK_USAGE_FIELDS, "psutil disk total/free bytes unavailable")
        else:
            set_metric_aliases(payload, DISK_USAGE_FIELDS, disk_usage)
        if disk_used is None:
            mark_unavailable(payload, reasons, DISK_USED_BYTES_FIELDS, "psutil disk total/free bytes unavailable")
        else:
            set_metric_aliases(payload, DISK_USED_BYTES_FIELDS, disk_used)
        if disk_total is None:
            mark_unavailable(payload, reasons, DISK_TOTAL_BYTES_FIELDS, "psutil disk total bytes unavailable")
        else:
            set_metric_aliases(payload, DISK_TOTAL_BYTES_FIELDS, disk_total)

    def collect_disk_io(self, payload: dict[str, Any], reasons: dict[str, str], observed_at: float) -> None:
        if self.psutil is None:
            mark_unavailable(payload, reasons, DISK_DELTA_FIELDS, "psutil unavailable")
            payload["diskCollectorSource"] = "unavailable"
            return
        try:
            current = self.psutil.disk_io_counters()
        except Exception as exception:
            reason = sensor_exception_reason("psutil disk_io_counters", exception)
            mark_unavailable(payload, reasons, DISK_DELTA_FIELDS, reason)
            payload["diskCollectorSource"] = "unavailable"
            return
        if current is None:
            mark_unavailable(payload, reasons, DISK_DELTA_FIELDS, "psutil disk_io_counters unavailable")
            payload["diskCollectorSource"] = "unavailable"
            return
        previous = self._last_disk_io
        previous_at = self._last_disk_at
        self._last_disk_io = current
        self._last_disk_at = observed_at
        if previous is None or previous_at is None:
            mark_unavailable(payload, reasons, DISK_DELTA_FIELDS, "disk delta requires previous sample")
            payload["diskCollectorSource"] = "unavailable"
            return
        elapsed = observed_at - previous_at
        if elapsed <= 0:
            mark_unavailable(payload, reasons, DISK_DELTA_FIELDS, "disk delta elapsed time unavailable")
            payload["diskCollectorSource"] = "unavailable"
            return
        payload["diskReadBytesPerSec"] = round(
            max(0.0, float(getattr(current, "read_bytes", 0) - getattr(previous, "read_bytes", 0))) / elapsed,
            1,
        )
        payload["diskWriteBytesPerSec"] = round(
            max(0.0, float(getattr(current, "write_bytes", 0) - getattr(previous, "write_bytes", 0))) / elapsed,
            1,
        )
        payload["diskReadCountPerSec"] = round(
            max(0.0, float(getattr(current, "read_count", 0) - getattr(previous, "read_count", 0))) / elapsed,
            1,
        )
        payload["diskWriteCountPerSec"] = round(
            max(0.0, float(getattr(current, "write_count", 0) - getattr(previous, "write_count", 0))) / elapsed,
            1,
        )
        disk_busy, disk_busy_reason, disk_source = disk_busy_percent_from_counters(current, previous, elapsed)
        disk_bytes_per_sec = payload["diskReadBytesPerSec"] + payload["diskWriteBytesPerSec"]
        needs_windows_fallback = disk_busy is None or (disk_busy == 0.0 and disk_bytes_per_sec > 0)
        if needs_windows_fallback:
            try:
                fallback_busy, fallback_reason = self.disk_busy_reader()
            except Exception:
                fallback_busy, fallback_reason = None, "Windows disk counter query failed"
            fallback_busy = clamp_percent(fallback_busy)
            if fallback_busy is not None:
                payload["diskBusyEstimatePercent"] = fallback_busy
                payload["diskCollectorSource"] = "windows-performance-counter"
                return
            payload["diskBusyEstimatePercent"] = None
            payload["diskCollectorSource"] = "unavailable"
            reasons["diskBusyEstimatePercent"] = (
                fallback_reason
                or disk_busy_reason
                or "disk busy counter unavailable"
            )
        else:
            payload["diskBusyEstimatePercent"] = disk_busy
            payload["diskCollectorSource"] = disk_source or "psutil"

    def run_nvidia_smi(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "nvidia-smi",
                "--query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu,fan.speed",
                "--format=csv,noheader,nounits",
            ],
            capture_output=True,
            text=True,
            timeout=2,
            check=False,
            **hidden_subprocess_kwargs(),
        )

    def collect_nvidia_gpu(self, payload: dict[str, Any], reasons: dict[str, str]) -> tuple[bool, str | None]:
        try:
            result = self.nvidia_smi_runner()
        except FileNotFoundError:
            return False, "nvidia-smi unavailable"
        except subprocess.TimeoutExpired:
            return False, "nvidia-smi timed out"
        except Exception as exception:
            return False, sensor_exception_reason("nvidia-smi", exception)
        if getattr(result, "returncode", 1) != 0:
            error_text = str(getattr(result, "stderr", "")).casefold()
            if "access denied" in error_text or "permission" in error_text:
                return False, "nvidia-smi permission required"
            return False, "nvidia-smi query failed"
        lines = [line.strip() for line in str(getattr(result, "stdout", "")).splitlines() if line.strip()]
        if not lines:
            return False, "nvidia-smi returned no GPU metrics"
        parts = [part.strip() for part in lines[0].split(",")]
        if len(parts) < 4:
            return False, "nvidia-smi output parse failed"
        gpu_usage = clamp_percent(parse_float(parts[0]))
        memory_used = parse_float(parts[1])
        memory_total = parse_float(parts[2])
        gpu_temp = rounded_or_none(parse_float(parts[3]))
        gpu_fan = clamp_percent(parse_float(parts[4])) if len(parts) >= 5 else None
        if gpu_usage is None:
            payload["gpuUsage"] = None
            payload["gpuUsagePercent"] = None
            reasons["gpuUsage"] = "nvidia-smi GPU utilization unavailable"
            reasons["gpuUsagePercent"] = "nvidia-smi GPU utilization unavailable"
        else:
            payload["gpuUsage"] = gpu_usage
            payload["gpuUsagePercent"] = gpu_usage
        if memory_used is None or memory_total is None or memory_total <= 0:
            payload["vramUsage"] = None
            payload["vramUsagePercent"] = None
            reasons["vramUsage"] = "nvidia-smi VRAM utilization unavailable"
            reasons["vramUsagePercent"] = "nvidia-smi VRAM utilization unavailable"
        else:
            vram_usage = round(max(0.0, min(100.0, memory_used / memory_total * 100.0)), 1)
            payload["vramUsage"] = vram_usage
            payload["vramUsagePercent"] = vram_usage
        if gpu_temp is None:
            payload["gpuTemp"] = None
            payload["gpuTempCelsius"] = None
            reasons["gpuTemp"] = "nvidia-smi GPU temperature unavailable"
            reasons["gpuTempCelsius"] = "nvidia-smi GPU temperature unavailable"
        else:
            payload["gpuTemp"] = gpu_temp
            payload["gpuTempCelsius"] = gpu_temp
        payload["gpuFanRpm"] = None
        reasons["gpuFanRpm"] = "GPU fan RPM unsupported by nvidia-smi"
        if gpu_fan is None:
            payload["gpuFanPercent"] = None
            reasons["gpuFanPercent"] = "nvidia-smi GPU fan sensor unavailable"
        else:
            payload["gpuFanPercent"] = gpu_fan
        payload["gpuCollectorSource"] = "nvidia-smi"
        return True, None

    def collect_gpu_counter_fallback(
        self,
        payload: dict[str, Any],
        reasons: dict[str, str],
        reader: Any,
        source: str,
    ) -> tuple[bool, str | None]:
        try:
            usage, reason = reader()
        except Exception as exception:
            return False, sensor_exception_reason(source, exception)
        usage = clamp_percent(usage)
        if usage is None:
            return False, reason or f"{source} unavailable"
        set_metric_aliases(payload, GPU_USAGE_FIELDS, usage)
        mark_unavailable(payload, reasons, GPU_VRAM_FIELDS, f"VRAM utilization unavailable from {source}")
        mark_unavailable(payload, reasons, GPU_TEMP_FIELDS, f"GPU temperature unavailable from {source}")
        mark_unavailable(payload, reasons, GPU_FAN_RPM_FIELDS, f"GPU fan RPM unavailable from {source}")
        mark_unavailable(payload, reasons, GPU_FAN_PERCENT_FIELDS, f"GPU fan sensor unavailable from {source}")
        payload["gpuCollectorSource"] = source
        return True, None

    def collect_gpu(self, payload: dict[str, Any], reasons: dict[str, str]) -> None:
        nvidia_ok, nvidia_reason = self.collect_nvidia_gpu(payload, reasons)
        if nvidia_ok:
            return

        counter_ok, counter_reason = self.collect_gpu_counter_fallback(
            payload,
            reasons,
            self.gpu_counter_reader,
            "windows-performance-counter",
        )
        if counter_ok:
            return

        powershell_ok, powershell_reason = self.collect_gpu_counter_fallback(
            payload,
            reasons,
            self.powershell_gpu_counter_reader,
            "powershell-get-counter",
        )
        if powershell_ok:
            return

        reason = "; ".join(
            part
            for part in (nvidia_reason, counter_reason, powershell_reason)
            if part
        ) or "GPU metrics unavailable"
        mark_unavailable(payload, reasons, GPU_FIELDS, reason)
        payload["gpuCollectorSource"] = "unavailable"

    def collect_cpu_temperature(self, payload: dict[str, Any], reasons: dict[str, str]) -> None:
        if self.psutil is None:
            mark_unavailable(payload, reasons, CPU_TEMP_FIELDS, "psutil unavailable")
            return
        sensors = getattr(self.psutil, "sensors_temperatures", None)
        if not callable(sensors):
            mark_unavailable(payload, reasons, CPU_TEMP_FIELDS, "CPU temperature sensor unavailable")
            return
        try:
            readings = sensors(fahrenheit=False) or {}
        except Exception as exception:
            mark_unavailable(payload, reasons, CPU_TEMP_FIELDS, sensor_exception_reason("CPU temperature sensor", exception))
            return
        selected: float | None = None
        fallback: float | None = None
        for entries in readings.values():
            for entry in entries:
                current = rounded_or_none(getattr(entry, "current", None))
                if current is None:
                    continue
                if fallback is None:
                    fallback = current
                label = str(getattr(entry, "label", "")).lower()
                if "cpu" in label or "package" in label or "core" in label:
                    selected = current
                    break
            if selected is not None:
                break
        cpu_temp = selected if selected is not None else fallback
        if cpu_temp is None:
            mark_unavailable(payload, reasons, CPU_TEMP_FIELDS, "CPU temperature sensor unavailable")
        else:
            set_metric_aliases(payload, CPU_TEMP_FIELDS, cpu_temp)

    def collect_sensor_status(self, payload: dict[str, Any], reasons: dict[str, str]) -> None:
        status: dict[str, str] = {}
        default_reasons = {
            "vramUsagePercent": "VRAM sensor currently unsupported by this collector",
            "cpuTempCelsius": "CPU temperature sensor currently unsupported by this collector",
            "gpuTempCelsius": "GPU temperature sensor currently unsupported by this collector",
            "gpuFanRpm": "GPU fan RPM sensor currently unsupported by this collector",
            "gpuFanPercent": "GPU fan sensor currently unsupported by this collector",
            "diskSmartStatus": "SMART health currently unsupported by this collector",
        }
        for field in OPTIONAL_SENSOR_STATUS_FIELDS:
            if payload.get(field) is None:
                reason = reasons.get(field, default_reasons[field])
                status[field] = "failed" if sensor_reason_is_failure(reason) else "unsupported"
                reasons.setdefault(field, default_reasons[field])
            else:
                status[field] = "collected"
        payload["sensorStatus"] = status

    def collect_top_processes(self, payload: dict[str, Any], reasons: dict[str, str], observed_at: float) -> None:
        if self.psutil is None:
            payload["topCpuProcess"] = None
            payload["topRamProcess"] = None
            reasons["topCpuProcess"] = "psutil unavailable"
            reasons["topRamProcess"] = "psutil unavailable"
            return
        samples: dict[int, tuple[float, str]] = {}
        top_ram: tuple[int, str] | None = None
        try:
            processes = self.psutil.process_iter(["name", "memory_info", "cpu_times"])
        except Exception:
            processes = []
        for process in processes:
            try:
                info = getattr(process, "info", {}) or {}
                name = safe_process_name(info.get("name"))
                if not name:
                    name = safe_process_name(process.name())
                if not name:
                    continue
                memory_info = info.get("memory_info")
                if memory_info is None and hasattr(process, "memory_info"):
                    memory_info = process.memory_info()
                rss = getattr(memory_info, "rss", None)
                if isinstance(rss, int) and (top_ram is None or rss > top_ram[0]):
                    top_ram = (rss, name)
                cpu_times = info.get("cpu_times")
                if cpu_times is None and hasattr(process, "cpu_times"):
                    cpu_times = process.cpu_times()
                user_time = getattr(cpu_times, "user", None)
                system_time = getattr(cpu_times, "system", None)
                if is_number(user_time) and is_number(system_time):
                    samples[int(getattr(process, "pid", id(process)))] = (float(user_time) + float(system_time), name)
            except Exception:
                continue
        previous = self._last_process_cpu
        previous_at = self._last_process_at
        self._last_process_cpu = samples
        self._last_process_at = observed_at
        if top_ram is None:
            payload["topRamProcess"] = None
            reasons["topRamProcess"] = "process memory info unavailable"
        else:
            payload["topRamProcess"] = top_ram[1]
        if not samples:
            payload["topCpuProcess"] = None
            reasons["topCpuProcess"] = "process CPU info unavailable"
            return
        if previous is None or previous_at is None:
            payload["topCpuProcess"] = None
            reasons["topCpuProcess"] = "process CPU delta requires previous sample"
            return
        elapsed = observed_at - previous_at
        if elapsed <= 0:
            payload["topCpuProcess"] = None
            reasons["topCpuProcess"] = "process CPU delta elapsed time unavailable"
            return
        cpu_count = 1
        try:
            cpu_count = int(self.psutil.cpu_count() or 1)
        except Exception:
            cpu_count = 1
        top_cpu: tuple[float, str] | None = None
        for pid, (total_time, name) in samples.items():
            previous_sample = previous.get(pid)
            if previous_sample is None:
                continue
            delta = max(0.0, total_time - previous_sample[0])
            percent = delta / elapsed / max(1, cpu_count) * 100.0
            if top_cpu is None or percent > top_cpu[0]:
                top_cpu = (percent, name)
        if top_cpu is None or top_cpu[0] <= 0:
            payload["topCpuProcess"] = None
            reasons["topCpuProcess"] = "no process CPU activity observed"
        else:
            payload["topCpuProcess"] = top_cpu[1]


DEFAULT_METRIC_COLLECTOR = HardwareMetricCollector()


def metric_snapshot(ts: datetime, index: int, collector: Any = None) -> dict:
    metric_collector = collector or DEFAULT_METRIC_COLLECTOR
    return metric_collector.collect(ts, index)


def metric_payload_from_snapshot(snapshot: dict[str, Any]) -> dict[str, Any]:
    payload = snapshot.get("payload")
    if isinstance(payload, dict):
        return payload
    return snapshot


def sample_metric_snapshot(ts: datetime, index: int) -> dict:
    event_type = "DISPLAY_DRIVER_WARNING" if index % 7 == 0 else "DEMO_METRIC"
    message = "Display driver warning observed." if event_type != "DEMO_METRIC" else "Demo metric collected."
    scenario = DEMO_SENSOR_SAMPLES[index % len(DEMO_SENSOR_SAMPLES)]
    cpu_usage = float(scenario["cpuUsagePercent"])
    memory_usage = float(scenario["memoryUsedPercent"])
    disk_usage = float(scenario["diskUsedPercent"])
    gpu_usage = float(scenario["gpuUsagePercent"])
    gpu_temp = float(scenario["gpuTempCelsius"])
    cpu_temp = float(scenario["cpuTempCelsius"])
    payload = {
        "metricKind": "sample-demo",
        "cpuUsage": cpu_usage,
        "cpuUsagePercent": cpu_usage,
        "memoryUsage": memory_usage,
        "ramUsage": memory_usage,
        "memoryUsedPercent": memory_usage,
        "gpuUsage": gpu_usage,
        "gpuUsagePercent": gpu_usage,
        "vramUsage": None,
        "vramUsagePercent": None,
        "gpuTemp": gpu_temp,
        "gpuTempCelsius": gpu_temp,
        "cpuTemp": cpu_temp,
        "cpuTempCelsius": cpu_temp,
        "cpuTemperatureCelsius": cpu_temp,
        "diskUsage": disk_usage,
        "diskUsedPercent": disk_usage,
        "eventType": event_type,
        "message": message,
        "osErrorEvent": None if event_type == "DEMO_METRIC" else "Display driver warning",
        "topCpuProcess": "game.exe" if index % 2 else "ide64.exe",
        "topRamProcess": "game.exe",
        "unavailableReason": {"vramUsage": "demo scenario does not provide VRAM usage"},
    }
    return build_metric_snapshot(ts, index, event_type, payload)


def metric_log_row(
    ts: datetime,
    index: int,
    schema_version: int,
    agent_id: str,
    collector: Any = None,
) -> dict:
    snapshot = metric_snapshot(ts, index, collector)
    payload = metric_payload_from_snapshot(snapshot)
    return {
        **payload,
        "schemaVersion": schema_version,
        "collectedAt": ts.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
        "agentId": agent_id,
        "sequence": index,
        "kind": payload.get("eventType") or snapshot.get("kind") or "SYSTEM_METRIC",
        "payload": payload,
        "timestamp": ts.isoformat(),
        "privacyFlags": {
            "containsRawPath": False,
            "masked": True,
        },
    }


def sample_metric_log_row(ts: datetime, index: int, schema_version: int, agent_id: str) -> dict:
    snapshot = sample_metric_snapshot(ts, index)
    payload = metric_payload_from_snapshot(snapshot)
    return {
        **payload,
        "schemaVersion": schema_version,
        "collectedAt": ts.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
        "agentId": agent_id,
        "sequence": index,
        "kind": payload.get("eventType") or snapshot.get("kind") or "DEMO_METRIC",
        "payload": payload,
        "timestamp": ts.isoformat(),
        "privacyFlags": {
            "containsRawPath": False,
            "masked": True,
        },
    }


def write_sample(out: Path, count: int, interval_seconds: int) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    start = datetime.now(KST) - timedelta(seconds=count * interval_seconds)
    with out.open("w", encoding="utf-8") as file:
        for index in range(count):
            row = sample_metric_log_row(
                start + timedelta(seconds=index * interval_seconds),
                index,
                DEFAULT_SCHEMA_VERSION,
                "sample-agent",
            )
            file.write(json.dumps(row, ensure_ascii=False) + "\n")


def read_recent_rows(source: Path, minutes: int) -> list[dict]:
    cutoff = datetime.now(KST) - timedelta(minutes=minutes)
    rows: list[dict] = []
    with source.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(row, dict):
                continue
            ts = parse_log_timestamp(row)
            if ts is None:
                continue
            if ts >= cutoff:
                rows.append(row)
    return rows


def parse_datetime(value: str, field_name: str) -> datetime:
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError as exception:
        raise ConfigError(f"{field_name} must be ISO-8601 datetime.") from exception
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=KST)
    return parsed


def default_incident_window(
    symptom_type: str,
    detected_at: datetime | None = None,
    trigger_type: str = "USER_REQUEST",
    incident_id: str | None = None,
    selected_by_user: bool = True,
    consent_id: str | None = None,
) -> IncidentWindow:
    detected = detected_at or datetime.now(KST)
    if symptom_type in VISIT_SYMPTOM_TYPES and symptom_type != "VISIT_BOOT_REMOTE_BLOCKED":
        pre = timedelta(minutes=30)
        post = timedelta(minutes=10)
    elif symptom_type == "VISIT_BOOT_REMOTE_BLOCKED":
        pre = timedelta(minutes=30)
        post = timedelta(minutes=0)
    else:
        pre = timedelta(minutes=15)
        post = timedelta(minutes=5)
    return IncidentWindow(
        incident_id=incident_id or f"incident-{uuid.uuid4()}",
        trigger_type=trigger_type,
        symptom_type=symptom_type,
        detected_at=detected,
        started_at=detected - pre,
        ended_at=detected + post,
        selected_by_user=selected_by_user,
        consent_id=consent_id,
    )


def build_incident_window(
    symptom_type: str,
    detected_at: str | None,
    started_at: str | None,
    ended_at: str | None,
    trigger_type: str,
    incident_id: str | None,
    selected_by_user: bool,
    consent_id: str | None,
) -> IncidentWindow:
    detected = parse_datetime(detected_at, "detectedAt") if detected_at else datetime.now(KST)
    window = default_incident_window(
        symptom_type,
        detected,
        trigger_type=trigger_type,
        incident_id=incident_id,
        selected_by_user=selected_by_user,
        consent_id=consent_id,
    )
    start = parse_datetime(started_at, "startedAt") if started_at else window.started_at
    end = parse_datetime(ended_at, "endedAt") if ended_at else window.ended_at
    if not end > start:
        raise ConfigError("endedAt must be after startedAt.")
    return IncidentWindow(
        incident_id=window.incident_id,
        trigger_type=trigger_type,
        symptom_type=symptom_type,
        detected_at=detected,
        started_at=start,
        ended_at=end,
        selected_by_user=selected_by_user,
        consent_id=consent_id,
    )


def read_window_rows(source: Path, window: IncidentWindow) -> list[dict]:
    rows: list[dict] = []
    with source.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(row, dict):
                continue
            timestamp = parse_log_timestamp(row)
            if timestamp is None:
                continue
            if timestamp.tzinfo is None:
                timestamp = timestamp.replace(tzinfo=KST)
            if window.started_at <= timestamp <= window.ended_at:
                rows.append(row)
    return rows


def export_recent(source: Path, out: Path, minutes: int) -> None:
    rows = read_recent_rows(source, minutes)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as file:
        for row in rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")


def export_window(source: Path, out: Path, window: IncidentWindow) -> None:
    rows = read_window_rows(source, window)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as file:
        for row in rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")


def append_metric_with_row(config: AgentConfig, index: int = 0, collector: Any = None) -> tuple[Path, dict]:
    path = log_file(config)
    path.parent.mkdir(parents=True, exist_ok=True)
    row = metric_log_row(
        datetime.now(KST),
        index,
        config.schema_version,
        config.device_fingerprint_hash,
        collector,
    )
    row["agentVersion"] = config.agent_version
    row["policyVersion"] = config.policy_version
    with path.open("a", encoding="utf-8") as file:
        file.write(json.dumps(row, ensure_ascii=False) + "\n")
    return path, row


def append_metric(config: AgentConfig, index: int = 0, collector: Any = None) -> Path:
    path, _ = append_metric_with_row(config, index, collector)
    return path


def gzip_recent(source: Path, out: Path, minutes: int = DEFAULT_RANGE_MINUTES) -> int:
    if minutes <= 0:
        raise AgentError("minutes must be greater than 0.")
    if not source.exists():
        raise AgentError(f"log file does not exist: {source}")
    rows = read_recent_rows(source, minutes)
    if not rows:
        raise AgentError(f"no log rows found in the last {minutes} minutes: {source}")
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as raw_file:
        with gzip.GzipFile(fileobj=raw_file, mode="wb", mtime=0) as gzip_file:
            with TextIOWrapper(gzip_file, encoding="utf-8") as text_file:
                for row in rows:
                    text_file.write(json.dumps(row, ensure_ascii=False) + "\n")
    return out.stat().st_size


def gzip_window(source: Path, out: Path, window: IncidentWindow) -> int:
    if not source.exists():
        raise AgentError(f"log file does not exist: {source}")
    rows = read_window_rows(source, window)
    if not rows:
        raise AgentError(f"no log rows found in selected incident window: {source}")
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as raw_file:
        with gzip.GzipFile(fileobj=raw_file, mode="wb", mtime=0) as gzip_file:
            with TextIOWrapper(gzip_file, encoding="utf-8") as text_file:
                for row in rows:
                    text_file.write(json.dumps(row, ensure_ascii=False) + "\n")
    return out.stat().st_size


def support_url(api_base_url: str, ticket_id: str, configured_web_base_url: str | None = None) -> str:
    base = configured_web_base_url.rstrip("/") if configured_web_base_url else api_base_url.rstrip("/")
    if not configured_web_base_url and base.endswith(":8080"):
        base = base[:-5] + ":5173"
    return f"{base}/support/{ticket_id}"


def as_rag_preview_endpoint(api_base_url: str) -> str:
    return api_base_url.rstrip("/") + AS_RAG_PREVIEW_PATH


def diagnosis_chat_endpoint(api_base_url: str) -> str:
    return api_base_url.rstrip("/") + DIAGNOSIS_CHAT_PATH


def support_mode_label_for_service(recommended_service: Any) -> str:
    return {
        "REMOTE_SUPPORT": "원격지원 신청",
        "VISIT_SUPPORT": "방문지원 신청",
        "DIAGNOSIS_ONLY": "우선 진단만 받기",
    }.get(str(recommended_service or "DIAGNOSIS_ONLY"), "우선 진단만 받기")


def format_as_rag_preview(result: dict[str, Any]) -> str:
    label = str(result.get("recommendedServiceLabel") or support_mode_label_for_service(result.get("recommendedService")))
    confidence = str(result.get("confidence") or "LOW")
    message = str(result.get("recommendationMessage") or "로그 기반 추천 결과를 확인했습니다.")
    summary = str(result.get("summaryText") or "").strip()
    if summary:
        return f"추천: {label} / 신뢰도: {confidence}\n{message}\n요약: {summary}"
    return f"추천: {label} / 신뢰도: {confidence}\n{message}"


def diagnosis_history_path(config: AgentConfig) -> Path:
    return config.log_dir.parent / DIAGNOSIS_HISTORY_FILE


def diagnosis_chat_history_path(config: AgentConfig) -> Path:
    return config.log_dir.parent / DIAGNOSIS_CHAT_HISTORY_FILE


def string_list(value: Any, limit: int = 6) -> list[str]:
    if not isinstance(value, list):
        return []
    items: list[str] = []
    for item in value:
        text = sanitize_display_text(item, 80)
        if text != "-":
            items.append(text)
        if len(items) >= limit:
            break
    return items


def diagnosis_record_tone(recommended_service: str, support_decision: str) -> str:
    if support_decision in {"VISIT_REQUIRED", "REPAIR_OR_REPLACE"} or recommended_service == "VISIT_SUPPORT":
        return "danger"
    if support_decision in {"REMOTE_POSSIBLE", "NEEDS_MORE_INFO"} or recommended_service == "REMOTE_SUPPORT":
        return "warning"
    return "ok"


def diagnosis_history_record(
    result: dict[str, Any],
    window: IncidentWindow,
    created_at: datetime | None = None,
) -> dict[str, Any]:
    created = created_at or datetime.now(KST)
    routing = result.get("supportRouting") if isinstance(result.get("supportRouting"), dict) else {}
    recommended_service = str(result.get("recommendedService") or routing.get("recommendedService") or "DIAGNOSIS_ONLY")
    service_label = str(result.get("recommendedServiceLabel") or routing.get("recommendedServiceLabel") or support_mode_label_for_service(recommended_service))
    support_decision = str(result.get("supportDecision") or routing.get("recommendedDecision") or "NEEDS_MORE_INFO")
    evidence_items: list[dict[str, str]] = []
    evidence = result.get("evidence")
    if isinstance(evidence, list):
        for item in evidence[:6]:
            if not isinstance(item, dict):
                continue
            evidence_items.append(
                {
                    "title": sanitize_display_text(item.get("title") or item.get("sourceId"), 80),
                    "summary": sanitize_display_text(item.get("summary"), 140),
                    "reasonCode": sanitize_display_text(item.get("reasonCode"), 80),
                }
            )
    return {
        "id": f"diagnosis-{uuid.uuid4()}",
        "createdAt": created.isoformat(),
        "recommendedService": recommended_service,
        "recommendedServiceLabel": service_label,
        "supportDecision": support_decision,
        "supportDecisionLabel": sanitize_display_text(result.get("supportDecisionLabel"), 80),
        "confidence": sanitize_display_text(result.get("confidence") or routing.get("confidence") or "LOW", 20),
        "recommendationMessage": sanitize_display_text(result.get("recommendationMessage"), 260),
        "summaryText": sanitize_display_text(result.get("summaryText") or result.get("recommendationMessage"), 420),
        "reasonCodes": string_list(routing.get("reasonCodes")),
        "remoteActions": string_list(routing.get("remoteActions")),
        "visitReasons": string_list(routing.get("visitReasons")),
        "blockingFactors": string_list(routing.get("blockingFactors")),
        "evidence": evidence_items,
        "incidentWindow": window.metadata(),
        "tone": diagnosis_record_tone(recommended_service, support_decision),
    }


def normalize_diagnosis_history_record(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        return None
    recommended_service = str(value.get("recommendedService") or "DIAGNOSIS_ONLY")
    support_decision = str(value.get("supportDecision") or "NEEDS_MORE_INFO")
    return {
        "id": sanitize_display_text(value.get("id") or f"diagnosis-{uuid.uuid4()}", 80),
        "createdAt": sanitize_display_text(value.get("createdAt"), 40),
        "recommendedService": recommended_service,
        "recommendedServiceLabel": sanitize_display_text(
            value.get("recommendedServiceLabel") or support_mode_label_for_service(recommended_service),
            80,
        ),
        "supportDecision": support_decision,
        "supportDecisionLabel": sanitize_display_text(value.get("supportDecisionLabel"), 80),
        "confidence": sanitize_display_text(value.get("confidence") or "LOW", 20),
        "recommendationMessage": sanitize_display_text(value.get("recommendationMessage"), 260),
        "summaryText": sanitize_display_text(value.get("summaryText") or value.get("recommendationMessage"), 420),
        "reasonCodes": string_list(value.get("reasonCodes")),
        "remoteActions": string_list(value.get("remoteActions")),
        "visitReasons": string_list(value.get("visitReasons")),
        "blockingFactors": string_list(value.get("blockingFactors")),
        "evidence": [
            {
                "title": sanitize_display_text(item.get("title"), 80),
                "summary": sanitize_display_text(item.get("summary"), 140),
                "reasonCode": sanitize_display_text(item.get("reasonCode"), 80),
            }
            for item in value.get("evidence", [])
            if isinstance(item, dict)
        ][:6],
        "incidentWindow": value.get("incidentWindow") if isinstance(value.get("incidentWindow"), dict) else {},
        "tone": str(value.get("tone") or diagnosis_record_tone(recommended_service, support_decision)),
    }


def append_diagnosis_history(config: AgentConfig, record: dict[str, Any]) -> Path:
    path = diagnosis_history_path(config)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as file:
        file.write(json.dumps(record, ensure_ascii=False) + "\n")
    return path


def read_diagnosis_history(config: AgentConfig, limit: int = DIAGNOSIS_HISTORY_LIMIT) -> list[dict[str, Any]]:
    path = diagnosis_history_path(config)
    if not path.exists():
        return []
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError:
                continue
            record = normalize_diagnosis_history_record(value)
            if record is not None:
                rows.append(record)
    return list(reversed(rows[-limit:]))


def latest_diagnosis_record(config: AgentConfig) -> dict[str, Any] | None:
    records = read_diagnosis_history(config, limit=1)
    return records[0] if records else None


def diagnosis_chat_context(record: dict[str, Any]) -> dict[str, Any]:
    evidence_ids: list[str] = []
    evidence = record.get("evidence")
    if isinstance(evidence, list):
        for index, item in enumerate(evidence[:5]):
            if isinstance(item, dict):
                reason_code = sanitize_display_text(item.get("reasonCode"), 80)
                title = sanitize_display_text(item.get("title"), 80)
                evidence_ids.append(reason_code if reason_code != "-" else f"pc-agent-evidence-{index + 1}:{title}")
    return {
        "diagnosisId": sanitize_display_text(record.get("id"), 80),
        "summaryText": sanitize_display_text(record.get("summaryText"), 420),
        "recommendationMessage": sanitize_display_text(record.get("recommendationMessage"), 260),
        "recommendedService": sanitize_display_text(record.get("recommendedService"), 80),
        "recommendedDecision": sanitize_display_text(record.get("supportDecision"), 80),
        "confidence": sanitize_display_text(record.get("confidence"), 20),
        "reasonCodes": string_list(record.get("reasonCodes"), 6),
        "remoteActions": string_list(record.get("remoteActions"), 6),
        "visitReasons": string_list(record.get("visitReasons"), 6),
        "blockingFactors": string_list(record.get("blockingFactors"), 6),
        "evidenceIds": evidence_ids,
    }


def normalize_diagnosis_chat_message(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        return None
    role = str(value.get("role") or "").strip().lower()
    if role not in {"user", "assistant"}:
        return None
    content = sanitize_display_text(value.get("content"), 2000)
    if content == "-":
        return None
    return {
        "id": sanitize_display_text(value.get("id") or f"chat-{uuid.uuid4()}", 80),
        "diagnosisId": sanitize_display_text(value.get("diagnosisId"), 80),
        "createdAt": sanitize_display_text(value.get("createdAt"), 40),
        "role": role,
        "content": content,
        "payload": value.get("payload") if isinstance(value.get("payload"), dict) else {},
    }


def append_diagnosis_chat_message(
    config: AgentConfig,
    diagnosis_id: str,
    role: str,
    content: str,
    payload: dict[str, Any] | None = None,
) -> Path:
    path = diagnosis_chat_history_path(config)
    path.parent.mkdir(parents=True, exist_ok=True)
    row = {
        "id": f"chat-{uuid.uuid4()}",
        "diagnosisId": sanitize_display_text(diagnosis_id, 80),
        "createdAt": datetime.now(KST).isoformat(),
        "role": role,
        "content": sanitize_display_text(content, 2000),
        "payload": payload or {},
    }
    with path.open("a", encoding="utf-8") as file:
        file.write(json.dumps(row, ensure_ascii=False) + "\n")
    return path


def read_diagnosis_chat_history(
    config: AgentConfig,
    diagnosis_id: str | None = None,
    limit: int = DIAGNOSIS_CHAT_HISTORY_LIMIT,
) -> list[dict[str, Any]]:
    path = diagnosis_chat_history_path(config)
    if not path.exists():
        return []
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError:
                continue
            message = normalize_diagnosis_chat_message(value)
            if message is None:
                continue
            if diagnosis_id is not None and message.get("diagnosisId") != diagnosis_id:
                continue
            rows.append(message)
    return rows[-limit:]


def format_diagnosis_history_time(value: Any) -> str:
    parsed = parse_iso_datetime(value)
    if parsed is None:
        return "-"
    return parsed.strftime("%Y-%m-%d %H:%M")


def compact_home_diagnosis_reason(record: dict[str, Any]) -> str:
    recommended_service = str(record.get("recommendedService") or "")
    support_decision = str(record.get("supportDecision") or "")
    if support_decision in {"VISIT_REQUIRED", "REPAIR_OR_REPLACE"} or recommended_service == "VISIT_SUPPORT":
        return "방문 점검이 필요한 신호가 있어 관리자 검토를 권장합니다."
    if support_decision == "REMOTE_POSSIBLE" or recommended_service == "REMOTE_SUPPORT":
        return "원격으로 먼저 확인 가능한 신호가 있어 원격지원을 권장합니다."
    if support_decision == "UNSUPPORTED":
        return "PC Agent AS 지원 범위 밖 신호로 분류되어 관리자 확인이 필요합니다."
    if support_decision == "MONITOR_ONLY":
        return "즉시 조치보다 재발 여부를 더 지켜보는 것이 적절합니다."
    return "최근 로그에서 뚜렷한 장애 신호가 없어 원격/방문 판단은 보류했습니다."


def compact_home_diagnosis_text(record: dict[str, Any]) -> str:
    label = sanitize_display_text(record.get("recommendedServiceLabel"), 40)
    confidence = sanitize_display_text(record.get("confidence"), 20)
    return f"최근 진단: {label} / 신뢰도 {confidence}\n{compact_home_diagnosis_reason(record)}"


def diagnosis_history_detail_text(record: dict[str, Any]) -> str:
    lines = [
        f"진단 시간: {format_diagnosis_history_time(record.get('createdAt'))}",
        f"추천 서비스: {sanitize_display_text(record.get('recommendedServiceLabel'), 80)}",
        f"신뢰도: {sanitize_display_text(record.get('confidence'), 20)}",
        "",
        "요약",
        sanitize_display_text(record.get("summaryText"), 420),
        "",
        "추천 문구",
        sanitize_display_text(record.get("recommendationMessage"), 260),
    ]
    for title, key in (
        ("근거 코드", "reasonCodes"),
        ("원격 조치 후보", "remoteActions"),
        ("방문 판단 근거", "visitReasons"),
        ("차단 요인", "blockingFactors"),
    ):
        items = record.get(key)
        if isinstance(items, list) and items:
            lines.extend(["", title, "- " + "\n- ".join(sanitize_display_text(item, 100) for item in items)])
    evidence = record.get("evidence")
    if isinstance(evidence, list) and evidence:
        lines.extend(["", "근거 요약"])
        for item in evidence[:6]:
            if isinstance(item, dict):
                title = sanitize_display_text(item.get("title"), 80)
                summary = sanitize_display_text(item.get("summary"), 140)
                lines.append(f"- {title}: {summary}")
    return "\n".join(lines)


def build_multipart(fields: dict[str, str], file_field: str, file_path: Path) -> tuple[bytes, str]:
    boundary = f"----buildgraph-agent-{uuid.uuid4().hex}"
    parts: list[bytes] = []
    for name, value in fields.items():
        parts.append(
            (
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="{name}"\r\n'
                "Content-Type: text/plain; charset=utf-8\r\n\r\n"
                f"{value}\r\n"
            ).encode("utf-8")
        )

    content_type = mimetypes.guess_type(file_path.name)[0] or "application/gzip"
    parts.append(
        (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{file_field}"; filename="{file_path.name}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n"
        ).encode("utf-8")
    )
    parts.append(file_path.read_bytes())
    parts.append(f"\r\n--{boundary}--\r\n".encode("utf-8"))
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def upload_gzip(
    config: AgentConfig,
    gzip_path: Path,
    idempotency_key: str,
    symptom: str | None = None,
    incident_window: IncidentWindow | None = None,
) -> dict:
    if not config.agent_token:
        raise UploadError("agentToken is missing. Run register first or wait for Goal 10 token storage.")
    if gzip_path.stat().st_size == 0:
        raise UploadError(f"gzip file is empty: {gzip_path}")

    fields = incident_window.metadata() if incident_window else {"rangeMinutes": str(DEFAULT_RANGE_MINUTES)}
    fields["schemaVersion"] = str(config.schema_version)
    if symptom:
        fields["symptom"] = symptom
    body, content_type = build_multipart(fields, "file", gzip_path)
    upload_url = config.api_base_url.rstrip("/") + LOG_UPLOAD_PATH
    request = urllib.request.Request(
        upload_url,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {config.agent_token}",
            "Idempotency-Key": idempotency_key,
            "Content-Type": content_type,
            "Content-Length": str(len(body)),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise UploadError(f"upload failed: HTTP {exception.code} {detail}") from exception
    except urllib.error.URLError as exception:
        raise UploadError(f"upload failed: {exception.reason}") from exception

    try:
        result = json.loads(payload)
    except json.JSONDecodeError as exception:
        raise UploadError(f"upload response is not JSON: {payload[:200]}") from exception

    if not isinstance(result, dict) or not result.get("ticketId"):
        raise UploadError(f"upload response did not include ticketId: {result}")
    return result


def preview_as_rag(
    config: AgentConfig,
    gzip_path: Path,
    idempotency_key: str,
    incident_window: IncidentWindow,
    timeout_seconds: int = 8,
) -> dict[str, Any]:
    if not config.agent_token:
        raise UploadError("agentToken is missing. Run register first or wait for Goal 10 token storage.")
    if gzip_path.stat().st_size == 0:
        raise UploadError(f"gzip file is empty: {gzip_path}")

    fields = incident_window.metadata()
    fields["schemaVersion"] = str(config.schema_version)
    body, content_type = build_multipart(fields, "file", gzip_path)
    request = urllib.request.Request(
        as_rag_preview_endpoint(config.api_base_url),
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {config.agent_token}",
            "Idempotency-Key": idempotency_key,
            "Content-Type": content_type,
            "Content-Length": str(len(body)),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise UploadError(f"AS RAG preview failed: HTTP {exception.code} {detail}") from exception
    except urllib.error.URLError as exception:
        raise UploadError(f"AS RAG preview failed: {exception.reason}") from exception

    try:
        result = json.loads(payload)
    except json.JSONDecodeError as exception:
        raise UploadError(f"AS RAG preview response is not JSON: {payload[:200]}") from exception
    if not isinstance(result, dict) or not result.get("recommendedService"):
        raise UploadError(f"AS RAG preview response did not include recommendedService: {result}")
    return result


def send_diagnosis_chat(
    config: AgentConfig,
    diagnosis_record: dict[str, Any],
    messages: Sequence[dict[str, Any]],
    message: str,
    timeout_seconds: int = 20,
) -> dict[str, Any]:
    if not config.agent_token:
        raise UploadError("agentToken is missing. Run register first or wait for Goal 10 token storage.")
    text = message.strip()
    if not text:
        raise UploadError("diagnosis chat message is empty.")
    payload = {
        "message": text,
        "diagnosis": diagnosis_chat_context(diagnosis_record),
        "messages": [
            {
                "role": str(item.get("role") or ""),
                "content": sanitize_display_text(item.get("content"), 2000),
            }
            for item in messages[-8:]
            if isinstance(item, dict) and item.get("role") in {"user", "assistant"}
        ],
    }
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        diagnosis_chat_endpoint(config.api_base_url),
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {config.agent_token}",
            "Content-Type": "application/json; charset=utf-8",
            "Content-Length": str(len(body)),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            response_body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise UploadError(f"diagnosis chat failed: HTTP {exception.code} {detail}") from exception
    except urllib.error.URLError as exception:
        raise UploadError(f"diagnosis chat failed: {exception.reason}") from exception

    try:
        result = json.loads(response_body)
    except json.JSONDecodeError as exception:
        raise UploadError(f"diagnosis chat response is not JSON: {response_body[:200]}") from exception
    if not isinstance(result, dict) or not result.get("assistantMessage"):
        raise UploadError(f"diagnosis chat response did not include assistantMessage: {result}")
    return result


def create_as_draft(
    config: AgentConfig,
    gzip_path: Path,
    idempotency_key: str,
    macro: IssueDraftMacro,
    incident_window: IncidentWindow,
) -> dict:
    if not config.agent_token:
        raise UploadError("agentToken is missing. Run register first or wait for Goal 10 token storage.")
    if gzip_path.stat().st_size == 0:
        raise UploadError(f"gzip file is empty: {gzip_path}")

    fields = incident_window.metadata()
    fields["schemaVersion"] = str(config.schema_version)
    fields["symptom"] = macro.symptom
    fields["title"] = macro.title
    fields["detailDescription"] = macro.detail
    fields["supportRequestKind"] = macro.support_request_kind
    body, content_type = build_multipart(fields, "file", gzip_path)
    draft_url = config.api_base_url.rstrip("/") + AS_DRAFT_PATH
    request = urllib.request.Request(
        draft_url,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {config.agent_token}",
            "Idempotency-Key": idempotency_key,
            "Content-Type": content_type,
            "Content-Length": str(len(body)),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise UploadError(f"draft upload failed: HTTP {exception.code} {detail}") from exception
    except urllib.error.URLError as exception:
        raise UploadError(f"draft upload failed: {exception.reason}") from exception

    try:
        result = json.loads(payload)
    except json.JSONDecodeError as exception:
        raise UploadError(f"draft response is not JSON: {payload[:200]}") from exception

    if not isinstance(result, dict) or not result.get("draftId"):
        raise UploadError(f"draft response did not include draftId: {result}")
    return result


def collect_metrics(config: AgentConfig, iterations: int | None, interval_seconds: int) -> None:
    index = 0
    collector = HardwareMetricCollector()
    while iterations is None or index < iterations:
        path = append_metric(config, index, collector)
        print(f"appended system metric to {path}")
        index += 1
        if iterations is None or index < iterations:
            time.sleep(interval_seconds)


def hide_console_window() -> None:
    if os.name != "nt":
        return
    try:
        import ctypes

        window = ctypes.windll.kernel32.GetConsoleWindow()
        if window:
            ctypes.windll.user32.ShowWindow(window, 0)
    except Exception:
        return


def startup_dir() -> Path:
    appdata = os.environ.get("APPDATA")
    if not appdata:
        raise AgentError("APPDATA is not available; cannot register startup command.")
    return Path(appdata) / "Microsoft" / "Windows" / "Start Menu" / "Programs" / "Startup"


def installed_executable_path() -> Path:
    return app_data_dir() / f"{APP_NAME}.exe"


def files_have_same_content(left: Path, right: Path) -> bool:
    try:
        if left.stat().st_size != right.stat().st_size:
            return False
        with left.open("rb") as left_file, right.open("rb") as right_file:
            while True:
                left_chunk = left_file.read(1024 * 1024)
                right_chunk = right_file.read(1024 * 1024)
                if left_chunk != right_chunk:
                    return False
                if not left_chunk:
                    return True
    except OSError:
        return False


def executable_install_required(source: Path, target: Path) -> bool:
    if not target.exists():
        return True
    try:
        source_stat = source.stat()
        target_stat = target.stat()
        if source_stat.st_size != target_stat.st_size:
            return True
    except OSError:
        return True
    return not files_have_same_content(source, target)


def install_permission_error_message(target: Path, exception: OSError) -> str:
    return (
        f"PCAgent 시작프로그램 실행 파일을 교체할 수 없습니다: {target}\n"
        "이미 실행 중인 PCAgent가 있으면 트레이 아이콘에서 종료하거나 작업 관리자에서 "
        "PCAgent.exe를 종료한 뒤 다시 실행해 주세요. 재부팅 직후 다시 실행해도 해결할 수 있습니다.\n"
        f"원인: {exception}"
    )


def ensure_installed_executable() -> Path:
    if not getattr(sys, "frozen", False):
        return Path(sys.executable)

    source = Path(sys.executable).resolve()
    target = installed_executable_path()
    if source == target.resolve():
        return target

    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        if executable_install_required(source, target):
            shutil.copy2(source, target)
    except PermissionError as exception:
        raise AgentError(install_permission_error_message(target, exception)) from exception
    except OSError as exception:
        raise AgentError(f"Failed to install PCAgent executable for startup: {exception}") from exception
    return target


def executable_command() -> str:
    if getattr(sys, "frozen", False):
        return f'"{ensure_installed_executable()}" run-background'
    script = Path(__file__).resolve()
    return f'"{sys.executable}" "{script}" run-background'


def cleanup_legacy_startup_commands(directory: Path) -> list[Path]:
    removed: list[Path] = []
    current_name = f"{APP_NAME}.cmd".casefold()
    for name in LEGACY_DISPLAY_APP_NAMES:
        if not name:
            continue
        legacy_path = directory / f"{name}.cmd"
        if legacy_path.name.casefold() == current_name:
            continue
        try:
            if legacy_path.exists() and legacy_path.is_file():
                legacy_path.unlink()
                removed.append(legacy_path)
        except OSError:
            continue
    return removed


def register_startup() -> Path:
    directory = startup_dir()
    directory.mkdir(parents=True, exist_ok=True)
    cleanup_legacy_startup_commands(directory)
    path = directory / f"{APP_NAME}.cmd"
    path.write_text(f"@echo off\nstart \"\" {executable_command()}\n", encoding="utf-8")
    return path


def protocol_launch_command() -> str:
    if getattr(sys, "frozen", False):
        return f'"{ensure_installed_executable()}"'
    return f'"{sys.executable}" "{Path(__file__).resolve()}"'


def register_url_protocol() -> bool:
    if os.name != "nt":
        return False
    import winreg

    protocol_key = rf"Software\Classes\{PC_AGENT_URL_PROTOCOL}"
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, protocol_key) as key:
        winreg.SetValueEx(key, None, 0, winreg.REG_SZ, "URL:PCAgent Protocol")
        winreg.SetValueEx(key, "URL Protocol", 0, winreg.REG_SZ, "")
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, protocol_key + r"\DefaultIcon") as key:
        winreg.SetValueEx(key, None, 0, winreg.REG_SZ, str(ensure_installed_executable()))
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, protocol_key + r"\shell\open\command") as key:
        # URI의 userId/diagnosisId는 받지 않는다. 무인자 실행은 서버 인증 WebSocket 요청만 처리한다.
        winreg.SetValueEx(key, None, 0, winreg.REG_SZ, protocol_launch_command())
    return True


def pid_file() -> Path:
    return app_data_dir() / "agent.pid"


def running_agent_file() -> Path:
    return app_data_dir() / "agent-running.json"


def write_pid() -> None:
    path = pid_file()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(str(os.getpid()), encoding="utf-8")
    # 실행 중인 Agent의 버전을 남긴다. 나중에 실행된 프로세스가 단일 인스턴스 락에 막힐 때,
    # 자기가 새 버전인데 구버전이 계속 돌고 있는 상황을 사용자에게 알릴 수 있어야 한다.
    try:
        running_agent_file().write_text(
            json.dumps({"pid": os.getpid(), "agentVersion": DEFAULT_AGENT_VERSION}),
            encoding="utf-8",
        )
    except OSError:
        return


def running_agent_version() -> str | None:
    try:
        payload = json.loads(running_agent_file().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    version = payload.get("agentVersion") if isinstance(payload, dict) else None
    return version if isinstance(version, str) and version.strip() else None


def remove_pid() -> None:
    try:
        pid_file().unlink(missing_ok=True)
    except Exception:
        pass
    try:
        running_agent_file().unlink(missing_ok=True)
    except Exception:
        return


def app_asset_path(filename: str) -> Path:
    return runtime_asset_path(Path(APP_ASSET_DIR) / filename)


def render_screen_logo_image(size: tuple[int, int] = SCREEN_LOGO_DISPLAY_SIZE) -> Any:
    if Image is None:
        raise RuntimeError("Pillow is required for the PC Agent screen logo")
    width, height = (max(1, int(size[0])), max(1, int(size[1])))
    logo_path = app_asset_path(SCREEN_LOGO_PNG)
    with Image.open(logo_path) as source:
        logo = source.convert("RGBA")
    resample = getattr(getattr(Image, "Resampling", Image), "LANCZOS")
    logo.thumbnail((width, height), resample)
    surface = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    surface.alpha_composite(logo, ((width - logo.width) // 2, (height - logo.height) // 2))
    return surface


def apply_agent_window_icon(window: object) -> None:
    if tk is None:
        return
    ico_path = app_asset_path(AGENT_ICON_ICO)
    png_path = app_asset_path(AGENT_ICON_PNG)
    try:
        if sys.platform.startswith("win") and ico_path.exists():
            window.iconbitmap(str(ico_path))
    except Exception:
        pass
    try:
        if png_path.exists():
            photo = tk.PhotoImage(file=str(png_path))
            window.iconphoto(True, photo)
            setattr(window, "_specup_agent_icon", photo)
    except Exception:
        pass


def create_tray_image() -> object | None:
    if Image is None or ImageDraw is None:
        return None
    icon_path = app_asset_path(AGENT_ICON_PNG)
    if icon_path.exists():
        try:
            image = Image.open(icon_path).convert("RGBA")
            resample = getattr(getattr(Image, "Resampling", Image), "LANCZOS")
            return image.resize((64, 64), resample)
        except Exception:
            pass
    image = Image.new("RGB", (64, 64), "#1d4ed8")
    draw = ImageDraw.Draw(image)
    draw.ellipse((10, 10, 54, 54), fill="#ffffff")
    draw.rectangle((27, 18, 37, 46), fill="#1d4ed8")
    draw.rectangle((18, 27, 46, 37), fill="#1d4ed8")
    return image


def open_log_folder(config_path: Path) -> None:
    config = load_config(config_path)
    config.log_dir.mkdir(parents=True, exist_ok=True)
    os.startfile(str(config.log_dir.resolve()))


def read_log_tail(path: Path, limit: int = 100) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(row, dict):
                rows.append(row)
    return rows[-limit:]


def parse_iso_datetime(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=KST)
    return parsed.astimezone(KST)


def parse_log_timestamp(row: dict[str, Any]) -> datetime | None:
    for field in ("timestamp", "collectedAt", "receivedAt", "detectedAt"):
        parsed = parse_iso_datetime(row.get(field))
        if parsed is not None:
            return parsed
    payload = row.get("payload")
    if isinstance(payload, dict):
        for field in ("timestamp", "collectedAt", "detectedAt"):
            parsed = parse_iso_datetime(payload.get(field))
            if parsed is not None:
                return parsed
    return None


def log_payload(row: dict[str, Any]) -> dict[str, Any]:
    payload = row.get("payload")
    return payload if isinstance(payload, dict) else {}


def normalized_log_kind(row: dict[str, Any]) -> str:
    value = log_value(row, "kind", "eventType")
    return str(value).strip().upper() if value is not None else ""


def is_system_metric_row(row: dict[str, Any]) -> bool:
    return normalized_log_kind(row) == SYSTEM_METRIC_KIND


def log_value(row: dict[str, Any], *fields: str) -> Any:
    payload = log_payload(row)
    for field in fields:
        if field in row and row[field] is not None:
            return row[field]
        if field in payload and payload[field] is not None:
            return payload[field]
    return None


def format_log_timestamp(row: dict[str, Any]) -> str:
    timestamp = parse_log_timestamp(row)
    if timestamp is None:
        return "-"
    return timestamp.strftime("%Y-%m-%d %H:%M:%S")


def format_log_time(row: dict[str, Any]) -> str:
    timestamp = parse_log_timestamp(row)
    if timestamp is None:
        return "-"
    return timestamp.strftime("%H:%M:%S")


def read_log_hour(path: Path, date_text: str, hour: int, limit: int = 500) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    try:
        selected_date = datetime.strptime(date_text, "%Y-%m-%d").date()
    except ValueError:
        return []
    if hour < 0 or hour > 23:
        return []

    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(row, dict):
                continue
            timestamp = parse_log_timestamp(row)
            if timestamp and timestamp.date() == selected_date and timestamp.hour == hour:
                rows.append(row)
    rows.sort(key=lambda row: parse_log_timestamp(row) or datetime.min.replace(tzinfo=KST), reverse=True)
    return rows[:limit]


def read_log_day_latest(path: Path, date_text: str, limit: int = LOG_TABLE_LIMIT) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    try:
        selected_date = datetime.strptime(date_text, "%Y-%m-%d").date()
    except ValueError:
        return []

    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(row, dict):
                continue
            timestamp = parse_log_timestamp(row)
            if timestamp and timestamp.date() == selected_date:
                rows.append(row)
    rows.sort(key=lambda row: parse_log_timestamp(row) or datetime.min.replace(tzinfo=KST), reverse=True)
    return rows[:limit]


def read_log_rows_for_filter(
    path: Path,
    date_text: str,
    hour: int,
    user_touched_filter: bool,
    limit: int = LOG_TABLE_LIMIT,
) -> list[dict[str, Any]]:
    if user_touched_filter:
        return read_log_hour(path, date_text, hour, limit)
    return read_log_day_latest(path, date_text, limit)


def format_percent(value: Any) -> str:
    if isinstance(value, int | float):
        return f"{value:.1f}%"
    return "-"


def format_temperature(value: Any) -> str:
    if isinstance(value, int | float):
        return f"{value:.1f}C"
    return "-"


def format_optional_sensor_percent(row: dict[str, Any], *fields: str) -> str:
    value = log_value(row, *fields)
    if isinstance(value, int | float):
        return f"{value:.1f}%"
    if is_system_metric_row(row):
        return OPTIONAL_SENSOR_UNSUPPORTED_TEXT
    return "-"


def format_optional_sensor_temperature(row: dict[str, Any], *fields: str) -> str:
    value = log_value(row, *fields)
    if isinstance(value, int | float):
        return f"{value:.1f}C"
    if is_system_metric_row(row):
        return OPTIONAL_SENSOR_UNSUPPORTED_TEXT
    return "-"


def sanitize_display_text(value: Any, limit: int = 160) -> str:
    if value is None:
        return "-"
    if isinstance(value, bool):
        text = "true" if value else "false"
    elif isinstance(value, int | float):
        text = str(value)
    else:
        text = str(value)
    text = re.sub(r"(?i)(authorization|agenttoken|activationtoken|token|password)\s*[:=]\s*\S+", r"\1=[hidden]", text)
    text = re.sub(r"[A-Za-z]:\\[^\s\t\r\n]+", "[path hidden]", text)
    text = re.sub(r"(/[^\s\t\r\n]+){2,}", "[path hidden]", text)
    text = text.replace("\r", " ").replace("\n", " ").strip()
    if not text:
        return "-"
    if len(text) > limit:
        return text[: limit - 1] + "..."
    return text


def display_log_kind(row: dict[str, Any]) -> str:
    return sanitize_display_text(log_value(row, "kind", "eventType"), 60)


def display_log_message(row: dict[str, Any]) -> str:
    return sanitize_display_text(log_value(row, "message", "osErrorEvent", "status", "summary"), 180)


def display_log_event_summary(row: dict[str, Any]) -> str:
    kind = display_log_kind(row)
    labels = {
        "DEMO_METRIC": "상태 수집",
        "SYSTEM_METRIC": "상태 수집",
        "DISPLAY_DRIVER_WARNING": "드라이버 경고",
        "EVENT_LOG": "시스템 이벤트",
        "WINDOWS_EVENT": "시스템 이벤트",
        "AGENT_HEALTH": "Agent 상태",
    }
    if kind != "-":
        return labels.get(kind.upper(), sanitize_display_text(kind, 80))
    return sanitize_display_text(display_log_message(row), 80)


def display_core_metric_values(row: dict[str, Any]) -> tuple[str, str, str, str]:
    return (
        format_percent(log_value(row, "cpuUsage", "cpuUsagePercent")),
        format_percent(log_value(row, "memoryUsage", "ramUsage", "memoryUsedPercent")),
        format_percent(log_value(row, "diskBusyEstimatePercent")),
        format_percent(log_value(row, "gpuUsage", "gpuUsagePercent")),
    )


def display_log_table_values(row: dict[str, Any]) -> tuple[str, ...]:
    cpu, memory, disk, gpu = display_core_metric_values(row)
    return (
        format_log_time(row),
        display_log_kind(row),
        cpu,
        memory,
        disk,
        gpu,
        format_optional_sensor_percent(row, "vramUsage", "vramUsagePercent"),
        format_optional_sensor_temperature(row, "cpuTemp", "cpuTempCelsius", "cpuTemperatureCelsius"),
        format_optional_sensor_temperature(row, "gpuTemp", "gpuTempCelsius", "gpuTemperatureCelsius"),
        display_log_message(row),
    )


def display_log_summary_values(row: dict[str, Any]) -> tuple[str, ...]:
    cpu, memory, disk, gpu = display_core_metric_values(row)
    return (
        format_log_time(row),
        cpu,
        memory,
        disk,
        gpu,
        display_log_event_summary(row),
    )


def read_status_log_summary_rows(path: Path, limit: int = STATUS_LOG_SUMMARY_LIMIT) -> list[dict[str, Any]]:
    cutoff = datetime.now(KST) - timedelta(hours=1)
    rows: list[dict[str, Any]] = []
    for row in read_log_tail(path, LOG_TABLE_LIMIT):
        timestamp = parse_log_timestamp(row)
        if timestamp is None:
            continue
        if timestamp.tzinfo is None:
            timestamp = timestamp.replace(tzinfo=KST)
        if timestamp >= cutoff:
            rows.append(row)
    return rows[-limit:]


def signal_search_text(row: dict[str, Any]) -> str:
    fields = (
        "kind",
        "eventType",
        "message",
        "osErrorEvent",
        "symptomType",
        "supportDecision",
        "riskLevel",
        "reasonCode",
        "reasonCodes",
        "visitReason",
        "visitReasons",
        "blockingFactor",
        "blockingFactors",
        "status",
    )
    values: list[str] = []
    payload = log_payload(row)
    for field in fields:
        for container in (row, payload):
            value = container.get(field)
            if isinstance(value, str):
                values.append(value)
            elif isinstance(value, Sequence) and not isinstance(value, str):
                values.extend(str(item) for item in value if isinstance(item, str))
    return " ".join(values).lower()


def detect_signal(row: dict[str, Any]) -> dict[str, Any] | None:
    text = signal_search_text(row)
    if not text:
        return None
    for rule in FINAL_SIGNAL_RULES:
        if any(keyword in text for keyword in rule["keywords"]):
            timestamp = parse_log_timestamp(row)
            fallback = datetime.now(KST)
            return {
                "code": rule["code"],
                "title": rule["title"],
                "level": rule["level"],
                "timestamp": timestamp,
                "time": timestamp.strftime("%H:%M:%S") if timestamp else "-",
                "date": (timestamp or fallback).strftime("%Y-%m-%d"),
                "hour": (timestamp or fallback).hour,
            }
    return None


def detect_recent_signals(rows: Sequence[dict[str, Any]], limit: int = STATUS_HOME_SIGNAL_LIMIT) -> list[dict[str, Any]]:
    signals: list[dict[str, Any]] = []
    seen: set[str] = set()
    for row in reversed(list(rows)):
        signal = detect_signal(row)
        if signal is None or signal["code"] in seen:
            continue
        seen.add(signal["code"])
        signals.append(signal)
        if len(signals) >= limit:
            break
    return signals


def numeric_log_value(row: dict[str, Any], *fields: str) -> float | None:
    value = log_value(row, *fields)
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


SENSOR_COLLECTED = "collected"
SENSOR_UNSUPPORTED = "unsupported"
SENSOR_FAILED = "failed"
SENSOR_PERMISSION_REQUIRED = "permission_required"
SENSOR_PENDING = "pending"


@dataclass(frozen=True)
class SensorReading:
    value: float | str | None
    availability: str
    reason: str | None = None
    unit: str = ""
    status: str = UNAVAILABLE
    source: str = "unknown"
    sampled_at: str | None = None
    error_code: str | None = None


@dataclass(frozen=True)
class HardwareSensorSnapshot:
    cpu_usage: SensorReading
    cpu_temp: SensorReading
    gpu_usage: SensorReading
    gpu_temp: SensorReading
    gpu_fan: SensorReading
    memory_usage: SensorReading
    memory_used: SensorReading
    memory_total: SensorReading
    disk_activity: SensorReading
    disk_usage: SensorReading
    disk_health: SensorReading
    cpu_history: tuple[float, ...]
    gpu_history: tuple[float, ...]
    memory_history: tuple[float, ...]
    disk_history: tuple[float, ...]


@dataclass(frozen=True)
class SymptomScreenInput:
    snapshot: HardwareSensorSnapshot
    symptom: str
    symptom_type: str | None
    requested_checks: tuple[str, ...] = ()


@dataclass(frozen=True)
class HardwareWidgetState:
    key: str
    title: str
    primary: str
    details: tuple[str, ...]
    status: str
    tone: str
    history: tuple[float, ...]
    highlighted: bool = False


@dataclass(frozen=True)
class SymptomScreenState:
    widgets: tuple[HardwareWidgetState, ...]
    symptom: str
    suspected_components: tuple[str, ...]


def metric_metadata(row: dict[str, Any], field: str) -> dict[str, Any]:
    payload = log_payload(row)
    value = row.get(field)
    if not isinstance(value, dict):
        value = payload.get(field)
    return value if isinstance(value, dict) else {}


def sensor_reading(row: dict[str, Any], *fields: str, unit: str = "") -> SensorReading:
    value = log_value(row, *fields)
    if value is not None and (not isinstance(value, str) or value.strip()):
        normalized = rounded_or_none(value)
        return SensorReading(normalized if normalized is not None else str(value).strip(), SENSOR_COLLECTED, unit=unit)

    statuses = metric_metadata(row, "sensorStatus")
    reasons = metric_metadata(row, "unavailableReason")
    reason = next((str(reasons[field]) for field in fields if reasons.get(field)), None)
    explicit_status = next((str(statuses[field]) for field in fields if statuses.get(field)), None)
    availability = SENSOR_FAILED if explicit_status == SENSOR_FAILED or sensor_reason_is_failure(reason) else SENSOR_UNSUPPORTED
    return SensorReading(None, availability, reason, unit)


def sensor_history(rows: Sequence[dict[str, Any]], *fields: str) -> tuple[float, ...]:
    history: list[float] = []
    for row in rows[-16:]:
        value = numeric_log_value(row, *fields)
        if value is not None:
            history.append(max(0.0, min(100.0, value)))
    return tuple(history)


def hardware_sensor_snapshot(current: dict[str, Any], rows: Sequence[dict[str, Any]]) -> HardwareSensorSnapshot:
    fan_rpm = sensor_reading(current, *GPU_FAN_RPM_FIELDS, unit="RPM")
    fan_percent = sensor_reading(current, *GPU_FAN_PERCENT_FIELDS, unit="%")
    gpu_fan = fan_rpm if fan_rpm.availability == SENSOR_COLLECTED else fan_percent
    if gpu_fan.availability != SENSOR_COLLECTED and fan_rpm.availability == SENSOR_FAILED:
        gpu_fan = fan_rpm

    disk_activity = sensor_reading(current, *DISK_ACTIVE_FIELDS)
    disk_history = (
        sensor_history(rows, *DISK_ACTIVE_FIELDS)
        if disk_activity.availability == SENSOR_COLLECTED
        else ()
    )
    return HardwareSensorSnapshot(
        cpu_usage=sensor_reading(current, *CPU_USAGE_FIELDS),
        cpu_temp=sensor_reading(current, *CPU_TEMP_FIELDS),
        gpu_usage=sensor_reading(current, *GPU_USAGE_FIELDS),
        gpu_temp=sensor_reading(current, *GPU_TEMP_FIELDS),
        gpu_fan=gpu_fan,
        memory_usage=sensor_reading(current, *MEMORY_USAGE_FIELDS),
        memory_used=sensor_reading(current, *MEMORY_USED_FIELDS),
        memory_total=sensor_reading(current, *MEMORY_TOTAL_FIELDS),
        disk_activity=disk_activity,
        disk_usage=sensor_reading(current, *DISK_USAGE_FIELDS),
        disk_health=sensor_reading(current, *DISK_HEALTH_FIELDS),
        cpu_history=sensor_history(rows, *CPU_USAGE_FIELDS),
        gpu_history=sensor_history(rows, *GPU_USAGE_FIELDS),
        memory_history=sensor_history(rows, *MEMORY_USAGE_FIELDS),
        disk_history=disk_history,
    )


def metric_sensor_reading(reading: MetricReading | None) -> SensorReading:
    if reading is None:
        return SensorReading(None, SENSOR_PENDING, "initial sensor collection pending")
    availability = {
        AVAILABLE: SENSOR_COLLECTED,
        UNSUPPORTED: SENSOR_UNSUPPORTED,
        PERMISSION_REQUIRED: SENSOR_PERMISSION_REQUIRED,
        FAILED: SENSOR_FAILED,
    }.get(reading.availability, SENSOR_FAILED)
    return SensorReading(
        reading.value,
        availability,
        reading.failure_reason,
        reading.unit,
        reading.status,
        reading.source,
        reading.sampled_at,
        reading.error_code,
    )


def metrics_snapshot_screen_input(
    metrics: MetricsSnapshot,
    symptom: str | None,
    requested_checks: Sequence[str] = (),
) -> SymptomScreenInput:
    rpm = metric_sensor_reading(metrics.latest("gpu", "fan_rpm"))
    percent = metric_sensor_reading(metrics.latest("gpu", "fan_percent"))
    gpu_fan = rpm
    if rpm.availability == SENSOR_UNSUPPORTED and percent.availability != SENSOR_UNSUPPORTED:
        gpu_fan = percent
    disk_activity = metric_sensor_reading(metrics.latest("disk", "activity"))
    return SymptomScreenInput(
        snapshot=HardwareSensorSnapshot(
            cpu_usage=metric_sensor_reading(metrics.latest("cpu", "usage")),
            cpu_temp=metric_sensor_reading(metrics.latest("cpu", "temperature")),
            gpu_usage=metric_sensor_reading(metrics.latest("gpu", "usage")),
            gpu_temp=metric_sensor_reading(metrics.latest("gpu", "temperature")),
            gpu_fan=gpu_fan,
            memory_usage=metric_sensor_reading(metrics.latest("ram", "usage")),
            memory_used=metric_sensor_reading(metrics.latest("ram", "used_bytes")),
            memory_total=metric_sensor_reading(metrics.latest("ram", "total_bytes")),
            disk_activity=disk_activity,
            disk_usage=metric_sensor_reading(metrics.latest("disk", "usage")),
            disk_health=metric_sensor_reading(metrics.latest("disk", "smart")),
            cpu_history=metrics.history("cpu", "usage"),
            gpu_history=metrics.history("gpu", "usage"),
            memory_history=metrics.history("ram", "usage"),
            disk_history=(
                metrics.history("disk", "activity")
                if disk_activity.availability == SENSOR_COLLECTED
                else ()
            ),
        ),
        symptom=(symptom or "").strip() or "웹에서 전달받은 증상 정보가 없습니다.",
        symptom_type=None,
        requested_checks=tuple(requested_checks),
    )


def failed_system_metric_row(reason: str) -> dict[str, Any]:
    fields = (
        *CPU_USAGE_FIELDS,
        *CPU_TEMP_FIELDS,
        *CPU_CLOCK_FIELDS,
        *GPU_USAGE_FIELDS,
        *GPU_TEMP_FIELDS,
        *GPU_FAN_RPM_FIELDS,
        *GPU_FAN_PERCENT_FIELDS,
        *MEMORY_USAGE_FIELDS,
        *MEMORY_USED_FIELDS,
        *MEMORY_TOTAL_FIELDS,
        *DISK_ACTIVE_FIELDS,
        *DISK_USAGE_FIELDS,
        *DISK_USED_BYTES_FIELDS,
        *DISK_TOTAL_BYTES_FIELDS,
        *DISK_HEALTH_FIELDS,
    )
    payload: dict[str, Any] = {field: None for field in fields}
    payload["unavailableReason"] = {field: reason for field in fields}
    payload["sensorStatus"] = {field: SENSOR_FAILED for field in fields}
    return build_metric_snapshot(datetime.now(KST), 0, SYSTEM_METRIC_KIND, payload)


class SystemSensorProvider:
    def __init__(self, collector: HardwareMetricCollector | None = None) -> None:
        self.collector = collector or HardwareMetricCollector()

    def load(self, config: AgentConfig) -> SymptomScreenInput:
        rows = [row for row in read_log_tail(log_file(config), 16) if is_system_metric_row(row)]
        if rows:
            current = rows[-1]
            snapshot_rows = rows
        else:
            try:
                current = self.collector.collect(datetime.now(KST), 0)
            except Exception:
                current = failed_system_metric_row("system sensor collection failed")
            snapshot_rows = [current]
        return SymptomScreenInput(
            snapshot=hardware_sensor_snapshot(current, snapshot_rows),
            symptom=config.symptom or "웹에서 전달받은 증상이 없습니다.",
            symptom_type=config.symptom_type,
        )


class DemoSensorProvider:
    DEMO_SYMPTOM = "게임을 실행하면 처음에는 괜찮은데, 조금 지나면 프레임이 심하게 끊깁니다."

    def load(self, config: AgentConfig) -> SymptomScreenInput:
        provider = InitialDemoSensorProvider()
        normalizer = MetricsNormalizer()
        store = MetricsStore()
        store.begin("manual-demo", "DEMO")
        for index in range(3):
            store.append("manual-demo", normalizer.normalize(provider.collect_sample(index)))
        return metrics_snapshot_screen_input(
            store.snapshot,
            config.symptom or self.DEMO_SYMPTOM,
            ("cpu", "gpu", "cooling"),
        )


def reading_number(reading: SensorReading) -> float | None:
    return float(reading.value) if is_number(reading.value) else None


def reading_text(reading: SensorReading, unit: str = "") -> str:
    if reading.availability == SENSOR_FAILED:
        return "확인 실패"
    if reading.availability == SENSOR_PERMISSION_REQUIRED:
        return "권한 필요"
    if reading.availability == SENSOR_PENDING:
        return "수집 중"
    if reading.availability == SENSOR_UNSUPPORTED:
        return "센서 미지원"
    if reading.availability != SENSOR_COLLECTED:
        return "측정 불가"
    if isinstance(reading.value, str):
        return reading.value
    value = reading_number(reading)
    if value is None:
        return "측정 불가"
    formatted = f"{value:.0f}" if value.is_integer() else f"{value:.1f}"
    return f"{formatted}{unit}"


def memory_size_text(reading: SensorReading) -> str:
    value = reading_number(reading)
    if reading.availability != SENSOR_COLLECTED or value is None:
        return reading_text(reading)
    return f"{value / 1024**3:.1f}GB"


def fan_text(reading: SensorReading) -> str:
    value = reading_number(reading)
    if reading.availability != SENSOR_COLLECTED or value is None:
        return reading_text(reading)
    unit = reading.unit or "%"
    if value == 0:
        return f"0 {unit} (정지)"
    return f"정상 회전 · {value:.0f}{unit}"


def component_status(
    usage: SensorReading,
    optional: Sequence[SensorReading] = (),
    temperature: SensorReading | None = None,
    fan: SensorReading | None = None,
    temperature_warning: float = 1000.0,
    temperature_danger: float = 1000.0,
) -> tuple[str, str]:
    readings = (usage, *optional)
    centralized = [reading.status for reading in readings if reading.status not in {UNAVAILABLE, ERROR}]
    if usage.availability == SENSOR_FAILED or any(item.availability == SENSOR_FAILED for item in optional):
        return "확인 실패", "danger"
    if usage.availability == SENSOR_PERMISSION_REQUIRED:
        return "권한 필요", "default"
    if usage.availability == SENSOR_PENDING:
        return "수집 중", "default"
    if usage.availability != SENSOR_COLLECTED:
        return "측정 불가", "default"
    if ABNORMAL in centralized:
        return "이상", "danger"
    if WARNING in centralized:
        return "주의", "warning"
    if MODERATE in centralized:
        return "보통", "default"
    if NORMAL in centralized:
        return "정상", "default"
    usage_value = reading_number(usage) or 0.0
    temperature_value = reading_number(temperature) if temperature is not None else None
    fan_value = reading_number(fan) if fan is not None else None
    if usage_value >= PC_AGENT_USAGE_DANGER_THRESHOLD or (
        temperature_value is not None and temperature_value >= temperature_danger
    ):
        return "이상", "danger"
    if usage_value >= PC_AGENT_USAGE_WARNING_THRESHOLD or (
        temperature_value is not None and temperature_value >= temperature_warning
    ):
        return "주의", "warning"
    if fan_value == 0 or any(item.availability != SENSOR_COLLECTED for item in optional):
        return "보통", "default"
    if usage_value >= 50.0:
        return "보통", "default"
    return "정상", "default"


def suspected_hardware_components(
    symptom: str,
    symptom_type: str | None,
    requested_checks: Sequence[str] = (),
) -> tuple[str, ...]:
    normalized = symptom.casefold()
    matches: set[str] = set()
    keyword_groups = {
        "cpu": ("cpu", "프로세서", "과열", "발열"),
        "gpu": ("gpu", "그래픽", "프레임", "게임", "화면", "냉각", "팬", "온도"),
        "ram": ("ram", "메모리"),
        "disk": ("disk", "디스크", "ssd", "저장", "부팅"),
    }
    for component, keywords in keyword_groups.items():
        if any(keyword in normalized for keyword in keywords):
            matches.add(component)
    type_components = {
        "REMOTE_DRIVER_OS": ("gpu",),
        "REMOTE_STORAGE_MEMORY": ("ram", "disk"),
        "VISIT_FAN_THERMAL": ("cpu", "gpu"),
        "VISIT_DISK_FAILURE": ("disk",),
    }
    matches.update(type_components.get(symptom_type or "", ()))
    requested_components = {
        "cpu": ("cpu",),
        "gpu": ("gpu",),
        "memory": ("ram",),
        "disk": ("disk",),
        "cooling": ("cpu", "gpu"),
    }
    for check in requested_checks:
        matches.update(requested_components.get(str(check).casefold(), ()))
    return tuple(component for component in ("cpu", "gpu", "ram", "disk") if component in matches)


def build_symptom_screen_state(screen_input: SymptomScreenInput) -> SymptomScreenState:
    snapshot = screen_input.snapshot
    symptom = screen_input.symptom.strip() or "웹에서 전달받은 증상 정보가 없습니다."
    suspected = suspected_hardware_components(symptom, screen_input.symptom_type, screen_input.requested_checks)

    cpu_status, cpu_tone = component_status(
        snapshot.cpu_usage,
        (snapshot.cpu_temp,),
        temperature=snapshot.cpu_temp,
        temperature_warning=PC_AGENT_CPU_TEMP_WARNING_THRESHOLD,
        temperature_danger=PC_AGENT_CPU_TEMP_DANGER_THRESHOLD,
    )
    gpu_status, gpu_tone = component_status(
        snapshot.gpu_usage,
        (snapshot.gpu_temp, snapshot.gpu_fan),
        temperature=snapshot.gpu_temp,
        fan=snapshot.gpu_fan,
        temperature_warning=PC_AGENT_GPU_TEMP_WARNING_THRESHOLD,
        temperature_danger=PC_AGENT_GPU_TEMP_DANGER_THRESHOLD,
    )
    memory_status, memory_tone = component_status(
        snapshot.memory_usage,
        (snapshot.memory_used, snapshot.memory_total),
    )
    disk_storage_available = snapshot.disk_usage.availability == SENSOR_COLLECTED
    disk_primary = snapshot.disk_usage if disk_storage_available else snapshot.disk_activity
    disk_label = "저장 공간" if disk_storage_available else "활성 시간"
    disk_status, disk_tone = component_status(disk_primary, (snapshot.disk_health,))

    memory_capacity = f"{memory_size_text(snapshot.memory_used)} / {memory_size_text(snapshot.memory_total)}"
    disk_details = (
        (
            f"SMART {reading_text(snapshot.disk_health)}",
            f"활성 시간 {reading_text(snapshot.disk_activity, '%')}",
        )
        if disk_storage_available
        else (
            f"SMART {reading_text(snapshot.disk_health)}",
            f"저장 공간 {reading_text(snapshot.disk_usage, '%')}",
        )
    )
    widgets = (
        HardwareWidgetState(
            "cpu", "CPU", f"사용률 {reading_text(snapshot.cpu_usage, '%')}",
            (f"온도 {reading_text(snapshot.cpu_temp, '°C')}",), cpu_status, cpu_tone,
            snapshot.cpu_history, "cpu" in suspected,
        ),
        HardwareWidgetState(
            "gpu", "GPU", f"사용률 {reading_text(snapshot.gpu_usage, '%')}",
            (f"온도 {reading_text(snapshot.gpu_temp, '°C')}", f"팬 {fan_text(snapshot.gpu_fan)}"), gpu_status, gpu_tone,
            snapshot.gpu_history, "gpu" in suspected,
        ),
        HardwareWidgetState(
            "ram", "RAM", f"사용률 {reading_text(snapshot.memory_usage, '%')}",
            (memory_capacity,), memory_status, memory_tone,
            snapshot.memory_history, "ram" in suspected,
        ),
        HardwareWidgetState(
            "disk", "디스크", f"{disk_label} {reading_text(disk_primary, '%')}",
            disk_details, disk_status, disk_tone,
            snapshot.disk_history, "disk" in suspected,
        ),
    )
    return SymptomScreenState(widgets, symptom, suspected)


def home_usage_detection(rows: Sequence[dict[str, Any]]) -> dict[str, Any] | None:
    now = datetime.now(KST)
    cutoff = now - timedelta(minutes=HOME_USAGE_LOOKBACK_MINUTES)
    for row in reversed(list(rows)):
        timestamp = parse_log_timestamp(row)
        if timestamp is None:
            continue
        observed_at = timestamp.astimezone(KST) if timestamp.tzinfo else timestamp.replace(tzinfo=KST)
        if observed_at < cutoff:
            continue
        memory = numeric_log_value(row, "memoryUsage", "ramUsage", "memoryUsedPercent")
        if memory is not None and memory >= HOME_MEMORY_WARNING_THRESHOLD:
            return {
                "code": "REMOTE_STORAGE_MEMORY",
                "title": "메모리 사용량 높음",
                "level": "주의",
                "timestamp": observed_at,
                "time": observed_at.strftime("%H:%M:%S"),
                "date": observed_at.strftime("%Y-%m-%d"),
                "hour": observed_at.hour,
                "detail": "최근 5분간 메모리 점유율이 높게 유지되었습니다.",
            }
    return None


def status_home_detection(rows: Sequence[dict[str, Any]], signals: Sequence[dict[str, Any]]) -> dict[str, Any] | None:
    if signals:
        primary = dict(signals[0])
        primary["detail"] = event_panel_signal_summary(primary)
        return primary
    return home_usage_detection(rows)


def pc_status_card(
    rows: Sequence[dict[str, Any]],
    signals: Sequence[dict[str, Any]],
    detection: dict[str, Any] | None,
) -> dict[str, str]:
    if any(str(signal.get("level")) == "위험" for signal in signals):
        return {"value": "점검 필요", "detail": "진단 또는 AS 접수가 필요합니다.", "tone": "danger"}
    if detection is not None:
        title = sanitize_display_text(detection.get("title"), 32)
        detail = f"이상 징후: {title}" if title else "일부 항목에서 이상 징후가 감지되었습니다."
        return {"value": "주의", "detail": detail, "tone": "warning"}
    if not rows:
        return {"value": "정상", "detail": "최근 문제 신호 없음", "tone": "ok"}
    return {"value": "정상", "detail": "최근 문제 신호 없음", "tone": "ok"}


def event_panel_signals(signals: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    seen: set[str] = set()
    for signal in signals:
        code = str(signal.get("code", ""))
        if code not in EVENT_PANEL_SIGNAL_CODES or code in seen:
            continue
        seen.add(code)
        selected.append(signal)
        if len(selected) >= EVENT_PANEL_SIGNAL_LIMIT:
            break
    return selected


def event_panel_signal_summary(signal: dict[str, Any]) -> str:
    code = str(signal.get("code", ""))
    if code in EVENT_PANEL_SUMMARIES:
        return EVENT_PANEL_SUMMARIES[code]
    return sanitize_display_text(signal.get("title"), 90)


def event_panel_detected_at(signal: dict[str, Any]) -> datetime:
    timestamp = signal.get("timestamp")
    if isinstance(timestamp, datetime):
        return timestamp.astimezone(KST) if timestamp.tzinfo else timestamp.replace(tzinfo=KST)
    return datetime.now(KST)


def event_panel_latest_time(signals: Sequence[dict[str, Any]]) -> str:
    timestamps = [event_panel_detected_at(signal) for signal in signals]
    if not timestamps:
        return "-"
    return max(timestamps).strftime("%Y-%m-%d %H:%M:%S")


def event_panel_signature(signals: Sequence[dict[str, Any]]) -> str | None:
    selected = event_panel_signals(signals)
    if not selected:
        return None
    parts = [
        f"{signal.get('code')}:{signal.get('date')}:{signal.get('hour')}"
        for signal in selected
    ]
    return "|".join(parts)


def event_panel_model(signals: Sequence[dict[str, Any]]) -> dict[str, Any] | None:
    selected = event_panel_signals(signals)
    if not selected:
        return None
    primary = selected[0]
    detected_at = event_panel_detected_at(primary)
    symptom_type = str(primary.get("code", "REMOTE_DRIVER_OS"))
    window = default_incident_window(symptom_type, detected_at=detected_at, trigger_type="SYSTEM_DETECTED")
    return {
        "latestTime": event_panel_latest_time(selected),
        "summaries": [event_panel_signal_summary(signal) for signal in selected],
        "primarySignal": primary,
        "detectedTime": detected_at.strftime("%Y-%m-%d %H:%M"),
        "signalTitle": event_panel_signal_summary(primary),
        "windowText": (
            f"{window.started_at.strftime('%H:%M')} ~ {window.ended_at.strftime('%H:%M')} "
            f"({window.range_minutes()}분)"
        ),
    }


def event_panel_symptom(signals: Sequence[dict[str, Any]]) -> str:
    summaries = [event_panel_signal_summary(signal) for signal in event_panel_signals(signals)]
    if not summaries:
        return "PCAgent가 확인이 필요한 이벤트를 감지했습니다."
    return " / ".join(summaries[:EVENT_PANEL_SIGNAL_LIMIT])


AGENT_REGISTRATION_ERROR_MARKERS = (
    "agenttoken is missing",
    "agent token is required",
    "agent activation token is invalid",
    "activation token is invalid",
    "device is inactive",
    "http 401",
    "http 403",
)

AGENT_REGISTRATION_EVENT_HELP = (
    "Agent 등록이 필요합니다. 웹 지원 페이지에서 PCAgent를 다시 다운로드해 실행해 주세요."
)
AGENT_REGISTRATION_PREVIEW_HELP = (
    "Agent 등록이 필요합니다. 웹 지원 페이지에서 등록 토큰이 포함된 PCAgent를 다시 다운로드해 실행해 주세요."
)
AGENT_REGISTRATION_COMPACT_HELP = (
    "진단 실패: 웹에서 PCAgent.exe와 pcagent-activation.json을 다시 받아 실행해 주세요."
)


def is_agent_registration_error_text(text: str) -> bool:
    return any(marker in text for marker in AGENT_REGISTRATION_ERROR_MARKERS)


def event_panel_failure_message(exception: Exception) -> str:
    text = str(exception).lower()
    if is_agent_registration_error_text(text):
        return AGENT_REGISTRATION_EVENT_HELP
    if "consent" in text or "consentaccepted" in text:
        return "서버 업로드 동의가 필요해 전송할 수 없습니다."
    if "no log rows" in text or "log file does not exist" in text:
        return "전송할 이벤트 로그가 아직 없습니다. 잠시 후 다시 시도해 주세요."
    if "timed out" in text or "connection" in text or "refused" in text or "unreachable" in text:
        return "서버 연결을 확인할 수 없어 전송하지 못했습니다."
    return "전송하지 못했습니다. 등록, 동의, 서버 연결 상태를 확인해 주세요."


def as_rag_preview_failure_message(exception: Exception) -> str:
    text = str(exception).lower()
    if is_agent_registration_error_text(text):
        return AGENT_REGISTRATION_PREVIEW_HELP
    if "consent" in text or "consentaccepted" in text:
        return "서버 업로드 동의가 필요해 AI 추천을 받을 수 없습니다."
    if "no log rows" in text or "log file does not exist" in text:
        return "분석할 로그가 아직 없습니다. PCAgent가 로그를 수집한 뒤 다시 시도해 주세요."
    if (
        "timed out" in text
        or "timeout" in text
        or "connection" in text
        or "refused" in text
        or "unreachable" in text
        or "연결" in text
    ):
        return "서버 연결을 확인할 수 없어 AI 추천을 받지 못했습니다."
    return "AI 추천을 받지 못했습니다. 등록, 동의, 서버 연결 상태를 확인해 주세요."


def compact_as_rag_preview_failure_message(exception: Exception) -> str:
    text = str(exception).lower()
    if is_agent_registration_error_text(text):
        return AGENT_REGISTRATION_COMPACT_HELP
    if "consent" in text or "consentaccepted" in text:
        return "진단 실패: 서버 업로드 동의가 필요합니다."
    if "no log rows" in text or "log file does not exist" in text:
        return "진단 실패: 분석할 로그가 아직 없습니다."
    if (
        "timed out" in text
        or "timeout" in text
        or "connection" in text
        or "refused" in text
        or "unreachable" in text
        or "연결" in text
    ):
        return "진단 실패: 서버 연결을 확인해 주세요."
    return "진단 실패. 등록, 동의, 서버 상태를 확인해 주세요."


def latest_upload_status(rows: Sequence[dict[str, Any]]) -> str:
    for row in reversed(list(rows)):
        text = signal_search_text(row)
        if "upload failed" in text or "upload_failed" in text:
            return f"실패 {format_log_time(row)}"
        if "upload succeeded" in text or "upload success" in text or "upload_succeeded" in text:
            return f"성공 {format_log_time(row)}"
    return "기록 없음"


def latest_upload_card(rows: Sequence[dict[str, Any]]) -> dict[str, str]:
    for row in reversed(list(rows)):
        text = signal_search_text(row)
        if "upload failed" in text or "upload_failed" in text:
            return {"value": format_log_time(row), "detail": "업로드 상태: 실패", "tone": "danger"}
        if "upload succeeded" in text or "upload success" in text or "upload_succeeded" in text:
            return {"value": format_log_time(row), "detail": "업로드 상태: 성공", "tone": "ok"}
    return {"value": "기록 없음", "detail": "업로드 상태: 대기", "tone": "muted"}


def latest_server_status(rows: Sequence[dict[str, Any]]) -> str:
    for row in reversed(list(rows)):
        text = signal_search_text(row)
        if "heartbeat failed" in text or "heartbeat missing" in text:
            return f"실패 {format_log_time(row)}"
        if "heartbeat succeeded" in text or "heartbeat success" in text:
            return f"연결 기록 {format_log_time(row)}"
    return "확인 전"


def latest_server_card(rows: Sequence[dict[str, Any]]) -> dict[str, str]:
    for row in reversed(list(rows)):
        text = signal_search_text(row)
        if "heartbeat failed" in text or "heartbeat missing" in text:
            return {"value": "● 연결 실패", "detail": f"마지막 확인 : {format_log_time(row)}", "tone": "danger"}
        if "heartbeat succeeded" in text or "heartbeat success" in text:
            return {"value": "● 연결됨", "detail": f"마지막 확인 : {format_log_time(row)}", "tone": "ok"}
    return {"value": "● 확인 전", "detail": "heartbeat 대기", "tone": "muted"}


def startup_card_status() -> dict[str, str]:
    try:
        path = startup_dir() / f"{APP_NAME}.cmd"
    except Exception:
        return {"value": "확인 불가", "detail": "시작프로그램 경로 확인 실패", "tone": "warning"}
    if path.exists():
        return {"value": "사용 중", "detail": "시스템 시작 시 자동 실행", "tone": "ok"}
    return {"value": "미등록", "detail": "시작프로그램 미등록", "tone": "warning"}


def status_home_model(config: AgentConfig, path: Path) -> dict[str, Any]:
    rows = read_log_tail(path, LOG_TABLE_LIMIT)
    signals = detect_recent_signals(rows)
    home_detection = status_home_detection(rows, signals)
    pc_card = pc_status_card(rows, signals, home_detection)
    upload_card = latest_upload_card(rows)
    startup_card = startup_card_status()
    return {
        "agentStatus": "정상 실행 중" if config.agent_token else "등록 필요",
        "pcStatusCard": pc_card,
        "lastUpload": latest_upload_status(rows),
        "uploadCard": upload_card,
        "startupCard": startup_card,
        "version": config.agent_version,
        "policyVersion": config.policy_version,
        "versionCard": {"value": config.agent_version, "detail": "최신 버전", "tone": "ok"},
        "signals": signals,
        "homeDetection": home_detection,
    }


def diagnosis_tone_label(tone: str) -> str:
    if tone == "danger":
        return "위험"
    if tone == "warning":
        return "주의"
    if tone == "muted":
        return "확인 전"
    return "정상"


def diagnosis_metric_status(value: float | None) -> tuple[str, str]:
    if value is None:
        return "확인 불가", "muted"
    if value >= DIAGNOSIS_DETAIL_DANGER_THRESHOLD:
        return "위험", "danger"
    if value >= DIAGNOSIS_DETAIL_WARNING_THRESHOLD:
        return "주의", "warning"
    return "정상", "ok"


def diagnosis_metric_description(name: str, value: float | None, status: str) -> str:
    if value is None:
        return f"최근 로그에 {name} 값이 없습니다."
    if status == "정상":
        if name == "디스크":
            return "디스크 busy는 안정적입니다."
        return f"현재 {name} 사용률은 안정적입니다."
    if name == "메모리":
        return "메모리 사용량이 높습니다."
    if name == "디스크":
        return "디스크 busy가 높습니다."
    return f"{name} 사용률이 높습니다."


def latest_diagnosis_metric_row(rows: Sequence[dict[str, Any]]) -> dict[str, Any] | None:
    metric_fields = (
        ("cpuUsage", "cpuUsagePercent"),
        ("memoryUsage", "ramUsage", "memoryUsedPercent"),
        ("diskBusyEstimatePercent",),
        ("gpuUsage", "gpuUsagePercent"),
    )
    for row in reversed(list(rows)):
        if any(numeric_log_value(row, *fields) is not None for fields in metric_fields):
            return row
    return None


def diagnosis_metric_card(
    row: dict[str, Any] | None,
    name: str,
    fields: Sequence[str],
    current_label: str,
    threshold: str,
) -> dict[str, str]:
    value = numeric_log_value(row, *fields) if row is not None else None
    status, tone = diagnosis_metric_status(value)
    return {
        "name": name,
        "status": status,
        "tone": tone,
        "currentLabel": current_label,
        "currentValue": format_percent(value),
        "threshold": threshold,
        "description": diagnosis_metric_description(name, value, status),
    }


def diagnosis_detail_event_status(row: dict[str, Any]) -> tuple[str, str]:
    signal = detect_signal(row)
    if signal is not None:
        if str(signal.get("level")) == "위험":
            return "위험", "danger"
        return "주의", "warning"
    values = [
        numeric_log_value(row, "cpuUsage", "cpuUsagePercent"),
        numeric_log_value(row, "memoryUsage", "ramUsage", "memoryUsedPercent"),
        numeric_log_value(row, "diskBusyEstimatePercent"),
        numeric_log_value(row, "gpuUsage", "gpuUsagePercent"),
    ]
    numeric_values = [value for value in values if value is not None]
    if any(value >= DIAGNOSIS_DETAIL_DANGER_THRESHOLD for value in numeric_values):
        return "위험", "danger"
    if any(value >= DIAGNOSIS_DETAIL_WARNING_THRESHOLD for value in numeric_values):
        return "주의", "warning"
    return "정상", "ok"


def diagnosis_detail_event_rows(rows: Sequence[dict[str, Any]]) -> list[dict[str, str]]:
    events: list[dict[str, str]] = []
    for row in rows[-DIAGNOSIS_DETAIL_EVENT_LIMIT:]:
        status, tone = diagnosis_detail_event_status(row)
        content = display_log_message(row)
        if content == "-":
            content = display_log_event_summary(row)
        events.append(
            {
                "time": format_log_time(row),
                "type": display_log_event_summary(row),
                "content": content,
                "status": status,
                "tone": tone,
            }
        )
    return events


def diagnosis_detail_model(config: AgentConfig, path: Path) -> dict[str, Any]:
    rows = read_log_tail(path, LOG_TABLE_LIMIT)
    home_model = status_home_model(config, path)
    latest_row = rows[-1] if rows else None
    metric_row = latest_diagnosis_metric_row(rows)
    home_detection = home_model.get("homeDetection")
    has_result = latest_row is not None
    pc_card = home_model["pcStatusCard"]
    tone = "muted" if not has_result else str(pc_card.get("tone", "muted"))

    if not has_result:
        summary = "아직 진단 결과가 없습니다."
    elif isinstance(home_detection, dict):
        summary = sanitize_display_text(home_detection.get("detail") or home_detection.get("title"), 120)
    else:
        summary = "최근 로그에서 특이 신호가 없습니다."

    threshold = (
        f"{DIAGNOSIS_DETAIL_WARNING_THRESHOLD:.0f}% 이상 시 주의 / "
        f"{DIAGNOSIS_DETAIL_DANGER_THRESHOLD:.0f}% 이상 시 위험"
    )
    disk_threshold = (
        f"busy {DIAGNOSIS_DETAIL_WARNING_THRESHOLD:.0f}% 이상 시 주의 / "
        f"{DIAGNOSIS_DETAIL_DANGER_THRESHOLD:.0f}% 이상 시 위험"
    )
    summary_rows = read_status_log_summary_rows(path, DIAGNOSIS_DETAIL_EVENT_LIMIT)
    return {
        "hasResult": has_result,
        "title": "진단 결과 상세",
        "status": diagnosis_tone_label(tone),
        "tone": tone,
        "lastDiagnosticTime": format_log_timestamp(latest_row) if latest_row else "-",
        "summary": summary,
        "emptyTitle": "아직 진단 결과가 없습니다.",
        "emptyMessage": "PCAgent가 로그를 수집하면 상세 결과를 확인할 수 있습니다.",
        "metrics": [
            diagnosis_metric_card(metric_row, "CPU", ("cpuUsage", "cpuUsagePercent"), "현재 사용률", threshold),
            diagnosis_metric_card(
                metric_row,
                "메모리",
                ("memoryUsage", "ramUsage", "memoryUsedPercent"),
                "현재 사용률",
                threshold,
            ),
            diagnosis_metric_card(metric_row, "디스크", ("diskBusyEstimatePercent",), "현재 busy", disk_threshold),
            diagnosis_metric_card(metric_row, "GPU", ("gpuUsage", "gpuUsagePercent"), "현재 사용률", threshold),
        ],
        "events": diagnosis_detail_event_rows(summary_rows),
        "aiAnalysis": {
            "available": False,
            "summary": "AI 분석 결과 없음",
            "suspectedCauses": [],
            "recommendedActions": [],
            "adminReview": "AI 분석 결과가 저장되어 있지 않습니다.",
        },
    }


def powershell_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def show_agent_error_dialog(title: str, message: str) -> None:
    if tk is not None:
        root = None
        try:
            width = 560
            height = 260
            root = tk.Tk()
            root.title(title)
            root.update_idletasks()
            screen_width = root.winfo_screenwidth()
            screen_height = root.winfo_screenheight()
            x = max(0, (screen_width - width) // 2)
            y = max(0, (screen_height - height) // 2)
            root.geometry(f"{width}x{height}+{x}+{y}")
            root.minsize(520, 220)
            root.configure(background="#f8fafc")
            root.attributes("-topmost", True)
            root.after(250, lambda: root.attributes("-topmost", False))

            container = tk.Frame(root, background="#f8fafc", padx=22, pady=18)
            container.pack(fill="both", expand=True)
            tk.Label(
                container,
                text=title,
                font=("Segoe UI", 13, "bold"),
                foreground="#991b1b",
                background="#f8fafc",
                anchor="w",
            ).pack(fill="x")
            tk.Label(
                container,
                text=message,
                font=("Segoe UI", 10),
                foreground="#1f2937",
                background="#f8fafc",
                justify="left",
                anchor="nw",
                wraplength=500,
            ).pack(fill="both", expand=True, pady=(12, 16))
            tk.Button(
                container,
                text="확인",
                width=10,
                command=root.destroy,
            ).pack(anchor="e")
            root.protocol("WM_DELETE_WINDOW", root.destroy)
            root.mainloop()
            return
        except Exception:
            pass
        finally:
            if root is not None:
                try:
                    root.destroy()
                except Exception:
                    pass
    if os.name != "nt":
        return
    command = (
        "Add-Type -AssemblyName PresentationFramework; "
        f"[System.Windows.MessageBox]::Show({powershell_string(message)}, {powershell_string(title)}, 'OK', 'Error')"
    )
    try:
        subprocess.Popen(
            ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command],
            **hidden_subprocess_kwargs(),
        )
    except OSError:
        return


def show_log_viewer_powershell(config_path: Path) -> None:
    config = load_config(config_path)
    config.log_dir.mkdir(parents=True, exist_ok=True)
    path = log_file(config)
    model = status_home_model(config, path)
    signal_lines = [
        f"{signal['time']}  {signal['level']}  {signal['title']} ->"
        for signal in model["signals"]
    ] or ["최근 감지 신호 없음"]
    signals_text = "\r\n".join(signal_lines)
    viewer_path = app_data_dir() / "log-viewer.ps1"
    viewer_path.parent.mkdir(parents=True, exist_ok=True)
    script = f"""
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$logPath = {powershell_string(str(log_file(config).resolve()))}
$logDir = {powershell_string(str(config.log_dir.resolve()))}
$supportUrl = {powershell_string(support_new_url(config))}
$pcStatus = {powershell_string(str(model["pcStatusCard"]["value"]))}
$pcDetail = {powershell_string(str(model["pcStatusCard"]["detail"]))}
$pcTone = {powershell_string(str(model["pcStatusCard"].get("tone", "muted")))}
$uploadStatus = {powershell_string(str(model["uploadCard"]["value"]))}
$uploadDetail = {powershell_string(str(model["uploadCard"]["detail"]))}
$startupStatus = {powershell_string(str(model["startupCard"]["value"]))}
$startupDetail = {powershell_string(str(model["startupCard"]["detail"]))}
$versionText = {powershell_string(str(model["versionCard"]["value"]))}
$versionDetail = {powershell_string(str(model["versionCard"]["detail"]))}
$signalsText = {powershell_string(signals_text)}

$appBg = [System.Drawing.ColorTranslator]::FromHtml("#f5f7f8")
$sidebarBg = [System.Drawing.ColorTranslator]::FromHtml("#e7f2ef")
$cardBg = [System.Drawing.ColorTranslator]::FromHtml("#ffffff")
$sectionBg = [System.Drawing.ColorTranslator]::FromHtml("#f8fbfb")
$primaryBg = [System.Drawing.ColorTranslator]::FromHtml("#1f8a70")
$warningColor = [System.Drawing.ColorTranslator]::FromHtml("#b7791f")
$dangerColor = [System.Drawing.ColorTranslator]::FromHtml("#b42318")
$okSoftBg = [System.Drawing.ColorTranslator]::FromHtml("#e8f7f4")
$warningSoftBg = [System.Drawing.ColorTranslator]::FromHtml("#fff7e6")
$dangerSoftBg = [System.Drawing.ColorTranslator]::FromHtml("#fff1f0")
$borderColor = [System.Drawing.ColorTranslator]::FromHtml("#d7e0e3")
$textColor = [System.Drawing.ColorTranslator]::FromHtml("#172b3a")
$mutedColor = [System.Drawing.ColorTranslator]::FromHtml("#5c6b73")
$uiFontFamily = "Malgun Gothic"
$installedFonts = New-Object System.Drawing.Text.InstalledFontCollection
$installedFontNames = @($installedFonts.Families | ForEach-Object {{ $_.Name }})
foreach ($candidate in @("Segoe UI Variable", "Segoe UI", "Malgun Gothic")) {{
  if ($installedFontNames -contains $candidate) {{
    $uiFontFamily = $candidate
    break
  }}
}}

function New-UIFont {{
  param([float]$Px, [System.Drawing.FontStyle]$Style = [System.Drawing.FontStyle]::Regular)
  return New-Object System.Drawing.Font($uiFontFamily, ($Px * 72.0 / 96.0), $Style, [System.Drawing.GraphicsUnit]::Point)
}}

function Get-ToneColor {{
  param([string]$Tone)
  switch ($Tone) {{
    "ok" {{ return $primaryBg }}
    "warning" {{ return $warningColor }}
    "danger" {{ return $dangerColor }}
    default {{ return $mutedColor }}
  }}
}}

function Get-ToneBackColor {{
  param([string]$Tone)
  switch ($Tone) {{
    "ok" {{ return $okSoftBg }}
    "warning" {{ return $warningSoftBg }}
    "danger" {{ return $dangerSoftBg }}
    default {{ return $sectionBg }}
  }}
}}

function Style-Button {{
  param($Button, [bool]$Primary = $false)
  $Button.FlatStyle = [System.Windows.Forms.FlatStyle]::Flat
  $Button.FlatAppearance.BorderSize = 1
  $Button.FlatAppearance.BorderColor = $borderColor
  $Button.Font = New-UIFont 14 ([System.Drawing.FontStyle]::Bold)
  $Button.Cursor = [System.Windows.Forms.Cursors]::Hand
  if ($Primary) {{
    $Button.BackColor = $primaryBg
    $Button.ForeColor = [System.Drawing.Color]::White
  }} else {{
    $Button.BackColor = [System.Drawing.Color]::White
    $Button.ForeColor = $textColor
  }}
}}

function Style-NavLink {{
  param($Button)
  $Button.FlatStyle = [System.Windows.Forms.FlatStyle]::Flat
  $Button.FlatAppearance.BorderSize = 0
  $Button.Font = New-UIFont 14
  $Button.Cursor = [System.Windows.Forms.Cursors]::Hand
  $Button.BackColor = $sidebarBg
  $Button.ForeColor = $textColor
  $Button.TextAlign = [System.Drawing.ContentAlignment]::MiddleLeft
}}

function Add-SoftBorder {{
  param($Panel)
  $Panel.BorderStyle = [System.Windows.Forms.BorderStyle]::None
  $Panel.Add_Paint({{
    param($sender, $eventArgs)
    $eventArgs.Graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $rect = New-Object System.Drawing.Rectangle(0, 0, ($sender.Width - 1), ($sender.Height - 1))
    $radius = 8
    $diameter = $radius * 2
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc($rect.X, $rect.Y, $diameter, $diameter, 180, 90)
    $path.AddArc(($rect.Right - $diameter), $rect.Y, $diameter, $diameter, 270, 90)
    $path.AddArc(($rect.Right - $diameter), ($rect.Bottom - $diameter), $diameter, $diameter, 0, 90)
    $path.AddArc($rect.X, ($rect.Bottom - $diameter), $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    $pen = New-Object System.Drawing.Pen($borderColor, 1)
    $eventArgs.Graphics.DrawPath($pen, $path)
    $pen.Dispose()
    $path.Dispose()
  }})
}}

function Get-RowValue {{
  param($Row, [string[]]$Names)
  foreach ($name in $Names) {{
    if ($Row.PSObject.Properties[$name] -and $null -ne $Row.$name) {{
      return $Row.$name
    }}
    if ($Row.PSObject.Properties["payload"] -and $null -ne $Row.payload -and $Row.payload.PSObject.Properties[$name] -and $null -ne $Row.payload.$name) {{
      return $Row.payload.$name
    }}
  }}
  return $null
}}

function Hide-SensitiveText {{
  param($Value)
  if ($null -eq $Value) {{ return "-" }}
  $text = [string]$Value
  $text = [regex]::Replace($text, "(?i)(authorization|agenttoken|activationtoken|token|password)\\s*[:=]\\s*\\S+", '$1=[hidden]')
  $text = [regex]::Replace($text, "[A-Za-z]:\\\\[^\\s\\t\\r\\n]+", "[path hidden]")
  $text = [regex]::Replace($text, "(/[^\\s\\t\\r\\n]+){{2,}}", "[path hidden]")
  if ($text.Length -gt 120) {{ $text = $text.Substring(0, 117) + "..." }}
  return $text
}}

function Format-Percent {{
  param($Value)
  if ($null -eq $Value) {{ return "-" }}
  try {{ return "{{0:N1}}%" -f [double]$Value }} catch {{ return "-" }}
}}

function Format-Temp {{
  param($Value)
  if ($null -eq $Value) {{ return "-" }}
  try {{ return "{{0:N1}}C" -f [double]$Value }} catch {{ return "-" }}
}}

function Get-ObservedAt {{
  param($Row)
  $value = Get-RowValue $Row @("timestamp", "collectedAt", "receivedAt", "detectedAt")
  if ($null -eq $value) {{ return $null }}
  try {{ return [datetime]::Parse([string]$value).ToLocalTime() }} catch {{ return $null }}
}}

function Get-FilteredLogText {{
  param([string]$DateText, [int]$Hour)
  if (-not (Test-Path -LiteralPath $logPath)) {{
    return "아직 수집된 로그가 없습니다."
  }}
  $output = New-Object System.Collections.Generic.List[string]
  try {{
    $start = [datetime]::ParseExact($DateText, "yyyy-MM-dd", $null).AddHours($Hour)
  }} catch {{
    return "날짜 형식이 올바르지 않습니다. yyyy-MM-dd 형식으로 입력하세요."
  }}
  $end = $start.AddHours(1)
  $output.Add(("{{0,-9}} {{1,-26}} {{2,8}} {{3,8}} {{4,8}} {{5,8}} {{6,8}}  {{7}}" -f "시간", "이벤트 종류", "CPU", "MEM", "DISK", "GPU", "CPU 온도", "메시지"))
  $output.Add("-" * 112)
  Get-Content -LiteralPath $logPath | ForEach-Object {{
    try {{
      $row = $_ | ConvertFrom-Json
      $observedAt = Get-ObservedAt $row
      if ($observedAt -ge $start -and $observedAt -lt $end) {{
        $timeText = $observedAt.ToString("HH:mm:ss")
        $kind = Hide-SensitiveText (Get-RowValue $row @("kind", "eventType"))
        $cpu = Format-Percent (Get-RowValue $row @("cpuUsage", "cpuUsagePercent"))
        $memory = Format-Percent (Get-RowValue $row @("memoryUsage", "ramUsage", "memoryUsedPercent"))
        $disk = Format-Percent (Get-RowValue $row @("diskBusyEstimatePercent"))
        $gpu = Format-Percent (Get-RowValue $row @("gpuUsage", "gpuUsagePercent"))
        $cpuTemp = Format-Temp (Get-RowValue $row @("cpuTemp", "cpuTempCelsius", "cpuTemperatureCelsius"))
        $message = Hide-SensitiveText (Get-RowValue $row @("message", "osErrorEvent", "status", "summary"))
        $output.Add(("{{0,-9}} {{1,-26}} {{2,8}} {{3,8}} {{4,8}} {{5,8}} {{6,8}}  {{7}}" -f $timeText, $kind, $cpu, $memory, $disk, $gpu, $cpuTemp, $message))
      }}
    }} catch {{}}
  }}
  if ($output.Count -eq 2) {{
    return "선택한 구간에 표시할 로그가 없습니다."
  }}
  if ($output.Count -gt 500) {{
    $output = $output.GetRange($output.Count - 500, 500)
  }}
  return ($output -join "`r`n")
}}

$form = New-Object System.Windows.Forms.Form
$form.Text = "PCAgent"
$form.Size = New-Object System.Drawing.Size(1000, 720)
$form.MinimumSize = New-Object System.Drawing.Size(1000, 720)
$form.MaximumSize = New-Object System.Drawing.Size(1000, 720)
$form.StartPosition = "CenterScreen"
$form.BackColor = $appBg
$form.Font = New-UIFont 14

$sidebar = New-Object System.Windows.Forms.Panel
$sidebar.Location = New-Object System.Drawing.Point(0, 0)
$sidebar.Size = New-Object System.Drawing.Size(150, 640)
$sidebar.Anchor = [System.Windows.Forms.AnchorStyles]::Top -bor [System.Windows.Forms.AnchorStyles]::Bottom -bor [System.Windows.Forms.AnchorStyles]::Left
$sidebar.BackColor = $sidebarBg
$form.Controls.Add($sidebar)

$brand = New-Object System.Windows.Forms.Label
$brand.Text = "PCAgent"
$brand.Font = New-UIFont 14
$brand.ForeColor = $textColor
$brand.BackColor = $sidebarBg
$brand.Location = New-Object System.Drawing.Point(24, 12)
$brand.Size = New-Object System.Drawing.Size(110, 26)
$sidebar.Controls.Add($brand)

$navStatus = New-Object System.Windows.Forms.Panel
$navStatus.Location = New-Object System.Drawing.Point(8, 58)
$navStatus.Size = New-Object System.Drawing.Size(134, 44)
$navStatus.BackColor = [System.Drawing.Color]::White
Add-SoftBorder $navStatus
$activeBar = New-Object System.Windows.Forms.Panel
$activeBar.Location = New-Object System.Drawing.Point(0, 0)
$activeBar.Size = New-Object System.Drawing.Size(4, 44)
$activeBar.BackColor = $primaryBg
$navStatus.Controls.Add($activeBar)
$navStatusLabel = New-Object System.Windows.Forms.Label
$navStatusLabel.Text = "  • 상태"
$navStatusLabel.Font = New-UIFont 14 ([System.Drawing.FontStyle]::Bold)
$navStatusLabel.ForeColor = $textColor
$navStatusLabel.BackColor = [System.Drawing.Color]::White
$navStatusLabel.Location = New-Object System.Drawing.Point(18, 12)
$navStatusLabel.Size = New-Object System.Drawing.Size(96, 20)
$navStatus.Controls.Add($navStatusLabel)
$sidebar.Controls.Add($navStatus)

$navLog = New-Object System.Windows.Forms.Button
$navLog.Text = "≡ 기록"
$navLog.Location = New-Object System.Drawing.Point(28, 124)
$navLog.Size = New-Object System.Drawing.Size(96, 32)
Style-NavLink $navLog
$navLog.Add_Click({{ $textbox.Focus() }})
$sidebar.Controls.Add($navLog)

$navSupport = New-Object System.Windows.Forms.Button
$navSupport.Text = "+ 진단"
$navSupport.Location = New-Object System.Drawing.Point(28, 174)
$navSupport.Size = New-Object System.Drawing.Size(96, 32)
Style-NavLink $navSupport
$navSupport.Add_Click({{ Start-Process -FilePath $supportUrl }})
$sidebar.Controls.Add($navSupport)

$navSettings = New-Object System.Windows.Forms.Button
$navSettings.Text = "○ 설정"
$navSettings.Location = New-Object System.Drawing.Point(28, 224)
$navSettings.Size = New-Object System.Drawing.Size(96, 32)
Style-NavLink $navSettings
$navSettings.Add_Click({{ Start-Process -FilePath $logDir }})
$sidebar.Controls.Add($navSettings)

$title = New-Object System.Windows.Forms.Label
$title.Text = "상태 홈"
$title.Font = New-UIFont 20 ([System.Drawing.FontStyle]::Bold)
$title.ForeColor = $textColor
$title.BackColor = $appBg
$title.AutoSize = $true
$title.Location = New-Object System.Drawing.Point(170, 48)
$form.Controls.Add($title)

$subtitle = New-Object System.Windows.Forms.Label
$subtitle.Text = "시스템 상태와 감지 로그를 실시간으로 확인합니다."
$subtitle.Font = New-UIFont 12
$subtitle.ForeColor = $mutedColor
$subtitle.BackColor = $appBg
$subtitle.AutoSize = $true
$subtitle.Location = New-Object System.Drawing.Point(170, 82)
$form.Controls.Add($subtitle)

function Add-Card {{
  param(
    [int]$X,
    [string]$Icon,
    [string]$Title,
    [string]$Value,
    [string]$Detail,
    [System.Drawing.Color]$ToneColor = $primaryBg,
    [System.Drawing.Color]$ToneBackColor = $sectionBg,
    [bool]$HighlightTitle = $false
  )
  $card = New-Object System.Windows.Forms.Panel
  $card.Location = New-Object System.Drawing.Point($X, 114)
  $card.Size = New-Object System.Drawing.Size(185, 90)
  $card.BackColor = $cardBg
  Add-SoftBorder $card
  $form.Controls.Add($card)
  $iconBox = New-Object System.Windows.Forms.Label
  $iconBox.Text = $Icon
  $iconBox.TextAlign = [System.Drawing.ContentAlignment]::MiddleCenter
  $iconBox.Font = New-UIFont 14 ([System.Drawing.FontStyle]::Bold)
  $iconBox.ForeColor = $ToneColor
  $iconBox.BackColor = $ToneBackColor
  $iconBox.BorderStyle = [System.Windows.Forms.BorderStyle]::FixedSingle
  $iconBox.Location = New-Object System.Drawing.Point(144, 12)
  $iconBox.Size = New-Object System.Drawing.Size(28, 28)
  $card.Controls.Add($iconBox)
  $cardTitle = New-Object System.Windows.Forms.Label
  $cardTitle.Text = $Title
  $cardTitle.Font = New-UIFont 16 ([System.Drawing.FontStyle]::Bold)
  if ($HighlightTitle) {{ $cardTitle.ForeColor = $ToneColor }} else {{ $cardTitle.ForeColor = $textColor }}
  $cardTitle.BackColor = $cardBg
  $cardTitle.Location = New-Object System.Drawing.Point(14, 14)
  $cardTitle.Size = New-Object System.Drawing.Size(124, 20)
  $card.Controls.Add($cardTitle)
  $cardValue = New-Object System.Windows.Forms.Label
  $cardValue.Text = $Value
  $cardValue.Font = New-UIFont 14 ([System.Drawing.FontStyle]::Bold)
  $cardValue.ForeColor = $ToneColor
  $cardValue.BackColor = $cardBg
  $cardValue.Location = New-Object System.Drawing.Point(14, 38)
  $cardValue.Size = New-Object System.Drawing.Size(136, 24)
  $card.Controls.Add($cardValue)
  $cardDetail = New-Object System.Windows.Forms.Label
  $cardDetail.Text = $Detail
  $cardDetail.ForeColor = $mutedColor
  $cardDetail.BackColor = $cardBg
  $cardDetail.Location = New-Object System.Drawing.Point(14, 68)
  $cardDetail.Size = New-Object System.Drawing.Size(154, 18)
  $card.Controls.Add($cardDetail)
}}

$pcToneColor = Get-ToneColor $pcTone
$pcToneBackColor = Get-ToneBackColor $pcTone
Add-Card 170 "PC" "PC 상태" $pcStatus $pcDetail $pcToneColor $pcToneBackColor $true
Add-Card 365 "UP" "마지막 업로드" $uploadStatus $uploadDetail
Add-Card 560 "ST" "시작프로그램" $startupStatus $startupDetail
Add-Card 755 "i" "버전" $versionText $versionDetail

$signalsPanel = New-Object System.Windows.Forms.Panel
$signalsPanel.Location = New-Object System.Drawing.Point(170, 216)
$signalsPanel.Size = New-Object System.Drawing.Size(790, 124)
$signalsPanel.BackColor = $cardBg
Add-SoftBorder $signalsPanel
$form.Controls.Add($signalsPanel)

$signalLabel = New-Object System.Windows.Forms.Label
$signalLabel.Text = "최근 감지 신호"
$signalLabel.Font = New-UIFont 16 ([System.Drawing.FontStyle]::Bold)
$signalLabel.ForeColor = $textColor
$signalLabel.BackColor = $cardBg
$signalLabel.Location = New-Object System.Drawing.Point(16, 14)
$signalLabel.AutoSize = $true
$signalsPanel.Controls.Add($signalLabel)

$signalsBody = New-Object System.Windows.Forms.Label
$signalsBody.Text = $signalsText
$signalsBody.Font = New-UIFont 14
$signalsBody.ForeColor = $textColor
$signalsBody.BackColor = $cardBg
$signalsBody.Location = New-Object System.Drawing.Point(16, 44)
$signalsBody.Size = New-Object System.Drawing.Size(750, 22)
$signalsPanel.Controls.Add($signalsBody)

$signalsHelp = New-Object System.Windows.Forms.Label
$signalsHelp.Text = "단순 CPU/RAM/GPU 고사용률은 AS 알림에서 제외됩니다"
$signalsHelp.Font = New-UIFont 12
$signalsHelp.ForeColor = $mutedColor
$signalsHelp.BackColor = $cardBg
$signalsHelp.Location = New-Object System.Drawing.Point(16, 70)
$signalsHelp.Size = New-Object System.Drawing.Size(750, 20)
$signalsPanel.Controls.Add($signalsHelp)

$dateLabel = New-Object System.Windows.Forms.Label
$dateLabel.Text = "날짜"
$dateLabel.AutoSize = $true
$dateLabel.ForeColor = $mutedColor
$dateLabel.BackColor = $appBg
$dateLabel.Location = New-Object System.Drawing.Point(138, 282)
$dateLabel.Visible = $false
$form.Controls.Add($dateLabel)

$dateInput = New-Object System.Windows.Forms.TextBox
$dateInput.Location = New-Object System.Drawing.Point(180, 278)
$dateInput.Size = New-Object System.Drawing.Size(96, 22)
$dateInput.Text = (Get-Date).ToString("yyyy-MM-dd")
$dateInput.Visible = $false
$form.Controls.Add($dateInput)

$hourLabel = New-Object System.Windows.Forms.Label
$hourLabel.Text = "시간"
$hourLabel.AutoSize = $true
$hourLabel.ForeColor = $mutedColor
$hourLabel.BackColor = $appBg
$hourLabel.Location = New-Object System.Drawing.Point(292, 282)
$hourLabel.Visible = $false
$form.Controls.Add($hourLabel)

$hourSelect = New-Object System.Windows.Forms.ComboBox
$hourSelect.DropDownStyle = "DropDownList"
$hourSelect.Location = New-Object System.Drawing.Point(334, 278)
$hourSelect.Size = New-Object System.Drawing.Size(72, 22)
0..23 | ForEach-Object {{ [void]$hourSelect.Items.Add(($_.ToString("00")) + ":00") }}
$hourSelect.SelectedIndex = [int](Get-Date).Hour
$hourSelect.Visible = $false
$form.Controls.Add($hourSelect)

$logPanel = New-Object System.Windows.Forms.Panel
$logPanel.Location = New-Object System.Drawing.Point(170, 350)
$logPanel.Size = New-Object System.Drawing.Size(790, 240)
$logPanel.Anchor = [System.Windows.Forms.AnchorStyles]::Top -bor [System.Windows.Forms.AnchorStyles]::Bottom -bor [System.Windows.Forms.AnchorStyles]::Left
$logPanel.BackColor = $cardBg
Add-SoftBorder $logPanel
$form.Controls.Add($logPanel)

$logTitle = New-Object System.Windows.Forms.Label
$logTitle.Text = "로그 현황"
$logTitle.Font = New-UIFont 16 ([System.Drawing.FontStyle]::Bold)
$logTitle.ForeColor = $textColor
$logTitle.BackColor = $cardBg
$logTitle.Location = New-Object System.Drawing.Point(16, 14)
$logTitle.AutoSize = $true
$logPanel.Controls.Add($logTitle)

$textbox = New-Object System.Windows.Forms.TextBox
$textbox.Multiline = $true
$textbox.ScrollBars = "Both"
$textbox.ReadOnly = $true
$textbox.Font = New-UIFont 12
$textbox.BackColor = [System.Drawing.Color]::White
$textbox.ForeColor = $textColor
$textbox.BorderStyle = [System.Windows.Forms.BorderStyle]::None
$textbox.Location = New-Object System.Drawing.Point(16, 46)
$textbox.Size = New-Object System.Drawing.Size(758, 178)
$textbox.Anchor = [System.Windows.Forms.AnchorStyles]::Top -bor [System.Windows.Forms.AnchorStyles]::Bottom -bor [System.Windows.Forms.AnchorStyles]::Left -bor [System.Windows.Forms.AnchorStyles]::Right
$textbox.Text = Get-FilteredLogText $dateInput.Text $hourSelect.SelectedIndex
$logPanel.Controls.Add($textbox)

$refresh = New-Object System.Windows.Forms.Button
$refresh.Text = "로그 갱신"
$refresh.Location = New-Object System.Drawing.Point(420, 277)
$refresh.Size = New-Object System.Drawing.Size(88, 24)
Style-Button $refresh $true
$refresh.Add_Click({{ $textbox.Text = Get-FilteredLogText $dateInput.Text $hourSelect.SelectedIndex }})
$refresh.Visible = $false
$form.Controls.Add($refresh)

$today = New-Object System.Windows.Forms.Button
$today.Text = "현재"
$today.Location = New-Object System.Drawing.Point(516, 277)
$today.Size = New-Object System.Drawing.Size(64, 24)
Style-Button $today
$today.Add_Click({{
  $dateInput.Text = (Get-Date).ToString("yyyy-MM-dd")
  $hourSelect.SelectedIndex = [int](Get-Date).Hour
  $textbox.Text = Get-FilteredLogText $dateInput.Text $hourSelect.SelectedIndex
}})
$today.Visible = $false
$form.Controls.Add($today)

$folder = New-Object System.Windows.Forms.Button
$folder.Text = "로그 폴더"
$folder.Location = New-Object System.Drawing.Point(138, 550)
$folder.Size = New-Object System.Drawing.Size(100, 28)
$folder.Anchor = [System.Windows.Forms.AnchorStyles]::Bottom -bor [System.Windows.Forms.AnchorStyles]::Left
Style-Button $folder
$folder.Add_Click({{ Start-Process -FilePath $logDir }})
$folder.Visible = $false
$form.Controls.Add($folder)

$support = New-Object System.Windows.Forms.Button
$support.Text = "AS 페이지"
$support.Location = New-Object System.Drawing.Point(250, 550)
$support.Size = New-Object System.Drawing.Size(100, 28)
$support.Anchor = [System.Windows.Forms.AnchorStyles]::Bottom -bor [System.Windows.Forms.AnchorStyles]::Left
Style-Button $support
$support.Add_Click({{ Start-Process -FilePath $supportUrl }})
$support.Visible = $false
$form.Controls.Add($support)

$liveRefreshTimer = New-Object System.Windows.Forms.Timer
$liveRefreshTimer.Interval = 5000
$liveRefreshTimer.Add_Tick({{
  $textbox.Text = Get-FilteredLogText $dateInput.Text $hourSelect.SelectedIndex
  $textbox.SelectionStart = $textbox.TextLength
  $textbox.ScrollToCaret()
}})
$form.Add_Shown({{ $liveRefreshTimer.Start() }})
$form.Add_FormClosed({{ $liveRefreshTimer.Stop(); $liveRefreshTimer.Dispose() }})

[void]$form.ShowDialog()
"""
    viewer_path.write_text(script.strip() + "\n", encoding="utf-8")
    try:
        subprocess.Popen(
            [
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(viewer_path),
            ],
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except Exception:
        open_log_folder(config_path)


def show_log_viewer(
    config_path: Path,
    focus_signal: dict[str, Any] | None = None,
    support_signal: dict[str, Any] | None = None,
    background_mode: bool = False,
    diagnosis_session_provider: Any = None,
    connection_state_provider: Any = None,
    metrics_snapshot_provider: Any = None,
    diagnosis_snapshot_provider: Any = None,
    diagnosis_result_provider: Any = None,
    start_initial_metrics: Any = None,
    start_diagnosis: Any = None,
    cancel_diagnosis: Any = None,
    retry_diagnosis: Any = None,
    finish_diagnosis_session: Any = None,
    on_window_ready: Any = None,
    on_window_closed: Any = None,
) -> None:
    """Render the reference PC diagnosis flow while preserving the existing Agent boundaries."""
    if tk is None or ttk is None:
        show_log_viewer_powershell(config_path)
        return

    viewer_lock = acquire_named_instance_lock(VIEWER_INSTANCE_MUTEX_NAME)
    if viewer_lock is None:
        return

    config = load_config(config_path)
    config.log_dir.mkdir(parents=True, exist_ok=True)
    root = tk.Tk()
    root.title(WINDOW_TITLE)
    apply_agent_window_icon(root)
    root.geometry(f"{PC_AGENT_WINDOW_WIDTH}x{PC_AGENT_WINDOW_HEIGHT}")
    root.minsize(PC_AGENT_WINDOW_WIDTH, PC_AGENT_WINDOW_HEIGHT)
    root.resizable(True, True)
    root.overrideredirect(False)
    root.configure(background="#ffffff")
    root.attributes("-topmost", True)
    root.after(15000, lambda: root.attributes("-topmost", False))
    root.lift()
    root.focus_force()
    try:
        root.tk.call("tk", "scaling", 1.0)
    except tk.TclError:
        pass

    ui_font_family = resolve_ui_font_family(root)

    def font(size: int, weight: str = "regular") -> tuple[Any, ...]:
        return tk_ui_font(ui_font_family, size, weight)

    measurement_fonts: dict[tuple[int, str], Any] = {}

    def fitted_text(value: str, size: int, width: int, max_lines: int, weight: str = "regular") -> str:
        key = (size, weight)
        measurement_font = measurement_fonts.get(key)
        if measurement_font is None and tkfont is not None:
            measurement_font = tkfont.Font(root=root, font=font(size, weight))
            measurement_fonts[key] = measurement_font
        if measurement_font is None:
            return fit_measured_text(value, width, max_lines, lambda text_value: len(text_value) * size)
        return fit_measured_text(value, width, max_lines, measurement_font.measure)

    canvas = tk.Canvas(
        root,
        width=PC_AGENT_WINDOW_WIDTH,
        height=PC_AGENT_WINDOW_HEIGHT,
        background="#ffffff",
        highlightthickness=0,
        borderwidth=0,
    )
    canvas.pack(fill="both", expand=True)

    colors = {
        "text": "#111111",
        "muted": "#767676",
        "subtle": "#9a9a9a",
        "line": "#dedede",
        "soft": "#f5f5f5",
        "blue": "#1677ff",
        "red": "#ff4d3d",
        "red_soft": "#fff0ef",
        "green": "#12bd67",
        "green_soft": "#effbf5",
        "warning": "#f59e0b",
        "danger": "#c2410c",
    }
    capture_state_override = os.environ.get("PC_AGENT_CAPTURE_STATE", "").strip()
    capture_state = capture_state_override
    if capture_state not in {"SYMPTOM_CONFIRM", "DIAGNOSING", "DIAGNOSIS_RESULT"}:
        capture_state = "SYMPTOM_CONFIRM"
    stored_session = diagnosis_session_provider() if callable(diagnosis_session_provider) else None
    stored_diagnosis = diagnosis_snapshot_provider() if callable(diagnosis_snapshot_provider) else None
    diagnosis_session = active_viewer_session(stored_session, stored_diagnosis)
    if isinstance(diagnosis_session, DiagnosisSession):
        initial_metrics = metrics_snapshot_provider() if callable(metrics_snapshot_provider) else None
        diagnosis_snapshot = stored_diagnosis
        result_snapshot = diagnosis_result_provider() if callable(diagnosis_result_provider) else None
    else:
        initial_metrics = None
        diagnosis_snapshot = None
        result_snapshot = None
    diagnosis_started = (
        isinstance(diagnosis_session, DiagnosisSession)
        and diagnosis_session.agent_state in {"RUNNING", "COMPLETED", "FAILED"}
    )
    if not capture_state_override:
        capture_state = diagnosis_session_ui_state(
            diagnosis_session,
            initial_metrics,
            diagnosis_snapshot,
            result_snapshot,
            diagnosis_started=diagnosis_started,
        )
    ui = {
        "state": capture_state,
        "demo": diagnosis_session.request.mode == "DEMO" if isinstance(diagnosis_session, DiagnosisSession) else False,
        "scenarioId": diagnosis_demo_scenario_id(diagnosis_session),
        "diagnosisSession": diagnosis_session,
        "diagnosisSnapshot": diagnosis_snapshot,
        "diagnosisResult": result_snapshot,
        "requestLocked": isinstance(diagnosis_session, DiagnosisSession),
        "diagnosisStarted": diagnosis_started,
        "resultRequested": False,
        "busy": False,
        "initialMetricsRequested": isinstance(diagnosis_session, DiagnosisSession),
        "diagnosisReady": False,
        "status": "",
        "diagnosis": None,
        "window": None,
        "asState": "diagnosisResult",
        "asRequest": None,
        "asRequestPayload": None,
        "ticketUrl": None,
    }
    static_photo_cache = RetainedAssetCache()
    progress_ring_photo_cache = RetainedAssetCache()
    spinner_photo_cache: dict[tuple[int, str], list[Any]] = {}
    callback_state: dict[str, Any] = {
        "initialMetricsAfterId": None,
        "metricsAfterId": None,
        "renderAfterId": None,
        "diagnosisProgressAfterId": None,
        "visibilityAfterId": None,
        "renderPending": False,
        "uiActive": True,
        "closed": False,
    }
    window_visibility = WindowVisibilityState()
    button_counter = {"value": 0}
    wave_animation: dict[str, Any] = {
        "frame": 0,
        "items": {},
        "states": {
            component: FluidWaveDisplayState()
            for component in ("cpu", "gpu", "ram", "disk")
        },
        "spinnerItems": [],
    }
    animation_controller: AnimationCallbackController | None = None

    def pillow_photo(image: Any) -> Any:
        if ImageTk is None:
            raise RuntimeError("Pillow ImageTk is required for the PC Agent UI")
        return ImageTk.PhotoImage(image, master=root)

    def cached_photo(key: tuple[Any, ...], renderer: Callable[[], Any]) -> Any:
        return static_photo_cache.get(key, lambda: pillow_photo(renderer()))

    fluid_wave_cache = DeferredFluidWaveCache(
        root.after,
        root.after_cancel,
        render_fluid_wave_frame,
        pillow_photo,
        frame_count=FLUID_WAVE_FRAME_COUNT,
        batch_size=2,
        max_buckets=6,
        delay_ms=1,
    )

    def static_fluid_wave_photo(bucket: int) -> Any:
        return cached_photo(
            ("fluid-wave-static", bucket),
            lambda: render_fluid_wave_frame(bucket, 0),
        )

    def progress_ring_photo(progress: int) -> Any:
        value = max(0, min(100, int(progress)))
        return progress_ring_photo_cache.get(
            ("progress-ring", value),
            lambda: pillow_photo(render_pillow_progress_ring(value)),
        )

    def spinner_frames(size: int, color: str) -> list[Any]:
        key = (size, color)
        frames = spinner_photo_cache.get(key)
        if frames is None:
            frames = [
                pillow_photo(render_pillow_status_icon("running", size, color, frame))
                for frame in range(SPINNER_FRAME_COUNT)
            ]
            spinner_photo_cache[key] = frames
        return frames

    def round_rect(
        x1: int,
        y1: int,
        x2: int,
        y2: int,
        radius: int,
        fill: str,
        outline: str = "",
        width: int = 1,
        tags: tuple[str, ...] | str = (),
    ) -> int:
        surface_width = max(1, int(round(x2 - x1)))
        surface_height = max(1, int(round(y2 - y1)))
        photo = cached_photo(
            ("rounded", surface_width, surface_height, radius, fill, outline, width),
            lambda: render_rounded_surface(surface_width, surface_height, radius, fill, outline, width),
        )
        return canvas.create_image(
            round(x1),
            round(y1),
            image=photo,
            anchor="nw",
            tags=tags,
        )

    def text(
        x: int,
        y: int,
        value: str,
        size: int,
        color: str | None = None,
        weight: str = "regular",
        anchor: str = "nw",
        width: int | None = None,
        tags: tuple[str, ...] | str = (),
    ) -> int:
        options: dict[str, Any] = {
            "text": value,
            "font": font(size, weight),
            "fill": color or colors["text"],
            "anchor": anchor,
            "tags": tags,
        }
        if width is not None:
            options["width"] = width
        return canvas.create_text(x, y, **options)

    def line(x1: int, y1: int, x2: int, y2: int, color: str | None = None, width: int = 1) -> int:
        return canvas.create_line(x1, y1, x2, y2, fill=color or colors["line"], width=width)

    def bind_click(tag: str, command: Any) -> None:
        canvas.tag_bind(tag, "<Button-1>", lambda event: command())
        canvas.tag_bind(tag, "<Enter>", lambda event: canvas.configure(cursor="hand2"))
        canvas.tag_bind(tag, "<Leave>", lambda event: canvas.configure(cursor=""))

    def button(
        x1: int,
        y1: int,
        x2: int,
        y2: int,
        label: str,
        command: Any,
        primary: bool = True,
        disabled: bool = False,
        size: int = 20,
    ) -> None:
        button_counter["value"] += 1
        tag = f"button-{button_counter['value']}"
        if primary:
            fill, stroke, foreground = ("#111111", "#111111", "#ffffff")
        else:
            fill, stroke, foreground = ("#ffffff", "#111111", "#111111")
        if disabled:
            fill, stroke, foreground = ("#f2f2f2", "#dedede", "#999999")
        round_rect(x1, y1, x2, y2, 10, fill, stroke, 1, tag)
        text((x1 + x2) // 2, (y1 + y2) // 2, label, size, foreground, "semibold", "center", tags=tag)
        if not disabled:
            bind_click(tag, command)

    def draw_check(x: int, y: int, size: int, color: str, width: int = 3) -> None:
        canvas.create_line(
            x - int(size * 0.42), y, x - int(size * 0.12), y + int(size * 0.30),
            x + int(size * 0.48), y - int(size * 0.36), fill=color, width=width,
            capstyle="round", joinstyle="round",
        )

    def draw_chat_icon(x: int, y: int, size: int = 22) -> None:
        display_size = size if size >= 24 else max(24, size * 2)
        photo = cached_photo(
            ("summary-icon", display_size),
            lambda: render_summary_icon(display_size),
        )
        canvas.create_image(x, y, image=photo)

    def tone_color(tone: str) -> str:
        return {
            "running": colors["blue"],
            "success": colors["green"],
            "warning": colors["warning"],
            "error": colors["red"],
        }.get(tone, "#92989d")

    def draw_status_icon(x: int, y: int, tone: str, size: int = 14) -> None:
        color = tone_color(tone)
        if tone == "running":
            frames = spinner_frames(size, color)
            item = canvas.create_image(x, y, image=frames[wave_animation["frame"] % len(frames)])
            wave_animation["spinnerItems"].append((item, frames))
        else:
            photo = cached_photo(
                ("status", tone, size, color),
                lambda: render_pillow_status_icon(tone, size, color),
            )
            canvas.create_image(x, y, image=photo)

    def draw_hardware_icon(x: int, y: int, component: str, color: str = "#555555") -> None:
        photo = cached_photo(
            home_hardware_icon_cache_key(component, color, 30),
            lambda: render_pillow_home_hardware_icon(component, 30, color),
        )
        canvas.create_image(x, y, image=photo)

    def draw_home_hardware_icon(x: int, y: int, component: str, color: str = "#555555") -> None:
        photo = cached_photo(
            home_hardware_icon_cache_key(component, color, 30),
            lambda: render_pillow_home_hardware_icon(component, 30, color),
        )
        canvas.create_image(x, y, image=photo)

    def draw_status_controls() -> None:
        connection_state = connection_state_provider() if callable(connection_state_provider) else "IDLE"
        connected = connection_state in {"IDLE", "REQUEST_RECEIVED", "RUNNING", "COMPLETED"}
        round_rect(778, 68, 866, 102, 8, "#ffffff", "#d8d8d8")
        connection_color = colors["green"] if connected else colors["subtle"]
        connection_dot = cached_photo(
            ("dot", 8, connection_color),
            lambda: render_status_dot(8, connection_color),
        )
        canvas.create_image(795, 85, image=connection_dot)
        text(809, 85, "연결됨" if connected else "연결 끊김", 13, colors["text"], "regular", "w")
        measurement_label = "시연 데이터" if ui["demo"] else "실시간 측정"
        round_rect(878, 68, 970, 102, 8, "#ffffff", "#111111" if ui["demo"] else "#cfcfcf", 1)
        text(924, 85, measurement_label, 13, colors["text"], "regular", "center")

    def draw_screen_logo() -> None:
        photo = cached_photo(
            ("screen-logo", SCREEN_LOGO_DISPLAY_SIZE),
            render_screen_logo_image,
        )
        canvas.create_image(*SCREEN_LOGO_POSITION, image=photo, anchor="center")

    def draw_step(number: int, x: int, label: str, style: str) -> None:
        if style == "loading":
            frames = [
                cached_photo(
                    ("step-node", style, 34, frame),
                    lambda frame=frame: render_step_node(style, 34, frame),
                )
                for frame in range(SPINNER_FRAME_COUNT)
            ]
            item = canvas.create_image(x, 128, image=frames[wave_animation["frame"] % len(frames)])
            wave_animation["spinnerItems"].append((item, frames))
        else:
            photo = cached_photo(
                ("step-node", style, 34),
                lambda: render_step_node(style, 34),
            )
            canvas.create_image(x, 128, image=photo)

        if style == "active" or style == "done-black":
            text(x, 128, str(number), 13, "#ffffff", "regular", "center")
            label_color = colors["text"]
        elif style == "done-check":
            label_color = colors["muted"]
        elif style == "loading":
            label_color = colors["text"]
        else:
            text(x, 128, str(number), 13, colors["subtle"], "regular", "center")
            label_color = colors["muted"]
        text(x, 157, label, 14, label_color, "regular", "center")

    def draw_step_connector(x1: float, x2: float, color: str, thickness: int) -> None:
        length = max(1, int(round(x2 - x1)))
        photo = cached_photo(
            ("step-connector", length, color, thickness),
            lambda: render_step_connector(length, color, thickness),
        )
        canvas.create_image(round(x1), 126, image=photo, anchor="nw")

    STEP_X = (180, 500, 820)
    STEP_ANIM_SECONDS = 0.8

    def draw_stepper() -> None:
        state = str(ui["state"])
        if state == "SYMPTOM_CONFIRM":
            styles = ("active", "idle", "idle")
        elif state == "DIAGNOSING":
            styles = ("done-check", "loading", "idle")
        elif state == "DIAGNOSIS_RESULT":
            # 지나온 단계는 체크로 남긴다(원이 이동해 간 흔적).
            styles = (
                ("done-black", "done-black", "done-black")
                if ui["asState"] == "asCompleted"
                else ("done-check", "done-check", "active")
            )
        else:
            styles = ("done-black", "done-black", "done-black")

        # 전이 애니메이션: 검은 원이 선을 타고 다음 단계로 이동하는 동안
        # 출발 자리는 체크(✓), 도착 자리는 빈 원으로 그리고, 이동 원은 숫자 없이 그린다.
        anim = ui.get("stepAnim")
        moving_ratio = None
        if isinstance(anim, dict):
            elapsed = (time.monotonic() - anim["start"]) / STEP_ANIM_SECONDS
            if elapsed >= 1.0:
                ui["stepAnim"] = None
                anim = None
            else:
                moving_ratio = 1 - (1 - elapsed) ** 3  # ease-out: 도착 직전 감속
                overridden = list(styles)
                overridden[anim["from"]] = "done-check"
                overridden[anim["to"]] = "idle"
                styles = tuple(overridden)

        # 지나간 구간의 선은 진하고 두껍게 — 원이 지나온 경로를 남긴다.
        step_index = ui.get("stepperIndex")
        passed_index = step_index if isinstance(step_index, int) else 0
        if isinstance(anim, dict) and moving_ratio is not None:
            passed_index = anim["from"]  # 이동 중에는 아직 도착 전이므로 완주 구간은 출발 단계까지만
        segments = tuple((STEP_X[index] + 16, STEP_X[index + 1] - 16) for index in range(2))
        for segment_index, (seg_x1, seg_x2) in enumerate(segments):
            if segment_index < passed_index:
                draw_step_connector(seg_x1, seg_x2, "#050505", 2)
            else:
                draw_step_connector(seg_x1, seg_x2, "#d9d9d9", 1)

        draw_step(1, STEP_X[0], PC_AGENT_DIAGNOSIS_STEPS[0], styles[0])
        draw_step(2, STEP_X[1], PC_AGENT_DIAGNOSIS_STEPS[1], styles[1])
        draw_step(3, STEP_X[2], PC_AGENT_DIAGNOSIS_STEPS[2], styles[2])

        if moving_ratio is not None and isinstance(anim, dict):
            from_x = STEP_X[anim["from"]]
            to_x = STEP_X[anim["to"]]
            moving_x = from_x + (to_x - from_x) * moving_ratio
            # 이동 원 뒤로 지나온 만큼 선이 진해지며 따라온다(트레일).
            seg_x1, seg_x2 = segments[anim["from"]]
            trail_end = max(seg_x1, min(seg_x2, moving_x))
            if trail_end > seg_x1:
                draw_step_connector(seg_x1, trail_end, "#050505", 2)
            moving_photo = cached_photo(
                ("step-node", "moving", 34),
                lambda: render_step_node("moving", 34),
            )
            canvas.create_image(round(moving_x), 128, image=moving_photo)

    def draw_metric_wave(
        component: str,
        x: int,
        baseline_y: int,
        width: int,
        latest_value: float | None,
        color: str,
    ) -> None:
        del color
        state = wave_animation["states"][component]
        state.set_measurement(latest_value)
        if latest_value is None:
            canvas.create_image(
                round(x),
                round(baseline_y - FLUID_WAVE_SIZE[1] / 2),
                image=static_fluid_wave_photo(0),
                anchor="nw",
            )
            return
        bucket = state.bucket
        frames = fluid_wave_cache.get(bucket)
        fluid_wave_cache.request(bucket)
        photo = (
            frames[wave_animation["frame"] % len(frames)]
            if frames
            else static_fluid_wave_photo(bucket)
        )
        item = canvas.create_image(
            round(x),
            round(baseline_y - FLUID_WAVE_SIZE[1] / 2),
            image=photo,
            anchor="nw",
        )
        wave_animation["items"][component] = item

    def draw_symptom() -> None:
        active_session = ui["diagnosisSession"]
        if isinstance(active_session, DiagnosisSession):
            metrics = metrics_snapshot_provider() if callable(metrics_snapshot_provider) else MetricsSnapshot(
                active_session.request.diagnosis_id,
                active_session.request.mode,
                False,
                (),
            )
            if not isinstance(metrics, MetricsSnapshot) or metrics.diagnosis_id != active_session.request.diagnosis_id:
                metrics = MetricsSnapshot(active_session.request.diagnosis_id, active_session.request.mode, False, ())
            screen_input = metrics_snapshot_screen_input(
                metrics,
                active_session.request.symptom,
                active_session.request.requested_checks,
            )
            source = active_session.request.source
            symptom = active_session.request.symptom
        else:
            metrics = MetricsSnapshot(None, None, False, ())
            screen_input = metrics_snapshot_screen_input(
                metrics,
                "",
                ("cpu", "gpu", "memory", "disk"),
            )
            source = STANDALONE
            symptom = ""
        screen = build_symptom_screen_state(screen_input)
        display = symptom_display_state(source, symptom, metrics)
        for x, component, card in zip((70, 288, 506, 724), ("cpu", "gpu", "ram", "disk"), screen.widgets, strict=False):
            tone_color = colors[card.tone] if card.tone in {"warning", "danger"} else None
            outline = tone_color or "#d9d9d9"
            round_rect(x, 184, x + 206, 364, 12, "#ffffff", outline, 1)
            text(x + 18, 201, card.title, 16, tone_color or colors["text"], "semibold", width=170)
            draw_home_hardware_icon(x + 182, 202, component, tone_color or "#666666")
            text(x + 18, 230, card.primary, 14, tone_color or colors["text"], "semibold", width=170)
            for detail_index, detail in enumerate(card.details[:2]):
                text(x + 18, 257 + detail_index * 19, detail, 13, colors["muted"], width=170)
            text(x + 18, 302, f"상태 {card.status}", 13, tone_color or colors["muted"], "semibold")
            latest_value = float(card.history[-1]) if card.history else None
            draw_metric_wave(component, x + 18, 335, 170, latest_value, tone_color or "#555555")

        round_rect(70, 384, 930, 624, 12, "#ffffff", "#dddddd", 1)
        draw_chat_icon(111, 431, 42)
        text(153, 405, display.title, 16, colors["text"], "semibold", width=735)
        text(153, 438, display.description, 14, "#777777", "regular", width=735)
        text(153, 580, display.helper, 13, "#888888", "regular", width=735)
        initial_ready = (
            isinstance(active_session, DiagnosisSession)
            and metrics.diagnosis_id == active_session.request.diagnosis_id
            and metrics.initial_complete
        )
        if initial_ready:
            button(390, 650, 610, 704, "진단 시작", handle_symptom_action, True, disabled=bool(ui["busy"]), size=15)
        else:
            button(390, 650, 610, 704, "진단 준비 중", handle_symptom_action, True, disabled=True, size=15)
        if ui["status"]:
            text(500, 724, str(ui["status"]), 13, colors["red"], "regular", "center", width=760)

    def draw_progress_ring(x: int, y: int, progress: int) -> int:
        return canvas.create_image(x, y, image=progress_ring_photo(progress))

    def diagnosis_display_progress(
        snapshot: DiagnosisRunSnapshot,
        result: Any,
    ) -> tuple[int, bool]:
        active_session = ui.get("diagnosisSession")
        result_available = diagnosis_result_available(active_session, snapshot, result)
        smoother = ui.get("progressSmoother")
        if not isinstance(smoother, SmoothedProgress) or ui.get("progressSmootherId") != snapshot.diagnosis_id:
            smoother = SmoothedProgress()
            ui["progressSmoother"] = smoother
            ui["progressSmootherId"] = snapshot.diagnosis_id
        display_progress = smoother.update(100 if result_available else snapshot.progress, time.monotonic())
        if result_available and display_progress >= 100 and not ui.get("autoAdvanceAt"):
            ui["autoAdvanceAt"] = time.monotonic() + 1.0
        return display_progress, result_available

    def cancel_diagnosis_progress_tick() -> None:
        after_id = callback_state.get("diagnosisProgressAfterId")
        if after_id is None:
            return
        try:
            root.after_cancel(after_id)
        except tk.TclError:
            pass
        callback_state["diagnosisProgressAfterId"] = None

    def schedule_diagnosis_progress_tick() -> None:
        if (
            callback_state["closed"]
            or not callback_state["uiActive"]
            or str(ui.get("state")) != "DIAGNOSING"
            or callback_state.get("diagnosisProgressAfterId") is not None
        ):
            return
        callback_state["diagnosisProgressAfterId"] = root.after(120, diagnosis_progress_tick)

    def diagnosis_progress_tick() -> None:
        callback_state["diagnosisProgressAfterId"] = None
        if callback_state["closed"] or not root_ui_active() or str(ui.get("state")) != "DIAGNOSING":
            return
        snapshot = diagnosis_snapshot_provider() if callable(diagnosis_snapshot_provider) else ui.get("diagnosisSnapshot")
        if not isinstance(snapshot, DiagnosisRunSnapshot):
            snapshot = DiagnosisRunSnapshot()
        result = diagnosis_result_provider() if callable(diagnosis_result_provider) else ui.get("diagnosisResult")
        ui["diagnosisSnapshot"] = snapshot
        if isinstance(result, DiagnosisResult):
            ui["diagnosisResult"] = result
        display_progress, result_available = diagnosis_display_progress(snapshot, result)
        advance_at = ui.get("autoAdvanceAt")
        if advance_at and time.monotonic() >= advance_at:
            ui["autoAdvanceAt"] = None
            show_diagnosis_result()
            return
        items = ui.get("diagnosisProgressItems")
        if isinstance(items, dict):
            try:
                canvas.itemconfigure(items["ring"], image=progress_ring_photo(display_progress))
                canvas.itemconfigure(items["percent"], text=f"{display_progress}%")
                canvas.itemconfigure(
                    items["title"],
                    text="진단 완료" if result_available else "하드웨어 진단 진행 중",
                )
                canvas.itemconfigure(
                    items["detail"],
                    text="진단 작업이 완료되었습니다."
                    if result_available
                    else str(ui["status"] or diagnosis_current_task_label(snapshot)),
                )
                terminal_statuses = {"COMPLETED", "UNSUPPORTED", "FAILED", "TIMED_OUT", "CANCELLED"}
                completed_count = sum(task.status in terminal_statuses for task in snapshot.tasks)
                canvas.itemconfigure(
                    items["summary"],
                    text=f"전체 {len(snapshot.tasks)}개 · 완료 {completed_count}개",
                )
            except (KeyError, tk.TclError):
                return
        schedule_diagnosis_progress_tick()

    def draw_diagnosing() -> None:
        active_session = ui["diagnosisSession"]
        snapshot = diagnosis_snapshot_provider() if callable(diagnosis_snapshot_provider) else ui.get("diagnosisSnapshot")
        if not isinstance(snapshot, DiagnosisRunSnapshot):
            snapshot = DiagnosisRunSnapshot()
        ui["diagnosisSnapshot"] = snapshot
        symptom = (
            active_session.request.symptom
            if isinstance(active_session, DiagnosisSession) and active_session.request.symptom.strip()
            else "전달받은 증상 정보가 없습니다."
        )
        requested_checks = active_session.request.requested_checks if isinstance(active_session, DiagnosisSession) else ()
        check_labels = {
            "cpu": "CPU",
            "gpu": "GPU",
            "memory": "RAM",
            "ram": "RAM",
            "disk": "디스크",
            "cooling": "냉각",
        }
        scope = " · ".join(dict.fromkeys(check_labels.get(item, item.upper()) for item in requested_checks))
        scope_text = f"점검 범위: {scope}" if scope else "요청된 점검 범위를 확인하고 있습니다."
        current_label = diagnosis_current_task_label(snapshot)
        result = ui.get("diagnosisResult")
        display_progress, result_available = diagnosis_display_progress(snapshot, result)

        round_rect(70, 180, 930, 296, UI_CARD_RADIUS, "#ffffff", "#d7dce0", UI_CARD_BORDER_WIDTH)
        draw_chat_icon(109, 234, 42)
        text(148, 198, "전달받은 증상", 15, colors["text"], "semibold", width=735)
        text(148, 224, fitted_text(symptom, 14, 735, 2), 14, "#777777", "regular", width=735)
        text(148, 270, fitted_text(scope_text, 13, 735, 1), 13, "#888888", "regular", width=735)

        terminal_statuses = {"COMPLETED", "UNSUPPORTED", "FAILED", "TIMED_OUT", "CANCELLED"}
        completed_count = sum(task.status in terminal_statuses for task in snapshot.tasks)
        round_rect(70, 308, 930, 400, UI_CARD_RADIUS, "#ffffff", "#d7dce0", UI_CARD_BORDER_WIDTH)
        ring_item = draw_progress_ring(124, 354, display_progress)
        percent_item = text(124, 354, f"{display_progress}%", 15, colors["text"], "semibold", "center")
        title_item = text(190, 328, "진단 완료" if result_available else "하드웨어 진단 진행 중", 16, colors["text"], "semibold")
        detail = "진단 작업이 완료되었습니다." if result_available else ui["status"] or current_label
        detail_item = text(190, 354, str(detail), 14, "#777777", "regular", width=600)
        summary_item = text(190, 380, f"전체 {len(snapshot.tasks)}개 · 완료 {completed_count}개", 13, "#888888", "regular")
        ui["diagnosisProgressItems"] = {
            "ring": ring_item,
            "percent": percent_item,
            "title": title_item,
            "detail": detail_item,
            "summary": summary_item,
        }
        schedule_diagnosis_progress_tick()

        component_cards = (
            (70, "cpu", "CPU"),
            (288, "gpu", "GPU"),
            (506, "ram", "RAM"),
            (724, "disk", "디스크"),
        )
        for x, component, label in component_cards:
            status_label, tone = diagnosis_component_presentation(snapshot, component)
            color = tone_color(tone)
            fill = {"running": "#f5f9ff", "warning": "#fff9ee", "error": "#fff5f5"}.get(tone, "#ffffff")
            round_rect(x, 412, x + 206, 486, UI_CARD_RADIUS, fill, "#d7dce0", UI_CARD_BORDER_WIDTH)
            draw_hardware_icon(x + 27, 438, component, color if tone != "neutral" else "#666666")
            text(x + 50, 431, label, 15, colors["text"], "semibold", width=90)
            draw_status_icon(x + 28, 462, tone, 14)
            text(x + 50, 462, status_label, 13, color, "semibold", "w", width=130)

        round_rect(70, 498, 492, 684, UI_CARD_RADIUS, "#ffffff", "#d7dce0", UI_CARD_BORDER_WIDTH)
        text(88, 514, "검사 작업", 15, colors["text"], "semibold")
        current_index = next(
            (index for index, task in enumerate(snapshot.tasks) if task.task_id == snapshot.current_task_id),
            max(0, len([task for task in snapshot.tasks if task.status not in {"PENDING", "RUNNING"}]) - 1),
        )
        start_index = max(0, min(current_index - 1, max(0, len(snapshot.tasks) - 4)))
        checklist = snapshot.tasks[start_index:start_index + 4]
        for idx, task in enumerate(checklist):
            cy = 550 + idx * 38
            status_label, tone = diagnosis_task_presentation(task.status)
            draw_status_icon(96, cy, tone, 14)
            label = DiagnosisOrchestrator.TASK_LABELS.get(task.task_id, task.task_id)
            text(114, cy - 8, label, 13, colors["text"], "semibold", "w", width=250)
            text(114, cy + 10, f"{task.component.upper()} · {status_label}", 12, tone_color(tone), "regular", "w", width=250)

        round_rect(508, 498, 930, 684, UI_CARD_RADIUS, "#ffffff", "#d7dce0", UI_CARD_BORDER_WIDTH)
        text(526, 514, "실시간 진단 로그", 15, colors["text"], "semibold")
        visible_events = snapshot.events[-4:]
        for idx, event in enumerate(visible_events):
            cy = 550 + idx * 32
            try:
                stamp = datetime.fromisoformat(event.timestamp.replace("Z", "+00:00")).astimezone(KST).strftime("%H:%M:%S")
            except ValueError:
                stamp = "--:--:--"
            event_label, tone = diagnosis_event_presentation(event.event_type)
            draw_status_icon(532, cy, tone, 12)
            text(548, cy - 8, stamp, 11, "#888888", "regular", "w")
            text(606, cy - 8, event_label, 11, tone_color(tone), "semibold", "w", width=70)
            source_label = event.component or event.task_id or "진단"
            text(548, cy + 10, fitted_text(f"{source_label} · {event.message}", 11, 350, 1), 11, colors["text"], "regular", "w", width=350)
        if result_available:
            button(390, 696, 610, 748, "진단 결과 보기", show_diagnosis_result, True, size=15)
        elif snapshot.state in {"FAILED", "TIMED_OUT"}:
            button(390, 696, 610, 748, "진단 재시도", request_diagnosis_retry, False, size=15)
        elif snapshot.state == "CANCELLED":
            text(500, 722, "진단이 취소되었습니다.", 13, colors["muted"], "regular", "center")
        else:
            button(390, 696, 610, 748, "진단 취소", request_diagnosis_cancel, False, size=15)

    def draw_result_icon(x: int, y: int) -> None:
        photo = cached_photo(
            ("result-header-icon", 42, "#333333"),
            lambda: render_pillow_result_icon(42, "#333333"),
        )
        canvas.create_image(x, y, image=photo)

    def draw_result() -> None:
        result = ui.get("diagnosisResult")
        diagnosis = ui.get("diagnosisSnapshot")
        session = ui.get("diagnosisSession")
        round_rect(70, 180, 930, 710, UI_CARD_RADIUS, "#ffffff", "#d7dce0", UI_CARD_BORDER_WIDTH)
        draw_result_icon(112, 222)
        text(145, 214, "진단 결과", 17, colors["text"], "semibold")
        if not isinstance(result, DiagnosisResult):
            text(145, 264, "진단 결과를 불러올 수 없습니다.", 22, colors["text"], "semibold")
            text(145, 318, "저장된 측정 근거를 확인한 뒤 다시 시도하세요.", 14, "#5f6368")
            button(390, 652, 610, 694, "진단 상세", show_diagnosis_detail, False, disabled=True, size=15)
            return
        text(145, 258, fitted_text(result.title, 22, 710, 2, "semibold"), 22, colors["text"], "semibold", "nw", width=710)
        text(145, 318, fitted_text(result.summary, 14, 710, 3), 14, "#5f6368", "regular", "nw", width=710)
        is_device_configuration = result.diagnosis_type == "DEVICE_DRIVER_CONFIGURATION_ISSUE"
        if is_device_configuration:
            text(145, 382, "문제: 그래픽 장치 구성 이상", 12, colors["text"], "semibold")
            text(145, 402, "로컬 처리: Agent가 안전하게 로컬 자동 복구할 수 없음", 12, colors["muted"])
            text(145, 422, "다음 조치: 원격 AS 기사 점검 필요", 12, colors["muted"])
        else:
            recovery_label = "가능" if result.can_auto_recover else "불가"
            remote_label = "필요" if result.remote_as_recommended else "자동 판정 없음"
            text(145, 402, f"로컬 자동 복구: {recovery_label} · 원격 기사 점검: {remote_label}", 12, colors["muted"])
        line(145, 444, 855, 444)
        text(145, 460, "핵심 결과", 16, colors["text"], "semibold")
        visible_findings = [(finding.code, finding.title) for finding in result.findings[:3]]
        if not visible_findings:
            visible_findings = [
                (
                    "NO_ABNORMAL_EVIDENCE" if result.severity == "NORMAL" else "INSUFFICIENT_MEASUREMENTS",
                    "이상 근거 없음" if result.severity == "NORMAL" else "측정 정보 부족",
                )
            ]
        chip_width = 220 if len(visible_findings) <= 2 else 205
        chip_gap = 18
        chip_fill = "#effbf5" if result.severity == "NORMAL" else "#f4f4f4" if result.severity == "INFO" else "#ffeaea"
        chip_color = colors["green"] if result.severity == "NORMAL" else colors["muted"] if result.severity == "INFO" else colors["red"]
        for index, (finding_code, label) in enumerate(visible_findings):
            x1 = 145 + index * (chip_width + chip_gap)
            x2 = x1 + chip_width
            y1, y2 = 484, 518
            kind = "temp" if "TEMPERATURE" in finding_code else "fan" if "FAN" in finding_code else "warn"
            round_rect(x1, y1, x2, y2, 17, chip_fill, "")
            finding_photo = cached_photo(
                ("result-finding", kind, 24, chip_color),
                lambda kind=kind: render_finding_icon(kind, 24, chip_color),
            )
            canvas.create_image(x1 + 22, 501, image=finding_photo)
            text(x1 + 42, 501, fitted_text(label, 12, chip_width - 50, 1), 12, colors["text"], "regular", "w", width=chip_width - 50)
        line(145, 530, 855, 530)
        text(145, 546, "권장 조치", 16, colors["text"], "semibold")
        actions = tuple(result.recommended_actions[:3])
        columns = result_action_columns(len(actions))
        for index, (label, (column_left, column_right)) in enumerate(zip(actions, columns, strict=False), start=1):
            badge_x = column_left + 18
            text_width = max(80, column_right - column_left - 58)
            action_label = fitted_text(label, 12, text_width, 2)
            action_font = measurement_fonts.get((12, "regular"))
            line_height = (
                int(action_font.metrics("linespace"))
                if action_font is not None
                else 18
            )
            action_text_y, badge_center_y = result_action_vertical_layout(action_label, line_height)
            badge_photo = cached_photo(
                ("result-action-badge", 26, "#fff0ef"),
                lambda: render_number_badge(26, "#fff0ef"),
            )
            canvas.create_image(badge_x, badge_center_y, image=badge_photo)
            text(badge_x, badge_center_y + 1, str(index), 11, colors["red"], "semibold", "center")
            text(
                column_left + 48,
                action_text_y,
                action_label,
                12,
                "#333333",
                "regular",
                "nw",
                width=text_width,
            )
            if index < len(actions):
                line(column_right, 566, column_right, 610)
        if ui["status"]:
            round_rect(145, 618, 855, 642, 8, "#fff6f6", "")
            text(500, 630, fitted_text(str(ui["status"]), 12, 680, 1), 12, colors["red"], "regular", "center", width=680)
        if can_offer_as(
            result,
            diagnosis if isinstance(diagnosis, DiagnosisRunSnapshot) else None,
            session,
        ):
            button(145, 652, 340, 694, "처음으로", go_home, False, size=14)
            button(365, 652, 560, 694, "진단 상세", show_diagnosis_detail, False, size=14)
            button(585, 652, 855, 694, "원격 AS 기사 연결", connect_as, True, disabled=bool(ui["busy"]), size=14)
        else:
            button(270, 652, 485, 694, "처음으로", go_home, False, size=15)
            button(515, 652, 730, 694, "진단 상세", show_diagnosis_detail, False, size=15)

    def draw_success() -> None:
        response = ui.get("asRequest")
        payload = ui.get("asRequestPayload")
        round_rect(145, 180, 855, 675, 12, "#ffffff", "#d7dce0")
        canvas.create_oval(474, 202, 526, 254, fill=colors["green_soft"], outline="#b9efd3")
        draw_check(500, 228, 22, colors["green"], 4)
        text(500, 284, "AS 요청이 생성되었습니다", 25, colors["text"], "semibold", "center")
        text(500, 320, "전송 완료 · 진단 정보가 기사 요청서에 첨부되었습니다.", 13, colors["green"], "semibold", "center")
        text(500, 348, "개인 파일이나 문서 내용은 전송되지 않았습니다.", 12, colors["muted"], "regular", "center")
        round_rect(220, 378, 780, 558, 10, "#fafafa", "#e2e2e2")
        if isinstance(response, DiagnosisAsResponse) and isinstance(payload, DiagnosisAsRequest):
            stored_request_type = response.request_type or payload.request_type
            request_type = "기존 일반 AS 티켓 (PHYSICAL_INSPECTION)" if stored_request_type == "PHYSICAL_INSPECTION" else stored_request_type
            diagnosed_result = ui.get("diagnosisResult")
            diagnosis_problem = payload.diagnosis_title or "진단 결과 확인"
            technician_note = "원격 기사 점검 필요"
            if isinstance(diagnosed_result, DiagnosisResult) and not diagnosed_result.remote_as_recommended:
                technician_note = "사용자 요청 접수"
            detail_rows = (
                ("요청 번호", response.request_number),
                ("요청 유형", request_type),
                ("진단 문제", diagnosis_problem),
                ("기사 점검", technician_note),
                ("진단 요약", response.diagnosis_summary or payload.diagnosis_summary),
            )
            row_y = (400, 428, 456, 484, 512)
            for (label, value), y in zip(detail_rows, row_y):
                text(250, y, label, 11, colors["muted"], "semibold", "w")
                text(365, y, value, 11, colors["text"], "regular", "w", width=380)
        else:
            text(500, 465, "저장된 AS 요청 정보를 불러올 수 없습니다.", 13, colors["red"], "regular", "center")
        button(240, 585, 475, 637, "처음으로", go_home, False, size=15)
        button(525, 585, 760, 637, "웹에서 확인하기", open_created_ticket, True, size=15)
        if ui["status"]:
            text(500, 657, str(ui["status"]), 10, colors["red"], "regular", "center", width=640)

    def render() -> None:
        if ui["requestLocked"] and ui["asState"] != "asCompleted":
            active_session = ui["diagnosisSession"]
            metrics = metrics_snapshot_provider() if callable(metrics_snapshot_provider) else None
            diagnosis = diagnosis_snapshot_provider() if callable(diagnosis_snapshot_provider) else None
            result = diagnosis_result_provider() if callable(diagnosis_result_provider) else None
            if isinstance(diagnosis, DiagnosisRunSnapshot):
                ui["diagnosisSnapshot"] = diagnosis
            if isinstance(result, DiagnosisResult):
                ui["diagnosisResult"] = result
            ui["state"] = diagnosis_session_ui_state(
                active_session,
                metrics,
                diagnosis,
                result,
                diagnosis_started=bool(ui["diagnosisStarted"]),
                result_requested=bool(ui["resultRequested"]),
            )
            if ui["asState"] == "asCompleted":
                ui["state"] = "DIAGNOSIS_RESULT"
            if isinstance(diagnosis, DiagnosisRunSnapshot) and diagnosis.diagnosis_id:
                if diagnosis.state == "FAILED":
                    ui["status"] = "필수 진단 증거를 만들지 못했습니다."
                elif diagnosis.state == "TIMED_OUT":
                    ui["status"] = "전체 진단 시간이 초과되었습니다."
                elif diagnosis.state == "CANCELLED":
                    ui["status"] = "진단이 취소되었습니다."
                elif diagnosis.state in {"DIAGNOSING", "EVALUATING"}:
                    ui["status"] = ""
        # 스텝 전이 감지: 단계가 한 칸 전진하면 검은 원이 선을 타고 이동하는 애니메이션을 시작한다.
        stepper_index = {"SYMPTOM_CONFIRM": 0, "DIAGNOSING": 1, "DIAGNOSIS_RESULT": 2}.get(str(ui["state"]))
        if stepper_index is not None:
            previous_index = ui.get("stepperIndex")
            if isinstance(previous_index, int) and stepper_index == previous_index + 1:
                ui["stepAnim"] = {"from": previous_index, "to": stepper_index, "start": time.monotonic()}
            ui["stepperIndex"] = stepper_index

        if ui["state"] != "DIAGNOSING":
            cancel_diagnosis_progress_tick()
            ui.pop("diagnosisProgressItems", None)

        wave_animation["items"].clear()
        wave_animation["spinnerItems"].clear()
        canvas.delete("all")
        button_counter["value"] = 0
        draw_screen_logo()
        draw_status_controls()
        draw_stepper()
        if ui["state"] == "SYMPTOM_CONFIRM":
            draw_symptom()
        elif ui["state"] == "DIAGNOSING":
            draw_diagnosing()
        elif ui["state"] == "DIAGNOSIS_RESULT":
            if ui["asState"] == "asCompleted":
                draw_success()
            else:
                draw_result()
        else:
            draw_symptom()
        canvas.move("all", 0, -PC_AGENT_REMOVED_HEADER_HEIGHT)

    def handle_symptom_action() -> None:
        if ui["busy"]:
            return
        active_session = ui.get("diagnosisSession")
        if not isinstance(active_session, DiagnosisSession):
            return

        metrics = metrics_snapshot_provider() if callable(metrics_snapshot_provider) else None
        if (
            not isinstance(metrics, MetricsSnapshot)
            or metrics.diagnosis_id != active_session.request.diagnosis_id
            or not metrics.initial_complete
        ):
            return
        if not callable(start_diagnosis):
            ui["status"] = "진단을 시작할 수 없습니다."
            render()
            return
        ui["busy"] = True
        started_session = start_diagnosis(active_session, active_session.request.mode)
        ui["busy"] = False
        if isinstance(started_session, DiagnosisSession):
            ui["diagnosisSession"] = started_session
            ui["diagnosisStarted"] = True
            ui["resultRequested"] = False
            ui["state"] = "DIAGNOSING"
            ui["status"] = ""
        else:
            ui["status"] = "진단을 시작하지 못했습니다."
        render()

    def auto_start_initial_metrics() -> None:
        active_session = ui.get("diagnosisSession")
        if not should_auto_start_initial_metrics(active_session, bool(ui["initialMetricsRequested"])):
            if isinstance(active_session, DiagnosisSession):
                ui["initialMetricsRequested"] = True
            return
        ui["initialMetricsRequested"] = True
        if not callable(start_initial_metrics):
            ui["status"] = "하드웨어 상태 수집을 시작할 수 없습니다."
            render()
            return
        ui["busy"] = True
        started_session = start_initial_metrics(None, "LIVE")
        ui["busy"] = False
        if isinstance(started_session, DiagnosisSession):
            apply_diagnosis_session(started_session)
        else:
            ui["status"] = "하드웨어 상태 수집을 시작하지 못했습니다."
            render()

    def run_scheduled_initial_metrics_start() -> None:
        callback_state["initialMetricsAfterId"] = None
        if callback_state["closed"]:
            return
        auto_start_initial_metrics()

    def schedule_initial_metrics_start() -> None:
        if callback_state["closed"] or callback_state["initialMetricsAfterId"] is not None:
            return
        callback_state["initialMetricsAfterId"] = root.after(0, run_scheduled_initial_metrics_start)

    def show_diagnosis_result() -> None:
        cancel_diagnosis_progress_tick()
        ui["resultRequested"] = True
        render()

    def request_diagnosis_cancel() -> None:
        if callable(cancel_diagnosis) and cancel_diagnosis():
            ui["status"] = "진단 취소를 요청했습니다."
            render()

    def request_diagnosis_retry() -> None:
        if callable(retry_diagnosis) and retry_diagnosis():
            ui["status"] = "실패한 진단 작업을 다시 시작합니다."
            render()

    def show_diagnosis_detail() -> None:
        result = ui.get("diagnosisResult")
        diagnosis = ui.get("diagnosisSnapshot")
        if not isinstance(result, DiagnosisResult):
            return
        detail = format_diagnosis_result_detail(
            result,
            diagnosis if isinstance(diagnosis, DiagnosisRunSnapshot) else None,
        )
        panel = tk.Toplevel(root)
        panel.title("진단 상세")
        panel.geometry("680x520")
        panel.configure(background="#ffffff")
        apply_agent_window_icon(panel)
        tk.Label(panel, text="진단 상세", font=font(24, "semibold"), background="#ffffff", anchor="w").pack(fill="x", padx=28, pady=(28, 14))
        detail_frame = tk.Frame(panel, background="#ffffff")
        detail_frame.pack(fill="both", expand=True, padx=28)
        detail_text = tk.Text(
            detail_frame,
            font=font(13),
            foreground=colors["muted"],
            background="#ffffff",
            relief="flat",
            wrap="word",
            padx=4,
            pady=4,
        )
        scrollbar = ttk.Scrollbar(detail_frame, orient="vertical", command=detail_text.yview)
        detail_text.configure(yscrollcommand=scrollbar.set)
        detail_text.insert("1.0", detail)
        detail_text.configure(state="disabled")
        detail_text.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")
        tk.Button(panel, text="닫기", command=panel.destroy, font=font(15), background="#111111", foreground="#ffffff", relief="flat", padx=30, pady=8).pack(pady=24)
        panel.transient(root)
        panel.grab_set()

    def submit_as_request(payload: DiagnosisAsRequest) -> None:
        session = ui.get("diagnosisSession")
        result = ui.get("diagnosisResult")
        if (
            ui["busy"]
            or not isinstance(session, DiagnosisSession)
            or not isinstance(result, DiagnosisResult)
            or session.request.diagnosis_id != payload.diagnosis_id
            or result.result_id != payload.result_id
        ):
            return
        ui["busy"] = True
        ui["asState"] = "asSubmitting"
        ui["status"] = "AS 요청을 생성하고 있습니다."
        render()

        def worker() -> None:
            try:
                current_config = load_config(config_path)
                client = DiagnosisAsRequestClient(
                    current_config.api_base_url,
                    current_config.agent_token,
                    web_base_url=current_config.web_base_url,
                    device_id=current_config.device_id,
                )
                response = client.create_request(payload)

                def apply() -> None:
                    session = ui.get("diagnosisSession")
                    result = ui.get("diagnosisResult")
                    if (
                        not isinstance(session, DiagnosisSession)
                        or not isinstance(result, DiagnosisResult)
                        or session.request.diagnosis_id != payload.diagnosis_id
                        or result.result_id != payload.result_id
                    ):
                        return
                    ui["busy"] = False
                    ui["asState"] = "asCompleted"
                    ui["asRequest"] = response
                    ui["asRequestPayload"] = payload
                    ui["ticketUrl"] = response.web_url
                    ui["status"] = ""
                    render()

                schedule_ui(apply)
            except Exception as exception:
                def fail(current: Exception = exception) -> None:
                    session = ui.get("diagnosisSession")
                    result = ui.get("diagnosisResult")
                    if (
                        not isinstance(session, DiagnosisSession)
                        or not isinstance(result, DiagnosisResult)
                        or session.request.diagnosis_id != payload.diagnosis_id
                        or result.result_id != payload.result_id
                    ):
                        return
                    ui["busy"] = False
                    ui["asState"] = "asFailed"
                    ui["status"] = str(current) or "AS 요청 저장에 실패했습니다. 다시 시도해 주세요."
                    render()
                schedule_ui(fail)

        threading.Thread(target=worker, daemon=True).start()

    def schedule_ui(action: Callable[[], None]) -> None:
        try:
            if root.winfo_exists():
                root.after(0, action)
        except tk.TclError:
            return

    def go_home() -> bool:
        if ui["busy"]:
            return False
        if not callable(finish_diagnosis_session):
            ui["status"] = "진단 세션을 종료할 수 없습니다."
            render()
            return False
        try:
            finished = bool(finish_diagnosis_session())
        except Exception as exception:
            ui["status"] = str(exception) or "진단 세션 종료에 실패했습니다."
            render()
            return False
        if not finished:
            ui["status"] = "진단 세션 종료에 실패했습니다."
            render()
            return False
        cancel_diagnosis_progress_tick()
        ui["initialMetricsRequested"] = False
        apply_diagnosis_session(None)
        schedule_initial_metrics_start()
        return True

    def connect_as() -> None:
        if ui["busy"]:
            return
        session = ui.get("diagnosisSession")
        result = ui.get("diagnosisResult")
        if not isinstance(session, DiagnosisSession) or not isinstance(result, DiagnosisResult):
            ui["asState"] = "asFailed"
            ui["status"] = "완료된 진단 세션과 결과가 필요합니다."
            render()
            return
        # 뷰어가 들고 있는 세션은 진단 시작 시점(RUNNING) 스냅샷이라 완료 후에도 갱신되지 않는다.
        # 진단이 실제로 끝났으면 완료 상태로 맞춰준다 — 그러지 않으면 AS 접수가 항상 거절된다.
        snapshot = ui.get("diagnosisSnapshot")
        if (
            session.agent_state != "COMPLETED"
            and isinstance(snapshot, DiagnosisRunSnapshot)
            and snapshot.diagnosis_id == result.diagnosis_id
            and snapshot.state in {"COMPLETED", "PARTIALLY_COMPLETED"}
        ):
            session = dataclass_replace(session, agent_state="COMPLETED")
        current_config = load_config(config_path)
        try:
            payload = build_diagnosis_as_request(
                session,
                result,
                consent_accepted=True,
                expected_device_id=current_config.device_id,
            )
        except Exception as exception:
            ui["asState"] = "asFailed"
            ui["status"] = str(exception)
            render()
            return
        panel = tk.Toplevel(root)
        panel.title("진단 정보 전송 동의")
        panel.geometry("700x620")
        panel.resizable(False, False)
        # 창이 콘텐츠 자연 크기로 줄어들면(pack propagation) 하단 버튼이 창 밖으로 밀려
        # 안 보인다. 700x620을 고정해 버튼이 항상 창 안에 들어오게 한다.
        panel.pack_propagate(False)
        panel.configure(background="#ffffff")
        apply_agent_window_icon(panel)
        tk.Label(
            panel,
            text="진단 정보 전송 동의",
            font=font(22, "semibold"),
            background="#ffffff",
            foreground=colors["text"],
            anchor="w",
        ).pack(fill="x", padx=32, pady=(28, 8))
        tk.Label(
            panel,
            text="AS 접수를 위해 아래 진단 정보를 전송합니다.",
            font=font(12),
            background="#ffffff",
            foreground=colors["muted"],
            anchor="w",
        ).pack(fill="x", padx=32, pady=(0, 16))
        evidence = [
            f"{item['component'].upper()} {item['metricType']}: {item['value']}{item.get('unit') or ''}"
            for item in payload.evidence_summary
        ]
        problem_device = actual_device_problem_evidence(result)
        display_driver = matching_display_driver_evidence(result, problem_device)
        device_value = problem_device.value if problem_device is not None and isinstance(problem_device.value, dict) else {}
        driver_value = display_driver.value if display_driver is not None and isinstance(display_driver.value, dict) else {}
        measured_components = ", ".join(dict.fromkeys(
            item["component"].upper() for item in payload.evidence_summary
        )) or "측정된 구성요소 없음"
        summary = "\n".join((
            "전송 정보",
            f"• diagnosisId: {result.diagnosis_id}",
            f"• 사용자 증상: {session.request.symptom or '전달된 증상 없음'}",
            f"• 진단 분류: {result.diagnosis_type or '분류 없음'}",
            f"• 결과: {result.title}",
            f"• 요약: {result.summary}",
            f"• 해결 유형: {result.resolution_type}",
            f"• 로컬 자동 복구: {'가능' if result.can_auto_recover else '불가'}",
            f"• 장치: {device_value.get('deviceName') or '확인되지 않음'}",
            f"• problem code: {device_value.get('problemCode') if device_value.get('problemCode') is not None else '확인되지 않음'}",
            f"• 드라이버: {driver_value.get('provider') or '확인되지 않음'} / {driver_value.get('version') or '-'} / {driver_value.get('date') or '-'}",
            "• 핵심 측정 근거:",
            *(f"  - {item}" for item in evidence),
            "• 요청 유형: 기존 일반 AS 티켓 (PHYSICAL_INSPECTION)",
            f"• 진단 시각: {result.evaluated_at}",
            f"• PC 주요 구성 정보(실제 측정 구성): {measured_components}",
            f"• 진단 모드: {session.request.mode}",
            "",
            "전송하지 않는 정보",
            "• 개인 파일 내용 · 문서 내용 · 브라우저 기록",
            "• 진단과 무관한 전체 프로세스 목록 · 불필요한 개인정보",
        ))
        summary_box = tk.Text(
            panel,
            height=17,
            font=font(11),
            foreground=colors["text"],
            background="#fafafa",
            relief="solid",
            borderwidth=1,
            wrap="word",
            padx=16,
            pady=14,
        )
        summary_box.insert("1.0", summary)
        summary_box.configure(state="disabled")
        consent = tk.BooleanVar(panel, value=False)

        # 버튼과 체크박스를 창 하단에 먼저 고정한다. 그래야 진단 근거가 많아 요약 상자가
        # 길어져도 '동의 후 AS 접수' 버튼이 창 밖으로 밀리지 않는다. 요약 상자는 남은 공간만 채운다.
        action_row = tk.Frame(panel, background="#ffffff")
        action_row.pack(side="bottom", fill="x", padx=32, pady=(0, 24))
        # 접수 버튼은 action_row의 직접 자식으로 만든다. 다른 부모(panel)에서 in_= 로
        # 얹으면 배치가 불안정해 버튼이 렌더되지 않는 경우가 있었다.
        consent_button = tk.Button(
            action_row,
            text="동의 후 AS 접수",
            state="disabled",
            font=font(12, "semibold"),
            background="#111111",
            foreground="#ffffff",
            disabledforeground="#999999",
            relief="flat",
            padx=24,
            pady=9,
        )

        def approve() -> None:
            if not consent.get():
                return
            panel.destroy()
            submit_as_request(payload)

        consent_button.configure(command=approve)

        def update_consent() -> None:
            consent_button.configure(state="normal" if consent.get() else "disabled")

        tk.Checkbutton(
            panel,
            text="위 진단 정보 전송에 동의합니다.",
            variable=consent,
            command=update_consent,
            font=font(11),
            background="#ffffff",
            foreground=colors["text"],
            activebackground="#ffffff",
        ).pack(side="bottom", anchor="w", padx=32, pady=(14, 10))
        tk.Button(
            action_row,
            text="취소",
            command=panel.destroy,
            font=font(12, "semibold"),
            background="#ffffff",
            foreground=colors["text"],
            relief="solid",
            borderwidth=1,
            padx=28,
            pady=9,
        ).pack(side="left")
        consent_button.pack(side="right")
        summary_box.pack(fill="both", expand=True, padx=32, pady=(0, 4))
        panel.transient(root)
        panel.grab_set()

    def open_created_ticket() -> None:
        url = ui.get("ticketUrl")
        if not isinstance(url, str) or not url:
            ui["status"] = "웹 상세 주소를 확인할 수 없습니다."
            render()
            return
        try:
            opened = webbrowser.open(url)
        except (OSError, webbrowser.Error):
            opened = False
        if not opened:
            ui["status"] = "웹 요청 상세 페이지를 열지 못했습니다."
            render()
            return
        go_home()

    def root_ui_active() -> bool:
        try:
            return window_visibility.ui_active(
                root.state(),
                bool(root.winfo_viewable()),
                bool(root.winfo_ismapped()),
            )
        except tk.TclError:
            return False

    def focus_window() -> None:
        try:
            window_visibility.show()
            root.deiconify()
            root.state("normal")
            root.lift()
            root.attributes("-topmost", True)
            root.after(800, lambda: root.attributes("-topmost", False))
            root.focus_force()
            resume_ui_animation("focus_request")
        except tk.TclError:
            return

    def cleanup_ui_resources() -> None:
        if callback_state["closed"]:
            return
        callback_state["closed"] = True
        if animation_controller is not None:
            try:
                animation_controller.close()
            except tk.TclError:
                pass
        for callback_key in (
            "initialMetricsAfterId",
            "metricsAfterId",
            "renderAfterId",
            "diagnosisProgressAfterId",
            "visibilityAfterId",
        ):
            after_id = callback_state.get(callback_key)
            if after_id is not None:
                try:
                    root.after_cancel(after_id)
                except tk.TclError:
                    pass
                callback_state[callback_key] = None
        wave_animation["items"].clear()
        wave_animation["spinnerItems"].clear()
        fluid_wave_cache.close()
        progress_ring_photo_cache.clear()
        spinner_photo_cache.clear()
        static_photo_cache.clear()

    def destroy_window() -> None:
        cleanup_ui_resources()
        try:
            root.destroy()
        except tk.TclError:
            pass

    def close_window() -> None:
        if background_mode:
            window_visibility.hide()
            pause_ui_animation("user_close")
            root.withdraw()
        else:
            destroy_window()

    root.protocol("WM_DELETE_WINDOW", close_window)

    def flush_pending_render() -> None:
        callback_state["renderAfterId"] = None
        if callback_state["closed"] or not callback_state["renderPending"] or not root_ui_active():
            return
        callback_state["renderPending"] = False
        render()

    def queue_ui_render() -> None:
        callback_state["renderPending"] = True
        if (
            callback_state["closed"]
            or not callback_state["uiActive"]
            or callback_state["renderAfterId"] is not None
        ):
            return
        callback_state["renderAfterId"] = root.after(0, flush_pending_render)

    def apply_diagnosis_session(session: DiagnosisSession | None) -> None:
        previous = ui.get("diagnosisSession")
        previous_id = previous.request.diagnosis_id if isinstance(previous, DiagnosisSession) else None
        next_id = session.request.diagnosis_id if isinstance(session, DiagnosisSession) else None
        if previous_id != next_id:
            ui["busy"] = False
            ui["diagnosisStarted"] = False
            ui["resultRequested"] = False
            ui["diagnosisSnapshot"] = None
            ui["diagnosisResult"] = None
            ui["diagnosisReady"] = False
            ui["diagnosis"] = None
            ui["window"] = None
            ui["asState"] = "diagnosisResult"
            ui["asRequest"] = None
            ui["asRequestPayload"] = None
            ui["ticketUrl"] = None
            ui["status"] = ""
        ui["diagnosisSession"] = session
        ui["requestLocked"] = isinstance(session, DiagnosisSession)
        if isinstance(session, DiagnosisSession):
            ui["initialMetricsRequested"] = True
            ui["demo"] = session.request.mode == "DEMO"
            ui["scenarioId"] = diagnosis_demo_scenario_id(session)
            metrics = metrics_snapshot_provider() if callable(metrics_snapshot_provider) else None
            diagnosis = diagnosis_snapshot_provider() if callable(diagnosis_snapshot_provider) else None
            result = diagnosis_result_provider() if callable(diagnosis_result_provider) else None
            ui["diagnosisSnapshot"] = diagnosis
            ui["diagnosisResult"] = result
            ui["diagnosisStarted"] = bool(ui["diagnosisStarted"]) or session.agent_state in {
                "RUNNING", "COMPLETED", "FAILED"
            }
            ui["state"] = diagnosis_session_ui_state(
                session,
                metrics,
                diagnosis,
                result,
                diagnosis_started=bool(ui["diagnosisStarted"]),
                result_requested=bool(ui["resultRequested"]),
            )
        else:
            ui["demo"] = False
            ui["scenarioId"] = None
            ui["state"] = "SYMPTOM_CONFIRM"
        queue_ui_render()

    def request_focus() -> None:
        root.after(0, focus_window)

    def request_apply_session(session: DiagnosisSession | None) -> None:
        root.after(0, lambda: apply_diagnosis_session(session))

    def request_metrics_refresh() -> None:
        queue_ui_render()

    def request_initial_metrics_complete() -> None:
        queue_ui_render()

    def request_destroy() -> None:
        root.after(0, destroy_window)

    def refresh_symptom_metrics() -> None:
        callback_state["metricsAfterId"] = None
        try:
            if callback_state["closed"] or not root_ui_active():
                return
            if ui["state"] == "SYMPTOM_CONFIRM":
                render()
            schedule_metrics_refresh()
        except tk.TclError:
            callback_state["metricsAfterId"] = None

    def schedule_metrics_refresh() -> None:
        if (
            callback_state["closed"]
            or not root_ui_active()
            or callback_state["metricsAfterId"] is not None
        ):
            return
        callback_state["metricsAfterId"] = root.after(5000, refresh_symptom_metrics)

    def animate_ui_frame() -> None:
        try:
            if not root.winfo_exists():
                return
            wave_animation["frame"] = (int(wave_animation["frame"]) + 1) % max(
                FLUID_WAVE_FRAME_COUNT,
                SPINNER_FRAME_COUNT,
            )
            if ui.get("stepAnim"):
                render()
            if ui["state"] == "SYMPTOM_CONFIRM":
                for component, item in tuple(wave_animation["items"].items()):
                    state = wave_animation["states"][component]
                    state.advance()
                    bucket = state.bucket
                    frames = fluid_wave_cache.get(bucket)
                    if not frames:
                        fluid_wave_cache.request(bucket)
                        continue
                    canvas.itemconfigure(
                        item,
                        image=frames[int(wave_animation["frame"]) % len(frames)],
                    )
            for item, frames in tuple(wave_animation["spinnerItems"]):
                canvas.itemconfigure(item, image=frames[int(wave_animation["frame"]) % len(frames)])
        except tk.TclError:
            return

    animation_controller = AnimationCallbackController(
        root.after,
        root.after_cancel,
        animate_ui_frame,
        METRIC_WAVE_FRAME_MS,
    )

    def resume_ui_animation(reason: str = "window_visible") -> None:
        if callback_state["closed"] or not root_ui_active():
            return
        callback_state["uiActive"] = True
        animation_controller.resume()
        fluid_wave_cache.resume()
        schedule_metrics_refresh()
        queue_ui_render()
        if str(ui.get("state")) == "DIAGNOSING":
            schedule_diagnosis_progress_tick()

    def pause_ui_animation(reason: str = "window_hidden") -> None:
        callback_state["uiActive"] = False
        animation_controller.pause()
        fluid_wave_cache.pause()
        for callback_key in ("metricsAfterId", "renderAfterId", "diagnosisProgressAfterId"):
            after_id = callback_state.get(callback_key)
            if after_id is not None:
                try:
                    root.after_cancel(after_id)
                except tk.TclError:
                    pass
                callback_state[callback_key] = None

    def reconcile_window_visibility(reason: str) -> None:
        callback_state["visibilityAfterId"] = None
        if callback_state["closed"]:
            return
        if root_ui_active():
            resume_ui_animation(reason)
        else:
            pause_ui_animation(reason)

    def schedule_visibility_reconcile(reason: str) -> None:
        if callback_state["closed"] or callback_state["visibilityAfterId"] is not None:
            return
        callback_state["visibilityAfterId"] = root.after_idle(
            lambda: reconcile_window_visibility(reason)
        )

    def handle_root_map(event: Any) -> None:
        if not WindowVisibilityState.is_root_event(getattr(event, "widget", None), root):
            return
        window_visibility.show()
        schedule_visibility_reconcile("root_map")

    def handle_root_unmap(event: Any) -> None:
        if not WindowVisibilityState.is_root_event(getattr(event, "widget", None), root):
            return
        schedule_visibility_reconcile("root_unmap")

    root.bind("<Map>", handle_root_map, add="+")
    root.bind("<Unmap>", handle_root_unmap, add="+")

    render()
    if callable(on_window_ready):
        on_window_ready(
            request_focus,
            request_apply_session,
            request_metrics_refresh,
            request_initial_metrics_complete,
            request_destroy,
        )
    schedule_initial_metrics_start()
    resume_ui_animation("initial_start")
    try:
        root.mainloop()
    finally:
        cleanup_ui_resources()
        if callable(on_window_closed):
            on_window_closed()
        viewer_lock.release()


def open_support_page(config_path: Path) -> None:
    config = load_config(config_path)
    webbrowser.open(support_new_url(config))


def upload_event_panel_request(
    config: AgentConfig,
    source: Path,
    signals: Sequence[dict[str, Any]],
    request_mode: str | None = None,
) -> tuple[str, str]:
    selected = event_panel_signals(signals)
    if not selected:
        raise UploadError("no eligible event signal was selected for upload.")
    primary = selected[0]
    symptom_type = str(primary.get("code", ""))
    detected_at = event_panel_detected_at(primary)
    window = default_incident_window(
        symptom_type,
        detected_at=detected_at,
        trigger_type="SYSTEM_DETECTED",
        selected_by_user=True,
    )
    work_dir = config.log_dir.parent / "uploads"
    gzip_path = work_dir / f"{window.incident_id}.jsonl.gz"
    gzip_window(source, gzip_path, window)
    symptom = event_panel_symptom(selected)
    if request_mode:
        symptom = f"{request_mode} / {symptom}"
    result = upload_gzip(
        config,
        gzip_path,
        f"agent-panel-{uuid.uuid4()}",
        symptom,
        window,
    )
    ticket_id = str(result["ticketId"])
    return ticket_id, support_url(config.api_base_url, ticket_id, config.web_base_url)


class SmoothedProgress:
    """진단 진행률 링의 표시용 스무딩.

    실제 진행률은 태스크가 끝날 때만 계단식으로 뛰므로 그대로 그리면 링이
    15%→100%처럼 점프한다. 표시값은 실제값을 초당 FAST_RATE로 부드럽게
    뒤쫓되, 실제값이 정체되면 절대 증가하지 않는다. 실제 100% 도달 시에만
    100까지 마무리 스윕한다.
    """

    FAST_RATE = 45.0

    def __init__(self) -> None:
        self._display = 0.0
        self._last_seconds: float | None = None

    def update(self, actual: int, now_seconds: float) -> int:
        target = float(max(0, min(100, actual)))
        if self._last_seconds is None:
            # 진행 중 화면에 뒤늦게 합류한 경우 현재 값에서 시작한다(0부터 훑지 않음).
            self._last_seconds = now_seconds
            self._display = target
            return int(self._display)
        elapsed = max(0.0, now_seconds - self._last_seconds)
        self._last_seconds = now_seconds
        if self._display < target:
            self._display = min(target, self._display + self.FAST_RATE * elapsed)
        return int(self._display)


def status_pulse_frame(base_text: str, step: int) -> str:
    """로딩 애니메이션의 step번째 프레임 문자열. 점 0~3개가 순환한다."""
    return f"{base_text}{'.' * (step % 4)}"


class StatusPulse:
    """서버 응답을 기다리는 동안 상태 문구에 점(...)을 순환시키는 로딩 애니메이션.

    tkinter에는 내장 스피너가 없고 이 앱의 대기 표시는 전부 문자열 기반이라,
    위젯 after() 타이머로 프레임을 갱신한다. start/stop은 반드시 메인 스레드에서
    호출한다(워커 스레드에서는 after(0, ...)로 마샬링). 위젯이 파괴되면 조용히 멈춘다.
    """

    def __init__(self, widget: Any, status_target: Any, interval_ms: int = 400) -> None:
        self._widget = widget
        # tk.StringVar처럼 .set()이 있으면 그것을, 아니면 호출 가능한 setter를 그대로 쓴다.
        self._set = status_target.set if hasattr(status_target, "set") else status_target
        self._interval_ms = interval_ms
        self._job: Any = None
        self._step = 0
        self._base_text = ""

    @property
    def active(self) -> bool:
        return self._job is not None

    def start(self, base_text: str) -> None:
        self.stop()
        self._base_text = base_text
        self._step = 0
        self._tick()

    def stop(self, final_text: str | None = None) -> None:
        if self._job is not None:
            try:
                self._widget.after_cancel(self._job)
            except Exception:
                pass
            self._job = None
        if final_text is not None:
            self._set(final_text)

    def _tick(self) -> None:
        try:
            if not int(self._widget.winfo_exists()):
                self._job = None
                return
            self._set(status_pulse_frame(self._base_text, self._step))
            self._step += 1
            self._job = self._widget.after(self._interval_ms, self._tick)
        except Exception:
            # 위젯 파괴 후 늦게 도착한 tick — 애니메이션만 조용히 종료한다.
            self._job = None


def show_event_panel(config_path: Path, signals: Sequence[dict[str, Any]]) -> None:
    if tk is None or ttk is None:
        return
    config = load_config(config_path)
    source = log_file(config)
    model = event_panel_model(signals)
    if model is None:
        return

    panel = tk.Tk()
    panel.title("감지 신호")
    apply_agent_window_icon(panel)
    panel.configure(background="#f8fbfc")
    panel.resizable(False, False)
    panel.attributes("-topmost", True)
    width = 386
    height = 432
    screen_width = panel.winfo_screenwidth()
    screen_height = panel.winfo_screenheight()
    x = max(20, screen_width - width - 28)
    y = max(20, screen_height - height - 64)
    panel.geometry(f"{width}x{height}+{x}+{y}")

    status_text = tk.StringVar(value="선택한 구간의 로그를 함께 첨부해 접수합니다.")
    request_mode = tk.StringVar(value="원격 접수")
    colors = {
        "bg": "#f8fbfc",
        "card": "#ffffff",
        "line": "#dde7ea",
        "teal": "#0f7aae",
        "deep": "#17313b",
        "muted": "#5f737b",
        "soft": "#eef8f9",
        "button": "#0e7490",
        "button_hover": "#0b6178",
        "secondary": "#ffffff",
        "secondary_hover": "#f1f6f7",
    }
    ui_font_family = resolve_ui_font_family(panel)

    def ui_font(size_px: int, weight: str = "regular", underline: bool = False) -> tuple[Any, ...]:
        return tk_ui_font(ui_font_family, size_px, weight, underline)

    panel.option_add("*Font", ui_font(FONT_BODY_PX))
    container = tk.Frame(panel, background=colors["bg"], padx=12, pady=12)
    container.pack(fill="both", expand=True)
    card = tk.Frame(
        container,
        background=colors["card"],
        highlightthickness=1,
        highlightbackground=colors["line"],
        padx=14,
        pady=12,
    )
    card.pack(fill="both", expand=True)
    card.grid_columnconfigure(0, weight=1)
    card.grid_rowconfigure(0, weight=1)
    body = tk.Frame(card, background=colors["card"])
    body.grid(row=0, column=0, sticky="nsew")
    footer = tk.Frame(card, background=colors["card"])
    footer.grid(row=1, column=0, sticky="ew", pady=(10, 0))

    header = tk.Frame(body, background=colors["card"])
    header.pack(fill="x")
    icon = tk.Canvas(header, width=28, height=28, background=colors["card"], highlightthickness=0)
    icon.pack(side="left", padx=(0, 8))
    icon.create_oval(3, 3, 25, 25, fill=colors["soft"], outline="#b8e2e6")
    icon.create_text(14, 14, text="!", fill=colors["teal"], font=ui_font(FONT_BUTTON_PX, "semibold"))
    title_box = tk.Frame(header, background=colors["card"])
    title_box.pack(side="left", fill="x", expand=True)
    tk.Label(
        title_box,
        text="확인이 필요한 신호가 감지되었습니다",
        font=ui_font(FONT_SECTION_TITLE_PX, "semibold"),
        foreground=colors["deep"],
        background=colors["card"],
        anchor="w",
    ).pack(fill="x")
    tk.Label(
        title_box,
        text="로그에서 자세히 확인할 수 있습니다.",
        font=ui_font(FONT_SECONDARY_PX),
        foreground=colors["muted"],
        background=colors["card"],
        anchor="w",
    ).pack(fill="x", pady=(2, 0))
    close_label = tk.Label(
        header,
        text="×",
        font=ui_font(FONT_SECTION_TITLE_PX),
        foreground=colors["muted"],
        background=colors["card"],
        cursor="hand2",
    )
    close_label.pack(side="right", padx=(8, 0))

    meta = tk.Frame(body, background=colors["soft"], padx=10, pady=8)
    meta.pack(fill="x", pady=(12, 10))

    def add_meta_row(label: str, value: str) -> None:
        row = tk.Frame(meta, background=colors["soft"])
        row.pack(fill="x", pady=(0, 3))
        tk.Label(
            row,
            text=label,
            font=ui_font(FONT_SECONDARY_PX, "semibold"),
            foreground=colors["muted"],
            background=colors["soft"],
            anchor="w",
            width=8,
        ).pack(side="left")
        tk.Label(
            row,
            text=value,
            font=ui_font(FONT_SECONDARY_PX),
            foreground=colors["deep"],
            background=colors["soft"],
            anchor="w",
            justify="left",
            wraplength=250,
        ).pack(side="left", fill="x", expand=True)

    add_meta_row("발생 시간", str(model["detectedTime"]))
    add_meta_row("감지 신호", str(model["signalTitle"]))
    add_meta_row("전송 구간", str(model["windowText"]))

    tk.Label(
        body,
        text="PCAgent가 관련 경고 이벤트를 감지했습니다. 필요하면 자동으로 정리된 제목과 로그 구간으로 AS 접수를 진행할 수 있습니다.",
        font=ui_font(FONT_BODY_PX),
        foreground=colors["deep"],
        background=colors["card"],
        anchor="w",
        justify="left",
        wraplength=318,
    ).pack(fill="x")

    mode_row = tk.Frame(body, background=colors["card"])
    mode_row.pack(fill="x", pady=(8, 0))
    tk.Label(
        mode_row,
        text="신청 방식",
        font=ui_font(FONT_SECONDARY_PX, "semibold"),
        foreground=colors["muted"],
        background=colors["card"],
        anchor="w",
        width=8,
    ).pack(side="left")
    for label in ("원격 접수", "방문 접수"):
        tk.Radiobutton(
            mode_row,
            text=label,
            variable=request_mode,
            value=label,
            font=ui_font(FONT_SECONDARY_PX),
            foreground=colors["deep"],
            background=colors["card"],
            activebackground=colors["card"],
            selectcolor=colors["card"],
        ).pack(side="left", padx=(0, 10))

    tk.Label(
        body,
        textvariable=status_text,
        font=ui_font(FONT_SECONDARY_PX),
        foreground=colors["teal"],
        background=colors["card"],
        anchor="w",
        justify="left",
        wraplength=318,
    ).pack(fill="x", pady=(8, 8))

    upload_pulse = StatusPulse(panel, status_text)

    def request_review() -> None:
        # gzip 압축 + 20초 타임아웃 업로드가 메인 스레드를 얼리지 않도록 워커에서 수행한다.
        # tk 위젯/변수는 메인 스레드 전용 — 모드는 미리 읽고, 결과는 after(0)로 되돌린다.
        selected_mode = request_mode.get()
        send_button.configure(state="disabled")
        upload_pulse.start("AS 접수를 진행하고 있습니다")

        def apply_success(ticket_id: str, url: str) -> None:
            upload_pulse.stop(f"AS 접수가 완료되었습니다. 티켓 {ticket_id}")
            webbrowser.open(url)

        def apply_failure(exception: Exception) -> None:
            upload_pulse.stop(event_panel_failure_message(exception))
            send_button.configure(state="normal")

        def schedule_on_panel(callback: Any) -> None:
            try:
                panel.after(0, callback)
            except Exception:
                pass  # 업로드 중 패널이 닫힘 — 표시할 곳이 없다.

        def run_upload() -> None:
            try:
                ticket_id, url = upload_event_panel_request(config, source, signals, selected_mode)
            except Exception as exception:
                schedule_on_panel(lambda current=exception: apply_failure(current))
                return
            schedule_on_panel(lambda: apply_success(ticket_id, url))

        threading.Thread(target=run_upload, daemon=True).start()

    def open_detail() -> None:
        primary = model["primarySignal"]
        panel.destroy()
        show_log_viewer(config_path, focus_signal=primary)

    def close_panel() -> None:
        panel.destroy()

    close_label.bind("<Button-1>", lambda event: close_panel())

    button_row = tk.Frame(footer, background=colors["card"])
    button_row.pack(fill="x")

    def make_button(parent: tk.Frame, text: str, command: Any, primary: bool = False) -> tk.Button:
        button = tk.Button(
            parent,
            text=text,
            command=command,
            font=ui_font(FONT_BUTTON_PX, "semibold" if primary else "regular"),
            foreground="#ffffff" if primary else colors["deep"],
            background=colors["button"] if primary else colors["secondary"],
            activebackground=colors["button_hover"] if primary else colors["secondary_hover"],
            activeforeground="#ffffff" if primary else colors["deep"],
            relief="flat",
            highlightthickness=1 if not primary else 0,
            highlightbackground=colors["line"],
            padx=10,
            pady=7,
            cursor="hand2",
        )
        return button

    send_button = make_button(button_row, "접수하기", request_review, True)
    send_button.pack(side="left", fill="x", expand=True, padx=(0, 8))
    make_button(button_row, "무시하기", close_panel).pack(side="left", fill="x", expand=True)

    link = tk.Label(
        footer,
        text="로그 확인하기 ↗",
        font=ui_font(FONT_SECONDARY_PX, underline=True),
        foreground=colors["teal"],
        background=colors["card"],
        cursor="hand2",
        anchor="e",
    )
    link.pack(fill="x", pady=(8, 0))
    link.bind("<Button-1>", lambda event: open_detail())

    panel.after(900, lambda: panel.attributes("-topmost", False))
    panel.mainloop()


def show_event_panel_async(config_path: Path, signals: Sequence[dict[str, Any]]) -> None:
    import threading

    selected = list(event_panel_signals(signals))
    if not selected:
        return
    threading.Thread(target=show_event_panel, args=(config_path, selected), daemon=True).start()


def maybe_show_event_panel(config_path: Path, config: AgentConfig, runtime: AgentRuntime) -> None:
    rows = read_log_tail(log_file(config), LOG_TABLE_LIMIT)
    signals = event_panel_signals(detect_recent_signals(rows))
    signature = event_panel_signature(signals)
    if signature is None or signature == runtime.last_event_panel_signature:
        return
    runtime.last_event_panel_signature = signature
    show_event_panel_async(config_path, signals)


def is_issue_metric(row: dict) -> bool:
    event_type = str(row.get("eventType", "")).strip()
    if not event_type:
        return False
    return event_type not in {"DEMO_METRIC", "SYSTEM_METRIC", "INFO", "OK"}


def should_show_issue_notification(row: dict, runtime: AgentRuntime, now: float | None = None) -> bool:
    if not is_issue_metric(row):
        return False
    observed = time.time() if now is None else now
    if observed - runtime.last_issue_notification_at < 60:
        return False
    runtime.last_issue_notification_at = observed
    return True


def issue_macro(row: dict) -> IssueDraftMacro:
    event_type = str(row.get("eventType") or row.get("kind") or "").strip()
    payload = row.get("payload") if isinstance(row.get("payload"), dict) else {}
    message = str(row.get("message") or payload.get("message") or "").strip()
    if event_type == "DISPLAY_DRIVER_WARNING":
        return IssueDraftMacro(
            symptom_type="REMOTE_DRIVER_OS",
            title="디스플레이 드라이버 경고가 감지되었습니다",
            detail="PCAgent가 디스플레이 드라이버 관련 경고 이벤트를 감지했습니다. 선택된 구간의 로그를 함께 전송합니다.",
            symptom=f"PCAgent 자동 감지: {message or 'Display driver warning observed.'}",
            support_request_kind="REMOTE_REQUESTED",
        )
    return IssueDraftMacro(
        symptom_type="REMOTE_AGENT",
        title="PCAgent가 문제를 감지했습니다",
        detail="PCAgent가 문제 이벤트를 감지했습니다. 선택된 구간의 로그를 함께 전송합니다.",
        symptom=f"PCAgent 자동 감지: {message or event_type or 'Unknown issue'}",
        support_request_kind="DIAGNOSIS_ONLY",
    )


def issue_idempotency_key(row: dict) -> str:
    payload = json.dumps(
        {
            "timestamp": row.get("timestamp") or row.get("collectedAt"),
            "eventType": row.get("eventType") or row.get("kind"),
            "message": row.get("message"),
        },
        ensure_ascii=False,
        sort_keys=True,
    )
    return "agent-draft-" + hashlib.sha256(payload.encode("utf-8")).hexdigest()[:32]


def issue_ticket_idempotency_key(row: dict, title: str, detail: str, support_request_kind: str) -> str:
    payload = json.dumps(
        {
            "timestamp": row.get("timestamp") or row.get("collectedAt"),
            "eventType": row.get("eventType") or row.get("kind"),
            "message": row.get("message"),
            "title": title,
            "detail": detail,
            "supportRequestKind": support_request_kind,
            "target": "ticket",
        },
        ensure_ascii=False,
        sort_keys=True,
    )
    return "agent-ticket-" + hashlib.sha256(payload.encode("utf-8")).hexdigest()[:32]


def agent_launch_parts(command: str, config_path: Path, issue_file: Path) -> tuple[str, list[str]]:
    if getattr(sys, "frozen", False):
        return sys.executable, [command, "--config", str(config_path), "--issue-file", str(issue_file)]
    return sys.executable, [str(Path(__file__).resolve()), command, "--config", str(config_path), "--issue-file", str(issue_file)]


def powershell_array(values: Sequence[str]) -> str:
    return "@(" + ", ".join(powershell_string(value) for value in values) + ")"


def show_issue_notification(config_path: Path, row: dict) -> None:
    config = load_config(config_path)
    macro = issue_macro(row)
    detected_at = parse_log_timestamp(row) or datetime.now(KST)
    window = default_incident_window(macro.symptom_type, detected_at=detected_at, trigger_type="AGENT_DETECTED")
    message = str(row.get("message") or row.get("eventType") or "알 수 없는 문제가 감지되었습니다.").strip()
    if not message:
        message = "알 수 없는 문제가 감지되었습니다."
    title = DISPLAY_APP_NAME
    body = f"컴퓨터에 문제가 감지되었습니다: {message}"
    support_url_text = support_new_url(config)
    issue_file = app_data_dir() / "pending-issues" / f"issue-{uuid.uuid4()}.json"
    issue_file.parent.mkdir(parents=True, exist_ok=True)
    issue_file.write_text(json.dumps(row, ensure_ascii=False, indent=2), encoding="utf-8")
    launch_file, launch_args = agent_launch_parts("submit-issue", config_path, issue_file)
    script = f"""
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$appBg = [System.Drawing.ColorTranslator]::FromHtml("#f8fbfc")
$cardBg = [System.Drawing.ColorTranslator]::FromHtml("#ffffff")
$softBg = [System.Drawing.ColorTranslator]::FromHtml("#eef8f9")
$primaryBg = [System.Drawing.ColorTranslator]::FromHtml("#0e7490")
$borderColor = [System.Drawing.ColorTranslator]::FromHtml("#d7e0e3")
$textColor = [System.Drawing.ColorTranslator]::FromHtml("#17313b")
$mutedColor = [System.Drawing.ColorTranslator]::FromHtml("#5f737b")
$uiFontFamily = "Malgun Gothic"
$installedFonts = New-Object System.Drawing.Text.InstalledFontCollection
$installedFontNames = @($installedFonts.Families | ForEach-Object {{ $_.Name }})
foreach ($candidate in @("Segoe UI Variable", "Segoe UI", "Malgun Gothic")) {{
  if ($installedFontNames -contains $candidate) {{
    $uiFontFamily = $candidate
    break
  }}
}}

function New-UIFont {{
  param([float]$Px, [System.Drawing.FontStyle]$Style = [System.Drawing.FontStyle]::Regular)
  return New-Object System.Drawing.Font($uiFontFamily, ($Px * 72.0 / 96.0), $Style, [System.Drawing.GraphicsUnit]::Point)
}}

function Style-PopupButton {{
  param($Button, [bool]$Primary = $false)
  $Button.FlatStyle = [System.Windows.Forms.FlatStyle]::Flat
  $Button.FlatAppearance.BorderSize = 1
  $Button.FlatAppearance.BorderColor = $borderColor
  $Button.Cursor = [System.Windows.Forms.Cursors]::Hand
  $Button.Font = New-UIFont 14 ([System.Drawing.FontStyle]::Bold)
  if ($Primary) {{
    $Button.BackColor = $primaryBg
    $Button.ForeColor = [System.Drawing.Color]::White
  }} else {{
    $Button.BackColor = [System.Drawing.Color]::White
    $Button.ForeColor = $textColor
  }}
}}

$form = New-Object System.Windows.Forms.Form
$form.Text = "감지 신호"
$form.Size = New-Object System.Drawing.Size(352, 430)
$form.FormBorderStyle = [System.Windows.Forms.FormBorderStyle]::FixedDialog
$form.MaximizeBox = $false
$form.MinimizeBox = $false
$form.TopMost = $true
$form.ShowInTaskbar = $true
$form.BackColor = $appBg
$form.Font = New-UIFont 14
$screen = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$form.StartPosition = [System.Windows.Forms.FormStartPosition]::Manual
$form.Location = New-Object System.Drawing.Point(20, ($screen.Bottom - $form.Height - 20))

$iconLabel = New-Object System.Windows.Forms.Label
$iconLabel.Text = "!"
$iconLabel.TextAlign = [System.Drawing.ContentAlignment]::MiddleCenter
$iconLabel.Font = New-UIFont 14 ([System.Drawing.FontStyle]::Bold)
$iconLabel.ForeColor = $primaryBg
$iconLabel.BackColor = [System.Drawing.Color]::White
$iconLabel.BorderStyle = "FixedSingle"
$iconLabel.Location = New-Object System.Drawing.Point(18, 26)
$iconLabel.Size = New-Object System.Drawing.Size(22, 22)
$form.Controls.Add($iconLabel)

$headline = New-Object System.Windows.Forms.Label
$headline.Text = "확인이 필요한 신호가 감지되었습니다"
$headline.Location = New-Object System.Drawing.Point(54, 20)
$headline.Size = New-Object System.Drawing.Size(270, 24)
$headline.Font = New-UIFont 16 ([System.Drawing.FontStyle]::Bold)
$headline.ForeColor = $textColor
$headline.BackColor = $appBg
$form.Controls.Add($headline)

$subhead = New-Object System.Windows.Forms.Label
$subhead.Text = "로그에서 자세히 확인할 수 있습니다."
$subhead.Location = New-Object System.Drawing.Point(56, 46)
$subhead.Size = New-Object System.Drawing.Size(250, 20)
$subhead.Font = New-UIFont 12
$subhead.ForeColor = $mutedColor
$subhead.BackColor = $appBg
$form.Controls.Add($subhead)

$infoPanel = New-Object System.Windows.Forms.Panel
$infoPanel.Location = New-Object System.Drawing.Point(16, 84)
$infoPanel.Size = New-Object System.Drawing.Size(312, 96)
$infoPanel.BackColor = $softBg
$form.Controls.Add($infoPanel)

$timeCaption = New-Object System.Windows.Forms.Label
$timeCaption.Text = "발생 시간"
$timeCaption.Location = New-Object System.Drawing.Point(12, 12)
$timeCaption.Size = New-Object System.Drawing.Size(58, 18)
$timeCaption.Font = New-UIFont 12 ([System.Drawing.FontStyle]::Bold)
$timeCaption.ForeColor = $mutedColor
$timeCaption.BackColor = $softBg
$infoPanel.Controls.Add($timeCaption)

$timeLabel = New-Object System.Windows.Forms.Label
$timeLabel.Text = {powershell_string(detected_at.strftime("%Y-%m-%d %H:%M"))}
$timeLabel.Location = New-Object System.Drawing.Point(82, 12)
$timeLabel.Size = New-Object System.Drawing.Size(210, 18)
$timeLabel.ForeColor = $textColor
$timeLabel.BackColor = $softBg
$infoPanel.Controls.Add($timeLabel)

$signalCaption = New-Object System.Windows.Forms.Label
$signalCaption.Text = "감지 신호"
$signalCaption.Location = New-Object System.Drawing.Point(12, 38)
$signalCaption.Size = New-Object System.Drawing.Size(58, 18)
$signalCaption.Font = New-UIFont 12 ([System.Drawing.FontStyle]::Bold)
$signalCaption.ForeColor = $mutedColor
$signalCaption.BackColor = $softBg
$infoPanel.Controls.Add($signalCaption)

$signalLabel = New-Object System.Windows.Forms.Label
$signalLabel.Text = {powershell_string(message)}
$signalLabel.Location = New-Object System.Drawing.Point(82, 38)
$signalLabel.Size = New-Object System.Drawing.Size(210, 34)
$signalLabel.ForeColor = $textColor
$signalLabel.BackColor = $softBg
$infoPanel.Controls.Add($signalLabel)

$windowCaption = New-Object System.Windows.Forms.Label
$windowCaption.Text = "전송 구간"
$windowCaption.Location = New-Object System.Drawing.Point(12, 72)
$windowCaption.Size = New-Object System.Drawing.Size(58, 18)
$windowCaption.Font = New-UIFont 12 ([System.Drawing.FontStyle]::Bold)
$windowCaption.ForeColor = $mutedColor
$windowCaption.BackColor = $softBg
$infoPanel.Controls.Add($windowCaption)

$windowLabel = New-Object System.Windows.Forms.Label
$windowLabel.Text = {powershell_string(window.started_at.strftime("%H:%M") + " ~ " + window.ended_at.strftime("%H:%M") + f" ({window.range_minutes()}분)")}
$windowLabel.Location = New-Object System.Drawing.Point(82, 72)
$windowLabel.Size = New-Object System.Drawing.Size(210, 18)
$windowLabel.ForeColor = $textColor
$windowLabel.BackColor = $softBg
$infoPanel.Controls.Add($windowLabel)

$bodyText = New-Object System.Windows.Forms.Label
$bodyText.Text = "PCAgent가 관련 경고 이벤트를 감지했습니다. 필요하면 자동으로 정리된 제목과 로그 구간으로 AS 접수를 진행할 수 있습니다."
$bodyText.Location = New-Object System.Drawing.Point(18, 196)
$bodyText.Size = New-Object System.Drawing.Size(310, 58)
$bodyText.ForeColor = $textColor
$bodyText.BackColor = $appBg
$form.Controls.Add($bodyText)

$kindLabel = New-Object System.Windows.Forms.Label
$kindLabel.Text = "신청 방식"
$kindLabel.Location = New-Object System.Drawing.Point(18, 266)
$kindLabel.Size = New-Object System.Drawing.Size(70, 22)
$kindLabel.Font = New-UIFont 12 ([System.Drawing.FontStyle]::Bold)
$kindLabel.ForeColor = $mutedColor
$kindLabel.BackColor = $appBg
$form.Controls.Add($kindLabel)

$remoteRadio = New-Object System.Windows.Forms.RadioButton
$remoteRadio.Text = "원격 접수"
$remoteRadio.Location = New-Object System.Drawing.Point(96, 264)
$remoteRadio.Size = New-Object System.Drawing.Size(82, 24)
$remoteRadio.Checked = $true
$remoteRadio.ForeColor = $textColor
$remoteRadio.BackColor = $appBg
$form.Controls.Add($remoteRadio)

$visitRadio = New-Object System.Windows.Forms.RadioButton
$visitRadio.Text = "방문 접수"
$visitRadio.Location = New-Object System.Drawing.Point(188, 264)
$visitRadio.Size = New-Object System.Drawing.Size(82, 24)
$visitRadio.ForeColor = $textColor
$visitRadio.BackColor = $appBg
$form.Controls.Add($visitRadio)

$notice = New-Object System.Windows.Forms.Label
$notice.Text = "선택한 구간의 로그를 함께 첨부해 접수합니다."
$notice.Location = New-Object System.Drawing.Point(18, 302)
$notice.Size = New-Object System.Drawing.Size(300, 22)
$notice.Font = New-UIFont 12
$notice.ForeColor = $primaryBg
$notice.BackColor = $appBg
$form.Controls.Add($notice)

$send = New-Object System.Windows.Forms.Button
$send.Text = "AS 접수하기"
$send.Location = New-Object System.Drawing.Point(18, 340)
$send.Size = New-Object System.Drawing.Size(156, 36)
Style-PopupButton $send $true
$send.Add_Click({{
  $send.Enabled = $false
  $send.Text = "접수 중"
  $ignore.Enabled = $false
  $requestKind = "REMOTE_REQUESTED"
  if ($visitRadio.Checked) {{ $requestKind = "VISIT_REQUESTED" }}
  $args = {powershell_array(launch_args)}
  $args += @("--title", {powershell_string(macro.title)}, "--detail", {powershell_string(macro.detail)}, "--support-request-kind", $requestKind, "--no-open")
  $script:submitStdout = New-Object System.Text.StringBuilder
  $script:submitStderr = New-Object System.Text.StringBuilder
  $script:submitProcess = New-Object System.Diagnostics.Process
  $script:submitProcess.StartInfo.FileName = {powershell_string(launch_file)}
  $escapedArgs = New-Object System.Collections.Generic.List[string]
  foreach ($arg in $args) {{
    $escaped = [string]$arg
    $escaped = $escaped.Replace('"', '\"')
    [void]$escapedArgs.Add('"' + $escaped + '"')
  }}
  $script:submitProcess.StartInfo.Arguments = [string]::Join(" ", $escapedArgs)
  $script:submitProcess.StartInfo.UseShellExecute = $false
  $script:submitProcess.StartInfo.RedirectStandardOutput = $true
  $script:submitProcess.StartInfo.RedirectStandardError = $true
  $script:submitProcess.Add_OutputDataReceived({{
    if ($_.Data) {{ [void]$script:submitStdout.AppendLine($_.Data) }}
  }})
  $script:submitProcess.Add_ErrorDataReceived({{
    if ($_.Data) {{ [void]$script:submitStderr.AppendLine($_.Data) }}
  }})
  try {{
    [void]$script:submitProcess.Start()
    $script:submitProcess.BeginOutputReadLine()
    $script:submitProcess.BeginErrorReadLine()
  }} catch {{
    [System.Windows.Forms.MessageBox]::Show("AS 접수 실행에 실패했습니다.`r`n`r`n" + $_.Exception.Message, "PCAgent") | Out-Null
    $send.Enabled = $true
    $send.Text = "접수하기"
    $ignore.Enabled = $true
    return
  }}
  $script:submitTimer = New-Object System.Windows.Forms.Timer
  $script:submitTimer.Interval = 500
  $script:submitTimer.Add_Tick({{
    if (-not $script:submitProcess.HasExited) {{
      return
    }}
    $script:submitTimer.Stop()
    $stdout = $script:submitStdout.ToString().Trim()
    $stderr = $script:submitStderr.ToString().Trim()
    if ($script:submitProcess.ExitCode -eq 0) {{
      [System.Windows.Forms.MessageBox]::Show("AS 접수가 완료되었습니다.`r`n`r`n" + $stdout, "PCAgent") | Out-Null
      $form.Close()
    }} else {{
      [System.Windows.Forms.MessageBox]::Show("AS 접수에 실패했습니다.`r`n`r`n" + $stderr, "PCAgent") | Out-Null
      $send.Enabled = $true
      $send.Text = "접수하기"
      $ignore.Enabled = $true
    }}
  }})
  $script:submitTimer.Start()
}})
$form.Controls.Add($send)

$ignore = New-Object System.Windows.Forms.Button
$ignore.Text = "무시하기"
$ignore.Location = New-Object System.Drawing.Point(220, 340)
$ignore.Size = New-Object System.Drawing.Size(84, 36)
Style-PopupButton $ignore
$ignore.Add_Click({{ $form.Close() }})
$form.Controls.Add($ignore)

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 120000
$timer.Add_Tick({{ $timer.Stop(); $form.Close() }})
$timer.Start()

[void]$form.ShowDialog()
"""
    notification_path = app_data_dir() / "issue-notification.ps1"
    notification_path.parent.mkdir(parents=True, exist_ok=True)
    notification_path.write_text(script.strip() + "\n", encoding="utf-8")
    try:
        subprocess.Popen(
            [
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(notification_path),
            ],
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except Exception:
        webbrowser.open(support_url_text)


def collect_background_loop(
    config_path: Path,
    runtime: AgentRuntime,
    interval_seconds: int,
    collector: Any = None,
) -> None:
    metric_collector = collector if collector is not None else HardwareMetricCollector()
    while runtime.running:
        try:
            config = load_config(config_path)
            _, row = append_metric_with_row(config, runtime.index, metric_collector)
            if should_show_issue_notification(row, runtime):
                show_issue_notification(config_path, row)
            runtime.index += 1
            maybe_show_event_panel(config_path, config, runtime)
        except Exception as exception:
            error_log = app_data_dir() / "agent-error.log"
            error_log.parent.mkdir(parents=True, exist_ok=True)
            with error_log.open("a", encoding="utf-8") as file:
                file.write(f"{datetime.now(KST).isoformat()} {exception}\n")
        for _ in range(interval_seconds):
            if not runtime.running:
                break
            time.sleep(1)


def run_background(
    config_path: Path | None = None,
    interval_seconds: int = 5,
    with_tray: bool = True,
    open_viewer_when_running: bool = False,
) -> int:
    instance_lock = acquire_named_instance_lock(BACKGROUND_INSTANCE_MUTEX_NAME)
    if instance_lock is None:
        # 이미 실행 중인 Agent가 있으면 이 프로세스는 조용히 물러난다. 다만 새 버전을 받아 실행한
        # 경우에는 구버전이 계속 돌게 되므로, 사용자가 그 사실을 모른 채 넘어가지 않도록 알린다.
        running_version = running_agent_version()
        if running_version and running_version != DEFAULT_AGENT_VERSION:
            show_agent_error_dialog(
                "PCAgent가 이미 실행 중입니다",
                f"실행 중인 PCAgent {running_version}이(가) 있어 새 버전 {DEFAULT_AGENT_VERSION}이(가) "
                "적용되지 않았습니다.\n\n"
                "작업 표시줄 트레이의 PCAgent를 종료한 뒤 새 앱을 다시 실행해 주세요.",
            )
        if open_viewer_when_running:
            ViewerRequestSignal(
                app_data_dir() / "show-viewer-request.json",
                restrict_file_to_current_user,
            ).signal(reconnect=True)
        return 0
    try:
        path = ensure_default_config(config_path or default_background_config_path())
        try:
            imported_activation = import_activation_config(path)
            auto_register_agent(path)
            if imported_activation:
                cleanup_activation_config_files()
        except Exception as exception:
            error_log = app_data_dir() / "agent-error.log"
            error_log.parent.mkdir(parents=True, exist_ok=True)
            with error_log.open("a", encoding="utf-8") as file:
                file.write(f"{datetime.now(KST).isoformat()} auto-register failed: {exception}\n")
        register_startup()
        register_url_protocol()
        hide_console_window()
        write_pid()
        runtime = AgentRuntime()

        connection_state = {"value": "DISCONNECTED"}
        diagnosis_store = DiagnosisSessionStore(app_data_dir() / "diagnosis-request-state.json")
        metrics_store = MetricsStore(app_data_dir() / "diagnosis-metrics-state.json")
        diagnosis_log_store = DiagnosisLogStore(app_data_dir() / "diagnosis-progress-state.json")
        diagnosis_result_store = DiagnosisResultStore(app_data_dir() / "diagnosis-result-state.json")
        pending_replacement_statuses: list[dict[str, Any]] = []
        stale_replacement = diagnosis_store.expire_stale_request()
        if stale_replacement is not None and stale_replacement.session.request.source == WEB_REQUEST:
            pending_replacement_statuses.append(diagnosis_session_replacement_sync_detail(stale_replacement))
        move_terminal_session_to_idle(diagnosis_store, diagnosis_log_store.snapshot)
        diagnosis_rule_engine = DiagnosisRuleEngine()
        diagnosis_orchestrator_holder: dict[str, DiagnosisOrchestrator] = {}
        diagnosis_client_holder: dict[str, AgentDiagnosisWebSocketClient] = {}
        forwarded_event_ids: set[str] = set()
        viewer_controller = BackgroundViewerController(
            path,
            lambda: active_viewer_session(diagnosis_store.session, diagnosis_log_store.snapshot),
            lambda: connection_state["value"],
            show_log_viewer,
            metrics_snapshot_provider=lambda: metrics_store.snapshot,
            diagnosis_snapshot_provider=lambda: diagnosis_log_store.snapshot,
            diagnosis_result_provider=lambda: diagnosis_result_store.result,
            start_initial_metrics=lambda session, mode: begin_initial_metrics(session, mode),
            start_diagnosis=lambda session, mode: begin_diagnosis(session, mode),
            cancel_diagnosis=lambda: diagnosis_orchestrator_holder["value"].cancel()
            if "value" in diagnosis_orchestrator_holder else False,
            retry_diagnosis=lambda: diagnosis_orchestrator_holder["value"].retry()
            if "value" in diagnosis_orchestrator_holder else False,
            finish_diagnosis_session=lambda: finish_current_diagnosis_session(),
            request_reconnect=lambda: diagnosis_client_holder["value"].request_reconnect()
            if "value" in diagnosis_client_holder else False,
        )

        def on_connection_state_changed(state: str) -> None:
            connection_state["value"] = state
            # 연결 상태가 바뀌면 열린 뷰어를 다시 그린다 — 진단 종료 후 홈 복귀 시
            # 재접속 중 순간의 '연결 끊김'이 화면에 박제되는 문제를 막는다.
            viewer_controller.refresh_metrics()

        def on_metrics_updated(metrics: MetricsSnapshot) -> None:
            viewer_controller.refresh_metrics()

        def sync_diagnosis_events(snapshot: DiagnosisRunSnapshot) -> None:
            client = diagnosis_client_holder.get("value")
            if client is None:
                return
            for event in snapshot.events:
                if event.event_id in forwarded_event_ids:
                    continue
                detail = diagnosis_event_sync_detail(snapshot, event, diagnosis_store.session)
                if client.send_diagnosis_status(detail):
                    forwarded_event_ids.add(event.event_id)

        def on_diagnosis_updated(snapshot: DiagnosisRunSnapshot) -> None:
            sync_diagnosis_events(snapshot)
            viewer_controller.refresh_metrics()

        def sync_diagnosis_result() -> None:
            client = diagnosis_client_holder.get("value")
            result = diagnosis_result_store.result
            if client is not None and isinstance(result, DiagnosisResult):
                detail = result.to_dict()
                detail["mode"] = diagnosis_log_store.snapshot.mode
                client.send_diagnosis_result(detail)

        def on_diagnosis_complete(snapshot: DiagnosisRunSnapshot) -> None:
            initial_metrics_coordinator.stop()
            if snapshot.state in {"COMPLETED", "PARTIALLY_COMPLETED"}:
                try:
                    result = compact_result_evidence(
                        diagnosis_rule_engine.evaluate(metrics_store.snapshot, snapshot)
                    )
                    diagnosis_result_store.save(result)
                    sync_diagnosis_result()
                    diagnosis_store.update_state("COMPLETED")
                except (OSError, TypeError, ValueError):
                    diagnosis_store.update_state("FAILED")
            elif snapshot.state in {"FAILED", "TIMED_OUT", "CANCELLED"}:
                diagnosis_store.update_state(snapshot.state)
            viewer_controller.refresh_metrics()

        windows_graphics_provider = WindowsGraphicsDiagnosticsProvider(
            PowerShellJsonRunner(hidden_kwargs_provider=hidden_subprocess_kwargs),
        )
        code43_demo_graphics_provider = Code43RemoteSupportDemoGraphicsProvider()
        diagnosis_orchestrator = DiagnosisOrchestrator(
            lambda: metrics_store.snapshot,
            diagnosis_log_store,
            settings=DiagnosisSettings(
                task_weights=GRAPHICS_DIAGNOSIS_TASK_WEIGHTS,
                task_timeout_seconds=35.0,
                session_timeout_seconds=90.0,
                max_retries=1,
            ),
            task_handlers=graphics_diagnosis_task_handlers(
                lambda: diagnosis_store.session,
                lambda: metrics_store.snapshot,
                lambda: diagnosis_log_store.snapshot,
                lambda: collect_session_windows_graphics_snapshot(
                    diagnosis_store.session,
                    windows_graphics_provider.collect,
                    code43_demo_graphics_provider.collect,
                ),
            ),
            task_definitions=GRAPHICS_DIAGNOSIS_TASK_DEFINITIONS,
            task_labels=GRAPHICS_DIAGNOSIS_TASK_LABELS,
            on_update=on_diagnosis_updated,
            on_complete=on_diagnosis_complete,
        )
        diagnosis_orchestrator_holder["value"] = diagnosis_orchestrator

        def on_initial_metrics_complete(metrics: MetricsSnapshot) -> None:
            viewer_controller.complete_initial_metrics()

        initial_metrics_coordinator = InitialMetricsCoordinator(
            metrics_store,
            live_provider_factory=lambda: HardwareSensorProvider(HardwareMetricCollector()),
            demo_provider_factory=InitialDemoSensorProvider,
            on_update=on_metrics_updated,
            on_complete=on_initial_metrics_complete,
        )

        def finish_current_diagnosis_session() -> bool:
            initial_metrics_coordinator.stop()
            reset_diagnosis_session_state(
                diagnosis_store,
                metrics_store,
                diagnosis_log_store,
                diagnosis_result_store,
            )
            forwarded_event_ids.clear()
            client = diagnosis_client_holder.get("value")
            if client is not None:
                # 전송 버퍼도 함께 비운다. 남겨두면 재접속할 때마다 지난 진단 프레임을
                # 다시 보내고, 서버가 그 프레임을 거절하면 무한 재접속 루프가 된다.
                client.reset_sync_state()
                client.mark_idle()
            return True

        def begin_initial_metrics(
            session: DiagnosisSession | None,
            mode: str,
        ) -> DiagnosisSession | None:
            return start_initial_metrics_session(
                session,
                mode,
                current_config.device_id if current_config else None,
                diagnosis_store,
                metrics_store,
                diagnosis_result_store,
                diagnosis_orchestrator,
                initial_metrics_coordinator,
            )

        def begin_diagnosis(
            session: DiagnosisSession | None,
            mode: str,
        ) -> DiagnosisSession | None:
            return start_diagnosis_once(
                session,
                diagnosis_store,
                metrics_store,
                diagnosis_orchestrator,
                diagnosis_result_store,
            )

        def on_diagnosis_request(session: DiagnosisSession) -> None:
            viewer_controller.show(session)
            begin_initial_metrics(session, session.request.mode)

        def on_session_replaced(replacement: DiagnosisSessionReplacement) -> None:
            initial_metrics_coordinator.stop()
            if replacement.session.request.source != WEB_REQUEST:
                return
            detail = diagnosis_session_replacement_sync_detail(replacement)
            client = diagnosis_client_holder.get("value")
            if client is None or not client.send_diagnosis_status(detail):
                pending_replacement_statuses.append(detail)

        try:
            current_config = load_config(path)
        except ConfigError:
            current_config = None
        diagnosis_processor = DiagnosisRequestProcessor(
            diagnosis_store,
            device_id=current_config.device_id if current_config else None,
            on_request=on_diagnosis_request,
            on_session_replaced=on_session_replaced,
        )
        existing_session = diagnosis_store.session
        existing_metrics = metrics_store.snapshot
        if isinstance(existing_session, DiagnosisSession) and existing_session.agent_state == "RUNNING":
            if (
                existing_metrics.diagnosis_id == existing_session.request.diagnosis_id
                and existing_metrics.initial_complete
            ):
                existing_diagnosis = diagnosis_log_store.snapshot
                if existing_diagnosis.diagnosis_id == existing_session.request.diagnosis_id and existing_diagnosis.state in FINAL_SESSION_STATES:
                    on_diagnosis_complete(existing_diagnosis)
                else:
                    diagnosis_orchestrator.prepare(
                        existing_session.request.diagnosis_id,
                        existing_session.request.mode,
                        existing_session.request.requested_checks,
                    )
                    diagnosis_orchestrator.start(
                        existing_session.request.diagnosis_id,
                        existing_session.request.mode,
                        existing_session.request.requested_checks,
                    )
        elif (
            isinstance(existing_session, DiagnosisSession)
            and existing_session.agent_state == "REQUEST_RECEIVED"
            and (
                existing_metrics.diagnosis_id != existing_session.request.diagnosis_id
                or not existing_metrics.initial_complete
            )
        ):
            begin_initial_metrics(existing_session, existing_session.request.mode)
        diagnosis_client = None
        if current_config and current_config.agent_token:
            def sync_diagnosis_state() -> None:
                client = diagnosis_client_holder.get("value")
                if client is not None:
                    for detail in tuple(pending_replacement_statuses):
                        if client.send_diagnosis_status(detail):
                            pending_replacement_statuses.remove(detail)
                sync_diagnosis_events(diagnosis_log_store.snapshot)
                sync_diagnosis_result()

            diagnosis_client = AgentDiagnosisWebSocketClient(
                current_config.api_base_url,
                current_config.agent_token,
                diagnosis_processor,
                on_state_changed=on_connection_state_changed,
                on_ready=sync_diagnosis_state,
            )
            diagnosis_client_holder["value"] = diagnosis_client
            diagnosis_client.start()
        else:
            on_connection_state_changed("FAILED")

        import threading

        worker = threading.Thread(target=collect_background_loop, args=(path, runtime, interval_seconds), daemon=True)
        worker.start()
        viewer_signal = ViewerRequestSignal(
            app_data_dir() / "show-viewer-request.json",
            restrict_file_to_current_user,
        )
        viewer_signal_worker = threading.Thread(
            target=viewer_signal.monitor,
            args=(runtime, viewer_controller),
            daemon=True,
        )
        viewer_signal_worker.start()

        if open_viewer_when_running:
            viewer_controller.show()

        if with_tray and pystray is not None:
            def stop(icon: object, item: object = None) -> None:
                runtime.stop()
                initial_metrics_coordinator.stop()
                if diagnosis_client is not None:
                    diagnosis_client.stop()
                viewer_controller.shutdown()
                remove_pid()
                icon.stop()

            icon = pystray.Icon(
                APP_NAME,
                create_tray_image(),
                DISPLAY_APP_NAME,
                menu=pystray.Menu(
                    pystray.MenuItem("PC Agent 열기", lambda icon, item: viewer_controller.show(), default=True),
                    pystray.MenuItem("로그 폴더 열기", lambda icon, item: open_log_folder(path)),
                    pystray.MenuItem("AS 페이지 열기", lambda icon, item: open_support_page(path)),
                    pystray.MenuItem("Agent 종료", stop),
                ),
            )
            icon.run()
        else:
            try:
                while runtime.running:
                    time.sleep(1)
            except KeyboardInterrupt:
                runtime.stop()

        runtime.stop()
        initial_metrics_coordinator.stop()
        if diagnosis_client is not None:
            diagnosis_client.stop()
        viewer_controller.shutdown()
        remove_pid()
        return 0
    finally:
        instance_lock.release()


def upload_recent(
    config: AgentConfig,
    work_dir: Path,
    symptom: str | None,
    idempotency_key: str | None,
    open_browser: bool,
    incident_window: IncidentWindow | None = None,
) -> None:
    source = log_file(config)
    if not source.exists():
        raise AgentError(f"log file does not exist: {source}")
    key = idempotency_key or f"agent-upload-{uuid.uuid4()}"
    window = incident_window or default_incident_window("REMOTE_AGENT")
    gzip_path = work_dir / f"{window.incident_id}.jsonl.gz"
    size = gzip_window(source, gzip_path, window)
    print(f"created gzip: {gzip_path} ({size} bytes)")
    print(f"upload path: {LOG_UPLOAD_PATH}")
    print(f"incidentId: {window.incident_id}")
    print(f"symptomType: {window.symptom_type}")
    print(f"window: {window.started_at.isoformat()} -> {window.ended_at.isoformat()}")
    print(f"rangeMinutes: {window.range_minutes()}")
    print(f"Idempotency-Key: {key}")
    print("Replay the same command with this Idempotency-Key to verify duplicate ticket prevention.")
    result = upload_gzip(config, gzip_path, key, symptom, window)
    ticket_id = str(result["ticketId"])
    url = support_url(config.api_base_url, ticket_id, config.web_base_url)
    print(f"ticketId: {ticket_id}")
    print(f"supportUrl: {url}")
    if open_browser:
        webbrowser.open(url)
        print("opened support ticket in default browser")


def open_issue_draft(config_path: Path, issue_file: Path, open_browser: bool = True) -> dict:
    config = load_config(config_path)
    try:
        row = json.loads(issue_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise AgentError(f"issue file cannot be read: {issue_file}") from exception
    if not isinstance(row, dict):
        raise AgentError("issue file root must be a JSON object.")

    macro = issue_macro(row)
    detected_at = parse_log_timestamp(row) or datetime.now(KST)
    window = default_incident_window(
        macro.symptom_type,
        detected_at=detected_at,
        trigger_type="AGENT_DETECTED",
        incident_id=f"incident-{uuid.uuid4()}",
        selected_by_user=False,
        consent_id=f"agent-consent-{uuid.uuid4()}",
    )
    work_dir = app_data_dir() / "draft-uploads"
    gzip_path = work_dir / f"{window.incident_id}.jsonl.gz"
    size = gzip_window(log_file(config), gzip_path, window)
    key = issue_idempotency_key(row)
    print(f"created draft gzip: {gzip_path} ({size} bytes)")
    print(f"draft upload path: {AS_DRAFT_PATH}")
    print(f"incidentId: {window.incident_id}")
    print(f"detectedAt: {window.detected_at.isoformat()}")
    print(f"window: {window.started_at.isoformat()} -> {window.ended_at.isoformat()}")
    print(f"Idempotency-Key: {key}")
    result = create_as_draft(config, gzip_path, key, macro, window)
    draft_id = str(result["draftId"])
    url = support_draft_url(config, draft_id)
    print(f"draftId: {draft_id}")
    print(f"supportDraftUrl: {url}")
    if open_browser:
        webbrowser.open(url)
        print("opened support draft in default browser")
    return result


def submit_issue_ticket(
    config_path: Path,
    issue_file: Path,
    title: str | None = None,
    detail: str | None = None,
    support_request_kind: str | None = None,
    open_browser: bool = False,
) -> dict:
    config = load_config(config_path)
    try:
        row = json.loads(issue_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise AgentError(f"issue file cannot be read: {issue_file}") from exception
    if not isinstance(row, dict):
        raise AgentError("issue file root must be a JSON object.")

    macro = issue_macro(row)
    detected_at = parse_log_timestamp(row) or datetime.now(KST)
    window = default_incident_window(
        macro.symptom_type,
        detected_at=detected_at,
        trigger_type="AGENT_CONFIRMED",
        incident_id=f"incident-{uuid.uuid4()}",
        selected_by_user=True,
        consent_id=f"agent-consent-{uuid.uuid4()}",
    )
    resolved_title = (title or macro.title).strip() or macro.title
    resolved_detail = (detail or macro.detail).strip() or macro.detail
    resolved_kind = (support_request_kind or macro.support_request_kind).strip() or macro.support_request_kind
    symptom = "\n".join(
        [
            f"[증상 제목] {resolved_title}",
            f"[증상 상세] {resolved_detail}",
            f"[감지 이벤트] {macro.symptom}",
            f"[신청 방식] {resolved_kind}",
            f"[발생 시각] {window.detected_at.isoformat()}",
            f"[선택 구간] {window.started_at.isoformat()} ~ {window.ended_at.isoformat()}",
        ]
    )
    work_dir = app_data_dir() / "ticket-uploads"
    gzip_path = work_dir / f"{window.incident_id}.jsonl.gz"
    size = gzip_window(log_file(config), gzip_path, window)
    key = issue_ticket_idempotency_key(row, resolved_title, resolved_detail, resolved_kind)
    print(f"created gzip: {gzip_path} ({size} bytes)")
    print(f"upload path: {LOG_UPLOAD_PATH}")
    print(f"incidentId: {window.incident_id}")
    print(f"detectedAt: {window.detected_at.isoformat()}")
    print(f"window: {window.started_at.isoformat()} -> {window.ended_at.isoformat()}")
    print(f"Idempotency-Key: {key}")
    result = upload_gzip(config, gzip_path, key, symptom, window)
    ticket_id = str(result["ticketId"])
    url = support_url(config.api_base_url, ticket_id, config.web_base_url)
    print(f"ticketId: {ticket_id}")
    print(f"supportUrl: {url}")
    if open_browser:
        webbrowser.open(url)
        print("opened support ticket in default browser")
    return result


def main(argv: Sequence[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        try:
            return run_background(open_viewer_when_running=True)
        except AgentError as exception:
            show_agent_error_dialog("PCAgent 실행 실패", str(exception))
            return 4

    parser = argparse.ArgumentParser(description="PCAgent prototype CLI")
    sub = parser.add_subparsers(dest="command", required=True)

    sample = sub.add_parser("sample", help="generate sample JSONL hardware metrics")
    sample.add_argument("--out", type=Path, default=Path("sample-agent-log.jsonl"))
    sample.add_argument("--count", type=int, default=24)
    sample.add_argument("--interval-seconds", type=int, default=5)

    export = sub.add_parser("export", help="export recent JSONL rows")
    export.add_argument("--source", type=Path, required=True)
    export.add_argument("--out", type=Path, default=Path("incident-window.jsonl"))
    export.add_argument("--minutes", type=int, default=30)
    export.add_argument("--symptom-type", default=None)
    export.add_argument("--detected-at", default=None)
    export.add_argument("--started-at", default=None)
    export.add_argument("--ended-at", default=None)
    export.add_argument("--trigger-type", default="USER_REQUEST")
    export.add_argument("--incident-id", default=None)
    export.add_argument("--consent-id", default=None)

    status = sub.add_parser("status", help="read config and print registration state")
    status.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)

    doctor = sub.add_parser("doctor", help="validate config without registering or uploading")
    doctor.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)

    register = sub.add_parser("register", help="register this device and save the returned agent token")
    register.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)

    collect = sub.add_parser("collect", help="append hardware metrics every 5 seconds")
    collect.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    collect.add_argument("--iterations", type=int, default=1, help="number of hardware metric rows to append; use 0 for forever")
    collect.add_argument("--interval-seconds", type=int, default=5)

    upload = sub.add_parser("upload", help="gzip selected incident-window JSONL rows and upload to Agent AS API")
    upload.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    upload.add_argument("--work-dir", type=Path, default=Path("out"))
    upload.add_argument("--symptom", default=None)
    upload.add_argument("--symptom-type", default="REMOTE_AGENT")
    upload.add_argument("--detected-at", default=None)
    upload.add_argument("--started-at", default=None)
    upload.add_argument("--ended-at", default=None)
    upload.add_argument("--trigger-type", default="USER_REQUEST")
    upload.add_argument("--incident-id", default=None)
    upload.add_argument("--consent-id", default=None)
    upload.add_argument("--system-detected", action="store_true")
    upload.add_argument("--idempotency-key", default=None)
    upload.add_argument("--no-open", action="store_true", help="do not open /support/{ticketId} in the default browser")

    background = sub.add_parser("run-background", help="run as a startup-friendly background tray agent")
    background.add_argument("--config", type=Path, default=None)
    background.add_argument("--interval-seconds", type=int, default=5)
    background.add_argument("--no-tray", action="store_true")

    viewer = sub.add_parser("viewer", help="open the Tkinter status home and log viewer")
    viewer.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)

    open_issue = sub.add_parser("open-issue", help="upload detected issue logs as an AS draft and open the support form")
    open_issue.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    open_issue.add_argument("--issue-file", type=Path, required=True)
    open_issue.add_argument("--no-open", action="store_true")

    submit_issue = sub.add_parser("submit-issue", help="submit detected issue logs as an AS ticket after user confirmation")
    submit_issue.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    submit_issue.add_argument("--issue-file", type=Path, required=True)
    submit_issue.add_argument("--title", default=None)
    submit_issue.add_argument("--detail", default=None)
    submit_issue.add_argument("--support-request-kind", default=None)
    submit_issue.add_argument("--no-open", action="store_true")

    args = parser.parse_args(argv)

    try:
        if args.command == "sample":
            write_sample(args.out, args.count, args.interval_seconds)
            print(f"wrote {args.out}")
        elif args.command == "export":
            if args.symptom_type:
                window = build_incident_window(
                    args.symptom_type,
                    args.detected_at,
                    args.started_at,
                    args.ended_at,
                    args.trigger_type,
                    args.incident_id,
                    True,
                    args.consent_id,
                )
                export_window(args.source, args.out, window)
                print(f"incidentId: {window.incident_id}")
                print(f"window: {window.started_at.isoformat()} -> {window.ended_at.isoformat()}")
            else:
                export_recent(args.source, args.out, args.minutes)
            print(f"exported {args.out}")
        elif args.command == "status":
            print_status(args.config)
        elif args.command == "doctor":
            print_doctor(args.config)
        elif args.command == "register":
            register_agent(args.config)
        elif args.command == "collect":
            config = load_config(args.config)
            iterations = None if args.iterations == 0 else args.iterations
            collect_metrics(config, iterations, args.interval_seconds)
        elif args.command == "upload":
            config = load_config(args.config)
            window = build_incident_window(
                args.symptom_type,
                args.detected_at,
                args.started_at,
                args.ended_at,
                args.trigger_type,
                args.incident_id,
                not args.system_detected,
                args.consent_id,
            )
            upload_recent(config, args.work_dir, args.symptom, args.idempotency_key, not args.no_open, window)
        elif args.command == "run-background":
            return run_background(args.config, args.interval_seconds, not args.no_tray)
        elif args.command == "viewer":
            show_log_viewer(args.config)
        elif args.command == "open-issue":
            open_issue_draft(args.config, args.issue_file, not args.no_open)
        elif args.command == "submit-issue":
            submit_issue_ticket(
                args.config,
                args.issue_file,
                title=args.title,
                detail=args.detail,
                support_request_kind=args.support_request_kind,
                open_browser=not args.no_open,
            )
    except ConfigError as exception:
        print(f"config error: {exception}", file=sys.stderr)
        return 2
    except RegisterError as exception:
        print(f"register error: {exception}", file=sys.stderr)
        return 3
    except AgentError as exception:
        print(f"agent error: {exception}", file=sys.stderr)
        return 4
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
