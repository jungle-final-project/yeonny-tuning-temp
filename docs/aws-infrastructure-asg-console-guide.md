# AWS Green API 단계형 Auto Scaling Group 콘솔 가이드

이 문서는 BuildGraph Green API를 현재의 수동 EC2 한 대에서 Launch Template과 EC2 Auto Scaling Group으로 단계적으로 전환하는 작업 가이드다.

> 2026-07-17 운영 결정: WebSocket·PC Agent cross-instance 처리는 구현하지
> 않는다. 따라서 Web ASG는 `Min 1 / Desired 1 / Max 1`을 유지하며,
> 이 문서의 2단계 `Max 3` 내용은 실행 대상이 아닌 보류 기록으로만 남긴다.

이번 가이드의 기존 단계 구분은 다음과 같다.

```text
1단계: Min 1 / Desired 1 / Max 1
       자동 교체와 재현 가능한 부팅만 검증

2단계: Min 1 / Desired 1 / Max 3
       현재 운영 결정으로 미적용
```

ASG를 만들었다는 사실만으로 애플리케이션이 수평 확장 가능한 것은 아니다. 현재 BuildGraph에는 JVM 메모리 기반 WebSocket 상태, EC2 로컬 Docker volume, 특정 EC2 ID를 대상으로 하는 배포 workflow가 남아 있다. 이 문제를 해결하지 않고 `Max`를 2 이상으로 올리면 HTTP 요청은 분산되더라도 WebSocket push, PC Agent 진단, 파일 조회, 모델 일관성, 배포 버전이 깨질 수 있다.

이 문서는 AWS Management Console 작업을 중심으로 설명하며, 검증용 AWS CLI는 `describe`, `get`, `list` 계열의 읽기 전용 명령만 제공한다.

> 확인 기준일: 2026-07-15  
> AWS 계정: `443915990705`  
> 리전: `ap-northeast-2` 서울  
> 이 문서 작성 중 AWS 리소스 변경: 없음

---

## 1. 목표 구조

### 1.1 현재 구조

```text
CloudFront
  → ALB
    → Target Group
      → 수동 Green EC2 1대
        → Nginx 80
          → API 8080
          → XGB 8091
```

ALB는 만들어졌지만 Target이 한 대라서 처리 용량과 애플리케이션 가용성은 아직 증가하지 않았다.

### 1.2 1단계 완료 구조

```text
CloudFront
  → ALB
    → Target Group
      → ASG: Min 1 / Desired 1 / Max 1
        → Green EC2 1대
          → Nginx 80
            → API 8080
            → XGB 8091

기존 수동 Green EC2 + EIP
  → 롤백 대기용으로 유지
```

1단계의 목적은 부하에 따라 대수가 늘어나는지 확인하는 것이 아니다.

- Launch Template으로 동일한 서버가 재생성되는지 확인한다.
- 인스턴스 ID가 바뀌어도 SSM, Secret, ECR, Docker, CloudWatch가 복구되는지 확인한다.
- ASG가 종료된 인스턴스를 한 대로 복원하는지 확인한다.
- 기존 수동 Green EC2를 즉시 삭제하지 않고 롤백 경로로 보존한다.

### 1.3 2단계 보류 구조

```text
CloudFront
  → ALB
    → Target Group
      → ASG: Min 1 / Desired 1 / Max 3
        ├─ EC2 ap-northeast-2a
        ├─ EC2 ap-northeast-2b
        └─ 필요 시 세 번째 EC2

공유 상태
  ├─ RDS PostgreSQL
  ├─ ElastiCache Redis
  ├─ Amazon MQ RabbitMQ
  ├─ WebSocket fan-out 또는 공유 session routing
  ├─ S3/EFS 또는 immutable model artifact
  └─ 단일 scheduler/worker
```

`Desired 1`에서는 두 AZ Subnet을 선택해도 실제 인스턴스는 한 AZ에만 존재한다. 두 AZ에 동시에 한 대씩 유지하려면 `Min 2 / Desired 2`가 필요하며 비용도 두 대분이 고정 발생한다.

---

## 2. 현재 AWS 확정값

2026-07-15 AWS CLI 읽기 전용 조회 결과다.

### 2.1 공통 자원

| 항목 | 현재 값 |
| --- | --- |
| AWS Account | `443915990705` |
| Region | `ap-northeast-2` |
| VPC | `buildgraph-demo-vpc` / `vpc-06c90b864a62f93a4` |
| ALB | `buildgraph-demo-api-green-alb` |
| ALB ARN suffix | `app/buildgraph-demo-api-green-alb/c2a7e4158cc0ffd7` |
| ALB SG | `buildgraph-demo-alb-green-sg` / `sg-0f3f05d71747114a2` |
| Target Group | `buildgraph-demo-api-green-tg` |
| Target Group ARN | `arn:aws:elasticloadbalancing:ap-northeast-2:443915990705:targetgroup/buildgraph-demo-api-green-tg/f905e7669645a411` |
| Target type | `instance` |
| Target protocol | HTTP / port `80` / `HTTP1` |
| Health check | HTTP `/api/health`, matcher `200` |
| Deregistration delay | `300초` |
| Routing algorithm | `round_robin` |
| Target stickiness | 비활성 |
| 기존 공유 EC2 SG | `buildgraph-demo-ec2-sg` / `sg-099aac782b77a854e` |

### 2.2 기존 수동 Green EC2

| 항목 | 현재 값 |
| --- | --- |
| Name | `buildgraph-demo-api-green-ec2` |
| Instance ID | `i-033105106a7970ac1` |
| Type | `t3.medium` |
| Architecture | `x86_64` |
| OS AMI | `ami-0e4ab31f1847c850c`, Ubuntu 24.04 |
| AZ | `ap-northeast-2b` |
| Subnet | `subnet-0db73cf18a85ea8f1` |
| EIP | `43.203.33.190` |
| IAM Instance Profile | `buildgraph-demo-api-green-role` |
| EBS | encrypted gp3 `30 GiB`, 3000 IOPS, 125 MiB/s |
| Detailed monitoring | 활성 |
| IMDS | V2 required, hop limit `2` |

현재 Green Role에는 다음 권한이 연결돼 있다.

- `AmazonSSMManagedInstanceCore`
- `CloudWatchAgentServerPolicy`
- `AmazonEC2ContainerRegistryReadOnly`
- `buildgraph/demo-green/api-env` 한 개를 읽는 inline policy

### 2.3 ASG에서 사용할 Subnet

| AZ | 이름 | Subnet ID | Public route | Public IPv4 자동 할당 |
| --- | --- | --- | --- | --- |
| 2a | `buildgraph-demo-subnet-public1-ap-northeast-2a` | `subnet-0b48bd72162060261` | IGW | 비활성 |
| 2b | `buildgraph-demo-subnet-public2-ap-northeast-2b` | `subnet-0db73cf18a85ea8f1` | IGW | 비활성 |

현재 VPC에는 NAT Gateway가 없다. Private Data Subnet에는 인터넷 기본 경로가 없고 RDS·Redis·RabbitMQ용이므로 ASG 애플리케이션 인스턴스를 넣지 않는다.

따라서 1단계에서는 다음 조건을 사용한다.

```text
ASG Subnet: 기존 Public 2a + Public 2b
Launch Template: Auto-assign public IPv4 = Enable
Inbound: ALB SG의 TCP 80만 허용
SSH: 허용하지 않음
관리 접속: SSM만 사용
```

ASG 인스턴스의 Public IPv4는 고정 EIP가 아니며 교체될 때 바뀐다. CloudFront Origin은 ALB이므로 인스턴스 고정 IP가 필요하지 않다.

향후 Public IPv4를 제거하려면 별도의 Private App Subnet과 다음 중 하나가 필요하다.

- NAT Gateway
- ECR API/DKR, S3, SSM, EC2 Messages, SSM Messages, Secrets Manager, CloudWatch Logs 등의 VPC Endpoint 조합

현재 존재하는 Amazon MQ requester-managed endpoint 하나만으로는 ECR pull·Secret 조회·SSM 연결을 해결할 수 없다.

현재 ALB의 CloudFront origin 검증용 custom header와 listener 기본 `403`은 연기된 상태다. 자세한 예외와 후속 절차는 [AWS Green API ALB 추가 콘솔 가이드](aws-infrastructure-alb-console-guide.md)의 9번·13.1을 따른다. ASG 1단계 작업이 이 보안 후속 작업을 완료한 것으로 오해해서는 안 된다.

### 2.4 아직 존재하지 않는 자원

- BuildGraph Launch Template: 없음
- BuildGraph Auto Scaling Group: 없음
- BuildGraph CloudWatch Alarm: 없음

---

## 3. 단계별 용량값

| 상태 | Min | Desired | Max | Scaling policy | 목적 |
| --- | ---: | ---: | ---: | --- | --- |
| ASG 생성 직후 | 0 | 0 | 1 | 없음 | 설정 검토 중 오발진 방지 |
| 1단계 최종 | 1 | 1 | 1 | 없음 | 자동 교체와 bootstrap 검증 |
| 2단계 부하 테스트 | 1 | 1 | 3 | scale-out 우선 | 실제 수평 확장 검증 |
| 2단계 HA 선택안 | 2 | 2 | 3 이상 | 검증된 정책 | 두 AZ 상시 가용성 |

`Min 1 / Desired 1 / Max 1`은 ASG 관리 상태지만 부하 기반 확장은 하지 않는다. `Max`를 2 이상으로 올리는 순간부터 아래 4장에 정리한 다중 인스턴스 하드 게이트가 모두 적용된다.

---

## 4. 2단계 전 필수 하드 게이트

다음 항목을 해결하지 않은 상태에서는 `Max`를 2 이상으로 변경하지 않는다.

### 4.1 WebSocket 인스턴스 간 전달

현재 다음 상태가 API JVM 메모리에 있다.

