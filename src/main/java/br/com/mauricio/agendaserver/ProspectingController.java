package br.com.mauricio.agendaserver;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agenda")
public final class ProspectingController {
    private static final String DEVICE_HEADER = "X-Agenda-Device-Id";
    private static final String TOKEN_HEADER = "X-Agenda-Auth-Token";
    private final AgendaService agenda;
    private final AdminAuthorizationService admins;
    private final SpecialtyService specialties;
    private final ProspectingService prospecting;
    private final CnpjImportService imports;
    private final ProspectingSettingsService settings;
    private final ProspectingProcessLogService processLogs;

    public ProspectingController(AgendaService agenda, AdminAuthorizationService admins, SpecialtyService specialties,
                          ProspectingService prospecting, CnpjImportService imports,
                          ProspectingSettingsService settings, ProspectingProcessLogService processLogs) {
        this.agenda = agenda;
        this.admins = admins;
        this.specialties = specialties;
        this.prospecting = prospecting;
        this.imports = imports;
        this.settings = settings;
        this.processLogs = processLogs;
    }

    @GetMapping("/specialties")
    public List<SpecialtyService.Specialty> activeSpecialties(
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(TOKEN_HEADER) String authToken) {
        agenda.authenticate(deviceId, authToken);
        return specialties.activeSpecialties();
    }

    @GetMapping("/users/me/specialties")
    public List<Long> mySpecialties(@RequestHeader(DEVICE_HEADER) String deviceId,
                             @RequestHeader(TOKEN_HEADER) String authToken) {
        AgendaService.AgendaUser user = agenda.authenticate(deviceId, authToken);
        return specialties.userSpecialtyIds(user.id());
    }

    @PutMapping("/users/me/specialties")
    public Map<String, Object> updateMySpecialties(@RequestHeader(DEVICE_HEADER) String deviceId,
                                             @RequestHeader(TOKEN_HEADER) String authToken,
                                             @RequestBody SpecialtySelection body) {
        AgendaService.AgendaUser user = agenda.authenticate(deviceId, authToken);
        specialties.replaceUserSpecialties(user.id(), body == null ? List.of() : body.specialtyIds());
        return Map.of("status", "SAVED", "specialtyIds", specialties.userSpecialtyIds(user.id()));
    }

