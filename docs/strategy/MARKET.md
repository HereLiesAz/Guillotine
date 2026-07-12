# Guillotine — Market & Competitive Analysis (2026)

> **Internal strategy document.** Candid, not marketing. Kept in the repo for the maintainer;
> excluded from the in-app docs reader (see `.azignore`) and not linked from the website.
> Figures are 2026-current where dated; treat vendor "user" counts as directional (they conflate
> downloads, installs, accounts, and MAU). Sources are listed at the end.

## TL;DR

- The consumer video-editing market is **CapCut's to lose**, and it is actively losing goodwill:
  a June-2025 Terms-of-Service change (broad content license + biometrics), a Pro price hike to
  **$19.99/mo**, features moved behind the paywall, and a 75-day US app-store outage. That combination
  created the first real **"CapCut refugee"** wave in years.
- What actually drives adoption in 2026 is **not editing power** — it's **auto-captions, a no-watermark
  free tier, one-tap vertical/social presets, templates/trends, and a music library** wrapped in a
  social-app-simple UX.
- **Privacy/on-device/open-source is a real but niche pull** (OpenCut hit ~48K GitHub stars in under a
  year on exactly this pitch). It wins *trust*, not *mass adoption* — sell it as insurance, lead with
  features.
- **Guillotine's honest gap:** it's framed as a power/pro NLE, is pre-1.0, has no templates/trends/
  stickers/music-library/one-tap-publish, has real setup friction, and has near-zero distribution. Its
  on-device AI + privacy + open-source + free stack is a genuine, defensible wedge — but only if the
  mainstream on-ramp (captions, presets, simplicity, zero-setup) gets built.

## Who uses what (2026)

### Mobile / consumer

| App | Reach (best available) | Pricing | Position |
|---|---|---|---|
| **CapCut** (ByteDance) | ~323M MAU (Sensor Tower, Jul 2024); **509M downloads in 2025** — #1 Photo & Video app worldwide; ByteDance claims 800M+ MAU incl. its Chinese sibling Jianying | Free tier + Standard ~$9.99/mo + **Pro $19.99/mo** | The default. Social-native, template- and AI-driven, TikTok-optimized. |
| **InShot** | ~93M downloads (2025); 500M+ lifetime; the most-named migration target | Free (ads+watermark) + Pro ~$17.99/yr + **~$39.99 lifetime** | Fast, simple short-form; cheap; no ByteDance risk. |
| **Instagram "Edits"** (Meta) | **7.1M downloads first week** (Apr 2025) | Free | Meta's CapCut rival, tied to Reels; free, no ByteDance baggage. |
| **VN (VlogNow)** | Tens of millions | Free, **no watermark** | Real multi-track + keyframe timeline "impressive for a free app"; power-user free pick. |
| **Splice** (Bending Spoons) | 70M+ | Freemium | Strong music/audio heritage. |
| **LumaFusion** | Niche, paid | ~$29.99 one-time | The "serious mobile" iPad/iOS NLE. |

### Desktop / prosumer

| App | Pricing | Position |
|---|---|---|
| **DaVinci Resolve** (Blackmagic) | **Free**; Studio **$295 one-time** | Best free-to-pro on-ramp; the budget-pro default; a top CapCut-refugee target. |
| **Adobe Premiere Pro** | ~$22.99/mo | Industry standard. |
| **Final Cut Pro** | $299 one-time | Mac-native pro. |
| **Filmora** (Wondershare) | Freemium + ~$50–80/yr + **$79.99 perpetual** | Beginner/intermediate; heavy AI marketing. |
| **Clipchamp** (Microsoft) | Free (1080p) + paid | Windows-bundled, no ByteDance risk. |
| **Kdenlive / OpenCut** | Free / OSS | The privacy-conscious open-source picks. |

Market size: video-editing software ~$3.1B (2023) → ~$5.1B by 2032 (~5.8% CAGR); AI editing is the
fastest-growing slice.

## What users look for first (ranked)

The features that repeatedly decide adoption in 2026 roundups, creator guides, and migration threads:

1. **Auto-captions/subtitles** — *table stakes.* Most social video plays muted; expectation is 15–20+
   languages, animated/trendy styles, auto-sync. **The #1 must-have.**
2. **Mobile-first workflow** — edit where you film; feel like a social app, not desktop software.
3. **No watermark on the free tier** — a watermark on brand content is an instant rejection.
4. **One-tap social aspect presets** — 9:16 / 1:1 / 16:9 + auto-reframe to repurpose one clip across
   TikTok/Reels/Shorts.
5. **Templates / trends engine** — the hardest thing for an indie to replicate; needs a content pipeline.
6. **Music library + beat-sync** — royalty-free library with beat detection.
7. **AI effects** — background removal, filler-word/silence removal, vocal isolation, text-to-video.
8. **Speed & simplicity** — open, drop clips, caption, transition, done.
9. **Direct publish** to TikTok/Reels/Shorts.
10. **A genuinely usable free tier.**

## The CapCut backlash — the opening

