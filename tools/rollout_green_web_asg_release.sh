#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

readonly APPROVED_AWS_ACCOUNT_ID="443915990705"
readonly APPROVED_AWS_REGION="ap-northeast-2"
readonly ECR_REGISTRY="${APPROVED_AWS_ACCOUNT_ID}.dkr.ecr.${APPROVED_AWS_REGION}.amazonaws.com"
readonly API_REPOSITORY="buildgraph-demo-api-green"
readonly XGB_REPOSITORY="buildgraph-demo-xgb-reranker-green"
readonly ASG_NAME="buildgraph-demo-api-green-asg"
readonly LAUNCH_TEMPLATE_NAME="buildgraph-demo-api-green-lt"
readonly TARGET_GROUP_ARN="arn:aws:elasticloadbalancing:ap-northeast-2:443915990705:targetgroup/buildgraph-demo-api-green-tg/f905e7669645a411"
readonly APP_ROOT="/opt/buildgraph/prototype"
readonly APP_USER="ubuntu"
readonly RELEASE_MANIFEST_MARKER="BUILDGRAPH_RELEASE_MANIFEST_B64"
readonly RELEASE_GIT_SHA_MARKER="BUILDGRAPH_RELEASE_GIT_SHA"

readonly AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-$APPROVED_AWS_ACCOUNT_ID}"
readonly AWS_REGION="${AWS_REGION:-$APPROVED_AWS_REGION}"
readonly REFRESH_MAX_ATTEMPTS="${BUILDGRAPH_ASG_REFRESH_MAX_ATTEMPTS:-180}"
readonly REFRESH_POLL_SECONDS="${BUILDGRAPH_ASG_REFRESH_POLL_SECONDS:-10}"
readonly TARGET_HEALTH_MAX_ATTEMPTS="${BUILDGRAPH_TARGET_HEALTH_MAX_ATTEMPTS:-60}"
readonly TARGET_HEALTH_POLL_SECONDS="${BUILDGRAPH_TARGET_HEALTH_POLL_SECONDS:-10}"

SERVICE=""
GIT_SHA=""
IMAGE_TAG=""
BOOTSTRAP_MANIFEST=""
APPLY=false
TEMP_DIR=""
SOURCE_LT_VERSION=""
ROLLBACK_ARMED=false
ROLLBACK_STARTED=false
ROLLBACK_COMPLETED=false

log() {
  printf '%s\n' "buildgraph Green ASG rollout: $*"
}

die() {
  printf '%s\n' "buildgraph Green ASG rollout rejected: $*" >&2
  return 1
}

usage() {
  cat <<'USAGE'
Usage:
  tools/rollout_green_web_asg_release.sh \
    --service api|xgb-reranker \
    --git-sha <40-character-lowercase-git-sha> \
    --image-tag <same-40-character-git-sha> \
    [--bootstrap-manifest <green-release.env>] \
    [--apply]

The default mode is read-only. It validates the immutable ECR image, current
numeric Launch Template version, and four-key release manifest without
changing AWS resources.

--apply uses MinHealthyPercentage=100 and MaxHealthyPercentage=200. The old
and new instances can temporarily receive traffic at the same time. HTTP
availability is preserved, but WebSocket and PC Agent realtime behavior can
be intermittently inconsistent during the overlap window.
USAGE
}

cleanup() {
  if [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
    rm -rf "$TEMP_DIR"
  fi
}

require_command() {
  command -v "$1" >/dev/null 2>&1 ||
    die "required command is missing: $1"
}

aws_cli() {
  AWS_PAGER="" aws "$@" \
    --region "$AWS_REGION" \
    --no-cli-pager
}

require_positive_integer() {
  local name="$1"
  local value="$2"

  [[ "$value" =~ ^[1-9][0-9]*$ ]] ||
    die "$name must be a positive integer"
}

require_non_negative_integer() {
  local name="$1"
  local value="$2"

  [[ "$value" =~ ^[0-9]+$ ]] ||
    die "$name must be a non-negative integer"
}

read_manifest_value() {
  local key="$1"
  local file="$2"
  local count value

  count="$(grep -c "^${key}=" "$file" || true)"
  [[ "$count" -eq 1 ]] ||
    die "release manifest must contain exactly one $key"
  value="$(sed -n "s/^${key}=//p" "$file")"
  [[ -n "$value" ]] ||
    die "release manifest value is empty: $key"
  printf '%s' "$value"
}

validate_ecr_digest_image() {
  local image_uri="$1"
  local repository="$2"
  local prefix="${ECR_REGISTRY}/${repository}@sha256:"
  local digest

  [[ "$image_uri" == "$prefix"* ]] ||
    die "release image must use the approved ECR repository and digest: $repository"
  digest="${image_uri#"$prefix"}"
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] ||
    die "release image digest is invalid for repository: $repository"
}

