package org.example.agent.pinterest;

import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/** Turns Pinterest error responses into {@link PinterestApiException}, keeping Pinterest's message. */
public class PinterestErrorMapper implements ResponseExceptionMapper<PinterestApiException> {

    @Override
    public PinterestApiException toThrowable(Response response) {
        String message = null;
        try {
            PinterestDtos.Error error = response.readEntity(PinterestDtos.Error.class);
            message = error == null ? null : error.message();
        } catch (RuntimeException ignored) {
            // non-JSON error body; fall back to the status line
        }
        if (message == null || message.isBlank()) {
            message = "HTTP " + response.getStatus();
        }
        return new PinterestApiException(response.getStatus(), message);
    }
}
