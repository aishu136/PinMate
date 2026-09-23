package org.example.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.agent.model.PinIdea;
import org.example.agent.model.PinIdeas;
import org.example.agent.model.PinRequest;
import org.example.agent.model.PinResponse;
import org.jboss.logging.Logger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@ApplicationScoped
public class PinContentService {

    // Pinterest field limits
    static final int MAX_TITLE = 100;
    static final int MAX_DESCRIPTION = 500;
    static final int MAX_ALT_TEXT = 500;
    static final int MAX_HASHTAGS = 8;

    private static final Logger LOG = Logger.getLogger(PinContentService.class);

    private static final String SYSTEM_PROMPT = """
            You are an expert Pinterest content strategist and SEO copywriter.
            Write pin copy that ranks in Pinterest search and earns saves and clicks:
            - Put the most searchable keywords early in the title and the first sentence of the description.
            - Write naturally for people; no keyword stuffing, no clickbait, no ALL CAPS.
            - Keep titles under 100 characters and descriptions under 500 characters.
            - End each description with a clear, specific call to action.
            - Make each variation meaningfully different in angle (e.g. how-to, list, inspiration, problem/solution).
            - Never invent facts, prices, statistics or claims about the destination link.
            """;

    /** JSON schema derived from {@link PinIdeas}; computed once via the SDK's typed builder. */
    static final JsonOutputFormat PIN_IDEAS_FORMAT = MessageCreateParams.builder()
            .model("schema-only").maxTokens(1).addUserMessage("-")
            .outputConfig(PinIdeas.class)
            .build().rawParams().outputConfig().flatMap(OutputConfig::format).orElseThrow();

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;
    private final String effort;
    private final boolean refusalFallbacks;

    public PinContentService(AnthropicClient client,
                             @ConfigProperty(name = "pinterest-agent.model") String model,
                             @ConfigProperty(name = "pinterest-agent.max-tokens") long maxTokens,
                             @ConfigProperty(name = "pinterest-agent.effort") String effort,
                             @ConfigProperty(name = "pinterest-agent.refusal-fallbacks") boolean refusalFallbacks) {
        this.client = client;
        this.model = model;
        this.maxTokens = maxTokens;
        this.effort = effort;
        this.refusalFallbacks = refusalFallbacks;
    }

    public PinResponse generate(PinRequest request) {
        StructuredMessageCreateParams.Builder<PinIdeas> builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(SYSTEM_PROMPT)
                .outputConfig(PinIdeas.class)
                // Re-set the full config: outputConfig(Class) alone replaces the whole object and drops effort.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).format(PIN_IDEAS_FORMAT).build())
                .addUserMessage(buildUserPrompt(request));

        if (refusalFallbacks) {
            // Server-side fallback: if the model declines, the API retries on a recommended fallback model.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        StructuredMessage<PinIdeas> message = client.messages().create(builder.build());
        LOG.debugf("Claude usage for topic '%s': %s", request.topic(), message.usage());

        StopReason stopReason = message.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stopReason)) {
            String reason = message.stopDetails().flatMap(d -> d.explanation()).orElse("the request was declined");
            throw new PinGenerationException(422, "Claude declined to generate content: " + reason);
        }
        if (StopReason.MAX_TOKENS.equals(stopReason)) {
            throw new PinGenerationException(502, "Response was truncated; increase pinterest-agent.max-tokens");
        }

        List<PinIdea> pins = message.content().stream()
                .flatMap(block -> block.text().stream())
                .flatMap(text -> text.text().pins().stream())
                .filter(Objects::nonNull)
                .map(PinContentService::enforceLimits)
                .limit(request.variationsOrDefault())
                .toList();

        if (pins.isEmpty()) {
            throw new PinGenerationException(502, "Claude returned no pin ideas");
        }
        return new PinResponse(request.topic(), message.model().asString(), pins);
    }

    static String buildUserPrompt(PinRequest request) {
        StringBuilder sb = new StringBuilder()
                .append("Create exactly ").append(request.variationsOrDefault())
                .append(" Pinterest pin variations.\n\n<topic>").append(request.topic().strip()).append("</topic>\n");
        if (hasText(request.audience())) {
            sb.append("<audience>").append(request.audience().strip()).append("</audience>\n");
        }
        if (hasText(request.tone())) {
            sb.append("<tone>").append(request.tone().strip()).append("</tone>\n");
        }
        if (hasText(request.url())) {
            sb.append("<destination_url>").append(request.url().strip()).append("</destination_url>\n")
                    .append("The URL is context only; you cannot open it, so don't describe its contents.\n");
        }
        return sb.toString();
    }

    /** Model output is guided by the prompt, but Pinterest limits are enforced here as a hard guarantee. */
    static PinIdea enforceLimits(PinIdea pin) {
        return new PinIdea(
                truncate(pin.title(), MAX_TITLE),
                truncate(pin.description(), MAX_DESCRIPTION),
                normalizeHashtags(pin.hashtags()),
                truncate(pin.altText(), MAX_ALT_TEXT),
                pin.suggestedBoards() == null ? List.of()
                        : pin.suggestedBoards().stream().filter(PinContentService::hasText).map(String::strip).distinct().toList(),
                pin.imageIdea() == null ? null : pin.imageIdea().strip());
    }

    static List<String> normalizeHashtags(List<String> hashtags) {
        if (hashtags == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String tag : hashtags) {
            if (!hasText(tag)) {
                continue;
            }
            String cleaned = tag.strip().replaceAll("\\s+", "").replaceFirst("^#+", "");
            if (!cleaned.isEmpty()) {
                seen.add("#" + cleaned);
            }
        }
        return seen.stream().limit(MAX_HASHTAGS).toList();
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String s = value.strip();
        if (s.length() <= max) {
            return s;
        }
        String cut = s.substring(0, max - 1);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > max / 2) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.stripTrailing() + "…";
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
