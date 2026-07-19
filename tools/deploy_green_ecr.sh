#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

readonly AWS_REGION="${AWS_REGION:-ap-northeast-2}"
readonly AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-443915990705}"
readonly ECR_REGISTRY="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
readonly API_REPOSITORY="buildgraph-demo-api-green"
readonly XGB_REPOSITORY="buildgraph-demo-xgb-reranker-green"
readonly APP_ROOT="${BUILDGRAPH_APP_ROOT:-/opt/buildgraph/prototype}"
readonly IMAGE_MANIFEST="${GREEN_IMAGE_MANIFEST:-/opt/buildgraph/green-images.env}"
readonly LOCK_FILE="${GREEN_DEPLOY_LOCK_FILE:-/opt/buildgraph/green-deploy.lock}"
readonly SECRET_ID="${GREEN_API_SECRET_ID:-buildgraph/demo-green/api-env}"
readonly COMPOSE_PROJECT_NAME="buildgraph-green"

usage() {
  echo "usage: $0 <api|xgb-reranker> <40-character-git-sha> <ecr-image-uri>" >&2
}

die() {
  echo "green deployment rejected: $*" >&2
  return 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command is missing: $1"
}

validate_image_uri() {
  local image_uri="$1"
  local repository="$2"
  local prefix="${ECR_REGISTRY}/${repository}:"
  local tag

  [[ "$image_uri" == "$prefix"* ]] || return 1
  tag="${image_uri##*:}"
  [[ "$tag" =~ ^[0-9a-f]{40}$ ]]
}

read_manifest_value() {
  local key="$1"
  local file="$2"
  local count value

  count="$(grep -c "^${key}=" "$file" || true)"
  [[ "$count" -eq 1 ]] || {
    die "manifest must contain exactly one ${key}"
    return 1
  }

  value="$(sed -n "s/^${key}=//p" "$file")"
  [[ -n "$value" ]] || {
    die "manifest value is empty: ${key}"
    return 1
  }

  printf '%s' "$value"
}

compose_with() {
  local runtime_env="$1"
  local image_env="$2"
  shift 2

  env -u API_IMAGE_URI -u XGB_IMAGE_URI \
    docker compose \
      -p "$COMPOSE_PROJECT_NAME" \
      -f "$APP_ROOT/compose.api.ecr.prod.yaml" \
      --env-file "$runtime_env" \
      --env-file "$image_env" \
      "$@"
}

target_deploy_services() {
  local service="$1"
  if [[ "$service" == "api" ]]; then
    printf '%s\n' api recommendation-event-worker
  else
    printf '%s\n' "$service"
  fi
}

verify_running_image() {
  local runtime_env="$1"
  local image_env="$2"
  local service="$3"
  local expected_image="$4"
  local container_id running_image

  container_id="$(compose_with "$runtime_env" "$image_env" ps -q "$service")"
  [[ -n "$container_id" ]] || die "container is missing after deployment: $service"

  running_image="$(docker inspect --format '{{.Config.Image}}' "$container_id")"
  [[ "$running_image" == "$expected_image" ]] ||
    die "running image mismatch: expected=$expected_image actual=$running_image"
}

wait_for_api() {
  local attempt
  for attempt in $(seq 1 60); do
    if curl -fsS http://127.0.0.1/api/health >/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_for_xgb() {
  local runtime_env="$1"
  local image_env="$2"
  local attempt container_id health

  for attempt in $(seq 1 60); do
    container_id="$(compose_with "$runtime_env" "$image_env" ps -q xgb-reranker)"
    if [[ -n "$container_id" ]]; then
      health="$(docker inspect --format '{{.State.Health.Status}}' "$container_id" 2>/dev/null || true)"
      if [[ "$health" == "healthy" ]]; then
        return 0
      fi
    fi
    sleep 2
  done
  return 1
}

[[ "$#" -eq 3 ]] || {
  usage
  exit 2
}

readonly TARGET_SERVICE="$1"
readonly TARGET_SHA="$2"
readonly TARGET_IMAGE_URI="$3"

