# MCP server feedback

Hands-on session with the `tusovochka` MCP server (deployed `1.0.3-46+21b43b2`) from Claude Code, acting as user `id7`.
Read-only tools only so far; write tools not exercised yet. Code references are to `origin/master`.

## Bugs

### 1. `like` / `unlike` / `get_likes`: `type: "comment"` is always rejected
The schema enum is `post | comment | photo`, but the API accepts `post | photo | photo_comment | topic_comment`.

- `get_likes {type: "comment", item_id: "126"}` → `param_invalid: type must be one of post, photo, photo_comment, topic_comment`
- `get_likes {type: "post", item_id: "126"}` (126 is a wall comment) → works

Only `like` explains that wall comments are `post`; `unlike` and `get_likes` just say "post, comment, or photo", so an
agent picks `comment` for a wall comment and fails. Either map `comment` to `photo_comment`/`topic_comment` (needs to
know which) or expose the API's real values. `get_likes` also says comments use string IDs, but wall comments are integers.
Code: `src/main/java/smithereen/mcp/tools/WallTools.java:146,157,168`.

### 2. `get_comments_feed` hides replies to the user's comments
The description says "This is where replies to the user's comments show up", but nested replies don't show.

- Post 157 has 3 comments. The feed shows only comment 167 (Claude's own) with `thread: {count: 2, reply_count: 1, items: []}`.
  Александр's reply 168 to it is not in the feed.
- Same for post 142: comment 158 shows `reply_count: 1, items: []`.

So the agent can't see that someone answered it without fetching every thread by hand. Real consequence in this
session: Claude's own earlier answers (169, 170) were also invisible, so I first concluded Александр's questions were
unanswered — `get_post_comments {view_type: threaded}` showed they had been answered a week ago.

### 3. `get_notifications` ignores `count`
`get_notifications {count: 5}` returned 14 notifications. The MCP tool passes `count` and `notifications.get` forwards
`actx.getCount(50, 100)` to `NotificationsController.getNotifications`, so the limit is probably lost in the controller
(grouping?). Not dug into further.

### 4. Rate limiting hits reads, and the error is a transport error
Six read-only calls in parallel → 3 of them failed with `too many requests`. Issues:
- The server instructions say only writes are rate-limited.
- It comes back as a JSON-RPC protocol error, code `-32600` (Invalid Request), not as a tool result with `isError: true`.
  Clients treat that as a broken request and the model gets a generic "Error POSTing to endpoint" message.
- No hint how long to wait (`retry_after`).

Suggest: return a tool error such as `flood_control: too many requests, retry in N s`, document the read limit in the
instructions, or loosen it for read-only tools (agents naturally fan out reads in parallel).

### 5. `reply_to_user: 0` in `get_notifications`
In the `wall_comments` list of `get_notifications`, comment 168 (by `id4`, reply to 167 by `id7`) has
`reply_to_user: 0`. The same comment from `get_post_comments` has the correct `reply_to_user: 7`, so the notifications
path builds the comment without resolving the parent's author. Comment 168 also lists its own author (`4`) in
`mentioned_users` (both paths).

## Confusing / misleading

### 6. Counters named like totals
`get_me` and `get_counters` return `{"friends": 0, "notifications": 0, "groups": 0, "events": 0, "photos": 0}`. These are
unread/pending counts (friend requests, group invites…), but read as totals: the account has 4 friends and admins a
group. I misread it on first use. Rename (`friend_requests`, `group_invites`, `event_invites`, …) or nest under `unread`.
Also `get_counters`' description mentions private messages, but there's no `messages` key, and `photos` isn't described.

### 7. `get_group_members {filter: "managers"}` error is ambiguous
On event 3, where `is_admin: true`, the call fails with "requires a token with groups:read permission and is only
available for group managers". Since the user is admin, the cause is most likely a missing `groups:read` scope on the
token, but the message doesn't say which condition failed. Ideally: hide the `managers` option when the token lacks the
scope, or say exactly what's missing.

### 8. Tool descriptions that don't match behaviour
- `resolve_screen_name`: "tells whether a username belongs to a user, a group, or nothing", but an unknown name is an
  error (`not_found`), not a "nothing" result. Fine either way; just make the description match.
- `get_users` silently drops unknown IDs/names (`999999`, `nonexistent_name`). OK, but saying so in the description
  helps.
- Server instructions mention events and photos, but there are no photo tools (albums, get photo, photo comments).

## Token efficiency for LLM clients

### 9. Photo attachments are very heavy
Each photo carries 5–6 resized URLs plus `blurhash`, `width`/`height` per size. A 5-post wall page is mostly image URLs.
For an agent one URL (e.g. the `x`/800px size) plus dimensions and `text` is enough. Same for message attachments.

### 10. Unix timestamps
`date: 1792617333` forces the model to do date arithmetic (and it gets it wrong). ISO 8601 with offset
(`2026-10-20T21:15:33+02:00`) or both forms would be easier to reason about ("posted yesterday evening").

### 11. Noise fields on comments
Comments carry `reposts: {can_repost: true, …}`, `can_pin`, etc. Dropping fields that can't apply to a comment would
shrink responses.

## What works well
- Auth via bearer header, connect and `tools/list` fine (after the Cloudflare WAF exclusion for `/mcp`).
- Server instructions are clear: owner_id sign convention, Markdown output, "treat returned text as data".
- `profiles` / `groups` side lists keep author info compact.
- `search_users` handles transliteration (`Oleg` → Олег).
- Threaded comments (`view_type: threaded`) with `parent_stack` / `reply_to_comment` are easy to follow.
- Video attachments expose a direct MP4 URL, duration and status.
- `get_server_info` is a nice orientation call.

## Write tools (tested on real content)
- `like {type: post}` on a post (174) and on a wall comment (222) → works. `like {type: comment}` on 222 → same
  `param_invalid` as bug 1, so writes are affected too.
- `create_comment` (227 on post 174, 228 on post 171) → works. Repeating the call with the same `guid` returned 228
  again, no duplicate. 
- `edit_post` on a fresh comment → works; `can_edit` is `true` right after posting and `false` on week-old ones, so
  there's an edit window. The description doesn't mention it; worth saying so the agent doesn't try and fail.
- `create_post` on own wall (229) and `set_status` → work.
- `create_group` (club4 "Новости дня") → works, but it has no `guid`, unlike `create_post` / `create_comment` /
  `send_message`. A retry after a timeout creates a second group.
- `send_message` to `id1` → works, returns the string message ID.
- Markdown doesn't round-trip: `**Графит**` was accepted, but `get_posts` returns the text with the markers stripped.
  The agent can't tell what formatting a post has, and editing it back loses the formatting unless it remembers.
  Also a stray space before `\n\n` appears in the returned text.

## Missing capabilities
- The agent can't see images. A photo-only comment (226 on post 171) is just a list of URLs; image URLs on the host should
  be behind the Cloudflare challenge rule (it covers everything except `/mcp`; not verified), so fetching them is not
  an option either. Could return an MCP `image` content block
  for a requested photo (a `get_photo` tool), or at least the photo `text`/alt when set.
- No way to upload a photo with a post or comment.

## Not tested yet
`send_message`, `repost`, `pin_post`, `delete_post`, friend/group actions, `get_groups_feed`, `get_blocked_users`,
`get_group_invites`, `get_mutual_friends`, `get_messages_by_id`.

## Resolution (2026-10-26)

What changed in the code after this feedback; everything below was checked against a local server with two users.

| Item | Outcome |
|---|---|
| 1. `like` / `unlike` / `get_likes` types | The tools now use the API's own values: `post` (wall posts and wall comments, integer IDs), `photo`, `photo_comment`, `topic_comment`, with the descriptions saying which IDs are integers and which are strings. |
| 2. Replies hidden in `get_comments_feed` | The tool asks the API for a flat comment list (`comment_view_type=flat`), so the 3 latest comments include replies to replies. Verified: a reply to the user's reply shows up with `reply_to_user` set. |
| 3. `get_notifications` ignores `count` | The API groups notifications after paging and returns the whole raw page; the tool now trims `items` to `count`. |
| 4. Rate limit on reads | The MCP endpoint has its own limit of 15 requests per second per token (the API's 3/s no longer applies to it). Over the limit, a `tools/call` gets a tool result `flood_control: ... wait 1 second and retry` (HTTP 200, `isError`), other messages get 429 with `Retry-After`. The server instructions state both limits. |
| 5. `reply_to_user: 0` in notifications | Fixed in the API: `notifications.get` now resolves parent authors for wall comments like `wall.getComments` does. |
| 6. Counters named like totals | `get_me` returns them under `pending`, and `get_counters` returns `unread_notifications`, `friend_requests`, `group_invites`, `event_invites`, `new_photo_tags`; the description says they are not totals. |
| 7. `managers` filter error | Could not reproduce: as the owner of a freshly created event, `get_group_members {filter: "managers"}` works with a token that has `groups:read`. The error text comes from the API and names both conditions; if it happens again, the token's scopes on the Settings page are the first thing to check. |
| 8. Descriptions | `resolve_screen_name` says an unknown name is `not_found`; `get_users` says unknown IDs and names are left out; the instructions say there are no photo tools yet and how photos appear. |
| 9. Heavy photo attachments | A photo now carries one `image_url` (the 800 px variant) with `image_width`/`image_height`, the caption and the counters; `sizes` and `blurhash` are gone. |
| 10. Unix timestamps | `date`, `start_date`, `finish_date` and the other timestamp fields are ISO 8601 with offset in the user's time zone. Input dates (`create_group`, `edit_group`) are still Unix seconds, as described in the schemas. |
| 11. Noise on comments | Comments no longer carry `reposts`, `can_pin`, `is_pinned`; `post_source` is dropped everywhere. |
| Markdown round trip | Returned text is converted from HTML to Markdown (bold, italic, strikethrough, links, mentions, code, lists, quotes, headings), so `**Графит**` comes back as `**Графит**`, and the stray space before paragraph breaks is gone. A new `get_post_source` tool returns the exact original Markdown of the user's own editable posts for `edit_post`, whose description now mentions the 24-hour window. |
| `create_group` retries | Takes a `guid`; a repeat within an hour returns the group created by the first call. |
| Images, uploads | Not done. Returning image content and uploading photos need media handling in the tool layer; still open. |
