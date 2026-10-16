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

Web UI: <http://localhost:8080/> · API docs: <http://localhost:8080/q/swagger-ui/> · Dev UI: <http://localhost:8080/q/dev/> · Health: <http://localhost:8080/q/health>

### Web UI

Open <http://localhost:8080/> to generate pins from a topic, a link or an image, edit the copy (with
Pinterest's length limits shown as you type), copy it, or publish it to one of your boards. If the server
sets `PINMATE_API_KEY`, enter the key under **API key**; it is kept in that browser only.

### API key

`/api/*` spends Claude credits and can post to your Pinterest account, so it is protected:

- **`PINMATE_API_KEY` not set:** only requests from this machine (localhost) are accepted, and browser
  requests must come from PinMate's own page (other websites you have open can't call the API).
- **`PINMATE_API_KEY` set:** every request, including from localhost, must send it:
  `curl -H "X-API-Key: $PINMATE_API_KEY" ...`

Behind a reverse proxy on the same machine every request looks local, so always set a key there.
`/q/health` stays open.

## API

Interactive docs are at `/q/swagger-ui/` and the OpenAPI spec at `/q/openapi` (`?format=json` for JSON), in dev mode
and packaged builds. If the server sets `PINMATE_API_KEY`, click **Authorize** and enter it to try requests.

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
  ],
  "warnings": []
}
```

`warnings` lists brand rules the pins still break after the last attempt (see [Brand voices](#brand-voices)).

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

## Scheduling pins

Queue a pin to publish later. In the web UI, choose **Publish → Later**; the **Scheduled** tab
(<http://localhost:8080/#scheduled>) lists what's upcoming, failed and published.

Scheduled pins are stored in an embedded H2 database in `./data/` (created on first start), and the server checks for
due pins every 30 seconds, so it must be running at the scheduled time; pins that come due while it is down go out
when it starts. When Pinterest is unreachable, busy (5xx) or rate limiting, a pin is retried after 1 and then 4 minutes;
after `pinmate.schedule.max-attempts` tries, or straight away when Pinterest rejects the pin (wrong board, bad
image, expired token), it is marked `FAILED` with the reason and can be retried. A pin that was mid-publish when the
server stopped is marked `FAILED` rather than retried, since it may already be on Pinterest.

| Endpoint                                | Notes                                                                 |
|-----------------------------------------|-----------------------------------------------------------------------|
| `POST /api/schedule`                    | Body of `POST /api/pinterest/pins` plus `publishAt` and optional `boardName` (shown in lists). 201 |
| `POST /api/schedule/upload`             | Form of `POST /api/pinterest/pins/upload` plus `publishAt` and optional `boardName`. JPEG or PNG. 201 |
| `GET /api/schedule[?status=...]`        | All pins, or those `SCHEDULED`, `PUBLISHING`, `PUBLISHED` or `FAILED` |
| `GET /api/schedule/{id}`                | One pin, including `attempts`, `lastError` and `pinterestUrl`          |
| `GET /api/schedule/{id}/image`          | The uploaded image                                                     |
| `POST /api/schedule/{id}/retry`         | Puts a `FAILED` pin back in the queue to publish now                   |
| `DELETE /api/schedule/{id}`             | Cancels a pending pin or removes a finished one from the list (it stays on Pinterest) |

`publishAt` is an ISO-8601 time with an offset, e.g. `2026-10-20T09:00:00Z` or `2026-10-20T09:00:00+05:30`.

```shell
curl -X POST http://localhost:8080/api/schedule \
  -H "Content-Type: application/json" \
  -d '{
        "boardId": "1234567890",
        "publishAt": "2026-10-20T09:00:00Z",
        "imageUrl": "https://example.com/images/noodles.jpg",
        "link": "https://example.com/vegan-recipes",
        "pin": {"title": "15-Minute Vegan Dinners", "description": "Quick meals... Save this pin!",
                "hashtags": ["#vegandinner"], "altText": "Bowl of peanut noodles"}
      }'
```

## Brand voices

Save a brand once and generate with it everywhere: pick it under **Brand** in the Create or Bulk form
(**Manage** adds and edits brands), or pass its id as `?brandId=` to `/api/pins/generate`, as a `brandId` form
field to `/api/pins/generate-from-image`, or as `brandId` in a bulk job.

| Field              | Effect                                                                                     |
|--------------------|--------------------------------------------------------------------------------------------|
| `voice`, `guidelines` | Given to Claude with every request                                                      |
| `audience`         | Used when the request doesn't name one                                                     |
| `bannedWords`      | Given to Claude; pins that still contain one (whole words, any case, hashtags included) are rewritten, and any left after the last attempt come back in the response's `warnings` |
| `requiredHashtags` | Up to 5, added first to every pin in code (Pinterest's 8-hashtag limit still applies)       |

| Endpoint                 | Notes                                    |
|--------------------------|------------------------------------------|
| `GET /api/brands`        | All brands, by name                      |
| `POST /api/brands`       | Create; 409 `duplicate_brand` if the name is taken (ignoring case) |
| `GET /api/brands/{id}`   |                                          |
| `PUT /api/brands/{id}`   | Replace all fields                       |
| `DELETE /api/brands/{id}`| Pending bulk pages then run without it   |

```shell
curl -X POST http://localhost:8080/api/brands -H "Content-Type: application/json" \
  -d '{"name":"Green Table Kitchen","voice":"Warm, practical, no hype","audience":"busy parents",
       "bannedWords":["cheap","guilt-free"],"requiredHashtags":["#greentablekitchen"]}'

