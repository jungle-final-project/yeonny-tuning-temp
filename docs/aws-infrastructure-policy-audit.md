# AWS 인프라 통합 정책 감사 문서

> [!CAUTION]
> **내부 민감 문서 — Public GitHub 저장소에 commit·push하지 않는다.** 이 파일에는
> Secret 원문은 없지만 실제 AWS 계정·리소스 ID·Public IP·보안 미완료 항목이
> 집약돼 있다. 승인된 비공개 저장소나 접근 통제된 내부 문서함으로 옮긴 뒤에만
> 공유한다.

이 문서는 BuildGraph AWS 인프라의 **현재 실제 설정**, 팀이 승인한 **운영 정책**, 아직 적용되지 않은 **예외·후속 작업**, 과거 문서와의 **드리프트**를 하나로 정리한 내부 감사 문서다.

- 조회 기준: `2026-07-16 14:51 KST`
- ASG·Target Group·CloudFront 완료 조건 재조회: `2026-07-16 15:10 KST`
- AWS 계정: `443915990705`
- 기본 리전: `ap-northeast-2` 서울
- 조회 프로필: `team03-admin-443915990705`
- 조회 주체: IAM Identity Center의 `AWSReservedSSO_team03-admin_a85bec6c993e8a28`
- 조회 방법: AWS CLI `describe`, `get`, `list`, `head` 계열과 GitHub 읽기 API
- AWS·GitHub 리소스 변경: 없음
- 기존 문서 변경: 없음
- Secret value, token, origin 검증 header 값, Launch Template user data, SSM command 출력: 조회·기록하지 않음

> 현재 SSO Role에는 `AdministratorAccess`가 연결돼 있다. 따라서 이번 감사의 읽기 전용 성격은 IAM이 강제한 것이 아니라, 허용 명령을 조회 계열로 제한한 **절차적 통제**다.

---

## 1. 판정 기준

### 1.1 근거 우선순위

문서마다 작성 시점이 다르므로 현재 상태는 다음 순서로 판정한다.

| 우선순위 | 근거 | 용도 |
| ---: | --- | --- |
| 1 | 2026-07-16 AWS·GitHub 읽기 전용 실시간 조회 | 현재 Control Plane 실제값 |
| 2 | [ASG 콘솔 가이드](aws-infrastructure-asg-console-guide.md)의 2026-07-16 완료 기록 | 최근 전환 절차·승인 예외·검증 결과 |
| 3 | [ALB 콘솔 가이드](aws-infrastructure-alb-console-guide.md) | CloudFront·ALB·Target Group 설계 정책 |
| 4 | [Phase 0~7 감사 문서](aws-infrastructure-current-state-audit.md) | 2026-07-14 역사적 기준선 |
| 5 | Phase 0~8 가이드와 [초기 분리 계획](aws-infrastructure-separation-plan.md) | 생성 당시 목표와 의도 |

Phase 문서의 미체크 항목이나 과거 구조도를 현재 AWS 상태로 해석하지 않는다. 실제값과 계획이 충돌하면 실시간 조회값을 현재 상태로 기록하고, 계획은 목표 또는 역사적 설정으로 분리한다.

### 1.2 상태 표기

| 상태 | 의미 |
| --- | --- |
| `적용` | AWS·GitHub 실시간 조회 또는 최신 런타임 검증으로 확인 |
| `승인 예외` | 위험을 인지하고 담당자·해제 조건을 두고 임시 유지 |
| `미완료` | 목표 정책은 있으나 아직 적용·검증되지 않음 |
| `드리프트` | 문서·설명·태그·배포 기준과 실제값이 다름 |
| `역사적` | 과거 단계에서 유효했으나 현재 상태를 나타내지 않음 |

### 1.3 민감 정보 경계

이 문서는 내부 상세본이므로 리소스 ID, ARN, Public IP, image digest와 취약점 심각도 개수는 기록한다. 다음 값은 기록하지 않는다.

- Secrets Manager Secret 원문과 version별 값
- DB·Redis·RabbitMQ 비밀번호와 AUTH token
- JWT·OAuth·외부 API key
- GitHub Secret 값
- CloudFront origin 검증 header 이름과 값
- Launch Template user data 원문
- 전체 Compose render, `docker inspect`, 컨테이너 환경변수
- SSM Run Command stdout·stderr

### 1.4 현재값의 조회 시각·근거

별도 시각이 적히지 않은 `실제 값`, `현재 값`, `현재 상태`는 문서 머리말의
`2026-07-16 14:51 KST`를 기준으로 하며, 해당 절 제목의 AWS 서비스 Control
Plane 조회 결과를 근거로 한다. 현재값의 출처는 다음과 같이 구분한다.

| 범위 | 근거 서비스·자료 | 기준 |
| --- | --- | --- |
| 계정·IAM·조직 | STS, IAM, IAM Identity Center Role metadata, Organizations | AWS CLI 실시간 조회 |
| VPC·SG·S3·CloudFront·WAF·ALB | EC2/VPC, S3, CloudFront, WAFv2, ELBv2 | AWS CLI 실시간 조회 |
| EC2·EBS·AMI·Launch Template·ASG | EC2, Auto Scaling, SSM managed-node metadata | AWS CLI 실시간 조회 |
| RDS·Redis·RabbitMQ·Secret·ECR·관측 | RDS, ElastiCache, Amazon MQ, Secrets Manager metadata, ECR, CloudWatch 계열과 보안 서비스 | AWS CLI 실시간 조회 |
| GitHub 변수·workflow·remote SHA | GitHub 읽기 API와 repository workflow 파일 | 2026-07-16 조회 |
| Git local SHA·working tree | 로컬 Git metadata | 2026-07-16 조회 |
| 컨테이너·bootstrap·scheduler 검증 | ASG 콘솔 가이드의 2026-07-16 완료 기록 | 이번 감사에서 SSM을 재실행하지 않은 최근 검증 기록 |

따라서 AWS Control Plane에서 확인할 수 없는 컨테이너 내부 상태는 실시간 AWS
조회값과 섞지 않고 최신 완료 기록에 근거한 정책·검증값으로만 해석한다.

---

## 2. 현재 아키텍처

### 2.1 사용자 트래픽 경로

```text
사용자
  → Green CloudFront E1MVNMU0O781IM
      ├─ Default (*) → Private S3 Web + OAC
      ├─ /api/*      → Green ALB origin
      └─ /ws/*       → Green ALB origin
  → internet-facing ALB
  → Target Group
  → ASG Min 1 / Desired 1 / Max 1
  → Green ASG EC2
      → Nginx :80
          → Spring Boot API :8080
              → XGB Reranker :8091

Green API
  ├─ RDS PostgreSQL :5432
  ├─ ElastiCache Redis TLS :6379
  └─ Amazon MQ RabbitMQ AMQPS :5671
```

### 2.2 현재 트래픽 상태

| 항목 | 현재 값 | 상태 |
| --- | --- | --- |
| Green CloudFront | `E1MVNMU0O781IM` / `d2qhd7deuwmlln.cloudfront.net` / `Deployed` | `적용` |
| `/api/*` origin | `buildgraph-demo-api-green-alb-origin` | `적용` |
| `/ws/*` origin | `buildgraph-demo-api-green-alb-origin` | `적용` |
| ASG | `buildgraph-demo-api-green-asg` / `1/1/1` | `적용` |
| 현재 Target | `i-0d8bfaec8aab8cc65:80` 한 대 `healthy` | `적용` |
| Scaling policy | 없음 | `적용` |
| 기존 수동 Green Target | Target Group에서 제외 | `적용` |

