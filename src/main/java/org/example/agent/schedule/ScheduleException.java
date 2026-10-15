package org.example.agent.schedule;

/** A schedule request that can't be carried out, with the HTTP status and error code to return. */
public class ScheduleException extends RuntimeException {

    private final int status;
    private final String code;

    public ScheduleException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
