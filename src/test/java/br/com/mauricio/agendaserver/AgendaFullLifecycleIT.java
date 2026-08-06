package br.com.mauricio.agendaserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste de integração de ponta a ponta do AgendaJá.
 *
 * <p>O teste inicia o AgendaServer programaticamente em uma porta aleatória,
 * usa HTTP real contra o Tomcat embutido, grava no PostgreSQL configurado,
 * valida cada fase do ciclo e remove todos os registros temporários ao final.</p>
 *
 * <p>Não usa {@code @SpringBootTest}, {@code SpringExtension}, MockMvc ou o
 * bootstrap do Spring TestContext. Isso evita que a execução dependa da
 * descoberta de {@code @SpringBootConfiguration} feita pelo launcher da IDE.</p>
 */
public class AgendaFullLifecycleIT {
    private static final String DEVICE_HEADER = "X-Agenda-Device-Id";
    private static final String TOKEN_HEADER = "X-Agenda-Auth-Token";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final String emailPrefix = "agenda-it-" + runId;
    private final String taskTitle = "Teste ciclo completo " + runId;
    private final List<String> createdUserIds = new ArrayList<>();
    private final List<String> createdTaskIds = new ArrayList<>();

    private ConfigurableApplicationContext context;
    private HttpClient http;
    private ObjectMapper objectMapper;
    private JdbcTemplate jdbc;
    private String baseUrl;

    @Test
    void executesCompleteLifecycleAtProviderLocationAndRemovesEveryCreatedRecord() throws Exception {
        executeCompleteLifecycle("PROVIDER");
    }

    @Test
    void executesCompleteLifecycleAtRequesterLocationWhenProviderSuggestionIsRefusedAndRemovesEveryCreatedRecord()
            throws Exception {
        executeCompleteLifecycle("ORIGINAL");
    }

