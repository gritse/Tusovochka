# MCP server for Tusovochka: design spec

Status: proposal, nothing implemented yet. Date: 2026-10-21.

Goal: let an AI agent act on behalf of a user and do what that user can do in the web UI: read the news feed,
replies and notifications, write wall posts and comments, like, add friends, join and post to groups, read and
write private messages, and so on. The agent runs in a headless container (Claude Code or the Claude Agent SDK),
so there is no browser on the agent side and every credential has to be passed in through configuration.

This document covers: what the server already exposes (section 1), how the MCP endpoint fits into the code base
(section 2), the tool surface (section 3), authentication (section 4), security (section 5), rollout and testing
(section 6).

## 0. Summary of the recommendation

- **Build the MCP endpoint into the existing server** as `POST /mcp`, in a new package `smithereen.mcp`. No
  separate process, no proxy, no new deployment unit. Docker, nginx and Caddy configs do not change.
- **Every tool is a thin adapter over the existing JSON API** (`ApiDispatcher.doApiCall`). Tools never touch
  controllers directly, so privacy checks, scopes, flood control, captcha and error mapping are reused as-is.
- **Do not take the official MCP Java SDK as a dependency.** It needs Jakarta Servlet 6.1 (we run Jetty 11 with
  Servlet 5.0 through the patched Spark), pulls in Reactor and Jackson 3, and we only need the request/response
  subset of the protocol. A minimal Streamable HTTP + JSON-RPC handler on top of Gson is about 500 lines and has
  zero new dependencies. Details and the alternative in section 2.3.
- **Authentication: personal access tokens created in Settings → Applications**, stored in the existing
  `api_tokens` table under a built-in "Agents" application, with a user-chosen expiry and scopes, shown once,
  revocable per token. The agent sends it as `Authorization: Bearer …` from an environment variable. This reuses
  the token code path the API already has, so it adds no new trust boundary. The existing OAuth password grant
  works today as a zero-code fallback. Section 4.
- **Phase 1** ships tokens + endpoint + the everyday tools (feed, wall, comments, likes, friends, groups,
  messages, notifications, users, search). Phase 2 adds photos and uploads, polls, board topics, events,
  bookmarks, profile editing. Phase 3 is optional: OAuth discovery for interactive MCP clients. Section 6.

## 1. What exists today

### 1.1 The JSON API

The server already has a complete VK-style JSON API inherited from Smithereen, at `GET|POST /api/method/<name>`
(`src/main/java/smithereen/routes/ApiRoutes.java`, `SmithereenApplication.java:867`). Upstream documentation:
<https://smithereen.software/docs/api>, which still applies except for federation.

- Dispatch: `ApiDispatcher` (`src/main/java/smithereen/api/ApiDispatcher.java`) holds a static registry of
  ~170 methods, each a `(ApplicationContext, ApiCallContext) -> Object` function with a `requireUser` flag and
  an optional set of required `ClientAppPermission` scopes. The dispatcher checks the token, the scopes, the
  `private_server` flag and maps domain exceptions to API errors.
- Call context: `ApiCallContext` carries the token, the account, a `Map<String, Object>` of parameters (strings or
  Gson `JsonElement`s), the HTTP request, the API version and the user's permissions, with typed parameter accessors
  (`requireParamIntPositive`, `optParamString`, …).
- Responses are plain Java objects or records serialised with `ApiRoutes.gson` (snake_case). Errors are
  `ApiErrorException` with an `ApiErrorType` code (`USER_AUTH_FAILED`, `NO_PERMISSION`, `NOT_FOUND`,
  `PARAM_INVALID`, `CAPTCHA_NEEDED`, `TOO_MANY_REQUESTS`, …).
- Flood control: 3 requests/second per token (`FloodControl.API_REQUESTS`), 300 posts per 3 hours per user
  (`FloodControl.POSTS`). Posting may require a captcha (`ApiUtils` → `CAPTCHA_NEEDED`).
- Conventions the tools must follow: users and groups are positive ints; an `owner_id` is a user ID or a
  *negative* group ID (`ApiUtils.getOwner`); posts and comments are post IDs; private messages and photos use
  obfuscated string IDs (`XTEA.decodeObjectID`). Text written through the API takes `text_format`
  (`markdown`, `html`, `plain`), default from the account preference. Text in responses is HTML.
- `execute`: a small scripting VM that runs up to 25 API calls in one request. Not needed for MCP.

Method families (the complete list is the `registerMethod` block in `ApiDispatcher`):

