from __future__ import annotations

import json
import os
import subprocess
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable

from diagnosis_result import DiagnosisEvidence
from initial_metrics import AVAILABLE, FAILED, PERMISSION_REQUIRED, UNSUPPORTED
from pc_agent_demo_scenarios import (
    DEMO_DATA_MODE,
    GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID,
)


OK = "OK"
DISABLED = "DISABLED"
DRIVER_LOAD_FAILED = "DRIVER_LOAD_FAILED"
DEVICE_REPORTED_PROBLEM = "DEVICE_REPORTED_PROBLEM"
DRIVER_BLOCKED = "DRIVER_BLOCKED"
SIGNATURE_PROBLEM = "SIGNATURE_PROBLEM"
DEGRADED = "DEGRADED"
UNKNOWN = "UNKNOWN"
QUERY_FAILED = "QUERY_FAILED"
NO_RESULTS = "NO_RESULTS"

QUERY_STATUSES = {OK, NO_RESULTS, QUERY_FAILED, PERMISSION_REQUIRED, UNSUPPORTED}
DRIVER_LOAD_FAILED_CODES = {28, 31, 32, 37, 39}
DRIVER_BLOCKED_CODES = {48}
SIGNATURE_PROBLEM_CODES = {52}

DISPLAY_DEVICE_SOURCE = "Get-PnpDevice/Get-PnpDeviceProperty"
DISPLAY_DEVICE_CIM_SOURCE = "Win32_PnPEntity"
DISPLAY_DRIVER_SOURCE = "Win32_PnPSignedDriver"
WINDOWS_EVENT_SOURCE = "Get-WinEvent:System"


@dataclass(frozen=True)
class PowerShellQueryResult:
    status: str
    items: tuple[dict[str, Any], ...] = ()
    error: str | None = None


class PowerShellJsonRunner:
    def __init__(
        self,
        runner: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
        is_windows: bool | None = None,
        hidden_kwargs_provider: Callable[[], dict[str, Any]] | None = None,
    ) -> None:
        self.runner = runner
        self.is_windows = os.name == "nt" if is_windows is None else is_windows
        self.hidden_kwargs_provider = hidden_kwargs_provider or _default_hidden_subprocess_kwargs

    def query(
        self,
        query_name: str,
        script: str,
        timeout_seconds: float = 12.0,
    ) -> PowerShellQueryResult:
        if not self.is_windows:
            return PowerShellQueryResult(UNSUPPORTED, error="Windows PowerShell is unavailable on this OS")
        command = (
            "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)\n"
            + script
        )
        kwargs = self.hidden_kwargs_provider() if self.runner is subprocess.run else {}
        try:
            result = self.runner(
                ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout_seconds,
                check=False,
                **kwargs,
            )
        except FileNotFoundError:
            return PowerShellQueryResult(QUERY_FAILED, error="PowerShell executable not found")
        except subprocess.TimeoutExpired:
            return PowerShellQueryResult(QUERY_FAILED, error=f"{query_name} query timed out")
        except OSError as exception:
            return PowerShellQueryResult(QUERY_FAILED, error=f"{query_name} query failed: {exception}")

        if result.returncode != 0:
            error = (result.stderr or "").strip() or f"{query_name} query exited with {result.returncode}"
            return PowerShellQueryResult(QUERY_FAILED, error=error)

        payload = self._parse_payload(result.stdout)
        if payload is None:
            return PowerShellQueryResult(QUERY_FAILED, error=f"{query_name} returned invalid JSON")
        status = str(payload.get("queryStatus") or QUERY_FAILED).upper()
        if status not in QUERY_STATUSES:
            status = QUERY_FAILED
        raw_items = payload.get("items")
        if isinstance(raw_items, dict):
            raw_items = [raw_items]
        items = tuple(item for item in raw_items if isinstance(item, dict)) if isinstance(raw_items, list) else ()
        if status == OK and not items:
            status = NO_RESULTS
        error = _text(payload.get("error"))
        return PowerShellQueryResult(status, items, error)

    @staticmethod
    def _parse_payload(output: str) -> dict[str, Any] | None:
        lines = [line.strip().lstrip("\ufeff") for line in output.splitlines() if line.strip()]
        for line in reversed(lines):
            try:
                payload = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(payload, dict):
                return payload
        return None


