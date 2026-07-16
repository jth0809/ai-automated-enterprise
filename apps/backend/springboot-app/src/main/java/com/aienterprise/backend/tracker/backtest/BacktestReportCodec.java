package com.aienterprise.backend.tracker.backtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

/** Byte-stable JSON codec for immutable backtest audit reports. */
public final class BacktestReportCodec {

    private static final ObjectMapper JSON = mapper();

    public Encoded encode(BacktestReport report) {
        Objects.requireNonNull(report, "report");
        try {
            String json = JSON.writeValueAsString(report);
            return new Encoded(report.inputSha256(), json, sha256(json));
        } catch (IOException encodingFailure) {
            throw new IllegalStateException(
                    "cannot encode backtest report", encodingFailure);
        }
    }

    public BacktestReport decode(String json, String expectedSha256) {
        if (json == null || json.isBlank()
                || expectedSha256 == null
                || !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("backtest report payload is incomplete");
        }
        String actual = sha256(json);
        if (!actual.equals(expectedSha256)) {
            throw new IllegalStateException("backtest report hash mismatch");
        }
        try {
            return JSON.readValue(json, BacktestReport.class);
        } catch (IOException decodingFailure) {
            throw new IllegalStateException(
                    "cannot decode backtest report", decodingFailure);
        }
    }

    private static ObjectMapper mapper() {
        SimpleModule dates = new SimpleModule("tracker-local-date-v1");
        dates.addSerializer(LocalDate.class, new JsonSerializer<>() {
            @Override
            public void serialize(
                    LocalDate value,
                    JsonGenerator generator,
                    SerializerProvider serializers) throws IOException {
                generator.writeString(value.toString());
            }
        });
        dates.addDeserializer(LocalDate.class, new JsonDeserializer<>() {
            @Override
            public LocalDate deserialize(
                    JsonParser parser,
                    DeserializationContext context) throws IOException {
                return LocalDate.parse(parser.getValueAsString());
            }
        });
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(dates);
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        mapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        return mapper;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record Encoded(String inputSha256, String json, String sha256) {
        public Encoded {
            if (inputSha256 == null || !inputSha256.matches("[0-9a-f]{64}")
                    || json == null || json.isBlank()
                    || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "encoded backtest report is incomplete");
            }
        }
    }
}
