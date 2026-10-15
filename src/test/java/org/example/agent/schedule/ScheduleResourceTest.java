package org.example.agent.schedule;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.example.agent.pinterest.FakePinterest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class ScheduleResourceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] GIF = {'G', 'I', 'F', '8', '9', 'a', 0, 0};
    private static final String PIN_JSON = "{\"title\":\"15-Minute Vegan Dinners\",\"description\":\"Quick meals. Save this pin!\","
            + "\"hashtags\":[\"#vegan\",\"#dinner\"],\"altText\":\"Bowl of noodles\"}";

    @Inject
    PinScheduleService service;

    @BeforeEach
    void reset() {
        FakePinterest.reset();
        QuarkusTransaction.requiringNew().run(() -> ScheduledPin.deleteAll());
    }

    private static String future() {
        return Instant.now().plus(Duration.ofHours(2)).toString();
    }

    private long scheduleJson(String publishAt) {
        return given().contentType(ContentType.JSON)
                .body("{\"boardId\":\"111\",\"boardName\":\"Vegan Recipes\",\"imageUrl\":\"https://example.com/pin.jpg\","
                        + "\"link\":\"https://example.com/post\",\"publishAt\":\"" + publishAt + "\",\"pin\":" + PIN_JSON + "}")
                .when().post("/api/schedule")
                .then().statusCode(201)
                .body("status", is("SCHEDULED"))
                .body("boardName", is("Vegan Recipes"))
                .body("pin.title", is("15-Minute Vegan Dinners"))
                .body("pin.hashtags[1]", is("#dinner"))
                .body("hasImage", is(false))
                .extract().jsonPath().getLong("id");
    }

    /** Makes a pin due now, as if its time had arrived. */
    private static void makeDue(long id) {
        QuarkusTransaction.requiringNew().run(() -> {
            ScheduledPin p = ScheduledPin.findById(id);
            p.nextAttemptAt = Instant.now().minusSeconds(1);
        });
    }

    private static ScheduledPin load(long id) {
        return QuarkusTransaction.requiringNew().call(() -> ScheduledPin.<ScheduledPin>findById(id));
    }

    @Test
    void schedulesListsAndCancels() {
        long id = scheduleJson(future());
        given().when().get("/api/schedule")
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].id", is((int) id));
        given().when().get("/api/schedule?status=PUBLISHED")
                .then().statusCode(200).body("size()", is(0));

        given().when().delete("/api/schedule/" + id).then().statusCode(204);
        given().when().get("/api/schedule/" + id).then().statusCode(404).body("error", is("not_found"));
    }

    @Test
    void doesNotPublishBeforeItsTime() {
        scheduleJson(future());
        assertEquals(0, service.publishDue());
        assertNull(FakePinterest.lastPin);
    }

    @Test
    void publishesDuePinsOnce() {
        long id = scheduleJson(future());
        makeDue(id);

        assertEquals(1, service.publishDue());
        assertEquals("111", FakePinterest.lastPin.path("board_id").asText());
        assertEquals("https://example.com/pin.jpg", FakePinterest.lastPin.path("media_source").path("url").asText());
        assertEquals("https://example.com/post", FakePinterest.lastPin.path("link").asText());

        given().when().get("/api/schedule/" + id)
                .then().statusCode(200)
                .body("status", is("PUBLISHED"))
                .body("attempts", is(1))
                .body("pinterestUrl", is("https://www.pinterest.com/pin/987654321/"))
                .body("lastError", nullValue());

        FakePinterest.reset();
        assertEquals(0, service.publishDue());
        assertNull(FakePinterest.lastPin, "a published pin is not posted again");
    }

    @Test
    void schedulesAndPublishesUploadedImages() {
        long id = given().multiPart("image", "pin.png", PNG, "image/png")
                .multiPart("boardId", "111")
                .multiPart("publishAt", "2099-01-01T09:00:00+02:00")
                .multiPart("pin", PIN_JSON, "application/json")
                .when().post("/api/schedule/upload")
                .then().statusCode(201)
                .body("hasImage", is(true))
                .body("publishAt", is("2099-01-01T07:00:00Z"))
                .extract().jsonPath().getLong("id");

        byte[] served = given().when().get("/api/schedule/" + id + "/image")
                .then().statusCode(200).contentType("image/png")
                .extract().asByteArray();
        assertEquals(PNG.length, served.length);

        makeDue(id);
        assertEquals(1, service.publishDue());
        assertEquals("image/png", FakePinterest.lastPin.path("media_source").path("content_type").asText());
    }

    @Test
    void rejectsBadRequests() {
        given().contentType(ContentType.JSON)
                .body("{\"boardId\":\"111\",\"imageUrl\":\"https://example.com/pin.jpg\",\"publishAt\":\""
                        + Instant.now().minus(Duration.ofHours(1)) + "\",\"pin\":" + PIN_JSON + "}")
                .when().post("/api/schedule")
                .then().statusCode(400).body("error", is("invalid_time"));
        given().contentType(ContentType.JSON)
                .body("{\"boardId\":\"111\",\"imageUrl\":\"https://example.com/pin.jpg\",\"pin\":" + PIN_JSON + "}")
                .when().post("/api/schedule")
                .then().statusCode(400);
        given().multiPart("image", "pin.png", PNG, "image/png")
                .multiPart("boardId", "111")
                .multiPart("publishAt", "tomorrow 9am")
                .multiPart("pin", PIN_JSON, "application/json")
                .when().post("/api/schedule/upload")
                .then().statusCode(400).body("error", is("invalid_time"));
        given().multiPart("image", "pin.gif", GIF, "image/gif")
                .multiPart("boardId", "111")
                .multiPart("publishAt", future())
                .multiPart("pin", PIN_JSON, "application/json")
                .when().post("/api/schedule/upload")
                .then().statusCode(415);
        given().when().get("/api/schedule").then().body("size()", is(0));
    }

    @Test
    void retriesTransientFailuresThenGivesUp() {
        long id = scheduleJson(future());
        FakePinterest.failWith = 503;

        for (int attempt = 1; attempt <= 3; attempt++) {
            makeDue(id);
            assertEquals(0, service.publishDue());
            ScheduledPin p = load(id);
            assertEquals(attempt, p.attempts);
            assertEquals(attempt < 3 ? ScheduledPin.Status.SCHEDULED : ScheduledPin.Status.FAILED, p.status);
            assertEquals("Pinterest returned an error (HTTP 503).", p.lastError);
        }

        FakePinterest.failWith = 0;
        given().when().post("/api/schedule/" + id + "/retry")
                .then().statusCode(200).body("status", is("SCHEDULED")).body("attempts", is(0));
        assertEquals(1, service.publishDue());
        assertEquals(ScheduledPin.Status.PUBLISHED, load(id).status);
    }

    @Test
    void failsImmediatelyWhenPinterestRejectsThePin() {
        long id = scheduleJson(future());
        makeDue(id);
        FakePinterest.failWith = 404;

        assertEquals(0, service.publishDue());
        given().when().get("/api/schedule/" + id)
                .then().body("status", is("FAILED"))
                .body("attempts", is(1))
                .body("lastError", is("Pinterest rejected the pin: Board not found."));

        given().when().post("/api/schedule/" + id + "/retry").then().statusCode(200);
        given().when().post("/api/schedule/" + id + "/retry").then().statusCode(409).body("error", is("not_failed"));
    }

    @Test
    void retryDelaysGrow() {
        assertEquals(Duration.ofMinutes(1), PinScheduleService.retryDelay(1));
        assertEquals(Duration.ofMinutes(4), PinScheduleService.retryDelay(2));
        assertEquals(Duration.ofMinutes(16), PinScheduleService.retryDelay(3));
    }
}
