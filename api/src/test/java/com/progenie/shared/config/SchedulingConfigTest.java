package com.progenie.shared.config;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.progenie.shared.web.RequestIds;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    @Test
    void everyRunOfAFixedDelayJobGetsItsOwnJobId() {
        SimpleAsyncTaskScheduler delegate = new SimpleAsyncTaskScheduler();
        try {
            List<String> ids = new CopyOnWriteArrayList<>();
            ScheduledFuture<?> job = new SchedulingConfig.JobIdTaskScheduler(delegate)
                .scheduleWithFixedDelay(() -> ids.add(String.valueOf(MDC.get(RequestIds.MDC_REQUEST))), Duration.ofMillis(20));
            Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> ids.size() >= 3);
            job.cancel(true);
            assertThat(ids).allMatch(id -> id.matches("job-[0-9A-Z]{12}"));
            assertThat(ids.stream().distinct().count()).isEqualTo(ids.size());
        } finally {
            delegate.close();
        }
    }
}