- Support Chat WebSocket session map
- 관리자 queue WebSocket session set
- PC Agent diagnosis WebSocket session map
- PC Agent diagnosis pending response/status/result map

문제 상황:

```text
PC Agent WebSocket → EC2 A
사용자의 진단 REST 요청 → EC2 B

EC2 B에는 Agent socket이 없으므로 진단 요청 전달 실패
```

ALB stickiness로 해결할 수 없다. PC Agent와 브라우저는 서로 다른 클라이언트이므로 같은 Target으로 고정된다는 보장이 없다.

2단계 전 다음 중 하나를 구현하고 테스트한다.

- Redis Pub/Sub를 이용한 WebSocket event fan-out과 device/session routing
- WebSocket gateway를 별도 단일 서비스로 분리
- 동등한 인스턴스 간 message routing 구조

Support Chat의 REST polling fallback은 push 누락을 늦게 복구할 뿐 PC Agent의 5초 응답 계약을 보장하지 못한다.

통과 조건:

- [ ] WebSocket이 EC2 A에 연결된 상태에서 REST 변경 요청을 EC2 B로 보내도 push가 도착한다.
- [ ] PC Agent가 EC2 A에 연결된 상태에서 진단 REST 요청을 EC2 B로 보내도 5초 안에 응답한다.
- [ ] 인스턴스 종료 후 client가 다른 Target으로 재연결한다.
- [ ] 중복 frame과 재전달 idempotency 테스트가 통과한다.

### 4.2 로컬 volume 제거 또는 재생성 계약

현재 Compose는 다음 로컬 volume을 사용한다.

```text
agent-log-data        → /data/agent-logs
recommendation-models → /models
```

ASG scale-in과 instance replacement는 로컬 volume을 함께 제거한다. 다른 인스턴스는 해당 파일을 볼 수 없다.

2단계 전 다음을 확정한다.

- Agent 업로드 로그: S3 같은 공유 object storage로 이전
- XGB model: 이미지에 포함하거나 S3/EFS에서 immutable version을 내려받음
- DB에는 로컬 절대경로 대신 공유 object key/model version만 저장
- 새 인스턴스가 과거 로컬 volume 없이도 Healthy가 됨

통과 조건:

- [ ] 한 인스턴스에서 업로드한 로그를 다른 인스턴스에서 조회할 수 있다.
- [ ] 새 인스턴스가 동일 model version과 checksum을 사용한다.
- [ ] 인스턴스 교체 뒤에도 DB row가 가리키는 artifact가 유효하다.

### 4.3 Scheduler 단일 실행

프로젝트에는 `BUILDGRAPH_SCHEDULING_ENABLED`로 전체 `@Scheduled` 등록을 끄는 코드가 있다. `compose.api.ecr.prod.yaml`은 이 환경변수를 API 컨테이너에 전달하며, 기본값 `true`로 기존 수동 Green 동작을 보존한다. ASG bootstrap은 별도 runtime env에서 반드시 `false`를 주입한다.

ASG web-facing 인스턴스에서는 반드시 다음 상태가 돼야 한다.

```text
BUILDGRAPH_SCHEDULING_ENABLED=false
```

별도의 singleton worker만 다음 상태를 사용한다.

```text
BUILDGRAPH_SCHEDULING_ENABLED=true
```

2026-07-16 코드 준비 상태:

1. bootstrap 계약·실행 테스트를 먼저 작성했다.
2. `compose.api.ecr.prod.yaml`의 API environment에 `BUILDGRAPH_SCHEDULING_ENABLED` 전달을 추가했다.
3. release manifest와 Compose render에서 `false` 전달을 검증했다.
4. 실제 Builder/ASG API 컨테이너에서 모든 `@Scheduled` 비활성 상태를 확인해야 한다.
5. singleton scheduler worker 또는 모든 job의 분산 lock은 별도 작업으로 준비해야 한다.

가격·추천 cron 중 일부는 advisory lock이 있지만 cache prewarm과 tier snapshot은 advisory lock이 없어 인스턴스 수만큼 외부 AI 호출과 비용이 증가할 수 있다.

통과 조건:

- [ ] ASG web 인스턴스 두 대에서 scheduled job 실행 수가 0이다.
- [ ] singleton worker 한 곳에서만 scheduled job이 실행된다.
- [ ] 배포·재시작 중에도 같은 job이 중복 실행되지 않는다.

### 4.4 재현 가능한 bootstrap과 배포

ASG의 인스턴스 ID는 교체될 때마다 바뀐다. 따라서 Repository Variable이나
workflow 입력에 저장한 `GREEN_EC2_INSTANCE_ID`로 SSM command를 보내는 고정
인스턴스 배포는 사용하지 않는다.

승인된 배포 방식은 다음 두 가지다.

1. `Fast Deploy Green Web ASG`: `1/1/1` ASG에서 현재의 단일 Healthy
   인스턴스를 매번 동적으로 찾아 선택한 컨테이너만 제자리에서 교체한다. 짧은
   중단을 허용하며 Instance Refresh와 CloudFront 전환은 수행하지 않는다.
2. `Release Green Web ASG`: 새 숫자형 Launch Template version과 `100/200`
   Instance Refresh로 인스턴스를 교체하는 immutable release다.

재현 가능한 immutable release 흐름은 다음과 같다.

```text
Git SHA 검증
→ API/XGB immutable ECR image publish
→ 검증된 AMI 또는 bootstrap release manifest 확정
→ Launch Template 새 version 생성
→ ASG가 해당 version 사용
→ Instance Refresh
→ Target Healthy와 smoke 확인
→ 실패 시 이전 Launch Template version으로 rollback
```

주의:

- Fast Deploy는 ASG가 정확히 `1/1/1`이고 단일 Target이 Healthy일 때만 허용한다.
  여러 인스턴스를 대상으로 SSM `docker compose up`을 실행하지 않는다.
- Fast Deploy도 성공한 release manifest를 새 숫자형 Launch Template version에
  기록하고 ASG 포인터를 전진시켜, 이후 교체 인스턴스가 이전 버전으로 돌아가지
  않게 한다.
- Launch Template의 `$Latest`를 자동으로 따라가지 말고 검증된 특정 version을 사용한다.
- `nginx:1.27-alpine`은 mutable tag다. ASG 재현성을 위해 digest로 고정하거나 전용 ECR에 mirror한다.
- User data에 Secret value, GitHub token, DB password를 넣지 않는다.

통과 조건:

- [ ] 임의의 새 EC2가 사람의 SSH 작업 없이 Healthy가 된다.
- [ ] 새 EC2의 Git SHA와 API/XGB image URI·digest가 release manifest와 일치한다.
- [ ] workflow가 고정 Instance ID를 요구하지 않는다.
- [ ] Fast Deploy 후 기존 EC2 ID는 그대로이고 ASG의 숫자형 Launch Template
      포인터만 새 release version으로 전진한다.
- [ ] 이전 Launch Template version으로 교체하는 rollback drill을 완료한다.

### 4.5 Liveness와 readiness 분리

현재 Target Group의 `/api/health`는 DB 연결 실패 시 `503`을 반환한다. ALB readiness로는 적합하지만 ASG replacement 판단에 그대로 연결하면 공유 RDS 장애 때 모든 EC2를 반복 교체할 수 있다.

초기 ASG 설정은 다음과 같이 둔다.

```text
ALB Target Group readiness: /api/health 유지
ASG health check type: EC2만 사용
ELB health check replacement: 아직 활성화하지 않음
```

추후 다음을 구현한다.

- liveness: JVM/API process 상태, DB 비의존
- readiness: 요청 처리에 필요한 DB·Redis 등 의존성 포함
- 컨테이너가 liveness 실패 시 먼저 Docker/systemd가 재시작
- 반복 실패 시 custom health 또는 검증된 방식으로 ASG replacement

`/healthz`를 ALB readiness로 바꿔 억지로 Healthy를 만들지 않는다.

### 4.6 DB connection budget

다음 값을 실제 측정해 기록한다.

```text
ASG 최대 EC2 수 × EC2당 Hikari maximum pool size
+ singleton worker connection
+ migration/admin 여유
< RDS 안전 connection 한도
```

목표는 전체 잠재 connection을 DB 한도의 70~80% 이내로 두는 것이다.

현재 `application.yml`에는 Hikari pool 크기가 명시돼 있지 않다. `docs/AWS_LOAD_TEST_PREFLIGHT.md`의 Hikari 10/15 비교를 먼저 수행한 뒤 Max 3 허용 여부를 결정한다.

통과 조건:

- [ ] 인스턴스 수 1·2·3에서 Hikari active/pending/timeout을 기록했다.
- [ ] RDS DatabaseConnections, CPU, FreeableMemory가 안전 범위다.
- [ ] RDS 장애 때 EC2 replacement storm이 발생하지 않는다.

---

## 5. 작업 중 절대 하지 않는 것

1. 기존 수동 Green EC2와 EIP를 ASG 검증 전에 삭제하지 않는다.
2. Launch Template 생성만으로 현재 EC2의 설치 파일과 Docker volume이 복제된다고 가정하지 않는다.
3. Production Secret이 남은 EC2 root volume을 검토 없이 AMI로 복제·공유하지 않는다.
4. Launch Template user data에 Secret value를 입력하지 않는다.
5. 기존 Blue와 Green이 공유하는 `sg-099aac782b77a854e`를 ASG에 그대로 연결하지 않는다.
6. Data 전용 Private Subnet을 ASG 애플리케이션 Subnet으로 선택하지 않는다.
7. NAT나 필요한 VPC Endpoint 없이 Public IPv4를 끄지 않는다.
8. 1단계에서 dynamic scaling policy를 만들지 않는다.
9. 다중 인스턴스 하드 게이트 전 `Max`를 2 이상으로 올리지 않는다.
10. Sticky session을 PC Agent routing 해결책으로 간주하지 않는다.
11. Target Group health path를 `/healthz`로 바꾸지 않는다.
12. `/api/health`의 DB 장애를 이유로 모든 EC2를 교체하도록 ELB health check를 즉시 켜지 않는다.
13. 부하 테스트 중 배포, Instance Refresh, AMI 교체를 실행하지 않는다.
14. ASG 인스턴스를 단순히 Stop해서 비용을 줄이려 하지 않는다. ASG는 Desired capacity를 맞추기 위해 대체 인스턴스를 시작한다.
15. 롤백 전에 ASG, Launch Template, AMI, 기존 수동 EC2를 삭제하지 않는다.