    private void executeCompleteLifecycle(String agreedLocation) throws Exception {
        if (isPortOpen(28212)) {
            throw new IllegalStateException(
                    "Encerre o AgendaServer da porta 28212 antes de executar o teste completo.");
        }

        try {
            startApplication();

            printPhase("1/10", "Criando conta e sessão do consumidor");
            TestSession consumer = createSession(
                    emailPrefix + "-consumer@example.invalid", "cliente", "CONSUMER");

            printPhase("2/10", "Criando conta e sessão do prestador");
            TestSession provider = createSession(
                    emailPrefix + "-provider@example.invalid", "prest", "PROVIDER");

            long specialtyId = firstActiveSpecialty(consumer);

            printPhase("3/10", "Associando uma especialidade ao perfil do prestador");
            putJson("/api/agenda/users/me/specialties",
                    Map.of("specialtyIds", List.of(specialtyId)), provider, 200);
            assertEquals(1, count(
                    "SELECT COUNT(*) FROM agenda_user_specialties WHERE user_id=? AND specialty_id=?",
                    provider.userId(), specialtyId));

            printPhase("4/10", "Publicando o compromisso pelo consumidor");
            LocalDateTime start = LocalDateTime.now().plusDays(2).withSecond(0).withNano(0);
            Map<String, Object> taskRequest = Map.ofEntries(
                    Map.entry("title", taskTitle),
                    Map.entry("description", "Compromisso criado pelo teste completo do AgendaJá."),
                    Map.entry("date", start.format(DATE)),
                    Map.entry("time", start.format(TIME)),
                    Map.entry("durationHours", 2),
                    Map.entry("peopleNeeded", 1),
                    Map.entry("latitude", -23.5015),
                    Map.entry("longitude", -47.4526),
                    Map.entry("recurrenceType", "NONE"),
                    Map.entry("recurrenceDays", List.of()),
                    Map.entry("recurrenceUntil", ""),
                    Map.entry("favoriteProviderIds", List.of()),
                    Map.entry("specialtyId", specialtyId));

            JsonNode createdTask = postJson("/api/agenda/tasks", taskRequest, consumer, 200);
            String taskId = createdTask.path("id").asText();
            assertFalse(taskId.isBlank(), "A API não retornou o ID da atividade criada.");
            createdTaskIds.add(taskId);

            assertEquals(1, count(
                    "SELECT COUNT(*) FROM agenda_tasks WHERE id=? AND owner_id=?",
                    taskId, consumer.userId()));
            assertEquals(1, count(
                    "SELECT COUNT(*) FROM agenda_tasks WHERE id=? AND specialty_id=?",
                    taskId, specialtyId));
            assertEquals("ACTIVE", text("SELECT task_status FROM agenda_tasks WHERE id=?", taskId));
            assertEquals(1, count("SELECT COUNT(*) FROM agenda_prospecting_jobs WHERE task_id=?", taskId));
            assertEquals(1, count("""
                    SELECT COUNT(*) FROM agenda_prospecting_process_logs l
                    JOIN agenda_prospecting_jobs j ON j.id=l.job_id
                    WHERE j.task_id=? AND l.event_code='JOB_CREATED'
                    """, taskId));

            printPhase("5/10", "Confirmando que o prestador visualiza a atividade aberta");
            JsonNode providerTasks = loadTasks(provider, -23.5016, -47.4525);
            assertTrue(containsTask(providerTasks, taskId),
                    "A atividade criada não apareceu para o prestador dentro do raio permitido.");
            JsonNode providerTaskBeforeApplication = taskById(providerTasks, taskId);
            assertTrue(providerTaskBeforeApplication.path("locationApproximate").asBoolean(),
                    "O local exato foi exposto antes da aprovação.");
            assertEquals(0, providerTaskBeforeApplication.path("latitude").asDouble(), 0.0,
                    "Nenhuma coordenada do pedido deve ser enviada antes da aprovação.");

            printPhase("6/10", "Prestador enviando a proposta");
            postJson("/api/agenda/tasks/" + taskId
                            + "/candidates?latitude=-23.5016&longitude=-47.4525&locationProposal=PROVIDER",
                    null, provider, 200);
            assertEquals("PENDING", text(
                    "SELECT status FROM agenda_candidates WHERE task_id=? AND user_id=?",
                    taskId, provider.userId()));
            assertEquals("PROVIDER", text(
                    "SELECT location_proposal FROM agenda_candidates WHERE task_id=? AND user_id=?",
                    taskId, provider.userId()));
            assertEquals(1, count("""
                    SELECT COUNT(*) FROM agenda_notifications
                    WHERE user_id=? AND task_id=? AND type='NEW_CANDIDATE'
                    """, consumer.userId(), taskId));
            assertNotification(loadNotifications(consumer), taskId, "NEW_CANDIDATE");

            JsonNode ownerTaskAfterApplication = taskById(
                    loadTasks(consumer, -23.5015, -47.4526), taskId);
            assertNotNull(ownerTaskAfterApplication, "O consumidor não recuperou a própria atividade.");
            assertEquals(1, ownerTaskAfterApplication.path("candidates").size());
            assertEquals("PENDING",
                    ownerTaskAfterApplication.path("candidates").get(0).path("status").asText());
            assertEquals("PROVIDER",
                    ownerTaskAfterApplication.path("candidates").get(0).path("locationProposal").asText());
            assertTrue(ownerTaskAfterApplication.path("candidates").get(0).path("serviceLatitude").isNull(),
                    "O local do prestador foi exposto antes da aprovação.");

            printPhase("7/10", "Consumidor aprovando a proposta no local "
                    + ("PROVIDER".equals(agreedLocation) ? "do prestador" : "original do pedido"));
            putJson("/api/agenda/tasks/" + taskId + "/candidates/" + provider.userId(),
                    Map.of("status", "APPROVED", "serviceLocation", agreedLocation), consumer, 200);
            assertEquals("APPROVED", text(
                    "SELECT status FROM agenda_candidates WHERE task_id=? AND user_id=?",
                    taskId, provider.userId()));
            assertEquals(agreedLocation, text(
                    "SELECT agreed_location FROM agenda_candidates WHERE task_id=? AND user_id=?",
                    taskId, provider.userId()));
            JsonNode ownerTaskAfterDecision = taskById(loadTasks(consumer, -23.5015, -47.4526), taskId);
            JsonNode ownerCandidateAfterDecision = ownerTaskAfterDecision.path("candidates").get(0);
            if ("PROVIDER".equals(agreedLocation)) {
                assertEquals(-23.5016, ownerCandidateAfterDecision.path("serviceLatitude").asDouble(), 0.000001,
                        "O solicitante deve receber o local exato do prestador após aceitá-lo.");
            } else {
                assertTrue(ownerCandidateAfterDecision.path("serviceLatitude").isNull(),
                        "O local do prestador deve permanecer oculto quando a sugestão for recusada.");
            }
            assertEquals(1, count("""
                    SELECT COUNT(*) FROM agenda_notifications
                    WHERE user_id=? AND task_id=? AND type='CANDIDATE_DECISION'
                    """, provider.userId(), taskId));
            assertNotification(loadNotifications(provider), taskId, "CANDIDATE_DECISION");

            printPhase("8/10", "Prestador abrindo a proposta aprovada pela notificação");
            JsonNode providerApprovedTask = taskById(
                    loadTasks(provider, -23.5016, -47.4525), taskId);
            assertNotNull(providerApprovedTask,
                    "A notificação aponta para uma atividade que o prestador não consegue abrir.");
            assertEquals("APPROVED",
                    providerApprovedTask.path("candidates").get(0).path("status").asText());
            assertFalse(providerApprovedTask.path("locationApproximate").asBoolean());
            double expectedServiceLatitude = "PROVIDER".equals(agreedLocation) ? -23.5016 : -23.5015;
            assertEquals(expectedServiceLatitude, providerApprovedTask.path("latitude").asDouble(), 0.000001);

            printPhase("9/10", "Prestador confirmando a participação aprovada");
            postJson("/api/agenda/tasks/" + taskId + "/participation-response",
                    Map.of("response", "CONFIRMED"), provider, 200);
            assertEquals("CONFIRMED", text(
                    "SELECT status FROM agenda_candidates WHERE task_id=? AND user_id=?",
                    taskId, provider.userId()));
            assertEquals("FILLED", text("SELECT task_status FROM agenda_tasks WHERE id=?", taskId));
            assertEquals("FILLED", text("SELECT offer_phase FROM agenda_tasks WHERE id=?", taskId));
            assertEquals(1, count("""
                    SELECT COUNT(*) FROM agenda_notifications
                    WHERE user_id=? AND task_id=? AND type='CONFIRMATION'
                    """, consumer.userId(), taskId));
            assertNotification(loadNotifications(consumer), taskId, "CONFIRMATION");

            JsonNode ownerTaskCompleted = taskById(
                    loadTasks(consumer, -23.5015, -47.4526), taskId);
            assertNotNull(ownerTaskCompleted);
            assertEquals("FILLED", ownerTaskCompleted.path("taskStatus").asText());
            assertEquals("CONFIRMED",
                    ownerTaskCompleted.path("candidates").get(0).path("status").asText());

            printPhase("10/10", "Validando todos os registros pertinentes no PostgreSQL");
            assertEquals(2, count(
                    "SELECT COUNT(*) FROM agenda_accounts WHERE id IN (?,?)",
                    consumer.userId(), provider.userId()));
            assertEquals(2, count(
                    "SELECT COUNT(*) FROM agenda_sessions WHERE user_id IN (?,?)",
                    consumer.userId(), provider.userId()));
            assertEquals(2, count(
                    "SELECT COUNT(*) FROM agenda_users WHERE id IN (?,?)",
                    consumer.userId(), provider.userId()));
            assertEquals(1, count("SELECT COUNT(*) FROM agenda_candidates WHERE task_id=?", taskId));
            assertTrue(count("SELECT COUNT(*) FROM agenda_notifications WHERE task_id=?", taskId) >= 3);
            assertEquals(1, count("SELECT COUNT(*) FROM agenda_prospecting_jobs WHERE task_id=?", taskId));
            assertTrue(count(
                    "SELECT COUNT(*) FROM agenda_prospecting_process_logs WHERE task_id=?", taskId) >= 1);

            printPhase("FIM", "Fluxo completo validado. Iniciando limpeza dos registros de teste");
        } finally {
            try {
                if (jdbc != null) {
                    cleanupCreatedRecords();
                    assertNoTestRecordsRemain();
                    printPhase("LIMPEZA", "Todos os registros criados pelo teste foram removidos");
                }
            } finally {
                closeApplication();
            }
        }
    }

