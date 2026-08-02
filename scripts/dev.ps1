$ErrorActionPreference = 'Stop'

Write-Host 'Start each service in a separate terminal after dependencies are installed:'
Write-Host '  Web:       cd apps/web; npm run dev'
Write-Host '  Server:    cd services/server; mvn spring-boot:run'
Write-Host '  Model API: cd services/model-api; python -m uvicorn originguard_model_api.main:app --app-dir src --reload --port 8090'

