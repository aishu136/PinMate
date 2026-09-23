package org.example.agent;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.example.agent.model.PinIdea;
import org.example.agent.model.PinRequest;
import org.example.agent.model.PinResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class PinContentResourceTest {

    @InjectMock
    PinContentService service;

    @Test
    void generatesPins() {
        PinIdea pin = new PinIdea("15-Minute Vegan Dinners", "Quick meals. Save for later!",
                List.of("#vegan"), "Bowl of noodles", List.of("Vegan Recipes"), "Overhead shot");
        when(service.generate(any(PinRequest.class)))
                .thenReturn(new PinResponse("vegan dinners", "claude-opus-5", List.of(pin)));

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
        verify(service, never()).generate(any());
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
        when(service.generate(any(PinRequest.class))).thenThrow(new PinGenerationException(422, "declined"));

        given().contentType(ContentType.JSON)
                .body("{\"topic\":\"x\"}")
                .when().post("/api/pins/generate")
                .then()
                .statusCode(422)
                .body("error", is("generation_failed"));
    }

    @Test
    void healthIsUp() {
        given().when().get("/q/health").then().statusCode(200).body("status", is("UP"));
    }
}