    private void startApplication() {
        context = new SpringApplicationBuilder(AgendaServerApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--spring.main.register-shutdown-hook=false",
                        "--agenda.scheduling.enabled=false",
                        "--agenda.prospecting.enabled=false",
                        "--agenda.prospecting.dry-run=true",
                        "--agenda.prospecting.auto-dry-run=false",
                        "--agenda.email.sending-enabled=false",
                        "--agenda.geocoder.provider=mock",
                        "--agenda.upload.dir=target/full-lifecycle-test-uploads",
                        "--agenda.prospecting.key-file=target/full-lifecycle-test-prospecting.key");

        if (!(context instanceof ServletWebServerApplicationContext webContext)) {
            throw new IllegalStateException("O teste não iniciou um contexto web do Spring Boot.");
        }

        int port = webContext.getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        objectMapper = context.getBean(ObjectMapper.class);
        jdbc = context.getBean(JdbcTemplate.class);
        printPhase("SERVIDOR", "AgendaServer de teste iniciado em " + baseUrl);
    }

    private void closeApplication() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    private TestSession createSession(String email, String name, String role) throws Exception {
        String deviceId = "agenda-integration-device-" + UUID.randomUUID();
        Map<String, Object> authRequest = Map.of(
                "email", email,
                "password", "Teste@123",
                "deviceId", deviceId,
                "versionCode", 999,
                "versionName", "integration-test",
                "register", true,
                "inviteToken", "");

        JsonNode auth = postJson("/api/agenda/auth", authRequest, null, 200);
        String userId = auth.path("userId").asText();
        String token = auth.path("authToken").asText();
        assertFalse(userId.isBlank(), "A autenticação não retornou userId.");
        assertFalse(token.isBlank(), "A autenticação não retornou authToken.");
        createdUserIds.add(userId);

        Map<String, Object> sessionRequest = Map.of(
                "name", name,
                "latitude", -23.5015,
                "longitude", -47.4526,
                "role", role,
                "bio", "Teste");
        postJson("/api/agenda/session", sessionRequest,
                new TestSession(userId, deviceId, token), 200);
        return new TestSession(userId, deviceId, token);
    }