    @GetMapping("/tasks/{taskId}/prospecting")
    public ProspectingService.JobSummary taskProspecting(@RequestHeader(DEVICE_HEADER) String deviceId,
                                                   @RequestHeader(TOKEN_HEADER) String authToken,
                                                   @PathVariable String taskId) {
        AgendaService.AgendaUser user = agenda.authenticate(deviceId, authToken);
        if (!admins.isAdmin(user.email()) && !prospecting.isTaskOwner(user.id(), taskId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas o consumidor responsável ou o administrador podem ver este processamento.");
        }
        return prospecting.summary(taskId);
    }


    @GetMapping("/admin/tasks/{taskId}/prospecting/logs")
    public List<ProspectingProcessLogService.ProcessLogEntry> processLogs(
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(TOKEN_HEADER) String authToken,
            @PathVariable String taskId,
            @RequestParam(defaultValue = "500") int limit) {
        AgendaService.AgendaUser user = agenda.authenticate(deviceId, authToken);
        admins.requireProcessLogAdmin(user);
        return processLogs.listByTask(taskId, limit);
    }

    @GetMapping("/admin/access")
    public Map<String, Object> adminAccess(@RequestHeader(DEVICE_HEADER) String deviceId,
                                    @RequestHeader(TOKEN_HEADER) String authToken) {
        AgendaService.AgendaUser user = agenda.authenticate(deviceId, authToken);
        admins.requireAdmin(user);
        return Map.of(
                "authorized", true,
                "email", user.email(),
                "processLogAdmin", admins.isPrimaryAdmin(user.email())
        );
    }

    @GetMapping("/admin/specialties")
    public List<SpecialtyService.Specialty> adminSpecialties(@RequestHeader(DEVICE_HEADER) String deviceId,
                                                       @RequestHeader(TOKEN_HEADER) String authToken) {
        admins.requireAdmin(agenda.authenticate(deviceId, authToken));
        return specialties.allSpecialties();
    }

    @PostMapping("/admin/specialties")
    public SpecialtyService.Specialty createSpecialty(@RequestHeader(DEVICE_HEADER) String deviceId,
                                                @RequestHeader(TOKEN_HEADER) String authToken,
                                                @RequestBody SpecialtyService.SpecialtyInput body) {
        admins.requireAdmin(agenda.authenticate(deviceId, authToken));
        return specialties.create(body);
    }

    @PutMapping("/admin/specialties/{id}")
    public SpecialtyService.Specialty updateSpecialty(@RequestHeader(DEVICE_HEADER) String deviceId,
                                                @RequestHeader(TOKEN_HEADER) String authToken,
                                                @PathVariable long id,
                                                @RequestBody SpecialtyService.SpecialtyInput body) {
        admins.requireAdmin(agenda.authenticate(deviceId, authToken));
        return specialties.update(id, body);
    }

    @PutMapping("/admin/specialties/{id}/cnaes/{code}")
    public SpecialtyService.CnaeLink saveCnae(@RequestHeader(DEVICE_HEADER) String deviceId,
                                       @RequestHeader(TOKEN_HEADER) String authToken,
                                       @PathVariable long id, @PathVariable String code,
                                       @RequestBody SpecialtyService.CnaeInput body) {
        admins.requireAdmin(agenda.authenticate(deviceId, authToken));
        return specialties.saveCnae(id, code, body);
    }

    @DeleteMapping("/admin/specialties/{id}/cnaes/{code}")
    public Map<String, String> deleteCnae(@RequestHeader(DEVICE_HEADER) String deviceId,
                                   @RequestHeader(TOKEN_HEADER) String authToken,
                                   @PathVariable long id, @PathVariable String code) {
        admins.requireAdmin(agenda.authenticate(deviceId, authToken));
        specialties.deleteCnae(id, code);
        return Map.of("status", "REMOVED");
    }

    @PostMapping("/admin/cnpj-imports")
    public CnpjImportService.ImportRun startImport(@RequestHeader(DEVICE_HEADER) String deviceId,
                                             @RequestHeader(TOKEN_HEADER) String authToken,
                                             @RequestBody CnpjImportService.ImportRequest body) {
        AgendaService.AgendaUser user = agenda.authenticate(deviceId, authToken);
        admins.requireAdmin(user);
        return imports.start(user.id(), body);
    }

    @GetMapping("/admin/cnpj-imports")
    public List<CnpjImportService.ImportRun> listImports(@RequestHeader(DEVICE_HEADER) String deviceId,
                                                  @RequestHeader(TOKEN_HEADER) String authToken) {
        admins.requireAdmin(agenda.authenticate(deviceId, authToken));
        return imports.list();
    }

    @PostMapping("/admin/cnpj-imports/{id}/resume")
    public CnpjImportService.ImportRun resumeImport(@RequestHeader(DEVICE_HEADER) String deviceId,
                                              @RequestHeader(TOKEN_HEADER) String authToken,
                                              @PathVariable String id) {
        admins.requireAdmin(agenda.authenticate(deviceId, authToken));
        return imports.resume(id);
    }

    @PostMapping("/admin/cnpj-imports/{id}/cancel")
    public CnpjImportService.ImportRun cancelImport(@RequestHeader(DEVICE_HEADER) String deviceId,
                                              @RequestHeader(TOKEN_HEADER) String authToken,
                                              @PathVariable String id) {
        admins.requireAdmin(agenda.authenticate(deviceId, authToken));
        return imports.cancel(id);
    }

    @GetMapping("/admin/prospecting/settings")
    public Map<String, Object> settings(@RequestHeader(DEVICE_HEADER) String deviceId,
                                 @RequestHeader(TOKEN_HEADER) String authToken) {
        admins.requireSettingsAdmin(agenda.authenticate(deviceId, authToken));
        return settings.adminView();
    }

    @PutMapping("/admin/prospecting/settings")
    public Map<String, Object> updateSettings(@RequestHeader(DEVICE_HEADER) String deviceId,
                                       @RequestHeader(TOKEN_HEADER) String authToken,
                                       @RequestBody ProspectingSettingsService.SettingsUpdate body) {
        AgendaService.AgendaUser user = agenda.authenticate(deviceId, authToken);
        admins.requireSettingsAdmin(user);
        settings.updateEditable(user.id(), body);
        return settings.adminView();
    }

    @PostMapping("/admin/tasks/{taskId}/prospecting/simulate")
    public ProspectingService.JobSummary simulate(@RequestHeader(DEVICE_HEADER) String deviceId,
                                            @RequestHeader(TOKEN_HEADER) String authToken,
                                            @PathVariable String taskId) {
        AgendaService.AgendaUser user = agenda.authenticate(deviceId, authToken);
        admins.requireAdmin(user);
        return prospecting.simulate(taskId, user.id());
    }

    @PostMapping("/admin/tasks/{taskId}/prospecting/authorize")
    public ProspectingService.JobSummary authorize(@RequestHeader(DEVICE_HEADER) String deviceId,
                                             @RequestHeader(TOKEN_HEADER) String authToken,
                                             @PathVariable String taskId) {
        AgendaService.AgendaUser user = agenda.authenticate(deviceId, authToken);
        admins.requireAdmin(user);
        return prospecting.authorizeSending(taskId, user.id());
    }

    @PostMapping("/admin/tasks/{taskId}/prospecting/cancel")
    public ProspectingService.JobSummary cancel(@RequestHeader(DEVICE_HEADER) String deviceId,
                                          @RequestHeader(TOKEN_HEADER) String authToken,
                                          @PathVariable String taskId) {
        admins.requireAdmin(agenda.authenticate(deviceId, authToken));
        return prospecting.cancel(taskId);
    }

    @GetMapping("/admin/tasks/{taskId}/prospecting/preview")
    public List<ProspectingService.InvitationPreview> preview(@RequestHeader(DEVICE_HEADER) String deviceId,
                                                        @RequestHeader(TOKEN_HEADER) String authToken,
                                                        @PathVariable String taskId) {
        admins.requireAdmin(agenda.authenticate(deviceId, authToken));
        return prospecting.preview(taskId);
    }

    @GetMapping("/admin/prospecting/metrics")
    public Map<String, Object> metrics(@RequestHeader(DEVICE_HEADER) String deviceId,
                                @RequestHeader(TOKEN_HEADER) String authToken) {
        admins.requireAdmin(agenda.authenticate(deviceId, authToken));
        return prospecting.metrics();
    }

    @GetMapping("/admin/suppressions")
    public List<ProspectingService.SuppressionInfo> suppressions(@RequestHeader(DEVICE_HEADER) String deviceId,
                                                           @RequestHeader(TOKEN_HEADER) String authToken) {
        admins.requireAdmin(agenda.authenticate(deviceId, authToken));
        return prospecting.suppressions();
    }

    record SpecialtySelection(List<Long> specialtyIds) {}
}
