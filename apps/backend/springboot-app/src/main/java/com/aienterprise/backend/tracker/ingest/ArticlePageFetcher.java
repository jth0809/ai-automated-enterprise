package com.aienterprise.backend.tracker.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.IDN;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Allowlisted, bounded article page fetcher. Every request and every redirect
 * hop must be HTTPS on port 443 to an exact active body-domain host — no user
 * info, no fragments, no IP literals. At most three redirects are followed,
 * at most 2 MiB is read, and only HTML media types are accepted. Application
 * checks complement the Cilium egress policy; they never replace it.
 */
public class ArticlePageFetcher {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Set<String> HTML_MEDIA_TYPES = Set.of("text/html", "application/xhtml+xml");
    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final PageTransport transport;

    public ArticlePageFetcher(PageTransport transport) {
        this.transport = transport;
    }

    public FetchedPage fetch(URI uri, Set<String> allowedHosts) {
        Set<String> normalizedAllowed = normalizeHosts(allowedHosts);
        URI current = uri;
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                URI checked = validate(current, normalizedAllowed);
                try (PageTransport.Response response = transport.get(checked)) {
                    if (isRedirect(response.status())) {
                        if (hop == MAX_REDIRECTS) {
                            throw new IllegalStateException("Redirect limit exceeded for " + uri);
                        }
                        current = checked.resolve(requiredLocation(response.headers()));
                        continue;
                    }
                    if (response.status() < 200 || response.status() >= 300) {
                        throw new IllegalStateException(
                                "Fetch failed with HTTP " + response.status() + " for " + checked.getHost());
                    }
                    return readBounded(response, checked);
                }
            }
            throw new IllegalStateException("Redirect limit exceeded for " + uri);
        } catch (IOException e) {
            throw new UncheckedIOException("Fetch failed for " + uri.getHost(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fetch interrupted for " + uri.getHost(), e);
        }
    }

    private static URI validate(URI uri, Set<String> allowedHosts) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new SecurityException("Only https article fetches are allowed: " + uri);
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new SecurityException("User info and fragments are not allowed: " + uri);
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new SecurityException("Only port 443 is allowed: " + uri);
        }
        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank()) {
            throw new SecurityException("Article fetch requires a named host: " + uri);
        }
        if (rawHost.startsWith("[") || IPV4_LITERAL.matcher(rawHost).matches()) {
            throw new SecurityException("IP-literal hosts are not allowed: " + uri);
        }
        if (!allowedHosts.contains(normalizeHost(rawHost))) {
            throw new SecurityException("Host is not in the body-domain allowlist: " + rawHost);
        }
        return uri;
    }

    private FetchedPage readBounded(PageTransport.Response response, URI finalUri) throws IOException {
        String contentType = firstHeader(response.headers(), "content-type");
        String mediaType = mediaType(contentType);
        if (mediaType == null || !HTML_MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalArgumentException("Unsupported article media type: " + contentType);
        }
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IllegalArgumentException("Article response exceeds 2 MiB: " + finalUri.getHost());
            }
            return new FetchedPage(finalUri, mediaType, charsetHint(contentType), bytes);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String requiredLocation(Map<String, List<String>> headers) {
        String location = firstHeader(headers, "location");
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("Redirect response carries no location header");
        }
        return location.trim();
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey()) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private static String mediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        int paramsAt = contentType.indexOf(';');
        String type = paramsAt < 0 ? contentType : contentType.substring(0, paramsAt);
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private static String charsetHint(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String param : contentType.split(";")) {
            String trimmed = param.trim().toLowerCase(Locale.ROOT);
            if (trimmed.startsWith("charset=")) {
                String charset = trimmed.substring("charset=".length()).replace("\"", "").trim();
                return charset.isBlank() ? null : charset;
            }
        }
        return null;
    }

    private static Set<String> normalizeHosts(Set<String> hosts) {
        return Set.copyOf(hosts.stream().map(ArticlePageFetcher::normalizeHost).toList());
    }

    private static String normalizeHost(String host) {
        return IDN.toASCII(host.trim()).toLowerCase(Locale.ROOT);
    }
}
