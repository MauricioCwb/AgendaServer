-- Garante no máximo uma especialidade por perfil de prestador.
-- Perfis antigos com várias especialidades preservam a primeira associação criada.
WITH ranked AS (
    SELECT user_id, specialty_id,
           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY created_at, specialty_id) AS position
    FROM agenda_user_specialties
)
DELETE FROM agenda_user_specialties target
USING ranked
WHERE target.user_id = ranked.user_id
  AND target.specialty_id = ranked.specialty_id
  AND ranked.position > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_agenda_user_specialty_single
    ON agenda_user_specialties(user_id);
