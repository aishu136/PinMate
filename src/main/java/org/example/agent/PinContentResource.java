package org.example.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.example.agent.model.PinRequest;
import org.example.agent.model.PinResponse;

@Path("/api/pins")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PinContentResource {

    private final PinContentService service;

    public PinContentResource(PinContentService service) {
        this.service = service;
    }

    @POST
    @Path("/generate")
    public PinResponse generate(@NotNull @Valid PinRequest request) {
        return service.generate(request);
    }
}
