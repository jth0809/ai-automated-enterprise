package com.aienterprise.backend.tracker.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class MetaculusClientTest {

    @Test
    void requestsTheExactAuthenticatedPostEndpoint() {
        AtomicReference<URI> uri = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        long epoch = Instant.parse("2045-01-01T00:00:00Z").getEpochSecond();
        MetaculusClient client = new MetaculusClient("safe-token-123", (target, header) -> {
            uri.set(target);
            authorization.set(header);
            return new MetaculusClient.HttpResult(200, bytes(json(epoch)));
        });

        assertEquals("2045.0", client.fetch(3515).orElseThrow()
                .forecastYear().toPlainString());
        assertEquals(URI.create(
                "https://www.metaculus.com/api/posts/3515/?with_cp=true"), uri.get());
        assertEquals("Token safe-token-123", authorization.get());
    }

    @Test
    void rejectsBadTokensBeforeTransport() {
        MetaculusClient.Transport transport = (target, header) -> {
            throw new AssertionError("transport must not be called");
        };

        assertThrows(IllegalArgumentException.class,
                () -> new MetaculusClient("", transport));
        assertThrows(IllegalArgumentException.class,
                () -> new MetaculusClient("token with spaces", transport));
        assertThrows(IllegalArgumentException.class,
                () -> new MetaculusClient("x".repeat(257), transport));
    }

    @Test
    void boundsTheResponseAndClassifiesSafeHttpFailuresWithoutTokenLeak() {
        String token = "safe-token-123";
        assertSafeFailure(token, 401, "authorization");
        assertSafeFailure(token, 403, "authorization");
        assertSafeFailure(token, 429, "rate limited");
        assertSafeFailure(token, 503, "HTTP 503");

        MetaculusClient oversized = new MetaculusClient(token,
                (target, header) -> new MetaculusClient.HttpResult(
                        200, new byte[MetaculusClient.MAX_RESPONSE_BYTES + 1]));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> oversized.fetch(3515));
        assertFalse(failure.getMessage().contains(token));
    }

    private static void assertSafeFailure(String token, int status, String fragment) {
        MetaculusClient client = new MetaculusClient(token,
                (target, header) -> new MetaculusClient.HttpResult(status, new byte[0]));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client.fetch(3515));
        assertFalse(failure.getMessage().contains(token));
        org.junit.jupiter.api.Assertions.assertTrue(
                failure.getMessage().contains(fragment), failure::getMessage);
    }

    private static String json(long epoch) {
        return """
                {"id":3515,"question":{"type":"date","aggregations":{
                  "recency_weighted":{"latest":{"centers":[%d]}}}}}
                """.formatted(epoch);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
