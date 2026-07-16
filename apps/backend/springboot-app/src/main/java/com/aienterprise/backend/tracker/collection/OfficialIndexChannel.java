package com.aienterprise.backend.tracker.collection;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Fixed, reviewable HTML-index channels whose hosts are mirrored in CNP. */
public enum OfficialIndexChannel {
    ISRO_PRESS(
            "ISRO",
            URI.create("https://www.isro.gov.in/Press.html"),
            Pattern.compile("^/[A-Za-z0-9][A-Za-z0-9_-]*\\.html$")),
    CNSA_POLICY(
            "CNSA",
            URI.create("https://www.cnsa.gov.cn/english/n6465645/n6465648/index.html"),
            Pattern.compile("^/english/n6465645/n6465648/c\\d+/content\\.html$")),
    CNSA_NEWS(
            "CNSA_HOSTED_MEDIA",
            URI.create("https://www.cnsa.gov.cn/english/"),
            Pattern.compile("^/english/(?:n\\d+/)+c\\d+/content\\.html$"));

    private final String sourceCode;
    private final URI indexUri;
    private final Pattern eligiblePath;

    OfficialIndexChannel(String sourceCode, URI indexUri, Pattern eligiblePath) {
        this.sourceCode = sourceCode;
        this.indexUri = indexUri;
        this.eligiblePath = eligiblePath;
    }

    public String sourceCode() {
        return sourceCode;
    }

    public URI indexUri() {
        return indexUri;
    }

    public String host() {
        return indexUri.getHost().toLowerCase(Locale.ROOT);
    }

    public Optional<URI> normalizeCandidate(URI candidate) {
        if (candidate == null
                || !"https".equalsIgnoreCase(candidate.getScheme())
                || candidate.getHost() == null
                || !host().equals(candidate.getHost().toLowerCase(Locale.ROOT))
                || candidate.getUserInfo() != null
                || (candidate.getPort() != -1 && candidate.getPort() != 443)
                || candidate.getQuery() != null
                || candidate.getPath() == null
                || !eligiblePath.matcher(candidate.getPath()).matches()) {
            return Optional.empty();
        }
        try {
            URI normalized = new URI(
                    "https", null, host(), -1, candidate.getPath(), null, null);
            if (normalized.equals(indexUri)) {
                return Optional.empty();
            }
            return Optional.of(normalized);
        } catch (URISyntaxException invalid) {
            return Optional.empty();
        }
    }

    public static List<OfficialIndexChannel> defaults() {
        return List.of(values());
    }
}
