#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

readonly APPROVED_AWS_ACCOUNT_ID="443915990705"
readonly APPROVED_AWS_REGION="ap-northeast-2"
readonly ECR_REGISTRY="${APPROVED_AWS_ACCOUNT_ID}.dkr.ecr.${APPROVED_AWS_REGION}.amazonaws.com"
readonly API_REPOSITORY="buildgraph-demo-api-green"
readonly XGB_REPOSITORY="buildgraph-demo-xgb-reranker-green"
readonly APP_ROOT="${BUILDGRAPH_APP_ROOT:-/opt/buildgraph/prototype}"
readonly APP_USER="${BUILDGRAPH_APP_USER:-ubuntu}"
readonly FILE_OWNER="${BUILDGRAPH_FILE_OWNER:-ubuntu:ubuntu}"
readonly IMAGE_MANIFEST="${GREEN_IMAGE_MANIFEST:-/opt/buildgraph/green-images.env}"
readonly ASG_RUNTIME_ENV="${BUILDGRAPH_ASG_RUNTIME_ENV:-/opt/buildgraph/asg-runtime.env}"
readonly SUCCESS_MARKER="${BUILDGRAPH_ASG_SUCCESS_MARKER:-/var/lib/buildgraph/asg-bootstrap-success}"
readonly RELEASE_ROOT="${BUILDGRAPH_RELEASE_ROOT:-/opt/buildgraph/releases}"
readonly TRANSACTION_ROOT="${BUILDGRAPH_FAST_DEPLOY_STATE_DIR:-/var/lib/buildgraph/fast-deploy}"
readonly LOCK_FILE="${GREEN_DEPLOY_LOCK_FILE:-/opt/buildgraph/green-deploy.lock}"
readonly COMPOSE_PROJECT_NAME="buildgraph-green"
readonly HEALTH_MAX_ATTEMPTS="${BUILDGRAPH_FAST_HEALTH_MAX_ATTEMPTS:-60}"
readonly HEALTH_POLL_SECONDS="${BUILDGRAPH_FAST_HEALTH_POLL_SECONDS:-2}"

ACTION=""
DEPLOYMENT_ID=""
SERVICE=""
SOURCE_GIT_SHA=""
TARGET_GIT_SHA=""
SOURCE_MANIFEST_B64=""
TARGET_MANIFEST_B64=""
TRANSACTION_DIR=""
CANCELLATION_FENCE=""
PREPARE_MUTATED=false

log() {
  printf '%s\n' "buildgraph ASG in-place apply: $*"
}

die() {
  printf '%s\n' "buildgraph ASG in-place apply rejected: $*" >&2
  return 1
}

usage() {
  cat <<'USAGE' >&2
Usage:
  tools/apply_green_asg_release_in_place.sh prepare \
    --deployment-id <safe-id> \
    --service api|xgb-reranker \
    --source-git-sha <40-char-sha> \
    --target-git-sha <40-char-sha> \
    --source-manifest-b64 <base64> \
    --target-manifest-b64 <base64>

  tools/apply_green_asg_release_in_place.sh rollback|commit|cleanup \
    --deployment-id <safe-id>
USAGE
}

require_command() {
  command -v "$1" >/dev/null 2>&1 ||
    die "required command is missing: $1"
}

run_git() {
  runuser -u "$APP_USER" -- git "$@"
}

read_manifest_value() {
  local key="$1"
  local file="$2"
  local count value

  count="$(grep -c "^${key}=" "$file" || true)"
  [[ "$count" -eq 1 ]] ||
    die "manifest must contain exactly one $key"
  value="$(sed -n "s/^${key}=//p" "$file")"
  [[ -n "$value" ]] || die "manifest value is empty: $key"
  printf '%s' "$value"
}

validate_ecr_digest_image() {
  local image_uri="$1"
  local repository="$2"
  local prefix="${ECR_REGISTRY}/${repository}@sha256:"
  local digest

  [[ "$image_uri" == "$prefix"* ]] ||
    die "image must use the approved digest repository: $repository"
  digest="${image_uri#"$prefix"}"
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] ||
    die "image digest is invalid: $repository"
}

