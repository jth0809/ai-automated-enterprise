package com.aienterprise.backend.tracker.event;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextFeatureEmbeddingTest {

    private final TextFeatureEmbedding embedding = new TextFeatureEmbedding();

    private static double cosine(double[] a, double[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    private static double l2(double[] v) {
        double sum = 0.0;
        for (double value : v) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }

    @Test
    void producesDeterministicFixedDimensionVectors() {
        double[] first = embedding.embed("Starship reached orbital velocity");
        double[] second = embedding.embed("Starship reached orbital velocity");

        assertEquals(TextFeatureEmbedding.DIMENSIONS, first.length);
        assertArrayEquals(first, second);
    }

    @Test
    void isStableAcrossCaseUnicodeAndPunctuation() {
        double[] messy = embedding.embed("Hello,   WORLD!!!");
        double[] clean = embedding.embed("hello world");

        assertEquals(1.0, cosine(messy, clean), 1e-9);
    }

    @Test
    void scoresRelatedTextHigherThanUnrelatedText() {
        double[] base = embedding.embed("SpaceX launched a Falcon 9 rocket to orbit");
        double[] related = embedding.embed("SpaceX launched a Falcon 9 booster to orbit");
        double[] unrelated = embedding.embed("The chef slowly cooked a delicious pasta dinner");

        double relatedScore = cosine(base, related);
        double unrelatedScore = cosine(base, unrelated);

        assertTrue(relatedScore > 0.7, "related score was " + relatedScore);
        assertTrue(unrelatedScore < 0.5, "unrelated score was " + unrelatedScore);
        assertTrue(relatedScore > unrelatedScore + 0.2);
    }

    @Test
    void blankInputIsAZeroVectorWithoutNaN() {
        for (String blank : new String[] {"", "   \t  ", "!!! ...", null}) {
            double[] vector = embedding.embed(blank);
            for (double value : vector) {
                assertEquals(0.0, value);
                assertFalse(Double.isNaN(value));
            }
        }
    }

    @Test
    void unitNormIsZeroOrOne() {
        assertEquals(0.0, l2(embedding.embed("")), 1e-12);
        assertEquals(1.0, l2(embedding.embed("integrated life support demonstration")), 1e-9);
    }

    @Test
    void ignoresTextBeyondTheInputLengthCap() {
        String capped = "x".repeat(TextFeatureEmbedding.MAX_INPUT_LENGTH);
        double[] atCap = embedding.embed(capped);
        double[] overCap = embedding.embed(capped + " this trailing text must be ignored");

        assertArrayEquals(atCap, overCap);
    }
}
