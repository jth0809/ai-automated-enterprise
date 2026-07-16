package com.aienterprise.backend.tracker.transport;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Focused persistence boundary for WP3.3 transport economics and coherence. */
@Repository
public class TransportEconomicsRepository {

    private static final String PROJECTION_SELECT = """
            SELECT id, as_of_date, assumption_version, model_version, status,
                   sufficiency_tier, qualification_flags, observation_count,
                   alpha, beta, r_squared, current_cumulative_launches,
                   central_cadence, fast_cadence, slow_cadence,
                   central_target_usd_per_kg, easy_target_usd_per_kg,
                   hard_target_usd_per_kg, central_required_launches,
                   easy_required_launches, hard_required_launches,
                   central_eta_year, earliest_eta_year, latest_eta_year,
                   central_beyond_horizon, earliest_beyond_horizon,
                   latest_beyond_horizon, price_basis_year, horizon_years,
                   interval_kind, basis, price_meaning, projection_label,
                   reason_code
              FROM transport_economics_projection
            """;

    private static final String REPORT_SELECT = """
            SELECT id, report_period_end, layer_c_snapshot_date, price_direction,
                   cadence_direction, layer_b_direction, layer_c_direction,
                   coherence_state, polarity, consecutive_quarter_streak,
                   alert_active, widening_factor, first_divergent_period
              FROM transport_coherence_report
            """;

    private final JdbcClient jdbc;

    public TransportEconomicsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TransportAssumption> findAssumption(String version) {
        return jdbc.sql("""
                SELECT assumption_version, model_version,
                       central_target_usd_per_kg, easy_target_usd_per_kg,
                       hard_target_usd_per_kg, price_basis_year, horizon_years,
                       weak_fit_r2, widening_factor
                  FROM transport_economics_assumption
                 WHERE assumption_version = :version
                """)
                .param("version", version)
                .query((rs, rowNum) -> new TransportAssumption(
                        rs.getString("assumption_version"),
                        rs.getString("model_version"),
                        rs.getBigDecimal("central_target_usd_per_kg"),
                        rs.getBigDecimal("easy_target_usd_per_kg"),
                        rs.getBigDecimal("hard_target_usd_per_kg"),
                        rs.getInt("price_basis_year"),
                        rs.getInt("horizon_years"),
                        rs.getBigDecimal("weak_fit_r2"),
                        rs.getBigDecimal("widening_factor")))
                .optional();
    }

    public void insertAssumption(TransportAssumption assumption) {
        Optional<TransportAssumption> existing = findAssumption(assumption.version());
        if (existing.isPresent()) {
            if (!sameAssumption(existing.get(), assumption)) {
                throw new IllegalStateException(
                        "Transport assumption changed for " + assumption.version());
            }
            return;
        }
        jdbc.sql("""
                INSERT INTO transport_economics_assumption
                  (assumption_version, model_version, central_target_usd_per_kg,
                   easy_target_usd_per_kg, hard_target_usd_per_kg,
                   price_basis_year, horizon_years, weak_fit_r2, widening_factor)
                VALUES (:version, :model, :central, :easy, :hard,
                        :basisYear, :horizon, :weakFit, :widening)
                """)
                .param("version", assumption.version())
                .param("model", assumption.modelVersion())
                .param("central", assumption.centralTargetUsdPerKg())
                .param("easy", assumption.easyTargetUsdPerKg())
                .param("hard", assumption.hardTargetUsdPerKg())
                .param("basisYear", assumption.priceBasisYear())
                .param("horizon", assumption.horizonYears())
                .param("weakFit", assumption.weakFitR2())
                .param("widening", assumption.wideningFactor())
                .update();
    }

