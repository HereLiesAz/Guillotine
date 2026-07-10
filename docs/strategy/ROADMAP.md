# Guillotine — Strategic Roadmap: what it should become

> **Internal strategy document.** The honest plan for turning Guillotine from a powerful pre-1.0 NLE into
> something people actually adopt — and that the maintainer can be proud of and sustain. Pairs with
> `MARKET.md` (the landscape) and `FINANCIAL.md` (the money + license). Excluded from the in-app reader
> (`.azignore`); not linked from the website.

## North star

**The private editor that does what you ask.** A real multi-track editor where you can *tell it what to
do and it does it* — captions, cuts, looks, cleanup, generation — with a hard guarantee the mainstream
can feel: **your footage never leaves your device.** Free and open source; powerful when you plug in a
key; trustworthy because it can't betray you architecturally.

The wedge is real (privacy + on-device AI + free + open source, aimed at CapCut refugees doing client/
brand work). The job is to make it *adoptable* without diluting it.

## The gap to close (from `MARKET.md`)

What drives adoption in 2026 is **auto-captions, a no-watermark free tier, one-tap vertical/social
presets, templates/trends, a music library, and social-app simplicity** — plus **being findable.**
Guillotine today over-indexes on timeline/AI depth and under-indexes on all of that, and has near-zero
distribution. The roadmap is ordered to fix findability and the on-ramp first, then productize the wedge.

## Phased plan

### Phase 0 — Credibility & hygiene (get to a trustworthy 1.0)
Nothing else matters if the app looks abandoned or won't install cleanly.
- **Sign the desktop installers** (macOS/Windows/Linux) — kill the "unidentified developer" wall.
- **Real store presence:** a polished Play Store listing; **F-Droid** (a natural home now that it's
  AGPL); consider Mac App Store / Microsoft Store later.
- **Verify the built-but-lightly-tested features** end-to-end (the on-device AI paths, export parity
  across Android/desktop) and close export holes.
- **Cut a real 1.0** with a tight, honest feature set and a changelog.
- **Distribution basics:** the landing page (privacy wedge front-and-center — in progress), a 30-second
  demo video, and a couple of "CapCut alternative" / "private video editor" posts where that audience is.

### Phase 1 — Mainstream on-ramp (make a casual creator succeed in 60 seconds)
Add the adoption drivers *without* removing the pro depth.
- **Auto-captions, best-in-class and on-device** — the #1 requested feature *and* a privacy proof-point
  (transcription never hits a server). Animated/trendy styles, multi-language, one tap.
- **One-tap social aspect presets** (9:16 / 1:1 / 16:9) + auto-reframe; later, **direct publish** to
  TikTok/Reels/Shorts.
- **A quick-edit mode** — a social-app-simple lane over the pro timeline: import → auto-caption →
  template → one transition → export.
- **Templates/presets** and **stickers/text presets**; **surface beat-sync + a music story** as a
  first-class flow.
- **One-tap background removal** (already on-device — make it a button, not a setting).
- **Kill setup friction:** feel powerful with **zero key** — lean hard on the keyless free tier
  (Pollinations/Guillotine-free) + on-device brain; sensible defaults; no account, ever.

### Phase 2 — The AI wedge, productized (the thing only Guillotine can honestly claim)
- Make **"tell it what to cut, it cuts"** reliably the headline experience — the on-device analyzer +
  agent loop has to *just work* on real footage.
- **Desktop AI parity** — unstub the on-device AI tools on desktop so the cross-platform story is true.
- **Opt-in cloud AI credits** for generative tasks too heavy to run locally — the primary *scalable*
  revenue lever, strictly opt-in so the privacy promise holds (see `FINANCIAL.md`).

### Phase 3 — Ecosystem & moat
- **Plugin / LUT / shader marketplace** (revenue share) once there's an install base — the MCP server +
  the extensibility ecosystem already make this possible.
- **The external-MCP-control story** for power users (any AI can drive the editor) as a differentiator.
- **Community + sponsors/grants** maturing into sustained funding.

### Throughout — distribution & marketing
The biggest *actual* gap. A better editor nobody can find loses. Privacy is the brand; every grievance
CapCut created (watermark-gating, price doubling, content-licensing, disappearing act) is a message
Guillotine can invert.

## Governance & values — carrying the maintainer's values forward

The maintainer wants the project's values to persist even as others build on it, and to keep a voice in
its direction — **a seat at the table, not control over anyone's use.** The FOSS-compatible way to do
that is governance + brand, layered on top of the AGPL code (details in `FINANCIAL.md`):

- **AGPL-3.0 core (done)** — the code stays open; copyleft carries the "stays free/open" value downstream.
- **`NOTICE` §7 terms (done)** — forks must keep visible attribution, mark changes, and **rename**
  (the "Guillotine" name + logo are reserved). Forks may take the code, not the brand.
- **`GOVERNANCE.md` charter (to write)** — states the project's values and decision model, reserves the
  **founder a permanent voice**, and ties **use of the Guillotine name** to the charter. Crucially, per
  the maintainer's "continued use" framing, the obligation lasts **only as long as an organization
  operates under the Guillotine name**: any such org extends a **standing invitation for the founder to be
  heard, in an official capacity, at its strategic/directional reviews** (a voice, not a vote or veto —
  which keeps it FOSS-compatible and enforceable via the trademark, not the code license). Stop using the
  name → the obligation ends.
- **CLA before any outside contribution (to do)** — preserves 100% ownership so future versions can be
  relicensed/dual-licensed and the commercial layer stays sellable.
- **Steward entity later** — a small nonprofit/foundation whose bylaws give the founder a permanent seat,
  the durable institutional home for the values and the voice, if the project grows.
- **Honest limit:** a hard fork that renames and walks away escapes all of this — that is the definition
  of a fork, and the price they pay is the brand (users, trust, momentum). No FOSS-compatible mechanism
  reaches further, and reaching further (a use-restriction) would forfeit open-source status.

## Immediate next actions

- [ ] Website: privacy wedge + shipped features + docs links (in progress) and a short demo.
- [ ] Sign desktop installers; stand up a Play listing + F-Droid; verify export parity.
- [ ] Draft `GOVERNANCE.md` (values + decision model + founder voice + name-use/standing-invitation
      clause) and add a **CLA**.
- [ ] Apply to **NLnet/NGI Zero**; set up **GitHub Sponsors + Ko-fi**.
- [ ] Prototype the **quick-edit + auto-captions** on-ramp; make background removal one-tap.
- [ ] Decide the **ads** question (wind down vs keep) and design the **buy-once Pro** unlock.
