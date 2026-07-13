package com.aienterprise.backend.tracker.domain;

/** One quote-verified evidence record shown to the human reviewer. */
public record ReviewEvidence(String articleTitle, String articleUrl, String evidenceQuote) {
}
