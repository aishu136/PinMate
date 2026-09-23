package org.example.agent;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

/** Runs the app with an API key configured and checks the filter enforces it end to end. */
@QuarkusTest
@TestProfile(ApiKeyRequiredTest.WithApiKey.class)
class ApiKeyRequiredTest {

    public static class WithApiKey implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("pinmate.api-key", "test-api-key");
        }
    }

    @Test
    void rejectsMissingOrWrongKey() {
        given().contentType(ContentType.JSON).body("{\"topic\":\" \"}")
                .when().post("/api/pins/generate")
                .then().statusCode(401).body("error", is("unauthorized"));
        given().header(ApiKeyFilter.HEADER, "nope")
                .when().get("/api/pinterest/boards")
                .then().statusCode(401);
    }

    @Test
    void acceptsCorrectKey() {
        // Passes the filter and reaches validation, which rejects the blank topic.
        given().header(ApiKeyFilter.HEADER, "test-api-key")
                .contentType(ContentType.JSON).body("{\"topic\":\" \"}")
                .when().post("/api/pins/generate")
                .then().statusCode(400);
        given().header(ApiKeyFilter.HEADER, "test-api-key")
                .when().get("/api/pinterest/boards")
                .then().statusCode(200);
    }

    @Test
    void healthStaysOpen() {
        given().when().get("/q/health").then().statusCode(200);
    }
}
