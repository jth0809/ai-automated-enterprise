package com.aienterprise.backend.tracker.scoring;

/**
 * One successful fluke evaluation: the verdict, the code-verified evidence
 * quote, and the audit stamp (raw tool output, model id, prompt SHA-256).
 */
public record FlukeResult(
        String verdict,
        String evidenceQuote,
        String rawOutput,
        String modelId,
        String promptSha256) {
}
