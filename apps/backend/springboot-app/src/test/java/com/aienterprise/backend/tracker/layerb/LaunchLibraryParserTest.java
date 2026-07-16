package com.aienterprise.backend.tracker.layerb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

class LaunchLibraryParserTest {

    private final LaunchLibraryParser parser = new LaunchLibraryParser();

    private static final String SAMPLE = """
            {
              "count": 3,
              "results": [
                {"id":"abc-1","name":"Falcon 9 | Starlink Group 6-1",
                 "net":"2024-01-03T23:44:20Z",
                 "status":{"id":3,"name":"Launch Successful","abbrev":"Success"},
                 "rocket":{"configuration":{"full_name":"Falcon 9 Block 5"}},
                 "launch_service_provider":{"id":121,"name":"SpaceX"}},
                {"id":"abc-2","name":"Vulcan | Peregrine",
                 "net":"2024-01-08T07:18:00Z",
                 "status":{"id":4,"name":"Launch Failure","abbrev":"Failure"},
                 "launch_service_provider":{"id":124,"name":"United Launch Alliance"}},
                {"name":"Malformed (no id)","net":"2024-01-09T00:00:00Z"}
              ]
            }
            """;

    @Test
    void parsesResultsAndSkipsMalformedEntries() {
        List<LaunchRecord> launches = parser.parse(SAMPLE);

        assertEquals(2, launches.size());
        LaunchRecord first = launches.get(0);
        assertEquals("abc-1", first.id());
        assertEquals("SpaceX", first.provider());
        assertEquals("Success", first.status());
        assertTrue(first.successful());
        assertEquals("Falcon 9 Block 5", first.vehicleConfiguration());
        assertEquals(Instant.parse("2024-01-03T23:44:20Z"), first.net());
        assertFalse(launches.get(1).successful());
    }

    @Test
    void returnsEmptyListForInvalidOrEmptyJson() {
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse("not json").isEmpty());
        assertTrue(parser.parse("{\"results\":[]}").isEmpty());
    }

    @Test
    void parsesResultsAndNextPageAsOneValidatedPage() {
        String next = "https://ll.thespacedevs.com/2.3.0/launches/"
                + "?format=json&limit=100&offset=100&year=2024";
        String pageJson = """
                {
                  "next":"%s",
                  "results":[
                    {"id":"abc-1","name":"Falcon 9 | Starlink",
                     "net":"2024-01-03T23:44:20Z",
                     "status":{"id":3,"abbrev":"Success"},
                     "launch_service_provider":{"name":"SpaceX"}}
                  ]
                }
                """.formatted(next);

        LaunchPage page = parser.parsePage(pageJson).orElseThrow();

        assertEquals(1, page.launches().size());
        assertEquals(URI.create(next), page.next());
        assertTrue(parser.parsePage("not json").isEmpty());
        assertTrue(parser.parsePage("{}").isEmpty());
    }
}
