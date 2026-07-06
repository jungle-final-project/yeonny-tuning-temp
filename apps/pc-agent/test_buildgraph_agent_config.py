from __future__ import annotations

import hashlib
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import buildgraph_agent as agent
from buildgraph_agent import ConfigError, import_activation_config, load_config, main


class FakeHttpResponse:
    def __init__(self, payload: dict) -> None:
        self.payload = payload

    def __enter__(self) -> "FakeHttpResponse":
        return self

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        return None

    def read(self) -> bytes:
        return json.dumps(self.payload).encode("utf-8")


class AgentConfigTest(unittest.TestCase):
    def write_config(self, data: dict) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "agent-config.json"
        path.write_text(json.dumps(data), encoding="utf-8")
        return path

    def valid_config(self, **overrides: object) -> dict:
        data = {
            "apiBaseUrl": "http://localhost:8080",
            "activationToken": "demo-agent-activation-token",
            "deviceFingerprintHash": "fingerprint-hash",
            "osVersion": "Windows 11",
            "agentVersion": "0.1.0",
            "policyVersion": "policy-v1",
        }
        data.update(overrides)
        return data

    def test_loads_valid_config(self) -> None:
        path = self.write_config(self.valid_config(agentToken="agent-token"))

        config = load_config(path)

        self.assertEqual(config.api_base_url, "http://localhost:8080")
        self.assertEqual(config.activation_token, "demo-agent-activation-token")
        self.assertEqual(config.device_fingerprint_hash, "fingerprint-hash")
        self.assertEqual(config.os_version, "Windows 11")
        self.assertEqual(config.agent_version, "0.1.0")
        self.assertEqual(config.policy_version, "policy-v1")
        self.assertEqual(config.agent_token, "agent-token")

    def test_loads_utf8_bom_config(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "agent-config.json"
        path.write_text(json.dumps(self.valid_config()), encoding="utf-8-sig")

        config = load_config(path)

        self.assertEqual(config.activation_token, "demo-agent-activation-token")

    def test_missing_required_config_field_fails_with_clear_message(self) -> None:
        data = self.valid_config()
        del data["activationToken"]
        path = self.write_config(data)

        with self.assertRaisesRegex(ConfigError, "Missing required config field: activationToken"):
            load_config(path)

    def test_status_without_agent_token_is_unregistered(self) -> None:
        path = self.write_config(self.valid_config())
        output = io.StringIO()

        with redirect_stdout(output):
            exit_code = main(["status", "--config", str(path)])

        self.assertEqual(exit_code, 0)
        self.assertEqual(output.getvalue().strip(), "UNREGISTERED")

    def test_status_with_agent_token_is_registered(self) -> None:
        path = self.write_config(self.valid_config(agentToken="agent-token"))
        output = io.StringIO()

        with redirect_stdout(output):
            exit_code = main(["status", "--config", str(path)])

        self.assertEqual(exit_code, 0)
        self.assertEqual(output.getvalue().strip(), "REGISTERED")

    def test_register_posts_without_authorization_and_saves_agent_token(self) -> None:
        path = self.write_config(self.valid_config())
        captured_requests = []

        def fake_urlopen(request: object, timeout: int) -> FakeHttpResponse:
            captured_requests.append((request, timeout))
            return FakeHttpResponse(
                {
                    "deviceId": "device-1",
                    "status": "ACTIVE",
                    "agentToken": "raw-agent-token",
                    "tokenType": "Bearer",
                }
            )

        output = io.StringIO()
        with patch("buildgraph_agent.urllib.request.urlopen", side_effect=fake_urlopen):
            with redirect_stdout(output):
                exit_code = main(["register", "--config", str(path)])

        self.assertEqual(exit_code, 0)
        self.assertIn("REGISTERED", output.getvalue())
        self.assertEqual(len(captured_requests), 2)

        request, timeout = captured_requests[0]
        self.assertEqual(timeout, 15)
        self.assertEqual(request.full_url, "http://localhost:8080/api/agent/devices/register")
        self.assertIsNone(request.get_header("Authorization"))

        request_body = json.loads(request.data.decode("utf-8"))
        expected_registration_key = "agent-register-" + hashlib.sha256(
            b"fingerprint-hash"
        ).hexdigest()[:32]
        self.assertEqual(
            request_body,
            {
                "activationToken": "demo-agent-activation-token",
                "deviceFingerprintHash": "fingerprint-hash",
                "registrationIdempotencyKey": expected_registration_key,
                "osVersion": "Windows 11",
                "agentVersion": "0.1.0",
                "policyVersion": "policy-v1",
            },
        )

        saved_config = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(saved_config["agentToken"], "raw-agent-token")

        consent_request, consent_timeout = captured_requests[1]
        self.assertEqual(consent_timeout, 15)
        self.assertEqual(consent_request.full_url, "http://localhost:8080/api/agent/consents")
        self.assertEqual(consent_request.headers["Authorization"], "Bearer raw-agent-token")
        consent_body = json.loads(consent_request.data.decode("utf-8"))
        self.assertEqual(consent_body["consentType"], "SERVER_UPLOAD")
        self.assertTrue(consent_body["accepted"])

        status_output = io.StringIO()
        with redirect_stdout(status_output):
            status_exit_code = main(["status", "--config", str(path)])

        self.assertEqual(status_exit_code, 0)
        self.assertEqual(status_output.getvalue().strip(), "REGISTERED")

    def test_import_activation_config_preserves_local_fingerprint_and_updates_token(self) -> None:
        path = self.write_config(self.valid_config(activationToken="old-token", deviceFingerprintHash="local-fingerprint"))
        activation_path = path.parent / "buildgraph-agent-activation.json"
        activation_path.write_text(
            json.dumps(
                {
                    "apiBaseUrl": "http://localhost:8080",
                    "webBaseUrl": "http://localhost:5173",
                    "activationToken": "download-token",
                    "environment": "local",
                }
            ),
            encoding="utf-8",
        )

        changed = import_activation_config(path, activation_path)
        saved = json.loads(path.read_text(encoding="utf-8"))

        self.assertTrue(changed)
        self.assertEqual(saved["activationToken"], "download-token")
        self.assertEqual(saved["deviceFingerprintHash"], "local-fingerprint")
        self.assertIsNone(saved["agentToken"])

    def test_import_activation_config_reads_token_from_executable_name(self) -> None:
        path = self.write_config(self.valid_config(activationToken="old-token", agentToken="old-agent-token"))

        with patch("buildgraph_agent.sys.executable", r"C:\Users\me\Downloads\BuildGraphAgent-download-token-1234567890.exe"):
            with patch.object(agent.sys, "frozen", True, create=True):
                changed = agent.import_activation_config(path)

        saved = json.loads(path.read_text(encoding="utf-8"))
        self.assertTrue(changed)
        self.assertEqual(saved["activationToken"], "download-token-1234567890")
        self.assertIsNone(saved["agentToken"])


if __name__ == "__main__":
    unittest.main()
