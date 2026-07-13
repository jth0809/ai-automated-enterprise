package com.aienterprise.backend.tracker.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Production transport over one shared JDK HttpClient: redirects disabled
 * (the fetcher revalidates each hop), no cookie handler, 10 s connect and
 * 15 s request timeouts, no script execution of any kind.
 */
public class JdkPageTransport implements PageTransport {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public Response get(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "text/html, application/xhtml+xml")
                .GET()
                .build();
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        return new Response() {
            @Override
            public int status() {
                return response.statusCode();
            }

            @Override
            public Map<String, List<String>> headers() {
                return response.headers().map();
            }

            @Override
            public InputStream body() {
                return response.body();
            }

            @Override
            public void close() {
                try {
                    response.body().close();
                } catch (IOException ignored) {
                    // Closing a drained or aborted stream needs no recovery.
                }
            }
        };
    }
}
