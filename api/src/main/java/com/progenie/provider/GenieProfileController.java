package com.progenie.provider;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.progenie.provider.GenieDocumentService.StoredDocument;
import com.progenie.provider.GenieProfileDtos.AvailabilityDto;
import com.progenie.provider.GenieProfileDtos.AvailabilityRequest;
import com.progenie.provider.GenieProfileDtos.DocumentDto;
import com.progenie.provider.GenieProfileDtos.LocationRequest;
import com.progenie.provider.GenieProfileDtos.MyProfileDto;
import com.progenie.provider.GenieProfileDtos.MyServiceDto;
import com.progenie.provider.GenieProfileDtos.OnlineRequest;
import com.progenie.provider.GenieProfileDtos.ProfileRequest;
import com.progenie.provider.GenieProfileDtos.ServicesRequest;
import com.progenie.provider.GenieProfileDtos.TimeOffDto;
import com.progenie.provider.GenieProfileDtos.TimeOffRequest;
import com.progenie.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Genie-only endpoints for onboarding and running their profile (all under /api/v1/genie, role GENIE). */
@RestController
@RequestMapping("/api/v1/genie")
public class GenieProfileController {

    private final GenieProfileService profiles;
    private final GenieDocumentService documents;

    public GenieProfileController(GenieProfileService profiles, GenieDocumentService documents) {
        this.profiles = profiles;
        this.documents = documents;
    }

    @GetMapping("/profile")
    public MyProfileDto me() {
        return profiles.me(CurrentUser.id());
    }

    @PutMapping("/profile")
    public MyProfileDto update(@Valid @RequestBody ProfileRequest req) {
        return profiles.updateProfile(CurrentUser.id(), req);
    }

    /** Sends the completed profile to the admin verification queue. */
    @PostMapping("/profile/submit")
    public MyProfileDto submit() {
        return profiles.submitForReview(CurrentUser.id());
    }

    @PutMapping("/services")
    public List<MyServiceDto> services(@Valid @RequestBody ServicesRequest req) {
        return profiles.replaceServices(CurrentUser.id(), req.services());
    }

    @PutMapping("/availability")
    public List<AvailabilityDto> availability(@Valid @RequestBody AvailabilityRequest req) {
        return profiles.replaceAvailability(CurrentUser.id(), req.windows());
    }

    @GetMapping("/time-off")
    public List<TimeOffDto> timeOff() {
        return profiles.timeOff(CurrentUser.id());
    }

    @PostMapping("/time-off")
    @ResponseStatus(HttpStatus.CREATED)
    public TimeOffDto addTimeOff(@Valid @RequestBody TimeOffRequest req) {
        return profiles.addTimeOff(CurrentUser.id(), req);
    }

    @DeleteMapping("/time-off/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTimeOff(@PathVariable long id) {
        profiles.deleteTimeOff(CurrentUser.id(), id);
    }

    @GetMapping("/documents")
    public List<DocumentDto> documents() {
        return documents.list(CurrentUser.id());
    }

    /** multipart/form-data: docType=AADHAAR|PAN|SELFIE|CERTIFICATE|OTHER, last4 (for Aadhaar/PAN), file. */
    @PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentDto upload(@RequestParam String docType,
                              @RequestParam(required = false) String last4,
                              @RequestPart MultipartFile file) {
        return documents.upload(CurrentUser.id(), docType, last4, file);
    }

    @GetMapping("/documents/{id}/file")
    public ResponseEntity<InputStreamResource> documentFile(@PathVariable UUID id) {
        StoredDocument doc = documents.find(id, CurrentUser.id());
        return fileResponse(doc, documents);
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable UUID id) {
        documents.delete(CurrentUser.id(), id);
    }

    @PostMapping("/status")
    public Map<String, Boolean> online(@RequestBody OnlineRequest req) {
        return Map.of("online", profiles.setOnline(CurrentUser.id(), req.online()));
    }

    @PutMapping("/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void location(@Valid @RequestBody LocationRequest req) {
        profiles.updateLocation(CurrentUser.id(), req);
    }

    /** Streams a KYC file privately (never cached, always as an attachment). Shared with the admin controller. */
    public static ResponseEntity<InputStreamResource> fileResponse(StoredDocument doc, GenieDocumentService documents) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(doc.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(doc.fileName()).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(new InputStreamResource(documents.open(doc)));
    }
}
