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
 * Minimal RSS 2.0 parser over the JDK XML stack — no third-party feed
 * library, which keeps the dependency (and Trivy) surface small. The parser
 * is hardened against XXE (external entities and DOCTYPE disabled).
 */
public class RssParser {

    public List<Article> parse(String xml, String source) {
        Element root = documentElement(xml);
        List<Article> articles = new ArrayList<>();
        NodeList items = root.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            articles.add(new Article(
                    text(item, "title"),
                    text(item, "link"),
                    source,
                    text(item, "pubDate"),
                    text(item, "description"),
                    null));
        }
        return articles;
    }

    private static Element documentElement(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
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

    /** First direct-or-nested child element text, or null when absent/blank. */
    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        Node node = nodes.item(0);
        String value = node.getTextContent();
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