### 2.3 롤백 자원

| 자원 | 현재 상태 | 보존 정책 |
| --- | --- | --- |
| 수동 Green EC2 | `i-033105106a7970ac1` / `43.203.33.190` / Running | ASG replacement·rollback 검증 전 삭제 금지 |
| 수동 Green CloudFront origin | `buildgraph-demo-api-green-origin` | `/api/*`, `/ws/*` 즉시 rollback용으로 보존 |
| Blue EC2 | `i-082c21a20e14f3295` / Running | Blue rollback 대기 종료 전 보존 |
| Blue CloudFront | `EI6MMNZLTTN3H` | Green과 별도 환경으로 보존 |

수동 Green rollback은 Green 관리형 데이터 서비스를 그대로 사용한다. Blue로 되돌리면 Green에서 발생한 쓰기 데이터가 자동 역동기화되지 않으므로 데이터 손실 가능성을 별도로 판단해야 한다.

---

## 3. 계정·조직·운영자 접근 정책

### 3.1 감사 세션

| 항목 | 실제 값 | 상태 |
| --- | --- | --- |
| Caller account | `443915990705` | `적용` |
| SSO Role | `AWSReservedSSO_team03-admin_a85bec6c993e8a28` | `적용` |
| 연결 Policy | AWS managed `AdministratorAccess` | `적용` |
| Max session | `43200초` | `적용` |
| Permissions boundary | 없음 | 현재 설정 `적용` |
| 이번 감사의 Read-only 강제 | Admin Role에서 조회 명령만 사용 | 절차적 `승인 예외` |

### 3.2 Organizations·SCP

| 항목 | 실제 값 | 판정 |
| --- | --- | --- |
| Organization | `o-39rlmf50iq`, Feature Set `ALL` | `적용` |
| 계정 역할 | Organization management account | `적용` |
| SCP 기능 | Enabled | `적용` |
| 존재하는 SCP | AWS managed `FullAWSAccess` | `적용` |
| 계정 대상 직접 적용 조회 | 반환된 Policy 없음 | `적용` |

Management account의 운영자 권한을 SCP가 안전 경계로 제한한다고 가정하지 않는다.

### 3.3 IAM 계정 정책

| 항목 | 실제 값 | 상태·주의 |
| --- | --- | --- |
| Root account MFA summary | `AccountMFAEnabled=1` | `적용` |
| Account password policy | 없음 | 현재 설정 `적용`; SSO 암호 정책과 별개 |
| IAM user | `boardtest` 한 명 | `적용` |
| `boardtest` attached policy | `AmazonS3FullAccess` | `적용`; 필요성·최소 권한 검토 필요 |
| `boardtest` Access key | 없음 | 현재 자격증명 없음 |
| `boardtest` Console login | 없음 | 현재 자격증명 없음 |
| `boardtest` MFA | 없음 | 자격증명 생성 전 재검토 필요 |

`boardtest`는 Access key, Console login profile, SSH public key, service-specific
credential, signing certificate가 모두 없다. 현재 로그인 수단은 없지만 향후
자격증명이 추가되면 S3 전체 권한이 즉시 활성화되는 잠재 권한 주체다. IAM
password policy 부재는 현재 이 user에 직접 적용되는 로그인 위험이 아니며, IAM
Console user를 새로 만들기 전에 필요 여부를 결정한다.

### 3.4 계정 기본 보안 설정

| 항목 | 실제 값 | 상태 |
| --- | --- | --- |
| EBS encryption by default | `false` | `미완료` |
| EBS default KMS | `alias/aws/ebs` | 기본 key만 지정 |
| Account-level S3 Public Access Block | 설정 없음 | `미완료` |
| VPC Block Public Access | `InternetGatewayBlockMode=off` | 현재 public ALB·EC2 구조에 `적용` |

개별 Green·ASG volume과 Web bucket은 별도로 암호화·차단돼 있지만 EBS·S3 계정
기본값이 새 리소스를 자동 보호하지는 않는다. VPC Block Public Access의 `off`는
현재 internet-facing ALB와 Public ASG·rollback EC2를 사용하는 설계와 일치하며,
애플리케이션을 Private Subnet으로 전환할 때 다시 검토한다.

---

## 4. VPC·Subnet·Route·NACL 정책

### 4.1 VPC

| 항목 | 실제 값 |
| --- | --- |
| Name / ID | `buildgraph-demo-vpc` / `vpc-06c90b864a62f93a4` |
| CIDR | `10.0.0.0/16` |
| State | `available` |
| Tenancy | `default` |
| DNS support | 활성 |
| DNS hostnames | 활성 |
| Internet Gateway | `buildgraph-demo-igw` / `igw-0eeddf412eaad84c9` |
| NAT Gateway | 없음 |
| VPC Flow Log | 없음 |

### 4.2 Subnet

| 구분 | Name / ID | AZ | CIDR | Available IPv4 | 자동 Public IPv4 |
| --- | --- | --- | --- | ---: | --- |
| Public | `buildgraph-demo-subnet-public1-ap-northeast-2a` / `subnet-0b48bd72162060261` | 2a | `10.0.0.0/20` | 4089 | 비활성 |
| Public | `buildgraph-demo-subnet-public2-ap-northeast-2b` / `subnet-0db73cf18a85ea8f1` | 2b | `10.0.16.0/20` | 4085 | 비활성 |
| Private Data | `buildgraph-demo-subnet-private-data1-ap-northeast-2a` / `subnet-09bba1fd17639ce6a` | 2a | `10.0.32.0/24` | 251 | 비활성 |
| Private Data | `buildgraph-demo-subnet-private-data2-ap-northeast-2b` / `subnet-0816bc2771fd5e1ca` | 2b | `10.0.33.0/24` | 248 | 비활성 |

Public Subnet의 `MapPublicIpOnLaunch=false`와 ASG Public IPv4 사용은 충돌하지 않는다. Launch Template의 NIC 설정이 Subnet 기본값을 덮어써 ASG 인스턴스에 Public IPv4를 할당한다.

### 4.3 Route Table

| Route Table | Subnet | Route |
| --- | --- | --- |
| `rtb-05bef98dcdf339683` Public | Public 2a·2b | `10.0.0.0/16 → local`, `0.0.0.0/0 → igw-0eeddf412eaad84c9` |
| `rtb-084440a81e5721f2f` Private Data | Private Data 2a·2b | `10.0.0.0/16 → local`만 |
| `rtb-06ebc9091b81d6788` Main | Main association | `10.0.0.0/16 → local`만 |

Private Data Subnet에는 인터넷·NAT 기본 경로가 없다. NAT 또는 ECR·S3·SSM·Secrets Manager·CloudWatch용 VPC Endpoint를 준비하기 전에는 ASG의 Public IPv4를 끄지 않는다.

### 4.4 NACL·VPC Endpoint

| 항목 | 실제 값 | 판정 |
| --- | --- | --- |
| NACL | 기본 `acl-0a75907f80d8a6f04` | `적용` |
| 연결 | 네 Subnet 전체 | `적용` |
| Inbound / Outbound | Rule 100 전체 허용, 마지막 Rule 전체 거부 | SG가 실제 접근 경계 |
| Amazon MQ Endpoint | `vpce-0510f62ee4020f51d` / Interface / Private 2b | `적용` |
| Endpoint 관리 | `RequesterManaged=true`, `AMQManaged=true` | MQ 관리 리소스 |

