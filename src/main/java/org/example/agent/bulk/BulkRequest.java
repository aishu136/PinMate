package org.example.agent.bulk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.util.List;

/**
 * Pages to write pins for. {@code items} usually comes from {@code POST /api/bulk/discover}, trimmed to the ones wanted.
 *
 * @param source     the sitemap or feed the items came from, for display
 * @param variations pins per page (1-5, default 3)
 * @param brandId    brand voice to write in, from {@code GET /api/brands}
 */
public record BulkRequest(
        @URL @Size(max = 2048) String source,
        @NotEmpty @Size(max = 500) List<@Valid Item> items,
        @Size(max = 200) String audience,
        @Size(max = 50) String tone,
        @Min(1) @Max(5) Integer variations,
        Long brandId) {

    /** @param topic optional; guessed from the page title or URL when blank */
    public record Item(@NotBlank @URL @Size(max = 2048) String url, @Size(max = 300) String topic) {
    }
}
