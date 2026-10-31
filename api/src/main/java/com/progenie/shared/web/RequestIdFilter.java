package com.progenie.shared.web;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First filter of every request: gives it an ID ({@link RequestIds}) and writes one access-log line when it ends.
 *
 * <p>The ID comes from an incoming {@code X-Request-Id} when that looks like an ID (the Cloudflare Worker sends its
 * Ray ID, so Cloudflare's and Render's logs can be matched), else a new one. Access lines: DEBUG for normal requests,
 * INFO for 4xx/5xx, WARN when a request takes longer than {@link #SLOW_MS} ms. The query string is never logged
 * (it can hold PIN codes and map points). Health checks and live streams (logged by LiveHub) are skipped.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger access = LoggerFactory.getLogger("com.progenie.access");
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    static final long SLOW_MS = 2000;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String incoming = request.getHeader(RequestIds.HEADER);
        String id = incoming != null && VALID.matcher(incoming).matches() ? incoming : RequestIds.next();
        MDC.put(RequestIds.MDC_REQUEST, id);
        response.setHeader(RequestIds.HEADER, id);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (!request.isAsyncStarted()) {
                logAccess(request, response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            }
            MDC.remove(RequestIds.MDC_REQUEST);
            MDC.remove(RequestIds.MDC_USER);
        }
    }

    private static void logAccess(HttpServletRequest request, int status, long ms) {
        String path = request.getRequestURI();
        if (path.startsWith("/actuator/")) {
            return;
        }
        if (ms > SLOW_MS) {
            access.warn("{} {} -> {} in {} ms (slow)", request.getMethod(), path, status, ms);
        } else if (status >= 400) {
            access.info("{} {} -> {} in {} ms", request.getMethod(), path, status, ms);
        } else if (access.isDebugEnabled()) {
            access.debug("{} {} -> {} in {} ms", request.getMethod(), path, status, ms);
        }
    }
}
