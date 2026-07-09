package com.aienterprise.backend.news;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** No-op alert sink used until a Discord webhook URL is provisioned. */
public class DisabledNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(DisabledNotifier.class);

    @Override
    public void send(String message) {
        log.debug("notifier disabled; dropping alert: {}", message);
    }
}
