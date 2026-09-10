package com.akshitanchan.saas.ratelimit;

import com.akshitanchan.saas.config.AppProperties;
import com.akshitanchan.saas.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// fixed-window limiter backed by redis, mirroring app/ratelimit.py: incr a bucket keyed by
// (name, client ip), set a 60s expiry only the first time the bucket is touched, and fail
// open if redis itself is unreachable. called directly at the top of a handler; no filter,
// no annotation.
@Component
public class RateLimiter {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;
    private final AppProperties properties;

    public RateLimiter(StringRedisTemplate redisTemplate, AppProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void check(String name, int limit) {
        if (!properties.rateLimit().enabled()) {
            return;
        }

        String key = "rl:" + name + ":" + hash(currentRequest().getRemoteAddr());

        long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
            if (count == 1) {
                redisTemplate.expire(key, WINDOW);
            }
        } catch (Exception e) {
            return;
        }

        if (count > limit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }
    }

    private static HttpServletRequest currentRequest() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    }

    private static String hash(String ip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(ip.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
