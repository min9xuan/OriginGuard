$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeRoot = Join-Path $repositoryRoot '.runtime'
$python = Join-Path $runtimeRoot 'python\Scripts\python.exe'
$modelPath = Join-Path $runtimeRoot 'models\bge-small-zh-v1.5'

if (-not (Test-Path -LiteralPath $python)) {
    throw "Project Python environment is missing: $python"
}
if (-not (Test-Path -LiteralPath (Join-Path $modelPath 'model.safetensors'))) {
    throw "BGE model is missing: $modelPath"
}

$env:EMBEDDING_MODEL_PATH = $modelPath
$env:HF_HOME = Join-Path $runtimeRoot 'cache\huggingface'
$env:TORCH_HOME = Join-Path $runtimeRoot 'cache\torch'
$env:TEMP = Join-Path $runtimeRoot 'cache\tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null

& $python -m uvicorn originguard_model_api.main:app `
    --app-dir (Join-Path $repositoryRoot 'services\model-api\src') `
    --host 127.0.0.1 `
    --port 8090
