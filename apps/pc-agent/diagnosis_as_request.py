from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Callable
from urllib.parse import quote, urljoin, urlparse, urlunparse

from diagnosis_request_agent import DiagnosisSession, WEB_REQUEST
from diagnosis_result import (
    WINDOWS_EVENT_METRIC_TYPE,
    DiagnosisEvidence,
    DiagnosisResult,
    actual_device_problem_evidence,
    matching_display_driver_evidence,
)
from pc_agent_demo_scenarios import GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID


AS_REQUEST_PATH = "/api/agent/as-requests"
AS_REQUEST_TYPE = "PHYSICAL_INSPECTION"
SUCCESS_RESPONSE_STATUSES = frozenset(
    {"OPEN", "CREATED", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED", "CANCELLED"}
)


class DiagnosisAsRequestError(RuntimeError):
    code = "AS_REQUEST_ERROR"
    retryable = False

    def __init__(self, message: str, *, status_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code


class DiagnosisAsValidationError(DiagnosisAsRequestError):
    code = "AS_VALIDATION_FAILED"


class DiagnosisAsConsentRequiredError(DiagnosisAsValidationError):
    code = "AS_CONSENT_REQUIRED"


class DiagnosisAsAuthenticationError(DiagnosisAsRequestError):
    code = "AS_AUTHENTICATION_REQUIRED"


class DiagnosisAsAuthorizationError(DiagnosisAsRequestError):
    code = "AS_FORBIDDEN"


class DiagnosisAsNetworkError(DiagnosisAsRequestError):
    code = "AS_NETWORK_ERROR"
    retryable = True


class DiagnosisAsServerError(DiagnosisAsRequestError):
    code = "AS_SERVER_ERROR"
    retryable = True


class DiagnosisAsHttpError(DiagnosisAsRequestError):
    code = "AS_HTTP_ERROR"


class DiagnosisAsResponseError(DiagnosisAsRequestError):
    code = "AS_INVALID_RESPONSE"


@dataclass(frozen=True)
class DiagnosisAsRequest:
    diagnosis_id: str
    device_id: str
    request_type: str
    symptom: str
    diagnosis_title: str
    diagnosis_summary: str
    evidence_summary: tuple[dict[str, Any], ...]
    diagnosed_at: str
    mode: str
    result_id: str
    consent_accepted: bool = True

    def to_dict(self) -> dict[str, Any]:
        return {
            "diagnosisId": self.diagnosis_id,
            "deviceId": self.device_id,
            "requestType": self.request_type,
            "symptom": self.symptom,
            "diagnosisTitle": self.diagnosis_title,
            "diagnosisSummary": self.diagnosis_summary,
            "evidenceSummary": [dict(item) for item in self.evidence_summary],
            "diagnosedAt": self.diagnosed_at,
            "mode": self.mode,
            "resultId": self.result_id,
            "consentAccepted": self.consent_accepted,
        }


@dataclass(frozen=True)
class DiagnosisAsResponse:
    request_id: str
    request_number: str
    status: str
    created_at: str
    web_url: str
    request_type: str | None = None
    symptom: str | None = None
    diagnosis_title: str | None = None
    diagnosis_summary: str | None = None
    mode: str | None = None

    @classmethod
    def from_dict(cls, payload: Any, *, web_base_url: str) -> "DiagnosisAsResponse":
        if not isinstance(payload, dict):
            raise DiagnosisAsResponseError("AS 요청 응답이 JSON 객체가 아닙니다.")
        request_id = _required_response_text(payload, "requestId")
        request_number = _required_response_text(payload, "requestNumber")
        status = _required_response_text(payload, "status")
        if status.upper() not in SUCCESS_RESPONSE_STATUSES:
            raise DiagnosisAsResponseError("AS 요청이 완료 상태로 확인되지 않았습니다.")
        created_at = _required_response_text(payload, "createdAt")
        _require_iso8601(created_at, "createdAt", DiagnosisAsResponseError)
        raw_web_url = payload.get("webUrl") or payload.get("webPath")
        if raw_web_url is not None and not isinstance(raw_web_url, str):
            raise DiagnosisAsResponseError("AS 요청 응답의 webUrl 형식이 잘못되었습니다.")
        return cls(
            request_id,
            request_number,
            status,
            created_at,
            resolve_as_web_url(web_base_url, raw_web_url, request_id),
            _optional_response_text(payload, "requestType"),
            _optional_response_text(payload, "symptom"),
            _optional_response_text(payload, "diagnosisTitle"),
            _optional_response_text(payload, "diagnosisSummary"),
            _optional_response_text(payload, "mode"),
        )


def build_diagnosis_as_request(
    session: DiagnosisSession,
    result: DiagnosisResult,
    *,
    consent_accepted: bool,
    expected_device_id: str | None = None,
) -> DiagnosisAsRequest:
    if not consent_accepted:
        raise DiagnosisAsConsentRequiredError("진단 정보 전송 동의가 필요합니다.")
    if not isinstance(session, DiagnosisSession) or not isinstance(result, DiagnosisResult):
        raise DiagnosisAsValidationError("진단 세션과 결과가 필요합니다.")
    request = session.request
    if session.agent_state != "COMPLETED":
        raise DiagnosisAsValidationError("완료된 진단 세션만 AS 요청을 생성할 수 있습니다.")
    if not request.diagnosis_id or request.diagnosis_id != result.diagnosis_id:
        raise DiagnosisAsValidationError("진단 세션과 결과의 diagnosisId가 일치하지 않습니다.")
    if not request.device_id:
        raise DiagnosisAsValidationError("진단 세션에 deviceId가 없습니다.")
    if expected_device_id is not None and request.device_id != expected_device_id.strip():
        raise DiagnosisAsValidationError("현재 Agent 장치와 진단 요청의 deviceId가 일치하지 않습니다.")
    if request.mode not in {"LIVE", "DEMO"}:
        raise DiagnosisAsValidationError("진단 모드는 LIVE 또는 DEMO여야 합니다.")
    if result.resolution_type != AS_REQUEST_TYPE and (
        request.source != WEB_REQUEST or not request.symptom.strip()
    ):
        # 이상 근거가 없는 결과(정상·근거 부족)도 접수할 수 있게 허용하되, 서버가 증상 원문
        # 일치를 검증하므로 웹에서 시작한 진단 세션에서만 연다.
        raise DiagnosisAsValidationError("웹에서 전달된 증상이 있는 진단 세션에서만 AS 요청을 생성할 수 있습니다.")
    if result.diagnosis_type == "DEVICE_DRIVER_CONFIGURATION_ISSUE":
        supported_mode = request.mode == "LIVE" or (
            request.mode == "DEMO"
            and result.data_mode == "DEMO"
            and result.scenario_id == GRAPHICS_CODE43_REMOTE_SUPPORT_SCENARIO_ID
        )
        if request.source != WEB_REQUEST or not supported_mode or not request.symptom.strip():
            raise DiagnosisAsValidationError(
                "웹에서 전달된 LIVE 진단 또는 검증된 Code 43 시연 진단이 필요합니다."
            )
        if result.can_auto_recover or not result.remote_as_recommended:
            raise DiagnosisAsValidationError("로컬 자동 복구 불가·원격 기사 점검 결과만 AS 요청을 생성할 수 있습니다.")
        if actual_device_problem_evidence(result) is None:
            raise DiagnosisAsValidationError("실제 problem code가 있는 Display 장치 근거가 필요합니다.")
    if not result.title.strip() or not result.summary.strip():
        raise DiagnosisAsValidationError("진단 결과 제목과 요약이 필요합니다.")
    if not result.evaluated_at.strip():
        raise DiagnosisAsValidationError("진단 시각이 없습니다.")
    _require_iso8601(result.evaluated_at, "evaluatedAt", DiagnosisAsValidationError)
    evidence_summary = _result_evidence_summary(result)
    if not evidence_summary:
        raise DiagnosisAsValidationError("AS 요청에 사용할 실제 측정 근거가 없습니다.")
    return DiagnosisAsRequest(
        diagnosis_id=request.diagnosis_id,
        device_id=request.device_id,
        request_type=AS_REQUEST_TYPE,
        symptom=request.symptom,
        diagnosis_title=result.title,
        diagnosis_summary=result.summary,
        evidence_summary=evidence_summary,
        diagnosed_at=result.evaluated_at,
        mode=request.mode,
        result_id=result.result_id,
    )


class DiagnosisAsRequestClient:
    def __init__(
        self,
        api_base_url: str,
        agent_token: str | None,
        *,
        web_base_url: str | None = None,
        device_id: str | None = None,
        timeout_seconds: int = 15,
        opener: Callable[..., Any] | None = None,
    ) -> None:
        self.api_base_url = _normalize_base_url(api_base_url, "apiBaseUrl")
        self.agent_token = agent_token.strip() if isinstance(agent_token, str) else ""
        self.web_base_url = derive_web_base_url(self.api_base_url, web_base_url)
        self.device_id = device_id.strip() if isinstance(device_id, str) and device_id.strip() else None
        self.timeout_seconds = timeout_seconds
        self.opener = opener or urllib.request.urlopen

    @property
    def endpoint(self) -> str:
        return self.api_base_url + AS_REQUEST_PATH

    def create(
        self,
        session: DiagnosisSession,
        result: DiagnosisResult,
        *,
        consent_accepted: bool,
    ) -> DiagnosisAsResponse:
        if not self.agent_token:
            raise DiagnosisAsAuthenticationError("Agent 인증 정보가 없습니다.")
        request_payload = build_diagnosis_as_request(
            session,
            result,
            consent_accepted=consent_accepted,
            expected_device_id=self.device_id,
        )
        return self.create_request(request_payload)

    def create_request(self, request_payload: DiagnosisAsRequest) -> DiagnosisAsResponse:
        if not isinstance(request_payload, DiagnosisAsRequest):
            raise DiagnosisAsValidationError("AS 요청 데이터 형식이 올바르지 않습니다.")
        if self.device_id is not None and request_payload.device_id != self.device_id:
            raise DiagnosisAsValidationError("현재 Agent 장치와 AS 요청의 deviceId가 일치하지 않습니다.")
        if not request_payload.consent_accepted:
            raise DiagnosisAsConsentRequiredError("진단 정보 전송 동의가 필요합니다.")
        if not self.agent_token:
            raise DiagnosisAsAuthenticationError("Agent 인증 정보가 없습니다.")
        body = json.dumps(request_payload.to_dict(), ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            self.endpoint,
            data=body,
            method="POST",
            headers={
                "Accept": "application/json",
                "Authorization": f"Bearer {self.agent_token}",
                "Content-Type": "application/json; charset=utf-8",
                "Idempotency-Key": request_payload.diagnosis_id,
            },
        )
        try:
            with self.opener(request, timeout=self.timeout_seconds) as response:
                response_body = response.read()
        except urllib.error.HTTPError as exception:
            if exception.code == 401:
                raise DiagnosisAsAuthenticationError(
                    "Agent 인증이 만료되었습니다. Agent 재연결 또는 장치 재등록 후 다시 시도해 주세요.",
                    status_code=exception.code,
                ) from exception
            if exception.code == 403:
                raise DiagnosisAsAuthorizationError(
                    "현재 Agent에는 AS 요청 권한이 없습니다.", status_code=exception.code
                ) from exception
            if exception.code >= 500:
                raise DiagnosisAsServerError(
                    f"AS 요청 저장에 실패했습니다. 잠시 후 다시 시도해 주세요. (HTTP {exception.code})",
                    status_code=exception.code,
                ) from exception
            raise DiagnosisAsHttpError(
                f"AS 요청이 거절되었습니다. 전송 정보를 확인해 주세요. (HTTP {exception.code})",
                status_code=exception.code,
            ) from exception
        except (urllib.error.URLError, TimeoutError, OSError) as exception:
            raise DiagnosisAsNetworkError(
                "AS 요청 서버에 연결할 수 없습니다. 네트워크 연결을 확인한 뒤 다시 시도해 주세요."
            ) from exception
        try:
            payload = json.loads(response_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exception:
            raise DiagnosisAsResponseError("AS 요청 응답을 JSON으로 해석할 수 없습니다.") from exception
        return DiagnosisAsResponse.from_dict(payload, web_base_url=self.web_base_url)


DiagnosisAsClient = DiagnosisAsRequestClient


def derive_web_base_url(api_base_url: str, configured_web_base_url: str | None) -> str:
    if isinstance(configured_web_base_url, str) and configured_web_base_url.strip():
        return _normalize_base_url(configured_web_base_url, "webBaseUrl")
    parsed = urlparse(api_base_url)
    if parsed.port == 8080:
        host = parsed.hostname or ""
        if ":" in host and not host.startswith("["):
            host = f"[{host}]"
        return urlunparse((parsed.scheme, f"{host}:5173", parsed.path.rstrip("/"), "", "", ""))
    return api_base_url.rstrip("/")


def resolve_as_web_url(web_base_url: str, web_url: str | None, request_id: str) -> str:
    allowed_base = _normalize_base_url(web_base_url, "webBaseUrl")
    raw = web_url.strip() if isinstance(web_url, str) else ""
    resolved = urljoin(allowed_base + "/", raw) if raw else f"{allowed_base}/support/{quote(request_id, safe='')}"
    parsed = urlparse(resolved)
    allowed = urlparse(allowed_base)
    if parsed.scheme not in {"http", "https"} or parsed.username or parsed.password or parsed.fragment:
        raise DiagnosisAsResponseError("AS 요청 웹 URL 형식이 안전하지 않습니다.")
    if _url_origin(parsed) != _url_origin(allowed):
        raise DiagnosisAsResponseError("AS 요청 웹 URL이 허용된 웹 주소와 일치하지 않습니다.")
    return resolved


def _result_evidence_summary(result: DiagnosisResult) -> tuple[dict[str, Any], ...]:
    references: dict[str, list[str]] = {}
    for finding in result.findings:
        for key in finding.evidence_keys:
            references.setdefault(key, []).append(finding.code)
    included_keys = set(references)
    problem_device = actual_device_problem_evidence(result)
    display_driver = matching_display_driver_evidence(result, problem_device)
    if result.diagnosis_type == "DEVICE_DRIVER_CONFIGURATION_ISSUE" and problem_device is not None:
        included_keys.add(problem_device.key)
        if display_driver is not None:
            included_keys.add(display_driver.key)
    if not included_keys:
        # 정상·근거 부족 결과는 findings가 없어 참조 키가 비는데, 그래도 접수는 허용한다.
        # 서버가 제출 근거를 저장된 진단 결과와 대조하므로 실측 근거를 그대로 싣는다.
        included_keys = {
            item.key
            for item in result.evidence
            if _usable_evidence(item) and item.metric_type != WINDOWS_EVENT_METRIC_TYPE
        }
    summary: list[dict[str, Any]] = []
    for item in result.evidence:
        if not _usable_evidence(item) or item.key not in included_keys:
            continue
        evidence_item = {
            "component": item.component,
            "metricType": item.metric_type,
            "value": item.value,
            "unit": item.unit,
            "status": item.status,
            "source": item.source,
            "sampledAt": item.sampled_at,
            "findingCodes": list(dict.fromkeys(references.get(item.key, ()))),
        }
        if item.category:
            evidence_item["category"] = item.category
        if item.code is not None:
            evidence_item["code"] = item.code
        if item.occurred_at:
            evidence_item["occurredAt"] = item.occurred_at
        if item.description:
            evidence_item["description"] = item.description
        if problem_device is not None and item is problem_device:
            evidence_item.update({
                "diagnosisType": result.diagnosis_type,
                "resolutionType": result.resolution_type,
                "canAutoRecover": result.can_auto_recover,
                "remoteAsRecommended": result.remote_as_recommended,
            })
        summary.append(evidence_item)
    return tuple(summary)


def _usable_evidence(item: DiagnosisEvidence) -> bool:
    return bool(
        item.availability == "AVAILABLE"
        and item.value is not None
        and item.component.strip()
        and item.metric_type.strip()
        and item.source.strip()
        and item.sampled_at.strip()
    )


def _normalize_base_url(value: str, field: str) -> str:
    text = value.strip() if isinstance(value, str) else ""
    parsed = urlparse(text)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.username or parsed.password:
        raise DiagnosisAsValidationError(f"{field} 형식이 올바르지 않습니다.")
    if parsed.query or parsed.fragment:
        raise DiagnosisAsValidationError(f"{field}에는 query 또는 fragment를 사용할 수 없습니다.")
    return text.rstrip("/")


def _url_origin(parsed: Any) -> tuple[str, str, int]:
    return parsed.scheme.lower(), (parsed.hostname or "").lower(), parsed.port or (443 if parsed.scheme == "https" else 80)


def _required_response_text(payload: dict[str, Any], field: str) -> str:
    value = payload.get(field)
    if not isinstance(value, str) or not value.strip():
        raise DiagnosisAsResponseError(f"AS 요청 응답에 {field}가 없습니다.")
    return value.strip()


def _optional_response_text(payload: dict[str, Any], field: str) -> str | None:
    value = payload.get(field)
    if value is None:
        return None
    if not isinstance(value, str) or not value.strip():
        raise DiagnosisAsResponseError(f"AS 요청 응답의 {field} 형식이 잘못되었습니다.")
    return value.strip()


def _require_iso8601(value: str, field: str, error_type: type[DiagnosisAsRequestError]) -> None:
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        datetime.fromisoformat(text)
    except ValueError as exception:
        raise error_type(f"{field}는 ISO-8601 시각이어야 합니다.") from exception
