$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$documentation = Join-Path $root "DOCUMENTACAO.md"

if (-not (Test-Path $documentation)) {
    Set-Content -Path $documentation -Encoding UTF8 -Value "# AgendaServer — Documentação consolidada`n"
}

$ignoredFolders = "\\(target|\.git|\.idea|\.settings)\\"
$extraMarkdown = Get-ChildItem -Path $root -Recurse -File -Filter *.md |
    Where-Object { $_.FullName -ne $documentation -and $_.FullName -notmatch $ignoredFolders }

foreach ($file in $extraMarkdown) {
    $relative = $file.FullName.Substring($root.Length).TrimStart('\\')
    Add-Content -Path $documentation -Encoding UTF8 -Value "`n---`n`n## Documento consolidado automaticamente: ``$relative```n"
    Add-Content -Path $documentation -Encoding UTF8 -Value (Get-Content -Path $file.FullName -Raw)
    Remove-Item -Path $file.FullName -Force
}

$legacyText = Get-ChildItem -Path $root -File -Filter "LEIA_PRIMEIRO_*.txt"
foreach ($file in $legacyText) {
    Add-Content -Path $documentation -Encoding UTF8 -Value "`n---`n`n## Instrução legada consolidada: ``$($file.Name)```n"
    Add-Content -Path $documentation -Encoding UTF8 -Value (Get-Content -Path $file.FullName -Raw)
    Remove-Item -Path $file.FullName -Force
}

$legacyScripts = Get-ChildItem -Path $root -File | Where-Object {
    $_.Name -match '^Validar-Projeto-\d+\.cmd$' -or
    $_.Name -match '^Testar-Projeto-\d+\.cmd$' -or
    $_.Name -eq 'Testar.cmd'
}
foreach ($file in $legacyScripts) {
    Remove-Item -Path $file.FullName -Force
}

Write-Host "Limpeza concluída: documentação consolidada e scripts legados removidos." -ForegroundColor Green
