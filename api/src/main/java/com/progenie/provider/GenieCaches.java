package com.progenie.provider;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.progenie.catalog.CatalogCaches.CatalogChanged;
import com.progenie.provider.GenieDtos.GenieCardDto;
import com.progenie.provider.GenieDtos.GenieDetailDto;
import com.progenie.shared.cache.CacheSpec;
import com.progenie.shared.cache.ReadCacheProperties;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Read caches for the public Genie lists and profiles (Phase 2B-6). Short-lived ({@code progenie.cache.genie-ttl},
 * 2 minutes) and emptied whenever a Genie's card can change: profile, services, location, online switch, a finished
 * job, a new rating, an admin decision or flag ({@link EvictsGenies} on those methods), and catalog edits
 * (names, prices: {@link CatalogEvictions}). Whether a Genie can be booked is always checked against the database.
 */
@Configuration
public class GenieCaches {

    public static final String LIST = "genie-list";
    public static final String DETAIL = "genie-detail";

    /** Empties the Genie list and profile caches (after the commit). */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @CacheEvict(cacheNames = {LIST, DETAIL}, allEntries = true)
    @interface EvictsGenies {
    }

    @Bean
    CacheSpec genieListCache(ReadCacheProperties p) {
        return CacheSpec.listOf(LIST, p.genieTtl(), GenieCardDto.class);
    }

    @Bean
    CacheSpec genieDetailCache(ReadCacheProperties p) {
        return CacheSpec.of(DETAIL, p.genieTtl(), GenieDetailDto.class);
    }

    /** Category names and service prices are on the Genie cards. */
    @Component
    static class CatalogEvictions {

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
        @EvictsGenies
        public void on(CatalogChanged event) {
            // the annotation does the work
        }
    }
}
