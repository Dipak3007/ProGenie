package com.progenie.shared.cache;

import java.time.Duration;
import java.util.List;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * One read cache, declared as a bean by the module that owns the data (catalog, provider): its name, how long an
 * entry lives, and the Java type of its values (Redis stores them as JSON of exactly this type, so there is no
 * class name in the data and no polymorphic deserialization).
 */
public record CacheSpec(String name, Duration ttl, JavaType valueType) {

    private static final JsonMapper TYPES = JsonMapper.builder().build();

    public static CacheSpec of(String name, Duration ttl, Class<?> type) {
        return new CacheSpec(name, ttl, TYPES.constructType(type));
    }

    public static CacheSpec listOf(String name, Duration ttl, Class<?> elementType) {
        return new CacheSpec(name, ttl, TYPES.getTypeFactory().constructCollectionType(List.class, elementType));
    }
}
