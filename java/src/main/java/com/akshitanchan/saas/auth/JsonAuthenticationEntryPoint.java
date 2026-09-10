package com.akshitanchan.saas.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

// every anonymous or rejected request ends up here; the filter leaves the specific reason
// on the request, defaulting to "missing bearer token" when there was no header at all
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        String reason = (String) request.getAttribute(BearerTokenAuthenticationFilter.FAILURE_REASON_ATTRIBUTE);
        writeUnauthorized(response, objectMapper, reason == null ? "missing bearer token" : reason);
    }

    static void writeUnauthorized(HttpServletResponse response, ObjectMapper objectMapper, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("detail", detail)));
    }
}
