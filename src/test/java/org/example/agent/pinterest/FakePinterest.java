package org.example.agent.pinterest;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/** Stand-in for api.pinterest.com/v5 during tests; records the last request it received. */
@Path("/fake-pinterest/v5")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FakePinterest {

    static volatile String lastAuthorization;
    static volatile JsonNode lastPin;
    static volatile String lastPageSize;
    /** When non-zero, the next call fails with this status and a Pinterest-style error body. */
    static volatile int failWith;

    static void reset() {
        lastAuthorization = null;
        lastPin = null;
        lastPageSize = null;
        failWith = 0;
    }

    @POST
    @Path("/pins")
    public Response createPin(@HeaderParam("Authorization") String authorization, JsonNode pin) {
        lastAuthorization = authorization;
        lastPin = pin;
        if (failWith != 0) {
            return Response.status(failWith).entity(Map.of("code", 1, "message", "Board not found.")).build();
        }
        return Response.status(201).entity(Map.of("id", "987654321", "board_id", pin.path("board_id").asText())).build();
    }

    @GET
    @Path("/boards")
    public Map<String, Object> boards(@HeaderParam("Authorization") String authorization,
                                      @QueryParam("bookmark") String bookmark,
                                      @QueryParam("page_size") String pageSize) {
        lastAuthorization = authorization;
        lastPageSize = pageSize;
        if (bookmark == null) {
            return Map.of("items", List.of(Map.of("id", "111", "name", "Vegan Recipes", "privacy", "PUBLIC", "pin_count", 42)),
                    "bookmark", "page2");
        }
        return Map.of("items", List.of(Map.of("id", "222", "name", "Home Decor", "privacy", "SECRET", "pin_count", 7)));
    }
}
