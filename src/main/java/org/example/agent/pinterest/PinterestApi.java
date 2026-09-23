package org.example.agent.pinterest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Pinterest API v5. Base URL: {@code quarkus.rest-client.pinterest.url}.
 * Every call takes the full {@code Authorization} header so the token never lives in client config.
 */
@RegisterRestClient(configKey = "pinterest")
@RegisterProvider(PinterestErrorMapper.class)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface PinterestApi {

    /** Needs scope {@code boards:read}. */
    @GET
    @Path("/boards")
    PinterestDtos.BoardPage listBoards(@HeaderParam("Authorization") String authorization,
                                       @QueryParam("bookmark") String bookmark,
                                       @QueryParam("page_size") int pageSize);

    /** Needs scopes {@code boards:read}, {@code pins:read}, {@code pins:write}. */
    @POST
    @Path("/pins")
    PinterestDtos.CreatedPin createPin(@HeaderParam("Authorization") String authorization,
                                       PinterestDtos.PinCreate pin);
}
