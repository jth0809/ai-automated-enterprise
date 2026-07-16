package com.aienterprise.backend.tracker.layerb;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;

import com.aienterprise.backend.tracker.domain.LayerBMetric;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Static validator for the Layer B measurement seed. Fails closed: unknown
 * metric codes, out-of-range pillars, unknown basis, negative values, malformed
 * hashes, or any prohibited copyright-risk key (body/quote/html/pdf/image/...)
 * produce errors and the item is not materialized.
 */
public class LayerBDatasetValidator {

    /** Result of validating a Layer B dataset. */
    public record ValidatedLayerB(List<LayerBMetric> metrics, List<String> errors) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> CODES = Set.of(
            "LAUNCH_PRICE_LEO",
            "ANNUAL_LAUNCH_COUNT",
            "ANNUAL_UPMASS_TONNES",
            "LEO_PUBLISHED_PRICE_FRONTIER_REAL_2025",
            "ANNUAL_FALCON_FAMILY_LAUNCH_COUNT");
    private static final Set<String> BASES = Set.of("MEASURED", "PUBLISHED_PRICE", "CONSTRUCTED");
    private static final Set<String> PROHIBITED = Set.of(
            "quote", "body", "bodyhtml", "bodytext", "html", "pdf", "image",
            "excerpt", "attachment", "attachments", "rawhtml", "warc");

    public ValidatedLayerB validate(Resource resource) {
        try (var in = resource.getInputStream()) {
            return validateNode(JSON.readTree(in));
        } catch (Exception e) {
            return new ValidatedLayerB(List.of(), List.of("layer-b: cannot read resource"));
        }
    }

    public ValidatedLayerB validateJson(String json) {
        try {
            return validateNode(JSON.readTree(json));
        } catch (Exception e) {
            return new ValidatedLayerB(List.of(), List.of("layer-b: invalid json"));
        }
    }

    private ValidatedLayerB validateNode(JsonNode root) {
        List<String> errors = new ArrayList<>();
        List<LayerBMetric> metrics = new ArrayList<>();
        if (root == null || !root.isArray()) {
            return new ValidatedLayerB(List.of(), List.of("layer-b: root must be an array"));
        }
        int index = 0;
        for (JsonNode item : root) {
            String at = "metric[" + index++ + "]";
            item.fieldNames().forEachRemaining(name -> {
                if (PROHIBITED.contains(name.toLowerCase())) {
                    errors.add(at + ": prohibited field " + name);
                }
            });
            String code = item.path("metricCode").asText("");
            if (!CODES.contains(code)) {
                errors.add(at + ": unknown metricCode " + code);
            }
            int pillar = item.path("pillar").asInt(0);
            if (pillar < 1 || pillar > 6) {
                errors.add(at + ": pillar out of range");
            }
            String basis = item.path("basis").asText("");
            if (!BASES.contains(basis)) {
                errors.add(at + ": bad basis " + basis);
            }
            String hash = item.path("contentSha256").asText("");
            if (!SHA256.matcher(hash).matches()) {
                errors.add(at + ": bad content_sha256");
            }
            BigDecimal value = item.path("value").decimalValue();
            if (value.signum() < 0) {
                errors.add(at + ": negative value");
            }
            final String path = at;
            if (errors.stream().noneMatch(error -> error.startsWith(path))) {
                metrics.add(new LayerBMetric(0, code, pillar,
                        LocalDate.parse(item.path("observedOn").asText()), value,
                        item.path("unit").asText(), basis, item.path("sourceLabel").asText(),
                        item.path("sourceUrl").asText(),
                        LocalDate.parse(item.path("accessedOn").asText()),
                        hash, item.path("factSummary").asText()));
            }
        }
        return new ValidatedLayerB(metrics, errors);
    }
}
