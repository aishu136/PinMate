package org.example.agent;

/** Raised when Claude answers but the answer can't be turned into pins (refusal, truncation, empty output). */
public class PinGenerationException extends RuntimeException {

    private final int status;

    public PinGenerationException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
