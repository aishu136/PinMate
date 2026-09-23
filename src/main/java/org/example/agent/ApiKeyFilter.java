package org.example.agent;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.agent.model.ErrorResponse;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Protects {@code /api/*}: these endpoints spend Claude credits and post to the owner's Pinterest account.
 * With {@code pinmate.api-key} set, every request needs a matching {@code X-API-Key} header.
 * Without it, only requests from this machine are accepted.
 */
@ApplicationScoped
public class ApiKeyFilter {

    static final String HEADER = "X-API-Key";

    private static final Logger LOG = Logger.getLogger(ApiKeyFilter.class);

    private final Optional<String> apiKey;

    public ApiKeyFilter(@ConfigProperty(name = "pinmate.api-key") Optional<String> apiKey) {
        this.apiKey = apiKey.map(String::strip).filter(k -> !k.isEmpty());
    }

    void warnIfOpen(@Observes StartupEvent event) {
        if (apiKey.isEmpty()) {
            LOG.warn("PINMATE_API_KEY is not set; /api/* only accepts requests from localhost");
        }
    }

    @ServerRequestFilter
    public Response check(ContainerRequestContext context, HttpServerRequest request) {
        if (!context.getUriInfo().getPath().startsWith("/api/")) {
            return null;
        }
        return allowed(apiKey, context.getHeaderString(HEADER), isLoopback(request.remoteAddress()))
                ? null
                : Response.status(401)
                        .entity(new ErrorResponse("unauthorized", apiKey.isPresent()
                                ? "Send a valid " + HEADER + " header."
                                : "Set PINMATE_API_KEY to allow requests from other machines."))
                        .build();
    }

    static boolean allowed(Optional<String> configuredKey, String providedKey, boolean loopback) {
        if (configuredKey.isEmpty()) {
            return loopback;
        }
        if (providedKey == null) {
            return false;
        }
        // Constant-time comparison so response timing doesn't reveal the key.
        return MessageDigest.isEqual(configuredKey.get().getBytes(StandardCharsets.UTF_8),
                providedKey.strip().getBytes(StandardCharsets.UTF_8));
    }

    static boolean isLoopback(SocketAddress address) {
        if (address == null || address.hostAddress() == null) {
            return false;
        }
        try {
            return InetAddress.getByName(address.hostAddress()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
