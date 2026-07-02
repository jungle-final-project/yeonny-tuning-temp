from pathlib import Path

try:
    import yaml
except ModuleNotFoundError as exc:
    raise SystemExit("PyYAML is required to validate docs/openapi.yaml") from exc


OPENAPI_PATH = Path("docs/openapi.yaml")

TOOL_PATHS = [
    "/api/tools/compatibility/check",
    "/api/tools/power/check",
    "/api/tools/size/check",
    "/api/tools/performance/check",
    "/api/tools/price/check",
]

REQUIRED_PATHS = [
    "/api/health",
    "/api/users",
    "/api/auth/login",
    "/api/auth/refresh",
    "/api/auth/logout",
    "/api/auth/me",
    "/api/auth/google/start",
    "/api/auth/google/callback",
    "/api/auth/exchange",
    "/api/requirements/parse",
    "/api/builds/recommend",
    "/api/builds/{id}",
    "/api/builds/history",
    "/api/builds/{id}/change-part",
    "/api/ai/build-chat",
    "/api/parts",
    "/api/parts/{id}",
    "/api/quote-drafts/current/apply-ai-build",
    *TOOL_PATHS,
    "/api/price-alerts",
    "/api/admin/price-jobs",
    "/api/admin/price-jobs/run",
    "/api/ai/agent-sessions",
    "/api/ai/agent-sessions/{id}/run",
    "/api/ai/agent-sessions/{id}",
    "/api/rag/search",
    "/api/rag/evidence/{id}",
    "/api/agent-logs/upload",
    "/api/agent-logs/{id}",
    "/api/as-tickets",
    "/api/as-tickets/{id}",
    "/api/admin/dashboard",
    "/api/admin/audit-logs/recent",
    "/api/admin/agent-sessions",
    "/api/admin/agent-sessions/{id}",
    "/api/admin/tool-invocations",
    "/api/admin/tool-invocations/{id}",
    "/api/admin/rag-evidence",
    "/api/admin/rag-evidence/{id}",
    "/api/admin/as-tickets",
    "/api/admin/as-tickets/{id}",
]

POST_JSON_REQUEST_SCHEMAS = {
    "/api/users": "SignupRequest",
    "/api/auth/login": "LoginRequest",
    "/api/auth/refresh": "RefreshRequest",
    "/api/auth/logout": "RefreshRequest",
    "/api/auth/exchange": "AuthExchangeRequest",
    "/api/requirements/parse": "RequirementParseRequest",
    "/api/builds/recommend": "BuildRecommendRequest",
    "/api/builds/{id}/change-part": "ChangePartRequest",
    "/api/ai/build-chat": "AiBuildChatRequest",
    "/api/price-alerts": "PriceAlertCreateRequest",
    "/api/ai/agent-sessions": "AgentSessionCreateRequest",
    "/api/as-tickets": "AsTicketCreateRequest",
}

PUT_JSON_REQUEST_SCHEMAS = {
    "/api/quote-drafts/current/apply-ai-build": "AiBuildApplyRequest",
}

REQUIRED_SCHEMAS = [
    "ErrorResponse",
    "AuthResponse",
    "ChangePartRequest",
    "AiBuildChatRequest",
    "AiBuildChatResponse",
    "AiBuildApplyRequest",
    "QuoteDraftDto",
    "ToolCheckRequest",
    "ToolCheckResponse",
    "AgentLogUploadRequest",
    "AgentSessionDto",
    "ToolInvocationDto",
    "RagEvidenceDto",
    "AdminRagEvidenceDto",
]

REQUIRED_ERROR_CODES = {
    "VALIDATION_ERROR",
    "UNAUTHORIZED",
    "FORBIDDEN",
    "NOT_FOUND",
    "CONFLICT_STATE",
    "DUPLICATE_RESOURCE",
    "FILE_VALIDATION_ERROR",
    "INTERNAL_ERROR",
}


