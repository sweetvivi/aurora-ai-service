# Backend Service

Spring Boot 3.5 baseline for a Java 17 backend.

Requires JDK 17 or newer (`JAVA_HOME` must point to the JDK). Maven is downloaded automatically by the included wrapper.

## Included

- Spring Web and Bean Validation
- Spring Data JPA with persistent MySQL by default
- In-memory H2 for tests only, with environment-based MySQL overrides
- Actuator health, info, and metrics endpoints
- Springdoc OpenAPI with a Chinese-first Knife4j console (no Swagger UI on the classpath)
- Flyway-managed MySQL schema for the `/newchat` conversation platform
- Example `Product` CRUD API with validation and consistent error responses
- Site bootstrap API (`GET /api/v1/app/config`) with white label settings, feature switches and
  `app-name` / host based site resolution
- AI conversation API (`/api/v1/chat/completions`, `/api/v1/chat/stream`) backed by an
  OpenAI-compatible gateway, with conversation and message persistence
- Conversation history API (`/api/v1/conversations`) scoped to the current account: list, detail,
  cursor-paged messages, rename and soft delete
- Attachment API (`/api/v1/files`) for uploads and images, backed by a swappable storage port
- Authentication API (`/api/v1/auth`) with BCrypt passwords, short-lived JWT access tokens and
  rotating refresh sessions, plus per-address registration throttling
- Model catalogue API (`/api/v1/models`) exposing display fields only — the upstream route never
  leaves the server — plus a vendor-folded `/api/v1/models/families` view; both take `?type=` so a
  chat picker and a drawing panel never list each other's models
- Image generation API (`/api/v1/images/generations`); generated images become ordinary owned
  attachments
- Conversation API with explicit creation, message appends without generation, and batch delete
- Admin API under `/api/admin/v1`: upstream provider management with a **plaintext key field the
  console can read back**, a one-field status toggle (`PUT /providers/{id}/status`) instead of the
  read-modify-write a console would otherwise do, a queryable `category` column and per-supplier
  `images_path`, model types (chat / image / video / audio), full model create/update/delete with
  capability and per-direction pricing fields, `/sites` and `/model-groups` reference lists so the
  forms can offer selects, upstream model discovery, and deletes that explain themselves instead of
  failing on a foreign key — see [docs/provider-admin-api.md](docs/provider-admin-api.md)
- Supplier protocols actually honoured: OpenAI, OpenAI-compatible, Ollama, Azure OpenAI, Anthropic
  Messages and Google Gemini each get their own request shape
- Conversation sharing with a frozen snapshot, so editing a thread does not change what was shared
- Frontend integration notes in [docs/frontend-integration.md](docs/frontend-integration.md)
- Context and MockMvc integration tests

## Run

```powershell
$env:AUTH_JWT_SECRET = 'replace-with-at-least-32-random-bytes'
.\mvnw.cmd spring-boot:run
```

