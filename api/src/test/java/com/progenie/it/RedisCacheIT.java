package com.progenie.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The read cache and rate limits in Redis ({@code progenie.cache.type=redis}): the same checks as
 * {@link ReadCacheIT}, plus the data really is in Redis. Redis comes from Testcontainers, or from
 * {@code PROGENIE_IT_REDIS_URL} (e.g. redis://localhost:6379) when set.
 */
class RedisCacheIT extends ReadCacheIT {

    private static final String EXTERNAL = System.getenv("PROGENIE_IT_REDIS_URL");
    private static final GenericContainer<?> REDIS;

    static {
        if (EXTERNAL == null || EXTERNAL.isBlank()) {
            REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
            REDIS.start();
        } else {
            REDIS = null;
        }
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("progenie.cache.type", () -> "redis");
        registry.add("spring.data.redis.url", () -> REDIS == null ? EXTERNAL
            : "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Autowired
    StringRedisTemplate redis;

    @Test
    void cachedReadsAndRateLimitsLiveInRedis() {
        ApiClient anon = new ApiClient(port);
        anon.get("/api/v1/categories");
        anon.get("/api/v1/cities");
        assertThat(redis.keys("progenie:catalog-categories::*")).contains("progenie:catalog-categories::all");
        assertThat(redis.opsForValue().get("progenie:catalog-cities::all")).as("typed JSON, no class names")
            .contains("\"slug\":\"ahmedabad\"").doesNotContain("@class");
        assertThat(redis.getExpire("progenie:catalog-cities::all")).isPositive();

        anon.post("/api/v1/auth/login", "{\"identifier\":\"customer@example.com\",\"password\":\"wrong-password\"}");
        assertThat(redis.keys("progenie:rl:login-fail:*")).as("failed logins are counted in Redis").isNotEmpty();
        new ApiClient(port).login("customer@example.com");                 // a good login resets the counter
        assertThat(redis.keys("progenie:rl:login-fail:*")).isEmpty();
    }
}
