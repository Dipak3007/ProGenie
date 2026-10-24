package com.progenie.customer;

import java.util.List;
import java.util.UUID;

import com.progenie.customer.AddressDtos.AddressDto;
import com.progenie.customer.AddressDtos.AddressRequest;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
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

    private final JdbcClient jdbc;

    public AddressService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<AddressDto> list(UUID userId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_ADDRESS_LIST))
            .param("userId", userId)
            .query(AddressDto.class)
            .list();
    }

    @Transactional(readOnly = true)
    public AddressDto getOwned(UUID userId, UUID addressId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_ADDRESS_OWNED))
            .param("userId", userId)
            .param("id", addressId)
            .query(AddressDto.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("ADDRESS_NOT_FOUND", "Address not found"));
    }

    @Transactional
    public AddressDto create(UUID userId, AddressRequest req) {
        int count = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_ADDRESS_BY_USER)).param("userId", userId)
            .query(Integer.class).single();
        if (count >= MAX_ADDRESSES) {
            throw ApiException.unprocessable("ADDRESS_LIMIT", "You can save up to " + MAX_ADDRESSES + " addresses");
        }
        long cityId = requireActiveCity(req.cityId());
        boolean makeDefault = req.isDefault() || count == 0;
        if (makeDefault) {
            clearDefault(userId);
        }
        UUID id = jdbc.sql(QueryUtil.get(QueryConstants.INSERT_ADDRESS_CREATE))
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
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_ADDRESS_DETAILS))
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
        jdbc.sql(QueryUtil.get(QueryConstants.DELETE_ADDRESS_OWNED))
            .param("id", addressId).param("userId", userId).update();
        if (existing.isDefault()) {
            jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_ADDRESS_PROMOTE_OLDEST_DEFAULT))
                .param("userId", userId).update();
        }
    }

    @Transactional
    public AddressDto makeDefault(UUID userId, UUID addressId) {
        getOwned(userId, addressId);
        clearDefault(userId);
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_ADDRESS_SET_DEFAULT))
            .param("id", addressId).param("userId", userId).update();
        return getOwned(userId, addressId);
    }

    private void clearDefault(UUID userId) {
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_ADDRESS_CLEAR_DEFAULT))
            .param("userId", userId).update();
    }

    private long requireActiveCity(Long cityId) {
        long id = cityId == null ? DEFAULT_CITY_ID : cityId;
        boolean active = jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_ADDRESS_ACTIVE_CITY))
            .param("id", id).query(Boolean.class).single();
        if (!active) {
            throw ApiException.unprocessable("CITY_NOT_SERVED", "We do not serve this city yet");
        }
        return id;
    }
}
