package com.progenie.shared.cache;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Small in-memory LRU cache with a time limit per entry, for answers from outside services (geocoding, road
 * routes) so repeat lookups never reach the provider. Per API instance; a few thousand entries are well under a
 * megabyte. (Shared read caches for API responses use Spring Cache instead: see {@code CacheConfig}.)
 */
public final class TtlCache<V> {

    private record Slot<V>(V value, long expiresAtNanos) {
    }

    private final Map<String, Slot<V>> entries;

    public TtlCache(int maxEntries) {
        this.entries = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Slot<V>> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /** The cached value, or null when there is none (or it expired). */
    public synchronized @Nullable V get(String key) {
        Slot<V> e = entries.get(key);
        if (e == null) {
            return null;
        }
        if (System.nanoTime() - e.expiresAtNanos() > 0) {
            entries.remove(key);
            return null;
        }
        return e.value();
    }

    public synchronized void put(String key, V value, Duration ttl) {
        entries.put(key, new Slot<>(value, System.nanoTime() + ttl.toNanos()));
    }

    public synchronized int size() {
        return entries.size();
    }
}
