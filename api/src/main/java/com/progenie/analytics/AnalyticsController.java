package com.progenie.analytics;

import java.time.LocalDate;

import com.progenie.analytics.AnalyticsService.GenieStatsDto;
import com.progenie.analytics.AnalyticsService.OverviewDto;
import com.progenie.shared.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboards: platform overview (admin) and a Genie's own performance. */
@RestController
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    /** Defaults to the last 30 days. Dates are inclusive, in the business time zone. */
    @GetMapping("/api/v1/admin/analytics/overview")
    public OverviewDto overview(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analytics.overview(from, to);
    }

    @GetMapping("/api/v1/genie/analytics")
    public GenieStatsDto mine(@RequestParam(defaultValue = "30") int days) {
        return analytics.genieStats(CurrentUser.id(), days);
    }
}
