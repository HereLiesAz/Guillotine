# Guillotine — backlog

Deferred work, newest at the top. Pick up when prioritized.

## Loop tool + playback-region drag: desktop parity, and one real gap found (2026-08-12)

Turned out most of what was asked for already existed on Android, just not on desktop: `playbackRegion`,
`toggleLoop`, and `advancePlayhead`'s region/loop-aware playback (`EditorViewModel.kt`) are `:shared` and
were already fully wired into Android's `Timeline.kt` `Ruler` (drag the ruler strip → replace the
committed region; tap → seek) and toolbar (a Repeat icon toggles `loopPlayback`) — exactly the
"dragging the top strip defines the region, dragging the playhead itself scrubs" behavior asked for.
Desktop had none of it: no loop toggle, and its own `Ruler` treated *any* drag as scrubbing (no
region-drag at all). Brought desktop to parity: `Ruler` now takes `state.playbackRegion` and mirrors the
app side's drag-defines/tap-seeks split exactly, `Timeline.kt`'s region overlay band is the same
translucent-red box, and `EditorToolStrip` got the same Repeat toggle. Zero `:shared` changes needed —
this was a UI-only gap.

**Real gap, not fixed here:** "the playback area is also the rendering area" is not true today on either
platform. `Exporter.export`/`DesktopExporter`'s render path always renders the full document —
`playbackRegion` is read nowhere outside `EditorViewModel`/the two `Timeline.kt`s. Making a set region
double as the render range needs either a trim parameter threaded through the exporter's whole media
pipeline, or a pure `EditorDocument` → sub-range transform (drop/clip every track's clips to the window,
shifting start times and keyframe offsets to match) run before handing the document to `Exporter.export`.
Deliberately not attempted in this pass: it's real logic over `TimelineClip`'s keyframes/groups/crossfade
semantics with no device/emulator here to verify the output actually renders correctly, and export
correctness for every user is a much higher-stakes place to guess wrong than a UI gesture.

## AI settings moved out of Settings into their own menu entry, plus a capability-at-a-glance summary (2026-08-12)

`SettingsScreen`'s "AI Analyzer"/"Generation"/"Transcription" tabs are unambiguously AI setup (model
paths, provider keys); "Advanced" is a genuine mix of AI (model install) and non-AI (backup/restore,
updater, crash relay) controls. A user had no reason to think "Settings" was where model setup lived at
all. Added a `restrictToTabs` param to `SettingsScreen` (both platforms) so the same screen can be
opened scoped to a subset of its tabs without duplicating ~800 lines of tab content and its ~20 local
`var`s: a new **AI** menu entry opens tabs 0-2; **Settings** now opens tab 3 only. Also added
`AiCapabilitySummary`/`DesktopAiCapabilitySummary` — a compact "your setup, at a glance" panel shown
above the AI tabs, answering "is X on right now" (configured-only, not verified-working: a blank path
vs. a set one, not a file-exists/ping check) since the tabs themselves only ever answered "how do I
configure X."

**Not done in this pass:** "Advanced" itself is still mixed (AI model install alongside non-AI backup/
updater/crash-relay controls) — splitting that tab is a real follow-up, not attempted here since it
needs reading the tab's full content to separate safely, which this pass didn't have room for.

## Own Azphalt Store catalog browser is back, on both platforms (2026-08-12)

Reversed the 2026-07-28 decision below ("Azphalt Store: delegated acquisition replaces Guillotine's
own storefront UI"). That decision wasn't wrong on the merits it weighed at the time — a home-grown
storefront is one more thing to maintain, and the ecosystem's delegated-acquisition handoff genuinely
means a host doesn't have to build one. It was made **too early**: before the flagship catalog had
grown past a handful of packages, and before Guillotine's own trust/install machinery (compat checks,
publisher pinning, install-surface routing, state reporting — all the entries below this one) existed
to make an in-app browser more than a bare list. Both have since matured well past the point that
justifies the maintenance cost, so the browser is back.

Verified the real registry shape live before building against it (`curl https://www.azphalt.store/packages`
et al., 2026-08-12): `GET /packages?page=&q=&types=` returns `{packages,total,page,pages}`; `q` and
`types` genuinely filter server-side (confirmed: `types=lut` returns only LUTs), but `type`/`category`/
`kind` are silently ignored despite looking like they should work — worth remembering before assuming
any query param does something just because the server returns 200 for it. 158 live packages today
across 4 asset `types` (`lut`/`shader`/`motion`/`onnx`) and 6 manifest `kind`s (`asset`/`app`/`code`/
`mcp`/`pack`/`skill`).

