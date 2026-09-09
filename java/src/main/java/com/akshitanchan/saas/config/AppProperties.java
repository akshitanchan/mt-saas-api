package com.akshitanchan.saas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String env,
        String baseUrl,
        Jwt jwt,
        MagicLink magicLink,
        Stripe stripe,
        RateLimit rateLimit
) {

    public record Jwt(String secret, String issuer, String audience, int expiresMinutes) {
    }

    public record MagicLink(String pepper, int expiresMinutes) {
    }

    public record Stripe(String webhookSecret) {
    }

    public record RateLimit(
            boolean enabled,
            int authRequestLinkPerMin,
            int authRedeemPerMin,
            int webhooksPerMin
    ) {
    }
}
