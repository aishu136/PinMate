package org.example.agent.brand;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.example.agent.PinAgent;
import org.example.agent.model.Brand;
import org.example.agent.model.PinIdea;
import org.example.agent.model.PinRequest;
import org.example.agent.model.PinResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class BrandResourceTest {

    private static final String BRAND_JSON = """
            {"name":" Green Table ","audience":"busy parents","voice":"warm, practical",
             "bannedWords":["cheap"," Cheap ","super   food"],"requiredHashtags":["greentable","#GreenTable"," "]}""";

    @InjectMock
    PinAgent agent;

    @BeforeEach
    void reset() {
        QuarkusTransaction.requiringNew().run(() -> BrandProfile.deleteAll());
    }

    private long create(String json) {
        return given().contentType(ContentType.JSON).body(json)
                .when().post("/api/brands")
                .then().statusCode(201)
                .extract().jsonPath().getLong("id");
    }

    @Test
    void createsCleansListsUpdatesAndDeletes() {
        given().contentType(ContentType.JSON).body(BRAND_JSON.replace("\" \"]", "\"#x\"]").replace(",\"#x\"", ""))
                .when().post("/api/brands")
                .then().statusCode(201)
                .body("name", is("Green Table"))
                .body("bannedWords", is(List.of("cheap", "super food")))
                .body("requiredHashtags", is(List.of("#greentable", "#GreenTable")));

        given().when().get("/api/brands").then().statusCode(200).body("size()", is(1)).body("[0].guidelines", nullValue());

        long id = given().when().get("/api/brands").then().extract().jsonPath().getLong("[0].id");
        given().contentType(ContentType.JSON).body("{\"name\":\"Green Table\",\"voice\":\"calm\"}")
                .when().put("/api/brands/" + id)
                .then().statusCode(200)
                .body("voice", is("calm"))
                .body("bannedWords", is(List.of()));

        given().contentType(ContentType.JSON).body("{\"name\":\"green table\"}")
                .when().post("/api/brands")
                .then().statusCode(409).body("error", is("duplicate_brand"));

        given().when().delete("/api/brands/" + id).then().statusCode(204);
        given().when().get("/api/brands/" + id).then().statusCode(404);
    }

    @Test
    void validatesBrands() {
        given().contentType(ContentType.JSON).body("{\"name\":\" \"}")
                .when().post("/api/brands").then().statusCode(400);
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"X\",\"requiredHashtags\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}")
                .when().post("/api/brands").then().statusCode(400);
    }

    @Test
    void generatesWithABrand() {
        long id = create("{\"name\":\"Green Table\",\"bannedWords\":[\"cheap\"],\"requiredHashtags\":[\"#greentable\"]}");
        when(agent.generate(any(PinRequest.class), any(), any())).thenReturn(new PinResponse("dinners", "claude-opus-5",
                null, 1, List.of(new PinIdea("T", "D", List.of("#greentable"), "A", List.of(), "I")),
                List.of("Pin 1 uses words the Green Table brand avoids: cheap")));

        given().contentType(ContentType.JSON).body("{\"topic\":\"dinners\"}")
                .when().post("/api/pins/generate?brandId=" + id)
                .then().statusCode(200)
                .body("warnings[0]", is("Pin 1 uses words the Green Table brand avoids: cheap"));

        ArgumentCaptor<Brand> brand = ArgumentCaptor.forClass(Brand.class);
        verify(agent).generate(any(PinRequest.class), isNull(), brand.capture());
        assertEquals("Green Table", brand.getValue().name());
        assertEquals(List.of("cheap"), brand.getValue().bannedWords());

        given().contentType(ContentType.JSON).body("{\"topic\":\"dinners\"}")
                .when().post("/api/pins/generate?brandId=999999")
                .then().statusCode(400).body("error", is("unknown_brand"));
        given().contentType(ContentType.JSON)
                .body("{\"brandId\":999999,\"items\":[{\"url\":\"https://example.com/a\"}]}")
                .when().post("/api/bulk")
                .then().statusCode(400).body("error", is("unknown_brand"));
    }

    @Test
    void generatesWithoutABrandByDefault() {
        when(agent.generate(any(PinRequest.class), any(), any())).thenReturn(new PinResponse("dinners", "claude-opus-5",
                null, 1, List.of(new PinIdea("T", "D", List.of(), "A", List.of(), "I"))));
        given().contentType(ContentType.JSON).body("{\"topic\":\"dinners\"}")
                .when().post("/api/pins/generate")
                .then().statusCode(200).body("warnings.size()", is(0));
        ArgumentCaptor<Brand> brand = ArgumentCaptor.forClass(Brand.class);
        verify(agent).generate(any(PinRequest.class), isNull(), brand.capture());
        assertNull(brand.getValue());
    }
}