    private long firstActiveSpecialty(TestSession session) throws Exception {
        JsonNode specialties = getJson("/api/agenda/specialties", session, 200);
        assertTrue(specialties.isArray(), "A API de especialidades não retornou uma lista.");
        assertTrue(specialties.size() > 0, "Nenhuma especialidade ativa foi carregada.");
        long specialtyId = specialties.get(0).path("id").asLong();
        assertTrue(specialtyId > 0, "A primeira especialidade não possui ID válido.");
        return specialtyId;
    }

    private JsonNode loadTasks(TestSession session, double latitude, double longitude) throws Exception {
        return getJson("/api/agenda/tasks?latitude=" + latitude + "&longitude=" + longitude,
                session, 200);
    }

    private JsonNode loadNotifications(TestSession session) throws Exception {
        return getJson("/api/agenda/notifications?unreadOnly=false", session, 200);
    }

    private void assertNotification(JsonNode notifications, String taskId, String type) {
        assertTrue(notifications.isArray(), "A API de notificações não retornou uma lista.");
        for (JsonNode notification : notifications) {
            if (taskId.equals(notification.path("taskId").asText())
                    && type.equals(notification.path("type").asText())) {
                return;
            }
        }
        throw new AssertionError(
                "Notificação " + type + " vinculada à atividade " + taskId + " não encontrada.");
    }

