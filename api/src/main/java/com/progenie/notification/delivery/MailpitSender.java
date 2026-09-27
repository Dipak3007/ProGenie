package com.progenie.notification.delivery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.progenie.notification.delivery.MessageOutbox.QueuedMessage;
import com.progenie.shared.storage.FileStorage;
import com.progenie.shared.util.Phones;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Local adapter for every channel: sends through Mailpit's HTTP API ({@code POST /api/v1/send}), so nothing
 * leaves your machine. SMS arrive as emails to {@code <phone>@sms.progenie.local} and WhatsApp messages to
 * {@code <phone>@whatsapp.progenie.local}; open http://localhost:8025 to read them. The automated tests read
 * OTP codes back through Mailpit's search API.
 */
@Component
class MailpitSender implements MessageSender {

    static final String SMS_DOMAIN = "sms.progenie.local";
    static final String WHATSAPP_DOMAIN = "whatsapp.progenie.local";

    private final RestClient http;
    private final MessagingProperties props;
    private final FileStorage storage;

    MailpitSender(MessagingProperties props, FileStorage storage) {
        this.http = ProviderHttp.client(props.mailpit().baseUrl());
        this.props = props;
        this.storage = storage;
    }

    @Override
    public String provider() {
        return "mailpit";
    }

    @Override
    public Set<Channel> channels() {
        return Set.of(Channel.SMS, Channel.WHATSAPP, Channel.EMAIL);
    }

    @Override
    public String send(QueuedMessage m) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("From", Map.of("Email", props.fromEmail(), "Name", props.fromName()));
        String name = m.params().getOrDefault("name", "");
        payload.put("Tags", List.of(m.channel().name().toLowerCase(), m.template().toLowerCase().replace('_', '-')));
        switch (m.channel()) {
            case EMAIL -> {
                payload.put("To", List.of(Map.of("Email", m.destination(), "Name", name)));
                payload.put("Subject", m.subject());
                payload.put("Text", m.body());
                payload.put("HTML", MessageRenderer.emailHtml(m.subject(), m.body(), m.params().get("link"),
                    m.params().get("linkLabel"), m.params().get("code")));
                if (m.attachmentKey() != null) {
                    List<Map<String, String>> attachments = new ArrayList<>();
                    attachments.add(Map.of(
                        "Content", ProviderHttp.base64(storage, m.attachmentKey()),
                        "Filename", m.attachmentName() == null ? "attachment" : m.attachmentName(),
                        "ContentType", ProviderHttp.contentType(m.attachmentName())));
                    payload.put("Attachments", attachments);
                }
            }
            case SMS, WHATSAPP -> {
                String digits = Phones.national(m.destination());
                if (digits == null) {
                    throw new DeliveryException("Not a mobile number", false);
                }
                String domain = m.channel() == Channel.SMS ? SMS_DOMAIN : WHATSAPP_DOMAIN;
                payload.put("To", List.of(Map.of("Email", digits + "@" + domain, "Name", name)));
                payload.put("Subject", (m.channel() == Channel.SMS ? "SMS" : "WhatsApp") + " · " + m.template());
                payload.put("Text", m.body());
            }
        }
        try {
            JsonNode res = http.post().uri("/api/v1/send")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);
            return res == null || res.get("ID") == null ? null : res.get("ID").asString();
        } catch (RuntimeException ex) {
            throw ProviderHttp.classify("Mailpit", ex);
        }
    }
}
