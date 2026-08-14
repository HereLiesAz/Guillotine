# Guillotine Extensibility & Ecosystem

Guillotine is built to take **standard, already-existing formats** for presets, filters, effects, and
plugins — not a bespoke Guillotine-only format nobody else supports. If you've already made a LUT in a
colour-grading tool, a transition for [gl-transitions](https://gl-transitions.com/), or an MCP tool
for Claude, the goal is that it drops straight into Guillotine.

The invariant holds throughout: **your video and audio never leave the device.** Extensions run
on-device (GL shaders, LUTs, native filters) or, for the MCP plugin protocol, exchange only *text*
with a controller — never your media.

Status legend: ✅ shipped · 🛠 in progress · 🗺 planned.

**Applying these in the app:** the manual's [Advanced looks](MANUAL.md#6-advanced-looks) walks the UI,
and the MCP tools that apply them (`apply_lut`, `apply_shader` / `list_shader_params`,
`apply_ffmpeg_filter`, `apply_transition`, …) are specified in [TOOLS.md](TOOLS.md).

---

## 1. LUTs — `.cube` 3D color grades ✅

The universal color-grade interchange format, exported by every major colour-grading and raw photo
editor and shared in countless free LUT packs.

- **Drop-in:** clip → **Filters → LUT → Pick .cube**. Any standard 3D `.cube` (sizes 2–129,
  `DOMAIN_MIN/MAX` respected) grades the clip in **both live preview and export** — WYSIWYG.
- **AI:** `apply_lut(clip_id, path)` / `clear_lut(clip_id)` MCP tools — see [TOOLS.md](TOOLS.md).
- **How it works:** [`CubeLut`](../shared/src/main/kotlin/com/hereliesaz/guillotine/media/CubeLut.kt)
  (pure-Kotlin, shared) parses the file;
  [`LutCube`](../app/src/main/java/com/hereliesaz/guillotine/media/LutCube.kt) builds a Media3
  `SingleColorLut` GL effect (`cube[R][G][B]`, ARGB), applied last in
  [`VideoEffects`](../app/src/main/java/com/hereliesaz/guillotine/media/VideoEffects.kt) so it grades
  the adjusted picture.
- **Author a LUT:** any tool that exports `.cube` works. 1D `.cube` LUTs are rejected (use a 3D LUT).

---

## 2. GLSL / ISF shader effects ✅ (adjustable) · clip-to-clip transitions ✅ (FFmpeg xfade)

Two widely-used open GLSL standards:

- **[ISF](https://isf.video/) (Interactive Shader Format):** a JSON-header-over-GLSL convention for
  per-clip effect shaders with declared `INPUTS`, used by VDMX, Resolume, and others.
- **[GL Transitions](https://gl-transitions.com/):** a community library of *transition* shaders, each a
  `vec4 transition(vec2 uv)` sampling both `getFromColor` and `getToColor`.

**Shipped — single-input shader effects.** Drop a standard **`.isf`** shader (or a raw `.fs`/`.glsl`
fragment) onto a clip via **Filters → Shader**, or the `apply_shader` / `clear_shader` MCP tools. It
runs on every frame in **both preview and export**.
[`GlslShader`](../shared/src/main/kotlin/com/hereliesaz/guillotine/media/GlslShader.kt) (shared, pure
Kotlin) parses the ISF `/*{…}*/` header, maps `INPUTS` to uniforms, and rewrites ISF built-ins
(`IMG_THIS_PIXEL`, `IMG_NORM_PIXEL`, `isf_FragNormCoord`, `RENDERSIZE`, `TIME`) to Media3's conventions;
[`GlslEffect`](../app/src/main/java/com/hereliesaz/guillotine/media/GlslEffect.kt) is a Media3
`BaseGlShaderProgram` (1-in/1-out). **Supported subset:** single-pass, single-image ISF filters
(`float`/`bool`/`long`/`color`/`point2D`/`event` inputs) and raw single-input fragments. A shader's
scalar **`FLOAT` inputs are adjustable, not fixed** — they surface as **sliders** in the Filters →
Shader popup, and over MCP via `list_shader_params` (read the inputs) and `apply_shader`'s `params` map
(override them); see [TOOLS.md](TOOLS.md). **Rejected** (Media3's per-clip effect is 1-in/1-out):
multi-pass, persistent/feedback buffers, audio inputs, and multiple image inputs.

**Transitions between clips — shipped via FFmpeg `xfade` ✅.** True gl-transition *warps* sample two
frames per pixel, which Media3's per-clip effect chain can't express (single-input; `DefaultVideoCompositor`
only does fixed alpha-over). Rather than rewrite the render core, transitions are delivered through
FFmpeg's **`xfade`** filter, which ships ~50 GL-style transitions: `apply_transition(from_clip_id,
to_clip_id, type, duration_sec)` (MCP) bakes the two clips into one transitioned clip — `fade`,
`wipeleft/right/up/down`, `slide*`, `circleopen/close`, `dissolve`, `pixelize`, `radial`, `smoothleft`,
`distance`, and more. Runs on-device (requires the same ffmpeg binary as §4). A Media3-native, live-preview
compositor for the *exact* gl-transitions GLSL catalog (custom `VideoCompositor` + `CompositionPlayer`)
remains a possible follow-up.

---

## 3. MCP plugin protocol — AI-drivable tools 🛠 (foundation ✅ · documented in [PLUGINS.md](PLUGINS.md) + [TOOLS.md](TOOLS.md))

Guillotine's editor is already a standard **[Model Context Protocol](https://modelcontextprotocol.io)**
server (JSON-RPC 2.0, protocol `2024-11-05`, `tools/list` + `tools/call`, bearer auth on `/mcp`:6274):
every capability (cut, filter, LUT, denoise, generate, …) is an MCP tool, and controller LLMs drive the
editor purely through those tools — exchanging **text only**, never media. **See
[PLUGINS.md](PLUGINS.md)** for the full protocol, how to connect a client, user-defined tool packs, and
the draft distributable-manifest proposal, and **[TOOLS.md](TOOLS.md)** for the complete tool catalog.

Already shipped:

- **Embedded MCP server.** The app serves `/mcp` over HTTP on port **6274**, gated by a bearer token
  (Settings → Advanced). Any MCP client — Claude Desktop, a script, another app — can list and call
  the editor's tools. See [`McpServer`]/[`McpAuth`](../app/src/main/java/com/hereliesaz/guillotine/mcp/McpAuth.kt).
- **Remote relay.** [`tools/mcp-relay`](../tools) + a Cloudflare Worker bridges the on-device server to
  a remote MCP client without exposing the device, using the same token.
- **User-defined tools.** `create_user_tool(name, description)` / `run_user_tool(name, clip_id)` let a
  user (or an LLM) mint a named macro over existing tools, persisted via
  [`UserToolStore`](../app/src/main/java/com/hereliesaz/guillotine/data/UserToolStore.kt) and shared in
  the settings backup bundle.

**Formalizing the protocol (in progress).** The tool catalog is now published as **[TOOLS.md](TOOLS.md)**
(generated from the JSON tool definitions this build emits from `McpTools`). Still ahead: a stable
versioned tool namespace and a plugin manifest so third-party MCP tool packs can be discovered/installed.
Until those land, point any MCP client at `/mcp` with the token — the live `tools/list` catalog is
self-describing.

---

## 3b. azphalt — the portable extension standard 📦 (adopting; loader ✅, host runtime ahead)

Beyond MCP (which drives the *editor* as text), Guillotine is adopting **[azphalt](https://github.com/HereLiesAz/azphalt)** —
a *vendor-neutral*, MIT-licensed **portable extension standard**: write a brush, filter, LUT, or shader
once as an `.azp` package and it runs in **any** conforming host, not just Guillotine. The point is the
opposite of a walled plugin store — extensions travel, and any app (or you) can run its own registry.
It's a separate repo (the `.azp` format, a TypeScript SDK, importers that normalize
`.abr`/`.cube`/… into `.azp`, a reference runtime, an open registry, and a consignment storefront).

**Where Guillotine is on the [adoption path](https://github.com/HereLiesAz/azphalt/blob/main/docs/ADOPTION.md):**

- ✅ **Load, verify & trust (job #1).** [`AzpPackage`](../shared/src/main/kotlin/com/hereliesaz/guillotine/azphalt/AzpPackage.kt)
  opens a `.azp`, rejects unsafe paths, parses the manifest, and verifies every payload file against its
  SHA-256 digest — on-device, pure-JVM, mirroring `@azphalt/azp` so a package built by the reference
  tools loads unchanged. **Signing is now enforced too:** `signatureStatus` verifies the detached
  Ed25519 `signature.json` over the manifest, and `verifyTrust` decides identity against a host trust
  store — directly, or through a registry counter-signature chain (web of trust). Unsigned-but-valid
  packages have integrity, not provenance (surfaced as a warning, per the spec), never silently trusted.
- ✅ **Deliver AI models over azphalt.** azphalt isn't only for brushes and LUTs — a `.azp` can ship an
  **on-device AI model** (`onnx` / `tflite` / `litert` / `sherpa-bundle`), bundled or referenced by
  `remoteUrl` + `checksum` for large weights. [`AzpModelInstaller`](../shared/src/main/kotlin/com/hereliesaz/guillotine/azphalt/AzpModelInstaller.kt)
  routes each model to the right settings slot by its `role`, and
  [`AzpModelInstall`](../shared/src/main/kotlin/com/hereliesaz/guillotine/azphalt/AzpModelInstall.kt)
  downloads (resumable), **verifies the SHA-256 before wiring it in**, and writes it under the app's
  models directory. **Settings → Advanced → Install AI model (.azp)** drives it on both platforms; an
  unsigned package is installed only after an explicit trust warning. So a speech, segmentation, or
  labeling model can be delivered and adopted without a new app build.
- ✅ **Tracks the current manifest spec (format `0.1`).** [`AzpManifest`](../shared/src/main/kotlin/com/hereliesaz/guillotine/azphalt/AzpManifest.kt)
  models the latest `spec/extension-manifest.md`: the `app` (companion-app) and `mcp` (MCP-server)
  package kinds alongside `asset`/`code`/`mixed`, plus `targetApps` (host scoping) and a `preview`
  store-card still/clip.
- ✅ **Builds its own catalog browser again (own store primary; delegated acquisition as a fallback).**
  Guillotine briefly delegated all browsing to whichever Azphalt Store app was installed (2026-07-28),
  reasoning that a host shouldn't have to build a storefront if it doesn't want to. That call was right
  too early, not right permanently: it was made before the catalog and Guillotine's own trust/install
  machinery were mature enough to justify the maintenance cost of a browser, and both have grown well
  past that bar since. As of 2026-08-12,
  [`AzphaltStoreScreen`](../app/src/main/java/com/hereliesaz/guillotine/ui/AzphaltStoreScreen.kt) (Android)
  and `DesktopAzphaltStoreScreen` (desktop, which had no Store entry point at all before this) both show
  Guillotine's own catalog browser — search, category chips, an Install button per package — fetched via
  [`AzphaltRegistry.browseAll`](../shared/src/main/kotlin/com/hereliesaz/guillotine/azphalt/AzphaltRegistry.kt).
  The Azphalt Store app's `store.azphalt.action.BROWSE` handoff (azphalt `spec/store-app.md`) and the web
  storefront both still work — one tap away from the browser's own overflow menu — but as a secondary
  route now, not the only one. A store app is still a convenience, never a trust anchor, however a
  package was found: Guillotine re-verifies every byte itself through
  [`AzpHandoffInstaller`](../shared/src/main/kotlin/com/hereliesaz/guillotine/azphalt/AzpHandoffInstaller.kt)
  (integrity, signature, and publisher continuity) before writing it into the extensions dir, exactly the
  same whether the bytes came from the in-app browser, a store app's handoff, or a direct download.
  **The web storefront now has a way in too:** Guillotine claims the `azphalt://install?id=…&version=…`
  deep link, so the store page's **Install** button can hand a package *name* to whatever conforming host
  is on the device (the scheme is host-agnostic by design — no package name is baked in, and Android shows
  a chooser when several hosts are installed). [`AzpInstallLink`](../shared/src/main/kotlin/com/hereliesaz/guillotine/azphalt/AzpInstallLink.kt)
  parses and *validates* the link — a web page must not be able to steer a URL path — and
  [`AzphaltRegistry`](../shared/src/main/kotlin/com/hereliesaz/guillotine/azphalt/AzphaltRegistry.kt)
  fetches the named `.azp` from the flagship registry (download only; browsing stays delegated). The user
  confirms before anything downloads, and the bytes then run the same `AzpHandoffInstaller` gauntlet as
  every other route: a link names a package, it does not vouch for one.
  This route was built against a contract that has since been **published as `spec/web-handoff.md`**
  (status: Proposed). Guillotine meets its host obligations with one gap: it parses defensively, verifies
  integrity and signature, enforces publisher continuity, **refuses a package whose non-empty `targetApps`
  excludes this host**, refuses one whose `compat` the host's azphalt-API version doesn't satisfy
  (`AzpCompat.satisfies`, surfaced as `InstallResult.Incompatible`), and asks before installing. Obligation
  5 is only partly met, though: besides `targetApps` and `compat` it also names `kind` and `mediaDomains`,
  neither of which is gated at install — `kind` is never checked, and `mediaDomains` isn't a manifest
  field at all (it's a repository-summary field, so a host verifying raw bytes has nothing to read). The spec's optional `repo` parameter is ignored, which *is* explicitly
  conforming: a host MUST NOT fetch from a repository it doesn't already trust, and how a host *starts*
  trusting one is still an open question upstream.
- ✅ **Opens a `.azp` handed in from outside the app.** `spec/store-app.md` specifies only the Android
  app-to-app handoff and says outright that the web case is left unspecified — so the web storefront can
  tell a visitor to install a package "from any Azphalt-conforming host" but has no way to hand it to
  one. Guillotine closes the host half: it registers as an opener for `.azp` packages (VIEW on
  `application/vnd.azphalt.package` **and `application/x-azphalt`** — the latter now settled upstream as a
  *deprecated alias* (`spec/package-format.md` § Media type): a server must not send it, a client should
  still accept it. Guillotine accepts it for servers that haven't caught up — azphalt.store itself has,
  and returns the normative type as of 2026-08-01 — plus `.azp`-suffixed octet-stream/zip
  downloads, and a SEND share-sheet route), so a package downloaded from azphalt.store in the browser,
  sitting in a file manager, or shared from another app opens straight into the editor's confirmation
  dialog — not straight into an install. The type is a routing hint only; nothing is ever trusted
  because of it, and being opened this way is not by itself the user asking to install anything: some
  *other* app decided to hand Guillotine this file, which is exactly as unsolicited as an
  `azphalt://install` deep link naming one. Both routes now ask before a byte is written — the file-open
  route used to call straight into install with no confirmation at all, the one gap in an otherwise
  identical treatment of "arrived from outside the app".
  [`AzpExternalOpen`](../app/src/main/java/com/hereliesaz/guillotine/azphalt/AzpExternalOpen.kt) carries
  it — a file URI or an install link, one sealed `Incoming` rather than two parallel flows — from
  `MainActivity` to `AzphaltStoreScreen`, which confirms with the user and then runs the identical
  `AzpHandoffInstaller` verification either way: bytes arriving with no trust anchor at all is precisely
  the case it was written for.
- ✅ **Says what was installed, and where to find it.** An install that succeeds and then can't be located
  is, from the user's side, indistinguishable from one that failed — and the destination is *different per
  package*: a shader gets its own section in the clip panel, named after the package (note
  `AzpAssetContribution`'s "Extensions" `title` is never rendered, and the panel's own heading depends on
  the active tool), a caption animation appears under **Kinetic type**, an on-device model needs
  Settings → Advanced → Install AI model to actually be wired in, and a `code`/`app`/`mcp` package has no
  surface in this build at all. So the answer is derived from
  the package rather than asserted: [`AzpInstallSurfaces`](../shared/src/main/kotlin/com/hereliesaz/guillotine/azphalt/AzpInstallSurfaces.kt)
  maps a manifest's payload to the surfaces it actually reaches, and the post-install dialog names them
  (replacing a Toast that vanished before it could be read). Nothing in the ecosystem can answer this for
  a host — `spec/web-handoff.md` § Open questions is about the *storefront* having no return path, and
  observes in passing that state reporting "covers the statistic but not *show the user what they just
  installed*" — because only the app knows what its own panels are called.
- ✅ **UI schema → native Compose (job #6).** An extension's declarative control panel
  (azphalt `spec/ui-schema.md`, `{ "controls": […] }` referenced by an asset's `ui`) is parsed by
  [`AzpUiSchema`](../shared/src/main/kotlin/com/hereliesaz/guillotine/azphalt/AzpUiSchema.kt) and rendered
  as native widgets by [`AzpUiSchemaControls`](../app/src/main/java/com/hereliesaz/guillotine/ui/AzpUiSchemaControls.kt),
  surfaced in the editor's clip-properties panel through the [`ClipPanelContribution`](../app/src/main/java/com/hereliesaz/guillotine/ui/ClipPanelContribution.kt)
  seam (built-in kinetic typography is the first consumer; installed asset packages that ship a `ui`
  render automatically). See **[PLUGIN_PANELS.md](PLUGIN_PANELS.md)**.
- ✅ **Apply asset extensions to media (asset-kind runtime).** For the extension types Guillotine already
  renders natively — a GLSL **shader**, a `.cube` **LUT** — an installed `.azp` is applied to the selected
  clip on-device ([`AzpAssetApplier`](../app/src/main/java/com/hereliesaz/guillotine/ui/AzpAssetApplier.kt)):
  its bytes are written into the clip's real render filters, so it takes effect in **both live preview and
  export**, and a shader package's UI-schema controls drive the clip's `shaderParams` live. No sandbox is
  needed — the asset is declarative data. This closes the loop end-to-end for asset extensions.
- ⏳ **Capabilities & WASM substrate for `code` extensions (jobs #2–#5).** The capability boundary and
  runtime seam are in place — [`AzpCodeRuntime`](../shared/src/main/kotlin/com/hereliesaz/guillotine/azphalt/AzpCodeRuntime.kt)
  enforces least-privilege grants (`missingGrants`) and the shipped runtime honestly refuses to execute
  rather than fake a result. The remaining piece is the sandbox itself: a WASM engine hosting
  QuickJS-in-WASM (`js`) or a module against the host ABI (`wasm`), to run arbitrary `code`-kind
  extensions. That's the last step to a full **conforming host**.

The fit is deliberate: azphalt's **never-list** (a host must never expose its engine, camera, sensors,
filesystem, or network to extensions) is the same on-device, least-authority boundary Guillotine already
enforces — so adopting it strengthens the privacy invariant rather than bending it.

---

## 4. Frei0r & FFmpeg filters — native video filters ✅ (advanced, bring-your-own ffmpeg)

- **FFmpeg `-vf` filtergraphs:** the ubiquitous filter-chain syntax.
- **[Frei0r](https://frei0r.dyne.org/):** the cross-application video-plugin API (`f0r_*` entry points),
  reached **through FFmpeg's `frei0r=<name>:<params>` filter** — so one path covers both ecosystems.

**Shipped — bake an FFmpeg/Frei0r filtergraph.** Point **Settings → AI Analyzer → FFmpeg filters** at an
`ffmpeg` executable, then `apply_ffmpeg_filter(clip_id, filter)` (MCP) bakes a standard `-vf` graph (e.g.
`hue=s=0, gblur=sigma=2`, or `frei0r=cartoon`) onto the clip and adds the result as a new clip.
[`FfmpegFilter`](../app/src/main/java/com/hereliesaz/guillotine/media/FfmpegFilter.kt) runs the process
on local files only — **on-device, nothing leaves the device**. This is a **bake-to-new-clip** step, not
a live filter (FFmpeg can't drive GL preview), and **requires the user to supply an ffmpeg binary**
(desktop-first; on Android, a bundled/downloaded ARM build) — so it's an optional advanced capability,
not a default mobile dependency. A live-filter (Media3-native) subset of common FFmpeg filters, and a
`:desktop` Frei0r host that loads `.so`/`.dll`/`.dylib` directly, are possible follow-ups.

---

## Contributing an extension

- **A LUT:** just export a `.cube` — it already works. Share packs anywhere.
- **A GL/ISF shader:** a standard single-input ISF (`.isf`) or raw `.fs`/`.glsl` fragment loads as-is
  today (§2), with its `FLOAT` inputs exposed as sliders.
- **A GL-Transition:** clip-to-clip transitions ship now via FFmpeg `xfade` (§2); a Media3-native
  compositor for the exact gl-transitions GLSL catalog is the remaining follow-up.
- **An MCP tool/plugin:** build against the `/mcp` surface today ([TOOLS.md](TOOLS.md) is the tool
  reference); the formal distributable manifest is still draft.
- **A Frei0r/FFmpeg filter:** works today (§4) — set an `ffmpeg` binary and bake a `-vf` graph (or
  `frei0r=name:params`); desktop-first.

Everything here is designed so the **existing worldwide libraries** of these formats are Guillotine's
ecosystem from day one — no waiting for a Guillotine-specific marketplace to fill up.
