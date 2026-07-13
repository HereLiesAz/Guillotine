# Guillotine — Governance & Charter

This document states how Guillotine is governed, what it values, and how those values are meant to
carry forward as others build on it. It complements — and does not replace — the license
([`LICENSE`](LICENSE), GNU AGPL-3.0), the additional §7 terms in [`NOTICE`](NOTICE), and the non-binding
[Open-Source Open-Mind covenant](docs/OPEN-SOURCE-OPEN-MIND.md).

Guiding principle: **govern participation and the name, never anyone's freedom to use the code.**
Everything here is designed to stay open-source-compatible — the AGPL freedoms are untouched.

---

## Values

1. **On-device by default, private by architecture.** The core promise is that your footage never has
   to leave your device. Any cloud path is opt-in and exchanges the minimum (text, not your frames).
   Features that would quietly break this invariant do not ship as defaults.
2. **Free and open source.** The whole editor is AGPL-3.0. Openness is not a marketing layer; it is the
   point — it is what makes the project trustworthy and adoptable.
3. **Honest software.** No dark patterns, no faked capabilities, no silent data collection. A stub says
   it's a stub; a limitation is documented; a claim is one we can stand behind.
4. **Powerful, not gatekept.** A real editor that does what you ask. Depth for power users; a simple
   on-ramp for everyone else. Capability is not held hostage to a subscription.

## How decisions get made

- **Maintainer-led, in the open.** The founder/maintainer is the final decision-maker on direction and
  merges today. Substantial changes are discussed in public issues; larger ones as a lightweight RFC
  (an issue tagged `rfc`) so the reasoning is on the record.
- **A voice, not a veto.** The intent — for the project and for anyone who later stewards it — is that
  the founder always keeps *a seat at the table*, a standing right to be **heard**, not unilateral
  control over what others do. Contributors and forks are free; governance is about being listened to,
  not about commanding.
- **Transparency.** Decisions that shape the project (roadmap direction, license posture, the meaning of
  the brand) are explained, not decreed silently.

## The founder's continuing voice (tied to the name, not the code)

The founder's values and voice are meant to persist even as the project grows or others build on it.
The FOSS-compatible way to do that is to attach the obligation to **use of the Guillotine name**, which
is reserved (see [`NOTICE`](NOTICE) §7(e)) and enforceable via trademark — **not** via the code license,
which stays a pure AGPL grant.

- **The name is reserved.** The name "Guillotine" and the application icon/logo are not licensed for
  modified or redistributed versions. A fork may take the code; it may not take the brand.
- **Standing invitation while operating under the name.** Any person or organization that operates a
  product or project **under the Guillotine name** extends a **standing invitation for the founder to be
  heard, in an official capacity, at that entity's strategic or directional reviews** — a voice, not a
  vote or a veto. It creates no duty to obey, answer, or act; only to *listen, once, when the founder
  speaks.*
- **It ends when the name does.** This obligation lasts **only as long as an entity operates under the
  Guillotine name.** Rename and walk away and the obligation ends with the name — which is precisely
  what keeps this a brand term, not a use-restriction, and therefore open-source-compatible.
- **The good-faith companion.** [OSOM](docs/OPEN-SOURCE-OPEN-MIND.md) expresses the same wish as a
  non-binding courtesy that rides with the *code* itself (read one statement, once). GOVERNANCE states
  the enforceable-via-trademark layer that rides with the *name*. Same value, two mechanisms.

## Contributions

- **Inbound = outbound, plus a CLA.** Contributions are accepted under the project's license, and
  contributors sign the [Contributor License Agreement](CLA.md). The CLA keeps the maintainer able to
  hold (or be granted) rights to 100% of the code, which is what preserves the ability to relicense
  future versions or offer a commercial exception for the eventual paid/hosted layer (see
  [`docs/strategy/FINANCIAL.md`](docs/strategy/FINANCIAL.md)). Without it, the first outside merge would
  fragment ownership and foreclose that path.
- **How to contribute.** See [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Stewardship, later

If the project grows enough to warrant it, the durable home for these values is a small
nonprofit/foundation whose bylaws give the founder a **permanent seat** and hold the trademark and the
charter. Until then, the maintainer holds them directly. Adopting a steward entity does not change the
values above; it institutionalizes them.

## Honest limits

No FOSS-compatible mechanism reaches past a rename. A hard fork that renames, re-badges, and walks away
escapes every clause here — that is the definition of a fork, and the price it pays is the brand: the
users, the trust, and the momentum that live with the name. Reaching further would require a
use-restriction, which would forfeit open-source status — a trade this project will not make.

---

*Values in the code and the `NOTICE`. Voice in the name and in OSOM. Freedom, always, in the license.*
