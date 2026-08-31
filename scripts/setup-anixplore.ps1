param(
    [string]$CheckpointPath = '',
    [string]$CheckpointSha256 = '',
    [string]$SourceCommit = 'bd81880df8577aa3d7ae746e1b492bc837d3a77d'
)

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
git -C $sourceRoot fetch --depth 1 origin $SourceCommit
if ($LASTEXITCODE -ne 0) { throw "Unable to fetch pinned AnimeDL-2M source commit $SourceCommit." }
git -C $sourceRoot checkout --detach $SourceCommit
if ($LASTEXITCODE -ne 0) { throw "Unable to checkout pinned AnimeDL-2M source commit $SourceCommit." }
& $python -m pip install -e "$repositoryRoot\services\model-api[anixplore]"
if ($LASTEXITCODE -ne 0) { throw 'Unable to install illustration detector dependencies.' }
if ($CheckpointPath) {
    $resolvedCheckpoint = (Resolve-Path -LiteralPath $CheckpointPath).Path
    Copy-Item -LiteralPath $resolvedCheckpoint -Destination $checkpointTarget -Force
}
if (Test-Path -LiteralPath $checkpointTarget -PathType Leaf) {
    $actualSha256 = (Get-FileHash -LiteralPath $checkpointTarget -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($CheckpointSha256 -and $actualSha256 -ne $CheckpointSha256.ToLowerInvariant()) {
        throw "AniXplore checkpoint SHA-256 mismatch. Expected $CheckpointSha256, found $actualSha256."
    }
    Set-Content -LiteralPath "$checkpointTarget.sha256" -Value $actualSha256 -Encoding ascii
}
Write-Host ''
Write-Host 'Illustration detector source and dependencies are ready.' -ForegroundColor Green
if (Test-Path -LiteralPath $checkpointTarget -PathType Leaf) {
    Write-Host "Checkpoint: $checkpointTarget"
    Write-Host "SHA-256:   $((Get-Content -LiteralPath "$checkpointTarget.sha256" -Raw).Trim())"
    Write-Host "Source:    $SourceCommit"
} else {
    Write-Warning 'The official checkpoint is not installed yet.'
    Write-Host 'Rerun with: .\scripts\setup-anixplore.ps1 -CheckpointPath D:\path\to\checkpoint.pth'
}
