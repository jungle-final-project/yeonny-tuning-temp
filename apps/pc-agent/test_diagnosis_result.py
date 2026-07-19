import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

from diagnosis_orchestrator import DiagnosisRunSnapshot, DiagnosisTask
from diagnosis_result import (
    DiagnosisResultStore,
    DiagnosisRuleEngine,
    can_offer_as,
    format_diagnosis_result_detail,
)
from initial_metrics import (
    AVAILABLE,
    ERROR,
    FAILED,
    NORMAL,
    UNAVAILABLE,
    UNSUPPORTED,
    MetricReading,
    MetricsSnapshot,
)


STAMP = "2026-07-13T01:00:00+00:00"


def reading(
    component,
    metric_type,
    value,
    unit="",
    availability=AVAILABLE,
    status=NORMAL,
    sampled_at=STAMP,
    error_code=None,
    failure_reason=None,
):
    return MetricReading(
        component,
        metric_type,
        value,
        unit,
        availability,
        status,
        "test-sensor",
        sampled_at,
        error_code,
        failure_reason,
    )


def unsupported(component, metric_type, unit=""):
    return reading(
        component,
        metric_type,
        None,
        unit,
        UNSUPPORTED,
        UNAVAILABLE,
        error_code="SENSOR_UNSUPPORTED",
        failure_reason="sensor unsupported",
    )


def base_readings():
    return [
        reading("cpu", "usage", 35.0, "%"),
        reading("cpu", "temperature", 60.0, "°C"),
        reading("cpu", "clock", 4200.0, "MHz"),
        reading("gpu", "usage", 45.0, "%"),
        reading("gpu", "temperature", 65.0, "°C"),
        reading("gpu", "fan_rpm", 1300.0, "RPM"),
        unsupported("gpu", "fan_percent", "%"),
        reading("gpu", "clock", 1800.0, "MHz"),
        reading("gpu", "thermal_throttling", "false"),
        reading("ram", "usage", 55.0, "%"),
        reading("ram", "used_bytes", 8 * 1024**3, "bytes"),
        reading("ram", "total_bytes", 16 * 1024**3, "bytes"),
        reading("disk", "activity", 15.0, "%"),
        reading("disk", "usage", 48.0, "%"),
        reading("disk", "smart", "정상"),
    ]


def replace_metric(readings, replacement):
    return [
        item
        for item in readings
        if not (item.component == replacement.component and item.metric_type == replacement.metric_type)
    ] + [replacement]


def build_inputs(readings=None, mode="LIVE"):
    values = list(readings or base_readings())
    metrics = MetricsSnapshot("diagnosis-1", mode, True, tuple(values))

    def latest(component, *metric_types):
        return tuple(
            item.to_dict()
            for metric_type in metric_types
            if (item := metrics.latest(component, metric_type)) is not None
        )

    definitions = (
        ("initial_snapshot", "system", 10, True, ({"initialComplete": True},)),
        ("cpu_health", "cpu", 10, False, latest("cpu", "usage", "temperature", "clock")),
        ("gpu_usage_temperature", "gpu", 20, False, latest("gpu", "usage", "temperature")),
        ("gpu_cooling_fan", "gpu", 20, False, latest("gpu", "fan_rpm", "fan_percent")),
        ("ram_health", "ram", 10, False, latest("ram", "usage", "used_bytes", "total_bytes")),
        ("disk_health", "disk", 15, False, latest("disk", "activity", "usage", "smart")),
        ("thermal_clock", "thermal", 10, False, latest("gpu", "thermal_throttling", "clock")),
    )
    tasks = []
    for task_id, component, weight, required, evidence in definitions:
        availabilities = {item.get("availability") for item in evidence if isinstance(item, dict)}
        status = "FAILED" if FAILED in availabilities else "UNSUPPORTED" if availabilities and AVAILABLE not in availabilities else "COMPLETED"
        tasks.append(DiagnosisTask(task_id, component, weight, required, status, evidence=evidence))
    tasks.append(DiagnosisTask("evidence_finalize", "system", 5, True, "COMPLETED", evidence=({"ready": True},)))
    partial = any(task.status != "COMPLETED" for task in tasks[:-1])
    diagnosis = DiagnosisRunSnapshot(
        diagnosis_id="diagnosis-1",
        mode=mode,
        state="PARTIALLY_COMPLETED" if partial else "COMPLETED",
        progress=100,
        tasks=tuple(tasks),
        completed_at=STAMP,
        transition_allowed=True,
    )
    return metrics, diagnosis


