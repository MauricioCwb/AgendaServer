package br.com.mauricio.agendaserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

@Service
final class AgendaService {
    private static final long MAX_PHOTO_BYTES = 5L * 1024L * 1024L;
    private static final long MAX_VIDEO_BYTES = 30L * 1024L * 1024L;
    private static final double MAX_DISTANCE_KM = 50.0;
    private static final DateTimeFormatter INPUT_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter OUTPUT_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter OUTPUT_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final DataSource dataSource;
    private final AuthenticationService authentication;
    private final Path photoRoot;
    private final Path videoRoot;
    private final AtomicBoolean storageReady = new AtomicBoolean(false);
    private final AgendaMarketplaceService marketplace;
    private final AgendaMediaClassifier mediaClassifier;
    private final SpecialtyService specialties;
    private final ProspectingService prospecting;

    AgendaService(
            DataSource dataSource,
            AuthenticationService authentication,
            AgendaMarketplaceService marketplace,
            AgendaMediaClassifier mediaClassifier,
            SpecialtyService specialties,
            ProspectingService prospecting,
            @Value("${agenda.upload.dir:${user.home}/appdata/agenda}") String uploadDirectory) {
        this.dataSource = dataSource;
        this.authentication = authentication;
        this.marketplace = marketplace;
        this.mediaClassifier = mediaClassifier;
        this.specialties = specialties;
        this.prospecting = prospecting;
        Path root = Path.of(uploadDirectory).toAbsolutePath().normalize();
        this.photoRoot = root.resolve("photos").normalize();
        this.videoRoot = root.resolve("videos").normalize();
    }

    AgendaMarketplaceService marketplace() { return marketplace; }

