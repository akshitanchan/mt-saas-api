package com.akshitanchan.saas.auth;

import com.akshitanchan.saas.config.AppProperties;
import com.akshitanchan.saas.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final AuthMagicLinkRepository magicLinkRepository;
    private final JwtService jwtService;
    private final AppProperties properties;

    public AuthController(
            UserRepository userRepository,
            AuthMagicLinkRepository magicLinkRepository,
            JwtService jwtService,
            AppProperties properties) {
        this.userRepository = userRepository;
        this.magicLinkRepository = magicLinkRepository;
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @PostMapping("/request-link")
    @Transactional
    public RequestLinkResponse requestLink(@Valid @RequestBody RequestLinkRequest body) {
        String email = body.email().toLowerCase().strip();

        User user = userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(new User(email, null)));

        String token = MagicLinkTokens.newToken();
        String tokenHash = MagicLinkTokens.hash(properties.magicLink().pepper(), token);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(properties.magicLink().expiresMinutes());
        magicLinkRepository.save(new AuthMagicLink(tokenHash, user.getId(), expiresAt));

        if ("prod".equals(properties.env())) {
            return new RequestLinkResponse(true, null, properties.baseUrl() + "/auth/redeem?token=" + token);
        }
        return new RequestLinkResponse(true, token, null);
    }

    @PostMapping("/redeem")
    @Transactional
    public AccessTokenResponse redeem(@Valid @RequestBody RedeemRequest body) {
        String token = body.token().strip();
        String tokenHash = MagicLinkTokens.hash(properties.magicLink().pepper(), token);
        OffsetDateTime now = OffsetDateTime.now();

        UUID userId = magicLinkRepository.redeem(tokenHash, now).orElseGet(() -> claimFailureReason(tokenHash, now));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "invalid token"));

        return new AccessTokenResponse(jwtService.mint(user.getId()), "bearer");
    }

    // the atomic update matched zero rows; re-read the row to explain why
    private UUID claimFailureReason(String tokenHash, OffsetDateTime now) {
        AuthMagicLink link = magicLinkRepository.findById(tokenHash).orElse(null);
        if (link == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid token");
        }
        if (link.getUsedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "token already used");
        }
        if (!link.getExpiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "token expired");
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "invalid token");
    }

    public record RequestLinkRequest(@NotBlank @Email String email) {
    }

    public record RequestLinkResponse(boolean sent, String token, String link) {
    }

    public record RedeemRequest(@NotBlank String token) {
    }

    public record AccessTokenResponse(String accessToken, String tokenType) {
    }
}
