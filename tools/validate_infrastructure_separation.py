from __future__ import annotations

from pathlib import Path
from typing import Any

try:
    import yaml
except ModuleNotFoundError as exc:
    raise SystemExit(
        "PyYAML is required: python -m pip install -r tools/requirements.txt"
    ) from exc


APPLICATION_CONFIG_PATH = Path("apps/api/src/main/resources/application.yml")
API_COMPOSE_PATH = Path("compose.api.prod.yaml")
API_NGINX_CONFIG_PATH = Path("infra/nginx/api.conf")

TARGET_SERVICES = {"nginx", "api", "xgb-reranker"}
PROHIBITED_SERVICES = {"web", "postgres", "redis", "rabbitmq", "mailpit"}

REQUIRED_API_ENVIRONMENT = {
    "SPRING_DATASOURCE_URL",
    "SPRING_DATASOURCE_USERNAME",
    "SPRING_DATASOURCE_PASSWORD",
    "SPRING_DATA_REDIS_HOST",
    "SPRING_DATA_REDIS_PORT",
    "SPRING_DATA_REDIS_USERNAME",
    "SPRING_DATA_REDIS_PASSWORD",
    "SPRING_DATA_REDIS_SSL_ENABLED",
    "SPRING_RABBITMQ_ADDRESSES",
    "SPRING_RABBITMQ_USERNAME",
    "SPRING_RABBITMQ_PASSWORD",
    "SPRING_RABBITMQ_SSL_ENABLED",
}

REQUIRED_XGB_DB_ENVIRONMENT = {
    "RECOMMENDATION_DB_HOST",
    "RECOMMENDATION_DB_PORT",
    "RECOMMENDATION_DB_NAME",
    "RECOMMENDATION_DB_USER",
    "RECOMMENDATION_DB_PASSWORD",
}

APPLICATION_ENVIRONMENT_CONTRACT = {
    ("spring", "datasource", "url"): "SPRING_DATASOURCE_URL",
    ("spring", "datasource", "username"): "SPRING_DATASOURCE_USERNAME",
    ("spring", "datasource", "password"): "SPRING_DATASOURCE_PASSWORD",
    ("spring", "data", "redis", "host"): "SPRING_DATA_REDIS_HOST",
    ("spring", "data", "redis", "port"): "SPRING_DATA_REDIS_PORT",
    ("spring", "data", "redis", "username"): "SPRING_DATA_REDIS_USERNAME",
    ("spring", "data", "redis", "password"): "SPRING_DATA_REDIS_PASSWORD",
    ("spring", "data", "redis", "ssl", "enabled"): "SPRING_DATA_REDIS_SSL_ENABLED",
    ("spring", "rabbitmq", "addresses"): "SPRING_RABBITMQ_ADDRESSES",
    ("spring", "rabbitmq", "username"): "SPRING_RABBITMQ_USERNAME",
    ("spring", "rabbitmq", "password"): "SPRING_RABBITMQ_PASSWORD",
    ("spring", "rabbitmq", "ssl", "enabled"): "SPRING_RABBITMQ_SSL_ENABLED",
}


def nested_value(mapping: dict[str, Any], path: tuple[str, ...]) -> Any:
    value: Any = mapping
    for key in path:
        if not isinstance(value, dict) or key not in value:
            return None
        value = value[key]
    return value


def environment_names(environment: Any) -> set[str]:
    if isinstance(environment, dict):
        return {str(name) for name in environment}
    if isinstance(environment, list):
        return {
            str(entry).split("=", 1)[0]
            for entry in environment
            if isinstance(entry, str) and entry
        }
    return set()


def dependency_names(depends_on: Any) -> set[str]:
    if isinstance(depends_on, dict):
        return {str(name) for name in depends_on}
    if isinstance(depends_on, list):
        return {str(name) for name in depends_on}
    return set()


