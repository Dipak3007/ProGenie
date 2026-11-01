package com.progenie.admin;

import java.util.UUID;

import com.progenie.review.ReviewPhotos;
import com.progenie.review.ReviewPhotos.AdminReviewPhotoDto;
import com.progenie.shared.security.CurrentUser;
import com.progenie.shared.web.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin moderation of photos in reviews (Phase 3.5A): reported ones first; hide or keep. */
@RestController
@RequestMapping("/api/v1/admin/review-photos")
public class AdminReviewPhotoController {

    private final ReviewPhotos photos;

    public AdminReviewPhotoController(ReviewPhotos photos) {
        this.photos = photos;
    }

    /** reported=true: reported and still visible (the queue); false: every photo, newest first. */
    @GetMapping
    public PageResponse<AdminReviewPhotoDto> list(@RequestParam(defaultValue = "true") boolean reported,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return photos.adminList(reported, page, size);
    }

    @PostMapping("/{id}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hide(@PathVariable UUID id) {
        photos.hide(CurrentUser.id(), id);
    }

    /** Shows a hidden photo again, or keeps a reported one (clears the report). */
    @PostMapping("/{id}/keep")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void keep(@PathVariable UUID id) {
        photos.keep(CurrentUser.id(), id);
    }
}