    AgendaUser openSession(String deviceId, String authToken, SessionRequest request) {
        validateCredential(deviceId, "identificador do dispositivo");
        validateCredential(authToken, "token de autenticação");
        if (request == null || request.name() == null || !request.name().matches("[a-z]{2,8}")) {
            throw badRequest("O nome deve ter de 2 a 8 letras minúsculas, sem números, espaços ou acentos.");
        }
        validateCoordinates(request.latitude(), request.longitude());
        ensureSchema();
        try (Connection connection = connection()) {
            Account account = authenticatedAccount(connection, deviceId, authToken);
            String displayName = clean(request.name(), 120);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO agenda_users(id, installation_id, device_secret_hash, display_name, latitude, longitude)
                    VALUES(?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET display_name=EXCLUDED.display_name, latitude=EXCLUDED.latitude,
                      longitude=EXCLUDED.longitude, last_seen_at=CURRENT_TIMESTAMP
                    """)) {
                insert.setString(1, account.userId());
                insert.setString(2, "account:" + account.userId());
                insert.setString(3, AuthenticationService.sha256("account:" + account.userId()));
                insert.setString(4, displayName);
                insert.setDouble(5, request.latitude());
                insert.setDouble(6, request.longitude());
                insert.executeUpdate();
            }
            marketplace.initializeUser(account.userId());
            marketplace.updateProfile(account.userId(), new AgendaMarketplaceService.ProfileUpdate(
                    displayName, request.role() == null ? "BOTH" : request.role(), request.bio(), null));
            return new AgendaUser(account.userId(), displayName, account.email());
        } catch (SQLException exception) {
            throw serverError("Não foi possível abrir a sessão da Agenda.", exception);
        }
    }

    AgendaUser authenticate(String deviceId, String authToken) {
        validateCredential(deviceId, "identificador do dispositivo");
        validateCredential(authToken, "token de autenticação");
        ensureSchema();
        try (Connection connection = connection()) {
            Account account = authenticatedAccount(connection, deviceId, authToken);
            try (PreparedStatement select = connection.prepareStatement("SELECT display_name FROM agenda_users WHERE id=?")) {
                select.setString(1, account.userId());
                try (ResultSet rows = select.executeQuery()) {
                    if (!rows.next()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Abra a sessão da Agenda novamente.");
                    return new AgendaUser(account.userId(), rows.getString(1), account.email());
                }
            }
        } catch (SQLException exception) {
            throw serverError("Não foi possível validar a sessão.", exception);
        }
    }

    List<AgendaTask> listTasks(AgendaUser current, double latitude, double longitude, String baseUrl) {
        validateCoordinates(latitude, longitude);
        ensureSchema();
        marketplace.processDeadlines();
        List<AgendaTask> tasks = new ArrayList<>();
        try (Connection connection = connection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT t.id,t.title,t.description,t.owner_id,u.display_name,t.starts_at,
                            t.duration_minutes,t.people_needed,t.latitude,t.longitude,t.recurrence_label,
                            t.offer_phase,t.offer_expires_at,t.task_status,t.specialty_id,s.name specialty_name
                     FROM agenda_tasks t
                     JOIN agenda_users u ON u.id=t.owner_id
                     JOIN agenda_specialties s ON s.id=t.specialty_id
                     WHERE t.starts_at >= CURRENT_TIMESTAMP - INTERVAL '1 day'
                     ORDER BY t.starts_at ASC
                     """)) {
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    double distance = distanceKm(latitude, longitude, rows.getDouble("latitude"), rows.getDouble("longitude"));
                    boolean owner = current.id().equals(rows.getString("owner_id"));
                    boolean applied = hasCandidate(connection, rows.getString("id"), current.id());
                    boolean externallyInvited = prospecting.canViewTask(current.id(), rows.getString("id"));
                    if (!owner && !applied && !externallyInvited && distance > MAX_DISTANCE_KM) continue;
                    if (!externallyInvited && !marketplace.canViewTask(current.id(), rows.getString("id"), rows.getString("offer_phase"), owner, applied)) continue;
                    LocalDateTime startsAt = rows.getTimestamp("starts_at").toLocalDateTime();
                    boolean approximateLocation = externallyInvited && !owner && !applied;
                    double displayLatitude = approximateLocation ? approximateCoordinate(rows.getDouble("latitude")) : rows.getDouble("latitude");
                    double displayLongitude = approximateLocation ? approximateCoordinate(rows.getDouble("longitude")) : rows.getDouble("longitude");
                    tasks.add(new AgendaTask(
                            rows.getString("id"), rows.getString("title"), rows.getString("description"),
                            rows.getString("owner_id"), rows.getString("display_name"),
                            startsAt.format(OUTPUT_DATE), startsAt.format(OUTPUT_TIME),
                            rows.getInt("duration_minutes") / 60.0, rows.getInt("people_needed"),
                            displayLatitude, displayLongitude, approximateLocation, rows.getString("recurrence_label"),
                            rows.getLong("specialty_id"), rows.getString("specialty_name"),
                            rows.getString("offer_phase"), formatTimestamp(rows.getTimestamp("offer_expires_at")), rows.getString("task_status"),
                            marketplace.offerStatus(current.id(), rows.getString("id")),
                            photoUrls(connection, rows.getString("owner_id"), baseUrl),
                            candidates(connection, rows.getString("id"), current.id(), owner),
                            owner ? prospecting.summary(rows.getString("id")) : ProspectingService.JobSummary.none(rows.getString("id"))));
                }
            }
            return tasks;
        } catch (SQLException exception) {
            throw serverError("Não foi possível carregar as tarefas.", exception);
        }
    }

    AgendaTask createTask(AgendaUser current, CreateTaskRequest request, String baseUrl) {
        if (request == null || request.title() == null || request.title().trim().length() < 3
                || request.description() == null || request.description().trim().length() < 10) {
            throw badRequest("Título ou descrição inválidos.");
        }
        if (request.durationHours() <= 0 || request.durationHours() > 24 || request.peopleNeeded() < 1 || request.peopleNeeded() > 100) {
            throw badRequest("Duração ou quantidade de pessoas inválida.");
        }
        if (request.specialtyId() <= 0) throw badRequest("Escolha uma especialidade.");
        specialties.requireActive(request.specialtyId());
        validateCoordinates(request.latitude(), request.longitude());
        LocalDateTime startsAt;
        try {
            startsAt = LocalDateTime.parse(request.date() + " " + request.time(), INPUT_DATE_TIME);
        } catch (DateTimeParseException exception) {
            throw badRequest("Data ou horário inválido. Use dd/MM/aaaa e HH:mm.");
        }
        List<LocalDateTime> occurrences = recurrenceDates(startsAt, request);
        String recurrenceLabel = recurrenceLabel(request);
        String firstId = null;
        List<String> createdIds = new ArrayList<>();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO agenda_tasks(id,owner_id,title,description,starts_at,duration_minutes,people_needed,latitude,longitude,recurrence_label,specialty_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                for (LocalDateTime occurrence : occurrences) {
                    String id = UUID.randomUUID().toString();
                    if (firstId == null) firstId = id;
                    createdIds.add(id);
                    insert.setString(1, id);
                    insert.setString(2, current.id());
                    insert.setString(3, clean(request.title(), 180));
                    insert.setString(4, clean(request.description(), 4000));
                    insert.setObject(5, occurrence);
                    insert.setInt(6, Math.max(1, (int) Math.round(request.durationHours() * 60)));
                    insert.setInt(7, request.peopleNeeded());
                    insert.setDouble(8, request.latitude());
                    insert.setDouble(9, request.longitude());
                    insert.setString(10, recurrenceLabel);
                    insert.setLong(11, request.specialtyId());
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
            throw serverError("Não foi possível publicar a tarefa.", exception);
        }
        for (String taskId : createdIds) {
            marketplace.startOffers(taskId, current.id(), request.favoriteProviderIds());
            prospecting.scheduleForTask(taskId, request.specialtyId());
        }
        return findTask(current, firstId, request.latitude(), request.longitude(), baseUrl);
    }

    void apply(AgendaUser current, String taskId, double userLatitude, double userLongitude) {
        validateCoordinates(userLatitude, userLongitude);
        marketplace.assertApplicationOpen(taskId, current.id());
        try (Connection connection = connection()) {
            TaskLocation task = taskLocation(connection, taskId);
            if (current.id().equals(task.ownerId())) throw badRequest("Você não pode se candidatar à própria tarefa.");
            double distance = distanceKm(userLatitude, userLongitude, task.latitude(), task.longitude());
            boolean registeredExternalInvite = prospecting.canViewTask(current.id(), taskId);
            if (distance > MAX_DISTANCE_KM && !registeredExternalInvite) {
                throw badRequest("A tarefa está fora do limite de 50 km.");
            }
            if (approvedCount(connection, taskId) >= task.peopleNeeded()) throw badRequest("Todas as vagas já foram preenchidas.");
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO agenda_candidates(task_id,user_id,status,distance_km)
                    VALUES(?,?,'PENDING',?)
                    ON CONFLICT(task_id,user_id) DO UPDATE SET distance_km=EXCLUDED.distance_km, updated_at=CURRENT_TIMESTAMP
                    """)) {
                insert.setString(1, taskId);
                insert.setString(2, current.id());
                insert.setDouble(3, distance);
                insert.executeUpdate();
            }
            marketplace.candidateApplied(taskId, current.id());
        } catch (SQLException exception) {
            throw serverError("Não foi possível registrar a candidatura.", exception);
        }
    }

    void decideCandidate(AgendaUser current, String taskId, String candidateId, CandidateDecision decision) {
        if (decision == null || !("APPROVED".equals(decision.status()) || "REJECTED".equals(decision.status()))) {
            throw badRequest("Status deve ser APPROVED ou REJECTED.");
        }
        try (Connection connection = connection()) {
            TaskLocation task = taskLocation(connection, taskId);
            if (!current.id().equals(task.ownerId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas o anunciante pode decidir.");
            if ("APPROVED".equals(decision.status()) && approvedCount(connection, taskId) >= task.peopleNeeded()
                    && !isAlreadyApproved(connection, taskId, candidateId)) {
                throw badRequest("Todas as vagas já foram preenchidas.");
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE agenda_candidates SET status=?, updated_at=CURRENT_TIMESTAMP WHERE task_id=? AND user_id=?")) {
                update.setString(1, decision.status());
                update.setString(2, taskId);
                update.setString(3, candidateId);
                if (update.executeUpdate() == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidato não encontrado.");
            }
            marketplace.candidateDecision(taskId, candidateId, decision.status());
        } catch (SQLException exception) {
            throw serverError("Não foi possível atualizar a candidatura.", exception);
        }
    }

    synchronized PhotoInfo addPhoto(AgendaUser current, MultipartFile file, String baseUrl) {
        if (file == null || file.isEmpty()) throw badRequest("Selecione uma foto.");
        if (file.getSize() > MAX_PHOTO_BYTES) throw badRequest("Cada foto pode ter no máximo 5 MB.");
        ImageType imageType = inspectImage(file);
        try (Connection connection = connection()) {
            int count;
            try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM agenda_photos WHERE user_id=?")) {
                query.setString(1, current.id());
                try (ResultSet rows = query.executeQuery()) { rows.next(); count = rows.getInt(1); }
            }
            int photoLimit = marketplace.limits(current.id()).photoLimit();
            if (count >= photoLimit) throw badRequest("Seu plano permite até " + photoLimit + " fotos.");
            String classification;
            try { classification = mediaClassifier.classify(file.getBytes(), imageType.mimeType()); }
            catch (IOException exception) { throw badRequest("Não foi possível ler a foto enviada."); }
            catch (IllegalStateException exception) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "A foto é válida, mas a classificação automática está indisponível: " + exception.getMessage());
            }
            Files.createDirectories(photoRoot.resolve(current.id()));
            String id = UUID.randomUUID().toString();
            String fileName = id + imageType.extension();
            Path destination = safePhotoPath(current.id(), fileName);
            Path temp = Files.createTempFile(photoRoot.resolve(current.id()), "upload-", ".tmp");
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO agenda_photos(id,user_id,file_name,mime_type,sort_order,size_bytes,service_classification)
                    VALUES(?,?,?,?,?,?,?)
                    """)) {
                insert.setString(1, id);
                insert.setString(2, current.id());
                insert.setString(3, fileName);
                insert.setString(4, imageType.mimeType());
                insert.setInt(5, count + 1);
                insert.setLong(6, file.getSize());
                insert.setString(7, classification);
                insert.executeUpdate();
            } catch (SQLException exception) {
                Files.deleteIfExists(destination);
                throw exception;
            }
            return new PhotoInfo(id, baseUrl + "/api/agenda/photos/" + id, count + 1, classification);
        } catch (SQLException | IOException exception) {
            throw serverError("Não foi possível armazenar a foto.", exception);
        }
    }

    synchronized VideoInfo addVideo(AgendaUser current, MultipartFile file, String baseUrl) {
        if (file == null || file.isEmpty()) throw badRequest("Selecione um vídeo.");
        if (file.getSize() > MAX_VIDEO_BYTES) throw badRequest("Cada vídeo pode ter no máximo 30 MB.");
        VideoType type = inspectVideo(file);
        int limit = marketplace.limits(current.id()).videoLimit();
        if (limit == 0) throw badRequest("Seu plano não permite vídeos.");
        try (Connection connection = connection()) {
            int count;
            try (PreparedStatement query=connection.prepareStatement("SELECT COUNT(*) FROM agenda_videos WHERE user_id=?")) {
                query.setString(1,current.id());try(ResultSet rows=query.executeQuery()){rows.next();count=rows.getInt(1);}
            }
            if(count>=limit)throw badRequest("Seu plano permite até "+limit+" vídeos.");
            Files.createDirectories(videoRoot.resolve(current.id()));
            String id=UUID.randomUUID().toString(),fileName=id+type.extension();Path destination=safeVideoPath(current.id(),fileName);
            try(InputStream input=file.getInputStream()){Files.copy(input,destination,StandardCopyOption.REPLACE_EXISTING);}
            try(PreparedStatement insert=connection.prepareStatement("INSERT INTO agenda_videos(id,user_id,file_name,mime_type,sort_order,size_bytes) VALUES(?,?,?,?,?,?)")){
                insert.setString(1,id);insert.setString(2,current.id());insert.setString(3,fileName);insert.setString(4,type.mimeType());insert.setInt(5,count+1);insert.setLong(6,file.getSize());insert.executeUpdate();
            }catch(SQLException exception){Files.deleteIfExists(destination);throw exception;}
            return new VideoInfo(id,baseUrl+"/api/agenda/videos/"+id,count+1);
        }catch(SQLException|IOException exception){throw serverError("Não foi possível armazenar o vídeo.",exception);}
    }

    List<VideoInfo> myVideos(AgendaUser current,String baseUrl){
        try(Connection connection=connection();PreparedStatement query=connection.prepareStatement("SELECT id,sort_order FROM agenda_videos WHERE user_id=? ORDER BY sort_order")){
            query.setString(1,current.id());List<VideoInfo> result=new ArrayList<>();try(ResultSet rows=query.executeQuery()){while(rows.next())result.add(new VideoInfo(rows.getString(1),baseUrl+"/api/agenda/videos/"+rows.getString(1),rows.getInt(2)));}return result;
        }catch(SQLException exception){throw serverError("Não foi possível carregar os vídeos.",exception);}
    }

    synchronized void deleteVideo(AgendaUser current, String videoId) {
        ensureSchema();
        Path filePath;
        int removedOrder;
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT file_name,sort_order FROM agenda_videos WHERE id=? AND user_id=? FOR UPDATE")) {
                    query.setString(1, videoId);
                    query.setString(2, current.id());
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next()) {
                            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vídeo não encontrado.");
                        }
                        filePath = safeVideoPath(current.id(), rows.getString("file_name"));
                        removedOrder = rows.getInt("sort_order");
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM agenda_videos WHERE id=? AND user_id=?")) {
                    delete.setString(1, videoId);
                    delete.setString(2, current.id());
                    delete.executeUpdate();
                }
                try (PreparedStatement reorder = connection.prepareStatement(
                        "UPDATE agenda_videos SET sort_order=sort_order-1 WHERE user_id=? AND sort_order>?")) {
                    reorder.setString(1, current.id());
                    reorder.setInt(2, removedOrder);
                    reorder.executeUpdate();
                }
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw serverError("Não foi possível apagar o vídeo.", exception);
        }
        deleteStoredFile(filePath);
    }

    PhotoFile video(String videoId){
        ensureSchema();marketplace.ensureSchema();
        try(Connection connection=connection();PreparedStatement query=connection.prepareStatement("SELECT user_id,file_name,mime_type FROM agenda_videos WHERE id=?")){
            query.setString(1,videoId);try(ResultSet rows=query.executeQuery()){if(!rows.next())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Vídeo não encontrado.");Path path=safeVideoPath(rows.getString(1),rows.getString(2));if(!Files.isRegularFile(path))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Arquivo do vídeo não encontrado.");return new PhotoFile(path,rows.getString(3));}
        }catch(SQLException exception){throw serverError("Não foi possível carregar o vídeo.",exception);}
    }

    List<PhotoInfo> myPhotos(AgendaUser current, String baseUrl) {
        try (Connection connection = connection()) {
            return photos(connection, current.id(), baseUrl);
        } catch (SQLException exception) {
            throw serverError("Não foi possível carregar as fotos.", exception);
        }
    }

    synchronized void deletePhoto(AgendaUser current, String photoId) {
        ensureSchema();
        Path filePath;
        int removedOrder;
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT file_name,sort_order FROM agenda_photos WHERE id=? AND user_id=? FOR UPDATE")) {
                    query.setString(1, photoId);
                    query.setString(2, current.id());
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next()) {
                            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Foto não encontrada.");
                        }
                        filePath = safePhotoPath(current.id(), rows.getString("file_name"));
                        removedOrder = rows.getInt("sort_order");
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM agenda_photos WHERE id=? AND user_id=?")) {
                    delete.setString(1, photoId);
                    delete.setString(2, current.id());
                    delete.executeUpdate();
                }
                try (PreparedStatement reorder = connection.prepareStatement(
                        "UPDATE agenda_photos SET sort_order=sort_order-1 WHERE user_id=? AND sort_order>?")) {
                    reorder.setString(1, current.id());
                    reorder.setInt(2, removedOrder);
                    reorder.executeUpdate();
                }
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw serverError("Não foi possível apagar a foto.", exception);
        }
        deleteStoredFile(filePath);
    }

    PhotoFile photo(String photoId) {
        ensureSchema();
        try (Connection connection = connection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT user_id,file_name,mime_type FROM agenda_photos WHERE id=?")) {
            query.setString(1, photoId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Foto não encontrada.");
                Path path = safePhotoPath(rows.getString("user_id"), rows.getString("file_name"));
                if (!Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Arquivo da foto não encontrado.");
                return new PhotoFile(path, rows.getString("mime_type"));
            }
        } catch (SQLException exception) {
            throw serverError("Não foi possível carregar a foto.", exception);
        }
    }

    private AgendaTask findTask(AgendaUser current, String id, double lat, double lon, String baseUrl) {
        for (AgendaTask task : listTasks(current, lat, lon, baseUrl)) if (task.id().equals(id)) return task;
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tarefa não encontrada.");
    }

    private List<CandidateInfo> candidates(Connection connection, String taskId, String currentUserId, boolean owner) throws SQLException {
        List<CandidateInfo> result = new ArrayList<>();
        String sql = """
                SELECT c.user_id,u.display_name,u.bio,c.distance_km,c.status
                FROM agenda_candidates c JOIN agenda_users u ON u.id=c.user_id
                WHERE c.task_id=?
                """ + (owner ? " ORDER BY c.created_at" : " AND c.user_id=?");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, taskId);
            if (!owner) query.setString(2, currentUserId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new CandidateInfo(
                        rows.getString("user_id"), rows.getString("display_name"),
                        rows.getString("bio"),rows.getDouble("distance_km"), rows.getString("status"),
                        marketplace.pricesFor(rows.getString("user_id"))));
            }
        }
        return result;
    }

    private List<String> photoUrls(Connection connection, String userId, String baseUrl) throws SQLException {
        List<String> result = new ArrayList<>();
        for (PhotoInfo photo : photos(connection, userId, baseUrl)) result.add(photo.url());
        return result;
    }

    private List<PhotoInfo> photos(Connection connection, String userId, String baseUrl) throws SQLException {
        List<PhotoInfo> result = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id,sort_order,service_classification FROM agenda_photos WHERE user_id=? ORDER BY sort_order")) {
            query.setString(1, userId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new PhotoInfo(rows.getString("id"),
                        baseUrl + "/api/agenda/photos/" + rows.getString("id"), rows.getInt("sort_order"),
                        rows.getString("service_classification")));
            }
        }
        return result;
    }

    private boolean hasCandidate(Connection connection, String taskId, String userId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM agenda_candidates WHERE task_id=? AND user_id=?")) {
            query.setString(1, taskId); query.setString(2, userId);
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        }
    }

    private int approvedCount(Connection connection, String taskId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM agenda_candidates WHERE task_id=? AND status IN ('APPROVED','CONFIRMED')")) {
            query.setString(1, taskId);
            try (ResultSet rows = query.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    private boolean isAlreadyApproved(Connection connection, String taskId, String userId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM agenda_candidates WHERE task_id=? AND user_id=? AND status IN ('APPROVED','CONFIRMED')")) {
            query.setString(1, taskId); query.setString(2, userId);
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        }
    }

    private TaskLocation taskLocation(Connection connection, String taskId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT owner_id,latitude,longitude,people_needed FROM agenda_tasks WHERE id=?")) {
            query.setString(1, taskId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tarefa não encontrada.");
                return new TaskLocation(rows.getString("owner_id"), rows.getDouble("latitude"), rows.getDouble("longitude"), rows.getInt("people_needed"));
            }
        }
    }

    private Account authenticatedAccount(Connection connection, String deviceId, String authToken) throws SQLException {
        AuthenticationService.Account account = authentication.authenticate(connection, deviceId, authToken);
        return new Account(account.id(), account.email(), account.name());
    }

    private synchronized void ensureSchema() {
        if (storageReady.get()) return;
        try {
            Files.createDirectories(photoRoot);
            Files.createDirectories(videoRoot);
            storageReady.set(true);
        } catch (IOException exception) {
            throw serverError("Não foi possível preparar os diretórios de mídia da Agenda.", exception);
        }
    }

    private Connection connection() throws SQLException { return dataSource.getConnection(); }

    private List<LocalDateTime> recurrenceDates(LocalDateTime startsAt, CreateTaskRequest request) {
        String type = request.recurrenceType() == null ? "NONE" : request.recurrenceType().trim().toUpperCase(Locale.ROOT);
        if ("NONE".equals(type)) return List.of(startsAt);
        if (!("WEEKLY".equals(type) || "MONTHLY".equals(type))) throw badRequest("Tipo de repetição inválido.");
        if (request.recurrenceDays() == null || request.recurrenceDays().isEmpty()) throw badRequest("Escolha os dias da repetição.");
        LocalDate until;
        try { until = LocalDate.parse(request.recurrenceUntil(), OUTPUT_DATE); }
        catch (Exception exception) { throw badRequest("Informe a data final da repetição."); }
        if (until.isBefore(startsAt.toLocalDate()) || until.isAfter(startsAt.toLocalDate().plusYears(1)))
            throw badRequest("A repetição deve terminar entre a primeira data e até um ano depois.");
        for (Integer day : request.recurrenceDays()) {
            if (day == null || day < 1 || day > ("WEEKLY".equals(type) ? 7 : 31)) throw badRequest("Dia de repetição inválido.");
        }
        List<LocalDateTime> result = new ArrayList<>();
        for (LocalDate date = startsAt.toLocalDate(); !date.isAfter(until); date = date.plusDays(1)) {
            boolean matches = "WEEKLY".equals(type)
                    ? request.recurrenceDays().contains(date.getDayOfWeek().getValue())
                    : request.recurrenceDays().contains(date.getDayOfMonth());
            if (matches) result.add(LocalDateTime.of(date, startsAt.toLocalTime()));
            if (result.size() >= 100) break;
        }
        if (result.isEmpty()) throw badRequest("A repetição não gerou nenhuma data no período informado.");
        return result;
    }

    private String recurrenceLabel(CreateTaskRequest request) {
        String type = request.recurrenceType() == null ? "NONE" : request.recurrenceType().trim().toUpperCase(Locale.ROOT);
        if ("NONE".equals(type)) return "";
        return ("WEEKLY".equals(type) ? "Repetição semanal" : "Repetição mensal") + " até " + request.recurrenceUntil();
    }

    private ImageType inspectImage(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(12);
            if (header.length >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff)
                return new ImageType("image/jpeg", ".jpg");
            if (header.length >= 8 && header[0] == (byte) 0x89 && header[1] == 0x50 && header[2] == 0x4e && header[3] == 0x47)
                return new ImageType("image/png", ".png");
            if (header.length >= 12 && new String(header, 0, 4).equals("RIFF") && new String(header, 8, 4).equals("WEBP"))
                return new ImageType("image/webp", ".webp");
        } catch (IOException exception) {
            throw badRequest("Não foi possível ler a imagem.");
        }
        throw badRequest("Formato não suportado. Use JPEG, PNG ou WebP.");
    }

    private Path safePhotoPath(String userId, String fileName) {
        if (!userId.matches("[a-fA-F0-9-]{36}") || !fileName.matches("[a-fA-F0-9-]{36}\\.(jpg|png|webp)"))
            throw badRequest("Caminho de foto inválido.");
        Path path = photoRoot.resolve(userId).resolve(fileName).normalize();
        if (!path.startsWith(photoRoot)) throw badRequest("Caminho de foto inválido.");
        return path;
    }

    private VideoType inspectVideo(MultipartFile file) {
        try (InputStream input=file.getInputStream()) {
            byte[] head=input.readNBytes(12);
            if(head.length>=8&&head[4]=='f'&&head[5]=='t'&&head[6]=='y'&&head[7]=='p')return new VideoType("video/mp4",".mp4");
            if(head.length>=4&&(head[0]&255)==0x1A&&(head[1]&255)==0x45&&(head[2]&255)==0xDF&&(head[3]&255)==0xA3)return new VideoType("video/webm",".webm");
            throw badRequest("Envie um vídeo MP4 ou WebM válido.");
        }catch(IOException exception){throw serverError("Não foi possível validar o vídeo.",exception);}
    }

    private Path safeVideoPath(String userId,String fileName){
        if(!userId.matches("[a-fA-F0-9-]{36}")||!fileName.matches("[a-fA-F0-9-]{36}\\.(mp4|webm)"))throw badRequest("Caminho de vídeo inválido.");
        Path path=videoRoot.resolve(userId).resolve(fileName).normalize();if(!path.startsWith(videoRoot))throw badRequest("Caminho de vídeo inválido.");return path;
    }

    private static void deleteStoredFile(Path path) {
        try {
            Files.deleteIfExists(path);
            Path parent = path.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                try (var entries = Files.list(parent)) {
                    if (entries.findAny().isEmpty()) Files.deleteIfExists(parent);
                }
            }
        } catch (IOException ignored) {
            // A mídia deixa de ser acessível assim que o registro é removido.
            // Um arquivo residual não deve transformar uma exclusão lógica concluída em erro para o usuário.
        }
    }

    private static String clean(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static String formatTimestamp(java.sql.Timestamp timestamp) {
        return timestamp == null ? "" : timestamp.toLocalDateTime().format(INPUT_DATE_TIME);
    }

    private static void validateCredential(String value, String label) {
        if (value == null || value.length() < 20 || value.length() > 200) throw badRequest("Informe um " + label + " válido.");
    }

    private static void validateCoordinates(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
            throw badRequest("Coordenadas inválidas.");
    }

    private static double approximateCoordinate(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException serverError(String message, Exception cause) { return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message, cause); }

    record SessionRequest(String name, double latitude, double longitude, String role, String bio) {}
    record AgendaUser(String id, String name, String email) {}
    record CreateTaskRequest(String title, String description, String date, String time, double durationHours,
                             int peopleNeeded, double latitude, double longitude, String recurrenceType,
                             List<Integer> recurrenceDays, String recurrenceUntil, List<String> favoriteProviderIds,
                             long specialtyId) {}
    record CandidateDecision(String status) {
        CandidateDecision { status = status == null ? "" : status.trim().toUpperCase(Locale.ROOT); }
    }
    record CandidateInfo(String userId, String name, String bio,double distanceKm, String status,
                         List<AgendaMarketplaceService.ServicePrice> prices) {}
    record AgendaTask(String id, String title, String description, String ownerId, String ownerName,
                      String date, String time, double durationHours, int peopleNeeded,
                      double latitude, double longitude, boolean locationApproximate, String recurrenceLabel,
                      long specialtyId, String specialtyName,
                      String offerPhase, String offerExpiresAt, String taskStatus,
                      String myOfferStatus,
                      List<String> ownerPhotos, List<CandidateInfo> candidates,
                      ProspectingService.JobSummary prospecting) {}
    record PhotoInfo(String id, String url, int order, String classification) {}
    record VideoInfo(String id,String url,int order) {}
    record PhotoFile(Path path, String mimeType) {}
    private record TaskLocation(String ownerId, double latitude, double longitude, int peopleNeeded) {}
    private record ImageType(String mimeType, String extension) {}
    private record VideoType(String mimeType,String extension) {}
    private record Account(String userId, String email, String name) {}
}
