package com.aienterprise.backend.resume;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stateless, self-contained access token for the résumé view. A token is
 * {@code base64url(expiryEpochMillis).base64url(HMAC-SHA256(payload))}; there
 * is no server-side session store. Verification checks the signature in
 * constant time and rejects an elapsed expiry. Keeping it dependency-free
 * (no JWT library) keeps the container's attack surface — and the Trivy
 * dependency scan — minimal.
 */
public class ResumeTokenService {

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;

    public ResumeTokenService(String secret, Duration ttl, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.clock = clock;
    }

    public String mint() {
        long expiry = clock.instant().plus(ttl).toEpochMilli();
        byte[] payload = Long.toString(expiry).getBytes(StandardCharsets.UTF_8);
        return ENC.encodeToString(payload) + "." + ENC.encodeToString(sign(payload));
    }

    public boolean verify(String token) {
        if (token == null) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot < 0 || dot == token.length() - 1) {
            return false;
        }
        try {
            byte[] payload = DEC.decode(token.substring(0, dot));
            byte[] providedSig = DEC.decode(token.substring(dot + 1));
            if (!java.security.MessageDigest.isEqual(providedSig, sign(payload))) {
                return false;
            }
            long expiry = Long.parseLong(new String(payload, StandardCharsets.UTF_8));
            return clock.instant().toEpochMilli() < expiry;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
