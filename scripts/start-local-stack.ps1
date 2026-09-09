param(
    [switch]$SkipBackendBuild,
    [int]$StartupTimeoutSeconds = 180,
    [int]$BackendPort = 18080
)

$ErrorActionPreference = 'Stop'

function Import-DotEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return }
    $values = [ordered]@{}
    foreach ($rawLine in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        if ($line.StartsWith('export ')) { $line = $line.Substring(7).TrimStart() }
        $separator = $line.IndexOf('=')
        if ($separator -lt 1) { throw "Invalid .env entry: $rawLine" }
        $name = $line.Substring(0, $separator).Trim()
        if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { throw "Invalid .env variable name: $name" }
        $value = $line.Substring($separator + 1).Trim()
        if ($value.Length -ge 2 -and (
            ($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$name] = $value
    }
    $loaded = 0
    foreach ($entry in $values.GetEnumerator()) {
        if ($null -ne [Environment]::GetEnvironmentVariable($entry.Key, 'Process')) { continue }
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
        $loaded++
    }
    Write-Host "Loaded $loaded local setting(s) from .env; existing shell variables kept precedence."
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Import-DotEnv (Join-Path $repositoryRoot '.env')
$runtimeRoot = Join-Path $repositoryRoot '.runtime'
$logRoot = Join-Path $runtimeRoot 'logs\local-stack'
$pidRoot = Join-Path $runtimeRoot 'pids'
$pidFile = Join-Path $pidRoot 'local-stack.json'
$detachedInputFile = Join-Path $runtimeRoot ("detached-process-{0}.stdin" -f [Guid]::NewGuid().ToString('N'))
$serverDirectory = Join-Path $repositoryRoot 'services\server'
$python = Join-Path $runtimeRoot 'python\Scripts\python.exe'
$embeddingModel = Join-Path $runtimeRoot 'models\bge-small-zh-v1.5'
$aideSource = Join-Path $runtimeRoot 'vendor\AIDE'
$aideCheckpoint = Join-Path $runtimeRoot 'models\aide\GenImage_train.pth'
$clipModel = Join-Path $runtimeRoot 'models\clip\ViT-B-32.pt'
$animeSource = Join-Path $runtimeRoot 'vendor-src\AnimeDL2M\AniXplore\IMDLBenCo'
$animeCheckpoint = Join-Path $runtimeRoot 'models\anixplore\checkpoint.pth'
$aerobladeSource = Join-Path $runtimeRoot 'vendor-src\aeroblade'
$aerobladeReady = Join-Path $runtimeRoot 'models\aeroblade\ready'
$c2paTool = Join-Path $runtimeRoot 'tools\c2patool\c2patool.exe'
$llamaServer = Join-Path $runtimeRoot 'llama.cpp\bin\llama-server.exe'
$qwenModel = Join-Path $runtimeRoot 'models\qwen3-vl-4b-instruct-gguf\Qwen3VL-4B-Instruct-Q4_K_M.gguf'
$qwenProjector = Join-Path $runtimeRoot 'models\qwen3-vl-4b-instruct-gguf\mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf'
$viteEntry = Join-Path $repositoryRoot 'apps\web\node_modules\vite\bin\vite.js'
$serverJar = Join-Path $serverDirectory 'target\server-0.1.0-SNAPSHOT.jar'

New-Item -ItemType Directory -Force -Path $logRoot, $pidRoot, (Join-Path $runtimeRoot 'cache\tmp') | Out-Null
[System.IO.File]::WriteAllBytes($detachedInputFile, [byte[]]@())

$state = [ordered]@{
    repositoryRoot = $repositoryRoot
    startedAt = [DateTime]::UtcNow.ToString('O')
    detachedInputFile = $detachedInputFile
    dockerDesktopStartedByScript = $false
    processes = @()
}

function Save-State {
    $state | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $pidFile -Encoding UTF8
}

function Test-TcpPort([int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync('127.0.0.1', $Port)
        return $task.Wait(400) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-ForPortAvailable([string]$Name, [int]$Port, [int]$TimeoutSeconds = 30) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not (Test-TcpPort $Port)) {
            Start-Sleep -Milliseconds 750
            if (-not (Test-TcpPort $Port)) { return }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$Name cannot start because port $Port remained in use for $TimeoutSeconds seconds."
}

function Require-File([string]$Path, [string]$Description) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Description is missing: $Path"
    }
}

function Start-ManagedProcess(
    [string]$Name,
    [string]$Executable,
    [string[]]$Arguments,
    [string]$WorkingDirectory
) {
    $stdout = Join-Path $logRoot "$Name.stdout.log"
    $stderr = Join-Path $logRoot "$Name.stderr.log"
    $escapedArguments = $Arguments | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    }
    $process = Start-Process `
        -FilePath $Executable `
        -ArgumentList $escapedArguments `
        -WorkingDirectory $WorkingDirectory `
        -WindowStyle Hidden `
        -RedirectStandardInput $detachedInputFile `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -PassThru
    $state.processes += [ordered]@{
        name = $Name
        pid = $process.Id
        executable = $Executable
        startedAt = $process.StartTime.ToUniversalTime().ToString('O')
        stdout = $stdout
        stderr = $stderr
    }
    Save-State
    return $process
}

