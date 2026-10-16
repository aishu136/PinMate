package org.example.agent.bulk;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.example.agent.PinAgent;
import org.example.agent.PinGenerationException;
import org.example.agent.model.PinIdea;
import org.example.agent.model.PinRequest;
import org.example.agent.model.PinResponse;
import org.example.agent.model.SourcePage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class BulkResourceTest {

    @InjectMock
    PinAgent agent;

    @Inject
    BulkService service;

    @BeforeEach
    void reset() {
        QuarkusTransaction.requiringNew().run(() -> {
            BulkItem.deleteAll();
            BulkJob.deleteAll();
        });
    }

    private static String feed(String path) {
        return "http://localhost:" + RestAssured.port + "/fake-feeds/" + path;
    }

    private static PinResponse pinsFor(PinRequest r) {
        PinIdea pin = new PinIdea("=" + r.topic() + " ideas", "About \"" + r.topic() + "\". Save it!",
                List.of("#ideas"), "Alt", List.of("Ideas"), "Bright flat lay");
        return new PinResponse(r.topic(), "claude-opus-5", new SourcePage(r.url(), true, "A page about " + r.topic()),
                1, List.of(pin));
    }

    private long createJob(String itemsJson) {
        return given().contentType(ContentType.JSON)
                .body("{\"source\":\"https://blog.example.com/sitemap.xml\",\"tone\":\"cozy\",\"variations\":1,\"items\":" + itemsJson + "}")
                .when().post("/api/bulk")
                .then().statusCode(201)
                .body("status", is("RUNNING"))
                .extract().jsonPath().getLong("id");
    }

    @Test
    void discoversPagesInSitemapIndexes() {
        given().contentType(ContentType.JSON).body("{\"source\":\"" + feed("sitemap-index.xml") + "\"}")
                .when().post("/api/bulk/discover")
                .then().statusCode(200)
                .body("pages.size()", is(2)) // de-duplicated, non-http dropped, missing child skipped
                .body("pages[0].url", is("https://blog.example.com/easy-vegan-dinners/"))
                .body("pages[0].topic", is("easy vegan dinners"))
                .body("maxItemsPerJob", is(50));
    }

    @Test
    void followsRedirectsAndReadsFeedTitles() {
        given().contentType(ContentType.JSON).body("{\"source\":\"" + feed("moved") + "\"}")
                .when().post("/api/bulk/discover")
                .then().statusCode(200)
                .body("pages[0].topic", is("Cozy Fall Living Room Ideas"));
    }

    @Test
    void reportsUnusableSources() {
        given().contentType(ContentType.JSON).body("{\"source\":\"" + feed("page.html") + "\"}")
                .when().post("/api/bulk/discover")
                .then().statusCode(422).body("error", is("not_a_feed"));
        given().contentType(ContentType.JSON).body("{\"source\":\"" + feed("nope.xml") + "\"}")
                .when().post("/api/bulk/discover")
                .then().statusCode(422).body("error", is("feed_unreachable"));
        given().contentType(ContentType.JSON).body("{\"source\":\"file:///etc/passwd\"}")
                .when().post("/api/bulk/discover")
                .then().statusCode(400).body("error", is("invalid_source"));
    }

    @Test
    void writesPinsForEveryPageAndRecordsFailures() {
        when(agent.generate(any(PinRequest.class), any(), any())).thenAnswer(inv -> {
            PinRequest r = inv.getArgument(0);
            if (r.url().contains("broken")) {
                throw new PinGenerationException(502, "Claude returned no pin ideas");
            }
            return pinsFor(r);
        });
        long id = createJob("[{\"url\":\"https://blog.example.com/easy-vegan-dinners/\"},"
                + "{\"url\":\"https://blog.example.com/broken\"},"
                + "{\"url\":\"https://blog.example.com/fall\",\"topic\":\"Fall decor\"},"
                + "{\"url\":\"https://blog.example.com/fall\"}]");

        assertEquals(3, service.processPending());
        assertEquals(0, service.processPending());

        ArgumentCaptor<PinRequest> requests = ArgumentCaptor.forClass(PinRequest.class);
        verify(agent, atLeastOnce()).generate(requests.capture(), any(), any());
        assertTrue(requests.getAllValues().stream().allMatch(r -> "cozy".equals(r.tone()) && r.variations() == 1));

        given().when().get("/api/bulk/" + id)
                .then().statusCode(200)
                .body("status", is("FINISHED"))
                .body("counts.total", is(3))
                .body("counts.done", is(2))
                .body("counts.failed", is(1))
                .body("items[0].topic", is("easy vegan dinners"))
                .body("items[0].status", is("DONE"))
                .body("items[0].pageRead", is(true))
                .body("items[0].pins[0].title", is("=easy vegan dinners ideas"))
                .body("items[1].status", is("FAILED"))
                .body("items[1].error", is("Claude returned no pin ideas"))
                .body("items[1].pins.size()", is(0))
                .body("items[2].topic", is("Fall decor"));

        given().when().get("/api/bulk")
                .then().statusCode(200)
                .body("[0].id", is((int) id))
                .body("[0].counts.done", is(2))
                .body("[0].items", nullValue());

        String csv = given().when().get("/api/bulk/" + id + "/pins.csv")
                .then().statusCode(200).contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("pinmate-bulk-" + id + ".csv"))
                .extract().asString();
        List<String> lines = csv.lines().toList();
        assertEquals(3, lines.size(), "header plus one row per pin");
        assertTrue(lines.get(1).startsWith("\"https://blog.example.com/easy-vegan-dinners/\",\"easy vegan dinners\",1,\"'=easy"),
                "formula-looking cells are neutralised: " + lines.get(1));
        assertTrue(lines.get(1).contains("\"About \"\"easy vegan dinners\"\". Save it!\""), lines.get(1));

        // Retry re-queues only the failed page.
        when(agent.generate(any(PinRequest.class), any(), any())).thenAnswer(inv -> pinsFor(inv.getArgument(0)));
        given().when().post("/api/bulk/" + id + "/retry").then().statusCode(200).body("counts.pending", is(1));
        assertEquals(1, service.processPending());
        given().when().get("/api/bulk/" + id).then().body("counts.done", is(3)).body("items[1].error", nullValue());

        given().when().delete("/api/bulk/" + id).then().statusCode(204);
        given().when().get("/api/bulk/" + id).then().statusCode(404).body("error", is("not_found"));
    }

    @Test
    void cancelStopsPagesThatHaveNotStarted() {
        long id = createJob("[{\"url\":\"https://blog.example.com/a\"},{\"url\":\"https://blog.example.com/b\"}]");
        given().when().post("/api/bulk/" + id + "/cancel")
                .then().statusCode(200)
                .body("status", is("FINISHED"))
                .body("counts.cancelled", is(2));
        assertEquals(0, service.processPending());
        verify(agent, never()).generate(argThat(r -> r.url().startsWith("https://blog.example.com/")), any(), any());
    }

    @Test
    void validatesJobs() {
        given().contentType(ContentType.JSON).body("{\"items\":[]}")
                .when().post("/api/bulk").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"items\":[{\"url\":\"not a url\"}]}")
                .when().post("/api/bulk").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"items\":[{\"url\":\"ftp://example.com/x\"}]}")
                .when().post("/api/bulk").then().statusCode(400).body("error", is("invalid_url"));
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            many.append(i == 0 ? "" : ",").append("{\"url\":\"https://example.com/p").append(i).append("\"}");
        }
        given().contentType(ContentType.JSON).body("{\"items\":" + many + "]}")
                .when().post("/api/bulk").then().statusCode(400).body("error", is("too_many_items"));
        given().when().get("/api/bulk").then().body("size()", is(0));
    }
}