validate_nginx_digest_image() {
  local image_uri="$1"
  local prefix="docker.io/library/nginx@sha256:"
  local digest

  [[ "$image_uri" == "$prefix"* ]] ||
    die "Nginx image must use the approved official digest"
  digest="${image_uri#"$prefix"}"
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || die "Nginx digest is invalid"
}

canonicalize_manifest() {
  local source_file="$1"
  local destination_file="$2"
  local line key count=0
  local api_image xgb_image nginx_image scheduling

  [[ -s "$source_file" ]] || die "release manifest is empty"
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -n "$line" ]] || continue
    [[ "$line" != \#* ]] || continue
    [[ "$line" =~ ^([A-Z][A-Z0-9_]*)=(.+)$ ]] ||
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
  done <"$source_file"
  [[ "$count" -eq 4 ]] || die "release manifest must contain four values"

  api_image="$(read_manifest_value API_IMAGE_URI "$source_file")"
  xgb_image="$(read_manifest_value XGB_IMAGE_URI "$source_file")"
  nginx_image="$(read_manifest_value NGINX_IMAGE_URI "$source_file")"
  scheduling="$(read_manifest_value BUILDGRAPH_SCHEDULING_ENABLED "$source_file")"
  validate_ecr_digest_image "$api_image" "$API_REPOSITORY"
  validate_ecr_digest_image "$xgb_image" "$XGB_REPOSITORY"
  validate_nginx_digest_image "$nginx_image"
  [[ "$scheduling" == "false" ]] ||
    die "ASG web scheduling must remain disabled"

  printf '%s\n' \
    "API_IMAGE_URI=$api_image" \
    "XGB_IMAGE_URI=$xgb_image" \
    "NGINX_IMAGE_URI=$nginx_image" \
    "BUILDGRAPH_SCHEDULING_ENABLED=false" >"$destination_file"
}

decode_manifest() {
  local encoded="$1"
  local destination="$2"
  local decoded="${destination}.decoded"

  printf '%s' "$encoded" | base64 --decode >"$decoded" 2>/dev/null ||
    die "release manifest is not valid base64"
  canonicalize_manifest "$decoded" "$destination"
  rm -f "$decoded"
}

write_current_manifest() {
  local destination="$1"

  [[ -s "$IMAGE_MANIFEST" ]] || die "active image manifest is missing"
  [[ -s "$ASG_RUNTIME_ENV" ]] || die "ASG runtime environment is missing"
  printf '%s\n' \
    "API_IMAGE_URI=$(read_manifest_value API_IMAGE_URI "$IMAGE_MANIFEST")" \
    "XGB_IMAGE_URI=$(read_manifest_value XGB_IMAGE_URI "$IMAGE_MANIFEST")" \
    "NGINX_IMAGE_URI=$(read_manifest_value NGINX_IMAGE_URI "$ASG_RUNTIME_ENV")" \
    "BUILDGRAPH_SCHEDULING_ENABLED=$(read_manifest_value BUILDGRAPH_SCHEDULING_ENABLED "$ASG_RUNTIME_ENV")" \
    >"${destination}.raw"
  canonicalize_manifest "${destination}.raw" "$destination"
  rm -f "${destination}.raw"
}

write_runtime_files() {
  local release_manifest="$1"
  local image_destination="$2"
  local runtime_destination="$3"

  printf '%s\n' \
    "API_IMAGE_URI=$(read_manifest_value API_IMAGE_URI "$release_manifest")" \
    "XGB_IMAGE_URI=$(read_manifest_value XGB_IMAGE_URI "$release_manifest")" \
    >"$image_destination"
  printf '%s\n' \
    "NGINX_IMAGE_URI=$(read_manifest_value NGINX_IMAGE_URI "$release_manifest")" \
    "BUILDGRAPH_SCHEDULING_ENABLED=false" \
    "AWS_STS_REGIONAL_ENDPOINTS=regional" \
    >"$runtime_destination"
  chmod 600 "$image_destination" "$runtime_destination"
}

install_runtime_file() {
  local source="$1"
  local destination="$2"
  local candidate="${destination}.fast-deploy.$$"

  cp "$source" "$candidate"
  chmod 600 "$candidate"
  chown "$FILE_OWNER" "$candidate"
  mv -f "$candidate" "$destination"
}

compose_with() {
  local image_env="$1"
  local runtime_env="$2"
  shift 2

  env \
    -u API_IMAGE_URI \
    -u XGB_IMAGE_URI \
    -u NGINX_IMAGE_URI \
    -u BUILDGRAPH_SCHEDULING_ENABLED \
    docker compose \
      -p "$COMPOSE_PROJECT_NAME" \
      -f "$APP_ROOT/compose.api.ecr.prod.yaml" \
      --env-file "$APP_ROOT/.env.prod" \
      --env-file "$image_env" \
      --env-file "$runtime_env" \
      "$@"
}

container_id_for() {
  local service="$1"
  local container_id

  container_id="$(compose_with "$IMAGE_MANIFEST" "$ASG_RUNTIME_ENV" ps -q "$service")"
  [[ -n "$container_id" ]] || die "container is missing: $service"
  printf '%s' "$container_id"
}

runtime_services() {
  printf '%s\n' nginx api recommendation-event-worker xgb-reranker
}

target_deploy_services() {
  local service="$1"
  if [[ "$service" == "api" ]]; then
    printf '%s\n' api recommendation-event-worker
  else
    printf '%s\n' "$service"
  fi
}

is_target_deploy_service() {
  local service="$1"
  local target_service="$2"
  local candidate

  for candidate in $(target_deploy_services "$target_service"); do
    [[ "$service" != "$candidate" ]] || return 0
  done
  return 1
}

verify_running_image() {
  local service="$1"
  local expected_image="$2"
  local container_id running_image

  container_id="$(container_id_for "$service")"
  running_image="$(docker inspect --format '{{.Config.Image}}' "$container_id")"
  [[ "$running_image" == "$expected_image" ]] ||
    die "running image does not match the approved digest: $service"
}

verify_api_scheduling_disabled() {
  local container_id scheduling_disabled

  container_id="$(container_id_for api)"
  scheduling_disabled="$(
    docker inspect \
      --format '{{range .Config.Env}}{{if eq . "BUILDGRAPH_SCHEDULING_ENABLED=false"}}true{{end}}{{end}}' \
      "$container_id"
  )"
  [[ "$scheduling_disabled" == "true" ]] ||
    die "API container scheduling must remain disabled"
}

