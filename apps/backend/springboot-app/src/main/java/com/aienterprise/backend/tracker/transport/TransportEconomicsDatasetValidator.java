package com.aienterprise.backend.tracker.transport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Strict, content-safe validator for the immutable WP3.3 numeric corpus.
 * Sparse valid evidence is retained; sufficiency is decided by the projection
 * model rather than by silently discarding observations here.
 */
public class TransportEconomicsDatasetValidator {

    public record CpiEvidence(
            int year, BigDecimal value, String seriesId,
            String sourceLabel, String sourceUrl, String sourceLocator,
            LocalDate accessedOn, String contentSha256, String factSummary) {
    }

    public record AnnualCountEvidence(
            AnnualLaunchCount count,
            String sourceLabel, String sourceUrl, String sourceLocator,
            LocalDate accessedOn, String contentSha256, String factSummary) {
    }

    public record ValidatedTransportDataset(
            String datasetVersion,
            TransportAssumption assumption,
            List<CpiEvidence> cpi,
            List<AnnualCountEvidence> annualCounts,
            List<TransportPriceObservation> observations,
            Map<Integer, TransportPriceObservation> annualFrontier,
            List<String> errors) {

        public ValidatedTransportDataset {
            cpi = List.copyOf(cpi);
            annualCounts = List.copyOf(annualCounts);
            observations = List.copyOf(observations);
            annualFrontier = Map.copyOf(annualFrontier);
            errors = List.copyOf(errors);
        }
    }

    private record Provenance(
            String sourceLabel, String sourceUrl, String sourceLocator,
            LocalDate accessedOn, String contentSha256, String factSummary) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> PROHIBITED = Set.of(
            "quote", "body", "bodyhtml", "bodytext", "html", "pdf", "image",
            "excerpt", "attachment", "attachments", "rawhtml", "warc", "binary");
    private static final Set<String> ROOT_KEYS = Set.of(
            "datasetVersion", "assumption", "cpi", "annualLaunchCounts",
            "priceObservations");
    private static final Set<String> ASSUMPTION_KEYS = Set.of(
            "version", "modelVersion", "centralTargetUsdPerKg", "easyTargetUsdPerKg",
            "hardTargetUsdPerKg", "priceBasisYear", "horizonYears", "weakFitR2",
            "wideningFactor");
    private static final Set<String> CPI_KEYS = Set.of(
            "year", "value", "seriesId", "sourceLabel", "sourceUrl", "sourceLocator",
            "accessedOn", "contentSha256", "factSummary");
    private static final Set<String> COUNT_KEYS = Set.of(
            "year", "count", "sourceLabel", "sourceUrl", "sourceLocator", "accessedOn",
            "contentSha256", "factSummary");
    private static final Set<String> PRICE_KEYS = Set.of(
            "observationYear", "vehicleFamily", "vehicleVariant", "operational",
            "configurationMatch", "publishedPriceUsd", "maxLeoPayloadKg", "sourceLabel",
            "sourceUrl", "sourceLocator", "accessedOn", "contentSha256", "factSummary");

    public ValidatedTransportDataset validate(Resource resource) {
        try (var input = resource.getInputStream()) {
            return validateNode(JSON.readTree(input));
        } catch (Exception failure) {
            return empty("transport-economics: cannot read resource");
        }
    }

    public ValidatedTransportDataset validateJson(String json) {
        try {
            return validateNode(JSON.readTree(json));
        } catch (Exception failure) {
            return empty("transport-economics: invalid json");
        }
    }

    private ValidatedTransportDataset validateNode(JsonNode root) {
        List<String> errors = new ArrayList<>();
        if (root == null || !root.isObject()) {
            return empty("root: must be an object");
        }
        scanProhibited(root, "root", errors);
        checkAllowed(root, ROOT_KEYS, "root", errors);

        String datasetVersion = text(root, "datasetVersion", "root", 80, errors);
        TransportAssumption assumption = parseAssumption(root.get("assumption"), errors);
        List<CpiEvidence> cpi = parseCpi(root.get("cpi"), errors);
        List<AnnualCountEvidence> annualCounts =
                parseAnnualCounts(root.get("annualLaunchCounts"), errors);
        List<TransportPriceObservation> observations = parsePrices(
                root.get("priceObservations"), assumption, cpi, annualCounts, errors);
        Map<Integer, TransportPriceObservation> frontier = annualFrontier(observations);
        validateFrontierCumulative(frontier, errors);

        return new ValidatedTransportDataset(
                datasetVersion, assumption, cpi, annualCounts, observations, frontier, errors);
    }

