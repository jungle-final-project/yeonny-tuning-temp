from __future__ import annotations

import hashlib
import json
import threading
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Any

from diagnosis_orchestrator import DiagnosisRunSnapshot
from initial_metrics import (
    AVAILABLE,
    FAILED,
    PERMISSION_REQUIRED,
    UNSUPPORTED,
    DEFAULT_METRIC_POLICY,
    MetricsSnapshot,
)
from pc_agent_demo_scenarios import (
    DEMO_DATA_MODE,
    GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID,
    LIVE_DATA_MODE,
)


SEVERITIES = ("NORMAL", "INFO", "WARNING", "CRITICAL", "INDETERMINATE")
RESOLUTION_TYPES = ("NONE", "SOFTWARE_RECOVERY", "USER_ACTION", "PHYSICAL_INSPECTION", "UNKNOWN")


@dataclass(frozen=True)
class DiagnosisRulePolicy:
    usage_warning: float = DEFAULT_METRIC_POLICY.usage_warning
    usage_abnormal: float = DEFAULT_METRIC_POLICY.usage_abnormal
    gpu_temperature_warning: float = DEFAULT_METRIC_POLICY.gpu_temperature_warning
    gpu_temperature_abnormal: float = DEFAULT_METRIC_POLICY.gpu_temperature_abnormal
    clock_drop_ratio: float = 0.8
    insufficient_component_count: int = 2


DEFAULT_DIAGNOSIS_RULE_POLICY = DiagnosisRulePolicy()


@dataclass(frozen=True)
class DiagnosisEvidence:
    task_id: str
    component: str
    metric_type: str
    value: Any
    unit: str
    availability: str
    status: str
    source: str
    sampled_at: str
    error_code: str | None = None
    failure_reason: str | None = None
    category: str | None = None
    code: int | str | None = None
    occurred_at: str | None = None
    description: str | None = None

    @property
    def key(self) -> str:
        return f"{self.component}.{self.metric_type}"

    def to_dict(self) -> dict[str, Any]:
        payload = {
            "taskId": self.task_id,
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
        if self.category:
            payload["category"] = self.category
            payload["code"] = self.code
        elif self.code is not None:
            payload["code"] = self.code
        if self.occurred_at:
            payload["occurredAt"] = self.occurred_at
        if self.description:
            payload["description"] = self.description
        return payload

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> "DiagnosisEvidence":
        return cls(
            task_id=str(payload.get("taskId") or ""),
            component=str(payload.get("component") or ""),
            metric_type=str(payload.get("metricType") or ""),
            value=payload.get("value"),
            unit=str(payload.get("unit") or ""),
            availability=str(payload.get("availability") or ""),
            status=str(payload.get("status") or ""),
            source=str(payload.get("source") or "unknown"),
            sampled_at=str(payload.get("sampledAt") or ""),
            error_code=str(payload["errorCode"]) if payload.get("errorCode") else None,
            failure_reason=str(payload["failureReason"]) if payload.get("failureReason") else None,
            category=str(payload["category"]) if payload.get("category") else None,
            code=payload.get("code"),
            occurred_at=str(payload["occurredAt"]) if payload.get("occurredAt") else None,
            description=str(payload["description"]) if payload.get("description") else None,
        )


@dataclass(frozen=True)
class DiagnosisFinding:
    code: str
    severity: str
    title: str
    summary: str
    evidence_keys: tuple[str, ...]
    suspected_causes: tuple[str, ...]
    recommended_actions: tuple[str, ...]
    resolution_type: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "code": self.code,
            "severity": self.severity,
            "title": self.title,
            "summary": self.summary,
            "evidenceKeys": list(self.evidence_keys),
            "suspectedCauses": list(self.suspected_causes),
            "recommendedActions": list(self.recommended_actions),
            "resolutionType": self.resolution_type,
        }

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> "DiagnosisFinding":
        return cls(
            code=str(payload.get("code") or ""),
            severity=str(payload.get("severity") or "INDETERMINATE"),
            title=str(payload.get("title") or ""),
            summary=str(payload.get("summary") or ""),
            evidence_keys=tuple(str(item) for item in payload.get("evidenceKeys", ())),
            suspected_causes=tuple(str(item) for item in payload.get("suspectedCauses", ())),
            recommended_actions=tuple(str(item) for item in payload.get("recommendedActions", ())),
            resolution_type=str(payload.get("resolutionType") or "UNKNOWN"),
        )


