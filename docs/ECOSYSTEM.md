# Guillotine Extensibility & Ecosystem

Guillotine is built to take **standard, already-existing formats** for presets, filters, effects, and
plugins — not a bespoke Guillotine-only format nobody else supports. If you've made a LUT for DaVinci
Resolve, a transition for [gl-transitions](https://gl-transitions.com/), or an MCP tool for Claude,
the goal is that it drops straight into Guillotine.

The invariant holds throughout: **your video and audio never leave the device.** Extensions run
on-device (GL shaders, LUTs, native filters) or, for the MCP plugin protocol, exchange only *text*
with a controller — never your media.

Status legend: ✅ shipped · 🛠 in progress · 🗺 planned.

**Applying these in the app:** the manual's [Advanced looks](MANUAL.md#6-advanced-looks) walks the UI,
and the MCP tools that apply them (`apply_lut`, `apply_shader` / `list_shader_params`,
`apply_ffmpeg_filter`, `apply_transition`, …) are specified in [TOOLS.md](TOOLS.md).

---

## 1. LUTs — `.cube` 3D color grades ✅

The universal color-grade interchange format, exported by DaVinci Resolve, Photoshop/Camera Raw, and
shared in countless free LUT packs.

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

- ✅ **Load & verify (job #1).** [`AzpPackage`](../shared/src/main/kotlin/com/hereliesaz/guillotine/azphalt/AzpPackage.kt)
  opens a `.azp`, rejects unsafe paths, parses the manifest, and verifies every payload file against its
  SHA-256 digest — on-device, pure-JVM, mirroring `@azphalt/azp` so a package built by the reference
  tools loads unchanged. Signature (Ed25519) enforcement follows once azphalt's trust model lands.
- ⏳ **Capabilities, WASM substrate, UI schema (jobs #2–#6).** Running extension code on a WASM sandbox
  (QuickJS-in-WASM for JS), granting least-privilege capabilities, and rendering the declarative UI
  schema natively in Compose are the remaining work to become a full **conforming host**.

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
