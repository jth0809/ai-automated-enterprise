package com.aienterprise.backend.tracker.ingest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.forecast.ExternalForecastObservation;
import com.aienterprise.backend.tracker.forecast.ForecastReference;
import com.aienterprise.backend.tracker.forecast.ForecastRepository;
import com.aienterprise.backend.tracker.forecast.ForecastSmoother;
import com.aienterprise.backend.tracker.forecast.MetaculusClient;
import com.aienterprise.backend.tracker.forecast.MetaculusSnapshot;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** Weekly crowd observation path, dark unless terms, token, and feature gates pass. */
@Component
@ConditionalOnProperty(
        prefix = "tracker",
        name = { "enabled", "metaculus-enabled", "metaculus-terms-approved" },
        havingValue = "true")
@Conditional(MetaculusForecastJob.TokenConfiguredCondition.class)
public class MetaculusForecastJob {

    private static final Logger log = LoggerFactory.getLogger(MetaculusForecastJob.class);
    private static final int HARD_MAX_POSTS = 2;

    @FunctionalInterface
    interface SnapshotFetcher {
        Optional<MetaculusSnapshot> fetch(int postId);
    }

    private final SnapshotFetcher fetcher;
    private final ForecastRepository repository;
    private final Clock clock;
    private final int maxPosts;
    private final ForecastSmoother smoother = new ForecastSmoother();

    /** Keeps a missing or malformed Vault value dark instead of failing startup. */
    public static final class TokenConfiguredCondition implements Condition {
        @Override
        public boolean matches(
                ConditionContext context,
                AnnotatedTypeMetadata metadata) {
            return MetaculusClient.isTokenFormatValid(
                    context.getEnvironment().getProperty("tracker.metaculus-token"));
        }
    }

    @Autowired
    public MetaculusForecastJob(
            ForecastRepository repository,
            @Value("${tracker.metaculus-token:}") String token,
            @Value("${tracker.metaculus-max-posts:2}") int maxPosts) {
        this(MetaculusClient.overHttp(token)::fetch, repository, Clock.systemUTC(), maxPosts);
    }

    MetaculusForecastJob(
            SnapshotFetcher fetcher,
            ForecastRepository repository,
            Clock clock,
            int maxPosts) {
        this.fetcher = fetcher;
        this.repository = repository;
        this.clock = clock;
        this.maxPosts = Math.max(1, Math.min(HARD_MAX_POSTS, maxPosts));
    }

    @Scheduled(cron = "${tracker.metaculus-cron:0 17 5 * * MON}", zone = "UTC")
    @SchedulerLock(name = "tracker-metaculus-forecast", lockAtMostFor = "PT15M")
    public void runOnce() {
        for (ForecastReference reference : repository.findCrowdReferences(maxPosts)) {
            try {
                collect(reference);
            } catch (RuntimeException failure) {
                log.warn("Metaculus collection failed for {}: {}",
                        reference.forecastKey(), failure.toString());
            }
        }
    }

    private void collect(ForecastReference reference) {
        int postId = postId(reference.sourceLocator());
        Optional<MetaculusSnapshot> snapshot = fetcher.fetch(postId);
        if (snapshot.isEmpty()) {
            log.info("Metaculus aggregate unavailable for {}; history unchanged",
                    reference.forecastKey());
            return;
        }
        LocalDate observedOn = LocalDate.now(clock);
        String hash = observationHash(reference.forecastKey(),
                snapshot.get().forecastYear(), observedOn, postId);
        List<ExternalForecastObservation> window = new ArrayList<>(
                repository.findCrowdWindow(reference.forecastKey(),
                        observedOn.minusDays(ForecastSmoother.WINDOW_DAYS - 1L), observedOn)
                        .stream()
                        .filter(item -> !item.retrievedOn().equals(observedOn))
                        .toList());
        window.add(new ExternalForecastObservation(
                0, reference.forecastKey(), "CROWD", reference.sourceName(),
                reference.question(), snapshot.get().forecastYear(), null,
                observedOn, hash, "CURRENT", ForecastSmoother.WINDOW_DAYS));
        BigDecimal mean = smoother.mean90Day(window, observedOn).orElseThrow();
        repository.saveCrowdObservation(reference.forecastKey(),
                snapshot.get().forecastYear(), mean, observedOn, hash);
        log.info("Metaculus observation stored for {} on {}",
                reference.forecastKey(), observedOn);
    }

    private static int postId(String locator) {
        if (locator == null || !locator.matches("post:[1-9][0-9]*")) {
            throw new IllegalArgumentException("Crowd reference requires a stable post locator");
        }
        return Integer.parseInt(locator.substring("post:".length()));
    }

    private static String observationHash(
            String key, BigDecimal year, LocalDate date, int postId) {
        String canonical = String.join("|", key,
                year.stripTrailingZeros().toPlainString(), date.toString(),
                Integer.toString(postId));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
