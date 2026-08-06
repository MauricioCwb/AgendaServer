package br.com.mauricio.agendaserver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
final class ProspectingProcessLogService {
    private static final int MAX_LIMIT = 1000;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    ProspectingProcessLogService(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    void info(String jobId, String stage, String eventCode, String message) {
        write(jobId, stage, eventCode, "INFO", message, null, null, Map.of());
    }

    void info(String jobId, String stage, String eventCode, String message,
              Long recordsCount, Long elapsedMs, Map<String, ?> details) {
        write(jobId, stage, eventCode, "INFO", message, recordsCount, elapsedMs, details);
    }

    void warn(String jobId, String stage, String eventCode, String message) {
        write(jobId, stage, eventCode, "WARN", message, null, null, Map.of());
    }

    void warn(String jobId, String stage, String eventCode, String message,
              Long recordsCount, Long elapsedMs, Map<String, ?> details) {
        write(jobId, stage, eventCode, "WARN", message, recordsCount, elapsedMs, details);
    }

    void error(String jobId, String stage, String eventCode, String message) {
        write(jobId, stage, eventCode, "ERROR", message, null, null, Map.of());
    }

    List<ProcessLogEntry> listByTask(String taskId, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, MAX_LIMIT));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id,job_id,task_id,stage,event_code,level,message,records_count,
                            elapsed_ms,details::text,created_at
                     FROM agenda_prospecting_process_logs
                     WHERE task_id=?
                     ORDER BY created_at DESC,id DESC
                     LIMIT ?
                     """)) {
            query.setString(1, taskId);
            query.setInt(2, limit);
            try (ResultSet rows = query.executeQuery()) {
                java.util.ArrayList<ProcessLogEntry> values = new java.util.ArrayList<>();
                while (rows.next()) {
                    Long recordsCount = nullableLong(rows, "records_count");
                    Long elapsedMs = nullableLong(rows, "elapsed_ms");
                    values.add(new ProcessLogEntry(
                            rows.getLong("id"),
                            rows.getString("job_id"),
                            rows.getString("task_id"),
                            rows.getString("stage"),
                            rows.getString("event_code"),
                            rows.getString("level"),
                            rows.getString("message"),
                            recordsCount,
                            elapsedMs,
                            parseDetails(rows.getString("details")),
                            rows.getTimestamp("created_at").toLocalDateTime()
                    ));
                }
                return List.copyOf(values);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Não foi possível carregar o log do processamento.", exception);
        }
    }

    private void write(String jobId, String stage, String eventCode, String level, String message,
                       Long recordsCount, Long elapsedMs, Map<String, ?> details) {
        if (jobId == null || jobId.isBlank()) return;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO agenda_prospecting_process_logs(
                         job_id,task_id,stage,event_code,level,message,records_count,elapsed_ms,details)
                     SELECT j.id,j.task_id,?,?,?,?,?,?,CAST(? AS jsonb)
                     FROM agenda_prospecting_jobs j
                     WHERE j.id=?
                     """)) {
            insert.setString(1, safe(stage, 40));
            insert.setString(2, safe(eventCode, 80));
            insert.setString(3, safe(level, 12));
            insert.setString(4, safe(message, 500));
            if (recordsCount == null) insert.setNull(5, java.sql.Types.BIGINT);
            else insert.setLong(5, recordsCount);
            if (elapsedMs == null) insert.setNull(6, java.sql.Types.BIGINT);
            else insert.setLong(6, elapsedMs);
            insert.setString(7, objectMapper.writeValueAsString(safeDetails(details)));
            insert.setString(8, jobId);
            insert.executeUpdate();
        } catch (Exception exception) {
            // O log nunca deve interromper o processo principal.
            System.err.println("[AgendaServer][process-log] Falha ao registrar evento " + safe(eventCode, 80)
                    + ": " + safe(exception.getMessage(), 250));
        }
    }

    private Map<String, Object> safeDetails(Map<String, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) return result;
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            if (result.size() >= 20) break;
            String key = safe(entry.getKey(), 60);
            if (key.isBlank()) continue;
            Object value = entry.getValue();
            if (value == null || value instanceof Number || value instanceof Boolean) {
                result.put(key, value);
            } else {
                result.put(key, safe(String.valueOf(value), 250));
            }
        }
        return result;
    }

    private Map<String, Object> parseDetails(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static String safe(String value, int max) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    record ProcessLogEntry(long id, String jobId, String taskId, String stage, String eventCode,
                           String level, String message, Long recordsCount, Long elapsedMs,
                           Map<String, Object> details, LocalDateTime createdAt) {}
}
