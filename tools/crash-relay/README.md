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
3. Copy the deployed URL (e.g. `https://guillotine-crash-relay.<you>.workers.dev`).
4. Bake it into release builds so end users don't need any configuration. Add a line to
   `~/.gradle/gradle.properties` (or set as a CI secret exposed via `-P`):
   ```
   guillotine.crashRelayUrl=https://guillotine-crash-relay.<you>.workers.dev
   ```
   `app/build.gradle.kts` picks it up as `BuildConfig.DEFAULT_CRASH_RELAY_URL`. Alternately,
   power users can override at runtime via **Menu → Settings → Crash reporting**.

That's it — crashes captured on one run are POSTed automatically on the next launch,
recurring crashes (same title) are added as comments instead of new issues, and the manual
Report button on the ExportSheet files `bug,export`-labelled issues in one tap.

## Request shape

```
POST /
{
  "title":  "<fingerprint or short summary>",
  "body":   "<markdown body>",
  "labels": ["bug", "export"]      // optional; defaults to ["crash"]
}
```

The Worker wraps a crash body in a fenced code block (it's raw text); manual reports pass a
pre-formatted markdown body and are used as-is.

## Other hosts

Any endpoint that accepts that POST works (Vercel/Netlify function, Deno Deploy, etc.).
Port `worker.js` — the only platform-specific bits are reading `env.GH_TOKEN` / `env.GH_REPO`.
