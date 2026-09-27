package com.progenie.admin;

import java.util.UUID;

import com.progenie.notification.delivery.MessageAdmin;
import com.progenie.notification.delivery.MessageAdmin.MessageDto;
import com.progenie.shared.web.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Outgoing SMS / WhatsApp / email: see what was sent, find failures, retry them. */
@RestController
@RequestMapping("/api/v1/admin/messages")
public class AdminMessagesController {

    private final MessageAdmin messages;

    public AdminMessagesController(MessageAdmin messages) {
        this.messages = messages;
    }

    @GetMapping
    public PageResponse<MessageDto> list(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String channel,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return messages.list(status, channel, q, page, size);
    }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retry(@PathVariable UUID id) {
        messages.retry(id);
    }
}
