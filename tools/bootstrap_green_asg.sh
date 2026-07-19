#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

readonly AWS_REGION="${AWS_REGION:-ap-northeast-2}"
readonly AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-443915990705}"
readonly AWS_STS_REGIONAL_ENDPOINTS="regional"
readonly ECR_REGISTRY="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
readonly API_REPOSITORY="buildgraph-demo-api-green"
readonly XGB_REPOSITORY="buildgraph-demo-xgb-reranker-green"
readonly APP_ROOT="${BUILDGRAPH_APP_ROOT:-/opt/buildgraph/prototype}"
readonly RELEASE_MANIFEST="${BUILDGRAPH_RELEASE_MANIFEST:-$APP_ROOT/infra/asg/green-release.env}"
readonly RUNTIME_ENV="${BUILDGRAPH_RUNTIME_ENV:-$APP_ROOT/.env.prod}"
readonly IMAGE_MANIFEST="${GREEN_IMAGE_MANIFEST:-/opt/buildgraph/green-images.env}"
readonly ASG_RUNTIME_ENV="${BUILDGRAPH_ASG_RUNTIME_ENV:-/opt/buildgraph/asg-runtime.env}"
readonly STATE_DIR="${BUILDGRAPH_ASG_STATE_DIR:-/var/lib/buildgraph}"
readonly LOCK_FILE="${BUILDGRAPH_ASG_LOCK_FILE:-$STATE_DIR/asg-bootstrap.lock}"
readonly SUCCESS_MARKER="$STATE_DIR/asg-bootstrap-success"
readonly SECRET_ID="${GREEN_API_SECRET_ID:-buildgraph/demo-green/api-env}"
readonly APP_USER="${BUILDGRAPH_APP_USER:-ubuntu}"
readonly FILE_OWNER="${BUILDGRAPH_FILE_OWNER:-ubuntu:ubuntu}"
readonly COMPOSE_PROJECT_NAME="buildgraph-green"
readonly HEALTH_URL="${BUILDGRAPH_HEALTH_URL:-http://127.0.0.1/api/health}"
readonly HEALTH_MAX_ATTEMPTS="${BUILDGRAPH_HEALTH_MAX_ATTEMPTS:-60}"
readonly HEALTH_RETRY_SECONDS="${BUILDGRAPH_HEALTH_RETRY_SECONDS:-5}"

export AWS_STS_REGIONAL_ENDPOINTS

CURRENT_STEP="initialization"
TEMP_DIR=""

log() {
  printf '%s\n' "buildgraph ASG bootstrap: $*"
}

die() {
  printf '%s\n' "buildgraph ASG bootstrap rejected: $*" >&2
  return 1
}

cleanup() {
  if [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
    rm -rf "$TEMP_DIR"
  fi
}

handle_error() {
  local status=$?
  trap - ERR
  rm -f "$SUCCESS_MARKER"
  printf '%s\n' \
    "buildgraph ASG bootstrap failed: step=$CURRENT_STEP status=$status" >&2
  exit "$status"
}

trap cleanup EXIT
trap handle_error ERR

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command is missing: $1"
}

run_git() {
  runuser -u "$APP_USER" -- git "$@"
}

read_manifest_value() {
  local key="$1"
  local file="$2"
  local count value

  count="$(grep -c "^${key}=" "$file" || true)"
  [[ "$count" -eq 1 ]] || die "release manifest must contain exactly one $key"
  value="$(sed -n "s/^${key}=//p" "$file")"
  [[ -n "$value" ]] || die "release manifest value is empty: $key"
  printf '%s' "$value"
}

validate_release_manifest_shape() {
  local line key
  local count=0

  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -n "$line" ]] || continue
    [[ "$line" != \#* ]] || continue
    [[ "$line" =~ ^([A-Z][A-Z0-9_]*)= ]] ||
      die "release manifest contains an invalid line"
    key="${BASH_REMATCH[1]}"
    case "$key" in
      API_IMAGE_URI|XGB_IMAGE_URI|NGINX_IMAGE_URI|BUILDGRAPH_SCHEDULING_ENABLED)
        ;;
      *)
        die "release manifest contains an unexpected key: $key"
        ;;
    esac
    count=$((count + 1))
  done <"$RELEASE_MANIFEST"

  [[ "$count" -eq 4 ]] || die "release manifest must contain exactly four values"
}

