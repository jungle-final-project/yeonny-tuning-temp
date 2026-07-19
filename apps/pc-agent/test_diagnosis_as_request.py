import io
import json
import unittest
import urllib.error
from typing import Any

from diagnosis_as_request import (
    DiagnosisAsAuthenticationError,
    DiagnosisAsConsentRequiredError,
    DiagnosisAsNetworkError,
    DiagnosisAsRequestClient,
    DiagnosisAsResponseError,
    DiagnosisAsServerError,
    DiagnosisAsValidationError,
    build_diagnosis_as_request,
    resolve_as_web_url,
)
from diagnosis_request_agent import STANDALONE, DiagnosisRequest, DiagnosisSession
from diagnosis_result import DiagnosisEvidence, DiagnosisFinding, DiagnosisResult
from pc_agent_demo_scenarios import GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID


class FakeResponse:
    def __init__(self, payload: bytes) -> None:
        self.payload = payload

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, *_: Any) -> None:
        return None

    def read(self) -> bytes:
        return self.payload


def make_session(mode: str = "LIVE", state: str = "COMPLETED") -> DiagnosisSession:
    return DiagnosisSession(
        DiagnosisRequest(
            "diagnosis-1",
            "device-1",
            "게임 실행 후 프레임이 급격히 저하됩니다.",
            ("gpu", "cooling"),
            "2026-07-14T01:00:00Z",
            "2026-07-14T01:02:00Z",
            mode,
        ),
        state,
    )


def make_evidence(
    metric_type: str = "temperature",
    value: float | str | None = 91.0,
    availability: str = "AVAILABLE",
) -> DiagnosisEvidence:
    return DiagnosisEvidence(
        "gpu_usage_temperature",
        "gpu",
        metric_type,
        value,
        "°C" if metric_type == "temperature" else "RPM",
        availability,
        "ABNORMAL",
        "nvidia-smi",
        "2026-07-14T01:01:30Z",
        failure_reason="지원되지 않음" if availability != "AVAILABLE" else None,
    )


def make_result(*items: DiagnosisEvidence, resolution_type: str = "PHYSICAL_INSPECTION") -> DiagnosisResult:
    actual = items or (make_evidence(), make_evidence("fan_rpm", 0.0))
    return DiagnosisResult(
        "diagnosis-1",
        "CRITICAL",
        "GPU 냉각 계통 이상 가능성이 높습니다.",
        "GPU 고온과 팬 회전 이상 근거가 함께 감지되었습니다.",
        tuple(actual),
        (DiagnosisFinding(
            "GPU_COOLING_PHYSICAL_RISK",
            "CRITICAL",
            "GPU 냉각 이상",
            "복수 근거가 감지되었습니다.",
            tuple(item.key for item in actual),
            ("냉각 계통 이상",),
            ("GPU 냉각 팬 점검",),
            resolution_type,
        ),),
        ("냉각 계통 이상",),
        ("GPU 냉각 팬 점검",),
        resolution_type,
        False,
        (),
        "2026-07-14T01:01:45Z",
    )


def make_normal_result() -> DiagnosisResult:
    """이상 근거가 없는 정상 결과 — findings가 비어 있고 resolution_type이 NONE이다."""
    return DiagnosisResult(
        "diagnosis-1",
        "NORMAL",
        "측정된 하드웨어 상태가 정상 범위입니다.",
        "이상 근거가 확인되지 않았습니다.",
        (make_evidence(), make_evidence("fan_rpm", 1200.0)),
        (),
        (),
        ("현재 상태 유지",),
        "NONE",
        False,
        (),
        "2026-07-14T01:01:45Z",
    )


