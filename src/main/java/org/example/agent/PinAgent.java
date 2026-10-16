package org.example.agent;

import com.anthropic.errors.AnthropicException;
import jakarta.enterprise.context.ApplicationScoped;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.example.agent.model.Brand;
import org.example.agent.model.PinIdea;
import org.example.agent.model.PinImage;
import org.example.agent.model.PinRequest;
import org.example.agent.model.PinResponse;
import org.example.agent.model.SourcePage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * The pin-generation agent, as a LangGraph4j state graph:
 *
 * <pre>
 * START ─┬─ has url ─> fetch_page ─┐
 *        └─ no url ────────────────┴─> generate ─> validate ─┬─ ok / out of attempts ─> END
 *                                          ^─────── retry ────┘
 * </pre>
 */
@ApplicationScoped
public class PinAgent {

    static final String FETCH_PAGE = "fetch_page";
    static final String GENERATE = "generate";
    static final String VALIDATE = "validate";

    /** Graph state. Every key uses the default last-value-wins channel. */
    static class PinState extends AgentState {
        static final String REQUEST = "request";
        static final String IMAGE = "image";
        static final String BRAND = "brand";
        static final String SOURCE = "source";
        static final String PINS = "pins";
        static final String MODEL = "model";
        static final String ATTEMPTS = "attempts";
        static final String FEEDBACK = "feedback";

        PinState(Map<String, Object> data) {
            super(data);
        }

        PinRequest request() {
            return this.<PinRequest>value(REQUEST).orElseThrow();
        }

        PinImage image() {
            return this.<PinImage>value(IMAGE).orElse(null);
        }

        Brand brand() {
            return this.<Brand>value(BRAND).orElse(null);
        }

        SourcePage source() {
            return this.<SourcePage>value(SOURCE).orElse(null);
        }

        List<PinIdea> pins() {
            return this.<List<PinIdea>>value(PINS).orElse(List.of());
        }

        String model() {
            return this.<String>value(MODEL).orElse(null);
        }

        int attempts() {
            return this.<Integer>value(ATTEMPTS).orElse(0);
        }

        String feedback() {
            return this.<String>value(FEEDBACK).orElse("");
        }
    }

    private final PageReader pageReader;
    private final PinWriter pinWriter;
    private final AgentConfig config;
    private final CompiledGraph<PinState> graph;

    public PinAgent(PageReader pageReader, PinWriter pinWriter, AgentConfig config) {
        this.pageReader = pageReader;
        this.pinWriter = pinWriter;
        this.config = config;
        this.graph = buildGraph();
    }

    public PinResponse generate(PinRequest request) {
        return generate(request, null);
    }

    /** @param image uploaded pin image, or null */
    public PinResponse generate(PinRequest request, PinImage image) {
        return generate(request, image, null);
    }

    /**
     * @param image uploaded pin image, or null
     * @param brand brand voice to apply, or null; its audience is used when the request has none
     */
    public PinResponse generate(PinRequest request, PinImage image, Brand brand) {
        if (brand != null && !PinWriter.hasText(request.audience()) && PinWriter.hasText(brand.audience())) {
            request = new PinRequest(request.topic(), request.url(), brand.audience(), request.tone(), request.variations());
        }
        Map<String, Object> input = new HashMap<>();
        input.put(PinState.REQUEST, request);
        if (image != null) {
            input.put(PinState.IMAGE, image);
        }
        if (brand != null) {
            input.put(PinState.BRAND, brand);
        }
        PinState state;
        try {
            state = graph.invoke(input).orElseThrow();
        } catch (RuntimeException e) {
            throw unwrap(e);
        }
        return new PinResponse(request.topic(), state.model(), state.source(), state.attempts(), state.pins(),
                brandWarnings(state.pins(), brand));
    }

    private CompiledGraph<PinState> buildGraph() {
        try {
            return new StateGraph<>(Map.of(), PinState::new)
                    .addNode(FETCH_PAGE, node_async(this::fetchPage))
                    .addNode(GENERATE, node_async(this::generate))
                    .addNode(VALIDATE, node_async(this::validate))
                    .addConditionalEdges(START, edge_async(this::routeStart),
                            Map.of(FETCH_PAGE, FETCH_PAGE, GENERATE, GENERATE))
                    .addEdge(FETCH_PAGE, GENERATE)
                    .addEdge(GENERATE, VALIDATE)
                    .addConditionalEdges(VALIDATE, edge_async(this::routeAfterValidate),
                            Map.of(GENERATE, GENERATE, END, END))
                    .compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("Invalid pin agent graph", e);
        }
    }

