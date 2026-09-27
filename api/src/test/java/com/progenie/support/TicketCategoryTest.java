package com.progenie.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class TicketCategoryTest {

    @Test
    void safetyIsUrgentWithOneHourToFirstReply() {
        assertThat(TicketCategory.SAFETY.priority()).isEqualTo("URGENT");
        assertThat(TicketCategory.SAFETY.firstResponse).isEqualTo(Duration.ofHours(1));
        assertThat(TicketCategory.SAFETY.resolution).isEqualTo(Duration.ofHours(24));
        assertThat(TicketCategory.NO_SHOW.priority()).isEqualTo("HIGH");
        assertThat(TicketCategory.NO_SHOW.resolution).isEqualTo(Duration.ofHours(48));
        assertThat(TicketCategory.QUALITY.priority()).isEqualTo("NORMAL");
    }

    @Test
    void everyDeadlineGivesTimeToReplyBeforeResolving() {
        for (TicketCategory c : TicketCategory.values()) {
            assertThat(c.firstResponse).as(c.name()).isLessThan(c.resolution);
        }
    }
}
