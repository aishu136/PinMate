package org.example.agent.schedule;

import org.example.agent.model.PinIdea;

import java.time.Instant;

/**
 * A scheduled pin as returned by the API. Uploaded image bytes are served separately from
 * {@code GET /api/schedule/{id}/image}.
 */
public record ScheduledPinView(
        long id,
        ScheduledPin.Status status,
        Instant publishAt,
        String boardId,
        String boardName,
        PinIdea pin,
        String link,
        String imageUrl,
        boolean hasImage,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        Instant publishedAt,
        String pinterestUrl,
        Instant createdAt) {

    static ScheduledPinView of(ScheduledPin p) {
        return new ScheduledPinView(p.id, p.status, p.publishAt, p.boardId, p.boardName, p.pin(), p.link,
                p.imageUrl, p.hasImage(), p.attempts, p.nextAttemptAt, p.lastError, p.publishedAt,
                p.pinterestUrl, p.createdAt);
    }
}
