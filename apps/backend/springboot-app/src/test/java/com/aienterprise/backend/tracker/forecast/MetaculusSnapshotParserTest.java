package com.aienterprise.backend.tracker.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class MetaculusSnapshotParserTest {

    private final MetaculusSnapshotParser parser = new MetaculusSnapshotParser();

    @Test
    void parsesOneRecencyWeightedDateCenter() {
        long epoch = Instant.parse("2045-01-01T00:00:00Z").getEpochSecond();
        String json = """
                {
                  "id": 3515,
                  "question": {
                    "type": "date",
                    "aggregations": {
                      "recency_weighted": {
                        "latest": {"centers": [%d]}
                      }
                    }
                  }
                }
                """.formatted(epoch);

        MetaculusSnapshot snapshot = parser.parse(
                3515, json.getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertEquals(3515, snapshot.postId());
        assertEquals("2045.0", snapshot.forecastYear().toPlainString());
        assertEquals(epoch, snapshot.centerEpochSeconds());
    }

    @Test
    void missingOrWithheldAggregateIsANormalEmptyResult() {
        String missing = """
                {"id":3515,"question":{"type":"date","aggregations":{}}}
                """;
        String withheld = """
                {"id":3515,"question":{"type":"date","aggregations":{
                  "recency_weighted":{"latest":null}}}}
                """;

        assertTrue(parser.parse(3515, bytes(missing)).isEmpty());
        assertTrue(parser.parse(3515, bytes(withheld)).isEmpty());
    }

    @Test
    void rejectsWrongPostBinaryQuestionAndImpossibleDate() {
        String binary = """
                {"id":3515,"question":{"type":"binary","aggregations":{}}}
                """;
        String wrong = """
                {"id":999,"question":{"type":"date","aggregations":{}}}
                """;
        String impossible = """
                {"id":3515,"question":{"type":"date","aggregations":{
                  "recency_weighted":{"latest":{"centers":[999999999999]}}}}}
                """;

        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(3515, bytes(binary)));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(3515, bytes(wrong)));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(3515, bytes(impossible)));
    }

    @Test
    void rejectsAmbiguousCentersAndUnknownRootShape() {
        String multiple = """
                {"id":3515,"question":{"type":"date","aggregations":{
                  "recency_weighted":{"latest":{"centers":[1,2]}}}}}
                """;

        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(3515, bytes(multiple)));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(3515, bytes("[]")));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
