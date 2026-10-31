package com.progenie.catalog;

import java.math.BigDecimal;
import java.util.List;

import org.jspecify.annotations.Nullable;

/** Read models returned by the catalog endpoints. */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    /** A live city. lat/lng is the centre the map opens on; radiusKm is how far from it ProGenie serves. */
    public record CityDto(long id, String slug, String name, String state, @Nullable Double lat, @Nullable Double lng,
                          BigDecimal radiusKm) {
    }

    /** A serviceable PIN code of a city, e.g. 380009 Navrangpura. lat/lng is its rough centre (may be null). */
    public record AreaDto(String pincode, @Nullable String area, @Nullable Double lat, @Nullable Double lng) {
    }

    /** Answer to "do you serve PIN code X?". The city fields are null when it is not served. */
    public record PincodeCheckDto(String pincode, boolean serviceable, @Nullable Long cityId, @Nullable String citySlug, @Nullable String cityName,
                                  @Nullable String cityState, @Nullable String area, @Nullable Double lat, @Nullable Double lng) {

        static PincodeCheckDto notServed(String pincode) {
            return new PincodeCheckDto(pincode, false, null, null, null, null, null, null, null);
        }
    }

    /** Answer to "is this map pin usable?": inside India, and within a live city's service radius. */
    public record LocationCheckDto(double lat, double lng, boolean inIndia, boolean serviceable, @Nullable CityDto city) {
    }

    /** Where an answer about a PIN code or point came from. */
    public enum GeoSource {
        /** ProGenie's own list of served PIN codes (their centres) */
        SERVED,
        /** the map data provider (OpenStreetMap by default) */
        GEOCODER,
        /** no provider answer: the nearest PIN code ProGenie serves */
        NEAREST
    }

    /** Where a PIN code is (its rough centre). {@code found} false: nobody knows; lat/lng and source are then null. */
    public record PincodeLocationDto(String pincode, boolean found, @Nullable Double lat, @Nullable Double lng,
                                     @Nullable String area, @Nullable GeoSource source) {
    }

    /** The PIN code at a map point. {@code pincode} null: unknown there (the user types it), or outside India. */
    public record PincodeAtDto(double lat, double lng, boolean inIndia, @Nullable String pincode, @Nullable String area,
                               @Nullable GeoSource source) {
    }

    public record CategoryDto(long id, String slug, String name, @Nullable String description, @Nullable String icon,
                              @Nullable String imageUrl, int serviceCount, @Nullable BigDecimal startingPrice) {
    }

    /** {@code instantEnabled}: can be booked with Genie Now (nearest free Genie, Phase 3C-9). */
    public record ServiceDto(long id, String slug, String name, @Nullable String description,
                             BigDecimal basePrice, int durationMinutes, boolean instantEnabled) {
    }

    public record CategoryDetailDto(CategoryDto category, List<ServiceDto> services) {
    }

    /** One search hit: a service and the category it belongs to. */
    public record SearchHitDto(long serviceId, String serviceSlug, String serviceName, String categorySlug,
                               String categoryName, BigDecimal basePrice, int durationMinutes) {
    }
}
