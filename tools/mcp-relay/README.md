# Guillotine MCP relay (encrypted)

Reach the app's embedded MCP server **from anywhere** — no LAN, no port-forwarding — over an
**end-to-end-encrypted** channel. A Cloudflare Worker brokers the connection; it only ever sees
ciphertext.

```
AI tool ──HTTP──▶ proxy.js ──WSS(sealed)──▶ Cloudflare Worker ──WSS(sealed)──▶ Guillotine app
            (localhost)                       (relays ciphertext only)        (decrypts, runs tool)
```

## Why a relay?

The app's MCP server runs on the phone, behind NAT, so a tool can't dial into it. Instead both
sides dial **out** to the Worker and meet in a `room` derived from the shared MCP token. Payloads
are AES-256-GCM sealed with a key derived from that token, so Cloudflare can route the pair but
can't read or forge editor traffic.

## 1. Deploy the Worker

```sh
cd tools/mcp-relay
npm install -g wrangler        # or: npx wrangler ...
wrangler deploy                # creates the Worker + Durable Object
# optional but recommended — gate who can use your Worker at all:
wrangler secret put RELAY_ACCESS_KEY
```

Copy the deployed URL and append `/relay`, e.g.
`wss://guillotine-mcp-relay.<you>.workers.dev/relay`.

## 2. Enable it in the app

Settings → **Encrypted cloud relay**:
- check *Reach the editor via Cloudflare*,
- paste the `wss://…/relay` URL,
- paste the same `RELAY_ACCESS_KEY` if you set one,
- copy the **MCP access token** shown just above (it's the end-to-end secret).

## 3. Run the tool-side proxy

```sh
cd tools/mcp-relay
npm install
MCP_TOKEN="<token from the app>" \
RELAY_WORKER_URL="wss://guillotine-mcp-relay.<you>.workers.dev/relay" \
RELAY_ACCESS_KEY="<key if you set one>" \
PORT=6275 \
node proxy.js
```

Point your AI tool at `http://127.0.0.1:6275/mcp`. The proxy seals each JSON-RPC request, tunnels
it to the phone, and returns the decrypted response — a drop-in replacement for hitting the app's
`/mcp` directly, but encrypted and reachable from anywhere.

## Security notes

- **End-to-end:** only the app and this proxy (both holding the token) can read traffic. Cloudflare
  relays opaque `{rid, iv, ct}` frames.
- **Token = secret.** Anyone with it can drive the editor. Rotate it any time with **Regenerate**
  in the app's Settings (instantly invalidates old proxies).
- `RELAY_ACCESS_KEY` is a separate, optional gate that just stops strangers from using *your*
  Worker; it is **not** what protects the payloads.
- The room id is a one-way hash of the token, so it leaks neither the token nor the key.
