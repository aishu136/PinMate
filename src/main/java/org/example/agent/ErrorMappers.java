package org.example.agent;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.NoCredentialsException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import org.example.agent.model.ErrorResponse;
import org.example.agent.pinterest.PinterestApiException;
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
    public Response invalidImage(InvalidImageException e) {
        return error(e.status(), "invalid_image", e.getMessage());
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
            // The SDK sends requests even with no key configured, so a missing key also surfaces as a 401.
            return error(503, "not_configured", "The AI service credentials are missing or invalid. Set ANTHROPIC_API_KEY.");
        }
        LOG.errorf(e, "Claude API error (status %d)", e.statusCode());
        int status = e.statusCode() >= 500 ? 503 : 502;
        return error(status, "upstream_error", "The AI service returned an error.");
    }

    @ServerExceptionMapper
    public Response pinterest(PinterestApiException e) {
        return switch (e.status()) {
            case 401 -> {
                LOG.warnf("Pinterest rejected the access token: %s", e.getMessage());
                yield error(503, "pinterest_not_configured",
                        "The Pinterest access token is missing, invalid or expired. Set PINTEREST_ACCESS_TOKEN.");
            }
            case 403 -> error(403, "pinterest_forbidden", "Pinterest refused: " + e.getMessage());
            case 404 -> error(404, "pinterest_not_found", "Pinterest: " + e.getMessage());
            case 429 -> error(429, "pinterest_rate_limited", "Too many requests to Pinterest. Try again shortly.");
            case 400, 409, 422 -> error(422, "pinterest_rejected", "Pinterest rejected the pin: " + e.getMessage());
            default -> {
                LOG.errorf("Pinterest API error (status %d): %s", e.status(), e.getMessage());
                yield error(502, "pinterest_error", "Pinterest returned an error.");
            }
        };
    }

    /** Raised by the REST client when Pinterest can't be reached. */
    @ServerExceptionMapper
    public Response pinterestUnreachable(ProcessingException e) {
        LOG.warn("Could not reach the Pinterest API", e);
        return error(503, "pinterest_unavailable", "Pinterest could not be reached. Try again shortly.");
    }

    private static Response error(int status, String code, String message) {
        return Response.status(status).entity(new ErrorResponse(code, message)).build();
    }
}