    public void insertObservation(TransportPriceObservation observation) {
        Optional<TransportPriceObservation> existing = findObservation(
                observation.observationYear(), observation.vehicleFamily(),
                observation.vehicleVariant());
        if (existing.isPresent()) {
            if (!sameObservation(existing.get(), observation)) {
                throw new IllegalStateException("Transport price observation changed for "
                        + observation.observationYear() + "/" + observation.vehicleVariant());
            }
            return;
        }
        jdbc.sql("""
                INSERT INTO transport_price_observation
                  (observation_year, vehicle_family, vehicle_variant,
                   published_price_usd, max_leo_payload_kg, nominal_usd_per_kg,
                   cpi_observation_value, cpi_basis_value, real_basis_usd_per_kg,
                   cumulative_family_launches, source_label, source_url,
                   source_locator, accessed_on, content_sha256, fact_summary)
                VALUES (:year, :family, :variant, :price, :payload, :nominal,
                        :cpiObservation, :cpiBasis, :realPrice, :cumulative,
                        :sourceLabel, :sourceUrl, :sourceLocator, :accessedOn,
                        :hash, :summary)
                """)
                .param("year", observation.observationYear())
                .param("family", observation.vehicleFamily())
                .param("variant", observation.vehicleVariant())
                .param("price", observation.publishedPriceUsd())
                .param("payload", observation.maxLeoPayloadKg())
                .param("nominal", observation.nominalUsdPerKg())
                .param("cpiObservation", observation.cpiObservationValue())
                .param("cpiBasis", observation.cpiBasisValue())
                .param("realPrice", observation.realBasisUsdPerKg())
                .param("cumulative", observation.cumulativeFamilyLaunches())
                .param("sourceLabel", observation.sourceLabel())
                .param("sourceUrl", observation.sourceUrl())
                .param("sourceLocator", observation.sourceLocator())
                .param("accessedOn", date(observation.accessedOn()))
                .param("hash", observation.contentSha256())
                .param("summary", observation.factSummary())
                .update();
    }

    public List<TransportPriceObservation> findPriceObservations() {
        return jdbc.sql("""
                SELECT id, observation_year, vehicle_family, vehicle_variant,
                       published_price_usd, max_leo_payload_kg, nominal_usd_per_kg,
                       cpi_observation_value, cpi_basis_value, real_basis_usd_per_kg,
                       cumulative_family_launches, source_label, source_url,
                       source_locator, accessed_on, content_sha256, fact_summary
                  FROM transport_price_observation
                 ORDER BY observation_year, vehicle_variant
                """)
                .query(TransportEconomicsRepository::mapObservation)
                .list();
    }

    public List<AnnualLaunchCount> findAnnualFalconLaunchCounts() {
        return jdbc.sql("""
                SELECT observed_on, metric_value
                  FROM layer_b_metric
                 WHERE metric_code = 'ANNUAL_FALCON_FAMILY_LAUNCH_COUNT'
                 ORDER BY observed_on
                """)
                .query((rs, rowNum) -> new AnnualLaunchCount(
                        rs.getDate("observed_on").toLocalDate().getYear(),
                        rs.getBigDecimal("metric_value").longValueExact()))
                .list();
    }

