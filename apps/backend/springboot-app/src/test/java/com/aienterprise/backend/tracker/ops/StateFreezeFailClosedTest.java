package com.aienterprise.backend.tracker.ops;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.domain.TrackerRepository;

class StateFreezeFailClosedTest {

    @Test
    void lookupFailureIsTreatedAsFrozen() {
        TrackerRepository repository = mock(TrackerRepository.class);
        when(repository.findOpsState(StateFreezeService.STATE_FROZEN_KEY))
                .thenThrow(new IllegalStateException("database unavailable"));

        StateFreezeService service = new StateFreezeService(
                repository, Clock.systemUTC());

        assertTrue(service.isFrozen());
    }
}
