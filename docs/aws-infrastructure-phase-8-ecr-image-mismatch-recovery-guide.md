# AWS 인프라 분리 Phase 8 ECR 이미지 불일치 복구 가이드

이 문서는 GitHub Actions와 SSM 배포가 `Success`로 끝났지만 Green EC2의 실제 컨테이너가 요청한 Git SHA 이미지가 아닌 이전 이미지를 계속 실행하는 문제를 복구하고 재발을 방지하는 절차다.

이 작업은 Green 배포 파이프라인만 대상으로 한다. 다음 Blue 리소스는 변경하지 않는다.

- Blue EC2 `i-082c21a20e14f3295`
- Blue Public IP `15.164.235.183`
- Blue CloudFront `EI6MMNZLTTN3H`
- Blue Compose와 기존 SSH 배포 workflow

---

## 0. 확인된 장애 현상과 원인

### 0.1 확인된 불일치

API 배포 요청 SHA는 다음과 같았다.

```text
3b351c25eb1b2d8c9c4d4de1a4318a3610f24a63
```

GitHub Actions는 위 SHA로 API image를 빌드하여 ECR에 push했고, SSM에도 같은 SHA와 image URI를 전달했다. 그러나 SSM 출력에는 이전 image가 pull된 것으로 나타났다.

```text
요청·ECR push: 3b351c25eb1b2d8c9c4d4de1a4318a3610f24a63
실제 Docker pull: 93a7f672f84added76246f55c8fc368a36c12e00
```

Green EC2에서도 다음 불일치가 확인됐다.

```text
실제 실행 API image: 93a7f672f84added76246f55c8fc368a36c12e00
/opt/buildgraph/green-images.env: 3b351c25eb1b2d8c9c4d4de1a4318a3610f24a63
```

따라서 이 문제는 GitHub Actions가 실행되지 않은 문제가 아니다. `Deploy Green API` workflow는 `push` event로 실행됐고 ECR push와 SSM 호출까지 성공했다.

### 0.2 근본 원인

`tools/deploy_green_ecr.sh`가 기존 image manifest를 다음 방식으로 읽고 있었다.

```bash
set -a
source "$IMAGE_MANIFEST"
set +a
```

`set -a`는 `API_IMAGE_URI`와 `XGB_IMAGE_URI`를 자식 프로세스에서도 보이는 환경변수로 export한다. 이후 새 SHA가 들어 있는 candidate manifest를 `--env-file`로 Docker Compose에 전달해도 셸 환경변수가 `--env-file`보다 우선되어 기존 image URI가 사용된다.

문제 흐름은 다음과 같다.

```text
기존 manifest의 API_IMAGE_URI=93a7...
→ source + set -a로 93a7... export
→ candidate manifest에 API_IMAGE_URI=3b351... 작성
→ docker compose --env-file candidate 실행
→ export된 93a7...가 candidate의 3b351...보다 우선
→ 이전 image로 컨테이너 재생성
→ 이전 image도 정상이라 health PASS
→ candidate manifest만 활성 manifest로 이동
→ 실제 컨테이너와 manifest가 서로 달라짐
```

API와 XGB가 같은 배포 스크립트를 사용하므로 XGB에도 동일한 문제가 발생할 수 있다.

---

## 1. 작업 원칙

1. 코드 수정 전에 자동 Green CD를 잠시 중지한다.
2. 프로젝트 규칙에 따라 실패를 재현하는 회귀 테스트를 먼저 작성한다.
3. 기존 image manifest를 셸 환경변수로 export하지 않는다.
4. Compose 실행 시 상위 셸의 image 환경변수를 제거한다.
5. Health만 확인하지 않고 실제 실행 image URI가 요청 URI와 같은지 확인한다.
6. Green API와 XGB를 한 번에 변경하지 않고 순서대로 독립 배포한다.
7. 모든 검증을 통과한 뒤에만 자동 Green CD를 다시 활성화한다.

---

## 2. 작업 전 현재 상태 기록

Green EC2에 SSM 또는 SSH로 접속한 뒤 Ubuntu shell로 전환한다.

```bash
sudo -iu ubuntu
```

현재 Git SHA, manifest, 컨테이너 image와 Container ID를 기록한다.