class DiagnosisRuleEngineTest(unittest.TestCase):
    def setUp(self):
        self.engine = DiagnosisRuleEngine()

    def evaluate(self, readings=None, mode="LIVE"):
        metrics, diagnosis = build_inputs(readings, mode)
        return self.engine.evaluate(metrics, diagnosis), diagnosis

    def test_gpu_high_temperature_zero_fan_and_throttling_requires_physical_inspection(self):
        values = base_readings()
        for replacement in (
            reading("gpu", "usage", 90.0, "%"),
            reading("gpu", "temperature", 90.0, "°C"),
            reading("gpu", "fan_rpm", 0.0, "RPM"),
            reading("gpu", "thermal_throttling", "active"),
        ):
            values = replace_metric(values, replacement)

        result, diagnosis = self.evaluate(values)

        self.assertEqual("CRITICAL", result.severity)
        self.assertEqual("PHYSICAL_INSPECTION", result.resolution_type)
        self.assertIn("팬 회전 상태 비정상", [item.title for item in result.findings])
        self.assertTrue(can_offer_as(result, diagnosis))

    def test_gpu_high_temperature_unsupported_fan_throttling_and_clock_drop_does_not_claim_fan_failure(self):
        values = base_readings()
        values.append(reading("gpu", "clock", 1000.0, "MHz", sampled_at="2026-07-13T01:00:01+00:00"))
        for replacement in (
            reading("gpu", "usage", 90.0, "%"),
            reading("gpu", "temperature", 90.0, "°C"),
            unsupported("gpu", "fan_rpm", "RPM"),
            unsupported("gpu", "fan_percent", "%"),
            reading("gpu", "thermal_throttling", "active"),
        ):
            values = replace_metric(values, replacement)
        values.insert(7, reading("gpu", "clock", 1800.0, "MHz", sampled_at=STAMP))

        result, _ = self.evaluate(values)

        titles = [item.title for item in result.findings]
        self.assertEqual("PHYSICAL_INSPECTION", result.resolution_type)
        self.assertIn("팬 회전 상태 확인 불가", titles)
        self.assertNotIn("팬 회전 상태 비정상", titles)
        self.assertTrue(any("GPU 팬" in item for item in result.unsupported_checks))

    def test_gpu_ninety_percent_usage_alone_is_only_information(self):
        values = replace_metric(base_readings(), reading("gpu", "usage", 90.0, "%"))

        result, diagnosis = self.evaluate(values)

        self.assertEqual("INFO", result.severity)
        self.assertEqual("USER_ACTION", result.resolution_type)
        self.assertFalse(can_offer_as(result, diagnosis))

    def test_idle_zero_rpm_is_not_a_fault(self):
        values = base_readings()
        values = replace_metric(values, reading("gpu", "usage", 5.0, "%"))
        values = replace_metric(values, reading("gpu", "temperature", 40.0, "°C"))
        values = replace_metric(values, reading("gpu", "fan_rpm", 0.0, "RPM"))

        result, _ = self.evaluate(values)

        self.assertEqual("NORMAL", result.severity)
        self.assertFalse(result.findings)

    def test_gpu_high_temperature_alone_does_not_claim_physical_failure(self):
        values = replace_metric(base_readings(), reading("gpu", "temperature", 90.0, "°C"))

        result, _ = self.evaluate(values)

        self.assertEqual("WARNING", result.severity)
        self.assertEqual("USER_ACTION", result.resolution_type)

    def test_sustained_ram_pressure_is_software_recovery(self):
        values = base_readings()
        values = replace_metric(values, reading("ram", "usage", 82.0, "%", sampled_at=STAMP))
        values.append(reading("ram", "usage", 90.0, "%", sampled_at="2026-07-13T01:00:01+00:00"))
        values.append(reading("ram", "usage", 96.0, "%", sampled_at="2026-07-13T01:00:02+00:00"))

        result, diagnosis = self.evaluate(values)

        self.assertEqual("SOFTWARE_RECOVERY", result.resolution_type)
        self.assertTrue(result.can_auto_recover)
        self.assertFalse(can_offer_as(result, diagnosis))

    def test_disk_smart_abnormal_is_physical_inspection(self):
        values = replace_metric(base_readings(), reading("disk", "smart", "CRITICAL"))

        result, diagnosis = self.evaluate(values)

        self.assertEqual("CRITICAL", result.severity)
        self.assertEqual("PHYSICAL_INSPECTION", result.resolution_type)
        self.assertTrue(can_offer_as(result, diagnosis))

    def test_all_normal_has_no_as_recommendation(self):
        result, diagnosis = self.evaluate()

        self.assertEqual("NORMAL", result.severity)
        self.assertEqual("NONE", result.resolution_type)
        self.assertFalse(can_offer_as(result, diagnosis))

    def test_multiple_required_sensors_unsupported_is_indeterminate(self):
        values = base_readings()
        for replacement in (
            unsupported("cpu", "usage", "%"),
            unsupported("gpu", "usage", "%"),
            unsupported("ram", "usage", "%"),
        ):
            values = replace_metric(values, replacement)

        result, diagnosis = self.evaluate(values)

        self.assertEqual("INDETERMINATE", result.severity)
        self.assertEqual("UNKNOWN", result.resolution_type)
        self.assertFalse(can_offer_as(result, diagnosis))

    def test_partial_sensor_failure_is_reported_without_inventing_a_finding(self):
        values = replace_metric(
            base_readings(),
            reading("cpu", "temperature", None, "°C", FAILED, ERROR, error_code="SENSOR_FAILED", failure_reason="query failed"),
        )

        result, _ = self.evaluate(values)

        self.assertEqual("NORMAL", result.severity)
        self.assertTrue(any("CPU 온도 확인 실패" == item for item in result.unsupported_checks))

    def test_same_input_is_deterministic(self):
        metrics, diagnosis = build_inputs()

        first = self.engine.evaluate(metrics, diagnosis)
        second = self.engine.evaluate(metrics, diagnosis)

        self.assertEqual(first, second)
        self.assertEqual(first.result_id, second.result_id)

    def test_detail_contains_value_unit_time_source_failures_and_logs(self):
        values = replace_metric(
            base_readings(),
            reading("cpu", "temperature", None, "°C", FAILED, ERROR, error_code="SENSOR_FAILED", failure_reason="query failed"),
        )
        result, diagnosis = self.evaluate(values)

        detail = format_diagnosis_result_detail(result, diagnosis)

        self.assertIn("GPU 사용률", detail)
        self.assertIn("%", detail)
        self.assertIn(STAMP, detail)
        self.assertIn("test-sensor", detail)
        self.assertIn("[측정 불가 검사]", detail)
        self.assertIn("[검사 실패]", detail)
        self.assertIn("[진단 로그 요약]", detail)

    def test_live_and_demo_use_identical_rules(self):
        live, _ = self.evaluate(base_readings(), "LIVE")
        demo, _ = self.evaluate(base_readings(), "DEMO")

        self.assertEqual(live.severity, demo.severity)
        self.assertEqual(live.resolution_type, demo.resolution_type)
        self.assertEqual([item.code for item in live.findings], [item.code for item in demo.findings])


