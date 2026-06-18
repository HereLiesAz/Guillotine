#!/usr/bin/env node
/**
 * Guillotine MCP relay — tool-side proxy.
 *
 * Exposes a LOCAL drop-in MCP endpoint (`http://127.0.0.1:<PORT>/mcp`) for your AI tool, and
 * tunnels each JSON-RPC request to the phone through the Cloudflare relay Worker over an
 * end-to-end-encrypted WebSocket. The Worker (and Cloudflare) only ever see ciphertext.
 *
 * The MCP token is the shared secret — use the exact token shown in the app's Settings. The
 * key/room derivation and AES-256-GCM framing here must match McpCrypto.kt in the app.
 *
 * Usage:
 *   npm install            # installs the `ws` dependency
 *   MCP_TOKEN=<token from app> \
 *   RELAY_WORKER_URL=wss://guillotine-mcp-relay.<you>.workers.dev/relay \
 *   [RELAY_ACCESS_KEY=<key>] [PORT=6275] \
 *   node proxy.js
 *
 * Then point your tool at http://127.0.0.1:6275/mcp (no Authorization header needed locally —
 * possession of the token, via this proxy, is the auth).
 */

const http = require("http");
const crypto = require("crypto");
const WebSocket = require("ws");

const TOKEN = process.env.MCP_TOKEN || "";
const WORKER_URL = process.env.RELAY_WORKER_URL || "";
const ACCESS_KEY = process.env.RELAY_ACCESS_KEY || "";
const PORT = parseInt(process.env.PORT || "6275", 10);
const REQUEST_TIMEOUT_MS = 120000;

if (!TOKEN || !WORKER_URL) {
  console.error("Set MCP_TOKEN and RELAY_WORKER_URL (wss://…/relay). See the header of this file.");
  process.exit(1);
}

const sha256 = (s) => crypto.createHash("sha256").update(s, "utf8").digest();
const KEY = sha256("guillotine-mcp-key:" + TOKEN); // 32 bytes (AES-256)
const ROOM = sha256("guillotine-mcp-room:" + TOKEN).toString("hex");

function seal(plaintext) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", KEY, iv);
  const enc = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag(); // appended so the framing matches Java's GCM output
  return { iv: iv.toString("base64"), ct: Buffer.concat([enc, tag]).toString("base64") };
}

function open(ivB64, ctB64) {
  const iv = Buffer.from(ivB64, "base64");
  const buf = Buffer.from(ctB64, "base64");
  const tag = buf.subarray(buf.length - 16);
  const enc = buf.subarray(0, buf.length - 16);
  const decipher = crypto.createDecipheriv("aes-256-gcm", KEY, iv);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(enc), decipher.final()]).toString("utf8");
}

const pending = new Map(); // rid -> { res, timer }
let ws = null;

function connect() {
  const sep = WORKER_URL.includes("?") ? "&" : "?";
  const url = `${WORKER_URL}${sep}room=${ROOM}&role=tool`;
  const headers = ACCESS_KEY ? { "X-Relay-Key": ACCESS_KEY } : {};
  ws = new WebSocket(url, { headers });

  ws.on("open", () => console.error("Relay connected; device pairing on room", ROOM.slice(0, 8) + "…"));
  ws.on("message", (data) => {
    let frame;
    try { frame = JSON.parse(data.toString()); } catch (_) { return; }
    const entry = pending.get(frame.rid);
    if (!entry) return;
    clearTimeout(entry.timer);
    pending.delete(frame.rid);
    try {
      const plain = open(frame.iv, frame.ct);
      entry.res.writeHead(200, { "content-type": "application/json" });
      entry.res.end(plain);
    } catch (e) {
      entry.res.writeHead(502);
      entry.res.end("relay decrypt failed (token mismatch?)");
    }
  });
  ws.on("close", () => { ws = null; setTimeout(connect, 2000); });
  ws.on("error", () => { try { ws.close(); } catch (_) {} });
}

const server = http.createServer((req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    res.writeHead(200, { "content-type": "application/json" });
    res.end('{"status":"ok"}');
    return;
  }
  if (req.method !== "POST" || req.url !== "/mcp") {
    res.writeHead(404);
    res.end("not found");
    return;
  }
  let body = "";
  req.on("data", (c) => { body += c; });
  req.on("end", () => {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      res.writeHead(503);
      res.end("relay not connected");
      return;
    }
    const rid = crypto.randomUUID();
    const sealed = seal(body);
    const timer = setTimeout(() => {
      pending.delete(rid);
      res.writeHead(504);
      res.end("relay timeout (is the app open and the relay enabled?)");
    }, REQUEST_TIMEOUT_MS);
    pending.set(rid, { res, timer });
    ws.send(JSON.stringify({ rid, iv: sealed.iv, ct: sealed.ct }));
  });
});

connect();
server.listen(PORT, "127.0.0.1", () => {
  console.error(`MCP proxy listening on http://127.0.0.1:${PORT}/mcp`);
});
