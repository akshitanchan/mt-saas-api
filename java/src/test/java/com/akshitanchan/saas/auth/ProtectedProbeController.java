package com.akshitanchan.saas.auth;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// test-only endpoint with no permitAll rule, so security tests have a real mapped
// route to hit instead of relying on how spring boot's default /error forward behaves
@RestController
class ProtectedProbeController {

    @GetMapping("/internal/probe")
    Map<String, Object> probe() {
        return Map.of("ok", true);
    }
}
