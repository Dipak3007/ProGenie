package com.progenie.customer;

import java.util.List;
import java.util.UUID;

import com.progenie.customer.AddressDtos.AddressDto;
import com.progenie.customer.AddressDtos.AddressRequest;
import com.progenie.shared.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer address book. Written with SQL because the location is a PostGIS geography point.
 * The booking module reads an address through {@link #getOwned(UUID, UUID)} and snapshots it.
 */
@Service
public class AddressService {

    static final int MAX_ADDRESSES = 10;
    private static final long DEFAULT_CITY_ID = 1L; // Ahmedabad (trial city)

    private static final String SELECT = """
        SELECT a.id, a.label, a.line1, a.line2, a.landmark, a.area, a.city_id, c.name AS city_name, a.pincode,
               ST_Y(a.location::geometry) AS lat, ST_X(a.location::geometry) AS lng, a.is_default
          FROM addresses a
          JOIN cities c ON c.id = a.city_id
        """;

    private final JdbcClient jdbc;

    public AddressService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<AddressDto> list(UUID userId) {
        return jdbc.sql(SELECT + " WHERE a.user_id = :userId ORDER BY a.is_default DESC, a.created_at DESC")
            .param("userId", userId)
            .query(AddressDto.class)
            .list();
    }

    @Transactional(readOnly = true)
    public AddressDto getOwned(UUID userId, UUID addressId) {
        return jdbc.sql(SELECT + " WHERE a.user_id = :userId AND a.id = :id")
            .param("userId", userId)
            .param("id", addressId)
            .query(AddressDto.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("ADDRESS_NOT_FOUND", "Address not found"));
    }

    @Transactional
    public AddressDto create(UUID userId, AddressRequest req) {
        int count = jdbc.sql("SELECT count(*) FROM addresses WHERE user_id = :userId").param("userId", userId)
            .query(Integer.class).single();
        if (count >= MAX_ADDRESSES) {
            throw ApiException.unprocessable("ADDRESS_LIMIT", "You can save up to " + MAX_ADDRESSES + " addresses");
        }
        long cityId = requireActiveCity(req.cityId());
        boolean makeDefault = req.isDefault() || count == 0;
        if (makeDefault) {
            clearDefault(userId);
        }
        UUID id = jdbc.sql("""
                INSERT INTO addresses (user_id, label, line1, line2, landmark, area, city_id, pincode, location, is_default)
                VALUES (:userId, :label, :line1, :line2, :landmark, :area, :cityId, :pincode,
                        ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :isDefault)
                RETURNING id
                """)
            .param("userId", userId)
            .param("label", req.label().trim())
            .param("line1", req.line1().trim())
            .param("line2", req.line2())
            .param("landmark", req.landmark())
            .param("area", req.area())
            .param("cityId", cityId)
            .param("pincode", req.pincode())
            .param("lat", req.lat())
            .param("lng", req.lng())
            .param("isDefault", makeDefault)
            .query(UUID.class)
            .single();
        return getOwned(userId, id);
    }

    @Transactional
    public AddressDto update(UUID userId, UUID addressId, AddressRequest req) {
        AddressDto existing = getOwned(userId, addressId);
        long cityId = requireActiveCity(req.cityId());
        boolean makeDefault = req.isDefault() || existing.isDefault();
        if (makeDefault && !existing.isDefault()) {
            clearDefault(userId);
        }
        jdbc.sql("""
                UPDATE addresses
                   SET label = :label, line1 = :line1, line2 = :line2, landmark = :landmark, area = :area,
                       city_id = :cityId, pincode = :pincode,
                       location = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, is_default = :isDefault
                 WHERE id = :id AND user_id = :userId
                """)
            .param("label", req.label().trim())
            .param("line1", req.line1().trim())
            .param("line2", req.line2())
            .param("landmark", req.landmark())
            .param("area", req.area())
            .param("cityId", cityId)
            .param("pincode", req.pincode())
            .param("lat", req.lat())
            .param("lng", req.lng())
            .param("isDefault", makeDefault)
            .param("id", addressId)
            .param("userId", userId)
            .update();
        return getOwned(userId, addressId);
    }

    @Transactional
    public void delete(UUID userId, UUID addressId) {
        AddressDto existing = getOwned(userId, addressId);
        // bookings keep their own address snapshot, so deleting is safe (FK is ON DELETE SET NULL)
        jdbc.sql("DELETE FROM addresses WHERE id = :id AND user_id = :userId")
            .param("id", addressId).param("userId", userId).update();
        if (existing.isDefault()) {
            jdbc.sql("""
                    UPDATE addresses SET is_default = TRUE
                     WHERE id = (SELECT id FROM addresses WHERE user_id = :userId ORDER BY created_at DESC LIMIT 1)
                    """)
                .param("userId", userId).update();
        }
    }

    @Transactional
    public AddressDto makeDefault(UUID userId, UUID addressId) {
        getOwned(userId, addressId);
        clearDefault(userId);
        jdbc.sql("UPDATE addresses SET is_default = TRUE WHERE id = :id AND user_id = :userId")
            .param("id", addressId).param("userId", userId).update();
        return getOwned(userId, addressId);
    }

    private void clearDefault(UUID userId) {
        jdbc.sql("UPDATE addresses SET is_default = FALSE WHERE user_id = :userId AND is_default")
            .param("userId", userId).update();
    }

    private long requireActiveCity(Long cityId) {
        long id = cityId == null ? DEFAULT_CITY_ID : cityId;
        boolean active = jdbc.sql("SELECT EXISTS (SELECT 1 FROM cities WHERE id = :id AND is_active)")
            .param("id", id).query(Boolean.class).single();
        if (!active) {
            throw ApiException.unprocessable("CITY_NOT_SERVED", "We do not serve this city yet");
        }
        return id;
    }
}