```bash
cd /opt/buildgraph/prototype

git rev-parse HEAD

stat -c '%a %U:%G %n' \
  /opt/buildgraph/green-images.env \
  .env.prod

grep -E '^(API_IMAGE_URI|XGB_IMAGE_URI)=' \
  /opt/buildgraph/green-images.env

docker inspect buildgraph-green-api-1 \
  --format 'api_id={{.Id}} api_image={{.Config.Image}}'

docker inspect buildgraph-green-xgb-reranker-1 \
  --format 'xgb_id={{.Id}} xgb_image={{.Config.Image}} health={{.State.Health.Status}}'

curl -fsS http://127.0.0.1/healthz
echo

curl -fsS http://127.0.0.1/api/health | jq .
```

비밀번호, AUTH token, `.env.prod` 원문은 출력하거나 문서에 기록하지 않는다.

7d7caeb3bb2a78f1fa1f38346a22fa6b04209a0f
600 ubuntu:ubuntu /opt/buildgraph/green-images.env
600 ubuntu:ubuntu .env.prod
API_IMAGE_URI=443915990705.dkr.ecr.ap-northeast-2.amazonaws.com/buildgraph-demo-api-green:7d7caeb3bb2a78f1fa1f38346a22fa6b04209a0f
XGB_IMAGE_URI=443915990705.dkr.ecr.ap-northeast-2.amazonaws.com/buildgraph-demo-xgb-reranker-green:93a7f672f84added76246f55c8fc368a36c12e00
api_id=d8a646ce3374fb355102a1b17d45f01310b2a2d08f04599f1e7b2c40767185bf api_image=443915990705.dkr.ecr.ap-northeast-2.amazonaws.com/buildgraph-demo-api-green:908ee01917a03da1b326628fe50154ff2d1aa9d1
xgb_id=a249becca03bb77cfd49a9e7a83b835ca8fee7f3926f4648f81a0ca78698fce4 xgb_image=443915990705.dkr.ecr.ap-northeast-2.amazonaws.com/buildgraph-demo-xgb-reranker-green:93a7f672f84added76246f55c8fc368a36c12e00 health=healthy
ok

{
  "status": "UP",
  "database": "UP"
}
ubuntu@ip-10-0-23-7:/opt/buildgraph/prototype$


---

## 3. Green 자동 CD 일시 중지

수정 도중 새로운 `main` push가 기존 버그가 있는 배포 스크립트를 실행하지 않게 한다.

### 3.1 GitHub Console에서 변경

1. GitHub repository `jungle-final-project/prototype`을 연다.
2. `Settings`를 누른다.
3. `Secrets and variables` → `Actions`를 연다.
4. `Variables` 탭을 연다.
5. `GREEN_CD_ENABLED`를 찾는다.
6. 값을 다음으로 변경한다.

```text
false
```

7. 저장 후 목록에서 값이 `false`인지 확인한다.

`GREEN_CD_ENABLED=false`여도 `Run workflow`로 시작한 `workflow_dispatch` 수동 실행은 가능하다.

### 3.2 GitHub CLI를 사용할 경우

로컬 Mac에서 실행한다.

```bash
gh variable set GREEN_CD_ENABLED \
  --body false \
  --repo jungle-final-project/prototype

gh variable get GREEN_CD_ENABLED \
  --repo jungle-final-project/prototype
```

출력이 `false`인지 확인한다.

---

## 4. 회귀 테스트를 먼저 작성

수정 대상 테스트 파일은 다음과 같다.

```text
tools/test_validate_green_deployment.py
```

### 4.1 테스트 시나리오

테스트는 최소한 다음 상황을 재현해야 한다.

| 항목 | 테스트 값 |
| --- | --- |
| 기존 API SHA | `aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa` |
| 신규 API SHA | `bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb` |
| 기존 XGB SHA | `cccccccccccccccccccccccccccccccccccccccc` |
| 상위 셸 `API_IMAGE_URI` | 기존 API SHA URI |
| candidate manifest API URI | 신규 API SHA URI |
| candidate manifest XGB URI | 기존 XGB SHA URI |

테스트 통과 조건은 다음과 같다.

1. 기존 셸에 `API_IMAGE_URI=...:aaaaaaaa...`가 있어도 candidate의 API image는 `...:bbbbbbbb...`로 해석된다.
2. API만 배포할 때 XGB image는 `...:cccccccc...`로 유지된다.
3. 실제 실행 API image가 요청 image와 다르면 배포가 성공으로 끝나지 않는다.
4. image 불일치가 발생하면 활성 manifest를 신규 값으로 확정하지 않는다.
5. XGB 배포에도 같은 규칙이 적용된다.

