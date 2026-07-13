# AWS 인프라 분리 Phase 0 콘솔 따라하기

이 문서는 데이터 백업 없이 Phase 0의 운영 설정만 확인하는 절차다. 반드시 1번부터 순서대로 실행한다.

## 0. 이번 단계에서 하지 않는 작업

1. EBS 스냅샷을 생성하지 않는다.
2. PostgreSQL `pg_dump`를 실행하지 않는다.
3. Redis key를 백업하거나 이전하지 않는다.
4. RabbitMQ 메시지를 백업하거나 이전하지 않는다.
5. `recommendation-models` Docker Volume을 백업하지 않는다.
6. `agent-log-data` Docker Volume을 백업하지 않는다.
7. EC2, EBS, Docker Volume을 아직 삭제하지 않는다.
8. `.env.prod`의 실제 값을 문서나 채팅에 붙여넣지 않는다.

데이터를 폐기할 수 있어도 기존 서비스 삭제는 최종 전환 검증 후에 진행한다. Phase 0에서는 조회와 기록만 수행한다.

## 1. 시작 전에 확인할 값

| 항목 | 확인할 값 |
| --- | --- |
| AWS Region | `ap-northeast-2` 서울 |
| EC2 이름 | `buildgraph-demo-ec2` |
| EC2 ID | `i-082c21a20e14f3295` |
| EC2 상태 | `실행 중` |
| EC2 Public IP | `15.164.235.183` |
| CloudFront Distribution | `EI6MMNZLTTN3H` |
| 현재 배포 SHA | `144a204` |

위 값과 AWS 콘솔에 표시되는 값이 다르면 작업을 멈추고 Codex에게 알린다.

## 2. AWS 콘솔 로그인과 Region 확인

