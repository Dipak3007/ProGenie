package com.progenie.shared.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.ScheduledFuture;

import com.progenie.shared.web.RequestIds;
import org.springframework.boot.task.SimpleAsyncTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

/**
 * Background jobs (booking expiry, reminders, message dispatch, payouts, badges, retention) and a single injectable
 * {@link Clock}, so time-based rules can be tested with a fixed clock.
 *
 * <p>Every job run gets its own {@code job-…} ID in the logs ({@link RequestIds#forJob}), the way every HTTP request
 * gets a request ID. This is done by wrapping the scheduler itself rather than with a task decorator, because the
 * scheduler runs fixed-delay jobs directly on its own thread, where a decorator is never applied.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    /** Boot's scheduler (virtual threads, "scheduling-" thread names, graceful shutdown), configured as usual. */
    @Bean
    SimpleAsyncTaskScheduler jobSchedulerDelegate(SimpleAsyncTaskSchedulerBuilder builder) {
        return builder.build();
    }

    /** The scheduler @Scheduled jobs use (the bean named "taskScheduler" wins). */
    @Bean
    @Primary
    TaskScheduler taskScheduler(SimpleAsyncTaskScheduler jobSchedulerDelegate) {
        return new JobIdTaskScheduler(jobSchedulerDelegate);
    }

    @Bean
    Clock clock(AppProperties props) {
        return Clock.system(ZoneId.of(props.timezone()));
    }

    /** Gives each run of each scheduled task a fresh job ID. */
    static final class JobIdTaskScheduler implements TaskScheduler {

        private final TaskScheduler delegate;

        JobIdTaskScheduler(TaskScheduler delegate) {
            this.delegate = delegate;
        }

        @Override
        public Clock getClock() {
            return delegate.getClock();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return delegate.schedule(RequestIds.forJob(task), trigger);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return delegate.schedule(RequestIds.forJob(task), startTime);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return delegate.scheduleAtFixedRate(RequestIds.forJob(task), startTime, period);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return delegate.scheduleAtFixedRate(RequestIds.forJob(task), period);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return delegate.scheduleWithFixedDelay(RequestIds.forJob(task), startTime, delay);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return delegate.scheduleWithFixedDelay(RequestIds.forJob(task), delay);
        }
    }
}