처음에는 기존 구현을 대상으로 테스트를 실행해 실패하는지 확인한다. 테스트가 기존 코드에서도 통과한다면 장애를 재현하지 못한 테스트이므로 수정해야 한다.

---

## 5. 배포 스크립트 수정

수정 대상은 다음 파일이다.

```text
tools/deploy_green_ecr.sh
```

### 5.1 기존 manifest를 export하지 않기

다음 코드를 제거한다.

```bash
set -a
# shellcheck disable=SC1090
source "$IMAGE_MANIFEST"
set +a
```

manifest에서 필요한 값만 지역 변수로 읽는다. 동일한 key가 중복되거나 값이 비어 있으면 배포를 중단하도록 검증한다.

예시:

```bash
read_manifest_value() {
  local key="$1"
  local file="$2"
  local count value

  count="$(grep -c "^${key}=" "$file" || true)"
  [[ "$count" -eq 1 ]] || die "manifest must contain exactly one ${key}"

  value="$(sed -n "s/^${key}=//p" "$file")"
  [[ -n "$value" ]] || die "manifest value is empty: ${key}"

  printf '%s' "$value"
}

active_api_image="$(read_manifest_value API_IMAGE_URI "$IMAGE_MANIFEST")"
active_xgb_image="$(read_manifest_value XGB_IMAGE_URI "$IMAGE_MANIFEST")"
```

기존 image 검증과 candidate 생성도 지역 변수를 사용한다.

```bash
validate_image_uri "$active_api_image" "$API_REPOSITORY" ||
  die "active API image URI is invalid"

validate_image_uri "$active_xgb_image" "$XGB_REPOSITORY" ||
  die "active XGB image URI is invalid"

candidate_api_image="$active_api_image"
candidate_xgb_image="$active_xgb_image"

if [[ "$TARGET_SERVICE" == "api" ]]; then
  candidate_api_image="$TARGET_IMAGE_URI"
else
  candidate_xgb_image="$TARGET_IMAGE_URI"
fi
```

### 5.2 Compose 실행에서 상위 image 환경변수 제거

`compose_with()`를 다음 원칙으로 수정한다.

```bash
compose_with() {
  local runtime_env="$1"
  local image_env="$2"
  shift 2

  env -u API_IMAGE_URI -u XGB_IMAGE_URI \
    docker compose \
      -p "$COMPOSE_PROJECT_NAME" \
      -f "$APP_ROOT/compose.api.ecr.prod.yaml" \
      --env-file "$runtime_env" \
      --env-file "$image_env" \
      "$@"
}
```

이 방어 코드는 SSM, SSH 또는 다른 상위 프로세스가 `API_IMAGE_URI`나 `XGB_IMAGE_URI`를 이미 export한 경우에도 candidate manifest가 우선 적용되게 한다.

### 5.3 실제 실행 image 검증 추가

`docker compose up` 직후, Nginx reload와 health 검사 전에 실제 컨테이너 image를 확인한다.

```bash
verify_running_image() {
  local runtime_env="$1"
  local image_env="$2"
  local service="$3"
  local expected_image="$4"
  local container_id running_image

  container_id="$(compose_with "$runtime_env" "$image_env" ps -q "$service")"
  [[ -n "$container_id" ]] || die "container is missing after deployment: $service"

  running_image="$(docker inspect --format '{{.Config.Image}}' "$container_id")"
  [[ "$running_image" == "$expected_image" ]] ||
    die "running image mismatch: expected=$expected_image actual=$running_image"
}
```

호출 위치:

```bash
compose_with \
  "$CANDIDATE_RUNTIME_ENV" \
  "$CANDIDATE_MANIFEST" \
  up -d --no-deps --force-recreate --no-build "$TARGET_SERVICE"

verify_running_image \
  "$CANDIDATE_RUNTIME_ENV" \
  "$CANDIDATE_MANIFEST" \
  "$TARGET_SERVICE" \
  "$TARGET_IMAGE_URI"
```

검증은 candidate manifest를 `/opt/buildgraph/green-images.env`로 이동하기 전에 수행해야 한다. 불일치하면 기존 `ERR` trap이 rollback을 시작해야 한다.

