# PinMate — AI Pinterest pin content generator

A Quarkus REST service that uses Claude to write Pinterest-ready pin copy: SEO-friendly titles,
descriptions, hashtags, alt text, board suggestions and an image concept, in several variations per topic.

## Requirements

- Java 21+ and Maven (or the bundled `./mvnw`)
- An Anthropic API key: `export ANTHROPIC_API_KEY=sk-ant-...` (PowerShell: `$env:ANTHROPIC_API_KEY="sk-ant-..."`)

## Run

```shell
./mvnw quarkus:dev
```

Dev UI: <http://localhost:8080/q/dev/> · Health: <http://localhost:8080/q/health>

## API

### `POST /api/pins/generate`

| Field        | Required | Notes                                              |
|--------------|----------|----------------------------------------------------|
| `topic`      | yes      | Up to 300 characters                               |
| `url`        | no       | Destination link; used as context only, not fetched |
| `audience`   | no       | e.g. "busy parents"                                |
| `tone`       | no       | e.g. "playful", "minimal"                          |
| `variations` | no       | 1–5, default 3                                     |

```shell
curl -X POST http://localhost:8080/api/pins/generate \
  -H "Content-Type: application/json" \
  -d '{"topic":"easy weeknight vegan dinners","audience":"busy parents","tone":"friendly","variations":2}'
```

```json
{
  "topic": "easy weeknight vegan dinners",
  "model": "claude-opus-5",
  "pins": [
    {
      "title": "15-Minute Vegan Dinners for Busy Weeknights",
      "description": "Easy vegan dinner ideas ready in 15 minutes... Save this pin for your next meal plan!",
      "hashtags": ["#vegandinner", "#easyrecipes", "#mealprep"],
      "altText": "Colorful bowl of peanut noodles with vegetables on a wooden table",
      "suggestedBoards": ["Vegan Recipes", "Quick Weeknight Meals"],
      "imageIdea": "Overhead shot of three bowls with bold text overlay"
    }
  ]
}
```

Pinterest limits are enforced on every response (title ≤ 100 chars, description and alt text ≤ 500 chars,
at most 8 de-duplicated `#hashtags`).

### Errors

All errors return `{"error": "...", "message": "..."}`.

| Status | Meaning                                                         |
|--------|-----------------------------------------------------------------|
| 400    | Invalid request (blank topic, bad URL, variations outside 1–5)   |
| 422    | Claude declined the request                                     |
| 429    | Rate limited by the Claude API                                  |
| 502    | Unusable model response (truncated / empty)                      |
| 503    | Claude API unreachable, or API key missing/invalid              |

## Configuration (`src/main/resources/application.properties`)

| Property                            | Default         | Description                                          |
|-------------------------------------|-----------------|------------------------------------------------------|
| `pinterest-agent.model`             | `claude-opus-5` | Claude model ID                                      |
| `pinterest-agent.max-tokens`        | `16000`         | Max output tokens per request                        |
| `pinterest-agent.effort`            | `high`          | `low` / `medium` / `high` / `xhigh` / `max`          |
| `pinterest-agent.refusal-fallbacks` | `true`          | Retry declined requests on a fallback model (server-side) |

Any property can be overridden with an env var, e.g. `PINTEREST_AGENT_EFFORT=medium`.

## Tests

```shell
./mvnw test
```

Tests mock the Claude client, so they need no API key and make no paid calls.

## Packaging

```shell
./mvnw package                       # target/quarkus-app/quarkus-run.jar
./mvnw package -Dnative              # native executable (GraalVM)
```