@dataclass(frozen=True)
class DiagnosisResult:
    diagnosis_id: str
    severity: str
    title: str
    summary: str
    evidence: tuple[DiagnosisEvidence, ...]
    findings: tuple[DiagnosisFinding, ...]
    suspected_causes: tuple[str, ...]
    recommended_actions: tuple[str, ...]
    resolution_type: str
    can_auto_recover: bool
    unsupported_checks: tuple[str, ...]
    evaluated_at: str
    diagnosis_type: str | None = None
    remote_as_recommended: bool = False
    data_mode: str = LIVE_DATA_MODE
    scenario_id: str | None = None

    @property
    def result_id(self) -> str:
        encoded = json.dumps(self._payload(), ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()

    def _payload(self) -> dict[str, Any]:
        payload = {
            "diagnosisId": self.diagnosis_id,
            "severity": self.severity,
            "title": self.title,
            "summary": self.summary,
            "evidence": [item.to_dict() for item in self.evidence],
            "findings": [item.to_dict() for item in self.findings],
            "suspectedCauses": list(self.suspected_causes),
            "recommendedActions": list(self.recommended_actions),
            "resolutionType": self.resolution_type,
            "canAutoRecover": self.can_auto_recover,
            "unsupportedChecks": list(self.unsupported_checks),
            "evaluatedAt": self.evaluated_at,
            "dataMode": self.data_mode,
        }
        if self.scenario_id:
            payload["scenarioId"] = self.scenario_id
        if self.diagnosis_type:
            payload["diagnosisType"] = self.diagnosis_type
            payload["remoteAsRecommended"] = self.remote_as_recommended
        return payload

    def to_dict(self) -> dict[str, Any]:
        return {"resultId": self.result_id, **self._payload()}

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> "DiagnosisResult":
        return cls(
            diagnosis_id=str(payload.get("diagnosisId") or ""),
            severity=str(payload.get("severity") or "INDETERMINATE"),
            title=str(payload.get("title") or ""),
            summary=str(payload.get("summary") or ""),
            evidence=tuple(DiagnosisEvidence.from_dict(item) for item in payload.get("evidence", ()) if isinstance(item, dict)),
            findings=tuple(DiagnosisFinding.from_dict(item) for item in payload.get("findings", ()) if isinstance(item, dict)),
            suspected_causes=tuple(str(item) for item in payload.get("suspectedCauses", ())),
            recommended_actions=tuple(str(item) for item in payload.get("recommendedActions", ())),
            resolution_type=str(payload.get("resolutionType") or "UNKNOWN"),
            can_auto_recover=bool(payload.get("canAutoRecover")),
            unsupported_checks=tuple(str(item) for item in payload.get("unsupportedChecks", ())),
            evaluated_at=str(payload.get("evaluatedAt") or ""),
            diagnosis_type=str(payload["diagnosisType"]) if payload.get("diagnosisType") else None,
            remote_as_recommended=bool(payload.get("remoteAsRecommended")),
            data_mode=str(payload.get("dataMode") or LIVE_DATA_MODE),
            scenario_id=str(payload["scenarioId"]) if payload.get("scenarioId") else None,
        )


class DiagnosisResultStore:
    def __init__(self, path: Path | None = None) -> None:
        self.path = path
        self._lock = threading.Lock()
        self._result: DiagnosisResult | None = None
        self._load()

    @property
    def result(self) -> DiagnosisResult | None:
        with self._lock:
            return self._result

    def save(self, result: DiagnosisResult) -> bool:
        with self._lock:
            if self._result is not None and self._result.result_id == result.result_id:
                return False
            self._result = result
            self._save_locked()
            return True

    def clear(self) -> None:
        with self._lock:
            self._result = None
            if self.path is not None:
                self.path.unlink(missing_ok=True)

    def _load(self) -> None:
        if self.path is None or not self.path.exists():
            return
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
            if isinstance(payload, dict):
                self._result = DiagnosisResult.from_dict(payload)
        except (OSError, ValueError, TypeError, KeyError):
            self._result = None

    def _save_locked(self) -> None:
        if self.path is None or self._result is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(f"{self.path.suffix}.tmp")
        temporary.write_text(json.dumps(self._result.to_dict(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(self.path)


class DiagnosisRuleEngine:
    LABELS = {
        "cpu.usage": "CPU 사용률",
        "cpu.temperature": "CPU 온도",
        "cpu.clock": "CPU 클럭",
        "gpu.usage": "GPU 사용률",
        "gpu.temperature": "GPU 온도",
        "gpu.fan_rpm": "GPU 팬 RPM",
        "gpu.fan_percent": "GPU 팬 회전 상태",
        "gpu.clock": "GPU 클럭",
        "gpu.thermal_throttling": "GPU 열 제한",
        "ram.usage": "RAM 사용률",
        "ram.used_bytes": "RAM 사용량",
        "ram.total_bytes": "RAM 전체 용량",
        "disk.activity": "디스크 활성 시간",
        "disk.usage": "디스크 사용률",
        "disk.smart": "디스크 SMART",
        "gpu.display_device_status": "Windows Display 장치 상태",
        "gpu.display_driver": "Windows Display 드라이버",
        "system.symptom_correlation": "웹 전달 증상",
        "system.observation_window": "실제 관찰 샘플",
        "system.diagnosis_type": "진단 분류",
    }

    def __init__(self, policy: DiagnosisRulePolicy = DEFAULT_DIAGNOSIS_RULE_POLICY) -> None:
        self.policy = policy

    def evaluate(self, metrics: MetricsSnapshot, diagnosis: DiagnosisRunSnapshot) -> DiagnosisResult:
        diagnosis_id = str(diagnosis.diagnosis_id or "")
        if not diagnosis_id or metrics.diagnosis_id != diagnosis_id:
            raise ValueError("metrics and diagnosis must refer to the same diagnosisId")
        if diagnosis.state not in {"COMPLETED", "PARTIALLY_COMPLETED"} or not diagnosis.transition_allowed:
            raise ValueError("diagnosis must contain valid completed evidence")

        if diagnosis.task("final_classification") is not None:
            return self._evaluate_graphics_configuration(diagnosis)

        evidence = self._collect_evidence(diagnosis)
        latest = self._latest_evidence(evidence)
        unsupported_checks = self._unsupported_checks(evidence, diagnosis)
        findings: list[DiagnosisFinding] = []

        gpu_usage = self._number(latest.get("gpu.usage"))
        gpu_temperature = self._number(latest.get("gpu.temperature"))
        gpu_fan = self._fan_evidence(latest)
        gpu_throttling = self._truthy(latest.get("gpu.thermal_throttling"))
        gpu_clock_drop = self._clock_drop(metrics, latest)
        high_gpu_load = gpu_usage is not None and gpu_usage >= self.policy.usage_warning
        high_gpu_temperature = gpu_temperature is not None and gpu_temperature >= self.policy.gpu_temperature_warning
        fan_zero = gpu_fan is not None and gpu_fan.availability == AVAILABLE and self._number(gpu_fan) == 0
        fan_unavailable = gpu_fan is None or gpu_fan.availability != AVAILABLE
        strong_gpu_cooling = high_gpu_temperature and high_gpu_load and fan_zero and (gpu_throttling or gpu_clock_drop)
        unknown_fan_cooling = high_gpu_temperature and fan_unavailable and gpu_throttling and gpu_clock_drop

        if strong_gpu_cooling:
            findings.extend((
                self._finding("GPU_TEMPERATURE_HIGH", "WARNING", "GPU 온도 상승", "고부하 상태에서 GPU 온도가 임계값을 초과했습니다.", ("gpu.usage", "gpu.temperature"), ("GPU 냉각 성능 저하",), ("GPU 냉각 팬 점검",), "PHYSICAL_INSPECTION"),
                self._finding("GPU_FAN_ABNORMAL", "CRITICAL", "팬 회전 상태 비정상", "고부하·고온 상태에서 지원되는 팬 센서가 0을 보고했습니다.", ("gpu.usage", "gpu.temperature", gpu_fan.key), ("GPU 팬 정지 또는 전원 연결 이상",), ("팬 전원 연결 상태 확인",), "PHYSICAL_INSPECTION"),
                self._finding("GPU_THERMAL_LIMIT", "WARNING", "열 제한 징후", "열 제한 또는 의미 있는 GPU 클럭 저하가 함께 감지되었습니다.", tuple(key for key, active in (("gpu.thermal_throttling", gpu_throttling), ("gpu.clock", gpu_clock_drop)) if active), ("냉각 계통의 열 배출 저하",), ("AS 기사 연결 권장",), "PHYSICAL_INSPECTION"),
            ))
            profile = "GPU_COOLING_STRONG"
        elif unknown_fan_cooling:
            findings.extend((
                self._finding("GPU_TEMPERATURE_HIGH", "WARNING", "GPU 온도 상승", "GPU 온도가 임계값을 초과했습니다.", ("gpu.temperature",), ("GPU 냉각 성능 저하",), ("GPU 냉각 상태 점검",), "PHYSICAL_INSPECTION"),
                self._finding("GPU_FAN_UNAVAILABLE", "INFO", "팬 회전 상태 확인 불가", "팬 센서가 지원되지 않아 회전 상태를 판정하지 않았습니다.", (), ("팬 센서 미지원",), ("팬 회전 상태 직접 확인",), "PHYSICAL_INSPECTION"),
                self._finding("GPU_THERMAL_LIMIT", "WARNING", "열 제한 징후", "열 제한과 GPU 클럭 저하가 함께 감지되었습니다.", ("gpu.thermal_throttling", "gpu.clock"), ("냉각 계통의 열 배출 저하",), ("AS 기사 연결 권장",), "PHYSICAL_INSPECTION"),
            ))
            profile = "GPU_COOLING_UNKNOWN_FAN"
        else:
            profile = "GENERAL"
            if high_gpu_temperature:
                findings.append(self._finding(
                    "GPU_TEMPERATURE_HIGH", "WARNING", "GPU 온도 상승",
                    "GPU 온도가 높지만 냉각 계통의 물리 이상을 확정할 복수 근거는 부족합니다.",
                    ("gpu.temperature",), ("일시적 고부하 또는 통풍 저하",),
                    ("실행 중인 작업 확인", "통풍 상태 확인", "온도 재확인"), "USER_ACTION",
                ))
            elif high_gpu_load:
                findings.append(self._finding(
                    "GPU_HIGH_LOAD", "INFO", "GPU 고부하",
                    "GPU 사용률은 높지만 온도와 열 제한 근거는 정상 범위입니다.",
                    ("gpu.usage",), ("게임 또는 그래픽 작업의 지속 부하",),
                    ("실행 중인 작업 확인", "불필요한 그래픽 작업 종료", "부하 종료 후 재확인"), "USER_ACTION",
                ))

        ram_usage = self._number(latest.get("ram.usage"))
        ram_history = metrics.history("ram", "usage", limit=3)
        ram_sustained = (
            len(ram_history) >= 3
            and all(value >= self.policy.usage_warning for value in ram_history)
            and all(left <= right for left, right in zip(ram_history, ram_history[1:]))
        )
        if ram_usage is not None and (ram_usage >= self.policy.usage_abnormal or ram_sustained):
            findings.append(self._finding(
                "RAM_PRESSURE", "WARNING", "RAM 사용량 부족",
                "RAM 사용률이 지속적으로 높아 가용 메모리 부족 가능성이 있습니다.",
                ("ram.usage", "ram.used_bytes", "ram.total_bytes"), ("실행 프로그램의 메모리 과다 사용",),
                ("메모리 사용량이 큰 프로그램 종료", "시작 프로그램 정리", "PC 재시작 후 재확인"), "SOFTWARE_RECOVERY",
            ))

        disk_smart = latest.get("disk.smart")
        if self._smart_abnormal(disk_smart):
            findings.append(self._finding(
                "DISK_SMART_ABNORMAL", "CRITICAL", "디스크 SMART 이상",
                "디스크가 명시적인 비정상 상태를 보고했습니다.",
                ("disk.smart",), ("저장장치 물리 이상 가능성",),
                ("중요 데이터 백업", "디스크 연결 상태 확인", "AS 기사 연결 권장"), "PHYSICAL_INSPECTION",
            ))

        missing_components = self._missing_primary_components(latest)
        insufficient = len(missing_components) >= self.policy.insufficient_component_count
        evaluated_at = max((item.sampled_at for item in evidence if item.sampled_at), default=diagnosis.completed_at or "")

        if not findings and insufficient:
            return DiagnosisResult(
                diagnosis_id, "INDETERMINATE", "측정 정보가 부족하여 문제를 판정할 수 없습니다.",
                "주요 하드웨어 검사 중 여러 항목을 확인하지 못했습니다.", evidence, (), (),
                ("지원되는 센서와 권한을 확인한 뒤 다시 진단",), "UNKNOWN", False,
                tuple(unsupported_checks), evaluated_at,
                data_mode=diagnosis.mode,
            )
        if not findings:
            return DiagnosisResult(
                diagnosis_id, "NORMAL", "측정된 하드웨어 상태가 정상 범위입니다.",
                "현재 수집된 근거에서는 즉시 조치가 필요한 이상이 확인되지 않았습니다.", evidence, (), (),
                ("현재 상태 유지",), "NONE", False, tuple(unsupported_checks), evaluated_at,
                data_mode=diagnosis.mode,
            )

        severity = self._overall_severity(findings)
        resolution_type = self._overall_resolution(findings)
        causes = self._unique(item for finding in findings for item in finding.suspected_causes)
        actions = self._unique(item for finding in findings for item in finding.recommended_actions)[:3]
        title, summary = self._presentation(profile, findings, severity)
        return DiagnosisResult(
            diagnosis_id, severity, title, summary, evidence, tuple(findings), tuple(causes), tuple(actions),
            resolution_type, resolution_type == "SOFTWARE_RECOVERY", tuple(unsupported_checks), evaluated_at,
            data_mode=diagnosis.mode,
        )

    def _evaluate_graphics_configuration(self, diagnosis: DiagnosisRunSnapshot) -> DiagnosisResult:
        diagnosis_id = str(diagnosis.diagnosis_id or "")
        evidence = self._collect_evidence(diagnosis)
        unsupported_checks = self._unsupported_checks(evidence, diagnosis)
        evaluated_at = max(
            (item.occurred_at or item.sampled_at for item in evidence if item.occurred_at or item.sampled_at),
            default=diagnosis.completed_at or "",
        )
        symptom_evidence = next(
            (
                item
                for item in evidence
                if item.metric_type == "symptom_correlation"
                and item.status == "MATCHED"
                and isinstance(item.value, dict)
                and bool(item.value.get("supported"))
            ),
            None,
        )
        problem_device = next(
            (
                item
                for item in evidence
                if item.category == "DEVICE"
                and item.metric_type == "display_device_status"
                and isinstance(item.code, int)
                and item.code != 0
                and isinstance(item.value, dict)
                and item.value.get("deviceName")
                and item.value.get("instanceId")
                and item.value.get("problemCode") == item.code
                and item.value.get("problemCodeQueryStatus") == "OK"
            ),
            None,
        )
        scenario_evidence = next(
            (
                item
                for item in evidence
                if item.metric_type == "demo_scenario"
                and item.source == "demo-scenario"
                and isinstance(item.value, dict)
                and item.value.get("dataMode") == DEMO_DATA_MODE
                and item.value.get("scenarioId") == GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID
            ),
            None,
        )
        scenario_id = (
            GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID
            if diagnosis.mode == DEMO_DATA_MODE and scenario_evidence is not None
            else None
        )
        has_physical_error_evidence = any(
            item.metric_type == "windows_event"
            and item.category in {"HARDWARE", "DEVICE", "DRIVER"}
            for item in evidence
        )

        if symptom_evidence is not None and problem_device is not None:
            device_name = str(problem_device.value["deviceName"])
            problem_code = int(problem_device.value["problemCode"])
            if problem_code == 22:
                result_title = "그래픽 장치 비활성 상태가 확인되었습니다"
                finding_title = "그래픽 장치 비활성 상태"
                state_description = "Windows에서 장치가 비활성 상태입니다."
            elif problem_code == 43:
                result_title = "그래픽 장치 오류 상태가 확인되었습니다"
                finding_title = "그래픽 장치 중지 상태"
                state_description = "장치가 문제를 보고하여 Windows가 장치를 중지한 상태입니다."
            else:
                result_title = "그래픽 장치 오류가 확인되었습니다"
                finding_title = "그래픽 장치 오류 상태"
                state_description = f"Windows가 장치 문제(problem code {problem_code})를 보고했습니다."
            remote_support_demo = (
                scenario_id == GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID
                and problem_code == 43
                and not has_physical_error_evidence
            )
            finding_actions = (
                "원격 지원으로 그래픽 장치와 드라이버 상태 점검",
                "공식 드라이버 재설치 또는 이전 버전 롤백 후 재부팅",
                "재진단 후에도 증상이 지속되면 방문 점검 전환",
            ) if remote_support_demo else ("장치 상태와 드라이버를 원격으로 점검",)
            resolution_type = "SOFTWARE_RECOVERY" if remote_support_demo else "PHYSICAL_INSPECTION"
            finding = self._finding(
                "DEVICE_DRIVER_CONFIGURATION_ISSUE",
                "WARNING",
                finding_title,
                f"{device_name} 장치에서 Windows problem code {problem_code}가 확인되었습니다. "
                f"{state_description} 이 evidence만으로 물리 고장이나 검은 화면의 직접 원인을 확정하지 않습니다.",
                (problem_device.key, symptom_evidence.key),
                ("그래픽 장치 또는 드라이버 구성 이상",) if remote_support_demo else (),
                finding_actions,
                resolution_type,
            )
            if remote_support_demo:
                summary = (
                    f"{device_name} 그래픽 장치에서 problem code 43이 확인되었습니다. "
                    "WHEA 또는 그래픽 장치의 물리 오류 이벤트 근거는 확인되지 않아 물리 고장으로 확정하지 않습니다. "
                    "원격 지원으로 드라이버를 우선 점검하고, 조치 후에도 증상이 지속될 때 방문 점검으로 전환합니다."
                )
                recommended_actions = finding_actions
            else:
                summary = (
                    f"{device_name} 그래픽 장치에서 problem code {problem_code}가 확인되었습니다. "
                    f"{state_description} Agent는 장치나 드라이버를 자동 조작하지 않으며 원격 AS 기사 점검이 필요합니다. "
                    "검은 화면 또는 화면 복구 증상의 직접 원인이나 물리 고장으로 확정하지 않습니다."
                )
                recommended_actions = (
                    "Agent가 장치를 자동 비활성화·활성화하거나 드라이버를 변경하지 않음",
                    "원격 AS 기사 점검 권장",
                    "진단 상세에서 실제 장치 근거 확인",
                )
            return DiagnosisResult(
                diagnosis_id=diagnosis_id,
                severity="WARNING",
                title=result_title,
                summary=summary,
                evidence=evidence,
                findings=(finding,),
                suspected_causes=finding.suspected_causes,
                recommended_actions=recommended_actions,
                resolution_type=resolution_type,
                can_auto_recover=False,
                unsupported_checks=tuple(unsupported_checks),
                evaluated_at=evaluated_at,
                diagnosis_type="DEVICE_DRIVER_CONFIGURATION_ISSUE",
                remote_as_recommended=True,
                data_mode=diagnosis.mode,
                scenario_id=scenario_id,
            )

        return DiagnosisResult(
            diagnosis_id=diagnosis_id,
            severity="INDETERMINATE",
            title="그래픽 장치 구성 이상을 확정할 근거가 부족합니다",
            summary=(
                "검은 화면 또는 화면 복구 증상은 전달됐지만 명확한 실제 장치 problem code를 확인하지 못했거나 장치 조회가 실패했습니다."
            ),
            evidence=evidence,
            findings=(),
            suspected_causes=(),
            recommended_actions=(
                "지원되는 권한으로 그래픽 장치 상태 다시 확인",
                "진단 상세에서 미지원·조회 실패 항목 확인",
            ),
            resolution_type="UNKNOWN",
            can_auto_recover=False,
            unsupported_checks=tuple(unsupported_checks),
            evaluated_at=evaluated_at,
            diagnosis_type="INSUFFICIENT_EVIDENCE",
            remote_as_recommended=False,
            data_mode=diagnosis.mode,
            scenario_id=scenario_id,
        )

    @staticmethod
    def _finding(
        code: str,
        severity: str,
        title: str,
        summary: str,
        evidence_keys: tuple[str, ...],
        suspected_causes: tuple[str, ...],
        recommended_actions: tuple[str, ...],
        resolution_type: str,
    ) -> DiagnosisFinding:
        return DiagnosisFinding(code, severity, title, summary, evidence_keys, suspected_causes, recommended_actions, resolution_type)

    @staticmethod
    def _collect_evidence(diagnosis: DiagnosisRunSnapshot) -> tuple[DiagnosisEvidence, ...]:
        collected: list[DiagnosisEvidence] = []
        seen: set[tuple[str, str, str, str, str]] = set()
        for task in diagnosis.tasks:
            for payload in task.evidence:
                if not isinstance(payload, dict) or not payload.get("component") or not payload.get("metricType"):
                    continue
                evidence = DiagnosisEvidence.from_dict({"taskId": task.task_id, **payload})
                instance_id = (
                    str(evidence.value.get("instanceId") or "")
                    if isinstance(evidence.value, dict)
                    else ""
                )
                identity = (
                    evidence.task_id,
                    evidence.component,
                    evidence.metric_type,
                    evidence.sampled_at,
                    instance_id,
                )
                if identity in seen:
                    continue
                seen.add(identity)
                collected.append(evidence)
        return tuple(collected)

    @staticmethod
    def _latest_evidence(evidence: tuple[DiagnosisEvidence, ...]) -> dict[str, DiagnosisEvidence]:
        latest: dict[str, DiagnosisEvidence] = {}
        for item in evidence:
            current = latest.get(item.key)
            if current is None or item.sampled_at >= current.sampled_at:
                latest[item.key] = item
        return latest

    def _unsupported_checks(
        self,
        evidence: tuple[DiagnosisEvidence, ...],
        diagnosis: DiagnosisRunSnapshot,
    ) -> list[str]:
        checks: list[str] = []
        suffixes = {
            UNSUPPORTED: "측정 불가",
            PERMISSION_REQUIRED: "권한 필요",
            FAILED: "확인 실패",
        }
        for item in evidence:
            suffix = suffixes.get(item.availability)
            if suffix:
                checks.append(f"{self.LABELS.get(item.key, item.key)} {suffix}")
        tasks_with_evidence = {item.task_id for item in evidence}
        for task in diagnosis.tasks:
            if task.task_id in tasks_with_evidence:
                continue
            if task.status == "UNSUPPORTED":
                checks.append(f"{task.task_id} 측정 불가")
            elif task.status in {"FAILED", "TIMED_OUT"}:
                checks.append(f"{task.task_id} 확인 실패")
        return self._unique(checks)

    @staticmethod
    def _number(evidence: DiagnosisEvidence | None) -> float | None:
        if evidence is None or evidence.availability != AVAILABLE or isinstance(evidence.value, bool):
            return None
        try:
            return float(evidence.value)
        except (TypeError, ValueError):
            return None

    @staticmethod
    def _truthy(evidence: DiagnosisEvidence | None) -> bool:
        if evidence is None or evidence.availability != AVAILABLE:
            return False
        return str(evidence.value).strip().casefold() in {"active", "true", "1", "yes", "on"}

    @staticmethod
    def _fan_evidence(latest: dict[str, DiagnosisEvidence]) -> DiagnosisEvidence | None:
        rpm = latest.get("gpu.fan_rpm")
        percent = latest.get("gpu.fan_percent")
        if rpm is not None and rpm.availability == AVAILABLE:
            return rpm
        if percent is not None and percent.availability == AVAILABLE:
            return percent
        return rpm or percent

    def _clock_drop(self, metrics: MetricsSnapshot, latest: dict[str, DiagnosisEvidence]) -> bool:
        clock = latest.get("gpu.clock")
        current = self._number(clock)
        history = metrics.history("gpu", "clock", limit=8)
        if current is None or len(history) < 2:
            return False
        baseline = max(history)
        return baseline > 0 and current <= baseline * self.policy.clock_drop_ratio

    @staticmethod
    def _smart_abnormal(evidence: DiagnosisEvidence | None) -> bool:
        if evidence is None or evidence.availability != AVAILABLE:
            return False
        normalized = str(evidence.value).casefold()
        return any(token in normalized for token in ("bad", "fail", "critical", "주의", "경고", "비정상"))

    @staticmethod
    def _missing_primary_components(latest: dict[str, DiagnosisEvidence]) -> tuple[str, ...]:
        requirements = {
            "cpu": ("cpu.usage",),
            "gpu": ("gpu.usage",),
            "ram": ("ram.usage",),
            "disk": ("disk.activity", "disk.usage"),
        }
        missing = []
        for component, keys in requirements.items():
            if not any(key in latest and latest[key].availability == AVAILABLE for key in keys):
                missing.append(component)
        return tuple(missing)

    @staticmethod
    def _overall_severity(findings: list[DiagnosisFinding]) -> str:
        priority = {"INFO": 1, "WARNING": 2, "CRITICAL": 3}
        return max((finding.severity for finding in findings), key=lambda value: priority.get(value, 0))

    @staticmethod
    def _overall_resolution(findings: list[DiagnosisFinding]) -> str:
        priority = {"NONE": 0, "USER_ACTION": 1, "SOFTWARE_RECOVERY": 2, "PHYSICAL_INSPECTION": 3, "UNKNOWN": 4}
        return max((finding.resolution_type for finding in findings), key=lambda value: priority.get(value, 0))

    @staticmethod
    def _presentation(profile: str, findings: list[DiagnosisFinding], severity: str) -> tuple[str, str]:
        codes = {finding.code for finding in findings}
        if profile == "GPU_COOLING_STRONG":
            return (
                "현재 냉각 시스템 또는 하드웨어의 물리적 이상 가능성이 높습니다.",
                "GPU 온도 상승, 팬 회전 상태 비정상, 열 제한 징후가 함께 감지되었습니다.\n현재 환경에서는 소프트웨어를 통한 자동 복구가 어렵습니다.",
            )
        if profile == "GPU_COOLING_UNKNOWN_FAN":
            return (
                "GPU 냉각 계통 이상 가능성이 있습니다.",
                "GPU 고온, 열 제한, 클럭 저하가 함께 감지되었습니다. 팬 회전 상태는 지원되지 않아 직접 확인이 필요합니다.",
            )
        if "DISK_SMART_ABNORMAL" in codes:
            return "디스크 하드웨어 이상 가능성이 높습니다.", "SMART가 명시적인 비정상 상태를 보고해 데이터 백업과 물리 점검이 필요합니다."
        if "RAM_PRESSURE" in codes and len(codes) == 1:
            return "사용 가능한 메모리가 부족합니다.", "RAM 사용률이 지속적으로 높아 소프트웨어 정리 또는 재시작이 필요합니다."
        if "GPU_TEMPERATURE_HIGH" in codes and len(codes) == 1:
            return "GPU 온도가 높은 상태입니다.", "냉각 이상을 확정할 복수 근거는 부족하므로 부하와 통풍 상태를 먼저 확인하세요."
        if "GPU_HIGH_LOAD" in codes and len(codes) == 1:
            return "GPU 고부하 상태가 감지되었습니다.", "온도와 열 제한은 정상 범위이므로 실행 중인 작업을 확인하세요."
        return (
            "복수의 점검 항목에서 조치가 필요한 신호가 감지되었습니다.",
            "측정된 근거를 바탕으로 우선순위가 높은 조치부터 확인하세요.",
        )

    @staticmethod
    def _unique(values: Any) -> list[str]:
        result: list[str] = []
        seen: set[str] = set()
        for value in values:
            text = str(value)
            if text and text not in seen:
                seen.add(text)
                result.append(text)
        return result


def actual_device_problem_evidence(result: DiagnosisResult | None) -> DiagnosisEvidence | None:
    if not isinstance(result, DiagnosisResult):
        return None
    return next(
        (
            item
            for item in result.evidence
            if item.category == "DEVICE"
            and item.metric_type == "display_device_status"
            and item.availability == "AVAILABLE"
            and isinstance(item.code, int)
            and item.code != 0
            and isinstance(item.value, dict)
            and bool(item.value.get("deviceName"))
            and bool(item.value.get("instanceId"))
            and item.value.get("problemCode") == item.code
            and item.value.get("problemCodeQueryStatus") == "OK"
        ),
        None,
    )


def matching_display_driver_evidence(
    result: DiagnosisResult | None,
    device_evidence: DiagnosisEvidence | None = None,
) -> DiagnosisEvidence | None:
    problem_device = device_evidence or actual_device_problem_evidence(result)
    if not isinstance(result, DiagnosisResult) or problem_device is None or not isinstance(problem_device.value, dict):
        return None
    instance_id = str(problem_device.value.get("instanceId") or "").strip().upper()
    if not instance_id:
        return None
    return next(
        (
            item
            for item in result.evidence
            if item.category == "DRIVER"
            and item.metric_type == "display_driver"
            and item.availability == "AVAILABLE"
            and isinstance(item.value, dict)
            and str(item.value.get("instanceId") or "").strip().upper() == instance_id
        ),
        None,
    )


def can_offer_as(
    result: DiagnosisResult | None,
    diagnosis: DiagnosisRunSnapshot | None,
    session: Any = None,
) -> bool:
    base_eligible = bool(
        isinstance(result, DiagnosisResult)
        and isinstance(diagnosis, DiagnosisRunSnapshot)
        and result.diagnosis_id == diagnosis.diagnosis_id
        and bool(result.evidence)
        and diagnosis.state in {"COMPLETED", "PARTIALLY_COMPLETED"}
        and diagnosis.transition_allowed
    )
    if not base_eligible or not isinstance(result, DiagnosisResult):
        return False
    request = getattr(session, "request", None)
    if result.resolution_type != "PHYSICAL_INSPECTION":
        # 이상 근거가 없어도 사용자가 AS를 접수할 수 있게 한다. 다만 서버는 웹에서 발급한
        # diagnosisId(UUID)와 증상 원문 일치를 요구하므로, 웹 접수로 시작한 세션에서만 연다.
        return bool(
            request is not None
            and getattr(request, "diagnosis_id", None) == result.diagnosis_id
            and getattr(request, "source", None) == "WEB_REQUEST"
            and bool(str(getattr(request, "symptom", "") or "").strip())
        )
    if result.diagnosis_type != "DEVICE_DRIVER_CONFIGURATION_ISSUE":
        return True
    supported_mode = bool(
        getattr(request, "mode", None) == "LIVE"
        or (
            getattr(request, "mode", None) == DEMO_DATA_MODE
            and result.data_mode == DEMO_DATA_MODE
            and result.scenario_id == GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID
        )
    )
    return bool(
        result.remote_as_recommended
        and not result.can_auto_recover
        and actual_device_problem_evidence(result) is not None
        and request is not None
        and getattr(request, "diagnosis_id", None) == result.diagnosis_id
        and getattr(request, "source", None) == "WEB_REQUEST"
        and supported_mode
        and bool(str(getattr(request, "symptom", "") or "").strip())
    )


WINDOWS_EVENT_METRIC_TYPE = "windows_event"
MAX_WINDOWS_EVENT_EVIDENCE_PER_CATEGORY = 5
MAX_RESULT_PAYLOAD_BYTES = 60_000


def compact_result_evidence(
    result: DiagnosisResult | None,
    *,
    max_events_per_category: int = MAX_WINDOWS_EVENT_EVIDENCE_PER_CATEGORY,
    max_payload_bytes: int = MAX_RESULT_PAYLOAD_BYTES,
) -> DiagnosisResult | None:
    """Windows 이벤트 로그 근거를 상한선까지만 남긴다.

    이벤트 1건이 evidence 1건이라 최대 300건까지 쌓이고, 그대로 직렬화하면 서버 WebSocket
    텍스트 프레임 한계를 넘겨 연결이 끊긴다. 판정(findings)은 이 함수 호출 전에 이미
    끝나므로 근거 목록만 줄어들 뿐 진단 결과는 달라지지 않는다. 판정이 참조하는 근거와
    이벤트가 아닌 계측 근거는 언제나 보존한다.
    """
    if not isinstance(result, DiagnosisResult) or not result.evidence:
        return result
    referenced = {key for finding in result.findings for key in finding.evidence_keys}
    kept: list[DiagnosisEvidence] = []
    per_category: dict[str, int] = {}
    for item in result.evidence:
        if item.metric_type != WINDOWS_EVENT_METRIC_TYPE or item.key in referenced:
            kept.append(item)
            continue
        category = item.category or ""
        per_category[category] = per_category.get(category, 0) + 1
        if per_category[category] <= max(0, max_events_per_category):
            kept.append(item)

    def droppable(index: int) -> bool:
        item = kept[index]
        return item.metric_type == WINDOWS_EVENT_METRIC_TYPE and item.key not in referenced

    compacted = replace(result, evidence=tuple(kept))
    while _payload_bytes(compacted) > max_payload_bytes:
        drop_at = next((index for index in range(len(kept) - 1, -1, -1) if droppable(index)), None)
        if drop_at is None:
            break
        kept.pop(drop_at)
        compacted = replace(result, evidence=tuple(kept))
    return compacted


def _payload_bytes(result: DiagnosisResult) -> int:
    return len(json.dumps(result.to_dict(), ensure_ascii=False).encode("utf-8"))


def format_diagnosis_result_detail(
    result: DiagnosisResult,
    diagnosis: DiagnosisRunSnapshot | None,
) -> str:
    used_keys = {key for finding in result.findings for key in finding.evidence_keys}
    measured = []
    unavailable = []
    failures = []
    for item in result.evidence:
        label = DiagnosisRuleEngine.LABELS.get(item.key, item.key)
        if item.availability == AVAILABLE:
            value = f"{item.value}{item.unit}" if item.unit else str(item.value)
            used = " · 판정 근거" if item.key in used_keys else ""
            measured.append(f"- {label}: {value} / {item.sampled_at} / {item.source}{used}")
        elif item.availability in {UNSUPPORTED, PERMISSION_REQUIRED}:
            unavailable.append(f"- {label}: {item.failure_reason or item.availability}")
        else:
            failures.append(f"- {label}: {item.failure_reason or item.error_code or '확인 실패'}")
    log_lines = []
    if isinstance(diagnosis, DiagnosisRunSnapshot):
        log_lines = [f"- {event.timestamp} {event.message}" for event in diagnosis.events[-5:]]
    sections = [
        "[실제 측정값]",
        *(measured or ["- 확인된 측정값 없음"]),
        "",
        "[측정 불가 검사]",
        *(unavailable or ["- 없음"]),
        "",
        "[검사 실패]",
        *(failures or ["- 없음"]),
        "",
        "[진단 로그 요약]",
        *(log_lines or ["- 저장된 진단 로그 없음"]),
    ]
    return "\n".join(sections)
