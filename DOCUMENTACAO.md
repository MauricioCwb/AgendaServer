# AgendaServer — Documentação consolidada

## AgendaServer 018 — local negociável e privacidade

- Versão interna: `1.1.13`.
- O prestador pode aceitar o local original ou sugerir atendimento no próprio local.
- Antes da aprovação, a API informa somente a distância e não envia coordenadas exatas para a outra parte.
- O contratante pode aceitar a sugestão ou aprovar o prestador mantendo o local original.
- Somente após a aprovação o ponto exato do local combinado é liberado para quem precisa se deslocar.
- O teste de ciclo completo possui dois casos independentes: atendimento no local do prestador e atendimento no local original do solicitante após a sugestão do prestador ser recusada.

## AgendaServer 017

- Versão interna: `1.1.10`.
- Corrigido o teste `AgendaFullLifecycleIT` para usar uma biografia compatível com o limite de 20 caracteres do plano inicial.
- A limpeza final agora valida diretamente, pelos IDs criados, a ausência de contas, usuários, sessões, especialidades do perfil, notificações, tarefas, candidaturas, jobs e logs.
- Os avisos de detecção de configuração do Spring e de autoanexação do Mockito não representam falha do teste.


## AgendaServer 016

- Versão interna: `1.1.9`.
- Criado o teste de integração real `AgendaFullLifecycleIT`, executado contra o PostgreSQL configurado do projeto.
- O teste cria consumidor e prestador temporários, abre as duas sessões, associa a especialidade do prestador, publica uma atividade, registra candidatura, aprova a proposta, confirma a participação e valida as tabelas pertinentes.
- Ao final, mesmo quando ocorre falha intermediária, o teste remove a atividade e as duas contas temporárias; as exclusões em cascata removem sessões, perfis, especialidade do perfil, candidatura, job de prospecção, logs e notificações.
- O status da atividade passa para `FILLED` quando o prestador aprovado confirma e todas as vagas são preenchidas.
- O teste completo desliga apenas os agendadores durante a execução e mantém o envio de e-mail bloqueado.
- Execute com `Testar-Fluxo-Completo.cmd`. Os testes unitários normais continuam em `Testar.cmd`.
- Toda a documentação permanece em um único arquivo: `DOCUMENTACAO.md`.
- Os validadores antigos por versão e os arquivos `LEIA_PRIMEIRO_*.txt` foram removidos.
- `Limpar-Projeto.ps1` consolida automaticamente documentos Markdown residuais e apaga scripts legados de validação ao compilar ou testar.

### Tabelas verificadas pelo teste completo

- `agenda_accounts`;
- `agenda_sessions`;
- `agenda_users`;
- `agenda_user_specialties`;
- `agenda_tasks`;
- `agenda_candidates`;
- `agenda_notifications`;
- `agenda_prospecting_jobs`;
- `agenda_prospecting_process_logs`.

### Fases verificadas

1. cadastro e sessão do consumidor;
2. cadastro e sessão do prestador;
3. definição da especialidade única do prestador;
4. publicação da atividade;
5. visibilidade da atividade para o prestador;
6. candidatura em estado `PENDING`;
7. aprovação em estado `APPROVED`;
8. confirmação em estado `CONFIRMED`;
9. fechamento da atividade em `FILLED`;
10. limpeza e confirmação de ausência de resíduos.


Documento único do projeto. Consolida arquitetura, API, histórico de versões, correções, validações, segurança, execução e arquivos alterados.

## AgendaServer 011

- Versão interna: `1.1.4`.
- O Spring importa `AgendaServer-Database.properties`, inclusive quando iniciado diretamente pelo Eclipse.
- A senha local do PostgreSQL já está preenchida e não é solicitada.
- `AgendaServer-Database.properties` e `AgendaServer-Database.cmd` permanecem ignorados pelo Git.
- Os metadados Eclipse/Maven continuam completos; não é necessário reimportar o projeto.
- Todos os documentos Markdown anteriores foram consolidados neste arquivo.
- Os scripts de compilação, testes e execução emitem aviso sonoro e mostram o tempo decorrido ao terminar.

## Arquivos alterados ou criados na versão 011

- `src/main/resources/application.properties`
- `AgendaServer-Database.properties`
- `AgendaServer-Database.cmd`
- `.gitignore`
- `pom.xml`
- `Verificar-Segredos.ps1`
- `Compilar.cmd`
- `Testar.cmd`
- `Iniciar-AgendaServer.cmd`
- `Avisar-Conclusao.ps1`
- `Validar-Projeto-011.cmd`
- `DOCUMENTACAO.md`

---

# Conteúdo histórico consolidado


---

## Documento original: `README.md`

# AgendaServer 1.1.2 — prospecção automática em modo seguro

Servidor independente do AgendaJá em **Java 21**, **Spring Boot 3.4.7**, JDBC, Flyway e **PostgreSQL**. A versão 008 consolida especialidades estruturadas e um processo determinístico para localizar, simular e, somente quando expressamente autorizado, convidar estabelecimentos públicos do CNPJ para conhecer uma demanda real.


## Diretório oficial desta entrega

Instale este projeto em:

```text
C:\Projetos\AgendaServer
```

O arquivo abaixo é obrigatório e contém `GET /api/agenda/specialties`:

```text
src\main\java\br\com\mauricio\agendaserver\ProspectingController.java
```

O script `Validar-Projeto-008.cmd` verifica o fonte e, depois da compilação, confirma que a classe entrou em `target\AgendaServer.jar`.

## Princípios de segurança

- O processo de prospecção **não usa inteligência artificial**.
- O envio real começa desativado: `PRODUCAO=false`, `AGENDA_PROSPECTING_ENABLED=false` e `AGENDA_PROSPECTING_DRY_RUN=true`.
- Nenhuma conta, perfil ou candidatura é criada por convite.
- O contato só é associado à demanda se a pessoa acessar o link, usar o mesmo e-mail e concluir voluntariamente o cadastro/login.
- O convite não informa endereço exato nem dados pessoais do consumidor.
- Descadastro é permanente, não exige login e somente altera estado por `POST` após confirmação.
- E-mails são normalizados, armazenados como hash para comparação e criptografados com AES-256-GCM para envio.
- Chaves, senhas e tokens completos não podem ser gravados no Git, em logs ou em respostas da API.

## Arquitetura

