package com.progenie.shared.cache;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code progenie.cache.*}: the read cache in front of the catalog and the Genie lists.
 *
 * @param type        memory (Caffeine, per API instance; the default) | redis (shared by every instance; set
 *                    {@code REDIS_URL}) | none (always read the database)
 * @param catalogTtl  cities, categories, services, served PIN code areas: changed only by admins (and evicted then)
 * @param genieTtl    Genie cards and profiles: ratings, jobs done and "online" change all the time, so keep it short
 * @param maxEntries  per cache, memory mode
 */
@ConfigurationProperties(prefix = "progenie.cache")
public record ReadCacheProperties(
    @DefaultValue("memory") String type,
    @DefaultValue("1h") Duration catalogTtl,
    @DefaultValue("2m") Duration genieTtl,
    @DefaultValue("2000") int maxEntries) {

    public boolean isRedis() {
        return "redis".equalsIgnoreCase(type);
    }
}
