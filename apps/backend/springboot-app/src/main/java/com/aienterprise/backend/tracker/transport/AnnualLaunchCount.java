package com.aienterprise.backend.tracker.transport;

/** Completed Falcon 9 plus Falcon Heavy orbital launches in one UTC year. */
public record AnnualLaunchCount(int year, long launches) {
}