curl -X POST "http://localhost:8080/api/pins/generate?brandId=1" -H "Content-Type: application/json" \
  -d '{"topic":"easy weeknight vegan dinners"}'
```

An unknown `brandId` returns 400 `unknown_brand`.

## Bulk generation

Write pins for many pages at once. In the web UI open **Bulk** (<http://localhost:8080/#bulk>), paste a sitemap,
an RSS or Atom feed, or a list of page URLs, pick the pages, and start the job. Each page goes through the same agent
as `/api/pins/generate` (read the page, write pins, check and retry), two pages at a time, in the background; the job
keeps running if you close the page, and pages interrupted by a restart are written again. Results can be copied,
opened in the editor to publish or schedule, or exported as CSV.

Each page is a separate Claude request, so a job's cost grows with its size; jobs are capped at
`pinmate.bulk.max-items` pages (default 50). Topics come from feed titles, or from the URL when there is none
(`/blog/easy-vegan-dinners/` becomes "easy vegan dinners").

Feeds are downloaded by the server. Sitemap indexes (up to 5 child sitemaps) and `.xml.gz` files work; at most 200
pages are listed. Feeds on localhost or private network addresses are refused so the server can't be used to probe
its own network (`pinmate.bulk.allow-private-hosts=true` turns that off, e.g. for a local test site).

| Endpoint                         | Notes                                                                  |
|----------------------------------|------------------------------------------------------------------------|
| `POST /api/bulk/discover`        | `{"source": "<sitemap or feed URL>"}` → the pages in it, with topics   |
| `POST /api/bulk`                 | `{"items": [{"url": "...", "topic": "optional"}], "source", "audience", "tone", "variations"}` → 201, the job |
| `GET /api/bulk`                  | Jobs, newest first, with counts                                        |
| `GET /api/bulk/{id}`             | A job with each page's status, error and pins                          |
| `GET /api/bulk/{id}/pins.csv`    | All pins, one row per pin                                              |
| `POST /api/bulk/{id}/cancel`     | Stops pages that haven't started                                       |
| `POST /api/bulk/{id}/retry`      | Queues failed and cancelled pages again                                |
| `DELETE /api/bulk/{id}`          | Deletes the job and its pins                                           |

```shell
curl -X POST http://localhost:8080/api/bulk/discover   -H "Content-Type: application/json" -d '{"source":"https://example.com/sitemap.xml"}'

curl -X POST http://localhost:8080/api/bulk   -H "Content-Type: application/json"   -d '{"variations":2,"tone":"cozy","items":[{"url":"https://example.com/easy-vegan-dinners/"},{"url":"https://example.com/fall-decor"}]}'
```

## Errors

All errors return `{"error": "...", "message": "..."}`.

| Status | Meaning                                                         |
|--------|-----------------------------------------------------------------|
| 400    | Invalid request (blank topic, bad URL, variations outside 1–5, missing image, unknown `brandId`) |
| 401    | Missing or wrong `X-API-Key` (see [API key](#api-key))          |
| 403    | Browser request from another website's page (no API key set)     |
| 413    | Image larger than 5 MB                                          |
| 415    | Uploaded file isn't a JPEG, PNG, GIF or WebP image              |
| 422    | Claude declined the request                                     |
| 429    | Rate limited by the Claude API                                  |
| 502    | Unusable model response (truncated / empty)                      |
| 503    | Claude API unreachable, or API key missing/invalid              |

Bulk errors: `invalid_source` (400), `blocked_host` (400, private or local address), `feed_unreachable` (422),
`not_a_feed` (422), `feed_too_large` (422, over 10 MB), `invalid_url` and `too_many_items` (400), `running` (409,
deleting a job while a page is being written).

Scheduling errors: `invalid_time` (400, unparseable or past `publishAt`), `not_found` (404), `not_failed` (409,
retrying a pin that hasn't failed) and `publishing` (409, deleting a pin while it is being published).

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
| `pinmate.schedule.poll-interval`    | `30s`           | How often due scheduled pins are published           |
| `pinmate.schedule.max-attempts`     | `3`             | Tries per scheduled pin when Pinterest is unreachable or busy |
| `pinmate.bulk.max-items`            | `50`            | Pages per bulk job                                   |
| `pinmate.bulk.concurrency`          | `2`             | Bulk pages written at the same time                  |
| `pinmate.bulk.allow-private-hosts`  | `false`         | Allow feeds on localhost / private network addresses |
| `quarkus.datasource.jdbc.url`       | `jdbc:h2:file:./data/pinmate` | Where scheduled pins and bulk jobs are stored |

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
