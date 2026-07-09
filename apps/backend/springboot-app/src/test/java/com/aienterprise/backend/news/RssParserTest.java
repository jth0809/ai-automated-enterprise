package com.aienterprise.backend.news;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RssParserTest {

    private static final String FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Tech News</title>
                <item>
                  <title>Kubernetes 2.0 released</title>
                  <link>https://example.com/k8s-2</link>
                  <pubDate>Tue, 08 Jul 2026 09:00:00 GMT</pubDate>
                  <description>The next major version ships today.</description>
                </item>
                <item>
                  <title>Rust in the kernel</title>
                  <link>https://example.com/rust-kernel</link>
                  <pubDate>Tue, 08 Jul 2026 08:00:00 GMT</pubDate>
                </item>
              </channel>
            </rss>
            """;

    @Test
    void parsesEachItemWithItsCoreFields() {
        List<Article> articles = new RssParser().parse(FEED, "Tech News");

        assertEquals(2, articles.size());
        Article first = articles.get(0);
        assertEquals("Kubernetes 2.0 released", first.title());
        assertEquals("https://example.com/k8s-2", first.link());
        assertEquals("Tech News", first.source());
        assertEquals("The next major version ships today.", first.excerpt());
        assertNull(first.summary(), "AI summary is not populated by the parser");
    }

    @Test
    void toleratesItemsMissingOptionalFields() {
        List<Article> articles = new RssParser().parse(FEED, "Tech News");

        Article second = articles.get(1);
        assertEquals("Rust in the kernel", second.title());
        assertNull(second.excerpt(), "missing <description> yields a null excerpt");
    }

    @Test
    void returnsEmptyListForAFeedWithNoItems() {
        String empty = "<rss version=\"2.0\"><channel><title>Quiet</title></channel></rss>";
        assertTrue(new RssParser().parse(empty, "Quiet").isEmpty());
    }
}
