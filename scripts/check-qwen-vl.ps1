param([int]$Port = 8092)

$ErrorActionPreference = 'Stop'
$baseUrl = "http://127.0.0.1:$Port"
$health = Invoke-RestMethod -Uri "$baseUrl/health" -Method Get -TimeoutSec 10
if ($health.status -ne 'ok') {
    throw "Qwen3-VL health check failed: $($health | ConvertTo-Json -Compress)"
}

$body = @{
    model = 'qwen3-vl-4b-instruct-q4-k-m'
    messages = @(@{ role = 'user'; content = 'Return exactly: READY' })
    temperature = 0
    max_tokens = 16
    chat_template_kwargs = @{ enable_thinking = $false }
} | ConvertTo-Json -Depth 8

$response = Invoke-RestMethod `
    -Uri "$baseUrl/v1/chat/completions" `
    -Method Post `
    -ContentType 'application/json' `
    -Body $body `
    -TimeoutSec 300

[pscustomobject]@{
    status = $health.status
    model = $response.model
    response = $response.choices[0].message.content
    usage = $response.usage
} | ConvertTo-Json -Depth 8
