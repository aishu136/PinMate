package org.example.agent.brand;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Create or replace a brand.
 *
 * @param voice            how the brand sounds, e.g. "warm, practical, no hype"
 * @param guidelines       free-form do's and don'ts
 * @param bannedWords      words and phrases pins must not contain
 * @param requiredHashtags added to every pin (at most 5, leaving room for topic hashtags)
 */
public record BrandInput(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 200) String audience,
        @Size(max = 500) String voice,
        @Size(max = 2000) String guidelines,
        @Size(max = 50) List<@NotBlank @Size(max = 50) String> bannedWords,
        @Size(max = 5) List<@NotBlank @Size(max = 50) String> requiredHashtags) {
}
