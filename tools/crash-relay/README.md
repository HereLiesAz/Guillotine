# Guillotine crash + issue relay

A tiny [Cloudflare Worker](https://workers.cloudflare.com/) that receives a crash report OR a
manual bug report from the app and files (or de-dupes into) a GitHub issue. The GitHub token
stays here as a secret — it is **never** shipped in the APK.

## Why a relay?

A public Android app can't safely embed a GitHub token (anyone can extract it from the APK).
The app instead POSTs to this endpoint, which holds the token and creates the issue. That
means end users can hit **Report** on an export failure and land a real issue in the tracker
**without a GitHub account of their own**.

## Deploy (free tier)

1. Install Wrangler and log in:
   ```
   npm i -g wrangler
   wrangler login
   ```
2. From `tools/crash-relay/`, deploy and set the token:
   ```
   wrangler deploy
   wrangler secret put GH_TOKEN     # paste a fine-grained PAT: Issues read & write on the repo
   ```
   `GH_REPO` is set in `wrangler.toml` (default `HereLiesAz/Guillotine`).
3. Recommended — set a shared header secret and (optionally) a KV namespace for rate limiting:
   ```
   wrangler secret put RELAY_SECRET   # clients must send it as X-Guillotine-Relay-Key
   ```
   This secret is **weak**: any app build that sends it carries it, so it only deters casual
   abuse of a known URL. The relay additionally enforces a per-IP rate limit (10/hour; shared
   across isolates when a `RATE_KV` binding is configured in `wrangler.toml`, best-effort
   in-memory otherwise), a 64 KB body cap, and a label allowlist (`crash`, `bug`, `export`).
4. Copy the deployed URL (e.g. `https://guillotine-crash-relay.<you>.workers.dev`).

Crash reporting is **strictly opt-in**. No relay URL is baked into the app: nothing is sent
unless the user turns crash reporting on and enters a relay URL in **Menu → Settings → Crash
reporting**. Once enabled, crashes captured on one run are POSTed on the next launch, recurring
crashes (same title) are added as comments instead of new issues, and the manual Report button
on the ExportSheet files `bug,export`-labelled issues.

## What gets published

Issues land in a **public** tracker. Before filing, the relay applies basic redaction to the
title and body: email addresses, common API-token / bearer / JWT shapes, and `content://`,
`file://` and on-device storage paths are replaced with placeholders. This is best-effort — it
is not a guarantee that a log contains no personal data.

## Request shape

```
POST /
{
  "title":  "<fingerprint or short summary>",
  "body":   "<markdown body>",
  "labels": ["bug", "export"]      // optional; allowlist crash|bug|export, defaults to ["crash"]
}
```

The Worker wraps a crash body in a fenced code block (it's raw text); manual reports pass a
pre-formatted markdown body and are used as-is. Each new issue ends with a
`Relay fingerprint: guillotine-relay-<hash>` line (a hash of the title); de-duplication searches
for that marker rather than the raw title.

## Other hosts

Any endpoint that accepts that POST works (Vercel/Netlify function, Deno Deploy, etc.).
Port `worker.js` — the only platform-specific bits are reading `env.GH_TOKEN` / `env.GH_REPO` / `env.RELAY_SECRET` and the optional `RATE_KV` binding.
