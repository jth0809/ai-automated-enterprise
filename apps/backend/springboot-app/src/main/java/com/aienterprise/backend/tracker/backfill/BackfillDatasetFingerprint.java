package com.aienterprise.backend.tracker.backfill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

import org.springframework.core.io.Resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Canonical identity shared by import and cutoff-local backtesting. */
public final class BackfillDatasetFingerprint {

    private static final ObjectMapper JSON = new ObjectMapper();

    private BackfillDatasetFingerprint() {
    }

    public static String sha256(
            Resource candidatesResource,
            Resource mappingsResource,
            String datasetVersion,
            String nodeSetVersion,
            String rubricVersion) {
        requireToken(datasetVersion, "datasetVersion");
        requireToken(nodeSetVersion, "nodeSetVersion");
        requireToken(rubricVersion, "rubricVersion");
        try {
            String candidates = normalizeLf(new String(
                    candidatesResource.getContentAsByteArray(),
                    StandardCharsets.UTF_8));
            JsonNode mappings = JSON.readTree(
                    mappingsResource.getContentAsByteArray());
            if (mappings == null) {
                throw new IllegalStateException(
                        "backfill mappings cannot be empty");
            }
            byte[] canonicalMappings = JSON.writeValueAsBytes(
                    canonicalize(mappings));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "datasetVersion=" + datasetVersion + "\n");
            update(digest, "nodeSetVersion=" + nodeSetVersion + "\n");
            update(digest, "rubricVersion=" + rubricVersion + "\n");
            update(digest, "candidates\n");
            update(digest, candidates);
            update(digest, "\nmappings\n");
            digest.update(canonicalMappings);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException loadingFailure) {
            throw new IllegalStateException(
                    "cannot canonicalize tracker backfill dataset "
                            + datasetVersion,
                    loadingFailure);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, canonicalize(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            node.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return node.deepCopy();
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeLf(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void requireToken(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 80) {
            throw new IllegalArgumentException(label + " is required");
        }
    }
}
