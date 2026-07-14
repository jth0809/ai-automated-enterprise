package com.aienterprise.backend.tracker.layerb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
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
}
