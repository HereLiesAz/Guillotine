# Guillotine — Financial Future & License Strategy (2026)

> **Internal strategy document.** Candid, not marketing. Excluded from the in-app docs reader
> (`.azignore`), not linked from the website. Written in good faith by a non-lawyer — have counsel
> review anything load-bearing before relying on it.

## Bottom line up front

- The chosen structure is **open-core on AGPL-3.0**: the whole editor stays genuinely open source; a
  future **paid/hosted layer** sits on top as a **separate PolyForm/commercial component**. Monetization
  comes from *structure*, not from the license fighting the open-source identity.
- **Realistic near-term revenue is small.** The biggest single lever is **grants (NLnet)**, not sales.
  Everything else is gated on **distribution and scale** — which is a product/marketing problem
  (see `ROADMAP.md`), not a licensing one.
- For an **on-device, privacy-first** app there is little compute to resell, so the classic "managed
  cloud" SaaS lever is mostly unavailable. The money is in **the app (buy-once Pro)** + **opt-in cloud AI
  credits** + **grants/donations** — not a subscription treadmill.

## The license decision (what we did, and why)

**AGPL-3.0** structured for open-core monetization:

- **Core app = AGPL-3.0.** Genuinely open source (keeps NLnet grant eligibility, F-Droid, community
  goodwill, and the privacy-**and**-open-source brand that is the actual wedge). Copyleft — especially
  the network clause (§13) — plus the fact that many companies' lawyers ban AGPL internally, deters
  corporate free-riding. As sole copyright holder, the maintainer can also **dual-license**: sell a
  commercial exception to anyone who won't accept AGPL.
- **Paid/hosted layer = separate PolyForm/commercial component.** When a "Pro" or hosted-generation layer
  is actually built, it ships as its own module *on top of* the AGPL core. You can legally combine your
  own AGPL code with your own PolyForm/proprietary code because you own both copyrights; third parties
  can't. Pick the variant by what the layer is:
  - Hosted service / anything a competitor could resell → **PolyForm Noncommercial** (or Strict).
  - A consumer "Pro features" unlock → a plain **commercial/proprietary license** (PolyForm's
    *noncommercial* grant fits poorly when your paying users are themselves commercial creators).
- **Attribution & name (`NOTICE`, already added).** Under AGPLv3 §7: forks must keep visible attribution
  in the About/legal-notices screen (§7(b)), mark modifications (§7(c)), and **rename** — the "Guillotine"
  name and logo are reserved (§7(e)). This is the real anti-clone lever: forks may use the code but not
  the brand.

> **Honest tradeoff.** The financial research leaned toward putting *everything* under PolyForm
> Noncommercial (the strongest block on paid clones), because Guillotine's real threat is app-store
> clones, not SaaS — and AGPL's headline weapon (the §13 network trigger) barely fires for an on-device
> app with no server. We chose **AGPL open-core anyway**, on purpose: the open-source label, grant
> eligibility, and goodwill are worth more to *this* project's positioning than an absolute clone-block,
> and the name/logo reservation + the value living in the brand and the (future) hosted layer make
> cloning the bare core largely pointless.

### Two decided prerequisites

- **Add a CLA before merging any outside contribution.** To keep the right to dual-license / relicense
  future versions, the maintainer must own or have a grant to 100% of the code. Solo today = 100% owned;
  the moment an outside PR merges, that contributor holds copyright on their patch. A CLA (or DCO-style
  inbound=outbound grant) preserves the commercial-license right.
- **A `GOVERNANCE.md` / charter** institutionalizes the maintainer's permanent vote/seat (the "a vote,
  not a veto" goal) and ties **use of the Guillotine name** to the charter — FOSS-compatible, because it
  governs participation and the brand, not anyone's freedom to *use* the code. (Details in `ROADMAP.md`.)

## Monetization options + 2026 benchmarks

### What competitors charge (2026)
CapCut Standard ~$9.99/mo, **Pro $19.99/mo** ($179.99/yr); InShot Pro ~$17.99/yr **+ ~$39.99 lifetime**
+ à-la-carte packs; Filmora $49.99–59.99/yr **+ $79.99 perpetual**; DaVinci Resolve Studio **$295
one-time**.

### A · Buy-once "Pro" unlock — *recommended primary*
A one-time IAP / lifetime license (**~$30–50, InShot-style**) — optionally a low annual sub as an
alternative. Fits on-device better than SaaS and lets "your footage never leaves your device" be the paid
differentiator. **Be pessimistic on conversion:** median mobile freemium download→paid is **~2.2%**;
assume **2–5%** unless onboarding and time-to-value are excellent.

### B · Opt-in cloud AI credits — *secondary*
Usage-based credits for generative tasks too heavy to run locally (Runway/Pika-style metering: e.g.
Runway ~5–25 credits/sec; Pika ~80 credits per 10-sec 1080p clip). Pass GPU cost through with margin.
**Strictly opt-in**, so the privacy promise holds — on-device/BYO-key stay first-class and free.

