package com.progenie.shared.web;

import java.io.IOException;
import java.util.UUID;

import com.progenie.shared.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds the logged-in user's ID to every log line of the request (MDC {@value RequestIds#MDC_USER}). Runs inside
 * Spring Security, right after the bearer token was checked (see SecurityConfig). Not a {@code @Component}: as one
 * it would also run before security, when nobody is logged in yet.
 */
public class UserMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        UUID user = CurrentUser.idOrNull();
        if (user != null) {
            MDC.put(RequestIds.MDC_USER, user.toString());
        }
        chain.doFilter(request, response);
    }
}
