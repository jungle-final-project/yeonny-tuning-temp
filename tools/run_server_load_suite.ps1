[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:18082",
    [ValidateSet("smoke", "load", "stress", "spike", "soak", "breakpoint", "capacity")]
    [string[]]$Profiles = @("smoke", "load", "stress", "spike", "soak", "breakpoint"),
    [string]$UserEmail = "user@example.com",
    [string]$UserPassword = "passw0rd!",
    [string]$SoakDuration = "1h",
    [int]$SoakRate = 30,
    [int]$SoakWindowMinutes = 5,
    [double]$LoginRatio = 0.05,
    [ValidateSet("local", "aws")]
    [string]$SloProfile = "local",
    [string]$K6Image = "grafana/k6:0.54.0",
    [string]$RunId,
    [string]$ApiLogPath
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$shortCommit = (& git -C $repoRoot rev-parse --short HEAD).Trim()
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "{0}KST-{1}" -f (Get-Date -Format "yyyyMMdd'T'HHmmss"), $shortCommit
}

# "1h", "90m", "1h30m", "300s" 형식을 분으로 환산한다.
function ConvertTo-DurationMinutes {
    param([string]$Duration)
    $tokenMatches = [regex]::Matches($Duration, '(\d+)\s*(h|m|s)')
    if ($tokenMatches.Count -eq 0) {
        throw "Unsupported SoakDuration format: $Duration (use e.g. 1h, 90m, 1h30m)"
    }
    $total = 0.0
    foreach ($token in $tokenMatches) {
        $value = [double]$token.Groups[1].Value
        switch ($token.Groups[2].Value) {
            "h" { $total += $value * 60 }
            "m" { $total += $value }
            "s" { $total += $value / 60 }
        }
    }
    return $total
}

# Soak 구간 수는 하드코딩(12) 대신 지속시간에서 계산한다: ceil(분 / 구간분).
$soakWindowCount = [int][math]::Max(1, [math]::Ceiling((ConvertTo-DurationMinutes $SoakDuration) / $SoakWindowMinutes))
$loginRatioText = $LoginRatio.ToString([System.Globalization.CultureInfo]::InvariantCulture)

$runDir = Join-Path $repoRoot "infra\k6\reports\runs\$RunId"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$manifest = [ordered]@{
    runId = $RunId
    startedAt = (Get-Date).ToString("o")
    endedAt = $null
    commit = (& git -C $repoRoot rev-parse HEAD).Trim()
    branch = (& git -C $repoRoot branch --show-current).Trim()
    baseUrl = $BaseUrl
    k6Image = $K6Image
    profiles = @($Profiles)
    soakDuration = $SoakDuration
    soakRate = $SoakRate
    soakWindowMinutes = $SoakWindowMinutes
    soakWindowCount = $soakWindowCount
    loginRatio = $LoginRatio
    sloProfile = $SloProfile
    host = $env:COMPUTERNAME
    results = @()
}

function Write-Manifest {
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $runDir "manifest.json") -Encoding utf8
}

function Start-ResourceMonitor {
    param([string]$Profile)

    if ($Profile -ne "soak") {
        return $null
    }
    try {
        $uri = [Uri]$BaseUrl
        $connection = Get-NetTCPConnection -LocalPort $uri.Port -State Listen -ErrorAction Stop | Select-Object -First 1
        $processId = $connection.OwningProcess
    } catch {
        return $null
    }

    $csvPath = Join-Path $runDir "server-soak-resources.csv"
    $stopPath = Join-Path $runDir ".stop-soak-monitor"
    Remove-Item -LiteralPath $stopPath -Force -ErrorAction SilentlyContinue
    "timestamp,workingSetMb,privateMb,cpuSeconds" | Set-Content -Path $csvPath -Encoding utf8
    return Start-Job -ArgumentList $processId,$csvPath,$stopPath -ScriptBlock {
        param($monitoredProcessId, $outputPath, $sentinelPath)
        while (-not (Test-Path -LiteralPath $sentinelPath)) {
            $process = Get-Process -Id $monitoredProcessId -ErrorAction SilentlyContinue
            if ($process) {
                "$(Get-Date -Format o),$([math]::Round($process.WorkingSet64 / 1MB, 2)),$([math]::Round($process.PrivateMemorySize64 / 1MB, 2)),$([math]::Round($process.CPU, 2))" |
                    Add-Content -Path $outputPath -Encoding utf8
            }
            Start-Sleep -Seconds 60
        }
    }
}