@dataclass(frozen=True)
class WindowsDisplayDevice:
    device_name: str
    instance_id: str
    pnp_status: str | None
    problem_code: int | None
    problem_code_query_status: str
    device_class: str | None
    manufacturer: str | None
    driver_provider: str | None
    driver_version: str | None
    driver_date: str | None
    driver_signed: bool | None
    signer: str | None
    inf_name: str | None
    status: str
    queried_at: str
    device_source: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "deviceName": self.device_name,
            "instanceId": self.instance_id,
            "pnpStatus": self.pnp_status,
            "problemCode": self.problem_code,
            "problemCodeQueryStatus": self.problem_code_query_status,
            "deviceClass": self.device_class,
            "manufacturer": self.manufacturer,
            "driverProvider": self.driver_provider,
            "driverVersion": self.driver_version,
            "driverDate": self.driver_date,
            "driverSigned": self.driver_signed,
            "signer": self.signer,
            "infName": self.inf_name,
            "status": self.status,
            "queriedAt": self.queried_at,
            "source": self.device_source,
        }


@dataclass(frozen=True)
class WindowsGraphicsEvent:
    provider: str
    event_id: int
    level: int | None
    occurred_at: str
    component: str
    code: str
    status: str
    description: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "provider": self.provider,
            "eventId": self.event_id,
            "level": self.level,
            "occurredAt": self.occurred_at,
            "component": self.component,
            "code": self.code,
            "status": self.status,
            "description": self.description,
        }


