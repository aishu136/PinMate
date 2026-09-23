package org.example.agent.pinterest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.example.agent.ImageUploads;
import org.example.agent.model.PinIdea;
import org.hibernate.validator.constraints.URL;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.List;

/** Publishing to the Pinterest account behind PINTEREST_ACCESS_TOKEN. */
@Path("/api/pinterest")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PinterestResource {

    private final PinPublisher publisher;

    public PinterestResource(PinPublisher publisher) {
        this.publisher = publisher;
    }

    @GET
    @Path("/boards")
    public List<PinterestDtos.Board> boards() {
        return publisher.boards();
    }

    /** Publishes a pin whose image Pinterest downloads from {@code imageUrl}. */
    @POST
    @Path("/pins")
    public PinPublisher.PublishedPin publish(@NotNull @Valid PublishRequest request) {
        return publisher.publish(request.boardId(), request.pin(), request.link(), request.imageUrl());
    }

    /** Publishes a pin with an uploaded JPEG or PNG; {@code pin} is a JSON form part. */
    @POST
    @Path("/pins/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public PinPublisher.PublishedPin publishUpload(
            @RestForm("image") FileUpload image,
            @RestForm @NotBlank @Pattern(regexp = "\\s*\\d+\\s*", message = "must be a numeric board id") String boardId,
            @RestForm @PartType(MediaType.APPLICATION_JSON) @NotNull PinIdea pin,
            @RestForm @URL @Size(max = 2048) String link) {
        return publisher.publish(boardId, pin, link, ImageUploads.read(image));
    }
}
