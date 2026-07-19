from __future__ import annotations

import copy
import json
import queue
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Protocol

from demo_sensor_data import DEMO_SENSOR_SAMPLES, DEMO_SENSOR_STATUS, DEMO_UNAVAILABLE_REASONS


AVAILABLE = "AVAILABLE"
UNSUPPORTED = "UNSUPPORTED"
PERMISSION_REQUIRED = "PERMISSION_REQUIRED"
FAILED = "FAILED"

NORMAL = "NORMAL"
MODERATE = "MODERATE"
WARNING = "WARNING"
ABNORMAL = "ABNORMAL"
UNAVAILABLE = "UNAVAILABLE"
ERROR = "ERROR"

COMPONENTS = ("cpu", "gpu", "ram", "disk")
TERMINAL_AVAILABILITIES = {AVAILABLE, UNSUPPORTED, PERMISSION_REQUIRED, FAILED}
MAX_STORED_READINGS = 512
MAX_HISTORY_SAMPLES = 30

# 상태 확인 중 히스토리 막대 구간 검사: 막대 N개가 한 구간이며, 구간이 완성돼도
# 곧바로 칠하지 않고 lag초 뒤에 확정한다 → 회색(미검사) 꼬리가 항상 남아
# "쌓인 것을 뒤따라가며 검사한다"는 리듬이 생긴다.
CHECKING_SEGMENT_BARS = 4
CHECKING_LAG_SECONDS = 10.0


@dataclass(frozen=True)
class MetricPolicy:
    usage_moderate: float = 60.0
    usage_warning: float = 80.0
    usage_abnormal: float = 95.0
    cpu_temperature_warning: float = 85.0
    cpu_temperature_abnormal: float = 95.0
    gpu_temperature_warning: float = 85.0
    gpu_temperature_abnormal: float = 95.0


@dataclass(frozen=True)
class InitialCollectionSettings:
    sample_count: int = 3
    sample_interval_seconds: float = 0.25
    sample_timeout_seconds: float = 8.0
    transition_delay_seconds: float = 0.6
    steady_interval_seconds: float = 5.0

    def interval_seconds(self, initial_complete: bool) -> float:
        return self.steady_interval_seconds if initial_complete else self.sample_interval_seconds


DEFAULT_METRIC_POLICY = MetricPolicy()
DEFAULT_COLLECTION_SETTINGS = InitialCollectionSettings()


@dataclass(frozen=True)
class ProviderSample:
    payload: dict[str, Any]
    sampled_at: str
    source: str


class SensorProvider(Protocol):
    def collect_sample(self, sample_index: int) -> ProviderSample:
        ...


@dataclass(frozen=True)
class MetricReading:
    component: str
    metric_type: str
    value: float | str | None
    unit: str
    availability: str
    status: str
    source: str
    sampled_at: str
    error_code: str | None = None
    failure_reason: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "component": self.component,
            "metricType": self.metric_type,
            "value": self.value,
            "unit": self.unit,
            "availability": self.availability,
            "status": self.status,
            "source": self.source,
            "sampledAt": self.sampled_at,
            "errorCode": self.error_code,
            "failureReason": self.failure_reason,
        }

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> "MetricReading":
        return cls(
            component=str(payload["component"]),
            metric_type=str(payload["metricType"]),
            value=payload.get("value"),
            unit=str(payload.get("unit") or ""),
            availability=str(payload["availability"]),
            status=str(payload["status"]),
            source=str(payload.get("source") or "unknown"),
            sampled_at=str(payload["sampledAt"]),
            error_code=str(payload["errorCode"]) if payload.get("errorCode") else None,
            failure_reason=str(payload["failureReason"]) if payload.get("failureReason") else None,
        )