Amazon MQ Endpoint는 일반 애플리케이션 outbound를 해결하는 사용자 관리 Endpoint로 간주하지 않는다.

---

## 5. Security Group 정책

### 5.1 현재 Rule

| SG | Inbound | Outbound | 상태 |
| --- | --- | --- | --- |
| 공유 EC2 `sg-099aac782b77a854e` | TCP 80: `0.0.0.0/0` + ALB SG, TCP 22: `0.0.0.0/0` | 전체 Protocol: `0.0.0.0/0` | `미완료`·역사적 롤백 경로 |
| ALB `sg-0f3f05d71747114a2` | TCP 80: CloudFront prefix list `pl-22a6434b` + 작업자 `1.238.129.195/32` | TCP 80: 공유 EC2 SG + ASG SG | `/32`는 `승인 예외` |
| ASG EC2 `sg-0a0a2fe0e54027420` | TCP 80: ALB SG만 | TCP 443: `0.0.0.0/0`; 5432→RDS SG; 6379→Redis SG; 5671→RabbitMQ SG | `적용` |
| RDS `sg-0587fdbc766f9088f` | TCP 5432: 공유 EC2 SG + ASG SG | 전체 Protocol: `0.0.0.0/0` | `적용` |
| Redis `sg-0dc3c8766358e57f4` | TCP 6379: 공유 EC2 SG + ASG SG | TCP 6379: 공유 EC2 SG | `적용` |
| RabbitMQ `sg-0876855a9ac1da572` | TCP 5671: 공유 EC2 SG + ASG SG | TCP 5671: 공유 EC2 SG | `적용` |

### 5.2 경계 원칙

- 인터넷에 8080·8091·5432·6379·5671을 공개하지 않는다.
- ASG는 공유 EC2 SG, ALB SG, default SG를 연결하지 않고 ASG 전용 SG 하나만 사용한다.
- Data SG는 기존 수동 Green rollback 때문에 공유 EC2 SG source를 유지하며 ASG SG source를 병렬 허용한다.
- ALB SG inbound에 `0.0.0.0/0`를 추가하지 않는다.
- 공유 EC2 SG의 22·80 공개는 수동 Green·Blue rollback 종료 후 제거·분리 대상으로 유지한다.

### 5.3 CloudFront prefix list·Quota

| 항목 | 값 |
| --- | --- |
| Prefix list | `com.amazonaws.global.cloudfront.origin-facing` / `pl-22a6434b` |
| Owner | AWS |
| Address family | IPv4 |
| Security Group rule quota | 60 |
| AWS 문서상 CloudFront prefix list weight | 55 |

CloudFront prefix list 하나만 사용하면 기본 quota 60에서 추가 weight 여유는 5다.
현재는 작업자 `/32` Rule weight 1도 함께 사용하므로 ALB SG inbound의 실제 사용량은
56, 잔여 quota는 4다. quota가 부족할 때 `0.0.0.0/0`로 우회하지 않는다.

### 5.4 확인된 태그 드리프트

ALB SG의 Name 태그 key가 정확한 `Name`이 아니라 공백·탭이 섞인 `" Name\t"`로 저장돼 있다. 기능에는 직접 영향이 없지만 태그 검색·비용 분류·자동화에서 누락될 수 있다.

---

## 6. Private S3·CloudFront·WAF 정책

### 6.1 Private Web Bucket

| 항목 | 실제 값 | 상태 |
| --- | --- | --- |
| Bucket | `buildgraph-demo-web-green-443915990705` | `적용` |
| Block Public ACL / Policy | 네 항목 모두 활성 | `적용` |
| Policy public 판정 | `false` | `적용` |
| Object Ownership | `BucketOwnerEnforced` | `적용` |
| Encryption | SSE-S3 `AES256`, SSE-C 차단 | `적용` |
| Versioning | Enabled | `적용` |
| Static website | 없음 | REST origin 사용 |
| CORS | 없음 | `적용` |
| Lifecycle / Replication | 없음 | 비용·복구 정책 `미완료` |
| Access logging / Notification | 없음 | 관측 정책 `미완료` |

Bucket Policy는 `cloudfront.amazonaws.com` Principal에 `s3:GetObject`만 허용하고, `AWS:SourceArn`을 Green Distribution `E1MVNMU0O781IM`으로 제한한다.

### 6.2 OAC·SPA Function

| 항목 | 실제 값 | 상태 |
| --- | --- | --- |
| 사용 중 OAC | `ETLAXAQDCBJF1`, SigV4 `always` | `적용` |
| 미사용 중복 OAC | `E32Q7M4JULG23V` | `드리프트` |
| SPA Function | `buildgraph-demo-web-spa-rewrite` / `LIVE` / `cloudfront-js-2.0` | `적용` |
| Association | Default behavior `viewer-request` | `적용` |

### 6.3 Green CloudFront

| 항목 | 실제 값 |
| --- | --- |
| ID / Domain | `E1MVNMU0O781IM` / `d2qhd7deuwmlln.cloudfront.net` |
| State | Enabled / `Deployed` |
| IPv6 / HTTP | IPv6 활성 / HTTP/2 |
| Viewer protocol | 모든 Behavior HTTP→HTTPS redirect |
| Certificate | CloudFront default certificate |
| Alias | 없음 |
| Price Class | `PriceClass_All` |
| Geo restriction | 없음 |
| Access logging | 비활성 |
| Custom error response | 없음 |
| Continuous Deployment | Policy 0개, Staging 없음 |

Behavior 정책:

| Path | Origin | Methods | Cache | Origin request | Compress |
| --- | --- | --- | --- | --- | --- |
| Default `*` | Private S3+OAC | GET, HEAD | Managed CachingOptimized | 없음 | Yes |
| `/api/*` | ALB | 모든 API method | Managed CachingDisabled | Managed AllViewer | Yes |
| `/ws/*` | ALB | GET, HEAD, OPTIONS | Managed CachingDisabled | Managed AllViewer | Yes |

Managed policy 실제값:

| Policy | Min TTL | Default TTL | Max TTL | 전달 정책 |
| --- | ---: | ---: | ---: | --- |
| CachingOptimized | 1초 | 86400초 | 31536000초 | header·cookie·query를 cache key에 포함하지 않음 |
| CachingDisabled | 0 | 0 | 0 | API·WS cache 비활성 |
| AllViewer | - | - | - | 모든 viewer header·cookie·query string을 origin으로 전달 |

`index.html`이 `no-cache`를 보내더라도 CachingOptimized의 Min TTL 1초가 우선할 수 있다.

### 6.4 WAF

| 항목 | 실제 값 |
| --- | --- |
| Web ACL | `CreatedByCloudFront-cf51f62f` |
| Default action | Allow |
| Capacity | 925 |
| Metrics / sampled requests | 활성 |
| Amazon IP Reputation | Count |
| Common Rule Set | Count |
| Known Bad Inputs | Count |

세 managed rule은 탐지·계측만 하고 요청을 차단하지 않는다. WAF 연결 자체를 “차단 정책 적용 완료”로 해석하지 않는다.

---

## 7. ALB·Listener·Target Group 정책

### 7.1 ALB

| 항목 | 실제 값 |
| --- | --- |
| Name | `buildgraph-demo-api-green-alb` |
| ARN suffix | `app/buildgraph-demo-api-green-alb/c2a7e4158cc0ffd7` |
| DNS | `buildgraph-demo-api-green-alb-1359641784.ap-northeast-2.elb.amazonaws.com` |
| Scheme / Type | internet-facing / application |
| IP type | IPv4 |
| Subnet | Public 2a·2b |
| Security Group | ALB SG 하나 |
| State | active |