**Built:**
- `AzphaltRegistry.browse()`/`browseAll()` (`:shared`) — the catalog-list HTTP client `AzphaltRegistry`
  lost in the 2026-07-28 cut (only the download half survived). `browseAll()` walks every page once and
  hands back the whole catalog; at today's size (158 packages, well under a megabyte) that's simpler and
  more responsive than re-querying the server per keystroke/chip-tap, and sidesteps depending on exactly
  which fields the server can filter by.
- Android `AzphaltStoreScreen` now shows its own full-screen catalog browser (search + category chips +
  card list) as the primary UI, replacing the old "always launch the store app / web store" entry point.
  Installing a catalog entry runs the *identical* `AzpHandoffInstaller` verification every other route
  here already used (a browsed package earns no more trust than a downloaded or handed-off one).
  "Use the Store app" / "Browse the web store" survive as a secondary route behind the browser's own
  overflow menu.
- **New: desktop has a Store entry point at all**, for the first time — `DesktopAzphaltStoreScreen`,
  reachable from the menu's new **Store** item. Desktop previously had no browsing UI whatsoever; the
  only azphalt install route was the model-specific file picker buried in Settings → AI Analyzer. Applies
  installed packages via new `DesktopPluginApplier` (`:desktop`), extracted from `DesktopMcpTools`'
  `apply_azp_plugin`/`clear_azp_plugin` handlers (previously ~90 lines of private, unshared logic) so the
  Store's install flow and the MCP tool are the same implementation — mirroring the app side's
  `AzpPluginApplier` parity, which existed for exactly this reason already.

**Deliberately not done in this pass:** no preview-image thumbnails in the catalog cards — neither
platform has an image-loading dependency (Coil, or a desktop equivalent) today, and pulling one in to
show `.png` previews of shaders/LUTs is a real, separate scope decision, not a one-line addition. Cards
show a category/price/maturity text line instead. A `.azp` package's own `preview.image` field
(`AzpPreview`, already modeled in `AzpManifest`) is unused by the browser for the same reason.

## Correction: the versionCode collision below was permanent, not a rare race — fixed (2026-08-08)

