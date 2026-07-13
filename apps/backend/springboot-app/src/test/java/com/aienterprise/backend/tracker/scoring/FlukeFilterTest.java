package com.aienterprise.backend.tracker.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

import com.aienterprise.backend.tracker.evaluate.AnthropicClient;

@ExtendWith(MockitoExtension.class)
class FlukeFilterTest {

    private static final String BODY =
            "Independent telemetry confirmed the outcome. The vehicle completed the test. "
                    + "Engineers described the result as within predictions.";

    @Mock
    private AnthropicClient client;

    private FlukeFilter filter;

    @BeforeEach
    void setUp() {
        filter = new FlukeFilter(client, "fluke-model");
    }

    @Test
    void acceptsMatchWithExactEvidenceQuote() {
        when(client.completeWithTool(anyString(), anyList(), anyString(), anyMap(), eq("review_claim")))
                .thenReturn(Map.of("verdict", "MATCH", "evidence_quote", "The vehicle completed the test."));

        FlukeResult result = filter.evaluate(reviewCase());

        assertEquals("MATCH", result.verdict());
        assertEquals("The vehicle completed the test.", result.evidenceQuote());
        assertEquals("fluke-model", result.modelId());
    }

    @Test
    void rejectsUnmatchedQuoteWithoutInventingAVerdict() {
        when(client.completeWithTool(anyString(), anyList(), anyString(), anyMap(), anyString()))
                .thenReturn(Map.of("verdict", "MISMATCH", "evidence_quote", "Not in any article."));

        assertThrows(IllegalArgumentException.class, () -> filter.evaluate(reviewCase()));
    }

    @Test
    void rejectsUnknownVerdicts() {
        when(client.completeWithTool(anyString(), anyList(), anyString(), anyMap(), anyString()))
                .thenReturn(Map.of("verdict", "MAYBE", "evidence_quote", "The vehicle completed the test."));

        assertThrows(IllegalArgumentException.class, () -> filter.evaluate(reviewCase()));
    }

    @Test
    void rejectsMissingFields() {
        when(client.completeWithTool(anyString(), anyList(), anyString(), anyMap(), anyString()))
                .thenReturn(Map.of("verdict", "MATCH"));

        assertThrows(IllegalArgumentException.class, () -> filter.evaluate(reviewCase()));
    }

    @Test
    void matchesQuotesAcrossWhitespaceDifferences() {
        when(client.completeWithTool(anyString(), anyList(), anyString(), anyMap(), anyString()))
                .thenReturn(Map.of("verdict", "MISMATCH",
                        "evidence_quote", "The vehicle   completed\nthe test."));

        FlukeResult result = filter.evaluate(reviewCase());

        assertEquals("MISMATCH", result.verdict());
    }

    @Test
    void stampsPromptShaAndSendsClaimWithoutClassifierReasoning() throws Exception {
        when(client.completeWithTool(anyString(), anyList(), anyString(), anyMap(), anyString()))
                .thenReturn(Map.of("verdict", "MATCH", "evidence_quote", "The vehicle completed the test."));

        FlukeResult result = filter.evaluate(reviewCase());

        String prompt = new ClassPathResource("tracker/prompt-fluke-system.txt")
                .getContentAsString(StandardCharsets.UTF_8);
        String expectedSha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(prompt.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expectedSha, result.promptSha256());

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(client).completeWithTool(eq("fluke-model"), anyList(), user.capture(), anyMap(),
                eq("review_claim"));
        assertTrue(user.getValue().contains("P1-ORBIT-REFUEL"));
        assertTrue(user.getValue().contains("FLIGHT_TEST"));
        assertTrue(user.getValue().contains("current level: 5"));
        assertTrue(user.getValue().contains(BODY));
    }

    private FlukeCandidate reviewCase() {
        return new FlukeCandidate(
                1L, 2L, "P1-ORBIT-REFUEL", "궤도 추진제 이송·급유", "TRL", 5,
                "FLIGHT_TEST", 6, "SpaceX", LocalDate.of(2026, 1, 30), List.of(BODY));
    }
}