    public void saveProjection(TransportProjection projection) {
        Optional<TransportProjection> existing = findProjection(
                projection.asOfDate(), projection.assumptionVersion());
        if (existing.isPresent()) {
            if (!withoutId(existing.get()).equals(withoutId(projection))) {
                throw new IllegalStateException("Transport projection changed for "
                        + projection.asOfDate() + "/" + projection.assumptionVersion());
            }
            return;
        }
        jdbc.sql("""
                INSERT INTO transport_economics_projection
                  (as_of_date, assumption_version, model_version, status,
                   sufficiency_tier, qualification_flags, observation_count,
                   alpha, beta, r_squared, current_cumulative_launches,
                   central_cadence, fast_cadence, slow_cadence,
                   central_target_usd_per_kg, easy_target_usd_per_kg,
                   hard_target_usd_per_kg, central_required_launches,
                   easy_required_launches, hard_required_launches,
                   central_eta_year, earliest_eta_year, latest_eta_year,
                   central_beyond_horizon, earliest_beyond_horizon,
                   latest_beyond_horizon, price_basis_year, horizon_years,
                   interval_kind, basis, price_meaning, projection_label,
                   reason_code)
                VALUES (:asOf, :assumption, :model, :status, :tier, :flags,
                        :observationCount, :alpha, :beta, :rSquared, :current,
                        :centralCadence, :fastCadence, :slowCadence,
                        :centralTarget, :easyTarget, :hardTarget,
                        :centralRequired, :easyRequired, :hardRequired,
                        :centralEta, :earliestEta, :latestEta,
                        :centralBeyond, :earliestBeyond, :latestBeyond,
                        :basisYear, :horizon, :intervalKind, :basis,
                        :priceMeaning, :projectionLabel, :reasonCode)
                """)
                .param("asOf", date(projection.asOfDate()))
                .param("assumption", projection.assumptionVersion())
                .param("model", projection.modelVersion())
                .param("status", projection.status())
                .param("tier", projection.sufficiencyTier())
                .param("flags", encodeFlags(projection.qualificationFlags()))
                .param("observationCount", projection.observationCount())
                .param("alpha", projection.alpha(), Types.NUMERIC)
                .param("beta", projection.beta(), Types.NUMERIC)
                .param("rSquared", projection.rSquared(), Types.NUMERIC)
                .param("current", projection.currentCumulativeLaunches())
                .param("centralCadence", projection.centralCadence(), Types.NUMERIC)
                .param("fastCadence", projection.fastCadence(), Types.NUMERIC)
                .param("slowCadence", projection.slowCadence(), Types.NUMERIC)
                .param("centralTarget", projection.centralTargetUsdPerKg())
                .param("easyTarget", projection.easyTargetUsdPerKg())
                .param("hardTarget", projection.hardTargetUsdPerKg())
                .param("centralRequired", projection.centralRequiredLaunches(), Types.NUMERIC)
                .param("easyRequired", projection.easyRequiredLaunches(), Types.NUMERIC)
                .param("hardRequired", projection.hardRequiredLaunches(), Types.NUMERIC)
                .param("centralEta", projection.centralEtaYear(), Types.NUMERIC)
                .param("earliestEta", projection.earliestEtaYear(), Types.NUMERIC)
                .param("latestEta", projection.latestEtaYear(), Types.NUMERIC)
                .param("centralBeyond", yesNo(projection.centralBeyondHorizon()))
                .param("earliestBeyond", yesNo(projection.earliestBeyondHorizon()))
                .param("latestBeyond", yesNo(projection.latestBeyondHorizon()))
                .param("basisYear", projection.priceBasisYear())
                .param("horizon", projection.horizonYears())
                .param("intervalKind", projection.intervalKind())
                .param("basis", projection.basis())
                .param("priceMeaning", projection.priceMeaning())
                .param("projectionLabel", projection.projectionLabel())
                .param("reasonCode", projection.reasonCode())
                .update();
    }

    public Optional<TransportProjection> findLatestProjection() {
        return jdbc.sql(PROJECTION_SELECT + """
                 ORDER BY as_of_date DESC, id DESC
                 FETCH FIRST 1 ROWS ONLY
                """)
                .query(TransportEconomicsRepository::mapProjection)
                .optional();
    }

    public long saveCoherenceReport(TransportCoherenceReport report) {
        Optional<TransportCoherenceReport> existing = findReport(report.reportPeriodEnd());
        if (existing.isPresent()) {
            if (!withoutId(existing.get()).equals(withoutId(report))) {
                throw new IllegalStateException(
                        "Transport coherence report changed for " + report.reportPeriodEnd());
            }
            return existing.get().id();
        }
        jdbc.sql("""
                INSERT INTO transport_coherence_report
                  (report_period_end, layer_c_snapshot_date, price_direction,
                   cadence_direction, layer_b_direction, layer_c_direction,
                   coherence_state, polarity, consecutive_quarter_streak,
                   alert_active, widening_factor, first_divergent_period)
                VALUES (:periodEnd, :snapshotDate, :priceDirection,
                        :cadenceDirection, :layerBDirection, :layerCDirection,
                        :state, :polarity, :streak, :alert, :widening,
                        :firstDivergent)
                """)
                .param("periodEnd", date(report.reportPeriodEnd()))
                .param("snapshotDate", date(report.layerCSnapshotDate()), Types.DATE)
                .param("priceDirection", report.priceDirection())
                .param("cadenceDirection", report.cadenceDirection())
                .param("layerBDirection", report.layerBDirection())
                .param("layerCDirection", report.layerCDirection())
                .param("state", report.state())
                .param("polarity", report.polarity())
                .param("streak", report.consecutiveQuarterStreak())
                .param("alert", yesNo(report.alertActive()))
                .param("widening", report.wideningFactor())
                .param("firstDivergent", date(report.firstDivergentPeriod()), Types.DATE)
                .update();
        return findReport(report.reportPeriodEnd()).orElseThrow().id();
    }

