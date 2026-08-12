package br.com.mauricio.agendaserver;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CnpjCatalogImportTest {
    @Test void fullCatalogKeepsInactiveCompanyAndAllItsCnaes() {
        List<String> row = receitaRow();
        row.set(5, "08");

        CnpjImportService.CatalogDecision result = CnpjImportService.inspectCatalog(
                row, Map.of("7145", "Sorocaba"));

        assertTrue(result.accepted());
        assertEquals("12345678000195", result.cnpj());
        assertEquals("08", result.status());
        assertEquals("SOROCABA", result.municipality());
        assertEquals("4321500", result.primaryCnae());
        assertEquals(List.of("4321500", "9521500", "3314707"),
                result.cnaes().stream().map(CnpjImportService.CnaeValue::code).toList());
        assertTrue(result.cnaes().getFirst().primary());
        assertFalse(result.cnaes().get(1).primary());
    }

    @Test void fullCatalogRetainsRecordButDiscardsMalformedEmail() {
        List<String> row = receitaRow();
        row.set(27, "email-invalido");

        CnpjImportService.CatalogDecision result = CnpjImportService.inspectCatalog(
                row, Map.of("7145", "Sorocaba"));

        assertTrue(result.accepted());
        assertEquals("", result.email());
        assertEquals("", result.emailDomain());
        assertTrue(result.address().contains("SOROCABA"));
    }

    @Test void rejectsRowsThatCannotIdentifyACnpj() {
        assertFalse(CnpjImportService.inspectCatalog(List.of("curta"), Map.of()).accepted());
    }

    private static List<String> receitaRow() {
        List<String> row = new ArrayList<>();
        for (int index = 0; index < 28; index++) row.add("");
        row.set(0, "12345678"); row.set(1, "0001"); row.set(2, "95");
        row.set(4, "ELETRICA TESTE"); row.set(5, "02");
        row.set(11, "4321500"); row.set(12, "9521500,3314707");
        row.set(13, "RUA"); row.set(14, "DAS FLORES"); row.set(15, "10");
        row.set(17, "CENTRO"); row.set(18, "18000000"); row.set(19, "SP");
        row.set(20, "7145"); row.set(27, "CONTATO@EXEMPLO.COM.BR");
        return row;
    }
}
