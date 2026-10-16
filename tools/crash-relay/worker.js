/**
 * Guillotine crash relay — a Cloudflare Worker that turns a crash POST from the app into a
 * GitHub issue. The GitHub token lives here as a secret (never in the shipped APK).
 *
 * Crash reporting in the app is strictly opt-in: nothing is sent unless the user enables it and
 * enters a relay URL in Settings. See README.md and PRIVACY.md.
 *
 * Env:
 *   GH_TOKEN      (secret)  fine-grained PAT with Issues: read & write on the repo
 *   GH_REPO       (var)     e.g. "HereLiesAz/Guillotine"
 *   RELAY_SECRET  (secret, optional but recommended) shared value the client must send in the
 *                 `X-Guillotine-Relay-Key` header. This is WEAK protection: any build that sends
 *                 it embeds it, so it only stops drive-by abuse of a leaked URL, not a determined
 *                 attacker. Rotate it if abused.
 *   RATE_KV       (KV binding, optional) enables a cross-isolate per-IP rate limit. Without it,
 *                 a best-effort in-memory limit (per isolate) is used.
 *
 * The app POSTs JSON { "title": "<fingerprint>", "body": "<full report>", "labels": [...] }.
 * Identical reports (same title) are de-duplicated: a hash marker of the title is embedded in the
 * issue body and searched for, so a recurring crash adds a comment instead of a new issue.
 */

const MAX_BODY_BYTES = 64 * 1024;
const RATE_LIMIT = 10; // requests
const RATE_WINDOW_SECONDS = 3600; // per hour per IP
const ALLOWED_LABELS = new Set(["crash", "bug", "export"]);
const memoryBuckets = new Map();

function text(msg, status) {
  return new Response(msg, { status, headers: { "Content-Type": "text/plain" } });
}

async function rateLimited(env, ip) {
  const windowId = Math.floor(Date.now() / 1000 / RATE_WINDOW_SECONDS);
  const key = `rl:${ip}:${windowId}`;
  if (env.RATE_KV) {
    const n = parseInt((await env.RATE_KV.get(key)) || "0", 10);
    if (n >= RATE_LIMIT) return true;
    await env.RATE_KV.put(key, String(n + 1), { expirationTtl: RATE_WINDOW_SECONDS * 2 });
    return false;
  }
  // Best effort only: isolates are short-lived and not shared across locations.
  if (memoryBuckets.size > 10000) memoryBuckets.clear();
  const n = memoryBuckets.get(key) || 0;
  if (n >= RATE_LIMIT) return true;
  memoryBuckets.set(key, n + 1);
  return false;
}

/** Strip obvious personal data from logcat / stack text before it lands in a public issue. */
function redact(s) {
  return s
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, "[email]")
    .replace(/\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/g, "[token]")
    .replace(/\b(sk-[A-Za-z0-9_-]{16,}|AIza[0-9A-Za-z_-]{30,}|hf_[A-Za-z0-9]{20,})\b/g, "[token]")
    .replace(/\b(Bearer|Authorization:?|api[_-]?key[=:]|token[=:])\s*[A-Za-z0-9._~+\/=-]{8,}/gi, "$1 [token]")
    .replace(/\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/g, "[jwt]")
    .replace(/content:\/\/[^\s)'"`]+/g, "content://[redacted]")
    .replace(/file:\/\/[^\s)'"`]+/g, "file://[redacted]")
    .replace(/\/(storage\/emulated\/\d+|sdcard|data\/user\/\d+)\/[^\s)'"`]+/g, "/$1/[redacted]");
}

async function sha256Hex(s) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") return text("POST only", 405);

    if (env.RELAY_SECRET && !safeEqual(request.headers.get("X-Guillotine-Relay-Key") || "", env.RELAY_SECRET)) {
      return text("Forbidden", 403);
    }

    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    if (await rateLimited(env, ip)) return text("Rate limited", 429);

    const declared = parseInt(request.headers.get("Content-Length") || "0", 10);
    if (declared > MAX_BODY_BYTES) return text("Payload too large", 413);
    const raw = await request.text();
    if (new TextEncoder().encode(raw).length > MAX_BODY_BYTES) return text("Payload too large", 413);

    let payload;
    try {
      payload = JSON.parse(raw);
    } catch {
      return text("Invalid JSON", 400);
    }
    if (payload === null || typeof payload !== "object" || Array.isArray(payload)) {
      return text("Expected a JSON object", 400);
    }

    const title = redact(String(payload.title || "App crash")).replace(/\s+/g, " ").trim().slice(0, 200) || "App crash";
    const body = redact(String(payload.body || "")).slice(0, 60000);
    const requested = Array.isArray(payload.labels) ? payload.labels.map((l) => String(l).toLowerCase()) : [];
    const filtered = [...new Set(requested.filter((l) => ALLOWED_LABELS.has(l)))];
    const labels = filtered.length > 0 ? filtered : ["crash"];

    const repo = env.GH_REPO;
    const ghHeaders = {
      Authorization: `Bearer ${env.GH_TOKEN}`,
      Accept: "application/vnd.github+json",
      "User-Agent": "guillotine-crash-relay",
    };
    const gh = (path, init = {}) =>
      fetch(`https://api.github.com/repos/${repo}${path}`, {
        ...init,
        headers: { ...ghHeaders, "Content-Type": "application/json", ...(init.headers || {}) },
      });

    // De-dupe on a hash marker embedded in the issue body — no user text is interpolated into the
    // search query, so a crafted title can't widen or bypass the match.
    const marker = `guillotine-relay-${(await sha256Hex(title)).slice(0, 32)}`;
    const q = encodeURIComponent(`repo:${repo} is:issue is:open in:body ${marker}`);
    const search = await fetch(`https://api.github.com/search/issues?q=${q}`, { headers: ghHeaders });
    if (search.ok) {
      const found = (await search.json()).items?.find((i) => (i.body || "").includes(marker));
      if (found) {
        const r = await gh(`/issues/${found.number}/comments`, {
          method: "POST",
          body: JSON.stringify({ body: "Recurred:\n\n```\n" + body + "\n```" }),
        });
        return text(r.ok ? "ok" : "upstream error", r.ok ? 201 : 502);
      }
    }

    // Preface distinguishes an automatic crash upload from a manual Report tap — the manual
    // path already carries a formatted markdown body, so we don't wrap it in ``` again.
    const isManual = labels.some((l) => l === "bug" || l === "export");
    const issueBody =
      (isManual ? body : "Automatically filed from an app crash.\n\n```\n" + body + "\n```") +
      `\n\n---\nRelay fingerprint: ${marker}`;

    const r = await gh(`/issues`, {
      method: "POST",
      body: JSON.stringify({ title, body: issueBody, labels }),
    });
    return text(r.ok ? "ok" : "upstream error", r.ok ? 201 : 502);
  },
};
