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

rem Busca externa por IA: raciocínio local no Ollama + busca web oficial do Ollama.
set "AGENDA_AI_SEARCH_ENABLED=true"
set "AGENDA_OLLAMA_BASE_URL=http://localhost:11434"
set "AGENDA_OLLAMA_MODEL="
set "OLLAMA_API_KEY="
rem Fallback opcional. Se OPENAI_API_KEY já estiver preenchida acima, será reutilizada.
set "AGENDA_AI_OPENAI_FALLBACK_ENABLED=false"
set "AGENDA_AI_OPENAI_MODEL=gpt-5.1"
set "AGENDA_AI_FALLBACK_MIN_CANDIDATES=5"
set "AGENDA_AI_MAX_QUERIES=3"
set "AGENDA_AI_MAX_RESULTS_PER_QUERY=6"
set "AGENDA_AI_MAX_PAGES=8"

rem Rodadas de contato: nunca mais de 5 por pedido de uma vez.
set "AGENDA_CONTACT_ROUND_SIZE=5"
set "AGENDA_RESPONSE_BUSINESS_HOURS=2"
set "AGENDA_BUSINESS_START=09:00"
set "AGENDA_BUSINESS_END=18:00"
set "AGENDA_BUSINESS_ZONE=America/Sao_Paulo"
set "AGENDA_GEOCODER_PROVIDER=mock"
set "AGENDA_GEOCODER_URL="
set "AGENDA_GEOCODER_API_KEY="
rem Opcional: se vazio, o servidor cria uma chave persistente fora do Git.
set "AGENDA_PROSPECT_DATA_KEY="
set "AGENDA_PROSPECT_KEY_FILE=%USERPROFILE%\appdata\agenda\prospecting.key"
set "AGENDA_PUBLIC_WEB_URL=https://agendafaz.com.br"
set "AGENDA_SMTP_HOST="
set "AGENDA_SMTP_PORT=587"
set "AGENDA_SMTP_USERNAME="
set "AGENDA_SMTP_PASSWORD="
set "AGENDA_SMTP_FROM="
