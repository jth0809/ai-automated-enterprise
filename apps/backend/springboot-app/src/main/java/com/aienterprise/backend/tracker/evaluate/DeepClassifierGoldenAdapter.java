package com.aienterprise.backend.tracker.evaluate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.domain.ArticleRow;
import com.aienterprise.backend.tracker.evaluate.DeepClassifier.ClaimDraft;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class DeepClassifierGoldenAdapter implements GoldenClassifier {

    private final DeepClassifier classifier;

    public DeepClassifierGoldenAdapter(DeepClassifier classifier) {
        this.classifier = classifier;
    }

    @Override
    public GoldenOutput classify(GoldenInput input) {
        ArticleRow article = new ArticleRow(
                input.itemId(), 0L,
                "urn:tracker:golden:" + input.caseCode(),
                sha256(input.caseCode()),
                input.title(), Instant.EPOCH, Instant.EPOCH,
                input.body(), true, "SYNTHETIC", "GOLDEN_EVALUATION", 0);
        List<ClaimDraft> claims = classifier.classify(article, List.of());
        if (claims.isEmpty()) {
            return new GoldenOutput(false, null, null, null, null, null, null, null);
        }
        if (claims.size() != 1) {
            throw new IllegalStateException("golden case produced multiple claims");
        }
        ClaimDraft claim = claims.getFirst();
        return new GoldenOutput(
                true, claim.nodeCode(), claim.eventType(), claim.claimedLevel(),
                claim.actor(), claim.occurredOn(), claim.publicationPath(),
                claim.evidenceQuote());
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
