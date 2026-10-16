package org.example.agent.model;

import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A saved brand voice applied to generation.
 *
 * @param audience         default audience when the request doesn't give one
 * @param voice            how the brand sounds, e.g. "warm, practical, no hype"
 * @param guidelines       free-form do's and don'ts
 * @param bannedWords      words and phrases the pins must not contain
 * @param requiredHashtags added to every pin
 */
public record Brand(String name, String audience, String voice, String guidelines,
                    List<String> bannedWords, List<String> requiredHashtags) implements Serializable {

    public Brand {
        bannedWords = bannedWords == null ? List.of() : List.copyOf(bannedWords);
        requiredHashtags = requiredHashtags == null ? List.of() : List.copyOf(requiredHashtags);
    }

    /** Banned words or phrases that appear in {@code text} as whole words, ignoring case. */
    public List<String> bannedWordsIn(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return bannedWords.stream()
                .filter(w -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(w.toLowerCase(Locale.ROOT))
                        + "(?![\\p{L}\\p{N}])").matcher(lower).find())
                .toList();
    }
}
