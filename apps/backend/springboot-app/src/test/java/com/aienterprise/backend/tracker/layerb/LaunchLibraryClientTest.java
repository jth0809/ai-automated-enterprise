package com.aienterprise.backend.tracker.layerb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class LaunchLibraryClientTest {

    private static final String SAMPLE = """
            {"count":1,"results":[
              {"id":"abc-1","name":"Falcon 9 | Starlink","net":"2024-01-03T23:44:20Z",
               "status":{"id":3,"abbrev":"Success"},
               "launch_service_provider":{"name":"SpaceX"}}]}
            """;

    @Test
    void fetchParsesTheTransportResponse() {
        var client = new LaunchLibraryClient(uri -> SAMPLE);

        List<LaunchRecord> launches = client.fetch(URI.create(LaunchLibraryClient.BASE));

        assertEquals(1, launches.size());
        assertEquals("SpaceX", launches.get(0).provider());
    }

    @Test
    void aFeedErrorYieldsAnEmptyListRatherThanThrowing() {
        var client = new LaunchLibraryClient(uri -> {
            throw new RuntimeException("feed down");
        });

        assertTrue(client.fetch(URI.create(LaunchLibraryClient.BASE)).isEmpty());
    }

    @Test
    void fetchAllFollowsSameHostPagesAndReturnsOnlyACompleteResult() {
        URI first = LaunchLibraryClient.yearUri(2024);
        URI second = URI.create(LaunchLibraryClient.BASE
                + "?format=json&include_suborbital=false&limit=100&mode=list"
                + "&offset=100&ordering=net&year=2024");
        List<URI> calls = new ArrayList<>();
        var client = new LaunchLibraryClient(uri -> {
            calls.add(uri);
            if (uri.equals(first)) {
                return page(second.toString(), "abc-1", "2024-01-03T23:44:20Z");
            }
            if (uri.equals(second)) {
                return page(null, "abc-2", "2024-02-03T23:44:20Z");
            }
            throw new AssertionError("unexpected URI " + uri);
        });

        List<LaunchRecord> launches = client.fetchAll(first, 4).orElseThrow();

        assertEquals(List.of(first, second), calls);
        assertEquals(List.of("abc-1", "abc-2"),
                launches.stream().map(LaunchRecord::id).toList());
    }

    @Test
    void fetchAllRejectsCrossHostContinuationInsteadOfReturningPartialData() {
        URI first = LaunchLibraryClient.yearUri(2024);
        var client = new LaunchLibraryClient(uri ->
                page("https://attacker.example/launches/?offset=100",
                        "abc-1", "2024-01-03T23:44:20Z"));

        assertTrue(client.fetchAll(first, 4).isEmpty());
    }

    @Test
    void yearUriUsesTheCurrentBoundedOrbitalLaunchListContract() {
        assertEquals(
                "https://ll.thespacedevs.com/2.3.0/launches/"
                        + "?format=json&include_suborbital=false&limit=100&mode=list"
                        + "&ordering=net&year=2024",
                LaunchLibraryClient.yearUri(2024).toString());
    }

    private static String page(String next, String id, String net) {
        String nextJson = next == null ? "null" : "\"" + next + "\"";
        return """
                {"next":%s,"results":[
                  {"id":"%s","name":"Test launch","net":"%s",
                   "status":{"id":3,"abbrev":"Success"},
                   "launch_service_provider":{"name":"Provider"}}]}
                """.formatted(nextJson, id, net);
    }
}
