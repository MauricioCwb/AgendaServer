@echo off
rem Este arquivo exporta PSQL_CMD e AGENDA_PSQL_CMD para o script chamador.
rem Nao usar SETLOCAL aqui.

set "PSQL_CMD="

if defined AGENDA_PSQL_CMD (
  call :AceitarCandidato "%AGENDA_PSQL_CMD%"
)

if not defined PSQL_CMD (
  for /f "delims=" %%P in ('where psql.exe 2^>nul') do (
    if not defined PSQL_CMD call :AceitarCandidato "%%P"
  )
)

if not defined PSQL_CMD call :ProcurarRaiz "C:\IDE\PostgreSQL"
if not defined PSQL_CMD call :ProcurarRaiz "D:\IDE\PostgreSQL"
if not defined PSQL_CMD call :ProcurarRaiz "%ProgramFiles%\PostgreSQL"
if not defined PSQL_CMD call :ProcurarRaiz "%ProgramFiles(x86)%\PostgreSQL"
if not defined PSQL_CMD call :ProcurarRaiz "C:\PostgreSQL"
if not defined PSQL_CMD call :ProcurarRaiz "D:\PostgreSQL"

if defined PSQL_CMD goto :Encontrado

if /I "%~1"=="/noprompt" exit /b 1

echo PostgreSQL psql.exe nao foi localizado automaticamente.
echo.
echo Foram pesquisados o PATH e estas pastas:
echo   C:\IDE\PostgreSQL
echo   D:\IDE\PostgreSQL
echo   %ProgramFiles%\PostgreSQL
echo   %ProgramFiles(x86)%\PostgreSQL
echo   C:\PostgreSQL
echo   D:\PostgreSQL
echo.
echo Voce pode informar o arquivo psql.exe ou a pasta de instalacao do PostgreSQL.
set "PSQL_INPUT="
set /p "PSQL_INPUT=Caminho do PostgreSQL ou do psql.exe: "
if not defined PSQL_INPUT exit /b 1
set "PSQL_INPUT=%PSQL_INPUT:"=%"
call :AceitarCandidato "%PSQL_INPUT%"
if not defined PSQL_CMD call :AceitarCandidato "%PSQL_INPUT%\bin\psql.exe"
if not defined PSQL_CMD (
  echo.
  echo psql.exe nao encontrado no caminho informado:
  echo %PSQL_INPUT%
  exit /b 1
)

:Encontrado
set "AGENDA_PSQL_CMD=%PSQL_CMD%"
> "%~dp0AgendaServer-Local.cmd" echo @echo off
>>"%~dp0AgendaServer-Local.cmd" echo set "AGENDA_PSQL_CMD=%AGENDA_PSQL_CMD%"
exit /b 0

:AceitarCandidato
if defined PSQL_CMD exit /b 0
if exist "%~1" (
  for %%F in ("%~1") do set "PSQL_CMD=%%~fF"
)
exit /b 0

:ProcurarRaiz
if defined PSQL_CMD exit /b 0
if not exist "%~1" exit /b 0
for /f "delims=" %%P in ('dir /b /s /a-d "%~1\psql.exe" 2^>nul') do (
  if not defined PSQL_CMD call :AceitarCandidato "%%P"
)
exit /b 0
