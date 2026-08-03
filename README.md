# AgendaServer 1.0.3

Servidor independente do AgendaJá, extraído do servidor compartilhado anterior.

## Tecnologia

- Java 21
- Spring Boot 3.4.7
- Maven
- PostgreSQL
- Flyway para versionamento do esquema
- JDBC com pool HikariCP

O projeto não contém CallBlock e não consulta nenhuma tabela, API ou sessão do servidor antigo.

## Diretório de instalação

```text
C:\Projetos\AgendaServer
```

## Banco PostgreSQL

Execute:

```bat
Configurar-Banco-PostgreSQL.cmd
```

O configurador procura `psql.exe` no `PATH`, em `C:\IDE`, `D:\IDE`, `Program Files` e nas pastas usuais do PostgreSQL. Se não encontrar, solicita manualmente o arquivo ou a pasta de instalação e grava a localização em `AgendaServer-Local.cmd`. Depois cria ou atualiza o usuário `agenda_app`, cria o banco `agenda` quando necessário e testa a autenticação.

Use exatamente a mesma senha ao executar `Iniciar-AgendaServer.cmd`. O Flyway criará automaticamente as tabelas na primeira inicialização bem-sucedida.

## Variáveis de ambiente

```bat
set AGENDA_DB_URL=jdbc:postgresql://localhost:5432/agenda
set AGENDA_DB_USER=agenda_app
set AGENDA_DB_PASSWORD=troque-esta-senha
set AGENDA_SERVER_PORT=8081
```

Opcional para classificação de fotos:

```bat
set OPENAI_API_KEY=sua-chave
set AGENDA_VISION_MODEL=gpt-5.6-luna
```

Diretório padrão das mídias:

```text
%USERPROFILE%\appdata\agenda
```

Pode ser alterado com `AGENDA_UPLOAD_DIR`.

## Compilar

```bat
Compilar.cmd
```

Artefato esperado:

```text
target\AgendaServer.jar
```

## Executar

```bat
Iniciar-AgendaServer.cmd
```

Teste de saúde:

```text
GET http://localhost:8081/api/agenda/health
```

## Autenticação própria

O Android e a versão Web usam:

```text
POST /api/agenda/auth
```

A primeira autenticação de um e-mail cria a conta. As senhas são armazenadas com PBKDF2-HMAC-SHA256, salt aleatório e 210.000 iterações. O token devolvido ao cliente não é armazenado em texto puro; somente seu SHA-256 fica no PostgreSQL.

## Dados do servidor antigo

Esta versão migra as funcionalidades e a arquitetura. Ela não importa automaticamente os registros MySQL existentes. Uma eventual migração de dados deve ser feita separadamente, após validar quais dados de Agenda precisam ser preservados.


## Maven no ambiente do projeto

O script `Compilar.cmd` procura o Maven nesta ordem:

1. variável `AGENDA_MAVEN_CMD`;
2. `D:\IDE\apache-maven-3.9.9\bin\mvn.cmd`;
3. variável `MAVEN_HOME`;
4. `mvn.cmd` disponível no `PATH`.

## Correção da versão 1.0.1

A configuração CORS `WebConfig` passou a usar `@Configuration(proxyBeanMethods = false)` e deixou de ser uma classe `final`. Isso evita a falha de inicialização `Cannot subclass final class br.com.mauricio.agendaserver.WebConfig`.

## Correção da versão 1.0.2

- adicionado `Configurar-Banco-PostgreSQL.cmd`;
- criação ou redefinição idempotente do usuário `agenda_app`;
- criação do banco `agenda` quando ainda não existe;
- teste de autenticação antes de iniciar o Java;
- mensagem direta quando a senha informada não corresponde ao PostgreSQL.


## Correção da versão 1.0.3

- localização automática do `psql.exe` em diferentes discos e versões;
- entrada manual do caminho quando a busca não encontra a instalação;
- gravação local do caminho em `AgendaServer-Local.cmd`;
- inicialização do Java permitida mesmo sem `psql.exe`, usando a conexão JDBC;
- nenhuma alteração nas regras ou endpoints da API.
