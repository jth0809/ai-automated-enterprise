package com.aienterprise.backend.tracker.layerb;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
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
    public static final String BASE = "https://ll.thespacedevs.com/2.2.0/launch/";

    private final Function<URI, String> transport;
    private final LaunchLibraryParser parser = new LaunchLibraryParser();

    public LaunchLibraryClient(Function<URI, String> transport) {
        this.transport = transport;
    }

    public List<LaunchRecord> fetch(URI uri) {
        try {
            return parser.parse(transport.apply(uri));
        } catch (RuntimeException feedError) {
            return List.of();
        }
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
