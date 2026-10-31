package com.progenie.shared.error;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.progenie.shared.web.RequestIds;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 401 and 403 answers written by Spring Security (before any controller runs) as the same ProblemDetail JSON as
 * every other error, with {@code code} and {@code errorId}. The standard bearer-token handlers still set the status
 * and the {@code WWW-Authenticate} header.
 */
public final class ProblemResponses {

    private static final Logger log = LoggerFactory.getLogger(ProblemResponses.class);

    private ProblemResponses() {
    }

    public static AuthenticationEntryPoint authenticationEntryPoint() {
        BearerTokenAuthenticationEntryPoint standard = new BearerTokenAuthenticationEntryPoint();
        return (request, response, ex) -> {
            standard.commence(request, response, ex);
            log.debug("Request refused: 401 NOT_AUTHENTICATED ({} {})", request.getMethod(), request.getRequestURI());
            write(response, 401, "NOT_AUTHENTICATED", "Please log in");
        };
    }

    public static AccessDeniedHandler accessDeniedHandler() {
        BearerTokenAccessDeniedHandler standard = new BearerTokenAccessDeniedHandler();
        return (request, response, ex) -> {
            standard.handle(request, response, ex);
            log.info("Request refused: 403 FORBIDDEN ({} {})", request.getMethod(), request.getRequestURI());
            write(response, 403, "FORBIDDEN", "You are not allowed to do this");
        };
    }

    private static void write(HttpServletResponse response, int status, String code, String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String id = RequestIds.current();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // code, detail and the ID are our own fixed strings / [A-Za-z0-9-]: nothing to escape
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + code + "\",\"status\":" + status
            + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\""
            + (id == null ? "" : ",\"errorId\":\"" + id + "\"") + "}");
    }
}
