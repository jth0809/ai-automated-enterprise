package com.aienterprise.backend.tracker.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.aienterprise.backend.tracker.ingest.FetchedPage;

class OfficialIndexParserTest {

    private final OfficialIndexParser parser = new OfficialIndexParser();

    @Test
    void parsesEligibleIsroPressLinksAndUnambiguousDates() {
        String html = """
                <table>
                  <tr><td><a href="/Successful_Test.html">Successful test</a></td>
                      <td>July 11, 2025</td></tr>
                  <tr><td><a href="https://www.isro.gov.in/Another-Test.html#top">
                      Another test</a></td><td>Jun 23, 2024</td></tr>
                  <tr><td><a href="/Press.html">Index self-link</a></td></tr>
                  <tr><td><a href="https://other.example/out.html">External</a></td></tr>
                  <tr><td><a href="/document.pdf">PDF</a></td></tr>
                </table>
                """;

        var entries = parser.parse(page(OfficialIndexChannel.ISRO_PRESS, html),
                OfficialIndexChannel.ISRO_PRESS, 40);

        assertEquals(2, entries.size());
        assertEquals(URI.create("https://www.isro.gov.in/Successful_Test.html"),
                entries.get(0).url());
        assertEquals(Instant.parse("2025-07-11T00:00:00Z"), entries.get(0).publishedAt());
        assertEquals(URI.create("https://www.isro.gov.in/Another-Test.html"),
                entries.get(1).url());
        assertEquals(Instant.parse("2024-06-23T00:00:00Z"), entries.get(1).publishedAt());
    }

    @Test
    void separatesCnsaContentLinksFromMalformedAndExternalLinks() {
        String html = """
                <ul>
                  <li><a href="/english/n6465645/n6465648/c10652198/content.html">
                      Mars sample return cooperation</a> 03/11/2025</li>
                  <li><a href="/english/n6465652/n6465653/c10743249/content.html">
                      Lunar minerals</a> 2026-04-24</li>
                  <li><a href="/english/chinadaily:">Malformed</a></li>
                  <li><a href="http://www.cnsa.gov.cn/english/n1/c1/content.html">HTTP</a></li>
                  <li><a href="https://124.17.81.212:8081/a">IP</a></li>
                  <li><a href="https://www.sastind.gov.cn/story">External</a></li>
                  <li><a href="/english/n6465652/index.html">Section</a></li>
                </ul>
                """;

        var entries = parser.parse(page(OfficialIndexChannel.CNSA_NEWS, html),
                OfficialIndexChannel.CNSA_NEWS, 40);

        assertEquals(2, entries.size());
        assertEquals(Instant.parse("2025-03-11T00:00:00Z"), entries.get(0).publishedAt());
        assertEquals(Instant.parse("2026-04-24T00:00:00Z"), entries.get(1).publishedAt());

        var policyEntries = parser.parse(page(OfficialIndexChannel.CNSA_POLICY, html),
                OfficialIndexChannel.CNSA_POLICY, 40);
        assertEquals(1, policyEntries.size(),
                "Tier 1 policy channel must not absorb hosted-news sections");
    }

    @Test
    void deduplicatesNormalizesWhitespaceAndLeavesUnknownDatesNull() {
        String html = """
                <div><a href="/No_Date.html">  No   date  </a></div>
                <div><a href="https://www.isro.gov.in/No_Date.html#copy">Duplicate</a></div>
                """;

        var entries = parser.parse(page(OfficialIndexChannel.ISRO_PRESS, html),
                OfficialIndexChannel.ISRO_PRESS, 40);

        assertEquals(1, entries.size());
        assertEquals("No date", entries.getFirst().title());
        assertNull(entries.getFirst().publishedAt());
    }

    @Test
    void hardCapsEveryChannelAtFortyLinks() {
        StringBuilder html = new StringBuilder("<ul>");
        for (int i = 0; i < 45; i++) {
            html.append("<li><a href=\"/Press_").append(i)
                    .append(".html\">Press ").append(i).append(" item</a></li>");
        }
        html.append("</ul>");

        assertEquals(40, parser.parse(page(OfficialIndexChannel.ISRO_PRESS,
                html.toString()), OfficialIndexChannel.ISRO_PRESS, 999).size());
    }

    private FetchedPage page(OfficialIndexChannel channel, String html) {
        return new FetchedPage(channel.indexUri(), "text/html", "utf-8",
                html.getBytes(StandardCharsets.UTF_8));
    }
}
