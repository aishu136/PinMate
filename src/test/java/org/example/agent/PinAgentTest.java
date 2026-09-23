package org.example.agent;

import com.anthropic.errors.RateLimitException;
import org.example.agent.model.PinIdea;
import org.example.agent.model.PinRequest;
import org.example.agent.model.PinResponse;
import org.example.agent.model.SourcePage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.example.agent.PinWriterTest.pin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PinAgentTest {

    private static final String URL = "https://example.com/recipes";

    private PageReader reader;
    private PinWriter writer;

    @BeforeEach
    void setUp() {
        reader = mock(PageReader.class);
        writer = mock(PinWriter.class);
    }

    @Test
    void noUrlSkipsFetch() {
        when(writer.write(any(), any(), any())).thenReturn(draft(pin("A"), pin("B")));

        PinResponse response = agent(TestConfig.defaults()).generate(new PinRequest("topic", null, null, null, 2));

        verify(reader, never()).read(anyString());
        assertNull(response.source());
        assertEquals(1, response.attempts());
        assertEquals(2, response.pins().size());
        assertEquals("claude-opus-5", response.model());
    }

    @Test
    void urlIsFetchedAndPassedToWriter() {
        SourcePage page = new SourcePage(URL, true, "Brief.");
        when(reader.read(URL)).thenReturn(page);
        when(writer.write(any(), eq(page), any())).thenReturn(draft(pin("A")));

        PinResponse response = agent(TestConfig.defaults()).generate(new PinRequest("topic", " " + URL + " ", null, null, 1));

        verify(reader).read(URL);
        assertEquals(page, response.source()); // graph state is serialized, so compare by value
    }

    @Test
    void fetchCanBeDisabled() {
        when(writer.write(any(), isNull(), any())).thenReturn(draft(pin("A")));

        agent(new TestConfig(false, 2)).generate(new PinRequest("topic", URL, null, null, 1));

        verify(reader, never()).read(anyString());
    }

    @Test
    void retriesWithFeedbackWhenOutputIsShort() {
        when(writer.write(any(), any(), any()))
                .thenReturn(draft(pin("A")))
                .thenReturn(draft(pin("A"), pin("B"), pin("C")));

        PinResponse response = agent(TestConfig.defaults()).generate(new PinRequest("topic", null, null, null, 3));

        assertEquals(2, response.attempts());
        assertEquals(3, response.pins().size());
        verify(writer).write(any(), any(), eq(""));
        verify(writer).write(any(), any(), eq("Only 1 of the 3 requested pins were returned."));
    }

    @Test
    void stopsAtMaxAttemptsAndReturnsBestEffort() {
        when(writer.write(any(), any(), any())).thenReturn(draft(pin("Same"), pin("same")));

        PinResponse response = agent(TestConfig.defaults()).generate(new PinRequest("topic", null, null, null, 2));

        verify(writer, times(2)).write(any(), any(), any());
        assertEquals(2, response.attempts());
        assertEquals(2, response.pins().size());
    }

    @Test
    void nodeFailuresSurfaceUnwrapped() {
        PinGenerationException refusal = new PinGenerationException(422, "declined");
        when(writer.write(any(), any(), any())).thenThrow(refusal);

        PinGenerationException thrown = assertThrows(PinGenerationException.class,
                () -> agent(TestConfig.defaults()).generate(new PinRequest("topic", null, null, null, 1)));
        assertSame(refusal, thrown);
    }

    @Test
    void unwrapFindsSdkExceptionInCauseChain() {
        RateLimitException rateLimit = mock(RateLimitException.class);
        RuntimeException wrapped = new RuntimeException(new IllegalStateException(rateLimit));
        assertInstanceOf(RateLimitException.class, PinAgent.unwrap(wrapped));
    }

    @Test
    void findIssuesChecksCountAndDuplicates() {
        assertEquals("", PinAgent.findIssues(List.of(pin("A"), pin("B")), 2));
        assertTrue(PinAgent.findIssues(List.of(pin("A")), 2).contains("Only 1 of the 2"));
        assertTrue(PinAgent.findIssues(List.of(pin("Pasta"), pin("pasta")), 2).contains("different"));
    }

    private PinAgent agent(AgentConfig config) {
        return new PinAgent(reader, writer, config);
    }

    private static PinWriter.Draft draft(PinIdea... pins) {
        return new PinWriter.Draft(List.of(pins), "claude-opus-5");
    }
}
