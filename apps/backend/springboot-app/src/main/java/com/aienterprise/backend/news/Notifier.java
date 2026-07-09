package com.aienterprise.backend.news;

/**
 * Seam for breaking-news alerts. The default {@link DisabledNotifier} is a
 * no-op; a Discord webhook implementation is swapped in once the webhook URL
 * is provisioned. The real-time trigger path (Kafka) is a later phase — this
 * interface is the boundary that path will call.
 */
@FunctionalInterface
public interface Notifier {
    void send(String message);
}
