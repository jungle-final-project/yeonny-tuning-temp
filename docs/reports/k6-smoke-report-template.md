# k6 Smoke Report Template

이 문서는 k6 smoke 실행 결과를 PR에 일관되게 남기기 위한 템플릿이다.

이 템플릿은 대규모 부하 테스트 결과가 아니다. smoke는 `infra/k6/server-workload.js`의 `TEST_TYPE=smoke`(인증 포함 전체 믹스, 2 rps 60초) 기준으로 API 연결, 주요 endpoint 응답, 기본 threshold 통과 여부만 기록한다. 구 `infra/k6/smoke.js`는 비인증 `/api/health`만 남긴 deprecated shim이므로 이 리포트에 사용하지 않는다. 6종 테스트 전체는 `docs/SERVER_LOAD_TEST_GUIDE.md`를 본다.

## Report Metadata

| 항목 | 값 |
| --- | --- |
| 테스트 일시 | `<YYYY-MM-DD HH:mm KST>` |
| 실행자 | `<name>` |
| PR/브랜치 | `<PR number or branch>` |
| commit | `<git rev-parse --short HEAD>` |
| 실행 환경 | `<local Docker Compose / CI / injector EC2 / other>` |
| BASE_URL | `<http://host.docker.internal:18082 또는 CloudFront/origin URL>` |
| k6 script | `infra/k6/server-workload.js` (`TEST_TYPE=smoke`) |
| SLO_PROFILE | `<local / aws>` |

## Runtime Preconditions

| 항목 | 결과 | 근거 |
| --- | --- | --- |
| `docker compose config` | `<PASS/FAIL>` | `<command output summary>` |
| `docker compose up --build` | `<PASS/FAIL/N/A>` | `<service status summary>` |
| `/api/health` | `<PASS/FAIL>` | `<status/database response>` |
| API auth/test seed 상태 | `<PASS/FAIL/N/A>` | `<JWT token or seed note>` |

## k6 Command

```bash
docker run --rm \
  -e TEST_TYPE=smoke -e BASE_URL=<BASE_URL> \
  -v "$PWD:/work" -w /work \
  grafana/k6:0.54.0 run infra/k6/server-workload.js
```

Windows PowerShell:

```powershell
docker run --rm `
  -e TEST_TYPE=smoke -e BASE_URL=http://host.docker.internal:18082 `
  -v "${PWD}:/work" -w /work `
  grafana/k6:0.54.0 run infra/k6/server-workload.js
```

## Scenario

| 항목 | 값 |
| --- | --- |
| scenario | `smoke` |
| executor | `constant-arrival-rate` |
| rate | `2 rps` |
| duration | `60s` |
| preAllocatedVUs / maxVUs | `5 / 10` |
| think time | `0` (arrival-rate 강제) |

## Endpoint Checks

전체 인증 믹스를 재사용하므로 60초 동안 아래 endpoint가 확률적으로 섞여 호출된다. 실제 offered mix는 summary의 `buildgraph.effectiveMix`를 옮겨 적는다.

| endpoint | method | expected | result | note |
| --- | --- | --- | --- | --- |
| `/api/health` | `GET` | `200` | `<PASS/FAIL>` | API와 DB 연결 |
| `/api/auth/login` · `/api/auth/refresh` | `POST` | `200` | `<PASS/FAIL>` | LOGIN_RATIO + 401 복구 경로 |
| `/api/parts` | `GET` | `200` | `<PASS/FAIL>` | 인증 부품 조회 |
| `/api/recommendations/home-parts` | `GET` | `200` | `<PASS/FAIL>` | 홈 추천 |
| `/api/quote-drafts/current` | `GET` | `200` | `<PASS/FAIL>` | 견적초안 |
| `/api/builds/history` | `GET` | `200` | `<PASS/FAIL>` | 견적 이력 |
| `/api/price-alerts` | `GET` | `200` | `<PASS/FAIL>` | 가격 알림 |
| `/api/assembly-requests` | `GET` | `200` | `<PASS/FAIL>` | 조립 요청 이력 |
| `/api/ai/build-chat` | `POST` | `200` | `<PASS/FAIL>` | deterministic AI fast path |

## Threshold Results

| metric | threshold | result | pass |
| --- | --- | --- | --- |
| `http_req_failed` | `rate < 0.01` | `<value>` | `<YES/NO>` |
| `http_req_duration` | `p95 < 1500ms` (local 전역) | `<value>` | `<YES/NO>` |
| `checks` | `rate > 0.99` | `<value>` | `<YES/NO>` |
| `dropped_iterations` | `count == 0` | `<value>` | `<YES/NO>` |

endpoint별 threshold는 `SLO_PROFILE`(local/aws)에 따라 달라진다 — 실패한 항목만 아래 Failure Details에 옮겨 적는다.

## Summary Metrics

| metric | value |
| --- | --- |
| total requests | `<count>` |
| checks passed | `<count or percent>` |
| checks failed | `<count or percent>` |
| avg latency | `<ms>` |
| p95 latency | `<ms>` |
| max latency | `<ms>` |
| error rate | `<rate>` |

## Failure Details

| 실패 항목 | 증상 | 원인 후보 | owner | 후속 조치 |
| --- | --- | --- | --- | --- |
| `<endpoint or threshold>` | `<status/error>` | `<suspected cause>` | `<owner>` | `<next action>` |

## Bottleneck Notes

- `<DB connection / API latency / seed data / Docker resource / endpoint owner issue>`

## Follow-up

| 우선순위 | 작업 | owner | 이슈/PR |
| --- | --- | --- | --- |
| P0 | `<must fix before merge>` | `<owner>` | `<link>` |
| P1 | `<follow-up>` | `<owner>` | `<link>` |

## PR Checklist Snippet

```text
k6 smoke:
  script: infra/k6/server-workload.js (TEST_TYPE=smoke)
  baseUrl: <BASE_URL>
  sloProfile: <local/aws>
  result: <PASS/FAIL>
  http_req_failed: <value> / threshold rate<0.01
  http_req_duration_p95: <value> / threshold p95<1500ms
  dropped_iterations: <value> / threshold count==0
  failed endpoints:
    - <none or endpoint>
  report: docs/reports/<YYYY-MM-DD>-k6-smoke.md
```

## Scope Notes

- 이 리포트는 smoke 결과 기록용이다.
- load/stress/spike/soak/breakpoint는 같은 스크립트의 `TEST_TYPE`으로 실행하며 `docs/SERVER_LOAD_TEST_GUIDE.md`와 스위트 러너(`tools/run_server_load_suite.ps1`, `tools/run_server_load_suite.sh`) 기준으로 기록한다.
- 결과는 DB에 저장하지 않고 k6 리포트 파일로 관리한다.
- endpoint request/response 구조를 바꾸면 담당 owner와 `docs/API_CONTRACT.md`, `docs/openapi.yaml` 변경 여부를 먼저 확인한다.
