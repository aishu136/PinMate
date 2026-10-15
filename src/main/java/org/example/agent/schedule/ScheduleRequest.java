package org.example.agent.schedule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.example.agent.model.PinIdea;
import org.hibernate.validator.constraints.URL;

import java.time.Instant;

/**
 * Like {@link org.example.agent.pinterest.PublishRequest}, plus when to publish.
 *
 * @param boardName optional, shown in the schedule list
 * @param publishAt ISO-8601 instant with an offset, e.g. {@code 2026-10-20T09:00:00Z}
 */
public record ScheduleRequest(
        @NotBlank @Pattern(regexp = "\\s*\\d+\\s*", message = "must be a numeric board id") String boardId,
        @Size(max = 200) String boardName,
        @NotNull PinIdea pin,
        @URL @Size(max = 2048) String link,
        @NotBlank @URL @Size(max = 2048) String imageUrl,
        @NotNull Instant publishAt) {
}
