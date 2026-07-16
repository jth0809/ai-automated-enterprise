package com.aienterprise.backend.tracker.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.aienterprise.backend.tracker.forecast.ForecastRepository;

class MetaculusForecastJobConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ForecastRepository.class, () -> mock(ForecastRepository.class))
            .withUserConfiguration(MetaculusForecastJob.class)
            .withPropertyValues(
                    "tracker.enabled=true",
                    "tracker.metaculus-enabled=true",
                    "tracker.metaculus-terms-approved=true");

    @Test
    void nonblankValidTokenIsTheThirdActivationGate() {
        runner.withPropertyValues("tracker.metaculus-token=")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(MetaculusForecastJob.class));

        runner.withPropertyValues("tracker.metaculus-token=token with spaces")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(MetaculusForecastJob.class));

        runner.withPropertyValues("tracker.metaculus-token=safe-token-123")
                .run(context -> assertThat(context)
                        .hasSingleBean(MetaculusForecastJob.class));
    }
}
