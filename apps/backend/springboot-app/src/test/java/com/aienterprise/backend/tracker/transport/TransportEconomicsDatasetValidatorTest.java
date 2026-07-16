package com.aienterprise.backend.tracker.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class TransportEconomicsDatasetValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final TransportEconomicsDatasetValidator validator =
            new TransportEconomicsDatasetValidator();

    @Test
    void acceptsThreeFrontierYearsAndAZeroLaunchYearAsProvisionalEvidence() {
        var result = validator.validateJson(validJson());

        assertTrue(result.errors().isEmpty(), () -> String.join("\n", result.errors()));
        assertEquals("transport-economics-v1", result.datasetVersion());
        assertEquals(3, result.annualFrontier().size());
        assertEquals(0, result.annualCounts().stream()
                .filter(row -> row.count().year() == 2011)
                .findFirst().orElseThrow().count().launches());
    }

    @Test
    void choosesTheLowestEligibleRealPriceAsTheAnnualFrontier() throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(validJson());
        ArrayNode prices = (ArrayNode) root.get("priceObservations");
        ObjectNode lower = prices.get(2).deepCopy();
        lower.put("vehicleVariant", "FALCON_HEAVY_EXPENDABLE");
        lower.put("publishedPriceUsd", 60_000_000);
        lower.put("maxLeoPayloadKg", 30_000);
        prices.add(lower);

        var result = validator.validateJson(JSON.writeValueAsString(root));

        assertTrue(result.errors().isEmpty(), () -> String.join("\n", result.errors()));
        assertEquals("FALCON_HEAVY_EXPENDABLE",
                result.annualFrontier().get(2018).vehicleVariant());
        assertEquals(4, result.observations().size());
    }

    @ParameterizedTest
    @MethodSource("invalidMutations")
    void failsClosedOnInvalidOrOverstrictnessSensitiveInputs(Mutation mutation)
            throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(validJson());
        mutation.change().accept(root);

        var result = validator.validateJson(JSON.writeValueAsString(root));

        assertFalse(result.errors().isEmpty(), mutation.name());
        assertTrue(result.errors().stream().anyMatch(
                error -> error.contains(mutation.expectedError())),
                () -> mutation.name() + ": " + String.join("\n", result.errors()));
    }

    @Test
    void rejectsProhibitedSourceContentRecursively() throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(validJson());
        ObjectNode nested = root.withObject("reviewMetadata");
        nested.put("body", "external source content");

        var result = validator.validateJson(JSON.writeValueAsString(root));

        assertTrue(result.errors().stream()
                .anyMatch(error -> error.contains("prohibited field body")));
    }

    @Test
    void rejectsDerivedFieldsInsteadOfTrustingThem() throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(validJson());
        ((ObjectNode) root.withArray("priceObservations").get(0))
                .put("realBasisUsdPerKg", 1);

        var result = validator.validateJson(JSON.writeValueAsString(root));

        assertTrue(result.errors().stream()
                .anyMatch(error -> error.contains("unknown field realBasisUsdPerKg")));
    }

    private static Stream<Mutation> invalidMutations() {
        return Stream.of(
                new Mutation("unknown root key", root -> root.put("extra", true),
                        "unknown field extra"),
                new Mutation("runtime target order", root -> root.withObject("assumption")
                        .put("easyTargetUsdPerKg", 200), "target ordering"),
                new Mutation("non-https provenance", root -> ((ObjectNode) root
                        .withArray("priceObservations").get(0))
                        .put("sourceUrl", "http://example.test/fact"), "sourceUrl must use HTTPS"),
                new Mutation("bad hash", root -> ((ObjectNode) root
                        .withArray("cpi").get(0)).put("contentSha256", "ABC"),
                        "bad contentSha256"),
                new Mutation("pre-operational estimate", root -> ((ObjectNode) root
                        .withArray("priceObservations").get(0)).put("operational", false),
                        "operational must be true"),
                new Mutation("mismatched configuration", root -> ((ObjectNode) root
                        .withArray("priceObservations").get(0)).put("configurationMatch", false),
                        "configurationMatch must be true"),
                new Mutation("negative annual count", root -> ((ObjectNode) root
                        .withArray("annualLaunchCounts").get(1)).put("count", -1),
                        "count must be nonnegative"),
                new Mutation("missing annual year", root -> root
                        .withArray("annualLaunchCounts").remove(1),
                        "annual years must be contiguous"),
                new Mutation("non-falcon family", root -> ((ObjectNode) root
                        .withArray("priceObservations").get(0)).put("vehicleFamily", "STARSHIP"),
                        "vehicleFamily must be FALCON"));
    }

    public static String validJson() {
        String hash = "a".repeat(64);
        return """
                {
                  "datasetVersion": "transport-economics-v1",
                  "assumption": {
                    "version": "transport-assumptions-v1",
                    "modelVersion": "wright-falcon-v1",
                    "centralTargetUsdPerKg": 200,
                    "easyTargetUsdPerKg": 500,
                    "hardTargetUsdPerKg": 100,
                    "priceBasisYear": 2025,
                    "horizonYears": 150,
                    "weakFitR2": 0.50,
                    "wideningFactor": 1.50
                  },
                  "cpi": [
                    {"year":2016,"value":240.007,"seriesId":"CUUR0000SA0",
                     "sourceLabel":"BLS CPI-U","sourceUrl":"https://www.bls.gov/cpi/",
                     "sourceLocator":"2016 annual average","accessedOn":"2026-07-15",
                     "contentSha256":"%1$s","factSummary":"Annual CPI-U numeric index."},
                    {"year":2017,"value":245.120,"seriesId":"CUUR0000SA0",
                     "sourceLabel":"BLS CPI-U","sourceUrl":"https://www.bls.gov/cpi/",
                     "sourceLocator":"2017 annual average","accessedOn":"2026-07-15",
                     "contentSha256":"%1$s","factSummary":"Annual CPI-U numeric index."},
                    {"year":2018,"value":251.107,"seriesId":"CUUR0000SA0",
                     "sourceLabel":"BLS CPI-U","sourceUrl":"https://www.bls.gov/cpi/",
                     "sourceLocator":"2018 annual average","accessedOn":"2026-07-15",
                     "contentSha256":"%1$s","factSummary":"Annual CPI-U numeric index."},
                    {"year":2025,"value":321.943,"seriesId":"CUUR0000SA0",
                     "sourceLabel":"BLS CPI-U","sourceUrl":"https://www.bls.gov/cpi/",
                     "sourceLocator":"2025 annual average","accessedOn":"2026-07-15",
                     "contentSha256":"%1$s","factSummary":"Annual CPI-U numeric index."}
                  ],
                  "annualLaunchCounts": [
                    %2$s
                  ],
                  "priceObservations": [
                    %3$s
                  ]
                }
                """.formatted(hash, annualCounts(hash), priceObservations(hash));
    }

    private static String annualCounts(String hash) {
        int[] counts = {2, 0, 2, 3, 6, 7, 8, 18, 21};
        StringBuilder json = new StringBuilder();
        for (int index = 0; index < counts.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            int year = 2010 + index;
            json.append("""
                    {"year":%d,"count":%d,"sourceLabel":"Launch Library 2",
                     "sourceUrl":"https://ll.thespacedevs.com/2.3.0/launches/",
                     "sourceLocator":"completed Falcon-family orbital launches in %d",
                     "accessedOn":"2026-07-15","contentSha256":"%s",
                     "factSummary":"Completed Falcon-family orbital launch count."}
                    """.formatted(year, counts[index], year, hash));
        }
        return json.toString();
    }

    private static String priceObservations(String hash) {
        return """
                {"observationYear":2016,"vehicleFamily":"FALCON",
                 "vehicleVariant":"FALCON_9_FT_EXPENDABLE","operational":true,
                 "configurationMatch":true,"publishedPriceUsd":61200000,
                 "maxLeoPayloadKg":22800,"sourceLabel":"FAA 2017 Compendium",
                 "sourceUrl":"https://www.faa.gov/about/office_org/headquarters_offices/ast/media/2017_ast_compendium.pdf",
                 "sourceLocator":"Falcon 9 fact sheet","accessedOn":"2026-07-15",
                 "contentSha256":"%1$s","factSummary":"Published expendable price and matching maximum LEO payload."},
                {"observationYear":2017,"vehicleFamily":"FALCON",
                 "vehicleVariant":"FALCON_9_FT_EXPENDABLE","operational":true,
                 "configurationMatch":true,"publishedPriceUsd":62000000,
                 "maxLeoPayloadKg":22800,"sourceLabel":"FAA 2018 Compendium",
                 "sourceUrl":"https://www.faa.gov/sites/faa.gov/files/space/additional_information/2018_AST_Compendium.pdf",
                 "sourceLocator":"Falcon 9 fact sheet","accessedOn":"2026-07-15",
                 "contentSha256":"%1$s","factSummary":"Published expendable price and matching maximum LEO payload."},
                {"observationYear":2018,"vehicleFamily":"FALCON",
                 "vehicleVariant":"FALCON_9_EXPENDABLE","operational":true,
                 "configurationMatch":true,"publishedPriceUsd":62000000,
                 "maxLeoPayloadKg":22800,"sourceLabel":"NASA NTRS",
                 "sourceUrl":"https://ntrs.nasa.gov/citations/20180007067",
                 "sourceLocator":"NTRS abstract numeric statement","accessedOn":"2026-07-15",
                 "contentSha256":"%1$s","factSummary":"Advertised price and matching maximum LEO payload."}
                """.formatted(hash);
    }

    private record Mutation(
            String name, java.util.function.Consumer<ObjectNode> change,
            String expectedError) {
    }
}
