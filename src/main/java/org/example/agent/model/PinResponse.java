package org.example.agent.model;

import java.util.List;

public record PinResponse(String topic, String model, List<PinIdea> pins) {
}
