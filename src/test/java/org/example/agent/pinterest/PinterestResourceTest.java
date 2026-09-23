package org.example.agent.pinterest;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end through the real REST client against {@link FakePinterest}. */
@QuarkusTest
class PinterestResourceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final String PIN_JSON = """
            {"title":"15-Minute Vegan Dinners","description":"Quick meals for busy nights. Save this pin!",
             "hashtags":["vegan","#easyrecipes"],"altText":"Bowl of noodles",
             "suggestedBoards":["Vegan Recipes"],"imageIdea":"overlay text"}""";

    @BeforeEach
    void reset() {
        FakePinterest.reset();
    }

    @Test
    void publishesWithImageUrl() {
        given().contentType(ContentType.JSON)
                .body("{\"boardId\":\"111\",\"link\":\"https://example.com/recipes\","
                        + "\"imageUrl\":\"https://example.com/pin.jpg\",\"pin\":" + PIN_JSON + "}")
                .when().post("/api/pinterest/pins")
                .then()
                .statusCode(200)
                .body("id", is("987654321"))
                .body("boardId", is("111"))
                .body("url", is("https://www.pinterest.com/pin/987654321/"));

        assertEquals("Bearer test-token", FakePinterest.lastAuthorization);
        JsonNode sent = FakePinterest.lastPin;
        assertEquals("111", sent.path("board_id").asText());
        assertEquals("15-Minute Vegan Dinners", sent.path("title").asText());
        assertEquals("Quick meals for busy nights. Save this pin!\n\n#vegan #easyrecipes", sent.path("description").asText());
        assertEquals("Bowl of noodles", sent.path("alt_text").asText());
        assertEquals("https://example.com/recipes", sent.path("link").asText());
        assertEquals("image_url", sent.path("media_source").path("source_type").asText());
        assertEquals("https://example.com/pin.jpg", sent.path("media_source").path("url").asText());
        assertFalse(sent.has("suggestedBoards") || sent.has("imageIdea"), "app-only fields must not be sent");
        assertFalse(sent.path("media_source").has("data"), "null fields are omitted");
    }

    @Test
    void publishesUploadedImageAsBase64() {
        given().multiPart("image", "pin.png", PNG, "image/png")
                .multiPart("boardId", "111")
                .multiPart("pin", PIN_JSON, "application/json")
                .when().post("/api/pinterest/pins/upload")
                .then().statusCode(200).body("id", is("987654321"));

        JsonNode media = FakePinterest.lastPin.path("media_source");
        assertEquals("image_base64", media.path("source_type").asText());
        assertEquals("image/png", media.path("content_type").asText());
        assertEquals(Base64.getEncoder().encodeToString(PNG), media.path("data").asText());
        assertTrue(FakePinterest.lastPin.path("link").isMissingNode());
    }

    @Test
    void rejectsGifUploadBeforeCallingPinterest() {
        given().multiPart("image", "pin.gif", "GIF89a......".getBytes(), "image/gif")
                .multiPart("boardId", "111")
                .multiPart("pin", PIN_JSON, "application/json")
                .when().post("/api/pinterest/pins/upload")
                .then().statusCode(415).body("error", is("invalid_image"));
        assertNull(FakePinterest.lastPin);
    }

    @Test
    void validatesPublishRequest() {
        given().contentType(ContentType.JSON)
                .body("{\"boardId\":\"my-board\",\"imageUrl\":\"https://example.com/pin.jpg\",\"pin\":" + PIN_JSON + "}")
                .when().post("/api/pinterest/pins")
                .then().statusCode(400);
        given().contentType(ContentType.JSON)
                .body("{\"boardId\":\"111\",\"pin\":" + PIN_JSON + "}")
                .when().post("/api/pinterest/pins")
                .then().statusCode(400);
        assertNull(FakePinterest.lastPin);
    }

    @Test
    void mapsPinterestErrors() {
        FakePinterest.failWith = 404;
        given().contentType(ContentType.JSON)
                .body("{\"boardId\":\"999\",\"imageUrl\":\"https://example.com/pin.jpg\",\"pin\":" + PIN_JSON + "}")
                .when().post("/api/pinterest/pins")
                .then().statusCode(404)
                .body("error", is("pinterest_not_found"))
                .body("message", is("Pinterest: Board not found."));

        FakePinterest.failWith = 401;
        given().contentType(ContentType.JSON)
                .body("{\"boardId\":\"111\",\"imageUrl\":\"https://example.com/pin.jpg\",\"pin\":" + PIN_JSON + "}")
                .when().post("/api/pinterest/pins")
                .then().statusCode(503).body("error", is("pinterest_not_configured"));
    }

    @Test
    void listsBoardsAcrossPages() {
        given().when().get("/api/pinterest/boards")
                .then()
                .statusCode(200)
                .body("size()", is(2))
                .body("[0].name", is("Vegan Recipes"))
                .body("[0].pinCount", is(42))
                .body("[1].id", is("222"));
        assertEquals(String.valueOf(PinPublisher.BOARD_PAGE_SIZE), FakePinterest.lastPageSize);
    }
}