---

## 6. 로컬 검증

로컬 저장소에서 실행한다.

```bash
cd /Users/juhoseok/Desktop/prototype
```

### 6.1 Shell 문법 검사

```bash
bash -n tools/deploy_green_ecr.sh
```

출력이 없고 exit code가 `0`이면 통과다.

### 6.2 배포 validator와 인프라 validator

```bash
python -m pip install -r tools/requirements.txt

python -m unittest \
  tools.test_validate_green_deployment \
  tools.test_validate_infrastructure_separation

python tools/validate_infrastructure_separation.py
```

통과 기준:

```text
OK
Infrastructure separation validation passed
```

### 6.3 ECR Compose 검증

image example 파일명은 `green-images.env.example`이 아니라 `.env.images.example`이다.

```bash
docker compose \
  -f compose.api.ecr.prod.yaml \
  --env-file .env.prod.example \
  --env-file .env.images.example \
  config --quiet

docker compose \
  -f compose.api.ecr.prod.yaml \
  --env-file .env.prod.example \
  --env-file .env.images.example \
  config --services |
sort
```

service 목록은 다음 세 개뿐이어야 한다.

```text
api
nginx
xgb-reranker
```

### 6.4 변경 범위 확인

```bash
git status --short
git diff -- tools/deploy_green_ecr.sh tools/test_validate_green_deployment.py
```

사용자 또는 다른 개발자가 만든 관련 없는 변경을 포함하거나 되돌리지 않는다.

---

## 7. 수정 커밋과 FIX_SHA 확정

테스트가 모두 통과한 변경만 프로젝트의 Git 협업 절차에 따라 commit하고 `main`에 반영한다. `main` 반영 후 로컬에서 실행한다.

```bash
cd /Users/juhoseok/Desktop/prototype

git fetch origin

export FIX_SHA="$(git rev-parse origin/main)"

test "${#FIX_SHA}" -eq 40

git merge-base --is-ancestor "$FIX_SHA" origin/main

echo "$FIX_SHA"
```

출력된 40자리 SHA만 Phase 8 기록표에 적는다. `<FIX_SHA>` 문자열 자체를 명령에 입력하지 않는다.

---

## 8. Green EC2에 수정 스크립트 bootstrap

기존 Green EC2의 working tree에는 버그가 있는 스크립트가 남아 있다. 새 workflow를 처음 실행하기 전에 Green EC2가 수정 commit을 가리키게 해야 한다.

이 checkout만으로 실행 중인 Docker 컨테이너는 재시작되지 않는다.

Green EC2 Ubuntu shell에서 실행한다.

```bash
sudo -iu ubuntu

cd /opt/buildgraph/prototype

export FIX_SHA="실제_40자리_FIX_SHA"

git fetch origin

git cat-file -e "$FIX_SHA^{commit}"

git merge-base --is-ancestor "$FIX_SHA" origin/main

git checkout --detach "$FIX_SHA"

git rev-parse HEAD
```

마지막 출력이 `FIX_SHA`와 정확히 같아야 한다.

다음 파일이 수정 버전인지 확인한다.

```bash
rg -n 'env -u API_IMAGE_URI|verify_running_image|read_manifest_value' \
  tools/deploy_green_ecr.sh
```

EC2에 `rg`가 없으면 다음을 사용한다.

```bash
grep -nE 'env -u API_IMAGE_URI|verify_running_image|read_manifest_value' \
  tools/deploy_green_ecr.sh
```

---

## 9. API workflow 수동 복구 배포

### 9.1 배포 전 XGB Container ID 기록

Green EC2에서 실행한다.

```bash
docker inspect buildgraph-green-xgb-reranker-1 \
  --format '{{.Id}}' |
tee /tmp/xgb-before-api-deploy.txt
```

### 9.2 GitHub Actions 실행

1. GitHub repository의 `Actions`를 연다.
2. `Deploy Green API`를 연다.
3. `Run workflow`를 누른다.
4. 다음 값을 입력한다.

| 입력 | 값 |
| --- | --- |
| Branch | `main` |
| `git_sha` | 실제 `FIX_SHA` 40자리 |
| `publish_only` | `false` |

5. 실행한다.
6. test, OIDC, ECR push, SSM deploy가 모두 성공하는지 확인한다.

SSM 출력에서 다음 세 가지 SHA가 모두 `FIX_SHA`여야 한다.