@dataclass(frozen=True)
class MetricsSnapshot:
    diagnosis_id: str | None
    mode: str | None
    initial_complete: bool
    readings: tuple[MetricReading, ...]

    def latest(self, component: str, *metric_types: str) -> MetricReading | None:
        allowed = set(metric_types)
        for reading in reversed(self.readings):
            if reading.component == component and reading.metric_type in allowed:
                return reading
        return None

    def history(self, component: str, metric_type: str, limit: int = 16) -> tuple[float, ...]:
        values = [
            float(reading.value)
            for reading in self.readings
            if reading.component == component
            and reading.metric_type == metric_type
            and reading.availability == AVAILABLE
            and isinstance(reading.value, (int, float))
            and not isinstance(reading.value, bool)
        ]
        bounded_limit = min(max(0, limit), MAX_HISTORY_SAMPLES)
        return tuple(values[-bounded_limit:]) if bounded_limit else ()

    def terminal_components(self) -> set[str]:
        terminal: set[str] = set()
        for component, metric_types in {
            "cpu": ("usage",),
            "gpu": ("usage",),
            "ram": ("usage",),
            "disk": ("activity", "usage"),
        }.items():
            reading = self.latest(component, *metric_types)
            if reading is not None and reading.availability in TERMINAL_AVAILABILITIES:
                terminal.add(component)
        return terminal


class MetricsStore:
    def __init__(self, path: Path | None = None) -> None:
        self.path = path
        self._lock = threading.Lock()
        self._diagnosis_id: str | None = None
        self._mode: str | None = None
        self._initial_complete = False
        self._readings: list[MetricReading] = []
        self._load()

    def begin(self, diagnosis_id: str, mode: str) -> None:
        with self._lock:
            self._diagnosis_id = diagnosis_id
            self._mode = mode
            self._initial_complete = False
            self._readings = []
            self._save_locked()

    def append(self, diagnosis_id: str, readings: tuple[MetricReading, ...]) -> bool:
        with self._lock:
            if diagnosis_id != self._diagnosis_id:
                return False
            self._readings.extend(readings)
            self._readings = self._readings[-MAX_STORED_READINGS:]
            self._save_locked()
            return True

    def complete(self, diagnosis_id: str) -> bool:
        with self._lock:
            if diagnosis_id != self._diagnosis_id:
                return False
            self._initial_complete = True
            self._save_locked()
            return True

    def clear(self) -> None:
        with self._lock:
            self._diagnosis_id = None
            self._mode = None
            self._initial_complete = False
            self._readings = []
            self._save_locked()

    @property
    def snapshot(self) -> MetricsSnapshot:
        with self._lock:
            return MetricsSnapshot(
                self._diagnosis_id,
                self._mode,
                self._initial_complete,
                tuple(self._readings),
            )

    def _load(self) -> None:
        if self.path is None:
            return
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
            readings = payload.get("readings") if isinstance(payload, dict) else None
            if not isinstance(readings, list):
                return
            self._diagnosis_id = str(payload["diagnosisId"]) if payload.get("diagnosisId") else None
            self._mode = str(payload["mode"]) if payload.get("mode") else None
            self._initial_complete = bool(payload.get("initialComplete"))
            self._readings = [MetricReading.from_dict(item) for item in readings if isinstance(item, dict)]
        except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError):
            self._diagnosis_id = None
            self._mode = None
            self._initial_complete = False
            self._readings = []

    def _save_locked(self) -> None:
        if self.path is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        payload = {
            "diagnosisId": self._diagnosis_id,
            "mode": self._mode,
            "initialComplete": self._initial_complete,
            "readings": [reading.to_dict() for reading in self._readings],
        }
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(self.path)


class HardwareSensorProvider:
    def __init__(self, collector: Any, now: Callable[[], datetime] | None = None) -> None:
        self.collector = collector
        self.now = now or (lambda: datetime.now(timezone.utc))

    def collect_sample(self, sample_index: int) -> ProviderSample:
        sampled_at = self.now()
        collect_initial = getattr(self.collector, "collect_initial", None)
        payload = collect_initial(sampled_at, sample_index) if callable(collect_initial) else self.collector.collect(sampled_at, sample_index)
        return ProviderSample(payload, sampled_at.astimezone(timezone.utc).isoformat(), "hardware")


class DemoSensorProvider:
    def __init__(self, now: Callable[[], datetime] | None = None) -> None:
        self.now = now or (lambda: datetime.now(timezone.utc))

    def collect_sample(self, sample_index: int) -> ProviderSample:
        sampled_at = self.now().astimezone(timezone.utc).isoformat()
        values = copy.deepcopy(DEMO_SENSOR_SAMPLES[sample_index % len(DEMO_SENSOR_SAMPLES)])
        values["sensorStatus"] = dict(DEMO_SENSOR_STATUS)
        values["unavailableReason"] = dict(DEMO_UNAVAILABLE_REASONS)
        values["gpuCollectorSource"] = "demo-scenario"
        values["diskCollectorSource"] = "demo-scenario"
        return ProviderSample(values, sampled_at, "demo-scenario")


