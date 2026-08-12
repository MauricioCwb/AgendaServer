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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
final class AiProspectingService {
    private final ObjectMapper json;
    private final HttpClient http;
    private final boolean enabled;
    private final String ollamaBaseUrl;
    private final String configuredOllamaModel;
    private final String ollamaApiKey;
    private final String ollamaWebUrl;
    private final String openAiApiKey;
    private final boolean openAiFallbackEnabled;
    private final String openAiModel;
    private final int fallbackMinCandidates;
    private final int maxQueries;
    private final int maxResultsPerQuery;
    private final int maxPages;
    private volatile String detectedModel = "";

    AiProspectingService(
            ObjectMapper json,
            @Value("${agenda.prospecting.ai.enabled:true}") boolean enabled,
            @Value("${agenda.prospecting.ai.ollama-base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${agenda.prospecting.ai.ollama-model:}") String configuredOllamaModel,
            @Value("${agenda.prospecting.ai.ollama-api-key:}") String ollamaApiKey,
            @Value("${agenda.prospecting.ai.ollama-web-url:https://ollama.com/api}") String ollamaWebUrl,
            @Value("${agenda.prospecting.ai.openai-api-key:}") String openAiApiKey,
            @Value("${agenda.prospecting.ai.openai-fallback-enabled:false}") boolean openAiFallbackEnabled,
            @Value("${agenda.prospecting.ai.openai-model:gpt-5.1}") String openAiModel,
            @Value("${agenda.prospecting.ai.fallback-min-candidates:5}") int fallbackMinCandidates,
            @Value("${agenda.prospecting.ai.max-queries:3}") int maxQueries,
            @Value("${agenda.prospecting.ai.max-results-per-query:6}") int maxResultsPerQuery,
            @Value("${agenda.prospecting.ai.max-pages:8}") int maxPages) {
        this.json = json;
        this.enabled = enabled;
        this.ollamaBaseUrl = stripSlash(ollamaBaseUrl, "http://localhost:11434");
        this.configuredOllamaModel = safe(configuredOllamaModel);
        this.ollamaApiKey = safe(ollamaApiKey);
        this.ollamaWebUrl = stripSlash(ollamaWebUrl, "https://ollama.com/api");
        this.openAiApiKey = safe(openAiApiKey);
        this.openAiFallbackEnabled = openAiFallbackEnabled;
        this.openAiModel = safe(openAiModel).isBlank() ? "gpt-5.1" : safe(openAiModel);
        this.fallbackMinCandidates = clamp(fallbackMinCandidates, 1, 20);
        this.maxQueries = clamp(maxQueries, 1, 5);
        this.maxResultsPerQuery = clamp(maxResultsPerQuery, 1, 10);
        this.maxPages = clamp(maxPages, 1, 20);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    }

