package com.progenie.shared.cache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.health.DataRedisHealthIndicator;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read cache (Phase 2B-6). Modules declare their caches as {@link CacheSpec} beans and use {@code @Cacheable} /
 * {@code @CacheEvict}; this class builds the matching {@link CacheManager}:
 * <ul>
 *   <li>{@code memory} (default): Caffeine in this JVM, one size- and time-bounded cache per spec;</li>
 *   <li>{@code redis}: Redis (Upstash, Docker), shared by all API instances, values as typed JSON under
 *       {@code progenie:<cache>::<key>};</li>
 *   <li>{@code none}: no caching.</li>
 * </ul>
 * The manager is transaction-aware: an eviction inside a transaction happens after the commit, so a reader can't
 * put the old row back into the cache while the change is still uncommitted.
 *
 * <p>Redis writes are immediate: Spring Data Redis 4 otherwise sends puts and clears in the background, so the HTTP
 * response to an admin edit could go out before the old entries are gone (the next read then showed the old name),
 * and a late put could even land after an eviction and keep a stale value for the whole TTL.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public CacheManager cacheManager(ReadCacheProperties props, List<CacheSpec> specs,
                                     ObjectProvider<RedisConnectionFactory> redis) {
        CacheManager target = switch (props.type().toLowerCase(Locale.ROOT)) {
            case "none" -> new NoOpCacheManager();
            case "redis" -> redis(specs, redis.getObject());
            default -> memory(specs, props.maxEntries());
        };
        log.info("Read cache: {} ({} caches: {})", props.type(), specs.size(), specs.stream().map(CacheSpec::name).toList());
        return new TransactionAwareCacheManagerProxy(target);
    }

    /** Boot's own Redis health check is off (management.health.redis.enabled=false); it only matters in redis mode. */
    @Bean
    @ConditionalOnProperty(name = "progenie.cache.type", havingValue = "redis")
    public DataRedisHealthIndicator redisHealthIndicator(RedisConnectionFactory redis) {
        return new DataRedisHealthIndicator(redis);
    }

    private static CacheManager memory(List<CacheSpec> specs, int maxEntries) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAllowNullValues(false);
        manager.setCacheNames(List.of());                 // fixed set: an unknown cache name is an error, not a new cache
        for (CacheSpec spec : specs) {
            manager.registerCustomCache(spec.name(),
                Caffeine.newBuilder().expireAfterWrite(spec.ttl()).maximumSize(maxEntries).build());
        }
        return manager;
    }

    private static CacheManager redis(List<CacheSpec> specs, RedisConnectionFactory connections) {
        JsonMapper json = JsonMapper.builder().build();
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
            .prefixCacheNameWith("progenie:")
            .disableCachingNullValues()
            .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()));
        Map<String, RedisCacheConfiguration> perCache = new LinkedHashMap<>();
        for (CacheSpec spec : specs) {
            perCache.put(spec.name(), defaults.entryTtl(spec.ttl())
                .serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(json, spec.valueType()))));
        }
        // immediateWrites: SET / UNLINK finish before the call returns. SCAN instead of KEYS when clearing a cache,
        // so a big Redis isn't blocked.
        RedisCacheWriter writer = RedisCacheWriter.create(connections,
            w -> w.immediateWrites().batchStrategy(BatchStrategies.scan(200)));
        RedisCacheManager manager = RedisCacheManager.RedisCacheManagerBuilder.fromCacheWriter(writer)
            .cacheDefaults(defaults)
            .withInitialCacheConfigurations(perCache)
            .disableCreateOnMissingCache()
            .build();
        manager.afterPropertiesSet();
        return manager;
    }
}
