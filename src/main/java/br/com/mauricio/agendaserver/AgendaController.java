package br.com.mauricio.agendaserver;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agenda")
public final class AgendaController {
    private static final String DEVICE_HEADER = "X-Agenda-Device-Id";
    private static final String TOKEN_HEADER = "X-Agenda-Auth-Token";
    private final AgendaService service;

    public AgendaController(AgendaService service) { this.service = service; }

    @GetMapping("/plans")
    public List<AgendaMarketplaceService.Plan> plans() { return service.marketplace().plans(); }

    @GetMapping("/users/me/profile")
    public AgendaMarketplaceService.Profile profile(
            @RequestHeader(DEVICE_HEADER) String deviceId, @RequestHeader(TOKEN_HEADER) String authToken) {
        AgendaService.AgendaUser user = service.authenticate(deviceId, authToken);
        return service.marketplace().profile(user.id());
    }

    @PutMapping("/users/me/profile")
    public AgendaMarketplaceService.Profile updateProfile(
            @RequestHeader(DEVICE_HEADER) String deviceId, @RequestHeader(TOKEN_HEADER) String authToken,
            @RequestBody AgendaMarketplaceService.ProfileUpdate body) {
        AgendaService.AgendaUser user = service.authenticate(deviceId, authToken);
        return service.marketplace().updateProfile(user.id(), body);
    }

    @PutMapping("/users/me/prices")
    public Map<String, String> prices(
            @RequestHeader(DEVICE_HEADER) String deviceId, @RequestHeader(TOKEN_HEADER) String authToken,
            @RequestBody List<AgendaMarketplaceService.ServicePriceInput> body) {
        AgendaService.AgendaUser user = service.authenticate(deviceId, authToken);
        service.marketplace().replacePrices(user.id(), body); return Map.of("status", "SAVED");
    }

    @GetMapping("/users/me/favorites")
    public List<AgendaMarketplaceService.FavoriteProvider> favorites(
            @RequestHeader(DEVICE_HEADER) String deviceId, @RequestHeader(TOKEN_HEADER) String authToken) {
        return service.marketplace().favorites(service.authenticate(deviceId, authToken).id());
    }

    @PostMapping("/users/me/favorites/{providerId}")
    public Map<String, String> addFavorite(
            @RequestHeader(DEVICE_HEADER) String deviceId, @RequestHeader(TOKEN_HEADER) String authToken,
            @PathVariable String providerId) {
        service.marketplace().addFavorite(service.authenticate(deviceId, authToken).id(), providerId);
        return Map.of("status", "FAVORITE");
    }

    @DeleteMapping("/users/me/favorites/{providerId}")
    public Map<String, String> removeFavorite(
            @RequestHeader(DEVICE_HEADER) String deviceId, @RequestHeader(TOKEN_HEADER) String authToken,
            @PathVariable String providerId) {
        service.marketplace().removeFavorite(service.authenticate(deviceId, authToken).id(), providerId);
        return Map.of("status", "REMOVED");
    }

    @GetMapping("/notifications")
    public List<AgendaMarketplaceService.NotificationInfo> notifications(
            @RequestHeader(DEVICE_HEADER) String deviceId, @RequestHeader(TOKEN_HEADER) String authToken,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return service.marketplace().notifications(service.authenticate(deviceId, authToken).id(), unreadOnly);
    }

    @PutMapping("/notifications/read")
    public Map<String, String> readNotifications(
            @RequestHeader(DEVICE_HEADER) String deviceId, @RequestHeader(TOKEN_HEADER) String authToken) {
        service.marketplace().readNotifications(service.authenticate(deviceId, authToken).id());
        return Map.of("status", "READ");
    }

    @PutMapping("/notifications/{notificationId}/read")
    public Map<String, String> readNotification(
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(TOKEN_HEADER) String authToken,
            @PathVariable long notificationId) {
        service.marketplace().readNotification(service.authenticate(deviceId, authToken).id(), notificationId);
        return Map.of("status", "READ");
    }

    @PostMapping("/session")
    public AgendaService.AgendaUser session(
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(TOKEN_HEADER) String authToken,
            @RequestBody AgendaService.SessionRequest request) {
        return service.openSession(deviceId, authToken, request);
    }

    @GetMapping("/tasks")
    public List<AgendaService.AgendaTask> tasks(
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(TOKEN_HEADER) String authToken,
            @RequestParam double latitude,
            @RequestParam double longitude,
            HttpServletRequest request) {
        return service.listTasks(service.authenticate(deviceId, authToken), latitude, longitude, baseUrl(request));
    }

    @PostMapping("/tasks")
    public AgendaService.AgendaTask createTask(
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(TOKEN_HEADER) String authToken,
            @RequestBody AgendaService.CreateTaskRequest body,
            HttpServletRequest request) {
        return service.createTask(service.authenticate(deviceId, authToken), body, baseUrl(request));
    }

