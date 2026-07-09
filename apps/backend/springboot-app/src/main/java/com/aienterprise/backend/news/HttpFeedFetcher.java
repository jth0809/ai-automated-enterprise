package com.aienterprise.backend.news;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches a feed over HTTPS. In-cluster this requires the backend
 * CiliumNetworkPolicy to allow egress to the feed hosts (currently
 * default-deny); until those egress rules and feed URLs are configured, the
 * scheduler simply has nothing to fetch.
 */
public class HttpFeedFetcher implements FeedFetcher {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "ai-automated-enterprise-newsbot/0.1")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("feed " + url + " returned HTTP " + response.statusCode());
        }
        return response.body();
    }
}
