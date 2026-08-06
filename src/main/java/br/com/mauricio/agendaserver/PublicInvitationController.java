package br.com.mauricio.agendaserver;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/agenda/public")
final class PublicInvitationController {
    private final ProspectingService prospecting;

    PublicInvitationController(ProspectingService prospecting) {
        this.prospecting = prospecting;
    }

    @GetMapping("/invitations/{token}")
    ProspectingService.InvitationContext invitation(@PathVariable String token) {
        return prospecting.invitationContext(token);
    }

    @GetMapping("/opt-out/{token}")
    ProspectingService.OptOutContext optOut(@PathVariable String token) {
        return prospecting.optOutContext(token);
    }

    @PostMapping("/opt-out/{token}")
    Map<String, String> confirmOptOut(@PathVariable String token) {
        prospecting.confirmOptOut(token);
        return Map.of("status", "OPTED_OUT", "message", "Descadastro confirmado. Este e-mail não receberá novos convites.");
    }
}