    private TransportAssumption parseAssumption(JsonNode node, List<String> errors) {
        String path = "assumption";
        if (node == null || !node.isObject()) {
            errors.add(path + ": must be an object");
            return null;
        }
        checkAllowed(node, ASSUMPTION_KEYS, path, errors);
        String version = text(node, "version", path, 80, errors);
        String modelVersion = text(node, "modelVersion", path, 80, errors);
        BigDecimal central = positiveDecimal(node, "centralTargetUsdPerKg", path, errors);
        BigDecimal easy = positiveDecimal(node, "easyTargetUsdPerKg", path, errors);
        BigDecimal hard = positiveDecimal(node, "hardTargetUsdPerKg", path, errors);
        Integer basisYear = integer(node, "priceBasisYear", path, errors);
        Integer horizon = integer(node, "horizonYears", path, errors);
        BigDecimal weakFit = decimal(node, "weakFitR2", path, errors);
        BigDecimal widening = decimal(node, "wideningFactor", path, errors);
        if (central != null && easy != null && hard != null
                && !(hard.compareTo(central) < 0 && central.compareTo(easy) < 0)) {
            errors.add(path + ": invalid target ordering; require hard < central < easy");
        }
        if (basisYear != null && basisYear != 2025) {
            errors.add(path + ": priceBasisYear must be 2025");
        }
        if (horizon != null && (horizon < 1 || horizon > 150)) {
            errors.add(path + ": horizonYears must be within 1..150");
        }
        if (weakFit != null
                && (weakFit.compareTo(BigDecimal.ZERO) < 0
                        || weakFit.compareTo(BigDecimal.ONE) > 0)) {
            errors.add(path + ": weakFitR2 must be within 0..1");
        }
        if (widening != null && widening.compareTo(BigDecimal.ONE) < 0) {
            errors.add(path + ": wideningFactor must be at least 1");
        }
        if (version == null || modelVersion == null || central == null || easy == null
                || hard == null || basisYear == null || horizon == null
                || weakFit == null || widening == null) {
            return null;
        }
        return new TransportAssumption(
                version, modelVersion, central.setScale(4), easy.setScale(4), hard.setScale(4),
                basisYear, horizon, weakFit.setScale(5), widening.setScale(2));
    }

