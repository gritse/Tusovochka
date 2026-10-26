#!/bin/bash
# Smoke test for the MCP endpoint against a running server, in the spirit of regression.js.
#
# Usage: dev/mcp-smoke.sh <agent token> [base url]
#
# The token is created in Settings > Applications > "Access tokens for AI agents"; see TESTS.md for the local
# setup. Needs curl and python3. Every call sleeps a little, because the API allows 3 requests per second per token.

set -u
TOKEN=${1:?usage: dev/mcp-smoke.sh <agent token> [base url]}
BASE=${2:-http://127.0.0.1:4567}
FAILED=0

mcp() { # mcp <json-rpc body> -> prints "<http status> <body>"
	sleep 0.5
	curl -s -w ' %{http_code}' -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
		-H "Accept: application/json, text/event-stream" -H "MCP-Protocol-Version: 2025-11-25" -d "$1" "$BASE/mcp"
}

call() { # call <tool> <json arguments> -> prints the tool's text block, or "ERROR ..." for an error result
	mcp "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":${2:-{\}}}}" | sed 's/ [0-9]*$//' | python3 -c '
import json, sys
d = json.load(sys.stdin)
if "error" in d:
	print("JSONRPC ERROR", d["error"]); sys.exit(0)
r = d["result"]
print(("ERROR " if r.get("isError") else "") + r["content"][0]["text"])'
}

check() { # check <description> <expected substring> <actual>
	if [[ "$3" == *"$2"* ]]; then
		echo "ok   $1"
	else
		echo "FAIL $1: expected '$2' in: ${3:0:300}"
		FAILED=1
	fi
}

echo "== transport"
check "GET is not allowed" "405" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/mcp")"
check "no token is a 401" "401" "$(curl -s -o /dev/null -w '%{http_code}' -d '{"jsonrpc":"2.0","id":1,"method":"ping"}' "$BASE/mcp")"
check "foreign Origin is a 403" "403" "$(sleep 0.5; curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" -H "Origin: https://evil.example" -d '{"jsonrpc":"2.0","id":1,"method":"ping"}' "$BASE/mcp")"
check "initialize" '"protocolVersion":"2025-11-25"' "$(mcp '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}')"
check "initialized notification is a 202" " 202" "$(mcp '{"jsonrpc":"2.0","method":"notifications/initialized"}')"
check "ping" '"result":{}' "$(mcp '{"jsonrpc":"2.0","id":2,"method":"ping"}')"
check "unknown method" '"code":-32601' "$(mcp '{"jsonrpc":"2.0","id":3,"method":"resources/list"}')"
check "tools/list" '"name":"get_me"' "$(mcp '{"jsonrpc":"2.0","id":4,"method":"tools/list"}')"

echo "== tools"
ME=$(call get_me)
check "get_me" '"screen_name"' "$ME"
check "get_server_info" '"domain"' "$(call get_server_info)"
check "get_newsfeed" '"items"' "$(call get_newsfeed '{"count":3}')"
check "get_notifications" '"items"' "$(call get_notifications)"
check "unknown tool" "-32602" "$(call no_such_tool)"
check "missing argument" "missing required arguments" "$(call get_posts)"
GUID="smoke-$(date +%s)"
POST=$(call create_post "{\"message\":\"Smoke test post\",\"guid\":\"$GUID\"}")
check "create_post returns an id" "" "$POST"
[[ "$POST" =~ ^[0-9]+$ ]] || { echo "FAIL create_post: $POST"; FAILED=1; }
if [[ "$POST" =~ ^[0-9]+$ ]]; then
	check "create_post with the same guid is idempotent" "$POST" "$(call create_post "{\"message\":\"Smoke test post\",\"guid\":\"$GUID\"}")"
	check "get_posts" '"text": "Smoke test post"' "$(call get_posts "{\"post_ids\":[$POST]}")"
	COMMENT=$(call create_comment "{\"post_id\":$POST,\"message\":\"Smoke test comment\"}")
	check "get_post_comments" '"text": "Smoke test comment"' "$(call get_post_comments "{\"post_id\":$POST}")"
	check "delete_post (comment)" "true" "$(call delete_post "{\"post_id\":$COMMENT}")"
	check "delete_post" "true" "$(call delete_post "{\"post_id\":$POST}")"
fi

if [ $FAILED -eq 0 ]; then
	echo "all checks passed"
else
	echo "some checks FAILED"
	exit 1
fi
