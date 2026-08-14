$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeRoot = Join-Path $repositoryRoot '.runtime'
$python = Join-Path $runtimeRoot 'python\Scripts\python.exe'
$modelPath = Join-Path $runtimeRoot 'models\bge-small-zh-v1.5'

if (-not (Test-Path -LiteralPath $python)) {
    throw "Create the project .runtime Python environment first: $python"
}

$env:HF_HOME = Join-Path $runtimeRoot 'cache\huggingface'
$env:HUGGINGFACE_HUB_CACHE = Join-Path $env:HF_HOME 'hub'
$env:TORCH_HOME = Join-Path $runtimeRoot 'cache\torch'
$env:TEMP = Join-Path $runtimeRoot 'cache\tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $modelPath,$env:HUGGINGFACE_HUB_CACHE,$env:TEMP | Out-Null

$download = @"
from huggingface_hub import snapshot_download
snapshot_download(
    repo_id='BAAI/bge-small-zh-v1.5',
    local_dir=r'$modelPath',
    cache_dir=r'$env:HUGGINGFACE_HUB_CACHE',
    allow_patterns=[
        'config.json', 'model.safetensors', 'tokenizer.json',
        'tokenizer_config.json', 'special_tokens_map.json', 'vocab.txt'
    ],
)
"@
& $python -c $download
