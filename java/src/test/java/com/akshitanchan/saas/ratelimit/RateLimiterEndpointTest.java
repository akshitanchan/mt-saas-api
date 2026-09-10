package com.akshitanchan.saas.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.akshitanchan.saas.OrgScopedTestSupport;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// AbstractIntegrationTest turns rate limiting off for the rest of the suite; this class turns
// it back on and lowers the webhooks limit, just for its own spring context. a @DynamicPropertySource
// here (rather than @TestPropertySource) is required to win over the superclass's: dynamic property
// sources always take precedence over @TestPropertySource, regardless of class hierarchy
class RateLimiterEndpointTest extends OrgScopedTestSupport {

    @DynamicPropertySource
    static void lowLimit(DynamicPropertyRegistry registry) {
        registry.add("app.rate-limit.enabled", () -> true);
        registry.add("app.rate-limit.webhooks-per-min", () -> 2);
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearBucket() {
        // every test in the suite shares this redis instance and the same loopback client ip,
        // so the webhooks:stripe bucket must be reset before asserting on its exact count
        Set<String> keys = redisTemplate.keys("rl:webhooks:stripe:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void exceedingTheConfiguredLimitReturns429() {
        for (int i = 0; i < 2; i++) {
            ResponseEntity<Map<String, Object>> response = post("/webhooks/stripe", null, unhandledEvent());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<Map<String, Object>> blocked = post("/webhooks/stripe", null, unhandledEvent());
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getBody()).isEqualTo(Map.of("detail", "rate_limited"));
    }

    private static Map<String, Object> unhandledEvent() {
        return Map.of(
                "id", "evt_" + UUID.randomUUID(),
                "type", "some.unhandled.type",
                "data", Map.of("object", Map.of()));
    }
}