Attribute 정책:

| Attribute | 값 | 상태 |
| --- | ---: | --- |
| Idle timeout | 3600초 | WebSocket 고려 `적용` |
| Client keep-alive | 3600초 | `적용` |
| Cross-zone | 활성 | `적용` |
| HTTP/2 | 활성 | `적용` |
| Desync mitigation | defensive | `적용` |
| WAF fail-open | 비활성 | `적용` |
| Deletion protection | 비활성 | `미완료` |
| Access / connection / health log | 모두 비활성 | `미완료` |

### 7.2 Listener

| 항목 | 현재 값 | 판정 |
| --- | --- | --- |
| Listener | HTTP 80 |
| Rule | default rule 하나 |
| Default action | Target Group forward |
| HTTP header condition | 없음 |
| Fixed 403 | 없음 |

### 7.3 Target Group

| 항목 | 실제 값 |
| --- | --- |
| Name | `buildgraph-demo-api-green-tg` |
| Target type | instance |
| Protocol / Port | HTTP1 / 80 |
| Health path | `/api/health` |
| Matcher | 200 |
| Interval / Timeout | 30초 / 5초 |
| Healthy / Unhealthy threshold | 2 / 2 |
| Deregistration delay | 300초 |
| Algorithm | round robin |
| Stickiness | 비활성 |
| Current Target | `i-0d8bfaec8aab8cc65:80` healthy |

`/api/health`는 DB까지 확인하는 readiness다. ASG 자체 health는 EC2만 사용해 공유 RDS 장애가 모든 EC2 교체로 이어지는 replacement storm을 피한다.

### 7.4 Origin 보호 미완료

| 항목 | 현재 상태 |
| --- | --- |
| CloudFront→ALB transport | HTTP only |
| Origin custom header | 0개 |
| Header 일치 listener rule | 없음 |
| Default fixed 403 | 없음 |
| Network 제한 | CloudFront prefix list + 작업자 `/32` |

CloudFront prefix list는 CloudFront origin-facing 주소 전체를 허용하며 우리 Distribution만 식별하지 않는다. AWS 권장 구조인 custom header 일치 시 forward, 나머지 default 403, origin HTTPS는 후속 작업으로 유지한다.

---

## 8. EC2·EBS·AMI·Launch Template·ASG 정책

### 8.1 현재 EC2

| 역할 | Instance | AZ | Public IP | AMI | SG | State |
| --- | --- | --- | --- | --- | --- | --- |
| ASG live | `i-0d8bfaec8aab8cc65` | 2a | `3.35.19.43` | `ami-0b047a683dc98c08a` | ASG SG | Running |
| 수동 Green rollback | `i-033105106a7970ac1` | 2b | `43.203.33.190` EIP | Ubuntu base | 공유 EC2 SG | Running |
| Blue rollback | `i-082c21a20e14f3295` | 2b | `15.164.235.183` | Ubuntu base | 공유 EC2 SG | Running |
| AMI Builder | `i-019d4e7050d6d641d` | 2b | `3.36.14.185` | Ubuntu base | ASG SG | Running |
| 구 AMI 검증 | `i-0466ca0e85e32f03c` | 2b | `3.34.28.242` | 사용 금지 AMI | ASG SG | Running |
| 현 AMI 검증 | `i-0426a04c6deec0597` | 2b | `43.202.111.29` | 현 Custom AMI | ASG SG | Running |

Builder와 검증 EC2 두 대는 계획상 검증 완료 후 종료 대상이지만 현재도 Running이다. 세 인스턴스 모두 Public IPv4와 Secret 조회가 가능한 Green Instance Profile을 가진다.

### 8.2 공통 EC2 보안

| 항목 | ASG | 수동 Green·Blue |
| --- | --- | --- |
| Instance type | `t3.medium` | `t3.medium` |
| IMDS | V2 token required, hop limit 2 | 동일 |
| Detailed Monitoring | 활성 | 활성 |
| ASG Key pair | 없음, SSM 전용 | 기존 RSA Key pair 존재 |
| Shutdown behavior | terminate | stop |
| Termination protection | 비활성 | 수동 Green 활성·Blue 비활성은 2026-07-14 마지막 확인값이며 이번 감사에서 재조회하지 않음 |

### 8.3 EBS

| 대상 | Root EBS | 암호화 |
| --- | --- | --- |
| ASG·수동 Green·Builder·검증 | gp3 30 GiB, 3000 IOPS, 125 MiB/s | `alias/aws/ebs`로 암호화 |
| Blue | gp3 30 GiB | 비암호화 `드리프트` |

모든 확인 대상 root volume은 Delete on termination이 활성이다. 인스턴스 교체·종료 시 로컬 Docker volume도 함께 사라진다는 전제로 운영한다.

### 8.4 Custom AMI

| 항목 | 실제 값 |
| --- | --- |
| AMI | `ami-0b047a683dc98c08a` |
| Name | `buildgraph-demo-api-green-asg-e7d481bdf7b4-20260716-1304` |
| State / Public | available / private |
| Architecture | x86_64 |
| Snapshot | `snap-0024cd18e0c379fdc` |
| Snapshot encryption | 활성, `alias/aws/ebs` |
| External create-volume permission | 없음 |
| GitSha tag | `e7d481bdf7b455723f888df8d1522adae97b0c85` |
| Validation tag | `passed` |

수정 전 bootstrap이 들어 있는 `ami-07aa52b036822d054`는 사용하지 않는다.

### 8.5 Launch Template

| 항목 | 실제 값 |
| --- | --- |
| Name / ID | `buildgraph-demo-api-green-lt` / `lt-0024991a1e82e5e6c` |
| 고정 version | `1` |
| AMI / Type | 현 Custom AMI / `t3.medium` |
| IAM Profile | `buildgraph-demo-api-green-role` |
| SG | ASG SG 하나 |
| Subnet | Template에 고정하지 않음 |
| Public IPv4 | 활성 |
| EBS | encrypted gp3 30 GiB, Delete on termination |
| Monitoring / IMDS | 상세 모니터링, IMDSv2 required |
| User data | 존재하지만 원문 미조회 |

ASG는 `$Latest`나 `$Default` 자동 추종 대신 검증된 특정 version을 사용한다.

### 8.6 ASG

| 항목 | 실제 값 |
| --- | --- |
| Name | `buildgraph-demo-api-green-asg` |
| Min / Desired / Max | `1 / 1 / 1` |
| Subnet | Public 2a·2b |
| Launch Template | `lt-0024991a1e82e5e6c` version `1` |
| Health type / grace | EC2 / 300초 |
| Default warmup | 미설정 |
| Scaling policy | 없음 |
| Scheduled action | 없음 |
| Lifecycle hook | 없음 |
| Current instance | `i-0d8bfaec8aab8cc65`, InService/Healthy |

현재 단계는 EC2 장애나 수동 instance 종료 시 한 대를 복원하는 기반을 검증하는
단계이며 부하 기반 scale-out이 아니다. `HealthCheckType=EC2`이므로 cloud-init,
Nginx, API 또는 Target health만 실패한 instance를 ASG가 자동 교체하는 정책은 아직
적용되지 않았다.

---

## 9. 관리형 데이터 서비스 정책

### 9.1 RDS PostgreSQL

