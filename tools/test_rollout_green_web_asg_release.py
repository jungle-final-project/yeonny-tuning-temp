from __future__ import annotations

import base64
import json
import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/rollout_green_web_asg_release.sh"

AWS_ACCOUNT_ID = "443915990705"
AWS_REGION = "ap-northeast-2"
ECR_REGISTRY = f"{AWS_ACCOUNT_ID}.dkr.ecr.{AWS_REGION}.amazonaws.com"
API_REPOSITORY = "buildgraph-demo-api-green"
XGB_REPOSITORY = "buildgraph-demo-xgb-reranker-green"
ASG_NAME = "buildgraph-demo-api-green-asg"
LAUNCH_TEMPLATE_ID = "lt-0greenrollouttest"
TARGET_GROUP_ARN = (
    "arn:aws:elasticloadbalancing:ap-northeast-2:443915990705:"
    "targetgroup/buildgraph-demo-api-green-tg/f905e7669645a411"
)

GIT_SHA = "1" * 40
IMAGE_TAG = GIT_SHA
OLD_API_DIGEST = "a" * 64
NEW_API_DIGEST = "b" * 64
OLD_XGB_DIGEST = "c" * 64
NEW_XGB_DIGEST = "d" * 64
NGINX_DIGEST = "e" * 64
OLD_API_IMAGE = (
    f"{ECR_REGISTRY}/{API_REPOSITORY}@sha256:{OLD_API_DIGEST}"
)
NEW_API_IMAGE = (
    f"{ECR_REGISTRY}/{API_REPOSITORY}@sha256:{NEW_API_DIGEST}"
)
OLD_XGB_IMAGE = (
    f"{ECR_REGISTRY}/{XGB_REPOSITORY}@sha256:{OLD_XGB_DIGEST}"
)
NEW_XGB_IMAGE = (
    f"{ECR_REGISTRY}/{XGB_REPOSITORY}@sha256:{NEW_XGB_DIGEST}"
)
NGINX_IMAGE = (
    "docker.io/library/nginx"
    f"@sha256:{NGINX_DIGEST}"
)

MUTATING_MARKERS = (
    "aws:ec2 create-launch-template-version",
    "aws:autoscaling start-instance-refresh",
    "aws:autoscaling rollback-instance-refresh",
)