snapshot_container_ids() {
  local transaction_dir="$1"
  local service

  for service in $(runtime_services); do
    container_id_for "$service" >"$transaction_dir/container-id-$service"
  done
}

verify_non_target_containers_unchanged() {
  local transaction_dir="$1"
  local target_service="$2"
  local service before after

  for service in $(runtime_services); do
    if is_target_deploy_service "$service" "$target_service"; then
      continue
    fi
    before="$(cat "$transaction_dir/container-id-$service")"
    after="$(container_id_for "$service")"
    [[ "$after" == "$before" ]] ||
      die "Fast Deploy changed a non-target container: $service"
  done
}

verify_all_running_images() {
  local manifest="$1"
  verify_running_image nginx "$(read_manifest_value NGINX_IMAGE_URI "$manifest")"
  verify_running_image api "$(read_manifest_value API_IMAGE_URI "$manifest")"
  verify_running_image recommendation-event-worker "$(read_manifest_value API_IMAGE_URI "$manifest")"
  verify_running_image xgb-reranker "$(read_manifest_value XGB_IMAGE_URI "$manifest")"
  verify_api_scheduling_disabled
}

wait_for_service() {
  local service="$1"
  local attempt container_id health

  for ((attempt = 1; attempt <= HEALTH_MAX_ATTEMPTS; attempt++)); do
    if [[ "$service" == "api" ]]; then
      if curl -fsS http://127.0.0.1/api/health >/dev/null; then
        return 0
      fi
    else
      container_id="$(compose_with "$IMAGE_MANIFEST" "$ASG_RUNTIME_ENV" ps -q xgb-reranker)"
      if [[ -n "$container_id" ]]; then
        health="$(docker inspect --format '{{.State.Health.Status}}' "$container_id" 2>/dev/null || true)"
        [[ "$health" != "healthy" ]] || return 0
      fi
    fi
    [[ "$attempt" -ge "$HEALTH_MAX_ATTEMPTS" ]] || sleep "$HEALTH_POLL_SECONDS"
  done
  return 1
}

