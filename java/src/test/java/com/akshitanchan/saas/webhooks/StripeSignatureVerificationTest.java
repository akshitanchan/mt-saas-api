package com.akshitanchan.saas.webhooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.akshitanchan.saas.OrgScopedTestSupport;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

// exercises the signature path only reached when app.stripe.webhook-secret is non-empty;
// every other webhook test relies on the secret being unset by default
@TestPropertySource(properties = "app.stripe.webhook-secret=whsec_test")
class StripeSignatureVerificationTest extends OrgScopedTestSupport {

    private static final String SECRET = "whsec_test";

    @Test
    void validSignatureIsAccepted() {
        byte[] raw = unhandledEventPayload();
        long ts = Instant.now().getEpochSecond();

        ResponseEntity<Map<String, Object>> response = postRaw(raw, "t=" + ts + ",v1=" + sign(ts, raw));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void missingSignatureHeaderIsRejectedWhenSecretIsConfigured() {
        byte[] raw = unhandledEventPayload();

        ResponseEntity<Map<String, Object>> response = postRaw(raw, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "missing stripe-signature"));
    }

    @Test
    void invalidSignatureIsRejected() {
        byte[] raw = unhandledEventPayload();
        String badHeader = "t=" + Instant.now().getEpochSecond() + ",v1=" + "0".repeat(64);

        ResponseEntity<Map<String, Object>> response = postRaw(raw, badHeader);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "invalid stripe-signature"));
    }

    private static byte[] unhandledEventPayload() {
        String eventId = "evt_" + UUID.randomUUID();
        return ("{\"id\":\"" + eventId + "\",\"type\":\"some.unhandled.type\",\"data\":{\"object\":{}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String sign(long timestamp, byte[] raw) {
        try {
            byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
            byte[] signedPayload = new byte[prefix.length + raw.length];
            System.arraycopy(prefix, 0, signedPayload, 0, prefix.length);
            System.arraycopy(raw, 0, signedPayload, prefix.length, raw.length);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
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

    private ResponseEntity<Map<String, Object>> postRaw(byte[] body, String signatureHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (signatureHeader != null) {
            headers.set("stripe-signature", signatureHeader);
        }
        return restTemplate.exchange("/webhooks/stripe", HttpMethod.POST, new HttpEntity<>(body, headers), MAP_BODY);
    }
}
