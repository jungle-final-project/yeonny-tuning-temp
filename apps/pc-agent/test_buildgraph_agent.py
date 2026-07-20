from __future__ import annotations

import gzip
import inspect
import json
import os
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import buildgraph_agent as agent
from diagnosis_request_agent import DiagnosisRequest, DiagnosisSession
from initial_metrics import ProviderSample


def missing_gpu_counter() -> tuple[None, str]:
    return None, "GPU counter unavailable in test"


class StaticMetricCollector:
    def __init__(self, event_type: str = "SYSTEM_METRIC", message: str = "System metrics collected.") -> None:
        self.event_type = event_type
        self.message = message

    def collect(self, ts: datetime, index: int) -> dict:
        payload = {
            "cpuUsage": 12.0,
            "cpuUsagePercent": 12.0,
            "memoryUsage": 34.0,
            "ramUsage": 34.0,
            "memoryUsedPercent": 34.0,
            "diskUsage": 56.0,
            "diskUsedPercent": 56.0,
            "diskBusyEstimatePercent": 7.0,
            "gpuUsage": None,
            "gpuUsagePercent": None,
            "vramUsage": None,
            "vramUsagePercent": None,
            "gpuTemp": None,
            "gpuTempCelsius": None,
            "cpuTemp": None,
            "cpuTempCelsius": None,
            "cpuTemperatureCelsius": None,
            "eventType": self.event_type,
            "message": self.message,
            "osErrorEvent": None,
            "topCpuProcess": "python.exe",
            "topRamProcess": "python.exe",
            "unavailableReason": {"gpuUsage": "nvidia-smi unavailable"},
        }
        return agent.build_metric_snapshot(ts, index, self.event_type, payload)


