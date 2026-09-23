package org.example.agent;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.NoCredentialsException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import jakarta.ws.rs.core.Response;
import org.example.agent.model.ErrorResponse;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Translates Claude API failures into client-friendly HTTP responses without leaking upstream details. */
public class ErrorMappers {

    private static final Logger LOG = Logger.getLogger(ErrorMappers.class);

    @ServerExceptionMapper
    public Response pinGeneration(PinGenerationException e) {
        return error(e.status(), "generation_failed", e.getMessage());
    }

    @ServerExceptionMapper
    public Response noCredentials(NoCredentialsException e) {
        LOG.error("No Anthropic credentials configured", e);
        return error(503, "not_configured", "The AI service is not configured. Set ANTHROPIC_API_KEY.");
    }

    @ServerExceptionMapper
    public Response io(AnthropicIoException e) {
        LOG.warn("Could not reach the Claude API", e);
        return error(503, "upstream_unavailable", "The AI service could not be reached. Try again shortly.");
    }

    @ServerExceptionMapper
    public Response service(AnthropicServiceException e) {
        if (e instanceof RateLimitException) {
            return error(429, "rate_limited", "Too many requests to the AI service. Try again shortly.");
        }
        if (e instanceof UnauthorizedException || e instanceof PermissionDeniedException) {
            LOG.error("Claude API rejected the configured credentials", e);
            return error(503, "not_configured", "The AI service credentials are invalid.");
        }
        LOG.errorf(e, "Claude API error (status %d)", e.statusCode());
        int status = e.statusCode() >= 500 ? 503 : 502;
        return error(status, "upstream_error", "The AI service returned an error.");
    }

    private static Response error(int status, String code, String message) {
        return Response.status(status).entity(new ErrorResponse(code, message)).build();
    }
}
