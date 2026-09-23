# PinMate — AI Pinterest pin content generator

A Quarkus REST service that uses Claude to write Pinterest-ready pin copy: SEO-friendly titles,
descriptions, hashtags, alt text, board suggestions and an image concept, in several variations per topic.
Give it a destination URL and it reads the page first, so the copy is grounded in what the page actually says.

## How it works

The agent is a [LangGraph4j](https://github.com/langgraph4j/langgraph4j) state graph (`PinAgent`):

```
START ─┬─ has url ─> fetch_page ─┐
       └─ no url ────────────────┴─> generate ─> validate ─┬─ ok / out of attempts ─> END
                                         ^─────── retry ────┘
```

- **fetch_page** (`PageReader`): Claude reads the URL with its server-side web fetch tool (restricted to the
  URL's own domain) and writes a short brief. If the page can't be read, generation continues from the topic alone.
- **generate** (`PinWriter`): Claude writes the pins with structured output, looking at the uploaded image
  when there is one; Pinterest limits are enforced in code.
- **validate**: if fewer pins than requested came back, or titles repeat, `generate` runs again with feedback
  (up to `pinterest-agent.max-attempts` passes in total).

## Requirements

- Java 21+ and Maven (or the bundled `./mvnw`)
- An Anthropic API key: `export ANTHROPIC_API_KEY=sk-ant-...` (PowerShell: `$env:ANTHROPIC_API_KEY="sk-ant-..."`)

## Run

```shell
./mvnw quarkus:dev
```

Dev UI: <http://localhost:8080/q/dev/> · Health: <http://localhost:8080/q/health>

### API key

`/api/*` spends Claude credits and can post to your Pinterest account, so it is protected:

- **`PINMATE_API_KEY` not set:** only requests from this machine (localhost) are accepted.
- **`PINMATE_API_KEY` set:** every request, including from localhost, must send it:
  `curl -H "X-API-Key: $PINMATE_API_KEY" ...`

Behind a reverse proxy on the same machine every request looks local, so always set a key there.
`/q/health` stays open.

## API

### `POST /api/pins/generate`

| Field        | Required | Notes                                              |
|--------------|----------|----------------------------------------------------|
| `topic`      | yes      | Up to 300 characters                               |
| `url`        | no       | Destination link; the page is read and used to ground the copy |
| `audience`   | no       | e.g. "busy parents"                                |
| `tone`       | no       | e.g. "playful", "minimal"                          |
| `variations` | no       | 1–5, default 3                                     |

```shell
curl -X POST http://localhost:8080/api/pins/generate \
  -H "Content-Type: application/json" \
  -d '{"topic":"easy weeknight vegan dinners","url":"https://example.com/vegan-recipes","audience":"busy parents","variations":2}'
```

```json
{
  "topic": "easy weeknight vegan dinners",
  "model": "claude-opus-5",
  "source": {
    "url": "https://example.com/vegan-recipes",
    "fetched": true,
    "summary": "A collection of 25 vegan dinners that take 15-20 minutes..."
  },
  "attempts": 1,
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

### `POST /api/pins/generate-from-image`

Upload the pin image and get copy written for it: alt text describes what the image actually shows,
and `imageIdea` becomes a text-overlay or styling suggestion. Send `multipart/form-data` with:

| Field        | Required | Notes                                                        |
|--------------|----------|--------------------------------------------------------------|
| `image`      | yes      | JPEG, PNG, GIF or WebP, up to 5 MB (format is detected from the file) |
| `topic`      | no       | Inferred from the image when omitted                         |
| `url`, `audience`, `tone`, `variations` | no | Same as `/generate`; a URL is still read and used |

```shell
curl -X POST http://localhost:8080/api/pins/generate-from-image \
  -F "image=@living-room.jpg" \
  -F "topic=cozy fall living room decor" \
  -F "variations=3"
```

The response has the same shape as `/generate` (`topic` is `null` when not given).

Pinterest limits are enforced on every response (title ≤ 100 chars, description and alt text ≤ 500 chars,
at most 8 de-duplicated `#hashtags`).

## Publishing to Pinterest

Generate first, review the copy, then publish the pin you like. Publishing uses the Pinterest API v5 with
an access token for your account.

1. Create an app at <https://developers.pinterest.com/apps/> and generate an access token with the scopes
   `boards:read`, `pins:read` and `pins:write`.
2. `export PINTEREST_ACCESS_TOKEN=pina_...` (PowerShell: `$env:PINTEREST_ACCESS_TOKEN="pina_..."`)
3. If your app only has trial access, point it at the sandbox:
   `export QUARKUS_REST_CLIENT_PINTEREST_URL=https://api-sandbox.pinterest.com/v5`

> **Security:** these endpoints post to your Pinterest account. See [API key](#api-key) before exposing
> the server beyond your own machine.

### `GET /api/pinterest/boards`

Lists your boards (`id`, `name`, `privacy`, `pinCount`) so you can pick a `boardId`.

### `POST /api/pinterest/pins` — image from a URL

`pin` accepts a pin object straight from `/api/pins/generate`. Hashtags are appended to the description
(trailing tags are dropped if it would exceed Pinterest's 800-character limit).

```shell
curl -X POST http://localhost:8080/api/pinterest/pins \
  -H "Content-Type: application/json" \
  -d '{
        "boardId": "1234567890",
        "link": "https://example.com/vegan-recipes",
        "imageUrl": "https://example.com/images/noodles.jpg",
        "pin": {"title": "15-Minute Vegan Dinners", "description": "Quick meals... Save this pin!",
                "hashtags": ["#vegandinner"], "altText": "Bowl of peanut noodles"}
      }'
```

```json
{ "id": "987654321", "boardId": "1234567890", "url": "https://www.pinterest.com/pin/987654321/" }
```

### `POST /api/pinterest/pins/upload` — upload the image

Multipart form with `image` (JPEG or PNG only — Pinterest's upload limit), `boardId`, optional `link`,
and `pin` as a JSON part:

```shell
curl -X POST http://localhost:8080/api/pinterest/pins/upload \
  -F "image=@living-room.jpg" -F "boardId=1234567890" \
  -F 'pin={"title":"Cozy Fall Living Room","description":"...","hashtags":["#falldecor"],"altText":"..."};type=application/json'
```

## Errors

All errors return `{"error": "...", "message": "..."}`.

| Status | Meaning                                                         |
|--------|-----------------------------------------------------------------|
| 400    | Invalid request (blank topic, bad URL, variations outside 1–5, missing image) |
| 401    | Missing or wrong `X-API-Key` (see [API key](#api-key))          |
| 413    | Image larger than 5 MB                                          |
| 415    | Uploaded file isn't a JPEG, PNG, GIF or WebP image              |
| 422    | Claude declined the request                                     |
| 429    | Rate limited by the Claude API                                  |
| 502    | Unusable model response (truncated / empty)                      |
| 503    | Claude API unreachable, or API key missing/invalid              |

Pinterest publishing errors use `pinterest_*` codes: `pinterest_not_configured` (503, token missing,
invalid or expired), `pinterest_forbidden` (403), `pinterest_not_found` (404, e.g. wrong board),
`pinterest_rejected` (422, Pinterest refused the pin, with its reason), `pinterest_rate_limited` (429),
`pinterest_unavailable` (503) and `pinterest_error` (502).

## Configuration (`src/main/resources/application.properties`)

| Property                            | Default         | Description                                          |
|-------------------------------------|-----------------|------------------------------------------------------|
| `pinterest-agent.model`             | `claude-opus-5` | Claude model ID                                      |
| `pinterest-agent.max-tokens`        | `16000`         | Max output tokens per request                        |
| `pinterest-agent.effort`            | `high`          | `low` / `medium` / `high` / `xhigh` / `max`          |
| `pinterest-agent.refusal-fallbacks` | `true`          | Retry declined requests on a fallback model (server-side) |
| `pinterest-agent.max-attempts`      | `2`             | Total generate passes, including validation retries  |
| `pinterest-agent.fetch.enabled`     | `true`          | Read destination URLs before writing                 |
| `pinterest-agent.fetch.effort`      | `low`           | Effort for the page-reading call                     |
| `pinterest-agent.fetch.max-content-tokens` | `20000`  | Cap on page content sent to Claude                   |

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
