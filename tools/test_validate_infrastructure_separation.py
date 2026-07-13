import tempfile
import unittest
from pathlib import Path

from tools import validate_infrastructure_separation as validator


class InfrastructureSeparationValidatorTest(unittest.TestCase):
    def application_config(self) -> dict:
        return {
            "spring": {
                "datasource": {
                    "url": "${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/buildgraph}",
                    "username": "${SPRING_DATASOURCE_USERNAME:buildgraph}",
                    "password": "${SPRING_DATASOURCE_PASSWORD:buildgraph}",
                },
                "data": {
                    "redis": {
                        "host": "${SPRING_DATA_REDIS_HOST:localhost}",
                        "port": "${SPRING_DATA_REDIS_PORT:6379}",
                        "username": "${SPRING_DATA_REDIS_USERNAME:}",
                        "password": "${SPRING_DATA_REDIS_PASSWORD:}",
                        "ssl": {
                            "enabled": "${SPRING_DATA_REDIS_SSL_ENABLED:false}",
                        },
                    }
                },
                "rabbitmq": {
                    "addresses": "${SPRING_RABBITMQ_ADDRESSES:localhost:5672}",
                    "username": "${SPRING_RABBITMQ_USERNAME:buildgraph}",
                    "password": "${SPRING_RABBITMQ_PASSWORD:buildgraph}",
                    "ssl": {
                        "enabled": "${SPRING_RABBITMQ_SSL_ENABLED:false}",
                    },
                },
            }
        }

    def api_compose(self) -> dict:
        return {
            "services": {
                "nginx": {
                    "ports": ["80:80"],
                    "depends_on": ["api"],
                },
                "api": {
                    "environment": {
                        name: "${" + name + ":?required}"
                        for name in validator.REQUIRED_API_ENVIRONMENT
                    },
                    "depends_on": {
                        "xgb-reranker": {"condition": "service_healthy"},
                    },
                },
                "xgb-reranker": {
                    "environment": {
                        name: "${" + name + ":?required}"
                        for name in validator.REQUIRED_XGB_DB_ENVIRONMENT
                    }
                },
            }
        }

    def test_application_config_accepts_managed_service_environment_contract(self) -> None:
        self.assertEqual([], validator.validate_application_config(self.application_config()))

    def test_application_config_requires_redis_auth_and_tls_switch(self) -> None:
        config = self.application_config()
        redis = config["spring"]["data"]["redis"]
        redis.pop("username")
        redis.pop("password")
        redis.pop("ssl")

        errors = "\n".join(validator.validate_application_config(config))

        self.assertIn("SPRING_DATA_REDIS_USERNAME", errors)
        self.assertIn("SPRING_DATA_REDIS_PASSWORD", errors)
        self.assertIn("SPRING_DATA_REDIS_SSL_ENABLED", errors)

    def test_application_config_requires_rabbitmq_cluster_addresses_and_tls(self) -> None:
        config = self.application_config()
        rabbitmq = config["spring"]["rabbitmq"]
        rabbitmq["host"] = "${SPRING_RABBITMQ_HOST:localhost}"
        rabbitmq.pop("addresses")
        rabbitmq.pop("ssl")

        errors = "\n".join(validator.validate_application_config(config))

        self.assertIn("SPRING_RABBITMQ_ADDRESSES", errors)
        self.assertIn("SPRING_RABBITMQ_SSL_ENABLED", errors)

    def test_api_compose_accepts_only_api_runtime_services(self) -> None:
        self.assertEqual([], validator.validate_api_compose(self.api_compose()))

    def test_api_compose_rejects_web_and_managed_data_containers(self) -> None:
        compose = self.api_compose()
        compose["services"].update(
            {
                "web": {},
                "postgres": {},
                "redis": {},
                "rabbitmq": {},
            }
        )

        errors = "\n".join(validator.validate_api_compose(compose))

        for service in ["web", "postgres", "redis", "rabbitmq"]:
            with self.subTest(service=service):
                self.assertIn(service, errors)

    def test_api_compose_requires_managed_service_environment_and_private_api_port(self) -> None:
        compose = self.api_compose()
        compose["services"]["api"]["environment"].pop(
            "SPRING_DATA_REDIS_SSL_ENABLED"
        )
        compose["services"]["api"]["ports"] = ["8080:8080"]

        errors = "\n".join(validator.validate_api_compose(compose))

        self.assertIn("SPRING_DATA_REDIS_SSL_ENABLED", errors)
        self.assertIn("api service must not publish ports", errors)

    def test_nginx_config_accepts_api_and_websocket_proxy_only(self) -> None:
        config = """
        server {
            listen 80;
            location = /healthz { return 200 "ok\\n"; }
            location /api/ { proxy_pass http://api:8080; }
            location /ws/ {
                proxy_pass http://api:8080;
                proxy_set_header Upgrade $http_upgrade;
                proxy_set_header Connection $connection_upgrade;
            }
        }
        """

        self.assertEqual([], validator.validate_nginx_config(config))

    def test_nginx_config_rejects_react_static_file_hosting(self) -> None:
        config = """
        server {
            root /usr/share/nginx/html;
            location /assets/ { try_files $uri =404; }
            location /api/ { proxy_pass http://api:8080; }
            location /ws/ { proxy_pass http://api:8080; }
            location / { try_files $uri /index.html; }
        }
        """

        errors = "\n".join(validator.validate_nginx_config(config))

        self.assertIn("must not serve React static files", errors)

    def test_repository_validation_reports_missing_target_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            errors = "\n".join(validator.validate_repository(Path(directory)))

        self.assertIn("compose.api.prod.yaml", errors)
        self.assertIn("infra/nginx/api.conf", errors)
        self.assertIn("apps/api/src/main/resources/application.yml", errors)


if __name__ == "__main__":
    unittest.main()