1. O consumidor publica uma tarefa com uma especialidade obrigatória.
2. O servidor registra um `agenda_prospecting_jobs` sem bloquear a publicação.
3. O fluxo interno de favoritos e candidatos continua prioritário.
4. No piloto, o administrador executa **Simular busca de prestadores**.
5. O job filtra estabelecimentos ativos importados da Receita por município, CNAE, e-mail, endereço, supressão, cadastro interno, convite anterior e intervalo mínimo.
6. Endereços elegíveis são geocodificados por uma abstração configurável e armazenados em cache por hash.
7. O servidor calcula a distância por Haversine e seleciona no máximo 20 contatos, deduplicados por e-mail.
8. Em dry-run, apenas a prévia protegida é criada; nenhum e-mail é enviado.
9. O envio real exige simultaneamente produção, recurso habilitado, dry-run desativado, SMTP configurado e autorização administrativa da tarefa.
10. A cada envio o servidor confirma que a tarefa continua aberta e sem vagas preenchidas.

## Banco e migrações

O Flyway executa automaticamente:

- `V1__agenda_schema.sql` — domínio original do Agenda;
- `V2__normalize_account_email.sql` — normalização de contas;
- `V3__specialties_and_external_prospecting.sql` — especialidades, CNAEs, importações, prospects, cache de geocodificação, jobs, convites, supressões e configurações.

A V3 preserva tarefas existentes associando-as à especialidade padrão `Serviços gerais`. Nenhuma tabela anterior é apagada.

Principais tabelas novas:

- `agenda_specialties` e `agenda_specialty_cnaes`;
- `agenda_user_specialties`;
- `agenda_cnpj_import_runs` e `agenda_import_rejections`;
- `agenda_cnpj_prospects` e `agenda_cnpj_prospect_cnaes`;
- `agenda_geocoding_cache`;
- `agenda_prospecting_jobs`;
- `agenda_external_invitations`;
- `agenda_email_suppressions`;
- `agenda_prospecting_settings`.

## Preparação local

1. Instale Java 21, Maven e PostgreSQL.
2. Crie/configure o banco com `Configurar-Banco-PostgreSQL.cmd`.
3. Copie `AgendaServer-Local.example.cmd` para `AgendaServer-Local.cmd`.
4. Preencha apenas no arquivo local, que está no `.gitignore`.
5. Compile com `Compilar.cmd`.
6. Inicie com `Iniciar-AgendaServer.cmd`.

Endpoint de saúde:

```text
http://127.0.0.1:28212/api/agenda/health
```

## Variáveis de ambiente

### Banco e servidor

| Variável | Padrão | Uso |
|---|---:|---|
| `AGENDA_SERVER_PORT` | `28212` | Porta HTTP |
| `AGENDA_DB_URL` | `jdbc:postgresql://localhost:5432/agenda` | JDBC PostgreSQL |
| `AGENDA_DB_USER` | `agenda_app` | Usuário do banco |
| `AGENDA_DB_PASSWORD` | vazio | Senha local, obrigatória |
| `AGENDA_UPLOAD_DIR` | pasta do usuário | Fotos e vídeos |
| `AGENDA_ADMIN_EMAILS` | `lixocwb@gmail.com` | Administradores separados por vírgula |

### Prospecção e limites

| Variável | Padrão |
|---|---:|
| `PRODUCAO` | `false` |
| `AGENDA_PROSPECTING_ENABLED` | `false` |
| `AGENDA_PROSPECTING_DRY_RUN` | `true` |
| `AGENDA_EXTERNAL_INVITE_RADIUS_KM` | `2` |
| `AGENDA_EXTERNAL_INVITE_LIMIT_PER_TASK` | `20` |
| `AGENDA_EXTERNAL_INVITE_DAILY_LIMIT` | `5` |
| `AGENDA_EXTERNAL_INVITE_COOLDOWN_DAYS` | `90` |
| `AGENDA_EXTERNAL_INVITE_TOKEN_HOURS` | `72` |
| `AGENDA_PROSPECT_DATA_KEY` | vazio |
| `AGENDA_PUBLIC_WEB_URL` | `https://agendafaz.com.br` |

`AGENDA_PROSPECT_DATA_KEY` deve ser Base64 de exatamente 32 bytes. Gere localmente no PowerShell:

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

Não publique o resultado.

### Importação do CNPJ

| Variável | Padrão |
|---|---:|
| `AGENDA_CNPJ_IMPORT_DIR` | vazio |
| `AGENDA_EMAIL_CHECK_MX` | `false` |
| `AGENDA_EMAIL_REPEATED_THRESHOLD` | `20` |

O diretório deve conter os arquivos oficiais compactados da Receita, em formato semicolon/ISO-8859-1:

```text
Municipios*.zip
Estabelecimentos*.zip
```

O importador lê os ZIPs em streaming, em lotes de 500 registros. Não carrega um arquivo de estabelecimentos inteiro em memória. No primeiro piloto, configure `SOROCABA/SP` na tela administrativa.

A ordem de descarte ocorre antes da geocodificação:

1. situação cadastral ativa (`02`);
2. município piloto;
3. CNAE associado a especialidade ativa;
4. e-mail sintática e tecnicamente aceitável;
5. endereço e CEP mínimos;
6. CNPJ válido em formato normalizado.

Registros repetidos são atualizados por CNPJ. Estabelecimentos de fontes anteriores que não aparecem na importação completa mais recente do município piloto são desativados, sem apagar históricos ou supressões.

### Geocodificação

| Variável | Padrão |
|---|---:|
| `AGENDA_GEOCODER_PROVIDER` | `mock` |
| `AGENDA_GEOCODER_URL` | vazio |
| `AGENDA_GEOCODER_API_KEY` | vazio |

O provedor `mock` é determinístico e serve apenas para desenvolvimento e testes. **Não habilite envio real com ele.**

Para um provedor HTTP, configure uma URL que aceite `{address}` ou o parâmetro `address`. A resposta deve conter `latitude`/`longitude` ou `lat`/`lon`, além de `confidence` e `precision`/`type`. A chave é enviada por `Authorization: Bearer` e `X-Api-Key` para compatibilidade com provedores contratados.

Não use Google Maps/Places como fonte de prestadores. O endereço público `nominatim.openstreetmap.org` é bloqueado pelo servidor; utilize serviço contratado, instância própria do Nominatim ou outro provedor permitido. O envio real também exige um geocodificador de produção configurado.

