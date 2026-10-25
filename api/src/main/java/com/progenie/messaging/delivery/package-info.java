/**
 * Outbox-based delivery of SMS, WhatsApp and e-mail (Mailpit locally, MSG91 in production), templates and
 * per-user preferences. Exposed to other modules (Messenger, MessageTemplate, Channel, NotificationPreferences).
 */
@org.springframework.modulith.NamedInterface("delivery")
package com.progenie.messaging.delivery;
