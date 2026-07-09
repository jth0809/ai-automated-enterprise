package com.aienterprise.backend.news;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NewsControllerTest {

    private static String feed(String... links) {
        StringBuilder sb = new StringBuilder("<rss version=\"2.0\"><channel><title>F</title>");
        for (String link : links) {
            sb.append("<item><title>T</title><link>").append(link).append("</link></item>");
        }
        return sb.append("</channel></rss>").toString();
    }

    @Test
    void feedReturnsIngestedArticlesWithACount() {
        NewsService svc = new NewsService(new RssParser(), new DisabledSummarizer());
        svc.ingest(feed("https://a", "https://b"), "F");

        ResponseEntity<Map<String, Object>> res = new NewsController(svc).feed(20);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(2, res.getBody().get("count"));
        assertEquals(2, ((List<?>) res.getBody().get("articles")).size());
    }

    @Test
    void feedRespectsTheLimit() {
        NewsService svc = new NewsService(new RssParser(), new DisabledSummarizer());
        svc.ingest(feed("https://a", "https://b", "https://c"), "F");

        assertEquals(1, ((List<?>) new NewsController(svc).feed(1).getBody().get("articles")).size());
    }

    @Test
    void feedIsEmptyBeforeAnyIngest() {
        ResponseEntity<Map<String, Object>> res =
                new NewsController(new NewsService(new RssParser(), new DisabledSummarizer())).feed(20);

        assertEquals(0, res.getBody().get("count"));
    }
}