class MetricsNormalizer:
    METRICS = (
        ("cpu", "usage", ("cpuUsagePercent", "cpuUsage"), "%", "usage", "psutil"),
        ("cpu", "temperature", ("cpuTempCelsius", "cpuTemp", "cpuTemperatureCelsius"), "°C", "cpu_temperature", "psutil-temperature"),
        ("cpu", "clock", ("cpuClockMhz",), "MHz", "plain", "psutil"),
        ("gpu", "usage", ("gpuUsagePercent", "gpuUsage"), "%", "usage", "gpuCollectorSource"),
        ("gpu", "temperature", ("gpuTempCelsius", "gpuTemp"), "°C", "gpu_temperature", "gpuCollectorSource"),
        ("gpu", "fan_rpm", ("gpuFanRpm",), "RPM", "fan", "gpuCollectorSource"),
        ("gpu", "fan_percent", ("gpuFanPercent",), "%", "fan", "gpuCollectorSource"),
        ("gpu", "clock", ("gpuClockMhz",), "MHz", "plain", "gpuCollectorSource"),
        ("gpu", "thermal_throttling", ("gpuThermalThrottling",), "", "throttling", "gpuCollectorSource"),
        ("ram", "usage", ("memoryUsedPercent", "memoryUsage", "ramUsage"), "%", "usage", "psutil"),
        ("ram", "used_bytes", ("memoryUsedBytes",), "bytes", "plain", "psutil"),
        ("ram", "total_bytes", ("memoryTotalBytes",), "bytes", "plain", "psutil"),
        ("disk", "activity", ("diskBusyEstimatePercent",), "%", "usage", "diskCollectorSource"),
        ("disk", "usage", ("diskUsedPercent", "diskUsage"), "%", "usage", "psutil"),
        ("disk", "used_bytes", ("diskUsedBytes",), "bytes", "plain", "psutil"),
        ("disk", "total_bytes", ("diskTotalBytes",), "bytes", "plain", "psutil"),
        ("disk", "smart", ("diskSmartStatus", "diskHealth"), "", "smart", "smart"),
    )

    def __init__(self, policy: MetricPolicy = DEFAULT_METRIC_POLICY) -> None:
        self.policy = policy

    def normalize(self, sample: ProviderSample) -> tuple[MetricReading, ...]:
        payload = self._payload(sample.payload)
        statuses = self._metadata(sample.payload, "sensorStatus")
        reasons = self._metadata(sample.payload, "unavailableReason")
        readings: list[MetricReading] = []
        for component, metric_type, fields, unit, rule, source_key in self.METRICS:
            value = self._value(sample.payload, fields)
            explicit_status = self._first_text(statuses, fields)
            reason = self._first_text(reasons, fields)
            availability = self._availability(value, explicit_status, reason)
            normalized_value = self._normalized_value(value)
            source = sample.source if sample.source == "demo-scenario" else str(payload.get(source_key) or source_key)
            readings.append(MetricReading(
                component=component,
                metric_type=metric_type,
                value=normalized_value if availability == AVAILABLE else None,
                unit=unit,
                availability=availability,
                status=self._status(component, rule, normalized_value, availability),
                source=source,
                sampled_at=sample.sampled_at,
                error_code=self._error_code(availability, reason),
                failure_reason=reason,
            ))
        return tuple(readings)

    def unavailable_readings(
        self,
        sampled_at: str,
        reason: str,
        availability: str = FAILED,
        error_code: str = "SENSOR_FAILED",
    ) -> tuple[MetricReading, ...]:
        status = ERROR if availability == FAILED else UNAVAILABLE
        return tuple(
            MetricReading(component, metric_type, None, unit, availability, status, "agent-core", sampled_at, error_code, reason)
            for component, metric_type, _, unit, _, _ in self.METRICS
        )

    @staticmethod
    def _payload(row: dict[str, Any]) -> dict[str, Any]:
        payload = row.get("payload")
        return payload if isinstance(payload, dict) else row

    @classmethod
    def _value(cls, row: dict[str, Any], fields: tuple[str, ...]) -> Any:
        payload = cls._payload(row)
        for field in fields:
            value = row.get(field, payload.get(field))
            if value is not None and (not isinstance(value, str) or value.strip()):
                return value
        return None

    @classmethod
    def _metadata(cls, row: dict[str, Any], name: str) -> dict[str, Any]:
        payload = cls._payload(row)
        value = row.get(name, payload.get(name))
        return value if isinstance(value, dict) else {}

    @staticmethod
    def _first_text(values: dict[str, Any], fields: tuple[str, ...]) -> str | None:
        for field in fields:
            value = values.get(field)
            if value is not None and str(value).strip():
                return str(value).strip()
        return None

    @staticmethod
    def _normalized_value(value: Any) -> float | str | None:
        if isinstance(value, bool):
            return str(value).lower()
        if isinstance(value, (int, float)):
            return round(float(value), 1)
        return str(value).strip() if value is not None else None

    @staticmethod
    def _availability(value: Any, explicit_status: str | None, reason: str | None) -> str:
        if value is not None and (not isinstance(value, str) or value.strip()):
            return AVAILABLE
        status = (explicit_status or "").casefold()
        detail = (reason or "").casefold()
        if status in {"permission_required", "permission-required"} or any(
            token in detail for token in ("permission", "access denied", "administrator")
        ):
            return PERMISSION_REQUIRED
        if status == "failed" or any(token in detail for token in ("failed", "timed out", "timeout", "exception")):
            return FAILED
        return UNSUPPORTED

    def _status(self, component: str, rule: str, value: float | str | None, availability: str) -> str:
        if availability == FAILED:
            return ERROR
        if availability != AVAILABLE:
            return UNAVAILABLE
        if rule == "usage" and isinstance(value, (int, float)):
            number = float(value)
            if number >= self.policy.usage_abnormal:
                return ABNORMAL
            if number >= self.policy.usage_warning:
                return WARNING
            if number >= self.policy.usage_moderate:
                return MODERATE
            return NORMAL
        if rule in {"cpu_temperature", "gpu_temperature"} and isinstance(value, (int, float)):
            warning = self.policy.cpu_temperature_warning if component == "cpu" else self.policy.gpu_temperature_warning
            abnormal = self.policy.cpu_temperature_abnormal if component == "cpu" else self.policy.gpu_temperature_abnormal
            if float(value) >= abnormal:
                return ABNORMAL
            if float(value) >= warning:
                return WARNING
            return NORMAL
        if rule == "fan" and isinstance(value, (int, float)):
            return MODERATE if float(value) == 0 else NORMAL
        if rule == "smart":
            normalized = str(value).casefold()
            if any(token in normalized for token in ("bad", "fail", "critical", "주의", "경고", "비정상")):
                return ABNORMAL
            return NORMAL
        if rule == "throttling":
            normalized = str(value).casefold()
            return WARNING if normalized in {"active", "true", "1", "yes"} else NORMAL
        return NORMAL

    @staticmethod
    def _error_code(availability: str, reason: str | None) -> str | None:
        if availability == AVAILABLE:
            return None
        if availability == PERMISSION_REQUIRED:
            return "PERMISSION_REQUIRED"
        if availability == UNSUPPORTED:
            return "SENSOR_UNSUPPORTED"
        detail = (reason or "").casefold()
        return "SENSOR_TIMEOUT" if "timeout" in detail or "timed out" in detail else "SENSOR_FAILED"