validate_nginx_digest_image() {
  local image_uri="$1"
  local prefix="docker.io/library/nginx@sha256:"
  local digest

  [[ "$image_uri" == "$prefix"* ]] ||
    die "Nginx release image must use the approved official digest reference"
  digest="${image_uri#"$prefix"}"
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] ||
    die "Nginx release image digest is invalid"
}

canonicalize_release_manifest() {
  local source_file="$1"
  local destination_file="$2"
  local line key
  local entry_count=0
  local api_image xgb_image nginx_image scheduling_enabled

  [[ -f "$source_file" && -r "$source_file" ]] ||
    die "release manifest is not a readable file: $source_file"

  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -n "$line" ]] || continue
    [[ "$line" != \#* ]] || continue
    [[ "$line" =~ ^([A-Z][A-Z0-9_]*)=(.+)$ ]] ||
      die "release manifest contains an invalid or empty line"
    key="${BASH_REMATCH[1]}"
    case "$key" in
      API_IMAGE_URI|XGB_IMAGE_URI|NGINX_IMAGE_URI|BUILDGRAPH_SCHEDULING_ENABLED)
        ;;
      *)
        die "release manifest contains an unexpected key: $key"
        ;;
    esac
    entry_count=$((entry_count + 1))
  done <"$source_file"

  [[ "$entry_count" -eq 4 ]] ||
    die "release manifest must contain exactly four values"

  api_image="$(read_manifest_value API_IMAGE_URI "$source_file")"
  xgb_image="$(read_manifest_value XGB_IMAGE_URI "$source_file")"
  nginx_image="$(read_manifest_value NGINX_IMAGE_URI "$source_file")"
  scheduling_enabled="$(
    read_manifest_value BUILDGRAPH_SCHEDULING_ENABLED "$source_file"
  )"

  validate_ecr_digest_image "$api_image" "$API_REPOSITORY"
  validate_ecr_digest_image "$xgb_image" "$XGB_REPOSITORY"
  validate_nginx_digest_image "$nginx_image"
  [[ "$scheduling_enabled" == "false" ]] ||
    die "ASG web release must keep BUILDGRAPH_SCHEDULING_ENABLED=false"

  printf '%s\n' \
    "API_IMAGE_URI=$api_image" \
    "XGB_IMAGE_URI=$xgb_image" \
    "NGINX_IMAGE_URI=$nginx_image" \
    "BUILDGRAPH_SCHEDULING_ENABLED=false" >"$destination_file"
}

load_source_release_manifest() {
  local source_user_data_base64="$1"
  local destination_file="$2"
  local decoded_user_data="$TEMP_DIR/source-user-data.sh"
  local embedded_manifest="$TEMP_DIR/source-release-embedded.env"
  local marker_count marker_value

  if [[ -n "$source_user_data_base64" && "$source_user_data_base64" != "null" ]]; then
    if ! printf '%s' "$source_user_data_base64" |
      base64 --decode >"$decoded_user_data" 2>/dev/null; then
      die "source Launch Template UserData is not valid base64"
    fi

    marker_count="$(
      grep -c "^# ${RELEASE_MANIFEST_MARKER}=" "$decoded_user_data" || true
    )"
    if [[ "$marker_count" -gt 1 ]]; then
      die "source Launch Template contains multiple release manifest markers"
    fi
    if [[ "$marker_count" -eq 1 ]]; then
      marker_value="$(
        sed -n "s/^# ${RELEASE_MANIFEST_MARKER}=//p" "$decoded_user_data"
      )"
      [[ -n "$marker_value" ]] ||
        die "source Launch Template release manifest marker is empty"
      if ! printf '%s' "$marker_value" |
        base64 --decode >"$embedded_manifest" 2>/dev/null; then
        die "source Launch Template release manifest marker is invalid"
      fi
      canonicalize_release_manifest "$embedded_manifest" "$destination_file"
      log "loaded the deployed four-key release manifest from Launch Template UserData"
      return 0
    fi
  fi

  [[ -n "$BOOTSTRAP_MANIFEST" ]] ||
    die "source Launch Template has no embedded release manifest; --bootstrap-manifest is required"
  canonicalize_release_manifest "$BOOTSTRAP_MANIFEST" "$destination_file"
  log "used --bootstrap-manifest because the source Launch Template has no release marker"
}

replace_service_image() {
  local source_manifest="$1"
  local destination_manifest="$2"
  local immutable_image="$3"
  local api_image xgb_image nginx_image

  api_image="$(read_manifest_value API_IMAGE_URI "$source_manifest")"
  xgb_image="$(read_manifest_value XGB_IMAGE_URI "$source_manifest")"
  nginx_image="$(read_manifest_value NGINX_IMAGE_URI "$source_manifest")"

  case "$SERVICE" in
    api)
      api_image="$immutable_image"
      ;;
    xgb-reranker)
      xgb_image="$immutable_image"
      ;;
    *)
      die "unsupported service: $SERVICE"
      ;;
  esac

  printf '%s\n' \
    "API_IMAGE_URI=$api_image" \
    "XGB_IMAGE_URI=$xgb_image" \
    "NGINX_IMAGE_URI=$nginx_image" \
    "BUILDGRAPH_SCHEDULING_ENABLED=false" >"$destination_manifest"
  canonicalize_release_manifest "$destination_manifest" \
    "$TEMP_DIR/release-manifest-validated.env"
  mv -f "$TEMP_DIR/release-manifest-validated.env" "$destination_manifest"
}

