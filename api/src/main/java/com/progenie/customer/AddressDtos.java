package com.progenie.customer;

import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Saved customer addresses. The map pin (lat/lng) is required: it drives the travel fee. */
public final class AddressDtos {

    private AddressDtos() {
    }

    public record AddressDto(UUID id, String label, String line1, String line2, String landmark, String area,
                             long cityId, String cityName, String pincode, double lat, double lng, boolean isDefault) {
    }

    public record AddressRequest(
        @NotBlank @Size(max = 40) String label,
        @NotBlank @Size(max = 200) String line1,
        @Size(max = 200) String line2,
        @Size(max = 120) String landmark,
        @Size(max = 80) String area,
        Long cityId,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "must be a 6-digit PIN code") String pincode,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
        boolean isDefault) {
    }
}
