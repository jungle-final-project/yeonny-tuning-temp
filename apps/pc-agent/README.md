# PCAgent

4번 담당자의 PCAgent/AS 흐름 시작점입니다. 현재는 실제 Windows Service나 installer가 아니라, AS 업로드 테스트에 사용할 JSON Lines 로그를 만들고 사용자가 확인한 IncidentWindow 구간을 gzip 업로드하는 CLI/트레이 MVP입니다.

## 실행

Python 3.11 기준 CLI입니다. 저장소 루트에서 `scripts/setup-dev`를 실행하면 필요한 Python 의존성이 `.venv`에 설치됩니다.

```powershell
cd apps/pc-agent
pip install -r requirements.txt
python buildgraph_agent.py sample --out ../../seed/sample-agent-log.jsonl
python buildgraph_agent.py export --source ../../seed/sample-agent-log.jsonl --out incident-window.jsonl --symptom-type REMOTE_DRIVER_OS
```

macOS/Linux에서 `pip` 또는 `python` 명령이 없다면 `pip3`, `python3`를 사용합니다.

## Goal 11/12 CLI

`agent-config.example.json`을 복사해 `agent-config.json`을 만들고, register를 실행했거나 Goal 10에서 받은 `agentToken`을 넣은 상태를 가정합니다.
`apiBaseUrl`은 Agent API 서버, `webBaseUrl`은 티켓을 열 웹 앱 주소입니다. 로컬 데모는 각각 `http://localhost:8080`, `http://localhost:5173`을 사용합니다.

```powershell
cd apps/pc-agent
python buildgraph_agent.py status --config ./agent-config.json
python buildgraph_agent.py doctor --config ./agent-config.json
python buildgraph_agent.py register --config ./agent-config.json
python buildgraph_agent.py collect --config ./agent-config.json --iterations 1
python buildgraph_agent.py upload --config ./agent-config.json --symptom "게임 중 프레임 드랍" --symptom-type REMOTE_DRIVER_OS --no-open
```

기본 수집 파일은 `logDir/agent-metrics.jsonl`입니다. `collect`는 기본 5초 간격이며, 검증용 기본값은 `--iterations 1`입니다. `--iterations 0`을 주면 계속 실행합니다.

하드웨어 metric은 실제 수집 가능한 값만 기록합니다. 디스크 컬럼은 용량 사용률이 아니라 `psutil.disk_io_counters()` delta 기반의 활성 추정값을 우선 표시합니다. Windows에서 디스크 I/O counter가 `None`이거나 시간 counter가 비정상이라면 OS 성능 카운터가 꺼져 있을 수 있으며, 관리자 터미널에서 `diskperf -y`가 필요할 수 있습니다. Agent가 이 명령을 자동 실행하지는 않습니다.

업로드 명령은 다음을 수행합니다.

- 증상 유형에 맞는 IncidentWindow row 선택
- `{incidentId}.jsonl.gz` 생성
- `POST /api/agent/log-uploads` multipart 요청 생성
- `Authorization: Bearer <agentToken>` 사용
- `Idempotency-Key` 생성 및 출력
- 응답의 `ticketId` 파싱
- 기본 브라우저로 `/support/{ticketId}` 열기, `--no-open`이면 URL만 출력

선택한 구간 안에 보낼 로그가 없거나 로그 파일이 없으면 빈 gzip을 만들지 않고 명확한 오류로 종료합니다. `register`로 token을 저장할 때는 Windows에서 config 파일 ACL을 현재 사용자, Administrators, SYSTEM 중심으로 제한합니다. MVP에서는 token이 여전히 로컬 config에 저장되므로 운영 배포 전에는 Windows Credential Manager 같은 저장소 검토가 필요합니다.

같은 `Idempotency-Key`를 재사용하려면 아래처럼 명시합니다.

```powershell
python buildgraph_agent.py upload --config ./agent-config.json --idempotency-key agent-upload-demo-001 --no-open
```

