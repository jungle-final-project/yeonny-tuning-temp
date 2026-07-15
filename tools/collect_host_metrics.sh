#!/usr/bin/env bash
# 부하테스트 중 EC2 호스트/컨테이너/JVM 지표를 1분 간격으로 수집한다.
# k6 결과(p95 상승)와 같은 시각의 서버 내부 상태(Hikari pending·GC·스레드·CPU·재시작)를
# 대조할 수 있어야 "어느 계층이 먼저 무너졌는지"를 판정할 수 있다(preflight §4/§7).
#
# 사용(테스트 EC2에서, 테스트 시작 전에 백그라운드로):
#   RUN_ID=aws-load-0715 nohup ./tools/collect_host_metrics.sh > /dev/null 2>&1 &
#   ... 테스트 종료 후: kill %1  (산출물: metrics-<RUN_ID>.ndjson)
# 환경변수:
#   RUN_ID       실행 식별자(기본 타임스탬프) — k6 run-id와 맞춘다
#   INTERVAL     샘플 간격 초(기본 60)
#   OUT_DIR      산출 디렉터리(기본 ./loadtest-metrics)
#   API_NAME     api 컨테이너 이름 매칭 패턴(기본 'api')
#   NGINX_NAME   nginx/web 컨테이너 이름 매칭 패턴(기본 'nginx|web')
set -u

RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
INTERVAL="${INTERVAL:-60}"
OUT_DIR="${OUT_DIR:-./loadtest-metrics}"
API_NAME="${API_NAME:-api}"
NGINX_NAME="${NGINX_NAME:-nginx|web}"
OUT_FILE="${OUT_DIR}/metrics-${RUN_ID}.ndjson"

mkdir -p "${OUT_DIR}"
echo "[collect_host_metrics] run=${RUN_ID} interval=${INTERVAL}s out=${OUT_FILE}" >&2

# 한 줄 JSON을 안전하게 기록(값 내 따옴표는 python으로 이스케이프 — jq 미설치 EC2 대비).
emit() { # emit <section> <raw-text>
  local section="$1"; shift
  python3 - "$section" "$OUT_FILE" <<'PY' "$@"
import json, sys, datetime
section, out = sys.argv[1], sys.argv[2]
raw = sys.argv[3] if len(sys.argv) > 3 else ""
line = json.dumps({
    "ts": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    "section": section,
    "data": raw,
}, ensure_ascii=False)
with open(out, "a", encoding="utf-8") as fh:
    fh.write(line + "\n")
PY
}

api_container() { docker ps --format '{{.Names}}' | grep -E "${API_NAME}" | head -1; }
nginx_container() { docker ps --format '{{.Names}}' | grep -E "${NGINX_NAME}" | head -1; }

# api 내부 actuator 미터 수집 — 포트 미공개(도커 네트워크 내부)라 exec로 컨테이너 안에서 조회한다.
# eclipse-temurin JRE 이미지에 curl이 없어 java의 URL 스트림 대신 wget(busybox)도 없을 수 있으니
# /dev/tcp 셸 HTTP로 최소 요청을 보낸다(응답 본문만).
actuator_meter() { # actuator_meter <container> <meter-path>
  docker exec "$1" sh -c "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/metrics/%s HTTP/1.0\r\nHost: localhost\r\n\r\n' '$2' >&3 && cat <&3 | tr -d '\r' | sed '1,/^$/d'" 2>/dev/null
}

while true; do
  # 1) 컨테이너 자원(CPU/메모리/네트워크/PIDs)
  emit docker_stats "$(docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}} net={{.NetIO}} pids={{.PIDs}}' 2>&1)"
  # 2) 호스트 메모리/디스크/로드
  emit host "$(free -m 2>/dev/null | sed -n '1,3p'; df -h / 2>/dev/null | tail -1; uptime 2>/dev/null)"
  # 3) 컨테이너 재시작/OOM (breakpoint 판정에 필수 — k6만 보면 재시작을 놓친다)
  emit restarts "$(docker ps -a --format '{{.Names}} {{.Status}}' | grep -iE 'restart|exited' ; docker inspect $(docker ps -q) --format '{{.Name}} restarts={{.RestartCount}} oom={{.State.OOMKilled}}' 2>/dev/null)"
  # 4) JVM/Hikari/Tomcat 핵심 미터
  API_C="$(api_container)"
  if [ -n "${API_C}" ]; then
    for meter in hikaricp.connections.active hikaricp.connections.pending jvm.memory.used jvm.gc.pause tomcat.threads.busy system.cpu.usage; do
      emit "actuator:${meter}" "$(actuator_meter "${API_C}" "${meter}")"
    done
  fi
  # 5) nginx 라이브 커넥션(stub_status)
  NGINX_C="$(nginx_container)"
  if [ -n "${NGINX_C}" ]; then
    emit nginx_status "$(docker exec "${NGINX_C}" sh -c 'wget -qO- http://127.0.0.1/nginx_status 2>/dev/null || curl -s http://127.0.0.1/nginx_status' 2>/dev/null)"
  fi
  sleep "${INTERVAL}"
done
