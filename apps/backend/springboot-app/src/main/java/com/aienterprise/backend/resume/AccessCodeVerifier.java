package com.aienterprise.backend.resume;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Verifies a submitted access code against a configured set of SHA-256
 * digests. Codes are never stored in plaintext; the comparison is
 * constant-time (MessageDigest.isEqual) and scans every configured hash
 * without early exit so a caller cannot infer a match from timing.
 */
public class AccessCodeVerifier {

    private final List<byte[]> codeHashes;

    public AccessCodeVerifier(List<String> hexSha256Hashes) {
        this.codeHashes = hexSha256Hashes.stream()
                .map(h -> fromHex(h.trim()))
                .toList();
    }

    public boolean verify(String rawCode) {
        byte[] candidate = sha256(rawCode.getBytes(StandardCharsets.UTF_8));
        boolean matched = false;
        for (byte[] known : codeHashes) {
            // OR-accumulate without short-circuit to keep the scan constant-time.
            matched |= MessageDigest.isEqual(candidate, known);
        }
        return matched;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] fromHex(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }
}
