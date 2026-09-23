package org.example.agent;

/** The uploaded file is missing, too large, or not an image format Claude accepts. */
public class InvalidImageException extends RuntimeException {

    private final int status;

    public InvalidImageException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
