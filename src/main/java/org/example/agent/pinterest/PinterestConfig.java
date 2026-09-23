package org.example.agent.pinterest;

import io.smallrye.config.ConfigMapping;

import java.util.Optional;

@ConfigMapping(prefix = "pinterest")
public interface PinterestConfig {

    /**
     * OAuth access token with scopes boards:read, pins:read and pins:write.
     * Set through the PINTEREST_ACCESS_TOKEN environment variable; never put it in application.properties.
     */
    Optional<String> accessToken();
}
