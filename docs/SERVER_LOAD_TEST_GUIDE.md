# 전체 서버 부하 테스트 가이드

`infra/k6/server-workload.js` 하나로 6종 테스트를 `TEST_TYPE` 환경변수로 전환해 실행한다. 모든 프로필이 **arrival-rate 실행기**를 사용하므로 결과를 VU 수가 아니라 **RPS(도착률)** 로 설명한다. arrival-rate에서는 pacing이 실행기 책임이므로 think time은 0으로 강제된다 — VU 내부 sleep은 서버가 느려질수록 부하도 줄어드는 자기-교축(coordinated omission)만 만들기 때문이다.

| TEST_TYPE | 목적 | 프로필 |
|---|---|---|
| smoke | 배선 확인: 인증 포함 전체 믹스가 1분간 동작하는지 | constant-arrival-rate 2 rps, 60초, VU 5~10 |
| load | 예상 정상 부하에서 SLO 확인(preflight §5 램프) | 2→5→10→20→40→80→5 rps, 60분. `SHORT=1`이면 20 rps 램프 후 10분 유지 |
| stress | 도착률을 단계적으로 높여 성능 저하 지점 확인 | 20→40→60→80→100 rps, 단계당 4분(1분 램프+3분 유지) |
| spike | 순간 폭증과 회복 확인 | 5 rps 2분 → 100 rps 2분 → 5 rps 3분 |
| soak | 장시간 반복 시 누수·고갈 징후 확인 | `SOAK_RATE`(기본 30) rps 고정, `SOAK_DURATION`(연구 실행 60분+) |
| breakpoint | 포화 지점(knee) 탐색. 구 capacity의 개칭이며 `TEST_TYPE=capacity`는 별칭으로 계속 동작 | 50→100→200→300→400 rps, 단계별 2분 램프+10분 유지, ramp-down 없음 |

breakpoint는 `http_req_failed rate>=1%` 또는 `p95>=2s`가 2분 넘게 지속되면 threshold abort로 스스로 중단한다(k6 exit code 99는 "knee를 찾았다"는 뜻이지 하네스 고장이 아니다). 단계는 `BREAKPOINT_LEVELS`(기본 `50,100,200,300,400`), `BREAKPOINT_RAMP`(기본 `2m`), `BREAKPOINT_HOLD`(기본 `10m`), `BREAKPOINT_MAX_RATE`(상한 컷)로 조정한다.

혼합 요청은 인증, 부품, 홈 추천부품/XGBoost, 견적초안, 견적 이력, 가격 알림, 조립 요청 이력, 읽기 전용 AI 위치 강조를 포함한다. 외부 OpenAI 호출은 대량 부하에서 제외해 BuildGraph API·DB·Redis·scorer의 용량을 측정하며, 실제 LLM 병렬 결과는 별도 Build Chat QA 보고서에서 관리한다.

## 트래픽 믹스와 LOGIN_RATIO

문서 비중(preflight §5)을 그대로 따른다: 로그인 `LOGIN_RATIO` / health 10% / parts 30% / 홈 추천 5% / 견적초안 15% / 견적 이력 10% / 가격 알림 5% / 조립 요청 10% / AI fast 10%. `LOGIN_RATIO`가 0.05가 아니면 나머지 비중이 (1-LOGIN_RATIO)에 비례 배분되어 합이 1로 유지되고, 실제 적용된 믹스는 각 summary JSON의 `buildgraph.effectiveMix`와 stdout `offeredMix=`에 기록된다.

- `LOGIN_RATIO` 기본 0.05(로컬). **AWS 실측에서는 0.005 또는 0을 사용한다.** 반복 로그인은 (1) 호출마다 refresh token row를 생성해 60분 soak 기준 수만 row가 쌓이고, (2) BCrypt cost-10이 CPU를 소모해 spike에서 인증 CPU 벽을 만든다. `LOGIN_RATIO=0`이어도 access token 만료 시 401→`/api/auth/refresh` 경로가 auth 부하를 자연스럽게 발생시킨다.
- VU 최초 로그인에는 `STARTUP_JITTER_SECONDS`(기본 2초) 무작위 지연이 걸려 spike 시작 시 수백 VU의 동시 bcrypt 로그인을 분산한다.

각 VU는 독립 access/refresh token을 유지한다. 보호 API가 `401`을 반환하면 `/api/auth/refresh` 후 원 요청을 한 번만 재시도하며, refresh도 실패할 때만 로그인으로 복구한다. 15분 access token TTL보다 긴 Soak에서 setup token 하나를 계속 재사용하면 서버 내구성이 아니라 클라이언트 인증 만료를 측정하게 되므로 금지한다.

## SLO_PROFILE

`SLO_PROFILE=local`(기본)은 기존 로컬 기준값을 유지한다. **AWS 실측은 `SLO_PROFILE=aws`로 실행한다** — `docs/AWS_LOAD_TEST_PREFLIGHT.md` §6 잠정 합격 기준(p99 포함)이 threshold로 적용된다.

| 요청군 | aws p95 | aws p99 |
|---|---:|---:|
| health | 100 ms | 250 ms |
| 일반 조회(parts/draft/history/alerts/assembly) | 300 ms | 800 ms |
| 인증(login/refresh) | 700 ms | 1,500 ms |
| 홈 추천 | 800 ms | 1,500 ms |
| deterministic AI fast | 500 ms | 1,000 ms |

두 프로필 공통으로 `dropped_iterations count==0` threshold가 있다(preflight §6). dropped가 나오면 injector VU 부족 또는 SUT 지연이므로 RPS 결론을 내리기 전에 원인을 분리한다.

## 단일 프로필 실행

Windows(Docker Desktop):

