package com.aienterprise.backend.resume;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeTokenServiceTest {

    private static Clock fixedAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Test
    void aFreshlyMintedTokenVerifies() {
        Instant now = Instant.parse("2026-07-08T00:00:00Z");
        ResumeTokenService svc = new ResumeTokenService("secret-key", Duration.ofMinutes(15), fixedAt(now));

        String token = svc.mint();

        assertTrue(svc.verify(token));
    }

    @Test
    void aTokenIsRejectedAfterItsTtlElapses() {
        Instant issue = Instant.parse("2026-07-08T00:00:00Z");
        String token = new ResumeTokenService("secret-key", Duration.ofMinutes(15), fixedAt(issue)).mint();

        // Verify 16 minutes later, past the 15-minute TTL.
        ResumeTokenService later =
                new ResumeTokenService("secret-key", Duration.ofMinutes(15), fixedAt(issue.plus(Duration.ofMinutes(16))));

        assertFalse(later.verify(token));
    }

    @Test
    void aTamperedTokenIsRejected() {
        Instant now = Instant.parse("2026-07-08T00:00:00Z");
        ResumeTokenService svc = new ResumeTokenService("secret-key", Duration.ofMinutes(15), fixedAt(now));

        String token = svc.mint();
        String tampered = token.substring(0, token.length() - 1)
                + (token.charAt(token.length() - 1) == 'a' ? 'b' : 'a');

        assertFalse(svc.verify(tampered));
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRejected() {
        Instant now = Instant.parse("2026-07-08T00:00:00Z");
        String token = new ResumeTokenService("secret-A", Duration.ofMinutes(15), fixedAt(now)).mint();

        ResumeTokenService otherSecret = new ResumeTokenService("secret-B", Duration.ofMinutes(15), fixedAt(now));

        assertFalse(otherSecret.verify(token));
    }

    @Test
    void aMalformedTokenIsRejectedWithoutThrowing() {
        ResumeTokenService svc = new ResumeTokenService("secret-key", Duration.ofMinutes(15), Clock.systemUTC());

        assertFalse(svc.verify("not-a-token"));
        assertFalse(svc.verify(""));
    }
}