write_success_marker() {
  local git_sha="$1"
  local manifest="$2"
  local candidate="${SUCCESS_MARKER}.fast-deploy.$$"

  printf '%s\n' \
    "completed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    "git_sha=$git_sha" \
    "api_image_digest=$(read_manifest_value API_IMAGE_URI "$manifest" | sed 's/^.*@//')" \
    "xgb_image_digest=$(read_manifest_value XGB_IMAGE_URI "$manifest" | sed 's/^.*@//')" \
    "nginx_image_digest=$(read_manifest_value NGINX_IMAGE_URI "$manifest" | sed 's/^.*@//')" \
    >"$candidate"
  chmod 600 "$candidate"
  mv -f "$candidate" "$SUCCESS_MARKER"
}

restore_transaction() {
  local transaction_dir="$1"
  local service source_git_sha target_git_sha source_manifest previous_marker_exists

  [[ -d "$transaction_dir" ]] || {
    die "deployment transaction was not found"
    return 1
  }
  service="$(cat "$transaction_dir/service")" || return 1
  source_git_sha="$(cat "$transaction_dir/source-git-sha")" || return 1
  target_git_sha="$(cat "$transaction_dir/target-git-sha")" || return 1
  source_manifest="$transaction_dir/source-release.env"
  previous_marker_exists="$(cat "$transaction_dir/previous-marker-exists")" || return 1

  run_git -C "$APP_ROOT" checkout --detach "$source_git_sha" >/dev/null || return 1
  [[ "$(run_git -C "$APP_ROOT" rev-parse HEAD)" == "$source_git_sha" ]] || return 1
  install_runtime_file "$transaction_dir/green-images.env" "$IMAGE_MANIFEST" || return 1
  install_runtime_file "$transaction_dir/asg-runtime.env" "$ASG_RUNTIME_ENV" || return 1
  compose_with "$IMAGE_MANIFEST" "$ASG_RUNTIME_ENV" config --quiet || return 1
  aws ecr get-login-password --region "$APPROVED_AWS_REGION" --no-cli-pager |
    docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null || return 1
  compose_with "$IMAGE_MANIFEST" "$ASG_RUNTIME_ENV" \
    pull $(target_deploy_services "$service") || return 1
  compose_with "$IMAGE_MANIFEST" "$ASG_RUNTIME_ENV" \
    up -d --no-deps --force-recreate --no-build \
    $(target_deploy_services "$service") || return 1
  if [[ "$service" == "api" ]]; then
    compose_with "$IMAGE_MANIFEST" "$ASG_RUNTIME_ENV" exec -T nginx nginx -t || return 1
    compose_with "$IMAGE_MANIFEST" "$ASG_RUNTIME_ENV" exec -T nginx nginx -s reload || return 1
  fi
  wait_for_service "$service" || {
    die "rollback health did not recover"
    return 1
  }
  verify_all_running_images "$source_manifest" || return 1

  if [[ "$previous_marker_exists" == "true" ]]; then
    install_runtime_file "$transaction_dir/asg-bootstrap-success" "$SUCCESS_MARKER" || return 1
  else
    rm -f "$SUCCESS_MARKER" || return 1
  fi
  install -d -m 0700 "$RELEASE_ROOT" || return 1
  install_runtime_file \
    "$transaction_dir/source-release.env" \
    "$RELEASE_ROOT/green-release-${source_git_sha}.env" || return 1
  if [[ "$target_git_sha" != "$source_git_sha" ]]; then
    rm -f "$RELEASE_ROOT/green-release-${target_git_sha}.env" || return 1
  fi
  write_transaction_status "$transaction_dir" rolled-back || return 1
  rm -rf "$transaction_dir" || return 1
  log "rollback completed: deployment_id=$(basename "$transaction_dir")"
}

