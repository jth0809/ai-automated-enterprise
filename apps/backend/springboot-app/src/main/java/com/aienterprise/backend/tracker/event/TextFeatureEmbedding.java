package com.aienterprise.backend.tracker.event;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Deterministic, dependency-free text embedding for local event matching. Maps
 * normalized text to a fixed {@value #DIMENSIONS}-dimension, L2-normalized
 * vector using signed feature hashing over word tokens and character n-grams.
 *
 * <p>No model files, external services, native libraries, or persisted state:
 * every vector is computed in request memory. Identical input always yields a
 * bit-for-bit identical vector so that merge decisions are reproducible.
 */
public final class TextFeatureEmbedding {

    /** Fixed embedding width. */
    public static final int DIMENSIONS = 256;

    /** Characters beyond this bound are not processed. */
    public static final int MAX_INPUT_LENGTH = 512;

    /** Stamped alongside merge decisions so re-embeds stay comparable. */
    public static final String VERSION = "features-v1";

    private static final int CHAR_NGRAM = 3;
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /** Returns the L2-normalized feature vector for {@code text}. */
    public double[] embed(String text) {
        double[] vector = new double[DIMENSIONS];
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return vector;
        }
        int start = 0;
        for (int i = 0; i <= normalized.length(); i++) {
            if (i == normalized.length() || normalized.charAt(i) == ' ') {
                if (i > start) {
                    add(vector, "w:" + normalized.substring(start, i));
                }
                start = i + 1;
            }
        }
        for (int i = 0; i + CHAR_NGRAM <= normalized.length(); i++) {
            add(vector, "c:" + normalized.substring(i, i + CHAR_NGRAM));
        }
        normalizeL2(vector);
        return vector;
    }

    /**
     * Lowercases (NFKC), reduces every non-alphanumeric run to a single space,
     * and trims. Input is bounded to {@link #MAX_INPUT_LENGTH} first.
     */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String bounded = text.length() > MAX_INPUT_LENGTH
                ? text.substring(0, MAX_INPUT_LENGTH)
                : text;
        String lower = Normalizer.normalize(bounded, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(lower.length());
        boolean pendingSpace = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (pendingSpace && builder.length() > 0) {
                    builder.append(' ');
                }
                pendingSpace = false;
                builder.append(c);
            } else {
                pendingSpace = true;
            }
        }
        return builder.toString();
    }

    private static void add(double[] vector, String feature) {
        long hash = fnv1a(feature);
        int index = (int) Long.remainderUnsigned(hash >>> 1, DIMENSIONS);
        double sign = (hash & 1L) == 0L ? 1.0 : -1.0;
        vector[index] += sign;
    }

    private static void normalizeL2(double[] vector) {
        double sumSquares = 0.0;
        for (double value : vector) {
            sumSquares += value * value;
        }
        if (sumSquares == 0.0) {
            return;
        }
        double norm = Math.sqrt(sumSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }

    private static long fnv1a(String value) {
        long hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
