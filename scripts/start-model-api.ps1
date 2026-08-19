$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeRoot = Join-Path $repositoryRoot '.runtime'
$python = Join-Path $runtimeRoot 'python\Scripts\python.exe'
$modelPath = Join-Path $runtimeRoot 'models\bge-small-zh-v1.5'
$aideSourcePath = Join-Path $runtimeRoot 'vendor\AIDE'
$aideCheckpointPath = Join-Path $runtimeRoot 'models\aide\GenImage_train.pth'
$clipModelPath = Join-Path $runtimeRoot 'models\clip\ViT-B-32.pt'

if (-not (Test-Path -LiteralPath $python)) {
    throw "Project Python environment is missing: $python"
}
if (-not (Test-Path -LiteralPath (Join-Path $modelPath 'model.safetensors'))) {
    throw "BGE model is missing: $modelPath"
}
if (-not (Test-Path -LiteralPath (Join-Path $aideSourcePath 'models\AIDE.py'))) {
    throw "AIDE source is missing. Run .\scripts\setup-aide.ps1 first."
}
if (-not (Test-Path -LiteralPath $aideCheckpointPath)) {
    throw "AIDE checkpoint is missing. Run .\scripts\setup-aide.ps1 first."
}
if (-not (Test-Path -LiteralPath $clipModelPath)) {
    throw "CLIP model is missing. Run .\scripts\setup-clip.ps1 first."
}

$env:EMBEDDING_MODEL_PATH = $modelPath
$env:AIDE_SOURCE_PATH = $aideSourcePath
$env:AIDE_CHECKPOINT_PATH = $aideCheckpointPath
$env:AIDE_DEVICE = if ($env:AIDE_DEVICE) { $env:AIDE_DEVICE } else { 'cpu' }
$env:AIDE_PRECISION = if ($env:AIDE_PRECISION) { $env:AIDE_PRECISION } else { 'auto' }
$env:CLIP_MODEL_PATH = $clipModelPath
$env:CLIP_DEVICE = if ($env:CLIP_DEVICE) { $env:CLIP_DEVICE } else { 'cpu' }
$env:HF_HOME = Join-Path $runtimeRoot 'cache\huggingface'
$env:TORCH_HOME = Join-Path $runtimeRoot 'cache\torch'
$env:TEMP = Join-Path $runtimeRoot 'cache\tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null

& $python -m uvicorn originguard_model_api.main:app `
    --app-dir (Join-Path $repositoryRoot 'services\model-api\src') `
    --host 127.0.0.1 `
    --port 8090
