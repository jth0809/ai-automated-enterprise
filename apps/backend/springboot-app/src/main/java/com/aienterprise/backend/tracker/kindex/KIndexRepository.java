package com.aienterprise.backend.tracker.kindex;

import java.sql.Date;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence boundary for annual K-index observations and import audits. */
@Repository
public class KIndexRepository {

    private final JdbcClient jdbc;

    public KIndexRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<KIndexObservation> findAll() {
        return jdbc.sql("""
                SELECT obs_year, primary_energy_twh, power_watts, k_value,
                       accounting_basis, source, source_url, accessed_on,
                       dataset_version
                  FROM k_index
                 ORDER BY obs_year
                """)
                .query((rs, rowNum) -> new KIndexObservation(
                        rs.getInt("obs_year"),
                        rs.getBigDecimal("primary_energy_twh"),
                        rs.getLong("power_watts"),
                        rs.getBigDecimal("k_value"),
                        rs.getString("accounting_basis"),
                        rs.getString("source"),
                        rs.getString("source_url"),
                        rs.getDate("accessed_on").toLocalDate(),
                        rs.getString("dataset_version")))
                .list();
    }

    public Optional<String> findImportSha(String datasetVersion) {
        return jdbc.sql("""
                SELECT dataset_sha256 FROM k_index_import
                 WHERE dataset_version = :version
                """)
                .param("version", datasetVersion)
                .query(String.class)
                .optional();
    }

    public void upsert(KIndexObservation observation) {
        int updated = jdbc.sql("""
                UPDATE k_index
                   SET primary_energy_twh = :energy,
                       power_watts = :power,
                       k_value = :kValue,
                       accounting_basis = :basis,
                       source = :source,
                       source_url = :sourceUrl,
                       accessed_on = :accessedOn,
                       dataset_version = :version
                 WHERE obs_year = :year
                """)
                .params(parameters(observation))
                .update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO k_index
                      (obs_year, primary_energy_twh, power_watts, k_value,
                       accounting_basis, source, source_url, accessed_on,
                       dataset_version)
                    VALUES (:year, :energy, :power, :kValue, :basis, :source,
                            :sourceUrl, :accessedOn, :version)
                    """)
                    .params(parameters(observation))
                    .update();
        }
    }

    public void recordImport(
            String datasetVersion,
            String sha256,
            int recordCount,
            String sourceUrl,
            java.time.LocalDate accessedOn) {
        jdbc.sql("""
                INSERT INTO k_index_import
                  (dataset_version, dataset_sha256, record_count,
                   source_url, accessed_on)
                VALUES (:version, :sha, :recordCount, :sourceUrl, :accessedOn)
                """)
                .param("version", datasetVersion)
                .param("sha", sha256)
                .param("recordCount", recordCount)
                .param("sourceUrl", sourceUrl)
                .param("accessedOn", Date.valueOf(accessedOn))
                .update();
    }

    private static java.util.Map<String, Object> parameters(
            KIndexObservation observation) {
        return java.util.Map.of(
                "year", observation.year(),
                "energy", observation.primaryEnergyTwh(),
                "power", observation.powerWatts(),
                "kValue", observation.kValue(),
                "basis", observation.accountingBasis(),
                "source", observation.sourceName(),
                "sourceUrl", observation.sourceUrl(),
                "accessedOn", Date.valueOf(observation.accessedOn()),
                "version", observation.datasetVersion());
    }
}
