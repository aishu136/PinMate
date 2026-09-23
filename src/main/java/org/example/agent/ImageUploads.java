package org.example.agent;

import org.example.agent.model.PinImage;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/** Reads and checks an uploaded image form field. */
public final class ImageUploads {

    private ImageUploads() {
    }

    public static PinImage read(FileUpload upload) {
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
