package org.example.agent;

import org.example.agent.model.Brand;
import org.example.agent.model.PinIdea;
import org.example.agent.model.PinRequest;
import org.example.agent.model.PinResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How a {@link Brand} shapes generation: prompt, required hashtags, banned words, default audience. */
class BrandRulesTest {

    private static final Brand BRAND = new Brand("Green Table", "busy parents", "warm, practical, no hype",
            "Mention prep time when the page gives it.", List.of("cheap", "super food"), List.of("#greentable"));

    private PinWriter writer;
    private PinAgent agent;

    @BeforeEach
    void setUp() {
        writer = mock(PinWriter.class);
        agent = new PinAgent(mock(PageReader.class), writer, TestConfig.defaults());
    }

    private static PinIdea pin(String title, String description, String... tags) {
        return new PinIdea(title, description, List.of(tags), "alt", List.of(), "idea");
    }

    private static PinWriter.Draft draft(PinIdea... pins) {
        return new PinWriter.Draft(List.of(pins), "claude-opus-5");
    }

    @Test
    void addsRequiredHashtagsFirstAndUsesTheBrandAudience() {
        when(writer.write(any(), any(), any(), any(), eq(BRAND)))
                .thenReturn(draft(pin("Quick dinners", "Fast meals.", "#dinner", "#greentable")));

        PinResponse r = agent.generate(new PinRequest("dinners", null, null, null, 1), null, BRAND);

        assertEquals(List.of("#greentable", "#dinner"), r.pins().get(0).hashtags());
        assertTrue(r.warnings().isEmpty());
        ArgumentCaptor<PinRequest> request = ArgumentCaptor.forClass(PinRequest.class);
        verify(writer).write(request.capture(), any(), any(), any(), eq(BRAND));
        assertEquals("busy parents", request.getValue().audience(), "brand audience fills a blank request audience");
    }

    @Test
    void requestAudienceWinsOverTheBrand() {
        when(writer.write(any(), any(), any(), any(), any())).thenReturn(draft(pin("A", "B.")));
        agent.generate(new PinRequest("dinners", null, "students", null, 1), null, BRAND);
        ArgumentCaptor<PinRequest> request = ArgumentCaptor.forClass(PinRequest.class);
        verify(writer).write(request.capture(), any(), any(), any(), any());
        assertEquals("students", request.getValue().audience());
    }

    @Test
    void bannedWordsTriggerARetryWithFeedback() {
        when(writer.write(any(), any(), any(), any(), any()))
                .thenReturn(draft(pin("Cheap weeknight dinners", "A SUPER FOOD bowl.")))
                .thenReturn(draft(pin("Budget weeknight dinners", "A hearty bowl.")));

        PinResponse r = agent.generate(new PinRequest("dinners", null, null, null, 1), null, BRAND);

        assertEquals(2, r.attempts());
        assertEquals("Budget weeknight dinners", r.pins().get(0).title());
        assertTrue(r.warnings().isEmpty());
        verify(writer).write(any(), any(), any(), eq("Never use these words or phrases: cheap, super food."), any());
    }

    @Test
    void warnsWhenBannedWordsSurviveTheLastAttempt() {
        when(writer.write(any(), any(), any(), any(), any())).thenReturn(draft(pin("Fine", "Ok."), pin("Cheap eats", "Ok.")));

        PinResponse r = agent.generate(new PinRequest("dinners", null, null, null, 2), null, BRAND);

        verify(writer, times(2)).write(any(), any(), any(), any(), any());
        assertEquals(List.of("Pin 2 uses words the Green Table brand avoids: cheap"), r.warnings());
    }

    @Test
    void matchesWholeWordsOnly() {
        assertEquals(List.of(), BRAND.bannedWordsIn("Cheapest options and superfoods"));
        assertEquals(List.of("cheap"), BRAND.bannedWordsIn("#cheap eats"));
        assertEquals(List.of("super food"), BRAND.bannedWordsIn("this Super  Food".replace("  ", " ")));
    }

    @Test
    void promptCarriesTheBrand() {
        String prompt = PinWriter.buildUserPrompt(new PinRequest("dinners", null, null, null, 1), null, null, null, BRAND);
        assertTrue(prompt.contains("<brand name=\"Green Table\">"), prompt);
        assertTrue(prompt.contains("<voice>warm, practical, no hype</voice>"), prompt);
        assertTrue(prompt.contains("<never_use>cheap, super food</never_use>"), prompt);
        assertTrue(prompt.contains("<required_hashtags>#greentable</required_hashtags>"), prompt);
        assertTrue(prompt.contains("Mention prep time"), prompt);
    }
}
