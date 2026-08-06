-- Corrige jobs antigos concluídos sem qualquer base CNPJ importada.
UPDATE agenda_prospecting_jobs
SET state='FAILED',
    last_error='A base pública do CNPJ ainda não foi importada. Configure AGENDA_CNPJ_IMPORT_DIR e execute a importação administrativa antes de processar demandas.',
    completed_at=COALESCE(completed_at, CURRENT_TIMESTAMP),
    updated_at=CURRENT_TIMESTAMP
WHERE state='DRY_RUN'
  AND records_analyzed=0;

INSERT INTO agenda_prospecting_process_logs(
    job_id, task_id, stage, event_code, level, message, records_count, details
)
SELECT j.id, j.task_id, 'FAILED', 'CNPJ_BASE_EMPTY', 'WARN',
       'A base pública do CNPJ ainda não foi importada; o processamento não possui estabelecimentos para analisar.',
       0, '{"activeProspects":0}'::jsonb
FROM agenda_prospecting_jobs j
WHERE j.state='FAILED'
  AND j.records_analyzed=0
  AND j.last_error LIKE 'A base pública do CNPJ ainda não foi importada%'
  AND NOT EXISTS (
      SELECT 1 FROM agenda_prospecting_process_logs l
      WHERE l.job_id=j.id AND l.event_code='CNPJ_BASE_EMPTY'
  );
