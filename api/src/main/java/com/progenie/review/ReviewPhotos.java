package com.progenie.review;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.progenie.booking.BookingQueryService;
import com.progenie.provider.GenieProfileService;
import com.progenie.provider.GenieQueryService;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.storage.FileStorage;
import com.progenie.shared.util.FileTypes;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.web.PageResponse;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Photos in reviews (Phase 3.5A): the customer adds up to 3 photos of the finished work to their review, while the
 * review can still be written (14 days after the job). The browser shrinks them; the API checks the real file type.
 * They are public with the review on the Genie's profile unless an admin hid one; the Genie can report one. They go
 * with the review, and with the customer's account when it is deleted.
 */
@Service
public class ReviewPhotos {

    private static final Logger log = LoggerFactory.getLogger(ReviewPhotos.class);

    public static final int MAX_PER_REVIEW = 3;
    static final long MAX_BYTES = 5L * 1024 * 1024;

    /**
     * {@code url} is a stable API link ({@code /api/v1/review-photos/{id}}) that redirects to a fresh, short-lived file
     * link, so lists and cached profiles can carry it. {@code reported}: the Genie reported it (waiting for an admin).
     */
    public record ReviewPhotoDto(UUID id, String url, boolean reported) {
    }

    public record ReportRequest(@Size(max = 200) String reason) {
    }

    /** A photo in the admin moderation list, with its review. */
    public record AdminReviewPhotoDto(UUID id, String url, Instant expiresAt, UUID reviewId, int rating,
                                      @Nullable String comment, String customerName, String genieName, UUID genieId,
                                      String bookingRef, OffsetDateTime createdAt, @Nullable OffsetDateTime reportedAt,
                                      @Nullable String reportReason, @Nullable OffsetDateTime hiddenAt) {
    }

    record Row(UUID id, UUID reviewId, String objectKey, String contentType, OffsetDateTime reportedAt,
               OffsetDateTime hiddenAt) {
    }

    record AdminRow(UUID id, String objectKey, String contentType, UUID reviewId, int rating, String comment,
                    String customerName, String genieName, UUID genieId, String bookingRef, OffsetDateTime createdAt,
                    OffsetDateTime reportedAt, String reportReason, OffsetDateTime hiddenAt) {
    }

    record OwnedReview(UUID id, String bookingRef) {
    }

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final BookingQueryService bookings;
    private final GenieProfileService genies;