class AgentGoal1112Test(unittest.TestCase):
    def test_append_metric_writes_required_system_jsonl_fields(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="token",
                log_dir=Path(directory),
                agent_version="test-agent",
                policy_version="test-policy",
            )

            log_path = agent.append_metric(config, collector=StaticMetricCollector())

            row = json.loads(log_path.read_text(encoding="utf-8").strip())
            self.assertIn("timestamp", row)
            self.assertIn("cpuUsage", row)
            self.assertIn("memoryUsage", row)
            self.assertIn("eventType", row)
            self.assertIn("message", row)
            self.assertEqual(row["schemaVersion"], 1)
            self.assertIn("collectedAt", row)
            self.assertEqual(row["agentId"], "fingerprint")
            self.assertEqual(row["sequence"], 0)
            self.assertEqual(row["kind"], "SYSTEM_METRIC")
            self.assertEqual(row["kind"], row["eventType"])
            self.assertEqual(row["payload"]["eventType"], row["eventType"])
            self.assertEqual(row["privacyFlags"], {"containsRawPath": False, "masked": True})

    def test_sample_metric_log_row_keeps_demo_issue_event_for_samples(self) -> None:
        row = agent.sample_metric_log_row(
            datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST),
            7,
            agent.DEFAULT_SCHEMA_VERSION,
            "sample-agent",
        )

        self.assertEqual(row["agentId"], "sample-agent")
        self.assertEqual(row["eventType"], "DISPLAY_DRIVER_WARNING")
        self.assertEqual(row["message"], "Display driver warning observed.")
        self.assertEqual(row["payload"]["metricKind"], "sample-demo")

    def test_hidden_subprocess_kwargs_hides_windows_console(self) -> None:
        with patch("buildgraph_agent.os.name", "nt"), \
            patch.object(agent.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True):
            kwargs = agent.hidden_subprocess_kwargs()

        self.assertEqual(kwargs, {"creationflags": 0x08000000})

    def test_windows_counter_powershell_runs_without_console_window(self) -> None:
        run = MagicMock(return_value=SimpleNamespace(returncode=0, stdout="12.5\n"))
        with patch("buildgraph_agent.os.name", "nt"), \
            patch.object(agent.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True), \
            patch("buildgraph_agent.subprocess.run", run):
            value, reason = agent.read_windows_disk_busy_percent_powershell(runner=agent.subprocess.run)

        self.assertEqual(value, 12.5)
        self.assertIsNone(reason)
        self.assertEqual(run.call_args.kwargs["creationflags"], 0x08000000)

    def test_nvidia_smi_runs_without_console_window(self) -> None:
        run = MagicMock(return_value=SimpleNamespace(returncode=1, stdout=""))
        with patch("buildgraph_agent.os.name", "nt"), \
            patch.object(agent.subprocess, "CREATE_NO_WINDOW", 0x08000000, create=True), \
            patch("buildgraph_agent.subprocess.run", run):
            agent.HardwareMetricCollector().run_nvidia_smi()

        self.assertEqual(run.call_args.kwargs["creationflags"], 0x08000000)

    def test_metric_collector_marks_unavailable_values_without_fake_gpu_or_temperature(self) -> None:
        class FakePsutil:
            def cpu_percent(self, interval: float = 0.0) -> float:
                return 11.2

            def virtual_memory(self) -> SimpleNamespace:
                return SimpleNamespace(percent=22.3)

            def disk_usage(self, path: str) -> SimpleNamespace:
                return SimpleNamespace(percent=33.4)

            def disk_io_counters(self) -> SimpleNamespace:
                return SimpleNamespace(read_bytes=1000, write_bytes=500, read_count=10, write_count=5, busy_time=100)

            def sensors_temperatures(self, fahrenheit: bool = False) -> dict:
                return {}

            def process_iter(self, attrs: list[str]) -> list:
                return []

        def missing_nvidia_smi() -> None:
            raise FileNotFoundError("nvidia-smi")

        collector = agent.HardwareMetricCollector(
            psutil_module=FakePsutil(),
            nvidia_smi_runner=missing_nvidia_smi,
            gpu_counter_reader=missing_gpu_counter,
            powershell_gpu_counter_reader=missing_gpu_counter,
            time_fn=lambda: 100.0,
        )

        row = collector.collect(datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST), 0)

        self.assertEqual(row["eventType"], "SYSTEM_METRIC")
        self.assertEqual(row["metricKind"], "system")
        self.assertEqual(row["cpuUsage"], 11.2)
        self.assertEqual(row["memoryUsage"], 22.3)
        self.assertIsNone(row["gpuUsage"])
        self.assertIsNone(row["vramUsage"])
        self.assertIsNone(row["gpuTemp"])
        self.assertIsNone(row["cpuTemp"])
        self.assertIsNone(row["diskReadBytesPerSec"])
        self.assertIn("gpuUsage", row["unavailableReason"])
        self.assertIn("cpuTemp", row["unavailableReason"])
        self.assertIn("diskReadBytesPerSec", row["unavailableReason"])
        self.assertEqual(row["diskCollectorSource"], "unavailable")
        self.assertEqual(row["sensorStatus"]["vramUsagePercent"], "unsupported")
        self.assertEqual(row["sensorStatus"]["cpuTempCelsius"], "unsupported")
        self.assertEqual(row["sensorStatus"]["gpuTempCelsius"], "unsupported")

    def test_metric_collector_uses_disk_io_delta(self) -> None:
        class FakePsutil:
            def __init__(self) -> None:
                self.index = 0
                self.counters = [
                    SimpleNamespace(read_bytes=1000, write_bytes=500, read_count=10, write_count=5, busy_time=1000),
                    SimpleNamespace(read_bytes=1500, write_bytes=750, read_count=20, write_count=10, busy_time=2000),
                ]

            def cpu_percent(self, interval: float = 0.0) -> float:
                return 10.0

            def virtual_memory(self) -> SimpleNamespace:
                return SimpleNamespace(percent=20.0)

            def disk_usage(self, path: str) -> SimpleNamespace:
                return SimpleNamespace(percent=30.0)

            def disk_io_counters(self) -> SimpleNamespace:
                value = self.counters[min(self.index, len(self.counters) - 1)]
                self.index += 1
                return value

            def sensors_temperatures(self, fahrenheit: bool = False) -> dict:
                return {}

            def process_iter(self, attrs: list[str]) -> list:
                return []

        times = iter([100.0, 105.0])
        collector = agent.HardwareMetricCollector(
            psutil_module=FakePsutil(),
            nvidia_smi_runner=lambda: SimpleNamespace(returncode=1, stdout=""),
            gpu_counter_reader=missing_gpu_counter,
            powershell_gpu_counter_reader=missing_gpu_counter,
            time_fn=lambda: next(times),
        )

        collector.collect(datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST), 0)
        row = collector.collect(datetime(2026, 7, 2, 14, 0, 5, tzinfo=agent.KST), 1)

        self.assertEqual(row["diskReadBytesPerSec"], 100.0)
        self.assertEqual(row["diskWriteBytesPerSec"], 50.0)
        self.assertEqual(row["diskReadCountPerSec"], 2.0)
        self.assertEqual(row["diskWriteCountPerSec"], 1.0)
        self.assertEqual(row["diskBusyEstimatePercent"], 20.0)
        self.assertEqual(row["diskCollectorSource"], "psutil-busy-time")

    def test_metric_collector_uses_read_write_time_when_busy_time_is_missing(self) -> None:
        class FakePsutil:
            def __init__(self) -> None:
                self.index = 0
                self.counters = [
                    SimpleNamespace(
                        read_bytes=1000,
                        write_bytes=500,
                        read_count=10,
                        write_count=5,
                        read_time=1000,
                        write_time=500,
                    ),
                    SimpleNamespace(
                        read_bytes=1500,
                        write_bytes=750,
                        read_count=20,
                        write_count=10,
                        read_time=1600,
                        write_time=900,
                    ),
                ]

            def cpu_percent(self, interval: float = 0.0) -> float:
                return 10.0

            def virtual_memory(self) -> SimpleNamespace:
                return SimpleNamespace(percent=20.0)

            def disk_usage(self, path: str) -> SimpleNamespace:
                return SimpleNamespace(percent=30.0)

            def disk_io_counters(self) -> SimpleNamespace:
                value = self.counters[min(self.index, len(self.counters) - 1)]
                self.index += 1
                return value

            def sensors_temperatures(self, fahrenheit: bool = False) -> dict:
                return {}

            def process_iter(self, attrs: list[str]) -> list:
                return []

        times = iter([100.0, 105.0])
        collector = agent.HardwareMetricCollector(
            psutil_module=FakePsutil(),
            nvidia_smi_runner=lambda: SimpleNamespace(returncode=1, stdout=""),
            gpu_counter_reader=missing_gpu_counter,
            powershell_gpu_counter_reader=missing_gpu_counter,
            time_fn=lambda: next(times),
        )

        collector.collect(datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST), 0)
        row = collector.collect(datetime(2026, 7, 2, 14, 0, 5, tzinfo=agent.KST), 1)

        self.assertEqual(row["diskBusyEstimatePercent"], 20.0)
        self.assertEqual(row["diskCollectorSource"], "psutil-read-write-time")

    def test_metric_collector_uses_windows_disk_counter_when_psutil_time_delta_is_zero(self) -> None:
        class FakePsutil:
            def __init__(self) -> None:
                self.index = 0
                self.counters = [
                    SimpleNamespace(
                        read_bytes=1000,
                        write_bytes=500,
                        read_count=10,
                        write_count=5,
                        read_time=0,
                        write_time=0,
                    ),
                    SimpleNamespace(
                        read_bytes=2500,
                        write_bytes=1500,
                        read_count=40,
                        write_count=25,
                        read_time=0,
                        write_time=0,
                    ),
                ]

            def cpu_percent(self, interval: float = 0.0) -> float:
                return 10.0

            def virtual_memory(self) -> SimpleNamespace:
                return SimpleNamespace(percent=20.0)

            def disk_usage(self, path: str) -> SimpleNamespace:
                return SimpleNamespace(percent=30.0)

            def disk_io_counters(self) -> SimpleNamespace:
                value = self.counters[min(self.index, len(self.counters) - 1)]
                self.index += 1
                return value

            def sensors_temperatures(self, fahrenheit: bool = False) -> dict:
                return {}

            def process_iter(self, attrs: list[str]) -> list:
                return []

        fallback_calls = 0

        def disk_fallback() -> tuple[float, None]:
            nonlocal fallback_calls
            fallback_calls += 1
            return 42.3, None

        times = iter([100.0, 105.0])
        collector = agent.HardwareMetricCollector(
            psutil_module=FakePsutil(),
            nvidia_smi_runner=lambda: SimpleNamespace(returncode=1, stdout=""),
            gpu_counter_reader=missing_gpu_counter,
            powershell_gpu_counter_reader=missing_gpu_counter,
            disk_busy_reader=disk_fallback,
            time_fn=lambda: next(times),
        )

        collector.collect(datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST), 0)
        row = collector.collect(datetime(2026, 7, 2, 14, 0, 5, tzinfo=agent.KST), 1)

        self.assertEqual(row["diskReadBytesPerSec"], 300.0)
        self.assertEqual(row["diskWriteBytesPerSec"], 200.0)
        self.assertEqual(row["diskBusyEstimatePercent"], 42.3)
        self.assertEqual(row["diskCollectorSource"], "windows-performance-counter")
        self.assertEqual(row["metricKind"], "system")
        self.assertEqual(fallback_calls, 1)

    def test_metric_collector_marks_disk_busy_unavailable_when_windows_disk_counter_fails(self) -> None:
        class FakePsutil:
            def __init__(self) -> None:
                self.index = 0
                self.counters = [
                    SimpleNamespace(read_bytes=1000, write_bytes=500, read_count=10, write_count=5, read_time=0, write_time=0),
                    SimpleNamespace(read_bytes=1500, write_bytes=750, read_count=20, write_count=10, read_time=0, write_time=0),
                ]

            def cpu_percent(self, interval: float = 0.0) -> float:
                return 10.0

            def virtual_memory(self) -> SimpleNamespace:
                return SimpleNamespace(percent=20.0)

            def disk_usage(self, path: str) -> SimpleNamespace:
                return SimpleNamespace(percent=30.0)

            def disk_io_counters(self) -> SimpleNamespace:
                value = self.counters[min(self.index, len(self.counters) - 1)]
                self.index += 1
                return value

            def sensors_temperatures(self, fahrenheit: bool = False) -> dict:
                return {}

            def process_iter(self, attrs: list[str]) -> list:
                return []

        times = iter([100.0, 105.0])
        collector = agent.HardwareMetricCollector(
            psutil_module=FakePsutil(),
            nvidia_smi_runner=lambda: SimpleNamespace(returncode=1, stdout=""),
            gpu_counter_reader=missing_gpu_counter,
            powershell_gpu_counter_reader=missing_gpu_counter,
            disk_busy_reader=lambda: (None, "Windows disk counter unavailable"),
            time_fn=lambda: next(times),
        )

        collector.collect(datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST), 0)
        row = collector.collect(datetime(2026, 7, 2, 14, 0, 5, tzinfo=agent.KST), 1)

        self.assertIsNone(row["diskBusyEstimatePercent"])
        self.assertEqual(row["diskCollectorSource"], "unavailable")
        self.assertIn("diskBusyEstimatePercent", row["unavailableReason"])

    def test_read_windows_gpu_usage_percent_win32pdh_sums_counter_values(self) -> None:
        class FakeWin32Pdh:
            PDH_FMT_DOUBLE = 1
            PERF_DETAIL_WIZARD = 400

            def __init__(self) -> None:
                self.paths: list[str] = []
                self.closed = False

            def EnumObjectItems(
                self,
                data_source: object,
                machine: object,
                object_name: str,
                detail_level: int,
            ) -> tuple[list[str], list[str]]:
                return ["Utilization Percentage", "Running Time"], ["engine-3d", "engine-copy"]

            def OpenQuery(self) -> str:
                return "query"

            def MakeCounterPath(self, parts: tuple[object, str, str, object, int, str]) -> str:
                return f"{parts[1]}:{parts[2]}:{parts[5]}"

            def AddCounter(self, query: str, path: str) -> str:
                self.paths.append(path)
                return path

            def CollectQueryData(self, query: str) -> None:
                return None

            def GetFormattedCounterValue(self, handle: str, fmt: int) -> tuple[int, float]:
                return 0, {
                    "GPU Engine:engine-3d:Utilization Percentage": 12.5,
                    "GPU Engine:engine-copy:Utilization Percentage": 33.0,
                }[handle]

            def CloseQuery(self, query: str) -> None:
                self.closed = True

        fake = FakeWin32Pdh()

        value, reason = agent.read_windows_gpu_usage_percent_win32pdh(fake)

        self.assertEqual(value, 45.5)
        self.assertIsNone(reason)
        self.assertEqual(fake.paths, [
            "GPU Engine:engine-3d:Utilization Percentage",
            "GPU Engine:engine-copy:Utilization Percentage",
        ])
        self.assertTrue(fake.closed)

    def test_metric_collector_reads_nvidia_smi_values(self) -> None:
        class FakePsutil:
            def cpu_percent(self, interval: float = 0.0) -> float:
                return 10.0

            def virtual_memory(self) -> SimpleNamespace:
                return SimpleNamespace(percent=20.0, used=8 * 1024**3, total=16 * 1024**3)

            def disk_usage(self, path: str) -> SimpleNamespace:
                return SimpleNamespace(percent=30.0)

            def disk_io_counters(self) -> SimpleNamespace:
                return SimpleNamespace(read_bytes=1000, write_bytes=500, read_count=10, write_count=5, busy_time=1000)

            def sensors_temperatures(self, fahrenheit: bool = False) -> dict:
                return {}

            def process_iter(self, attrs: list[str]) -> list:
                return []

        collector = agent.HardwareMetricCollector(
            psutil_module=FakePsutil(),
            nvidia_smi_runner=lambda: SimpleNamespace(returncode=0, stdout="42, 2048, 8192, 66, 37\n"),
            time_fn=lambda: 100.0,
        )

        row = collector.collect(datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST), 0)

        self.assertEqual(row["gpuUsage"], 42.0)
        self.assertEqual(row["gpuUsagePercent"], 42.0)
        self.assertEqual(row["vramUsage"], 25.0)
        self.assertEqual(row["vramUsagePercent"], 25.0)
        self.assertEqual(row["gpuTemp"], 66.0)
        self.assertEqual(row["gpuTempCelsius"], 66.0)
        self.assertEqual(row["gpuFanPercent"], 37.0)
        self.assertEqual(row["memoryUsedBytes"], 8 * 1024**3)
        self.assertEqual(row["memoryTotalBytes"], 16 * 1024**3)
        self.assertEqual(row["gpuCollectorSource"], "nvidia-smi")
        self.assertEqual(row["sensorStatus"]["vramUsagePercent"], "collected")
        self.assertEqual(row["sensorStatus"]["gpuTempCelsius"], "collected")
        self.assertEqual(row["sensorStatus"]["cpuTempCelsius"], "unsupported")
        self.assertEqual(agent.display_log_table_values(row)[6], "25.0%")
        self.assertEqual(agent.display_log_table_values(row)[8], "66.0C")

    def test_metric_collector_reads_cpu_clock_and_disk_capacity(self) -> None:
        class FakePsutil:
            disk_paths: list[str] = []

            def cpu_percent(self, interval: float = 0.0) -> float:
                return 24.0

            def cpu_freq(self) -> SimpleNamespace:
                return SimpleNamespace(current=4250.0)

            def virtual_memory(self) -> SimpleNamespace:
                return SimpleNamespace(percent=41.0, used=8 * 1024**3, total=16 * 1024**3)

            def disk_usage(self, path: str) -> SimpleNamespace:
                self.disk_paths.append(path)
                return SimpleNamespace(
                    percent=3.0,
                    used=30 * 1024**3,
                    free=480 * 1024**3,
                    total=1000 * 1024**3,
                )

            def disk_io_counters(self) -> SimpleNamespace:
                return SimpleNamespace(read_bytes=1000, write_bytes=500, read_count=10, write_count=5, busy_time=100)

            def sensors_temperatures(self, fahrenheit: bool = False) -> dict:
                return {}

            def process_iter(self, attrs: list[str]) -> list:
                return []

        collector = agent.HardwareMetricCollector(
            psutil_module=FakePsutil(),
            nvidia_smi_runner=lambda: SimpleNamespace(returncode=1, stdout="", stderr="not supported"),
            gpu_counter_reader=missing_gpu_counter,
            powershell_gpu_counter_reader=missing_gpu_counter,
            disk_busy_reader=lambda: (None, "counter unavailable"),
            time_fn=lambda: 1.0,
        )

        with patch("buildgraph_agent.os.name", "nt"), patch.dict(
            agent.os.environ,
            {"SystemDrive": "D:"},
        ):
            row = collector.collect(datetime.now(agent.KST), 0)

        self.assertEqual(4250.0, row["cpuClockMhz"])
        self.assertEqual(52.0, row["diskUsage"])
        self.assertEqual(52.0, row["diskUsedPercent"])
        self.assertEqual(520 * 1024**3, row["diskUsedBytes"])
        self.assertEqual(1000 * 1024**3, row["diskTotalBytes"])
        self.assertEqual(["D:\\"], FakePsutil.disk_paths)

    def test_symptom_screen_marks_usage_at_80_percent_as_warning(self) -> None:
        payload = {
            "cpuUsagePercent": 80.0,
            "cpuTempCelsius": 60.0,
            "gpuUsagePercent": 30.0,
            "gpuTempCelsius": 55.0,
            "gpuFanPercent": 25.0,
            "memoryUsedPercent": 40.0,
            "memoryUsedBytes": 6 * 1024**3,
            "memoryTotalBytes": 16 * 1024**3,
            "diskBusyEstimatePercent": 20.0,
            "diskUsedPercent": 45.0,
            "diskSmartStatus": "정상",
        }
        row = agent.build_metric_snapshot(datetime.now(agent.KST), 0, agent.SYSTEM_METRIC_KIND, payload)
        snapshot = agent.hardware_sensor_snapshot(row, [row])
        screen = agent.build_symptom_screen_state(agent.SymptomScreenInput(snapshot, "CPU 사용률이 높습니다.", None))

        self.assertEqual(screen.widgets[0].status, "주의")
        self.assertEqual(screen.widgets[0].tone, "warning")
        self.assertTrue(all(widget.tone == "default" for widget in screen.widgets[1:]))

        critical_payload = dict(payload)
        critical_payload["cpuUsagePercent"] = 95.0
        critical_row = agent.build_metric_snapshot(
            datetime.now(agent.KST), 1, agent.SYSTEM_METRIC_KIND, critical_payload
        )
        critical_screen = agent.build_symptom_screen_state(agent.SymptomScreenInput(
            agent.hardware_sensor_snapshot(critical_row, [critical_row]), "CPU 상태 확인", None
        ))
        self.assertEqual("danger", critical_screen.widgets[0].tone)

    def test_disk_card_uses_activity_for_wave_and_labels_storage_separately(self) -> None:
        first_payload = {
            "cpuUsagePercent": 20.0,
            "gpuUsagePercent": 30.0,
            "memoryUsedPercent": 40.0,
            "diskBusyEstimatePercent": 12.0,
            "diskUsedPercent": 91.6,
            "diskSmartStatus": "정상",
        }
        second_payload = dict(first_payload, diskBusyEstimatePercent=18.0)
        first = agent.build_metric_snapshot(datetime.now(agent.KST), 0, agent.SYSTEM_METRIC_KIND, first_payload)
        second = agent.build_metric_snapshot(datetime.now(agent.KST), 1, agent.SYSTEM_METRIC_KIND, second_payload)

        screen = agent.build_symptom_screen_state(agent.SymptomScreenInput(
            agent.hardware_sensor_snapshot(second, [first, second]), "디스크 상태 확인", None
        ))
        disk = screen.widgets[3]

        self.assertEqual("저장 공간 91.6%", disk.primary)
        self.assertEqual("활성 시간 18%", disk.details[1])
        self.assertEqual((12.0, 18.0), disk.history)
        self.assertEqual("주의", disk.status)
        self.assertEqual("warning", disk.tone)

    def test_disk_card_uses_high_activity_only_for_detail_and_wave(self) -> None:
        payload = {
            "cpuUsagePercent": 20.0,
            "gpuUsagePercent": 30.0,
            "memoryUsedPercent": 40.0,
            "diskBusyEstimatePercent": 92.0,
            "diskUsedPercent": 35.0,
            "diskSmartStatus": "정상",
        }
        row = agent.build_metric_snapshot(datetime.now(agent.KST), 0, agent.SYSTEM_METRIC_KIND, payload)
        disk = agent.build_symptom_screen_state(agent.SymptomScreenInput(
            agent.hardware_sensor_snapshot(row, [row]), "디스크 상태 확인", None
        )).widgets[3]

        self.assertEqual("저장 공간 35%", disk.primary)
        self.assertEqual("활성 시간 92%", disk.details[1])
        self.assertEqual((92.0,), disk.history)
        self.assertEqual("정상", disk.status)
        self.assertEqual("default", disk.tone)

    def test_disk_card_keeps_low_activity_wave_when_storage_is_high(self) -> None:
        payload = {
            "cpuUsagePercent": 20.0,
            "gpuUsagePercent": 30.0,
            "memoryUsedPercent": 40.0,
            "diskBusyEstimatePercent": 0.1,
            "diskUsedPercent": 92.0,
            "diskSmartStatus": "정상",
        }
        row = agent.build_metric_snapshot(datetime.now(agent.KST), 0, agent.SYSTEM_METRIC_KIND, payload)
        disk = agent.build_symptom_screen_state(agent.SymptomScreenInput(
            agent.hardware_sensor_snapshot(row, [row]), "디스크 상태 확인", None
        )).widgets[3]

        self.assertEqual("저장 공간 92%", disk.primary)
        self.assertEqual("활성 시간 0.1%", disk.details[1])
        self.assertEqual((0.1,), disk.history)
        self.assertEqual("주의", disk.status)
        self.assertEqual("warning", disk.tone)
        self.assertLess(agent.usage_wave_target_amplitude(disk.history[-1]), 1.0)

    def test_disk_card_distinguishes_zero_activity_from_missing_activity(self) -> None:
        zero_payload = {
            "cpuUsagePercent": 20.0,
            "gpuUsagePercent": 30.0,
            "memoryUsedPercent": 40.0,
            "diskBusyEstimatePercent": 0.0,
            "diskUsedPercent": 35.0,
            "diskSmartStatus": "정상",
        }
        zero_row = agent.build_metric_snapshot(datetime.now(agent.KST), 0, agent.SYSTEM_METRIC_KIND, zero_payload)
        zero_disk = agent.build_symptom_screen_state(agent.SymptomScreenInput(
            agent.hardware_sensor_snapshot(zero_row, [zero_row]), "디스크 상태 확인", None
        )).widgets[3]

        self.assertEqual("저장 공간 35%", zero_disk.primary)
        self.assertEqual("활성 시간 0%", zero_disk.details[1])
        self.assertEqual((0.0,), zero_disk.history)
        self.assertGreater(agent.usage_wave_target_amplitude(0.0), 0.0)
        self.assertLess(agent.usage_wave_target_amplitude(0.0), 1.0)

        failed_payload = dict(zero_payload)
        failed_payload.pop("diskBusyEstimatePercent")
        failed_payload["unavailableReason"] = {"diskBusyEstimatePercent": "disk activity query failed"}
        failed_row = agent.build_metric_snapshot(datetime.now(agent.KST), 1, agent.SYSTEM_METRIC_KIND, failed_payload)
        failed_disk = agent.build_symptom_screen_state(agent.SymptomScreenInput(
            agent.hardware_sensor_snapshot(failed_row, [failed_row]), "디스크 상태 확인", None
        )).widgets[3]
        self.assertEqual("활성 시간 확인 실패", failed_disk.details[1])
        self.assertEqual((), failed_disk.history)
        self.assertEqual("정상", failed_disk.status)

    def test_disk_card_never_uses_storage_capacity_as_wave_input(self) -> None:
        payload = {
            "cpuUsagePercent": 20.0,
            "gpuUsagePercent": 30.0,
            "memoryUsedPercent": 40.0,
            "diskUsedPercent": 91.6,
            "diskSmartStatus": "정상",
            "unavailableReason": {"diskBusyEstimatePercent": "disk busy counter unavailable"},
        }
        previous_payload = dict(payload)
        previous_payload.pop("unavailableReason")
        previous_payload["diskBusyEstimatePercent"] = 63.0
        previous = agent.build_metric_snapshot(
            datetime.now(agent.KST) - timedelta(seconds=5), 0, agent.SYSTEM_METRIC_KIND, previous_payload
        )
        row = agent.build_metric_snapshot(datetime.now(agent.KST), 1, agent.SYSTEM_METRIC_KIND, payload)

        screen = agent.build_symptom_screen_state(agent.SymptomScreenInput(
            agent.hardware_sensor_snapshot(row, [previous, row]), "디스크 상태 확인", None
        ))
        disk = screen.widgets[3]

        self.assertEqual("저장 공간 91.6%", disk.primary)
        self.assertEqual("활성 시간 센서 미지원", disk.details[1])
        self.assertEqual((), disk.history)
        self.assertEqual("주의", disk.status)
        self.assertEqual("warning", disk.tone)

        previous_readings = agent.MetricsNormalizer().normalize(
            ProviderSample(previous_payload, (datetime.now(timezone.utc) - timedelta(seconds=5)).isoformat(), "hardware")
        )
        current_readings = agent.MetricsNormalizer().normalize(
            ProviderSample(payload, datetime.now(timezone.utc).isoformat(), "hardware")
        )
        metrics = agent.MetricsSnapshot(
            "disk-storage-only",
            "LIVE",
            True,
            (*previous_readings, *current_readings),
        )
        metrics_disk = agent.build_symptom_screen_state(
            agent.metrics_snapshot_screen_input(metrics, "디스크 상태 확인", ("disk",))
        ).widgets[3]
        self.assertEqual("저장 공간 91.6%", metrics_disk.primary)
        self.assertEqual((), metrics_disk.history)

    def test_disk_storage_and_activity_fields_remain_available_to_metrics_and_logs(self) -> None:
        payload = {
            "diskBusyEstimatePercent": 7.5,
            "diskUsedPercent": 62.5,
            "diskSmartStatus": "정상",
        }
        row = agent.build_metric_snapshot(datetime.now(agent.KST), 0, agent.SYSTEM_METRIC_KIND, payload)
        readings = agent.MetricsNormalizer().normalize(
            ProviderSample(payload, datetime.now(timezone.utc).isoformat(), "hardware")
        )

        self.assertEqual(7.5, row["diskBusyEstimatePercent"])
        self.assertEqual(62.5, row["diskUsedPercent"])
        self.assertEqual(7.5, next(item.value for item in readings if item.component == "disk" and item.metric_type == "activity"))
        self.assertEqual(62.5, next(item.value for item in readings if item.component == "disk" and item.metric_type == "usage"))

    def test_symptom_screen_distinguishes_zero_fan_unsupported_and_failed_sensor(self) -> None:
        zero_fan_payload = {
            "cpuUsagePercent": 20.0,
            "cpuTempCelsius": 55.0,
            "gpuUsagePercent": 30.0,
            "gpuTempCelsius": 60.0,
            "gpuFanRpm": 0.0,
            "memoryUsedPercent": 40.0,
            "memoryUsedBytes": 6 * 1024**3,
            "memoryTotalBytes": 16 * 1024**3,
            "diskUsedPercent": 45.0,
            "diskSmartStatus": None,
            "unavailableReason": {"diskSmartStatus": "SMART unsupported"},
        }
        zero_fan_row = agent.build_metric_snapshot(datetime.now(agent.KST), 0, agent.SYSTEM_METRIC_KIND, zero_fan_payload)
        zero_fan_screen = agent.build_symptom_screen_state(agent.SymptomScreenInput(
            agent.hardware_sensor_snapshot(zero_fan_row, [zero_fan_row]), "팬을 확인해 주세요.", None
        ))
        self.assertIn("0 RPM (정지)", zero_fan_screen.widgets[1].details[1])
        self.assertIn("측정 불가", zero_fan_screen.widgets[3].details[0])
        self.assertEqual("default", zero_fan_screen.widgets[3].tone)

        failed = agent.failed_system_metric_row("system sensor collection failed")
        failed_screen = agent.build_symptom_screen_state(agent.SymptomScreenInput(
            agent.hardware_sensor_snapshot(failed, [failed]), "센서 확인", None
        ))
        self.assertEqual(failed_screen.widgets[0].status, "확인 실패")
        self.assertIn("확인 실패", failed_screen.widgets[0].primary)

    def test_demo_sensor_provider_is_deterministic_and_uses_shared_status_policy(self) -> None:
        config = agent.AgentConfig(
            api_base_url="http://localhost:8080",
            activation_token="activation-token",
            device_fingerprint_hash="fingerprint",
            os_version="Windows 11",
            agent_version="test-agent",
            policy_version="test-policy",
        )
        provider = agent.DemoSensorProvider()

        first = agent.build_symptom_screen_state(provider.load(config))
        second = agent.build_symptom_screen_state(provider.load(config))

        self.assertEqual(first, second)
        self.assertEqual(first.widgets[1].status, "주의")
        self.assertIn("0 RPM (정지)", first.widgets[1].details[1])

    def test_metrics_screen_handles_missing_web_symptom_and_requested_checks(self) -> None:
        readings = agent.MetricsNormalizer().normalize(agent.InitialDemoSensorProvider().collect_sample(0))
        metrics = agent.MetricsSnapshot("diagnosis-missing-symptom", "DEMO", False, readings)

        screen = agent.build_symptom_screen_state(
            agent.metrics_snapshot_screen_input(metrics, "", ("disk",))
        )

        self.assertEqual(screen.symptom, "웹에서 전달받은 증상 정보가 없습니다.")
        self.assertTrue(screen.widgets[3].highlighted)

    def test_ui_state_changes_only_after_diagnosis_and_result_actions(self) -> None:
        request = DiagnosisRequest(
            diagnosis_id="diagnosis-auto-transition",
            device_id="device-1",
            symptom="게임 중 프레임이 저하됩니다.",
            requested_checks=("gpu",),
            requested_at="2026-07-13T00:00:00Z",
            expires_at="2026-07-13T00:02:00Z",
            mode="LIVE",
        )
        session = DiagnosisSession(request)
        result = agent.DiagnosisResult(
            diagnosis_id=request.diagnosis_id,
            severity="NORMAL",
            title="측정된 하드웨어 상태가 정상 범위입니다.",
            summary="현재 수집된 근거에서는 즉시 조치가 필요한 이상이 확인되지 않았습니다.",
            evidence=(),
            findings=(),
            suspected_causes=(),
            recommended_actions=("현재 상태 유지",),
            resolution_type="NONE",
            can_auto_recover=False,
            unsupported_checks=(),
            evaluated_at="2026-07-13T00:01:00Z",
        )

        self.assertEqual(
            "SYMPTOM_CONFIRM",
            agent.diagnosis_session_ui_state(
                session,
                agent.MetricsSnapshot(request.diagnosis_id, "LIVE", False, ()),
            ),
        )
        self.assertEqual(
            "SYMPTOM_CONFIRM",
            agent.diagnosis_session_ui_state(
                session,
                agent.MetricsSnapshot(request.diagnosis_id, "LIVE", True, ()),
            ),
        )
        self.assertEqual(
            "DIAGNOSING",
            agent.diagnosis_session_ui_state(
                session,
                agent.MetricsSnapshot(request.diagnosis_id, "LIVE", True, ()),
                agent.DiagnosisRunSnapshot(
                    diagnosis_id=request.diagnosis_id,
                    mode="LIVE",
                    state="PARTIALLY_COMPLETED",
                    progress=100,
                    transition_allowed=True,
                ),
                diagnosis_started=True,
            ),
        )
        self.assertEqual(
            "DIAGNOSIS_RESULT",
            agent.diagnosis_session_ui_state(
                session,
                agent.MetricsSnapshot(request.diagnosis_id, "LIVE", True, ()),
                agent.DiagnosisRunSnapshot(
                    diagnosis_id=request.diagnosis_id,
                    mode="LIVE",
                    state="PARTIALLY_COMPLETED",
                    progress=100,
                    transition_allowed=True,
                ),
                result,
                diagnosis_started=True,
                result_requested=True,
            ),
        )
        self.assertEqual(
            "DIAGNOSING",
            agent.diagnosis_session_ui_state(
                session,
                agent.MetricsSnapshot(request.diagnosis_id, "LIVE", True, ()),
                agent.DiagnosisRunSnapshot(
                    diagnosis_id=request.diagnosis_id,
                    mode="LIVE",
                    state="FAILED",
                    progress=99,
                    transition_allowed=False,
                ),
                diagnosis_started=True,
                result_requested=True,
            ),
        )

    def test_initial_metrics_auto_start_is_requested_only_without_an_active_session(self) -> None:
        request = DiagnosisRequest(
            diagnosis_id="diagnosis-existing",
            device_id="device-1",
            symptom="",
            requested_checks=("cpu", "gpu", "memory", "disk"),
            requested_at="2026-07-13T00:00:00Z",
            expires_at="2026-07-13T00:02:00Z",
            mode="LIVE",
        )

        self.assertTrue(agent.should_auto_start_initial_metrics(None, False))
        self.assertFalse(agent.should_auto_start_initial_metrics(None, True))
        self.assertFalse(agent.should_auto_start_initial_metrics(DiagnosisSession(request), False))

    def test_go_home_resets_and_reschedules_initial_metrics_without_stale_callbacks(self) -> None:
        source = inspect.getsource(agent.show_log_viewer)
        go_home_source = source[source.index("def go_home"):source.index("def connect_as")]
        cleanup_source = source[source.index("def cleanup_ui_resources"):source.index("def close_window")]

        self.assertIn('callback_state["initialMetricsAfterId"] = None', source)
        self.assertIn('callback_state["initialMetricsAfterId"] is not None', source)
        self.assertIn("cancel_diagnosis_progress_tick()", go_home_source)
        self.assertIn('ui["initialMetricsRequested"] = False', go_home_source)
        self.assertIn("schedule_initial_metrics_start()", go_home_source)
        self.assertIn('"initialMetricsAfterId",', cleanup_source)
        self.assertNotIn("root.after(0, auto_start_initial_metrics)", source)

    def test_metric_wave_uses_latest_real_usage_as_target_and_smooths_changes(self) -> None:
        self.assertEqual(0.0, agent.usage_wave_target_amplitude(None))
        self.assertEqual(0.65, agent.usage_wave_target_amplitude(0.0))
        self.assertEqual(2.5, agent.usage_wave_target_amplitude(20.0))
        self.assertEqual(7.0, agent.usage_wave_target_amplitude(50.0))
        self.assertEqual(14.0, agent.usage_wave_target_amplitude(75.0))
        self.assertEqual(22.0, agent.usage_wave_target_amplitude(92.0))
        self.assertEqual(agent.METRIC_WAVE_MAX_AMPLITUDE, agent.usage_wave_target_amplitude(100.0))
        self.assertEqual(agent.METRIC_WAVE_MAX_AMPLITUDE, agent.usage_wave_target_amplitude(140.0))

        target = agent.usage_wave_target_amplitude(80.0)
        first = agent.smooth_wave_amplitude(0.0, target)
        second = agent.smooth_wave_amplitude(first, target)
        self.assertGreater(first, 0.0)
        self.assertGreater(second, first)
        self.assertLess(second, target)

        coordinates = agent.metric_wave_coordinates(10, 40, 100, second, 0.5, point_count=6)
        self.assertEqual(12, len(coordinates))
        self.assertEqual(10.0, coordinates[0])
        self.assertEqual(110.0, coordinates[-2])

    def test_log_viewer_defers_wave_bucket_generation_until_after_initial_render(self) -> None:
        source = inspect.getsource(agent.show_log_viewer)

        self.assertIn("DeferredFluidWaveCache(", source)
        self.assertIn("if latest_value is None:", source)
        self.assertIn("static_fluid_wave_photo(0)", source)
        self.assertIn("fluid_wave_cache.request(bucket)", source)
        self.assertIn("static_fluid_wave_photo(bucket)", source)
        self.assertNotIn("build_fluid_wave_cache", source)

    def test_log_viewer_filters_child_map_events_and_stops_ui_callbacks_while_hidden(self) -> None:
        source = inspect.getsource(agent.show_log_viewer)

        self.assertIn('root.bind("<Map>", handle_root_map', source)
        self.assertIn('root.bind("<Unmap>", handle_root_unmap', source)
        self.assertIn("WindowVisibilityState.is_root_event", source)
        self.assertIn('pause_ui_animation("user_close")', source)
        self.assertIn('(\"metricsAfterId\", \"renderAfterId\", \"diagnosisProgressAfterId\")', source)
        self.assertIn("queue_ui_render()", source)

    def test_pretendard_is_the_first_ui_font_candidate(self) -> None:
        self.assertEqual("Pretendard", agent.UI_FONT_CANDIDATES[0])

    def test_code43_demo_symptom_is_specific_and_does_not_overmatch_unrelated_text(self) -> None:
        symptom = "게임 중 화면이 잠깐 꺼졌다가 다시 켜지고,\n이후 게임이 심하게 느려졌어요."

        self.assertTrue(agent.is_supported_graphics_symptom(symptom))
        self.assertFalse(agent.is_supported_graphics_symptom("게임 중 소리가 잠깐 끊겼다가 다시 들립니다."))
        self.assertFalse(agent.is_supported_graphics_symptom("게임이 심하게 느려졌어요."))

    def test_code43_fixture_is_selected_only_for_explicit_demo_mode(self) -> None:
        live_provider = MagicMock(return_value="live-snapshot")
        demo_provider = MagicMock(return_value="demo-snapshot")
        symptom = "게임 중 화면이 잠깐 꺼졌다가 다시 켜지고, 이후 게임이 심하게 느려졌어요."

        def session(mode: str, current_symptom: str) -> DiagnosisSession:
            return DiagnosisSession(DiagnosisRequest(
                "diagnosis-demo", "device-1", current_symptom, ("gpu",),
                "2026-07-13T00:00:00Z", "2026-07-13T00:02:00Z", mode,
            ))

        self.assertEqual(
            "live-snapshot",
            agent.collect_session_windows_graphics_snapshot(
                session("LIVE", symptom), live_provider, demo_provider,
            ),
        )
        self.assertEqual(
            "demo-snapshot",
            agent.collect_session_windows_graphics_snapshot(
                session("DEMO", "게임이 심하게 느려졌어요."), live_provider, demo_provider,
            ),
        )
        self.assertEqual(
            "demo-snapshot",
            agent.collect_session_windows_graphics_snapshot(
                session("DEMO", symptom), live_provider, demo_provider,
            ),
        )
        live_provider.assert_called_once_with()
        self.assertEqual(2, demo_provider.call_count)

    def test_active_live_and_demo_sessions_are_both_restorable(self) -> None:
        for mode in ("LIVE", "DEMO"):
            with self.subTest(mode=mode):
                request = DiagnosisRequest(
                    f"diagnosis-{mode.casefold()}", "device-1", "증상", ("gpu",),
                    "2026-07-13T00:00:00Z", "2026-07-13T00:02:00Z", mode,
                )
                session = DiagnosisSession(request, agent_state="RUNNING")
                diagnosis = agent.DiagnosisRunSnapshot(
                    diagnosis_id=request.diagnosis_id,
                    mode=mode,
                    state="DIAGNOSING",
                )

                self.assertIs(session, agent.active_viewer_session(session, diagnosis))

        self.assertNotIn("discard_persisted_demo_session", inspect.getsource(agent.run_background))

    def test_demo_scenario_metadata_remains_internal_without_large_page_badge(self) -> None:
        source = inspect.getsource(agent.show_log_viewer)

        self.assertNotIn("draw_demo_scenario_badge", source)
        self.assertNotIn('f"DEMO · {scenario_id}"', source)
        self.assertIn('measurement_label = "시연 데이터" if ui["demo"] else "실시간 측정"', source)

    def test_log_viewer_uses_single_native_window_title_bar(self) -> None:
        source = inspect.getsource(agent.show_log_viewer)

        self.assertEqual("PC Agent", agent.WINDOW_TITLE)
        self.assertIn("root.title(WINDOW_TITLE)", source)
        self.assertIn("root.overrideredirect(False)", source)
        self.assertIn('root.protocol("WM_DELETE_WINDOW", close_window)', source)
        self.assertIn('canvas.move("all", 0, -PC_AGENT_REMOVED_HEADER_HEIGHT)', source)
        self.assertNotIn("def draw_header", source)
        self.assertNotIn('tags="window-min"', source)
        self.assertNotIn('tags="window-max"', source)
        self.assertNotIn('tags="window-close"', source)
        self.assertNotIn("def start_drag", source)
        self.assertNotIn("def drag_window", source)

    def test_page_two_uses_shared_icons_and_updates_progress_without_full_rerender(self) -> None:
        source = inspect.getsource(agent.show_log_viewer)
        tick_source = source[source.index("def diagnosis_progress_tick"):source.index("def draw_diagnosing")]
        diagnosing_source = source[source.index("def draw_diagnosing"):source.index("def draw_result_icon")]

        self.assertIn("render_pillow_home_hardware_icon", source)
        self.assertNotIn("create_arc", diagnosing_source)
        self.assertIn('canvas.itemconfigure(items["ring"]', tick_source)
        self.assertIn('canvas.itemconfigure(items["percent"]', tick_source)
        self.assertNotIn("render()", tick_source)
        self.assertTrue(tick_source.rstrip().endswith("schedule_diagnosis_progress_tick()"))
        self.assertIn('callback_state["diagnosisProgressAfterId"]', source)
        self.assertIn('round_rect(70, 180, 930, 296', diagnosing_source)
        self.assertIn('text(148, 270, fitted_text(scope_text', diagnosing_source)
        self.assertIn('cy = 550 + idx * 38', diagnosing_source)

    def test_page_text_is_measured_and_action_columns_are_even(self) -> None:
        measure = lambda value: len(value) * 10

        self.assertEqual("12345\n6789…", agent.fit_measured_text("123456789012", 50, 2, measure))
        self.assertEqual(((145, 382), (382, 618), (618, 855)), agent.result_action_columns(3))
        self.assertEqual(((145, 500), (500, 855)), agent.result_action_columns(2))
        self.assertEqual((580, 588), agent.result_action_vertical_layout("한 줄", 16))
        self.assertEqual((572, 588), agent.result_action_vertical_layout("첫 줄\n둘째 줄", 16))

        result_source = inspect.getsource(agent.show_log_viewer)
        self.assertIn('render_number_badge(26, "#fff0ef")', result_source)
        self.assertNotIn('canvas.create_oval(badge_x - 13', result_source)

    def test_diagnosis_presentations_use_actual_task_and_evidence_status(self) -> None:
        running = agent.DiagnosisTask("display_devices", "gpu", 20, status="RUNNING")
        warning = agent.DiagnosisTask(
            "display_drivers",
            "gpu",
            10,
            status="COMPLETED",
            evidence=({"status": "WARNING"},),
        )
        completed = agent.DiagnosisTask("current_system_status", "system", 15, status="COMPLETED")
        completed_without_evidence = agent.DiagnosisTask(
            "display_devices",
            "gpu",
            20,
            status="COMPLETED",
        )
        completed_with_evidence = agent.DiagnosisTask(
            "display_devices",
            "gpu",
            20,
            status="COMPLETED",
            evidence=({"status": "OK", "deviceCount": 1},),
        )

        self.assertEqual(
            ("검사 중", "running"),
            agent.diagnosis_component_presentation(agent.DiagnosisRunSnapshot(tasks=(running,)), "gpu"),
        )
        self.assertEqual(
            ("주의", "warning"),
            agent.diagnosis_component_presentation(agent.DiagnosisRunSnapshot(tasks=(warning,)), "gpu"),
        )
        self.assertEqual(
            ("초기 상태 확인", "neutral"),
            agent.diagnosis_component_presentation(agent.DiagnosisRunSnapshot(tasks=(completed,)), "cpu"),
        )
        self.assertEqual(
            ("근거 없음", "neutral"),
            agent.diagnosis_component_presentation(
                agent.DiagnosisRunSnapshot(tasks=(completed_without_evidence,)),
                "gpu",
            ),
        )
        self.assertEqual(
            ("정상", "success"),
            agent.diagnosis_component_presentation(
                agent.DiagnosisRunSnapshot(tasks=(completed_with_evidence,)),
                "gpu",
            ),
        )
        self.assertEqual(("시간 초과", "error"), agent.diagnosis_task_presentation("TIMED_OUT"))
        self.assertEqual(("오류", "error"), agent.diagnosis_event_presentation("TASK_FAILED"))

    def test_symptom_display_preserves_web_input_and_uses_standalone_summary(self) -> None:
        web_symptoms = ("게임 A에서만 화면이 멈춥니다.", "영상 편집 중 GPU 부하가 증가합니다.")
        for symptom in web_symptoms:
            with self.subTest(symptom=symptom):
                display = agent.symptom_display_state(
                    "WEB_REQUEST",
                    symptom,
                    agent.MetricsSnapshot("diagnosis-web", "LIVE", False, ()),
                )
                self.assertEqual("전달받은 증상", display.title)
                self.assertEqual(symptom, display.description)

        standalone = agent.symptom_display_state(
            "STANDALONE",
            "",
            agent.MetricsSnapshot(None, None, False, ()),
        )
        self.assertEqual("초기 상태 요약", standalone.title)
        self.assertNotIn("전달받은 증상", standalone.title)
        self.assertNotIn("게임", standalone.description)

        readings = agent.MetricsNormalizer().normalize(agent.InitialDemoSensorProvider().collect_sample(0))
        collecting = agent.symptom_display_state(
            "STANDALONE",
            "",
            agent.MetricsSnapshot("standalone-1", "DEMO", False, readings),
        )
        ready = agent.symptom_display_state(
            "STANDALONE",
            "",
            agent.MetricsSnapshot("standalone-1", "DEMO", True, readings),
        )
        self.assertIn("확인하고 있습니다", collecting.description)
        self.assertNotEqual(collecting.description, ready.description)

    def test_diagnosis_orchestrator_starts_only_once_after_initial_metrics(self) -> None:
        request = DiagnosisRequest(
            diagnosis_id="diagnosis-once",
            device_id="device-1",
            symptom="GPU 상태 확인",
            requested_checks=("gpu",),
            requested_at="2026-07-13T00:00:00Z",
            expires_at="2026-07-13T00:02:00Z",
            mode="LIVE",
        )
        session = DiagnosisSession(request)
        with tempfile.TemporaryDirectory() as directory:
            diagnosis_store = agent.DiagnosisSessionStore(Path(directory) / "session.json")
            diagnosis_store.accept(session)
            metrics_store = agent.MetricsStore()
            metrics_store.begin(request.diagnosis_id, request.mode)
            metrics_store.complete(request.diagnosis_id)
            orchestrator = SimpleNamespace(prepare=MagicMock(), start=MagicMock(return_value=True))

            first = agent.start_diagnosis_once(session, diagnosis_store, metrics_store, orchestrator)
            second = agent.start_diagnosis_once(session, diagnosis_store, metrics_store, orchestrator)

        self.assertEqual("RUNNING", first.agent_state)
        self.assertIsNone(second)
        orchestrator.prepare.assert_called_once()
        orchestrator.start.assert_called_once()

    def test_web_and_standalone_initial_metrics_start_from_their_expected_actions(self) -> None:
        web_request = DiagnosisRequest(
            diagnosis_id="diagnosis-web-start",
            device_id="device-1",
            symptom="게임 실행 중 화면이 멈춥니다.",
            requested_checks=("cpu", "gpu", "memory", "disk"),
            requested_at="2026-07-13T00:00:00Z",
            expires_at="2026-07-13T00:02:00Z",
            mode="LIVE",
        )
        with tempfile.TemporaryDirectory() as directory:
            web_store = agent.DiagnosisSessionStore(Path(directory) / "web-session.json")
            web_session = DiagnosisSession(web_request)
            web_store.accept(web_session)
            web_coordinator = SimpleNamespace(start=MagicMock(return_value=True))
            web_orchestrator = SimpleNamespace(prepare=MagicMock())
            web_started = agent.start_initial_metrics_session(
                web_session,
                "LIVE",
                "device-1",
                web_store,
                agent.MetricsStore(),
                agent.DiagnosisResultStore(),
                web_orchestrator,
                web_coordinator,
            )

            standalone_store = agent.DiagnosisSessionStore(Path(directory) / "standalone-session.json")
            standalone_coordinator = SimpleNamespace(start=MagicMock(return_value=True))
            standalone_orchestrator = SimpleNamespace(prepare=MagicMock())
            self.assertIsNone(standalone_store.session)
            standalone_started = agent.start_initial_metrics_session(
                None,
                "LIVE",
                "device-1",
                standalone_store,
                agent.MetricsStore(),
                agent.DiagnosisResultStore(),
                standalone_orchestrator,
                standalone_coordinator,
                now=lambda: datetime(2026, 7, 13, tzinfo=timezone.utc),
            )

        self.assertEqual("WEB_REQUEST", web_started.request.source)
        self.assertEqual(web_request.symptom, web_started.request.symptom)
        web_coordinator.start.assert_called_once_with(web_request.diagnosis_id, "LIVE")
        self.assertEqual("STANDALONE", standalone_started.request.source)
        self.assertEqual("", standalone_started.request.symptom)
        standalone_coordinator.start.assert_called_once()

    def test_result_action_is_unavailable_for_failed_cancelled_and_timed_out(self) -> None:
        request = DiagnosisRequest(
            diagnosis_id="diagnosis-terminal",
            device_id="device-1",
            symptom="하드웨어 상태 확인",
            requested_checks=("cpu",),
            requested_at="2026-07-13T00:00:00Z",
            expires_at="2026-07-13T00:02:00Z",
            mode="LIVE",
        )
        session = DiagnosisSession(request, "FAILED")
        for state in ("FAILED", "CANCELLED", "TIMED_OUT"):
            with self.subTest(state=state):
                snapshot = agent.DiagnosisRunSnapshot(
                    diagnosis_id=request.diagnosis_id,
                    mode="LIVE",
                    state=state,
                    progress=100,
                    transition_allowed=False,
                )
                self.assertFalse(agent.diagnosis_result_available(session, snapshot, None))
                self.assertEqual(
                    "DIAGNOSING",
                    agent.diagnosis_session_ui_state(
                        session,
                        agent.MetricsSnapshot(request.diagnosis_id, "LIVE", True, ()),
                        snapshot,
                        diagnosis_started=True,
                        result_requested=True,
                    ),
                )

    def test_terminal_session_is_moved_to_idle_without_restoring_completed_ui(self) -> None:
        request = DiagnosisRequest(
            diagnosis_id="diagnosis-restored-terminal",
            device_id="device-1",
            symptom="",
            requested_checks=("cpu", "gpu", "memory", "disk"),
            requested_at="2026-07-13T00:00:00Z",
            expires_at="2026-07-13T00:02:00Z",
            mode="LIVE",
            source="STANDALONE",
        )
        cases = (
            ("COMPLETED", "COMPLETED"),
            ("RUNNING", "PARTIALLY_COMPLETED"),
            ("CANCELLED", "CANCELLED"),
            ("TIMED_OUT", "TIMED_OUT"),
        )
        for agent_state, diagnosis_state in cases:
            with self.subTest(agent_state=agent_state, diagnosis_state=diagnosis_state):
                with tempfile.TemporaryDirectory() as directory:
                    session_path = Path(directory) / "diagnosis-request-state.json"
                    progress_path = Path(directory) / "diagnosis-progress-state.json"
                    diagnosis_store = agent.DiagnosisSessionStore(session_path)
                    diagnosis_store.accept(DiagnosisSession(request, agent_state))
                    diagnosis_log_store = agent.DiagnosisLogStore(progress_path)
                    diagnosis_snapshot = agent.DiagnosisRunSnapshot(
                        diagnosis_id=request.diagnosis_id,
                        mode="LIVE",
                        state=diagnosis_state,
                        progress=100,
                        transition_allowed=True,
                    )
                    diagnosis_log_store.replace(diagnosis_snapshot)
                    preserved_progress = progress_path.read_text(encoding="utf-8")

                    self.assertTrue(agent.move_terminal_session_to_idle(diagnosis_store, diagnosis_snapshot))
                    idle_session = diagnosis_store.session

                    self.assertEqual("IDLE", idle_session.agent_state)
                    self.assertEqual(request.diagnosis_id, idle_session.request.diagnosis_id)
                    self.assertIsNone(agent.active_viewer_session(idle_session, diagnosis_snapshot))
                    self.assertEqual(
                        "SYMPTOM_CONFIRM",
                        agent.diagnosis_session_ui_state(None, None),
                    )
                    self.assertEqual(preserved_progress, progress_path.read_text(encoding="utf-8"))

        sampled_at = "2026-07-13T00:00:00+00:00"
        metrics = agent.MetricsSnapshot(
            "diagnosis-cpu-display",
            "LIVE",
            True,
            (
                agent.MetricReading(
                    "cpu", "usage", 42.0, "%", "AVAILABLE", "NORMAL", "psutil", sampled_at,
                ),
                agent.MetricReading(
                    "cpu", "temperature", None, "°C", "UNSUPPORTED", "UNAVAILABLE",
                    "psutil-temperature", sampled_at, "SENSOR_UNSUPPORTED", "CPU temperature sensor unavailable",
                ),
            ),
        )
        screen = agent.build_symptom_screen_state(agent.metrics_snapshot_screen_input(metrics, ""))
        cpu_card = screen.widgets[0]
        self.assertEqual("사용률 42%", cpu_card.primary)
        self.assertEqual((42.0,), cpu_card.history)
        self.assertEqual(("온도 센서 미지원",), cpu_card.details)

    def test_session_replacement_status_preserves_reason_for_server_sync(self) -> None:
        request = DiagnosisRequest(
            diagnosis_id="diagnosis-replaced",
            device_id="device-1",
            symptom="화면 멈춤",
            requested_checks=("gpu",),
            requested_at="2026-07-13T00:00:00Z",
            expires_at="2026-07-13T00:02:00Z",
            mode="LIVE",
        )
        replacement = agent.DiagnosisSessionReplacement(DiagnosisSession(request), "SUPERSEDED")

        detail = agent.diagnosis_session_replacement_sync_detail(
            replacement,
            datetime(2026, 7, 13, tzinfo=timezone.utc),
        )

        self.assertEqual("diagnosis-replaced", detail["diagnosisId"])
        self.assertEqual("DIAGNOSIS_CANCELLED", detail["eventType"])
        self.assertEqual("CANCELLED", detail["sessionState"])
        self.assertEqual({"reason": "SUPERSEDED"}, detail["metadata"])
        self.assertEqual("2026-07-13T00:00:00+00:00", detail["occurredAt"])

    def test_system_sensor_provider_keeps_web_symptom_and_suspected_component(self) -> None:
        class Collector:
            def collect(self, ts: datetime, index: int) -> dict:
                payload = {
                    "cpuUsagePercent": 20.0,
                    "cpuTempCelsius": 55.0,
                    "gpuUsagePercent": 30.0,
                    "gpuTempCelsius": 60.0,
                    "gpuFanPercent": 25.0,
                    "memoryUsedPercent": 40.0,
                    "memoryUsedBytes": 6 * 1024**3,
                    "memoryTotalBytes": 16 * 1024**3,
                    "diskBusyEstimatePercent": 15.0,
                    "diskUsedPercent": 45.0,
                    "diskSmartStatus": "정상",
                }
                return agent.build_metric_snapshot(ts, index, agent.SYSTEM_METRIC_KIND, payload)

        with tempfile.TemporaryDirectory() as directory:
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_version="test-agent",
                policy_version="test-policy",
                log_dir=Path(directory),
                symptom="SSD 저장장치에서 오류가 발생합니다.",
                symptom_type="VISIT_DISK_FAILURE",
            )
            screen = agent.build_symptom_screen_state(agent.SystemSensorProvider(Collector()).load(config))

        self.assertEqual(screen.symptom, "SSD 저장장치에서 오류가 발생합니다.")
        self.assertTrue(screen.widgets[3].highlighted)

    def test_metric_collector_uses_windows_gpu_counter_after_nvidia_failure(self) -> None:
        class FakePsutil:
            def cpu_percent(self, interval: float = 0.0) -> float:
                return 10.0

            def virtual_memory(self) -> SimpleNamespace:
                return SimpleNamespace(percent=20.0)

            def disk_usage(self, path: str) -> SimpleNamespace:
                return SimpleNamespace(percent=30.0)

            def disk_io_counters(self) -> SimpleNamespace:
                return SimpleNamespace(read_bytes=1000, write_bytes=500, read_count=10, write_count=5, busy_time=1000)

            def sensors_temperatures(self, fahrenheit: bool = False) -> dict:
                return {}

            def process_iter(self, attrs: list[str]) -> list:
                return []

        collector = agent.HardwareMetricCollector(
            psutil_module=FakePsutil(),
            nvidia_smi_runner=lambda: SimpleNamespace(returncode=1, stdout=""),
            gpu_counter_reader=lambda: (37.8, None),
            powershell_gpu_counter_reader=missing_gpu_counter,
            time_fn=lambda: 100.0,
        )

        row = collector.collect(datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST), 0)

        self.assertEqual(row["gpuUsage"], 37.8)
        self.assertEqual(row["gpuUsagePercent"], 37.8)
        self.assertIsNone(row["vramUsage"])
        self.assertIsNone(row["gpuTemp"])
        self.assertEqual(row["gpuCollectorSource"], "windows-performance-counter")
        self.assertIn("vramUsage", row["unavailableReason"])
        self.assertEqual(row["sensorStatus"]["vramUsagePercent"], "unsupported")
        self.assertEqual(row["sensorStatus"]["gpuTempCelsius"], "unsupported")
        self.assertEqual(agent.display_log_table_values(row)[5], "37.8%")
        self.assertEqual(agent.display_log_table_values(row)[6], agent.OPTIONAL_SENSOR_UNSUPPORTED_TEXT)
        self.assertEqual(agent.display_log_table_values(row)[7], agent.OPTIONAL_SENSOR_UNSUPPORTED_TEXT)
        self.assertEqual(agent.display_log_table_values(row)[8], agent.OPTIONAL_SENSOR_UNSUPPORTED_TEXT)

    def test_metric_collector_uses_powershell_gpu_counter_as_secondary_fallback(self) -> None:
        class FakePsutil:
            def cpu_percent(self, interval: float = 0.0) -> float:
                return 10.0

            def virtual_memory(self) -> SimpleNamespace:
                return SimpleNamespace(percent=20.0)

            def disk_usage(self, path: str) -> SimpleNamespace:
                return SimpleNamespace(percent=30.0)

            def disk_io_counters(self) -> SimpleNamespace:
                return SimpleNamespace(read_bytes=1000, write_bytes=500, read_count=10, write_count=5, busy_time=1000)

            def sensors_temperatures(self, fahrenheit: bool = False) -> dict:
                return {}

            def process_iter(self, attrs: list[str]) -> list:
                return []

        collector = agent.HardwareMetricCollector(
            psutil_module=FakePsutil(),
            nvidia_smi_runner=lambda: SimpleNamespace(returncode=1, stdout=""),
            gpu_counter_reader=missing_gpu_counter,
            powershell_gpu_counter_reader=lambda: (16.4, None),
            time_fn=lambda: 100.0,
        )

        row = collector.collect(datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST), 0)

        self.assertEqual(row["gpuUsagePercent"], 16.4)
        self.assertEqual(row["gpuCollectorSource"], "powershell-get-counter")

    def test_metric_collector_marks_gpu_unavailable_when_all_sources_fail(self) -> None:
        class FakePsutil:
            def cpu_percent(self, interval: float = 0.0) -> float:
                return 10.0

            def virtual_memory(self) -> SimpleNamespace:
                return SimpleNamespace(percent=20.0)

            def disk_usage(self, path: str) -> SimpleNamespace:
                return SimpleNamespace(percent=30.0)

            def disk_io_counters(self) -> SimpleNamespace:
                return SimpleNamespace(read_bytes=1000, write_bytes=500, read_count=10, write_count=5, busy_time=1000)

            def sensors_temperatures(self, fahrenheit: bool = False) -> dict:
                return {}

            def process_iter(self, attrs: list[str]) -> list:
                return []

        collector = agent.HardwareMetricCollector(
            psutil_module=FakePsutil(),
            nvidia_smi_runner=lambda: SimpleNamespace(returncode=1, stdout=""),
            gpu_counter_reader=missing_gpu_counter,
            powershell_gpu_counter_reader=missing_gpu_counter,
            time_fn=lambda: 100.0,
        )

        row = collector.collect(datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST), 0)

        self.assertIsNone(row["gpuUsagePercent"])
        self.assertIsNone(row["vramUsagePercent"])
        self.assertIsNone(row["gpuTempCelsius"])
        self.assertEqual(row["gpuCollectorSource"], "unavailable")
        self.assertIn("gpuUsagePercent", row["unavailableReason"])

    def test_metric_collector_stores_only_process_names(self) -> None:
        class FakeProcess:
            def __init__(self, pid: int, name: str, rss: int, user_time: float) -> None:
                self.pid = pid
                self.info = {
                    "name": name,
                    "memory_info": SimpleNamespace(rss=rss),
                    "cpu_times": SimpleNamespace(user=user_time, system=0.0),
                }

        class FakePsutil:
            def __init__(self) -> None:
                self.index = 0

            def cpu_percent(self, interval: float = 0.0) -> float:
                return 10.0

            def virtual_memory(self) -> SimpleNamespace:
                return SimpleNamespace(percent=20.0)

            def disk_usage(self, path: str) -> SimpleNamespace:
                return SimpleNamespace(percent=30.0)

            def disk_io_counters(self) -> SimpleNamespace:
                return SimpleNamespace(read_bytes=1000, write_bytes=500, read_count=10, write_count=5, busy_time=1000)

            def sensors_temperatures(self, fahrenheit: bool = False) -> dict:
                return {}

            def cpu_count(self) -> int:
                return 4

            def process_iter(self, attrs: list[str]) -> list[FakeProcess]:
                self.index += 1
                if self.index == 1:
                    return [
                        FakeProcess(1, "C:\\Users\\me\\heavy.exe", 500, 1.0),
                        FakeProcess(2, "render.exe", 100, 1.0),
                    ]
                return [
                    FakeProcess(1, "C:\\Users\\me\\heavy.exe", 600, 1.1),
                    FakeProcess(2, "render.exe", 200, 3.0),
                ]

        times = iter([100.0, 105.0])
        collector = agent.HardwareMetricCollector(
            psutil_module=FakePsutil(),
            nvidia_smi_runner=lambda: SimpleNamespace(returncode=1, stdout=""),
            gpu_counter_reader=missing_gpu_counter,
            powershell_gpu_counter_reader=missing_gpu_counter,
            time_fn=lambda: next(times),
        )

        collector.collect(datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST), 0)
        row = collector.collect(datetime(2026, 7, 2, 14, 0, 5, tzinfo=agent.KST), 1)

        self.assertEqual(row["topCpuProcess"], "render.exe")
        self.assertEqual(row["topRamProcess"], "heavy.exe")
        self.assertNotIn("\\", row["topRamProcess"])
        self.assertNotIn("Users", row["topRamProcess"])

    def test_issue_notification_is_throttled(self) -> None:
        runtime = agent.AgentRuntime()
        warning = {"eventType": "DISPLAY_DRIVER_WARNING", "message": "Display driver warning observed."}
        normal = {"eventType": "SYSTEM_METRIC", "message": "System metrics collected."}

        self.assertFalse(agent.should_show_issue_notification(normal, runtime, now=1000))
        self.assertFalse(agent.should_show_issue_notification({"eventType": "DEMO_METRIC"}, runtime, now=1000))
        self.assertTrue(agent.should_show_issue_notification(warning, runtime, now=1000))
        self.assertFalse(agent.should_show_issue_notification(warning, runtime, now=1059))
        self.assertTrue(agent.should_show_issue_notification(warning, runtime, now=1060))

    def test_background_loop_reuses_metric_collector_for_delta_metrics(self) -> None:
        runtime = agent.AgentRuntime()
        config = object()
        collectors = []

        def append_once(config_arg: object, index: int, collector: object = None) -> tuple[Path, dict]:
            collectors.append(collector)
            if len(collectors) == 2:
                runtime.stop()
            return Path("agent-metrics.jsonl"), {"eventType": "SYSTEM_METRIC", "message": "System metrics collected."}

        with patch("buildgraph_agent.load_config", return_value=config), \
            patch("buildgraph_agent.append_metric_with_row", side_effect=append_once), \
            patch("buildgraph_agent.should_show_issue_notification", return_value=False), \
            patch("buildgraph_agent.maybe_show_event_panel"):
            agent.collect_background_loop(Path("agent-config.json"), runtime, interval_seconds=0)

        self.assertEqual(len(collectors), 2)
        self.assertIs(collectors[0], collectors[1])
        self.assertIsInstance(collectors[0], agent.HardwareMetricCollector)

    def test_background_metric_row_uses_system_metric_not_demo_metric(self) -> None:
        row = agent.metric_log_row(
            datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST),
            0,
            agent.DEFAULT_SCHEMA_VERSION,
            "agent-1",
            collector=StaticMetricCollector(),
        )

        self.assertEqual(row["kind"], "SYSTEM_METRIC")
        self.assertEqual(row["eventType"], "SYSTEM_METRIC")
        self.assertNotEqual(row["kind"], "DEMO_METRIC")

    def test_issue_macro_maps_display_driver_warning_to_remote_draft(self) -> None:
        macro = agent.issue_macro({"eventType": "DISPLAY_DRIVER_WARNING", "message": "Display driver warning observed."})

        self.assertEqual(macro.symptom_type, "REMOTE_DRIVER_OS")
        self.assertEqual(macro.support_request_kind, "REMOTE_REQUESTED")
        self.assertIn("디스플레이", macro.title)
        self.assertIn("Display driver warning observed.", macro.symptom)

    def test_gzip_recent_selects_recent_rows_and_writes_non_empty_gzip(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "agent-metrics.jsonl"
            out = Path(directory) / "recent-30m.jsonl.gz"
            now = datetime.now(timezone(timedelta(hours=9)))
            rows = [
                {"timestamp": (now - timedelta(minutes=40)).isoformat(), "message": "old"},
                {"timestamp": (now - timedelta(minutes=5)).isoformat(), "message": "recent"},
            ]
            source.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")

            size = agent.gzip_recent(source, out, 30)

            self.assertGreater(size, 0)
            with gzip.open(out, "rt", encoding="utf-8") as file:
                payload = file.read()
            self.assertIn("recent", payload)
            self.assertNotIn("old", payload)

    def test_default_incident_window_uses_symptom_policy(self) -> None:
        detected = datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST)

        remote = agent.default_incident_window("REMOTE_DRIVER_OS", detected_at=detected)
        visit = agent.default_incident_window("VISIT_DISK_FAILURE", detected_at=detected)

        self.assertEqual(remote.range_minutes(), 20)
        self.assertEqual(remote.started_at, detected - timedelta(minutes=15))
        self.assertEqual(remote.ended_at, detected + timedelta(minutes=5))
        self.assertEqual(visit.range_minutes(), 40)
        self.assertEqual(visit.started_at, detected - timedelta(minutes=30))
        self.assertEqual(visit.ended_at, detected + timedelta(minutes=10))

    def test_gzip_window_selects_incident_window_rows(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "agent-metrics.jsonl"
            out = Path(directory) / "incident-window.jsonl.gz"
            detected = datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST)
            window = agent.default_incident_window(
                "REMOTE_DRIVER_OS",
                detected_at=detected,
                incident_id="incident-1",
                consent_id="consent-1",
            )
            rows = [
                {"timestamp": (detected - timedelta(minutes=20)).isoformat(), "message": "before"},
                {"timestamp": (detected - timedelta(minutes=10)).isoformat(), "message": "inside"},
                {"timestamp": (detected + timedelta(minutes=6)).isoformat(), "message": "after"},
            ]
            source.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")

            size = agent.gzip_window(source, out, window)

            self.assertGreater(size, 0)
            with gzip.open(out, "rt", encoding="utf-8") as file:
                payload = file.read()
            self.assertIn("inside", payload)
            self.assertNotIn("before", payload)
            self.assertNotIn("after", payload)

    def test_multipart_contains_agent_upload_fields(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            upload_file = Path(directory) / "recent-30m.jsonl.gz"
            upload_file.write_bytes(b"gzip-bytes")

            body, content_type = agent.build_multipart(
                {"rangeMinutes": "30", "schemaVersion": "1", "symptom": "demo"},
                "file",
                upload_file,
            )

            self.assertIn("multipart/form-data", content_type)
            self.assertIn(b'name="rangeMinutes"', body)
            self.assertIn(b"Content-Type: text/plain; charset=utf-8", body)
            self.assertIn(b"\r\n30\r\n", body)
            self.assertIn(b'name="schemaVersion"', body)
            self.assertIn(b'name="symptom"', body)
            self.assertIn(b'name="file"; filename="recent-30m.jsonl.gz"', body)

    def test_support_url_maps_api_port_to_web_port(self) -> None:
        self.assertEqual(
            agent.support_url("http://localhost:8080", "ticket-1"),
            "http://localhost:5173/support/ticket-1",
        )

    def test_support_url_prefers_configured_web_base_url(self) -> None:
        self.assertEqual(
            agent.support_url("https://api.example.com", "ticket-1", "https://app.example.com"),
            "https://app.example.com/support/ticket-1",
        )

    def test_upload_gzip_parses_ticket_id_from_response(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            upload_file = Path(directory) / "recent-30m.jsonl.gz"
            upload_file.write_bytes(b"gzip-bytes")
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="token",
                log_dir=Path(directory),
                agent_version="test-agent",
                policy_version="test-policy",
            )
            response = MagicMock()
            response.__enter__.return_value.read.return_value = b'{"ticketId":"ticket-public-id"}'
            response.__exit__.return_value = None

            with patch("buildgraph_agent.urllib.request.urlopen", return_value=response) as urlopen:
                result = agent.upload_gzip(config, upload_file, "idem-key", "demo symptom")

            request = urlopen.call_args.args[0]
            self.assertEqual(result["ticketId"], "ticket-public-id")
            self.assertEqual(request.full_url, "http://localhost:8080/api/agent/log-uploads")
            self.assertEqual(request.headers["Authorization"], "Bearer token")
            self.assertEqual(request.headers["Idempotency-key"], "idem-key")

    def test_upload_gzip_sends_incident_window_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            upload_file = Path(directory) / "incident-window.jsonl.gz"
            upload_file.write_bytes(b"gzip-bytes")
            detected = datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST)
            window = agent.default_incident_window(
                "REMOTE_DRIVER_OS",
                detected_at=detected,
                trigger_type="USER_REQUEST",
                incident_id="incident-1",
                consent_id="consent-1",
            )
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="token",
                log_dir=Path(directory),
                agent_version="test-agent",
                policy_version="test-policy",
            )
            response = MagicMock()
            response.__enter__.return_value.read.return_value = b'{"ticketId":"ticket-public-id"}'
            response.__exit__.return_value = None

            with patch("buildgraph_agent.urllib.request.urlopen", return_value=response) as urlopen:
                agent.upload_gzip(config, upload_file, "idem-key", "demo symptom", window)

            body = urlopen.call_args.args[0].data
            self.assertIn(b'name="incidentId"', body)
            self.assertIn(b"\r\nincident-1\r\n", body)
            self.assertIn(b'name="symptomType"', body)
            self.assertIn(b"\r\nREMOTE_DRIVER_OS\r\n", body)
            self.assertIn(b'name="rangeMinutes"', body)
            self.assertIn(b"\r\n20\r\n", body)
            self.assertIn(b'name="consentId"', body)

    def test_create_as_draft_sends_prefill_fields(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            upload_file = Path(directory) / "incident-window.jsonl.gz"
            upload_file.write_bytes(b"gzip-bytes")
            detected = datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST)
            window = agent.default_incident_window(
                "REMOTE_DRIVER_OS",
                detected_at=detected,
                trigger_type="AGENT_DETECTED",
                incident_id="incident-1",
                selected_by_user=False,
                consent_id="consent-1",
            )
            macro = agent.IssueDraftMacro(
                symptom_type="REMOTE_DRIVER_OS",
                title="디스플레이 드라이버 경고가 감지되었습니다",
                detail="PC Agent가 경고를 감지했습니다.",
                symptom="PC Agent 자동 감지: Display driver warning observed.",
                support_request_kind="REMOTE_REQUESTED",
            )
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="token",
                log_dir=Path(directory),
                agent_version="test-agent",
                policy_version="test-policy",
            )
            response = MagicMock()
            response.__enter__.return_value.read.return_value = b'{"draftId":"draft-public-id","logUploadId":"log-id"}'
            response.__exit__.return_value = None

            with patch("buildgraph_agent.urllib.request.urlopen", return_value=response) as urlopen:
                result = agent.create_as_draft(config, upload_file, "draft-key", macro, window)

            request = urlopen.call_args.args[0]
            self.assertEqual(result["draftId"], "draft-public-id")
            self.assertEqual(request.full_url, "http://localhost:8080/api/agent/as-drafts")
            self.assertEqual(request.headers["Authorization"], "Bearer token")
            self.assertEqual(request.headers["Idempotency-key"], "draft-key")
            self.assertIn(b'name="title"', request.data)
            self.assertIn("디스플레이 드라이버".encode("utf-8"), request.data)
            self.assertIn(b'name="supportRequestKind"', request.data)
            self.assertIn(b"\r\nREMOTE_REQUESTED\r\n", request.data)

    def test_preview_as_rag_uses_agent_token_and_incident_window(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            preview_file = Path(directory) / "incident-window.jsonl.gz"
            preview_file.write_bytes(b"gzip-bytes")
            detected = datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST)
            window = agent.default_incident_window(
                "REMOTE_DRIVER_OS",
                detected_at=detected,
                trigger_type="USER_REQUEST",
                incident_id="incident-1",
                consent_id="consent-1",
            )
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="token",
                log_dir=Path(directory),
                agent_version="test-agent",
                policy_version="test-policy",
            )
            response = MagicMock()
            response.__enter__.return_value.read.return_value = b'{"recommendedService":"REMOTE_SUPPORT"}'
            response.__exit__.return_value = None

            with patch("buildgraph_agent.urllib.request.urlopen", return_value=response) as urlopen:
                result = agent.preview_as_rag(config, preview_file, "preview-key", window)

            request = urlopen.call_args.args[0]
            self.assertEqual(result["recommendedService"], "REMOTE_SUPPORT")
            self.assertEqual(request.full_url, "http://localhost:8080/api/agent/log-uploads/as-rag-preview")
            self.assertEqual(request.headers["Authorization"], "Bearer token")
            self.assertEqual(request.headers["Idempotency-key"], "preview-key")
            self.assertIn(b'name="incidentId"', request.data)
            self.assertIn(b"\r\nincident-1\r\n", request.data)
            self.assertIn(b'name="symptomType"', request.data)
            self.assertIn(b"\r\nREMOTE_DRIVER_OS\r\n", request.data)

    def test_preview_as_rag_requires_agent_token(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            preview_file = Path(directory) / "incident-window.jsonl.gz"
            preview_file.write_bytes(b"gzip-bytes")
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                log_dir=Path(directory),
                agent_version="test-agent",
                policy_version="test-policy",
            )
            window = agent.default_incident_window(
                "REMOTE_DRIVER_OS",
                detected_at=datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST),
                trigger_type="USER_REQUEST",
            )

            with self.assertRaises(agent.UploadError):
                agent.preview_as_rag(config, preview_file, "preview-key", window)

    def test_send_diagnosis_chat_posts_json_without_idempotency_key(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="token",
                log_dir=Path(directory),
                agent_version="test-agent",
                policy_version="test-policy",
            )
            response = MagicMock()
            response.__enter__.return_value.read.return_value = b'{"assistantMessage":"check the driver"}'
            response.__exit__.return_value = None

            with patch("buildgraph_agent.urllib.request.urlopen", return_value=response) as urlopen:
                result = agent.send_diagnosis_chat(
                    config,
                    {
                        "id": "diagnosis-1",
                        "summaryText": "Display driver reset repeated",
                        "recommendedService": "REMOTE_SUPPORT",
                        "supportDecision": "REMOTE_POSSIBLE",
                        "confidence": "HIGH",
                    },
                    [{"role": "user", "content": "game crashes"}],
                    "what should I do?",
                )

            request = urlopen.call_args.args[0]
            body = json.loads(request.data.decode("utf-8"))
            self.assertEqual(result["assistantMessage"], "check the driver")
            self.assertEqual(request.full_url, "http://localhost:8080/api/agent/diagnosis-chat")
            self.assertEqual(request.headers["Authorization"], "Bearer token")
            self.assertNotIn("Idempotency-key", request.headers)
            self.assertEqual(body["message"], "what should I do?")
            self.assertEqual(body["diagnosis"]["diagnosisId"], "diagnosis-1")
            self.assertEqual(body["diagnosis"]["recommendedService"], "REMOTE_SUPPORT")
            self.assertEqual(body["messages"][0]["role"], "user")

    def test_format_as_rag_preview_uses_korean_service_label(self) -> None:
        text = agent.format_as_rag_preview(
            {
                "recommendedService": "VISIT_SUPPORT",
                "confidence": "HIGH",
                "recommendationMessage": "방문 점검 가능성이 높습니다.",
                "summaryText": "Kernel-Power 이벤트가 반복되었습니다.",
            }
        )

        self.assertIn("방문지원 신청", text)
        self.assertIn("HIGH", text)
        self.assertIn("Kernel-Power", text)

    def test_diagnosis_history_record_is_compact_and_masks_sensitive_values(self) -> None:
        window = agent.default_incident_window(
            "REMOTE_DRIVER_OS",
            detected_at=datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST),
            trigger_type="USER_REQUEST",
        )

        record = agent.diagnosis_history_record(
            {
                "recommendedService": "REMOTE_SUPPORT",
                "recommendedServiceLabel": "원격지원 신청",
                "supportDecision": "REMOTE_POSSIBLE",
                "confidence": "HIGH",
                "recommendationMessage": "원격지원으로 점검하는 것이 좋습니다. token=secret",
                "summaryText": "드라이버 오류가 반복되었습니다. C:\\Users\\me\\raw.log",
                "supportRouting": {
                    "reasonCodes": ["DRIVER_ERROR_REPEAT"],
                    "remoteActions": ["DRIVER_ROLLBACK"],
                },
                "evidence": [
                    {
                        "title": "드라이버 오류",
                        "summary": "Display driver reset 반복",
                        "reasonCode": "DRIVER_ERROR_REPEAT",
                    }
                ],
            },
            window,
            created_at=datetime(2026, 7, 2, 15, 0, tzinfo=agent.KST),
        )

        serialized = json.dumps(record, ensure_ascii=False).lower()
        self.assertEqual(record["recommendedServiceLabel"], "원격지원 신청")
        self.assertEqual(record["tone"], "warning")
        self.assertIn("DRIVER_ERROR_REPEAT", record["reasonCodes"])
        self.assertNotIn("token=secret", serialized)
        self.assertNotIn("c:\\users", serialized)

    def test_diagnosis_history_round_trip_returns_newest_first(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="raw-agent-token",
                log_dir=Path(directory) / "logs",
                agent_version="test-agent",
                policy_version="test-policy",
            )
            window = agent.default_incident_window(
                "REMOTE_DRIVER_OS",
                detected_at=datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST),
                trigger_type="USER_REQUEST",
            )
            first = agent.diagnosis_history_record(
                {"recommendedService": "DIAGNOSIS_ONLY", "summaryText": "첫 번째 진단"},
                window,
                created_at=datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST),
            )
            second = agent.diagnosis_history_record(
                {"recommendedService": "VISIT_SUPPORT", "summaryText": "두 번째 진단"},
                window,
                created_at=datetime(2026, 7, 2, 15, 0, tzinfo=agent.KST),
            )

            agent.append_diagnosis_history(config, first)
            agent.append_diagnosis_history(config, second)

            rows = agent.read_diagnosis_history(config)
            self.assertEqual(rows[0]["summaryText"], "두 번째 진단")
            self.assertEqual(rows[1]["summaryText"], "첫 번째 진단")
            self.assertIn("최근 진단:", agent.compact_home_diagnosis_text(rows[0]))

    def test_diagnosis_chat_history_is_scoped_to_diagnosis_record(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="raw-agent-token",
                log_dir=Path(directory) / "logs",
                agent_version="test-agent",
                policy_version="test-policy",
            )

            agent.append_diagnosis_chat_message(config, "diagnosis-a", "user", "first question")
            agent.append_diagnosis_chat_message(config, "diagnosis-a", "assistant", "first answer")
            agent.append_diagnosis_chat_message(config, "diagnosis-b", "user", "other question")

            rows = agent.read_diagnosis_chat_history(config, "diagnosis-a")

            self.assertEqual([row["role"] for row in rows], ["user", "assistant"])
            self.assertEqual([row["content"] for row in rows], ["first question", "first answer"])

    def test_compact_home_diagnosis_text_explains_diagnosis_only_reason(self) -> None:
        text = agent.compact_home_diagnosis_text(
            {
                "recommendedService": "DIAGNOSIS_ONLY",
                "recommendedServiceLabel": "우선 진단만 받기",
                "supportDecision": "NEEDS_MORE_INFO",
                "confidence": "LOW",
            }
        )

        self.assertIn("최근 진단: 우선 진단만 받기 / 신뢰도 LOW", text)
        self.assertIn("뚜렷한 장애 신호가 없어", text)
        self.assertIn("원격/방문 판단은 보류", text)

    def test_ensure_default_config_creates_background_config(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-config.json"

            with patch("buildgraph_agent.restrict_file_to_current_user") as restrict:
                created = agent.ensure_default_config(path)
            config = agent.load_config(created)

            self.assertEqual(created, path)
            self.assertEqual(config.api_base_url, "http://localhost:8080")
            self.assertEqual(config.web_base_url, "http://localhost:5173")
            self.assertEqual(config.environment, "local")
            self.assertEqual(config.activation_token, "demo-agent-activation-token")
            self.assertEqual(config.log_dir, Path(directory) / "logs")
            self.assertIsNone(config.agent_token)
            restrict.assert_called_once_with(path)

    def test_save_agent_token_restricts_config_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-config.json"
            path.write_text(
                json.dumps(
                    {
                        "apiBaseUrl": "http://localhost:8080",
                        "activationToken": "activation-token",
                        "deviceFingerprintHash": "fingerprint",
                        "osVersion": "Windows 11",
                        "agentVersion": "test-agent",
                        "policyVersion": "test-policy",
                    }
                ),
                encoding="utf-8",
            )

            with patch("buildgraph_agent.restrict_file_to_current_user") as restrict:
                agent.save_agent_token(path, "agent-token")

            self.assertEqual(agent.load_config(path).agent_token, "agent-token")
            restrict.assert_called_once_with(path)

    def test_gzip_recent_fails_when_recent_window_is_empty(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "agent-metrics.jsonl"
            out = Path(directory) / "recent-30m.jsonl.gz"
            old = datetime.now(timezone(timedelta(hours=9))) - timedelta(hours=2)
            source.write_text(json.dumps({"timestamp": old.isoformat(), "message": "old"}) + "\n", encoding="utf-8")

            with self.assertRaises(agent.AgentError) as error:
                agent.gzip_recent(source, out, 30)

            self.assertIn("no log rows found", str(error.exception))

    def test_read_log_tail_returns_recent_valid_rows(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            rows = [{"message": f"row-{index}"} for index in range(4)]
            path.write_text(
                "\n".join(json.dumps(row) for row in rows[:2])
                + "\nnot-json\n"
                + "\n".join(json.dumps(row) for row in rows[2:])
                + "\n",
                encoding="utf-8",
            )

            tail = agent.read_log_tail(path, 2)

            self.assertEqual([row["message"] for row in tail], ["row-2", "row-3"])

    def test_read_log_tail_returns_empty_when_file_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "missing.jsonl"

            self.assertEqual(agent.read_log_tail(path), [])

    def test_read_log_hour_filters_selected_day_and_hour(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            rows = [
                {"timestamp": "2026-07-02T13:59:59+09:00", "kind": "SYSTEM_METRIC", "message": "before"},
                {"timestamp": "2026-07-02T14:00:00+09:00", "kind": "SYSTEM_METRIC", "message": "start"},
                {"timestamp": "2026-07-02T14:30:00+09:00", "kind": "SYSTEM_METRIC", "message": "middle"},
                {"timestamp": "2026-07-02T15:00:00+09:00", "kind": "SYSTEM_METRIC", "message": "after"},
                {"timestamp": "2026-07-03T14:30:00+09:00", "kind": "SYSTEM_METRIC", "message": "other-day"},
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")

            selected = agent.read_log_hour(path, "2026-07-02", 14)

            self.assertEqual([row["message"] for row in selected], ["middle", "start"])

    def test_log_readers_include_non_system_rows(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            base = datetime.now(agent.KST)
            if base.minute < 10:
                now = (base - timedelta(hours=1)).replace(minute=55, second=0, microsecond=0)
            else:
                now = base.replace(minute=10, second=0, microsecond=0)
            demo_at = now - timedelta(minutes=5)
            system_at = now - timedelta(minutes=4)
            rows = [
                {
                    "timestamp": demo_at.isoformat(),
                    "kind": "DEMO_METRIC",
                    "gpuUsagePercent": 98.0,
                    "vramUsagePercent": 95.0,
                    "gpuTempCelsius": 91.0,
                    "message": "Demo metric collected.",
                },
                {
                    "timestamp": system_at.isoformat(),
                    "kind": "SYSTEM_METRIC",
                    "gpuUsagePercent": 12.0,
                    "message": "System metrics collected.",
                },
                {
                    "timestamp": (system_at + timedelta(seconds=30)).isoformat(),
                    "kind": "AGENT_HEALTH",
                    "message": "Heartbeat accepted.",
                },
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")

            summary = agent.read_status_log_summary_rows(path, 6)
            selected = agent.read_log_hour(path, system_at.strftime("%Y-%m-%d"), system_at.hour)

            self.assertEqual([row["kind"] for row in summary], ["DEMO_METRIC", "SYSTEM_METRIC", "AGENT_HEALTH"])
            self.assertEqual([row["kind"] for row in selected], ["AGENT_HEALTH", "SYSTEM_METRIC", "DEMO_METRIC"])
            self.assertEqual(agent.display_log_summary_values(summary[1])[4], "12.0%")
            self.assertIn("Agent 상태", agent.display_log_summary_values(summary[2]))

    def test_read_log_hour_accepts_envelope_collected_at(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            rows = [
                {"collectedAt": "2026-07-02T04:59:59Z", "kind": "SYSTEM_METRIC", "message": "before"},
                {
                    "collectedAt": "2026-07-02T05:30:00Z",
                    "kind": "SYSTEM_METRIC",
                    "payload": {"cpuUsagePercent": 11.5, "message": "inside"},
                },
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")

            selected = agent.read_log_hour(path, "2026-07-02", 14)

            self.assertEqual(len(selected), 1)
            self.assertEqual(selected[0]["payload"]["message"], "inside")

    def test_read_log_hour_rejects_invalid_date_or_hour(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            path.write_text('{"timestamp":"2026-07-02T14:00:00+09:00"}\n', encoding="utf-8")

            self.assertEqual(agent.read_log_hour(path, "bad-date", 14), [])
            self.assertEqual(agent.read_log_hour(path, "2026-07-02", 24), [])

    def test_default_log_filter_values_use_current_kst_date_and_hour(self) -> None:
        date_text, hour = agent.default_log_filter_values(datetime(2026, 7, 4, 13, 42, tzinfo=agent.KST))

        self.assertEqual(date_text, "2026-07-04")
        self.assertEqual(hour, 13)

    def test_read_log_day_latest_uses_today_logs_latest_first(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            rows = [
                {
                    "timestamp": "2026-07-04T03:00:00+09:00",
                    "kind": "DEMO_METRIC",
                    "gpuUsagePercent": 98.0,
                    "message": "demo",
                },
                {
                    "timestamp": "2026-07-04T11:00:00+09:00",
                    "kind": "SYSTEM_METRIC",
                    "message": "older",
                },
                {
                    "timestamp": "2026-07-03T23:59:00+09:00",
                    "kind": "SYSTEM_METRIC",
                    "message": "yesterday",
                },
                {
                    "timestamp": "2026-07-04T13:10:00+09:00",
                    "kind": "SYSTEM_METRIC",
                    "message": "latest",
                },
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")

            selected = agent.read_log_day_latest(path, "2026-07-04", limit=10)

            self.assertEqual([row["message"] for row in selected], ["latest", "older", "demo"])

    def test_read_log_rows_for_filter_keeps_user_selected_hour(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            rows = [
                {"timestamp": "2026-07-04T13:10:00+09:00", "kind": "SYSTEM_METRIC", "message": "default-latest"},
                {"timestamp": "2026-07-04T11:20:00+09:00", "kind": "SYSTEM_METRIC", "message": "selected-hour"},
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")

            default_rows = agent.read_log_rows_for_filter(path, "2026-07-04", 11, False, limit=10)
            selected_rows = agent.read_log_rows_for_filter(path, "2026-07-04", 11, True, limit=10)

            self.assertEqual([row["message"] for row in default_rows], ["default-latest", "selected-hour"])
            self.assertEqual([row["message"] for row in selected_rows], ["selected-hour"])

    def test_status_log_summary_uses_recent_rows_only(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            now = datetime.now(agent.KST)
            rows = [
                {"timestamp": (now - timedelta(hours=2)).isoformat(), "kind": "SYSTEM_METRIC", "message": "old"},
                {"timestamp": (now - timedelta(minutes=20)).isoformat(), "kind": "SYSTEM_METRIC", "message": "recent-1"},
                {"timestamp": (now - timedelta(minutes=15)).isoformat(), "kind": "AGENT_HEALTH", "message": "recent-health"},
                {"timestamp": (now - timedelta(minutes=10)).isoformat(), "kind": "SYSTEM_METRIC", "message": "recent-2"},
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")

            summary = agent.read_status_log_summary_rows(path, 6)

            self.assertEqual([row["message"] for row in summary], ["recent-1", "recent-health", "recent-2"])

    def test_display_log_summary_values_hide_sensitive_values(self) -> None:
        row = {
            "collectedAt": "2026-07-02T05:30:00Z",
            "kind": "AGENT_HEALTH",
            "payload": {
                "cpuUsagePercent": 20.0,
                "memoryUsedPercent": 40.0,
                "diskUsedPercent": 50.0,
                "processList": ["secret.exe"],
                "message": "upload failed token=secret C:\\Users\\me\\raw.log",
            },
        }

        values = agent.display_log_summary_values(row)
        joined = " ".join(values).lower()

        self.assertEqual(values[4], "-")
        self.assertIn("agent 상태", joined)
        self.assertNotIn("token", joined)
        self.assertNotIn("c:\\users", joined)
        self.assertNotIn("secret.exe", joined)

    def test_display_log_values_use_disk_busy_without_disk_usage_fallback(self) -> None:
        row = {
            "timestamp": "2026-07-02T14:00:00+09:00",
            "kind": "SYSTEM_METRIC",
            "payload": {
                "cpuUsagePercent": 20.0,
                "memoryUsedPercent": 40.0,
                "diskUsedPercent": 87.6,
                "diskBusyEstimatePercent": 12.5,
                "gpuUsagePercent": None,
                "message": "System metrics collected.",
            },
        }
        row_without_busy = {
            "timestamp": "2026-07-02T14:00:00+09:00",
            "kind": "SYSTEM_METRIC",
            "payload": {
                "cpuUsagePercent": 20.0,
                "memoryUsedPercent": 40.0,
                "diskUsedPercent": 87.6,
                "message": "System metrics collected.",
            },
        }

        self.assertEqual(agent.display_log_summary_values(row)[3], "12.5%")
        self.assertEqual(agent.display_log_table_values(row)[4], "12.5%")
        self.assertEqual(agent.display_log_summary_values(row_without_busy)[3], "-")
        self.assertEqual(agent.display_log_table_values(row_without_busy)[4], "-")

    def test_summary_and_table_use_same_core_metric_display_values(self) -> None:
        row = {
            "timestamp": "2026-07-04T13:10:00+09:00",
            "kind": "SYSTEM_METRIC",
            "payload": {
                "cpuUsagePercent": 21.5,
                "memoryUsedPercent": 72.0,
                "diskBusyEstimatePercent": 8.4,
                "diskUsedPercent": 87.6,
                "gpuUsagePercent": 13.2,
                "message": "System metrics collected.",
            },
        }

        summary = agent.display_log_summary_values(row)
        table = agent.display_log_table_values(row)

        self.assertEqual(summary[1:5], (table[2], table[3], table[4], table[5]))
        self.assertEqual(summary[3], "8.4%")
        self.assertEqual(table[4], "8.4%")

    def test_optional_sensor_slots_show_unsupported_status_without_fake_numbers(self) -> None:
        row = {
            "timestamp": "2026-07-04T13:10:00+09:00",
            "kind": "SYSTEM_METRIC",
            "payload": {
                "cpuUsagePercent": 21.5,
                "memoryUsedPercent": 72.0,
                "diskBusyEstimatePercent": 8.4,
                "gpuUsagePercent": 13.2,
                "vramUsagePercent": None,
                "cpuTempCelsius": None,
                "gpuTempCelsius": None,
                "unavailableReason": {
                    "vramUsagePercent": "VRAM utilization unavailable from windows-performance-counter",
                    "cpuTempCelsius": "CPU temperature sensor unavailable",
                    "gpuTempCelsius": "GPU temperature unavailable from windows-performance-counter",
                },
            },
        }

        table = agent.display_log_table_values(row)

        self.assertEqual(table[6], agent.OPTIONAL_SENSOR_UNSUPPORTED_TEXT)
        self.assertEqual(table[7], agent.OPTIONAL_SENSOR_UNSUPPORTED_TEXT)
        self.assertEqual(table[8], agent.OPTIONAL_SENSOR_UNSUPPORTED_TEXT)
        self.assertNotIn("95.0%", table)
        self.assertNotIn("98.0%", table)
        self.assertNotIn("0.0C", table)

    def test_log_filter_initializes_only_before_user_selection(self) -> None:
        self.assertTrue(agent.should_initialize_log_filter(log_tab_opened=False, user_touched_filter=False))
        self.assertFalse(agent.should_initialize_log_filter(log_tab_opened=True, user_touched_filter=False))
        self.assertFalse(agent.should_initialize_log_filter(log_tab_opened=False, user_touched_filter=True))

    def test_detect_recent_signals_uses_final_scenarios_without_simple_usage_noise(self) -> None:
        rows = [
            {
                "timestamp": "2026-07-02T14:00:00+09:00",
                "kind": "SYSTEM_METRIC",
                "cpuUsage": 99.0,
                "memoryUsage": 94.0,
                "message": "High CPU and memory usage only.",
            },
            {
                "timestamp": "2026-07-02T14:05:00+09:00",
                "kind": "DISPLAY_DRIVER_WARNING",
                "message": "Display driver warning observed.",
            },
            {
                "timestamp": "2026-07-02T14:10:00+09:00",
                "kind": "EVENT_LOG",
                "message": "Kernel-Power unexpected shutdown repeated.",
            },
        ]

        signals = agent.detect_recent_signals(rows)

        self.assertEqual([signal["code"] for signal in signals], ["VISIT_POWER_SHUTDOWN", "REMOTE_DRIVER_OS"])

    def test_event_panel_model_uses_only_clear_user_safe_signals(self) -> None:
        detected = datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST)
        signals = [
            {
                "code": "REMOTE_STORAGE_MEMORY",
                "title": "memory pressure token=secret C:\\Users\\me\\raw.log",
                "timestamp": detected,
                "date": "2026-07-02",
                "hour": 14,
            },
            {
                "code": "REMOTE_DRIVER_OS",
                "title": "driver token=secret C:\\Users\\me\\raw.log",
                "timestamp": detected,
                "date": "2026-07-02",
                "hour": 14,
            },
            {
                "code": "VISIT_WHEA_BSOD",
                "title": "WHEA",
                "timestamp": detected + timedelta(minutes=1),
                "date": "2026-07-02",
                "hour": 14,
            },
        ]

        model = agent.event_panel_model(signals)

        self.assertIsNotNone(model)
        assert model is not None
        joined = " ".join(model["summaries"]).lower()
        self.assertIn("드라이버", joined)
        self.assertIn("블루스크린", joined)
        self.assertEqual(model["detectedTime"], "2026-07-02 14:00")
        self.assertIn("드라이버", model["signalTitle"])
        self.assertEqual(model["windowText"], "13:45 ~ 14:05 (20분)")
        self.assertNotIn("memory pressure", joined)
        self.assertNotIn("token", joined)
        self.assertNotIn("c:\\users", joined)

    def test_event_panel_failure_message_hides_raw_error_detail(self) -> None:
        message = agent.event_panel_failure_message(
            agent.UploadError("upload failed: HTTP 400 token=secret C:\\Users\\me\\raw.log consentAccepted=false")
        )

        self.assertIn("동의", message)
        self.assertNotIn("token", message.lower())
        self.assertNotIn("c:\\users", message.lower())

    def test_registration_failure_messages_guide_web_redownload(self) -> None:
        error = agent.UploadError(
            "AS RAG preview failed: HTTP 401 "
            '{"code":"UNAUTHORIZED","message":"Agent activation token is invalid."} '
            "token=secret C:\\Users\\me\\raw.log"
        )

        preview_message = agent.as_rag_preview_failure_message(error)
        compact_message = agent.compact_as_rag_preview_failure_message(error)

        self.assertIn("다시 다운로드", preview_message)
        self.assertIn("등록 토큰", preview_message)
        self.assertIn("PCAgent.exe", compact_message)
        self.assertIn("pcagent-activation.json", compact_message)
        self.assertNotIn("secret", preview_message.lower())
        self.assertNotIn("c:\\users", preview_message.lower())
        self.assertNotIn("invalid", preview_message.lower())

    def test_upload_event_panel_request_uses_existing_incident_upload_flow(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log_dir = root / "logs"
            log_dir.mkdir()
            source = log_dir / "agent-metrics.jsonl"
            detected = datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST)
            source.write_text(
                json.dumps(
                    {
                        "timestamp": detected.isoformat(),
                        "kind": "DISPLAY_DRIVER_WARNING",
                        "message": "Display driver warning observed.",
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="agent-token",
                log_dir=log_dir,
                agent_version="test-agent",
                policy_version="test-policy",
                web_base_url="http://localhost:5173",
            )
            signals = [
                {
                    "code": "REMOTE_DRIVER_OS",
                    "title": "드라이버/OS 오류",
                    "timestamp": detected,
                    "date": "2026-07-02",
                    "hour": 14,
                }
            ]

            with patch("buildgraph_agent.upload_gzip", return_value={"ticketId": "ticket-public-id"}) as upload:
                ticket_id, url = agent.upload_event_panel_request(config, source, signals, "방문 접수")

            self.assertEqual(ticket_id, "ticket-public-id")
            self.assertEqual(url, "http://localhost:5173/support/ticket-public-id")
            upload_args = upload.call_args.args
            self.assertEqual(upload_args[0], config)
            self.assertTrue(upload_args[1].name.endswith(".jsonl.gz"))
            self.assertTrue(upload_args[2].startswith("agent-panel-"))
            self.assertIn("방문 접수", upload_args[3])
            self.assertIn("드라이버", upload_args[3])
            self.assertEqual(upload_args[4].trigger_type, "SYSTEM_DETECTED")
            self.assertEqual(upload_args[4].symptom_type, "REMOTE_DRIVER_OS")
            self.assertTrue(upload_args[4].selected_by_user)

    def test_display_log_table_values_hide_sensitive_values(self) -> None:
        row = {
            "timestamp": "2026-07-02T14:00:00+09:00",
            "kind": "AGENT_HEALTH",
            "payload": {
                "cpuUsagePercent": 20.0,
                "processList": ["secret.exe", "other.exe"],
                "message": "upload failed token=secret C:\\Users\\me\\raw.log",
            },
        }

        values = agent.display_log_table_values(row)
        joined = " ".join(values).lower()

        self.assertIn("agent_health", joined)
        self.assertIn("[hidden]", joined)
        self.assertIn("[path hidden]", joined)
        self.assertNotIn("secret.exe", joined)
        self.assertNotIn("c:\\users\\me", joined)

    def test_status_home_model_does_not_expose_agent_token(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            path.write_text("", encoding="utf-8")
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="raw-agent-token",
                log_dir=Path(directory),
                agent_version="test-agent",
                policy_version="test-policy",
            )

            model = agent.status_home_model(config, path)

            self.assertEqual(model["agentStatus"], "정상 실행 중")
            self.assertNotIn("raw-agent-token", json.dumps(model, ensure_ascii=False))

    def test_status_home_model_builds_dynamic_status_cards(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            now = datetime.now(agent.KST)
            rows = [
                {"timestamp": (now - timedelta(minutes=2)).isoformat(), "message": "heartbeat success"},
                {"timestamp": (now - timedelta(minutes=1)).isoformat(), "message": "upload success"},
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
            startup_path = Path(directory) / f"{agent.APP_NAME}.cmd"
            startup_path.write_text("@echo off\n", encoding="utf-8")
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="raw-agent-token",
                log_dir=Path(directory),
                agent_version="1.2.3",
                policy_version="test-policy",
            )

            with patch("buildgraph_agent.startup_dir", return_value=Path(directory)):
                model = agent.status_home_model(config, path)

            self.assertEqual(model["pcStatusCard"]["value"], "정상")
            self.assertEqual(model["pcStatusCard"]["tone"], "ok")
            self.assertEqual(model["uploadCard"]["detail"], "업로드 상태: 성공")
            self.assertEqual(model["uploadCard"]["tone"], "ok")
            self.assertEqual(model["startupCard"]["value"], "사용 중")
            self.assertEqual(model["startupCard"]["tone"], "ok")
            self.assertEqual(model["versionCard"]["value"], "1.2.3")
            self.assertEqual(model["versionCard"]["detail"], "최신 버전")

    def test_register_startup_uses_stable_localappdata_executable_for_frozen_build(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "Downloads" / "PCAgent.exe"
            source.parent.mkdir(parents=True)
            source.write_bytes(b"pca-agent-exe")
            localappdata = root / "LocalAppData"
            appdata = root / "AppData" / "Roaming"

            with patch.dict("os.environ", {"LOCALAPPDATA": str(localappdata), "APPDATA": str(appdata)}), \
                    patch.object(agent.sys, "frozen", True, create=True), \
                    patch.object(agent.sys, "executable", str(source)):
                startup_path = agent.register_startup()

            installed = localappdata / agent.DATA_APP_NAME / f"{agent.APP_NAME}.exe"
            self.assertTrue(installed.exists())
            self.assertEqual(installed.read_bytes(), b"pca-agent-exe")
            self.assertEqual(startup_path.name, f"{agent.APP_NAME}.cmd")
            self.assertIn(f'"{installed}" run-background', startup_path.read_text(encoding="utf-8"))

    def test_register_url_protocol_launches_without_uri_identity_arguments(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "PCAgent.exe"
            with patch("buildgraph_agent.os.name", "nt"), \
                    patch.object(agent.sys, "frozen", True, create=True), \
                    patch("buildgraph_agent.ensure_installed_executable", return_value=executable), \
                    patch("winreg.CreateKey") as create_key, \
                    patch("winreg.SetValueEx") as set_value:
                registered = agent.register_url_protocol()

            self.assertTrue(registered)
            created_paths = [call.args[1] for call in create_key.call_args_list]
            self.assertEqual(created_paths, [
                rf"Software\Classes\{agent.PC_AGENT_URL_PROTOCOL}",
                rf"Software\Classes\{agent.PC_AGENT_URL_PROTOCOL}\DefaultIcon",
                rf"Software\Classes\{agent.PC_AGENT_URL_PROTOCOL}\shell\open\command",
            ])
            command = set_value.call_args_list[-1].args[-1]
            self.assertEqual(command, f'"{executable}"')
            self.assertNotIn("%1", command)

    def test_ensure_installed_executable_skips_copy_when_content_matches(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "Downloads" / "PCAgent.exe"
            target = root / "LocalAppData" / agent.DATA_APP_NAME / f"{agent.APP_NAME}.exe"
            source.parent.mkdir(parents=True)
            target.parent.mkdir(parents=True)
            source.write_bytes(b"pca-agent-exe")
            target.write_bytes(b"pca-agent-exe")
            os.utime(source, (200, 200))
            os.utime(target, (100, 100))

            with patch.dict("os.environ", {"LOCALAPPDATA": str(root / "LocalAppData")}), \
                    patch.object(agent.sys, "frozen", True, create=True), \
                    patch.object(agent.sys, "executable", str(source)), \
                    patch("buildgraph_agent.shutil.copy2") as copy2:
                installed = agent.ensure_installed_executable()

            self.assertEqual(installed, target)
            copy2.assert_not_called()

    def test_ensure_installed_executable_reports_locked_target_permission_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "Downloads" / "PCAgent.exe"
            target = root / "LocalAppData" / agent.DATA_APP_NAME / f"{agent.APP_NAME}.exe"
            source.parent.mkdir(parents=True)
            target.parent.mkdir(parents=True)
            source.write_bytes(b"new-pca-agent-exe")
            target.write_bytes(b"old-pca-agent-exe")
            permission_error = PermissionError(13, "Permission denied", str(target))

            with patch.dict("os.environ", {"LOCALAPPDATA": str(root / "LocalAppData")}), \
                    patch.object(agent.sys, "frozen", True, create=True), \
                    patch.object(agent.sys, "executable", str(source)), \
                    patch("buildgraph_agent.shutil.copy2", side_effect=permission_error):
                with self.assertRaises(agent.AgentError) as context:
                    agent.ensure_installed_executable()

            self.assertIn("PCAgent.exe", str(context.exception))
            self.assertIn("Permission denied", str(context.exception))

    def test_register_startup_removes_legacy_startup_commands(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            appdata = root / "AppData" / "Roaming"
            startup = appdata / "Microsoft" / "Windows" / "Start Menu" / "Programs" / "Startup"
            startup.mkdir(parents=True)
            legacy = startup / "BuildGraphAgent.cmd"
            legacy.write_text('@echo off\nstart "" "C:\\Users\\me\\Downloads\\BuildGraphAgent-token.exe" run-background\n', encoding="utf-8")
            spaced_legacy = startup / "PC Agent.cmd"
            spaced_legacy.write_text("@echo off\n", encoding="utf-8")
            unrelated = startup / "Unrelated.cmd"
            unrelated.write_text("@echo off\n", encoding="utf-8")

            with patch.dict("os.environ", {"APPDATA": str(appdata)}):
                startup_path = agent.register_startup()

            self.assertEqual(startup_path, startup / "PCAgent.cmd")
            self.assertFalse(legacy.exists())
            self.assertFalse(spaced_legacy.exists())
            self.assertTrue(unrelated.exists())
            self.assertTrue(startup_path.exists())

    def test_compare_versions_uses_numeric_parts(self) -> None:
        self.assertGreater(agent.compare_versions("0.10.0", "0.2.0"), 0)
        self.assertEqual(agent.compare_versions("1.0.0", "1.0"), 0)
        self.assertLess(agent.compare_versions("1.0.0", "1.0.1"), 0)

    def test_parse_update_manifest_resolves_relative_download_url(self) -> None:
        info = agent.parse_agent_update_manifest(
            {
                "version": "0.2.0",
                "downloadUrl": "agent.exe",
                "sha256": "a" * 64,
                "fileName": "PCAgent.exe",
            },
            "http://localhost:5173/downloads/pc-agent/latest.json",
        )

        self.assertEqual(info.version, "0.2.0")
        self.assertEqual(info.download_url, "http://localhost:5173/downloads/pc-agent/agent.exe")
        self.assertEqual(info.sha256, "a" * 64)

    def test_prepare_agent_update_stages_verified_exe_for_frozen_build(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            localappdata = root / "LocalAppData"
            payload = b"new-pcagent-exe"
            digest = agent.hashlib.sha256(payload).hexdigest()
            calls: list[str] = []

            class Response:
                def __init__(self, body: bytes) -> None:
                    self.body = body

                def __enter__(self) -> "Response":
                    return self

                def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
                    return None

                def read(self) -> bytes:
                    return self.body

            def opener(request: object, timeout: int = 0) -> Response:
                url = request.full_url
                calls.append(url)
                if url.endswith("latest.json"):
                    return Response(
                        json.dumps(
                            {
                                "version": "0.2.0",
                                "downloadUrl": "agent.exe",
                                "sha256": digest,
                            }
                        ).encode("utf-8")
                    )
                return Response(payload)

            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="raw-agent-token",
                log_dir=root / "logs",
                agent_version="0.1.0",
                policy_version="test-policy",
                web_base_url="http://localhost:5173",
            )

            with patch.dict("os.environ", {"LOCALAPPDATA": str(localappdata)}), \
                    patch.object(agent.sys, "frozen", True, create=True):
                result = agent.prepare_agent_update(config, opener=opener)

            staged = Path(str(result["stagedPath"]))
            script = Path(str(result["scriptPath"]))
            self.assertEqual(result["status"], "READY")
            self.assertEqual(calls, [
                "http://localhost:5173/downloads/pc-agent/latest.json",
                "http://localhost:5173/downloads/pc-agent/agent.exe",
            ])
            self.assertEqual(staged.read_bytes(), payload)
            script_text = script.read_text(encoding="utf-8")
            self.assertIn("PCAgent.exe", script_text)
            self.assertIn("taskkill /PID", script_text)
            self.assertIn("Get-CimInstance Win32_Process", script_text)
            self.assertIn("Name -eq 'PCAgent.exe'", script_text)
            self.assertIn("ExecutablePath", script_text)
            self.assertIn("agentVersion", script_text)
            self.assertIn("0.2.0", script_text)

    def test_prepare_agent_update_rejects_bad_sha256(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = b"tampered"

            class Response:
                def __init__(self, body: bytes) -> None:
                    self.body = body

                def __enter__(self) -> "Response":
                    return self

                def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
                    return None

                def read(self) -> bytes:
                    return self.body

            def opener(request: object, timeout: int = 0) -> Response:
                if request.full_url.endswith("latest.json"):
                    return Response(
                        json.dumps(
                            {
                                "version": "0.2.0",
                                "downloadUrl": "agent.exe",
                                "sha256": "b" * 64,
                            }
                        ).encode("utf-8")
                    )
                return Response(payload)

            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="raw-agent-token",
                log_dir=root / "logs",
                agent_version="0.1.0",
                policy_version="test-policy",
                web_base_url="http://localhost:5173",
            )

            with patch.dict("os.environ", {"LOCALAPPDATA": str(root / "LocalAppData")}), \
                    patch.object(agent.sys, "frozen", True, create=True):
                with self.assertRaisesRegex(agent.AgentError, "sha256"):
                    agent.prepare_agent_update(config, opener=opener)

    def test_status_home_model_marks_recent_memory_pressure_as_warning(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            now = datetime.now(agent.KST)
            rows = [
                {
                    "timestamp": (now - timedelta(minutes=4)).isoformat(),
                    "kind": "SYSTEM_METRIC",
                    "memoryUsedPercent": 88.2,
                    "message": "System metrics collected.",
                }
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="raw-agent-token",
                log_dir=Path(directory),
                agent_version="1.2.3",
                policy_version="test-policy",
            )

            model = agent.status_home_model(config, path)

            self.assertEqual(model["pcStatusCard"]["value"], "주의")
            self.assertEqual(model["pcStatusCard"]["detail"], "이상 징후: 메모리 사용량 높음")
            self.assertEqual(model["pcStatusCard"]["tone"], "warning")
            self.assertEqual(model["homeDetection"]["title"], "메모리 사용량 높음")

    def test_status_home_model_marks_danger_signal_as_check_required(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            now = datetime.now(agent.KST)
            rows = [
                {
                    "timestamp": (now - timedelta(minutes=1)).isoformat(),
                    "kind": "EVENT_LOG",
                    "message": "Kernel-Power unexpected shutdown repeated.",
                }
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="raw-agent-token",
                log_dir=Path(directory),
                agent_version="1.2.3",
                policy_version="test-policy",
            )

            model = agent.status_home_model(config, path)

            self.assertEqual(model["pcStatusCard"]["value"], "점검 필요")
            self.assertEqual(model["pcStatusCard"]["detail"], "진단 또는 AS 접수가 필요합니다.")
            self.assertEqual(model["pcStatusCard"]["tone"], "danger")

    def test_diagnosis_detail_model_empty_state_without_logs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="raw-agent-token",
                log_dir=Path(directory),
                agent_version="1.2.3",
                policy_version="test-policy",
            )

            with patch("buildgraph_agent.startup_dir", return_value=Path(directory)):
                model = agent.diagnosis_detail_model(config, path)

            self.assertFalse(model["hasResult"])
            self.assertEqual(model["status"], "확인 전")
            self.assertEqual(model["summary"], "아직 진단 결과가 없습니다.")
            self.assertEqual(model["events"], [])
            self.assertTrue(all(metric["status"] == "확인 불가" for metric in model["metrics"]))

    def test_diagnosis_detail_model_builds_metric_cards_from_recent_log(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            now = datetime.now(agent.KST)
            path.write_text(
                json.dumps(
                    {
                        "timestamp": now.isoformat(),
                        "kind": "SYSTEM_METRIC",
                        "cpuUsagePercent": 32.0,
                        "memoryUsedPercent": 91.0,
                        "diskBusyEstimatePercent": 0.0,
                        "gpuUsagePercent": None,
                        "message": "System metrics collected.",
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="raw-agent-token",
                log_dir=Path(directory),
                agent_version="1.2.3",
                policy_version="test-policy",
            )

            with patch("buildgraph_agent.startup_dir", return_value=Path(directory)):
                model = agent.diagnosis_detail_model(config, path)

            metrics = {metric["name"]: metric for metric in model["metrics"]}
            self.assertTrue(model["hasResult"])
            self.assertNotEqual(model["lastDiagnosticTime"], "-")
            self.assertEqual(metrics["CPU"]["currentValue"], "32.0%")
            self.assertEqual(metrics["CPU"]["status"], "정상")
            self.assertEqual(metrics["메모리"]["currentValue"], "91.0%")
            self.assertEqual(metrics["메모리"]["status"], "주의")
            self.assertEqual(metrics["디스크"]["currentValue"], "0.0%")
            self.assertEqual(metrics["디스크"]["status"], "정상")
            self.assertEqual(metrics["GPU"]["status"], "확인 불가")
            self.assertEqual(len(model["events"]), 1)

    def test_diagnosis_detail_model_includes_non_metric_event_logs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            now = datetime.now(agent.KST)
            rows = [
                {
                    "timestamp": now.isoformat(),
                    "kind": "SYSTEM_METRIC",
                    "cpuUsagePercent": 24.0,
                    "message": "System metrics collected.",
                },
                {
                    "timestamp": (now + timedelta(seconds=10)).isoformat(),
                    "kind": "AGENT_HEALTH",
                    "message": "Heartbeat accepted.",
                },
                {
                    "timestamp": (now + timedelta(seconds=20)).isoformat(),
                    "kind": "WINDOWS_EVENT",
                    "message": "Display driver warning repeated.",
                },
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="raw-agent-token",
                log_dir=Path(directory),
                agent_version="1.2.3",
                policy_version="test-policy",
            )

            with patch("buildgraph_agent.startup_dir", return_value=Path(directory)):
                model = agent.diagnosis_detail_model(config, path)

            event_types = [event["type"] for event in model["events"]]
            event_contents = [event["content"] for event in model["events"]]
            self.assertIn("Agent 상태", event_types)
            self.assertIn("시스템 이벤트", event_types)
            self.assertIn("Heartbeat accepted.", event_contents)
            self.assertIn("Display driver warning repeated.", event_contents)

    def test_diagnosis_detail_model_has_no_collection_upload_or_server_side_effects(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            path.write_text(
                json.dumps(
                    {
                        "timestamp": datetime.now(agent.KST).isoformat(),
                        "kind": "SYSTEM_METRIC",
                        "cpuUsagePercent": 10.0,
                        "message": "System metrics collected.",
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="raw-agent-token",
                log_dir=Path(directory),
                agent_version="1.2.3",
                policy_version="test-policy",
            )

            with (
                patch("buildgraph_agent.startup_dir", return_value=Path(directory)),
                patch("buildgraph_agent.urllib.request.urlopen") as urlopen,
                patch("buildgraph_agent.upload_gzip") as upload,
                patch("buildgraph_agent.preview_as_rag") as preview,
                patch("buildgraph_agent.append_metric") as append_metric,
                patch("buildgraph_agent.gzip_window") as gzip_window,
            ):
                agent.diagnosis_detail_model(config, path)

            urlopen.assert_not_called()
            upload.assert_not_called()
            preview.assert_not_called()
            append_metric.assert_not_called()
            gzip_window.assert_not_called()

    def test_diagnosis_detail_model_hides_sensitive_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            path.write_text(
                json.dumps(
                    {
                        "timestamp": datetime.now(agent.KST).isoformat(),
                        "kind": "AGENT_HEALTH",
                        "payload": {
                            "message": "upload failed token=secret C:\\Users\\me\\raw.log",
                            "processList": ["secret.exe"],
                        },
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="raw-agent-token",
                log_dir=Path(directory),
                agent_version="1.2.3",
                policy_version="test-policy",
            )

            with patch("buildgraph_agent.startup_dir", return_value=Path(directory)):
                model = agent.diagnosis_detail_model(config, path)

            serialized = json.dumps(model, ensure_ascii=False).lower()
            self.assertNotIn("raw-agent-token", serialized)
            self.assertNotIn("token=secret", serialized)
            self.assertNotIn("c:\\users", serialized)
            self.assertNotIn("secret.exe", serialized)

    def test_diagnosis_detail_model_does_not_fallback_disk_busy_to_capacity_usage(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            path.write_text(
                json.dumps(
                    {
                        "timestamp": datetime.now(agent.KST).isoformat(),
                        "kind": "SYSTEM_METRIC",
                        "cpuUsagePercent": 20.0,
                        "memoryUsedPercent": 40.0,
                        "diskUsedPercent": 87.6,
                        "message": "System metrics collected.",
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="raw-agent-token",
                log_dir=Path(directory),
                agent_version="1.2.3",
                policy_version="test-policy",
            )

            with patch("buildgraph_agent.startup_dir", return_value=Path(directory)):
                model = agent.diagnosis_detail_model(config, path)

            metrics = {metric["name"]: metric for metric in model["metrics"]}
            self.assertEqual(metrics["디스크"]["currentValue"], "-")
            self.assertEqual(metrics["디스크"]["status"], "확인 불가")

    def test_powershell_string_escapes_single_quotes(self) -> None:
        self.assertEqual(agent.powershell_string("C:\\Users\\O'Brien"), "'C:\\Users\\O''Brien'")

    def test_run_background_is_available_as_cli_command(self) -> None:
        with patch("buildgraph_agent.run_background", return_value=0) as run_background:
            exit_code = agent.main(["run-background", "--interval-seconds", "1", "--no-tray"])

        self.assertEqual(exit_code, 0)
        self.assertEqual(run_background.call_args.args[1], 1)
        self.assertFalse(run_background.call_args.args[2])
        self.assertNotIn("open_viewer_when_running", run_background.call_args.kwargs)

    def test_no_arg_launch_opens_viewer_when_background_is_already_running(self) -> None:
        with patch("buildgraph_agent.run_background", return_value=0) as run_background:
            exit_code = agent.main([])

        self.assertEqual(exit_code, 0)
        run_background.assert_called_once_with(open_viewer_when_running=True)

    def test_no_arg_launch_reports_agent_error_without_unhandled_exception(self) -> None:
        with patch("buildgraph_agent.run_background", side_effect=agent.AgentError("locked target")), \
                patch("buildgraph_agent.show_agent_error_dialog") as show_error:
            exit_code = agent.main([])

        self.assertEqual(exit_code, 4)
        show_error.assert_called_once()
        self.assertEqual(show_error.call_args.args[1], "locked target")

    def test_run_background_exits_when_instance_lock_exists(self) -> None:
        with patch("buildgraph_agent.acquire_named_instance_lock", return_value=None) as acquire_lock, \
            patch("buildgraph_agent.ensure_default_config") as ensure_default_config, \
            patch("buildgraph_agent.show_log_viewer") as show_log_viewer:
            exit_code = agent.run_background(Path("agent-config.json"), with_tray=False)

        self.assertEqual(exit_code, 0)
        acquire_lock.assert_called_once_with(agent.BACKGROUND_INSTANCE_MUTEX_NAME)
        ensure_default_config.assert_not_called()
        show_log_viewer.assert_not_called()

    def test_run_background_warns_when_an_older_agent_is_already_running(self) -> None:
        with patch("buildgraph_agent.acquire_named_instance_lock", return_value=None), \
            patch("buildgraph_agent.running_agent_version", return_value="0.1.14"), \
            patch("buildgraph_agent.show_agent_error_dialog") as show_dialog:
            exit_code = agent.run_background(Path("agent-config.json"), with_tray=False)

        self.assertEqual(exit_code, 0)
        show_dialog.assert_called_once()
        self.assertIn("0.1.14", show_dialog.call_args.args[1])
        self.assertIn(agent.DEFAULT_AGENT_VERSION, show_dialog.call_args.args[1])

    def test_run_background_stays_quiet_when_the_running_agent_is_the_same_version(self) -> None:
        with patch("buildgraph_agent.acquire_named_instance_lock", return_value=None), \
            patch("buildgraph_agent.running_agent_version", return_value=agent.DEFAULT_AGENT_VERSION), \
            patch("buildgraph_agent.show_agent_error_dialog") as show_dialog:
            exit_code = agent.run_background(Path("agent-config.json"), with_tray=False)

        self.assertEqual(exit_code, 0)
        show_dialog.assert_not_called()

    def test_run_background_signals_existing_agent_for_user_launch_when_instance_lock_exists(self) -> None:
        config_path = Path("agent-config.json")
        with patch("buildgraph_agent.acquire_named_instance_lock", return_value=None) as acquire_lock, \
            patch("buildgraph_agent.ensure_default_config", return_value=config_path) as ensure_default_config, \
            patch("buildgraph_agent.ViewerRequestSignal") as viewer_request_signal, \
            patch("buildgraph_agent.show_log_viewer") as show_log_viewer:
            exit_code = agent.run_background(config_path, with_tray=False, open_viewer_when_running=True)

        self.assertEqual(exit_code, 0)
        acquire_lock.assert_called_once_with(agent.BACKGROUND_INSTANCE_MUTEX_NAME)
        ensure_default_config.assert_not_called()
        viewer_request_signal.return_value.signal.assert_called_once_with(reconnect=True)
        show_log_viewer.assert_not_called()

    def test_run_background_releases_instance_lock_after_shutdown(self) -> None:
        fake_lock = MagicMock()
        runtime = SimpleNamespace(running=False, stop=MagicMock())

        class FakeThread:
            def __init__(self, target: object, args: tuple, daemon: bool) -> None:
                self.target = target
                self.args = args
                self.daemon = daemon

            def start(self) -> None:
                self.target(*self.args)

        with patch("buildgraph_agent.acquire_named_instance_lock", return_value=fake_lock), \
            patch("buildgraph_agent.ensure_default_config", return_value=Path("agent-config.json")), \
            patch("buildgraph_agent.import_activation_config"), \
            patch("buildgraph_agent.auto_register_agent"), \
            patch("buildgraph_agent.register_startup"), \
            patch("buildgraph_agent.hide_console_window"), \
            patch("buildgraph_agent.write_pid"), \
            patch("buildgraph_agent.remove_pid"), \
            patch("buildgraph_agent.AgentRuntime", return_value=runtime), \
            patch("buildgraph_agent.collect_background_loop"), \
            patch("threading.Thread", FakeThread):
            exit_code = agent.run_background(Path("agent-config.json"), interval_seconds=0, with_tray=False)

        self.assertEqual(exit_code, 0)
        runtime.stop.assert_called_once()
        fake_lock.release.assert_called_once()

    def test_cli_status_does_not_acquire_background_instance_lock(self) -> None:
        with patch("buildgraph_agent.print_status") as print_status, \
            patch("buildgraph_agent.acquire_named_instance_lock") as acquire_lock:
            exit_code = agent.main(["status", "--config", "agent-config.json"])

        self.assertEqual(exit_code, 0)
        print_status.assert_called_once()
        acquire_lock.assert_not_called()

    def test_show_log_viewer_exits_when_viewer_lock_exists(self) -> None:
        with patch("buildgraph_agent.tk", object()), \
            patch("buildgraph_agent.ttk", object()), \
            patch("buildgraph_agent.acquire_named_instance_lock", return_value=None) as acquire_lock, \
            patch("buildgraph_agent.load_config") as load_config:
            agent.show_log_viewer(Path("agent-config.json"))

        acquire_lock.assert_called_once_with(agent.VIEWER_INSTANCE_MUTEX_NAME)
        load_config.assert_not_called()

    def test_pc_agent_ui_flow_has_three_diagnosis_states(self) -> None:
        state = "SYMPTOM_CONFIRM"
        visited = [state]
        for _ in range(2):
            state = agent.next_pc_agent_ui_state(state)
            visited.append(state)

        self.assertEqual(
            visited,
            ["SYMPTOM_CONFIRM", "DIAGNOSING", "DIAGNOSIS_RESULT"],
        )
        self.assertEqual(agent.next_pc_agent_ui_state(state), "DIAGNOSIS_RESULT")

    def test_pc_agent_window_uses_legacy_dimensions_and_three_steps(self) -> None:
        self.assertEqual((agent.PC_AGENT_WINDOW_WIDTH, agent.PC_AGENT_WINDOW_HEIGHT), (1000, 740))
        self.assertEqual(
            agent.PC_AGENT_DIAGNOSIS_STEPS,
            ("증상 확인", "하드웨어 진단", "결과 및 조치"),
        )

    def test_specup_agent_icon_assets_are_available(self) -> None:
        self.assertTrue(agent.app_asset_path(agent.AGENT_ICON_PNG).exists())
        self.assertTrue(agent.app_asset_path(agent.AGENT_ICON_ICO).exists())
        tray_image = agent.create_tray_image()
        if agent.Image is not None:
            self.assertIsNotNone(tray_image)
            self.assertEqual(tray_image.size, (64, 64))

    def test_screen_logo_asset_is_transparent_and_drawn_without_moving_layout(self) -> None:
        self.assertIsNotNone(agent.Image)
        self.assertNotEqual(agent.SCREEN_LOGO_PNG, agent.AGENT_ICON_PNG)
        logo_path = agent.app_asset_path(agent.SCREEN_LOGO_PNG)
        self.assertTrue(logo_path.exists())
        with agent.Image.open(logo_path) as logo:
            self.assertEqual(logo.mode, "RGBA")
            self.assertEqual(logo.size, (1254, 1254))
            self.assertEqual(0, logo.getchannel("A").getpixel((0, 0)))
            self.assertIsNotNone(logo.getchannel("A").getbbox())

        rendered = agent.render_screen_logo_image()
        self.assertEqual(rendered.mode, "RGBA")
        self.assertEqual(rendered.size, agent.SCREEN_LOGO_DISPLAY_SIZE)
        self.assertIsNotNone(rendered.getchannel("A").getbbox())

        source = inspect.getsource(agent.show_log_viewer)
        self.assertIn("draw_screen_logo()", source)
        self.assertIn('canvas.create_image(*SCREEN_LOGO_POSITION, image=photo, anchor="center")', source)
        self.assertIn('canvas.move("all", 0, -PC_AGENT_REMOVED_HEADER_HEIGHT)', source)

        build_script = Path(agent.__file__).with_name("build-agent-exe.ps1").read_text(encoding="utf-8")
        self.assertIn('$Args += "$Assets;assets"', build_script)


class FakePulseWidget:
    """StatusPulse가 쓰는 위젯 표면(after/after_cancel/winfo_exists)만 흉내낸다."""

    def __init__(self) -> None:
        self.exists = 1
        self.pending: dict[int, Any] = {}
        self._next_id = 0

    def winfo_exists(self) -> int:
        return self.exists

    def after(self, _interval_ms: int, callback: Any) -> int:
        self._next_id += 1
        self.pending[self._next_id] = callback
        return self._next_id

    def after_cancel(self, job_id: int) -> None:
        self.pending.pop(job_id, None)

    def fire_next(self) -> None:
        job_id, callback = next(iter(self.pending.items()))
        del self.pending[job_id]
        callback()


class StatusPulseTest(unittest.TestCase):
    def test_frame_cycles_zero_to_three_dots(self) -> None:
        frames = [agent.status_pulse_frame("업로드 중", step) for step in range(5)]
        self.assertEqual(
            frames,
            ["업로드 중", "업로드 중.", "업로드 중..", "업로드 중...", "업로드 중"],
        )

    def test_start_animates_and_stop_sets_final_text(self) -> None:
        widget = FakePulseWidget()
        values: list[str] = []
        pulse = agent.StatusPulse(widget, values.append)
        pulse.start("서버 확인")
        self.assertTrue(pulse.active)
        self.assertEqual(values[-1], "서버 확인")
        widget.fire_next()
        self.assertEqual(values[-1], "서버 확인.")
        pulse.stop("완료되었습니다")
        self.assertFalse(pulse.active)
        self.assertEqual(values[-1], "완료되었습니다")
        self.assertEqual(widget.pending, {})

    def test_restart_cancels_previous_job(self) -> None:
        widget = FakePulseWidget()
        values: list[str] = []
        pulse = agent.StatusPulse(widget, values.append)
        pulse.start("첫 작업")
        pulse.start("둘째 작업")
        self.assertEqual(len(widget.pending), 1)
        self.assertEqual(values[-1], "둘째 작업")

    def test_destroyed_widget_stops_silently(self) -> None:
        widget = FakePulseWidget()
        values: list[str] = []
        pulse = agent.StatusPulse(widget, values.append)
        pulse.start("작업 중")
        widget.exists = 0
        widget.fire_next()
        self.assertFalse(pulse.active)
        self.assertEqual(values[-1], "작업 중")

    def test_accepts_stringvar_like_target(self) -> None:
        class VarLike:
            def __init__(self) -> None:
                self.value = ""

            def set(self, text: str) -> None:
                self.value = text

        widget = FakePulseWidget()
        var = VarLike()
        pulse = agent.StatusPulse(widget, var)
        pulse.start("접수 중")
        self.assertEqual(var.value, "접수 중")
        pulse.stop("접수 완료")
        self.assertEqual(var.value, "접수 완료")


class SmoothedProgressTest(unittest.TestCase):
    def test_first_update_starts_at_actual_without_sweep(self) -> None:
        smoother = agent.SmoothedProgress()
        self.assertEqual(smoother.update(60, 0.0), 60)

    def test_jump_becomes_smooth_sweep(self) -> None:
        smoother = agent.SmoothedProgress()
        smoother.update(10, 0.0)
        # 실제값이 10→90으로 점프해도 표시값은 초당 45%로만 따라간다.
        self.assertEqual(smoother.update(90, 1.0), 55)
        self.assertEqual(smoother.update(90, 2.0), 90)

    def test_stall_never_moves_without_new_actual_progress(self) -> None:
        smoother = agent.SmoothedProgress()
        smoother.update(15, 0.0)
        self.assertEqual(smoother.update(15, 60.0), 15)
        self.assertEqual(smoother.update(15, 120.0), 15)
        smoother2 = agent.SmoothedProgress()
        smoother2.update(90, 0.0)
        self.assertEqual(smoother2.update(90, 600.0), 90)

    def test_completion_sweeps_to_exactly_100(self) -> None:
        smoother = agent.SmoothedProgress()
        smoother.update(80, 0.0)
        smoother.update(100, 0.2)  # 스윕 시작
        self.assertEqual(smoother.update(100, 5.0), 100)

    def test_display_is_monotonic(self) -> None:
        smoother = agent.SmoothedProgress()
        values = [smoother.update(actual, t * 0.2) for t, actual in enumerate([0, 10, 10, 10, 40, 40, 100, 100, 100])]
        self.assertEqual(values, sorted(values))


if __name__ == "__main__":
    unittest.main()