---

## 6. 작업 전 하드 게이트

### 6.1 현재 ALB 정리

- [ ] ALB `Active`
- [ ] Target `Healthy`
- [ ] CloudFront `/api/*`, `/ws/*`가 ALB origin을 사용
- [ ] CloudFront `Deployed`
- [ ] ALB SG의 작업자 Public IPv4 `/32` 임시 rule 삭제
- [ ] ALB SG inbound에 `0.0.0.0/0` 없음
- [ ] 기존 EC2 origin 보존
- [ ] 기존 수동 Green EC2와 EIP 정상
- [ ] custom header·listener 기본 `403` 미적용 예외의 담당자와 후속 일정 기록

2026-07-15 조회 시 ALB SG에는 작업자 `/32` 임시 rule이 아직 남아 있었다. 이 rule을 삭제하기 전 ASG 작업을 시작하지 않는다.

### 6.2 배포 artifact

- [ ] 배포할 40자리 Git SHA 확정
- [x] API ECR image URI와 digest 기록
- [x] XGB ECR image URI와 digest 기록
- [x] Nginx image digest 또는 사내 ECR mirror 확정
- [x] bootstrap script 테스트 통과
- [ ] 새 인스턴스에서 Secret value가 로그에 출력되지 않음
- [ ] `BUILDGRAPH_SCHEDULING_ENABLED=false`가 실제 API 컨테이너에 전달됨

2026-07-16 로컬 코드 준비 결과:

- API: `443915990705.dkr.ecr.ap-northeast-2.amazonaws.com/buildgraph-demo-api-green@sha256:aae7ad00b42e07b372a03aa12f6b354d4c6c0f3a780b1d1f8d91d52ef3d9b267`
- XGB: `443915990705.dkr.ecr.ap-northeast-2.amazonaws.com/buildgraph-demo-xgb-reranker-green@sha256:d71bd6a390c747b306c3e9bfd302059d04ec691b755c2abc90115b797bf565a9`
- Nginx Linux x86_64: `docker.io/library/nginx@sha256:62223d644fa234c3a1cc785ee14242ec47a77364226f1c811d2f669f96dc2ac8`
- `tools/test_bootstrap_green_asg.py`에서 정상 실행, 재실행, 계정·리전 불일치, Secret 조회 실패, health timeout, Secret 비노출을 검증했다.
- 실제 40자리 Git SHA는 이 변경을 commit하고 `main`에 반영한 뒤 확정한다.
- Secret 비노출과 scheduler 비활성의 체크박스는 로컬 fake 실행만으로 완료 처리하지 않는다. 새 Builder EC2의 cloud-init/journal과 실제 API 컨테이너를 확인한 뒤 체크한다.

### 6.3 담당자와 변경 동결

- [ ] CloudFront rollback 담당자
- [ ] ASG/Launch Template 담당자
- [ ] 애플리케이션 smoke 담당자
- [ ] CloudWatch 관측 담당자
- [ ] 작업 중 Green API/XGB workflow 중지
- [ ] 가격·메일·외부 AI scheduled job 처리 방침 확정

---

## 7. ASG 전용 EC2 Security Group 생성

기존 `buildgraph-demo-ec2-sg`는 Blue와 수동 Green이 공유하고 TCP 80·22가 `0.0.0.0/0`에 열려 있다. ASG에 재사용하지 않는다.

### 7.1 새 SG 생성

EC2 Console → Security Groups → Create security group을 연다.

| 항목 | 값 |
| --- | --- |
| Name | `buildgraph-demo-api-green-asg-sg` |
| Description | `BuildGraph Green ASG instances behind ALB` |
| VPC | `vpc-06c90b864a62f93a4` |

Inbound:

| Type | Protocol | Port | Source |
| --- | --- | ---: | --- |
| HTTP | TCP | 80 | `buildgraph-demo-alb-green-sg` / `sg-0f3f05d71747114a2` |

다음 inbound는 만들지 않는다.

- SSH 22
- API 8080
- XGB 8091
- `0.0.0.0/0`

초기 outbound:

| Type | Protocol | Port | Destination | 목적 |
| --- | --- | ---: | --- | --- |
| HTTPS | TCP | 443 | `0.0.0.0/0` | ECR, S3 image layer, Secrets Manager, SSM, CloudWatch, GitHub, 외부 HTTPS API |
| PostgreSQL | TCP | 5432 | `buildgraph-demo-rds-sg` | RDS |
| Custom TCP | TCP | 6379 | `buildgraph-demo-redis-sg` | Redis TLS |
| Custom TCP | TCP | 5671 | `buildgraph-demo-rabbitmq-sg` | RabbitMQ AMQPS |

Runtime bootstrap이 HTTP package repository를 실제로 사용한다면 TCP 80 outbound 필요성을 별도로 검토한다. 검증되지 않은 편의를 위해 전체 프로토콜 outbound를 추가하지 않는다.

Tags:

| Key | Value |
| --- | --- |
| Name | `buildgraph-demo-api-green-asg-sg` |
| Stack | `green` |
| Service | `api` |
| ManagedBy | `asg` |

생성한 SG ID를 기록한다.

### 7.2 기존 SG들에 병렬 전환 rule 추가

새 ASG SG를 `<ASG_EC2_SG_ID>`로 표기한다. 실제 ID를 확인해서 입력하고 문자열 자체를 입력하지 않는다.

ALB SG `sg-0f3f05d71747114a2` outbound에 추가:

| Type | Port | Destination |
| --- | ---: | --- |
| HTTP | 80 | `<ASG_EC2_SG_ID>` |

Data SG inbound에 추가:

| SG | Port | Source |
| --- | ---: | --- |
| `sg-0587fdbc766f9088f` RDS | 5432 | `<ASG_EC2_SG_ID>` |
| `sg-0dc3c8766358e57f4` Redis | 6379 | `<ASG_EC2_SG_ID>` |
| `sg-0876855a9ac1da572` RabbitMQ | 5671 | `<ASG_EC2_SG_ID>` |

이 단계에서는 기존 공유 SG source와 ALB SG의 기존 공유 SG destination을 삭제하지 않는다. 기존 수동 Green rollback 경로에 필요하다.

---

## 8. 재현 가능한 AMI와 bootstrap 준비

### 8.1 Launch Template과 AMI의 차이

`Create template from instance`는 인스턴스 타입, SG, IAM 같은 launch parameter를 복사하지만 현재 EC2 root disk의 설치 상태를 복제하지 않는다.

현재 설치된 Docker, `/opt/buildgraph/prototype`, CloudWatch Agent, image manifest까지 복제하려면 다음 중 하나가 필요하다.

1. 검증된 custom AMI
2. base AMI에서 모든 설치와 배포를 완료하는 idempotent user data/bootstrap

권장 방식은 Secret이 없는 disposable builder에서 custom AMI를 만들고, runtime Secret과 image URI는 launch 시점에 주입하는 것이다.

### 8.2 기존 Production Green을 바로 AMI로 만들지 않는 이유

현재 root disk에는 다음 파일이 존재할 수 있다.

- `/opt/buildgraph/prototype/.env.prod`
- `/opt/buildgraph/green-images.env`
- Docker image와 container layer
- 배포 임시 파일과 로그
- Agent 로그와 recommendation model volume

기존 Green에서 만든 AMI는 이 데이터를 snapshot에 포함한다. 해당 AMI를 사용해야 하는 긴급 demo 예외라면 private·encrypted 상태를 유지하고 Secret-bearing artifact로 취급해야 한다. 공유하거나 Public AMI로 바꾸지 않으며, 깨끗한 bootstrap이 완성되면 폐기한다.

AMI 생성 시 기본 reboot를 허용하면 파일 시스템 일관성이 높지만 현재 단일 Green에 짧은 중단이 발생할 수 있다. `No reboot`는 중단을 줄이지만 AWS가 파일 시스템 일관성을 보장하지 않는다. 운영 Green이 아닌 disposable builder 사용을 권장하는 이유다.

### 8.3 Disposable builder 기준

Builder는 사용자 트래픽을 받지 않는다.

| 항목 | 값 |
| --- | --- |
| Base AMI | 검증된 Ubuntu 24.04 x86_64 AMI |
| Instance type | `t3.medium` 또는 설치 검증에 충분한 임시 타입 |
| Subnet | Public 2a 또는 2b |
| Public IPv4 | Enable |
| SG | ASG 전용 SG, Target Group 미등록 상태 |
| IAM Profile | `buildgraph-demo-api-green-role` |
| Root EBS | encrypted gp3 30 GiB |

ASG 전용 SG에는 ALB SG source의 TCP 80 inbound rule이 있지만, Builder를 Target Group에 등록하지 않으므로 ALB 사용자 트래픽은 Builder로 전달되지 않는다.

Builder 준비는 `tools/prepare_green_asg_builder.sh`를 SSM Run Command로 실행해 재현한다. script에는 main에 병합된 40자리 Git SHA를 하나만 전달한다.

```bash
sudo bash /tmp/prepare_green_asg_builder.sh "실제_40자리_MAIN_SHA"
```

script는 [Phase 6 Docker·CloudWatch 설치 절차](aws-infrastructure-phase-6-console-guide.md)의 다음 항목만 준비한다.

