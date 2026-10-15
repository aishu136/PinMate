package org.example.agent;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;

@QuarkusTest
class OpenApiTest {

    @Test
    void documentsTheApiWithTheApiKeyScheme() {
        given().accept("application/json")
                .when().get("/q/openapi")
                .then()
                .statusCode(200)
                .body("info.title", is("PinMate API"))
                .body("paths.'/api/pins/generate'.post.summary", notNullValue())
                .body("paths.'/api/pinterest/pins/upload'.post.tags[0]", is("Pinterest"))
                .body("paths.'/api/schedule/{id}/retry'.post.tags[0]", is("Schedule"))
                .body("components.securitySchemes.apiKey.name", is(ApiKeyFilter.HEADER))
                .body("security[0].apiKey", notNullValue());
    }

    @Test
    void servesSwaggerUi() {
        given().when().get("/q/swagger-ui/")
                .then().statusCode(200).body(containsString("PinMate API"));
    }
}