```text
Image .../buildgraph-demo-api-green:<FIX_SHA> Pulling
Image .../buildgraph-demo-api-green:<FIX_SHA> Pulled
green deployment succeeded: service=api sha=<FIX_SHA>
```

이전 `93a7f672...` image가 pull되면 성공으로 판정하지 않는다.

### 9.3 실제 API image와 manifest 비교

Green EC2에서 실행한다.

```bash
RUNNING_API="$(
  docker inspect buildgraph-green-api-1 \
    --format '{{.Config.Image}}'
)"

EXPECTED_API="$(
  sed -n 's/^API_IMAGE_URI=//p' \
    /opt/buildgraph/green-images.env
)"

echo "running=$RUNNING_API"
echo "expected=$EXPECTED_API"

test "$RUNNING_API" = "$EXPECTED_API" &&
  echo 'PASS: API image 일치' ||
  echo 'FAIL: API image 불일치'
```

두 URI가 같고 tag가 `FIX_SHA`여야 한다.

### 9.4 API 배포가 XGB를 재시작하지 않았는지 확인

```bash
XGB_BEFORE="$(cat /tmp/xgb-before-api-deploy.txt)"

XGB_AFTER="$(
  docker inspect buildgraph-green-xgb-reranker-1 \
    --format '{{.Id}}'
)"

test "$XGB_BEFORE" = "$XGB_AFTER" &&
  echo 'PASS: API 배포가 XGB를 재시작하지 않음' ||
  echo 'FAIL: XGB Container ID 변경됨'
```

### 9.5 API health 확인

```bash
curl -fsS http://127.0.0.1/healthz
echo

curl -fsS http://127.0.0.1/api/health | jq .

curl -fsS \
  https://d2qhd7deuwmlln.cloudfront.net/api/health |
jq .
```

통과 예시:

```json
{
  "database": "UP",
  "status": "UP"
}
```

---

## 10. XGB workflow 수동 복구 배포

### 10.1 배포 전 API Container ID 기록

```bash
docker inspect buildgraph-green-api-1 \
  --format '{{.Id}}' |
tee /tmp/api-before-xgb-deploy.txt
```

### 10.2 GitHub Actions 실행

1. `Actions`에서 `Deploy Green XGB`를 연다.
2. `Run workflow`를 누른다.
3. 다음 값을 입력한다.

| 입력 | 값 |
| --- | --- |
| Branch | `main` |
| `git_sha` | API와 같은 `FIX_SHA` |
| `publish_only` | `false` |

4. 실행하고 ECR push와 SSM deploy가 성공하는지 확인한다.

### 10.3 실제 XGB image와 manifest 비교

```bash
RUNNING_XGB="$(
  docker inspect buildgraph-green-xgb-reranker-1 \
    --format '{{.Config.Image}}'
)"

EXPECTED_XGB="$(
  sed -n 's/^XGB_IMAGE_URI=//p' \
    /opt/buildgraph/green-images.env
)"

echo "running=$RUNNING_XGB"
echo "expected=$EXPECTED_XGB"

test "$RUNNING_XGB" = "$EXPECTED_XGB" &&
  echo 'PASS: XGB image 일치' ||
  echo 'FAIL: XGB image 불일치'
```

XGB health를 확인한다.

```bash
docker inspect buildgraph-green-xgb-reranker-1 \
  --format 'image={{.Config.Image}} health={{.State.Health.Status}}'
```

`health=healthy`여야 한다.

### 10.4 XGB 배포가 API를 재시작하지 않았는지 확인

```bash
API_BEFORE="$(cat /tmp/api-before-xgb-deploy.txt)"

API_AFTER="$(
  docker inspect buildgraph-green-api-1 \
    --format '{{.Id}}'
)"

test "$API_BEFORE" = "$API_AFTER" &&
  echo 'PASS: XGB 배포가 API를 재시작하지 않음' ||
  echo 'FAIL: API Container ID 변경됨'
```

---

## 11. API rollback drill

API 이전 정상 SHA로 실제 rollback한 뒤 다시 `FIX_SHA`로 복귀한다. DB, Redis, RabbitMQ 데이터는 rollback하지 않는다.

### 11.1 주의: 구버전 checkout과 배포 스크립트

