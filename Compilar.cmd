@echo off
setlocal EnableExtensions
cd /d "%~dp0"
for /f %%I in ('powershell.exe -NoProfile -Command "[DateTimeOffset]::Now.ToUnixTimeMilliseconds()"') do set "TASK_START_MS=%%I"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Limpar-Projeto.ps1"
if errorlevel 1 goto :erro

call "%~dp0Validar-Projeto.cmd" /source
if errorlevel 1 goto :erro

set "MAVEN_CMD="
if defined AGENDA_MAVEN_CMD if exist "%AGENDA_MAVEN_CMD%" set "MAVEN_CMD=%AGENDA_MAVEN_CMD%"
if not defined MAVEN_CMD if exist "D:\IDE\apache-maven-3.9.9\bin\mvn.cmd" set "MAVEN_CMD=D:\IDE\apache-maven-3.9.9\bin\mvn.cmd"
if not defined MAVEN_CMD if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MAVEN_CMD=%MAVEN_HOME%\bin\mvn.cmd"
if not defined MAVEN_CMD for /f "delims=" %%M in ('where mvn.cmd 2^>nul') do if not defined MAVEN_CMD set "MAVEN_CMD=%%M"
if not defined MAVEN_CMD (
  echo Maven nao foi encontrado.
  goto :erro
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Verificar-Segredos.ps1"
if errorlevel 1 goto :erro

echo Usando Maven: %MAVEN_CMD%
call "%MAVEN_CMD%" clean package
if errorlevel 1 goto :erro

call "%~dp0Validar-Projeto.cmd"
if errorlevel 1 goto :erro

echo.
echo Gerado: %CD%\target\AgendaServer.jar
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Avisar-Conclusao.ps1" -InicioMs %TASK_START_MS% -Tarefa "Compilacao do AgendaServer"
pause
exit /b 0

:erro
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Avisar-Conclusao.ps1" -InicioMs %TASK_START_MS% -Tarefa "Compilacao do AgendaServer" -Erro
pause
exit /b 1
