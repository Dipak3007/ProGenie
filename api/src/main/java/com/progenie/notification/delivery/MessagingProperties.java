package com.progenie.notification.delivery;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code progenie.messaging.*}: which provider sends each channel, and the retry schedule.
 * Locally everything goes to Mailpit; set the providers to {@code msg91} for live delivery.
 *
 * @param enabled          false stops the dispatcher (messages stay PENDING in the outbox)
 * @param smsProvider      mailpit | msg91
 * @param whatsappProvider mailpit | msg91
 * @param emailProvider    mailpit | msg91
 * @param retryDelays      wait before attempt 2, 3, 4, 5; after the last one the row becomes FAILED
 * @param webBaseUrl       used to build links in messages
 */
@ConfigurationProperties(prefix = "progenie.messaging")
public record MessagingProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("mailpit") String smsProvider,
    @DefaultValue("mailpit") String whatsappProvider,
    @DefaultValue("mailpit") String emailProvider,
    @DefaultValue("no-reply@progenie.in") String fromEmail,
    @DefaultValue("ProGenie") String fromName,
    @DefaultValue("20") int batchSize,
    @DefaultValue({"1m", "5m", "30m", "120m"}) List<Duration> retryDelays,
    @DefaultValue("2m") Duration sendLease,
    @DefaultValue("http://localhost:4200") String webBaseUrl,
    @DefaultValue Mailpit mailpit,
    @DefaultValue Msg91 msg91) {

    public String providerFor(Channel channel) {
        return switch (channel) {
            case SMS -> smsProvider;
            case WHATSAPP -> whatsappProvider;
            case EMAIL -> emailProvider;
        };
    }

    /** Mailpit's HTTP API (send and read). */
    public record Mailpit(@DefaultValue("http://localhost:8025") String baseUrl) {
    }

    /**
     * MSG91 account. Template ids are per message template name (e.g. {@code OTP}, {@code BOOKING_ACCEPTED}).
     *
     * @param webhookToken shared secret expected as {@code ?token=} on delivery-report webhooks
     */
    public record Msg91(String authKey,
                        @DefaultValue("https://control.msg91.com") String baseUrl,
                        @DefaultValue("https://api.msg91.com") String whatsappBaseUrl,
                        String senderId,
                        String whatsappNumber,
                        String emailDomain,
                        String webhookToken,
                        @DefaultValue Map<String, Msg91Template> templates) {
    }

    /**
     * Provider-side template for one message.
     *
     * @param smsTemplateId   DLT-approved Flow template id
     * @param whatsappName    Meta-approved WhatsApp template name
     * @param whatsappParams  our parameter names, in the order of the template's body placeholders
     * @param emailTemplateId MSG91 email template id
     */
    public record Msg91Template(String smsTemplateId, String whatsappName, List<String> whatsappParams,
                                String emailTemplateId) {
    }
}
