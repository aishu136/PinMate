package org.example.agent.schedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.example.agent.ImageUploads;
import org.example.agent.model.PinIdea;
import org.hibernate.validator.constraints.URL;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Queue pins to publish later; {@link PinScheduleService} publishes them when they're due. */
@Path("/api/schedule")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ScheduleResource {

    private final PinScheduleService service;

    public ScheduleResource(PinScheduleService service) {
        this.service = service;
    }

    @GET
    public List<ScheduledPinView> list(@QueryParam("status") ScheduledPin.Status status) {
        return service.list(status);
    }

    @GET
    @Path("/{id}")
    public ScheduledPinView get(@RestPath long id) {
        return service.get(id);
    }

    /** Schedules a pin whose image Pinterest downloads from {@code imageUrl}. */
    @POST
    public Response schedule(@NotNull @Valid ScheduleRequest request) {
        return Response.status(201).entity(service.schedule(request)).build();
    }

    /** Schedules a pin with an uploaded JPEG or PNG; {@code pin} is a JSON form part. */
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response scheduleUpload(
            @RestForm("image") FileUpload image,
            @RestForm @NotBlank @Pattern(regexp = "\\s*\\d+\\s*", message = "must be a numeric board id") String boardId,
            @RestForm @Size(max = 200) String boardName,
            @RestForm @PartType(MediaType.APPLICATION_JSON) @NotNull PinIdea pin,
            @RestForm @URL @Size(max = 2048) String link,
            @RestForm @NotBlank String publishAt) {
        ScheduledPinView view = service.schedule(boardId, boardName, pin, link, ImageUploads.read(image), parseTime(publishAt));
        return Response.status(201).entity(view).build();
    }

    @GET
    @Path("/{id}/image")
    @Produces({"image/jpeg", "image/png"})
    public Response image(@RestPath long id) {
        return service.image(id)
                .map(img -> Response.ok(img.data(), img.mediaType()).build())
                .orElseGet(() -> Response.status(404).build());
    }

    @POST
    @Path("/{id}/retry")
    @Consumes(MediaType.WILDCARD) // no body
    public ScheduledPinView retry(@RestPath long id) {
        return service.retry(id);
    }

    /** Cancels a pending pin, or removes a finished one from the list. Pins already on Pinterest stay there. */
    @DELETE
    @Path("/{id}")
    public Response delete(@RestPath long id) {
        service.delete(id);
        return Response.noContent().build();
    }

    static Instant parseTime(String value) {
        try {
            return OffsetDateTime.parse(value.strip()).toInstant();
        } catch (DateTimeParseException e) {
            throw new ScheduleException(400, "invalid_time",
                    "publishAt must be an ISO-8601 time with an offset, e.g. 2026-10-20T09:00:00Z.");
        }
    }
}
