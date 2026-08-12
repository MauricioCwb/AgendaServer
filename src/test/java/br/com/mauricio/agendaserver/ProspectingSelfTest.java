package br.com.mauricio.agendaserver;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class ProspectingSelfTest {
    private ProspectingSelfTest() {}

    public static void main(String[] args) throws Exception {
        require("4321500".equals(ProspectingValidation.normalizeCnae("43.21-5/00")), "normalização de CNAE");
        require(ProspectingValidation.validateEmail("contato@empresa.com.br", false).valid(), "e-mail válido");
        require(!ProspectingValidation.validateEmail("x@mailinator.com", false).valid(), "e-mail descartável");
        require(ProspectingValidation.inRadius(-23.5015, -47.4526, -23.5100, -47.4526, 2), "raio de 2 km");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 30; i++) values.add("e" + i);
        require(ProspectingRules.distinctLimited(values, value -> value, 50).size() == 30, "pool configurável");
        LocalDateTime now = LocalDateTime.of(2026, 8, 3, 12, 0);
        require(ProspectingRules.withinCooldown(now.minusDays(10), 90, now), "cooldown");
        require(!ProspectingRules.tokenUsable("a".repeat(64), now.minusHours(1), now), "token expirado");
        require(ProspectingRules.cnaeMatches(true, true, false), "CNAE principal");
        require(ProspectingRules.cnaeMatches(false, false, true), "CNAE secundário");
        require(ProspectingRules.repeatedEmail(20, 20), "e-mail repetido");
        require(ProspectingRules.reusableGeocodeCache("VALID", -23.5, -47.4, 0.9, 0.75, "ADDRESS"), "cache de geocodificação");
        require(!ProspectingRules.reusableGeocodeCache("INVALID", -23.5, -47.4, 0.9, 0.75, "ADDRESS"), "retry de geocodificação inválida");
        require("PENDING".equals(ProspectingRules.recoverJobState("FILTERING", 0, 0, 0)), "retomada de job");
        require(ProspectingRules.registrationEmailMatches("A@EMPRESA.COM", " a@empresa.com "), "vínculo por e-mail");
        require(ProspectingRules.resumeFileIndex(3, 10) == 3, "retomada da importação");
        require(ConfigurableGeocoder.isPublicNominatim("https://nominatim.openstreetmap.org/search"), "bloqueio do Nominatim público");
        try (SemicolonCsvReader csv = new SemicolonCsvReader(new StringReader("\"A;B\";C\n"))) {
            require(csv.next().equals(List.of("A;B", "C")), "CSV em streaming");
        }
        Geocoder.Result geocode = ConfigurableGeocoder.mock("RUA TESTE, 100, SOROCABA, SP");
        require(geocode.success() && geocode.confidence() >= 0.75, "geocodificador falso");
        String invitation = ExternalInviteMailer.body("contato@empresa.com.br", "Eletricista", "Sorocaba/SP", 1.0,
                "10/08/2026", "https://agenda/?invite=x", "https://agenda/?optout=y");
        require(invitation.contains("opcionais") && invitation.contains("descadastro"), "texto do convite");
        System.out.println("ProspectingSelfTest: 21 verificações concluídas sem falhas.");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError("Falha em: " + label);
    }
}
