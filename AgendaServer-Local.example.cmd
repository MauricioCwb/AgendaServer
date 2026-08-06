@echo off
rem Copie este arquivo para AgendaServer-Local.cmd. O arquivo real e ignorado pelo Git.
rem A senha do banco e carregada por AgendaServer-Database.cmd.
set "OPENAI_API_KEY=SUBSTITUA_LOCALMENTE"
set "AGENDA_VISION_MODEL=gpt-5.1"

rem Prospecção externa: envio real permanece bloqueado por padrão.
set "PRODUCAO=false"
set "AGENDA_PROSPECTING_ENABLED=true"
set "AGENDA_PROSPECTING_DRY_RUN=true"
set "AGENDA_AUTOMATIC_DRY_RUN_ENABLED=true"
set "AGENDA_EMAIL_SENDING_ENABLED=false"
set "AGENDA_CNPJ_IMPORT_DIR=C:\Dados\ReceitaCNPJ"
set "AGENDA_GEOCODER_PROVIDER=mock"
set "AGENDA_GEOCODER_URL="
set "AGENDA_GEOCODER_API_KEY="
rem Opcional: se vazio, o servidor cria uma chave persistente fora do Git.
set "AGENDA_PROSPECT_DATA_KEY="
set "AGENDA_PROSPECT_KEY_FILE=%USERPROFILE%\appdata\agenda\prospecting.key"
set "AGENDA_PUBLIC_WEB_URL=http://127.0.0.1:5500"
set "AGENDA_SMTP_HOST="
set "AGENDA_SMTP_PORT=587"
set "AGENDA_SMTP_USERNAME="
set "AGENDA_SMTP_PASSWORD="
set "AGENDA_SMTP_FROM="