    // --- nodes ---

    private Map<String, Object> fetchPage(PinState state) {
        return Map.of(PinState.SOURCE, pageReader.read(state.request().url().strip()));
    }

    private Map<String, Object> generate(PinState state) {
        PinWriter.Draft draft = pinWriter.write(state.request(), state.source(), state.image(), state.feedback(),
                state.brand());
        return Map.of(
                PinState.PINS, withRequiredHashtags(draft.pins(), state.brand()),
                PinState.MODEL, draft.model(),
                PinState.ATTEMPTS, state.attempts() + 1);
    }

    private Map<String, Object> validate(PinState state) {
        return Map.of(PinState.FEEDBACK,
                findIssues(state.pins(), state.request().variationsOrDefault(), state.brand()));
    }

    // --- edges ---

    private String routeStart(PinState state) {
        return config.fetch().enabled() && PinWriter.hasText(state.request().url()) ? FETCH_PAGE : GENERATE;
    }

    private String routeAfterValidate(PinState state) {
        return !state.feedback().isEmpty() && state.attempts() < config.maxAttempts() ? GENERATE : END;
    }

    static String findIssues(List<PinIdea> pins, int requested) {
        return findIssues(pins, requested, null);
    }

    /** Problems worth one more generate pass; empty when the pins are fine. */
    static String findIssues(List<PinIdea> pins, int requested, Brand brand) {
        List<String> issues = new ArrayList<>();
        if (pins.size() < requested) {
            issues.add("Only " + pins.size() + " of the " + requested + " requested pins were returned.");
        }
        Set<String> titles = new HashSet<>();
        for (PinIdea pin : pins) {
            if (!PinWriter.hasText(pin.title()) || !PinWriter.hasText(pin.description())) {
                issues.add("Every pin needs a title and a description.");
                break;
            }
            if (!titles.add(pin.title().toLowerCase(Locale.ROOT))) {
                issues.add("Pin titles must all be different.");
                break;
            }
        }
        List<String> banned = bannedWordsIn(pins, brand);
        if (!banned.isEmpty()) {
            issues.add("Never use these words or phrases: " + String.join(", ", banned) + ".");
        }
        return String.join(" ", issues);
    }

    /** The brand's required hashtags come first on every pin; Pinterest's limit of 8 still applies. */
    static List<PinIdea> withRequiredHashtags(List<PinIdea> pins, Brand brand) {
        if (brand == null || brand.requiredHashtags().isEmpty()) {
            return pins;
        }
        return pins.stream().map(p -> {
            List<String> tags = new ArrayList<>(brand.requiredHashtags());
            if (p.hashtags() != null) {
                tags.addAll(p.hashtags());
            }
            return new PinIdea(p.title(), p.description(), PinWriter.normalizeHashtags(tags), p.altText(),
                    p.suggestedBoards(), p.imageIdea());
        }).toList();
    }

    private static List<String> bannedWordsIn(List<PinIdea> pins, Brand brand) {
        if (brand == null || brand.bannedWords().isEmpty()) {
            return List.of();
        }
        Set<String> found = new LinkedHashSet<>();
        for (PinIdea pin : pins) {
            found.addAll(brand.bannedWordsIn(pinText(pin)));
        }
        return List.copyOf(found);
    }

    /** Banned words still present after the last attempt, one warning per affected pin. */
    static List<String> brandWarnings(List<PinIdea> pins, Brand brand) {
        if (brand == null || brand.bannedWords().isEmpty()) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < pins.size(); i++) {
            List<String> found = brand.bannedWordsIn(pinText(pins.get(i)));
            if (!found.isEmpty()) {
                warnings.add("Pin " + (i + 1) + " uses words the " + brand.name() + " brand avoids: "
                        + String.join(", ", found));
            }
        }
        return warnings;
    }

    private static String pinText(PinIdea p) {
        return String.join("\n", nullToEmpty(p.title()), nullToEmpty(p.description()), nullToEmpty(p.altText()),
                p.hashtags() == null ? "" : String.join(" ", p.hashtags()));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Node failures come back wrapped by the graph runner; surface the original so the error mappers apply. */
    public static RuntimeException unwrap(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PinGenerationException || t instanceof AnthropicException) {
                return (RuntimeException) t;
            }
        }
        return e;
    }
}