class GreenWebAsgReleaseRolloutTest(unittest.TestCase):
    maxDiff = None

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.temp_root = Path(self.temporary_directory.name)
        self.fake_bin = self.temp_root / "bin"
        self.fake_bin.mkdir()
        self.trace_file = self.temp_root / "aws-trace.log"
        self.asg_version_file = self.temp_root / "asg-version"
        self.refresh_count_file = self.temp_root / "refresh-count"
        self.created_user_data_file = self.temp_root / "created-user-data"
        self.bootstrap_manifest = self.temp_root / "green-release.env"
        self.bootstrap_manifest.write_text(
            "\n".join(
                (
                    f"API_IMAGE_URI={OLD_API_IMAGE}",
                    f"XGB_IMAGE_URI={OLD_XGB_IMAGE}",
                    f"NGINX_IMAGE_URI={NGINX_IMAGE}",
                    "BUILDGRAPH_SCHEDULING_ENABLED=false",
                    "",
                )
            ),
            encoding="utf-8",
        )
        self._write_fake_aws()

        self.environment = os.environ.copy()
        self.environment.update(
            {
                "PATH": f"{self.fake_bin}{os.pathsep}{self.environment['PATH']}",
                "AWS_ACCOUNT_ID": AWS_ACCOUNT_ID,
                "AWS_REGION": AWS_REGION,
                "AWS_DEFAULT_REGION": AWS_REGION,
                "BUILDGRAPH_ASG_REFRESH_MAX_ATTEMPTS": "3",
                "BUILDGRAPH_ASG_REFRESH_POLL_SECONDS": "0",
                "BUILDGRAPH_TARGET_HEALTH_MAX_ATTEMPTS": "3",
                "BUILDGRAPH_TARGET_HEALTH_POLL_SECONDS": "0",
                "FAKE_ACCOUNT_ID": AWS_ACCOUNT_ID,
                "FAKE_API_DIGEST": f"sha256:{NEW_API_DIGEST}",
                "FAKE_XGB_DIGEST": f"sha256:{NEW_XGB_DIGEST}",
                "FAKE_REFRESH_STATUS": "Successful",
                "FAKE_ACTIVE_REFRESH_STATUS": "None",
                "FAKE_AMI_GIT_SHA": GIT_SHA,
                "FAKE_TARGET_HEALTH_STATE": "healthy",
                "FAKE_CANDIDATE_USER_DATA_MODE": "present",
                "FAKE_TRACE_FILE": str(self.trace_file),
                "FAKE_ASG_VERSION_FILE": str(self.asg_version_file),
                "FAKE_REFRESH_COUNT_FILE": str(self.refresh_count_file),
                "FAKE_CREATED_USER_DATA_FILE": str(
                    self.created_user_data_file
                ),
            }
        )

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def _run(
        self,
        service: str = "api",
        *,
        apply: bool = False,
        **overrides: str,
    ) -> subprocess.CompletedProcess[str]:
        environment = self.environment.copy()
        environment.update(overrides)
        arguments = [
            "bash",
            str(SCRIPT),
            "--service",
            service,
            "--git-sha",
            GIT_SHA,
            "--image-tag",
            IMAGE_TAG,
            "--bootstrap-manifest",
            str(self.bootstrap_manifest),
        ]
        if apply:
            arguments.append("--apply")
        return subprocess.run(
            arguments,
            cwd=ROOT,
            env=environment,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def _trace_lines(self) -> list[str]:
        if not self.trace_file.exists():
            return []
        return self.trace_file.read_text(encoding="utf-8").splitlines()

    def _mutation_lines(self) -> list[str]:
        return [
            line
            for line in self._trace_lines()
            if any(marker in line for marker in MUTATING_MARKERS)
        ]

    def _launch_template_user_data(self) -> str:
        launch_template_data = next(
            line.removeprefix("launch-template-data:")
            for line in self._trace_lines()
            if line.startswith("launch-template-data:")
        )
        override = json.loads(launch_template_data)
        return base64.b64decode(override["UserData"]).decode("utf-8")

    def _write_fake_aws(self) -> None:
        fake_aws = self.fake_bin / "aws"
        fake_aws.write_text(
            "#!/usr/bin/env bash\nset -euo pipefail\n"
            + textwrap.dedent(
                r"""
                trace() {
                  printf 'aws:%s\n' "$*" >>"$FAKE_TRACE_FILE"
                }

                argument_value() {
                  local expected="$1"
                  shift
                  while [[ "$#" -gt 0 ]]; do
                    if [[ "$1" == "$expected" ]]; then
                      printf '%s' "${2:-}"
                      return 0
                    fi
                    shift
                  done
                  return 1
                }

                trace "$*"

                case "${1:-}:${2:-}" in
                  sts:get-caller-identity)
                    printf '%s\n' "$FAKE_ACCOUNT_ID"
                    ;;

                  ecr:describe-repositories)
                    jq -n '{
                      repositories: [{
                        imageTagMutability: "IMMUTABLE"
                      }]
                    }'
                    ;;

                  ecr:describe-images|ecr:batch-get-image)
                    repository="$(
                      argument_value --repository-name "$@"
                    )"
                    case "$repository" in
                      buildgraph-demo-api-green)
                        digest="$FAKE_API_DIGEST"
                        ;;
                      buildgraph-demo-xgb-reranker-green)
                        digest="$FAKE_XGB_DIGEST"
                        ;;
                      *)
                        echo "unapproved repository: $repository" >&2
                        exit 93
                        ;;
                    esac
                    query="$(argument_value --query "$@" || true)"
                    if [[ -n "$query" ]]; then
                      printf '%s\n' "$digest"
                    else
                      jq -n --arg digest "$digest" '{
                        imageDetails: [{imageDigest: $digest}],
                        images: [{imageId: {imageDigest: $digest}}]
                      }'
                    fi
                    ;;

                  autoscaling:describe-auto-scaling-groups)
                    version=7
                    [[ ! -f "$FAKE_ASG_VERSION_FILE" ]] ||
                      version="$(cat "$FAKE_ASG_VERSION_FILE")"
                    query="$(argument_value --query "$@" || true)"
                    case "$query" in
                      *LaunchTemplate.LaunchTemplateId*)
                        printf '%s\n' "lt-0greenrollouttest"
                        ;;
                      *LaunchTemplate.Version*)
                        printf '%s\n' "$version"
                        ;;
                      *TargetGroupARNs*)
                        printf '%s\n' \
                          "arn:aws:elasticloadbalancing:ap-northeast-2:443915990705:targetgroup/buildgraph-demo-api-green-tg/f905e7669645a411"
                        ;;
                      *AutoScalingGroupName*)
                        printf '%s\n' "buildgraph-demo-api-green-asg"
                        ;;
                      *length*)
                        printf '%s\n' 1
                        ;;
                      *)
                        jq -n \
                          --arg version "$version" \
                          '{
                            AutoScalingGroups: [{
                              AutoScalingGroupName:
                                "buildgraph-demo-api-green-asg",
                              MinSize: 1,
                              DesiredCapacity: 1,
                              MaxSize: 1,
                              DefaultInstanceWarmup: 120,
                              HealthCheckGracePeriod: 300,
                              LaunchTemplate: {
                                LaunchTemplateId: "lt-0greenrollouttest",
                                LaunchTemplateName:
                                  "buildgraph-demo-api-green-lt",
                                Version: $version
                              },
                              TargetGroupARNs: [
                                "arn:aws:elasticloadbalancing:ap-northeast-2:443915990705:targetgroup/buildgraph-demo-api-green-tg/f905e7669645a411"
                              ],
                              Instances: [{
                                InstanceId: "i-green-rollout",
                                LifecycleState: "InService",
                                HealthStatus: "Healthy"
                              }]
                            }]
                          }'
                        ;;
                    esac
                    ;;

                  ec2:describe-launch-template-versions)
                    requested_version="$(
                      argument_value --versions "$@" || true
                    )"
                    [[ -n "$requested_version" ]] || requested_version=7
                    query="$(argument_value --query "$@" || true)"
                    case "$query" in
                      *VersionNumber*)
                        printf '%s\n' "$requested_version"
                        ;;
                      *)
                        user_data=""
                        if [[ "$requested_version" == "8" &&
                          "$FAKE_CANDIDATE_USER_DATA_MODE" == "present" &&
                          -f "$FAKE_CREATED_USER_DATA_FILE" ]]; then
                          user_data="$(cat "$FAKE_CREATED_USER_DATA_FILE")"
                        fi
                        jq -n \
                          --argjson version "$requested_version" \
                          --arg user_data "$user_data" \
                          '{
                            LaunchTemplateVersions: [{
                              VersionNumber: $version,
                              LaunchTemplateData: (
                                {ImageId: "ami-0greenrollouttest"}
                                + if $user_data == "" then {}
                                  else {UserData: $user_data}
                                  end
                              )
                            }]
                          }'
                        ;;
                    esac
                    ;;

                  ec2:describe-images)
                    query="$(argument_value --query "$@" || true)"
                    case "$query" in
                      *GitSha*)
                        printf '%s\n' "$FAKE_AMI_GIT_SHA"
                        ;;
                      *Validation*)
                        printf '%s\n' passed
                        ;;
                      *)
                        jq -n --arg git_sha "$FAKE_AMI_GIT_SHA" '{
                          Images: [{
                            ImageId: "ami-0greenrollouttest",
                            State: "available",
                            Tags: [
                              {Key: "GitSha", Value: $git_sha},
                              {Key: "Validation", Value: "passed"}
                            ]
                          }]
                        }'
                        ;;
                    esac
                    ;;

                  ec2:create-launch-template-version)
                    launch_template_data="$(
                      argument_value --launch-template-data "$@"
                    )"
                    case "$launch_template_data" in
                      file://*)
                        jq -r '.UserData' \
                          "${launch_template_data#file://}" \
                          >"$FAKE_CREATED_USER_DATA_FILE"
                        printf 'launch-template-data:%s\n' \
                          "$(jq -c . "${launch_template_data#file://}")" \
                          >>"$FAKE_TRACE_FILE"
                        ;;
                      *)
                        echo "launch template data must use file:// JSON" >&2
                        exit 94
                        ;;
                    esac
                    query="$(argument_value --query "$@" || true)"
                    if [[ -n "$query" ]]; then
                      printf '%s\n' 8
                    else
                      printf '%s\n' \
                        '{"LaunchTemplateVersion":{"VersionNumber":8}}'
                    fi
                    ;;

                  autoscaling:start-instance-refresh)
                    desired_configuration="$(
                      argument_value --desired-configuration "$@"
                    )"
                    preferences="$(
                      argument_value --preferences "$@"
                    )"
                    case "$desired_configuration" in
                      file://*)
                        version="$(
                          jq -r '.LaunchTemplate.Version' \
                            "${desired_configuration#file://}"
                        )"
                        ;;
                      *)
                        echo "desired configuration must use file:// JSON" >&2
                        exit 95
                        ;;
                    esac
                    [[ "$version" =~ ^[0-9]+$ ]] || {
                      echo "desired configuration must use a numeric version" >&2
                      exit 95
                    }
                    printf 'desired-configuration:%s\n' \
                      "$(jq -c . "${desired_configuration#file://}")" \
                      >>"$FAKE_TRACE_FILE"
                    printf 'refresh-preferences:%s\n' \
                      "$(jq -c . "${preferences#file://}")" \
                      >>"$FAKE_TRACE_FILE"
                    count=0
                    [[ ! -f "$FAKE_REFRESH_COUNT_FILE" ]] ||
                      count="$(cat "$FAKE_REFRESH_COUNT_FILE")"
                    count=$((count + 1))
                    printf '%s\n' "$count" >"$FAKE_REFRESH_COUNT_FILE"
                    printf '%s\n' "$version" >"$FAKE_ASG_VERSION_FILE"
                    if [[ "$count" -eq 1 ]]; then
                      printf '%s\n' refresh-forward
                    else
                      printf '%s\n' refresh-reverse
                    fi
                    ;;

                  autoscaling:describe-instance-refreshes)
                    refresh_id="$(
                      argument_value --instance-refresh-ids "$@" || true
                    )"
                    if [[ -z "$refresh_id" ]]; then
                      printf '%s\n' "$FAKE_ACTIVE_REFRESH_STATUS"
                    elif [[ "$refresh_id" == "refresh-forward" ]]; then
                      printf '%s\n' "$FAKE_REFRESH_STATUS"
                    else
                      printf '%s\n' Successful
                    fi
                    ;;

                  autoscaling:rollback-instance-refresh)
                    printf '%s\n' 7 >"$FAKE_ASG_VERSION_FILE"
                    printf '%s\n' rollback-refresh
                    exit 0
                    ;;

                  elbv2:describe-target-health)
                    query="$(argument_value --query "$@" || true)"
                    version=7
                    [[ ! -f "$FAKE_ASG_VERSION_FILE" ]] ||
                      version="$(cat "$FAKE_ASG_VERSION_FILE")"
                    target_state=healthy
                    if [[ "$version" == "8" ]]; then
                      target_state="$FAKE_TARGET_HEALTH_STATE"
                    fi
                    if [[ -n "$query" ]]; then
                      printf '%s\n' "$target_state"
                    else
                      jq -n --arg state "$target_state" '{
                        TargetHealthDescriptions: [{
                          Target: {
                            Id: "i-green-rollout",
                            Port: 80
                          },
                          TargetHealth: {
                            State: $state
                          }
                        }]
                      }'
                    fi
                    ;;

                  ssm:send-command|ssm:get-command-invocation)
                    echo "SSM is forbidden for ASG release rollout" >&2
                    exit 96
                    ;;

                  *)
                    echo "unexpected fake aws invocation: $*" >&2
                    exit 91
                    ;;
                esac
                """
            ).lstrip(),
            encoding="utf-8",
        )
        fake_aws.chmod(0o755)

    def test_common_helper_cli_contract_supports_api_and_xgb(self) -> None:
        self.assertTrue(SCRIPT.is_file())
        script = SCRIPT.read_text(encoding="utf-8")

        for marker in (
            "--service",
            "--git-sha",
            "--image-tag",
            "--bootstrap-manifest",
            "--apply",
            "api",
            "xgb-reranker",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, script)

        for service, repository in (
            ("api", API_REPOSITORY),
            ("xgb-reranker", XGB_REPOSITORY),
        ):
            with self.subTest(service=service):
                result = self._run(service)
                self.assertEqual(0, result.returncode, result.stdout)
                self.assertTrue(
                    any(
                        "aws:ecr " in line and repository in line
                        for line in self._trace_lines()
                    ),
                    self._trace_lines(),
                )
                self.trace_file.unlink(missing_ok=True)

    def test_default_execution_is_read_only_and_performs_zero_mutations(
        self,
    ) -> None:
        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual([], self._mutation_lines(), result.stdout)
        self.assertIn("read-only", result.stdout.lower())

    def test_apply_uses_100_200_without_cloudfront_isolation(
        self,
    ) -> None:
        result = self._run(apply=True)

        self.assertEqual(0, result.returncode, result.stdout)
        preferences = next(
            json.loads(line.removeprefix("refresh-preferences:"))
            for line in self._trace_lines()
            if line.startswith("refresh-preferences:")
        )
        self.assertEqual(100, preferences["MinHealthyPercentage"])
        self.assertEqual(200, preferences["MaxHealthyPercentage"])
        self.assertTrue(preferences["SkipMatching"])
        self.assertTrue(preferences["AutoRollback"])
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn(
            "BUILDGRAPH_CLOUDFRONT_MANUAL_GREEN_CONFIRMED",
            script,
        )

    def test_active_instance_refresh_rejects_before_any_mutation(self) -> None:
        result = self._run(
            apply=True,
            FAKE_ACTIVE_REFRESH_STATUS="InProgress",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("active Instance Refresh", result.stdout)
        self.assertEqual([], self._mutation_lines(), result.stdout)

    def test_api_digest_replacement_preserves_other_release_contract(
        self,
    ) -> None:
        result = self._run(apply=True)

        self.assertEqual(0, result.returncode, result.stdout)
        user_data = self._launch_template_user_data()
        self.assertIn(f"API_IMAGE_URI={NEW_API_IMAGE}", user_data)
        self.assertIn(f"XGB_IMAGE_URI={OLD_XGB_IMAGE}", user_data)
        self.assertIn(f"NGINX_IMAGE_URI={NGINX_IMAGE}", user_data)
        self.assertIn(
            "BUILDGRAPH_SCHEDULING_ENABLED=false",
            user_data,
        )
        self.assertIn(GIT_SHA, user_data)
        self.assertNotIn(OLD_API_IMAGE, user_data)
        self.assertNotIn(f":{IMAGE_TAG}", user_data)

    def test_xgb_uses_same_helper_and_preserves_api_release(self) -> None:
        result = self._run("xgb-reranker", apply=True)

        self.assertEqual(0, result.returncode, result.stdout)
        user_data = self._launch_template_user_data()
        self.assertIn(f"API_IMAGE_URI={OLD_API_IMAGE}", user_data)
        self.assertIn(f"XGB_IMAGE_URI={NEW_XGB_IMAGE}", user_data)
        self.assertIn(f"NGINX_IMAGE_URI={NGINX_IMAGE}", user_data)
        self.assertIn(
            "BUILDGRAPH_SCHEDULING_ENABLED=false",
            user_data,
        )
        self.assertNotIn(OLD_XGB_IMAGE, user_data)

    def test_apply_orders_lt_refresh_and_target_health(self) -> None:
        result = self._run(apply=True)

        self.assertEqual(0, result.returncode, result.stdout)
        lines = self._trace_lines()
        create_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:ec2 create-launch-template-version" in line
        )
        refresh_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:autoscaling start-instance-refresh" in line
        )
        refresh_health_index = max(
            index
            for index, line in enumerate(lines)
            if "aws:autoscaling describe-instance-refreshes" in line
        )
        target_health_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:elbv2 describe-target-health" in line
        )

        self.assertLess(create_index, refresh_index)
        self.assertLess(refresh_index, refresh_health_index)
        self.assertLess(refresh_health_index, target_health_index)
        self.assertIn("--source-version 7", lines[create_index])
        candidate_describe_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:ec2 describe-launch-template-versions" in line
            and "--versions 8" in line
        )
        self.assertLess(create_index, candidate_describe_index)
        self.assertLess(candidate_describe_index, refresh_index)
        desired_configuration = next(
            json.loads(line.removeprefix("desired-configuration:"))
            for line in lines
            if line.startswith("desired-configuration:")
        )
        self.assertEqual(
            {
                "LaunchTemplate": {
                    "LaunchTemplateId": LAUNCH_TEMPLATE_ID,
                    "Version": "8",
                }
            },
            desired_configuration,
        )
        self.assertNotIn("$Latest", "\n".join(lines))

    def test_candidate_lt_without_exact_userdata_stops_before_refresh(
        self,
    ) -> None:
        result = self._run(
            apply=True,
            FAKE_CANDIDATE_USER_DATA_MODE="missing",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("UserData", result.stdout)
        self.assertFalse(
            any(
                "aws:autoscaling start-instance-refresh" in line
                for line in self._trace_lines()
            ),
            self._trace_lines(),
        )

    def test_post_success_unhealthy_target_starts_reverse_refresh(
        self,
    ) -> None:
        result = self._run(
            apply=True,
            FAKE_TARGET_HEALTH_STATE="unhealthy",
        )

        self.assertNotEqual(0, result.returncode)
        lines = self._trace_lines()
        refresh_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:autoscaling start-instance-refresh" in line
        )
        target_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:elbv2 describe-target-health" in line
        )
        reverse_refresh_index = max(
            index
            for index, line in enumerate(lines)
            if "aws:autoscaling start-instance-refresh" in line
        )

        self.assertLess(refresh_index, target_index)
        self.assertLess(target_index, reverse_refresh_index)
        desired_configurations = [
            json.loads(line.removeprefix("desired-configuration:"))
            for line in lines
            if line.startswith("desired-configuration:")
        ]
        self.assertEqual(2, len(desired_configurations), lines)
        self.assertEqual(
            "7",
            desired_configurations[1]["LaunchTemplate"]["Version"],
        )
        self.assertFalse(
            any(
                "aws:autoscaling rollback-instance-refresh" in line
                for line in lines
            ),
            lines,
        )
        self.assertIn("rollback", result.stdout.lower())

    def test_failed_refresh_restores_old_numeric_lt_version(self) -> None:
        result = self._run(apply=True, FAKE_REFRESH_STATUS="Failed")

        self.assertNotEqual(0, result.returncode)
        lines = self._trace_lines()
        refreshes = [
            line
            for line in lines
            if "aws:autoscaling start-instance-refresh" in line
        ]
        self.assertTrue(
            any(
                "aws:autoscaling rollback-instance-refresh" in line
                for line in lines
            ),
            lines,
        )
        self.assertTrue(refreshes, lines)
        rollback_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:autoscaling rollback-instance-refresh" in line
        )

        self.assertEqual(1, len(refreshes), lines)
        forward_refresh_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:autoscaling start-instance-refresh" in line
        )
        self.assertLess(forward_refresh_index, rollback_index)
        self.assertNotIn("$Latest", "\n".join(lines))
        self.assertIn("rollback", result.stdout.lower())

    def test_rollout_never_uses_ssm_commands(self) -> None:
        self.assertTrue(SCRIPT.is_file())
        script = SCRIPT.read_text(encoding="utf-8")

        self.assertNotIn("ssm send-command", script)
        self.assertNotIn("ssm get-command-invocation", script)

        result = self._run(apply=True)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertFalse(
            any("aws:ssm " in line for line in self._trace_lines()),
            self._trace_lines(),
        )


if __name__ == "__main__":
    unittest.main()
