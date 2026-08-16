param(
    [string]$EmbeddingBaseUrl = 'http://127.0.0.1:8090',
    [string]$QwenBaseUrl = 'http://127.0.0.1:8092'
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$serverDirectory = Join-Path $repositoryRoot 'services\server'

if (-not (Test-Path -LiteralPath (Join-Path $serverDirectory 'pom.xml'))) {
    throw "OriginGuard server directory is invalid: $serverDirectory"
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw 'Maven was not found in PATH.'
}

$env:EMBEDDING_PROVIDER = 'bge-small-zh-v1.5'
$env:MODEL_API_BASE_URL = $EmbeddingBaseUrl
$env:AGENT_PLANNER_PROVIDER = 'local-qwen'
$env:QWEN_VL_BASE_URL = $QwenBaseUrl

Write-Host 'Starting OriginGuard backend with:'
Write-Host "  Embedding: $($env:EMBEDDING_PROVIDER) at $EmbeddingBaseUrl"
Write-Host "  Planner:   $($env:AGENT_PLANNER_PROVIDER) at $QwenBaseUrl"

Push-Location $serverDirectory
try {
    & mvn spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "OriginGuard backend exited with code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
