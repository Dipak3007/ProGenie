package com.progenie.identity.privacy;

import java.util.List;
import java.util.UUID;

import com.progenie.identity.privacy.AccountDeletionService.DeletionRequestRow;
import com.progenie.identity.privacy.AccountDeletionService.DeletionStatus;
import com.progenie.identity.privacy.DataExportService.ExportFile;
import com.progenie.identity.privacy.DataExportService.ExportJobDto;
import com.progenie.shared.security.CurrentUser;
import com.progenie.shared.web.PageResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Privacy rights of the logged-in user: download my data, delete my account. */
@RestController
public class PrivacyController {

    public record DeletionRequestBody(String reason) {
    }

    private final DataExportService exports;
    private final AccountDeletionService deletions;

    public PrivacyController(DataExportService exports, AccountDeletionService deletions) {
        this.exports = exports;
        this.deletions = deletions;
    }

    @PostMapping("/api/v1/me/data-export")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExportJobDto requestExport() {
        return exports.request(CurrentUser.id());
    }

    @GetMapping("/api/v1/me/data-export")
    public List<ExportJobDto> exports() {
        return exports.list(CurrentUser.id());
    }

    @GetMapping("/api/v1/me/data-export/{id}/file")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        ExportFile f = exports.download(CurrentUser.id(), id);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.fileName()).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(f.content());
    }

    /** The pending deletion (if any) and what currently blocks one. */
    @GetMapping("/api/v1/me/deletion-request")
    public DeletionStatus deletionStatus() {
        return deletions.status(CurrentUser.id());
    }

    @PostMapping("/api/v1/me/deletion-request")
    @ResponseStatus(HttpStatus.CREATED)
    public DeletionStatus requestDeletion(@RequestBody(required = false) DeletionRequestBody body) {
        return deletions.request(CurrentUser.id(), body == null ? null : body.reason());
    }

    @DeleteMapping("/api/v1/me/deletion-request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelDeletion() {
        deletions.cancel(CurrentUser.id());
    }

    @GetMapping("/api/v1/admin/deletion-requests")
    public PageResponse<DeletionRequestRow> adminList(@RequestParam(required = false) String status,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return deletions.adminList(status, page, size);
    }
}
