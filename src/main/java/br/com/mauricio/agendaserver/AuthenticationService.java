package br.com.mauricio.agendaserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.spec.KeySpec;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

@Service
final class AuthenticationService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataSource dataSource;
    private final int sessionDays;

    AuthenticationService(DataSource dataSource, @Value("${agenda.session.days:90}") int sessionDays) {
        this.dataSource = dataSource;
        this.sessionDays = Math.max(1, Math.min(sessionDays, 365));
    }

    AuthResult authenticate(AuthRequest request) {
        if (request == null) throw badRequest("Informe os dados de acesso.");
        String email = normalizeEmail(request.email());
        String password = request.password() == null ? "" : request.password();
        String deviceId = request.deviceId() == null ? "" : request.deviceId().trim();
        if (!EMAIL.matcher(email).matches() || email.length() > 254) throw badRequest("Informe um e-mail válido.");
        if (password.length() < 6 || password.length() > 200) throw badRequest("A senha deve ter de 6 a 200 caracteres.");
        if (deviceId.length() < 20 || deviceId.length() > 200) throw badRequest("Identificador do dispositivo inválido.");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Account account = findAccount(connection, email);
                boolean created = false;
                if (account == null) {
                    if (!request.register()) {
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                                "Conta não encontrada. Marque a opção de cadastro para criar uma conta.");
                    }
                    account = createAccount(connection, email, password);
                    created = true;
                } else if (!verifyPassword(password, account.passwordHash())) {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha incorretos.");
                }

                String token = randomToken();
                String tokenHash = sha256(token);
                try (PreparedStatement cleanup = connection.prepareStatement(
                        "DELETE FROM agenda_sessions WHERE expires_at <= CURRENT_TIMESTAMP OR (user_id=? AND device_id=?)")) {
                    cleanup.setString(1, account.id());
                    cleanup.setString(2, deviceId);
                    cleanup.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO agenda_sessions(token_hash,user_id,device_id,version_code,version_name,expires_at)
                        VALUES(?,?,?,?,?,?)
                        """)) {
                    insert.setString(1, tokenHash);
                    insert.setString(2, account.id());
                    insert.setString(3, deviceId);
                    insert.setInt(4, Math.max(0, request.versionCode()));
                    insert.setString(5, clean(request.versionName(), 40));
                    insert.setObject(6, LocalDateTime.now().plusDays(sessionDays));
                    insert.executeUpdate();
                }
                connection.commit();
                return new AuthResult(account.id(), account.email(), token, created);
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Não foi possível autenticar no AgendaServer.", exception);
        }
    }

    Account authenticate(Connection connection, String deviceId, String rawToken) throws SQLException {
        if (deviceId == null || deviceId.length() < 20 || deviceId.length() > 200
                || rawToken == null || rawToken.length() < 40 || rawToken.length() > 200) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida. Entre novamente.");
        }
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT a.id,a.email,a.account_name
                FROM agenda_sessions s
                JOIN agenda_accounts a ON a.id=s.user_id
                WHERE s.token_hash=? AND s.device_id=? AND s.expires_at>CURRENT_TIMESTAMP AND a.enabled=TRUE
                LIMIT 1
                """)) {
            query.setString(1, sha256(rawToken));
            query.setString(2, deviceId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Sessão inválida ou expirada. Entre novamente.");
                Account account = new Account(rows.getString(1), rows.getString(2), "", rows.getString(3));
                touchSession(connection, sha256(rawToken));
                return account;
            }
        }
    }

    private Account findAccount(Connection connection, String email) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id,email,password_hash,account_name FROM agenda_accounts WHERE LOWER(email)=LOWER(?) FOR UPDATE")) {
            query.setString(1, email);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                return new Account(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4));
            }
        }
    }

    private Account createAccount(Connection connection, String email, String password) throws SQLException {
        String id = UUID.randomUUID().toString();
        String name = clean(email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z]", "").toLowerCase(Locale.ROOT), 120);
        if (name.length() < 2) name = "usuario";
        String hash = hashPassword(password);
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO agenda_accounts(id,email,password_hash,account_name)
                VALUES(?,?,?,?)
                """)) {
            insert.setString(1, id);
            insert.setString(2, email);
            insert.setString(3, hash);
            insert.setString(4, name);
            insert.executeUpdate();
        }
        return new Account(id, email, hash, name);
    }

    private void touchSession(Connection connection, String tokenHash) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE agenda_sessions SET last_seen_at=CURRENT_TIMESTAMP WHERE token_hash=?")) {
            update.setString(1, tokenHash);
            update.executeUpdate();
        }
    }


    private static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        int iterations = 210_000;
        byte[] derived = derivePassword(password, salt, iterations);
        return "pbkdf2$" + iterations + "$" + HexFormat.of().formatHex(salt) + "$" + HexFormat.of().formatHex(derived);
    }

    private static boolean verifyPassword(String password, String stored) {
        try {
            String[] parts = stored.split("\\$", -1);
            if (parts.length != 4 || !"pbkdf2".equals(parts[0])) return false;
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = HexFormat.of().parseHex(parts[2]);
            byte[] expected = HexFormat.of().parseHex(parts[3]);
            byte[] actual = derivePassword(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static byte[] derivePassword(String password, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível proteger a senha.", exception);
        }
    }

    private static String normalizeEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String clean(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    record AuthRequest(String email, String password, String deviceId, int versionCode, String versionName, boolean register, String inviteToken) {}
    record AuthResult(String userId, String email, String authToken, boolean created) {}
    record Account(String id, String email, String passwordHash, String name) {}
}