1. 브라우저에서 [AWS Management Console](https://console.aws.amazon.com/)에 로그인한다.
2. 화면 오른쪽 위의 Region 선택 메뉴를 누른다.
3. `아시아 태평양(서울) ap-northeast-2`가 선택되어 있는지 확인한다.
4. 서울이 아니라면 `아시아 태평양(서울)`을 선택한다.
5. 상단 검색창에 `EC2`를 입력한다.
6. 검색 결과에서 `EC2` 서비스를 누른다.
7. EC2 대시보드 오른쪽 위에도 `서울`이 표시되는지 다시 확인한다.

성공 기준:

```text
현재 서비스: EC2
현재 Region: ap-northeast-2 서울
```

## 3. 운영 EC2 인스턴스 확인

1. EC2 왼쪽 메뉴에서 `인스턴스` → `인스턴스`를 누른다.
2. 목록에서 이름이 `buildgraph-demo-ec2`인 행을 찾는다.
3. 해당 행의 체크박스를 누른다.
4. 인스턴스 ID가 `i-082c21a20e14f3295`인지 확인한다.
5. 인스턴스 상태가 `실행 중`인지 확인한다.
6. 인스턴스 유형이 `t3.medium`인지 확인한다.
7. 가용 영역이 `ap-northeast-2b`인지 확인한다.
8. Public IPv4가 `15.164.235.183`인지 확인한다.
9. 위 값 중 하나라도 다르면 `연결`을 누르지 않고 작업을 중단한다.

성공 기준:

```text
Name: buildgraph-demo-ec2
Instance ID: i-082c21a20e14f3295
State: running
Public IPv4: 15.164.235.183
```

## 4. EC2 Instance Connect로 브라우저 터미널 열기

1. `buildgraph-demo-ec2` 행이 선택된 상태에서 화면 위의 `연결` 버튼을 누른다.
2. `인스턴스에 연결` 화면이 열리는지 확인한다.
3. `EC2 Instance Connect` 탭을 누른다.
4. 연결 유형에서 `퍼블릭 IP를 사용하여 연결`을 선택한다.
5. Public IP로 `15.164.235.183`이 표시되는지 확인한다.
6. 사용자 이름은 AWS 콘솔이 자동으로 제안한 값을 그대로 사용한다.
7. `연결` 버튼을 누른다.
8. 새 브라우저 탭에 터미널이 열릴 때까지 기다린다.
9. 다음 명령을 실행한다.

```bash
whoami
hostname
docker version --format '{{.Server.Version}}'
```

10. `docker version`에서 권한 오류가 발생하면 다음 명령을 실행한다.

```bash
sudo -i
docker version --format '{{.Server.Version}}'
```

11. Docker Server 버전이 출력되는지 확인한다.

### EC2 Instance Connect가 실패하는 경우

1. 연결 화면으로 돌아간다.
2. `SSH 클라이언트` 탭을 누른다.
3. AWS 콘솔이 표시한 예제 SSH 명령을 확인한다.
4. `buildgraph-demo-key`의 개인 키를 가지고 있을 때만 예제 명령을 사용한다.
5. 개인 키가 없다면 설정을 임의로 변경하지 않고 작업을 중단한다.
6. 오류 문구만 Codex에게 전달하고 개인 키 내용은 전달하지 않는다.

## 5. 운영 Compose 디렉터리 찾기

1. 아래 명령을 전체 복사해 브라우저 터미널에서 실행한다.

```bash
CONTAINER_ID="$(docker ps --filter label=com.docker.compose.service=api -q | head -n 1)"
test -n "$CONTAINER_ID"
APP_DIR="$(docker inspect "$CONTAINER_ID" --format '{{ index .Config.Labels "com.docker.compose.project.working_dir" }}')"
printf 'APP_DIR=%s\n' "$APP_DIR"
test -f "$APP_DIR/compose.prod.yaml"
test -f "$APP_DIR/.env.prod"
cd "$APP_DIR"
```

2. `APP_DIR=` 뒤에 경로가 출력되는지 확인한다.
3. 오류가 출력되지 않았는지 확인한다.
4. 다음 명령으로 현재 위치를 확인한다.

```bash
pwd
```

5. 출력된 경로를 기록한다.

```text
APP_DIR: /opt/buildgraph/prototype
```

중단 조건:

1. `CONTAINER_ID`가 비어 있다.
2. `APP_DIR`이 비어 있다.
3. `compose.prod.yaml`이 없다는 오류가 나온다.
4. `.env.prod`가 없다는 오류가 나온다.

중단 조건에 해당하면 다음 단계로 넘어가지 않는다.

## 6. 운영 Compose 서비스와 Volume 이름 확인

1. 이후 단계는 새 로그인 셸에서도 동작하도록 먼저 확인된 운영 경로로 이동한다.

```bash
cd /opt/buildgraph/prototype
test -f compose.prod.yaml
test -f .env.prod
```

2. 다음 명령으로 Compose 설정이 정상인지 먼저 검증한다.

```bash
docker compose -f compose.prod.yaml --env-file .env.prod config --quiet
```

3. 오류가 없다면 다음 명령으로 현재 서비스 상태를 확인한다.

```bash
docker compose -f compose.prod.yaml --env-file .env.prod ps
```

4. 실행 중이거나 재시작 중인 서비스 이름을 확인한다.
5. 다음 명령으로 Compose에 선언된 서비스 이름만 출력한다.

```bash
docker compose -f compose.prod.yaml --env-file .env.prod config --services
```

6. 다음 서비스가 표시되는지 확인한다.

```text
postgres
redis
rabbitmq
mailpit
xgb-reranker
api
web
```

7. 다음 명령으로 Volume 이름을 출력한다.

```bash
docker compose -f compose.prod.yaml --env-file .env.prod config --volumes
```

8. 다음 명령으로 렌더링된 Compose의 SHA-256만 계산한다.

```bash
set -o pipefail
docker compose -f compose.prod.yaml --env-file .env.prod config | sha256sum
```

9. SHA-256만 기록하고 렌더링된 Compose 원문은 복사하지 않는다. 원문에는 비밀값이 포함될 수 있다.
10. `e3b0c44298fc...`는 빈 입력의 SHA-256이므로 이 값이 나오면 정상 결과로 기록하지 않는다.

```text
Compose 서비스:
Compose Volume:
Rendered Compose SHA-256:
```

## 7. `.env.prod` 존재와 권한 확인

1. 다음 명령을 실행한다.

```bash
test -f .env.prod
stat -c '%a %U:%G %n' .env.prod
```

2. 오류 없이 `.env.prod` 경로가 출력되는지 확인한다.
3. 파일 내용을 출력하지 않는다.
4. 권한이 `600` 또는 그보다 제한적인지 확인한다.
5. 권한이 `644`처럼 다른 사용자가 읽을 수 있는 상태라면 값을 출력하지 말고 권한 정보만 Codex에게 전달한다.

성공 기준:

```text
.env.prod 파일이 존재함
.env.prod 내용을 출력하지 않음
```

## 8. 환경 변수 이름만 확인

1. 다음 명령으로 `.env.prod`의 변수 이름만 출력한다.

```bash
sed -n 's/^[[:space:]]*\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' .env.prod | sort
```

2. 출력에 비밀값이 없고 변수 이름만 있는지 확인한다.
3. 변수 이름 목록은 전달해도 된다.
4. 다음 명령으로 현재 API 컨테이너의 환경 변수 이름만 출력한다.

```bash
cd /opt/buildgraph/prototype
docker compose -f compose.prod.yaml --env-file .env.prod exec -T api env | cut -d= -f1 | sort
```

5. 이 결과도 변수 이름만 있는지 확인한다.

## 9. 중요 비밀 환경 변수의 설정 여부만 확인

1. 다음 명령을 그대로 실행한다.

```bash
for name in \
  GOOGLE_OAUTH_CLIENT_ID \
  GOOGLE_OAUTH_CLIENT_SECRET \
  OPENAI_API_KEY \
  NAVER_SEARCH_CLIENT_ID \
  NAVER_SEARCH_CLIENT_SECRET \
  AGENT_DEMO_ACTIVATION_TOKEN \
  BUILDGRAPH_AUTH_JWT_SECRET
do
  if grep -q "^${name}=." .env.prod; then
    printf '%s=SET\n' "$name"
  else
    printf '%s=EMPTY_OR_MISSING\n' "$name"
  fi
done
```

2. 출력이 `변수명=SET` 또는 `변수명=EMPTY_OR_MISSING` 형태인지 확인한다.
3. 실제 값이 출력되지 않았는지 확인한다.
4. 출력 결과를 기록한다.

```text
GOOGLE_OAUTH_CLIENT_ID: SET | EMPTY_OR_MISSING
GOOGLE_OAUTH_CLIENT_SECRET: SET | EMPTY_OR_MISSING
OPENAI_API_KEY: SET | EMPTY_OR_MISSING
NAVER_SEARCH_CLIENT_ID: SET | EMPTY_OR_MISSING
NAVER_SEARCH_CLIENT_SECRET: SET | EMPTY_OR_MISSING
AGENT_DEMO_ACTIVATION_TOKEN: SET | EMPTY_OR_MISSING
BUILDGRAPH_AUTH_JWT_SECRET: SET | EMPTY_OR_MISSING
```

5. `SET`으로 표시된 값은 이후 Secrets Manager 이전 대상이다.
6. `EMPTY_OR_MISSING`이 의도한 상태인지 Phase 2 전에 확인한다.
7. 비밀값 자체는 Codex에게 전달하지 않는다.

## 10. Phase 0 결과 전달

1. 다음 형식으로 결과를 작성한다.

```text
APP_DIR:
Compose 서비스:
Compose Volume:
Rendered Compose SHA-256:
.env.prod 권한:
GOOGLE_OAUTH_CLIENT_ID: SET | EMPTY_OR_MISSING
GOOGLE_OAUTH_CLIENT_SECRET: SET | EMPTY_OR_MISSING
OPENAI_API_KEY: SET | EMPTY_OR_MISSING
NAVER_SEARCH_CLIENT_ID: SET | EMPTY_OR_MISSING
NAVER_SEARCH_CLIENT_SECRET: SET | EMPTY_OR_MISSING
AGENT_DEMO_ACTIVATION_TOKEN: SET | EMPTY_OR_MISSING
BUILDGRAPH_AUTH_JWT_SECRET: SET | EMPTY_OR_MISSING
```

2. 다음 정보는 전달하지 않는다.

   1. `.env.prod` 원문
   2. API Key
   3. OAuth Secret
   4. JWT Secret
   5. PostgreSQL 비밀번호
   6. RabbitMQ 비밀번호
   7. SSH 개인 키

3. 필요한 결과를 기록했으면 다음 명령을 실행한다.

```bash
exit
```

4. 브라우저 터미널 탭을 닫는다.

## 11. 전환 시간과 담당자 기록

1. 다음 항목을 정한다.

```text
예정 전환 일시(KST):
작업 책임자:
검증 담당자:
롤백 결정자:
공지 채널:
```

2. 값이 정해지지 않은 항목이 있으면 Phase 1 구현은 가능하지만 실제 운영 전환은 시작하지 않는다.

## 12. Phase 0 완료 체크

1. [ ] 운영 EC2와 Region을 확인했다.
2. [ ] 운영 Compose 서비스 목록을 확인했다.
3. [ ] Compose Volume 목록을 확인했다.
4. [ ] 렌더링된 Compose SHA-256을 기록했다.
5. [ ] `.env.prod` 파일 존재와 권한을 확인했다.
6. [ ] 비밀 환경 변수 이름과 설정 여부만 확인했다.
7. [ ] 비밀값을 화면 출력, 채팅, Git에 노출하지 않았다.
8. [ ] 현재 운영 SHA `144a204`를 기록했다.
9. [ ] 전환 시간과 담당자를 기록했다.
10. [ ] EC2, EBS, Docker Volume을 삭제하지 않았다.

10개 항목이 모두 완료되면 Phase 0을 종료하고 Phase 1 테스트 작성으로 넘어간다.

## 13. 자주 발생하는 문제

### EC2 Instance Connect가 열리지 않음

1. 인스턴스 상태가 `실행 중`인지 확인한다.
2. Public IP가 `15.164.235.183`인지 확인한다.
3. `SSH 클라이언트` 탭의 안내를 확인한다.
4. 개인 키가 없다면 설정을 임의로 변경하지 않고 오류 문구만 전달한다.

### `docker: permission denied`

1. 다음 명령을 실행한다.

```bash
sudo -i
```

2. 5번 단계부터 다시 진행한다.

### `.env.prod`가 없음

1. 새 `.env.prod`를 임의로 만들지 않는다.
2. 다른 디렉터리를 추측하지 않는다.
3. `APP_DIR`과 오류 문구만 전달한다.

### Compose 서비스가 예상 목록과 다름

1. `docker compose up`, `down`, `restart`를 실행하지 않는다.
2. 실제 출력된 서비스 이름만 전달한다.
3. Phase 0 문서의 기준선을 실제 운영 상태에 맞게 수정한 후 진행한다.