render_user_data() {
  local release_manifest="$1"
  local destination_file="$2"
  local manifest_base64

  manifest_base64="$(base64 <"$release_manifest" | tr -d '\n')"
  [[ -n "$manifest_base64" ]] ||
    die "failed to encode the release manifest"

  {
    cat <<EOF
#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

# ${RELEASE_GIT_SHA_MARKER}=${GIT_SHA}
# ${RELEASE_MANIFEST_MARKER}=${manifest_base64}
readonly AWS_ACCOUNT_ID="${APPROVED_AWS_ACCOUNT_ID}"
readonly AWS_REGION="${APPROVED_AWS_REGION}"
readonly AWS_STS_REGIONAL_ENDPOINTS="regional"
readonly APP_ROOT="${APP_ROOT}"
readonly APP_USER="${APP_USER}"
readonly RELEASE_GIT_SHA="${GIT_SHA}"
readonly RELEASE_MANIFEST="/opt/buildgraph/releases/green-release-${GIT_SHA}.env"

export AWS_STS_REGIONAL_ENDPOINTS

[[ -d "\${APP_ROOT}/.git" ]]
runuser -u "\${APP_USER}" -- \
  git -C "\${APP_ROOT}" fetch --no-tags origin \
  "+refs/heads/main:refs/remotes/origin/main"
runuser -u "\${APP_USER}" -- \
  git -C "\${APP_ROOT}" cat-file -e "\${RELEASE_GIT_SHA}^{commit}"
runuser -u "\${APP_USER}" -- \
  git -C "\${APP_ROOT}" merge-base --is-ancestor \
  "\${RELEASE_GIT_SHA}" origin/main
runuser -u "\${APP_USER}" -- \
  git -C "\${APP_ROOT}" checkout --detach "\${RELEASE_GIT_SHA}"

install -d -m 0700 /opt/buildgraph/releases
cat >"\${RELEASE_MANIFEST}" <<'BUILDGRAPH_RELEASE_MANIFEST'
EOF
    cat "$release_manifest"
    cat <<'EOF'
BUILDGRAPH_RELEASE_MANIFEST
chmod 600 "${RELEASE_MANIFEST}"

AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID}" \
AWS_REGION="${AWS_REGION}" \
AWS_STS_REGIONAL_ENDPOINTS="${AWS_STS_REGIONAL_ENDPOINTS}" \
BUILDGRAPH_RELEASE_MANIFEST="${RELEASE_MANIFEST}" \
"${APP_ROOT}/tools/bootstrap_green_asg.sh"
EOF
  } >"$destination_file"

  [[ "$(grep -c '/tools/bootstrap_green_asg.sh' "$destination_file")" -eq 1 ]] ||
    die "rendered UserData must invoke bootstrap exactly once"
  if grep -Eq \
    'secretsmanager[[:space:]]+get-secret-value|ssm[[:space:]]+(send-command|get-command-invocation)' \
    "$destination_file"; then
    die "rendered UserData must not contain Secret or SSM deployment commands"
  fi
}

wait_for_instance_refresh() {
  local refresh_id="$1"
  local label="$2"
  local attempt status

  for ((attempt = 1; attempt <= REFRESH_MAX_ATTEMPTS; attempt++)); do
    if ! status="$(
      aws_cli autoscaling describe-instance-refreshes \
        --auto-scaling-group-name "$ASG_NAME" \
        --instance-refresh-ids "$refresh_id" \
        --query 'InstanceRefreshes[0].Status' \
        --output text
    )"; then
      log "$label refresh status query failed on attempt $attempt"
      status=""
    fi

    case "$status" in
      Successful)
        log "$label Instance Refresh completed successfully"
        return 0
        ;;
      Failed|Cancelled|RollbackFailed)
        log "$label Instance Refresh reached terminal status: $status"
        return 2
        ;;
      RollbackSuccessful)
        log "$label Instance Refresh was rolled back by AutoRollback"
        return 3
        ;;
      Pending|InProgress|Cancelling|RollbackInProgress|Baking|"")
        ;;
      *)
        log "$label Instance Refresh returned unexpected status: $status"
        ;;
    esac

    if [[ "$attempt" -lt "$REFRESH_MAX_ATTEMPTS" ]]; then
      sleep "$REFRESH_POLL_SECONDS"
    fi
  done

  log "$label Instance Refresh did not finish within the approved wait window"
  return 1
}

