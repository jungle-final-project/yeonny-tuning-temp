# AWS 인프라 분리 Phase 0 실행 문서

이 문서는 [aws-infrastructure-separation-plan.md](/Users/juhoseok/Desktop/prototype/docs/aws-infrastructure-separation-plan.md)의 Phase 0 기준선이다.

AWS 콘솔에서 직접 따라 할 절차는 [aws-infrastructure-phase-0-console-guide.md](/Users/juhoseok/Desktop/prototype/docs/aws-infrastructure-phase-0-console-guide.md)를 1번부터 순서대로 실행한다.

## 확정된 데이터 정책

| 대상 | 정책 |
| --- | --- |
| 기존 PostgreSQL 데이터 | 폐기 가능, `pg_dump` 생략 |
| 기존 Redis key·세션·캐시 | 폐기 가능, 이전 생략 |
| 기존 RabbitMQ 메시지 | 폐기 가능, Queue drain 생략 |
| 기존 XGB 모델·booster 파일 | 폐기 가능, Volume 백업 생략 |
| 기존 PC Agent 로그 | 폐기 가능, Volume 백업 생략 |
| `.env.prod`의 API Key·OAuth Secret | 서비스 재구성에 필요하므로 유실 금지 |
| 소스 코드와 배포 SHA | Git과 GitHub Actions에서 보존 |

EBS 스냅샷, PostgreSQL dump, Docker Volume 압축 백업은 Phase 0 완료 조건이 아니다.

## 완료 조건

- [x] 로컬 저장소와 Compose 기준선 기록
- [x] 현재 AWS 리소스 식별자 기록
- [x] 현재 운영 배포 SHA 기록
- [x] 운영 EC2의 Compose 서비스 목록 확인
- [x] 운영 EC2의 `.env.prod` 파일 존재 확인
- [x] 비밀 환경 변수의 이름과 설정 여부만 확인
- [x] 전환 예정 시간과 담당자 확정 시점 기록

## 현재 AWS 기준선

| 항목 | 현재 값 |
| --- | --- |
| Region | `ap-northeast-2` 서울 |
| CloudFront 이름 | `buildgraph-demo-cloudfront` |
| CloudFront Distribution ID | `EI6MMNZLTTN3H` |
| CloudFront 도메인 | `d1a7gxvxxd385i.cloudfront.net` |
| 현재 CloudFront Origin | `ec2-15-164-235-183.ap-northeast-2.compute.amazonaws.com` |
| EC2 이름 | `buildgraph-demo-ec2` |
| EC2 ID | `i-082c21a20e14f3295` |
| EC2 타입 | `t3.medium` |
| EC2 Public IP | `15.164.235.183` |
| VPC | `vpc-06c90b864a62f93a4` (`buildgraph-demo-vpc`) |
| EC2 보안 그룹 | `sg-099aac782b77a854e` |

현재 CloudFront는 Web과 API가 분리되지 않았으며 모든 요청을 같은 EC2 Origin으로 전달한다.

## 현재 배포 기준선

```text
운영 Commit SHA: 144a204
배포 완료 시각(KST): 2026-07-13 04:03
GitHub Actions Run URL: https://github.com/jungle-final-project/prototype/actions/runs/29205075367
```

현재 배포 워크플로우는 `.git`을 제외하고 저장소 파일을 EC2에 전송한다. EC2에서 `git rev-parse HEAD`가 실패할 수 있으므로 GitHub Actions의 마지막 성공 실행을 운영 버전 기준으로 사용한다.

## 새 관리형 서비스 초기화 원칙

1. 새 RDS는 빈 PostgreSQL로 만든다.
2. API가 Flyway migration 전체를 실행한다.
3. `vector`, `pgcrypto`, seed 데이터 생성 여부를 검증한다.
4. 필요한 RAG embedding은 새로 backfill한다.
5. ElastiCache는 빈 Redis로 시작한다.
6. Amazon MQ는 빈 RabbitMQ Broker로 시작한다.
7. Spring의 `RabbitQueueConfig`가 Exchange, Queue, Binding을 새로 선언한다.
8. 기존 XGB 모델을 복원하지 않는다.
9. 새 모델 artifact가 준비되기 전에는 `RECOMMENDATION_RERANKER_ENABLED=false`를 유지한다.
10. 기존 PC Agent 로그는 이전하지 않는다.

## Phase 0에서 보존할 비밀값

문서나 채팅에는 값이 아니라 환경 변수 이름과 `SET`, `EMPTY_OR_MISSING` 상태만 기록한다.

| 환경 변수 | 처리 |
| --- | --- |
| `GOOGLE_OAUTH_CLIENT_ID` | 사용 중이면 Secrets Manager 이전 대상 |
| `GOOGLE_OAUTH_CLIENT_SECRET` | 사용 중이면 Secrets Manager 이전 대상 |
| `OPENAI_API_KEY` | 사용 중이면 Secrets Manager 이전 대상 |
| `NAVER_SEARCH_CLIENT_ID` | 사용 중이면 Secrets Manager 이전 대상 |
| `NAVER_SEARCH_CLIENT_SECRET` | 사용 중이면 Secrets Manager 이전 대상 |
| `AGENT_DEMO_ACTIVATION_TOKEN` | 사용 중이면 Secrets Manager 이전 대상 |
| `BUILDGRAPH_AUTH_JWT_SECRET` | 새 환경에서 새 값 생성 가능, Git 커밋 금지 |
| `POSTGRES_PASSWORD` | 기존 값 폐기 가능, 새 RDS용 값 별도 생성 |
| `RABBITMQ_DEFAULT_PASS` | 기존 값 폐기 가능, 새 Amazon MQ용 값 별도 생성 |

