package com.akshitanchan.saas.auth;

import com.auth0.jwt.exceptions.JWTVerificationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// reads Authorization on every request, scheme case-insensitive; on success it sets the
// User as principal, on failure it leaves a reason for the entry point to report as 401
@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    static final String FAILURE_REASON_ATTRIBUTE = "auth.failureReason";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public BearerTokenAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null) {
            String[] parts = header.trim().split("\\s+", 2);
            if (parts.length == 2 && parts[0].equalsIgnoreCase("bearer")) {
                authenticate(request, parts[1]);
            } else {
                request.setAttribute(FAILURE_REASON_ATTRIBUTE, "missing bearer token");
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        try {
            UUID userId = UUID.fromString(jwtService.decode(token).getSubject());
            userRepository.findById(userId).ifPresentOrElse(
                    user -> SecurityContextHolder.getContext()
                            .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of())),
                    () -> request.setAttribute(FAILURE_REASON_ATTRIBUTE, "user not found"));
        } catch (JWTVerificationException | IllegalArgumentException e) {
            // IllegalArgumentException covers a missing/non-uuid subject claim
            request.setAttribute(FAILURE_REASON_ATTRIBUTE, "invalid token");
        }
    }
}