class DiagnosisResultStoreTest(unittest.TestCase):
    def test_persists_and_deduplicates_same_result(self):
        result = DiagnosisRuleEngine().evaluate(*build_inputs())
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "diagnosis-result.json"
            store = DiagnosisResultStore(path)

            self.assertTrue(store.save(result))
            self.assertFalse(store.save(result))
            self.assertEqual(result, DiagnosisResultStore(path).result)
            store.clear()
            self.assertIsNone(store.result)
            self.assertFalse(path.exists())

    def test_persists_demo_mode_and_scenario_metadata(self):
        result = DiagnosisRuleEngine().evaluate(*build_inputs(mode="DEMO"))
        result = replace(result, scenario_id="GRAPHICS_CODE43_REMOTE_SUPPORT")
        with tempfile.TemporaryDirectory() as directory:
            store = DiagnosisResultStore(Path(directory) / "diagnosis-result.json")

            store.save(result)
            restored = DiagnosisResultStore(store.path).result

            self.assertEqual("DEMO", restored.data_mode)
            self.assertEqual("GRAPHICS_CODE43_REMOTE_SUPPORT", restored.scenario_id)
            self.assertEqual("DEMO", restored.to_dict()["dataMode"])
            self.assertEqual("GRAPHICS_CODE43_REMOTE_SUPPORT", restored.to_dict()["scenarioId"])


if __name__ == "__main__":
    unittest.main()
