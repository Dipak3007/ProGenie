package com.progenie.notification;

import java.util.List;

import com.progenie.notification.delivery.NotificationPreferences;
import com.progenie.notification.delivery.NotificationPreferences.PreferenceDto;
import com.progenie.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SMS / WhatsApp / email switches per category for the logged-in user (any role).
 * Security codes, account notices and a Genie's new-request alert are always sent.
 */
@RestController
@RequestMapping("/api/v1/me/notification-preferences")
public class NotificationPreferenceController {

    private final NotificationPreferences preferences;

    public NotificationPreferenceController(NotificationPreferences preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    public List<PreferenceDto> get() {
        return preferences.list(CurrentUser.id());
    }

    @PutMapping
    public List<PreferenceDto> update(@RequestBody List<PreferenceDto> changes) {
        return preferences.update(CurrentUser.id(), changes);
    }
}
