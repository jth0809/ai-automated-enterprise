package com.aienterprise.backend.tracker.kindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class KIndexCalculatorTest {

    private final KIndexCalculator calculator = new KIndexCalculator();

    @Test
    void calculatesKardashevGaugeFromAnnualPrimaryEnergy() {
        KIndexCalculator.Calculation result = calculator.calculate(
                new BigDecimal("176737.1"));

        assertEquals(20_175_468_036_530L, result.powerWatts());
        assertEquals(new BigDecimal("0.7305"), result.kValue());
        assertEquals(new BigDecimal("0.2695"), result.typeOneGap());
        assertEquals(new BigDecimal("495.7"), result.typeOneMultiplier());
    }

    @Test
    void rejectsZeroPrimaryEnergy() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(BigDecimal.ZERO));
    }

    @Test
    void rejectsNegativePrimaryEnergy() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(new BigDecimal("-1")));
    }
}