[[ "$TARGET_SHA" =~ ^[0-9a-f]{40}$ ]] || die "Git SHA must be 40 lowercase hexadecimal characters"

case "$TARGET_SERVICE" in
  api)
    readonly TARGET_REPOSITORY="$API_REPOSITORY"
    ;;
  xgb-reranker)
    readonly TARGET_REPOSITORY="$XGB_REPOSITORY"
    ;;
  *)
    die "service must be api or xgb-reranker"
    ;;
esac

validate_image_uri "$TARGET_IMAGE_URI" "$TARGET_REPOSITORY" ||
  die "image URI must target the approved repository with the requested Git SHA"
[[ "${TARGET_IMAGE_URI##*:}" == "$TARGET_SHA" ]] ||
  die "image tag and requested Git SHA must match"

for command_name in aws curl docker flock git; do
  require_command "$command_name"
done

[[ -d "$APP_ROOT/.git" ]] || die "application repository is missing: $APP_ROOT"
[[ -s "$APP_ROOT/.env.prod" ]] || die "active runtime environment is missing"
[[ -s "$IMAGE_MANIFEST" ]] || die "active image manifest is missing"

mkdir -p "$(dirname "$LOCK_FILE")"
exec 9>"$LOCK_FILE"
flock -x 9

cd "$APP_ROOT"

readonly PREVIOUS_GIT_SHA="$(git rev-parse HEAD)"
readonly TEMP_DIR="$(mktemp -d /opt/buildgraph/.green-deploy.XXXXXX)"
readonly PREVIOUS_MANIFEST="$TEMP_DIR/green-images.previous.env"
readonly CANDIDATE_MANIFEST="$TEMP_DIR/green-images.candidate.env"
readonly CANDIDATE_RUNTIME_ENV="$TEMP_DIR/api-env.candidate"
deployment_mutated=0

cleanup() {
  rm -rf "$TEMP_DIR"
}

rollback() {
  local status=$?
  local rollback_failed=0
  trap - ERR
  set +e

  if [[ "$deployment_mutated" -eq 1 ]]; then
    echo "deployment failed; starting rollback for $TARGET_SERVICE" >&2

    git checkout --detach "$PREVIOUS_GIT_SHA" >/dev/null 2>&1 || rollback_failed=1
    cp "$PREVIOUS_MANIFEST" "$IMAGE_MANIFEST" || rollback_failed=1
    chmod 600 "$IMAGE_MANIFEST" || rollback_failed=1

    aws ecr get-login-password --region "$AWS_REGION" |
      docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null || rollback_failed=1

    compose_with "$APP_ROOT/.env.prod" "$IMAGE_MANIFEST" config --quiet || rollback_failed=1
    compose_with "$APP_ROOT/.env.prod" "$IMAGE_MANIFEST" \
      pull $(target_deploy_services "$TARGET_SERVICE") || rollback_failed=1
    compose_with "$APP_ROOT/.env.prod" "$IMAGE_MANIFEST" \
      up -d --no-deps --force-recreate --no-build \
      $(target_deploy_services "$TARGET_SERVICE") || rollback_failed=1

    if [[ "$TARGET_SERVICE" == "api" ]]; then
      compose_with "$APP_ROOT/.env.prod" "$IMAGE_MANIFEST" exec -T nginx nginx -t || rollback_failed=1
      compose_with "$APP_ROOT/.env.prod" "$IMAGE_MANIFEST" exec -T nginx nginx -s reload || rollback_failed=1
      wait_for_api || rollback_failed=1
    else
      wait_for_xgb "$APP_ROOT/.env.prod" "$IMAGE_MANIFEST" || rollback_failed=1
    fi

    if [[ "$rollback_failed" -eq 0 ]]; then
      echo "rollback completed for $TARGET_SERVICE" >&2
    else
      echo "rollback encountered an error; manual recovery is required" >&2
    fi
  fi

  exit "$status"
}

