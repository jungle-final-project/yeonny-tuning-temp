from __future__ import annotations

import json
import os
import re
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[1]
ECR_COMPOSE = ROOT / "compose.api.ecr.prod.yaml"
DEPLOY_SCRIPT = ROOT / "tools/deploy_green_ecr.sh"
ASG_ROLLOUT_SCRIPT = ROOT / "tools/rollout_green_web_asg_release.sh"
ASG_ROLLOUT_POLICY = (
    ROOT
    / "infra/iam/buildgraph-demo-github-actions-green-asg-rollout-policy.json"
)
WORKFLOWS = {
    "web": ROOT / ".github/workflows/deploy-web-green.yml",
    "api": ROOT / ".github/workflows/deploy-api-green.yml",
    "xgb": ROOT / ".github/workflows/deploy-xgb-green.yml",
    "release": ROOT / ".github/workflows/release-green-web-asg.yml",
}

EXPECTED_SERVICES = {"nginx", "api", "xgb-reranker"}
EXPECTED_API_IMAGE = "${API_IMAGE_URI:?API_IMAGE_URI is required}"
EXPECTED_XGB_IMAGE = "${XGB_IMAGE_URI:?XGB_IMAGE_URI is required}"
GREEN_CLOUDFRONT_VARIABLE = "vars.GREEN_CF_DISTRIBUTION_ID"
BLUE_CLOUDFRONT_ID = "EI6MMNZLTTN3H"
AWS_ACCOUNT_ID = "443915990705"
AWS_REGION = "ap-northeast-2"
ECR_REGISTRY = f"{AWS_ACCOUNT_ID}.dkr.ecr.{AWS_REGION}.amazonaws.com"
API_REPOSITORY = "buildgraph-demo-api-green"
XGB_REPOSITORY = "buildgraph-demo-xgb-reranker-green"
OLD_API_SHA = "a" * 40
NEW_SHA = "b" * 40
OLD_XGB_SHA = "c" * 40
OLD_API_IMAGE = f"{ECR_REGISTRY}/{API_REPOSITORY}:{OLD_API_SHA}"
NEW_API_IMAGE = f"{ECR_REGISTRY}/{API_REPOSITORY}:{NEW_SHA}"
OLD_XGB_IMAGE = f"{ECR_REGISTRY}/{XGB_REPOSITORY}:{OLD_XGB_SHA}"
NEW_XGB_IMAGE = f"{ECR_REGISTRY}/{XGB_REPOSITORY}:{NEW_SHA}"


