package br.com.mauricio.agendaserver;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

@Service
final class AgendaMarketplaceService {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Set<String> ROLES = Set.of("CONSUMER", "PROVIDER", "BOTH");
    private final DataSource dataSource;
    private final SpecialtyService specialties;

    AgendaMarketplaceService(DataSource dataSource, SpecialtyService specialties) {
        this.dataSource = dataSource;
        this.specialties = specialties;
    }

    void ensureSchema() {
        // O esquema é controlado exclusivamente pelo Flyway.
    }

    void initializeUser(String userId) {
        ensureSchema();
        try (Connection connection = connection(); PreparedStatement update = connection.prepareStatement("""
                UPDATE agenda_users SET plan_code='STARTER', founder_free=TRUE
                WHERE id=? AND plan_code='FREE_ADS' AND id IN (
                  SELECT id FROM (SELECT id FROM agenda_users ORDER BY created_at,id LIMIT 100) founders)
                """)) {
            update.setString(1, userId);
            update.executeUpdate();
        } catch (SQLException exception) {
            throw serverError("Não foi possível inicializar o plano do usuário.", exception);
        }
    }

    Profile profile(String userId) {
        ensureSchema();
        try (Connection connection = connection(); PreparedStatement query = connection.prepareStatement(
                "SELECT display_name,role_code,bio,plan_code,founder_free FROM agenda_users WHERE id=?")) {
            query.setString(1, userId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Perfil não encontrado.");
                Plan plan = plan(rows.getString("plan_code"), rows.getBoolean("founder_free"));
                return new Profile(userId, rows.getString("display_name"), rows.getString("role_code"),
                        rows.getString("bio"), plan, prices(connection, userId), specialties.userSpecialtyIds(userId));
            }
        } catch (SQLException exception) { throw serverError("Não foi possível carregar o perfil.", exception); }
    }

