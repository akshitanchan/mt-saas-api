package com.akshitanchan.saas.webhooks;

import com.akshitanchan.saas.config.AppProperties;
import com.akshitanchan.saas.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

// mirrors app/routes/webhooks.py:_verify_stripe_signature; a no-op unless a webhook secret is
// configured, in which case every field of the stripe-signature header is checked exactly as
// the python service does, including the 300s replay tolerance
@Component
public class StripeSignatureVerifier {

    private static final int TOLERANCE_SECONDS = 300;

    private final AppProperties properties;

    public StripeSignatureVerifier(AppProperties properties) {
        this.properties = properties;
    }

    public void verify(byte[] payload, String signatureHeader) {
        String secret = properties.stripe().webhookSecret();
        if (secret == null || secret.isEmpty()) {
            return;
        }
        if (signatureHeader == null || signatureHeader.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "missing stripe-signature");
        }

        Map<String, List<String>> parts = new LinkedHashMap<>();
        for (String item : signatureHeader.split(",")) {
            int eq = item.indexOf('=');
            if (eq < 0) {
                continue;
            }
            parts.computeIfAbsent(item.substring(0, eq).strip(), k -> new ArrayList<>())
                    .add(item.substring(eq + 1).strip());
        }

        List<String> timestamps = parts.getOrDefault("t", List.of());
        List<String> signatures = parts.getOrDefault("v1", List.of());
        if (timestamps.isEmpty() || signatures.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid stripe-signature format");
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestamps.get(0));
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid stripe-signature timestamp");
        }

        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > TOLERANCE_SECONDS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "stale stripe-signature");
        }

        String expected = hmacHex(secret, timestamp, payload);
        boolean matches = signatures.stream().anyMatch(candidate -> constantTimeEquals(expected, candidate));
        if (!matches) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid stripe-signature");
        }
    }

    private static String hmacHex(String secret, long timestamp, byte[] payload) {
        try {
            byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
            byte[] signedPayload = new byte[prefix.length + payload.length];
            System.arraycopy(prefix, 0, signedPayload, 0, prefix.length);
            System.arraycopy(payload, 0, signedPayload, prefix.length, payload.length);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(signedPayload);

            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String expected, String candidate) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }
}
