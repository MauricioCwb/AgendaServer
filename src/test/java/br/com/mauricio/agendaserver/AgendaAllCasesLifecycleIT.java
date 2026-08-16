package br.com.mauricio.agendaserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ciclo de vida abrangente executado por HTTP real contra o PostgreSQL configurado.
 *
 * <p>Todos os dados possuem um marcador exclusivo por execução. O teste de maior ordem
 * realiza a limpeza funcional e o {@link AfterAll} repete a limpeza como proteção caso
 * algum cenário anterior falhe.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AgendaAllCasesLifecycleIT {
    private static final String DEVICE_HEADER = "X-Agenda-Device-Id";
    private static final String TOKEN_HEADER = "X-Agenda-Auth-Token";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Path UPLOAD_ROOT = Path.of("target", "all-cases-lifecycle-uploads").toAbsolutePath();
    private static final Path KEY_FILE = Path.of("target", "all-cases-lifecycle-prospecting.key").toAbsolutePath();

    private final String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final String emailPrefix = "agenda-all-it-" + runId;
    private final String titlePrefix = "ITALL-" + runId + "-";
    private final List<String> userIds = new ArrayList<>();
    private final List<String> taskIds = new ArrayList<>();

    private ConfigurableApplicationContext context;
    private HttpClient http;
    private ObjectMapper json;
    private JdbcTemplate jdbc;
    private String baseUrl;
    private long specialtyId;
    private boolean finalCleanupExecuted;

    private Session owner;
    private Session otherConsumer;
    private Session provider1;
    private Session provider2;
    private Session provider3;
    private Session provider4;
    private Session distantProvider;

    @BeforeAll
    void startRealApplication() throws Exception {
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
                        "--agenda.prospecting.ai.enabled=false",
                        "--assistant.openai.api-key=",
                        "--agenda.geocoder.provider=mock",
                        "--agenda.upload.dir=" + UPLOAD_ROOT,
                        "--agenda.prospecting.key-file=" + KEY_FILE);
        ServletWebServerApplicationContext web = (ServletWebServerApplicationContext) context;
        baseUrl = "http://127.0.0.1:" + web.getWebServer().getPort();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        json = context.getBean(ObjectMapper.class);
        jdbc = context.getBean(JdbcTemplate.class);
        phase("SERVIDOR", "Servidor real iniciado em " + baseUrl + " usando " + jdbc.getDataSource());
    }

    @AfterAll
    void closeAndGuaranteeCleanup() throws Exception {
        try {
            if (!finalCleanupExecuted && jdbc != null) {
                phase("SEGURANÇA", "Executando limpeza de segurança após falha anterior");
                cleanupAllGeneratedData();
            }
        } finally {
            if (context != null) context.close();
            deleteTestStorage();
        }
    }

    @Test
    @Order(1)
    void accountsProfilesPlansPricesSpecialtiesAndFavorites() throws Exception {
        phase("1/8", "Contas, autenticação, perfis, planos, preços, especialidades e favoritos");
        owner = createSession("owner", "CONSUMER", -23.5015, -47.4526);
        otherConsumer = createSession("buyer", "CONSUMER", -23.5020, -47.4520);
        provider1 = createSession("prestum", "PROVIDER", -23.5016, -47.4525);
        provider2 = createSession("prestdo", "PROVIDER", -23.5030, -47.4510);
        provider3 = createSession("prestre", "PROVIDER", -23.5040, -47.4500);
        provider4 = createSession("prestqu", "BOTH", -23.5050, -47.4490);
        distantProvider = createSession("distante", "PROVIDER", -22.9000, -46.8000);

        JsonNode plans = getJson("/api/agenda/plans", null, 200);
        assertEquals(4, plans.size());
        assertTrue(arrayContains(plans, "code", "FREE_ADS"));
        assertTrue(arrayContains(plans, "code", "PRO"));

        JsonNode specialties = getJson("/api/agenda/specialties", owner, 200);
        assertTrue(specialties.isArray() && !specialties.isEmpty(), "A base real não possui especialidade ativa.");
        specialtyId = specialties.get(0).path("id").asLong();
        assertTrue(specialtyId > 0);

        for (Session provider : List.of(provider1, provider2, provider3, provider4, distantProvider)) {
            putJson("/api/agenda/users/me/profile", Map.of(
                    "name", provider.name(), "role", provider == provider4 ? "BOTH" : "PROVIDER",
                    "bio", "servico local", "specialtyIds", List.of(specialtyId)), provider, 200);
            assertEquals(1, count("SELECT COUNT(*) FROM agenda_user_specialties WHERE user_id=? AND specialty_id=?",
                    provider.userId(), specialtyId));
        }

        putJson("/api/agenda/users/me/prices",
                List.of(Map.of("serviceName", "Visita", "priceCents", 7500)), provider1, 200);
        putJson("/api/agenda/users/me/prices", List.of(
                Map.of("serviceName", "Visita", "priceCents", 7500),
                Map.of("serviceName", "Retorno", "priceCents", 3500)), provider1, 400);
        putJson("/api/agenda/users/me/prices",
                List.of(Map.of("serviceName", "Compra", "priceCents", 100)), owner, 400);
        putJson("/api/agenda/users/me/profile",
                Map.of("name", "Nome1", "role", "CONSUMER", "bio", "", "specialtyIds", List.of()), owner, 400);

        for (Session provider : List.of(provider1, provider2, provider3, provider4)) {
            postJson("/api/agenda/users/me/favorites/" + provider.userId(), null, owner, 200);
        }
        JsonNode favorites = getJson("/api/agenda/users/me/favorites", owner, 200);
        assertEquals(4, favorites.size());
        requestJson("DELETE", "/api/agenda/users/me/favorites/" + provider4.userId(), null, owner, 200);
        assertEquals(3, getJson("/api/agenda/users/me/favorites", owner, 200).size());
        postJson("/api/agenda/users/me/favorites/" + provider4.userId(), null, owner, 200);
        postJson("/api/agenda/users/me/favorites/" + owner.userId(), null, owner, 400);

        postJson("/api/agenda/auth", Map.of(
                "email", owner.email(), "password", "senha-errada", "deviceId", owner.deviceId(),
                "versionCode", 999, "versionName", "all-cases", "register", false, "inviteToken", ""), null, 401);
    }

    @Test
    @Order(2)
    void taskValidationAndWeeklyAndMonthlyRecurrence() throws Exception {
        phase("2/8", "Validações da tarefa e recorrências semanal e mensal");
        LocalDateTime weeklyStart = future(5);
        String weeklyTitle = titlePrefix + "weekly";
        Map<String, Object> weekly = taskBody(weeklyTitle, 1, weeklyStart, List.of());
        weekly.put("recurrenceType", "WEEKLY");
        weekly.put("recurrenceDays", List.of(weeklyStart.getDayOfWeek().getValue()));
        weekly.put("recurrenceUntil", weeklyStart.toLocalDate().plusWeeks(3).format(DATE));
        postJson("/api/agenda/tasks", weekly, owner, 200);
        List<String> weeklyIds = taskIdsForTitle(weeklyTitle);
        assertEquals(4, weeklyIds.size());
        track(weeklyIds);
        for (String taskId : weeklyIds) {
            assertEquals(1, count("SELECT COUNT(*) FROM agenda_prospecting_jobs WHERE task_id=?", taskId));
        }

        LocalDateTime monthlyStart = future(7);
        String monthlyTitle = titlePrefix + "monthly";
        Map<String, Object> monthly = taskBody(monthlyTitle, 1, monthlyStart, List.of());
        monthly.put("recurrenceType", "MONTHLY");
        monthly.put("recurrenceDays", List.of(monthlyStart.getDayOfMonth()));
        monthly.put("recurrenceUntil", monthlyStart.toLocalDate().plusMonths(2).format(DATE));
        postJson("/api/agenda/tasks", monthly, owner, 200);
        List<String> monthlyIds = taskIdsForTitle(monthlyTitle);
        assertTrue(monthlyIds.size() >= 2 && monthlyIds.size() <= 3);
        track(monthlyIds);
        assertTrue(text("SELECT recurrence_label FROM agenda_tasks WHERE id=?", monthlyIds.get(0))
                .startsWith("Repetição mensal"));

        Map<String, Object> invalid = taskBody(titlePrefix + "invalid", 1, future(2), List.of());
        invalid.put("recurrenceType", "YEARLY");
        postJson("/api/agenda/tasks", invalid, owner, 400);
        invalid = taskBody(titlePrefix + "invalid-days", 1, future(2), List.of());
        invalid.put("recurrenceType", "WEEKLY");
        invalid.put("recurrenceDays", List.of());
        invalid.put("recurrenceUntil", future(10).toLocalDate().format(DATE));
        postJson("/api/agenda/tasks", invalid, owner, 400);
        Map<String, Object> invalidTitle = taskBody("x", 1, future(2), List.of());
        postJson("/api/agenda/tasks", invalidTitle, owner, 400);
    }

    @Test
    @Order(3)
    void favoritePriorityAcceptDeclineTimeoutAndOpenMarket() throws Exception {
        phase("3/8", "Prioridades de favoritos: recusa, segundo nível, aceite e decurso de prazo");
        String priorityTask = createTask(titlePrefix + "priority-accept", 1,
                List.of(provider1.userId(), provider2.userId(), provider3.userId()));
        assertEquals("PRIORITY_1", text("SELECT offer_phase FROM agenda_tasks WHERE id=?", priorityTask));
        assertEquals("OFFERED", inviteStatus(priorityTask, provider1));
        assertFalse(containsTask(loadTasks(provider2), priorityTask), "Segundo favorito não deve ver antes da sua rodada.");

        postJson("/api/agenda/tasks/" + priorityTask + "/offer-response",
                Map.of("response", "DECLINED"), provider1, 200);
        assertEquals("DECLINED", inviteStatus(priorityTask, provider1));
        assertEquals("OFFERED", inviteStatus(priorityTask, provider2));
        assertEquals("PRIORITY_2", text("SELECT offer_phase FROM agenda_tasks WHERE id=?", priorityTask));
        assertNotification(owner, priorityTask, "SECOND_LEVEL");

        postJson("/api/agenda/tasks/" + priorityTask + "/offer-response",
                Map.of("response", "ACCEPTED"), provider2, 200);
        assertEquals("CONFIRMED", candidateStatus(priorityTask, provider2));
        assertEquals("FILLED", text("SELECT task_status FROM agenda_tasks WHERE id=?", priorityTask));

        String timeoutTask = createTask(titlePrefix + "priority-timeout", 1,
                List.of(provider1.userId(), provider2.userId()));
        expireCurrentWindow(timeoutTask);
        loadTasks(owner);
        assertEquals("EXPIRED", inviteStatus(timeoutTask, provider1));
        assertEquals("OFFERED", inviteStatus(timeoutTask, provider2));
        assertNotification(owner, timeoutTask, "PRIORITY_TIMEOUT");
        expireCurrentWindow(timeoutTask);
        loadTasks(owner);
        assertEquals("OPEN", text("SELECT offer_phase FROM agenda_tasks WHERE id=?", timeoutTask));
        assertNotification(owner, timeoutTask, "OPEN_MARKET");
        apply(timeoutTask, provider3, -23.5040, -47.4500, "ORIGINAL", 200);

        String thirdLevel = createTask(titlePrefix + "priority-third", 1,
                List.of(provider1.userId(), provider2.userId(), provider3.userId()));
        declineOffer(thirdLevel, provider1);
        declineOffer(thirdLevel, provider2);
        assertEquals("PRIORITY_3", text("SELECT offer_phase FROM agenda_tasks WHERE id=?", thirdLevel));
        declineOffer(thirdLevel, provider3);
        assertEquals("OPEN", text("SELECT offer_phase FROM agenda_tasks WHERE id=?", thirdLevel));
    }

    @Test
    @Order(4)
    void openApplicationsPrivacyDecisionsWithdrawalsAndCapacity() throws Exception {
        phase("4/8", "Candidaturas abertas, distância, privacidade, decisões, desistência e lotação");
        String taskId = createTask(titlePrefix + "applications", 2, List.of());
        apply(taskId, owner, -23.5015, -47.4526, "ORIGINAL", 400);
        apply(taskId, otherConsumer, -23.5020, -47.4520, "ORIGINAL", 400);
        apply(taskId, distantProvider, -22.9000, -46.8000, "ORIGINAL", 400);
        apply(taskId, provider3, -23.5040, -47.4500, "INVALID", 400);

        apply(taskId, provider1, -23.5016, -47.4525, "PROVIDER", 200);
        apply(taskId, provider2, -23.5030, -47.4510, "ORIGINAL", 200);
        JsonNode beforeApproval = taskById(loadTasks(provider1), taskId);
        assertTrue(beforeApproval.path("locationApproximate").asBoolean());
        assertEquals(0, beforeApproval.path("latitude").asDouble(), 0);

        decide(taskId, provider2, "REJECTED", "ORIGINAL", 200);
        assertEquals("REJECTED", candidateStatus(taskId, provider2));
        decide(taskId, provider1, "APPROVED", "PROVIDER", 200);
        JsonNode providerApproved = taskById(loadTasks(provider1), taskId);
        assertFalse(providerApproved.path("locationApproximate").asBoolean());
        assertEquals(-23.5016, providerApproved.path("latitude").asDouble(), 0.000001);
        postJson("/api/agenda/tasks/" + taskId + "/participation-response",
                Map.of("response", "WITHDRAWN"), provider1, 200);
        assertEquals("WITHDRAWN", candidateStatus(taskId, provider1));
        assertNotification(owner, taskId, "WITHDRAWAL");

        apply(taskId, provider3, -23.5040, -47.4500, "ORIGINAL", 200);
        decide(taskId, provider3, "APPROVED", "ORIGINAL", 200);
        postJson("/api/agenda/tasks/" + taskId + "/participation-response",
                Map.of("response", "CONFIRMED"), provider3, 200);
        JsonNode originalApproved = taskById(loadTasks(provider3), taskId);
        assertEquals(-23.5015, originalApproved.path("latitude").asDouble(), 0.000001);
        assertEquals("ACTIVE", text("SELECT task_status FROM agenda_tasks WHERE id=?", taskId));

        apply(taskId, provider4, -23.5050, -47.4490, "ORIGINAL", 200);
        decide(taskId, provider4, "APPROVED", "ORIGINAL", 200);
        postJson("/api/agenda/tasks/" + taskId + "/participation-response",
                Map.of("response", "CONFIRMED"), provider4, 200);
        assertEquals("FILLED", text("SELECT task_status FROM agenda_tasks WHERE id=?", taskId));
        apply(taskId, provider1, -23.5016, -47.4525, "ORIGINAL", 400);
    }

    @Test
    @Order(5)
    void openWindowExpiryReviewExtendCancelAndAuthorization() throws Exception {
        phase("5/8", "Fim da janela aberta, revisão, extensão, cancelamento e autorização");
        String emptyTask = createTask(titlePrefix + "empty-expiry", 1, List.of());
        expireCurrentWindow(emptyTask);
        loadTasks(owner);
        assertEquals("AWAITING_OWNER", text("SELECT task_status FROM agenda_tasks WHERE id=?", emptyTask));
        assertNotification(owner, emptyTask, "NO_CANDIDATES");
        putJson("/api/agenda/tasks/" + emptyTask + "/offer", Map.of("action", "EXTEND"), provider1, 403);
        putJson("/api/agenda/tasks/" + emptyTask + "/offer", Map.of("action", "EXTEND"), owner, 200);
        assertEquals("OPEN", text("SELECT offer_phase FROM agenda_tasks WHERE id=?", emptyTask));
        putJson("/api/agenda/tasks/" + emptyTask + "/offer", Map.of("action", "CANCEL"), owner, 200);
        assertEquals("CANCELLED", text("SELECT task_status FROM agenda_tasks WHERE id=?", emptyTask));

        String reviewTask = createTask(titlePrefix + "review-expiry", 1, List.of());
        apply(reviewTask, provider1, -23.5016, -47.4525, "ORIGINAL", 200);
        expireCurrentWindow(reviewTask);
        loadTasks(owner);
        assertEquals("REVIEW", text("SELECT task_status FROM agenda_tasks WHERE id=?", reviewTask));
        assertNotification(owner, reviewTask, "OFFER_EXPIRED");
        putJson("/api/agenda/tasks/" + reviewTask + "/offer", Map.of("action", "CANCEL"), owner, 200);
    }

    @Test
    @Order(6)
    void notificationsIndividualAndBulkRead() throws Exception {
        phase("6/8", "Leitura individual e em lote das notificações");
        JsonNode unread = getJson("/api/agenda/notifications?unreadOnly=true", owner, 200);
        assertTrue(unread.size() > 0);
        long notificationId = unread.get(0).path("id").asLong();
        putJson("/api/agenda/notifications/" + notificationId + "/read", Map.of(), owner, 200);
        assertEquals(1, count("SELECT COUNT(*) FROM agenda_notifications WHERE id=? AND read_at IS NOT NULL", notificationId));
        putJson("/api/agenda/notifications/read", Map.of(), owner, 200);
        assertEquals(0, getJson("/api/agenda/notifications?unreadOnly=true", owner, 200).size());
        putJson("/api/agenda/notifications/999999999/read", Map.of(), owner, 404);
    }

    @Test
    @Order(7)
    void photoAndVideoUploadDownloadLimitsAndDeletion() throws Exception {
        phase("7/8", "Uploads, downloads, limites e exclusões de fotos e vídeos");
        byte[] invalidImage = "isto nao e uma imagem".getBytes(StandardCharsets.UTF_8);
        multipart("/api/agenda/users/me/photos", "invalida.txt", "text/plain", invalidImage, provider1, 400);

        AgendaService agendaService = context.getBean(AgendaService.class);
        Object originalClassifier = ReflectionTestUtils.getField(agendaService, "mediaClassifier");
        AgendaMediaClassifier classifier = mock(AgendaMediaClassifier.class);
        when(classifier.classify(any(byte[].class), any(String.class))).thenReturn("elétrica");
        ReflectionTestUtils.setField(agendaService, "mediaClassifier", classifier);
        try {
            byte[] png = tinyPng();
            List<String> photos = new ArrayList<>();
            for (int index = 1; index <= 3; index++) {
                JsonNode uploaded = multipart("/api/agenda/users/me/photos", "foto" + index + ".png",
                        "image/png", png, provider1, 200);
                photos.add(uploaded.path("id").asText());
                assertEquals("elétrica", uploaded.path("classification").asText());
            }
            multipart("/api/agenda/users/me/photos", "foto4.png", "image/png", png, provider1, 400);
            assertEquals(3, getJson("/api/agenda/users/me/photos", provider1, 200).size());
            HttpResponse<byte[]> photo = getBytes("/api/agenda/photos/" + photos.get(0), 200);
            assertTrue(photo.headers().firstValue("Content-Type").orElse("").startsWith("image/png"));
            for (String photoId : photos) {
                requestJson("DELETE", "/api/agenda/users/me/photos/" + photoId, null, provider1, 200);
            }
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_photos WHERE user_id=?", provider1.userId()));
        } finally {
            ReflectionTestUtils.setField(agendaService, "mediaClassifier", originalClassifier);
        }

        jdbc.update("UPDATE agenda_users SET plan_code='PRO',founder_free=FALSE WHERE id=?", provider1.userId());
        List<Map<String, Object>> fivePrices = new ArrayList<>();
        for (int index = 1; index <= 5; index++) fivePrices.add(Map.of("serviceName", "Serviço " + index, "priceCents", index * 1000));
        putJson("/api/agenda/users/me/prices", fivePrices, provider1, 200);
        List<Map<String, Object>> sixPrices = new ArrayList<>(fivePrices);
        sixPrices.add(Map.of("serviceName", "Serviço 6", "priceCents", 6000));
        putJson("/api/agenda/users/me/prices", sixPrices, provider1, 400);

        multipart("/api/agenda/users/me/videos", "invalido.bin", "application/octet-stream",
                invalidImage, provider1, 400);
        List<String> videos = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            JsonNode uploaded = multipart("/api/agenda/users/me/videos", "video" + index + ".mp4",
                    "video/mp4", tinyMp4(), provider1, 200);
            videos.add(uploaded.path("id").asText());
        }
        multipart("/api/agenda/users/me/videos", "video6.mp4", "video/mp4", tinyMp4(), provider1, 400);
        assertEquals(5, getJson("/api/agenda/users/me/videos", provider1, 200).size());
        HttpResponse<byte[]> video = getBytes("/api/agenda/videos/" + videos.get(0), 200);
        assertTrue(video.headers().firstValue("Content-Type").orElse("").startsWith("video/mp4"));
        for (String videoId : videos) {
            requestJson("DELETE", "/api/agenda/users/me/videos/" + videoId, null, provider1, 200);
        }
        assertEquals(0, count("SELECT COUNT(*) FROM agenda_videos WHERE user_id=?", provider1.userId()));
    }

    @Test
    @Order(999)
    void finalCleanupRemovesEveryGeneratedRecordFromRealDatabase() throws Exception {
        phase("8/8", "Limpeza final de todos os dados gerados na base real");
        cleanupAllGeneratedData();
        assertNoGeneratedDataRemains();
        finalCleanupExecuted = true;
        phase("LIMPEZA", "Nenhum registro ou arquivo do ciclo abrangente permaneceu");
    }

    private Session createSession(String name, String role, double latitude, double longitude) throws Exception {
        String email = emailPrefix + "-" + name + "@example.invalid";
        String deviceId = "agenda-all-cases-device-" + UUID.randomUUID();
        JsonNode auth = postJson("/api/agenda/auth", Map.of(
                "email", email, "password", "Teste@123", "deviceId", deviceId,
                "versionCode", 999, "versionName", "all-cases", "register", true, "inviteToken", ""), null, 200);
        Session session = new Session(auth.path("userId").asText(), deviceId,
                auth.path("authToken").asText(), email, name);
        assertFalse(session.userId().isBlank());
        assertFalse(session.token().isBlank());
        userIds.add(session.userId());
        postJson("/api/agenda/session", Map.of(
                "name", name, "latitude", latitude, "longitude", longitude, "role", role, "bio", "Teste"), session, 200);
        return session;
    }

    private String createTask(String title, int peopleNeeded, List<String> favorites) throws Exception {
        JsonNode result = postJson("/api/agenda/tasks", taskBody(title, peopleNeeded, future(4), favorites), owner, 200);
        String id = result.path("id").asText();
        assertFalse(id.isBlank());
        track(List.of(id));
        return id;
    }

    private Map<String, Object> taskBody(String title, int peopleNeeded, LocalDateTime startsAt, List<String> favorites) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", "Cenário abrangente do teste " + runId + ".");
        body.put("date", startsAt.format(DATE));
        body.put("time", startsAt.format(TIME));
        body.put("durationHours", 2);
        body.put("peopleNeeded", peopleNeeded);
        body.put("latitude", -23.5015);
        body.put("longitude", -47.4526);
        body.put("recurrenceType", "NONE");
        body.put("recurrenceDays", List.of());
        body.put("recurrenceUntil", "");
        body.put("favoriteProviderIds", favorites);
        body.put("specialtyId", specialtyId);
        return body;
    }

    private void apply(String taskId, Session session, double latitude, double longitude,
                       String location, int expectedStatus) throws Exception {
        postJson("/api/agenda/tasks/" + taskId + "/candidates?latitude=" + latitude
                + "&longitude=" + longitude + "&locationProposal=" + location, null, session, expectedStatus);
    }

    private void decide(String taskId, Session candidate, String status, String location, int expectedStatus) throws Exception {
        putJson("/api/agenda/tasks/" + taskId + "/candidates/" + candidate.userId(),
                Map.of("status", status, "serviceLocation", location), owner, expectedStatus);
    }

    private void declineOffer(String taskId, Session provider) throws Exception {
        postJson("/api/agenda/tasks/" + taskId + "/offer-response", Map.of("response", "DECLINED"), provider, 200);
    }

    private void expireCurrentWindow(String taskId) {
        jdbc.update("UPDATE agenda_tasks SET offer_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE id=?", taskId);
        jdbc.update("UPDATE agenda_task_invites SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE task_id=? AND status='OFFERED'", taskId);
    }

    private JsonNode loadTasks(Session session) throws Exception {
        return getJson("/api/agenda/tasks?latitude=-23.5015&longitude=-47.4526", session, 200);
    }

    private JsonNode taskById(JsonNode tasks, String id) {
        for (JsonNode task : tasks) if (id.equals(task.path("id").asText())) return task;
        throw new AssertionError("Tarefa " + id + " não encontrada na resposta.");
    }

    private boolean containsTask(JsonNode tasks, String id) {
        for (JsonNode task : tasks) if (id.equals(task.path("id").asText())) return true;
        return false;
    }

    private void assertNotification(Session session, String taskId, String type) throws Exception {
        JsonNode notifications = getJson("/api/agenda/notifications?unreadOnly=false", session, 200);
        for (JsonNode notification : notifications) {
            if (taskId.equals(notification.path("taskId").asText()) && type.equals(notification.path("type").asText())) return;
        }
        throw new AssertionError("Notificação " + type + " não encontrada para " + taskId);
    }

    private boolean arrayContains(JsonNode array, String field, String value) {
        for (JsonNode item : array) if (value.equals(item.path(field).asText())) return true;
        return false;
    }

    private String inviteStatus(String taskId, Session provider) {
        return text("SELECT status FROM agenda_task_invites WHERE task_id=? AND provider_id=?", taskId, provider.userId());
    }

    private String candidateStatus(String taskId, Session provider) {
        return text("SELECT status FROM agenda_candidates WHERE task_id=? AND user_id=?", taskId, provider.userId());
    }

    private List<String> taskIdsForTitle(String title) {
        return jdbc.queryForList("SELECT id FROM agenda_tasks WHERE title=? ORDER BY starts_at", String.class, title);
    }

    private void track(List<String> ids) {
        for (String id : ids) if (!taskIds.contains(id)) taskIds.add(id);
    }

    private LocalDateTime future(int days) {
        return LocalDateTime.now().plusDays(days).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    private JsonNode getJson(String path, Session session, int expected) throws Exception {
        return requestJson("GET", path, null, session, expected);
    }

    private JsonNode postJson(String path, Object body, Session session, int expected) throws Exception {
        return requestJson("POST", path, body, session, expected);
    }

    private JsonNode putJson(String path, Object body, Session session, int expected) throws Exception {
        return requestJson("PUT", path, body, session, expected);
    }

    private JsonNode requestJson(String method, String path, Object body, Session session, int expected) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/json");
        if (session != null) request.header(DEVICE_HEADER, session.deviceId()).header(TOKEN_HEADER, session.token());
        if (body != null) request.header("Content-Type", "application/json");
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body));
        request.method(method, publisher);
        HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(expected, response.statusCode(), () -> method + " " + path + " retornou "
                + response.statusCode() + ": " + new String(response.body(), StandardCharsets.UTF_8));
        if (response.body().length == 0) return json.createObjectNode();
        return json.readTree(response.body());
    }

    private JsonNode multipart(String path, String fileName, String contentType, byte[] bytes,
                               Session session, int expected) throws Exception {
        String boundary = "AgendaAllCases" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + fileName + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").header(DEVICE_HEADER, session.deviceId())
                .header(TOKEN_HEADER, session.token()).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(expected, response.statusCode(), () -> "Upload retornou " + response.statusCode() + ": "
                + new String(response.body(), StandardCharsets.UTF_8));
        return response.body().length == 0 ? json.createObjectNode() : json.readTree(response.body());
    }

    private HttpResponse<byte[]> getBytes(String path, int expected) throws Exception {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(expected, response.statusCode());
        return response;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String text(String sql, Object... args) {
        String value = jdbc.queryForObject(sql, String.class, args);
        assertNotNull(value);
        return value;
    }

    private void cleanupAllGeneratedData() {
        List<String> ids = jdbc.queryForList("""
                SELECT t.id FROM agenda_tasks t JOIN agenda_accounts a ON a.id=t.owner_id
                WHERE a.email LIKE ? OR t.title LIKE ?
                """, String.class, emailPrefix + "%", titlePrefix + "%");
        track(ids);
        for (String taskId : ids) {
            jdbc.update("DELETE FROM agenda_notifications WHERE task_id=?", taskId);
            jdbc.update("DELETE FROM agenda_tasks WHERE id=?", taskId);
        }
        jdbc.update("DELETE FROM agenda_accounts WHERE email LIKE ?", emailPrefix + "%");
        deleteTestStorage();
    }

    private void assertNoGeneratedDataRemains() {
        assertEquals(0, count("SELECT COUNT(*) FROM agenda_accounts WHERE email LIKE ?", emailPrefix + "%"));
        assertEquals(0, count("SELECT COUNT(*) FROM agenda_tasks WHERE title LIKE ?", titlePrefix + "%"));
        for (String userId : userIds) {
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_accounts WHERE id=?", userId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_users WHERE id=?", userId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_sessions WHERE user_id=?", userId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_photos WHERE user_id=?", userId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_videos WHERE user_id=?", userId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_service_prices WHERE user_id=?", userId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_favorites WHERE consumer_id=? OR provider_id=?", userId, userId));
        }
        for (String taskId : taskIds) {
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_tasks WHERE id=?", taskId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_candidates WHERE task_id=?", taskId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_task_invites WHERE task_id=?", taskId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_notifications WHERE task_id=?", taskId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_prospecting_jobs WHERE task_id=?", taskId));
            assertEquals(0, count("SELECT COUNT(*) FROM agenda_prospecting_process_logs WHERE task_id=?", taskId));
        }
        assertFalse(Files.exists(UPLOAD_ROOT), "O diretório de mídia do teste permaneceu no disco.");
    }

    private void deleteTestStorage() {
        deleteTree(UPLOAD_ROOT);
        try { Files.deleteIfExists(KEY_FILE); } catch (Exception ignored) { }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private static byte[] tinyPng() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    }

    private static byte[] tinyMp4() {
        return new byte[]{0, 0, 0, 20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0, 'i', 's', 'o', 'm'};
    }

    private static void phase(String phase, String message) {
        System.out.printf("%n[AGENDA-ALL-CASES][%s] %s%n", phase, message);
    }

    private record Session(String userId, String deviceId, String token, String email, String name) { }
}
