package org.example.agent.pinterest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.example.agent.model.PinIdea;
import org.hibernate.validator.constraints.URL;

/**
 * @param boardId  target board, from {@code GET /api/pinterest/boards}
 * @param pin      a pin from {@code /api/pins/generate}, used as-is (suggestedBoards and imageIdea are ignored)
 * @param link     where the pin should link to
 * @param imageUrl public image URL for Pinterest to download
 */
public record PublishRequest(
        @NotBlank @Pattern(regexp = "\\s*\\d+\\s*", message = "must be a numeric board id") String boardId,
        @NotNull PinIdea pin,
        @URL @Size(max = 2048) String link,
        @NotBlank @URL @Size(max = 2048) String imageUrl) {
}
