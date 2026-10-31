package com.progenie.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class RequestIdsTest {

    @Test
    void idsAreTwelveReadableCharactersAndDontRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String id = RequestIds.next();
            assertThat(id).matches("[0-9A-HJKMNP-TV-Z]{12}");   // no I, L, O, U
            seen.add(id);
        }
        assertThat(seen).hasSize(10_000);
    }

    @Test
    void eachJobRunGetsItsOwnIdAndTheThreadIsLeftAsItWas() {
        AtomicReference<String> during = new AtomicReference<>();
        MDC.remove(RequestIds.MDC_REQUEST);
        RequestIds.forJob(() -> during.set(RequestIds.current())).run();
        assertThat(during.get()).startsWith("job-").hasSize(16);
        assertThat(RequestIds.current()).isNull();

        MDC.put(RequestIds.MDC_REQUEST, "OUTER1234567");
        RequestIds.forJob(() -> { }).run();
        assertThat(RequestIds.current()).isEqualTo("OUTER1234567");
        MDC.remove(RequestIds.MDC_REQUEST);
    }
}
