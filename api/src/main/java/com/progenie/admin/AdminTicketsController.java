package com.progenie.admin;

import java.util.UUID;

import com.progenie.shared.security.CurrentUser;
import com.progenie.shared.web.PageResponse;
import com.progenie.support.TicketAdminService;
import com.progenie.support.TicketAdminService.AdminReply;
import com.progenie.support.TicketAdminService.ResolveRequest;
import com.progenie.support.TicketService;
import com.progenie.support.TicketService.TicketDetail;
import com.progenie.support.TicketService.TicketSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Complaint and privacy-request queue with deadlines; replies, internal notes and resolutions. */
@RestController
@RequestMapping("/api/v1/admin/tickets")
public class AdminTicketsController {

    public record AssignRequest(UUID adminId) {
    }

    public record RejectRequest(String reason) {
    }

    private final TicketAdminService admin;
    private final TicketService tickets;

    public AdminTicketsController(TicketAdminService admin, TicketService tickets) {
        this.admin = admin;
        this.tickets = tickets;
    }

    @GetMapping
    public PageResponse<TicketSummary> queue(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String priority,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(defaultValue = "false") boolean overdue,
                                             @RequestParam(defaultValue = "false") boolean mine,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return admin.queue(status, priority, type, overdue, mine, CurrentUser.id(), q, page, size);
    }

    @GetMapping("/{id}")
    public TicketDetail get(@PathVariable UUID id) {
        return tickets.detail(id, CurrentUser.id(), "ADMIN");
    }

    @PostMapping("/{id}/assign")
    public TicketDetail assign(@PathVariable UUID id, @RequestBody(required = false) AssignRequest req) {
        return admin.assign(id, CurrentUser.id(), req == null ? null : req.adminId());
    }

    @PostMapping("/{id}/reply")
    public TicketDetail reply(@PathVariable UUID id, @RequestBody AdminReply req) {
        return admin.reply(id, CurrentUser.id(), req);
    }

    @PostMapping("/{id}/resolve")
    public TicketDetail resolve(@PathVariable UUID id, @RequestBody ResolveRequest req) {
        return admin.resolve(id, CurrentUser.id(), req);
    }

    @PostMapping("/{id}/reject")
    public TicketDetail reject(@PathVariable UUID id, @RequestBody RejectRequest req) {
        return admin.reject(id, CurrentUser.id(), req.reason());
    }
}