| 항목 | 실제 값 | 상태 |
| --- | --- | --- |
| Identifier | `buildgraph-demo-postgres-green` | `적용` |
| Engine / Class | PostgreSQL 16.14 / `db.t4g.small` | `적용` |
| AZ / Multi-AZ | 2b / 비활성 | Single-AZ |
| Public access | 비활성 | `적용` |
| Subnet | Private Data 2a·2b | `적용` |
| SG / Port | RDS SG / 5432 | `적용` |
| Storage | encrypted gp3 30 GiB, max 1000 GiB | 계획과 `드리프트` |
| KMS | `alias/aws/rds`, AWS managed, 365일 rotation | `적용` |
| Backup retention | 1일 | 짧은 보존 |
| Manual snapshot | 없음 | `미완료` |
| Automated snapshot | 최근 3개 확인 | `적용` |
| Deletion protection | 비활성 | `미완료` |
| Performance Insights | 활성 | `적용` |
| Enhanced Monitoring | 비활성 | `미완료` |
| PostgreSQL log export | 활성 | `적용` |

초기 문서의 20 GiB·최대 100 GiB·deletion protection 활성과 실제값이 다르다.

### 9.2 ElastiCache Redis

| 항목 | 실제 값 | 상태 |
| --- | --- | --- |
| Replication Group | `buildgraph-demo-redis-green` | `적용` |
| Engine / Type | Redis OSS 7.1.0 / `cache.t4g.small` | `적용` |
| Node / AZ | primary 1대 / 2b | Single-AZ |
| Cluster / Multi-AZ / Failover | 모두 비활성 | `미완료` |
| Transit encryption | 활성, Required | `적용` |
| At-rest encryption | 활성 | `적용` |
| AUTH token | 활성 | `적용` |
| Snapshot retention | 0일 | `미완료` |
| Log delivery | 없음 | `미완료` |

### 9.3 Amazon MQ RabbitMQ

| 항목 | 실제 값 | 상태 |
| --- | --- | --- |
| Broker | `buildgraph-demo-rabbitmq-green` | `적용` |
| Engine / Type | RabbitMQ 3.13.7 / `mq.m7g.medium` | `적용` |
| Deployment | SINGLE_INSTANCE / Private 2b | Single-AZ |
| Public access | 비활성 | `적용` |
| Protocol | AMQPS 5671 | `적용` |
| Authentication | simple username/password | `적용` |
| Encryption | AWS owned key | `적용` |
| General log | 활성 | `적용` |
| Automatic minor upgrade | 활성 | `적용` |

ASG를 여러 AZ로 늘려도 RDS·Redis·RabbitMQ가 현재 단일 2b 구성이라 데이터 계층 HA가 자동으로 생기지 않는다.

---

## 10. IAM Role·OIDC·Secret·KMS·ECR·SSM 정책

### 10.1 EC2 Role

| Role | Trust | 권한 | Boundary |
| --- | --- | --- | --- |
| `buildgraph-demo-api-green-role` | `ec2.amazonaws.com` | SSM Core, CloudWatch Agent, ECR ReadOnly, 지정 Green Secret Get/Describe | 없음 |
| `buildgraph-demo-ec2-role` | `ec2.amazonaws.com` | SSM Core | 없음 |

Green Role의 inline policy는 `buildgraph/demo-green/api-env` Secret ARN 하나에만 `DescribeSecret`, `GetSecretValue`를 허용한다. 그러나 같은 Role을 live, 수동 Green, Builder, 검증 EC2가 함께 사용하므로 Builder 격리는 IAM이 아니라 절차와 bootstrap 검증에 의존한다.

### 10.2 GitHub OIDC·Deploy Role

| 항목 | 실제 정책 |
| --- | --- |
| OIDC Provider | `token.actions.githubusercontent.com` |
| Audience | `sts.amazonaws.com` |
| Trust subject | `repo:jungle-final-project/prototype:ref:refs/heads/main` |
| Deploy Role | `buildgraph-demo-github-actions-green-role` |
| ECR | API·XGB 두 repository push/pull 관련 action |
| S3 | Green bucket list/get/put/delete |
| CloudFront | Green Distribution invalidation |
| SSM SendCommand | `AWS-RunShellScript` + 수동 Green `i-033105106a7970ac1`만 |
| Secret·IAM PassRole·Blue SSM | 허용 없음 |

Role은 main branch로 제한돼 있지만 배포 대상이 수동 Green 고정 Instance ID라 ASG replacement와 호환되지 않는다.

### 10.3 Secrets Manager

| 항목 | 실제 값 |
| --- | --- |
| Secret | `buildgraph/demo-green/api-env` |
| Encryption | 기본 `aws/secretsmanager` |
| Rotation | 비활성 |
| Region replication | 없음 |
| Resource policy | 없음 |
| Tags | 없음 |
| Last accessed | 2026-07-16 |

Phase 6과 bootstrap 계약은 RDS·Redis·MQ·JWT·OAuth·외부 API 관련 환경값을 이
dotenv Secret에서 공급하도록 설계돼 있다. 이번 감사에서는 Secret 원문을 조회하지
않았으므로 실제 key 구성과 값의 존재 여부는 확인하지 않았다. bootstrap은 조회한
원문을 `.env.prod` mode 600으로 materialize하며 stdout·cloud-init·CloudWatch에
출력하지 않아야 한다.

### 10.4 ECR

| Repository | Mutability | Scan | Encryption | Lifecycle |
| --- | --- | --- | --- | --- |
| `buildgraph-demo-api-green` | IMMUTABLE | Basic scan-on-push | AES256 | 50개 초과 expire |
| `buildgraph-demo-xgb-reranker-green` | IMMUTABLE | Basic scan-on-push | AES256 | 30개 초과 expire |

- Repository policy와 registry policy는 없다.
- `latest` tag를 사용하지 않고 40자리 Git SHA tag와 digest를 사용한다.
- XGB lifecycle description은 50개 보존이라고 쓰였지만 실제 `countNumber`는 30이다.

ASG release manifest에 고정되고 최근 완료 기록에서 검증된 이미지 scan:

| Image | Git SHA tag | Digest | Scan 결과 |
| --- | --- | --- | --- |
| API | `0a49108f1376df2f73deb7e0cf5fc7997faf1108` | `sha256:aae7ad00b42e07b372a03aa12f6b354d4c6c0f3a780b1d1f8d91d52ef3d9b267` | HIGH 2, MEDIUM 6 |
| XGB | `0a49108f1376df2f73deb7e0cf5fc7997faf1108` | `sha256:d71bd6a390c747b306c3e9bfd302059d04ec691b755c2abc90115b797bf565a9` | CRITICAL 4, HIGH 8, MEDIUM 5 |

취약점 개수는 ECR scan 결과이며 실제 exploit 가능성·영향 범위 판정을 대체하지 않는다. 그러나 Critical finding을 검토하지 않은 상태를 보안 완료로 표시하지 않는다.

### 10.5 SSM

| 대상 | 상태 |
| --- | --- |
| Blue `i-082c21a20e14f3295` | Online |
| 수동 Green `i-033105106a7970ac1` | Online |
| ASG `i-0d8bfaec8aab8cc65` | Online |
| AMI Builder `i-019d4e7050d6d641d` | Online |
| 구 AMI 검증 `i-0466ca0e85e32f03c` | Online |
| 현 AMI 검증 `i-0426a04c6deec0597` | Online |

Inbound 관리 포트를 새 ASG SG에 열지 않고 Session Manager·Run Command와 Instance Profile을 사용한다. 이번 감사에서는 `SendCommand`를 실행하지 않았다.

---

## 11. 버전·배포 정책

### 11.1 현재 버전 축

