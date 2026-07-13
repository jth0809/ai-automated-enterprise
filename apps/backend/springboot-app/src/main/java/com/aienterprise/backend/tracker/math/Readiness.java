package com.aienterprise.backend.tracker.math;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.aienterprise.backend.tracker.domain.NodeRow;

public final class Readiness {

    private Readiness() {
    }

    public static double nodeReadiness(
            int level,
            boolean dormant,
            LocalDate dormantSince,
            Params params) {
        return nodeReadiness(level, dormant, dormantSince, "TRL", params, LocalDate.now(ZoneOffset.UTC));
    }

    public static double pillarReadiness(List<NodeRow> nodes, Params params) {
        LocalDate asOf = LocalDate.now(ZoneOffset.UTC);
        double readiness = 0;
        for (NodeRow node : nodes) {
            LocalDate dormancyOrigin = node.programEndDate() == null
                    ? node.dormantSince()
                    : node.programEndDate();
            readiness += node.weight() * nodeReadiness(
                    node.currentLevel(),
                    "DORMANT".equals(node.nodeStatus()),
                    dormancyOrigin,
                    node.scaleType(),
                    params,
                    asOf);
        }
        return readiness;
    }

    public static double nodeReadiness(
            int level,
            boolean dormant,
            LocalDate dormantSince,
            String scaleType,
            Params params,
            LocalDate asOf) {
        if (level == 0) {
            return 0;
        }
        Map<Integer, Double> mapping = "EGL".equals(scaleType)
                ? params.maturityMap()
                : params.trlMap();
        Double base = mapping.get(level);
        if (base == null) {
            throw new IllegalArgumentException("No readiness mapping for level " + level);
        }
        if (!dormant) {
            return base;
        }
        if (dormantSince == null) {
            throw new IllegalArgumentException("dormantSince is required for a dormant capability");
        }
        long inactiveYears = Math.max(0, ChronoUnit.YEARS.between(dormantSince, asOf));
        long decadesAfterTrigger = Math.max(0, inactiveYears - params.dormancyTriggerYears()) / 10;
        double factor = Math.max(
                params.dormancyFloor(),
                params.dormancyStart() - decadesAfterTrigger * params.dormancyStepPerDecade());
        return base * factor;
    }
}