- SSM Agent
- Docker Engine과 Compose plugin
- AWS CLI, Git, jq, curl
- CloudWatch Agent와 설정
- `/opt/buildgraph/prototype`의 확정 Git SHA
- 실행에 필요한 Compose와 Nginx 설정
- 검증된 bootstrap script

다음 조건도 함께 검증한다.

- Ubuntu `24.04`, `x86_64`, AWS account `443915990705`, region `ap-northeast-2`
- Git HEAD가 입력한 SHA와 정확히 일치하고 해당 SHA가 `origin/main`의 조상
- Docker image와 container가 모두 0개
- `.env.prod`, runtime image manifest, AWS credentials 파일이 없음
- 같은 SHA로 다시 실행해도 결과가 동일함

Builder에는 다음을 넣지 않는다.

- `.env.prod`
- Secret value
- ECR login password
- 사용자 업로드 파일
- runtime model volume

AMI 생성 전 user data 흔적과 bootstrap log에 Secret이 없는지 확인한다. AWS 공식 문서에 따라 `/var/lib/cloud/instances/` 아래에 user data 사본이 남을 수 있으므로 Secret을 user data에 넣어서는 안 된다. Builder 검증을 마친 뒤에만 package cache와 shell history를 정리하고 `cloud-init clean --logs --machine-id`를 실행한다. 이 정리 이후에는 Builder를 재부팅하거나 다른 용도로 사용하지 않고 reboot를 허용하는 AMI 생성으로 바로 진행한다.

AMI 이름 예시:

```text
buildgraph-demo-api-green-asg-<12자리_GIT_SHA>-YYYYMMDD-HHMM
```

AMI가 `Available`이 된 뒤 AMI ID와 연결 snapshot ID를 기록한다. Builder는 AMI launch 검증이 끝난 뒤 종료한다.

### 8.4 Bootstrap contract

테스트를 먼저 작성한 뒤 다음 코드로 구현했다.

- 실행 script: `tools/bootstrap_green_asg.sh`
- 계약·실행 테스트: `tools/test_bootstrap_green_asg.py`
- Builder 준비 script: `tools/prepare_green_asg_builder.sh`
- Builder 준비 테스트: `tools/test_prepare_green_asg_builder.py`
- immutable image release manifest: `infra/asg/green-release.env`
- Compose 전달 계약: `compose.api.ecr.prod.yaml`

기존 수동 Green 배포 script와의 호환성을 위해 runtime 파일을 나눈다.

- `/opt/buildgraph/green-images.env`: 기존 계약대로 `API_IMAGE_URI`, `XGB_IMAGE_URI`만 기록
- `/opt/buildgraph/asg-runtime.env`: `NGINX_IMAGE_URI`, `BUILDGRAPH_SCHEDULING_ENABLED=false` 기록
- `/opt/buildgraph/prototype/.env.prod`: Secrets Manager의 dotenv 원문을 mode `600`, owner `ubuntu:ubuntu`로 저장

bootstrap은 첫 부팅에서 다음 순서를 모두 만족해야 한다.

1. `set -Eeuo pipefail`, `umask 077` 적용
2. AWS region/account 검증
3. IMDSv2와 IAM Role 사용
4. Secrets Manager의 `buildgraph/demo-green/api-env` 조회
5. Secret 원문을 stdout/cloud-init log에 출력하지 않음
6. `.env.prod`를 `600 ubuntu:ubuntu`로 저장
7. 확정된 API/XGB/Nginx immutable image를 manifest에 기록
8. `BUILDGRAPH_SCHEDULING_ENABLED=false` 적용
9. ECR login과 image pull
10. `docker compose config --quiet`
11. `nginx`, `api`, `xgb-reranker` 시작
12. `nginx -t`
13. `http://127.0.0.1/api/health`가 200이 될 때까지 제한된 재시도
14. CloudWatch Agent 시작
15. 성공 marker와 Git/image digest만 기록
16. 실패 시 non-zero 종료하고 Secret·token을 로그에 남기지 않음

로컬 계약·실행 테스트:

```bash
python3 -m unittest tools.test_bootstrap_green_asg
python3 -m unittest tools.test_prepare_green_asg_builder
bash -n tools/bootstrap_green_asg.sh
bash -n tools/prepare_green_asg_builder.sh
```

이 코드가 포함된 commit을 `main`에 반영하고 Builder 준비 script로 해당 SHA를 checkout한다. runtime bootstrap은 Builder에서 실행하지 않고 Custom AMI로 만든 검증용 EC2의 user data에서 처음 실행한다. Builder에는 Secret과 실행 image layer를 남기지 않는다.

최초 부팅 시작부터 Target `Healthy`까지 걸린 시간을 기록한다. 이 값이 ASG default instance warmup과 health check grace period의 근거다.

---

## 9. Launch Template 생성

EC2 Console → Launch Templates → Create launch template을 연다.

### 9.1 기본값

| 항목 | 값 |
| --- | --- |
| Launch template name | `buildgraph-demo-api-green-lt` |
| Template version description | `stage1-<12자리_GIT_SHA>-<AMI_ID>` |
| Auto Scaling guidance | 체크 |
| AMI | 8번에서 검증한 custom AMI |
| Instance type | `t3.medium` |
| Key pair | 포함하지 않음, SSM만 사용 |
| Security Group | `buildgraph-demo-api-green-asg-sg`만 선택 |
| IAM Instance Profile | `buildgraph-demo-api-green-role` |

기존 공유 EC2 SG, ALB SG, default VPC SG를 Launch Template에 연결하지 않는다.

### 9.2 Network interface

| 항목 | 값 |
| --- | --- |
| Device index | `0` |
| Subnet | Launch Template에 고정하지 않음 |
| Auto-assign public IPv4 | `Enable` |
| Delete on termination | 활성 |
| Security Group | ASG 전용 EC2 SG |

Subnet은 ASG가 2a·2b 중 선택하도록 Launch Template에서 고정하지 않는다.

Public IPv4를 끄려면 NAT 또는 필수 VPC Endpoint 준비를 먼저 완료해야 한다.

### 9.3 Storage

| 항목 | 값 |
| --- | --- |
| Root device | AMI 기준 `/dev/sda1` |
| Type | gp3 |
| Size | 30 GiB 이상, 실제 peak 기준 검토 |
| IOPS | 3000 |
| Throughput | 125 MiB/s |
| Encryption | 활성 |
| Delete on termination | 활성 |

로컬 volume은 instance replacement 시 삭제되는 전제로 사용한다.

### 9.4 Advanced details

| 항목 | 값 |
| --- | --- |
| Shutdown behavior | `Terminate` |
| Termination protection | 비활성 |
| Detailed CloudWatch monitoring | 활성 |
| Metadata accessible | Enabled |
| Metadata version | V2 only, token required |
| Metadata hop limit | `2` |
| User data | 검증된 bootstrap만 입력 |

User data에 실제 Secret, password, token을 붙여 넣지 않는다. Console 캡처와 Launch Template 전체 출력에도 주의한다.

### 9.5 Tags

Instance와 Volume 모두에 적용한다.

| Key | Value |
| --- | --- |
| Name | `buildgraph-demo-api-green-asg-ec2` |
| Stack | `green` |
| Service | `api` |
| ManagedBy | `asg` |

Launch Template 생성 후 version number를 기록한다. ASG에서는 `$Latest` 대신 이 특정 version을 선택한다.

---

## 10. 1단계 ASG 생성

EC2 Console → Auto Scaling Groups → Create Auto Scaling group을 연다.

### 10.1 Launch Template

| 항목 | 값 |
| --- | --- |
| Auto Scaling group name | `buildgraph-demo-api-green-asg` |
| Launch Template | `buildgraph-demo-api-green-lt` |
| Version | 9번에서 검증한 특정 version |

### 10.2 Network

| 항목 | 값 |
| --- | --- |
| VPC | `vpc-06c90b864a62f93a4` |
| AZ/Subnet 1 | `ap-northeast-2a` / `subnet-0b48bd72162060261` |
| AZ/Subnet 2 | `ap-northeast-2b` / `subnet-0db73cf18a85ea8f1` |
| AZ distribution | Balanced best effort |

Private Data 2a·2b는 선택하지 않는다.

### 10.3 Load balancing

| 항목 | 값 |
| --- | --- |
| Attach to an existing load balancer | 선택 |
| Target Group | `buildgraph-demo-api-green-tg` |
| VPC Lattice | 사용하지 않음 |

ASG가 Target Group에 연결되면 새 인스턴스는 자동 등록되고 scale-in 인스턴스는 자동 deregistration된다. Lambda로 수동 등록 코드를 만들지 않는다.

### 10.4 Health checks

| 항목 | 1단계 값 |
| --- | --- |
| EC2 health check | 활성, 기본 |
| ELB health check replacement | 비활성 |
| EBS health check | 초기에는 선택하지 않음 |
| Health check grace period | 측정한 bootstrap 시간 + 60초 |

Console 기본값 300초를 사용하려면 실제 Target Healthy 시간이 240초 이내인지 먼저 확인한다. 실제 측정 없이 지나치게 짧게 줄이지 않는다.

Target Group은 계속 `/api/health`로 readiness를 판단한다. ASG replacement는 우선 EC2 상태만 사용한다.

### 10.5 초기 Group size

ASG 생성 화면에서는 다음 값을 사용한다.

| 항목 | 값 |
| --- | ---: |
| Desired capacity | `0` |
| Min desired capacity | `0` |
| Max desired capacity | `1` |

설정 검토가 끝나기 전에 인스턴스가 자동 시작되는 것을 막기 위한 임시값이다. 1단계 검증에서 `1/1/1`로 변경한다.

### 10.6 Automatic scaling

```text
No scaling policies
```

