CREATE TABLE agenda_specialties (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agenda_specialties_name UNIQUE (name),
    CONSTRAINT uq_agenda_specialties_slug UNIQUE (slug)
);

CREATE TABLE agenda_specialty_cnaes (
    specialty_id BIGINT NOT NULL REFERENCES agenda_specialties(id) ON DELETE CASCADE,
    cnae_code VARCHAR(7) NOT NULL,
    description VARCHAR(250) NOT NULL DEFAULT '',
    match_primary BOOLEAN NOT NULL DEFAULT TRUE,
    match_secondary BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (specialty_id, cnae_code)
);
CREATE INDEX idx_agenda_specialty_cnaes_code ON agenda_specialty_cnaes(cnae_code, active);

INSERT INTO agenda_specialties(name, slug, description, active)
VALUES ('Serviços gerais', 'servicos-gerais', 'Especialidade padrão para tarefas criadas antes do cadastro estruturado.', TRUE)
ON CONFLICT (slug) DO NOTHING;

ALTER TABLE agenda_tasks ADD COLUMN specialty_id BIGINT;
UPDATE agenda_tasks
SET specialty_id=(SELECT id FROM agenda_specialties WHERE slug='servicos-gerais')
WHERE specialty_id IS NULL;
ALTER TABLE agenda_tasks ALTER COLUMN specialty_id SET NOT NULL;
ALTER TABLE agenda_tasks ADD CONSTRAINT fk_agenda_tasks_specialty
    FOREIGN KEY (specialty_id) REFERENCES agenda_specialties(id);
CREATE INDEX idx_agenda_tasks_specialty ON agenda_tasks(specialty_id, task_status, starts_at);

CREATE TABLE agenda_user_specialties (
    user_id VARCHAR(36) NOT NULL REFERENCES agenda_users(id) ON DELETE CASCADE,
    specialty_id BIGINT NOT NULL REFERENCES agenda_specialties(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, specialty_id)
);
CREATE INDEX idx_agenda_user_specialties_specialty ON agenda_user_specialties(specialty_id, user_id);

