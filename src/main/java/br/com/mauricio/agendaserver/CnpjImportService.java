package br.com.mauricio.agendaserver;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
final class CnpjImportService {
    private static final Charset RECEITA_CHARSET = Charset.forName("ISO-8859-1");
    private static final int BATCH_SIZE = 500;
    private final DataSource dataSource;
    private final ProspectingSettingsService settings;
    private final ProspectingCryptoService crypto;
    private final SpecialtyService specialties;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);

    CnpjImportService(DataSource dataSource, ProspectingSettingsService settings,
                      ProspectingCryptoService crypto, SpecialtyService specialties) {
        this.dataSource = dataSource;
        this.settings = settings;
        this.crypto = crypto;
        this.specialties = specialties;
    }

    ImportRun start(String userId, ImportRequest request) {
        ProspectingSettingsService.Snapshot configuration = settings.snapshot();
        if (configuration.importDir() == null) {
            throw badRequest("Configure AGENDA_CNPJ_IMPORT_DIR antes de iniciar a importação.");
        }
        if (!Files.isDirectory(configuration.importDir())) {
            throw badRequest("O diretório configurado para os arquivos do CNPJ não existe.");
        }
        if (!crypto.configured()) {
            throw badRequest("Configure AGENDA_PROSPECT_DATA_KEY antes de importar contatos.");
        }
        String requestedMode = request == null ? null : request.mode();
        if (requestedMode != null && !requestedMode.isBlank() && !ImportMode.isValid(requestedMode)) {
            throw badRequest("Modo de importação inválido. Use FULL_CATALOG ou PROSPECTING_ONLY.");
        }
        ImportMode importMode = ImportMode.parse(requestedMode);
        String sourceVersion = request == null || request.sourceVersion() == null
                ? "" : request.sourceVersion().trim();
        if (sourceVersion.isBlank() || sourceVersion.length() > 80) {
            throw badRequest("Informe a versão da fonte, por exemplo Receita CNPJ 2026-07.");
        }
        LocalDate sourceDate = request == null ? null : request.sourceDate();
        String id = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO agenda_cnpj_import_runs(id,status,source_version,source_date,import_directory,requested_by,import_mode)
                     VALUES(?,'PENDING',?,?,?,?,?)
                     """)) {
            insert.setString(1, id);
            insert.setString(2, sourceVersion);
            if (sourceDate == null) insert.setNull(3, java.sql.Types.DATE); else insert.setDate(3, Date.valueOf(sourceDate));
            insert.setString(4, configuration.importDir().toString());
            insert.setString(5, userId);
            insert.setString(6, importMode.name());
            insert.executeUpdate();
            return run(id);
        } catch (SQLException exception) {
            throw serverError("Não foi possível registrar a importação.", exception);
        }
    }

    ImportRun resume(String id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_cnpj_import_runs SET status='PENDING',last_error='',updated_at=CURRENT_TIMESTAMP
                     WHERE id=? AND status IN ('FAILED','CANCELLED')
                     """)) {
            update.setString(1, id);
            if (update.executeUpdate() == 0) throw badRequest("Somente importações com falha ou canceladas podem ser retomadas.");
            return run(id);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw serverError("Não foi possível retomar a importação.", exception);
        }
    }

    ImportRun cancel(String id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_cnpj_import_runs SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP,
                       completed_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('PENDING','RUNNING')
                     """)) {
            update.setString(1, id);
            if (update.executeUpdate() == 0) throw badRequest("A importação não está pendente ou em execução.");
            return run(id);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw serverError("Não foi possível cancelar a importação.", exception);
        }
    }

    List<ImportRun> list() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id,status,source_version,source_date,import_mode,current_file,checkpoint_line,files_total,files_processed,
                       records_read,records_imported,records_rejected,last_error,created_at,started_at,completed_at
                     FROM agenda_cnpj_import_runs ORDER BY created_at DESC LIMIT 100
                     """)) {
            List<ImportRun> result = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(map(rows));
            }
            return result;
        } catch (SQLException exception) {
            throw serverError("Não foi possível carregar as importações.", exception);
        }
    }

    ImportRun run(String id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id,status,source_version,source_date,import_mode,current_file,checkpoint_line,files_total,files_processed,
                       records_read,records_imported,records_rejected,last_error,created_at,started_at,completed_at
                     FROM agenda_cnpj_import_runs WHERE id=?
                     """)) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Importação não encontrada.");
                return map(rows);
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw serverError("Não foi possível carregar a importação.", exception);
        }
    }

    @Scheduled(fixedDelayString = "${agenda.cnpj.import-poll-ms:15000}")
    void poll() {
        if (!workerRunning.compareAndSet(false, true)) return;
        try {
            recoverInterruptedRuns();
            String id = claimNext();
            if (id != null) process(id);
        } finally {
            workerRunning.set(false);
        }
    }

    private String claimNext() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT id FROM agenda_cnpj_import_runs WHERE status='PENDING'
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                    """)) {
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) { connection.rollback(); return null; }
                    String id = rows.getString(1);
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE agenda_cnpj_import_runs SET status='RUNNING',started_at=COALESCE(started_at,CURRENT_TIMESTAMP),
                              updated_at=CURRENT_TIMESTAMP WHERE id=?
                            """)) {
                        update.setString(1, id);
                        update.executeUpdate();
                    }
                    connection.commit();
                    return id;
                }
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ignored) {
            return null;
        }
    }

    private void process(String id) {
        ImportContext context = loadContext(id);
        ProspectingSettingsService.Snapshot configuration = settings.snapshot();
        Path directory = configuration.importDir();
        if (directory == null || !Files.isDirectory(directory)) {
            fail(id, "Diretório de importação não configurado ou inexistente.");
            return;
        }
        try {
            Set<String> activeCnaes = specialties.activeCnaeCodes();
            if (context.mode() == ImportMode.PROSPECTING_ONLY && activeCnaes.isEmpty()) {
                throw new IllegalStateException("Cadastre CNAEs ativos antes da importação de prospecção.");
            }
            Map<String, String> municipalities = readMunicipalities(directory);
            Set<String> pilot = pilotMunicipalities(configuration.pilotMunicipalities());
            List<Path> files = establishmentFiles(directory);
            if (files.isEmpty()) throw new IllegalStateException("Nenhum arquivo Estabelecimentos*.zip foi encontrado.");
            updateFileCount(id, files.size());
            int startIndex = ProspectingRules.resumeFileIndex(context.filesProcessed(), files.size());
            int processedFiles = startIndex;
            for (int index = startIndex; index < files.size(); index++) {
                if (isCancelled(id)) return;
                Path file = files.get(index);
                String fileName = file.getFileName().toString();
                long checkpoint = fileName.equals(context.currentFile()) ? context.checkpointLine() : 0;
                processFile(id, file, checkpoint, municipalities, pilot, activeCnaes, context,
                        configuration.emailCheckMx());
                if (isCancelled(id)) return;
                processedFiles++;
                markFileComplete(id, fileName, processedFiles);
                context = loadContext(id);
            }
            markRepeatedEmails(configuration.repeatedEmailThreshold());
            deactivateStaleProspects(context.sourceVersion(), pilot);
            if (context.mode() == ImportMode.FULL_CATALOG) markCatalogSourceCurrent(context.sourceVersion());
            complete(id);
        } catch (Exception exception) {
            if (!isCancelled(id)) fail(id, safeMessage(exception));
        }
    }

    private void processFile(String runId, Path file, long checkpoint, Map<String, String> municipalities,
                             Set<String> pilot, Set<String> activeCnaes, ImportContext context,
                             boolean checkMx) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file), RECEITA_CHARSET)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new NonClosingInputStream(zip), RECEITA_CHARSET), 128 * 1024);
                     SemicolonCsvReader parser = new SemicolonCsvReader(reader)) {
                    long line = 0;
                    int batchRead = 0;
                    int batchImported = 0;
                    int batchRejected = 0;
                    Map<String, Long> rejections = new LinkedHashMap<>();
                    Connection connection = dataSource.getConnection();
                    connection.setAutoCommit(false);
                    try (CatalogBatchWriter catalogWriter = context.mode() == ImportMode.FULL_CATALOG
                            ? new CatalogBatchWriter(connection, context) : null) {
                        List<String> record;
                        while ((record = parser.next()) != null) {
                            line++;
                            if (line <= checkpoint) continue;
                            if (context.mode() == ImportMode.FULL_CATALOG) {
                                CatalogDecision catalog = inspectCatalog(record, municipalities);
                                if (!catalog.accepted()) {
                                    rejections.merge(catalog.reason(), 1L, Long::sum);
                                    batchRejected++;
                                } else {
                                    catalogWriter.add(catalog);
                                    batchImported++;
                                    ImportDecision prospect = inspectProspect(catalog, pilot, activeCnaes, checkMx);
                                    if (prospect.accepted()) upsertProspect(connection, context, prospect);
                                }
                            } else {
                                ImportDecision decision = inspect(record, municipalities, pilot, activeCnaes, checkMx);
                                if (!decision.accepted()) {
                                    rejections.merge(decision.reason(), 1L, Long::sum);
                                    batchRejected++;
                                } else {
                                    upsertProspect(connection, context, decision);
                                    batchImported++;
                                }
                            }
                            batchRead++;
                            if (batchRead >= BATCH_SIZE) {
                                if (catalogWriter != null) catalogWriter.flush();
                                updateProgress(connection, runId, file.getFileName().toString(), line, batchRead,
                                        batchImported, batchRejected, rejections);
                                connection.commit();
                                if (isCancelled(connection, runId)) return;
                                rejections.clear();
                                batchRead = 0;
                                batchImported = 0;
                                batchRejected = 0;
                            }
                        }
                        if (catalogWriter != null) catalogWriter.flush();
                        if (batchRead > 0 || !rejections.isEmpty()) {
                            updateProgress(connection, runId, file.getFileName().toString(), line, batchRead,
                                    batchImported, batchRejected, rejections);
                        }
                        connection.commit();
                    } catch (Exception exception) {
                        connection.rollback();
                        throw exception;
                    } finally {
                        connection.setAutoCommit(true);
                        connection.close();
                    }
                }
                zip.closeEntry();
            }
        }
    }

    static CatalogDecision inspectCatalog(List<String> row, Map<String, String> municipalities) {
        if (row.size() < 28) return CatalogDecision.reject("ROW_INVALID");
        String cnpj = ProspectingValidation.normalizeCnpj(value(row, 0) + value(row, 1) + value(row, 2));
        if (cnpj.isBlank()) return CatalogDecision.reject("CNPJ_INVALID");
        String status = clean(value(row, 5), 8);
        String municipalityCode = clean(value(row, 20), 12);
        String municipality = clean(municipalities.getOrDefault(municipalityCode, ""), 120).toUpperCase(Locale.ROOT);
        String uf = clean(value(row, 19), 2).toUpperCase(Locale.ROOT);
        String primary = ProspectingValidation.normalizeCnae(value(row, 11));
        List<CnaeValue> cnaes = new ArrayList<>();
        if (!primary.isBlank()) cnaes.add(new CnaeValue(primary, true));
        for (String code : splitCnaes(value(row, 12))) {
            if (!code.equals(primary)) cnaes.add(new CnaeValue(code, false));
        }
        ProspectingValidation.EmailValidation emailValidation = ProspectingValidation.validateEmail(value(row, 27), false);
        String email = emailValidation.valid() ? emailValidation.normalized() : "";
        String emailDomain = emailValidation.valid() ? emailValidation.domain() : "";
        String cep = ProspectingValidation.normalizeCep(value(row, 18));
        String address = ProspectingValidation.normalizeAddress(value(row, 13), value(row, 14), value(row, 15),
                value(row, 16), value(row, 17), cep, municipality, uf);
        return CatalogDecision.accept(cnpj, clean(value(row, 4), 250), status, municipalityCode,
                municipality, uf, primary, email, emailDomain, address, cep, cnaes);
    }

    private ImportDecision inspectProspect(CatalogDecision catalog, Set<String> pilot,
                                           Set<String> activeCnaes, boolean checkMx) {
        if (!"02".equals(catalog.status())) return ImportDecision.reject("STATUS_NOT_ACTIVE");
        if (catalog.municipality().isBlank() || !pilot.contains(catalog.municipality() + "/" + catalog.uf())) {
            return ImportDecision.reject("MUNICIPALITY_OUTSIDE_PILOT");
        }
        List<CnaeValue> matches = new ArrayList<>();
        for (CnaeValue cnae : catalog.cnaes()) if (activeCnaes.contains(cnae.code())) matches.add(cnae);
        if (matches.isEmpty()) return ImportDecision.reject("CNAE_NOT_MAPPED");
        ProspectingValidation.EmailValidation email = ProspectingValidation.validateEmail(catalog.email(), checkMx);
        if (!email.valid()) return ImportDecision.reject(email.reason());
        if (!ProspectingValidation.validAddress(catalog.address(), catalog.cep(), catalog.municipality(), catalog.uf())) {
            return ImportDecision.reject("ADDRESS_INVALID");
        }
        return ImportDecision.accept(catalog.cnpj(), "", catalog.tradeName(), catalog.status(),
                catalog.municipalityCode(), catalog.municipality(), catalog.uf(), catalog.primaryCnae(),
                email.normalized(), email.domain(), catalog.address(), catalog.cep(), matches);
    }

    private ImportDecision inspect(List<String> row, Map<String, String> municipalities, Set<String> pilot,
                                   Set<String> activeCnaes, boolean checkMx) {
        CatalogDecision catalog = inspectCatalog(row, municipalities);
        if (!catalog.accepted()) return ImportDecision.reject(catalog.reason());
        return inspectProspect(catalog, pilot, activeCnaes, checkMx);
    }

    private long upsertProspect(Connection connection, ImportContext context, ImportDecision value) throws SQLException {
        String emailHash = ProspectingValidation.sha256(value.email());
        String addressHash = ProspectingValidation.sha256(value.address());
        long prospectId;
        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO agenda_cnpj_prospects(cnpj,legal_name,trade_name,registration_status,municipality_code,
                  municipality_name,uf,cnae_primary,email_hash,email_ciphertext,email_domain,email_quality_status,
                  address_normalized,address_hash,cep,source_version,source_date,active,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,'VALID',?,?,?,?,?,TRUE,CURRENT_TIMESTAMP)
                ON CONFLICT(cnpj) DO UPDATE SET legal_name=EXCLUDED.legal_name,trade_name=EXCLUDED.trade_name,
                  registration_status=EXCLUDED.registration_status,municipality_code=EXCLUDED.municipality_code,
                  municipality_name=EXCLUDED.municipality_name,uf=EXCLUDED.uf,cnae_primary=EXCLUDED.cnae_primary,
                  email_hash=EXCLUDED.email_hash,email_ciphertext=EXCLUDED.email_ciphertext,email_domain=EXCLUDED.email_domain,
                  email_quality_status='VALID',address_normalized=EXCLUDED.address_normalized,
                  address_hash=EXCLUDED.address_hash,cep=EXCLUDED.cep,source_version=EXCLUDED.source_version,
                  source_date=EXCLUDED.source_date,active=TRUE,updated_at=CURRENT_TIMESTAMP,
                  geocode_status=CASE WHEN agenda_cnpj_prospects.address_hash<>EXCLUDED.address_hash THEN 'PENDING' ELSE agenda_cnpj_prospects.geocode_status END,
                  latitude=CASE WHEN agenda_cnpj_prospects.address_hash<>EXCLUDED.address_hash THEN NULL ELSE agenda_cnpj_prospects.latitude END,
                  longitude=CASE WHEN agenda_cnpj_prospects.address_hash<>EXCLUDED.address_hash THEN NULL ELSE agenda_cnpj_prospects.longitude END
                RETURNING id
                """)) {
            int index = 1;
            upsert.setString(index++, value.cnpj());
            upsert.setString(index++, value.legalName());
            upsert.setString(index++, value.tradeName());
            upsert.setString(index++, value.status());
            upsert.setString(index++, value.municipalityCode());
            upsert.setString(index++, value.municipality());
            upsert.setString(index++, value.uf());
            upsert.setString(index++, value.primaryCnae());
            upsert.setString(index++, emailHash);
            upsert.setString(index++, crypto.encrypt(value.email()));
            upsert.setString(index++, value.emailDomain());
            upsert.setString(index++, value.address());
            upsert.setString(index++, addressHash);
            upsert.setString(index++, value.cep());
            upsert.setString(index++, context.sourceVersion());
            if (context.sourceDate() == null) upsert.setNull(index, java.sql.Types.DATE); else upsert.setDate(index, Date.valueOf(context.sourceDate()));
            try (ResultSet rows = upsert.executeQuery()) { rows.next(); prospectId = rows.getLong(1); }
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM agenda_cnpj_prospect_cnaes WHERE prospect_id=?");
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO agenda_cnpj_prospect_cnaes(prospect_id,cnae_code,primary_cnae) VALUES(?,?,?)
                     """)) {
            delete.setLong(1, prospectId);
            delete.executeUpdate();
            for (CnaeValue cnae : value.cnaes()) {
                insert.setLong(1, prospectId);
                insert.setString(2, cnae.code());
                insert.setBoolean(3, cnae.primary());
                insert.addBatch();
            }
            insert.executeBatch();
        }
        return prospectId;
    }

    private Map<String, String> readMunicipalities(Path directory) throws Exception {
        List<Path> candidates;
        try (var files = Files.list(directory)) {
            candidates = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).contains("municip"))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted().toList();
        }
        if (candidates.isEmpty()) throw new IllegalStateException("Arquivo Municipios*.zip não encontrado.");
        Map<String, String> values = new HashMap<>();
        for (Path file : candidates) {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file), RECEITA_CHARSET)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new NonClosingInputStream(zip), RECEITA_CHARSET));
                         SemicolonCsvReader parser = new SemicolonCsvReader(reader)) {
                        List<String> row;
                        while ((row = parser.next()) != null) if (row.size() >= 2) values.put(value(row, 0), value(row, 1));
                    }
                    zip.closeEntry();
                }
            }
        }
        return values;
    }

    private List<Path> establishmentFiles(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).contains("estabele"))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private Set<String> pilotMunicipalities(String configured) {
        Set<String> values = new HashSet<>();
        for (String item : configured.split("[,;\r\n]+")) {
            String normalized = item.trim().toUpperCase(Locale.ROOT);
            if (normalized.matches(".+/[A-Z]{2}")) values.add(normalized);
        }
        if (values.isEmpty()) values.add("SOROCABA/SP");
        return values;
    }

    private void updateProgress(Connection connection, String id, String file, long line, int readDelta,
                                int importedDelta, int rejectedDelta, Map<String, Long> rejections) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE agenda_cnpj_import_runs SET current_file=?,checkpoint_line=?,records_read=records_read+?,
                  records_imported=records_imported+?,records_rejected=records_rejected+?,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='RUNNING'
                """)) {
            update.setString(1, file);
            update.setLong(2, line);
            update.setInt(3, readDelta);
            update.setInt(4, importedDelta);
            update.setInt(5, rejectedDelta);
            update.setString(6, id);
            if (update.executeUpdate() != 1) throw new IllegalStateException("A importação foi cancelada ou interrompida.");
        }
        try (PreparedStatement rejection = connection.prepareStatement("""
                INSERT INTO agenda_import_rejections(import_run_id,reason_code,rejected_count) VALUES(?,?,?)
                ON CONFLICT(import_run_id,reason_code) DO UPDATE SET
                  rejected_count=agenda_import_rejections.rejected_count+EXCLUDED.rejected_count
                """)) {
            for (Map.Entry<String, Long> entry : rejections.entrySet()) {
                rejection.setString(1, id);
                rejection.setString(2, entry.getKey());
                rejection.setLong(3, entry.getValue());
                rejection.addBatch();
            }
            rejection.executeBatch();
        }
    }

    private void markRepeatedEmails(int threshold) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement reset = connection.prepareStatement("""
                    UPDATE agenda_cnpj_prospects SET email_quality_status='VALID',updated_at=CURRENT_TIMESTAMP
                    WHERE active=TRUE AND email_quality_status='REPEATED'
                    """)) {
                reset.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE agenda_cnpj_prospects SET email_quality_status='REPEATED',updated_at=CURRENT_TIMESTAMP
                    WHERE active=TRUE AND email_hash IN (
                      SELECT email_hash FROM agenda_cnpj_prospects WHERE active=TRUE GROUP BY email_hash HAVING COUNT(*)>=?
                    )
                    """)) {
                update.setInt(1, threshold);
                update.executeUpdate();
            }
            connection.commit();
        }
    }

    private void deactivateStaleProspects(String sourceVersion, Set<String> pilot) throws SQLException {
        if (sourceVersion == null || sourceVersion.isBlank() || pilot == null || pilot.isEmpty()) return;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_cnpj_prospects SET active=FALSE,updated_at=CURRENT_TIMESTAMP
                     WHERE active=TRUE AND source_version<>? AND (municipality_name || '/' || uf)=ANY(?)
                     """)) {
            update.setString(1, sourceVersion);
            update.setArray(2, connection.createArrayOf("varchar", pilot.toArray(String[]::new)));
            update.executeUpdate();
        }
    }

    private void markCatalogSourceCurrent(String sourceVersion) throws SQLException {
        if (sourceVersion == null || sourceVersion.isBlank()) return;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_cnpj_catalog SET source_current=(source_version=?),updated_at=CURRENT_TIMESTAMP
                     WHERE source_current=TRUE OR source_version=?
                     """)) {
            update.setString(1, sourceVersion);
            update.setString(2, sourceVersion);
            update.executeUpdate();
        }
    }

    private ImportContext loadContext(String id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT source_version,source_date,import_mode,current_file,checkpoint_line,files_processed
                     FROM agenda_cnpj_import_runs WHERE id=?
                     """)) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Importação não encontrada.");
                Date date = rows.getDate(2);
                return new ImportContext(rows.getString(1), date == null ? null : date.toLocalDate(),
                        ImportMode.parse(rows.getString(3)), rows.getString(4), rows.getLong(5), rows.getInt(6));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Não foi possível carregar o contexto da importação.", exception);
        }
    }

    private void updateFileCount(String id, int total) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("UPDATE agenda_cnpj_import_runs SET files_total=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
            update.setInt(1, total); update.setString(2, id); update.executeUpdate();
        }
    }

    private void markFileComplete(String id, String file, int processed) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_cnpj_import_runs SET current_file=?,checkpoint_line=0,files_processed=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
                     """)) {
            update.setString(1, file); update.setInt(2, processed); update.setString(3, id); update.executeUpdate();
        }
    }

    private void recoverInterruptedRuns() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_cnpj_import_runs SET status='PENDING',last_error='Importação retomada após reinicialização.',
                       updated_at=CURRENT_TIMESTAMP,completed_at=NULL
                     WHERE status='RUNNING' AND updated_at < CURRENT_TIMESTAMP - INTERVAL '10 minutes'
                     """)) {
            update.executeUpdate();
        } catch (SQLException ignored) { }
    }

    private boolean isCancelled(Connection connection, String id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT status FROM agenda_cnpj_import_runs WHERE id=?")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                return !rows.next() || !"RUNNING".equals(rows.getString(1));
            }
        }
    }

    private boolean isCancelled(String id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("SELECT status FROM agenda_cnpj_import_runs WHERE id=?")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) { return rows.next() && "CANCELLED".equals(rows.getString(1)); }
        } catch (SQLException exception) { return true; }
    }

    private void complete(String id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_cnpj_import_runs SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP,
                       updated_at=CURRENT_TIMESTAMP,last_error='' WHERE id=? AND status='RUNNING'
                     """)) {
            update.setString(1, id); update.executeUpdate();
        } catch (SQLException ignored) { }
    }

    private void fail(String id, String error) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_cnpj_import_runs SET status='FAILED',last_error=?,completed_at=CURRENT_TIMESTAMP,
                       updated_at=CURRENT_TIMESTAMP WHERE id=?
                     """)) {
            update.setString(1, clean(error, 1000)); update.setString(2, id); update.executeUpdate();
        } catch (SQLException ignored) { }
    }

    private static ImportRun map(ResultSet rows) throws SQLException {
        Date sourceDate = rows.getDate("source_date");
        return new ImportRun(rows.getString("id"), rows.getString("status"), rows.getString("source_version"),
                sourceDate == null ? null : sourceDate.toLocalDate(), rows.getString("import_mode"), basename(rows.getString("current_file")),
                rows.getLong("checkpoint_line"), rows.getInt("files_total"), rows.getInt("files_processed"),
                rows.getLong("records_read"), rows.getLong("records_imported"), rows.getLong("records_rejected"),
                rows.getString("last_error"), timestamp(rows, "created_at"), timestamp(rows, "started_at"), timestamp(rows, "completed_at"));
    }

    private static String timestamp(ResultSet rows, String column) throws SQLException {
        var value = rows.getTimestamp(column);
        return value == null ? "" : value.toLocalDateTime().toString();
    }

    private static String basename(String value) {
        if (value == null || value.isBlank()) return "";
        try { return Path.of(value).getFileName().toString(); }
        catch (Exception ignored) { return ""; }
    }

    private static String value(List<String> row, int index) {
        return index < row.size() ? row.get(index).trim() : "";
    }

    private static List<String> splitCnaes(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) return result;
        for (String item : value.split(",")) {
            String code = ProspectingValidation.normalizeCnae(item);
            if (!code.isBlank() && !result.contains(code)) result.add(code);
        }
        return result;
    }

    private static String clean(String value, int max) {
        String result = value == null ? "" : value.trim();
        return result.length() > max ? result.substring(0, max) : result;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return clean(message, 1000);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException serverError(String message, Exception exception) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message, exception);
    }

    record ImportRequest(String sourceVersion, LocalDate sourceDate, String mode) {}
    record ImportRun(String id, String status, String sourceVersion, LocalDate sourceDate, String mode, String currentFile,
                     long checkpointLine, int filesTotal, int filesProcessed, long recordsRead,
                     long recordsImported, long recordsRejected, String lastError,
                     String createdAt, String startedAt, String completedAt) {}
    private record ImportContext(String sourceVersion, LocalDate sourceDate, ImportMode mode, String currentFile,
                                 long checkpointLine, int filesProcessed) {}
    record CnaeValue(String code, boolean primary) {}
    record CatalogDecision(boolean accepted, String reason, String cnpj, String tradeName, String status,
                                   String municipalityCode, String municipality, String uf, String primaryCnae,
                                   String email, String emailDomain, String address, String cep, List<CnaeValue> cnaes) {
        static CatalogDecision reject(String reason) {
            return new CatalogDecision(false, reason, "", "", "", "", "", "", "", "", "", "", "", List.of());
        }
        static CatalogDecision accept(String cnpj, String tradeName, String status, String municipalityCode,
                                      String municipality, String uf, String primaryCnae, String email,
                                      String emailDomain, String address, String cep, List<CnaeValue> cnaes) {
            return new CatalogDecision(true, "", cnpj, tradeName, status, municipalityCode, municipality, uf,
                    primaryCnae, email, emailDomain, address, cep, List.copyOf(cnaes));
        }
    }

    private record ImportDecision(boolean accepted, String reason, String cnpj, String legalName, String tradeName,
                                  String status, String municipalityCode, String municipality, String uf,
                                  String primaryCnae, String email, String emailDomain, String address,
                                  String cep, List<CnaeValue> cnaes) {
        static ImportDecision reject(String reason) {
            return new ImportDecision(false, reason, "", "", "", "", "", "", "", "", "", "", "", "", List.of());
        }
        static ImportDecision accept(String cnpj, String legalName, String tradeName, String status,
                                     String municipalityCode, String municipality, String uf, String primaryCnae,
                                     String email, String emailDomain, String address, String cep, List<CnaeValue> cnaes) {
            return new ImportDecision(true, "", cnpj, legalName, tradeName, status, municipalityCode,
                    municipality, uf, primaryCnae, email, emailDomain, address, cep, List.copyOf(cnaes));
        }
    }

    private final class CatalogBatchWriter implements AutoCloseable {
        private final ImportContext context;
        private final PreparedStatement upsert;
        private final PreparedStatement deleteCnaes;
        private final PreparedStatement insertCnaes;
        private int pending;

        CatalogBatchWriter(Connection connection, ImportContext context) throws SQLException {
            this.context = context;
            this.upsert = connection.prepareStatement("""
                    INSERT INTO agenda_cnpj_catalog(cnpj,trade_name,registration_status,municipality_code,municipality_name,
                      uf,cnae_primary,email_hash,email_ciphertext,email_domain,email_quality_status,address_normalized,
                      address_hash,cep,source_version,source_date,source_current,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,TRUE,CURRENT_TIMESTAMP)
                    ON CONFLICT(cnpj) DO UPDATE SET trade_name=EXCLUDED.trade_name,
                      registration_status=EXCLUDED.registration_status,municipality_code=EXCLUDED.municipality_code,
                      municipality_name=EXCLUDED.municipality_name,uf=EXCLUDED.uf,cnae_primary=EXCLUDED.cnae_primary,
                      email_hash=EXCLUDED.email_hash,email_ciphertext=EXCLUDED.email_ciphertext,
                      email_domain=EXCLUDED.email_domain,email_quality_status=EXCLUDED.email_quality_status,
                      address_normalized=EXCLUDED.address_normalized,address_hash=EXCLUDED.address_hash,cep=EXCLUDED.cep,
                      source_version=EXCLUDED.source_version,source_date=EXCLUDED.source_date,source_current=TRUE,
                      updated_at=CURRENT_TIMESTAMP
                    """);
            this.deleteCnaes = connection.prepareStatement("DELETE FROM agenda_cnpj_catalog_cnaes WHERE cnpj=?");
            this.insertCnaes = connection.prepareStatement("""
                    INSERT INTO agenda_cnpj_catalog_cnaes(cnpj,cnae_code,primary_cnae) VALUES(?,?,?)
                    ON CONFLICT(cnpj,cnae_code) DO UPDATE SET primary_cnae=EXCLUDED.primary_cnae
                    """);
        }

        void add(CatalogDecision value) throws SQLException {
            String emailHash = value.email().isBlank() ? "" : ProspectingValidation.sha256(value.email());
            String emailCiphertext = value.email().isBlank() ? "" : crypto.encrypt(value.email());
            String addressHash = value.address().isBlank() ? "" : ProspectingValidation.sha256(value.address());
            int index = 1;
            upsert.setString(index++, value.cnpj());
            upsert.setString(index++, value.tradeName());
            upsert.setString(index++, value.status());
            upsert.setString(index++, value.municipalityCode());
            upsert.setString(index++, value.municipality());
            upsert.setString(index++, value.uf());
            upsert.setString(index++, value.primaryCnae());
            upsert.setString(index++, emailHash);
            upsert.setString(index++, emailCiphertext);
            upsert.setString(index++, value.emailDomain());
            upsert.setString(index++, value.email().isBlank() ? "UNAVAILABLE" : "VALID");
            upsert.setString(index++, value.address());
            upsert.setString(index++, addressHash);
            upsert.setString(index++, value.cep());
            upsert.setString(index++, context.sourceVersion());
            if (context.sourceDate() == null) upsert.setNull(index, java.sql.Types.DATE);
            else upsert.setDate(index, Date.valueOf(context.sourceDate()));
            upsert.addBatch();

            deleteCnaes.setString(1, value.cnpj());
            deleteCnaes.addBatch();
            for (CnaeValue cnae : value.cnaes()) {
                insertCnaes.setString(1, value.cnpj());
                insertCnaes.setString(2, cnae.code());
                insertCnaes.setBoolean(3, cnae.primary());
                insertCnaes.addBatch();
            }
            pending++;
        }

        void flush() throws SQLException {
            if (pending == 0) return;
            upsert.executeBatch();
            deleteCnaes.executeBatch();
            insertCnaes.executeBatch();
            pending = 0;
        }

        @Override
        public void close() throws SQLException {
            upsert.close();
            deleteCnaes.close();
            insertCnaes.close();
        }
    }

    enum ImportMode {
        PROSPECTING_ONLY, FULL_CATALOG;

        static boolean isValid(String value) {
            if (value == null || value.isBlank()) return true;
            try { ImportMode.valueOf(value.trim().toUpperCase(Locale.ROOT)); return true; }
            catch (Exception ignored) { return false; }
        }

        static ImportMode parse(String value) {
            if (value == null || value.isBlank()) return FULL_CATALOG;
            return ImportMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private static final class NonClosingInputStream extends java.io.FilterInputStream {
        NonClosingInputStream(java.io.InputStream input) { super(input); }
        @Override public void close() { }
    }
}