1단계에서는 CPU target tracking, request count policy, predictive scaling, scheduled scaling을 만들지 않는다.

### 10.7 Instance maintenance·protection

- Instance maintenance policy: 1단계에서는 설정하지 않는다.
- New instance scale-in protection: 비활성
- Capacity Rebalancing: Spot을 사용하지 않으므로 비활성
- Purchase option: On-Demand 한 종류
- Warm pool: 만들지 않음

### 10.8 Monitoring·notification

- ASG group metrics collection을 활성화한다.
- SNS topic이 이미 승인돼 있다면 launch/terminate/fail notification을 연결한다.
- 임의의 신규 이메일 구독을 팀 합의 없이 만들지 않는다.

### 10.9 Tags

| Key | Value | Propagate to instances |
| --- | --- | --- |
| Name | `buildgraph-demo-api-green-asg` | Yes |
| Stack | `green` | Yes |
| Service | `api` | Yes |
| ManagedBy | `asg` | Yes |

Summary에서 `Desired 0 / Min 0 / Max 1`인지 확인하고 생성한다.

---

## 11. 1단계 첫 인스턴스 시작

### 11.1 사용자 트래픽 격리

다중 인스턴스 안전 조건이 아직 완료되지 않았다면 ASG candidate를 Target Group에 넣기 전에 사용자 트래픽을 기존 수동 Green으로 격리한다.

권장 순서:

1. CloudFront `/api/*`, `/ws/*` Target origin을 보존된 기존 EC2 origin으로 임시 복구한다.
2. CloudFront가 `Deployed`가 될 때까지 기다린다.
3. 기존 수동 Green에서 API와 WebSocket smoke를 확인한다.
4. 작업 중 scheduled job과 배포 workflow를 동결한다.
5. 그 뒤 ASG Desired를 올린다.

이렇게 하면 ALB Target Group에 수동 Green과 ASG candidate가 잠시 같이 있어도 사용자 요청은 기존 EC2 origin만 사용한다.

### 11.2 ASG 용량 변경

ASG → Details → Group size → Edit에서 다음으로 바꾼다.

| 항목 | 값 |
| --- | ---: |
| Min | `1` |
| Desired | `1` |
| Max | `1` |

저장하면 ASG가 한 대를 시작한다.

### 11.3 시작 상태 확인

다음 순서로 확인한다.

1. ASG Activity가 `Successful`인지 확인
2. 새 EC2가 `Running`인지 확인
3. EC2 system/instance status check가 모두 통과하는지 확인
4. SSM Managed node가 `Online`인지 확인
5. `/var/log/cloud-init-output.log`에 bootstrap 성공 marker가 있는지 확인
6. CloudWatch log stream이 새 Instance ID로 생성되는지 확인
7. Target Group에서 새 Instance가 `Healthy`인지 확인
8. 기존 수동 Green과 별개의 Instance ID인지 확인

Secret 원문, `.env.prod`, 전체 `docker inspect`, 전체 Compose render 결과는 출력·공유하지 않는다.

### 11.4 ASG instance 내부 검증

SSM Session Manager로 접속해 다음을 확인한다.

```bash
sudo -iu ubuntu

cd /opt/buildgraph/prototype

git rev-parse HEAD

docker compose \
  -p buildgraph-green \
  -f compose.api.ecr.prod.yaml \
  --env-file .env.prod \
  --env-file /opt/buildgraph/green-images.env \
  ps

curl -fsS http://127.0.0.1/api/health
```

통과 기준:

- Nginx만 host 80에 공개
- API 8080과 XGB 8091은 host에 공개되지 않음
- `/api/health`가 `status: UP`, `database: UP`
- API/XGB running image가 manifest와 일치
- `BUILDGRAPH_SCHEDULING_ENABLED=false`가 실제 컨테이너에 적용
- RDS·Redis·RabbitMQ 연결 성공
- 새 instance가 기존 local volume 데이터 없이도 정상

환경변수 전체를 출력하지 말고 scheduling 값 하나만 안전하게 확인하는 전용 검증 script 또는 테스트를 사용한다.

---

## 12. 1단계 Cutover

### 12.1 기존 수동 Target deregistration

CloudFront가 기존 EC2 origin을 사용 중인 상태에서 Target Group을 연다.

1. 기존 수동 Green `i-033105106a7970ac1`을 선택한다.
2. Deregister를 누른다.
3. 상태가 `draining`에서 Target 목록 제외로 바뀔 때까지 기다린다.
4. ASG instance 한 대만 `Healthy`인지 확인한다.

Target Group deregistration delay는 현재 300초다. 즉시 종료하지 않는다.

### 12.2 CloudFront를 ALB로 복귀

1. `/api/*` Target origin을 `buildgraph-demo-api-green-alb-origin`으로 변경한다.
2. `Deployed`까지 기다린다.
3. API smoke를 완료한다.
4. API가 모두 통과한 뒤 `/ws/*`도 ALB origin으로 변경한다.
5. `Deployed`까지 기다린다.
6. WebSocket AUTH, frame, 65초 유지, 재연결을 확인한다.

### 12.3 1단계 완료 조건

- [ ] ASG `Min 1 / Desired 1 / Max 1`
- [ ] ASG instance 한 대 `InService`
- [ ] Target Group에 ASG instance 한 대만 `Healthy`
- [ ] 기존 수동 Green은 Running이지만 Target에서 제외
- [ ] 기존 수동 Green EIP와 CloudFront EC2 origin 보존
- [ ] CloudFront `/api/*`, `/ws/*`는 ALB origin
- [ ] API와 WebSocket smoke PASS
- [ ] scheduled job 중복 없음
- [ ] ALB·Target 5xx 이상 없음

---

## 13. 1단계 자동 교체 drill

1단계의 목적은 scale-out이 아니라 동일 서버 재생성이다.

사용자 영향을 피하려면 먼저 CloudFront `/api/*`, `/ws/*`를 기존 수동 EC2 origin으로 임시 복구한다.

검증 순서:

1. 현재 ASG Instance ID와 Launch Template version 기록
2. API/XGB image digest 기록
3. CloudFront를 기존 EC2 origin으로 전환하고 `Deployed` 확인
4. ASG instance를 Terminate
5. ASG Activity에서 replacement launch 확인
6. 새 Instance ID가 생성되는지 확인
7. SSM Online 확인
8. 새 Target이 Healthy인지 확인
9. Git SHA와 image digest 일치 확인
10. local volume 없이 기능 smoke
11. CloudFront를 ALB로 복귀
12. API·WebSocket smoke

`Min/Desired/Max = 1/1/1`에서 기존 ASG instance를 종료하면 replacement가 준비되는 동안 Target이 0개가 될 수 있다. 무중단 replacement는 2단계의 다중 인스턴스 안전 조건을 통과한 뒤 수행한다.

---

## 14. 2단계 scale-out 승인 조건

다음이 모두 통과해야 한다.

- [ ] 4.1 WebSocket fan-out/session routing 완료
- [ ] 4.2 local volume 외부화 완료
- [ ] 4.3 web scheduler 비활성·singleton worker 완료
- [ ] 4.4 immutable bootstrap/배포 완료
- [ ] 4.5 liveness/readiness 운영 방침 완료
- [ ] 4.6 DB connection budget 완료
- [ ] ASG replacement drill PASS
- [ ] 두 AZ 모두에서 candidate launch PASS
- [ ] CloudWatch dashboard와 Alarm 준비
- [ ] 롤백 담당자와 중단 기준 확정
- [ ] ALB custom header·listener 기본 `403` 후속 작업 완료 또는 승인된 보안 예외의 담당자·만료일 기록

하나라도 미완료면 `Max 1`을 유지한다.

---

## 15. 2단계 ASG 용량 확장

부하 테스트용 초기값:

| 항목 | 값 |
| --- | ---: |
| Min | `1` |
| Desired | `1` |
| Max | `3` |

이 값은 낮은 트래픽에서 한 대 비용만 유지하고 부하 때 최대 세 대까지 늘리는 구성이다. AZ 장애에도 한 대씩 계속 유지해야 하는 HA 목표라면 `Min 2 / Desired 2`를 별도로 승인한다.

### 15.1 Default instance warmup

1단계에서 측정한 다음 시간을 사용한다.

```text
EC2 launch 시작
→ cloud-init/bootstrap 완료
→ Target Healthy
```

설정값:

```text
Default instance warmup = 측정된 Healthy 시간 + 60초
```

Console 기본 300초를 무조건 줄이지 않는다. 실제 bootstrap이 5분보다 길면 그에 맞춰 늘린다.

### 15.2 첫 scaling policy

단일 인스턴스 Capacity 시험에서 다음 조건을 만족하는 마지막 지속 가능 RPS를 먼저 구한다.

- 비LLM API p95 500ms 이하
- 오류율 1% 이하
- EC2 CPU steady state 70% 이하
- Hikari pending 지속 증가 없음
- RDS·Redis·MQ 여유 있음

그 값을 `SAFE_RPS_PER_TARGET`으로 기록한다. 문자열 자체를 Console에 입력하지 않는다.

권장 첫 policy:

| 항목 | 값 |
| --- | --- |
| Policy type | Target tracking |
| Policy name | `buildgraph-demo-api-green-requests-tt` |
| Metric type | Application Load Balancer request count per target |
| Target Group | `buildgraph-demo-api-green-tg` |
| Target value | 실측값으로 계산 |
| Instance warmup | ASG default warmup 사용 |
| Disable scale in | 첫 부하 시험에서는 활성 |

계산 기준:

```text
Target value
= SAFE_RPS_PER_TARGET × 60초 × 0.65
```

`0.65`는 측정된 한계의 65%에서 scale-out 여유를 두기 위한 시작값이다. 예를 들어 한 Target이 40 RPS를 안정 처리했다면 예시 target은 `40 × 60 × 0.65 = 1560 requests/minute/target`이다. 예시 `1560`을 실측 없이 사용하지 않는다.

