package com.aienterprise.backend.news;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Minimal feed parser over the JDK XML stack — no third-party feed library,
 * which keeps the dependency (and Trivy) surface small. Accepts RSS 2.0
 * {@code item}, RSS 1.0/RDF {@code item} (Dublin Core dates), and Atom
 * {@code entry} (alternate link relation). Elements are matched by local name
 * so namespaced feeds parse uniformly. The parser is hardened against XXE
 * (external entities and DOCTYPE disabled). Entries without a usable link are
 * skipped individually; only a malformed document fails the whole feed.
 */
public class RssParser {

    public List<Article> parse(String xml, String source) {
        Element root = documentElement(xml);
        List<Article> articles = new ArrayList<>();
        collect(root.getElementsByTagNameNS("*", "item"), source, articles);
        collect(root.getElementsByTagNameNS("*", "entry"), source, articles);
        return articles;
    }

    private static void collect(NodeList entries, String source, List<Article> articles) {
        for (int i = 0; i < entries.getLength(); i++) {
            try {
                Article article = toArticle((Element) entries.item(i), source);
                if (article.link() != null && !article.link().isBlank()) {
                    articles.add(article);
                }
            } catch (RuntimeException malformedEntry) {
                // One bad entry must not discard the rest of the feed.
            }
        }
    }

    private static Article toArticle(Element entry, String source) {
        boolean atom = "entry".equals(entry.getLocalName());
        String link = atom ? atomLink(entry) : text(entry, "link");
        String published = atom
                ? firstNonNull(text(entry, "published"), text(entry, "updated"))
                : firstNonNull(text(entry, "pubDate"), text(entry, "date"));
        String excerpt = atom
                ? firstNonNull(text(entry, "summary"), text(entry, "content"))
                : text(entry, "description");
        return new Article(text(entry, "title"), link, source, published, excerpt, null, null);
    }

    /** Atom links live in attributes; the blank/alternate relation wins. */
    private static String atomLink(Element entry) {
        NodeList children = entry.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element link)
                    || !"link".equals(link.getLocalName())) {
                continue;
            }
            String rel = link.getAttribute("rel");
            String href = link.getAttribute("href");
            if ((rel.isBlank() || "alternate".equals(rel)) && !href.isBlank()) {
                return href.trim();
            }
        }
        return null;
    }

    private static Element documentElement(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .getDocumentElement();
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed RSS feed", e);
        }
    }

    /** First descendant element with the local name, as trimmed text or null. */
    private static String text(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            return null;
        }
        Node node = nodes.item(0);
        String value = node.getTextContent();
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String firstNonNull(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }
}
