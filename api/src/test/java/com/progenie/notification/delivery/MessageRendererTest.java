package com.progenie.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class MessageRendererTest {

    @Test
    void fillsPlaceholdersAndLeavesMissingOnesEmpty() {
        var r = MessageRenderer.render(MessageTemplate.OTP, Channel.SMS,
            Map.of("code", "123456", "purpose", "log in", "minutes", "5"));
        assertThat(r.body()).isEqualTo("123456 is your ProGenie code to log in. It expires in 5 minutes. Never share it with anyone.");
        assertThat(r.subject()).isNull();
        assertThat(MessageRenderer.fill("Hi {name}{missing}!", Map.of("name", "Asha"))).isEqualTo("Hi Asha!");
    }

    @Test
    void emailHasASubjectAndSmsAvoidsTheRupeeSign() {
        var email = MessageRenderer.render(MessageTemplate.PAYMENT_RECEIPT, Channel.EMAIL,
            Map.of("name", "Asha", "amount", "499.00", "ref", "PG-2026-000001", "number", "PG/2026-27/000001",
                "service", "Fan repair", "method", "Cash"));
        assertThat(email.subject()).isEqualTo("Receipt PG/2026-27/000001 for PG-2026-000001");
        assertThat(email.body()).contains("₹499.00").contains("Fan repair");
        for (MessageTemplate t : MessageTemplate.values()) {
            assertThat(t.text(Channel.SMS)).as(t.name()).doesNotContain("₹");
        }
    }

    @Test
    void smsIsCappedAndValuesCanContainRegexCharacters() {
        var r = MessageRenderer.render(MessageTemplate.GENIE_VERIFICATION, Channel.SMS,
            Map.of("headline", "$1 \\ done", "detail", "x".repeat(600)));
        assertThat(r.body()).startsWith("ProGenie: $1 \\ done").hasSizeLessThanOrEqualTo(480);
    }

    @Test
    void htmlEscapesTextAndHighlightsTheCode() {
        String html = MessageRenderer.emailHtml("Hi <b>", "Hello <script>\n\n123456", "https://x.test/a?b=1&c=2", "Open", "123456");
        assertThat(html).contains("Hello &lt;script&gt;").doesNotContain("<script>")
            .contains("letter-spacing:8px").contains("href=\"https://x.test/a?b=1&amp;c=2\"");
    }

    @Test
    void everyTemplateHasTextForEveryChannel() {
        for (MessageTemplate t : MessageTemplate.values()) {
            for (Channel c : Channel.values()) {
                assertThat(t.text(c)).as(t + "/" + c).isNotBlank();
            }
            assertThat(t.emailSubject()).isNotBlank();
        }
        assertThat(MessageTemplate.OTP.sensitive()).isTrue();
        assertThat(MessageTemplate.BOOKING_REQUEST_GENIE.mandatory()).isTrue();
        assertThat(MessageTemplate.BOOKING_ACCEPTED.mandatory()).isFalse();
    }
}
