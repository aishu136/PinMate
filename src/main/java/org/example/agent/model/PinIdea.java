package org.example.agent.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** A single ready-to-post pin. Also used as the structured-output schema sent to Claude. */
public record PinIdea(
        @JsonPropertyDescription("Keyword-rich pin title, at most 100 characters")
        String title,
        @JsonPropertyDescription("Pin description, at most 500 characters, with the main keywords in the first sentence and a call to action")
        String description,
        @JsonPropertyDescription("3 to 8 relevant hashtags, each starting with # and containing no spaces")
        List<String> hashtags,
        @JsonPropertyDescription("Accessibility alt text describing the ideal pin image, at most 500 characters")
        String altText,
        @JsonPropertyDescription("2 to 4 board names this pin would fit on")
        List<String> suggestedBoards,
        @JsonPropertyDescription("Short description of the image or visual concept to create for this pin")
        String imageIdea) {
}
