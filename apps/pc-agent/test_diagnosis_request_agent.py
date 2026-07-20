import json
import tempfile
import threading
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import MagicMock, patch

from diagnosis_request_agent import (
    AgentDiagnosisWebSocketClient,
    BackgroundViewerController,
    DiagnosisRequest,
    DiagnosisRequestProcessor,
    DiagnosisSession,
    DiagnosisSessionStore,
    STANDALONE,
    ViewerRequestSignal,
    WEB_REQUEST,
    diagnosis_websocket_url,
)


NOW = datetime(2026, 7, 13, 1, 0, tzinfo=timezone.utc)


def request_payload(
    diagnosis_id="diagnosis-1",
    device_id="device-1",
    expires_at=None,
    mode="LIVE",
    symptom="게임 실행 후 프레임이 급격히 저하됨",
):
    return {
        "diagnosisId": diagnosis_id,
        "deviceId": device_id,
        "symptom": symptom,
        "requestedChecks": ["cpu", "gpu", "memory", "disk", "cooling"],
        "requestedAt": NOW.isoformat(),
        "expiresAt": (expires_at or NOW + timedelta(minutes=2)).isoformat(),
        "mode": mode,
    }


class DiagnosisRequestProcessorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary.name) / "diagnosis-request-state.json"
        self.shown = []
        self.store = DiagnosisSessionStore(self.path)
        self.processor = DiagnosisRequestProcessor(
            self.store,
            device_id="device-1",
            now=lambda: NOW,
            on_request=self.shown.append,
        )

    def tearDown(self):
        self.temporary.cleanup()

    def test_accepts_and_persists_request_received_session(self):
        decision = self.processor.process(request_payload(), authenticated=True)

        self.assertEqual("ACCEPTED", decision.status)
        self.assertEqual("REQUEST_RECEIVED", self.store.session.agent_state)
        self.assertEqual("게임 실행 후 프레임이 급격히 저하됨", self.store.session.request.symptom)
        self.assertEqual(WEB_REQUEST, self.store.session.request.source)
        self.assertEqual(1, len(self.shown))

    def test_accepts_java_instant_with_nanosecond_precision(self):
        payload = request_payload()
        payload["requestedAt"] = "2026-07-13T01:00:00.123456789Z"
        payload["expiresAt"] = "2026-07-13T01:02:00.123456789Z"

        decision = self.processor.process(payload, authenticated=True)

        self.assertEqual("ACCEPTED", decision.status)

    def test_preserves_each_web_symptom_without_replacing_it(self):
        symptoms = ("게임 A에서만 화면이 멈춥니다.", "렌더링 중 GPU 온도가 상승합니다.")
        for index, symptom in enumerate(symptoms):
            with self.subTest(symptom=symptom):
                store = DiagnosisSessionStore(Path(self.temporary.name) / f"state-{index}.json")
                shown = []
                processor = DiagnosisRequestProcessor(
                    store,
                    device_id="device-1",
                    now=lambda: NOW,
                    on_request=shown.append,
                )
                decision = processor.process(
                    request_payload(diagnosis_id=f"diagnosis-{index}", symptom=symptom),
                    authenticated=True,
                )
                self.assertEqual(symptom, decision.session.request.symptom)
                self.assertEqual(symptom, shown[0].request.symptom)

    def test_standalone_session_with_empty_symptom_round_trips(self):
        request = DiagnosisRequest(
            diagnosis_id="standalone-1",
            device_id="device-1",
            symptom="",
            requested_checks=("cpu", "gpu", "memory", "disk"),
            requested_at=NOW.isoformat(),
            expires_at=(NOW + timedelta(minutes=2)).isoformat(),
            mode="LIVE",
            source=STANDALONE,
        )
        self.store.accept(DiagnosisSession(request))

        restored = DiagnosisSessionStore(self.path).session

        self.assertEqual(STANDALONE, restored.request.source)
        self.assertEqual("", restored.request.symptom)

    def test_web_request_replaces_default_standalone_request_received_session(self):
        standalone = DiagnosisRequest.from_payload(
            request_payload(diagnosis_id="standalone-1", symptom=""),
            source=STANDALONE,
        )
        self.store.accept(DiagnosisSession(standalone))

        decision = self.processor.process(request_payload(), authenticated=True)

        self.assertEqual("ACCEPTED", decision.status)
        self.assertEqual(WEB_REQUEST, self.store.session.request.source)

    def test_expired_request_received_is_replaced_without_losing_processed_ids(self):
        replaced = []
        processor = DiagnosisRequestProcessor(
            self.store,
            device_id="device-1",
            now=lambda: NOW,
            on_session_replaced=replaced.append,
        )
        expired = DiagnosisRequest.from_payload(
            request_payload(diagnosis_id="expired-diagnosis", expires_at=NOW - timedelta(seconds=1)),
        )
        self.store.accept(DiagnosisSession(expired))

        decision = processor.process(request_payload(diagnosis_id="replacement-diagnosis"), True)

        self.assertEqual("ACCEPTED", decision.status)
        self.assertEqual("replacement-diagnosis", self.store.session.request.diagnosis_id)
        self.assertEqual("REQUEST_EXPIRED", replaced[0].reason)
        self.assertTrue(self.store.contains("expired-diagnosis"))
        self.assertTrue(self.store.contains("replacement-diagnosis"))

    def test_unstarted_web_request_is_superseded_by_new_web_request(self):
        replaced = []
        processor = DiagnosisRequestProcessor(
            self.store,
            device_id="device-1",
            now=lambda: NOW,
            on_session_replaced=replaced.append,
        )
        self.assertEqual("ACCEPTED", processor.process(request_payload(diagnosis_id="pending-diagnosis"), True).status)

        decision = processor.process(request_payload(diagnosis_id="new-diagnosis"), True)

        self.assertEqual("ACCEPTED", decision.status)
        self.assertEqual("new-diagnosis", self.store.session.request.diagnosis_id)
        self.assertEqual("SUPERSEDED", replaced[0].reason)

    def test_restart_cleanup_removes_stale_request_without_touching_sibling_files(self):
        credential_path = Path(self.temporary.name) / "agent-credential.json"
        config_path = Path(self.temporary.name) / "agent-config.json"
        credential_path.write_text("credential", encoding="utf-8")
        config_path.write_text("config", encoding="utf-8")
        expired = DiagnosisRequest.from_payload(
            request_payload(diagnosis_id="expired-after-restart", expires_at=NOW - timedelta(seconds=1)),
        )
        self.store.accept(DiagnosisSession(expired))

        restarted_store = DiagnosisSessionStore(self.path)
        replacement = restarted_store.expire_stale_request(NOW)
        restarted = DiagnosisRequestProcessor(restarted_store, "device-1", now=lambda: NOW)
        decision = restarted.process(request_payload(diagnosis_id="after-restart"), True)

        self.assertEqual("REQUEST_EXPIRED", replacement.reason)
        self.assertEqual("ACCEPTED", decision.status)
        self.assertTrue(restarted_store.contains("expired-after-restart"))
        self.assertEqual("credential", credential_path.read_text(encoding="utf-8"))
        self.assertEqual("config", config_path.read_text(encoding="utf-8"))

    def test_running_session_is_the_only_state_that_blocks_a_new_request(self):
        self.assertEqual("ACCEPTED", self.processor.process(request_payload(diagnosis_id="running-diagnosis"), True).status)
        self.store.update_state("RUNNING")

        decision = self.processor.process(request_payload(diagnosis_id="blocked-diagnosis"), True)

        self.assertEqual("BUSY", decision.status)
        self.assertIn("running-diagnosis", decision.message)
        self.assertEqual("running-diagnosis", self.store.session.request.diagnosis_id)

    def test_terminal_states_allow_a_new_request(self):
        for state in ("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"):
            with self.subTest(state=state), tempfile.TemporaryDirectory() as directory:
                store = DiagnosisSessionStore(Path(directory) / "state.json")
                processor = DiagnosisRequestProcessor(store, "device-1", now=lambda: NOW)
                self.assertEqual("ACCEPTED", processor.process(request_payload(diagnosis_id=f"old-{state}"), True).status)
                store.update_state(state)

                decision = processor.process(request_payload(diagnosis_id=f"new-{state}"), True)

                self.assertEqual("ACCEPTED", decision.status)
                self.assertEqual(f"new-{state}", store.session.request.diagnosis_id)

    def test_rejects_auth_device_expiry_and_busy_cases(self):
        self.assertEqual("AUTH_FAILED", self.processor.process(request_payload(), authenticated=False).status)
        self.assertEqual("DEVICE_MISMATCH", self.processor.process(request_payload(device_id="other"), True).status)
        self.assertEqual(
            "EXPIRED",
            self.processor.process(request_payload(expires_at=NOW - timedelta(seconds=1)), True).status,
        )
        self.assertEqual("ACCEPTED", self.processor.process(request_payload(), True).status)
        self.store.update_state("RUNNING")
        self.assertEqual("BUSY", self.processor.process(request_payload(diagnosis_id="diagnosis-2"), True).status)

    def test_duplicate_is_detected_before_busy_and_after_restart(self):
        self.assertEqual("ACCEPTED", self.processor.process(request_payload(), True).status)
        self.assertEqual("DUPLICATE", self.processor.process(request_payload(), True).status)
        restarted = DiagnosisRequestProcessor(DiagnosisSessionStore(self.path), "device-1", now=lambda: NOW)
        self.assertEqual("DUPLICATE", restarted.process(request_payload(), True).status)

    def test_demo_mode_is_only_taken_from_request(self):
        decision = self.processor.process(request_payload(mode="DEMO"), True)
        self.assertEqual("DEMO", decision.session.request.mode)


