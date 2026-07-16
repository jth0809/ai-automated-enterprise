package com.aienterprise.backend.tracker.forecast;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.aienterprise.backend.tracker.domain.SnapshotRow;
import com.aienterprise.backend.tracker.domain.TrackerRepository;
import com.aienterprise.backend.tracker.transport.TransportEconomicsRepository;
import com.aienterprise.backend.tracker.transport.TransportProjection;

/** Builds an honest matrix without converting supporting signals into target dates. */
@Service
@ConditionalOnProperty(prefix = "tracker", name = "enabled", havingValue = "true")
public class ForecastComparisonService {

    private static final int STALE_AFTER_DAYS = 45;
    private static final String LANDING = "LANDING";
    private static final String RETURN = "RETURN";
    private static final String SETTLEMENT = "SETTLEMENT";

    private final TrackerRepository tracker;
    private final TransportEconomicsRepository transport;
    private final ForecastRepository forecasts;
    private final Clock clock;
    private final boolean metaculusEnabled;
    private final boolean metaculusTermsApproved;
    private final boolean metaculusTokenConfigured;

    @Autowired
    public ForecastComparisonService(
            TrackerRepository tracker,
            TransportEconomicsRepository transport,
            ForecastRepository forecasts,
            @Value("${tracker.metaculus-enabled:false}") boolean metaculusEnabled,
            @Value("${tracker.metaculus-terms-approved:false}")
                    boolean metaculusTermsApproved,
            @Value("${tracker.metaculus-token:}") String metaculusToken) {
        this(tracker, transport, forecasts, Clock.systemUTC(),
                metaculusEnabled, metaculusTermsApproved,
                MetaculusClient.isTokenFormatValid(metaculusToken));
    }

    ForecastComparisonService(
            TrackerRepository tracker,
            TransportEconomicsRepository transport,
            ForecastRepository forecasts,
            Clock clock,
            boolean metaculusEnabled,
            boolean metaculusTermsApproved) {
        this(tracker, transport, forecasts, clock, metaculusEnabled,
                metaculusTermsApproved, false);
    }

    ForecastComparisonService(
            TrackerRepository tracker,
            TransportEconomicsRepository transport,
            ForecastRepository forecasts,
            Clock clock,
            boolean metaculusEnabled,
            boolean metaculusTermsApproved,
            boolean metaculusTokenConfigured) {
        this.tracker = tracker;
        this.transport = transport;
        this.forecasts = forecasts;
        this.clock = clock;
        this.metaculusEnabled = metaculusEnabled;
        this.metaculusTermsApproved = metaculusTermsApproved;
        this.metaculusTokenConfigured = metaculusTokenConfigured;
    }

    public ForecastComparison current() {
        LocalDate today = LocalDate.now(clock);
        List<ForecastReference> references = forecasts.findAllReferences();
        Optional<SnapshotRow> snapshot = tracker.findLatestSnapshot(0);
        Optional<TransportProjection> projection = transport.findLatestProjection();

        ForecastReference landingCrowdReference = crowdReference(references, LANDING)
                .orElse(null);
        ForecastReference settlementCrowdReference = crowdReference(
                references, SETTLEMENT).orElse(null);
        Optional<ExternalForecastObservation> landingCrowd = observation(
                landingCrowdReference);
        Optional<ExternalForecastObservation> settlementCrowd = observation(
                settlementCrowdReference);

        List<ForecastComparisonRow> rows = List.of(
                new ForecastComparisonRow(
                        LANDING,
                        "첫 유인 화성 착륙",
                        "사람이 화성 표면에 처음 성공적으로 착륙하는 시점",
                        notApplicable("정착 모델 적용 밖",
                                "트래커 모델은 첫 착륙이 아니라 자립 정착 준비도를 추정합니다."),
                        transportEstimate(projection, LANDING),
                        crowdEstimate(landingCrowdReference, landingCrowd, today),
                        institutions(references, LANDING)),
                new ForecastComparisonRow(
                        RETURN,
                        "유인 화성 임무 귀환",
                        "화성 표면 임무 뒤 승무원이 지구로 안전하게 귀환하는 시점",
                        notApplicable("독립 모델 없음",
                                "현재 트래커에는 귀환 시점을 계산하는 독립 모델이 없습니다."),
                        notApplicable("직접 적용 불가",
                                "$200/kg 수송 조건은 안전 귀환 날짜를 예측하지 않습니다."),
                        questionNotSelected(),
                        institutions(references, RETURN)),
                new ForecastComparisonRow(
                        SETTLEMENT,
                        "자립 가능한 화성 정착",
                        "외부 보급 중단에도 핵심 생존 기능을 유지하는 지속 가능한 정착 준비 시점",
                        modelEstimate(snapshot),
                        transportEstimate(projection, SETTLEMENT),
                        crowdEstimate(settlementCrowdReference, settlementCrowd, today),
                        institutions(references, SETTLEMENT)));

        return new ForecastComparison(
                comparisonStatus(references, landingCrowd, settlementCrowd, today),
                today,
                ForecastSmoother.WINDOW_DAYS,
                crowdLiveStatus(),
                rows);
    }

