/**
 * Guillotine crash relay — a Cloudflare Worker that turns a crash POST from the app into a
 * GitHub issue. The GitHub token lives here as a secret (never in the shipped APK).
 *
 * Deploy: see README.md. Required env:
 *   GH_TOKEN  (secret)  fine-grained PAT with Issues: read & write on the repo
 *   GH_REPO   (var)     e.g. "HereLiesAz/Guillotine"
 *
 * The app POSTs JSON { "title": "<fingerprint>", "body": "<full report>" }. Identical
 * crashes (same title) are de-duplicated: instead of a new issue, a comment is added to the
 * existing open one, so a recurring crash doesn't flood the tracker.
 */
export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response("POST only", { status: 405 });
    }
    let payload;
    try {
      payload = await request.json();
    } catch {
      return new Response("Invalid JSON", { status: 400 });
    }
    const title = (payload.title || "App crash").toString().slice(0, 200);
    const body = (payload.body || "").toString().slice(0, 60000);
    const repo = env.GH_REPO;
    const gh = (path, init = {}) =>
      fetch(`https://api.github.com/repos/${repo}${path}`, {
        ...init,
        headers: {
          Authorization: `Bearer ${env.GH_TOKEN}`,
          Accept: "application/vnd.github+json",
          "User-Agent": "guillotine-crash-relay",
          "Content-Type": "application/json",
          ...(init.headers || {}),
        },
      });

    // De-dupe: is there already an open issue with this exact title?
    const q = encodeURIComponent(`repo:${repo} is:issue is:open in:title "${title}"`);
    const search = await fetch(`https://api.github.com/search/issues?q=${q}`, {
      headers: {
        Authorization: `Bearer ${env.GH_TOKEN}`,
        Accept: "application/vnd.github+json",
        "User-Agent": "guillotine-crash-relay",
      },
    });
    if (search.ok) {
      const found = (await search.json()).items?.find((i) => i.title === title);
      if (found) {
        const r = await gh(`/issues/${found.number}/comments`, {
          method: "POST",
          body: JSON.stringify({ body: "Recurred:\n\n```\n" + body + "\n```" }),
        });
        return new Response(await r.text(), { status: r.status });
      }
    }

    const r = await gh(`/issues`, {
      method: "POST",
      body: JSON.stringify({
        title,
        body: "Automatically filed from an app crash.\n\n```\n" + body + "\n```",
        labels: ["crash"],
      }),
    });
    return new Response(await r.text(), { status: r.status });
  },
};
