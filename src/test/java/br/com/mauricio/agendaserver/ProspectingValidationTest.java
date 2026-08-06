package br.com.mauricio.agendaserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProspectingValidationTest {
    @Test void normalizesCnpjCnaeCepAndEmail() {
        assertEquals("12345678000190", ProspectingValidation.normalizeCnpj("12.345.678/0001-90"));
        assertEquals("4321500", ProspectingValidation.normalizeCnae("43.21-5/00"));
        assertEquals("18016000", ProspectingValidation.normalizeCep("18016-000"));
        assertEquals("contato@empresa.com.br", ProspectingValidation.normalizeEmail(" CONTATO @EMPRESA.COM.BR "));
    }

    @Test void validatesEmailConservatively() {
        assertTrue(ProspectingValidation.validateEmail("contato@empresa.com.br", false).valid());
        assertEquals("EMAIL_SYNTAX", ProspectingValidation.validateEmail("sem-arroba", false).reason());
        assertEquals("EMAIL_DISPOSABLE", ProspectingValidation.validateEmail("a@mailinator.com", false).reason());
        assertEquals("EMAIL_DOMAIN_INVALID", ProspectingValidation.validateEmail("a@example.com", false).reason());
    }

    @Test void normalizesAndValidatesAddress() {
        String address = ProspectingValidation.normalizeAddress("Rua", "Árvore Azul", "100", "Sala 2", "Centro",
                "18016-000", "Sorocaba", "SP");
        assertEquals("RUA ARVORE AZUL, 100, SALA 2, CENTRO, 18016000, SOROCABA, SP", address);
        assertTrue(ProspectingValidation.validAddress(address, "18016000", "SOROCABA", "SP"));
        assertFalse(ProspectingValidation.validAddress("RUA X", "", "SOROCABA", "SP"));
    }

    @Test void hashesNormalizedEmailDeterministically() {
        String first = ProspectingValidation.sha256(ProspectingValidation.normalizeEmail("A@EMPRESA.COM"));
        String second = ProspectingValidation.sha256(ProspectingValidation.normalizeEmail(" a@empresa.com "));
        assertEquals(first, second);
        assertEquals(64, first.length());
    }

    @Test void checksTwoKilometerRadius() {
        assertTrue(ProspectingValidation.inRadius(-23.5015, -47.4526, -23.5100, -47.4526, 2.0));
        assertFalse(ProspectingValidation.inRadius(-23.5015, -47.4526, -23.5300, -47.4526, 2.0));
    }
}