@dataclass(frozen=True)
class WindowsGraphicsDiagnosticsSnapshot:
    queried_at: str
    devices: tuple[WindowsDisplayDevice, ...]
    graphics_events: tuple[WindowsGraphicsEvent, ...]
    whea_events: tuple[WindowsGraphicsEvent, ...]
    kernel_power_events: tuple[WindowsGraphicsEvent, ...]
    device_query: PowerShellQueryResult
    driver_query: PowerShellQueryResult
    graphics_event_query: PowerShellQueryResult
    whea_event_query: PowerShellQueryResult
    kernel_power_event_query: PowerShellQueryResult
    data_mode: str = "LIVE"
    scenario_id: str | None = None

    def query_statuses(self) -> dict[str, str]:
        return {
            "displayDevices": self.device_query.status,
            "displayDrivers": self.driver_query.status,
            "graphicsEvents": self.graphics_event_query.status,
            "wheaEvents": self.whea_event_query.status,
            "kernelPowerEvents": self.kernel_power_event_query.status,
        }

    def to_evidence(self) -> tuple[DiagnosisEvidence, ...]:
        evidence: list[DiagnosisEvidence] = []
        if self.data_mode == DEMO_DATA_MODE and self.scenario_id:
            evidence.append(DiagnosisEvidence(
                task_id="windows_display_devices",
                component="system",
                metric_type="demo_scenario",
                value={"dataMode": self.data_mode, "scenarioId": self.scenario_id},
                unit="",
                availability=AVAILABLE,
                status="ACTIVE",
                source="demo-scenario",
                sampled_at=self.queried_at,
                category="SYSTEM",
                code=self.scenario_id,
                occurred_at=self.queried_at,
                description="Explicitly activated PC Agent demo scenario",
            ))
        for device in self.devices:
            device_value = {
                "deviceName": device.device_name,
                "instanceId": device.instance_id,
                "pnpStatus": device.pnp_status,
                "problemCode": device.problem_code,
                "problemCodeQueryStatus": device.problem_code_query_status,
                "deviceClass": device.device_class,
                "manufacturer": device.manufacturer,
            }
            evidence.append(DiagnosisEvidence(
                task_id="windows_display_devices",
                component="gpu",
                metric_type="display_device_status",
                value=device_value,
                unit="",
                availability=AVAILABLE,
                status=device.status,
                source=device.device_source,
                sampled_at=device.queried_at,
                category="DEVICE",
                code=device.problem_code,
                occurred_at=device.queried_at,
                description="Windows Display PnP device state",
            ))
            driver_value = {
                "deviceName": device.device_name,
                "instanceId": device.instance_id,
                "provider": device.driver_provider,
                "version": device.driver_version,
                "date": device.driver_date,
                "signed": device.driver_signed,
                "signer": device.signer,
                "infName": device.inf_name,
            }
            if any(value is not None for key, value in driver_value.items() if key not in {"deviceName", "instanceId"}):
                driver_status = SIGNATURE_PROBLEM if device.driver_signed is False else OK
                evidence.append(DiagnosisEvidence(
                    task_id="windows_display_drivers",
                    component="gpu",
                    metric_type="display_driver",
                    value=driver_value,
                    unit="",
                    availability=AVAILABLE,
                    status=driver_status,
                    source=DISPLAY_DRIVER_SOURCE,
                    sampled_at=device.queried_at,
                    category="DRIVER",
                    code="DRIVER_SIGNATURE" if device.driver_signed is False else "DRIVER_METADATA",
                    occurred_at=device.queried_at,
                    description="Windows signed display driver metadata",
                ))

        evidence.extend(_query_state_evidence(
            "windows_display_devices", "gpu", "DEVICE", DISPLAY_DEVICE_SOURCE,
            self.device_query, self.queried_at,
        ))
        evidence.extend(_query_state_evidence(
            "windows_display_drivers", "gpu", "DRIVER", DISPLAY_DRIVER_SOURCE,
            self.driver_query, self.queried_at,
        ))
        evidence.extend(_event_evidence(
            "windows_graphics_events", "DRIVER", self.graphics_events,
            self.graphics_event_query, self.queried_at,
        ))
        evidence.extend(_event_evidence(
            "windows_whea_events", "HARDWARE", self.whea_events,
            self.whea_event_query, self.queried_at,
        ))
        evidence.extend(_event_evidence(
            "windows_kernel_power_events", "SYSTEM", self.kernel_power_events,
            self.kernel_power_event_query, self.queried_at,
        ))
        return tuple(evidence)

    def to_dict(self) -> dict[str, Any]:
        return {
            "queriedAt": self.queried_at,
            "dataMode": self.data_mode,
            "scenarioId": self.scenario_id,
            "queryStatuses": self.query_statuses(),
            "queryErrors": {
                "displayDevices": self.device_query.error,
                "displayDrivers": self.driver_query.error,
                "graphicsEvents": self.graphics_event_query.error,
                "wheaEvents": self.whea_event_query.error,
                "kernelPowerEvents": self.kernel_power_event_query.error,
            },
            "displayDevices": [item.to_dict() for item in self.devices],
            "displayDrivers": [dict(item) for item in self.driver_query.items],
            "graphicsEvents": [item.to_dict() for item in self.graphics_events],
            "wheaEvents": [item.to_dict() for item in self.whea_events],
            "kernelPowerEvents": [item.to_dict() for item in self.kernel_power_events],
            "evidence": [item.to_dict() for item in self.to_evidence()],
        }


