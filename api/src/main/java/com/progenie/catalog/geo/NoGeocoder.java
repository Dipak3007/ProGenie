package com.progenie.catalog.geo;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** {@code progenie.geocoding.provider=none}: no outside service; only ProGenie's own PIN code list is used. */
@Component
@ConditionalOnProperty(name = "progenie.geocoding.provider", havingValue = "none")
class NoGeocoder implements Geocoder {

    @Override
    public String name() {
        return "none";
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Optional<Place> pincode(String pincode) {
        return Optional.empty();
    }

    @Override
    public Optional<Place> reverse(double lat, double lng) {
        return Optional.empty();
    }
}
