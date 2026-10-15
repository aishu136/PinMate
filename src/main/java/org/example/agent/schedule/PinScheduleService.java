package org.example.agent.schedule;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ProcessingException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.agent.ApiException;
import org.example.agent.InvalidImageException;
import org.example.agent.PinWriter;
import org.example.agent.model.PinIdea;
import org.example.agent.model.PinImage;
import org.example.agent.pinterest.PinPublisher;
import org.example.agent.pinterest.PinterestApiException;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Stores scheduled pins and publishes them when they're due. */
@ApplicationScoped
public class PinScheduleService {

    /** A little slack so "publish at the current minute" from a form isn't rejected as past. */
    static final Duration PAST_TOLERANCE = Duration.ofMinutes(2);
    /** Pins published per timer tick; the rest wait for the next one. */
    static final int BATCH_SIZE = 20;
    static final Duration FIRST_RETRY_DELAY = Duration.ofMinutes(1);
    private static final int MAX_HASHTAG_CHARS = 1000;

    private static final Logger LOG = Logger.getLogger(PinScheduleService.class);

    private final PinPublisher publisher;
    private final int maxAttempts;

    public PinScheduleService(PinPublisher publisher,
                              @ConfigProperty(name = "pinmate.schedule.max-attempts", defaultValue = "3") int maxAttempts) {
        this.publisher = publisher;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /** A pin that was mid-publish when the app stopped may or may not be on Pinterest; don't post it twice. */
    @Transactional
    void recoverInterrupted(@Observes StartupEvent event) {
        long n = ScheduledPin.update("status = ?1, lastError = ?2 where status = ?3", ScheduledPin.Status.FAILED,
                "Interrupted while publishing. Check Pinterest before retrying.", ScheduledPin.Status.PUBLISHING);
        if (n > 0) {
            LOG.warnf("Marked %d interrupted scheduled pin(s) as failed", n);
        }
    }

    @Transactional
    public ScheduledPinView schedule(ScheduleRequest request) {
        ScheduledPin p = newPin(request.boardId(), request.boardName(), request.pin(), request.link(), request.publishAt());
        p.imageUrl = request.imageUrl().strip();
        p.persist();
        return ScheduledPinView.of(p);
    }

    @Transactional
    public ScheduledPinView schedule(String boardId, String boardName, PinIdea pin, String link, PinImage image,
                                     Instant publishAt) {
        if (!PinImage.JPEG.equals(image.mediaType()) && !PinImage.PNG.equals(image.mediaType())) {
            throw new InvalidImageException(415, "Pinterest only accepts JPEG or PNG uploads.");
        }
        ScheduledPin p = newPin(boardId, boardName, pin, link, publishAt);
        p.imageData = image.data();
        p.imageMediaType = image.mediaType();
        p.persist();
        return ScheduledPinView.of(p);
    }

    private ScheduledPin newPin(String boardId, String boardName, PinIdea pin, String link, Instant publishAt) {
        Instant now = Instant.now();
        if (publishAt.isBefore(now.minus(PAST_TOLERANCE))) {
            throw new ApiException(400, "invalid_time", "publishAt is in the past.");
        }
        ScheduledPin p = new ScheduledPin();
        p.status = ScheduledPin.Status.SCHEDULED;
        p.boardId = boardId.strip();
        p.boardName = blankToNull(boardName);
        p.setPin(fitHashtags(PinWriter.enforceLimits(pin)));
        p.link = blankToNull(link);
        p.publishAt = publishAt;
        p.nextAttemptAt = publishAt;
        p.createdAt = now;
        return p;
    }

    /** Ordered by publish time. */
    @Transactional
    public List<ScheduledPinView> list(ScheduledPin.Status status) {
        List<ScheduledPin> pins = status == null
                ? ScheduledPin.listAll(Sort.by("publishAt"))
                : ScheduledPin.list("status", Sort.by("publishAt"), status);
        return pins.stream().map(ScheduledPinView::of).toList();
    }

    @Transactional
    public ScheduledPinView get(long id) {
        return ScheduledPinView.of(find(id));
    }

    /** Uploaded image bytes, or empty when the pin uses an image URL. */
    @Transactional
    public Optional<PinImage> image(long id) {
        ScheduledPin p = find(id);
        return p.hasImage() ? Optional.of(new PinImage(p.imageData, p.imageMediaType)) : Optional.empty();
    }

    @Transactional
    public void delete(long id) {
        ScheduledPin p = find(id);
        if (p.status == ScheduledPin.Status.PUBLISHING) {
            throw new ApiException(409, "publishing", "This pin is being published right now.");
        }
        p.delete();
    }

    /** Puts a failed pin back in the queue to publish now. */
    @Transactional
    public ScheduledPinView retry(long id) {
        ScheduledPin p = find(id);
        if (p.status != ScheduledPin.Status.FAILED) {
            throw new ApiException(409, "not_failed", "Only failed pins can be retried.");
        }
        p.status = ScheduledPin.Status.SCHEDULED;
        p.attempts = 0;
        p.nextAttemptAt = Instant.now();
        p.lastError = null;
        return ScheduledPinView.of(p);
    }

    @Scheduled(every = "{pinmate.schedule.poll-interval}", delayed = "10s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        publishDue();
    }

    /** Publishes pins whose time has come; returns how many went out. */
    public int publishDue() {
        Instant now = Instant.now();
        List<Long> due = QuarkusTransaction.requiringNew().call(() -> ScheduledPin
                .<ScheduledPin>find("status = ?1 and nextAttemptAt <= ?2", Sort.by("nextAttemptAt"),
                        ScheduledPin.Status.SCHEDULED, now)
                .page(0, BATCH_SIZE).list().stream().map(p -> p.id).toList());
        int published = 0;
        for (Long id : due) {
            if (publishOne(id)) {
                published++;
            }
        }
        return published;
    }

    private record Job(String boardId, PinIdea pin, String link, String imageUrl, PinImage image, int attempt) {
    }

    private boolean publishOne(long id) {
        // Claim the pin first so it is never published twice, even if two ticks overlap.
        Optional<Job> claimed = QuarkusTransaction.requiringNew().call(() -> {
            if (ScheduledPin.update("status = ?1 where id = ?2 and status = ?3",
                    ScheduledPin.Status.PUBLISHING, id, ScheduledPin.Status.SCHEDULED) == 0) {
                return Optional.<Job>empty();
            }
            ScheduledPin p = ScheduledPin.findById(id);
            p.attempts++;
            PinImage image = p.hasImage() ? new PinImage(p.imageData, p.imageMediaType) : null;
            return Optional.of(new Job(p.boardId, p.pin(), p.link, p.imageUrl, image, p.attempts));
        });
        if (claimed.isEmpty()) {
            return false;
        }
        Job job = claimed.get();
        try {
            PinPublisher.PublishedPin result = job.image() != null
                    ? publisher.publish(job.boardId(), job.pin(), job.link(), job.image())
                    : publisher.publish(job.boardId(), job.pin(), job.link(), job.imageUrl());
            QuarkusTransaction.requiringNew().run(() -> {
                ScheduledPin p = ScheduledPin.findById(id);
                p.status = ScheduledPin.Status.PUBLISHED;
                p.pinterestPinId = result.id();
                p.pinterestUrl = result.url();
                p.publishedAt = Instant.now();
                p.lastError = null;
            });
            return true;
        } catch (RuntimeException e) {
            Failure failure = classify(e);
            boolean retry = failure.retryable() && job.attempt() < maxAttempts;
            if (retry) {
                LOG.warnf("Scheduled pin %d failed (attempt %d of %d), will retry: %s", id, job.attempt(), maxAttempts, failure.message());
            } else {
                LOG.warnf("Scheduled pin %d failed: %s", id, failure.message());
            }
            QuarkusTransaction.requiringNew().run(() -> {
                ScheduledPin p = ScheduledPin.findById(id);
                p.lastError = PinWriter.truncate(failure.message(), 1000);
                if (retry) {
                    p.status = ScheduledPin.Status.SCHEDULED;
                    p.nextAttemptAt = Instant.now().plus(retryDelay(job.attempt()));
                } else {
                    p.status = ScheduledPin.Status.FAILED;
                }
            });
            return false;
        }
    }

    /** 1, 4, 16... minutes. */
    static Duration retryDelay(int attempt) {
        return FIRST_RETRY_DELAY.multipliedBy(1L << (2 * Math.min(Math.max(attempt - 1, 0), 10)));
    }

    record Failure(boolean retryable, String message) {
    }

    static Failure classify(RuntimeException e) {
        if (e instanceof PinterestApiException p) {
            return switch (p.status()) {
                case 401 -> new Failure(false, "The Pinterest access token is missing, invalid or expired.");
                case 429 -> new Failure(true, "Pinterest rate limit reached.");
                default -> p.status() >= 500
                        ? new Failure(true, "Pinterest returned an error (HTTP " + p.status() + ").")
                        : new Failure(false, "Pinterest rejected the pin: " + p.getMessage());
            };
        }
        if (e instanceof ProcessingException) {
            return new Failure(true, "Pinterest could not be reached.");
        }
        if (e instanceof InvalidImageException) {
            return new Failure(false, e.getMessage());
        }
        LOG.error("Unexpected error publishing a scheduled pin", e);
        return new Failure(true, "Unexpected error: " + e.getClass().getSimpleName());
    }

    private static ScheduledPin find(long id) {
        ScheduledPin p = ScheduledPin.findById(id);
        if (p == null) {
            throw new ApiException(404, "not_found", "No scheduled pin with id " + id + ".");
        }
        return p;
    }

    /** The hashtags column holds the joined tags; drop trailing ones that don't fit. */
    private static PinIdea fitHashtags(PinIdea pin) {
        List<String> tags = new ArrayList<>(pin.hashtags());
        while (String.join(" ", tags).length() > MAX_HASHTAG_CHARS) {
            tags.remove(tags.size() - 1);
        }
        return new PinIdea(pin.title(), pin.description(), tags, pin.altText(), null, null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
