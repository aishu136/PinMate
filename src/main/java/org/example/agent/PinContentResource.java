package org.example.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.example.agent.model.PinImage;
import org.example.agent.model.PinRequest;
import org.example.agent.model.PinResponse;
import org.hibernate.validator.constraints.URL;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

@Path("/api/pins")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PinContentResource {

    private final PinAgent agent;

    public PinContentResource(PinAgent agent) {
        this.agent = agent;
    }

    @POST
    @Path("/generate")
    public PinResponse generate(@NotNull @Valid PinRequest request) {
        return agent.generate(request);
    }

    /** Same as {@link #generate} but for an uploaded image; the topic is optional and inferred from the image. */
    @POST
    @Path("/generate-from-image")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public PinResponse generateFromImage(@RestForm("image") FileUpload image,
                                         @RestForm @Size(max = 300) String topic,
                                         @RestForm @URL @Size(max = 2048) String url,
                                         @RestForm @Size(max = 200) String audience,
                                         @RestForm @Size(max = 50) String tone,
                                         @RestForm @Min(1) @Max(5) Integer variations) {
        return agent.generate(new PinRequest(topic, url, audience, tone, variations), readImage(image));
    }

    static PinImage readImage(FileUpload upload) {
        if (upload == null) {
            throw new InvalidImageException(400, "Attach an image in the 'image' form field.");
        }
        if (upload.size() > PinImage.MAX_BYTES) {
            throw new InvalidImageException(413, "Images must be 5 MB or smaller.");
        }
        byte[] data;
        try {
            data = Files.readAllBytes(upload.uploadedFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read uploaded image", e);
        }
        String mediaType = PinImage.detectMediaType(data)
                .orElseThrow(() -> new InvalidImageException(415, "Upload a JPEG, PNG, GIF or WebP image."));
        return new PinImage(data, mediaType);
    }
}