def ref_name(schema: dict) -> str | None:
    ref = schema.get("$ref")
    if not ref:
        return None
    return ref.removeprefix("#/components/schemas/")


def request_schema_ref(operation: dict, content_type: str = "application/json") -> str | None:
    schema = (
        operation.get("requestBody", {})
        .get("content", {})
        .get(content_type, {})
        .get("schema", {})
    )
    return ref_name(schema)


def validate_json_request_schemas(
    paths: dict, method: str, request_schemas: dict[str, str]
) -> None:
    for path, schema_name in request_schemas.items():
        operation = paths.get(path, {}).get(method)
        if not operation:
            raise SystemExit(f"Missing {method.upper()} operation for {path}")

        if request_schema_ref(operation) != schema_name:
            raise SystemExit(f"{path} must reference {schema_name}")


def main() -> None:
    with OPENAPI_PATH.open(encoding="utf-8") as file:
        spec = yaml.safe_load(file)

    if spec.get("openapi") != "3.0.3":
        raise SystemExit("docs/openapi.yaml must declare openapi: 3.0.3")

    paths = spec.get("paths", {})
    missing_paths = [path for path in REQUIRED_PATHS if path not in paths]
    if missing_paths:
        raise SystemExit(f"Missing OpenAPI paths: {', '.join(missing_paths)}")

    if "/api/tools/{tool}/check" in paths:
        raise SystemExit("Use five concrete Tool endpoints, not /api/tools/{tool}/check")

    if "/api/price-snapshots/collect" in paths:
        raise SystemExit("Public /api/price-snapshots/collect is excluded from V1")

    schemas = spec.get("components", {}).get("schemas", {})
    missing_schemas = [schema for schema in REQUIRED_SCHEMAS if schema not in schemas]
    if missing_schemas:
        raise SystemExit(f"Missing OpenAPI schemas: {', '.join(missing_schemas)}")

    validate_json_request_schemas(paths, "post", POST_JSON_REQUEST_SCHEMAS)
    validate_json_request_schemas(paths, "put", PUT_JSON_REQUEST_SCHEMAS)

    for path in TOOL_PATHS:
        post = paths.get(path, {}).get("post")
        if not post:
            raise SystemExit(f"Missing POST operation for {path}")
        request_body = post.get("requestBody", {})
        if request_body.get("$ref") != "#/components/requestBodies/ToolCheckRequestBody":
            raise SystemExit(f"{path} must use ToolCheckRequestBody")

    upload_post = paths["/api/agent-logs/upload"].get("post", {})
    upload_schema = request_schema_ref(upload_post, "multipart/form-data")
    if upload_schema != "AgentLogUploadRequest":
        raise SystemExit("/api/agent-logs/upload must use multipart/form-data AgentLogUploadRequest")

    auth_properties = schemas["AuthResponse"].get("properties", {})
    for field in ["accessToken", "refreshToken", "user"]:
        if field not in auth_properties:
            raise SystemExit(f"AuthResponse missing {field}")
    if "token" in auth_properties:
        raise SystemExit("AuthResponse must not use legacy token field")

    change_part_properties = schemas["ChangePartRequest"].get("properties", {})
    if {"category", "partId"} - set(change_part_properties):
        raise SystemExit("ChangePartRequest must use category and partId")
    if {"itemId", "replacementPartId"} & set(change_part_properties):
        raise SystemExit("ChangePartRequest must not use legacy itemId/replacementPartId")

    error_code_enum = set(
        schemas["ErrorResponse"]
        .get("properties", {})
        .get("code", {})
        .get("enum", [])
    )
    missing_error_codes = REQUIRED_ERROR_CODES - error_code_enum
    if missing_error_codes:
        raise SystemExit(f"ErrorResponse missing codes: {', '.join(sorted(missing_error_codes))}")

    print(f"OpenAPI validation passed: {len(paths)} paths")


if __name__ == "__main__":
    main()