    Map<String, Object> adminView() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("aiSearchEnabled", enabled);
        result.put("ollamaBaseUrl", ollamaBaseUrl);
        result.put("ollamaModel", configuredOllamaModel.isBlank() ? (detectedModel.isBlank() ? "auto" : detectedModel) : configuredOllamaModel);
        result.put("ollamaWebSearchConfigured", !ollamaApiKey.isBlank());
        result.put("openAiFallbackEnabled", openAiFallbackEnabled);
        result.put("openAiFallbackConfigured", !openAiApiKey.isBlank());
        result.put("aiFallbackMinCandidates", fallbackMinCandidates);
        return result;
    }

    SearchResult search(SearchRequest request) {
        if (!enabled || request == null) return SearchResult.disabled();
        List<Candidate> combined = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean ollamaAttempted = false;
        boolean openAiAttempted = false;

        if (!ollamaApiKey.isBlank()) {
            ollamaAttempted = true;
            try {
                combined.addAll(searchWithOllama(request));
            } catch (Exception exception) {
                errors.add("Ollama: " + safeError(exception));
            }
        } else {
            errors.add("Ollama Web Search não configurado: defina OLLAMA_API_KEY.");
        }

        if (openAiFallbackEnabled && combined.size() < fallbackMinCandidates && !openAiApiKey.isBlank()) {
            openAiAttempted = true;
            try {
                combined.addAll(searchWithOpenAi(request));
            } catch (Exception exception) {
                errors.add("OpenAI: " + safeError(exception));
            }
        }

        Map<String, Candidate> unique = new LinkedHashMap<>();
        combined.stream().sorted(Comparator.comparingDouble(Candidate::score).reversed()).forEach(candidate -> {
            String key = ProspectingValidation.normalizeEmail(candidate.email());
            if (!key.isBlank()) unique.putIfAbsent(key, candidate);
        });
        List<Candidate> candidates = unique.values().stream().limit(Math.max(5, request.limit())).toList();
        String provider = ollamaAttempted && openAiAttempted ? "OLLAMA+OPENAI" : ollamaAttempted ? "OLLAMA" : openAiAttempted ? "OPENAI" : "";
        return new SearchResult(provider, ollamaAttempted, openAiAttempted, candidates, String.join(" | ", errors));
    }

    private List<Candidate> searchWithOllama(SearchRequest request) throws Exception {
        String model = resolveOllamaModel();
        if (model.isBlank()) throw new IllegalStateException("Nenhum modelo local foi encontrado no Ollama.");
        List<String> queries = generateQueries(model, request);
        Map<String, SearchPage> results = new LinkedHashMap<>();
        for (String query : queries) {
            for (SearchPage page : webSearch(query)) results.putIfAbsent(page.url(), page);
        }
        List<SearchPage> fetched = new ArrayList<>();
        for (SearchPage page : results.values()) {
            if (fetched.size() >= maxPages) break;
            try {
                SearchPage full = webFetch(page.url());
                String content = full.content().isBlank() ? page.content() : full.content();
                fetched.add(new SearchPage(page.title().isBlank() ? full.title() : page.title(), page.url(), content));
            } catch (Exception ignored) {
                fetched.add(page);
            }
        }
        if (fetched.isEmpty()) return List.of();
        return extractCandidatesWithOllama(model, request, fetched);
    }

    private List<String> generateQueries(String model, SearchRequest request) {
        try {
            ObjectNode schema = json.createObjectNode();
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            ObjectNode queries = properties.putObject("queries");
            queries.put("type", "array");
            queries.putObject("items").put("type", "string");
            queries.put("minItems", 1);
            queries.put("maxItems", maxQueries);
            schema.putArray("required").add("queries");

            String prompt = "Gere até " + maxQueries + " consultas curtas para encontrar na web prestadores reais no Brasil. "
                    + "Não invente empresas. Serviço/especialidade: " + request.specialty() + ". Título: " + request.title()
                    + ". Descrição: " + request.description() + ". Localidade de referência: " + request.location()
                    + ". Inclua variações úteis como profissional, empresa, assistência ou técnico quando fizer sentido.";
            JsonNode response = ollamaChat(model, prompt, schema);
            JsonNode parsed = parseJson(response.path("message").path("content").asText("{}"));
            List<String> values = new ArrayList<>();
            for (JsonNode query : parsed.path("queries")) {
                String value = clean(query.asText(), 220);
                if (!value.isBlank() && !values.contains(value)) values.add(value);
                if (values.size() >= maxQueries) break;
            }
            if (!values.isEmpty()) return values;
        } catch (Exception ignored) { }
        String base = clean(request.specialty() + " " + request.location(), 180);
        List<String> fallback = new ArrayList<>();
        fallback.add(base);
        if (fallback.size() < maxQueries) fallback.add("profissional " + base);
        if (fallback.size() < maxQueries) fallback.add("empresa " + base);
        return fallback;
    }

    private List<SearchPage> webSearch(String query) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("query", query);
        body.put("max_results", maxResultsPerQuery);
        JsonNode response = postJson(ollamaWebUrl + "/web_search", body, ollamaApiKey, Duration.ofSeconds(35));
        List<SearchPage> values = new ArrayList<>();
        for (JsonNode item : response.path("results")) {
            String url = clean(item.path("url").asText(), 1000);
            if (url.startsWith("http://") || url.startsWith("https://")) {
                values.add(new SearchPage(clean(item.path("title").asText(), 500), url,
                        clean(item.path("content").asText(), 8000)));
            }
        }
        return values;
    }

    private SearchPage webFetch(String url) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("url", url);
        JsonNode response = postJson(ollamaWebUrl + "/web_fetch", body, ollamaApiKey, Duration.ofSeconds(35));
        return new SearchPage(clean(response.path("title").asText(), 500), url,
                clean(response.path("content").asText(), 12000));
    }

    private List<Candidate> extractCandidatesWithOllama(String model, SearchRequest request, List<SearchPage> pages) throws Exception {
        ObjectNode schema = candidateSchema();
        StringBuilder evidence = new StringBuilder();
        int number = 0;
        for (SearchPage page : pages) {
            evidence.append("\n[FONTE ").append(++number).append("]\nURL: ").append(page.url())
                    .append("\nTÍTULO: ").append(page.title()).append("\nCONTEÚDO:\n")
                    .append(clean(page.content(), 8000)).append('\n');
        }
        String prompt = "Extraia somente prestadores que realmente possam executar o serviço pedido. "
                + "Use APENAS os dados presentes nas fontes abaixo. Não invente e-mail, endereço ou URL. "
                + "Só inclua candidato que possua e-mail público explícito e algum endereço/localização. "
                + "A pontuação de 0 a 100 mede adequação ao pedido. Serviço: " + request.specialty()
                + ". Título: " + request.title() + ". Descrição: " + request.description()
                + ". Localidade: " + request.location() + ".\nFONTES:" + evidence;
        JsonNode response = ollamaChat(model, prompt, schema);
        JsonNode parsed = parseJson(response.path("message").path("content").asText("{}"));
        return verifiedCandidates(parsed, pages, "OLLAMA");
    }

    private JsonNode ollamaChat(String model, String prompt, JsonNode formatSchema) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("think", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", prompt);
        body.set("format", formatSchema);
        body.putObject("options").put("temperature", 0);
        return postJson(ollamaBaseUrl + "/api/chat", body, "", Duration.ofSeconds(120));
    }

    private List<Candidate> searchWithOpenAi(SearchRequest request) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("model", openAiModel);
        ArrayNode tools = body.putArray("tools");
        tools.addObject().put("type", "web_search");
        String prompt = "Pesquise na web prestadores reais no Brasil para o serviço abaixo. Retorne SOMENTE JSON válido, "
                + "sem markdown, no formato {\"candidates\":[{\"name\":\"\",\"email\":\"\",\"phone\":\"\","
                + "\"address\":\"\",\"municipality\":\"\",\"uf\":\"\",\"sourceUrl\":\"\",\"sourceTitle\":\"\","
                + "\"score\":0}]}. Só inclua e-mails públicos encontrados em uma fonte. Não invente contatos. "
                + "Serviço: " + request.specialty() + ". Título: " + request.title() + ". Descrição: "
                + request.description() + ". Localidade: " + request.location() + ". Máximo: " + request.limit() + ".";
        body.put("input", prompt);
        body.put("max_output_tokens", 2500);
        JsonNode response = postJson("https://api.openai.com/v1/responses", body, openAiApiKey, Duration.ofSeconds(120));
        StringBuilder text = new StringBuilder();
        for (JsonNode output : response.path("output")) {
            for (JsonNode part : output.path("content")) {
                if ("output_text".equals(part.path("type").asText())) text.append(part.path("text").asText());
            }
        }
        JsonNode parsed = parseJson(text.toString());
        return basicValidatedCandidates(parsed, "OPENAI");
    }

    List<Candidate> verifiedCandidates(JsonNode parsed, List<SearchPage> pages, String provider) {
        Map<String, SearchPage> byUrl = new LinkedHashMap<>();
        for (SearchPage page : pages) byUrl.put(page.url(), page);
        List<Candidate> values = new ArrayList<>();
        for (JsonNode item : parsed.path("candidates")) {
            String email = ProspectingValidation.normalizeEmail(item.path("email").asText());
            String sourceUrl = clean(item.path("sourceUrl").asText(), 1000);
            SearchPage source = byUrl.get(sourceUrl);
            if (source == null || !ProspectingValidation.validateEmail(email, false).valid()) continue;
            if (!source.content().toLowerCase(Locale.ROOT).contains(email.toLowerCase(Locale.ROOT))) continue;
            Candidate candidate = mapCandidate(item, provider, source.title());
            if (!candidate.address().isBlank()) values.add(candidate);
        }
        return values;
    }

    private List<Candidate> basicValidatedCandidates(JsonNode parsed, String provider) {
        List<Candidate> values = new ArrayList<>();
        for (JsonNode item : parsed.path("candidates")) {
            String email = ProspectingValidation.normalizeEmail(item.path("email").asText());
            String sourceUrl = clean(item.path("sourceUrl").asText(), 1000);
            if (!ProspectingValidation.validateEmail(email, false).valid()) continue;
            if (!(sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://"))) continue;
            Candidate candidate = mapCandidate(item, provider, item.path("sourceTitle").asText());
            if (!candidate.address().isBlank()) values.add(candidate);
        }
        return values;
    }

    private Candidate mapCandidate(JsonNode item, String provider, String fallbackTitle) {
        String email = ProspectingValidation.normalizeEmail(item.path("email").asText());
        return new Candidate(clean(item.path("name").asText(), 250), email,
                clean(item.path("phone").asText(), 60), clean(item.path("address").asText(), 500),
                clean(item.path("municipality").asText(), 120).toUpperCase(Locale.ROOT),
                clean(item.path("uf").asText(), 2).toUpperCase(Locale.ROOT),
                clean(item.path("sourceUrl").asText(), 1000),
                clean(item.path("sourceTitle").asText(fallbackTitle), 500), provider,
                Math.max(0, Math.min(item.path("score").asDouble(50), 100)));
    }

    private ObjectNode candidateSchema() {
        ObjectNode root = json.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ObjectNode candidates = props.putObject("candidates");
        candidates.put("type", "array");
        ObjectNode item = candidates.putObject("items");
        item.put("type", "object");
        ObjectNode p = item.putObject("properties");
        for (String name : List.of("name", "email", "phone", "address", "municipality", "uf", "sourceUrl", "sourceTitle")) {
            p.putObject(name).put("type", "string");
        }
        p.putObject("score").put("type", "number").put("minimum", 0).put("maximum", 100);
        ArrayNode required = item.putArray("required");
        for (String name : List.of("name", "email", "phone", "address", "municipality", "uf", "sourceUrl", "sourceTitle", "score")) required.add(name);
        item.put("additionalProperties", false);
        root.putArray("required").add("candidates");
        root.put("additionalProperties", false);
        return root;
    }

    private String resolveOllamaModel() throws Exception {
        if (!configuredOllamaModel.isBlank()) return configuredOllamaModel;
        if (!detectedModel.isBlank()) return detectedModel;
        HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaBaseUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(15)).GET().header("Accept", "application/json").build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) return "";
        JsonNode body = json.readTree(response.body());
        List<String> models = new ArrayList<>();
        for (JsonNode model : body.path("models")) {
            String name = safe(model.path("name").asText());
            if (!name.isBlank()) models.add(name);
        }
        for (String preferred : List.of("qwen3", "gpt-oss", "llama3.1", "llama3", "gemma3", "mistral")) {
            for (String model : models) if (model.toLowerCase(Locale.ROOT).contains(preferred)) {
                detectedModel = model; return model;
            }
        }
        if (!models.isEmpty()) detectedModel = models.get(0);
        return detectedModel;
    }

    private JsonNode postJson(String url, JsonNode body, String bearer, Duration timeout) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Content-Type", "application/json").header("Accept", "application/json");
        if (bearer != null && !bearer.isBlank()) builder.header("Authorization", "Bearer " + bearer);
        HttpResponse<byte[]> response = http.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        JsonNode parsed = response.body().length == 0 ? json.createObjectNode() : json.readTree(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = parsed.path("error").path("message").asText(parsed.path("error").asText("HTTP " + response.statusCode()));
            throw new IllegalStateException(clean(message, 700));
        }
        return parsed;
    }

    private JsonNode parseJson(String text) throws Exception {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        int first = value.indexOf('{');
        int last = value.lastIndexOf('}');
        if (first >= 0 && last >= first) value = value.substring(first, last + 1);
        return json.readTree(value.isBlank() ? "{}" : value);
    }

    private static String safeError(Exception exception) {
        String message = exception.getMessage();
        return clean(message == null || message.isBlank() ? exception.getClass().getSimpleName() : message, 500);
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(value, max)); }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static String stripSlash(String value, String fallback) {
        String result = safe(value); if (result.isBlank()) result = fallback; return result.replaceAll("/+$", "");
    }
    private static String clean(String value, int max) {
        String result = value == null ? "" : value.trim(); return result.length() > max ? result.substring(0, max) : result;
    }

    record SearchRequest(String specialty, String title, String description, String location, int limit) {}
    record SearchResult(String provider, boolean ollamaAttempted, boolean openAiAttempted,
                        List<Candidate> candidates, String warning) {
        static SearchResult disabled() { return new SearchResult("", false, false, List.of(), "Pesquisa por IA desabilitada."); }
    }
    record Candidate(String name, String email, String phone, String address, String municipality, String uf,
                     String sourceUrl, String sourceTitle, String provider, double score) {}
    record SearchPage(String title, String url, String content) {}
}