    public Optional<TransportCoherenceReport> findLatestCoherenceReport() {
        return jdbc.sql(REPORT_SELECT + """
                 ORDER BY report_period_end DESC, id DESC
                 FETCH FIRST 1 ROWS ONLY
                """)
                .query(TransportEconomicsRepository::mapReport)
                .optional();
    }

    public Optional<TransportCoherenceReport> findPreviousCoherenceReport(
            LocalDate beforePeriodEnd) {
        return jdbc.sql(REPORT_SELECT + """
                 WHERE report_period_end < :periodEnd
                 ORDER BY report_period_end DESC, id DESC
                 FETCH FIRST 1 ROWS ONLY
                """)
                .param("periodEnd", date(beforePeriodEnd))
                .query(TransportEconomicsRepository::mapReport)
                .optional();
    }

    public long insertSample(long reportId, long eventId) {
        Optional<Long> existing = jdbc.sql("""
                SELECT id FROM transport_coherence_sample
                 WHERE report_id = :reportId AND event_id = :eventId
                """)
                .param("reportId", reportId)
                .param("eventId", eventId)
                .query(Long.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        jdbc.sql("""
                INSERT INTO transport_coherence_sample (report_id, event_id)
                VALUES (:reportId, :eventId)
                """)
                .param("reportId", reportId)
                .param("eventId", eventId)
                .update();
        return jdbc.sql("""
                SELECT id FROM transport_coherence_sample
                 WHERE report_id = :reportId AND event_id = :eventId
                """)
                .param("reportId", reportId)
                .param("eventId", eventId)
                .query(Long.class)
                .single();
    }

    public List<TransportCoherenceSample> findSamples(long reportId) {
        return jdbc.sql("""
                SELECT id, report_id, event_id, review_status, reviewer_note, reviewed_at
                  FROM transport_coherence_sample
                 WHERE report_id = :reportId
                 ORDER BY id
                """)
                .param("reportId", reportId)
                .query((rs, rowNum) -> new TransportCoherenceSample(
                        rs.getLong("id"), rs.getLong("report_id"),
                        rs.getLong("event_id"), rs.getString("review_status"),
                        rs.getString("reviewer_note"),
                        instant(rs.getTimestamp("reviewed_at"))))
                .list();
    }

    public boolean reviewSample(long sampleId, String note) {
        return jdbc.sql("""
                UPDATE transport_coherence_sample
                   SET review_status = 'REVIEWED', reviewer_note = :note,
                       reviewed_at = CURRENT_TIMESTAMP
                 WHERE id = :id AND review_status = 'PENDING'
                """)
                .param("note", note)
                .param("id", sampleId)
                .update() == 1;
    }

    public Optional<String> findImportSha(String datasetVersion) {
        return jdbc.sql("""
                SELECT dataset_sha256 FROM transport_economics_import
                 WHERE dataset_version = :version
                """)
                .param("version", datasetVersion)
                .query(String.class)
                .optional();
    }

    public void recordImport(
            String version, String sha256, int priceCount, int launchCount, int cpiCount) {
        jdbc.sql("""
                INSERT INTO transport_economics_import
                  (dataset_version, dataset_sha256, price_observation_count,
                   annual_launch_record_count, cpi_record_count)
                VALUES (:version, :hash, :prices, :launches, :cpi)
                """)
                .param("version", version)
                .param("hash", sha256)
                .param("prices", priceCount)
                .param("launches", launchCount)
                .param("cpi", cpiCount)
                .update();
    }

    private Optional<TransportPriceObservation> findObservation(
            int year, String family, String variant) {
        return jdbc.sql("""
                SELECT id, observation_year, vehicle_family, vehicle_variant,
                       published_price_usd, max_leo_payload_kg, nominal_usd_per_kg,
                       cpi_observation_value, cpi_basis_value, real_basis_usd_per_kg,
                       cumulative_family_launches, source_label, source_url,
                       source_locator, accessed_on, content_sha256, fact_summary
                  FROM transport_price_observation
                 WHERE observation_year = :year AND vehicle_family = :family
                   AND vehicle_variant = :variant
                """)
                .param("year", year)
                .param("family", family)
                .param("variant", variant)
                .query(TransportEconomicsRepository::mapObservation)
                .optional();
    }

    private Optional<TransportProjection> findProjection(
            LocalDate asOfDate, String assumptionVersion) {
        return jdbc.sql(PROJECTION_SELECT + """
                 WHERE as_of_date = :asOf AND assumption_version = :assumption
                """)
                .param("asOf", date(asOfDate))
                .param("assumption", assumptionVersion)
                .query(TransportEconomicsRepository::mapProjection)
                .optional();
    }

    private Optional<TransportCoherenceReport> findReport(LocalDate periodEnd) {
        return jdbc.sql(REPORT_SELECT + " WHERE report_period_end = :periodEnd")
                .param("periodEnd", date(periodEnd))
                .query(TransportEconomicsRepository::mapReport)
                .optional();
    }

    private static TransportPriceObservation mapObservation(ResultSet rs, int rowNum)
            throws SQLException {
        return new TransportPriceObservation(
                rs.getLong("id"), rs.getInt("observation_year"),
                rs.getString("vehicle_family"), rs.getString("vehicle_variant"),
                rs.getBigDecimal("published_price_usd"),
                rs.getBigDecimal("max_leo_payload_kg"),
                rs.getBigDecimal("nominal_usd_per_kg"),
                rs.getBigDecimal("cpi_observation_value"),
                rs.getBigDecimal("cpi_basis_value"),
                rs.getBigDecimal("real_basis_usd_per_kg"),
                rs.getLong("cumulative_family_launches"),
                rs.getString("source_label"), rs.getString("source_url"),
                rs.getString("source_locator"), rs.getDate("accessed_on").toLocalDate(),
                rs.getString("content_sha256"), rs.getString("fact_summary"));
    }

    private static TransportProjection mapProjection(ResultSet rs, int rowNum)
            throws SQLException {
        return new TransportProjection(
                rs.getLong("id"), rs.getDate("as_of_date").toLocalDate(),
                rs.getString("assumption_version"), rs.getString("model_version"),
                rs.getString("status"), rs.getString("sufficiency_tier"),
                decodeFlags(rs.getString("qualification_flags")),
                rs.getInt("observation_count"), nullableDouble(rs, "alpha"),
                nullableDouble(rs, "beta"), nullableDouble(rs, "r_squared"),
                rs.getLong("current_cumulative_launches"),
                nullableDouble(rs, "central_cadence"),
                nullableDouble(rs, "fast_cadence"),
                nullableDouble(rs, "slow_cadence"),
                rs.getBigDecimal("central_target_usd_per_kg"),
                rs.getBigDecimal("easy_target_usd_per_kg"),
                rs.getBigDecimal("hard_target_usd_per_kg"),
                nullableDouble(rs, "central_required_launches"),
                nullableDouble(rs, "easy_required_launches"),
                nullableDouble(rs, "hard_required_launches"),
                nullableDouble(rs, "central_eta_year"),
                nullableDouble(rs, "earliest_eta_year"),
                nullableDouble(rs, "latest_eta_year"),
                "Y".equals(rs.getString("central_beyond_horizon")),
                "Y".equals(rs.getString("earliest_beyond_horizon")),
                "Y".equals(rs.getString("latest_beyond_horizon")),
                rs.getInt("price_basis_year"), rs.getInt("horizon_years"),
                rs.getString("interval_kind"), rs.getString("basis"),
                rs.getString("price_meaning"), rs.getString("projection_label"),
                rs.getString("reason_code"));
    }

    private static TransportCoherenceReport mapReport(ResultSet rs, int rowNum)
            throws SQLException {
        return new TransportCoherenceReport(
                rs.getLong("id"), rs.getDate("report_period_end").toLocalDate(),
                localDate(rs.getDate("layer_c_snapshot_date")),
                rs.getString("price_direction"), rs.getString("cadence_direction"),
                rs.getString("layer_b_direction"), rs.getString("layer_c_direction"),
                rs.getString("coherence_state"), rs.getString("polarity"),
                rs.getInt("consecutive_quarter_streak"),
                "Y".equals(rs.getString("alert_active")),
                rs.getBigDecimal("widening_factor"),
                localDate(rs.getDate("first_divergent_period")));
    }

    private static boolean sameAssumption(
            TransportAssumption left, TransportAssumption right) {
        return Objects.equals(left.version(), right.version())
                && Objects.equals(left.modelVersion(), right.modelVersion())
                && decimalEquals(left.centralTargetUsdPerKg(), right.centralTargetUsdPerKg())
                && decimalEquals(left.easyTargetUsdPerKg(), right.easyTargetUsdPerKg())
                && decimalEquals(left.hardTargetUsdPerKg(), right.hardTargetUsdPerKg())
                && left.priceBasisYear() == right.priceBasisYear()
                && left.horizonYears() == right.horizonYears()
                && decimalEquals(left.weakFitR2(), right.weakFitR2())
                && decimalEquals(left.wideningFactor(), right.wideningFactor());
    }

    private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static boolean sameObservation(
            TransportPriceObservation left, TransportPriceObservation right) {
        return left.observationYear() == right.observationYear()
                && Objects.equals(left.vehicleFamily(), right.vehicleFamily())
                && Objects.equals(left.vehicleVariant(), right.vehicleVariant())
                && decimalEquals(left.publishedPriceUsd(), right.publishedPriceUsd())
                && decimalEquals(left.maxLeoPayloadKg(), right.maxLeoPayloadKg())
                && decimalEquals(left.nominalUsdPerKg(), right.nominalUsdPerKg())
                && decimalEquals(left.cpiObservationValue(), right.cpiObservationValue())
                && decimalEquals(left.cpiBasisValue(), right.cpiBasisValue())
                && decimalEquals(left.realBasisUsdPerKg(), right.realBasisUsdPerKg())
                && left.cumulativeFamilyLaunches() == right.cumulativeFamilyLaunches()
                && Objects.equals(left.sourceLabel(), right.sourceLabel())
                && Objects.equals(left.sourceUrl(), right.sourceUrl())
                && Objects.equals(left.sourceLocator(), right.sourceLocator())
                && Objects.equals(left.accessedOn(), right.accessedOn())
                && Objects.equals(left.contentSha256(), right.contentSha256())
                && Objects.equals(left.factSummary(), right.factSummary());
    }

    private static TransportProjection withoutId(TransportProjection value) {
        return new TransportProjection(
                0, value.asOfDate(), value.assumptionVersion(), value.modelVersion(),
                value.status(), value.sufficiencyTier(), value.qualificationFlags(),
                value.observationCount(), value.alpha(), value.beta(), value.rSquared(),
                value.currentCumulativeLaunches(), value.centralCadence(), value.fastCadence(),
                value.slowCadence(), value.centralTargetUsdPerKg(), value.easyTargetUsdPerKg(),
                value.hardTargetUsdPerKg(), value.centralRequiredLaunches(),
                value.easyRequiredLaunches(), value.hardRequiredLaunches(),
                value.centralEtaYear(), value.earliestEtaYear(), value.latestEtaYear(),
                value.centralBeyondHorizon(), value.earliestBeyondHorizon(),
                value.latestBeyondHorizon(), value.priceBasisYear(), value.horizonYears(),
                value.intervalKind(), value.basis(), value.priceMeaning(),
                value.projectionLabel(), value.reasonCode());
    }

    private static TransportCoherenceReport withoutId(TransportCoherenceReport value) {
        return new TransportCoherenceReport(
                0, value.reportPeriodEnd(), value.layerCSnapshotDate(),
                value.priceDirection(), value.cadenceDirection(), value.layerBDirection(),
                value.layerCDirection(), value.state(), value.polarity(),
                value.consecutiveQuarterStreak(), value.alertActive(),
                value.wideningFactor(), value.firstDivergentPeriod());
    }

    private static String encodeFlags(List<String> flags) {
        return flags == null || flags.isEmpty() ? "NONE" : String.join(",", flags);
    }

    private static List<String> decodeFlags(String value) {
        if (value == null || value.isBlank() || "NONE".equals(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static Date date(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    private static LocalDate localDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static String yesNo(boolean value) {
        return value ? "Y" : "N";
    }
}
