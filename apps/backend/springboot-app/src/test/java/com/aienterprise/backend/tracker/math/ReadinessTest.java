package com.aienterprise.backend.tracker.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.domain.NodeRow;

class ReadinessTest {

    private final Params params = Params.defaults();

    @Test
    void levelSevenMapsToPointSixFive() {
        assertEquals(0.65, Readiness.nodeReadiness(7, false, null, params), 0.0001);
    }

    @Test
    void dormancyAttenuationUsesTriggerAndFloor() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        assertEquals(0.65 * 0.70,
                Readiness.nodeReadiness(7, true, today.minusYears(25), params), 0.0001);
        assertEquals(0.65 * 0.40,
                Readiness.nodeReadiness(7, true, today.minusYears(45), params), 0.0001);
    }

    @Test
    void pillarReadinessIsWeightedAndUsesEglMap() {
        NodeRow first = node(1, "TRL", 7, 0.25);
        NodeRow second = node(2, "EGL", 5, 0.75);

        assertEquals(0.25 * 0.65 + 0.75 * 0.30,
                Readiness.pillarReadiness(List.of(first, second), params), 0.0001);
    }

    @Test
    void pillarDormancyUsesProgramEndAsTheAttenuationOrigin() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        NodeRow dormant = new NodeRow(
                1, "N1", 1, "node", "TRL", 7, "OFFICIAL",
                "DORMANT", today.minusYears(10), today.minusYears(25),
                1.0, false, null, "nodes-v1.0");

        assertEquals(0.65 * 0.70,
                Readiness.pillarReadiness(List.of(dormant), params), 0.0001);
    }

    private NodeRow node(long id, String scale, int level, double weight) {
        return new NodeRow(id, "N" + id, 1, "node", scale, level, null,
                "ACTIVE", null, null, weight, false, null, "nodes-v0.1");
    }
}