    private JsonNode getJson(String path, TestSession session, int expectedStatus) throws Exception {
        return requestJson("GET", path, null, session, expectedStatus);
    }

    private JsonNode postJson(String path, Object body, TestSession session, int expectedStatus)
            throws Exception {
        return requestJson("POST", path, body, session, expectedStatus);
    }

    private JsonNode putJson(String path, Object body, TestSession session, int expectedStatus)
            throws Exception {
        return requestJson("PUT", path, body, session, expectedStatus);
    }

    private JsonNode requestJson(
            String method, String path, Object body, TestSession session, int expectedStatus) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");

        if (session != null) {
            builder.header(DEVICE_HEADER, session.deviceId());
            builder.header(TOKEN_HEADER, session.token());
        }

        String bodyText = body == null ? "" : objectMapper.writeValueAsString(body);
        if (body != null) {
            builder.header("Content-Type", "application/json; charset=UTF-8");
        }

        switch (method) {
            case "GET" -> builder.GET();
            case "POST" -> builder.POST(body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(bodyText, StandardCharsets.UTF_8));
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(bodyText, StandardCharsets.UTF_8));
            default -> throw new IllegalArgumentException("Método HTTP não suportado no teste: " + method);
        }

        HttpResponse<String> response = http.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != expectedStatus) {
            throw new AssertionError(method + " " + path + " retornou HTTP "
                    + response.statusCode() + ", esperado " + expectedStatus
                    + ". Corpo: " + response.body());
        }

        if (response.body() == null || response.body().isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(response.body());
    }

    private boolean containsTask(JsonNode tasks, String taskId) {
        return taskById(tasks, taskId) != null;
    }

    private JsonNode taskById(JsonNode tasks, String taskId) {
        if (!tasks.isArray()) {
            return null;
        }
        for (JsonNode task : tasks) {
            if (taskId.equals(task.path("id").asText())) {
                return task;
            }
        }
        return null;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private void cleanupCreatedRecords() {
        List<String> taskIds = jdbc.queryForList(
                """
                SELECT id FROM agenda_tasks
                WHERE title=? OR owner_id IN (
                    SELECT id FROM agenda_accounts WHERE email LIKE ?
                )
                """,
                String.class, taskTitle, emailPrefix + "%");

        for (String taskId : taskIds) {
            jdbc.update("DELETE FROM agenda_notifications WHERE task_id=?", taskId);
            jdbc.update("DELETE FROM agenda_tasks WHERE id=?", taskId);
        }
        jdbc.update("DELETE FROM agenda_accounts WHERE email LIKE ?", emailPrefix + "%");
    }

    private void assertNoTestRecordsRemain() {
        assertEquals(0, count(
                "SELECT COUNT(*) FROM agenda_accounts WHERE email LIKE ?", emailPrefix + "%"));
        assertEquals(0, count("SELECT COUNT(*) FROM agenda_tasks WHERE title=?", taskTitle));

        for (String userId : createdUserIds) {
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_accounts WHERE id=?", userId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_users WHERE id=?", userId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_sessions WHERE user_id=?", userId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_user_specialties WHERE user_id=?", userId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_notifications WHERE user_id=?", userId));
        }

        for (String taskId : createdTaskIds) {
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_tasks WHERE id=?", taskId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_candidates WHERE task_id=?", taskId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_notifications WHERE task_id=?", taskId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_prospecting_jobs WHERE task_id=?", taskId));
            assertEquals(0, count(
                    "SELECT COUNT(*) FROM agenda_prospecting_process_logs WHERE task_id=?", taskId));
        }
    }

    private static boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void printPhase(String phase, String message) {
        System.out.printf("%n[AGENDA-FULL-CYCLE][%s] %s%n", phase, message);
    }

    private record TestSession(String userId, String deviceId, String token) {
    }
}
