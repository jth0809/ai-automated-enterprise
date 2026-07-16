package com.aienterprise.backend.tracker.transport;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.domain.SnapshotRow;

/** Applies divergence widening only to API output; persistence is untouched. */
@Component
public class TransportEtaOverlay {

    private static final int DISPLAY_HORIZON_YEARS = 150;

    public EtaBounds apply(
            int pillar,
            SnapshotRow snapshot,
            TransportCoherenceReport report,
            int nowYear) {
        Double baseLow = snapshot == null ? null : snapshot.etaLow();
        Double baseHigh = snapshot == null ? null : snapshot.etaHigh();
        if (!canAdjust(pillar, snapshot, report, nowYear)) {
            return new EtaBounds(
                    baseLow, baseHigh, baseLow, baseHigh, false, null);
        }

        double eta = snapshot.etaYear();
        double factor = report.wideningFactor().doubleValue();
        double widenedLow = Math.max(nowYear,
                eta - factor * (eta - baseLow));
        double widenedHigh = Math.min(nowYear + DISPLAY_HORIZON_YEARS,
                eta + factor * (baseHigh - eta));
        return new EtaBounds(
                baseLow, baseHigh, roundOneDecimal(widenedLow),
                roundOneDecimal(widenedHigh), true, report.reportPeriodEnd());
    }

    private static boolean canAdjust(
            int pillar,
            SnapshotRow snapshot,
            TransportCoherenceReport report,
            int nowYear) {
        if (pillar != 1 || snapshot == null || report == null
                || !report.alertActive() || !"DIVERGENT".equals(report.state())
                || report.reportPeriodEnd() == null
                || report.wideningFactor() == null
                || snapshot.etaYear() == null
                || snapshot.etaLow() == null || snapshot.etaHigh() == null) {
            return false;
        }
        double eta = snapshot.etaYear();
        double low = snapshot.etaLow();
        double high = snapshot.etaHigh();
        double factor = report.wideningFactor().doubleValue();
        return nowYear > 0
                && Double.isFinite(eta) && Double.isFinite(low)
                && Double.isFinite(high) && Double.isFinite(factor)
                && low <= eta && eta <= high && factor > 1.0;
    }

    private static double roundOneDecimal(double value) {
        return BigDecimal.valueOf(value)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