def make_device_configuration_result(
    *,
    problem_code: int = 22,
    data_mode: str = "LIVE",
    scenario_id: str | None = None,
) -> DiagnosisResult:
    sampled_at = "2026-07-14T01:01:30Z"
    instance_id = "PCI\\VEN_8086&DEV_5694"
    device = DiagnosisEvidence(
        "windows_display_devices", "gpu", "display_device_status",
        {
            "deviceName": "Intel(R) Arc(TM) A350M Graphics",
            "instanceId": instance_id,
            "problemCode": problem_code,
            "problemCodeQueryStatus": "OK",
        },
        "", "AVAILABLE", "DISABLED", "Win32_PnPEntity", sampled_at,
        category="DEVICE", code=problem_code, occurred_at=sampled_at,
    )
    driver = DiagnosisEvidence(
        "windows_display_drivers", "gpu", "display_driver",
        {
            "deviceName": "Intel(R) Arc(TM) A350M Graphics",
            "instanceId": instance_id,
            "provider": "Intel Corporation",
            "version": "32.0.101.8826",
            "date": "2026-05-29T00:00:00Z",
        },
        "", "AVAILABLE", "OK", "Win32_PnPSignedDriver", sampled_at,
        category="DRIVER", occurred_at=sampled_at,
    )
    symptom = DiagnosisEvidence(
        "symptom_correlation", "system", "symptom_correlation",
        "게임 중 검은 화면이 나타났다가 복구됩니다.", "", "AVAILABLE", "OK", "WEB_REQUEST", sampled_at,
        category="SYSTEM", occurred_at=sampled_at,
    )
    finding = DiagnosisFinding(
        "DEVICE_DRIVER_CONFIGURATION_ISSUE", "WARNING", "그래픽 장치 비활성 상태",
        f"실제 Code {problem_code}이 확인됐습니다.", (device.key, symptom.key), (), ("원격 기사 점검",),
        "PHYSICAL_INSPECTION",
    )
    return DiagnosisResult(
        "diagnosis-1", "WARNING", "그래픽 장치 비활성 상태가 확인되었습니다",
        "Windows에서 그래픽 장치가 비활성 상태로 확인됐습니다.",
        (device, driver, symptom), (finding,), (), ("원격 AS 기사 점검 권장",),
        "PHYSICAL_INSPECTION", False, (), "2026-07-14T01:01:45Z",
        diagnosis_type="DEVICE_DRIVER_CONFIGURATION_ISSUE", remote_as_recommended=True,
        data_mode=data_mode, scenario_id=scenario_id,
    )


