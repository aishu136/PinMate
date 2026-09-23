package org.example.agent.model;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

/**
 * An uploaded image to write pins for.
 *
 * @param mediaType one of the formats Claude accepts, detected from the bytes
 */
public record PinImage(byte[] data, String mediaType) implements Serializable {

    /** Claude API limit per image. */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    public static final String GIF = "image/gif";
    public static final String WEBP = "image/webp";

    /** Detects the format from magic bytes; the client-sent content type isn't trusted. */
    public static Optional<String> detectMediaType(byte[] d) {
        if (startsWith(d, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        if (startsWith(d, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        if (startsWith(d, 0, 'G', 'I', 'F', '8') && d.length > 5 && (d[4] == '7' || d[4] == '9') && d[5] == 'a') {
            return Optional.of(GIF);
        }
        if (startsWith(d, 0, 'R', 'I', 'F', 'F') && startsWith(d, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, int offset, int... prefix) {
        if (data.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[offset + i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    // Keep image bytes out of logs (LangGraph4j and error messages print state).
    @Override
    public String toString() {
        return "PinImage[mediaType=" + mediaType + ", bytes=" + data.length + "]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PinImage other && mediaType.equals(other.mediaType) && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return 31 * mediaType.hashCode() + Arrays.hashCode(data);
    }
}