    public ReviewPhotos(JdbcClient jdbc, FileStorage storage, BookingQueryService bookings, GenieProfileService genies) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.bookings = bookings;
        this.genies = genies;
    }

    /** The file behind a public photo link; 404 once an admin hid it or it was removed. */
    @Transactional(readOnly = true)
    public FileStorage.FileLink publicLink(UUID photoId) {
        Row r = jdbc.sql(QueryUtil.get(QueryConstants.GET_REVIEW_PHOTO_VISIBLE)).param("id", photoId)
            .query(Row.class).optional()
            .orElseThrow(() -> ApiException.notFound("PHOTO_NOT_FOUND", "Photo not found"));
        return link(r.id(), r.objectKey(), r.contentType());
    }

    /** The customer adds a photo to their review of this booking. */
    @Transactional
    public ReviewPhotoDto add(UUID customerId, UUID bookingId, MultipartFile file) {
        bookings.reviewable(customerId, bookingId); // completed, theirs, within the review window
        OwnedReview review = ownReview(customerId, bookingId);
        int count = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_REVIEW_PHOTOS)).param("r", review.id())
            .query(Integer.class).single();
        if (count >= MAX_PER_REVIEW) {
            throw ApiException.unprocessable("TOO_MANY_PHOTOS", "At most " + MAX_PER_REVIEW + " photos per review");
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
        String key = "reviews/" + review.id() + "/" + id + "." + FileTypes.extension(type);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, type);
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_REVIEW_PHOTO))
            .param("id", id).param("r", review.id()).param("k", key).param("ct", type).param("size", bytes.length)
            .update();
        log.info("Booking {}: photo added to the review ({}, {} bytes)", review.bookingRef(), type, bytes.length);
        genies.reviewsChanged();
        return toDto(new Row(id, review.id(), key, type, null, null));
    }

    /** The customer removes a photo from their review, while the review window is open. */
    @Transactional
    public void delete(UUID customerId, UUID bookingId, UUID photoId) {
        bookings.reviewable(customerId, bookingId);
        OwnedReview review = ownReview(customerId, bookingId);
        String key = jdbc.sql(QueryUtil.get(QueryConstants.DELETE_REVIEW_PHOTO_OWNED)).param("id", photoId)
            .param("r", review.id()).query(String.class).optional()
            .orElseThrow(() -> ApiException.notFound("PHOTO_NOT_FOUND", "Photo not found"));
        deleteFile(key);
        genies.reviewsChanged();
        log.info("Booking {}: photo removed from the review", review.bookingRef());
    }

    /** The Genie reports a photo in a review of their work; an admin decides whether to hide it. */
    @Transactional
    public void report(UUID genieId, UUID reviewId, UUID photoId, @Nullable String reason) {
        int rows = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_REVIEW_PHOTO_REPORT))
            .param("id", photoId).param("r", reviewId).param("g", genieId)
            .param("reason", StringUtils.hasText(reason) ? reason.trim() : null)
            .update();
        if (rows == 0) {
            throw ApiException.notFound("PHOTO_NOT_FOUND", "Photo not found");
        }
        log.warn("Review photo {} reported by Genie {}", photoId, genieId);
    }

    /** Visible photos per review, oldest first. {@code owner}: the reviewed Genie, who also sees what they reported. */
    @Transactional(readOnly = true)
    public Map<UUID, List<ReviewPhotoDto>> forReviews(Collection<UUID> reviewIds, boolean owner) {
        if (reviewIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_REVIEW_PHOTOS_FOR_REVIEWS))
            .param("ids", List.copyOf(reviewIds)).param("withHidden", false)
            .query(Row.class).list().stream()
            .map(r -> owner ? r : new Row(r.id(), r.reviewId(), r.objectKey(), r.contentType(), null, r.hiddenAt()))
            .collect(Collectors.groupingBy(Row::reviewId, Collectors.mapping(this::toDto, Collectors.toList())));
    }

    /** Admin moderation list: reported photos first, or every photo. */
    @Transactional(readOnly = true)
    public PageResponse<AdminReviewPhotoDto> adminList(boolean reportedOnly, int page, int size) {
        long total = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_REVIEW_PHOTO_ADMIN)).param("reported", reportedOnly)
            .query(Long.class).single();
        List<AdminReviewPhotoDto> items = jdbc.sql(QueryUtil.get(QueryConstants.GET_REVIEW_PHOTO_ADMIN))
            .param("reported", reportedOnly)
            .param("limit", PageResponse.clampSize(size)).param("offset", PageResponse.offset(page, size))
            .query(AdminRow.class).list().stream()
            .map(r -> {
                FileStorage.FileLink link = link(r.id(), r.objectKey(), r.contentType());
                return new AdminReviewPhotoDto(r.id(), link.url(), link.expiresAt(), r.reviewId(), r.rating(), r.comment(),
                    r.customerName(), r.genieName(), r.genieId(), r.bookingRef(), r.createdAt(), r.reportedAt(),
                    r.reportReason(), r.hiddenAt());
            })
            .toList();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    /** Admin: hide a photo from everyone but admins (the report is settled). */
    @Transactional
    public void hide(UUID adminId, UUID photoId) {
        if (jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_REVIEW_PHOTO_HIDE)).param("id", photoId).param("a", adminId).update() == 0) {
            throw ApiException.notFound("PHOTO_NOT_FOUND", "Photo not found");
        }
        genies.reviewsChanged();
        log.info("Review photo {} hidden by admin {}", photoId, adminId);
    }

    /** Admin: show the photo again, or keep it after a report (the report is cleared). */
    @Transactional
    public void keep(UUID adminId, UUID photoId) {
        if (jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_REVIEW_PHOTO_KEEP)).param("id", photoId).update() == 0) {
            throw ApiException.notFound("PHOTO_NOT_FOUND", "Photo not found");
        }
        genies.reviewsChanged();
        log.info("Review photo {} kept visible by admin {}", photoId, adminId);
    }

    private OwnedReview ownReview(UUID customerId, UUID bookingId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_REVIEW_OWNED_BY_BOOKING)).param("b", bookingId).param("c", customerId)
            .query(OwnedReview.class).optional()
            .orElseThrow(() -> ApiException.unprocessable("REVIEW_FIRST", "Write your review first, then add photos"));
    }

    private ReviewPhotoDto toDto(Row r) {
        return new ReviewPhotoDto(r.id(), GenieQueryService.REVIEW_PHOTO_PATH + r.id(), r.reportedAt() != null);
    }

    private FileStorage.FileLink link(UUID id, String key, String contentType) {
        return storage.link(key, "review-" + id.toString().substring(0, 8) + "." + FileTypes.extension(contentType),
            contentType, true);
    }

    private void deleteFile(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException ex) {
            log.warn("Could not delete review photo {}: {}", key, ex.getMessage());
        }
    }
}