A confiança mínima, o modo de acionamento e os municípios piloto são editáveis somente pelo administrador no AgendaWeb. Somente resultados com precisão de endereço, imóvel, rua, parcela ou ponto equivalente são aceitos. Resultados de bairro genérico, município, estado, região ou país são rejeitados. O cache é indexado pelo hash do endereço normalizado. Resultados válidos são reutilizados enquanto o endereço não mudar; resultados inválidos ou insuficientes podem ser tentados novamente.

### SMTP

| Variável | Padrão |
|---|---:|
| `AGENDA_SMTP_HOST` | vazio |
| `AGENDA_SMTP_PORT` | `587` |
| `AGENDA_SMTP_USERNAME` | vazio |
| `AGENDA_SMTP_PASSWORD` | vazio |
| `AGENDA_SMTP_FROM` | vazio |

Antes de qualquer envio real:

- configure SPF, DKIM e DMARC;
- confirme domínio e remetente;
- teste bounce/falha temporária;
- teste o descadastro por POST;
- revise a lista permanente de supressão;
- mantenha limites de envio conservadores.

## Operação administrativa

No AgendaWeb, entre com `lixocwb@gmail.com` e abra **Administração**.

1. Cadastre especialidades.
2. Relacione CNAEs e defina se o match vale para CNAE principal, secundário ou ambos.
3. Confirme municípios piloto e confiança mínima.
4. Coloque os arquivos da Receita no diretório configurado.
5. Inicie a importação informando versão e data da fonte.
6. Publique uma tarefa real com especialidade.
7. Abra a tarefa e execute **Simular busca de prestadores**.
8. Aguarde o estado `DRY_RUN` e revise métricas/prévia.
9. Para produção, ajuste variáveis fora do Git, reinicie o servidor e só então use **Autorizar envio**.

## Estados persistentes

Jobs: `PENDING`, `FILTERING`, `GEOCODING`, `READY`, `DRY_RUN`, `SENDING`, `SENT`, `PARTIAL`, `FAILED`, `CANCELLED`.

Convites: `DRY_RUN`, `QUEUED`, `SENDING`, `SENT`, `FAILED`, `SUPPRESSED`, `OPTED_OUT`, `REGISTERED`, `EXPIRED`, `CANCELLED`.

O worker usa bloqueio persistente e `FOR UPDATE SKIP LOCKED`. Jobs e importações abandonados são retomados somente após um período de inatividade, evitando que outra instância ativa tenha seu trabalho capturado. Um convite é marcado como `SENDING` antes da operação SMTP. Se o processo for interrompido, ele é colocado em falha para revisão manual, evitando reenvio automático potencialmente duplicado.

## Privacidade, retenção e antispam

- O AgendaWeb comum não recebe nomes, e-mails, telefones ou CNPJs de prospects.
- A prévia administrativa mostra apenas e-mail mascarado.
- Logs não devem conter e-mail completo, token, chave ou caminho de arquivo da importação.
- Não há SMTP probing.
- `opened_at` é registrado apenas quando a pessoa abre legitimamente o link; não existe pixel invisível.
- A supressão é permanente e não é removida por importação.
- Defina política formal de retenção para prospects inativos e histórico operacional antes de produção.
- Faça revisão jurídica de base legal, transparência, LGPD e regras antispam aplicáveis antes de habilitar envios.

## Testes

No Windows:

```text
Testar.cmd
```

Ou:

```powershell
D:\IDE\apache-maven-3.9.9\bin\mvn.cmd test
```

Os testes unitários cobrem normalização de CNAE/CNPJ/CEP/e-mail, CNAE principal e secundário, e-mails descartáveis e repetidos, normalização de endereço, hash/deduplicação, cache de geocodificação, bloqueio do Nominatim público, raio de 2 km, limite de 20, limite diário, cooldown, tokens válidos/expirados, associação voluntária por e-mail, recuperação de jobs, retomada da importação, parser em streaming, geocodificador falso e texto transparente do convite.

O teste local sem JUnit `ProspectingSelfTest` permite validar as regras puras quando o Maven não estiver disponível.

Testes integrais de importação, retomada, concorrência, PostgreSQL, geocodificador e SMTP devem ser executados em ambiente de homologação com banco descartável e provedores falsos. Nunca use SMTP real nos testes.

## Verificação de segredos e push do GitHub

`Compilar.cmd` executa `Verificar-Segredos.ps1` antes do Maven. O scanner bloqueia chaves OpenAI e propriedades sensíveis com valor literal.

Se o GitHub bloquear o último commit por segredo, revogue a chave exposta, corrija o arquivo e substitua o próprio commit:

```powershell
git add src/main/resources/application.properties
git commit --amend --no-edit
git push origin main
```

Não use o link de desbloqueio para liberar um segredo real. Consulte `CORRIGIR_PUSH_BLOQUEADO.md`.

## Rollback

1. Pare o AgendaServer.
2. Restaure o backup de aplicação criado pelo IA Updater ou volte ao commit anterior.
3. As migrações são aditivas. Não apague a V3 manualmente em produção.
4. Para rollback de código, mantenha as tabelas novas sem uso; versões anteriores ignoram-nas.
5. Antes de qualquer rollback destrutivo de banco, faça `pg_dump` e valide a restauração.

## Limitações conhecidas

- O arquivo `Estabelecimentos` não contém razão social completa. Nesta versão, `legal_name` permanece vazio e o nome fantasia é usado somente quando estiver disponível. A tabela de Empresas não é importada para evitar dados desnecessários.
- O cálculo espacial usa caixa lógica/filtragem e Haversine no servidor, sem exigir PostGIS. Para uma base nacional ou volume elevado, avalie PostGIS e índices geográficos.
- A resposta do geocodificador HTTP precisa ser adaptável ao contrato descrito. Provedores com formato diferente exigem um adaptador específico.
- Confirmação de `DELIVERED` e `BOUNCED` depende de webhook legítimo do provedor de e-mail e não está ativada nesta versão.


## Versão 009 — automação segura e catálogo inicial

- A busca externa inicia automaticamente após a publicação da demanda (`AUTO_IMMEDIATE`).
- Filtragem, geocodificação, seleção e preparação da prévia são automáticas.
- O envio de e-mail permanece bloqueado por `AGENDA_EMAIL_SENDING_ENABLED=false`, além de `PRODUCAO=false` e dry-run.
- A migração `V4__initial_specialties_and_automatic_dry_run.sql` inclui um catálogo inicial administrável com especialidades e CNAEs normalizados.
- Jobs pendentes das versões anteriores são liberados para processamento automático em dry-run.

