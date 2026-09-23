package org.example.agent;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.example.agent.model.PinIdea;
import org.example.agent.model.PinImage;
import org.example.agent.model.PinRequest;
import org.example.agent.model.PinResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class PinContentResourceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @InjectMock
    PinAgent agent;

    @Test
    void generatesPins() {
        PinIdea pin = new PinIdea("15-Minute Vegan Dinners", "Quick meals. Save for later!",
                List.of("#vegan"), "Bowl of noodles", List.of("Vegan Recipes"), "Overhead shot");
        when(agent.generate(any(PinRequest.class)))
                .thenReturn(new PinResponse("vegan dinners", "claude-opus-5", null, 1, List.of(pin)));

        given().contentType(ContentType.JSON)
                .body("{\"topic\":\"vegan dinners\",\"variations\":1}")
                .when().post("/api/pins/generate")
                .then()
                .statusCode(200)
                .body("model", is("claude-opus-5"))
                .body("pins[0].title", is("15-Minute Vegan Dinners"))
                .body("pins[0].hashtags[0]", is("#vegan"));
    }

    @Test
    void rejectsBlankTopic() {
        given().contentType(ContentType.JSON)
                .body("{\"topic\":\"  \"}")
                .when().post("/api/pins/generate")
                .then().statusCode(400);
        verify(agent, never()).generate(any());
    }

    @Test
    void rejectsTooManyVariationsAndBadUrl() {
        given().contentType(ContentType.JSON)
                .body("{\"topic\":\"x\",\"variations\":9}")
                .when().post("/api/pins/generate")
                .then().statusCode(400);
        given().contentType(ContentType.JSON)
                .body("{\"topic\":\"x\",\"url\":\"not a url\"}")
                .when().post("/api/pins/generate")
                .then().statusCode(400);
    }

    @Test
    void mapsGenerationFailure() {
        when(agent.generate(any(PinRequest.class))).thenThrow(new PinGenerationException(422, "declined"));

        given().contentType(ContentType.JSON)
                .body("{\"topic\":\"x\"}")
                .when().post("/api/pins/generate")
                .then()
                .statusCode(422)
                .body("error", is("generation_failed"));
    }

    @Test
    void generatesFromUploadedImage() {
        when(agent.generate(any(PinRequest.class), any(PinImage.class)))
                .thenReturn(new PinResponse(null, "claude-opus-5", null, 1, List.of()));

        given().multiPart("image", "pin.png", PNG, "application/octet-stream")
                .multiPart("variations", "2")
                .when().post("/api/pins/generate-from-image")
                .then().statusCode(200);

        ArgumentCaptor<PinRequest> request = ArgumentCaptor.forClass(PinRequest.class);
        ArgumentCaptor<PinImage> image = ArgumentCaptor.forClass(PinImage.class);
        verify(agent).generate(request.capture(), image.capture());
        assertNull(request.getValue().topic());
        assertEquals(2, request.getValue().variationsOrDefault());
        // type comes from the bytes, not the client-declared content type
        assertEquals(PinImage.PNG, image.getValue().mediaType());
    }

    @Test
    void rejectsMissingImage() {
        given().multiPart("topic", "x")
                .when().post("/api/pins/generate-from-image")
                .then().statusCode(400).body("error", is("invalid_image"));
    }

    @Test
    void rejectsNonImageUpload() {
        given().multiPart("image", "fake.png", "%PDF-1.7 not an image".getBytes(), "image/png")
                .when().post("/api/pins/generate-from-image")
                .then().statusCode(415).body("error", is("invalid_image"));
        verify(agent, never()).generate(any(), any());
    }

    @Test
    void rejectsOversizedImage() {
        byte[] big = new byte[PinImage.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        given().multiPart("image", "big.png", big, "image/png")
                .when().post("/api/pins/generate-from-image")
                .then().statusCode(413);
    }

    @Test
    void validatesImageFormFields() {
        given().multiPart("image", "pin.png", PNG, "image/png")
                .multiPart("variations", "9")
                .when().post("/api/pins/generate-from-image")
                .then().statusCode(400);
    }

    @Test
    void healthIsUp() {
        given().when().get("/q/health").then().statusCode(200).body("status", is("UP"));
    }
}
