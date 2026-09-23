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
    void detectsLoopbackAddresses() {
        assertTrue(ApiKeyFilter.isLoopback(SocketAddress.inetSocketAddress(1234, "127.0.0.1")));
        assertTrue(ApiKeyFilter.isLoopback(SocketAddress.inetSocketAddress(1234, "::1")));
        assertFalse(ApiKeyFilter.isLoopback(SocketAddress.inetSocketAddress(1234, "192.168.1.20")));
        assertFalse(ApiKeyFilter.isLoopback(null));
    }
}