A taxonomia inicial é uma curadoria operacional baseada na estrutura de subclasses CNAE 2.1 da CONCLA/IBGE. Ela não substitui revisão administrativa ou jurídica e pode ser alterada na tela de administração.

A chave AES usada para proteger os e-mails importados é criada automaticamente em `%USERPROFILE%\appdata\agenda\prospecting.key` quando `AGENDA_PROSPECT_DATA_KEY` não estiver definida. Esse arquivo não fica no projeto nem no Git e deve entrar no backup seguro do servidor.


---

## Documento original: `AGENDA_API.md`

# AgendaServer API 1.1.0

Base local: `http://localhost:28212/api/agenda`

Endpoints autenticados usam:

```text
X-Agenda-Device-Id
X-Agenda-Auth-Token
```

## Autenticação

### `POST /auth`

```json
{
  "email": "usuario@dominio.com",
  "password": "senha-local",
  "deviceId": "identificador-com-pelo-menos-20-caracteres",
  "versionCode": 2,
  "versionName": "web-1.3.0",
  "register": true,
  "inviteToken": "token-opcional-do-convite"
}
```

O cadastro continua voluntário. `inviteToken` apenas associa uma conta criada/acessada com o mesmo e-mail; não cria candidatura.

## Especialidades

- `GET /specialties` — lista ativas para tarefa e perfil;
- `GET /users/me/specialties`;
- `PUT /users/me/specialties`;
- `GET /admin/specialties`;
- `POST /admin/specialties`;
- `PUT /admin/specialties/{id}`;
- `PUT /admin/specialties/{id}/cnaes/{code}`;
- `DELETE /admin/specialties/{id}/cnaes/{code}`.

Criação/alteração administrativa:

```json
{
  "name": "Eletricista",
  "slug": "eletricista",
  "description": "Instalações e reparos elétricos",
  "active": true
}
```

Relação CNAE:

```json
{
  "cnaeCode": "4321500",
  "description": "Instalação e manutenção elétrica",
  "matchPrimary": true,
  "matchSecondary": true,
  "active": true
}
```

## Tarefas

`POST /tasks` agora exige `specialtyId`:

```json
{
  "specialtyId": 2,
  "title": "Reparo elétrico",
  "description": "Descrição da demanda real",
  "date": "10/08/2026",
  "time": "14:00",
  "durationHours": 2,
  "peopleNeeded": 1,
  "latitude": -23.50,
  "longitude": -47.45,
  "recurrenceType": "NONE",
  "recurrenceDays": [],
  "recurrenceUntil": "",
  "favoriteProviderIds": []
}
```

A resposta de tarefa inclui `specialtyId`, `specialtyName` e, para o proprietário, o resumo protegido em `prospecting`.

## Processamento externo

- `GET /tasks/{taskId}/prospecting` — proprietário ou administrador;
- `POST /admin/tasks/{taskId}/prospecting/simulate`;
- `GET /admin/tasks/{taskId}/prospecting/preview`;
- `POST /admin/tasks/{taskId}/prospecting/authorize`;
- `POST /admin/tasks/{taskId}/prospecting/cancel`;
- `GET /admin/prospecting/metrics`.

A prévia mascara e-mails e não expõe CNPJ, telefone ou endereço.

## Importação CNPJ

- `POST /admin/cnpj-imports`;
- `GET /admin/cnpj-imports`;
- `POST /admin/cnpj-imports/{id}/resume`;
- `POST /admin/cnpj-imports/{id}/cancel`.

```json
{
  "sourceVersion": "Receita CNPJ 2026-08",
  "sourceDate": "2026-08-01"
}
```

Os arquivos são lidos exclusivamente no diretório configurado no servidor.

## Configurações administrativas

- `GET /admin/prospecting/settings`;
- `PUT /admin/prospecting/settings`.

```json
{
  "geocoderMinConfidence": 0.75,
  "triggerMode": "MANUAL",
  "pilotMunicipalities": "SOROCABA/SP"
}
```

As chaves e credenciais permanecem somente em variáveis de ambiente e nunca são retornadas.

## Supressão

- `GET /admin/suppressions` — hashes parciais, somente leitura;
- `GET /public/opt-out/{token}` — exibe contexto, não altera estado;
- `POST /public/opt-out/{token}` — confirma descadastro sem login.

## Convite público

- `GET /public/invitations/{token}`.

Retorna apenas especialidade, região aproximada, distância, data e o e-mail que precisa ser mantido no cadastro. Não retorna endereço exato do consumidor.

## Segurança

- tokens brutos não são persistidos;
- e-mails externos são criptografados e comparados por hash;
- envio real é bloqueado por `PRODUCAO=false`;
- endpoints `/admin/**` exigem e-mail administrativo;
- não há endpoints para remover supressão pelo navegador.


## Validação da rota de especialidades — versão 008

`GET /api/agenda/specialties` é implementado por `ProspectingController.activeSpecialties` e exige os cabeçalhos `X-Agenda-Device-Id` e `X-Agenda-Auth-Token`.


---

## Documento original: `ARQUIVOS_ALTERADOS_007.md`

# AgendaServer 007 — arquivos criados e alterados

Base comparada: AgendaServer 006.

## Criados

- `AgendaServer-Local.example.cmd`
- `ARQUIVOS_ALTERADOS_007.md`
- `CORRECAO_007.md`
- `CORRIGIR_PUSH_BLOQUEADO.md`
- `Testar.cmd`
- `VALIDACAO_007.md`
- `Verificar-Segredos.ps1`
- `src/main/java/br/com/mauricio/agendaserver/AdminAuthorizationService.java`
- `src/main/java/br/com/mauricio/agendaserver/CnpjImportService.java`
- `src/main/java/br/com/mauricio/agendaserver/ConfigurableGeocoder.java`
- `src/main/java/br/com/mauricio/agendaserver/ExternalInviteMailer.java`
- `src/main/java/br/com/mauricio/agendaserver/Geocoder.java`
- `src/main/java/br/com/mauricio/agendaserver/ProspectingController.java`
- `src/main/java/br/com/mauricio/agendaserver/ProspectingCryptoService.java`
- `src/main/java/br/com/mauricio/agendaserver/ProspectingRules.java`
- `src/main/java/br/com/mauricio/agendaserver/ProspectingService.java`
- `src/main/java/br/com/mauricio/agendaserver/ProspectingSettingsService.java`
- `src/main/java/br/com/mauricio/agendaserver/ProspectingValidation.java`
- `src/main/java/br/com/mauricio/agendaserver/PublicInvitationController.java`
- `src/main/java/br/com/mauricio/agendaserver/SemicolonCsvReader.java`
- `src/main/java/br/com/mauricio/agendaserver/SpecialtyService.java`
- `src/main/resources/db/migration/V3__specialties_and_external_prospecting.sql`
- `src/test/java/br/com/mauricio/agendaserver/ConfigurableGeocoderTest.java`
- `src/test/java/br/com/mauricio/agendaserver/ExternalInviteMailerTest.java`
- `src/test/java/br/com/mauricio/agendaserver/ProspectingRulesTest.java`
- `src/test/java/br/com/mauricio/agendaserver/ProspectingSelfTest.java`
- `src/test/java/br/com/mauricio/agendaserver/ProspectingValidationTest.java`
- `src/test/java/br/com/mauricio/agendaserver/SemicolonCsvReaderTest.java`

