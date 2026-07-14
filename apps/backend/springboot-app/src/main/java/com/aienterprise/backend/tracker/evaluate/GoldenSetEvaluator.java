package com.aienterprise.backend.tracker.evaluate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.aienterprise.backend.tracker.domain.GoldenResultRow;
import com.aienterprise.backend.tracker.domain.GoldenRunDraft;
import com.aienterprise.backend.tracker.domain.GoldenSetItemRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

public class GoldenSetEvaluator {

    private static final int MAX_CASES = 60;

    private final TrackerRepository repository;
    private final GoldenSetLoader loader;
    private final GoldenClassifier classifier;

    public GoldenSetEvaluator(
            TrackerRepository repository,
            GoldenSetLoader loader,
            GoldenClassifier classifier) {
        this.repository = repository;
        this.loader = loader;
        this.classifier = classifier;
    }

    public Report evaluate(RunMode mode, VersionTuple versions) {
        if (mode == null) {
            throw new IllegalArgumentException("run mode is required");
        }
        versions.validate();
        GoldenSetLoader.LoadSummary loaded = loader.loadIfNeeded();
        if (!versions.datasetVersion().equals(loaded.datasetVersion())) {
            throw new IllegalStateException("golden dataset version mismatch");
        }
        List<GoldenSetItemRow> items = repository.findActiveGoldenSetItems(
                versions.datasetVersion(), MAX_CASES);
        if (items.isEmpty() || items.size() > MAX_CASES) {
            throw new IllegalStateException("golden run requires 1..60 active cases");
        }
        if (items.stream().anyMatch(item ->
                !versions.expectedSchemaVersion().equals(item.expectedSchemaVersion()))) {
            throw new IllegalStateException("golden output schema version mismatch");
        }

        long rubricVersionId = repository.rubricVersionIdByLabel(versions.rubricVersion());
        long runId = repository.insertGoldenRun(new GoldenRunDraft(
                mode.name(), versions.datasetVersion(), versions.promptVersion(),
                versions.modelVersion(), rubricVersionId,
                versions.expectedSchemaVersion(), items.size()));

        int matched = 0;
        int failed = 0;
        for (GoldenSetItemRow item : items) {
            Evaluation evaluation = evaluateCase(item);
            if (evaluation.matched()) {
                matched++;
            }
            if (evaluation.errorCode() != null) {
                failed++;
            }
            repository.insertGoldenResult(new GoldenResultRow(
                    runId, item.id(), evaluation.actualOutputSha256(),
                    evaluation.matched(), evaluation.mismatchFields(),
                    evaluation.errorCode()));
        }
        double agreement = (double) matched / items.size();
        String status = failed == 0 ? "SUCCEEDED" : "FAILED";
        repository.completeGoldenRun(runId, status, matched, failed, agreement);
        return new Report(
                runId, mode, status, items.size(), matched, failed,
                items.size() - matched - failed, agreement, versions);
    }

    private Evaluation evaluateCase(GoldenSetItemRow item) {
        GoldenInput input = new GoldenInput(
                item.id(), item.caseCode(), item.fixtureKind(), item.title(),
                item.body(), item.expectedSchemaVersion());
        try {
            GoldenOutput actual = classifier.classify(input);
            if (actual == null) {
                return Evaluation.error("SCHEMA_INVALID", null);
            }
            String hash = actual.canonicalOutputSha256();
            String validationError = actual.validationError(input);
            if (validationError != null) {
                return Evaluation.error(validationError, hash);
            }
            GoldenOutput expected = GoldenOutput.fromExpectedJson(item.expectedOutput());
            Set<String> differences = expected.diff(actual);
            return differences.isEmpty()
                    ? new Evaluation(true, hash, null, null)
                    : new Evaluation(false, hash, mismatchFields(differences), null);
        } catch (Exception classifierFailure) {
            return Evaluation.error("CLASSIFIER_ERROR", null);
        }
    }

    private static String mismatchFields(Set<String> fields) {
        List<String> ordered = new ArrayList<>();
        for (String field : List.of(
                "relevant", "nodeCode", "eventType", "claimedLevel", "actor",
                "occurredOn", "publicationPath")) {
            if (fields.contains(field)) {
                ordered.add(field);
            }
        }
        return String.join(",", ordered);
    }

    public enum RunMode {
        OFFLINE_REPLAY,
        LIVE_MODEL,
        DRILL
    }

    public record VersionTuple(
            String datasetVersion,
            String promptVersion,
            String modelVersion,
            String rubricVersion,
            String expectedSchemaVersion) {

        void validate() {
            requireToken(datasetVersion, 80, "datasetVersion");
            requireToken(promptVersion, 80, "promptVersion");
            requireToken(modelVersion, 120, "modelVersion");
            requireToken(rubricVersion, 40, "rubricVersion");
            requireToken(expectedSchemaVersion, 40, "expectedSchemaVersion");
        }

        private static void requireToken(String value, int max, String name) {
            if (value == null || value.isBlank() || value.length() > max) {
                throw new IllegalArgumentException("invalid " + name);
            }
        }
    }

    public record Report(
            long runId,
            RunMode mode,
            String status,
            int totalCount,
            int matchedCount,
            int failedCount,
            int mismatchCount,
            double agreement,
            VersionTuple versions) {
    }

    private record Evaluation(
            boolean matched,
            String actualOutputSha256,
            String mismatchFields,
            String errorCode) {

        static Evaluation error(String errorCode, String hash) {
            return new Evaluation(false, hash, null, errorCode);
        }
    }
}
