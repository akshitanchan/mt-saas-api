package com.akshitanchan.saas.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

// fastapi has no separate "forbidden" shape, so an access-denied request still comes back
// as a 401 with the same {"detail": "..."} body as the authentication entry point
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        String reason = (String) request.getAttribute(BearerTokenAuthenticationFilter.FAILURE_REASON_ATTRIBUTE);
        JsonAuthenticationEntryPoint.writeUnauthorized(response, objectMapper, reason == null ? "invalid token" : reason);
    }
}
