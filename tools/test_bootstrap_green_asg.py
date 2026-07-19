from __future__ import annotations

import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[1]
BOOTSTRAP_SCRIPT = ROOT / "tools/bootstrap_green_asg.sh"
ECR_COMPOSE = ROOT / "compose.api.ecr.prod.yaml"
RELEASE_MANIFEST = ROOT / "infra/asg/green-release.env"
DEPLOY_WORKFLOWS = (
    ROOT / ".github/workflows/ci.yml",
    ROOT / ".github/workflows/deploy-api-green.yml",
    ROOT / ".github/workflows/deploy-xgb-green.yml",
)
PRIVATE_NETWORK_TEST_MODULES = (
    "tools.test_provision_green_private_app_network",
    "tools.test_migrate_green_web_asg_private",
)

AWS_ACCOUNT_ID = "443915990705"
AWS_REGION = "ap-northeast-2"
API_IMAGE = (
    "443915990705.dkr.ecr.ap-northeast-2.amazonaws.com/"
    "buildgraph-demo-api-green@"
    "sha256:aae7ad00b42e07b372a03aa12f6b354d4c6c0f3a780b1d1f8d91d52ef3d9b267"
)
XGB_IMAGE = (
    "443915990705.dkr.ecr.ap-northeast-2.amazonaws.com/"
    "buildgraph-demo-xgb-reranker-green@"
    "sha256:d71bd6a390c747b306c3e9bfd302059d04ec691b755c2abc90115b797bf565a9"
)
NGINX_IMAGE = (
    "docker.io/library/nginx@"
    "sha256:62223d644fa234c3a1cc785ee14242ec47a77364226f1c811d2f669f96dc2ac8"
)
NGINX_COMPOSE_EXPRESSION = f"${{NGINX_IMAGE_URI:-{NGINX_IMAGE}}}"
TEST_SECRET = "TEST_DB_PASSWORD_MUST_NOT_LEAK"