reject_active_instance_refresh() {
  local active_status

  active_status="$(
    aws_cli autoscaling describe-instance-refreshes \
      --auto-scaling-group-name "$ASG_NAME" \
      --max-records 20 \
      --query \
        'InstanceRefreshes[?contains(`["Pending","InProgress","Baking","Cancelling","RollbackInProgress"]`, Status)] | [0].Status' \
      --output text
  )"

  case "$active_status" in
    ""|None|null|Successful|Failed|Cancelled|RollbackFailed|RollbackSuccessful)
      return 0
      ;;
    Pending|InProgress|Baking|Cancelling|RollbackInProgress)
      die "an active Instance Refresh already exists with status: $active_status"
      return 1
      ;;
    *)
      die "unable to prove that no active Instance Refresh exists: $active_status"
      return 1
      ;;
  esac
}

describe_asg() {
  aws_cli autoscaling describe-auto-scaling-groups \
    --auto-scaling-group-names "$ASG_NAME" \
    --output json
}

validate_candidate_launch_template() {
  local source_file="$1"
  local candidate_file="$2"
  local expected_version="$3"
  local expected_user_data="$4"
  local source_without_user_data candidate_without_user_data
  local candidate_user_data

  if [[ "$(jq -r '.LaunchTemplateVersions | length' "$candidate_file")" != "1" ]]; then
    die "candidate Launch Template version was not found"
    return 1
  fi
  if [[ "$(jq -r '.LaunchTemplateVersions[0].VersionNumber | tostring' "$candidate_file")" != "$expected_version" ]]; then
    die "candidate Launch Template response version does not match the created version"
    return 1
  fi

  source_without_user_data="$(
    jq -S -c \
      '.LaunchTemplateVersions[0].LaunchTemplateData | del(.UserData)' \
      "$source_file"
  )"
  candidate_without_user_data="$(
    jq -S -c \
      '.LaunchTemplateVersions[0].LaunchTemplateData | del(.UserData)' \
      "$candidate_file"
  )"
  if [[ "$candidate_without_user_data" != "$source_without_user_data" ]]; then
    die "candidate Launch Template changed a field other than UserData"
    return 1
  fi

  candidate_user_data="$(
    jq -r '.LaunchTemplateVersions[0].LaunchTemplateData.UserData // empty' \
      "$candidate_file"
  )"
  if [[ "$candidate_user_data" != "$expected_user_data" ]]; then
    die "candidate Launch Template UserData does not match the approved override"
    return 1
  fi
}

verify_asg_version() {
  local expected_version="$1"
  local asg_json current_version

  asg_json="$(describe_asg)"
  if [[ "$(jq -r '.AutoScalingGroups | length' <<<"$asg_json")" != "1" ]]; then
    die "expected exactly one approved Auto Scaling Group"
    return 1
  fi
  current_version="$(
    jq -r '.AutoScalingGroups[0].LaunchTemplate.Version // empty' <<<"$asg_json"
  )"
  if [[ ! "$current_version" =~ ^[0-9]+$ ]]; then
    die "Auto Scaling Group Launch Template version is not numeric"
    return 1
  fi
  if [[ "$current_version" != "$expected_version" ]]; then
    die "Auto Scaling Group uses LT version $current_version, expected $expected_version"
    return 1
  fi

  if ! jq -e '
    .AutoScalingGroups[0].Instances as $instances
    | ($instances | length) == 1
    and ([$instances[]
      | select(
          .LifecycleState != "InService"
          or .HealthStatus != "Healthy"
        )
      ] | length) == 0
  ' <<<"$asg_json" >/dev/null; then
    die "Auto Scaling Group has an instance that is not InService and Healthy"
    return 1
  fi
}

wait_for_all_targets_healthy() {
  local attempt target_health_json target_count unhealthy_count

  for ((attempt = 1; attempt <= TARGET_HEALTH_MAX_ATTEMPTS; attempt++)); do
    if target_health_json="$(
      aws_cli elbv2 describe-target-health \
        --target-group-arn "$TARGET_GROUP_ARN" \
        --output json
    )"; then
      target_count="$(
        jq -r '.TargetHealthDescriptions | length' <<<"$target_health_json"
      )"
      unhealthy_count="$(
        jq -r '
          [.TargetHealthDescriptions[]
            | select(.TargetHealth.State != "healthy")
          ] | length
        ' <<<"$target_health_json"
      )"
      if [[ "$target_count" == "1" && "$unhealthy_count" == "0" ]]; then
        log "the single Target Group target is healthy"
        return 0
      fi
    fi

    if [[ "$attempt" -lt "$TARGET_HEALTH_MAX_ATTEMPTS" ]]; then
      sleep "$TARGET_HEALTH_POLL_SECONDS"
    fi
  done

  die "Target Group did not converge to exactly one healthy target"
  return 1
}

