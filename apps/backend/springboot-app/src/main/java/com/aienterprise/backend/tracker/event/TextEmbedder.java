package com.aienterprise.backend.tracker.event;

/**
 * Maps text to a fixed-width, L2-normalized feature vector. Kept as a small
 * interface so matching logic can be unit-tested with controlled vectors while
 * production uses {@link TextFeatureEmbedding}.
 */
public interface TextEmbedder {

    double[] embed(String text);
}