function Wait-ForHttp([string]$Name, [string]$Url, [System.Diagnostics.Process]$Process) {
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            $stderr = Join-Path $logRoot "$Name.stderr.log"
            $stdout = Join-Path $logRoot "$Name.stdout.log"
            $tail = @()
            if (Test-Path -LiteralPath $stdout) { $tail += Get-Content -LiteralPath $stdout -Tail 40 }
            if (Test-Path -LiteralPath $stderr) { $tail += Get-Content -LiteralPath $stderr -Tail 40 }
            throw "$Name exited during startup.`n$($tail -join [Environment]::NewLine)"
        }
        try {
            $response = Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 750
        }
    }
    throw "$Name did not become ready within $StartupTimeoutSeconds seconds. See $logRoot"
}

function Stop-StartedProcesses {
    $entries = @($state.processes)
    [Array]::Reverse($entries)
    foreach ($entry in $entries) {
        $process = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
        if ($process) {
            Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
        }
    }
}

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

function Wait-ForDocker {
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-DockerReady) { return }
        Start-Sleep -Seconds 2
    }
    throw "Docker Desktop did not become ready within $StartupTimeoutSeconds seconds."
}

if (Test-Path -LiteralPath $pidFile) {
    $existing = Get-Content -Raw -LiteralPath $pidFile | ConvertFrom-Json
    $live = @($existing.processes | Where-Object { Get-Process -Id $_.pid -ErrorAction SilentlyContinue })
    if ($live.Count -gt 0) {
        throw "OriginGuard local stack is already running. Run .\scripts\stop-local-stack.ps1 first."
    }
    Remove-Item -LiteralPath $pidFile -Force
}

Require-File $python 'Project Python runtime'
Require-File (Join-Path $embeddingModel 'model.safetensors') 'BGE model'
Require-File (Join-Path $aideSource 'models\AIDE.py') 'AIDE official source (run scripts/setup-aide.ps1)'
Require-File $aideCheckpoint 'AIDE GenImage checkpoint (run scripts/setup-aide.ps1)'
Require-File $clipModel 'OpenAI CLIP ViT-B/32 model (run scripts/setup-clip.ps1)'
Require-File $llamaServer 'llama.cpp server'
Require-File $qwenModel 'Qwen3-VL model'
Require-File $qwenProjector 'Qwen3-VL vision projector'
Require-File $viteEntry 'Vite runtime'

$java = (Get-Command java -ErrorAction Stop).Source
$node = (Get-Command node -ErrorAction Stop).Source
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Maven was not found in PATH.' }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker CLI was not found in PATH.' }

foreach ($port in 5173, $BackendPort, 8090, 8091, 8092, 6379, 25672, 15672) {
    if (Test-TcpPort $port) {
        throw "Port $port is already in use. Stop the existing service before starting OriginGuard."
    }
}