class WindowsGraphicsDiagnosticsProvider:
    def __init__(
        self,
        powershell: PowerShellJsonRunner | None = None,
        now: Callable[[], datetime] | None = None,
        recent_days: int = 7,
        max_events: int = 100,
    ) -> None:
        self.powershell = powershell or PowerShellJsonRunner()
        self.now = now or (lambda: datetime.now(timezone.utc))
        self.recent_days = max(1, recent_days)
        self.max_events = max(1, max_events)

    def collect(self) -> WindowsGraphicsDiagnosticsSnapshot:
        queried_at = _utc_iso(self.now())
        device_query = self.powershell.query(
            "display_devices",
            _display_device_cim_script(),
        )
        if device_query.status in {QUERY_FAILED, PERMISSION_REQUIRED, UNSUPPORTED}:
            device_query = self.powershell.query(
                "display_devices_pnp",
                _display_device_script(),
                timeout_seconds=30.0,
            )
        driver_query = self.powershell.query("display_drivers", _display_driver_script())
        graphics_event_query = self.powershell.query(
            "graphics_events",
            _event_script(_graphics_event_filter(), self.recent_days, self.max_events),
        )
        whea_event_query = self.powershell.query(
            "whea_events",
            _event_script(
                "Provider[@Name='Microsoft-Windows-WHEA-Logger'] and (Level=1 or Level=2 or Level=3)",
                self.recent_days,
                self.max_events,
            ),
        )
        kernel_power_event_query = self.powershell.query(
            "kernel_power_events",
            _event_script(
                "Provider[@Name='Microsoft-Windows-Kernel-Power'] and EventID=41",
                self.recent_days,
                self.max_events,
            ),
        )
        devices = normalize_display_devices(
            device_query.items,
            driver_query.items,
            queried_at,
            device_query.status,
        )
        return WindowsGraphicsDiagnosticsSnapshot(
            queried_at=queried_at,
            devices=devices,
            graphics_events=normalize_events(graphics_event_query.items, "gpu", False),
            whea_events=normalize_events(whea_event_query.items, "hardware", False),
            kernel_power_events=normalize_events(kernel_power_event_query.items, "system", True),
            device_query=device_query,
            driver_query=driver_query,
            graphics_event_query=graphics_event_query,
            whea_event_query=whea_event_query,
            kernel_power_event_query=kernel_power_event_query,
        )


class Code43RemoteSupportDemoGraphicsProvider:
    """Provider-boundary fixture for the explicit Code 43 demo scenario."""

    def __init__(self, now: Callable[[], datetime] | None = None) -> None:
        self.now = now or (lambda: datetime.now(timezone.utc))

    def collect(self) -> WindowsGraphicsDiagnosticsSnapshot:
        queried_at = _utc_iso(self.now())
        iris_instance_id = "PCI\\VEN_8086&DEV_A7A0&SUBSYS_C2D4144D&REV_04\\3&11583659&0&10"
        arc_instance_id = "PCI\\VEN_8086&DEV_5694&SUBSYS_C2D4144D&REV_05\\6&7F5B445&0&00080030"
        device_query = PowerShellQueryResult(OK, (
            {
                "deviceName": "Intel(R) Iris(R) Xe Graphics",
                "instanceId": iris_instance_id,
                "pnpStatus": "OK",
                "problemCode": 0,
                "problemCodeQueryStatus": OK,
                "deviceClass": "Display",
                "manufacturer": "Intel Corporation",
                "source": DISPLAY_DEVICE_CIM_SOURCE,
            },
            {
                "deviceName": "Intel(R) Arc(TM) A350M Graphics",
                "instanceId": arc_instance_id,
                "pnpStatus": "Error",
                "problemCode": 43,
                "problemCodeQueryStatus": OK,
                "deviceClass": "Display",
                "manufacturer": "Intel Corporation",
                "source": DISPLAY_DEVICE_CIM_SOURCE,
            },
        ))
        driver_query = PowerShellQueryResult(OK, (
            {
                "deviceName": "Intel(R) Iris(R) Xe Graphics",
                "instanceId": iris_instance_id,
                "deviceClass": "Display",
                "manufacturer": "Intel Corporation",
                "provider": "Intel Corporation",
                "version": "31.0.101.4502",
                "date": "2023-06-15T00:00:00+00:00",
                "signed": True,
                "signer": "Microsoft Windows Hardware Compatibility Publisher",
                "infName": "oem70.inf",
            },
            {
                "deviceName": "Intel(R) Arc(TM) A350M Graphics",
                "instanceId": arc_instance_id,
                "deviceClass": "Display",
                "manufacturer": "Intel Corporation",
                "provider": "Intel Corporation",
                "version": "32.0.101.8826",
                "date": "2026-05-29T00:00:00+00:00",
                "signed": True,
                "signer": "Microsoft Windows Hardware Compatibility Publisher",
                "infName": "oem81.inf",
            },
        ))
        no_events = PowerShellQueryResult(NO_RESULTS)
        devices = normalize_display_devices(device_query.items, driver_query.items, queried_at)
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
            data_mode=DEMO_DATA_MODE,
            scenario_id=GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID,
        )


