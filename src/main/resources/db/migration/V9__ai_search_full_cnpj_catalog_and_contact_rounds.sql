-- Catálogo integral CNPJ/CNAE, pesquisa por IA e rodadas controladas de convites externos.

ALTER TABLE agenda_cnpj_import_runs
    ADD COLUMN import_mode VARCHAR(24) NOT NULL DEFAULT 'PROSPECTING_ONLY';

CREATE TABLE agenda_cnpj_catalog (
    cnpj CHAR(14) PRIMARY KEY,
    trade_name VARCHAR(250) NOT NULL DEFAULT '',
    registration_status VARCHAR(8) NOT NULL DEFAULT '',
    municipality_code VARCHAR(12) NOT NULL DEFAULT '',
    municipality_name VARCHAR(120) NOT NULL DEFAULT '',
    uf CHAR(2) NOT NULL DEFAULT '',
    cnae_primary VARCHAR(7) NOT NULL DEFAULT '',
    email_hash CHAR(64) NOT NULL DEFAULT '',
    email_ciphertext TEXT NOT NULL DEFAULT '',
    email_domain VARCHAR(190) NOT NULL DEFAULT '',
    email_quality_status VARCHAR(30) NOT NULL DEFAULT 'UNAVAILABLE',
    address_normalized VARCHAR(500) NOT NULL DEFAULT '',
    address_hash CHAR(64) NOT NULL DEFAULT '',
    cep VARCHAR(8) NOT NULL DEFAULT '',
    source_version VARCHAR(80) NOT NULL,
    source_date DATE NULL,
    source_current BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_agenda_cnpj_catalog_status_location ON agenda_cnpj_catalog(registration_status, uf, municipality_name);
CREATE INDEX idx_agenda_cnpj_catalog_email ON agenda_cnpj_catalog(email_hash) WHERE email_hash<>'';
CREATE INDEX idx_agenda_cnpj_catalog_source ON agenda_cnpj_catalog(source_current, source_version);
CREATE INDEX idx_agenda_cnpj_catalog_primary ON agenda_cnpj_catalog(cnae_primary, registration_status);

CREATE TABLE agenda_cnpj_catalog_cnaes (
    cnpj CHAR(14) NOT NULL REFERENCES agenda_cnpj_catalog(cnpj) ON DELETE CASCADE,
    cnae_code VARCHAR(7) NOT NULL,
    primary_cnae BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(cnpj, cnae_code)
);
CREATE INDEX idx_agenda_cnpj_catalog_cnaes_code ON agenda_cnpj_catalog_cnaes(cnae_code, primary_cnae, cnpj);

CREATE TABLE agenda_web_prospects (
    id BIGSERIAL PRIMARY KEY,
    identity_hash CHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(250) NOT NULL DEFAULT '',
    email_hash CHAR(64) NOT NULL,
    email_ciphertext TEXT NOT NULL,
    email_domain VARCHAR(190) NOT NULL DEFAULT '',
    phone VARCHAR(60) NOT NULL DEFAULT '',
    address_normalized VARCHAR(500) NOT NULL DEFAULT '',
    address_hash CHAR(64) NOT NULL DEFAULT '',
    municipality_name VARCHAR(120) NOT NULL DEFAULT '',
    uf CHAR(2) NOT NULL DEFAULT '',
    latitude DOUBLE PRECISION NULL,
    longitude DOUBLE PRECISION NULL,
    geocode_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    geocode_confidence DOUBLE PRECISION NULL,
    geocode_precision VARCHAR(40) NOT NULL DEFAULT '',
    source_url VARCHAR(1000) NOT NULL,
    source_title VARCHAR(500) NOT NULL DEFAULT '',
    source_provider VARCHAR(40) NOT NULL,
    relevance_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    discovered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_agenda_web_prospects_email ON agenda_web_prospects(email_hash);
CREATE INDEX idx_agenda_web_prospects_geo ON agenda_web_prospects(latitude, longitude) WHERE geocode_status='VALID';
CREATE INDEX idx_agenda_web_prospects_provider ON agenda_web_prospects(source_provider, discovered_at);

ALTER TABLE agenda_external_invitations
    ALTER COLUMN prospect_id DROP NOT NULL;
ALTER TABLE agenda_external_invitations
    ADD COLUMN web_prospect_id BIGINT NULL REFERENCES agenda_web_prospects(id),
    ADD COLUMN round_number INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN source_url VARCHAR(1000) NOT NULL DEFAULT '';
ALTER TABLE agenda_external_invitations
    ADD CONSTRAINT ck_agenda_external_invitation_source
    CHECK ((prospect_id IS NOT NULL AND web_prospect_id IS NULL) OR (prospect_id IS NULL AND web_prospect_id IS NOT NULL));
CREATE INDEX idx_agenda_external_invitations_round ON agenda_external_invitations(job_id, round_number, status);

ALTER TABLE agenda_prospecting_jobs
    ADD COLUMN current_round INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_round_at TIMESTAMP NULL,
    ADD COLUMN ai_provider VARCHAR(40) NOT NULL DEFAULT '',
    ADD COLUMN ai_candidates_count INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_agenda_prospecting_jobs_round_wait ON agenda_prospecting_jobs(state, not_before, next_round_at);

INSERT INTO agenda_prospecting_settings(setting_key, setting_value) VALUES
('contact.round_size', '5'),
('contact.response_business_hours', '2')
ON CONFLICT(setting_key) DO NOTHING;
