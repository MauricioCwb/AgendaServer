@echo off
setlocal EnableExtensions
cd /d "%~dp0"

if exist "%~dp0AgendaServer-Local.cmd" call "%~dp0AgendaServer-Local.cmd"
call "%~dp0Encontrar-PostgreSQL.cmd"
if errorlevel 1 (
  echo.
  echo Nao foi possivel localizar o cliente PostgreSQL psql.exe.
  echo Localize o arquivo no Explorador do Windows e execute novamente.
  pause
  exit /b 1
)

echo Usando PostgreSQL: %PSQL_CMD%
echo.

set "PG_ADMIN_USER=postgres"
set "PG_ADMIN_INPUT="
set /p "PG_ADMIN_INPUT=Usuario administrador do PostgreSQL [postgres]: "
if defined PG_ADMIN_INPUT set "PG_ADMIN_USER=%PG_ADMIN_INPUT%"

set "PG_ADMIN_PASSWORD="
set /p "PG_ADMIN_PASSWORD=Senha do PostgreSQL para %PG_ADMIN_USER%: "
if not defined PG_ADMIN_PASSWORD (
  echo A senha do administrador nao pode ficar vazia.
  pause
  exit /b 1
)

set "AGENDA_DB_PASSWORD="
set /p "AGENDA_DB_PASSWORD=Nova senha para agenda_app: "
if not defined AGENDA_DB_PASSWORD (
  echo A senha de agenda_app nao pode ficar vazia.
  pause
  exit /b 1
)
set "AGENDA_DB_PASSWORD_CONFIRM="
set /p "AGENDA_DB_PASSWORD_CONFIRM=Repita a nova senha: "
if not "%AGENDA_DB_PASSWORD%"=="%AGENDA_DB_PASSWORD_CONFIRM%" (
  echo As senhas informadas nao conferem.
  pause
  exit /b 1
)

echo.
echo Configurando usuario e banco...
set "PGPASSWORD=%PG_ADMIN_PASSWORD%"
"%PSQL_CMD%" -X -v ON_ERROR_STOP=1 -h localhost -p 5432 -U "%PG_ADMIN_USER%" -d postgres -v "agenda_password=%AGENDA_DB_PASSWORD%" -f "%~dp0Configurar-Banco-PostgreSQL.sql"
if errorlevel 1 (
  echo.
  echo Nao foi possivel configurar o banco com o usuario administrador informado.
  echo Verifique a senha do usuario %PG_ADMIN_USER% e se o PostgreSQL esta iniciado.
  set "PGPASSWORD="
  pause
  exit /b 1
)

set "PGPASSWORD=%AGENDA_DB_PASSWORD%"
"%PSQL_CMD%" -X -v ON_ERROR_STOP=1 -h localhost -p 5432 -U agenda_app -d agenda -tAc "SELECT 1" >nul
if errorlevel 1 (
  echo.
  echo O banco foi configurado, mas o teste de agenda_app falhou.
  set "PGPASSWORD="
  pause
  exit /b 1
)

set "PGPASSWORD="
echo.
echo Banco agenda e usuario agenda_app configurados com sucesso.
echo Use essa mesma senha ao executar Iniciar-AgendaServer.cmd.
pause
endlocal