## Alterados

- `.gitignore`
- `AGENDA_API.md`
- `Compilar.cmd`
- `Iniciar-AgendaServer.cmd`
- `README.md`
- `pom.xml`
- `src/main/java/br/com/mauricio/agendaserver/AgendaMarketplaceService.java`
- `src/main/java/br/com/mauricio/agendaserver/AgendaServerApplication.java`
- `src/main/java/br/com/mauricio/agendaserver/AgendaService.java`
- `src/main/java/br/com/mauricio/agendaserver/AuthController.java`
- `src/main/java/br/com/mauricio/agendaserver/AuthenticationService.java`
- `src/main/resources/application.properties`

## Removidos

Nenhum arquivo funcional da base 006 foi removido.


---

## Documento original: `ARQUIVOS_ALTERADOS_008.md`

# AgendaServer 008 — arquivos alterados e criados

Base: AgendaServer 007.

## Alterados

- `pom.xml`
- `README.md`
- `Compilar.cmd`
- `src/main/java/br/com/mauricio/agendaserver/ProspectingController.java`

## Criados

- `CORRECAO_008.md`
- `LEIA_PRIMEIRO_008.txt`
- `Validar-Projeto-008.cmd`
- `ARQUIVOS_ALTERADOS_008.md`
- `src/test/java/br/com/mauricio/agendaserver/ProspectingControllerRouteTest.java`

Nenhum endpoint ou funcionalidade da versão 007 foi removido.


---

## Documento original: `ARQUIVOS_ALTERADOS_009.md`

# Arquivos alterados e criados — AgendaServer 009

## Alterados

- `pom.xml`
- `README.md`
- `AgendaServer-Local.example.cmd`
- `Compilar.cmd`
- `src/main/resources/application.properties`
- `src/main/java/br/com/mauricio/agendaserver/ProspectingCryptoService.java`
- `src/main/java/br/com/mauricio/agendaserver/ProspectingService.java`
- `src/main/java/br/com/mauricio/agendaserver/ProspectingSettingsService.java`

## Criados

- `src/main/resources/db/migration/V4__initial_specialties_and_automatic_dry_run.sql`
- `Validar-Projeto-009.cmd`
- `VERSAO_009.md`
- `ARQUIVOS_ALTERADOS_009.md`


---

## Documento original: `ARQUIVOS_ALTERADOS_010.md`

# Arquivos alterados e criados — AgendaServer 010

## Alterados

- `.gitignore`
- `pom.xml`
- `Iniciar-AgendaServer.cmd`
- `Compilar.cmd`
- `Verificar-Segredos.ps1`
- `AgendaServer-Local.example.cmd`
- `README.md`

## Criados

- `.project`
- `.classpath`
- `.settings/org.eclipse.jdt.core.prefs`
- `.settings/org.eclipse.m2e.core.prefs`
- `.settings/org.eclipse.core.resources.prefs`
- `AgendaServer-Database.cmd` — arquivo local ignorado pelo Git
- `Validar-Projeto-010.cmd`
- `CORRECAO_010.md`
- `VERSAO_010.md`
- `ARQUIVOS_ALTERADOS_010.md`
- `src/test/resources/.gitkeep`


---

## Documento original: `CORRECAO_002.md`

# AgendaServer 1.0.1 — Correção 002

## Problema corrigido

A inicialização falhava com:

```text
Cannot subclass final class br.com.mauricio.agendaserver.WebConfig
```

## Alterações

- `WebConfig` deixou de ser `final`.
- `WebConfig` passou a usar `@Configuration(proxyBeanMethods = false)`.
- A classe e o construtor foram declarados `public`.
- `Compilar.cmd` passou a localizar automaticamente o Maven em:
  `D:\IDE\apache-maven-3.9.9\bin\mvn.cmd`.
- A versão Maven passou de `1.0.0` para `1.0.1`.

## Validação

- Todos os fontes Java foram compilados com Java 21.
- `WebConfig` foi carregado em um `AnnotationConfigApplicationContext` real do Spring sem tentativa de proxy CGLIB.


---

## Documento original: `CORRECAO_003.md`

# Correção 003 — AgendaServer 1.0.2

O servidor 1.0.1 iniciou o Tomcat, mas o PostgreSQL recusou a senha do usuário `agenda_app` com SQL State `28P01`.

A versão 1.0.2 adiciona um configurador de banco que cria ou redefine o usuário e valida a conexão antes da inicialização do Spring Boot.

## Ordem correta

1. Execute `Configurar-Banco-PostgreSQL.cmd`.
2. Informe a senha administrativa do PostgreSQL.
3. Defina a senha de `agenda_app`.
4. Execute `Compilar.cmd`.
5. Execute `Iniciar-AgendaServer.cmd` e informe a mesma senha de `agenda_app`.


---

## Documento original: `CORRECAO_004.md`

# Correção 004 — AgendaServer 1.0.3

O configurador anterior dependia de caminhos fixos para o PostgreSQL.

Esta versão adiciona `Encontrar-PostgreSQL.cmd`, que procura `psql.exe` no PATH, em C:\IDE, D:\IDE, Program Files e pastas usuais. Quando necessário, aceita manualmente o caminho do executável ou da pasta de instalação e salva a escolha em `AgendaServer-Local.cmd`.

A lógica Java e os contratos da API não foram alterados.


---

## Documento original: `CORRECAO_005.md`

# Correção 005 — AgendaServer 1.0.4

## Problema corrigido

A classificação de fotos falhava quando `AGENDA_VISION_MODEL` estava configurado como `gpt-51`, nome que não corresponde a um modelo da API.

## Alterações