    @PostMapping("/tasks/{taskId}/candidates")
    public Map<String, String> apply(
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(TOKEN_HEADER) String authToken,
            @PathVariable String taskId,
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(defaultValue = "ORIGINAL") String locationProposal) {
        service.apply(service.authenticate(deviceId, authToken), taskId, latitude, longitude, locationProposal);
        return Map.of("status", "PENDING");
    }

    @PutMapping("/tasks/{taskId}/candidates/{candidateId}")
    public Map<String, String> decide(
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(TOKEN_HEADER) String authToken,
            @PathVariable String taskId,
            @PathVariable String candidateId,
            @RequestBody AgendaService.CandidateDecision decision) {
        service.decideCandidate(service.authenticate(deviceId, authToken), taskId, candidateId, decision);
        return Map.of("status", decision.status());
    }

    @PostMapping("/tasks/{taskId}/offer-response")
    public Map<String, String> respondOffer(
            @RequestHeader(DEVICE_HEADER) String deviceId, @RequestHeader(TOKEN_HEADER) String authToken,
            @PathVariable String taskId, @RequestBody AgendaMarketplaceService.OfferResponse body) {
        String status = service.marketplace().respondOffer(service.authenticate(deviceId, authToken).id(), taskId, body.response());
        return Map.of("status", status);
    }

    @PostMapping("/tasks/{taskId}/participation-response")
    public Map<String, String> respondParticipation(
            @RequestHeader(DEVICE_HEADER) String deviceId, @RequestHeader(TOKEN_HEADER) String authToken,
            @PathVariable String taskId, @RequestBody AgendaMarketplaceService.OfferResponse body) {
        String status = service.marketplace().candidateResponse(service.authenticate(deviceId, authToken).id(), taskId, body.response());
        return Map.of("status", status);
    }

    @PutMapping("/tasks/{taskId}/offer")
    public Map<String, String> resolveOffer(
            @RequestHeader(DEVICE_HEADER) String deviceId, @RequestHeader(TOKEN_HEADER) String authToken,
            @PathVariable String taskId, @RequestBody AgendaMarketplaceService.TaskAction body) {
        service.marketplace().resolveOpenTask(service.authenticate(deviceId, authToken).id(), taskId, body.action());
        return Map.of("status", body.action().toUpperCase());
    }

    @GetMapping("/users/me/photos")
    public List<AgendaService.PhotoInfo> photos(
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(TOKEN_HEADER) String authToken,
            HttpServletRequest request) {
        return service.myPhotos(service.authenticate(deviceId, authToken), baseUrl(request));
    }

    @PostMapping(path = "/users/me/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AgendaService.PhotoInfo uploadPhoto(
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(TOKEN_HEADER) String authToken,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request) {
        return service.addPhoto(service.authenticate(deviceId, authToken), file, baseUrl(request));
    }

    @DeleteMapping("/users/me/photos/{photoId}")
    public Map<String, String> deletePhoto(
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(TOKEN_HEADER) String authToken,
            @PathVariable String photoId) {
        service.deletePhoto(service.authenticate(deviceId, authToken), photoId);
        return Map.of("status", "REMOVED");
    }

    @GetMapping("/photos/{photoId}")
    public ResponseEntity<FileSystemResource> photo(@PathVariable String photoId) {
        AgendaService.PhotoFile photo = service.photo(photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.mimeType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(new FileSystemResource(photo.path()));
    }

    @GetMapping("/users/me/videos")
    public List<AgendaService.VideoInfo> videos(
            @RequestHeader(DEVICE_HEADER) String deviceId,@RequestHeader(TOKEN_HEADER) String authToken,HttpServletRequest request){
        return service.myVideos(service.authenticate(deviceId,authToken),baseUrl(request));
    }

    @PostMapping(path="/users/me/videos",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public AgendaService.VideoInfo uploadVideo(
            @RequestHeader(DEVICE_HEADER) String deviceId,@RequestHeader(TOKEN_HEADER) String authToken,
            @RequestPart("file") MultipartFile file,HttpServletRequest request){
        return service.addVideo(service.authenticate(deviceId,authToken),file,baseUrl(request));
    }

    @DeleteMapping("/users/me/videos/{videoId}")
    public Map<String, String> deleteVideo(
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(TOKEN_HEADER) String authToken,
            @PathVariable String videoId) {
        service.deleteVideo(service.authenticate(deviceId, authToken), videoId);
        return Map.of("status", "REMOVED");
    }

    @GetMapping("/videos/{videoId}")
    public ResponseEntity<FileSystemResource> video(@PathVariable String videoId){
        AgendaService.PhotoFile video=service.video(videoId);return ResponseEntity.ok().contentType(MediaType.parseMediaType(video.mimeType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic()).body(new FileSystemResource(video.path()));
    }

    private String baseUrl(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromRequestUri(request).replacePath(request.getContextPath()).replaceQuery(null)
                .build().toUriString().replaceAll("/$", "");
    }
}