AWS의 `ALBRequestCountPerTarget` target value는 한 Target의 1분당 최적 평균 요청 수다.

### 15.3 CPU policy는 보조로만 검토

CPU와 요청 수의 상관관계를 확인한 뒤 두 번째 target tracking policy를 추가할 수 있다. 여러 target tracking policy가 있으면 AWS는 하나라도 scale-out 조건이면 확장하고, scale-in은 scale-in이 활성화된 모든 policy가 동의해야 수행한다.

T3는 burstable instance이므로 다음도 같이 본다.

- `CPUUtilization`
- `CPUCreditBalance`
- `CPUSurplusCreditBalance`
- `CPUSurplusCreditsCharged`

CPU target 숫자를 근거 없이 고정하지 않는다. CPU가 낮아도 DB connection이나 외부 AI latency가 병목일 수 있다.

### 15.4 Scale-in 활성화

첫 scale-out 시험에서는 scale-in을 비활성화해 부하 중 Target이 줄어드는 변수를 제거한다.

다음 검증 후에만 scale-in을 활성화한다.

- 최소 2회 scale-out 성공
- 부하 제거 후 CPU/RPS/latency 회복
- deregistration delay 300초 동안 신규 요청 차단과 기존 연결 drain 확인
- WebSocket client 정상 재연결
- local file/model 손실 없음
- DB connection이 instance 감소 후 회수됨

---

## 16. Scale-out 부하 테스트

[부하테스트.md](부하테스트.md)의 단계를 그대로 따르되 ASG metric을 추가한다.

### 16.1 순서

1. ASG 한 대 기준 5 VU Smoke
2. 한 대의 지속 가능 RPS 재측정
3. request target tracking policy 활성
4. 같은 RPS ramp 재실행
5. Desired가 1→2가 되는 시각 기록
6. 두 번째 Target이 Healthy가 되는 시각 기록
7. p95와 오류율이 회복되는 시각 기록
8. 필요할 때만 2→3 확장 확인
9. 100 VU 합격 후 200→300→500→1,000 VU 조건부 진행

1,000 VU와 1,000 RPS는 별도 시험이다. ASG가 세 대가 됐다는 사실만으로 어느 숫자도 자동 합격하지 않는다.

### 16.2 필수 metric

| 계층 | Metric |
| --- | --- |
| ASG | `GroupDesiredCapacity`, `GroupInServiceInstances`, `GroupPendingInstances`, `GroupTerminatingInstances` |
| EC2 | CPU, CPU credit, memory, disk, network, status check |
| ALB | RequestCount, TargetResponseTime, HealthyHostCount, UnHealthyHostCount, ALB/Target 4xx·5xx |
| JVM | heap, GC pause, thread, Tomcat, Hikari active/pending/timeout |
| RDS | CPU, credit, DatabaseConnections, latency, IOPS, lock, FreeableMemory |
| Redis | CPU, memory, connection, eviction, latency |
| MQ | queue depth, publish/ack, consumer, unacked/retry |
| XGB | latency, CPU/memory, model version, restart |

### 16.3 Auto Scaling 성공 기준

- [ ] 부하 증가 후 Desired capacity가 증가
- [ ] 새 EC2가 두 AZ에 가능한 한 균형 배치
- [ ] 새 Target이 warmup 안에 Healthy
- [ ] scale-out 중 ALB 5xx 급증 없음
- [ ] p95가 기준선 방향으로 회복
- [ ] 오류율 1% 이하
- [ ] 모든 instance의 Git SHA와 image digest 동일
- [ ] WebSocket push와 PC Agent 진단 정상
- [ ] scheduled job 중복 0
- [ ] local artifact 불일치 0
- [ ] DB connection budget 내 유지

---

## 17. Green Web ASG 배포 모드

현재 BuildGraph는 WebSocket·PC Agent cross-instance 공유를 구현하지 않으므로
Web ASG를 계속 `Min 1 / Desired 1 / Max 1`로 유지한다. 배포 목적에 따라 다음
두 모드 중 하나를 명시적으로 선택한다.

| 모드 | 실행 방식 | 사용자 영향 | 사용 기준 |
| --- | --- | --- | --- |
| Fast Deploy | 현재 단일 ASG EC2에서 선택한 컨테이너를 SSM으로 제자리 교체 | API 또는 WebSocket의 짧은 중단 허용 | API/XGB image와 애플리케이션 코드만 빠르게 반영 |
| Immutable Release | 새 숫자형 Launch Template version으로 `100/200` Instance Refresh | HTTP 가용성 유지, 구·신 Target 잠시 공존 | bootstrap·AMI·Nginx·런타임 기반 변경 또는 완전한 replacement 검증 |

두 모드 모두 정확한 현재 `origin/main` 40자리 SHA, 동일 SHA의 immutable ECR
tag와 digest, 숫자형 Launch Template version을 사용한다. 두 workflow는 같은
concurrency group을 사용해 동시에 실행하지 않는다.

### 17.1 Workflow 역할 분리

다음 두 workflow는 테스트와 immutable ECR image 발행까지만 수행한다.

- `Publish Green API Image`
- `Publish Green XGB Reranker Image`

이 workflow는 다음 작업을 하지 않는다.

- 고정 EC2 instance ID 사용
- SSM `SendCommand`
- 실행 중인 컨테이너 직접 교체
- Launch Template 또는 ASG 변경

이미지 발행 후 서버 반영은 다음 수동 workflow 중 하나에서만 수행한다.

- `Fast Deploy Green Web ASG`
- `Release Green Web ASG`

두 workflow는 다음 값을 요구한다.

| 입력 | 의미 |
| --- | --- |
| `service` | `api` 또는 `xgb-reranker` |
| `git_sha` | 현재 `origin/main`의 정확한 40자리 SHA |
| `image_tag` | `git_sha`와 동일한 immutable ECR tag |

### 17.2 Fast Deploy 적용 조건

Fast Deploy는 기존 EC2를 재사용하므로 다음 조건을 모두 확인하고 하나라도
다르면 변경 없이 중단한다.

- ASG가 `Min 1 / Desired 1 / Max 1`
- ASG가 승인된 Launch Template의 특정 숫자 version 사용
- ASG에 정확히 한 대가 있고 `InService`, `Healthy`
- Target Group에 같은 인스턴스 한 대만 port `80`, `healthy`
- 해당 인스턴스가 SSM `Online`
- Pending·InProgress·Baking·Rollback 중인 Instance Refresh 없음
- source AMI가 `available`, `Validation=passed`
- 실행 Git SHA·image manifest가 source Launch Template release와 일치
- target SHA가 정확한 현재 `origin/main`이고 source SHA의 후손
- 선택한 ECR SHA tag가 immutable digest 하나로 해석됨
- `BUILDGRAPH_SCHEDULING_ENABLED=false` 유지

`tools/bootstrap_green_asg.sh`, `tools/prepare_green_asg_builder.sh`, production
Compose, Nginx 설정 또는 base release manifest가 source와 target SHA 사이에서
바뀌었다면 Fast Deploy를 거절하고 Immutable Release를 사용한다. Nginx image
변경도 Fast Deploy 대상이 아니다.

### 17.3 Fast Deploy 순서

1. API 또는 XGB immutable ECR image를 먼저 발행한다.
2. `Fast Deploy Green Web ASG` workflow에서 `service`, `git_sha`,
   `image_tag`를 선택한다.
3. helper가 ASG 상태에서 현재 단일 EC2 ID를 동적으로 찾는다. workflow
   variable이나 입력으로 Instance ID를 받지 않는다.
4. 읽기 전용 preflight에서 ASG·Target·SSM·source release·ECR digest를
   검증한다.
5. SSM으로 선택한 service image를 pull하고 해당 컨테이너만
   `--no-deps --force-recreate`로 교체한다.
6. API는 `/api/health`, XGB는 container health를 확인하고 API·XGB·Nginx
   실행 image가 target manifest와 일치하는지 확인한다. API container의 실제
   `BUILDGRAPH_SCHEDULING_ENABLED=false`와 비대상 container ID가 유지됐는지도
   함께 검증한다.
7. 같은 release manifest와 Git SHA가 들어 있는 새 숫자형 Launch Template
   version을 만든다. User data 외 다른 Launch Template 필드는 바꾸지 않는다.
8. **Instance Refresh 없이** ASG의 Launch Template 포인터만 새 숫자 version으로
   변경한다.
9. EC2 ID가 처음 발견한 값과 동일하고 Target이 계속 Healthy인지 다시 확인한
   뒤 transaction을 commit하고 rollback 자료를 정리한다.
10. API smoke와 WebSocket 재연결을 확인한다.

Fast Deploy helper는 다음 파일이다.

```text
tools/deploy_green_web_asg_in_place.sh
tools/apply_green_asg_release_in_place.sh
```

기본 실행은 읽기 전용이다. `--apply`가 있을 때만 SSM container 교체, 새
Launch Template version 생성과 ASG 숫자 포인터 변경을 수행한다.

Fast Deploy에서는 다음 작업을 하지 않는다.

- 새 EC2 생성·종료·교체
- Instance Refresh 시작·취소·rollback
- CloudFront origin 또는 behavior 변경
- ASG 용량 변경
- AMI, Security Group, Subnet 변경
- 여러 ASG EC2에 동시 SSM 실행

단일 컨테이너가 강제로 재생성되는 동안 짧은 API 실패 또는 WebSocket 연결
종료가 발생할 수 있다. 이 중단은 개발·데모 환경의 승인된 정책이며 client
재연결과 배포 직후 smoke test로 확인한다.