rollback_failed_refresh() {
  local old_version="$1"
  local rollback_refresh_id rollback_wait_status

  ROLLBACK_STARTED=true
  log "requesting Instance Refresh rollback to LT version $old_version"
  if ! rollback_refresh_id="$(
    aws_cli autoscaling rollback-instance-refresh \
      --auto-scaling-group-name "$ASG_NAME" \
      --query 'InstanceRefreshId' \
      --output text
  )"; then
    die "failed to request Instance Refresh rollback"
    return 1
  fi
  if [[ -z "$rollback_refresh_id" || "$rollback_refresh_id" == "None" ]]; then
    die "Instance Refresh rollback returned an empty refresh ID"
    return 1
  fi

  if wait_for_instance_refresh "$rollback_refresh_id" "rollback"; then
    :
  else
    rollback_wait_status=$?
    die "rollback Instance Refresh did not succeed (status=$rollback_wait_status)"
    return 1
  fi

  if ! verify_asg_version "$old_version"; then
    return 1
  fi
  if ! wait_for_all_targets_healthy; then
    return 1
  fi
  ROLLBACK_COMPLETED=true
  ROLLBACK_ARMED=false
  log "rollback verified the previous numeric LT version $old_version"
}

reverse_completed_refresh() {
  local old_version="$1"
  local reverse_refresh_id reverse_wait_status

  ROLLBACK_STARTED=true
  log "starting reverse Instance Refresh to LT version $old_version"

  jq -n \
    --arg launch_template_id "$SOURCE_LT_ID" \
    --arg version "$old_version" \
    '{
      LaunchTemplate: {
        LaunchTemplateId: $launch_template_id,
        Version: $version
      }
    }' >"$REVERSE_DESIRED_CONFIGURATION_FILE"
  jq -n \
    --argjson warmup "$INSTANCE_WARMUP" \
    '{
      MinHealthyPercentage: 100,
      MaxHealthyPercentage: 200,
      InstanceWarmup: $warmup,
      SkipMatching: true,
      AutoRollback: false
    }' >"$REVERSE_REFRESH_PREFERENCES_FILE"

  if ! reverse_refresh_id="$(
    aws_cli autoscaling start-instance-refresh \
      --auto-scaling-group-name "$ASG_NAME" \
      --strategy Rolling \
      --desired-configuration \
        "file://${REVERSE_DESIRED_CONFIGURATION_FILE}" \
      --preferences "file://${REVERSE_REFRESH_PREFERENCES_FILE}" \
      --query 'InstanceRefreshId' \
      --output text
  )"; then
    die "failed to start reverse Instance Refresh"
    return 1
  fi
  if [[ -z "$reverse_refresh_id" || "$reverse_refresh_id" == "None" ]]; then
    die "reverse Instance Refresh returned an empty refresh ID"
    return 1
  fi

  if wait_for_instance_refresh "$reverse_refresh_id" "reverse"; then
    :
  else
    reverse_wait_status=$?
    die "reverse Instance Refresh did not succeed (status=$reverse_wait_status)"
    return 1
  fi

  if ! verify_asg_version "$old_version"; then
    return 1
  fi
  if ! wait_for_all_targets_healthy; then
    return 1
  fi
  ROLLBACK_COMPLETED=true
  ROLLBACK_ARMED=false
  log "reverse refresh verified the previous numeric LT version $old_version"
}

handle_unexpected_error() {
  local status=$?

  trap - ERR
  if [[ "$ROLLBACK_ARMED" == "true" &&
    "$ROLLBACK_STARTED" != "true" &&
    -n "$SOURCE_LT_VERSION" ]]; then
    log "unexpected rollout error; attempting rollback before exit"
    if ! rollback_failed_refresh "$SOURCE_LT_VERSION"; then
      log "rollback failed; manual recovery is required"
    fi
  fi
  exit "$status"
}

handle_signal() {
  local signal_name="$1"
  local status=130

  [[ "$signal_name" != "TERM" ]] || status=143
  trap - ERR INT TERM
  if [[ "$ROLLBACK_ARMED" == "true" &&
    "$ROLLBACK_STARTED" != "true" &&
    -n "$SOURCE_LT_VERSION" ]]; then
    log "received $signal_name; attempting rollback before exit"
    if ! rollback_failed_refresh "$SOURCE_LT_VERSION"; then
      log "rollback failed after $signal_name; manual recovery is required"
    fi
  fi
  exit "$status"
}

trap cleanup EXIT
trap handle_unexpected_error ERR
trap 'handle_signal INT' INT
trap 'handle_signal TERM' TERM

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --service)
      [[ "$#" -ge 2 ]] || die "--service requires a value"
      SERVICE="$2"
      shift 2
      ;;
    --git-sha)
      [[ "$#" -ge 2 ]] || die "--git-sha requires a value"
      GIT_SHA="$2"
      shift 2
      ;;
    --image-tag)
      [[ "$#" -ge 2 ]] || die "--image-tag requires a value"
      IMAGE_TAG="$2"
      shift 2
      ;;
    --bootstrap-manifest)
      [[ "$#" -ge 2 ]] || die "--bootstrap-manifest requires a value"
      BOOTSTRAP_MANIFEST="$2"
      shift 2
      ;;
    --apply)
      APPLY=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

