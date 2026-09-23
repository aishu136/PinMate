package org.example.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredContentBlock;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.services.blocking.MessageService;
import org.example.agent.model.PinIdea;
import org.example.agent.model.PinIdeas;
import org.example.agent.model.PinRequest;
import org.example.agent.model.PinResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PinContentServiceTest {

    private MessageService messages;
    private PinContentService service;

    @BeforeEach
    void setUp() {
        AnthropicClient client = mock(AnthropicClient.class);
        messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);
        service = new PinContentService(client, "claude-opus-5", 16000, "high", true);
    }

    @Test
    void sendsEffortSchemaAndFallbackTogether() {
        stubResponse(StopReason.END_TURN, new PinIdeas(List.of(pin("Title"))));

        service.generate(new PinRequest("vegan dinners", "https://example.com/recipes", "busy parents", "playful", 1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<StructuredMessageCreateParams<PinIdeas>> captor = ArgumentCaptor.forClass(StructuredMessageCreateParams.class);
        verify(messages).create(captor.capture());
        MessageCreateParams params = captor.getValue().rawParams();

        OutputConfig outputConfig = params.outputConfig().orElseThrow();
        assertEquals(Optional.of(OutputConfig.Effort.HIGH), outputConfig.effort());
        assertTrue(outputConfig.format().isPresent(), "structured output schema must be sent");
        assertEquals(JsonValue.from("default"), params._additionalBodyProperties().get("fallbacks"));
        assertEquals(List.of("server-side-fallback-2026-07-01"), params._additionalHeaders().values("anthropic-beta"));

        String prompt = params.messages().get(0).content().asString();
        assertTrue(prompt.contains("<topic>vegan dinners</topic>"));
        assertTrue(prompt.contains("<audience>busy parents</audience>"));
        assertTrue(prompt.contains("<destination_url>https://example.com/recipes</destination_url>"));
    }

    @Test
    void returnsNormalizedPinsCappedAtRequestedCount() {
        PinIdea messy = new PinIdea("  " + "a".repeat(150) + "  ", "desc",
                List.of("#vegan", "vegan", "meal prep", "##dinner", " "), "alt",
                List.of("Dinner Ideas", "Dinner Ideas", ""), "overhead shot");
        stubResponse(StopReason.END_TURN, new PinIdeas(List.of(messy, pin("Second"), pin("Third"))));

        PinResponse response = service.generate(new PinRequest("vegan dinners", null, null, null, 2));

        assertEquals(2, response.pins().size());
        assertEquals("claude-opus-5", response.model());
        PinIdea first = response.pins().get(0);
        assertTrue(first.title().length() <= PinContentService.MAX_TITLE);
        assertEquals(List.of("#vegan", "#mealprep", "#dinner"), first.hashtags());
        assertEquals(List.of("Dinner Ideas"), first.suggestedBoards());
    }

    @Test
    void refusalBecomes422() {
        stubResponse(StopReason.REFUSAL, null);
        PinGenerationException e = assertThrows(PinGenerationException.class,
                () -> service.generate(new PinRequest("topic", null, null, null, null)));
        assertEquals(422, e.status());
    }

    @Test
    void truncatedResponseBecomes502() {
        stubResponse(StopReason.MAX_TOKENS, null);
        PinGenerationException e = assertThrows(PinGenerationException.class,
                () -> service.generate(new PinRequest("topic", null, null, null, null)));
        assertEquals(502, e.status());
    }

    @Test
    void truncatePrefersWordBoundary() {
        String result = PinContentService.truncate("one two three four five six seven", 20);
        assertTrue(result.length() <= 20);
        assertEquals("one two three four…", result);
        assertEquals("short", PinContentService.truncate("  short ", 20));
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(StopReason stopReason, PinIdeas ideas) {
        StructuredMessage<PinIdeas> message = mock(StructuredMessage.class);
        when(message.stopReason()).thenReturn(Optional.of(stopReason));
        when(message.stopDetails()).thenReturn(Optional.empty());
        when(message.model()).thenReturn(Model.of("claude-opus-5"));
        if (ideas != null) {
            StructuredTextBlock<PinIdeas> text = mock(StructuredTextBlock.class);
            when(text.text()).thenReturn(ideas);
            StructuredContentBlock<PinIdeas> block = mock(StructuredContentBlock.class);
            when(block.text()).thenReturn(Optional.of(text));
            when(message.content()).thenReturn(List.of(block));
        }
        when(messages.create(any(StructuredMessageCreateParams.class))).thenReturn(message);
    }

    private static PinIdea pin(String title) {
        return new PinIdea(title, "A description. Save this pin!", List.of("#idea"), "alt text", List.of("Board"), "image");
    }
}
