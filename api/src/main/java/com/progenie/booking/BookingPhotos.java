package com.progenie.booking;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.progenie.booking.BookingDtos.BookingPhotoDto;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.storage.FileStorage;
import com.progenie.shared.util.FileTypes;
import com.progenie.shared.util.QueryUtil;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Before/after job photos (Phase 3A-8). Optional: the Genie adds up to 3 when starting the job (BEFORE) and up to 3
 * when finishing it (AFTER). The browser shrinks them to about 1600 px before upload; the API checks the real file
 * type from the bytes. Seen by the customer, the Genie and admins (booking detail, complaints); never public.
 *
 * <ul>
 *   <li>BEFORE photos: while the job is in progress</li>
 *   <li>AFTER photos: while the job is in progress, or up to 24 hours after it was completed</li>
 *   <li>A photo can be removed by the Genie within the same window</li>
 *   <li>Deleted 1 year after the job unless a complaint is still open ({@link BookingPhotoRetentionJob}), and at
 *       once when the customer's account is deleted</li>
 * </ul>
 */
@Service
public class BookingPhotos {

    private static final Logger log = LoggerFactory.getLogger(BookingPhotos.class);

    public static final int MAX_PER_STAGE = 3;
    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final Duration AFTER_WINDOW = Duration.ofHours(24);

    public enum Stage { BEFORE, AFTER }

    record Context(UUID id, String bookingRef, UUID customerId, UUID genieId, String status,
                   @Nullable OffsetDateTime completedAt) {
    }

    record Row(UUID id, String stage, String objectKey, String contentType, int sizeBytes, OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final Clock clock;

    public BookingPhotos(JdbcClient jdbc, FileStorage storage, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.clock = clock;
    }

    /** The Genie adds a photo to their own job. */
    @Transactional
    public BookingPhotoDto add(UUID bookingId, UUID genieId, Stage stage, MultipartFile file) {
        Context b = lockOwn(bookingId, genieId);
        if (!canChange(stage, b.status(), b.completedAt(), clock.instant())) {
            throw ApiException.unprocessable("PHOTOS_CLOSED", stage == Stage.BEFORE
                ? "Before photos can be added while the job is in progress"
                : "After photos can be added until 24 hours after the job is completed");
        }
        int count = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_BOOKING_PHOTOS_STAGE))
            .param("b", bookingId).param("stage", stage.name()).query(Integer.class).single();
        if (count >= MAX_PER_STAGE) {
            throw ApiException.unprocessable("TOO_MANY_PHOTOS",
                "At most " + MAX_PER_STAGE + " " + stage.name().toLowerCase() + " photos per job");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_REQUIRED", "Choose a photo");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("FILE_TOO_LARGE", "Photos can be at most 5 MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String type = FileTypes.sniffImage(bytes);
        if (type == null) {
            throw ApiException.badRequest("UNSUPPORTED_FILE", "Only JPEG, PNG or WEBP photos are accepted");
        }
        UUID id = UUID.randomUUID();
        String key = "bookings/" + bookingId + "/" + id + "." + FileTypes.extension(type);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, type);
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_BOOKING_PHOTO))
            .param("id", id).param("b", bookingId).param("stage", stage.name()).param("k", key)
            .param("ct", type).param("size", bytes.length).param("u", genieId)
            .update();
        log.info("Booking {}: {} photo added ({}, {} bytes)", b.bookingRef(), stage, type, bytes.length);
        return list(bookingId).stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    /** The Genie removes one of their photos, within the window in which it could be added. */
    @Transactional
    public void delete(UUID bookingId, UUID photoId, UUID genieId) {
        Context b = lockOwn(bookingId, genieId);
        Row photo = jdbc.sql(QueryUtil.get(QueryConstants.GET_BOOKING_PHOTO))
            .param("id", photoId).param("b", bookingId).query(Row.class).optional()
            .orElseThrow(() -> ApiException.notFound("PHOTO_NOT_FOUND", "Photo not found"));
        if (!canChange(Stage.valueOf(photo.stage()), b.status(), b.completedAt(), clock.instant())) {
            throw ApiException.unprocessable("PHOTOS_CLOSED", "This photo can no longer be removed");
        }
        jdbc.sql(QueryUtil.get(QueryConstants.DELETE_BOOKING_PHOTO)).param("id", photoId).update();
        deleteFile(photo.objectKey());
        log.info("Booking {}: {} photo removed", b.bookingRef(), photo.stage());
    }

    /**
     * The booking's photos with short-lived links, oldest first. No access check: callers (booking detail,
     * complaint detail) have already checked that the viewer is the customer, the Genie or an admin.
     */
    @Transactional(readOnly = true)
    public List<BookingPhotoDto> list(UUID bookingId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_BOOKING_PHOTOS)).param("b", bookingId)
            .query(Row.class).list().stream()
            .map(p -> {
                FileStorage.FileLink link = storage.link(p.objectKey(),
                    p.stage().toLowerCase() + "-" + p.id().toString().substring(0, 8) + "." + FileTypes.extension(p.contentType()),
                    p.contentType(), true);
                return new BookingPhotoDto(p.id(), p.stage(), link.url(), link.expiresAt(), p.contentType(),
                    p.sizeBytes(), p.createdAt());
            })
            .toList();
    }

    /** "ADD_PHOTO_BEFORE" / "ADD_PHOTO_AFTER" for the Genie's booking screen, when allowed and not full. */
    List<String> genieActions(String status, @Nullable OffsetDateTime completedAt, List<BookingPhotoDto> photos) {
        Instant now = clock.instant();
        List<String> actions = new ArrayList<>();
        for (Stage stage : Stage.values()) {
            long count = photos.stream().filter(p -> p.stage().equals(stage.name())).count();
            if (count < MAX_PER_STAGE && canChange(stage, status, completedAt, now)) {
                actions.add("ADD_PHOTO_" + stage.name());
            }
        }
        return actions;
    }

    static boolean canChange(Stage stage, String status, @Nullable OffsetDateTime completedAt, Instant now) {
        if ("IN_PROGRESS".equals(status)) {
            return true;
        }
        return stage == Stage.AFTER && "COMPLETED".equals(status) && completedAt != null
            && now.isBefore(completedAt.toInstant().plus(AFTER_WINDOW));
    }

    private Context lockOwn(UUID bookingId, UUID genieId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.LOCK_BOOKING_PHOTO_CONTEXT)).param("id", bookingId)
            .query(Context.class).optional()
            .filter(b -> b.genieId().equals(genieId))
            .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking not found"));
    }

    void deleteFile(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException ex) {
            log.warn("Could not delete job photo {}: {}", key, ex.getMessage());
        }
    }
}