validate_ecr_digest_image() {
  local image_uri="$1"
  local repository="$2"
  local prefix="${ECR_REGISTRY}/${repository}@sha256:"
  local digest

  [[ "$image_uri" == "$prefix"* ]] ||
    die "image must use the approved ECR repository and digest: $repository"
  digest="${image_uri#"$prefix"}"
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] ||
    die "image digest is invalid for repository: $repository"
}

validate_nginx_digest_image() {
  local image_uri="$1"
  local prefix="docker.io/library/nginx@sha256:"
  local digest

  [[ "$image_uri" == "$prefix"* ]] ||
    die "Nginx image must use the approved official digest reference"
  digest="${image_uri#"$prefix"}"
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || die "Nginx image digest is invalid"
}

install_runtime_file() {
  local source="$1"
  local destination="$2"
  local candidate="${destination}.tmp.$$"

  cp "$source" "$candidate"
  chmod 600 "$candidate"
  chown "$FILE_OWNER" "$candidate"
  mv -f "$candidate" "$destination"
}

compose() {
  env \
    -u API_IMAGE_URI \
    -u XGB_IMAGE_URI \
    -u NGINX_IMAGE_URI \
    -u BUILDGRAPH_SCHEDULING_ENABLED \
    docker compose \
      -p "$COMPOSE_PROJECT_NAME" \
      -f "$APP_ROOT/compose.api.ecr.prod.yaml" \
      --env-file "$RUNTIME_ENV" \
      --env-file "$IMAGE_MANIFEST" \
      --env-file "$ASG_RUNTIME_ENV" \
      "$@"
}

verify_running_image() {
  local service="$1"
  local expected_image="$2"
  local container_id running_image

  container_id="$(compose ps -q "$service")"
  [[ -n "$container_id" ]] || die "container is missing after bootstrap: $service"
  running_image="$(docker inspect --format '{{.Config.Image}}' "$container_id")"
  [[ "$running_image" == "$expected_image" ]] ||
    die "running image mismatch for service: $service"
}

wait_for_api() {
  local attempt

  for ((attempt = 1; attempt <= HEALTH_MAX_ATTEMPTS; attempt++)); do
    if curl -fsS "$HEALTH_URL" >/dev/null; then
      return 0
    fi
    if [[ "$attempt" -lt "$HEALTH_MAX_ATTEMPTS" ]]; then
      sleep "$HEALTH_RETRY_SECONDS"
    fi
  done
  return 1
}

if [[ "$(id -u)" -ne 0 && "${BUILDGRAPH_ALLOW_NON_ROOT_FOR_TESTS:-false}" != "true" ]]; then
  die "bootstrap must run as root"
fi

for command_name in aws chown chmod cp curl docker env flock git grep mkdir mktemp mv runuser sed systemctl; do
  require_command "$command_name"
done

[[ -d "$APP_ROOT/.git" ]] || die "application repository is missing: $APP_ROOT"
[[ -s "$APP_ROOT/compose.api.ecr.prod.yaml" ]] || die "ECR Compose file is missing"
[[ -s "$RELEASE_MANIFEST" ]] || die "release manifest is missing"
[[ "$HEALTH_MAX_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] || die "health attempts must be positive"
[[ "$HEALTH_RETRY_SECONDS" =~ ^[0-9]+$ ]] || die "health retry seconds must be non-negative"

mkdir -p "$STATE_DIR" "$(dirname "$RUNTIME_ENV")" "$(dirname "$IMAGE_MANIFEST")" "$(dirname "$ASG_RUNTIME_ENV")"
rm -f "$SUCCESS_MARKER"
exec 9>"$LOCK_FILE"
flock -x 9

CURRENT_STEP="verify-instance-identity"
readonly IMDS_TOKEN="$(
  curl -fsS \
    -X PUT \
    -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' \
    http://169.254.169.254/latest/api/token
)"
[[ -n "$IMDS_TOKEN" ]] || die "IMDSv2 token request returned an empty value"
readonly INSTANCE_REGION="$(
  curl -fsS \
    -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" \
    http://169.254.169.254/latest/meta-data/placement/region
)"
[[ "$INSTANCE_REGION" == "$AWS_REGION" ]] || die "instance region does not match the approved region"

readonly CALLER_ACCOUNT="$(
  aws sts get-caller-identity \
    --query Account \
    --output text \
    --region "$AWS_REGION" \
    --no-cli-pager
)"
[[ "$CALLER_ACCOUNT" == "$AWS_ACCOUNT_ID" ]] || die "caller account does not match the approved account"

