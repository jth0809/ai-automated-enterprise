package com.aienterprise.backend.tracker.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Fail-closed parser for the selected Metaculus date-question aggregate shape. */
public final class MetaculusSnapshotParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MIN_EPOCH = Instant.parse("2026-01-01T00:00:00Z")
            .getEpochSecond();
    private static final long MAX_EPOCH_EXCLUSIVE = Instant.parse("2301-01-01T00:00:00Z")
            .getEpochSecond();

    public Optional<MetaculusSnapshot> parse(int expectedPostId, byte[] json) {
        if (expectedPostId <= 0 || json == null || json.length == 0) {
            throw new IllegalArgumentException("Post ID and JSON response are required");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception malformed) {
            throw new IllegalArgumentException("Metaculus response is invalid JSON", malformed);
        }
        if (!root.isObject() || !root.path("id").canConvertToInt()) {
            throw new IllegalArgumentException("Metaculus response must be one post object");
        }
        int postId = root.path("id").intValue();
        if (postId != expectedPostId) {
            throw new IllegalArgumentException("Metaculus response post ID mismatch");
        }
        JsonNode question = root.get("question");
        if (question == null || !question.isObject()) {
            throw new IllegalArgumentException("Metaculus post has no question object");
        }
        if (!"date".equals(question.path("type").asText())) {
            throw new IllegalArgumentException("Metaculus comparison requires a date question");
        }

        JsonNode recency = question.path("aggregations").path("recency_weighted");
        if (recency.isMissingNode() || recency.isNull()) {
            return Optional.empty();
        }
        JsonNode latest = recency.get("latest");
        if (latest == null || latest.isNull()) {
            return Optional.empty();
        }
        JsonNode centers = latest.get("centers");
        if (centers == null || centers.isNull()) {
            return Optional.empty();
        }
        if (!centers.isArray() || centers.size() != 1 || !centers.get(0).isNumber()) {
            throw new IllegalArgumentException(
                    "Metaculus date aggregate must expose exactly one numeric center");
        }
        double rawEpoch = centers.get(0).doubleValue();
        if (!Double.isFinite(rawEpoch) || rawEpoch != Math.rint(rawEpoch)) {
            throw new IllegalArgumentException("Metaculus date center must be finite epoch seconds");
        }
        long epoch = (long) rawEpoch;
        if (epoch < MIN_EPOCH || epoch >= MAX_EPOCH_EXCLUSIVE) {
            throw new IllegalArgumentException("Metaculus date center is outside supported years");
        }
        LocalDate date = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).toLocalDate();
        double fractionalYear = date.getYear()
                + (date.getDayOfYear() - 1.0) / date.lengthOfYear();
        BigDecimal year = BigDecimal.valueOf(fractionalYear)
                .setScale(1, RoundingMode.HALF_UP);
        return Optional.of(new MetaculusSnapshot(postId, year, epoch));
    }
}
