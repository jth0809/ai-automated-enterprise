package com.aienterprise.backend.resume;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the résumé beans from configuration. With no {@code
 * resume.access.code-hashes} set (the default), the verifier accepts nothing
 * — the résumé ships locked and is opened by adding code hashes via env/Vault
 * later. The signing secret should come from Vault in production.
 */
@Configuration
public class ResumeConfig {

    @Bean
    public AccessCodeVerifier accessCodeVerifier(
            @Value("${resume.access.code-hashes:}") String csv) {
        return new AccessCodeVerifier(splitCsv(csv));
    }

    @Bean
    public ResumeTokenService resumeTokenService(
            @Value("${resume.access.signing-secret:change-me-dev-secret}") String secret,
            @Value("${resume.access.ttl-minutes:15}") long ttlMinutes) {
        return new ResumeTokenService(secret, Duration.ofMinutes(ttlMinutes), Clock.systemUTC());
    }

    @Bean
    public ResumeContent resumeContent() {
        return new ClasspathResumeContent(new ObjectMapper(), "resume.json");
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
