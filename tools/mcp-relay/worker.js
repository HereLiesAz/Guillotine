/**
 * Guillotine MCP relay — a Cloudflare Worker that brokers an end-to-end-encrypted channel
 * between the Guillotine app (running an embedded MCP server on a phone, behind NAT) and an
 * external AI/ML tool, without port-forwarding.
 *
 * Both sides open an outbound WebSocket to this Worker and join the same `room` (a public id
 * derived from the shared MCP token). A Durable Object pairs them and relays opaque frames:
 *
 *   { "rid": "<correlation>", "iv": "<base64>", "ct": "<base64>" }
 *
 * The payloads are AES-256-GCM sealed with a key derived from the token (see McpCrypto.kt and
 * proxy.js), so this Worker never sees plaintext and cannot read or forge editor traffic. The
 * room id is one-way-derived from the token, so it reveals neither the token nor the key.
 *
 * Optional abuse gate: set the RELAY_ACCESS_KEY secret and both sides must send it as the
 * `X-Relay-Key` header to connect at all.
 */

export class RelayRoom {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.device = null; // the single app/device socket
    this.tools = new Set(); // one or more tool sockets
  }

  async fetch(request) {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    const role = new URL(request.url).searchParams.get("role") === "device" ? "device" : "tool";
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();

    if (role === "device") {
      if (this.device) {
        try { this.device.close(1012, "replaced by a newer device"); } catch (_) {}
      }
      this.device = server;
    } else {
      this.tools.add(server);
    }

    server.addEventListener("message", (event) => {
      const data = typeof event.data === "string" ? event.data : null;
      if (!data) return;
      if (role === "device") {
        for (const tool of this.tools) {
          try { tool.send(data); } catch (_) {}
        }
      } else if (this.device) {
        try { this.device.send(data); } catch (_) {}
      }
    });

    const cleanup = () => {
      if (role === "device") {
        if (this.device === server) this.device = null;
      } else {
        this.tools.delete(server);
      }
    };
    server.addEventListener("close", cleanup);
    server.addEventListener("error", cleanup);

    return new Response(null, { status: 101, webSocket: client });
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return new Response(JSON.stringify({ status: "ok" }), {
        headers: { "content-type": "application/json" },
      });
    }
    if (url.pathname !== "/relay") {
      return new Response("not found", { status: 404 });
    }
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    if (env.RELAY_ACCESS_KEY && request.headers.get("X-Relay-Key") !== env.RELAY_ACCESS_KEY) {
      return new Response("unauthorized", { status: 401 });
    }

    const room = url.searchParams.get("room");
    if (!room) return new Response("missing room", { status: 400 });

    const id = env.RELAY_ROOM.idFromName(room);
    return env.RELAY_ROOM.get(id).fetch(request);
  },
};