    private String crowdLiveStatus() {
        return metaculusEnabled && metaculusTermsApproved && metaculusTokenConfigured
                ? "ENABLED"
                : "AUTHORIZATION_REQUIRED";
    }

    private static String comparisonStatus(
            List<ForecastReference> references,
            Optional<ExternalForecastObservation> landing,
            Optional<ExternalForecastObservation> settlement,
            LocalDate today) {
        if (references.isEmpty()) {
            return "INSUFFICIENT_DATA";
        }
        if (landing.isEmpty() || settlement.isEmpty()) {
            return "PARTIAL";
        }
        boolean stale = List.of(landing.orElseThrow(), settlement.orElseThrow())
                .stream()
                .anyMatch(value -> ageDays(value.retrievedOn(), today)
                        > STALE_AFTER_DAYS);
        return stale ? "STALE" : "CURRENT";
    }

    private static long ageDays(LocalDate observedOn, LocalDate today) {
        return Math.max(0L, ChronoUnit.DAYS.between(observedOn, today));
    }

    private Optional<ExternalForecastObservation> observation(
            ForecastReference reference) {
        return reference == null
                ? Optional.empty()
                : forecasts.findLatestCrowdObservation(reference.forecastKey());
    }

    private static Optional<ForecastReference> crowdReference(
            List<ForecastReference> references,
            String track) {
        return references.stream()
                .filter(value -> "CROWD".equals(value.sourceType()))
                .filter(value -> track.equals(value.trackCode()))
                .findFirst();
    }

    private static ForecastEstimate modelEstimate(Optional<SnapshotRow> snapshot) {
        if (snapshot.isEmpty() || snapshot.get().displayedEtaYear() == null) {
            return unavailable("INSUFFICIENT_DATA", "정착 모델 데이터 없음",
                    "현재 표시 가능한 자립 정착 준비도 ETA가 없습니다.",
                    "DIRECT_PROXY");
        }
        SnapshotRow value = snapshot.get();
        return new ForecastEstimate(
                "DIRECT_PROXY",
                decimal(value.displayedEtaYear()),
                decimal(value.etaYear()),
                decimal(value.etaLow()),
                decimal(value.etaHigh()),
                "DIRECT_PROXY",
                "트래커 정착 준비도 ETA",
                "35개 역량 노드가 자립 정착 준비도에 도달하는 시점의 모델 추정입니다. 첫 착륙 날짜가 아닙니다.",
                "TRACKER_MODEL",
                null,
                value.paramsVersion(),
                value.snapshotDate(),
                null,
                false);
    }

    private static ForecastEstimate transportEstimate(
            Optional<TransportProjection> projection,
            String track) {
        if (projection.isEmpty() || projection.get().centralEtaYear() == null) {
            return unavailable("INSUFFICIENT_DATA", "$200/kg 시나리오 없음",
                    "검증 가능한 수송경제 예측이 아직 없습니다.", "SUPPORTING");
        }
        TransportProjection value = projection.get();
        return new ForecastEstimate(
                "SUPPORTING",
                decimal(value.centralEtaYear()),
                null,
                decimal(value.earliestEtaYear()),
                decimal(value.latestEtaYear()),
                "SUPPORTING",
                "중앙 $200/kg 시나리오",
                "$200/kg은 " + (LANDING.equals(track) ? "착륙" : "정착")
                        + "을 가능하게 하는 수송 조건이며 그 사건 자체의 날짜가 아닙니다. 민감도는 $100~$500/kg입니다.",
                "TRANSPORT_ECONOMICS",
                null,
                value.assumptionVersion(),
                value.asOfDate(),
                null,
                false);
    }

