package com.aienterprise.backend.tracker.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Generic Readability-style extractor over jsoup. Candidates are scored by
 * paragraph density, sentence punctuation, and heading proximity, with
 * penalties for link density and boilerplate-like class/id vocabulary.
 * Deliberately free of any site-specific selector or hostname.
 */
public class JsoupReadabilityExtractor implements ArticleBodyExtractor {

    private static final int MIN_NON_WHITESPACE_CHARS = 500;
    private static final int MAX_TEXT_CHARS = 200_000;
    private static final int SIBLING_MIN_PARAGRAPH_CHARS = 100;
    private static final double SIBLING_MAX_LINK_DENSITY = 0.33;
    private static final String REMOVED_TAGS =
            "script, style, noscript, nav, aside, form, dialog, iframe, svg, button, input, select, textarea";
    private static final String CANDIDATE_TAGS = "article, main, section, div";
    private static final String TEXT_BLOCK_TAGS = "p, h1, h2, h3, li, blockquote, pre";
    private static final Pattern BOILERPLATE_HINT = Pattern.compile(
            "(?i)nav|menu|footer|header|masthead|sidebar|comment|share|social|newsletter|subscribe"
                    + "|related|promo|advert|banner|breadcrumb|widget|colophon|copyright|cookie");
    private static final Pattern SENTENCE_PUNCTUATION = Pattern.compile("[.!?]");

    @Override
    public ExtractedArticle extract(FetchedPage page) {
        Document document = parse(page);
        document.select(REMOVED_TAGS).remove();
        document.select("[hidden], [aria-hidden=true], [style*=display:none], [style*=visibility:hidden]")
                .remove();

        Element best = null;
        double bestScore = 0;
        for (Element candidate : document.select(CANDIDATE_TAGS)) {
            double score = score(candidate);
            if (score >= bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("Page has no scorable content block");
        }

        String text = renderText(contentElements(best));
        if (text.replaceAll("\\s", "").length() < MIN_NON_WHITESPACE_CHARS) {
            throw new IllegalArgumentException("Extraction is too short to stand in for the article");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }
        return new ExtractedArticle(title(document), text);
    }

    private static double score(Element element) {
        List<Element> paragraphs = element.select("p");
        int paragraphChars = paragraphs.stream().mapToInt(p -> p.text().length()).sum();
        if (paragraphChars == 0) {
            return 0;
        }
        int punctuation = (int) SENTENCE_PUNCTUATION.matcher(element.text()).results().count();
        int headings = element.select("h1, h2").isEmpty() ? 0 : 1;
        double linkDensity = linkDensity(element);
        return paragraphChars
                + paragraphs.size() * 80.0
                + punctuation * 12.0
                + headings * 40.0
                - linkDensity * paragraphChars * 2.0
                - boilerplatePenalty(element);
    }

    private static double linkDensity(Element element) {
        int linked = element.select("a").stream().mapToInt(a -> a.text().length()).sum();
        return linked / (double) Math.max(1, element.text().length());
    }

    private static double boilerplatePenalty(Element element) {
        String hints = element.className() + " " + element.id();
        return BOILERPLATE_HINT.matcher(hints).find() ? 500.0 : 0.0;
    }

    /** The best candidate plus adjacent text-bearing, link-light siblings. */
    private static List<Element> contentElements(Element best) {
        List<Element> elements = new ArrayList<>();
        Element previous = best.previousElementSibling();
        if (isTextBearingSibling(previous)) {
            elements.add(previous);
        }
        elements.add(best);
        Element next = best.nextElementSibling();
        if (isTextBearingSibling(next)) {
            elements.add(next);
        }
        return elements;
    }

    private static boolean isTextBearingSibling(Element sibling) {
        if (sibling == null || boilerplatePenalty(sibling) > 0) {
            return false;
        }
        int paragraphChars = sibling.select("p").stream().mapToInt(p -> p.text().length()).sum();
        return paragraphChars >= SIBLING_MIN_PARAGRAPH_CHARS
                && linkDensity(sibling) < SIBLING_MAX_LINK_DENSITY;
    }

    private static String renderText(List<Element> elements) {
        StringBuilder text = new StringBuilder();
        for (Element element : elements) {
            for (Element block : element.select(TEXT_BLOCK_TAGS)) {
                String paragraph = block.text().trim();
                if (paragraph.isEmpty()) {
                    continue;
                }
                if (!text.isEmpty()) {
                    text.append("\n\n");
                }
                text.append(paragraph);
            }
        }
        if (text.isEmpty()) {
            for (Element element : elements) {
                String fallback = element.text().trim();
                if (!fallback.isEmpty()) {
                    if (!text.isEmpty()) {
                        text.append("\n\n");
                    }
                    text.append(fallback);
                }
            }
        }
        return text.toString();
    }

    private static String title(Document document) {
        String title = document.title();
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        Element heading = document.selectFirst("h1");
        return heading == null ? "" : heading.text().trim();
    }

    private static Document parse(FetchedPage page) {
        try {
            String charset = page.charsetHint() == null
                    ? null
                    : page.charsetHint().toUpperCase(Locale.ROOT);
            return Jsoup.parse(new ByteArrayInputStream(page.bytes()), charset,
                    page.finalUri().toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable article bytes for " + page.finalUri().getHost(), e);
        }
    }
}