| Family | Methods |
|---|---|
| users | get, getFollowers, getSubscriptions, search |
| friends | get, getOnline, getMutual, areFriends, getLists, add, delete, addList, deleteList, edit, editList, getRequests |
| wall | get, getById, getComments, getReposts, pin, unpin, delete, post, repost, createComment, getEditSource, edit |
| likes | add, delete, isLiked, getList |
| newsfeed | get, getGroups, getComments, getBanned, addBan, deleteBan, getFilters, addFilter, editFilter, deleteFilter |
| notifications | get, markAsViewed |
| messages | get, getById, getHistory, send, delete, restore, markAsRead |
| groups | get, getById, getInvites, join, leave, search, isMember, getMembers, banUser, unbanUser, getBannedUsers, getInvitedUsers, getRequests, approveRequest, removeUser, invite, getSettings, edit, addManager, editManager, deleteManager, reorderManager, addLink, editLink, reorderLink, deleteLink, create (groups *and* events: `type=event`, `start_date`, `finish_date`, `place`) |
| photos | getAttachmentUploadServer, getUploadServer, getOwnerPhotoUploadServer, getAlbums, getAlbumsById, get, getById, getAll, getUserPhotos, createAlbum, editAlbum, deleteAlbum, edit, makeCover, delete, copy, getTags, putTag, removeTag, confirmTag, getNewTags, getComments, createComment, deleteComment, getCommentEditSource, editComment, getFeedEntry, save, saveOwnerPhoto |
| board | getTopics, getTopicsById, getComments, createComment, getCommentEditSource, editComment, deleteComment, createTopic, editTopic, deleteTopic, openTopic, closeTopic, pinTopic, unpinTopic |
| polls | getById, addVote, getVoters, create |
| fave | addUser, removeUser, addGroup, removeGroup, getUsers, getGroups, getPhotos, getPosts |
| status | get, set |
| account | getCounters, setOnline, setOffline, getAppPermissions, getBannedUsers, banUser, unbanUser, revokeToken, getProfileInfo, saveProfileInfo, saveProfileContacts, saveProfileInterests, saveProfilePersonal, getPrivacySettings, savePrivacySettings |
| server, utils | getInfo, report, getAnnouncements; getServerTime, resolveScreenName, testCaptcha, testValidation |

Gaps relative to the web UI, relevant for "do whatever a human can do":

- No full-text search of posts (the web has `/system/qsearch` for users and groups only; the API has
  `users.search` and `groups.search`). Not a blocker.
- Photo upload is two-step: `photos.getAttachmentUploadServer` returns a one-time signed URL, then a multipart
  POST to `/api/uploadAttachmentPhoto`. Video and audio uploads exist only in the web UI. Section 3.4.
- Admin and moderation actions (reports queue, bans, roles) are web-only. Out of scope on purpose.

### 1.2 Authentication and tokens

Everything is in `src/main/java/smithereen/model/apps/*`, `controllers/AppsController.java`,
`storage/AppsStorage.java` and the tables `api_applications`, `api_grants`, `api_codes`, `api_tokens`
(`schema.sql:60-140`).

- A `ClientApp` is registered by a user at `/apps/create` (Settings → Applications → "Create"). Its
  `client_id` is the app URL (`https://<domain>/apps/<id>`), it may list allowed redirect URIs.
- `GET /oauth/authorize` + `POST /oauth/doAuthorize`: browser-based authorization code (with PKCE S256) or implicit
  flow, with a consent page listing scopes. `POST /oauth/token` exchanges the code, or performs a **password
  grant** (`grant_type=password`, `client_id`, `username`, `password`) protected by the login flood control.
- Tokens are 64 random bytes (`AppsController.createAccessToken`), stored raw in `api_tokens` with account,
  app, permissions bitmask, IP, user agent hash, `created_at`, `expires_at`. Tokens from the code flow expire
  after one hour unless the `offline` scope was granted; password-grant tokens never expire and carry the
  pseudo-scope `PASSWORD_GRANT_USED`, which `ApiDispatcher` treats as "all scopes".
- `ApiRoutes.apiCall` accepts the token as `access_token` parameter or `Authorization: Bearer`, checks
  expiry and that the account is active, records last use (batched, async), and applies flood control keyed
  by token.
- Scopes (`ClientAppPermission`): `friends:read|write`, `photos:read|write`, `account`, `wall:read|write`,
  `groups:read|write`, `messages:read|write`, `likes:read|write`, `newsfeed`, `notifications`, `offline`.
- Lifecycle: an hourly job deletes expired codes and tokens (`SmithereenApplication.java:1107`). Changing the
  password revokes every other session *and* every API token (`UsersController.changePassword` →
  `revokeUserAccessTokensExcept`). "End all other sessions" does the same. API tokens show up in Settings →
  "Activity history" next to browser sessions, with the app name (`settings_activity_history.twig`).
- Settings → Applications (`SettingsRoutes.apps`, `settings_apps.twig`) lists granted apps with a per-app
  "revoke access" link (`deleteAccessGrantAndRevokeTokens`) and the apps the user develops.

Web sessions are a separate mechanism (`psid` cookie, `sessions` table, `SessionStorage`), not reused by the API.
There is no two-factor authentication in the code base.

### 1.3 Runtime and build facts that constrain the design

- Java 21, Maven, a JPMS module (`src/main/java/module-info.java`: `open module smithereen.server`). The jar
  runs on the module path (`Dockerfile` ENTRYPOINT, `TESTS.md`). Every dependency must be a named or automatic
  module.
- HTTP server: `me.grishka.sparkjava:spark-core:2.9.4+patch.4` → Jetty 11.0.15 → Jakarta Servlet 5.0. Virtual
  thread pool. One `before` filter (`SmithereenApplication.java:205`) does session handling for every path
  except `/api/`; `isAllowedForAnonymousUsers` (`:1184`) whitelists `/api/` and `/oauth/*` on private servers.
