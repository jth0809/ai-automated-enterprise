package com.aienterprise.backend.tracker.math;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class ModelParameterRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final ModelParameterValidator validator;

    public ModelParameterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.validator = new ModelParameterValidator();
    }

    public ModelParameters loadActive() {
        List<ParameterRow> active = jdbc.sql("""
                SELECT id, version_label, epsilon, k_shrink, window_m,
                       window_fixed_years, window_min_years, window_max_years,
                       dormancy_start, dormancy_step_per_decade, dormancy_floor,
                       dormancy_trigger_years, default_delta_e,
                       eta_clamp_min_years, eta_clamp_max_years,
                       display_damping_days_per_day, daily_cost_cap_usd,
                       trl_map, maturity_map
                  FROM parameter_set
                 WHERE active = 'Y'
                 ORDER BY version_label
                """)
                .query(ModelParameterRepository::mapParameterRow)
                .list();
        if (active.size() != 1) {
            throw new IllegalStateException(
                    "exactly one active parameter set is required; found " + active.size());
        }

        ParameterRow row = active.getFirst();
        Params params = new Params(
                row.version(), row.epsilon(), row.kShrink(), row.windowM(),
                row.windowFixedYears(), row.windowMinYears(), row.windowMaxYears(),
                row.dormancyStart(), row.dormancyStepPerDecade(), row.dormancyFloor(),
                row.dormancyTriggerYears(), row.defaultDeltaE(),
                row.etaClampMinYears(), row.etaClampMaxYears(),
                row.displayDampingDaysPerDay(), row.dailyCostCapUsd(),
                parseLevelMap(row.trlMapJson(), "trl_map"),
                parseLevelMap(row.maturityMapJson(), "maturity_map"));

        Map<String, ParameterUncertainty> uncertainty = new LinkedHashMap<>();
        for (ParameterUncertainty value : jdbc.sql("""
                SELECT parameter_name, distribution_type,
                       lower_value, central_value, upper_value, scale_value
                  FROM parameter_uncertainty
                 WHERE parameter_set_id = :parameterSetId
                 ORDER BY parameter_name
                """)
                .param("parameterSetId", row.id())
                .query((rs, rowNum) -> new ParameterUncertainty(
                        rs.getString("parameter_name"),
                        rs.getString("distribution_type"),
                        rs.getDouble("lower_value"),
                        rs.getDouble("central_value"),
                        rs.getDouble("upper_value"),
                        nullableDouble(rs, "scale_value")))
                .list()) {
            if (uncertainty.put(value.name(), value) != null) {
                throw new IllegalStateException(
                        "duplicate uncertainty parameter: " + value.name());
            }
        }

        ModelParameters result = new ModelParameters(params, uncertainty);
        validator.validate(result);
        return result;
    }

    private Map<Integer, Double> parseLevelMap(String encoded, String label) {
        try {
            JsonNode root = JSON.readTree(encoded);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException(label + " must be a JSON object");
            }
            Map<Integer, Double> values = new LinkedHashMap<>();
            var fields = root.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                int level = Integer.parseInt(field.getKey());
                if (!field.getValue().isNumber()) {
                    throw new IllegalStateException(label + " values must be numeric");
                }
                if (values.put(level, field.getValue().doubleValue()) != null) {
                    throw new IllegalStateException(label + " contains duplicate level " + level);
                }
            }
            return values;
        } catch (IllegalStateException invalid) {
            throw invalid;
        } catch (Exception invalidJson) {
            throw new IllegalStateException("invalid " + label + " JSON", invalidJson);
        }
    }

    private static ParameterRow mapParameterRow(ResultSet rs, int rowNum) throws SQLException {
        return new ParameterRow(
                rs.getLong("id"),
                rs.getString("version_label"),
                rs.getDouble("epsilon"),
                rs.getDouble("k_shrink"),
                rs.getInt("window_m"),
                rs.getInt("window_fixed_years"),
                rs.getInt("window_min_years"),
                rs.getInt("window_max_years"),
                rs.getDouble("dormancy_start"),
                rs.getDouble("dormancy_step_per_decade"),
                rs.getDouble("dormancy_floor"),
                rs.getInt("dormancy_trigger_years"),
                rs.getDouble("default_delta_e"),
                rs.getInt("eta_clamp_min_years"),
                rs.getInt("eta_clamp_max_years"),
                rs.getInt("display_damping_days_per_day"),
                rs.getDouble("daily_cost_cap_usd"),
                rs.getString("trl_map"),
                rs.getString("maturity_map"));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private record ParameterRow(
            long id,
            String version,
            double epsilon,
            double kShrink,
            int windowM,
            int windowFixedYears,
            int windowMinYears,
            int windowMaxYears,
            double dormancyStart,
            double dormancyStepPerDecade,
            double dormancyFloor,
            int dormancyTriggerYears,
            double defaultDeltaE,
            int etaClampMinYears,
            int etaClampMaxYears,
            int displayDampingDaysPerDay,
            double dailyCostCapUsd,
            String trlMapJson,
            String maturityMapJson) {
    }
}
