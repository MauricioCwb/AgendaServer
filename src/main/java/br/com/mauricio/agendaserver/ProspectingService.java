package br.com.mauricio.agendaserver;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
final class ProspectingService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private final DataSource dataSource;
    private final ProspectingSettingsService settings;
    private final ProspectingCryptoService crypto;
    private final SpecialtyService specialties;
    private final Geocoder geocoder;
    private final ExternalInviteMailer mailer;
    private final ProspectingProcessLogService processLogs;
    private final AiProspectingService aiProspecting;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    private final String workerId = "AgendaServer-" + UUID.randomUUID();

    ProspectingService(DataSource dataSource, ProspectingSettingsService settings,
                       ProspectingCryptoService crypto, SpecialtyService specialties,
                       Geocoder geocoder, ExternalInviteMailer mailer,
                       ProspectingProcessLogService processLogs, AiProspectingService aiProspecting) {
        this.dataSource = dataSource;
        this.settings = settings;
        this.crypto = crypto;
        this.specialties = specialties;
        this.geocoder = geocoder;
        this.mailer = mailer;
        this.processLogs = processLogs;
        this.aiProspecting = aiProspecting;
    }

    void scheduleForTask(String taskId, long specialtyId) {
        String jobId = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO agenda_prospecting_jobs(id,task_id,specialty_id,state,dry_run,manual_trigger,not_before)
                     VALUES(?,?,?,'PENDING',TRUE,FALSE,CURRENT_TIMESTAMP)
                     ON CONFLICT(task_id) DO NOTHING
                     """)) {
            insert.setString(1, jobId);
            insert.setString(2, taskId);
            insert.setLong(3, specialtyId);
            if (insert.executeUpdate() == 1) {
                processLogs.info(jobId, "PENDING", "JOB_CREATED",
                        "Processamento automático registrado após a publicação da atividade.");
            }
        } catch (SQLException exception) {
            throw serverError("Não foi possível registrar a busca externa da tarefa.", exception);
        }
    }

    JobSummary simulate(String taskId, String adminId) {
        TaskInfo task = task(taskId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement upsert = connection.prepareStatement("""
                     INSERT INTO agenda_prospecting_jobs(id,task_id,specialty_id,state,dry_run,manual_trigger,not_before,
                       send_authorized,authorized_by,authorized_at,last_error,updated_at,completed_at)
                     VALUES(?,?,?,'PENDING',TRUE,TRUE,CURRENT_TIMESTAMP,FALSE,NULL,NULL,'',CURRENT_TIMESTAMP,NULL)
                     ON CONFLICT(task_id) DO UPDATE SET state='PENDING',dry_run=TRUE,manual_trigger=TRUE,
                       not_before=CURRENT_TIMESTAMP,send_authorized=FALSE,authorized_by=NULL,authorized_at=NULL,
                       last_error='',updated_at=CURRENT_TIMESTAMP,completed_at=NULL,
                       records_analyzed=0,filtered_cnae=0,filtered_email=0,filtered_address=0,inside_radius=0,
                       selected_count=0,prepared_count=0,sent_count=0,failure_count=0,optout_count=0,registration_count=0,
                       current_round=0,next_round_at=NULL,ai_provider='',ai_candidates_count=0
                     """)) {
                upsert.setString(1, UUID.randomUUID().toString());
                upsert.setString(2, taskId);
                upsert.setLong(3, task.specialtyId());
                upsert.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM agenda_external_invitations WHERE task_id=? AND status IN ('DRY_RUN','SELECTED','QUEUED','WAITING_ROUND','FAILED','SUPPRESSED','CANCELLED_RESPONSE')")) {
                delete.setString(1, taskId);
                delete.executeUpdate();
            }
            connection.commit();
            JobSummary result = summary(taskId);
            processLogs.info(result.id(), "PENDING", "MANUAL_SIMULATION_REQUESTED",
                    "Nova simulação administrativa solicitada; métricas e prévia anteriores foram reiniciadas.");
            return result;
        } catch (SQLException exception) {
            throw serverError("Não foi possível iniciar a simulação.", exception);
        }
    }

    JobSummary authorizeSending(String taskId, String adminId) {
        ProspectingSettingsService.Snapshot configuration = settings.snapshot();
        if (!configuration.emailSendingEnabled()) {
            throw badRequest("O envio de e-mails está desabilitado por AGENDA_EMAIL_SENDING_ENABLED=false.");
        }
        if (!configuration.production()) throw badRequest("Envio bloqueado porque PRODUCAO=false.");
        if (!configuration.enabled()) throw badRequest("Ative AGENDA_PROSPECTING_ENABLED para autorizar envios.");
        if (configuration.dryRun()) throw badRequest("Desative AGENDA_PROSPECTING_DRY_RUN após validar a prévia.");
        if (!configuration.smtpConfigured()) throw badRequest("Configure o SMTP antes de autorizar envios.");
        if (!configuration.geocoderProductionReady()) {
            throw badRequest("Configure um geocodificador de produção. O provedor mock e o Nominatim público não podem ser usados para envio real.");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE agenda_prospecting_jobs SET state='READY',dry_run=FALSE,send_authorized=TRUE,
                      authorized_by=?,authorized_at=CURRENT_TIMESTAMP,not_before=CURRENT_TIMESTAMP,current_round=1,
                      next_round_at=NULL,updated_at=CURRENT_TIMESTAMP,last_error='' WHERE task_id=? AND state='DRY_RUN'
                    """)) {
                update.setString(1, adminId);
                update.setString(2, taskId);
                if (update.executeUpdate() == 0) throw badRequest("Execute e conclua a simulação antes de autorizar o envio.");
            }
            try (PreparedStatement queued = connection.prepareStatement("""
                    UPDATE agenda_external_invitations SET status='QUEUED',failure_reason='',updated_at=CURRENT_TIMESTAMP
                    WHERE task_id=? AND round_number=1 AND status='DRY_RUN'
                    """)) {
                queued.setString(1, taskId);
                queued.executeUpdate();
            }
            connection.commit();
            JobSummary result = summary(taskId);
            processLogs.warn(result.id(), "READY", "SEND_AUTHORIZED",
                    "Primeira rodada autorizada administrativamente; as demais permanecem bloqueadas até o prazo útil.");
            return result;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw serverError("Não foi possível autorizar o envio.", exception);
        }
    }

    JobSummary cancel(String taskId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE agenda_prospecting_jobs SET state='CANCELLED',cancelled_at=CURRENT_TIMESTAMP,
                      completed_at=CURRENT_TIMESTAMP,next_round_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE task_id=?
                      AND state NOT IN ('SENT','RESPONDED','EXHAUSTED','CANCELLED')
                    """)) {
                update.setString(1, taskId);
                if (update.executeUpdate() == 0) throw badRequest("O processamento não pode mais ser cancelado.");
            }
            try (PreparedStatement invitations = connection.prepareStatement("""
                    UPDATE agenda_external_invitations SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP
                    WHERE task_id=? AND status IN ('SELECTED','DRY_RUN','QUEUED','WAITING_ROUND')
                    """)) {
                invitations.setString(1, taskId);
                invitations.executeUpdate();
            }
            connection.commit();
            JobSummary result = summary(taskId);
            processLogs.warn(result.id(), "CANCELLED", "JOB_CANCELLED_BY_ADMIN",
                    "Processamento e convites pendentes cancelados pelo administrador.");
            return result;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw serverError("Não foi possível cancelar o processamento.", exception);
        }
    }

    JobSummary summary(String taskId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT j.id,j.task_id,j.state,j.dry_run,j.manual_trigger,j.send_authorized,
                       j.records_analyzed,j.filtered_cnae,j.filtered_email,j.filtered_address,j.inside_radius,
                       j.selected_count,j.prepared_count,j.sent_count,j.failure_count,j.optout_count,
                       j.registration_count,j.current_round,j.next_round_at,j.ai_provider,j.ai_candidates_count,
                       j.last_error,j.created_at,j.updated_at,j.completed_at,
                       s.id specialty_id,s.name specialty_name
                     FROM agenda_prospecting_jobs j JOIN agenda_specialties s ON s.id=j.specialty_id
                     WHERE j.task_id=?
                     """)) {
            query.setString(1, taskId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return JobSummary.none(taskId);
                return mapSummary(rows);
            }
        } catch (SQLException exception) {
            throw serverError("Não foi possível carregar o processamento da tarefa.", exception);
        }
    }

    List<InvitationPreview> preview(String taskId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT i.id,i.status,i.round_number,i.source,i.source_url,i.distance_km,i.matched_cnae,i.cnae_match_type,
                       COALESCE(NULLIF(p.trade_name,''),NULLIF(w.display_name,''),'Contato externo') establishment,
                       i.email_ciphertext,s.name specialty_name,
                       COALESCE(NULLIF(p.municipality_name,''),w.municipality_name,'') municipality_name,
                       COALESCE(NULLIF(p.uf,''),w.uf,'') uf
                     FROM agenda_external_invitations i
                     LEFT JOIN agenda_cnpj_prospects p ON p.id=i.prospect_id
                     LEFT JOIN agenda_web_prospects w ON w.id=i.web_prospect_id
                     JOIN agenda_specialties s ON s.id=i.specialty_id
                     WHERE i.task_id=? ORDER BY i.round_number,i.distance_km,i.created_at
                     """)) {
            query.setString(1, taskId);
            List<InvitationPreview> values = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String email = crypto.decrypt(rows.getString("email_ciphertext"));
                    String status = rows.getString("status");
                    String message = "WAITING_ROUND".equals(status)
                            ? "Aguardando a liberação da rodada; nenhum contato foi enviado."
                            : "Prévia sem envio. O link real será criado somente no momento do envio autorizado.";
                    values.add(new InvitationPreview(rows.getString("id"), status,
                            rows.getInt("round_number"), rows.getString("source"), rows.getString("source_url"),
                            maskEmail(email), clean(rows.getString("establishment"), 120), rows.getDouble("distance_km"),
                            rows.getString("matched_cnae"), rows.getString("cnae_match_type"),
                            rows.getString("specialty_name"), rows.getString("municipality_name") + "/" + rows.getString("uf"), message));
                }
            }
            return values;
        } catch (SQLException exception) {
            throw serverError("Não foi possível carregar a prévia.", exception);
        }
    }

    List<SuppressionInfo> suppressions() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT email_hash,scope,reason,origin,requested_by_holder,created_at
                     FROM agenda_email_suppressions ORDER BY created_at DESC LIMIT 500
                     """)) {
            List<SuppressionInfo> values = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) values.add(new SuppressionInfo(rows.getString(1).substring(0, 12) + "…",
                        rows.getString(2), rows.getString(3), rows.getString(4), rows.getBoolean(5),
                        rows.getTimestamp(6).toLocalDateTime().toString()));
            }
            return values;
        } catch (SQLException exception) {
            throw serverError("Não foi possível carregar a lista de supressão.", exception);
        }
    }

    Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            result.put("catalogRecords", scalar(connection, "SELECT COUNT(*) FROM agenda_cnpj_catalog"));
            result.put("catalogCurrent", scalar(connection, "SELECT COUNT(*) FROM agenda_cnpj_catalog WHERE source_current=TRUE"));
            result.put("prospects", scalar(connection, "SELECT COUNT(*) FROM agenda_cnpj_prospects WHERE active=TRUE"));
            result.put("webProspects", scalar(connection, "SELECT COUNT(*) FROM agenda_web_prospects"));
            result.put("geocoded", scalar(connection, "SELECT COUNT(*) FROM agenda_cnpj_prospects WHERE geocode_status='VALID'"));
            result.put("jobs", scalar(connection, "SELECT COUNT(*) FROM agenda_prospecting_jobs"));
            result.put("sent", scalar(connection, "SELECT COUNT(*) FROM agenda_external_invitations WHERE status='SENT'"));
            result.put("optOuts", scalar(connection, "SELECT COUNT(*) FROM agenda_email_suppressions"));
            result.put("registrations", scalar(connection, "SELECT COUNT(*) FROM agenda_external_invitations WHERE status='REGISTERED'"));
            return result;
        } catch (SQLException exception) {
            throw serverError("Não foi possível carregar as métricas.", exception);
        }
    }

    boolean isTaskOwner(String userId, String taskId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("SELECT 1 FROM agenda_tasks WHERE id=? AND owner_id=?")) {
            query.setString(1, taskId);
            query.setString(2, userId);
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        } catch (SQLException exception) { return false; }
    }

    boolean canViewTask(String userId, String taskId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT 1 FROM agenda_external_invitations
                     WHERE task_id=? AND registered_user_id=? AND status='REGISTERED' LIMIT 1
                     """)) {
            query.setString(1, taskId);
            query.setString(2, userId);
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        } catch (SQLException exception) { return false; }
    }

    InvitationContext invitationContext(String rawToken) {
        String hash = ProspectingValidation.sha256(validateToken(rawToken));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT i.id,i.status,i.expires_at,i.distance_km,i.email_ciphertext,
                       COALESCE(NULLIF(p.municipality_name,''),w.municipality_name,'') municipality_name,
                       COALESCE(NULLIF(p.uf,''),w.uf,'') uf,s.name specialty_name,t.date_label,t.task_status
                     FROM agenda_external_invitations i
                     LEFT JOIN agenda_cnpj_prospects p ON p.id=i.prospect_id
                     LEFT JOIN agenda_web_prospects w ON w.id=i.web_prospect_id
                     JOIN agenda_specialties s ON s.id=i.specialty_id
                     JOIN (SELECT id,TO_CHAR(starts_at,'DD/MM/YYYY') date_label,task_status FROM agenda_tasks) t ON t.id=i.task_id
                     WHERE i.token_hash=?
                     """)) {
            query.setString(1, hash);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Convite inválido.");
                LocalDateTime expires = rows.getTimestamp("expires_at").toLocalDateTime();
                if (expires.isBefore(LocalDateTime.now())) throw badRequest("Este convite expirou.");
                if (!Set.of("SENT", "REGISTERED").contains(rows.getString("status"))) throw badRequest("Este convite não está disponível.");
                if (!"ACTIVE".equals(rows.getString("task_status"))) throw badRequest("A demanda não está mais aberta.");
                try (PreparedStatement opened = connection.prepareStatement("""
                        UPDATE agenda_external_invitations SET opened_at=COALESCE(opened_at,CURRENT_TIMESTAMP)
                        WHERE id=?
                        """)) {
                    opened.setString(1, rows.getString("id"));
                    opened.executeUpdate();
                }
                String email = crypto.decrypt(rows.getString("email_ciphertext"));
                return new InvitationContext(rows.getString("specialty_name"),
                        rows.getString("municipality_name") + "/" + rows.getString("uf"),
                        rows.getDouble("distance_km"), rows.getString("date_label"), email,
                        "Mantenha este mesmo e-mail ao criar ou acessar sua conta para associar a demanda.");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw serverError("Não foi possível abrir o convite.", exception);
        }
    }

    void validateInvitationEmail(String rawToken, String accountEmail) {
        String tokenHash = ProspectingValidation.sha256(validateToken(rawToken));
        String normalizedEmail = ProspectingValidation.normalizeEmail(accountEmail);
        if (!ProspectingValidation.validateEmail(normalizedEmail, false).valid()) {
            throw badRequest("Informe um e-mail válido para usar o convite.");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT email_ciphertext,status,expires_at FROM agenda_external_invitations
                     WHERE token_hash=?
                     """)) {
            query.setString(1, tokenHash);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw badRequest("Convite inválido.");
                LocalDateTime expiresAt = rows.getTimestamp("expires_at").toLocalDateTime();
                if (!expiresAt.isAfter(LocalDateTime.now())) throw badRequest("O convite expirou.");
                if (!Set.of("SENT", "REGISTERED").contains(rows.getString("status"))) {
                    throw badRequest("O convite não pode ser associado.");
                }
                String expected = ProspectingValidation.normalizeEmail(crypto.decrypt(rows.getString("email_ciphertext")));
                if (!expected.equals(normalizedEmail)) {
                    throw badRequest("Use o mesmo e-mail informado no convite para associar a demanda.");
                }
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw serverError("Não foi possível validar o convite.", exception);
        }
    }

    void registerInvitation(String rawToken, String accountEmail, String userId) {
        if (rawToken == null || rawToken.isBlank()) return;
        String tokenHash = ProspectingValidation.sha256(validateToken(rawToken));
        String normalizedEmail = ProspectingValidation.normalizeEmail(accountEmail);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            String jobId;
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT id,job_id,email_ciphertext,status,expires_at FROM agenda_external_invitations
                    WHERE token_hash=? FOR UPDATE
                    """)) {
                query.setString(1, tokenHash);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw badRequest("Convite inválido.");
                    String expected = ProspectingValidation.normalizeEmail(crypto.decrypt(rows.getString("email_ciphertext")));
                    if (!expected.equals(normalizedEmail)) {
                        throw badRequest("Use o mesmo e-mail informado no convite para associar a demanda.");
                    }
                    if (rows.getTimestamp("expires_at").toLocalDateTime().isBefore(LocalDateTime.now())) throw badRequest("O convite expirou.");
                    if (!Set.of("SENT", "REGISTERED").contains(rows.getString("status"))) throw badRequest("O convite não pode ser associado.");
                    String invitationId = rows.getString("id");
                    jobId = rows.getString("job_id");
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE agenda_external_invitations SET status='REGISTERED',registered_at=COALESCE(registered_at,CURRENT_TIMESTAMP),
                              registered_user_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
                            """)) {
                        update.setString(1, userId);
                        update.setString(2, invitationId);
                        update.executeUpdate();
                    }
                }
            }
            try (PreparedStatement stopLater = connection.prepareStatement("""
                    UPDATE agenda_external_invitations SET status='CANCELLED_RESPONSE',updated_at=CURRENT_TIMESTAMP
                    WHERE job_id=? AND status IN ('WAITING_ROUND','QUEUED')
                    """)) {
                stopLater.setString(1, jobId);
                stopLater.executeUpdate();
            }
            try (PreparedStatement count = connection.prepareStatement("""
                    UPDATE agenda_prospecting_jobs j SET state='RESPONDED',registration_count=(
                      SELECT COUNT(*) FROM agenda_external_invitations i WHERE i.job_id=j.id AND i.status='REGISTERED'
                    ),next_round_at=NULL,completed_at=CURRENT_TIMESTAMP,locked_at=NULL,lock_owner='',updated_at=CURRENT_TIMESTAMP
                    WHERE j.id=?
                    """)) {
                count.setString(1, jobId);
                count.executeUpdate();
            }
            connection.commit();
            processLogs.info(jobId, "RESPONDED", "EXTERNAL_RESPONSE_RECEIVED",
                    "Um prestador respondeu positivamente; rodadas posteriores foram canceladas.");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw serverError("Não foi possível associar o convite ao cadastro.", exception);
        }
    }

    OptOutContext optOutContext(String rawToken) {
        String hash = ProspectingValidation.sha256(validateToken(rawToken));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT status,email_ciphertext FROM agenda_external_invitations WHERE optout_token_hash=?
                     """)) {
            query.setString(1, hash);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Link de descadastro inválido.");
                return new OptOutContext(maskEmail(crypto.decrypt(rows.getString("email_ciphertext"))),
                        "Confirme para não receber novos convites do AgendaFaz.");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw serverError("Não foi possível abrir o descadastro.", exception);
        }
    }

    void confirmOptOut(String rawToken) {
        String hash = ProspectingValidation.sha256(validateToken(rawToken));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            String emailHash;
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT email_hash FROM agenda_external_invitations WHERE optout_token_hash=? FOR UPDATE
                    """)) {
                query.setString(1, hash);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Link de descadastro inválido.");
                    emailHash = rows.getString(1);
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO agenda_email_suppressions(email_hash,scope,specialty_id,reason,origin,requested_by_holder)
                    VALUES(?,'GLOBAL',NULL,'OPT_OUT','PUBLIC_LINK',TRUE)
                    ON CONFLICT DO NOTHING
                    """)) {
                insert.setString(1, emailHash);
                insert.executeUpdate();
            }
            try (PreparedStatement cancel = connection.prepareStatement("""
                    UPDATE agenda_external_invitations SET status='OPTED_OUT',opted_out_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                    WHERE email_hash=? AND status IN ('SELECTED','DRY_RUN','QUEUED','WAITING_ROUND','SENT')
                    """)) {
                cancel.setString(1, emailHash);
                cancel.executeUpdate();
            }
            try (PreparedStatement count = connection.prepareStatement("""
                    UPDATE agenda_prospecting_jobs j SET optout_count=(
                      SELECT COUNT(*) FROM agenda_external_invitations i WHERE i.job_id=j.id AND i.status='OPTED_OUT'
                    ),updated_at=CURRENT_TIMESTAMP
                    WHERE EXISTS (SELECT 1 FROM agenda_external_invitations i WHERE i.job_id=j.id AND i.email_hash=?)
                    """)) {
                count.setString(1, emailHash);
                count.executeUpdate();
            }
            connection.commit();
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw serverError("Não foi possível concluir o descadastro.", exception);
        }
    }

    @Scheduled(fixedDelayString = "${agenda.prospecting.poll-ms:10000}")
    void poll() {
        if (!workerRunning.compareAndSet(false, true)) return;
        try {
            expireInvitations();
            recoverStaleInvitationSends();
            recoverStaleJobs();
            Job job = claimNext();
            if (job == null) return;
            if ("PENDING".equals(job.state())) prepare(job);
            else if ("READY".equals(job.state())) send(job);
            else if ("WAITING_RESPONSE".equals(job.state())) advanceRound(job);
        } finally {
            workerRunning.set(false);
        }
    }

    private Job claimNext() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT id,task_id,specialty_id,state,dry_run,manual_trigger,send_authorized,current_round
                    FROM agenda_prospecting_jobs
                    WHERE state IN ('PENDING','READY','WAITING_RESPONSE') AND not_before<=CURRENT_TIMESTAMP
                      AND (locked_at IS NULL OR locked_at<CURRENT_TIMESTAMP-INTERVAL '10 minutes')
                    ORDER BY not_before,created_at FOR UPDATE SKIP LOCKED LIMIT 1
                    """)) {
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) { connection.rollback(); return null; }
                    Job job = new Job(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getString(4),
                            rows.getBoolean(5), rows.getBoolean(6), rows.getBoolean(7), rows.getInt(8));
                    try (PreparedStatement lock = connection.prepareStatement("""
                            UPDATE agenda_prospecting_jobs SET locked_at=CURRENT_TIMESTAMP,lock_owner=?,attempts=attempts+1,
                              updated_at=CURRENT_TIMESTAMP WHERE id=?
                            """)) {
                        lock.setString(1, workerId);
                        lock.setString(2, job.id());
                        lock.executeUpdate();
                    }
                    connection.commit();
                    processLogs.info(job.id(), job.state(), "JOB_CLAIMED",
                            "Job reservado pelo worker para execução.", null, null,
                            Map.of("worker", workerId, "state", job.state(), "round", job.currentRound()));
                    return job;
                }
            } catch (SQLException exception) {
                connection.rollback();
                return null;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) { return null; }
    }

    private void prepare(Job job) {
        long startedAt = System.nanoTime();
        ProspectingSettingsService.Snapshot configuration = settings.snapshot();
        processLogs.info(job.id(), "PENDING", "PROCESS_STARTED",
                "Processamento da demanda iniciado.", null, 0L,
                Map.of("manual", job.manualTrigger(), "dryRun", job.dryRun(), "triggerMode", configuration.triggerMode()));
        if (!job.manualTrigger()) {
            if (!configuration.automaticDryRunEnabled() || "MANUAL".equals(configuration.triggerMode())) {
                processLogs.info(job.id(), "PENDING", "PROCESS_DEFERRED",
                        "Execução automática adiada pela configuração do modo de acionamento.", null,
                        elapsedMs(startedAt), Map.of("minutes", 15));
                defer(job.id(), 15);
                return;
            }
            if ("AUTO_AFTER_INTERNAL".equals(configuration.triggerMode())) {
                TaskInfo task = task(job.taskId());
                if (!Set.of("OPEN", "REVIEW", "AWAITING_OWNER").contains(task.offerPhase())) {
                    processLogs.info(job.id(), "PENDING", "WAITING_INTERNAL_FLOW",
                            "Aguardando a conclusão da etapa interna de favoritos e ofertas prioritárias.", null,
                            elapsedMs(startedAt), Map.of("minutes", 5, "offerPhase", task.offerPhase()));
                    defer(job.id(), 5);
                    return;
                }
            }
        }
        TaskInfo task = task(job.taskId());
        processLogs.info(job.id(), "PENDING", "TASK_VALIDATED",
                "Atividade carregada e validada para processamento.", null, elapsedMs(startedAt),
                Map.of("taskStatus", task.status(), "offerPhase", task.offerPhase(), "specialtyId", task.specialtyId()));
        if (!taskOpen(task)) {
            cancelWithReason(job.id(), "A tarefa foi cancelada, encerrada ou preenchida.");
            return;
        }
        if (internalCandidatesSufficient(task)) {
            cancelWithReason(job.id(), "Já existem candidatos internos suficientes.");
            return;
        }
        if (!crypto.configured()) {
            failJob(job.id(), "AGENDA_PROSPECT_DATA_KEY não configurada.");
            return;
        }
        try {
            long materialized = materializeCatalogProspects(job.specialtyId(), configuration);
            if (materialized > 0) {
                processLogs.info(job.id(), "FILTERING", "CNPJ_CATALOG_MATERIALIZED",
                        "Registros elegíveis foram materializados do catálogo integral CNPJ/CNAE.", materialized, null,
                        Map.of("materialized", materialized));
            }
        } catch (SQLException exception) {
            processLogs.warn(job.id(), "FILTERING", "CNPJ_CATALOG_MATERIALIZE_FAILED",
                    "Não foi possível materializar o catálogo integral; a base de prospecção existente continuará sendo usada.");
        }
        long importedProspects = activeProspectCount();
        if (importedProspects == 0) {
            processLogs.warn(job.id(), "FILTERING", "CNPJ_BASE_EMPTY",
                    "A base de prospecção CNPJ está vazia; a pesquisa por IA continuará disponível como fonte complementar.",
                    0L, elapsedMs(startedAt), Map.of("activeProspects", 0));
        } else {
            processLogs.info(job.id(), "FILTERING", "CNPJ_BASE_AVAILABLE",
                    "Base pública do CNPJ disponível para filtragem.", importedProspects,
                    elapsedMs(startedAt), Map.of("activeProspects", importedProspects));
        }
        try {
            setState(job.id(), "FILTERING", "");
            SelectionResult selection = select(task, job.specialtyId(), configuration, job.id());
            replaceDryRunInvitations(job, task, selection.selected(), configuration);
            processLogs.info(job.id(), job.dryRun() ? "DRY_RUN" : "READY", "INVITATIONS_PREPARED",
                    "Prévia de convites preparada sem envio de e-mail.", (long) selection.selected().size(),
                    elapsedMs(startedAt), Map.of("selected", selection.selected().size(), "dryRun", job.dryRun()));
            updateSelectionMetrics(job.id(), selection, job.dryRun() ? "DRY_RUN" : "READY");
            processLogs.info(job.id(), job.dryRun() ? "DRY_RUN" : "READY", "PROCESS_COMPLETED",
                    "Processamento concluído com sucesso.", (long) selection.selected().size(), elapsedMs(startedAt),
                    Map.of("analyzed", selection.analyzed(), "insideRadius", selection.insideRadius(),
                            "selected", selection.selected().size()));
        } catch (Exception exception) {
            failJob(job.id(), safeMessage(exception));
        }
    }

    private long materializeCatalogProspects(long specialtyId, ProspectingSettingsService.Snapshot configuration) throws SQLException {
        List<String> municipalities = new ArrayList<>();
        for (String item : configuration.pilotMunicipalities().split("[,;\\r\\n]+")) {
            String normalized = item.trim().toUpperCase(Locale.ROOT);
            if (normalized.matches(".+/[A-Z]{2}")) municipalities.add(normalized);
        }
        if (municipalities.isEmpty()) municipalities.add("SOROCABA/SP");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            long changed;
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO agenda_cnpj_prospects(cnpj,legal_name,trade_name,registration_status,municipality_code,
                      municipality_name,uf,cnae_primary,email_hash,email_ciphertext,email_domain,email_quality_status,
                      address_normalized,address_hash,cep,source_version,source_date,active,updated_at)
                    SELECT DISTINCT c.cnpj,'',c.trade_name,c.registration_status,c.municipality_code,c.municipality_name,
                      c.uf,c.cnae_primary,c.email_hash,c.email_ciphertext,c.email_domain,'VALID',c.address_normalized,
                      c.address_hash,c.cep,c.source_version,c.source_date,TRUE,CURRENT_TIMESTAMP
                    FROM agenda_cnpj_catalog c
                    WHERE c.source_current=TRUE AND c.registration_status='02' AND c.email_quality_status='VALID'
                      AND c.email_hash<>'' AND c.address_normalized<>''
                      AND (c.municipality_name || '/' || c.uf)=ANY(?)
                      AND EXISTS (
                        SELECT 1 FROM agenda_cnpj_catalog_cnaes cc
                        JOIN agenda_specialty_cnaes sc ON sc.cnae_code=cc.cnae_code
                        WHERE cc.cnpj=c.cnpj AND sc.specialty_id=? AND sc.active=TRUE
                          AND ((cc.primary_cnae=TRUE AND sc.match_primary=TRUE)
                            OR (cc.primary_cnae=FALSE AND sc.match_secondary=TRUE))
                      )
                    ON CONFLICT(cnpj) DO UPDATE SET trade_name=EXCLUDED.trade_name,
                      registration_status=EXCLUDED.registration_status,municipality_code=EXCLUDED.municipality_code,
                      municipality_name=EXCLUDED.municipality_name,uf=EXCLUDED.uf,cnae_primary=EXCLUDED.cnae_primary,
                      email_hash=EXCLUDED.email_hash,email_ciphertext=EXCLUDED.email_ciphertext,email_domain=EXCLUDED.email_domain,
                      email_quality_status=CASE WHEN agenda_cnpj_prospects.email_hash=EXCLUDED.email_hash
                        THEN agenda_cnpj_prospects.email_quality_status ELSE 'VALID' END,
                      address_normalized=EXCLUDED.address_normalized,address_hash=EXCLUDED.address_hash,cep=EXCLUDED.cep,
                      source_version=EXCLUDED.source_version,source_date=EXCLUDED.source_date,active=TRUE,updated_at=CURRENT_TIMESTAMP,
                      geocode_status=CASE WHEN agenda_cnpj_prospects.address_hash<>EXCLUDED.address_hash THEN 'PENDING'
                        ELSE agenda_cnpj_prospects.geocode_status END,
                      latitude=CASE WHEN agenda_cnpj_prospects.address_hash<>EXCLUDED.address_hash THEN NULL
                        ELSE agenda_cnpj_prospects.latitude END,
                      longitude=CASE WHEN agenda_cnpj_prospects.address_hash<>EXCLUDED.address_hash THEN NULL
                        ELSE agenda_cnpj_prospects.longitude END
                    """)) {
                upsert.setArray(1, connection.createArrayOf("varchar", municipalities.toArray(String[]::new)));
                upsert.setLong(2, specialtyId);
                changed = upsert.executeUpdate();
            }
            try (PreparedStatement links = connection.prepareStatement("""
                    INSERT INTO agenda_cnpj_prospect_cnaes(prospect_id,cnae_code,primary_cnae)
                    SELECT DISTINCT p.id,cc.cnae_code,cc.primary_cnae
                    FROM agenda_cnpj_catalog c
                    JOIN agenda_cnpj_catalog_cnaes cc ON cc.cnpj=c.cnpj
                    JOIN agenda_specialty_cnaes sc ON sc.cnae_code=cc.cnae_code AND sc.specialty_id=? AND sc.active=TRUE
                    JOIN agenda_cnpj_prospects p ON p.cnpj=c.cnpj
                    WHERE c.source_current=TRUE AND c.registration_status='02' AND c.email_quality_status='VALID'
                      AND c.email_hash<>'' AND c.address_normalized<>''
                      AND (c.municipality_name || '/' || c.uf)=ANY(?)
                      AND ((cc.primary_cnae=TRUE AND sc.match_primary=TRUE)
                        OR (cc.primary_cnae=FALSE AND sc.match_secondary=TRUE))
                    ON CONFLICT(prospect_id,cnae_code) DO UPDATE SET primary_cnae=EXCLUDED.primary_cnae
                    """)) {
                links.setLong(1, specialtyId);
                links.setArray(2, connection.createArrayOf("varchar", municipalities.toArray(String[]::new)));
                links.executeUpdate();
            }
            connection.commit();
            return changed;
        }
    }

    private SelectionResult select(TaskInfo task, long specialtyId, ProspectingSettingsService.Snapshot configuration,
                                   String jobId) throws SQLException {
        List<SpecialtyService.CnaeRule> rules = specialties.cnaeRules(specialtyId);
        if (rules.isEmpty()) {
            processLogs.warn(jobId, "FILTERING", "CNAE_RULES_EMPTY",
                    "A especialidade não possui CNAE mapeado; a fonte CNPJ será ignorada e a pesquisa por IA poderá complementar o pool.");
        } else {
            processLogs.info(jobId, "FILTERING", "CNAE_RULES_LOADED",
                    "Regras de CNAE da especialidade carregadas.", (long) rules.size(), null,
                    Map.of("rules", rules.size(), "specialtyId", specialtyId));
        }
        Map<String, SpecialtyService.CnaeRule> ruleMap = new LinkedHashMap<>();
        for (SpecialtyService.CnaeRule rule : rules) ruleMap.put(rule.code(), rule);

        long analyzed;
        Set<String> existingProviderHashes = existingProviderEmailHashes(specialtyId);
        Set<String> suppressedHashes = suppressionEmailHashes(specialtyId);
        Set<String> alreadyInvitedHashes = taskInvitationEmailHashes(task.id());
        Set<String> cooldownHashes = cooldownEmailHashes(configuration.cooldownDays());
        Set<Long> matchedProspects = new LinkedHashSet<>();
        Set<Long> blockedProspects = new LinkedHashSet<>();
        Map<Long, CandidateProspect> candidates = new LinkedHashMap<>();

        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement count = connection.prepareStatement("""
                    SELECT COUNT(*) FROM agenda_cnpj_prospects
                    WHERE active=TRUE AND registration_status='02'
                    """)) {
                try (ResultSet rows = count.executeQuery()) { rows.next(); analyzed = rows.getLong(1); }
            }
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT p.id,p.email_hash,p.email_ciphertext,p.email_quality_status,p.address_normalized,p.address_hash,
                      p.latitude,p.longitude,p.geocode_status,p.geocode_confidence,p.geocode_precision,p.trade_name,
                      p.municipality_name,p.uf,pc.cnae_code,pc.primary_cnae
                    FROM agenda_cnpj_prospects p
                    JOIN agenda_cnpj_prospect_cnaes pc ON pc.prospect_id=p.id
                    WHERE p.active=TRUE AND p.registration_status='02'
                    ORDER BY p.id,pc.primary_cnae DESC
                    """)) {
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        long id = rows.getLong("id");
                        String cnae = rows.getString("cnae_code");
                        boolean primary = rows.getBoolean("primary_cnae");
                        SpecialtyService.CnaeRule rule = ruleMap.get(cnae);
                        if (rule == null || !ProspectingRules.cnaeMatches(primary, rule.primary(), rule.secondary())) continue;
                        matchedProspects.add(id);
                        String emailHash = rows.getString("email_hash");
                        boolean blocked = !"VALID".equals(rows.getString("email_quality_status"))
                                || emailHash == null || emailHash.isBlank()
                                || existingProviderHashes.contains(emailHash)
                                || suppressedHashes.contains(emailHash)
                                || alreadyInvitedHashes.contains(emailHash)
                                || cooldownHashes.contains(emailHash);
                        if (blocked) {
                            blockedProspects.add(id);
                            candidates.remove(id);
                            continue;
                        }
                        if (blockedProspects.contains(id)) continue;
                        CandidateProspect current = candidates.get(id);
                        if (current == null || (!current.primaryMatch() && primary)) {
                            candidates.put(id, new CandidateProspect(id, "RECEITA_CNPJ", "", 100, emailHash, rows.getString("email_ciphertext"),
                                    rows.getString("address_normalized"), rows.getString("address_hash"),
                                    nullableDouble(rows, "latitude"), nullableDouble(rows, "longitude"),
                                    rows.getString("geocode_status"), nullableDouble(rows, "geocode_confidence"),
                                    rows.getString("geocode_precision"), clean(rows.getString("trade_name"), 120),
                                    rows.getString("municipality_name"), rows.getString("uf"), cnae, primary, 0));
                        }
                    }
                }
            }
        }

        long filteredCnae = Math.max(0, analyzed - matchedProspects.size());
        long filteredEmail = blockedProspects.size();
        long filteredAddress = 0;
        long inside = 0;
        processLogs.info(jobId, "FILTERING", "FILTERING_COMPLETED",
                "Triagem por CNAE, e-mail, cadastro existente, supressão e intervalo mínimo concluída.",
                analyzed, null, Map.of("matchedCnae", matchedProspects.size(), "eligibleForGeocoding", candidates.size(),
                        "filteredCnae", filteredCnae, "filteredEmail", filteredEmail));
        setState(jobId, "GEOCODING", "");
        processLogs.info(jobId, "GEOCODING", "GEOCODING_STARTED",
                "Geocodificação e cálculo de distância iniciados.", (long) candidates.size(), null,
                Map.of("candidates", candidates.size(), "radiusKm", configuration.radiusKm()));
        List<CandidateProspect> eligible = new ArrayList<>();
        int geocodingProcessed = 0;
        int geocodingRejected = 0;
        for (CandidateProspect candidate : candidates.values()) {
            CandidateProspect geocoded = ensureGeocoded(candidate, configuration);
            geocodingProcessed++;
            boolean invalidGeocode = !"VALID".equals(geocoded.geocodeStatus())
                    || geocoded.latitude() == null || geocoded.longitude() == null
                    || geocoded.confidence() == null || geocoded.confidence() < configuration.minConfidence()
                    || !ProspectingRules.addressLevelPrecision(geocoded.precision());
            if (invalidGeocode) {
                filteredAddress++;
                geocodingRejected++;
            } else {
                double distance = AgendaService.distanceKm(task.latitude(), task.longitude(),
                        geocoded.latitude(), geocoded.longitude());
                if (distance <= configuration.radiusKm()) {
                    inside++;
                    eligible.add(geocoded.withDistance(distance));
                }
            }
            if (geocodingProcessed % 25 == 0 || geocodingProcessed == candidates.size()) {
                processLogs.info(jobId, "GEOCODING", "GEOCODING_PROGRESS",
                        "Progresso da geocodificação atualizado.", (long) geocodingProcessed, null,
                        Map.of("processed", geocodingProcessed, "total", candidates.size(),
                                "invalidAddressOrPrecision", geocodingRejected, "insideRadius", inside));
            }
        }
        if (eligible.size() < configuration.perTaskLimit()) {
            int missing = configuration.perTaskLimit() - eligible.size();
            AiProspectingService.SearchResult aiResult = aiProspecting.search(new AiProspectingService.SearchRequest(
                    task.specialtyName(), task.title(), task.description(), searchLocation(configuration), missing));
            processLogs.info(jobId, "AI_SEARCH", "AI_SEARCH_COMPLETED",
                    "Pesquisa complementar por IA concluída.", (long) aiResult.candidates().size(), null,
                    Map.of("provider", aiResult.provider(), "candidates", aiResult.candidates().size(),
                            "ollamaAttempted", aiResult.ollamaAttempted(), "openAiAttempted", aiResult.openAiAttempted()));
            if (!aiResult.warning().isBlank()) {
                processLogs.warn(jobId, "AI_SEARCH", "AI_SEARCH_WARNING", clean(aiResult.warning(), 500));
            }
            Set<String> seenHashes = new LinkedHashSet<>();
            for (CandidateProspect current : eligible) seenHashes.add(current.emailHash());
            int aiInside = 0;
            for (AiProspectingService.Candidate aiCandidate : aiResult.candidates()) {
                String normalizedEmail = ProspectingValidation.normalizeEmail(aiCandidate.email());
                if (!ProspectingValidation.validateEmail(normalizedEmail, false).valid()) continue;
                String emailHash = ProspectingValidation.sha256(normalizedEmail);
                if (seenHashes.contains(emailHash) || existingProviderHashes.contains(emailHash)
                        || suppressedHashes.contains(emailHash) || alreadyInvitedHashes.contains(emailHash)
                        || cooldownHashes.contains(emailHash)) continue;
                CandidateProspect webCandidate = upsertWebProspect(aiCandidate);
                CandidateProspect geocoded = ensureGeocoded(webCandidate, configuration);
                boolean invalidGeocode = !"VALID".equals(geocoded.geocodeStatus())
                        || geocoded.latitude() == null || geocoded.longitude() == null
                        || geocoded.confidence() == null || geocoded.confidence() < configuration.minConfidence()
                        || !ProspectingRules.addressLevelPrecision(geocoded.precision());
                if (invalidGeocode) {
                    filteredAddress++;
                    continue;
                }
                double distance = AgendaService.distanceKm(task.latitude(), task.longitude(),
                        geocoded.latitude(), geocoded.longitude());
                if (distance <= configuration.radiusKm()) {
                    eligible.add(geocoded.withDistance(distance));
                    seenHashes.add(emailHash);
                    inside++;
                    aiInside++;
                }
            }
            updateAiMetrics(jobId, aiResult.provider(), aiInside);
        }
        eligible.sort(Comparator.comparingDouble(CandidateProspect::distanceKm)
                .thenComparing(Comparator.comparingDouble(CandidateProspect::relevanceScore).reversed())
                .thenComparing(Comparator.comparingDouble((CandidateProspect value) -> value.confidence() == null ? 0 : value.confidence()).reversed())
                .thenComparing(Comparator.comparing(CandidateProspect::primaryMatch).reversed()));
        List<CandidateProspect> selected = ProspectingRules.distinctLimited(eligible,
                CandidateProspect::emailHash, configuration.perTaskLimit());
        processLogs.info(jobId, "GEOCODING", "SELECTION_COMPLETED",
                "Ordenação por distância e seleção final concluídas.", (long) selected.size(), null,
                Map.of("insideRadius", inside, "selected", selected.size(), "limit", configuration.perTaskLimit()));
        return new SelectionResult(analyzed, filteredCnae, filteredEmail, filteredAddress, inside, selected);
    }

    private String searchLocation(ProspectingSettingsService.Snapshot configuration) {
        String configured = configuration.pilotMunicipalities();
        if (configured == null || configured.isBlank()) return "Brasil";
        String first = configured.split("[,;\\r\\n]+", 2)[0].trim();
        return first.isBlank() ? "Brasil" : first;
    }

    private CandidateProspect upsertWebProspect(AiProspectingService.Candidate value) throws SQLException {
        String email = ProspectingValidation.normalizeEmail(value.email());
        String emailHash = ProspectingValidation.sha256(email);
        String address = clean(value.address(), 500);
        String municipality = clean(value.municipality(), 120).toUpperCase(Locale.ROOT);
        String uf = clean(value.uf(), 2).toUpperCase(Locale.ROOT);
        if (!municipality.isBlank() && !address.toUpperCase(Locale.ROOT).contains(municipality)) {
            address = clean(address + ", " + municipality + (uf.isBlank() ? "" : "/" + uf), 500);
        }
        String addressHash = ProspectingValidation.sha256(address);
        String sourceUrl = clean(value.sourceUrl(), 1000);
        String identityHash = ProspectingValidation.sha256(email + "|" + sourceUrl);
        String encryptedEmail = crypto.encrypt(email);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement upsert = connection.prepareStatement("""
                     INSERT INTO agenda_web_prospects(identity_hash,display_name,email_hash,email_ciphertext,email_domain,
                       phone,address_normalized,address_hash,municipality_name,uf,source_url,source_title,source_provider,
                       relevance_score,updated_at)
                     VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                     ON CONFLICT(identity_hash) DO UPDATE SET display_name=EXCLUDED.display_name,
                       email_hash=EXCLUDED.email_hash,email_ciphertext=EXCLUDED.email_ciphertext,
                       email_domain=EXCLUDED.email_domain,phone=EXCLUDED.phone,address_normalized=EXCLUDED.address_normalized,
                       address_hash=EXCLUDED.address_hash,municipality_name=EXCLUDED.municipality_name,uf=EXCLUDED.uf,
                       source_url=EXCLUDED.source_url,source_title=EXCLUDED.source_title,source_provider=EXCLUDED.source_provider,
                       relevance_score=EXCLUDED.relevance_score,updated_at=CURRENT_TIMESTAMP,
                       geocode_status=CASE WHEN agenda_web_prospects.address_hash<>EXCLUDED.address_hash THEN 'PENDING'
                         ELSE agenda_web_prospects.geocode_status END,
                       latitude=CASE WHEN agenda_web_prospects.address_hash<>EXCLUDED.address_hash THEN NULL ELSE agenda_web_prospects.latitude END,
                       longitude=CASE WHEN agenda_web_prospects.address_hash<>EXCLUDED.address_hash THEN NULL ELSE agenda_web_prospects.longitude END
                     RETURNING id,latitude,longitude,geocode_status,geocode_confidence,geocode_precision
                     """)) {
            int index = 1;
            upsert.setString(index++, identityHash);
            upsert.setString(index++, clean(value.name(), 250));
            upsert.setString(index++, emailHash);
            upsert.setString(index++, encryptedEmail);
            int at = email.indexOf('@');
            upsert.setString(index++, at >= 0 ? email.substring(at + 1) : "");
            upsert.setString(index++, clean(value.phone(), 60));
            upsert.setString(index++, address);
            upsert.setString(index++, addressHash);
            upsert.setString(index++, municipality);
            upsert.setString(index++, uf);
            upsert.setString(index++, sourceUrl);
            upsert.setString(index++, clean(value.sourceTitle(), 500));
            upsert.setString(index++, clean(value.provider(), 40));
            upsert.setDouble(index, Math.max(0, Math.min(value.score(), 100)));
            try (ResultSet rows = upsert.executeQuery()) {
                rows.next();
                return new CandidateProspect(rows.getLong("id"), clean(value.provider(), 40), sourceUrl,
                        Math.max(0, Math.min(value.score(), 100)), emailHash, encryptedEmail, address, addressHash,
                        nullableDouble(rows, "latitude"), nullableDouble(rows, "longitude"), rows.getString("geocode_status"),
                        nullableDouble(rows, "geocode_confidence"), rows.getString("geocode_precision"),
                        clean(value.name(), 120), municipality, uf, "", false, 0);
            }
        }
    }

    private void updateAiMetrics(String jobId, String provider, int count) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_prospecting_jobs SET ai_provider=?,ai_candidates_count=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
                     """)) {
            update.setString(1, clean(provider, 40));
            update.setInt(2, Math.max(0, count));
            update.setString(3, jobId);
            update.executeUpdate();
        } catch (SQLException ignored) { }
    }

    private CandidateProspect ensureGeocoded(CandidateProspect candidate, ProspectingSettingsService.Snapshot configuration) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement cache = connection.prepareStatement("""
                    SELECT latitude,longitude,confidence,precision_code,status,provider,error_reason
                    FROM agenda_geocoding_cache WHERE address_hash=?
                    """)) {
                cache.setString(1, candidate.addressHash());
                try (ResultSet rows = cache.executeQuery()) {
                    if (rows.next()) {
                        Double latitude = nullableDouble(rows, "latitude");
                        Double longitude = nullableDouble(rows, "longitude");
                        Double confidence = nullableDouble(rows, "confidence");
                        String precision = rows.getString("precision_code");
                        String status = rows.getString("status");
                        if (ProspectingRules.reusableGeocodeCache(status, latitude, longitude, confidence,
                                configuration.minConfidence(), precision)) {
                            applyCandidateGeocode(connection, candidate, latitude, longitude, confidence, precision, status,
                                    rows.getString("provider"), rows.getString("error_reason"));
                            return candidate.withGeocode(latitude, longitude, confidence, precision, status);
                        }
                    }
                }
            }
            Geocoder.Result result = geocoder.geocode(candidate.address());
            String status = result.success() && ProspectingRules.addressLevelPrecision(result.precision())
                    && result.confidence() >= configuration.minConfidence() ? "VALID" : "INVALID";
            String error = result.error();
            if (result.success() && !ProspectingRules.addressLevelPrecision(result.precision())) {
                error = "Precisão insuficiente: " + clean(result.precision(), 40);
            }
            try (PreparedStatement cache = connection.prepareStatement("""
                    INSERT INTO agenda_geocoding_cache(address_hash,address_normalized,latitude,longitude,provider,confidence,
                      precision_code,status,error_reason,geocoded_at)
                    VALUES(?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                    ON CONFLICT(address_hash) DO UPDATE SET address_normalized=EXCLUDED.address_normalized,
                      latitude=EXCLUDED.latitude,longitude=EXCLUDED.longitude,provider=EXCLUDED.provider,
                      confidence=EXCLUDED.confidence,precision_code=EXCLUDED.precision_code,status=EXCLUDED.status,
                      error_reason=EXCLUDED.error_reason,geocoded_at=CURRENT_TIMESTAMP
                    """)) {
                cache.setString(1, candidate.addressHash());
                cache.setString(2, candidate.address());
                setNullable(cache, 3, result.success() ? result.latitude() : null);
                setNullable(cache, 4, result.success() ? result.longitude() : null);
                cache.setString(5, result.provider());
                setNullable(cache, 6, result.confidence());
                cache.setString(7, clean(result.precision(), 40));
                cache.setString(8, status);
                cache.setString(9, clean(error, 500));
                cache.executeUpdate();
            }
            applyCandidateGeocode(connection, candidate, result.success() ? result.latitude() : null,
                    result.success() ? result.longitude() : null, result.confidence(), result.precision(), status,
                    result.provider(), error);
            return candidate.withGeocode(result.success() ? result.latitude() : null,
                    result.success() ? result.longitude() : null, result.confidence(), result.precision(), status);
        }
    }

    private void applyCandidateGeocode(Connection connection, CandidateProspect candidate, Double lat, Double lon, Double confidence,
                                       String precision, String status, String provider, String error) throws SQLException {
        if (candidate.webSource()) {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE agenda_web_prospects SET latitude=?,longitude=?,geocode_confidence=?,geocode_precision=?,
                      geocode_status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
                    """)) {
                setNullable(update, 1, lat); setNullable(update, 2, lon); setNullable(update, 3, confidence);
                update.setString(4, clean(precision, 40)); update.setString(5, status); update.setLong(6, candidate.id());
                update.executeUpdate();
            }
        } else {
            applyGeocode(connection, candidate.id(), lat, lon, confidence, precision, status, provider, error);
        }
    }

    private void applyGeocode(Connection connection, long prospectId, Double lat, Double lon, Double confidence,
                              String precision, String status, String provider, String error) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE agenda_cnpj_prospects SET latitude=?,longitude=?,geocode_confidence=?,geocode_precision=?,
                  geocode_status=?,geocode_provider=?,geocode_error=?,geocoded_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """)) {
            setNullable(update, 1, lat); setNullable(update, 2, lon); setNullable(update, 3, confidence);
            update.setString(4, clean(precision, 40)); update.setString(5, status); update.setString(6, clean(provider, 80));
            update.setString(7, clean(error, 500)); update.setLong(8, prospectId); update.executeUpdate();
        }
    }

    private void replaceDryRunInvitations(Job job, TaskInfo task, List<CandidateProspect> selected,
                                          ProspectingSettingsService.Snapshot configuration) throws SQLException {
        int roundSize = configuration.contactRoundSize();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM agenda_external_invitations WHERE job_id=? AND status IN
                      ('DRY_RUN','SELECTED','QUEUED','WAITING_ROUND','FAILED','SUPPRESSED','CANCELLED_RESPONSE')
                    """)) {
                delete.setString(1, job.id()); delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO agenda_external_invitations(id,job_id,task_id,specialty_id,prospect_id,web_prospect_id,
                      email_hash,email_ciphertext,distance_km,matched_cnae,cnae_match_type,token_hash,optout_token_hash,
                      status,expires_at,round_number,source,source_url)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(task_id,email_hash) DO NOTHING
                    """)) {
                int position = 0;
                for (CandidateProspect candidate : selected) {
                    int round = (position / roundSize) + 1;
                    String status;
                    if (round == 1) status = job.dryRun() ? "DRY_RUN" : "QUEUED";
                    else status = "WAITING_ROUND";
                    int i = 1;
                    insert.setString(i++, UUID.randomUUID().toString());
                    insert.setString(i++, job.id()); insert.setString(i++, task.id()); insert.setLong(i++, job.specialtyId());
                    if (candidate.webSource()) { insert.setNull(i++, java.sql.Types.BIGINT); insert.setLong(i++, candidate.id()); }
                    else { insert.setLong(i++, candidate.id()); insert.setNull(i++, java.sql.Types.BIGINT); }
                    insert.setString(i++, candidate.emailHash()); insert.setString(i++, candidate.emailCiphertext());
                    insert.setDouble(i++, candidate.distanceKm()); insert.setString(i++, candidate.matchedCnae());
                    insert.setString(i++, candidate.primaryMatch() ? "PRIMARY" : (candidate.webSource() ? "WEB" : "SECONDARY"));
                    insert.setString(i++, ProspectingValidation.sha256(randomToken()));
                    insert.setString(i++, ProspectingValidation.sha256(randomToken()));
                    insert.setString(i++, status);
                    insert.setTimestamp(i++, Timestamp.valueOf(LocalDateTime.now().plus(configuration.tokenDuration())));
                    insert.setInt(i++, round); insert.setString(i++, clean(candidate.source(), 30)); insert.setString(i, clean(candidate.sourceUrl(), 1000));
                    insert.addBatch(); position++;
                }
                insert.executeBatch();
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE agenda_prospecting_jobs SET current_round=CASE WHEN ?>0 THEN 1 ELSE 0 END,
                      next_round_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?
                    """)) {
                update.setInt(1, selected.size()); update.setString(2, job.id()); update.executeUpdate();
            }
            connection.commit();
        }
    }

    private void send(Job job) {
        ProspectingSettingsService.Snapshot configuration = settings.snapshot();
        if (!job.sendAuthorized() || !configuration.realSendingAllowed()) {
            processLogs.warn(job.id(), "READY", "EMAIL_SEND_BLOCKED",
                    "Envio real bloqueado por configuração, autorização ou PRODUCAO=false.");
            failJob(job.id(), "Envio real bloqueado por configuração, autorização ou PRODUCAO=false.");
            return;
        }
        TaskInfo task = task(job.taskId());
        if (!taskOpen(task)) { cancelWithReason(job.id(), "A tarefa não está mais aberta."); return; }
        int remaining = ProspectingRules.dailyAllowance(sentToday(), configuration.dailyLimit());
        if (remaining == 0) { defer(job.id(), 60); return; }
        setState(job.id(), "SENDING", "");
        List<QueuedInvitation> invitations = queued(job.id(), Math.min(remaining, configuration.contactRoundSize()));
        processLogs.info(job.id(), "SENDING", "EMAIL_BATCH_STARTED",
                "Lote de convites reservado para envio.", (long) invitations.size(), null,
                Map.of("batchSize", invitations.size(), "dailyAllowance", remaining));
        int sent = 0, failures = 0;
        for (QueuedInvitation invitation : invitations) {
            if (hasPositiveResponse(job.id())) {
                stopPendingAfterResponse(job.id());
                return;
            }
            if (!taskOpen(task(job.taskId()))) { cancelWithReason(job.id(), "A tarefa foi encerrada durante o envio."); return; }
            if (isSuppressed(invitation.emailHash(), job.specialtyId())) {
                markInvitation(invitation.id(), "SUPPRESSED", "Contato presente na lista de supressão.");
                continue;
            }
            if (!markInvitationSending(invitation.id())) continue;
            String email = crypto.decrypt(invitation.emailCiphertext());
            String inviteToken = randomToken();
            String optOutToken = randomToken();
            String inviteLink = configuration.publicWebUrl() + "/?invite=" + inviteToken;
            String optOutLink = configuration.publicWebUrl() + "/?optout=" + optOutToken;
            updateTokens(invitation.id(), inviteToken, optOutToken, configuration);
            ExternalInviteMailer.SendResult result = mailer.send(email, task.specialtyName(),
                    invitation.municipality() + "/" + invitation.uf(), invitation.distanceKm(),
                    task.startsAt().format(BR_DATE), inviteLink, optOutLink);
            if (result.sent()) { markSent(invitation.id()); sent++; }
            else { markInvitation(invitation.id(), "FAILED", result.error()); failures++; }
        }
        finishSend(job.id(), sent, failures);
    }

    private List<QueuedInvitation> queued(String jobId, int limit) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT i.id,i.email_hash,i.email_ciphertext,i.distance_km,
                       COALESCE(NULLIF(p.municipality_name,''),w.municipality_name,'') municipality_name,
                       COALESCE(NULLIF(p.uf,''),w.uf,'') uf,i.round_number
                     FROM agenda_external_invitations i
                     LEFT JOIN agenda_cnpj_prospects p ON p.id=i.prospect_id
                     LEFT JOIN agenda_web_prospects w ON w.id=i.web_prospect_id
                     WHERE i.job_id=? AND i.status='QUEUED'
                     ORDER BY i.round_number,i.distance_km,i.created_at LIMIT ?
                     """)) {
            query.setString(1, jobId); query.setInt(2, limit);
            List<QueuedInvitation> values = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) values.add(new QueuedInvitation(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getDouble(4), rows.getString(5), rows.getString(6), rows.getInt(7)));
            }
            return values;
        } catch (SQLException exception) { throw serverError("Não foi possível carregar a fila de convites.", exception); }
    }

    private boolean hasPositiveResponse(String jobId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT 1 FROM agenda_external_invitations WHERE job_id=? AND status='REGISTERED' LIMIT 1")) {
            query.setString(1, jobId);
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        } catch (SQLException exception) { return false; }
    }

    private void stopPendingAfterResponse(String jobId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            stopAfterResponse(connection, jobId);
            connection.commit();
        } catch (SQLException exception) { throw serverError("Não foi possível interromper as rodadas após a resposta.", exception); }
    }

    private boolean markInvitationSending(String invitationId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_external_invitations SET status='SENDING',failure_reason='',sending_started_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                     WHERE id=? AND status='QUEUED'
                     """)) {
            update.setString(1, invitationId);
            return update.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw serverError("Não foi possível reservar o convite para envio.", exception);
        }
    }

    private void updateTokens(String invitationId, String inviteToken, String optOutToken,
                              ProspectingSettingsService.Snapshot configuration) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_external_invitations SET token_hash=?,optout_token_hash=?,expires_at=?,updated_at=CURRENT_TIMESTAMP
                     WHERE id=? AND status='SENDING'
                     """)) {
            update.setString(1, ProspectingValidation.sha256(inviteToken));
            update.setString(2, ProspectingValidation.sha256(optOutToken));
            update.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now().plus(configuration.tokenDuration())));
            update.setString(4, invitationId); update.executeUpdate();
        } catch (SQLException exception) { throw serverError("Não foi possível preparar o token do convite.", exception); }
    }

    private void markSent(String invitationId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_external_invitations SET status='SENT',sent_at=CURRENT_TIMESTAMP,failure_reason='',sending_started_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?
                     """)) {
            update.setString(1, invitationId); update.executeUpdate();
        } catch (SQLException exception) { throw serverError("Não foi possível registrar o envio.", exception); }
    }

    private void markInvitation(String invitationId, String status, String reason) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_external_invitations SET status=?,failure_reason=?,sending_started_at=NULL,updated_at=CURRENT_TIMESTAMP,
                       failed_at=CASE WHEN ?='FAILED' THEN CURRENT_TIMESTAMP ELSE failed_at END WHERE id=?
                     """)) {
            update.setString(1, status); update.setString(2, clean(reason, 500)); update.setString(3, status);
            update.setString(4, invitationId); update.executeUpdate();
        } catch (SQLException exception) { throw serverError("Não foi possível atualizar o convite.", exception); }
    }

    private void finishSend(String jobId, int ignoredSentDelta, int ignoredFailureDelta) {
        ProspectingSettingsService.Snapshot configuration = settings.snapshot();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int currentRound;
            try (PreparedStatement current = connection.prepareStatement(
                    "SELECT current_round FROM agenda_prospecting_jobs WHERE id=?")) {
                current.setString(1, jobId);
                try (ResultSet rows = current.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Job não encontrado.");
                    currentRound = Math.max(1, rows.getInt(1));
                }
            }
            int queued, waiting, sent, sentCurrentRound, failures, registered;
            try (PreparedStatement count = connection.prepareStatement("""
                    SELECT COUNT(*) FILTER (WHERE status='QUEUED'),
                           COUNT(*) FILTER (WHERE status='WAITING_ROUND'),
                           COUNT(*) FILTER (WHERE status IN ('SENT','DELIVERED','REGISTERED')),
                           COUNT(*) FILTER (WHERE round_number=? AND status IN ('SENT','DELIVERED','REGISTERED')),
                           COUNT(*) FILTER (WHERE status='FAILED'),
                           COUNT(*) FILTER (WHERE status='REGISTERED')
                    FROM agenda_external_invitations WHERE job_id=?
                    """)) {
                count.setInt(1, currentRound); count.setString(2, jobId);
                try (ResultSet rows = count.executeQuery()) {
                    rows.next(); queued=rows.getInt(1); waiting=rows.getInt(2); sent=rows.getInt(3);
                    sentCurrentRound=rows.getInt(4); failures=rows.getInt(5); registered=rows.getInt(6);
                }
            }
            String state = "EXHAUSTED";
            LocalDateTime completedAt = null;
            LocalDateTime nextRoundAt = null;
            ProspectingRoundRules.Action action = ProspectingRoundRules.nextAction(
                    registered, queued, waiting, sentCurrentRound, sent, failures);
            switch (action) {
                case RESPONDED -> { state="RESPONDED"; completedAt=LocalDateTime.now(); }
                case READY -> state="READY";
                case WAIT_FOR_RESPONSE -> {
                    state="WAITING_RESPONSE";
                    nextRoundAt = nextRoundDeadline(configuration);
                }
                case RELEASE_NEXT_ROUND -> {
                    int nextRound = currentRound + 1;
                    int released = queueNextRound(connection, jobId, nextRound);
                    state = released > 0 ? "READY" : "EXHAUSTED";
                    if (released > 0) currentRound = nextRound; else completedAt = LocalDateTime.now();
                }
                case FAILED -> { state="FAILED"; completedAt=LocalDateTime.now(); }
                case EXHAUSTED -> { state="EXHAUSTED"; completedAt=LocalDateTime.now(); }
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE agenda_prospecting_jobs SET state=?,sent_count=?,failure_count=?,registration_count=?,
                      current_round=?,not_before=?,next_round_at=?,completed_at=?,locked_at=NULL,lock_owner='',updated_at=CURRENT_TIMESTAMP
                    WHERE id=?
                    """)) {
                update.setString(1,state); update.setInt(2,sent); update.setInt(3,failures); update.setInt(4,registered);
                update.setInt(5,currentRound);
                LocalDateTime wake = nextRoundAt == null ? LocalDateTime.now() : nextRoundAt;
                update.setTimestamp(6, Timestamp.valueOf(wake));
                if (nextRoundAt == null) update.setNull(7, java.sql.Types.TIMESTAMP); else update.setTimestamp(7, Timestamp.valueOf(nextRoundAt));
                if (completedAt == null) update.setNull(8, java.sql.Types.TIMESTAMP); else update.setTimestamp(8, Timestamp.valueOf(completedAt));
                update.setString(9,jobId); update.executeUpdate();
            }
            connection.commit();
            processLogs.info(jobId, state, "EMAIL_BATCH_FINISHED", "Rodada de envio finalizada.",
                    (long)(sent+failures), null, Map.of("sent",sent,"sentCurrentRound",sentCurrentRound,"failures",failures,
                            "waiting",waiting,"round",currentRound,"nextRoundAt", nextRoundAt == null ? "" : nextRoundAt.toString()));
        } catch (SQLException exception) { throw serverError("Não foi possível finalizar o envio.", exception); }
    }

    private void advanceRound(Job job) {
        TaskInfo task = task(job.taskId());
        if (!taskOpen(task)) { cancelWithReason(job.id(), "A tarefa não está mais aberta."); return; }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int registered;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT COUNT(*) FROM agenda_external_invitations WHERE job_id=? AND status='REGISTERED'")) {
                query.setString(1, job.id());
                try (ResultSet rows=query.executeQuery()) { rows.next(); registered=rows.getInt(1); }
            }
            if (registered > 0) {
                stopAfterResponse(connection, job.id());
                connection.commit();
                return;
            }
            int nextRound = Math.max(1, job.currentRound()+1);
            int released = queueNextRound(connection, job.id(), nextRound);
            if (released == 0) {
                try (PreparedStatement done=connection.prepareStatement("""
                        UPDATE agenda_prospecting_jobs SET state='EXHAUSTED',next_round_at=NULL,completed_at=CURRENT_TIMESTAMP,
                          locked_at=NULL,lock_owner='',updated_at=CURRENT_TIMESTAMP WHERE id=?
                        """)) { done.setString(1,job.id()); done.executeUpdate(); }
                connection.commit();
                processLogs.info(job.id(), "EXHAUSTED", "CONTACT_POOL_EXHAUSTED",
                        "Não há novos candidatos preparados para outra rodada.");
                return;
            }
            try (PreparedStatement update=connection.prepareStatement("""
                    UPDATE agenda_prospecting_jobs SET state='READY',current_round=?,next_round_at=NULL,
                      not_before=CURRENT_TIMESTAMP,locked_at=NULL,lock_owner='',updated_at=CURRENT_TIMESTAMP WHERE id=?
                    """)) {
                update.setInt(1,nextRound); update.setString(2,job.id()); update.executeUpdate();
            }
            connection.commit();
            processLogs.info(job.id(), "READY", "NEXT_CONTACT_ROUND_RELEASED",
                    "Prazo útil encerrado sem resposta positiva; próxima rodada liberada.", (long)released, null,
                    Map.of("round",nextRound,"released",released));
        } catch (SQLException exception) { throw serverError("Não foi possível liberar a próxima rodada.", exception); }
    }

    private int queueNextRound(Connection connection, String jobId, int round) throws SQLException {
        try (PreparedStatement update=connection.prepareStatement("""
                UPDATE agenda_external_invitations SET status='QUEUED',updated_at=CURRENT_TIMESTAMP
                WHERE job_id=? AND round_number=? AND status='WAITING_ROUND'
                """)) {
            update.setString(1,jobId); update.setInt(2,round); return update.executeUpdate();
        }
    }

    private void stopAfterResponse(Connection connection, String jobId) throws SQLException {
        try (PreparedStatement invitations=connection.prepareStatement("""
                UPDATE agenda_external_invitations SET status='CANCELLED_RESPONSE',updated_at=CURRENT_TIMESTAMP
                WHERE job_id=? AND status IN ('WAITING_ROUND','QUEUED')
                """)) { invitations.setString(1,jobId); invitations.executeUpdate(); }
        try (PreparedStatement job=connection.prepareStatement("""
                UPDATE agenda_prospecting_jobs SET state='RESPONDED',next_round_at=NULL,completed_at=CURRENT_TIMESTAMP,
                  locked_at=NULL,lock_owner='',updated_at=CURRENT_TIMESTAMP WHERE id=?
                """)) { job.setString(1,jobId); job.executeUpdate(); }
    }

    private LocalDateTime nextRoundDeadline(ProspectingSettingsService.Snapshot configuration) {
        ZoneId zone = ZoneId.of(configuration.businessZone());
        LocalDateTime now = LocalDateTime.now(zone);
        return BusinessHoursCalculator.addBusinessHours(now, configuration.responseBusinessHours(),
                configuration.businessStart(), configuration.businessEnd());
    }

    private void updateSelectionMetrics(String jobId, SelectionResult result, String state) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_prospecting_jobs SET state=?,records_analyzed=?,filtered_cnae=?,filtered_email=?,
                       filtered_address=?,inside_radius=?,selected_count=?,prepared_count=?,last_error='',
                       completed_at=CASE WHEN ?='DRY_RUN' THEN CURRENT_TIMESTAMP ELSE NULL END,
                       locked_at=NULL,lock_owner='',updated_at=CURRENT_TIMESTAMP WHERE id=?
                     """)) {
            update.setString(1, state); update.setLong(2, result.analyzed()); update.setLong(3, result.filteredCnae());
            update.setLong(4, result.filteredEmail()); update.setLong(5, result.filteredAddress());
            update.setLong(6, result.insideRadius()); update.setInt(7, result.selected().size());
            update.setInt(8, Math.min(result.selected().size(), settings.snapshot().contactRoundSize())); update.setString(9, state); update.setString(10, jobId);
            update.executeUpdate();
        } catch (SQLException exception) { throw serverError("Não foi possível registrar as métricas.", exception); }
    }

    private Set<String> suppressionEmailHashes(long specialtyId) throws SQLException {
        Set<String> hashes = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT email_hash FROM agenda_email_suppressions
                     WHERE scope='GLOBAL' OR specialty_id=?
                     """)) {
            query.setLong(1, specialtyId);
            try (ResultSet rows = query.executeQuery()) { while (rows.next()) hashes.add(rows.getString(1)); }
        }
        return hashes;
    }

    private Set<String> taskInvitationEmailHashes(String taskId) throws SQLException {
        Set<String> hashes = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT email_hash FROM agenda_external_invitations WHERE task_id=?")) {
            query.setString(1, taskId);
            try (ResultSet rows = query.executeQuery()) { while (rows.next()) hashes.add(rows.getString(1)); }
        }
        return hashes;
    }

    private Set<String> cooldownEmailHashes(int days) throws SQLException {
        Set<String> hashes = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT DISTINCT email_hash FROM agenda_external_invitations
                     WHERE sent_at>CURRENT_TIMESTAMP-(? * INTERVAL '1 day')
                     """)) {
            query.setInt(1, days);
            try (ResultSet rows = query.executeQuery()) { while (rows.next()) hashes.add(rows.getString(1)); }
        }
        return hashes;
    }

    private Set<String> existingProviderEmailHashes(long specialtyId) throws SQLException {
        Set<String> hashes = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT a.email FROM agenda_accounts a JOIN agenda_users u ON u.id=a.id
                     JOIN agenda_user_specialties us ON us.user_id=u.id
                     WHERE us.specialty_id=? AND u.role_code IN ('PROVIDER','BOTH') AND a.enabled=TRUE
                     """)) {
            query.setLong(1, specialtyId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) hashes.add(ProspectingValidation.sha256(ProspectingValidation.normalizeEmail(rows.getString(1))));
            }
        }
        return hashes;
    }

    private boolean suppressed(Connection connection, String emailHash, long specialtyId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM agenda_email_suppressions WHERE email_hash=?
                  AND (scope='GLOBAL' OR specialty_id=?) LIMIT 1
                """)) {
            query.setString(1, emailHash); query.setLong(2, specialtyId);
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        }
    }

    private boolean isSuppressed(String emailHash, long specialtyId) {
        try (Connection connection = dataSource.getConnection()) { return suppressed(connection, emailHash, specialtyId); }
        catch (SQLException exception) { return true; }
    }

    private boolean invitedForTask(Connection connection, String taskId, String emailHash) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM agenda_external_invitations WHERE task_id=? AND email_hash=? LIMIT 1")) {
            query.setString(1, taskId); query.setString(2, emailHash);
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        }
    }

    private boolean withinCooldown(Connection connection, String emailHash, int days) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM agenda_external_invitations WHERE email_hash=? AND sent_at>CURRENT_TIMESTAMP-(? * INTERVAL '1 day') LIMIT 1
                """)) {
            query.setString(1, emailHash); query.setInt(2, days);
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        }
    }

    private boolean internalCandidatesSufficient(TaskInfo task) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT COUNT(*) FROM agenda_candidates WHERE task_id=? AND status NOT IN ('REJECTED','WITHDRAWN')
                     """)) {
            query.setString(1, task.id());
            try (ResultSet rows = query.executeQuery()) { rows.next(); return rows.getInt(1) >= task.peopleNeeded(); }
        } catch (SQLException exception) { return false; }
    }

    private boolean taskOpen(TaskInfo task) {
        if (!"ACTIVE".equals(task.status())) return false;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT COUNT(*) FROM agenda_candidates WHERE task_id=? AND status IN ('APPROVED','CONFIRMED')
                     """)) {
            query.setString(1, task.id());
            try (ResultSet rows = query.executeQuery()) { rows.next(); return rows.getInt(1) < task.peopleNeeded(); }
        } catch (SQLException exception) { return false; }
    }

    private TaskInfo task(String taskId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT t.id,t.owner_id,t.specialty_id,s.name specialty_name,t.title,t.description,t.latitude,t.longitude,t.people_needed,
                       t.task_status,t.offer_phase,t.starts_at FROM agenda_tasks t JOIN agenda_specialties s ON s.id=t.specialty_id
                     WHERE t.id=?
                     """)) {
            query.setString(1, taskId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tarefa não encontrada.");
                return new TaskInfo(rows.getString("id"), rows.getString("owner_id"), rows.getLong("specialty_id"),
                        rows.getString("specialty_name"), rows.getString("title"), rows.getString("description"),
                        rows.getDouble("latitude"), rows.getDouble("longitude"), rows.getInt("people_needed"),
                        rows.getString("task_status"), rows.getString("offer_phase"), rows.getTimestamp("starts_at").toLocalDateTime());
            }
        } catch (ResponseStatusException exception) { throw exception; }
        catch (SQLException exception) { throw serverError("Não foi possível carregar a tarefa.", exception); }
    }

    private int sentToday() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT COUNT(*) FROM agenda_external_invitations WHERE sent_at>=CURRENT_DATE
                     """)) {
            try (ResultSet rows = query.executeQuery()) { rows.next(); return rows.getInt(1); }
        } catch (SQLException exception) { return Integer.MAX_VALUE; }
    }

    private void setState(String jobId, String state, String error) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_prospecting_jobs SET state=?,last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
                     """)) {
            update.setString(1, state); update.setString(2, clean(error, 1000)); update.setString(3, jobId); update.executeUpdate();
            processLogs.info(jobId, state, "STATE_CHANGED", stateMessage(state));
        } catch (SQLException ignored) { }
    }

    private void defer(String jobId, int minutes) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_prospecting_jobs SET not_before=CURRENT_TIMESTAMP+(? * INTERVAL '1 minute'),
                       locked_at=NULL,lock_owner='',updated_at=CURRENT_TIMESTAMP WHERE id=?
                     """)) {
            update.setInt(1, minutes); update.setString(2, jobId); update.executeUpdate();
            processLogs.info(jobId, "PENDING", "JOB_DEFERRED",
                    "Job adiado para nova tentativa.", null, null, Map.of("minutes", minutes));
        } catch (SQLException ignored) { }
    }

    private void failJob(String jobId, String error) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_prospecting_jobs SET state='FAILED',last_error=?,failure_count=failure_count+1,
                       completed_at=CURRENT_TIMESTAMP,locked_at=NULL,lock_owner='',updated_at=CURRENT_TIMESTAMP WHERE id=?
                     """)) {
            update.setString(1, clean(error, 1000)); update.setString(2, jobId); update.executeUpdate();
            processLogs.error(jobId, "FAILED", "PROCESS_FAILED", clean(error, 500));
        } catch (SQLException ignored) { }
    }

    private void cancelWithReason(String jobId, String reason) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_prospecting_jobs SET state='CANCELLED',last_error=?,cancelled_at=CURRENT_TIMESTAMP,
                       completed_at=CURRENT_TIMESTAMP,locked_at=NULL,lock_owner='',updated_at=CURRENT_TIMESTAMP WHERE id=?
                     """)) {
            update.setString(1, clean(reason, 1000)); update.setString(2, jobId); update.executeUpdate();
            processLogs.warn(jobId, "CANCELLED", "PROCESS_CANCELLED", clean(reason, 500));
        } catch (SQLException ignored) { }
    }

    private void expireInvitations() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_external_invitations SET status='EXPIRED',updated_at=CURRENT_TIMESTAMP
                     WHERE status='SENT' AND expires_at<=CURRENT_TIMESTAMP
                     """)) {
            update.executeUpdate();
        } catch (SQLException ignored) { }
    }

    private void recoverStaleInvitationSends() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_external_invitations SET status='FAILED',failed_at=CURRENT_TIMESTAMP,
                       sending_started_at=NULL,updated_at=CURRENT_TIMESTAMP,
                       failure_reason='Estado de envio interrompido; não será reenviado automaticamente para evitar duplicidade.'
                     WHERE status='SENDING' AND sending_started_at<CURRENT_TIMESTAMP-INTERVAL '10 minutes'
                     """)) {
            update.executeUpdate();
        } catch (SQLException ignored) { }
    }

    private void recoverStaleJobs() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement interrupted = connection.prepareStatement("""
                    UPDATE agenda_prospecting_jobs SET state='PENDING',locked_at=NULL,lock_owner='',
                      not_before=CURRENT_TIMESTAMP,last_error='Processamento retomado após reinicialização.',updated_at=CURRENT_TIMESTAMP
                    WHERE state IN ('FILTERING','GEOCODING') AND locked_at<CURRENT_TIMESTAMP-INTERVAL '10 minutes'
                    """)) { interrupted.executeUpdate(); }
            try (PreparedStatement sending = connection.prepareStatement("""
                    UPDATE agenda_prospecting_jobs j SET state=CASE
                        WHEN EXISTS(SELECT 1 FROM agenda_external_invitations i WHERE i.job_id=j.id AND i.status='REGISTERED') THEN 'RESPONDED'
                        WHEN EXISTS(SELECT 1 FROM agenda_external_invitations i WHERE i.job_id=j.id AND i.status='QUEUED') THEN 'READY'
                        WHEN EXISTS(SELECT 1 FROM agenda_external_invitations i WHERE i.job_id=j.id AND i.status='WAITING_ROUND')
                          AND EXISTS(SELECT 1 FROM agenda_external_invitations i WHERE i.job_id=j.id AND i.status IN ('SENT','DELIVERED')) THEN 'WAITING_RESPONSE'
                        WHEN EXISTS(SELECT 1 FROM agenda_external_invitations i WHERE i.job_id=j.id AND i.status IN ('SENT','DELIVERED')) THEN 'EXHAUSTED'
                        ELSE 'FAILED' END,
                      sent_count=(SELECT COUNT(*) FROM agenda_external_invitations i WHERE i.job_id=j.id AND i.status IN ('SENT','DELIVERED','REGISTERED')),
                      failure_count=(SELECT COUNT(*) FROM agenda_external_invitations i WHERE i.job_id=j.id AND i.status='FAILED'),
                      locked_at=NULL,lock_owner='',updated_at=CURRENT_TIMESTAMP,
                      not_before=CASE WHEN EXISTS(SELECT 1 FROM agenda_external_invitations i WHERE i.job_id=j.id AND i.status='WAITING_ROUND')
                        THEN COALESCE(j.next_round_at,CURRENT_TIMESTAMP) ELSE CURRENT_TIMESTAMP END,
                      completed_at=CASE WHEN EXISTS(SELECT 1 FROM agenda_external_invitations i WHERE i.job_id=j.id AND i.status IN ('QUEUED','WAITING_ROUND')) THEN NULL ELSE CURRENT_TIMESTAMP END,
                      last_error='Processamento de envio recuperado após reinicialização.'
                    WHERE j.state='SENDING' AND j.locked_at<CURRENT_TIMESTAMP-INTERVAL '10 minutes'
                    """)) { sending.executeUpdate(); }
            try (PreparedStatement release = connection.prepareStatement("""
                    UPDATE agenda_prospecting_jobs SET locked_at=NULL,lock_owner=''
                    WHERE locked_at<CURRENT_TIMESTAMP-INTERVAL '10 minutes' AND state IN ('PENDING','READY','WAITING_RESPONSE')
                    """)) { release.executeUpdate(); }
            connection.commit();
        } catch (SQLException ignored) { }
    }

    private static long elapsedMs(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private static String stateMessage(String state) {
        return switch (state) {
            case "PENDING" -> "Processamento aguardando execução.";
            case "FILTERING" -> "Filtragem de estabelecimentos iniciada.";
            case "GEOCODING" -> "Geocodificação e cálculo de distância iniciados.";
            case "READY" -> "Convites preparados e prontos para eventual autorização.";
            case "DRY_RUN" -> "Simulação concluída sem envio de e-mail.";
            case "SENDING" -> "Envio de convites iniciado.";
            case "WAITING_RESPONSE" -> "Aguardando resposta positiva antes de liberar a próxima rodada.";
            case "RESPONDED" -> "Resposta positiva recebida; novas rodadas foram interrompidas.";
            case "EXHAUSTED" -> "Pool de candidatos encerrado sem novas rodadas disponíveis.";
            case "SENT" -> "Envio concluído.";
            case "PARTIAL" -> "Processamento concluído parcialmente.";
            case "FAILED" -> "Processamento encerrado com falha.";
            case "CANCELLED" -> "Processamento cancelado.";
            default -> "Estado do processamento atualizado para " + clean(state, 40) + ".";
        };
    }

    private long activeProspectCount() {
        try (Connection connection = dataSource.getConnection()) {
            return scalar(connection, "SELECT COUNT(*) FROM agenda_cnpj_prospects WHERE active=TRUE AND registration_status='02'");
        } catch (SQLException exception) {
            throw serverError("Não foi possível verificar a base pública do CNPJ.", exception);
        }
    }

    private long scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql); ResultSet rows = query.executeQuery()) {
            rows.next(); return rows.getLong(1);
        }
    }

    private static JobSummary mapSummary(ResultSet rows) throws SQLException {
        return new JobSummary(rows.getString("id"), rows.getString("task_id"), rows.getString("state"),
                rows.getBoolean("dry_run"), rows.getBoolean("manual_trigger"), rows.getBoolean("send_authorized"),
                rows.getLong("specialty_id"), rows.getString("specialty_name"), rows.getLong("records_analyzed"),
                rows.getLong("filtered_cnae"), rows.getLong("filtered_email"), rows.getLong("filtered_address"),
                rows.getLong("inside_radius"), rows.getInt("selected_count"), rows.getInt("prepared_count"),
                rows.getInt("sent_count"), rows.getInt("failure_count"), rows.getInt("optout_count"),
                rows.getInt("registration_count"), rows.getInt("current_round"), timestamp(rows, "next_round_at"),
                rows.getString("ai_provider"), rows.getInt("ai_candidates_count"), rows.getString("last_error"),
                timestamp(rows, "created_at"), timestamp(rows, "updated_at"), timestamp(rows, "completed_at"));
    }

    private static String validateToken(String token) {
        String value = token == null ? "" : token.trim();
        if (!ProspectingRules.tokenUsable(value, LocalDateTime.now().plusMinutes(1), LocalDateTime.now()))
            throw badRequest("Token inválido.");
        return value;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return HexFormat.of().formatHex(bytes);
    }

    private static String maskEmail(String email) {
        String normalized = ProspectingValidation.normalizeEmail(email);
        int at = normalized.indexOf('@');
        if (at < 1) return "contato protegido";
        String local = normalized.substring(0, at);
        String masked = local.length() <= 2 ? local.charAt(0) + "*" : local.substring(0, 2) + "***";
        return masked + normalized.substring(at);
    }

    private static Double nullableDouble(ResultSet rows, String column) throws SQLException {
        double value = rows.getDouble(column); return rows.wasNull() ? null : value;
    }

    private static void setNullable(PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.DOUBLE); else statement.setDouble(index, value);
    }

    private static String timestamp(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column); return value == null ? "" : value.toLocalDateTime().toString();
    }

    private static String clean(String value, int max) {
        String result = value == null ? "" : value.trim(); return result.length() > max ? result.substring(0, max) : result;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage(); return message == null || message.isBlank() ? exception.getClass().getSimpleName() : clean(message, 1000);
    }

    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException serverError(String message, Exception cause) { return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message, cause); }

    record JobSummary(String id, String taskId, String state, boolean dryRun, boolean manualTrigger,
                      boolean sendAuthorized, long specialtyId, String specialtyName,
                      long recordsFound, long filteredCnae, long filteredEmail, long filteredAddress,
                      long insideRadius, int selected, int prepared, int sent, int failures,
                      int optOuts, int registrations, int currentRound, String nextRoundAt,
                      String aiProvider, int aiCandidates, String lastError,
                      String createdAt, String updatedAt, String completedAt) {
        static JobSummary none(String taskId) {
            return new JobSummary("", taskId, "NOT_STARTED", true, false, false, 0, "", 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, "", "", 0, "", "", "", "");
        }
    }
    record InvitationPreview(String id, String status, int roundNumber, String source, String sourceUrl,
                             String maskedEmail, String establishment, double distanceKm, String matchedCnae, String cnaeMatchType,
                             String specialty, String region, String message) {}
    record SuppressionInfo(String emailHashPrefix, String scope, String reason, String origin,
                           boolean requestedByHolder, String createdAt) {}
    record InvitationContext(String specialty, String region, double approximateDistanceKm,
                             String date, String email, String emailInstruction) {}
    record OptOutContext(String maskedEmail, String message) {}
    private record Job(String id, String taskId, long specialtyId, String state, boolean dryRun,
                       boolean manualTrigger, boolean sendAuthorized, int currentRound) {}
    private record TaskInfo(String id, String ownerId, long specialtyId, String specialtyName, String title, String description,
                            double latitude, double longitude, int peopleNeeded, String status,
                            String offerPhase, LocalDateTime startsAt) {}
    private record SelectionResult(long analyzed, long filteredCnae, long filteredEmail,
                                   long filteredAddress, long insideRadius, List<CandidateProspect> selected) {}
    private record CandidateProspect(long id, String source, String sourceUrl, double relevanceScore,
                                     String emailHash, String emailCiphertext, String address,
                                     String addressHash, Double latitude, Double longitude, String geocodeStatus,
                                     Double confidence, String precision, String tradeName, String municipality,
                                     String uf, String matchedCnae, boolean primaryMatch, double distanceKm) {
        boolean webSource() { return !"RECEITA_CNPJ".equals(source); }
        CandidateProspect withGeocode(Double lat, Double lon, Double value, String precisionValue, String statusValue) {
            return new CandidateProspect(id, source, sourceUrl, relevanceScore, emailHash, emailCiphertext, address, addressHash, lat, lon,
                    statusValue, value, precisionValue, tradeName, municipality, uf, matchedCnae, primaryMatch, distanceKm);
        }
        CandidateProspect withDistance(double value) {
            return new CandidateProspect(id, source, sourceUrl, relevanceScore, emailHash, emailCiphertext, address, addressHash, latitude, longitude,
                    geocodeStatus, confidence, precision, tradeName, municipality, uf, matchedCnae, primaryMatch, value);
        }
    }
    private record QueuedInvitation(String id, String emailHash, String emailCiphertext,
                                    double distanceKm, String municipality, String uf, int roundNumber) {}
}
