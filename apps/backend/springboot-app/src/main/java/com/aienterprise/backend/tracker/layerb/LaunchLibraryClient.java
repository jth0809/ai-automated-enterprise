package com.aienterprise.backend.tracker.layerb;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Fetches launches from Launch Library 2. The HTTP transport is injected so
 * unit tests run without network; production wires {@link #overHttp()}, which
 * requires a CNP {@code toFQDNs} allow entry for the host at deploy time. A
 * feed error yields an empty list rather than throwing, so a hiccup never
 * breaks ingestion.
 */
public class LaunchLibraryClient {

    /** Public LL2 launch endpoint; deploy-time CNP allowlist required. */
    public static final String BASE = "https://ll.thespacedevs.com/2.3.0/launches/";
    private static final URI BASE_URI = URI.create(BASE);
    private static final int MAX_ALLOWED_PAGES = 20;

    private final Function<URI, String> transport;
    private final LaunchLibraryParser parser = new LaunchLibraryParser();

    public LaunchLibraryClient(Function<URI, String> transport) {
        this.transport = transport;
    }

    public List<LaunchRecord> fetch(URI uri) {
        return fetchPage(uri).map(LaunchPage::launches).orElseGet(List::of);
    }

    /**
     * Follows LL2 pagination only on the exact HTTPS API host. A failed,
     * unsafe, cyclic, or truncated sequence returns empty rather than exposing
     * a partial annual count as a complete measurement.
     */
    public Optional<List<LaunchRecord>> fetchAll(URI initial, int maxPages) {
        if (maxPages < 1 || maxPages > MAX_ALLOWED_PAGES || !isAllowed(initial)) {
            return Optional.empty();
        }
        Map<String, LaunchRecord> byId = new LinkedHashMap<>();
        Set<URI> visited = new HashSet<>();
        URI current = initial;
        for (int pageNumber = 0; pageNumber < maxPages; pageNumber++) {
            if (!isAllowed(current) || !visited.add(current)) {
                return Optional.empty();
            }
            Optional<LaunchPage> fetched = fetchPage(current);
            if (fetched.isEmpty()) {
                return Optional.empty();
            }
            LaunchPage page = fetched.get();
            for (LaunchRecord launch : page.launches()) {
                byId.putIfAbsent(launch.id(), launch);
            }
            if (page.next() == null) {
                return Optional.of(List.copyOf(byId.values()));
            }
            current = page.next();
        }
        return Optional.empty();
    }

    public static URI yearUri(int year) {
        if (year < 1957 || year > 9999) {
            throw new IllegalArgumentException("Unsupported launch year: " + year);
        }
        return URI.create(BASE
                + "?format=json&include_suborbital=false&limit=100&mode=list"
                + "&ordering=net&year=" + year);
    }

    private Optional<LaunchPage> fetchPage(URI uri) {
        try {
            return parser.parsePage(transport.apply(uri));
        } catch (RuntimeException feedError) {
            return Optional.empty();
        }
    }

    private static boolean isAllowed(URI uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && BASE_URI.getHost().equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getUserInfo() == null
                && uri.getPath() != null
                && uri.getPath().startsWith(BASE_URI.getPath());
    }

    /** HTTP-backed client for production. Not exercised by unit tests. */
    public static LaunchLibraryClient overHttp() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return new LaunchLibraryClient(uri -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200 ? response.body() : "";
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Launch Library 2 request interrupted", interrupted);
            } catch (Exception failure) {
                throw new RuntimeException("Launch Library 2 request failed", failure);
            }
        });
    }
}
