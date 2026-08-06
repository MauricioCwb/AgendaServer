package br.com.mauricio.agendaserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExternalInviteMailerTest {
    @Test void invitationIsTransparentAndOptional() {
        String body = ExternalInviteMailer.body("contato@empresa.com.br", "Eletricista", "Sorocaba/SP", 1.4,
                "10/08/2026", "https://agenda.test/?invite=x", "https://agenda.test/?optout=y");
        assertTrue(body.contains("cadastro público do CNPJ"));
        assertTrue(body.contains("cadastro e a participação são opcionais"));
        assertTrue(body.contains("não há promessa de contratação ou renda"));
        assertTrue(body.contains("contato@empresa.com.br"));
        assertTrue(body.contains("descadastro"));
    }
}
