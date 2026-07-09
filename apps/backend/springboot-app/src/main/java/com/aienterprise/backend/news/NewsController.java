package com.aienterprise.backend.news;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public read-only news feed. */
@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsService news;

    public NewsController(NewsService news) {
        this.news = news;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> feed(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        int capped = Math.max(1, Math.min(limit, 100));
        List<Article> articles = news.latest(capped);
        return ResponseEntity.ok(Map.of("count", articles.size(), "articles", articles));
    }
}
