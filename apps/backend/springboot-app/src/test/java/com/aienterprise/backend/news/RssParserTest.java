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

    @Test
    void parsesAtomAlternateLinkAndPublishedTimestamp() {
        String atom = """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry><title>Transfer demo</title>
                    <link rel="alternate" href="https://example.test/a"/>
                    <published>2026-07-12T01:02:03Z</published>
                    <summary>Propellant moved between tanks.</summary>
                  </entry>
                </feed>
                """;

        Article article = new RssParser().parse(atom, "TEST").getFirst();

        assertEquals("Transfer demo", article.title());
        assertEquals("https://example.test/a", article.link());
        assertEquals("2026-07-12T01:02:03Z", article.publishedAt());
        assertEquals("Propellant moved between tanks.", article.excerpt());
    }

    @Test
    void parsesRdfItemsWithDublinCoreDates() {
        String rdf = """
                <?xml version="1.0"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns="http://purl.org/rss/1.0/"
                         xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <channel rdf:about="https://example.test/feed">
                    <title>RDF Feed</title>
                  </channel>
                  <item rdf:about="https://example.test/one">
                    <title>Ion engine result</title>
                    <link>https://example.test/one</link>
                    <dc:date>2026-07-11T09:30:00+00:00</dc:date>
                    <description>Lab result summary.</description>
                  </item>
                  <item rdf:about="https://example.test/two">
                    <title>Second item</title>
                    <link>https://example.test/two</link>
                    <dc:date>2026-07-10T09:30:00Z</dc:date>
                  </item>
                </rdf:RDF>
                """;

        List<Article> articles = new RssParser().parse(rdf, "ARXIV");

        assertEquals(2, articles.size());
        assertEquals("https://example.test/one", articles.get(0).link());
        assertEquals("2026-07-11T09:30:00+00:00", articles.get(0).publishedAt());
        assertEquals("Lab result summary.", articles.get(0).excerpt());
        assertEquals("2026-07-10T09:30:00Z", articles.get(1).publishedAt());
    }

    @Test
    void skipsEntriesWithoutLinksWithoutFailingTheFeed() {
        String atom = """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Atom Feed</title>
                  <entry><title>First</title>
                    <link rel="alternate" href="https://example.test/a"/>
                    <published>2026-07-12T01:02:03Z</published>
                  </entry>
                  <entry><title>No link at all</title>
                    <published>2026-07-12T02:00:00Z</published>
                  </entry>
                  <entry><title>Third</title>
                    <link href="https://example.test/c"/>
                    <updated>2026-07-12T03:00:00Z</updated>
                  </entry>
                </feed>
                """;

        List<Article> articles = new RssParser().parse(atom, "TEST");

        assertEquals(2, articles.size());
        assertEquals("https://example.test/a", articles.get(0).link());
        assertEquals("https://example.test/c", articles.get(1).link());
        // A bare <link href=...> without rel counts as the alternate link,
        // and <updated> stands in when <published> is absent.
        assertEquals("2026-07-12T03:00:00Z", articles.get(1).publishedAt());
    }
}
