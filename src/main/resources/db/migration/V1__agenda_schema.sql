CREATE TABLE agenda_accounts (
    id VARCHAR(36) PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(160) NOT NULL,
    account_name VARCHAR(120) NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agenda_accounts_email UNIQUE (email)
);

CREATE TABLE agenda_sessions (
    token_hash CHAR(64) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES agenda_accounts(id) ON DELETE CASCADE,
    device_id VARCHAR(200) NOT NULL,
    version_code INTEGER NOT NULL DEFAULT 0,
    version_name VARCHAR(40) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_agenda_sessions_user ON agenda_sessions(user_id);
CREATE INDEX idx_agenda_sessions_expiry ON agenda_sessions(expires_at);

CREATE TABLE agenda_users (
    id VARCHAR(36) PRIMARY KEY REFERENCES agenda_accounts(id) ON DELETE CASCADE,
    installation_id VARCHAR(128) NOT NULL UNIQUE,
    device_secret_hash CHAR(64) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    role_code VARCHAR(16) NOT NULL DEFAULT 'BOTH',
    bio VARCHAR(100) NOT NULL DEFAULT '',
    plan_code VARCHAR(20) NOT NULL DEFAULT 'FREE_ADS',
    founder_free BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agenda_users_role CHECK (role_code IN ('CONSUMER','PROVIDER','BOTH'))
);

CREATE TABLE agenda_tasks (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL REFERENCES agenda_users(id),
    title VARCHAR(180) NOT NULL,
    description TEXT NOT NULL,
    starts_at TIMESTAMP NOT NULL,
    duration_minutes INTEGER NOT NULL,
    people_needed INTEGER NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    recurrence_label VARCHAR(120) NOT NULL DEFAULT '',
    offer_phase VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    offer_expires_at TIMESTAMP NULL,
    task_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_agenda_tasks_starts ON agenda_tasks(starts_at);
CREATE INDEX idx_agenda_tasks_owner ON agenda_tasks(owner_id);

CREATE TABLE agenda_candidates (
    task_id VARCHAR(36) NOT NULL REFERENCES agenda_tasks(id) ON DELETE CASCADE,
    user_id VARCHAR(36) NOT NULL REFERENCES agenda_users(id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    distance_km DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(task_id,user_id)
);
CREATE INDEX idx_agenda_candidates_user ON agenda_candidates(user_id);

CREATE TABLE agenda_photos (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES agenda_users(id) ON DELETE CASCADE,
    file_name VARCHAR(100) NOT NULL,
    mime_type VARCHAR(40) NOT NULL,
    sort_order SMALLINT NOT NULL,
    size_bytes BIGINT NOT NULL,
    service_classification VARCHAR(80) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agenda_photo_order UNIQUE(user_id,sort_order)
);

CREATE TABLE agenda_videos (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES agenda_users(id) ON DELETE CASCADE,
    file_name VARCHAR(100) NOT NULL,
    mime_type VARCHAR(40) NOT NULL,
    sort_order SMALLINT NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agenda_video_order UNIQUE(user_id,sort_order)
);

CREATE TABLE agenda_service_prices (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES agenda_users(id) ON DELETE CASCADE,
    service_name VARCHAR(80) NOT NULL,
    price_cents INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agenda_service_name UNIQUE(user_id,service_name)
);

CREATE TABLE agenda_favorites (
    consumer_id VARCHAR(36) NOT NULL REFERENCES agenda_users(id) ON DELETE CASCADE,
    provider_id VARCHAR(36) NOT NULL REFERENCES agenda_users(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(consumer_id,provider_id),
    CONSTRAINT ck_agenda_favorite_distinct CHECK (consumer_id <> provider_id)
);

CREATE TABLE agenda_task_invites (
    task_id VARCHAR(36) NOT NULL REFERENCES agenda_tasks(id) ON DELETE CASCADE,
    provider_id VARCHAR(36) NOT NULL REFERENCES agenda_users(id) ON DELETE CASCADE,
    priority_level SMALLINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
    offered_at TIMESTAMP NULL,
    expires_at TIMESTAMP NULL,
    responded_at TIMESTAMP NULL,
    PRIMARY KEY(task_id,provider_id),
    CONSTRAINT uq_agenda_invite_level UNIQUE(task_id,priority_level)
);

CREATE TABLE agenda_notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES agenda_users(id) ON DELETE CASCADE,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(120) NOT NULL,
    message VARCHAR(500) NOT NULL,
    task_id VARCHAR(36) NULL REFERENCES agenda_tasks(id) ON DELETE SET NULL,
    read_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_agenda_notification_user ON agenda_notifications(user_id,created_at DESC);
