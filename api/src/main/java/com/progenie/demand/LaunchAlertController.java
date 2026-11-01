package com.progenie.demand;

import com.progenie.demand.LaunchAlerts.LaunchAlertDto;
import com.progenie.demand.LaunchAlerts.LaunchAlertRequest;
import com.progenie.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** "Notify me when ProGenie arrives" (Phase 3.5B): open to guests, rate limited. */
@RestController
public class LaunchAlertController {

    private final LaunchAlerts alerts;

    public LaunchAlertController(LaunchAlerts alerts) {
        this.alerts = alerts;
    }

    /** A PIN code we don't serve (or lat/lng outside every live city) and a phone or e-mail, with consent. */
    @PostMapping("/api/v1/launch-alerts")
    @ResponseStatus(HttpStatus.CREATED)
    public LaunchAlertDto signUp(@Valid @RequestBody LaunchAlertRequest req, HttpServletRequest http) {
        return alerts.signUp(CurrentUser.idOrNull(), DemandRecorder.who(http), req);
    }
}
