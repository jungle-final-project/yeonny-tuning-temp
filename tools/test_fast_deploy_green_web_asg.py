from __future__ import annotations

import base64
import json
import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
CONTROL_SCRIPT = ROOT / "tools/deploy_green_web_asg_in_place.sh"
REMOTE_SCRIPT = ROOT / "tools/apply_green_asg_release_in_place.sh"
WORKFLOW = ROOT / ".github/workflows/fast-deploy-green-web-asg.yml"
RELEASE_WORKFLOW = ROOT / ".github/workflows/release-green-web-asg.yml"
POLICY = ROOT / "infra/iam/buildgraph-demo-github-actions-green-asg-rollout-policy.json"

ACCOUNT_ID = "443915990705"
REGION = "ap-northeast-2"
ASG_NAME = "buildgraph-demo-api-green-asg"
LAUNCH_TEMPLATE_ID = "lt-0greenfasttest"
TARGET_GROUP_ARN = (
    "arn:aws:elasticloadbalancing:ap-northeast-2:443915990705:"
    "targetgroup/buildgraph-demo-api-green-tg/f905e7669645a411"
)
INSTANCE_ID = "i-0123456789abcdef0"
SOURCE_SHA = "a" * 40
TARGET_SHA = "b" * 40
OLD_API_DIGEST = "1" * 64
NEW_API_DIGEST = "2" * 64
OLD_XGB_DIGEST = "3" * 64
NEW_XGB_DIGEST = "4" * 64
NGINX_DIGEST = "5" * 64
API_REPOSITORY = "buildgraph-demo-api-green"
XGB_REPOSITORY = "buildgraph-demo-xgb-reranker-green"
ECR_REGISTRY = f"{ACCOUNT_ID}.dkr.ecr.{REGION}.amazonaws.com"
OLD_API_IMAGE = f"{ECR_REGISTRY}/{API_REPOSITORY}@sha256:{OLD_API_DIGEST}"
NEW_API_IMAGE = f"{ECR_REGISTRY}/{API_REPOSITORY}@sha256:{NEW_API_DIGEST}"
OLD_XGB_IMAGE = f"{ECR_REGISTRY}/{XGB_REPOSITORY}@sha256:{OLD_XGB_DIGEST}"
NEW_XGB_IMAGE = f"{ECR_REGISTRY}/{XGB_REPOSITORY}@sha256:{NEW_XGB_DIGEST}"
NGINX_IMAGE = f"docker.io/library/nginx@sha256:{NGINX_DIGEST}"


def load_yaml(path: Path) -> dict:
    with path.open(encoding="utf-8") as file:
        value = yaml.safe_load(file)
    if not isinstance(value, dict):
        raise AssertionError(f"{path} must contain a YAML mapping")
    return value


