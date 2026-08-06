package br.com.mauricio.agendaserver;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ProspectingRules {
    private ProspectingRules() {}

    static int perTaskLimit(int configured) {
        return Math.max(1, Math.min(configured, 20));
    }

    static int dailyAllowance(int alreadySent, int configuredLimit) {
        return Math.max(0, Math.max(1, configuredLimit) - Math.max(0, alreadySent));
    }

    static boolean withinCooldown(LocalDateTime lastSentAt, int cooldownDays, LocalDateTime now) {
        if (lastSentAt == null) return false;
        LocalDateTime reference = now == null ? LocalDateTime.now() : now;
        return lastSentAt.isAfter(reference.minusDays(Math.max(1, cooldownDays)));
    }

    static boolean tokenUsable(String token, LocalDateTime expiresAt, LocalDateTime now) {
        if (token == null || !token.matches("[a-fA-F0-9]{64}") || expiresAt == null) return false;
        LocalDateTime reference = now == null ? LocalDateTime.now() : now;
        return expiresAt.isAfter(reference);
    }

    static boolean taskAcceptsInvites(String taskStatus, int approvedOrConfirmed, int peopleNeeded) {
        return "ACTIVE".equals(taskStatus) && approvedOrConfirmed < Math.max(1, peopleNeeded);
    }

    static <T> List<T> distinctLimited(List<T> values, java.util.function.Function<T, String> keyExtractor,
                                       int configuredLimit) {
        if (values == null || values.isEmpty()) return List.of();
        Set<String> keys = new LinkedHashSet<>();
        java.util.ArrayList<T> result = new java.util.ArrayList<>();
        int limit = perTaskLimit(configuredLimit);
        for (T value : values) {
            String key = keyExtractor.apply(value);
            if (key != null && keys.add(key)) result.add(value);
            if (result.size() >= limit) break;
        }
        return List.copyOf(result);
    }


    static boolean cnaeMatches(boolean primaryCnae, boolean matchPrimary, boolean matchSecondary) {
        return primaryCnae ? matchPrimary : matchSecondary;
    }

    static boolean repeatedEmail(int occurrences, int threshold) {
        return occurrences >= Math.max(2, threshold);
    }

    static boolean reusableGeocodeCache(String status, Double latitude, Double longitude,
                                         Double confidence, double minimumConfidence,
                                         String precision) {
        return "VALID".equalsIgnoreCase(status)
                && latitude != null && longitude != null && confidence != null
                && Double.isFinite(latitude) && Double.isFinite(longitude)
                && confidence >= Math.max(0, Math.min(minimumConfidence, 1))
                && addressLevelPrecision(precision);
    }

    static boolean addressLevelPrecision(String precision) {
        if (precision == null || precision.isBlank()) return false;
        String value = precision.trim().toUpperCase(java.util.Locale.ROOT);
        return Set.of("ADDRESS", "HOUSE", "BUILDING", "ROOFTOP", "STREET", "PARCEL", "POINT", "PREMISE")
                .stream().anyMatch(value::contains);
    }

    static String recoverJobState(String state, int queued, int sent, int failures) {
        String normalized = state == null ? "" : state.trim().toUpperCase(java.util.Locale.ROOT);
        if ("FILTERING".equals(normalized) || "GEOCODING".equals(normalized)) return "PENDING";
        if ("SENDING".equals(normalized)) {
            if (queued > 0) return "READY";
            if (sent > 0 && failures > 0) return "PARTIAL";
            if (sent > 0) return "SENT";
            return failures > 0 ? "FAILED" : "READY";
        }
        return normalized;
    }

    static boolean registrationEmailMatches(String expected, String supplied) {
        return ProspectingValidation.normalizeEmail(expected)
                .equals(ProspectingValidation.normalizeEmail(supplied));
    }

    static int resumeFileIndex(int filesProcessed, int fileCount) {
        return Math.max(0, Math.min(filesProcessed, Math.max(0, fileCount)));
    }

    static Duration tokenDuration(int hours) {
        return Duration.ofHours(Math.max(1, Math.min(hours, 720)));
    }
}
