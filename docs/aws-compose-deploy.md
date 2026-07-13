# EC2 Docker Compose 배포

이 문서는 `main` 기준 BuildGraph를 EC2 한 대에서 Docker Compose로 배포하는 절차를 정리한다. HTTPS는 CloudFront 기본 도메인으로 처리하고, EC2는 HTTP origin 역할만 한다.

## AWS 리소스

- EC2: Ubuntu 22.04 또는 24.04 LTS, `t3.medium` 이상, 30GB gp3 이상
- VPC: `buildgraph-demo-vpc`의 public subnet
- Elastic IP: EC2에 연결 권장
- Security group inbound:
  - `80` from `0.0.0.0/0`
  - `22` from 관리자/배포용 IP 또는 GitHub Actions SSH 접근 정책
- EC2에는 `443`, `5432`, `6379`, `5672`, `15672`, `8080`, `8091`을 열지 않는다.

## EC2 준비

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git rsync
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo tee /etc/apt/keyrings/docker.asc >/dev/null
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"
sudo mkdir -p /opt/buildgraph
sudo chown -R "$USER:$USER" /opt/buildgraph
```

설치 후 SSH를 재접속해 `docker ps`가 sudo 없이 실행되는지 확인한다.

## 환경 파일

EC2의 `/opt/buildgraph/.env.prod`는 git에 커밋하지 않는다. 첫 배포 전에 아래처럼 준비한다.

```bash
cd /opt/buildgraph
cp .env.prod.example .env.prod
vi .env.prod
```

반드시 바꿀 값:

- `POSTGRES_PASSWORD`
- `RABBITMQ_DEFAULT_PASS`
- `BUILDGRAPH_AUTH_JWT_SECRET`: 32바이트 이상 랜덤 문자열
- `BUILDGRAPH_CORS_ALLOWED_ORIGINS`: CloudFront 생성 전에는 임시로 `http://<EC2_PUBLIC_DNS>`를 넣고, CloudFront 생성 후 `https://<distribution>.cloudfront.net`으로 교체
- `OPENAI_API_KEY`, `NAVER_SEARCH_CLIENT_ID`, `NAVER_SEARCH_CLIENT_SECRET`: 실제 외부 연동이 필요할 때만 입력

## 수동 배포

```bash
cd /opt/buildgraph
docker compose -f compose.prod.yaml --env-file .env.prod config --quiet
docker compose -f compose.prod.yaml --env-file .env.prod up -d --build
docker compose -f compose.prod.yaml --env-file .env.prod ps
curl -fsS http://localhost/api/health
```

외부에서는 `http://<EC2_PUBLIC_DNS>/api/health`가 200인지 확인한다.

## CloudFront

CloudFront distribution은 EC2를 단일 origin으로 둔다.

- Origin domain: EC2 public DNS 또는 Elastic IP가 연결된 public DNS
- Origin protocol policy: HTTP only
- Viewer protocol policy: Redirect HTTP to HTTPS
- Default behavior cache policy: CachingDisabled
- Allowed HTTP methods: GET, HEAD, OPTIONS, PUT, POST, PATCH, DELETE
- Origin request policy: Authorization, Origin, query string, cookies가 API와 WebSocket에 전달되도록 설정

데모에서는 default behavior도 cache disabled로 둔다. 이렇게 하면 GitHub Actions 배포 후 CloudFront invalidation 없이 최신 정적 파일과 API 응답을 바로 확인할 수 있다.

CloudFront 생성 후 EC2의 `/opt/buildgraph/.env.prod`에서 아래 값을 CloudFront URL로 바꾼 뒤 compose를 재기동한다.

```bash
BUILDGRAPH_CORS_ALLOWED_ORIGINS=https://<distribution>.cloudfront.net
```

## GitHub Actions CD

Repository secrets:

- `EC2_HOST`: EC2 public DNS 또는 Elastic IP
- `EC2_USER`: 보통 Ubuntu AMI는 `ubuntu`
- `EC2_SSH_KEY`: EC2 접속 private key 전체
- `EC2_APP_DIR`: `/opt/buildgraph`

`main`에 push하면 `CI` workflow가 먼저 실행된다. 모든 CI job이 성공한 경우에만
`.github/workflows/deploy-compose.yml`이 같은 commit SHA를 대상으로 다음 순서로 실행된다.

1. CI를 통과한 SHA가 현재 `main` HEAD인지 확인
2. web build
3. API bootJar
4. `compose.prod.yaml` config 검증
5. EC2로 rsync
6. EC2에서 `docker compose -f compose.prod.yaml --env-file .env.prod up -d --build --remove-orphans`
7. EC2 내부 `http://localhost/api/health` 확인
8. health 확인 성공 후 사용하지 않는 Docker image prune

CI가 진행되는 동안 더 최신 commit이 `main`에 들어오면 이전 SHA 배포는 자동으로 건너뛴다.
수동 재배포는 `Deploy EC2 Compose`의 `Run workflow`를 사용하며, 현재 `main`에 성공한 CI가
없으면 배포가 거부된다. 이 게이트 적용 전에 생성된 과거 Deploy 실행은 구버전 workflow를
사용하므로 재실행하지 않는다.

## 검증

```bash
docker compose -f compose.prod.yaml --env-file .env.prod ps
docker compose -f compose.prod.yaml --env-file .env.prod logs --tail=200 api
curl -fsS http://localhost/api/health
```

브라우저에서는 CloudFront 기본 도메인으로 접속한다.

- `https://<distribution>.cloudfront.net`
- `https://<distribution>.cloudfront.net/api/health`