def normalize_display_devices(
    devices: tuple[dict[str, Any], ...],
    drivers: tuple[dict[str, Any], ...],
    queried_at: str,
    device_query_status: str = OK,
) -> tuple[WindowsDisplayDevice, ...]:
    drivers_by_id = {
        _instance_key(item.get("instanceId")): item
        for item in drivers
        if _instance_key(item.get("instanceId"))
    }
    normalized: list[WindowsDisplayDevice] = []
    matched_driver_ids: set[str] = set()
    for item in devices:
        instance_id = _text(item.get("instanceId")) or ""
        instance_key = _instance_key(instance_id)
        driver = drivers_by_id.get(instance_key, {})
        if driver:
            matched_driver_ids.add(instance_key)
        problem_code = _integer(item.get("problemCode"))
        pnp_status = _text(item.get("pnpStatus"))
        signed = _boolean(driver.get("signed"))
        normalized.append(WindowsDisplayDevice(
            device_name=_text(item.get("deviceName")) or _text(driver.get("deviceName")) or instance_id,
            instance_id=instance_id,
            pnp_status=pnp_status,
            problem_code=problem_code,
            problem_code_query_status=_query_status(item.get("problemCodeQueryStatus")),
            device_class=_text(item.get("deviceClass")) or _text(driver.get("deviceClass")),
            manufacturer=_text(item.get("manufacturer")) or _text(driver.get("manufacturer")),
            driver_provider=_text(driver.get("provider")),
            driver_version=_text(driver.get("version")),
            driver_date=_text(driver.get("date")),
            driver_signed=signed,
            signer=_text(driver.get("signer")),
            inf_name=_text(driver.get("infName")),
            status=normalize_device_status(pnp_status, problem_code, signed),
            queried_at=queried_at,
            device_source=_text(item.get("source")) or DISPLAY_DEVICE_CIM_SOURCE,
        ))
    for driver in drivers:
        instance_id = _text(driver.get("instanceId")) or ""
        instance_key = _instance_key(instance_id)
        if instance_key in matched_driver_ids:
            continue
        fallback_status = (
            device_query_status
            if device_query_status in {QUERY_FAILED, PERMISSION_REQUIRED, UNSUPPORTED}
            else UNKNOWN
        )
        normalized.append(WindowsDisplayDevice(
            device_name=_text(driver.get("deviceName")) or instance_id,
            instance_id=instance_id,
            pnp_status=None,
            problem_code=None,
            problem_code_query_status=fallback_status,
            device_class=_text(driver.get("deviceClass")),
            manufacturer=_text(driver.get("manufacturer")),
            driver_provider=_text(driver.get("provider")),
            driver_version=_text(driver.get("version")),
            driver_date=_text(driver.get("date")),
            driver_signed=_boolean(driver.get("signed")),
            signer=_text(driver.get("signer")),
            inf_name=_text(driver.get("infName")),
            status=fallback_status,
            queried_at=queried_at,
            device_source=DISPLAY_DRIVER_SOURCE,
        ))
    return tuple(normalized)