for command_name in aws base64 cat grep jq mktemp mv sed sleep tr; do
  require_command "$command_name"
done

[[ "$AWS_ACCOUNT_ID" == "$APPROVED_AWS_ACCOUNT_ID" ]] ||
  die "AWS account must be $APPROVED_AWS_ACCOUNT_ID"
[[ "$AWS_REGION" == "$APPROVED_AWS_REGION" ]] ||
  die "AWS region must be $APPROVED_AWS_REGION"
[[ "$SERVICE" == "api" || "$SERVICE" == "xgb-reranker" ]] ||
  die "--service must be api or xgb-reranker"
[[ "$GIT_SHA" =~ ^[0-9a-f]{40}$ ]] ||
  die "--git-sha must be exactly 40 lowercase hexadecimal characters"
[[ "$IMAGE_TAG" =~ ^[0-9a-f]{40}$ ]] ||
  die "--image-tag must be exactly 40 lowercase hexadecimal characters"
[[ "$IMAGE_TAG" == "$GIT_SHA" ]] ||
  die "--image-tag must exactly equal --git-sha"
require_positive_integer BUILDGRAPH_ASG_REFRESH_MAX_ATTEMPTS \
  "$REFRESH_MAX_ATTEMPTS"
require_non_negative_integer BUILDGRAPH_ASG_REFRESH_POLL_SECONDS \
  "$REFRESH_POLL_SECONDS"
require_positive_integer BUILDGRAPH_TARGET_HEALTH_MAX_ATTEMPTS \
  "$TARGET_HEALTH_MAX_ATTEMPTS"
require_non_negative_integer BUILDGRAPH_TARGET_HEALTH_POLL_SECONDS \
  "$TARGET_HEALTH_POLL_SECONDS"

case "$SERVICE" in
  api)
    readonly ECR_REPOSITORY="$API_REPOSITORY"
    ;;
  xgb-reranker)
    readonly ECR_REPOSITORY="$XGB_REPOSITORY"
    ;;
esac

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/buildgraph-green-asg-rollout.XXXXXX")"
readonly ASG_JSON_FILE="$TEMP_DIR/asg.json"
readonly SOURCE_LT_JSON_FILE="$TEMP_DIR/source-lt.json"
readonly CANDIDATE_LT_JSON_FILE="$TEMP_DIR/candidate-lt.json"
readonly SOURCE_MANIFEST="$TEMP_DIR/source-release.env"
readonly RELEASE_MANIFEST="$TEMP_DIR/next-release.env"
readonly USER_DATA_FILE="$TEMP_DIR/user-data.sh"
readonly LT_OVERRIDE_FILE="$TEMP_DIR/launch-template-data.json"
readonly DESIRED_CONFIGURATION_FILE="$TEMP_DIR/desired-configuration.json"
readonly REFRESH_PREFERENCES_FILE="$TEMP_DIR/refresh-preferences.json"
readonly REVERSE_DESIRED_CONFIGURATION_FILE="$TEMP_DIR/reverse-desired-configuration.json"
readonly REVERSE_REFRESH_PREFERENCES_FILE="$TEMP_DIR/reverse-refresh-preferences.json"

readonly CALLER_ACCOUNT="$(
  aws_cli sts get-caller-identity \
    --query Account \
    --output text
)"
[[ "$CALLER_ACCOUNT" == "$APPROVED_AWS_ACCOUNT_ID" ]] ||
  die "AWS caller account does not match the approved account"

readonly REPOSITORY_JSON="$(
  aws_cli ecr describe-repositories \
    --repository-names "$ECR_REPOSITORY" \
    --output json
)"
[[ "$(jq -r '.repositories | length' <<<"$REPOSITORY_JSON")" == "1" ]] ||
  die "approved ECR repository was not found"
[[ "$(jq -r '.repositories[0].imageTagMutability' <<<"$REPOSITORY_JSON")" == "IMMUTABLE" ]] ||
  die "approved ECR repository must use IMMUTABLE image tags"

