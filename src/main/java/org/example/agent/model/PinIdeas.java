package org.example.agent.model;

import java.util.List;

/** Structured-output wrapper returned by Claude. */
public record PinIdeas(List<PinIdea> pins) {
}