    Profile updateProfile(String userId, ProfileUpdate request) {
        ensureSchema();
        if (request == null || request.name() == null || !request.name().matches("[a-z]{2,8}"))
            throw badRequest("O nome deve ter de 2 a 8 letras minúsculas, sem números, espaços ou acentos.");
        String role = request.role() == null ? "BOTH" : request.role().trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(role)) throw badRequest("Tipo de usuário inválido.");
        Plan current = profile(userId).plan();
        String bio = request.bio() == null ? "" : request.bio().trim();
        if (bio.length() > current.descriptionLimit()) throw badRequest("A descrição excede o limite de " + current.descriptionLimit() + " caracteres do plano.");
        try (Connection connection = connection(); PreparedStatement update = connection.prepareStatement(
                "UPDATE agenda_users SET display_name=?,role_code=?,bio=? WHERE id=?")) {
            update.setString(1, request.name()); update.setString(2, role); update.setString(3, bio); update.setString(4, userId);
            update.executeUpdate();
        } catch (SQLException exception) { throw serverError("Não foi possível atualizar o perfil.", exception); }
        if ("PROVIDER".equals(role) || "BOTH".equals(role)) {
            if (request.specialtyIds() != null) specialties.replaceUserSpecialties(userId, request.specialtyIds());
        } else {
            specialties.replaceUserSpecialties(userId, List.of());
        }
        return profile(userId);
    }

    List<Plan> plans() {
        return List.of(
                new Plan("FREE_ADS", "Grátis com anúncios", 0, 3, 0, 20, 1, true, false),
                new Plan("STARTER", "Essencial", 200, 3, 0, 20, 1, false, false),
                new Plan("PLUS", "Plus", 500, 10, 0, 50, 3, false, false),
                new Plan("PRO", "Profissional", 1000, 20, 5, 100, 5, false, false));
    }

    Plan limits(String userId) { return profile(userId).plan(); }
    List<ServicePrice> pricesFor(String userId){try(Connection connection=connection()){return prices(connection,userId);}catch(SQLException exception){throw serverError("Não foi possível carregar os preços.",exception);}}

    void replacePrices(String userId, List<ServicePriceInput> requested) {
        Profile profile = profile(userId);
        if (!("PROVIDER".equals(profile.role()) || "BOTH".equals(profile.role()))) throw badRequest("Apenas prestadores podem cadastrar preços.");
        List<ServicePriceInput> values = requested == null ? List.of() : requested;
        if (values.size() > profile.plan().servicePriceLimit()) throw badRequest("Seu plano permite " + profile.plan().servicePriceLimit() + " preço(s).");
        Set<String> names = new LinkedHashSet<>();
        for (ServicePriceInput value : values) {
            String name = value.serviceName() == null ? "" : value.serviceName().trim();
            if (name.length() < 2 || name.length() > 80 || value.priceCents() < 0 || !names.add(name.toLowerCase(Locale.ROOT)))
                throw badRequest("Revise os nomes e preços dos serviços.");
        }
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM agenda_service_prices WHERE user_id=?");
                 PreparedStatement insert = connection.prepareStatement("INSERT INTO agenda_service_prices(user_id,service_name,price_cents) VALUES(?,?,?)")) {
                delete.setString(1, userId); delete.executeUpdate();
                for (ServicePriceInput value : values) {
                    insert.setString(1, userId); insert.setString(2, value.serviceName().trim()); insert.setInt(3, value.priceCents()); insert.addBatch();
                }
                insert.executeBatch(); connection.commit();
            } catch (SQLException exception) { connection.rollback(); throw exception; }
        } catch (SQLException exception) { throw serverError("Não foi possível salvar os preços.", exception); }
    }

    List<FavoriteProvider> favorites(String consumerId) {
        ensureSchema();
        try (Connection connection = connection(); PreparedStatement query = connection.prepareStatement("""
                SELECT u.id,u.display_name,u.bio,u.plan_code,u.founder_free
                FROM agenda_favorites f JOIN agenda_users u ON u.id=f.provider_id
                WHERE f.consumer_id=? ORDER BY u.display_name
                """)) {
            query.setString(1, consumerId); List<FavoriteProvider> result = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new FavoriteProvider(rows.getString("id"), rows.getString("display_name"),
                        rows.getString("bio"), prices(connection, rows.getString("id"))));
            }
            return result;
        } catch (SQLException exception) { throw serverError("Não foi possível carregar os favoritos.", exception); }
    }

    void addFavorite(String consumerId, String providerId) {
        ensureSchema();
        requireRole(consumerId,"CONSUMER");
        if (consumerId.equals(providerId)) throw badRequest("Você não pode adicionar a si mesmo aos favoritos.");
        try (Connection connection = connection()) {
            try (PreparedStatement provider = connection.prepareStatement("SELECT role_code FROM agenda_users WHERE id=?")) {
                provider.setString(1, providerId);
                try (ResultSet rows = provider.executeQuery()) {
                    if (!rows.next() || !("PROVIDER".equals(rows.getString(1)) || "BOTH".equals(rows.getString(1))))
                        throw badRequest("Este usuário não está cadastrado como prestador.");
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO agenda_favorites(consumer_id,provider_id) VALUES(?,?) ON CONFLICT DO NOTHING")) {
                insert.setString(1, consumerId); insert.setString(2, providerId); insert.executeUpdate();
            }
        } catch (SQLException exception) { throw serverError("Não foi possível adicionar o favorito.", exception); }
    }

    void removeFavorite(String consumerId, String providerId) {
        ensureSchema();
        try (Connection connection = connection(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM agenda_favorites WHERE consumer_id=? AND provider_id=?")) {
            delete.setString(1, consumerId); delete.setString(2, providerId); delete.executeUpdate();
        } catch (SQLException exception) { throw serverError("Não foi possível remover o favorito.", exception); }
    }

    void startOffers(String taskId, String ownerId, List<String> requestedProviders) {
        ensureSchema();
        requireRole(ownerId,"CONSUMER");
        List<String> providers = requestedProviders == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(requestedProviders));
        if (providers.size() > 3) throw badRequest("Selecione no máximo três prestadores favoritos.");
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            validateFavoriteProviders(connection, taskId, ownerId, providers);
            if (providers.isEmpty()) {
                setTaskWindow(connection, taskId, "OPEN", LocalDateTime.now().plusHours(1), "ACTIVE");
            } else {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO agenda_task_invites(task_id,provider_id,priority_level,status,offered_at,expires_at) VALUES(?,?,?,'WAITING',NULL,NULL)")) {
                    for (int index=0; index<providers.size(); index++) {
                        insert.setString(1, taskId); insert.setString(2, providers.get(index)); insert.setInt(3, index + 1); insert.addBatch();
                    }
                    insert.executeBatch();
                }
                offerLevel(connection, taskId, ownerId, providers.get(0), 1);
            }
            connection.commit();
        } catch (SQLException exception) { throw serverError("Não foi possível iniciar as ofertas prioritárias.", exception); }
    }

    void processDeadlines() {
        ensureSchema();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT id,owner_id,offer_phase FROM agenda_tasks
                    WHERE task_status='ACTIVE' AND offer_expires_at IS NOT NULL AND offer_expires_at<=CURRENT_TIMESTAMP
                    FOR UPDATE
                    """)) {
                List<ExpiredTask> expired = new ArrayList<>();
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) expired.add(new ExpiredTask(rows.getString(1),rows.getString(2),rows.getString(3)));
                }
                for (ExpiredTask task : expired) {
                    if (task.phase().startsWith("PRIORITY_")) advancePriority(connection, task.id(), task.ownerId(), true);
                    else if ("OPEN".equals(task.phase())) closeOpenWindow(connection, task.id(), task.ownerId());
                }
            }
            connection.commit();
        } catch (SQLException exception) { throw serverError("Não foi possível processar os prazos.", exception); }
    }

    void assertApplicationOpen(String taskId, String userId) {
        processDeadlines();
        requireRole(userId,"PROVIDER");
        try (Connection connection = connection(); PreparedStatement query = connection.prepareStatement(
                "SELECT offer_phase,task_status FROM agenda_tasks WHERE id=?")) {
            query.setString(1, taskId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tarefa não encontrada.");
                if (!("OPEN".equals(rows.getString(1)) && "ACTIVE".equals(rows.getString(2))))
                    throw badRequest("Esta tarefa ainda não está aberta para propostas ou o prazo terminou.");
            }
        } catch (SQLException exception) { throw serverError("Não foi possível validar a oferta.", exception); }
    }

    boolean canViewTask(String userId, String taskId, String phase, boolean owner, boolean applied) {
        if (owner || applied) return true;
        if ("OPEN".equals(phase)) return true;
        if (phase != null && phase.startsWith("PRIORITY_")) return "OFFERED".equals(offerStatus(userId, taskId));
        return false;
    }

    String offerStatus(String userId, String taskId) {
        ensureSchema();
        try (Connection connection=connection();PreparedStatement query=connection.prepareStatement(
                "SELECT status FROM agenda_task_invites WHERE task_id=? AND provider_id=?")) {
            query.setString(1,taskId);query.setString(2,userId);
            try(ResultSet rows=query.executeQuery()){return rows.next()?rows.getString(1):"";}
        } catch(SQLException exception){throw serverError("Não foi possível consultar a oferta prioritária.",exception);}
    }

    String respondOffer(String providerId, String taskId, String response) {
        processDeadlines();
        String answer = response == null ? "" : response.trim().toUpperCase(Locale.ROOT);
        if (!("ACCEPTED".equals(answer) || "DECLINED".equals(answer))) throw badRequest("Resposta deve ser ACCEPTED ou DECLINED.");
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            String ownerId;
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT t.owner_id FROM agenda_task_invites i JOIN agenda_tasks t ON t.id=i.task_id
                    WHERE i.task_id=? AND i.provider_id=? AND i.status='OFFERED' AND i.expires_at>CURRENT_TIMESTAMP FOR UPDATE
                    """)) {
                query.setString(1, taskId); query.setString(2, providerId);
                try (ResultSet rows = query.executeQuery()) { if (!rows.next()) throw badRequest("A oferta não está mais disponível."); ownerId=rows.getString(1); }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE agenda_task_invites SET status=?,responded_at=CURRENT_TIMESTAMP WHERE task_id=? AND provider_id=?")) {
                update.setString(1, answer); update.setString(2, taskId); update.setString(3, providerId); update.executeUpdate();
            }
            if ("ACCEPTED".equals(answer)) {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO agenda_candidates(task_id,user_id,status,distance_km) VALUES(?,?,'CONFIRMED',0)
                        ON CONFLICT(task_id,user_id) DO UPDATE SET status='CONFIRMED',updated_at=CURRENT_TIMESTAMP
                        """)) { insert.setString(1,taskId); insert.setString(2,providerId); insert.executeUpdate(); }
                notify(connection, ownerId, "CONFIRMATION", "Oferta confirmada", "Um prestador favorito confirmou a tarefa.", taskId);
                notify(connection, providerId, "CONFIRMATION", "Participação confirmada", "Sua participação na tarefa foi confirmada.", taskId);
                if (approvedCount(connection, taskId) >= peopleNeeded(connection, taskId)) setTaskWindow(connection, taskId,"FILLED",null,"FILLED");
                else advancePriority(connection, taskId, ownerId, false);
            } else {
                notify(connection, ownerId, "WITHDRAWAL", "Oferta recusada", "O prestador prioritário recusou a oferta.", taskId);
                advancePriority(connection, taskId, ownerId, false);
            }
            connection.commit(); return answer;
        } catch (SQLException exception) { throw serverError("Não foi possível responder à oferta.", exception); }
    }

    void candidateApplied(String taskId, String candidateId) {
        try (Connection connection = connection()) {
            String owner = ownerId(connection, taskId);
            notify(connection, owner, "NEW_CANDIDATE", "Nova proposta", "Um prestador enviou uma proposta para sua tarefa.", taskId);
        } catch (SQLException exception) { throw serverError("Não foi possível gerar a notificação.", exception); }
    }

    void candidateDecision(String taskId, String candidateId, String status, String serviceLocation) {
        try (Connection connection = connection()) {
            String locationText = "PROVIDER".equals(serviceLocation)
                    ? " O atendimento será no seu local."
                    : " O atendimento será no local originalmente solicitado.";
            String text = "APPROVED".equals(status)
                    ? "Sua proposta foi aprovada." + locationText + " Confirme sua participação."
                    : "Sua proposta não foi selecionada.";
            notify(connection, candidateId, "CANDIDATE_DECISION", "Atualização da proposta", text, taskId);
        } catch (SQLException exception) { throw serverError("Não foi possível gerar a notificação.", exception); }
    }

    String candidateResponse(String userId, String taskId, String response) {
        String answer = response == null ? "" : response.trim().toUpperCase(Locale.ROOT);
        if (!("CONFIRMED".equals(answer) || "WITHDRAWN".equals(answer))) throw badRequest("Resposta deve ser CONFIRMED ou WITHDRAWN.");
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            String owner = ownerId(connection, taskId);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE agenda_candidates SET status=?,updated_at=CURRENT_TIMESTAMP
                    WHERE task_id=? AND user_id=? AND status IN ('PENDING','APPROVED','CONFIRMED')
                    """)) {
                update.setString(1, answer); update.setString(2, taskId); update.setString(3, userId);
                if (update.executeUpdate()==0) throw badRequest("Não existe uma participação ativa para responder.");
            }
            notify(connection, owner, "CONFIRMED".equals(answer)?"CONFIRMATION":"WITHDRAWAL",
                    "CONFIRMED".equals(answer)?"Participação confirmada":"Desistência",
                    "CONFIRMED".equals(answer)?"Um prestador confirmou a participação.":"Um prestador desistiu da tarefa.", taskId);
            if ("CONFIRMED".equals(answer) && approvedCount(connection, taskId) >= peopleNeeded(connection, taskId)) {
                setTaskWindow(connection, taskId, "FILLED", null, "FILLED");
            }
            connection.commit(); return answer;
        } catch (SQLException exception) { throw serverError("Não foi possível atualizar a participação.", exception); }
    }

    void resolveOpenTask(String ownerId, String taskId, String action) {
        String value = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (!("EXTEND".equals(value) || "CANCEL".equals(value))) throw badRequest("Ação deve ser EXTEND ou CANCEL.");
        try (Connection connection = connection()) {
            if (!ownerId.equals(ownerId(connection,taskId))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Apenas o contratante pode alterar a oferta.");
            if ("EXTEND".equals(value)) setTaskWindow(connection,taskId,"OPEN",LocalDateTime.now().plusHours(1),"ACTIVE");
            else setTaskWindow(connection,taskId,"CANCELLED",null,"CANCELLED");
        } catch (SQLException exception) { throw serverError("Não foi possível atualizar a tarefa.", exception); }
    }

    List<NotificationInfo> notifications(String userId, boolean unreadOnly) {
        processDeadlines();
        try (Connection connection = connection(); PreparedStatement query = connection.prepareStatement("""
                SELECT id,type,title,message,task_id,read_at,created_at FROM agenda_notifications
                WHERE user_id=? AND (?=FALSE OR read_at IS NULL) ORDER BY created_at DESC LIMIT 100
                """)) {
            query.setString(1,userId); query.setBoolean(2,unreadOnly); List<NotificationInfo> result=new ArrayList<>();
            try(ResultSet rows=query.executeQuery()) { while(rows.next()) result.add(new NotificationInfo(rows.getLong(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),rows.getTimestamp(6)==null,rows.getTimestamp(7).toLocalDateTime().format(DATE_TIME))); }
            return result;
        } catch(SQLException exception){ throw serverError("Não foi possível carregar as notificações.",exception); }
    }

    void readNotifications(String userId) {
        try(Connection connection=connection();PreparedStatement update=connection.prepareStatement("UPDATE agenda_notifications SET read_at=CURRENT_TIMESTAMP WHERE user_id=? AND read_at IS NULL")){
            update.setString(1,userId);update.executeUpdate();
        }catch(SQLException exception){throw serverError("Não foi possível marcar as notificações.",exception);}
    }

    void readNotification(String userId, long notificationId) {
        if (notificationId <= 0) throw badRequest("Notificação inválida.");
        try (Connection connection = connection(); PreparedStatement update = connection.prepareStatement(
                "UPDATE agenda_notifications SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP) WHERE id=? AND user_id=?")) {
            update.setLong(1, notificationId);
            update.setString(2, userId);
            if (update.executeUpdate() == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificação não encontrada.");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw serverError("Não foi possível marcar a notificação.", exception);
        }
    }

    private void closeOpenWindow(Connection connection,String taskId,String ownerId)throws SQLException{
        int candidates;
        try(PreparedStatement count=connection.prepareStatement("SELECT COUNT(*) FROM agenda_candidates WHERE task_id=? AND status NOT IN ('REJECTED','WITHDRAWN')")){
            count.setString(1,taskId);try(ResultSet rows=count.executeQuery()){rows.next();candidates=rows.getInt(1);}
        }
        setTaskWindow(connection,taskId,candidates==0?"AWAITING_OWNER":"REVIEW",null,candidates==0?"AWAITING_OWNER":"REVIEW");
        notify(connection,ownerId,candidates==0?"NO_CANDIDATES":"OFFER_EXPIRED",candidates==0?"Nenhuma proposta":"Prazo encerrado",
                candidates==0?"A oferta terminou sem interessados. Estenda por mais uma hora ou cancele.":"O prazo terminou. Analise as propostas recebidas.",taskId);
    }

    private void advancePriority(Connection connection,String taskId,String ownerId,boolean expired)throws SQLException{
        if(expired){
            try(PreparedStatement update=connection.prepareStatement("UPDATE agenda_task_invites SET status='EXPIRED',responded_at=CURRENT_TIMESTAMP WHERE task_id=? AND status='OFFERED'")){update.setString(1,taskId);update.executeUpdate();}
            notify(connection,ownerId,"PRIORITY_TIMEOUT","Prazo prioritário encerrado","O prestador não respondeu dentro de uma hora.",taskId);
        }
        try(PreparedStatement next=connection.prepareStatement("SELECT provider_id,priority_level FROM agenda_task_invites WHERE task_id=? AND status='WAITING' ORDER BY priority_level LIMIT 1")){
            next.setString(1,taskId);try(ResultSet rows=next.executeQuery()){
                if(rows.next()){offerLevel(connection,taskId,ownerId,rows.getString(1),rows.getInt(2));return;}
            }
        }
        setTaskWindow(connection,taskId,"OPEN",LocalDateTime.now().plusHours(1),"ACTIVE");
        notify(connection,ownerId,"OPEN_MARKET","Oferta aberta","A tarefa agora está aberta para prestadores não favoritos por uma hora.",taskId);
    }

    private void offerLevel(Connection connection,String taskId,String ownerId,String providerId,int level)throws SQLException{
        LocalDateTime expiry=LocalDateTime.now().plusHours(1);
        try(PreparedStatement update=connection.prepareStatement("UPDATE agenda_task_invites SET status='OFFERED',offered_at=CURRENT_TIMESTAMP,expires_at=? WHERE task_id=? AND provider_id=?")){
            update.setObject(1,expiry);update.setString(2,taskId);update.setString(3,providerId);update.executeUpdate();
        }
        setTaskWindow(connection,taskId,"PRIORITY_"+level,expiry,"ACTIVE");
        notify(connection,providerId,"PRIORITY_OFFER","Oferta prioritária","Você tem uma hora para aceitar ou recusar uma tarefa.",taskId);
        notify(connection,ownerId,level==1?"FIRST_LEVEL":level==2?"SECOND_LEVEL":"THIRD_LEVEL","Oferta de "+level+"º nível","O prestador prioritário tem uma hora para responder.",taskId);
    }

    private void validateFavoriteProviders(Connection connection,String taskId,String ownerId,List<String> providers)throws SQLException{
        for(String providerId:providers){
            try(PreparedStatement query=connection.prepareStatement("""
                    SELECT p.role_code,p.latitude,p.longitude,t.latitude,t.longitude,
                      EXISTS(SELECT 1 FROM agenda_favorites f WHERE f.consumer_id=? AND f.provider_id=?) favorite
                    FROM agenda_users p JOIN agenda_tasks t ON t.id=? WHERE p.id=?
                    """)){
                query.setString(1,ownerId);query.setString(2,providerId);query.setString(3,taskId);query.setString(4,providerId);
                try(ResultSet rows=query.executeQuery()){
                    if(!rows.next()||!rows.getBoolean("favorite"))throw badRequest("Selecione apenas prestadores da sua lista de favoritos.");
                    String role=rows.getString("role_code");if(!("PROVIDER".equals(role)||"BOTH".equals(role)))throw badRequest("Um favorito selecionado não é prestador.");
                    if(AgendaService.distanceKm(rows.getDouble(2),rows.getDouble(3),rows.getDouble(4),rows.getDouble(5))>50)throw badRequest("Um prestador selecionado está a mais de 50 km.");
                }
            }
        }
    }

    private List<ServicePrice> prices(Connection connection,String userId)throws SQLException{
        try(PreparedStatement query=connection.prepareStatement("SELECT id,service_name,price_cents FROM agenda_service_prices WHERE user_id=? ORDER BY service_name")){
            query.setString(1,userId);List<ServicePrice> result=new ArrayList<>();try(ResultSet rows=query.executeQuery()){while(rows.next())result.add(new ServicePrice(rows.getLong(1),rows.getString(2),rows.getInt(3)));}return result;
        }
    }

    private Plan plan(String code,boolean founder){
        for(Plan value:plans())if(value.code().equals(code))return founder&&"STARTER".equals(code)?new Plan(value.code(),"Essencial fundador",0,value.photoLimit(),value.videoLimit(),value.descriptionLimit(),value.servicePriceLimit(),false,true):value;
        return plans().get(0);
    }

    private void setTaskWindow(Connection connection,String taskId,String phase,LocalDateTime expires,String status)throws SQLException{
        try(PreparedStatement update=connection.prepareStatement("UPDATE agenda_tasks SET offer_phase=?,offer_expires_at=?,task_status=? WHERE id=?")){
            update.setString(1,phase);update.setObject(2,expires);update.setString(3,status);update.setString(4,taskId);update.executeUpdate();
        }
    }
    private void notify(Connection connection,String userId,String type,String title,String message,String taskId)throws SQLException{
        try(PreparedStatement insert=connection.prepareStatement("INSERT INTO agenda_notifications(user_id,type,title,message,task_id) VALUES(?,?,?,?,?)")){
            insert.setString(1,userId);insert.setString(2,type);insert.setString(3,title);insert.setString(4,message);insert.setString(5,taskId);insert.executeUpdate();
        }
    }
    private String ownerId(Connection connection,String taskId)throws SQLException{try(PreparedStatement q=connection.prepareStatement("SELECT owner_id FROM agenda_tasks WHERE id=?")){q.setString(1,taskId);try(ResultSet r=q.executeQuery()){if(!r.next())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tarefa não encontrada.");return r.getString(1);}}}
    private int peopleNeeded(Connection connection,String taskId)throws SQLException{try(PreparedStatement q=connection.prepareStatement("SELECT people_needed FROM agenda_tasks WHERE id=?")){q.setString(1,taskId);try(ResultSet r=q.executeQuery()){r.next();return r.getInt(1);}}}
    private int approvedCount(Connection connection,String taskId)throws SQLException{try(PreparedStatement q=connection.prepareStatement("SELECT COUNT(*) FROM agenda_candidates WHERE task_id=? AND status IN ('APPROVED','CONFIRMED')")){q.setString(1,taskId);try(ResultSet r=q.executeQuery()){r.next();return r.getInt(1);}}}
    private Connection connection() throws SQLException { return dataSource.getConnection(); }
    private void requireRole(String userId,String required){
        try(Connection connection=connection();PreparedStatement query=connection.prepareStatement("SELECT role_code FROM agenda_users WHERE id=?")){
            query.setString(1,userId);try(ResultSet rows=query.executeQuery()){if(!rows.next())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Perfil não encontrado.");String role=rows.getString(1);if(!(required.equals(role)||"BOTH".equals(role)))throw badRequest("Seu perfil não permite esta ação.");}
        }catch(SQLException exception){throw serverError("Não foi possível validar o tipo de usuário.",exception);}
    }
    private static ResponseStatusException badRequest(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException serverError(String message,Exception cause){return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,message,cause);}

    record ProfileUpdate(String name,String role,String bio,List<Long> specialtyIds){}
    record ServicePriceInput(String serviceName,int priceCents){}
    record ServicePrice(long id,String serviceName,int priceCents){}
    record Plan(String code,String name,int monthlyPriceCents,int photoLimit,int videoLimit,int descriptionLimit,int servicePriceLimit,boolean showAds,boolean founderFree){}
    record Profile(String id,String name,String role,String bio,Plan plan,List<ServicePrice> prices,List<Long> specialtyIds){}
    record FavoriteProvider(String id,String name,String bio,List<ServicePrice> prices){}
    record NotificationInfo(long id,String type,String title,String message,String taskId,boolean unread,String createdAt){}
    record OfferResponse(String response){}
    record TaskAction(String action){}
    private record ExpiredTask(String id,String ownerId,String phase){}
}
