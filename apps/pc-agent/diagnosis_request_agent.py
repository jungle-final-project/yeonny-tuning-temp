from __future__ import annotations

import json
import threading
import time
import uuid
from collections import OrderedDict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlparse, urlunparse

try:
    import websocket
except Exception:  # pragma: no cover - optional until packaged dependencies are installed
    websocket = None


AGENT_STATES = (
    "DISCONNECTED",
    "CONNECTING",
    "IDLE",
    "REQUEST_RECEIVED",
    "RUNNING",
    "COMPLETED",
    "FAILED",
    "CANCELLED",
    "TIMED_OUT",
)
ACTIVE_DIAGNOSIS_STATES = {"REQUEST_RECEIVED", "RUNNING"}
TERMINAL_AGENT_STATES = {"COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"}
REQUEST_RESPONSE_STATUSES = {
    "ACCEPTED",
    "DUPLICATE",
    "EXPIRED",
    "DEVICE_MISMATCH",
    "AUTH_FAILED",
    "BUSY",
    "REJECTED",
}
DIAGNOSIS_SOCKET_PATH = "/ws/pc-agent/diagnosis"
PROCESSED_DIAGNOSIS_LIMIT = 200
MAX_SYNC_STATUS_FRAMES = 200
MAX_SYNC_RESULT_FRAMES = 20
WEB_REQUEST = "WEB_REQUEST"
STANDALONE = "STANDALONE"


