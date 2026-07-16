package com.aienterprise.backend.tracker.collection;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.aienterprise.backend.tracker.ingest.FetchedPage;

/** Pure, network-free parser for the three fixed official index shapes. */
public final class OfficialIndexParser {

    private static final int HARD_MAX_LINKS = 40;
    private static final Pattern MONTH_DATE = Pattern.compile(
            "(?i)\\b(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|"
                    + "Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|"
                    + "Nov(?:ember)?|Dec(?:ember)?)\\s+\\d{1,2},\\s+\\d{4}\\b");
    private static final Pattern ISO_DATE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern SLASH_DATE = Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{4}\\b");
    private static final Pattern UNAMBIGUOUS_DMY = Pattern.compile(
            "\\b(?:1[3-9]|2\\d|3[01])-(?:0?[1-9]|1[0-2])-\\d{4}\\b");
    private static final DateTimeFormatter FULL_MONTH = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("MMMM d, uuuu")
            .toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_MONTH = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("MMM d, uuuu")
            .toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter SLASH = DateTimeFormatter.ofPattern("M/d/uuuu");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("d-M-uuuu");

    public List<OfficialIndexEntry> parse(
            FetchedPage page,
            OfficialIndexChannel channel,
            int requestedMaxLinks) {
        if (page == null || channel == null || page.bytes() == null) {
            throw new IllegalArgumentException("Page and channel are required");
        }
        int limit = Math.max(1, Math.min(HARD_MAX_LINKS, requestedMaxLinks));
        Charset charset = charset(page.charsetHint());
        Document document = Jsoup.parse(
                new String(page.bytes(), charset), page.finalUri().toString());
        List<OfficialIndexEntry> entries = new ArrayList<>();
        Set<URI> seen = new HashSet<>();

        for (Element link : document.select("a[href]")) {
            if (entries.size() >= limit) {
                break;
            }
            String href = link.attr("href").trim();
            String title = normalizeText(link.text());
            if (href.isEmpty() || title.length() < 3 || title.length() > 1_000) {
                continue;
            }
            URI resolved;
            try {
                resolved = page.finalUri().resolve(href);
            } catch (IllegalArgumentException invalidUri) {
                continue;
            }
            var normalized = channel.normalizeCandidate(resolved);
            if (normalized.isEmpty() || !seen.add(normalized.get())) {
                continue;
            }
            entries.add(new OfficialIndexEntry(
                    normalized.get(), title, parseDate(contextText(link))));
        }
        return List.copyOf(entries);
    }

    private static Charset charset(String hint) {
        if (hint == null || hint.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(hint.trim());
        } catch (RuntimeException unsupported) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String contextText(Element link) {
        Element current = link;
        Element fallback = link.parent();
        for (int depth = 0; current != null && depth < 5; depth++, current = current.parent()) {
            if ("tr".equals(current.tagName()) || "li".equals(current.tagName())) {
                return normalizeText(current.text());
            }
        }
        return normalizeText(fallback == null ? link.text() : fallback.text());
    }

    private static Instant parseDate(String text) {
        LocalDate date = parse(text, MONTH_DATE, FULL_MONTH);
        if (date == null) {
            date = parse(text, MONTH_DATE, SHORT_MONTH);
        }
        if (date == null) {
            date = parse(text, ISO_DATE, DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (date == null) {
            date = parse(text, SLASH_DATE, SLASH);
        }
        if (date == null) {
            date = parse(text, UNAMBIGUOUS_DMY, DMY);
        }
        return date == null ? null : date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static LocalDate parse(String text, Pattern pattern, DateTimeFormatter formatter) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.parse(matcher.group(), formatter);
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
