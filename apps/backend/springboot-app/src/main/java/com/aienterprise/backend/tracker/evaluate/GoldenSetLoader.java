package com.aienterprise.backend.tracker.evaluate;

import java.sql.Types;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class GoldenSetLoader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final Resource resource;
    private final GoldenSetDatasetValidator validator;

    @Autowired
    public GoldenSetLoader(
            JdbcClient jdbc,
            @Value("${tracker.golden-resource:tracker/golden-set-v1.json}") String resourcePath) {
        this(jdbc, new ClassPathResource(resourcePath), new GoldenSetDatasetValidator());
    }

    GoldenSetLoader(
            JdbcClient jdbc,
            Resource resource,
            GoldenSetDatasetValidator validator) {
        this.jdbc = jdbc;
        this.resource = resource;
        this.validator = validator;
    }

    @Transactional
    public LoadSummary loadIfNeeded() {
        GoldenSetDatasetValidator.ValidationResult dataset = validator.validate(resource);
        if (!dataset.valid()) {
            throw new IllegalStateException(
                    "invalid golden dataset: " + String.join("; ", dataset.errors()));
        }

        ExistingDataset existing = jdbc.sql("""
                SELECT dataset_sha256, item_count
                  FROM golden_set_dataset
                 WHERE dataset_version = :datasetVersion
                """)
                .param("datasetVersion", dataset.datasetVersion())
                .query((rs, rowNum) -> new ExistingDataset(
                        rs.getString("dataset_sha256").trim(),
                        rs.getInt("item_count")))
                .optional()
                .orElse(null);
        if (existing != null) {
            if (!existing.datasetSha256().equals(dataset.datasetSha256())) {
                throw new IllegalStateException(
                        "golden dataset hash mismatch for " + dataset.datasetVersion());
            }
            int storedItems = jdbc.sql("""
                    SELECT COUNT(*) FROM golden_set_item
                     WHERE dataset_version = :datasetVersion
                    """)
                    .param("datasetVersion", dataset.datasetVersion())
                    .query(Integer.class)
                    .single();
            if (existing.itemCount() != dataset.cases().size()
                    || storedItems != dataset.cases().size()) {
                throw new IllegalStateException(
                        "golden dataset ledger is incomplete for " + dataset.datasetVersion());
            }
            return new LoadSummary(
                    dataset.datasetVersion(), dataset.datasetSha256(), 0, true);
        }

        long rubricId = jdbc.sql("""
                SELECT id FROM rubric_version WHERE version_label = :rubricVersion
                """)
                .param("rubricVersion", dataset.rubricVersion())
                .query(Long.class)
                .single();
        jdbc.sql("""
                INSERT INTO golden_set_dataset
                  (dataset_version, dataset_sha256, item_count)
                VALUES (:datasetVersion, :datasetSha256, :itemCount)
                """)
                .param("datasetVersion", dataset.datasetVersion())
                .param("datasetSha256", dataset.datasetSha256())
                .param("itemCount", dataset.cases().size())
                .update();
        for (GoldenSetDatasetValidator.GoldenCase item : dataset.cases()) {
            insertItem(dataset, item, rubricId);
        }
        return new LoadSummary(
                dataset.datasetVersion(), dataset.datasetSha256(), dataset.cases().size(), false);
    }

    private void insertItem(
            GoldenSetDatasetValidator.ValidationResult dataset,
            GoldenSetDatasetValidator.GoldenCase item,
            long rubricId) {
        jdbc.sql("""
                INSERT INTO golden_set_item
                  (case_code, fixture_kind, title, body, expected_output, notes,
                   expected_schema_version, rubric_version_id, dataset_version,
                   provenance_refs, input_sha256, active)
                VALUES
                  (:caseCode, :fixtureKind, :title, :body, :expectedOutput, :notes,
                   :expectedSchemaVersion, :rubricId, :datasetVersion,
                   :provenanceRefs, :inputSha256, :active)
                """)
                .param("caseCode", item.caseCode())
                .param("fixtureKind", item.fixtureKind())
                .param("title", item.title())
                .param("body", item.body())
                .param("expectedOutput", item.expectedOutputJson())
                .param("notes", item.notes(), Types.VARCHAR)
                .param("expectedSchemaVersion", dataset.expectedSchemaVersion())
                .param("rubricId", rubricId)
                .param("datasetVersion", dataset.datasetVersion())
                .param("provenanceRefs", json(item.provenanceRefs()))
                .param("inputSha256", item.inputSha256())
                .param("active", item.active() ? "Y" : "N")
                .update();
    }

    private static String json(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("cannot serialize provenance refs", impossible);
        }
    }

    public record LoadSummary(
            String datasetVersion,
            String datasetSha256,
            int insertedItems,
            boolean noOp) {
    }

    private record ExistingDataset(String datasetSha256, int itemCount) {
    }
}
