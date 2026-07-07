# BuildGraph AI를 서울 리전에 올리는 결정 완결형 설계

> AWS 배포 전략 · ap-northeast-2 · ECS Fargate

그린필드 AWS 배포. 로컬 `docker-compose`(앱 3 + 인프라 4)를 풀 관리형 스택으로 옮기고, **오토스케일링 부하 시연**을 핵심 목표로 삼는다. 5개 축(컴퓨트 · 데이터 · 네트워크 · CI/CD · 관측/비용)의 개별 설계를 하나로 통합하고 상호 모순을 해소했으며, 적대적 리뷰가 짚은 14개 갭(다중 태스크 상태 공유 · WS 브로드캐스트 · LLM 과금 봉쇄 · 시크릿 평문)을 본문에 반영했다. **`apps/pc-agent`는 사용자 로컬 실행 CLI라 서버 배포 대상이 아니다** — AWS는 RabbitMQ 큐·활성화 토큰만 제공하고 에이전트 실행은 사용자 PC에서 돈다.

**메타**

- 목적 — **데모/포트폴리오 + 부하시연**
- 우선순위 — **관리형 우선**
- 도메인 — **임시 URL(무도메인)**
- 타임존 — **Asia/Seoul**
- Flyway — **V113 / 선(先) RunTask**

## 목차

