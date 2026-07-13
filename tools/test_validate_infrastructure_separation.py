import tempfile
import unittest
from pathlib import Path

from tools import validate_infrastructure_separation as validator


# 이 테스트에서 말하는 "정상/비정상"은 현재의 단일 Compose 배포가 아니라,
# Web은 S3, DB/Redis/RabbitMQ는 AWS 관리형 서비스로 분리된 목표 구조를 기준으로 한다.
class InfrastructureSeparationValidatorTest(unittest.TestCase):
    def application_config(self) -> dict:
        # 정상 설정: Spring Boot가 특정 컨테이너 주소를 하드코딩하지 않고
        # RDS, ElastiCache, Amazon MQ 접속 정보를 모두 환경변수로 받을 수 있다.
        return {
            "spring": {
                # 정상 설정: RDS endpoint, 계정, 비밀번호를 배포 환경에서 주입한다.
                "datasource": {
                    "url": "${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/buildgraph}",
                    "username": "${SPRING_DATASOURCE_USERNAME:buildgraph}",
                    "password": "${SPRING_DATASOURCE_PASSWORD:buildgraph}",
                },
                "data": {
                    # 정상 설정: ElastiCache의 주소·인증정보·TLS 여부를 모두 주입한다.
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
                # 정상 설정: Amazon MQ broker 주소와 TLS 여부를 주입한다.
                # 단일 Docker 서비스 이름인 rabbitmq에만 의존하지 않는다.
                "rabbitmq": {
                    "addresses": "${SPRING_RABBITMQ_ADDRESSES:${SPRING_RABBITMQ_HOST:localhost}:5672}",
                    "username": "${SPRING_RABBITMQ_USERNAME:buildgraph}",
                    "password": "${SPRING_RABBITMQ_PASSWORD:buildgraph}",
                    "ssl": {
                        "enabled": "${SPRING_RABBITMQ_SSL_ENABLED:false}",
                    },
                },
            }
        }

    def api_compose(self) -> dict:
        # 정상 설정: 운영 Compose에는 API 실행에 필요한 세 서비스만 남긴다.
        # Web은 S3로, PostgreSQL/Redis/RabbitMQ는 AWS 관리형 서비스로 분리한다.
        return {
            "services": {
                # 정상 설정: 외부 요청은 Nginx의 80번 포트만 받는다.
                "nginx": {
                    "ports": ["80:80"],
                    "depends_on": ["api"],
                },
                # 정상 설정: API는 관리형 서비스 접속 정보를 환경변수로 받고,
                # 8080 포트를 호스트에 직접 공개하지 않는다.
                "api": {
                    "environment": {
                        name: "${" + name + ":?required}"
                        for name in validator.REQUIRED_API_ENVIRONMENT
                    },
                    "depends_on": {
                        "xgb-reranker": {"condition": "service_healthy"},
                    },
                },
                # 정상 설정: XGB Reranker도 Compose PostgreSQL이 아니라 RDS에 연결한다.
                "xgb-reranker": {
                    "environment": {
                        name: "${" + name + ":?required}"
                        for name in validator.REQUIRED_XGB_DB_ENVIRONMENT
                    }
                },
            }
        }

    def test_application_config_accepts_managed_service_environment_contract(self) -> None:
        # 정상 설정을 넣으면 검증 오류 목록이 비어 있어야 한다.
        self.assertEqual([], validator.validate_application_config(self.application_config()))

    def test_application_config_requires_redis_auth_and_tls_switch(self) -> None:
        config = self.application_config()
        redis = config["spring"]["data"]["redis"]

        # 비정상 설정을 의도적으로 만든다:
        # ElastiCache 사용자명, 비밀번호, TLS 설정을 제거한다.
        redis.pop("username")
        redis.pop("password")
        redis.pop("ssl")

        errors = "\n".join(validator.validate_application_config(config))

        # 검증기가 위의 세 누락 항목을 모두 오류로 찾아야 정상이다.
        self.assertIn("SPRING_DATA_REDIS_USERNAME", errors)
        self.assertIn("SPRING_DATA_REDIS_PASSWORD", errors)
        self.assertIn("SPRING_DATA_REDIS_SSL_ENABLED", errors)

    def test_application_config_requires_rabbitmq_addresses_and_tls(self) -> None:
        config = self.application_config()
        rabbitmq = config["spring"]["rabbitmq"]

        # 비정상 설정을 의도적으로 만든다:
        # 관리형 broker 주소(addresses)를 단일 Docker용 host로 바꾸고 TLS도 제거한다.
        rabbitmq["host"] = "${SPRING_RABBITMQ_HOST:localhost}"
        rabbitmq.pop("addresses")
        rabbitmq.pop("ssl")

        errors = "\n".join(validator.validate_application_config(config))

        # Amazon MQ 주소 목록과 TLS 설정이 없다는 오류가 나와야 한다.
        self.assertIn("SPRING_RABBITMQ_ADDRESSES", errors)
        self.assertIn("SPRING_RABBITMQ_SSL_ENABLED", errors)

    def test_application_config_keeps_blue_rabbitmq_host_fallback(self) -> None:
        config = self.application_config()
        config["spring"]["rabbitmq"]["addresses"] = (
            "${SPRING_RABBITMQ_ADDRESSES:localhost:5672}"
        )

        errors = "\n".join(validator.validate_application_config(config))

        self.assertIn("SPRING_RABBITMQ_HOST", errors)

    def test_api_compose_accepts_only_api_runtime_services(self) -> None:
        # 정상 설정인 nginx/api/xgb-reranker만 있으면 오류가 없어야 한다.
        self.assertEqual([], validator.validate_api_compose(self.api_compose()))

    def test_api_compose_rejects_web_and_managed_data_containers(self) -> None:
        compose = self.api_compose()

        # 비정상 설정을 의도적으로 만든다:
        # 이미 S3 또는 AWS 관리형 서비스로 분리해야 할 컨테이너를 다시 추가한다.
        compose["services"].update(
            {
                "web": {},
                "postgres": {},
                "redis": {},
                "rabbitmq": {},
            }
        )

        errors = "\n".join(validator.validate_api_compose(compose))

        # 금지된 각 서비스가 오류 메시지에 포함돼야 한다.
        for service in ["web", "postgres", "redis", "rabbitmq"]:
            with self.subTest(service=service):
                self.assertIn(service, errors)

    def test_api_compose_requires_managed_service_environment_and_private_api_port(self) -> None:
        compose = self.api_compose()

        # 비정상 설정 1: API에서 ElastiCache TLS 환경변수를 제거한다.
        compose["services"]["api"]["environment"].pop(
            "SPRING_DATA_REDIS_SSL_ENABLED"
        )

        # 비정상 설정 2: 내부용 API 8080을 EC2 호스트에 직접 공개한다.
        # 목표 구조에서는 Nginx만 외부 요청을 받고 API 8080은 Docker 내부에만 둔다.
        compose["services"]["api"]["ports"] = ["8080:8080"]

        errors = "\n".join(validator.validate_api_compose(compose))

        # 검증기가 TLS 누락과 API 포트 공개를 모두 발견해야 한다.
        self.assertIn("SPRING_DATA_REDIS_SSL_ENABLED", errors)
        self.assertIn("api service must not publish ports", errors)

    def test_nginx_config_accepts_api_and_websocket_proxy_only(self) -> None:
        # 정상 설정: API 전용 Nginx는 /api와 /ws만 Spring Boot로 전달한다.
        # React 정적 파일 제공 설정은 포함하지 않는다.
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
        # 비정상 설정을 의도적으로 만든다:
        # API 전용 Nginx에 React root/assets/index.html 제공 설정을 넣는다.
        # 목표 구조에서 React 정적 파일은 S3와 CloudFront가 제공해야 한다.
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
        # 비정상 저장소를 의도적으로 만든다:
        # 빈 임시 디렉터리를 저장소처럼 검사해 필수 파일 누락을 재현한다.
        with tempfile.TemporaryDirectory() as directory:
            errors = "\n".join(validator.validate_repository(Path(directory)))

        # 검증기가 목표 Compose, API Nginx, Spring 설정 파일 누락을 모두 알려야 한다.
        self.assertIn("compose.api.prod.yaml", errors)
        self.assertIn("infra/nginx/api.conf", errors)
        self.assertIn("apps/api/src/main/resources/application.yml", errors)


if __name__ == "__main__":
    unittest.main()