def validate_application_config(config: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    for path, environment_name in APPLICATION_ENVIRONMENT_CONTRACT.items():
        value = nested_value(config, path)
        if environment_name not in str(value or ""):
            dotted_path = ".".join(path)
            errors.append(
                f"{dotted_path} must be configurable with {environment_name}"
            )

    rabbitmq_addresses = nested_value(config, ("spring", "rabbitmq", "addresses"))
    if "SPRING_RABBITMQ_HOST" not in str(rabbitmq_addresses or ""):
        errors.append(
            "spring.rabbitmq.addresses must keep SPRING_RABBITMQ_HOST as the Blue deployment fallback"
        )
    return errors


def validate_api_compose(compose: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    services = compose.get("services")
    if not isinstance(services, dict):
        return ["compose.api.prod.yaml must define a services mapping"]

    service_names = {str(name) for name in services}
    for service in sorted(TARGET_SERVICES - service_names):
        errors.append(f"compose.api.prod.yaml is missing required service: {service}")
    for service in sorted(PROHIBITED_SERVICES & service_names):
        errors.append(f"compose.api.prod.yaml must not contain service: {service}")

    unexpected = service_names - TARGET_SERVICES - PROHIBITED_SERVICES
    for service in sorted(unexpected):
        errors.append(f"compose.api.prod.yaml contains unexpected service: {service}")

    api = services.get("api", {})
    if not isinstance(api, dict):
        errors.append("api service must be a mapping")
    else:
        api_environment = environment_names(api.get("environment"))
        for name in sorted(REQUIRED_API_ENVIRONMENT - api_environment):
            errors.append(f"api service environment is missing {name}")
        if api.get("ports"):
            errors.append("api service must not publish ports; nginx is the only origin entrypoint")
        prohibited_dependencies = dependency_names(api.get("depends_on")) & PROHIBITED_SERVICES
        for service in sorted(prohibited_dependencies):
            errors.append(f"api service must not depend_on managed service container: {service}")

    xgb = services.get("xgb-reranker", {})
    if not isinstance(xgb, dict):
        errors.append("xgb-reranker service must be a mapping")
    else:
        xgb_environment = environment_names(xgb.get("environment"))
        for name in sorted(REQUIRED_XGB_DB_ENVIRONMENT - xgb_environment):
            errors.append(f"xgb-reranker environment is missing {name}")

    return errors


def validate_nginx_config(config: str) -> list[str]:
    errors: list[str] = []
    for marker in [
        "location /api/",
        "location /ws/",
        "proxy_pass http://api:8080",
        "proxy_set_header Upgrade $http_upgrade",
        "proxy_set_header Connection $connection_upgrade",
    ]:
        if marker not in config:
            errors.append(f"API nginx config is missing: {marker}")

    static_markers = [
        "root /usr/share/nginx/html",
        "location /assets/",
        "try_files ",
        "/index.html",
    ]
    if any(marker in config for marker in static_markers):
        errors.append("API nginx config must not serve React static files")

    return errors


def load_yaml(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as file:
        value = yaml.safe_load(file)
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a YAML mapping")
    return value


def validate_repository(root: Path) -> list[str]:
    errors: list[str] = []
    targets = [
        (APPLICATION_CONFIG_PATH, validate_application_config, "yaml"),
        (API_COMPOSE_PATH, validate_api_compose, "yaml"),
        (API_NGINX_CONFIG_PATH, validate_nginx_config, "text"),
    ]

    for relative_path, validate, kind in targets:
        path = root / relative_path
        if not path.is_file():
            errors.append(f"missing target file: {relative_path.as_posix()}")
            continue
        try:
            value = load_yaml(path) if kind == "yaml" else path.read_text(encoding="utf-8")
            errors.extend(validate(value))
        except (OSError, ValueError, yaml.YAMLError) as exc:
            errors.append(f"failed to read {relative_path.as_posix()}: {exc}")

    return errors


def main() -> None:
    errors = validate_repository(Path.cwd())
    if errors:
        print("Infrastructure separation validation failed:")
        for error in errors:
            print(f"- {error}")
        raise SystemExit(1)
    print("Infrastructure separation validation passed")


if __name__ == "__main__":
    main()