The default profile connects to the local persistent `backend` database at
`jdbc:mysql://127.0.0.1:3306/backend`. Local development defaults to `root/root`; override
`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, and `DB_PASSWORD` when needed:

```powershell
$env:AUTH_JWT_SECRET = 'replace-with-at-least-32-random-bytes'
.\mvnw.cmd spring-boot:run
```

The MySQL profile runs Flyway migrations from `src/main/resources/db/migration` and uses
`ddl-auto=validate`. Restarting the service therefore keeps existing rows instead of recreating the
schema. Tests activate the `test` profile and use a disposable in-memory H2 database, so
`.\mvnw.cmd test` does not touch the local MySQL data. See
[docs/database-schema.md](docs/database-schema.md) for the schema and database verification
commands.

`AUTH_JWT_SECRET` is mandatory and must be at least 32 bytes; the service refuses to start without
it. Set `ADMIN_API_KEY` as well before calling any `/api/admin/**` endpoint.

项目现状、风险和改进优先级见 [docs/project-audit.md](docs/project-audit.md)；完整复刻规划（用户端、后台端、技术栈、数据模型、接口、可靠性和分阶段路线）见 [docs/ai1foo-rebuild-plan.md](docs/ai1foo-rebuild-plan.md)。

API documentation is served at `/doc.html` (Knife4j); health checks are available at `/actuator/health`.

## App configuration API

The client bootstrap call. The site is resolved from the `app-name` header, then the request host
(via `app_sites.domain`), then the configured default site; an explicitly requested but unknown
site is rejected with `404` instead of silently serving another tenant.

| Endpoint | Description |
| --- | --- |
| `GET /api/v1/app/config` | Canonical bootstrap payload: white label name, feature switches, announcement, default model. |
| `POST /api/v2/config/init` | Compatibility adapter for the legacy site path, identical payload. |

```bash
curl -s http://localhost:8080/api/v1/app/config
curl -s -H 'app-name: default' http://localhost:8080/api/v1/app/config
```

```json
{
  "siteKey": "default",
  "appName": "Default Site",
  "defaultModel": "deepseek-v4.1-flash",
  "features": { "chat": true, "stream": true, "attachment": false, "share": true, "points": false },
  "announcement": "欢迎使用 AI 对话服务",
  "serverTime": "2026-10-14T09:31:36.191117600Z"
}
```

Feature switches and the default model are merged as *server defaults → per-site overrides*, so a
site with no settings rows still answers with a usable payload. Details in
[docs/app-config-api.md](docs/app-config-api.md).

## AI conversation API

Both endpoints speak plain OpenAI-style JSON and accept the same body.

```jsonc
{
  "model": "deepseek-v4.1-flash",     // optional, falls back to app.llm.default-model
  "messages": [                        // required, at least one role=user entry
    { "role": "system", "content": "You are concise." },
    { "role": "user", "content": "Say pong" }
  ],
  "temperature": 0.7,                  // optional, 0.0 - 2.0
  "maxTokens": 512,                    // optional, 1 - 32768
  "conversationId": 12,                // optional, appends to an existing conversation
  "title": "First chat"                // optional, used only when a conversation is created
}
```

| Endpoint | Description |
| --- | --- |
| `POST /api/v1/chat/completions` | Blocking turn, answers with `{conversationId, messageId, model, content, finishReason, usage, latencyMs}`. |
| `POST /api/v1/chat/stream` | The same turn as Server-Sent Events: `message.created`, `message.thinking`, `message.delta`, `message.completed`, `message.cancelled`, `message.failed`. |
| `POST /api/v1/chat/requests/{requestId}/cancel` | Stop an in-flight streamed generation. `requestId` comes from `message.created`. |

Stopping a generation closes the upstream connection rather than merely ignoring further tokens, and
stores what the client had already received as a `CANCELLED` assistant message before sending
`message.cancelled`. Stopping an already finished generation is a `204`, so a stop button pressed at
the wrong moment is not an error. Details in [docs/chat-api.md](docs/chat-api.md) §5.

```bash
curl -s http://localhost:8080/api/v1/chat/completions \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"Say pong"}]}'

curl -sN http://localhost:8080/api/v1/chat/stream \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"Count to three"}]}'
```

Every turn is persisted to `conversations` and `chat_messages`, owned by the account behind the
access token. A conversation is created on demand; a failed upstream call is still recorded as an
assistant message with `status = FAILED`. The full contract is described in
[docs/chat-api.md](docs/chat-api.md).

Error mapping: `400` for validation errors, a missing user turn, or a model whose type does not
match the endpoint; `401` for a missing or invalid access token; `404` for an unknown conversation
or a retired catalogue model; `502` when the gateway is unreachable or errors out.

Only `CHAT` models are accepted here. Without that rule a drawing model could be forwarded to the
chat endpoint and — the part that actually corrupts data — be stamped into
`chat_messages.model_id` as the model that produced the answer, so pricing and per-model reporting
would read another model's row. This path and the image path resolve through a single
`ModelUseResolver`, so the rule cannot drift apart between them.

## Conversation history API

Every endpoint requires `Authorization: Bearer <accessToken>` and is scoped to that account. These
request-facing endpoints never fall back to `X-User-Id` or a demo account; a missing or invalid
token is a `401`. A conversation owned by somebody else is reported as `404`, never `403`, so the
API does not leak the existence of other accounts' data.

| Endpoint | Description |
| --- | --- |
| `GET /api/v1/conversations?page=0&size=20` | History list, newest activity first. |
| `GET /api/v1/conversations/{id}` | Conversation summary. |
| `GET /api/v1/conversations/{id}/messages?cursor=&size=20` | Messages, keyset paged by message id. |
| `PATCH /api/v1/conversations/{id}` | Rename. |
| `DELETE /api/v1/conversations/{id}` | Soft delete; messages are kept for audit. |

```bash
curl -s -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/api/v1/conversations
curl -s -H "Authorization: Bearer $ACCESS_TOKEN" \
  'http://localhost:8080/api/v1/conversations/1/messages?size=20'
```

Message paging returns `nextCursor` and `hasMore`; pass `nextCursor` back as `cursor`. Keyset paging
is used instead of an offset because messages are appended continuously — an offset page would
duplicate or skip rows, while `WHERE id > ?` stays stable and index-cheap. Details in
[docs/conversation-api.md](docs/conversation-api.md).

## Attachment API

Uploads are scoped to the current account, like conversations. Images are ordinary attachments —
`image/png`, `image/jpeg`, `image/gif`, `image/webp` and `image/bmp` are on the allow list, so a
screenshot goes through the same endpoint as a PDF.

| Endpoint | Description |
| --- | --- |
| `POST /api/v1/files` | `multipart/form-data`, field `file`, optional `conversationId`. |
| `GET /api/v1/files?conversationId=&page=&size=` | Uploads of the account, newest first. |
| `GET /api/v1/files/{id}` | Metadata, including SHA-256 and a download URL. |
| `GET /api/v1/files/{id}/content` | Bytes, served as `attachment`. |
| `DELETE /api/v1/files/{id}` | Marks deleted and drops the blob. |

```bash
curl -X POST http://localhost:8080/api/v1/files \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -F "file=@screenshot.png"
curl -s -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/api/v1/files
```

Validation requires an allow-listed extension, an allow-listed MIME type, and a MIME family that
matches the extension. Recognised image and PDF formats are additionally checked against their
magic bytes, so neither a renamed executable nor a forged `Content-Type` is enough. The blob is
written first and the row second; if the insert fails the blob is deleted again, so a failed upload
never leaves an orphan object. Details in [docs/file-api.md](docs/file-api.md).

## Authentication API

| Endpoint | Description |
| --- | --- |
| `POST /api/v1/auth/register` | Create an account on the resolved site and sign in. |
| `POST /api/v1/auth/login` | Username and password sign in. |
| `POST /api/v1/auth/refresh` | Exchange a refresh token for a new pair, rotating the session. |
| `POST /api/v1/auth/logout` | Revoke the current session. |
| `GET /api/v1/auth/me` | The account behind the access token. |

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"correct-horse-battery"}'

curl -s http://localhost:8080/api/v1/auth/me -H "Authorization: Bearer $ACCESS_TOKEN"
```

Two token shapes on purpose. The **access token** is a short-lived HS256 JWT verified without a
database round trip; the **refresh token** is an opaque secret of which only the SHA-256 is stored,
so a database leak cannot be replayed. Every request still re-checks the session row, which is why
logout takes effect immediately rather than at token expiry. Reusing an already rotated refresh
token is treated as theft and revokes every active session of the account. Details in
[docs/auth-api.md](docs/auth-api.md).

The algorithm is pinned to HS256 *after* the signature check, signatures are compared in constant
time, and a missing or shorter-than-32-byte JWT secret refuses to start. No signing key is committed:
**`AUTH_JWT_SECRET` must be supplied in every environment**, including local development.

## Model catalogue API

Readable without authentication, because the site shows the model list before sign in.

| Endpoint | Description |
| --- | --- |
| `GET /api/v1/models?group=&type=` | Groups and models visible to the resolved site. `type` narrows to `CHAT` / `IMAGE` / `VIDEO` / `AUDIO`. |
| `GET /api/v1/models/{modelKey}` | One model. |

```bash
curl -s http://localhost:8080/api/v1/models
curl -s http://localhost:8080/api/v1/models/deepseek-v4.1-flash
```

Visibility is `status = ACTIVE AND (site_id IS NULL OR site_id = <resolved site>)`: rows without a
site are global, the rest belong to one white label site. Groups and models come back as two
parallel arrays so that a model with no group still reaches the client instead of being dropped.

Every entry carries `modelType`, and that type is enforced when the model is used: the chat
endpoints only run `CHAT` models, `POST /api/v1/images/generations` only runs `IMAGE` ones, and a
mismatch is a `400` rather than a request forwarded to the wrong upstream endpoint.

`GET /api/v1/models/families` returns the same visible models folded by vendor for a model picker.
It takes the same `?type=` filter as the catalogue and applies it through the same helper, so both
views agree on what a type filter means.

`llm_models.route` — the provider specific model name actually sent upstream — is **not** part of the
response type, so it cannot leak by accident. Details in
[docs/model-catalog-api.md](docs/model-catalog-api.md).

## Configuration

Model gateway settings live under `app.llm` in `application.yml`. Override them per environment
instead of editing the file:

| Variable | Default | Purpose |
| --- | --- | --- |
| `LLM_BASE_URL` | `https://api.example.com/v1` | Base URL of the OpenAI-compatible gateway |
| `APP_LLM_API_KEY` | empty | Optional legacy service-wide upstream credential. User AI calls use each account's New API relay token instead. |
| `LLM_DEFAULT_MODEL` | `deepseek-v4.1-flash` | Model used when a request omits `model` |

Site resolution and the baseline feature switches live under `app.site.*`; per-site overrides come
from the `app_site_settings` table. `app.chat.persistence-enabled=false` turns the conversation
tables into a no-op, which is useful when only the raw completion is needed.

Attachments are stored through `infra/storage/FileStorage`; the shipped `LocalFileStorage` writes to
`app.storage.local-root` (`STORAGE_LOCAL_ROOT`, default `./data/uploads`, git-ignored). Swapping in
an S3/MinIO adapter is a bean replacement — `modules/file` does not change.

Finally, `app.security.trust-user-header` must remain `false` outside local development. It only
remains for legacy non-request resolver paths; request-facing account APIs require a Bearer token
and ignore `X-User-Id`.

## Logging

Five destinations, split by purpose (`src/main/resources/logback-spring.xml`), all rooted at
`logging.file.path` (`LOG_DIR`, default `./logs`):

| Destination | Content | Why separate |
| --- | --- | --- |
| `backend.log` | everything | the main file to grep during an incident |
| `backend-error.log` | WARN and above | alerting only needs to tail this |
| `startup.log` | Spring Boot start-up + Flyway + the `STARTUP` summary | answers "which config/profile/datasource did this instance actually boot with" without digging through business logs |
| `sql.log` | Hibernate SQL and bound parameters | a single chat turn emits dozens of statements; they would drown `backend.log` |
| `request_logs` / `audit_logs` tables | one row per HTTP request / per data change | queryable, with retention and an admin UI |

SQL logging is off by default and is switched with a single boolean:

| Variable | Default | Purpose |
| --- | --- | --- |
| `SQL_LOG_ENABLED` | `false` | master switch; `SqlLogEnvironmentPostProcessor` maps it onto `logging.level.*` before logback starts |
| `SQL_LOG_BIND_VALUES` | `true` | show `?` → actual value |
| `SQL_LOG_RESULT_VALUES` | `false` | also log result-set extraction (very noisy) |
| `SQL_LOG_JDBC_TEMPLATE` | `false` | include hand-written `JdbcTemplate` statements |
| `LOG_CONSOLE_LEVEL` | `DEBUG` | `OFF` silences the console in containers |

Local development turns it on in `.env` (`SQL_LOG_ENABLED=true`); `deploy/transfer/docker-compose.yml`
defaults it to `false`. **Keep it false in production**: bound parameters are written by Hibernate
directly to the logger, i.e. *below* `SensitiveMasker`, so conversation text, phone numbers and
passwords reach the file verbatim. The `startup features ... sqlLog=on|off` line in `startup.log` is
the quickest way to confirm what a given instance is running with.

Tests write to `target/test-logs` instead of `logs/` — the suite boots hundreds of Spring contexts,
and letting them append to the real `startup.log` makes that file useless for its one job.

## API documentation

The documentation console is served by the running application itself — no extra profile, no
interceptor in front of it, and exactly one console:

| URL | What it is |
| --- | --- |
| `/doc.html` | **Knife4j**: Chinese-first sidebar grouping, request debugging, offline export |
| `/v3/api-docs` | The raw OpenAPI document |

There is deliberately no second console. springdoc is pulled in through
`springdoc-openapi-starter-webmvc-api` rather than `-webmvc-ui`, so the Swagger UI webjar is not on
the classpath and `/swagger-ui/*` is a `404`. Two consoles are how you end up with one of them fixed
and the other still handing out a stale security scheme.

**One subtlety, learned the hard way.** Knife4j's bundle reads `/v3/api-docs/swagger-config` to find
out which document to load, and that handler is registered by the swagger-ui autoconfiguration — so
dropping `-webmvc-ui` removes it, and setting `springdoc.swagger-ui.enabled=false` removes it too.
`/doc.html` keeps answering `200` either way (it is only a static page) while every request behind it
404s, so the console opens **blank** and looks like an outage rather than a misconfiguration.
`SwaggerConfigEndpoint` therefore serves that one payload itself, and
`DocumentationConsoleTest#theConsoleCanActuallyBootstrapItsDocument` walks the whole chain — pointer,
then the document it names — instead of trusting the single URL an operator is most likely to check
by hand.

The console authenticates with the real thing: the document declares an HTTP **bearer** scheme, so
the Authorize button sends `Authorization: Bearer <accessToken>`. It used to declare an `apiKey`
header literally named `Token`, which nothing in this service reads — every authorised request from
the console therefore failed with a `401` while the page itself looked fine.

To export the machine readable contract to `target/openapi/openapi.json` — for review, diffing or
client generation — run the dedicated profile:

```powershell
.\mvnw.cmd -Popenapi verify -DskipTests
```

## Tests

```powershell
.\mvnw.cmd test
```

Unit tests cover the SSE decoder and the gateway adapter (against a local stub server), the chat
orchestration service (Mockito), and both HTTP endpoints end to end through `MockMvc`.