## Windows exe 빌드

개발용 Windows 실행 파일은 PyInstaller로 생성합니다. 서명, installer, tray app, Windows Service, auto-update는 아직 포함하지 않습니다.

```powershell
cd apps/pc-agent
build-agent-exe.cmd
.\dist\agent-cli.exe doctor --config agent-config.example.json
```

`agent.exe`는 사용자용 무콘솔 실행 파일입니다. 인자 없이 더블클릭하면 `%LOCALAPPDATA%\BuildGraphAgent` 아래에 기본 config/log 폴더를 만들고, 실행 파일을 `%LOCALAPPDATA%\BuildGraphAgent\PCAgent.exe`로 복사한 뒤 Windows 시작프로그램에 고정 경로로 등록합니다. 이후 트레이 아이콘으로 백그라운드 수집을 시작합니다. 트레이 메뉴에서는 로그 뷰어 열기, 로그 폴더 열기, AS 페이지 열기, 종료를 사용할 수 있습니다. 로그 뷰어는 날짜와 시간을 선택해 1시간 단위 JSONL row를 가볍게 보여주는 창입니다. 이것은 Windows Service가 아니라 MVP용 시작프로그램 기반 백그라운드 실행입니다.

사용자 등록은 웹 지원 페이지의 PCAgent 다운로드 흐름을 기준으로 합니다. 웹은 `/api/users/me/agent-activation-token`으로 activation token을 발급한 뒤 `PCAgent.zip`을 내려받게 합니다. ZIP 안에는 `PCAgent.exe`, `pcagent-activation.json`, `README.txt`가 들어 있습니다. 사용자는 압축을 풀고 `PCAgent.exe`를 실행해야 합니다. Agent는 첫 실행 때 같은 폴더 또는 다운로드 폴더의 activation JSON에서 token을 읽어 기존 `agentToken`을 지운 뒤 현재 prototype DB에 다시 등록합니다. 등록에 성공하면 activation JSON은 자동 삭제합니다. 저장소의 `apps/web/public/downloads/pc-agent/agent.exe`를 직접 실행하면 activation JSON이 없으므로, 기존 로컬 config나 demo token이 현재 DB와 맞지 않는 환경에서는 PC 진단이 등록 실패로 끝날 수 있습니다.

터미널 출력이 필요한 `status`, `doctor`, `register`, `collect`, `upload` 검증은 콘솔 실행 파일인 `agent-cli.exe`를 사용합니다.

현재 로그 뷰어의 첫 화면은 상태 홈입니다. 상태 홈은 서버 연결, 마지막 업로드, 시작프로그램, 버전 카드와 최근 감지 신호, 로그 현황 테이블을 보여줍니다. 카드 상태는 로컬 config와 Agent 로그에 남은 heartbeat/upload/startup 정보를 기준으로 표시합니다. 이번 UI는 별도 동기 API 호출을 추가하지 않으며, heartbeat 호출, 로그 업로드 마법사, 위험 모달, AS 접수 마법사는 기존 흐름을 유지합니다. `tkinter`가 없는 패키징 환경에서는 PowerShell fallback 창이 같은 개인정보 기준으로 표시됩니다.

백그라운드 수집 중 `detect_recent_signals()`가 드라이버/앱/네트워크 오류 반복, WHEA/BSOD, Kernel-Power, SMART, thermal 계열의 명확한 신호를 찾으면 오른쪽 아래 Blue/Teal 알림 패널을 띄웁니다. 이 패널은 진단 확정 UI가 아니라 AS 검토 시작 여부를 묻는 알림이며, raw log 원문, token, raw path, 전체 process list는 표시하지 않습니다. 사용자가 `로그 전송하고 AS 검토 요청`을 누르면 기존 IncidentWindow gzip 업로드 흐름으로 `/api/agent/log-uploads`에 전송하고 ticket 생성 결과를 엽니다. 등록, 동의, 서버 연결 문제로 전송할 수 없으면 사용자용 실패 문구만 표시합니다.