def load_yaml(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as file:
        value = yaml.safe_load(file)
    if not isinstance(value, dict):
        raise AssertionError(f"{path} must contain a YAML mapping")
    return value


def parse_dotenv(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key:
            raise AssertionError(f"invalid dotenv line: {line}")
        if key in values:
            raise AssertionError(f"duplicate dotenv key: {key}")
        values[key] = value
    return values


class GreenAsgBootstrapContractTest(unittest.TestCase):
    maxDiff = None

    def test_required_files_exist(self) -> None:
        required = [BOOTSTRAP_SCRIPT, RELEASE_MANIFEST]
        missing = [str(path.relative_to(ROOT)) for path in required if not path.is_file()]
        self.assertEqual([], missing, f"missing ASG bootstrap files: {missing}")

    def test_release_manifest_pins_immutable_images_and_disables_scheduler(self) -> None:
        values = parse_dotenv(RELEASE_MANIFEST)

        self.assertEqual(
            {
                "API_IMAGE_URI": API_IMAGE,
                "XGB_IMAGE_URI": XGB_IMAGE,
                "NGINX_IMAGE_URI": NGINX_IMAGE,
                "BUILDGRAPH_SCHEDULING_ENABLED": "false",
            },
            values,
        )
        for key in ("API_IMAGE_URI", "XGB_IMAGE_URI", "NGINX_IMAGE_URI"):
            self.assertRegex(values[key], r"^[^\s@]+@sha256:[0-9a-f]{64}$")

    def test_compose_accepts_pinned_nginx_and_passes_scheduler_switch(self) -> None:
        services = load_yaml(ECR_COMPOSE)["services"]

        self.assertEqual(NGINX_COMPOSE_EXPRESSION, services["nginx"].get("image"))
        self.assertEqual(
            "${BUILDGRAPH_SCHEDULING_ENABLED:-true}",
            services["api"].get("environment", {}).get(
                "BUILDGRAPH_SCHEDULING_ENABLED"
            ),
        )
        self.assertEqual(
            "false",
            services["api"].get("environment", {}).get(
                "RECOMMENDATION_EVENTS_WORKER_ENABLED"
            ),
        )
        self.assertEqual(
            "${API_IMAGE_URI:?API_IMAGE_URI is required}",
            services["recommendation-event-worker"].get("image"),
        )
        self.assertEqual(
            "true",
            services["recommendation-event-worker"]
            .get("environment", {})
            .get("RECOMMENDATION_EVENTS_WORKER_ENABLED"),
        )
        self.assertEqual(
            "false",
            services["recommendation-event-worker"]
            .get("environment", {})
            .get("AGENT_WORKER_ENABLED"),
        )
        self.assertEqual(
            "false",
            services["recommendation-event-worker"]
            .get("environment", {})
            .get("PART_PRICE_REFRESH_WORKER_ENABLED"),
        )
        self.assertEqual(
            "false",
            services["recommendation-event-worker"]
            .get("environment", {})
            .get("BUILDGRAPH_AUTH_REFRESH_TOKEN_CLEANUP_ENABLED"),
        )
        self.assertEqual(
            "false",
            services["recommendation-event-worker"]
            .get("environment", {})
            .get("BUILDGRAPH_AUTH_REFRESH_TOKEN_CLEANUP_RUN_ON_STARTUP"),
        )
        for service_name in ("api", "recommendation-event-worker", "xgb-reranker"):
            with self.subTest(service=service_name):
                self.assertEqual(
                    "${AWS_STS_REGIONAL_ENDPOINTS:-regional}",
                    services[service_name]
                    .get("environment", {})
                    .get("AWS_STS_REGIONAL_ENDPOINTS"),
                )

    def test_bootstrap_has_secret_safe_idempotent_runtime_contract(self) -> None:
        script = BOOTSTRAP_SCRIPT.read_text(encoding="utf-8")

        required_markers = [
            "set -Eeuo pipefail",
            "umask 077",
            "flock",
            "sts get-caller-identity",
            "AWS_STS_REGIONAL_ENDPOINTS",
            "secretsmanager get-secret-value",
            "ecr get-login-password",
            "config --quiet",
            "pull",
            "up -d",
            "nginx -t",
            "/api/health",
            "BUILDGRAPH_SCHEDULING_ENABLED",
            "asg-bootstrap-success",
        ]
        for marker in required_markers:
            with self.subTest(marker=marker):
                self.assertIn(marker, script)

        self.assertNotIn("set -x", script)
        self.assertNotRegex(script, r"(?m)^\s*(cat|printf|echo)\s+[^\n]*\.env\.prod")

    def test_green_workflows_run_bootstrap_contract_tests(self) -> None:
        for workflow in DEPLOY_WORKFLOWS:
            with self.subTest(workflow=workflow.name):
                self.assertIn(
                    "tools.test_bootstrap_green_asg",
                    workflow.read_text(encoding="utf-8"),
                )
                for module_name in PRIVATE_NETWORK_TEST_MODULES:
                    self.assertIn(
                        module_name,
                        workflow.read_text(encoding="utf-8"),
                    )


class GreenAsgBootstrapExecutionTest(unittest.TestCase):
    maxDiff = None

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.temp_root = Path(self.temporary_directory.name)
        self.app_root = self.temp_root / "app"
        self.fake_bin = self.temp_root / "bin"
        self.runtime_dir = self.temp_root / "runtime"
        self.state_dir = self.temp_root / "state"
        self.trace_file = self.temp_root / "trace.log"
        self.health_counter = self.temp_root / "health-counter"

        (self.app_root / "infra/asg").mkdir(parents=True)
        (self.app_root / ".git").mkdir()
        self.fake_bin.mkdir()
        self.runtime_dir.mkdir()
        self.state_dir.mkdir()
        (self.app_root / "compose.api.ecr.prod.yaml").write_text(
            ECR_COMPOSE.read_text(encoding="utf-8"),
            encoding="utf-8",
        )
        (self.app_root / "infra/asg/green-release.env").write_text(
            RELEASE_MANIFEST.read_text(encoding="utf-8"),
            encoding="utf-8",
        )
        self._install_fake_commands()

        uid = os.getuid()
        gid = os.getgid()
        self.environment = os.environ.copy()
        self.environment.update(
            {
                "PATH": f"{self.fake_bin}{os.pathsep}{self.environment['PATH']}",
                "AWS_ACCOUNT_ID": AWS_ACCOUNT_ID,
                "AWS_REGION": AWS_REGION,
                "BUILDGRAPH_ALLOW_NON_ROOT_FOR_TESTS": "true",
                "BUILDGRAPH_APP_ROOT": str(self.app_root),
                "BUILDGRAPH_RUNTIME_ENV": str(self.runtime_dir / ".env.prod"),
                "GREEN_IMAGE_MANIFEST": str(
                    self.runtime_dir / "green-images.env"
                ),
                "BUILDGRAPH_ASG_RUNTIME_ENV": str(
                    self.runtime_dir / "asg-runtime.env"
                ),
                "BUILDGRAPH_ASG_STATE_DIR": str(self.state_dir),
                "BUILDGRAPH_ASG_LOCK_FILE": str(self.state_dir / "bootstrap.lock"),
                "BUILDGRAPH_RELEASE_MANIFEST": str(
                    self.app_root / "infra/asg/green-release.env"
                ),
                "BUILDGRAPH_FILE_OWNER": f"{uid}:{gid}",
                "BUILDGRAPH_HEALTH_MAX_ATTEMPTS": "3",
                "BUILDGRAPH_HEALTH_RETRY_SECONDS": "0",
                "FAKE_AWS_ACCOUNT_ID": AWS_ACCOUNT_ID,
                "FAKE_INSTANCE_REGION": AWS_REGION,
                "FAKE_GIT_SHA": "f" * 40,
                "FAKE_SECRET_CONTENT": "\n".join(
                    [
                        "SPRING_DATASOURCE_URL=jdbc:postgresql://db/buildgraph",
                        "SPRING_DATASOURCE_USERNAME=buildgraph",
                        f"SPRING_DATASOURCE_PASSWORD={TEST_SECRET}",
                        "BUILDGRAPH_AUTH_JWT_SECRET=test-jwt-secret",
                    ]
                ),
                "FAKE_TRACE_FILE": str(self.trace_file),
                "FAKE_HEALTH_COUNTER": str(self.health_counter),
                "FAKE_IMAGE_MANIFEST": str(
                    self.runtime_dir / "green-images.env"
                ),
                "FAKE_ASG_RUNTIME_ENV": str(
                    self.runtime_dir / "asg-runtime.env"
                ),
            }
        )

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def _write_executable(self, name: str, body: str) -> None:
        path = self.fake_bin / name
        path.write_text(textwrap.dedent(body).lstrip(), encoding="utf-8")
        path.chmod(0o755)

    def _install_fake_commands(self) -> None:
        self._write_executable(
            "aws",
            r"""
            #!/usr/bin/env bash
            set -euo pipefail

            printf 'aws-sts-endpoints=%s\n' \
              "${AWS_STS_REGIONAL_ENDPOINTS:-unset}" >>"$FAKE_TRACE_FILE"
            case "${1:-}:${2:-}" in
              sts:get-caller-identity)
                printf '%s\n' "$FAKE_AWS_ACCOUNT_ID"
                ;;
              secretsmanager:get-secret-value)
                [[ "${FAKE_SECRET_FAILURE:-false}" != "true" ]] || exit 65
                printf '%s\n' "$FAKE_SECRET_CONTENT"
                ;;
              ecr:get-login-password)
                printf '%s\n' 'fake-ecr-token'
                ;;
              *)
                echo "unexpected fake aws invocation: $*" >&2
                exit 64
                ;;
            esac
            printf 'aws:%s:%s\n' "${1:-}" "${2:-}" >>"$FAKE_TRACE_FILE"
            """,
        )
        self._write_executable(
            "docker",
            r"""
            #!/usr/bin/env bash
            set -euo pipefail

            dotenv_value() {
              local key="$1"
              local file="$2"
              sed -n "s/^${key}=//p" "$file" | tail -n 1
            }

            case "${1:-}" in
              login)
                cat >/dev/null
                printf '%s\n' 'docker:login' >>"$FAKE_TRACE_FILE"
                ;;
              compose)
                shift
                compose_command=''
                while [[ "$#" -gt 0 ]]; do
                  case "$1" in
                    -p|-f|--env-file)
                      shift 2
                      ;;
                    config|pull|up|ps|exec)
                      compose_command="$1"
                      shift
                      break
                      ;;
                    *)
                      shift
                      ;;
                  esac
                done
                printf 'docker:compose:%s\n' "$compose_command" >>"$FAKE_TRACE_FILE"
                case "$compose_command" in
                  config|pull|up|exec)
                    exit 0
                    ;;
                  ps)
                    service="${*: -1}"
                    printf '%s-container\n' "$service"
                    ;;
                  *)
                    echo "unexpected fake compose invocation: $compose_command" >&2
                    exit 64
                    ;;
                esac
                ;;
              inspect)
                container_id="${*: -1}"
                case "$container_id" in
                  api-container|recommendation-event-worker-container)
                    dotenv_value API_IMAGE_URI "$FAKE_IMAGE_MANIFEST"
                    ;;
                  xgb-reranker-container)
                    dotenv_value XGB_IMAGE_URI "$FAKE_IMAGE_MANIFEST"
                    ;;
                  nginx-container)
                    dotenv_value NGINX_IMAGE_URI "$FAKE_ASG_RUNTIME_ENV"
                    ;;
                  *)
                    echo "unexpected fake container: $container_id" >&2
                    exit 64
                    ;;
                esac
                ;;
              *)
                echo "unexpected fake docker invocation: $*" >&2
                exit 64
                ;;
            esac
            """,
        )
        self._write_executable(
            "curl",
            r"""
            #!/usr/bin/env bash
            set -euo pipefail
            case "$*" in
              *169.254.169.254/latest/api/token*)
                printf '%s\n' 'fake-imdsv2-token'
                exit 0
                ;;
              *169.254.169.254/latest/meta-data/placement/region*)
                printf '%s\n' "$FAKE_INSTANCE_REGION"
                exit 0
                ;;
            esac
            count=0
            [[ ! -f "$FAKE_HEALTH_COUNTER" ]] || count="$(cat "$FAKE_HEALTH_COUNTER")"
            count=$((count + 1))
            printf '%s\n' "$count" >"$FAKE_HEALTH_COUNTER"
            printf 'curl:%s\n' "$count" >>"$FAKE_TRACE_FILE"
            [[ "${FAKE_HEALTH_ALWAYS_FAIL:-false}" != "true" ]] || exit 22
            [[ "$count" -ge 2 ]] || exit 22
            printf '%s\n' '{"database":"UP","status":"UP"}'
            """,
        )
        self._write_executable(
            "git",
            r"""
            #!/usr/bin/env bash
            set -euo pipefail
            [[ "$*" == *"rev-parse HEAD"* ]] || {
              echo "unexpected fake git invocation: $*" >&2
              exit 64
            }
            printf '%s\n' "$FAKE_GIT_SHA"
            """,
        )
        self._write_executable(
            "runuser",
            r"""
            #!/usr/bin/env bash
            set -euo pipefail
            [[ "${1:-}" == "-u" && "${3:-}" == "--" ]] || exit 64
            app_user="$2"
            shift 3
            printf 'runuser:%s:%s\n' "$app_user" "$*" >>"$FAKE_TRACE_FILE"
            exec "$@"
            """,
        )
        self._write_executable(
            "systemctl",
            r"""
            #!/usr/bin/env bash
            set -euo pipefail
            printf 'systemctl:%s\n' "$*" >>"$FAKE_TRACE_FILE"
            """,
        )
        self._write_executable(
            "flock",
            r"""
            #!/usr/bin/env bash
            exit 0
            """,
        )
        self._write_executable(
            "sleep",
            r"""
            #!/usr/bin/env bash
            exit 0
            """,
        )

    def _run_bootstrap(
        self, **environment_overrides: str
    ) -> subprocess.CompletedProcess[str]:
        environment = self.environment.copy()
        environment.update(environment_overrides)
        return subprocess.run(
            ["bash", str(BOOTSTRAP_SCRIPT)],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )

    def test_success_writes_split_runtime_files_without_leaking_secret(self) -> None:
        result = self._run_bootstrap()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        combined_output = result.stdout + result.stderr
        self.assertNotIn(TEST_SECRET, combined_output)

        runtime_env = self.runtime_dir / ".env.prod"
        image_manifest = self.runtime_dir / "green-images.env"
        asg_runtime_env = self.runtime_dir / "asg-runtime.env"
        success_marker = self.state_dir / "asg-bootstrap-success"

        self.assertIn(TEST_SECRET, runtime_env.read_text(encoding="utf-8"))
        self.assertEqual(0o600, runtime_env.stat().st_mode & 0o777)
        self.assertEqual(
            {"API_IMAGE_URI": API_IMAGE, "XGB_IMAGE_URI": XGB_IMAGE},
            parse_dotenv(image_manifest),
        )
        self.assertEqual(
            {
                "NGINX_IMAGE_URI": NGINX_IMAGE,
                "BUILDGRAPH_SCHEDULING_ENABLED": "false",
                "AWS_STS_REGIONAL_ENDPOINTS": "regional",
            },
            parse_dotenv(asg_runtime_env),
        )
        self.assertTrue(success_marker.is_file())
        self.assertNotIn(TEST_SECRET, success_marker.read_text(encoding="utf-8"))

        trace = self.trace_file.read_text(encoding="utf-8")
        for marker in (
            "aws-sts-endpoints=regional",
            "aws:secretsmanager:get-secret-value",
            "docker:login",
            "docker:compose:config",
            "docker:compose:pull",
            "docker:compose:up",
            "docker:compose:exec",
            "curl:2",
            "systemctl:enable --now amazon-cloudwatch-agent",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, trace)

    def test_regional_sts_setting_is_not_loaded_from_runtime_secret(self) -> None:
        result = self._run_bootstrap(
            FAKE_SECRET_CONTENT="\n".join(
                [
                    "SPRING_DATASOURCE_URL=jdbc:postgresql://db/buildgraph",
                    "SPRING_DATASOURCE_USERNAME=buildgraph",
                    f"SPRING_DATASOURCE_PASSWORD={TEST_SECRET}",
                ]
            )
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(
            "regional",
            parse_dotenv(self.runtime_dir / "asg-runtime.env")[
                "AWS_STS_REGIONAL_ENDPOINTS"
            ],
        )

    def test_second_run_is_idempotent(self) -> None:
        first = self._run_bootstrap()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        first_runtime = (self.runtime_dir / ".env.prod").read_bytes()
        first_images = (self.runtime_dir / "green-images.env").read_bytes()
        first_asg = (self.runtime_dir / "asg-runtime.env").read_bytes()

        second = self._run_bootstrap()

        self.assertEqual(0, second.returncode, second.stdout + second.stderr)
        self.assertEqual(first_runtime, (self.runtime_dir / ".env.prod").read_bytes())
        self.assertEqual(
            first_images, (self.runtime_dir / "green-images.env").read_bytes()
        )
        self.assertEqual(first_asg, (self.runtime_dir / "asg-runtime.env").read_bytes())
        self.assertNotIn(TEST_SECRET, second.stdout + second.stderr)

    def test_reads_git_sha_as_application_user(self) -> None:
        result = self._run_bootstrap()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        trace = self.trace_file.read_text(encoding="utf-8")
        self.assertIn("runuser:ubuntu:git -C", trace)
        self.assertIn("rev-parse HEAD", trace)

    def test_wrong_account_fails_before_secret_lookup(self) -> None:
        result = self._run_bootstrap(FAKE_AWS_ACCOUNT_ID="000000000000")

        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.runtime_dir / ".env.prod").exists())
        self.assertFalse((self.state_dir / "asg-bootstrap-success").exists())
        trace = self.trace_file.read_text(encoding="utf-8")
        self.assertNotIn("secretsmanager", trace)

    def test_wrong_instance_region_fails_before_secret_lookup(self) -> None:
        result = self._run_bootstrap(FAKE_INSTANCE_REGION="us-east-1")

        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.runtime_dir / ".env.prod").exists())
        self.assertFalse((self.state_dir / "asg-bootstrap-success").exists())
        self.assertFalse(self.trace_file.exists())

    def test_secret_failure_does_not_create_success_marker(self) -> None:
        result = self._run_bootstrap(FAKE_SECRET_FAILURE="true")

        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.state_dir / "asg-bootstrap-success").exists())
        self.assertNotIn(TEST_SECRET, result.stdout + result.stderr)

    def test_health_timeout_does_not_create_success_marker(self) -> None:
        result = self._run_bootstrap(FAKE_HEALTH_ALWAYS_FAIL="true")

        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.state_dir / "asg-bootstrap-success").exists())
        self.assertNotIn(TEST_SECRET, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
