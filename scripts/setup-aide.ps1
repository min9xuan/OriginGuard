param(
    [switch]$SkipDependencies
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeRoot = Join-Path $repositoryRoot '.runtime'
$python = Join-Path $runtimeRoot 'python\Scripts\python.exe'
$sourceRoot = Join-Path $runtimeRoot 'vendor\AIDE'
$checkpointRoot = Join-Path $runtimeRoot 'models\aide'
$checkpoint = Join-Path $checkpointRoot 'GenImage_train.pth'
$officialCommit = '6725b710d5c437ab2f59792908ce0377dfc907de'
$googleDriveFileId = '1ZJCJmzyIrbSOROS7bKTgSm-Fe6yHsVXz'
$expectedCheckpointSha256 = 'f084346dfb0cd05ab4201fe82a62d4f1b4381ee28f2c8295d6cb14d559a9ff16'

if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw "Project Python environment is missing: $python"
}

$env:PIP_CACHE_DIR = Join-Path $runtimeRoot 'cache\pip'
$env:HF_HOME = Join-Path $runtimeRoot 'cache\huggingface'
$env:TORCH_HOME = Join-Path $runtimeRoot 'cache\torch'
$env:TEMP = Join-Path $runtimeRoot 'cache\tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path `
    $env:PIP_CACHE_DIR, $env:HF_HOME, $env:TORCH_HOME, $env:TEMP, `
    (Split-Path $sourceRoot), $checkpointRoot | Out-Null

if (-not $SkipDependencies) {
    & $python -m pip install --cache-dir $env:PIP_CACHE_DIR `
        'gdown>=5.2,<6' 'open-clip-torch==2.24.0' 'openai-clip==1.0.1'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to install AIDE runtime dependencies.' }
}

if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot '.git'))) {
    git clone https://github.com/shilinyan99/AIDE.git $sourceRoot
    if ($LASTEXITCODE -ne 0) { throw 'Unable to clone the official AIDE repository.' }
}
$currentCommit = (& git -C $sourceRoot rev-parse HEAD).Trim()
if ($currentCommit -ne $officialCommit) {
    throw "AIDE source is not the pinned revision. Expected $officialCommit, found $currentCommit."
}

if (-not (Test-Path -LiteralPath $checkpoint -PathType Leaf)) {
    Write-Host 'Downloading the 3.59 GB official GenImage checkpoint to the project runtime...'
    & $python -m gdown $googleDriveFileId -O $checkpoint --continue
    if ($LASTEXITCODE -ne 0) { throw 'Unable to download the official AIDE checkpoint.' }
}

$checkpointFile = Get-Item -LiteralPath $checkpoint
if ($checkpointFile.Length -lt 3500000000) {
    throw "AIDE checkpoint looks incomplete: $($checkpointFile.Length) bytes"
}
$sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $checkpoint).Hash.ToLowerInvariant()
if ($sha256 -ne $expectedCheckpointSha256) {
    throw "AIDE checkpoint SHA-256 mismatch. Expected $expectedCheckpointSha256, found $sha256."
}
Write-Host 'AIDE runtime is ready.' -ForegroundColor Green
Write-Host "  Source:     $sourceRoot"
Write-Host "  Revision:   $officialCommit"
Write-Host "  Checkpoint: $checkpoint"
Write-Host "  Size:       $([Math]::Round($checkpointFile.Length / 1GB, 2)) GiB"
Write-Host "  SHA-256:    $sha256"
