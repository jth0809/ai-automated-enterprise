package com.aienterprise.backend.tracker.forecast;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/** Bounded authenticated reader for one official Metaculus post endpoint. */
public final class MetaculusClient {

    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final Pattern TOKEN = Pattern.compile("[!-~]{8,256}");
    private static final URI BASE = URI.create("https://www.metaculus.com");

    @FunctionalInterface
    interface Transport {
        HttpResult get(URI uri, String authorizationHeader);
    }

    public record HttpResult(int status, byte[] body) {
    }

    private final String authorizationHeader;
    private final Transport transport;
    private final MetaculusSnapshotParser parser = new MetaculusSnapshotParser();

    MetaculusClient(String token, Transport transport) {
        if (!isTokenFormatValid(token)) {
            throw new IllegalArgumentException(
                    "Metaculus token must be 8 to 256 visible non-space characters");
        }
        if (transport == null) {
            throw new IllegalArgumentException("Metaculus transport is required");
        }
        this.authorizationHeader = "Token " + token;
        this.transport = transport;
    }

    /** Shared activation/constructor policy without exposing or logging the token. */
    public static boolean isTokenFormatValid(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    public Optional<MetaculusSnapshot> fetch(int postId) {
        if (postId <= 0) {
            throw new IllegalArgumentException("Metaculus post ID must be positive");
        }
        URI uri = BASE.resolve("/api/posts/" + postId + "/?with_cp=true");
        HttpResult response = transport.get(uri, authorizationHeader);
        if (response.status() == 401 || response.status() == 403) {
            throw new IllegalStateException("Metaculus authorization rejected");
        }
        if (response.status() == 429) {
            throw new IllegalStateException("Metaculus request was rate limited");
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new IllegalStateException("Metaculus request failed with HTTP "
                    + response.status());
        }
        if (response.body() == null || response.body().length > MAX_RESPONSE_BYTES) {
            throw new IllegalStateException("Metaculus response exceeds 1 MiB");
        }
        return parser.parse(postId, response.body());
    }

    public static MetaculusClient overHttp(String token) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new MetaculusClient(token, (uri, authorization) -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Authorization", authorization)
                    .GET()
                    .build();
            try {
                HttpResponse<InputStream> response = client.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                    return new HttpResult(response.statusCode(), bytes);
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Metaculus network request failed", failure);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Metaculus network request interrupted", failure);
            }
        });
    }
}
