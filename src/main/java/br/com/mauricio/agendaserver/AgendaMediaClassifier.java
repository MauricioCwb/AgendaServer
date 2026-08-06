package br.com.mauricio.agendaserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

@Service
final class AgendaMediaClassifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_MODEL = "gpt-5.1";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final String apiKey;
    private final String model;

    AgendaMediaClassifier(
            @Value("${assistant.openai.api-key:}") String apiKey,
            @Value("${agenda.vision.model:gpt-5.1}") String model) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = normalizeModel(model);
    }

    String classify(byte[] image, String mimeType) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Configure OPENAI_API_KEY ou assistant.openai.api-key no application.properties para classificar as fotos.");
        }

        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("model", model);
            body.put("instructions", "Classifique a natureza do serviço profissional mostrado na foto. "
                    + "Responda somente com uma categoria curta em português, em letras minúsculas, por exemplo: "
                    + "jardinagem, pintura, elétrica, limpeza, beleza ou construção. "
                    + "Se não houver serviço identificável, responda: serviço não identificado.");

            ArrayNode input = body.putArray("input");
            ObjectNode message = input.addObject();
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            content.addObject()
                    .put("type", "input_text")
                    .put("text", "Qual é a natureza do serviço desta imagem?");
            content.addObject()
                    .put("type", "input_image")
                    .put("image_url", "data:" + mimeType + ";base64,"
                            + Base64.getEncoder().encodeToString(image))
                    .put("detail", "low");
            body.put("max_output_tokens", 40);

            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)))
                    .build();

            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            JsonNode json = JSON.readTree(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Classificação OpenAI falhou: "
                        + json.path("error").path("message").asText("HTTP " + response.statusCode()));
            }

            StringBuilder text = new StringBuilder();
            for (JsonNode output : json.path("output")) {
                for (JsonNode part : output.path("content")) {
                    if ("output_text".equals(part.path("type").asText())) {
                        text.append(part.path("text").asText());
                    }
                }
            }

            String category = text.toString()
                    .trim()
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-záàâãéêíóôõúç ]", "");
            if (category.isBlank()) {
                category = "serviço não identificado";
            }
            return category.substring(0, Math.min(category.length(), 80));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Classificação da foto interrompida.", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("Não foi possível classificar a foto.", exception);
        }
    }

    private static String normalizeModel(String configuredModel) {
        if (configuredModel == null || configuredModel.isBlank()) {
            return DEFAULT_MODEL;
        }

        String value = configuredModel.trim();
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "gpt-51", "gpt5.1", "gpt-5-1", "gpt-5.6-luna" -> DEFAULT_MODEL;
            default -> value;
        };
    }
}
