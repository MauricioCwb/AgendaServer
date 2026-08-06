@echo off
setlocal EnableExtensions
cd /d "%~dp0"
for /f %%I in ('powershell.exe -NoProfile -Command "[DateTimeOffset]::Now.ToUnixTimeMilliseconds()"') do set "TASK_START_MS=%%I"

echo ================================================================
echo TESTE COMPLETO DO CICLO AGENDAJA
echo ================================================================
echo Este teste usa o PostgreSQL local configurado para o AgendaServer.
echo Ele cria duas contas temporarias, publica uma atividade, registra a
echo proposta do prestador, aprova, confirma e apaga tudo ao terminar.
echo Nenhum e-mail e enviado e os agendadores ficam desligados no teste.
echo.

powershell.exe -NoProfile -Command "if (Get-NetTCPConnection -LocalPort 28212 -State Listen -ErrorAction SilentlyContinue) { exit 1 }"
if errorlevel 1 (
  echo O AgendaServer esta rodando na porta 28212.
  echo Encerre o servidor antes de executar o teste completo para impedir
  echo que o worker externo processe os registros temporarios do teste.
  goto :erro
)

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

call "%MAVEN_CMD%" clean -Pintegration-test -Dit.test=AgendaFullLifecycleIT verify
if errorlevel 1 goto :erro

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Avisar-Conclusao.ps1" -InicioMs %TASK_START_MS% -Tarefa "Teste completo consumidor-prestador"
pause
exit /b 0

:erro
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Avisar-Conclusao.ps1" -InicioMs %TASK_START_MS% -Tarefa "Teste completo consumidor-prestador" -Erro
pause
exit /b 1
