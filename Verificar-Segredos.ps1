$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$extensions = @("*.java", "*.properties", "*.yml", "*.yaml", "*.xml", "*.md", "*.cmd", "*.bat", "*.ps1", "*.sql")
$files = Get-ChildItem -Path $root -Recurse -File -Include $extensions |
    Where-Object { $_.FullName -notmatch "\\target\\|\\.git\\|AgendaServer-Local\.cmd$|AgendaServer-Database\.cmd$|AgendaServer-Database\.properties$" }

$violations = @()
foreach ($file in $files) {
    $matches = Select-String -Path $file.FullName -Pattern 'sk-(proj-)?[A-Za-z0-9_-]{20,}' -AllMatches
    foreach ($match in $matches) {
        $violations += "$($file.FullName):$($match.LineNumber) contem uma possivel chave OpenAI."
    }
}

$properties = Join-Path $root "src\main\resources\application.properties"
if (Test-Path $properties) {
    $unsafe = Select-String -Path $properties -Pattern '^\s*(spring\.datasource\.password|assistant\.openai\.api-key|agenda\.geocoder\.api-key|agenda\.smtp\.password|agenda\.prospecting\.data-key)\s*=\s*(?!\$\{).+' -AllMatches
    foreach ($match in $unsafe) {
        $violations += "${properties}:$($match.LineNumber) contem segredo literal em propriedade sensivel."
    }
}

if ($violations.Count -gt 0) {
    Write-Host "Falha: possiveis segredos encontrados:" -ForegroundColor Red
    $violations | ForEach-Object { Write-Host " - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "Verificacao de segredos concluida sem ocorrencias." -ForegroundColor Green
