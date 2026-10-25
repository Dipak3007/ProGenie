package com.progenie.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class TtlCacheTest {

    @Test
    void keepsTheMostRecentlyUsedEntriesAndDropsExpiredOnes() throws InterruptedException {
        TtlCache<String> cache = new TtlCache<>(2);
        cache.put("a", "A", Duration.ofMinutes(1));
        cache.put("b", "B", Duration.ofMinutes(1));
        assertThat(cache.get("a")).isEqualTo("A");          // a is now the most recent
        cache.put("c", "C", Duration.ofMinutes(1));          // evicts b
        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("a")).isEqualTo("A");
        assertThat(cache.size()).isEqualTo(2);

        cache.put("d", "D", Duration.ofMillis(20));
        Thread.sleep(40);
        assertThat(cache.get("d")).isNull();
    }
}