def normalize_device_status(
    pnp_status: str | None,
    problem_code: int | None,
    driver_signed: bool | None,
) -> str:
    if problem_code == 22:
        return DISABLED
    if problem_code in DRIVER_LOAD_FAILED_CODES:
        return DRIVER_LOAD_FAILED
    if problem_code in DRIVER_BLOCKED_CODES:
        return DRIVER_BLOCKED
    if problem_code in SIGNATURE_PROBLEM_CODES or driver_signed is False:
        return SIGNATURE_PROBLEM
    if problem_code not in {None, 0}:
        return DEVICE_REPORTED_PROBLEM
    normalized_pnp = (pnp_status or "").strip().upper()
    if normalized_pnp == OK:
        return OK
    if normalized_pnp == "UNKNOWN" or not normalized_pnp:
        return UNKNOWN
    if normalized_pnp == "DEGRADED":
        return DEGRADED
    return DEGRADED


def normalize_events(
    items: tuple[dict[str, Any], ...],
    component: str,
    supporting_only: bool,
) -> tuple[WindowsGraphicsEvent, ...]:
    events: list[WindowsGraphicsEvent] = []
    for item in items:
        provider = _text(item.get("provider")) or ""
        event_id = _integer(item.get("eventId"))
        occurred_at = _text(item.get("occurredAt")) or ""
        if not provider or event_id is None or not occurred_at:
            continue
        events.append(WindowsGraphicsEvent(
            provider=provider,
            event_id=event_id,
            level=_integer(item.get("level")),
            occurred_at=occurred_at,
            component=component,
            code=f"{provider}:{event_id}",
            status="SUPPORTING_EVIDENCE" if supporting_only else "RECORDED",
            description=_text(item.get("description")) or "",
        ))
    return tuple(events)


def _query_state_evidence(
    task_id: str,
    component: str,
    category: str,
    source: str,
    query: PowerShellQueryResult,
    queried_at: str,
) -> tuple[DiagnosisEvidence, ...]:
    if query.status == OK:
        return ()
    availability = _availability_for_query(query.status)
    return (DiagnosisEvidence(
        task_id=task_id,
        component=component,
        metric_type="query_status",
        value=0 if query.status == NO_RESULTS else None,
        unit="count" if query.status == NO_RESULTS else "",
        availability=availability,
        status=query.status,
        source=source,
        sampled_at=queried_at,
        error_code=query.status if availability != AVAILABLE else None,
        failure_reason=query.error if availability != AVAILABLE else None,
        category=category,
        code=query.status,
        occurred_at=queried_at,
        description=query.error or "Query completed with no matching records",
    ),)


def _event_evidence(
    task_id: str,
    category: str,
    events: tuple[WindowsGraphicsEvent, ...],
    query: PowerShellQueryResult,
    queried_at: str,
) -> tuple[DiagnosisEvidence, ...]:
    if not events:
        component = "gpu" if category == "DRIVER" else "hardware" if category == "HARDWARE" else "system"
        return _query_state_evidence(task_id, component, category, WINDOWS_EVENT_SOURCE, query, queried_at)
    return tuple(DiagnosisEvidence(
        task_id=task_id,
        component=event.component,
        metric_type="windows_event",
        value=event.to_dict(),
        unit="",
        availability=AVAILABLE,
        status=event.status,
        source=WINDOWS_EVENT_SOURCE,
        sampled_at=event.occurred_at,
        category=category,
        code=event.code,
        occurred_at=event.occurred_at,
        description=event.description,
    ) for event in events)


def _availability_for_query(status: str) -> str:
    if status == PERMISSION_REQUIRED:
        return PERMISSION_REQUIRED
    if status == UNSUPPORTED:
        return UNSUPPORTED
    if status == QUERY_FAILED:
        return FAILED
    return AVAILABLE


def _default_hidden_subprocess_kwargs() -> dict[str, Any]:
    if os.name != "nt":
        return {}
    flag = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    return {"creationflags": flag} if flag else {}


