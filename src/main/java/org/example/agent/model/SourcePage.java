package org.example.agent.model;

import java.io.Serializable;

/**
 * What the agent learned from the destination URL.
 *
 * @param fetched false when the page couldn't be read; pins are then written from the topic alone
 * @param summary facts extracted from the page, or null when not fetched
 */
public record SourcePage(String url, boolean fetched, String summary) implements Serializable {

    public static SourcePage failed(String url) {
        return new SourcePage(url, false, null);
    }
}
