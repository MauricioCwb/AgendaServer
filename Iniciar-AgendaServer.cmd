@echo off
setlocal EnableExtensions
cd /d "%~dp0"
for /f %%I in ('powershell.exe -NoProfile -Command "[DateTimeOffset]::Now.ToUnixTimeMilliseconds()"') do set "TASK_START_MS=%%I"

rem Compatibilidade com execucao por script. O Spring tambem carrega
rem AgendaServer-Database.properties diretamente quando iniciado pelo Eclipse.
if exist "%~dp0AgendaServer-Database.cmd" call "%~dp0AgendaServer-Database.cmd"
if exist "%~dp0AgendaServer-Local.cmd" call "%~dp0AgendaServer-Local.cmd"
if not defined AGENDA_SERVER_PORT set "AGENDA_SERVER_PORT=28212"

if not exist "target\AgendaServer.jar" (
  echo target\AgendaServer.jar nao encontrado. Execute Compilar.cmd primeiro.
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Avisar-Conclusao.ps1" -InicioMs %TASK_START_MS% -Tarefa "Inicializacao do AgendaServer" -Erro
  pause
  exit /b 1
)

java -jar "target\AgendaServer.jar"
set "JAVA_EXIT=%ERRORLEVEL%"
if not "%JAVA_EXIT%"=="0" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Avisar-Conclusao.ps1" -InicioMs %TASK_START_MS% -Tarefa "Execucao do AgendaServer" -Erro
  pause
  exit /b %JAVA_EXIT%
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Avisar-Conclusao.ps1" -InicioMs %TASK_START_MS% -Tarefa "Execucao do AgendaServer"
endlocal
