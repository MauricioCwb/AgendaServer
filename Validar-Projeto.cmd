@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "JAR=target\AgendaServer.jar"
set "FULL_TEST=src\test\java\br\com\mauricio\agendaserver\AgendaFullLifecycleIT.java"
set "SCHEDULING=src\main\java\br\com\mauricio\agendaserver\AgendaSchedulingConfiguration.java"

if not exist "pom.xml" ( echo ERRO: pom.xml ausente. & exit /b 1 )
if not exist "DOCUMENTACAO.md" ( echo ERRO: DOCUMENTACAO.md ausente. & exit /b 1 )
if not exist "%FULL_TEST%" ( echo ERRO: teste completo do ciclo ausente. & exit /b 1 )
if not exist "%SCHEDULING%" ( echo ERRO: configuracao condicional de agendamento ausente. & exit /b 1 )
if not exist "Testar-Fluxo-Completo.cmd" ( echo ERRO: executor do teste completo ausente. & exit /b 1 )

findstr /L /C:"AgendaFullLifecycleIT.java" "pom.xml" >nul || ( echo ERRO: perfil de integracao nao inclui AgendaFullLifecycleIT. & exit /b 1 )
findstr /L /C:"new SpringApplicationBuilder(AgendaServerApplication.class)" "%FULL_TEST%" >nul || ( echo ERRO: teste completo nao inicia o AgendaServer programaticamente. & exit /b 1 )
findstr /L /C:"--server.port=0" "%FULL_TEST%" >nul || ( echo ERRO: teste completo nao usa porta aleatoria. & exit /b 1 )
findstr /L /C:"java.net.http.HttpClient" "%FULL_TEST%" >nul || ( echo ERRO: teste completo nao executa requisicoes HTTP reais. & exit /b 1 )
findstr /L /C:"import org.springframework.boot.test.context.SpringBootTest" "%FULL_TEST%" >nul && ( echo ERRO: teste completo ainda depende de SpringBootTest. & exit /b 1 )
findstr /L /C:"import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc" "%FULL_TEST%" >nul && ( echo ERRO: teste completo ainda depende de MockMvc. & exit /b 1 )
findstr /L /C:"agenda.scheduling.enabled" "%SCHEDULING%" >nul || ( echo ERRO: agendamento nao pode ser desligado no teste. & exit /b 1 )
findstr /L /C:"CONFIRMED" "src\main\java\br\com\mauricio\agendaserver\AgendaMarketplaceService.java" >nul || ( echo ERRO: confirmacao do prestador ausente. & exit /b 1 )
findstr /L /C:"setTaskWindow(connection, taskId," "src\main\java\br\com\mauricio\agendaserver\AgendaMarketplaceService.java" | findstr /L /C:"FILLED" >nul || ( echo ERRO: atividade nao e encerrada ao preencher todas as vagas. & exit /b 1 )
findstr /L /C:"cleanupCreatedRecords" "%FULL_TEST%" >nul || ( echo ERRO: limpeza final do teste ausente. & exit /b 1 )
findstr /L /C:"assertNoTestRecordsRemain" "%FULL_TEST%" >nul || ( echo ERRO: validacao da limpeza ausente. & exit /b 1 )

for /f %%C in ('dir /b /s "*.md" 2^>nul ^| find /c /v ""') do set "MD_COUNT=%%C"
if not "%MD_COUNT%"=="1" ( echo ERRO: devem existir exatamente 1 arquivo .md; encontrados %MD_COUNT%. & exit /b 1 )

for /f %%C in ('dir /b "Validar-Projeto-*.cmd" 2^>nul ^| find /c /v ""') do set "LEGACY_VALIDATORS=%%C"
if not "%LEGACY_VALIDATORS%"=="0" ( echo ERRO: ainda existem validadores antigos por versao. & exit /b 1 )

if exist "Testar.cmd" ( echo ERRO: o script generico Testar.cmd deveria ter sido removido. & exit /b 1 )

if /I "%~1"=="/source" goto success
if not exist "%JAR%" ( echo ERRO: JAR ausente. & exit /b 1 )
jar tf "%JAR%" | findstr /L /C:"BOOT-INF/classes/br/com/mauricio/agendaserver/AgendaSchedulingConfiguration.class" >nul || ( echo ERRO: configuracao de agendamento nao entrou no JAR. & exit /b 1 )

:success
echo Validacao do AgendaServer 019 concluida com sucesso.
exit /b 0
