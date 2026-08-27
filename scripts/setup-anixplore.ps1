param([string]$CheckpointPath = '')

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeRoot = Join-Path $repositoryRoot '.runtime'
$python = Join-Path $runtimeRoot 'python\Scripts\python.exe'
$sourceRoot = Join-Path $runtimeRoot 'vendor-src\AnimeDL2M'
$modelRoot = Join-Path $runtimeRoot 'models\anixplore'
$checkpointTarget = Join-Path $modelRoot 'checkpoint.pth'
$cacheRoot = Join-Path $runtimeRoot 'cache'
$tempRoot = Join-Path $cacheRoot 'tmp'
$pipCache = Join-Path $cacheRoot 'pip'

if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { throw "Project Python runtime is missing: $python" }
New-Item -ItemType Directory -Force -Path (Split-Path $sourceRoot), $modelRoot, $tempRoot, $pipCache | Out-Null
$env:TEMP = $tempRoot
$env:TMP = $tempRoot
$env:PIP_CACHE_DIR = $pipCache
if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot '.git'))) {
    git clone --depth 1 https://github.com/FlyTweety/AnimeDL2M.git $sourceRoot
    if ($LASTEXITCODE -ne 0) { throw 'Unable to clone the official AnimeDL2M repository.' }
}
& $python -m pip install -e "$repositoryRoot\services\model-api[anixplore]"
if ($LASTEXITCODE -ne 0) { throw 'Unable to install illustration detector dependencies.' }
if ($CheckpointPath) {
    $resolvedCheckpoint = (Resolve-Path -LiteralPath $CheckpointPath).Path
    Copy-Item -LiteralPath $resolvedCheckpoint -Destination $checkpointTarget -Force
}
Write-Host ''
Write-Host 'Illustration detector source and dependencies are ready.' -ForegroundColor Green
if (Test-Path -LiteralPath $checkpointTarget -PathType Leaf) {
    Write-Host "Checkpoint: $checkpointTarget"
} else {
    Write-Warning 'The official checkpoint is not installed yet.'
    Write-Host 'Rerun with: .\scripts\setup-anixplore.ps1 -CheckpointPath D:\path\to\checkpoint.pth'
}
