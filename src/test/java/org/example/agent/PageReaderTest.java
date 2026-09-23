package org.example.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.WebFetchTool20260209;
import com.anthropic.models.messages.WebFetchToolResultBlock;
import com.anthropic.models.messages.WebFetchToolResultErrorBlock;
import com.anthropic.models.messages.WebFetchToolResultErrorCode;
import com.anthropic.services.blocking.MessageService;
import org.example.agent.model.SourcePage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageReaderTest {

    private static final String URL = "https://shop.example.com/vegan-cookbook";

    private MessageService messages;
    private PageReader reader;

    @BeforeEach
    void setUp() {
        AnthropicClient client = mock(AnthropicClient.class);
        messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);
        reader = new PageReader(client, TestConfig.defaults());
    }

    @Test
    void returnsBriefAndRestrictsFetchToDestinationDomain() {
        Message response = message(StopReason.END_TURN, fetchSuccess(), text("A cookbook with 80 vegan recipes."));
        when(messages.create(any(MessageCreateParams.class))).thenReturn(response);

        SourcePage page = reader.read(URL);

        assertTrue(page.fetched());
        assertEquals("A cookbook with 80 vegan recipes.", page.summary());

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(messages).create(captor.capture());
        WebFetchTool20260209 tool = captor.getValue().tools().orElseThrow().get(0).asWebFetchTool20260209();
        assertEquals(Optional.of(List.of("shop.example.com")), tool.allowedDomains());
        assertTrue(captor.getValue().messages().get(0).content().asString().contains(URL));
    }

    @Test
    void fetchToolErrorFallsBackToTopicOnly() {
        Message response = message(StopReason.END_TURN, fetchError(), text("I could not load that page."));
        when(messages.create(any(MessageCreateParams.class))).thenReturn(response);

        SourcePage page = reader.read(URL);

        assertFalse(page.fetched());
        assertNull(page.summary());
    }

    @Test
    void firstFetchFailingButRetrySucceedingCountsAsFetched() {
        Message response = message(StopReason.END_TURN, fetchError(), fetchSuccess(), text("Brief."));
        when(messages.create(any(MessageCreateParams.class))).thenReturn(response);

        assertTrue(reader.read(URL).fetched());
    }

    @Test
    void failureMarkerFallsBack() {
        Message response = message(StopReason.END_TURN, fetchSuccess(), text(PageReader.FETCH_FAILED));
        when(messages.create(any(MessageCreateParams.class))).thenReturn(response);

        assertFalse(reader.read(URL).fetched());
    }

    @Test
    void continuesPausedTurn() {
        Message paused = message(StopReason.PAUSE_TURN, fetchSuccess());
        when(paused.toParam()).thenReturn(
                MessageParam.builder().role(MessageParam.Role.ASSISTANT).content("(fetching)").build());
        Message done = message(StopReason.END_TURN, text("Final brief."));
        when(messages.create(any(MessageCreateParams.class))).thenReturn(paused, done);

        SourcePage page = reader.read(URL);

        assertTrue(page.fetched());
        assertEquals("Final brief.", page.summary());
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(messages, times(2)).create(captor.capture());
        assertEquals(2, captor.getAllValues().get(1).messages().size(), "continuation resends the paused turn");
    }

    private static Message message(StopReason stopReason, ContentBlock... blocks) {
        Message message = mock(Message.class);
        when(message.stopReason()).thenReturn(Optional.of(stopReason));
        when(message.content()).thenReturn(List.of(blocks));
        return message;
    }

    private static ContentBlock text(String value) {
        TextBlock text = mock(TextBlock.class);
        when(text.text()).thenReturn(value);
        ContentBlock block = mock(ContentBlock.class);
        when(block.text()).thenReturn(Optional.of(text));
        return block;
    }

    private static ContentBlock fetchSuccess() {
        WebFetchToolResultBlock.Content content = mock(WebFetchToolResultBlock.Content.class);
        when(content.isWebFetchBlock()).thenReturn(true);
        return fetchResult(content);
    }

    private static ContentBlock fetchError() {
        WebFetchToolResultErrorBlock error = mock(WebFetchToolResultErrorBlock.class);
        when(error.errorCode()).thenReturn(WebFetchToolResultErrorCode.URL_NOT_ACCESSIBLE);
        WebFetchToolResultBlock.Content content = mock(WebFetchToolResultBlock.Content.class);
        when(content.isWebFetchBlock()).thenReturn(false);
        when(content.asWebFetchToolResultErrorBlock()).thenReturn(error);
        return fetchResult(content);
    }

    private static ContentBlock fetchResult(WebFetchToolResultBlock.Content content) {
        WebFetchToolResultBlock result = mock(WebFetchToolResultBlock.class);
        when(result.content()).thenReturn(content);
        ContentBlock block = mock(ContentBlock.class);
        when(block.webFetchToolResult()).thenReturn(Optional.of(result));
        return block;
    }
}
