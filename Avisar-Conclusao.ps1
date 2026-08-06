param(
    [Parameter(Mandatory = $true)]
    [long]$InicioMs,
    [string]$Tarefa = "Tarefa",
    [switch]$Erro
)

$agoraMs = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
$decorrido = [TimeSpan]::FromMilliseconds([Math]::Max(0, $agoraMs - $InicioMs))
$tempo = "{0:00}:{1:00}:{2:00}" -f [Math]::Floor($decorrido.TotalHours), $decorrido.Minutes, $decorrido.Seconds

if ($Erro) {
    Write-Host "`n[ERRO] $Tarefa" -ForegroundColor Red
    Write-Host "Tempo total: $tempo" -ForegroundColor Yellow
    try { [Console]::Beep(330, 300); Start-Sleep -Milliseconds 100; [Console]::Beep(220, 450) } catch {}
    exit 1
}

Write-Host "`n[CONCLUIDO] $Tarefa" -ForegroundColor Green
Write-Host "Tempo total: $tempo" -ForegroundColor Cyan
try { [Console]::Beep(880, 180); Start-Sleep -Milliseconds 80; [Console]::Beep(1175, 260) } catch {}