1. [한눈 요약](#01-한눈-요약)
2. [목표 아키텍처](#02-목표-아키텍처)
3. [데이터 & 상태](#03-데이터--상태)
4. [네트워크 & 보안](#04-네트워크--보안)
5. [CI/CD & IaC](#05-cicd--iac)
6. [오토스케일 & 부하테스트](#06-오토스케일--부하테스트)
7. [관측성](#07-관측성)
8. [비용 추정](#08-비용-추정)
9. [단계별 롤아웃](#09-단계별-롤아웃)
10. [레포 필수 변경 체크리스트](#10-레포-필수-변경-체크리스트)

## 01. 한눈 요약

*무엇을 · 어디에 · 왜 이 선택인지*

**web**은 컨테이너가 아니라 **S3 + CloudFront 정적 배포**로, CloudFront가 `/api/*`·`/ws/*`를 ALB로 프록시해 **프론트 코드 무변경으로 same-origin**을 유지한다. **api**(Spring Boot)와 **xgb-reranker**(Python)만 ECR 이미지 2개로 **ECS Fargate**에 올린다. 상태 인프라는 전부 관리형으로 대체 — **RDS PostgreSQL 16(pgvector)** · **ElastiCache Redis 7** · **Amazon MQ(RabbitMQ)** · 파일 볼륨 2종은 **EFS**. 배포는 **GitHub OIDC → Terraform**, 크론·마이그레이션의 다중 태스크 위험은 코드가 이미 쥔 `advisory lock`과 **선(先) Flyway RunTask**로 봉인한다.

### 핵심 결정 카드

| 항목 | 결정 | 근거 |
|---|---|---|
| **컴퓨트 (컨테이너 2)** | *api* web-facing 2→8 + worker 고정1 · *reranker* 고정 1 | web은 S3+CF(컨테이너 아님). api는 크론 off/on으로 서비스 2분리, reranker는 `/models` 로컬승격이라 단일 |
| **스케일 트리거** | *req/target* 타깃트래킹 + CPU 보조 | `/admin/load-tests`의 읽기 폭격이 즉각 반응하는 선행 지표 |
| **진짜 상한** | *RDS 커넥션* × 태스크 수 | Hikari 풀 × 8태스크가 `max_connections` 병목 — 스케일아웃의 한계는 앱이 아니라 DB |
| **비용 안전판 (블로커)** | *deterministic 프로파일* 강제 + Spot + 단일 NAT | 부하 중 build-chat/AS-chat의 OpenAI 유료 호출을 스텁으로 강제해야 청구 0 — `DEMO_FREEZE`만으론 부족 |

## 02. 목표 아키텍처

*로컬 → AWS 서비스 매핑과 요청 흐름*

| 로컬 (compose) | AWS | 사이징 / 형태 | 핵심 근거 |
|---|---|---|---|
| **web** `npm run dev :5173` | S3 + CloudFront | 정적 `dist/` sync, OAC · **ECR 이미지 없음** | 현재 Dockerfile은 dev서버 — 폐기하고 빌드 산출물만 배포(R7) |
| **api** (web-facing) `:8080` | ECS Fargate | 1 vCPU / 2 GB · min2/max8 · **크론 off** | 스케일 대상. `@Scheduled` 전부 게이팅 off |
| **api** (worker) `:8080` | ECS Fargate | 1 vCPU / 2 GB · **고정 1** · 크론 on | 같은 이미지, env 토글만. 프리웜·티어스냅샷 단일 실행 보장 |
| **xgb-reranker** (서빙) `:8091` | ECS Fargate (내부) | 1 vCPU / 2 GB · **고정 1** · shadow | 학습·승격이 `/models` 로컬 파일 → 다중화 시 active 갈라짐 |
| **postgres** `pgvector:pg16` | RDS PostgreSQL 16.4+ | `db.t4g.medium` · gp3 50GB · Single-AZ | pgvector는 16.x 지원(15.2+ 실용), `CREATE EXTENSION`로 활성 |
| **redis** `:6379` | ElastiCache Redis 7 | `cache.t4g.small` · 1노드 | 캐시·시맨틱캐시·프리웜은 재생성 가능 |
| **rabbitmq** `:5672` | Amazon MQ 3.13 | `mq.t3.micro` · single | 1큐 규모, 관리형 우선 원칙 |
| **mailpit** `:1025` | Amazon SES | SMTP `:587` STARTTLS | 실 발신 자격, 발송량 극소 |
| vol **postgres-data** | RDS 스토리지 | 덤프 복원 | 임베딩 `vector(1536)`까지 이관 |
| vol **redis-data** | — | 이관 불필요 | 휘발, 프리웜이 재충전 |
| vol **recommendation-models** `/models` | EFS (AP `/models`) | reranker 단일 마운트, 정확히 `/models` | `default_model_path()`가 `/models/home-parts-active.json` 참조 — 마운트 경로 고정 필수 |
| vol **agent-log-data** `/data/agent-logs` | EFS (AP `/agent-logs`) | api 다중태스크 공유 | 파일명 UUID(`agent_log_uploads.id`) 확인 시 append 경합 없음 |

*요청 흐름 — 브라우저가 보는 origin은 CloudFront 도메인 하나로 통일된다.*

```text
  사용자 브라우저
        │
        ▼
  CloudFront  https://dxxxx.cloudfront.net  (AWS 기본 인증서 HTTPS, 무도메인)
   ├─ /*        ─▶  S3 (web 정적, OAC 비공개, 403/404→/index.html)
   ├─ /api/*    ─▶  ┐
   └─ /ws/*     ─▶  ┴─▶  ALB (HTTP:80, public subnet)
                              │
          ┌─────────────▼────────────┐   private-app subnet (2 AZ)
          │  ECS api · web-facing      │  ◀── Application Auto Scaling (2→8)
          │   · @Scheduled 전부 off    │      크론/컨슈머 없음 = N배 실행 원천차단
          │   · WS /ws (raw, 스티키)   │
          └───────────┬──────────────┘
          ┌───────────┼──────────────┐  고정 1 (스케일 제외)
          │  ECS api · worker          │
          │   · @Scheduled 8+2 전부 on │  ◀ 프리웜·티어스냅샷도 여기서만
          │   · RabbitMQ 컨슈머         │
          └──┬────┬────┬────┬─────────┘
   Svc Connect │    │    │    │  EFS /agent-logs (양 서비스 공유, UUID 파일명)
          ┌────▼─┐  │    │    └──────────────┐
          │reranker│ │    │                   │
          │ :8091 │◀┘    │            EFS /models (서빙 단일, RW)
          │ 단일  │      │
          └───┬───┘      │
   private-data ▼         ▼         ▼
     RDS pgvector   ElastiCache   Amazon MQ
     :5432(api+rerank):6379        :5671 AMQPS
       │
       └─ WS 브로드캐스트 팬아웃: Redis Pub/Sub (cross-task 갱신, §4 함정)

  private-app  ──▶  NAT GW (단일)  ──▶  OpenAI · naver openapi · search.danawa.com
```

## 03. 데이터 & 상태

*볼륨 4개 이전 + Flyway 실행 전략*

### RDS PostgreSQL 16 (pgvector)

로컬은 `pgvector/pgvector:pg16` 커스텀 이미지지만 RDS PostgreSQL 16은 pgvector를 **기본 지원 확장**으로 포함한다 — Aurora·커스텀 이미지 불필요. 인스턴스 `db.t4g.medium`(2 vCPU / 4 GB): 부하시연에서 RAG 벡터쿼리 + HNSW 인덱스 + reranker 직접접속이 겹치면 `t4g.micro`는 OOM 위험이라 medium이 실질 하한. 스토리지 gp3 50GB(자동확장 100GB). **Single-AZ 확정** — 데모라 페일오버 시연 가치 0, Multi-AZ는 비용 2배.

> ⚠️ **함정 · PGVECTOR 확장 순서 + 버전 + 권한**
>
> RDS PostgreSQL은 pgvector를 **지원 버전 하한**이 있다(대략 15.2+/16.x부터 실용) — 엔진 버전을 `16.4` 이상으로 못박는다. `vector` 확장은 자동 생성돼 있지 않으므로, 마이그레이션이 `vector(1536)` 컬럼을 만들기 전에 `V0__enable_pgvector.sql`의 `CREATE EXTENSION IF NOT EXISTS vector;`가 선행돼야 한다. RDS는 `shared_preload`·파라미터그룹 없이 **마스터 유저 권한으로 `CREATE EXTENSION`만** 실행되므로, Flyway 마이그레이션 태스크가 **RDS 마스터 자격**으로 도는지 게이팅한다(앱 런타임 유저는 확장 생성 권한 없을 수 있음). 미탑재 시 `RAG_VECTOR_ENABLED=true` 경로 전면 실패.

### ElastiCache Redis 7 (WS 팬아웃 역할 추가)

Redis는 **cluster mode OFF, 단일 노드**(`cache.t4g.small`). 용도 — 시맨틱 캐시·프리웜·티어 스냅샷·상담챗 WS 티켓(`SUPPORT_CHAT_WS_TICKET_TTL_SECONDS=60`) — 가 재생성 가능한 휘발 데이터라 replica·AOF 백업 불필요. 단 아래 WS 함정을 봉합하려면 **Redis Pub/Sub 채널을 하나 추가로 쓴다**(정합성 부담은 낮음, 이미 있는 Redis 재활용).

> ⚠️ **함정(치명) · WS 브로드캐스트가 IN-MEMORY라 다중 태스크에서 깨짐**
>
> `SupportChatWebSocketHandler`는 세션을 `sessionsByChatId = new ConcurrentHashMap<>()`(**태스크 로컬 JVM 메모리**, 34행)에 담고 `broadcastRoomUpdate()`(87~89행)가 그 맵만 순회한다. 상담원이 태스크 A, 고객이 태스크 B에 붙으면 상대 태스크 세션에 **도달 못 해 실시간 갱신 유실**. **스티키 세션은 연결 유지에만 유효하고 cross-task 브로드캐스트는 못 고친다.** → 봉합안 두 가지: ① **Redis Pub/Sub 팬아웃** — `broadcastRoomUpdate`가 채널에 발행, 각 태스크가 구독해 자기 로컬 맵으로 push(코드 소폭 수정, 스케일 유지). ② **상담챗 WS만 worker 단일 태스크로 라우팅**(`/ws/*`를 web-facing 아닌 worker로, 코드 0변경이나 WS는 스케일 못 함). 데모라면 ②가 가장 싸고 안전 — 상담챗은 부하시연 대상 아님(코드 없이 `/ws/*` ALB 타깃을 worker로).

### reranker 모델 경로 — 학습/서빙 불일치 (다중화 금지 근거)

서빙(`reranker_service.py`)의 `default_model_path()`는 `/models/home-parts-active.json`을 읽고, 서빙 컨테이너 내 학습 워커(`RECOMMENDATION_TRAINING_WORKER_ENABLED`)가 `/models/{version}.json`을 쓴 뒤 `persist_active_model`로 `-active.json`에 **로컬 복사·승격**한다. 반면 오프라인 CLI `train_xgb_reranker.py`는 `--output-dir` 기본이 `artifacts/recommendation/model`이고 `home-parts-latest.json`을 써 **서빙 경로와 다르다**. 따라서 reranker를 다중 태스크로 띄우면 각 태스크가 자기 로컬 `/models`에 승격 → **active 모델이 태스크마다 갈라진다**.

> ⚠️ **결정 · RERANKER는 서빙 1태스크, EFS 마운트는 정확히 /MODELS**
>
> **서빙 태스크 1개**(학습 워커 포함)만 두고 `recommendation-models`를 **EFS로 `/models`에 RW 마운트**한다 — `default_model_path()`가 `/models/home-parts-active.json`을 보므로 마운트 지점이 정확히 `/models`여야 한다. 훗날 서빙을 늘리려면 **워커 1(RW) + 서빙 N(RO 마운트)**로 분리하고 승격 후 서빙에 `/reload` 엔드포인트(240행)로 리로드 신호를 준다. 기본은 shadow-only(`RECOMMENDATION_RERANKER_ENABLED=false`)라 단일로 충분.

### 볼륨 2종 → EFS (S3 아님)

두 볼륨을 EFS로 마운트하면 **저장/조회 코드가 0변경**이다(S3 전환은 경로 전면 리팩터링 → 데모 범위 밖). `agent-log-data`는 api가 `AGENT_LOG_STORAGE_ROOT=/data/agent-logs`로 파일 기록 — 다중 태스크 공유엔 EFS가 맞으나 **append 경합**이 관건이다. 파일명이 잡 UUID(`agent_log_uploads.id`) 기반이면 태스크 간 충돌 없이 안전, 순번/타임스탬프 기반이면 경합 위험이므로 **P0에서 파일명 규칙을 코드로 확인**한다(R9). 기록 태스크와 조회 태스크가 달라도 EFS 공유라 동작은 하지만 쓰기→읽기 일관성 지연 주의.

### RabbitMQ → Amazon MQ (SQS 대안 트레이드오프)

에이전트 잡 컨슈머는 **competing consumer**라 다중 태스크에서 안전하게 나뉜다(단, 크론과 함께 worker 단일 태스크에만 둔다). 큐가 에이전트 잡 하나뿐이라 **SQS가 훨씬 싸고 관리 0**이지만, SQS는 AMQP가 아니라 **Spring RabbitMQ 리스너 코드를 재작성**해야 해 "코드 무변경" 원칙과 상충한다. → 데모 기간은 **Amazon MQ single-instance(비HA, `mq.t3.micro`)**로 코드 무변경 + 고정비 최소화를 택한다. 로컬 `5672` 평문과 달리 **AMQPS `5671`/TLS**이므로 `SPRING_RABBITMQ_SSL_ENABLED=true` 필수(미설정 시 컨슈머 침묵 실패, R4). 상시비용($16/월)이 부담이면 비시연 기간 **브로커 삭제**가 현실적 절감 레버(§8).

### Flyway — 부팅 실행 금지, 선(先) RunTask

현재 `spring.flyway.enabled=true`라 모든 태스크가 부팅 때 마이그레이션한다. 부하시연·Blue/Green으로 N개가 동시 부팅하면 히스토리 락 경합으로 뒤 태스크가 대기 → **오토스케일 반응성 시연이 훼손**된다. out-of-order까지 켜져 있어 더 예민.

> 📌 **전략 · MIGRATE → DEPLOY 순서 강제**
>
> 앱 태스크는 `SPRING_FLYWAY_ENABLED=false`로 부팅. 배포 파이프라인이 api 이미지로 **one-off `ecs run-task`**를 1회 실행(`flyway.enabled=true` override)해 마이그레이션 완료를 확인한 **뒤에만** 앱을 롤아웃한다. exit≠0이면 배포 중단 → 반쯤 마이그레이션된 채 신버전이 뜨는 사고 방지.

초기 시드는 **로컬 `pg_dump` → RDS 복원**이 데모 재현성 최상(임베딩까지 그대로 이관, RAG 즉시 동작). 크론 자연 수집은 NAT 경유 아웃바운드라 데모 직전엔 부적합.

## 04. 네트워크 & 보안

*VPC · CloudFront 단일화 · Secrets · IAM · CORS/WS*

**VPC `10.20.0.0/16`, 2 AZ**(a/c) 3계층 — public(ALB·NAT) / private-app(ECS) / private-data(RDS·Redis·MQ). private-data는 인터넷 라우트 없음. api·reranker 아웃바운드(OpenAI·`openapi.naver.com`·`search.danawa.com`)는 **단일 NAT Gateway**로 통과 — 데모라 AZ별 이중화 대신 1개로 월 비용 절감(advisory lock 덕에 Danawa 스크래핑은 다태스크에서도 1회만 → 레이트리밋 안전). ECR·S3·Secrets·Logs는 **VPC 엔드포인트**로 빼 NAT 데이터 요금을 줄인다.

> 📌 **핵심 결정 · CLOUDFRONT 단일 도메인**
>
> web·api·ws를 **CloudFront 단일 배포**로 통합한다. 브라우저 origin이 `https://dxxxx.cloudfront.net` 하나로 통일돼 CORS·WS origin·혼합콘텐츠(HTTPS→HTTP) 문제가 원천 제거된다. `/api/*`·`/ws/*`는 **캐시 비활성** + `Origin`·`Authorization` 전달 필수 — 안 그러면 응답 캐시로 유저 간 데이터 혼입, 프리플라이트 `Origin` 절단으로 CORS 붕괴.

**HTTPS는 커스텀 도메인 없이** 확보된다: CloudFront 배포가 `*.cloudfront.net` + AWS 관리 인증서를 즉시 주므로 ACM 발급·Route 53 불필요(사용자 확정 "임시 URL로 충분"과 부합). ALB 구간은 HTTP지만 사용자↔CloudFront는 HTTPS라 자물쇠가 뜬다.

> ⚠️ **함정 · CORS와 WS가 한 변수에 묶임 + ALLOWCREDENTIALS는 배포 전 블로커**
>
> `SupportChatWebSocketConfig`가 `setAllowedOrigins`에 **CORS와 동일한** `buildgraph.cors.allowed-origins`를 재사용한다. 값이 틀리면 CORS는 되는데 **상담챗 WS만 조용히 403**. → `BUILDGRAPH_CORS_ALLOWED_ORIGINS`에 CloudFront 도메인을 **스킴·슬래시·포트까지 정확 일치**로 넣고 WS 핸드셰이크를 스모크 테스트한다. **[블로커]** WS 티켓 인증(`SUPPORT_CHAT_WS_TICKET_TTL_SECONDS=60`)·`SecurityContext`가 **쿠키 세션이면** CloudFront/ALB가 쿠키를 forward하고 CORS `allowCredentials(true)`가 필요하다 — 미확정 상태로 배포하면 프로덕션 WS 인증이 깨질 수 있으므로 **배포 전 인증 방식(쿠키 세션 vs Bearer 티켓)을 반드시 확정**한다(추정 구현 금지, R10).

> ⚠️ **함정 · 데모 토큰·플래그가 프로덕션에 잔존**
>
> compose 기본이 `AGENT_DEMO_ACTIVATION_TOKEN=demo-agent-activation-token`, `PART_MANUFACTURER_RELEASE_DEMO_FEED_ENABLED=true`다. 이들은 Secrets Manager로 옮길 대상이 아니라 **태스크 정의에서 명시적으로 비활성**해야 한다(토큰은 빈값 또는 DB 토큰으로 대체, demo feed는 off). 오버라이드가 체크리스트에 없으면 데모 토큰이 프로덕션에 노출된다(R6).

### 보안그룹 (계층 최소권한)

| SG | 인바운드 | 아웃바운드 |
|---|---|---|
| **alb-sg** | CloudFront origin-facing prefix list → :80 | api-sg :8080 |
| **api-sg** | alb-sg :8080 | data-sg(5432/6379/5671), reranker-sg :8091, 0.0.0.0/0(NAT) |
| **reranker-sg** | api-sg :8091 (ALB 미노출) | data-sg :5432 |
| **data-sg** | api-sg + **reranker-sg** → 각 포트 | — |
| **efs-sg** | api-sg + reranker-sg :2049 | — |

> ⚠️ **함정 R3 · RERANKER→POSTGRES SG 누락**
>
> **reranker가 RDS에 직접 접속**한다. data-sg 인바운드에 reranker-sg를 빠뜨리면 학습 워커가 **조용히** DB 접속 실패 → 리랭커 shadow 무동작.

### Secrets vs SSM · IAM 두 역할

진짜 시크릿(`OPENAI_API_KEY`·`NAVER_SEARCH_CLIENT_ID/SECRET`·`SPRING_DATASOURCE_PASSWORD`·`SPRING_RABBITMQ_PASSWORD`·Redis/SMTP 자격)은 **Secrets Manager**, 비민감 엔드포인트·토글(`OPENAI_BASE_URL`·모델명·CORS origin)은 **SSM Parameter Store**(비용 0). ECS 역할은 둘로 분리 — **태스크 실행 역할**은 ECR pull + `GetSecretValue`·`GetParameters`·`kms:Decrypt`(정확 ARN), **태스크 역할**은 앱 런타임 권한(api만 SES `SendEmail`). GitHub Actions는 시크릿 값 자체를 만지지 않는다.

> ⚠️ **함정 · 시크릿을 ENVIRONMENT 평문에 두지 말 것**
>
> compose는 이 값들을 전부 `environment`로 넣는다. 태스크 정의에서는 **`environment`가 아니라 `secrets`(`valueFrom`)로 주입**해야 하며, 그러지 않으면 콘솔·`describe-task-definition`·CloudWatch에 평문 노출된다. 특히 `SPRING_DATASOURCE_PASSWORD`·`SPRING_RABBITMQ_PASSWORD`는 **RDS·Amazon MQ가 생성한 관리형 시크릿과 로테이션 연동**이 가능하므로 직접 상수로 박지 않는다(R13은 체크리스트에 개별 항목).

> 📌 **WAF · 부하시연 배려**
>
> 기본은 **붙이지 않되 스위치로 준비**. rate-based 룰이 켜지면 부하 생성기가 **스케일아웃 전에 429로 차단**돼 관찰 대상이 가려진다. 붙일 땐 `CommonRuleSet`+`KnownBadInputs`만, rate 룰은 부하 IP allowlist. `/admin/load-tests`는 앱 관리자 인증 뒤로.

## 05. CI/CD & IaC

*GitHub OIDC → Terraform · ECR 2 이미지 · Blue/Green*

**IaC는 Terraform**(CDK 아님) — 포트폴리오 시그니처가 크고, 이 스택은 로직 없는 정적 리소스 그래프라 CDK 장점이 살 여지가 적으며, 3언어(Java·Python·TS) 레포에 CDK 언어를 더하면 리뷰어 인지부하만 는다. **ECR 이미지는 2개**(`buildgraph/api`·`buildgraph/reranker`) — web은 S3 sync라 이미지 불필요. 태스크 정의는 **`:git-sha` 다이제스트 고정**(`:latest`는 롤백 불가).

자격증명은 **GitHub OIDC → AssumeRole**로 장기 액세스키 0개. `iam:PassRole`은 `Condition: iam:PassedToService = ecs-tasks.amazonaws.com` + 특정 role ARN으로 잠근다(와일드카드는 권한상승).

*배포 파이프라인 — 순서가 곧 안전장치.*

```text
  main push
     │
     ├─ [gate] ci.yml (web·api·openapi·compose·docker-smoke) 통과 필수
     ▼
  build-and-push   matrix: api, reranker → ECR :sha + :latest
     │                web: npm run build (VITE_API_BASE_URL="") → 아티팩트
     ▼
  migrate  ⚠ 앱보다 먼저 단독  ecs run-task, flyway=true, RDS 마스터 자격, exit0 대기
     ▼
  deploy-api      CodeDeploy Blue/Green · 헬스체크 후 트래픽 전환
     ▼
  deploy-reranker ECS 롤링 (shadow라 롤링으로 충분)
     ▼
  deploy-web     S3 sync --delete + CloudFront invalidation (/index.html)
```

web sync는 해시 자산을 `immutable`로, `index.html`만 `no-cache` + 무효화해 즉시 반영·비용 최소. api는 무중단·시연 대상이라 **Blue/Green**, reranker는 shadow라 **롤링**. web은 신규 API 계약을 기대하므로 **api 뒤에** 배포.

> ⚠️ **함정 · 롤링 중 스키마-코드 불일치 + OUT-OF-ORDER 가정**
>
> Flyway 자체 `pg_advisory_lock`이 동시 마이그레이션을 직렬화하므로 **경합으로 깨지진 않지만**, 롤링 배포 중 신·구 태스크가 공존하면 신버전이 V113→V114를 적용한 뒤에도 구버전 태스크가 트래픽을 받아 **스키마-코드 불일치 순간**이 생긴다. 선(先) RunTask 게이팅으로 마이그레이션을 앱 롤아웃 앞에 두면 이 창을 좁힌다(파괴적 DDL은 expand→contract 2배포로 분리). 로컬은 PR#62 V69~81·#74 V99~107 재번호 이력 때문에 `out-of-order`/ignore-pattern override가 필요했지만, **프로덕션은 clean RDS에 V1부터 순서대로 도는 전제**라 out-of-order는 불필요하다 — 이 가정(clean DB로 시작)을 배포 전 확정한다. 초기 시드를 `pg_dump`로 이관하면 히스토리 테이블도 함께 오므로 이 경우 로컬과 동일 설정으로 맞춘다.

## 06. 오토스케일 & 부하테스트

*트리거 · 기대 동작 · 시연 포인트*

**api에만** Application Auto Scaling(min 2 / max 8). 주 정책 **ALBRequestCountPerTarget 타깃트래킹**(≈1000 req/target/분) — req/target이 CPU보다 선행 지표라 "요청 몰림 → 즉시 증설" 서사가 깔끔. 보조 **CPU 60%** — OpenAI 대기·임베딩·GC 등 요청수 비례 안 하는 스파이크 방어. scale-out cooldown 60s(빠르게), scale-in 300s(천천히)로 그래프를 깔끔하게.

> ⚠️ **함정(최대) · 크론과 스케일아웃의 충돌 + 게이팅 실장**
>
> advisory lock은 `PipelineJobRunRecorder`에만 있고(`pg_try_advisory_lock`), 이를 부르는 건 Danawa·PartPrice·ManufacturerRelease·Recommendation{Drift,Retrain,ShadowRetention} **8종뿐** — 이들은 스케일아웃해도 잡당 1개만 돈다. 그러나 `BuildChatCachePrewarmService`(fixedDelay 45분)와 `BuildChatTierSnapshotRefresher`(fixedDelay 60분)는 **Recorder를 전혀 부르지 않아 N배 실행**되고, prewarm은 OpenAI 유료 호출을 하므로 **web 태스크가 N개면 프리웜이 N배 과금**된다. → api를 **web-facing(크론 off) + worker(고정 1, 크론 on)**로 분리하되, **게이팅 방식이 잡 유형마다 다르다**: cron 표현식 8종은 `@Scheduled(cron=...)`를 `-`(비활성)로 프로파일 override, **fixedDelay 2종은 cron 표현식이 없으므로 별도 on/off 플래그(`@ConditionalOnProperty` 또는 env 게이트)를 코드에 신설**해야 한다. 이 fixedDelay 게이팅 실장이 체크리스트에 반드시 있어야 한다(R2 확장).

### 부하테스트 설계

도구는 **k6**, **같은 VPC 내 Fargate 태스크**에서 구동(로컬 PC→ALB는 사무실 업링크가 먼저 병목이라 스케일이 안 뜬다). `/admin/load-tests`는 오케스트레이션·기록용.

| 티어 | 엔드포인트 | 비중 | 이유 |
|---|---|---|---|
| **[안전]** | `GET /api/parts` (20개 페이지) | 50% | 순수 DB 읽기, 외부 비용 0 |
| **[안전]** | 홈 추천 스코어러 | 30% | api CPU 집중 → 스케일 트리거 주역 |
| **[DB쓰기]** | `POST /api/build-graphs/resolve` | 15% | pgvector·조인, RDS 커넥션 병목 유발 |
| **[LLM]** | build-chat / AS-chat | ≤5% | OpenAI 실과금 — `deterministic` 프로파일로 스텁 강제 |

> ⚠️ **함정(핵심) · 부하 중 LLM 과금 봉쇄는 DEMO_FREEZE만으론 부족**
>
> `DEMO_FREEZE_MUTATIONS`는 **가격·자산 수집 동결**이고, `AGENT_RUNNER_MODE=deterministic`은 에이전트 잡 경로다 — 둘 다 **build-chat/AS-chat 인퍼런스 자체는 막지 못한다**. 스케일 유발 엔드포인트가 챗/추천이면 **cache miss마다 OpenAI 유료 호출이 샌다**. → 부하시연 태스크는 챗 경로를 **스텁/deterministic으로 강제하는 프로파일**(예: `OPENAI_BASE_URL`을 모의 응답 서버로, 또는 챗 서비스에 deterministic 플래그)로 띄우고, 부하 프로파일은 **prewarm 캐시·티어스냅샷 히트 경로에만** 트래픽을 몰아 miss가 유료로 새지 않는지 사전 확정한다. 비중 ≤5%도 지표 관찰용일 뿐, 실과금 경로면 스텁이 필수. **AWS Budgets 알람(§7)을 부하 전 반드시 무장**한다.

**기대 그래프(시연 서사):** ramp에서 ALB p95↑ → CPU 60% 초과 → DesiredTaskCount 계단 상승 → RunningTaskCount 추격 → p95 회복 → cooldown 스케일인. 그리고 **api 8태스크에서 `hikari_active_connections × 8`이 RDS `max_connections`를 초과**하는 지점을 일부러 노출해 "스케일아웃의 한계는 앱이 아니라 RDS"라는 인사이트를 시연(RDS Proxy 논거로 연결).

### Fargate Spot

api는 **base=1 On-Demand + 나머지 Spot**(크론·컨슈머·WS 최소 1개 보장, 스케일아웃분은 Spot으로 절감). Spot 회수 시 advisory lock은 세션 락이라 커넥션 종료로 자동 해제 → 데드락 없음(안전 근거). reranker는 학습 중 회수 위험 + 단일이라 **On-Demand**. `stopTimeout=120` + `server.shutdown=graceful`로 in-flight 드레이닝.

## 07. 관측성

*대시보드 1장이 시연 산출물*

**Container Insights**(기본형, Enhanced 금지 — 과금 큼)로 DesiredTaskCount vs RunningTaskCount 곡선을 자동 수집 — 오토스케일의 핵심 증거. 로그는 `awslogs`로 `/ecs/buildgraph/{api,reranker,web}` 분리, **보존기간 필수 설정**(미설정 = 무기한 = 비용 누수). 선택적으로 **Micrometer + CloudWatch**로 `home_reco_latency_ms`·`hikari_active_connections` push(체감 latency 우선순위 증명용).

*대시보드 1장 — 부하↔스케일↔병목의 인과를 한 화면에.*

```text
  ① 부하 입력    ALB RequestCount · ActiveConnectionCount
  ② 스케일 증거  ECS RunningTaskCount vs DesiredTaskCount
  ③ 응답 품질    ALB TargetResponseTime p95 · 5XX · RejectedConnection
  ④ 병목        RDS DatabaseConnections/CPU · ElastiCache CPU · MQ 큐깊이
  ⑤ 앱 커스텀    home_reco_latency_ms · hikari_active_connections
```

| 알람 | 임계 | 액션 |
|---|---|---|
| ALB 5xx | 5분 > 10건 | SNS 이메일 |
| ALB p95 지연 | > 2s ×3 | SNS |
| HealthyHostCount | < 1 | SNS (기동 실패) |
| ECS api CPU | > 60% | **스케일아웃**(타깃트래킹) |
| **RDS 커넥션** | > max의 80% | SNS — **진짜 상한 신호** |
| Amazon MQ 큐 | 깊이 > 1000 | SNS (잡 적체) |
| **AWS Budgets** | 월 예측 초과 | SNS — 데모 필수 |

## 08. 비용 추정

*서울 리전 · 월 USD · Single-AZ / Spot / 단일 NAT 기준*

| 항목 | 데모 상시 (월) | 부하시 추가 | 비고 |
|---|---|---|---|
| ECS api (web-facing min2 + worker 1) | ~$27 | +$3/회 | 상시 3태스크, 부하 시 8까지 |
| ECS reranker (**On-Demand**) | ~$18 | — | 학습 중 회수 위험·단일이라 Spot 아님 |
| S3 + CloudFront (web) | ~$2 | — | 컨테이너 아님 — 정적 호스팅 |
| ALB | ~$18 | +LCU 소액 | 시간요금 지배 |
| RDS t4g.medium Single-AZ | ~$18 | — | +스토리지 $3 |
| ElastiCache t4g.small | ~$13 | — | single-node |
| Amazon MQ t3.micro | ~$16 | — | **과소평가 상시비용** |
| **NAT Gateway (단일)** | ~$33 | — | **비용 1위** · 부하는 내부라 데이터비 미증 |
| Secrets Manager (~6) | ~$3 | — | |
| CloudWatch | ~$5 | +$3/회 | 보존기간 설정 시 |
| ECR · SES | ~$2 | — | |
| OpenAI/네이버/Danawa | 사용량 | **스텁 강제 시 0** | deterministic 프로파일 |
| **합계 (대략)** | **~$158/월** | **+$7~10/회** | 고정비 하한 ~$105 |

**트래픽 0에도 과금되는 고정비가 핵심 함정이다.** **NAT Gateway($33)·ALB($18)·Amazon MQ($16)·RDS($21)·ElastiCache($13)**는 요청이 0이어도 시간요금이 나간다. OpenAI·네이버·Danawa 아웃바운드가 프라이빗 서브넷을 나가려면 **NAT가 필수**라 NAT 비용은 회피 불가(VPC 엔드포인트로 AWS 트래픽만 우회해 데이터 요금만 줄인다). 포트폴리오라면 **상시 가동이 오히려 비합리**다.

**절감 레버(비시연 기간):** ECS 전 서비스 `desiredCount=0`(EventBridge 스케줄) · **RDS `stop`**(최대 7일, 이후 자동 기동되나 재중지 스케줄) · **Amazon MQ 브로커 삭제**(IaC로 재생성) · CloudFront/S3는 상시 유지(저렴). 이렇게 하면 비시연 기간 고정비를 NAT·ElastiCache 정도로 눌러 **월 $50 미만**까지 내려간다. 시연 직전 `terraform apply` + `pg_dump` 복원으로 30분 내 재기동. **NAT($33)와 Amazon MQ($16)**가 상시 비용 1·2위 — MQ는 SQS 전환 시 제거 가능하나 코드 재작성 비용과 저울질(§3).

## 09. 단계별 롤아웃

*각 단계 산출물 · 검증*

### Phase P0 — 레포 준비

- **작업**: `V0__enable_pgvector.sql` · flyway·크론 게이팅(fixedDelay 2종 플래그 신설) · **WS 팬아웃(Redis Pub/Sub 또는 worker 라우팅)** · web Dockerfile 폐기 · SES STARTTLS · **인증 방식 확정(allowCredentials 블로커)** · agent-logs 파일명 확인
- **산출물**: PR — 코드/설정 변경(§10 R1~R13)
- **검증**: `./gradlew test` · `npm run build` · compose로 `SPRING_FLYWAY_ENABLED=false` 부팅 · **2인스턴스로 WS 브로드캐스트 cross-task 도달 확인**

### Phase P1 — 코어 인프라 (Terraform)

- **작업**: network·rds·elasticache·amazonmq·secrets·efs·alb·cloudfront-s3 모듈 apply
- **산출물**: VPC + 관리형 4 + EFS 2 AP + CloudFront 도메인
- **검증**: `pg_dump`→RDS 복원 후 `SELECT` · CloudFront 도메인→S3 index 200 · reranker→RDS 접속 확인(R3)

### Phase P2 — CI/CD

- **작업**: OIDC Role · ECR 2 · `deploy.yml`(build→migrate→api→reranker→web) · ECS 서비스(api Blue/Green, reranker 롤링)
- **산출물**: main push 시 자동 배포 파이프라인
- **검증**: migrate exit0 게이트 동작 · CloudFront `/api/*`·`/ws/*` 통과 · **상담챗 WS 핸드셰이크 스모크**(R1)

### Phase P3 — 오토스케일 + 부하테스트

- **작업**: Auto Scaling 정책 · worker/web-facing 서비스 분리 · k6 Fargate 태스크 · CloudWatch 대시보드/알람
- **산출물**: 스케일 이벤트 대시보드 1장 · 부하 프로파일
- **검증**: k6 ramp → 2→4→8 태스크 실시간 · `DEMO_FREEZE` on 시 OpenAI 호출 0 · RDS 커넥션 상한 노출

## 10. 레포 필수 변경 체크리스트

*파일/설정 단위 · 우선순위순 (빠뜨리면 런타임 침묵 실패)*

| # | 우선 | 파일 / 설정 | 변경 |
|---|---|---|---|
| R1 | **[필수]** | `db/migration/V0__enable_pgvector.sql` | `CREATE EXTENSION IF NOT EXISTS vector;` 선행 + RDS 마스터 유저로 migrate 실행 (없으면 첫 배포 중단) |
| R2 | **[필수]** | `application.yml` flyway + 크론 게이팅 | `SPRING_FLYWAY_ENABLED` env화(앱=false, migrate 태스크=true). **fixedDelay 2종(prewarm·tier-snapshot)에 on/off 플래그 신설** + cron 8종 프로파일 override → worker만 on |
| R3 | **[필수]** | WS 브로드캐스트 (치명) | `SupportChatWebSocketHandler` in-memory 맵 → **Redis Pub/Sub 팬아웃** 또는 `/ws/*`를 worker 단일 태스크로 라우팅 |
| R4 | **[필수]** | reranker 서빙 단일 + `/models` 마운트 | 서빙 1태스크로 고정, EFS를 정확히 `/models`에 RW 마운트(`default_model_path()` 근거) |
| R5 | **[필수]** | 부하시연 LLM 스텁 | build-chat/AS-chat을 **deterministic/스텁 프로파일**로 강제 + AWS Budgets 무장 (`DEMO_FREEZE`만으론 불충분) |
| R6 | **[필수]** | task def — 시크릿 `valueFrom` | `OPENAI_API_KEY`·`NAVER_*`·`SPRING_DATASOURCE_PASSWORD`·`SPRING_RABBITMQ_PASSWORD`을 **`secrets`로 주입, `environment` 평문 금지** |
| R7 | **[중요]** | SES 메일 설정 | `mail.smtp.starttls.enable=true` + `:587` (안 하면 발송 실패) |
| R8 | **[중요]** | Amazon MQ 접속 | `SPRING_RABBITMQ_SSL_ENABLED=true` + `:5671` (안 하면 컨슈머 죽음) |
| R9 | **[중요]** | `apps/web/Dockerfile` | **폐기** → `npm run build` 산출물 `dist/` → S3 sync (현재 `npm run dev` dev서버). ECR에서 web 이미지 제외 |
| R10 | **[중요]** | ECS task env — CORS/데모 | `BUILDGRAPH_CORS_ALLOWED_ORIGINS=https://dxxxx.cloudfront.net` · `AGENT_DEMO_ACTIVATION_TOKEN=`(빈값) · `PART_MANUFACTURER_RELEASE_DEMO_FEED_ENABLED=false` |
| R11 | **[중요]** | HikariCP 풀 상한 | `maximum-pool-size` 태스크당 5~8 env화 (RDS `max_connections` 상한) |
| R12 | **[확인(블로커)]** | `allowCredentials` · 인증 방식 | WS/세션이 쿠키 기반이면 `allowCredentials(true)` + 쿠키 forward 필요 — **배포 전 확정**(추정 안 함) |
| R13 | **[확인]** | agent-logs 파일명 | UUID(`agent_log_uploads.id`) 기반인지 확인 (EFS append 경합 방지) |
| R14 | **[신규]** | `.github/workflows/deploy.yml` · `infra/terraform/**` | 배포 파이프라인·IaC 모듈 신설 (§5). **clean RDS 전제라 out-of-order 불필요**, `pg_dump` 이관 시 로컬 설정과 일치 |

### 열린 질문 (배포 전 확정 필요)

- **WS/세션 인증 방식** — 상담챗 티켓·`SecurityContext`가 쿠키 세션인지 Bearer 티켓인지. 쿠키면 `allowCredentials(true)` + CloudFront 쿠키 forward가 필수라 미확정 시 프로덕션 WS 인증이 깨진다(R12 블로커). **추정 구현 금지 — 코드 확인 후 결정.**
- **WS 브로드캐스트 봉합안 선택** — Redis Pub/Sub 팬아웃(스케일 유지, 코드 수정)과 상담챗 WS만 worker 단일 라우팅(코드 0, 스케일 불가) 중 데모 우선순위에 따라 택일. 상담챗을 부하 대상에 넣을지가 결정 인자.
- **build-chat/AS-chat 스텁 경로** — deterministic 프로파일이 실제로 OpenAI 호출을 우회하는 스위치가 코드에 있는지, 없으면 `OPENAI_BASE_URL`을 모의 서버로 돌리는 방식으로 대체할지. cache miss가 유료로 새지 않는지 부하 전 실측.
- **agent-logs 파일명 규칙** — 잡 UUID 기반이면 EFS append 안전, 순번/타임스탬프면 경합 대책 필요(R13).
- **초기 DB 시드 방식** — `pg_dump` 이관(히스토리 포함 → 로컬 Flyway 설정 승계) vs clean RDS에 V1부터(out-of-order 불필요). 데모 재현성과 마이그레이션 가정이 여기서 갈린다.
- **Amazon MQ 존속** — 상시비용 $16을 감수하고 코드 무변경(single-instance)으로 갈지, 장기적으로 SQS 전환(코드 재작성)으로 비용을 없앨지. 데모 기간은 MQ 유지 권장.

---

**관련 파일**

- `compose.yaml` — 서비스/볼륨/env 원본 (데모 토큰·시크릿 기본값)
- `apps/api/.../common/PipelineJobRunRecorder.java:48–69` — advisory lock (8종만 안전, prewarm·tier-snapshot 없음)
- `apps/api/.../ticket/SupportChatWebSocketHandler.java:34,87–89` — in-memory 세션 맵·cross-task 브로드캐스트 유실
- `tools/reranker_service.py:135–148,240,683–774` — default_model_path=/models/home-parts-active.json, 학습·승격·/reload
- `tools/train_xgb_reranker.py:36,105–108` — output-dir=artifacts/…, home-parts-latest.json (서빙 경로와 불일치)
- `apps/web/Dockerfile` — 현재 npm run dev(node:22-alpine, 5173), 폐기 대상

*BuildGraph AI · AWS 배포 전략 종합 · 5축 통합 + 적대적 리뷰 14갭 반영 (컴퓨트 · 데이터 · 네트워크 · CI/CD · 관측/비용)*
