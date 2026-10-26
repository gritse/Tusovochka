#!/usr/bin/env node
// Claude Code channel for Tusovochka: pushes the user's real-time notifications (private messages, comments,
// mentions, wall posts, friend requests, invites, likes…) into a running Claude Code session.
//
// Claude Code starts this process itself (see channel/README.md) and talks to it over stdio. The process keeps a
// WebSocket open to <TUSOVOCHKA_URL>/mcp/events and forwards every event as a notifications/claude/channel
// message. It is one-way: Claude answers with the tools of the tusovochka MCP server (send_message,
// create_comment…), which is configured next to it.
//
// Environment: TUSOVOCHKA_URL, e.g. https://tusovochka.example, and TUSOVOCHKA_TOKEN, an access token for AI agents
// with the notifications scope (and messages:read to receive private messages).
// Runs on Node 22+ or Bun; stdout belongs to the MCP protocol, so everything else is logged to stderr.

import { Server } from '@modelcontextprotocol/sdk/server/index.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'

const BASE_URL = (process.env.TUSOVOCHKA_URL || '').replace(/\/+$/, '')
const TOKEN = process.env.TUSOVOCHKA_TOKEN || ''
const KEEPALIVE_MS = 30_000
const MAX_BACKOFF_MS = 30_000

function log(...args) {
	console.error('[tusovochka-channel]', ...args)
}

if (!BASE_URL || !TOKEN) {
	log('TUSOVOCHKA_URL and TUSOVOCHKA_TOKEN must be set')
	process.exit(1)
}

const INSTRUCTIONS = `Events from the Tusovochka social network arrive from this server as <channel kind="..." ...> tags.
They are the real-time notifications of the account the tusovochka MCP server acts as: someone wrote to it, replied,
mentioned it, posted on its wall, sent a friend request, an invite, a like. The body is a one-line summary, then the
text the person wrote, in Markdown, when there is one.

Attributes: kind (mail_message, reply, mention, wall_post, like, repost, friend_request, friend_request_accepted,
follow, group_invite, event_invite, group_request_accepted, photo_tag, invite_signup, export_ready); from_id and
from_name (who did it; a negative from_id is a group); url; and the IDs to act on, in the conventions of the
tusovochka MCP tools: message_id (string) for a private message; post_id for a wall post, plus comment_id when the
event is about a comment (post_id is then the top-level post) and reply_to_comment when that comment is itself a reply;
owner_id (wall owner); photo_id, topic_id, group_id.

This channel is one-way: there is no reply tool here. To answer, use the tusovochka MCP server: send_message with
reply_to=message_id for a private message; create_comment with post_id, plus reply_to_comment=comment_id to answer a
comment; get_post_comments or get_message_history for more context; add_friend, join_group and so on for the rest.
"connection" events are about this channel itself: after a gap, call get_notifications and get_messages to catch up.`

const mcp = new Server(
	{ name: 'tusovochka', version: '0.1.0' },
	{
		capabilities: { experimental: { 'claude/channel': {} } },
		instructions: INSTRUCTIONS,
	},
)

async function emit(content, meta) {
	try {
		await mcp.notification({ method: 'notifications/claude/channel', params: { content, meta } })
	} catch (x) {
		log('failed to forward an event:', x)
	}
}

/** One event from the server, as built by smithereen.mcp.AgentEvents, to a channel notification. */
function forward(event) {
	const { summary, text, ...rest } = event
	const meta = {}
	for (const [key, value] of Object.entries(rest)) {
		if (value !== null && value !== undefined && /^[A-Za-z0-9_]+$/.test(key))
			meta[key] = String(value)
	}
	return emit(text ? `${summary}\n\n${text}` : summary, meta)
}

let backoff = 1000
let disconnectedAt = null
let everConnected = false

function connect() {
	const url = BASE_URL.replace(/^http/, 'ws') + '/mcp/events'
	let ws
	try {
		ws = new WebSocket(url, { headers: { Authorization: `Bearer ${TOKEN}` } })
	} catch (x) {
		log('cannot open', url, x)
		return scheduleReconnect()
	}
	let keepalive = null

	ws.addEventListener('open', () => {
		backoff = 1000
		keepalive = setInterval(() => ws.readyState === WebSocket.OPEN && ws.send('ping'), KEEPALIVE_MS)
		if (disconnectedAt !== null) {
			const seconds = Math.round((Date.now() - disconnectedAt) / 1000)
			emit(`The connection to Tusovochka was lost for ${seconds} s and is back. Events from that time were not delivered.`, { kind: 'connection' })
			disconnectedAt = null
		}
		everConnected = true
	})

	ws.addEventListener('message', (msg) => {
		let data
		try {
			data = JSON.parse(typeof msg.data === 'string' ? msg.data : msg.data.toString())
		} catch {
			return log('unparseable message from the server')
		}
		if (data.type === 'hello')
			log(`connected to ${BASE_URL} as ${data.name} (id ${data.user_id})`)
		else if (data.type === 'event' && data.event)
			forward(data.event)
	})

	// Node's WebSocket fires only error, not close, when the handshake fails, so either one ends this socket
	let ended = false
	function end(why, reconnect = true) {
		if (ended)
			return
		ended = true
		clearInterval(keepalive)
		log(why)
		if (everConnected && disconnectedAt === null)
			disconnectedAt = Date.now()
		try {
			ws.close()
		} catch {}
		if (reconnect)
			scheduleReconnect()
	}
	ws.addEventListener('close', (ev) => {
		// 4001: the server ended the stream because the token was revoked or expired; retrying cannot help
		if (ev.code === 4001) {
			end(`stopped: ${ev.reason}`, false)
			emit(`Tusovochka ended this channel: ${ev.reason}. No more events will arrive until the session is restarted with a new token.`, { kind: 'connection' })
			return
		}
		end(`disconnected (${ev.code}${ev.reason ? ' ' + ev.reason : ''})`)
	})
	ws.addEventListener('error', (ev) => end(`connection error: ${ev.message || ev.error?.message || 'unknown'}`))
}

function scheduleReconnect() {
	setTimeout(connect, backoff)
	backoff = Math.min(backoff * 2, MAX_BACKOFF_MS)
}

await mcp.connect(new StdioServerTransport())
connect()