    private static ForecastEstimate crowdEstimate(
            ForecastReference reference,
            Optional<ExternalForecastObservation> observation,
            LocalDate today) {
        if (reference == null) {
            return unavailable("INSUFFICIENT_DATA", "검토된 질문 없음",
                    "이 비교 대상에 연결된 검토 완료 군중예측 질문이 없습니다.",
                    "NONE");
        }
        if (observation.isEmpty()) {
            return new ForecastEstimate(
                    reference.displayStatus(),
                    null,
                    null,
                    null,
                    null,
                    reference.relationKind(),
                    "API 승인 대기",
                    crowdDetail(reference),
                    reference.sourceName(),
                    reference.sourceUrl(),
                    reference.sourceLocator(),
                    null,
                    reference.accessedOn(),
                    false);
        }
        ExternalForecastObservation value = observation.orElseThrow();
        boolean stale = ageDays(value.retrievedOn(), today) > STALE_AFTER_DAYS;
        return new ForecastEstimate(
                stale ? "STALE" : "CURRENT",
                value.smoothedYear(),
                value.forecastYear(),
                null,
                null,
                reference.relationKind(),
                "90일 이동평균",
                crowdDetail(reference),
                reference.sourceName(),
                reference.sourceUrl(),
                reference.sourceLocator(),
                value.retrievedOn(),
                reference.accessedOn(),
                false);
    }

    private static String crowdDetail(ForecastReference reference) {
        return "PROXY".equals(reference.relationKind())
                ? "화성 인구 100명 시점은 자립 정착보다 약한 프록시이며 같은 목표로 취급하지 않습니다."
                : "첫 유인 화성 착륙 질문의 승인된 군중예측 관측입니다.";
    }

    private static ForecastEstimate questionNotSelected() {
        return unavailable("QUESTION_NOT_SELECTED", "검토 질문 미선정",
                "안전 귀환을 직접 다루는 안정적인 질문이 검토되기 전에는 값을 표시하지 않습니다.",
                "NONE");
    }

    private static List<ForecastEstimate> institutions(
            List<ForecastReference> references,
            String track) {
        List<ForecastEstimate> result = new ArrayList<>();
        references.stream()
                .filter(value -> "INSTITUTIONAL".equals(value.sourceType()))
                .filter(value -> track.equals(value.trackCode()))
                .sorted(Comparator.comparing(ForecastReference::sourceName)
                        .thenComparing(ForecastReference::forecastKey))
                .map(ForecastComparisonService::institutionalEstimate)
                .forEach(result::add);
        return List.copyOf(result);
    }

    private static ForecastEstimate institutionalEstimate(ForecastReference value) {
        boolean legacy = "LEGACY".equals(value.displayStatus());
        String label = switch (value.displayStatus()) {
            case "LEGACY" -> value.sourceName() + " 과거 목표";
            case "PRECURSOR" -> value.sourceName() + " 선행 목표";
            default -> value.sourceName() + " 현재 공식 설명";
        };
        return new ForecastEstimate(
                value.displayStatus(),
                value.forecastYear(),
                null,
                value.forecastYearLow(),
                value.forecastYearHigh(),
                value.relationKind(),
                label,
                value.factSummary(),
                value.sourceName(),
                value.sourceUrl(),
                value.sourceLocator(),
                null,
                value.accessedOn(),
                legacy);
    }

    private static ForecastEstimate notApplicable(String label, String detail) {
        return unavailable("NOT_APPLICABLE", label, detail, "NONE");
    }

    private static ForecastEstimate unavailable(
            String status,
            String label,
            String detail,
            String relationKind) {
        return new ForecastEstimate(status, null, null, null, null,
                relationKind, label, detail, null, null, null,
                null, null, false);
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
