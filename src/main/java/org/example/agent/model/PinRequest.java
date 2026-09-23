package org.example.agent.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

/**
 * Input for pin content generation.
 *
 * @param topic      what the pin is about, e.g. "easy weeknight vegan dinners"
 * @param url        optional destination link; passed to the model as context only (it is not fetched)
 * @param audience   optional target audience, e.g. "busy parents"
 * @param tone       optional voice, e.g. "playful", "minimal", "luxury"
 * @param variations number of distinct pin ideas to return (1-5, default 3)
 */
public record PinRequest(
        @NotBlank @Size(max = 300) String topic,
        @URL @Size(max = 2048) String url,
        @Size(max = 200) String audience,
        @Size(max = 50) String tone,
        @Min(1) @Max(5) Integer variations) {

    public static final int DEFAULT_VARIATIONS = 3;

    public int variationsOrDefault() {
        return variations == null ? DEFAULT_VARIATIONS : variations;
    }
}
