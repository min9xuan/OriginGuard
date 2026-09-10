param(
    [string]$CheckpointPath = '',
    [string]$CheckpointSha256 = '',
    [string]$SourceCommit = '33454cb6d595267033ce1109b62ad373110678db'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeRoot = Join-Path $repositoryRoot '.runtime'
$python = Join-Path $runtimeRoot 'python\Scripts\python.exe'
$sourceRoot = Join-Path $runtimeRoot 'vendor-src\Mesorch'
$modelRoot = Join-Path $runtimeRoot 'models\mesorch'
$checkpointTarget = Join-Path $modelRoot 'mesorch-98.pth'
$cacheRoot = Join-Path $runtimeRoot 'cache'
$tempRoot = Join-Path $cacheRoot 'tmp'
$pipCache = Join-Path $cacheRoot 'pip'

if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw "Project Python runtime is missing: $python"
}
New-Item -ItemType Directory -Force -Path (Split-Path $sourceRoot), $modelRoot, $tempRoot, $pipCache | Out-Null
$env:TEMP = $tempRoot
$env:TMP = $tempRoot
$env:PIP_CACHE_DIR = $pipCache
if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot '.git'))) {
    git clone https://github.com/scu-zjz/Mesorch.git $sourceRoot
    if ($LASTEXITCODE -ne 0) { throw 'Unable to clone the official Mesorch repository.' }
}
git -C $sourceRoot fetch --depth 1 origin $SourceCommit
if ($LASTEXITCODE -ne 0) { throw "Unable to fetch pinned Mesorch commit $SourceCommit." }
git -C $sourceRoot checkout --detach $SourceCommit
if ($LASTEXITCODE -ne 0) { throw "Unable to checkout pinned Mesorch commit $SourceCommit." }
& $python -m pip install -e "$repositoryRoot\services\model-api[mesorch]"
if ($LASTEXITCODE -ne 0) { throw 'Unable to install Mesorch inference dependencies.' }

if ($CheckpointPath) {
    $resolvedCheckpoint = (Resolve-Path -LiteralPath $CheckpointPath).Path
    Copy-Item -LiteralPath $resolvedCheckpoint -Destination $checkpointTarget -Force
}
if (Test-Path -LiteralPath $checkpointTarget -PathType Leaf) {
    $actualSha256 = (Get-FileHash -LiteralPath $checkpointTarget -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($CheckpointSha256 -and $actualSha256 -ne $CheckpointSha256.ToLowerInvariant()) {
        throw "Mesorch checkpoint SHA-256 mismatch. Expected $CheckpointSha256, found $actualSha256."
    }
    Set-Content -LiteralPath "$checkpointTarget.sha256" -Value $actualSha256 -Encoding ascii
}

Write-Host ''
Write-Host 'Mesorch source and dependencies are ready.' -ForegroundColor Green
Write-Host "Source commit: $SourceCommit"
if (Test-Path -LiteralPath $checkpointTarget -PathType Leaf) {
    Write-Host "Checkpoint:    $checkpointTarget"
    Write-Host "SHA-256:       $((Get-Content -LiteralPath "$checkpointTarget.sha256" -Raw).Trim())"
} else {
    Write-Warning 'The official mesorch-98.pth checkpoint is not installed yet.'
    Write-Host 'Download it from the official Mesorch README, then run:'
    Write-Host '.\scripts\setup-mesorch.ps1 -CheckpointPath D:\path\to\mesorch-98.pth'
}