1. **ToS change (effective June 12, 2025).** Grants ByteDance a "perpetual, worldwide, royalty-free,
   irrevocable license to use, edit, distribute… and exploit" user content, asserts rights to biometric
   data, and **survives account deletion.** Entertainment lawyers flagged it as a dealbreaker for
   client/brand IP work.
2. **Pricing "rugpull."** Pro roughly **doubled to $19.99/mo**; previously-free features (animated
   captions, filler-word removal, vocal isolation) moved behind the paywall. The most-upvoted sentiment:
   *"rugpull."*
3. **Platform risk.** Pulled from US app stores Jan 18, 2025; gone ~75 days. Restored, but it spooked
   creators who "can't build a business on an app that can vanish."

**Where they're going:** VN (free, no watermark), InShot (cheap, simple), DaVinci Resolve (free desktop
pro), Clipchamp/iMovie, and the privacy cluster (Kdenlive, OpenCut). **Common thread:** content
ownership, predictable pricing, reliability, no data/geopolitical baggage — CapCut broke all four in ~12
months.

## Privacy / on-device / open-source demand — real but niche

- **OpenCut** — an MIT-licensed, browser-based editor that runs entirely locally ("your video files
  never leave your device") went **~0 → ~48K GitHub stars in under a year (2025→2026)**. The single
  strongest signal of latent demand for a private, no-lock-in editor. It's now featured on
  **PrivacyTools.io**.
- Privacy is now a **category axis** in "best editor" roundups, not a fringe concern; 2025–26 coverage
  documents a broader **local-first / on-device AI** shift driven by "privacy, data sovereignty, and
  reliability."
- **Honest sizing:** no clean MAU exists for the niche. OpenCut's stars ≈ a developer/early-adopter core
  in the low tens of thousands; the mass market still picks on features + free-ness and treats privacy as
  a **tiebreaker/insurance**. The niche is real, loud, and monetizable at indie scale — a **wedge, not the
  whole market.**

## Where Guillotine does NOT appeal to the mainstream (honest)

- **Framed as a power/pro NLE** ("Sony Vegas style") — the casual creator's loop is *open → drop clips →
  auto-caption → trending template → one transition → publish*, and a pro NLE optimizes the wrong verbs.
- **Missing the adoption drivers:** no templates/trends engine, no licensed music library + beat-sync
  surfaced as a first-class flow, no stickers, no one-tap social publish.
- **Setup friction:** BYO keys, model downloads, unsigned desktop installers, not prominent on app stores.
- **Maturity:** pre-1.0 (v0.9); some on-device AI is Android-first (desktop stubs); features exist that
  are built-but-lightly-verified.
- **Distribution & marketing ≈ zero** — the single biggest practical gap. A better editor nobody can find
  loses to a worse one everybody has.
- The **"AI drives the editor via tools"** idea is powerful but abstract to someone who just wants a
  finished clip.

## The wedge that is actually real

**Privacy + free + no-account + on-device AI + open source (AGPL).** This is exactly what the
anti-CapCut cohort — freelancers, agencies, brand/client creators — is now actively searching for, and
it is a claim CapCut **structurally cannot make** (its business is the cloud and the data). Guillotine
already *architecturally enforces* the on-device invariant (a cloud "brain," if used, only exchanges
text — never your frames), which is a defensible, honest differentiator.

## Strategic implications (the "so what")

1. **Aim the wedge precisely at CapCut refugees doing client/brand work** — the cohort with a concrete,
   ownership-driven reason to leave. Lead with *"your footage and your rights never leave your device."*
2. **Sell privacy as trust, not as the headline.** Make **auto-captions, a no-watermark free tier, and
   one-tap AI** the adoption drivers; make on-device the reason to *trust* it.
3. **Ship best-in-class on-device auto-captions.** Simultaneously the #1 requested feature *and* a privacy
   proof-point (transcription never hits a server).
4. **Close the mainstream gap deliberately** — templates/presets, a beat-sync + audio story, one-tap
   aspect presets, and eventually direct publish. Timeline depth is necessary insurance for
   retention/credibility, but the trend+captions+publish flow drives install-and-stay.
5. **Invert every CapCut grievance:** no watermark, predictable/one-time pricing, no account, no cloud
   dependence, no geopolitical off-switch, no content-licensing land-grab. That inversion *is* the
   positioning.
6. **Fix distribution first (see ROADMAP.md).** Store presence + signed installers + a sharp landing page
   are prerequisites to any of the above mattering.

## Sources

CapCut: Sensor Tower / Expanded Ramblings, SendShort, Rest of World; TechCrunch (Instagram Edits 7.1M);
Splice (most-downloaded roundup); AppBrain (InShot); electroIQ + ProDesignTools (Premiere/CC); SendShort
(DaVinci, Filmora, market size). Backlash: 2B-Advice & Larry Jordan (ToS), Yahoo/Tech, eesel & TechSmith
(alternatives), Fast Company & Wikipedia (ban timeline). Privacy demand: OpenCut.dev, MindwiredAI (48K
stars), PrivacyTools.io, StrongMocha (local-first). Adoption features: Influee, SendShort (captions),
Clippie, Digen. *(Full URLs captured in the research pass; revalidate before citing publicly.)*
