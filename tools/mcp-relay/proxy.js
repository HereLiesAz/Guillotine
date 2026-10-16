#!/usr/bin/env node
/**
 * Guillotine MCP relay — tool-side proxy.
 *
 * Exposes a LOCAL drop-in MCP endpoint (`http://127.0.0.1:<PORT>/mcp`) for your AI tool, and
 * tunnels each JSON-RPC request to the phone through the Cloudflare relay Worker over an
 * end-to-end-encrypted WebSocket. The Worker (and Cloudflare) only ever see ciphertext.
 *
 * The MCP token is the shared secret — use the exact token shown in the app's Settings. The
 * key/room derivation, AAD and AES-256-GCM framing (v2) here must match McpCrypto.kt in the app.
 *
 * Usage:
 *   npm install            # installs the `ws` dependency
 *   MCP_TOKEN=<token from app> \
 *   RELAY_WORKER_URL=wss://guillotine-mcp-relay.<you>.workers.dev/relay \
 *   [RELAY_ACCESS_KEY=<key>] [PORT=6275] \
 *   node proxy.js
 *
 * Then point your tool at http://127.0.0.1:6275/mcp (no Authorization header needed locally —
 * possession of the token, via this proxy, is the auth). Requests must use Host 127.0.0.1:<PORT>
 * (or localhost:<PORT>), Content-Type application/json, and no Origin header — browsers are refused.
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
// Frame v2 — must match McpCrypto.kt: one key per direction, and rid/direction/ctr/ts bound as AAD.
const VERSION = 2;
const DIR_TO_DEVICE = "t2d";
const DIR_TO_TOOL = "d2t";
const KEYS = {
  [DIR_TO_DEVICE]: sha256(`guillotine-mcp-key:v2:${DIR_TO_DEVICE}:` + TOKEN), // 32 bytes (AES-256)
  [DIR_TO_TOOL]: sha256(`guillotine-mcp-key:v2:${DIR_TO_TOOL}:` + TOKEN),
};
const ROOM = sha256("guillotine-mcp-room:" + TOKEN).toString("hex");
const aad = (dir, rid, ctr, ts) => Buffer.from(`guillotine-mcp|v${VERSION}|${dir}|${rid}|${ctr}|${ts}`, "utf8");

function seal(dir, rid, ctr, ts, plaintext) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", KEYS[dir], iv);
  cipher.setAAD(aad(dir, rid, ctr, ts));
  const enc = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag(); // appended so the framing matches Java's GCM output
  return { iv: iv.toString("base64"), ct: Buffer.concat([enc, tag]).toString("base64") };
}

function open(dir, rid, ctr, ts, ivB64, ctB64) {
  const iv = Buffer.from(ivB64, "base64");
  const buf = Buffer.from(ctB64, "base64");
  if (iv.length !== 12 || buf.length < 16) throw new Error("bad frame");
  const tag = buf.subarray(buf.length - 16);
  const enc = buf.subarray(0, buf.length - 16);
  const decipher = crypto.createDecipheriv("aes-256-gcm", KEYS[dir], iv);
  decipher.setAAD(aad(dir, rid, ctr, ts));
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(enc), decipher.final()]).toString("utf8");
}

// Monotonic per-process request counter, bound into each request's AAD and echoed by the device.
let counter = 0;
const MAX_BODY_BYTES = 8 * 1024 * 1024;

const pending = new Map(); // rid -> { res, timer }
let ws = null;

function connect() {
  if (ws) { try { ws.close(); } catch (_) {} }
  const sep = WORKER_URL.includes("?") ? "&" : "?";
  const url = `${WORKER_URL}${sep}room=${ROOM}&role=tool`;
  const headers = ACCESS_KEY ? { "X-Relay-Key": ACCESS_KEY } : {};
  const socket = new WebSocket(url, { headers });
  ws = socket;

  // Guard every handler with `ws === socket` so a stale (replaced/closing) socket can't clobber
  // the current connection or trigger duplicate reconnects.
  socket.on("open", () => {
    if (ws !== socket) return;
    console.error("Relay connected; device pairing on room", ROOM.slice(0, 8) + "…");
  });
  socket.on("message", (data) => {
    if (ws !== socket) return;
    let frame;
    try { frame = JSON.parse(data.toString()); } catch (_) { return; }
    if (!frame || frame.v !== VERSION || typeof frame.rid !== "string") return;
    const entry = pending.get(frame.rid);
    // Only a still-pending request, answered with its own counter, is accepted — a replayed or
    // misrouted response finds no entry (or the wrong ctr) and is dropped.
    if (!entry || frame.ctr !== entry.ctr || !Number.isSafeInteger(frame.ts)) return;
    let plain;
    try {
      plain = open(DIR_TO_TOOL, frame.rid, frame.ctr, frame.ts, frame.iv, frame.ct);
    } catch (e) {
      return; // forged/tampered frame: leave the request pending; it times out if nothing valid arrives
    }
    clearTimeout(entry.timer);
    pending.delete(frame.rid);
    entry.res.writeHead(200, { "content-type": "application/json" });
    entry.res.end(plain);
  });
  socket.on("close", () => {
    if (ws === socket) { ws = null; setTimeout(connect, 2000); }
  });
  socket.on("error", () => {
    if (ws === socket) { try { socket.close(); } catch (_) {} }
  });
}

// This endpoint is unauthenticated (holding the token via this proxy IS the auth), so guard it
// against the browser: a web page can POST to 127.0.0.1 (CSRF) or reach it via a rebound DNS name.
// Require the exact loopback Host, refuse anything carrying an Origin header (browsers send one on
// cross-origin POSTs; MCP clients don't), and require a JSON content type (which a page can't send
// cross-origin without a CORS preflight this server never answers).
const ALLOWED_HOSTS = new Set([`127.0.0.1:${PORT}`, `localhost:${PORT}`]);

function reject(res, code, msg) {
  res.writeHead(code, { "content-type": "text/plain" });
  res.end(msg);
}

function isNotification(body) {
  // JSON-RPC: a request without an `id` is a notification and gets no response. A batch is a
  // notification-only batch when every element lacks an id.
  try {
    const j = JSON.parse(body);
    const isNote = (m) => m && typeof m === "object" && (!("id" in m) || m.id === null);
    return Array.isArray(j) ? j.length > 0 && j.every(isNote) : isNote(j);
  } catch (_) {
    return false;
  }
}

const server = http.createServer((req, res) => {
  if (!ALLOWED_HOSTS.has(String(req.headers.host || "").toLowerCase())) {
    return reject(res, 421, "bad host");
  }
  if (req.headers.origin !== undefined) {
    return reject(res, 403, "browser origins are not allowed");
  }
  if (req.method === "GET" && req.url === "/health") {
    res.writeHead(200, { "content-type": "application/json" });
    res.end('{"status":"ok"}');
    return;
  }
  if (req.method !== "POST" || req.url !== "/mcp") {
    return reject(res, 404, "not found");
  }
  const ctype = String(req.headers["content-type"] || "").split(";")[0].trim().toLowerCase();
  if (ctype !== "application/json") {
    return reject(res, 415, "content-type must be application/json");
  }
  const chunks = [];
  let size = 0;
  let aborted = false;
  req.on("data", (c) => {
    if (aborted) return;
    size += c.length;
    if (size > MAX_BODY_BYTES) {
      aborted = true;
      reject(res, 413, "request too large");
      req.destroy();
      return;
    }
    chunks.push(c);
  });
  req.on("end", () => {
    if (aborted) return;
    const body = Buffer.concat(chunks).toString("utf8");
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      return reject(res, 503, "relay not connected");
    }
    const rid = crypto.randomUUID();
    const ctr = ++counter;
    const ts = Date.now();
    const sealed = seal(DIR_TO_DEVICE, rid, ctr, ts, body);
    const frame = JSON.stringify({ v: VERSION, rid, ctr, ts, iv: sealed.iv, ct: sealed.ct });
    if (isNotification(body)) {
      // The device sends nothing back for a notification; acknowledge it now instead of waiting
      // out the full timeout and answering 504.
      ws.send(frame);
      res.writeHead(202);
      res.end();
      return;
    }
    const timer = setTimeout(() => {
      pending.delete(rid);
      reject(res, 504, "relay timeout (is the app open and the relay enabled?)");
    }, REQUEST_TIMEOUT_MS);
    pending.set(rid, { res, timer, ctr });
    ws.send(frame);
  });
});

connect();
server.listen(PORT, "127.0.0.1", () => {
  console.error(`MCP proxy listening on http://127.0.0.1:${PORT}/mcp`);
});