function Stop-ResourceMonitor {
    param($Job)

    if ($null -eq $Job) {
        return
    }
    New-Item -ItemType File -Force -Path (Join-Path $runDir ".stop-soak-monitor") | Out-Null
    Wait-Job $Job -Timeout 65 | Out-Null
    Stop-Job $Job -ErrorAction SilentlyContinue
    Remove-Job $Job -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $runDir ".stop-soak-monitor") -Force -ErrorAction SilentlyContinue
}

Write-Manifest
foreach ($profile in $Profiles) {
    $profileStartedAt = Get-Date
    $summaryRelative = "infra/k6/reports/runs/$RunId/server-$profile.json"
    $consoleLog = Join-Path $runDir "server-$profile.console.log"
    $monitorJob = Start-ResourceMonitor -Profile $profile

    $envPairs = @(
        "TEST_TYPE=$profile",
        "BASE_URL=$BaseUrl",
        "USER_EMAIL=$UserEmail",
        "USER_PASSWORD=$UserPassword",
        "LOGIN_RATIO=$loginRatioText",
        "SLO_PROFILE=$SloProfile",
        "SUMMARY_PATH=/work/$summaryRelative",
        "SOAK_DURATION=$SoakDuration",
        "SOAK_RATE=$SoakRate",
        "SOAK_WINDOW_MINUTES=$SoakWindowMinutes",
        "SOAK_WINDOW_COUNT=$soakWindowCount"
    )
    # 선택 튜닝 env는 호출 셸에 설정된 경우에만 그대로 전달한다.
    foreach ($name in @("SHORT", "BREAKPOINT_LEVELS", "BREAKPOINT_RAMP", "BREAKPOINT_HOLD", "BREAKPOINT_MAX_RATE", "STARTUP_JITTER_SECONDS", "THINK_TIME_SECONDS")) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $envPairs += "$name=$value"
        }
    }

    $dockerArgs = @("run", "--rm")
    foreach ($pair in $envPairs) {
        $dockerArgs += @("-e", $pair)
    }
    $dockerArgs += @(
        "-v", "${repoRoot}:/work",
        "-w", "/work",
        $K6Image,
        "run", "--quiet", "infra/k6/server-workload.js"
    )

    # Windows PowerShell 5.1 wraps native stderr as non-terminating ErrorRecord objects.
    # k6 writes request timeout warnings to stderr even when the profile should continue,
    # so temporarily relax ErrorActionPreference and use the native exit code as truth.
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & docker @dockerArgs 2>&1 | Tee-Object -FilePath $consoleLog
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference
    Stop-ResourceMonitor -Job $monitorJob
    $health = "UNKNOWN"
    try {
        $healthResponse = Invoke-RestMethod -Uri "$BaseUrl/api/health" -TimeoutSec 5
        $health = $healthResponse.status
    } catch {
        $health = "DOWN"
    }
    $manifest.results += [ordered]@{
        profile = $profile
        startedAt = $profileStartedAt.ToString("o")
        endedAt = (Get-Date).ToString("o")
        exitCode = $exitCode
        healthAfter = $health
        summary = "server-$profile.json"
        consoleLog = "server-$profile.console.log"
    }
    Write-Manifest
}

if ($ApiLogPath -and (Test-Path -LiteralPath $ApiLogPath)) {
    Copy-Item -LiteralPath $ApiLogPath -Destination (Join-Path $runDir "api.log")
}
$manifest.endedAt = (Get-Date).ToString("o")
Write-Manifest
Write-Output $runDir