try {
    if (-not (Test-DockerReady)) {
        Write-Host 'Docker Desktop is not running; starting it now...'
        docker desktop start
        if ($LASTEXITCODE -ne 0) { throw 'Unable to start Docker Desktop.' }
        $state.dockerDesktopStartedByScript = $true
        Save-State
        Wait-ForDocker
    }
    Push-Location $repositoryRoot
    try {
        docker compose up -d postgres minio redis rabbitmq
        if ($LASTEXITCODE -ne 0) { throw 'Unable to start PostgreSQL, MinIO, Redis and RabbitMQ.' }
    } finally {
        Pop-Location
    }

    $env:EMBEDDING_MODEL_PATH = $embeddingModel
    $env:AIDE_SOURCE_PATH = $aideSource
    $env:AIDE_CHECKPOINT_PATH = $aideCheckpoint
    $env:AIDE_DEVICE = if ($env:AIDE_DEVICE) { $env:AIDE_DEVICE } else { 'cpu' }
    $env:AIDE_PRECISION = if ($env:AIDE_PRECISION) { $env:AIDE_PRECISION } else { 'auto' }
    $env:CLIP_MODEL_PATH = $clipModel
    $env:CLIP_DEVICE = if ($env:CLIP_DEVICE) { $env:CLIP_DEVICE } else { 'cpu' }
    $env:HF_HOME = Join-Path $runtimeRoot 'cache\huggingface'
    $env:TORCH_HOME = Join-Path $runtimeRoot 'cache\torch'
    $env:NO_ALBUMENTATIONS_UPDATE = '1'
    $env:TEMP = Join-Path $runtimeRoot 'cache\tmp'
    $env:TMP = $env:TEMP
    $env:ANIXPLORE_SOURCE_PATH = $animeSource
    $env:ANIXPLORE_CHECKPOINT_PATH = $animeCheckpoint
    $env:ANIXPLORE_DEVICE = if ($env:ANIXPLORE_DEVICE) { $env:ANIXPLORE_DEVICE } else { 'auto' }
    $env:AEROBLADE_SOURCE_PATH = $aerobladeSource
    $env:AEROBLADE_DEVICE = if ($env:AEROBLADE_DEVICE) { $env:AEROBLADE_DEVICE } else { 'auto' }
    $embedding = Start-ManagedProcess 'model-api' $python @(
        '-m', 'uvicorn', 'originguard_model_api.main:app',
        '--app-dir', (Join-Path $repositoryRoot 'services\model-api\src'),
        '--host', '127.0.0.1', '--port', '8090'
    ) $repositoryRoot
    Wait-ForHttp 'model-api' 'http://127.0.0.1:8090/health' $embedding

    $env:C2PATOOL_PATH = if (Test-Path -LiteralPath $c2paTool -PathType Leaf) { $c2paTool } else { '' }
    $c2pa = Start-ManagedProcess 'c2pa-sidecar' $python @(
        '-m', 'uvicorn', 'originguard_c2pa_sidecar.main:app',
        '--app-dir', (Join-Path $repositoryRoot 'services\c2pa-sidecar\src'),
        '--host', '127.0.0.1', '--port', '8091'
    ) $repositoryRoot
    Wait-ForHttp 'c2pa-sidecar' 'http://127.0.0.1:8091/health' $c2pa

    $qwen = Start-ManagedProcess 'qwen-vl' $llamaServer @(
        '--model', $qwenModel, '--mmproj', $qwenProjector,
        '--alias', 'qwen3-vl-4b-instruct-q4-k-m',
        '--host', '127.0.0.1', '--port', '8092',
        '--ctx-size', '4096', '--batch-size', '256', '--ubatch-size', '128',
        '--parallel', '1', '--n-gpu-layers', '999', '--flash-attn', 'on', '--jinja'
    ) (Split-Path $llamaServer)
    Wait-ForHttp 'qwen-vl' 'http://127.0.0.1:8092/health' $qwen

    $env:EMBEDDING_PROVIDER = 'bge-small-zh-v1.5'
    $env:MODEL_API_BASE_URL = 'http://127.0.0.1:8090'
    $env:AGENT_PLANNER_PROVIDER = 'local-qwen'
    $env:AIGC_EXPLAINER_PROVIDER = 'local-qwen'
    $env:MEDIA_TYPE_CLASSIFIER_PROVIDER = 'model-api'
    $env:ANIXPLORE_ENABLED = if (Test-Path -LiteralPath $animeCheckpoint -PathType Leaf) { 'true' } else { 'false' }
    $env:AEROBLADE_ENABLED = if (Test-Path -LiteralPath $aerobladeReady -PathType Leaf) { 'true' } else { 'false' }
    $env:QWEN_VL_BASE_URL = 'http://127.0.0.1:8092'
    $env:AGENT_ASYNC_ENABLED = 'true'
    $env:C2PA_ENABLED = 'true'
    $env:C2PA_BASE_URL = 'http://127.0.0.1:8091'
    $env:REDIS_HOST = '127.0.0.1'
    $env:REDIS_PORT = '6379'
    $env:RABBITMQ_HOST = '127.0.0.1'
    $env:RABBITMQ_PORT = '25672'
    $env:SERVER_PORT = [string]$BackendPort
    $env:VITE_API_TARGET = "http://127.0.0.1:$BackendPort"
    if (-not $SkipBackendBuild) {
        Push-Location $serverDirectory
        try {
            & mvn -DskipTests package
            if ($LASTEXITCODE -ne 0) { throw "Backend build exited with code $LASTEXITCODE" }
        } finally {
            Pop-Location
        }
    }
    Require-File $serverJar 'OriginGuard backend JAR'
    Wait-ForPortAvailable 'OriginGuard backend' $BackendPort
    $backend = Start-ManagedProcess 'server' $java @('-jar', $serverJar) $serverDirectory
    try {
        Wait-ForHttp 'server' "http://127.0.0.1:$BackendPort/actuator/health" $backend
    } catch {
        $serverLog = Join-Path $logRoot 'server.stdout.log'
        $portConflict = $backend.HasExited -and
            (Test-Path -LiteralPath $serverLog) -and
            ((Get-Content -Raw -LiteralPath $serverLog) -match "Port $BackendPort was already in use")
        if (-not $portConflict) { throw }

        Write-Warning "Port $BackendPort was occupied while the backend was binding; waiting and retrying once."
        Wait-ForPortAvailable 'OriginGuard backend' $BackendPort 45
        $backend = Start-ManagedProcess 'server' $java @('-jar', $serverJar) $serverDirectory
        Wait-ForHttp 'server' "http://127.0.0.1:$BackendPort/actuator/health" $backend
    }

    $web = Start-ManagedProcess 'web' $node @(
        $viteEntry, '--host', '127.0.0.1', '--port', '5173', '--strictPort'
    ) (Join-Path $repositoryRoot 'apps\web')
    Wait-ForHttp 'web' 'http://127.0.0.1:5173/' $web

    Write-Host ''
    Write-Host 'OriginGuard local stack is ready.' -ForegroundColor Green
    Write-Host '  Web:       http://127.0.0.1:5173'
    Write-Host "  Backend:   http://127.0.0.1:$BackendPort"
    Write-Host '  Model API: http://127.0.0.1:8090 (Embedding + AIGC detection + media classification)'
    Write-Host '  Qwen API:  http://127.0.0.1:8092'
    Write-Host '  RabbitMQ:  http://127.0.0.1:15672'
    Write-Host '  AMQP:      127.0.0.1:25672'
    Write-Host '  Redis:     127.0.0.1:6379'
    Write-Host "  Logs:      $logRoot"
    Write-Host 'Stop with: .\scripts\stop-local-stack.ps1'
} catch {
    $startupFailure = $_
    Stop-StartedProcesses
    if ((Get-Command docker -ErrorAction SilentlyContinue) -and (Test-DockerReady)) {
        Push-Location $repositoryRoot
        try {
            try { docker compose stop postgres minio redis rabbitmq 2>&1 | Out-Null } catch {}
        } finally {
            Pop-Location
        }
        if ($state.dockerDesktopStartedByScript) {
            try { docker desktop stop 2>&1 | Out-Null } catch {}
        }
    }
    if (Test-Path -LiteralPath $pidFile) { Remove-Item -LiteralPath $pidFile -Force }
    throw $startupFailure
}
