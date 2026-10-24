package com.progenie.booking.domain;

import java.util.Optional;
import java.util.UUID;

import com.progenie.shared.config.QueryConstants;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /** Row lock for state changes, so two actors (e.g. customer cancels while Genie accepts) cannot race. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(name = QueryConstants.LOCK_BOOKING_ENTITY)
    Optional<Booking> findForUpdate(@Param("id") UUID id);

    Optional<Booking> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);
}
