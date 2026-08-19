param([switch]$Force)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeRoot = Join-Path $repositoryRoot '.runtime'
$python = Join-Path $runtimeRoot 'python\Scripts\python.exe'
$modelDirectory = Join-Path $runtimeRoot 'models\clip'
$modelPath = Join-Path $modelDirectory 'ViT-B-32.pt'

if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw "Project Python environment is missing: $python"
}
if ((Test-Path -LiteralPath $modelPath -PathType Leaf) -and -not $Force) {
    Write-Host "OpenAI CLIP ViT-B/32 is already available: $modelPath" -ForegroundColor Green
    exit 0
}

New-Item -ItemType Directory -Force -Path $modelDirectory, (Join-Path $runtimeRoot 'cache\torch') | Out-Null
$env:CLIP_DOWNLOAD_ROOT = $modelDirectory
$env:TORCH_HOME = Join-Path $runtimeRoot 'cache\torch'
$env:TEMP = Join-Path $runtimeRoot 'cache\tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null

Write-Host 'Downloading official OpenAI CLIP ViT-B/32 weights to the project D-drive runtime...'
& $python -c "import os, clip; clip.load('ViT-B/32', device='cpu', jit=False, download_root=os.environ['CLIP_DOWNLOAD_ROOT']); print('CLIP model verified')"
if ($LASTEXITCODE -ne 0) { throw "CLIP setup exited with code $LASTEXITCODE" }
if (-not (Test-Path -LiteralPath $modelPath -PathType Leaf)) {
    throw "CLIP download completed without the expected file: $modelPath"
}

$hash = (Get-FileHash -LiteralPath $modelPath -Algorithm SHA256).Hash.ToLowerInvariant()
$sizeMb = [math]::Round((Get-Item -LiteralPath $modelPath).Length / 1MB, 1)
Write-Host "OpenAI CLIP ViT-B/32 is ready ($sizeMb MB, SHA-256 $hash)." -ForegroundColor Green
Write-Host $modelPath
