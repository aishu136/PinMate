package org.example.agent.model;

import java.util.List;

/**
 * @param source   what was read from the destination URL; null when no URL was given
 * @param attempts number of generate passes the workflow needed
 * @param warnings brand rules the pins still break after the last attempt; empty when there are none
 */
public record PinResponse(String topic, String model, SourcePage source, int attempts, List<PinIdea> pins,
                          List<String> warnings) {

    public PinResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public PinResponse(String topic, String model, SourcePage source, int attempts, List<PinIdea> pins) {
        this(topic, model, source, attempts, pins, List.of());
    }
}
