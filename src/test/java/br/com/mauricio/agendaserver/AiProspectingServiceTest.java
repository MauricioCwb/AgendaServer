package br.com.mauricio.agendaserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AiProspectingServiceTest {
    private HttpServer server;
    private final AtomicInteger chatCalls = new AtomicInteger();
    private final AtomicInteger searchCalls = new AtomicInteger();
    private final AtomicInteger fetchCalls = new AtomicInteger();

    @AfterEach void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test void orchestratesSearchFetchAndExtractionWithSourceEvidence() throws Exception {
        AiProspectingService.SearchResult result = search("contato@eletrica.com.br", "contato@eletrica.com.br");

        assertTrue(result.ollamaAttempted());
        assertEquals("OLLAMA", result.provider());
        assertEquals(1, result.candidates().size(), result.warning() + " calls="
                + chatCalls + "/" + searchCalls + "/" + fetchCalls);
        assertEquals("contato@eletrica.com.br", result.candidates().getFirst().email());
        assertEquals("https://example.test/eletrica", result.candidates().getFirst().sourceUrl());
    }

    @Test void rejectsEmailInventedByModelWhenItIsAbsentFromSource() throws Exception {
        AiProspectingService.SearchResult result = search("publico@eletrica.com.br", "inventado@eletrica.com.br");

        assertTrue(result.ollamaAttempted());
        assertTrue(result.candidates().isEmpty());
    }

    @Test void verifierAcceptsOnlyCandidateSupportedByTheExactSource() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AiProspectingService service = service(json, "http://127.0.0.1:1");
        var parsed = json.readTree(candidateJson("contato@eletrica.com.br"));
        var source = new AiProspectingService.SearchPage("Elétrica", "https://example.test/eletrica",
                "Rua das Flores, 10. contato@eletrica.com.br");

        assertEquals(1, service.verifiedCandidates(parsed, java.util.List.of(source), "OLLAMA").size());
    }

    private AiProspectingService.SearchResult search(String evidenceEmail, String candidateEmail) throws Exception {
        ObjectMapper json = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            chatCalls.incrementAndGet();
            boolean queryRequest = json.readTree(exchange.getRequestBody())
                    .path("format").path("properties").has("queries");
            String content = queryRequest
                    ? "{\"queries\":[\"eletricista Sorocaba\"]}"
                    : candidateJson(candidateEmail);
            var response = json.createObjectNode();
            response.putObject("message").put("content", content);
            respond(exchange, response.toString());
        });
        server.createContext("/web_search", exchange -> {
            searchCalls.incrementAndGet();
            respond(exchange, "{\"results\":[{\"title\":\"Elétrica Teste\",\"url\":\"https://example.test/eletrica\",\"content\":\"\"}]}");
        });
        server.createContext("/web_fetch", exchange -> {
            fetchCalls.incrementAndGet();
            respond(exchange, "{\"title\":\"Elétrica Teste\",\"content\":\"Eletricista em Sorocaba, Rua das Flores, 10. Email: "
                    + evidenceEmail + "\"}");
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        AiProspectingService service = service(json, baseUrl);
        return service.search(new AiProspectingService.SearchRequest(
                "Eletricista", "Instalação elétrica", "Trocar tomada", "Sorocaba/SP", 20));
    }

    private static AiProspectingService service(ObjectMapper json, String baseUrl) {
        return new AiProspectingService(json, true, baseUrl, "test-model", "test-key",
                baseUrl, "", false, "gpt-5.1", 5, 3, 6, 8);
    }

    private static String candidateJson(String email) {
        return "{\"candidates\":[{\"name\":\"Elétrica Teste\",\"email\":\"" + email
                + "\",\"phone\":\"\",\"address\":\"Rua das Flores, 10, Sorocaba/SP\","
                + "\"municipality\":\"Sorocaba\",\"uf\":\"SP\","
                + "\"sourceUrl\":\"https://example.test/eletrica\",\"sourceTitle\":\"Elétrica Teste\",\"score\":90}]}";
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
