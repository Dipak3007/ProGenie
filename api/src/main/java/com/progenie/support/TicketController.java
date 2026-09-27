package com.progenie.support;

import java.util.UUID;

import com.progenie.shared.security.CurrentUser;
import com.progenie.shared.web.PageResponse;
import com.progenie.support.TicketService.AttachmentDto;
import com.progenie.support.TicketService.FileContent;
import com.progenie.support.TicketService.RaiseRequest;
import com.progenie.support.TicketService.TicketDetail;
import com.progenie.support.TicketService.TicketSummary;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** "Report a problem" for customers and Genies, plus privacy requests. Admins use /api/v1/admin/tickets. */
@RestController
public class TicketController {

    public record MessageRequest(String body) {
    }

    public record PrivacyRequest(String subject, String description) {
    }

    private final TicketService tickets;

    public TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/api/v1/bookings/{bookingId}/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDetail raiseAsCustomer(@PathVariable UUID bookingId, @RequestBody RaiseRequest req) {
        return tickets.raise(CurrentUser.id(), "CUSTOMER", bookingId, req);
    }

    @PostMapping("/api/v1/genie/bookings/{bookingId}/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDetail raiseAsGenie(@PathVariable UUID bookingId, @RequestBody RaiseRequest req) {
        return tickets.raise(CurrentUser.id(), "GENIE", bookingId, req);
    }

    /** A privacy request or grievance (access, correction, deletion questions, consent withdrawal). */
    @PostMapping("/api/v1/tickets/privacy")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDetail privacy(@RequestBody PrivacyRequest req) {
        return tickets.raisePrivacy(CurrentUser.id(), CurrentUser.role(), req.subject(), req.description());
    }

    /** Reports I raised or that concern my bookings. {@code status}: a status or ACTIVE. */
    @GetMapping("/api/v1/tickets")
    public PageResponse<TicketSummary> mine(@RequestParam(required = false) String status,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return tickets.mine(CurrentUser.id(), status, page, size);
    }

    @GetMapping("/api/v1/tickets/{id}")
    public TicketDetail get(@PathVariable UUID id) {
        return tickets.detail(id, CurrentUser.id(), CurrentUser.role());
    }

    @PostMapping("/api/v1/tickets/{id}/messages")
    public TicketDetail reply(@PathVariable UUID id, @RequestBody MessageRequest req) {
        return tickets.reply(id, CurrentUser.id(), CurrentUser.role(), req.body());
    }

    @PostMapping(path = "/api/v1/tickets/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentDto photo(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return tickets.addPhoto(id, CurrentUser.id(), CurrentUser.role(), file);
    }

    @GetMapping("/api/v1/tickets/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id, @PathVariable UUID attachmentId) {
        FileContent f = tickets.photo(id, attachmentId, CurrentUser.id(), CurrentUser.role());
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(f.contentType()))
            .cacheControl(CacheControl.noStore().cachePrivate())
            .header("X-Content-Type-Options", "nosniff")
            .body(f.bytes());
    }

    @PostMapping("/api/v1/tickets/{id}/accept")
    public TicketDetail accept(@PathVariable UUID id) {
        return tickets.accept(id, CurrentUser.id(), CurrentUser.role());
    }

    @PostMapping("/api/v1/tickets/{id}/reopen")
    public TicketDetail reopen(@PathVariable UUID id, @RequestBody MessageRequest req) {
        return tickets.reopen(id, CurrentUser.id(), CurrentUser.role(), req.body());
    }
}