개발 중 상태 홈만 바로 확인할 때는 bare `python` 대신 저장소 루트의 `.venv` Python 또는 빌드된 `agent.exe`를 사용합니다. Windows 사용자별 `%LOCALAPPDATA%\BuildGraphAgent\agent-config.json`은 token 보호를 위해 ACL이 제한될 수 있으므로, Codex/개발 검증에서는 명시적인 workspace config를 넘깁니다.

```powershell
cd C:\나만무\prototype
.\.venv\Scripts\python.exe apps\pc-agent\buildgraph_agent.py viewer --config apps\pc-agent\agent-config.example.json
```

로컬 웹 데모에서 내려받는 바이너리 원본은 `apps/web/public/downloads/pc-agent/agent.exe`에 둡니다. 새 exe를 만들면 해당 위치에 복사한 뒤 웹 이미지를 다시 빌드합니다. 실제 사용자는 웹 다운로드 버튼으로 `PCAgent.zip`을 받고 압축 해제 후 같은 폴더의 `PCAgent.exe`를 실행해야 합니다. 첫 실행 후에는 시작프로그램이 `%LOCALAPPDATA%\BuildGraphAgent\PCAgent.exe`를 가리키므로 압축 해제 폴더가 이동되거나 삭제되어도 자동 실행 경로가 깨지지 않습니다. 기존 `BuildGraphAgent-<activationToken>.exe` 파일명 방식도 호환 목적으로 인식하지만, 신규 다운로드 흐름에서는 token을 파일명에 노출하지 않습니다.

## 출력 예시

`status` 예시:

```text
REGISTERED
```

`doctor` 예시:

```text
config: ok
apiBaseUrl: http://localhost:8080
registration: REGISTERED
logDir: C:\...\apps\pc-agent\out\logs
logFile: out\logs\agent-metrics.jsonl
logBytes: 412
agentVersion: 0.1.0
policyVersion: policy-v1
agentToken: present
```

## 현재 역할

- CPU/RAM/Disk/GPU/온도/프로세스/오류 이벤트 형태의 샘플 로그 생성
- 최근 N분 로그 export
- IncidentWindow 기반 JSONL gzip 생성
- Agent token 기반 `POST /api/agent/log-uploads` 업로드
- 업로드 응답 `ticketId`로 `/support/{ticketId}` URL 생성
- 더블클릭 시 시작프로그램 등록과 트레이 기반 백그라운드 하드웨어 metric 수집
- 트레이 아이콘에서 상태 홈과 날짜/시간별 전체 로그내용 뷰어 열기
- 명확한 이벤트 감지 시 오른쪽 아래 알림 패널로 AS 검토 요청 연결
- `viewer --config ...`로 같은 상태 홈을 개발 검증용으로 직접 열기

## 구현 시 지켜야 할 점

- JSONL 한 줄은 하나의 timestamp 관측치로 유지합니다.
- 실제 센서 수집을 붙이기 전에도 sample/export 명령은 계속 동작해야 합니다.
- 사용자 동의와 IncidentWindow 범위는 웹/API 흐름과 같은 필드명을 사용합니다.
- 원인 후보 분석 결과는 사용자 화면이 아니라 관리자 티켓 상세에서 다룹니다.
- register API 호출과 token 저장은 `register` 명령에서만 수행합니다.
- `/api/agent-logs/upload`가 아니라 `/api/agent/log-uploads`만 사용합니다.
- 현재 백그라운드 실행은 Windows Service가 아니라 사용자 시작프로그램 등록 방식입니다.
- `webBaseUrl`이 있으면 support URL은 `apiBaseUrl` 포트 추론 대신 해당 값을 우선 사용합니다.

## 이후 확장 후보

- `psutil`, NVML, Windows Event Log 수집 연결
- JSONL schema validation
- 문제 상황 재현용 sample profile 추가
- 업로드 파일 크기와 보관 기간 정책 적용