```powershell
docker run --rm `
  -e TEST_TYPE=smoke `
  -e BASE_URL=http://host.docker.internal:18082 `
  -v "${PWD}:/work" -w /work `
  grafana/k6:0.54.0 run infra/k6/server-workload.js
```

Linux injector(EC2 — `host.docker.internal`이 없으므로 BASE_URL을 반드시 명시):

```bash
docker run --rm --user "$(id -u):$(id -g)" \
  -e TEST_TYPE=smoke -e SLO_PROFILE=aws -e LOGIN_RATIO=0.005 \
  -e BASE_URL=https://<cloudfront-domain> \
  -v "$PWD:/work" -w /work \
  grafana/k6:0.54.0 run infra/k6/server-workload.js
```

`TEST_TYPE`을 `load`, `stress`, `spike`, `soak`, `breakpoint`로 바꿔 실행한다. 5분 Soak는 설정 확인용 short baseline일 뿐 연구용 내구 결론으로 사용하지 않는다. 구 `infra/k6/smoke.js`는 비인증 `/api/health`만 남긴 deprecated shim이다 — 공식 smoke는 `TEST_TYPE=smoke`다.

## 누적 연구 실행 (스위트 러너)

러너는 실행마다 `시각-commit` 형식의 고유 `runId`를 만들고 원시 k6 summary, 콘솔 로그, manifest(Soak는 JVM 자원 표본 포함)를 별도 디렉터리에 누적한다. 이전 실행 파일은 덮어쓰지 않는다. Soak 구간 수는 `ceil(SoakDuration분 / SOAK_WINDOW_MINUTES)`로 자동 계산된다(`1h`/`90m`/`1h30m` 지원).

Windows(로컬 기본값 유지 — BASE_URL 기본 `http://127.0.0.1:18082`, `SLO_PROFILE=local`, `LOGIN_RATIO=0.05`):

```powershell
.\tools\run_server_load_suite.ps1 `
  -Profiles smoke,load,stress,spike,soak,breakpoint `
  -SoakDuration 1h -SoakRate 30 `
  -ApiLogPath .qa-results\api-18082-server-suite.out.log
```

AWS 실측이면 `-SloProfile aws -LoginRatio 0.005 -BaseUrl https://<대상>`을 추가한다.

Linux injector(BASE_URL 필수, 기본 `SLO_PROFILE=aws`·`LOGIN_RATIO=0.005`, 프로필 사이 `COOLDOWN_SECONDS`(기본 300초) 냉각 후 `/api/health` 확인):

```bash
BASE_URL=https://<cloudfront-domain> ./tools/run_server_load_suite.sh
# 프로필 선택 실행:
BASE_URL=https://<cloudfront-domain> PROFILES="smoke load" ./tools/run_server_load_suite.sh
```

결과 경로:

```text
infra/k6/reports/runs/{runId}/
  manifest.json            # runId, commit, baseUrl, 프로필별 exit code, LOGIN_RATIO/SLO_PROFILE
  server-smoke.json
  server-load.json
  server-load.console.log
  ...
  server-soak-resources.csv  # (Windows 로컬 실행 시)
  api.log
```

한국어 종합 보고서는 다음처럼 생성한다. summarizer는 run-dir에 실제로 존재하는 프로필만 집계하고 없는 report는 경고 후 건너뛴다(smoke만 실행한 run도 요약 가능).

```powershell
python tools\summarize_server_load_suite.py `
  --run-dir infra\k6\reports\runs\{runId} `
  --output docs\reports\server-load-suite-{runId}.md `
  --json-output docs\reports\server-load-suite-{runId}.json
```

60분 Soak는 5분 단위 12구간의 평균/p95/최대/오류율과 1분 단위 JVM 메모리를 기록한다. 운영 인증에는 같은 인프라 조건으로 2시간 이상 재실행하는 것이 적절하다.

## 테스트 위생 (AWS 실측 필수 체크)

테스트 창(window) 동안 아래를 지키지 않으면 실행 간 비교가 무너진다.

1. **스케줄러 동결**: API를 `BUILDGRAPH_SCHEDULING_ENABLED=false` + `DEMO_FREEZE_MUTATIONS=true`로 띄운다. KST cron이 테스트 창과 겹치면 결과가 오염된다 — 가격 수집 04:00, danawa 04:30(기본 off), prewarm/tier snapshot 매시 정각.
2. **테스트 중 배포 금지**: 배포 workflow는 테스트 대상 EC2 위에서 Gradle/Vite/Docker 이미지를 빌드한다. 테스트 창과 배포가 겹치면 CPU를 SUT와 빌드가 나눠 쓴다.
3. **중단 판정은 `/api/health`로만**: `/actuator/health`는 mail indicator를 포함해 부하와 무관한 사유로 DOWN이 될 수 있다. 러너의 프로필 종료 후 health 확인과 수동 abort 판단 모두 `/api/health`만 사용한다.
4. **실행 간 `refresh_tokens` truncate**: 로그인 부하가 남긴 row가 다음 실행의 인증 쿼리 비용을 바꾼다. 비교 가능성을 위해 run 사이에 정리한다.
5. **reranker shadow 비활성화**: 부하 테스트 창에는 `RECOMMENDATION_RERANKER_SHADOW_ENABLED`를 꺼서 홈 추천 조회가 `recommendation_shadow_scores` 쓰기를 유발하지 않게 한다(켠 채 측정하면 조회 부하에 쓰기 비용이 섞인다).
6. **t3 CPU credit mode 확인**: breakpoint/capacity 전에 대상 EC2가 T3/T4g면 credit mode `unlimited`인지 확인하고 `CPUCreditBalance`를 수집한다. credit 고갈 상태의 knee는 재현되지 않는 값이다.
