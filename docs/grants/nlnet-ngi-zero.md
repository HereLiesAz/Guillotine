# NLnet / NGI Zero — application draft

> **Status: draft for the maintainer to review and submit.** NLnet runs rolling open calls
> (NGI Zero Commons Fund / NGI Zero Core). This is prepared answer text mapped to the fields on the
> application form at <https://nlnet.nl/propose/>. Grants are **€5,000–€50,000**; outputs must be
> free/libre and open source (AGPL qualifies). Trim to the form's length limits before submitting;
> revalidate every factual claim (see `docs/strategy/FINANCIAL.md` §E).

---

## Project name

**Guillotine — a private, on-device AI video editor**

## Website / repository

- Repository: <https://github.com/HereLiesAz/Guillotine>
- License: GNU AGPL-3.0 (the whole editor)

## Requested amount

**€30,000** (adjust to scope; NGI Zero grants run €5k–€50k, milestone-based).

## Abstract (≈1200 characters)

Guillotine is a free, open-source, multi-track video editor for Android and desktop (macOS, Windows,
Linux) whose defining guarantee is **your footage never leaves your device.** All frame and audio
analysis — labeling, face detection, subject segmentation, speech-to-text, highlight detection —
runs on-device. Optional cloud AI models are *controllers only*: they drive the editor as text over
a local tool protocol and never receive the user's clips or frames.

This inverts the dominant model. The market-leading editor (CapCut) recently granted itself a broad,
irrevocable license to user content and asserted rights to biometric data; its business *is* the
cloud and the data. Guillotine cannot betray its users that way, because the private path is
enforced architecturally, not promised in a policy.

The funding would harden and verify the on-device AI pipeline across both platforms, ship
best-in-class **on-device automatic captions** (simultaneously the most-requested feature and a
privacy proof-point — transcription never touches a server), and complete a vendor-neutral, portable
extension standard so the ecosystem is not locked to any single vendor.

## What is the project you would like to work on? Why is it important?

Video editing is where a huge amount of personal and client footage is handled, and it has quietly
become one of the most privacy-hostile categories of consumer software: uploads to opaque servers,
content-licensing land-grabs, biometric claims, subscription lock-in, and geopolitical off-switches.
People doing brand/client work now have a concrete, ownership-driven reason to leave — but the
open, private alternatives are either desktop-only, browser-sandboxed, or lack the AI features that
now decide adoption (auto-captions, background removal, reframing, silence/filler removal).

Guillotine already exists as a working pre-1.0 editor: a real multi-track timeline with keyframes and
bezier easing, transitions, color grading, text overlays, and true on-device export (Media3
Transformer on Android, FFmpeg on desktop). Its AI tools run on-device (ML Kit / TFLite / Vosk on
Android; ONNX Runtime for JVM on desktop), and cloud LLMs, when a user brings a key, act purely as
text-level controllers via a local MCP (Model Context Protocol) server. The desktop tool surface is
already ~61 of 66 tools functional on-device.

The work we propose:

1. **Harden and independently verify the on-device AI pipeline** end-to-end on both platforms —
   parity, export correctness, and honest documentation of every limit (no faked capabilities).
2. **Ship best-in-class on-device auto-captions** — multi-language, styled, one-tap — proving that
   the single most-demanded feature can be delivered without a server.
3. **Complete the portable extension standard (azphalt)** — a vendor-neutral `.azp` package format
   plus importers and an open registry, so extensions (LUTs, shaders, effects) are portable across
   apps rather than trapped in one vendor's silo, and Guillotine becomes a conforming host.
4. **Distribution as a public good** — signed installers and an F-Droid release, so a private editor
   is actually installable without a "unidentified developer" wall or a proprietary store.

Why it matters for NGI: it advances **data sovereignty and privacy by architecture** for a mainstream
task, produces reusable FOSS building blocks (on-device media-AI helpers, a portable extension
format), and demonstrably raises the bar for what a private, local-first application can do.

## Does the project involve development of a new technology, standard, or software?

Yes — and specifically an **open standard** plus reusable software:

- **On-device media-AI building blocks** (segmentation matte/bokeh, face detect/blur via keyframed
  overlays, YAMNet highlight detection, image labeling/embedding, speech-to-text) wired through a
  single cross-platform tool interface, usable beyond this app.
- **azphalt** — a vendor-neutral, portable extension standard: the `.azp` package format, a TypeScript
  SDK, importers that normalize existing asset formats (`.abr`, `.cube`, …) into `.azp`, and a free,
  self-hostable open registry. The explicit goal is that *other* apps can adopt it — the opposite of
  a walled plugin store.
- **Local-first AI control via MCP** — the editor is fully drivable by any AI agent over a local,
  authenticated tool server, with a hard architectural boundary that only text crosses it, never
  media.

## Comparison with the state of the art

- **CapCut / InShot / Filmora (proprietary):** feature-rich but cloud-coupled, subscription-driven,
  and — in CapCut's case — actively hostile to content ownership. None can make the on-device promise
  because their business depends on the data.
- **DaVinci Resolve (free, proprietary):** excellent desktop-pro tool, but closed and desktop-only.
- **Kdenlive / Shotcut (FOSS):** mature open editors, but desktop-only and without the on-device AI
  layer that now drives adoption.
- **OpenCut (MIT, browser):** proves the latent demand for a private editor (~0→~48k GitHub stars in
  under a year) but runs in a browser sandbox and is early on AI.

Guillotine's distinct position: **open source + genuinely on-device AI + mobile *and* desktop +
architectural (not policy) privacy**, with a portable extension standard no closed editor can match.

## Who is behind the project?

A solo maintainer (HereLiesAz), with the project developed in the open under AGPL-3.0. Governance,
values, and a contributor process are documented (`GOVERNANCE.md`, `CONTRIBUTING.md`, `CLA.md`), so
the project is set up to accept and steward outside contributions.

## Proposed milestones & deliverables

| # | Deliverable | Verification |
|---|---|---|
| 1 | On-device AI pipeline hardened + smoke-tested with real models on Android and desktop; every limit documented honestly | Test artifacts + reproducible checklists; CI packaging green on all OSes |
| 2 | On-device auto-captions: multi-language, styled, one-tap; export parity | Demo on real footage; no network calls during transcription (verifiable) |
| 3 | azphalt standard finalized: `.azp` format spec + SDK + importers + open registry; Guillotine as a conforming host | Public spec + a portable extension running in the host |
| 4 | Signed installers (macOS/Windows/Linux) + F-Droid release | Installable signed builds; F-Droid metadata merged |
| 5 | 1.0 release with a changelog and honest feature set | Tagged release + release notes |

## Amount requested & rough budget

€30,000, milestone-based (NLnet pays per accepted deliverable). Predominantly development time on
milestones 1–3; a small allocation for code-signing certificates and release infrastructure
(milestone 4).

## How does the project relate to NGI / the open internet?

It defends **privacy, data sovereignty, and user control** for one of the most personal categories of
media, delivers **reusable FOSS** (on-device media-AI blocks, a portable open extension standard),
and keeps everything **free/libre** end to end. It makes the private, local-first choice not just
possible but *pleasant and mainstream-capable* — which is exactly the internet NGI is funding.
