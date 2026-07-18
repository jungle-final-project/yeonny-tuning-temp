from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

import buildgraph_agent as agent
from diagnosis_request_agent import DiagnosisRequest, DiagnosisSession
from initial_metrics import DemoSensorProvider, InitialCollectionSettings
from pc_agent_demo_scenarios import (
    GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID,
    GRAPHICS_CODE43_REMOTE_SUPPORT_SYMPTOM,
)
from windows_graphics_diagnostics import (
    Code43RemoteSupportDemoGraphicsProvider,
    DEVICE_REPORTED_PROBLEM,
    DISABLED,
    NO_RESULTS,
    OK,
    PowerShellQueryResult,
    WindowsDisplayDevice,
    WindowsGraphicsDiagnosticsSnapshot,
)


STARTED_AT = datetime(2026, 7, 14, 8, 0, tzinfo=timezone.utc)


class CapturingSocket:
    def __init__(self) -> None:
        self.sent: list[dict] = []

    def send(self, payload: str) -> None:
        self.sent.append(json.loads(payload))


def readings_at(sampled_at: datetime, value: float) -> tuple[agent.MetricReading, ...]:
    stamp = sampled_at.isoformat()
    return (
        agent.MetricReading("cpu", "usage", value, "%", "AVAILABLE", "NORMAL", "psutil", stamp),
        agent.MetricReading("gpu", "usage", value, "%", "AVAILABLE", "NORMAL", "windows-performance-counter", stamp),
        agent.MetricReading("ram", "usage", value, "%", "AVAILABLE", "NORMAL", "psutil", stamp),
        agent.MetricReading("disk", "activity", value, "%", "AVAILABLE", "NORMAL", "windows-performance-counter", stamp),
    )


def windows_snapshot(problem_code: int | None) -> WindowsGraphicsDiagnosticsSnapshot:
    queried_at = (STARTED_AT + timedelta(seconds=1)).isoformat()
    if problem_code is not None:
        problem_device = WindowsDisplayDevice(
            device_name=f"Test Problem {problem_code} Display Adapter",
            instance_id="PCI\\VEN_1234&DEV_5678",
            pnp_status="Error",
            problem_code=problem_code,
            problem_code_query_status=OK,
            device_class="Display",
            manufacturer="Test Manufacturer",
            driver_provider="Test Driver Provider",
            driver_version=f"31.0.0.{problem_code}",
            driver_date="2026-07-01T00:00:00+00:00",
            driver_signed=True,
            signer="Microsoft Windows Hardware Compatibility Publisher",
            inf_name=f"oem{problem_code}.inf",
            status=DISABLED if problem_code == 22 else DEVICE_REPORTED_PROBLEM,
            queried_at=queried_at,
            device_source="Win32_PnPEntity",
        )
        devices = (problem_device,)
        if problem_code == 43:
            devices = (WindowsDisplayDevice(
                device_name="Test Healthy Display Adapter",
                instance_id="PCI\\VEN_0000&DEV_0000",
                pnp_status="OK",
                problem_code=0,
                problem_code_query_status=OK,
                device_class="Display",
                manufacturer="Test Manufacturer",
                driver_provider="Test Driver Provider",
                driver_version="31.0.0.0",
                driver_date="2026-07-01T00:00:00+00:00",
                driver_signed=True,
                signer="Microsoft Windows Hardware Compatibility Publisher",
                inf_name="oem0.inf",
                status=OK,
                queried_at=queried_at,
                device_source="Win32_PnPEntity",
            ), problem_device)
        query_items = tuple({"instanceId": device.instance_id} for device in devices)
        device_query = PowerShellQueryResult(OK, query_items)
        driver_query = PowerShellQueryResult(OK, query_items)
    else:
        devices = ()
        device_query = PowerShellQueryResult(NO_RESULTS)
        driver_query = PowerShellQueryResult(NO_RESULTS)
    no_events = PowerShellQueryResult(NO_RESULTS)
    return WindowsGraphicsDiagnosticsSnapshot(
        queried_at=queried_at,
        devices=devices,
        graphics_events=(),
        whea_events=(),
        kernel_power_events=(),
        device_query=device_query,
        driver_query=driver_query,
        graphics_event_query=no_events,
        whea_event_query=no_events,
        kernel_power_event_query=no_events,
    )