배포 스크립트는 대상 Git SHA로 repository를 checkout한다. 따라서 버그 수정 이전 SHA로 rollback하면 EC2 디스크의 `tools/deploy_green_ecr.sh`도 구버전으로 돌아간다.

rollback workflow를 시작할 때는 이미 실행 중인 수정 스크립트 프로세스가 배포를 수행하므로 rollback 자체는 수정 로직으로 진행된다. 그러나 최신 `FIX_SHA`로 복귀하기 전에는 8번 절차를 다시 실행해 수정 스크립트를 bootstrap해야 한다.

### 11.2 이전 정상 SHA로 rollback

1. ECR API repository에서 이전 정상 40자리 SHA tag가 존재하는지 확인한다.
2. `Deploy Green API`를 수동 실행한다.

| 입력 | 값 |
| --- | --- |
| Branch | `main` |
| `git_sha` | 이전 정상 SHA |
| `publish_only` | `false` |

3. workflow와 SSM이 성공하는지 확인한다.
4. 실제 API image와 manifest가 이전 정상 SHA로 일치하는지 확인한다.
5. `/healthz`, `/api/health`, Green CloudFront `/api/health`를 확인한다.

### 11.3 FIX_SHA로 복귀 전 수정 스크립트 재-bootstrap

Green EC2에서 다시 실행한다.

```bash
sudo -iu ubuntu

cd /opt/buildgraph/prototype

export FIX_SHA="실제_40자리_FIX_SHA"

git fetch origin
git checkout --detach "$FIX_SHA"
git rev-parse HEAD
```

수정 스크립트가 다시 존재하는지 확인한다.

```bash
grep -nE 'env -u API_IMAGE_URI|verify_running_image|read_manifest_value' \
  tools/deploy_green_ecr.sh
```

그다음 `Deploy Green API`를 `FIX_SHA`, `publish_only=false`로 다시 실행한다. 실제 image, manifest, health가 모두 `FIX_SHA` 기준으로 정상이어야 rollback drill이 완료된다.

---

## 12. Green 자동 CD 재활성화

다음 조건이 모두 통과한 뒤에만 `GREEN_CD_ENABLED`를 `true`로 되돌린다.

- 로컬 회귀 테스트 PASS
- 두 validator PASS
- ECR Compose config PASS
- API 실제 image와 manifest 일치
- XGB 실제 image와 manifest 일치
- API 배포 시 XGB Container ID 유지
- XGB 배포 시 API Container ID 유지
- Green `/healthz`, `/api/health` PASS
- Green CloudFront `/api/health` PASS
- API rollback과 최신 SHA 복귀 PASS

GitHub Console에서 `GREEN_CD_ENABLED=true`로 변경하거나 로컬 Mac에서 다음을 실행한다.

```bash
gh variable set GREEN_CD_ENABLED \
  --body true \
  --repo jungle-final-project/prototype

gh variable get GREEN_CD_ENABLED \
  --repo jungle-final-project/prototype
```

출력이 `true`인지 확인한다.

---

## 13. Path 기반 자동 배포 재검증

자동 CD를 활성화한 뒤 다음 실제 변경부터 독립 배포를 확인한다.

| 변경 경로 | 실행되어야 하는 Green workflow | 재시작되면 안 되는 서비스 |
| --- | --- | --- |
| `apps/web/**` | Deploy Green Web | API, XGB |
| `apps/api/**` | Deploy Green API | XGB |
| XGB 관련 경로 | Deploy Green XGB | API |

각 workflow에서 다음을 기록한다.

| 항목 | 기록 값 |
| --- | --- |
| Workflow name | Deploy Green Web/API/XGB |
| Git SHA | 40자리 SHA |
| Run ID 또는 URL | GitHub Actions 실행 URL |
| ECR image URI | API 또는 XGB image URI |
| SSM Command ID | API 또는 XGB 배포 Command ID |
| 실제 Container image | `docker inspect` 결과 |
| Health 결과 | `/healthz`, `/api/health` |
| 다른 서비스 Container ID 유지 | PASS/FAIL |
| Rollback 여부 | Yes/No |

---

## 14. 실패 시 처리

### 14.1 실제 image와 manifest가 다시 다름

1. 자동 CD를 다시 `false`로 바꾼다.
2. 신규 배포를 중지한다.
3. GitHub Actions의 요청 SHA와 ECR image URI를 기록한다.
4. SSM Standard Output과 Standard Error에서 실제 pull URI를 확인한다.
5. Green EC2에서 다음을 수집한다.

