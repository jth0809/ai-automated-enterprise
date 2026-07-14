package com.aienterprise.backend.tracker.evaluate;

@FunctionalInterface
public interface GoldenClassifier {

    GoldenOutput classify(GoldenInput input) throws Exception;
}
