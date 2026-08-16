param(
    [int]$Port = 8092,
    [int]$ContextSize = 4096,
    [int]$GpuLayers = 999
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeRoot = Join-Path $repositoryRoot '.runtime'
$server = Join-Path $runtimeRoot 'llama.cpp\bin\llama-server.exe'
$model = Join-Path $runtimeRoot 'models\qwen3-vl-4b-instruct-gguf\Qwen3VL-4B-Instruct-Q4_K_M.gguf'
$mmproj = Join-Path $runtimeRoot 'models\qwen3-vl-4b-instruct-gguf\mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf'

foreach ($required in @($server, $model, $mmproj)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Required local Qwen3-VL file is missing: $required"
    }
}

$env:TEMP = Join-Path $runtimeRoot 'cache\tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null

& $server `
    --model $model `
    --mmproj $mmproj `
    --alias 'qwen3-vl-4b-instruct-q4-k-m' `
    --host 127.0.0.1 `
    --port $Port `
    --ctx-size $ContextSize `
    --batch-size 256 `
    --ubatch-size 128 `
    --parallel 1 `
    --n-gpu-layers $GpuLayers `
    --flash-attn on `
    --jinja
