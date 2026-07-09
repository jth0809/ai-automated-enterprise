package com.aienterprise.backend.resume;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gated résumé. A visitor redeems an access code for a short-lived token,
 * then reads the résumé with it. Authorization is enforced here, at the
 * application layer (backend AGENTS.md). Failures return a single opaque
 * "invalid" — existence, expiry, and revocation are never distinguished,
 * so codes and tokens cannot be probed.
 */
@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private static final ResponseEntity<Map<String, Object>> UNAUTHORIZED =
            ResponseEntity.status(401).body(Map.of("error", "invalid"));

    private final AccessCodeVerifier verifier;
    private final ResumeTokenService tokens;
    private final ResumeContent content;

    public ResumeController(AccessCodeVerifier verifier, ResumeTokenService tokens, ResumeContent content) {
        this.verifier = verifier;
        this.tokens = tokens;
        this.content = content;
    }

    @PostMapping("/redeem")
    public ResponseEntity<Map<String, Object>> redeem(@RequestBody RedeemRequest request) {
        if (request == null || request.code() == null || !verifier.verify(request.code())) {
            return UNAUTHORIZED;
        }
        return ResponseEntity.ok(Map.of("token", tokens.mint()));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> resume(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return UNAUTHORIZED;
        }
        String token = authorization.substring("Bearer ".length());
        if (!tokens.verify(token)) {
            return UNAUTHORIZED;
        }
        return ResponseEntity.ok(content.get());
    }
}
