package org.example.agent.schedule;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.example.agent.model.PinIdea;
import org.example.agent.model.PinImage;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** A pin waiting to be published, or the record of one that was. */
@Entity
@Table(name = "scheduled_pin", indexes = @Index(columnList = "status, nextAttemptAt"))
public class ScheduledPin extends PanacheEntity {

    public enum Status {
        /** Waiting for {@link #nextAttemptAt}. */
        SCHEDULED,
        /** Claimed by the publisher; a pin left here by a crash is marked FAILED at startup. */
        PUBLISHING,
        PUBLISHED,
        /** Gave up; can be retried. */
        FAILED
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status;

    @Column(nullable = false, length = 32)
    public String boardId;
    /** Display only; Pinterest is addressed by {@link #boardId}. */
    @Column(length = 200)
    public String boardName;

    @Column(length = 100)
    public String title;
    @Column(length = 500)
    public String description;
    /** Space-separated. */
    @Column(length = 1000)
    public String hashtags;
    @Column(length = 500)
    public String altText;
    @Column(length = 2048)
    public String link;

    /** Either an image URL for Pinterest to download, or uploaded bytes. */
    @Column(length = 2048)
    public String imageUrl;
    @Basic(fetch = FetchType.LAZY)
    @Column(length = PinImage.MAX_BYTES)
    public byte[] imageData;
    @Column(length = 32)
    public String imageMediaType;

    /** When the user asked for the pin to go out. */
    @Column(nullable = false)
    public Instant publishAt;
    /** When the publisher should next try; later than {@link #publishAt} after a retryable failure. */
    @Column(nullable = false)
    public Instant nextAttemptAt;
    public int attempts;
    @Column(length = 1000)
    public String lastError;

    @Column(length = 64)
    public String pinterestPinId;
    @Column(length = 256)
    public String pinterestUrl;
    public Instant publishedAt;

    @Column(nullable = false)
    public Instant createdAt;

    PinIdea pin() {
        List<String> tags = hashtags == null || hashtags.isBlank() ? List.of() : Arrays.asList(hashtags.split(" "));
        return new PinIdea(title, description, tags, altText, null, null);
    }

    void setPin(PinIdea pin) {
        title = pin.title();
        description = pin.description();
        hashtags = pin.hashtags() == null ? null : String.join(" ", pin.hashtags());
        altText = pin.altText();
    }

    boolean hasImage() {
        return imageMediaType != null;
    }
}
