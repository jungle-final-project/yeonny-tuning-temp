from __future__ import annotations

import gzip
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import buildgraph_agent as agent


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
            nvidia_smi_runner=lambda: SimpleNamespace(returncode=0, stdout="42, 2048, 8192, 66\n"),
            time_fn=lambda: 100.0,
        )

        row = collector.collect(datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST), 0)

        self.assertEqual(row["gpuUsage"], 42.0)
        self.assertEqual(row["gpuUsagePercent"], 42.0)
        self.assertEqual(row["vramUsage"], 25.0)
        self.assertEqual(row["vramUsagePercent"], 25.0)
        self.assertEqual(row["gpuTemp"], 66.0)
        self.assertEqual(row["gpuTempCelsius"], 66.0)
        self.assertEqual(row["gpuCollectorSource"], "nvidia-smi")
        self.assertEqual(row["sensorStatus"]["vramUsagePercent"], "collected")
        self.assertEqual(row["sensorStatus"]["gpuTempCelsius"], "collected")
        self.assertEqual(row["sensorStatus"]["cpuTempCelsius"], "unsupported")
        self.assertEqual(agent.display_log_table_values(row)[6], "25.0%")
        self.assertEqual(agent.display_log_table_values(row)[8], "66.0C")

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
            now = datetime.now(agent.KST)
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

    def test_run_background_exits_when_instance_lock_exists(self) -> None:
        with patch("buildgraph_agent.acquire_named_instance_lock", return_value=None) as acquire_lock, \
            patch("buildgraph_agent.ensure_default_config") as ensure_default_config, \
            patch("buildgraph_agent.show_log_viewer") as show_log_viewer:
            exit_code = agent.run_background(Path("agent-config.json"), with_tray=False)

        self.assertEqual(exit_code, 0)
        acquire_lock.assert_called_once_with(agent.BACKGROUND_INSTANCE_MUTEX_NAME)
        ensure_default_config.assert_not_called()
        show_log_viewer.assert_not_called()

    def test_run_background_opens_viewer_for_user_launch_when_instance_lock_exists(self) -> None:
        config_path = Path("agent-config.json")
        with patch("buildgraph_agent.acquire_named_instance_lock", return_value=None) as acquire_lock, \
            patch("buildgraph_agent.ensure_default_config", return_value=config_path) as ensure_default_config, \
            patch("buildgraph_agent.show_log_viewer") as show_log_viewer:
            exit_code = agent.run_background(config_path, with_tray=False, open_viewer_when_running=True)

        self.assertEqual(exit_code, 0)
        acquire_lock.assert_called_once_with(agent.BACKGROUND_INSTANCE_MUTEX_NAME)
        ensure_default_config.assert_called_once_with(config_path)
        show_log_viewer.assert_called_once_with(config_path)

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

    def test_specup_agent_icon_assets_are_available(self) -> None:
        self.assertTrue(agent.app_asset_path(agent.AGENT_ICON_PNG).exists())
        self.assertTrue(agent.app_asset_path(agent.AGENT_ICON_ICO).exists())
        tray_image = agent.create_tray_image()
        if agent.Image is not None:
            self.assertIsNotNone(tray_image)
            self.assertEqual(tray_image.size, (64, 64))


if __name__ == "__main__":
    unittest.main()
