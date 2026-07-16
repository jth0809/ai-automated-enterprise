package com.aienterprise.backend.tracker.ingest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aienterprise.backend.tracker.collection.OfficialIndexChannel;
import com.aienterprise.backend.tracker.collection.OfficialIndexEntry;
import com.aienterprise.backend.tracker.collection.OfficialIndexParser;
import com.aienterprise.backend.tracker.domain.TrackerRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** Collects metadata-only candidates; it never promotes or scores them. */
@Component
@ConditionalOnProperty(
        prefix = "tracker",
        name = { "enabled", "official-index-enabled" },
        havingValue = "true")
public class OfficialIndexJob {

    private static final Logger log = LoggerFactory.getLogger(OfficialIndexJob.class);
    private static final int HARD_MAX_LINKS = 40;

    @FunctionalInterface
    interface IndexPageFetcher {
        FetchedPage fetch(URI uri, Set<String> allowedHosts);
    }

    private final IndexPageFetcher fetcher;
    private final OfficialIndexParser parser;
    private final TrackerRepository repository;
    private final List<OfficialIndexChannel> channels;
    private final int maxLinks;

    @Autowired
    public OfficialIndexJob(
            ArticlePageFetcher fetcher,
            TrackerRepository repository,
            @Value("${tracker.official-index-max-links:40}") int maxLinks) {
        this(fetcher::fetch, new OfficialIndexParser(), repository,
                OfficialIndexChannel.defaults(), maxLinks);
    }

    OfficialIndexJob(
            IndexPageFetcher fetcher,
            OfficialIndexParser parser,
            TrackerRepository repository,
            List<OfficialIndexChannel> channels,
            int maxLinks) {
        this.fetcher = fetcher;
        this.parser = parser;
        this.repository = repository;
        this.channels = List.copyOf(channels);
        this.maxLinks = Math.max(1, Math.min(HARD_MAX_LINKS, maxLinks));
    }

    @Scheduled(cron = "${tracker.official-index-cron:0 23 4 * * WED}", zone = "UTC")
    @SchedulerLock(name = "tracker-official-index", lockAtMostFor = "PT20M")
    public void runOnce() {
        for (OfficialIndexChannel channel : channels) {
            try {
                collect(channel);
            } catch (RuntimeException failure) {
                log.warn("official index collection failed for {} ({}): {}",
                        channel.sourceCode(), channel.indexUri(), failure.toString());
            }
        }
    }

    private void collect(OfficialIndexChannel channel) {
        long sourceId = repository.sourceIdByCode(channel.sourceCode());
        FetchedPage page = fetcher.fetch(channel.indexUri(), Set.of(channel.host()));
        int inserted = 0;
        for (OfficialIndexEntry entry : parser.parse(page, channel, maxLinks)) {
            try {
                if (repository.insertArticleCandidateIfNew(
                        entry.url().toString(), sha256(entry.url().toString()), sourceId,
                        entry.title(), entry.publishedAt()).isPresent()) {
                    inserted++;
                }
            } catch (RuntimeException failure) {
                log.warn("official index candidate insert failed for {} ({}): {}",
                        channel.sourceCode(), entry.url(), failure.toString());
            }
        }
        log.info("official index collected {} new quarantined candidates for {}",
                inserted, channel.sourceCode());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