class FastDeployStaticContractTest(unittest.TestCase):
    maxDiff = None

    def test_required_fast_deploy_files_exist(self) -> None:
        missing = [
            str(path.relative_to(ROOT))
            for path in (CONTROL_SCRIPT, REMOTE_SCRIPT, WORKFLOW)
            if not path.is_file()
        ]
        self.assertEqual([], missing)
        self.assertTrue(os.access(CONTROL_SCRIPT, os.X_OK))
        self.assertTrue(os.access(REMOTE_SCRIPT, os.X_OK))

    def test_fast_workflow_is_manual_and_shares_release_concurrency(self) -> None:
        workflow = load_yaml(WORKFLOW)
        release = load_yaml(RELEASE_WORKFLOW)
        triggers = workflow.get(True, workflow.get("on"))

        self.assertEqual({"workflow_dispatch"}, set(triggers))
        inputs = triggers["workflow_dispatch"]["inputs"]
        self.assertEqual(
            {"service", "git_sha", "image_tag"},
            set(inputs),
        )
        self.assertEqual(
            ["api", "xgb-reranker"],
            inputs["service"]["options"],
        )
        self.assertEqual(
            "deploy-green-web-asg-release",
            workflow["concurrency"]["group"],
        )
        self.assertEqual(
            workflow["concurrency"],
            release["concurrency"],
        )

    def test_fast_path_has_no_fixed_instance_or_replacement_or_cloudfront(self) -> None:
        text = "\n".join(
            path.read_text(encoding="utf-8")
            for path in (CONTROL_SCRIPT, WORKFLOW)
        )
        lowered = text.lower()

        self.assertNotIn("green_ec2_instance_id", lowered)
        self.assertNotRegex(text, r"i-[0-9a-f]{8,17}")
        for forbidden in (
            "run-instances",
            "start-instance-refresh",
            "rollback-instance-refresh",
            "cancel-instance-refresh",
            "aws cloudfront",
            "cloudfront update-distribution",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, lowered)

    def test_remote_helper_fences_cancelled_deployment_ids(self) -> None:
        text = REMOTE_SCRIPT.read_text(encoding="utf-8")
        controller = CONTROL_SCRIPT.read_text(encoding="utf-8")

        self.assertIn(".cancelled-$DEPLOYMENT_ID", text)
        self.assertIn("write_cancellation_fence", text)
        self.assertIn("deployment ID has a cancellation fence", text)
        self.assertIn("readonly cancellation_fence", controller)
        self.assertIn("cancelled before runtime mutation", text)

    def test_fast_workflow_uses_oidc_and_exact_current_main(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")

        self.assertRegex(text, r"aws-actions/configure-aws-credentials@v\d+")
        self.assertIn("vars.AWS_DEPLOY_ROLE_ARN", text)
        self.assertIn("git rev-parse origin/main", text)
        self.assertIn("tools/deploy_green_web_asg_in_place.sh", text)
        self.assertNotRegex(text, r"AWS_(ACCESS_KEY_ID|SECRET_ACCESS_KEY)")

    def test_policy_scopes_ssm_to_green_asg_instances(self) -> None:
        policy = json.loads(POLICY.read_text(encoding="utf-8"))
        statements = policy["Statement"]

        update = next(
            statement
            for statement in statements
            if statement.get("Action") == "autoscaling:UpdateAutoScalingGroup"
        )
        self.assertIn(ASG_NAME, update["Resource"])

        document = next(
            statement
            for statement in statements
            if statement.get("Action") == "ssm:SendCommand"
            and "document/AWS-RunShellScript" in str(statement.get("Resource"))
        )
        self.assertEqual(
            f"arn:aws:ssm:{REGION}::document/AWS-RunShellScript",
            document["Resource"],
        )

        instance = next(
            statement
            for statement in statements
            if statement.get("Action") == "ssm:SendCommand"
            and ":instance/*" in str(statement.get("Resource"))
        )
        self.assertEqual(
            {
                "ssm:resourceTag/ManagedBy": "asg",
                "ssm:resourceTag/Stack": "green",
                "ssm:resourceTag/Service": "api",
            },
            instance["Condition"]["StringEquals"],
        )


class FastDeployControlTest(unittest.TestCase):
    maxDiff = None

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.temp_root = Path(self.temporary_directory.name)
        self.fake_bin = self.temp_root / "bin"
        self.fake_bin.mkdir()
        self.trace_file = self.temp_root / "trace.log"
        self.asg_version_file = self.temp_root / "asg-version"
        self.asg_version_file.write_text("7\n", encoding="utf-8")
        self.candidate_user_data_file = self.temp_root / "candidate-user-data"
        self.ssm_parameters_file = self.temp_root / "ssm-parameters.json"
        self.ssm_prepare_parameters_file = (
            self.temp_root / "ssm-prepare-parameters.json"
        )
        self.cancelled_command_file = self.temp_root / "cancelled-command"
        self.target_health_call_count_file = self.temp_root / "target-health-count"
        self.target_health_call_count_file.write_text("0\n", encoding="utf-8")
        self.source_manifest = "\n".join(
            (
                f"API_IMAGE_URI={OLD_API_IMAGE}",
                f"XGB_IMAGE_URI={OLD_XGB_IMAGE}",
                f"NGINX_IMAGE_URI={NGINX_IMAGE}",
                "BUILDGRAPH_SCHEDULING_ENABLED=false",
                "",
            )
        )
        manifest_marker = base64.b64encode(
            self.source_manifest.encode()
        ).decode()
        source_user_data = textwrap.dedent(
            f"""\
            #!/usr/bin/env bash
            set -Eeuo pipefail
            # BUILDGRAPH_RELEASE_GIT_SHA={SOURCE_SHA}
            # BUILDGRAPH_RELEASE_MANIFEST_B64={manifest_marker}
            /opt/buildgraph/prototype/tools/bootstrap_green_asg.sh
            """
        )
        self.source_user_data = base64.b64encode(
            source_user_data.encode()
        ).decode()
        self._write_fake_aws()
        self._write_fake_git()
        self._write_executable("sleep", "#!/usr/bin/env bash\nexit 0\n")

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def _write_executable(self, name: str, content: str) -> None:
        path = self.fake_bin / name
        path.write_text(content, encoding="utf-8")
        path.chmod(0o755)

    def _write_fake_git(self) -> None:
        self._write_executable(
            "git",
            textwrap.dedent(
                f"""\
                #!/usr/bin/env bash
                set -euo pipefail
                printf 'git:%s\\n' "$*" >>"$FAKE_TRACE_FILE"
                case "${{1:-}}" in
                  rev-parse)
                    printf '%s\\n' "{TARGET_SHA}"
                    ;;
                  merge-base|cat-file)
                    exit 0
                    ;;
                  diff)
                    [[ -z "${{FAKE_GIT_DIFF:-}}" ]] || printf '%s\\n' "$FAKE_GIT_DIFF"
                    ;;
                  *)
                    exit 0
                    ;;
                esac
                """
            ),
        )

    def _write_fake_aws(self) -> None:
        self._write_executable(
            "aws",
            textwrap.dedent(
                f"""\
                #!/usr/bin/env bash
                set -euo pipefail
                service="${{1:-}}"
                action="${{2:-}}"
                shift 2 || true
                printf 'aws:%s %s %s\\n' "$service" "$action" "$*" >>"$FAKE_TRACE_FILE"

                argument_value() {{
                  local key="$1"
                  shift
                  while [[ "$#" -gt 0 ]]; do
                    if [[ "$1" == "$key" ]]; then
                      printf '%s' "${{2:-}}"
                      return 0
                    fi
                    shift
                  done
                  return 1
                }}

                case "$service:$action" in
                  sts:get-caller-identity)
                    printf '%s\\n' "{ACCOUNT_ID}"
                    ;;
                  ecr:describe-repositories)
                    printf '%s\\n' '{{"repositories":[{{"imageTagMutability":"IMMUTABLE"}}]}}'
                    ;;
                  ecr:describe-images)
                    repository="$(argument_value --repository-name "$@")"
                    if [[ "$repository" == "{API_REPOSITORY}" ]]; then
                      printf '%s\\n' 'sha256:{NEW_API_DIGEST}'
                    else
                      printf '%s\\n' 'sha256:{NEW_XGB_DIGEST}'
                    fi
                    ;;
                  autoscaling:describe-auto-scaling-groups)
                    version="$(cat "$FAKE_ASG_VERSION_FILE")"
                    instance_count="${{FAKE_ASG_INSTANCE_COUNT:-1}}"
                    min="${{FAKE_ASG_MIN:-1}}"
                    desired="${{FAKE_ASG_DESIRED:-1}}"
                    max="${{FAKE_ASG_MAX:-1}}"
                    health="${{FAKE_ASG_HEALTH:-Healthy}}"
                    lifecycle="${{FAKE_ASG_LIFECYCLE:-InService}}"
                    instance_id="${{FAKE_ASG_INSTANCE_ID:-{INSTANCE_ID}}}"
                    if [[ "$instance_count" == "1" ]]; then
                      instances="$(jq -nc \
                        --arg id "$instance_id" \
                        --arg health "$health" \
                        --arg lifecycle "$lifecycle" \
                        '[{{InstanceId:$id,HealthStatus:$health,LifecycleState:$lifecycle,LaunchTemplate:{{LaunchTemplateId:"{LAUNCH_TEMPLATE_ID}",Version:"7"}}}}]')"
                    else
                      instances="$(jq -nc --arg id "$instance_id" '[{{InstanceId:$id,HealthStatus:"Healthy",LifecycleState:"InService"}},{{InstanceId:"i-11111111111111111",HealthStatus:"Healthy",LifecycleState:"InService"}}]')"
                    fi
                    jq -nc \
                      --argjson min "$min" \
                      --argjson desired "$desired" \
                      --argjson max "$max" \
                      --arg version "$version" \
                      --argjson instances "$instances" \
                      '{{AutoScalingGroups:[{{AutoScalingGroupName:"{ASG_NAME}",MinSize:$min,DesiredCapacity:$desired,MaxSize:$max,LaunchTemplate:{{LaunchTemplateId:"{LAUNCH_TEMPLATE_ID}",LaunchTemplateName:"buildgraph-demo-api-green-lt",Version:$version}},TargetGroupARNs:["{TARGET_GROUP_ARN}"],Instances:$instances}}]}}'
                    ;;
                  autoscaling:describe-instance-refreshes)
                    printf '%s\\n' "${{FAKE_ACTIVE_REFRESH_STATUS:-None}}"
                    ;;
                  elbv2:describe-target-health)
                    count="${{FAKE_TARGET_COUNT:-1}}"
                    state="${{FAKE_TARGET_HEALTH:-healthy}}"
                    call_count="$(( $(cat "$FAKE_TARGET_HEALTH_CALL_COUNT_FILE") + 1 ))"
                    printf '%s\n' "$call_count" >"$FAKE_TARGET_HEALTH_CALL_COUNT_FILE"
                    if [[ -n "${{FAKE_TARGET_HEALTH_AFTER_CALL:-}}" && "$call_count" -ge "$FAKE_TARGET_HEALTH_AFTER_CALL" ]]; then
                      state="${{FAKE_TARGET_HEALTH_AFTER:-unhealthy}}"
                    fi
                    instance_id="${{FAKE_TARGET_INSTANCE_ID:-${{FAKE_ASG_INSTANCE_ID:-{INSTANCE_ID}}}}}"
                    if [[ "$count" == "1" ]]; then
                      jq -nc --arg id "$instance_id" --arg state "$state" '{{TargetHealthDescriptions:[{{Target:{{Id:$id,Port:80}},TargetHealth:{{State:$state}}}}]}}'
                    else
                      printf '%s\\n' '{{"TargetHealthDescriptions":[]}}'
                    fi
                    ;;
                  ssm:describe-instance-information)
                    instance_id="${{FAKE_ASG_INSTANCE_ID:-{INSTANCE_ID}}}"
                    status="${{FAKE_SSM_PING_STATUS:-Online}}"
                    jq -nc --arg id "$instance_id" --arg status "$status" '{{InstanceInformationList:[{{InstanceId:$id,PingStatus:$status}}]}}'
                    ;;
                  ec2:describe-launch-template-versions)
                    requested="$(argument_value --versions "$@" || true)"
                    if [[ "$requested" == "8" ]]; then
                      user_data="$(cat "$FAKE_CANDIDATE_USER_DATA_FILE")"
                    else
                      user_data="$FAKE_SOURCE_USER_DATA"
                      requested=7
                    fi
                    jq -nc --argjson version "$requested" --arg user_data "$user_data" '{{LaunchTemplateVersions:[{{VersionNumber:$version,LaunchTemplateData:{{ImageId:"ami-0greenfasttest",UserData:$user_data}}}}]}}'
                    ;;
                  ec2:describe-images)
                    printf '%s\\n' '{{"Images":[{{"ImageId":"ami-0greenfasttest","State":"available","Tags":[{{"Key":"Validation","Value":"passed"}}]}}]}}'
                    ;;
                  ssm:send-command)
                    [[ "${{FAKE_SEND_COMMAND_FAIL:-false}}" != "true" ]] || exit 81
                    parameters="$(argument_value --parameters "$@")"
                    [[ "$parameters" == file://* ]] || exit 82
                    cp "${{parameters#file://}}" "$FAKE_SSM_PARAMETERS_FILE"
                    comment="$(argument_value --comment "$@" || true)"
                    case "$comment" in
                      *rollback*) printf '%s\\n' command-rollback ;;
                      *commit*) printf '%s\\n' command-commit ;;
                      *cleanup*) printf '%s\\n' command-cleanup ;;
                      *)
                        cp "${{parameters#file://}}" "$FAKE_SSM_PREPARE_PARAMETERS_FILE"
                        printf '%s\\n' command-prepare
                        ;;
                    esac
                    ;;
                  ssm:get-command-invocation)
                    command_id="$(argument_value --command-id "$@")"
                    case "$command_id" in
                      command-commit)
                        printf '%s\\n' "${{FAKE_COMMIT_COMMAND_STATUS:-${{FAKE_SSM_COMMAND_STATUS:-Success}}}}"
                        ;;
                      command-cleanup)
                        printf '%s\\n' "${{FAKE_CLEANUP_COMMAND_STATUS:-${{FAKE_SSM_COMMAND_STATUS:-Success}}}}"
                        ;;
                      command-rollback)
                        printf '%s\\n' "${{FAKE_ROLLBACK_COMMAND_STATUS:-${{FAKE_SSM_COMMAND_STATUS:-Success}}}}"
                        ;;
                      *)
                        if [[ -e "$FAKE_CANCELLED_COMMAND_FILE" && "${{FAKE_CANCEL_REACHES_TERMINAL:-true}}" == "true" ]]; then
                          printf '%s\\n' Cancelled
                        else
                          printf '%s\\n' "${{FAKE_SSM_COMMAND_STATUS:-Success}}"
                        fi
                        ;;
                    esac
                    ;;
                  ssm:cancel-command)
                    : >"$FAKE_CANCELLED_COMMAND_FILE"
                    exit 0
                    ;;
                  ec2:create-launch-template-version)
                    [[ "${{FAKE_LT_CREATE_FAIL:-false}}" != "true" ]] || exit 83
                    data="$(argument_value --launch-template-data "$@")"
                    [[ "$data" == file://* ]] || exit 84
                    jq -r '.UserData' "${{data#file://}}" >"$FAKE_CANDIDATE_USER_DATA_FILE"
                    printf '%s\\n' 8
                    ;;
                  autoscaling:update-auto-scaling-group)
                    launch_template="$(argument_value --launch-template "$@")"
                    version="${{launch_template##*Version=}}"
                    [[ "$version" =~ ^[0-9]+$ ]] || exit 86
                    if [[ "${{FAKE_ASG_UPDATE_LOST_RESPONSE:-false}}" == "true" ]]; then
                      printf '%s\\n' "$version" >"$FAKE_ASG_VERSION_FILE"
                      exit 85
                    fi
                    [[ "${{FAKE_ASG_UPDATE_FAIL:-false}}" != "true" ]] || exit 85
                    printf '%s\\n' "$version" >"$FAKE_ASG_VERSION_FILE"
                    ;;
                  ec2:run-instances|autoscaling:start-instance-refresh|autoscaling:rollback-instance-refresh|cloudfront:*)
                    echo 'forbidden Fast Deploy mutation' >&2
                    exit 96
                    ;;
                  *)
                    echo "unexpected fake aws invocation: $service $action $*" >&2
                    exit 91
                    ;;
                esac
                """
            ),
        )

    def _run(
        self,
        service: str = "api",
        *,
        apply: bool = False,
        **overrides: str,
    ) -> subprocess.CompletedProcess[str]:
        command = [
            "bash",
            str(CONTROL_SCRIPT),
            "--service",
            service,
            "--git-sha",
            TARGET_SHA,
            "--image-tag",
            TARGET_SHA,
        ]
        if apply:
            command.append("--apply")
        env = {
            **os.environ,
            "PATH": f"{self.fake_bin}:{os.environ['PATH']}",
            "AWS_REGION": REGION,
            "AWS_ACCOUNT_ID": ACCOUNT_ID,
            "FAKE_TRACE_FILE": str(self.trace_file),
            "FAKE_ASG_VERSION_FILE": str(self.asg_version_file),
            "FAKE_CANDIDATE_USER_DATA_FILE": str(
                self.candidate_user_data_file
            ),
            "FAKE_SSM_PARAMETERS_FILE": str(self.ssm_parameters_file),
            "FAKE_SSM_PREPARE_PARAMETERS_FILE": str(
                self.ssm_prepare_parameters_file
            ),
            "FAKE_CANCELLED_COMMAND_FILE": str(
                self.cancelled_command_file
            ),
            "FAKE_TARGET_HEALTH_CALL_COUNT_FILE": str(
                self.target_health_call_count_file
            ),
            "FAKE_SOURCE_USER_DATA": self.source_user_data,
            "BUILDGRAPH_SSM_POLL_SECONDS": "0",
            "BUILDGRAPH_SSM_MAX_ATTEMPTS": "2",
            "BUILDGRAPH_TARGET_HEALTH_POLL_SECONDS": "0",
            "BUILDGRAPH_TARGET_HEALTH_MAX_ATTEMPTS": "2",
            **overrides,
        }
        return subprocess.run(
            command,
            cwd=ROOT,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )

    def _trace_lines(self) -> list[str]:
        if not self.trace_file.exists():
            return []
        return self.trace_file.read_text(encoding="utf-8").splitlines()

    def _mutation_lines(self) -> list[str]:
        mutations = (
            "aws:ssm send-command",
            "aws:ssm cancel-command",
            "aws:ec2 create-launch-template-version",
            "aws:autoscaling update-auto-scaling-group",
        )
        return [
            line
            for line in self._trace_lines()
            if any(marker in line for marker in mutations)
        ]

    def _candidate_user_data(self) -> str:
        encoded = self.candidate_user_data_file.read_text(
            encoding="utf-8"
        ).strip()
        return base64.b64decode(encoded).decode()

    def test_default_preflight_performs_zero_mutations(self) -> None:
        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual([], self._mutation_lines(), result.stdout)
        self.assertIn("read-only", result.stdout.lower())

    def test_rejects_invalid_capacity_before_mutation(self) -> None:
        result = self._run(apply=True, FAKE_ASG_MAX="3")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("1 / Desired 1 / Max 1", result.stdout)
        self.assertEqual([], self._mutation_lines(), result.stdout)

    def test_rejects_active_refresh_before_mutation(self) -> None:
        result = self._run(
            apply=True,
            FAKE_ACTIVE_REFRESH_STATUS="InProgress",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("Instance Refresh", result.stdout)
        self.assertEqual([], self._mutation_lines(), result.stdout)

    def test_rejects_multiple_instances_target_mismatch_or_ssm_offline(self) -> None:
        cases = (
            {"FAKE_ASG_INSTANCE_COUNT": "2"},
            {"FAKE_TARGET_INSTANCE_ID": "i-99999999999999999"},
            {"FAKE_SSM_PING_STATUS": "ConnectionLost"},
        )
        for overrides in cases:
            with self.subTest(overrides=overrides):
                self.trace_file.unlink(missing_ok=True)
                result = self._run(apply=True, **overrides)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertEqual([], self._mutation_lines(), result.stdout)

    def test_rejects_bootstrap_or_default_manifest_change_before_ssm(self) -> None:
        for changed in (
            "tools/bootstrap_green_asg.sh",
            "infra/asg/green-release.env",
            "compose.api.ecr.prod.yaml",
            "infra/nginx/api.conf",
        ):
            with self.subTest(changed=changed):
                self.trace_file.unlink(missing_ok=True)
                result = self._run(apply=True, FAKE_GIT_DIFF=changed)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertIn("Release Green Web ASG", result.stdout)
                self.assertEqual([], self._mutation_lines(), result.stdout)

    def test_apply_orders_ssm_health_lt_creation_and_pointer_update(self) -> None:
        result = self._run(apply=True)

        self.assertEqual(0, result.returncode, result.stdout)
        lines = self._trace_lines()
        prepare = next(
            index
            for index, line in enumerate(lines)
            if "aws:ssm send-command" in line and "prepare" in line
        )
        health = next(
            index
            for index, line in enumerate(lines[prepare + 1 :], prepare + 1)
            if "aws:elbv2 describe-target-health" in line
        )
        create = next(
            index
            for index, line in enumerate(lines)
            if "aws:ec2 create-launch-template-version" in line
        )
        update = next(
            index
            for index, line in enumerate(lines)
            if "aws:autoscaling update-auto-scaling-group" in line
            and "Version=8" in line
        )
        self.assertLess(prepare, health)
        self.assertLess(health, create)
        self.assertLess(create, update)
        self.assertEqual("8", self.asg_version_file.read_text().strip())
        self.assertIn("completed", result.stdout.lower())

    def test_ssm_command_enters_bash_before_enabling_pipefail(self) -> None:
        result = self._run(apply=True)

        self.assertEqual(0, result.returncode, result.stdout)
        command = json.loads(
            self.ssm_prepare_parameters_file.read_text(encoding="utf-8")
        )["commands"][0]
        self.assertTrue(
            command.startswith(
                "/usr/bin/env bash <<'BUILDGRAPH_FAST_DEPLOY_REMOTE'\n"
                "set -Eeuo pipefail\n"
            ),
            command,
        )
        self.assertNotIn(r'\"', command, command)
        self.assertIn(
            'readonly helper_path="$(mktemp '
            '/tmp/buildgraph-fast-deploy.XXXXXX)"',
            command,
        )
        self.assertIn(
            'runuser -u "$app_user" -- git -C "$app_root" fetch',
            command,
        )
        self.assertTrue(
            command.rstrip().endswith("BUILDGRAPH_FAST_DEPLOY_REMOTE"),
            command,
        )
        syntax = subprocess.run(
            ["/bin/sh", "-n"],
            input=command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )
        self.assertEqual(0, syntax.returncode, syntax.stdout)

    def test_api_digest_replacement_preserves_four_key_contract(self) -> None:
        result = self._run(apply=True)

        self.assertEqual(0, result.returncode, result.stdout)
        user_data = self._candidate_user_data()
        self.assertIn(f"API_IMAGE_URI={NEW_API_IMAGE}", user_data)
        self.assertIn(f"XGB_IMAGE_URI={OLD_XGB_IMAGE}", user_data)
        self.assertIn(f"NGINX_IMAGE_URI={NGINX_IMAGE}", user_data)
        self.assertIn("BUILDGRAPH_SCHEDULING_ENABLED=false", user_data)
        self.assertNotIn(f":{TARGET_SHA}", user_data)

    def test_xgb_digest_replacement_preserves_four_key_contract(self) -> None:
        result = self._run("xgb-reranker", apply=True)

        self.assertEqual(0, result.returncode, result.stdout)
        user_data = self._candidate_user_data()
        self.assertIn(f"API_IMAGE_URI={OLD_API_IMAGE}", user_data)
        self.assertIn(f"XGB_IMAGE_URI={NEW_XGB_IMAGE}", user_data)
        self.assertIn(f"NGINX_IMAGE_URI={NGINX_IMAGE}", user_data)
        self.assertIn("BUILDGRAPH_SCHEDULING_ENABLED=false", user_data)

    def test_ssm_failure_stops_before_launch_template_mutation(self) -> None:
        result = self._run(
            apply=True,
            FAKE_SSM_COMMAND_STATUS="Failed",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertFalse(
            any(
                "aws:ec2 create-launch-template-version" in line
                for line in self._trace_lines()
            ),
            self._trace_lines(),
        )
        self.assertEqual("7", self.asg_version_file.read_text().strip())

    def test_ssm_timeout_cancels_prepare_and_requests_rollback(self) -> None:
        result = self._run(
            apply=True,
            FAKE_SSM_COMMAND_STATUS="InProgress",
            FAKE_ROLLBACK_COMMAND_STATUS="Success",
        )

        self.assertNotEqual(0, result.returncode)
        lines = self._trace_lines()
        self.assertTrue(
            any("aws:ssm cancel-command" in line for line in lines),
            lines,
        )
        self.assertTrue(
            any(
                "aws:ssm send-command" in line and "rollback" in line
                for line in lines
            ),
            lines,
        )
        cancel_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:ssm cancel-command" in line
        )
        rollback_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:ssm send-command" in line and "rollback" in line
        )
        self.assertLess(cancel_index, rollback_index)
        self.assertFalse(
            any(
                "aws:ec2 create-launch-template-version" in line
                for line in lines
            ),
            lines,
        )
        rollback_command = json.loads(
            self.ssm_parameters_file.read_text(encoding="utf-8")
        )["commands"][0]
        self.assertNotIn(r'\"', rollback_command, rollback_command)
        self.assertIn(
            'readonly cancellation_fence="$transaction_root/.cancelled-fast-',
            rollback_command,
        )
        self.assertIn(".cancelled-fast-", rollback_command)
        self.assertLess(
            rollback_command.index("readonly cancellation_fence"),
            rollback_command.index("runuser -u"),
        )
        self.assertNotIn("fetch --no-tags origin", rollback_command)

    def test_ambiguous_cancel_still_dispatches_fenced_rollback(self) -> None:
        result = self._run(
            apply=True,
            FAKE_SSM_COMMAND_STATUS="InProgress",
            FAKE_CANCEL_REACHES_TERMINAL="false",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("requested cancellation", result.stdout)
        self.assertTrue(
            any(
                "aws:ssm send-command" in line and "rollback" in line
                for line in self._trace_lines()
            ),
            self._trace_lines(),
        )

    def test_asg_update_failure_requests_remote_rollback(self) -> None:
        result = self._run(apply=True, FAKE_ASG_UPDATE_FAIL="true")

        self.assertNotEqual(0, result.returncode)
        lines = self._trace_lines()
        self.assertTrue(
            any(
                "aws:ssm send-command" in line and "rollback" in line
                for line in lines
            ),
            lines,
        )
        self.assertEqual("7", self.asg_version_file.read_text().strip())

    def test_lost_asg_update_response_restores_observed_pointer(self) -> None:
        result = self._run(
            apply=True,
            FAKE_ASG_UPDATE_LOST_RESPONSE="true",
        )

        self.assertNotEqual(0, result.returncode)
        lines = self._trace_lines()
        self.assertTrue(
            any(
                "aws:autoscaling update-auto-scaling-group" in line
                and "Version=7" in line
                for line in lines
            ),
            lines,
        )
        self.assertTrue(
            any(
                "aws:ssm send-command" in line and "rollback" in line
                for line in lines
            ),
            lines,
        )
        self.assertEqual("7", self.asg_version_file.read_text().strip())

    def test_post_ssm_target_health_failure_rolls_back_before_lt_creation(self) -> None:
        result = self._run(
            apply=True,
            FAKE_TARGET_HEALTH_AFTER_CALL="2",
            FAKE_TARGET_HEALTH_AFTER="unhealthy",
        )

        self.assertNotEqual(0, result.returncode)
        lines = self._trace_lines()
        self.assertTrue(
            any(
                "aws:ssm send-command" in line and "rollback" in line
                for line in lines
            ),
            lines,
        )
        self.assertFalse(
            any(
                "aws:ec2 create-launch-template-version" in line
                for line in lines
            ),
            lines,
        )
        self.assertEqual("7", self.asg_version_file.read_text().strip())

    def test_launch_template_creation_failure_rolls_back_runtime(self) -> None:
        result = self._run(apply=True, FAKE_LT_CREATE_FAIL="true")

        self.assertNotEqual(0, result.returncode)
        lines = self._trace_lines()
        self.assertTrue(
            any(
                "aws:ssm send-command" in line and "rollback" in line
                for line in lines
            ),
            lines,
        )
        self.assertFalse(
            any(
                "aws:autoscaling update-auto-scaling-group" in line
                for line in lines
            ),
            lines,
        )
        self.assertEqual("7", self.asg_version_file.read_text().strip())

    def test_commit_failure_restores_pointer_and_runtime_transaction(self) -> None:
        result = self._run(
            apply=True,
            FAKE_COMMIT_COMMAND_STATUS="Failed",
            FAKE_ROLLBACK_COMMAND_STATUS="Success",
        )

        self.assertNotEqual(0, result.returncode)
        lines = self._trace_lines()
        self.assertTrue(
            any(
                "aws:autoscaling update-auto-scaling-group" in line
                and "Version=7" in line
                for line in lines
            ),
            lines,
        )
        self.assertTrue(
            any(
                "aws:ssm send-command" in line and "rollback" in line
                for line in lines
            ),
            lines,
        )
        self.assertEqual("7", self.asg_version_file.read_text().strip())

    def test_cleanup_failure_keeps_aligned_successful_release(self) -> None:
        result = self._run(
            apply=True,
            FAKE_CLEANUP_COMMAND_STATUS="Failed",
        )

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("cleanup must be retried", result.stdout)
        self.assertEqual("8", self.asg_version_file.read_text().strip())
        self.assertFalse(
            any(
                "aws:ssm send-command" in line and "rollback" in line
                for line in self._trace_lines()
            ),
            self._trace_lines(),
        )

    def test_success_keeps_same_instance_while_asg_pointer_advances(self) -> None:
        result = self._run(apply=True)

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual("8", self.asg_version_file.read_text().strip())
        descriptions = [
            line
            for line in self._trace_lines()
            if "aws:autoscaling describe-auto-scaling-groups" in line
        ]
        self.assertGreaterEqual(len(descriptions), 2)
        self.assertNotIn("start-instance-refresh", "\n".join(self._trace_lines()))


class FastDeployRemoteTransactionTest(unittest.TestCase):
    maxDiff = None

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.temp_root = Path(self.temporary_directory.name)
        self.fake_bin = self.temp_root / "bin"
        self.fake_bin.mkdir()
        self.app_root = self.temp_root / "prototype"
        (self.app_root / ".git").mkdir(parents=True)
        (self.app_root / "compose.api.ecr.prod.yaml").write_text(
            "services: {}\n", encoding="utf-8"
        )
        (self.app_root / ".env.prod").write_text(
            "DB_PASSWORD=must-not-appear-in-output\n", encoding="utf-8"
        )
        self.image_manifest = self.temp_root / "green-images.env"
        self.runtime_env = self.temp_root / "asg-runtime.env"
        self.success_marker = self.temp_root / "asg-bootstrap-success"
        self.transaction_root = self.temp_root / "transactions"
        self.release_root = self.temp_root / "releases"
        self.lock_file = self.temp_root / "green-deploy.lock"
        self.git_head_file = self.temp_root / "git-head"
        self.git_head_file.write_text(f"{SOURCE_SHA}\n", encoding="utf-8")
        self.fail_up_once_file = self.temp_root / "fail-up-once"
        self.trace_file = self.temp_root / "remote-trace.log"
        self.deployment_id = "fast-test-1234"
        self.source_manifest = self._manifest(OLD_API_IMAGE, OLD_XGB_IMAGE)
        self._write_runtime(self.source_manifest)
        self.success_marker.write_text(
            f"git_sha={SOURCE_SHA}\n", encoding="utf-8"
        )
        self.success_marker.chmod(0o600)
        self._write_fake_commands()

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    @staticmethod
    def _manifest(api_image: str, xgb_image: str) -> str:
        return "\n".join(
            (
                f"API_IMAGE_URI={api_image}",
                f"XGB_IMAGE_URI={xgb_image}",
                f"NGINX_IMAGE_URI={NGINX_IMAGE}",
                "BUILDGRAPH_SCHEDULING_ENABLED=false",
                "",
            )
        )

    def _write_runtime(self, manifest: str) -> None:
        values = dict(
            line.split("=", 1)
            for line in manifest.splitlines()
            if line
        )
        self.image_manifest.write_text(
            "\n".join(
                (
                    f"API_IMAGE_URI={values['API_IMAGE_URI']}",
                    f"XGB_IMAGE_URI={values['XGB_IMAGE_URI']}",
                    "",
                )
            ),
            encoding="utf-8",
        )
        self.runtime_env.write_text(
            "\n".join(
                (
                    f"NGINX_IMAGE_URI={values['NGINX_IMAGE_URI']}",
                    "BUILDGRAPH_SCHEDULING_ENABLED=false",
                    "AWS_STS_REGIONAL_ENDPOINTS=regional",
                    "",
                )
            ),
            encoding="utf-8",
        )
        self.image_manifest.chmod(0o600)
        self.runtime_env.chmod(0o600)

    def _write_executable(self, name: str, content: str) -> None:
        path = self.fake_bin / name
        path.write_text(content, encoding="utf-8")
        path.chmod(0o755)

    def _write_fake_commands(self) -> None:
        self._write_executable(
            "runuser",
            textwrap.dedent(
                """\
                #!/usr/bin/env bash
                set -euo pipefail
                while [[ "$#" -gt 0 && "$1" != "--" ]]; do shift; done
                [[ "${1:-}" == "--" ]] && shift
                exec "$@"
                """
            ),
        )
        self._write_executable(
            "git",
            textwrap.dedent(
                """\
                #!/usr/bin/env bash
                set -euo pipefail
                printf 'git:%s\\n' "$*" >>"$FAKE_REMOTE_TRACE_FILE"
                if [[ "${1:-}" == "-C" ]]; then shift 2; fi
                case "${1:-}" in
                  rev-parse)
                    cat "$FAKE_GIT_HEAD_FILE"
                    ;;
                  checkout)
                    [[ "${2:-}" == "--detach" ]]
                    if [[ "${3:-}" == "$FAKE_SOURCE_GIT_SHA" && "${FAKE_FAIL_SOURCE_CHECKOUT:-false}" == "true" ]]; then
                      exit 43
                    fi
                    printf '%s\\n' "${3:-}" >"$FAKE_GIT_HEAD_FILE"
                    ;;
                  diff)
                    [[ "${FAKE_GIT_DIRTY:-false}" != "true" ]]
                    ;;
                  fetch|cat-file|merge-base)
                    exit 0
                    ;;
                  *)
                    echo "unexpected fake git invocation: $*" >&2
                    exit 91
                    ;;
                esac
                """
            ),
        )
        self._write_executable(
            "aws",
            textwrap.dedent(
                """\
                #!/usr/bin/env bash
                set -euo pipefail
                printf 'aws:%s\\n' "$*" >>"$FAKE_REMOTE_TRACE_FILE"
                [[ "${1:-}" == "ecr" && "${2:-}" == "get-login-password" ]]
                printf '%s\\n' token
                """
            ),
        )
        self._write_executable(
            "curl",
            "#!/usr/bin/env bash\nset -euo pipefail\nexit 0\n",
        )
        self._write_executable(
            "chown",
            "#!/usr/bin/env bash\nset -euo pipefail\nexit 0\n",
        )
        self._write_executable(
            "flock",
            "#!/usr/bin/env bash\nset -euo pipefail\nexit 0\n",
        )
        self._write_executable(
            "docker",
            textwrap.dedent(
                """\
                #!/usr/bin/env bash
                set -euo pipefail
                printf 'docker:%s\\n' "$*" >>"$FAKE_REMOTE_TRACE_FILE"

                manifest_value() {
                  local key="$1"
                  local file="$2"
                  sed -n "s/^${key}=//p" "$file"
                }

                if [[ "${1:-}" == "login" ]]; then
                  cat >/dev/null
                  exit 0
                fi
                if [[ "${1:-}" == "inspect" ]]; then
                  format="${3:-}"
                  container_id="${4:-}"
                  if [[ "$format" == *State.Health.Status* ]]; then
                    printf '%s\\n' healthy
                    exit 0
                  fi
                  if [[ "$format" == *BUILDGRAPH_SCHEDULING_ENABLED=false* ]]; then
                    printf '%s\\n' "${FAKE_API_SCHEDULING_DISABLED:-true}"
                    exit 0
                  fi
                  case "$container_id" in
                    cid-nginx)
                      manifest_value NGINX_IMAGE_URI "$FAKE_RUNTIME_ENV"
                      ;;
                    cid-api|cid-recommendation-event-worker)
                      manifest_value API_IMAGE_URI "$FAKE_IMAGE_MANIFEST"
                      ;;
                    cid-xgb-reranker)
                      manifest_value XGB_IMAGE_URI "$FAKE_IMAGE_MANIFEST"
                      ;;
                    *)
                      exit 92
                      ;;
                  esac
                  exit 0
                fi
                [[ "${1:-}" == "compose" ]] || exit 93
                args=" $* "
                if [[ "$args" == *" ps -q nginx "* ]]; then
                  printf '%s\\n' cid-nginx
                elif [[ "$args" == *" ps -q api "* ]]; then
                  printf '%s\\n' cid-api
                elif [[ "$args" == *" ps -q recommendation-event-worker "* ]]; then
                  printf '%s\\n' cid-recommendation-event-worker
                elif [[ "$args" == *" ps -q xgb-reranker "* ]]; then
                  printf '%s\\n' cid-xgb-reranker
                elif [[ "$args" == *" up -d "* && "${FAKE_SIGNAL_PARENT_ON_TARGET_UP:-false}" == "true" && "$(manifest_value API_IMAGE_URI "$FAKE_IMAGE_MANIFEST")" == "$FAKE_TARGET_API_IMAGE" ]]; then
                  kill -TERM "$PPID"
                  sleep 0.1
                  exit 143
                elif [[ "$args" == *" up -d "* && "${FAKE_FAIL_UP_ONCE:-false}" == "true" && ! -e "$FAKE_FAIL_UP_ONCE_FILE" ]]; then
                  : >"$FAKE_FAIL_UP_ONCE_FILE"
                  exit 42
                else
                  exit 0
                fi
                """
            ),
        )

    def _run(
        self,
        action: str,
        *,
        service: str = "api",
        target_manifest: str | None = None,
        fail_up_once: bool = False,
        fail_source_checkout: bool = False,
        signal_parent_on_target_up: bool = False,
        api_scheduling_disabled: bool = True,
        git_dirty: bool = False,
    ) -> subprocess.CompletedProcess[str]:
        command = [
            "bash",
            str(REMOTE_SCRIPT),
            action,
            "--deployment-id",
            self.deployment_id,
        ]
        if action == "prepare":
            target = target_manifest or self._manifest(
                NEW_API_IMAGE, OLD_XGB_IMAGE
            )
            command.extend(
                (
                    "--service",
                    service,
                    "--source-git-sha",
                    SOURCE_SHA,
                    "--target-git-sha",
                    TARGET_SHA,
                    "--source-manifest-b64",
                    base64.b64encode(self.source_manifest.encode()).decode(),
                    "--target-manifest-b64",
                    base64.b64encode(target.encode()).decode(),
                )
            )
        env = {
            **os.environ,
            "PATH": f"{self.fake_bin}:{os.environ['PATH']}",
            "BUILDGRAPH_APP_ROOT": str(self.app_root),
            "BUILDGRAPH_APP_USER": "test-user",
            "BUILDGRAPH_FILE_OWNER": f"{os.getuid()}:{os.getgid()}",
            "GREEN_IMAGE_MANIFEST": str(self.image_manifest),
            "BUILDGRAPH_ASG_RUNTIME_ENV": str(self.runtime_env),
            "BUILDGRAPH_ASG_SUCCESS_MARKER": str(self.success_marker),
            "BUILDGRAPH_RELEASE_ROOT": str(self.release_root),
            "BUILDGRAPH_FAST_DEPLOY_STATE_DIR": str(
                self.transaction_root
            ),
            "GREEN_DEPLOY_LOCK_FILE": str(self.lock_file),
            "BUILDGRAPH_FAST_HEALTH_MAX_ATTEMPTS": "2",
            "BUILDGRAPH_FAST_HEALTH_POLL_SECONDS": "0",
            "FAKE_GIT_HEAD_FILE": str(self.git_head_file),
            "FAKE_SOURCE_GIT_SHA": SOURCE_SHA,
            "FAKE_FAIL_SOURCE_CHECKOUT": (
                "true" if fail_source_checkout else "false"
            ),
            "FAKE_IMAGE_MANIFEST": str(self.image_manifest),
            "FAKE_RUNTIME_ENV": str(self.runtime_env),
            "FAKE_FAIL_UP_ONCE_FILE": str(self.fail_up_once_file),
            "FAKE_REMOTE_TRACE_FILE": str(self.trace_file),
            "FAKE_FAIL_UP_ONCE": "true" if fail_up_once else "false",
            "FAKE_SIGNAL_PARENT_ON_TARGET_UP": (
                "true" if signal_parent_on_target_up else "false"
            ),
            "FAKE_TARGET_API_IMAGE": NEW_API_IMAGE,
            "FAKE_API_SCHEDULING_DISABLED": (
                "true" if api_scheduling_disabled else "false"
            ),
            "FAKE_GIT_DIRTY": "true" if git_dirty else "false",
        }
        return subprocess.run(
            command,
            cwd=ROOT,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )

    def test_fake_commands_begin_with_an_executable_bash_shebang(self) -> None:
        for path in sorted(self.fake_bin.iterdir()):
            with self.subTest(command=path.name):
                self.assertTrue(
                    path.read_bytes().startswith(b"#!/usr/bin/env bash\n"),
                    f"{path.name} does not start with a Bash shebang",
                )
                syntax = subprocess.run(
                    ["bash", "-n", str(path)],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    check=False,
                )
                self.assertEqual(0, syntax.returncode, syntax.stdout)

    def _runtime_values(self) -> dict[str, str]:
        return dict(
            line.split("=", 1)
            for line in (
                self.image_manifest.read_text(encoding="utf-8").splitlines()
                + self.runtime_env.read_text(encoding="utf-8").splitlines()
            )
            if line
        )

    def test_rollback_before_prepare_fences_a_late_prepare(self) -> None:
        rolled_back = self._run("rollback")

        self.assertEqual(0, rolled_back.returncode, rolled_back.stdout)
        fence = self.transaction_root / f".cancelled-{self.deployment_id}"
        self.assertTrue(fence.is_file())

        late_prepare = self._run("prepare")

        self.assertNotEqual(0, late_prepare.returncode, late_prepare.stdout)
        self.assertIn("cancellation fence", late_prepare.stdout)
        self.assertEqual(SOURCE_SHA, self.git_head_file.read_text().strip())
        self.assertEqual(OLD_API_IMAGE, self._runtime_values()["API_IMAGE_URI"])
        self.assertEqual(OLD_XGB_IMAGE, self._runtime_values()["XGB_IMAGE_URI"])
        self.assertFalse(
            (self.transaction_root / self.deployment_id).exists()
        )

    def test_api_prepare_and_explicit_rollback_restore_all_runtime_state(
        self,
    ) -> None:
        prepared = self._run("prepare")

        self.assertEqual(0, prepared.returncode, prepared.stdout)
        self.assertNotIn("must-not-appear-in-output", prepared.stdout)
        self.assertEqual(TARGET_SHA, self.git_head_file.read_text().strip())
        self.assertEqual(NEW_API_IMAGE, self._runtime_values()["API_IMAGE_URI"])
        self.assertEqual(OLD_XGB_IMAGE, self._runtime_values()["XGB_IMAGE_URI"])
        self.assertEqual(
            "prepared",
            (
                self.transaction_root
                / self.deployment_id
                / "status"
            ).read_text(encoding="utf-8").strip(),
        )

        rolled_back = self._run("rollback")

        self.assertEqual(0, rolled_back.returncode, rolled_back.stdout)
        self.assertEqual(SOURCE_SHA, self.git_head_file.read_text().strip())
        self.assertEqual(OLD_API_IMAGE, self._runtime_values()["API_IMAGE_URI"])
        self.assertEqual(OLD_XGB_IMAGE, self._runtime_values()["XGB_IMAGE_URI"])
        self.assertFalse(
            (self.transaction_root / self.deployment_id).exists()
        )
        self.assertEqual(
            self.source_manifest,
            (
                self.release_root / f"green-release-{SOURCE_SHA}.env"
            ).read_text(encoding="utf-8"),
        )
        self.assertFalse(
            (self.release_root / f"green-release-{TARGET_SHA}.env").exists()
        )
        self.assertEqual(
            0o600,
            self.image_manifest.stat().st_mode & 0o777,
        )

    def test_xgb_prepare_and_commit_keep_only_xgb_digest_change(self) -> None:
        target = self._manifest(OLD_API_IMAGE, NEW_XGB_IMAGE)
        prepared = self._run(
            "prepare",
            service="xgb-reranker",
            target_manifest=target,
        )

        self.assertEqual(0, prepared.returncode, prepared.stdout)
        committed = self._run("commit")
        self.assertEqual(0, committed.returncode, committed.stdout)
        cleaned = self._run("cleanup")
        self.assertEqual(0, cleaned.returncode, cleaned.stdout)
        self.assertEqual(TARGET_SHA, self.git_head_file.read_text().strip())
        self.assertEqual(OLD_API_IMAGE, self._runtime_values()["API_IMAGE_URI"])
        self.assertEqual(NEW_XGB_IMAGE, self._runtime_values()["XGB_IMAGE_URI"])
        self.assertFalse(
            (self.transaction_root / self.deployment_id).exists()
        )

    def test_xgb_prepare_and_rollback_restore_source_digest(self) -> None:
        target = self._manifest(OLD_API_IMAGE, NEW_XGB_IMAGE)
        prepared = self._run(
            "prepare",
            service="xgb-reranker",
            target_manifest=target,
        )
        self.assertEqual(0, prepared.returncode, prepared.stdout)
        self.assertEqual(NEW_XGB_IMAGE, self._runtime_values()["XGB_IMAGE_URI"])

        rolled_back = self._run("rollback")

        self.assertEqual(0, rolled_back.returncode, rolled_back.stdout)
        self.assertEqual(SOURCE_SHA, self.git_head_file.read_text().strip())
        self.assertEqual(OLD_API_IMAGE, self._runtime_values()["API_IMAGE_URI"])
        self.assertEqual(OLD_XGB_IMAGE, self._runtime_values()["XGB_IMAGE_URI"])
        self.assertFalse(
            (self.transaction_root / self.deployment_id).exists()
        )

    def test_failed_container_replacement_rolls_back_before_exit(self) -> None:
        failed = self._run("prepare", fail_up_once=True)

        self.assertNotEqual(0, failed.returncode, failed.stdout)
        self.assertIn("restoring the previous runtime", failed.stdout)
        self.assertEqual(SOURCE_SHA, self.git_head_file.read_text().strip())
        self.assertEqual(OLD_API_IMAGE, self._runtime_values()["API_IMAGE_URI"])
        self.assertEqual(OLD_XGB_IMAGE, self._runtime_values()["XGB_IMAGE_URI"])
        self.assertFalse(
            (self.transaction_root / self.deployment_id).exists()
        )

    def test_actual_api_scheduler_state_is_checked_before_mutation(self) -> None:
        rejected = self._run(
            "prepare",
            api_scheduling_disabled=False,
        )

        self.assertNotEqual(0, rejected.returncode, rejected.stdout)
        self.assertIn("scheduling must remain disabled", rejected.stdout)
        self.assertEqual(SOURCE_SHA, self.git_head_file.read_text().strip())
        self.assertEqual(OLD_API_IMAGE, self._runtime_values()["API_IMAGE_URI"])
        self.assertFalse(
            (self.transaction_root / self.deployment_id).exists()
        )

    def test_dirty_runtime_git_tree_is_rejected_before_mutation(self) -> None:
        rejected = self._run("prepare", git_dirty=True)

        self.assertNotEqual(0, rejected.returncode, rejected.stdout)
        self.assertIn("working tree", rejected.stdout)
        self.assertEqual(SOURCE_SHA, self.git_head_file.read_text().strip())
        self.assertEqual(OLD_API_IMAGE, self._runtime_values()["API_IMAGE_URI"])
        self.assertFalse(
            (self.transaction_root / self.deployment_id).exists()
        )

    def test_failed_rollback_preserves_transaction_evidence(self) -> None:
        prepared = self._run("prepare")
        self.assertEqual(0, prepared.returncode, prepared.stdout)

        rollback = self._run(
            "rollback",
            fail_source_checkout=True,
        )

        self.assertNotEqual(0, rollback.returncode, rollback.stdout)
        transaction = self.transaction_root / self.deployment_id
        self.assertTrue(transaction.is_dir())
        self.assertTrue((transaction / "source-release.env").is_file())
        self.assertTrue((transaction / "mutation-armed").is_file())
        self.assertEqual(TARGET_SHA, self.git_head_file.read_text().strip())

    def test_remote_helper_traps_interrupt_and_termination(self) -> None:
        text = REMOTE_SCRIPT.read_text(encoding="utf-8")

        self.assertIn("rollback_on_prepare_signal INT", text)
        self.assertIn("rollback_on_prepare_signal TERM", text)

        interrupted = self._run(
            "prepare",
            signal_parent_on_target_up=True,
        )
        self.assertNotEqual(0, interrupted.returncode, interrupted.stdout)
        self.assertIn("prepare interrupted", interrupted.stdout)
        self.assertEqual(SOURCE_SHA, self.git_head_file.read_text().strip())
        self.assertEqual(OLD_API_IMAGE, self._runtime_values()["API_IMAGE_URI"])
        self.assertFalse(
            (self.transaction_root / self.deployment_id).exists()
        )


if __name__ == "__main__":
    unittest.main()
