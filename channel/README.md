# Tusovochka channel for Claude Code

Pushes your Tusovochka notifications into a running Claude Code session as they happen: private messages,
comments and replies, mentions, wall posts, friend requests, group and event invites, likes, reposts, photo tags.
Claude sees each one as a `<channel>` event and can answer it with the tools of the `tusovochka` MCP server
(`send_message`, `create_comment`, …). This is a [Claude Code channel](https://code.claude.com/docs/en/channels-reference);
the design notes are in section 8 of [docs/mcp-server-spec.md](../docs/mcp-server-spec.md).

```
Tusovochka ──/mcp/events (WebSocket)──► tusovochka-channel.mjs ──stdio──► Claude Code
     ▲                                                                      │
     └──────────────────── /mcp tools (send_message, create_comment…) ◄─────┘
```

## Limits

- Channels are a Claude Code research preview. A self-made channel is not on the approved list, so every session
  has to be started with `--dangerously-load-development-channels`, and it only works in an interactive session:
  `claude -p` and the Agent SDK ignore the flag. On Team and Enterprise plans an admin must enable channels first.
- The bridge needs Node 22 or newer (or Bun) on the machine where Claude Code runs.
- Events are not stored: what happens while the session is closed or the connection is down is not replayed.
  After a reconnect the bridge tells Claude how long the gap was, and Claude catches up with `get_notifications`
  and `get_messages`.
- No permission relay: tool approval prompts stay in the terminal.

## Setup

1. Create an agent token in Settings → Applications → "Access tokens for AI agents". It needs the
   `notifications` scope for the stream, `messages:read` to receive private messages, and the write scopes for
   whatever Claude should be able to answer with. Revoking the token ends the stream.
2. Install the bridge's one dependency:
   ```sh
   cd channel && npm install
   ```
3. Add both servers to the `.mcp.json` of the project you run Claude in, with the absolute path to the bridge:
   ```json
   {
     "mcpServers": {
       "tusovochka": {
         "type": "http",
         "url": "https://<your domain>/mcp",
         "headers": { "Authorization": "Bearer ${TUSOVOCHKA_TOKEN}" }
       },
       "tusovochka-events": {
         "command": "node",
         "args": ["/path/to/Tusovochka/channel/tusovochka-channel.mjs"],
         "env": { "TUSOVOCHKA_URL": "https://<your domain>", "TUSOVOCHKA_TOKEN": "${TUSOVOCHKA_TOKEN}" }
       }
     }
   }
   ```
4. Start Claude Code with the channel enabled:
   ```sh
   export TUSOVOCHKA_TOKEN=<token>
   claude --dangerously-load-development-channels server:tusovochka-events
   ```
   Accept the two MCP servers and the development-channel warning. `/mcp` should list both as connected. To let
   Claude answer without asking each time, add `--allowedTools 'mcp__tusovochka__*'`, or allow only the tools you
   want it to use on its own, e.g. `mcp__tusovochka__send_message`.

## What Claude sees

A private message arrives like this:

```text
<channel source="tusovochka-events" kind="mail_message" from_id="2" from_name="Yulia Ivanova" from_username="yulia"
         message_id="i84ZzaSM_80" id="msgi84ZzaSM_80" url="https://<your domain>/my/mail/messages/i84ZzaSM_80">
Yulia Ivanova sent you a private message

Are you coming to the **party** tonight?
</channel>
```

The body is a one-line summary, then what the person wrote, in Markdown. The attributes carry the IDs in the
conventions of the MCP tools: `message_id` for `send_message(reply_to=…)`; `post_id`, plus `comment_id` and
`reply_to_comment` for comments, for `create_comment(post_id, reply_to_comment=comment_id)`; `owner_id`,
`photo_id`, `topic_id`, `group_id`. `kind` is one of `mail_message`, `reply`, `mention`, `wall_post`, `like`,
`repost`, `friend_request`, `friend_request_accepted`, `follow`, `group_invite`, `event_invite`,
`group_request_accepted`, `photo_tag`, `invite_signup`, `export_ready`, and `connection` for messages from the
bridge itself (a reconnect after a gap, or the server ending the stream because the token was revoked).

## The event stream

`wss://<your domain>/mcp/events`, with `Authorization: Bearer <token>`. The server answers the upgrade with 401
for a missing or invalid token, 403 without the `notifications` scope or with a foreign `Origin`, and 404 when
`mcp.enabled=false`. After the upgrade it sends `{"type":"hello","user_id":…,"name":…}`, then one
`{"type":"event","event":{…}}` per notification; the event object holds `summary`, `text` and the attributes above.
The client must send something (any text) at least every 90 seconds; the bridge sends `ping` every 30. When the
token is revoked or expires, the server closes the stream with code 4001 at the next event, and the bridge stops.
