package br.com.mauricio.agendaserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
final class ProspectingSettingsService {
    private final DataSource dataSource;
    private final boolean production;
    private final boolean enabled;
    private final boolean dryRun;
    private final double radiusKm;
    private final int perTaskLimit;
    private final int dailyLimit;
    private final int cooldownDays;
    private final int tokenHours;
    private final Path importDir;
    private final String geocoderProvider;
    private final String geocoderUrl;
    private final String geocoderApiKey;
    private final boolean emailCheckMx;
    private final int repeatedEmailThreshold;
    private final String publicWebUrl;
    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUsername;
    private final String smtpPassword;
    private final String smtpFrom;
    private final boolean emailSendingEnabled;
    private final boolean automaticDryRunEnabled;

    ProspectingSettingsService(
            DataSource dataSource,
            @Value("${agenda.production:false}") boolean production,
            @Value("${agenda.prospecting.enabled:false}") boolean enabled,
            @Value("${agenda.prospecting.dry-run:true}") boolean dryRun,
            @Value("${agenda.prospecting.radius-km:2}") double radiusKm,
            @Value("${agenda.prospecting.limit-per-task:20}") int perTaskLimit,
            @Value("${agenda.prospecting.daily-limit:5}") int dailyLimit,
            @Value("${agenda.prospecting.cooldown-days:90}") int cooldownDays,
            @Value("${agenda.prospecting.token-hours:72}") int tokenHours,
            @Value("${agenda.cnpj.import-dir:}") String importDir,
            @Value("${agenda.geocoder.provider:mock}") String geocoderProvider,
            @Value("${agenda.geocoder.url:}") String geocoderUrl,
            @Value("${agenda.geocoder.api-key:}") String geocoderApiKey,
            @Value("${agenda.email.check-mx:false}") boolean emailCheckMx,
            @Value("${agenda.email.repeated-threshold:20}") int repeatedEmailThreshold,
            @Value("${agenda.public-web-url:http://127.0.0.1:5500}") String publicWebUrl,
            @Value("${agenda.smtp.host:}") String smtpHost,
            @Value("${agenda.smtp.port:587}") int smtpPort,
            @Value("${agenda.smtp.username:}") String smtpUsername,
            @Value("${agenda.smtp.password:}") String smtpPassword,
            @Value("${agenda.smtp.from:}") String smtpFrom,
            @Value("${agenda.email.sending-enabled:false}") boolean emailSendingEnabled,
            @Value("${agenda.prospecting.auto-dry-run:true}") boolean automaticDryRunEnabled) {
        this.dataSource = dataSource;
        this.production = production;
        this.enabled = enabled;
        this.dryRun = dryRun;
        this.radiusKm = Math.max(0.1, Math.min(radiusKm, 2.0));
        this.perTaskLimit = Math.max(1, Math.min(perTaskLimit, 20));
        this.dailyLimit = Math.max(1, Math.min(dailyLimit, 1000));
        this.cooldownDays = Math.max(1, Math.min(cooldownDays, 3650));
        this.tokenHours = Math.max(1, Math.min(tokenHours, 720));
        this.importDir = importDir == null || importDir.isBlank() ? null : Path.of(importDir).toAbsolutePath().normalize();
        this.geocoderProvider = safe(geocoderProvider, "mock").toLowerCase(Locale.ROOT);
        this.geocoderUrl = safe(geocoderUrl, "");
        this.geocoderApiKey = safe(geocoderApiKey, "");
        this.emailCheckMx = emailCheckMx;
        this.repeatedEmailThreshold = Math.max(2, Math.min(repeatedEmailThreshold, 10000));
        this.publicWebUrl = stripTrailingSlash(safe(publicWebUrl, "http://127.0.0.1:5500"));
        this.smtpHost = safe(smtpHost, "");
        this.smtpPort = Math.max(1, Math.min(smtpPort, 65535));
        this.smtpUsername = safe(smtpUsername, "");
        this.smtpPassword = safe(smtpPassword, "");
        this.smtpFrom = safe(smtpFrom, smtpUsername);
        this.emailSendingEnabled = emailSendingEnabled;
        this.automaticDryRunEnabled = automaticDryRunEnabled;
    }

    Snapshot snapshot() {
        Map<String, String> overrides = databaseOverrides();
        double minConfidence = parseDouble(overrides.get("geocoder.min_confidence"), 0.75, 0.0, 1.0);
        String triggerMode = overrides.getOrDefault("trigger.mode", "AUTO_IMMEDIATE").trim().toUpperCase(Locale.ROOT);
        if (!triggerMode.equals("MANUAL") && !triggerMode.equals("AUTO_AFTER_INTERNAL") && !triggerMode.equals("AUTO_IMMEDIATE")) {
            triggerMode = "AUTO_IMMEDIATE";
        }
        String municipalities = overrides.getOrDefault("pilot.municipalities", "SOROCABA/SP");
        return new Snapshot(production, enabled, dryRun, radiusKm, perTaskLimit, dailyLimit,
                cooldownDays, tokenHours, importDir, geocoderProvider, geocoderUrl, geocoderApiKey,
                minConfidence, emailCheckMx, repeatedEmailThreshold, publicWebUrl, triggerMode,
                municipalities, smtpHost, smtpPort, smtpUsername, smtpPassword, smtpFrom, emailSendingEnabled, automaticDryRunEnabled);
    }

