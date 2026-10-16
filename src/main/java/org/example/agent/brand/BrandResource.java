package org.example.agent.brand;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestPath;

import java.util.List;

/** Saved brand voices; pass a brand's id as {@code brandId} when generating. */
@Path("/api/brands")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Brands")
public class BrandResource {

    private final BrandService service;

    public BrandResource(BrandService service) {
        this.service = service;
    }

    @Operation(summary = "List brands by name")
    @GET
    public List<BrandService.View> list() {
        return service.list();
    }

    @Operation(summary = "Get a brand")
    @GET
    @Path("/{id}")
    public BrandService.View get(@RestPath long id) {
        return service.get(id);
    }

    @Operation(summary = "Create a brand")
    @POST
    public Response create(@NotNull @Valid BrandInput input) {
        return Response.status(201).entity(service.create(input)).build();
    }

    @Operation(summary = "Replace a brand")
    @PUT
    @Path("/{id}")
    public BrandService.View update(@RestPath long id, @NotNull @Valid BrandInput input) {
        return service.update(id, input);
    }

    @Operation(summary = "Delete a brand")
    @DELETE
    @Path("/{id}")
    public Response delete(@RestPath long id) {
        service.delete(id);
        return Response.noContent().build();
    }
}