### C · Ads — *reconsider; likely wind down*
The app currently runs AdMob (banner/interstitial/app-open). Reality: rewarded eCPM is ~$15–30 *well
implemented*, but **utility/creator apps run 20–30% below gaming**, sessions are infrequent, and
**tracking-based ads contradict the privacy positioning.** We already removed the trivially-bypassable
"Free Session" ad-off (ad-free is now a real one-time purchase). **Decision to make:** keep ads as
free-tier monetization for now, but plan to **drop them** as the privacy brand solidifies — the lost
revenue is small and the brand cost is real. A no-ads, buy-once, privacy-first stance is more coherent.

### D · Donations — *do now, ~zero cost, small returns*
GitHub Sponsors (no fee) + **Ko-fi** (0% on one-time, broader reach) + optionally Open Collective. Reality:
most solo maintainers earn **$0–low-tens/month**; a visible project reaches a few hundred. Treat as
goodwill income, not a plan.

### E · Grants — *the biggest realistic lever*
- **NLnet / NGI Zero** — grants **€5,000–€50,000**, R&D + privacy/security focus, **outputs must be
  FOSS** (AGPL qualifies), funds user-facing apps, rolling open calls. **Strong fit** for Guillotine's
  privacy + on-device-AI angle. **Apply.**
- **Sovereign Tech Fund** — larger (€50k+), but **excludes user-facing apps**; only a spun-out *library*
  would qualify.
- **Mozilla MOSS** — on hiatus; the smaller Mozilla Technology Fund is themed.

### F · Ecosystem marketplace (azphalt) — *now a concrete asset; revenue still gated on scale*
No longer hypothetical. **azphalt** — a separate **MIT** repo — is a vendor-neutral portable extension
standard (the `.azp` package format + a TS SDK + importers + a registry) with a built, tested Next.js
**consignment storefront**. Its two-lane design *is* the money model, made explicit:
- **Free open registry** — no fee; the adoption/neutrality layer (the Open-VSX answer). Keeps the
  standard adoptable and self-hostable, so no app is forced through a store to use it.
- **Paid consignment marketplace** — the *only* place a fee lives. Creators who'd rather the platform
  handle the sale list here; the platform takes a thin cut via a split-payout merchant-of-record
  (Stripe-Connect-shaped, **not** single-vendor). The `quote()` split (gross → processor + platform fee
  → seller net) is already modeled, with a fee floor so tiny sales don't run at a loss.

Why it matters financially: it's a **second revenue lane that isn't the editor** and a moat the closed
editors can't copy (a portable format nobody else built), seeded cheaply because the importers turn
brushes/LUTs users already own into catalog. But the fee only nets at **volume**, so marketplace income
stays a *later* line — the standard is the strategic asset now; the revenue follows the install base.

## Realistic expectations

Be honest with yourself: solo, privacy-first, open-source video editors do **not** generate large
revenue early. The plausible 12–24-month picture is **grants (if won) + modest donations + a trickle of
Pro unlocks**, with cloud-credit and marketplace revenue only after real adoption. **Every revenue path
is gated on distribution and scale** — so the highest-leverage financial work right now is not pricing,
it's **shipping the mainstream on-ramp and getting distributed** (see `ROADMAP.md`).

## Recommended stack

1. **License:** AGPL-3.0 core (done) + a future PolyForm/commercial **paid layer** (open-core). Keep the
   CC0-snapshot caveat noted.
2. **Add a CLA now**; publish a `GOVERNANCE.md` charter (the maintainer's permanent vote + name-use terms).
3. **Primary revenue = buy-once Pro** (~$30–50 lifetime unlock) with "your footage never leaves your
   device" as the paid differentiator. Assume 2–5% conversion.
4. **Secondary = opt-in cloud AI credits** for heavy generative tasks — margin on GPU, privacy preserved.
5. **Wind down ads** as the privacy brand solidifies; a no-ads, buy-once stance is more coherent than
   AdMob on a privacy app.
6. **Fund development with grants + donations:** apply to **NLnet/NGI Zero** (best fit) and run **GitHub
   Sponsors + Ko-fi** — modest but real, and on-brand.

## Sources

Pricing: costbench, fluxnote, TrustRadius, toolradar. Conversion: Adapty, PaywallPro/DEV, First Page
Sage. Ads: Playwire, Coinis, MonetizeMore. Credits: Runway & Pika pricing, ProPicked, eesel. Open-source
business models: FourWeekMBA (GitLab), OpenAlternative, Grafana. Licenses: Creative Commons FAQ, PolyForm,
HashiCorp/BSL, Sentry/FSL, Elastic, SSPL, AGPL (Wikipedia), Open Core Ventures. Irrevocability: CC0
legalcode/FAQ, OSI (MIT), arXiv 2407.13064. Funding: NLnet/NGI Zero, Sovereign Tech Fund, Mozilla MOSS/MTF,
Ko-fi vs GH Sponsors, FUTO Grayjay (precedent). *(Full URLs captured in the research pass; revalidate
before citing publicly.)*