readonly IMAGE_DIGEST="$(
  aws_cli ecr describe-images \
    --repository-name "$ECR_REPOSITORY" \
    --image-ids "imageTag=$IMAGE_TAG" \
    --query 'imageDetails[0].imageDigest' \
    --output text
)"
[[ "$IMAGE_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] ||
  die "ECR tag did not resolve to one valid immutable image digest"
readonly IMMUTABLE_IMAGE="${ECR_REGISTRY}/${ECR_REPOSITORY}@${IMAGE_DIGEST}"

describe_asg >"$ASG_JSON_FILE"
[[ "$(jq -r '.AutoScalingGroups | length' "$ASG_JSON_FILE")" == "1" ]] ||
  die "expected exactly one approved Auto Scaling Group"
[[ "$(jq -r '.AutoScalingGroups[0].AutoScalingGroupName' "$ASG_JSON_FILE")" == "$ASG_NAME" ]] ||
  die "Auto Scaling Group name does not match the fixed resource contract"
[[ "$(jq -r '.AutoScalingGroups[0].MinSize' "$ASG_JSON_FILE")" == "1" &&
  "$(jq -r '.AutoScalingGroups[0].DesiredCapacity' "$ASG_JSON_FILE")" == "1" &&
  "$(jq -r '.AutoScalingGroups[0].MaxSize' "$ASG_JSON_FILE")" == "1" ]] ||
  die "Green web ASG must remain Min 1 / Desired 1 / Max 1 for this rollout"
[[ "$(jq -r '.AutoScalingGroups[0].LaunchTemplate.LaunchTemplateName // empty' "$ASG_JSON_FILE")" == "$LAUNCH_TEMPLATE_NAME" ]] ||
  die "Auto Scaling Group Launch Template name does not match the fixed contract"
[[ "$(jq -r '.AutoScalingGroups[0].TargetGroupARNs | length' "$ASG_JSON_FILE")" == "1" &&
  "$(jq -r '.AutoScalingGroups[0].TargetGroupARNs[0]' "$ASG_JSON_FILE")" == "$TARGET_GROUP_ARN" ]] ||
  die "Auto Scaling Group Target Group does not match the fixed contract"

readonly SOURCE_LT_ID="$(
  jq -r '.AutoScalingGroups[0].LaunchTemplate.LaunchTemplateId // empty' \
    "$ASG_JSON_FILE"
)"
SOURCE_LT_VERSION="$(
  jq -r '.AutoScalingGroups[0].LaunchTemplate.Version // empty' \
    "$ASG_JSON_FILE"
)"
readonly INSTANCE_WARMUP="$(
  jq -r '
    .AutoScalingGroups[0].DefaultInstanceWarmup
    // .AutoScalingGroups[0].HealthCheckGracePeriod
    // 300
  ' "$ASG_JSON_FILE"
)"
[[ -n "$SOURCE_LT_ID" ]] ||
  die "Auto Scaling Group has no Launch Template ID"
[[ "$SOURCE_LT_VERSION" =~ ^[0-9]+$ ]] ||
  die "source Launch Template version must be numeric"
require_positive_integer "ASG instance warmup" "$INSTANCE_WARMUP"

aws_cli ec2 describe-launch-template-versions \
  --launch-template-id "$SOURCE_LT_ID" \
  --versions "$SOURCE_LT_VERSION" \
  --output json >"$SOURCE_LT_JSON_FILE"
[[ "$(jq -r '.LaunchTemplateVersions | length' "$SOURCE_LT_JSON_FILE")" == "1" ]] ||
  die "source Launch Template version was not found"
[[ "$(jq -r '.LaunchTemplateVersions[0].VersionNumber | tostring' "$SOURCE_LT_JSON_FILE")" == "$SOURCE_LT_VERSION" ]] ||
  die "source Launch Template response version does not match the ASG"

readonly SOURCE_AMI_ID="$(
  jq -r '.LaunchTemplateVersions[0].LaunchTemplateData.ImageId // empty' \
    "$SOURCE_LT_JSON_FILE"
)"
[[ -n "$SOURCE_AMI_ID" ]] ||
  die "source Launch Template has no AMI"
readonly SOURCE_AMI_JSON="$(
  aws_cli ec2 describe-images \
    --image-ids "$SOURCE_AMI_ID" \
    --output json
)"
[[ "$(jq -r '.Images | length' <<<"$SOURCE_AMI_JSON")" == "1" ]] ||
  die "source Launch Template AMI was not found"
[[ "$(jq -r '.Images[0].State' <<<"$SOURCE_AMI_JSON")" == "available" ]] ||
  die "source Launch Template AMI is not available"
[[ "$(jq -r '[.Images[0].Tags[]? | select(.Key == "Validation")][0].Value // empty' <<<"$SOURCE_AMI_JSON")" == "passed" ]] ||
  die "source Launch Template AMI is missing Validation=passed"

readonly SOURCE_USER_DATA_BASE64="$(
  jq -r '.LaunchTemplateVersions[0].LaunchTemplateData.UserData // empty' \
    "$SOURCE_LT_JSON_FILE"
)"
load_source_release_manifest "$SOURCE_USER_DATA_BASE64" "$SOURCE_MANIFEST"
replace_service_image "$SOURCE_MANIFEST" "$RELEASE_MANIFEST" "$IMMUTABLE_IMAGE"
render_user_data "$RELEASE_MANIFEST" "$USER_DATA_FILE"

log "validated service=$SERVICE git_sha=$GIT_SHA"
log "resolved immutable image=$IMMUTABLE_IMAGE"
log "source Launch Template=$SOURCE_LT_ID version=$SOURCE_LT_VERSION"

if [[ "$APPLY" != "true" ]]; then
  log "read-only validation complete; no AWS resources were changed"
  exit 0
fi

verify_asg_version "$SOURCE_LT_VERSION"
reject_active_instance_refresh

