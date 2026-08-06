CREATE TABLE agenda_prospecting_process_logs (
    id BIGSERIAL PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL REFERENCES agenda_prospecting_jobs(id) ON DELETE CASCADE,
    task_id VARCHAR(36) NOT NULL REFERENCES agenda_tasks(id) ON DELETE CASCADE,
    stage VARCHAR(40) NOT NULL,
    event_code VARCHAR(80) NOT NULL,
    level VARCHAR(12) NOT NULL DEFAULT 'INFO',
    message VARCHAR(500) NOT NULL,
    records_count BIGINT NULL,
    elapsed_ms BIGINT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agenda_process_log_level CHECK (level IN ('INFO','WARN','ERROR'))
);

CREATE INDEX idx_agenda_process_logs_task_created
    ON agenda_prospecting_process_logs(task_id, created_at, id);
CREATE INDEX idx_agenda_process_logs_job_created
    ON agenda_prospecting_process_logs(job_id, created_at, id);
CREATE INDEX idx_agenda_process_logs_level
    ON agenda_prospecting_process_logs(level, created_at);

INSERT INTO agenda_prospecting_process_logs(
    job_id,task_id,stage,event_code,level,message,details)
SELECT j.id,j.task_id,j.state,'LOG_ENABLED','INFO',
       'Log operacional habilitado. Eventos anteriores à versão 013 não podem ser reconstruídos.',
       jsonb_build_object('currentState',j.state)
FROM agenda_prospecting_jobs j;
