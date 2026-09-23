package org.example.agent.model;

import java.util.List;

/**
 * @param source   what was read from the destination URL; null when no URL was given
 * @param attempts number of generate passes the workflow needed
 */
public record PinResponse(String topic, String model, SourcePage source, int attempts, List<PinIdea> pins) {
}
