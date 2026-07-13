# AWS 인프라 분리 Phase 1 테스트 기준선

이 문서는 [aws-infrastructure-separation-plan.md](aws-infrastructure-separation-plan.md)의 Phase 1 결과다. Phase 1에서는 운영 AWS 리소스와 현재 배포를 변경하지 않고, 이후 설정 변경을 판정할 테스트부터 작성한다.

## Phase 1 상태

- 테스트 작성 완료일: `2026-07-13`
- AWS 콘솔 변경: 없음
- 운영 EC2 변경: 없음
- 현재 서비스 재배포: 없음
- 목표 설정 검증 결과: `RED`가 정상

목표 설정 검증이 `RED`인 이유는 아직 `compose.api.prod.yaml`과 API 전용 Nginx 설정을 만들지 않았고, Spring Boot에 관리형 Redis·RabbitMQ 연결 속성을 추가하지 않았기 때문이다. 해당 설정을 구현하기 전에 실패 기준을 먼저 고정했다.

## 추가된 자동 검증

| 파일 | 역할 |
| --- | --- |
| `tools/test_validate_infrastructure_separation.py` | 정적 검증기의 정상·실패 조건 9개 단위 테스트 |
| `tools/validate_infrastructure_separation.py` | Spring 설정, API 전용 Compose, API 전용 Nginx 설정 검사 |
| `apps/api/src/test/java/com/buildgraph/prototype/infra/ManagedInfrastructureSmokeTest.java` | RDS, ElastiCache, Amazon MQ 실제 연결 테스트 |
| `.github/workflows/ci.yml` | 정적 검증기 단위 테스트를 기존 CI에 포함 |

## 정적 검증 계약

### Spring Boot

다음 환경변수를 `application.yml`에서 읽을 수 있어야 한다.

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD

SPRING_DATA_REDIS_HOST
SPRING_DATA_REDIS_PORT
SPRING_DATA_REDIS_USERNAME
SPRING_DATA_REDIS_PASSWORD
SPRING_DATA_REDIS_SSL_ENABLED

SPRING_RABBITMQ_ADDRESSES
SPRING_RABBITMQ_USERNAME
SPRING_RABBITMQ_PASSWORD
SPRING_RABBITMQ_SSL_ENABLED
```

Amazon MQ RabbitMQ Cluster의 `SPRING_RABBITMQ_ADDRESSES`는 다음 형식을 사용한다.

```text
broker-host-1:5671,broker-host-2:5671,broker-host-3:5671
```

`amqps://` 접두사는 넣지 않고 TLS 여부는 `SPRING_RABBITMQ_SSL_ENABLED=true`로 별도 설정한다.

### API 전용 Compose

`compose.api.prod.yaml`에는 다음 서비스만 허용한다.

```text
nginx
api
xgb-reranker
```

다음 서비스는 포함하지 않는다.

```text
web
postgres
redis
rabbitmq
mailpit
```

- `api`는 호스트에 `8080`을 직접 공개하지 않는다.
- 외부 요청은 `nginx:80`만 받는다.
- `api`는 PostgreSQL, Redis, RabbitMQ 컨테이너를 `depends_on`으로 참조하지 않는다.
- `xgb-reranker`는 RDS 연결 환경변수를 받는다.

### API 전용 Nginx

- `/api/*`를 `api:8080`으로 프록시한다.
- `/ws/*`를 `api:8080`으로 프록시하고 WebSocket upgrade header를 전달한다.
- React 정적 파일, `/assets/*`, `index.html`을 제공하지 않는다.
- 실제 설정 파일이 추가되면 컨테이너에서 `nginx -t`를 실행한다.

CloudFront는 AWS 콘솔 수동 관리 대상이므로 정적 파일 검사에 포함하지 않는다. `/api/*`와 `/ws/*`의 캐시·헤더·WebSocket 설정은 CloudFront 전환 단계에서 콘솔 체크리스트와 배포 후 요청으로 검증한다.

## 실제 AWS 연결 테스트

`ManagedInfrastructureSmokeTest`는 다음을 확인한다.

| 테스트 | 확인 내용 |
| --- | --- |
| RDS | JDBC 연결, `vector` extension, 성공한 Flyway migration 존재 |
| ElastiCache | 인증·TLS 연결, 문자열 쓰기/읽기, TTL, 테스트 key 삭제 |
| Amazon MQ | TLS 연결, 임시 Queue publish/consume |
| Amazon MQ 재연결 | 첫 client 연결 종료 후 새 연결 성공 |

기본 실행에서는 모두 skip한다. Private endpoint에 접근할 수 있는 호스트에서 다음 변수가 있을 때만 실행한다.

```text
MANAGED_INFRA_TEST_ENABLED=true
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
SPRING_DATA_REDIS_HOST
SPRING_DATA_REDIS_PORT
SPRING_DATA_REDIS_USERNAME
SPRING_DATA_REDIS_PASSWORD
SPRING_DATA_REDIS_SSL_ENABLED=true
SPRING_RABBITMQ_ADDRESSES
SPRING_RABBITMQ_USERNAME
SPRING_RABBITMQ_PASSWORD
SPRING_RABBITMQ_VIRTUAL_HOST
SPRING_RABBITMQ_SSL_ENABLED=true
```

실제 값은 Git, 문서, 채팅, 터미널 캡처에 남기지 않는다.

실행 명령:

```bash
cd apps/api
./gradlew test --tests com.buildgraph.prototype.infra.ManagedInfrastructureSmokeTest --no-daemon
```

## Phase 1 실행 결과

정적 검증기 단위 테스트:

```text
Ran 9 tests
OK
```

기존 OpenAPI 단위 테스트와 함께 실행:

```text
Ran 12 tests
OK
```

AWS 연결 테스트 로컬 컴파일 결과:

```text
tests=4
skipped=4
failures=0
errors=0
BUILD SUCCESSFUL
```

현재 저장소에 목표 검증기를 실행한 결과는 다음 8개 항목에서 실패한다.

1. Redis port 환경변수 매핑 없음
2. Redis username 환경변수 매핑 없음
3. Redis password 환경변수 매핑 없음
4. Redis TLS enable 환경변수 매핑 없음
5. RabbitMQ cluster addresses 환경변수 매핑 없음
6. RabbitMQ TLS enable 환경변수 매핑 없음
7. `compose.api.prod.yaml` 없음
8. `infra/nginx/api.conf` 없음

이 실패는 설정 구현 전의 테스트 우선 기준선이다. 기존 `compose.prod.yaml`과 운영 서버는 변경하지 않는다.

## 명령 모음

검증기 단위 테스트:

```bash
python3 -m unittest tools.test_validate_infrastructure_separation
```

현재 저장소를 목표 구조와 비교:

```bash
python3 tools/validate_infrastructure_separation.py
```

Phase 2~6에서 설정 구현이 끝난 후 위 명령은 성공해야 한다.

## Phase 1 종료 판정

- [x] 설정 변경 전에 정적 검증 테스트 작성
- [x] RDS·ElastiCache·Amazon MQ 실제 연결 테스트 작성
- [x] 관리형 서비스 연결 테스트가 기본 CI에서 외부 서비스에 접속하지 않도록 차단
- [x] 정적 검증기 단위 테스트를 CI에 포함
- [x] 현재 목표 설정의 실패 기준선 기록
- [ ] API 관리형 서비스 환경변수 매핑 구현
- [ ] API 전용 Compose와 Nginx 설정 구현
- [ ] 실제 AWS endpoint 연결 테스트 통과

아래 세 미완료 항목은 테스트 작성 누락이 아니라 이후 Phase의 구현·실행 항목이다.
