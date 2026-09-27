package com.progenie.notification.delivery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.progenie.notification.delivery.MessageOutbox.QueuedMessage;
import com.progenie.notification.delivery.MessagingProperties.Msg91;
import com.progenie.notification.delivery.MessagingProperties.Msg91Template;
import com.progenie.shared.storage.FileStorage;
import com.progenie.shared.util.Phones;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Live adapter for India: MSG91 SMS (Flow API with DLT templates), WhatsApp (Meta-approved templates)
 * and email. Every message needs a provider-side template, configured per message under
 * {@code progenie.messaging.msg91.templates.<TEMPLATE>}; a message without one fails at once (no retries)
 * and shows up in the admin message list.
 *
 * <p>Payload shapes follow MSG91's v5 APIs. Check them against MSG91's current documentation when you
 * add your keys: they could not be tested here without an account.
 */
@Component
class Msg91Sender implements MessageSender {

    private final Msg91 cfg;
    private final MessagingProperties props;
    private final FileStorage storage;
    private final RestClient control;
    private final RestClient whatsapp;

    Msg91Sender(MessagingProperties props, FileStorage storage) {
        this.props = props;
        this.cfg = props.msg91();
        this.storage = storage;
        this.control = ProviderHttp.client(cfg.baseUrl());
        this.whatsapp = ProviderHttp.client(cfg.whatsappBaseUrl());
    }

    @Override
    public String provider() {
        return "msg91";
    }

    @Override
    public Set<Channel> channels() {
        return Set.of(Channel.SMS, Channel.WHATSAPP, Channel.EMAIL);
    }

    @Override
    public String send(QueuedMessage m) {
        if (!StringUtils.hasText(cfg.authKey())) {
            throw new DeliveryException("MSG91_AUTH_KEY is not set", false);
        }
        Msg91Template t = cfg.templates().get(m.template());
        try {
            return switch (m.channel()) {
                case SMS -> sms(m, t);
                case WHATSAPP -> whatsApp(m, t);
                case EMAIL -> email(m, t);
            };
        } catch (RuntimeException ex) {
            throw ProviderHttp.classify("MSG91", ex);
        }
    }

    /** POST /api/v5/flow: one recipient, template variables by name. */
    private String sms(QueuedMessage m, Msg91Template t) {
        if (t == null || !StringUtils.hasText(t.smsTemplateId())) {
            throw new DeliveryException("No MSG91 SMS (DLT) template configured for " + m.template(), false);
        }
        Map<String, Object> recipient = new LinkedHashMap<>(m.params());
        recipient.put("mobiles", mobile(m));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("template_id", t.smsTemplateId());
        body.put("short_url", "0");
        if (StringUtils.hasText(cfg.senderId())) {
            body.put("sender", cfg.senderId());
        }
        body.put("recipients", List.of(recipient));
        JsonNode res = control.post().uri("/api/v5/flow")
            .header("authkey", cfg.authKey())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(JsonNode.class);
        return requestId(res);
    }

    /** POST /api/v5/whatsapp/whatsapp-outbound-message/bulk/: a template message with ordered body parameters. */
    private String whatsApp(QueuedMessage m, Msg91Template t) {
        if (t == null || !StringUtils.hasText(t.whatsappName())) {
            throw new DeliveryException("No WhatsApp template configured for " + m.template(), false);
        }
        if (!StringUtils.hasText(cfg.whatsappNumber())) {
            throw new DeliveryException("MSG91_WHATSAPP_NUMBER is not set", false);
        }
        Map<String, Object> components = new LinkedHashMap<>();
        List<String> order = t.whatsappParams() == null ? List.of() : t.whatsappParams();
        for (int i = 0; i < order.size(); i++) {
            components.put("body_" + (i + 1), Map.of("type", "text", "value", m.params().getOrDefault(order.get(i), "")));
        }
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", t.whatsappName());
        template.put("language", Map.of("code", "en", "policy", "deterministic"));
        template.put("to_and_components", List.of(Map.of("to", List.of(mobile(m)), "components", components)));
        Map<String, Object> body = Map.of(
            "integrated_number", cfg.whatsappNumber(),
            "content_type", "template",
            "payload", Map.of("messaging_product", "whatsapp", "type", "template", "template", template));
        JsonNode res = whatsapp.post().uri("/api/v5/whatsapp/whatsapp-outbound-message/bulk/")
            .header("authkey", cfg.authKey())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(JsonNode.class);
        return requestId(res);
    }

    /** POST /api/v5/email/send: template email with variables and an optional attachment. */
    private String email(QueuedMessage m, Msg91Template t) {
        if (t == null || !StringUtils.hasText(t.emailTemplateId())) {
            throw new DeliveryException("No MSG91 email template configured for " + m.template(), false);
        }
        if (!StringUtils.hasText(cfg.emailDomain())) {
            throw new DeliveryException("MSG91_EMAIL_DOMAIN is not set", false);
        }
        Map<String, Object> variables = new LinkedHashMap<>(m.params());
        variables.put("subject", m.subject());
        variables.put("body", m.body());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipients", List.of(Map.of(
            "to", List.of(Map.of("email", m.destination(), "name", m.params().getOrDefault("name", ""))),
            "variables", variables)));
        body.put("from", Map.of("email", props.fromEmail(), "name", props.fromName()));
        body.put("domain", cfg.emailDomain());
        body.put("template_id", t.emailTemplateId());
        if (m.attachmentKey() != null) {
            String type = ProviderHttp.contentType(m.attachmentName());
            body.put("attachments", List.of(Map.of(
                "fileName", m.attachmentName() == null ? "attachment" : m.attachmentName(),
                "file", "data:" + type + ";base64," + ProviderHttp.base64(storage, m.attachmentKey()))));
        }
        JsonNode res = control.post().uri("/api/v5/email/send")
            .header("authkey", cfg.authKey())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(JsonNode.class);
        return requestId(res);
    }

    private static String mobile(QueuedMessage m) {
        String mobile = Phones.e164Digits(m.destination());
        if (mobile == null) {
            throw new DeliveryException("Not a mobile number", false);
        }
        return mobile;
    }

    /** MSG91 answers {"type":"success","message":"<request id>"} or {"type":"error","message":"..."}. */
    private static String requestId(JsonNode res) {
        if (res == null) {
            return null;
        }
        String type = res.path("type").asString("");
        String status = res.path("status").asString("");
        if ("error".equalsIgnoreCase(type) || "fail".equalsIgnoreCase(status)) {
            throw new DeliveryException("MSG91 rejected the message: " + res, false);
        }
        for (String field : List.of("request_id", "requestId", "message")) {
            JsonNode v = res.path(field);
            if (!v.isMissingNode() && v.isValueNode()) {
                return v.asString();
            }
        }
        JsonNode data = res.path("data");
        if (!data.path("unique_id").isMissingNode()) {
            return data.path("unique_id").asString();
        }
        return null;
    }
}