    private List<CpiEvidence> parseCpi(JsonNode node, List<String> errors) {
        if (node == null || !node.isArray()) {
            errors.add("cpi: must be an array");
            return List.of();
        }
        List<CpiEvidence> result = new ArrayList<>();
        Set<Integer> years = new HashSet<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.get(index);
            String path = "cpi[" + index + "]";
            int before = errors.size();
            if (!item.isObject()) {
                errors.add(path + ": must be an object");
                continue;
            }
            checkAllowed(item, CPI_KEYS, path, errors);
            Integer year = integer(item, "year", path, errors);
            BigDecimal value = positiveDecimal(item, "value", path, errors);
            String seriesId = text(item, "seriesId", path, 40, errors);
            Provenance provenance = provenance(item, path, errors);
            if (seriesId != null && !"CUUR0000SA0".equals(seriesId)) {
                errors.add(path + ": seriesId must be CUUR0000SA0");
            }
            if (year != null && !years.add(year)) {
                errors.add(path + ": duplicate CPI year " + year);
            }
            if (errors.size() == before && provenance != null) {
                result.add(new CpiEvidence(
                        year, value.setScale(3), seriesId,
                        provenance.sourceLabel(), provenance.sourceUrl(),
                        provenance.sourceLocator(), provenance.accessedOn(),
                        provenance.contentSha256(), provenance.factSummary()));
            }
        }
        result.sort(Comparator.comparingInt(CpiEvidence::year));
        if (result.stream().noneMatch(row -> row.year() == 2025)) {
            errors.add("cpi: missing 2025 basis row");
        }
        return result;
    }

    private List<AnnualCountEvidence> parseAnnualCounts(
            JsonNode node, List<String> errors) {
        if (node == null || !node.isArray()) {
            errors.add("annualLaunchCounts: must be an array");
            return List.of();
        }
        List<AnnualCountEvidence> result = new ArrayList<>();
        Set<Integer> years = new HashSet<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.get(index);
            String path = "annualLaunchCounts[" + index + "]";
            int before = errors.size();
            if (!item.isObject()) {
                errors.add(path + ": must be an object");
                continue;
            }
            checkAllowed(item, COUNT_KEYS, path, errors);
            Integer year = integer(item, "year", path, errors);
            Long count = longInteger(item, "count", path, errors);
            Provenance provenance = provenance(item, path, errors);
            if (count != null && count < 0) {
                errors.add(path + ": count must be nonnegative");
            }
            if (year != null && !years.add(year)) {
                errors.add(path + ": duplicate annual year " + year);
            }
            if (errors.size() == before && provenance != null) {
                result.add(new AnnualCountEvidence(
                        new AnnualLaunchCount(year, count),
                        provenance.sourceLabel(), provenance.sourceUrl(),
                        provenance.sourceLocator(), provenance.accessedOn(),
                        provenance.contentSha256(), provenance.factSummary()));
            }
        }
        result.sort(Comparator.comparingInt(row -> row.count().year()));
        if (result.size() < 3) {
            errors.add("annualLaunchCounts: requires at least three complete years");
        }
        for (int index = 1; index < result.size(); index++) {
            int previous = result.get(index - 1).count().year();
            int current = result.get(index).count().year();
            if (current != previous + 1) {
                errors.add("annualLaunchCounts: annual years must be contiguous");
                break;
            }
        }
        return result;
    }

    private List<TransportPriceObservation> parsePrices(
            JsonNode node,
            TransportAssumption assumption,
            List<CpiEvidence> cpi,
            List<AnnualCountEvidence> annualCounts,
            List<String> errors) {
        if (node == null || !node.isArray()) {
            errors.add("priceObservations: must be an array");
            return List.of();
        }
        Map<Integer, BigDecimal> cpiByYear = new LinkedHashMap<>();
        for (CpiEvidence row : cpi) {
            cpiByYear.put(row.year(), row.value());
        }
        Map<Integer, Long> countByYear = new LinkedHashMap<>();
        for (AnnualCountEvidence row : annualCounts) {
            countByYear.put(row.count().year(), row.count().launches());
        }
        BigDecimal cpiBasis = cpiByYear.get(2025);
        List<TransportPriceObservation> result = new ArrayList<>();
        Set<String> naturalKeys = new HashSet<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.get(index);
            String path = "priceObservations[" + index + "]";
            int before = errors.size();
            if (!item.isObject()) {
                errors.add(path + ": must be an object");
                continue;
            }
            checkAllowed(item, PRICE_KEYS, path, errors);
            Integer year = integer(item, "observationYear", path, errors);
            String family = text(item, "vehicleFamily", path, 40, errors);
            String variant = text(item, "vehicleVariant", path, 80, errors);
            requireTrue(item, "operational", path, errors);
            requireTrue(item, "configurationMatch", path, errors);
            BigDecimal price = positiveDecimal(item, "publishedPriceUsd", path, errors);
            BigDecimal payload = positiveDecimal(item, "maxLeoPayloadKg", path, errors);
            Provenance provenance = provenance(item, path, errors);
            if (family != null && !"FALCON".equals(family)) {
                errors.add(path + ": vehicleFamily must be FALCON");
            }
            if (variant != null && !(variant.startsWith("FALCON_9_")
                    || variant.startsWith("FALCON_HEAVY_"))) {
                errors.add(path + ": unsupported operational Falcon variant");
            }
            if (year != null && family != null && variant != null
                    && !naturalKeys.add(year + "|" + family + "|" + variant)) {
                errors.add(path + ": duplicate price observation natural key");
            }
            BigDecimal cpiObservation = year == null ? null : cpiByYear.get(year);
            if (year != null && cpiObservation == null) {
                errors.add(path + ": missing CPI row for " + year);
            }
            long cumulative = 0;
            if (year != null) {
                for (Map.Entry<Integer, Long> entry : countByYear.entrySet()) {
                    if (entry.getKey() <= year) {
                        cumulative += entry.getValue();
                    }
                }
                if (countByYear.isEmpty() || countByYear.keySet().stream()
                        .mapToInt(Integer::intValue).max().orElse(0) < year) {
                    errors.add(path + ": annual launch counts do not cover observation year");
                } else if (cumulative <= 0) {
                    errors.add(path + ": cumulative launches must be positive");
                }
            }
            if (errors.size() == before && assumption != null && cpiBasis != null
                    && provenance != null) {
                BigDecimal nominalRaw = price.divide(payload, 8, RoundingMode.HALF_UP);
                BigDecimal real = nominalRaw.multiply(cpiBasis)
                        .divide(cpiObservation, 4, RoundingMode.HALF_UP);
                result.add(new TransportPriceObservation(
                        0, year, family, variant, price.setScale(2), payload.setScale(2),
                        nominalRaw.setScale(4, RoundingMode.HALF_UP),
                        cpiObservation.setScale(3), cpiBasis.setScale(3), real,
                        cumulative, provenance.sourceLabel(), provenance.sourceUrl(),
                        provenance.sourceLocator(), provenance.accessedOn(),
                        provenance.contentSha256(), provenance.factSummary()));
            }
        }
        if (result.isEmpty()) {
            errors.add("priceObservations: requires at least one valid observation");
        }
        return result;
    }

    private static Map<Integer, TransportPriceObservation> annualFrontier(
            List<TransportPriceObservation> observations) {
        Map<Integer, TransportPriceObservation> result = new LinkedHashMap<>();
        Comparator<TransportPriceObservation> comparator = Comparator
                .comparing(TransportPriceObservation::realBasisUsdPerKg)
                .thenComparing(TransportPriceObservation::vehicleVariant);
        observations.stream()
                .sorted(Comparator.comparingInt(TransportPriceObservation::observationYear)
                        .thenComparing(TransportPriceObservation::vehicleVariant))
                .forEach(observation -> result.merge(
                        observation.observationYear(), observation,
                        (left, right) -> comparator.compare(left, right) <= 0 ? left : right));
        return result;
    }

    private static void validateFrontierCumulative(
            Map<Integer, TransportPriceObservation> frontier, List<String> errors) {
        long previous = -1;
        for (TransportPriceObservation observation : frontier.values()) {
            if (previous >= 0 && observation.cumulativeFamilyLaunches() <= previous) {
                errors.add("priceObservations: cumulative launches must strictly increase "
                        + "between frontier years");
                return;
            }
            previous = observation.cumulativeFamilyLaunches();
        }
    }

    private static Provenance provenance(
            JsonNode node, String path, List<String> errors) {
        String label = text(node, "sourceLabel", path, 200, errors);
        String url = text(node, "sourceUrl", path, 600, errors);
        String locator = text(node, "sourceLocator", path, 300, errors);
        String accessedText = text(node, "accessedOn", path, 10, errors);
        String hash = text(node, "contentSha256", path, 64, errors);
        String summary = text(node, "factSummary", path, 500, errors);
        if (url != null && !isHttps(url)) {
            errors.add(path + ": sourceUrl must use HTTPS");
        }
        if (hash != null && !SHA256.matcher(hash).matches()) {
            errors.add(path + ": bad contentSha256");
        }
        LocalDate accessedOn = null;
        if (accessedText != null) {
            try {
                accessedOn = LocalDate.parse(accessedText);
            } catch (RuntimeException malformed) {
                errors.add(path + ": bad accessedOn");
            }
        }
        if (label == null || url == null || locator == null || accessedOn == null
                || hash == null || summary == null) {
            return null;
        }
        return new Provenance(label, url, locator, accessedOn, hash, summary);
    }

    private static boolean isHttps(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static void requireTrue(
            JsonNode node, String field, String path, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean() || !value.booleanValue()) {
            errors.add(path + ": " + field + " must be true");
        }
    }

    private static BigDecimal positiveDecimal(
            JsonNode node, String field, String path, List<String> errors) {
        BigDecimal value = decimal(node, field, path, errors);
        if (value != null && value.signum() <= 0) {
            errors.add(path + ": " + field + " must be positive");
        }
        return value;
    }

    private static BigDecimal decimal(
            JsonNode node, String field, String path, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            errors.add(path + ": " + field + " must be numeric");
            return null;
        }
        return value.decimalValue();
    }

    private static Integer integer(
            JsonNode node, String field, String path, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            errors.add(path + ": " + field + " must be an integer");
            return null;
        }
        return value.intValue();
    }

    private static Long longInteger(
            JsonNode node, String field, String path, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            errors.add(path + ": " + field + " must be an integer");
            return null;
        }
        return value.longValue();
    }

    private static String text(
            JsonNode node, String field, String path, int maxLength, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            errors.add(path + ": " + field + " must be nonblank text");
            return null;
        }
        String text = value.textValue().trim();
        if (text.length() > maxLength) {
            errors.add(path + ": " + field + " exceeds " + maxLength + " characters");
            return null;
        }
        return text;
    }

    private static void checkAllowed(
            JsonNode node, Set<String> allowed, String path, List<String> errors) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                errors.add(path + ": unknown field " + field);
            }
        });
    }

    private static void scanProhibited(
            JsonNode node, String path, List<String> errors) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String childPath = path + "." + entry.getKey();
                if (PROHIBITED.contains(entry.getKey().toLowerCase())) {
                    errors.add(childPath + ": prohibited field " + entry.getKey());
                }
                scanProhibited(entry.getValue(), childPath, errors);
            });
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                scanProhibited(node.get(index), path + "[" + index + "]", errors);
            }
        }
    }

    private static ValidatedTransportDataset empty(String error) {
        return new ValidatedTransportDataset(
                null, null, List.of(), List.of(), List.of(), Map.of(), List.of(error));
    }
}