The entry directly below this one ("Release workflows named a GitHub Release after a version that
wasn't in the APK") closed with: "since the write to `version.properties` never gets committed
back, `versionCode` isn't a true monotonic build counter across separate CI runs... a known,
accepted tradeoff rather than a silent failure." That was wrong, and live CI logs proved it wrong
twice: Google Play rejected an upload with `"Version code 205921443 has already been used"` — not
once, but again on a **later, separate** run, still computing the exact same number. That's not
`publish_play.py`'s documented same-commit-retry race (which the "already been used → treat as
success" handling genuinely does cover); it was a **permanent** collision that would recur on
every single future build forever.

The actual math: `versionBuild` is `maxOf(the committed file's last versionBuild, gitFloor) + 1`,
where `gitFloor = 205920500 + git rev-list --count HEAD`. At the time of the fix, the repo had 183
commits (`gitFloor = 205920683`) but the *committed* `versionBuild` was already `205921442` — 759
higher. Since nothing ever commits the auto-incremented value back to the repo (confirmed — see the
entry below), every fresh CI checkout of *any* commit re-reads that same frozen `205921442`
baseline and computes the identical `205921443`, and will keep doing so until roughly 759 more
commits land and `gitFloor` finally overtakes it. That's not a tradeoff worth accepting; it's a
future publish blocked indefinitely.

**Fixed** in `build.gradle.kts`: the Build increment is now `GITHUB_RUN_NUMBER` (GitHub Actions'
own per-workflow-file counter — set automatically, strictly increasing forever, needs no commit-back
or repo push at all) instead of a flat `+1`, falling back to `+1` when that env var is absent (local,
non-CI builds — unchanged behavior there). Verified locally: with `GITHUB_RUN_NUMBER=47` simulated,
the computed `versionBuild` jumped straight to `205921489` — comfortably clear of the already-consumed
`205921443` — and every subsequent CI run of the same workflow will keep climbing past whatever the
last one used, permanently. `verPatch` has the same underlying "frozen forever" defect (it also
anchors to the never-committed file value first) but doesn't cause an external failure the way
Build's Play-facing collision did, so it's left alone here — a real but non-blocking follow-up, not
bundled into this fix.

## Release workflows named a GitHub Release after a version that wasn't in the APK (2026-08-08)

Reported as: "versionCode is set at Gradle configuration time, but a later step that increments the
file runs at execution time — after the versionCode is already frozen." That exact shape doesn't
exist in `build.gradle.kts` (its versioning `run {}` block computes `verBuild` and writes it to
`version.properties` in the same configuration-time pass — nothing execution-time reads a stale
value there). The analogous bug was real, just one layer up: in CI bash, not Gradle.

`release-apk.yml`'s **"Prepare Release"** step and `merged-build.yml`'s **"Prepare Release
Variables"** step both ran *after* their job's `./gradlew assemble...` step had already computed
Patch/Build at configuration time and written them to `version.properties` on disk — the values
actually baked into the APK's `versionCode`/`versionName`. Neither step read that freshly-written
file back. Both independently **re-derived their own PATCH/BUILD_NUMBER from raw git commands**
(`git rev-list --count HEAD` for the build number, a `git blame`-anchored recount for patch) —
a second, different, stale approximation of the same numbers Gradle had just computed differently
(`versionBuild` is `git rev-list --count HEAD` **plus** a 205920500 floor **plus** an auto-increment
carried over from `version.properties`; the bash re-derivation had none of that). Net effect: the
GitHub Release's title/tag/filename showed something like `1.1.5.1502` for an APK whose actual
embedded `versionCode` was `~205921443` — cosmetically wrong, and actively misleading if anyone
ever needed to correlate a filed bug report's "app version" against the real installed
`versionCode`.

Fixed both steps to read `versionPatch`/`versionBuild` straight out of `version.properties` (same
`grep`/`cut` already used for Major/Minor) instead of recomputing them — the file is guaranteed
fresh at that point in the job, same working directory, same checkout, no re-clone in between.

**While tracing this, found and corrected two stale/inaccurate comments** describing the versioning
scheme (in `release-aab.yml` and `merged-build.yml`): one claimed `release-aab` "commits the
auto-incremented versionCode back" to the repo — no workflow does (`git log`/grep confirms the only
`git commit` in any workflow is `update-libs.yml`, and that's for `libs/`, unrelated); another
described versionCode as simply `git rev-list --count HEAD`, which was true before the
`version.properties`-based scheme existed but isn't the current formula. Comments now describe what
the code actually does.

**Not fixed, deliberately out of scope of this pass:** since the write to `version.properties`
never gets committed back, `versionCode` isn't a true monotonic build counter across separate CI
runs — every fresh checkout of the same commit recomputes the identical number. This is already
handled gracefully for Play (`publish_play.py` treats "Version code N has already been used" from a
concurrent/duplicate run as success, with a comment explaining exactly this race), so it's a known,
accepted tradeoff rather than a silent failure — but it's worth knowing this is *why* that handling
exists, not just that it does.

## Userflow audit: store→extension, model discovery→download→use, and crop-on-preview (2026-08-08)

Audited three end-to-end flows a real user takes, against the actual code rather than the docs'
description of it. Two were substantially broken; the third (Store install→apply) had a smaller,
verified gap. Fixed what's below; the rest is recorded as follow-up.

**Fixed: the entire on-device Model Manager was unreachable.** `AiSettings`
(`shared/.../ai/AiTypes.kt`) had no fields at all for `agentModelPath`/`asrModelPath`/`vlmModelPath`/…
— every one of the 11+ on-device model slots MODELS.md and SETTINGS.md document in detail. The
`ModelPicker` composable (curated catalog, Download/Resume/✓ Use/Remove) existed fully built in
`Sheets.kt` but was **never called** from `SettingsScreen` — there was nowhere in `AiSettings` to
write a selection into. So `ModelResolver.resolve()` could only ever auto-detect whatever happened to
be on disk in catalog order, silently ignoring which model the user actually picked or typed
(with two or more models of a category installed — e.g. both Gemma 3n E2B and E4B — whichever came
first in `OnDeviceModels.kt` always won, and a hand-typed custom path was completely inert). The
"AI Analyzer" and "Transcription" settings tabs had no picker UI for any category at all.

Fixed by adding the 11 documented path fields + `effectModelPaths` (Settings §5's own field
reference) to `AiSettings`, persisting them in `ApiKeyStore` and the backup/restore bundle, wiring a
`ModelPathField` + `ModelPicker` section for every category into the AI Analyzer tab (and a Vosk
model-path field into Transcription), and making `ModelResolver.resolve()` check the user's chosen
path **first**, existence-verified, before falling back to disk auto-detection. Backup/export also now
reuses `buildSettings()` instead of a second, independently-maintained `AiSettings(...)` literal that
had silently drifted (it was missing `leonardoKey`/`idEmbedModelPath`/etc. entirely).

**Also fixed while in here: on-device Vosk transcription was fully implemented and never called.**
`VoskTranscriber` (decode → recognize → word-timed cues) has existed complete, but `Transcription.transcribe`
— what `transcribe_clip`/`animated_transcribe_clip` actually call — only ever used cloud OpenAI Whisper,
contradicting its own tool descriptions ("on-device Vosk or cloud Whisper") and the Transcription tab's
now-real model-path field. It now tries on-device Vosk first when a model directory is set, falling back
to Whisper otherwise.

**Fixed: Crop & Transform's panel was empty.** The gesture itself worked
(`PreviewPlayer.kt`'s `cropMode` → pinch/drag/twist → `EditorViewModel.transformSelectedClip`), but
`InlineClipTools.kt`'s `EditorTool.CROP` branch was a bare comment — opening the tool showed a title
and nothing else: no instruction that the preview itself is the control surface, no way to see the
current scale/rotation/offset, and no way back to identity short of fighting the same imprecise
gesture in reverse. Added an instructional line, a live numeric readout, and a `resetSelectedClipTransform()`
(new, `EditorViewModel`) button.

**Fixed: azphalt store — desktop's `apply_azp_plugin` silently faked success for shader/LUT
packages.** Only the kinetic-typography branch was real; every other asset kind fell through to
stamping the clip's unread `azpPluginId` field and returning `ok()`. New `DesktopAzpAssetApplier`
(mirroring the app's `AzpAssetApplier`) writes the shader/LUT bytes into the clip's real render
filters, same as Android.

**Fixed: azphalt store — the "Extensions" clip panel had no clip-type gating.**
`AzpAssetContribution.appliesTo` always returned `true`, so a shader/LUT section (and its "Apply to
clip" button) rendered on TEXT/AUDIO clips too, silently writing `shaderPath`/`lutPath` into a clip
whose render pipeline never reads them. Gated to `ClipType.VIDEO`, matching the built-in
`FiltersToolInline`'s existing gate.

**Still open:**
- **Desktop has no direct-preview interaction for Crop & Transform at all** — no mouse-drag/scroll
  equivalent of the Android pinch/pan/twist gesture, and no numeric fallback either (the desktop
  "Crop / transform" toolbar button sets `EditorTool.CROP` but nothing on desktop reads that tool
  state). `scale`/`offsetX`/`offsetY`/`rotation` already render correctly on desktop
  (`DesktopPreviewPlayer.kt`'s `VideoSlot`) — only the *editing* surface is missing. Needs a product
  call on the desktop interaction (drag-to-pan + scroll-to-zoom is the obvious mapping; rotate has no
  obvious mouse-only gesture and may just need a slider).
- **`.azp`-delivered AI models still don't converge with the model-path fields above.**
  `AzpModelInstaller.ModelSlot` (7 values: image labeling, face detect/embed, segmentation, image
  embed, speech-to-text, audio-event) is a disjoint identity scheme from `ModelCategory` (14 values),
  and `Sheets.kt`'s `applyInstalled()` after a `.azp` model install is still a literal no-op ("Now
  handled entirely by ModelResolver" — which, now that `ModelResolver` reads real settings fields
  first, is *more* wrong than before, not less). A `.azp`-installed sherpa ASR bundle also lands as a
  directory while `ModelSlot`'s only wiring assumption is a flat file. Needs the slot enum reconciled
  against `ModelCategory` (or dropped in favor of it) before `applyInstalled` can honestly fold a
  `.azp` model into the settings field the picker above now actually reads.
- **No automated test coverage added for the model-path resolution fix** — `ModelResolver` needs an
  `android.content.Context` and has no existing test harness (Robolectric isn't set up in this repo);
  `EditorViewModel` likewise has no direct unit tests to extend for `resetSelectedClipTransform`. Both
  were instead verified by full-module compiles (`:shared:compileKotlin`, `:app:compileGithubDebugKotlin`,
  `:desktop:compileKotlin`). **Correcting an overstated claim from the first pass of this entry:**
  "the existing test suites, all green" is true only of `:shared:test` (240 tests, none touching the
  changed lines). `:desktop` has **no `src/test` directory at all** (`:desktop:test` is `NO-SOURCE`,
  not a passing empty run), and `:app:testGithubDebugUnitTest` runs exactly one file
  (`ClipPanelContributionsTest.kt`) whose own KDoc says `appliesTo` is "exercised in the app, not
  here" — it doesn't touch `AzpAssetContribution`'s changed gate either. None of this PR's new/changed
  desktop or Android logic has test coverage; only compilation was verified, which is a much weaker
  claim than "tests pass" and should have been stated as such the first time.

## Glee audit of the userflow-audit PR above (2026-08-08, same day)

An adversarial audit of everything the entry above touched, done immediately after merge rather than
before — should have been the other way around. Found four real breaks (three of them the *exact*
defect class the PR claimed to fix, just relocated) and two doc-accuracy problems. All fixed in a
follow-up commit on the same branch/day; recorded here so the pattern (ship a fix, audit finds the
fix reproduced its own bug one level over) doesn't repeat silently.

- **Fixed: the new Crop panel's Reset (and the pre-existing drag/pinch/twist gesture underneath it)
  did nothing for the single most common clip in the app.** `EditorUiState.selectedClipId` is
  `selectedClipIds.singleOrNull()` — `null` the instant more than one clip is selected — and
  selecting an imported video's picture clip also selects its linked shadow audio clip
  (`expandGroups`, same `groupId`), so `selectedClipId` was `null` for exactly the case a user
  actually hits by tapping a video with sound. `transformSelectedClip`/`resetSelectedClipTransform`
  both `return` on that `null` with no error, no disabled state, nothing — while the new panel (keyed
  off a *different* selection rule, `selectedClips.firstOrNull()`) kept rendering a live readout and a
  Reset button that looked functional and were not. This was already true of the gesture before this
  PR touched anything; the new panel just made the inconsistency visible instead of merely silent.
  Fixed with one shared resolution rule (`EditorViewModel.cropTargetClipId()`: first non-`AUDIO` clip
  in the selection, else whatever's selected) used by the gesture, the reset, and the panel's target
  alike, so all three agree — and the underlying gesture now actually works on a video-with-audio
  clip for the first time.
- **Fixed: desktop's rewritten `apply_azp_plugin` reproduced "reports success, changes nothing" in
  three places**, the exact defect it was written to close:
  - The "is this a caption animation?" check only tested whether `AzpMotionInstaller.plan` accepted
    the bytes (true for *any* valid `.azp`, since `plan` doesn't require a motion asset and returns
    an empty `motions` list rather than failing) — so a shader/LUT/model package's id matched
    `isMotion` too, and applying it to a video clip printed "is a caption animation. Select a
    caption…" instead of ever reaching the real asset branch. Fixed by using the shared
    `KineticTypographyPicker.listInstalled` (`:shared`, already used elsewhere on both platforms),
    which correctly requires a resolvable bundled motion.
  - The asset (shader/LUT) branch had no clip-type gate: applying a shader to a TEXT or AUDIO clip
    wrote real `shaderPath`/`lutPath` into a clip whose render pipeline never reads them (captions
    render through a separate overlay; audio has no picture pipeline) and reported "Applied" —
    changing nothing, the identical user-visible symptom the PR's own commit message described.
    Gated to `ClipType.VIDEO`, with an honest "select a video clip" message otherwise.
  - `clear_azp_plugin` only ever called `clearCaptionMotion`, which was harmless while `apply` was
    fake (nothing real to clear) but became a real regression once `apply` started writing genuine
    filters: a shader applied via the assistant could no longer be removed via the assistant. Added
    `DesktopAzpAssetApplier.remove` (mirroring the app's `AzpAssetApplier.remove`, which already
    existed) and wired it into `clearAzpPlugin`.
  Noted but **not** fixed here, since it predates this PR and isn't part of what it touched: the
  app-side `AzpAssetApplier.apply`/`AzpPluginApplier.apply` (Android's own `apply_azp_plugin` path)
  has the identical missing clip-type gate — applying a shader/LUT to a non-video clip via the
  assistant on Android silently "succeeds" too. Worth its own fix; deliberately out of scope of an
  audit of *this* PR's diff.
- **Fixed: the Image effects section was missing `style` (3 of 4 documented fields, not 4).**
  SETTINGS.md/MODELS.md both describe a fourth, custom-path-only slot; `apply_image_effect`'s own
  tool schema advertises `style` as a valid effect. It resolved to nowhere in the UI, so the tool's
  own error message pointed at a section that didn't have the field it named. Added (no picker,
  since `STYLE` has no recommended catalog — matches MODELS.md).
- **Fixed: the Vosk `speechModelPath` field got the "settings-first" treatment without the
  existence check** the same PR added to `ModelResolver` for every *other* model path — a stale,
  restored, or externally-cleared path made `transcribe_clip` route into a Vosk `Model()` constructor
  call that could only fail, with no fallback to a perfectly good configured OpenAI key. Added the
  same `File(path).exists()` guard.
- **Fixed: a stale code comment contradicted the line directly under it.** `NleScreen.kt`'s
  `agentModelPath` comment said "not a settings field" — true before this PR, false as of the same
  diff that edited the line below it to read `settings.agentModelPath`. Corrected.
- **Softened: an unverifiable claim in the new `DesktopMcpTools.kt` comment** ("`targetApps` is empty
  on virtually every real package") — asserted with nothing in this repo (no catalog, no fixture) able
  to confirm or refute it. Left the constant (there's no better option today) but the comment now says
  plainly that this is unverified from here rather than presenting it as a measured fact.

## Store: conform to the published web-handoff spec, and say where an install went (2026-08-01)

Two things, from catching up with azphalt upstream.

**The deep link's contract is now a real spec.** `spec/web-handoff.md` landed (status: Proposed) and the
`azphalt://install?id=&version=` shape Guillotine already implements matches it. Three deltas needed
closing:

- **`targetApps` is now enforced.** § Host obligations (5) makes it a MUST: a host absent from a
  *non-empty* `targetApps` must refuse and say so. Guillotine parsed the field but never checked it at
  install — which barely mattered while the store app was the only route, since it filters on the `app`
  browse extra, but a deep link names a package with nothing in between. `AzpHandoffInstaller` now returns
  `WrongHost` (naming the hosts it *is* for), and refuses **before** the trust prompt, so the user is
  never asked to vouch for a publisher on something that was never going to run here. **This takes
  something away**, and the first draft of this entry got that wrong: asset packages scoped elsewhere were
  already invisible (`AzpInstalledUi.list` filters on the same field), but *motion* packages were not —
  `KineticTypographyPicker.listInstalled` walks every `.azp` on disk with no host filter, so a wrong-host
  caption animation used to install and work fine. It no longer installs. The spec asks for exactly that,
  but it is a regression for anyone relying on it.
- **`repo` stays ignored, now on purpose.** § Which repository says a host MUST NOT fetch from a
  repository it doesn't already trust and that ignoring the parameter entirely is conforming. Documented
  and tested as a deliberate choice rather than an omission.
- **The media type is settled.** `spec/package-format.md` § Media type now makes
  `application/vnd.azphalt.package` the only normative type and `application/x-azphalt` a *deprecated
  alias* — a server MUST NOT send it, a client SHOULD accept it. Both filters stay, since accepting the
  alias is what the spec asks of a client and it costs nothing. Note the flagship registry has **already
  switched**: a download from azphalt.store returns `vnd.azphalt.package` as of 2026-08-01. An earlier
  draft of this work claimed the opposite and cited a `curl` that had not been re-run — the alias is kept
  for servers that haven't caught up, not because this one hasn't.

**And the thing nothing outside a host can answer: telling the user what they just got.** The spec touches
this only obliquely — § Open questions' **Return path** bullet is about the *storefront* never learning
whether an install happened, and notes in passing that state reporting "covers the statistic but not *show
the user what they just installed*". The in-app half is ours alone, because only the app knows what its
own panels are called.

Guillotine used to answer with a Toast that said "select a clip, then reopen the store" — fleeting, and
*wrong for most package kinds*. The destination depends entirely on the payload: a shader or LUT gets its
own section in the **Clip Properties** panel; a `motion` package appears under **Kinetic type** on a
selected caption; an on-device model isn't wired in by this path at all and needs Settings → Advanced →
Install AI model (which picks the `.azp` file, so a link-installed model has no file to point it at); an
unrecognised asset type is listed but has no render path; and a `code`/`app`/`mcp`/`pack` package has no
surface in this build. `AzpInstallSurfaces` (`:shared`, unit-tested) derives that from the manifest, and a
persistent dialog states what happened, where it lives, and whether the signature actually verified.

Two traps worth recording, both caught in review after the first attempt shipped them:

- **Do not tell a user to look for "Extensions".** `AzpAssetContribution` declares
  `override val title = "Extensions"`, but `ClipPanelHost` never reads `title` — it calls `Content`
  directly, and each section is drawn by `ClipPanelSection(panel.packageName)`. The string is never
  rendered anywhere. "Kinetic type" *is* real (`ClipTools.kt` passes it to `ClipPanelSection`), which is
  what made the wrong one look plausible. Either delete the dead `title` or make the host render it.
- **`AzpAssetApplier`'s "needs the extension runtime" message is misleading for motion.** A `motion`
  package applied while a *video* clip is selected falls through to the asset path, lands on
  `RenderKind.OTHER`, and gets told the runtime isn't wired — when the motion runtime is shipped and
  working; the user simply had the wrong clip selected. The dialog no longer repeats that message.

**Still open:**

- ~~**`compat` is parsed but never evaluated.**~~ **Done (2026-08-01):** `AzpCompat` implements the
  `0.1` grammar (optional comparator defaulting to `>=`, `MAJOR[.MINOR[.PATCH]]`, omitted parts zero) and
  `AzpHandoffInstaller` refuses an unsatisfied one as `Incompatible`, before the trust prompt for the same
  reason `WrongHost` is. A comparator *outside* the grammar (`^`, `~`, ranges, unions, prereleases)
  returns null rather than false and does **not** refuse: unrecognised syntax is not evidence of
  incompatibility, and failing closed there would reject packages a later spec legitimises.
  `AzpCompat.HOST_API_VERSION` is `0.1`, declared for the first time — Guillotine had no host API version
  constant at all.
- **`kind` and `mediaDomains` still aren't gated.** `kind` is deliberately surfaced rather than refused
  (`AzpInstallSurfaces` tells the user when a kind has no consumer here, which beats refusing a package
  someone installed knowing it awaits a runtime). `mediaDomains` **cannot** be checked at install: it is
  not a manifest field at all, only a repository-summary field, so a host verifying raw bytes has nothing
  to read. Obligation 5 is met for `targetApps` and `compat`, by design for `kind`, and unmeetable for
  `mediaDomains` without a spec change.
- **State reporting: § 3.1 done, § 3.2 and § 4 deliberately not.** (2026-08-01)
  - ✅ **§ 3.1, the inventory on a browse request.** `AzpStateReport` builds the spec's
    `{"entries":[…]}` document from the installed packages and `AzphaltStoreHandoff.browseIntent`
    attaches it as `azphalt.extra.INVENTORY`, so the store app's buttons can read *Open*/*Update*
    instead of *Get* on everything. Only the host knows this — acquisition and installation are separate
    events, and a store app that hands over bytes never learns what became of them. Guillotine reports
    `active` and only `active`: § 1 distinguishes it from `installed` (present but switched off) and
    Guillotine has no disabled state to report. The Binder caps (500 entries, 256 KiB) are enforced
    before the extra is attached, because an oversized extra throws in the *caller*.
  - ⛔ **§ 3.2, the state `ContentProvider`.** Only helps when the store app is opened standalone, and it
    costs an exported provider plus a new permission. Worth doing, but it is added attack surface for a
    secondary case and should be a deliberate decision rather than a side effect of this pass.
  - ⛔ **§ 4, the aggregate channel.** This one is a *new outbound network call* reporting what the user
    installed. The spec is careful (opaque single-use report tokens, no identifiers, explicitly optional,
    and a host that reports nothing is fully conforming) — but Guillotine's stated invariant is that any
    cloud path is **opt-in**, and defaulting install reporting to on would break that promise to make a
    publisher's counter nicer. It needs a settings toggle and the store app's
    `azphalt.extra.REPORT_TOKEN` plumbing, and the toggle is a product decision, not a refactor.
- ~~**The AI-model path still doesn't converge.**~~ **Done (2026-08-01):** the store/file/deep-link route
  now runs `AzpModelInstall` itself when the package carries model assets, writing into the same
  `filesDir/azp-models` that `ModelResolver` scans — so the model is actually in use instead of sitting
  inert in the extensions dir. Trust is not re-prompted: the gate already ran on those exact bytes moments
  earlier and the user answered it, and there is no dialog in flight to answer a second one with. Download
  progress for `remoteUrl` weights shows in the same busy dialog Settings uses. The notice now reports how
  many models installed and how many matched a settings slot, instead of telling the user to re-install
  from a file a deep-link install never gave them.
- **Still no device test of the deep link**, and azphalt.store still doesn't emit `azphalt://install`.

## Store: the `azphalt://install` deep link, and a MIME type that was simply wrong (2026-07-31)

The entry below closed the *file* half of the web-storefront gap: a `.azp` downloaded from azphalt.store
opens into Guillotine. It left the harder half open — the storefront's **Install · Free** button, which
still dead-ends at "install it from any Azphalt-conforming host". A web page can't push bytes into an
Android app, so it has to hand over a *name* and let the host fetch. That's now built, to this contract:

    azphalt://install?id=<packageId>&version=<version>

`version` optional (absent ⇒ resolve `latest` from `GET /packages/{id}`). Host-agnostic on purpose: any
conforming host claims `azphalt://`, Android shows a chooser if several are installed, and no package
name appears anywhere in the scheme. `AzpInstallLink` (`:shared`, pure JVM, unit-tested) parses and
*validates* it — `id` and `version` go into a URL path, and a link arrives from a web page, so both are
pattern-checked and `..` refused rather than merely escaped. `AzphaltRegistry` is the download half only;
browsing stays delegated to the store app, per the 2026-07-28 entry below. `AzpExternalOpen` grew a sealed
`Incoming` (file URI *or* link) rather than a second parallel flow, and both still converge on the one
`AzpHandoffInstaller` path — a link names a package, it doesn't vouch for one. The user confirms before
anything downloads.

Also fixed a real bug found while in here: the manifest registered Guillotine as an opener for
`application/vnd.azphalt.package`, but the registry served **`application/x-azphalt`**.
**Correction (2026-08-01, see the entry at the top of this file):** both halves of that parenthetical
have since stopped being true — azphalt.store now returns the normative `vnd.azphalt.package`, and
`spec/repository-api.md` § Download Package now cross-references `package-format.md` § Media type instead
of naming `x-azphalt`. The extra filter is kept because the spec asks clients to accept the deprecated
alias, not because this registry sends it. So a browser download never
matched the explicit-type filter at all — it only ever matched via the `*.azp` octet-stream/zip fallback,
and only when the browser happened to label it that way. Both types are now declared on the VIEW and SEND
filters.

**Still open, and still `HereLiesAz/azphalt`'s, not ours:**

- **The storefront has to emit the link.** Nothing on azphalt.store produces an `azphalt://install` URL
  yet, so this route is unreachable from the web until it does. A parallel session is speccing that side
  against the same contract.
- **Paid packages stop at the download.** The registry answers 401/402 for a paid package and Guillotine
  says so honestly ("acquire it in the Store app") rather than pretending the network failed. Implementing
  azphalt's entitlement tokens is deliberately out of scope here.
- **v1 downloads from the flagship registry only.** A caller-supplied repository URL is ignored on
  purpose — a deep link from a web page must not be able to point a host at an arbitrary download host.
  Removable once the spec says how a host decides some *other* repository is trustworthy; the
  `/.well-known/azphalt-repository.json` trust bootstrap is the obvious hook, but its `signingKeys` field
  still isn't populated (see `AzphaltTrust.FLAGSHIP_SIGNING_KEY`).
- **Untested on a device.** The parse and download halves are unit-tested and the app compiles, but no
  device or emulator was available to follow a real link end to end.

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
  its own text defers. *(Host half built — see the entry above; the storefront still has to emit it.)*

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
- ~~**Quality/FPS settings not wired into export**~~ — **Done (2026-08-01):** both are applied in
  `VideoEffects.geometry()`, which is where the other project-level settings (crop, aspect ratio) already
  land. `quality` becomes `Presentation.createForHeight(Quality.targetHeight)`, applied *after* the
  aspect-ratio presentation so it resizes the letterboxed frame and the ratio survives; `fps` becomes a
  `FrameDropEffect`. **Caveat worth keeping:** frame drop can only *cap* the rate — Media3 discards
  frames and cannot synthesise them — so selecting 60 fps on 30 fps source is a no-op, not interpolation.
  Bitrate is still unconfigured (`DefaultEncoderFactory` defaults apply); that would need
  `VideoEncoderSettings` and a target worth defending, so it is deliberately not guessed at here.
- ~~**AI edit segments play through in preview**~~ — **Fixed (2026-08-01).** It was not deliberate: a
  preview that doesn't show what will be rendered isn't a preview. `TimelineMath.previewSourceTimeMs`
  advances past any REMOVE the playhead lands in, and `syncPosition` uses it for both scrubbing and
  drift correction. Drift polling tightens from 400 ms to 100 ms on clips that actually have removes,
  because at 400 ms a cut is visible before the correction lands. Clips without edits are untouched, on
  both the maths and the polling.
- ~~**Project-level crop not shown in preview**~~ — **Fixed (2026-08-01).** Also not deliberate.
  `PreviewGeometry.forCrop` returns the scale-and-translate that makes the cropped region fill the frame,
  and the preview applies it to the **video layers only** — matching export's order, where `geometry()`
  is a per-clip video effect and captions are overlays composited onto the result. Suppressed while the
  crop tool is open, where the user needs to see what they're cutting away. A degenerate crop returns
  null and draws the frame uncropped rather than blanking the preview.

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
  GitHub Releases on launch and offer to download+run the installer). ~~The real remaining gap: no
  checksum/signature verification of the downloaded installer before launching it.~~
  **Checksum done (2026-08-01):** `UpdateChecker.verify` checks the downloaded file's size always and
  its SHA-256 when the release publishes a `digest`, and both updaters — desktop *and* Android, which
  had the identical gap in front of the package installer — refuse and delete a file that fails.
  `UpdateVerification` distinguishes `Verified` from `SizeOnly` ("no digest published") so the UI can
  never call an unchecked download verified. **Signature verification is still open** and is a different
  problem: the digest arrives over the same API connection as the download URL, so it proves the bytes
  are the ones GitHub meant to serve, not that they are ours. That needs the signing keys the
  notarization item above is already blocked on.
- **On-device ML on desktop** — the ONNX-Runtime-for-JVM foundation has landed: stem separation
  (Spleeter), speech captions (Vosk), audio sync, and the color/LUT render all run on-device on
  desktop. The remaining on-device gap is the **vision / face / speech-model tools** (image
  labeling, face detect/segment, Whisper ASR, TTS, diarization, VLM captioning). Each needs a
  desktop ONNX model wired the same way as stems: a model path in Settings + an inference helper.
  `search_clips` is the first wired (ONNX ImageNet labeler); the rest return an honest "needs a
  model" stub until their model is bundled/pointed at. Cloud BYO still works for all.
