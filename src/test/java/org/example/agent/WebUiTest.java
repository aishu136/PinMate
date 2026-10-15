package org.example.agent;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class WebUiTest {

    @Test
    void servesTheWebUiAtRoot() {
        given().when().get("/")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("<title>PinMate</title>"))
                .body(containsString("/api/pins/generate"));
    }

    @Test
    void blocksApiCallsFromOtherSites() {
        given().header("Origin", "https://evil.example")
                .contentType(ContentType.JSON).body("{\"topic\":\"vegan dinners\"}")
                .when().post("/api/pins/generate")
                .then().statusCode(403).body("error", is("forbidden"));
    }

    @Test
    void allowsApiCallsFromItsOwnPages() {
        // Passes the filter and reaches validation, which rejects the blank topic.
        given().header("Origin", "http://localhost:" + RestAssured.port)
                .contentType(ContentType.JSON).body("{\"topic\":\" \"}")
                .when().post("/api/pins/generate")
                .then().statusCode(400);
    }
}