```bash
grep -E '^(API_IMAGE_URI|XGB_IMAGE_URI)=' \
  /opt/buildgraph/green-images.env

docker inspect buildgraph-green-api-1 \
  --format '{{.Config.Image}}'

docker inspect buildgraph-green-xgb-reranker-1 \
  --format '{{.Config.Image}} {{.State.Health.Status}}'
```

Secret 원문이나 `.env.prod` 원문은 수집하지 않는다.

### 14.2 API 재시작 직후 502

API 컨테이너 기동 중에는 Nginx가 잠깐 `502`를 반환할 수 있다. 최대 120초 동안 재시도한다.

```bash
for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1/api/health; then
    echo
    break
  fi

  if [[ "$attempt" -eq 60 ]]; then
    echo 'FAIL: API health timeout'
    exit 1
  fi

  sleep 2
done
```

120초 후에도 실패하면 정상 배포로 판정하지 않는다.

### 14.3 같은 SHA workflow 단순 재실행

현재 manifest가 이미 신규 SHA로 변경된 상태라면 같은 SHA workflow를 다시 실행했을 때 우연히 올바른 image가 적용될 수 있다. 그러나 다음 SHA 변경에서 문제가 재발하므로 단순 재실행만으로 장애를 종결하지 않는다.

반드시 회귀 테스트, 환경변수 우선순위 수정, 실제 실행 image 검증까지 완료한다.

---

## 15. 완료 조건

- [ ] `GREEN_CD_ENABLED=false`로 수정 중 자동 배포 차단
- [ ] 기존 셸 image 환경변수가 candidate를 덮는 회귀 테스트 작성
- [ ] 수정 전 회귀 테스트 FAIL 확인
- [ ] `source + set -a` 제거
- [ ] manifest 값을 export하지 않고 지역 변수로 읽음
- [ ] Compose 실행 시 `API_IMAGE_URI`, `XGB_IMAGE_URI` 상위 환경 제거
- [ ] 배포 후 실제 Container image URI 검증 추가
- [ ] image 불일치 시 배포 실패와 rollback 수행
- [ ] Shell 문법 검사 PASS
- [ ] Green deployment validator PASS
- [ ] Infrastructure validator PASS
- [ ] ECR Compose config PASS
- [ ] 수정 commit이 `main`에 반영됨
- [ ] Green EC2에 수정 스크립트 bootstrap 완료
- [ ] API workflow 수동 배포 PASS
- [ ] API 실행 image와 manifest SHA 일치
- [ ] API 배포 중 XGB Container ID 유지
- [ ] XGB workflow 수동 배포 PASS
- [ ] XGB 실행 image와 manifest SHA 일치
- [ ] XGB 배포 중 API Container ID 유지
- [ ] Green `/healthz`, `/api/health` PASS
- [ ] Green CloudFront `/api/health` PASS
- [ ] API rollback drill PASS
- [ ] rollback 후 FIX_SHA 복귀 PASS
- [ ] `GREEN_CD_ENABLED=true` 재활성화
- [ ] Web/API/XGB path 기반 독립 자동 배포 확인
- [ ] Blue EC2·CloudFront·Compose 무변경

모든 조건이 통과해야 이 문제를 해결 완료로 판정하고 Phase 8 완료 조건을 갱신한다.

---

## 16. 결과 기록표

Secret value는 기록하지 않는다.

| 항목 | 값 |
| --- | --- |
| 장애 확인 시각 |  |
| 장애 API 요청 SHA | `3b351c25eb1b2d8c9c4d4de1a4318a3610f24a63` |
| 장애 시 실제 API image SHA | `93a7f672f84added76246f55c8fc368a36c12e00` |
| 수정 commit FIX_SHA |  |
| API workflow Run URL |  |
| API SSM Command ID |  |
| API ECR image URI |  |
| API 실행 image URI |  |
| XGB workflow Run URL |  |
| XGB SSM Command ID |  |
| XGB ECR image URI |  |
| XGB 실행 image URI |  |
| 이전 정상 rollback SHA |  |
| rollback Run URL |  |
| 최신 FIX_SHA 복귀 Run URL |  |
| 자동 CD 재활성화 시각 |  |
| Blue 무변경 확인 | PASS / FAIL |