class DiagnosisAsRequestTest(unittest.TestCase):
    def test_device_configuration_payload_includes_actual_device_driver_and_result_metadata(self) -> None:
        payload = build_diagnosis_as_request(
            make_session(), make_device_configuration_result(), consent_accepted=True,
        ).to_dict()

        device = next(item for item in payload["evidenceSummary"] if item["category"] == "DEVICE")
        driver = next(item for item in payload["evidenceSummary"] if item["category"] == "DRIVER")
        self.assertEqual(22, device["code"])
        self.assertEqual("Intel(R) Arc(TM) A350M Graphics", device["value"]["deviceName"])
        self.assertEqual("DEVICE_DRIVER_CONFIGURATION_ISSUE", device["diagnosisType"])
        self.assertFalse(device["canAutoRecover"])
        self.assertEqual("Intel Corporation", driver["value"]["provider"])
        self.assertEqual("32.0.101.8826", driver["value"]["version"])

    def test_verified_code43_demo_can_create_as_request_but_other_demo_cannot(self) -> None:
        result = make_device_configuration_result(
            problem_code=43,
            data_mode="DEMO",
            scenario_id=GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID,
        )

        payload = build_diagnosis_as_request(
            make_session("DEMO"), result, consent_accepted=True,
        ).to_dict()

        self.assertEqual("DEMO", payload["mode"])
        self.assertEqual(43, payload["evidenceSummary"][0]["code"])
        with self.assertRaises(DiagnosisAsValidationError):
            build_diagnosis_as_request(
                make_session("DEMO"),
                make_device_configuration_result(
                    problem_code=43,
                    data_mode="DEMO",
                    scenario_id="UNVERIFIED_DEMO",
                ),
                consent_accepted=True,
            )

    def test_live_and_demo_payload_use_actual_result(self) -> None:
        live = build_diagnosis_as_request(make_session(), make_result(), consent_accepted=True).to_dict()
        demo = build_diagnosis_as_request(make_session("DEMO"), make_result(), consent_accepted=True).to_dict()
        self.assertEqual(live["requestType"], "PHYSICAL_INSPECTION")
        self.assertEqual(live["evidenceSummary"][1]["value"], 0.0)
        self.assertTrue(live["consentAccepted"])
        self.assertEqual(demo["mode"], "DEMO")

    def test_validates_consent_session_result_device_and_evidence(self) -> None:
        with self.assertRaises(DiagnosisAsConsentRequiredError):
            build_diagnosis_as_request(make_session(), make_result(), consent_accepted=False)
        with self.assertRaises(DiagnosisAsValidationError):
            build_diagnosis_as_request(make_session(state="RUNNING"), make_result(), consent_accepted=True)
        with self.assertRaises(DiagnosisAsValidationError):
            build_diagnosis_as_request(
                make_session(), make_result(), consent_accepted=True, expected_device_id="device-2"
            )
        unsupported = make_evidence(value=None, availability="UNSUPPORTED")
        with self.assertRaises(DiagnosisAsValidationError):
            build_diagnosis_as_request(make_session(), make_result(unsupported), consent_accepted=True)

    def test_normal_result_can_create_as_request_from_web_session(self) -> None:
        normal = make_normal_result()

        payload = build_diagnosis_as_request(make_session(), normal, consent_accepted=True).to_dict()

        self.assertEqual("PHYSICAL_INSPECTION", payload["requestType"])
        self.assertTrue(payload["evidenceSummary"])
        self.assertEqual([], payload["evidenceSummary"][0]["findingCodes"])

    def test_normal_result_requires_web_symptom_session(self) -> None:
        standalone = DiagnosisSession(
            DiagnosisRequest(
                "diagnosis-1",
                "device-1",
                "",
                ("gpu",),
                "2026-07-14T01:00:00Z",
                "2026-07-14T01:02:00Z",
                "LIVE",
                STANDALONE,
            ),
            "COMPLETED",
        )

        with self.assertRaises(DiagnosisAsValidationError):
            build_diagnosis_as_request(standalone, make_normal_result(), consent_accepted=True)

    def test_posts_bearer_json_and_uses_diagnosis_idempotency_key(self) -> None:
        captured: dict[str, Any] = {}

        def opener(request: Any, timeout: int) -> FakeResponse:
            captured["request"] = request
            captured["timeout"] = timeout
            return FakeResponse(json.dumps({
                "requestId": "request-1",
                "requestNumber": "AS-20260714-0001",
                "status": "CREATED",
                "createdAt": "2026-07-14T01:02:00Z",
                "requestType": "PHYSICAL_INSPECTION",
                "symptom": make_session().request.symptom,
                "diagnosisTitle": make_result().title,
                "diagnosisSummary": make_result().summary,
                "mode": "LIVE",
            }).encode())

        client = DiagnosisAsRequestClient(
            "http://localhost:8080",
            "agent-token",
            web_base_url="http://localhost:5173",
            device_id="device-1",
            opener=opener,
        )
        response = client.create(make_session(), make_result(), consent_accepted=True)
        request = captured["request"]
        self.assertEqual(request.full_url, "http://localhost:8080/api/agent/as-requests")
        self.assertEqual(request.get_header("Authorization"), "Bearer agent-token")
        self.assertEqual(request.get_header("Idempotency-key"), "diagnosis-1")
        self.assertEqual(json.loads(request.data)["symptom"], make_session().request.symptom)
        self.assertEqual(response.web_url, "http://localhost:5173/support/request-1")
        self.assertEqual(response.request_type, "PHYSICAL_INSPECTION")
        self.assertEqual(response.symptom, make_session().request.symptom)
        self.assertEqual(response.diagnosis_title, make_result().title)

    def test_posts_the_exact_prebuilt_payload_without_rereading_session_state(self) -> None:
        captured: dict[str, Any] = {}

        def opener(request: Any, timeout: int) -> FakeResponse:
            captured["body"] = json.loads(request.data)
            return FakeResponse(json.dumps({
                "requestId": "request-1",
                "requestNumber": "AS-20260714-0001",
                "status": "OPEN",
                "createdAt": "2026-07-14T01:02:00Z",
            }).encode())

        payload = build_diagnosis_as_request(make_session(), make_result(), consent_accepted=True)
        client = DiagnosisAsRequestClient(
            "http://localhost:8080", "agent-token", device_id="device-1", opener=opener
        )
        client.create_request(payload)

        self.assertEqual(captured["body"], payload.to_dict())

    def test_web_url_must_match_configured_origin(self) -> None:
        self.assertEqual(
            resolve_as_web_url("https://app.example.com", "/support/request-1", "request-1"),
            "https://app.example.com/support/request-1",
        )
        with self.assertRaises(DiagnosisAsResponseError):
            resolve_as_web_url("https://app.example.com", "https://evil.example/support/1", "request-1")
        with self.assertRaises(DiagnosisAsResponseError):
            resolve_as_web_url("https://app.example.com", "javascript:alert(1)", "request-1")

    def test_classifies_auth_network_server_and_invalid_response(self) -> None:
        with self.assertRaises(DiagnosisAsAuthenticationError):
            DiagnosisAsRequestClient("https://api.example.com", None).create(
                make_session(), make_result(), consent_accepted=True
            )

        def http_error(code: int, body: bytes):
            def opener(*_args: Any, **_kwargs: Any) -> FakeResponse:
                raise urllib.error.HTTPError("https://api.example.com", code, "error", {}, io.BytesIO(body))
            return opener

        with self.assertRaises(DiagnosisAsAuthenticationError):
            DiagnosisAsRequestClient("https://api.example.com", "token", opener=http_error(401, b"expired")).create(
                make_session(), make_result(), consent_accepted=True
            )
        with self.assertRaises(DiagnosisAsServerError) as server:
            DiagnosisAsRequestClient("https://api.example.com", "token", opener=http_error(503, b"failed")).create(
                make_session(), make_result(), consent_accepted=True
            )
        self.assertTrue(server.exception.retryable)
        self.assertNotIn("failed", str(server.exception))

        def offline(*_args: Any, **_kwargs: Any) -> FakeResponse:
            raise urllib.error.URLError("offline")

        with self.assertRaises(DiagnosisAsNetworkError):
            DiagnosisAsRequestClient("https://api.example.com", "token", opener=offline).create(
                make_session(), make_result(), consent_accepted=True
            )
        with self.assertRaises(DiagnosisAsResponseError):
            DiagnosisAsRequestClient(
                "https://api.example.com", "token", opener=lambda *_a, **_k: FakeResponse(b"not-json")
            ).create(make_session(), make_result(), consent_accepted=True)

    def test_rejects_non_success_business_status_in_2xx_response(self) -> None:
        response = json.dumps({
            "requestId": "request-1",
            "requestNumber": "AS-20260714-0001",
            "status": "REJECTED",
            "createdAt": "2026-07-14T01:02:00Z",
        }).encode()
        client = DiagnosisAsRequestClient(
            "https://api.example.com", "token", opener=lambda *_a, **_k: FakeResponse(response)
        )
        with self.assertRaises(DiagnosisAsResponseError):
            client.create(make_session(), make_result(), consent_accepted=True)

    def test_accepts_current_status_when_idempotency_returns_an_existing_request(self) -> None:
        response = json.dumps({
            "requestId": "request-1",
            "requestNumber": "AS-20260714-0001",
            "status": "IN_PROGRESS",
            "createdAt": "2026-07-14T01:02:00Z",
        }).encode()
        client = DiagnosisAsRequestClient(
            "https://api.example.com", "token", opener=lambda *_a, **_k: FakeResponse(response)
        )

        actual = client.create(make_session(), make_result(), consent_accepted=True)

        self.assertEqual(actual.status, "IN_PROGRESS")

    def test_response_requires_all_server_fields(self) -> None:
        response = json.dumps({
            "requestId": "request-1",
            "status": "CREATED",
            "createdAt": "2026-07-14T01:02:00Z",
        }).encode()
        client = DiagnosisAsRequestClient(
            "https://api.example.com", "token", opener=lambda *_a, **_k: FakeResponse(response)
        )
        with self.assertRaises(DiagnosisAsResponseError):
            client.create(make_session(), make_result(), consent_accepted=True)


if __name__ == "__main__":
    unittest.main()
