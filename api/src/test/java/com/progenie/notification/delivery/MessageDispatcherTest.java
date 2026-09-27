package com.progenie.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MessageDispatcherTest {

    private final MessagingProperties props = new MessagingProperties(true, "mailpit", "mailpit", "msg91", "a@b.c", "PG", 20,
        List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofMinutes(120)),
        Duration.ofMinutes(2), "http://localhost:4200", new MessagingProperties.Mailpit("http://localhost:8025"),
        new MessagingProperties.Msg91(null, "https://control.msg91.com", "https://api.msg91.com", null, null, null, null,
            Map.of()));

    @Test
    void retriesAfter1_5_30_120MinutesThenGivesUp() {
        MessageDispatcher d = new MessageDispatcher(null, props, List.of());
        assertThat(d.retryDelay(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(d.retryDelay(2)).isEqualTo(Duration.ofMinutes(5));
        assertThat(d.retryDelay(3)).isEqualTo(Duration.ofMinutes(30));
        assertThat(d.retryDelay(4)).isEqualTo(Duration.ofMinutes(120));
        assertThat(d.retryDelay(5)).isNull();
    }

    @Test
    void providerIsChosenPerChannel() {
        assertThat(props.providerFor(Channel.SMS)).isEqualTo("mailpit");
        assertThat(props.providerFor(Channel.EMAIL)).isEqualTo("msg91");
    }
}
