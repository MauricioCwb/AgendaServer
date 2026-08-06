package br.com.mauricio.agendaserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;

@Service
final class ProspectingCryptoService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] key;

    ProspectingCryptoService(
            @Value("${agenda.prospecting.data-key:}") String encodedKey,
            @Value("${agenda.prospecting.key-file:${user.home}/appdata/agenda/prospecting.key}") String keyFile) {
        byte[] configured = decode(encodedKey);
        if (configured == null) configured = loadOrCreateKey(keyFile);
        this.key = configured;
    }

    boolean configured() { return key != null; }

    String encrypt(String value) {
        requireConfigured();
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
                    + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível proteger o contato.", exception);
        }
    }

    String decrypt(String protectedValue) {
        requireConfigured();
        try {
            String[] parts = protectedValue.split("\\.", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException("Formato inválido");
            byte[] iv = Base64.getUrlDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getUrlDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível ler o contato protegido.", exception);
        }
    }

    private static byte[] loadOrCreateKey(String configuredPath) {
        try {
            Path path = Path.of(configuredPath).toAbsolutePath().normalize();
            if (Files.exists(path)) {
                byte[] loaded = decode(Files.readString(path, StandardCharsets.US_ASCII));
                if (loaded != null) return loaded;
                throw new IllegalStateException("O arquivo da chave de prospecção existe, mas não contém uma chave Base64 válida de 32 bytes: " + path);
            }
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            byte[] generated = new byte[32];
            RANDOM.nextBytes(generated);
            String encoded = Base64.getEncoder().encodeToString(generated);
            try {
                Files.writeString(path, encoded + System.lineSeparator(), StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return generated;
            } catch (java.nio.file.FileAlreadyExistsException race) {
                return decode(Files.readString(path, StandardCharsets.US_ASCII));
            }
        } catch (Exception exception) {
            return null;
        }
    }

    private static byte[] decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(value.trim());
            return decoded.length == 32 ? decoded : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void requireConfigured() {
        if (key == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Não foi possível carregar ou criar a chave local de proteção dos contatos. Verifique AGENDA_PROSPECT_DATA_KEY ou AGENDA_PROSPECT_KEY_FILE.");
        }
    }
}
