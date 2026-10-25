package com.progenie.messaging;

import com.progenie.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live updates for the signed-in user: {@code GET /api/v1/stream} (text/event-stream). Events:
 * <ul>
 *   <li>{@code ready}: the stream works</li>
 *   <li>{@code notification}: a new bell notification ({@code type, title, link})</li>
 *   <li>{@code booking}: a booking changed ({@code bookingId, change})</li>
 *   <li>{@code ticket}: a complaint changed ({@code ticketId, bookingId, change})</li>
 * </ul>
 * Sent with the normal Authorization header (the web app reads it with fetch, not EventSource, for that reason).
 */
@RestController
public class LiveController {

    private final LiveHub hub;

    public LiveController(LiveHub hub) {
        this.hub = hub;
    }

    @Hidden // a text/event-stream, not JSON: documented in docs/API.md, read by web/src/app/core/live/live-stream.ts
    @GetMapping(path = "/api/v1/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no"); // nginx: don't buffer
        return hub.open(CurrentUser.id(), CurrentUser.role());
    }
}