서로 다른 SHA를 하나의 “현재 버전”으로 합치지 않는다.

| 축 | 값 | 의미 |
| --- | --- | --- |
| GitHub remote `main` | `45c63b6fc89e52d1a1337266ca5664b89fa94330` | 현재 원격 저장소 HEAD |
| Local working branch HEAD | `61265b1df6af4d8f49468243333ca1a214d65c6c` | `hotfix/asg-bootstrap-git-ownership` 작업 SHA |
| Local `origin/main` ref | `e7d481bdf7b455723f888df8d1522adae97b0c85` | fetch 전 로컬 remote-tracking ref |
| ASG AMI/bootstrap SHA | `e7d481bdf7b455723f888df8d1522adae97b0c85` | Custom AMI와 Launch Template 검증 SHA |
| 최근 검증된 API·XGB image tag | `0a49108f1376df2f73deb7e0cf5fc7997faf1108` | manifest에 고정된 이미지 build SHA; 이번 감사에서 runtime 재검증 안 함 |
| ECR에 가장 최근 push된 API SHA tag | `45c63b6fc89e52d1a1337266ca5664b89fa94330` | live 배포 의미 아님 |
| ECR에 가장 최근 push된 XGB SHA tag | `90afbffbcdc2cb7c687152362866409664cae61a` | live 배포 의미 아님 |

AMI가 검증한 bootstrap 코드 SHA와 컨테이너 image build SHA는 역할이 다르므로 같아야 한다는 계약은 없다. manifest의 digest와 실제 running image 일치가 실행 이미지 계약이다.

### 11.2 GitHub Actions 정책

| 항목 | 현재 값 | 상태 |
| --- | --- | --- |
| `GREEN_CD_ENABLED` | `false` | 검증 기간 배포 동결 `적용` |
| `GREEN_EC2_INSTANCE_ID` | `i-033105106a7970ac1` | ASG와 비호환 `드리프트` |
| API/XGB push trigger | main path 기반, CD flag 필요 | `적용` |
| Manual dispatch | CD flag와 무관하게 실행 가능 | 운영상 수동 실행 금지 |
| `publish_only` 기본 | false | 잘못 실행하면 수동 Green 배포 |
| Workflow permission | `contents:read`, `id-token:write` | 최소 GitHub 권한 `적용` |
| Concurrency | API·XGB·Web group 별도, cancel-in-progress false | 서비스별 workflow 분리; 수동 Green host의 공통 flock에서 직렬화 |

현재 사용자 트래픽은 ASG에 가므로 수동 workflow를 실행하면 수동 Green만 새 버전으로 바뀌고 live ASG는 그대로 남는다. replacement drill 완료 전 API/XGB 수동 `Run workflow`도 실행하지 않는다.

### 11.3 ASG 배포 목표

ASG 배포는 다음 흐름을 목표로 한다.

```text
검증된 Git SHA와 immutable image digest 확정
→ 새 AMI 또는 release manifest 검증
→ Launch Template 새 version 생성
→ ASG Instance Refresh
→ 새 Target Healthy·smoke 확인
→ 실패 시 이전 Launch Template version rollback
```

고정 Instance ID 대상 SSM 배포나 `$Latest` 자동 추종을 ASG 최종 배포 방식으로 사용하지 않는다.

---

## 12. Runtime·Bootstrap·Scheduler 정책

### 12.1 컨테이너 경계

허용 Compose service는 `nginx`, `api`, `xgb-reranker`다.

- Nginx 80만 host에 공개한다.
- API 8080과 XGB 8091은 Docker network 내부 전용이다.
- RDS 5432, Redis 6379, RabbitMQ 5671을 host에 공개하지 않는다.
- `/healthz`는 Nginx liveness, `/api/health`는 DB 포함 readiness로 구분한다.

### 12.2 Bootstrap 계약

- `set -Eeuo pipefail`, `umask 077`을 사용한다.
- 계정·리전·IMDSv2·IAM Role을 검증한다.
- Secret 원문을 user data, stdout, cloud-init, CloudWatch에 출력하지 않는다.
- `.env.prod`, image manifest, ASG runtime env는 `ubuntu:ubuntu`, mode 600으로 저장한다.
- API·XGB·Nginx immutable digest를 manifest에 기록한다.
- `docker compose config --quiet`, `nginx -t`, running image 일치, `/api/health`를 확인한다.
- 성공 marker에는 Git/image 식별자만 기록한다.
- 최초 boot→bootstrap 성공 marker와 local `/api/health` 확인까지 최근 실측은 약
  140초다. Target Group `Healthy` 도달시간은 별도로 측정되지 않았으며 ASG health
  grace는 현재 300초다.

### 12.3 Local artifact

`agent-log-data`, `recommendation-models` 같은 local Docker volume은 instance replacement 시 함께 삭제되고 다른 instance가 볼 수 없다. 공유 storage 또는 immutable artifact 계약이 끝나기 전에는 다중 인스턴스 안전 조건을 통과한 것으로 보지 않는다.

### 12.4 Scheduler

- 승인 정책상 기존 수동 Green 한 대만 가격·메일·외부 AI scheduled job을 실행한다.
- ASG API 컨테이너는 `BUILDGRAPH_SCHEDULING_ENABLED=false`를 유지한다.
- 수동 Green을 종료하기 전에 singleton scheduler/worker를 별도로 준비한다.
- ASG `Max > 1`에서 web instance가 scheduled job을 실행해서는 안 된다.

ASG의 scheduler `false`는 최근 완료 기록에서 확인했지만 수동 Green의 scheduler
`true`는 이번 감사에서 재검증하지 않았다. 따라서 “수동 Green만 실행”은 승인된
운영 정책이며 현재 runtime 사실로 단정하지 않는다.

---

## 13. 관측·감사 정책

### 13.1 CloudWatch Logs

| Log Group | Retention | KMS |
| --- | ---: | --- |
| `/buildgraph/demo/api-green/docker` | 14일 | 없음 |
| RDS PostgreSQL | 만료 없음 | 없음 |
| MQ general | 만료 없음 | 없음 |
| MQ connection | 만료 없음 | 없음 |

Docker log는 instance ID stream을 사용한다. RDS·MQ 무기한 보존은 비용 정책과 함께 재검토한다.

### 13.2 경보·감사 서비스

다음 `없음` 판정은 `ap-northeast-2`와 해당 조회 endpoint에서 확인한 결과다.
이번 감사에서는 모든 AWS Region을 순회하지 않았으므로 계정 전체에 존재하지
않는다고 확대 해석하지 않는다.

| 항목 | 실제 상태 | 판정 |
| --- | --- | --- |
| BuildGraph CloudWatch Alarm | 서울 리전 0개 | `미완료` |
| CloudWatch Dashboard | 서울 리전 0개 | `미완료` |
| AWS Config Recorder / Delivery | 서울 리전 없음 | `미완료` |
| CloudTrail Trail | 서울 리전 조회에서 없음 | `미완료` |
| CloudTrail service channel | Resource Explorer용만 존재 | 지속 감사 Trail 아님 |
| GuardDuty Detector | 서울 리전 없음 | `미완료` |
| Access Analyzer | 서울 리전 없음 | `미완료` |
| Security Hub | 서울 리전 미가입 | `미완료` |

CloudTrail Event history가 제공되더라도 별도 Trail·S3 보존·log validation 정책이 구성된 것은 아니다.

### 13.3 2단계 전 Alarm 후보

