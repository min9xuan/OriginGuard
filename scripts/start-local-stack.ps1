param(
    [switch]$SkipBackendBuild,
    [int]$StartupTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeRoot = Join-Path $repositoryRoot '.runtime'
$logRoot = Join-Path $runtimeRoot 'logs\local-stack'
$pidRoot = Join-Path $runtimeRoot 'pids'
$pidFile = Join-Path $pidRoot 'local-stack.json'
$serverDirectory = Join-Path $repositoryRoot 'services\server'
$python = Join-Path $runtimeRoot 'python\Scripts\python.exe'
$embeddingModel = Join-Path $runtimeRoot 'models\bge-small-zh-v1.5'
$aideSource = Join-Path $runtimeRoot 'vendor\AIDE'
$aideCheckpoint = Join-Path $runtimeRoot 'models\aide\GenImage_train.pth'
$clipModel = Join-Path $runtimeRoot 'models\clip\ViT-B-32.pt'
$llamaServer = Join-Path $runtimeRoot 'llama.cpp\bin\llama-server.exe'
$qwenModel = Join-Path $runtimeRoot 'models\qwen3-vl-4b-instruct-gguf\Qwen3VL-4B-Instruct-Q4_K_M.gguf'
$qwenProjector = Join-Path $runtimeRoot 'models\qwen3-vl-4b-instruct-gguf\mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf'
$viteEntry = Join-Path $repositoryRoot 'apps\web\node_modules\vite\bin\vite.js'
$serverJar = Join-Path $serverDirectory 'target\server-0.1.0-SNAPSHOT.jar'

New-Item -ItemType Directory -Force -Path $logRoot, $pidRoot, (Join-Path $runtimeRoot 'cache\tmp') | Out-Null

$state = [ordered]@{
    repositoryRoot = $repositoryRoot
    startedAt = [DateTime]::UtcNow.ToString('O')
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
            $tail = if (Test-Path -LiteralPath $stderr) { Get-Content -LiteralPath $stderr -Tail 30 } else { @() }
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

foreach ($port in 5173, 8080, 8090, 8092) {
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
        docker compose up -d postgres minio
        if ($LASTEXITCODE -ne 0) { throw 'Unable to start PostgreSQL and MinIO.' }
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
    $env:TEMP = Join-Path $runtimeRoot 'cache\tmp'
    $env:TMP = $env:TEMP
    $embedding = Start-ManagedProcess 'model-api' $python @(
        '-m', 'uvicorn', 'originguard_model_api.main:app',
        '--app-dir', (Join-Path $repositoryRoot 'services\model-api\src'),
        '--host', '127.0.0.1', '--port', '8090'
    ) $repositoryRoot
    Wait-ForHttp 'model-api' 'http://127.0.0.1:8090/health' $embedding

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
    $env:QWEN_VL_BASE_URL = 'http://127.0.0.1:8092'
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
    $backend = Start-ManagedProcess 'server' $java @('-jar', $serverJar) $serverDirectory
    Wait-ForHttp 'server' 'http://127.0.0.1:8080/actuator/health' $backend

    $web = Start-ManagedProcess 'web' $node @(
        $viteEntry, '--host', '127.0.0.1', '--port', '5173', '--strictPort'
    ) (Join-Path $repositoryRoot 'apps\web')
    Wait-ForHttp 'web' 'http://127.0.0.1:5173/' $web

    Write-Host ''
    Write-Host 'OriginGuard local stack is ready.' -ForegroundColor Green
    Write-Host '  Web:       http://127.0.0.1:5173'
    Write-Host '  Backend:   http://127.0.0.1:8080'
    Write-Host '  Model API: http://127.0.0.1:8090 (BGE + AIDE + CLIP)'
    Write-Host '  Qwen API:  http://127.0.0.1:8092'
    Write-Host "  Logs:      $logRoot"
    Write-Host 'Stop with: .\scripts\stop-local-stack.ps1'
} catch {
    $startupFailure = $_
    Stop-StartedProcesses
    if ((Get-Command docker -ErrorAction SilentlyContinue) -and (Test-DockerReady)) {
        Push-Location $repositoryRoot
        try {
            try { docker compose stop postgres minio 2>&1 | Out-Null } catch {}
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