SSM 실패, health 실패 또는 ASG 포인터 변경 실패 시 helper는 저장한 source
Git SHA와 manifest로 기존 컨테이너를 복구하고 ASG 포인터도 이전 숫자
version으로 되돌린다. SSM 취소와 실행 순서가 엇갈려도 같은 deployment ID의
취소 fence를 먼저 기록하므로 늦게 도착한 prepare는 변경을 시작하지 않는다.
자동 보상이 완료되지 않으면 추가 배포를 중지하고 수동 복구한다.

### 17.4 Immutable Release 순서

1. 현재 `/api/health`, WebSocket, PC Agent 상태를 기록한다.
2. CloudFront `/api/*`, `/ws/*`는 ALB origin을 계속 사용한다.
3. `Release Green Web ASG`를 실행한다.
4. 선택한 ECR SHA tag를 immutable digest URI로 변환한다.
5. 기존 release manifest에서 반대편 API/XGB image와 Nginx digest를 보존한다.
6. Secret이 없는 release manifest를 User data에 포함한 숫자형 Launch
   Template version을 생성한다.
7. 새 숫자 version을 `DesiredConfiguration`으로 지정해 Instance Refresh를
   시작한다.
8. 기존 Target과 신규 Target이 잠시 함께 등록되는 것을 관찰한다.
9. Refresh 완료 후 신규 Target 한 대만 Healthy인지 확인한다.
10. API smoke test를 수행한다.
11. WebSocket과 PC Agent의 재연결·실시간 기능을 확인한다.

Release helper는 다음 파일이다.

```text
tools/rollout_green_web_asg_release.sh
```

기본 실행은 읽기 전용이다. `--apply`를 지정한 경우에만 Launch Template과
Instance Refresh를 변경한다.

### 17.5 Immutable Release의 Instance Refresh 정책

CloudFront 격리 없이 다음 값을 사용한다.

```text
Minimum healthy percentage: 100
Maximum healthy percentage: 200
Skip matching: Enable
Auto rollback: Enable
Launch Template: DesiredConfiguration의 특정 숫자 version
```

Minimum healthy 100%는 새 인스턴스를 먼저 시작한 뒤 이전 인스턴스를
종료한다. Desired가 1이어도 잠시 두 대가 실행되며 ALB가 두 Target에
요청을 분산할 수 있다.

이 시간의 HTTP 가용성은 유지하지만 다음 위험을 승인된 예외로 수용한다.

- WebSocket 연결과 push가 서로 다른 인스턴스에 위치할 수 있음
- PC Agent 연결과 진단 요청이 다른 인스턴스로 분리될 수 있음
- 배포 전·후 API image가 짧은 시간 동시에 요청을 처리함
- 클라이언트 재연결 또는 REST polling fallback이 필요할 수 있음

Refresh 중 다음을 확인한다.

- 새 Target Healthy
- warmup 완료
- API smoke
- WebSocket AUTH·frame
- ALB/Target 5xx
- RDS connection
- ASG Activity와 Refresh percentage

진행 중인 Refresh가 실패하면 이전 숫자 Launch Template version으로
rollback한다. Refresh가 이미 성공했지만 Target health 검증이 실패하면 이전
version을 `DesiredConfiguration`으로 지정한 reverse Refresh를 새로 시작한다.
기존 AMI와 이전 Launch Template version을 삭제하지 않는다.

### 17.6 고정 Instance ID 금지와 SSM 권한 경계

Fast Deploy의 SSM 사용은 과거 고정 EC2 배포를 다시 허용하는 것이 아니다.

- `GREEN_EC2_INSTANCE_ID` 같은 Repository Variable을 배포 대상으로 사용하지
  않는다.
- workflow input, Secret 또는 문서에 특정 ASG EC2 ID를 저장하지 않는다.
- helper가 승인된 ASG에서 정확히 한 대의 `InService/Healthy` instance를 매번
  동적으로 찾고, Target health와 SSM Online 상태까지 교차 검증한다.
- SSM instance 권한은 `ManagedBy=asg`, `Stack=green`, `Service=api` resource
  tag를 모두 만족하는 EC2로 제한한다.
- SSM document는 `AWS-RunShellScript` 하나로 제한한다.
- ASG에 0대 또는 2대 이상이 있거나 instance가 중간에 바뀌면 fail closed한다.

수동 Green EC2, Blue EC2 또는 임의의 고정 Instance ID에 SSM 배포하는 경로는
계속 금지한다.

### 17.7 GitHub OIDC Role 권한

ASG rollout에 필요한 추가 최소 권한은 다음 파일에 정의한다.

```text
infra/iam/buildgraph-demo-github-actions-green-asg-rollout-policy.json
```

정책 범위는 현재 Green Web Launch Template, ASG, Private App Subnet, ASG SG,
검증 AMI와 runtime IAM Role로 제한한다.

Immutable Release에는 기존 Launch Template version 생성, 제한된 replacement
launch, Instance Refresh 권한을 사용한다. Fast Deploy에는 다음 최소 권한만
추가한다.

- 승인된 Green Web ASG 하나의 `autoscaling:UpdateAutoScalingGroup`
- tag 조건을 통과한 Green ASG EC2의 `ssm:SendCommand`
- `AWS-RunShellScript` document의 `ssm:SendCommand`
- command 상태 확인과 실패 보상을 위한 `ssm:GetCommandInvocation`,
  `ssm:CancelCommand`

다음 권한과 동작은 허용하지 않는다.

- 고정 EC2 대상 SSM 배포
- ASG·Launch Template 삭제
- EC2 수동 종료
- Secret value 조회

---

## 18. CloudWatch와 알림

현재 BuildGraph CloudWatch Alarm은 0개다. 2단계 전 최소 다음을 만든다.

### 18.1 필수 Alarm 후보

- ALB `HealthyHostCount`가 기대 Min보다 낮음
- ALB `UnHealthyHostCount >= 1`
- `HTTPCode_ELB_5XX_Count` 발생
- `HTTPCode_Target_5XX_Count` 비율이 1% 초과
- TargetResponseTime p95 급증
- EC2 CPU 85% 이상 5분 지속
- `CPUCreditBalance` 저하
- ASG Desired와 InService 불일치 지속
- RDS DatabaseConnections budget 초과
- RDS CPU/FreeableMemory 위험
- Redis eviction 또는 memory 위험
- RabbitMQ queue/unacked 지속 증가

Alarm 임계값은 정상 기준선과 부하 테스트 결과로 확정한다. 예상 가능한 애플리케이션 4xx를 장애 5xx와 섞지 않는다.

### 18.2 ASG event 알림

승인된 SNS topic에 다음 event를 연결한다.

- instance launch
- instance launch error
- instance terminate
- instance terminate error

SNS 알림은 best effort이므로 CloudWatch와 ASG Activity도 함께 확인한다.

---

## 19. 롤백

### 19.1 첫 ASG instance가 Healthy가 되지 않음

1. CloudFront가 기존 EC2 origin인지 확인한다.
2. ASG Desired와 Min을 `0`으로 내린다.
3. Max는 `1`로 유지한다.
4. ASG Activity의 launch failure를 기록한다.
5. SSM, cloud-init, ECR, Secret, SG, public IPv4를 순서대로 확인한다.
6. 기존 수동 Green과 Target Group을 삭제하지 않는다.

### 19.2 Cutover 후 API 또는 WebSocket 실패

1. CloudFront `/ws/*`만 실패하면 먼저 기존 EC2 origin으로 되돌린다.
2. 공통 실패면 `/api/*`와 `/ws/*` 모두 기존 EC2 origin으로 되돌린다.
3. CloudFront `Deployed`까지 기다린다.
4. 기존 수동 Green health·인증·WebSocket을 확인한다.
5. ASG를 `Min 0 / Desired 0 / Max 1`로 축소한다.
6. ASG·Launch Template·AMI를 즉시 삭제하지 않는다.

### 19.3 Scale-out 후 오류

다음 순서로 영향을 줄인다.

1. 부하 테스트 중지
2. dynamic scaling policy의 추가 scale-out 중지
3. 신규 Target 상태와 image digest 비교
4. 모든 Target에 공통이면 CloudFront를 기존 EC2 origin으로 롤백
5. 특정 Target만 문제면 해당 ASG instance를 Standby/교체하는 절차를 승인 후 수행
6. 안전하면 `Min 1 / Desired 1 / Max 1`로 복구
7. WebSocket fan-out, local artifact, scheduler, DB pool 원인 확인

ASG Desired보다 인스턴스를 수동으로 적게 종료하면 ASG가 다시 생성한다. 비용을 멈추려면 반드시 Min/Desired 설정을 함께 변경한다.

### 19.4 새 release 문제

1. Instance Refresh 중지
2. 이전 Launch Template version 확인
3. ASG를 이전 version으로 변경
4. rollback Instance Refresh
5. Target Healthy와 smoke 확인
6. 실패한 AMI·Launch Template version을 원인 분석 전 삭제하지 않음

---

## 20. 읽기 전용 AWS CLI 검증

다음 명령은 설정을 변경하지 않는다.

```bash
export AWS_PROFILE="team03-admin-443915990705"
export AWS_REGION="ap-northeast-2"
```

ASG 설정:

```bash
aws autoscaling describe-auto-scaling-groups \
  --auto-scaling-group-names buildgraph-demo-api-green-asg \
  --region "$AWS_REGION" \
  --profile "$AWS_PROFILE" \
  --query 'AutoScalingGroups[0].{Name:AutoScalingGroupName,Min:MinSize,Desired:DesiredCapacity,Max:MaxSize,HealthCheckType:HealthCheckType,Grace:HealthCheckGracePeriod,Subnets:VPCZoneIdentifier,TargetGroups:TargetGroupARNs,Instances:Instances[].{Id:InstanceId,Zone:AvailabilityZone,Lifecycle:LifecycleState,Health:HealthStatus}}'
```

최근 scaling activity:

```bash
aws autoscaling describe-scaling-activities \
  --auto-scaling-group-name buildgraph-demo-api-green-asg \
  --max-items 20 \
  --region "$AWS_REGION" \
  --profile "$AWS_PROFILE" \
  --query 'Activities[].{Start:StartTime,Status:StatusCode,Description:Description,Cause:Cause}'
```

Target health:

```bash
aws elbv2 describe-target-health \
  --target-group-arn arn:aws:elasticloadbalancing:ap-northeast-2:443915990705:targetgroup/buildgraph-demo-api-green-tg/f905e7669645a411 \
  --region "$AWS_REGION" \
  --profile "$AWS_PROFILE" \
  --query 'TargetHealthDescriptions[].{Id:Target.Id,Port:Target.Port,State:TargetHealth.State,Reason:TargetHealth.Reason}'
```

Launch Template의 안전한 필드만 확인:

```bash
aws ec2 describe-launch-template-versions \
  --launch-template-name buildgraph-demo-api-green-lt \
  --versions '$Default' \
  --region "$AWS_REGION" \
  --profile "$AWS_PROFILE" \
  --query 'LaunchTemplateVersions[0].{Version:VersionNumber,Description:VersionDescription,ImageId:LaunchTemplateData.ImageId,InstanceType:LaunchTemplateData.InstanceType,IamProfile:LaunchTemplateData.IamInstanceProfile.Name,SecurityGroups:LaunchTemplateData.NetworkInterfaces[0].Groups,PublicIpv4:LaunchTemplateData.NetworkInterfaces[0].AssociatePublicIpAddress,Monitoring:LaunchTemplateData.Monitoring.Enabled,Metadata:LaunchTemplateData.MetadataOptions}'
```

Launch Template 전체 출력에는 user data가 포함될 수 있다. 전체 JSON을 파일·채팅·이슈에 저장하지 않는다.

---

## 21. 비용

EC2 Auto Scaling 기능 자체에는 추가 사용료가 없지만 다음은 과금된다.

- 실제 실행된 EC2 대수와 시간
- 각 EC2의 EBS volume과 snapshot/AMI
- 인스턴스별 Public IPv4
- Detailed Monitoring과 CloudWatch custom metric/log/alarm
- NAT Gateway 또는 VPC Interface Endpoint를 추가하면 해당 시간·처리 비용
- scale-out으로 증가한 RDS·Redis·MQ·외부 AI 사용량

1단계 rollback 기간에는 다음이 동시에 과금될 수 있다.

```text
기존 수동 Green EC2 1대
+ ASG Green EC2 1대
+ 두 EBS
+ 두 Public IPv4/EIP
+ AMI snapshot
```

2단계 `Max 3`은 최대 세 대분의 EC2·EBS·Public IPv4 비용이 발생한다. 부하 테스트가 끝났다고 인스턴스만 Stop하지 말고 scaling policy를 비활성화한 뒤 Min/Desired를 승인된 값으로 되돌린다.

---

## 22. 실행 기록표

| 항목 | 기록 |
| --- | --- |
| 작업 시작/종료 KST | |
| 작업자 | |
| Git SHA | |
| API image URI/digest | |
| XGB image URI/digest | |
| Nginx image digest | |
| ASG EC2 SG ID | |
| Custom AMI ID | |
| AMI snapshot ID | |
| Launch Template ID/version | |
| ASG ARN | |
| 최초 bootstrap 시간 | |
| Default instance warmup | |
| Health check grace period | |
| 최초 ASG Instance ID/AZ | |
| Replacement Instance ID/AZ | |
| 배포 모드 | `Fast Deploy / Immutable Release` |
| Fast Deploy 기존 Instance ID 유지 | |
| Fast Deploy 신규 LT 숫자 version | |
| SAFE_RPS_PER_TARGET | |
| Request target value | |
| Scale-out 1→2 소요시간 | |
| Scale-out 2→3 소요시간 | |
| 최대 RDS connection | |
| WebSocket multi-instance 결과 | |
| Scheduler 중복 결과 | |
| Rollback 결과 | |
| 비용·특이사항 | |

Secret value, WebSocket ticket, user data 원문은 기록하지 않는다.

---

## 23. 최종 체크리스트

### 단계 1 — ASG 한 대

- [ ] ALB 작업자 `/32` 임시 rule 삭제
- [ ] ASG 전용 EC2 SG 생성
- [ ] ALB SG outbound에 ASG SG TCP 80 추가
- [ ] RDS·Redis·RabbitMQ SG에 ASG SG source 추가
- [ ] clean custom AMI 생성
- [ ] Launch Template 특정 version 생성
- [ ] Public 2a·2b 선택
- [ ] ASG 최초 `0/0/1`, scaling policy 없음
- [ ] ASG `1/1/1` 전환
- [ ] 새 EC2 SSM Online
- [ ] 새 Target Healthy
- [ ] Git SHA와 image digest 일치
- [ ] API·WebSocket smoke PASS
- [ ] 기존 수동 Green·EIP·EC2 origin 보존
- [ ] replacement drill PASS
- [ ] Fast Deploy가 고정 Instance ID 없이 단일 Healthy ASG EC2를 동적으로 선택
- [ ] Fast Deploy 후 기존 EC2 ID 유지·Target Healthy·LT 숫자 포인터 전진 확인
- [ ] Immutable Release `100/200` Instance Refresh와 이전 LT rollback 확인

### 단계 2 진입 전 — 현재 미진행

WebSocket·PC Agent cross-instance 처리를 구현하지 않기로 결정했으므로 아래
항목과 `Max 3` 전환은 수행하지 않는다.

- [ ] WebSocket cross-instance fan-out/session routing 완료
- [ ] PC Agent cross-instance 진단 5초 계약 PASS
- [ ] Agent log shared storage 완료
- [ ] XGB model immutable/shared artifact 완료
- [ ] ASG web scheduler 비활성 전달 테스트 PASS
- [ ] singleton scheduler/worker 준비
- [ ] 고정 EC2 ID 없는 배포 workflow 준비
- [ ] liveness/readiness 운영 방침 확정
- [ ] Hikari × Max instance DB budget 확인
- [ ] bootstrap test와 rollback test PASS

### 단계 2 — 실제 scale-out 미적용

- [ ] 2단계 진입 전 하드 게이트 전체 PASS
- [ ] ALB origin 보호 후속 작업 완료 또는 승인된 예외 만료일 기록
- [ ] 단일 Target SAFE_RPS 측정
- [ ] ASG `1/1/3`
- [ ] 실측 warmup 적용
- [ ] ALBRequestCountPerTarget target value 계산
- [ ] 첫 시험 scale-in 비활성
- [ ] 1→2 scale-out PASS
- [ ] 2→3 조건부 scale-out PASS
- [ ] 두 AZ 배치 확인
- [ ] p95·오류율 회복 확인
- [ ] WebSocket·PC Agent multi-instance PASS
- [ ] scheduler 중복 0
- [ ] local artifact 불일치 0
- [ ] DB connection budget 내 유지
- [ ] scale-in·drain·재연결 PASS

### 관측·롤백

- [ ] ASG group metric collection 활성
- [ ] ALB·ASG·EC2·RDS·Redis·MQ Alarm 준비
- [ ] launch/terminate/fail 알림 준비
- [ ] 기존 EC2 origin rollback 절차 확인
- [ ] 이전 Launch Template version rollback PASS
- [ ] rollback 전 기존 수동 Green 미삭제
- [ ] 부하 종료 후 Min/Desired/Max 복구

---

## 24. 공식 참고 문서

- [Create a launch template for an Auto Scaling group](https://docs.aws.amazon.com/autoscaling/ec2/userguide/create-launch-template.html)
- [Create an Auto Scaling group using a launch template](https://docs.aws.amazon.com/autoscaling/ec2/userguide/create-asg-launch-template.html)
- [Auto Scaling group Availability Zone distribution](https://docs.aws.amazon.com/autoscaling/ec2/userguide/ec2-auto-scaling-availability-zone-balanced.html)
- [Health checks for instances in an Auto Scaling group](https://docs.aws.amazon.com/autoscaling/ec2/userguide/ec2-auto-scaling-health-checks.html)
- [Set the health check grace period](https://docs.aws.amazon.com/autoscaling/ec2/userguide/health-check-grace-period.html)
- [Target tracking scaling policies](https://docs.aws.amazon.com/autoscaling/ec2/userguide/as-scaling-target-tracking.html)
- [Create a target tracking scaling policy](https://docs.aws.amazon.com/autoscaling/ec2/userguide/policy_creating.html)
- [How an instance refresh works](https://docs.aws.amazon.com/autoscaling/ec2/userguide/instance-refresh-overview.html)
- [Instance refresh default values](https://docs.aws.amazon.com/autoscaling/ec2/userguide/understand-instance-refresh-default-values.html)
- [Auto Scaling lifecycle hooks](https://docs.aws.amazon.com/autoscaling/ec2/userguide/lifecycle-hooks-overview.html)
- [Auto Scaling CloudWatch metrics](https://docs.aws.amazon.com/autoscaling/ec2/userguide/ec2-auto-scaling-metrics.html)
- [Auto Scaling SNS notifications](https://docs.aws.amazon.com/autoscaling/ec2/userguide/ec2-auto-scaling-sns-notifications.html)
- [Register targets with an ALB Target Group](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/target-group-register-targets.html)
- [ALB Target Group attributes and deregistration delay](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/edit-target-group-attributes.html)
- [EC2 user data](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/user-data.html)
- [Create an EBS-backed AMI](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/creating-an-ami-ebs.html)
- [Secrets Manager IAM policies](https://docs.aws.amazon.com/secretsmanager/latest/userguide/auth-and-access_iam-policies.html)
- [EC2 Auto Scaling pricing](https://aws.amazon.com/ec2/autoscaling/pricing/)
- [Amazon VPC pricing](https://aws.amazon.com/vpc/pricing/)
