package br.com.mauricio.agendaserver;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SemicolonCsvReaderTest {
    @Test void parsesQuotedSemicolonsAndEscapedQuotes() throws Exception {
        try (SemicolonCsvReader reader = new SemicolonCsvReader(new StringReader("\"A;B\";\"C\"\"D\";E\n1;2;3\n"))) {
            assertEquals(List.of("A;B", "C\"D", "E"), reader.next());
            assertEquals(List.of("1", "2", "3"), reader.next());
            assertNull(reader.next());
        }
    }
}
