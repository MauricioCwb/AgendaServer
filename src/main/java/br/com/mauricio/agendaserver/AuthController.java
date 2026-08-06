package br.com.mauricio.agendaserver;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agenda")
final class AuthController {
    private final AuthenticationService authentication;
    private final ProspectingService prospecting;
    private final AdminAuthorizationService admins;

    AuthController(AuthenticationService authentication, ProspectingService prospecting,
                   AdminAuthorizationService admins) {
        this.authentication = authentication;
        this.prospecting = prospecting;
        this.admins = admins;
    }

    @PostMapping("/auth")
    AuthResponse authenticate(@RequestBody AuthenticationService.AuthRequest request) {
        if (request != null && request.inviteToken() != null && !request.inviteToken().isBlank()) {
            prospecting.validateInvitationEmail(request.inviteToken(), request.email());
        }
        AuthenticationService.AuthResult result = authentication.authenticate(request);
        if (request != null && request.inviteToken() != null && !request.inviteToken().isBlank()) {
            prospecting.registerInvitation(request.inviteToken(), result.email(), result.userId());
        }
        return new AuthResponse(result.userId(), result.email(), result.authToken(), result.created(),
                admins.isAdmin(result.email()), admins.isPrimaryAdmin(result.email()));
    }

    record AuthResponse(String userId, String email, String authToken, boolean created, boolean admin, boolean processLogAdmin) {}
}