- ALB `HTTPCode_ELB_5XX`, `HTTPCode_Target_5XX`, `TargetResponseTime`
- Target Group `HealthyHostCount`, `UnHealthyHostCount`
- ASG desired·in-service·pending·terminating 불일치
- EC2 CPU·memory·disk, Docker restart/OOM
- RDS CPU·connection·storage, Redis CPU·memory·credit, MQ queue·connection
- bootstrap·launch·terminate 실패 알림

Alarm·notification은 팀이 action 대상과 수신자를 합의한 뒤 별도 변경으로 만든다.

---

## 14. Rollback·부하 테스트·비용 정책

### 14.1 Rollback

- CloudFront 전환은 `/api/*`를 먼저 바꾸고 검증한 뒤 `/ws/*`를 바꾼다.
- WebSocket만 실패하면 `/ws/*`만 기존 EC2 origin으로 되돌린다.
- 공통 API/ALB 장애면 `/api/*`, `/ws/*` 모두 기존 EC2 origin으로 되돌리고 `Deployed`를 기다린다.
- ASG 첫 instance 실패 시 CloudFront `/api/*`, `/ws/*`를 기존 EC2 origin으로 먼저
  전환하고 `Deployed`와 smoke를 확인한 뒤 ASG를 `0/0/1`로 축소할 수 있다.
- 코드·image rollback은 DB·Redis·RabbitMQ 데이터를 되돌리지 않는다.
- 기존 수동 Green, EIP, EC2 origin과 현재 검증된 AMI·Launch Template version을
  rollback 검증 전에 삭제하지 않는다. 이후 검증된 이전 release가 생기면 rollback
  후보로 보존하되, 사용 금지 AMI `ami-07aa52b036822d054`는 rollback 후보가 아니다.

### 14.2 부하 테스트

- 배포, Instance Refresh, AMI 변경과 부하 테스트를 동시에 실행하지 않는다.
- 서버 자체가 아닌 별도 load injector에서 실행한다.
- Stage 0에서 dashboard·alarm·log·metric 수집이 같은 시간축으로 동작하는지 먼저
  확인하고 5 VU smoke를 수행한다.
- 초기 RPS는 2→5→10→20→40→80 순서로 올린다.
- VU 시험은 10→30→50→80→100 순서로 각 단계를 통과한 뒤
  200→300→500→1,000 VU를 진행한다.
- 1,000 VU와 1,000 RPS는 별도 시험이다.
- 실제 LLM·메일·가격 수집은 초기 혼합 부하에서 제외하거나 호출·비용 상한을 둔다.
- steady 단계 뒤 1시간 soak와 별도 spike를 수행한다.
- 합격 기준은 비LLM API p95 500ms 이하, 정상 요청 오류율 1% 이하, dropped
  iteration 0, restart/OOM 0, steady CPU 70% 이하, Hikari pending 지속 증가 없음이다.
- p95 2초 초과 3분, 오류율 1% 초과 2분, health 2회 연속 실패, OOM, CPU 85%
  5분, DB connection 80% 초과 중 하나면 즉시 중단한다.
- p95, 오류율, dropped iteration, Target health, restart/OOM, DB connection을 같은 시간축으로 본다.
- 현재 `Max 1`에서는 단일 Target 용량 시험일 뿐 scale-out 검증이 아니다. 16.3의
  하드 게이트 전에는 시험을 이유로 `Max 3`을 활성화하지 않는다.
- 부하 종료 후 instance만 Stop하지 않고 승인된 Min/Desired/Max로 복구한다.

세부 시나리오와 endpoint별 기준은 [부하 테스트 문서](부하테스트.md)를 실행
기준으로 사용한다.

### 14.3 비용

- ASG 기능 자체가 아니라 실행 EC2·EBS·Public IPv4·AMI snapshot이 과금된다.
- 현재 Blue, 수동 Green, ASG live, Builder, 검증 EC2 두 대가 동시에 실행돼
  EC2·EBS·Public IPv4 비용이 발생한다.
- ALB는 시간과 LCU, CloudFront는 전송·요청, Detailed Monitoring과 로그는 별도 과금 대상이다.
- NAT 고정비는 없지만 ASG Public IPv4 비용이 발생한다.
- S3 Versioning+Lifecycle 없음과 RDS·MQ 무기한 로그는 누적 비용 위험이다. RDS는
  현재 할당된 30 GiB가 과금되며 autoscaling으로 최대 1000 GiB까지 증가하면 비용이
  늘고 자동 축소되지 않는다.

---

## 15. 드리프트·위험 등록부

중요도는 이번 내부 감사의 운영 우선순위이며 AWS 서비스 자체의 공식 severity 등급이 아니다.

| 중요도 | 항목 | 현재 영향 | 상태 |
| --- | --- | --- | --- |
| 높음 | XGB 고정 image scan `CRITICAL 4 / HIGH 8` | 알려진 취약점 검토 근거 없음 | `미완료` |
| 중간 | API 고정 image scan `HIGH 2` | 알려진 취약점 검토 근거 없음 | `미완료` |
| 높음 | Builder 1대·검증 EC2 2대 Running | 비용·Public IP·Secret-capable Role 공격면 | `드리프트` |
| 높음 | 공유 EC2 SG 22·80 `0.0.0.0/0` | Blue·수동 Green 직접 접근 가능 | `미완료`·롤백 의존 |
| 높음 | CloudFront→ALB HTTP, header·403 없음 | Distribution별 origin 검증·전송 암호화 없음 | `미완료` |
| 높음 | 서울 리전 Alarm·Config·CloudTrail Trail 없음 | 장애 감지·구성 이력·지속 감사 부족 | `미완료` |
| 높음 | RDS deletion protection 비활성 | 운영 DB 실수 삭제 위험 | `미완료` |
| 높음 | GitHub SSM 대상 수동 Green 고정 | ASG live와 배포 버전 드리프트 | `드리프트` |
| 중간 | ALB SG 작업자 `/32` 유지 | 담당자 승인 아래 `1/1/3` 도달까지 직접 접근 경로 유지 | `승인 예외` |
| 중간 | WAF 세 Rule 모두 Count | 탐지하지만 차단하지 않음 | `미완료` |
| 중간 | 서울 리전 GuardDuty·Access Analyzer·Security Hub 미적용 | 위협 탐지·외부 접근 분석·통합 보안 상태 부족 | `미완료` |
| 중간 | Redis backup 0일·데이터 계층 Single-AZ | 장애 시 복구·가용성 제한 | `적용된 저비용 예외` |
| 중간 | 계정 EBS 기본 암호화 비활성 | 새 volume이 비암호화될 수 있음 | `미완료` |
| 중간 | 계정 S3 Public Access Block 없음 | 새 bucket 기본 보호가 강제되지 않음 | `미완료` |
| 중간 | `boardtest`의 `AmazonS3FullAccess` | 자격증명 추가 시 과도한 잠재 권한 | `미완료`·필요성 검토 |
| 중간 | ECR API 50개·XGB 30개 lifecycle | 문서·description·실제 보존 수 불일치 | `드리프트` |
| 중간 | RDS·MQ log 만료 없음 | 로그 비용 무기한 증가 | `미완료` |
| 중간 | Blue EBS 비암호화 | Blue disk 저장 데이터 비암호화 | `역사적 예외` |
| 낮음 | ALB SG Name tag key 오입력 | 검색·비용·자동화 누락 가능 | `드리프트` |
| 낮음 | 미사용 OAC 한 개 | 리소스 혼동 | `드리프트` |

---

## 16. ASG 단계 정책과 후속 하드 게이트

### 16.1 현재 유지 정책

