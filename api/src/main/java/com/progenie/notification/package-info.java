/**
 * Notification module: turns business events (bookings, payments, reviews, complaints, Genie verification)
 * into messages after their transaction commits: in-app notifications, SMS / WhatsApp / e-mail through the
 * messaging module, and live-update pushes. It depends on the modules whose events it listens to; nothing
 * depends on it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Notifications")
package com.progenie.notification;
