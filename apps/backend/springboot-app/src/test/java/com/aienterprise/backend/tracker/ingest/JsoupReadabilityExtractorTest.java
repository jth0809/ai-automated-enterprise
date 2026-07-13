package com.aienterprise.backend.tracker.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

class JsoupReadabilityExtractorTest {

    private static final String EVIDENCE = "The transfer moved 1,200 kilograms of propellant.";

    private final ArticleBodyExtractor extractor = new JsoupReadabilityExtractor();

    @ParameterizedTest
    @ValueSource(strings = {"semantic-article.html", "nested-divs.html", "sidebar-heavy.html", "malformed.html"})
    void extractsEvidenceAndDropsBoilerplate(String fixture) {
        ExtractedArticle result = extractor.extract(page(fixture));

        assertTrue(result.text().contains(EVIDENCE),
                fixture + " must preserve the exact evidence sentence");
        assertTrue(result.text().contains("stable tank pressures"),
                fixture + " must keep ordinary body paragraphs");
        assertFalse(result.text().contains("Subscribe to our newsletter"),
                fixture + " must drop the newsletter boilerplate");
        assertFalse(result.text().contains("Related stories"),
                fixture + " must drop the related-links block");
        assertFalse(result.text().toLowerCase().contains("<script"),
                fixture + " must not leak markup");
    }

    @Test
    void preservesParagraphBoundariesAndTitle() {
        ExtractedArticle result = extractor.extract(page("semantic-article.html"));

        assertTrue(result.title().contains("Orbital propellant transfer demonstration"));
        int first = result.text().indexOf("space environment.");
        int second = result.text().indexOf(EVIDENCE);
        assertTrue(first >= 0 && second > first);
        assertTrue(result.text().substring(first, second).contains("\n\n"),
                "paragraphs must stay separated by a blank line");
    }

    @Test
    void rejectsPagesWithTooLittleContent() {
        byte[] tiny = "<html><body><div><p>Too short.</p></div></body></html>"
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> extractor.extract(
                new FetchedPage(URI.create("https://allowed.test/x"), "text/html", "utf-8", tiny)));
    }

    @Test
    void capsExtractedTextAtTwoHundredThousandCharacters() {
        StringBuilder html = new StringBuilder("<html><body><article>");
        String sentence = "This paragraph repeats to inflate the article body far past the cap. ";
        for (int i = 0; i < 3_500; i++) {
            html.append("<p>").append(sentence).append(i).append("</p>");
        }
        html.append("</article></body></html>");

        ExtractedArticle result = extractor.extract(new FetchedPage(
                URI.create("https://allowed.test/long"), "text/html", "utf-8",
                html.toString().getBytes(StandardCharsets.UTF_8)));

        assertEquals(200_000, result.text().length());
    }

    private FetchedPage page(String fixture) {
        try {
            byte[] bytes = new ClassPathResource("tracker/extraction/" + fixture)
                    .getContentAsByteArray();
            return new FetchedPage(URI.create("https://allowed.test/story"), "text/html", "utf-8", bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
