package com.progenie.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class Msg91WebhookControllerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void readsListsOfReportsWithNestedStatuses() {
        var reports = Msg91WebhookController.reports(json.readTree("""
            [{"requestId":"r1","report":[{"desc":"DELIVERED","number":"919825000005"}]},
             {"request_id":"r2","status":"FAILED"}, {"noid":true}]"""));
        assertThat(reports).hasSize(2);
        assertThat(reports.get(0)).containsExactly("r1", "DELIVERED");
        assertThat(reports.get(1)).containsExactly("r2", "FAILED");
    }
}