def parse_server_datetime(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    fraction_start = text.find(".")
    if fraction_start >= 0:
        offset_positions = (
            position
            for position in (text.find("+", fraction_start), text.find("-", fraction_start))
            if position >= 0
        )
        fraction_end = min(offset_positions, default=len(text))
        fraction = text[fraction_start + 1:fraction_end]
        if fraction.isdigit() and len(fraction) > 6:
            text = text[:fraction_start + 1] + fraction[:6] + text[fraction_end:]
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def diagnosis_websocket_url(api_base_url: str) -> str:
    parsed = urlparse(api_base_url.rstrip("/"))
    scheme = "wss" if parsed.scheme == "https" else "ws"
    return urlunparse((scheme, parsed.netloc, DIAGNOSIS_SOCKET_PATH, "", "", ""))


@dataclass(frozen=True)
class DiagnosisRequest:
    diagnosis_id: str
    device_id: str
    symptom: str
    requested_checks: tuple[str, ...]
    requested_at: str
    expires_at: str
    mode: str
    source: str = WEB_REQUEST

    @classmethod
    def from_payload(
        cls,
        payload: dict[str, Any],
        source: str = WEB_REQUEST,
    ) -> "DiagnosisRequest":
        requested_checks = payload.get("requestedChecks")
        if not isinstance(requested_checks, list) or not all(
            isinstance(value, str) and value.strip() for value in requested_checks
        ):
            raise ValueError("requestedChecks must be a non-empty string list")
        values = {
            "diagnosis_id": payload.get("diagnosisId"),
            "device_id": payload.get("deviceId"),
            "requested_at": payload.get("requestedAt"),
            "expires_at": payload.get("expiresAt"),
            "mode": payload.get("mode"),
        }
        if any(not isinstance(value, str) or not value.strip() for value in values.values()):
            raise ValueError("diagnosis request has a missing text field")
        symptom = payload.get("symptom")
        if not isinstance(symptom, str) or (source != STANDALONE and not symptom.strip()):
            raise ValueError("diagnosis request has a missing symptom")
        mode = str(values["mode"]).strip().upper()
        if mode not in {"LIVE", "DEMO"}:
            raise ValueError("mode must be LIVE or DEMO")
        if parse_server_datetime(values["requested_at"]) is None or parse_server_datetime(values["expires_at"]) is None:
            raise ValueError("requestedAt and expiresAt must be ISO-8601 timestamps")
        return cls(
            diagnosis_id=str(values["diagnosis_id"]).strip(),
            device_id=str(values["device_id"]).strip(),
            symptom=symptom.strip(),
            requested_checks=tuple(value.strip().lower() for value in requested_checks),
            requested_at=str(values["requested_at"]).strip(),
            expires_at=str(values["expires_at"]).strip(),
            mode=mode,
            source=source if source in {WEB_REQUEST, STANDALONE} else WEB_REQUEST,
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "diagnosisId": self.diagnosis_id,
            "deviceId": self.device_id,
            "symptom": self.symptom,
            "requestedChecks": list(self.requested_checks),
            "requestedAt": self.requested_at,
            "expiresAt": self.expires_at,
            "mode": self.mode,
            "source": self.source,
        }


@dataclass(frozen=True)
class DiagnosisSession:
    request: DiagnosisRequest
    agent_state: str = "REQUEST_RECEIVED"

    def to_dict(self) -> dict[str, Any]:
        return {"agentState": self.agent_state, "request": self.request.to_dict()}

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> "DiagnosisSession":
        request = payload.get("request")
        state = payload.get("agentState")
        if not isinstance(request, dict) or state not in AGENT_STATES:
            raise ValueError("invalid diagnosis session")
        source = request.get("source")
        return cls(
            DiagnosisRequest.from_payload(
                request,
                source=source if source in {WEB_REQUEST, STANDALONE} else WEB_REQUEST,
            ),
            str(state),
        )


@dataclass(frozen=True)
class DiagnosisSessionReplacement:
    session: DiagnosisSession
    reason: str


@dataclass(frozen=True)
class DiagnosisDecision:
    status: str
    diagnosis_id: str | None
    message: str
    session: DiagnosisSession | None = None

    def response_frame(self) -> dict[str, Any]:
        frame: dict[str, Any] = {
            "type": "DIAGNOSIS_RESPONSE",
            "status": self.status,
            "message": self.message,
        }
        if self.diagnosis_id:
            frame["diagnosisId"] = self.diagnosis_id
        return frame


class DiagnosisSessionStore:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._lock = threading.Lock()
        self._processed_ids: list[str] = []
        self._session: DiagnosisSession | None = None
        self._load()

    @property
    def session(self) -> DiagnosisSession | None:
        with self._lock:
            return self._session

    def contains(self, diagnosis_id: str) -> bool:
        with self._lock:
            return diagnosis_id in self._processed_ids

    def is_busy(self, now: datetime | None = None) -> bool:
        with self._lock:
            if self._session is None:
                return False
            if self._session.agent_state == "RUNNING":
                return True
            if self._session.agent_state != "REQUEST_RECEIVED" or self._session.request.source == STANDALONE:
                return False
            expires_at = parse_server_datetime(self._session.request.expires_at)
            current_time = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
            return expires_at is not None and expires_at > current_time

    def expire_stale_request(self, now: datetime | None = None) -> DiagnosisSessionReplacement | None:
        with self._lock:
            current = self._session
            if current is None or current.agent_state != "REQUEST_RECEIVED":
                return None
            expires_at = parse_server_datetime(current.request.expires_at)
            current_time = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
            if expires_at is not None and expires_at > current_time:
                return None
            self._session = None
            self._save_locked()
            return DiagnosisSessionReplacement(current, "REQUEST_EXPIRED")

    def accept_request(
        self,
        session: DiagnosisSession,
        now: datetime,
    ) -> tuple[str, DiagnosisSessionReplacement | None, DiagnosisSession | None]:
        with self._lock:
            if session.request.diagnosis_id in self._processed_ids:
                return "DUPLICATE", None, None
            current = self._session
            replacement = None
            if current is not None and current.agent_state == "RUNNING":
                return "BUSY", None, current
            if current is not None and current.agent_state == "REQUEST_RECEIVED":
                expires_at = parse_server_datetime(current.request.expires_at)
                reason = "REQUEST_EXPIRED" if expires_at is None or expires_at <= now else "SUPERSEDED"
                replacement = DiagnosisSessionReplacement(current, reason)
            self._session = session
            self._processed_ids.append(session.request.diagnosis_id)
            self._processed_ids = self._processed_ids[-PROCESSED_DIAGNOSIS_LIMIT:]
            self._save_locked()
            return "ACCEPTED", replacement, None

    def accept(self, session: DiagnosisSession) -> None:
        with self._lock:
            self._session = session
            if session.request.diagnosis_id not in self._processed_ids:
                self._processed_ids.append(session.request.diagnosis_id)
                self._processed_ids = self._processed_ids[-PROCESSED_DIAGNOSIS_LIMIT:]
            self._save_locked()

    def update_state(self, state: str) -> None:
        if state not in AGENT_STATES:
            raise ValueError(f"unsupported Agent state: {state}")
        with self._lock:
            if self._session is None:
                return
            self._session = DiagnosisSession(self._session.request, state)
            self._save_locked()

    def clear_current(self) -> None:
        with self._lock:
            self._session = None
            self._save_locked()

    def _load(self) -> None:
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return
        if not isinstance(payload, dict):
            return
        processed = payload.get("processedDiagnosisIds")
        if isinstance(processed, list):
            self._processed_ids = [value for value in processed if isinstance(value, str)][-PROCESSED_DIAGNOSIS_LIMIT:]
        session = payload.get("currentSession")
        if isinstance(session, dict):
            try:
                self._session = DiagnosisSession.from_dict(session)
            except ValueError:
                self._session = None

    def _save_locked(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        payload = {
            "processedDiagnosisIds": self._processed_ids,
            "currentSession": self._session.to_dict() if self._session else None,
        }
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(self.path)


class DiagnosisRequestProcessor:
    def __init__(
        self,
        store: DiagnosisSessionStore,
        device_id: str | None = None,
        now: Callable[[], datetime] | None = None,
        on_request: Callable[[DiagnosisSession], None] | None = None,
        on_session_replaced: Callable[[DiagnosisSessionReplacement], None] | None = None,
    ) -> None:
        self.store = store
        self.device_id = device_id
        self.now = now or (lambda: datetime.now(timezone.utc))
        self.on_request = on_request or (lambda session: None)
        self.on_session_replaced = on_session_replaced or (lambda replacement: None)

    def bind_authenticated_device(self, device_id: str) -> bool:
        value = device_id.strip()
        if not value:
            return False
        if self.device_id and self.device_id != value:
            return False
        self.device_id = value
        return True

    def process(self, payload: dict[str, Any], authenticated: bool) -> DiagnosisDecision:
        diagnosis_id = payload.get("diagnosisId") if isinstance(payload.get("diagnosisId"), str) else None
        if not authenticated:
            return DiagnosisDecision("AUTH_FAILED", diagnosis_id, "인증되지 않은 요청입니다.")
        try:
            request = DiagnosisRequest.from_payload(payload)
        except ValueError as error:
            return DiagnosisDecision("REJECTED", diagnosis_id, str(error))
        if not self.device_id or request.device_id != self.device_id:
            return DiagnosisDecision("DEVICE_MISMATCH", request.diagnosis_id, "요청 장치가 현재 Agent와 일치하지 않습니다.")
        if self.store.contains(request.diagnosis_id):
            return DiagnosisDecision("DUPLICATE", request.diagnosis_id, "이미 처리한 진단 요청입니다.")
        expires_at = parse_server_datetime(request.expires_at)
        current_time = self.now().astimezone(timezone.utc)
        if expires_at is None or expires_at <= current_time:
            return DiagnosisDecision("EXPIRED", request.diagnosis_id, "만료된 진단 요청입니다.")
        session = DiagnosisSession(request=request)
        status, replacement, active = self.store.accept_request(session, current_time)
        if status == "DUPLICATE":
            return DiagnosisDecision("DUPLICATE", request.diagnosis_id, "이미 처리한 진단 요청입니다.")
        if status == "BUSY":
            active_id = active.request.diagnosis_id if active is not None else ""
            message = f"현재 진단({active_id})이 진행 중입니다. 완료 후 다시 시도해 주세요."
            return DiagnosisDecision("BUSY", request.diagnosis_id, message)
        if replacement is not None:
            self.on_session_replaced(replacement)
        self.on_request(session)
        return DiagnosisDecision("ACCEPTED", request.diagnosis_id, "진단 요청을 수신했습니다.", session)


class AgentDiagnosisWebSocketClient:
    BACKOFF_SECONDS = (1, 2, 5, 10, 30)
    # 서버가 프레임을 거절해 연결이 끊기는 경우, 같은 프레임을 영원히 재전송하지 않는다.
    MAX_FRAME_SEND_FAILURES = 3
    # 이보다 짧게 살고 끊긴 연결은 '정상 연결'로 치지 않고 백오프 사다리를 태운다.
    STABLE_CONNECTION_SECONDS = 30.0

    def __init__(
        self,
        api_base_url: str,
        agent_token: str,
        processor: DiagnosisRequestProcessor,
        on_state_changed: Callable[[str], None] | None = None,
        on_device_identified: Callable[[str], None] | None = None,
        on_ready: Callable[[], None] | None = None,
        websocket_factory: Callable[..., Any] | None = None,
    ) -> None:
        self.url = diagnosis_websocket_url(api_base_url)
        self.agent_token = agent_token
        self.processor = processor
        self.on_state_changed = on_state_changed or (lambda state: None)
        self.on_device_identified = on_device_identified or (lambda device_id: None)
        self.on_ready = on_ready or (lambda: None)
        self.websocket_factory = websocket_factory or (websocket.WebSocketApp if websocket is not None else None)
        self.stop_event = threading.Event()
        self.reconnect_event = threading.Event()
        self.authenticated = False
        self.ready_once = False
        self.state = "DISCONNECTED"
        self._thread: threading.Thread | None = None
        self._socket: Any = None
        self._send_lock = threading.Lock()
        self._status_lock = threading.Lock()
        self._status_frames: OrderedDict[str, dict[str, Any]] = OrderedDict()
        self._pending_status_event_ids: set[str] = set()
        self._result_frames: OrderedDict[str, dict[str, Any]] = OrderedDict()
        self._pending_result_ids: set[str] = set()
        self._frame_send_failures: dict[str, int] = {}

    def start(self) -> None:
        if self.websocket_factory is None:
            self._set_state("FAILED")
            return
        if self._thread and self._thread.is_alive():
            return
        self.stop_event.clear()
        self.reconnect_event.clear()
        self._thread = threading.Thread(target=self._run, name="pc-agent-diagnosis-websocket", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self.stop_event.set()
        self.reconnect_event.set()
        socket_app = self._socket
        if socket_app is not None:
            try:
                socket_app.close()
            except Exception:
                pass
        self._set_state("DISCONNECTED")

    def request_reconnect(self) -> bool:
        if self.authenticated:
            return False
        self.reconnect_event.set()
        socket_app = self._socket
        if socket_app is not None:
            try:
                socket_app.close()
            except Exception:
                pass
        if self._thread is None or not self._thread.is_alive():
            self.start()
        return True

    def send_diagnosis_status(self, detail: dict[str, Any]) -> bool:
        event_id = detail.get("eventId")
        diagnosis_id = detail.get("diagnosisId")
        if not isinstance(event_id, str) or not event_id.strip():
            return False
        if not isinstance(diagnosis_id, str) or not diagnosis_id.strip():
            return False
        frame = {"type": "DIAGNOSIS_STATUS", "detail": dict(detail)}
        with self._status_lock:
            self._status_frames[event_id] = frame
            self._status_frames.move_to_end(event_id)
            self._pending_status_event_ids.add(event_id)
            while len(self._status_frames) > MAX_SYNC_STATUS_FRAMES:
                removed_event_id, _ = self._status_frames.popitem(last=False)
                self._pending_status_event_ids.discard(removed_event_id)
        self._flush_status_frames()
        return True

    def send_diagnosis_result(self, detail: dict[str, Any]) -> bool:
        result_id = detail.get("resultId")
        diagnosis_id = detail.get("diagnosisId")
        if not isinstance(result_id, str) or not result_id.strip():
            return False
        if not isinstance(diagnosis_id, str) or not diagnosis_id.strip():
            return False
        frame = {"type": "DIAGNOSIS_RESULT", "detail": dict(detail)}
        with self._status_lock:
            self._result_frames[result_id] = frame
            self._result_frames.move_to_end(result_id)
            self._pending_result_ids.add(result_id)
            while len(self._result_frames) > MAX_SYNC_RESULT_FRAMES:
                removed_result_id, _ = self._result_frames.popitem(last=False)
                self._pending_result_ids.discard(removed_result_id)
        self._flush_result_frames()
        return True

    def _run(self) -> None:
        attempt = 0
        while not self.stop_event.is_set():
            self.authenticated = False
            self.ready_once = False
            self._set_state("CONNECTING")
            socket_app = self.websocket_factory(
                self.url,
                on_open=self._on_open,
                on_message=self._on_message,
                on_error=self._on_error,
                on_close=self._on_close,
            )
            self._socket = socket_app
            started_at = time.monotonic()
            try:
                socket_app.run_forever(ping_interval=30, ping_timeout=10, suppress_origin=True)
            except Exception:
                self._set_state("DISCONNECTED")
            if self.stop_event.is_set():
                break
            if self.reconnect_event.is_set():
                self.reconnect_event.clear()
                attempt = 0
                continue
            delay = self.BACKOFF_SECONDS[min(attempt, len(self.BACKOFF_SECONDS) - 1)]
            # READY까지 도달했더라도 곧바로 끊긴 연결은 정상으로 치지 않는다. 그렇지 않으면
            # 서버가 매번 연결을 끊는 상황에서 1초 간격 재접속 핫루프가 된다.
            stable = time.monotonic() - started_at >= self.STABLE_CONNECTION_SECONDS
            attempt = 0 if stable else attempt + 1
            if self.reconnect_event.wait(delay):
                self.reconnect_event.clear()
                attempt = 0

    def _on_open(self, socket_app: Any) -> None:
        socket_app.send(json.dumps({"type": "AUTH", "agentToken": self.agent_token}))

    def mark_idle(self) -> bool:
        if not self.authenticated:
            return False
        self._set_state("IDLE")
        return True

    def _on_message(self, socket_app: Any, raw_message: str) -> None:
        try:
            frame = json.loads(raw_message)
        except (TypeError, json.JSONDecodeError):
            return
        if not isinstance(frame, dict):
            return
        frame_type = frame.get("type")
        if frame_type == "READY":
            detail = frame.get("detail")
            device_id = detail.get("deviceId") if isinstance(detail, dict) else None
            if not isinstance(device_id, str) or not self.processor.bind_authenticated_device(device_id):
                self._set_state("FAILED")
                socket_app.close()
                return
            self.on_device_identified(device_id)
            self.authenticated = True
            self.ready_once = True
            self._set_state("REQUEST_RECEIVED" if self.processor.store.is_busy() else "IDLE")
            with self._status_lock:
                self._pending_status_event_ids.update(self._status_frames)
                self._pending_result_ids.update(self._result_frames)
            self.on_ready()
            self._flush_status_frames()
            self._flush_result_frames()
            return
        if frame_type == "DIAGNOSIS_REQUEST":
            detail = frame.get("detail")
            payload = detail if isinstance(detail, dict) else {}
            decision = self.processor.process(payload, self.authenticated)
            socket_app.send(json.dumps(decision.response_frame(), ensure_ascii=False))
            if decision.status == "ACCEPTED":
                self._set_state("REQUEST_RECEIVED")
            return
        if frame_type == "DIAGNOSIS_STATUS_ACK":
            detail = frame.get("detail")
            if isinstance(detail, dict):
                self._acknowledge_frame("status", detail.get("eventId"))
            return
        if frame_type == "DIAGNOSIS_RESULT_ACK":
            detail = frame.get("detail")
            if isinstance(detail, dict):
                self._acknowledge_frame("result", detail.get("resultId"))
            return
        if frame_type == "ERROR" and frame.get("code") in {"AUTH_FAILED", "AGENT_FORBIDDEN"}:
            self.authenticated = False
            self._set_state("FAILED")
            socket_app.close()

    def _on_error(self, socket_app: Any, error: Any) -> None:
        if self.state != "FAILED":
            self._set_state("DISCONNECTED")

    def _on_close(self, socket_app: Any, status_code: Any, message: Any) -> None:
        self.authenticated = False
        with self._status_lock:
            self._pending_status_event_ids.update(self._status_frames)
            self._pending_result_ids.update(self._result_frames)
        if self.state != "FAILED":
            self._set_state("DISCONNECTED")

    def _flush_status_frames(self) -> None:
        socket_app = self._socket
        if not self.authenticated or socket_app is None:
            return
        with self._status_lock:
            pending = [
                (event_id, self._status_frames[event_id])
                for event_id in self._status_frames
                if event_id in self._pending_status_event_ids
            ]
        for event_id, frame in pending:
            try:
                with self._send_lock:
                    socket_app.send(json.dumps(frame, ensure_ascii=False))
            except Exception:
                self._record_frame_failure("status", event_id)
                return
            with self._status_lock:
                self._pending_status_event_ids.discard(event_id)

    def _flush_result_frames(self) -> None:
        socket_app = self._socket
        if not self.authenticated or socket_app is None:
            return
        with self._status_lock:
            pending = [
                (result_id, self._result_frames[result_id])
                for result_id in self._result_frames
                if result_id in self._pending_result_ids
            ]
        for result_id, frame in pending:
            try:
                with self._send_lock:
                    socket_app.send(json.dumps(frame, ensure_ascii=False))
            except Exception:
                self._record_frame_failure("result", result_id)
                return
            with self._status_lock:
                self._pending_result_ids.discard(result_id)

    def _record_frame_failure(self, kind: str, frame_id: str) -> None:
        """전송에 반복 실패하는 프레임은 버린다.

        서버가 특정 프레임(예: 한계를 넘는 큰 결과 프레임) 때문에 연결을 끊으면, 그 프레임을
        계속 껴안고 재접속할 때마다 다시 보내 무한 재접속 루프가 된다. 실패를 세어 폐기한다.
        """
        marker = f"{kind}:{frame_id}"
        with self._status_lock:
            failures = self._frame_send_failures.get(marker, 0) + 1
            self._frame_send_failures[marker] = failures
            if failures < self.MAX_FRAME_SEND_FAILURES:
                return
            self._frame_send_failures.pop(marker, None)
            if kind == "status":
                self._status_frames.pop(frame_id, None)
                self._pending_status_event_ids.discard(frame_id)
            else:
                self._result_frames.pop(frame_id, None)
                self._pending_result_ids.discard(frame_id)

    def _acknowledge_frame(self, kind: str, frame_id: str) -> None:
        """서버가 ACK한 프레임은 보관 목록에서 지워 재전송 대상에서 뺀다."""
        if not isinstance(frame_id, str) or not frame_id.strip():
            return
        with self._status_lock:
            self._frame_send_failures.pop(f"{kind}:{frame_id}", None)
            if kind == "status":
                self._status_frames.pop(frame_id, None)
                self._pending_status_event_ids.discard(frame_id)
            else:
                self._result_frames.pop(frame_id, None)
                self._pending_result_ids.discard(frame_id)

    def reset_sync_state(self) -> None:
        """진단 세션을 끝낼 때 전송 버퍼를 비운다."""
        with self._status_lock:
            self._status_frames.clear()
            self._pending_status_event_ids.clear()
            self._result_frames.clear()
            self._pending_result_ids.clear()
            self._frame_send_failures.clear()

    def _set_state(self, state: str) -> None:
        if state not in AGENT_STATES:
            raise ValueError(f"unsupported Agent state: {state}")
        self.state = state
        self.on_state_changed(state)


class BackgroundViewerController:
    def __init__(
        self,
        config_path: Path,
        diagnosis_session_provider: Callable[[], DiagnosisSession | None],
        connection_state_provider: Callable[[], str],
        show_viewer: Callable[..., None],
        metrics_snapshot_provider: Callable[[], Any] | None = None,
        diagnosis_snapshot_provider: Callable[[], Any] | None = None,
        diagnosis_result_provider: Callable[[], Any] | None = None,
        start_initial_metrics: Callable[[DiagnosisSession | None, str], DiagnosisSession | None] | None = None,
        start_diagnosis: Callable[[DiagnosisSession | None, str], DiagnosisSession | None] | None = None,
        cancel_diagnosis: Callable[[], bool] | None = None,
        retry_diagnosis: Callable[[], bool] | None = None,
        finish_diagnosis_session: Callable[[], bool] | None = None,
        request_reconnect: Callable[[], bool] | None = None,
    ) -> None:
        self.config_path = config_path
        self.diagnosis_session_provider = diagnosis_session_provider
        self.connection_state_provider = connection_state_provider
        self.show_viewer = show_viewer
        self.metrics_snapshot_provider = metrics_snapshot_provider or (lambda: None)
        self.diagnosis_snapshot_provider = diagnosis_snapshot_provider or (lambda: None)
        self.diagnosis_result_provider = diagnosis_result_provider or (lambda: None)
        self.start_initial_metrics = start_initial_metrics or (lambda session, mode: None)
        self.start_diagnosis = start_diagnosis or (lambda session, mode: None)
        self.cancel_diagnosis = cancel_diagnosis or (lambda: False)
        self.retry_diagnosis = retry_diagnosis or (lambda: False)
        self.finish_diagnosis_session = finish_diagnosis_session or (lambda: False)
        self.request_reconnect = request_reconnect or (lambda: False)
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._request_focus: Any = None
        self._request_apply_session: Any = None
        self._request_metrics_refresh: Any = None
        self._request_initial_metrics_complete: Any = None
        self._request_destroy: Any = None

    def show(self, session: DiagnosisSession | None = None, reconnect: bool = False) -> None:
        if reconnect:
            self.request_reconnect()
        with self._lock:
            request_focus = self._request_focus
            request_apply_session = self._request_apply_session
            if request_focus is None:
                if self._thread is None or not self._thread.is_alive():
                    self._thread = threading.Thread(target=self._run, name="pc-agent-viewer", daemon=True)
                    self._thread.start()
                return
        restored_session = session if session is not None else self.diagnosis_session_provider()
        if not (
            isinstance(restored_session, DiagnosisSession)
            and restored_session.agent_state in ACTIVE_DIAGNOSIS_STATES
        ):
            restored_session = None
        if request_apply_session is not None:
            request_apply_session(restored_session)
        request_focus()

    def shutdown(self) -> None:
        with self._lock:
            request_destroy = self._request_destroy
        if request_destroy is not None:
            request_destroy()

    def refresh_metrics(self) -> None:
        with self._lock:
            request_metrics_refresh = self._request_metrics_refresh
        if request_metrics_refresh is not None:
            request_metrics_refresh()

    def complete_initial_metrics(self) -> None:
        with self._lock:
            request_initial_metrics_complete = self._request_initial_metrics_complete
        if request_initial_metrics_complete is not None:
            request_initial_metrics_complete()

    def _run(self) -> None:
        self.show_viewer(
            self.config_path,
            background_mode=True,
            diagnosis_session_provider=self.diagnosis_session_provider,
            connection_state_provider=self.connection_state_provider,
            metrics_snapshot_provider=self.metrics_snapshot_provider,
            diagnosis_snapshot_provider=self.diagnosis_snapshot_provider,
            diagnosis_result_provider=self.diagnosis_result_provider,
            start_initial_metrics=self.start_initial_metrics,
            start_diagnosis=self.start_diagnosis,
            cancel_diagnosis=self.cancel_diagnosis,
            retry_diagnosis=self.retry_diagnosis,
            finish_diagnosis_session=self.finish_diagnosis_session,
            on_window_ready=self._on_window_ready,
            on_window_closed=self._on_window_closed,
        )

    def _on_window_ready(
        self,
        request_focus: Any,
        request_apply_session: Any,
        request_metrics_refresh: Any,
        request_initial_metrics_complete: Any,
        request_destroy: Any,
    ) -> None:
        with self._lock:
            self._request_focus = request_focus
            self._request_apply_session = request_apply_session
            self._request_metrics_refresh = request_metrics_refresh
            self._request_initial_metrics_complete = request_initial_metrics_complete
            self._request_destroy = request_destroy
        session = self.diagnosis_session_provider()
        if isinstance(session, DiagnosisSession) and session.agent_state in ACTIVE_DIAGNOSIS_STATES:
            request_apply_session(session)
        request_focus()

    def _on_window_closed(self) -> None:
        with self._lock:
            self._request_focus = None
            self._request_apply_session = None
            self._request_metrics_refresh = None
            self._request_initial_metrics_complete = None
            self._request_destroy = None


class ViewerRequestSignal:
    def __init__(self, path: Path, restrict_file: Callable[[Path], None]) -> None:
        self.path = path
        self.restrict_file = restrict_file

    def signal(self, reconnect: bool = False) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_name(f"{self.path.name}.{uuid.uuid4().hex}.tmp")
        try:
            temporary.write_text(
                json.dumps({"requestId": str(uuid.uuid4()), "reconnect": reconnect}) + "\n",
                encoding="utf-8",
            )
            for attempt in range(5):
                try:
                    temporary.replace(self.path)
                    break
                except PermissionError:
                    if attempt == 4:
                        raise
                    # The running Agent may briefly hold the signal file while polling it.
                    time.sleep(0.05 * (attempt + 1))
        finally:
            temporary.unlink(missing_ok=True)
        self.restrict_file(self.path)

    def monitor(self, runtime: Any, controller: BackgroundViewerController) -> None:
        try:
            last_request = self.path.read_text(encoding="utf-8") if self.path.exists() else ""
        except OSError:
            last_request = ""
        while runtime.running:
            try:
                current_request = self.path.read_text(encoding="utf-8") if self.path.exists() else ""
                if current_request and current_request != last_request:
                    last_request = current_request
                    try:
                        payload = json.loads(current_request)
                    except (TypeError, json.JSONDecodeError):
                        payload = {}
                    controller.show(reconnect=payload.get("reconnect") is True)
            except OSError:
                pass
            time.sleep(0.25)