- modelo padrão: `gpt-5.1`;
- aliases inválidos conhecidos são normalizados automaticamente para `gpt-5.1`;
- porta padrão do servidor: `28212`;
- mensagem de configuração aponta corretamente para `application.properties`;
- API e esquema PostgreSQL permanecem inalterados.


---

## Documento original: `CORRECAO_006.md`

# AgendaServer 1.0.5 — versão 006

## Alterações

- Inclusão da exclusão autenticada de fotos do próprio usuário.
- Inclusão da exclusão autenticada de vídeos do próprio usuário.
- Remoção do registro no PostgreSQL e do arquivo armazenado.
- Reorganização automática de `sort_order` após cada exclusão.
- Nenhuma alteração de esquema do banco foi necessária.

## Novos endpoints

- `DELETE /api/agenda/users/me/photos/{photoId}`
- `DELETE /api/agenda/users/me/videos/{videoId}`


---

## Documento original: `CORRECAO_007.md`

# AgendaServer 007 — versão 1.1.0

- especialidades obrigatórias e CNAEs administráveis;
- importação em streaming dos dados públicos do CNPJ;
- validação conservadora de e-mail e detecção de contatos repetidos;
- geocodificação configurável com cache por endereço;
- busca por raio de 2 km e limite de 20 por tarefa;
- jobs persistentes, idempotência por tarefa/e-mail e dry-run;
- convites voluntários com tokens fortes e e-mail como chave de associação;
- descadastro público por confirmação POST e supressão permanente;
- telas e endpoints administrativos;
- proteção absoluta por `PRODUCAO=false`;
- verificação de segredos antes do build;
- testes unitários e auto-teste de regras puras.


---

## Documento original: `CORRECAO_008.md`

# Correção 008 — projeto e rota de especialidades

## Causa encontrada

O `AgendaServer_007.zip` continha `ProspectingController.java`, porém o IA Updater 011 estava configurado para atualizar:

```text
C:\Projetos\Agenda\AgendaServer
```

O repositório usado pelo desenvolvimento está em:

```text
C:\Projetos\AgendaServer
```

Consequentemente, o servidor iniciado a partir do repositório não recebeu os novos fontes.

## Correções desta versão

- versão Maven atualizada para `1.1.1`;
- `ProspectingController` e seus endpoints REST tornados públicos;
- teste de reflexão para `GET /api/agenda/specialties`;
- `Validar-Projeto-008.cmd` verifica o fonte, a migração e o JAR;
- `Compilar.cmd` interrompe o build quando a rota não estiver no projeto ou no artefato;
- documentação fixa o diretório oficial em `C:\Projetos\AgendaServer`.

## Instalação

Extraia `AgendaServer_008.zip` dentro de `C:\Projetos`, de forma que exista:

```text
C:\Projetos\AgendaServer\pom.xml
C:\Projetos\AgendaServer\src\main\java\br\com\mauricio\agendaserver\ProspectingController.java
```

Preserve seu `.git` e seu `AgendaServer-Local.cmd` locais.


---

## Documento original: `CORRECAO_010.md`

# Correção 010

## Senha do PostgreSQL

A inicialização não solicita mais a senha do banco.

A configuração usada localmente está em `AgendaServer-Database.cmd`, arquivo ignorado pelo Git. O `Iniciar-AgendaServer.cmd` carrega esse arquivo automaticamente antes de iniciar o Spring Boot.

`AgendaServer-Local.cmd` continua reservado para OpenAI e outras configurações privadas e pode sobrescrever valores quando necessário.

## Eclipse

O pacote passa a incluir `.project`, `.classpath` e `.settings` completos, com:

- `src/main/java` como source folder;
- `src/main/resources` como resources;
- `src/test/java` como source folder de testes;
- JavaSE-21;
- Maven Dependencies;
- nature Java e Maven.

As declarações `package br.com.mauricio.agendaserver;` permanecem corretas. A raiz do projeto não é source folder.

Não é necessário remover e reimportar o projeto a cada atualização. Após substituir os arquivos, o Eclipse deve apenas atualizar o workspace automaticamente; quando necessário, use `F5` no projeto.


---

## Documento original: `CORRIGIR_PUSH_BLOQUEADO.md`

# Corrigir push bloqueado por segredo

O GitHub informou uma chave OpenAI no commit `25007de479365f5dfa21c3339142b4b3427dc0f1`, em `src/main/resources/application.properties`.

1. Revogue a chave no painel da OpenAI e crie outra apenas para uso local.
2. Confirme que a propriedade está assim:

```properties
assistant.openai.api-key=${OPENAI_API_KEY:}
spring.datasource.password=${AGENDA_DB_PASSWORD:}
```

3. Execute:

```powershell
cd C:\Projetos\AgendaServer
powershell -NoProfile -ExecutionPolicy Bypass -File .\Verificar-Segredos.ps1
git add src/main/resources/application.properties
git commit --amend --no-edit
git push origin main
```

Como a branch estava apenas um commit à frente, `--amend` remove o segredo do commit que seria enviado. Criar outro commit de remoção não resolve, pois o segredo continuaria no commit anterior do push.

Não inclua `AgendaServer-Local.cmd` no Git. O arquivo correto para versionamento é somente `AgendaServer-Local.example.cmd`, sem valores reais.


---

## Documento original: `VALIDACAO_007.md`

# AgendaServer 007 — validação da entrega

## Executado no ambiente de geração

- Compilação de todos os fontes de produção com `javac --release 21`: concluída sem erros.
- `ProspectingSelfTest`: 21 verificações concluídas sem falhas.
- Varredura de segredos: nenhuma chave OpenAI ou propriedade sensível literal encontrada.
- Revisão estrutural da migração Flyway V3.

## Não executado neste ambiente

O Maven não está instalado no ambiente de geração e não havia dependências JUnit locais. Portanto, `mvn test` e `mvn package` não foram executados aqui. No computador de desenvolvimento, execute:

```powershell
D:\IDE\apache-maven-3.9.9\bin\mvn.cmd clean test package
```

Também é necessário validar a V3 em um PostgreSQL de homologação e executar testes de integração com geocodificador e SMTP falsos antes de produção.


---

## Documento original: `VALIDACAO_008.md`

# AgendaServer 008 — validação

## Executado no ambiente de geração

