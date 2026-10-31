package com.progenie.shared.error;

import com.progenie.shared.web.RequestIds;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Adds {@code errorId} (the request's ID) to every ProblemDetail the API returns, whichever handler made it
 * (ours, or Spring's own for 404/405). Errors written by Spring Security get it in {@link ProblemResponses}.
 */
@RestControllerAdvice
public class ErrorIdAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof ProblemDetail pd && (pd.getProperties() == null || !pd.getProperties().containsKey("errorId"))) {
            String id = RequestIds.current();
            if (id != null) {
                pd.setProperty("errorId", id);
            }
        }
        return body;
    }
}
