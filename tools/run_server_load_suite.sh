#!/usr/bin/env bash
# =============================================================================
# BuildGraph 전체 서버 부하 스위트 러너 — Linux injector(EC2)용.
# tools/run_server_load_suite.ps1과 같은 산출물 구조를 만든다:
#   infra/k6/reports/runs/{runId}/manifest.json + 프로필별 summary/console log
#
# 필수: BASE_URL — 기본값이 없다. Linux에서는 host.docker.internal이 해석되지
# 않으므로 CloudFront 또는 origin 주소를 명시해야 한다. 미설정 시 즉시 실패.
#
# 사용 예 (injector EC2에서):
#   BASE_URL=https://d1a7gxvxxd385i.cloudfront.net \
#   USER_EMAIL=loadtest@example.com USER_PASSWORD='...' \
#   PROFILES="smoke load" ./tools/run_server_load_suite.sh
#
# 주요 env (기본값):
#   PROFILES="smoke load stress spike soak breakpoint"  실행할 프로필 목록(공백 구분)
#   SLO_PROFILE=aws        AWS 실측 기본. 로컬 재현이면 local
#   LOGIN_RATIO=0.005      AWS 권장 저율 로그인. 로컬 재현이면 0.05
#   SOAK_DURATION=1h SOAK_RATE=30 SOAK_WINDOW_MINUTES=5
#   COOLDOWN_SECONDS=300   프로필 사이 냉각 시간(0이면 생략)
#   K6_IMAGE=grafana/k6:0.54.0
#   RUN_ID                 미지정 시 {KST시각}KST-{git short commit}
# 선택 passthrough: SHORT, BREAKPOINT_LEVELS/RAMP/HOLD/MAX_RATE,
#   STARTUP_JITTER_SECONDS, THINK_TIME_SECONDS
# =============================================================================
set -u -o pipefail

if [[ -z "${BASE_URL:-}" ]]; then
  echo "error: BASE_URL 환경변수가 필요하다 (예: BASE_URL=https://<cloudfront>.cloudfront.net $0)" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

PROFILES="${PROFILES:-smoke load stress spike soak breakpoint}"
USER_EMAIL="${USER_EMAIL:-user@example.com}"
USER_PASSWORD="${USER_PASSWORD:-passw0rd!}"
SOAK_DURATION="${SOAK_DURATION:-1h}"
SOAK_RATE="${SOAK_RATE:-30}"
SOAK_WINDOW_MINUTES="${SOAK_WINDOW_MINUTES:-5}"
LOGIN_RATIO="${LOGIN_RATIO:-0.005}"
SLO_PROFILE="${SLO_PROFILE:-aws}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.54.0}"
COOLDOWN_SECONDS="${COOLDOWN_SECONDS:-300}"

# "1h", "90m", "1h30m", "300s" 형식을 분으로 환산한다(초는 올림).
duration_to_minutes() {
  local rest="$1" total=0 value unit
  while [[ "$rest" =~ ^([0-9]+)(h|m|s)(.*)$ ]]; do
    value="${BASH_REMATCH[1]}"
    unit="${BASH_REMATCH[2]}"
    rest="${BASH_REMATCH[3]}"
    case "$unit" in
      h) total=$((total + value * 60)) ;;
      m) total=$((total + value)) ;;
      s) total=$((total + (value + 59) / 60)) ;;
    esac
  done
  if [[ -n "$rest" || "$total" -eq 0 ]]; then
    echo "error: SOAK_DURATION 형식을 해석할 수 없다: $1 (예: 1h, 90m, 1h30m)" >&2
    return 1
  fi
  echo "$total"
}

soak_minutes="$(duration_to_minutes "$SOAK_DURATION")" || exit 1
# Soak 구간 수 = ceil(지속시간 분 / 구간 분). 명시 override 가능.
SOAK_WINDOW_COUNT="${SOAK_WINDOW_COUNT:-$(( (soak_minutes + SOAK_WINDOW_MINUTES - 1) / SOAK_WINDOW_MINUTES ))}"

SHORT_COMMIT="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo nogit)"
COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
BRANCH="$(git -C "$REPO_ROOT" branch --show-current 2>/dev/null || echo unknown)"
RUN_ID="${RUN_ID:-$(TZ=Asia/Seoul date +%Y%m%dT%H%M%S)KST-$SHORT_COMMIT}"
RUN_DIR="$REPO_ROOT/infra/k6/reports/runs/$RUN_ID"
mkdir -p "$RUN_DIR"

STARTED_AT="$(date -Iseconds)"
RESULTS=()

profiles_json() {
  local out="" p
  for p in $PROFILES; do
    out+="\"$p\","
  done
  echo "${out%,}"
}

join_results() {
  local IFS=','
  echo "${RESULTS[*]:-}"
}

