# Guillotine Plugin Protocol (MCP)

Guillotine's editor is a **standard [Model Context Protocol](https://modelcontextprotocol.io) server**.
Every editing capability — cut, filter, LUT, denoise, matte, generate, … — is an MCP *tool*. Any MCP
client (Claude Desktop, an SDK script, another app, your own plugin) can discover and call them. This
is the extensibility surface: you don't need a Guillotine-specific plugin API; you write against MCP,
which already has a large tool/client ecosystem.

**Invariant:** tools exchange **text only** (ids, parameters, summaries). Your video and audio never
leave the device — a controller drives the editor, it doesn't receive frames.

> **The full tool catalog lives in [TOOLS.md](TOOLS.md)** — every tool's name, description, and JSON
> schema (generated from the live definitions), alongside the authoritative server spec: port `6274`,
> bearer auth on `POST /mcp`, the JSON-RPC methods, and the read-only resources. This document is the
> **plugin / extensibility** side — connecting a client, user-defined tool packs, and the draft
> manifest. The two don't repeat each other: start here to build a plugin, cross to TOOLS.md for the
> exact catalog of what's callable.

---

## The server

- **Transport:** HTTP, JSON-RPC 2.0. `POST /mcp` with a JSON-RPC body; `GET /health` for liveness.
- **Port:** `6274` (bound so tools on your network can reach the app).
- **Protocol version:** `2024-11-05`.
- **Auth:** every `/mcp` call needs `Authorization: Bearer <token>`. The token is shown in
  **Settings → Advanced** (generated on first use). See
  [`McpServer`](../shared/src/main/kotlin/com/hereliesaz/guillotine/mcp/McpServer.kt) /
  [`McpAuth`](../app/src/main/java/com/hereliesaz/guillotine/mcp/McpAuth.kt).

### Methods

| Method | Purpose |
| --- | --- |
| `initialize` | Handshake — returns `protocolVersion`, `capabilities`, `serverInfo`. |
| `tools/list` | The full, **self-describing** tool catalog: each tool's `name`, `description`, and JSON `inputSchema`. This is how a plugin discovers what the editor can do — no separate manifest needed. |
| `tools/call` | Invoke a tool by `name` with `arguments`. Returns the tool's result (JSON, usually with a `humanSummary`). |

### Connect

```bash
# 1. Discover tools
curl -s http://<device-ip>:6274/mcp \
  -H "Authorization: Bearer $GUILLOTINE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 2. Call one
curl -s http://<device-ip>:6274/mcp \
  -H "Authorization: Bearer $GUILLOTINE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"apply_lut","arguments":{"path":"/sdcard/luts/teal.cube"}}}'
```

Point any MCP client at that endpoint + token and it sees the editor's tools like any other MCP server.

### Remote access

To drive the editor from off-device without exposing the phone directly, deploy
[`tools/mcp-relay`](../tools) (a Cloudflare Worker) and run the local proxy with the same token — see
**Settings → Advanced → Remote relay** and [`McpRelayConfig`](../app/src/main/java/com/hereliesaz/guillotine/mcp/McpRelayConfig.kt).
The relay only ever brokers the same text JSON-RPC; no media crosses it.

---

## User-defined tools (macros)

You (or a controller LLM) can mint named macros over existing tools, persisted on-device:

- `create_user_tool(name, description)` — save a named editing method.
- `run_user_tool(name, clip_id)` — apply it to a clip.
- `list_user_tools()` / `delete_user_tool(name)` — manage them.

User tools travel in the **settings backup bundle** (Settings → export), so a pack of macros is a
single shareable JSON file — the simplest way to distribute a "plugin" today. See
[`UserToolStore`](../app/src/main/java/com/hereliesaz/guillotine/data/UserToolStore.kt).

---

## Writing a plugin

1. **A tool client / agent.** Speak MCP to `/mcp` (or the relay). Call `tools/list`, then `tools/call`.
   Anything the app can do, your plugin can orchestrate. This works today.
2. **A macro pack.** Ship a settings-backup JSON containing `userTools`; users import it. Works today.
3. **A native effect** (LUT / shader / filter) — see [ECOSYSTEM.md](ECOSYSTEM.md): `.cube` LUTs, ISF/GLSL
   shaders (with adjustable sliders), Frei0r/FFmpeg filters, and clip-to-clip `xfade` transitions all
   drop in today. A live-preview compositor for the exact gl-transitions GLSL catalog is the remaining
   follow-up.

### Proposed: distributable tool-pack manifest (draft)

To make third-party tool packs installable/discoverable (rather than only importable via settings
backup), the proposed manifest — **draft, subject to change; feedback welcome** — is a small JSON:

```jsonc
{
  "name": "cinematic-pack",
  "version": "1.0.0",
  "description": "Cinematic grades + macros",
  "author": "you",
  "requiresProtocol": "2024-11-05",
  "userTools": [ /* same shape as the settings-backup userTools */ ],
  "assets": [ { "type": "lut", "path": "luts/teal-orange.cube" } ]
}
```

Until this lands, use the two shipping mechanisms above. The **live `tools/list` catalog is the source
of truth** for what's callable, so a plugin never has to hard-code a tool list — it introspects.

---

## See also

- **[TOOLS.md](TOOLS.md)** — the full, generated tool catalog and the authoritative MCP server spec
  (port `6274`, bearer auth, `/mcp`, JSON-RPC methods, resources).
- **[ECOSYSTEM.md](ECOSYSTEM.md)** — LUTs, shaders, Frei0r/FFmpeg filters, and transitions.
- **[SETTINGS.md](SETTINGS.md)** — where the MCP access token and the remote relay are configured.
- **[MANUAL.md](MANUAL.md)** — using the editor and its AI features.
