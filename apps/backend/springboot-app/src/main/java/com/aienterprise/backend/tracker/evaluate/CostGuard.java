package com.aienterprise.backend.tracker.evaluate;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class CostGuard {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final CostRates rates;

    @Autowired
    public CostGuard(
            JdbcClient jdbc,
            @Value("${tracker.cost-per-mtok.input:15}") double inputPrice,
            @Value("${tracker.cost-per-mtok.output:75}") double outputPrice,
            @Value("${tracker.cost-per-mtok.cached-input:1.5}") double cachedInputPrice) {
        this(jdbc, Clock.systemUTC(), new CostRates(inputPrice, outputPrice, cachedInputPrice));
    }

    public CostGuard(JdbcClient jdbc, Clock clock, CostRates rates) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.rates = rates;
    }

    public boolean allow() {
        double cap = jdbc.sql("SELECT daily_cost_cap_usd FROM parameter_set WHERE active = 'Y'")
                .query(Double.class)
                .single();
        double spent = jdbc.sql("""
                SELECT COALESCE(SUM(est_cost_usd), 0)
                  FROM llm_usage
                 WHERE usage_date = :usageDate
                """)
                .param("usageDate", Date.valueOf(today()))
                .query(Double.class)
                .single();
        return spent < cap;
    }

    @Transactional
    public void record(String model, int inputTokens, int outputTokens, int cachedTokens) {
        double cost = rates.estimate(inputTokens, outputTokens, cachedTokens);
        Date usageDate = Date.valueOf(today());
        int changed = increment(model, usageDate, inputTokens, outputTokens, cachedTokens, cost);
        if (changed == 1) {
            return;
        }
        try {
            jdbc.sql("""
                    INSERT INTO llm_usage
                      (usage_date, model, calls, input_tokens, output_tokens, cached_tokens, est_cost_usd)
                    VALUES (:usageDate, :model, 1, :inputTokens, :outputTokens, :cachedTokens, :cost)
                    """)
                    .param("usageDate", usageDate)
                    .param("model", model)
                    .param("inputTokens", inputTokens)
                    .param("outputTokens", outputTokens)
                    .param("cachedTokens", cachedTokens)
                    .param("cost", cost)
                    .update();
        } catch (DuplicateKeyException concurrentInsert) {
            increment(model, usageDate, inputTokens, outputTokens, cachedTokens, cost);
        }
    }

    private int increment(
            String model,
            Date usageDate,
            int inputTokens,
            int outputTokens,
            int cachedTokens,
            double cost) {
        return jdbc.sql("""
                UPDATE llm_usage
                   SET calls = calls + 1,
                       input_tokens = input_tokens + :inputTokens,
                       output_tokens = output_tokens + :outputTokens,
                       cached_tokens = cached_tokens + :cachedTokens,
                       est_cost_usd = est_cost_usd + :cost
                 WHERE usage_date = :usageDate AND model = :model
                """)
                .param("inputTokens", inputTokens)
                .param("outputTokens", outputTokens)
                .param("cachedTokens", cachedTokens)
                .param("cost", cost)
                .param("usageDate", usageDate)
                .param("model", model)
                .update();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