## 사용자 실행 결과 기록

```text
APP_DIR:
APP_DIR=/opt/buildgraph/prototype

Compose 서비스:
NAME                       IMAGE                    COMMAND                  SERVICE        CREATED         STATUS                     PORTS
prototype-api-1            prototype-api            "java -jar /app/app.…"   api            5 minutes ago   Up 5 minutes               8080/tcp
prototype-mailpit-1        axllent/mailpit:latest   "/mailpit"               mailpit        2 days ago      Up 2 days (healthy)        1025/tcp, 1110/tcp, 8025/tcp
prototype-postgres-1       buildgraph-postgres      "docker-entrypoint.s…"   postgres       6 minutes ago   Up 5 minutes (healthy)     5432/tcp
prototype-rabbitmq-1       rabbitmq:3-management    "docker-entrypoint.s…"   rabbitmq       2 days ago      Up 2 days (healthy)        4369/tcp, 5671-5672/tcp, 15671-15672/tcp, 15691-15692/tcp, 25672/tcp
prototype-redis-1          redis:7-alpine           "docker-entrypoint.s…"   redis          2 days ago      Up 2 days                  6379/tcp
prototype-web-1            prototype-web            "/docker-entrypoint.…"   web            5 minutes ago   Up 5 minutes (unhealthy)   0.0.0.0:80->80/tcp, [::]:80->80/tcp
prototype-xgb-reranker-1   prototype-xgb-reranker   "python tools/rerank…"   xgb-reranker   6 minutes ago   Up 5 minutes (healthy)     8091/tcp

ubuntu@ip-10-0-24-118:/opt/buildgraph/prototype$ "${COMPOSE[@]}" config --services
rabbitmq
redis
postgres
xgb-reranker
api
web
mailpit

Compose Volume:
ubuntu@ip-10-0-24-118:/opt/buildgraph/prototype$ "${COMPOSE[@]}" config --volumes
recommendation-models
agent-log-data
postgres-data
redis-data


Rendered Compose SHA-256:
docker compose -f compose.prod.yaml --env-file .env.prod config | sha256sum
postgres-data
redis-data
rabbitmq-data
recommendation-models
agent-log-data
4e16652007e10a439e0b7c5627661c2311268fc098abe25e72c45ec38e4451db  -


.env.prod 존재 여부:
ubuntu@ip-10-0-24-118:/opt/buildgraph/prototype$ cd /opt/buildgraph/prototype
chmod 600 .env.prod
stat -c '%a %U:%G %n' .env.prod
600 ubuntu:ubuntu .env.prod

GOOGLE_OAUTH_CLIENT_ID: SET | EMPTY_OR_MISSING
GOOGLE_OAUTH_CLIENT_SECRET: SET | EMPTY_OR_MISSING
OPENAI_API_KEY: SET | EMPTY_OR_MISSING
NAVER_SEARCH_CLIENT_ID: SET | EMPTY_OR_MISSING
NAVER_SEARCH_CLIENT_SECRET: SET | EMPTY_OR_MISSING
AGENT_DEMO_ACTIVATION_TOKEN: SET | EMPTY_OR_MISSING
BUILDGRAPH_AUTH_JWT_SECRET: SET | EMPTY_OR_MISSING
```

GOOGLE_OAUTH_CLIENT_ID=SET
GOOGLE_OAUTH_CLIENT_SECRET=SET
OPENAI_API_KEY=SET
NAVER_SEARCH_CLIENT_ID=SET
NAVER_SEARCH_CLIENT_SECRET=SET
AGENT_DEMO_ACTIVATION_TOKEN=EMPTY_OR_MISSING
BUILDGRAPH_AUTH_JWT_SECRET=SET

위 `SET`과 `EMPTY_OR_MISSING`는 Phase 0 확인 명령이 출력한 설정 여부다. `.env.prod`에 저장된 실제 값이 아니며, 실제 비밀값은 문서에 기록하지 않는다.

## 전환 일정과 담당자

```text
예정 전환 일시(KST): Phase 2 시작 전에 사용자 확정
AWS 콘솔 작업 책임자: 사용자
저장소·로컬 검증 담당자: Codex
운영 검증 및 롤백 결정자: 사용자
공지 채널: Phase 2 시작 전에 사용자 확정
```

Phase 0 완료일: `2026-07-13`

## Phase 0 종료 판정

다음 조건을 모두 만족하면 Phase 0을 완료한다.

1. 운영 Compose 서비스 목록을 확인했다.
2. `.env.prod` 파일이 존재한다.
3. 서비스 재구성에 필요한 비밀 환경 변수의 설정 여부를 확인했다.
4. 비밀값을 Git, 문서, 채팅에 노출하지 않았다.
5. 현재 운영 SHA를 기록했다.
6. 전환 시간과 담당자의 확정 시점을 기록했다.

완료 후 Phase 1의 테스트와 검증 자동화를 시작한다.
