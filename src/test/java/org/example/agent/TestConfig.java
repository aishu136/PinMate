package org.example.agent;

/** Plain {@link AgentConfig} for unit tests that construct beans directly. */
record TestConfig(boolean fetchEnabled, int maxAttempts) implements AgentConfig {

    static TestConfig defaults() {
        return new TestConfig(true, 2);
    }

    @Override
    public String model() {
        return "claude-opus-5";
    }

    @Override
    public long maxTokens() {
        return 16000;
    }

    @Override
    public String effort() {
        return "high";
    }

    @Override
    public boolean refusalFallbacks() {
        return true;
    }

    @Override
    public Fetch fetch() {
        return new Fetch() {
            @Override
            public boolean enabled() {
                return fetchEnabled;
            }

            @Override
            public String effort() {
                return "low";
            }

            @Override
            public long maxContentTokens() {
                return 20000;
            }
        };
    }
}
