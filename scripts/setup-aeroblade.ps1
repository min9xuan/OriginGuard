param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeRoot = Join-Path $repositoryRoot '.runtime'
$python = Join-Path $runtimeRoot 'python\Scripts\python.exe'
$sourceRoot = Join-Path $runtimeRoot 'vendor-src\aeroblade'
$modelRoot = Join-Path $runtimeRoot 'models\aeroblade'
$readyMarker = Join-Path $modelRoot 'ready'
$cacheRoot = Join-Path $runtimeRoot 'cache'
$tempRoot = Join-Path $cacheRoot 'tmp'
$pipCache = Join-Path $cacheRoot 'pip'

if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { throw "Project Python runtime is missing: $python" }
New-Item -ItemType Directory -Force -Path (Split-Path $sourceRoot), $modelRoot, $tempRoot, $pipCache | Out-Null
$env:TEMP = $tempRoot
$env:TMP = $tempRoot
$env:PIP_CACHE_DIR = $pipCache
$env:HF_HOME = Join-Path $cacheRoot 'huggingface'
$env:TORCH_HOME = Join-Path $cacheRoot 'torch'
if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot '.git'))) {
    git clone --depth 1 https://github.com/jonasricker/aeroblade.git $sourceRoot
    if ($LASTEXITCODE -ne 0) { throw 'Unable to clone the official diffusion verifier repository.' }
}
& $python -m pip install -e "$repositoryRoot\services\model-api[aeroblade]"
if ($LASTEXITCODE -ne 0) { throw 'Unable to install diffusion verifier dependencies.' }
Remove-Item -LiteralPath $readyMarker -Force -ErrorAction SilentlyContinue
& $python -c "from pathlib import Path; from originguard_model_api.diffusion_verifier import LocalDiffusionVerifier; root=Path(r'$repositoryRoot'); verifier=LocalDiffusionVerifier(root, root/'.runtime'); verifier.warm_up(); print('diffusion verifier assets ready on', verifier._device)"
if ($LASTEXITCODE -ne 0) { throw 'Diffusion verifier asset warm-up failed; capability remains disabled.' }
Set-Content -LiteralPath $readyMarker -Value 'ready' -Encoding UTF8
Write-Host ''
Write-Host 'Diffusion reconstruction verifier is ready.' -ForegroundColor Green
Write-Host 'Autoencoder and LPIPS weights are stored under .runtime/cache on D drive.'
Write-Host 'A calibrated distance threshold is intentionally not configured by this script.'
