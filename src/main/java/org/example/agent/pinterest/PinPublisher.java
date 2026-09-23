package org.example.agent.pinterest;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.example.agent.InvalidImageException;
import org.example.agent.PinWriter;
import org.example.agent.model.PinIdea;
import org.example.agent.model.PinImage;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Publishes pins to the Pinterest account that owns the configured access token. */
@ApplicationScoped
public class PinPublisher {

    /** Pinterest's description limit; hashtags are appended to the description and must fit too. */
    static final int MAX_DESCRIPTION = 800;
    static final int BOARD_PAGE_SIZE = 250;
    /** Stop paging after this many boards; plenty for choosing a board. */
    static final int MAX_BOARDS = 1000;

    private static final Logger LOG = Logger.getLogger(PinPublisher.class);

    public record PublishedPin(String id, String boardId, String url) {
    }

    private final PinterestApi api;
    private final PinterestConfig config;

    public PinPublisher(@RestClient PinterestApi api, PinterestConfig config) {
        this.api = api;
        this.config = config;
    }

    public List<PinterestDtos.Board> boards() {
        String auth = authorization();
        List<PinterestDtos.Board> boards = new ArrayList<>();
        String bookmark = null;
        do {
            PinterestDtos.BoardPage page = api.listBoards(auth, bookmark, BOARD_PAGE_SIZE);
            if (page.items() != null) {
                boards.addAll(page.items());
            }
            bookmark = page.bookmark();
        } while (bookmark != null && !bookmark.isBlank() && boards.size() < MAX_BOARDS);
        return boards;
    }

    /** Publishes with an image Pinterest downloads from {@code imageUrl}. */
    public PublishedPin publish(String boardId, PinIdea pin, String link, String imageUrl) {
        return create(boardId, pin, link, PinterestDtos.MediaSource.imageUrl(imageUrl.strip()));
    }

    /** Publishes with uploaded image bytes. */
    public PublishedPin publish(String boardId, PinIdea pin, String link, PinImage image) {
        if (!PinImage.JPEG.equals(image.mediaType()) && !PinImage.PNG.equals(image.mediaType())) {
            throw new InvalidImageException(415,
                    "Pinterest only accepts JPEG or PNG uploads; use imageUrl for " + image.mediaType() + ".");
        }
        String base64 = Base64.getEncoder().encodeToString(image.data());
        return create(boardId, pin, link, PinterestDtos.MediaSource.imageBase64(image.mediaType(), base64));
    }

    private PublishedPin create(String boardId, PinIdea pin, String link, PinterestDtos.MediaSource media) {
        String auth = authorization();
        PinIdea clean = PinWriter.enforceLimits(pin);
        PinterestDtos.PinCreate body = new PinterestDtos.PinCreate(
                boardId.strip(),
                blankToNull(clean.title()),
                descriptionWithHashtags(clean.description(), clean.hashtags()),
                blankToNull(link),
                blankToNull(clean.altText()),
                media);
        PinterestDtos.CreatedPin created = api.createPin(auth, body);
        LOG.infof("Published pin %s to board %s", created.id(), created.boardId());
        return new PublishedPin(created.id(), created.boardId(), "https://www.pinterest.com/pin/" + created.id() + "/");
    }

    /** Appends hashtags after the description, dropping trailing tags if the result would exceed 800 characters. */
    static String descriptionWithHashtags(String description, List<String> hashtags) {
        String desc = description == null ? "" : description.strip();
        List<String> tags = new ArrayList<>(hashtags == null ? List.of() : hashtags);
        while (true) {
            String tagLine = String.join(" ", tags);
            String combined = desc.isEmpty() ? tagLine : tagLine.isEmpty() ? desc : desc + "\n\n" + tagLine;
            if (combined.length() <= MAX_DESCRIPTION || tags.isEmpty()) {
                return blankToNull(PinWriter.truncate(combined, MAX_DESCRIPTION));
            }
            tags.remove(tags.size() - 1);
        }
    }

    private String authorization() {
        String token = config.accessToken().filter(t -> !t.isBlank())
                .orElseThrow(() -> new PinterestApiException(401, "No Pinterest access token configured"));
        return "Bearer " + token.strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