class ViewerRequestSignalTest(unittest.TestCase):
    def test_retries_when_windows_temporarily_denies_signal_file_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "show-viewer-request.json"
            path.write_text("{}\n", encoding="utf-8")
            restricted = []
            signal = ViewerRequestSignal(path, restricted.append)
            original_replace = Path.replace
            attempts = 0

            def flaky_replace(source, target):
                nonlocal attempts
                attempts += 1
                if attempts == 1:
                    raise PermissionError("signal file is being read")
                return original_replace(source, target)

            with patch.object(Path, "replace", autospec=True, side_effect=flaky_replace):
                signal.signal(reconnect=True)

            self.assertEqual(2, attempts)
            self.assertEqual([path], restricted)
            payload = json.loads(path.read_text(encoding="utf-8"))
            self.assertIn("requestId", payload)
            self.assertTrue(payload["reconnect"])

    def test_monitor_forwards_reconnect_request_to_existing_controller(self):
        runtime = MagicMock(running=True)
        controller = MagicMock()
        signal = ViewerRequestSignal(Path("show-viewer-request.json"), lambda path: None)
        with patch.object(Path, "exists", return_value=True), \
                patch.object(Path, "read_text", side_effect=["{}", '{"requestId":"next","reconnect":true}']), \
                patch("diagnosis_request_agent.time.sleep", side_effect=lambda seconds: setattr(runtime, "running", False)):
            signal.monitor(runtime, controller)

        controller.show.assert_called_once_with(reconnect=True)