- `Min 1 / Desired 1 / Max 1`을 유지한다.
- Scaling policy와 scheduled scaling을 만들지 않는다.
- `GREEN_CD_ENABLED=false`와 API/XGB 수동 workflow 금지를 유지한다.
- ASG scheduler는 false를 유지하고, 수동 Green scheduler만 활성이라는 승인 정책은
  runtime 재확인 전까지 정책값으로 취급한다.
- 작업자 `/32`는 담당자 `juhoseok`의 승인 예외로 유지하며 2단계 `1/1/3` 도달 시 삭제한다.
- replacement drill, origin 보호, 다중 인스턴스 준비를 완료로 표시하지 않는다.

### 16.2 다음 작업

1. CloudFront `/api/*`, `/ws/*`를 기존 수동 Green origin으로 격리한다.
2. `Deployed`와 기준 smoke를 확인한다.
3. ASG 현재 instance를 교체해 Launch Template·bootstrap 자동 복구를 검증한다.
4. 새 instance SSM·Git SHA·image digest·scheduler·health를 확인한다.
5. 새 Target만 Healthy가 된 뒤 API, WebSocket 순서로 ALB에 복귀한다.

### 16.3 `1/1/3` 진입 전 필수 조건

- [ ] replacement drill PASS
- [ ] WebSocket cross-instance fan-out 또는 공유 routing
- [ ] PC Agent cross-instance 진단 5초 계약
- [ ] Agent log shared storage
- [ ] XGB model immutable/shared artifact
- [ ] singleton scheduler/worker
- [ ] 고정 EC2 ID 없는 Launch Template·Instance Refresh 배포
- [ ] liveness/readiness 운영 방침
- [ ] Hikari × Max instance DB connection budget
- [ ] 두 AZ candidate launch
- [ ] CloudWatch Alarm·rollback 중단 기준
- [ ] origin custom header·default 403·HTTPS 또는 만료일이 있는 승인 예외

하나라도 미완료면 `Max 1`을 유지한다.

---

## 17. 과거 문서와의 주요 차이

| 과거 기록 | 현재 판정 |
| --- | --- |
| Phase 0~7 감사: CloudFront→수동 Green EC2 | 현재 `/api/*`, `/ws/*`는 ALB→ASG |
| ALB 가이드: Target은 수동 Green 한 대 | 현재 Target은 ASG instance 한 대 |
| ALB 가이드: Auto Scaling 없음 | 현재 ASG `1/1/1`, policy 없음 |
| ALB 가이드: `/32` 직접 검증 직후 삭제 | 최신 승인 예외는 `1/1/3` 도달 시 삭제 |
| Phase 3: RDS 20 GiB·최대 100 GiB·삭제 보호 | 실제 30 GiB·최대 1000 GiB·삭제 보호 비활성 |
| Phase 7: S3+EC2 origin 두 개 | 현재 S3+EC2 rollback+ALB 세 origin |
| Phase 7: custom cache min TTL 0 목표 | 실제 Web은 Managed CachingOptimized min TTL 1 |
| Phase 8: lifecycle 30개 | API 실제 50개, XGB 실제 30개 |
| Phase 8: CD 최종 활성 | 최신 검증 정책은 `GREEN_CD_ENABLED=false` |
| Phase 8: 고정 Green EC2 SSM 배포 | ASG 최종 배포에는 사용할 수 없음 |
| ECR 복구 가이드 FIX SHA `0a49108…` | 현재 image build SHA로 사용되지만 ASG bootstrap SHA와 remote main은 별도 축 |

---

## 18. 읽기 전용 조회 무결성

### 18.1 사용한 조회 계열

- `aws sts get-caller-identity`
- EC2/VPC/ELB/ASG의 `describe-*`, `get-*`
- RDS·ElastiCache·MQ의 `describe-*`, `list-*`
- IAM의 `get-role`, `list-*`, `get-role-policy`, account summary
- Secrets Manager의 `describe-secret`, `get-resource-policy`
- ECR의 `describe-*`, `get-lifecycle-policy`, scan finding severity count
- CloudFront·WAF·S3의 제한된 `get-*`, `list-*`, `head-*`
- CloudWatch·Logs·Config·CloudTrail·GuardDuty·Security Hub·Access Analyzer 조회
- GitHub `variable get`, Secret 이름 목록, remote main SHA 조회

### 18.2 실행하지 않은 작업

- AWS 리소스 생성·수정·삭제
- IAM Policy 저장·연결·해제
- Security Group Rule 변경
- EC2·ASG·RDS·Redis·RabbitMQ 시작·중지·재시작·교체
- CloudFront config 변경·invalidation
- S3 object 업로드·삭제
- GitHub Variable·Secret·workflow 변경·실행
- SSM `SendCommand`
- Secrets Manager `GetSecretValue`
- Secret·password·token·header 값 출력

### 18.3 재조회 시 안전 규칙

1. CloudFront는 전체 config를 파일로 저장하지 않고 필요한 field만 projection한다.
2. Listener rule은 condition field와 action type만 조회하고 header 값은 출력하지 않는다.
3. Launch Template은 `UserData` 존재 여부만 확인하고 원문은 출력하지 않는다.
4. ECR scan은 severity count만 기록하고 불필요한 package 상세를 문서에 복사하지 않는다.
5. SSM은 managed node 상태만 조회하고 command 실행·output 조회는 별도 승인 없이는 하지 않는다.

---

## 19. 공식 참고 문서

- [AWS-managed prefix lists](https://docs.aws.amazon.com/vpc/latest/userguide/working-with-aws-managed-prefix-lists.html)
- [Restrict access to Application Load Balancers](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/restrict-access-to-load-balancer.html)
- [CloudFront cache policies](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/controlling-the-cache-key.html)
- [Application Load Balancer Target Groups](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-target-groups.html)
- [Create an Auto Scaling group using a launch template](https://docs.aws.amazon.com/autoscaling/ec2/userguide/create-auto-scaling-groups.html)
- [Amazon EBS encryption by default](https://docs.aws.amazon.com/ebs/latest/userguide/ebs-encryption.html)
- [Service control policies](https://docs.aws.amazon.com/organizations/latest/userguide/orgs_manage_policies_scps.html)

---

## 20. 감사 결론

현재 Control Plane은 Green 사용자 트래픽을 CloudFront→ALB→ASG 한 대 경로로
보내고 Target은 `healthy`다. 최근 ASG 가이드의 smoke 기록은 PASS이며, 이번
감사에서는 애플리케이션 요청이나 컨테이너 runtime을 재검증하지 않았다. Private
S3·OAC, ASG 전용 SG, immutable AMI·Launch Template, 관리형 데이터 암호화, SSM
기반 관리는 Control Plane 조회로 확인했다.

그러나 다음 이유로 아직 “다중 인스턴스 Auto Scaling·보안 완료” 상태는 아니다.

1. ASG replacement drill과 cross-instance 상태 공유가 미완료다.
2. origin custom header·default 403·HTTPS가 미적용이다.
3. XGB 고정 image에 Critical scan finding이 있다.
4. 감시·구성 감사·지속 CloudTrail 정책이 없다.
5. 임시 Builder·검증 EC2와 수동 Green·Blue가 동시에 실행된다.
6. 배포 workflow가 수동 Green Instance ID에 고정돼 있다.

따라서 현재 승인 용량은 `1/1/1`, `Max 1`이며, 16.3의 하드 게이트를 모두 통과하기 전에는 `Max 3`과 부하 기반 scaling policy를 활성화하지 않는다.
