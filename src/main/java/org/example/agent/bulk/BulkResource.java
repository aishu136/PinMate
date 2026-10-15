package org.example.agent.bulk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestPath;

import java.util.List;

/** Write pins for many pages at once, from a sitemap, a feed, or a list of URLs. */
@Path("/api/bulk")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Bulk")
public class BulkResource {

    public record DiscoverRequest(@NotBlank @Size(max = 2048) String source) {
    }

    private final BulkService service;

    public BulkResource(BulkService service) {
        this.service = service;
    }

    @Operation(summary = "List the pages in a sitemap, sitemap index, RSS or Atom feed")
    @POST
    @Path("/discover")
    public BulkViews.Discovery discover(@NotNull @Valid DiscoverRequest request) {
        return service.discover(request.source());
    }

    @Operation(summary = "Start a job that writes pins for each page")
    @POST
    public Response create(@NotNull @Valid BulkRequest request) {
        return Response.status(201).entity(service.create(request)).build();
    }

    @Operation(summary = "List jobs, newest first")
    @GET
    public List<BulkViews.Job> list() {
        return service.list();
    }

    @Operation(summary = "Get a job with its pages and pins")
    @GET
    @Path("/{id}")
    public BulkViews.Job get(@RestPath long id) {
        return service.get(id);
    }

    @Operation(summary = "Download a job's pins as CSV")
    @GET
    @Path("/{id}/pins.csv")
    @Produces("text/csv")
    public Response csv(@RestPath long id) {
        return Response.ok(service.csv(id), "text/csv; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"pinmate-bulk-" + id + ".csv\"")
                .build();
    }

    @Operation(summary = "Stop pages that haven't started")
    @POST
    @Path("/{id}/cancel")
    @Consumes(MediaType.WILDCARD) // no body
    public BulkViews.Job cancel(@RestPath long id) {
        return service.cancel(id);
    }

    @Operation(summary = "Queue failed and cancelled pages again")
    @POST
    @Path("/{id}/retry")
    @Consumes(MediaType.WILDCARD) // no body
    public BulkViews.Job retry(@RestPath long id) {
        return service.retry(id);
    }

    @Operation(summary = "Delete a job and its pins")
    @DELETE
    @Path("/{id}")
    public Response delete(@RestPath long id) {
        service.delete(id);
        return Response.noContent().build();
    }
}
