package br.com.mauricio.agendaserver;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class ProspectingValidation {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> DISPOSABLE_DOMAINS = Set.of(
            "mailinator.com", "guerrillamail.com", "10minutemail.com", "tempmail.com", "yopmail.com",
            "trashmail.com", "dispostable.com", "getnada.com", "sharklasers.com", "temp-mail.org");
    private static final Set<String> OBVIOUSLY_INVALID_DOMAINS = Set.of(
            "example.com", "example.org", "localhost", "teste.com", "test.com", "email.com.br");

    private ProspectingValidation() {}

    static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    static String normalizeCnpj(String value) {
        String result = digits(value);
        return result.length() == 14 ? result : "";
    }

    static String normalizeCnae(String value) {
        String result = digits(value);
        return result.length() == 7 ? result : "";
    }

    static String normalizeCep(String value) {
        String result = digits(value);
        return result.length() == 8 ? result : "";
    }

    static String normalizeEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    static EmailValidation validateEmail(String value, boolean checkMx) {
        String email = normalizeEmail(value);
        if (email.isBlank()) return EmailValidation.invalid("EMAIL_EMPTY", email, "");
        if (email.length() > 254 || !EMAIL_PATTERN.matcher(email).matches()) {
            return EmailValidation.invalid("EMAIL_SYNTAX", email, domain(email));
        }
        String domain = domain(email);
        if (domain.isBlank() || domain.length() > 190 || domain.startsWith(".") || domain.endsWith(".")) {
            return EmailValidation.invalid("EMAIL_DOMAIN", email, domain);
        }
        if (OBVIOUSLY_INVALID_DOMAINS.contains(domain)) {
            return EmailValidation.invalid("EMAIL_DOMAIN_INVALID", email, domain);
        }
        if (DISPOSABLE_DOMAINS.contains(domain)) {
            return EmailValidation.invalid("EMAIL_DISPOSABLE", email, domain);
        }
        if (checkMx && !hasMx(domain)) {
            return EmailValidation.invalid("EMAIL_NO_MX", email, domain);
        }
        return new EmailValidation(true, "VALID", email, domain);
    }

    static String normalizeAddress(String streetType, String street, String number, String complement,
                                   String district, String cep, String municipality, String uf) {
        String raw = String.join(", ", safe(streetType) + " " + safe(street), safe(number), safe(complement),
                safe(district), normalizeCep(cep), safe(municipality), safe(uf));
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9, /.-]", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("(?:,\\s*){2,}", ", ")
                .trim()
                .replaceAll("^[, ]+|[, ]+$", "")
                .toUpperCase(Locale.ROOT);
        return normalized.length() > 500 ? normalized.substring(0, 500) : normalized;
    }

    static boolean validAddress(String normalizedAddress, String cep, String municipality, String uf) {
        return normalizedAddress != null && normalizedAddress.length() >= 12
                && !normalizeCep(cep).isBlank()
                && municipality != null && municipality.trim().length() >= 2
                && uf != null && uf.trim().matches("[A-Za-z]{2}");
    }

    static String slug(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.length() > 120 ? normalized.substring(0, 120).replaceAll("-+$", "") : normalized;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível gerar hash.", exception);
        }
    }

    static boolean inRadius(double originLat, double originLon, double targetLat, double targetLon, double radiusKm) {
        return AgendaService.distanceKm(originLat, originLon, targetLat, targetLon) <= radiusKm;
    }

    private static String domain(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at < 0 || at == email.length() - 1 ? "" : email.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean hasMx(String domain) {
        try {
            Attributes attributes = new InitialDirContext().getAttributes("dns:/" + domain, new String[]{"MX"});
            Attribute mx = attributes.get("MX");
            return mx != null && mx.size() > 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    record EmailValidation(boolean valid, String reason, String normalized, String domain) {
        static EmailValidation invalid(String reason, String normalized, String domain) {
            return new EmailValidation(false, reason, normalized, domain);
        }
    }
}
