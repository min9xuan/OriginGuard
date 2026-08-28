param([string]$Version = 'c2patool-v0.27.15')

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolRoot = Join-Path $repositoryRoot '.runtime\tools\c2patool'
$archive = Join-Path $toolRoot 'c2patool.zip'

New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
$assetName = "$Version-x86_64-pc-windows-msvc.zip"
$downloadUrl = "https://github.com/contentauth/c2pa-rs/releases/download/$Version/$assetName"
Invoke-WebRequest -Uri $downloadUrl -OutFile $archive
Expand-Archive -LiteralPath $archive -DestinationPath $toolRoot -Force
Remove-Item -LiteralPath $archive -Force
$binary = Get-ChildItem -LiteralPath $toolRoot -Filter c2patool.exe -Recurse | Select-Object -First 1
if (-not $binary) { throw 'c2patool.exe was not found in the downloaded archive.' }
if ($binary.DirectoryName -ne $toolRoot) {
    Copy-Item -LiteralPath $binary.FullName -Destination (Join-Path $toolRoot 'c2patool.exe') -Force
}
& (Join-Path $toolRoot 'c2patool.exe') --version
Write-Host "C2PA verifier installed at $toolRoot" -ForegroundColor Green