write_transaction_status() {
  local transaction_dir="$1"
  local status="$2"
  local candidate="$transaction_dir/status.tmp.$$"

  printf '%s\n' "$status" >"$candidate"
  chmod 600 "$candidate"
  mv -f "$candidate" "$transaction_dir/status"
}

write_cancellation_fence() {
  local candidate="${CANCELLATION_FENCE}.tmp.$$"

  printf '%s\n' "cancelled_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$candidate"
  chmod 600 "$candidate"
  mv -f "$candidate" "$CANCELLATION_FENCE"
}

reconcile_stale_transactions() {
  local transaction status source_sha target_sha current_git_sha
  local reconcile_manifest="$TRANSACTION_DIR/reconcile-current-release.env"

  shopt -s nullglob
  for transaction in "$TRANSACTION_ROOT"/*; do
    [[ -d "$transaction" ]] || continue
    [[ "$transaction" != "$TRANSACTION_DIR" ]] || continue
    status="$(cat "$transaction/status" 2>/dev/null || true)"
    if [[ ! -e "$transaction/mutation-armed" ]]; then
      rm -rf "$transaction"
      log "removed a non-mutating stale transaction"
      continue
    fi
    source_sha="$(cat "$transaction/source-git-sha")"
    target_sha="$(cat "$transaction/target-git-sha")"
    case "$status" in
      mutating|prepared|committed|"")
        if [[ "$SOURCE_GIT_SHA" == "$target_sha" ]] &&
          cmp -s "$transaction/target-release.env" "$TRANSACTION_DIR/source-release.env"; then
          current_git_sha="$(run_git -C "$APP_ROOT" rev-parse HEAD)"
          write_current_manifest "$reconcile_manifest"
          if [[ "$current_git_sha" != "$target_sha" ]] ||
            ! cmp -s "$reconcile_manifest" "$transaction/target-release.env"; then
            die "committed stale transaction runtime drifted; rollback evidence was preserved"
          fi
          verify_all_running_images "$transaction/target-release.env"
          rm -f "$reconcile_manifest"
          rm -rf "$transaction"
          log "removed an already committed stale transaction"
        elif [[ "$SOURCE_GIT_SHA" == "$source_sha" ]] &&
          cmp -s "$transaction/source-release.env" "$TRANSACTION_DIR/source-release.env"; then
          log "rolling back an interrupted stale transaction"
          restore_transaction "$transaction"
        else
          die "stale transaction does not match the current Launch Template release"
        fi
        ;;
      *)
        die "stale transaction has an unsupported status: $status"
        ;;
    esac
  done
  shopt -u nullglob
}

rollback_on_prepare_error() {
  local status=$?
  trap - ERR
  if [[ -n "$TRANSACTION_DIR" && -d "$TRANSACTION_DIR" ]]; then
    if [[ "$PREPARE_MUTATED" == "true" || -e "$TRANSACTION_DIR/mutation-armed" ]]; then
      log "prepare failed; restoring the previous runtime"
      restore_transaction "$TRANSACTION_DIR" ||
        log "automatic rollback failed; transaction evidence was preserved"
    else
      rm -rf "$TRANSACTION_DIR"
    fi
  fi
  exit "$status"
}

rollback_on_prepare_signal() {
  local signal_name="$1"
  local status=130
  [[ "$signal_name" != "TERM" ]] || status=143
  trap - ERR INT TERM
  if [[ -n "$TRANSACTION_DIR" && -d "$TRANSACTION_DIR" ]]; then
    if [[ "$PREPARE_MUTATED" == "true" || -e "$TRANSACTION_DIR/mutation-armed" ]]; then
      log "prepare interrupted; restoring the previous runtime"
      restore_transaction "$TRANSACTION_DIR" ||
        log "automatic rollback failed; transaction evidence was preserved"
    else
      rm -rf "$TRANSACTION_DIR"
    fi
  fi
  exit "$status"
}

for command_name in aws base64 basename cat chown chmod cmp cp curl date dirname docker env flock git grep install mkdir mktemp mv rm runuser sed; do
  require_command "$command_name"
done

[[ "$#" -ge 1 ]] || {
  usage
  exit 2
}
ACTION="$1"
shift

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --deployment-id)
      DEPLOYMENT_ID="${2:-}"
      shift 2
      ;;
    --service)
      SERVICE="${2:-}"
      shift 2
      ;;
    --source-git-sha)
      SOURCE_GIT_SHA="${2:-}"
      shift 2
      ;;
    --target-git-sha)
      TARGET_GIT_SHA="${2:-}"
      shift 2
      ;;
    --source-manifest-b64)
      SOURCE_MANIFEST_B64="${2:-}"
      shift 2
      ;;
    --target-manifest-b64)
      TARGET_MANIFEST_B64="${2:-}"
      shift 2
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

[[ "$DEPLOYMENT_ID" =~ ^[A-Za-z0-9._-]{8,128}$ ]] ||
  die "deployment ID is invalid"
mkdir -p "$TRANSACTION_ROOT" "$(dirname "$LOCK_FILE")"
chmod 700 "$TRANSACTION_ROOT"
exec 9>"$LOCK_FILE"
flock -x 9
TRANSACTION_DIR="$TRANSACTION_ROOT/$DEPLOYMENT_ID"
CANCELLATION_FENCE="$TRANSACTION_ROOT/.cancelled-$DEPLOYMENT_ID"

case "$ACTION" in
  commit)
    [[ ! -e "$CANCELLATION_FENCE" ]] ||
      die "deployment was cancelled and cannot be committed"
    if [[ ! -d "$TRANSACTION_DIR" ]]; then
      log "transaction is already finalized: deployment_id=$DEPLOYMENT_ID"
      exit 0
    fi
    [[ "$(cat "$TRANSACTION_DIR/status" 2>/dev/null || true)" == "prepared" ||
      "$(cat "$TRANSACTION_DIR/status" 2>/dev/null || true)" == "committed" ]] ||
      die "deployment transaction is not prepared"
    write_transaction_status "$TRANSACTION_DIR" committed
    log "transaction committed: deployment_id=$DEPLOYMENT_ID"
    exit 0
    ;;
  cleanup)
    [[ ! -e "$CANCELLATION_FENCE" ]] ||
      die "cancelled deployment evidence cannot be cleaned as committed"
    if [[ ! -d "$TRANSACTION_DIR" ]]; then
      log "transaction cleanup is already complete: deployment_id=$DEPLOYMENT_ID"
      exit 0
    fi
    [[ "$(cat "$TRANSACTION_DIR/status" 2>/dev/null || true)" == "committed" ]] ||
      die "only a committed transaction can be cleaned"
    rm -rf "$TRANSACTION_DIR"
    log "transaction cleanup completed: deployment_id=$DEPLOYMENT_ID"
    exit 0
    ;;
  rollback)
    write_cancellation_fence
    if [[ ! -d "$TRANSACTION_DIR" ]]; then
      log "rollback fence recorded before prepare: deployment_id=$DEPLOYMENT_ID"
      exit 0
    fi
    if [[ -e "$TRANSACTION_DIR/mutation-armed" ]]; then
      restore_transaction "$TRANSACTION_DIR"
    else
      rm -rf "$TRANSACTION_DIR"
      log "removed a non-mutating transaction: deployment_id=$DEPLOYMENT_ID"
    fi
    exit 0
    ;;
  prepare)
    ;;
  *)
    usage
    exit 2
    ;;
esac

[[ "$SERVICE" == "api" || "$SERVICE" == "xgb-reranker" ]] ||
  die "service must be api or xgb-reranker"
[[ ! -e "$CANCELLATION_FENCE" ]] ||
  die "deployment ID has a cancellation fence"
[[ "$SOURCE_GIT_SHA" =~ ^[0-9a-f]{40}$ ]] || die "source Git SHA is invalid"
[[ "$TARGET_GIT_SHA" =~ ^[0-9a-f]{40}$ ]] || die "target Git SHA is invalid"
[[ -n "$SOURCE_MANIFEST_B64" && -n "$TARGET_MANIFEST_B64" ]] ||
  die "source and target manifests are required"
[[ -d "$APP_ROOT/.git" ]] || die "application repository is missing"
[[ -s "$APP_ROOT/.env.prod" ]] || die "runtime Secret environment is missing"

[[ ! -e "$TRANSACTION_DIR" ]] ||
  die "deployment transaction ID is already in use"
trap rollback_on_prepare_error ERR
trap 'rollback_on_prepare_signal INT' INT
trap 'rollback_on_prepare_signal TERM' TERM
mkdir "$TRANSACTION_DIR"
chmod 700 "$TRANSACTION_DIR"
write_transaction_status "$TRANSACTION_DIR" validating
decode_manifest "$SOURCE_MANIFEST_B64" "$TRANSACTION_DIR/source-release.env"
decode_manifest "$TARGET_MANIFEST_B64" "$TRANSACTION_DIR/target-release.env"
reconcile_stale_transactions

readonly SOURCE_API_IMAGE="$(read_manifest_value API_IMAGE_URI "$TRANSACTION_DIR/source-release.env")"
readonly SOURCE_XGB_IMAGE="$(read_manifest_value XGB_IMAGE_URI "$TRANSACTION_DIR/source-release.env")"
readonly SOURCE_NGINX_IMAGE="$(read_manifest_value NGINX_IMAGE_URI "$TRANSACTION_DIR/source-release.env")"
readonly TARGET_API_IMAGE="$(read_manifest_value API_IMAGE_URI "$TRANSACTION_DIR/target-release.env")"
readonly TARGET_XGB_IMAGE="$(read_manifest_value XGB_IMAGE_URI "$TRANSACTION_DIR/target-release.env")"
readonly TARGET_NGINX_IMAGE="$(read_manifest_value NGINX_IMAGE_URI "$TRANSACTION_DIR/target-release.env")"

[[ "$SOURCE_NGINX_IMAGE" == "$TARGET_NGINX_IMAGE" ]] ||
  die "Fast Deploy cannot change the Nginx image"
if [[ "$SERVICE" == "api" ]]; then
  [[ "$SOURCE_XGB_IMAGE" == "$TARGET_XGB_IMAGE" ]] ||
    die "Fast API Deploy cannot change the XGB image"
else
  [[ "$SOURCE_API_IMAGE" == "$TARGET_API_IMAGE" ]] ||
    die "Fast XGB Deploy cannot change the API image"
fi

readonly CURRENT_MANIFEST="$TRANSACTION_DIR/current-release.env"
write_current_manifest "$CURRENT_MANIFEST"
cmp -s "$CURRENT_MANIFEST" "$TRANSACTION_DIR/source-release.env" ||
  die "runtime manifest drifted from the Launch Template release"
readonly CURRENT_GIT_SHA="$(run_git -C "$APP_ROOT" rev-parse HEAD)"
[[ "$CURRENT_GIT_SHA" == "$SOURCE_GIT_SHA" ]] ||
  die "runtime Git SHA drifted from the Launch Template release"
run_git -C "$APP_ROOT" diff --quiet ||
  die "runtime Git working tree has unstaged tracked changes"
run_git -C "$APP_ROOT" diff --cached --quiet ||
  die "runtime Git working tree has staged changes"
verify_all_running_images "$TRANSACTION_DIR/source-release.env"

run_git -C "$APP_ROOT" fetch --no-tags origin \
  "+refs/heads/main:refs/remotes/origin/main"
run_git -C "$APP_ROOT" cat-file -e "${TARGET_GIT_SHA}^{commit}"
run_git -C "$APP_ROOT" merge-base --is-ancestor "$TARGET_GIT_SHA" origin/main

cp "$IMAGE_MANIFEST" "$TRANSACTION_DIR/green-images.env"
cp "$ASG_RUNTIME_ENV" "$TRANSACTION_DIR/asg-runtime.env"
printf '%s\n' "$SERVICE" >"$TRANSACTION_DIR/service"
printf '%s\n' "$SOURCE_GIT_SHA" >"$TRANSACTION_DIR/source-git-sha"
printf '%s\n' "$TARGET_GIT_SHA" >"$TRANSACTION_DIR/target-git-sha"
if [[ -s "$SUCCESS_MARKER" ]]; then
  cp "$SUCCESS_MARKER" "$TRANSACTION_DIR/asg-bootstrap-success"
  printf '%s\n' true >"$TRANSACTION_DIR/previous-marker-exists"
else
  printf '%s\n' false >"$TRANSACTION_DIR/previous-marker-exists"
fi
snapshot_container_ids "$TRANSACTION_DIR"
write_runtime_files \
  "$TRANSACTION_DIR/target-release.env" \
  "$TRANSACTION_DIR/green-images.candidate.env" \
  "$TRANSACTION_DIR/asg-runtime.candidate.env"

compose_with \
  "$TRANSACTION_DIR/green-images.candidate.env" \
  "$TRANSACTION_DIR/asg-runtime.candidate.env" \
  config --quiet
aws ecr get-login-password --region "$APPROVED_AWS_REGION" --no-cli-pager |
  docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null
compose_with \
  "$TRANSACTION_DIR/green-images.candidate.env" \
  "$TRANSACTION_DIR/asg-runtime.candidate.env" \
  pull $(target_deploy_services "$SERVICE")

[[ ! -e "$CANCELLATION_FENCE" ]] ||
  die "deployment was cancelled before runtime mutation"
: >"$TRANSACTION_DIR/mutation-armed"
write_transaction_status "$TRANSACTION_DIR" mutating
PREPARE_MUTATED=true
run_git -C "$APP_ROOT" checkout --detach "$TARGET_GIT_SHA" >/dev/null
[[ "$(run_git -C "$APP_ROOT" rev-parse HEAD)" == "$TARGET_GIT_SHA" ]] ||
  die "target Git checkout did not reach the approved SHA"
install_runtime_file "$TRANSACTION_DIR/green-images.candidate.env" "$IMAGE_MANIFEST"
install_runtime_file "$TRANSACTION_DIR/asg-runtime.candidate.env" "$ASG_RUNTIME_ENV"
install -d -m 0700 "$RELEASE_ROOT"
install_runtime_file \
  "$TRANSACTION_DIR/target-release.env" \
  "$RELEASE_ROOT/green-release-${TARGET_GIT_SHA}.env"
compose_with "$IMAGE_MANIFEST" "$ASG_RUNTIME_ENV" \
  up -d --no-deps --force-recreate --no-build \
  $(target_deploy_services "$SERVICE")

if [[ "$SERVICE" == "api" ]]; then
  compose_with "$IMAGE_MANIFEST" "$ASG_RUNTIME_ENV" exec -T nginx nginx -t
  compose_with "$IMAGE_MANIFEST" "$ASG_RUNTIME_ENV" exec -T nginx nginx -s reload
fi
wait_for_service "$SERVICE" || die "service health did not recover"
verify_all_running_images "$TRANSACTION_DIR/target-release.env"
verify_non_target_containers_unchanged "$TRANSACTION_DIR" "$SERVICE"
write_success_marker "$TARGET_GIT_SHA" "$TRANSACTION_DIR/target-release.env"
write_transaction_status "$TRANSACTION_DIR" prepared
PREPARE_MUTATED=false
trap - ERR INT TERM

log "prepared successfully: service=$SERVICE deployment_id=$DEPLOYMENT_ID"
