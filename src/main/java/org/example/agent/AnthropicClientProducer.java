package org.example.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;

public class AnthropicClientProducer {

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
