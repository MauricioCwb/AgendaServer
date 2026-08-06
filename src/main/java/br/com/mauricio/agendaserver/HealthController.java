package br.com.mauricio.agendaserver;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.Map;

@RestController
final class HealthController {
    private final DataSource dataSource;

    HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/api/agenda/health")
    Map<String, Object> health() throws Exception {
        boolean database;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT 1")) {
            database = rows.next() && rows.getInt(1) == 1;
        }
        return Map.of(
                "status", database ? "UP" : "DOWN",
                "service", "AgendaServer",
                "database", database ? "UP" : "DOWN",
                "time", OffsetDateTime.now().toString()
        );
    }
}