def _wrap_query(body: str) -> str:
    return (
        "$ErrorActionPreference = 'Stop'\n"
        "try {\n"
        "  $items = @(\n"
        + body
        + "\n  )\n"
        "  [pscustomobject]@{ queryStatus = 'OK'; items = @($items); error = $null } | "
        "ConvertTo-Json -Depth 8 -Compress\n"
        "} catch {\n"
        "  $errorId = [string]$_.FullyQualifiedErrorId\n"
        "  $errorCategory = [string]$_.CategoryInfo.Category\n"
        "  $errorException = $_.Exception\n"
        "  $unauthorized = $false\n"
        "  while ($null -ne $errorException) {\n"
        "    if ($errorException -is [System.UnauthorizedAccessException]) { $unauthorized = $true; break }\n"
        "    $errorException = $errorException.InnerException\n"
        "  }\n"
        "  $status = 'QUERY_FAILED'\n"
        "  if ($unauthorized -or $errorCategory -eq 'PermissionDenied' -or "
        "$errorId -match 'UnauthorizedAccess|AccessDenied') { $status = 'PERMISSION_REQUIRED' }\n"
        "  elseif ($errorId -match '^NoMatchingEventsFound') { $status = 'NO_RESULTS' }\n"
        "  [pscustomobject]@{ queryStatus = $status; items = @(); error = [string]$_.Exception.Message } | "
        "ConvertTo-Json -Depth 8 -Compress\n"
        "}\n"
    )


def _display_device_script() -> str:
    return _wrap_query(r"""
    Get-PnpDevice -Class Display -ErrorAction Stop | ForEach-Object {
      $device = $_
      $problemCode = $null
      $problemCodeQueryStatus = 'OK'
      try {
        $problemProperty = Get-PnpDeviceProperty -InstanceId $device.InstanceId -KeyName 'DEVPKEY_Device_ProblemCode' -ErrorAction Stop
        if ($null -ne $problemProperty.Data) { $problemCode = [int]$problemProperty.Data }
      } catch {
        $propertyErrorId = [string]$_.FullyQualifiedErrorId
        if ($_.Exception -is [System.UnauthorizedAccessException] -or $propertyErrorId -match 'UnauthorizedAccess|AccessDenied') {
          $problemCodeQueryStatus = 'PERMISSION_REQUIRED'
        } elseif ($propertyErrorId -match 'ObjectNotFound|PropertyNotFound') {
          $problemCodeQueryStatus = 'UNSUPPORTED'
        } else {
          $problemCodeQueryStatus = 'QUERY_FAILED'
        }
      }
      $manufacturer = $null
      try {
        $manufacturer = (Get-PnpDeviceProperty -InstanceId $device.InstanceId -KeyName 'DEVPKEY_Device_Manufacturer' -ErrorAction Stop).Data
      } catch {}
      [pscustomobject]@{
        deviceName = [string]$device.FriendlyName
        instanceId = [string]$device.InstanceId
        pnpStatus = [string]$device.Status
        problemCode = $problemCode
        problemCodeQueryStatus = $problemCodeQueryStatus
        deviceClass = [string]$device.Class
        manufacturer = if ($null -ne $manufacturer) { [string]$manufacturer } else { $null }
        source = 'Get-PnpDevice/Get-PnpDeviceProperty'
      }
    }
""")


def _display_device_cim_script() -> str:
    return _wrap_query(r"""
    Get-CimInstance -ClassName Win32_PnPEntity -Filter "PNPClass='Display'" -ErrorAction Stop | ForEach-Object {
      $device = $_
      [pscustomobject]@{
        deviceName = [string]$device.Name
        instanceId = [string]$device.PNPDeviceID
        pnpStatus = if ($null -eq $device.Status) { $null } else { [string]$device.Status }
        problemCode = if ($null -eq $device.ConfigManagerErrorCode) { $null } else { [int]$device.ConfigManagerErrorCode }
        problemCodeQueryStatus = 'OK'
        deviceClass = if ($null -eq $device.PNPClass) { $null } else { [string]$device.PNPClass }
        manufacturer = if ($null -eq $device.Manufacturer) { $null } else { [string]$device.Manufacturer }
        source = 'Win32_PnPEntity'
      }
    }
""")


