# Guillotine — backlog

Deferred work, newest at the top. Pick up when prioritized.

## Store: one dialog instead of two, and a way in for the web storefront (2026-07-31)

Three things, from a walkthrough of the no-store-app path.

The overflow menu item read "Azphalt Store" where every neighbour is one word ("Project", "Settings",
"Render"). Now just **Store**.

The no-store-app case asked twice. First dialog: "Azphalt Store isn't installed" → *Get the app* /
*Cancel*. Only if you took *Get the app*, bounced off Play without installing, and came back did a
second dialog appear offering the web store. Two dialogs to surface three choices, with the web store
reachable only by failing at Play first. Collapsed into one dialog carrying all three routes — *Get the
app*, *Use the web store*, *Cancel* — stacked, since those labels won't sit in a row on a phone. The
Play round-trip still re-checks on resume and dives straight into the store app if it's now installed;
if it isn't, the same dialog comes back rather than a differently-worded second one.

Third, and the real gap: taking the web store got you to azphalt.store, where **Install** says the
package "is free — install it from any Azphalt-conforming host." A dead end that points back at the app
you just left. That's not a Guillotine bug so much as a hole in the ecosystem —
`spec/store-app.md` specifies the Android `store.azphalt.action.BROWSE` handoff and states plainly that
the web equivalent is *deliberately unspecified* ("browser install semantics differ enough that it
should be specified separately rather than assumed"). Nothing exists for the web store to call.

Guillotine can only close the host half, and now does: it declares itself an opener for `.azp` packages
(`AndroidManifest.xml` — VIEW on `application/vnd.azphalt.package`, plus `.azp`-suffixed
octet-stream/zip so ordinary browser downloads match, plus a SEND share-sheet route), `MainActivity`
routes the URI through the new `AzpExternalOpen`, and `AzphaltStoreScreen` installs it down the exact
same `AzpHandoffInstaller` path a store app's handoff takes. So: download from azphalt.store, tap the
download, it lands in Guillotine. `singleTask` is what makes an open that arrives mid-session install
into the project already on screen instead of a fresh instance.

**Still open, and belongs to `HereLiesAz/azphalt`, not here:**

- The storefront's **Install** button has nothing to offer a native host. Simplest fix needing no spec
  change: make it download the `.azp` — a plain download reaches the host through the filters above.
  Serving it as `application/vnd.azphalt.package` would make the chooser exact rather than
  extension-matched.
- Better, and the actual missing piece: **specify the web→host handoff** in `spec/store-app.md`. A
  claimable `https://azphalt.store/install/<pkg>` App Link, or an `azphalt://install?...` scheme, would
  let the storefront name and launch a conforming host instead of describing one. That is the spec work
  its own text defers.

## Azphalt Store: delegated acquisition replaces Guillotine's own storefront UI (2026-07-28)

Guillotine used to build and maintain its own browse-grid UI (`AzphaltStoreScreen`'s category chips,
`PluginCard`s, "what's in the store" guide) on top of `AzphaltRepository` talking to the Repository API
directly. That's the ecosystem's `spec/store-app.md` "delegated acquisition" contract's whole reason to
exist: a host that doesn't want to build a storefront shouldn't have to, and azphalt already ships one —
`apps/storefront-cmp` in the `HereLiesAz/azphalt` repo, the reference Azphalt Store app
(`store.azphalt.storefront`), which implements exactly this handoff (`store.azphalt.action.BROWSE`).

Rebuilt `AzphaltStoreScreen` to launch that intent for result instead of rendering a catalog: whichever
Azphalt Store app is installed does the browsing, downloading, and its own check, then hands back a
verified package as a content URI. Guillotine still re-verifies from scratch (`AzpHandoffInstaller`,
replacing `AzphaltStoreState`) — the spec is explicit that a store app is a convenience, not a trust
anchor, so integrity/signature/publisher-continuity checks all run again on the actual bytes received,
same as before. No Azphalt Store app installed degrades to a prompt to get it (Play listing) or open
the web storefront directly — never a fallback to Guillotine fetching and rendering its own catalog.

Removed as dead weight once nothing else called them: `AzphaltRepository`'s catalog/download HTTP
client (`fetchCatalog`, `download`, `RepoPackage` parsing) — only the pinned flagship signing key and
web-store URL survive, moved to `AzphaltTrust`. The AI-model install flow (`AzpModelInstall`, `Sheets.kt`
on both platforms) was already independent of this and is unaffected.

## Beat-sync tools already implement the trending "auto beat zoom" effect — now has real test coverage

Checked whether the "beat-synced auto zoom" effect real editors ask for (zooms that hit exactly on
drops/kicks/snares) needed building from scratch. It doesn't —
`McpTools.applyOnBeat()` (`apply_on_beat` tool) already does exactly this: keyframes a video clip's
`SCALE` 1.0 → 1.12 → 1.0 around every beat/downbeat/onset of a chosen audio clip, on-device, via
`BeatAnalyzer`'s real spectral-flux onset detection + autocorrelation tempo estimation. `flash`
(brightness pulse) and `shake` (offset jitter) variants exist too — covering three of the most
commonly requested "impact" edit effects already. Reachable today via the AI assistant (e.g. "add a
beat zoom to this clip").

The actual gap was **zero test coverage anywhere** for this: `BeatAnalyzer` (real DSP — FFT, spectral
flux, autocorrelation tempo, phase-aligned beat grid, downbeat anchoring) had never been run against
anything but real device audio. Added `BeatAnalyzerTest.kt`: builds a synthetic click track at a known
BPM and confirms the analyzer recovers that tempo, finds the right beat count, spaces beats evenly at
the beat period, and groups downbeats correctly — all pass, confirming the implementation is
genuinely correct, not just plausible-looking.

Not pursued: a true audio-reactive **shader** (as opposed to this existing keyframe-based approach)
isn't possible in the current asset pipeline — ISF/GLSL shaders explicitly reject audio inputs
(`GlslShader.kt`), so "beat-synced" effects have to stay in the keyframe/MCP-tool layer, which is
exactly where this already lives.

## Music licensing search (asked, not built): can't integrate TikTok/Instagram/YouTube "approved
## music" catalogs the way a user might expect

Researched whether Guillotine could let a user search and pull in "platform-approved" music (the
kind TikTok/Instagram/YouTube let creators add in-app) for use in an edited/exported video. Verified:
- TikTok's Commercial Music Library has a real developer API, but its own terms restrict Commercial
  Sounds to use **within TikTok**, shared via TikTok's own sharing features — use outside TikTok needs
  separate licensing directly from the rights holder.
- Instagram's Audio API lets an app search Meta's catalog and attach an `audio_id` to a Reel, but only
  as part of **publishing that Reel through Meta's own Content Publishing API** — it doesn't hand over
  a usable audio file for an independently exported video.
- YouTube Audio Library has genuinely free-to-use tracks but no official API — only unofficial
  scrapers, fragile and against YouTube's ToS.
- The one credible path for "licensed music baked into a video exported anywhere" is a real stock-music
  API with an all-inclusive global license — Epidemic Sound's Partner API is the standout real example
  — but it requires a partner relationship + API key on the user's end, not something buildable without
  that access. Deferred pending a decision on whether to pursue that partnership.

## Azphalt catalog sync check (2026-07-27, second pass) — the whole registry went from unsigned to uniformly signed

The `HereLiesAz/azphalt` repo had a large burst of activity (catalog jumped ~10 → 133 packages, moved
to serving content from a git-committed build instead of a runtime database). Checked whether
Guillotine's client still holds up against the new state:

- **Fixed: every install started requiring an "Install anyway" confirmation again.** The entire
  flagship catalog is now signed with one Ed25519 key (verified independently: downloaded and
  unzipped several live packages via `curl`, all carry the same `signature.json` `publicKey`).
  `trustedKeys` was empty at all three production install call sites (`AzphaltStoreScreen.kt`,
  `app`+`desktop` `Sheets.kt`'s model-install flow) — so the earlier "unsigned installs skip the
  confirmation" fix (2026-07-24 audit) no longer helped, since nothing is unsigned anymore. Pinned the
  key as `AzphaltRepository.FLAGSHIP_SIGNING_KEY` and wired it into all three call sites. The *proper*
  channel for this is the registry's `/.well-known/azphalt-repository.json` `signingKeys` (the spec's
  trust-bootstrap mechanism) — not currently populated with this key upstream (it's wired to a
  different, entitlement-token key instead) — so this is pinned out-of-band until that's fixed; noted
  in the constant's doc comment so it's easy to remove once discovery works.
- **Not acted on — informational only:**
  - The old teal-orange/cool-noir/Fold-Lab LUT submissions (merged into `azphalt` earlier this
    session, `submissions/` → `publish-submissions.ts` pipeline) are **gone** from the new 133-package
    catalog — the git-catalog migration replaced the old runtime-published content wholesale. If that
    content should still be live, it needs re-submitting under whatever the new `registry/sources.json`
    / `registry/local` pipeline is now, not the old `/api/publish` flow.
  - New `kind: "pack"` (22 entries, e.g. `com.hereliesaz.azphalt.pack.3d-environment`) has no dedicated
    Store category — falls through `AzphaltStoreState.categoryFor()`'s id-substring checks into the
    generic `"layer-effects"` bucket. A "pack" carries no assets/types of its own (`types: []`,
    `mediaDomains: []`) — it reads like a themed collection/description, not a standalone installable
    effect, so `AzpPluginApplier` correctly reports it "not wired to a clip yet" rather than falsely
    succeeding, but whether it deserves its own category chip or a different browse treatment
    (curated collections?) is a product call, not a bug fix.
  - New `maturity` field ("general"/"mature") on every summary — Guillotine doesn't read it at all.
    Every live package is currently `"general"`, so no urgency, but there's no age-gate if that changes.
  - Paid-package downloads now require a `Bearer` token per the spec (enforced live), and Guillotine's
    `AzphaltRepository` sends no `Authorization` header at all. Zero packages are currently `paid`, so
    this isn't live yet — but the day a real paid/private listing appears, its downloads will 401/402
    until Bearer-token support is added.

## Azphalt Store showed "Free" for paid packages, and generic descriptions (2026-07-27)

Follow-up to the 2026-07-26 registry-base-URL fix. Once the store started hitting the *real*
Repository API root, two client-side gaps in `AzphaltRepository.RepoPackage` surfaced (the previous,
broken endpoint happened to serve a richer legacy shape that masked both):

- The real registry sends pricing as a bare `priceStatus: "free"|"paid"` string with **no** dollar
  amount at the browse-list level (per `spec/repository-api.md`) — never the `price: {amountCents,
  currency}` object `RepoPackage` was built around. Since `isFree` only ever checked `price`, a
  `priceStatus: "paid"` package with no `price` object read as free — the Store displayed "Free" on
  a package the registry explicitly marked paid. Fixed: `isFree`/`priceLabel` now check `priceStatus`
  first, falling back to the legacy `price` shape when present; a paid package with no known amount
  now shows "Paid" rather than a wrong "Free" or a dollar figure it doesn't have.
- Every catalog card also showed the generic "An azphalt plugin." placeholder instead of a real
  description — this one was a genuine bug in the **registry itself**
  (`HereLiesAz/azphalt#138`, drafted): `GET /packages`'s summary response was missing the flat
  `description` field (present on detail, and required "always present" by spec) — a one-line
  omission in `toSdkSummary()`, not anything wrong on Guillotine's side. No client-side change
  needed here once that PR lands.

## Azphalt Store install failed for every real package: wrong registry base URL (2026-07-26)

Reported on a real device: every install ("Film Emulation LUTs", "Selfie Segmentation", confirmed
live for others too) failed with `"invalid package: azp: manifest.json is missing"`. Root cause:
`AzphaltRepository.DEFAULT_BASE_URL` was `https://www.azphalt.store/api` — the Next.js storefront's
own **internal** route namespace, where only `/api/packages` happens to exist (the storefront's own
catalog fetch). That's the trap: browsing worked, so the URL looked right, while
`/api/packages/{id}/versions/{version}/download` and `/api/.well-known/...` fell through the SPA
catch-all and returned the storefront's `index.html` — HTTP 200, so not even caught as a transport
error — which `AzpPackage` then unzipped to nothing and correctly, but confusingly, rejected as a
missing manifest. Verified live against the real registry: the actual Repository API root has **no**
`/api` prefix — `GET /packages` there returns the spec's `{ "packages": [...] }` envelope, and
`GET /packages/{id}/versions/{version}/download` returns a real `.azp`. Fixed by dropping `/api`
from `DEFAULT_BASE_URL`. Also hardened `AzphaltRepository.download()` to reject a non-ZIP HTTP-200
body itself (checking for the `PK` signature) with an error naming the actual problem — "did not
return a package (got an HTML page instead) — check the registry base URL" — instead of letting a
routing bug like this one surface as a confusing package-integrity error three layers downstream.

This was found *after* the two fixes below (installs merely appeared to fail differently
depending on where a user hit the bug — this one blocks it earlier, at download, for every
package) — so with this fixed, the full loop (browse → install → apply) should now work end to end
for the current live catalog.

## Azphalt Store install was a no-op past the download (2026-07-25)

Follow-up to the 2026-07-24 audit's Azphalt Store trust-verification fix: install itself worked
(bytes download, verify, land on disk), but **using** an installed package didn't. `onApplyPlugin`
as wired from `AzphaltStoreScreen` (`NleScreen.kt`) only stamped the selected clip's unread
`azpPluginId` field and closed the dialog — nothing ever read that field for rendering. Two other
bugs compounded it: `AzpInstalledUi.list()` (the "Extensions" clip-panel enumerator, the one other
UI surface that could apply a package) silently dropped every asset that didn't ship a `ui` control
schema — which is **100% of the current live catalog** (the teal-orange/cool-noir LUT submissions
have none, since a static LUT has nothing to adjust) — so that path was equally unreachable for
real content. And `McpTools.applyAzpPlugin` (the AI-assistant tool) reported `"Applied plugin …"`
success for the same inert stamp on anything but a kinetic-typography motion package — a false
positive an agent would relay to the user as done when nothing rendered.

**Fixed:**
- `AzpInstalledUi.list()` no longer requires a `ui` schema — a schema-less asset still gets a panel
  (an empty one, no controls), since it can still be applied, just with no adjustable params.
- New `AzpPluginApplier` (`app/.../ui/AzpPluginApplier.kt`) is the one apply implementation both the
  Store and the MCP tool now call: motion packages bake real caption keyframes (`TEXT` clips only,
  via the existing `KineticTypographyPicker`), asset packages (shader/LUT) write real render filters
  (via the existing `AzpAssetApplier`), and anything else (bare `code`/`app`/`mcp` packages) returns
  an honest "not wired to a clip yet" message instead of a false success.
- The Store's install flow now actually applies the package to the selected clip (or, if none is
  selected, says so instead of silently no-opping) and only auto-closes on a real success.

**Still not wired to anything, by design scope of this fix** (unchanged from the 2026-07-24 audit):
`code`-kind packages (`AzpCodeRuntime` sandbox has no caller in production code at all), `app`-kind
companion apps (nothing reads `manifest.app` to launch an intent/PWA), and `mcp`-kind packages
(nothing reads `manifest.mcp` to register tools with the assistant — `McpTools`/`DesktopMcpTools`
are still a fixed, hard-coded set). These are real features, not bugs in the fixed path; `AzpPluginApplier`
reports them honestly as unsupported rather than silently pretending they work.

## Full codebase + azphalt audit (2026-07-24)

A repo-wide audit (Guillotine `shared/`, `app/`, `desktop/`, tests/CI, plus the sibling
`HereLiesAz/azphalt` spec/runtime repo). Two stale items below were corrected; everything else in
this section is newly surfaced. Not yet triaged into "confirmed"/"deferred" — read each item on
its own merits.

~~**🔴 `app/` currently does not compile on `main` at all.**~~ **Fixed.** `GuillotineApplication.kt`,
`MainActivity.kt`, `CrashReporter.kt`, and `Sheets.kt` all reference `BuildConfig.ADS_ENABLED`,
`BuildConfig.UPDATER_ENABLED`, and `BuildConfig.DEFAULT_CRASH_RELAY_URL`, none of which were defined
anywhere — `app/build.gradle.kts` had no `buildConfigField` for any of them (only `GH_TOKEN`).
`git log -S` traced this to `be9f7a6` ("Refactor build.gradle.kts for versioning and dependencies"):
it removed the `productFlavors`/`flavorDimensions` block (and the per-flavor `buildConfigField`s
that lived inside it, plus the top-level `DEFAULT_CRASH_RELAY_URL` field) while leaving
`app/src/github/` (the flavor source set — `AndroidManifest.xml`, `update_file_paths.xml`) still on
disk and every `BuildConfig.*` reference in source dangling. The `be9f7a6` diff also carries other
tells of a copy-paste from a different project of the author's (a `GraffitiXR` app): a stray
`HereLiesAz/GraffitiXR` repo name in a security comment (also fixed) and `:core:nativebridge`/
`libgraffitixr.so` references in the (harmless — no matching top-level `externalNativeBuild.cmake`
declaration, so it's inert) NDK config comments, left as-is since untangling unrelated dead config
isn't this fix's job. Restored the `productFlavors` block (`play` — ads on, no updater, default;
`github` — ads off, updater on) and the `DEFAULT_CRASH_RELAY_URL` field verbatim from before
`be9f7a6`, which also makes `app/src/github/` a live source set again (AGP auto-discovers
`src/<flavorName>`) and restores the ad-free-on-GitHub / ad-supported-on-Play split and self-updater
gating that README.md describes. The `unit-tests` CI job's task name (see Bugs, below — it was
"fixed" to `testDebugUnitTest` right before this was found) is reverted back to
`testGithubDebugUnitTest`, since the flavor now legitimately exists again; `release-apk.yml`
(`assembleGithubRelease`), `release-aab.yml` (`bundlePlayRelease`), and `merged-build.yml`'s
`assembleGithubDebug` were already assuming the flavors existed the whole time, so this also
unblocks them. `:shared:test` passes; `:app:compileGithubDebugKotlin` couldn't be verified locally
(no Android SDK in this environment) — confirmed on CI, which surfaced a second `be9f7a6` casualty:
`packaging.jniLibs.pickFirsts` had also been trimmed down to just `libc++_shared.so`, dropping the
rules for `libtensorflowlite_jni.so`/`libtensorflowlite_gpu_jni.so` and
`libonnxruntime.so`/`libonnxruntime4j_jni.so` (onnxruntime-android and the sherpa-onnx AAR both
bundle the latter pair) — `:app:mergeGithubDebugNativeLibs` failed with a duplicate-file error for
`libonnxruntime.so` until those were restored too. Both are now back verbatim from before `be9f7a6`.

**Security**
- ~~**Azphalt Store install path skips trust verification for non-model packages.**~~ **Fixed.**
  `AzphaltStoreState.install()` now calls `AzpPackage.verifyTrust` (integrity + signature + trust
  store) instead of the old integrity-only `AzpPackage.load`, and accepts an `AzpPublisherPins`
  instance for trust-on-first-use publisher pinning — the exact protection `AzpModelInstall.install()`
  already had, now shared by both install paths (a package id is trusted/pinned the same way whether
  it arrived via the model-install flow or the general store). Two new `InstallResult` variants,
  `Untrusted` and `PublisherChanged`, replace the old silent-install-if-integrity-passes behavior;
  `AzphaltStoreScreen.kt` surfaces both as confirmation dialogs (mirroring `Sheets.kt`'s existing
  untrusted/publisher-change UX for AI-model installs) rather than a soft snackbar note. Both flows
  share one pins file (`azp-publishers.json`) since a package id's trust is one continuity, not two.
  Covered by `AzphaltStoreStateTest.kt` (trusted-signer success, unsigned/untrusted-signer requiring
  explicit approval, tampered-package rejection, and publisher-change detection + re-pin-on-approval).
- **Desktop self-updater has no integrity check before running the downloaded installer.**
  `UpdateChecker.download()` (`shared/.../update/UpdateChecker.kt:128-154`) and
  `DesktopUpdater.launchInstaller()` (`desktop/.../platform/DesktopUpdater.kt:73-80`) stream the
  GitHub Release asset to disk and hand it straight to `Desktop.open()` — no checksum or signature
  check. Combined with installers shipping unsigned (see Desktop follow-ups below), a compromised
  release asset or CDN edge would run unverified code. At minimum, verify a published SHA-256
  before launch.
- **CI grants broad write perms to no-op workflows.** `.github/workflows/jules-agent.yml` and
  `jules-auto-merge.yml` are literal stubs (`run: echo "Jules invocation placeholder..."`) but
  request `contents: write`, `issues: write`, `pull-requests: write` and trigger on real repo
  events — unused attack surface.

**Bugs**
- ~~**CI's `unit-tests` job was broken on `main`, not just here.**~~ **Fixed.**
  `merged-build.yml` ran `testGithubDebugUnitTest`, referencing the `github`/`play` product-flavor
  split that `be9f7a6` had (accidentally, see above) stripped from `app/build.gradle.kts` — so the
  task didn't exist and every CI run since `#211` failed red before ever reaching compilation. The
  flavor split itself turned out to be the real bug (`release-apk.yml`/`release-aab.yml`/
  `merged-build.yml`'s release job all still assumed it existed); restoring it made
  `testGithubDebugUnitTest` valid again, so that's what the task name reverted to — a same-named
  task, just a real one this time instead of a stale reference.
- **Cancelling a background operation leaves the UI stuck.** `OperationController.kt:119-131`:
  the `CancellationException` catch block is empty and never calls `onComplete()`/`onError()`, but
  every caller only clears its "busy" flag inside those callbacks. Cancelling analysis
  (`NleScreen.kt:351/357-368`) leaves the "Analyzing…" state forever; cancelling export
  (`NleScreen.kt:763/767-798`) leaves `exporting = true` forever, and the export sheet becomes
  **undismissable** since `onDismiss` is gated on `!exporting`.
- **Desktop `DesktopSegmenter` silently no-ops on inference failure**, contradicting the
  "fails loudly, never fakes" invariant: `matte()`/`portraitBlur()`
  (`desktop/.../media/DesktopSegmenter.kt:23-24,29`) wrap inference in
  `runCatching { … }.getOrDefault(img)`, so `replace_background`/`apply_bokeh` silently render the
  un-matted/un-blurred frame with no error if the model file is later missing or corrupt (model
  *path* is validated at tool-call time, but not at every render).
- **`transcribe_precise` has a different contract on desktop than Android for the same tool
  name.** Android returns `{"text": …}` and is read-only; desktop (`DesktopMcpTools.kt:804`)
  silently dispatches to the timeline-mutating `transcribe_clip` path instead and returns
  `{"captions", "clipCount"}` — no `text` field. The tool's own schema description also claims
  desktop returns an error pointing at `transcribe_clip`, which isn't what actually happens.
- **`GlslToSksl.kt` drops alpha on `.rgb`-only shaders (desktop only).** `rewriteMain()`
  (`shared/.../media/GlslToSksl.kt:151-154`) initializes `_fragColor` with alpha `0.0`; a GLSL
  shader that only writes `gl_FragColor.rgb` (a common ISF idiom the surrounding comment says is
  supported) renders/exports fully transparent on desktop. No test fixture exercises an `.rgb`-only
  shader, so `GlslToSkslTest.kt` doesn't catch it.
- **`extensions.yml` has a YAML indentation bug**: `with: node-version: 22`
  (`.github/workflows/extensions.yml:25-30`) is nested under `actions/checkout@v7` instead of the
  `actions/setup-node@v7` step above it — `setup-node` gets no version pin.
- Desktop JavaCV resource leaks: `grabber.start()` / `filter.start()` / `recorder.start()` calls in
  `DesktopExporter.kt` (`exportAudio`, ~:264-268), `DesktopMediaDecoder.kt`, and
  `DesktopFfmpegFilter.apply()` (`:31-56`) sit **before** their `try/finally` release block, so a
  failure on a corrupt/unsupported user-imported file leaks the native grabber/filter/recorder.
  `DesktopFfmpegFilter.apply()` is worst — three `.start()` calls outside the guard.

**Incomplete / mislabeled features**
- **`AI_ROADMAP.md`'s "desktop is at 66/66 functional tools" is inaccurate.**
  `analyze_clip_with_reference` and `denoise_clip` are unconditional stubs
  (`DesktopMcpTools.kt:762-763,791-792` → `visionToolUnavailable()`) regardless of model config.
  `denoise_clip` is the more notable gap: a model slot already resolves
  (`ModelResolver.kt:40` → `denoiseModelPath`/`gtcrn_simple.onnx`) but the handler never calls it —
  wired plumbing, unwired tool.
- **Several `DesktopMcpTools.kt` tool descriptions/comments are stale**, telling an MCP client a
  tool doesn't work when it does: `apply_shader` (`:267-273`, actually renders via
  `DesktopShaderPass`), `remove_fillers` (`:167-171`, actually works via Vosk), and "honest stub"
  comments at `:522, 568-569, 760, 784-785` covering `add_reference`, `blur_faces`,
  `replace_background`, `find_highlights`, `apply_bokeh`, `auto_reframe`, `search_clips`,
  `caption_frame`, `apply_image_effect`, `remove_object_generative` — all of which now have real
  implementations. An agent reading these descriptions may refuse working tools.
- **`TOOLS.md` undercounts the tool surface** — `list_azp_plugins`, `apply_azp_plugin`,
  `clear_azp_plugin` are real dispatchable tools (`McpTools.kt:792,799,810`,
  `DesktopMcpTools.kt:705-729`) missing from the documented list.
- Desktop-only stubs not yet in this doc: prompt-driven clip analysis ("keep shots with a face")
  and Leonardo image generation are both hard "not available on desktop" stubs in `NleScreen.kt`
  (~:269, ~:492-496), with no fallback.
- **Timeline edge-trim confirmed to have no snapping** (`Timeline.kt:843-874`) — the long-press
  trim handler commits raw pixel deltas directly, never calling `snappedDeltaMs`. Confirms the
  follow-up noted below under on-device verification.
- **Azphalt Store UI (in-app) has no in-app Pause/Resume control** — only the system notification's
  action buttons drive `OperationController`; no Compose UI observes its state, so a user who
  pauses from the shade sees no paused indicator on return to the app.
- Dead one-tap model download: the `LOWLIGHT` model URL in `OnDeviceModels.kt:475` (MIRNet via
  TF Hub) 404s — TensorFlow Hub has been effectively decommissioned. `apply_image_effect(lowlight)`
  can't be one-tap installed as documented.

**Corrected stale doc claims**
- ~~Caption background box missing from export~~ — **already fixed.** `CaptionOverlay.kt:41-44`
  sets a `BackgroundColorSpan` scrim matching preview, landed in the same commit that (incorrectly)
  added this backlog item. Struck below.
- ~~Desktop "Auto-update framework… currently users download by hand"~~ — **already shipped.**
  `DesktopUpdater.kt` + `UpdateChecker.kt` check GitHub Releases on launch and offer to
  download+run the installer, matching the README claim. The real remaining gap is the missing
  integrity check called out above under Security, not the absence of an updater. Corrected below.

**Process / test coverage gaps** (see also existing entries)
- `shared/.../mcp/` (dispatcher, protocol, crypto, relay client — 6 files) has zero tests; MCP tool
  dispatch is completely untested.
- `desktop/` has no test directory at all; `app/` has exactly one test file (`ClipPanelContributionsTest.kt`)
  — `Exporter.kt`, billing, and the AI tool layer (33 files) are all untested on Android too.
- No lint/static-analysis step in any CI workflow (no ktlint/detekt, no `./gradlew lint`).
- `material3 = "1.5.0-alpha24"` and `composeUi = "1.12.0-beta02"` are pre-release UI toolkit
  versions pinned in a shipping app.
- No `fastlane/metadata/.../changelogs/` directory and no root `CHANGELOG.md` — Play release notes
  aren't automated per version.

**azphalt (sibling repo, `HereLiesAz/azphalt`) — informational, not actionable here**
- Real, substantive implementation overall (not spec-only): a working QuickJS-in-WASM sandbox with
  capability gating + timeout/memory limits (`packages/runtime-wasm`), a real reference registry
  server + client, 18 working format importers, and a conformance suite that tests capability/
  never-list enforcement, not just `.azp` parsing. All packages are pre-1.0 (0.1.x–0.2.x), which
  the authors flag themselves.
- Weakest link is **registry-side content trust**: the `submissions/` PR-CI only checks package
  *structure*, not the `scanPackage` security sweep that runs at actual registry-publish time;
  payload static analysis (checking a code module's real imports against declared capabilities) is
  explicitly spec'd as "planned… not yet implemented" (`spec/marketplace-integrity.md:61-64`).
  Unsigned packages are accepted.
- Root `SECURITY.md` is an unfilled GitHub template (generic placeholder text, invented version
  numbers) — notable for a trust-and-safety-focused project.
- `packages/registry-store-vercel` is versioned `1.0.0` while every other package sits at
  0.1.x–0.2.x — likely unintentional inconsistency.
- Guillotine's own `AzpCodeRuntime` correctly does *not* fake code execution yet (returns
  `Unavailable`, never a fake `Ok`) — consistent with azphalt's WASM runtime existing but not yet
  being the thing blocking Guillotine; the integration, not the standard, is what's pending.

## Export parity follow-ups (confirmed by audit)
Surfaced by a codebase audit comparing the preview and export pipelines in detail. All filters,
all 12 keyframe properties, background removal, audio effects, multi-track compositing, and
crossfade are at full parity. The following gaps remain:
- **Caption text size differs:** preview uses `14.sp`; export uses `AbsoluteSizeSpan(64)`. The
  relative proportions won't match unless compensated.
- **Quality/FPS settings not wired into export:** `GlobalSettings.quality` and `.fps` exist in the
  model but `Transformer.Builder` never calls `setVideoFrameRate()` or any resolution/bitrate
  configuration — they have no effect on the output.
- **AI edit segments play through in preview:** removed ranges are correctly cut from the export
  (via `TimelineMath.keptRanges`) but play normally in preview (`syncPosition` does a simple linear
  seek). May be deliberate (show full source with proposed cuts highlighted).
- **Project-level crop not shown in preview:** the `Crop` from `GlobalSettings` is applied in export
  (`VideoEffects.geometry()`) but preview only applies aspect ratio. May be deliberate.

## Audit follow-ups (deferred — need a device or a design call)
Surfaced by a codebase audit. Everything that was a clear, safe bug has been fixed. The rest was
left because it needs on-device verification or is a design decision:
- **Export: background-removed video clips contribute no audio** (`Exporter.kt` — foreground/bg-removed
  clips are added only as the matte overlay, never as sequence items, and aren't in `audioClips`).
  Confirm whether a bg-removed clip's own audio should still export, then include it if so.
- **Keyframed opacity / export crossfade via `RgbMatrix[15]`** (`VideoEffects.kt` `FadeInAlpha`/
  `KeyframeAlpha`): verify on-device that writing only the alpha term actually changes output alpha —
  if not, opacity keyframes and dissolves are silent no-ops.

## Needs an on-device verification pass (built; untestable in CI)
Implemented but never run on a device — confirm and tune:
- **Multi-track compositor** (preview `PreviewPlayer` + export `Exporter`): one layer/sequence per
  video track, stacked bottom-to-top; per-track **crossfade** of overlapping clips; a background-
  removed clip on an upper track showing lower tracks through its matte (composition-level overlay).
  Verify leading-gap alignment, N-sequence compositing, and alpha-blend dissolve on Media3 1.10.1.
- **Background operations** (`operation/OperationController` + `OperationService`): foreground-service
  notification, Pause/Resume (analysis + generative), Cancel, and that work survives backgrounding.
- **Long-press edge trim** (`Timeline.kt`): gesture layering vs. move/keyframe handles; re-extend
  bounds; linked-audio sync. **Follow-up:** snap the trimmed edge to playhead/clips/grid.
- **3 fps sampling + ±5-frame extension** (`MlKitProvider.scanVideo`): cut tightness + speed.
- **Export fidelity** (keyframed opacity/scale via `RgbMatrix`/`MatrixTransformation`, keyframed
  volume via `KeyframeVolumeProcessor`, caption/matte overlay timing after cuts, audio gain/pan
  levels): eyeball compositing, centering, and overlay sync.

## Export follow-ups
- **Cross-process resume**: an OS kill currently drops an in-flight operation (by design). Persisting
  a checkpoint to resume analysis/generative after relaunch (and a resumable/segmented export) is open.
- **Pausable export**: Media3's `Transformer` can't pause an encode, so export is cancel-only.

## Desktop follow-ups (v1 ships unsigned, single-arch)

The desktop apps (`.dmg` / `.msi` / `.deb`) ship in every GitHub Release via the CI matrix in
`.github/workflows/release-desktop.yml`. Remaining polish:

- **Signing / notarization** — macOS Developer ID (Apple Developer account required), Windows
  code signing (CA certificate required). Without these, users see a "unknown developer" warning
  on first launch.
- **Universal macOS binary** — `macos-latest` gives us Apple Silicon; Intel Macs need a second
  runner (or `lipo`-ing two builds).
- **AppImage / Flatpak / Snap** — `.deb` covers the mainstream case; broader Linux coverage is
  open.
- ~~Auto-update framework~~ — **already shipped** (`DesktopUpdater.kt` + `UpdateChecker.kt` check
  GitHub Releases on launch and offer to download+run the installer). The real remaining gap: no
  checksum/signature verification of the downloaded installer before launching it — see the
  Security items in the 2026-07-24 audit section above.
- **On-device ML on desktop** — the ONNX-Runtime-for-JVM foundation has landed: stem separation
  (Spleeter), speech captions (Vosk), audio sync, and the color/LUT render all run on-device on
  desktop. The remaining on-device gap is the **vision / face / speech-model tools** (image
  labeling, face detect/segment, Whisper ASR, TTS, diarization, VLM captioning). Each needs a
  desktop ONNX model wired the same way as stems: a model path in Settings + an inference helper.
  `search_clips` is the first wired (ONNX ImageNet labeler); the rest return an honest "needs a
  model" stub until their model is bundled/pointed at. Cloud BYO still works for all.