class FakeSocket:
    def __init__(self):
        self.sent = []
        self.closed = False

    def send(self, payload):
        self.sent.append(json.loads(payload))

    def close(self):
        self.closed = True


class FailingSocket(FakeSocket):
    def send(self, payload):
        frame = json.loads(payload)
        if frame.get("type") == "DIAGNOSIS_RESULT":
            raise OSError("server unavailable")
        self.sent.append(frame)


class AgentDiagnosisWebSocketClientTest(unittest.TestCase):
    def test_protocol_reconnect_is_a_noop_while_authenticated(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AgentDiagnosisWebSocketClient(
                "http://localhost:8080",
                "secret",
                DiagnosisRequestProcessor(DiagnosisSessionStore(Path(directory) / "state.json")),
                websocket_factory=lambda *args, **kwargs: None,
            )
            socket = FakeSocket()
            client._socket = socket
            client.authenticated = True

            self.assertFalse(client.request_reconnect())
            self.assertFalse(socket.closed)
            self.assertFalse(client.reconnect_event.is_set())

    def test_protocol_reconnect_closes_disconnected_socket_and_wakes_backoff(self):
        with tempfile.TemporaryDirectory() as directory:
            wait_started = threading.Event()
            sockets = []

            class ObservableReconnectEvent(threading.Event):
                def wait(self, timeout=None):
                    wait_started.set()
                    return super().wait(timeout)

            class BackoffSocket(FakeSocket):
                def run_forever(self, **kwargs):
                    if len(sockets) >= 2:
                        client.stop_event.set()

            def socket_factory(*args, **kwargs):
                socket = BackoffSocket()
                sockets.append(socket)
                return socket

            client = AgentDiagnosisWebSocketClient(
                "http://localhost:8080",
                "secret",
                DiagnosisRequestProcessor(DiagnosisSessionStore(Path(directory) / "state.json")),
                websocket_factory=socket_factory,
            )
            client.BACKOFF_SECONDS = (30,)
            client.reconnect_event = ObservableReconnectEvent()
            client.start()
            self.assertTrue(wait_started.wait(1))

            self.assertTrue(client.request_reconnect())
            client._thread.join(1)

            self.assertGreaterEqual(len(sockets), 2)
            self.assertTrue(sockets[0].closed)

    def test_ready_authenticates_and_request_gets_real_response(self):
        with tempfile.TemporaryDirectory() as directory:
            states = []
            devices = []
            shown = []
            processor = DiagnosisRequestProcessor(
                DiagnosisSessionStore(Path(directory) / "state.json"),
                now=lambda: NOW,
                on_request=shown.append,
            )
            client = AgentDiagnosisWebSocketClient(
                "http://localhost:8080",
                "secret",
                processor,
                on_state_changed=states.append,
                on_device_identified=devices.append,
                websocket_factory=lambda *args, **kwargs: None,
            )
            socket = FakeSocket()
            client._on_open(socket)
            client._on_message(socket, json.dumps({"type": "READY", "detail": {"deviceId": "device-1"}}))
            client._on_message(socket, json.dumps({"type": "DIAGNOSIS_REQUEST", "detail": request_payload()}))

            self.assertEqual("AUTH", socket.sent[0]["type"])
            self.assertEqual("ACCEPTED", socket.sent[1]["status"])
            self.assertEqual(["device-1"], devices)
            self.assertEqual("REQUEST_RECEIVED", states[-1])
            self.assertEqual(1, len(shown))

    def test_disconnect_and_auth_failure_are_distinct(self):
        with tempfile.TemporaryDirectory() as directory:
            states = []
            client = AgentDiagnosisWebSocketClient(
                "http://localhost:8080",
                "bad-token",
                DiagnosisRequestProcessor(DiagnosisSessionStore(Path(directory) / "state.json")),
                on_state_changed=states.append,
                websocket_factory=lambda *args, **kwargs: None,
            )
            socket = FakeSocket()
            client._on_close(socket, 1006, "network lost")
            self.assertEqual("DISCONNECTED", states[-1])
            client._on_message(socket, json.dumps({"type": "ERROR", "code": "AUTH_FAILED"}))
            self.assertEqual("FAILED", states[-1])
            self.assertTrue(socket.closed)

    def test_websocket_url_is_outbound_and_secure_when_api_is_https(self):
        self.assertEqual("ws://localhost:8080/ws/pc-agent/diagnosis", diagnosis_websocket_url("http://localhost:8080"))
        self.assertEqual("wss://api.example.com/ws/pc-agent/diagnosis", diagnosis_websocket_url("https://api.example.com"))

    def test_status_event_is_queued_offline_and_sent_after_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            ready = []
            client = AgentDiagnosisWebSocketClient(
                "http://localhost:8080",
                "secret",
                DiagnosisRequestProcessor(DiagnosisSessionStore(Path(directory) / "state.json")),
                on_ready=lambda: ready.append(True),
                websocket_factory=lambda *args, **kwargs: None,
            )
            detail = {
                "diagnosisId": "diagnosis-1",
                "eventId": "event-1",
                "eventType": "PROGRESS_UPDATED",
                "sessionState": "DIAGNOSING",
                "progress": 25,
            }
            self.assertTrue(client.send_diagnosis_status(detail))
            socket = FakeSocket()
            client._socket = socket
            client._on_message(socket, json.dumps({"type": "READY", "detail": {"deviceId": "device-1"}}))

            status_frames = [frame for frame in socket.sent if frame.get("type") == "DIAGNOSIS_STATUS"]
            self.assertEqual([detail], [frame["detail"] for frame in status_frames])
            self.assertEqual([True], ready)

    def test_reconnect_resends_same_event_id_for_server_deduplication(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AgentDiagnosisWebSocketClient(
                "http://localhost:8080",
                "secret",
                DiagnosisRequestProcessor(DiagnosisSessionStore(Path(directory) / "state.json")),
                websocket_factory=lambda *args, **kwargs: None,
            )
            detail = {"diagnosisId": "diagnosis-1", "eventId": "event-1", "progress": 100}
            first = FakeSocket()
            client._socket = first
            client._on_message(first, json.dumps({"type": "READY", "detail": {"deviceId": "device-1"}}))
            client.send_diagnosis_status(detail)
            client._on_close(first, 1006, "network lost")

            second = FakeSocket()
            client._socket = second
            client._on_message(second, json.dumps({"type": "READY", "detail": {"deviceId": "device-1"}}))

            self.assertEqual("event-1", first.sent[-1]["detail"]["eventId"])
            self.assertEqual("event-1", second.sent[-1]["detail"]["eventId"])

    def test_result_is_queued_offline_and_sent_after_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AgentDiagnosisWebSocketClient(
                "http://localhost:8080",
                "secret",
                DiagnosisRequestProcessor(DiagnosisSessionStore(Path(directory) / "state.json")),
                websocket_factory=lambda *args, **kwargs: None,
            )
            detail = {
                "diagnosisId": "diagnosis-1",
                "resultId": "result-1",
                "severity": "NORMAL",
                "resolutionType": "NONE",
            }

            self.assertTrue(client.send_diagnosis_result(detail))
            socket = FakeSocket()
            client._socket = socket
            client._on_message(socket, json.dumps({"type": "READY", "detail": {"deviceId": "device-1"}}))

            result_frames = [frame for frame in socket.sent if frame.get("type") == "DIAGNOSIS_RESULT"]
            self.assertEqual([detail], [frame["detail"] for frame in result_frames])

    def test_result_send_failure_stays_pending_and_retries_after_reconnect(self):
        with tempfile.TemporaryDirectory() as directory:
            client = AgentDiagnosisWebSocketClient(
                "http://localhost:8080",
                "secret",
                DiagnosisRequestProcessor(DiagnosisSessionStore(Path(directory) / "state.json")),
                websocket_factory=lambda *args, **kwargs: None,
            )
            failing = FailingSocket()
            client._socket = failing
            client._on_message(failing, json.dumps({"type": "READY", "detail": {"deviceId": "device-1"}}))
            self.assertTrue(client.send_diagnosis_result({"diagnosisId": "diagnosis-1", "resultId": "result-1"}))

            client._on_close(failing, 1006, "network lost")
            recovered = FakeSocket()
            client._socket = recovered
            client._on_message(recovered, json.dumps({"type": "READY", "detail": {"deviceId": "device-1"}}))

            result_frames = [frame for frame in recovered.sent if frame.get("type") == "DIAGNOSIS_RESULT"]
            self.assertEqual("result-1", result_frames[-1]["detail"]["resultId"])


class BackgroundViewerControllerTest(unittest.TestCase):
    def test_show_requests_reconnect_and_reuses_hidden_viewer(self):
        events = []
        controller = BackgroundViewerController(
            Path("agent-config.json"),
            diagnosis_session_provider=lambda: None,
            connection_state_provider=lambda: "DISCONNECTED",
            show_viewer=lambda *args, **kwargs: events.append("new-root"),
            request_reconnect=lambda: events.append("reconnect") or True,
        )
        controller._on_window_ready(
            lambda: events.append("focus"),
            lambda session: events.append(("session", session)),
            lambda: None,
            lambda: None,
            lambda: None,
        )
        events.clear()

        controller.show(reconnect=True)

        self.assertEqual(["reconnect", ("session", None), "focus"], events)
        self.assertIsNone(controller._thread)

    def test_show_reuses_ready_root_and_only_requests_apply_and_focus(self):
        events = []
        controller = BackgroundViewerController(
            Path("agent-config.json"),
            diagnosis_session_provider=lambda: None,
            connection_state_provider=lambda: "RUNNING",
            show_viewer=lambda *args, **kwargs: events.append("new-root"),
        )
        controller._on_window_ready(
            lambda: events.append("focus"),
            lambda session: events.append(("session", session)),
            lambda: None,
            lambda: None,
            lambda: None,
        )
        events.clear()

        controller.show()

        self.assertEqual([("session", None), "focus"], events)
        self.assertIsNone(controller._thread)

    def test_show_reuses_ready_root_for_an_active_demo_session(self):
        events = []
        session = DiagnosisSession(DiagnosisRequest.from_payload(request_payload(mode="DEMO")))
        controller = BackgroundViewerController(
            Path("agent-config.json"),
            diagnosis_session_provider=lambda: session,
            connection_state_provider=lambda: "RUNNING",
            show_viewer=lambda *args, **kwargs: events.append("new-root"),
        )
        controller._on_window_ready(
            lambda: events.append("focus"),
            lambda current: events.append(("session", current)),
            lambda: None,
            lambda: None,
            lambda: None,
        )
        events.clear()

        controller.show(session)

        self.assertEqual([("session", session), "focus"], events)
        self.assertIsNone(controller._thread)

    def test_forwards_metric_refresh_and_real_completion_to_existing_window(self):
        events = []
        controller = BackgroundViewerController(
            Path("agent-config.json"),
            diagnosis_session_provider=lambda: None,
            connection_state_provider=lambda: "RUNNING",
            show_viewer=lambda *args, **kwargs: None,
            metrics_snapshot_provider=lambda: "snapshot",
        )
        controller._on_window_ready(
            lambda: events.append("focus"),
            lambda session: events.append("session"),
            lambda: events.append("refresh"),
            lambda: events.append("complete"),
            lambda: events.append("destroy"),
        )

        controller.refresh_metrics()
        controller.complete_initial_metrics()
        controller.shutdown()

        self.assertEqual(["focus", "refresh", "complete", "destroy"], events)

    def test_window_close_removes_all_view_callbacks(self):
        events = []
        controller = BackgroundViewerController(
            Path("agent-config.json"),
            diagnosis_session_provider=lambda: None,
            connection_state_provider=lambda: "RUNNING",
            show_viewer=lambda *args, **kwargs: None,
        )
        controller._on_window_ready(
            lambda: events.append("focus"),
            lambda session: events.append("session"),
            lambda: events.append("refresh"),
            lambda: events.append("complete"),
            lambda: events.append("destroy"),
        )
        controller._on_window_closed()

        controller.refresh_metrics()
        controller.complete_initial_metrics()
        controller.shutdown()

        self.assertEqual(["focus"], events)


if __name__ == "__main__":
    unittest.main()