- conferência da estrutura completa do projeto;
- compilação dos 23 fontes principais com Java 21;
- geração de 78 classes sem erro de compilação;
- confirmação da classe `ProspectingController.class`;
- execução de `ProspectingSelfTest`: 21 verificações sem falhas;
- confirmação textual de `GET /api/agenda/specialties`;
- conferência da migração Flyway V3;
- varredura de padrões de chave OpenAI e credenciais literais;
- teste de integridade do ZIP.

## Validação local obrigatória

Execute:

```powershell
cd C:\Projetos\AgendaServer
.\Validar-Projeto-008.cmd /source
.\Compilar.cmd
.\Iniciar-AgendaServer.cmd
```

O `Compilar.cmd` confirma que o controller e a migração V3 foram inseridos no JAR Spring Boot.


---

## Documento original: `VERSAO_009.md`

# AgendaServer 009 — versão 1.1.2

## Alterações

- processamento automático de prospecção assim que uma tarefa é publicada;
- `AUTO_IMMEDIATE` como modo inicial;
- dry-run obrigatório por padrão;
- bloqueio adicional de envio por `AGENDA_EMAIL_SENDING_ENABLED=false`;
- catálogo inicial de 49 especialidades e 57 relações com CNAEs;
- criação automática e persistente da chave local de proteção dos contatos, fora do Git;
- migração V4 idempotente para instalações já existentes;
- jobs pendentes liberados automaticamente após a migração.

## Segurança

Nenhum e-mail será enviado enquanto `AGENDA_EMAIL_SENDING_ENABLED=false`. A autorização administrativa também rejeita a tentativa de envio nesse estado.


---

## Documento original: `VERSAO_010.md`

# AgendaServer 010

Versão interna: `1.1.3`.

Principais mudanças:

- senha PostgreSQL local preenchida e carregada sem prompt;
- senha mantida fora dos arquivos rastreados pelo Git;
- metadados Eclipse/Maven corrigidos e incluídos no pacote;
- source folders Java e recursos definidos explicitamente;
- validação automática da estrutura Eclipse, package Java, rotas e migrações;
- preservadas todas as funcionalidades do AgendaServer 009.


---

# Versão 012 — interface de data/horário, especialidade única e notificações

- O perfil de prestador passa a aceitar no máximo uma especialidade.
- A migração `V5__single_profile_specialty.sql` preserva a primeira especialidade de perfis antigos e cria índice único por usuário.
- Foi adicionado `PUT /api/agenda/notifications/{notificationId}/read` para marcar a notificação aberta individualmente.
- O AgendaWeb 009 abre a tarefa vinculada ao clicar em uma notificação.
- Datas de formulário passam a usar `dd/mm/aaaa`; horário passa a ser selecionado por hora e minuto.


---

# Versão 013 — log operacional do processamento

A migração `V6__prospecting_process_logs.sql` cria a tabela `agenda_prospecting_process_logs`.
Ela registra, sem dados pessoais, as etapas de criação do job, reserva pelo worker, validação da atividade,
filtragem, carregamento de CNAEs, geocodificação, progresso em lotes de 25 registros, seleção, preparação
da prévia, conclusão, adiamentos, cancelamentos e falhas.

O log é consultado por atividade em `GET /api/agenda/admin/tasks/{taskId}/prospecting/logs`.
A autorização é fixa e exclusiva para `lixocwb@gmail.com`; outros consumidores, prestadores e administradores
recebem HTTP 403. O AgendaWeb também não renderiza o botão ou o painel para outras contas.

A tabela não armazena e-mails, CNPJs, tokens, senhas, chaves nem endereços completos.
Os detalhes são limitados a contadores e estados operacionais.


# Versão 014 — diagnóstico da base CNPJ e acesso ao log

- A autenticação informa explicitamente se a conta possui acesso ao log operacional.
- O endpoint administrativo de acesso devolve `processLogAdmin`.
- Jobs não são mais marcados como simulação concluída quando a base CNPJ está vazia.
- A migração V7 converte falsos sucessos antigos em falha explicativa e registra `CNPJ_BASE_EMPTY`.


---

# AgendaServer 015 — correção da migração V7

A migração V6 cria a coluna JSONB `details` em `agenda_prospecting_process_logs`. A V7 foi corrigida para usar essa coluna, removendo a referência inválida a `details_json`. Como PostgreSQL executou a migração com transação e informou rollback, basta substituir o projeto, recompilar e reiniciar; não é necessário executar `flyway repair`.


---

## Instrução legada consolidada: `LEIA_PRIMEIRO_011.txt`

AGENDA SERVER 011

1. Extraia AgendaServer sobre C:\Projetos\AgendaServer.
2. Nao remova nem reimporte o projeto no Eclipse; pressione F5.
3. A senha do PostgreSQL ja esta em AgendaServer-Database.properties.
4. O Spring carrega esse arquivo tambem quando iniciado pelo Eclipse.
5. Execute Compilar.cmd e Iniciar-AgendaServer.cmd.
6. Toda a documentacao esta em DOCUMENTACAO.md.


---

## Instrução legada consolidada: `LEIA_PRIMEIRO_012.txt`

AgendaServer 012 — versão interna 1.1.5

Principais alterações:
- somente uma especialidade por perfil;
- migração V5 para dados existentes;
- leitura individual de notificação;
- compatível com AgendaWeb 009.


---

## Instrução legada consolidada: `LEIA_PRIMEIRO_013.txt`

AgendaServer 013 — versão interna 1.1.6

Principais alterações:
- tabela persistente de log do processamento externo;
- eventos de criação, reserva, filtragem, geocodificação, seleção, conclusão, adiamento, cancelamento e falha;
- endpoint de consulta restrito exclusivamente a lixocwb@gmail.com;
- nenhum dado pessoal, token, senha ou credencial é gravado no log;
- compatível com AgendaWeb 011.


---

## Instrução legada consolidada: `LEIA_PRIMEIRO_014.txt`

AgendaServer 014 — versão interna 1.1.7

Correções:
- acesso ao log determinado pelo servidor;
- base CNPJ vazia gera falha explicativa, não falso sucesso;
- migração V7 corrige jobs antigos com zero registros.


---

## Instrução legada consolidada: `LEIA_PRIMEIRO_015.txt`

AgendaServer 015 - versão 1.1.8

Correção:
- A migração V7 agora usa a coluna JSONB details, criada pela migração V6.
- Removida a referência inválida à coluna details_json.
- A falha anterior foi transacional e foi revertida pelo PostgreSQL; não é necessário executar Flyway repair.

