package com.aienterprise.backend.resume;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Plain unit test (no Spring context): the contract is the authorization
 * decision at the application layer (backend AGENTS.md), exercised by
 * calling the controller methods directly.
 */
class ResumeControllerTest {

    private static final String HUNTER2_SHA256 =
            "f52fbd32b2b3b86ff88ef6c490628285f482af15ddcb29541f94bcf526a3f6c7";

    private ResumeController controller() {
        AccessCodeVerifier verifier = new AccessCodeVerifier(List.of(HUNTER2_SHA256));
        ResumeTokenService tokens =
                new ResumeTokenService("test-secret", Duration.ofMinutes(15), Clock.systemUTC());
        ResumeContent content = () -> Map.of("name", "Jane Dev", "headline", "Platform Engineer");
        return new ResumeController(verifier, tokens, content);
    }

    @Test
    void redeemWithValidCodeReturnsAUsableToken() {
        ResponseEntity<Map<String, Object>> res = controller().redeem(new RedeemRequest("hunter2"));

        assertEquals(HttpStatus.OK, res.getStatusCode());
        Object token = res.getBody().get("token");
        assertNotNull(token);
        // The issued token must open the résumé.
        assertEquals(HttpStatus.OK, controller().resume("Bearer " + token).getStatusCode());
    }

    @Test
    void redeemWithInvalidCodeIsUnauthorizedAndLeaksNoToken() {
        ResponseEntity<Map<String, Object>> res = controller().redeem(new RedeemRequest("wrong"));

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        assertNull(res.getBody().get("token"));
    }

    @Test
    void resumeIsServedForAValidBearerToken() {
        ResumeController c = controller();
        String token = (String) c.redeem(new RedeemRequest("hunter2")).getBody().get("token");

        ResponseEntity<?> res = c.resume("Bearer " + token);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("Jane Dev", ((Map<?, ?>) res.getBody()).get("name"));
    }

    @Test
    void resumeIsUnauthorizedWithoutAValidToken() {
        ResumeController c = controller();
        assertEquals(HttpStatus.UNAUTHORIZED, c.resume(null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, c.resume("Bearer bogus").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, c.resume("not-a-bearer-header").getStatusCode());
    }
}
