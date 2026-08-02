$ErrorActionPreference = 'Stop'

Write-Host 'OriginGuard bootstrap intentionally does not install dependencies automatically.'
Write-Host 'Review README.md and confirm D-drive cache locations before running npm, pip, Maven, or Docker pulls.'
Write-Host 'Detected tools:'
Get-Command git, node, npm, python, java, docker, mvn -ErrorAction SilentlyContinue |
    Select-Object Name, Source |
    Format-Table -AutoSize

