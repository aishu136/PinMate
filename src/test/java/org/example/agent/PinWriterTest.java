package org.example.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
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
import org.example.agent.model.PinImage;
import org.example.agent.model.PinRequest;
import org.example.agent.model.SourcePage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PinWriterTest {

    private MessageService messages;
    private PinWriter writer;

    @BeforeEach
    void setUp() {
        AnthropicClient client = mock(AnthropicClient.class);
        messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);
        writer = new PinWriter(client, TestConfig.defaults());
    }

    @Test
    void sendsEffortSchemaFallbackAndPageBrief() {
        stubResponse(StopReason.END_TURN, new PinIdeas(List.of(pin("Title"))));
        SourcePage source = new SourcePage("https://example.com/recipes", true, "Ten vegan recipes under 20 minutes.");

        writer.write(new PinRequest("vegan dinners", "https://example.com/recipes", "busy parents", "playful", 1),
                source, null, "Pin titles must all be different.");

        MessageCreateParams params = capturedParams();
        OutputConfig outputConfig = params.outputConfig().orElseThrow();
        assertEquals(Optional.of(OutputConfig.Effort.HIGH), outputConfig.effort());
        assertTrue(outputConfig.format().isPresent(), "structured output schema must be sent");
        assertEquals(JsonValue.from("default"), params._additionalBodyProperties().get("fallbacks"));
        assertEquals(List.of("server-side-fallback-2026-07-01"), params._additionalHeaders().values("anthropic-beta"));

        String prompt = promptText(params);
        assertTrue(prompt.contains("<topic>vegan dinners</topic>"));
        assertTrue(prompt.contains("<audience>busy parents</audience>"));
        assertTrue(prompt.contains("<page_brief>\nTen vegan recipes under 20 minutes.\n</page_brief>"));
        assertTrue(prompt.contains("<feedback>Pin titles must all be different.</feedback>"));
    }

    @Test
    void unreadablePageIsFlaggedInPrompt() {
        String prompt = PinWriter.buildUserPrompt(
                new PinRequest("topic", "https://example.com", null, null, null), SourcePage.failed("https://example.com"), null, null);
        assertTrue(prompt.contains("could not be read"));
        assertFalse(prompt.contains("<page_brief>"));
        assertFalse(prompt.contains("<feedback>"));
    }

    @Test
    void returnsNormalizedPinsCappedAtRequestedCount() {
        PinIdea messy = new PinIdea("  " + "a".repeat(150) + "  ", "desc",
                List.of("#vegan", "vegan", "meal prep", "##dinner", " "), "alt",
                List.of("Dinner Ideas", "Dinner Ideas", ""), "overhead shot");
        stubResponse(StopReason.END_TURN, new PinIdeas(List.of(messy, pin("Second"), pin("Third"))));

        PinWriter.Draft draft = writer.write(new PinRequest("vegan dinners", null, null, null, 2), null, null, null);

        assertEquals(2, draft.pins().size());
        assertEquals("claude-opus-5", draft.model());
        PinIdea first = draft.pins().get(0);
        assertTrue(first.title().length() <= PinWriter.MAX_TITLE);
        assertEquals(List.of("#vegan", "#mealprep", "#dinner"), first.hashtags());
        assertEquals(List.of("Dinner Ideas"), first.suggestedBoards());
    }

    @Test
    void refusalBecomes422() {
        stubResponse(StopReason.REFUSAL, null);
        PinGenerationException e = assertThrows(PinGenerationException.class,
                () -> writer.write(new PinRequest("topic", null, null, null, null), null, null, null));
        assertEquals(422, e.status());
    }

    @Test
    void truncatedResponseBecomes502() {
        stubResponse(StopReason.MAX_TOKENS, null);
        PinGenerationException e = assertThrows(PinGenerationException.class,
                () -> writer.write(new PinRequest("topic", null, null, null, null), null, null, null));
        assertEquals(502, e.status());
    }

    @Test
    void sendsImageBeforePromptAndInfersMissingTopic() {
        stubResponse(StopReason.END_TURN, new PinIdeas(List.of(pin("Title"))));
        PinImage image = new PinImage(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, PinImage.PNG);

        writer.write(new PinRequest(null, null, null, null, 1), null, image, null);

        List<ContentBlockParam> blocks = capturedParams().messages().get(0).content().asBlockParams();
        assertEquals(2, blocks.size());
        Base64ImageSource source = blocks.get(0).asImage().source().asBase64();
        assertEquals(Base64ImageSource.MediaType.IMAGE_PNG, source.mediaType());
        assertEquals(Base64.getEncoder().encodeToString(image.data()), source.data());

        String prompt = blocks.get(1).asText().text();
        assertTrue(prompt.contains("for the attached image"));
        assertTrue(prompt.contains("infer it from the image"));
        assertFalse(prompt.contains("<topic>"));
    }

    @Test
    void truncatePrefersWordBoundary() {
        String result = PinWriter.truncate("one two three four five six seven", 20);
        assertTrue(result.length() <= 20);
        assertEquals("one two three four…", result);
        assertEquals("short", PinWriter.truncate("  short ", 20));
    }

    private static String promptText(MessageCreateParams params) {
        List<ContentBlockParam> blocks = params.messages().get(0).content().asBlockParams();
        return blocks.get(blocks.size() - 1).asText().text();
    }

    private MessageCreateParams capturedParams() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<StructuredMessageCreateParams<PinIdeas>> captor = ArgumentCaptor.forClass(StructuredMessageCreateParams.class);
        verify(messages).create(captor.capture());
        return captor.getValue().rawParams();
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

    static PinIdea pin(String title) {
        return new PinIdea(title, "A description. Save this pin!", List.of("#idea"), "alt text", List.of("Board"), "image");
    }
}
