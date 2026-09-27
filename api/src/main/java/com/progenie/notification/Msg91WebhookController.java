package com.progenie.notification;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.progenie.notification.delivery.MessageAdmin;
import com.progenie.notification.delivery.MessagingProperties;
import com.progenie.shared.error.ApiException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MSG91 delivery reports (DLR). MSG91 doesn't sign these callbacks, so the URL carries a shared secret:
 * configure {@code https://<api>/api/v1/webhooks/msg91/delivery?token=<MSG91_WEBHOOK_TOKEN>} in MSG91.
 * Accepts a JSON body or a form field {@code data} holding JSON, as a single report or a list.
 */
@RestController
@RequestMapping("/api/v1/webhooks/msg91")
public class Msg91WebhookController {

    private final MessageAdmin messages;
    private final MessagingProperties props;
    private final JsonMapper json;

    public Msg91WebhookController(MessageAdmin messages, MessagingProperties props, JsonMapper json) {
        this.messages = messages;
        this.props = props;
        this.json = json;
    }

    @PostMapping("/delivery")
    public Map<String, Integer> delivery(@RequestParam(required = false) String token,
                                         @RequestHeader(value = "Content-Type", required = false) String contentType,
                                         @RequestBody(required = false) String body) {
        String expected = props.msg91().webhookToken();
        if (!StringUtils.hasText(expected) || token == null
            || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.forbidden("BAD_WEBHOOK_TOKEN", "Unknown webhook");
        }
        if (!StringUtils.hasText(body)) {
            return Map.of("updated", 0);
        }
        String raw = body;
        if (contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
            raw = formField(body, "data");
        }
        int updated = 0;
        for (String[] report : reports(json.readTree(raw))) {
            if (messages.applyDeliveryReport("msg91", report[0], report[1])) {
                updated++;
            }
        }
        return Map.of("updated", updated);
    }

    /** Pairs of (request id, status) found in the payload. */
    static List<String[]> reports(JsonNode node) {
        List<String[]> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        if (node.isArray()) {
            node.forEach(n -> out.addAll(reports(n)));
            return out;
        }
        String id = first(node, "requestId", "request_id", "uniqueId", "unique_id", "messageId");
        if (id == null) {
            return out;
        }
        JsonNode report = node.path("report");
        if (report.isArray() && !report.isEmpty()) {
            report.forEach(r -> out.add(new String[] {id, first(r, "desc", "status")}));
        } else {
            out.add(new String[] {id, first(node, "status", "desc", "event")});
        }
        return out;
    }

    private static String first(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.path(f);
            if (!v.isMissingNode() && !v.isNull() && v.isValueNode()) {
                return v.asString();
            }
        }
        return null;
    }

    private static String formField(String form, String name) {
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "[]";
    }
}
