package br.com.mauricio.agendaserver;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
final class SpecialtyService {
    private final DataSource dataSource;

    SpecialtyService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    List<Specialty> activeSpecialties() {
        return listSpecialties(false);
    }

    List<Specialty> allSpecialties() {
        return listSpecialties(true);
    }

    Specialty create(SpecialtyInput input) {
        SpecialtyInput value = validate(input, null);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO agenda_specialties(name,slug,description,active)
                     VALUES(?,?,?,?) RETURNING id
                     """)) {
            insert.setString(1, value.name());
            insert.setString(2, value.slug());
            insert.setString(3, value.description());
            insert.setBoolean(4, value.active());
            try (ResultSet rows = insert.executeQuery()) {
                rows.next();
                return specialty(rows.getLong(1));
            }
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) throw badRequest("Já existe uma especialidade com este nome ou identificador.");
            throw serverError("Não foi possível cadastrar a especialidade.", exception);
        }
    }

    Specialty update(long id, SpecialtyInput input) {
        if (id <= 0) throw badRequest("Especialidade inválida.");
        SpecialtyInput value = validate(input, id);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE agenda_specialties SET name=?,slug=?,description=?,active=?,updated_at=CURRENT_TIMESTAMP
                     WHERE id=?
                     """)) {
            update.setString(1, value.name());
            update.setString(2, value.slug());
            update.setString(3, value.description());
            update.setBoolean(4, value.active());
            update.setLong(5, id);
            if (update.executeUpdate() == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Especialidade não encontrada.");
            return specialty(id);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) throw badRequest("Já existe uma especialidade com este nome ou identificador.");
            throw serverError("Não foi possível atualizar a especialidade.", exception);
        }
    }

    CnaeLink saveCnae(long specialtyId, String originalCode, CnaeInput input) {
        specialty(specialtyId);
        String code = ProspectingValidation.normalizeCnae(input == null ? null : input.cnaeCode());
        if (code.isBlank()) code = ProspectingValidation.normalizeCnae(originalCode);
        if (code.isBlank()) throw badRequest("Informe um CNAE com sete dígitos.");
        String description = clean(input == null ? "" : input.description(), 250);
        boolean primary = input == null || input.matchPrimary();
        boolean secondary = input == null || input.matchSecondary();
        if (!primary && !secondary) throw badRequest("O CNAE deve ser compatível como principal, secundário ou ambos.");
        boolean active = input == null || input.active();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agenda_specialty_cnaes(specialty_id,cnae_code,description,match_primary,match_secondary,active)
                     VALUES(?,?,?,?,?,?)
                     ON CONFLICT(specialty_id,cnae_code) DO UPDATE SET description=EXCLUDED.description,
                       match_primary=EXCLUDED.match_primary,match_secondary=EXCLUDED.match_secondary,
                       active=EXCLUDED.active,updated_at=CURRENT_TIMESTAMP
                     """)) {
            statement.setLong(1, specialtyId);
            statement.setString(2, code);
            statement.setString(3, description);
            statement.setBoolean(4, primary);
            statement.setBoolean(5, secondary);
            statement.setBoolean(6, active);
            statement.executeUpdate();
            return new CnaeLink(code, description, primary, secondary, active);
        } catch (SQLException exception) {
            throw serverError("Não foi possível salvar o CNAE.", exception);
        }
    }

    void deleteCnae(long specialtyId, String cnaeCode) {
        String code = ProspectingValidation.normalizeCnae(cnaeCode);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM agenda_specialty_cnaes WHERE specialty_id=? AND cnae_code=?")) {
            delete.setLong(1, specialtyId);
            delete.setString(2, code);
            if (delete.executeUpdate() == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CNAE não encontrado.");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw serverError("Não foi possível remover o CNAE.", exception);
        }
    }

    Specialty specialty(long id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id,name,slug,description,active FROM agenda_specialties WHERE id=?
                     """)) {
            query.setLong(1, id);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Especialidade não encontrada.");
                return mapSpecialty(connection, rows);
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw serverError("Não foi possível carregar a especialidade.", exception);
        }
    }

    void requireActive(long id) {
        Specialty value = specialty(id);
        if (!value.active()) throw badRequest("A especialidade escolhida está inativa.");
    }

    List<Long> userSpecialtyIds(String userId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT us.specialty_id FROM agenda_user_specialties us
                     JOIN agenda_specialties s ON s.id=us.specialty_id
                     WHERE us.user_id=? AND s.active=TRUE ORDER BY s.name
                     """)) {
            query.setString(1, userId);
            List<Long> values = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) values.add(rows.getLong(1));
            }
            return values;
        } catch (SQLException exception) {
            throw serverError("Não foi possível carregar as especialidades do perfil.", exception);
        }
    }

    void replaceUserSpecialties(String userId, List<Long> requested) {
        Set<Long> ids = new LinkedHashSet<>(requested == null ? List.of() : requested);
        if (ids.size() > 1) throw badRequest("O perfil pode ter somente uma especialidade.");
        for (Long id : ids) {
            if (id == null || id <= 0) throw badRequest("Especialidade inválida.");
            requireActive(id);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM agenda_user_specialties WHERE user_id=?");
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO agenda_user_specialties(user_id,specialty_id) VALUES(?,?)")) {
                delete.setString(1, userId);
                delete.executeUpdate();
                for (Long id : ids) {
                    insert.setString(1, userId);
                    insert.setLong(2, id);
                    insert.addBatch();
                }
                insert.executeBatch();
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw serverError("Não foi possível salvar as especialidades do perfil.", exception);
        }
    }

    Set<String> activeCnaeCodes() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT DISTINCT sc.cnae_code FROM agenda_specialty_cnaes sc
                     JOIN agenda_specialties s ON s.id=sc.specialty_id
                     WHERE sc.active=TRUE AND s.active=TRUE
                     """)) {
            Set<String> result = new LinkedHashSet<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
            return result;
        } catch (SQLException exception) {
            throw serverError("Não foi possível carregar os CNAEs ativos.", exception);
        }
    }

    List<CnaeRule> cnaeRules(long specialtyId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT cnae_code,match_primary,match_secondary FROM agenda_specialty_cnaes
                     WHERE specialty_id=? AND active=TRUE
                     """)) {
            query.setLong(1, specialtyId);
            List<CnaeRule> values = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) values.add(new CnaeRule(rows.getString(1), rows.getBoolean(2), rows.getBoolean(3)));
            }
            return values;
        } catch (SQLException exception) {
            throw serverError("Não foi possível carregar as regras de CNAE.", exception);
        }
    }

    private List<Specialty> listSpecialties(boolean includeInactive) {
        String sql = "SELECT id,name,slug,description,active FROM agenda_specialties"
                + (includeInactive ? "" : " WHERE active=TRUE") + " ORDER BY active DESC,name";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(sql);
             ResultSet rows = query.executeQuery()) {
            List<Specialty> values = new ArrayList<>();
            while (rows.next()) values.add(mapSpecialty(connection, rows));
            return values;
        } catch (SQLException exception) {
            throw serverError("Não foi possível carregar as especialidades.", exception);
        }
    }

    private Specialty mapSpecialty(Connection connection, ResultSet rows) throws SQLException {
        long id = rows.getLong("id");
        List<CnaeLink> cnaes = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT cnae_code,description,match_primary,match_secondary,active
                FROM agenda_specialty_cnaes WHERE specialty_id=? ORDER BY cnae_code
                """)) {
            query.setLong(1, id);
            try (ResultSet cnaeRows = query.executeQuery()) {
                while (cnaeRows.next()) cnaes.add(new CnaeLink(cnaeRows.getString(1), cnaeRows.getString(2),
                        cnaeRows.getBoolean(3), cnaeRows.getBoolean(4), cnaeRows.getBoolean(5)));
            }
        }
        return new Specialty(id, rows.getString("name"), rows.getString("slug"), rows.getString("description"),
                rows.getBoolean("active"), cnaes);
    }

    private SpecialtyInput validate(SpecialtyInput input, Long existingId) {
        if (input == null) throw badRequest("Informe a especialidade.");
        String name = clean(input.name(), 120);
        if (name.length() < 2) throw badRequest("O nome da especialidade deve ter pelo menos dois caracteres.");
        String slug = clean(input.slug(), 120).toLowerCase(Locale.ROOT);
        if (slug.isBlank()) slug = ProspectingValidation.slug(name);
        if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw badRequest("Identificador da especialidade inválido.");
        return new SpecialtyInput(name, slug, clean(input.description(), 500), input.active());
    }

    private static String clean(String value, int max) {
        String result = value == null ? "" : value.trim();
        return result.length() > max ? result.substring(0, max) : result;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException serverError(String message, Exception exception) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message, exception);
    }

    record SpecialtyInput(String name, String slug, String description, boolean active) {}
    record CnaeInput(String cnaeCode, String description, boolean matchPrimary, boolean matchSecondary, boolean active) {}
    record CnaeLink(String cnaeCode, String description, boolean matchPrimary, boolean matchSecondary, boolean active) {}
    record CnaeRule(String code, boolean primary, boolean secondary) {}
    record Specialty(long id, String name, String slug, String description, boolean active, List<CnaeLink> cnaes) {}
}
