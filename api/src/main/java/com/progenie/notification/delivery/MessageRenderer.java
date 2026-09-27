package com.progenie.notification.delivery;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.web.util.HtmlUtils;

/** Fills a template's placeholders and wraps email text in a simple branded HTML layout. */
public final class MessageRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final int SMS_MAX = 480;          // 3 SMS parts; DLT templates are shorter anyway

    private MessageRenderer() {
    }

    public record Rendered(String subject, String body) {
    }

    public static Rendered render(MessageTemplate template, Channel channel, Map<String, String> params) {
        String body = fill(template.text(channel), params).replaceAll("[ \\t]+\\n", "\n").strip();
        body = body.replaceAll(" {2,}", " ");
        if (channel == Channel.SMS && body.length() > SMS_MAX) {
            body = body.substring(0, SMS_MAX - 1) + "…";
        }
        String subject = channel == Channel.EMAIL ? fill(template.emailSubject(), params).strip() : null;
        return new Rendered(subject, body);
    }

    static String fill(String text, Map<String, String> params) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = params.getOrDefault(m.group(1), "");
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Plain-text email body → HTML: paragraphs, an emphasised one-time code, and a button when a link is given.
     */
    public static String emailHtml(String subject, String body, String link, String linkLabel, String code) {
        StringBuilder paragraphs = new StringBuilder();
        for (String para : body.split("\\n\\s*\\n")) {
            String p = para.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (code != null && p.equals(code)) {
                paragraphs.append("<p style=\"margin:24px 0;font:700 32px/1 ui-monospace,Menlo,Consolas,monospace;")
                    .append("letter-spacing:8px;color:#131A2E\">").append(HtmlUtils.htmlEscape(p)).append("</p>");
            } else {
                paragraphs.append("<p style=\"margin:0 0 16px\">")
                    .append(HtmlUtils.htmlEscape(p).replace("\n", "<br>")).append("</p>");
            }
        }
        String button = link == null || link.isBlank() ? "" : """
            <p style="margin:24px 0 8px"><a href="%s" style="display:inline-block;background:#4F46E5;color:#fff;\
            text-decoration:none;font-weight:600;padding:12px 20px;border-radius:10px">%s</a></p>"""
            .formatted(HtmlUtils.htmlEscape(link), HtmlUtils.htmlEscape(linkLabel == null ? "Open ProGenie" : linkLabel));
        return """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <title>%s</title></head>
            <body style="margin:0;background:#F4F5FB;font:15px/1.6 -apple-system,Segoe UI,Roboto,Arial,sans-serif;color:#1F2433">
            <div style="max-width:560px;margin:0 auto;padding:24px 16px">
              <div style="background:#131A2E;border-radius:14px 14px 0 0;padding:18px 24px;color:#fff;font-size:20px">
                <span style="font-weight:500">Pro</span><span style="font-weight:800;color:#F5B301">Genie</span>
              </div>
              <div style="background:#fff;border-radius:0 0 14px 14px;padding:24px">%s%s</div>
              <p style="color:#6B7280;font-size:12px;margin:16px 4px">You're receiving this because you have a ProGenie
              account. Manage messages in Profile → Notifications.</p>
            </div></body></html>"""
            .formatted(HtmlUtils.htmlEscape(subject == null ? "ProGenie" : subject), paragraphs, button);
    }
}
