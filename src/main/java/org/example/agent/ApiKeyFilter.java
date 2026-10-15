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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Optional;

/**
 * Protects {@code /api/*}: these endpoints spend Claude credits and post to the owner's Pinterest account.
 * With {@code pinmate.api-key} set, every request needs a matching {@code X-API-Key} header.
 * Without it, only requests from this machine are accepted, and browser requests must come from this app's own
 * pages: otherwise any website open in the user's browser could post to {@code localhost} on their behalf.
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
        if (!allowed(apiKey, context.getHeaderString(HEADER), isLoopback(request.remoteAddress()))) {
            return Response.status(401)
                    .entity(new ErrorResponse("unauthorized", apiKey.isPresent()
                            ? "Send a valid " + HEADER + " header."
                            : "Set PINMATE_API_KEY to allow requests from other machines."))
                    .build();
        }
        // With a key, a cross-site page can't send the custom header (no CORS is configured), so the
        // check is only needed when localhost alone is trusted. Behind a proxy the Host header may not
        // match the public origin, which is one more reason the README says to set a key there.
        if (apiKey.isEmpty() && !sameOrigin(context.getHeaderString("Origin"), context.getHeaderString("Host"))) {
            return Response.status(403)
                    .entity(new ErrorResponse("forbidden", "Cross-origin requests to /api are not allowed."))
                    .build();
        }
        return null;
    }

    /**
     * True when there is no {@code Origin} (curl, scripts, same-origin GETs) or it names the host the request
     * was sent to. Browsers always send {@code Origin} on cross-origin POSTs, including plain HTML form posts.
     */
    static boolean sameOrigin(String origin, String host) {
        if (origin == null) {
            return true;
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(origin.strip());
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        int defaultPort = switch (scheme) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
        if (defaultPort < 0 || uri.getHost() == null) {
            return false; // includes the opaque "null" origin of sandboxed frames and file:// pages
        }
        int originPort = uri.getPort() == -1 ? defaultPort : uri.getPort();

        String h = host.strip();
        int colon = h.lastIndexOf(':');
        String hostName = h;
        int hostPort = defaultPort;
        if (colon > h.lastIndexOf(']')) { // a port, not part of an IPv6 literal
            hostName = h.substring(0, colon);
            try {
                hostPort = Integer.parseInt(h.substring(colon + 1));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return uri.getHost().equalsIgnoreCase(hostName) && originPort == hostPort;
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