class InitialMetricsCoordinator:
    def __init__(
        self,
        store: MetricsStore,
        live_provider_factory: Callable[[], SensorProvider],
        demo_provider_factory: Callable[[], SensorProvider],
        normalizer: MetricsNormalizer | None = None,
        settings: InitialCollectionSettings = DEFAULT_COLLECTION_SETTINGS,
        on_update: Callable[[MetricsSnapshot], None] | None = None,
        on_complete: Callable[[MetricsSnapshot], None] | None = None,
    ) -> None:
        self.store = store
        self.live_provider_factory = live_provider_factory
        self.demo_provider_factory = demo_provider_factory
        self.normalizer = normalizer or MetricsNormalizer()
        self.settings = settings
        self.on_update = on_update or (lambda snapshot: None)
        self.on_complete = on_complete or (lambda snapshot: None)
        self._lock = threading.Lock()
        self._active_diagnosis_id: str | None = None
        self._thread: threading.Thread | None = None
        self._stop_event: threading.Event | None = None
        self._initial_complete_event = threading.Event()

    def start(self, diagnosis_id: str, mode: str) -> bool:
        normalized_mode = mode.upper()
        if normalized_mode not in {"LIVE", "DEMO"}:
            raise ValueError("mode must be LIVE or DEMO")
        with self._lock:
            active_thread = self._thread
            active_diagnosis_id = self._active_diagnosis_id
        if active_thread is not None and active_thread.is_alive():
            if active_diagnosis_id == diagnosis_id:
                return False
            if not self.stop():
                return False
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return False
            stop_event = threading.Event()
            self._active_diagnosis_id = diagnosis_id
            self._stop_event = stop_event
            self._initial_complete_event.clear()
            self.store.begin(diagnosis_id, normalized_mode)
            self._thread = threading.Thread(
                target=self._run,
                args=(diagnosis_id, normalized_mode, stop_event),
                name="pc-agent-initial-metrics",
                daemon=True,
            )
            self._thread.start()
            return True

    def wait(self, timeout: float | None = None) -> bool:
        with self._lock:
            thread = self._thread
        if thread is None and not self._initial_complete_event.is_set():
            return True
        return self._initial_complete_event.wait(timeout)

    def is_running(self, diagnosis_id: str | None = None) -> bool:
        with self._lock:
            thread = self._thread
            active_diagnosis_id = self._active_diagnosis_id
        return bool(
            thread is not None
            and thread.is_alive()
            and (diagnosis_id is None or diagnosis_id == active_diagnosis_id)
        )

    def stop(self, timeout: float | None = None) -> bool:
        with self._lock:
            thread = self._thread
            stop_event = self._stop_event
        if stop_event is not None:
            stop_event.set()
        if thread is None or thread is threading.current_thread():
            return True
        wait_timeout = timeout
        if wait_timeout is None:
            wait_timeout = max(
                1.0,
                self.settings.sample_timeout_seconds + self.settings.sample_interval_seconds + 0.5,
            )
        thread.join(wait_timeout)
        return not thread.is_alive()

    def _run(self, diagnosis_id: str, mode: str, stop_event: threading.Event) -> None:
        try:
            try:
                provider = self.demo_provider_factory() if mode == "DEMO" else self.live_provider_factory()
            except PermissionError:
                self._finish_with_unavailable(
                    diagnosis_id,
                    "sensor provider permission required",
                    PERMISSION_REQUIRED,
                    "PERMISSION_REQUIRED",
                )
                return
            except Exception:
                self._finish_with_unavailable(
                    diagnosis_id,
                    "sensor provider initialization failed",
                    FAILED,
                    "SENSOR_FAILED",
                )
                return
            sample_index = 0
            initial_complete = False
            while not stop_event.is_set():
                sample, error = self._collect_with_timeout(provider, sample_index)
                if error is None and sample is not None:
                    readings = self.normalizer.normalize(sample)
                else:
                    sampled_at = datetime.now(timezone.utc).isoformat()
                    if error == "sensor collection permission required":
                        availability, error_code = PERMISSION_REQUIRED, "PERMISSION_REQUIRED"
                    else:
                        availability = FAILED
                        error_code = "SENSOR_TIMEOUT" if error == "sensor collection timed out" else "SENSOR_FAILED"
                    readings = self.normalizer.unavailable_readings(
                        sampled_at,
                        error or "sensor collection failed",
                        availability,
                        error_code,
                    )
                if not self.store.append(diagnosis_id, readings):
                    return
                self.on_update(self.store.snapshot)
                sample_index += 1
                snapshot = self.store.snapshot
                initial_ready = (
                    sample_index >= self.settings.sample_count or error is not None
                ) and snapshot.terminal_components() == set(COMPONENTS)
                if not initial_complete and initial_ready and self.store.complete(diagnosis_id):
                    initial_complete = True
                    completed = self.store.snapshot
                    self._initial_complete_event.set()
                    self.on_update(completed)
                    self.on_complete(completed)
                interval_seconds = self.settings.interval_seconds(initial_complete)
                if stop_event.wait(max(0.01, interval_seconds)):
                    return
        finally:
            with self._lock:
                if self._active_diagnosis_id == diagnosis_id and self._stop_event is stop_event:
                    self._active_diagnosis_id = None
                    self._stop_event = None
                    self._thread = None

    def _finish_with_unavailable(
        self,
        diagnosis_id: str,
        reason: str,
        availability: str,
        error_code: str,
    ) -> None:
        readings = self.normalizer.unavailable_readings(
            datetime.now(timezone.utc).isoformat(),
            reason,
            availability,
            error_code,
        )
        if not self.store.append(diagnosis_id, readings):
            return
        self.on_update(self.store.snapshot)
        if self.store.complete(diagnosis_id):
            completed = self.store.snapshot
            self._initial_complete_event.set()
            self.on_update(completed)
            self.on_complete(completed)

    def _collect_with_timeout(
        self,
        provider: SensorProvider,
        sample_index: int,
    ) -> tuple[ProviderSample | None, str | None]:
        result_queue: queue.Queue[tuple[ProviderSample | None, str | None]] = queue.Queue(maxsize=1)

        def collect() -> None:
            try:
                result_queue.put((provider.collect_sample(sample_index), None))
            except PermissionError:
                result_queue.put((None, "sensor collection permission required"))
            except Exception:
                result_queue.put((None, "sensor collection failed"))

        worker = threading.Thread(target=collect, name=f"pc-agent-sensor-sample-{sample_index}", daemon=True)
        worker.start()
        worker.join(self.settings.sample_timeout_seconds)
        if worker.is_alive():
            return None, "sensor collection timed out"
        try:
            return result_queue.get_nowait()
        except queue.Empty:
            return None, "sensor collection failed"


