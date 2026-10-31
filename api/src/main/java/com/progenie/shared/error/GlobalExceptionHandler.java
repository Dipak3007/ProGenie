package com.progenie.shared.error;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.progenie.shared.security.CurrentUser;
import com.progenie.shared.web.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Turns exceptions into consistent ProblemDetail JSON: {status, title, detail, code, errorId, errors?}. The
 * {@code errorId} is the request's ID ({@link RequestIds}), added to every error body by {@link ErrorIdAdvice}.
 *
 * <p>Logging (3A-0): a business error (4xx) is one INFO line with its code (401 and 404 at DEBUG, 429 at WARN); an
 * unexpected error (5xx) is one ERROR line with the stack trace. Field values and request bodies are never logged.
 * The request ID and user ID are added to every line by the log pattern.
 *
 * <p>Runs before Spring Boot's own ProblemDetail handler ({@code spring.mvc.problemdetails.enabled}), otherwise
 * that one answers body validation and unreadable JSON first, with a generic "Invalid request content." and no code.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException ex) {
        logHandled(ex.getStatus(), ex.getCode(), ex.getMessage());
        ProblemDetail pd = problem(ex.getStatus(), ex.getCode(), ex.getMessage());
        ex.getDetails().forEach(pd::setProperty);
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        logHandled(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "fields " + errors.keySet());
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are invalid");
        pd.setProperty("errors", errors);
        return pd;
    }

    /** Validation of @RequestParam / @PathVariable constraints (e.g. @Min on a query parameter). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail handleMethodValidation(HandlerMethodValidationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(error ->
            errors.putIfAbsent(result.getMethodParameter().getParameterName(), error.getDefaultMessage())));
        logHandled(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "parameters " + errors.keySet());
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some parameters are invalid");
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
        MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ProblemDetail handleBadInput(Exception ex) {
        logHandled(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getClass().getSimpleName());
        return problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The request is malformed or missing a value");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail handleUploadSize(MaxUploadSizeExceededException ex) {
        logHandled(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "upload over the limit");
        return problem(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "The file is too large");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Concurrent update: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "This record was changed by someone else, please retry");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleConflict(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "CONFLICT", "The request conflicts with existing data");
    }

    /** A method-security denial inside a controller: 401 when nobody is logged in, else 403. */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    ProblemDetail handleDenied(RuntimeException ex) {
        if (ex instanceof AuthenticationException || CurrentUser.idOrNull() == null) {
            logHandled(HttpStatus.UNAUTHORIZED, "NOT_AUTHENTICATED", "login needed");
            return problem(HttpStatus.UNAUTHORIZED, "NOT_AUTHENTICATED", "Please log in");
        }
        logHandled(HttpStatus.FORBIDDEN, "FORBIDDEN", "not allowed");
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to do this");
    }

    /**
     * Everything else. Spring MVC's own errors (404 no such path, 405 wrong method, ...) keep their status; a client
     * that went away (closed tab, dropped live stream) is not an error; anything else is a bug: ERROR with the stack
     * trace and a 500 that only says "quote the error ID".
     */
    @ExceptionHandler(Exception.class)
    @Nullable ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse er) {
            HttpStatusCode status = er.getStatusCode();
            String code = HttpStatus.resolve(status.value()) != null ? HttpStatus.resolve(status.value()).name() : "ERROR";
            logHandled(status, code, ex.getClass().getSimpleName());
            ProblemDetail pd = er.getBody();
            pd.setTitle(code);
            pd.setProperty("code", code);
            return ResponseEntity.status(status).headers(er.getHeaders()).body(pd);
        }
        if (isClientGone(ex)) {
            log.debug("Client went away during {} {}", request.getMethod(), request.getRequestURI());
            return null;
        }
        log.error("Unexpected error in {} {} (error ID {})", request.getMethod(), request.getRequestURI(),
            RequestIds.current(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem(HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR", "Something went wrong on our side. Please try again; if it keeps happening, quote the error ID."));
    }

    // ------------------------------------------------------------------ helpers

    private static void logHandled(HttpStatusCode status, String code, @Nullable String detail) {
        int s = status.value();
        if (s == 401 || s == 404) {
            log.debug("Request refused: {} {} ({})", s, code, detail);
        } else if (s == 429) {
            log.warn("Rate limited: {} {} ({})", s, code, detail);
        } else if (s >= 500) {
            log.warn("Request failed: {} {} ({})", s, code, detail);
        } else {
            log.info("Request refused: {} {} ({})", s, code, detail);
        }
    }

    private static boolean isClientGone(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof AsyncRequestNotUsableException || t.getClass().getSimpleName().equals("ClientAbortException")) {
                return true;
            }
            if (t instanceof IOException && t.getMessage() != null
                && (t.getMessage().contains("Broken pipe") || t.getMessage().contains("Connection reset"))) {
                return true;
            }
        }
        return false;
    }

    static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(code);
        pd.setProperty("code", code);
        return pd;
    }
}
