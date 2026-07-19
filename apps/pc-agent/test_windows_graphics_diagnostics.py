from __future__ import annotations

import unittest
from datetime import datetime, timezone

from initial_metrics import AVAILABLE, FAILED, PERMISSION_REQUIRED
from windows_graphics_diagnostics import (
    Code43RemoteSupportDemoGraphicsProvider,
    DEVICE_REPORTED_PROBLEM,
    DISABLED,
    NO_RESULTS,
    OK,
    QUERY_FAILED,
    PowerShellQueryResult,
    WindowsGraphicsDiagnosticsProvider,
)
from pc_agent_demo_scenarios import GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID


QUERIED_AT = datetime(2026, 7, 14, 7, 0, tzinfo=timezone.utc)


class FakePowerShell:
    def __init__(self, responses: dict[str, PowerShellQueryResult]) -> None:
        self.responses = responses
        self.calls: list[str] = []

    def query(
        self,
        query_name: str,
        script: str,
        timeout_seconds: float = 12.0,
    ) -> PowerShellQueryResult:
        self.calls.append(query_name)
        return self.responses[query_name]


def event_responses(status: str = NO_RESULTS) -> dict[str, PowerShellQueryResult]:
    return {
        "graphics_events": PowerShellQueryResult(status),
        "whea_events": PowerShellQueryResult(status),
        "kernel_power_events": PowerShellQueryResult(status),
    }