trap cleanup EXIT
trap rollback ERR

cp "$IMAGE_MANIFEST" "$PREVIOUS_MANIFEST"
chmod 600 "$PREVIOUS_MANIFEST"

active_api_image="$(read_manifest_value API_IMAGE_URI "$IMAGE_MANIFEST")"
active_xgb_image="$(read_manifest_value XGB_IMAGE_URI "$IMAGE_MANIFEST")"

validate_image_uri "$active_api_image" "$API_REPOSITORY" ||
  die "active API image URI is invalid"
validate_image_uri "$active_xgb_image" "$XGB_REPOSITORY" ||
  die "active XGB image URI is invalid"

candidate_api_image="$active_api_image"
candidate_xgb_image="$active_xgb_image"
if [[ "$TARGET_SERVICE" == "api" ]]; then
  candidate_api_image="$TARGET_IMAGE_URI"
else
  candidate_xgb_image="$TARGET_IMAGE_URI"
fi

printf '%s\n' "API_IMAGE_URI=$candidate_api_image" "XGB_IMAGE_URI=$candidate_xgb_image" >"$CANDIDATE_MANIFEST"
chmod 600 "$CANDIDATE_MANIFEST"

git fetch --quiet origin main
git cat-file -e "${TARGET_SHA}^{commit}"
git merge-base --is-ancestor "$TARGET_SHA" origin/main ||
  die "target Git SHA is not reachable from origin/main"

aws secretsmanager get-secret-value --secret-id "$SECRET_ID" --region "$AWS_REGION" --query SecretString --output text --no-cli-pager >"$CANDIDATE_RUNTIME_ENV"
[[ -s "$CANDIDATE_RUNTIME_ENV" ]] || die "Secrets Manager returned an empty runtime environment"
chmod 600 "$CANDIDATE_RUNTIME_ENV"

compose_with "$CANDIDATE_RUNTIME_ENV" "$CANDIDATE_MANIFEST" config --quiet

aws ecr get-login-password --region "$AWS_REGION" |
  docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null
compose_with "$CANDIDATE_RUNTIME_ENV" "$CANDIDATE_MANIFEST" \
  pull $(target_deploy_services "$TARGET_SERVICE")

deployment_mutated=1
git checkout --detach "$TARGET_SHA"
compose_with "$CANDIDATE_RUNTIME_ENV" "$CANDIDATE_MANIFEST" \
  up -d --no-deps --force-recreate --no-build \
  $(target_deploy_services "$TARGET_SERVICE")
verify_running_image "$CANDIDATE_RUNTIME_ENV" "$CANDIDATE_MANIFEST" "$TARGET_SERVICE" "$TARGET_IMAGE_URI"
if [[ "$TARGET_SERVICE" == "api" ]]; then
  verify_running_image "$CANDIDATE_RUNTIME_ENV" "$CANDIDATE_MANIFEST" \
    recommendation-event-worker "$TARGET_IMAGE_URI"
fi

if [[ "$TARGET_SERVICE" == "api" ]]; then
  compose_with "$CANDIDATE_RUNTIME_ENV" "$CANDIDATE_MANIFEST" exec -T nginx nginx -t
  compose_with "$CANDIDATE_RUNTIME_ENV" "$CANDIDATE_MANIFEST" exec -T nginx nginx -s reload
  wait_for_api || die "API health did not recover within 120 seconds"
else
  wait_for_xgb "$CANDIDATE_RUNTIME_ENV" "$CANDIDATE_MANIFEST" ||
    die "XGB container did not become healthy within 120 seconds"
fi

chmod 600 "$CANDIDATE_MANIFEST" "$CANDIDATE_RUNTIME_ENV"
mv "$CANDIDATE_MANIFEST" "$IMAGE_MANIFEST"
mv "$CANDIDATE_RUNTIME_ENV" "$APP_ROOT/.env.prod"
trap - ERR

echo "green deployment succeeded: service=$TARGET_SERVICE sha=$TARGET_SHA"