    Map<String, Object> adminView() {
        Snapshot value = snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("production", value.production());
        result.put("enabled", value.enabled());
        result.put("dryRun", value.dryRun());
        result.put("radiusKm", value.radiusKm());
        result.put("limitPerTask", value.perTaskLimit());
        result.put("dailyLimit", value.dailyLimit());
        result.put("cooldownDays", value.cooldownDays());
        result.put("tokenHours", value.tokenHours());
        result.put("importDirectoryConfigured", value.importDir() != null);
        result.put("geocoderProvider", value.geocoderProvider());
        result.put("geocoderUrlConfigured", !value.geocoderUrl().isBlank());
        result.put("geocoderApiKeyConfigured", !value.geocoderApiKey().isBlank());
        result.put("geocoderMinConfidence", value.minConfidence());
        result.put("emailCheckMx", value.emailCheckMx());
        result.put("repeatedEmailThreshold", value.repeatedEmailThreshold());
        result.put("publicWebUrl", value.publicWebUrl());
        result.put("triggerMode", value.triggerMode());
        result.put("pilotMunicipalities", value.pilotMunicipalities());
        result.put("smtpConfigured", value.smtpConfigured());
        result.put("emailSendingEnabled", value.emailSendingEnabled());
        result.put("automaticDryRunEnabled", value.automaticDryRunEnabled());
        result.put("geocoderProductionReady", value.geocoderProductionReady());
        result.put("realSendingAllowed", value.realSendingAllowed());
        result.put("warning", "Antes do envio real, configure SPF, DKIM e DMARC, valide o descadastro e defina limites de envio.");
        return result;
    }

    void updateEditable(String userId, SettingsUpdate update) {
        if (update == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe as configurações.");
        double confidence = Math.max(0.0, Math.min(update.geocoderMinConfidence(), 1.0));
        String mode = update.triggerMode() == null ? "AUTO_IMMEDIATE" : update.triggerMode().trim().toUpperCase(Locale.ROOT);
        if (!mode.equals("MANUAL") && !mode.equals("AUTO_AFTER_INTERNAL") && !mode.equals("AUTO_IMMEDIATE")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Modo de acionamento inválido.");
        }
        String municipalities = update.pilotMunicipalities() == null ? "" : update.pilotMunicipalities().trim().toUpperCase(Locale.ROOT);
        if (municipalities.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe ao menos um município piloto.");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agenda_prospecting_settings(setting_key,setting_value,updated_by,updated_at)
                     VALUES(?,?,?,CURRENT_TIMESTAMP)
                     ON CONFLICT(setting_key) DO UPDATE SET setting_value=EXCLUDED.setting_value,
                       updated_by=EXCLUDED.updated_by,updated_at=CURRENT_TIMESTAMP
                     """)) {
            put(statement, "geocoder.min_confidence", Double.toString(confidence), userId);
            put(statement, "trigger.mode", mode, userId);
            put(statement, "pilot.municipalities", municipalities, userId);
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Não foi possível salvar as configurações.", exception);
        }
    }

    private Map<String, String> databaseOverrides() {
        Map<String, String> values = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("SELECT setting_key,setting_value FROM agenda_prospecting_settings");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) values.put(rows.getString(1), rows.getString(2));
        } catch (SQLException ignored) {
            // Durante a primeira migração o Flyway pode ainda não ter criado a tabela.
        }
        return values;
    }

    private static void put(PreparedStatement statement, String key, String value, String userId) throws SQLException {
        statement.setString(1, key);
        statement.setString(2, value);
        statement.setString(3, userId);
        statement.executeUpdate();
    }

    private static double parseDouble(String value, double fallback, double min, double max) {
        try { return Math.max(min, Math.min(Double.parseDouble(value), max)); }
        catch (Exception ignored) { return fallback; }
    }

    private static String safe(String value, String fallback) {
        String result = value == null ? "" : value.trim();
        return result.isEmpty() ? fallback : result;
    }

    private static String stripTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    record SettingsUpdate(double geocoderMinConfidence, String triggerMode, String pilotMunicipalities) {}

    record Snapshot(boolean production, boolean enabled, boolean dryRun, double radiusKm, int perTaskLimit,
                    int dailyLimit, int cooldownDays, int tokenHours, Path importDir,
                    String geocoderProvider, String geocoderUrl, String geocoderApiKey,
                    double minConfidence, boolean emailCheckMx, int repeatedEmailThreshold,
                    String publicWebUrl, String triggerMode, String pilotMunicipalities,
                    String smtpHost, int smtpPort, String smtpUsername, String smtpPassword, String smtpFrom,
                    boolean emailSendingEnabled, boolean automaticDryRunEnabled) {
        Duration tokenDuration() { return Duration.ofHours(tokenHours); }
        boolean smtpConfigured() { return !smtpHost.isBlank() && !smtpFrom.isBlank(); }
        boolean geocoderProductionReady() {
            if (geocoderProvider == null || geocoderProvider.isBlank() || "mock".equalsIgnoreCase(geocoderProvider)) return false;
            if (geocoderUrl == null || geocoderUrl.isBlank()) return false;
            String normalized = geocoderUrl.toLowerCase(Locale.ROOT);
            return !normalized.contains("nominatim.openstreetmap.org");
        }
        boolean realSendingAllowed() {
            return emailSendingEnabled && production && enabled && !dryRun && smtpConfigured() && geocoderProductionReady();
        }
    }
}
