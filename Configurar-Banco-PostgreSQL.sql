\set ON_ERROR_STOP on

-- Recebe agenda_password pelo parametro -v do psql.
-- O uso de :'agenda_password' faz o escape SQL do valor informado.

SELECT format('CREATE ROLE agenda_app LOGIN PASSWORD %L', :'agenda_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agenda_app')
\gexec

ALTER ROLE agenda_app WITH LOGIN PASSWORD :'agenda_password';

SELECT 'CREATE DATABASE agenda OWNER agenda_app ENCODING ''UTF8'''
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'agenda')
\gexec

ALTER DATABASE agenda OWNER TO agenda_app;
GRANT ALL PRIVILEGES ON DATABASE agenda TO agenda_app;
