package br.com.mauricio.agendaserver;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

final class SingleSpecialtyMigrationTest {
    @Test
    void migrationLimitsProfileToOneSpecialty() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V5__single_profile_specialty.sql"));
        assertTrue(sql.contains("ROW_NUMBER() OVER (PARTITION BY user_id"));
        assertTrue(sql.contains("UNIQUE INDEX"));
        assertTrue(sql.contains("agenda_user_specialties(user_id)"));
    }
}
