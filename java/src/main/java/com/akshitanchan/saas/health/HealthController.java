package com.akshitanchan.saas.health;

import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;

    public HealthController(DataSource dataSource, StringRedisTemplate redisTemplate) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Boolean> checks = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();

        checks.put("db", runCheck("db", this::pingDb, errors));
        checks.put("redis", runCheck("redis", this::pingRedis, errors));

        boolean ok = checks.values().stream().allMatch(Boolean::booleanValue);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ok ? "ok" : "unready");
        body.put("checks", checks);
        if (!errors.isEmpty()) {
            body.put("errors", errors);
        }

        return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean runCheck(String name, ThrowingCheck check, Map<String, String> errors) {
        try {
            return check.run();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().strip();
            errors.put(name, e.getClass().getSimpleName() + (msg.isEmpty() ? "" : ": " + msg));
            return false;
        }
    }

    private boolean pingDb() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("select 1");
        }
        return true;
    }

    private boolean pingRedis() {
        String pong = redisTemplate.execute((RedisCallback<String>) RedisConnection::ping);
        return "PONG".equals(pong);
    }

    @FunctionalInterface
    private interface ThrowingCheck {
        boolean run() throws Exception;
    }
}
