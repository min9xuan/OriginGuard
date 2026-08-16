$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pidFile = Join-Path $repositoryRoot '.runtime\pids\local-stack.json'

function Test-DockerReady {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $null = & docker info --format '{{.ServerVersion}}' 2>&1
        return $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

if (Test-Path -LiteralPath $pidFile) {
    $state = Get-Content -Raw -LiteralPath $pidFile | ConvertFrom-Json
    $entries = @($state.processes)
    [Array]::Reverse($entries)
    foreach ($entry in $entries) {
        $process = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
        if (-not $process) {
            Write-Host "$($entry.name) is already stopped."
            continue
        }

        $sameExecutable = $false
        try {
            $sameExecutable = [string]::Equals(
                [System.IO.Path]::GetFullPath($process.Path),
                [System.IO.Path]::GetFullPath([string]$entry.executable),
                [System.StringComparison]::OrdinalIgnoreCase)
        } catch {
            Write-Warning "Unable to verify process $($entry.name) (PID $($entry.pid)); it was not stopped."
            continue
        }
        $expectedStart = if ($entry.startedAt -is [DateTime]) {
            $entry.startedAt.ToUniversalTime().ToString('O')
        } else {
            [DateTimeOffset]::Parse([string]$entry.startedAt).UtcDateTime.ToString('O')
        }
        $actualStart = $process.StartTime.ToUniversalTime().ToString('O')
        $sameStart = [string]::Equals($actualStart, $expectedStart, [System.StringComparison]::Ordinal)
        if (-not ($sameExecutable -and $sameStart)) {
            Write-Warning "PID $($entry.pid) did not match $($entry.name) (pathMatch=$sameExecutable, startMatch=$sameStart); it was not stopped."
            continue
        }

        Stop-Process -Id $process.Id
        Write-Host "Stopped $($entry.name) (PID $($entry.pid))."
    }
} else {
    Write-Host 'No OriginGuard local-stack PID file was found.'
    $state = $null
}

if (Get-Command docker -ErrorAction SilentlyContinue) {
    if (Test-DockerReady) {
        Push-Location $repositoryRoot
        try {
            docker compose stop postgres minio
            if ($LASTEXITCODE -ne 0) {
                Write-Warning 'PostgreSQL or MinIO could not be stopped.'
            }
        } finally {
            Pop-Location
        }
    }
}

if ($state -and $state.dockerDesktopStartedByScript) {
    if (Test-DockerReady) {
        docker desktop stop
        if ($LASTEXITCODE -ne 0) {
            Write-Warning 'Docker Desktop could not be stopped.'
        }
    }
}

if (Test-Path -LiteralPath $pidFile) { Remove-Item -LiteralPath $pidFile -Force }

Write-Host 'OriginGuard local stack is stopped.' -ForegroundColor Green
