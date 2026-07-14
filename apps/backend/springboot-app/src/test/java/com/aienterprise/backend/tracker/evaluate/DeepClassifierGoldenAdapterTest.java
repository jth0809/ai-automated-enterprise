package com.aienterprise.backend.tracker.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aienterprise.backend.tracker.domain.ArticleRow;
import com.aienterprise.backend.tracker.evaluate.DeepClassifier.ClaimDraft;

@ExtendWith(MockitoExtension.class)
class DeepClassifierGoldenAdapterTest {

    @Mock
    private DeepClassifier deepClassifier;

    @Test
    void mapsOneValidatedClaimToGoldenOutputWithoutPersistence() throws Exception {
        when(deepClassifier.classify(any(), eq(List.of()))).thenReturn(List.of(new ClaimDraft(
                "P1-REUSE-LV", "FLIGHT_TEST", 5, "Aster Works",
                LocalDate.parse("2026-01-12"), "PRIMARY",
                "recovered it intact", "NEW")));
        DeepClassifierGoldenAdapter adapter = new DeepClassifierGoldenAdapter(deepClassifier);

        GoldenOutput output = adapter.classify(input());

        assertTrue(output.relevant());
        assertEquals("P1-REUSE-LV", output.nodeCode());
        assertEquals("FLIGHT_TEST", output.eventType());
        assertEquals(5, output.claimedLevel());
        assertEquals("Aster Works", output.actor());
        assertEquals(LocalDate.parse("2026-01-12"), output.occurredOn());
        assertEquals("PRIMARY", output.publicationPath());
        assertEquals("recovered it intact", output.evidenceQuote());

        ArgumentCaptor<ArticleRow> article = ArgumentCaptor.forClass(ArticleRow.class);
        verify(deepClassifier).classify(article.capture(), eq(List.of()));
        assertEquals(1L, article.getValue().id());
        assertEquals(input().title(), article.getValue().title());
        assertEquals(input().body(), article.getValue().body());
        assertEquals("GOLDEN_EVALUATION", article.getValue().pipelineStatus());
    }

    @Test
    void mapsNoClaimsToCanonicalIrrelevantOutput() throws Exception {
        when(deepClassifier.classify(any(), eq(List.of()))).thenReturn(List.of());
        DeepClassifierGoldenAdapter adapter = new DeepClassifierGoldenAdapter(deepClassifier);

        GoldenOutput output = adapter.classify(input());

        assertFalse(output.relevant());
        assertNull(output.nodeCode());
        assertNull(output.evidenceQuote());
    }

    @Test
    void rejectsAmbiguousMultipleClaimsInsteadOfSilentlyChoosingOne() {
        ClaimDraft claim = new ClaimDraft(
                "P1-REUSE-LV", "FLIGHT_TEST", 5, "Aster Works",
                LocalDate.parse("2026-01-12"), "PRIMARY",
                "recovered it intact", "NEW");
        when(deepClassifier.classify(any(), eq(List.of()))).thenReturn(List.of(claim, claim));
        DeepClassifierGoldenAdapter adapter = new DeepClassifierGoldenAdapter(deepClassifier);

        assertThrows(IllegalStateException.class, () -> adapter.classify(input()));
    }

    private static GoldenInput input() {
        return new GoldenInput(
                1L, "GOLD-001", "SYNTHETIC",
                "Reusable orbital stage completes a recovery flight",
                "Aster Works flew an orbital-class booster and recovered it intact after the mission.",
                "golden-output-v1");
    }
}
