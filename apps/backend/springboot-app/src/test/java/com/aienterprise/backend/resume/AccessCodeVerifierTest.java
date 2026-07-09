package com.aienterprise.backend.resume;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessCodeVerifierTest {

    // sha256("hunter2") — precomputed so the test documents the contract.
    private static final String HUNTER2_SHA256 =
            "f52fbd32b2b3b86ff88ef6c490628285f482af15ddcb29541f94bcf526a3f6c7";

    @Test
    void acceptsCodeWhoseSha256MatchesAConfiguredHash() {
        AccessCodeVerifier verifier = new AccessCodeVerifier(List.of(HUNTER2_SHA256));
        assertTrue(verifier.verify("hunter2"));
    }

    @Test
    void rejectsUnknownCode() {
        AccessCodeVerifier verifier = new AccessCodeVerifier(List.of(HUNTER2_SHA256));
        assertFalse(verifier.verify("wrong-code"));
    }

    @Test
    void rejectsEverythingWhenNoCodesConfigured() {
        AccessCodeVerifier verifier = new AccessCodeVerifier(List.of());
        assertFalse(verifier.verify("hunter2"));
    }

    @Test
    void isCaseInsensitiveOnTheConfiguredHexDigest() {
        AccessCodeVerifier verifier = new AccessCodeVerifier(List.of(HUNTER2_SHA256.toUpperCase()));
        assertTrue(verifier.verify("hunter2"));
    }
}
