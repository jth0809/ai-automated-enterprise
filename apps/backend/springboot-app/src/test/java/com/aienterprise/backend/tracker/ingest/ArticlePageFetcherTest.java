package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ArticlePageFetcherTest {

    private final FakeTransport transport = new FakeTransport();
    private final ArticlePageFetcher fetcher = new ArticlePageFetcher(transport);

    @Test
    void followsRelativeRedirectAndReturnsBoundedPage() {
        transport.respond("https://allowed.test/a", 302, Map.of("location", List.of("/b")), "");
        transport.respond("https://allowed.test/b", 200,
                Map.of("content-type", List.of("text/html; charset=UTF-8")), "<html>body text</html>");

        FetchedPage page = fetcher.fetch(URI.create("https://allowed.test/a"), Set.of("allowed.test"));

        assertEquals(URI.create("https://allowed.test/b"), page.finalUri());
        assertEquals("text/html", page.mediaType());
        assertEquals("utf-8", page.charsetHint());
        assertArrayEquals("<html>body text</html>".getBytes(StandardCharsets.UTF_8), page.bytes());
    }

    @Test
    void rejectsRedirectOutsideAllowlist() {
        transport.respond("https://allowed.test/a", 302,
                Map.of("location", List.of("https://evil.test/b")), "");

        assertThrows(SecurityException.class,
                () -> fetcher.fetch(URI.create("https://allowed.test/a"), Set.of("allowed.test")));
    }

    @Test
    void rejectsBodiesOverTwoMiB() {
        transport.respond("https://allowed.test/a", 200,
                Map.of("content-type", List.of("text/html")), "x".repeat(2 * 1024 * 1024 + 1));

        assertThrows(IllegalArgumentException.class,
                () -> fetcher.fetch(URI.create("https://allowed.test/a"), Set.of("allowed.test")));
    }

    @Test
    void rejectsPlainHttp() {
        assertThrows(SecurityException.class,
                () -> fetcher.fetch(URI.create("http://allowed.test/a"), Set.of("allowed.test")));
    }

    @Test
    void rejectsUserInfoInTheUri() {
        assertThrows(SecurityException.class,
                () -> fetcher.fetch(URI.create("https://user@allowed.test/a"), Set.of("allowed.test")));
    }

    @Test
    void rejectsIpLiteralHosts() {
        assertThrows(SecurityException.class,
                () -> fetcher.fetch(URI.create("https://192.168.0.10/a"), Set.of("192.168.0.10")));
        assertThrows(SecurityException.class,
                () -> fetcher.fetch(URI.create("https://[2001:db8::1]/a"), Set.of("[2001:db8::1]")));
    }

    @Test
    void rejectsExplicitNonHttpsPort() {
        assertThrows(SecurityException.class,
                () -> fetcher.fetch(URI.create("https://allowed.test:8443/a"), Set.of("allowed.test")));
    }

    @Test
    void rejectsHostsOutsideTheAllowlist() {
        assertThrows(SecurityException.class,
                () -> fetcher.fetch(URI.create("https://other.test/a"), Set.of("allowed.test")));
    }

    @Test
    void rejectsTheFourthRedirect() {
        transport.respond("https://allowed.test/1", 301, Map.of("location", List.of("/2")), "");
        transport.respond("https://allowed.test/2", 302, Map.of("location", List.of("/3")), "");
        transport.respond("https://allowed.test/3", 307, Map.of("location", List.of("/4")), "");
        transport.respond("https://allowed.test/4", 308, Map.of("location", List.of("/5")), "");

        assertThrows(IllegalStateException.class,
                () -> fetcher.fetch(URI.create("https://allowed.test/1"), Set.of("allowed.test")));
    }

    @Test
    void rejectsMissingAndUnsupportedContentTypes() {
        transport.respond("https://allowed.test/none", 200, Map.of(), "<html/>");
        transport.respond("https://allowed.test/json", 200,
                Map.of("content-type", List.of("application/json")), "{}");

        assertThrows(IllegalArgumentException.class,
                () -> fetcher.fetch(URI.create("https://allowed.test/none"), Set.of("allowed.test")));
        assertThrows(IllegalArgumentException.class,
                () -> fetcher.fetch(URI.create("https://allowed.test/json"), Set.of("allowed.test")));
    }

    @Test
    void rejectsNonSuccessStatuses() {
        transport.respond("https://allowed.test/missing", 404,
                Map.of("content-type", List.of("text/html")), "not here");

        assertThrows(IllegalStateException.class,
                () -> fetcher.fetch(URI.create("https://allowed.test/missing"), Set.of("allowed.test")));
    }

    private static final class FakeTransport implements PageTransport {

        private record Stub(int status, Map<String, List<String>> headers, String body) {
        }

        private final Map<String, Stub> stubs = new HashMap<>();

        void respond(String url, int status, Map<String, List<String>> headers, String body) {
            stubs.put(url, new Stub(status, headers, body));
        }

        @Override
        public Response get(URI uri) {
            Stub stub = stubs.get(uri.toString());
            if (stub == null) {
                throw new IllegalStateException("Unexpected fetch: " + uri);
            }
            return new Response() {
                @Override
                public int status() {
                    return stub.status();
                }

                @Override
                public Map<String, List<String>> headers() {
                    return stub.headers();
                }

                @Override
                public InputStream body() {
                    return new ByteArrayInputStream(stub.body().getBytes(StandardCharsets.UTF_8));
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