def _display_driver_script() -> str:
    return _wrap_query(r"""
    Get-CimInstance -ClassName Win32_PnPSignedDriver -Filter "DeviceClass='DISPLAY'" -ErrorAction Stop | ForEach-Object {
      $driver = $_
      $driverDate = $null
      if ($driver.DriverDate -is [datetime]) {
        $driverDate = $driver.DriverDate.ToUniversalTime().ToString('o')
      } elseif ($null -ne $driver.DriverDate) {
        $driverDate = [string]$driver.DriverDate
      }
      [pscustomobject]@{
        deviceName = [string]$driver.DeviceName
        instanceId = [string]$driver.DeviceID
        deviceClass = [string]$driver.DeviceClass
        manufacturer = [string]$driver.Manufacturer
        provider = [string]$driver.DriverProviderName
        version = [string]$driver.DriverVersion
        date = $driverDate
        signed = if ($null -eq $driver.IsSigned) { $null } else { [bool]$driver.IsSigned }
        signer = if ($null -eq $driver.Signer) { $null } else { [string]$driver.Signer }
        infName = if ($null -eq $driver.InfName) { $null } else { [string]$driver.InfName }
      }
    }
""")


def _graphics_event_filter() -> str:
    providers = (
        "Display",
        "Microsoft-Windows-DxgKrnl",
        "nvlddmkm",
        "amdkmdag",
        "amdwddmg",
        "igfx",
        "Intel-GFX-Info",
    )
    provider_filter = " or ".join(f"Provider[@Name='{provider}']" for provider in providers)
    return f"({provider_filter}) and (Level=1 or Level=2 or Level=3)"


def _event_script(system_filter: str, recent_days: int, max_events: int) -> str:
    max_age_ms = max(1, recent_days) * 24 * 60 * 60 * 1000
    body = r"""
    $xpath = "*[System[__SYSTEM_FILTER__ and TimeCreated[timediff(@SystemTime) <= __MAX_AGE__]]]"
    Get-WinEvent -LogName 'System' -FilterXPath $xpath -MaxEvents __MAX_EVENTS__ -ErrorAction Stop | ForEach-Object {
      $event = $_
      [pscustomobject]@{
        provider = [string]$event.ProviderName
        eventId = [int]$event.Id
        level = if ($null -eq $event.Level) { $null } else { [int]$event.Level }
        occurredAt = $event.TimeCreated.ToUniversalTime().ToString('o')
        description = if ($null -eq $event.Message) { '' } else { [string]$event.Message }
      }
    }
"""
    body = body.replace("__SYSTEM_FILTER__", system_filter)
    body = body.replace("__MAX_AGE__", str(max_age_ms))
    body = body.replace("__MAX_EVENTS__", str(max(1, max_events)))
    return _wrap_query(body)


def _query_status(value: Any) -> str:
    status = (_text(value) or UNKNOWN).upper()
    return status if status in QUERY_STATUSES else UNKNOWN


def _instance_key(value: Any) -> str:
    return (_text(value) or "").casefold()


def _text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _integer(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _boolean(value: Any) -> bool | None:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        normalized = value.strip().casefold()
        if normalized in {"true", "1"}:
            return True
        if normalized in {"false", "0"}:
            return False
    return None


def _utc_iso(value: datetime) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat()


if __name__ == "__main__":
    snapshot = WindowsGraphicsDiagnosticsProvider().collect()
    print(json.dumps(snapshot.to_dict(), ensure_ascii=False, indent=2))