Instalação:
1. Encerrar o AgendaServer.
2. Extrair sobre C:\Projetos\AgendaServer.
3. Executar Compilar.cmd.
4. Iniciar novamente pelo Eclipse ou Iniciar-AgendaServer.cmd.

Resultado esperado:
Migrating schema public to version 7 - prospecting empty base and access
Successfully applied 1 migration

---

## Versão 1.1.11 — AgendaServer 018

Correção do teste `AgendaFullLifecycleIT` para declarar explicitamente a aplicação Spring Boot:

```java
@SpringBootTest(classes = AgendaServerApplication.class, ...)
```

A declaração explícita elimina a dependência da descoberta automática de `@SpringBootConfiguration` pelo pacote do teste e permite executar o teste pelo Maven ou diretamente pelo Eclipse. A classe de teste também passou a ser pública para evitar limitações de launchers de IDE.


---

## Versão 1.1.12 — AgendaServer 019

O teste `AgendaFullLifecycleIT` deixou de usar `@SpringBootTest`, `SpringExtension`,
`@AutoConfigureMockMvc` e a descoberta automática de `@SpringBootConfiguration`.

Agora o próprio teste:

1. inicia `AgendaServerApplication` programaticamente com `SpringApplicationBuilder`;
2. usa uma porta HTTP aleatória, sem ocupar a porta operacional 28212;
3. chama os endpoints reais pelo `java.net.http.HttpClient`;
4. grava e consulta o PostgreSQL configurado;
5. valida conta, sessão, especialidade, atividade, job, log, proposta, aprovação,
   confirmação, notificações e encerramento da atividade;
6. remove todos os registros temporários e confirma a limpeza diretamente no banco;
7. fecha o contexto do Spring mesmo quando alguma fase falha.

O script `Testar-Fluxo-Completo.cmd` executa `mvn clean` antes do teste para eliminar
classes antigas de `target/test-classes`. O antigo `Testar.cmd` genérico foi removido
e também é apagado automaticamente pelo `Limpar-Projeto.ps1` quando ainda existir
em uma instalação atualizada por sobreposição.

# Atualização — catálogo CNPJ/CNAE completo, Ollama e rodadas de 5

## Pesquisa externa híbrida

A busca externa passa a combinar três camadas sem acoplar o restante do Agenda ao provedor de IA:

1. **Catálogo CNPJ/CNAE local**: fonte prioritária para consultas rápidas e determinísticas.
2. **Ollama local + Web Search**: complementa o pool quando a base local não entrega candidatos suficientes. O modelo local cria consultas e extrai candidatos em JSON estruturado.
3. **OpenAI como fallback opcional**: só é acionada quando configurada e o resultado anterior fica abaixo do mínimo definido.

Os candidatos descobertos pela web são gravados separadamente em `agenda_web_prospects`. A tabela `agenda_external_invitations` referencia exatamente uma origem: `agenda_cnpj_prospects` ou `agenda_web_prospects`.

Variáveis principais:

- `AGENDA_AI_SEARCH_ENABLED=true`;
- `AGENDA_OLLAMA_BASE_URL=http://localhost:11434`;
- `AGENDA_OLLAMA_MODEL` (vazio = detecção automática entre modelos instalados);
- `OLLAMA_API_KEY` para o Web Search oficial do Ollama;
- `AGENDA_AI_OPENAI_FALLBACK_ENABLED=false` por padrão, para impedir custo acidental;
- `OPENAI_API_KEY` opcional para fallback quando essa chave for ativada;
- `AGENDA_AI_OPENAI_MODEL`;
- `AGENDA_AI_FALLBACK_MIN_CANDIDATES=5`.

## Carga integral CNPJ + CNAE

`POST /api/agenda/admin/cnpj-imports` aceita o novo campo `mode`:

```json
{
  "sourceVersion": "Receita CNPJ 2026-08",
  "sourceDate": "2026-08-01",
  "mode": "FULL_CATALOG"
}
```

`FULL_CATALOG` percorre todos os arquivos `Estabelecimentos*.zip` disponíveis no diretório configurado e persiste todos os CNPJs válidos encontrados, independentemente de CNAE, município, situação cadastral ou existência de e-mail. Todos os CNAEs principal/secundários disponíveis são indexados em `agenda_cnpj_catalog_cnaes`. E-mails públicos válidos, quando existirem, permanecem criptografados em repouso.

A carga integral **não cria autorização de contato**. Quando uma demanda precisa de determinado serviço, o Agenda materializa do catálogo apenas os registros que naquele momento satisfazem as regras de CNAE, situação ativa, município piloto e contato/endereço válidos. Depois ainda são aplicados distância, supressão, cooldown e deduplicação. Isso permite adicionar novos CNAEs sem reimportar toda a Receita.

O modo legado `PROSPECTING_ONLY` continua disponível para carregar apenas os registros elegíveis para as especialidades atuais. O modo padrão da nova carga administrativa é `FULL_CATALOG`.

## Rodadas de oportunidade

A seleção pode preparar um pool maior (`AGENDA_PROSPECTING_LIMIT_PER_TASK`, padrão 100), mas o envio é separado do pool:

- no máximo `AGENDA_CONTACT_ROUND_SIZE` contatos são liberados por rodada; o código limita esse valor tecnicamente a **5**;
- os candidatos das próximas rodadas ficam com status `WAITING_ROUND`;
- após uma rodada, o job entra em `WAITING_RESPONSE`;
- se não ocorrer resposta positiva em `AGENDA_RESPONSE_BUSINESS_HOURS` (padrão **2 horas úteis**), a próxima rodada de até 5 é liberada;
- um cadastro realizado por convite é considerado resposta positiva e muda o job para `RESPONDED`; filas futuras são canceladas com `CANCELLED_RESPONSE`;
- o mesmo e-mail não volta a ser usado na mesma tarefa, e supressão/cooldown continuam obrigatórios;
- quando não existem candidatos adicionais, o job termina em `EXHAUSTED`.

A janela útil padrão é segunda a sexta, 09:00–18:00, fuso `America/Sao_Paulo`. Feriados ainda não fazem parte do cálculo; podem ser adicionados posteriormente por calendário configurável.

## Migração V9

`V9__ai_search_full_cnpj_catalog_and_contact_rounds.sql` cria:

- `agenda_cnpj_catalog`;
- `agenda_cnpj_catalog_cnaes`;
- `agenda_web_prospects`;
- campos de origem web e número da rodada em `agenda_external_invitations`;
- `current_round`, `next_round_at`, `ai_provider` e `ai_candidates_count` nos jobs.
