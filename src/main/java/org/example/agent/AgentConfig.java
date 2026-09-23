package org.example.agent;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "pinterest-agent")
public interface AgentConfig {

    /** Claude model ID used for every call. */
    String model();

    @WithDefault("16000")
    long maxTokens();

    /** Effort for pin writing: low | medium | high | xhigh | max. */
    @WithDefault("high")
    String effort();

    /** Retry declined requests on the recommended fallback model (server-side, beta). */
    @WithDefault("true")
    boolean refusalFallbacks();

    /** Total generate attempts, including retries triggered by validation. */
    @WithDefault("2")
    int maxAttempts();

    Fetch fetch();

    interface Fetch {

        /** Set to false to skip reading destination URLs. */
        @WithDefault("true")
        boolean enabled();

        /** Effort for page reading; summarizing a page is simple work. */
        @WithDefault("low")
        String effort();

        /** Cap on page content tokens fed to Claude. */
        @WithDefault("20000")
        long maxContentTokens();
    }
}