CREATE TABLE agenda_cnpj_import_runs (
    id VARCHAR(36) PRIMARY KEY,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    source_version VARCHAR(80) NOT NULL,
    source_date DATE NULL,
    import_directory VARCHAR(500) NOT NULL DEFAULT '',
    current_file VARCHAR(500) NOT NULL DEFAULT '',
    checkpoint_line BIGINT NOT NULL DEFAULT 0,
    files_total INTEGER NOT NULL DEFAULT 0,
    files_processed INTEGER NOT NULL DEFAULT 0,
    records_read BIGINT NOT NULL DEFAULT 0,
    records_imported BIGINT NOT NULL DEFAULT 0,
    records_rejected BIGINT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NOT NULL DEFAULT '',
    requested_by VARCHAR(36) NOT NULL REFERENCES agenda_accounts(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_agenda_cnpj_import_runs_status ON agenda_cnpj_import_runs(status, created_at);

CREATE TABLE agenda_import_rejections (
    import_run_id VARCHAR(36) NOT NULL REFERENCES agenda_cnpj_import_runs(id) ON DELETE CASCADE,
    reason_code VARCHAR(60) NOT NULL,
    rejected_count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(import_run_id, reason_code)
);

CREATE TABLE agenda_cnpj_prospects (
    id BIGSERIAL PRIMARY KEY,
    cnpj CHAR(14) NOT NULL,
    legal_name VARCHAR(250) NOT NULL DEFAULT '',
    trade_name VARCHAR(250) NOT NULL DEFAULT '',
    registration_status VARCHAR(8) NOT NULL,
    municipality_code VARCHAR(12) NOT NULL,
    municipality_name VARCHAR(120) NOT NULL,
    uf CHAR(2) NOT NULL,
    cnae_primary VARCHAR(7) NOT NULL,
    email_hash CHAR(64) NOT NULL,
    email_ciphertext TEXT NOT NULL,
    email_domain VARCHAR(190) NOT NULL,
    email_quality_status VARCHAR(30) NOT NULL DEFAULT 'VALID',
    address_normalized VARCHAR(500) NOT NULL,
    address_hash CHAR(64) NOT NULL,
    cep VARCHAR(8) NOT NULL DEFAULT '',
    latitude DOUBLE PRECISION NULL,
    longitude DOUBLE PRECISION NULL,
    geocode_provider VARCHAR(80) NOT NULL DEFAULT '',
    geocode_confidence DOUBLE PRECISION NULL,
    geocode_precision VARCHAR(40) NOT NULL DEFAULT '',
    geocode_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    geocode_error VARCHAR(500) NOT NULL DEFAULT '',
    geocoded_at TIMESTAMP NULL,
    source_version VARCHAR(80) NOT NULL,
    source_date DATE NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agenda_cnpj_prospects_cnpj UNIQUE(cnpj)
);
CREATE INDEX idx_agenda_cnpj_prospects_status_municipality ON agenda_cnpj_prospects(registration_status, uf, municipality_name);
CREATE INDEX idx_agenda_cnpj_prospects_email_hash ON agenda_cnpj_prospects(email_hash);
CREATE INDEX idx_agenda_cnpj_prospects_address_hash ON agenda_cnpj_prospects(address_hash);
CREATE INDEX idx_agenda_cnpj_prospects_geo ON agenda_cnpj_prospects(latitude, longitude) WHERE geocode_status='VALID';
CREATE INDEX idx_agenda_cnpj_prospects_active ON agenda_cnpj_prospects(active, email_quality_status, geocode_status);

CREATE TABLE agenda_cnpj_prospect_cnaes (
    prospect_id BIGINT NOT NULL REFERENCES agenda_cnpj_prospects(id) ON DELETE CASCADE,
    cnae_code VARCHAR(7) NOT NULL,
    primary_cnae BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(prospect_id, cnae_code)
);
CREATE INDEX idx_agenda_cnpj_prospect_cnaes_code ON agenda_cnpj_prospect_cnaes(cnae_code, primary_cnae, prospect_id);

CREATE TABLE agenda_geocoding_cache (
    address_hash CHAR(64) PRIMARY KEY,
    address_normalized VARCHAR(500) NOT NULL,
    latitude DOUBLE PRECISION NULL,
    longitude DOUBLE PRECISION NULL,
    provider VARCHAR(80) NOT NULL,
    confidence DOUBLE PRECISION NULL,
    precision_code VARCHAR(40) NOT NULL DEFAULT '',
    status VARCHAR(24) NOT NULL,
    error_reason VARCHAR(500) NOT NULL DEFAULT '',
    geocoded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_agenda_geocoding_cache_status ON agenda_geocoding_cache(status, geocoded_at);

CREATE TABLE agenda_prospecting_jobs (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL REFERENCES agenda_tasks(id) ON DELETE CASCADE,
    specialty_id BIGINT NOT NULL REFERENCES agenda_specialties(id),
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    dry_run BOOLEAN NOT NULL DEFAULT TRUE,
    manual_trigger BOOLEAN NOT NULL DEFAULT FALSE,
    send_authorized BOOLEAN NOT NULL DEFAULT FALSE,
    authorized_by VARCHAR(36) NULL REFERENCES agenda_accounts(id),
    authorized_at TIMESTAMP NULL,
    cancelled_at TIMESTAMP NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    lock_owner VARCHAR(120) NOT NULL DEFAULT '',
    locked_at TIMESTAMP NULL,
    not_before TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    records_analyzed BIGINT NOT NULL DEFAULT 0,
    filtered_cnae BIGINT NOT NULL DEFAULT 0,
    filtered_email BIGINT NOT NULL DEFAULT 0,
    filtered_address BIGINT NOT NULL DEFAULT 0,
    inside_radius BIGINT NOT NULL DEFAULT 0,
    selected_count INTEGER NOT NULL DEFAULT 0,
    prepared_count INTEGER NOT NULL DEFAULT 0,
    sent_count INTEGER NOT NULL DEFAULT 0,
    failure_count INTEGER NOT NULL DEFAULT 0,
    optout_count INTEGER NOT NULL DEFAULT 0,
    registration_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    CONSTRAINT uq_agenda_prospecting_job_task UNIQUE(task_id)
);
CREATE INDEX idx_agenda_prospecting_jobs_state ON agenda_prospecting_jobs(state, not_before, locked_at);
CREATE INDEX idx_agenda_prospecting_jobs_task ON agenda_prospecting_jobs(task_id);

CREATE TABLE agenda_external_invitations (
    id VARCHAR(36) PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL REFERENCES agenda_prospecting_jobs(id) ON DELETE CASCADE,
    task_id VARCHAR(36) NOT NULL REFERENCES agenda_tasks(id) ON DELETE CASCADE,
    specialty_id BIGINT NOT NULL REFERENCES agenda_specialties(id),
    prospect_id BIGINT NOT NULL REFERENCES agenda_cnpj_prospects(id),
    email_hash CHAR(64) NOT NULL,
    email_ciphertext TEXT NOT NULL,
    distance_km DOUBLE PRECISION NOT NULL,
    matched_cnae VARCHAR(7) NOT NULL,
    cnae_match_type VARCHAR(12) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    optout_token_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    source VARCHAR(40) NOT NULL DEFAULT 'RECEITA_CNPJ',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sending_started_at TIMESTAMP NULL,
    sent_at TIMESTAMP NULL,
    opened_at TIMESTAMP NULL,
    registered_at TIMESTAMP NULL,
    opted_out_at TIMESTAMP NULL,
    failed_at TIMESTAMP NULL,
    failure_reason VARCHAR(500) NOT NULL DEFAULT '',
    expires_at TIMESTAMP NOT NULL,
    registered_user_id VARCHAR(36) NULL REFERENCES agenda_accounts(id),
    CONSTRAINT uq_agenda_external_invitation_task_email UNIQUE(task_id, email_hash),
    CONSTRAINT uq_agenda_external_invitation_token UNIQUE(token_hash),
    CONSTRAINT uq_agenda_external_optout_token UNIQUE(optout_token_hash)
);
CREATE INDEX idx_agenda_external_invitations_task ON agenda_external_invitations(task_id, status);
CREATE INDEX idx_agenda_external_invitations_email ON agenda_external_invitations(email_hash, sent_at);
CREATE INDEX idx_agenda_external_invitations_status ON agenda_external_invitations(status, updated_at);
CREATE INDEX idx_agenda_external_invitations_sending ON agenda_external_invitations(status, sending_started_at) WHERE status='SENDING';
CREATE INDEX idx_agenda_external_invitations_expires ON agenda_external_invitations(expires_at, status);

CREATE TABLE agenda_email_suppressions (
    id BIGSERIAL PRIMARY KEY,
    email_hash CHAR(64) NOT NULL,
    scope VARCHAR(20) NOT NULL DEFAULT 'GLOBAL',
    specialty_id BIGINT NULL REFERENCES agenda_specialties(id),
    reason VARCHAR(120) NOT NULL DEFAULT 'OPT_OUT',
    origin VARCHAR(80) NOT NULL DEFAULT 'PUBLIC_LINK',
    requested_by_holder BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uq_agenda_email_suppressions_scope
    ON agenda_email_suppressions(email_hash, scope, COALESCE(specialty_id, 0));
CREATE INDEX idx_agenda_email_suppressions_hash ON agenda_email_suppressions(email_hash);

CREATE TABLE agenda_prospecting_settings (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(500) NOT NULL,
    updated_by VARCHAR(36) NULL REFERENCES agenda_accounts(id),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO agenda_prospecting_settings(setting_key, setting_value) VALUES
('geocoder.min_confidence', '0.75'),
('trigger.mode', 'MANUAL'),
('pilot.municipalities', 'SOROCABA/SP')
ON CONFLICT(setting_key) DO NOTHING;
