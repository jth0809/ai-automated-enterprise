package com.aienterprise.backend.resume;

import java.io.InputStream;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads the résumé from a classpath JSON file at startup. Content lives on
 * the server (never in the frontend bundle) so it is only reachable through
 * the token-gated endpoint. Missing/invalid file degrades to a placeholder
 * rather than failing app startup.
 */
public class ClasspathResumeContent implements ResumeContent {

    private final Map<String, Object> resume;

    @SuppressWarnings("unchecked")
    public ClasspathResumeContent(ObjectMapper mapper, String resourcePath) {
        Map<String, Object> loaded;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            loaded = in == null ? placeholder() : mapper.readValue(in, Map.class);
        } catch (Exception e) {
            loaded = placeholder();
        }
        this.resume = Map.copyOf(loaded);
    }

    private static Map<String, Object> placeholder() {
        return Map.of("name", "TODO", "headline", "résumé content not configured");
    }

    @Override
    public Map<String, Object> get() {
        return resume;
    }
}
