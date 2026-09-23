package org.example.agent.pinterest;

import org.example.agent.model.PinIdea;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PinPublisherTest {

    @Test
    void hashtagsFollowDescription() {
        assertEquals("Save this!\n\n#a #b", PinPublisher.descriptionWithHashtags("Save this!", List.of("#a", "#b")));
        assertEquals("#a", PinPublisher.descriptionWithHashtags("  ", List.of("#a")));
        assertEquals("Only text", PinPublisher.descriptionWithHashtags("Only text", List.of()));
        assertNull(PinPublisher.descriptionWithHashtags(null, null));
    }

    @Test
    void dropsTrailingHashtagsToStayWithinPinterestLimit() {
        String description = "d".repeat(780);
        String result = PinPublisher.descriptionWithHashtags(description, List.of("#first", "#second", "#third"));
        assertTrue(result.length() <= PinPublisher.MAX_DESCRIPTION);
        assertEquals(description + "\n\n#first #second", result);
    }

    @Test
    void missingTokenFailsWithoutCallingPinterest() {
        PinterestApi api = mock(PinterestApi.class);
        PinPublisher publisher = new PinPublisher(api, Optional::empty);
        PinIdea pin = new PinIdea("t", "d", List.of(), "a", List.of(), null);

        PinterestApiException e = assertThrows(PinterestApiException.class,
                () -> publisher.publish("111", pin, null, "https://example.com/pin.jpg"));
        assertEquals(401, e.status());
        verifyNoInteractions(api);
    }
}