CURRENT_STEP="validate-release"
validate_release_manifest_shape
readonly API_IMAGE_URI="$(read_manifest_value API_IMAGE_URI "$RELEASE_MANIFEST")"
readonly XGB_IMAGE_URI="$(read_manifest_value XGB_IMAGE_URI "$RELEASE_MANIFEST")"
readonly NGINX_IMAGE_URI="$(read_manifest_value NGINX_IMAGE_URI "$RELEASE_MANIFEST")"
readonly BUILDGRAPH_SCHEDULING_ENABLED="$(
  read_manifest_value BUILDGRAPH_SCHEDULING_ENABLED "$RELEASE_MANIFEST"
)"
[[ "$BUILDGRAPH_SCHEDULING_ENABLED" == "false" ]] ||
  die "ASG web instance scheduling must be disabled"
validate_ecr_digest_image "$API_IMAGE_URI" "$API_REPOSITORY"
validate_ecr_digest_image "$XGB_IMAGE_URI" "$XGB_REPOSITORY"
validate_nginx_digest_image "$NGINX_IMAGE_URI"

readonly GIT_SHA="$(run_git -C "$APP_ROOT" rev-parse HEAD)"
[[ "$GIT_SHA" =~ ^[0-9a-f]{40}$ ]] || die "application Git SHA is invalid"

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/buildgraph-asg-bootstrap.XXXXXX")"
readonly SECRET_CANDIDATE="$TEMP_DIR/api-env.secret"
readonly IMAGE_CANDIDATE="$TEMP_DIR/green-images.env"
readonly ASG_RUNTIME_CANDIDATE="$TEMP_DIR/asg-runtime.env"

CURRENT_STEP="retrieve-runtime-secret"
aws secretsmanager get-secret-value \
  --secret-id "$SECRET_ID" \
  --region "$AWS_REGION" \
  --query SecretString \
  --output text \
  --no-cli-pager >"$SECRET_CANDIDATE"
[[ -s "$SECRET_CANDIDATE" ]] || die "Secrets Manager returned an empty runtime environment"
chmod 600 "$SECRET_CANDIDATE"

printf '%s\n' \
  "API_IMAGE_URI=$API_IMAGE_URI" \
  "XGB_IMAGE_URI=$XGB_IMAGE_URI" >"$IMAGE_CANDIDATE"
printf '%s\n' \
  "NGINX_IMAGE_URI=$NGINX_IMAGE_URI" \
  "BUILDGRAPH_SCHEDULING_ENABLED=false" \
  "AWS_STS_REGIONAL_ENDPOINTS=$AWS_STS_REGIONAL_ENDPOINTS" >"$ASG_RUNTIME_CANDIDATE"
chmod 600 "$IMAGE_CANDIDATE" "$ASG_RUNTIME_CANDIDATE"

CURRENT_STEP="install-runtime-files"
install_runtime_file "$SECRET_CANDIDATE" "$RUNTIME_ENV"
install_runtime_file "$IMAGE_CANDIDATE" "$IMAGE_MANIFEST"
install_runtime_file "$ASG_RUNTIME_CANDIDATE" "$ASG_RUNTIME_ENV"

CURRENT_STEP="validate-compose"
compose config --quiet

CURRENT_STEP="pull-images"
aws ecr get-login-password --region "$AWS_REGION" --no-cli-pager |
  docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null
compose pull

CURRENT_STEP="start-services"
compose up -d --remove-orphans
verify_running_image nginx "$NGINX_IMAGE_URI"
verify_running_image api "$API_IMAGE_URI"
verify_running_image recommendation-event-worker "$API_IMAGE_URI"
verify_running_image xgb-reranker "$XGB_IMAGE_URI"
compose exec -T nginx nginx -t

CURRENT_STEP="wait-for-api-health"
wait_for_api || die "API health did not become ready within the configured attempts"

CURRENT_STEP="start-cloudwatch-agent"
systemctl enable --now amazon-cloudwatch-agent

CURRENT_STEP="write-success-marker"
readonly MARKER_CANDIDATE="$TEMP_DIR/asg-bootstrap-success"
printf '%s\n' \
  "completed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  "git_sha=$GIT_SHA" \
  "api_image_digest=${API_IMAGE_URI##*@}" \
  "xgb_image_digest=${XGB_IMAGE_URI##*@}" \
  "nginx_image_digest=${NGINX_IMAGE_URI##*@}" >"$MARKER_CANDIDATE"
chmod 600 "$MARKER_CANDIDATE"
mv -f "$MARKER_CANDIDATE" "$SUCCESS_MARKER"

trap - ERR
log "completed successfully"
