/**
 * Messaging module: how people are reached. Knows nothing about bookings or payments, so every other module may
 * use it without creating a cycle.
 * <ul>
 *   <li>{@link com.progenie.messaging.NotificationService}: the in-app feed (the bell)</li>
 *   <li>{@link com.progenie.messaging.LiveHub}: live updates over Server-Sent Events</li>
 *   <li>{@code delivery}: SMS / WhatsApp / e-mail through the transactional outbox, templates, preferences</li>
 * </ul>
 * Deciding <em>who</em> is told <em>what</em> after a business event is the notification module's job.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Messaging")
package com.progenie.messaging;