- JSON: Gson 2.13 with `ApiRoutes.gson`. No Jackson.
- Reverse proxy in front (nginx/Caddy examples in `examples/`). `Utils.getRequestIP` uses `req.ip()`, so the
  proxy must set the forwarded IP the way the existing examples do.

## 2. Architecture

### 2.1 Placement

```
POST /mcp            ──►  smithereen.mcp.McpRoutes     (Spark route, auth, Origin check, JSON-RPC envelope)
                               │
                               ▼
                          smithereen.mcp.McpServer     (initialize / ping / tools/list / tools/call)
                               │
                               ▼
                          smithereen.mcp.tools.*       (McpTool implementations: schema + argument mapping)
                               │
                               ▼
                          ApiDispatcher.doApiCall()    (existing API: scopes, privacy, flood control, errors)
```

New package `smithereen.mcp`:

| Class | Responsibility |
|---|---|
| `McpRoutes` | Spark handlers for `/mcp`: `POST` (the endpoint), `GET`/`DELETE` → 405. Reads the bearer token, builds an `ApiCallContext`, enforces Origin and `Accept`, converts `McpError`/`ApiErrorException` into JSON-RPC errors. |
| `McpServer` | Protocol: parses the JSON-RPC message, routes `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`; returns `-32601` for everything else. Stateless. |
| `McpTool` | Interface: `name()`, `title()`, `description()`, `inputSchema()` (a `JsonObject`), `annotations()`, `requiredScopes()`, `call(ToolCallContext, JsonObject args) -> ToolResult`. |
| `ToolRegistry` | Static list of tools, grouped by family; filters `tools/list` by the token's scopes so the agent only sees tools it can call. |
| `ToolCallContext` | Wraps `ApplicationContext` + `ApiCallContext`; helper `api(String method, Map<String,Object> params)` that calls `ApiDispatcher.doApiCall` with a child `ApiCallContext` (same pattern `ExecuteMethods` uses). |
| `ToolResult` | Builds `content: [{type:"text", text}]` plus `structuredContent`, `isError`. |
| `JsonSchema` | Tiny builder for input schemas (`object(...).string("message", "…").integer("post_id").required(...)`). |
| `tools/*Tools.java` | One class per family: `FeedTools`, `WallTools`, `FriendsTools`, `GroupsTools`, `MessagesTools`, `NotificationsTools`, `UsersTools`, `PhotosTools`, `BoardTools`, `MiscTools`. |

Wiring in `SmithereenApplication`:

- `path("/mcp", ()->{ post("", McpRoutes::post); get("", McpRoutes::methodNotAllowed); delete("", McpRoutes::methodNotAllowed); })`
  next to the `/api` block.
- The session `before` filter returns early for `/mcp` exactly like it does for `/api/` (`:205`), and
  `isAllowedForAnonymousUsers` whitelists `/mcp` so a private server returns a 401 JSON-RPC error instead of a
  302 to the login page.
- Config: `mcp.enabled` (default `true`), read in `Config.load`. When off, the route returns 404.

No changes to `ApiDispatcher` or any API method are required for phase 1.

### 2.2 Protocol scope

Streamable HTTP, request/response only. Supported JSON-RPC methods:

| Method | Behaviour |
|---|---|
| `initialize` | Returns `protocolVersion` (negotiated, see below), `capabilities: {tools: {listChanged: false}}`, `serverInfo: {name: "tusovochka", version}`, and `instructions` (a short paragraph on what the server is for and the ID conventions). No `Mcp-Session-Id` is issued: the server is stateless, which the 2025-xx revisions allow ("MAY assign a session ID") and the 2026-07-28 revision requires. |
| `notifications/initialized` | 202 Accepted, empty body. |
| `ping` | `{}`. |
| `tools/list` | The registry filtered by the token's scopes. No pagination needed (< 100 tools). |
| `tools/call` | Validates `name`, runs the tool, returns `CallToolResult`. API errors become `isError: true` results with the API error code and message, so the model can recover; protocol errors (unknown tool, malformed arguments) become JSON-RPC errors (`-32602`). |
| anything else | JSON-RPC `-32601` with HTTP 404 (as 2026-07-28 asks), including `resources/*` and `prompts/*`. |

Transport rules the handler implements:

- Responds with `Content-Type: application/json` only. The spec lets the server choose JSON or SSE per request;
  we never stream, so no servlet async support is needed and Spark's synchronous handler is enough.
- `GET /mcp` and `DELETE /mcp` → 405. `Mcp-Session-Id` and `Last-Event-ID` on requests are ignored.
- `MCP-Protocol-Version` header: accept `2025-03-26`, `2025-06-18`, `2025-11-25`; a missing header is treated
  as `2025-03-26` per spec; an unknown value → 400. In `initialize`, answer with the client's version when it is
  one of those, otherwise with `2025-11-25`.
- Body must be a single JSON-RPC object. Batches (arrays) are rejected with 400 (they were removed in
  2025-06-18).
