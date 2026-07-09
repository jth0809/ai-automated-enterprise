package com.aienterprise.backend.resume;

import java.util.Map;

/**
 * Supplies the résumé payload. Kept as a seam so the content source (a
 * classpath JSON file today, a DB row later) can change without touching
 * the authorization logic in {@link ResumeController}.
 */
@FunctionalInterface
public interface ResumeContent {
    Map<String, Object> get();
}
