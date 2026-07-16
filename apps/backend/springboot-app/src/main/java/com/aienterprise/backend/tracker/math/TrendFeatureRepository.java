package com.aienterprise.backend.tracker.math;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class TrendFeatureRepository {

    private final JdbcClient jdbc;

    public TrendFeatureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<StateChangeEvent> findStateChanges(int pillar, LocalDate asOf) {
        requirePillarAndDate(pillar, asOf);
        List<StateChangeEvent> results = jdbc.sql("""
                SELECT e.id AS event_id, n.pillar, e.occurred_on, e.event_type,
                       h.prev_level, h.new_level, h.prev_status, h.new_status
                  FROM node_state_history h
                  JOIN event e ON e.id = h.cause_event_id
                  JOIN capability_node n ON n.id = h.node_id
                 WHERE n.pillar = :pillar
                   AND e.event_status = 'CONFIRMED'
                   AND e.occurred_on <= :asOf
                   AND (h.prev_level <> h.new_level OR h.prev_status <> h.new_status)
                 ORDER BY e.occurred_on, e.id, h.id
                """)
                .param("pillar", pillar)
                .param("asOf", Date.valueOf(asOf))
                .query((rs, rowNum) -> new StateChangeEvent(
                        rs.getLong("event_id"),
                        rs.getInt("pillar"),
                        rs.getDate("occurred_on").toLocalDate(),
                        rs.getString("event_type"),
                        rs.getInt("prev_level"),
                        rs.getInt("new_level"),
                        rs.getString("prev_status"),
                        rs.getString("new_status")))
                .list();
        if (results.stream().anyMatch(event -> event.occurredOn().isAfter(asOf))) {
            throw new IllegalStateException("state-change query leaked beyond cutoff");
        }
        return List.copyOf(results);
    }

    public Optional<RegimeBreak> findLatestApprovedBreak(
            int pillar, LocalDate asOf, String paramsVersion) {
        requirePillarAndDate(pillar, asOf);
        if (paramsVersion == null || paramsVersion.isBlank()) {
            throw new IllegalArgumentException("paramsVersion is required");
        }
        Optional<RegimeBreak> result = jdbc.sql("""
                SELECT id, pillar, break_date, cause_event_id, params_version
                  FROM model_regime_break
                 WHERE pillar = :pillar
                   AND break_date <= :asOf
                   AND review_status = 'APPROVED'
                   AND params_version = :paramsVersion
                 ORDER BY break_date DESC, id DESC
                 FETCH FIRST 1 ROWS ONLY
                """)
                .param("pillar", pillar)
                .param("asOf", Date.valueOf(asOf))
                .param("paramsVersion", paramsVersion)
                .query((rs, rowNum) -> new RegimeBreak(
                        rs.getLong("id"),
                        rs.getInt("pillar"),
                        rs.getDate("break_date").toLocalDate(),
                        rs.getLong("cause_event_id"),
                        rs.getString("params_version")))
                .optional();
        if (result.isPresent() && result.get().breakDate().isAfter(asOf)) {
            throw new IllegalStateException("regime-break query leaked beyond cutoff");
        }
        return result;
    }

    public List<RegimeBreak> findApprovedBreaks(
            LocalDate asOf, String paramsVersion) {
        if (asOf == null || paramsVersion == null || paramsVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "asOf and paramsVersion are required");
        }
        List<RegimeBreak> results = jdbc.sql("""
                SELECT id, pillar, break_date, cause_event_id, params_version
                  FROM model_regime_break
                 WHERE break_date <= :asOf
                   AND review_status = 'APPROVED'
                   AND params_version = :paramsVersion
                 ORDER BY pillar, break_date, id
                """)
                .param("asOf", Date.valueOf(asOf))
                .param("paramsVersion", paramsVersion)
                .query((rs, rowNum) -> new RegimeBreak(
                        rs.getLong("id"),
                        rs.getInt("pillar"),
                        rs.getDate("break_date").toLocalDate(),
                        rs.getLong("cause_event_id"),
                        rs.getString("params_version")))
                .list();
        if (results.stream().anyMatch(value -> value.breakDate().isAfter(asOf))) {
            throw new IllegalStateException("regime-break query leaked beyond cutoff");
        }
        return List.copyOf(results);
    }

    private static void requirePillarAndDate(int pillar, LocalDate asOf) {
        if (pillar < 1 || pillar > 6 || asOf == null) {
            throw new IllegalArgumentException("valid pillar and asOf are required");
        }
    }
}
