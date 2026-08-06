package br.com.mauricio.agendaserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

@Service
final class ConfigurableGeocoder implements Geocoder {
    private final ProspectingSettingsService settings;
    private final ObjectMapper objectMapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    ConfigurableGeocoder(ProspectingSettingsService settings, ObjectMapper objectMapper) {
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    @Override
    public Result geocode(String normalizedAddress) {
        ProspectingSettingsService.Snapshot configuration = settings.snapshot();
        String provider = configuration.geocoderProvider();
        if ("mock".equals(provider)) return mock(normalizedAddress);
        if (configuration.geocoderUrl().isBlank()) return Result.failure(provider, "URL do geocodificador não configurada.");
        if (isPublicNominatim(configuration.geocoderUrl())) {
            return Result.failure(provider, "O servidor público nominatim.openstreetmap.org não pode ser usado para processamento em massa.");
        }
        try {
            String encoded = URLEncoder.encode(normalizedAddress, StandardCharsets.UTF_8);
            String url = configuration.geocoderUrl().contains("{address}")
                    ? configuration.geocoderUrl().replace("{address}", encoded)
                    : configuration.geocoderUrl() + (configuration.geocoderUrl().contains("?") ? "&" : "?") + "address=" + encoded;
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30)).GET().header("Accept", "application/json");
            if (!configuration.geocoderApiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + configuration.geocoderApiKey());
                builder.header("X-Api-Key", configuration.geocoderApiKey());
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Result.failure(provider, "Geocodificador respondeu HTTP " + response.statusCode() + ".");
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode node = root.isArray() && !root.isEmpty() ? root.get(0) : root;
            double lat = number(node, "latitude", number(node, "lat", Double.NaN));
            double lon = number(node, "longitude", number(node, "lon", Double.NaN));
            double confidence = number(node, "confidence", number(node, "importance", 0.0));
            String precision = text(node, "precision", text(node, "type", "UNKNOWN"));
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) return Result.failure(provider, "Resposta sem coordenadas válidas.");
            return new Result(true, lat, lon, Math.max(0, Math.min(confidence, 1)), precision.toUpperCase(Locale.ROOT), provider, "");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.failure(provider, "Geocodificação interrompida.");
        } catch (Exception exception) {
            return Result.failure(provider, "Falha temporária do geocodificador: " + safeMessage(exception));
        }
    }

    static boolean isPublicNominatim(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            String host = URI.create(url).getHost();
            return host != null && host.equalsIgnoreCase("nominatim.openstreetmap.org");
        } catch (Exception ignored) {
            return url.toLowerCase(Locale.ROOT).contains("nominatim.openstreetmap.org");
        }
    }

    static Result mock(String normalizedAddress) {
        String hash = ProspectingValidation.sha256(normalizedAddress);
        long first = Long.parseUnsignedLong(hash.substring(0, 12), 16);
        long second = Long.parseUnsignedLong(hash.substring(12, 24), 16);
        double lat = -23.5015 + ((first % 20000) - 10000) / 1_000_000.0;
        double lon = -47.4526 + ((second % 20000) - 10000) / 1_000_000.0;
        return new Result(true, lat, lon, 0.99, "ADDRESS", "mock", "");
    }

    private static double number(JsonNode node, String name, double fallback) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || value.isNull()) return fallback;
        if (value.isNumber()) return value.asDouble();
        try { return Double.parseDouble(value.asText()); }
        catch (Exception ignored) { return fallback; }
    }

    private static String text(JsonNode node, String name, String fallback) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() || value.asText().isBlank() ? fallback : value.asText();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.length() > 180 ? message.substring(0, 180) : message;
    }
}
