package org.example.agent.pinterest;

/** A non-2xx response from the Pinterest API. */
public class PinterestApiException extends RuntimeException {

    private final int status;

    public PinterestApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
