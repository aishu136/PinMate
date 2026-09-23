package org.example.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.jboss.logging.Logger;

public class AnthropicClientProducer {

    private static final Logger LOG = Logger.getLogger(AnthropicClientProducer.class);

    void warnIfNoApiKey(@Observes StartupEvent event) {
        if (isBlank(System.getenv("ANTHROPIC_API_KEY")) && isBlank(System.getenv("ANTHROPIC_AUTH_TOKEN"))) {
            LOG.warn("ANTHROPIC_API_KEY is not set; /api/pins/generate will fail until credentials are configured");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Credentials are resolved by the SDK (ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN, or an `ant auth login` profile).
     * The bean is a lazy client proxy, so the app starts even when no credentials are present.
     */
    @Produces
    @ApplicationScoped
    AnthropicClient anthropicClient() {
        return AnthropicOkHttpClient.fromEnv();
    }

    void close(@Disposes AnthropicClient client) {
        client.close();
    }
}
