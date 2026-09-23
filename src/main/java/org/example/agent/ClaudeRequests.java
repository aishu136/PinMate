package org.example.agent;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;

/** Options shared by every Claude request this app makes. */
final class ClaudeRequests {

    private ClaudeRequests() {
    }

    /** Server-side fallback: if the model declines, the API retries on a recommended fallback model. */
    static MessageCreateParams.Builder withFallbacks(MessageCreateParams.Builder builder, AgentConfig config) {
        if (config.refusalFallbacks()) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder;
    }

    static <T> StructuredMessageCreateParams.Builder<T> withFallbacks(StructuredMessageCreateParams.Builder<T> builder,
                                                                      AgentConfig config) {
        if (config.refusalFallbacks()) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder;
    }
}
