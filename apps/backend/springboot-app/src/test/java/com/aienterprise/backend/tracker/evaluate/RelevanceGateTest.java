package com.aienterprise.backend.tracker.evaluate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aienterprise.backend.tracker.domain.ArticleRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

@ExtendWith(MockitoExtension.class)
class RelevanceGateTest {

    @Mock
    private AnthropicClient client;

    @Mock
    private CostGuard costGuard;

    @Mock
    private TrackerRepository repository;

    private RelevanceGate gate;

    @BeforeEach
    void setUp() {
        gate = new RelevanceGate(client, costGuard, repository, "gate-model");
    }

    @Test
    void parsesYesNoAndWhitespaceCaseInsensitively() {
        when(client.complete(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn("YES", "NO", "yes\n");

        assertTrue(gate.relevant(article()));
        assertFalse(gate.relevant(article()));
        assertTrue(gate.relevant(article()));
    }

    @Test
    void exhaustedCostGuardLeavesArticleInIngestedState() {
        when(costGuard.allow()).thenReturn(false);

        gate.process(article());

        verify(client, never()).complete(anyString(), anyString(), anyString(), anyInt());
        verify(repository, never()).updateArticleStatus(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    void processedArticleMovesToTheDeterministicGateStatus() {
        when(costGuard.allow()).thenReturn(true);
        when(client.complete(anyString(), anyString(), anyString(), anyInt())).thenReturn("YES");

        gate.process(article());

        verify(repository).updateArticleStatus(42, "GATE_PASSED");
    }

    private ArticleRow article() {
        return new ArticleRow(
                42, 1, "https://example.test/a", "a".repeat(64), "Orbital refueling test",
                Instant.parse("2026-07-12T00:00:00Z"), Instant.parse("2026-07-12T01:00:00Z"),
                "A flight test demonstrated propellant transfer.", false, "INGESTED", 0);
    }
}