def load_yaml(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as file:
        value = yaml.safe_load(file)
    if not isinstance(value, dict):
        raise AssertionError(f"{path} must contain a YAML mapping")
    return value


def published_ports(service: dict[str, Any]) -> list[Any]:
    ports = service.get("ports", [])
    return ports if isinstance(ports, list) else [ports]


def parse_dotenv(path: Path) -> dict[str, str]:
    return dict(
        line.split("=", 1)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#")
    )


class GreenDeploymentContractTest(unittest.TestCase):
    maxDiff = None

    def test_required_phase8_files_exist(self) -> None:
        required = [
            ECR_COMPOSE,
            DEPLOY_SCRIPT,
            ASG_ROLLOUT_SCRIPT,
            ASG_ROLLOUT_POLICY,
            *WORKFLOWS.values(),
        ]
        missing = [str(path.relative_to(ROOT)) for path in required if not path.is_file()]
        self.assertEqual([], missing, f"missing Phase 8 files: {missing}")

    def test_ecr_compose_has_only_three_runtime_services(self) -> None:
        compose = load_yaml(ECR_COMPOSE)
        services = compose.get("services")
        self.assertIsInstance(services, dict)
        self.assertEqual(EXPECTED_SERVICES, set(services))

    def test_ecr_compose_uses_required_images_without_build(self) -> None:
        services = load_yaml(ECR_COMPOSE)["services"]

        self.assertNotIn("build", services["api"])
        self.assertNotIn("build", services["xgb-reranker"])
        self.assertEqual(EXPECTED_API_IMAGE, services["api"].get("image"))
        self.assertEqual(EXPECTED_XGB_IMAGE, services["xgb-reranker"].get("image"))

    def test_only_nginx_publishes_host_port_80(self) -> None:
        services = load_yaml(ECR_COMPOSE)["services"]

        self.assertEqual(["80:80"], published_ports(services["nginx"]))
        self.assertEqual([], published_ports(services["api"]))
        self.assertEqual([], published_ports(services["xgb-reranker"]))
        self.assertEqual(["8080"], services["api"].get("expose"))
        self.assertEqual(["8091"], services["xgb-reranker"].get("expose"))

    def test_deploy_script_has_ecr_ssm_runtime_safety_contract(self) -> None:
        script = DEPLOY_SCRIPT.read_text(encoding="utf-8")

        required_markers = [
            "flock",
            "green-images.env",
            "git checkout --detach",
            "aws secretsmanager get-secret-value",
            "aws ecr get-login-password",
            "docker compose",
            "config --quiet",
            "--no-deps",
            "--force-recreate",
            "--no-build",
            "rollback",
            "/api/health",
            "healthy",
        ]
        for marker in required_markers:
            with self.subTest(marker=marker):
                self.assertIn(marker, script)

        self.assertIn("buildgraph-demo-api-green", script)
        self.assertIn("buildgraph-demo-xgb-reranker-green", script)
        self.assertNotIn("set -x", script)
        self.assertNotRegex(script, r"cat\s+[^\n]*\.env\.prod")

    def test_green_workflows_use_oidc_without_long_lived_aws_keys(self) -> None:
        for name, path in WORKFLOWS.items():
            text = path.read_text(encoding="utf-8")
            workflow = load_yaml(path)

            with self.subTest(workflow=name):
                self.assertEqual("read", workflow.get("permissions", {}).get("contents"))
                self.assertEqual("write", workflow.get("permissions", {}).get("id-token"))
                self.assertIn("aws-actions/configure-aws-credentials@v4", text)
                self.assertIn("vars.AWS_DEPLOY_ROLE_ARN", text)
                self.assertNotRegex(text, r"AWS_(ACCESS_KEY_ID|SECRET_ACCESS_KEY)")
                self.assertNotIn("secrets.AWS_", text)
                self.assertNotRegex(text, r"(?m)^\s*environment:\s*")

    def test_api_and_xgb_workflows_publish_sha_images_without_instance_mutation(
        self,
    ) -> None:
        for name in ("api", "xgb"):
            text = WORKFLOWS[name].read_text(encoding="utf-8")
            with self.subTest(workflow=name):
                self.assertIn("github.sha", text)
                self.assertNotRegex(text, r"(?i)(?:imageTag=|:)\s*latest\b")
                self.assertNotIn("aws ssm send-command", text)
                self.assertNotIn("aws ssm get-command-invocation", text)
                self.assertNotIn("GREEN_EC2_INSTANCE_ID", text)
                self.assertNotIn("tools/deploy_green_ecr.sh", text)
                self.assertNotIn("start-instance-refresh", text)
                self.assertNotRegex(text, r"(?m)^\s*[^#\n]*(ssh|rsync)\b")

    def test_asg_release_workflow_is_manual_without_cloudfront_isolation(
        self,
    ) -> None:
        path = WORKFLOWS["release"]
        text = path.read_text(encoding="utf-8")
        workflow = load_yaml(path)

        triggers = workflow.get(True, workflow.get("on"))
        self.assertIsInstance(triggers, dict)
        self.assertEqual({"workflow_dispatch"}, set(triggers))
        self.assertIn("tools/rollout_green_web_asg_release.sh", text)
        self.assertIn("deploy-green-web-asg-release", text)
        self.assertNotIn("traffic_isolated", text)
        self.assertNotIn(
            "BUILDGRAPH_CLOUDFRONT_MANUAL_GREEN_CONFIRMED",
            text,
        )
        self.assertNotIn("GREEN_EC2_INSTANCE_ID", text)
        self.assertNotIn("aws ssm send-command", text)

    def test_asg_rollout_policy_is_scoped_and_does_not_grant_ssm(self) -> None:
        policy = json.loads(ASG_ROLLOUT_POLICY.read_text(encoding="utf-8"))
        statements = policy.get("Statement", [])
        actions = {
            action
            for statement in statements
            for action in (
                statement.get("Action")
                if isinstance(statement.get("Action"), list)
                else [statement.get("Action")]
            )
            if isinstance(action, str)
        }

        self.assertIn("ec2:CreateLaunchTemplateVersion", actions)
        self.assertIn("autoscaling:StartInstanceRefresh", actions)
        self.assertIn("autoscaling:RollbackInstanceRefresh", actions)
        self.assertIn("elasticloadbalancing:DescribeTargetHealth", actions)
        self.assertIn("ec2:RunInstances", actions)
        self.assertIn("iam:PassRole", actions)
        self.assertNotIn("autoscaling:UpdateAutoScalingGroup", actions)
        self.assertFalse(any(action.startswith("ssm:") for action in actions))
        self.assertFalse(any(action == "*" for action in actions))
        policy_text = json.dumps(policy, sort_keys=True)
        self.assertIn("lt-0024991a1e82e5e6c", policy_text)
        self.assertIn("buildgraph-demo-api-green-asg", policy_text)
        self.assertIn("buildgraph-demo-api-green-role", policy_text)

    def test_web_workflow_targets_only_green_and_keeps_cache_classes_separate(self) -> None:
        text = WORKFLOWS["web"].read_text(encoding="utf-8")

        self.assertIn(GREEN_CLOUDFRONT_VARIABLE, text)
        self.assertNotIn(BLUE_CLOUDFRONT_ID, text)
        self.assertIn("public,max-age=31536000,immutable", text)
        self.assertIn("no-cache,max-age=0,must-revalidate", text)
        self.assertIn("public,max-age=3600", text)
        self.assertIn("/downloads/pc-agent/latest.json", text)

    def test_push_deployments_are_guarded_by_green_cd_variable(self) -> None:
        for name in ("web", "api", "xgb"):
            path = WORKFLOWS[name]
            text = path.read_text(encoding="utf-8")
            with self.subTest(workflow=name):
                self.assertIn("GREEN_CD_ENABLED", text)
                self.assertIn("workflow_dispatch", text)

    def test_image_example_uses_two_immutable_sha_uris(self) -> None:
        example = (ROOT / ".env.images.example").read_text(encoding="utf-8")
        values = dict(
            line.split("=", 1)
            for line in example.splitlines()
            if line and not line.startswith("#")
        )

        self.assertEqual({"API_IMAGE_URI", "XGB_IMAGE_URI"}, set(values))
        for value in values.values():
            self.assertRegex(value, r"^[^\s:]+(?:/[^\s:]+)+:[0-9a-f]{40}$")


class GreenDeploymentImageSelectionRegressionTest(unittest.TestCase):
    maxDiff = None

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.temp_root = Path(self.temporary_directory.name)
        self.app_root = self.temp_root / "app"
        self.fake_bin = self.temp_root / "bin"
        self.state_dir = self.temp_root / "state"
        self.deploy_temp_dir = self.temp_root / "deploy-temp"
        self.image_manifest = self.temp_root / "green-images.env"
        self.lock_file = self.temp_root / "green-deploy.lock"

        (self.app_root / ".git").mkdir(parents=True)
        self.fake_bin.mkdir()
        self.state_dir.mkdir()
        (self.app_root / "compose.api.ecr.prod.yaml").write_text(
            ECR_COMPOSE.read_text(encoding="utf-8"),
            encoding="utf-8",
        )
        (self.app_root / ".env.prod").write_text(
            "RUNTIME_SENTINEL=true\n",
            encoding="utf-8",
        )
        self._write_manifest(OLD_API_IMAGE, OLD_XGB_IMAGE)
        self._write_state("api", OLD_API_IMAGE)
        self._write_state("xgb-reranker", OLD_XGB_IMAGE)
        self._install_fake_commands()

        self.environment = os.environ.copy()
        self.environment.update(
            {
                "PATH": f"{self.fake_bin}{os.pathsep}{self.environment['PATH']}",
                "AWS_ACCOUNT_ID": AWS_ACCOUNT_ID,
                "AWS_REGION": AWS_REGION,
                "BUILDGRAPH_APP_ROOT": str(self.app_root),
                "GREEN_IMAGE_MANIFEST": str(self.image_manifest),
                "GREEN_DEPLOY_LOCK_FILE": str(self.lock_file),
                "FAKE_DEPLOY_TEMP_DIR": str(self.deploy_temp_dir),
                "FAKE_DOCKER_STATE_DIR": str(self.state_dir),
                "FAKE_PREVIOUS_GIT_SHA": OLD_API_SHA,
                # Reproduce the production bug: an inherited shell value is older
                # than the candidate manifest passed with --env-file.
                "API_IMAGE_URI": OLD_API_IMAGE,
                "XGB_IMAGE_URI": OLD_XGB_IMAGE,
            }
        )

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def _write_executable(self, name: str, body: str) -> None:
        path = self.fake_bin / name
        path.write_text(textwrap.dedent(body).lstrip(), encoding="utf-8")
        path.chmod(0o755)

    def _write_manifest(self, api_image: str, xgb_image: str) -> None:
        self.image_manifest.write_text(
            f"API_IMAGE_URI={api_image}\nXGB_IMAGE_URI={xgb_image}\n",
            encoding="utf-8",
        )

    def _write_state(self, service: str, image: str) -> None:
        (self.state_dir / f"{service}.image").write_text(
            f"{image}\n",
            encoding="utf-8",
        )

    def _read_state(self, service: str) -> str:
        return (self.state_dir / f"{service}.image").read_text(encoding="utf-8").strip()

    def _run_deploy(
        self,
        service: str,
        target_image: str,
        *,
        refuse_target_update: bool = False,
    ) -> subprocess.CompletedProcess[str]:
        environment = self.environment.copy()
        if refuse_target_update:
            environment["FAKE_DOCKER_REFUSE_TARGET_UPDATE"] = "true"

        return subprocess.run(
            ["bash", str(DEPLOY_SCRIPT), service, NEW_SHA, target_image],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )

    def _install_fake_commands(self) -> None:
        self._write_executable(
            "aws",
            r"""
            #!/usr/bin/env bash
            set -euo pipefail

            case "${1:-}:${2:-}" in
              secretsmanager:get-secret-value)
                printf '%s\n' 'RUNTIME_SENTINEL=true'
                ;;
              ecr:get-login-password)
                printf '%s\n' 'fake-ecr-token'
                ;;
              *)
                echo "unexpected fake aws invocation: $*" >&2
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
            printf '%s\n' '{"database":"UP","status":"UP"}'
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
            "git",
            r"""
            #!/usr/bin/env bash
            set -euo pipefail

            case "${1:-}" in
              rev-parse)
                printf '%s\n' "$FAKE_PREVIOUS_GIT_SHA"
                ;;
              fetch|cat-file|merge-base)
                exit 0
                ;;
              checkout)
                printf 'HEAD is now at %.7s fake deployment commit\n' "${3:-unknown}"
                ;;
              *)
                echo "unexpected fake git invocation: $*" >&2
                exit 64
                ;;
            esac
            """,
        )
        self._write_executable(
            "mktemp",
            r"""
            #!/usr/bin/env bash
            set -euo pipefail
            rm -rf "$FAKE_DEPLOY_TEMP_DIR"
            mkdir -p "$FAKE_DEPLOY_TEMP_DIR"
            printf '%s\n' "$FAKE_DEPLOY_TEMP_DIR"
            """,
        )
        self._write_executable(
            "docker",
            r"""
            #!/usr/bin/env bash
            set -euo pipefail

            state_dir="$FAKE_DOCKER_STATE_DIR"

            dotenv_value() {
              local key="$1"
              local file="$2"
              sed -n "s/^${key}=//p" "$file" | tail -n 1
            }

            case "${1:-}" in
              login)
                cat >/dev/null
                exit 0
                ;;
              inspect)
                format="${3:-}"
                container_id="${4:-}"
                if [[ "$format" == *State.Health.Status* ]]; then
                  printf '%s\n' 'healthy'
                elif [[ "$format" == *Config.Image* ]]; then
                  if [[ "$container_id" == api-* ]]; then
                    cat "$state_dir/api.image"
                  else
                    cat "$state_dir/xgb-reranker.image"
                  fi
                else
                  echo "unexpected inspect format: $format" >&2
                  exit 64
                fi
                exit 0
                ;;
              compose)
                shift
                env_files=()
                compose_command=''

                while [[ "$#" -gt 0 ]]; do
                  case "$1" in
                    --env-file)
                      env_files+=("$2")
                      shift 2
                      ;;
                    -p|-f)
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

                file_api=''
                file_xgb=''
                for env_file in "${env_files[@]}"; do
                  value="$(dotenv_value API_IMAGE_URI "$env_file")"
                  [[ -z "$value" ]] || file_api="$value"
                  value="$(dotenv_value XGB_IMAGE_URI "$env_file")"
                  [[ -z "$value" ]] || file_xgb="$value"
                done

                resolved_api="${API_IMAGE_URI:-$file_api}"
                resolved_xgb="${XGB_IMAGE_URI:-$file_xgb}"
                printf '%s\n' "$resolved_api" >"$state_dir/last-resolved-api.image"
                printf '%s\n' "$resolved_xgb" >"$state_dir/last-resolved-xgb.image"

                case "$compose_command" in
                  config|pull|exec)
                    exit 0
                    ;;
                  up)
                    target_service=''
                    for argument in "$@"; do
                      case "$argument" in
                        api|xgb-reranker)
                          target_service="$argument"
                          ;;
                      esac
                    done
                    [[ -n "$target_service" ]]

                    if [[ "${FAKE_DOCKER_REFUSE_TARGET_UPDATE:-false}" != 'true' ]]; then
                      if [[ "$target_service" == 'api' ]]; then
                        printf '%s\n' "$resolved_api" >"$state_dir/api.image"
                      else
                        printf '%s\n' "$resolved_xgb" >"$state_dir/xgb-reranker.image"
                      fi
                    fi
                    ;;
                  ps)
                    service=''
                    for argument in "$@"; do
                      case "$argument" in
                        api|xgb-reranker)
                          service="$argument"
                          ;;
                      esac
                    done
                    [[ -n "$service" ]]
                    printf '%s-container\n' "$service"
                    ;;
                  *)
                    echo "unexpected fake compose invocation: $compose_command $*" >&2
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

    def test_api_candidate_image_wins_over_inherited_shell_value(self) -> None:
        result = self._run_deploy("api", NEW_API_IMAGE)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(NEW_API_IMAGE, self._read_state("api"))
        self.assertEqual(OLD_XGB_IMAGE, self._read_state("xgb-reranker"))
        self.assertEqual(
            {
                "API_IMAGE_URI": NEW_API_IMAGE,
                "XGB_IMAGE_URI": OLD_XGB_IMAGE,
            },
            parse_dotenv(self.image_manifest),
        )

    def test_duplicate_manifest_key_is_rejected_before_deployment(self) -> None:
        self.image_manifest.write_text(
            "\n".join(
                [
                    f"API_IMAGE_URI={OLD_API_IMAGE}",
                    f"API_IMAGE_URI={NEW_API_IMAGE}",
                    f"XGB_IMAGE_URI={OLD_XGB_IMAGE}",
                    "",
                ]
            ),
            encoding="utf-8",
        )

        result = self._run_deploy("api", NEW_API_IMAGE)

        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(
            "manifest must contain exactly one API_IMAGE_URI",
            result.stderr,
        )
        self.assertEqual(OLD_API_IMAGE, self._read_state("api"))
        self.assertEqual(OLD_XGB_IMAGE, self._read_state("xgb-reranker"))

    def test_empty_manifest_value_is_rejected_before_deployment(self) -> None:
        self.image_manifest.write_text(
            f"API_IMAGE_URI={OLD_API_IMAGE}\nXGB_IMAGE_URI=\n",
            encoding="utf-8",
        )

        result = self._run_deploy("api", NEW_API_IMAGE)

        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("manifest value is empty: XGB_IMAGE_URI", result.stderr)
        self.assertEqual(OLD_API_IMAGE, self._read_state("api"))
        self.assertEqual(OLD_XGB_IMAGE, self._read_state("xgb-reranker"))

    def test_running_image_mismatch_rejects_manifest_promotion(self) -> None:
        result = self._run_deploy(
            "api",
            NEW_API_IMAGE,
            refuse_target_update=True,
        )

        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(OLD_API_IMAGE, self._read_state("api"))
        self.assertEqual(
            {
                "API_IMAGE_URI": OLD_API_IMAGE,
                "XGB_IMAGE_URI": OLD_XGB_IMAGE,
            },
            parse_dotenv(self.image_manifest),
        )

    def test_xgb_candidate_image_wins_over_inherited_shell_value(self) -> None:
        result = self._run_deploy("xgb-reranker", NEW_XGB_IMAGE)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(OLD_API_IMAGE, self._read_state("api"))
        self.assertEqual(NEW_XGB_IMAGE, self._read_state("xgb-reranker"))
        self.assertEqual(
            {
                "API_IMAGE_URI": OLD_API_IMAGE,
                "XGB_IMAGE_URI": NEW_XGB_IMAGE,
            },
            parse_dotenv(self.image_manifest),
        )

    def test_xgb_running_image_mismatch_rejects_manifest_promotion(self) -> None:
        result = self._run_deploy(
            "xgb-reranker",
            NEW_XGB_IMAGE,
            refuse_target_update=True,
        )

        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(OLD_XGB_IMAGE, self._read_state("xgb-reranker"))
        self.assertEqual(
            {
                "API_IMAGE_URI": OLD_API_IMAGE,
                "XGB_IMAGE_URI": OLD_XGB_IMAGE,
            },
            parse_dotenv(self.image_manifest),
        )


if __name__ == "__main__":
    unittest.main()
