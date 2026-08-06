package br.com.mauricio.agendaserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
final class AdminAuthorizationService {
    private static final String PRIMARY_ADMIN_EMAIL = "lixocwb@gmail.com";
    private final Set<String> adminEmails;

    AdminAuthorizationService(@Value("${agenda.admin.emails:lixocwb@gmail.com}") String configuredEmails) {
        this.adminEmails = Arrays.stream(configuredEmails.split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    boolean isAdmin(String email) {
        return email != null && adminEmails.contains(email.trim().toLowerCase(Locale.ROOT));
    }

    void requireAdmin(AgendaService.AgendaUser user) {
        if (user == null || !isAdmin(user.email())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Somente o administrador autorizado pode executar esta operação.");
        }
    }

    boolean isPrimaryAdmin(String email) {
        return email != null && PRIMARY_ADMIN_EMAIL.equalsIgnoreCase(email.trim());
    }

    void requireSettingsAdmin(AgendaService.AgendaUser user) {
        requirePrimaryAdmin(user, "As configurações de precisão e piloto são restritas ao administrador principal.");
    }

    void requireProcessLogAdmin(AgendaService.AgendaUser user) {
        requirePrimaryAdmin(user, "O log operacional é restrito exclusivamente a lixocwb@gmail.com.");
    }

    private void requirePrimaryAdmin(AgendaService.AgendaUser user, String message) {
        if (user == null || !isPrimaryAdmin(user.email())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
        }
    }
}