class GraphicsDiagnosisFlowTest(unittest.TestCase):
    def run_flow(
        self,
        problem_code: int | None,
        *,
        mode: str = "LIVE",
        symptom: str = "게임 중 검은 화면이 나타났다가 화면이 복구됩니다.",
        snapshot_override: WindowsGraphicsDiagnosticsSnapshot | None = None,
    ):
        diagnosis_id = "diagnosis-graphics-flow"
        request = DiagnosisRequest(
            diagnosis_id=diagnosis_id,
            device_id="device-1",
            symptom=symptom,
            requested_checks=("gpu",),
            requested_at=STARTED_AT.isoformat(),
            expires_at=(STARTED_AT + timedelta(minutes=5)).isoformat(),
            mode=mode,
        )
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        session_store = agent.DiagnosisSessionStore(Path(temporary.name) / "session.json")
        session_store.accept(DiagnosisSession(request))
        result_store = agent.DiagnosisResultStore(Path(temporary.name) / "result.json")
        result_store.save(agent.DiagnosisResult(
            diagnosis_id=diagnosis_id,
            severity="NORMAL",
            title="stale result",
            summary="stale result",
            evidence=(),
            findings=(),
            suspected_causes=(),
            recommended_actions=(),
            resolution_type="NONE",
            can_auto_recover=False,
            unsupported_checks=(),
            evaluated_at=(STARTED_AT - timedelta(minutes=1)).isoformat(),
        ))
        metrics_store = agent.MetricsStore(Path(temporary.name) / "metrics.json")
        metrics_store.begin(diagnosis_id, mode)
        metrics_store.append(diagnosis_id, readings_at(STARTED_AT - timedelta(seconds=1), 10.0))
        metrics_store.complete(diagnosis_id)
        log_store = agent.DiagnosisLogStore(Path(temporary.name) / "progress.json")
        log_store.replace(agent.DiagnosisRunSnapshot(
            diagnosis_id=diagnosis_id,
            mode=mode,
            state="COMPLETED",
            progress=100,
            transition_allowed=True,
        ))
        provider_calls: list[str] = []
        actual_windows_snapshot = snapshot_override or windows_snapshot(problem_code)

        def collect_windows() -> WindowsGraphicsDiagnosticsSnapshot:
            provider_calls.append(diagnosis_id)
            return actual_windows_snapshot

        updates: list[agent.DiagnosisRunSnapshot] = []
        handlers = agent.graphics_diagnosis_task_handlers(
            lambda: session_store.session,
            lambda: metrics_store.snapshot,
            lambda: log_store.snapshot,
            collect_windows,
            observation_timeout_seconds=0.5,
        )
        orchestrator = agent.DiagnosisOrchestrator(
            lambda: metrics_store.snapshot,
            log_store,
            settings=agent.DiagnosisSettings(
                task_weights=agent.GRAPHICS_DIAGNOSIS_TASK_WEIGHTS,
                task_timeout_seconds=2.0,
                session_timeout_seconds=10.0,
                max_retries=1,
            ),
            task_handlers=handlers,
            task_definitions=agent.GRAPHICS_DIAGNOSIS_TASK_DEFINITIONS,
            task_labels=agent.GRAPHICS_DIAGNOSIS_TASK_LABELS,
            on_update=updates.append,
            now=lambda: STARTED_AT,
        )

        started_session = agent.start_diagnosis_once(
            session_store.session,
            session_store,
            metrics_store,
            orchestrator,
            result_store,
        )
        self.assertIsNotNone(started_session)
        result_cleared = result_store.result is None
        for index in range(1, 4):
            metrics_store.append(
                diagnosis_id,
                readings_at(STARTED_AT + timedelta(seconds=index), 20.0 + index),
            )
        self.assertTrue(orchestrator.wait(5.0))
        snapshot = log_store.snapshot
        result = agent.DiagnosisRuleEngine().evaluate(metrics_store.snapshot, snapshot)
        result_store.save(result)
        return session_store.session, metrics_store.snapshot, snapshot, result, updates, provider_calls, result_cleared

    def test_code43_demo_uses_rule_engine_and_sends_progress_and_result_metadata(self) -> None:
        demo_snapshot = Code43RemoteSupportDemoGraphicsProvider(
            now=lambda: STARTED_AT + timedelta(seconds=1),
        ).collect()
        session, _, snapshot, result, _, provider_calls, _ = self.run_flow(
            43,
            mode="DEMO",
            symptom=GRAPHICS_CODE43_REMOTE_SUPPORT_SYMPTOM,
            snapshot_override=demo_snapshot,
        )

        self.assertEqual(1, len(provider_calls))
        self.assertEqual("COMPLETED", snapshot.state)
        self.assertEqual("WARNING", result.severity)
        self.assertEqual("DEVICE_DRIVER_CONFIGURATION_ISSUE", result.diagnosis_type)
        self.assertEqual("SOFTWARE_RECOVERY", result.resolution_type)
        self.assertFalse(result.can_auto_recover)
        self.assertTrue(result.remote_as_recommended)
        self.assertEqual("DEMO", result.data_mode)
        self.assertEqual(GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID, result.scenario_id)
        self.assertTrue(agent.can_offer_as(result, snapshot, session))
        self.assertIn("드라이버 재설치 또는 이전 버전 롤백", " ".join(result.recommended_actions))
        self.assertIn("방문 점검 전환", " ".join(result.recommended_actions))
        self.assertNotIn("물리 고장으로 확정", result.title)
        self.assertNotEqual("PHYSICAL_INSPECTION", result.resolution_type)

        websocket_temporary = tempfile.TemporaryDirectory()
        self.addCleanup(websocket_temporary.cleanup)
        client = agent.AgentDiagnosisWebSocketClient(
            "http://localhost:8080",
            "agent-token",
            agent.DiagnosisRequestProcessor(
                agent.DiagnosisSessionStore(Path(websocket_temporary.name) / "ws.json")
            ),
            websocket_factory=lambda *args, **kwargs: None,
        )
        socket = CapturingSocket()
        client.authenticated = True
        client._socket = socket
        for event in snapshot.events:
            client.send_diagnosis_status(agent.diagnosis_event_sync_detail(snapshot, event, session))
        client.send_diagnosis_result(result.to_dict())

        status_frames = [frame for frame in socket.sent if frame.get("type") == "DIAGNOSIS_STATUS"]
        result_frames = [frame for frame in socket.sent if frame.get("type") == "DIAGNOSIS_RESULT"]
        self.assertTrue(status_frames)
        self.assertTrue(any(frame["detail"].get("eventType") == "PROGRESS_UPDATED" for frame in status_frames))
        self.assertTrue(all(frame["detail"]["dataMode"] == "DEMO" for frame in status_frames))
        self.assertTrue(all(
            frame["detail"]["scenarioId"] == GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID
            for frame in status_frames
        ))
        self.assertEqual("DEMO", result_frames[-1]["detail"]["dataMode"])
        self.assertEqual(
            GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID,
            result_frames[-1]["detail"]["scenarioId"],
        )

    def test_black_screen_and_code_22_complete_real_task_event_and_result_flow(self) -> None:
        session, metrics, snapshot, result, updates, provider_calls, result_cleared = self.run_flow(22)

        self.assertTrue(result_cleared)
        self.assertEqual(0, updates[0].progress)
        self.assertEqual((), updates[0].events)
        self.assertTrue(all(task.status == "PENDING" and not task.evidence for task in updates[0].tasks))
        self.assertEqual("COMPLETED", snapshot.state)
        self.assertEqual(100, snapshot.progress)
        self.assertTrue(all(task.status == "COMPLETED" for task in snapshot.tasks))
        self.assertTrue(any(update.progress < 100 for update in updates))
        self.assertEqual("DIAGNOSIS_STARTED", snapshot.events[0].event_type)
        self.assertEqual("DIAGNOSIS_COMPLETED", snapshot.events[-1].event_type)
        self.assertEqual(
            [task.task_id for task in snapshot.tasks],
            [event.task_id for event in snapshot.events if event.event_type == "TASK_STARTED"],
        )
        progress_values = [event.metadata["progress"] for event in snapshot.events if event.event_type == "PROGRESS_UPDATED"]
        self.assertEqual(100, progress_values[-1])
        self.assertTrue(all(left < right for left, right in zip(progress_values, progress_values[1:])))
        observation = next(
            item for item in snapshot.task("current_system_status").evidence
            if item.get("metricType") == "observation_window"
        )
        self.assertEqual(3, observation["value"]["sampleCount"])
        self.assertEqual(1, len(provider_calls))
        self.assertEqual("DEVICE_DRIVER_CONFIGURATION_ISSUE", result.diagnosis_type)
        self.assertFalse(result.can_auto_recover)
        self.assertTrue(result.remote_as_recommended)
        self.assertEqual("PHYSICAL_INSPECTION", result.resolution_type)
        self.assertTrue(agent.can_offer_as(result, snapshot, session))
        self.assertEqual(
            "DIAGNOSING",
            agent.diagnosis_session_ui_state(session, metrics, snapshot, result, diagnosis_started=True),
        )
        self.assertEqual(
            "DIAGNOSIS_RESULT",
            agent.diagnosis_session_ui_state(
                session, metrics, snapshot, result, diagnosis_started=True, result_requested=True,
            ),
        )

    def test_black_screen_without_clear_device_evidence_is_insufficient(self) -> None:
        _, _, snapshot, result, _, provider_calls, _ = self.run_flow(None)

        self.assertEqual("PARTIALLY_COMPLETED", snapshot.state)
        self.assertEqual("INSUFFICIENT_EVIDENCE", result.diagnosis_type)
        self.assertFalse(result.can_auto_recover)
        self.assertFalse(result.remote_as_recommended)
        self.assertEqual("UNKNOWN", result.resolution_type)
        self.assertFalse(agent.can_offer_as(result, snapshot))
        self.assertNotIn("원격 AS 기사 점검 권장", result.recommended_actions)
        self.assertEqual(1, len(provider_calls))

    def test_problem_code_wording_uses_actual_device_and_exact_windows_state(self) -> None:
        expected = {
            22: ("그래픽 장치 비활성 상태가 확인되었습니다", "Windows에서 장치가 비활성 상태입니다."),
            43: ("그래픽 장치 오류 상태가 확인되었습니다", "장치가 문제를 보고하여 Windows가 장치를 중지한 상태입니다."),
        }
        for problem_code, (title, state_description) in expected.items():
            with self.subTest(problem_code=problem_code):
                _, _, _, result, _, _, _ = self.run_flow(problem_code)
                rendered = "\n".join((
                    result.title,
                    result.summary,
                    *(finding.summary for finding in result.findings),
                    *result.recommended_actions,
                ))
                self.assertEqual("DEVICE_DRIVER_CONFIGURATION_ISSUE", result.diagnosis_type)
                self.assertEqual(title, result.title)
                self.assertIn(f"Test Problem {problem_code} Display Adapter", rendered)
                self.assertIn(f"problem code {problem_code}", rendered)
                self.assertIn(state_description, rendered)
                if problem_code == 43:
                    self.assertNotIn("비활성 상태", rendered)
                    self.assertNotIn("구성 문제", rendered)
                self.assertNotIn("GPU가 고장 났습니다", rendered)
                self.assertNotIn(f"검은 화면의 원인은 Code {problem_code}입니다", rendered)
                self.assertNotIn("메인 GPU가 고장 났습니다", rendered)
                self.assertNotIn("자동으로 복구했습니다", rendered)

    def test_reset_starts_a_clean_second_diagnosis_with_new_workers_and_state(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        session_store = agent.DiagnosisSessionStore(root / "session.json")
        request = DiagnosisRequest(
            "diagnosis-completed",
            "device-1",
            "게임 중 검은 화면이 나타났다가 화면이 복구됩니다.",
            ("gpu",),
            STARTED_AT.isoformat(),
            (STARTED_AT + timedelta(minutes=5)).isoformat(),
            "LIVE",
        )
        session_store.accept(DiagnosisSession(request, "COMPLETED"))
        metrics_store = agent.MetricsStore(root / "metrics.json")
        metrics_store.begin(request.diagnosis_id, "LIVE")
        metrics_store.append(request.diagnosis_id, readings_at(STARTED_AT, 25.0))
        log_store = agent.DiagnosisLogStore(root / "progress.json")
        log_store.replace(agent.DiagnosisRunSnapshot(
            diagnosis_id=request.diagnosis_id,
            mode="LIVE",
            state="COMPLETED",
            progress=100,
            transition_allowed=True,
        ))
        result_store = agent.DiagnosisResultStore(root / "result.json")
        result_store.save(agent.DiagnosisResult(
            request.diagnosis_id, "WARNING", "완료", "완료", (), (), (), (),
            "UNKNOWN", False, (), STARTED_AT.isoformat(),
        ))

        processor = agent.DiagnosisRequestProcessor(
            session_store,
            device_id="device-1",
            now=lambda: STARTED_AT,
        )
        states: list[str] = []
        client = agent.AgentDiagnosisWebSocketClient(
            "http://localhost:8080",
            "agent-token",
            processor,
            on_state_changed=states.append,
        )
        client.authenticated = True

        agent.reset_diagnosis_session_state(session_store, metrics_store, log_store, result_store)
        self.assertTrue(client.mark_idle())
        self.assertEqual("IDLE", client.state)
        self.assertIsNone(session_store.session)
        self.assertIsNone(metrics_store.snapshot.diagnosis_id)
        self.assertEqual((), metrics_store.snapshot.readings)
        self.assertIsNone(log_store.snapshot.diagnosis_id)
        self.assertIsNone(result_store.result)

        decision = processor.process({
            "diagnosisId": "diagnosis-next",
            "deviceId": "device-1",
            "symptom": GRAPHICS_CODE43_REMOTE_SUPPORT_SYMPTOM,
            "requestedChecks": ["gpu"],
            "requestedAt": STARTED_AT.isoformat(),
            "expiresAt": (STARTED_AT + timedelta(minutes=5)).isoformat(),
            "mode": "DEMO",
        }, authenticated=True)
        self.assertEqual("ACCEPTED", decision.status)
        self.assertEqual("diagnosis-next", session_store.session.request.diagnosis_id)

        provider_factory_calls: list[str] = []
        metrics_updates: list[agent.MetricsSnapshot] = []
        coordinator = agent.InitialMetricsCoordinator(
            metrics_store,
            live_provider_factory=lambda: DemoSensorProvider(),
            demo_provider_factory=lambda: provider_factory_calls.append("DEMO") or DemoSensorProvider(),
            settings=InitialCollectionSettings(
                sample_count=1,
                sample_interval_seconds=0.01,
                sample_timeout_seconds=0.5,
                steady_interval_seconds=0.05,
            ),
            on_update=metrics_updates.append,
        )
        second_windows_snapshot = Code43RemoteSupportDemoGraphicsProvider(
            now=lambda: STARTED_AT + timedelta(seconds=1),
        ).collect()
        handlers = agent.graphics_diagnosis_task_handlers(
            lambda: session_store.session,
            lambda: metrics_store.snapshot,
            lambda: log_store.snapshot,
            lambda: second_windows_snapshot,
            observation_timeout_seconds=0.1,
        )
        updates: list[agent.DiagnosisRunSnapshot] = []
        orchestrator = agent.DiagnosisOrchestrator(
            lambda: metrics_store.snapshot,
            log_store,
            settings=agent.DiagnosisSettings(
                task_weights=agent.GRAPHICS_DIAGNOSIS_TASK_WEIGHTS,
                task_timeout_seconds=1.0,
                session_timeout_seconds=5.0,
                max_retries=1,
            ),
            task_handlers=handlers,
            task_definitions=agent.GRAPHICS_DIAGNOSIS_TASK_DEFINITIONS,
            task_labels=agent.GRAPHICS_DIAGNOSIS_TASK_LABELS,
            on_update=updates.append,
            now=lambda: STARTED_AT + timedelta(minutes=1),
        )

        second_session = agent.start_initial_metrics_session(
            decision.session,
            "DEMO",
            "device-1",
            session_store,
            metrics_store,
            result_store,
            orchestrator,
            coordinator,
        )
        self.assertIsNotNone(second_session)
        self.assertTrue(coordinator.wait(2.0))
        self.assertTrue(coordinator.is_running("diagnosis-next"))
        initial_metrics_thread = coordinator._thread
        repeated_session = agent.start_initial_metrics_session(
            second_session,
            "DEMO",
            "device-1",
            session_store,
            metrics_store,
            result_store,
            orchestrator,
            coordinator,
        )

        self.assertEqual("diagnosis-next", repeated_session.request.diagnosis_id)
        self.assertIs(initial_metrics_thread, coordinator._thread)
        self.assertEqual(["DEMO"], provider_factory_calls)
        self.assertEqual("diagnosis-next", metrics_store.snapshot.diagnosis_id)
        self.assertTrue(metrics_store.snapshot.initial_complete)
        self.assertTrue(metrics_updates)
        self.assertIsNone(result_store.result)
        self.assertEqual("diagnosis-next", log_store.snapshot.diagnosis_id)
        self.assertEqual((), log_store.snapshot.events)

        for index in range(1, 4):
            metrics_store.append(
                "diagnosis-next",
                readings_at(STARTED_AT + timedelta(minutes=1, seconds=index), 20.0 + index),
            )

        started = agent.start_diagnosis_once(
            second_session,
            session_store,
            metrics_store,
            orchestrator,
            result_store,
        )
        diagnosis_thread = orchestrator._thread
        duplicate = agent.start_diagnosis_once(
            second_session,
            session_store,
            metrics_store,
            orchestrator,
            result_store,
        )
        self.assertIsNotNone(started)
        self.assertIsNone(duplicate)
        self.assertIs(diagnosis_thread, orchestrator._thread)
        self.assertTrue(orchestrator.wait(5.0))
        self.assertTrue(coordinator.stop(2.0))

        second_snapshot = log_store.snapshot
        second_result = agent.DiagnosisRuleEngine().evaluate(metrics_store.snapshot, second_snapshot)
        result_store.save(second_result)
        session_store.update_state("COMPLETED")

        self.assertEqual("diagnosis-next", second_snapshot.diagnosis_id)
        self.assertEqual("COMPLETED", second_snapshot.state)
        self.assertTrue(any(event.event_type == "PROGRESS_UPDATED" for event in second_snapshot.events))
        self.assertEqual(
            1,
            sum(event.event_type == "DIAGNOSIS_STARTED" for event in second_snapshot.events),
        )
        self.assertTrue(all(event.event_id for event in second_snapshot.events))
        self.assertNotIn("diagnosis-completed", json.dumps(second_snapshot.to_dict()))
        self.assertEqual("diagnosis-next", result_store.result.diagnosis_id)
        self.assertNotEqual(request.diagnosis_id, result_store.result.diagnosis_id)
        self.assertEqual("COMPLETED", session_store.session.agent_state)


if __name__ == "__main__":
    unittest.main()