- Origin check: if an `Origin` header is present and is not `https://<Config.domain>` (or `http://` when
  `useHTTP`), respond 403. Non-browser clients send no Origin, so this costs nothing and blocks DNS rebinding.
- `Accept` must list `application/json` (the spec also requires `text/event-stream`; we only check for JSON so
  simpler clients still work).

Forward compatibility with the 2026-07-28 revision ("modern" era: no `initialize`, protocol version and client
info in `params._meta` of every request, `server/discover`): Claude Code ≥ 2.1.232 probes for it and falls back
to the `initialize` handshake when the server answers with a 4xx that is not a recognised modern error. Our
server answers the probe with 404/-32601 (`server/discover` is unknown), which is the documented fallback
trigger, so the legacy path is used. Adding the modern era later is small: accept `_meta.io.modelcontextprotocol/
protocolVersion`, implement `server/discover`, mirror-header validation (`Mcp-Method`, `Mcp-Name`). Tracked as
a phase-3 item; not needed for the agent to work.

### 2.3 Why not the official MCP Java SDK

Evaluated `io.modelcontextprotocol.sdk:mcp` 2.0.0 (GA, tracks the 2025-11-25 spec):

- `mcp-core` declares `jakarta.servlet:jakarta.servlet-api:6.1.0` (provided). Its `HttpServletStreamableServerTransportProvider`
  and `HttpServletStatelessServerTransport` are compiled against Servlet 6.1; we run Servlet 5.0 inside Jetty 11
  through the patched Spark fork, and cannot upgrade Jetty without replacing Spark. The streamable provider also
  uses servlet async for SSE. Mounting it inside Spark would mean running an unsupported API combination.
- Hard dependencies on `reactor-core` 3.7 and Jackson (the `mcp` convenience artifact bundles Jackson 3;
  `mcp-json-jackson2` exists for Jackson 2). The project is Gson-only, and the README's stated goal is a smaller
  attack surface and fewer moving parts.
- JPMS: `mcp-core` ships an `Automatic-Module-Name`, Reactor and Jackson are modular, so it *would* load on the
  module path, but the transitive set roughly doubles `target/lib`.
- We need a strict subset of the protocol (no sampling, elicitation, resources, prompts, SSE, sessions, or
  server-initiated notifications). The subset is ~5 methods and ~500 lines including the JSON-RPC envelope.

Decision: hand-written handler. Revisit if we ever want SSE streaming, server-initiated notifications or the
modern-era protocol with less effort; at that point running the SDK's stdio transport in a separate process is
also an option (but a separate process would need its own DB access, which is why it is not the first choice).

### 2.4 Request flow for one tool call

1. `McpRoutes.post`: `mcp.enabled`? Origin OK? Parse bearer token (header only; a token in the body or query is
   rejected). Look it up with `AppsController.getAccessTokenOrNull` (LRU-cached), check `expiresAt`, load the
   account, check `isActive()`, record last use. Failure → HTTP 401 with `WWW-Authenticate: Bearer` and a JSON-RPC
   error body. This duplicates ~25 lines of `ApiRoutes.apiCall`; extract them into `ApiRoutes.authenticateToken`
   so both paths share one implementation.
2. Flood control: `FloodControl.API_REQUESTS` keyed by token (3/s). On violation → JSON-RPC error with
   HTTP 429. `tools/list` is called once per session by the client, so the limit is not an obstacle.
3. `McpServer.handle`: route by method.
4. `tools/call`: look up the tool, check `requiredScopes()` against the token (same rule as the dispatcher:
   `PASSWORD_GRANT_USED` means all), validate required arguments against the schema, map them to API
   parameter names, call `ApiDispatcher.doApiCall(method, ctx, new ApiCallContext(actx, params))`.
5. Serialize the API result with `ApiRoutes.gson`, post-process for the model (section 3.2), wrap in
   `CallToolResult`.
6. `ApiErrorException` → `{isError: true, content: [{type: "text", text: "<code> <message>"}], structuredContent:
   {error: {...}}}`. `CAPTCHA_NEEDED` and `TOO_MANY_SIMILAR_ACTIONS` are reported verbatim so the agent can
   stop retrying.

## 3. Tool surface

### 3.1 Principles

- One tool per user intent, named `verb_object` in snake_case, ≤ 40 characters (Claude Code drops tools whose
  full `mcp__<server>__<tool>` name exceeds 128 characters).
- Descriptions: first sentence says what it does and when to use it; put the words the model would search
  for at the start (Claude Code's tool search reads only the first 4,096 characters of an upfront-loaded tool's
  description and of the server instructions).