def history_check_colors(
    readings: "Sequence[MetricReading]",
    component: str,
    metric_type: str,
    now: datetime,
    limit: int = 16,
    segment_bars: int = CHECKING_SEGMENT_BARS,
    lag_seconds: float = CHECKING_LAG_SECONDS,
) -> list[str | None]:
    """상태 확인 중 히스토리 막대에 입힐 구간 검사 색을 계산한다.

    막대는 기존처럼 표본이 올 때마다 하나씩 쌓인다(수집感은 기존 그대로).
    막대 segment_bars개가 한 구간이며, 구간의 마지막 표본으로부터 lag_seconds가
    지난 뒤에야 그 구간을 확정한다 — 검사가 수집을 지연을 두고 뒤따라가는 리듬.
    확정 색: 구간 시간 범위에 해당 component 센서 ERROR가 있으면 'error', 없으면 'ok'.
    미확정 막대는 None(기본 회색) = "수집됨, 검사 대기 중".
    반환 리스트는 MetricsSnapshot.history(...limit)와 같은 필터·슬라이스로 정렬된다.
    """
    series: list[datetime] = []
    error_times: list[datetime] = []
    for reading in readings:
        if reading.component != component:
            continue
        try:
            sampled = datetime.fromisoformat(str(reading.sampled_at))
        except (TypeError, ValueError):
            continue
        if reading.status == ERROR:
            error_times.append(sampled)
        if (
            reading.metric_type == metric_type
            and reading.availability == AVAILABLE
            and isinstance(reading.value, (int, float))
            and not isinstance(reading.value, bool)
        ):
            series.append(sampled)
    if not series or segment_bars <= 0:
        return []
    if now.tzinfo is None and series[0].tzinfo is not None:
        now = now.replace(tzinfo=series[0].tzinfo)

    colors: list[str | None] = []
    for index, _sampled in enumerate(series):
        segment = index // segment_bars
        last_index = segment * segment_bars + segment_bars - 1
        if last_index >= len(series):
            colors.append(None)  # 구간 미완성
            continue
        segment_start = series[segment * segment_bars]
        segment_end = series[last_index]
        if (now - segment_end).total_seconds() < lag_seconds:
            colors.append(None)  # 완성됐지만 아직 검사 차례가 안 옴(지연 추적)
            continue
        has_error = any(segment_start <= moment <= segment_end for moment in error_times)
        colors.append("error" if has_error else "ok")

    bounded_limit = min(max(0, limit), MAX_HISTORY_SAMPLES)
    return colors[-bounded_limit:] if bounded_limit else []
