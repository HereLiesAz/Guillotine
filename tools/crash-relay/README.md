# Guillotine crash relay

A tiny [Cloudflare Worker](https://workers.cloudflare.com/) that receives a crash report
from the app and files (or de-dupes into) a GitHub issue. The GitHub token stays here as a
secret — it is **never** shipped in the APK.

## Why a relay?

A public Android app can't safely embed a GitHub token (anyone can extract it from the APK).
The app instead POSTs the crash to this endpoint, which holds the token and creates the issue.

## Deploy (free tier)

1. Install Wrangler and log in:
   ```
   npm i -g wrangler
   wrangler login
   ```
2. From `tools/crash-relay/`, set the repo and token:
   ```
   wrangler deploy
   wrangler secret put GH_TOKEN     # paste a fine-grained PAT: Issues read & write on the repo
   ```
   `GH_REPO` is set in `wrangler.toml` (default `HereLiesAz/Guillotine`).
3. Copy the deployed URL (e.g. `https://guillotine-crash-relay.<you>.workers.dev`).
4. In the app: **Menu → Settings → Crash reporting**, paste that URL, Save.

That's it — crashes captured on one run are POSTed automatically on the next launch, and
recurring crashes (same fingerprint) are added as comments instead of new issues.

## Request shape

```
POST /
{ "title": "<exception fingerprint>", "body": "<full report: trace + device + logcat>" }
```

## Other hosts

Any endpoint that accepts that POST works (Vercel/Netlify function, Deno Deploy, etc.).
Port `worker.js` — the only platform-specific bits are reading `env.GH_TOKEN` / `env.GH_REPO`.