- Input schemas are flat objects, JSON Schema 2020-12, property names in `[A-Za-z0-9_.-]`, no root-level
  `anyOf`/`oneOf`. Paginated reads take `offset` and `count` with a server-side cap of 50 (the API allows up to
  200; the model does not need that, and tool results count against the agent's context).
- Annotations: `readOnlyHint: true` on every read; `destructiveHint: true` on delete/leave/remove; `idempotentHint`
  where true. The agent harness uses these for its permission prompts.
- Every write that creates content accepts `guid` and passes it through so a retried call does not double-post
  (the API already deduplicates on `guid`).
- Text is accepted as Markdown (`text_format=markdown` is forced), which is what the model writes naturally.

### 3.2 Result shaping

API responses are built for apps, not for a language model. The adapter does three things:

- `structuredContent` carries the API response unchanged (minus fields that are only useful to a UI, such as
  `ap_id`, image size variants, `post_source`).
- The `text` content block is a compact rendering: one line per item (`#1234 by @alice (user 7) 2026-10-20 14:02:
  "text…" [likes 3, comments 2]`), HTML stripped with the existing `TextProcessor.stripHTML(s, true)`. The
  model reads this; the structured copy is there for exact IDs.
- User and group references are expanded inline (`extended=true` on the API, then a lookup table in
  `structuredContent.profiles`), so the model does not need a second call to know who wrote something.

### 3.3 Phase 1 tools

Scopes in the last column are the API scopes the tool needs; a token without them does not see the tool.

**Identity and users**

| Tool | API method(s) | Scope |
|---|---|---|
| `get_me` | `users.get` (no ids) + `account.getCounters` | — |
| `get_users(ids | screen_names)` | `users.get`, `utils.resolveScreenName` | — |
| `search_users(q)` | `users.search` | — |
| `get_counters` | `account.getCounters` | — |

**Feed and notifications**

| Tool | API method(s) | Scope |
|---|---|---|
| `get_newsfeed(start_from, count, filters)` | `newsfeed.get` | newsfeed |
| `get_comments_feed(start_from, count)` | `newsfeed.getComments` | newsfeed |
| `get_notifications(count, max_id)` | `notifications.get` | notifications |
| `mark_notifications_viewed` | `notifications.markAsViewed` | notifications |

**Wall, comments, likes**

| Tool | API method(s) | Scope |
|---|---|---|
| `get_wall(owner_id, offset, count, filter)` | `wall.get` | wall:read |
| `get_posts(post_ids)` | `wall.getById` | wall:read |
| `get_post_comments(post_id, sort, offset, count)` | `wall.getComments` | wall:read |
| `create_post(owner_id?, message, content_warning?, guid?)` | `wall.post` (owner negative for a group) | wall:write |
| `create_comment(post_id, message, reply_to_comment?, guid?)` | `wall.createComment` | wall:write |
| `edit_post(post_id, message, content_warning?)` | `wall.edit` | wall:write |
| `delete_post(post_id)` | `wall.delete` | wall:write |
| `repost(post_id, message?)` | `wall.repost` | wall:write |
| `pin_post` / `unpin_post` | `wall.pin`, `wall.unpin` | wall:write |
| `like(type, item_id)` / `unlike` | `likes.add`, `likes.delete` | likes:write |
| `get_likes(type, item_id, offset, count)` | `likes.getList` | likes:read |

**Friends**

| Tool | API method(s) | Scope |
|---|---|---|
| `get_friends(user_id?, list_id?, offset, count)` | `friends.get` | friends:read |
| `get_online_friends` | `friends.getOnline` | friends:read |
| `get_mutual_friends(user_id)` | `friends.getMutual` | friends:read |
| `get_friend_requests(offset, count)` | `friends.getRequests` | friends:read |
| `add_friend(user_id, message?)` (also accepts a request) | `friends.add` | friends:write |
| `remove_friend(user_id)` (also declines a request) | `friends.delete` | friends:write |
| `get_friend_lists` | `friends.getLists` | friends:read |

**Groups and events**

| Tool | API method(s) | Scope |
|---|---|---|
| `get_my_groups(filter: all|admin|events)` | `groups.get` | groups:read |
| `get_groups(group_ids | screen_names)` | `groups.getById` | — |
| `search_groups(q, type: group|event)` | `groups.search` | — |
| `get_group_members(group_id, offset, count)` | `groups.getMembers` | groups:read |
| `join_group(group_id)` / `leave_group(group_id)` | `groups.join`, `groups.leave` | groups:write |
| `get_group_invites(type)` | `groups.getInvites` | groups:read |
| `invite_to_group(group_id, user_id)` | `groups.invite` | groups:write |
| `create_group(name, description, type, start_date?, finish_date?, place?)` | `groups.create` | groups:write |
| `edit_group(group_id, …)` | `groups.edit` | groups:write |

Posting to a group wall is `create_post` with a negative `owner_id`, as in the web UI.

**Private messages**

| Tool | API method(s) | Scope |
|---|---|---|
| `get_messages(folder: inbox|outbox, offset, count)` | `messages.get` | messages:read |
| `get_message_history(user_id, offset, count)` | `messages.getHistory` | messages:read |
| `get_messages_by_id(message_ids)` | `messages.getById` | messages:read |
| `send_message(to_user_ids, text, subject?, reply_to?, guid?)` | `messages.send` | messages:write |
| `mark_messages_read(message_ids)` | `messages.markAsRead` | messages:write |
| `delete_message(message_id)` / `restore_message` | `messages.delete`, `messages.restore` | messages:write |

**Misc**

| Tool | API method(s) | Scope |
|---|---|---|
| `get_server_info` | `server.getInfo`, `server.getAnnouncements` | — |
| `set_status(text)` / `get_status(user_id?)` | `status.set`, `status.get` | account / — |
| `block_user` / `unblock_user` / `get_blocked_users` | `account.banUser`, `unbanUser`, `getBannedUsers` | account |

Around 50 tools. With Claude Code's tool search enabled (the default), only names and the server instructions
load upfront, so the count is fine; for the Agent SDK with tool search off, 50 small schemas are still
well under the context budget.

### 3.4 Phase 2 tools

- **Photos and albums:** `get_albums`, `get_album_photos`, `get_photo`, `get_photo_comments`, `comment_photo`,
  `create_album`, `delete_photo`. **Uploads:** `upload_photo(album_id | for_attachment, base64 | file_url)`. The
  adapter calls `photos.getAttachmentUploadServer`, then performs the upload internally by calling the same code
  `ApiRoutes.uploadPhoto` uses (refactor it so the multipart handling is separate from the processing), and
  returns the attachment token to pass to `create_post(attachments=…)`. Base64 in a tool argument is capped at
  ~10 MB by `upload.max_image_size`; a `file_url` variant must only accept URLs on the server's own domain to
  avoid turning the server into an SSRF proxy. Video and audio uploads stay web-only.
- **Polls:** `create_post` gains a `poll` object (question, answers, multiple, anonymous, end_time) mapped to the
  `attachments` JSON the API expects; `vote_poll(poll_id, answer_ids)`, `get_poll_voters`.
- **Board:** `get_topics(group_id)`, `get_topic_comments`, `create_topic`, `comment_topic`, `edit/delete_topic_comment`.
- **Bookmarks:** `get_bookmarks(kind)`, `bookmark` / `unbookmark` (users, groups, posts, photos).
- **Profile:** `get_profile_info`, `update_profile(...)`, `get_privacy_settings`, `set_privacy_settings`.
- **Group management:** members' requests, bans, managers, links, settings (mirrors the rest of `groups.*`).
- **Feed controls:** `newsfeed.getFilters/addFilter/…`, `newsfeed.addBan`.
- **Reporting:** `report(type, id, reason, comment)` → `server.report`.

### 3.5 Generic escape hatch (optional)

`call_api(method, params)` exposing `ApiDispatcher` directly would give 100% coverage in one tool. It is
tempting for phase 1 but it makes the agent guess parameter names from the upstream docs, and it bypasses the
result shaping and the per-tool annotations the harness uses for permission prompts. Recommendation: do not
expose it by default; keep it behind `mcp.expose_generic_api=true` for development and for power users, with
`destructiveHint: true`.

## 4. Authentication

### 4.1 Requirements

- The agent runs headless. No browser, no interactive consent page at run time.
- The agent acts as one specific user; everything it does must be attributable to that user and revocable.
- Preference for a credential the user mints in Settings, with an expiry, over the account password, as long as
  this stays simple and does not open a new hole.
- Must work with how Claude Code and the Agent SDK pass credentials to remote MCP servers: a static
  `Authorization` header from `.mcp.json`, with `${VAR}` expansion from the environment. When an `Authorization`
  header is configured, Claude Code never attempts OAuth, so the server does not have to implement OAuth
  discovery for this use case.

### 4.2 Options considered

**A. Password grant (exists today, zero code).** `POST /oauth/token` with `grant_type=password`, `client_id`
of any registered app, `username`, `password` → a never-expiring token with all scopes. The container would
hold the account password and run the exchange at start-up.
Pros: nothing to build. Cons: the password sits in the container config; the token never expires and cannot be
scoped; needs an app registered just to have a `client_id`; subject to login rate limits (1/s, 100/h per IP), so
a fleet of containers behind one NAT would start failing; a leaked token is only noticed if the user looks at
"Activity history".

**B. Personal access tokens created in Settings (recommended).** A user opens Settings → Applications, picks
scopes and an expiry, and gets a token string shown once. Stored in the existing `api_tokens` table as a token
of a built-in system application. Sent as `Authorization: Bearer <token>`.
Pros: no password in the agent's environment; expiry and scopes chosen per token; revocation per token; reuses
the exact token validation, caching, last-use tracking, expiry sweeper and "end all sessions" behaviour the API
already has, so nothing new has to be trusted. Cons: ~400 lines of UI and routes; two new strings per
language; the user has to copy the token into the harness config.

**C. OAuth as the MCP spec wants it** (RFC 9728 protected resource metadata on `/.well-known/oauth-protected-resource`,
RFC 8414 authorization server metadata, Client ID Metadata Documents or dynamic client registration, resource
indicators, PKCE). This is what `claude mcp login` and claude.ai connectors use. It requires a browser for the
user to consent, so it does not solve the headless case on its own: the user would run the login once on their
laptop and copy the resulting token files into the container, which is more fragile than option B. The server
side is also a real project (metadata endpoints, CIMD fetching with SSRF protection, audience-bound tokens,
refresh tokens, which the current `/oauth/token` does not issue). Worth doing later for interactive clients;
not for phase 1. Our existing `/oauth/*` endpoints stay as they are.

**D. Device authorization grant (RFC 8628).** Headless-friendly in principle (the agent prints a code, the user
approves it in the browser), but MCP clients do not implement it, and someone must watch the agent's output.
Rejected.

**E. Server-side API key per account in `config.properties` or an admin page.** Rejected: it bypasses the
user's own control and breaks the "the user did this" attribution.

### 4.3 Design of option B

Data model: one new nullable column, `api_tokens.name varchar(64)`, for the label the user gives a token (NULL
for OAuth tokens). Everything else reuses existing tables.

- Add a built-in application row to `api_applications` in the next schema migration (`DatabaseSchemaUpdater`,
  `SCHEMA_VERSION` 95) and to `schema.sql`: `id` fixed (e.g. 1 when the table is empty, otherwise the next ID
  stored in the `config` table as `AgentAppID`), `username='agents'` so it cannot be registered by a user,
  `name="AI agents"`, `type=STANDALONE`, `developer_id=NULL`. `AppsController.getAgentApp()` returns it.
- A personal token is an `api_tokens` row for that app: `permissions` = chosen scopes, `expires_at` =
  now + chosen lifetime (never `NULL`), `ip`/`user_agent` = the browser that created it. The existing
  `createAccessToken` does exactly this once it accepts an explicit expiry; add an overload rather than
  touching the OAuth path.
- The token string stays what `AppAccessToken.getEncodedID()` produces (base64url of 64 random bytes, 86
  characters), so `ApiRoutes.apiCall` and the MCP endpoint parse it identically. Consequence: an agent token
  also works on `/api/method/*` with its scopes, which is fine and useful for debugging with `curl`.
- Lifetimes offered in the UI: 1 day, 7 days, 30 days, 90 days, 1 year. Default 30 days. No "never": the
  user's requirement is an expirable token; a long-running deployment re-mints before expiry.
- Scopes in the UI: the same checkboxes as the OAuth consent page (`oauth_prompt.twig`), all read scopes
  checked by default, write scopes unchecked. The `offline` scope is not offered (expiry is explicit).

Routes (`SettingsRoutes`, under the existing `path("/settings/apps")`):

| Route | Purpose |
|---|---|
| `GET /settings/apps` | existing page, plus a new section "Access tokens for agents" listing the user's tokens of the agent app: created, expires, last used, IP, scopes, a "revoke" link. |
| `GET /settings/apps/tokens/create` | form: a label ("laptop agent", "nightly digest"), scopes, lifetime, current password. |
| `POST /settings/apps/tokens/create` (CSRF) | verifies the password with `UsersController.checkPassword` (already flood-controlled, 5/min), creates the token, renders it once in a page with a copy button and the ready-to-paste `.mcp.json` snippet. |
| `GET /settings/apps/tokens/:id/revoke?csrf=` | `AppsController.revokeAccessToken(id)`; the `:id` is the 32-bit prefix already used for sessions (`OtherSession.id`) resolved through the user's own token list, never a raw token. |

Storage additions in `AppsStorage`: `getUserAppAccessTokensDetailed(accountID, appID)` returning id prefix,
created, expires, last active, IP, user agent string, permissions; the query is a copy of the one behind
`SessionStorage.getAccountSessions` restricted to one app.

Strings: `settings_agent_tokens`, `settings_agent_tokens_empty`, `settings_agent_token_create`, labels for
scopes and lifetimes, the "copy it now, it will not be shown again" warning, in `langs/en/settings.json` and
`langs/ru/settings.json` (other languages fall back to English through `Lang`).

Agent-side configuration (project `.mcp.json`, or `--mcp-config` for the Agent SDK):

```json
{
  "mcpServers": {
    "tusovochka": {
      "type": "http",
      "url": "https://social.example.com/mcp",
      "headers": { "Authorization": "Bearer ${TUSOVOCHKA_TOKEN}" }
    }
  }
}
```

`TUSOVOCHKA_TOKEN` is injected into the container as a secret. Do not name the variable `ANTHROPIC_*`,
`AWS_BEARER_TOKEN_BEDROCK`, `HTTPS_PROXY` or `NPM_TOKEN`: Claude Code refuses to expand those into headers and
sends an empty bearer. In headless runs use `--strict-mcp-config --mcp-config <file>` so only this server is
loaded.

Why this adds no new hole:

- The token has the same entropy, storage, lookup, cache and revocation path as every OAuth token the server
  issues today; the only new code on the trust boundary is the creation form, which is CSRF-protected,
  requires the session's account, and re-checks the password.
- Scopes are enforced by `ApiDispatcher` for every underlying call, not by the tool layer.
- Password change and "end all other sessions" already revoke all API tokens; this stays true.
- The token is never written to logs: the MCP handler must not log request headers, and `DebugLog` output is
  reviewed for the `/mcp` path before merge.
- Tokens are stored unhashed, like the existing API tokens and web session IDs. Hashing them (SHA-256 at rest,
  lookup by hash) would be a worthwhile hardening for *all* tokens but changes the existing table and cache; it
  is listed as a follow-up, not a prerequisite.

### 4.4 Password grant as the fallback

Nothing to build: with any registered app, a start-up script can obtain a token with `grant_type=password` and
export it. Document it in the README as the way to get going before the Settings UI lands, with the caveats
from 4.2 A. It stops being needed once option B ships.

## 5. Security considerations

- **Private servers.** `/mcp` is whitelisted in `isAllowedForAnonymousUsers` only so that it can return a
  proper 401; without a valid token no tool is listed or callable, and `ApiDispatcher` additionally refuses
  all content methods on a private server without a token.
- **Rate limits.** Existing `API_REQUESTS` (3/s per token) and `POSTS` (300 per 3h) apply unchanged. An agent
  loop that hits 429 gets an `isError` result it can back off from. Consider a separate, lower bucket for
  write tools (for example 60 writes/hour per token) configurable in `config.properties`; the harness has no
  notion of "too chatty".
- **Captcha.** The API may require a captcha for posting (`CAPTCHA_NEEDED`). An agent cannot solve one; the tool
  returns the error as-is and the server admin can disable the captcha requirement for API posting or whitelist
  the agent app. Decide at rollout; do not special-case in code.
- **Prompt injection.** Every post, comment and message the tools return is text written by other users and
  reaches the model as data. The server cannot fix that, but the `instructions` string tells the model that
  tool results are user content, and the text rendering (3.2) keeps content inside quoted fields so it is not
  confused with structure. Destructive tools carry `destructiveHint` so the harness can prompt before acting.
- **Origin / DNS rebinding.** Section 2.2.
- **No tokens in URLs.** The MCP endpoint accepts the bearer header only, unlike `/api/method/*` which also
  accepts `access_token` as a parameter. The 2026-07-28 spec forbids query-string tokens.
- **Audit.** Token last-use (IP, user agent) is already recorded; the agent harness sends a distinctive user
  agent, so "Activity history" shows where the agent runs. Optionally log tool name + token ID at INFO for
  write tools.
- **Scope of exposure.** No admin or moderation tools. Nothing the token's user could not already do through
  the web UI.
- **Output size.** Count caps (50) and text truncation (posts longer than ~4,000 characters in the text block
  are cut with a marker; the full text stays in `structuredContent`) keep single results under Claude Code's
  default 25,000-token result limit.

## 6. Rollout and testing

### 6.1 Phases

1. **Phase 1 (one PR each, in this order):**
   1. `ApiRoutes.authenticateToken` extraction + `AppsController.createAccessToken` overload with explicit
      expiry + the built-in agent app migration. Pure refactor plus one migration; existing API behaviour
      unchanged, covered by hitting `/api/method/users.get` before and after.
   2. Settings UI for agent tokens (4.3).
   3. `smithereen.mcp` protocol layer (`McpRoutes`, `McpServer`, `ToolRegistry`, `JsonSchema`, `ToolResult`) with
      `get_me` only, plus `mcp.enabled`.
   4. Tool families, one PR per family, in the order of section 3.3.
2. **Phase 2:** section 3.4, uploads last.
3. **Phase 3 (optional):** modern-era protocol (2026-07-28), OAuth discovery for interactive clients, token
   hashing at rest.

### 6.2 Tests

- Unit tests (JUnit 5, already set up): `McpServer` with a fake tool registry: initialize negotiation for each
  protocol version, unknown method → -32601, malformed JSON → -32700, notification → 202, tools/list filtering by
  scopes, tools/call argument validation, API error → `isError` result. `JsonSchema` output is checked against
  the constraints Claude Code enforces (property-name charset, no root combinators).
- Local end-to-end (the `TESTS.md` setup with the seeder):
  1. create a token in Settings as `yulia`;
  2. `curl -X POST http://127.0.0.1:4567/mcp -H "Authorization: Bearer $T" -H "Accept: application/json, text/event-stream" -H "MCP-Protocol-Version: 2025-11-25" -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'`;
  3. `tools/list`, then `get_newsfeed`, `send_message` to `maria`, verify in the web UI as `maria`;
  4. `claude mcp add --transport http tusovochka http://127.0.0.1:4567/mcp --header "Authorization: Bearer $T"`
     and ask Claude Code to post on the wall; also run the MCP Inspector against the endpoint;
  5. revoke the token in Settings and confirm 401; let a 1-day token expire (set the clock, or insert a row
     with a past `expires_at`) and confirm the hourly sweeper removes it and the endpoint answers 401 before
     that.
- A `dev/mcp-smoke.sh` script with the curl calls above, in the spirit of `dev/regression.js`.

### 6.3 Documentation

- README: a "Using with AI agents (MCP)" section: how to mint a token, the `.mcp.json` snippet, the scope list,
  the note about private servers and captcha.
- `examples/config.properties`: `mcp.enabled` and any write rate limit added in section 5.

## 7. Open questions

1. Should agent tokens show in Settings → Applications (next to OAuth grants, as proposed) or under
   "Activity history"? They already appear in the latter as sessions of the "AI agents" app; the proposal puts
   management in the former because that page is about third-party access.
2. Default scope set for a new token: all read scopes + messages:write + wall:write, or all read only? The user
   story ("write comments, posts, messages") suggests writes on by default; the safer default is reads only.
3. Write rate limit for agents (section 5): value, and whether per token or per account.
4. Captcha for API posting when the caller is an agent token: keep, or skip for tokens of the agent app?
