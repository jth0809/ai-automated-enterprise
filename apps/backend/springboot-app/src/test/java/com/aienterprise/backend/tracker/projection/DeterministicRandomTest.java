package com.aienterprise.backend.tracker.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeterministicRandomTest {

    @Test
    void sameSeedProducesSameUniformGaussianAndGammaSequence() {
        DeterministicRandom first = new DeterministicRandom(42L);
        DeterministicRandom second = new DeterministicRandom(42L);

        for (int index = 0; index < 100; index++) {
            assertEquals(first.uniform(), second.uniform());
            assertEquals(first.gaussian(), second.gaussian());
            assertEquals(first.gamma(0.4), second.gamma(0.4));
            assertEquals(first.gamma(3.0), second.gamma(3.0));
        }
        assertNotEquals(new DeterministicRandom(1L).uniform(),
                new DeterministicRandom(2L).uniform());
    }

    @Test
    void boundedAndPositiveDrawsRespectTheirContracts() {
        DeterministicRandom random = new DeterministicRandom(7L);
        for (int index = 0; index < 1_000; index++) {
            double bounded = random.boundedNormal(0.5, 0.2, 0.1, 0.8);
            assertTrue(bounded >= 0.1 && bounded <= 0.8);
            assertTrue(random.gamma(0.2) > 0);
            assertTrue(random.gamma(2.0) > 0);
        }
    }
}