readonly USER_DATA_BASE64="$(base64 <"$USER_DATA_FILE" | tr -d '\n')"
jq -n \
  --arg user_data "$USER_DATA_BASE64" \
  '{UserData: $user_data}' >"$LT_OVERRIDE_FILE"

readonly NEW_LT_VERSION="$(
  aws_cli ec2 create-launch-template-version \
    --launch-template-id "$SOURCE_LT_ID" \
    --source-version "$SOURCE_LT_VERSION" \
    --version-description \
      "green-${SERVICE}-${GIT_SHA:0:12}" \
    --launch-template-data "file://${LT_OVERRIDE_FILE}" \
    --query 'LaunchTemplateVersion.VersionNumber' \
    --output text
)"
[[ "$NEW_LT_VERSION" =~ ^[0-9]+$ ]] ||
  die "new Launch Template version is not numeric"
[[ "$NEW_LT_VERSION" != "$SOURCE_LT_VERSION" ]] ||
  die "new Launch Template version must differ from the source version"

aws_cli ec2 describe-launch-template-versions \
  --launch-template-id "$SOURCE_LT_ID" \
  --versions "$NEW_LT_VERSION" \
  --output json >"$CANDIDATE_LT_JSON_FILE"
validate_candidate_launch_template \
  "$SOURCE_LT_JSON_FILE" \
  "$CANDIDATE_LT_JSON_FILE" \
  "$NEW_LT_VERSION" \
  "$USER_DATA_BASE64"

jq -n \
  --arg launch_template_id "$SOURCE_LT_ID" \
  --arg version "$NEW_LT_VERSION" \
  '{
    LaunchTemplate: {
      LaunchTemplateId: $launch_template_id,
      Version: $version
    }
  }' >"$DESIRED_CONFIGURATION_FILE"
jq -n \
  --argjson warmup "$INSTANCE_WARMUP" \
  '{
    MinHealthyPercentage: 100,
    MaxHealthyPercentage: 200,
    InstanceWarmup: $warmup,
    SkipMatching: true,
    AutoRollback: true
  }' >"$REFRESH_PREFERENCES_FILE"

readonly REFRESH_ID="$(
  aws_cli autoscaling start-instance-refresh \
    --auto-scaling-group-name "$ASG_NAME" \
    --strategy Rolling \
    --desired-configuration "file://${DESIRED_CONFIGURATION_FILE}" \
    --preferences "file://${REFRESH_PREFERENCES_FILE}" \
    --query 'InstanceRefreshId' \
    --output text
)"
[[ -n "$REFRESH_ID" && "$REFRESH_ID" != "None" ]] ||
  die "Instance Refresh returned an empty refresh ID"
ROLLBACK_ARMED=true
log "started Instance Refresh id=$REFRESH_ID LT-version=$NEW_LT_VERSION"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  printf '%s\n' \
    "launch_template_version=$NEW_LT_VERSION" \
    "instance_refresh_id=$REFRESH_ID" >>"$GITHUB_OUTPUT"
fi

if wait_for_instance_refresh "$REFRESH_ID" "forward"; then
  POST_REFRESH_FAILURE=""
  if ! verify_asg_version "$NEW_LT_VERSION"; then
    POST_REFRESH_FAILURE="ASG did not converge to the new numeric LT version"
  elif ! wait_for_all_targets_healthy; then
    POST_REFRESH_FAILURE="Target Group did not converge to all healthy targets"
  fi

  if [[ -n "$POST_REFRESH_FAILURE" ]]; then
    log "$POST_REFRESH_FAILURE; starting rollback"
    if ! reverse_completed_refresh "$SOURCE_LT_VERSION"; then
      die "post-refresh validation failed and rollback also failed; manual recovery is required"
    fi
    die "post-refresh validation failed; rollback restored LT version $SOURCE_LT_VERSION"
  fi

  ROLLBACK_ARMED=false
  log "release rollout completed with numeric LT version $NEW_LT_VERSION"
  exit 0
else
  readonly FORWARD_WAIT_STATUS=$?
  if [[ "$FORWARD_WAIT_STATUS" -eq 3 ]]; then
    ROLLBACK_STARTED=true
    if ! verify_asg_version "$SOURCE_LT_VERSION"; then
      die "AWS AutoRollback did not restore the previous numeric LT version"
    fi
    if ! wait_for_all_targets_healthy; then
      die "AWS AutoRollback restored the LT version but targets are not healthy"
    fi
    ROLLBACK_COMPLETED=true
    ROLLBACK_ARMED=false
    die "forward Instance Refresh failed and AWS AutoRollback restored LT version $SOURCE_LT_VERSION"
  fi
  if ! rollback_failed_refresh "$SOURCE_LT_VERSION"; then
    die "forward Instance Refresh failed and rollback also failed; manual recovery is required"
  fi
  die "forward Instance Refresh failed; rollback restored LT version $SOURCE_LT_VERSION"
fi
