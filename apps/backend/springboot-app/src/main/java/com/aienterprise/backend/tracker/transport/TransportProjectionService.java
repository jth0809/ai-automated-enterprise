package com.aienterprise.backend.tracker.transport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runs and persists a projection only after runtime assumptions match the ledger. */
@Service
public class TransportProjectionService {

    static final String ASSUMPTION_VERSION = "transport-assumptions-v1";

    private final TransportEconomicsRepository repository;
    private final WrightProjectionCalculator calculator;

    @Autowired
    public TransportProjectionService(TransportEconomicsRepository repository) {
        this(repository, new WrightProjectionCalculator());
    }

    TransportProjectionService(
            TransportEconomicsRepository repository,
            WrightProjectionCalculator calculator) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
    }

    @Transactional
    public TransportProjection run(
            LocalDate asOfDate,
            BigDecimal central,
            BigDecimal easy,
            BigDecimal hard) {
        Objects.requireNonNull(asOfDate, "asOfDate");
        TransportAssumption assumption = repository.findAssumption(ASSUMPTION_VERSION)
                .orElseThrow(() -> new IllegalStateException(
                        "transport assumption missing: " + ASSUMPTION_VERSION));
        requireRuntimeMatch(assumption, central, easy, hard);
        TransportProjection projection = calculator.calculate(
                asOfDate, assumption, repository.findPriceObservations(),
                repository.findAnnualFalconLaunchCounts());
        repository.saveProjection(projection);
        return projection;
    }

    private static void requireRuntimeMatch(
            TransportAssumption assumption,
            BigDecimal central,
            BigDecimal easy,
            BigDecimal hard) {
        if (!sameNumber(assumption.centralTargetUsdPerKg(), central)
                || !sameNumber(assumption.easyTargetUsdPerKg(), easy)
                || !sameNumber(assumption.hardTargetUsdPerKg(), hard)) {
            throw new IllegalStateException(
                    "runtime transport targets do not match " + assumption.version());
        }
    }

    private static boolean sameNumber(BigDecimal expected, BigDecimal actual) {
        return expected != null && actual != null && expected.compareTo(actual) == 0;
    }
}