write_manifest() {
  local ended_at="${1:-}"
  {
    printf '{\n'
    printf '  "runId": "%s",\n' "$RUN_ID"
    printf '  "startedAt": "%s",\n' "$STARTED_AT"
    if [[ -z "$ended_at" ]]; then
      printf '  "endedAt": null,\n'
    else
      printf '  "endedAt": "%s",\n' "$ended_at"
    fi
    printf '  "commit": "%s",\n' "$COMMIT"
    printf '  "branch": "%s",\n' "$BRANCH"
    printf '  "baseUrl": "%s",\n' "$BASE_URL"
    printf '  "k6Image": "%s",\n' "$K6_IMAGE"
    printf '  "profiles": [%s],\n' "$(profiles_json)"
    printf '  "soakDuration": "%s",\n' "$SOAK_DURATION"
    printf '  "soakRate": %s,\n' "$SOAK_RATE"
    printf '  "soakWindowMinutes": %s,\n' "$SOAK_WINDOW_MINUTES"
    printf '  "soakWindowCount": %s,\n' "$SOAK_WINDOW_COUNT"
    printf '  "loginRatio": %s,\n' "$LOGIN_RATIO"
    printf '  "sloProfile": "%s",\n' "$SLO_PROFILE"
    printf '  "cooldownSeconds": %s,\n' "$COOLDOWN_SECONDS"
    printf '  "short": "%s",\n' "${SHORT:-}"
    printf '  "breakpointLevels": "%s",\n' "${BREAKPOINT_LEVELS:-50,100,200,300,400}"
    printf '  "host": "%s",\n' "$(hostname)"
    printf '  "results": [%s]\n' "$(join_results)"
    printf '}\n'
  } > "$RUN_DIR/manifest.json"
}

check_health() {
  local body
  if body="$(curl -fsS --max-time 5 "$BASE_URL/api/health" 2>/dev/null)"; then
    if [[ "$body" == *'"status":"UP"'* ]]; then
      echo "UP"
    else
      echo "DEGRADED"
    fi
  else
    echo "DOWN"
  fi
}

# 프로필 이름 검증(전부 확인한 뒤 실행 시작).
for profile in $PROFILES; do
  case "$profile" in
    smoke|load|stress|spike|soak|breakpoint|capacity) ;;
    *)
      echo "error: unknown profile: $profile (smoke|load|stress|spike|soak|breakpoint|capacity)" >&2
      exit 1
      ;;
  esac
done

write_manifest
first=1
for profile in $PROFILES; do
  if [[ "$first" -eq 0 && "$COOLDOWN_SECONDS" -gt 0 ]]; then
    echo "== cooldown ${COOLDOWN_SECONDS}s before $profile =="
    sleep "$COOLDOWN_SECONDS"
  fi
  first=0

  profile_started="$(date -Iseconds)"
  summary_relative="infra/k6/reports/runs/$RUN_ID/server-$profile.json"
  console_log="$RUN_DIR/server-$profile.console.log"
  echo "== running $profile against $BASE_URL =="

  docker_env=(
    -e "TEST_TYPE=$profile"
    -e "BASE_URL=$BASE_URL"
    -e "USER_EMAIL=$USER_EMAIL"
    -e "USER_PASSWORD=$USER_PASSWORD"
    -e "LOGIN_RATIO=$LOGIN_RATIO"
    -e "SLO_PROFILE=$SLO_PROFILE"
    -e "SUMMARY_PATH=/work/$summary_relative"
    -e "SOAK_DURATION=$SOAK_DURATION"
    -e "SOAK_RATE=$SOAK_RATE"
    -e "SOAK_WINDOW_MINUTES=$SOAK_WINDOW_MINUTES"
    -e "SOAK_WINDOW_COUNT=$SOAK_WINDOW_COUNT"
  )
  # 선택 튜닝 env는 호출 셸에 설정된 경우에만 그대로 전달한다.
  for name in SHORT BREAKPOINT_LEVELS BREAKPOINT_RAMP BREAKPOINT_HOLD BREAKPOINT_MAX_RATE STARTUP_JITTER_SECONDS THINK_TIME_SECONDS; do
    if [[ -n "${!name:-}" ]]; then
      docker_env+=(-e "$name=${!name}")
    fi
  done

  # --user: 컨테이너 기본 k6 사용자(uid 12345)는 호스트 소유 reports 디렉터리에
  # summary를 쓰지 못하므로 호출자 uid/gid로 실행한다.
  docker run --rm --user "$(id -u):$(id -g)" "${docker_env[@]}" \
    -v "$REPO_ROOT:/work" -w /work \
    "$K6_IMAGE" run --quiet infra/k6/server-workload.js 2>&1 | tee "$console_log"
  exit_code="${PIPESTATUS[0]}"

  health_after="$(check_health)"
  profile_ended="$(date -Iseconds)"
  echo "== $profile finished: exit=$exit_code healthAfter=$health_after =="

  RESULTS+=("$(printf '{"profile":"%s","startedAt":"%s","endedAt":"%s","exitCode":%s,"healthAfter":"%s","summary":"server-%s.json","consoleLog":"server-%s.console.log"}' \
    "$profile" "$profile_started" "$profile_ended" "$exit_code" "$health_after" "$profile" "$profile")")
  write_manifest
done

write_manifest "$(date -Iseconds)"
echo "$RUN_DIR"
