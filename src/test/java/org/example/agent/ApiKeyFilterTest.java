package org.example.agent;

import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyFilterTest {

    private static final Optional<String> KEY = Optional.of("s3cret-key");

    @Test
    void withoutConfiguredKeyOnlyLocalhostIsAllowed() {
        assertTrue(ApiKeyFilter.allowed(Optional.empty(), null, true));
        assertFalse(ApiKeyFilter.allowed(Optional.empty(), null, false));
        assertFalse(ApiKeyFilter.allowed(Optional.empty(), "anything", false));
    }

    @Test
    void withConfiguredKeyEveryoneNeedsIt() {
        assertTrue(ApiKeyFilter.allowed(KEY, "s3cret-key", false));
        assertTrue(ApiKeyFilter.allowed(KEY, " s3cret-key ", false));
        assertFalse(ApiKeyFilter.allowed(KEY, null, true), "localhost still needs the key once one is set");
        assertFalse(ApiKeyFilter.allowed(KEY, "wrong", true));
        assertFalse(ApiKeyFilter.allowed(KEY, "s3cret-key-extra", false));
    }

    @Test
    void allowsOnlyTheAppsOwnOrigin() {
        assertTrue(ApiKeyFilter.sameOrigin(null, "localhost:8080"), "non-browser clients send no Origin");
        assertTrue(ApiKeyFilter.sameOrigin("http://localhost:8080", "localhost:8080"));
        assertTrue(ApiKeyFilter.sameOrigin("http://LOCALHOST:8080", "localhost:8080"));
        assertTrue(ApiKeyFilter.sameOrigin("http://127.0.0.1:8080", "127.0.0.1:8080"));
        assertTrue(ApiKeyFilter.sameOrigin("http://[::1]:8080", "[::1]:8080"));
        assertTrue(ApiKeyFilter.sameOrigin("http://localhost", "localhost"));
        assertTrue(ApiKeyFilter.sameOrigin("https://localhost", "localhost:443"));

        assertFalse(ApiKeyFilter.sameOrigin("https://evil.example", "localhost:8080"));
        assertFalse(ApiKeyFilter.sameOrigin("http://localhost:3000", "localhost:8080"), "another port is another origin");
        assertFalse(ApiKeyFilter.sameOrigin("http://127.0.0.1:8080", "localhost:8080"));
        assertFalse(ApiKeyFilter.sameOrigin("null", "localhost:8080"));
        assertFalse(ApiKeyFilter.sameOrigin("file:///tmp/x.html", "localhost:8080"));
        assertFalse(ApiKeyFilter.sameOrigin("http://localhost:8080", null));
        assertFalse(ApiKeyFilter.sameOrigin("http://localhost:8080", "localhost:abc"));
    }

    @Test
    void detectsLoopbackAddresses() {
        assertTrue(ApiKeyFilter.isLoopback(SocketAddress.inetSocketAddress(1234, "127.0.0.1")));
        assertTrue(ApiKeyFilter.isLoopback(SocketAddress.inetSocketAddress(1234, "::1")));
        assertFalse(ApiKeyFilter.isLoopback(SocketAddress.inetSocketAddress(1234, "192.168.1.20")));
        assertFalse(ApiKeyFilter.isLoopback(null));
    }
}