class WindowsGraphicsDiagnosticsProviderTest(unittest.TestCase):
    def test_code43_demo_fixture_contains_normal_iris_and_arc_problem_without_fake_events(self) -> None:
        snapshot = Code43RemoteSupportDemoGraphicsProvider(now=lambda: QUERIED_AT).collect()

        self.assertEqual("DEMO", snapshot.data_mode)
        self.assertEqual(GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID, snapshot.scenario_id)
        self.assertEqual(
            (("Intel(R) Iris(R) Xe Graphics", 0, OK),
             ("Intel(R) Arc(TM) A350M Graphics", 43, DEVICE_REPORTED_PROBLEM)),
            tuple((device.device_name, device.problem_code, device.status) for device in snapshot.devices),
        )
        self.assertTrue(all(device.driver_provider == "Intel Corporation" for device in snapshot.devices))
        self.assertTrue(all(device.driver_signed is True for device in snapshot.devices))
        self.assertEqual((), snapshot.graphics_events)
        self.assertEqual((), snapshot.whea_events)
        self.assertEqual((), snapshot.kernel_power_events)
        self.assertEqual(NO_RESULTS, snapshot.graphics_event_query.status)
        self.assertEqual(NO_RESULTS, snapshot.whea_event_query.status)
        context = next(item for item in snapshot.to_evidence() if item.metric_type == "demo_scenario")
        self.assertEqual(
            {"dataMode": "DEMO", "scenarioId": GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID},
            context.value,
        )

    def test_normalizes_display_device_and_signed_driver_metadata(self) -> None:
        responses = {
            "display_devices": PowerShellQueryResult(OK, ({
                "deviceName": "Test Display Adapter",
                "instanceId": "PCI\\VEN_1234&DEV_5678",
                "pnpStatus": "OK",
                "problemCode": 0,
                "problemCodeQueryStatus": "OK",
                "deviceClass": "Display",
                "manufacturer": "Test Manufacturer",
                "source": "Win32_PnPEntity",
            },)),
            "display_drivers": PowerShellQueryResult(OK, ({
                "deviceName": "Test Display Adapter",
                "instanceId": "pci\\ven_1234&dev_5678",
                "deviceClass": "DISPLAY",
                "manufacturer": "Test Manufacturer",
                "provider": "Test Driver Provider",
                "version": "31.0.0.1",
                "date": "2026-01-02T00:00:00+00:00",
                "signed": True,
                "signer": "Microsoft Windows Hardware Compatibility Publisher",
                "infName": "oem42.inf",
            },)),
            **event_responses(),
        }
        provider = WindowsGraphicsDiagnosticsProvider(FakePowerShell(responses), now=lambda: QUERIED_AT)

        snapshot = provider.collect()

        self.assertEqual(1, len(snapshot.devices))
        device = snapshot.devices[0]
        self.assertEqual(OK, device.status)
        self.assertEqual(0, device.problem_code)
        self.assertEqual("Test Driver Provider", device.driver_provider)
        self.assertEqual("31.0.0.1", device.driver_version)
        self.assertEqual("2026-01-02T00:00:00+00:00", device.driver_date)
        self.assertTrue(device.driver_signed)
        self.assertEqual("oem42.inf", device.inf_name)
        self.assertEqual("Win32_PnPEntity", device.device_source)
        evidence = snapshot.to_evidence()
        self.assertTrue(any(item.category == "DEVICE" and item.status == OK for item in evidence))
        self.assertTrue(any(item.category == "DRIVER" and item.status == OK for item in evidence))
        device_payload = next(item.to_dict() for item in evidence if item.category == "DEVICE")
        self.assertTrue({
            "source", "category", "component", "code", "status", "value", "occurredAt", "description",
        }.issubset(device_payload))

    def test_problem_code_22_is_disabled_without_hardware_failure_inference(self) -> None:
        responses = {
            "display_devices": PowerShellQueryResult(OK, ({
                "deviceName": "Disabled Display Adapter",
                "instanceId": "PCI\\VEN_9999&DEV_0001",
                "pnpStatus": "Error",
                "problemCode": 22,
                "problemCodeQueryStatus": "OK",
                "deviceClass": "Display",
                "manufacturer": "Test Manufacturer",
                "source": "Get-PnpDevice/Get-PnpDeviceProperty",
            },)),
            "display_drivers": PowerShellQueryResult(NO_RESULTS),
            **event_responses(),
        }
        provider = WindowsGraphicsDiagnosticsProvider(FakePowerShell(responses), now=lambda: QUERIED_AT)

        snapshot = provider.collect()

        device = snapshot.devices[0]
        self.assertEqual(DISABLED, device.status)
        self.assertEqual(22, device.problem_code)
        evidence = next(item for item in snapshot.to_evidence() if item.metric_type == "display_device_status")
        self.assertEqual(22, evidence.code)
        self.assertEqual(DISABLED, evidence.status)
        self.assertEqual("Disabled Display Adapter", evidence.value["deviceName"])
        self.assertEqual("PCI\\VEN_9999&DEV_0001", evidence.value["instanceId"])
        self.assertNotIn("hardware failure", evidence.description.casefold())

    def test_event_no_results_query_failure_and_permission_are_distinct(self) -> None:
        responses = {
            "display_devices": PowerShellQueryResult(QUERY_FAILED, error="PnP query timed out"),
            "display_devices_pnp": PowerShellQueryResult(QUERY_FAILED, error="PnP fallback timed out"),
            "display_drivers": PowerShellQueryResult(OK, ({
                "deviceName": "Driver-only Display Adapter",
                "instanceId": "PCI\\VEN_1111&DEV_2222",
                "deviceClass": "DISPLAY",
                "manufacturer": "Test Manufacturer",
                "provider": "Test Driver Provider",
                "version": "31.0.0.2",
                "date": "2026-02-03T00:00:00+00:00",
                "signed": True,
                "signer": "Test Signer",
                "infName": "oem43.inf",
            },)),
            "graphics_events": PowerShellQueryResult(NO_RESULTS),
            "whea_events": PowerShellQueryResult(QUERY_FAILED, error="WHEA query failed"),
            "kernel_power_events": PowerShellQueryResult(PERMISSION_REQUIRED, error="Access denied"),
        }
        provider = WindowsGraphicsDiagnosticsProvider(FakePowerShell(responses), now=lambda: QUERIED_AT)

        snapshot = provider.collect()
        evidence = {item.task_id: item for item in snapshot.to_evidence()}

        self.assertEqual(NO_RESULTS, snapshot.graphics_event_query.status)
        self.assertEqual(QUERY_FAILED, snapshot.whea_event_query.status)
        self.assertEqual(PERMISSION_REQUIRED, snapshot.kernel_power_event_query.status)
        self.assertEqual(1, len(snapshot.devices))
        self.assertEqual(QUERY_FAILED, snapshot.devices[0].status)
        self.assertEqual("Win32_PnPSignedDriver", snapshot.devices[0].device_source)
        self.assertEqual(AVAILABLE, evidence["windows_graphics_events"].availability)
        self.assertEqual(0, evidence["windows_graphics_events"].value)
        self.assertEqual(FAILED, evidence["windows_whea_events"].availability)
        self.assertEqual(PERMISSION_REQUIRED, evidence["windows_kernel_power_events"].availability)


if __name__ == "__main__":
    unittest.main()
